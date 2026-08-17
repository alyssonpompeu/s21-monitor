#!/usr/bin/env python3
from pathlib import Path
import re

# v7.2 is intentionally applied AFTER v7.1 Recovery. It keeps the crash-safe boot path and
# specializes only hardware discovery/admission/root caps for the exact firmware supplied from
# the user's SM-G991B. No boot/vendor_boot/dtbo image is modified by this patch.

s21 = Path('offlineai/src/main/java/com/alysson/offlineai/S21KernelProfile.kt')
s21.write_text(r'''package com.alysson.offlineai

import android.os.Build
import java.io.File

/**
 * Runtime fingerprint and safe hardware discovery for the Galaxy S21 5G SM-G991B.
 *
 * v7.2 was tuned against a supplied firmware image whose kernel reports:
 *   5.4.242-30958140-abG991BXXSJHZA6
 * and whose DT describes Exynos 2100 clusters 0-3 / 4-6 / 7 and Mali-G78.
 *
 * IMPORTANT: values are still re-read from sysfs at runtime. The firmware constants below are
 * only validation/fallback data; a changed kernel never receives blind writes based on them.
 */
class S21KernelProfile {

    enum class ClusterRole { LITTLE, MID, BIG, UNKNOWN }

    data class CpuPolicy(
        val path: File,
        val affectedCpus: String,
        val role: ClusterRole,
        val minKHz: Long,
        val maxKHz: Long,
        val currentKHz: Long,
        val availableKHz: List<Long>,
    )

    data class GpuNode(
        val path: File,
        val name: String,
        val minHz: Long,
        val maxHz: Long,
        val currentHz: Long,
        val availableHz: List<Long>,
        val sustainableHz: Long?,
    )

    data class ThermalZone(val type: String, val tempC: Float)

    data class Snapshot(
        val model: String,
        val device: String,
        val hardware: String,
        val kernel: String,
        val exactTarget: Boolean,
        val firmwareMatch: Boolean,
        val cpuPolicies: List<CpuPolicy>,
        val gpu: GpuNode?,
        val thermalZones: List<ThermalZone>,
        val zramBytes: Long,
        val suBinaryPresent: Boolean,
    ) {
        val hottestRelevantC: Float?
            get() = thermalZones
                .filter { z ->
                    val t = z.type.uppercase()
                    t.contains("BIG") || t.contains("MID") || t.contains("LITTLE") ||
                        t.contains("G3D") || t.contains("GPU") || t.contains("CPU") || t.contains("SOC")
                }
                .maxOfOrNull { it.tempC }

        fun summary(): String = buildString {
            append(model.ifBlank { "Android" })
            append(" • ")
            append(hardware.ifBlank { "hardware desconhecido" })
            append(" • kernel ")
            append(kernel.ifBlank { "N/D" })
            append(" • ")
            append(cpuPolicies.size)
            append(" política(s) CPU")
            gpu?.let { append(" • GPU ${it.name}") }
            if (zramBytes > 0L) append(" • zRAM ${String.format("%.1f", zramBytes / GIB.toDouble())} GiB")
            hottestRelevantC?.let { append(" • ${String.format("%.0f", it)}°C") }
            if (firmwareMatch) append(" • firmware validado")
            else if (exactTarget) append(" • firmware diferente: modo adaptativo")
            if (suBinaryPresent) append(" • su detectado")
        }
    }

    fun snapshot(): Snapshot {
        val model = Build.MODEL.orEmpty()
        val device = Build.DEVICE.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val kernel = System.getProperty("os.version").orEmpty()
        val cpuInfo = readText(File("/proc/cpuinfo"))
        val exactTarget = model.equals("SM-G991B", ignoreCase = true) &&
            (hardware.contains("exynos", ignoreCase = true) || cpuInfo.contains("exynos", ignoreCase = true))
        val firmwareMatch = exactTarget && kernel.startsWith(EXPECTED_KERNEL, ignoreCase = true)
        return Snapshot(
            model = model,
            device = device,
            hardware = hardware,
            kernel = kernel,
            exactTarget = exactTarget,
            firmwareMatch = firmwareMatch,
            cpuPolicies = discoverCpuPolicies(),
            gpu = discoverGpu(firmwareMatch),
            thermalZones = discoverThermals(),
            zramBytes = discoverZramBytes(),
            suBinaryPresent = findSuBinary() != null,
        )
    }

    private fun discoverCpuPolicies(): List<CpuPolicy> {
        val root = File("/sys/devices/system/cpu/cpufreq")
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("policy") }
            .sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { dir ->
                val affected = readText(File(dir, "affected_cpus")).ifBlank { readText(File(dir, "related_cpus")) }
                val max = readLong(File(dir, "cpuinfo_max_freq")).takeIf { it > 0L }
                    ?: readLong(File(dir, "scaling_max_freq"))
                if (max <= 0L) return@mapNotNull null
                val available = (parseFrequencies(File(dir, "scaling_available_frequencies")) +
                    parseTimeInState(File(dir, "stats/time_in_state")))
                    .filter { it > 0L && it <= max }
                    .distinct().sorted()
                CpuPolicy(
                    path = dir,
                    affectedCpus = affected,
                    role = classifyCluster(affected),
                    minKHz = readLong(File(dir, "cpuinfo_min_freq")).takeIf { it > 0L }
                        ?: readLong(File(dir, "scaling_min_freq")),
                    maxKHz = max,
                    currentKHz = readLong(File(dir, "scaling_cur_freq")),
                    availableKHz = available,
                )
            }
    }

    private fun classifyCluster(cpus: String): ClusterRole {
        val ids = cpus.replace('-', ' ').split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }.toSet()
        return when {
            cpus.trim() == "7" || (7 in ids && ids.size == 1) -> ClusterRole.BIG
            cpus.contains("4-6") || (ids.containsAll(setOf(4, 6)) && 7 !in ids) -> ClusterRole.MID
            cpus.contains("0-3") || (0 in ids && 3 in ids) -> ClusterRole.LITTLE
            else -> ClusterRole.UNKNOWN
        }
    }

    private fun discoverGpu(firmwareMatch: Boolean): GpuNode? {
        val candidates = File("/sys/class/devfreq").listFiles().orEmpty().filter { it.isDirectory }
        val selected = candidates.firstOrNull { dir ->
            val text = (dir.name + " " + readText(File(dir, "name")) + " " +
                runCatching { dir.canonicalPath }.getOrDefault("")).lowercase()
            text.contains("mali") || text.contains("g3d") || text.contains("gpu")
        } ?: return null

        val availableRuntime = (parseFrequencies(File(selected, "available_frequencies")) +
            parseTimeInState(File(selected, "time_in_state")) +
            parseTimeInState(File(selected, "stats/time_in_state")))
            .distinct().sorted()
        val max = readLong(File(selected, "max_freq")).takeIf { it > 0L }
            ?: availableRuntime.maxOrNull()
            ?: 0L
        if (max <= 0L) return null
        val fallback = if (firmwareMatch) VALIDATED_GPU_FREQS_HZ.filter { it <= max } else emptyList()
        return GpuNode(
            path = selected,
            name = readText(File(selected, "name")).ifBlank { selected.name },
            minHz = readLong(File(selected, "min_freq")).takeIf { it > 0L }
                ?: (availableRuntime + fallback).minOrNull() ?: 0L,
            maxHz = max,
            currentHz = readLong(File(selected, "cur_freq")),
            availableHz = (availableRuntime + fallback).filter { it in 1..max }.distinct().sorted(),
            sustainableHz = if (firmwareMatch && max >= VALIDATED_GPU_SUSTAINABLE_HZ) VALIDATED_GPU_SUSTAINABLE_HZ else null,
        )
    }

    private fun discoverThermals(): List<ThermalZone> = File("/sys/class/thermal").listFiles().orEmpty()
        .asSequence()
        .filter { it.isDirectory && it.name.startsWith("thermal_zone") }
        .mapNotNull { dir ->
            val raw = readLong(File(dir, "temp"))
            if (raw <= 0L) return@mapNotNull null
            val c = if (raw > 1000L) raw / 1000f else raw.toFloat()
            if (c !in -20f..150f) return@mapNotNull null
            ThermalZone(readText(File(dir, "type")).ifBlank { dir.name }, c)
        }.toList()

    private fun discoverZramBytes(): Long {
        val direct = readLong(File("/sys/block/zram0/disksize"))
        if (direct > 0L) return direct
        val swaps = readText(File("/proc/swaps")).lineSequence()
            .firstOrNull { it.contains("zram0") }.orEmpty()
            .trim().split(Regex("\\s+"))
        // /proc/swaps Size is KiB.
        return swaps.getOrNull(2)?.toLongOrNull()?.times(1024L) ?: 0L
    }

    fun findSuBinary(): File? {
        val fixed = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/data/adb/magisk/su",
        ).map(::File).firstOrNull { it.isFile && it.canExecute() }
        if (fixed != null) return fixed
        return System.getenv("PATH").orEmpty().split(':').asSequence()
            .map { File(it, "su") }.firstOrNull { it.isFile && it.canExecute() }
    }

    private fun parseFrequencies(file: File): List<Long> = readText(file)
        .split(Regex("\\s+"))
        .mapNotNull { it.toLongOrNull() }
        .filter { it > 0L }

    private fun parseTimeInState(file: File): List<Long> = readText(file).lineSequence()
        .mapNotNull { line -> line.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() }
        .filter { it > 0L }.toList()

    private fun readLong(file: File): Long = readText(file).trim().toLongOrNull() ?: 0L
    private fun readText(file: File): String = runCatching {
        if (file.isFile && file.canRead()) file.readText().trim() else ""
    }.getOrDefault("")

    companion object {
        const val EXPECTED_KERNEL = "5.4.242-30958140-abG991BXXSJHZA6"
        const val VALIDATED_GPU_SUSTAINABLE_HZ = 494_000_000L
        val VALIDATED_GPU_FREQS_HZ = listOf(
            130_000_000L, 221_000_000L, 312_000_000L, 403_000_000L, 494_000_000L,
            585_000_000L, 676_000_000L, 767_000_000L, 858_000_000L,
        )
        private const val GIB = 1024L * 1024L * 1024L
    }
}
''', encoding='utf-8')

root = Path('offlineai/src/main/java/com/alysson/offlineai/RootPerformanceController.kt')
root.write_text(r'''package com.alysson.offlineai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Root bridge specialized for the SM-G991B while remaining fail-closed on changed hardware. */
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
    fun setEnabled(enabled: Boolean) { prefs.edit().putBoolean(KEY_ENABLED, enabled).apply() }
    fun hardwareSummary(): String = profiler.snapshot().summary()

    suspend fun apply(limits: PerformanceSettings.Limits): ApplyResult = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext ApplyResult(false, "Gerenciamento root está desativado.")
        val snapshot = profiler.snapshot()
        if (!snapshot.exactTarget) return@withContext ApplyResult(false,
            "Escrita root bloqueada: este perfil só aceita SM-G991B Exynos 2100 detectado em tempo de execução.")
        applyTargets(snapshot, limits.cpuPercent, limits.gpuPercent, sustained = false)
    }

    /**
     * Daily-use profile for the supplied firmware: keeps the X1 below the A78 cluster's thermal
     * burden and uses the DT-declared 494 MHz sustainable GPU clock. CPU values are selected from
     * the frequencies the running kernel actually exposes; no CPU 'sustainable clock' is invented.
     */
    suspend fun applySustainedAi(): ApplyResult = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext ApplyResult(false, "Ative o gerenciamento root primeiro.")
        val snapshot = profiler.snapshot()
        if (!snapshot.firmwareMatch) return@withContext ApplyResult(false,
            "Perfil IA Sustentada requer o firmware validado ${S21KernelProfile.EXPECTED_KERNEL}. O modo manual/adaptativo continua disponível.")
        applyTargets(snapshot, cpuPercent = 82, gpuPercent = 58, sustained = true)
    }

    private fun applyTargets(
        snapshot: S21KernelProfile.Snapshot,
        cpuPercent: Int,
        gpuPercent: Int,
        sustained: Boolean,
    ): ApplyResult {
        val su = profiler.findSuBinary() ?: return ApplyResult(false, "Binário su não encontrado.")
        val commands = mutableListOf<String>()
        val labels = mutableListOf<String>()
        var cpuChanged = 0

        snapshot.cpuPolicies.forEach { policy ->
            val scalingMax = File(policy.path, "scaling_max_freq")
            if (!scalingMax.exists()) return@forEach
            val requested = if (sustained) when (policy.role) {
                S21KernelProfile.ClusterRole.LITTLE -> 86
                S21KernelProfile.ClusterRole.MID -> 84
                S21KernelProfile.ClusterRole.BIG -> 76
                S21KernelProfile.ClusterRole.UNKNOWN -> 80
            } else cpuPercent
            val target = chooseDiscreteTarget(
                min = policy.minKHz.coerceAtLeast(1L), max = policy.maxKHz,
                percent = requested, available = policy.availableKHz,
            )
            commands += "printf '%s' '$target' > '${shellPath(scalingMax)}'"
            labels += "${policy.role.name.lowercase()}=${target / 1000}MHz"
            cpuChanged++
        }

        var gpuChanged = false
        var gpuLabel = ""
        snapshot.gpu?.let { gpu ->
            val maxFile = File(gpu.path, "max_freq")
            if (maxFile.exists() && gpuPercent > 0) {
                val target = if (sustained && gpu.sustainableHz != null) {
                    gpu.sustainableHz.coerceIn(gpu.minHz.coerceAtLeast(1L), gpu.maxHz)
                } else {
                    chooseDiscreteTarget(gpu.minHz.coerceAtLeast(1L), gpu.maxHz, gpuPercent, gpu.availableHz)
                }
                commands += "printf '%s' '$target' > '${shellPath(maxFile)}'"
                gpuChanged = true
                gpuLabel = " • GPU ${target / 1_000_000}MHz"
            }
        }

        if (commands.isEmpty()) return ApplyResult(false, "Kernel detectado, mas nenhum nó seguro de frequência máxima foi encontrado.")
        val exec = runSu(su, commands.joinToString(" ; "))
        if (!exec.first) return ApplyResult(false, "Root recusou ou o kernel rejeitou os limites: ${exec.second.take(220)}")
        return ApplyResult(
            true,
            if (sustained) "IA Sustentada aplicada • ${labels.joinToString(" • ")}$gpuLabel • térmico do kernel preservado."
            else "Limites discretos aplicados • ${labels.joinToString(" • ")}$gpuLabel. Sem overclock/undervolt.",
            cpuChanged, gpuChanged,
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
        ApplyResult(true, "Máximos stock expostos pelo kernel foram restaurados.", cpuChanged, gpuChanged)
    }

    private fun chooseDiscreteTarget(min: Long, max: Long, percent: Int, available: List<Long>): Long {
        val p = percent.coerceIn(25, 100)
        val valid = available.filter { it in min..max }.distinct().sorted()
        if (valid.isNotEmpty()) {
            val idx = (((valid.size - 1) * p) / 100.0).roundToInt().coerceIn(0, valid.lastIndex)
            return valid[idx]
        }
        return (min + ((max - min).coerceAtLeast(0L) * (p / 100.0)).roundToLong()).coerceIn(min, max)
    }

    private fun runSu(su: File, script: String): Pair<Boolean, String> = runCatching {
        val process = ProcessBuilder(su.absolutePath, "-c", script).redirectErrorStream(true).start()
        val finished = process.waitFor(8, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly(); false to "timeout aguardando su"
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            (process.exitValue() == 0) to output
        }
    }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }

    private fun shellPath(file: File): String = file.absolutePath.replace("'", "")

    companion object {
        private const val PREFS = "unilaw_root_perf_v6"
        private const val KEY_ENABLED = "root_limits_enabled"
    }
}
''', encoding='utf-8')

guard = Path('offlineai/src/main/java/com/alysson/offlineai/ResourceGuard.kt')
guard.write_text(r'''package com.alysson.offlineai

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.PowerManager
import kotlin.math.max
import kotlin.math.min

/** Memory/thermal admission control tuned to the supplied SM-G991B firmware. */
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
    private val profiler = S21KernelProfile()

    fun state(kind: TaskKind): State {
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val hw = runCatching { profiler.snapshot() }.getOrNull()
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
        val thermal = runCatching { powerManager.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val hottest = hw?.hottestRelevantC
        val limits = performance.limits()
        val processBytes = runCatching { Debug.getPss().coerceAtLeast(0L) * 1024L }.getOrDefault(0L)
        val userProcessLimit = (memory.totalMem * (limits.ramPercent / 100.0)).toLong()
        val residentTarget = min(TWO_GIB, userProcessLimit)
        val checkpointSuggested = processBytes > 0L && (
            processBytes >= (residentTarget * 0.82).toLong() || memory.availMem <= reserve + 512L * MIB ||
                (hottest != null && hottest >= 68f)
        )

        val firmwareThermalBlock = when (kind) {
            TaskKind.IMAGE -> hottest != null && hottest >= 71.5f
            TaskKind.CODER, TaskKind.CHAT -> hottest != null && hottest >= 74f
            TaskKind.BUILDER -> hottest != null && hottest >= 76f
        }
        val reason = when {
            memory.lowMemory -> "O Android marcou memória baixa. A tarefa foi interrompida após checkpoint."
            processBytes > 0L && processBytes >= userProcessLimit -> "O processo atingiu o limite de RAM configurado (${limits.ramPercent}% do total)."
            memory.availMem <= reserve -> "Pouca RAM disponível: foi preservada uma reserva para manter o sistema responsivo."
            firmwareThermalBlock -> "Exynos 2100 quente (${String.format("%.1f", hottest)}°C): tarefa pesada pausada para evitar throttling prolongado."
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> "Android sinalizou pressão térmica severa; aguarde o aparelho esfriar."
            else -> null
        }

        return State(reason == null, reason, memory.availMem, memory.totalMem, reserve, processBytes,
            userProcessLimit, residentTarget, checkpointSuggested, memory.lowMemory, thermal)
    }

    fun shortStatus(): String {
        val s = state(TaskKind.CHAT)
        val limits = performance.limits()
        val hw = runCatching { profiler.snapshot() }.getOrNull()
        val temp = hw?.hottestRelevantC?.let { " • ${String.format("%.0f", it)}°C" }.orEmpty()
        val zram = hw?.zramBytes?.takeIf { it > 0 }?.let { " • zRAM ${String.format("%.1f", it / GIB.toDouble())}G" }.orEmpty()
        return if (s.safe) {
            "Proteção S21 • CPU ${limits.cpuPercent}% • GPU ${limits.gpuPercent}% • RAM ${limits.ramPercent}%$temp$zram"
        } else "Proteção S21 • checkpoint / tarefa limitada$temp"
    }

    companion object {
        private const val MIB = 1024L * 1024L
        private const val GIB = 1024L * MIB
        private const val TWO_GIB = 2L * GIB
    }
}
''', encoding='utf-8')

# The generated v6/v7 Plugin screen already has the S21/kernel dialog. Extend it without replacing
# the whole UI so v7 model/library cards remain intact.
plugin_path = Path('offlineai/src/main/java/com/alysson/offlineai/PluginManagerActivity.kt')
plugin = plugin_path.read_text(encoding='utf-8')
plugin = plugin.replace(
    'appendLine("A v6 só reduz/restaura frequências máximas já expostas pelo kernel. Não faz overclock, undervolt, mudança de governor, escrita de tensão ou ajuste de LMKD/zRAM.")',
    'appendLine("v7.2 usa as frequências discretas expostas pelo kernel e reconhece o firmware validado. GPU sustentada do DT: 494 MHz. Não altera governor, tensão, mínimos, thermal trips, LMKD ou zRAM.")',
    1,
)
plugin = plugin.replace(
    'val positive = if (rootController.enabled()) "Aplicar limites" else "Ativar e aplicar"',
    'val positive = if (rootController.enabled()) "IA Sustentada" else "Ativar IA Sustentada"',
    1,
)
old_positive = '''            .setPositiveButton(positive) { _, _ ->
                rootController.setEnabled(true)
                scope.launch {
                    val applied = rootController.apply(performance.limits())
                    if (!applied.applied) rootController.setEnabled(false)
                    status.text = applied.message
                    refresh()
                }
            }
'''
new_positive = '''            .setPositiveButton(positive) { _, _ ->
                rootController.setEnabled(true)
                scope.launch {
                    val applied = rootController.applySustainedAi()
                    if (!applied.applied) rootController.setEnabled(false)
                    status.text = applied.message
                    refresh()
                }
            }
'''
if old_positive not in plugin:
    raise SystemExit('v7.2 patch point missing: root dialog positive button')
plugin = plugin.replace(old_positive, new_positive, 1)
plugin_path.write_text(plugin, encoding='utf-8')

# Keep the v7.1 non-minified recovery build for one more field release; hardware tuning must be
# validated on the physical phone before R8 is reintroduced.
gradle_path = Path('offlineai/build.gradle')
gradle = gradle_path.read_text(encoding='utf-8')
gradle, n = re.subn(r'versionCode\s+\d+', 'versionCode 22', gradle, count=1)
if n != 1: raise SystemExit('v7.2 could not update versionCode')
gradle, n = re.subn(r"versionName\s+'[^']+'", "versionName '7.2.0-s21-firmware-tuned'", gradle, count=1)
if n != 1: raise SystemExit('v7.2 could not update versionName')
gradle_path.write_text(gradle, encoding='utf-8')

manifest_path = Path('offlineai/src/main/AndroidManifest.xml')
manifest = manifest_path.read_text(encoding='utf-8')
manifest = re.sub(r'android:label="[^"]+"', 'android:label="Unilaw AI • S21 Tuned"', manifest, count=1)
manifest_path.write_text(manifest, encoding='utf-8')

print('Workspace v7.2 S21 firmware tuning applied: exact kernel fingerprint, thermal/zRAM awareness, discrete root caps and 494 MHz sustainable GPU profile')
