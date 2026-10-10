package com.trae.info.core.model

import kotlinx.serialization.Serializable

/**
 * 经过采集、清洗后的信息条目。视频/图文均以此模型进入信息流。
 */
@Serializable
data class ContentItem(
    val id: String,
    val categoryId: String,
    val source: String,              // SourcePlatform.id
    val title: String,
    val summary: String = "",
    val url: String,
    val author: String = "",
    /** epoch 毫秒；未知为 0 */
    val publishedAt: Long = 0L,
    val tags: List<String> = emptyList(),
    /** AI 二次分析摘要，可空 */
    val aiSummary: String? = null,
    /** 外文标题的中文翻译（服务端批量翻译+缓存，翻译出片方向 P0），可空 */
    val translatedTitle: String? = null
)
