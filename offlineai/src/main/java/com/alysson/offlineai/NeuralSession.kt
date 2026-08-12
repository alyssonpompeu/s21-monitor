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
 * The model is loaded once per Android process and is intentionally kept resident while the app
 * process is alive. Activities only borrow the already-loaded engine and change the system prompt.
 * A tiny marker in cacheDir records the live process/model for diagnostics; it is not a serialized
 * model and cannot restore RAM after Android kills the process.
 */
object NeuralSession {
    private val mutex = Mutex()

    @Volatile private var ownerPid: Int = -1
    @Volatile private var engine: InferenceEngine? = null
    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedAtElapsedMs: Long = 0L

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
            loadedModelPath = requestedModel.absolutePath
            loadedAtElapsedMs = SystemClock.elapsedRealtime()
            writeMarker(app)
        } else if (loadedModelPath != requestedModel.absolutePath) {
            // Do not evict the resident Qwen merely because another Activity prefers a Coder pack.
            // Explicit model switching can be added later, but navigation must not cause reload loops.
            progress("Reutilizando rede neural já residente na RAM…")
            writeMarker(app)
        } else {
            progress("Rede neural já está pronta na RAM…")
            writeMarker(app)
        }

        current.setSystemPrompt(systemPrompt)
        current
    }

    suspend fun applySystemPrompt(systemPrompt: String) {
        engine?.setSystemPrompt(systemPrompt)
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
                    appendLine("version=5.2")
                    appendLine("pid=${Process.myPid()}")
                    appendLine("loaded_elapsed_ms=$loadedAtElapsedMs")
                    appendLine("model=$model")
                    appendLine("note=diagnostic marker only; the neural state remains in RAM")
                }
            )
        }
    }

    private fun markerFile(context: Context): File =
        File(context.cacheDir, "neural-session-v5-2.tmp")
}
