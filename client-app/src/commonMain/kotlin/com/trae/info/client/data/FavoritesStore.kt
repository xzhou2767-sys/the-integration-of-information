package com.trae.info.client.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.trae.info.client.platform.Platform
import com.trae.info.core.model.ContentItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 收藏夹：纯客户端本地持久化（SharedPreferences / Preferences），不经过云端。
 * 条目以 [ContentItem.id] 去重（id 含平台前缀，全局唯一）；新收藏置顶。
 * [items] 为 Compose 可观察状态，星标切换后信息流卡片与收藏夹页面自动刷新。
 */
object FavoritesStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var items: List<ContentItem> by mutableStateOf(load())
        private set

    private fun load(): List<ContentItem> = runCatching {
        val raw = Platform.getFavoritesJson()
        if (raw.isBlank()) emptyList()
        else json.decodeFromString<List<ContentItem>>(raw)
    }.getOrElse { emptyList() }

    fun isFavorite(id: String): Boolean = items.any { it.id == id }

    /** 切换收藏状态：已收藏则移除，未收藏则置顶加入。每次变更原子落盘。 */
    fun toggle(item: ContentItem) {
        items = if (isFavorite(item.id)) {
            items.filterNot { it.id == item.id }
        } else {
            listOf(item) + items
        }
        runCatching { Platform.setFavoritesJson(json.encodeToString<List<ContentItem>>(items)) }
    }
}
