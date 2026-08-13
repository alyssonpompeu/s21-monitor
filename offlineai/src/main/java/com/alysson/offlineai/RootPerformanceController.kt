package com.alysson.offlineai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Optional root bridge for the exact SM-G991B target.
 *
 * Safety rules:
 * - never overclocks and never writes voltage nodes;
 * - never changes governors or CPU minimum frequencies;
 * - only lowers/restores scaling_max_freq using stock kernel limits already exposed by sysfs;
 * - GPU writes are limited to the discovered stock max and are skipped when no devfreq node exists;
 * - root is disabled by default and must be explicitly enabled by the user.
 */
class RootPerformanceController(context: Context) {

    data class ApplyResult(
        val applied: Boolean,
        val message: String,
        val cpuPoliciesChanged: Int = 0,
        val gpuChanged: Boolean = false,
    )

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val profiler = S21KernelProfile()

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun hardwareSummary(): String = profiler.snapshot().summary()

    suspend fun apply(limits: PerformanceSettings.Limits): ApplyResult = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext ApplyResult(false, "Gerenciamento root está desativado.")
        val snapshot = profiler.snapshot()
        if (!snapshot.exactTarget) {
            return@withContext ApplyResult(false, "Escrita root bloqueada: esta build só altera limites no SM-G991B Exynos 2100 detectado em tempo de execução.")
        }
        val su = profiler.findSuBinary()
            ?: return@withContext ApplyResult(false, "Binário su não encontrado. O monitoramento continua disponível sem root.")

        val commands = mutableListOf<String>()
        var cpuChanged = 0
        snapshot.cpuPolicies.forEach { policy ->
            val scalingMax = File(policy.path, "scaling_max_freq")
            if (!scalingMax.exists()) return@forEach
            val target = chooseTarget(
                min = policy.minKHz.coerceAtLeast(1L),
                max = policy.maxKHz,
                percent = limits.cpuPercent,
                available = policy.availableKHz,
            )
            commands += "printf '%s' '${target}' > '${shellPath(scalingMax)}'"
            cpuChanged++
        }

        var gpuChanged = false
        snapshot.gpu?.let { gpu ->
            val maxFile = File(gpu.path, "max_freq")
            if (maxFile.exists() && limits.gpuPercent > 0) {
                val target = chooseTarget(
                    min = gpu.minHz.coerceAtLeast(1L),
                    max = gpu.maxHz,
                    percent = limits.gpuPercent,
                    available = gpu.availableHz,
                )
                commands += "printf '%s' '${target}' > '${shellPath(maxFile)}'"
                gpuChanged = true
            }
        }

        if (commands.isEmpty()) {
            return@withContext ApplyResult(false, "Kernel detectado, mas nenhum nó seguro de limite máximo foi encontrado.")
        }
        val exec = runSu(su, commands.joinToString(" ; "))
        if (!exec.first) {
            return@withContext ApplyResult(false, "Root recusou ou o kernel rejeitou os limites: ${exec.second.take(220)}")
        }
        ApplyResult(
            applied = true,
            message = "Limites aplicados sem overclock: CPU ${limits.cpuPercent}% • GPU ${limits.gpuPercent}%. RAM continua sendo controlada pelo orçamento do app/Android.",
            cpuPoliciesChanged = cpuChanged,
            gpuChanged = gpuChanged,
        )
    }

    suspend fun restoreStockMaximums(): ApplyResult = withContext(Dispatchers.IO) {
        val snapshot = profiler.snapshot()
        if (!snapshot.exactTarget) return@withContext ApplyResult(false, "Restauração root bloqueada fora do SM-G991B Exynos 2100.")
        val su = profiler.findSuBinary() ?: return@withContext ApplyResult(false, "Binário su não encontrado.")
        val commands = mutableListOf<String>()
        var cpuChanged = 0
        snapshot.cpuPolicies.forEach { policy ->
            val scalingMax = File(policy.path, "scaling_max_freq")
            if (scalingMax.exists() && policy.maxKHz > 0L) {
                commands += "printf '%s' '${policy.maxKHz}' > '${shellPath(scalingMax)}'"
                cpuChanged++
            }
        }
        var gpuChanged = false
        snapshot.gpu?.let { gpu ->
            val maxFile = File(gpu.path, "max_freq")
            if (maxFile.exists() && gpu.maxHz > 0L) {
                commands += "printf '%s' '${gpu.maxHz}' > '${shellPath(maxFile)}'"
                gpuChanged = true
            }
        }
        if (commands.isEmpty()) return@withContext ApplyResult(false, "Nenhum limite restaurável foi detectado.")
        val exec = runSu(su, commands.joinToString(" ; "))
        if (!exec.first) return@withContext ApplyResult(false, "Falha ao restaurar limites: ${exec.second.take(220)}")
        ApplyResult(true, "Limites máximos expostos pelo kernel foram restaurados.", cpuChanged, gpuChanged)
    }

    private fun chooseTarget(min: Long, max: Long, percent: Int, available: List<Long>): Long {
        val safePercent = percent.coerceIn(25, 100)
        val raw = min + ((max - min).coerceAtLeast(0L) * (safePercent / 100.0)).roundToLong()
        val bounded = raw.coerceIn(min, max)
        if (available.isEmpty()) return bounded
        return available.lastOrNull { it <= bounded } ?: available.first().coerceAtMost(max)
    }

    private fun runSu(su: File, script: String): Pair<Boolean, String> {
        return runCatching {
            val process = ProcessBuilder(su.absolutePath, "-c", script)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false to "timeout aguardando su"
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                (process.exitValue() == 0) to output
            }
        }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }
    }

    private fun shellPath(file: File): String = file.absolutePath.replace("'", "")

    companion object {
        private const val PREFS = "unilaw_root_perf_v6"
        private const val KEY_ENABLED = "root_limits_enabled"
    }
}
