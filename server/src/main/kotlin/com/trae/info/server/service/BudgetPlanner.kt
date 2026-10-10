package com.trae.info.server.service

import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.SourcePlatform
import kotlin.math.ceil

/**
 * 媒体形态分组：决定单条解析成本与 AI 总结占比。
 * - VIDEO：B站 / YouTube / 抖音
 * - IMAGE：小红书
 * - TEXT：知乎 / Reddit / Quora / 中国大学MOOC / X(Twitter)
 */
enum class MediaKind { VIDEO, IMAGE, TEXT }

/**
 * 最小抓取工作单元 = 平台 × 类目。
 * @param limit 本单元计划保留的条目配额（规划期给出，运行中可被 [BudgetPlanner.recompute] 修正）
 * @param mediaKind 平台媒体形态（成本模型用）
 * @param weight 平台权重 w_p（来自偏好引擎，影响配额分配）
 */
data class ScrapeUnit(
    val category: InfoCategory,
    val platform: SourcePlatform,
    val limit: Int,
    val mediaKind: MediaKind,
    val weight: Double
)

/**
 * 时间预算规划器（纯函数，便于测试）：
 * 把单轮总预算（分钟）按 40% 总结 / 60% 抓取切分，再按成本模型
 * 推算本轮可抓条目总数 maxItems，并均分到各工作单元后按平台权重归一化。
 */
object BudgetPlanner {

    /** 抓取预算占总预算比例（其余 40% 预留给 AI 总结阶段）。 */
    const val FETCH_BUDGET_RATIO = 0.6

    /** 单条内容解析成本（秒）。 */
    const val PER_ITEM_PARSE_SEC = 0.2

    /** 单条 AI 一句话总结成本（秒）。 */
    const val PER_ITEM_SUMMARY_SEC = 12.0

    /** 总结覆盖率：仅约 35% 的高相关条目进入总结阶段。 */
    const val SUMMARY_COVERAGE = 0.35

    /** 浏览器登录态型平台单元固定开销（秒）——主通道为云端浏览器（含冷启动/渲染）。 */
    const val BROWSER_UNIT_SEC = 40.0

    /** API 型视频平台单元固定开销（秒）。 */
    const val API_VIDEO_UNIT_SEC = 5.0

    /** SerpAPI / 图文型平台单元固定开销（秒）。 */
    const val API_IMAGE_UNIT_SEC = 8.0

    /** API 型文本平台单元固定开销（秒）。 */
    const val API_TEXT_UNIT_SEC = 6.0

    /** 抓取阶段有效并发度（平台间并发，浏览器通道内部另有 2 路上限）。 */
    const val EFFECTIVE_CONCURRENCY = 3.0

    /** Bound queue growth on small cloud instances; unused capacity goes to larger per-unit limits. */
    const val MAX_UNITS_PER_MINUTE = 6
    const val EXPLORATION_RATIO = 0.10
    private const val FIXED_COST_BUDGET_RATIO = 0.75

    /**
     * 浏览器主通道平台单一事实源（primary=null，每次抓取需浏览器通道）：
     * - 成本档：[unitCostSec] 按 [BROWSER_UNIT_SEC] 计价；
     * - 抓取规划：ScrapingProcessService 仅让这些平台进入相关度 top-N 类目（控制冷启动单元数）。
     * 新增平台若装配为浏览器主通道，必须在此登记（见 platform-onboarding 技能）。
     */
    val BROWSER_PRIMARY: Set<SourcePlatform> = setOf(
        SourcePlatform.DOUYIN, SourcePlatform.XIAOHONGSHU, SourcePlatform.QUORA,
        SourcePlatform.WECHAT, SourcePlatform.CAIXIN_PENGPAI,
        SourcePlatform.GOOGLE_SCHOLAR, SourcePlatform.JSTOR_ACM, SourcePlatform.CNKI
    )

    /** 按平台归类媒体形态。 */
    fun mediaKindOf(platform: SourcePlatform): MediaKind = when (platform) {
        SourcePlatform.BILIBILI, SourcePlatform.YOUTUBE, SourcePlatform.DOUYIN -> MediaKind.VIDEO
        SourcePlatform.XIAOHONGSHU -> MediaKind.IMAGE
        else -> MediaKind.TEXT
    }

    /** 视频条目占比 r_p：VIDEO 0.9 / IMAGE 0.3 / TEXT 0.05。 */
    fun videoRatioOf(kind: MediaKind): Double = when (kind) {
        MediaKind.VIDEO -> 0.9
        MediaKind.IMAGE -> 0.3
        MediaKind.TEXT -> 0.05
    }

    /**
     * 单元固定开销 C_p（秒）：浏览器主通道平台 40s（[BROWSER_PRIMARY]）；
     * 其余按媒体档近似：视频 5s / 图文 8s / 文本 6s。
     */
    fun unitCostSec(platform: SourcePlatform): Double =
        if (platform in BROWSER_PRIMARY) BROWSER_UNIT_SEC
        else when (mediaKindOf(platform)) {
            MediaKind.VIDEO -> API_VIDEO_UNIT_SEC
            MediaKind.IMAGE -> API_IMAGE_UNIT_SEC
            MediaKind.TEXT -> API_TEXT_UNIT_SEC
        }

    /**
     * 规划一轮抓取的工作单元与配额。
     *
     * 成本模型（0.5.2 修正）：
     * - 总预算 B = budgetMinutes*60；抓取阶段预算 fetchSec = 0.6B（其余 40% 归独立的总结阶段，
     *   总结成本不再计入抓取的逐条成本，避免重复扣减导致所有单元配额被 clamp 到 1）；
     * - 单元固定开销按有效并发折算：ΣC_p / [EFFECTIVE_CONCURRENCY]（单元并行执行，墙钟开销重叠）；
     * - 逐条成本只计抓取/解析 [PER_ITEM_PARSE_SEC]；
     * - maxItems = (fetchSec − ΣC_p/并发) / PER_ITEM_PARSE_SEC；
     * - 权重 weightOf(类目, 平台) 融合平台权重与类目偏好相关度，按权重归一化分配配额。
     */
    fun plan(
        categories: List<InfoCategory>,
        platformsByCategory: (InfoCategory) -> List<SourcePlatform>,
        budgetMinutes: Int,
        weightOf: (InfoCategory, SourcePlatform) -> Double,
        /** 进入 AI 总结的条目占比（仅用于外部预算说明，不再影响抓取逐条成本） */
        summaryCoverage: Double = SUMMARY_COVERAGE
    ): List<ScrapeUnit> {
        val fetchSec = budgetMinutes * 60.0 * FETCH_BUDGET_RATIO
        val allPairs = categories.flatMap { cat -> platformsByCategory(cat).map { cat to it } }
        if (allPairs.isEmpty()) return emptyList()

        // A short budget must be allowed to skip low-yield work. Previously every
        // platform/category pair was forced into every run with at least one item.
        val ranked = allPairs.map { (cat, platform) ->
            Triple(cat, platform, weightOf(cat, platform).coerceAtLeast(0.1))
        }.sortedByDescending { (_, platform, weight) -> weight / unitCostSec(platform) }
        val maxUnits = (budgetMinutes * MAX_UNITS_PER_MINUTE)
            .coerceAtLeast(EFFECTIVE_CONCURRENCY.toInt())
            .coerceAtMost(ranked.size)
        val explorationSlots = ceil(maxUnits * EXPLORATION_RATIO).toInt().coerceAtLeast(1)
        val exploitSlots = (maxUnits - explorationSlots).coerceAtLeast(1)
        val fixedCostBudget = fetchSec * EFFECTIVE_CONCURRENCY * FIXED_COST_BUDGET_RATIO
        val selected = mutableListOf<Triple<InfoCategory, SourcePlatform, Double>>()
        var selectedFixedCost = 0.0

        fun addIfAffordable(candidate: Triple<InfoCategory, SourcePlatform, Double>): Boolean {
            val cost = unitCostSec(candidate.second)
            if (selected.size >= maxUnits ||
                (selected.isNotEmpty() && selectedFixedCost + cost > fixedCostBudget)) return false
            selected += candidate
            selectedFixedCost += cost
            return true
        }

        ranked.take(exploitSlots).forEach(::addIfAffordable)
        val remaining = ranked.filterNot { it in selected }
        if (remaining.isNotEmpty() && selected.size < maxUnits) {
            val stride = (remaining.size / explorationSlots.coerceAtLeast(1)).coerceAtLeast(1)
            remaining.filterIndexed { index, _ -> index % stride == 0 }
                .take(explorationSlots)
                .forEach(::addIfAffordable)
        }
        if (selected.isEmpty()) addIfAffordable(ranked.first())

        val pairs = selected.map { it.first to it.second }
        val kinds = pairs.map { (_, p) -> mediaKindOf(p) }
        val fixedSec = pairs.sumOf { (_, p) -> unitCostSec(p) } / EFFECTIVE_CONCURRENCY
        val perItemSec = PER_ITEM_PARSE_SEC
        val maxItems = ((fetchSec - fixedSec).coerceAtLeast(0.0) / perItemSec)
            .toInt().coerceAtLeast(pairs.size)

        val base = (maxItems / pairs.size).coerceIn(1, 12)
        val weights = selected.map { it.third }
        val weightedSum = base * weights.sum()
        val scale = if (weightedSum > 0) maxItems / weightedSum else 0.0

        return pairs.mapIndexed { i, (cat, p) ->
            ScrapeUnit(
                category = cat,
                platform = p,
                limit = (base * weights[i] * scale).toInt().coerceIn(1, 15),
                mediaKind = kinds[i],
                weight = weights[i]
            )
        }
    }

    /**
     * 运行中二次修正：每完成一个单元后，用剩余预算重算未开始单元的配额，
     * 防止前段单元超支导致后段单元被大量跳过。固定开销按并发折算，逐条只计解析成本。
     */
    fun recompute(
        remaining: List<ScrapeUnit>,
        remainSec: Long,
        summaryCoverage: Double = SUMMARY_COVERAGE
    ): List<ScrapeUnit> {
        if (remaining.isEmpty()) return remaining
        val fetchSec = remainSec * FETCH_BUDGET_RATIO
        val fixedSec = remaining.sumOf { unitCostSec(it.platform) } / EFFECTIVE_CONCURRENCY
        val perItemSec = PER_ITEM_PARSE_SEC
        val maxItems = ((fetchSec - fixedSec).coerceAtLeast(0.0) / perItemSec)
            .toInt().coerceAtLeast(remaining.size)
        val wSum = remaining.sumOf { it.weight.coerceAtLeast(0.1) }
        return remaining.map { u ->
            u.copy(limit = (maxItems * u.weight.coerceAtLeast(0.1) / wSum).toInt().coerceIn(1, 15))
        }
    }
}
