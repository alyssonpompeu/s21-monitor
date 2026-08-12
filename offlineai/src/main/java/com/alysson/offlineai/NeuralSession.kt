package com.alysson.offlineai

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Process-scoped owner for the text inference engine.
 *
 * llama.android requires the system prompt to be set immediately after model loading. Therefore
 * the resident session owns one immutable system prompt for the lifetime of the loaded model.
 * Builder/Coder specialization is carried in the user prompt instead of attempting to change the
 * system prompt after inference has started.
 */
object NeuralSession {
    private val mutex = Mutex()

    @Volatile private var ownerPid: Int = -1
    @Volatile private var engine: InferenceEngine? = null
    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedAtElapsedMs: Long = 0L
    @Volatile private var residentSystemPrompt: String? = null

    suspend fun acquire(
        context: Context,
        requestedModel: File,
        systemPrompt: String,
        progress: (String) -> Unit = {},
    ): InferenceEngine = mutex.withLock {
        val app = context.applicationContext
        val pid = Process.myPid()
        if (ownerPid != pid) {
            ownerPid = pid
            engine = null
            loadedModelPath = null
            loadedAtElapsedMs = 0L
            residentSystemPrompt = null
        }

        var current = engine
        if (current == null) {
            progress("Inicializando motor neural local…")
            current = AiChat.getInferenceEngine(app)
            val initialized = current.state.first {
                it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
            }
            if (initialized is InferenceEngine.State.Error) throw initialized.exception
            engine = current
        }

        if (loadedModelPath == null) {
            progress("Carregando rede neural uma única vez nesta sessão…")
            current.loadModel(requestedModel.absolutePath)
            // Required by llama.android: this call must happen RIGHT AFTER loadModel().
            current.setSystemPrompt(systemPrompt)
            residentSystemPrompt = systemPrompt
            loadedModelPath = requestedModel.absolutePath
            loadedAtElapsedMs = SystemClock.elapsedRealtime()
            writeMarker(app)
        } else if (loadedModelPath != requestedModel.absolutePath) {
            progress("Reutilizando Qwen residente; especialização será aplicada no pedido…")
            writeMarker(app)
        } else {
            progress("Rede neural já está pronta na RAM…")
            writeMarker(app)
        }

        current
    }

    /**
     * Kept for source compatibility. Changing the prompt after the model has started is forbidden by
     * this runtime, so callers must put task-specific instructions in the user prompt instead.
     */
    suspend fun applySystemPrompt(systemPrompt: String) {
        if (!isLoaded()) return
        if (residentSystemPrompt == null) residentSystemPrompt = systemPrompt
    }

    fun isLoaded(): Boolean = engine != null && loadedModelPath != null && ownerPid == Process.myPid()

    fun loadedModelFile(): File? = loadedModelPath?.let(::File)?.takeIf { it.isFile }

    fun sessionDescription(): String = if (isLoaded()) {
        val ageSec = ((SystemClock.elapsedRealtime() - loadedAtElapsedMs).coerceAtLeast(0L) / 1000L)
        "Qwen residente • sessão ${ageSec}s • PID ${Process.myPid()}"
    } else {
        "rede neural ainda não carregada"
    }

    fun clearDiagnosticMarkerIfFinishing(context: Context) {
        runCatching { markerFile(context.applicationContext).delete() }
    }

    private fun writeMarker(context: Context) {
        val model = loadedModelPath ?: return
        runCatching {
            markerFile(context).writeText(
                buildString {
                    appendLine("version=5.2.1")
                    appendLine("pid=${Process.myPid()}")
                    appendLine("loaded_elapsed_ms=$loadedAtElapsedMs")
                    appendLine("model=$model")
                    appendLine("system_prompt=immutable_after_load")
                    appendLine("note=diagnostic marker only; neural state remains in RAM")
                }
            )
        }
    }

    private fun markerFile(context: Context): File =
        File(context.cacheDir, "neural-session-v5-2.tmp")
}
