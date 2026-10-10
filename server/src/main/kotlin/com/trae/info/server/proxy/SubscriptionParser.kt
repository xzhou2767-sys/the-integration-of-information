package com.trae.info.server.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.util.Base64

/**
 * 3xui 订阅解析器。
 * 订阅响应为 Base64 编码的节点链接列表，逐行解析：
 * vmess://（Base64 JSON）、vless://、trojan://、ss://。
 */
object SubscriptionParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun decode(subscriptionBody: String): List<ProxyNode> {
        val raw = subscriptionBody.filter { !it.isWhitespace() }
        val decoded = b64Decode(raw)?.let { String(it, Charsets.UTF_8) } ?: subscriptionBody
        return decoded.lines()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .mapNotNull(::parseLink)
    }

    private fun parseLink(link: String): ProxyNode? = when {
        link.startsWith("vmess://") -> parseVmess(link.removePrefix("vmess://"))
        link.startsWith("vless://") -> parseUserPassLink("vless", link)
        link.startsWith("trojan://") -> parseUserPassLink("trojan", link)
        link.startsWith("ss://") -> parseSs(link.removePrefix("ss://"))
        else -> null
    }

    private fun parseVmess(payload: String): ProxyNode? = runCatching {
        val obj = json.parseToJsonElement(
            String(b64Decode(payload) ?: return null, Charsets.UTF_8)
        ).jsonObject
        ProxyNode(
            protocol = "vmess",
            host = obj.str("add") ?: return null,
            port = obj.str("port")?.toIntOrNull() ?: return null,
            uuidOrPassword = obj.str("id") ?: return null,
            remark = obj.str("ps") ?: "vmess-node",
            network = obj.str("net") ?: "tcp",
            tls = obj.str("tls") == "tls",
            wsPath = obj.str("path"),
            wsHost = obj.str("host")
        )
    }.getOrNull()

    private fun parseUserPassLink(protocol: String, link: String): ProxyNode? = runCatching {
        val body = link.substringAfter("://")
        val main = body.substringBefore("#")
        val remark = body.substringAfter("#", "").takeIf { body.contains("#") }.orEmpty()
        val query = main.substringAfter("?", "").takeIf { main.contains("?") }.orEmpty()
        val hostPart = main.substringBefore("?")
        val userInfo = hostPart.substringBeforeLast("@")
        val hostPort = hostPart.substringAfterLast("@")
        val host = hostPort.substringBeforeLast(":")
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: return null

        val params: Map<String, String> = query.split("&")
            .filter { it.contains("=") }
            .associate { it.substringBefore("=") to urlDecode(it.substringAfter("=")) }

        ProxyNode(
            protocol = protocol,
            host = host,
            port = port,
            uuidOrPassword = urlDecode(userInfo),
            remark = urlDecode(remark).ifBlank { "$protocol-$host" },
            network = params["type"] ?: "tcp",
            tls = params["security"] in setOf("tls", "reality"),
            wsPath = params["path"],
            wsHost = params["host"],
            grpcServiceName = params["serviceName"],
            sni = params["sni"],
            flow = params["flow"],
            reality = params["security"] == "reality",
            fingerprint = params["fp"],
            realityPublicKey = params["pbk"],
            realityShortId = params["sid"],
            spiderX = params["spx"]
        )
    }.getOrNull()

    private fun parseSs(body: String): ProxyNode? = runCatching {
        val main = body.substringBefore("#")
        val remark = body.substringAfter("#", "").takeIf { body.contains("#") }.orEmpty()

        val (credRaw, hostPort) = if (main.contains("@")) {
            main.substringBeforeLast("@") to main.substringAfterLast("@")
        } else {
            val decoded = String(b64Decode(main) ?: return null, Charsets.UTF_8)
            decoded.substringBeforeLast("@") to decoded.substringAfterLast("@")
        }
        val cred = b64Decode(credRaw)?.let { String(it, Charsets.UTF_8) } ?: urlDecode(credRaw)
        val method = cred.substringBefore(":")
        val password = cred.substringAfter(":")
        val host = hostPort.substringBeforeLast(":")
        val port = hostPort.substringAfterLast(":").substringBefore("?").toIntOrNull() ?: return null

        ProxyNode(
            protocol = "ss",
            host = host,
            port = port,
            uuidOrPassword = password,
            remark = urlDecode(remark).ifBlank { "ss-$host" },
            method = method
        )
    }.getOrNull()

    private fun b64Decode(s: String): ByteArray? {
        val normalized = s.filter { !it.isWhitespace() }.let {
            it.padEnd((4 - it.length % 4) % 4, '=')
        }
        return runCatching { Base64.getUrlDecoder().decode(normalized) }.recoverCatching {
            Base64.getDecoder().decode(normalized)
        }.getOrNull()
    }

    private fun urlDecode(s: String): String = URLDecoder.decode(s, "UTF-8")

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
}
