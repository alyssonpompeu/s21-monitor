package com.alysson.offlineai

import android.os.Build
import java.io.File

/**
 * Runtime hardware fingerprint for the SM-G991B/Exynos 2100 target.
 *
 * Kernel nodes are discovered at runtime instead of hardcoded so stock and rooted custom kernels
 * can expose different policy/devfreq paths without breaking the app.
 */
class S21KernelProfile {

    data class CpuPolicy(
        val path: File,
        val affectedCpus: String,
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
    )

    data class Snapshot(
        val model: String,
        val device: String,
        val hardware: String,
        val kernel: String,
        val exactTarget: Boolean,
        val cpuPolicies: List<CpuPolicy>,
        val gpu: GpuNode?,
        val suBinaryPresent: Boolean,
    ) {
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
            if (suBinaryPresent) append(" • su detectado")
        }
    }

    fun snapshot(): Snapshot {
        val model = Build.MODEL.orEmpty()
        val device = Build.DEVICE.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val kernel = System.getProperty("os.version").orEmpty()
        val exactTarget = model.equals("SM-G991B", ignoreCase = true) &&
            (hardware.contains("exynos", ignoreCase = true) || readText(File("/proc/cpuinfo")).contains("exynos", ignoreCase = true))
        return Snapshot(
            model = model,
            device = device,
            hardware = hardware,
            kernel = kernel,
            exactTarget = exactTarget,
            cpuPolicies = discoverCpuPolicies(),
            gpu = discoverGpu(),
            suBinaryPresent = findSuBinary() != null,
        )
    }

    private fun discoverCpuPolicies(): List<CpuPolicy> {
        val root = File("/sys/devices/system/cpu/cpufreq")
        return root.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("policy") }
            .sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { dir ->
                val max = readLong(File(dir, "cpuinfo_max_freq"))
                    .takeIf { it > 0L }
                    ?: readLong(File(dir, "scaling_max_freq"))
                if (max <= 0L) return@mapNotNull null
                CpuPolicy(
                    path = dir,
                    affectedCpus = readText(File(dir, "affected_cpus")).ifBlank { readText(File(dir, "related_cpus")) },
                    minKHz = readLong(File(dir, "cpuinfo_min_freq")).takeIf { it > 0L }
                        ?: readLong(File(dir, "scaling_min_freq")),
                    maxKHz = max,
                    currentKHz = readLong(File(dir, "scaling_cur_freq")),
                    availableKHz = parseFrequencies(File(dir, "scaling_available_frequencies")),
                )
            }
    }

    private fun discoverGpu(): GpuNode? {
        val candidates = File("/sys/class/devfreq").listFiles().orEmpty().filter { it.isDirectory }
        val selected = candidates.firstOrNull { dir ->
            val text = (dir.name + " " + readText(File(dir, "name")) + " " + runCatching { dir.canonicalPath }.getOrDefault("")).lowercase()
            text.contains("mali") || text.contains("g3d") || text.contains("gpu")
        } ?: return null

        val max = readLong(File(selected, "max_freq")).takeIf { it > 0L }
            ?: parseFrequencies(File(selected, "available_frequencies")).maxOrNull()
            ?: 0L
        if (max <= 0L) return null
        return GpuNode(
            path = selected,
            name = readText(File(selected, "name")).ifBlank { selected.name },
            minHz = readLong(File(selected, "min_freq")),
            maxHz = max,
            currentHz = readLong(File(selected, "cur_freq")),
            availableHz = parseFrequencies(File(selected, "available_frequencies")),
        )
    }

    fun findSuBinary(): File? {
        val fixed = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/magisk/su",
        ).map(::File).firstOrNull { it.isFile && it.canExecute() }
        if (fixed != null) return fixed
        val path = System.getenv("PATH").orEmpty().split(':')
        return path.asSequence().map { File(it, "su") }.firstOrNull { it.isFile && it.canExecute() }
    }

    private fun parseFrequencies(file: File): List<Long> = readText(file)
        .split(Regex("\\s+"))
        .mapNotNull { it.toLongOrNull() }
        .filter { it > 0L }
        .distinct()
        .sorted()

    private fun readLong(file: File): Long = readText(file).trim().toLongOrNull() ?: 0L

    private fun readText(file: File): String = runCatching {
        if (file.isFile && file.canRead()) file.readText().trim() else ""
    }.getOrDefault("")
}
