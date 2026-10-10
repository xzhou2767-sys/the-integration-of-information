package com.trae.info.client.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.trae.info.client.App

/**
 * 桌面入口：Windows / Linux / macOS（x86_64、ARM64 同一产物）。
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Trae 信息整合平台",
        state = rememberWindowState(width = 1280.dp, height = 820.dp)
    ) {
        App()
    }
}
