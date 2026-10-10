package com.trae.info.server.proxy

import com.trae.info.server.config.AppConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Xray-core 进程编排：
 * 为每个节点生成一个独立的本地 SOCKS5 入站（127.0.0.1:basePort+i），
 * 入站与出站一一绑定路由。代理池随后对各端口并行测速，
 * 选取延迟最低节点的端口作为活跃代理，切换节点无需重启进程。
 */
class XrayManager(private val cfg: AppConfig) {

    private val log = LoggerFactory.getLogger(XrayManager::class.java)
    private var process: Process? = null

    fun isAvailable(): Boolean = try {
        val p = ProcessBuilder(cfg.xrayPath, "version")
            .redirectErrorStream(true).start()
        val finished = p.waitFor(5, TimeUnit.SECONDS)
        finished && p.exitValue() == 0
    } catch (e: Exception) {
        log.warn("未找到 Xray 内核（{}）：{}", cfg.xrayPath, e.message)
        false
    }

    /** 启动 xray；返回 socksPort -> 节点 映射。启动失败抛异常。 */
    fun start(nodes: List<ProxyNode>): Map<Int, ProxyNode> {
        stop()
        val portMap = nodes.mapIndexed { i, n -> cfg.socksBasePort + i to n }.toMap()

        val dir = File(cfg.dataDir, "proxy").apply { mkdirs() }
        val configFile = File(dir, "xray-config.json")
        configFile.writeText(buildConfig(portMap))

        // 日志超 5MB 截断，防止无限增长占满磁盘
        val logFile = File(dir, "xray.log")
        if (logFile.exists() && logFile.length() > 5L * 1024 * 1024) {
            runCatching { logFile.writeText("") }
        }

        val builder = ProcessBuilder(cfg.xrayPath, "run", "-c", configFile.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        process = builder.start()
        log.info("Xray 已启动，共 {} 个节点，SOCKS 端口 {}-{}",
            nodes.size, cfg.socksBasePort, cfg.socksBasePort + nodes.size - 1)
        return portMap
    }

    /** 停止 xray：先优雅销毁，3 秒未退出则强杀，确保端口释放。 */
    fun stop() {
        val p = process ?: return
        process = null
        runCatching {
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                p.waitFor(2, TimeUnit.SECONDS)
            }
        }.onFailure { log.warn("停止 xray 进程异常：{}", it.message) }
    }

    /** xray 进程是否存活。 */
    fun isRunning(): Boolean = process?.isAlive == true

    private fun buildConfig(portMap: Map<Int, ProxyNode>): String = buildJsonObject {
        putJsonObject("log") { put("loglevel", "warning") }

        putJsonArray("inbounds") {
            portMap.keys.sorted().forEach { port ->
                add(buildJsonObject {
                    put("tag", "in-$port")
                    put("listen", "127.0.0.1")
                    put("port", port)
                    put("protocol", "socks")
                    putJsonObject("settings") { put("udp", true) }
                    putJsonObject("sniffing") {
                        put("enabled", true)
                        putJsonArray("destOverride") { add("http"); add("tls") }
                    }
                })
            }
        }

        putJsonArray("outbounds") {
            portMap.toSortedMap().forEach { (port, node) ->
                add(buildJsonObject {
                    put("tag", "out-$port")
                    put("protocol", node.protocol)
                    putJsonObject("settings") { buildOutboundSettings(node) }
                    putJsonObject("streamSettings") { buildStreamSettings(node) }
                })
            }
            // 兜底直连出口
            add(buildJsonObject {
                put("tag", "direct")
                put("protocol", "freedom")
            })
        }

        putJsonObject("routing") {
            putJsonArray("rules") {
                portMap.keys.sorted().forEach { port ->
                    add(buildJsonObject {
                        put("type", "field")
                        putJsonArray("inboundTag") { add("in-$port") }
                        put("outboundTag", "out-$port")
                    })
                }
            }
        }
    }.toString()

    private fun JsonObjectBuilderScope.buildOutboundSettings(node: ProxyNode) {
        when (node.protocol) {
            "vmess" -> putJsonArray("vnext") {
                add(buildJsonObject {
                    put("address", node.host)
                    put("port", node.port)
                    putJsonArray("users") {
                        add(buildJsonObject {
                            put("id", node.uuidOrPassword)
                            put("alterId", 0)
                            put("security", "auto")
                        })
                    }
                })
            }
            "vless" -> putJsonArray("vnext") {
                add(buildJsonObject {
                    put("address", node.host)
                    put("port", node.port)
                    putJsonArray("users") {
                        add(buildJsonObject {
                            put("id", node.uuidOrPassword)
                            put("encryption", "none")
                            node.flow?.let { put("flow", it) }
                        })
                    }
                })
            }
            "trojan" -> putJsonArray("servers") {
                add(buildJsonObject {
                    put("address", node.host)
                    put("port", node.port)
                    put("password", node.uuidOrPassword)
                })
            }
            "ss" -> putJsonArray("servers") {
                add(buildJsonObject {
                    put("address", node.host)
                    put("port", node.port)
                    put("method", node.method ?: "aes-256-gcm")
                    put("password", node.uuidOrPassword)
                })
            }
        }
    }

    private fun JsonObjectBuilderScope.buildStreamSettings(node: ProxyNode) {
        put("network", node.network)
        when {
            node.reality -> {
                put("security", "reality")
                putJsonObject("realitySettings") {
                    put("serverName", node.sni ?: node.host)
                    put("fingerprint", node.fingerprint?.takeIf { it.isNotBlank() } ?: "chrome")
                    node.realityPublicKey?.let { put("publicKey", it) }
                    node.realityShortId?.let { put("shortId", it) }
                    put("spiderX", node.spiderX ?: "")
                }
            }
            node.tls -> {
                put("security", "tls")
                putJsonObject("tlsSettings") {
                    put("serverName", node.sni ?: node.host)
                    put("allowInsecure", false)
                    node.fingerprint?.takeIf { it.isNotBlank() }?.let { put("fingerprint", it) }
                }
            }
            else -> put("security", "none")
        }
        if (node.network == "ws") {
            putJsonObject("wsSettings") {
                put("path", node.wsPath ?: "/")
                putJsonObject("headers") { put("Host", node.wsHost ?: node.host) }
            }
        }
        if (node.network == "grpc") {
            putJsonObject("grpcSettings") { put("serviceName", node.grpcServiceName ?: "") }
        }
    }
}

/** 类型别名，让扩展接收者在文件内可读 */
private typealias JsonObjectBuilderScope = kotlinx.serialization.json.JsonObjectBuilder
