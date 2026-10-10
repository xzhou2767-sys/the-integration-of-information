package com.trae.info.client.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trae.info.client.api.CloudApi
import com.trae.info.client.data.FavoritesStore
import com.trae.info.client.data.FeedCacheStore
import com.trae.info.client.data.ReadStore
import com.trae.info.client.platform.Platform
import com.trae.info.client.theme.HmclIndigo
import com.trae.info.core.api.AiSearchResponse
import com.trae.info.core.api.AnalyzeRequest
import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.InfoCategory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(cloud: CloudApi) {
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf<List<InfoCategory>>(emptyList()) }
    var selected by remember { mutableStateOf<InfoCategory?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var proxied by remember { mutableStateOf(false) }
    var displayMode by remember { mutableStateOf("default") }
    var hint by remember { mutableStateOf("请选择细分类目并刷新") }
    var analysis by remember { mutableStateOf<String?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var reader by remember { mutableStateOf<ContentItem?>(null) }

    // AI 自定搜索
    var aiGoal by remember { mutableStateOf("") }
    var aiSearching by remember { mutableStateOf(false) }
    var aiResult by remember { mutableStateOf<AiSearchResponse?>(null) }

    /** 类目加载：本地缓存先行（瞬时显示上次内容），随后网络刷新并覆盖缓存。 */
    fun load(cat: InfoCategory) {
        scope.launch {
            aiResult = null
            val cached = FeedCacheStore.get(cat.id)
            if (cached != null && items != cached.feed.items) {
                items = cached.feed.items
                proxied = cached.feed.proxied
                displayMode = cached.feed.displayMode
                hint = "本地缓存 ${Platform.formatTime(cached.cachedAtEpochMs)}，正在刷新最新内容…"
            }
            loading = true
            runCatching { cloud.feed(cat.id) }
                .onSuccess {
                    items = it.items
                    proxied = it.proxied
                    displayMode = it.displayMode
                    hint = if (it.items.isEmpty())
                        "该类目暂无内容（对应平台可能待接入 API Key 或未命中内容）"
                    else buildString {
                        append("共 ${it.items.size} 条")
                        if (it.personalized) {
                            append("（已按信息偏好个性化排序")
                            when (it.displayMode) {
                                "compact" -> append(" · 快讯密集模式")
                                "detail" -> append(" · 深度展开模式")
                            }
                            append("）")
                        }
                        if (it.proxied) append("（含云端代理海外来源）")
                    }
                    FeedCacheStore.put(cat.id, it)
                }
                .onFailure {
                    hint = if (cached != null) "网络刷新失败（${it.message}），当前显示本地缓存"
                    else "获取失败：${it.message}"
                }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        // 类目列表缓存先行：断网/弱网时信息流仍可进入并显示缓存内容
        FeedCacheStore.cachedCategories?.let { cached ->
            if (categories.isEmpty()) {
                categories = cached
                if (selected == null) {
                    selected = cached.firstOrNull()
                    selected?.let { load(it) }
                }
            }
        }
        runCatching { cloud.categories() }
            .onSuccess {
                categories = it.categories
                FeedCacheStore.putCategories(it.categories)
                if (selected == null) {
                    selected = it.categories.firstOrNull()
                    selected?.let { load(it) }
                }
            }
            .onFailure {
                if (categories.isEmpty()) hint = "加载失败：${it.message}"
            }
    }

    fun doAiSearch() {
        if (aiGoal.isBlank()) return
        scope.launch {
            aiSearching = true
            runCatching { cloud.aiSearch(aiGoal) }
                .onSuccess {
                    aiResult = it
                    items = it.items
                    proxied = it.proxied
                    hint = "AI 检索策略：${it.reasoning}\n关键词：${it.keywords.joinToString("、")}"
                }
                .onFailure { hint = "AI 搜索失败：${it.message}（请在云端管理配置 AI Key）" }
            aiSearching = false
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // ---- AI 自定搜索入口 ----
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("AI 自定对象搜索", style = MaterialTheme.typography.titleSmall, color = HmclIndigo)
                Text("输入收集目的，AI 将理解意图并在多平台检索",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = aiGoal, onValueChange = { aiGoal = it },
                        label = { Text("如：大模型推理加速最新技术") },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                    Button(onClick = { doAiSearch() }, enabled = !aiSearching && aiGoal.isNotBlank()) {
                        Text(if (aiSearching) "搜索中…" else "AI 搜索")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- 类目选择 + 刷新 + AI 分析 ----
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { menuOpen = true }) { Text(selected?.name ?: "选择类目") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                categories.forEach { cat ->
                    DropdownMenuItem(text = { Text(cat.name) }, onClick = {
                        selected = cat; menuOpen = false; load(cat)
                    })
                }
            }
            Button(onClick = { selected?.let(::load) }, enabled = !loading && selected != null) { Text("刷新") }
            OutlinedButton(
                onClick = {
                    val cat = selected ?: return@OutlinedButton
                    analyzing = true
                    scope.launch {
                        runCatching { cloud.analyze(AnalyzeRequest(categoryId = cat.id, mode = "domain")) }
                            .onSuccess { analysis = "【${it.provider}】\n${it.content}" }
                            .onFailure { analysis = "AI 分析失败：${it.message}" }
                        analyzing = false
                    }
                },
                enabled = !analyzing && selected != null
            ) { Text(if (analyzing) "分析中…" else "AI 领域分析") }
        }

        // ---- 提示行 + 全部标为已读（未读条目标记参考 ReadYou/Fusion 状态机）----
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(hint, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp))
            if (items.isNotEmpty()) {
                TextButton(
                    onClick = { ReadStore.markRead(items.map { it.id }) },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text("全部已读", style = MaterialTheme.typography.labelMedium) }
            }
        }
        if (loading && items.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())

        // ---- 条目化信息流 ----
        if (loading && items.isEmpty() && !aiSearching) {
            // 骨架屏：无缓存首次加载时显示 shimmer 占位卡片
            SkeletonFeedCards()
        } else {
            AnimatedContent(
                targetState = selected?.id,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 24 })
                        .togetherWith(fadeOut(tween(160)))
                },
                label = "categorySwitch"
            ) { _ ->
                // 下拉刷新（Android 手势 / 桌面滚轮回顶拖拽均可触发）
                PullToRefreshBox(
                    isRefreshing = loading,
                    onRefresh = { selected?.let(::load) }
                ) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(if (displayMode == "compact") 4.dp else 8.dp)
                    ) {
                        items(items, key = { it.id }) { item ->
                            FeedItemCard(
                                item,
                                displayMode = displayMode,
                                favorited = FavoritesStore.isFavorite(item.id),
                                onToggleFavorite = { FavoritesStore.toggle(item) },
                                read = ReadStore.isRead(item.id),
                                modifier = Modifier.animateItemPlacement()
                            ) {
                                reader = item
                                // 打开详情即标记已读（Fusion 模式：unread → read）
                                ReadStore.markRead(listOf(item.id))
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 沉浸式阅读弹窗（信息流与收藏夹共用）----
    val sheetState = rememberModalBottomSheetState()
    reader?.let { item ->
        ItemDetailSheet(item, sheetState, onDismiss = { reader = null })
    }

    if (analysis != null) {
        AlertDialog(
            onDismissRequest = { analysis = null },
            title = { Text("AI 系统性分析") },
            text = { Text(analysis!!) },
            confirmButton = { TextButton(onClick = { analysis = null }) { Text("关闭") } }
        )
    }
}

/** 收藏星标颜色：空心为灰色描边感，实心为琥珀金。 */
val StarAmber = Color(0xFFFFB300)

/**
 * 信息流条目卡片（信息流与收藏夹共用）。
 * @param favorited 是否已收藏（控制空心/实心星标）
 * @param read 是否已读（已读标题置灰、不再显示未读圆点）
 * @param modifier 由调用方注入（信息流用于 animateItemPlacement 列表重排动画）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FeedItemCard(
    item: ContentItem,
    displayMode: String = "default",
    favorited: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    read: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // compact：快讯密集模式（紧凑内边距、仅标题+来源）；detail：深度展开模式（摘要多行）
    val pad = if (displayMode == "compact") 10.dp else 14.dp
    Card(modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 未读圆点 + 标题 + 收藏星标（空心 ↔ 实心）
            Row(verticalAlignment = Alignment.Top) {
                if (!read) {
                    // 未读指示点（主题色，参考邮件/资讯类应用惯例）
                    Box(
                        Modifier
                            .padding(top = 6.dp, end = 6.dp)
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (read) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (displayMode == "compact") 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Icon(
                        imageVector = if (favorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (favorited) "取消收藏" else "收藏",
                        tint = if (favorited) StarAmber else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 外文标题中文译题（服务端批量翻译+缓存，信息流阅读辅助）
            item.translatedTitle?.let { zh ->
                Text(zh, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (displayMode == "compact") 1 else 2,
                    overflow = TextOverflow.Ellipsis)
            }
            // AI 一句话总结：compact 以单行 AI 总结替代摘要；default/detail 在摘要上方成块展示
            val ai = item.aiSummary
            if (!ai.isNullOrBlank() && displayMode == "compact") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("AI", style = MaterialTheme.typography.labelSmall, color = HmclIndigo)
                    Text(ai, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                }
            }
            if (displayMode != "compact") {
                if (!ai.isNullOrBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("AI 总结", style = MaterialTheme.typography.labelSmall, color = HmclIndigo)
                        Text(ai, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (displayMode == "detail") Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                if (item.summary.isNotBlank()) {
                    Text(item.summary, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (displayMode == "detail") 8 else 3,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("来源：${item.source}", style = MaterialTheme.typography.labelSmall, color = HmclIndigo)
                if (item.author.isNotBlank()) Text("｜${item.author}", style = MaterialTheme.typography.labelSmall, color = HmclIndigo)
            }
            if (item.tags.isNotEmpty() && displayMode != "compact") {
                Text(item.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (item.url.isNotBlank()) {
                Text(item.url, style = MaterialTheme.typography.labelSmall,
                    color = HmclIndigo,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { Platform.openUrl(item.url) })
            }
        }
    }
}

/**
 * 条目详情底部弹层（信息流与收藏夹共用）：标题/来源/标签/AI 总结/摘要/原文链接，
 * 标题栏右侧带收藏星标，收藏状态直接读写 [FavoritesStore]（可观察状态自动刷新）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItemDetailSheet(
    item: ContentItem,
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(item.title, style = MaterialTheme.typography.headlineSmall, color = HmclIndigo,
                    modifier = Modifier.weight(1f))
                val favorited = FavoritesStore.isFavorite(item.id)
                IconButton(onClick = { FavoritesStore.toggle(item) }) {
                    Icon(
                        imageVector = if (favorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (favorited) "取消收藏" else "收藏",
                        tint = if (favorited) StarAmber else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 外文标题中文译题（与卡片一致的阅读辅助）
            item.translatedTitle?.let { zh ->
                Text(zh, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("来源：${item.source}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                if (item.author.isNotBlank()) Text("｜${item.author}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (item.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.tags.take(6).forEach { tag ->
                        AssistChip(onClick = {}, label = { Text(tag, style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }
            // AI 总结高亮块（有总结时展示全文）
            val ai = item.aiSummary
            if (!ai.isNullOrBlank()) {
                Surface(color = HmclIndigo.copy(alpha = 0.08f)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("AI 总结", style = MaterialTheme.typography.labelSmall, color = HmclIndigo)
                        Text(ai, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (item.summary.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(item.summary, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp))
                }
            }
            if (item.url.isNotBlank()) {
                Text("原文链接：\n${item.url}", style = MaterialTheme.typography.labelSmall,
                    color = HmclIndigo,
                    modifier = Modifier.clickable { Platform.openUrl(item.url) })
            }
            Text("分类：${item.categoryId}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.url.isNotBlank()) {
                    TextButton(onClick = { Platform.openUrl(item.url) }) { Text("打开原文") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

// ---- 骨架屏（shimmer 加载占位，参考 ReadYou 的加载反馈）----

/** 首次加载（无本地缓存）时显示的 4 张 shimmer 占位卡片，形状贴合实际卡片布局。 */
@Composable
private fun SkeletonFeedCards() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(4) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShimmerLine(0.92f, 18.dp)
                    ShimmerLine(0.75f, 13.dp)
                    ShimmerLine(0.55f, 13.dp)
                    Spacer(Modifier.height(2.dp))
                    ShimmerLine(0.35f, 11.dp)
                }
            }
        }
    }
}

/** 单条 shimmer 高亮扫过占位线：surfaceVariant 底色上以移动渐变高光带模拟加载脉动。 */
@Composable
private fun ShimmerLine(widthFraction: Float, height: Dp) {
    val density = LocalDensity.current
    val travel = with(density) { 1200.dp.toPx() }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "shimmerProgress"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surface
    val brush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(progress * travel - travel / 3, 0f),
        end = Offset(progress * travel + travel / 3, 0f)
    )
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .background(brush, RoundedCornerShape(4.dp))
    )
}
