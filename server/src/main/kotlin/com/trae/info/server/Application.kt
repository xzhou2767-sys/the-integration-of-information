package com.trae.info.server

import com.trae.info.core.api.ErrorResponse
import com.trae.info.server.ai.AiProviders
import com.trae.info.server.browser.BrowserFetchService
import com.trae.info.server.config.AppConfig
import com.trae.info.server.config.ConfigStore
import com.trae.info.server.proxy.ProxyPool
import com.trae.info.server.routes.apiRoutes
import com.trae.info.server.service.ContentService
import com.trae.info.server.service.ScrapingProcessService
import com.trae.info.server.service.SummaryStore
import com.trae.info.server.service.TranslationService
import com.trae.info.server.service.VideoAnalysisService
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

fun main() {
    embeddedServer(Netty, host = "0.0.0.0", port = 8765) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    val store = ConfigStore(java.io.File("data"))
    val cfg = AppConfig(environment.config, store)

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    install(CallLogging) {
        level = Level.INFO
    }
    install(StatusPages) {
        val logger = LoggerFactory.getLogger("ApiErrors")
        exception<Throwable> { call, cause ->
            logger.error("请求处理失败", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "internal error")
            )
        }
    }

    // 云端代理池：自动拉取 3xui 订阅 -> 解析 -> xray -> 测速 -> 选优
    val pool = ProxyPool(cfg)
    val ai = AiProviders(cfg, pool)
    // 通道 A：云端浏览器自动化（缺 Chromium 时为 null，相关功能优雅降级）
    val browser = runCatching { BrowserFetchService(cfg, pool) }.getOrNull()
    // 外文条目概要翻译（译题缓存 translations.json，异步补翻不阻塞 feed）
    val translationService = TranslationService(cfg, ai)
    val contentService = ContentService.create(cfg, ai, pool, browser, store, translationService)
    val downloadClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 300_000
        }
    }
    val videoService = VideoAnalysisService(store, ai, downloadClient)
    val summaryStore = SummaryStore(cfg.dataDir)
    // 决策核心 V2：来源观测持久存储（影子收集，decision-store.json）
    val decisionStore = com.trae.info.server.store.DecisionStore(cfg.dataDir)
    val scrapingService = ScrapingProcessService(contentService, store, ai, summaryStore, decisionStore)

    routing {
        apiRoutes(contentService, pool, browser, cfg, store, videoService, scrapingService, ai, decisionStore)
    }

    pool.start()

    // 进程退出钩子：停 xray、关常驻浏览器，避免子进程泄漏/端口占用
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { browser?.shutdown() }
        runCatching { pool.shutdown() }
    })

    // 启动后异步探测 Chromium 可用性，写入启动日志
    val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    probeScope.launch {
        val ok = browser?.chromiumAvailable() ?: false
        LoggerFactory.getLogger("Server").info("Chromium 浏览器自动化：{}", if (ok) "可用" else "不可用（扫码/浏览器抓取降级）")
    }

    val bootLogger = LoggerFactory.getLogger("Server")
    bootLogger.info(
        "Trae 云端服务已启动：http://0.0.0.0:8765 ｜ 3xui 订阅：{} ｜ AI Key 配置：豆包={} GPT={} Gemini={}",
        if (cfg.proxySubUrl.isNotBlank()) "已配置" else "未配置",
        cfg.doubaoKey.isNotBlank(),
        cfg.openaiKey.isNotBlank(),
        cfg.geminiKey.isNotBlank()
    )
}
