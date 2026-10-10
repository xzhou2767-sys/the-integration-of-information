package com.trae.info.server.routes

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

import com.trae.info.core.api.AiConfigDto
import com.trae.info.core.api.AiSearchRequest
import com.trae.info.core.api.AiSearchResponse
import com.trae.info.core.api.AnalyzeRequest
import com.trae.info.core.api.CategoriesManagementResponse
import com.trae.info.core.api.CategoriesResponse
import com.trae.info.core.api.CategoryCreateRequest
import com.trae.info.core.api.CrawlerConfigDto
import com.trae.info.core.api.ErrorResponse
import com.trae.info.core.api.AgentLoginRequest
import com.trae.info.core.api.AgentLoginResponse
import com.trae.info.core.api.LoginCompleteResponse
import com.trae.info.core.api.LoginScreenshotResponse
import com.trae.info.core.api.OkResponse
import com.trae.info.core.api.PreferenceDto
import com.trae.info.core.api.ProxyConfigDto
import com.trae.info.core.api.RemoteClickRequest
import com.trae.info.core.api.RemoteKeyRequest
import com.trae.info.core.api.RemoteNavigateRequest
import com.trae.info.core.api.RemoteScrollRequest
import com.trae.info.core.api.RemoteTypeRequest
import com.trae.info.core.api.RemoteUrlResponse
import com.trae.info.core.api.ScrapingControlRequest
import com.trae.info.core.api.ScrapingStatusDto
import com.trae.info.core.api.SessionInfo
import com.trae.info.core.api.VideoAnalyzeRequest
import com.trae.info.core.model.Domain
import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.SourcePlatform
import com.trae.info.core.model.SubscriptionPrefs
import com.trae.info.core.model.Taxonomy
import com.trae.info.server.browser.AgentLoginEngine
import com.trae.info.server.browser.BrowserFetchService
import com.trae.info.server.browser.BrowserScrapers
import com.trae.info.server.companion.CompanionIngest
import com.trae.info.server.config.AppConfig
import com.trae.info.server.config.ConfigStore
import com.trae.info.server.proxy.ProxyPool
import com.trae.info.server.service.ContentService
import com.trae.info.server.service.PreferenceEngine
import com.trae.info.server.service.ScrapingProcessService
import com.trae.info.server.service.VideoAnalysisService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.Base64

/**
 * 云端 REST API：
 * - GET  /api/categories        全量细分类目 + 平台（含动态自定义）
 * - GET/POST /api/subscription  订阅偏好
 * - GET  /api/feed?category=    按类目聚合（自动应用降级链与云端代理）
 * - POST /api/analyze           AI 领域/倾向分析
 * - GET  /api/sessions          各平台浏览器登录会话状态
 * - POST /api/session/{p}/login/start|complete|cancel；GET .../screenshot
 * - POST /api/session/{p}/agent/login（Agent 智能登录：云端自动填充账号密码）
 * - POST /api/session/{p}/remote/click|type|key|navigate|scroll；GET .../remote/url（虚拟键鼠远程操控）
 * - POST /api/companion/ingest  通道 B 伴侣脚本上报
 *
 * 云端管理（图形化配置）：
 * - GET/POST /api/admin/config/ai      AI Key / 模型 / BaseUrl
 * - GET/POST /api/admin/config/crawler 抓取平台密钥
 * - GET/POST /api/admin/config/proxy   3xui 代理参数
 * - GET  /api/admin/categories         默认 + 自定义分类
 * - POST /api/admin/category           新增自定义分类
 * - DELETE /api/admin/category/{id}    删除自定义分类
 * - GET/POST /api/admin/goals          收集目的列表
 * - POST /api/admin/ai-search          AI 驱动自定对象搜索
 * - POST /api/video/analyze            视频内容分析（音频提取→ASR→关键帧→总结）
 * - POST /api/admin/scraping/start     手动启动后台抓取进程
 * - POST /api/admin/scraping/stop      手动停止后台抓取进程
 * - POST /api/admin/scraping/tick      立即触发一次抓取（不改变循环状态）
 * - GET  /api/admin/scraping/status    查询抓取进程状态
 */
fun Route.apiRoutes(
    service: ContentService,
    pool: ProxyPool,
    browser: BrowserFetchService?,
    cfg: AppConfig,
    store: ConfigStore,
    video: VideoAnalysisService,
    scraping: ScrapingProcessService,
    ai: com.trae.info.server.ai.AiProviders,
    decisionStore: com.trae.info.server.store.DecisionStore? = null
) {
    route("/api") {

        get("/categories") {
            call.respond(CategoriesResponse(store.categories(), Taxonomy.platforms))
        }

        route("/subscription") {
            get { call.respond(service.prefs) }
            post {
                val prefs = call.receive<SubscriptionPrefs>()
                service.savePrefs(prefs)
                call.respond(OkResponse(message = "订阅偏好已保存"))
            }
        }

        get("/feed") {
            val categoryId = call.request.queryParameters["category"]
            if (categoryId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少 category 参数"))
                return@get
            }
            call.respond(service.feed(categoryId))
        }

        route("/proxy") {
            get("/status") { call.respond(pool.status) }
            post("/refresh") { call.respond(pool.refresh()) }
        }

        post("/analyze") {
            val request = call.receive<AnalyzeRequest>()
            call.respond(service.analyze(request))
        }

        // ---- 用户信息获取偏好总结（信息流个性化）----
        route("/preference") {
            get {
                val s = store.get()
                val summary = s.preferenceSummary
                val pref = if (summary.isBlank()) null else PreferenceEngine.parse(summary)
                call.respond(
                    PreferenceDto(
                        summary = summary,
                        displayMode = pref?.displayMode ?: "default",
                        keywords = pref?.positiveKeywords ?: emptyList(),
                        negativeKeywords = pref?.negativeKeywords ?: emptyList(),
                        updatedAtEpochMs = s.preferenceUpdatedAtEpochMs
                    )
                )
            }
            post {
                val dto = call.receive<PreferenceDto>()
                val summary = dto.summary.trim()
                store.update {
                    it.copy(
                        preferenceSummary = summary,
                        preferenceUpdatedAtEpochMs = System.currentTimeMillis()
                    )
                }
                // 防抖 60s：若进程空闲，自动触发一次抓取让新偏好尽快生效
                if (summary.isNotBlank()) scraping.schedulePrefTick()
                call.respond(
                    OkResponse(
                        message = if (summary.isBlank()) "已清除信息偏好总结，信息流恢复默认排序"
                        else "信息偏好已保存，信息流将按偏好个性化排序与展示"
                    )
                )
            }
        }

        // ---- 运维观测：进程内运行指标（排障/效率审计用） ----
        get("/debug/metrics") {
            val runtime = Runtime.getRuntime()
            val heapUsed = runtime.totalMemory() - runtime.freeMemory()
            val st = scraping.status.value
            call.respond(
                buildJsonObject {
                    put("heapUsedMB", heapUsed / 1024 / 1024)
                    put("heapMaxMB", runtime.maxMemory() / 1024 / 1024)
                    put("pendingLogins", browser?.pendingLoginCount() ?: -1)
                    put("summaryCacheEntries", scraping.summaryCacheSize())
                    putJsonObject("proxy") {
                        pool.debugInfo().forEach { (k, v) -> put(k, v.toString()) }
                    }
                    putJsonObject("browserBreakers") {
                        com.trae.info.server.crawler.AdapterBreaker.snapshot()
                            .forEach { (k, v) -> put(k, v) }
                    }
                    putJsonObject("feedCache") {
                        service.cacheStats().forEach { (k, v) -> put(k, v) }
                    }
                    putJsonObject("scraping") {
                        put("running", st.running)
                        put("phase", st.phase)
                        put("progressPercent", st.progressPercent)
                        put("completedUnits", st.completedUnits)
                        put("totalUnits", st.totalUnits)
                    }
                    // 决策核心 V2：来源观测与灰度开关状态（方案 §11 可观测性）
                    putJsonObject("decision") {
                        put("coreV2", cfg.decisionCoreV2)
                        put("schedulerV2", cfg.sourceSchedulerV2)
                        put("observedSources", decisionStore?.size() ?: -1)
                        putJsonObject("sourceObservations") {
                            decisionStore?.snapshot()?.forEach { (sourceId, obs) ->
                                putJsonObject(sourceId) {
                                    put("attempts", obs.attempts)
                                    put("successRate", "%.2f".format(obs.successRate))
                                    put("uniqueNew", obs.uniqueNew)
                                    put("avgElapsedMs", "%.0f".format(obs.avgElapsedMs))
                                }
                            }
                        }
                    }
                }
            )
        }

        // ---- 通道 A：云端浏览器登录会话管理 ----
        route("/sessions") {
            get {
                if (browser == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("浏览器自动化不可用（服务器缺少 Chromium）"))
                    return@get
                }
                call.respond(
                    BrowserScrapers.all.values.map { s ->
                        SessionInfo(
                            platform = s.platform.id,
                            name = s.platform.displayName,
                            loginRequired = s.loginRequired,
                            loggedIn = browser.hasSession(s.platform.id)
                        )
                    }
                )
            }
        }

        route("/session/{platform}") {
            post("/login/start") {
                val platformId = call.parameters["platform"].orEmpty()
                val scraper = BrowserScrapers.byId(platformId)
                if (browser == null || scraper == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("浏览器不可用或平台不支持"))
                    return@post
                }
                val result = browser.startLogin(scraper)
                call.respond(
                    LoginScreenshotResponse(
                        pngBase64 = Base64.getEncoder().encodeToString(result.png),
                        state = if (result.qrDetected) "QR_AVAILABLE" else "PENDING"
                    )
                )
            }

            // ---- Agent 智能登录：客户端直填账号密码，云端自动填充表单提交 ----
            post("/agent/login") {
                val platformId = call.parameters["platform"].orEmpty()
                val scraper = BrowserScrapers.byId(platformId)
                if (browser == null || scraper == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("浏览器不可用或平台不支持"))
                    return@post
                }
                val req = call.receive<AgentLoginRequest>()
                if (req.username.isBlank() || req.password.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("账号与密码不能为空"))
                    return@post
                }
                val (png, result) = browser.startAgentLogin(scraper, req.username, req.password)
                call.respond(
                    AgentLoginResponse(
                        state = result.stage.name,
                        message = result.message,
                        pngBase64 = Base64.getEncoder().encodeToString(png),
                        loggedIn = result.stage == AgentLoginEngine.Stage.LOGGED_IN
                    )
                )
            }

            get("/login/screenshot") {
                val platformId = call.parameters["platform"].orEmpty()
                val shot = browser?.loginScreenshot(platformId)
                if (shot == null) {
                    call.respond(HttpStatusCode.NotFound,
                        ErrorResponse("无待完成的登录会话"))
                } else {
                    call.respond(LoginScreenshotResponse(
                        pngBase64 = Base64.getEncoder().encodeToString(shot.first),
                        // 延迟弹出的登录窗（抖音 ~12s+）在后续轮询帧补检 QR，提示条自动浮现
                        state = if (shot.second) "QR_AVAILABLE" else "PENDING"
                    ))
                }
            }

            post("/login/complete") {
                val platformId = call.parameters["platform"].orEmpty()
                val scraper = BrowserScrapers.byId(platformId)
                if (browser == null || scraper == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("浏览器不可用或平台不支持"))
                    return@post
                }
                val force = call.request.queryParameters["force"]?.equals("true", ignoreCase = true) ?: false
                call.respond(LoginCompleteResponse(browser.completeLogin(scraper, force)))
            }

            post("/login/cancel") {
                val platformId = call.parameters["platform"].orEmpty()
                browser?.cancelLogin(platformId)
                call.respond(OkResponse(message = "已取消"))
            }

            // ---- 远程浏览器控制（虚拟键鼠，用于无法扫码的平台手动登录）----
            // 作用于 startLogin 打开并保持打开的页面；无活动会话返回失败提示。
            post("/remote/click") {
                val platformId = call.parameters["platform"].orEmpty()
                if (browser == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("浏览器不可用"))
                    return@post
                }
                val req = call.receive<RemoteClickRequest>()
                val ok = browser.remoteClick(platformId, req.x, req.y)
                call.respond(OkResponse(message = if (ok) "已点击 ${req.x.toInt()},${req.y.toInt()}" else "无活动会话"))
            }

            post("/remote/type") {
                val platformId = call.parameters["platform"].orEmpty()
                if (browser == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("浏览器不可用"))
                    return@post
                }
                val req = call.receive<RemoteTypeRequest>()
                val ok = browser.remoteType(platformId, req.text)
                call.respond(OkResponse(message = if (ok) "已输入 ${req.text.length} 字" else "无活动会话"))
            }

            post("/remote/key") {
                val platformId = call.parameters["platform"].orEmpty()
                if (browser == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("浏览器不可用"))
                    return@post
                }
                val req = call.receive<RemoteKeyRequest>()
                val ok = browser.remoteKey(platformId, req.key)
                call.respond(OkResponse(message = if (ok) "已发送按键 ${req.key}" else "无活动会话"))
            }

            post("/remote/navigate") {
                val platformId = call.parameters["platform"].orEmpty()
                if (browser == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("浏览器不可用"))
                    return@post
                }
                val req = call.receive<RemoteNavigateRequest>()
                val ok = browser.remoteNavigate(platformId, req.url)
                call.respond(OkResponse(message = if (ok) "已导航" else "无活动会话"))
            }

            post("/remote/scroll") {
                val platformId = call.parameters["platform"].orEmpty()
                if (browser == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("浏览器不可用"))
                    return@post
                }
                val req = call.receive<RemoteScrollRequest>()
                val ok = browser.remoteScroll(platformId, req.deltaX, req.deltaY)
                call.respond(OkResponse(message = if (ok) "已滚动" else "无活动会话"))
            }

            get("/remote/url") {
                val platformId = call.parameters["platform"].orEmpty()
                if (browser == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("浏览器不可用"))
                    return@get
                }
                val url = browser.remoteUrl(platformId)
                if (url == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("无活动会话"))
                } else {
                    call.respond(RemoteUrlResponse(url))
                }
            }
        }

        // ---- 通道 B：伴侣脚本上报 ----
        post("/companion/ingest") {
            val token = call.request.headers["X-Companion-Token"]
            if (token.isNullOrBlank() || token != cfg.companionToken) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("令牌无效"))
                return@post
            }
            val ingest = call.receive<CompanionIngest>()
            val added = service.mergeCompanion(ingest)
            call.respond(OkResponse(message = "已入库 $added 条"))
        }

        // ---- 视频内容分析（音频提取→ASR→关键帧→AI总结） ----
        post("/video/analyze") {
            val req = call.receive<VideoAnalyzeRequest>()
            call.respond(video.analyze(req))
        }

        // ================================================================
        // 云端管理（图形化配置界面后端）
        // ================================================================
        route("/admin") {

            // ---- AI 配置 ----
            route("/config/ai") {
                get {
                    val s = store.get()
                    call.respond(AiConfigDto(
                        doubaoKey = s.doubaoKey, doubaoModel = s.doubaoModel, doubaoBaseUrl = s.doubaoBaseUrl,
                        doubaoVisionModel = s.doubaoVisionModel,
                        openaiKey = s.openaiKey, openaiModel = s.openaiModel, openaiBaseUrl = s.openaiBaseUrl,
                        geminiKey = s.geminiKey, geminiModel = s.geminiModel,
                        lasApiKey = s.lasApiKey
                    ))
                }
                post {
                    val dto = call.receive<AiConfigDto>()
                    store.update { it.copy(
                        doubaoKey = dto.doubaoKey, doubaoModel = dto.doubaoModel, doubaoBaseUrl = dto.doubaoBaseUrl,
                        doubaoVisionModel = dto.doubaoVisionModel,
                        openaiKey = dto.openaiKey, openaiModel = dto.openaiModel, openaiBaseUrl = dto.openaiBaseUrl,
                        geminiKey = dto.geminiKey, geminiModel = dto.geminiModel,
                        lasApiKey = dto.lasApiKey
                    ) }
                    call.respond(OkResponse(message = "AI 配置已保存并即时生效"))
                }
            }

            // ---- 爬虫密钥 ----
            route("/config/crawler") {
                get {
                    val s = store.get()
                    call.respond(CrawlerConfigDto(
                        youtubeApiKey = s.youtubeApiKey,
                        xBearerToken = s.xBearerToken,
                        serpApiKey = s.serpApiKey,
                        companionToken = s.companionToken
                    ))
                }
                post {
                    val dto = call.receive<CrawlerConfigDto>()
                    store.update { it.copy(
                        youtubeApiKey = dto.youtubeApiKey,
                        xBearerToken = dto.xBearerToken,
                        serpApiKey = dto.serpApiKey,
                        companionToken = dto.companionToken
                    ) }
                    service.refreshAdapters()
                    call.respond(OkResponse(message = "爬虫密钥已保存，适配器已重建"))
                }
            }

            // ---- 代理配置 ----
            route("/config/proxy") {
                get {
                    val s = store.get()
                    call.respond(ProxyConfigDto(
                        proxySubUrl = s.proxySubUrl,
                        xrayPath = s.xrayPath,
                        refreshIntervalMin = s.refreshIntervalMin
                    ))
                }
                post {
                    val dto = call.receive<ProxyConfigDto>()
                    store.update { it.copy(
                        proxySubUrl = dto.proxySubUrl,
                        xrayPath = dto.xrayPath,
                        refreshIntervalMin = dto.refreshIntervalMin
                    ) }
                    call.respond(OkResponse(message = "代理配置已保存（代理池刷新或重启后生效）"))
                }
            }

            // ---- 动态信息分类 ----
            route("/categories") {
                get {
                    call.respond(CategoriesManagementResponse(
                        defaults = Taxonomy.categories,
                        customs = store.customCategories()
                    ))
                }
                post {
                    val req = call.receive<CategoryCreateRequest>()
                    if (req.name.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("分类名称不能为空"))
                        return@post
                    }
                    val sources = req.platformIds.mapNotNull { SourcePlatform.byId(it) }
                        .ifEmpty { Taxonomy.platforms }
                    val domain = runCatching { Domain.valueOf(req.domain) }.getOrDefault(Domain.PRACTICAL)
                    val cat = InfoCategory(
                        id = "custom-${System.currentTimeMillis()}",
                        name = req.name,
                        domain = domain,
                        group = req.group.ifBlank { "自定义" },
                        description = req.description,
                        keywords = req.keywords.ifEmpty { listOf(req.name) },
                        sources = sources
                    )
                    store.addCustomCategory(cat)
                    call.respond(OkResponse(message = "分类「${cat.name}」已添加"))
                }
                delete("/{id}") {
                    val id = call.parameters["id"].orEmpty()
                    store.removeCustomCategory(id)
                    call.respond(OkResponse(message = "已删除"))
                }
            }

            // ---- 收集目的（AI 自定搜索输入）----
            route("/goals") {
                get { call.respond(store.goals()) }
                post {
                    val goals = call.receive<List<String>>()
                    store.updateGoals(goals)
                    call.respond(OkResponse(message = "收集目的已保存（${goals.size} 条）"))
                }
            }

            // ---- AI 驱动自定对象搜索 ----
            post("/ai-search") {
                val req = call.receive<AiSearchRequest>()
                if (req.goal.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("收集目的不能为空"))
                    return@post
                }
                runCatching { service.aiSearch(req.goal) }
                    .onSuccess { call.respond(it) }
                    .onFailure {
                        call.respond(HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("AI 搜索失败：${it.message}（请在云端管理界面配置 AI API Key）"))
                    }
            }

            // ---- 视觉模型健康状态 ----
            get("/vision-models") {
                call.respond(ai.visionRouter.healthSnapshot())
            }

            // ---- 后台抓取进程控制 ----
            route("/scraping") {
                get("/status") {
                    call.respond(scraping.status.value.toDto())
                }
                post("/start") {
                    val req = runCatching { call.receive<ScrapingControlRequest>() }.getOrNull()
                    call.respond(
                        scraping.start(
                            intervalSec = req?.intervalSec?.takeIf { it > 0 }
                                ?: ScrapingProcessService.DEFAULT_INTERVAL_SEC,
                            expectedMinutes = req?.expectedMinutes ?: 0,
                            summaryMode = req?.summaryMode ?: "default",
                            summarizeAll = req?.summarizeAll ?: false
                        ).toDto()
                    )
                }
                post("/stop") {
                    call.respond(scraping.stop().toDto())
                }
                post("/tick") {
                    val req = runCatching { call.receive<ScrapingControlRequest>() }.getOrNull()
                    call.respond(
                        scraping.tickOnce(
                            expectedMinutes = req?.expectedMinutes ?: 0,
                            summaryMode = req?.summaryMode ?: "default",
                            summarizeAll = req?.summarizeAll ?: false
                        ).toDto()
                    )
                }
            }
        }
    }
}

/** 服务端内部状态 -> 对外 DTO。 */
private fun com.trae.info.server.service.ScrapingStatus.toDto(): ScrapingStatusDto =
    ScrapingStatusDto(
        running = running,
        intervalSec = intervalSec,
        startedAtEpochMs = startedAtEpochMs,
        stoppedAtEpochMs = stoppedAtEpochMs,
        lastRunStartedAtEpochMs = lastRunStartedAtEpochMs,
        lastRunFinishedAtEpochMs = lastRunFinishedAtEpochMs,
        nextAtEpochMs = nextAtEpochMs,
        lastItemsCount = lastItemsCount,
        lastFailedCategories = lastFailedCategories,
        totalRuns = totalRuns,
        lastError = lastError,
        message = message,
        phase = phase,
        progressPercent = progressPercent,
        completedUnits = completedUnits,
        totalUnits = totalUnits,
        fetchedItems = fetchedItems,
        targetItems = targetItems,
        summarizedItems = summarizedItems,
        expectedMinutes = expectedMinutes,
        currentTask = currentTask,
        summaryMode = summaryMode,
        summarizeAll = summarizeAll
    )
