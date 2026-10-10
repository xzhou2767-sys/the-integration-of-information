package com.trae.info.core.model

import kotlinx.serialization.Serializable

/**
 * 用户自定义平台组合：选取若干平台编入一组，可重命名并选为抓取来源。
 * 激活后覆盖单独平台开关——仅组合内平台参与抓取。
 */
@Serializable
data class PlatformGroup(
    val id: String,
    val name: String,
    val platformIds: List<String>,
    val createdAt: Long = 0L
)

/**
 * 用户客制化订阅偏好：在图形界面勾选的细分类目与信息平台。
 * 未出现在 map 中的键取默认值（类目默认开启、平台默认开启）。
 *
 * [platformGroups] 与 [activeGroupId] 为 0.5.3 新增：
 * - activeGroupId = null：使用单独平台开关（向后兼容旧客户端行为）；
 * - activeGroupId 非空：仅组合内平台启用，单独开关被忽略。
 */
@Serializable
data class SubscriptionPrefs(
    val enabledCategories: Map<String, Boolean> = emptyMap(),
    val enabledSources: Map<String, Boolean> = emptyMap(),
    val platformGroups: List<PlatformGroup> = emptyList(),
    val activeGroupId: String? = null,
    val updatedAt: Long = 0L
) {
    fun categoryEnabled(id: String): Boolean =
        enabledCategories[id] ?: (Taxonomy.byId(id)?.defaultEnabled ?: true)

    /**
     * 当 [activeGroupId] 非空时，仅组合内平台返回 true；为空时走单独开关。
     */
    fun sourceEnabled(platform: SourcePlatform): Boolean {
        activeGroupId?.let { gid ->
            val group = platformGroups.firstOrNull { it.id == gid }
            return group?.platformIds?.contains(platform.id) ?: false
        }
        return enabledSources[platform.id] ?: true
    }

    fun toggledCategory(id: String, enabled: Boolean): SubscriptionPrefs =
        copy(enabledCategories = enabledCategories + (id to enabled), updatedAt = nowMs())

    fun toggledSource(id: String, enabled: Boolean): SubscriptionPrefs =
        copy(enabledSources = enabledSources + (id to enabled), updatedAt = nowMs())

    fun groupById(id: String): PlatformGroup? = platformGroups.firstOrNull { it.id == id }

    fun upsertGroup(group: PlatformGroup): SubscriptionPrefs = copy(
        platformGroups = platformGroups.filterNot { it.id == group.id } + group,
        updatedAt = nowMs()
    )

    fun removeGroup(id: String): SubscriptionPrefs = copy(
        platformGroups = platformGroups.filterNot { it.id == id },
        activeGroupId = if (activeGroupId == id) null else activeGroupId,
        updatedAt = nowMs()
    )

    fun activateGroup(id: String?): SubscriptionPrefs =
        copy(activeGroupId = id, updatedAt = nowMs())
}

/** 跨平台时间戳（commonMain 无 System.currentTimeMillis）。 */
expect fun nowMs(): Long
