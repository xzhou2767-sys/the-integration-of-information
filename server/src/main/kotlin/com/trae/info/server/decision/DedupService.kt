package com.trae.info.server.decision

import com.trae.info.core.model.ContentItem
import java.security.MessageDigest
import java.util.Locale

/**
 * 去重与事件分组（方案 §5.4）：
 * - 精确去重：来源 ID / 规范 URL / 正文指纹三级身份；
 * - 近似重复：轻量标题指纹（词集 simhash 式哈希）生成候选，首期不引入向量服务；
 * - 同事件分组：标题词集 Jaccard ≥ 阈值的条目归组（保留展示而非删除）。
 */
object DedupService {

    /** 规范 URL：仅删已知追踪参数（utm_* / spm / fbclid / gclid），
     *  按 §5.4 不统一删除可能影响资源身份的 source/ref/from（与 ContentPolicy 旧口径差异已在文档记录）。 */
    fun canonicalUrl(raw: String): String = runCatching {
        val u = java.net.URI(raw.trim())
        val kept = u.rawQuery?.split('&')
            ?.filterNot { it.substringBefore('=').lowercase(Locale.ROOT).startsWith("utm_")
                    || it.substringBefore('=').lowercase(Locale.ROOT) in setOf("spm", "fbclid", "gclid") }
            ?.joinToString("&")
        java.net.URI(u.scheme?.lowercase(), u.authority?.lowercase(), u.path?.trimEnd('/'), kept, null).toString()
    }.getOrDefault(raw.substringBefore('#').trimEnd('/'))

    /** 条目唯一身份：arXiv 去版本号 → 规范 URL → 标题+摘要指纹。 */
    fun identityOf(item: ContentItem): String {
        if (item.source == "arxiv") {
            val bare = item.id.replace(Regex("v\\d+$"), "")
            if (bare.isNotBlank()) return "${item.source}:$bare"
        }
        val canon = canonicalUrl(item.url)
        if (canon.isNotBlank()) return canon
        return fingerprint("${item.title.trim().lowercase()}\n${item.summary.trim().take(240)}")
    }

    /** 轻量文本指纹：规范化词序列 SHA-256 前 96 bit。 */
    fun fingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.lowercase().replace(Regex("\\s+"), " ").trim().toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }

    /** 标题词集（近似重复与事件分组的输入）。 */
    private fun titleTokens(item: ContentItem): Set<String> =
        item.title.lowercase().split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.length > 1 }.toSet()

    /** 标题词集 Jaccard 相似度。 */
    fun titleSimilarity(a: ContentItem, b: ContentItem): Double {
        val ta = titleTokens(a); val tb = titleTokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size
    }

    /**
     * 精确去重：同身份保留 publishedAt 最新（或信息更全者）。
     * 返回去重后列表（保持输入相对顺序）。
     */
    fun dedup(items: List<ContentItem>): List<ContentItem> {
        val best = LinkedHashMap<String, ContentItem>()
        for (item in items) {
            val id = identityOf(item)
            val existing = best[id]
            if (existing == null || item.publishedAt > existing.publishedAt) best[id] = item
        }
        return best.values.toList()
    }

    /**
     * 近似重复候选 + 同事件分组（方案 §5.4）：
     * 相似度 ≥ [threshold] 的相邻条目归入同组；每组保留首条为代表（组内其余标记组关系）。
     * 首期仅输出分组结构供展示层使用，不删除内容。
     */
    fun groupEvents(items: List<ContentItem>, threshold: Double = 0.6): Map<String, List<ContentItem>> {
        val groups = LinkedHashMap<String, MutableList<ContentItem>>()
        val ungrouped = items.toMutableList()
        while (ungrouped.isNotEmpty()) {
            val head = ungrouped.removeAt(0)
            val groupId = "evt-" + fingerprint(head.title).take(8)
            val members = mutableListOf(head)
            val it = ungrouped.iterator()
            while (it.hasNext()) {
                val candidate = it.next()
                if (titleSimilarity(head, candidate) >= threshold) {
                    members.add(candidate); it.remove()
                }
            }
            groups[groupId] = members
        }
        return groups
    }
}