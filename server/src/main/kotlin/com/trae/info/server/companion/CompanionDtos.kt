package com.trae.info.server.companion

import kotlinx.serialization.Serializable

/**
 * 通道 B（浏览器伴侣脚本）上报载荷。
 * 用户在自己真实浏览器（已登录、真实 IP）中采集后推送到云端。
 */
@Serializable
data class CompanionIngest(
    val platformId: String,
    val categoryId: String,
    val items: List<CompanionItem> = emptyList()
)

@Serializable
data class CompanionItem(
    val id: String,
    val title: String,
    val url: String,
    val summary: String = "",
    val author: String = ""
)
