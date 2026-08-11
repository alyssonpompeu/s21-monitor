package com.alysson.offlineai

import android.app.ActivityManager
import android.content.Context
import java.io.File

class ResourceMonitor(context: Context) {

    data class Sample(
        val cpuPercent: Int?,
        val gpuPercent: Int?,
        val ramPercent: Int
    )

    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private var previousCpu: CpuTimes? = null

    fun sample(): Sample {
        val current = readCpuTimes()
        val cpu = if (previousCpu != null && current != null) {
            val prev = previousCpu!!
            val totalDelta = current.total - prev.total
            val idleDelta = current.idle - prev.idle
            if (totalDelta > 0) (((totalDelta - idleDelta) * 100.0) / totalDelta).toInt().coerceIn(0, 100) else null
        } else null
        previousCpu = current

        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val ram = if (memory.totalMem > 0L) {
            (((memory.totalMem - memory.availMem) * 100.0) / memory.totalMem).toInt().coerceIn(0, 100)
        } else 0

        return Sample(cpu, readGpuPercent(), ram)
    }

    private fun readCpuTimes(): CpuTimes? = runCatching {
        val line = File("/proc/stat").useLines { lines -> lines.firstOrNull() } ?: return null
        val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return null
        val idle = values[3] + values.getOrElse(4) { 0L }
        CpuTimes(values.sum(), idle)
    }.getOrNull()

    private fun readGpuPercent(): Int? {
        val directCandidates = listOf(
            "/sys/class/misc/mali0/device/utilization",
            "/sys/class/misc/mali0/device/gpu_busy",
            "/sys/class/misc/mali0/device/load"
        )
        directCandidates.forEach { path ->
            parsePercent(File(path))?.let { return it }
        }

        val devfreq = File("/sys/class/devfreq")
        val nodes = runCatching { devfreq.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
        nodes.filter { node ->
            val key = node.name.lowercase()
            key.contains("gpu") || key.contains("mali") || runCatching {
                node.resolve("name").takeIf(File::canRead)?.readText()?.lowercase()?.contains("mali") == true
            }.getOrDefault(false)
        }.forEach { node ->
            listOf("load", "utilization", "gpu_busy").forEach { metric ->
                parsePercent(node.resolve(metric))?.let { return it }
            }
        }
        return null
    }

    private fun parsePercent(file: File): Int? = runCatching {
        if (!file.canRead()) return null
        val text = file.readText().trim()
        val values = Regex("\\d+").findAll(text).mapNotNull { it.value.toLongOrNull() }.toList()
        if (values.isEmpty()) return null

        if (values.size >= 2 && values[1] > 0L && values[0] > 100L) {
            return ((values[0] * 100.0) / values[1]).toInt().coerceIn(0, 100)
        }
        values.first().toInt().takeIf { it in 0..100 }
    }.getOrNull()

    private data class CpuTimes(val total: Long, val idle: Long)
}
