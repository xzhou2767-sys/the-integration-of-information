package com.trae.info.server.service

import com.trae.info.core.api.AiSearchResponse
import com.trae.info.core.api.AnalyzeRequest
import com.trae.info.core.api.AnalyzeResponse
import com.trae.info.core.api.FeedResponse
import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.Domain
import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.SourcePlatform
import com.trae.info.core.model.SubscriptionPrefs
import com.trae.info.core.model.Taxonomy
import com.trae.info.server.ai.AiProviders
import com.trae.info.server.browser.BrowserFetchService
import com.trae.info.server.companion.CompanionIngest
import com.trae.info.server.config.AppConfig
import com.trae.info.server.config.ConfigStore
import com.trae.info.server.crawler.PlatformAdapter
import com.trae.info.server.crawler.PlatformAdapters
import com.trae.info.server.decision.AdmissionPolicy
import com.trae.info.server.decision.ContentRanker
import com.trae.info.server.decision.DedupService
import com.trae.info.server.decision.Verdict
import com.trae.info.server.proxy.ProxyPool
import com.trae.info.server.util.AtomicFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 内容调度核心：按用户订阅的细分类目与平台，并发抓取、聚合、排序，
 * 并提供 AI 领域/倾向分析与 AI 驱动自定对象搜索。
 * 分类体系与爬虫密钥支持运行时通过 [ConfigStore] 动态更新。
 */
class ContentService(
    private val cfg: AppConfig,
    private val pool: ProxyPool,
    private val browser: BrowserFetchService?,
    private val ai: AiProviders,
    private val store: ConfigStore,
    private val translation: TranslationService? = null
) {
    private val log = LoggerFactory.getLogger(ContentService::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val subFile = File(cfg.dataDir, "subscription.json")

    @Volatile
    private var adapters: Map<SourcePlatform, PlatformAdapter> = PlatformAdapters.build(cfg, pool, browser)

    @Volatile
    private var prefsInternal: SubscriptionPrefs = load()
    val prefs: SubscriptionPrefs get() = prefsInternal

    private val companionFile = File(cfg.dataDir, "companion-items.json")
    @Volatile
    private var companionItems: List<ContentItem> = loadCompanion()

    /** 带时间戳的缓存条目。 */
    @Serializable
    private data class CachedFeed(val items: List<ContentItem> = emptyList(), val at: Long = 0L)

    private val feedCacheFile = File(cfg.dataDir, "feed-cache.json")

    /** 后台抓取进程写入的缓存：categoryId -> 已抓取条目（含写入时间戳）。 */
    @Volatile
    private var feedCache: Map<String, CachedFeed> = loadFeedCache()

    /** 类目级 single-flight：并发 /feed 请求不重复触发聚合抓取。 */
    private val feedLocks = ConcurrentHashMap<String, Mutex>()

    /** Request-triggered cold fetches share a service-wide cap with other categories. */
    private val aggregateSemaphore = Semaphore(4)

    private fun load(): SubscriptionPrefs = runCatching {
        if (subFile.exists()) json.decodeFromString<SubscriptionPrefs>(subFile.readText())
        else SubscriptionPrefs()
    }.getOrElse { SubscriptionPrefs() }

    @Synchronized
    fun savePrefs(newPrefs: SubscriptionPrefs) {
        val saved = newPrefs.copy(updatedAt = System.currentTimeMillis())
        prefsInternal = saved
        runCatching { AtomicFiles.writeTextAtomically(subFile, json.encodeToString(saved)) }
    }

    /** 持久化 feed 缓存（原子写，后台一轮抓取结束后调用）。 */
    @Synchronized
    fun persistFeedCache() {
        runCatching {
            AtomicFiles.writeTextAtomically(feedCacheFile, json.encodeToString(feedCache))
        }
    }

    private fun loadFeedCache(): Map<String, CachedFeed> = runCatching {
        val text = AtomicFiles.readTextOrBackup(feedCacheFile) ?: return@runCatching emptyMap()
        json.decodeFromString<Map<String, CachedFeed>>(text)
    }.getOrElse { emptyMap() }

    /** 缓存是否新鲜：非空 6 小时；空结果（负缓存）5 分钟。 */
    private fun CachedFeed.isFresh(): Boolean {
        val ttl = if (items.isEmpty()) NEG_CACHE_TTL_MS else FEED_TTL_MS
        return System.currentTimeMillis() - at < ttl
    }

    /** 通道 B：伴侣脚本上报入库。 */
    @Synchronized
    fun mergeCompanion(ingest: CompanionIngest): Int {
        val items = ingest.items.map { item ->
            ContentItem(
                id = item.id,
                categoryId = ingest.categoryId,
                source = ingest.platformId,
                title = item.title,
                summary = item.summary,
                url = item.url,
                author = item.author,
                tags = listOf("伴侣脚本")
            )
        }
        companionItems = ContentPolicy.filterAndDeduplicate(companionItems + items)
            .sortedByDescending { it.publishedAt }
            .take(500)
        runCatching { AtomicFiles.writeTextAtomically(companionFile, json.encodeToString(companionItems)) }
        return items.size
    }

    private fun loadCompanion(): List<ContentItem> = runCatching {
        if (companionFile.exists()) json.decodeFromString<List<ContentItem>>(companionFile.readText())
        else emptyList()
    }.getOrElse { emptyList() }

    /** 爬虫密钥变更后重建适配器（云端管理界面保存后调用）。 */
    @Synchronized
    fun refreshAdapters() {
        adapters = PlatformAdapters.build(cfg, pool, browser)
        log.info("平台适配器已重建（爬虫密钥变更生效）")
    }

    /** 后台抓取进程写入缓存（供 /api/feed 即时返回，避免每次请求都触发实时抓取）。
     *  空结果移除旧缓存，防止失败抓取污染缓存导致信息流长期为空。 */
    @Synchronized
    fun cacheFeed(categoryId: String, items: List<ContentItem>) {
        val accepted = ContentPolicy.filterAndDeduplicate(items)
        feedCache = if (accepted.isEmpty()) feedCache - categoryId
        else feedCache + (categoryId to CachedFeed(accepted, System.currentTimeMillis()))
    }

    /**
     * 工作单元直抓：单平台 × 单类目，直接调用平台适配器（不走 [feed] 的缓存读路径，避免回环）。
     * 后台进程允许浏览器通道（allowBrowser=true）。失败容错返回空列表；
     * 仅协程真被取消（isActive=false）时上抛。
     */
    suspend fun fetchUnit(category: InfoCategory, platform: SourcePlatform, limit: Int): List<ContentItem> {
        val items = adapters[platform]?.fetch(category, limit, allowBrowser = true) ?: emptyList()
        return ContentPolicy.filterAndDeduplicate(items)
    }

    /** 类目下用户订阅已启用的平台（供抓取规划圈定工作单元）。 */
    fun enabledSourcesOf(category: InfoCategory): List<SourcePlatform> =
        category.sources.filter { it != SourcePlatform.WIKIPEDIA && prefsInternal.sourceEnabled(it) }

    /** 全局启用平台（订阅偏好中未被关闭的全部平台），供抓取规划求交集用。 */
    fun enabledSources(): List<SourcePlatform> =
        Taxonomy.platforms.filter { it != SourcePlatform.WIKIPEDIA && prefsInternal.sourceEnabled(it) }

    /** 清空抓取缓存（停止进程时可选调用）。 */
    @Synchronized
    fun clearCache() {
        feedCache = emptyMap()
    }

    /** metrics：各类目缓存条目数与总条目。 */
    @Synchronized
    fun cacheStats(): Map<String, Int> = mapOf(
        "cachedCategories" to feedCache.size,
        "cachedItems" to feedCache.values.sumOf { it.items.size }
    )

    /** 按类目聚合所有已订阅平台的内容；海外平台自动经云端代理；合并伴侣脚本上报。
     *  优先返回未过期缓存；缓存缺失时在类目锁内触发实时聚合（single-flight），
     *  请求路径禁用浏览器通道、空结果做 5 分钟负缓存，避免重复冷启动/重复付费。 */
    suspend fun feed(categoryId: String): FeedResponse {
        val category = store.categoryById(categoryId)
            ?: return FeedResponse(categoryId, emptyList(), proxied = false)

        val enabledSources = category.sources.filter { prefsInternal.sourceEnabled(it) }

        // single-flight：同类目并发请求只做一次聚合
        val lock = feedLocks.getOrPut(categoryId) { Mutex() }
        val fetched = lock.withLock {
            val cached = feedCache[categoryId]?.takeIf { it.isFresh() }
            if (cached != null) {
                log.debug("类目 {} 命中抓取缓存（{} 条）", categoryId, cached.items.size)
                cached.items
            } else {
                val agg = aggregate(category, enabledSources)
                // 内存态缓存（含空结果负缓存）；后台进程写入的持久缓存会覆盖它
                synchronized(this) {
                    feedCache = feedCache + (categoryId to CachedFeed(agg, System.currentTimeMillis()))
                }
                agg
            }
        }

        val companion = companionItems.filter { it.categoryId == categoryId }
        val baseItems = ContentPolicy.filterAndDeduplicate(fetched + companion)
            .sortedByDescending { it.publishedAt }
            .take(50)

        // 个性化：若用户已保存信息偏好总结，则按兴趣关键词重排并推导展示模式（解析结果已 memo）
        val prefSummary = store.get().preferenceSummary
        val personalized = prefSummary.isNotBlank()
        val pref = if (personalized) PreferenceEngine.parse(prefSummary) else null
        val displayMode = pref?.displayMode ?: "default"

        // 决策核心 V2（灰度开关 decisionCoreV2，默认关闭走旧链路）：
        // 统一准入 Decision（论文 90 天/动态 45 天窗唯一选择 + 未知日期 NEEDS_EVIDENCE 降权）
        // → 精确去重（DedupService）→ 偏好排序带评分解释（ContentRanker：负向词硬排除）
        val rankedItems: List<ContentItem> = if (cfg.decisionCoreV2) {
            val withDecisions = baseItems.map { it to AdmissionPolicy.decide(it) }
            // BACKGROUND_ONLY（超龄论文/静态知识）不进入动态流；NEEDS_EVIDENCE 保留但 freshness 0.3 排序降权
            val admitted = withDecisions.filter { it.second.verdict == Verdict.ACCEPT || it.second.verdict == Verdict.NEEDS_EVIDENCE }
                .map { it.first }
            val deduped = DedupService.dedup(admitted).take(50)
            val freshnessById = withDecisions.associate { (item, d) -> item.id to d.freshness }
            if (pref != null) {
                ContentRanker.rankWithExplanation(
                    deduped, pref.positiveKeywords, pref.negativeKeywords, freshnessById
                ).map { it.item }
            } else {
                deduped.sortedByDescending { it.publishedAt }
            }
        } else {
            if (pref != null) PreferenceEngine.rank(baseItems, pref) else baseItems
        }

        // 外文条目概要翻译：同步填充已缓存译题（零延迟），缺失项异步补翻（下次生效）
        val translated = translation?.decorate(rankedItems) ?: rankedItems
        translation?.translateMissingAsync(translated)

        return FeedResponse(
            categoryId = categoryId,
            items = translated,
            proxied = enabledSources.any { it.requiresProxy },
            personalized = personalized,
            displayMode = displayMode
        )
    }

    /** 类目当前缓存条目身份集合（供观测「新增唯一数」统计）。 */
    fun cachedIdentitiesOf(categoryId: String): Set<String> =
        synchronized(this) { feedCache[categoryId] }?.items?.map(ContentPolicy::identityOf)?.toSet() ?: emptySet()

    suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse {
        val categoryName = request.categoryId?.let { store.categoryById(it)?.name }
        return ai.analyze(request.mode, categoryName, request.text)
    }

    /**
     * AI 驱动自定对象搜索：
     * 1. AI 理解用户收集目的 → 提取关键词与检索策略；
     * 2. 以关键词构建临时类目，在全部平台并发抓取；
     * 3. 返回聚合结果与 AI 检索策略说明。
     */
    suspend fun aiSearch(goal: String): AiSearchResponse {
        val (keywords, reasoning) = ai.extractKeywords(goal)

        val tempCategory = InfoCategory(
            id = "ai-search-${System.currentTimeMillis()}",
            name = "AI 搜索 · ${goal.take(20)}",
            domain = Domain.PRACTICAL,
            group = "AI 自定搜索",
            description = goal,
            keywords = keywords,
            sources = Taxonomy.platforms
        )

        val platforms = enabledSources()
        val items = aggregate(tempCategory, platforms)

        // AI 搜索结果同样享受外文条目译题装饰（与 feed 路径一致）
        val translated = translation?.decorate(items) ?: items
        translation?.translateMissingAsync(translated)

        return AiSearchResponse(
            keywords = keywords,
            reasoning = reasoning,
            items = translated,
            proxied = platforms.any { it.requiresProxy }
        )
    }

    /** 并发抓取多平台并按发布时间降序取前 50 条。请求路径禁用浏览器通道（[allowBrowser]=false）。 */
    private suspend fun aggregate(
        category: InfoCategory,
        sources: List<SourcePlatform>
    ): List<ContentItem> = coroutineScope {
        sources.map { platform ->
            async {
                val adapter = adapters[platform] ?: return@async emptyList()
                runCatching { aggregateSemaphore.withPermit { adapter.fetch(category, allowBrowser = false) } }
                    .onFailure { log.warn("平台 {} 抓取失败：{}", platform.id, it.message) }
                    .getOrDefault(emptyList())
            }
        }.awaitAll()
    }.flatten().let(ContentPolicy::filterAndDeduplicate).sortedByDescending { it.publishedAt }.take(50)

    companion object {
        /** 正常抓取缓存有效期：6 小时。 */
        private const val FEED_TTL_MS = 6L * 60 * 60 * 1000
        /** 空结果负缓存有效期：5 分钟。 */
        private const val NEG_CACHE_TTL_MS = 5L * 60 * 1000

        fun create(
            cfg: AppConfig,
            ai: AiProviders,
            pool: ProxyPool,
            browser: BrowserFetchService?,
            store: ConfigStore,
            translation: TranslationService? = null
        ): ContentService = ContentService(cfg, pool, browser, ai, store, translation)
    }
}
