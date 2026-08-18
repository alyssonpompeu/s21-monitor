#!/usr/bin/env python3
from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'v8.1 patch point missing: {label}')
    return text.replace(old, new, 1)

src = Path('offlineai/src/main/java/com/alysson/offlineai')

# -----------------------------------------------------------------------------
# Declarative plugin bundle. These profiles never load arbitrary code: they only describe
# first-party capabilities already compiled into the Core and their S21 scheduling policy.
# -----------------------------------------------------------------------------
(src / 'CapabilityBundleManager.kt').write_text(r'''package com.alysson.offlineai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class CapabilityBundleManager(private val context: Context) {
    data class Profile(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val automatic: Boolean,
        val threads: Int,
        val maxConcurrent: Int,
        val thermalSoftC: Int,
        val thermalHardC: Int,
        val maxImageDimension: Int,
        val requiresPackId: String?,
    )

    data class InstallResult(val bundleName: String, val profiles: List<Profile>)

    private val root = File(context.filesDir, "capability-profiles").apply { mkdirs() }

    suspend fun installBundle(uri: Uri): InstallResult = withContext(Dispatchers.IO) {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0
        context.contentResolver.openInputStream(uri)?.buffered()?.use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    require(!entry.isDirectory) { "Pastas explícitas não são necessárias no bundle." }
                    require(name == "bundle.json" || name == "README.md" || (name.startsWith("profiles/") && name.endsWith(".json"))) {
                        "Entrada não permitida no bundle: $name"
                    }
                    require(!name.startsWith('/') && !name.split('/').any { it == ".." || it.isBlank() }) { "Caminho inseguro no bundle." }
                    val bytes = readLimited(zip, MAX_ENTRY_BYTES)
                    total += bytes.size
                    require(total <= MAX_BUNDLE_BYTES) { "Bundle de plugins grande demais." }
                    entries[name] = bytes
                    zip.closeEntry()
                }
            }
        } ?: throw IllegalArgumentException("Não foi possível abrir o ZIP de plugins.")

        val bundleBytes = entries["bundle.json"] ?: throw IllegalArgumentException("bundle.json ausente.")
        val bundle = JSONObject(bundleBytes.toString(Charsets.UTF_8))
        require(bundle.getInt("schema") == 1) { "Schema de bundle não suportado." }
        require(bundle.getString("type") == "unilaw-capability-bundle") { "Tipo de bundle inválido." }
        val name = bundle.getString("name").take(100)
        val declared = bundle.getJSONArray("profiles")
        require(declared.length() in 1..MAX_PROFILES) { "Quantidade de plugins inválida." }

        val parsed = ArrayList<Profile>(declared.length())
        val seen = mutableSetOf<String>()
        for (i in 0 until declared.length()) {
            val path = declared.getString(i)
            require(path.startsWith("profiles/") && path.endsWith(".json")) { "Perfil inválido: $path" }
            val bytes = entries[path] ?: throw IllegalArgumentException("Perfil declarado ausente: $path")
            val profile = parseProfile(bytes.toString(Charsets.UTF_8))
            require(seen.add(profile.id)) { "Plugin duplicado: ${profile.id}" }
            parsed += profile
        }

        val temp = File(root, ".installing").apply { deleteRecursively(); mkdirs() }
        try {
            parsed.forEach { profile ->
                File(temp, "${profile.id}.json").writeText(profileJson(profile), Charsets.UTF_8)
            }
            parsed.forEach { profile ->
                val source = File(temp, "${profile.id}.json")
                val target = File(root, "${profile.id}.json")
                if (target.exists()) target.delete()
                require(source.renameTo(target)) { "Não foi possível instalar ${profile.name}." }
            }
        } finally {
            temp.deleteRecursively()
        }
        InstallResult(name, parsed)
    }

    fun listProfiles(): List<Profile> = root.listFiles().orEmpty()
        .asSequence().filter { it.isFile && it.extension == "json" }
        .mapNotNull { runCatching { parseProfile(it.readText()) }.getOrNull() }
        .sortedBy { it.name.lowercase() }.toList()

    fun profile(id: String): Profile? = File(root, "$id.json").takeIf { it.isFile }
        ?.let { runCatching { parseProfile(it.readText()) }.getOrNull() }

    fun remove(id: String): Boolean = !File(root, "$id.json").exists() || File(root, "$id.json").delete()

    private fun parseProfile(text: String): Profile {
        val j = JSONObject(text)
        require(j.getInt("schema") == 1) { "Schema de plugin não suportado." }
        val id = j.getString("id")
        require(id in ALLOWED_IDS) { "Capacidade não permitida neste Core: $id" }
        val threads = j.optInt("threads", 1).coerceIn(1, 3)
        val concurrent = j.optInt("max_concurrent", 1).coerceIn(1, 2)
        val soft = j.optInt("thermal_soft_c", 66).coerceIn(55, 72)
        val hard = j.optInt("thermal_hard_c", 70).coerceIn(60, 75)
        require(hard >= soft) { "Limite térmico inválido em $id" }
        return Profile(
            id = id,
            name = j.getString("name").take(80),
            description = j.optString("description").take(300),
            category = j.optString("category", "tool").take(30),
            automatic = j.optBoolean("automatic", true),
            threads = threads,
            maxConcurrent = concurrent,
            thermalSoftC = soft,
            thermalHardC = hard,
            maxImageDimension = j.optInt("max_image_dimension", 0).coerceIn(0, 2400),
            requiresPackId = j.optString("requires_pack_id").takeIf { it.isNotBlank() }?.take(80),
        )
    }

    private fun profileJson(p: Profile): String = JSONObject().apply {
        put("schema", 1); put("id", p.id); put("name", p.name); put("description", p.description)
        put("category", p.category); put("automatic", p.automatic); put("threads", p.threads)
        put("max_concurrent", p.maxConcurrent); put("thermal_soft_c", p.thermalSoftC)
        put("thermal_hard_c", p.thermalHardC); put("max_image_dimension", p.maxImageDimension)
        p.requiresPackId?.let { put("requires_pack_id", it) }
    }.toString(2)

    private fun readLimited(zip: ZipInputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = zip.read(buffer)
            if (n <= 0) break
            total += n
            require(total <= limit) { "Entrada do bundle excede o limite." }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_BUNDLE_BYTES = 2 * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 64 * 1024
        private const val MAX_PROFILES = 32
        private val ALLOWED_IDS = setOf(
            "tools.exact", "search.local", "document.ocr", "vision.labels", "barcode.qr",
            "files.universal", "files.office", "files.epub", "structured.csvjson", "database.sqlite",
            "security.apk", "developer.binary", "developer.logcat", "backup.projects", "image.tools",
            "device.s21", "model.qwen", "model.coder", "model.tinysd"
        )
    }
}
''', encoding='utf-8')

(src / 'S21PluginScheduler.kt').write_text(r'''package com.alysson.offlineai

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Conservative scheduler for SM-G991B / Exynos 2100. It limits app-side concurrency; the kernel
 * remains authoritative for DVFS/thermal decisions. Root clock caps are handled separately. */
class S21PluginScheduler(context: Context) {
    enum class Workload { LIGHT, DOCUMENT, VISION, DEVELOPER, HEAVY_MODEL }
    data class Policy(
        val threads: Int,
        val maxConcurrent: Int,
        val thermalSoftC: Int,
        val thermalHardC: Int,
        val maxImageDimension: Int,
        val note: String,
    )

    private val bundles = CapabilityBundleManager(context.applicationContext)
    private val hardware = S21KernelProfile()

    fun policy(capabilityId: String, workload: Workload): Policy {
        val custom = bundles.profile(capabilityId)
        if (custom != null) return Policy(
            custom.threads, custom.maxConcurrent, custom.thermalSoftC, custom.thermalHardC,
            custom.maxImageDimension, "Perfil do bundle v8.1"
        )
        val exact = runCatching { hardware.snapshot().exactTarget }.getOrDefault(false)
        return when (workload) {
            Workload.LIGHT -> Policy(1, 2, 68, 72, 0, "tarefas determinísticas leves")
            Workload.DOCUMENT -> Policy(if (exact) 2 else 2, 1, 66, 70, 0, "I/O + parsing serializado")
            Workload.VISION -> Policy(if (exact) 2 else 1, 1, 64, 69, if (exact) 1800 else 1600, "OCR/visão com bitmap limitado")
            Workload.DEVELOPER -> Policy(2, 1, 66, 70, 0, "análise local sem execução")
            Workload.HEAVY_MODEL -> Policy(if (exact) 3 else 2, 1, 63, 69, 0, "um modelo pesado por vez")
        }
    }

    fun dispatcher(capabilityId: String, workload: Workload): CoroutineDispatcher {
        val p = policy(capabilityId, workload)
        return Dispatchers.IO.limitedParallelism(p.threads)
    }
}
''', encoding='utf-8')

# -----------------------------------------------------------------------------
# Registry: expose the newly separated capabilities in the professional catalogue.
# -----------------------------------------------------------------------------
registry = src / 'BuiltInPluginRegistry.kt'
r = registry.read_text(encoding='utf-8')
r = replace_once(
    r,
    '        Plugin("document.ocr", "OCR offline", "Extrai texto de imagens e PDFs escaneados usando o reconhecedor latino empacotado.", Kind.DOCUMENT),\n',
    '        Plugin("document.ocr", "OCR offline", "Extrai texto de imagens e PDFs escaneados usando o reconhecedor latino empacotado.", Kind.DOCUMENT),\n'
    '        Plugin("vision.labels", "Visão leve", "Rotulagem local de imagens com modelo empacotado, sem download em runtime.", Kind.MEDIA),\n'
    '        Plugin("barcode.qr", "QR / Código de barras", "Detecta QR, EAN, UPC e outros códigos em imagens anexadas, totalmente offline.", Kind.MEDIA),\n',
    'registry vision/barcode',
)
r = replace_once(
    r,
    '        Plugin("files.universal", "Leitor universal", "Indexa PDF, TXT, Markdown, código, JSON, ZIP/APK e binários como fonte sem executá-los.", Kind.DOCUMENT),\n',
    '        Plugin("files.universal", "Leitor universal", "Indexa PDF, TXT, Markdown, código, JSON, ZIP/APK e binários como fonte sem executá-los.", Kind.DOCUMENT),\n'
    '        Plugin("files.office", "Office local", "Extrai conteúdo pesquisável de DOCX, XLSX e PPTX sem abrir macros.", Kind.DOCUMENT),\n'
    '        Plugin("files.epub", "EPUB Reader", "Extrai capítulos e texto de livros EPUB para a Biblioteca Neural.", Kind.DOCUMENT),\n'
    '        Plugin("structured.csvjson", "CSV / JSON Analyzer", "Valida e resume dados estruturados localmente antes de enviar contexto ao Qwen.", Kind.DOCUMENT),\n',
    'registry office structured',
)
registry.write_text(r, encoding='utf-8')

# -----------------------------------------------------------------------------
# Attachment importer: add bundled offline barcode reading and structured CSV/JSON summaries.
# -----------------------------------------------------------------------------
imp = src / 'AttachmentImporter.kt'
s = imp.read_text(encoding='utf-8')
s = replace_once(
    s,
    'import com.google.mlkit.vision.common.InputImage\n',
    'import com.google.mlkit.vision.common.InputImage\nimport com.google.mlkit.vision.barcode.BarcodeScanning\nimport com.google.mlkit.vision.barcode.common.Barcode\nimport com.google.mlkit.vision.barcode.BarcodeScannerOptions\n',
    'barcode imports',
)
s = replace_once(
    s,
    '    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)\n',
    '    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)\n'
    '    private val barcodeScanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())\n',
    'barcode field',
)
# v8 docs patch will have imageLabeler immediately after recognizer; the replacement above leaves it intact.
s = replace_once(
    s,
    '            val labels = recognizeLabels(bitmap)\n            val sections = mutableListOf<LibraryStore.Section>()\n',
    '            val labels = recognizeLabels(bitmap)\n            val barcodes = recognizeBarcodes(bitmap)\n            val sections = mutableListOf<LibraryStore.Section>()\n',
    'barcode image call',
)
s = replace_once(
    s,
    '            if (labels.isNotBlank()) sections += LibraryStore.Section("imagem/visão", labels)\n',
    '            if (labels.isNotBlank()) sections += LibraryStore.Section("imagem/visão", labels)\n'
    '            if (barcodes.isNotBlank()) sections += LibraryStore.Section("imagem/códigos", barcodes)\n',
    'barcode section',
)
barcode_helper = r'''    private suspend fun recognizeBarcodes(bitmap: Bitmap): String {
        val values = barcodeScanner.process(InputImage.fromBitmap(bitmap, 0)).awaitValue()
            .take(24)
        if (values.isEmpty()) return ""
        return buildString {
            appendLine("Códigos detectados localmente:")
            values.forEach { code ->
                val value = code.rawValue.orEmpty().replace(Regex("\\s+"), " ").take(500)
                appendLine("- formato=${code.format} • tipo=${code.valueType} • $value")
            }
        }
    }

'''
s = replace_once(s, '    private fun hash(uri: Uri): String {\n', barcode_helper + '    private fun hash(uri: Uri): String {\n', 'barcode helper')

# Structured summary for plain JSON/CSV sources.
s = s.replace('isTextLike(name, mime) -> extractText(uri)', 'isTextLike(name, mime) -> extractText(uri, name)', 1)
s = s.replace('private fun extractText(uri: Uri): Extraction {', 'private fun extractText(uri: Uri, name: String): Extraction {', 1)
s = replace_once(
    s,
    '        require(text.isNotBlank()) { "O arquivo não contém texto reconhecível." }\n        return Extraction(listOf(LibraryStore.Section("texto", text)))\n',
    '        require(text.isNotBlank()) { "O arquivo não contém texto reconhecível." }\n'
    '        val sections = mutableListOf(LibraryStore.Section("texto", text))\n'
    '        structuredSummary(name, text)?.let { sections += LibraryStore.Section("dados/estrutura", it) }\n'
    '        return Extraction(sections)\n',
    'structured text sections',
)
structured_helper = r'''    private fun structuredSummary(name: String, text: String): String? {
        val lower = name.lowercase(Locale.ROOT)
        if (lower.endsWith(".json")) {
            return runCatching {
                val trimmed = text.trim()
                val type = if (trimmed.startsWith("[")) "array" else "objeto"
                val top = if (type == "array") org.json.JSONArray(trimmed).length() else org.json.JSONObject(trimmed).length()
                "JSON válido • tipo raiz: $type • itens/chaves no topo: $top • ${text.length} caracteres"
            }.getOrElse { "JSON inválido: ${it.message ?: it.javaClass.simpleName}" }
        }
        if (lower.endsWith(".csv")) {
            val lines = text.lineSequence().take(5001).toList()
            if (lines.isEmpty()) return null
            val separators = listOf(',', ';', '\t')
            val sep = separators.maxByOrNull { c -> lines.take(20).sumOf { row -> row.count { it == c } } } ?: ','
            val columns = lines.first().count { it == sep } + 1
            val sampledRows = (lines.size - 1).coerceAtLeast(0)
            val suffix = if (lines.size >= 5001) "+" else ""
            return "CSV • separador ${if (sep == '\t') "TAB" else sep} • $columns coluna(s) • $sampledRows$suffix linha(s) amostradas"
        }
        return null
    }

'''
s = replace_once(s, '    private fun isArchive(name: String, mime: String): Boolean {\n', structured_helper + '    private fun isArchive(name: String, mime: String): Boolean {\n', 'structured helper')
s = replace_once(
    s,
    '        recognizer.close()\n        imageLabeler.close()\n',
    '        recognizer.close()\n        imageLabeler.close()\n        barcodeScanner.close()\n',
    'close barcode scanner',
)
imp.write_text(s, encoding='utf-8')

# Add bundled barcode model; bundled path remains offline after install.
gradle = Path('offlineai/build.gradle')
g = gradle.read_text(encoding='utf-8')
if "com.google.mlkit:barcode-scanning:17.3.0" not in g:
    g = replace_once(
        g,
        "    implementation 'com.google.mlkit:image-labeling:17.0.9'\n",
        "    implementation 'com.google.mlkit:image-labeling:17.0.9'\n    implementation 'com.google.mlkit:barcode-scanning:17.3.0'\n",
        'barcode dependency',
    )
g, count = re.subn(r"versionCode\s+\d+", "versionCode 22", g, count=1)
if count != 1: raise SystemExit('v8.1 versionCode not found')
g, count = re.subn(r"versionName\s+'[^']+'", "versionName '8.1.0-plugin-bundle-s21'", g, count=1)
if count != 1: raise SystemExit('v8.1 versionName not found')
gradle.write_text(g, encoding='utf-8')

# -----------------------------------------------------------------------------
# Plugin manager UI: one ZIP import button + professional per-profile cards.
# -----------------------------------------------------------------------------
p = src / 'PluginManagerActivity.kt'
ui = p.read_text(encoding='utf-8')
ui = replace_once(
    ui,
    '    private lateinit var imageGenerator: ImageGenerationManager\n',
    '    private lateinit var imageGenerator: ImageGenerationManager\n    private lateinit var capabilityBundles: CapabilityBundleManager\n    private lateinit var pluginScheduler: S21PluginScheduler\n',
    'plugin manager bundle fields',
)
ui = replace_once(
    ui,
    '        imageGenerator = ImageGenerationManager(applicationContext, ResourceGuard(applicationContext))\n',
    '        imageGenerator = ImageGenerationManager(applicationContext, ResourceGuard(applicationContext))\n        capabilityBundles = CapabilityBundleManager(applicationContext)\n        pluginScheduler = S21PluginScheduler(applicationContext)\n',
    'plugin manager bundle init',
)
# After the existing import/builder row, add the bundle row.
ui = replace_once(
    ui,
    '        root.addView(row)\n\n        status = TextView(this).apply {\n',
    '        root.addView(row)\n\n        val bundleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }\n'
    '        bundleRow.addView(pill("+ Importar ZIP de plugins") { openBundlePicker() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))\n'
    '        root.addView(bundleRow)\n\n        status = TextView(this).apply {\n',
    'bundle button',
)
ui = replace_once(
    ui,
    '        val installed = packs.listInstalled()\n',
    '        val installed = packs.listInstalled()\n        val profiles = capabilityBundles.listProfiles()\n',
    'profile list',
)
ui = ui.replace('• ${installed.size} .iapack(s)"', '• ${installed.size} .iapack(s) • ${profiles.size} perfil(is) ZIP"', 1)
# v8 has built-in suite before installed packs. Add profile cards just before installed packs.
ui = replace_once(
    ui,
    '        installed\n            .filterNot { it.id == PluginPackManager.GENERAL_PACK_ID || it.id == PluginPackManager.TINY_SD_PACK_ID }\n            .forEach { pack -> addPackCard(pack) }\n',
    '        profiles.forEach { addCapabilityProfileCard(it) }\n        installed\n            .filterNot { it.id == PluginPackManager.GENERAL_PACK_ID || it.id == PluginPackManager.TINY_SD_PACK_ID }\n            .forEach { pack -> addPackCard(pack) }\n',
    'profile cards placement',
)
profile_methods = r'''    private fun addCapabilityProfileCard(profile: CapabilityBundleManager.Profile) {
        val workload = when (profile.category) {
            "vision" -> S21PluginScheduler.Workload.VISION
            "developer" -> S21PluginScheduler.Workload.DEVELOPER
            "model" -> S21PluginScheduler.Workload.HEAVY_MODEL
            "document" -> S21PluginScheduler.Workload.DOCUMENT
            else -> S21PluginScheduler.Workload.LIGHT
        }
        val policy = pluginScheduler.policy(profile.id, workload)
        val packReady = profile.requiresPackId?.let(packs::isInstalled) ?: true
        addCard(
            title = profile.name,
            subtitle = "plugin-profile • ${if (packReady) "pronto" else "aguardando modelo"} • ${policy.threads} thread(s)",
            description = profile.description + "\nS21: serial=${policy.maxConcurrent == 1} • térmico ${policy.thermalSoftC}/${policy.thermalHardC}°C" +
                if (policy.maxImageDimension > 0) " • imagem ≤ ${policy.maxImageDimension}px" else "",
            onMenu = { capabilityProfileMenu(profile) },
        )
    }

    private fun capabilityProfileMenu(profile: CapabilityBundleManager.Profile) {
        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(arrayOf("Detalhes", "Remover perfil")) { _, which ->
                if (which == 0) {
                    AlertDialog.Builder(this)
                        .setTitle(profile.name)
                        .setMessage("ID: ${profile.id}\nCategoria: ${profile.category}\nAutomático: ${if (profile.automatic) "sim" else "não"}\nThreads: ${profile.threads}\nConcorrência: ${profile.maxConcurrent}\nTérmico: ${profile.thermalSoftC}/${profile.thermalHardC}°C\n\n${profile.description}\n\nEste perfil é declarativo e não contém código executável.")
                        .setPositiveButton("OK", null).show()
                } else {
                    capabilityBundles.remove(profile.id)
                    refresh()
                }
            }.show()
    }

'''
ui = replace_once(ui, '    private fun addImagePluginCard() {\n', profile_methods + '    private fun addImagePluginCard() {\n', 'profile methods')

bundle_picker = r'''    private fun openBundlePicker() {
        if (importing) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_CAPABILITY_BUNDLE)
    }

'''
ui = replace_once(ui, '    private fun openPackPicker() {\n', bundle_picker + '    private fun openPackPicker() {\n', 'bundle picker')
ui = replace_once(
    ui,
    '            REQUEST_PACK -> importPack(uri)\n            REQUEST_IMAGE_MODEL -> importImageModel(uri)\n',
    '            REQUEST_PACK -> importPack(uri)\n            REQUEST_IMAGE_MODEL -> importImageModel(uri)\n            REQUEST_CAPABILITY_BUNDLE -> importCapabilityBundle(uri)\n',
    'bundle result',
)
bundle_import = r'''    private fun importCapabilityBundle(uri: Uri) {
        importing = true
        status.text = "Validando ZIP de plugins…"
        scope.launch {
            try {
                val result = capabilityBundles.installBundle(uri)
                status.text = "${result.bundleName}: ${result.profiles.size} plugin(s) instalados. Perfis S21 aplicados sem carregar código externo."
            } catch (t: Throwable) {
                status.text = "Falha ao instalar ZIP de plugins: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                importing = false
                refresh()
            }
        }
    }

'''
ui = replace_once(ui, '    private fun importPack(uri: Uri) {\n', bundle_import + '    private fun importPack(uri: Uri) {\n', 'bundle import')
ui = replace_once(
    ui,
    '        private const val REQUEST_IMAGE_MODEL = 7102\n',
    '        private const val REQUEST_IMAGE_MODEL = 7102\n        private const val REQUEST_CAPABILITY_BUNDLE = 7104\n',
    'bundle request code',
)
p.write_text(ui, encoding='utf-8')

# -----------------------------------------------------------------------------
# Main document import uses a limited dispatcher chosen for the S21 instead of unconstrained IO.
# -----------------------------------------------------------------------------
main_path = src / 'MainActivity.kt'
main = main_path.read_text(encoding='utf-8')
main = replace_once(
    main,
    '    private lateinit var resourceGuard: ResourceGuard\n',
    '    private lateinit var resourceGuard: ResourceGuard\n    private lateinit var pluginScheduler: S21PluginScheduler\n',
    'main scheduler field',
)
main = replace_once(
    main,
    '        resourceGuard = ResourceGuard(applicationContext)\n',
    '        resourceGuard = ResourceGuard(applicationContext)\n        pluginScheduler = S21PluginScheduler(applicationContext)\n',
    'main scheduler init',
)
main = main.replace(
    'val result = withContext(Dispatchers.IO) {\n                        attachmentImporter.import(',
    'val result = withContext(pluginScheduler.dispatcher("files.universal", S21PluginScheduler.Workload.DOCUMENT)) {\n                        attachmentImporter.import(',
    1,
)
main_path.write_text(main, encoding='utf-8')

print('Unilaw v8.1 capability bundle + S21 plugin scheduler applied')
