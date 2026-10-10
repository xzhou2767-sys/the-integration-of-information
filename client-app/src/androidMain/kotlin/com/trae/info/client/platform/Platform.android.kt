package com.trae.info.client.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.trae.info.client.android.AppContext
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual object Platform {
    actual val type: PlatformType = PlatformType.ANDROID
    actual val isLargeScreen: Boolean = false
    // 安卓模拟器访问宿主机回环地址；真机部署时在设置页改为云端服务器地址
    actual val defaultServerUrl: String = "http://10.0.2.2:8765"

    private val prefs
        get() = AppContext.app.getSharedPreferences("trae-info", Context.MODE_PRIVATE)

    actual fun getServerUrl(): String =
        prefs.getString("serverUrl", defaultServerUrl) ?: defaultServerUrl

    actual fun setServerUrl(url: String) {
        prefs.edit().putString("serverUrl", url).apply()
    }

    actual fun getFavoritesJson(): String = prefs.getString("favorites", "") ?: ""

    actual fun setFavoritesJson(json: String) {
        prefs.edit().putString("favorites", json).apply()
    }

    actual fun getReadIdsJson(): String = prefs.getString("readIds", "") ?: ""

    actual fun setReadIdsJson(json: String) {
        prefs.edit().putString("readIds", json).apply()
    }

    actual fun getFeedCacheJson(): String = prefs.getString("feedCache", "") ?: ""

    actual fun setFeedCacheJson(json: String) {
        prefs.edit().putString("feedCache", json).apply()
    }

    actual fun formatTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

    actual fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            AppContext.app.startActivity(intent)
        } catch (e: Throwable) {
            Log.w("Platform", "无法打开链接：$url（${e.message}）")
        }
    }
}

actual fun platformHttpEngine(): HttpClientEngineFactory<*> = OkHttp
