package io.github.xororz.localdream.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.data.EditRoadPrefs
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.service.ConvertManager
import io.github.xororz.localdream.service.ConvertService
import io.github.xororz.localdream.service.ModelDownloadService
import io.github.xororz.localdream.utils.UrlModelImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页只保留两件事：下载模型、进入本地修图。没有登录、额度、社区、聊天、音乐与图片上传。
 */
@Composable
fun EditRoadHomeScreen(
    onOpenModel: (Model) -> Unit,
    bottomPadding: Dp = 104.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ModelRepository.getInstance(context) }
    LaunchedEffect(Unit) { repository.ensureLoaded() }
    // 打开应用就恢复上次被系统打断的修图任务（队列/进度都在本机文件里）
    LaunchedEffect(Unit) { EditBatchController.attach(context) }

    val models = repository.models
    val downloadModels = remember(models) {
        models.filter { !it.isSdxl && !it.isDit && it.runOnCpu }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Model> { it.isDownloaded }.thenBy { it.name })
    }

    val downloadState by ModelDownloadService.downloadState.collectAsState()
    val convertState by ConvertManager.state.collectAsState()
    var confirmDownload by remember { mutableStateOf<Model?>(null) }
    // 正在下载时再点同一个模型：给出“停止 / 继续等待”，不再重复弹“是否开始下载”
    var cancelDownloadFor by remember { mutableStateOf<Model?>(null) }
    var showLocalImport by remember { mutableStateOf(false) }
    var showUrlImport by remember { mutableStateOf(false) }

    val busyModelId = when (val s = downloadState) {
        is ModelDownloadService.DownloadState.Downloading -> s.modelId
        is ModelDownloadService.DownloadState.Extracting -> s.modelId
        is ModelDownloadService.DownloadState.Converting -> s.modelId
        else -> null
    }
    val busyModelName = downloadModels.firstOrNull { it.id == busyModelId }?.name ?: busyModelId

    // 转换完成后必须重扫磁盘，导入的模型才会出现在列表与“修改”页里。
    LaunchedEffect(convertState) {
        if (convertState is ConvertManager.UiState.Success || convertState is ConvertManager.UiState.Failed) {
            repository.refreshAllModels()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 22.dp, bottom = bottomPadding + 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text(
                        "NERT",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "Nightmare Edit Road · 本地 AI 修图",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xF2FFFFFF),
                    border = BorderStroke(1.dp, Color(0x66FFFFFF)),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("已按机型配置好", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "默认 ${EditRoadPrefs.STEPS_DEFAULT} 步 · ${EditRoadPrefs.rendererLabel(context)} · 尺寸 / CFG / 重绘强度自动适配",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "先下载一个模型，点它就能直接进入修图；也可以直接点下面“生成”进入修改界面。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        val readyModel = downloadModels.firstOrNull { it.isDownloaded }
                        if (readyModel != null) {
                            Button(
                                onClick = { onOpenModel(readyModel) },
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("生成 · 进入修图（${readyModel.name}）", maxLines = 1)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("生成 · 需先下载或导入一个模型") }
                        }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xF2FFFFFF),
                    border = BorderStroke(1.dp, Color(0x66FFFFFF)),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("自己导入模型", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "不想用列表里的模型？可以直接导入手机里的 .safetensors / .ckpt 权重（本机自动转换），" +
                                "或粘贴 HuggingFace 链接让应用下载转换。导入的模型和下载的模型一样能修图、能切换。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { showLocalImport = true },
                                enabled = busyModelId == null && convertState !is ConvertManager.UiState.Running,
                                modifier = Modifier.weight(1f),
                            ) { Text("本地文件") }
                            OutlinedButton(
                                onClick = { showUrlImport = true },
                                modifier = Modifier.weight(1f),
                            ) { Text("链接导入") }
                        }
                    }
                }
            }

            if (convertState !is ConvertManager.UiState.Idle) {
                item {
                    ConvertStatusCard(
                        state = convertState,
                        onDismiss = { ConvertManager.idle() },
                        onRetry = { showLocalImport = true },
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("模型", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${downloadModels.count { it.isDownloaded }} 个已下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (repository.isLoaded && downloadModels.isEmpty()) {
                item {
                    Text(
                        "没有可用模型。请检查网络后重试，或在设置里切换下载源。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(downloadModels, key = { it.id }) { model ->
                ModelDownloadCard(
                    model = model,
                    state = downloadState,
                    onOpen = { onOpenModel(model) },
                    onDownload = {
                        when {
                            model.isDownloaded -> onOpenModel(model)
                            // 正在下载的这个模型再点一次：给出“停止 / 继续等待”，
                            // 而不是重复弹“是否开始下载”的确认框。
                            busyModelId == model.id -> cancelDownloadFor = model
                            else -> confirmDownload = model
                        }
                    },
                )
            }

            item {
                Text(
                    "所有修图都在本机离线完成，不会上传图片。历史在“历史”页，已完成的图会自动保存到手机相册。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }

    confirmDownload?.let { model ->
        val replacing = busyModelId != null && busyModelId != model.id
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text(if (replacing) "替换正在下载的模型？" else "下载模型") },
            text = {
                Column {
                    Text("${model.name} · 约 ${model.approximateSize}")
                    if (replacing) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "当前正在下载「${busyModelName ?: busyModelId}」。开始新下载会停止当前任务，已下载的临时文件不会保留。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "下载源：${EditRoadPrefs.downloadSourceLabel(context)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDownload = null
                    model.startDownload(context)
                }) { Text(if (replacing) "停止并下载" else "开始下载") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownload = null }) { Text("取消") }
            },
        )
    }

    cancelDownloadFor?.let { model ->
        val pct = when (val d = downloadState) {
            is ModelDownloadService.DownloadState.Downloading -> (d.progress * 100).toInt().coerceIn(0, 100)
            else -> 0
        }
        AlertDialog(
            onDismissRequest = { cancelDownloadFor = null },
            title = { Text("「${model.name}」正在下载") },
            text = {
                Column {
                    Text("当前进度 $pct%。继续等待会保留这次下载；停止后可以马上换别的模型下载。")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "下载源：${EditRoadPrefs.downloadSourceLabel(context)}（可在设置里切换）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    cancelDownloadFor = null
                    runCatching {
                        context.startService(
                            Intent(context, ModelDownloadService::class.java).apply {
                                action = ModelDownloadService.ACTION_CANCEL_DOWNLOAD
                            },
                        )
                    }
                    Toast.makeText(context, "已停止下载「${model.name}」", Toast.LENGTH_SHORT).show()
                }) { Text("停止下载") }
            },
            dismissButton = {
                TextButton(onClick = { cancelDownloadFor = null }) { Text("继续等待") }
            },
        )
    }

    if (showLocalImport) {
        CustomModelDialog(
            context,
            onDismiss = { showLocalImport = false },
            onModelAdded = { modelName, fileUri, clipSkip, _ ->
                showLocalImport = false
                io.github.xororz.localdream.utils.BatteryOptimization.ensureIgnoring(context)
                // 转换跑在 ConvertService 自己的作用域里，离开本页也不会中断。
                ConvertService.start(context, modelName, fileUri, clipSkip)
                Toast.makeText(context, "开始在本机转换「$modelName」，可继续下载其它模型", Toast.LENGTH_LONG).show()
            },
        )
    }

    if (showUrlImport) {
        UrlImportDialog(
            context = context,
            onDismiss = { showUrlImport = false },
            onConfirmed = { name, convertPath ->
                showUrlImport = false
                scope.launch {
                    val entry = withContext(Dispatchers.IO) {
                        UrlModelImport.addEntry(context, name, convertPath)
                    }
                    repository.refreshAllModels()
                    io.github.xororz.localdream.utils.BatteryOptimization.ensureIgnoring(context)
                    val created = repository.models.firstOrNull { it.id == entry.id }
                    if (created != null) {
                        created.startDownload(context)
                        Toast.makeText(context, "已加入下载：${created.name}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "链接已保存，但模型创建失败，请重试", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }
}

/** 本机模型转换状态卡：导入 .safetensors/.ckpt 时的进度、成功、失败都在这里。 */
@Composable
private fun ConvertStatusCard(state: ConvertManager.UiState, onDismiss: () -> Unit, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xF2FFFFFF),
        border = BorderStroke(1.dp, Color(0x66FFFFFF)),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            when (state) {
                is ConvertManager.UiState.Preparing -> StatusRow("准备转换…", null, 0f)
                is ConvertManager.UiState.Running -> StatusRow(state.stage, null, state.percent / 100f)
                is ConvertManager.UiState.Finalizing -> StatusRow("正在写入模型文件…", null, 1f)
                is ConvertManager.UiState.Success -> {
                    StatusRow("转换完成", "模型已出现在下面的列表里，点它就能直接进入修图。", 1f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDismiss) { Text("知道了") }
                    }
                }

                is ConvertManager.UiState.Failed -> {
                    StatusRow("转换失败", state.reason, 0f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry) { Text("重新导入") }
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                    state.logPath?.let { path ->
                        Text(
                            "日志：$path",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun StatusRow(title: String, detail: String?, progress: Float) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    if (detail != null) {
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(6.dp),
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ModelDownloadCard(
    model: Model,
    state: ModelDownloadService.DownloadState,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    val downloading = (state as? ModelDownloadService.DownloadState.Downloading)
        ?.takeIf { it.modelId == model.id }
    val extracting = (state as? ModelDownloadService.DownloadState.Extracting)?.takeIf { it.modelId == model.id }
    val converting = (state as? ModelDownloadService.DownloadState.Converting)?.takeIf { it.modelId == model.id }
    val error = (state as? ModelDownloadService.DownloadState.Error)?.takeIf { it.modelId == model.id }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xF2FFFFFF),
        border = BorderStroke(1.dp, Color(0x66FFFFFF)),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (model.isDownloaded) onOpen() else onDownload()
            },
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        if (model.isDownloaded) Icons.Default.CheckCircle else Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp).size(21.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${model.approximateSize} · ${model.description.take(42)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (model.isDownloaded) {
                    Button(onClick = onOpen) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("生成")
                    }
                } else if (downloading == null && extracting == null && converting == null) {
                    TextButton(onClick = onDownload) { Text("下载") }
                }
            }

            if (downloading != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { downloading.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    Text(
                        "${(downloading.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${formatBytes(downloading.downloadedBytes)} / ${formatBytes(downloading.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (extracting != null || converting != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (extracting != null) "正在解压模型…" else (converting?.message ?: "正在转换模型…"),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "下载失败 · 点此重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

