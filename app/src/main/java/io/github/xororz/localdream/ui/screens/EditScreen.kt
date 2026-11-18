package io.github.xororz.localdream.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.xororz.localdream.data.Model

/**
 * 修图页。所有实际执行都在 [EditBatchController] 里（不依赖 Compose 生命周期），
 * 本页只负责展示队列、进度、预计时间与故障日志窗口：
 *  - 切到历史 / CPU压力 / 设置，甚至退回首页，生成都不会中断；
 *  - 失败时弹出带完整日志的窗口，可以“复制日志”或“关闭”；
 *  - 顶部点模型名可以在已下载/已导入的模型之间切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    model: Model,
    availableModels: List<Model>,
    onSwitchModel: (Model) -> Unit,
    onBack: () -> Unit,
    active: Boolean,
    bottomPadding: Dp = 104.dp,
    initialImagePath: String = "",
) {
    val context = LocalContext.current
    val rootView = LocalView.current
    val s by EditBatchController.state.collectAsState()
    var showModelPicker by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    val running = s.running
    LaunchedEffect(Unit) { EditBatchController.refreshConfig(context) }
    LaunchedEffect(active) { if (active) EditBatchController.refreshConfig(context) }

    DisposableEffect(active, running) {
        rootView.keepScreenOn = active || running
        onDispose { rootView.keepScreenOn = false }
    }

    fun exit() {
        if (running) showExitConfirm = true else onBack()
    }
    BackHandler(enabled = active) { exit() }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (!uris.isNullOrEmpty()) EditBatchController.pickImages(context, uris, append = true)
    }

    LaunchedEffect(initialImagePath, s.queue.isEmpty()) {
        if (initialImagePath.isNotBlank() && s.queue.isEmpty() && !running) {
            val bmp = BitmapFactory.decodeFile(initialImagePath)
            if (bmp != null) {
                EditBatchController.pickImages(
                    context,
                    listOf(android.net.Uri.fromFile(java.io.File(initialImagePath))),
                    append = false,
                )
            }
        }
    }

    LaunchedEffect(s.note) {
        if (s.note != null) {
            kotlinx.coroutines.delay(6000)
            EditBatchController.clearNote()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.clickable {
                            if (running) {
                                Toast.makeText(context, "修图完成前不能切换模型", Toast.LENGTH_SHORT).show()
                            } else if (availableModels.size > 1) {
                                showModelPicker = true
                            } else {
                                Toast.makeText(context, "只有一个可用模型，去首页下载或导入更多", Toast.LENGTH_SHORT)
                                    .show()
                            }
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(model.name, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "切换模型",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            "${s.steps} 步 · ${s.rendererLabel} · ${if (s.gpu) "GPU" else "CPU"} · 点模型名可切换",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { exit() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = bottomPadding + 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 主预览 ----------
            val preview: Bitmap? = s.preview
            val last = s.results.lastOrNull()
            when {
                preview != null -> PreviewBox(title = "实时预览 · ${(s.progress * 100).toInt()}%") {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "修图实时预览",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }

                last != null -> PreviewBox(title = "最新结果 ${last.params.width}×${last.params.height}") {
                    AsyncImage(
                        model = last.imageFile,
                        contentDescription = "修图结果",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }

                s.queue.firstOrNull { it.readable }?.thumb != null ->
                    PreviewBox(title = if (s.queue.size > 1) "待修 ${s.queue.size} 张 · 第 1 张" else "待修图片") {
                        Image(
                            bitmap = s.queue.first { it.readable }.thumb!!.asImageBitmap(),
                            contentDescription = "待修图片",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }

                else -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .clickable(enabled = !running && !s.loadingImages) { pickLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(if (s.loadingImages) "正在读取图片…" else "点击选择图片（可多选）")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { pickLauncher.launch("image/*") },
                    enabled = !running && !s.loadingImages,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (s.queue.isEmpty()) "选择图片" else "再选几张")
                }
                if (s.queue.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { EditBatchController.clearImages(context) },
                        enabled = !running,
                    ) { Text("清空") }
                }
            }

            // ---------- 队列 ----------
            if (s.queue.isNotEmpty()) {
                Text(
                    "已选 ${s.queue.size} 张 · 按顺序逐张修图" +
                        if (s.queue.any { !it.readable }) "（${s.queue.count { !it.readable }} 张读不出，会跳过）" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                    items(s.queue, key = { it.uri.toString() }) { item ->
                        Box(modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp))) {
                            if (item.thumb != null) {
                                Image(
                                    bitmap = item.thumb.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.errorContainer) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("损坏", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            if (!running) {
                                Surface(
                                    color = Color(0xAA000000),
                                    shape = RoundedCornerShape(bottomStart = 10.dp),
                                    modifier = Modifier.align(Alignment.TopEnd),
                                ) {
                                    IconButton(
                                        onClick = { EditBatchController.removeImage(item.uri) },
                                        modifier = Modifier.size(22.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "移除",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 提示语 ----------
            OutlinedTextField(
                value = s.prompt,
                onValueChange = { EditBatchController.setPrompt(it) },
                label = { Text("提示语（中文 / English 都可以）") },
                placeholder = { Text("例如：把背景换成海边，保留人物长相") },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
            )
            Text(
                "只写要改成什么就行；尺寸、CFG、重绘强度、防畸形反向词都已按机型配好。" +
                    if (s.prompt.isBlank()) " 留空则只做画质增强，几乎不动人物。" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---------- 配置摘要 ----------
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "输出长边 ${s.canvasSize} · ${s.steps} 步 · ${s.rendererLabel}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        "预计每张约 ${humanSeconds(EditBatchController.perImageEstimateMs())}" +
                            if (s.msPerStep > 0) "（已按实测校准）" else "（首次估算，跑完一张后自动校准）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---------- 主按钮 ----------
            Button(
                onClick = { EditBatchController.start(context, model) },
                enabled = !running && !s.loadingImages && s.queue.any { it.readable },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        running -> "修图中…"
                        s.queue.isEmpty() -> "请先选择图片"
                        else -> "开始修图（${s.queue.count { it.readable }} 张）"
                    },
                )
            }

            if (running) {
                LinearProgressIndicator(
                    progress = { EditBatchController.overallProgress() },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row {
                        Text(
                            s.stage.ifBlank { "修图中…" },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "本张 ${(s.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Row {
                        Text(
                            "第 ${(s.done + 1).coerceAtMost(s.total)} / ${s.total} 张 · 总进度 ${(EditBatchController.overallProgress() * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            EditBatchController.etaText(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { EditBatchController.stop(context) }) { Text("停止本次修图") }
                Text(
                    "可以切到“历史 / CPU压力 / 设置”，修图不会中断，回来仍是原来的进度。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            s.note?.let {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                }
            }

            s.failure?.let { f ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLog = true },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(
                                f.detail,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 3,
                            )
                        }
                        TextButton(onClick = { showLog = true }) { Text("查看日志") }
                    }
                }
            }

            if (s.results.isNotEmpty()) {
                Text("本次结果（已自动存入相册与历史）", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(s.results, key = { it.id }) { item ->
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        ) {
                            AsyncImage(
                                model = item.imageFile,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // ---------- 切换模型 ----------
    if (showModelPicker) {
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = { Text("切换模型") },
            text = {
                Column {
                    Text(
                        "已下载 / 已导入 ${availableModels.size} 个可用模型。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(availableModels, key = { it.id }) { candidate ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showModelPicker = false
                                        if (candidate.id != model.id) onSwitchModel(candidate)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = candidate.id == model.id,
                                    onClick = {
                                        showModelPicker = false
                                        if (candidate.id != model.id) onSwitchModel(candidate)
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(candidate.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${candidate.approximateSize} · ${if (candidate.isCustom) "导入模型" else "下载模型"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModelPicker = false }) { Text("关闭") } },
        )
    }

    // ---------- 停止确认 ----------
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("正在修图，确定结束？") },
            text = { Text("结束会停止本次批量；已完成的图片仍然保存在相册和历史里。你也可以直接切到别的标签页，生成不会中断。") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    EditBatchController.stop(context)
                    onBack()
                }) { Text("停止并退出") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("继续修") }
            },
        )
    }

    // ---------- 故障日志窗口：可复制、可关闭 ----------
    s.failure?.let { f ->
        if (showLog) {
            AlertDialog(
                onDismissRequest = { showLog = false },
                title = { Text("${f.title} · 日志") },
                text = {
                    Column {
                        Text(f.detail, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth().height(320.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(10.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    f.log,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("NERT日志", f.detail + "\n\n" + f.log))
                            Toast.makeText(context, "日志已复制，可直接粘贴发给我", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("复制日志")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLog = false }) { Text("关闭") }
                },
            )
        }
    }
}

@Composable
private fun PreviewBox(title: String, content: @Composable () -> Unit) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun scaleToCanvas(src: Bitmap, longEdge: Int): Bitmap {
    val edge = maxOf(src.width, src.height).coerceAtLeast(1)
    val scale = longEdge.toFloat() / edge.toFloat()
    var w = (src.width * scale).toInt().coerceAtLeast(64)
    var h = (src.height * scale).toInt().coerceAtLeast(64)
    w = (w / 8) * 8
    h = (h / 8) * 8
    if (w == src.width && h == src.height) return src
    return Bitmap.createScaledBitmap(src, w, h, true)
}
