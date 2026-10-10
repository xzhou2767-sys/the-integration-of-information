package com.trae.info.server.proxy

import com.trae.info.core.api.ProxyStatusResponse
import com.trae.info.server.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 云端代理池：
 * 1. 定时/手动拉取 3xui 订阅 -> 2. 解析节点 -> 3. 启动 xray（每节点一个本地 SOCKS 端口）
 * -> 4. 并行 TCP/HTTP 测速 -> 5. 自动选择延迟最低节点 -> 6. 运行时活跃节点故障自动切换 top-3。
 *
 * 所有海外平台外呼统一经 [clientFor] 取 SOCKS 代理客户端；国内平台直连。
 * 客户端应用不参与任何代理配置。
 */
class ProxyPool(private val cfg: AppConfig) {

    private val log = LoggerFactory.getLogger(ProxyPool::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val refreshLock = Mutex()

    /** 单例 xray 管理器：复用同一实例，stop() 才能真正杀掉旧进程释放端口。 */
    private val xray = XrayManager(cfg)

    @Volatile
    private var statusInternal: ProxyStatusResponse =
        ProxyStatusResponse(state = "BOOTING", xrayAvailable = false, message = "代理模块初始化中")
    val status: ProxyStatusResponse get() = statusInternal

    private val direct: HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
        install(HttpRequestRetry) {
            maxRetries = 2
            retryOnExceptionIf { _, cause ->
                // 仅对网络层异常重试（连接重置/超时），4xx 业务错误不重试
                cause is java.io.IOException
            }
            exponentialDelay()
        }
    }

    @Volatile
    private var proxied: HttpClient? = null

    @Volatile
    internal var activeSocksPort: Int? = null

    /** 测速通过的端口（按延迟升序），供运行时故障切换。 */
    @Volatile
    private var readyPorts: List<Int> = emptyList()

    /** 海外平台走自动选优的代理；代理未就绪时退化为直连（由调用方容错）。 */
    fun clientFor(requiresProxy: Boolean): HttpClient =
        if (requiresProxy) proxied ?: direct else direct

    fun start() {
        scope.launch {
            var tick = 0L
            while (isActive) {
                runCatching {
                    if (tick % 6L == 0L) {
                        refresh()
                    } else {
                        healthCheckAndFailover()
                    }
                }.onFailure { log.warn("代理调度异常：{}", it.message) }
                tick++
                delay(5 * 60_000L) // 每 5 分钟轻探活；每 30 分钟全量刷新
            }
        }
    }

    /** 活跃节点轻探活；失败则切换到下一可用端口，并触发一次全量刷新。 */
    private suspend fun healthCheckAndFailover() {
        val active = activeSocksPort ?: return
        if (probeLatency(active) > 0) return
        log.warn("活跃代理节点 {} 探活失败，尝试切换备用节点", active)
        val backup = readyPorts.firstOrNull { it != active && probeLatency(it) > 0 }
        if (backup != null) {
            rebuildProxiedClient(backup)
            activeSocksPort = backup
            log.info("已切换到备用代理端口 {}", backup)
        } else {
            log.warn("无可用备用节点，触发全量刷新")
        }
        runCatching { refresh() }
    }

    suspend fun refresh(): ProxyStatusResponse = refreshLock.withLock {
        statusInternal = statusInternal.copy(
            state = "REFRESHING",
            message = "正在拉取 3xui 订阅、启动 xray 并测速选优…"
        )
        try {
            if (cfg.proxySubUrl.isBlank()) {
                statusInternal = statusInternal.copy(
                    state = "NO_CONFIG", message = "未配置 3xui 订阅 URL（TRAE_PROXY_SUB_URL）"
                )
                return@withLock statusInternal
            }

            val body = direct.get(cfg.proxySubUrl).bodyAsText()
            val nodes = SubscriptionParser.decode(body)
            if (nodes.isEmpty()) {
                statusInternal = statusInternal.copy(
                    state = "EMPTY", nodeCount = 0, message = "订阅解析成功，但未发现可用节点"
                )
                return@withLock statusInternal
            }
            log.info("订阅解析完成，共 {} 个节点", nodes.size)

            if (!xray.isAvailable()) {
                statusInternal = statusInternal.copy(
                    state = "NO_XRAY",
                    xrayAvailable = false,
                    nodeCount = nodes.size,
                    message = "服务器未安装 Xray-core（当前路径：${cfg.xrayPath}）。" +
                            "安装后云端将自动启用代理穿透，期间海外平台抓取不可用。"
                )
                return@withLock statusInternal
            }

            val portMap = xray.start(nodes)
            waitForPortReady(portMap.keys.firstOrNull() ?: cfg.socksBasePort)

            data class Probe(val port: Int, val node: ProxyNode, val latencyMs: Long)

            val probes = coroutineScope {
                portMap.map { (port, node) ->
                    async { Probe(port, node, probeLatency(port)) }
                }.awaitAll()
            }
            val ok = probes.filter { it.latencyMs > 0 }.sortedBy { it.latencyMs }
            readyPorts = ok.map { it.port }
            val best = ok.firstOrNull()

            statusInternal = if (best == null) {
                activeSocksPort = null
                statusInternal.copy(
                    state = "ERROR",
                    xrayAvailable = true,
                    nodeCount = nodes.size,
                    message = "全部 ${nodes.size} 个节点测速失败，请检查节点有效性或服务器网络"
                )
            } else {
                rebuildProxiedClient(best.port)
                activeSocksPort = best.port
                ProxyStatusResponse(
                    state = "READY",
                    xrayAvailable = true,
                    nodeCount = nodes.size,
                    activeNode = best.node.remark,
                    activeProtocol = best.node.protocol,
                    activeLatencyMs = best.latencyMs,
                    lastRefreshEpochMs = System.currentTimeMillis(),
                    message = "已自动选择延迟最低节点（${best.latencyMs} ms），海外流量经云端代理穿透"
                )
            }
            log.info(statusInternal.message)
            statusInternal
        } catch (e: Exception) {
            log.warn("代理刷新失败", e)
            statusInternal = statusInternal.copy(
                state = "ERROR", message = "代理刷新失败：${e.message}"
            )
            statusInternal
        }
    }

    /** 轮询等待首个 SOCKS 端口可连（上限 8 秒），替代固定 sleep。 */
    private suspend fun waitForPortReady(port: Int) {
        repeat(8) {
            if (probeTcpConnect(port)) return
            delay(1_000)
        }
    }

    private fun probeTcpConnect(port: Int): Boolean = runCatching {
        java.net.Socket().use {
            it.connect(InetSocketAddress("127.0.0.1", port), 800)
            it.isConnected
        }
    }.getOrDefault(false)

    /** 通过指定本地 SOCKS 端口探测外网连通性，返回延迟毫秒；失败返回 -1。 */
    private suspend fun probeLatency(port: Int): Long {
        val client = HttpClient(OkHttp) {
            engine {
                proxy = ProxyBuilder.socks("127.0.0.1", port)
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 8_000
            }
            followRedirects = false
        }
        return try {
            val start = System.nanoTime()
            val resp = client.get(cfg.probeUrl)
            if (resp.status.value in 200..399) (System.nanoTime() - start) / 1_000_000L else -1L
        } catch (e: Exception) {
            -1L
        } finally {
            client.close()
        }
    }

    private fun rebuildProxiedClient(port: Int) {
        proxied?.close()
        proxied = HttpClient(OkHttp) {
            engine {
                proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                connectTimeoutMillis = 8_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
            install(HttpRequestRetry) {
                maxRetries = 2
                retryOnExceptionIf { _, cause -> cause is java.io.IOException }
                exponentialDelay()
            }
        }
    }

    /** 供 metrics/运维查询：xray 进程是否存活、可用节点数。 */
    fun debugInfo(): Map<String, Any> = mapOf(
        "xrayRunning" to xray.isRunning(),
        "readyNodeCount" to readyPorts.size,
        "activePort" to (activeSocksPort ?: -1)
    )

    /** 进程退出时清理 xray 子进程。 */
    fun shutdown() {
        runCatching { xray.stop() }
        runCatching { proxied?.close() }
        runCatching { direct.close() }
    }
}
