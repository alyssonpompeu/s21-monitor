#!/usr/bin/env python3
from pathlib import Path
import re

# Unilaw v8: polished full workspace shell + automatic offline capability routing.
# Heavy neural weights remain external .iapack payloads so the installable APK does not become a
# 3+ GiB monolith (Qwen + Coder + Tiny-SD alone exceed a practical sideload size). Lightweight
# capabilities and their UI/routers are built into the APK and activate automatically.


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'v8 patch point missing: {label}')
    return text.replace(old, new, 1)

src = Path('offlineai/src/main/java/com/alysson/offlineai')

# -----------------------------------------------------------------------------
# Built-in capability registry: these appear as installed even without .iapack.
# -----------------------------------------------------------------------------
(src / 'BuiltInPluginRegistry.kt').write_text(r'''package com.alysson.offlineai

/**
 * First-party capabilities compiled into the Unilaw Core.
 *
 * Built-in means the orchestration/UI code is always available. Model-backed capabilities may
 * still depend on a signed payload pack (Qwen/Coder/Tiny-SD) so daily updates stay small and the
 * package installer never has to parse a multi-gigabyte monolithic APK.
 */
object BuiltInPluginRegistry {
    enum class Kind { TOOL, DOCUMENT, MEDIA, DEVELOPER, HARDWARE, MODEL }

    data class Plugin(
        val id: String,
        val name: String,
        val description: String,
        val kind: Kind,
        val automatic: Boolean = true,
        val requiresPackId: String? = null,
    )

    val all: List<Plugin> = listOf(
        Plugin("tools.exact", "Ferramentas exatas", "Cálculo, porcentagens, conversões, hashes, Base64 e operações determinísticas.", Kind.TOOL),
        Plugin("search.local", "Pesquisa local inteligente", "Roteia perguntas para projeto, Biblioteca Neural e memória lexical antes do Qwen.", Kind.DOCUMENT),
        Plugin("document.ocr", "OCR offline", "Extrai texto de imagens e PDFs escaneados usando o reconhecedor latino empacotado.", Kind.DOCUMENT),
        Plugin("files.universal", "Leitor universal", "Indexa PDF, TXT, Markdown, código, JSON, ZIP/APK e binários como fonte sem executá-los.", Kind.DOCUMENT),
        Plugin("database.sqlite", "SQLite Inspector", "Inspeciona SQLite em modo leitura e produz esquema/consultas seguras para análise.", Kind.DEVELOPER),
        Plugin("security.apk", "APK Inspector", "Lê pacote, SDK, permissões, ABI, ZIP, hashes e metadados de APK sem executá-lo.", Kind.DEVELOPER),
        Plugin("developer.binary", "Binary / Hex Inspector", "Metadados, strings, assinatura mágica e amostra hexadecimal de arquivos binários.", Kind.DEVELOPER),
        Plugin("developer.logcat", "Logcat Analyzer", "Importa logs; com root pode capturar logcat sob solicitação explícita.", Kind.DEVELOPER),
        Plugin("backup.projects", "Backup de projetos", "Exporta/restaura projetos e estado persistente pela Storage Access Framework.", Kind.TOOL, automatic = false),
        Plugin("image.tools", "Ferramentas de imagem", "Redimensionamento, conversão e preparação de imagens localmente.", Kind.MEDIA),
        Plugin("audio.tts.system", "Voz offline", "Usa uma voz TTS instalada no Android quando ela estiver disponível offline.", Kind.MEDIA),
        Plugin("device.s21", "S21 / Exynos 2100", "Telemetria e limites root seguros adaptados ao SM-G991B e ao kernel reconhecido.", Kind.HARDWARE),
        Plugin("model.qwen", "Qwen General", "Modelo principal de conversa e Work Offline.", Kind.MODEL, requiresPackId = PluginPackManager.GENERAL_PACK_ID),
        Plugin("model.coder", "Qwen Coder", "Programação especializada e geração de código.", Kind.MODEL, requiresPackId = PluginPackManager.CODER_PACK_ID),
        Plugin("model.tinysd", "Tiny-SD", "Geração local de imagens.", Kind.MODEL, requiresPackId = "image.tinysd.q4k"),
    )

    fun status(packManager: PluginPackManager, imageManager: ImageGenerationManager): List<Pair<Plugin, Boolean>> =
        all.map { plugin ->
            val available = when (plugin.id) {
                "model.tinysd" -> imageManager.hasModel() || packManager.isInstalled("image.tinysd.q4k")
                else -> plugin.requiresPackId?.let(packManager::isInstalled) ?: true
            }
            plugin to available
        }
}
''', encoding='utf-8')

# -----------------------------------------------------------------------------
# Deterministic tool engine: intercepts exact math/unit/hash requests before spending LLM tokens.
# -----------------------------------------------------------------------------
(src / 'ExactToolEngine.kt').write_text(r'''package com.alysson.offlineai

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlin.math.round

class ExactToolEngine {
    data class Result(val handled: Boolean, val text: String = "", val tool: String = "")

    fun tryHandle(raw: String): Result {
        val q = raw.trim()
        if (q.isBlank()) return Result(false)

        percent(q)?.let { return Result(true, format(it), "calculadora.percentual") }
        conversion(q)?.let { return Result(true, it, "conversor.unidades") }
        hash(q)?.let { return Result(true, it, "sha256") }
        base64(q)?.let { return Result(true, it, "base64") }
        arithmetic(q)?.let { return Result(true, format(it), "calculadora") }
        return Result(false)
    }

    private fun percent(q: String): Double? {
        val r = Regex("(?i)([-+]?\\d+(?:[.,]\\d+)?)\\s*%\\s*(?:de|do|da)\\s*([-+]?\\d+(?:[.,]\\d+)?)")
        val m = r.find(q) ?: return null
        return number(m.groupValues[1]) * number(m.groupValues[2]) / 100.0
    }

    private fun arithmetic(q: String): Double? {
        var s = q.lowercase(Locale.ROOT)
            .replace("quanto é", "").replace("calcule", "").replace("calcular", "")
            .replace(',', '.').trim().removeSuffix("?").trim()
        if (!s.matches(Regex("[0-9+\\-*/().% ]{1,120}"))) return null
        return runCatching { Parser(s).parse() }.getOrNull()?.takeIf { it.isFinite() }
    }

    private fun conversion(q: String): String? {
        val m = Regex("(?i)([-+]?\\d+(?:[.,]\\d+)?)\\s*(kb|mb|gb|kib|mib|gib|km|m|cm|mm|kg|g|°?c|°?f)\\s*(?:em|para|->)\\s*(kb|mb|gb|kib|mib|gib|km|m|cm|mm|kg|g|°?c|°?f)").find(q) ?: return null
        val v = number(m.groupValues[1])
        val from = m.groupValues[2].lowercase().replace("°", "")
        val to = m.groupValues[3].lowercase().replace("°", "")
        val result = when {
            from == "c" && to == "f" -> v * 9.0 / 5.0 + 32.0
            from == "f" && to == "c" -> (v - 32.0) * 5.0 / 9.0
            from in decimalBytes && to in decimalBytes -> v * decimalBytes.getValue(from) / decimalBytes.getValue(to)
            from in binaryBytes && to in binaryBytes -> v * binaryBytes.getValue(from) / binaryBytes.getValue(to)
            from in length && to in length -> v * length.getValue(from) / length.getValue(to)
            from in mass && to in mass -> v * mass.getValue(from) / mass.getValue(to)
            else -> return null
        }
        return "${format(v)} ${m.groupValues[2]} = ${format(result)} ${m.groupValues[3]}"
    }

    private fun hash(q: String): String? {
        val m = Regex("(?is)^(?:sha-?256|hash sha-?256)(?:\\s+de|:)?\\s+(.+)$").find(q) ?: return null
        val bytes = MessageDigest.getInstance("SHA-256").digest(m.groupValues[1].toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun base64(q: String): String? {
        Regex("(?is)^(?:base64|codifique em base64)(?:\\s+de|:)?\\s+(.+)$").find(q)?.let {
            return Base64.getEncoder().encodeToString(it.groupValues[1].toByteArray())
        }
        Regex("(?is)^(?:decodifique base64|base64 decode)(?:\\s*:)?\\s+([A-Za-z0-9+/=\\r\\n]+)$").find(q)?.let {
            return runCatching { String(Base64.getMimeDecoder().decode(it.groupValues[1])) }.getOrNull()
        }
        return null
    }

    private fun number(s: String) = s.replace(',', '.').toDouble()
    private fun format(v: Double): String {
        val rounded = round(v * 1_000_000.0) / 1_000_000.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    private class Parser(private val source: String) {
        private var i = 0
        fun parse(): Double { val v = expr(); skip(); require(i == source.length); return v }
        private fun expr(): Double { var v = term(); while (true) { skip(); v = when { eat('+') -> v + term(); eat('-') -> v - term(); else -> return v } } }
        private fun term(): Double { var v = factor(); while (true) { skip(); v = when { eat('*') -> v * factor(); eat('/') -> v / factor(); else -> return v } } }
        private fun factor(): Double { skip(); if (eat('+')) return factor(); if (eat('-')) return -factor(); if (eat('(')) { val v=expr(); require(eat(')')); return v }; val start=i; while (i<source.length && (source[i].isDigit() || source[i]=='.')) i++; require(i>start); var v=source.substring(start,i).toDouble(); skip(); if (eat('%')) v/=100.0; return v }
        private fun eat(c: Char): Boolean { skip(); if (i<source.length && source[i]==c) { i++; return true }; return false }
        private fun skip() { while (i<source.length && source[i].isWhitespace()) i++ }
    }

    companion object {
        private val decimalBytes = mapOf("kb" to 1e3, "mb" to 1e6, "gb" to 1e9)
        private val binaryBytes = mapOf("kib" to 1024.0, "mib" to 1048576.0, "gib" to 1073741824.0)
        private val length = mapOf("mm" to .001, "cm" to .01, "m" to 1.0, "km" to 1000.0)
        private val mass = mapOf("g" to .001, "kg" to 1.0)
    }
}
''', encoding='utf-8')

# -----------------------------------------------------------------------------
# Smart capability router: one question box, automatic plugin choice.
# -----------------------------------------------------------------------------
(src / 'SmartCapabilityRouter.kt').write_text(r'''package com.alysson.offlineai

import java.util.Locale

class SmartCapabilityRouter(
    private val exactTools: ExactToolEngine = ExactToolEngine(),
) {
    enum class Route { EXACT_TOOL, CHAT, WORK_OFFLINE, IMAGE, CODER, BUILDER, APK_ANALYSIS, LOG_ANALYSIS }
    data class Decision(val route: Route, val reason: String, val direct: ExactToolEngine.Result? = null)

    fun decide(question: String, workMode: Boolean = false): Decision {
        val direct = exactTools.tryHandle(question)
        if (direct.handled) return Decision(Route.EXACT_TOOL, direct.tool, direct)

        val q = question.lowercase(Locale.ROOT)
        if (workMode) return Decision(Route.WORK_OFFLINE, "Work Offline: tarefa multi-etapas")
        if (Regex("\\b(crie|gere|desenhe|imagem|foto|ilustração|render)\\b").containsMatchIn(q))
            return Decision(Route.IMAGE, "pedido visual")
        if (Regex("\\b(apk|androidmanifest|assinatura|zipalign|apksigner|não foi instalado|nao foi instalado)\\b").containsMatchIn(q))
            return Decision(Route.APK_ANALYSIS, "diagnóstico Android/APK")
        if (Regex("\\b(logcat|stacktrace|fatal exception|anr|crash|segfault)\\b").containsMatchIn(q))
            return Decision(Route.LOG_ANALYSIS, "diagnóstico de execução")
        if (Regex("\\b(código|codigo|programa|função|funcao|classe|kotlin|java|python|javascript|c\\+\\+|sql)\\b").containsMatchIn(q))
            return Decision(Route.CODER, "programação")
        if (Regex("\\b(criar app|crie um app|gerar apk|compilar apk|builder|executável|executavel|\.exe)\\b").containsMatchIn(q))
            return Decision(Route.BUILDER, "construção de artefato")
        return Decision(Route.CHAT, "Qwen + Biblioteca Neural")
    }
}
''', encoding='utf-8')

# -----------------------------------------------------------------------------
# Offline Work prompt planner. The model still generates the content, but the orchestrator gives
# it an explicit multi-stage contract and live stages in the same visual workspace.
# -----------------------------------------------------------------------------
(src / 'OfflineWorkOrchestrator.kt').write_text(r'''package com.alysson.offlineai

class OfflineWorkOrchestrator {
    data class Stage(val index: Int, val total: Int, val title: String)
    val stages = listOf(
        Stage(1, 4, "Entendendo o objetivo"),
        Stage(2, 4, "Consultando projeto e Biblioteca Neural"),
        Stage(3, 4, "Produzindo e verificando a entrega"),
        Stage(4, 4, "Finalizando no projeto"),
    )

    fun wrap(task: String, localContext: String): String = buildString {
        appendLine("<modo_work_offline>")
        appendLine("Você está no Work Offline da Unilaw. Execute a tarefa como um trabalho completo, em múltiplas etapas, sem internet.")
        appendLine("Não diga que pesquisou a web. Use somente conhecimento do modelo e contexto local fornecido.")
        appendLine("Entregue primeiro o resultado final útil; inclua etapas/checklist apenas quando agregarem valor.")
        appendLine("Se faltar um dado essencial que não existe localmente, marque claramente a limitação em vez de inventar.")
        appendLine("</modo_work_offline>")
        if (localContext.isNotBlank()) {
            appendLine("<contexto_work_local>")
            appendLine(localContext.take(42_000))
            appendLine("</contexto_work_local>")
        }
        appendLine("<tarefa>$task</tarefa>")
    }
}
''', encoding='utf-8')

# -----------------------------------------------------------------------------
# Plugin manager: built-in catalogue card before optional model packs.
# -----------------------------------------------------------------------------
plugin_path = src / 'PluginManagerActivity.kt'
plugin = plugin_path.read_text(encoding='utf-8')

marker = '''        addWorkOnlineCard()\n        addNeuralLibraryCard()\n'''
if marker not in plugin:
    raise SystemExit('v8 plugin catalogue insertion point missing')
plugin = plugin.replace(marker, '''        addBuiltInSuiteCard()\n        addWorkOnlineCard()\n        addNeuralLibraryCard()\n''', 1)

methods = r'''    private fun addBuiltInSuiteCard() {
        val statuses = BuiltInPluginRegistry.status(packs, imageGenerator)
        val readyCount = statuses.count { it.second }
        addCard(
            title = "Unilaw Essential Suite",
            subtitle = "built-in • $readyCount/${statuses.size} recurso(s) prontos • roteamento automático",
            description = "Ferramentas exatas, OCR, leitura de arquivos, análise APK/binária/log, backup, imagem e perfil S21 aparecem em um único catálogo. Modelos grandes continuam em packs assinados para manter o APK instalável.",
            onMenu = { showBuiltInSuite() },
        )
    }

    private fun showBuiltInSuite() {
        val body = BuiltInPluginRegistry.status(packs, imageGenerator).joinToString("\n\n") { (p, available) ->
            "${if (available) "✓" else "○"} ${p.name}\n${p.description}${if (!p.automatic) "\nManual" else "\nAutomático"}${if (!available) "\nRequer modelo/pacote opcional" else ""}"
        }
        AlertDialog.Builder(this)
            .setTitle("Plugins essenciais")
            .setMessage(body)
            .setPositiveButton("OK", null)
            .show()
    }

'''
needle = '    private fun addWorkOnlineCard() {\n'
if needle not in plugin:
    raise SystemExit('v8 plugin methods insertion point missing')
plugin = plugin.replace(needle, methods + needle, 1)
plugin_path.write_text(plugin, encoding='utf-8')

# -----------------------------------------------------------------------------
# Main: replace mode list with Chat / Work Offline / Work Online / explicit specialist modes,
# then let AUTO decide exact tools and specialists for normal Chat.
# -----------------------------------------------------------------------------
main_path = src / 'MainActivity.kt'
main = main_path.read_text(encoding='utf-8')

# v5.3.1 renamed WORK_ONLINE label to Work Free; support either exact current variant.
main = re.sub(
    r'''private enum class InteractionMode\(val label: String\) \{\n\s*TEXT\("Texto • Qwen"\),\n\s*IMAGE\("Imagem • Tiny-SD"\),\n\s*CODER\("Programação • Coder"\),\n\s*BUILDER\("Builder • APK/EXE"\),\n\s*WORK_ONLINE\("Work • (?:Internet|Free)"\)\n\s*\}''',
    '''private enum class InteractionMode(val label: String) {\n        AUTO("Conversar • Auto"),\n        WORK_OFFLINE("Work • Offline"),\n        WORK_ONLINE("Work • Online"),\n        IMAGE("Imagem • Tiny-SD"),\n        CODER("Código • Coder"),\n        BUILDER("Builder • APK/EXE")\n    }''',
    main,
    count=1,
)
if 'AUTO("Conversar • Auto")' not in main:
    raise SystemExit('v8 could not replace interaction modes')

# Add orchestration fields next to persist store.
field_needle = '''    private lateinit var persistStore: UnilawPersistStore\n'''
if field_needle not in main:
    raise SystemExit('v8 main field anchor missing')
main = main.replace(field_needle, field_needle + '''    private lateinit var smartRouter: SmartCapabilityRouter\n    private lateinit var offlineWork: OfflineWorkOrchestrator\n''', 1)

init_needle = '''        appPreferences = AppPreferences(applicationContext)\n'''
main = replace_once(main, init_needle, init_needle + '''        smartRouter = SmartCapabilityRouter()\n        offlineWork = OfflineWorkOrchestrator()\n''', 'router init')

# More polished central terminology.
main = main.replace('input.hint = "Pergunte do seu jeito"', 'input.hint = "Pergunte ou descreva um trabalho"')
main = main.replace('"Pergunte do seu jeito" else "IA indisponível"', '"Pergunte ou descreva um trabalho" else "IA indisponível"')

# Dispatch auto/work.
old_when = '''        when (InteractionMode.entries[modeSpinner.selectedItemPosition]) {\n            InteractionMode.TEXT -> submitTextQuestion(prompt)\n            InteractionMode.IMAGE -> submitImagePrompt(prompt)\n            InteractionMode.CODER -> openBuilderForPrompt(prompt, sourceOnly = true)\n            InteractionMode.BUILDER -> openBuilderForPrompt(prompt, sourceOnly = false)\n            InteractionMode.WORK_ONLINE -> openWorkOnline(prompt)\n        }\n'''
new_when = '''        when (InteractionMode.entries[modeSpinner.selectedItemPosition]) {\n            InteractionMode.AUTO -> submitAuto(prompt)\n            InteractionMode.WORK_OFFLINE -> submitOfflineWork(prompt)\n            InteractionMode.WORK_ONLINE -> openWorkOnline(prompt)\n            InteractionMode.IMAGE -> submitImagePrompt(prompt)\n            InteractionMode.CODER -> openBuilderForPrompt(prompt, sourceOnly = true)\n            InteractionMode.BUILDER -> openBuilderForPrompt(prompt, sourceOnly = false)\n        }\n'''
if old_when not in main:
    raise SystemExit('v8 submit dispatch anchor missing')
main = main.replace(old_when, new_when, 1)

auto_methods = r'''    private fun submitAuto(prompt: String) {
        val decision = smartRouter.decide(prompt, workMode = false)
        showLiveStatus("◇", "Auto • ${decision.reason}", "A Unilaw escolheu o recurso local apropriado para esta solicitação.")
        when (decision.route) {
            SmartCapabilityRouter.Route.EXACT_TOOL -> {
                activateResultMode()
                generatedImage.visibility = View.GONE
                answer.text = decision.direct?.text.orEmpty()
                libraryStore.recordSearch(activeProjectId, prompt, "TOOL:${decision.direct?.tool.orEmpty()}")
                persistStore.appendEvent(activeProjectId, libraryStore.projectName(activeProjectId), "tool_result", answer.text.toString())
            }
            SmartCapabilityRouter.Route.IMAGE -> submitImagePrompt(prompt)
            SmartCapabilityRouter.Route.CODER -> openBuilderForPrompt(prompt, sourceOnly = true)
            SmartCapabilityRouter.Route.BUILDER -> openBuilderForPrompt(prompt, sourceOnly = false)
            SmartCapabilityRouter.Route.APK_ANALYSIS,
            SmartCapabilityRouter.Route.LOG_ANALYSIS,
            SmartCapabilityRouter.Route.CHAT -> submitTextQuestion(prompt)
            SmartCapabilityRouter.Route.WORK_OFFLINE -> submitOfflineWork(prompt)
        }
    }

    private fun submitOfflineWork(task: String) {
        if (!ready || !engineModelLoaded) return
        val admission = resourceGuard.state(ResourceGuard.TaskKind.CHAT)
        if (!admission.safe) {
            showProtectedMessage(admission.reason)
            return
        }

        libraryStore.autoRenameProjectFromQuestion(activeProjectId, task)
        libraryStore.recordSearch(activeProjectId, task, "WORK_OFFLINE")
        input.isEnabled = false
        attachButton.isEnabled = false
        input.hint = task
        activateResultMode()
        generatedImage.visibility = View.GONE
        answer.text = ""
        val stages = offlineWork.stages
        showLiveStatus("1/4", stages[0].title, "Work Offline • sem Internet")

        generationJob = scope.launch {
            try {
                val settings = appPreferences.load()
                val projectName = libraryStore.projectName(activeProjectId)
                showLiveStatus("2/4", stages[1].title, "Projeto + Biblioteca Neural")
                val localContext = withContext(Dispatchers.IO) {
                    val project = libraryStore.retrieve(task, activeProjectId, 10)
                    val common = libraryStore.retrieveNeuralLibrary(task, 10, excludeProjectId = activeProjectId)
                    listOf(project, common).filter { it.isNotBlank() }.joinToString("\n\n")
                }
                val workTask = offlineWork.wrap(task, localContext)
                val prompt = dialogueBrain.buildPrompt(
                    projectId = activeProjectId,
                    projectName = projectName,
                    originalQuestion = workTask,
                    lexicalContext = lexicalMemory?.retrieve(task).orEmpty(),
                    libraryContext = localContext,
                    userName = settings.userName,
                    answerLength = settings.answerLength,
                    qualityProfile = settings.qualityProfile,
                    specificInstruction = settings.specificInstruction,
                )
                showLiveStatus("3/4", stages[2].title, "Qwen local • resposta em tempo real")
                val generated = StringBuilder()
                engine.sendUserPrompt(prompt, predictionBudget(settings)).flowOn(Dispatchers.Default).collect { token ->
                    val liveGuard = resourceGuard.state(ResourceGuard.TaskKind.CHAT)
                    if (!liveGuard.safe) throw IllegalStateException(liveGuard.reason ?: "Work interrompido para proteger o aparelho.")
                    generated.append(token)
                    streamBatcher.append(token)
                }
                streamBatcher.flushNow()
                if (generated.isNotBlank()) {
                    dialogueBrain.recordTurn(activeProjectId, task, generated.toString())
                    persistStore.appendEvent(activeProjectId, projectName, "work_offline_result", generated.toString())
                    syncProjectPersistence(activeProjectId)
                }
                showLiveStatus("4/4", stages[3].title, "Entrega salva localmente")
            } catch (t: Throwable) {
                if (::streamBatcher.isInitialized) streamBatcher.flushNow()
                if (answer.text.isNotEmpty()) answer.append("\n\n")
                answer.append("Work interrompido: ${t.message ?: t.javaClass.simpleName}")
                showLiveStatus("!", "Work Offline interrompido", t.message ?: t.javaClass.simpleName)
            } finally {
                input.isEnabled = ready
                attachButton.isEnabled = !importing
                input.hint = if (ready) "Pergunte ou descreva um trabalho" else "IA indisponível"
            }
        }
    }

'''
anchor = '    private fun openWorkOnline(prompt: String) {\n'
if anchor not in main:
    raise SystemExit('v8 auto/work method anchor missing')
main = main.replace(anchor, auto_methods + anchor, 1)

# Increase Work Offline budget modestly without changing normal chat budget; model context remains 4096.
# Do not touch native thread count here.

main_path.write_text(main, encoding='utf-8')

# -----------------------------------------------------------------------------
# Version identity. Keep v7.1 crash-safe debug policy and v7.2 hardware tuning.
# -----------------------------------------------------------------------------
gradle_path = Path('offlineai/build.gradle')
gradle = gradle_path.read_text(encoding='utf-8')
gradle, n = re.subn(r'versionCode\s+\d+', 'versionCode 23', gradle, count=1)
if n != 1: raise SystemExit('v8 versionCode patch failed')
gradle, n = re.subn(r"versionName\s+'[^']+'", "versionName '8.0.0-full-workspace'", gradle, count=1)
if n != 1: raise SystemExit('v8 versionName patch failed')
# Keep minification disabled for this physical-device validation build.
gradle_path.write_text(gradle, encoding='utf-8')

manifest_path = Path('offlineai/src/main/AndroidManifest.xml')
manifest = manifest_path.read_text(encoding='utf-8')
manifest = re.sub(r'android:label="[^"]+"', 'android:label="Unilaw AI Workspace"', manifest, count=1)
manifest_path.write_text(manifest, encoding='utf-8')

print('Unilaw v8 Full Workspace patch applied: built-in essentials, auto router, Offline/Online Work shell')
