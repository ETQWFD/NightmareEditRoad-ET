package io.github.xororz.localdream.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.xororz.localdream.data.EditRoadPrefs
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.ModelRepository

private const val TAB_HOME = "home"
private const val TAB_HISTORY = "history"
private const val TAB_EDIT = "edit"
private const val TAB_SETTINGS = "settings"

/**
 * 四个常驻页面：模型（下载 / 导入）、历史、修改、设置。
 * 所有页面同时保留在组合树里，只切换可见层：修图生成过程中切到任意页都不会中断；
 * 切回“修改”时仍停在原来的进度上。CPU 压力 / 线程面板从设置页进入，作为覆盖层，
 * 不占用底部 Dock 的四个位置。
 */
@Composable
fun EditRoadShell() {
    val context = LocalContext.current
    val repository = remember { ModelRepository.getInstance(context) }

    LaunchedEffect(Unit) { repository.ensureLoaded() }

    val models = repository.models
    val editableModels = remember(models) {
        models.filter { it.isDownloaded && it.runOnCpu && !it.isSdxl && !it.isDit }
            .distinctBy { it.id }
            .sortedBy { it.name }
    }

    var tab by rememberSaveable { mutableStateOf(TAB_HOME) }
    var selectedId by rememberSaveable { mutableStateOf(EditRoadPrefs.lastModelId(context)) }
    var restored by rememberSaveable { mutableStateOf(false) }
    var showCpu by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(repository.isLoaded, editableModels.size) {
        if (!repository.isLoaded) return@LaunchedEffect
        if (selectedId != null && editableModels.none { it.id == selectedId }) {
            selectedId = null
        }
        if (!restored) {
            restored = true
            val last = EditRoadPrefs.lastModelId(context)
            if (last != null && editableModels.any { it.id == last }) selectedId = last
        }
    }

    val selectedModel = editableModels.firstOrNull { it.id == selectedId }

    BackHandler(enabled = showCpu) { showCpu = false }
    BackHandler(enabled = tab != TAB_HOME) { tab = TAB_HOME }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize()) {
            KeepAliveLayer(visible = tab == TAB_HOME) {
                EditRoadHomeScreen(
                    onOpenModel = { model ->
                        selectedId = model.id
                        EditRoadPrefs.setLastModelId(context, model.id)
                        tab = TAB_EDIT
                    },
                    bottomPadding = 104.dp,
                )
            }

            KeepAliveLayer(visible = tab == TAB_HISTORY) {
                EditRoadHistoryScreen(bottomPadding = 104.dp)
            }

            KeepAliveLayer(visible = tab == TAB_EDIT) {
                if (selectedModel != null) {
                    EditScreen(
                        model = selectedModel,
                        availableModels = editableModels,
                        onSwitchModel = { chosen ->
                            selectedId = chosen.id
                            EditRoadPrefs.setLastModelId(context, chosen.id)
                        },
                        onBack = {
                            selectedId = null
                            tab = TAB_HOME
                        },
                        active = tab == TAB_EDIT,
                        bottomPadding = 104.dp,
                    )
                } else {
                    EmptyEditState(onPick = { tab = TAB_HOME })
                }
            }

            KeepAliveLayer(visible = tab == TAB_SETTINGS) {
                EditRoadSettingsScreen(
                    onOpenCpu = { showCpu = true },
                    bottomPadding = 104.dp,
                )
            }

            KeepAliveLayer(visible = showCpu) {
                CpuPressureScreen(active = true, bottomPadding = 104.dp)
            }

            // 画笔选区面板画在最上层（zIndex 3 > Dock 的 2），工具栏不会被 Dock 挡住。
            val paint by EditBatchController.paint.collectAsState()
            paint?.let { session ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(3f),
                ) {
                    InpaintScreen(
                        originalBitmap = session.bitmap,
                        existingMaskBitmap = session.preview,
                        existingPathHistory = session.history,
                        onInpaintComplete = { maskBase64, _, maskBmp, history ->
                            EditBatchController.completePaint(
                                context, session.path, maskBase64, maskBmp, history,
                            )
                        },
                        onCancel = { EditBatchController.closePaint() },
                    )
                }
            }

            GlassDock(
                tab = tab,
                onTab = { tab = it; if (it != TAB_SETTINGS) showCpu = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
                    .zIndex(2f),
            )
        }
    }
}

@Composable
private fun EmptyEditState(onPick: () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xF2FFFFFF),
            border = BorderStroke(1.dp, Color(0x66FFFFFF)),
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("还没有选中模型", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "先到“模型”页下载或导入一个模型，点它就能进入修图。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("去模型页")
                }
            }
        }
    }
}

@Composable
private fun KeepAliveLayer(visible: Boolean, content: @Composable () -> Unit) {
    // 四页切换：淡入淡出 + 极轻微上浮，代替原本“瞬间跳页”。
    val anim by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "layerFade",
    )
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = anim
                scaleX = 0.985f + 0.015f * anim
                scaleY = 0.985f + 0.015f * anim
            }
            .zIndex(if (visible) 1f else 0f)
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
    ) {
        content()
    }
}

@Composable
private fun GlassDock(tab: String, onTab: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        // 白色页面上纯白 Dock 只靠阴影不够醒目：加一圈深色发丝描边 + 更高投影。
        color = Color(0xFDFFFFFF),
        border = BorderStroke(1.2.dp, Color(0x33000000)),
        shadowElevation = 24.dp,
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFCFFFFFF), Color(0xE8FFFFFF)),
                    ),
                )
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockTab(Icons.Default.CloudDownload, "模型", tab == TAB_HOME, Modifier.weight(1f)) {
                onTab(TAB_HOME)
            }
            DockTab(Icons.Default.History, "历史", tab == TAB_HISTORY, Modifier.weight(1f)) {
                onTab(TAB_HISTORY)
            }
            DockTab(Icons.Default.AutoFixHigh, "修改", tab == TAB_EDIT, Modifier.weight(1f)) {
                onTab(TAB_EDIT)
            }
            DockTab(Icons.Default.Settings, "设置", tab == TAB_SETTINGS, Modifier.weight(1f)) {
                onTab(TAB_SETTINGS)
            }
        }
    }
}

@Composable
private fun DockTab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 选中时轻微放大 + 底色渐入，按下时回弹一下：这就是“灵动”的来源，纯 Compose 动画，低配也不掉帧。
    val selectedAnim by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f),
        label = "dockSelect",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 520f),
        label = "dockPress",
    )
    Box(
        modifier = modifier.padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .graphicsLayer {
                    val s = (1f + 0.05f * selectedAnim) * pressScale
                    scaleX = s
                    scaleY = s
                }
                .clip(RoundedCornerShape(18.dp))
                .background(
                    lerp(
                        Color(0x00FFFFFF),
                        MaterialTheme.colorScheme.primaryContainer,
                        selectedAnim,
                    ),
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size((19 + 2 * selectedAnim).dp),
                tint = lerp(
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    MaterialTheme.colorScheme.primary,
                    selectedAnim,
                ),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                fontSize = 11.sp,
                maxLines = 1,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = lerp(
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    MaterialTheme.colorScheme.primary,
                    selectedAnim,
                ),
            )
        }
    }
}
