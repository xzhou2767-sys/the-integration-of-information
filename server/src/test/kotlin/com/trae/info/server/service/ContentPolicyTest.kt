package com.trae.info.server.service

import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.SourcePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentPolicyTest {
    private val now = 1_800_000_000_000L

    private fun item(source: String, title: String, url: String, ageDays: Long = 1) = ContentItem(
        id = "$source-$title",
        categoryId = "ai",
        source = source,
        title = title,
        url = url,
        publishedAt = now - ageDays * 24 * 60 * 60 * 1000
    )

    @Test fun rejectsWikipediaAndOldPapers() {
        assertFalse(ContentPolicy.admit(item(SourcePlatform.WIKIPEDIA.id, "AI", "https://w.example/AI"), now))
        assertFalse(ContentPolicy.admit(item(SourcePlatform.ARXIV.id, "Old paper", "https://arxiv.org/abs/1", 91), now))
        assertTrue(ContentPolicy.admit(item(SourcePlatform.ARXIV.id, "New paper", "https://arxiv.org/abs/2", 30), now))
    }

    @Test fun staticExplainersNeedANoveltySignal() {
        assertFalse(ContentPolicy.admit(item("news", "什么是人工智能", "https://example.com/a"), now))
        assertTrue(ContentPolicy.admit(item("news", "新版人工智能基准评测发布", "https://example.com/b"), now))
    }

    @Test fun removesTrackingParametersBeforeDeduplication() {
        val a = item("news", "one", "https://Example.com/a?utm_source=x&id=7")
        val b = item("news", "two", "https://example.com/a?id=7&utm_medium=y")
        assertEquals(1, ContentPolicy.filterAndDeduplicate(listOf(a, b), now).size)
    }
}
