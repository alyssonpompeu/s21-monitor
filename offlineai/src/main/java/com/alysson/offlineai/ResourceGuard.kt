package com.alysson.offlineai

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import kotlin.math.max

/**
 * Conservative admission control for heavy local inference.
 * It cannot control every kernel scheduler decision, but it deliberately keeps RAM headroom,
 * limits native worker threads in the build, and blocks work under severe thermal pressure.
 */
class ResourceGuard(context: Context) {

    enum class TaskKind { CHAT, IMAGE }

    data class State(
        val safe: Boolean,
        val reason: String?,
        val availableBytes: Long,
        val totalBytes: Long,
        val reserveBytes: Long,
        val lowMemory: Boolean,
        val thermalStatus: Int,
    )

    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    fun state(kind: TaskKind): State {
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val fractionReserve = when (kind) {
            TaskKind.CHAT -> (memory.totalMem * 0.16).toLong()
            TaskKind.IMAGE -> (memory.totalMem * 0.24).toLong()
        }
        val fixedReserve = when (kind) {
            TaskKind.CHAT -> 900L * 1024 * 1024
            TaskKind.IMAGE -> 1400L * 1024 * 1024
        }
        val reserve = max(max(fractionReserve, fixedReserve), memory.threshold * 2)
        val thermal = runCatching { powerManager.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)

        val reason = when {
            memory.lowMemory -> "O Android marcou o aparelho como memória baixa. Aguarde ou feche outros apps."
            memory.availMem <= reserve -> "Pouca RAM disponível: o app preserva uma reserva para evitar travamento do sistema."
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> "Temperatura elevada: a tarefa foi bloqueada até o aparelho esfriar."
            else -> null
        }

        return State(
            safe = reason == null,
            reason = reason,
            availableBytes = memory.availMem,
            totalBytes = memory.totalMem,
            reserveBytes = reserve,
            lowMemory = memory.lowMemory,
            thermalStatus = thermal,
        )
    }

    fun shortStatus(): String {
        val s = state(TaskKind.CHAT)
        val reserveGb = s.reserveBytes / (1024.0 * 1024.0 * 1024.0)
        return if (s.safe) {
            "Proteção ativa • reserva ${String.format("%.1f", reserveGb)} GB"
        } else {
            "Proteção ativa • tarefa limitada"
        }
    }
}
