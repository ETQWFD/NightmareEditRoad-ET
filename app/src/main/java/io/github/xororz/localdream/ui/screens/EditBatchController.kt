package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.cloud.LogHub
import io.github.xororz.localdream.data.EditRoadPrefs
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.data.HistoryItem
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.BackgroundGenerationService
import io.github.xororz.localdream.util.ChinesePrompt
import io.github.xororz.localdream.util.ImageDiagnostics
import io.github.xororz.localdream.utils.DeviceCapabilities
import io.github.xororz.localdream.utils.EnginePort
import io.github.xororz.localdream.utils.LogCapture
import io.github.xororz.localdream.utils.saveImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Date

/**
 * 修图批量任务控制器。
 *
 * 关键点：runner 挂在**自己的** CoroutineScope 上，不挂在 Compose 的
 * rememberCoroutineScope 上。因此
 *  - 切到历史 / CPU压力 / 设置、甚至退回首页或锁屏，任务都不会中断；
 *  - 不会再出现「rememberCoroutineScope left the composition」；
 *  - 进度、预计时间、结果统一由这里维护，界面只是订阅 [state]；
 *  - 任何失败都会带上引擎日志 / logcat / 模型目录清单，界面弹可复制的窗口。
 */
object EditBatchController {

    data class QueueItem(val uri: Uri, val thumb: Bitmap?, val readable: Boolean)

    data class Failure(val title: String, val detail: String, val log: String)

    data class State(
        val queue: List<QueueItem> = emptyList(),
        val prompt: String = "",
        val loadingImages: Boolean = false,
        val running: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val progress: Float = 0f,
        val preview: Bitmap? = null,
        val stage: String = "",
        val startedAt: Long = 0L,
        val currentStartedAt: Long = 0L,
        val lastProgressAt: Long = 0L,
        val msPerStep: Long = 0L,
        val steps: Int = EditRoadPrefs.STEPS_DEFAULT,
        val canvasSize: Int = 384,
        val rendererLabel: String = "",
        val gpu: Boolean = false,
        val modelName: String = "",
        val results: List<HistoryItem> = emptyList(),
        val note: String? = null,
        val failure: Failure? = null,
    )

    private const val TAG = "EditBatch"
    private const val PREFS = "app_prefs"
    private const val KEY_MS_PER_STEP = "editroad_ms_per_step"

    private const val MAX_IMAGES = 16
    private const val MAX_EDGE = 1536
    private const val THUMB_EDGE = 480
    private const val CFG = 7f
    private const val SCHEDULER = "dpm"
    private const val DENOISE = 0.45f
    private const val DENOISE_BLANK = 0.30f

    private const val ENGINE_READY_MS = 200_000L
    private const val FIRST_STEP_MS = 150_000L
    private const val PER_IMAGE_MS = 40L * 60L * 1000L
    private const val STALL_MS = 20L * 60L * 1000L

    private const val DEFAULT_POSITIVE =
        "masterpiece, best quality, highly detailed, sharp focus, correct anatomy, natural lighting"

    /** 内置防畸形反向词，用户不需要也不能自己填。 */
    private const val ANTI_DEFORM_NEGATIVE =
        "lowres, worst quality, low quality, blurry, jpeg artifacts, bad anatomy, bad hands, " +
            "missing fingers, extra fingers, fused fingers, extra limbs, missing limbs, " +
            "malformed limbs, mutated hands, poorly drawn hands, poorly drawn face, deformed face, " +
            "disfigured, mutated, extra eyes, cross-eyed, long neck, watermark, text, signature, " +
            "username, logo, cropped, out of frame"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var appRef: Context? = null

    // ------------------------------------------------------------------ 配置

    fun refreshConfig(context: Context) {
        appRef = context.applicationContext
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.update {
            it.copy(
                steps = EditRoadPrefs.steps(context),
                rendererLabel = EditRoadPrefs.rendererLabel(context),
                gpu = EditRoadPrefs.wantsGpu(context),
                canvasSize = DeviceCapabilities.sd15LongEdgeForRam(context),
                msPerStep = sp.getLong(KEY_MS_PER_STEP, 0L),
            )
        }
    }

    fun setPrompt(value: String) {
        _state.update { it.copy(prompt = value) }
    }

    fun dismissFailure() {
        _state.update { it.copy(failure = null) }
    }

    fun clearNote() {
        _state.update { it.copy(note = null) }
    }

    // ------------------------------------------------------------------ 队列

    fun pickImages(context: Context, uris: List<Uri>, append: Boolean) {
        val app = context.applicationContext
        appRef = app
        val merged = (if (append) _state.value.queue.map { it.uri } + uris else uris).distinct()
        if (merged.isEmpty()) return
        val limited = merged.take(MAX_IMAGES)
        val truncated = merged.size > limited.size
        _state.update { it.copy(loadingImages = true) }
        scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                limited.map { uri ->
                    val full = decodeSampledBitmap(app, uri, MAX_EDGE)
                    QueueItem(uri, full?.let { scaleToCanvas(it, THUMB_EDGE) }, full != null)
                }
            }
            _state.update {
                it.copy(
                    queue = decoded,
                    loadingImages = false,
                    note = when {
                        truncated -> "一次最多 $MAX_IMAGES 张，只保留了前 $MAX_IMAGES 张"
                        decoded.any { q -> !q.readable } ->
                            "有 ${decoded.count { q -> !q.readable }} 张读不出来，修图时会自动跳过"
                        else -> null
                    },
                )
            }
        }
    }

    fun removeImage(uri: Uri) {
        if (_state.value.running) return
        _state.update { it.copy(queue = it.queue.filterNot { q -> q.uri == uri }) }
    }

    fun clearImages(context: Context) {
        if (_state.value.running) return
        _state.update { it.copy(queue = emptyList(), results = emptyList(), note = null, preview = null) }
        refreshConfig(context)
    }

    // ------------------------------------------------------------------ 执行

    fun start(context: Context, model: Model) {
        val app = context.applicationContext
        appRef = app
        if (_state.value.running) return
        val items = _state.value.queue.filter { it.readable }.map { it.uri }
        if (items.isEmpty()) {
            _state.update { it.copy(note = "请先选择要修的图片（可多选）") }
            return
        }
        refreshConfig(app)
        _state.update { it.copy(failure = null, note = null) }
        job = scope.launch { runBatch(app, model, items, _state.value.prompt) }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        _state.update { it.copy(running = false, stage = "已停止") }
        runCatching { BackgroundGenerationService.stop(app) }
        job?.let { current ->
            job = null
            scope.launch { runCatching { current.cancelAndJoin() } }
        }
    }

    private suspend fun runBatch(app: Context, model: Model, items: List<Uri>, promptText: String) {
        val steps = EditRoadPrefs.steps(app)
        val useOpenCL = EditRoadPrefs.wantsGpu(app)
        val canvas = DeviceCapabilities.sd15LongEdgeForRam(app)
        val denoise = if (promptText.isBlank()) DENOISE_BLANK else DENOISE
        val history = HistoryManager(app)
        runCatching { LogCapture.start() }

        Log.i(TAG, "batch start model=${model.id} type=${model.backendType} images=${items.size} steps=$steps gpu=$useOpenCL canvas=$canvas")
        _state.update {
            it.copy(
                running = true,
                failure = null,
                total = items.size,
                done = 0,
                progress = 0f,
                preview = null,
                results = emptyList(),
                stage = "准备引擎…",
                startedAt = System.currentTimeMillis(),
                currentStartedAt = System.currentTimeMillis(),
                lastProgressAt = 0L,
                steps = steps,
                gpu = useOpenCL,
                canvasSize = canvas,
                modelName = model.name,
            )
        }

        var stopped = false
        var failed = false
        var finished = 0
        try {
            for ((index, uri) in items.withIndex()) {
                if (!_state.value.running) {
                    stopped = true
                    break
                }
                _state.update {
                    it.copy(
                        stage = "第 ${index + 1}/${items.size} 张：读取图片",
                        currentStartedAt = System.currentTimeMillis(),
                        progress = 0f,
                        lastProgressAt = 0L,
                    )
                }

                val decoded = withContext(Dispatchers.IO) { decodeSampledBitmap(app, uri, MAX_EDGE) }
                if (decoded == null) {
                    _state.update { it.copy(done = it.done + 1, note = "第 ${index + 1} 张读取失败，已跳过") }
                    continue
                }
                val scaled = scaleToCanvas(decoded, canvas)

                // 原图先入历史，用户在“历史”页就能看到自己上传过什么。
                runCatching {
                    history.saveGeneratedImage(
                        modelId = model.id,
                        bitmap = scaled,
                        params = GenerationParameters(
                            steps = steps,
                            cfg = CFG,
                            seed = null,
                            prompt = "上传原图",
                            negativePrompt = "",
                            generationTime = null,
                            width = scaled.width,
                            height = scaled.height,
                            runOnCpu = !useOpenCL,
                            denoiseStrength = 0f,
                            useOpenCL = useOpenCL,
                            scheduler = SCHEDULER,
                            mode = GenerationMode.EDIT,
                        ),
                        mode = GenerationMode.EDIT,
                    )
                }.onFailure { Log.w(TAG, "original history save failed", it) }

                val engineError = ensureEngineReady(app, model, scaled.width, scaled.height)
                if (engineError != null) {
                    reportFailure(model, "模型引擎无法加载", engineError, "第 ${index + 1} 张")
                    failed = true
                    break
                }
                if (!writeSourceImage(app, scaled)) {
                    reportFailure(
                        model,
                        "图片写入失败",
                        "无法写入 ${File(app.filesDir, "tmp.txt").absolutePath}（存储空间可能已满）",
                        "第 ${index + 1} 张",
                    )
                    failed = true
                    break
                }

                when (val outcome = generateOne(app, model, promptText, scaled, steps, denoise, useOpenCL)) {
                    is GenOutcome.Done -> {
                        val bitmap = outcome.bitmap
                        val elapsed = System.currentTimeMillis() - _state.value.currentStartedAt
                        calibrate(elapsed, steps)
                        _state.update { it.copy(stage = "第 ${index + 1} 张：保存到相册") }
                        withContext(Dispatchers.IO) {
                            runCatching {
                                saveImage(
                                    context = app,
                                    bitmap = bitmap,
                                    onSuccess = {},
                                    onError = { Log.w(TAG, "gallery save failed: $it") },
                                )
                            }
                        }
                        val item = withContext(Dispatchers.IO) {
                            runCatching {
                                history.saveGeneratedImage(
                                    modelId = model.id,
                                    bitmap = bitmap,
                                    params = GenerationParameters(
                                        steps = steps,
                                        cfg = CFG,
                                        seed = outcome.seed,
                                        prompt = promptText.ifBlank { DEFAULT_POSITIVE },
                                        negativePrompt = ANTI_DEFORM_NEGATIVE,
                                        generationTime = "${elapsed / 1000}s",
                                        width = bitmap.width,
                                        height = bitmap.height,
                                        runOnCpu = !useOpenCL,
                                        denoiseStrength = denoise,
                                        useOpenCL = useOpenCL,
                                        scheduler = SCHEDULER,
                                        mode = GenerationMode.IMG2IMG,
                                    ),
                                    mode = GenerationMode.IMG2IMG,
                                )
                            }.getOrNull()
                        }
                        if (item != null) {
                            _state.update { it.copy(results = (it.results + item).takeLast(8)) }
                        }
                        finished += 1
                        _state.update { it.copy(done = it.done + 1, progress = 0f, preview = null) }
                        BackgroundGenerationService.clearCompleteState()
                        delay(600)
                    }

                    GenOutcome.Cancelled -> {
                        stopped = true
                        break
                    }

                    is GenOutcome.Failed -> {
                        reportFailure(model, outcome.title, outcome.detail, "第 ${index + 1} 张")
                        failed = true
                        break
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            stopped = true
            Log.i(TAG, "batch cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "batch crashed", e)
            reportFailure(model, "修图异常", e.message ?: e.javaClass.simpleName, "批量任务")
            failed = true
        } finally {
            runCatching { LogCapture.stopAndPublish() }
            _state.update {
                it.copy(
                    running = false,
                    progress = if (failed || stopped) it.progress else 1f,
                    stage = when {
                        failed -> "未完成（见错误日志）"
                        stopped -> "已停止"
                        else -> "全部完成：$finished 张"
                    },
                    note = when {
                        failed -> it.note
                        stopped -> "已停止修图，已完成的部分仍在相册与历史里"
                        finished > 0 -> "$finished 张已自动保存到相册，可在“历史”里回看"
                        else -> it.note
                    },
                )
            }
            io.github.xororz.localdream.data.EditRoadPrefs.setLastModelId(app, model.id)
        }
    }

    // ------------------------------------------------------------------ 引擎

    /** 返回 null 表示引擎已就绪；否则返回可直接展示的失败原因。 */
    private suspend fun ensureEngineReady(app: Context, model: Model, width: Int, height: Int): String? {
        // 修图必须走 img2img；这个开关被关掉时引擎会忽略参考图，表现就是“改了等于没改”。
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("use_img2img", true)
            .apply()

        // 预检模型文件：缺文件时马上给出“能照着做”的解释，而不是让引擎静默失败、界面卡在 0%。
        val modelDir = File(Model.getModelsDir(app), model.id)
        if (!modelDir.isDirectory) {
            return "模型目录不存在：${modelDir.absolutePath}。可能更换过存储路径或目录被清理，" +
                "请回首页重新下载或重新导入「${model.name}」。"
        }
        val layoutOk = runCatching {
            Model.hasConvertedSd15Outputs(modelDir) ||
                Model.hasSd15CpuLayout(modelDir) ||
                File(modelDir, "finished").isFile ||
                File(modelDir, "model.finished").isFile
        }.getOrDefault(true)
        if (!layoutOk) {
            val listing = modelDir.listFiles()
                ?.sortedBy { f: File -> f.name }
                ?.joinToString(", ") { f: File -> "${f.name}(${f.length() / (1024 * 1024)}MB)" }
                ?: "空目录"
            return "「${model.name}」的模型文件不完整：CPU 修图需要 tokenizer.json / clip_v2.mnn / unet.mnn / " +
                "vae_decoder.mnn / pos_emb.bin / token_emb.bin（或已转换标记 finished）。当前目录里有：$listing。" +
                "请重新下载该模型，或重新导入一次权重文件。"
        }

        if (BackendService.servingModelId.value == model.id &&
            BackendService.backendState.value is BackendService.BackendState.Running &&
            portOpen(app)
        ) {
            Log.i(TAG, "engine already serving ${model.id}")
            return null
        }

        _state.update { it.copy(stage = "加载模型引擎（首次约 10–30 秒）…") }
        val startFailure = runCatching {
            app.startForegroundService(
                Intent(app, BackendService::class.java).apply {
                    putExtra("modelId", model.id)
                    putExtra("backendType", model.backendType)
                    putExtra("width", width)
                    putExtra("height", height)
                },
            )
        }.exceptionOrNull()
        if (startFailure != null) {
            Log.w(TAG, "start BackendService failed", startFailure)
            // 引擎如果本来就活着（例如上一张留下的进程），继续用没问题。
            if (portOpen(app) && BackendService.servingModelId.value == model.id) return null
            return "无法启动引擎服务：${startFailure.message}"
        }

        val deadline = System.currentTimeMillis() + ENGINE_READY_MS
        var lastSeen = ""
        while (System.currentTimeMillis() < deadline) {
            val bs = BackendService.backendState.value
            if (bs is BackendService.BackendState.Error) return bs.message
            val name = bs.javaClass.simpleName
            if (name != lastSeen) {
                lastSeen = name
                _state.update { it.copy(stage = "加载模型引擎…（$name）") }
            }
            if (BackendService.servingModelId.value == model.id && portOpen(app)) {
                Log.i(TAG, "engine ready model=${model.id} port=${EnginePort.activeEnginePort(app)}")
                return null
            }
            delay(500)
        }
        return "引擎在 ${ENGINE_READY_MS / 1000} 秒内没有就绪（状态 $lastSeen，端口 ${EnginePort.activeEnginePort(app)}）。" +
            "常见原因：模型文件不完整/损坏、该机型架构无法运行引擎、或内存不足被系统杀掉。"
    }

    private fun portOpen(app: Context): Boolean {
        val port = EnginePort.activeEnginePort(app)
        return runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 400) }
            true
        }.getOrDefault(false)
    }

    private fun writeSourceImage(app: Context, bitmap: Bitmap): Boolean = runCatching {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        FileOutputStream(File(app.filesDir, "tmp.txt")).use { it.write(b64.toByteArray()) }
        runCatching { File(app.filesDir, "mask.txt").takeIf { f -> f.exists() }?.delete() }
        true
    }.getOrDefault(false)

    private sealed class GenOutcome {
        data class Done(val bitmap: Bitmap, val seed: Long?) : GenOutcome()
        data class Failed(val title: String, val detail: String) : GenOutcome()
        object Cancelled : GenOutcome()
    }

    private suspend fun generateOne(
        app: Context,
        model: Model,
        promptText: String,
        source: Bitmap,
        steps: Int,
        denoise: Float,
        useOpenCL: Boolean,
    ): GenOutcome {
        BackgroundGenerationService.resetState()

        val positive = promptText.ifBlank { DEFAULT_POSITIVE }.trim()
        // 中文提示语：能联网翻译就先转英文标签；翻译失败就交给服务里的离线词典，
        // 绝不因为翻译接口挂掉而让整个修图失败。
        val translated = if (ChinesePrompt.hasChinese(positive)) {
            runCatching {
                withContext(Dispatchers.IO) { ChinesePrompt.translatePromptBest(positive) }
            }.getOrNull()?.takeIf { it.isNotBlank() && !ChinesePrompt.hasChinese(it) }
        } else {
            positive
        }

        val w = source.width
        val h = source.height
        val port = EnginePort.activeEnginePort(app)
        val intent = Intent(app, BackgroundGenerationService::class.java).apply {
            putExtra("prompt", translated ?: positive)
            putExtra("negative_prompt", ANTI_DEFORM_NEGATIVE)
            putExtra("steps", steps)
            putExtra("cfg", CFG)
            putExtra("width", w)
            putExtra("height", h)
            putExtra("effective_width", w)
            putExtra("effective_height", h)
            putExtra("denoise_strength", denoise)
            putExtra("use_opencl", useOpenCL)
            putExtra("scheduler", SCHEDULER)
            putExtra("aspect_ratio", ImageDiagnostics.aspectRatioFor(w, h))
            putExtra("has_image", true)
            putExtra("modelId", model.id)
            putExtra("backendType", model.backendType)
            putExtra("backend_host", "localhost:$port")
            putExtra("prompt_pretranslated", translated != null)
            putExtra("prompt_english_only", true)
        }
        _state.update { it.copy(stage = "修图中 ${w}×$h · $steps 步") }
        val startError = runCatching { app.startForegroundService(intent) }.exceptionOrNull()
        if (startError != null) {
            Log.e(TAG, "start generation failed", startError)
            return GenOutcome.Failed("无法启动生成服务", startError.message ?: startError.javaClass.simpleName)
        }

        val startedAt = System.currentTimeMillis()
        var sawStep = false
        while (true) {
            if (!_state.value.running) return GenOutcome.Cancelled

            when (val gs = BackgroundGenerationService.generationState.value) {
                is BackgroundGenerationService.GenerationState.Progress -> {
                    if (gs.progress > 0.001f) {
                        sawStep = true
                        _state.update {
                            it.copy(
                                progress = gs.progress.coerceIn(0f, 1f),
                                preview = gs.intermediateImage ?: it.preview,
                                lastProgressAt = System.currentTimeMillis(),
                            )
                        }
                    }
                }

                is BackgroundGenerationService.GenerationState.Complete -> {
                    BackgroundGenerationService.markBitmapConsumed()
                    return GenOutcome.Done(gs.bitmap, gs.seed)
                }

                is BackgroundGenerationService.GenerationState.Error -> {
                    return GenOutcome.Failed("修图失败", gs.message)
                }

                is BackgroundGenerationService.GenerationState.Idle -> Unit
            }

            val now = System.currentTimeMillis()
            if (!sawStep && now - startedAt > FIRST_STEP_MS) {
                return GenOutcome.Failed(
                    "引擎没有开始计算",
                    "端口已连通，但 ${FIRST_STEP_MS / 1000} 秒内没有收到任何一步进度。" +
                        "通常是模型权重与引擎类型不匹配、模型文件损坏，或内存不足把引擎杀掉了。",
                )
            }
            if (sawStep && _state.value.lastProgressAt > 0 &&
                now - _state.value.lastProgressAt > STALL_MS
            ) {
                return GenOutcome.Failed("修图卡住", "超过 20 分钟没有任何新步进，已中断本次任务。")
            }
            if (now - startedAt > PER_IMAGE_MS) {
                return GenOutcome.Failed("修图超时", "单张图片超过 40 分钟仍未完成，请降低步数或输出尺寸。")
            }
            delay(200)
        }
    }

    private fun reportFailure(model: Model, title: String, detail: String, where: String) {
        val log = runCatching { buildDiagnostics(model, where) }
            .getOrElse { "诊断信息收集失败：${it.message}" }
        _state.update {
            it.copy(
                failure = Failure(title, "$where：$detail", log),
                stage = "未完成",
                running = false,
            )
        }
        runCatching { BackgroundGenerationService.stop(appRef ?: return) }
        LogHub.log(LogHub.Category.GENERATE, "edit fail: $title / $where / ${detail.take(200)}")
    }

    // ------------------------------------------------------------------ 诊断日志

    private fun buildDiagnostics(model: Model, where: String): String {
        val ctx = appRef ?: return "诊断不可用（Context 丢失）"
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = _state.value
        val sb = StringBuilder()
        sb.appendLine("== NERT 修图故障日志 ==")
        sb.appendLine("时间：${Date()}  阶段：$where")
        sb.appendLine("版本：v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) flavor=${BuildConfig.FLAVOR}")
        sb.appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString()} · 进程位数=${if (android.os.Process.is64Bit()) "64" else "32"}")
        sb.appendLine("CPU：${Runtime.getRuntime().availableProcessors()} 核 · 大核 ${coreCountSafe()}")
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.appendLine("内存：可用 ${mi.availMem / 1048576}MB · 低内存=${mi.lowMemory} · 应用堆上限 ${Runtime.getRuntime().maxMemory() / 1048576}MB")
        }
        runCatching {
            val stat = android.os.StatFs(ctx.filesDir.absolutePath)
            sb.appendLine("存储：剩余 ${stat.availableBytes / 1073741824}GB / 共 ${stat.totalBytes / 1073741824}GB")
        }
        sb.appendLine("模型：${model.name} · id=${model.id} · type=${model.backendType} · ${model.approximateSize}")
        sb.appendLine("参数：${s.steps}步 CFG=$CFG ${if (s.gpu) "GPU" else "CPU"} 长边=${s.canvasSize} 渲染器=${s.rendererLabel}")
        sb.appendLine("线程：手动=${sp.getInt("cpu_threads", 0)}（0=自动） img2img=${sp.getBoolean("use_img2img", true)}")
        sb.appendLine("后端状态：${describeBackend()}")
        sb.appendLine("生成状态：${describeGeneration()}")
        sb.appendLine("引擎端口：${EnginePort.activeEnginePort(ctx)} · 连通=${if (portOpen(ctx)) "yes" else "no"}")

        val dir = File(Model.getModelsDir(ctx), model.id)
        sb.appendLine("模型目录：${dir.absolutePath} exists=${dir.exists()}")
        runCatching {
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                sb.appendLine("  ${f.name} ${if (f.isDirectory) "<dir>" else "${f.length()}B"}")
            }
        }
        val core = File(ctx.applicationInfo.nativeLibraryDir, "libstable_diffusion_core.so")
        sb.appendLine("引擎文件：${core.absolutePath} exists=${core.exists()} size=${if (core.exists()) core.length() else 0}")

        lastFileLog(ctx, "engine_", ".log")?.let { (name, text) ->
            sb.appendLine("---- $name（末 3000 字）----")
            sb.appendLine(text.takeLast(3000))
        }
        lastFileLog(ctx, "crash_", ".log")?.let { (name, text) ->
            sb.appendLine("---- $name（末 1500 字）----")
            sb.appendLine(text.takeLast(1500))
        }
        runCatching {
            val hub = LogHub.formatted(null)
            if (hub.isNotBlank()) {
                sb.appendLine("---- 运行事件（末 2000 字）----")
                sb.appendLine(hub.takeLast(2000))
            }
        }
        runCatching {
            val snap = LogCapture.snapshot()
            if (snap.isNotBlank()) {
                sb.appendLine("---- logcat（末 4000 字）----")
                sb.appendLine(snap.takeLast(4000))
            }
        }
        return sb.toString()
    }

    private fun coreCountSafe(): Int = runCatching { DeviceCapabilities.bigCoreCount() }.getOrDefault(0)

    private fun describeBackend(): String {
        val bs = BackendService.backendState.value
        val msg = (bs as? BackendService.BackendState.Error)?.message.orEmpty()
        val tail = " serving=${BackendService.servingModelId.value ?: "-"}"
        return bs.javaClass.simpleName + (if (msg.isNotBlank()) "（$msg）" else "") + tail
    }

    private fun describeGeneration(): String =
        when (val gs = BackgroundGenerationService.generationState.value) {
            is BackgroundGenerationService.GenerationState.Progress ->
                "Progress ${(gs.progress * 100).toInt()}% 预览=${gs.intermediateImage != null}"

            is BackgroundGenerationService.GenerationState.Complete ->
                "Complete ${gs.bitmap.width}×${gs.bitmap.height}"

            is BackgroundGenerationService.GenerationState.Error -> "Error ${gs.message}"
            else -> "Idle"
        }

    private fun lastFileLog(ctx: Context, prefix: String, suffix: String): Pair<String, String>? = runCatching {
        ctx.filesDir.listFiles { f: File -> f.name.startsWith(prefix) && f.name.endsWith(suffix) }
            ?.maxByOrNull { f: File -> f.lastModified() }
            ?.let { f: File -> f.name to f.readText() }
    }.getOrNull()

    // ------------------------------------------------------------------ 预计时间

    private fun calibrate(elapsedMs: Long, steps: Int) {
        if (steps <= 0 || elapsedMs <= 0) return
        val ctx = appRef ?: return
        val perStep = (elapsedMs / steps).coerceIn(80L, 6000L)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = sp.getLong(KEY_MS_PER_STEP, 0L)
        val merged = if (prev <= 0L) perStep else (prev * 3 + perStep) / 4
        sp.edit().putLong(KEY_MS_PER_STEP, merged).apply()
        _state.update { it.copy(msPerStep = merged) }
    }

    fun perImageEstimateMs(): Long {
        val s = _state.value
        val perStep = s.msPerStep.takeIf { it > 0 } ?: if (s.gpu) 420L else 1300L
        return perStep.coerceIn(150L, 6000L) * s.steps + 12_000L
    }

    /** 基于真实步进进度动态收敛的剩余时间，多张时同时给出总剩余。 */
    fun etaText(): String {
        val s = _state.value
        if (!s.running) return ""
        val now = System.currentTimeMillis()
        val elapsed = (now - s.currentStartedAt).coerceAtLeast(0L)
        val perImage = perImageEstimateMs()
        val thisRemaining = if (s.progress > 0.02f) {
            ((elapsed.toDouble() / s.progress) * (1.0 - s.progress)).toLong()
        } else {
            (perImage - elapsed).coerceAtLeast(0L)
        }
        val rest = (s.total - s.done - 1).coerceAtLeast(0)
        val allRemaining = thisRemaining + perImage * rest
        return if (s.total > 1) {
            "本张约剩 ${humanSeconds(thisRemaining)} · 全部约剩 ${humanSeconds(allRemaining)}"
        } else {
            "预计剩余约 ${humanSeconds(thisRemaining)}"
        }
    }

    fun overallProgress(): Float {
        val s = _state.value
        if (s.total <= 0) return 0f
        return ((s.done + s.progress).coerceAtMost(s.total.toFloat()) / s.total.toFloat())
    }
}

internal fun decodeSampledBitmap(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}.getOrNull()

internal fun humanSeconds(ms: Long): String {
    val sec = ((ms + 999L) / 1000L).coerceAtLeast(1L)
    return when {
        sec < 60 -> "${sec} 秒"
        sec < 3600 -> {
            val m = sec / 60
            val s = sec % 60
            if (s > 0) "$m 分 $s 秒" else "$m 分"
        }

        else -> "${sec / 3600} 小时 ${(sec % 3600) / 60} 分"
    }
}
