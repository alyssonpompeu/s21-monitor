#!/usr/bin/env python3
from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'v7 patch point missing: {label}')
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# PluginPackManager: v7 makes the main Qwen and Tiny-SD first-class .iapack models.
# Existing v5/v6 packs remain trusted through the legacy public key while v7 packs use
# a second key. This avoids invalidating already-installed Builder/Coder packages.
# -----------------------------------------------------------------------------
pack_path = Path('offlineai/src/main/java/com/alysson/offlineai/PluginPackManager.kt')
pack = pack_path.read_text(encoding='utf-8')

pack = replace_once(
    pack,
    '''    fun coderModel(): File? = payloadFile(CODER_PACK_ID, "coder.gguf")
        ?.takeIf { it.length() in MIN_CODER_BYTES..MAX_CODER_BYTES }
''',
    '''    fun generalModel(): File? = payloadFile(GENERAL_PACK_ID, "general.gguf")
        ?.takeIf { it.length() in MIN_GENERAL_BYTES..MAX_GENERAL_BYTES }

    fun tinySdModel(): File? = payloadFile(TINY_SD_PACK_ID, "tiny-sd.gguf")
        ?.takeIf { it.length() in MIN_TINY_SD_BYTES..MAX_TINY_SD_BYTES }

    fun coderModel(): File? = payloadFile(CODER_PACK_ID, "coder.gguf")
        ?.takeIf { it.length() in MIN_CODER_BYTES..MAX_CODER_BYTES }
''',
    'model pack accessors',
)

old_verify = '''    private fun verifySignature(manifest: ByteArray, signatureBytes: ByteArray) {
        val publicKeyBytes = context.assets.open(PUBLIC_KEY_ASSET).use { it.readBytes() }
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(key)
        verifier.update(manifest)
        require(verifier.verify(signatureBytes)) { "Assinatura do pacote inválida. Instalação recusada." }
    }
'''
new_verify = '''    private fun verifySignature(manifest: ByteArray, signatureBytes: ByteArray) {
        val verified = PUBLIC_KEY_ASSETS.any { asset ->
            runCatching {
                val publicKeyBytes = context.assets.open(asset).use { it.readBytes() }
                val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicKeyBytes))
                val verifier = Signature.getInstance("Ed25519")
                verifier.initVerify(key)
                verifier.update(manifest)
                verifier.verify(signatureBytes)
            }.getOrDefault(false)
        }
        require(verified) { "Assinatura do pacote inválida. Instalação recusada." }
    }
'''
pack = replace_once(pack, old_verify, new_verify, 'dual plugin signing keys')

pack = replace_once(
    pack,
    '''        const val BUILDER_PACK_ID = "dev.builder.core"
        const val CODER_PACK_ID = "coder.qwen25.1_5b"
        private const val PUBLIC_KEY_ASSET = "plugin_pubkey.der"
''',
    '''        const val BUILDER_PACK_ID = "dev.builder.core"
        const val CODER_PACK_ID = "coder.qwen25.1_5b"
        const val GENERAL_PACK_ID = "model.qwen35.2b"
        const val TINY_SD_PACK_ID = "image.tinysd.q4k"
        private val PUBLIC_KEY_ASSETS = arrayOf("plugin_pubkey.der", "plugin_pubkey_v7.der")
''',
    'v7 pack identifiers and keys',
)

pack = replace_once(
    pack,
    '''        private const val MIN_CODER_BYTES = 850L * MIB
        private const val MAX_CODER_BYTES = 1_300L * MIB
''',
    '''        private const val MIN_GENERAL_BYTES = 1_100L * MIB
        private const val MAX_GENERAL_BYTES = 1_500L * MIB
        private const val MIN_TINY_SD_BYTES = 600L * MIB
        private const val MAX_TINY_SD_BYTES = 900L * MIB
        private const val MIN_CODER_BYTES = 850L * MIB
        private const val MAX_CODER_BYTES = 1_300L * MIB
''',
    'v7 model size guards',
)
pack_path.write_text(pack, encoding='utf-8')


# -----------------------------------------------------------------------------
# Main workspace: stop copying a 1.3 GiB model out of APK assets. The core directly maps
# the verified payload installed under files/iapacks/model.qwen35.2b.
# -----------------------------------------------------------------------------
main_path = Path('offlineai/src/main/java/com/alysson/offlineai/MainActivity.kt')
main = main_path.read_text(encoding='utf-8')

main = replace_once(
    main,
    '''    private lateinit var persistStore: UnilawPersistStore
    private lateinit var uiTheme: UiThemeController
''',
    '''    private lateinit var persistStore: UnilawPersistStore
    private lateinit var pluginPacks: PluginPackManager
    private lateinit var uiTheme: UiThemeController
''',
    'main plugin pack field',
)
main = replace_once(
    main,
    '''        imageGenerator = ImageGenerationManager(applicationContext, resourceGuard)
        persistStore = UnilawPersistStore(applicationContext, appPreferences)
''',
    '''        imageGenerator = ImageGenerationManager(applicationContext, resourceGuard)
        pluginPacks = PluginPackManager(applicationContext)
        persistStore = UnilawPersistStore(applicationContext, appPreferences)
''',
    'main plugin pack init',
)

main, count = re.subn(
    r'''    private fun installVerifiedModel\(\): File \{.*?\n    \}\n\n    private fun smallAction''',
    '''    private fun installVerifiedModel(): File {
        return pluginPacks.generalModel()
            ?: throw IllegalStateException(
                "Qwen General 2B não instalado. Abra ◇ Plugins locais e importe o pacote Qwen-General-2B.iapack."
            )
    }

    private fun smallAction''',
    main,
    count=1,
    flags=re.S,
)
if count != 1:
    raise SystemExit('v7 patch point missing: installVerifiedModel')

old_fatal = '''    private fun showFatalError(t: Throwable) {
        ready = false
        input.isEnabled = false
        input.hint = "IA offline indisponível"
        activateResultMode()
        generatedImage.visibility = View.GONE
        answer.text = "Não foi possível iniciar o motor local.\\n\\n${t.message ?: t.javaClass.simpleName}"
    }
'''
new_fatal = '''    private fun showFatalError(t: Throwable) {
        ready = false
        input.isEnabled = false
        activateResultMode()
        generatedImage.visibility = View.GONE
        val detail = t.message ?: t.javaClass.simpleName
        if (detail.contains("Qwen General", ignoreCase = true)) {
            input.hint = "Instale o Qwen General em ◇ Plugins locais"
            answer.text = "Unilaw Core instalado.\\n\\nO motor está pronto, mas o modelo principal agora é um pacote separado. Abra ◇ Plugins locais e importe Qwen-General-2B.iapack. Depois volte para esta tela; a sessão neural será iniciada sem reinstalar o APK."
        } else {
            input.hint = "IA offline indisponível"
            answer.text = "Não foi possível iniciar o motor local.\\n\\n$detail"
        }
    }
'''
main = replace_once(main, old_fatal, new_fatal, 'friendly missing general model state')

old_plugin_result = '''        if (requestCode == REQUEST_PLUGINS) {
            engineModelLoaded = NeuralSession.isLoaded()
            ready = engineModelLoaded
            input.isEnabled = ready
            input.hint = if (ready) "Pergunte do seu jeito" else "IA indisponível"
            refreshModeWorkspace()
            return
        }
'''
new_plugin_result = '''        if (requestCode == REQUEST_PLUGINS) {
            if (NeuralSession.isLoaded()) {
                engineModelLoaded = true
                ready = true
                input.isEnabled = true
                input.hint = "Pergunte do seu jeito"
            } else if (::pluginPacks.isInitialized && pluginPacks.generalModel() != null) {
                input.isEnabled = false
                input.hint = "Ativando Qwen General…"
                prepareOfflineEngine()
            } else {
                engineModelLoaded = false
                ready = false
                input.isEnabled = false
                input.hint = "Instale o Qwen General em ◇ Plugins locais"
            }
            refreshModeWorkspace()
            return
        }
'''
main = replace_once(main, old_plugin_result, new_plugin_result, 'reload after installing Qwen General')

# Old asset constants are intentionally removed so future developers cannot accidentally re-bundle
# the 2B model into the core APK.
main = main.replace('        private const val MODEL_ASSET = "model.gguf"\n', '')
main_path.write_text(main, encoding='utf-8')


# -----------------------------------------------------------------------------
# Tiny-SD: prefer signed iapack payload. Keep the old naked-GGUF importer only as a migration
# fallback for existing users; new v7 UI installs the signed package.
# -----------------------------------------------------------------------------
image_path = Path('offlineai/src/main/java/com/alysson/offlineai/ImageGenerationManager.kt')
image = image_path.read_text(encoding='utf-8')
image = replace_once(
    image,
    '''    private val performance = PerformanceSettings(context.applicationContext)
    private val modelDir = File(context.filesDir, "image-models").apply { mkdirs() }
    private val outputDir = File(context.filesDir, "generated-images").apply { mkdirs() }
    private val modelFile = File(modelDir, MODEL_FILE)

    fun hasModel(): Boolean = modelFile.isFile && modelFile.length() >= MIN_MODEL_BYTES
''',
    '''    private val performance = PerformanceSettings(context.applicationContext)
    private val packs = PluginPackManager(context.applicationContext)
    private val modelDir = File(context.filesDir, "image-models").apply { mkdirs() }
    private val outputDir = File(context.filesDir, "generated-images").apply { mkdirs() }
    private val legacyModelFile = File(modelDir, MODEL_FILE)

    private fun activeModelFile(): File? =
        packs.tinySdModel() ?: legacyModelFile.takeIf { it.isFile && it.length() >= MIN_MODEL_BYTES }

    fun hasModel(): Boolean = activeModelFile() != null
''',
    'Tiny-SD pack lookup',
)
image = image.replace(
    '''    fun modelDescription(): String = if (hasModel()) {
        "Tiny-SD Q4_K local • 512×512"
''',
    '''    fun modelDescription(): String = if (hasModel()) {
        if (packs.tinySdModel() != null) "Tiny-SD Q4_K .iapack • 512×512" else "Tiny-SD Q4_K legado • 512×512"
''',
    1,
)
image = replace_once(
    image,
    '''    fun deleteModel(): Boolean {
        val tmp = File(modelDir, "$MODEL_FILE.tmp")
        tmp.delete()
        return !modelFile.exists() || modelFile.delete()
    }
''',
    '''    fun deleteModel(): Boolean {
        val tmp = File(modelDir, "$MODEL_FILE.tmp")
        tmp.delete()
        packs.remove(PluginPackManager.TINY_SD_PACK_ID)
        return !legacyModelFile.exists() || legacyModelFile.delete()
    }
''',
    'Tiny-SD pack delete',
)
# The legacy raw importer writes only to its legacy location.
image = image.replace('if (modelFile.exists()) modelFile.delete()', 'if (legacyModelFile.exists()) legacyModelFile.delete()', 1)
image = image.replace('check(tmp.renameTo(modelFile))', 'check(tmp.renameTo(legacyModelFile))', 1)
image = image.replace('ImportResult(modelFile, actual)', 'ImportResult(legacyModelFile, actual)', 1)

image = replace_once(
    image,
    '''        require(hasModel()) { "Instale primeiro o pacote local de geração de imagens em Plugins locais." }
        val admission = resourceGuard.state(ResourceGuard.TaskKind.IMAGE)
''',
    '''        val selectedModel = activeModelFile()
            ?: throw IllegalStateException("Instale primeiro o Tiny-SD.iapack em Plugins locais.")
        val admission = resourceGuard.state(ResourceGuard.TaskKind.IMAGE)
''',
    'Tiny-SD active payload in generate',
)
image = image.replace('            "-m", modelFile.absolutePath,', '            "-m", selectedModel.absolutePath,', 1)
image_path.write_text(image, encoding='utf-8')


# -----------------------------------------------------------------------------
# Plugin UI: make the modular architecture explicit. Qwen General is a required model card;
# Tiny-SD is managed as .iapack instead of a naked GGUF in the primary v7 path.
# -----------------------------------------------------------------------------
plugin_path = Path('offlineai/src/main/java/com/alysson/offlineai/PluginManagerActivity.kt')
plugin = plugin_path.read_text(encoding='utf-8')
plugin = plugin.replace(
    'Todos os recursos opcionais ficam aqui, inclusive imagens. Os limites são alvos de segurança: Android não permite impor uma quota exata de GPU por aplicativo.',
    'Unilaw Core fica pequeno: modelos e capacidades pesadas são instalados aqui como .iapack. Qwen General é necessário para chat local; Coder, Tiny-SD e Builder são independentes.',
    1,
)

plugin = replace_once(
    plugin,
    '''        addNeuralLibraryCard()
        addImagePluginCard()
        installed.forEach { pack -> addPackCard(pack) }
''',
    '''        addNeuralLibraryCard()
        addGeneralModelCard()
        addImagePluginCard()
        installed
            .filterNot { it.id == PluginPackManager.GENERAL_PACK_ID || it.id == PluginPackManager.TINY_SD_PACK_ID }
            .forEach { pack -> addPackCard(pack) }
''',
    'special model cards',
)

insert_before = '''    private fun addImagePluginCard() {
'''
general_methods = '''    private fun addGeneralModelCard() {
        val model = packs.generalModel()
        val installed = model != null
        addCard(
            title = "Qwen General 2B",
            subtitle = if (installed) "required-model • Q4_K_M • ${formatSize(model!!.length())} • pronto" else "required-model • não instalado",
            description = "Modelo principal do chat. Fica fora do APK Core e é mapeado diretamente do pacote verificado, evitando uma cópia duplicada de ~1,3 GiB no armazenamento interno.",
            onMenu = { generalModelMenu() },
        )
    }

    private fun generalModelMenu() {
        if (packs.generalModel() == null) {
            AlertDialog.Builder(this)
                .setTitle("Qwen General 2B")
                .setMessage("Importe Qwen-General-2B.iapack. Após a instalação, volte à tela principal para iniciar a sessão neural.")
                .setNegativeButton("Fechar", null)
                .setPositiveButton("Importar .iapack") { _, _ -> openPackPicker() }
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Qwen General 2B")
                .setMessage("Modelo principal instalado e verificado. Para evitar remover um arquivo que possa estar mapeado na RAM, a exclusão do Qwen General não é oferecida durante esta sessão.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

'''
plugin = replace_once(plugin, insert_before, general_methods + insert_before, 'general model plugin card methods')

plugin = plugin.replace('arrayOf("Detalhes", "Desempenho / backend", "Substituir modelo", "Excluir modelo")', 'arrayOf("Detalhes", "Desempenho / backend", "Substituir .iapack", "Excluir modelo")', 1)
plugin = plugin.replace('arrayOf("Detalhes", "Desempenho / backend", "Importar modelo")', 'arrayOf("Detalhes", "Desempenho / backend", "Importar .iapack")', 1)
plugin = plugin.replace('"Importar modelo", "Substituir modelo" -> openImagePicker()', '"Importar .iapack", "Substituir .iapack" -> openPackPicker()', 1)
plugin_path.write_text(plugin, encoding='utf-8')


# -----------------------------------------------------------------------------
# Build identity: v7 is a true shrunk release build (signed with CI debug identity for sideloading
# until a permanent project keystore is configured). No model.gguf belongs in the APK.
# -----------------------------------------------------------------------------
gradle_path = Path('offlineai/build.gradle')
gradle = gradle_path.read_text(encoding='utf-8')
gradle, n = re.subn(r'versionCode\s+\d+', 'versionCode 21', gradle, count=1)
if n != 1:
    raise SystemExit('v7 could not update versionCode')
gradle, n = re.subn(r"versionName\s+'[^']+'", "versionName '8.0.0-corepacks-v7'", gradle, count=1)
if n != 1:
    raise SystemExit('v7 could not update versionName')
gradle = replace_once(
    gradle,
    '''        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
''',
    '''        release {
            signingConfig signingConfigs.debug
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
''',
    'signed optimized release build',
)
gradle_path.write_text(gradle, encoding='utf-8')

manifest_path = Path('offlineai/src/main/AndroidManifest.xml')
manifest = manifest_path.read_text(encoding='utf-8')
manifest = manifest.replace('android:label="Unilaw AI • S21"', 'android:label="Unilaw AI • Core S21"', 1)
manifest_path.write_text(manifest, encoding='utf-8')

print('Workspace v7 Core + signed model packs patch applied')
