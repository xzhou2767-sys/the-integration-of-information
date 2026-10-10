package com.trae.info.server.service

import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.SourcePlatform
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Shared admission and deduplication policy for every dynamic-feed entry point. */
object ContentPolicy {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val PAPER_MAX_AGE_MS = 90L * DAY_MS
    private const val DYNAMIC_MAX_AGE_MS = 45L * DAY_MS

    private val staticTitleMarkers = listOf(
        "什么是", "是什么意思", "基础知识", "入门教程", "百科", "词条", "定义",
        "what is ", "beginner's guide", "beginners guide", "encyclopedia", "definition of "
    )
    private val noveltyMarkers = listOf(
        "发布", "更新", "新版", "突破", "研究发现", "宣布", "实测", "评测", "报告",
        "released", "launch", "update", "announced", "study finds", "benchmark"
    )

    fun admit(item: ContentItem, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (item.source == SourcePlatform.WIKIPEDIA.id) return false
        // 未来日期（超 1 天时钟误差）暂缓：日期异常不进入动态流
        if (item.publishedAt > nowMs + DAY_MS) return false
        val age = if (item.publishedAt > 0) nowMs - item.publishedAt else null
        // 时间窗按内容类型唯一选择（P0 修复：arXiv 论文走 90 天窗并跳过 45 天通用窗，
        // 此前 45-90 天论文会被通用窗口误删）；论文日期未知同样拒绝（不伪装新内容）
        if (item.source == SourcePlatform.ARXIV.id) {
            if (age == null || age > PAPER_MAX_AGE_MS) return false
        } else if (age != null && age > DYNAMIC_MAX_AGE_MS) {
            return false
        }

        val text = "${item.title} ${item.summary.take(160)}".lowercase(Locale.ROOT)
        if (staticTitleMarkers.any(text::contains) && noveltyMarkers.none(text::contains)) return false
        return item.title.isNotBlank() && item.url.isNotBlank()
    }

    fun filterAndDeduplicate(items: List<ContentItem>, nowMs: Long = System.currentTimeMillis()): List<ContentItem> {
        val seen = HashSet<String>()
        return items.asSequence().filter { admit(it, nowMs) }.filter { seen.add(identityOf(it)) }.toList()
    }

    fun identityOf(item: ContentItem): String {
        val externalId = if (item.source == SourcePlatform.ARXIV.id) item.id.replace(Regex("v\\d+$"), "") else ""
        if (externalId.isNotBlank()) return "${item.source}:$externalId"
        val canonical = canonicalUrl(item.url)
        if (canonical.isNotBlank()) return canonical
        val material = "${item.title.trim().lowercase(Locale.ROOT)}\n${item.summary.trim().take(240)}"
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
    }

    private fun canonicalUrl(raw: String): String = runCatching {
        val uri = URI(raw.trim())
        val keptQuery = uri.rawQuery?.split('&')
            ?.filterNot { part ->
                val key = part.substringBefore('=').lowercase(Locale.ROOT)
                key.startsWith("utm_") || key in setOf("spm", "from", "ref", "source", "fbclid", "gclid")
            }
            ?.takeIf { it.isNotEmpty() }?.joinToString("&")
        URI(uri.scheme?.lowercase(), uri.authority?.lowercase(), uri.path?.trimEnd('/'), keptQuery, null).toString()
    }.getOrDefault(raw.substringBefore('#').trimEnd('/'))
}
