package com.trae.info.server.service

import com.trae.info.server.util.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/** summaries.json 持久化条目（顶层类以便 kotlinx 生成可访问的序列化器）。 */
@Serializable
internal data class SummaryEntry(val summary: String, val at: Long)

/** summaries.json 顶层包装（用具体类避免 Map 重写序列化器推断问题）。 */
@Serializable
internal data class SummarySnapshot(val entries: Map<String, SummaryEntry> = emptyMap())

/**
 * AI 一句话总结跨轮持久缓存。
 *
 * 同一「总结级别 + 标题 + 摘要」的内容不重复调用 AI：
 * - key = mode|sha1(title + "\n" + excerpt)；
 * - LRU 上限 [MAX_ENTRIES]，TTL [TTL_MS]（30 天）；
 * - 持久化到 data/summaries.json，原子写；读坏文件备份后回退空态。
 */
class SummaryStore(dataDir: File) {

    private val log = LoggerFactory.getLogger(SummaryStore::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(dataDir, "summaries.json")

    private val entries = object : LinkedHashMap<String, SummaryEntry>() {
        // 简单 LRU：访问即移到末尾
    }
    private val lock = Any()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    init {
        runCatching { load() }.onFailure {
            log.warn("summaries.json 读取失败，备份后以空缓存启动：{}", it.message)
            AtomicFiles.backupCorrupt(file)
        }
    }

    private fun load() {
        if (!file.exists()) return
        val raw = json.decodeFromString<SummarySnapshot>(file.readText())
        val now = System.currentTimeMillis()
        synchronized(lock) {
            entries.clear()
            raw.entries.filterValues { now - it.at < TTL_MS }.forEach { (k, v) -> entries[k] = v }
        }
    }

    private fun keyOf(mode: String, title: String, excerpt: String): String {
        val excerptLimit = if (mode == "detailed") 600 else 300
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("v2\n$mode\n${title.trim()}\n${excerpt.trim().take(excerptLimit)}".toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "$mode|$hex"
    }

    fun get(mode: String, title: String, excerpt: String): String? {
        val k = keyOf(mode, title, excerpt)
        synchronized(lock) {
            val e = entries[k] ?: return null
            if (System.currentTimeMillis() - e.at >= TTL_MS) {
                entries.remove(k)
                return null
            }
            // LRU：移到末尾
            entries.remove(k)
            entries[k] = e
            return e.summary
        }
    }

    fun put(mode: String, title: String, excerpt: String, summary: String) {
        val k = keyOf(mode, title, excerpt)
        synchronized(lock) {
            entries.remove(k)
            entries[k] = SummaryEntry(summary, System.currentTimeMillis())
            while (entries.size > MAX_ENTRIES) {
                val oldest = entries.entries.firstOrNull()?.key ?: break
                entries.remove(oldest)
            }
        }
    }

    /** Coalesces concurrent cache misses so one logical summary produces one paid API call. */
    suspend fun getOrCompute(
        mode: String,
        title: String,
        excerpt: String,
        compute: suspend () -> String?
    ): String? {
        get(mode, title, excerpt)?.let { return it }
        val key = keyOf(mode, title, excerpt)
        val mine = CompletableDeferred<String?>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) return existing.await()
        try {
            get(mode, title, excerpt)?.let {
                mine.complete(it)
                return it
            }
            val value = compute()
            if (value != null) put(mode, title, excerpt, value)
            mine.complete(value)
            return value
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(key, mine)
        }
    }

    /** 持久化（原子写）。一轮总结结束后调用。 */
    fun flush() {
        val snapshot: SummarySnapshot = synchronized(lock) {
            SummarySnapshot(entries.toMap())
        }
        runCatching {
            AtomicFiles.writeTextAtomically(file, json.encodeToString(snapshot))
        }.onFailure { log.warn("summaries.json 写入失败：{}", it.message) }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    companion object {
        private const val MAX_ENTRIES = 2000
        private const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
