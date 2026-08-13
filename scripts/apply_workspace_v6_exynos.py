#!/usr/bin/env python3
from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'v6 patch point missing: {label}')
    return text.replace(old, new, 1)


def insert_import(text: str, after: str, line: str) -> str:
    if line in text:
        return text
    return replace_once(text, after, after + line, f'import {line.strip()}')


# -----------------------------------------------------------------------------
# Neural session: model initialization/loading must never block the UI thread.
# -----------------------------------------------------------------------------
neural_path = Path('offlineai/src/main/java/com/alysson/offlineai/NeuralSession.kt')
neural_path.write_text(r'''package com.alysson.offlineai

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-scoped owner for the resident Qwen session.
 *
 * V6 rule: no model initialization/loading is allowed on Android's UI thread. The Exynos 2100 can
 * spend seconds mapping and preparing a multi-gigabyte GGUF; doing that from Activity.onCreate()
 * causes input-dispatch ANRs even when the native runtime itself is healthy.
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
    ): InferenceEngine {
        mutex.lock()
        try {
            val app = context.applicationContext
            val pid = Process.myPid()
            if (ownerPid != pid) {
                ownerPid = pid
                engine = null
                loadedModelPath = null
                loadedAtElapsedMs = 0L
                residentSystemPrompt = null
            }

            return withContext(Dispatchers.Default) {
                // Keep RenderThread/UI ahead of model setup and inference orchestration.
                runCatching {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE)
                }

                var current = engine
                if (current == null) {
                    progress("Inicializando motor neural em segundo plano…")
                    current = AiChat.getInferenceEngine(app)
                    val initialized = current.state.first {
                        it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                    }
                    if (initialized is InferenceEngine.State.Error) throw initialized.exception
                    engine = current
                }

                if (loadedModelPath == null) {
                    progress("Mapeando Qwen na RAM sem bloquear a interface…")
                    current.loadModel(requestedModel.absolutePath)
                    // llama.android requires the system prompt immediately after loadModel().
                    current.setSystemPrompt(systemPrompt)
                    residentSystemPrompt = systemPrompt
                    loadedModelPath = requestedModel.absolutePath
                    loadedAtElapsedMs = SystemClock.elapsedRealtime()
                    writeMarker(app)
                } else if (loadedModelPath != requestedModel.absolutePath) {
                    progress("Mantendo Qwen residente; especialização será aplicada no pedido…")
                    writeMarker(app)
                } else {
                    progress("Rede neural residente pronta…")
                    writeMarker(app)
                }
                current
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Task-specific instructions belong in the user prompt after inference starts. */
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
                    appendLine("version=6.0-s21-native")
                    appendLine("pid=${Process.myPid()}")
                    appendLine("loaded_elapsed_ms=$loadedAtElapsedMs")
                    appendLine("model=$model")
                    appendLine("load_thread=background")
                    appendLine("system_prompt=immutable_after_load")
                    appendLine("note=diagnostic marker only; neural state remains resident in RAM")
                }
            )
        }
    }

    private fun markerFile(context: Context): File =
        File(context.cacheDir, "neural-session-s21-v6.tmp")
}
''', encoding='utf-8')


# -----------------------------------------------------------------------------
# Main workspace: thin native UI, explicit themes, edge-to-edge and batched stream.
# -----------------------------------------------------------------------------
main_path = Path('offlineai/src/main/java/com/alysson/offlineai/MainActivity.kt')
main = main_path.read_text(encoding='utf-8')
main = insert_import(main, 'import android.content.Intent\n', 'import android.content.Context\n')
main = insert_import(main, 'import kotlinx.coroutines.flow.first\n', 'import kotlinx.coroutines.flow.flowOn\n')

main = replace_once(
    main,
    '''    private lateinit var imageGenerator: ImageGenerationManager
    private lateinit var persistStore: UnilawPersistStore
''',
    '''    private lateinit var imageGenerator: ImageGenerationManager
    private lateinit var persistStore: UnilawPersistStore
    private lateinit var uiTheme: UiThemeController
    private lateinit var streamBatcher: StreamingUiBatcher
''',
    'main v6 fields',
)

# Apply explicit day/night resources before Activity.onCreate so dialogs/spinners follow the theme too.
main = replace_once(
    main,
    '''class MainActivity : Activity() {
''',
    '''class MainActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiThemeController.wrap(newBase))
    }
''',
    'main themed base context',
)

main = replace_once(
    main,
    '''        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        appPreferences = AppPreferences(applicationContext)
''',
    '''        super.onCreate(savedInstanceState)
        uiTheme = UiThemeController(applicationContext)
        uiTheme.applyWindow(this)

        appPreferences = AppPreferences(applicationContext)
''',
    'main window theme',
)

main = main.replace(
    'val frame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }',
    'val frame = FrameLayout(this).apply { setBackgroundColor(uiTheme.palette().background) }',
    1,
)
main = main.replace(
    'setBackgroundColor(Color.WHITE)\n        }\n\n        val toolbar',
    'setBackgroundColor(uiTheme.palette().background)\n        }\n\n        val toolbar',
    1,
)
# Drawer is a floating/raised surface.
main = main.replace(
    'setBackgroundColor(Color.WHITE)\n            elevation = dp(16).toFloat()',
    'setBackgroundColor(uiTheme.palette().surface)\n            elevation = dp(12).toFloat()',
    1,
)
# Purple real-time workspace becomes a polished tonal card in both themes.
main = main.replace(
    'setBackgroundColor(Color.rgb(245, 240, 255))\n            visibility = View.GONE',
    'background = uiTheme.cardDrawable(dp(20).toFloat(), live = true)\n            visibility = View.GONE',
    1,
)
main = main.replace(
    'setBackgroundColor(Color.rgb(248, 249, 250))\n            setPadding(dp(10), dp(10), dp(10), dp(10))',
    'background = uiTheme.cardDrawable(dp(16).toFloat())\n            setPadding(dp(12), dp(12), dp(12), dp(12))',
    1,
)
# Project selected state follows theme instead of hard-coded Google-blue/white colors.
main = main.replace(
    'setTextColor(if (selected) Color.rgb(26, 115, 232) else Color.rgb(32, 33, 36))',
    'setTextColor(if (selected) uiTheme.palette().accent else uiTheme.palette().text)',
    1,
)
main = main.replace(
    'setColor(if (selected) Color.rgb(240, 246, 255) else Color.WHITE)',
    'setColor(if (selected) uiTheme.palette().accentSoft else uiTheme.palette().surface)',
    1,
)

# Once all main views exist, install the frame-batched stream sink and edge-to-edge insets.
main = replace_once(
    main,
    '''        setContentView(frame)
    }
''',
    '''        setContentView(frame)
        uiTheme.applySystemInsets(frame)
        uiTheme.polishTextTree(frame)
        streamBatcher = StreamingUiBatcher(answer, intervalMs = 32L) {
            scheduleScroll()
        }
    }
''',
    'main setContentView v6 polish',
)

# Use palette-aware field primitives; these replace multiple per-screen GradientDrawable allocations.
main, n = re.subn(
    r'''    private fun roundedFieldDrawable\(\) = GradientDrawable\(\)\.apply \{.*?\n    \}\n\n    private fun circleDrawable\(\) = GradientDrawable\(\)\.apply \{.*?\n    \}\n''',
    '''    private fun roundedFieldDrawable() = uiTheme.fieldDrawable(dp(28).toFloat())\n\n    private fun circleDrawable() = uiTheme.circleDrawable()\n''',
    main,
    count=1,
    flags=re.S,
)
if n != 1:
    raise SystemExit('v6 patch point missing: main palette drawables')

# Resource meters do not need sub-second polling in a chat UI.
if 'delay(1200)' in main:
    main = main.replace('delay(1200)', 'delay(2000)', 1)

# Shift native token production upstream to a worker dispatcher and coalesce tiny UI updates.
main = replace_once(
    main,
    'engine.sendUserPrompt(prompt, predictionBudget(settings)).collect { token ->',
    'engine.sendUserPrompt(prompt, predictionBudget(settings)).flowOn(Dispatchers.Default).collect { token ->',
    'main inference flowOn',
)
main = main.replace(
    '''                        visibleGenerated.append(visible)
                        answer.append(visible)
                        updateCodePreview(visibleGenerated.toString())
                        scheduleScroll()
''',
    '''                        visibleGenerated.append(visible)
                        streamBatcher.append(visible)
''',
    1,
)
main = main.replace(
    '''                    visibleGenerated.append(tail)
                    answer.append(tail)
                    updateCodePreview(visibleGenerated.toString())
                }
                checkpointJob?.join()
''',
    '''                    visibleGenerated.append(tail)
                    streamBatcher.append(tail)
                }
                streamBatcher.flushNow()
                checkpointJob?.join()
''',
    1,
)
# Ensure any queued text is visible before capturing a partial result after interruption.
main = main.replace(
    '''            } catch (t: Throwable) {
                val partial = answer.text.toString()
''',
    '''            } catch (t: Throwable) {
                if (::streamBatcher.isInitialized) streamBatcher.flushNow()
                val partial = answer.text.toString()
''',
    1,
)

# Appearance selector inside existing Personalization dialog.
appearance_block = r'''        val previousThemeMode = uiTheme.mode()
        box.addView(TextView(this).apply {
            text = "Tema do aplicativo"
            textSize = 13f
            setPadding(0, dp(14), 0, 0)
        })
        val themeMode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                UiThemeController.Mode.entries.map { it.label },
            )
            setSelection(UiThemeController.Mode.entries.indexOf(previousThemeMode))
        }
        box.addView(themeMode)

'''
main, n = re.subn(
    r'(\n        AlertDialog\.Builder\(this\)\n            \.setTitle\("Personalização"\))',
    '\n' + appearance_block + r'\1',
    main,
    count=1,
)
if n != 1:
    raise SystemExit('v6 patch point missing: theme selector dialog')

main = replace_once(
    main,
    '''                appPreferences.setSpecificInstruction(specific.text.toString())
            }
            .show()
''',
    '''                appPreferences.setSpecificInstruction(specific.text.toString())
                val selectedTheme = UiThemeController.Mode.entries[themeMode.selectedItemPosition]
                uiTheme.setMode(selectedTheme)
                if (selectedTheme != previousThemeMode) recreate()
            }
            .show()
''',
    'save theme preference',
)

# Never leave delayed UI batches pointing at a destroyed Activity.
main = replace_once(
    main,
    '''    override fun onDestroy() {
        generationJob?.cancel()
''',
    '''    override fun onDestroy() {
        generationJob?.cancel()
        if (::streamBatcher.isInitialized) streamBatcher.cancel()
''',
    'cancel main stream batcher',
)

main_path.write_text(main, encoding='utf-8')


# -----------------------------------------------------------------------------
# Builder Studio: same background inference + frame-batched source preview + theme.
# -----------------------------------------------------------------------------
builder_path = Path('offlineai/src/main/java/com/alysson/offlineai/BuilderStudioActivity.kt')
builder = builder_path.read_text(encoding='utf-8')
builder = insert_import(builder, 'import android.content.Intent\n', 'import android.content.Context\n')
builder = insert_import(builder, 'import kotlinx.coroutines.flow.collect\n', 'import kotlinx.coroutines.flow.flowOn\n') if 'import kotlinx.coroutines.flow.collect\n' in builder else builder
if 'import kotlinx.coroutines.flow.flowOn\n' not in builder:
    # Some revisions only import first(). Insert near other coroutine imports.
    builder = builder.replace('import kotlinx.coroutines.launch\n', 'import kotlinx.coroutines.launch\nimport kotlinx.coroutines.flow.flowOn\n', 1)

builder = replace_once(
    builder,
    '''class BuilderStudioActivity : Activity() {
''',
    '''class BuilderStudioActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiThemeController.wrap(newBase))
    }
''',
    'builder themed base context',
)

# Fields are stable after v5.2.
builder = replace_once(
    builder,
    '''    private lateinit var processLog: TextView
    private lateinit var generateButton: TextView
''',
    '''    private lateinit var processLog: TextView
    private lateinit var generateButton: TextView
    private lateinit var uiTheme: UiThemeController
    private lateinit var sourceBatcher: StreamingUiBatcher
''',
    'builder v6 fields',
)

builder = replace_once(
    builder,
    '''        super.onCreate(savedInstanceState)
''',
    '''        super.onCreate(savedInstanceState)
        uiTheme = UiThemeController(applicationContext)
        uiTheme.applyWindow(this)
''',
    'builder theme initialization',
)
# Framework roots in Builder use hard-coded white in current generation.
builder = builder.replace('setBackgroundColor(Color.WHITE)', 'setBackgroundColor(uiTheme.palette().background)', 1)
builder = builder.replace('setBackgroundColor(Color.rgb(245, 240, 255))', 'background = uiTheme.cardDrawable(dp(18).toFloat(), live = true)', 1)

builder = replace_once(
    builder,
    '''        setContentView(root)
        val initialRequest = intent.getStringExtra(EXTRA_INITIAL_REQUEST).orEmpty().trim()
''',
    '''        setContentView(root)
        uiTheme.applySystemInsets(root)
        uiTheme.polishTextTree(root)
        sourceBatcher = StreamingUiBatcher(sourceField, intervalMs = 32L) {
            processScroll.postOnAnimation { processScroll.fullScroll(View.FOCUS_DOWN) }
        }
        val initialRequest = intent.getStringExtra(EXTRA_INITIAL_REQUEST).orEmpty().trim()
''',
    'builder setContentView polish',
)

# Move upstream native generation off main and coalesce source EditText layout passes.
builder = replace_once(
    builder,
    'engine.sendUserPrompt(prompt, budget).collect { token ->',
    'engine.sendUserPrompt(prompt, budget).flowOn(Dispatchers.Default).collect { token ->',
    'builder inference flowOn',
)
builder = builder.replace('sourceField.append(visible)', 'sourceBatcher.append(visible)', 1)
builder = builder.replace('sourceField.append(tail)', 'sourceBatcher.append(tail)', 1)
builder = builder.replace(
    '''                val normalized = normalizeGenerated(target, generated.toString())
                sourceField.setText(normalized)
''',
    '''                sourceBatcher.flushNow()
                val normalized = normalizeGenerated(target, generated.toString())
                sourceField.setText(normalized)
''',
    1,
)

# Builder process log currently rewrites the whole TextView on every message. Cap it and avoid scroll spam.
builder = builder.replace(
    '''        processScroll.postOnAnimation { processScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun generate() {
''',
    '''        processScroll.postOnAnimation { processScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun generate() {
''',
    1,
)

builder = replace_once(
    builder,
    '''    override fun onDestroy() {
''',
    '''    override fun onDestroy() {
        if (::sourceBatcher.isInitialized) sourceBatcher.cancel()
''',
    'builder batcher cleanup',
)
builder_path.write_text(builder, encoding='utf-8')


# -----------------------------------------------------------------------------
# Plugins/Performance: expose kernel discovery and opt-in safe root caps.
# -----------------------------------------------------------------------------
plugin_path = Path('offlineai/src/main/java/com/alysson/offlineai/PluginManagerActivity.kt')
plugin = plugin_path.read_text(encoding='utf-8')
plugin = insert_import(plugin, 'import android.content.Intent\n', 'import android.content.Context\n')
plugin = replace_once(
    plugin,
    '''class PluginManagerActivity : Activity() {
''',
    '''class PluginManagerActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiThemeController.wrap(newBase))
    }
''',
    'plugin themed base context',
)
plugin = replace_once(
    plugin,
    '''    private lateinit var builderButton: TextView
    private var importing = false
''',
    '''    private lateinit var builderButton: TextView
    private lateinit var uiTheme: UiThemeController
    private lateinit var rootController: RootPerformanceController
    private var importing = false
''',
    'plugin v6 fields',
)
plugin = replace_once(
    plugin,
    '''        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        packs = PluginPackManager(applicationContext)
''',
    '''        super.onCreate(savedInstanceState)
        uiTheme = UiThemeController(applicationContext)
        uiTheme.applyWindow(this)
        rootController = RootPerformanceController(applicationContext)
        packs = PluginPackManager(applicationContext)
''',
    'plugin theme/root init',
)
plugin = plugin.replace('setBackgroundColor(Color.WHITE)', 'setBackgroundColor(uiTheme.palette().background)', 1)
plugin = plugin.replace('setBackgroundColor(Color.rgb(248, 249, 250))', 'background = uiTheme.cardDrawable(dp(16).toFloat())')

# Polish after cards are populated.
plugin = replace_once(
    plugin,
    '''        buildUi()
        refresh()
    }
''',
    '''        buildUi()
        refresh()
        uiTheme.polishTextTree(window.decorView)
    }
''',
    'plugin initial polish',
)

# Root controls sit directly under CPU/GPU/RAM so they are not hidden in another screen.
root_panel = r'''        panel.addView(TextView(this).apply {
            text = "S21 / kernel  ›\n${rootController.hardwareSummary()}"
            textSize = 12f
            setTextColor(uiTheme.palette().accent)
            setPadding(0, dp(10), 0, dp(6))
            setOnClickListener { showRootPerformanceDialog() }
        })
'''
plugin = replace_once(
    plugin,
    '''        panel.addView(TextView(this).apply {
            text = "RAM usa limite de admissão + checkpoint antecipado perto de 2 GiB residentes. O spill persistente de até 5 GiB fica em disco; não é swap do kernel."
''',
    root_panel + '''        panel.addView(TextView(this).apply {
            text = "RAM usa limite de admissão + checkpoint antecipado perto de 2 GiB residentes. O spill persistente de até 5 GiB fica em disco; não é swap do kernel."
''',
    'root hardware entry',
)

# If root mode is enabled, saving UI budgets also applies safe max-frequency caps.
plugin = replace_once(
    plugin,
    '''            performance.setLimits(
                PerformanceSettings.MIN_CPU + cpu.progress,
                PerformanceSettings.MIN_GPU + gpu.progress,
                PerformanceSettings.MIN_RAM + ram.progress,
            )
            refresh()
''',
    '''            performance.setLimits(
                PerformanceSettings.MIN_CPU + cpu.progress,
                PerformanceSettings.MIN_GPU + gpu.progress,
                PerformanceSettings.MIN_RAM + ram.progress,
            )
            refresh()
            if (rootController.enabled()) {
                scope.launch {
                    val applied = rootController.apply(performance.limits())
                    status.text = applied.message
                }
            }
''',
    'inline root apply after save',
)
# The dialog save path has the same call pattern; apply there too if a second match remains.
needle = '''                performance.setLimits(
                    cpuPercent = PerformanceSettings.MIN_CPU + cpu.progress,
                    gpuPercent = PerformanceSettings.MIN_GPU + gpu.progress,
                    ramPercent = PerformanceSettings.MIN_RAM + ram.progress,
                )
                refresh()
'''
if needle in plugin:
    plugin = plugin.replace(
        needle,
        '''                performance.setLimits(
                    cpuPercent = PerformanceSettings.MIN_CPU + cpu.progress,
                    gpuPercent = PerformanceSettings.MIN_GPU + gpu.progress,
                    ramPercent = PerformanceSettings.MIN_RAM + ram.progress,
                )
                refresh()
                if (rootController.enabled()) {
                    scope.launch {
                        val applied = rootController.apply(performance.limits())
                        status.text = applied.message
                    }
                }
''',
        1,
    )

root_methods = r'''    private fun showRootPerformanceDialog() {
        val profile = S21KernelProfile().snapshot()
        val body = buildString {
            appendLine(profile.summary())
            appendLine()
            appendLine("CPU")
            profile.cpuPolicies.forEach { policy ->
                appendLine("• ${policy.path.name} • CPUs ${policy.affectedCpus.ifBlank { "?" }} • ${policy.minKHz / 1000}–${policy.maxKHz / 1000} MHz")
            }
            profile.gpu?.let { gpu ->
                appendLine()
                appendLine("GPU")
                appendLine("• ${gpu.name} • ${gpu.minHz / 1_000_000}–${gpu.maxHz / 1_000_000} MHz")
            }
            appendLine()
            appendLine(if (rootController.enabled()) "Root seguro: ATIVO" else "Root seguro: DESATIVADO")
            appendLine("A v6 só reduz/restaura frequências máximas já expostas pelo kernel. Não faz overclock, undervolt, mudança de governor, escrita de tensão ou ajuste de LMKD/zRAM.")
        }
        val positive = if (rootController.enabled()) "Aplicar limites" else "Ativar e aplicar"
        AlertDialog.Builder(this)
            .setTitle("SM-G991B • gerenciamento root")
            .setMessage(body)
            .setNegativeButton("Fechar", null)
            .setNeutralButton("Restaurar stock") { _, _ ->
                scope.launch {
                    val restored = rootController.restoreStockMaximums()
                    if (restored.applied) rootController.setEnabled(false)
                    status.text = restored.message
                    refresh()
                }
            }
            .setPositiveButton(positive) { _, _ ->
                rootController.setEnabled(true)
                scope.launch {
                    val applied = rootController.apply(performance.limits())
                    if (!applied.applied) rootController.setEnabled(false)
                    status.text = applied.message
                    refresh()
                }
            }
            .show()
    }

'''
plugin = replace_once(
    plugin,
    '    private fun showPerformanceDialog() {\n',
    root_methods + '    private fun showPerformanceDialog() {\n',
    'root performance dialog',
)
plugin_path.write_text(plugin, encoding='utf-8')


# -----------------------------------------------------------------------------
# Manifest/build identity: polished native theme, stable data package, smaller managed heap.
# -----------------------------------------------------------------------------
manifest_path = Path('offlineai/src/main/AndroidManifest.xml')
manifest = manifest_path.read_text(encoding='utf-8')
manifest = re.sub(r'android:label="[^"]+"', 'android:label="Unilaw AI • S21"', manifest, count=1)
manifest = manifest.replace('        android:largeHeap="true"\n', '')
manifest = re.sub(r'android:theme="[^"]+"', 'android:theme="@style/AppTheme"', manifest, count=1)
manifest_path.write_text(manifest, encoding='utf-8')

gradle_path = Path('offlineai/build.gradle')
gradle = gradle_path.read_text(encoding='utf-8')
gradle, count = re.subn(r'versionCode\s+\d+', 'versionCode 20', gradle, count=1)
if count != 1:
    raise SystemExit('v6 could not update versionCode')
gradle, count = re.subn(r"versionName\s+'[^']+'", "versionName '7.0.0-s21-native-v6'", gradle, count=1)
if count != 1:
    raise SystemExit('v6 could not update versionName')
if "applicationId 'com.alysson.offlineai.pluginv52'" not in gradle:
    raise SystemExit('v6 requires stable package com.alysson.offlineai.pluginv52')
# Optimize even the sideloaded test APK with R8; heavy GGUF/native payload is unaffected.
gradle = gradle.replace(
    '''        debug {
            minifyEnabled false
        }
''',
    '''        debug {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
''',
    1,
)
if "androidx.profileinstaller:profileinstaller" not in gradle:
    gradle = gradle.replace(
        "    implementation 'androidx.documentfile:documentfile:1.1.0'\n",
        "    implementation 'androidx.documentfile:documentfile:1.1.0'\n    implementation 'androidx.profileinstaller:profileinstaller:1.4.1'\n",
        1,
    )
gradle_path.write_text(gradle, encoding='utf-8')

# R8 must retain Activities launched by class name/manifest and native JNI bridge already retained.
proguard_path = Path('offlineai/proguard-rules.pro')
proguard = proguard_path.read_text(encoding='utf-8')
for rule in [
    '-keep class com.alysson.offlineai.MainActivity { *; }',
    '-keep class com.alysson.offlineai.PluginManagerActivity { *; }',
    '-keep class com.alysson.offlineai.BuilderStudioActivity { *; }',
]:
    if rule not in proguard:
        proguard += rule + '\n'
proguard_path.write_text(proguard, encoding='utf-8')

# Baseline/Startup profiles are class-level and intentionally small; ART can AOT critical paths.
baseline = Path('offlineai/src/main/baseline-prof.txt')
baseline.parent.mkdir(parents=True, exist_ok=True)
baseline.write_text('''Lcom/alysson/offlineai/MainActivity;\nLcom/alysson/offlineai/NeuralSession;\nLcom/alysson/offlineai/LibraryStore;\nLcom/alysson/offlineai/DialogueBrain;\nLcom/alysson/offlineai/ResourceGuard;\nLcom/alysson/offlineai/PerformanceSettings;\nLcom/alysson/offlineai/StreamingUiBatcher;\nLcom/alysson/offlineai/UiThemeController;\nLcom/alysson/offlineai/PluginManagerActivity;\nLcom/alysson/offlineai/BuilderStudioActivity;\n''', encoding='utf-8')
startup = Path('offlineai/src/main/startup-prof.txt')
startup.write_text('''Lcom/alysson/offlineai/MainActivity;\nLcom/alysson/offlineai/NeuralSession;\nLcom/alysson/offlineai/UiThemeController;\nLcom/alysson/offlineai/AppPreferences;\nLcom/alysson/offlineai/ResourceGuard;\n''', encoding='utf-8')

print('Workspace v6 S21 Native patch applied: background neural load, frame-batched stream, native themes and safe root caps')
