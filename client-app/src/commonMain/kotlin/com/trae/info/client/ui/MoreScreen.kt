package com.trae.info.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.trae.info.client.AppScreen
import com.trae.info.client.api.CloudApi
import com.trae.info.client.theme.HmclIndigo

/**
 * 「更多」页：云端相关功能的二级菜单入口。
 * 云端管理 / 云端登录 / 云端代理 / 设置 统一收归此处，保持底部导航精简。
 */
@Composable
fun MoreScreen(cloud: CloudApi, onOpen: (AppScreen) -> Unit) {
    val entries = listOf(
        MenuEntry(
            screen = AppScreen.ADMIN,
            icon = Icons.Filled.AdminPanelSettings,
            title = "云端管理",
            desc = "AI Key 与模型、爬虫密钥、代理参数、信息分类、视觉模型健康度"
        ),
        MenuEntry(
            screen = AppScreen.SESSIONS,
            icon = Icons.Filled.Person,
            title = "云端登录",
            desc = "哔哩哔哩 / 知乎 / 小红书 / 抖音 / Reddit / Quora 云端浏览器登录会话管理"
        ),
        MenuEntry(
            screen = AppScreen.PROXY,
            icon = Icons.Filled.Cloud,
            title = "云端代理",
            desc = "3xui 节点状态、延迟测速与手动刷新"
        ),
        MenuEntry(
            screen = AppScreen.SETTINGS,
            icon = Icons.Filled.Settings,
            title = "设置",
            desc = "云端服务地址配置"
        )
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("更多功能", style = MaterialTheme.typography.headlineSmall)
        Text(
            "云端能力与系统设置。所有 AI Key、代理穿透、抓取调度均在云端完成，客户端零配置。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        entries.forEach { entry ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(entry.screen) }) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(entry.icon, contentDescription = null, tint = HmclIndigo)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            entry.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "进入${entry.title}"
                    )
                }
            }
        }
    }
}

private data class MenuEntry(
    val screen: AppScreen,
    val icon: ImageVector,
    val title: String,
    val desc: String
)
