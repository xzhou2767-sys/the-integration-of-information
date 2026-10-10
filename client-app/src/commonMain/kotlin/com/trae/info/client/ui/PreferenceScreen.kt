package com.trae.info.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.trae.info.client.api.CloudApi
import com.trae.info.client.theme.HmclIndigo
import com.trae.info.core.api.PreferenceDto
import kotlinx.coroutines.launch

/**
 * 「信息偏好」页：
 * 1. 提供一段中文提示词，复制后粘贴到豆包 / Gemini / GPT 的对话框，
 *    与 AI 对话即可获得描述用户总体信息获取倾向与习惯的总结文本；
 * 2. 将 AI 输出的总结粘贴回本页并保存到云端；
 * 3. 云端依据总结推导兴趣关键词与展示模式，信息流自动个性化排序/展示。
 */

/** 发送给外部大模型对话框的中文提示词。 */
const val PREFERENCE_PROMPT = """你是一位信息获取习惯分析顾问。请你通过对话的方式向我提问（每轮 3-5 个问题，可分多轮逐步深入），帮助我梳理「总体信息获取倾向与习惯」。请覆盖以下方面：
1. 我长期关注的领域与主题（如人工智能、财经投资、人文历史、健康健身、国际时事等），以及各领域的关注程度与优先级；
2. 我偏好的内容形态：深度长文 / 快讯速览 / 视频 / 播客 / 数据图表 / 论文原文 / 社区讨论等；
3. 我的信息来源偏好：社交媒体、新闻门户、专业社区、学术论文、视频平台、播客、书籍等；
4. 我的阅读习惯：碎片时间浏览还是整块时间精读、每日/每周获取频率、对时效性的敏感程度；
5. 我对信息的处理方式：快速浏览标题、精读全文、收藏归档、做笔记、转发分享等；
6. 我希望避免或减少看到的信息类型。

在充分了解我的情况后，请输出一份「用户信息获取偏好总结」，要求：
- 中文、结构化分点，总字数 400-800 字；
- 必须包含：核心兴趣领域（按优先级列出 5-10 个具体关键词）、内容形态偏好、来源偏好、时效与深度偏好、信息处理习惯、希望过滤的信息类型；
- 核心兴趣领域的关键词请用「、」分隔，尽量使用具体名词（例如「大模型推理优化」「量化投资」「荣格心理学」，而不是「技术」「财经」这类泛词）；
- 这份总结将被信息聚合系统用于个性化排序信息流，请确保关键词与偏好描述明确、可直接用于内容匹配。"""

@Composable
fun PreferenceScreen(cloud: CloudApi) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var loaded by remember { mutableStateOf<PreferenceDto?>(null) }
    var input by remember { mutableStateOf("") }
    var savedSummary by remember { mutableStateOf("") }
    var savedMode by remember { mutableStateOf("default") }
    var savedKeywords by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("正在加载已保存的偏好…") }

    LaunchedEffect(Unit) {
        runCatching { cloud.getPreference() }
            .onSuccess {
                loaded = it
                savedSummary = it.summary
                savedMode = it.displayMode
                savedKeywords = it.keywords
                input = it.summary
                message = if (it.summary.isBlank())
                    "尚未保存信息偏好总结" else "已保存信息偏好总结，信息流个性化已启用"
            }
            .onFailure { message = "加载失败：${it.message}（请在「更多-设置」中检查云端地址）" }
    }

    fun save() {
        if (busy) return
        busy = true
        message = "正在保存…"
        scope.launch {
            runCatching { cloud.savePreference(input) }
                .onSuccess {
                    runCatching { cloud.getPreference() }
                        .onSuccess {
                            savedSummary = it.summary
                            savedMode = it.displayMode
                            savedKeywords = it.keywords
                        }
                    message = it.message
                }
                .onFailure { message = "保存失败：${it.message}" }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---- 步骤一：复制提示词到外部 AI 对话框 ----
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Psychology, contentDescription = null, tint = HmclIndigo)
                    Text("第一步：生成你的信息偏好档案", style = MaterialTheme.typography.titleSmall, color = HmclIndigo)
                }
                Text(
                    "复制下方提示词，粘贴到豆包、Gemini 或 ChatGPT 的对话框中，" +
                        "按 AI 的提问回答你的信息获取习惯，AI 会输出一份偏好总结。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )) {
                    Text(
                        PREFERENCE_PROMPT,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Button(onClick = {
                    clipboard.setText(AnnotatedString(PREFERENCE_PROMPT))
                    message = "提示词已复制，可粘贴到豆包 / Gemini / GPT 对话框"
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Text("  复制提示词")
                }
            }
        }

        // ---- 步骤二：粘贴 AI 总结并保存 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("第二步：粘贴 AI 给出的偏好总结", style = MaterialTheme.typography.titleSmall, color = HmclIndigo)
                Text(
                    "将豆包 / Gemini / GPT 输出的「用户信息获取偏好总结」全文粘贴到下方，保存后信息流将按你的兴趣关键词自动排序，并切换到匹配的展示模式（快讯密集 / 深度展开）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("在此粘贴 AI 生成的信息偏好总结") },
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save() }, enabled = !busy) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Text("  保存并启用个性化")
                    }
                    OutlinedButton(onClick = {
                        input = ""
                        if (!busy) {
                            busy = true
                            scope.launch {
                                runCatching { cloud.savePreference("") }
                                    .onSuccess {
                                        savedSummary = ""
                                        savedKeywords = emptyList()
                                        savedMode = "default"
                                        message = it.message
                                    }
                                    .onFailure { message = "清除失败：${it.message}" }
                                busy = false
                            }
                        }
                    }, enabled = !busy && savedSummary.isNotBlank()) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Text("  清除偏好")
                    }
                }
            }
        }

        // ---- 当前状态 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前个性化状态", style = MaterialTheme.typography.titleSmall, color = HmclIndigo)
                Text(message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (savedSummary.isNotBlank()) {
                    Text("展示模式：${modeLabel(savedMode)}", style = MaterialTheme.typography.bodyMedium)
                    if (savedKeywords.isNotEmpty()) {
                        Text("提取到的兴趣关键词：", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowChips(savedKeywords)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("已保存的总结原文：", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(savedSummary, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(keywords: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        keywords.take(16).forEach { kw ->
            AssistChip(onClick = {}, label = { Text(kw, style = MaterialTheme.typography.labelSmall) })
        }
    }
}

private fun modeLabel(mode: String): String = when (mode) {
    "compact" -> "快讯密集模式（紧凑卡片，便于快速浏览）"
    "detail" -> "深度展开模式（展示完整摘要）"
    else -> "默认模式"
}
