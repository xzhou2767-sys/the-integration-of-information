package com.trae.info.server.decision

import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.SourcePlatform
import com.trae.info.server.service.ContentPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 两套准入/去重实现的交叉一致性测试——整改方案 §2.1 的场景 16 与 T2.4。
 *
 * 背景（T1.2）：`ContentService.feed` 先跑 `ContentPolicy.filterAndDeduplicate`，
 * 再跑 `AdmissionPolicy.decide`。实测两处对**同一输入**判定的方向：
 *
 * | 输入 | ContentPolicy.admit | AdmissionPolicy.decide |
 * |---|---|---|
 * | arXiv，日期未知 | **拒绝**（admit=false） | **保留**（NEEDS_EVIDENCE） |
 * | arXiv，60 天前 | 放行 | 放行 |
 * | Wikipedia | 拒绝 | 拒绝（REJECT/WIKI_SOURCE） |
 *
 * 两处判定相反，导致文档《5.9》所述的「未知日期 → NEEDS_EVIDENCE，不伪装新内容」
 * 在 feed 路径上**不可达**：条目已被上游 ContentPolicy 拦掉。
 *
 * 本测试**固化这一差异为已知事实**，不做价值判断；待 B5/B6 裁定后，用例应改为
 * 断言二者一致（届时下方 `arxivWithUnknownDateIsCurrentlyDivergent` 会失败，
 * 提示实现已统一，需同步更新用例与文档）。
 */
class ContentPolicyCrossCheckTest {

    private val now = 1_800_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    private fun item(
        source: String,
        ageDays: Long? = 1,
        title: String = "A headline",
        url: String = "https://example.com/a",
        summary: String = "body"
    ) = ContentItem(
        id = "$source-$title-${ageDays ?: "unknown"}",
        categoryId = "ai-news", source = source, title = title, summary = summary,
        url = url, publishedAt = if (ageDays == null) 0L else now - ageDays * dayMs
    )

    // ---- 已知分歧：arXiv 日期未知 ----

    @Test
    fun `arxivWithUnknownDateIsCurrentlyDivergent`() {
        val paper = item(SourcePlatform.ARXIV.id, ageDays = null)

        val admitted = ContentPolicy.admit(paper, now)
        val verdict = AdmissionPolicy.decide(paper, now).verdict

        // 现状固化：上游拒绝，下游却准备保留。
        assertEquals(false, admitted, "ContentPolicy currently rejects unknown-date arXiv")
        assertEquals(Verdict.NEEDS_EVIDENCE, verdict, "AdmissionPolicy wants to keep it for evidence")

        // 二者不一致本身即 T1.2 的证据。
        assertTrue(
            admitted == false && verdict == Verdict.NEEDS_EVIDENCE,
            "expected divergence (T1.2); if this fails, the two paths were unified — update this test and PROJECT_SYSTEM.md 5.9"
        )
    }

    // ---- 方向一致的部分 ----

    @Test
    fun `wikipediaIsRejectedByBothPaths`() {
        val wiki = item(SourcePlatform.WIKIPEDIA.id)
        assertEquals(false, ContentPolicy.admit(wiki, now))
        assertEquals(Verdict.REJECT, AdmissionPolicy.decide(wiki, now).verdict)
    }

    @Test
    fun `staleArxivIsExcludedByBothPaths`() {
        val stale = item(SourcePlatform.ARXIV.id, ageDays = 120)
        assertEquals(false, ContentPolicy.admit(stale, now))
        assertEquals(Verdict.BACKGROUND_ONLY, AdmissionPolicy.decide(stale, now).verdict)
    }

    @Test
    fun `freshArxivIsAcceptedByBothPaths`() {
        val fresh = item(SourcePlatform.ARXIV.id, ageDays = 30)
        assertEquals(true, ContentPolicy.admit(fresh, now))
        assertEquals(Verdict.ACCEPT, AdmissionPolicy.decide(fresh, now).verdict)
    }

    @Test
    fun `staticExplainerIsExcludedByBothPaths`() {
        val explainer = item(SourcePlatform.HACKERNEWS.id, title = "什么是量子计算")
        assertEquals(false, ContentPolicy.admit(explainer, now))
        assertEquals(Verdict.BACKGROUND_ONLY, AdmissionPolicy.decide(explainer, now).verdict)
    }

    @Test
    fun `futureDateIsExcludedByBothPaths`() {
        val future = item(SourcePlatform.HACKERNEWS.id).copy(publishedAt = now + 3 * dayMs)
        assertEquals(false, ContentPolicy.admit(future, now))
        assertEquals(Verdict.REJECT, AdmissionPolicy.decide(future, now).verdict)
    }

    // ---- 身份（去重键）一致性 ----

    @Test
    fun `arxiv identity ignores version in both paths`() {
        val v1 = item(SourcePlatform.ARXIV.id).copy(id = "arxiv-2401.12345v1", url = "https://arxiv.org/abs/2401.12345v1")
        val v3 = item(SourcePlatform.ARXIV.id).copy(id = "arxiv-2401.12345v3", url = "https://arxiv.org/abs/2401.12345v3")

        assertEquals(
            ContentPolicy.identityOf(v1).replace("v1", ""),
            ContentPolicy.identityOf(v3).replace("v3", "")
        )
        assertEquals(
            DedupService.identityOf(v1).replace("v1", ""),
            DedupService.identityOf(v3).replace("v3", "")
        )
    }

    @Test
    fun `unknownDateArxivCollapsesToIdentityAcrossPaths`() {
        // 同一篇无日期论文在两条路径上必须得到同一身份，否则同一内容会被重复计数
        val paper = item(SourcePlatform.ARXIV.id, ageDays = null)
        assertEquals(ContentPolicy.identityOf(paper), DedupService.identityOf(paper))
    }

    @Test
    fun `identity set survives filterAndDeduplicate`() {
        val list = listOf(
            item(SourcePlatform.HACKERNEWS.id, url = "https://a.com/1"),
            item(SourcePlatform.HACKERNEWS.id, url = "https://a.com/1"),   // 重复
            item(SourcePlatform.HACKERNEWS.id, url = "https://b.com/2")
        )
        val filtered = ContentPolicy.filterAndDeduplicate(list, now)
        assertEquals(2, filtered.size)
        assertEquals(2, DedupService.dedup(filtered).size, "already-unique input must stay unique")
    }

    // ---- 已知口径差异：source/ref/from ----

    @Test
    fun `canonicalUrlDiffersOnSourceRefFromBetweenImplementations`() {
        val raw = "https://example.com/a?id=7&utm_source=x&source=wechat&ref=home&from=feed"

        val policyKey = ContentPolicy.identityOf(item(SourcePlatform.HACKERNEWS.id, url = raw))
        val dedupKey = DedupService.identityOf(item(SourcePlatform.HACKERNEWS.id, url = raw))

        // ContentPolicy 删除 source/ref/from；DedupService 保留 → 身份不同。
        // 这是一处**有意差异**（见 DedupService 注释）还是缺陷，需在 B5 一并裁定；
        // 现状固化，便于裁定后回归。
        assertTrue(
            policyKey.contains("id=7"),
            "ContentPolicy keeps identity params, got $policyKey"
        )
        assertTrue(!policyKey.contains("source=wechat"), "ContentPolicy strips source, got $policyKey")
        assertTrue(dedupKey.contains("source=wechat"), "DedupService keeps source, got $dedupKey")
    }
}
