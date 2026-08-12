package com.alysson.offlineai

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.PowerManager
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative admission control for heavy local inference.
 *
 * Android owns paging/zRAM, so the app cannot reserve an exact amount of physical RAM or create a
 * real 5 GiB swap device. V5 keeps the user RAM percentage as the hard admission ceiling and uses
 * 2 GiB as a soft resident-working-set target that triggers durable checkpoints before pressure
 * becomes critical. Thermal pressure and the system free-memory reserve remain authoritative.
 */
class ResourceGuard(context: Context) {

    enum class TaskKind { CHAT, IMAGE, CODER, BUILDER }

    data class State(
        val safe: Boolean,
        val reason: String?,
        val availableBytes: Long,
        val totalBytes: Long,
        val reserveBytes: Long,
        val currentProcessBytes: Long,
        val userProcessLimitBytes: Long,
        val residentTargetBytes: Long,
        val checkpointSuggested: Boolean,
        val lowMemory: Boolean,
        val thermalStatus: Int,
    )

    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val performance = PerformanceSettings(context.applicationContext)

    fun state(kind: TaskKind): State {
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val fractionReserve = when (kind) {
            TaskKind.CHAT, TaskKind.CODER -> (memory.totalMem * 0.16).toLong()
            TaskKind.IMAGE -> (memory.totalMem * 0.24).toLong()
            TaskKind.BUILDER -> (memory.totalMem * 0.12).toLong()
        }
        val fixedReserve = when (kind) {
            TaskKind.CHAT, TaskKind.CODER -> 900L * MIB
            TaskKind.IMAGE -> 1400L * MIB
            TaskKind.BUILDER -> 700L * MIB
        }
        val reserve = max(max(fractionReserve, fixedReserve), memory.threshold * 2)
        val thermal = runCatching { powerManager.currentThermalStatus }
            .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val limits = performance.limits()
        val processBytes = runCatching { Debug.getPss().coerceAtLeast(0L) * 1024L }.getOrDefault(0L)
        val userProcessLimit = (memory.totalMem * (limits.ramPercent / 100.0)).toLong()
        val residentTarget = min(TWO_GIB, userProcessLimit)
        val checkpointSuggested = processBytes > 0L && (
            processBytes >= (residentTarget * 0.82).toLong() ||
                memory.availMem <= reserve + 512L * MIB
            )

        val reason = when {
            memory.lowMemory -> "O Android marcou o aparelho como memória baixa. A tarefa foi interrompida depois de salvar um checkpoint."
            processBytes > 0L && processBytes >= userProcessLimit ->
                "O processo atingiu o limite de RAM configurado (${limits.ramPercent}% do total)."
            memory.availMem <= reserve ->
                "Pouca RAM disponível: o app preserva uma reserva para evitar travamento do sistema."
            thermal >= PowerManager.THERMAL_STATUS_SEVERE ->
                "Temperatura elevada: a tarefa foi bloqueada até o aparelho esfriar."
            else -> null
        }

        return State(
            safe = reason == null,
            reason = reason,
            availableBytes = memory.availMem,
            totalBytes = memory.totalMem,
            reserveBytes = reserve,
            currentProcessBytes = processBytes,
            userProcessLimitBytes = userProcessLimit,
            residentTargetBytes = residentTarget,
            checkpointSuggested = checkpointSuggested,
            lowMemory = memory.lowMemory,
            thermalStatus = thermal,
        )
    }

    fun shortStatus(): String {
        val s = state(TaskKind.CHAT)
        val limits = performance.limits()
        val reserveGb = s.reserveBytes / GIB.toDouble()
        val targetGb = s.residentTargetBytes / GIB.toDouble()
        return if (s.safe) {
            "Proteção ativa • CPU ${limits.cpuPercent}% • GPU ${limits.gpuPercent}% • RAM ${limits.ramPercent}% • alvo ${String.format("%.1f", targetGb)} GB • reserva ${String.format("%.1f", reserveGb)} GB"
        } else {
            "Proteção ativa • checkpoint salvo / tarefa limitada"
        }
    }

    companion object {
        private const val MIB = 1024L * 1024L
        private const val GIB = 1024L * MIB
        private const val TWO_GIB = 2L * GIB
    }
}
