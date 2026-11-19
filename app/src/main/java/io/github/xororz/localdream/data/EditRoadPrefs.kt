package io.github.xororz.localdream.data

import android.content.Context

/**
 * NERT 用户可配置项：步数（默认 40）、渲染器、重绘强度、负面提示语、提示语联想、
 * 下载源、CPU 线程、语言。尺寸 / CFG / 调度器仍由代码按机型配好，避免误调导致跑不出图。
 */
object EditRoadPrefs {
    private const val PREFS = "app_prefs"

    const val KEY_STEPS = "editroad_steps"
    const val KEY_RENDERER = "editroad_renderer"
    const val KEY_DOWNLOAD_SOURCE = "download_source"
    const val KEY_CPU_THREADS = "cpu_threads"
    private const val KEY_LAST_MODEL = "last_edit_model"

    const val RENDERER_AUTO = "auto"
    const val RENDERER_CPU = "cpu"
    const val RENDERER_OPENCL = "opencl"
    const val RENDERER_VULKAN = "vulkan"

    const val SOURCE_AUTO = "auto"
    const val SOURCE_MIRROR = "mirror"
    const val SOURCE_OFFICIAL = "official"

    const val STEPS_DEFAULT = 40
    const val STEPS_MIN = 10
    const val STEPS_MAX = 100

    const val KEY_QUALITY = "editroad_quality"
    const val KEY_STRENGTH = "editroad_strength"
    const val KEY_NEGATIVE = "editroad_negative"
    const val KEY_AUTOCOMPLETE = "editroad_autocomplete"
    private const val KEY_STEPS_SEEDED = "editroad_steps_seeded_v1"

    // 引擎侧把 strength 夹在 0.3–0.7，这里就用同一区间，避免“设了 0.9 实际只有 0.7”。
    const val STRENGTH_DEFAULT = 0.55f
    const val STRENGTH_MIN = 0.30f
    const val STRENGTH_MAX = 0.70f

    /** 画笔选了区域时用上限：选区外由蒙版保护，选区内要改得动。 */
    const val STRENGTH_MASKED = 0.70f

    /**
     * 内置底线反向词：防畸形、防血腥、防丑陋。
     * 无论用户在设置里怎么改负面提示语，这一段一定会拼上去，不能被关闭。
     */
    const val CORE_NEGATIVE =
        "lowres, worst quality, low quality, blurry, jpeg artifacts, " +
            "bad anatomy, bad hands, missing fingers, extra fingers, fused fingers, " +
            "extra limbs, missing limbs, malformed limbs, mutated hands, poorly drawn hands, " +
            "poorly drawn face, deformed face, deformed, disfigured, mutated, extra eyes, " +
            "cross-eyed, long neck, ugly, distorted body, " +
            "blood, blood splatter, gore, wounds, injury, " +
            "watermark, text, signature, username, logo, cropped, out of frame"

    /** 设置页里预填、可编辑的负面提示语。 */
    val DEFAULT_NEGATIVE: String = CORE_NEGATIVE

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun steps(context: Context): Int {
        val sp = prefs(context)
        if (!sp.getBoolean(KEY_STEPS_SEEDED, false)) {
            // 旧棕色版把步数持久化成了 13 之类的小值，升级后同包同名 prefs 会一直带过来。
            // 首次读到 times 时按“开箱即用”重配一次为 40，之后完全尊重用户在设置里的改动。
            sp.edit()
                .putInt(KEY_STEPS, STEPS_DEFAULT)
                .putBoolean(KEY_STEPS_SEEDED, true)
                .apply()
        }
        return sp.getInt(KEY_STEPS, STEPS_DEFAULT).coerceIn(STEPS_MIN, STEPS_MAX)
    }

    fun setSteps(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_STEPS, value.coerceIn(STEPS_MIN, STEPS_MAX))
            .putBoolean(KEY_STEPS_SEEDED, true)
            .apply()
    }

    /** 重绘强度：整图修图用这里的值，画笔选区后自动提到 [STRENGTH_MASKED]。 */
    fun strength(context: Context): Float =
        prefs(context).getFloat(KEY_STRENGTH, STRENGTH_DEFAULT).coerceIn(STRENGTH_MIN, STRENGTH_MAX)

    fun setStrength(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_STRENGTH, value.coerceIn(STRENGTH_MIN, STRENGTH_MAX)).apply()
    }

    /** 用户可见可改的负面提示语；实际发送时一定会再拼上 [CORE_NEGATIVE]。 */
    fun negativePrompt(context: Context): String =
        prefs(context).getString(KEY_NEGATIVE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NEGATIVE

    fun setNegativePrompt(context: Context, value: String) {
        prefs(context).edit().putString(KEY_NEGATIVE, value.trim()).apply()
    }

    const val QUALITY_1K = "1k"
    const val QUALITY_2K = "2k"
    const val QUALITY_3K = "3k"
    const val QUALITY_4K = "4k"

    /** 画质档位（输出长边）。原生扩散尺寸由机型决定，这里只是最终成片的分辨率。 */
    fun quality(context: Context): String =
        prefs(context).getString(KEY_QUALITY, QUALITY_1K)
            ?.takeIf { it in setOf(QUALITY_1K, QUALITY_2K, QUALITY_3K, QUALITY_4K) }
            ?: QUALITY_1K

    fun setQuality(context: Context, value: String) {
        if (value !in setOf(QUALITY_1K, QUALITY_2K, QUALITY_3K, QUALITY_4K)) return
        prefs(context).edit().putString(KEY_QUALITY, value).apply()
    }

    fun qualityEdge(quality: String): Int = when (quality) {
        QUALITY_4K -> 4096
        QUALITY_3K -> 3072
        QUALITY_2K -> 2048
        else -> 1024
    }

    /**
     * 实际输出长边：想选 4K 但机器存不下（32 位地址空间 / 内存偏小）时自动降档，
     * 避免保存那一刻 OOM 把整批结果带走。
     */
    fun outputEdge(context: Context): Int {
        val want = qualityEdge(quality(context))
        val ramGb = io.github.xororz.localdream.utils.DeviceCapabilities
            .totalRamBytes(context) / (1024L * 1024L * 1024L)
        val cap = when {
            !io.github.xororz.localdream.utils.DeviceCapabilities.is64Bit() -> 2048
            ramGb >= 8L -> 4096
            ramGb >= 6L -> 3072
            else -> 2048
        }
        return minOf(want, cap)
    }

    fun qualityLabel(context: Context): String {
        val edge = outputEdge(context)
        val label = when (edge) {
            4096 -> "4K"
            3072 -> "3K"
            2048 -> "2K"
            else -> "1K"
        }
        val want = qualityEdge(quality(context))
        return if (edge < want) "$label（本机上限，已从 ${want / 1024}K 降档）" else label
    }

    /** 提示语联想（输入时按中文词/英文标签给出候选）。默认开。 */
    fun autocompleteEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOCOMPLETE, true)

    fun setAutocompleteEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOCOMPLETE, value).apply()
    }

    fun renderer(context: Context): String =
        prefs(context).getString(KEY_RENDERER, RENDERER_AUTO)
            ?.takeIf { it in setOf(RENDERER_AUTO, RENDERER_CPU, RENDERER_OPENCL, RENDERER_VULKAN) }
            ?: RENDERER_AUTO

    fun setRenderer(context: Context, value: String) {
        val sp = prefs(context)
        sp.edit().putString(KEY_RENDERER, value).apply()
        // 用户刚手动选择过，就清掉“上一次崩溃过”的永久判死标记。
        sp.edit()
            .remove("gpu_unstable")
            .remove("opencl_unstable")
            .apply()
        when (value) {
            RENDERER_CPU -> sp.edit().putString("gpu_backend", "opencl").apply()
            RENDERER_OPENCL -> sp.edit().putString("gpu_backend", "opencl").apply()
            RENDERER_VULKAN -> sp.edit().putString("gpu_backend", "vulkan").apply()
            else -> Unit
        }
    }

    /** 渲染器是否走 GPU。自动模式按设备探测结果决定。 */
    fun wantsGpu(context: Context): Boolean = when (renderer(context)) {
        RENDERER_CPU -> false
        RENDERER_OPENCL, RENDERER_VULKAN -> true
        else -> io.github.xororz.localdream.utils.DeviceCapabilities.openclRecommended()
    }

    fun rendererLabel(context: Context): String = when (renderer(context)) {
        RENDERER_CPU -> "CPU（兼容 / 省电）"
        RENDERER_OPENCL -> "GPU · OpenCL"
        RENDERER_VULKAN -> "GPU · Vulkan"
        else -> "自动（优先 GPU，失败回退 CPU）"
    }

    fun downloadSource(context: Context): String =
        prefs(context).getString(KEY_DOWNLOAD_SOURCE, SOURCE_AUTO)
            ?.takeIf { it in setOf(SOURCE_AUTO, SOURCE_MIRROR, SOURCE_OFFICIAL) }
            ?: SOURCE_AUTO

    fun setDownloadSource(context: Context, value: String) {
        prefs(context).edit().putString(KEY_DOWNLOAD_SOURCE, value).apply()
    }

    fun downloadSourceLabel(context: Context): String = when (downloadSource(context)) {
        SOURCE_MIRROR -> "国内镜像（hf-mirror）"
        SOURCE_OFFICIAL -> "官方源（HuggingFace）"
        else -> "自动测速（推荐）"
    }

    /** 0 = 自动（按大核数）。用户可在 CPU 压力页手动指定线程数。 */
    /** 0 = 自动（按大核数）。手动可选 1–16 线程（407 起上限固定 16，不再接受负值）。 */
    fun cpuThreads(context: Context): Int =
        prefs(context).getInt(KEY_CPU_THREADS, 0).coerceIn(0, 16)

    fun setCpuThreads(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_CPU_THREADS, value.coerceIn(0, 16)).apply()
    }

    fun lastModelId(context: Context): String? =
        prefs(context).getString(KEY_LAST_MODEL, null)?.takeIf { it.isNotBlank() }

    fun setLastModelId(context: Context, modelId: String?) {
        prefs(context).edit().apply {
            if (modelId.isNullOrBlank()) remove(KEY_LAST_MODEL) else putString(KEY_LAST_MODEL, modelId)
        }.apply()
    }
}
