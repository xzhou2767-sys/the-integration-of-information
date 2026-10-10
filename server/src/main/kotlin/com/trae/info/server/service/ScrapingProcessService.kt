package com.trae.info.server.service

import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.SourcePlatform
import com.trae.info.server.ai.AiProviders
import com.trae.info.server.config.ConfigStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台抓取进程服务：
 * - 把一轮抓取拆分为「平台 × 类目」工作单元，按时间预算规划各单元配额（[BudgetPlanner]）；
 * - 抓取阶段：平台间并发 ≤3，同一平台单元串行（反爬），逐单元上报进度（0–90%）；
 * - 总结阶段：对将写入缓存的高相关条目做 AI 一句话总结（占预算 40%，进度 90–100%）；
 * - 抓取结果通过 [ContentService.cacheFeed] 写入缓存，供 /api/feed 即时返回；
 * - 通过 [status] 暴露当前运行状态（阶段/进度/单元数/当前任务），供客户端展示。
 *
 * 设计原则：
 * - 单一进程实例，避免重复启动（重复 start 视为 no-op）；
 * - 协程作用域独立于 HTTP 请求生命周期，stop 即取消整个作用域，phase 置 IDLE；
 * - 失败不中断：单平台/单元失败 log.warn 且计入 failed，不影响其余单元；
 * - 间隔可配置（默认 12 小时），运行中变更间隔需重启进程生效。
 */
class ScrapingProcessService(
    private val contentService: ContentService,
    private val store: ConfigStore,
    private val ai: AiProviders,
    private val summaryStore: SummaryStore,
    /** 决策核心 V2 来源观测存储（影子收集：无论灰度开关都记录，供 V2 开启时使用）。 */
    private val decisionStore: com.trae.info.server.store.DecisionStore? = null
) {
    private val log = LoggerFactory.getLogger(ScrapingProcessService::class.java)

    /** 饥饿补偿：因预算不足被跳过的单元，下一轮权重乘子（成功抓取后清除）。 */
    private val starvationBoost = ConcurrentHashMap<String, Double>()

    private fun boostKey(category: InfoCategory, platform: SourcePlatform) =
        "${category.id}|${platform.id}"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    /** 手动 tick（立即抓取一次）的后台任务；与循环互斥。 */
    private var tickJob: Job? = null
    /** 偏好保存后的防抖自动抓取任务（60s 内多次保存只触发最后一次）。 */
    private var prefTriggerJob: Job? = null

    /** 循环模式记住的本轮时间预算（分钟），供 runLoop 传给 runOnce；0 表示未设置（用默认）。 */
    @Volatile
    private var loopBudgetMinutes = 0

    /** 循环模式记住的 AI 总结级别（brief/default/detailed）与一键总结开关。 */
    @Volatile
    private var loopSummaryMode = "default"
    @Volatile
    private var loopSummarizeAll = false

    private val _status = MutableStateFlow(
        ScrapingStatus(running = false, intervalSec = DEFAULT_INTERVAL_SEC)
    )
    val status: StateFlow<ScrapingStatus> = _status.asStateFlow()

    /** 启动后台抓取循环；若已在运行则返回当前状态。 */
    fun start(
        intervalSec: Long = DEFAULT_INTERVAL_SEC,
        expectedMinutes: Int = 0,
        summaryMode: String = "default",
        summarizeAll: Boolean = false
    ): ScrapingStatus {
        if (loopJob?.isActive == true) {
            log.info("抓取进程已在运行，start 被忽略")
            return _status.value
        }
        val safeInterval = if (intervalSec > 0) intervalSec else DEFAULT_INTERVAL_SEC
        val safeMinutes = clampMinutes(expectedMinutes)
        val safeMode = normalizeMode(summaryMode)
        loopBudgetMinutes = safeMinutes
        loopSummaryMode = safeMode
        loopSummarizeAll = summarizeAll
        _status.value = _status.value.copy(
            running = true,
            intervalSec = safeInterval,
            startedAtEpochMs = System.currentTimeMillis(),
            lastError = null,
            expectedMinutes = safeMinutes,
            summaryMode = safeMode,
            summarizeAll = summarizeAll,
            phase = PHASE_IDLE,
            message = "已启动（单轮时间预算 $safeMinutes 分钟 · 总结 ${modeLabel(safeMode)}" +
                (if (summarizeAll) " · 全部条目" else "") + "）"
        )
        loopJob = scope.launch { runLoop(safeInterval) }
        log.info("抓取进程已启动，间隔 {} 秒，单轮预算 {} 分钟，总结级别 {}，一键总结 {}",
            safeInterval, safeMinutes, safeMode, summarizeAll)
        return _status.value
    }

    /** 停止后台抓取循环与手动 tick（协程取消语义，抓取/总结中的单元随取消中断）。 */
    fun stop(): ScrapingStatus {
        loopJob?.cancel()
        loopJob = null
        tickJob?.cancel()
        tickJob = null
        _status.value = _status.value.copy(
            running = false,
            stoppedAtEpochMs = System.currentTimeMillis(),
            phase = PHASE_IDLE,
            currentTask = "",
            message = "已停止"
        )
        log.info("抓取进程已停止")
        return _status.value
    }

    /**
     * 立即触发一次抓取（后台异步执行，立即返回当前状态；不影响循环节奏）。
     * expectedMinutes<=0 使用默认 15，下限 5 分钟、不设上限。
     * 进度通过 [status] 的 phase/progressPercent 等字段轮询获取，避免 HTTP 长连接超时。
     */
    fun tickOnce(
        expectedMinutes: Int = 0,
        summaryMode: String = "default",
        summarizeAll: Boolean = false
    ): ScrapingStatus {
        if (loopJob?.isActive == true || tickJob?.isActive == true) {
            log.info("抓取已在运行，tick 被忽略")
            return _status.value
        }
        val safeMinutes = clampMinutes(expectedMinutes)
        val safeMode = normalizeMode(summaryMode)
        _status.update {
            it.copy(
                currentRunStartedAtEpochMs = System.currentTimeMillis(),
                phase = PHASE_PLANNING,
                progressPercent = 0,
                completedUnits = 0,
                totalUnits = 0,
                fetchedItems = 0,
                targetItems = 0,
                summarizedItems = 0,
                expectedMinutes = safeMinutes,
                summaryMode = safeMode,
                summarizeAll = summarizeAll,
                currentTask = "",
                lastError = null,
                message = "正在规划工作单元（手动触发，预算 $safeMinutes 分钟 · 总结 ${modeLabel(safeMode)}" +
                    (if (summarizeAll) " · 全部条目" else "") + "）"
            )
        }
        tickJob = scope.launch { runOnce("手动触发", safeMinutes, safeMode, summarizeAll) }
        log.info("手动抓取已触发，单轮预算 {} 分钟，总结级别 {}，一键总结 {}", safeMinutes, safeMode, summarizeAll)
        return _status.value
    }

    /**
     * 偏好保存后防抖触发：60 秒内多次保存只安排最后一次；
     * 到点若循环/手动抓取未在运行，则自动 tick 一次，让新偏好尽快反映到信息流。
     */
    fun schedulePrefTick() {
        prefTriggerJob?.cancel()
        prefTriggerJob = scope.launch {
            delay(PREF_TRIGGER_DEBOUNCE_MS)
            if (loopJob?.isActive != true && tickJob?.isActive != true) {
                log.info("偏好保存后防抖到期，自动触发一次抓取")
                tickOnce()
            }
        }
    }

    /** metrics：跨轮总结缓存条目数。 */
    fun summaryCacheSize(): Int = summaryStore.size()

    private suspend fun runLoop(intervalSec: Long) {
        while (currentCoroutineContext().isActive) {
            runOnce("循环触发", loopBudgetMinutes, loopSummaryMode, loopSummarizeAll)
            _status.value = _status.value.copy(nextAtEpochMs = System.currentTimeMillis() + intervalSec * 1000)
            delay(intervalSec * 1000)
        }
    }

    /**
     * 单轮抓取：规划（PLANNING）→ 抓取（FETCHING）→ 总结（SUMMARIZING）→ 完成（DONE）。
     * 全程受时间预算约束：deadline = started + budgetMinutes*60s。
     */
    private suspend fun runOnce(reason: String, expectedMinutes: Int, summaryMode: String, summarizeAll: Boolean) {
        val started = System.currentTimeMillis()
        val budgetMinutes = clampMinutes(expectedMinutes)
        val mode = normalizeMode(summaryMode)
        val deadline = started + budgetMinutes * 60_000L
        // 一键总结：规划期按 100% 条目预留总结成本；否则按默认覆盖率
        val coverage = if (summarizeAll) 1.0 else BudgetPlanner.SUMMARY_COVERAGE
        try {
            val categories = store.categories()
            val prefSummary = store.get().preferenceSummary
            val pref = PreferenceEngine.parse(prefSummary)
            val displayMode = pref.displayMode
            val posKw = pref.positiveKeywords
            val negKw = pref.negativeKeywords
            val globalEnabled = contentService.enabledSources().toSet()

            // 各类目与偏好的相关度（用于浏览器 top-N 圈定与权重融合）
            val catRelevance: Map<String, Int> = categories.associate {
                it.id to PreferenceEngine.categoryRelevance(it, posKw, negKw)
            }
            // 浏览器型平台（单元固定开销最高）只排相关度 top-N 类目，大幅削减冷启动/渲染单元数
            val topBrowserCatIds = categories
                .sortedByDescending { catRelevance[it.id] ?: 0 }
                .take(BROWSER_TOP_CATEGORIES)
                .map { it.id }
                .toSet()
            // 浏览器主通道平台单一事实源（含成本档 40s 计价与 top-N 类目圈定）
            val browserPrimaryPlatforms = BudgetPlanner.BROWSER_PRIMARY

            // ---- a. PLANNING：生成 平台×类目 工作单元，按时间预算分配配额 ----
            _status.update {
                it.copy(
                    currentRunStartedAtEpochMs = started,
                    phase = PHASE_PLANNING,
                    progressPercent = 0,
                    completedUnits = 0,
                    totalUnits = 0,
                    fetchedItems = 0,
                    targetItems = 0,
                    summarizedItems = 0,
                    expectedMinutes = budgetMinutes,
                    summaryMode = mode,
                    summarizeAll = summarizeAll,
                    currentTask = "",
                    message = "正在规划工作单元（$reason，预算 $budgetMinutes 分钟）"
                )
            }
            val planUnits = BudgetPlanner.plan(
                categories,
                platformsByCategory = { cat ->
                    // 仅保留「类目订阅启用 ∩ 全局启用」的平台；
                    // 浏览器型平台只进入相关度 top-N 类目
                    contentService.enabledSourcesOf(cat)
                        .filter { it in globalEnabled }
                        .filter { p -> p !in browserPrimaryPlatforms || cat.id in topBrowserCatIds }
                },
                budgetMinutes = budgetMinutes,
                weightOf = { cat, p ->
                    val rel = catRelevance[cat.id] ?: 0
                    // 类目偏好相关度加成（每点 +15%），再叠加跨轮饥饿补偿乘子
                    val relFactor = 1.0 + rel * 0.15
                    val boost = starvationBoost[boostKey(cat, p)] ?: 1.0
                    PreferenceEngine.platformWeight(displayMode, prefSummary, p) * relFactor * boost
                },
                summaryCoverage = coverage
            )
            val totalUnits = planUnits.size
            if (totalUnits == 0) {
                _status.update {
                    it.copy(
                        lastRunStartedAtEpochMs = started,
                        lastRunFinishedAtEpochMs = System.currentTimeMillis(),
                        lastItemsCount = 0,
                        lastError = null,
                        totalRuns = it.totalRuns + 1,
                        phase = PHASE_DONE,
                        progressPercent = 100,
                        message = "无可用工作单元（订阅平台均已关闭或无类目）"
                    )
                }
                return
            }
            _status.update {
                it.copy(
                    phase = PHASE_FETCHING,
                    totalUnits = totalUnits,
                    targetItems = planUnits.sumOf { u -> u.limit },
                    message = "抓取中：$totalUnits 个工作单元（$reason）"
                )
            }

            // ---- b. FETCHING：全局优先级队列；许可证每单元申领；同平台串行（反爬） ----
            // 高权重单元先入队（Semaphore 公平 FIFO 近似按此序领证），尾部平台不再被整队列阻塞
            // sourceSchedulerV2（灰度开关）：单元优先级切换为收益调度
            // priority = expectedUseful/max(cost,ε)+explorationBonus（观测驱动），再除静态成本档
            val orderedUnits = if (store.get().sourceSchedulerV2 && decisionStore != null) {
                planUnits.sortedWith(
                    compareByDescending<ScrapeUnit> {
                        com.trae.info.server.decision.SourceScheduler.priority(
                            it.platform, decisionStore.observationOf(it.platform), it.weight
                        ) / BudgetPlanner.unitCostSec(it.platform)
                    }
                )
            } else {
                planUnits.sortedWith(compareByDescending<ScrapeUnit> { it.weight })
            }
            val platformLocks = ConcurrentHashMap<SourcePlatform, Mutex>()
            val fetchSem = Semaphore(3)
            val itemsByCategory = ConcurrentHashMap<String, MutableList<ContentItem>>()
            val completedUnits = AtomicInteger(0)
            val fetchedItems = AtomicInteger(0)
            val failedUnits = AtomicInteger(0)
            val skippedUnits = AtomicInteger(0)

            fun reportProgress() {
                val done = completedUnits.get()
                _status.update {
                    it.copy(
                        completedUnits = done,
                        fetchedItems = fetchedItems.get(),
                        progressPercent = done * 90 / totalUnits
                    )
                }
            }

            suspend fun processUnit(unit: ScrapeUnit) {
                if (!currentCoroutineContext().isActive) return
                // 单元开始前检查剩余预算，不足则跳过（不中断其余单元）
                if (System.currentTimeMillis() >= deadline) {
                    skippedUnits.incrementAndGet()
                    // 饥饿补偿：下一轮权重 ×1.5（封顶 3）
                    starvationBoost.compute(boostKey(unit.category, unit.platform)) { _, v ->
                        ((v ?: 1.0) * 1.5).coerceAtMost(3.0)
                    }
                    // 跳过也计入完成，进度不再卡在 <90%
                    completedUnits.incrementAndGet()
                    reportProgress()
                    log.warn("预算不足跳过单元 {} × {}", unit.platform.id, unit.category.name)
                    return
                }
                // 已实际获得服务：清除历史饥饿补偿
                starvationBoost.remove(boostKey(unit.category, unit.platform))
                _status.update { it.copy(currentTask = "${unit.platform.id} × ${unit.category.name}") }
                val unitStart = System.currentTimeMillis()
                runCatching {
                    // 偏好词补发：相关类目把 top3 正向偏好词并入检索词，提升源端召回（不替换类目词）
                    val fetchCategory = if ((catRelevance[unit.category.id] ?: 0) > 0) {
                        unit.category.copy(keywords = (unit.category.keywords + posKw.take(3)).distinct())
                    } else unit.category
                    // 多抓再筛：2.5 倍配额、上限 30 条
                    val rawLimit = (unit.limit * 2.5).toInt().coerceAtMost(30)
                    // 观测口径：单元开始前该类目缓存身份集合（用于「新增唯一数」）
                    val before = contentService.cachedIdentitiesOf(unit.category.id)
                    val raw = contentService.fetchUnit(fetchCategory, unit.platform, rawLimit)
                    // 正向（偏好∪类目）加分、负向屏蔽词扣分，取 top limit
                    val posForScore = (posKw + unit.category.keywords).distinct()
                    val kept = raw.map { it to PreferenceEngine.relevanceScore(it, posForScore, negKw) }
                        .sortedWith(
                            compareByDescending<Pair<ContentItem, Int>> { it.second }
                                .thenByDescending { it.first.publishedAt }
                        )
                        .take(unit.limit)
                        .map { it.first }
                    if (kept.isNotEmpty()) {
                        itemsByCategory
                            .computeIfAbsent(unit.category.id) { Collections.synchronizedList(mutableListOf()) }
                            .addAll(kept)
                    }
                    fetchedItems.addAndGet(kept.size)
                    // 来源观测影子收集（方案 §5.2）：奖励=新增唯一数，非原始抓到数量
                    if (decisionStore != null) {
                        val uniqueNew = kept.count { ContentPolicy.identityOf(it) !in before }
                        decisionStore.recordUnit(
                            unit.platform, success = true, uniqueNew = uniqueNew,
                            elapsedMs = System.currentTimeMillis() - unitStart
                        )
                    }
                }.onFailure {
                    // 仅协程真被取消时上抛；网络超时（HttpTimeoutCancellationException）按单元失败处理
                    if (it is CancellationException && !currentCoroutineContext().isActive) throw it
                    failedUnits.incrementAndGet()
                    if (decisionStore != null) {
                        decisionStore.recordUnit(
                            unit.platform, success = false, uniqueNew = 0,
                            elapsedMs = System.currentTimeMillis() - unitStart
                        )
                    }
                    log.warn("单元 {} × {} 抓取失败：{}", unit.platform.id, unit.category.name, it.message)
                }
                completedUnits.incrementAndGet()
                reportProgress()
            }

            coroutineScope {
                orderedUnits.forEach { unit ->
                    launch {
                        platformLocks.getOrPut(unit.platform) { Mutex() }.withLock {
                            fetchSem.withPermit {
                                processUnit(unit)
                            }
                        }
                    }
                }
            }

            // ---- c. SUMMARIZING：并发 AI 总结（级别可选；一键总结覆盖全部条目） ----
            // 展示模式 compact 时自动采用简短总结（省 token、更快）；用户显式选详细则尊重
            val effectiveMode = when {
                mode == "detailed" -> "detailed"
                mode == "brief" || displayMode == "compact" -> "brief"
                else -> "default"
            }
            _status.update {
                it.copy(
                    phase = PHASE_SUMMARIZING,
                    currentTask = "AI 总结（${modeLabel(effectiveMode)}）"
                )
            }

            // 每类目：去重后按发布时间降序取前 FEED_LIMIT 条（缓存保留池），只对将写入缓存的条目总结
            val cacheByCategory: Map<String, List<ContentItem>> = itemsByCategory.mapValues { (_, list) ->
                synchronized(list) {
                    list.distinctBy { it.url.ifBlank { it.id } }
                        .sortedByDescending { it.publishedAt }
                        .take(FEED_LIMIT)
                }
            }
            // 跨类目候选序列：同条去重 by id，按相关度降序（同分按平台内质量分，再按时间降序）
            val rankPos = (posKw + categories.flatMap { it.keywords }).distinct()
            val candidates = cacheByCategory.values.flatten()
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<ContentItem> {
                        PreferenceEngine.relevanceScore(it, rankPos, negKw)
                    }.thenByDescending { PreferenceEngine.qualityScoreOf(it) }
                    .thenByDescending { it.publishedAt }
                )

            /** 低价值条目跳过 AI 总结：X 推文（标题即正文）、B 站/StackExchange/豆瓣 元数据摘要、过短内容。 */
            fun isLowValue(item: ContentItem): Boolean {
                val xLike = item.source == SourcePlatform.TWITTER.id &&
                        item.summary.isNotBlank() && item.title.length <= 80 &&
                        item.summary.startsWith(item.title.take(40))
                val biliMeta = item.source == SourcePlatform.BILIBILI.id &&
                        item.summary.contains("UP主") && item.summary.contains("播放")
                // StackExchange HTTP 通道输出「投票：x↑y↓｜Wilson：z」纯元数据
                val seMeta = item.source == SourcePlatform.STACKEXCHANGE.id &&
                        item.summary.contains("投票：") && item.summary.contains("Wilson：")
                // 豆瓣 HTTP 通道输出「评分：x」纯评分（浏览器通道「评分：x｜正文」不受影响）
                val doubanRating = item.source == SourcePlatform.DOUBAN.id &&
                        item.summary.startsWith("评分：") && item.summary.length <= 30
                val tooShort = item.title.length < 8 && item.summary.length < 20
                return xLike || biliMeta || seMeta || doubanRating || tooShort
            }

            // 单条总结耗时按级别估算：模糊更快、详细更慢
            val perSummarySec = when (effectiveMode) {
                "brief" -> PER_SUMMARY_BRIEF_SEC
                "detailed" -> PER_SUMMARY_DETAILED_SEC
                else -> BudgetPlanner.PER_ITEM_SUMMARY_SEC
            }
            val remainSec = ((deadline - System.currentTimeMillis()).coerceAtLeast(0)) / 1000
            val budgetTarget = (remainSec * SUMMARY_CONCURRENCY / perSummarySec).toInt().coerceAtLeast(0)
            val worthSummarizing = candidates.filterNot { isLowValue(it) }
            // 一键总结：目标=全部候选；默认：仅预算内的高相关条目
            val summaryTarget = if (summarizeAll) worthSummarizing.size
                else minOf(worthSummarizing.size, budgetTarget, MAX_SUMMARIES_PER_RUN)
            val targets = worthSummarizing.take(summaryTarget)

            val summaries = ConcurrentHashMap<String, String>()
            val summarized = AtomicInteger(0)
            val summarySem = Semaphore(SUMMARY_CONCURRENCY)
            coroutineScope {
                targets.forEach { item ->
                    launch {
                        if (System.currentTimeMillis() >= deadline || !currentCoroutineContext().isActive) return@launch
                        summarySem.withPermit {
                            if (System.currentTimeMillis() >= deadline) return@withPermit
                            // 跨轮持久缓存命中则不重复调用 AI
                            val text = try {
                                val remainingMs = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                                summaryStore.getOrCompute(effectiveMode, item.title, item.summary) {
                                    withTimeout(remainingMs) {
                                        ai.summarize(item.title, item.summary, effectiveMode).content
                                    }.trim().takeIf { it.isNotBlank() }
                                }
                            } catch (e: TimeoutCancellationException) {
                                log.warn("条目总结超时，跳过：{}", item.id); null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                log.warn("条目总结失败，跳过：{}（{}）", item.id, e.message); null
                            }
                            if (text != null) {
                                summaries[item.id] = text
                            }
                        }
                        val n = summarized.incrementAndGet()
                        _status.update {
                            it.copy(
                                summarizedItems = n,
                                progressPercent = 90 + n * 10 / maxOf(1, targets.size)
                            )
                        }
                    }
                }
            }
            runCatching { summaryStore.flush() }

            // ---- d. 写缓存 + 收尾 ----
            var totalItems = 0
            for ((catId, items) in cacheByCategory) {
                val final = items.map { summaries[it.id]?.let { s -> it.copy(aiSummary = s) } ?: it }
                contentService.cacheFeed(catId, final)
                totalItems += final.size
            }
            runCatching { contentService.persistFeedCache() }
            // 来源观测落盘（每轮一次；影子收集数据供 V2 调度）
            runCatching { decisionStore?.flush() }
            val elapsedSec = (System.currentTimeMillis() - started) / 1000
            _status.update {
                it.copy(
                    lastRunStartedAtEpochMs = started,
                    lastRunFinishedAtEpochMs = System.currentTimeMillis(),
                    lastItemsCount = totalItems,
                    lastFailedCategories = failedUnits.get(),
                    lastError = null,
                    totalRuns = it.totalRuns + 1,
                    phase = PHASE_DONE,
                    progressPercent = 100,
                    currentTask = "",
                    message = "本轮 ${planUnits.size} 单元 / $totalItems 条 / 总结 ${summaries.size} 条" +
                        (if (summarizeAll) "（全部条目）" else "") +
                        " / 耗时 ${elapsedSec} 秒" +
                        (if (skippedUnits.get() > 0) "（预算不足跳过 ${skippedUnits.get()} 单元）" else "")
                )
            }
        } catch (e: CancellationException) {
            throw e // stop() 取消：状态由 stop() 统一置 IDLE
        } catch (e: Throwable) {
            log.error("抓取循环异常", e)
            _status.update {
                it.copy(
                    lastRunStartedAtEpochMs = started,
                    lastRunFinishedAtEpochMs = System.currentTimeMillis(),
                    lastError = e.message,
                    phase = PHASE_DONE,
                    currentTask = "",
                    message = "本轮异常结束：${e.message}"
                )
            }
        }
    }

    /** 单轮时间预算：<=0 用默认 15；下限 5 分钟，不设上限（用户可填任意 ≥5 的值）。 */
    private fun clampMinutes(m: Int): Int =
        (if (m <= 0) DEFAULT_EXPECTED_MINUTES else m).coerceAtLeast(MIN_EXPECTED_MINUTES)

    /** 总结级别归一化：仅接受 brief/detailed，其余一律 default。 */
    private fun normalizeMode(mode: String?): String = when (mode) {
        "brief", "detailed" -> mode
        else -> "default"
    }

    /** 总结级别中文标签（状态消息用）。 */
    private fun modeLabel(mode: String): String = when (mode) {
        "brief" -> "模糊"
        "detailed" -> "详细"
        else -> "默认"
    }

    companion object {
        /** 默认抓取间隔：12 小时（43200 秒），避免高频触发平台反爬。 */
        const val DEFAULT_INTERVAL_SEC: Long = 43200L

        /** 单轮默认时间预算（分钟）：expectedMinutes<=0 时使用。 */
        const val DEFAULT_EXPECTED_MINUTES = 15

        /** 单轮时间预算下限（分钟）。 */
        const val MIN_EXPECTED_MINUTES = 5

        /** 单条总结预算耗时（秒）：模糊级别更快、详细级别更慢（默认档用 BudgetPlanner.PER_ITEM_SUMMARY_SEC=12）。 */
        private const val PER_SUMMARY_BRIEF_SEC = 10.0
        private const val PER_SUMMARY_DETAILED_SEC = 20.0

        /** AI 总结并发度（0.5.2：串行 → 并发 3）。 */
        private const val SUMMARY_CONCURRENCY = 3

        /** 浏览器型平台（抖音/小红书/Quora）每轮只排相关度最高的类目数，削减昂贵渲染单元。 */
        private const val BROWSER_TOP_CATEGORIES = 3

        /** 默认轮次限制付费摘要数量；“总结全部”仍尊重用户的显式请求。 */
        private const val MAX_SUMMARIES_PER_RUN = 12

        /** 每类目缓存保留池上限（0.5.2：50 → 80；/api/feed 展示仍取前 50）。 */
        private const val FEED_LIMIT = 80

        /** 偏好保存后自动抓取的防抖延时：60 秒。 */
        private const val PREF_TRIGGER_DEBOUNCE_MS = 60_000L

        // 阶段取值：与 core ScrapingStatusDto.phase 注释保持一致
        private const val PHASE_IDLE = "IDLE"
        private const val PHASE_PLANNING = "PLANNING"
        private const val PHASE_FETCHING = "FETCHING"
        private const val PHASE_SUMMARIZING = "SUMMARIZING"
        private const val PHASE_DONE = "DONE"
    }
}

/** 抓取进程对外状态。 */
data class ScrapingStatus(
    val running: Boolean,
    val intervalSec: Long,
    val startedAtEpochMs: Long? = null,
    val stoppedAtEpochMs: Long? = null,
    val currentRunStartedAtEpochMs: Long? = null,
    val lastRunStartedAtEpochMs: Long? = null,
    val lastRunFinishedAtEpochMs: Long? = null,
    val nextAtEpochMs: Long? = null,
    val lastItemsCount: Int = 0,
    val lastFailedCategories: Int = 0,
    val totalRuns: Long = 0L,
    val lastError: String? = null,
    val message: String = "",
    // ---- 进度上报（与 core ScrapingStatusDto 新增字段对齐）----
    /** 当前阶段：IDLE / PLANNING / FETCHING / SUMMARIZING / DONE */
    val phase: String = "IDLE",
    /** 进度百分比 0–100（抓取 0–90%，总结 90–100%） */
    val progressPercent: Int = 0,
    /** 已完成工作单元数（平台×类目） */
    val completedUnits: Int = 0,
    /** 总工作单元数（平台×类目） */
    val totalUnits: Int = 0,
    /** 本轮已抓取（保留）条目数 */
    val fetchedItems: Int = 0,
    /** 目标条目数（按时间预算推算的计划抓取总量） */
    val targetItems: Int = 0,
    /** 已尝试 AI 总结的条目数 */
    val summarizedItems: Int = 0,
    /** 本轮预计总耗时（分钟） */
    val expectedMinutes: Int = 0,
    /** 当前正在处理的任务描述，如 "bilibili × 人工智能" */
    val currentTask: String = "",
    /** 本轮 AI 总结级别：brief / default / detailed */
    val summaryMode: String = "default",
    /** 本轮是否一键总结所有条目 */
    val summarizeAll: Boolean = false
)
