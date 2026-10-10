package com.trae.info.server.ai

/**
 * AI 平台统一抽象。豆包/GPT 走 OpenAI 兼容协议，Gemini 走 Google 原生协议。
 */
interface AiProvider {
    val id: String
    val displayName: String
    /** 该平台是否需要经云端代理访问 */
    val requiresProxy: Boolean

    suspend fun chat(systemPrompt: String, userPrompt: String, maxOutputTokens: Int? = null): String

    /** 视觉理解：描述图片内容（imageBase64 为 JPEG base64，不含 data: 前缀）。默认不支持。 */
    suspend fun describeImage(imageBase64: String): String =
        throw UnsupportedOperationException("${this.displayName} 不支持视觉理解")
}
