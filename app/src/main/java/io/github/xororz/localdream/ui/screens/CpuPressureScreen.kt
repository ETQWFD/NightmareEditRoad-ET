package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import io.github.xororz.localdream.service.BackgroundGenerationService
import io.github.xororz.localdream.utils.DeviceCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * CPU 压力 / 性能页。
 *
 * 这里不是“退出再重进”的独立 Activity：它和修图页同时常驻在 AppShell 里，
 * 切到本页时生成服务继续跑，切回修改页能看到原来的进度。
 */
@Composable
fun CpuPressureScreen(active: Boolean, bottomPadding: Dp = 104.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val batch by EditBatchController.state.collectAsState()

    var usage by remember { mutableFloatStateOf(0f) }
    var freqText by remember { mutableStateOf("") }
    var tempC by remember { mutableFloatStateOf(0f) }
    var threads by remember { mutableIntStateOf(EditRoadPrefs.cpuThreads(context)) }
    var testing by remember { mutableStateOf(false) }
    var score by remember { mutableLongStateOf(0L) }

    val allCores = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }
    val bigCores = remember { runCatching { DeviceCapabilities.bigCoreCount() }.getOrDefault(allCores) }
    val effectiveThreads = if (threads in 1..allCores) threads else bigCores

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var prev = readCpuTimes()
        while (true) {
            delay(1000)
            val now = readCpuTimes()
            val dTotal = now[0] - prev[0]
            val dIdle = now[1] - prev[1]
            if (dTotal > 0) usage = ((dTotal - dIdle).toFloat() / dTotal).coerceIn(0f, 1f)
            prev = now
            freqText = readFreqText(allCores)
            tempC = readTemperature()
        }
    }

    val failureTitle = batch.failure?.title
    val generationText = when {
        batch.running -> "修图中 ${(batch.progress * 100).toInt()}%（${(batch.done + 1).coerceAtMost(batch.total)}/${batch.total} 张）"
        failureTitle != null -> "上次失败：$failureTitle"
        batch.results.isNotEmpty() -> "空闲（上次完成 ${batch.results.size} 张）"
        else -> "当前空闲"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CPU 压力")
                        Text(
                            "$allCores 核 · 自动使用 $bigCores 个大核 · 当前 $generationText",
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
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("实时 CPU 占用", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text("${(usage * 100).toInt()}%", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { usage },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (freqText.isNotBlank()) freqText else "正在读取频率…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (tempC > 0f) {
                        Text(
                            "电池/机身温度约 ${"%.1f".format(tempC)} ℃",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("引擎线程", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "自动 = 大核数（$bigCores）。手动加线程可能更快，但更热、更费电。修改后下一次启动引擎生效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        val choices = buildList {
                            add(0 to "自动($bigCores)")
                            addAll(
                                listOf(2, 4, 6, 8, bigCores, allCores)
                                    .distinct()
                                    .filter { it in 1..allCores && it != bigCores }
                                    .sorted()
                                    .map { it to "$it 线程" },
                            )
                        }
                        choices.forEach { (value, label) ->
                            FilterChip(
                                selected = threads == value,
                                onClick = {
                                    threads = value
                                    EditRoadPrefs.setCpuThreads(context, value)
                                },
                                label = { Text(label, maxLines = 1) },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "当前生效：$effectiveThreads 线程",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            item {
                GlassCard {
                    Text("压力测试", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "用 $effectiveThreads 个线程做 6 秒纯计算，检查高负载下是否稳定。测试不会停止正在进行的修图。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            if (!testing) {
                                testing = true
                                score = 0L
                                scope.launch {
                                    val result = withContext(Dispatchers.Default) {
                                        runCpuBenchmark(effectiveThreads, 6000L)
                                    }
                                    score = result
                                    testing = false
                                }
                            }
                        },
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("测试中…")
                        } else {
                            Text("开始 6 秒压力测试")
                        }
                    }
                    if (score > 0L) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "测试通过 · 计算量 ${score / 1000} K ops / 6s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            item {
                Text(
                    "低配机建议保持“自动”，引擎会优先吃大核；中高配可把线程数调高。修图时切到本页或历史页都不会中断后台生成。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun GlassCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xF2FFFFFF),
        border = BorderStroke(1.dp, Color(0x66FFFFFF)),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

private fun readCpuTimes(): LongArray {
    return runCatching {
        val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return LongArray(2)
        val parts = line.trim().split(Regex("\\s+"))
        var total = 0L
        var idle = 0L
        parts.drop(1).forEachIndexed { index, value ->
            val v = value.toLongOrNull() ?: return@forEachIndexed
            total += v
            if (index == 3 || index == 4) idle += v // idle + iowait
        }
        longArrayOf(total, idle)
    }.getOrDefault(LongArray(2))
}

private fun readFreqText(cores: Int): String {
    val freqs = (0 until cores).mapNotNull { i ->
        runCatching {
            File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                .readText().trim().toLong() / 1024L
        }.getOrNull()
    }
    if (freqs.isEmpty()) return ""
    val max = freqs.maxOrNull() ?: return ""
    val min = freqs.minOrNull() ?: return ""
    return "核心频率 ${min}–${max} MHz"
}

private fun readTemperature(): Float {
    return runCatching {
        File("/sys/class/thermal").listFiles()
            ?.filter { it.name.startsWith("thermal_zone") }
            ?.mapNotNull { zone ->
                runCatching {
                    val raw = File(zone, "temp").readText().trim().toFloat()
                    if (raw > 1000f) raw / 1000f else raw
                }.getOrNull()
            }
            ?.filter { it in 10f..120f }
            ?.maxOrNull()
            ?: 0f
    }.getOrDefault(0f)
}

private suspend fun runCpuBenchmark(threads: Int, millis: Long): Long = coroutineScope {
    val end = System.nanoTime() + millis * 1_000_000L
    (1..threads.coerceIn(1, 32)).map { seed ->
        async(Dispatchers.Default) {
            var x = seed.toLong() * 2654435761L + 1
            var ops = 0L
            while (System.nanoTime() < end) {
                repeat(4096) {
                    x = x * 6364136223846793005L + 1442695040888963407L
                    x = x xor (x ushr 33)
                }
                ops += 4096
            }
            ops
        }
    }.awaitAll().sum()
}
