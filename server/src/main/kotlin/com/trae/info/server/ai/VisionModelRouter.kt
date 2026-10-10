package com.trae.info.server.ai

import com.trae.info.server.proxy.ProxyPool
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 视觉模型路由器：
 * - 按「性价比」排序的视觉模型候选列表（输入单价 + 输出单价综合排序）；
 * - 自动切换：额度不足 / 限流 / 超时(>180s) / 其他错误时，降级到下一个候选模型；
 * - 健康度追踪：失败模型进入冷却期，避免反复尝试已知不可用模型；
 * - 支持双通道：火山方舟（ARK）和 LAS 数据湖算子。
 *
 * 2026-09 起老 doubao-vision 专用系列（seed-1.6-vision / 1.5-vision-pro/lite）
 * 已进入下线流程（2026-09-21 正式下线），视觉理解由新一代 Doubao Seed 2.x
 * 系列承载——均为「文本生成」类目下原生多模态模型，可直接传图片：
 *
 * 性价比排序（档位由低到高）：
 *   1. doubao-seed-2-0-mini-260428   极低成本、高并发，30000 RPM
 *   2. doubao-seed-2-0-lite-260428   低时延、高并发，30000 RPM
 *   3. doubao-seed-2-1-turbo-260628  高性价比，256k 上下文
 *   4. doubao-seed-2-1-pro-260628    旗舰稳定版，多模态+深度思考，256k
 *   5. las-vision                    LAS 数据湖算子（独立通道/计费，ARK 全挂时兜底）
 *   6. doubao-seed-evolving          周级更新旗舰，最贵，最后兜底
 *
 * 切换触发条件：
 *   - HTTP 429（限流）/ 402（额度不足）/ 403（未开通）
 *   - 响应体包含 "quota" / "insufficient" / "exceeded" / "rate limit"
 *   - 单次调用耗时 > MAX_CALL_TIMEOUT_MS（180s）
 *   - 网络异常 / 解析异常
 */
class VisionModelRouter(
    private val arkBaseUrl: () -> String,
    private val arkApiKey: () -> String,
    private val lasApiKey: () -> String,
    private val pool: ProxyPool
) {
    private val log = LoggerFactory.getLogger(VisionModelRouter::class.java)

    /** 单次视觉调用最大耗时（秒），超时则切换下一模型。 */
    val maxCallTimeoutSec: Long = 180L

    /** 模型失败后冷却时间（毫秒），期间不再尝试该模型。 */
    private val cooldownMs: Long = 60_000L

    /** 候选视觉模型列表（按性价比优先级排序）。
     *  模型 ID 为方舟实际可用的版本化 ID；未开通的模型会自动跳过并降级。
     *  2026-09 起切换为 Seed 2.x 原生多模态系列（老 vision 专用系列 9/21 下线）：
     *    2-0-mini（极低价） < 2-0-lite（低时延） < 2-1-turbo（高性价比）
     *    < 2-1-pro（旗舰稳定） < las-vision（独立通道） < seed-evolving（最贵兜底）
     */
    private val candidates: List<VisionCandidate> = listOf(
        VisionCandidate(
            modelId = "doubao-seed-2-0-mini-260428",
            provider = Provider.ARK,
            priority = 1,
            estimatedCostPerFrame = 0.002,
            description = "Seed 2.0 mini，原生多模态，极低成本、高并发（30000 RPM）"
        ),
        VisionCandidate(
            modelId = "doubao-seed-2-0-lite-260428",
            provider = Provider.ARK,
            priority = 2,
            estimatedCostPerFrame = 0.002,
            description = "Seed 2.0 lite，原生多模态，低时延、高并发（30000 RPM）"
        ),
        VisionCandidate(
            modelId = "doubao-seed-2-1-turbo-260628",
            provider = Provider.ARK,
            priority = 3,
            estimatedCostPerFrame = 0.004,
            description = "Seed 2.1 turbo，原生多模态+深度思考，高性价比，256k 上下文"
        ),
        VisionCandidate(
            modelId = "doubao-seed-2-1-pro-260628",
            provider = Provider.ARK,
            priority = 4,
            estimatedCostPerFrame = 0.008,
            description = "Seed 2.1 pro，旗舰稳定版，原生多模态+深度思考，256k 上下文"
        ),
        // LAS 通道：独立于方舟的计费与额度，方舟模型全挂时的主力备用
        VisionCandidate(
            modelId = "las-vision",
            provider = Provider.LAS,
            priority = 5,
            estimatedCostPerFrame = 0.005,
            description = "LAS 数据湖视觉理解算子（独立通道，方舟额度/限流故障时兜底）"
        ),
        VisionCandidate(
            modelId = "doubao-seed-evolving",
            provider = Provider.ARK,
            priority = 6,
            estimatedCostPerFrame = 0.018,
            description = "Seed evolving 周级迭代旗舰，原生多模态，最贵，最后兜底"
        )
    )

    /** 各模型健康度状态。 */
    private val health = ConcurrentHashMap<String, ModelHealth>()

    /** 描述单张图片：按优先级尝试各模型，自动降级。 */
    suspend fun describeImage(imageBase64: String, preferredModel: String? = null): String {
        val ordered = buildOrderedList(preferredModel)
        var lastError: Throwable? = null

        for (candidate in ordered) {
            if (!isAvailable(candidate)) continue

            val start = System.currentTimeMillis()
            try {
                val result = withTimeout(maxCallTimeoutSec * 1000) {
                    callVision(candidate, imageBase64)
                }
                val elapsed = System.currentTimeMillis() - start
                log.info("视觉模型 {} 调用成功，耗时 {} ms", candidate.modelId, elapsed)
                recordSuccess(candidate)
                return result
            } catch (e: TimeoutCancellationException) {
                lastError = e
                log.warn("视觉模型 {} 超时（>{}s），切换下一模型", candidate.modelId, maxCallTimeoutSec)
                recordFailure(candidate, ErrorReason.TIMEOUT)
            } catch (e: CancellationException) {
                throw e // 协程取消不降级
            } catch (e: Throwable) {
                lastError = e
                val reason = classifyError(e)
                log.warn("视觉模型 {} 失败（{}）：{}", candidate.modelId, reason, e.message)
                recordFailure(candidate, reason)
                if (reason == ErrorReason.AUTH) {
                    // 鉴权失败通常所有 ARK 模型都会失败，跳过所有 ARK 模型
                    log.warn("ARK 鉴权失败，跳过所有 ARK 模型，尝试 LAS")
                    health.keys.filter { it.startsWith("ark:") }.forEach { markCooldown(it) }
                }
            }
        }

        throw lastError ?: IllegalStateException("所有视觉模型均不可用")
    }

    /** 按优先级构建尝试顺序，preferredModel 优先排到最前。 */
    private fun buildOrderedList(preferred: String?): List<VisionCandidate> {
        if (preferred.isNullOrBlank()) return candidates.sortedBy { it.priority }
        val pref = candidates.firstOrNull { it.modelId == preferred }
        return if (pref != null) listOf(pref) + candidates.filter { it.modelId != preferred }.sortedBy { it.priority }
        else candidates.sortedBy { it.priority }
    }

    /** 模型是否可用（未在冷却期内）。 */
    private fun isAvailable(c: VisionCandidate): Boolean {
        if (c.provider == Provider.LAS && lasApiKey().isBlank()) return false
        if (c.provider == Provider.ARK && arkApiKey().isBlank()) return false
        val h = health[c.key] ?: return true
        return System.currentTimeMillis() >= h.cooldownUntil
    }

    /** 调用具体模型的视觉理解。 */
    private suspend fun callVision(candidate: VisionCandidate, imageBase64: String): String {
        return when (candidate.provider) {
            Provider.ARK -> callArkVision(candidate.modelId, imageBase64)
            Provider.LAS -> callLasVision(imageBase64)
        }
    }

    /** 调用方舟视觉模型（OpenAI 兼容接口）。
     *  id 设为 "doubao" 以触发 thinking=disabled，确保视觉描述快速返回（禁用深度思考）。 */
    private suspend fun callArkVision(modelId: String, imageBase64: String): String {
        val provider = OpenAiCompatProvider(
            id = "doubao",
            displayName = "方舟视觉($modelId)",
            requiresProxy = false,
            baseUrl = arkBaseUrl(),
            apiKey = arkApiKey(),
            model = modelId,
            pool = pool
        )
        return provider.describeImage(imageBase64)
    }

    /** 调用 LAS 数据湖视觉理解算子。 */
    private suspend fun callLasVision(imageBase64: String): String {
        val client = pool.clientFor(false)
        val key = lasApiKey()
        val body = buildJsonObject {
            put("model", "doubao-seed-1.6")
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "请用中文简洁描述这张图片的主要内容。")
                        }
                        addJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/jpeg;base64,$imageBase64")
                            })
                        }
                    }
                }
            }
        }
        val resp = client.post("https://operator.las.cn-beijing.volces.com/api/v1/chat/completions") {
            header("Authorization", "Bearer $key")
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(body.toString())
        }.body<String>()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val parsed = json.parseToJsonElement(resp)
        return parsed.jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: error("LAS 视觉响应解析失败: ${resp.take(500)}")
    }

    /** 错误分类，决定是否切换模型。 */
    private fun classifyError(e: Throwable): ErrorReason {
        val msg = e.message?.lowercase() ?: ""
        return when {
            msg.contains("429") || msg.contains("rate limit") || msg.contains("too many") -> ErrorReason.RATE_LIMIT
            msg.contains("402") || msg.contains("quota") || msg.contains("insufficient") || msg.contains("exceeded") || msg.contains("余额") -> ErrorReason.QUOTA
            msg.contains("403") || msg.contains("forbidden") || msg.contains("unauthorized") || msg.contains("401") -> ErrorReason.AUTH
            msg.contains("timeout") || msg.contains("timed out") -> ErrorReason.TIMEOUT
            else -> ErrorReason.OTHER
        }
    }

    private fun recordSuccess(c: VisionCandidate) {
        health.remove(c.key)
    }

    private fun recordFailure(c: VisionCandidate, reason: ErrorReason) {
        val h = health.getOrPut(c.key) { ModelHealth() }
        h.failureCount.incrementAndGet()
        h.lastFailureMs.set(System.currentTimeMillis())
        h.lastReason = reason
        markCooldown(c.key)
    }

    private fun markCooldown(key: String) {
        val h = health.getOrPut(key) { ModelHealth() }
        h.cooldownUntil = System.currentTimeMillis() + cooldownMs
    }

    /** 当前所有候选模型的健康状态（供管理界面展示）。 */
    fun healthSnapshot(): List<ModelHealthDto> = candidates.map { c ->
        val h = health[c.key]
        ModelHealthDto(
            modelId = c.modelId,
            provider = c.provider.name,
            priority = c.priority,
            available = isAvailable(c),
            failureCount = h?.failureCount?.get() ?: 0,
            cooldownUntilEpochMs = h?.cooldownUntil?.takeIf { it > System.currentTimeMillis() },
            lastReason = h?.lastReason?.name
        )
    }

    private enum class Provider { ARK, LAS }

    private data class VisionCandidate(
        val modelId: String,
        val provider: Provider,
        val priority: Int,
        val estimatedCostPerFrame: Double,
        val description: String
    ) {
        val key: String get() = "${provider.name.lowercase()}:$modelId"
    }

    private class ModelHealth {
        val failureCount = AtomicInteger(0)
        val lastFailureMs = AtomicLong(0)
        @Volatile var cooldownUntil: Long = 0
        @Volatile var lastReason: ErrorReason = ErrorReason.OTHER
    }

    private enum class ErrorReason { QUOTA, RATE_LIMIT, AUTH, TIMEOUT, OTHER }

    @Serializable
    data class ModelHealthDto(
        val modelId: String,
        val provider: String,
        val priority: Int,
        val available: Boolean,
        val failureCount: Int,
        val cooldownUntilEpochMs: Long?,
        val lastReason: String?
    )
}
