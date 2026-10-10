package com.trae.info.server.decision

import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.SourcePlatform
import com.trae.info.server.service.PreferenceEngine

/**
 * 查询规划（方案 §5.1）：把偏好真正下推到检索词。
 * 用户明确实体优先进入 terms；硬排除词进入 excludeTerms（来源支持时下推）。
 * 输出 [QuerySpec]，其存在即"偏好下推生效"的可审计证明（terms 来源可追溯）。
 */
object QueryPlanner {

    /** 每来源每轮保留的互补查询上限（§5.1：少量互补查询）。 */
    const val MAX_TERMS_PER_QUERY = 4

    /**
     * 为「类目 × 平台」生成检索规格。
     * @param profileVersion 偏好版本（偏好变化触发重新解析时递增）
     * @param sinceMs 时间窗下界：论文类目 90 天，其余 45 天
     */
    fun plan(
        category: InfoCategory,
        platform: SourcePlatform,
        pref: PreferenceEngine.ParsedPref,
        profileVersion: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): QuerySpec {
        val isPaperCategory = category.sources.contains(SourcePlatform.ARXIV)
        val window = if (isPaperCategory) AdmissionPolicy.PAPER_WINDOW_MS else AdmissionPolicy.DYNAMIC_WINDOW_MS
        // 用户实体（来自偏好，最高优先）∪ 类目词；用户实体排前
        val terms = (pref.positiveKeywords.take(2) + category.keywords)
            .distinct().take(MAX_TERMS_PER_QUERY)
        return QuerySpec(
            sourceId = platform.id,
            categoryId = category.id,
            terms = terms,
            excludeTerms = pref.negativeKeywords,
            sinceMs = nowMs - window,
            profileVersion = profileVersion
        )
    }
}