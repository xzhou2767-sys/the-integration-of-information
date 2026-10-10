package com.trae.info.core.api

import kotlinx.serialization.Serializable

/** 视频分析请求 */
@Serializable
data class VideoAnalyzeRequest(
    /** 视频 URL（http/https） */
    val url: String,
    /** 关键帧数量，默认 5（summaryLevel=详细 时生效；模糊档强制 0、默认档强制 3） */
    val keyFrameCount: Int = 5,
    /** 是否启用视觉描述（需配置视觉模型），默认 false */
    val describeFrames: Boolean = false,
    /** 成本档位（借鉴 claude-video 帧预算三档）：
     *  - 模糊：0 帧，纯转录总结（零视觉/OCR 成本，批量初筛）
     *  - 默认：3 帧，视觉描述开、OCR 关、不回传帧图（响应体积优化）
     *  - 详细：尊重 keyFrameCount（默认 5）+ OCR + 回传帧图 base64 */
    val summaryLevel: String = "默认"
)

/** 转写片段 */
@Serializable
data class TranscriptSegment(
    val start: Double,
    val end: Double,
    val text: String
)

/** 关键帧 */
@Serializable
data class KeyFrame(
    val timestamp: Double,
    /** 帧图片 base64（JPEG），供客户端展示 */
    val imageBase64: String? = null,
    /** 视觉模型描述（如已配置视觉模型） */
    val description: String? = null,
    /** OCR 提取的画面文字（方案D增强，如 PPT 标题等） */
    val ocrText: String? = null
)

/** 视频分析响应 */
@Serializable
data class VideoAnalyzeResponse(
    val title: String,
    val summary: String,
    val transcript: String,
    val language: String,
    val durationSec: Double,
    val segments: List<TranscriptSegment> = emptyList(),
    val keyFrames: List<KeyFrame> = emptyList(),
    /** 本次分析是否使用了内嵌字幕（方案B），true 表示跳过了 ASR */
    val usedSubtitles: Boolean = false
)
