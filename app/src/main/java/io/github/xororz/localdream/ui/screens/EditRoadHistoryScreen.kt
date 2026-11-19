package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.xororz.localdream.data.HistoryFilter
import io.github.xororz.localdream.data.HistoryItem
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.utils.saveImageFromFile
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地历史：只放“修好的结果”。上传的原图不会出现在这里（407 起已从写入端去掉，
 * 旧数据在读取时过滤掉）。每张卡片都能直接「保存到相册」或「删除」。
 * 完全离线，没有云端上传/登录入口。
 */
@Composable
fun EditRoadHistoryScreen(bottomPadding: Dp = 104.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { HistoryManager(context) }
    val raw by remember {
        manager.observeRecent(HistoryFilter(), 800)
    }.collectAsState(initial = emptyList())
    // 历史里绝不出现原图：写入端已不再写，这里再把老数据里 prompt = "上传原图" 的滤掉。
    val items = remember(raw) { raw.filterNot { it.params.prompt.startsWith("上传原图") } }

    var preview by remember { mutableStateOf<HistoryItem?>(null) }
    var deleteTarget by remember { mutableStateOf<HistoryItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("历史")
                        Text(
                            "共 ${items.size} 张 · 全部保存在本机",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "还没有修图记录。\n去首页下载模型，再上传图片开始修图。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomPadding + 24.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { preview = item },
                    ) {
                        AsyncImage(
                            model = item.imageFile,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        // 底部操作条：保存 / 删除，不用点开大图也能处理。
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color(0xCC000000))
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            saveImageFromFile(
                                                context,
                                                item.imageFile,
                                                onSuccess = {
                                                    android.widget.Toast.makeText(context, "已保存到相册", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onError = { msg ->
                                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                                },
                                            )
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            ) {
                                Text("保存", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = { deleteTarget = item },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            ) {
                                Text("删除", color = Color(0xFFFF8A80), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    preview?.let { item ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("修图结果") },
            text = {
                Column {
                    AsyncImage(
                        model = item.imageFile,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(380.dp),
                        contentScale = ContentScale.Fit,
                    )
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    Text(
                        "${item.params.width} × ${item.params.height} · " +
                            fmt.format(Date(item.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!item.params.prompt.startsWith("上传原图") && item.params.prompt.isNotBlank()) {
                        Text(
                            item.params.prompt,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 3,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        saveImageFromFile(
                            context,
                            item.imageFile,
                            onSuccess = {
                                android.widget.Toast.makeText(context, "已保存到相册", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onError = { msg ->
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            },
                        )
                    }
                }) { Text("保存到相册") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        preview = null
                        deleteTarget = item
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { preview = null }) { Text("关闭") }
                }
            },
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这张记录？") },
            text = { Text("只删除本机历史，不会影响已保存到相册的图片。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = item
                    deleteTarget = null
                    scope.launch {
                        manager.deleteHistoryItem(target)
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}
