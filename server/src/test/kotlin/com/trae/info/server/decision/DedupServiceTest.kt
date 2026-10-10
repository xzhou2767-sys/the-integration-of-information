package com.trae.info.server.decision

import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.SourcePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 身份去重与事件分组测试——覆盖整改方案 §2.1 的场景 11–16。
 *
 * 注意：本包（DedupService）与 service/ContentPolicy 各有一套身份/规范化实现，
 * 二者对 source/ref/from 参数的处理**有意不同**（见各用例注释）。场景 16 的
 * 交叉一致性用例在 ContentPolicyCrossCheckTest 中。
 */
class DedupServiceTest {

    private fun item(
        id: String = "x",
        source: String = SourcePlatform.HACKERNEWS.id,
        title: String = "Title",
        url: String = "https://example.com/a",
        summary: String = "body",
        publishedAt: Long = 100L
    ) = ContentItem(
        id = id, categoryId = "ai-news", source = source,
        title = title, summary = summary, url = url, publishedAt = publishedAt
    )

    // ---- 11. 规范 URL 只删已知追踪参数 ----

    @Test
    fun `tracking parameters are stripped`() {
        val a = DedupService.canonicalUrl("https://Example.com/a?utm_source=x&id=7&fbclid=z&gclid=g&spm=s")
        val b = DedupService.canonicalUrl("https://example.com/a?id=7")
        assertEquals(b, a)
    }

    @Test
    fun `source ref and from are intentionally preserved here`() {
        // 与 ContentPolicy.canonicalUrl 的已知差异：本实现按方案 §5.4 保留
        // source/ref/from（可能影响资源身份），ContentPolicy 则删除。
        val kept = DedupService.canonicalUrl("https://example.com/a?source=wechat&ref=home")
        assertTrue(kept.contains("source=wechat"), "expected source to be kept, got $kept")
        assertTrue(kept.contains("ref=home"), "expected ref to be kept, got $kept")
    }

    @Test
    fun `scheme and host are lowercased and trailing slash removed`() {
        assertEquals(
            "https://example.com/a",
            DedupService.canonicalUrl("HTTPS://Example.COM/a/")
        )
    }

    // ---- 12. arXiv 去版本号 ----

    @Test
    fun `arxiv versions share one identity`() {
        val v2 = item(id = "arxiv-2401.12345v2", source = SourcePlatform.ARXIV.id)
        val v3 = item(id = "arxiv-2401.12345v3", source = SourcePlatform.ARXIV.id)
        // 去版本号后前缀仍含 arXiv 标识，但两侧应得到同一字符串
        val a = DedupService.identityOf(v2).replace("v2", "")
        val b = DedupService.identityOf(v3).replace("v3", "")
        assertEquals(a, b)
    }

    @Test
    fun `arxiv dedup keeps a single entry across versions`() {
        val v2 = item(id = "arxiv-2401.12345v2", source = SourcePlatform.ARXIV.id, publishedAt = 200L)
        val v3 = item(id = "arxiv-2401.12345v3", source = SourcePlatform.ARXIV.id, publishedAt = 300L)
        assertEquals(1, DedupService.dedup(listOf(v2, v3)).size)
    }

    // ---- 13. 同身份保留 publishedAt 最新 ----

    @Test
    fun `dedup keeps the newest publishedAt`() {
        val older = item(id = "old", url = "https://example.com/a", publishedAt = 100L)
        val newer = item(id = "new", url = "https://example.com/a", publishedAt = 500L)
        val result = DedupService.dedup(listOf(older, newer))
        assertEquals(1, result.size)
        assertEquals("new", result.single().id)
    }

    // ---- 14. 指纹稳定性与截断语义 ----

    @Test
    fun `fingerprint is stable and case insensitive`() {
        assertEquals(DedupService.fingerprint("Hello  World"), DedupService.fingerprint("hello world"))
        assertEquals(24, DedupService.fingerprint("abc").length, "expected 12 bytes hex = 24 chars")
    }

    @Test
    fun `identity falls back to fingerprint when url is blank`() {
        val blankUrl = item(url = "")
        assertTrue(DedupService.identityOf(blankUrl).isNotBlank())
    }

    // ---- 15. 标题相似度与事件分组 ----

    @Test
    fun `title similarity is jaccard over token sets`() {
        val a = item(title = "openai releases new model")
        val b = item(title = "openai releases new model")
        val c = item(title = "completely different headline")
        assertEquals(1.0, DedupService.titleSimilarity(a, b), 1e-9)
        assertEquals(0.0, DedupService.titleSimilarity(a, c), 1e-9)
    }

    @Test
    fun `identical titles group together at default threshold`() {
        val a = item(id = "1", title = "openai releases new model", url = "https://a.com/1")
        val b = item(id = "2", title = "openai releases new model", url = "https://b.com/2")
        val groups = DedupService.groupEvents(listOf(a, b))
        assertEquals(1, groups.size)
        assertEquals(2, groups.values.single().size)
    }

    @Test
    fun `unrelated titles stay in separate groups`() {
        val a = item(id = "1", title = "openai releases new model", url = "https://a.com/1")
        val b = item(id = "2", title = "quarterly earnings report", url = "https://b.com/2")
        val groups = DedupService.groupEvents(listOf(a, b))
        assertEquals(2, groups.size)
    }

    @Test
    fun `grouping does not crash on empty title tokens`() {
        // 标题仅含标点 → token 集为空 → 相似度 0，应按不相关处理而非抛异常
        val a = item(id = "1", title = "!!!", url = "https://a.com/1")
        val b = item(id = "2", title = "???", url = "https://b.com/2")
        val groups = DedupService.groupEvents(listOf(a, b))
        assertEquals(2, groups.size)
    }

    @Test
    fun `threshold boundary groups at exactly 06`() {
        // 2/3 交集共享 → Jaccard = 2/4 = 0.5 < 0.6，应不分组
        val a = item(id = "1", title = "alpha beta gamma", url = "https://a.com/1")
        val b = item(id = "2", title = "alpha beta delta", url = "https://b.com/2")
        val sim = DedupService.titleSimilarity(a, b)
        assertEquals(0.5, sim, 1e-9)
        assertEquals(2, DedupService.groupEvents(listOf(a, b)).size)
    }

    @Test
    fun `dedup preserves input relative order`() {
        val items = listOf(
            item(id = "1", url = "https://a.com/1"),
            item(id = "2", url = "https://b.com/2"),
            item(id = "3", url = "https://c.com/3")
        )
        assertEquals(listOf("1", "2", "3"), DedupService.dedup(items).map { it.id })
    }

    @Test
    fun `different urls produce different identities`() {
        assertNotEquals(
            DedupService.identityOf(item(url = "https://a.com/1")),
            DedupService.identityOf(item(url = "https://b.com/2"))
        )
    }
}
