package com.alysson.offlineai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * Local AI builder studio. Only one language model is loaded at a time. MainActivity unloads its
 * chat model before entering this screen; this activity prefers the optional 1.5B Coder pack and
 * falls back to the already-installed Qwen general model.
 */
class BuilderStudioActivity : Activity() {

    private enum class Target(val label: String) {
        ANDROID("Android APK"),
        WINDOWS_X64("Windows .exe x64"),
        WINDOWS_ARM64("Windows .exe ARM64"),
        SOURCE("Código-fonte .zip"),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var packs: PluginPackManager
    private lateinit var artifactBuilder: ArtifactBuilder
    private lateinit var guard: ResourceGuard
    private lateinit var prefs: AppPreferences

    private lateinit var targetSpinner: Spinner
    private lateinit var slotSpinner: Spinner
    private lateinit var nameField: EditText
    private lateinit var requestField: EditText
    private lateinit var sourceField: EditText
    private lateinit var status: TextView
    private lateinit var generateButton: TextView
    private lateinit var buildButton: TextView
    private lateinit var previewButton: TextView

    private lateinit var engine: InferenceEngine
    private var modelLoaded = false
    private var busy = false
    private var pendingExport: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        packs = PluginPackManager(applicationContext)
        artifactBuilder = ArtifactBuilder(applicationContext, packs)
        guard = ResourceGuard(applicationContext)
        prefs = AppPreferences(applicationContext)

        if (!packs.isInstalled(PluginPackManager.BUILDER_PACK_ID)) {
            AlertDialog.Builder(this)
                .setTitle("Builder Core não instalado")
                .setMessage("Importe primeiro o pacote Developer Builder Core em Plugins.")
                .setPositiveButton("Voltar") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(action("‹", "Voltar") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(TextView(this).apply {
            text = "Builder Studio local"
            textSize = 20f
            setTextColor(Color.rgb(32, 33, 36))
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(header)

        status = TextView(this).apply {
            text = modelStatusText()
            textSize = 12f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        root.addView(status)

        val selector = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        targetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@BuilderStudioActivity, android.R.layout.simple_spinner_dropdown_item, Target.entries.map { it.label })
        }
        selector.addView(targetSpinner, LinearLayout.LayoutParams(0, dp(52), 0.67f).apply { marginEnd = dp(8) })
        slotSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@BuilderStudioActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Slot APK 1", "Slot APK 2", "Slot APK 3", "Slot APK 4"))
        }
        selector.addView(slotSpinner, LinearLayout.LayoutParams(0, dp(52), 0.33f))
        root.addView(selector)

        nameField = EditText(this).apply {
            hint = "Nome do app/projeto"
            isSingleLine = true
            setText("meu-app")
        }
        root.addView(nameField, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))

        requestField = EditText(this).apply {
            hint = "Descreva o aplicativo ou código que deseja criar…"
            minLines = 3
            maxLines = 7
            gravity = Gravity.TOP
        }
        root.addView(requestField, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        generateButton = pill("Gerar com IA") { generate() }
        previewButton = pill("Prévia") { preview() }
        buildButton = pill("Criar arquivo") { buildArtifact() }
        buttons.addView(generateButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        buttons.addView(previewButton, LinearLayout.LayoutParams(0, dp(48), 0.72f).apply { marginEnd = dp(6) })
        buttons.addView(buildButton, LinearLayout.LayoutParams(0, dp(48), 0.95f))
        root.addView(buttons)

        val scroll = ScrollView(this)
        sourceField = EditText(this).apply {
            hint = "O resultado gerado aparecerá aqui e pode ser ajustado antes de criar o arquivo."
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            minLines = 16
            setHorizontallyScrolling(false)
        }
        scroll.addView(sourceField, ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun generate() {
        if (busy) return
        val request = requestField.text.toString().trim()
        if (request.isBlank()) {
            status.text = "Descreva primeiro o que deseja criar."
            return
        }
        val admission = guard.state(ResourceGuard.TaskKind.CHAT)
        if (!admission.safe) {
            status.text = admission.reason ?: "Recursos insuficientes agora."
            return
        }
        busy = true
        updateButtons()
        sourceField.setText("")
        status.text = "Preparando modelo especializado…"

        scope.launch {
            try {
                prepareEngine()
                val target = Target.entries[targetSpinner.selectedItemPosition]
                val settings = prefs.load()
                val prompt = builderPrompt(target, request, nameField.text.toString(), settings.qualityProfile)
                val budget = when (settings.qualityProfile) {
                    AppPreferences.QualityProfile.ADVANCED -> 1500
                    AppPreferences.QualityProfile.INTERMEDIATE -> 1050
                    AppPreferences.QualityProfile.FAST -> 650
                }
                val generated = StringBuilder()
                status.text = "Gerando localmente • ${modelStatusText()}"
                engine.sendUserPrompt(prompt, budget).collect { token ->
                    val live = guard.state(ResourceGuard.TaskKind.CHAT)
                    if (!live.safe) throw IllegalStateException(live.reason ?: "Geração interrompida para proteger o aparelho.")
                    generated.append(token)
                    sourceField.append(token)
                }
                val normalized = normalizeGenerated(target, generated.toString())
                sourceField.setText(normalized)
                status.text = "Pronto para revisar, visualizar e criar o arquivo."
            } catch (t: Throwable) {
                status.text = "Falha: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = false
                updateButtons()
            }
        }
    }

    private suspend fun prepareEngine() {
        if (modelLoaded) return
        val model = findModel() ?: error("Abra a tela principal uma vez para instalar o modelo local, ou importe o Coder Pack.")
        val admission = guard.state(ResourceGuard.TaskKind.CHAT)
        require(admission.safe) { admission.reason ?: "RAM/temperatura insuficientes." }
        engine = AiChat.getInferenceEngine(applicationContext)
        val initialized = engine.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error }
        if (initialized is InferenceEngine.State.Error) throw initialized.exception
        status.text = "Carregando ${if (packs.coderModel() != null) "Coder 1.5B" else "Qwen local"}…"
        engine.loadModel(model.absolutePath)
        engine.setSystemPrompt(SYSTEM_PROMPT)
        modelLoaded = true
    }

    private fun findModel(): File? {
        packs.coderModel()?.let { return it }
        return File(filesDir, "models").listFiles()
            ?.filter { it.isFile && it.extension.equals("gguf", true) && it.length() > 500L * 1024 * 1024 }
            ?.maxByOrNull { it.length() }
    }

    private fun builderPrompt(target: Target, request: String, appName: String, quality: AppPreferences.QualityProfile): String {
        val qualityInstruction = when (quality) {
            AppPreferences.QualityProfile.ADVANCED -> "Faça uma revisão extra de arquitetura e minimize complexidade, memória, dependências e tamanho."
            AppPreferences.QualityProfile.INTERMEDIATE -> "Priorize equilíbrio entre clareza, recursos e tamanho."
            AppPreferences.QualityProfile.FAST -> "Crie uma solução simples, curta e funcional."
        }
        return when (target) {
            Target.ANDROID -> """
                Crie a interface e lógica de um aplicativo Android solicitado abaixo. Você está alimentando um shell WebView local já compilado; portanto devolva APENAS um documento HTML completo, começando por <!doctype html> e terminando em </html>.
                Regras obrigatórias: tudo em um único HTML; CSS e JavaScript inline; zero CDN, zero URL http/https, zero fetch/XHR/WebSocket, zero iframe, nenhuma biblioteca externa; interface responsiva para celular; use localStorage quando precisar persistência; acessibilidade básica; sem imagens externas; prefira CSS/JS enxutos; não use explicações nem markdown.
                $qualityInstruction
                Nome: ${appName.take(60)}
                Pedido: ${request.take(2500)}
            """.trimIndent()
            Target.WINDOWS_X64, Target.WINDOWS_ARM64 -> """
                Converta o pedido em uma interface Windows nativa usando SOMENTE o DSL abaixo. Responda exatamente entre BEGIN_IASPEC e END_IASPEC, sem explicações.
                Linhas permitidas:
                APP|Título
                THEME|light ou dark
                TEXT|texto
                INPUT|id|placeholder
                RESULT|id|texto inicial
                CHECK|id|rótulo
                SPACE|pixels
                BUTTON|rótulo|COPY|origem|destino
                BUTTON|rótulo|SUM|id1,id2,...|destino
                BUTTON|rótulo|CLEAR|id1,id2,...
                BUTTON|rótulo|TOAST|mensagem
                BUTTON|rótulo|SAVE|chave|origem
                BUTTON|rótulo|LOAD|chave|destino
                IDs: apenas letras/números/_ e no máximo 24 caracteres. Máximo 36 componentes. Não invente outros comandos.
                $qualityInstruction
                Nome: ${appName.take(60)}
                Pedido: ${request.take(2500)}
            """.trimIndent()
            Target.SOURCE -> """
                Gere um pequeno projeto de código otimizado para o pedido. Entregue no máximo 8 arquivos. Não use markdown. Cada arquivo deve seguir exatamente:
                BEGIN_FILE caminho/arquivo.ext
                conteúdo integral
                END_FILE
                Não inclua binários nem dependências vendorizadas. Prefira biblioteca padrão e arquitetura simples. Inclua testes quando couber. $qualityInstruction
                Projeto: ${appName.take(60)}
                Pedido: ${request.take(2500)}
            """.trimIndent()
        }
    }

    private fun normalizeGenerated(target: Target, raw: String): String = when (target) {
        Target.ANDROID -> extractHtml(raw)
        Target.WINDOWS_X64, Target.WINDOWS_ARM64 -> extractSpec(raw)
        Target.SOURCE -> raw.trim().removePrefix("```").removeSuffix("```").trim()
    }

    private fun extractHtml(raw: String): String {
        var value = raw.trim().removePrefix("```html").removePrefix("```").removeSuffix("```").trim()
        val candidates = listOf(value.indexOf("<!doctype", ignoreCase = true), value.indexOf("<html", ignoreCase = true)).filter { it >= 0 }
        if (candidates.isNotEmpty()) value = value.substring(candidates.min())
        val end = value.lastIndexOf("</html>", ignoreCase = true)
        if (end >= 0) value = value.substring(0, end + 7)
        require(value.contains("<html", ignoreCase = true)) { "Saída HTML incompleta; tente novamente." }
        return value
    }

    private fun extractSpec(raw: String): String {
        val begin = raw.indexOf("BEGIN_IASPEC")
        val end = raw.indexOf("END_IASPEC")
        require(begin >= 0 && end > begin) { "A IA não devolveu o IASPEC esperado; tente novamente." }
        return "BEGIN_IASPEC\n" + raw.substring(begin + "BEGIN_IASPEC".length, end).trim() + "\nEND_IASPEC"
    }

    private fun preview() {
        if (busy) return
        val source = sourceField.text.toString().trim()
        if (source.isBlank()) return
        when (Target.entries[targetSpinner.selectedItemPosition]) {
            Target.ANDROID -> previewHtml(source)
            Target.WINDOWS_X64, Target.WINDOWS_ARM64 -> previewWindows(source)
            Target.SOURCE -> AlertDialog.Builder(this).setTitle("Código gerado").setMessage("Revise os arquivos no editor. Ao tocar em Criar arquivo, eles serão exportados como ZIP.").setPositiveButton("OK", null).show()
        }
    }

    private fun previewHtml(html: String) {
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.blockNetworkLoads = true
            loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
        }
        AlertDialog.Builder(this).setTitle("Prévia Android").setView(web).setPositiveButton("Fechar", null).show()
    }

    private fun previewWindows(raw: String) {
        val content = raw.substringAfter("BEGIN_IASPEC", raw).substringBefore("END_IASPEC").trim()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
        var count = 0
        content.lines().forEach { line ->
            if (count >= 24) return@forEach
            val parts = line.split('|')
            when (parts.firstOrNull()?.uppercase()) {
                "APP" -> box.addView(TextView(this).apply { text = parts.getOrNull(1) ?: "Aplicativo"; textSize = 22f })
                "TEXT" -> box.addView(TextView(this).apply { text = parts.drop(1).joinToString("|"); textSize = 16f; setPadding(0, dp(7), 0, dp(7)) })
                "INPUT" -> box.addView(EditText(this).apply { hint = parts.getOrNull(2).orEmpty(); isSingleLine = true })
                "RESULT" -> box.addView(TextView(this).apply { text = parts.getOrNull(2).orEmpty(); textSize = 16f; setPadding(0, dp(7), 0, dp(7)) })
                "CHECK" -> box.addView(android.widget.CheckBox(this).apply { text = parts.getOrNull(2).orEmpty() })
                "BUTTON" -> box.addView(TextView(this).apply { text = parts.getOrNull(1) ?: "Botão"; gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), dp(12)); setBackgroundColor(Color.rgb(235, 238, 242)) })
                "SPACE" -> box.addView(View(this), LinearLayout.LayoutParams(1, dp(parts.getOrNull(1)?.toIntOrNull()?.coerceIn(4, 80) ?: 12)))
            }
            count++
        }
        val scroll = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("Prévia aproximada Windows").setView(scroll).setPositiveButton("Fechar", null).show()
    }

    private fun buildArtifact() {
        if (busy) return
        val source = sourceField.text.toString().trim()
        if (source.isBlank()) {
            status.text = "Gere ou cole o conteúdo antes de criar o arquivo."
            return
        }
        busy = true
        updateButtons()
        val target = Target.entries[targetSpinner.selectedItemPosition]
        val appName = nameField.text.toString().ifBlank { "meu-app" }
        scope.launch {
            try {
                val artifact = when (target) {
                    Target.ANDROID -> artifactBuilder.buildAndroidApk(source, slotSpinner.selectedItemPosition + 1, appName) { p -> runOnUiThread { status.text = p } }
                    Target.WINDOWS_X64 -> artifactBuilder.buildWindowsExe(source, ArtifactBuilder.WindowsArch.X64, appName) { p -> runOnUiThread { status.text = p } }
                    Target.WINDOWS_ARM64 -> artifactBuilder.buildWindowsExe(source, ArtifactBuilder.WindowsArch.ARM64, appName) { p -> runOnUiThread { status.text = p } }
                    Target.SOURCE -> artifactBuilder.buildSourceZip(source, appName) { p -> runOnUiThread { status.text = p } }
                }
                pendingExport = artifact
                status.text = "Artefato criado: ${artifact.name} • ${formatSize(artifact.length())}"
                exportArtifact(artifact)
            } catch (t: Throwable) {
                status.text = "Falha ao criar artefato: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = false
                updateButtons()
            }
        }
    }

    private fun exportArtifact(file: File) {
        val mime = when (file.extension.lowercase()) {
            "apk" -> "application/vnd.android.package-archive"
            "exe" -> "application/octet-stream"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, file.name)
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    @Deprecated("Deprecated API retained for minSdk-compatible Storage Access Framework handling")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val file = pendingExport ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { it.copyTo(output, 1024 * 1024) } }
                        ?: error("Não foi possível abrir o destino.")
                }
                status.text = "Salvo: ${file.name}"
            } catch (t: Throwable) {
                status.text = "Falha ao salvar: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    private fun modelStatusText(): String = if (packs.coderModel() != null) {
        "Coder Pack Qwen2.5 1.5B disponível • CPU ARM64 • proteção ativa"
    } else {
        "Coder Pack não instalado • usando Qwen geral quando necessário"
    }

    private fun updateButtons() {
        generateButton.isEnabled = !busy
        previewButton.isEnabled = !busy
        buildButton.isEnabled = !busy
        val alpha = if (busy) 0.45f else 1f
        generateButton.alpha = alpha
        previewButton.alpha = alpha
        buildButton.alpha = alpha
    }

    private fun action(label: String, description: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 28f; gravity = Gravity.CENTER; contentDescription = description; setOnClickListener { onClick() }
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(26, 115, 232))
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { onClick() }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024 -> String.format("%.1f MiB", bytes / (1024.0 * 1024.0))
        else -> "${bytes / 1024} KiB"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::engine.isInitialized) runCatching { engine.destroy() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_EXPORT = 7201
        private val SYSTEM_PROMPT = """
            Você é o módulo local Builder Studio. Gere somente artefatos técnicos solicitados no formato exigido pela mensagem do usuário. Nunca alegue ser GPT, Codex, o3 ou serviço de nuvem. Não use internet, APIs externas, CDNs ou dependências remotas. Priorize código pequeno, previsível e eficiente para hardware móvel; evite alocações, animações e bibliotecas desnecessárias. Quando a saída tiver um protocolo estrito, obedeça exatamente ao protocolo.
        """.trimIndent()
    }
}
