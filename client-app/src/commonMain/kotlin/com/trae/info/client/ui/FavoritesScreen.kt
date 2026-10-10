package com.trae.info.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trae.info.client.data.FavoritesStore
import com.trae.info.client.theme.HmclIndigo
import com.trae.info.core.model.ContentItem

/**
 * 收藏夹：本地持久化的星标条目（新收藏置顶）。
 * 点击实心星标取消收藏；点击卡片打开与信息流相同的详情弹层（弹层内亦可切换收藏）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen() {
    // FavoritesStore.items 为可观察状态，收藏/取消收藏后列表自动刷新
    val favorites = FavoritesStore.items
    var reader by remember { mutableStateOf<ContentItem?>(null) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "收藏夹（${favorites.size}）",
            style = MaterialTheme.typography.titleMedium,
            color = HmclIndigo
        )
        Text(
            "收藏保存在本机，点击星标取消收藏",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        if (favorites.isEmpty()) {
            Text(
                "暂无收藏。\n在信息流条目右上角点击空心星标即可收藏。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 80.dp)
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(favorites) { item ->
                    FeedItemCard(
                        item,
                        favorited = true,
                        onToggleFavorite = { FavoritesStore.toggle(item) }
                    ) { reader = item }
                }
            }
        }
    }

    val sheetState = rememberModalBottomSheetState()
    reader?.let { item ->
        ItemDetailSheet(item, sheetState, onDismiss = { reader = null })
    }
}
