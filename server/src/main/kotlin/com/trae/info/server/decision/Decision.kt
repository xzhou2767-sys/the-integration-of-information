package com.trae.info.server.decision

import com.trae.info.core.model.ContentItem

/**
 * 决策核心 V2 数据契约（重写决策核心与复用采集组件方案 §4）。
 * 逻辑字段，不直接暴露给旧客户端；全部新增字段有缺省值保证兼容。
 */

/** 准入裁决。 */
enum class Verdict { ACCEPT, BACKGROUND_ONLY, NEEDS_EVIDENCE, REJECT }

/** 单条内容的准入决策（含策略版本与理由码，可解释）。 */
data class Decision(
    val verdict: Verdict,
    /** 理由码：如 WIKI_SOURCE / FUTURE_DATE / STALE_PAPER / STALE_DYNAMIC / UNKNOWN_DATE_PAPER / STATIC_KNOWLEDGE / MALFORMED / OK */
    val reasonCode: String,
    /** 命中的时间窗（毫秒）：论文 90 天 / 动态 45 天；未应用为 null。 */
    val windowMs: Long? = null,
    /** 时效评分 0–1（越新越高；未知日期 0.3 中性偏保守，不伪装新内容）。 */
    val freshness: Double = 0.0,
    val policyVersion: String = AdmissionPolicy.POLICY_VERSION
)

/** 来源观测（方案 §5.2 调度收益的输入；持久于 DecisionStore）。 */
@kotlinx.serialization.Serializable
data class SourceObservation(
    val sourceId: String,
    val attempts: Long = 0,
    val successes: Long = 0,
    /** 新增唯一条目数（去重后新进入缓存的数量）。 */
    val uniqueNew: Long = 0,
    val elapsedMsTotal: Long = 0,
    val paidCostTotal: Double = 0.0,
    val transferBytesTotal: Long = 0,
    val lastAttemptAtEpochMs: Long = 0,
    val lastSuccessAtEpochMs: Long = 0,
    /** 探索轮换游标：最近一次被探索调度选中时间。 */
    val lastExploredAtEpochMs: Long = 0
) {
    /** 平滑成功率（拉普拉斯平滑避免小样本 0/1 极端）。 */
    val successRate: Double get() = if (attempts == 0L) 0.5 else (successes + 1.0) / (attempts + 2.0)
    /** 每次尝试的平滑新增产出（≥0.05 下限防止单位成本发散）。 */
    val usefulPerAttempt: Double get() =
        if (attempts == 0L) 0.2 else (uniqueNew + 0.5) / (attempts + 2.0)
    val avgElapsedMs: Double get() = if (attempts == 0L) 0.0 else elapsedMsTotal.toDouble() / attempts
}

/** 排序评分解释（方案 §5.4：输出评分解释而非黑盒分数）。 */
data class ScoreExplanation(
    val itemId: String,
    /** 偏好相关性分（关键词命中数加权）。 */
    val relevance: Double,
    /** 平台内质量分（HN hot / SE Wilson / 豆瓣评分；黑箱平台 0.5 中性）。 */
    val quality: Double,
    /** 时效分（准入阶段计算的 freshness）。 */
    val freshness: Double,
    /** 总分 = relevance×2 + quality×0.5 + freshness×1（权重为配置初值）。 */
    val total: Double,
    /** 命中的正向偏好词（可解释）。 */
    val matchedKeywords: List<String> = emptyList()
)

/** 检索规格（方案 §4.2：偏好下推到来源的证明载体）。 */
data class QuerySpec(
    val sourceId: String,
    val categoryId: String,
    /** 实际检索词：用户实体（来自偏好）∪ 类目词，用户实体优先。 */
    val terms: List<String>,
    /** 排除词（来源支持时下推；否则本地过滤）。 */
    val excludeTerms: List<String>,
    /** 时间窗下界（epoch ms）。 */
    val sinceMs: Long,
    val languages: List<String> = listOf("zh", "en"),
    val profileVersion: String = ""
)

/** 统一准入与策略常量。 */
object AdmissionPolicy {
    const val POLICY_VERSION = "adm-v2.1"

    private const val DAY_MS = 24L * 60 * 60 * 1000
    /** 论文窗（arXiv）：90 天。 */
    const val PAPER_WINDOW_MS = 90L * DAY_MS
    /** 动态内容窗：45 天。 */
    const val DYNAMIC_WINDOW_MS = 45L * DAY_MS

    private val staticTitleMarkers = listOf(
        "什么是", "是什么意思", "基础知识", "入门教程", "百科", "词条", "定义",
        "what is ", "beginner's guide", "beginners guide", "encyclopedia", "definition of "
    )
    private val noveltyMarkers = listOf(
        "发布", "更新", "新版", "突破", "研究发现", "宣布", "实测", "评测", "报告",
        "released", "launch", "update", "announced", "study finds", "benchmark"
    )

    /**
     * 执行次序（方案 §5.3）：来源/类型硬限制 → 日期可信度 → 时效 → 固定知识标记。
     * 论文只套论文窗（P0 修复：45-90 天论文不再被通用窗误删）；
     * 论文日期未知 → NEEDS_EVIDENCE（不伪装新内容）；动态日期未知 → 保守放行但 freshness 降权。
     */
    fun decide(item: ContentItem, nowMs: Long = System.currentTimeMillis()): Decision {
        // 1) 来源硬限制
        if (item.source == "wikipedia") return Decision(Verdict.REJECT, "WIKI_SOURCE")
        if (item.title.isBlank() || item.url.isBlank()) return Decision(Verdict.REJECT, "MALFORMED")

        // 2) 日期可信度：未来日期（超 1 天时钟误差）异常
        if (item.publishedAt > nowMs + DAY_MS) return Decision(Verdict.REJECT, "FUTURE_DATE")

        val age = if (item.publishedAt > 0) nowMs - item.publishedAt else null
        val isPaper = item.source == "arxiv"

        // 3) 时效（按内容类型唯一选择时间窗）
        if (isPaper) {
            if (age == null) return Decision(Verdict.NEEDS_EVIDENCE, "UNKNOWN_DATE_PAPER")
            if (age > PAPER_WINDOW_MS) return Decision(
                Verdict.BACKGROUND_ONLY, "STALE_PAPER", windowMs = PAPER_WINDOW_MS,
                freshness = freshnessOf(age, PAPER_WINDOW_MS)
            )
        } else if (age != null && age > DYNAMIC_WINDOW_MS) {
            return Decision(
                Verdict.BACKGROUND_ONLY, "STALE_DYNAMIC", windowMs = DYNAMIC_WINDOW_MS,
                freshness = freshnessOf(age, DYNAMIC_WINDOW_MS)
            )
        }

        // 4) 固定知识标记（百科/教程/概念解释 → BACKGROUND_ONLY，除非带新事词）
        val text = "${item.title} ${item.summary.take(160)}".lowercase()
        if (staticTitleMarkers.any(text::contains) && noveltyMarkers.none(text::contains)) {
            return Decision(Verdict.BACKGROUND_ONLY, "STATIC_KNOWLEDGE")
        }

        return Decision(
            Verdict.ACCEPT, "OK",
            windowMs = if (isPaper) PAPER_WINDOW_MS else DYNAMIC_WINDOW_MS,
            freshness = if (age == null) 0.3 else freshnessOf(age, if (isPaper) PAPER_WINDOW_MS else DYNAMIC_WINDOW_MS)
        )
    }

    /** 时效评分：窗内线性衰减 1→0；未知日期 0.3。 */
    private fun freshnessOf(ageMs: Long, windowMs: Long): Double =
        (1.0 - ageMs.toDouble() / windowMs).coerceIn(0.0, 1.0)
}