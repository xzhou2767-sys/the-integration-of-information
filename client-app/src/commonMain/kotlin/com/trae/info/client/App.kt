package com.trae.info.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.trae.info.client.api.CloudApi
import com.trae.info.client.platform.Platform
import com.trae.info.client.theme.TraeTheme
import com.trae.info.client.ui.AdminScreen
import com.trae.info.client.ui.FavoritesScreen
import com.trae.info.client.ui.FeedScreen
import com.trae.info.client.ui.HomeScreen
import com.trae.info.client.ui.MoreScreen
import com.trae.info.client.ui.PreferenceScreen
import com.trae.info.client.ui.ProxyScreen
import com.trae.info.client.ui.SettingsScreen
import com.trae.info.client.ui.SessionsScreen
import com.trae.info.client.ui.SubscriptionScreen

enum class AppScreen(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Filled.Home),
    SUBSCRIBE("订阅定制", Icons.Filled.List),
    FEED("信息流", Icons.Filled.Article),
    FAVORITES("收藏夹", Icons.Filled.Star),
    PREFERENCE("信息偏好", Icons.Filled.Interests),
    MORE("更多", Icons.Filled.MoreHoriz),

    // ---- 二级页面：云端相关功能，收进「更多」菜单 ----
    SESSIONS("云端登录", Icons.Filled.Home),
    PROXY("云端代理", Icons.Filled.Home),
    ADMIN("云端管理", Icons.Filled.Home),
    SETTINGS("设置", Icons.Filled.Home)
}

/** 底部导航栏 / 侧边栏展示的一级页面。 */
private val primaryScreens = listOf(
    AppScreen.HOME, AppScreen.SUBSCRIBE, AppScreen.FEED,
    AppScreen.FAVORITES, AppScreen.PREFERENCE, AppScreen.MORE
)

/** 二级页面（云端功能），进入时显示返回栏。 */
private val subScreens = setOf(
    AppScreen.SESSIONS, AppScreen.PROXY, AppScreen.ADMIN, AppScreen.SETTINGS
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(dynamicColorScheme: ColorScheme? = null) {
    TraeTheme(dynamicColorScheme = dynamicColorScheme) {
        var current by remember { mutableStateOf(AppScreen.HOME) }
        // 云端地址变化后通过重建 CloudApi 生效（设置页保存后返回即生效）
        var apiEpoch by remember { mutableStateOf(0) }
        val cloud = remember(apiEpoch) { CloudApi(Platform.getServerUrl()) }
        val onServerChanged: () -> Unit = { apiEpoch++ }

        @Composable
        fun Content() {
            when (current) {
                AppScreen.HOME -> HomeScreen(cloud, onNavigate = { current = it })
                AppScreen.SUBSCRIBE -> SubscriptionScreen(cloud)
                AppScreen.FEED -> FeedScreen(cloud)
                AppScreen.FAVORITES -> FavoritesScreen()
                AppScreen.PREFERENCE -> PreferenceScreen(cloud)
                AppScreen.MORE -> MoreScreen(cloud, onOpen = { current = it })
                AppScreen.SESSIONS -> SubScreen("云端登录", onBack = { current = AppScreen.MORE }) { SessionsScreen(cloud) }
                AppScreen.PROXY -> SubScreen("云端代理", onBack = { current = AppScreen.MORE }) { ProxyScreen(cloud) }
                AppScreen.ADMIN -> SubScreen("云端管理", onBack = { current = AppScreen.MORE }) { AdminScreen(cloud) }
                AppScreen.SETTINGS -> SubScreen("设置", onBack = { current = AppScreen.MORE }) { SettingsScreen(cloud, onServerChanged) }
            }
        }

        // 二级页面高亮「更多」入口
        val selectedForNav = if (current in subScreens) AppScreen.MORE else current

        if (Platform.isLargeScreen) {
            // 桌面端（Windows / Linux / macOS）：左侧 NavigationRail 大屏布局
            Row(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                NavigationRail {
                    primaryScreens.forEach { screen ->
                        NavigationRailItem(
                            selected = selectedForNav == screen,
                            onClick = { current = screen },
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) }
                        )
                    }
                }
                Box(Modifier.fillMaxSize()) { Content() }
            }
        } else {
            // 安卓端：底部 NavigationBar
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (current in subScreens) current.label else "Trae 信息整合平台") },
                        navigationIcon = {
                            if (current in subScreens) {
                                IconButton(onClick = { current = AppScreen.MORE }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                            }
                        }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        primaryScreens.forEach { screen ->
                            NavigationBarItem(
                                selected = selectedForNav == screen,
                                onClick = { current = screen },
                                icon = { Icon(screen.icon, contentDescription = screen.label) },
                                label = { Text(screen.label) }
                            )
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { Content() }
            }
        }
    }
}

/** 二级页面容器：顶部返回栏 + 页面内容。 */
@Composable
private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) { content() }
}
