package com.trae.info.client.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * HMCL 启动器视觉基准色：默认主题 Indigo #5C6BC0（Material Design Indigo 400），
 * 辅以 HMCL 界面中出现的 Google Blue #4285F4 作为强调色。
 */
val HmclIndigo = Color(0xFF5C6BC0)
private val HmclIndigoDark = Color(0xFF3949AB)
private val HmclIndigoLight = Color(0xFF9FA8DA)
private val IndigoContainer = Color(0xFFE8EAF6)
private val HmclAccentBlue = Color(0xFF4285F4)

private val LightColors = lightColorScheme(
    primary = HmclIndigo,
    onPrimary = Color.White,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = Color(0xFF1A237E),
    secondary = HmclAccentBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E3FD),
    onSecondaryContainer = Color(0xFF0B3D91),
    tertiary = Color(0xFF7986CB),
    background = Color(0xFFFAFAFE),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EAF2)
)

private val DarkColors = darkColorScheme(
    primary = HmclIndigoLight,
    onPrimary = Color(0xFF101840),
    primaryContainer = HmclIndigoDark,
    onPrimaryContainer = IndigoContainer,
    secondary = Color(0xFF90CAF9),
    onSecondary = Color(0xFF062A4E),
    secondaryContainer = Color(0xFF1E4E8C),
    onSecondaryContainer = Color(0xFFD3E3FD),
    tertiary = Color(0xFFB0B8E8),
    background = Color(0xFF12131A),
    surface = Color(0xFF1A1B24),
    surfaceVariant = Color(0xFF2A2C3A)
)

@Composable
fun TraeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Android 12+ Monet 动态取色方案（跟随壁纸）；null 或桌面端回退 HMCL 靛蓝基准主题。 */
    dynamicColorScheme: ColorScheme? = null,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = dynamicColorScheme ?: if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
