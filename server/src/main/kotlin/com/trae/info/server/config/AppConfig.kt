package com.trae.info.server.config

import io.ktor.server.config.ApplicationConfig
import java.io.File

/**
 * 云端服务配置。运行时可变项（AI Key、爬虫密钥、代理参数）优先读取 [ConfigStore]，
 * 为空时降级到环境变量 / hocon。生产环境敏感项建议通过环境变量注入。
 */
class AppConfig(
    private val config: ApplicationConfig,
    private val store: ConfigStore
) {
    private fun str(path: String, env: String, default: String = ""): String =
        config.propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() }
            ?: System.getenv(env)?.takeIf { it.isNotBlank() }
            ?: default

    private fun long(path: String, env: String, default: Long): Long =
        config.propertyOrNull(path)?.getString()?.toLongOrNull()
            ?: System.getenv(env)?.toLongOrNull()
            ?: default

    /** 优先 ConfigStore，空则环境变量 / hocon。 */
    private fun resolve(storeValue: String, path: String, env: String, default: String = ""): String =
        storeValue.takeIf { it.isNotBlank() }
            ?: str(path, env, default)

    // ---- 3xui 代理 ----
    val proxySubUrl: String get() = resolve(store.get().proxySubUrl, "trae.proxy.subscription-url", "TRAE_PROXY_SUB_URL")
    val xrayPath: String get() = resolve(store.get().xrayPath, "trae.proxy.xray-path", "XRAY_PATH", "xray")
    val refreshIntervalMin: Long get() =
        store.get().refreshIntervalMin.takeIf { it > 0 }
            ?: long("trae.proxy.refresh-interval-min", "PROXY_REFRESH_MIN", 30L)
    val probeUrl: String = str("trae.proxy.probe-url", "PROBE_URL", "https://www.gstatic.com/generate_204")
    val socksBasePort: Int = config.propertyOrNull("trae.proxy.socks-base-port")?.getString()?.toIntOrNull()
        ?: System.getenv("SOCKS_BASE_PORT")?.toIntOrNull() ?: 10810

    // ---- AI 平台 ----
    val doubaoKey: String get() = resolve(store.get().doubaoKey, "trae.ai.doubao.api-key", "DOUBAO_API_KEY")
    val doubaoModel: String get() = resolve(store.get().doubaoModel, "trae.ai.doubao.model", "DOUBAO_MODEL", "doubao-pro-32k")
    val doubaoBaseUrl: String get() = resolve(store.get().doubaoBaseUrl, "trae.ai.doubao.base-url", "DOUBAO_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3")

    val openaiKey: String get() = resolve(store.get().openaiKey, "trae.ai.openai.api-key", "OPENAI_API_KEY")
    val openaiModel: String get() = resolve(store.get().openaiModel, "trae.ai.openai.model", "OPENAI_MODEL", "gpt-4o-mini")
    val openaiBaseUrl: String get() = resolve(store.get().openaiBaseUrl, "trae.ai.openai.base-url", "OPENAI_BASE_URL", "https://api.openai.com/v1")

    val geminiKey: String get() = resolve(store.get().geminiKey, "trae.ai.gemini.api-key", "GEMINI_API_KEY")
    val geminiModel: String get() = resolve(store.get().geminiModel, "trae.ai.gemini.model", "GEMINI_MODEL", "gemini-2.5-flash")

    // ---- LAS 数据湖（视觉理解备用通道）----
    val lasApiKey: String get() = resolve(store.get().lasApiKey, "trae.ai.las.api-key", "LAS_API_KEY")

    // ---- 抓取平台密钥 ----
    val youtubeApiKey: String get() = resolve(store.get().youtubeApiKey, "trae.crawler.youtube.api-key", "YOUTUBE_API_KEY")
    val xBearerToken: String get() = resolve(store.get().xBearerToken, "trae.crawler.x.bearer-token", "X_BEARER_TOKEN")
    val serpApiKey: String get() = resolve(store.get().serpApiKey, "trae.crawler.serpapi.api-key", "SERPAPI_KEY")
    val companionToken: String get() = resolve(store.get().companionToken, "trae.crawler.companion.token", "COMPANION_TOKEN", "trae-companion")

    // ---- 决策核心 V2 灰度开关（方案 §14：默认关闭，逐阶段开启）----
    /** decisionCoreV2：feed 路径切换到新决策核心（统一准入 Decision + 去重 + 偏好排序评分解释）。 */
    val decisionCoreV2: Boolean get() =
        store.get().decisionCoreV2 ||
            (config.propertyOrNull("trae.decision.core-v2")?.getString()?.toBoolean()
                ?: System.getenv("TRAE_DECISION_CORE_V2")?.toBoolean() ?: false)
    /** sourceSchedulerV2：抓取规划接入收益调度（基于来源观测的 expectedUseful/cost 优先级）。 */
    val sourceSchedulerV2: Boolean get() =
        store.get().sourceSchedulerV2 ||
            (config.propertyOrNull("trae.decision.scheduler-v2")?.getString()?.toBoolean()
                ?: System.getenv("TRAE_SCHEDULER_V2")?.toBoolean() ?: false)

    val dataDir: File = File("data").apply { mkdirs() }
}
