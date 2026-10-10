package com.trae.info.server.crawler

import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.SourcePlatform
import com.trae.info.server.browser.BrowserFetchService
import com.trae.info.server.browser.BrowserScraper
import com.trae.info.server.browser.BrowserScrapers
import com.trae.info.server.config.AppConfig
import com.trae.info.server.proxy.ProxyPool
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36"
private val htmlTagRegex = Regex("<[^>]+>")
private val adapterLog = LoggerFactory.getLogger("Adapters")

/**
 * 浏览器通道熔断器（进程内内存态）。
 * 某平台浏览器通道连续失败 [FAIL_THRESHOLD] 次即熔断 [OPEN_MS]，
 * 熔断期跳过该平台的浏览器冷启动（最贵的操作），直接走兜底；
 * 合法空结果（无会话/无匹配）不计失败、不熔断。
 */
object AdapterBreaker {
    private class State(@Volatile var failures: Int = 0, @Volatile var openedAt: Long = 0L)
    private val states = ConcurrentHashMap<SourcePlatform, State>()

    private const val FAIL_THRESHOLD = 3
    private const val OPEN_MS = 5L * 60 * 1000

    fun isOpen(p: SourcePlatform): Boolean {
        val s = states[p] ?: return false
        if (s.openedAt == 0L) return false
        return if (System.currentTimeMillis() - s.openedAt < OPEN_MS) true
        else { s.failures = 0; s.openedAt = 0L; false } // 半开：放一次试探
    }

    fun recordSuccess(p: SourcePlatform) {
        states[p]?.let { it.failures = 0; it.openedAt = 0L }
    }

    fun recordFailure(p: SourcePlatform) {
        val s = states.getOrPut(p) { State() }
        s.failures += 1
        if (s.failures >= FAIL_THRESHOLD && s.openedAt == 0L) {
            s.openedAt = System.currentTimeMillis()
            adapterLog.warn("平台 {} 浏览器通道连续失败 {} 次，熔断 {} 分钟", p.id, s.failures, OPEN_MS / 60000)
        }
    }

    /** metrics 快照 */
    fun snapshot(): Map<String, String> =
        states.entries.associate { (p, s) ->
            p.id to if (isOpen(p)) "OPEN(failures=${s.failures})" else "CLOSED(failures=${s.failures})"
        }
}

private fun isoToEpochMs(s: String?): Long = runCatching {
    OffsetDateTime.parse(s).toInstant().toEpochMilli()
}.getOrDefault(0L)

/**
 * RSS/Atom 日期宽容解析（P0 修复：此前 pubDate 用 ISO 解析器处理 RFC 1123 全部返回 0）。
 * 支持格式（按序尝试）：
 * 1. RFC 1123（RSS 2.0 pubDate 标准）：Thu, 18 Sep 2026 07:00:00 GMT
 * 2. ISO 8601 带偏移（Atom）：2026-09-18T07:00:00+08:00
 * 3. ISO 8601 无偏移（按 UTC 解释并记录不可信）：2026-09-18T07:00:00
 * 解析失败返回 0（未知日期，由准入策略按 NEEDS_EVIDENCE 处理，不伪装新内容）。
 */
private fun rssDateToEpochMs(s: String?): Long {
    if (s.isNullOrBlank()) return 0L
    val raw = s.trim()
    // RFC 1123（宽容冒号可选的时区写法）
    runCatching {
        return DateTimeFormatter.RFC_1123_DATE_TIME.parse(raw, OffsetDateTime::from).toInstant().toEpochMilli()
    }
    runCatching {
        return java.time.format.DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z")
            .parse(raw, OffsetDateTime::from).toInstant().toEpochMilli()
    }
    // ISO 8601 带偏移
    runCatching { return OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
    // ISO 8601 无偏移：按 UTC 解释（来源未声明时区，精度降级但日期可用）
    runCatching {
        return LocalDateTime.parse(raw).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    }
    // ISO 8601 纯日期
    runCatching {
        return java.time.LocalDate.parse(raw).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    return 0L
}

private fun JsonObject.str(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

/**
 * 各平台适配器装配。
 *
 * 接入路线：
 * - 官方 API：YouTube（Data API v3）、X/Twitter（API v2）、Reddit、抖音开放平台未开放检索故走检索引擎
 * - 公开接口：哔哩哔哩、中国大学MOOC（网页 RPC）、知乎（公开热榜 API + 关键词过滤）
 * - 检索引擎：抖音、小红书、Quora 无公开 API，经 SerpAPI 的 Google 站内检索间接获取真实链接
 *
 * 密钥缺失时对应平台优雅降级为空结果，不影响其余平台聚合。
 */
object PlatformAdapters {

    fun build(
        cfg: AppConfig,
        pool: ProxyPool,
        browser: BrowserFetchService?
    ) : Map<SourcePlatform, PlatformAdapter> = mapOf(
        // 降级链：公开 .json 主，登录态浏览器兜底（.json 被 Reddit 网络策略拦截时）
        SourcePlatform.REDDIT to CompositeAdapter(
            SourcePlatform.REDDIT,
            primary = RedditAdapter(pool),
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.REDDIT],
            fallback = SerpSearchAdapter(
                SourcePlatform.REDDIT, "site:reddit.com/r", cfg.serpApiKey, pool
            )
        ),
        // 降级链：公开 API 主（数据中心 IP 易被 412 风控），登录态浏览器兜底
        SourcePlatform.BILIBILI to CompositeAdapter(
            SourcePlatform.BILIBILI,
            primary = BilibiliAdapter(pool),
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.BILIBILI],
            fallback = SerpSearchAdapter(
                SourcePlatform.BILIBILI, "site:bilibili.com/video", cfg.serpApiKey, pool
            )
        ),
        // 降级链：公开 RPC 主，浏览器登录态兜底
        SourcePlatform.ICUOOC to CompositeAdapter(
            SourcePlatform.ICUOOC,
            primary = ICourseAdapter(pool),
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.ICUOOC],
            fallback = null
        ),
        // 降级链：公开热榜主，浏览器登录态搜索兜底
        SourcePlatform.ZHIHU to CompositeAdapter(
            SourcePlatform.ZHIHU,
            primary = ZhihuAdapter(pool),
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.ZHIHU],
            fallback = null
        ),
        // 降级链：浏览器登录态主，SerpAPI 兜底
        SourcePlatform.DOUYIN to CompositeAdapter(
            SourcePlatform.DOUYIN,
            primary = null,
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.DOUYIN],
            fallback = SerpSearchAdapter(
                SourcePlatform.DOUYIN, "site:douyin.com/video", cfg.serpApiKey, pool
            )
        ),
        SourcePlatform.XIAOHONGSHU to CompositeAdapter(
            SourcePlatform.XIAOHONGSHU,
            primary = null,
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.XIAOHONGSHU],
            fallback = SerpSearchAdapter(
                SourcePlatform.XIAOHONGSHU, "site:xiaohongshu.com", cfg.serpApiKey, pool
            )
        ),
        SourcePlatform.QUORA to CompositeAdapter(
            SourcePlatform.QUORA,
            primary = null,
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.QUORA],
            fallback = SerpSearchAdapter(
                SourcePlatform.QUORA, "site:quora.com", cfg.serpApiKey, pool
            )
        ),
        SourcePlatform.YOUTUBE to YouTubeAdapter(cfg.youtubeApiKey, pool),
        SourcePlatform.TWITTER to TwitterAdapter(cfg.xBearerToken, pool),
        // ---- 0.5.3 新增 12 平台：直连优先，不可用走模拟行为 ----
        // 完全公开机制 · 官方检索 API 直连主，浏览器兜底
        SourcePlatform.ARXIV to CompositeAdapter(
            SourcePlatform.ARXIV,
            primary = ArxivAdapter(pool),
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.ARXIV],
            fallback = null
        ),
        SourcePlatform.HACKERNEWS to CompositeAdapter(
            SourcePlatform.HACKERNEWS,
            primary = HackerNewsAdapter(pool),
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.HACKERNEWS],
            fallback = null
        ),
        SourcePlatform.STACKEXCHANGE to CompositeAdapter(
            SourcePlatform.STACKEXCHANGE,
            primary = StackExchangeAdapter(pool),
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.STACKEXCHANGE],
            fallback = null
        ),
        SourcePlatform.WIKIPEDIA to CompositeAdapter(
            SourcePlatform.WIKIPEDIA,
            primary = WikipediaAdapter(pool),
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.WIKIPEDIA],
            fallback = null
        ),
        // 部分公开 · 尽力 HTTP 主，降级浏览器
        SourcePlatform.NSSD to CompositeAdapter(
            SourcePlatform.NSSD,
            primary = NssdAdapter(pool),
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.NSSD],
            fallback = null
        ),
        SourcePlatform.DOUBAN to CompositeAdapter(
            SourcePlatform.DOUBAN,
            primary = DoubanAdapter(pool),
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.DOUBAN],
            fallback = null
        ),
        SourcePlatform.SUBSTACK to CompositeAdapter(
            SourcePlatform.SUBSTACK,
            primary = SubstackRssAdapter(pool),
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.SUBSTACK],
            fallback = null
        ),
        // 基本不公开 / 无公开检索 API · 浏览器主
        SourcePlatform.WECHAT to CompositeAdapter(
            SourcePlatform.WECHAT,
            primary = null,
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.WECHAT],
            fallback = null
        ),
        SourcePlatform.CAIXIN_PENGPAI to CompositeAdapter(
            SourcePlatform.CAIXIN_PENGPAI,
            primary = null,
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.CAIXIN_PENGPAI],
            fallback = null
        ),
        SourcePlatform.JSTOR_ACM to CompositeAdapter(
            SourcePlatform.JSTOR_ACM,
            primary = null,
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.JSTOR_ACM],
            fallback = null
        ),
        SourcePlatform.CNKI to CompositeAdapter(
            SourcePlatform.CNKI,
            primary = null,
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.CNKI],
            fallback = null
        ),
        SourcePlatform.GOOGLE_SCHOLAR to CompositeAdapter(
            SourcePlatform.GOOGLE_SCHOLAR,
            primary = null,
            browser = browser,
            scraper = BrowserScrapers.all[SourcePlatform.GOOGLE_SCHOLAR],
            fallback = SerpSearchAdapter(
                SourcePlatform.GOOGLE_SCHOLAR, "scholar.google", cfg.serpApiKey, pool
            )
        )
    )
}

/**
 * 组合适配器：主通道（公开接口/官方API）→ 云端登录态浏览器 → 兜底通道（SerpAPI）。
 *
 * 效率策略：
 * - 主通道抛异常时重试 1 次（短暂退避），空结果不重试直接降级；
 * - 浏览器通道受 [AdapterBreaker] 门控：连续失败熔断期间不冷启动浏览器；
 * - 合法空结果（无会话/无匹配）不计失败、不熔断；
 * - 任一通道有结果即返回，全空返回空列表，绝不因单通道失败中断聚合。
 */
class CompositeAdapter(
    override val platform: SourcePlatform,
    private val primary: PlatformAdapter?,
    private val browser: BrowserFetchService?,
    private val scraper: BrowserScraper?,
    private val fallback: PlatformAdapter?
) : PlatformAdapter {

    override suspend fun fetch(
        category: InfoCategory,
        limit: Int,
        allowBrowser: Boolean
    ): List<ContentItem> {
        // 1. 主通道：异常重试 1 次；有结果立即返回
        val prim = primary
        if (prim != null) {
            for (attempt in 0..1) {
                val res = runCatching { prim.fetch(category, limit) }
                res.onSuccess { items -> if (items.isNotEmpty()) return items }
                if (res.isSuccess) break // 合法空结果：不重试，转下一通道
                if (attempt == 0) delay(400)
                else adapterLog.warn("主通道 {} 两次尝试均失败：{}",
                    platform.id, res.exceptionOrNull()?.message)
            }
        }
        // 2. 浏览器通道：仅后台进程允许；熔断期跳过（避免为持续失败的平台反复冷启动 Chromium）
        val bf = browser
        val sc = scraper
        if (allowBrowser && bf != null && sc != null && !AdapterBreaker.isOpen(platform)) {
            bf.fetchCatching(sc, category, limit)
                .onSuccess { items ->
                    AdapterBreaker.recordSuccess(platform)
                    if (items.isNotEmpty()) return items
                }
                .onFailure { AdapterBreaker.recordFailure(platform) }
        }
        // 3. 兜底通道（SerpAPI 付费检索）
        val fb = fallback
        return if (fb != null) {
            runCatching { fb.fetch(category, limit) }.getOrDefault(emptyList())
        } else emptyList()
    }
}

/** 海外 · Reddit：官方榜单/检索 .json 接口（经云端代理）。 */
class RedditAdapter(pool: ProxyPool) : HttpAdapter(SourcePlatform.REDDIT, pool) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** 类目 → 垂直 subreddit 映射（平台原生分类，多 sub 用 r/a+b 合并榜单）。未命中回退全站检索。 */
        private val SUBREDDITS: Map<String, String> = mapOf(
            "ai-news" to "MachineLearning+artificial",
            "ai-algorithm" to "MachineLearning+deeplearning",
            "ai-cases" to "artificial+MachineLearning",
            "ai-industry" to "MachineLearning+artificial",
            "edu-cs" to "computerscience+programming",
            "edu-math" to "math",
            "edu-physics" to "Physics",
            "edu-chemistry" to "chemistry",
            "edu-biology" to "biology",
            "edu-medicine" to "medicine",
            "edu-economics" to "economics",
            "intl-news" to "worldnews+news",
            "policy-employment" to "geopolitics+economics",
            "ne-digital-news" to "gadgets+hardware",
            "ne-digital-review" to "hardware+gadgets",
            "ne-psy-jung" to "psychology+Jung",
            "ne-sociology" to "sociology",
            "ne-philosophy" to "philosophy"
        )
    }

    override suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem> = runCatching {
        val headers = mapOf("User-Agent" to "trae-info-aggregator/0.1 research")
        val subs = SUBREDDITS[category.id]
        // 榜单优先：垂直 subreddit 周榜（高认可度投票筛选）；无映射或空结果回退全站检索
        val items = if (subs != null) {
            val url = "https://www.reddit.com/r/$subs/top.json?t=week&limit=${limit.coerceAtMost(25)}"
            parseFeed(httpGet(url, headers), category).ifEmpty {
                parseFeed(
                    httpGet(
                        "https://www.reddit.com/search.json?q=${URLEncoder.encode(queryOf(category), "UTF-8")}" +
                                "&limit=${limit.coerceAtMost(25)}&sort=new", headers
                    ), category
                )
            }
        } else {
            parseFeed(
                httpGet(
                    "https://www.reddit.com/search.json?q=${URLEncoder.encode(queryOf(category), "UTF-8")}" +
                            "&limit=${limit.coerceAtMost(25)}&sort=new", headers
                ), category
            )
        }
        items.take(limit)
    }.getOrElse { emptyList() }

    private fun parseFeed(body: String, category: InfoCategory): List<ContentItem> =
        runCatching {
            json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("children")
                ?.jsonArray?.mapNotNull { child ->
                    val d = child.jsonObject["data"]?.jsonObject ?: return@mapNotNull null
                    val id = d.str("id") ?: return@mapNotNull null
                    ContentItem(
                        id = "reddit-$id",
                        categoryId = category.id,
                        source = platform.id,
                        title = d.str("title").orEmpty(),
                        summary = (d.str("selftext") ?: "").take(300),
                        url = "https://www.reddit.com" + (d.str("permalink") ?: ""),
                        author = d.str("author").orEmpty(),
                        publishedAt = ((d.str("created_utc")?.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                        tags = listOfNotNull(d.str("subreddit")?.let { "r/$it" })
                    )
                }.orEmpty()
        }.getOrDefault(emptyList())
}

/** 国内 · 哔哩哔哩：公开视频搜索接口（直连）。 */
class BilibiliAdapter(pool: ProxyPool) : HttpAdapter(SourcePlatform.BILIBILI, pool) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem> = runCatching {
        val q = URLEncoder.encode(queryOf(category), "UTF-8")
        val url = "https://api.bilibili.com/x/web-interface/search/type" +
                "?search_type=video&order=pubdate&page=1&keyword=$q"
        val root = json.parseToJsonElement(
            httpGet(url, mapOf("User-Agent" to UA, "Referer" to "https://www.bilibili.com"))
        ).jsonObject
        if (root.str("code") != "0") return emptyList()
        root["data"]?.jsonObject?.get("result")?.jsonArray?.mapNotNull { item ->
            val d = item.jsonObject
            val bvid = d.str("bvid") ?: return@mapNotNull null
            ContentItem(
                id = "bili-$bvid",
                categoryId = category.id,
                source = platform.id,
                title = (d.str("title") ?: "").replace(htmlTagRegex, ""),
                summary = "UP主：${d.str("author") ?: "--"}｜播放：${d.str("play") ?: "--"}｜弹幕：${d.str("danmaku") ?: "--"}",
                url = "https://www.bilibili.com/video/$bvid",
                author = d.str("author").orEmpty(),
                publishedAt = (d.str("pubdate")?.toLongOrNull() ?: 0L) * 1000L,
                tags = listOf("视频", "公开课")
            )
        }.orEmpty().take(limit)
    }.getOrThrow()
}

/**
 * 国内 · 中国大学MOOC：网页搜索 RPC（直连，无需 Key）。
 * 流程：先访问首页获取 csrfkey Cookie，再 POST mocSearchBean.searchCourse.rpc。
 * 站点接口可能随版本调整，异常时优雅降级为空。
 */
class ICourseAdapter(pool: ProxyPool) : HttpAdapter(SourcePlatform.ICUOOC, pool) {

    private val json = Json { ignoreUnknownKeys = true }

    // csrfkey 30 分钟内复用，避免每个类目单元都打一次首页
    @Volatile
    private var csrfCache: String? = null
    @Volatile
    private var csrfAt: Long = 0L

    companion object {
        private const val SEARCH_RPC = "https://www.icourse163.org/web/j/mocSearchBean.searchCourse.rpc"
        private const val CSRF_TTL_MS = 30L * 60 * 1000
    }

    private suspend fun obtainCsrf(client: io.ktor.client.HttpClient): String? {
        val cached = csrfCache
        if (cached != null && System.currentTimeMillis() - csrfAt < CSRF_TTL_MS) return cached
        val home = client.get("https://www.icourse163.org/") { header("User-Agent", UA) }
        val fresh = home.headers.getAll("Set-Cookie").orEmpty()
            .firstOrNull { it.startsWith("csrfkey=") }
            ?.substringAfter("csrfkey=")?.substringBefore(";")
        if (!fresh.isNullOrBlank()) { csrfCache = fresh; csrfAt = System.currentTimeMillis() }
        return fresh ?: csrfCache
    }

    override suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem> {
        val client = pool.clientFor(platform.requiresProxy)

        // 1. csrfkey（30 分钟缓存）
        val csrf = obtainCsrf(client)

        // 2. 搜索 RPC
        val body = client.post(SEARCH_RPC) {
            header("User-Agent", UA)
            header("Referer", "https://www.icourse163.org/search.htm")
            csrf?.let { header("Cookie", "csrfkey=$it") }
            setBody(FormDataContent(Parameters.build {
                append("csrfKey", csrf ?: "")
                append("keyword", queryOf(category))
                append("orderBy", "start")
                append("pageSize", limit.coerceAtMost(40).toString())
                append("pageIndex", "1")
                append("highlight", "false")
            }))
        }.bodyAsText()

        val root = json.parseToJsonElement(body).jsonObject
        val result = root["result"]?.jsonObject
        val list = result?.get("result")?.jsonArray
            ?: result?.get("courses")?.jsonArray
            ?: return emptyList()

        return list.mapNotNull { item ->
            val d = item.jsonObject
            val id = d.str("id") ?: d.str("courseId") ?: return@mapNotNull null
            val title = d.str("name") ?: d.str("title") ?: return@mapNotNull null
            val school = d["school"]?.jsonObject
            val schoolName = school?.str("name") ?: school?.str("shortName") ?: ""
            val shortName = school?.str("shortName")
            ContentItem(
                id = "mooc-$id",
                categoryId = category.id,
                source = platform.id,
                title = title.replace(htmlTagRegex, ""),
                summary = buildString {
                    if (schoolName.isNotBlank()) append("学校：$schoolName")
                    d.str("learnerCount")?.let { append(if (isEmpty()) "人数：$it" else "｜学习人数：$it") }
                },
                url = d.str("courseUrl")
                    ?: (if (shortName.isNullOrBlank()) "https://www.icourse163.org/course/$id"
                        else "https://www.icourse163.org/course/$shortName-$id"),
                author = schoolName,
                tags = listOf("慕课", "课程")
            )
        }.take(limit)
    }
}

/**
 * 国内 · 知乎：公开热榜 API（直连，无需签名）+ 类目关键词过滤。
 * 站内搜索 API 需要 x-zse-96 签名，故采用「热榜全量 + 关键词匹配」策略。
 */
class ZhihuAdapter(pool: ProxyPool) : HttpAdapter(SourcePlatform.ZHIHU, pool) {

    private val json = Json { ignoreUnknownKeys = true }

    private data class HotEntry(val title: String, val excerpt: String, val url: String)

    // 热榜一次抓取、多类目复用：31 个类目单元不再各打一次热榜接口
    @Volatile
    private var hotCache: List<HotEntry>? = null
    @Volatile
    private var hotCacheAt: Long = 0L

    companion object {
        private const val HOT_TTL_MS = 5L * 60 * 1000
    }

    private suspend fun loadHot(): List<HotEntry> {
        hotCache?.let { if (System.currentTimeMillis() - hotCacheAt < HOT_TTL_MS) return it }
        val text = httpGet(
            "https://www.zhihu.com/api/v3/feed/topstory/hot-lists/total?limit=50",
            mapOf("User-Agent" to UA)
        )
        val root = json.parseToJsonElement(text).jsonObject
        val list = root["data"]?.jsonArray?.mapNotNull { entry ->
            val t = entry.jsonObject["target"]?.jsonObject ?: return@mapNotNull null
            val title = (t.str("title") ?: return@mapNotNull null).replace(htmlTagRegex, "")
            val excerpt = (t.str("excerpt") ?: "").replace(htmlTagRegex, "")
            val rawUrl = t.str("url").orEmpty()
            val url = when {
                rawUrl.contains("questions/") -> "https://www.zhihu.com/question/" +
                        rawUrl.substringAfter("questions/").substringBefore("?")
                rawUrl.startsWith("http") -> rawUrl
                else -> ""
            }
            if (url.isBlank()) null else HotEntry(title, excerpt, url)
        }.orEmpty()
        hotCache = list
        hotCacheAt = System.currentTimeMillis()
        return list
    }

    override suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem> {
        val hot = loadHot() // 异常向上抛，由 CompositeAdapter 重试/降级
        return hot.mapNotNull { e ->
            val matched = (e.title + e.excerpt).let { s ->
                category.keywords.any { s.contains(it, ignoreCase = true) } ||
                        e.title.contains(queryOf(category), ignoreCase = true)
            }
            if (!matched) return@mapNotNull null
            ContentItem(
                id = "zhihu-" + e.url.substringAfterLast("/"),
                categoryId = category.id,
                source = platform.id,
                title = e.title,
                summary = e.excerpt.take(300),
                url = e.url,
                tags = listOf("知乎", "热榜")
            )
        }.take(limit)
    }
}

/**
 * 海外 · YouTube：Data API v3（经云端代理，需 YOUTUBE_API_KEY）。
 * 检索 search.list（order=date，100 quota）与官方热门榜 videos.list chart=mostPopular
 * （1 quota，类目映射 videoCategoryId）对半混用：榜单补充高热度候选，检索保底关键词召回。
 */
class YouTubeAdapter(
    private val apiKey: String,
    pool: ProxyPool
) : HttpAdapter(SourcePlatform.YOUTUBE, pool) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** 类目 → YouTube 官方视频分类号（28=科技 27=教育 25=新闻）。未命中不做榜单混用。 */
        private val CHART_CATEGORIES: Map<String, String> = mapOf(
            "ai-news" to "28", "ai-algorithm" to "28", "ai-cases" to "28", "ai-industry" to "28",
            "edu-cs" to "27", "edu-math" to "27", "edu-physics" to "28", "edu-chemistry" to "28",
            "edu-biology" to "27", "edu-ee" to "28", "edu-medicine" to "27", "edu-engineering" to "28",
            "edu-economics" to "27", "edu-humanities" to "27",
            "ne-digital-news" to "28", "ne-digital-review" to "28", "ne-elec-explain" to "28",
            "intl-news" to "25"
        )
    }

    override suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem> {
        if (apiKey.isBlank()) return emptyList()
        return runCatching {
            val half = (limit.coerceAtMost(25) / 2).coerceAtLeast(1)
            val searchItems = searchList(category, half)
            val chartCat = CHART_CATEGORIES[category.id]
            val chartItems = if (chartCat != null) chartList(category.id, chartCat, half) else emptyList()
            (searchItems + chartItems)
                .distinctBy { it.url }
                .take(limit)
        }.getOrElse { emptyList() }
    }

    private suspend fun searchList(category: InfoCategory, limit: Int): List<ContentItem> =
        runCatching {
            val q = URLEncoder.encode(searchQuery(category), "UTF-8")
            val url = "https://www.googleapis.com/youtube/v3/search" +
                    "?part=snippet&type=video&order=date&maxResults=${limit.coerceAtMost(25)}" +
                    "&q=$q&key=$apiKey"
            parseVideos(json.parseToJsonElement(httpGet(url)).jsonObject, category.id)
        }.getOrElse { emptyList() }

    /** 官方热门榜：按类目分类号取 mostPopular（1 quota/次，search.list 的 1/100）。 */
    private suspend fun chartList(belongCategoryId: String, videoCategoryId: String, limit: Int): List<ContentItem> =
        runCatching {
            val url = "https://www.googleapis.com/youtube/v3/videos" +
                    "?part=snippet&chart=mostPopular&regionCode=US" +
                    "&videoCategoryId=$videoCategoryId&maxResults=${limit.coerceAtMost(25)}&key=$apiKey"
            parseVideos(json.parseToJsonElement(httpGet(url)).jsonObject, belongCategoryId)
        }.getOrElse { emptyList() }

    /** search.list 与 videos.list 的条目结构兼容解析（chart 响应 id 为 string 而非 object）。 */
    private fun parseVideos(root: JsonObject, belongCategoryId: String): List<ContentItem> =
        runCatching {
            root["items"]?.jsonArray?.mapNotNull { item ->
                val d = item.jsonObject
                val videoId = d["id"]?.jsonObject?.str("videoId")
                    ?: d["id"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val sn = d["snippet"]?.jsonObject ?: return@mapNotNull null
                ContentItem(
                    id = "yt-$videoId",
                    categoryId = belongCategoryId,
                    source = platform.id,
                    title = (sn.str("title") ?: "").replace(htmlTagRegex, ""),
                    summary = (sn.str("description") ?: "").take(300),
                    url = "https://www.youtube.com/watch?v=$videoId",
                    author = sn.str("channelTitle").orEmpty(),
                    publishedAt = isoToEpochMs(sn.str("publishedAt")),
                    tags = listOf("视频")
                )
            }.orEmpty()
        }.getOrDefault(emptyList())
}

/**
 * 海外 · X/Twitter：API v2 recent search（经云端代理，需 X_BEARER_TOKEN）。
 */
class TwitterAdapter(
    private val bearer: String,
    pool: ProxyPool
) : HttpAdapter(SourcePlatform.TWITTER, pool) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem> {
        if (bearer.isBlank()) return emptyList()
        return runCatching {
            val q = URLEncoder.encode(searchQuery(category) + " -is:retweet", "UTF-8")
            val url = "https://api.twitter.com/2/tweets/search/recent" +
                    "?query=$q&max_results=${limit.coerceAtMost(25)}" +
                    "&tweet.fields=created_at&expansions=author_id"
            val root = json.parseToJsonElement(
                httpGet(url, mapOf("Authorization" to "Bearer $bearer"))
            ).jsonObject
            val users = root["includes"]?.jsonObject?.get("users")?.jsonArray
                ?.mapNotNull { u ->
                    val o = u.jsonObject
                    o.str("id")?.let { it to o.str("username").orEmpty() }
                }?.toMap().orEmpty()

            root["data"]?.jsonArray?.mapNotNull { item ->
                val d = item.jsonObject
                val id = d.str("id") ?: return@mapNotNull null
                val text = d.str("text") ?: return@mapNotNull null
                val author = users[d.str("author_id")].orEmpty()
                ContentItem(
                    id = "x-$id",
                    categoryId = category.id,
                    source = platform.id,
                    title = text.take(80),
                    summary = text.take(300),
                    url = if (author.isBlank()) "https://x.com/i/web/status/$id"
                          else "https://x.com/$author/status/$id",
                    author = author,
                    publishedAt = isoToEpochMs(d.str("created_at")),
                    tags = listOf("X")
                )
            }.orEmpty().take(limit)
        }.getOrElse { emptyList() }
    }
}

/**
 * 抖音 / 小红书 / Quora：无公开检索 API，经 SerpAPI 的 Google 站内检索
 * 获取真实内容链接（需 SERPAPI_KEY；缺失时返回空）。
 * 注意：对国内平台（douyin/xiaohongshu），SerpAPI 调用按平台设定直连，
 * 若服务器无法直连 serpapi.com，可将其 requiresProxy 需求由代理池评估。
 */
class SerpSearchAdapter(
    platform: SourcePlatform,
    private val siteQuery: String,
    private val apiKey: String,
    pool: ProxyPool
) : HttpAdapter(platform, pool) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem> {
        if (apiKey.isBlank()) return emptyList()
        return runCatching {
            val q = URLEncoder.encode("$siteQuery ${searchQuery(category)}", "UTF-8")
            val url = "https://serpapi.com/search.json" +
                    "?engine=google&num=${limit.coerceAtMost(20)}&hl=zh-CN&q=$q&api_key=$apiKey"
            // serpapi.com 为海外服务，统一经代理出口，避免直连超时
            val client = pool.clientFor(true)
            val root = json.parseToJsonElement(client.get(url).bodyAsText()).jsonObject
            root["organic_results"]?.jsonArray?.mapNotNull { item ->
                val d = item.jsonObject
                val link = d.str("link") ?: return@mapNotNull null
                val title = (d.str("title") ?: "").replace(htmlTagRegex, "")
                ContentItem(
                    id = "${platform.id}-" + link.substringAfter("://").take(120).hashCode(),
                    categoryId = category.id,
                    source = platform.id,
                    title = title,
                    summary = (d.str("snippet") ?: "").take(300),
                    url = link,
                    tags = listOf(platform.displayName, "检索")
                )
            }.orEmpty().distinctBy { it.url }.take(limit)
        }.getOrElse { emptyList() }
    }
}

// ===================== 0.5.3 新增 · 完全公开机制平台（官方 API 直连） =====================

/**
 * 海外 · arXiv：官方 Atom API（完全公开检索规则，无个性化推荐）。
 * 优先用平台原生垂直分类号（cat:cs.AI 等）精确检索，未映射类目回退 all: 全文检索。
 */
class ArxivAdapter(pool: ProxyPool) : HttpAdapter(SourcePlatform.ARXIV, pool) {

    private val entryRegex = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
    private val fieldRegex = Regex("<(\\w+)[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
    private val authorRegex = Regex("<name>(.*?)</name>")

    companion object {
        /** 类目 → arXiv 原生分类号（高置信映射；未命中回退全文检索，不产生空单元）。 */
        private val CATS: Map<String, List<String>> = mapOf(
            "ai-news" to listOf("cs.AI", "cs.CL", "cs.LG"),
            "ai-algorithm" to listOf("cs.LG", "cs.AI", "stat.ML"),
            "ai-cases" to listOf("cs.AI", "cs.HC", "cs.SE"),
            "ai-industry" to listOf("cs.AI", "cs.CY", "cs.SE"),
            "edu-cs" to listOf("cs.SE", "cs.DB", "cs.DC"),
            "edu-physics" to listOf("quant-ph", "physics.pop-ph"),
            "phys-discovery" to listOf("physics.pop-ph", "physics.atom-ph"),
            "phys-theory" to listOf("gr-qc", "hep-th", "quant-ph"),
            "phys-experiment" to listOf("physics.ins-det", "quant-ph")
        )
    }

    override suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem> = runCatching {
        val cats = CATS[category.id]
        // 分类号检索（精确到平台垂直领域）；未映射回退 all: 全文检索
        val query = if (cats != null) {
            URLEncoder.encode(cats.joinToString("+OR+") { "cat:$it" }, "UTF-8")
        } else {
            "all:" + URLEncoder.encode(searchQuery(category), "UTF-8")
        }
        val url = "http://export.arxiv.org/api/query?search_query=$query" +
                "&start=0&max_results=${limit.coerceAtMost(50)}&sortBy=submittedDate&sortOrder=descending"
        val xml = httpGet(url, mapOf("User-Agent" to UA))
        entryRegex.findAll(xml).mapNotNull { m ->
            val fields = fieldRegex.findAll(m.groupValues[1]).associate {
                it.groupValues[1] to it.groupValues[2].replace(htmlTagRegex, "").trim()
            }
            val id = fields["id"] ?: return@mapNotNull null
            val title = fields["title"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val summary = fields["summary"].orEmpty()
            val authors = authorRegex.findAll(m.groupValues[1])
                .map { it.groupValues[1].trim() }.joinToString(", ")
            val published = isoToEpochMs(fields["published"])
            val link = id  // arxiv id 本身是 http://arxiv.org/abs/xxx
            ContentItem(
                id = "arxiv-" + id.substringAfterLast("/").take(40),
                categoryId = category.id,
                source = platform.id,
                title = title,
                summary = summary.take(300),
                url = link,
                author = authors,
                publishedAt = published,
                tags = listOf("arXiv", "论文")
            )
        }.take(limit).toList()
    }.getOrElse { emptyList() }
}

/**
 * 海外 · Hacker News：Algolia API（Hot 排序公式完全开源：score=(P-1)/(T+2)^G, G=1.8）。
 * 关键词检索 + 官方首页榜 front_page（进程内缓存 30 分钟，一轮多类目共享一次请求），
 * 榜单条目经类目关键词过滤后合并；抓取后按官方公式复算 Hot 分重排序。
 */
class HackerNewsAdapter(pool: ProxyPool) : HttpAdapter(SourcePlatform.HACKERNEWS, pool) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** front_page 榜单缓存：一轮抓取中所有 HN 单元共享一次请求。 */
        @Volatile
        private var frontPageCache: List<Hit> = emptyList()
        @Volatile
        private var frontPageAt: Long = 0L
        private const val FRONT_PAGE_TTL = 30L * 60 * 1000
    }

    /** Algolia hit 的公共字段快照（与类目无关，便于跨类目缓存）。 */
    private data class Hit(
        val oid: String, val title: String, val points: Int, val link: String,
        val storyText: String, val author: String, val createdSec: Long, val hotScore: Double
    )

    private fun parseHits(root: JsonObject): List<Hit> {
        val nowSec = System.currentTimeMillis() / 1000
        return root["hits"]?.jsonArray?.mapNotNull { hit ->
            val d = hit.jsonObject
            val oid = d.str("objectID") ?: return@mapNotNull null
            val title = d.str("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val points = d.str("points")?.toIntOrNull() ?: 0
            val rawUrl = d.str("url")
            val link = if (rawUrl.isNullOrBlank()) "https://news.ycombinator.com/item?id=$oid" else rawUrl
            val createdSec = isoToEpochMs(d.str("created_at")) / 1000
            val hours = ((nowSec - createdSec) / 3600.0).coerceAtLeast(0.0)
            Hit(
                oid, title, points, link,
                (d.str("story_text") ?: "").replace(htmlTagRegex, "").take(300),
                d.str("author").orEmpty(), createdSec,
                (points - 1) / Math.pow(hours + 2.0, 1.8)  // Hot 公式复现（G=1.8，与官方一致）
            )
        }.orEmpty()
    }

    private fun hitToItem(h: Hit, category: InfoCategory): ContentItem = ContentItem(
        id = "hn-${h.oid}",
        categoryId = category.id,
        source = platform.id,
        title = h.title,
        summary = h.storyText,
        url = h.link,
        author = h.author,
        publishedAt = h.createdSec * 1000,
        tags = listOf("Hacker News", "热点", "hot=${"%.1f".format(h.hotScore)}")
    )

    override suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem> = runCatching {
        val q = URLEncoder.encode(searchQuery(category), "UTF-8")
        val url = "https://hn.algolia.com/api/v1/search?query=$q&tags=story&hitsPerPage=${limit.coerceAtMost(50)}"
        val searchHits = parseHits(json.parseToJsonElement(httpGet(url, mapOf("User-Agent" to UA))).jsonObject)

        // front_page 榜单增补：缓存命中不发请求（请求幂等，并发下最坏重复一次）；条目须命中类目关键词才并入
        if (frontPageCache.isEmpty() || System.currentTimeMillis() - frontPageAt > FRONT_PAGE_TTL) {
            runCatching {
                parseHits(
                    json.parseToJsonElement(
                        httpGet("https://hn.algolia.com/api/v1/search?tags=front_page&hitsPerPage=30", mapOf("User-Agent" to UA))
                    ).jsonObject
                )
            }.getOrElse { emptyList() }
                .takeIf { it.isNotEmpty() }
                ?.let { frontPageCache = it; frontPageAt = System.currentTimeMillis() }
        }
        val frontHits = frontPageCache
        val kw = searchQuery(category).lowercase().split(" ").filter { it.length >= 2 }
        val frontMerged = frontHits
            .filter { h -> kw.any { h.title.lowercase().contains(it) || h.storyText.lowercase().contains(it) } }
            .map { it to hitToItem(it, category) }

        (searchHits.map { it to hitToItem(it, category) } + frontMerged)
            .distinctBy { it.second.url }
            .sortedByDescending { it.first.hotScore }
            .map { it.second }
            .take(limit)
    }.getOrElse { emptyList() }
}

/**
 * 海外 · Stack Exchange：官方检索 API（投票+威尔逊区间排序规则开源）。
 * 类目映射到平台原生站点与高置信 tag（tagged= 垂直过滤），tag 命中空结果时回退纯关键词检索；
 * 抓取后按 Wilson 区间下界排序，复现官方「最佳」排序逻辑。
 */
class StackExchangeAdapter(pool: ProxyPool) : HttpAdapter(SourcePlatform.STACKEXCHANGE, pool) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** 类目 → 站点核心 tag（高置信映射；SE tag 体系敏感，宁缺毋滥，未命中回退纯关键词）。 */
        private val TAGS: Map<String, String> = mapOf(
            "ne-philosophy" to "philosophy",
            "ne-sociology" to "sociology",
            "ai-news" to "machine-learning",
            "ai-algorithm" to "machine-learning",
            "ai-cases" to "machine-learning",
            "ai-industry" to "machine-learning",
            "edu-cs" to "algorithm"
        )
    }

    // 类目 -> 站点映射（默认 stackoverflow；人文/社科用 philosophy.stackexchange 等）
    private fun siteOf(category: InfoCategory): String = when {
        category.id.startsWith("ne-philosophy") -> "philosophy"
        category.id.startsWith("ne-sociology") -> "sociology"
        category.id.startsWith("edu-math") || category.id.startsWith("ne-beauty-math") -> "math"
        category.id.startsWith("edu-physics") || category.id.startsWith("ne-beauty-physics") -> "physics"
        category.id.startsWith("edu-chemistry") || category.id.startsWith("ne-beauty-chemistry") -> "chemistry"
        category.id.startsWith("edu-cs") || category.id.startsWith("ai-") -> "stackoverflow"
        category.id.startsWith("edu-biology") -> "biology"
        category.id.startsWith("edu-medicine") -> "medicalsciences"
        else -> "stackoverflow"
    }

    // Wilson 区间下界（z=1.96 对应 95% 置信）
    private fun wilsonLower(up: Int, down: Int): Double {
        val n = up + down
        if (n == 0) return 0.0
        val z = 1.96
        val p = up.toDouble() / n
        return (p + z * z / (2 * n) - z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))) /
                (1 + z * z / n)
    }

    private fun parseQuestions(root: JsonObject, site: String, category: InfoCategory): List<Pair<Double, ContentItem>> =
        root["items"]?.jsonArray?.mapNotNull { item ->
            val d = item.jsonObject
            val qid = d.str("question_id") ?: return@mapNotNull null
            val title = d.str("title")?.replace(htmlTagRegex, "")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val link = d.str("link") ?: "https://$site.stackexchange.com/questions/$qid"
            val up = d.str("up_vote_count")?.toIntOrNull() ?: 0
            val down = d.str("down_vote_count")?.toIntOrNull() ?: 0
            val wilson = wilsonLower(up, down)
            val tags = d["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            wilson to ContentItem(
                id = "se-$site-$qid",
                categoryId = category.id,
                source = platform.id,
                title = title,
                summary = "投票：$up↑${down}↓｜Wilson：${"%.2f".format(wilson)}",
                url = link,
                author = d["owner"]?.jsonObject?.str("display_name").orEmpty(),
                publishedAt = (d.str("creation_date")?.toLongOrNull() ?: 0L) * 1000L,
                tags = listOf("Stack Exchange", site) + tags.take(3)
            )
        }.orEmpty()

    override suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem> = runCatching {
        val site = siteOf(category)
        val q = URLEncoder.encode(searchQuery(category), "UTF-8")
        val headers = mapOf("User-Agent" to UA)
        val tag = TAGS[category.id]
        // tag 组合检索（垂直领域过滤）→ 空结果回退纯关键词（防 tag 更名产生空单元）
        val candidates = if (tag != null) {
            val taggedUrl = "https://api.stackexchange.com/2.3/search/advanced" +
                    "?order=desc&sort=votes&pagesize=${limit.coerceAtMost(30)}&site=$site" +
                    "&tagged=${URLEncoder.encode(tag, "UTF-8")}&q=$q"
            parseQuestions(json.parseToJsonElement(httpGet(taggedUrl, headers)).jsonObject, site, category)
                .ifEmpty {
                    parseQuestions(
                        json.parseToJsonElement(
                            httpGet(
                                "https://api.stackexchange.com/2.3/search/advanced" +
                                        "?order=desc&sort=votes&pagesize=${limit.coerceAtMost(30)}&site=$site&q=$q",
                                headers
                            )
                        ).jsonObject, site, category
                    )
                }
        } else {
            parseQuestions(
                json.parseToJsonElement(
                    httpGet(
                        "https://api.stackexchange.com/2.3/search/advanced" +
                                "?order=desc&sort=votes&pagesize=${limit.coerceAtMost(30)}&site=$site&q=$q",
                        headers
                    )
                ).jsonObject, site, category
            )
        }
        candidates.sortedByDescending { it.first }.map { it.second }.take(limit)
    }.getOrElse { emptyList() }
}

/**
 * 海外 · Wikipedia：MediaWiki search API（检索代码开源，无个性化信息流）。
 * 端点：https://{lang}.wikipedia.org/w/api.php?action=query&list=search
 */
class WikipediaAdapter(pool: ProxyPool) : HttpAdapter(SourcePlatform.WIKIPEDIA, pool) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun langOf(category: InfoCategory): String =
        if (category.id.startsWith("intl-news") || category.id.startsWith("ne-") ||
            category.id.startsWith("edu-") || category.id.startsWith("policy-")) "zh" else "en"

    override suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem> = runCatching {
        val lang = langOf(category)
        val q = URLEncoder.encode(searchQuery(category), "UTF-8")
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search" +
                "&srsearch=$q&srlimit=${limit.coerceAtMost(30)}&format=json&srsort=relevance"
        val root = json.parseToJsonElement(httpGet(url, mapOf("User-Agent" to UA))).jsonObject
        root["query"]?.jsonObject?.get("search")?.jsonArray?.mapNotNull { item ->
            val d = item.jsonObject
            val title = d.str("title")?.replace(htmlTagRegex, "")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val pageid = d.str("pageid") ?: return@mapNotNull null
            val snippet = d.str("snippet")?.replace(htmlTagRegex, "").orEmpty()
            ContentItem(
                id = "wiki-$lang-$pageid",
                categoryId = category.id,
                source = platform.id,
                title = title,
                summary = snippet.take(300),
                url = "https://$lang.wikipedia.org/wiki?curid=$pageid",
                author = "Wikipedia",
                publishedAt = isoToEpochMs(d.str("timestamp")),
                tags = listOf("Wikipedia", lang)
            )
        }.orEmpty().take(limit)
    }.getOrElse { emptyList() }
}

// ===================== 0.5.3 新增 · 部分公开机制平台（尽力 HTTP，降级浏览器） =====================

/**
 * 国内 · 国家哲学社科文献中心：尽力 HTTP 检索（站点接口非稳定公开，失败降级浏览器）。
 */
class NssdAdapter(pool: ProxyPool) : HttpAdapter(SourcePlatform.NSSD, pool) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem> = runCatching {
        val q = URLEncoder.encode(searchQuery(category), "UTF-8")
        // 站点检索接口（best-effort，站点改版随时可能失效）
        val url = "https://www.ncpssd.cn/Literature/query"
        val client = pool.clientFor(platform.requiresProxy)
        val body = client.post(url) {
            header("User-Agent", UA)
            header("Content-Type", "application/json")
            setBody("""{"keyword":"$q","page":1,"pageSize":${limit.coerceAtMost(20)}}""")
        }.bodyAsText()
        val root = json.parseToJsonElement(body).jsonObject
        root["data"]?.jsonObject?.get("list")?.jsonArray?.mapNotNull { item ->
            val d = item.jsonObject
            val id = d.str("id") ?: d.str("literatureId") ?: return@mapNotNull null
            val title = d.str("title")?.replace(htmlTagRegex, "")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val link = d.str("url") ?: "https://www.ncpssd.cn/literature/$id"
            ContentItem(
                id = "nssd-$id",
                categoryId = category.id,
                source = platform.id,
                title = title,
                summary = (d.str("abstract") ?: d.str("summary") ?: "").take(300),
                url = link,
                author = d.str("author").orEmpty(),
                tags = listOf("社科文献", "期刊")
            )
        }.orEmpty().take(limit)
    }.getOrElse { emptyList() }
}

/**
 * 国内 · 豆瓣：尽力 HTTP 检索（评分加权与基础排序部分公开，反作弊细节不公开）。
 */
class DoubanAdapter(pool: ProxyPool) : HttpAdapter(SourcePlatform.DOUBAN, pool) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem> = runCatching {
        val q = URLEncoder.encode(searchQuery(category), "UTF-8")
        // 豆豆搜索 JSON 接口（best-effort，风控随时可能拦截）
        val url = "https://www.douban.com/j/search?q=$q&cat=1005&t=20"
        val root = json.parseToJsonElement(
            httpGet(url, mapOf("User-Agent" to UA, "Referer" to "https://www.douban.com/"))
        ).jsonObject
        root["items"]?.jsonArray?.mapNotNull { item ->
            val d = item.jsonObject
            val title = d.str("title")?.replace(htmlTagRegex, "")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val link = d.str("url") ?: return@mapNotNull null
            val rating = d.str("rating")?.takeIf { it.isNotBlank() } ?: "--"
            ContentItem(
                id = "douban-" + link.hashCode().toUInt().toString(16),
                categoryId = category.id,
                source = platform.id,
                title = title,
                summary = "评分：$rating",
                url = link,
                author = d.str("author").orEmpty(),
                tags = listOf("豆瓣")
            )
        }.orEmpty().take(limit)
    }.getOrElse { emptyList() }
}

/**
 * 海外 · Substack：轻量 RSS 解析（订阅流为时间序，无全局检索 API）。
 * 读取一组精选 Substack RSS 源，按类目关键词过滤。无匹配返回空降级浏览器发现页。
 */
class SubstackRssAdapter(pool: ProxyPool) : HttpAdapter(SourcePlatform.SUBSTACK, pool) {

    private val itemRegex = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private val tagRegex = Regex("<(\\w+)>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</\\1>", RegexOption.DOT_MATCHES_ALL)

    // 精选通用 Substack 源（覆盖科技/人文/学术/新闻）
    private val feeds = listOf(
        "https://stratechery.com/feed",
        "https://www.lennysnewsletter.com/feed",
        "https://www.notboring.co/feed",
        "https://asteriskmag.com/feed",
        "https://doomberg.com/feed"
    )

    override suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem> = runCatching {
        val kw = searchQuery(category).lowercase()
        feeds.flatMap { feedUrl ->
            runCatching {
                val xml = httpGet(feedUrl, mapOf("User-Agent" to UA))
                itemRegex.findAll(xml).mapNotNull { m ->
                    val fields = tagRegex.findAll(m.groupValues[1]).associate {
                        it.groupValues[1] to it.groupValues[2].replace(htmlTagRegex, "").trim()
                    }
                    val title = fields["title"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val link = fields["link"]?.takeIf { it.isNotBlank() }
                        ?: fields["guid"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val desc = fields["description"].orEmpty()
                    // 关键词匹配过滤
                    if (!(title + desc).lowercase().contains(kw.split(" ").first())) return@mapNotNull null
                    ContentItem(
                        id = "sub-" + link.hashCode().toUInt().toString(16),
                        categoryId = category.id,
                        source = platform.id,
                        title = title,
                        summary = desc.take(300),
                        url = link,
                        author = fields["creator"].orEmpty(),
                        publishedAt = rssDateToEpochMs(fields["pubDate"]),
                        tags = listOf("Substack", "专栏")
                    )
                }.toList()
            }.getOrElse { emptyList() }
        }.distinctBy { it.url }.take(limit)
    }.getOrElse { emptyList() }
}
