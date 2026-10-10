package com.trae.info.client.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.trae.info.client.platform.Platform
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 已读状态机（参考 ReadYou/Fusion 的 unread → read → starred 模式，本项目 star 由 FavoritesStore 承担）。
 * 纯客户端本地持久化（SharedPreferences / Preferences），不经过云端。
 * 打开条目详情即标记已读；已读卡片标题置灰并在信息流中降权显示。
 * [readIds] 为 Compose 可观察状态，标记后信息流卡片自动刷新。
 * 容量上限 [MAX_IDS]（2000），超出裁剪最旧记录防止无限膨胀。
 */
object ReadStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())
    private const val MAX_IDS = 2000

    /** 已读条目 id 列表（最新在前）。 */
    var readIds: List<String> by mutableStateOf(load())
        private set

    /** O(1) 查询集合（与 readIds 同步维护）。 */
    private val idSet: MutableSet<String> = readIds.toHashSet()

    private fun load(): List<String> = runCatching {
        val raw = Platform.getReadIdsJson()
        if (raw.isBlank()) emptyList() else json.decodeFromString(serializer, raw)
    }.getOrElse { emptyList() }

    fun isRead(id: String): Boolean = idSet.contains(id)

    /** 标记已读（打开详情弹层时调用）；重复标记为空操作。 */
    fun markRead(ids: List<String>) {
        val fresh = ids.filterNot { idSet.contains(it) }
        if (fresh.isEmpty()) return
        fresh.forEach(idSet::add)
        readIds = fresh + readIds
        persist()
    }

    /** 清空全部已读记录（信息流恢复未读状态）。 */
    fun clearAll() {
        idSet.clear()
        readIds = emptyList()
        persist()
    }

    private fun persist() {
        if (readIds.size > MAX_IDS) {
            val trimmed = readIds.take(MAX_IDS)
            readIds = trimmed
            idSet.clear()
            idSet.addAll(trimmed)
        }
        runCatching { Platform.setReadIdsJson(json.encodeToString(serializer, readIds)) }
    }
}
