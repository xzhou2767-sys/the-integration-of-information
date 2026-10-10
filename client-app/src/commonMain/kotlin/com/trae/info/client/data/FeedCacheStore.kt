package com.trae.info.client.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.trae.info.client.platform.Platform
import com.trae.info.core.api.FeedResponse
import com.trae.info.core.model.InfoCategory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 信息流本地缓存（参考 Twine 的离线阅读体验）：
 * - 类目列表 + 各类目内容双缓存：弱网/断网时信息流仍可瞬时进入并显示上次内容；
 * - 类目切换先显示本地缓存，再网络刷新覆盖；
 * - 纯客户端持久化（SharedPreferences / Preferences）；
 * - 容量 [MAX_FEED_ENTRIES] 个类目内容 + [TTL_MS] 7 天过期，写入时裁剪最旧条目。
 */
object FeedCacheStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val MAX_FEED_ENTRIES = 40
    private const val TTL_MS = 7L * 24 * 3600_000

    /** 单个类目的内容缓存条目。 */
    @kotlinx.serialization.Serializable
    data class Entry(
        val feed: FeedResponse,
        val cachedAtEpochMs: Long
    )

    /** 类目列表缓存条目。 */
    @kotlinx.serialization.Serializable
    private data class CachedCategories(
        val list: List<InfoCategory>,
        val cachedAtEpochMs: Long
    )

    /** 落盘根结构：类目列表 + 各类目内容缓存。 */
    @kotlinx.serialization.Serializable
    private data class CacheRoot(
        val categories: CachedCategories? = null,
        val feeds: Map<String, Entry> = emptyMap()
    )

    var entries: Map<String, Entry> by mutableStateOf(load().feeds)
        private set

    /** 原始类目缓存条目（保留落盘时间戳，避免每次 put 内容时误刷新类目 TTL）。 */
    private var categoriesEntry: CachedCategories? = load().categories

    /** 缓存的类目列表（未过期）。 */
    val cachedCategories: List<InfoCategory>?
        get() = categoriesEntry?.takeIf { fresh(it.cachedAtEpochMs) }?.list

    private fun fresh(cachedAtEpochMs: Long): Boolean =
        System.currentTimeMillis() - cachedAtEpochMs < TTL_MS

    private fun load(): CacheRoot = runCatching {
        val raw = Platform.getFeedCacheJson()
        if (raw.isBlank()) CacheRoot() else json.decodeFromString<CacheRoot>(raw)
    }.getOrElse { CacheRoot() }

    private fun persist(root: CacheRoot) {
        runCatching { Platform.setFeedCacheJson(json.encodeToString(root)) }
    }

    /** 取未过期的类目内容缓存；过期返回 null。 */
    fun get(categoryId: String): Entry? =
        entries[categoryId]?.takeIf { fresh(it.cachedAtEpochMs) }

    /** 写入/覆盖某类目内容缓存，并裁剪超量与过期条目。 */
    fun put(categoryId: String, feed: FeedResponse) {
        val now = System.currentTimeMillis()
        // 注意：buildMap 内部 `entries` 指向构建器自身属性，外层缓存需先捕获再进入 lambda
        val keep = entries.values
            .filter { now - it.cachedAtEpochMs < TTL_MS }
            .sortedByDescending { it.cachedAtEpochMs }
            .take(MAX_FEED_ENTRIES - 1)
        entries = buildMap {
            keep.forEach { put(it.feed.categoryId, it) }
            put(categoryId, Entry(feed, now))
        }
        persist(CacheRoot(categories = categoriesEntry, feeds = entries))
    }

    /** 缓存类目列表（断网时信息流仍可进入）。 */
    fun putCategories(list: List<InfoCategory>) {
        if (list.isEmpty()) return
        categoriesEntry = CachedCategories(list, System.currentTimeMillis())
        persist(CacheRoot(categories = categoriesEntry, feeds = entries))
    }

    /** 清空全部信息流缓存。 */
    fun clearAll() {
        entries = emptyMap()
        categoriesEntry = null
        runCatching { Platform.setFeedCacheJson("") }
    }
}
