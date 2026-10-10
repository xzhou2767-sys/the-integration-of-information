package com.trae.info.client.platform

import io.ktor.client.engine.HttpClientEngineFactory

enum class PlatformType { ANDROID, DESKTOP }

/**
 * 平台差异点（expect/actual 独立适配）：
 * - 安卓：小屏 BottomNavigation、模拟器默认 10.0.2.2 访问宿主机服务、SharedPreferences
 * - 桌面（Windows/Linux/macOS）：大屏 NavigationRail、127.0.0.1、Preferences
 */
expect object Platform {
    val type: PlatformType
    val isLargeScreen: Boolean
    val defaultServerUrl: String
    fun getServerUrl(): String
    fun setServerUrl(url: String)
    /** 收藏夹条目 JSON（List<ContentItem> 序列化），本地持久化、不经过云端 */
    fun getFavoritesJson(): String
    fun setFavoritesJson(json: String)
    /** 已读条目 id 列表 JSON（List<String> 序列化），本地持久化（已读状态机） */
    fun getReadIdsJson(): String
    fun setReadIdsJson(json: String)
    /** 信息流本地缓存 JSON（Map<categoryId, Entry> 序列化），断网/弱网时瞬时显示上次内容 */
    fun getFeedCacheJson(): String
    fun setFeedCacheJson(json: String)
    /** 时间戳格式化（commonMain 无 java.text，由各平台 actual 提供） */
    fun formatTime(epochMs: Long): String
    /** 在系统默认浏览器中打开网页链接（原文跳转） */
    fun openUrl(url: String)
}

expect fun platformHttpEngine(): HttpClientEngineFactory<*>
