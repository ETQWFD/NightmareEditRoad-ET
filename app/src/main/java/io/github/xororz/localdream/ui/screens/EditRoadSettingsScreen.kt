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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
fun EditRoadSettingsScreen(
    bottomPadding: Dp = 104.dp,
    onOpenCpu: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var steps by remember { mutableIntStateOf(EditRoadPrefs.steps(context)) }
    var strength by remember { mutableFloatStateOf(EditRoadPrefs.strength(context)) }
    var negative by remember { mutableStateOf(EditRoadPrefs.negativePrompt(context)) }
    var autocomplete by remember { mutableStateOf(EditRoadPrefs.autocompleteEnabled(context)) }
    var quality by remember { mutableStateOf(EditRoadPrefs.quality(context)) }
    val nativeEdge = remember {
        io.github.xororz.localdream.utils.DeviceCapabilities.sd15LongEdgeForRam(context)
    }
    val extremeLowEnd = remember {
        io.github.xororz.localdream.utils.DeviceCapabilities.extremeLowRam(context)
    }
    var forceParams by remember {
        mutableStateOf(
            context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                .getBoolean("force_generate", false),
        )
    }
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
                SettingsCard(Icons.Default.Brush, "重绘强度") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "改不动就调高，只想微调画质就调低。用画笔圈出区域时会自动按最高强度重画那一块。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${(strength * 100).toInt()}%",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = strength,
                        onValueChange = {
                            strength = it
                            EditRoadPrefs.setStrength(context, it)
                        },
                        valueRange = EditRoadPrefs.STRENGTH_MIN..EditRoadPrefs.STRENGTH_MAX,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.30f to "只微调", 0.45f to "保守", 0.55f to "推荐", 0.70f to "大改").forEach { (v, name) ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (kotlin.math.abs(strength - v) < 0.01f) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                },
                                modifier = Modifier.clickable {
                                    strength = v
                                    EditRoadPrefs.setStrength(context, v)
                                },
                            ) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingsCard(Icons.Default.Star, "画质（输出分辨率）") {
                    Text(
                        "引擎按机型原生尺寸（本机约 ${nativeEdge}px）生成，再放大到你选的成片分辨率。" +
                            "手机上不可能原生跑 4K 扩散，4K 属于放大输出；要细节就用高清模型或多跑几遍。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            EditRoadPrefs.QUALITY_1K to "1K",
                            EditRoadPrefs.QUALITY_2K to "2K",
                            EditRoadPrefs.QUALITY_3K to "3K",
                            EditRoadPrefs.QUALITY_4K to "4K",
                        ).forEach { (key, label) ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (quality == key) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                },
                                modifier = Modifier.clickable {
                                    quality = key
                                    EditRoadPrefs.setQuality(context, key)
                                    EditBatchController.refreshConfig(context)
                                },
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "当前成片：${EditRoadPrefs.qualityLabel(context)}（长边 ${EditRoadPrefs.outputEdge(context)}px）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            item {
                SettingsCard(Icons.Default.Block, "反推理（负面提示语）") {
                    Text(
                        "写在这里，模型会尽量避开这些内容。畸形人物、血腥、丑陋这三条底线是强制生效的，" +
                            "这里清空也不会失效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = negative,
                        onValueChange = {
                            negative = it
                            EditRoadPrefs.setNegativePrompt(context, it)
                        },
                        label = { Text("负面提示语") },
                        placeholder = { Text("例如：glasses, hat, watermark") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            negative = EditRoadPrefs.DEFAULT_NEGATIVE
                            EditRoadPrefs.setNegativePrompt(context, EditRoadPrefs.DEFAULT_NEGATIVE)
                        }) { Text("恢复默认") }
                        TextButton(onClick = {
                            negative = ""
                            EditRoadPrefs.setNegativePrompt(context, "")
                        }) { Text("清空（底线仍生效）") }
                    }
                }
            }

            item {
                SettingsCard(Icons.Default.TextFields, "提示语联想") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "在修改页输入时给出中文词与英文标签候选，点一下就接上去。关闭后输入框更清爽。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = autocomplete,
                            onCheckedChange = {
                                autocomplete = it
                                EditRoadPrefs.setAutocompleteEnabled(context, it)
                            },
                        )
                    }
                }
            }

            if (onOpenCpu != null) {
                item {
                    SettingsCard(Icons.Default.Memory, "CPU 压力 / 线程") {
                        SettingsLinkRow(
                            title = "实时占用 · 引擎线程 · 压力测试",
                            subtitle = "打开性能面板：查看 CPU 占用与频率、调整引擎线程数",
                            onClick = { onOpenCpu?.invoke() },
                        )
                    }
                }
            }

            // 32 位 / 内存紧张的机型里，引擎会把长边压到 256、步数最多 20（防 SIGKILL）。
            // 这里把这层保护显式说明清楚，并允许用户自己决定是否强制按上面的参数跑。
            if (extremeLowEnd) {
                item {
                    SettingsCard(Icons.Default.Memory, "低配机保护") {
                        Text(
                            "本机是 32 位 / 内存较小的机型。为避免跑到一半被系统杀掉，" +
                                "引擎会把输出长边压到 256、步数最多 20。" +
                                "开启下面这项可以强制按你设置的步数与尺寸跑，但可能中途闪退。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "强制按上面的参数跑",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = forceParams,
                                onCheckedChange = { on ->
                                    forceParams = on
                                    context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                                        .edit()
                                        .putBoolean("force_generate", on)
                                        .putBoolean("force_generate_confirmed", true)
                                        .apply()
                                },
                            )
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
