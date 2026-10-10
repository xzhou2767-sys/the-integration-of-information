package com.trae.info.server.decision

import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.SourcePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 准入策略（AdmissionPolicy.decide）测试——覆盖整改方案 §2.1 的场景 1–10。
 *
 * 建立背景：`decision/` 包此前零测试覆盖，而 T1.1 的缺陷（质量分分支永不命中）
 * 与 T1.2 的分歧（两套准入对 arXiv 无日期判定相反）都落在本包内。
 */
class AdmissionPolicyTest {

    private val now = 1_800_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    private fun item(
        source: String = SourcePlatform.ARXIV.id,
        title: String = "A new finding",
        url: String = "https://example.com/x",
        ageDays: Long? = 1,
        summary: String = "body text"
    ) = ContentItem(
        id = "$source-$title-${ageDays ?: "unknown"}",
        categoryId = "ai-news",
        source = source,
        title = title,
        summary = summary,
        url = url,
        publishedAt = if (ageDays == null) 0L else now - ageDays * dayMs
    )

    // ---- 1. 来源硬限制 ----

    @Test
    fun `wikipedia is rejected by source rule`() {
        val d = AdmissionPolicy.decide(item(source = SourcePlatform.WIKIPEDIA.id), now)
        assertEquals(Verdict.REJECT, d.verdict)
        assertEquals("WIKI_SOURCE", d.reasonCode)
    }

    // ---- 2. 畸形条目 ----

    @Test
    fun `blank title or url is malformed`() {
        val blankTitle = AdmissionPolicy.decide(item(title = "   "), now)
        assertEquals(Verdict.REJECT, blankTitle.verdict)
        assertEquals("MALFORMED", blankTitle.reasonCode)

        val blankUrl = AdmissionPolicy.decide(item(url = ""), now)
        assertEquals(Verdict.REJECT, blankUrl.verdict)
        assertEquals("MALFORMED", blankUrl.reasonCode)
    }

    // ---- 3. 未来日期（含 1 天时钟误差边界） ----

    @Test
    fun `future date beyond one day tolerance is rejected`() {
        val future = item().copy(publishedAt = now + 2 * dayMs)
        val d = AdmissionPolicy.decide(future, now)
        assertEquals(Verdict.REJECT, d.verdict)
        assertEquals("FUTURE_DATE", d.reasonCode)
    }

    @Test
    fun `exactly one day in the future is still accepted`() {
        val edge = item(ageDays = -1)
        assertEquals(Verdict.ACCEPT, AdmissionPolicy.decide(edge, now).verdict)
    }

    // ---- 4/5. 按内容类型唯一选择时间窗 ----

    @Test
    fun `paper uses the 90 day window not the 45 day one`() {
        // 89 天论文：在 90 天窗内 → 放行（此前会被 45 天通用窗误删）
        assertEquals(Verdict.ACCEPT, AdmissionPolicy.decide(item(ageDays = 89), now).verdict)

        // 91 天论文：超 90 天窗 → BACKGROUND_ONLY
        val stale = AdmissionPolicy.decide(item(ageDays = 91), now)
        assertEquals(Verdict.BACKGROUND_ONLY, stale.verdict)
        assertEquals("STALE_PAPER", stale.reasonCode)
        assertEquals(AdmissionPolicy.PAPER_WINDOW_MS, stale.windowMs)
    }

    @Test
    fun `dynamic content uses the 45 day window`() {
        val dynamic = SourcePlatform.REDDIT.id
        assertEquals(Verdict.ACCEPT, AdmissionPolicy.decide(item(source = dynamic, ageDays = 44), now).verdict)

        val stale = AdmissionPolicy.decide(item(source = dynamic, ageDays = 46), now)
        assertEquals(Verdict.BACKGROUND_ONLY, stale.verdict)
        assertEquals("STALE_DYNAMIC", stale.reasonCode)
        assertEquals(AdmissionPolicy.DYNAMIC_WINDOW_MS, stale.windowMs)
    }

    // ---- 6/7. 日期未知 ----

    @Test
    fun `paper with unknown date needs evidence and is not faked as fresh`() {
        val d = AdmissionPolicy.decide(item(ageDays = null), now)
        assertEquals(Verdict.NEEDS_EVIDENCE, d.verdict)
        assertEquals("UNKNOWN_DATE_PAPER", d.reasonCode)
    }

    @Test
    fun `dynamic content with unknown date is accepted with damped freshness`() {
        val d = AdmissionPolicy.decide(item(source = SourcePlatform.REDDIT.id, ageDays = null), now)
        assertEquals(Verdict.ACCEPT, d.verdict)
        // 未知日期不得伪装成新内容：freshness 固定 0.3，而非接近 1.0
        assertEquals(0.3, d.freshness, 1e-9)
    }

    // ---- 8. 固定知识标记 ----

    @Test
    fun `static explainer without novelty is background only`() {
        val d = AdmissionPolicy.decide(item(title = "什么是量子纠缠"), now)
        assertEquals(Verdict.BACKGROUND_ONLY, d.verdict)
        assertEquals("STATIC_KNOWLEDGE", d.reasonCode)
    }

    @Test
    fun `static explainer with novelty marker is accepted`() {
        val d = AdmissionPolicy.decide(item(title = "什么是量子纠缠：新版研究发布"), now)
        assertEquals(Verdict.ACCEPT, d.verdict)
    }

    // ---- 9. 时效线性衰减 ----

    @Test
    fun `freshness decays linearly inside the window`() {
        val fresh = AdmissionPolicy.decide(item(ageDays = 0), now)
        assertTrue(fresh.freshness > 0.99, "age 0 should be ~1.0, got ${fresh.freshness}")

        val mid = AdmissionPolicy.decide(item(ageDays = 45), now)
        assertTrue(mid.freshness in 0.49..0.51, "age 45 of 90 should be ~0.5, got ${mid.freshness}")
    }

    // ---- 10. 策略版本可追踪 ----

    @Test
    fun `decision carries the policy version`() {
        assertEquals("adm-v2.1", AdmissionPolicy.POLICY_VERSION)
        assertEquals(AdmissionPolicy.POLICY_VERSION, AdmissionPolicy.decide(item(), now).policyVersion)
    }
}
