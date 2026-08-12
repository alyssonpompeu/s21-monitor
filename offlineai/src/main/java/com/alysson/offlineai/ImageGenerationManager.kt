package com.alysson.offlineai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Runs stable-diffusion.cpp in a separate native process. CPU remains the safe default for
 * Galaxy S21 Exynos/Mali; a Vulkan engine may be bundled as an explicit experimental backend.
 */
class ImageGenerationManager(
    private val context: Context,
    private val resourceGuard: ResourceGuard,
) {

    data class ImportResult(val file: File, val sha256: String)

    private val performance = PerformanceSettings(context.applicationContext)
    private val modelDir = File(context.filesDir, "image-models").apply { mkdirs() }
    private val outputDir = File(context.filesDir, "generated-images").apply { mkdirs() }
    private val modelFile = File(modelDir, MODEL_FILE)

    fun hasModel(): Boolean = modelFile.isFile && modelFile.length() >= MIN_MODEL_BYTES

    fun hasVulkanEngine(): Boolean = File(context.applicationInfo.nativeLibraryDir, SD_VULKAN_LIB).isFile

    fun modelDescription(): String = if (hasModel()) {
        "Tiny-SD Q4_K local • 512×512"
    } else {
        "Modelo de imagens não instalado"
    }

    fun backendDescription(): String {
        val selected = performance.backend(PerformanceSettings.IMAGE_PLUGIN_ID)
        val effective = performance.effectiveBackend(PerformanceSettings.IMAGE_PLUGIN_ID, hasVulkanEngine())
        return if (selected == effective) effective.label else "${selected.label} → ${effective.label}"
    }

    fun deleteModel(): Boolean {
        val tmp = File(modelDir, "$MODEL_FILE.tmp")
        tmp.delete()
        return !modelFile.exists() || modelFile.delete()
    }

    suspend fun importModel(uri: Uri, progress: (String) -> Unit): ImportResult = withContext(Dispatchers.IO) {
        progress("Copiando pacote de imagens…")
        val tmp = File(modelDir, "$MODEL_FILE.tmp")
        if (tmp.exists()) tmp.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        var total = 0L

        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().buffered(1024 * 1024).use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    require(total <= MAX_MODEL_BYTES) { "Pacote de imagens maior que o limite aceito." }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    if (total % (64L * 1024 * 1024) < read) {
                        progress("Importando imagens • ${total / (1024 * 1024)} MiB")
                    }
                }
            }
        } ?: throw IllegalArgumentException("Não foi possível abrir o pacote de imagens.")

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == MODEL_SHA256) {
            tmp.delete()
            "Pacote de imagens incompatível. SHA-256 recebido: $actual"
        }
        if (modelFile.exists()) modelFile.delete()
        check(tmp.renameTo(modelFile)) { "Não foi possível instalar o pacote de imagens." }
        ImportResult(modelFile, actual)
    }

    suspend fun deleteProjectImages(projectId: Long) = withContext(Dispatchers.IO) {
        File(outputDir, "project_$projectId").deleteRecursively()
    }

    suspend fun generate(
        projectId: Long,
        prompt: String,
        quality: AppPreferences.QualityProfile,
        progress: (String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        require(hasModel()) { "Instale primeiro o pacote local de geração de imagens em Plugins locais." }
        val admission = resourceGuard.state(ResourceGuard.TaskKind.IMAGE)
        require(admission.safe) { admission.reason ?: "Recursos insuficientes para gerar imagem agora." }

        val backend = performance.effectiveBackend(PerformanceSettings.IMAGE_PLUGIN_ID, hasVulkanEngine())
        val executableName = if (backend == PerformanceSettings.Backend.VULKAN) SD_VULKAN_LIB else SD_CPU_LIB
        val executable = File(context.applicationInfo.nativeLibraryDir, executableName)
        require(executable.isFile) { "Motor ${backend.label} não foi incluído nesta compilação." }

        val projectDir = File(outputDir, "project_$projectId").apply { mkdirs() }
        val output = File(projectDir, "imagem_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.png")
        val steps = when (quality) {
            AppPreferences.QualityProfile.ADVANCED -> 24
            AppPreferences.QualityProfile.INTERMEDIATE -> 18
            AppPreferences.QualityProfile.FAST -> 12
        }
        val imageThreads = performance.imageThreads()
        val limits = performance.limits()

        val args = mutableListOf(
            executable.absolutePath,
            "-m", modelFile.absolutePath,
            "-W", "512",
            "-H", "512",
            "--cfg-scale", "7.0",
            "--steps", steps.toString(),
            "--sampling-method", "euler_a",
            "--diffusion-fa",
            "--vae-tiling",
            "-t", imageThreads.toString(),
            "-o", output.absolutePath,
            "-p", prompt,
        )

        progress("Gerando • ${backend.label} • $steps etapas • CPU alvo ${limits.cpuPercent}%")
        val processBuilder = ProcessBuilder(args)
            .directory(context.filesDir)
            .redirectErrorStream(true)
        processBuilder.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        val process = processBuilder.start()

        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) progress(line.take(180))
                    val guard = resourceGuard.state(ResourceGuard.TaskKind.IMAGE)
                    if (!guard.safe && process.isAlive) {
                        process.destroyForcibly()
                        throw IllegalStateException(guard.reason ?: "Geração interrompida para proteger o aparelho.")
                    }
                }
            }
            val exit = process.waitFor()
            check(exit == 0 && output.isFile && output.length() > 1024) {
                "O motor ${backend.label} terminou sem produzir uma imagem válida (código $exit)."
            }
            output
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    companion object {
        const val MODEL_FILE = "segmind_tiny-sd-q4_K.gguf"
        const val MODEL_SHA256 = "69fe70e0b72f3ea22830b12ddabeb55ee8fe55a28ccc0b763ace4cf39af346d6"
        private const val SD_CPU_LIB = "libsd-cli.so"
        private const val SD_VULKAN_LIB = "libsd-vulkan.so"
        private const val MIN_MODEL_BYTES = 600L * 1024 * 1024
        private const val MAX_MODEL_BYTES = 1024L * 1024 * 1024
    }
}
