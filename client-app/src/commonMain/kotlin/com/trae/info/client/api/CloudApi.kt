package com.trae.info.client.api

import com.trae.info.client.platform.platformHttpEngine
import com.trae.info.core.api.AiConfigDto
import com.trae.info.core.api.AiSearchRequest
import com.trae.info.core.api.AiSearchResponse
import com.trae.info.core.api.AnalyzeRequest
import com.trae.info.core.api.AnalyzeResponse
import com.trae.info.core.api.CategoriesManagementResponse
import com.trae.info.core.api.CategoriesResponse
import com.trae.info.core.api.CategoryCreateRequest
import com.trae.info.core.api.CrawlerConfigDto
import com.trae.info.core.api.FeedResponse
import com.trae.info.core.api.AgentLoginRequest
import com.trae.info.core.api.AgentLoginResponse
import com.trae.info.core.api.ErrorResponse
import com.trae.info.core.api.LoginCompleteResponse
import com.trae.info.core.api.LoginScreenshotResponse
import com.trae.info.core.api.OkResponse
import com.trae.info.core.api.PreferenceDto
import com.trae.info.core.api.ProxyConfigDto
import com.trae.info.core.api.ProxyStatusResponse
import com.trae.info.core.api.RemoteClickRequest
import com.trae.info.core.api.RemoteKeyRequest
import com.trae.info.core.api.RemoteNavigateRequest
import com.trae.info.core.api.RemoteScrollRequest
import com.trae.info.core.api.RemoteTypeRequest
import com.trae.info.core.api.RemoteUrlResponse
import com.trae.info.core.api.ScrapingControlRequest
import com.trae.info.core.api.ScrapingStatusDto
import com.trae.info.core.api.SessionInfo
import com.trae.info.core.model.SubscriptionPrefs
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpCallValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** 服务端返回非 2xx 时抛出：[message] 为服务端 ErrorResponse.error 原文，可直接展示给用户。 */
class CloudApiException(val statusCode: Int, message: String) : Exception(message)

/**
 * 云端服务客户端。客户端只与云端 REST API 通信，
 * 代理穿透、AI Key、抓取调度全部在服务端完成。
 */
class CloudApi(baseUrl: String) {

    private val base = baseUrl.trimEnd('/') + "/api"

    private val client = HttpClient(platformHttpEngine()) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
        // 非 2xx 响应统一抛 CloudApiException（携带服务端 ErrorResponse.error 原文），
        // 避免错误 JSON 被反序列化为正常响应类型导致的字段缺失误导（如登录启动失败）。
        install(HttpCallValidator) {
            validateResponse { response ->
                if (!response.status.isSuccess()) {
                    val err = runCatching { response.body<ErrorResponse>() }.getOrNull()
                    throw CloudApiException(
                        response.status.value,
                        err?.error ?: "服务器错误（HTTP ${response.status.value}）"
                    )
                }
            }
        }
    }

    suspend fun categories(): CategoriesResponse =
        client.get("$base/categories").body()

    suspend fun subscription(): SubscriptionPrefs =
        client.get("$base/subscription").body()

    suspend fun saveSubscription(prefs: SubscriptionPrefs) {
        client.post("$base/subscription") {
            contentType(ContentType.Application.Json)
            setBody(prefs)
        }
    }

    suspend fun feed(categoryId: String): FeedResponse =
        client.get("$base/feed?category=$categoryId").body()

    suspend fun proxyStatus(): ProxyStatusResponse =
        client.get("$base/proxy/status").body()

    suspend fun refreshProxy(): ProxyStatusResponse =
        client.post("$base/proxy/refresh").body()

    suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse =
        client.post("$base/analyze") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    // ---- 用户信息获取偏好总结 ----

    suspend fun getPreference(): PreferenceDto =
        client.get("$base/preference").body()

    suspend fun savePreference(summary: String): OkResponse =
        client.post("$base/preference") {
            contentType(ContentType.Application.Json)
            setBody(PreferenceDto(summary = summary))
        }.body()

    // ---- 通道 A：云端浏览器登录会话 ----

    suspend fun sessions(): List<SessionInfo> =
        client.get("$base/sessions").body()

    suspend fun loginStart(platformId: String): LoginScreenshotResponse =
        client.post("$base/session/$platformId/login/start").body()

    suspend fun loginScreenshot(platformId: String): LoginScreenshotResponse =
        client.get("$base/session/$platformId/login/screenshot").body()

    suspend fun loginComplete(platformId: String, force: Boolean = false): LoginCompleteResponse =
        client.post("$base/session/$platformId/login/complete" +
            if (force) "?force=true" else "").body()

    suspend fun loginCancel(platformId: String) {
        client.post("$base/session/$platformId/login/cancel")
    }

    /** Agent 智能登录：云端自动填充账号密码表单并提交，凭据仅经 HTTPS 传输、不落盘。 */
    suspend fun agentLogin(platformId: String, username: String, password: String): AgentLoginResponse =
        client.post("$base/session/$platformId/agent/login") {
            contentType(ContentType.Application.Json)
            setBody(AgentLoginRequest(username, password))
        }.body()

    // ---- 远程浏览器控制（虚拟键鼠，作用于 startLogin 打开的活动会话）----
    // 服务端视口为 1366x900；点击坐标需按客户端显示尺寸折算回视口坐标。

    suspend fun remoteClick(platformId: String, x: Double, y: Double): OkResponse =
        client.post("$base/session/$platformId/remote/click") {
            contentType(ContentType.Application.Json); setBody(RemoteClickRequest(x, y))
        }.body()

    suspend fun remoteType(platformId: String, text: String): OkResponse =
        client.post("$base/session/$platformId/remote/type") {
            contentType(ContentType.Application.Json); setBody(RemoteTypeRequest(text))
        }.body()

    suspend fun remoteKey(platformId: String, key: String): OkResponse =
        client.post("$base/session/$platformId/remote/key") {
            contentType(ContentType.Application.Json); setBody(RemoteKeyRequest(key))
        }.body()

    suspend fun remoteNavigate(platformId: String, url: String): OkResponse =
        client.post("$base/session/$platformId/remote/navigate") {
            contentType(ContentType.Application.Json); setBody(RemoteNavigateRequest(url))
        }.body()

    suspend fun remoteScroll(platformId: String, deltaX: Double, deltaY: Double): OkResponse =
        client.post("$base/session/$platformId/remote/scroll") {
            contentType(ContentType.Application.Json); setBody(RemoteScrollRequest(deltaX, deltaY))
        }.body()

    suspend fun remoteUrl(platformId: String): RemoteUrlResponse =
        client.get("$base/session/$platformId/remote/url").body()

    // ---- 云端管理（图形化配置）----

    suspend fun getAiConfig(): AiConfigDto =
        client.get("$base/admin/config/ai").body()

    suspend fun saveAiConfig(dto: AiConfigDto): OkResponse =
        client.post("$base/admin/config/ai") {
            contentType(ContentType.Application.Json); setBody(dto)
        }.body()

    suspend fun getCrawlerConfig(): CrawlerConfigDto =
        client.get("$base/admin/config/crawler").body()

    suspend fun saveCrawlerConfig(dto: CrawlerConfigDto): OkResponse =
        client.post("$base/admin/config/crawler") {
            contentType(ContentType.Application.Json); setBody(dto)
        }.body()

    suspend fun getProxyConfig(): ProxyConfigDto =
        client.get("$base/admin/config/proxy").body()

    suspend fun saveProxyConfig(dto: ProxyConfigDto): OkResponse =
        client.post("$base/admin/config/proxy") {
            contentType(ContentType.Application.Json); setBody(dto)
        }.body()

    suspend fun getCategoriesManagement(): CategoriesManagementResponse =
        client.get("$base/admin/categories").body()

    suspend fun addCustomCategory(req: CategoryCreateRequest): OkResponse =
        client.post("$base/admin/categories") {
            contentType(ContentType.Application.Json); setBody(req)
        }.body()

    suspend fun removeCustomCategory(id: String): OkResponse =
        client.delete("$base/admin/categories/$id").body()

    suspend fun getGoals(): List<String> =
        client.get("$base/admin/goals").body()

    suspend fun saveGoals(goals: List<String>): OkResponse =
        client.post("$base/admin/goals") {
            contentType(ContentType.Application.Json); setBody(goals)
        }.body()

    suspend fun aiSearch(goal: String): AiSearchResponse =
        client.post("$base/admin/ai-search") {
            contentType(ContentType.Application.Json); setBody(AiSearchRequest(goal))
        }.body()

    // ---- 后台抓取进程控制 ----

    suspend fun scrapingStatus(): ScrapingStatusDto =
        client.get("$base/admin/scraping/status").body()

    /**
     * 启动后台抓取循环；默认间隔 12 小时（43200 秒）。
     * @param expectedMinutes 本轮预计抓取时长（分钟，≥5 不设上限，<=0 用服务端默认 15）
     * @param summaryMode AI 总结级别：brief（模糊）/ default（默认）/ detailed（详细）
     * @param summarizeAll 一键总结所有条目（true=全部条目生成 AI 总结）
     */
    suspend fun scrapingStart(
        intervalSec: Long = 43200L,
        expectedMinutes: Int = 0,
        summaryMode: String = "default",
        summarizeAll: Boolean = false
    ): ScrapingStatusDto =
        client.post("$base/admin/scraping/start") {
            contentType(ContentType.Application.Json)
            setBody(
                ScrapingControlRequest(
                    intervalSec = intervalSec,
                    expectedMinutes = expectedMinutes,
                    summaryMode = summaryMode,
                    summarizeAll = summarizeAll
                )
            )
        }.body()

    suspend fun scrapingStop(): ScrapingStatusDto =
        client.post("$base/admin/scraping/stop").body()

    /** 立即触发一次抓取；参数含义同 [scrapingStart]。 */
    suspend fun scrapingTick(
        expectedMinutes: Int = 0,
        summaryMode: String = "default",
        summarizeAll: Boolean = false
    ): ScrapingStatusDto =
        client.post("$base/admin/scraping/tick") {
            contentType(ContentType.Application.Json)
            setBody(
                ScrapingControlRequest(
                    expectedMinutes = expectedMinutes,
                    summaryMode = summaryMode,
                    summarizeAll = summarizeAll
                )
            )
        }.body()
}
