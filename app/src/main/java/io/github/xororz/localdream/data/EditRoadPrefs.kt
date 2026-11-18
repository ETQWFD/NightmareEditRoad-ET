package io.github.xororz.localdream.data

import android.content.Context

/**
 * NERT 只暴露少量稳定可调项：步数、渲染器、下载源、CPU 线程。
 * 尺寸、CFG、调度器、反向提示、重绘强度都在代码里按机型配好。
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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun steps(context: Context): Int =
        prefs(context).getInt(KEY_STEPS, STEPS_DEFAULT).coerceIn(STEPS_MIN, STEPS_MAX)

    fun setSteps(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_STEPS, value.coerceIn(STEPS_MIN, STEPS_MAX)).apply()
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
    fun cpuThreads(context: Context): Int =
        prefs(context).getInt(KEY_CPU_THREADS, 0).coerceIn(0, 32)

    fun setCpuThreads(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_CPU_THREADS, value.coerceIn(0, 32)).apply()
    }

    fun lastModelId(context: Context): String? =
        prefs(context).getString(KEY_LAST_MODEL, null)?.takeIf { it.isNotBlank() }

    fun setLastModelId(context: Context, modelId: String?) {
        prefs(context).edit().apply {
            if (modelId.isNullOrBlank()) remove(KEY_LAST_MODEL) else putString(KEY_LAST_MODEL, modelId)
        }.apply()
    }
}
