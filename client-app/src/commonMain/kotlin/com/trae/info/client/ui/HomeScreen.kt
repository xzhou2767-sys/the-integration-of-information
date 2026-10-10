package com.trae.info.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trae.info.client.AppScreen
import com.trae.info.client.api.CloudApi
import com.trae.info.client.theme.HmclIndigo
import com.trae.info.core.api.ProxyStatusResponse
import com.trae.info.core.api.ScrapingStatusDto
import com.trae.info.core.model.Taxonomy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun HomeScreen(cloud: CloudApi, onNavigate: (AppScreen) -> Unit) {
    var proxy by remember { mutableStateOf<ProxyStatusResponse?>(null) }
    var scraping by remember { mutableStateOf<ScrapingStatusDto?>(null) }
    var proxyError by remember { mutableStateOf<String?>(null) }
    var scrapingError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 本轮预计抓取时长（分钟，≥5 不设上限）；空白/非数字时传 0 由服务端取默认
    var expectedMinutesText by remember { mutableStateOf("15") }
    // AI 总结级别：brief（模糊·≤25字速览）/ default（默认·≤40字精要）/ detailed（详细·80-120字）
    var summaryMode by remember { mutableStateOf("default") }
    // 一键总结所有条目（关闭时仅高相关条目、受预算限制）
    var summarizeAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refreshScraping() {
        runCatching { cloud.scrapingStatus() }
            .onSuccess { scraping = it }
            .onFailure { /* 静默失败，避免覆盖代理错误信息 */ }
    }

    LaunchedEffect(Unit) {
        runCatching { cloud.proxyStatus() }
            .onSuccess { proxy = it }
            .onFailure { proxyError = "无法连接云端服务，请在「设置」中检查服务地址" }
        refreshScraping()
    }

    // 抓取运行中（循环模式 running=true，或手动 tick 时 phase=PLANNING/FETCHING/SUMMARIZING）
    // 每 2.5 秒轮询一次进度；phase 到 DONE/IDLE 且循环未运行时停止，离开界面自动取消
    LaunchedEffect(scraping?.running, scraping?.phase) {
        val inProgress = scraping?.let {
            it.running || (it.phase != "IDLE" && it.phase != "DONE")
        } == true
        while (inProgress) {
            delay(2500)
            refreshScraping()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("跨平台信息整合系统", style = MaterialTheme.typography.headlineSmall)
        Text(
            "实用性信息 · Ne 类信息 · AI 领域/倾向分析",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("云端代理状态", style = MaterialTheme.typography.titleMedium,
                    color = HmclIndigo)
                Spacer(Modifier.height(8.dp))
                if (proxyError != null) {
                    Text(proxyError!!, color = MaterialTheme.colorScheme.error)
                } else if (proxy == null) {
                    Text("正在获取云端代理状态…")
                } else {
                    val p = proxy!!
                    Text("状态：${stateLabel(p.state)}")
                    Text("节点数：${p.nodeCount}")
                    p.activeNode?.let { Text("当前节点：$it（${p.activeProtocol}，${p.activeLatencyMs} ms）") }
                    Text(p.message, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onNavigate(AppScreen.PROXY) }) { Text("代理详情") }
                    Button(onClick = { onNavigate(AppScreen.SUBSCRIBE) }) { Text("去定制订阅") }
                }
            }
        }

        // ---- 抓取进程控制面板 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("信息抓取进程", style = MaterialTheme.typography.titleMedium, color = HmclIndigo)
                val s = scraping
                if (s == null) {
                    Text("正在获取抓取进程状态…")
                } else {
                    val runningLabel = if (s.running) "运行中" else "已停止"
                    Text("状态：$runningLabel ｜ 循环间隔：${formatInterval(s.intervalSec)}")
                    if (s.running) {
                        val next = s.nextAtEpochMs
                        if (next != null) {
                            val now = System.currentTimeMillis()
                            val remain = max(0L, (next - now) / 1000)
                            Text("距下次抓取：${formatInterval(remain)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (s.lastRunFinishedAtEpochMs != null) {
                        Text("上次抓取：${s.lastItemsCount} 条 / 失败 ${s.lastFailedCategories} 类目 ｜ 累计 ${s.totalRuns} 次",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    s.lastError?.let {
                        Text("错误：$it", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (s.message.isNotBlank()) {
                        Text(s.message, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 预计抓取时长输入（≥5 分钟、不设上限；留空或非数字时传 0 由服务端取默认）
                OutlinedTextField(
                    value = expectedMinutesText,
                    onValueChange = { v -> expectedMinutesText = v.filter(Char::isDigit).take(4) },
                    label = { Text("预计抓取时长") },
                    suffix = { Text("分钟") },
                    supportingText = { Text("至少 5 分钟，不设上限；留空默认 15 分钟") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val expectedMinutesInput = expectedMinutesText.trim().toIntOrNull()
                // 小于 5 视为非法输入；空白/非数字传 0 由服务端取默认
                val expectedMinutesValid = expectedMinutesInput == null || expectedMinutesInput >= 5
                val expectedMinutesValue = expectedMinutesInput ?: 0
                if (!expectedMinutesValid) {
                    Text("预计抓取时长至少 5 分钟",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }

                // ---- AI 总结级别（模糊/默认/详细）----
                Text("AI 总结级别", style = MaterialTheme.typography.labelLarge, color = HmclIndigo)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryLevelChip("模糊", "一句话速览（≤25字）", summaryMode == "brief") { summaryMode = "brief" }
                    SummaryLevelChip("默认", "一句话精要（≤40字）", summaryMode == "default") { summaryMode = "default" }
                    SummaryLevelChip("详细", "2-3 句要点（80-120字）", summaryMode == "detailed") { summaryMode = "detailed" }
                }
                // ---- 一键总结所有条目 ----
                Row(
                    Modifier.fillMaxWidth().clickable { summarizeAll = !summarizeAll },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Switch(checked = summarizeAll, onCheckedChange = { summarizeAll = it })
                    Column(Modifier.weight(1f)) {
                        Text("一键总结所有条目", style = MaterialTheme.typography.bodyMedium)
                        Text("关闭时仅总结高相关条目（更省时）；开启后本轮抓到的每条内容都生成总结",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true
                            scrapingError = null
                            scope.launch {
                                runCatching { cloud.scrapingStart(expectedMinutes = expectedMinutesValue) }
                                    .onSuccess { scraping = it }
                                    .onFailure { scrapingError = "启动失败：${it.message}" }
                                busy = false
                            }
                        },
                        enabled = scraping?.running != true && !busy && expectedMinutesValid,
                        colors = ButtonDefaults.buttonColors(containerColor = HmclIndigo)
                    ) { Text("开始抓取") }

                    OutlinedButton(
                        onClick = {
                            if (busy) return@OutlinedButton
                            busy = true
                            scrapingError = null
                            scope.launch {
                                runCatching { cloud.scrapingStop() }
                                    .onSuccess { scraping = it }
                                    .onFailure { scrapingError = "停止失败：${it.message}" }
                                busy = false
                            }
                        },
                        enabled = scraping?.running == true && !busy
                    ) { Text("停止抓取") }

                    OutlinedButton(
                        onClick = {
                            if (busy) return@OutlinedButton
                            busy = true
                            scrapingError = null
                            scope.launch {
                                runCatching { cloud.scrapingTick(
                                    expectedMinutes = expectedMinutesValue,
                                    summaryMode = summaryMode,
                                    summarizeAll = summarizeAll
                                ) }
                                    .onSuccess { scraping = it }
                                    .onFailure { scrapingError = "触发失败：${it.message}" }
                                busy = false
                            }
                        },
                        enabled = !busy && expectedMinutesValid
                    ) { Text("立即抓取一次") }
                }
                if (busy) {
                    Text("操作进行中…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                scrapingError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                // ---- 抓取进度展示区（DONE 且未在循环运行时收起，避免进度条长驻）----
                val st = scraping
                if (st != null && (st.running || (st.phase != "IDLE" && st.phase != "DONE"))) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { st.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${phaseLabel(st.phase)} ｜ 单元 ${st.completedUnits}/${st.totalUnits} ｜ " +
                                "条目 ${st.fetchedItems}/${st.targetItems} ｜ 已总结 ${st.summarizedItems}"
                    )
                    if (st.currentTask.isNotBlank()) {
                        Text("正在处理：${st.currentTask}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (st.expectedMinutes > 0) {
                        Text("预计 ${st.expectedMinutes} 分钟", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("信息分类体系", style = MaterialTheme.typography.titleMedium, color = HmclIndigo)
                Text("细分类目：${Taxonomy.categories.size} 项")
                Text("信息平台：${Taxonomy.platforms.size} 个（国内 ${Taxonomy.platforms.count { !it.requiresProxy }} / 海外 ${Taxonomy.platforms.count { it.requiresProxy }}）")
                Text(
                    "覆盖：高等教育学科、人工智能、前沿物理、国际时事、就业政策、" +
                            "数码硬件、电子信息解读、社会学、哲学、学科之美、" +
                            "荣格心理学、Socionics、占星、八字、紫微斗数",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("使用方式", style = MaterialTheme.typography.titleMedium, color = HmclIndigo)
                Text("1. 在「订阅定制」中勾选感兴趣的细分类目与信息平台")
                Text("2. 在「信息流」中选择类目查看聚合内容，并可发起 AI 领域分析")
                Text("3. 海外平台流量由云端自动经 3xui 代理穿透，客户端零配置")
            }
        }
    }
}

fun stateLabel(state: String): String = when (state) {
    "READY" -> "运行中（已自动选优）"
    "REFRESHING" -> "正在刷新/测速"
    "NO_XRAY" -> "云端未安装 Xray 内核"
    "EMPTY" -> "订阅无可用节点"
    "NO_CONFIG" -> "未配置订阅"
    "ERROR" -> "异常"
    else -> state
}

/** 抓取阶段中文映射：IDLE/PLANNING/FETCHING/SUMMARIZING/DONE。 */
fun phaseLabel(phase: String): String = when (phase) {
    "IDLE" -> "空闲"
    "PLANNING" -> "规划中"
    "FETCHING" -> "抓取中"
    "SUMMARIZING" -> "AI总结中"
    "DONE" -> "已完成"
    else -> phase
}

/** 将秒数格式化为人性化间隔，如「12小时」「30分钟」「45秒」。 */
fun formatInterval(sec: Long): String = when {
    sec >= 3600 -> {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        if (m == 0L) "${h}小时" else "${h}小时${m}分"
    }
    sec >= 60 -> "${sec / 60}分钟"
    else -> "${sec}秒"
}

/** AI 总结级别中文标签：brief/detailed/其他→默认。 */
fun summaryModeLabel(mode: String): String = when (mode) {
    "brief" -> "模糊"
    "detailed" -> "详细"
    else -> "默认"
}

/** 抓取页的总结级别选择 Chip。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryLevelChip(label: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(if (selected) "$label · $hint" else label) },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = HmclIndigo.copy(alpha = 0.15f),
            selectedLabelColor = HmclIndigo
        )
    )
}
