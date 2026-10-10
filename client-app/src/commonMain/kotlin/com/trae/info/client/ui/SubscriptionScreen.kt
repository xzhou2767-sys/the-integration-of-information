package com.trae.info.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trae.info.client.api.CloudApi
import com.trae.info.client.theme.HmclIndigo
import com.trae.info.core.model.Domain
import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.PlatformGroup
import com.trae.info.core.model.SourcePlatform
import com.trae.info.core.model.SubscriptionPrefs
import com.trae.info.core.model.nowMs
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * 订阅定制屏：信息细分类目按「域 -> 分组」渲染为可勾选列表，
 * 信息平台以 Chip 形式开关；结果保存到云端，决定云端采集范围。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubscriptionScreen(cloud: CloudApi) {
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf<List<InfoCategory>>(emptyList()) }
    var platforms by remember { mutableStateOf<List<SourcePlatform>>(emptyList()) }
    var prefs by remember { mutableStateOf(SubscriptionPrefs()) }
    var loaded by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    // 平台组合编辑器状态
    var showGroupDialog by remember { mutableStateOf(false) }
    var editingGroupId by remember { mutableStateOf<String?>(null) }
    var groupName by remember { mutableStateOf("") }
    var selectedPlatformIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        runCatching {
            val data = cloud.categories()
            categories = data.categories
            platforms = data.platforms
            prefs = cloud.subscription()
            loaded = true
        }.onFailure { hint = "加载失败：${it.message}（请检查设置中的云端地址）" }
    }

    if (!loaded) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(if (hint.isBlank()) "正在加载分类体系…" else hint)
        }
        return
    }

    // 固定域顺序（commonMain 无 java.util.TreeMap）
    val grouped: List<Pair<Domain, Map<String, List<InfoCategory>>>> =
        listOf(Domain.PRACTICAL, Domain.NE).map { domain ->
            domain to categories.filter { it.domain == domain }.groupBy { it.group }
        }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("客制化信息收集", style = MaterialTheme.typography.headlineSmall)
            Text(
                "勾选需要云端持续收集的细分类目与信息平台，保存后云端调度立即生效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 平台开关
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("信息平台来源", style = MaterialTheme.typography.titleMedium, color = HmclIndigo)
                    Text(
                        "海外平台（Reddit / YouTube / X / Quora）由云端自动代理穿透",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        platforms.forEach { p ->
                            FilterChip(
                                selected = prefs.sourceEnabled(p),
                                onClick = {
                                    prefs = prefs.toggledSource(p.id, !prefs.sourceEnabled(p))
                                },
                                label = { Text(p.displayName + if (p.requiresProxy) " ·海外" else "") }
                            )
                        }
                    }
                }
            }
        }

        // 平台组合：选取若干平台编组，可重命名并选为抓取来源
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("平台组合", style = MaterialTheme.typography.titleMedium, color = HmclIndigo)
                    Text(
                        "选取若干平台编入组合，激活后仅组合内平台参与抓取",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    // 单独平台开关选项
                    Row(
                        Modifier.fillMaxWidth().clickable { prefs = prefs.activateGroup(null) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = prefs.activeGroupId == null,
                            onClick = { prefs = prefs.activateGroup(null) }
                        )
                        Text("单独平台开关（使用上方勾选）", style = MaterialTheme.typography.bodyMedium)
                    }
                    // 已有组合列表
                    prefs.platformGroups.forEach { group ->
                        Row(
                            Modifier.fillMaxWidth().clickable { prefs = prefs.activateGroup(group.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = prefs.activeGroupId == group.id,
                                onClick = { prefs = prefs.activateGroup(group.id) }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(group.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    group.platformIds.size.toString() + " 个平台：" +
                                        group.platformIds.take(5).joinToString(" · ") { id ->
                                            platforms.firstOrNull { it.id == id }?.displayName ?: id
                                        } + if (group.platformIds.size > 5) " …" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                editingGroupId = group.id
                                groupName = group.name
                                selectedPlatformIds = group.platformIds.toSet()
                                showGroupDialog = true
                            }) { Text("编辑") }
                            TextButton(onClick = { prefs = prefs.removeGroup(group.id) }) {
                                Text("删除")
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = {
                        editingGroupId = null
                        groupName = ""
                        selectedPlatformIds = emptySet()
                        showGroupDialog = true
                    }) { Text("新建组合") }
                }
            }
        }

        // 细分类目：域 -> 分组
        grouped.forEach { (domain, groups) ->
            item {
                Text(
                    domain.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            groups.forEach { (groupName, cats) ->
                item(key = "group-$domain-$groupName") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(
                                groupName,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            cats.forEach { cat ->
                                CategoryRow(
                                    category = cat,
                                    checked = prefs.categoryEnabled(cat.id),
                                    onToggle = { enabled ->
                                        prefs = prefs.toggledCategory(cat.id, enabled)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    scope.launch {
                        runCatching { cloud.saveSubscription(prefs) }
                            .onSuccess { hint = "已保存：云端将按订阅收集 ${prefs.let { categories.count { c -> it.categoryEnabled(c.id) } }} 个类目" }
                            .onFailure { hint = "保存失败：${it.message}" }
                    }
                }) { Text("保存订阅到云端") }
                if (hint.isNotBlank()) {
                    Text(hint, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // 平台组合编辑对话框
    if (showGroupDialog) {
        AlertDialog(
            onDismissRequest = { showGroupDialog = false },
            title = { Text(if (editingGroupId == null) "新建平台组合" else "编辑平台组合") },
            text = {
                Column {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text("组合名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("选择平台：", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        platforms.forEach { p ->
                            FilterChip(
                                selected = p.id in selectedPlatformIds,
                                onClick = {
                                    selectedPlatformIds = if (p.id in selectedPlatformIds)
                                        selectedPlatformIds - p.id
                                    else selectedPlatformIds + p.id
                                },
                                label = { Text(p.displayName) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (groupName.isNotBlank() && selectedPlatformIds.isNotEmpty()) {
                        val id = editingGroupId ?: "group-" + nowMs().toString(16)
                        prefs = prefs.upsertGroup(
                            PlatformGroup(
                                id = id,
                                name = groupName.trim(),
                                platformIds = selectedPlatformIds.toList()
                            )
                        )
                        // 新建组合后自动激活
                        if (editingGroupId == null) prefs = prefs.activateGroup(id)
                    }
                    showGroupDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showGroupDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun CategoryRow(
    category: InfoCategory,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Column {
            Text(category.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                category.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
