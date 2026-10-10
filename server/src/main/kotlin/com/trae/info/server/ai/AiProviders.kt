package com.trae.info.server.ai

import com.trae.info.core.api.AnalyzeResponse
import com.trae.info.server.config.AppConfig
import com.trae.info.server.proxy.ProxyPool
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * AI 提供方注册表：按 豆包(火山方舟 Ark) -> GPT(OpenAI) -> Gemini 顺序择优使用。
 * Key 通过服务端环境变量/配置注入，绝不下发到客户端。
 */
class AiProviders(
    private val cfg: AppConfig,
    private val pool: ProxyPool
) {
    private val log = LoggerFactory.getLogger(AiProviders::class.java)

    /** 视觉模型路由器：按性价比排序 + 额度/超时自动切换。
     *  使用 getter 读取 API Key，使管理界面修改后即时生效。 */
    val visionRouter: VisionModelRouter by lazy {
        VisionModelRouter(
            arkBaseUrl = { cfg.doubaoBaseUrl },
            arkApiKey = { cfg.doubaoKey },
            lasApiKey = { cfg.lasApiKey },
            pool = pool
        )
    }
    /**
     * 每次调用时按当前 ConfigStore 快照重建 providers 列表，
     * 使云端管理界面修改 AI Key 后立即生效。
     */
    private fun buildProviders(): List<AiProvider> = buildList {
        if (cfg.doubaoKey.isNotBlank()) {
            add(
                OpenAiCompatProvider(
                    id = "doubao",
                    displayName = "豆包 · 火山方舟 Ark",
                    requiresProxy = false,          // 国内服务，直连
                    baseUrl = cfg.doubaoBaseUrl,
                    apiKey = cfg.doubaoKey,
                    model = cfg.doubaoModel,
                    pool = pool
                )
            )
        }
        if (cfg.openaiKey.isNotBlank()) {
            add(
                OpenAiCompatProvider(
                    id = "openai",
                    displayName = "GPT · OpenAI",
                    requiresProxy = true,
                    baseUrl = cfg.openaiBaseUrl,
                    apiKey = cfg.openaiKey,
                    model = cfg.openaiModel,
                    pool = pool
                )
            )
        }
        if (cfg.geminiKey.isNotBlank()) {
            add(GeminiProvider(cfg.geminiKey, cfg.geminiModel, pool))
        }
    }

    /** 取首个可用 provider，无可用时抛异常。 */
    fun firstProvider(): AiProvider = buildProviders().firstOrNull()
        ?: throw IllegalStateException(
            "未配置任何 AI API Key，请在云端管理界面或环境变量设置 DOUBAO_API_KEY / OPENAI_API_KEY / GEMINI_API_KEY 之一"
        )

    // ---- 语言模型降级链 ----

    /** 单次语言模型调用最大耗时（毫秒），超时则降级到下一 provider。 */
    private val langCallTimeoutMs: Long = 120_000L

    /** 单条总结最大耗时（毫秒）：总结必须快，超时由调用方跳过该条。 */
    private val SUMMARY_CALL_TIMEOUT_MS: Long = 20_000L
    /** 模糊级别总结更快（≤25字）。 */
    private val SUMMARY_BRIEF_TIMEOUT_MS: Long = 15_000L
    /** 详细级别总结允许更长（80-120字）。 */
    private val SUMMARY_DETAILED_TIMEOUT_MS: Long = 30_000L

    /** provider 失败后冷却时间（毫秒），期间跳过该 provider。 */
    private val langCooldownMs: Long = 60_000L

    /** 各语言 provider 健康状态（id -> 冷却截止时间）。 */
    private val langHealth = ConcurrentHashMap<String, Long>()

    /** 语言模型调用结果。 */
    data class ChatResult(val providerId: String, val providerName: String, val content: String)

    /**
     * 语言模型路由：按 豆包 -> GPT(OpenAI) -> Gemini 顺序尝试，
     * 鉴权失败/额度不足/限流/超时/网络异常时自动降级到下一 provider，
     * 失败 provider 进入冷却期，避免反复尝试已知不可用通道。
     */
    suspend fun chat(
        systemPrompt: String,
        userPrompt: String,
        timeoutMs: Long = langCallTimeoutMs,
        maxOutputTokens: Int? = null
    ): ChatResult {
        val providers = buildProviders()
        if (providers.isEmpty()) {
            throw IllegalStateException(
                "未配置任何 AI API Key，请在云端管理界面或环境变量设置 DOUBAO_API_KEY / OPENAI_API_KEY / GEMINI_API_KEY 之一"
            )
        }
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        for (provider in providers) {
            val cooldownUntil = langHealth[provider.id] ?: 0L
            if (System.currentTimeMillis() < cooldownUntil) {
                log.info("语言模型 {} 处于冷却期，跳过", provider.id)
                continue
            }
            val start = System.currentTimeMillis()
            try {
                val remainingMs = deadlineMs - System.currentTimeMillis()
                if (remainingMs <= 0) break
                val content = withTimeout(remainingMs) {
                    provider.chat(systemPrompt, userPrompt, maxOutputTokens)
                }
                log.info("语言模型 {} 调用成功，耗时 {} ms", provider.id, System.currentTimeMillis() - start)
                langHealth.remove(provider.id)
                return ChatResult(provider.id, provider.displayName, content)
            } catch (e: TimeoutCancellationException) {
                // 仅当协程仍活跃时才是"本次调用超时"；外层取消（如 stop）透传为 CancellationException
                if (!currentCoroutineContext().isActive) throw e
                lastError = e
                log.warn("语言模型 {} 超时（逻辑请求总预算 {}s），降级到下一通道", provider.id, timeoutMs / 1000)
                markLangCooldown(provider.id)
            } catch (e: CancellationException) {
                throw e // 协程取消不降级
            } catch (e: Throwable) {
                lastError = e
                log.warn("语言模型 {} 调用失败：{}", provider.id, e.message)
                markLangCooldown(provider.id)
            }
        }
        throw lastError ?: IllegalStateException("所有语言模型通道均不可用")
    }

    private fun markLangCooldown(providerId: String) {
        langHealth[providerId] = System.currentTimeMillis() + langCooldownMs
    }

    suspend fun analyze(mode: String, categoryName: String?, extraText: String?): AnalyzeResponse {
        val (system, user) = PromptStrategies.build(mode, categoryName, extraText)
        val result = chat(system, user)
        return AnalyzeResponse(provider = result.providerName, content = result.content)
    }

    /**
     * 条目中文总结：复用语言模型降级链（豆包 → GPT → Gemini）。
     * per-call 超时按级别分档（brief 15s / default 20s / detailed 30s），
     * 超时由调用方跳过该条；外层协程取消不误标通道冷却。
     * @param mode brief（模糊）/ default（默认）/ detailed（详细）
     */
    suspend fun summarize(title: String, excerpt: String, mode: String = "default"): ChatResult {
        val (system, user) = PromptStrategies.buildSummarize(title, excerpt, mode)
        val timeoutMs = when (mode) {
            "brief" -> SUMMARY_BRIEF_TIMEOUT_MS
            "detailed" -> SUMMARY_DETAILED_TIMEOUT_MS
            else -> SUMMARY_CALL_TIMEOUT_MS
        }
        val maxOutputTokens = when (mode) {
            "brief" -> 80
            "detailed" -> 240
            else -> 120
        }
        return chat(system, user, timeoutMs = timeoutMs, maxOutputTokens = maxOutputTokens)
    }

    /**
     * AI 驱动自定对象搜索的第一步：理解用户收集目的，提取检索关键词与检索策略。
     * @return (keywords, reasoning)
     */
    suspend fun extractKeywords(goal: String): Pair<List<String>, String> {
        val (system, user) = PromptStrategies.buildKeywordExtraction(goal)
        val content = chat(system, user).content
        // 解析 "关键词：a, b, c" 与 "策略：..." 两行
        val (kwLine, reasoningLine) = content.lines().partition { it.contains("关键词") || it.contains("keyword") }
        val keywords = kwLine.joinToString("")
            .substringAfter("：").substringAfter(":")
            .split(",", "，", "\n", "、")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length <= 30 }
            .take(6)
            .ifEmpty { listOf(goal.take(30)) }
        val reasoning = reasoningLine.joinToString(" ").ifBlank { content.take(200) }
        return keywords to reasoning
    }

    /**
     * 视觉理解：委托给 VisionModelRouter，按性价比排序自动尝试，
     * 额度不足/限流/超时(>180s)时自动切换下一模型。
     * @param visionModel 用户首选模型（可为空），优先级最高
     */
    suspend fun describeImage(visionModel: String, imageBase64: String): String {
        val preferred = visionModel.takeIf { it.isNotBlank() }
        return visionRouter.describeImage(imageBase64, preferred)
    }
}

/**
 * 豆包 Ark 与 OpenAI 均兼容 OpenAI Chat Completions 协议。
 */
internal class OpenAiCompatProvider(
    override val id: String,
    override val displayName: String,
    override val requiresProxy: Boolean,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val pool: ProxyPool
) : AiProvider {

    @Serializable
    private data class Msg(val role: String, val content: String)

    @Serializable
    private data class Thinking(val type: String)

    @Serializable
    private data class Req(
        val model: String,
        val messages: List<Msg>,
        val temperature: Double = 0.6,
        val thinking: Thinking? = null,
        @SerialName("max_tokens") val maxTokens: Int? = null
    )

    @Serializable
    private data class Choice(val message: Msg)

    @Serializable
    private data class Resp(val choices: List<Choice> = emptyList())

    override suspend fun chat(systemPrompt: String, userPrompt: String, maxOutputTokens: Int?): String {
        val client = pool.clientFor(requiresProxy)
        // 豆包推理模型默认会深度思考导致超时；显式禁用思考以加速响应
        val thinking = if (id == "doubao") Thinking("disabled") else null
        val resp: Resp = client.post("$baseUrl/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(
                Req(
                    model = model,
                    messages = listOf(
                        Msg("system", systemPrompt),
                        Msg("user", userPrompt)
                    ),
                    thinking = thinking,
                    maxTokens = maxOutputTokens
                )
            )
        }.body()
        return resp.choices.firstOrNull()?.message?.content
            ?: error("$displayName 返回内容为空")
    }

    /** 视觉理解：用指定视觉模型描述图片 */
    override suspend fun describeImage(imageBase64: String): String {
        val client = pool.clientFor(requiresProxy)
        val thinking = if (id == "doubao") Thinking("disabled") else null
        val visionReq = VisionReq(
            model = model,
            messages = listOf(
                VisionMsg(
                    role = "user",
                    content = listOf(
                        ContentPart(type = "text", text = "请用中文简洁描述这张图片的主要内容，包括场景、物体、人物、文字等关键信息。"),
                        ContentPart(type = "image_url", imageUrl = ImageUrl("data:image/jpeg;base64,$imageBase64"))
                    )
                )
            ),
            thinking = thinking
        )
        val resp: Resp = client.post("$baseUrl/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(visionReq)
        }.body()
        return resp.choices.firstOrNull()?.message?.content
            ?: error("$displayName 视觉描述返回为空")
    }

    @Serializable
    private data class ImageUrl(val url: String)

    @Serializable
    private data class ContentPart(
        val type: String,
        val text: String? = null,
        @SerialName("image_url") val imageUrl: ImageUrl? = null
    )

    @Serializable
    private data class VisionMsg(val role: String, val content: List<ContentPart>)

    @Serializable
    private data class VisionReq(
        val model: String,
        val messages: List<VisionMsg>,
        val thinking: Thinking? = null
    )
}

private class GeminiProvider(
    private val apiKey: String,
    private val model: String,
    private val pool: ProxyPool
) : AiProvider {

    override val id: String = "gemini"
    override val displayName: String = "Gemini · Google"
    override val requiresProxy: Boolean = true

    @Serializable
    private data class Part(val text: String)

    @Serializable
    private data class Content(val role: String? = null, val parts: List<Part>)

    @Serializable
    private data class GenerationConfig(val maxOutputTokens: Int? = null)

    @Serializable
    private data class Req(
        val contents: List<Content>,
        val systemInstruction: Content? = null,
        val generationConfig: GenerationConfig? = null
    )

    @Serializable
    private data class Candidate(val content: Content)

    @Serializable
    private data class Resp(val candidates: List<Candidate> = emptyList())

    override suspend fun chat(systemPrompt: String, userPrompt: String, maxOutputTokens: Int?): String {
        val client = pool.clientFor(requiresProxy = true)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val resp: Resp = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(
                Req(
                    contents = listOf(Content(role = "user", parts = listOf(Part(userPrompt)))),
                    systemInstruction = Content(parts = listOf(Part(systemPrompt))),
                    generationConfig = maxOutputTokens?.let(::GenerationConfig)
                )
            )
        }.body()
        return resp.candidates.firstOrNull()?.content?.parts?.joinToString("") { it.text }
            ?: error("Gemini 返回内容为空")
    }
}
