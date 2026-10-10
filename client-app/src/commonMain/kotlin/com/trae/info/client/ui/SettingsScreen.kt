package com.trae.info.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trae.info.client.api.CloudApi
import com.trae.info.client.platform.Platform
import com.trae.info.client.platform.PlatformType
import com.trae.info.client.theme.HmclIndigo
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(cloud: CloudApi, onServerChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(Platform.getServerUrl()) }
    var result by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("云端服务地址", style = MaterialTheme.typography.titleMedium, color = HmclIndigo)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("http://服务器IP:8765") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    if (Platform.type == PlatformType.ANDROID)
                        "安卓模拟器默认 http://10.0.2.2:8765；真机请填写云端服务器局域网/公网地址"
                    else
                        "桌面端默认 http://127.0.0.1:8765（本机运行云端服务时）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = {
                    val trimmed = url.trim()
                    Platform.setServerUrl(trimmed)
                    onServerChanged()
                    scope.launch {
                        runCatching { CloudApi(trimmed).categories() }
                            .onSuccess {
                                ok = true
                                result = "连接成功：云端提供 ${it.categories.size} 个细分类目、${it.platforms.size} 个信息平台"
                            }
                            .onFailure {
                                ok = false
                                result = "连接失败：${it.message}"
                            }
                    }
                }) { Text("保存并测试连接") }
                if (result.isNotBlank()) {
                    Text(result, color = if (ok) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("说明", style = MaterialTheme.typography.titleMedium, color = HmclIndigo)
                Text("· AI API Key 与 3xui 订阅地址只保存在云端，客户端不接触任何密钥")
                Text("· 客户端不做代理配置，海外数据获取由云端统一穿透")
                Text("· 平台：${if (Platform.type == PlatformType.ANDROID) "Android（ARM64/x86_64）" else "Windows / Linux / macOS（x86_64 / ARM64）"}")
            }
        }
    }
}
