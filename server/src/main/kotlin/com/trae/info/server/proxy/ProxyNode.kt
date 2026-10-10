package com.trae.info.server.proxy

/**
 * 从 3xui 订阅中解析出的单个代理节点。
 */
data class ProxyNode(
    val protocol: String,          // vmess / vless / trojan / ss
    val host: String,
    val port: Int,
    val uuidOrPassword: String,
    val remark: String,
    val network: String = "tcp",   // tcp / ws / grpc
    val tls: Boolean = false,
    val wsPath: String? = null,
    val wsHost: String? = null,
    val grpcServiceName: String? = null,
    val sni: String? = null,
    val flow: String? = null,
    val method: String? = null,    // ss 加密方式
    val reality: Boolean = false,          // VLESS + REALITY
    val fingerprint: String? = null,       // uTLS 指纹（fp，如 chrome）
    val realityPublicKey: String? = null,  // pbk
    val realityShortId: String? = null,    // sid
    val spiderX: String? = null            // spx
) {
    val displayName: String get() = "$remark（$protocol @ $host:$port）"
}
