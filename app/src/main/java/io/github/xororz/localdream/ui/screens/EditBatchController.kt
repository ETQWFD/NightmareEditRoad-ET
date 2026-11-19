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
import io.github.xororz.localdream.service.EditKeepAliveService
import io.github.xororz.localdream.util.ChinesePrompt
import io.github.xororz.localdream.util.ImageDiagnostics
import io.github.xororz.localdream.utils.BatteryOptimization
import io.github.xororz.localdream.utils.DeviceCapabilities
import io.github.xororz.localdream.utils.EnginePort
import io.github.xororz.localdream.utils.LogCapture
import io.github.xororz.localdream.utils.saveImage
import org.json.JSONArray
import org.json.JSONObject
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
 * 设计要点（对应实机反馈的三类问题）：
 *  1. runner 挂在**自己的** CoroutineScope 上，不挂 Compose 的 rememberCoroutineScope，
 *     切页 / 回首页 / 锁屏都不会取消任务，也不会再出现
 *     「rememberCoroutineScope left the composition」。
 *  2. 队列保存为应用私有目录里的图片文件，并持久化一份任务清单（job.json）；
 *     修图期间由 [EditKeepAliveService] 维持前台优先级。熄屏或进程被系统回收后
 *     重新打开，队列、进度、结果都还在，并能从下一张接着跑 —— 解决“后台突然被删除”。
 *  3. 预计时间按 (模型 / 渲染器 / 输出尺寸 / 步数) 分档标定，只统计真实的步进耗时，
 *     并在跑起来后用实测进度外推修正 —— 解决“预计时间不准”。
 *  4. 任何失败都会带上引擎日志 / logcat / 模型目录清单，界面弹可复制的窗口。
 */
object EditBatchController {

    /**
     * 队列项使用本地文件路径，而不是相册的 content:// Uri。
     * 相册 Uri 的读取授权只在当前进程有效，熄屏或进程被回收后就读不到，
     * 表现为“选好的图和结果突然被删掉”。选图时立刻把缩放后的图落到本地文件。
     *
     * [maskPath] 是画笔选区生成的蒙版（同一尺寸的 PNG，白=要重画）；空串表示整图修图。
     */
    data class QueueItem(
        val path: String,
        val name: String,
        val thumb: Bitmap?,
        val readable: Boolean,
        val maskPath: String = "",
        val maskThumb: Bitmap? = null,
    )

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
        val calibrated: Boolean = false,
        /** 剩余时间快照（毫秒），通知与界面共用，保证两处口径一致。 */
        val etaMs: Long = 0L,
        val engineLoadMs: Long = 0L,
        val overheadMs: Long = 0L,
        val steps: Int = EditRoadPrefs.STEPS_DEFAULT,
        val strength: Float = EditRoadPrefs.STRENGTH_DEFAULT,
        val negativePrompt: String = EditRoadPrefs.DEFAULT_NEGATIVE,
        val canvasSize: Int = 384,
        val rendererLabel: String = "",
        val gpu: Boolean = false,
        val modelName: String = "",
        val modelId: String = "",
        val results: List<HistoryItem> = emptyList(),
        val note: String? = null,
        val failure: Failure? = null,
        /** 上次被系统打断的批任务：这里是还没跑的张数（0 = 没有可续跑的）。 */
        val pendingResume: Int = 0,
    )

    private const val TAG = "EditBatch"
    private const val PREFS = "app_prefs"
    // v2：旧键里存过被时间戳污染的小值（导致“预计每张约 19 秒”），换键名等于一次性作废。
    private const val KEY_MS_PER_STEP = "editroad_ms_per_step_v2"
    private const val KEY_CALIB = "editroad_step_calib_v3"
    private const val KEY_OVERHEAD = "editroad_overhead_ms_v2"

    private const val QUEUE_DIR = "editroad/queue"
    // 蒙版单独放一个目录：attach() 开机清扫队列目录时只认 img_*.jpg，
    // 混在一起会把选区文件当垃圾删掉。
    private const val MASK_DIR = "editroad/mask"
    private const val JOB_FILE = "editroad/job.json"

    private const val MAX_IMAGES = 16

    // 队列图存本机时的长边上限：足够覆盖最高档输出尺寸，又不会把私有目录撑爆。
    private const val MAX_EDGE = 1024
    private const val THUMB_EDGE = 480
    private const val CFG = 7f
    private const val SCHEDULER = "dpm"

    // 重绘强度不再写死：整图修图用设置里的值（默认 0.55），画笔选区后自动用上限 0.70。
    // 旧值 0.45 太保守，用户写“把场景换成厨房”时模型基本改不动，看起来就是“没按提示修”。
    private const val DENOISE_BLANK = 0.30f

    private const val ENGINE_READY_MS = 200_000L
    private const val FIRST_STEP_MS = 150_000L
    private const val PER_IMAGE_MS = 40L * 60L * 1000L
    private const val STALL_MS = 20L * 60L * 1000L

    private const val DEFAULT_POSITIVE =
        "masterpiece, best quality, highly detailed, sharp focus, correct anatomy, natural lighting"

    /**
     * 组装最终反向词：用户在设置里写的 + 内置底线（畸形 / 血腥 / 丑陋一定包含）。
     * 用户把设置清空也拿不掉底线，这是需求里明确要求的“禁止生成畸形人物、血腥、丑陋”。
     */
    private fun buildNegative(userNegative: String): String {
        val core = EditRoadPrefs.CORE_NEGATIVE
        val user = userNegative.trim().trimEnd(',').trim()
        if (user.isBlank()) return core
        val coreSet = core.split(",").map { it.trim().lowercase() }.toSet()
        val extra = user.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.lowercase() !in coreSet }
            .distinct()
            .joinToString(", ")
        return if (extra.isBlank()) core else "$core, $extra"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var appRef: Context? = null

    /** 本批任务里引擎加载耗时（实测，用于预计时间）。 */
    private var engineLoadSampleMs = 0L

    // ------------------------------------------------------------------ 配置

    fun refreshConfig(context: Context) {
        appRef = context.applicationContext
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.update { s ->
            val modelId = s.modelId.ifBlank { EditRoadPrefs.lastModelId(context).orEmpty() }
            val steps = EditRoadPrefs.steps(context)
            val gpu = EditRoadPrefs.wantsGpu(context)
            val canvas = DeviceCapabilities.sd15LongEdgeForRam(context)
            val median = medianStepMs(context, modelId, gpu, canvas, steps)
            s.copy(
                modelId = modelId,
                steps = steps,
                strength = EditRoadPrefs.strength(context),
                negativePrompt = EditRoadPrefs.negativePrompt(context),
                rendererLabel = EditRoadPrefs.rendererLabel(context),
                gpu = gpu,
                canvasSize = canvas,
                msPerStep = median.takeIf { it > 0 } ?: sp.getLong(KEY_MS_PER_STEP, 0L),
                calibrated = median > 0,
                overheadMs = sp.getLong(KEY_OVERHEAD, 0L),
            )
        }
    }

    /** 进入修图页 / 打开应用时调用：恢复上次被系统打断的批任务，并清理遗留的临时图。 */
    fun attach(context: Context) {
        val app = context.applicationContext
        refreshConfig(app)
        restoreJob(app)
        sweepQueueDir(app)
    }

    /** 删掉三天前留下、且不在当前队列里的临时图，避免私有目录越积越大。 */
    private fun sweepQueueDir(app: Context) {
        scope.launch(Dispatchers.IO) {
            val keep = _state.value.queue.map { it.path }.toSet()
            val cutoff = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
            runCatching {
                queueDir(app).listFiles()?.forEach { f ->
                    if (f.isFile && f.absolutePath !in keep && f.lastModified() < cutoff) f.delete()
                }
            }
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

    private fun queueDir(context: Context): File =
        File(context.filesDir, QUEUE_DIR).apply { runCatching { mkdirs() } }

    private fun maskDir(context: Context): File =
        File(context.filesDir, MASK_DIR).apply { runCatching { mkdirs() } }

    private fun jobFile(context: Context): File = File(context.filesDir, JOB_FILE)

    fun pickImages(context: Context, uris: List<Uri>, append: Boolean) {
        val app = context.applicationContext
        appRef = app
        if (uris.isEmpty()) return
        _state.update { it.copy(loadingImages = true) }
        scope.launch {
            val dir = queueDir(app)
            val stamp = System.currentTimeMillis()
            val prevQueue = _state.value.queue
            val fresh = withContext(Dispatchers.IO) {
                uris.mapIndexed { index, uri ->
                    val full = decodeSampledBitmap(app, uri, MAX_EDGE)
                    if (full == null) {
                        QueueItem("", "第 ${index + 1} 张", null, false)
                    } else {
                        val scaled = scaleToCanvas(full, MAX_EDGE)
                        // 用 JPEG(92) 落盘：体积只有 PNG 的几分之一，修图输入画质足够。
                        val file = File(dir, "q_${stamp}_$index.jpg")
                        val ok = runCatching {
                            FileOutputStream(file).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 92, out) }
                            file.length() > 0L
                        }.getOrDefault(false)
                        if (!ok) {
                            runCatching { file.delete() }
                            QueueItem("", uri.lastPathSegment ?: "第 ${index + 1} 张", null, false)
                        } else {
                            QueueItem(
                                path = file.absolutePath,
                                name = uri.lastPathSegment ?: file.name,
                                thumb = scaleToCanvas(scaled, THUMB_EDGE),
                                readable = true,
                            )
                        }
                    }
                }
            }
            val unreadable = fresh.count { q -> !q.readable }
            val merged = (if (append) prevQueue else emptyList()) + fresh
            val kept = merged
                .filter { q -> q.readable && q.path.isNotBlank() }
                .distinctBy { it.path }
                .take(MAX_IMAGES)
            val dropped = merged.count { q -> q.readable && q.path.isNotBlank() } - kept.size
            // 被换掉/挤掉的临时图立刻删掉，不留垃圾。
            val keepPaths = kept.map { it.path }.toSet()
            merged.filter { it.path.isNotBlank() && it.path !in keepPaths }
                .forEach { q -> runCatching { File(q.path).takeIf { f -> f.isFile }?.delete() } }
            _state.update {
                it.copy(
                    queue = kept,
                    loadingImages = false,
                    note = when {
                        unreadable > 0 && dropped > 0 ->
                            "有 $unreadable 张没能加入（读不出或本机存储不足）；一次最多 $MAX_IMAGES 张"
                        unreadable > 0 ->
                            "有 $unreadable 张没能加入：相册授权失效、图片损坏或本机存储空间不足"
                        dropped > 0 -> "一次最多 $MAX_IMAGES 张，多出的 $dropped 张没有加入"
                        else -> null
                    },
                )
            }
        }
    }

    fun removeImage(path: String) {
        if (_state.value.running) return
        val item = _state.value.queue.firstOrNull { it.path == path }
        _state.update { it.copy(queue = it.queue.filterNot { q -> q.path == path }) }
        runCatching { File(path).takeIf { f -> f.isFile }?.delete() }
        item?.maskPath?.takeIf { it.isNotBlank() }?.let { m ->
            runCatching { File(m).takeIf { f -> f.isFile }?.delete() }
        }
    }

    /**
     * 保存某张图的画笔选区蒙版（base64 PNG 文本，尺寸与该图一致）。
     * 有蒙版时引擎只重画圈出来的区域，其余像素保持不变。
     */
    fun setMask(context: Context, path: String, maskBase64: String, maskThumb: Bitmap?) {
        if (_state.value.running) return
        val app = context.applicationContext
        val file = File(maskDir(app), "m_${System.currentTimeMillis()}.txt")
        val ok = runCatching {
            file.writeText(maskBase64)
            file.length() > 0L
        }.getOrDefault(false)
        if (!ok) {
            _state.update { it.copy(note = "蒙版写入失败（存储空间可能已满）") }
            return
        }
        val old = _state.value.queue.firstOrNull { it.path == path }?.maskPath
        _state.update { s ->
            s.copy(
                queue = s.queue.map { q ->
                    if (q.path != path) q else q.copy(maskPath = file.absolutePath, maskThumb = maskThumb)
                },
            )
        }
        // 换选区后旧蒙版没用了，马上删掉，避免私有目录里堆垃圾。
        old?.takeIf { it.isNotBlank() && it != file.absolutePath }?.let { m ->
            runCatching { File(m).takeIf { f -> f.isFile }?.delete() }
        }
        ModelHolder.current?.let { persistJob(app, it, _state.value.queue, _state.value.prompt, running = false) }
    }

    /** 一次画笔编辑会话（由 EditRoadShell 顶层渲染，避免被底部 Dock 盖住）。 */
    data class PaintSession(
        val path: String,
        val bitmap: Bitmap,
        val preview: Bitmap?,
        val history: List<PathData>,
    )

    private val _paint = MutableStateFlow<PaintSession?>(null)
    val paint: StateFlow<PaintSession?> = _paint.asStateFlow()

    /** 打开画笔：返回 false 表示这张图读不出来。 */
    fun openPaint(context: Context, item: QueueItem): Boolean {
        if (_state.value.running) {
            _state.update { it.copy(note = "修图进行中，先停止再改选区") }
            return false
        }
        val bmp = canvasBitmapForMask(context, item.path) ?: run {
            _state.update { it.copy(note = "这张图读不出来，换一张再试") }
            return false
        }
        _paint.value = PaintSession(
            path = item.path,
            bitmap = bmp,
            preview = if (item.maskPath.isNotBlank()) decodeMaskBitmap(item.maskPath) else null,
            history = emptyList(),
        )
        return true
    }

    fun closePaint() {
        _paint.value = null
    }

    /** 画笔确认：存蒙版并关闭面板。 */
    fun completePaint(
        context: Context,
        path: String,
        maskBase64: String,
        maskThumb: Bitmap?,
        history: List<PathData>,
    ) {
        _paint.value = null
        setMask(context, path, maskBase64, maskThumb)
        _state.update { it.copy(note = "选区已保存：这张只会重画圈出来的地方") }
    }

    /** 给界面一条明确反馈（选区保存、词典未命中之类）。 */
    fun setNote(context: Context, text: String) {
        _state.update { it.copy(note = text) }
    }

    /** 蒙版文件里存的是 base64 PNG，这里解回位图供画笔继续编辑。 */
    fun decodeMaskBitmap(path: String): Bitmap? {
        val b64 = runCatching { File(path).readText() }.getOrNull() ?: return null
        val bytes = runCatching { android.util.Base64.decode(b64.trim(), android.util.Base64.DEFAULT) }.getOrNull()
            ?: return null
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }

    /** 供画笔使用：把队列图按当前输出尺寸缩放后返回（蒙版必须与它同尺寸）。 */
    fun canvasBitmapForMask(context: Context, path: String): Bitmap? {
        val canvas = _state.value.canvasSize
        val src = decodeLocalBitmap(path, THUMB_EDGE) ?: return null
        if (canvas <= 0) return src
        val hi = maxOf(src.width, src.height)
        if (hi <= canvas) return src
        val scale = canvas.toFloat() / hi
        val w = ((src.width * scale).toInt().coerceAtLeast(8) / 8) * 8
        val h = ((src.height * scale).toInt().coerceAtLeast(8) / 8) * 8
        val dst = Bitmap.createScaledBitmap(src, w, h, true)
        if (dst !== src) src.recycle()
        return dst
    }

    /** 位图 -> 引擎要的 base64 PNG（无换行）。 */
    fun bitmapToBase64Png(bmp: Bitmap): String {
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** 清掉某张图的选区，回到整图修图。 */
    fun clearMask(context: Context, path: String) {
        if (_state.value.running) return
        val item = _state.value.queue.firstOrNull { it.path == path }
        _state.update { s ->
            s.copy(queue = s.queue.map { q -> if (q.path == path) q.copy(maskPath = "", maskThumb = null) else q })
        }
        item?.maskPath?.takeIf { it.isNotBlank() }?.let { m ->
            runCatching { File(m).takeIf { f -> f.isFile }?.delete() }
        }
        ModelHolder.current?.let { persistJob(context.applicationContext, it, _state.value.queue, _state.value.prompt, running = false) }
    }

    fun clearImages(context: Context) {
        if (_state.value.running) return
        val app = context.applicationContext
        _state.value.queue.forEach { q ->
            runCatching { File(q.path).takeIf { f -> f.isFile }?.delete() }
            if (q.maskPath.isNotBlank()) runCatching { File(q.maskPath).takeIf { f -> f.isFile }?.delete() }
        }
        clearJob(app)
        _state.update {
            it.copy(
                queue = emptyList(),
                results = emptyList(),
                note = null,
                preview = null,
                pendingResume = 0,
                total = 0,
                done = 0,
                progress = 0f,
            )
        }
        refreshConfig(app)
    }

    // ------------------------------------------------------------------ 执行

    fun start(context: Context, model: Model) {
        val app = context.applicationContext
        appRef = app
        if (_state.value.running) return
        val items = _state.value.queue.filter { it.readable && it.path.isNotBlank() }
        if (items.isEmpty()) {
            _state.update { it.copy(note = "请先选择要修的图片（可多选）") }
            return
        }
        refreshConfig(app)
        val promptText = _state.value.prompt
        _state.update {
            it.copy(
                failure = null,
                note = null,
                queue = items,
                pendingResume = 0,
                modelId = model.id,
                modelName = model.name,
            )
        }
        BatteryOptimization.ensureIgnoring(app)
        engineLoadSampleMs = 0L
        persistJob(app, model, items, promptText, running = true)
        job = scope.launch { runBatch(app, model, items, promptText) }
    }

    /** 上次被系统打断的队列已恢复，直接点“开始修图”就能接着跑完剩下的张。 */
    fun resume(context: Context, model: Model) = start(context, model)

    /** 用户主动放弃上次没跑完的那批：删掉临时图与清单，界面回到空队列。 */
    fun discardResume(context: Context) {
        val app = context.applicationContext
        _state.value.queue.forEach { q ->
            runCatching { File(q.path).takeIf { f -> f.isFile }?.delete() }
            if (q.maskPath.isNotBlank()) runCatching { File(q.maskPath).takeIf { f -> f.isFile }?.delete() }
        }
        clearJob(app)
        _state.update {
            it.copy(
                queue = emptyList(),
                pendingResume = 0,
                total = 0,
                done = 0,
                progress = 0f,
                note = null,
            )
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        if (!_state.value.running) {
            // 没在跑就不动队列，只把可能残留的保活服务与清单收掉。
            EditKeepAliveService.finish(app, "NERT 空闲")
            return
        }
        val rest = _state.value.queue.drop(_state.value.done + 1)
        _state.update { it.copy(running = false, stage = "已停止", pendingResume = rest.size) }
        runCatching { BackgroundGenerationService.stop(app) }
        ModelHolder.current?.let { persistJob(app, it, rest, _state.value.prompt, running = false) }
        EditKeepAliveService.finish(app, "已停止，剩余 ${rest.size} 张可以继续")
        job?.let { current ->
            job = null
            scope.launch { runCatching { current.cancelAndJoin() } }
        }
    }

    private suspend fun runBatch(
        app: Context,
        model: Model,
        items: List<QueueItem>,
        promptText: String,
    ) {
        val steps = EditRoadPrefs.steps(app)
        val useOpenCL = EditRoadPrefs.wantsGpu(app)
        val canvas = DeviceCapabilities.sd15LongEdgeForRam(app)
        // 强度：留空只做画质增强（低强度）；写了提示语就用设置里的强度（默认 0.55，能真的改得动）。
        val denoise = if (promptText.isBlank()) DENOISE_BLANK else EditRoadPrefs.strength(app)
        val userNegative = EditRoadPrefs.negativePrompt(app)
        val history = HistoryManager(app)
        runCatching { LogCapture.start() }

        Log.i(
            TAG,
            "batch start model=${model.id} type=${model.backendType} images=${items.size} " +
                "steps=$steps gpu=$useOpenCL canvas=$canvas strength=$denoise",
        )
        _state.update {
            it.copy(
                running = true,
                failure = null,
                total = items.size,
                done = 0,
                strength = denoise,
                negativePrompt = userNegative,
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
                modelId = model.id,
                modelName = model.name,
                pendingResume = 0,
                calibrated = medianStepMs(app, model.id, useOpenCL, canvas, steps) > 0,
            )
        }
        ModelHolder.current = model
        EditKeepAliveService.start(app, "正在准备引擎 · 共 ${items.size} 张", 0, items.size)

        var stopped = false
        var failed = false
        var finished = 0
        var galleryFailures = 0
        try {
            for ((index, queueItem) in items.withIndex()) {
                val path = queueItem.path
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

                val readStart = System.currentTimeMillis()
                val decoded = withContext(Dispatchers.IO) { decodeLocalBitmap(path, MAX_EDGE) }
                if (decoded == null) {
                    val msg = "第 ${index + 1} 张的本地文件读不到（可能存储空间被清理），已跳过"
                    _state.update { it.copy(done = it.done + 1, note = msg) }
                    LogHub.log(LogHub.Category.GENERATE, "queue file missing: $path")
                    persistJob(app, model, items.drop(index + 1), promptText, _state.value.running)
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

                val engineStart = System.currentTimeMillis()
                val engineError = ensureEngineReady(app, model, scaled.width, scaled.height)
                if (engineError != null) {
                    reportFailure(model, "模型引擎无法加载", engineError, "第 ${index + 1} 张")
                    failed = true
                    break
                }
                if (engineLoadSampleMs <= 0L) {
                    engineLoadSampleMs = System.currentTimeMillis() - engineStart
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

                when (val outcome = generateOne(
                    app, model, promptText, scaled, steps, denoise, useOpenCL,
                    maskPath = queueItem.maskPath,
                    userNegative = userNegative,
                )) {
                    is GenOutcome.Done -> {
                        val bitmap = outcome.bitmap
                        val elapsed = System.currentTimeMillis() - _state.value.currentStartedAt
                        calibrate(app, model.id, useOpenCL, canvas, steps, outcome.msPerStep)

                        // 先落本机历史（不会被系统清理），再写相册：
                        // 这样即使相册写入被拒或后台被杀，图也一定还在“历史”里。
                        _state.update { it.copy(stage = "第 ${index + 1} 张：写入历史") }
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
                                        negativePrompt = buildNegative(userNegative),
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
                        val historyOk = item != null && item.imageFile.exists()
                        if (item != null) {
                            _state.update { it.copy(results = (it.results + item).takeLast(8)) }
                        }

                        _state.update { it.copy(stage = "第 ${index + 1} 张：保存到相册") }
                        var galleryError: String? = null
                        withContext(Dispatchers.IO) {
                            runCatching {
                                saveImage(
                                    context = app,
                                    bitmap = bitmap,
                                    onSuccess = {},
                                    onError = { galleryError = it },
                                )
                            }.onFailure { galleryError = it.message ?: it.javaClass.simpleName }
                        }
                        if (galleryError != null) galleryFailures += 1
                        if (!historyOk || galleryError != null) {
                            val why = when {
                                !historyOk && galleryError != null ->
                                    "历史与相册都没写入成功：${galleryError}"
                                !historyOk -> "历史写入失败（相册已写入）"
                                else -> "相册写入失败：${galleryError}（历史里仍可看到这张）"
                            }
                            LogHub.log(LogHub.Category.GENERATE, "save issue: $why")
                            _state.update { it.copy(note = why) }
                        }

                        // 附加耗时 = 本张总耗时 - 纯步进耗时（读取、翻译、写历史与相册都算进来）
                        val totalThis = System.currentTimeMillis() - _state.value.currentStartedAt
                        recordOverhead(app, (totalThis - outcome.stepSpanMs).coerceIn(0L, 120_000L))
                        finished += 1
                        _state.update { it.copy(done = it.done + 1, progress = 0f, preview = null) }
                        BackgroundGenerationService.clearCompleteState()
                        // 这张已经完成：把队列文件删掉，避免占存储；清单同步剩余张数。
                        runCatching { File(path).takeIf { f -> f.isFile }?.delete() }
                        if (queueItem.maskPath.isNotBlank()) {
                            runCatching { File(queueItem.maskPath).takeIf { f -> f.isFile }?.delete() }
                        }
                        val rest = items.drop(index + 1)
                        persistJob(app, model, rest, promptText, running = rest.isNotEmpty())
                        EditKeepAliveService.update(
                            app,
                            "已完成 ${index + 1}/${items.size} 张",
                            index + 1,
                            items.size,
                        )
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
            val remaining = items.size - _state.value.done
            val doneNow = _state.value.done
            _state.update {
                it.copy(
                    running = false,
                    progress = if (failed || stopped) it.progress else 1f,
                    stage = when {
                        failed -> "未完成（见错误日志）"
                        stopped -> "已停止"
                        else -> "全部完成：$finished 张"
                    },
                    pendingResume = if ((stopped || failed) && remaining > 0) remaining else 0,
                    note = when {
                        failed -> it.note
                        stopped && remaining > 0 ->
                            "已停止修图；已完成的图片仍在相册与历史里，剩余 $remaining 点上方“继续修完”接着跑"
                        galleryFailures > 0 ->
                            "$finished 张已修好并保存在“历史”，其中 $galleryFailures 张写相册失败（多为相册权限或空间不足）"
                        finished > 0 -> "$finished 张已自动保存到相册，可在“历史”里回看"
                        else -> it.note
                    },
                )
            }
            if (!stopped && !failed) {
                // 整批跑完：清理队列文件与清单。
                items.forEach { q ->
                    runCatching { File(q.path).takeIf { f -> f.isFile }?.delete() }
                    if (q.maskPath.isNotBlank()) runCatching { File(q.maskPath).takeIf { f -> f.isFile }?.delete() }
                }
                clearJob(app)
                _state.update { it.copy(queue = emptyList(), pendingResume = 0) }
            } else {
                val rest = items.drop(doneNow)
                persistJob(app, model, rest, promptText, running = false)
                // 队列只留没跑完的那些：中途完成的图片文件已删，
                // 若继续留在队列里，重启后再点开始会把它们当成待修图片重复计数。
                val restPaths = rest.map { it.path }.toSet()
                _state.update {
                    it.copy(
                        queue = it.queue.filter { q -> q.path in restPaths },
                        pendingResume = rest.size,
                        total = rest.size,
                        done = 0,
                        progress = 0f,
                    )
                }
            }
            EditKeepAliveService.finish(
                app,
                when {
                    failed -> "修图未完成，点开应用查看日志"
                    stopped -> "已停止，剩余 ${maxOf(0, remaining)} 张可继续"
                    else -> "修图完成：$finished 张已存入相册与历史"
                },
            )
            io.github.xororz.localdream.data.EditRoadPrefs.setLastModelId(app, model.id)
        }
    }

    // ------------------------------------------------------------------ 断点清单

    private object ModelHolder {
        @Volatile
        var current: Model? = null
    }

    private fun persistJob(
        app: Context,
        model: Model,
        remaining: List<QueueItem>,
        prompt: String,
        running: Boolean,
    ) {
        runCatching {
            val obj = JSONObject()
                .put("modelId", model.id)
                .put("prompt", prompt)
                .put("running", running)
                .put("updatedAt", System.currentTimeMillis())
                .put(
                    "queue",
                    JSONArray().apply { remaining.map { it.path }.filter { it.isNotBlank() }.forEach { put(it) } },
                )
                .put(
                    "masks",
                    JSONArray().apply { remaining.map { it.maskPath }.forEach { put(it) } },
                )
            val file = jobFile(app)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(obj.toString().toByteArray()) }
        }.onFailure { Log.w(TAG, "persist job failed", it) }
    }

    private fun clearJob(app: Context) {
        runCatching { jobFile(app).takeIf { f -> f.isFile }?.delete() }
    }

    /**
     * 进程被系统回收后重新启动时恢复：把还没修完的队列文件读回来，
     * running 为 true 说明上次是被打断的（正常跑完会清掉清单）。
     */
    private fun restoreJob(app: Context) {
        val s = _state.value
        if (s.running || s.queue.isNotEmpty()) return
        val file = jobFile(app)
        if (!file.isFile) return
        val parsed = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        val modelId = parsed.optString("modelId")
        val paths = parsed.optJSONArray("queue")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        }.orEmpty().filter { it.isNotBlank() }
        val running = parsed.optBoolean("running", false)
        val prompt = parsed.optString("prompt")
        val masks = parsed.optJSONArray("masks")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        }.orEmpty()
        val pairs = paths.mapIndexed { i, p -> p to masks.getOrElse(i) { "" } }
        val existing = pairs.filter { (p, m) ->
            p.startsWith(queueDir(app).absolutePath) && File(p).isFile &&
                (m.isBlank() || (m.startsWith(maskDir(app).absolutePath) && File(m).isFile))
        }
        if (existing.isEmpty()) {
            clearJob(app)
            if (running) {
                _state.update { it.copy(note = "上次没跑完的图片文件已经不在了，请重新选择图片") }
            }
            return
        }
        val queue = existing.map { (p, m) ->
            val bmp = runCatching { decodeLocalBitmap(p, THUMB_EDGE) }.getOrNull()
            val maskBmp = if (m.isNotBlank()) decodeMaskBitmap(m) else null
            QueueItem(p, File(p).name, bmp, true, m, maskBmp)
        }
        val model = io.github.xororz.localdream.data.ModelRepository.getInstance(app)
            .models.firstOrNull { it.id == modelId }
        _state.update {
            it.copy(
                queue = queue,
                prompt = prompt.ifBlank { it.prompt },
                pendingResume = if (running) queue.size else 0,
                modelId = modelId,
                modelName = model?.name.orEmpty(),
                note = if (running && model != null) {
                    "上次修图在后台被系统打断，这里恢复了没跑完的 ${queue.size} 张，点“开始修图”接着跑完"
                } else {
                    it.note
                },
            )
        }
        Log.i(TAG, "restored job model=$modelId pending=${queue.size} running=$running")
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
        /**
         * @param msPerStep 本张实测的单步耗时（0 = 样本不足，不参与标定）
         * @param stepSpanMs 本张纯步进总耗时，用来反推“每张附加耗时”
         */
        data class Done(
            val bitmap: Bitmap,
            val seed: Long?,
            val msPerStep: Long,
            val stepSpanMs: Long,
        ) : GenOutcome()

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
        maskPath: String,
        userNegative: String,
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
        val masked = maskPath.isNotBlank() && File(maskPath).isFile
        if (masked) {
            runCatching { File(maskPath).readText().let { base64Mask ->
                FileOutputStream(File(app.filesDir, "mask.txt")).use { it.write(base64Mask.toByteArray()) }
            } }
        } else {
            runCatching { File(app.filesDir, "mask.txt").takeIf { f -> f.isFile }?.delete() }
        }
        val port = EnginePort.activeEnginePort(app)
        val intent = Intent(app, BackgroundGenerationService::class.java).apply {
            putExtra("prompt", translated ?: positive)
            putExtra("negative_prompt", buildNegative(userNegative))
            putExtra("steps", steps)
            putExtra("cfg", CFG)
            putExtra("width", w)
            putExtra("height", h)
            putExtra("effective_width", w)
            putExtra("effective_height", h)
            putExtra("denoise_strength", if (masked) EditRoadPrefs.STRENGTH_MASKED else denoise)
            putExtra("use_opencl", useOpenCL)
            putExtra("scheduler", SCHEDULER)
            putExtra("aspect_ratio", ImageDiagnostics.aspectRatioFor(w, h))
            putExtra("has_image", true)
            // 画笔选区：有蒙版时只重画圈出来的地方，其余像素保持不变。
            if (masked) {
                putExtra("has_mask", true)
            }
            putExtra("modelId", model.id)
            putExtra("backendType", model.backendType)
            putExtra("backend_host", "localhost:$port")
            putExtra("prompt_pretranslated", translated != null)
            putExtra("prompt_english_only", true)
        }
        _state.update {
            it.copy(
                stage = "修图中 ${w}×$h · $steps 步 · " +
                    if (masked) "只改圈出区域" else "整图重绘 ${(denoise * 100).toInt()}%",
            )
        }
        val startError = runCatching { app.startForegroundService(intent) }.exceptionOrNull()
        if (startError != null) {
            Log.e(TAG, "start generation failed", startError)
            return GenOutcome.Failed("无法启动生成服务", startError.message ?: startError.javaClass.simpleName)
        }

        val startedAt = System.currentTimeMillis()
        var sawStep = false
        var firstStepAt = 0L
        var lastStepAt = 0L
        var lastStepProgress = 0f
        while (true) {
            if (!_state.value.running) return GenOutcome.Cancelled

            when (val gs = BackgroundGenerationService.generationState.value) {
                is BackgroundGenerationService.GenerationState.Progress -> {
                    if (gs.progress > 0.001f) {
                        val now = System.currentTimeMillis()
                        if (!sawStep) {
                            sawStep = true
                            firstStepAt = now
                        }
                        if (gs.progress > lastStepProgress + 0.005f) {
                            lastStepAt = now
                            lastStepProgress = gs.progress
                        }
                        _state.update {
                            it.copy(
                                progress = gs.progress.coerceIn(0f, 1f),
                                preview = gs.intermediateImage ?: it.preview,
                                lastProgressAt = now,
                            )
                        }
                        updateEta(app, model, now)
                    }
                }

                is BackgroundGenerationService.GenerationState.Complete -> {
                    BackgroundGenerationService.markBitmapConsumed()
                    val measured = measureStepMs(sawStep, firstStepAt, lastStepAt, lastStepProgress, steps)
                    val span = if (sawStep && lastStepAt > firstStepAt) lastStepAt - firstStepAt else 0L
                    return GenOutcome.Done(gs.bitmap, gs.seed, measured, span)
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

    /** 只用步进时间差算单步耗时，跳过首步（含排队）与最后一步（含解码/回传）。 */
    private fun measureStepMs(
        sawStep: Boolean,
        firstStepAt: Long,
        lastStepAt: Long,
        lastProgress: Float,
        steps: Int,
    ): Long {
        if (!sawStep || firstStepAt <= 0L || lastStepAt <= firstStepAt || steps <= 2) return 0L
        val span = lastStepAt - firstStepAt
        val covered = (((lastProgress.coerceIn(0.05f, 0.98f) - (1f / steps)) * steps).toDouble())
            .coerceAtLeast(1.0)
        return (span / covered).toLong().coerceIn(60L, 30_000L)
    }

    private fun updateEta(app: Context, model: Model, now: Long) {
        val s = _state.value
        val eta = remainingMs(app, model.id, s, now)
        if (eta != s.etaMs) _state.update { it.copy(etaMs = eta) }
    }

    /** 界面每秒调一次：让“预计剩余”随时间跳动，不必等引擎的步进回调。 */
    fun tickEta() {
        val app = appRef ?: return
        val s = _state.value
        if (!s.running) return
        val eta = remainingMs(app, s.modelId, s, System.currentTimeMillis())
        if (eta != s.etaMs) _state.update { it.copy(etaMs = eta) }
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
        sb.appendLine(
            "标定：${if (s.calibrated || s.msPerStep > 0) "已实测（${s.msPerStep}ms/步）" else "未实测"}" +
                " · 单张附加 ${s.overheadMs}ms · 引擎加载 ${engineLoadSampleMs}ms",
        )
        sb.appendLine("后台保活：${if (Build.VERSION.SDK_INT >= 23) "前台服务已启用" else "n/a"}")
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

    private fun calibKey(modelId: String, gpu: Boolean, canvas: Int, steps: Int): String =
        "$modelId|${if (gpu) "gpu" else "cpu"}|$canvas|$steps"

    /** 取同一档位最近 5 个样本的中位数，抗单次抖动（后台被限频、发热降频等）。 */
    private fun medianStepMs(context: Context, modelId: String, gpu: Boolean, canvas: Int, steps: Int): Long {
        if (modelId.isBlank()) return 0L
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_CALIB, null) ?: return 0L
        val arr = runCatching { JSONObject(raw).optJSONArray(calibKey(modelId, gpu, canvas, steps)) }.getOrNull()
            ?: return 0L
        if (arr.length() == 0) return 0L
        val list = (0 until arr.length()).map { arr.getLong(it) }.sorted()
        return list[list.size / 2]
    }

    private fun calibrate(app: Context, modelId: String, gpu: Boolean, canvas: Int, steps: Int, measured: Long) {
        val per = measured.takeIf { it > 0 } ?: return
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = calibKey(modelId, gpu, canvas, steps)
        val root = runCatching { JSONObject(sp.getString(KEY_CALIB, "{}") ?: "{}") }.getOrElse { JSONObject() }
        val arr = runCatching { root.optJSONArray(key) }.getOrNull() ?: JSONArray()
        val samples = ((0 until arr.length()).map { arr.getLong(it) } + per).takeLast(5)
        root.put(key, JSONArray(samples))
        sp.edit().putString(KEY_CALIB, root.toString()).putLong(KEY_MS_PER_STEP, per).apply()
        _state.update { it.copy(msPerStep = samples.sorted()[samples.size / 2], calibrated = true) }
        Log.i(TAG, "calibrated $key -> samples=$samples")
    }

    private fun recordOverhead(app: Context, ms: Long) {
        if (ms <= 0L) return
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = sp.getLong(KEY_OVERHEAD, 0L)
        val merged = if (prev <= 0L) ms else (prev * 3 + ms) / 4
        sp.edit().putLong(KEY_OVERHEAD, merged).apply()
        _state.update { it.copy(overheadMs = merged) }
    }

    /** 当前档位“每张”应有的耗时（不含引擎加载）。 */
    private fun stepMs(app: Context, modelId: String, s: State): Long {
        val median = medianStepMs(app, modelId, s.gpu, s.canvasSize, s.steps)
        if (median > 0) return median
        if (s.msPerStep > 0) return s.msPerStep
        return if (s.gpu) 420L else 1300L
    }

    private fun perImageModelMs(app: Context, modelId: String, s: State): Long =
        stepMs(app, modelId, s) * s.steps + maxOf(s.overheadMs, 2_000L)

    /**
     * 剩余时间：先用“实测进度外推”，再用分档标定模型补齐整批；
     * 引擎还没加载过的那一张额外加引擎加载耗时。
     */
    private fun remainingMs(app: Context, modelId: String, s: State, now: Long): Long {
        if (!s.running || s.total <= 0) return 0L
        val perImage = perImageModelMs(app, modelId, s)
        val elapsed = (now - s.currentStartedAt).coerceAtLeast(0L)
        val p = s.progress
        val modelThisRemaining = (perImage - elapsed).coerceAtLeast(0L)
        val thisRemaining = if (p > 0.08f && elapsed > 4_000L) {
            // 这里绝不能按“模型预估的 3 倍”夹住：首次估算偏小时，
            // 就会出现实际跑了 5 分钟却显示“预计剩余 41 秒”。
            val extrapolated = ((elapsed.toDouble() / p) * (1.0 - p)).toLong()
                .coerceIn(0L, 4L * 3600L * 1000L)
            val w = (p * 1.6).coerceAtMost(0.85)
            (extrapolated * w + modelThisRemaining * (1.0 - w)).toLong()
        } else {
            modelThisRemaining
        }
        val restImages = (s.total - s.done - 1).coerceAtLeast(0)
        val load = if (engineLoadSampleMs <= 0L &&
            BackendService.servingModelId.value != modelId
        ) {
            if (s.gpu) 18_000L else 12_000L
        } else {
            0L
        }
        return thisRemaining + perImage * restImages + load
    }

    /** 界面与通知都用它，保证两处口径一致。 */
    fun etaText(): String {
        val app = appRef ?: return ""
        val s = _state.value
        if (!s.running) return ""
        val remaining = remainingMs(app, s.modelId, s, System.currentTimeMillis())
        val elapsed = (System.currentTimeMillis() - s.startedAt).coerceAtLeast(0L)
        val tail = if (s.calibrated) "已按本机实测" else "首次运行会先校准"
        return if (s.total > 1) {
            "本批约剩 ${humanSeconds(remaining)} · 已用 ${humanSeconds(elapsed)}（$tail）"
        } else {
            "预计剩余约 ${humanSeconds(remaining)} · 已用 ${humanSeconds(elapsed)}（$tail）"
        }
    }

    /** 每张刷新一次，界面用它展示“预计每张”。 */
    fun perImageEstimateMs(): Long {
        val app = appRef ?: return 0L
        val s = _state.value
        val modelId = s.modelId.ifBlank { EditRoadPrefs.lastModelId(app).orEmpty() }
        return perImageModelMs(app, modelId, s)
    }

    fun overallProgress(): Float {
        val s = _state.value
        if (s.total <= 0) return 0f
        return ((s.done + s.progress).coerceAtMost(s.total.toFloat()) / s.total.toFloat())
    }

    /** 上次被打断、还没跑的张数（0 = 没有）。 */
    fun pendingResumeCount(): Int = _state.value.pendingResume

    fun isCalibrated(): Boolean = _state.value.calibrated
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

internal fun decodeLocalBitmap(path: String, maxEdge: Int): Bitmap? = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(path, opts)
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
