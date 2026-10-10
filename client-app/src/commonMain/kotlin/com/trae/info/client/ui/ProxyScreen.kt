package com.trae.info.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trae.info.client.api.CloudApi
import com.trae.info.client.platform.Platform
import com.trae.info.client.theme.HmclIndigo
import com.trae.info.core.api.ProxyStatusResponse
import kotlinx.coroutines.launch

/**
 * 云端代理状态（只读）。客户端自身不配置任何代理：
 * 3xui 订阅拉取、xray 启动、测速选优全部在云端自动完成。
 */
@Composable
fun ProxyScreen(cloud: CloudApi) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ProxyStatusResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            runCatching { cloud.proxyStatus() }
                .onSuccess { status = it; error = null }
                .onFailure { error = "无法获取状态：${it.message}" }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("云端代理穿透", style = MaterialTheme.typography.headlineSmall)
        Text(
            "代理在云端服务器运行，客户端无需任何代理配置。" +
                    "Reddit / YouTube / X / Quora 等海外平台流量由云端自动经 3xui 节点穿透。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("运行状态", style = MaterialTheme.typography.titleMedium, color = HmclIndigo)
                val s = status
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                } else if (s == null) {
                    Text("正在获取…")
                } else {
                    Text("状态：${stateLabel(s.state)}")
                    Text("Xray 内核：${if (s.xrayAvailable) "可用" else "不可用（云端安装 Xray-core 后自动启用）"}")
                    Text("订阅节点数：${s.nodeCount}")
                    s.activeNode?.let {
                        Text("自动选中节点：$it")
                        Text("协议：${s.activeProtocol}")
                        Text("延迟：${s.activeLatencyMs} ms")
                    }
                    s.lastRefreshEpochMs?.let {
                        Text("上次测速选优：${Platform.formatTime(it)}")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(s.message, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Button(onClick = {
            scope.launch {
                runCatching { cloud.refreshProxy() }
                    .onSuccess { status = it }
                    .onFailure { error = "刷新失败：${it.message}" }
            }
        }) { Text("立即拉取订阅并测速选优") }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("自动化流程", style = MaterialTheme.typography.titleMedium, color = HmclIndigo)
                Text("1. 云端定时拉取 3xui 订阅地址（vmess/vless/trojan/ss）")
                Text("2. 为每个节点启动独立本地 SOCKS 端口并并行测速")
                Text("3. 自动选择延迟最低节点，失败节点下轮自动剔除")
                Text("4. 海外抓取/AI 请求经选中节点穿透，国内平台直连")
            }
        }
    }
}
