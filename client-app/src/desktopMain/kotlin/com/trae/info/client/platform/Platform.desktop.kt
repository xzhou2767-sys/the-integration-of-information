package com.trae.info.client.platform

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.prefs.Preferences

/**
 * 桌面端（Windows / Linux / macOS，x86_64 与 ARM64）实际实现。
 */
actual object Platform {
    actual val type: PlatformType = PlatformType.DESKTOP
    actual val isLargeScreen: Boolean = true
    actual val defaultServerUrl: String = "http://127.0.0.1:8765"

    private val prefs: Preferences = Preferences.userRoot().node("/com/trae/info/client")

    actual fun getServerUrl(): String = prefs.get("serverUrl", defaultServerUrl)

    actual fun setServerUrl(url: String) {
        prefs.put("serverUrl", url)
        prefs.flush()
    }

    actual fun getFavoritesJson(): String = prefs.get("favorites", "")

    actual fun setFavoritesJson(json: String) {
        prefs.put("favorites", json)
        prefs.flush()
    }

    actual fun getReadIdsJson(): String = prefs.get("readIds", "")

    actual fun setReadIdsJson(json: String) {
        prefs.put("readIds", json)
        prefs.flush()
    }

    actual fun getFeedCacheJson(): String = prefs.get("feedCache", "")

    actual fun setFeedCacheJson(json: String) {
        prefs.put("feedCache", json)
        prefs.flush()
    }

    actual fun formatTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

    actual fun openUrl(url: String) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            }
        } catch (e: Throwable) {
            System.err.println("无法打开链接：$url（${e.message}）")
        }
    }
}

actual fun platformHttpEngine(): HttpClientEngineFactory<*> = OkHttp
