package io.github.xororz.localdream.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

private const val TAB_HISTORY = "history"
private const val TAB_CPU = "cpu"
private const val TAB_EDIT = "edit"
private const val TAB_SETTINGS = "settings"

/**
 * 四个页面（历史 / CPU压力 / 修改 / 设置）始终同时存在于组合树里，只切换可见层。
 * 这样修图生成过程中切到任意页，BackgroundGenerationService 与修图页的进度状态
 * 都不会被销毁；切回“修改”时仍在原来的进度上继续。
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

    var tab by rememberSaveable { mutableStateOf(TAB_EDIT) }
    var selectedId by rememberSaveable { mutableStateOf(EditRoadPrefs.lastModelId(context)) }
    var restored by rememberSaveable { mutableStateOf(false) }

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

    BackHandler(enabled = tab != TAB_EDIT) {
        tab = TAB_EDIT
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize()) {
            KeepAliveLayer(visible = tab == TAB_HISTORY) {
                EditRoadHistoryScreen(bottomPadding = 104.dp)
            }

            KeepAliveLayer(visible = tab == TAB_CPU) {
                CpuPressureScreen(active = tab == TAB_CPU, bottomPadding = 104.dp)
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
                        onBack = { selectedId = null },
                        active = tab == TAB_EDIT,
                        bottomPadding = 104.dp,
                    )
                } else {
                    EditRoadHomeScreen(
                        onOpenModel = { model ->
                            selectedId = model.id
                            EditRoadPrefs.setLastModelId(context, model.id)
                            tab = TAB_EDIT
                        },
                        bottomPadding = 104.dp,
                    )
                }
            }

            KeepAliveLayer(visible = tab == TAB_SETTINGS) {
                EditRoadSettingsScreen(bottomPadding = 104.dp)
            }

            GlassDock(
                tab = tab,
                onTab = { tab = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun KeepAliveLayer(visible: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .alpha(if (visible) 1f else 0f)
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
        color = Color(0xF7FFFFFF),
        border = BorderStroke(1.dp, Color(0x80FFFFFF)),
        shadowElevation = 18.dp,
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
            DockTab(Icons.Default.History, "历史", tab == TAB_HISTORY, Modifier.weight(1f)) {
                onTab(TAB_HISTORY)
            }
            DockTab(Icons.Default.Memory, "CPU压力", tab == TAB_CPU, Modifier.weight(1f)) {
                onTab(TAB_CPU)
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
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color(0x00FFFFFF),
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                fontSize = 11.sp,
                maxLines = 1,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
