package com.trae.info.server.decision

import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.SourcePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 偏好排序与评分解释测试——覆盖整改方案 §2.1 的场景 17–22。
 *
 * 已知缺陷（T1.1）：ContentRanker.qualityScoreOf 判断 `item.source == "stackoverflow"`，
 * 而 StackExchangeAdapter 写入的是 `platform.id`（= "stackexchange"），因此 Wilson
 * 分支永不命中。`stackExchangeWilsonIsNotNeutral` 用例断言**正确行为**，在缺陷修复前
 * 预期失败——这是刻意的：它把 §二 阶段 1 的 T1.1 变成可执行的失败信号。
 */
class ContentRankerTest {

    private fun item(
        id: String = "i1",
        source: String = SourcePlatform.HACKERNEWS.id,
        title: String = "Title",
        summary: String = "",
        tags: List<String> = emptyList(),
        publishedAt: Long = 100L
    ) = ContentItem(
        id = id, categoryId = "ai-news", source = source, title = title,
        summary = summary, url = "https://example.com/$id", tags = tags,
        publishedAt = publishedAt
    )

    // ---- 17. 负向词硬排除 ----

    @Test
    fun `negative keywords hard exclude the item`() {
        val keep = item(id = "keep", title = "rust async runtime")
        val drop = item(id = "drop", title = "rust async runtime clickbait")
        val ranked = ContentRanker.rankWithExplanation(
            listOf(keep, drop), positive = listOf("rust"), negative = listOf("clickbait")
        )
        assertEquals(listOf("keep"), ranked.map { it.item.id })
    }

    @Test
    fun `negative keyword match is case insensitive`() {
        val ranked = ContentRanker.rankWithExplanation(
            listOf(item(title = "SPAM content")), positive = emptyList(), negative = listOf("spam")
        )
        assertTrue(ranked.isEmpty())
    }

    // ---- 18. relevance 上限 ----

    @Test
    fun `relevance is capped at five`() {
        val target = item(
            title = "alpha beta gamma delta epsilon zeta eta",
            summary = "alpha beta gamma delta epsilon zeta eta"
        )
        val ranked = ContentRanker.rankWithExplanation(
            listOf(target),
            positive = listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta"),
            negative = emptyList()
        )
        // 7 个词全部命中，但 relevance 上限 5.0
        assertEquals(5.0, ranked.single().explanation.relevance, 1e-9)
    }

    // ---- 19. 质量分分支 ----

    @Test
    fun `hackerNewsHotScoreIsNormalized`() {
    @Test
    fun `hackerNewsHotScoreIsReadFromTags`() {
        // HackerNewsAdapter 把 hot= 写入 tags 末位（Adapters.kt:838），
        // PreferenceEngine.qualityScoreOf 亦从 tags 读（PreferenceEngine.kt:312）。
        // 但 ContentRanker.qualityScore 从 item.summary 读（ContentRanker.kt:52）——
        // 因此 HN 分支在 V2 链路上永不命中（T1.1 同类缺陷，第二处）。
        val hot = item(tags = listOf("Hacker News", "热点", "hot=300.0"))
        val cold = item(tags = listOf("Hacker News", "热点", "hot=1.0"))
        val hotScore = ContentRanker.qualityScore(hot)
        val coldScore = ContentRanker.qualityScore(cold)
        assertTrue(hotScore > coldScore, "hot=$hotScore should exceed cold=$coldScore")
        assertTrue(hotScore <= 1.0)
    }

    @Test
    fun `hackerNewsHotScoreIsIgnoredWhenOnlyInSummary`() {
        // 现状固化：把 hot= 放在 summary 里也不会命中（读取位置只在 summary，
        // 但适配器从不往 summary 写 hot=）。修复后本用例应改为断言两处读取一致。
        val inSummary = item(summary = "hot=300.0")
        assertEquals(0.5, ContentRanker.qualityScore(inSummary), 1e-9,
            "if this fails, the read location changed — update T1.1 与其回归用例")
    }
    fun `stackExchangeWilsonIsNotNeutral`() {
        // T1.1 回归护栏：source 必须取枚举 id（"stackexchange"），
        // 而非适配器从不产出的 "stackoverflow"。
        val se = item(source = SourcePlatform.STACKEXCHANGE.id, summary = "投票：12↑3↓｜Wilson：0.72")
        assertEquals(0.72, ContentRanker.qualityScore(se), 1e-9)
    }

    @Test
    fun `blackboxPlatformsReturnNeutralQuality`() {
        assertEquals(0.5, ContentRanker.qualityScore(item(source = SourcePlatform.ZHIHU.id)), 1e-9)
    }

    // ---- 20. 总分公式 ----

    @Test
    fun `total follows the documented weighting`() {
        // 用黑箱平台（质量分恒 0.5）构造确定值，避免依赖 HN/SE 读取位置的缺陷。
        val zhihu = item(source = SourcePlatform.ZHIHU.id, title = "Title")
        val ranked = ContentRanker.rankWithExplanation(
            listOf(zhihu), positive = listOf("Title"), negative = emptyList(),
            freshnessById = mapOf("i1" to 0.8)
        )
        val e = ranked.single().explanation
        val expected = e.relevance * 2.0 + e.quality * 0.5 + e.freshness * 1.0
        assertEquals(expected, e.total, 1e-9)
        // 构造值核对：relevance=1（命中 "Title"）、quality=0.5（黑箱中性）、freshness=0.8
        assertEquals(2.0 + 0.25 + 0.8, e.total, 1e-9)
    }

    @Test
    fun `freshness defaults to neutral 03 when absent`() {
        val ranked = ContentRanker.rankWithExplanation(
            listOf(item(title = "something")), positive = emptyList(), negative = emptyList()
        )
        assertEquals(0.3, ranked.single().explanation.freshness, 1e-9)
    }

    // ---- 21. 同分按时间降序 ----

    @Test
    fun `equal totals are ordered by publishedAt descending`() {
        val older = item(id = "old", title = "same headline", publishedAt = 100L)
        val newer = item(id = "new", title = "same headline", publishedAt = 900L)
        val ranked = ContentRanker.rankWithExplanation(
            listOf(older, newer), positive = emptyList(), negative = emptyList()
        )
        assertEquals(listOf("new", "old"), ranked.map { it.item.id })
    }

    // ---- 22. 英文词边界 ----

    @Test
    fun `english word boundary avoids substring false positives`() {
        val item = item(title = "category theory explained")
        val ranked = ContentRanker.rankWithExplanation(
            listOf(item), positive = listOf("cat"), negative = emptyList()
        )
        assertEquals(0.0, ranked.single().explanation.relevance, 1e-9)
        assertTrue(ranked.single().explanation.matchedKeywords.isEmpty())
    }

    @Test
    fun `matched keywords are reported for explainability`() {
        val ranked = ContentRanker.rankWithExplanation(
            listOf(item(title = "rust and kotlin")),
            positive = listOf("rust", "kotlin", "swift"), negative = emptyList()
        )
        assertEquals(listOf("rust", "kotlin"), ranked.single().explanation.matchedKeywords)
    }

    @Test
    fun `empty positive list yields zero relevance not a crash`() {
        val ranked = ContentRanker.rankWithExplanation(
            listOf(item(title = "anything")), positive = emptyList(), negative = emptyList()
        )
        assertEquals(0.0, ranked.single().explanation.relevance, 1e-9)
    }

    @Test
    fun `blank keywords never match`() {
        val ranked = ContentRanker.rankWithExplanation(
            listOf(item(title = "anything")), positive = listOf("  "), negative = emptyList()
        )
        assertFalse(ranked.single().explanation.matchedKeywords.contains("  "))
    }
}
