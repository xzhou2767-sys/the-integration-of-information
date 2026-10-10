package com.trae.info.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.trae.info.client.theme.HmclIndigo
import com.trae.info.core.api.AiConfigDto
import com.trae.info.core.api.CrawlerConfigDto
import com.trae.info.core.api.ProxyConfigDto
import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.SourcePlatform
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminScreen(cloud: CloudApi) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(AdminTab.AI) }
    var loading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    // AI 配置
    var aiConfig by remember { mutableStateOf(AiConfigDto()) }
    // 爬虫配置
    var crawlerConfig by remember { mutableStateOf(CrawlerConfigDto()) }
    // 代理配置
    var proxyConfig by remember { mutableStateOf(ProxyConfigDto()) }
    // 分类管理
    var defaults by remember { mutableStateOf<List<InfoCategory>>(emptyList()) }
    var customs by remember { mutableStateOf<List<InfoCategory>>(emptyList()) }
    var newCatName by remember { mutableStateOf("") }
    var newCatKeywords by remember { mutableStateOf("") }
    var selectedPlatforms by remember { mutableStateOf(setOf<String>()) }
    // 收集目的
    var goalsText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        loading = true
        runCatching {
            aiConfig = cloud.getAiConfig()
            crawlerConfig = cloud.getCrawlerConfig()
            proxyConfig = cloud.getProxyConfig()
            val cm = cloud.getCategoriesManagement()
            defaults = cm.defaults
            customs = cm.customs
            goalsText = cloud.getGoals().joinToString("\n")
        }.onFailure { msg = "加载失败：${it.message}" }
        loading = false
    }

    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("云端管理", style = MaterialTheme.typography.headlineSmall, color = HmclIndigo)
        Text("在此配置 AI API Key、抓取平台账号、代理参数与信息收集类目。所有密钥仅保存在云端。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AdminTab.entries.forEach { t ->
                FilterChip(selected = tab == t, onClick = { tab = t },
                    label = { Text(t.label) })
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                when (tab) {
                    AdminTab.AI -> AiConfigCard(aiConfig) { dto ->
                        scope.launch {
                            loading = true
                            runCatching { cloud.saveAiConfig(dto) }
                                .onSuccess { msg = "AI 配置已保存并即时生效" }
                                .onFailure { msg = "保存失败：${it.message}" }
                            aiConfig = dto; loading = false
                        }
                    }
                    AdminTab.CRAWLER -> CrawlerConfigCard(crawlerConfig) { dto ->
                        scope.launch {
                            loading = true
                            runCatching { cloud.saveCrawlerConfig(dto) }
                                .onSuccess { msg = "爬虫密钥已保存，适配器已重建" }
                                .onFailure { msg = "保存失败：${it.message}" }
                            crawlerConfig = dto; loading = false
                        }
                    }
                    AdminTab.PROXY -> ProxyConfigCard(proxyConfig) { dto ->
                        scope.launch {
                            loading = true
                            runCatching { cloud.saveProxyConfig(dto) }
                                .onSuccess { msg = "代理配置已保存（刷新或重启后生效）" }
                                .onFailure { msg = "保存失败：${it.message}" }
                            proxyConfig = dto; loading = false
                        }
                    }
                    AdminTab.CATEGORIES -> CategoryManageCard(
                        defaults, customs, newCatName, newCatKeywords, selectedPlatforms,
                        onName = { newCatName = it },
                        onKeywords = { newCatKeywords = it },
                        onTogglePlatform = { id -> selectedPlatforms = if (id in selectedPlatforms) selectedPlatforms - id else selectedPlatforms + id },
                        onAdd = {
                            scope.launch {
                                loading = true
                                runCatching {
                                    cloud.addCustomCategory(
                                        com.trae.info.core.api.CategoryCreateRequest(
                                            name = newCatName,
                                            keywords = newCatKeywords.split(",", "，").map { it.trim() }.filter { it.isNotBlank() },
                                            platformIds = selectedPlatforms.toList()
                                        )
                                    )
                                }.onSuccess {
                                    msg = "分类「$newCatName」已添加"
                                    newCatName = ""; newCatKeywords = ""
                                    customs = cloud.getCategoriesManagement().customs
                                }.onFailure { msg = "添加失败：${it.message}" }
                                loading = false
                            }
                        },
                        onDelete = { id ->
                            scope.launch {
                                runCatching { cloud.removeCustomCategory(id) }
                                    .onSuccess { customs = cloud.getCategoriesManagement().customs }
                                    .onFailure { msg = "删除失败：${it.message}" }
                            }
                        }
                    )
                    AdminTab.GOALS -> GoalsCard(goalsText) { text ->
                        scope.launch {
                            loading = true
                            val goals = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                            runCatching { cloud.saveGoals(goals) }
                                .onSuccess { msg = "收集目的已保存（${goals.size} 条）" }
                                .onFailure { msg = "保存失败：${it.message}" }
                            goalsText = text; loading = false
                        }
                    }
                }
            }
        }
    }
}

enum class AdminTab(val label: String) {
    AI("AI 配置"), CRAWLER("抓取密钥"), PROXY("代理"), CATEGORIES("分类管理"), GOALS("收集目的")
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = HmclIndigo)
            content()
        }
    }
}

@Composable
private fun AiConfigCard(cfg: AiConfigDto, onSave: (AiConfigDto) -> Unit) {
    var d by remember(cfg) { mutableStateOf(cfg) }
    SectionCard("AI API 配置（豆包优先，即时生效）") {
        OutlinedTextField(d.doubaoKey, { d = d.copy(doubaoKey = it) }, label = { Text("豆包 API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(d.doubaoModel, { d = d.copy(doubaoModel = it) }, label = { Text("豆包模型") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(d.doubaoBaseUrl, { d = d.copy(doubaoBaseUrl = it) }, label = { Text("豆包 BaseUrl") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(d.doubaoVisionModel, { d = d.copy(doubaoVisionModel = it) }, label = { Text("视觉模型（视频关键帧描述，首选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text(
            "视觉模型按性价比自动降级链（额度不足/限流/超时>180s 时切换）：\n" +
                "  1. doubao-seed-2-0-mini-260428（极低成本、高并发 30000 RPM）\n" +
                "  2. doubao-seed-2-0-lite-260428（低时延、高并发 30000 RPM）\n" +
                "  3. doubao-seed-2-1-turbo-260628（高性价比，256k）\n" +
                "  4. doubao-seed-2-1-pro-260628（旗舰稳定版，256k）\n" +
                "  5. LAS 数据湖（独立通道，方舟故障时兜底）\n" +
                "  6. doubao-seed-evolving（周级旗舰，最贵兜底）\n" +
                "注：首选模型留空则按上述顺序自动尝试；方舟未开通的模型会自动跳过。\n" +
                "老 doubao-vision 专用系列 2026-09-21 下线，已全面切换 Seed 2.x 原生多模态。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(d.lasApiKey, { d = d.copy(lasApiKey = it) }, label = { Text("LAS 数据湖 API Key（视觉备用通道）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text(
            "LAS 提供独立的视觉理解通道，方舟额度不足时自动切换。\n" +
                "在 LAS 控制台「资源管理 > API Key 管理」创建。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(d.openaiKey, { d = d.copy(openaiKey = it) }, label = { Text("OpenAI API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(d.openaiModel, { d = d.copy(openaiModel = it) }, label = { Text("OpenAI 模型") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(d.geminiKey, { d = d.copy(geminiKey = it) }, label = { Text("Gemini API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(d.geminiModel, { d = d.copy(geminiModel = it) }, label = { Text("Gemini 模型") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { onSave(d) }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
    }
}

@Composable
private fun CrawlerConfigCard(cfg: CrawlerConfigDto, onSave: (CrawlerConfigDto) -> Unit) {
    var d by remember(cfg) { mutableStateOf(cfg) }
    SectionCard("抓取平台密钥（保存后重建适配器）") {
        OutlinedTextField(d.youtubeApiKey, { d = d.copy(youtubeApiKey = it) }, label = { Text("YouTube Data API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(d.xBearerToken, { d = d.copy(xBearerToken = it) }, label = { Text("X/Twitter Bearer Token") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(d.serpApiKey, { d = d.copy(serpApiKey = it) }, label = { Text("SerpAPI Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(d.companionToken, { d = d.copy(companionToken = it) }, label = { Text("伴侣脚本 Token") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { onSave(d) }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
    }
}

@Composable
private fun ProxyConfigCard(cfg: ProxyConfigDto, onSave: (ProxyConfigDto) -> Unit) {
    var d by remember(cfg) { mutableStateOf(cfg) }
    SectionCard("3xui 代理配置") {
        OutlinedTextField(d.proxySubUrl, { d = d.copy(proxySubUrl = it) }, label = { Text("订阅地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(d.xrayPath, { d = d.copy(xrayPath = it) }, label = { Text("xray 路径") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(d.refreshIntervalMin.toString(), {
            d = d.copy(refreshIntervalMin = it.toLongOrNull() ?: 30L)
        }, label = { Text("刷新间隔（分钟）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { onSave(d) }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryManageCard(
    defaults: List<InfoCategory>,
    customs: List<InfoCategory>,
    newCatName: String,
    newCatKeywords: String,
    selectedPlatforms: Set<String>,
    onName: (String) -> Unit,
    onKeywords: (String) -> Unit,
    onTogglePlatform: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit
) {
    SectionCard("信息分类管理") {
        Text("自定义分类（${customs.size} 个）", style = MaterialTheme.typography.bodyMedium)
        customs.forEach { cat ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text(cat.name, style = MaterialTheme.typography.bodySmall); Text(cat.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedButton(onClick = { onDelete(cat.id) }) { Text("删除") }
            }
        }
        Text("新增自定义分类", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(newCatName, onName, label = { Text("分类名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(newCatKeywords, onKeywords, label = { Text("关键词（逗号分隔）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("选择平台", style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SourcePlatform.entries.forEach { p ->
                FilterChip(
                    selected = p.id in selectedPlatforms,
                    onClick = { onTogglePlatform(p.id) },
                    label = { Text(p.displayName) }
                )
            }
        }
        Button(onClick = onAdd, enabled = newCatName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("添加分类") }
        Text("默认分类体系（${defaults.size} 个，只读）", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun GoalsCard(text: String, onSave: (String) -> Unit) {
    var t by remember(text) { mutableStateOf(text) }
    SectionCard("AI 自定搜索 · 收集目的") {
        Text("每行一条收集目的，输入后可在信息流页使用 AI 自定搜索。AI 将理解目的、提取关键词并在多平台检索。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(t, { t = it }, label = { Text("收集目的（每行一条）") },
            modifier = Modifier.fillMaxWidth(), minLines = 4)
        Button(onClick = { onSave(t) }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
    }
}
