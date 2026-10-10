package com.trae.info.client.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import com.trae.info.client.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.init(applicationContext)
        setContent {
            // Android 12+ 动态取色（Monet）：跟随壁纸取色；低版本回退 HMCL 靛蓝主题（硬约束基准色 #5C6BC0）
            val dark = isSystemInDarkTheme()
            val dynamic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else null
            App(dynamicColorScheme = dynamic)
        }
    }
}
