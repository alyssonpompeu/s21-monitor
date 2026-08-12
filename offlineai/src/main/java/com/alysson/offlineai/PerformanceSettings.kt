package com.alysson.offlineai

import android.content.Context

/**
 * User-facing performance policy. Percentages are safety targets, not kernel-enforced quotas:
 * Android does not expose reliable per-app GPU percentage throttling and native inference can
 * briefly exceed a CPU target. The app uses these values to select backends, worker counts,
 * pacing and RAM admission limits while thermal/memory guards remain authoritative.
 */
class PerformanceSettings(context: Context) {

    enum class Backend(val label: String) {
        AUTO("Automático"),
        CPU("CPU"),
        VULKAN("Vulkan / GPU (experimental)")
    }

    data class Limits(
        val cpuPercent: Int,
        val gpuPercent: Int,
        val ramPercent: Int,
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun limits(): Limits = Limits(
        cpuPercent = prefs.getInt(KEY_CPU, DEFAULT_CPU).coerceIn(MIN_CPU, MAX_CPU),
        gpuPercent = prefs.getInt(KEY_GPU, DEFAULT_GPU).coerceIn(MIN_GPU, MAX_GPU),
        ramPercent = prefs.getInt(KEY_RAM, DEFAULT_RAM).coerceIn(MIN_RAM, MAX_RAM),
    )

    fun setLimits(cpuPercent: Int, gpuPercent: Int, ramPercent: Int) {
        prefs.edit()
            .putInt(KEY_CPU, cpuPercent.coerceIn(MIN_CPU, MAX_CPU))
            .putInt(KEY_GPU, gpuPercent.coerceIn(MIN_GPU, MAX_GPU))
            .putInt(KEY_RAM, ramPercent.coerceIn(MIN_RAM, MAX_RAM))
            .apply()
    }

    fun backend(pluginId: String): Backend {
        val fallback = defaultBackend(pluginId)
        return runCatching {
            Backend.valueOf(prefs.getString(KEY_BACKEND_PREFIX + pluginId, fallback.name).orEmpty())
        }.getOrDefault(fallback)
    }

    fun setBackend(pluginId: String, backend: Backend) {
        prefs.edit().putString(KEY_BACKEND_PREFIX + pluginId, backend.name).apply()
    }

    fun supportedBackends(pluginId: String): List<Backend> = when (pluginId) {
        IMAGE_PLUGIN_ID -> listOf(Backend.AUTO, Backend.CPU, Backend.VULKAN)
        CODER_PLUGIN_ID -> listOf(Backend.AUTO, Backend.CPU)
        BUILDER_PLUGIN_ID -> listOf(Backend.CPU)
        else -> listOf(Backend.AUTO, Backend.CPU)
    }

    fun effectiveBackend(pluginId: String, vulkanAvailable: Boolean = false): Backend {
        val requested = backend(pluginId)
        val limits = limits()
        return when (requested) {
            Backend.CPU -> Backend.CPU
            Backend.VULKAN -> if (vulkanAvailable && limits.gpuPercent >= 25) Backend.VULKAN else Backend.CPU
            Backend.AUTO -> when (pluginId) {
                // Galaxy S21 Exynos/Mali keeps CPU as the conservative default. Vulkan is opt-in.
                IMAGE_PLUGIN_ID -> Backend.CPU
                else -> Backend.CPU
            }
        }
    }

    fun imageThreads(): Int = when (limits().cpuPercent) {
        in MIN_CPU..39 -> 1
        in 40..74 -> 2
        else -> 3
    }

    fun tokenPacingDelayMs(): Long = when (limits().cpuPercent) {
        in MIN_CPU..39 -> 70L
        in 40..54 -> 35L
        in 55..69 -> 15L
        else -> 0L
    }

    fun recommendation(pluginId: String): String = when (pluginId) {
        IMAGE_PLUGIN_ID -> "CPU é o padrão seguro no S21 Exynos. Vulkan fica disponível para teste manual e volta para CPU se indisponível."
        CODER_PLUGIN_ID -> "CPU é recomendada nesta compilação. O modelo Coder roda separado do Qwen principal para reduzir RAM."
        BUILDER_PLUGIN_ID -> "CPU/I/O. O Builder usa shells pré-compilados e não precisa de GPU."
        else -> "Automático prioriza estabilidade e respeita os limites globais."
    }

    private fun defaultBackend(pluginId: String): Backend = when (pluginId) {
        IMAGE_PLUGIN_ID, CODER_PLUGIN_ID, BUILDER_PLUGIN_ID -> Backend.CPU
        else -> Backend.AUTO
    }

    companion object {
        const val IMAGE_PLUGIN_ID = "image.tinysd"
        const val CODER_PLUGIN_ID = PluginPackManager.CODER_PACK_ID
        const val BUILDER_PLUGIN_ID = PluginPackManager.BUILDER_PACK_ID

        const val MIN_CPU = 25
        const val MAX_CPU = 90
        const val MIN_GPU = 0
        const val MAX_GPU = 90
        const val MIN_RAM = 35
        const val MAX_RAM = 75

        private const val DEFAULT_CPU = 65
        private const val DEFAULT_GPU = 50
        private const val DEFAULT_RAM = 55
        private const val PREFS = "offline_ai_performance_v4"
        private const val KEY_CPU = "cpu_limit"
        private const val KEY_GPU = "gpu_budget"
        private const val KEY_RAM = "ram_limit"
        private const val KEY_BACKEND_PREFIX = "backend_"
    }
}
