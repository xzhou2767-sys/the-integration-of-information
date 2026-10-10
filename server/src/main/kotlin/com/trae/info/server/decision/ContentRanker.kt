package com.trae.info.server.decision

import com.trae.info.core.model.ContentItem
import java.util.Locale

/**
 * 偏好相关性排序 + 评分解释（方案 §5.4 / §4.4）：
 * 在去重后执行，输出 [ScoreExplanation]，顺序 = 偏好相关度 → 质量分 → 时效 → 时间。
 * 与 PreferenceEngine.rank 的差异：评分因子可解释（命中词可见）、freshness 来自准入阶段、
 * 权重集中在此处单一事实源（初始 relevance×2 + quality×0.5 + freshness×1）。
 */
object ContentRanker {

    /** 排序结果：条目 + 评分解释。 */
    data class Ranked(val item: ContentItem, val explanation: ScoreExplanation)

    /**
     * 排序：正向词加分、负向词否决（硬排除），输出评分解释。
     * @param positive 正向偏好词 ∪ 类目词（与旧链路口径一致）
     * @param negative 负向偏好词（命中则移出结果，硬排除语义）
     * @param freshnessById 准入阶段计算的 freshness（缺省 0.3 中性）
     */
    fun rankWithExplanation(
        items: List<ContentItem>,
        positive: List<String>,
        negative: List<String>,
        freshnessById: Map<String, Double> = emptyMap()
    ): List<Ranked> {
        val posDistinct = positive.distinct()
        return items.asSequence()
            .filter { item -> negative.none { containsToken(item, it) } }
            .map { item ->
                val matched = posDistinct.filter { containsToken(item, it) }
                val relevance = (matched.size * 1.0).coerceAtMost(5.0)
                val quality = qualityScore(item)
                val freshness = freshnessById[item.id] ?: 0.3
                val total = relevance * 2.0 + quality * 0.5 + freshness * 1.0
                Ranked(item, ScoreExplanation(
                    itemId = item.id, relevance = relevance, quality = quality,
                    freshness = freshness, total = total, matchedKeywords = matched
                ))
            }
            .sortedWith(
                compareByDescending<Ranked> { it.explanation.total }
                    .thenByDescending { it.item.publishedAt }
            )
            .toList()
    }

    /** 平台内质量分（复用旧链路口径）：黑箱平台 0.5 中性。 */
    fun qualityScore(item: ContentItem): Double {
        val s = item.summary
        return when {
            // HN hot 归一化标记
            item.source == "hackernews" && s.contains("hot=") ->
                s.substringAfter("hot=").substringBefore(' ').toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.5
            // SE Wilson 区间下界
            item.source == "stackoverflow" && s.contains("Wilson：") ->
                s.substringAfter("Wilson：").substringBefore(' ').toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.5
            // 豆瓣评分 ÷10
            item.source == "douban" && s.contains("评分：") ->
                (s.substringAfter("评分：").substringBefore(' ').toDoubleOrNull() ?: 5.0) / 10.0
            else -> 0.5
        }
    }

    /** 关键词命中（中英文混合：包含匹配 + 词边界，与 PreferenceEngine.containsToken 口径对齐）。 */
    private fun containsToken(item: ContentItem, keyword: String): Boolean {
        if (keyword.isBlank()) return false
        val hay = "${item.title} ${item.summary}".lowercase(Locale.ROOT)
        val needle = keyword.lowercase(Locale.ROOT)
        if (hay.contains(needle)) return true
        // 英文词边界（避免 cat 命中 category）
        return needle.first().isLetter() &&
            Regex("\\b${Regex.escape(needle)}\\b").containsMatchIn(hay)
    }
}