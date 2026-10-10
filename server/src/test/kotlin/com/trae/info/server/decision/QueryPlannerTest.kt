package com.trae.info.server.decision

import com.trae.info.core.model.Domain
import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.SourcePlatform
import com.trae.info.server.service.PreferenceEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 查询规划测试——覆盖整改方案 §2.1 的场景 28–30。
 *
 * QuerySpec 的存在即"偏好下推生效"的可审计证明：terms 来源可追溯。
 */
class QueryPlannerTest {

    private val now = 1_800_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    private fun category(
        id: String = "ai-news",
        keywords: List<String> = listOf("大模型发布", "AI 新闻", "第三词", "第四词", "第五词"),
        sources: List<SourcePlatform> = listOf(SourcePlatform.HACKERNEWS, SourcePlatform.ARXIV)
    ) = InfoCategory(
        id = id, name = "test", domain = Domain.PRACTICAL, group = "test",
        description = "", keywords = keywords, sources = sources
    )

    private fun pref(
        positive: List<String> = listOf("用户实体一", "用户实体二", "多余词"),
        negative: List<String> = listOf("屏蔽词")
    ) = PreferenceEngine.ParsedPref(positive, negative, "default")

    // ---- 28. 检索词组成与上限 ----

    @Test
    fun `user entities take precedence over category keywords`() {
        val spec = QueryPlanner.plan(category(), SourcePlatform.HACKERNEWS, pref(), nowMs = now)
        assertTrue(spec.terms.take(2) == listOf("用户实体一", "用户实体二"),
            "user entities must come first, got ${spec.terms}")
    }

    @Test
    fun `term count is capped at four`() {
        val spec = QueryPlanner.plan(category(), SourcePlatform.HACKERNEWS, pref(), nowMs = now)
        assertEquals(QueryPlanner.MAX_TERMS_PER_QUERY, spec.terms.size)
        assertEquals(4, QueryPlanner.MAX_TERMS_PER_QUERY)
    }

    @Test
    fun `terms are de-duplicated`() {
        val dup = pref(positive = listOf("大模型发布"))
        val spec = QueryPlanner.plan(category(), SourcePlatform.HACKERNEWS, dup, nowMs = now)
        assertEquals(spec.terms.size, spec.terms.distinct().size)
    }

    @Test
    fun `empty preference falls back to category keywords`() {
        val empty = PreferenceEngine.ParsedPref(emptyList(), emptyList(), "default")
        val spec = QueryPlanner.plan(category(), SourcePlatform.HACKERNEWS, empty, nowMs = now)
        assertTrue(spec.terms.isNotEmpty())
        assertTrue(spec.terms.all { it in category().keywords })
    }

    // ---- 29. 时间窗按内容类型唯一选择 ----

    @Test
    fun `paper category uses the 90 day window`() {
        val paperCat = category(id = "ai-algorithm", sources = listOf(SourcePlatform.ARXIV))
        val spec = QueryPlanner.plan(paperCat, SourcePlatform.ARXIV, pref(), nowMs = now)
        assertEquals(now - AdmissionPolicy.PAPER_WINDOW_MS, spec.sinceMs)
        assertEquals(90L * dayMs, AdmissionPolicy.PAPER_WINDOW_MS)
    }

    @Test
    fun `non paper category uses the 45 day window`() {
        val dynamicCat = category(id = "ne-digital-news", sources = listOf(SourcePlatform.HACKERNEWS))
        val spec = QueryPlanner.plan(dynamicCat, SourcePlatform.HACKERNEWS, pref(), nowMs = now)
        assertEquals(now - AdmissionPolicy.DYNAMIC_WINDOW_MS, spec.sinceMs)
        assertEquals(45L * dayMs, AdmissionPolicy.DYNAMIC_WINDOW_MS)
    }

    @Test
    fun `category is considered a paper category when arxiv is among its sources`() {
        // 判定依据是 category.sources 是否含 ARXIV，而非平台本身
        val mixed = category(id = "edu-cs", sources = listOf(SourcePlatform.HACKERNEWS, SourcePlatform.ARXIV))
        val spec = QueryPlanner.plan(mixed, SourcePlatform.HACKERNEWS, pref(), nowMs = now)
        assertEquals(now - AdmissionPolicy.PAPER_WINDOW_MS, spec.sinceMs)
    }

    // ---- 30. 排除词 ----

    @Test
    fun `negative keywords become exclude terms`() {
        val spec = QueryPlanner.plan(category(), SourcePlatform.HACKERNEWS, pref(), nowMs = now)
        assertEquals(listOf("屏蔽词"), spec.excludeTerms)
    }

    // ---- 溯源字段 ----

    @Test
    fun `spec carries source category and profile version for auditability`() {
        val spec = QueryPlanner.plan(
            category(), SourcePlatform.ARXIV, pref(negative = emptyList()),
            profileVersion = "v7", nowMs = now
        )
        assertEquals(SourcePlatform.ARXIV.id, spec.sourceId)
        assertEquals("ai-news", spec.categoryId)
        assertEquals("v7", spec.profileVersion)
    }

    @Test
    fun `default languages cover chinese and english`() {
        val spec = QueryPlanner.plan(category(), SourcePlatform.HACKERNEWS, pref(), nowMs = now)
        assertEquals(listOf("zh", "en"), spec.languages)
    }
}
