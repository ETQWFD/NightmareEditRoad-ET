package io.github.xororz.localdream.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.data.EditRoadPrefs
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.utils.LocaleManager
import kotlinx.coroutines.launch

private const val NERT_QQ = "2416444244"
private const val NERT_GROUP = "1078799843"
private const val NERT_GROUP_URL =
    "http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=FEhaN_bPJIhurqBLoRhqkhmHnkRz17bu" +
        "&authKey=hYuEEJTFOcfkPr85OD8vyrhHH56GhhUKRYuIfEpEDIkdclfxVTEKMV0IMYrz5HFB" +
        "&noverify=0&group_code=1078799843"

@Composable
fun EditRoadSettingsScreen(bottomPadding: Dp = 104.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var steps by remember { mutableIntStateOf(EditRoadPrefs.steps(context)) }
    var renderer by remember { mutableStateOf(EditRoadPrefs.renderer(context)) }
    var language by remember { mutableStateOf(currentLanguage(context)) }
    var source by remember { mutableStateOf(EditRoadPrefs.downloadSource(context)) }
    var showContact by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("设置")
                        Text(
                            "NERT · Nightmare Edit Road · v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsCard(Icons.Default.Tune, "修图步数") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "默认 40 步，可调 10–100。步数越高细节越多，速度越慢。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text("$steps", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = steps.toFloat(),
                        onValueChange = {
                            steps = it.toInt().coerceIn(EditRoadPrefs.STEPS_MIN, EditRoadPrefs.STEPS_MAX)
                            EditRoadPrefs.setSteps(context, steps)
                        },
                        valueRange = EditRoadPrefs.STEPS_MIN.toFloat()..EditRoadPrefs.STEPS_MAX.toFloat(),
                        steps = 17,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(20, 40, 60, 80, 100).forEach { preset ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (steps == preset) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                },
                                modifier = Modifier.clickable {
                                    steps = preset
                                    EditRoadPrefs.setSteps(context, preset)
                                },
                            ) {
                                Text(
                                    "$preset",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingsCard(Icons.Default.Settings, "渲染器") {
                    Text(
                        "自动：能用 GPU 就优先 GPU，失败自动回 CPU。低配机建议 CPU。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    listOf(
                        EditRoadPrefs.RENDERER_AUTO to "自动（推荐）",
                        EditRoadPrefs.RENDERER_CPU to "CPU（兼容 / 省电）",
                        EditRoadPrefs.RENDERER_OPENCL to "GPU · OpenCL",
                        EditRoadPrefs.RENDERER_VULKAN to "GPU · Vulkan（天玑更稳）",
                    ).forEach { (key, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                renderer = key
                                EditRoadPrefs.setRenderer(context, key)
                            }.padding(vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = renderer == key,
                                onClick = {
                                    renderer = key
                                    EditRoadPrefs.setRenderer(context, key)
                                },
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item {
                SettingsCard(Icons.Default.Language, "语言 / Language") {
                    Text(
                        "切换后会自动重启界面；模型名称与提示词不受影响。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    listOf(
                        LocaleManager.ZH_CN to "简体中文",
                        LocaleManager.ZH_TW to "繁體中文",
                        LocaleManager.EN to "English",
                    ).forEach { (code, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (code == language) return@clickable
                                language = code
                                LocaleManager.setLanguage(context, code)
                                LocaleManager.applyToApplication(context, code)
                                val activity = context.findActivity()
                                scope.launch {
                                    runCatching { ModelRepository.getInstance(context).refreshAllModels() }
                                    activity?.recreate()
                                }
                            }.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = language == code, onClick = {
                                if (code == language) return@RadioButton
                                language = code
                                LocaleManager.setLanguage(context, code)
                                LocaleManager.applyToApplication(context, code)
                                val activity = context.findActivity()
                                scope.launch {
                                    runCatching { ModelRepository.getInstance(context).refreshAllModels() }
                                    activity?.recreate()
                                }
                            })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item {
                SettingsCard(Icons.Default.CloudDownload, "模型下载源") {
                    Text(
                        "只影响从 HuggingFace 下载模型时优先走哪个站；自动会先走国内镜像再回官方。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    listOf(
                        EditRoadPrefs.SOURCE_AUTO to "自动测速（推荐）",
                        EditRoadPrefs.SOURCE_MIRROR to "国内镜像 hf-mirror",
                        EditRoadPrefs.SOURCE_OFFICIAL to "官方 HuggingFace",
                    ).forEach { (key, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                source = key
                                EditRoadPrefs.setDownloadSource(context, key)
                            }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = source == key,
                                onClick = {
                                    source = key
                                    EditRoadPrefs.setDownloadSource(context, key)
                                },
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item {
                SettingsCard(Icons.Default.Groups, "联系方式 / 加群") {
                    SettingsLinkRow(
                        title = "作者 QQ",
                        subtitle = NERT_QQ,
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("QQ", NERT_QQ))
                            Toast.makeText(context, "QQ 号已复制", Toast.LENGTH_SHORT).show()
                        },
                    )
                    SettingsLinkRow(
                        title = "加入 QQ 群",
                        subtitle = "群号 $NERT_GROUP",
                        onClick = { showContact = true },
                    )
                }
            }

            item {
                SettingsCard(Icons.Default.Info, "检查更新") {
                    Text(
                        "当前版本 v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { showUpdate = true }) {
                        Text("检查更新")
                    }
                }
            }

            item {
                Text(
                    "本应用不包含账号、额度、社区、聊天、音乐与图片上传；所有修图都在本机完成。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    if (showContact) {
        AlertDialog(
            onDismissRequest = { showContact = false },
            title = { Text("加入 QQ 群") },
            text = {
                Column {
                    Text("群号：$NERT_GROUP")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "点“打开 QQ”会调起手机 QQ；失败时会复制加群链接。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showContact = false
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(NERT_GROUP_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("qq_group", NERT_GROUP_URL))
                        Toast.makeText(context, "已复制加群链接，请在浏览器打开", Toast.LENGTH_LONG).show()
                    }
                }) { Text("打开 QQ") }
            },
            dismissButton = {
                TextButton(onClick = { showContact = false }) { Text("关闭") }
            },
        )
    }

    if (showUpdate) {
        UpdateDialog(onDismiss = { showUpdate = false })
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xF2FFFFFF),
        border = BorderStroke(1.dp, Color(0x66FFFFFF)),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SettingsLinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    }
}

private fun currentLanguage(context: Context): String {
    val saved = LocaleManager.getSavedLanguage(context)
    if (saved != LocaleManager.SYSTEM) return saved
    val lang = context.resources.configuration.locales.get(0)?.language ?: "en"
    return if (lang == "zh") LocaleManager.ZH_CN else LocaleManager.EN
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
