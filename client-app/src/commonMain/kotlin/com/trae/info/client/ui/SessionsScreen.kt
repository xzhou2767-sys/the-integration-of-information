@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.trae.info.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.trae.info.client.api.CloudApi
import com.trae.info.client.platform.decodeImageBitmap
import com.trae.info.client.theme.HmclIndigo
import com.trae.info.core.api.SessionInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.math.roundToInt

/**
 * 云端浏览器登录会话管理。
 *
 * 登录方式优先级：
 * 1. 二维码（页面提供时自动提示，推荐手机 App 扫码）；
 * 2. Agent 智能登录（客户端直填账号密码，云端自动识别表单并填充提交）；
 * 3. 手动远程控制（触控板式映射的虚拟键鼠，主动点击唤起，保底方案）。
 *
 * 触控板映射：拖动 = 移动光标（客户端本地渲染光标叠加层），轻点 = 在光标处左键点击；
 * 「唤起键盘」主动弹出输入法；远程屏幕不再使用直接触控点击映射。
 * 登录完成后云端持久化会话长期复用。全程不破解验证码，凭据不落客户端与服务端磁盘。
 */
@Composable
fun SessionsScreen(cloud: CloudApi) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val inputFocus = remember { FocusRequester() }

    var sessions by remember { mutableStateOf<List<SessionInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var activeName by remember { mutableStateOf("") }
    var shot by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var urlField by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf("") }

    // Agent 智能登录表单
    var agentUser by remember { mutableStateOf("") }
    var agentPass by remember { mutableStateOf("") }
    var passVisible by remember { mutableStateOf(false) }
    // 二维码优先提示
    var qrAvailable by remember { mutableStateOf(false) }
    // 手动远程控制（保底方案，主动点击唤起）
    var manualMode by remember { mutableStateOf(false) }
    // 触控板光标（视口坐标系 1366x900）
    var cursorX by remember { mutableStateOf(VIEWPORT_W.toDouble() / 2) }
    var cursorY by remember { mutableStateOf(VIEWPORT_H.toDouble() / 2) }

    fun reload() {
        scope.launch {
            loading = true
            runCatching { cloud.sessions() }
                .onSuccess { sessions = it; error = null }
                .onFailure {
                    error = "无法获取会话状态：${it.message}\n（云端浏览器自动化需服务器安装 Chromium）"
                }
            loading = false
        }
    }

    fun refreshShot() {
        val id = activeId ?: return
        scope.launch {
            runCatching { cloud.loginScreenshot(id) }
                .onSuccess { decodePng(it.pngBase64)?.let { bmp -> shot = bmp } }
        }
    }

    fun resetLoginState() {
        activeId = null; shot = null; currentUrl = ""
        qrAvailable = false; manualMode = false
        agentUser = ""; agentPass = ""; passVisible = false
        cursorX = VIEWPORT_W.toDouble() / 2; cursorY = VIEWPORT_H.toDouble() / 2
    }

    LaunchedEffect(Unit) { reload() }

    // 登录进行中：每 2.5 秒轮询最新画面（二维码会刷新，远程操作后画面也会更新）
    LaunchedEffect(activeId) {
        val id = activeId ?: return@LaunchedEffect
        runCatching { cloud.remoteUrl(id) }.onSuccess { currentUrl = it.url }
        while (true) {
            delay(2500)
            runCatching { cloud.loginScreenshot(id) }
                .onSuccess {
                    decodePng(it.pngBase64)?.let { bmp -> shot = bmp }
                    // 延迟弹出的二维码入口在后续轮询帧补检出时，提示条自动浮现（只升不降）
                    if (it.state == "QR_AVAILABLE") qrAvailable = true
                }
                .onFailure { /* 会话可能已完成，等待 complete 处理 */ }
            runCatching { cloud.remoteUrl(id) }.onSuccess { currentUrl = it.url }
        }
    }

    fun startLogin(s: SessionInfo) {
        scope.launch {
            busy = true
            message = "云端正在打开 ${s.name} 登录页…"
            runCatching { cloud.loginStart(s.platform) }
                .onSuccess {
                    shot = decodePng(it.pngBase64)
                    activeId = s.platform
                    activeName = s.name
                    qrAvailable = it.state == "QR_AVAILABLE"
                    message = if (qrAvailable)
                        "检测到二维码登录入口：推荐用手机 App 扫描画面中的二维码（自动刷新）；也可在下方直接输入账号密码由 Agent 登录"
                    else
                        "可输入账号密码使用 Agent 智能登录，或切换手动远程控制操作；无法自动检测登录态时点「强制保存会话」"
                    runCatching { cloud.remoteUrl(s.platform) }.onSuccess { currentUrl = it.url }
                }
                .onFailure { message = "启动登录失败：${it.message}" }
            busy = false
        }
    }

    fun completeLogin(force: Boolean = false) {
        val id = activeId ?: return
        scope.launch {
            busy = true
            runCatching { cloud.loginComplete(id, force) }
                .onSuccess {
                    if (it.loggedIn) {
                        message = if (force) "已强制保存「$activeName」会话（跳过自动检测）"
                        else "登录成功，云端已保存「$activeName」会话"
                        resetLoginState(); reload()
                    } else {
                        message = "尚未检测到登录态。若已手动登录但检测不准，可点「强制保存会话」"
                    }
                }
                .onFailure { message = "校验失败：${it.message}" }
            busy = false
        }
    }

    fun agentLogin() {
        val id = activeId ?: return
        if (agentUser.isBlank() || agentPass.isBlank()) {
            message = "请先输入账号与密码"
            return
        }
        scope.launch {
            busy = true
            message = "Agent 正在云端登录 $activeName（识别表单 → 填充 → 提交）…"
            runCatching { cloud.agentLogin(id, agentUser, agentPass) }
                .onSuccess { r ->
                    decodePng(r.pngBase64)?.let { bmp -> shot = bmp }
                    busy = false
                    when (r.state) {
                        "LOGGED_IN" -> {
                            message = "Agent 登录成功，正在保存会话…"
                            completeLogin(false)
                        }
                        "QR_ONLY" -> message = r.message
                        "NEEDS_MANUAL" -> message = "${r.message}（云端会话已保留，可切手动远程控制）"
                        else -> message = r.message
                    }
                }
                .onFailure { message = "Agent 登录失败：${it.message}"; busy = false }
        }
    }

    fun cancelLogin() {
        val id = activeId ?: return
        scope.launch { runCatching { cloud.loginCancel(id) } }
        resetLoginState()
        message = "已取消登录"
    }

    fun sendClick(vx: Double, vy: Double) {
        val id = activeId ?: return
        scope.launch {
            runCatching { cloud.remoteClick(id, vx, vy) }
                .onFailure { message = "点击失败：${it.message}" }
            delay(120); refreshShot()
        }
    }

    fun sendType() {
        val id = activeId ?: return
        val t = text
        if (t.isEmpty()) return
        scope.launch {
            runCatching { cloud.remoteType(id, t) }
                .onFailure { message = "输入失败：${it.message}" }
            text = ""
            delay(120); refreshShot()
        }
    }

    fun sendKey(key: String) {
        val id = activeId ?: return
        scope.launch {
            runCatching { cloud.remoteKey(id, key) }
                .onFailure { message = "按键失败：${it.message}" }
            delay(120); refreshShot()
        }
    }

    fun sendNavigate() {
        val id = activeId ?: return
        val u = urlField.trim()
        if (u.isEmpty()) return
        scope.launch {
            runCatching { cloud.remoteNavigate(id, u) }
                .onSuccess { urlField = ""; message = "已导航到 $u" }
                .onFailure { message = "导航失败：${it.message}" }
            delay(300); refreshShot()
            runCatching { cloud.remoteUrl(id) }.onSuccess { currentUrl = it.url }
        }
    }

    fun sendScroll(deltaY: Double) {
        val id = activeId ?: return
        scope.launch {
            runCatching { cloud.remoteScroll(id, 0.0, deltaY) }
                .onFailure { message = "滚动失败：${it.message}" }
            delay(120); refreshShot()
        }
    }

    /** 唤起键盘：请求输入框焦点并主动弹出输入法（Android 默认不会自动弹出时使用）。 */
    fun invokeKeyboard() {
        runCatching { inputFocus.requestFocus() }
        keyboard?.show()
        message = "已请求弹出键盘；如未弹出请再点一次"
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().padding(12.dp)
    ) {
        item {
            Column {
                Text("云端浏览器登录", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "二维码 / Agent 账号密码登录优先，远程键鼠标底；云端长期复用登录态",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (activeId != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("正在登录：$activeName", style = MaterialTheme.typography.titleMedium, color = HmclIndigo)
                        Spacer(Modifier.height(8.dp))

                        // ---- 二维码优先提示条 ----
                        if (qrAvailable) {
                            Surface(
                                color = HmclIndigo.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            ) {
                                Text(
                                    "已识别二维码登录入口：优先用手机 App 扫描画面中的二维码（自动刷新）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = HmclIndigo,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        // ---- 远程屏幕（观察 / 触控板操控）----
                        BoxWithConstraints(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(VIEWPORT_W / VIEWPORT_H)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black)
                        ) {
                            val w = constraints.maxWidth.toFloat()
                            val scale = VIEWPORT_W / w
                            val bmp = shot
                            if (bmp != null && w > 0f) {
                                Box(Modifier.fillMaxSize()) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = "远程屏幕",
                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (manualMode) {
                                        // 触控板光标叠加层（视口坐标 → 屏幕坐标）
                                        val cursorPx = with(density) { 24.dp.toPx().roundToInt() }
                                        Box(
                                            Modifier
                                                .offset {
                                                    IntOffset(
                                                        (cursorX / scale).roundToInt() - cursorPx / 2,
                                                        (cursorY / scale).roundToInt() - cursorPx / 2
                                                    )
                                                }
                                                .size(24.dp)
                                                .border(2.dp, HmclIndigo, CircleShape)
                                                .background(HmclIndigo.copy(alpha = 0.20f), CircleShape)
                                        )
                                        // 触控板手势层：拖动 = 移动光标；轻点 = 光标处左键点击
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .pointerInput(activeId) {
                                                    detectDragGestures(
                                                        onDrag = { change, dragAmount ->
                                                            change.consume()
                                                            cursorX = (cursorX + dragAmount.x * scale)
                                                                .coerceIn(0.0, VIEWPORT_W.toDouble())
                                                            cursorY = (cursorY + dragAmount.y * scale)
                                                                .coerceIn(0.0, VIEWPORT_H.toDouble())
                                                        }
                                                    )
                                                }
                                                .pointerInput(activeId) {
                                                    detectTapGestures {
                                                        sendClick(cursorX, cursorY)
                                                    }
                                                }
                                        )
                                    }
                                }
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (manualMode) "触控板模式：屏幕上拖动移动光标，轻点=左键点击；「唤起键盘」弹出输入法"
                            else "观察模式：画面每 2.5 秒自动刷新；切换「手动远程控制」后可操作",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // ---- Agent 智能登录表单（主入口）----
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = agentUser, onValueChange = { agentUser = it },
                            label = { Text("账号（手机号/邮箱/用户名）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = agentPass, onValueChange = { agentPass = it },
                                label = { Text("密码") }, singleLine = true,
                                visualTransformation =
                                    if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = { passVisible = !passVisible }) {
                                Text(if (passVisible) "隐藏" else "显示")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { agentLogin() },
                            enabled = !busy && agentUser.isNotBlank() && agentPass.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "Agent 处理中…" else "Agent 智能登录") }

                        // ---- 手动远程控制（保底方案，主动唤起）----
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { manualMode = !manualMode },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (manualMode) "收起手动远程控制" else "手动远程控制（触控板 + 虚拟键鼠）") }

                        if (manualMode) {
                            // ---- 当前 URL + 导航 ----
                            Text(
                                "当前：$currentUrl",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = urlField, onValueChange = { urlField = it },
                                    label = { Text("网址") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = { sendNavigate() }, enabled = !busy) { Text("打开") }
                            }

                            // ---- 文本输入 + 唤起键盘 ----
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = text, onValueChange = { text = it },
                                    label = { Text("输入文本") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { sendType() }),
                                    modifier = Modifier.weight(1f).focusRequester(inputFocus)
                                )
                                Button(onClick = { sendType() }, enabled = !busy) { Text("发送") }
                            }
                            OutlinedButton(
                                onClick = { invokeKeyboard() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("唤起键盘") }

                            Spacer(Modifier.height(8.dp))
                            Text("特殊键", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            KeyRow(listOf("Enter" to "Enter", "Tab" to "Tab", "⌫" to "Backspace", "Esc" to "Escape"), !busy) { sendKey(it) }
                            Spacer(Modifier.height(4.dp))
                            KeyRow(listOf("↑" to "ArrowUp", "↓" to "ArrowDown", "←" to "ArrowLeft", "→" to "ArrowRight"), !busy) { sendKey(it) }

                            Spacer(Modifier.height(8.dp))
                            // ---- 滚动 + 刷新 ----
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(onClick = { sendScroll(-360.0) }, enabled = !busy) { Text("↑ 滚") }
                                OutlinedButton(onClick = { sendScroll(360.0) }, enabled = !busy) { Text("↓ 滚") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { refreshShot() }, enabled = !busy) { Text("刷新画面") }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        if (message.isNotBlank()) {
                            Text(message, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { completeLogin(false) }, enabled = !busy) {
                                Text(if (busy) "处理中…" else "校验登录")
                            }
                            OutlinedButton(onClick = { completeLogin(true) }, enabled = !busy) {
                                Text("强制保存会话")
                            }
                            OutlinedButton(onClick = { cancelLogin() }, enabled = !busy) { Text("取消") }
                        }
                    }
                }
            }
        } else if (message.isNotBlank()) {
            item {
                Text(message, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        if (loading && sessions.isEmpty()) {
            item { Text("加载中…", modifier = Modifier.padding(top = 8.dp)) }
            return@LazyColumn
        }
        error?.let {
            item {
                Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        item {
            Text("平台列表", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        }

        items(sessions) { s ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                !s.loginRequired -> "无需登录（公开内容即可采集）"
                                s.loggedIn -> "状态：已登录 ✓"
                                else -> "状态：未登录（浏览器通道暂不可用）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (s.loggedIn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (s.loginRequired) {
                        if (s.loggedIn) {
                            OutlinedButton(
                                onClick = { startLogin(s) },
                                enabled = activeId == null && !busy
                            ) { Text("重新登录") }
                        } else {
                            Button(
                                onClick = { startLogin(s) },
                                enabled = activeId == null && !busy
                            ) { Text("登录") }
                        }
                    }
                }
            }
        }
    }
}

/** 一行等宽特殊键按钮。 */
@Composable
private fun KeyRow(
    keys: List<Pair<String, String>>,
    enabled: Boolean,
    onKey: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        keys.forEach { (label, key) ->
            OutlinedButton(
                onClick = { onKey(key) },
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) { Text(label) }
        }
    }
}

private const val VIEWPORT_W = 1366f
private const val VIEWPORT_H = 900f

private fun decodePng(b64: String): androidx.compose.ui.graphics.ImageBitmap? =
    runCatching { decodeImageBitmap(Base64.decode(b64)) }.getOrNull()
