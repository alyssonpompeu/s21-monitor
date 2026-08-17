#!/usr/bin/env python3
from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'v7.2 patch point missing: {label}')
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# Exact firmware-derived profile.
# Source basis: user-supplied boot/vendor_boot/dtbo from SM-G991B G991BXXSJHZA6.
# The app only READS runtime sysfs/procfs unless the already-existing optional root
# controller is explicitly enabled by the user.
# -----------------------------------------------------------------------------
profile_path = Path('offlineai/src/main/java/com/alysson/offlineai/S21FirmwareProfile.kt')
profile_path.write_text(r'''package com.alysson.offlineai

import android.os.Build
import java.io.File

/** Exact optimization profile for the supplied Galaxy S21 firmware. */
object S21FirmwareProfile {
    const val MODEL = "SM-G991B"
    const val FIRMWARE = "G991BXXSJHZA6"
    const val KERNEL = "5.4.242-30958140-abG991BXXSJHZA6"
    const val ZRAM_BYTES = 3_221_225_472L

    data class CpuCluster(
        val cpus: String,
        val microarch: String,
        val capacity: Int,
        val minKHz: Long,
        val maxKHz: Long,
        val frequenciesKHz: List<Long>,
    )

    val cpuClusters = listOf(
        CpuCluster(
            cpus = "0-3",
            microarch = "Cortex-A55 / arm,ananke",
            capacity = 260,
            minKHz = 400_000L,
            maxKHz = 2_210_000L,
            frequenciesKHz = listOf(
                177000L, 266000L, 400000L, 533000L, 650000L, 754000L, 858000L,
                962000L, 1066000L, 1170000L, 1274000L, 1378000L, 1482000L,
                1586000L, 1690000L, 1794000L, 1898000L, 2002000L, 2106000L, 2210000L,
            ),
        ),
        CpuCluster(
            cpus = "4-6",
            microarch = "Cortex-A78 / arm,hercules",
            capacity = 880,
            minKHz = 533_000L,
            maxKHz = 2_808_000L,
            frequenciesKHz = listOf(
                177000L, 266000L, 400000L, 533000L, 624000L, 728000L, 832000L,
                936000L, 1040000L, 1144000L, 1248000L, 1352000L, 1456000L,
                1560000L, 1664000L, 1768000L, 1872000L, 1976000L, 2080000L,
                2184000L, 2288000L, 2392000L, 2496000L, 2600000L, 2704000L, 2808000L,
            ),
        ),
        CpuCluster(
            cpus = "7",
            microarch = "Cortex-X1 / arm,hera",
            capacity = 997,
            minKHz = 533_000L,
            maxKHz = 2_912_000L,
            frequenciesKHz = listOf(
                177000L, 266000L, 400000L, 533000L, 624000L, 728000L, 832000L,
                936000L, 1040000L, 1144000L, 1248000L, 1352000L, 1456000L,
                1560000L, 1664000L, 1768000L, 1872000L, 1976000L, 2080000L,
                2184000L, 2288000L, 2392000L, 2496000L, 2600000L, 2704000L, 2808000L,
                2912000L,
            ),
        ),
    )

    val gpuFrequenciesHz = listOf(
        130_000_000L, 221_000_000L, 312_000_000L, 403_000_000L, 494_000_000L,
        585_000_000L, 676_000_000L, 767_000_000L, 858_000_000L,
    )

    /**
     * Firmware DT thermal policy starts passive control around 70 C for BIG/MID/G3D.
     * We start checkpointing slightly before that and block sustained image work first.
     */
    const val CHECKPOINT_TEMP_MILLI_C = 68_000
    const val IMAGE_STOP_TEMP_MILLI_C = 72_000
    const val HEAVY_STOP_TEMP_MILLI_C = 80_000

    fun matchesDevice(): Boolean {
        val model = Build.MODEL.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        return model.equals(MODEL, ignoreCase = true) &&
            (hardware.contains("exynos", ignoreCase = true) ||
                readText(File("/proc/cpuinfo")).contains("exynos", ignoreCase = true))
    }

    fun matchesExactFirmware(): Boolean =
        matchesDevice() && System.getProperty("os.version").orEmpty().contains(FIRMWARE, ignoreCase = true)

    fun cpuFrequenciesFor(affectedCpus: String): List<Long> =
        cpuClusters.firstOrNull { normalizeCpuSet(it.cpus) == normalizeCpuSet(affectedCpus) }
            ?.frequenciesKHz.orEmpty()

    fun maxRelevantTemperatureMilliC(): Int? {
        if (!matchesDevice()) return null
        val zones = File("/sys/class/thermal").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("thermal_zone") }
        var max: Int? = null
        zones.forEach { zone ->
            val type = readText(File(zone, "type")).uppercase()
            if (type.isBlank()) return@forEach
            val relevant = type.contains("BIG") || type.contains("MID") ||
                type.contains("LITTLE") || type.contains("G3D") || type.contains("GPU")
            if (!relevant) return@forEach
            val raw = readText(File(zone, "temp")).toIntOrNull() ?: return@forEach
            val milliC = if (raw in -200..250) raw * 1000 else raw
            if (milliC in -20_000..150_000) max = maxOf(max ?: milliC, milliC)
        }
        return max
    }

    fun summary(): String = buildString {
        append(MODEL)
        append(" • Exynos 2100 • 4×A55 + 3×A78 + 1×X1")
        append(" • Mali 130–858 MHz")
        append(" • zRAM 3 GiB")
        if (matchesExactFirmware()) append(" • $FIRMWARE exato")
        else if (matchesDevice()) append(" • perfil compatível")
    }

    private fun normalizeCpuSet(value: String): String = value.replace(" ", "").trim()

    private fun readText(file: File): String = runCatching {
        if (file.isFile && file.canRead()) file.readText().trim() else ""
    }.getOrDefault("")
}
''', encoding='utf-8')


# -----------------------------------------------------------------------------
# S21 runtime profiler: enrich diagnostics and use firmware frequency tables as a
# fallback only when the running kernel does not expose scaling_available_frequencies.
# -----------------------------------------------------------------------------
kernel_path = Path('offlineai/src/main/java/com/alysson/offlineai/S21KernelProfile.kt')
kernel = kernel_path.read_text(encoding='utf-8')
kernel = replace_once(
    kernel,
    '''            append(cpuPolicies.size)
            append(" política(s) CPU")
            gpu?.let { append(" • GPU ${it.name}") }
''',
    '''            append(cpuPolicies.size)
            append(" política(s) CPU")
            gpu?.let { append(" • GPU ${it.name}") }
            if (S21FirmwareProfile.matchesDevice()) append(" • perfil HZA6 carregado")
''',
    'kernel diagnostic summary',
)
kernel_path.write_text(kernel, encoding='utf-8')


# -----------------------------------------------------------------------------
# Performance defaults and worker policy for the exact heterogeneous 4+3+1 CPU.
# Existing user preferences are preserved; these defaults only affect fresh settings.
# -----------------------------------------------------------------------------
perf_path = Path('offlineai/src/main/java/com/alysson/offlineai/PerformanceSettings.kt')
perf = perf_path.read_text(encoding='utf-8')
perf = replace_once(
    perf,
    '''    fun imageThreads(): Int = when (limits().cpuPercent) {
        in MIN_CPU..39 -> 1
        in 40..74 -> 2
        else -> 3
    }
''',
    '''    fun imageThreads(): Int = when (limits().cpuPercent) {
        in MIN_CPU..39 -> 1
        in 40..59 -> 2
        in 60..79 -> 3
        else -> if (S21FirmwareProfile.matchesDevice()) 4 else 3
    }
''',
    'image worker count',
)
perf = replace_once(
    perf,
    '''    fun tokenPacingDelayMs(): Long = when (limits().cpuPercent) {
        in MIN_CPU..39 -> 70L
        in 40..54 -> 35L
        in 55..69 -> 15L
        else -> 0L
    }
''',
    '''    fun tokenPacingDelayMs(): Long = when (limits().cpuPercent) {
        in MIN_CPU..39 -> 70L
        in 40..54 -> 30L
        in 55..69 -> if (S21FirmwareProfile.matchesDevice()) 8L else 15L
        else -> 0L
    }
''',
    'token pacing',
)
perf = perf.replace(
    'private const val DEFAULT_CPU = 65',
    'private const val DEFAULT_CPU = 70',
    1,
)
perf = perf.replace(
    'private const val DEFAULT_GPU = 50',
    'private const val DEFAULT_GPU = 35',
    1,
)
perf = perf.replace(
    'private const val DEFAULT_RAM = 55',
    'private const val DEFAULT_RAM = 60',
    1,
)
perf = perf.replace(
    'CPU é o padrão seguro no S21 Exynos. Vulkan fica disponível para teste manual e volta para CPU se indisponível.',
    'CPU é o padrão seguro no SM-G991B/HZA6. Vulkan na Mali-G78 continua experimental e só é usado por escolha manual.',
    1,
)
perf_path.write_text(perf, encoding='utf-8')


# -----------------------------------------------------------------------------
# Thermal guard: use the firmware's actual DT thermal policy as an additional,
# read-only signal. Android PowerManager remains authoritative/fallback.
# -----------------------------------------------------------------------------
guard_path = Path('offlineai/src/main/java/com/alysson/offlineai/ResourceGuard.kt')
guard = guard_path.read_text(encoding='utf-8')
guard = replace_once(
    guard,
    '''        val thermal = runCatching { powerManager.currentThermalStatus }
            .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val limits = performance.limits()
''',
    '''        val thermal = runCatching { powerManager.currentThermalStatus }
            .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val firmwareTempMilliC = runCatching { S21FirmwareProfile.maxRelevantTemperatureMilliC() }.getOrNull()
        val limits = performance.limits()
''',
    'firmware thermal probe',
)
guard = replace_once(
    guard,
    '''        val checkpointSuggested = processBytes > 0L && (
            processBytes >= (residentTarget * 0.82).toLong() ||
                memory.availMem <= reserve + 512L * MIB
            )
''',
    '''        val checkpointSuggested = (processBytes > 0L && (
            processBytes >= (residentTarget * 0.82).toLong() ||
                memory.availMem <= reserve + 512L * MIB
            )) || (firmwareTempMilliC != null && firmwareTempMilliC >= S21FirmwareProfile.CHECKPOINT_TEMP_MILLI_C)
''',
    'thermal checkpoint',
)
guard = replace_once(
    guard,
    '''            memory.availMem <= reserve ->
                "Pouca RAM disponível: o app preserva uma reserva para evitar travamento do sistema."
            thermal >= PowerManager.THERMAL_STATUS_SEVERE ->
                "Temperatura elevada: a tarefa foi bloqueada até o aparelho esfriar."
''',
    '''            memory.availMem <= reserve ->
                "Pouca RAM disponível: o app preserva uma reserva para evitar travamento do sistema."
            kind == TaskKind.IMAGE && firmwareTempMilliC != null && firmwareTempMilliC >= S21FirmwareProfile.IMAGE_STOP_TEMP_MILLI_C ->
                "GPU/CPU do S21 atingiu ${firmwareTempMilliC / 1000} °C. A geração de imagem foi interrompida antes do throttling forte."
            kind != TaskKind.BUILDER && firmwareTempMilliC != null && firmwareTempMilliC >= S21FirmwareProfile.HEAVY_STOP_TEMP_MILLI_C ->
                "SoC do S21 atingiu ${firmwareTempMilliC / 1000} °C. A tarefa pesada foi interrompida para resfriamento."
            thermal >= PowerManager.THERMAL_STATUS_SEVERE ->
                "Temperatura elevada: a tarefa foi bloqueada até o aparelho esfriar."
''',
    'firmware thermal stops',
)
guard_path.write_text(guard, encoding='utf-8')


# -----------------------------------------------------------------------------
# Optional root controller: if sysfs omits frequency lists, use the exact stock DT
# tables extracted from this firmware so every write still lands on a stock OPP.
# -----------------------------------------------------------------------------
root_path = Path('offlineai/src/main/java/com/alysson/offlineai/RootPerformanceController.kt')
root = root_path.read_text(encoding='utf-8')
root = replace_once(
    root,
    '''                available = policy.availableKHz,
            )
''',
    '''                available = policy.availableKHz.ifEmpty { S21FirmwareProfile.cpuFrequenciesFor(policy.affectedCpus) },
            )
''',
    'cpu stock OPP fallback',
)
root = replace_once(
    root,
    '''                    available = gpu.availableHz,
                )
''',
    '''                    available = gpu.availableHz.ifEmpty { S21FirmwareProfile.gpuFrequenciesHz },
                )
''',
    'gpu stock OPP fallback',
)
root_path.write_text(root, encoding='utf-8')


# -----------------------------------------------------------------------------
# Human-readable build fingerprint bundled in the Core for diagnostics.
# -----------------------------------------------------------------------------
asset_path = Path('offlineai/src/main/assets/device_profile_sm_g991b_hza6.json')
asset_path.parent.mkdir(parents=True, exist_ok=True)
asset_path.write_text('''{
  "device": "Samsung Galaxy S21 5G SM-G991B",
  "firmware_kernel_build": "G991BXXSJHZA6",
  "kernel": "5.4.242-30958140-abG991BXXSJHZA6",
  "soc": "Exynos 2100",
  "cpu": {
    "little": {"cpus": "0-3", "core": "Cortex-A55", "max_mhz": 2210},
    "mid": {"cpus": "4-6", "core": "Cortex-A78", "max_mhz": 2808},
    "prime": {"cpus": "7", "core": "Cortex-X1", "max_mhz": 2912}
  },
  "gpu": {"driver": "mali_kbase", "dvfs_mhz": [130,221,312,403,494,585,676,767,858]},
  "zram_bytes": 3221225472,
  "filesystem_data": "f2fs",
  "thermal": {"cpu_big_mid_passive_c": 70, "gpu_passive_c": 70},
  "llm_policy": {"decode_threads": 3, "batch_threads": 4, "context": 4096, "batch": 256},
  "gpu_policy": "Tiny-SD CPU default; Vulkan manual/experimental"
}
''', encoding='utf-8')


# -----------------------------------------------------------------------------
# Identity. Keep the v7.1 unminified recovery lineage while field-testing this exact profile.
# -----------------------------------------------------------------------------
gradle_path = Path('offlineai/build.gradle')
gradle = gradle_path.read_text(encoding='utf-8')
gradle, count = re.subn(r'versionCode\s+\d+', 'versionCode 22', gradle, count=1)
if count != 1:
    raise SystemExit('v7.2 could not update versionCode')
gradle, count = re.subn(r"versionName\s+'[^']+'", "versionName '7.2.0-sm-g991b-hza6'", gradle, count=1)
if count != 1:
    raise SystemExit('v7.2 could not update versionName')
gradle_path.write_text(gradle, encoding='utf-8')

manifest_path = Path('offlineai/src/main/AndroidManifest.xml')
manifest = manifest_path.read_text(encoding='utf-8')
manifest = re.sub(r'android:label="[^"]+"', 'android:label="Unilaw AI • G991B"', manifest, count=1)
manifest_path.write_text(manifest, encoding='utf-8')

print('Workspace v7.2 SM-G991B HZA6 exact-device optimization applied')
