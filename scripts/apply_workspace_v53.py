#!/usr/bin/env python3
from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'v5.3 patch point missing: {label}')
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# Main workspace: add Work Online as a selectable mode while keeping the main
# APK completely offline. Network access lives only in the companion APK.
# -----------------------------------------------------------------------------
main_path = Path('offlineai/src/main/java/com/alysson/offlineai/MainActivity.kt')
main = main_path.read_text(encoding='utf-8')

main = replace_once(
    main,
    '''    private enum class InteractionMode(val label: String) {
        TEXT("Texto • Qwen"),
        IMAGE("Imagem • Tiny-SD"),
        CODER("Programação • Coder"),
        BUILDER("Builder • APK/EXE")
    }
''',
    '''    private enum class InteractionMode(val label: String) {
        TEXT("Texto • Qwen"),
        IMAGE("Imagem • Tiny-SD"),
        CODER("Programação • Coder"),
        BUILDER("Builder • APK/EXE"),
        WORK_ONLINE("Work • Internet")
    }
''',
    'Work Online interaction mode',
)

main = replace_once(
    main,
    '''    private var attachmentTargetProjectId: Long? = null
    private var scrollScheduled = false
''',
    '''    private var attachmentTargetProjectId: Long? = null
    private var scrollScheduled = false
    private var pendingWorkTurnId: Long? = null
    private var pendingWorkProjectId: Long? = null
''',
    'Work Online pending turn fields',
)

main = replace_once(
    main,
    '''        when (InteractionMode.entries[modeSpinner.selectedItemPosition]) {
            InteractionMode.TEXT -> submitTextQuestion(prompt)
            InteractionMode.IMAGE -> submitImagePrompt(prompt)
            InteractionMode.CODER -> openBuilderForPrompt(prompt, sourceOnly = true)
            InteractionMode.BUILDER -> openBuilderForPrompt(prompt, sourceOnly = false)
        }
''',
    '''        when (InteractionMode.entries[modeSpinner.selectedItemPosition]) {
            InteractionMode.TEXT -> submitTextQuestion(prompt)
            InteractionMode.IMAGE -> submitImagePrompt(prompt)
            InteractionMode.CODER -> openBuilderForPrompt(prompt, sourceOnly = true)
            InteractionMode.BUILDER -> openBuilderForPrompt(prompt, sourceOnly = false)
            InteractionMode.WORK_ONLINE -> openWorkOnline(prompt)
        }
''',
    'submit Work Online mode',
)

work_methods = r'''    private fun openWorkOnline(prompt: String) {
        if (!workOnlineInstalled()) {
            activateResultMode()
            answer.text = "Work Online ainda não está instalado. Abra ◇ Plugins locais e instale o APK companion Work-Online-v1.apk da mesma release. A IA principal continua sem permissão de Internet."
            showLiveStatus("☁", "Work Online não instalado", "O acesso à rede fica isolado em um APK companion separado.")
            openPluginManager()
            return
        }

        libraryStore.autoRenameProjectFromQuestion(activeProjectId, prompt)
        libraryStore.recordSearch(activeProjectId, prompt, "WORK_ONLINE")
        pendingWorkProjectId = activeProjectId
        pendingWorkTurnId = libraryStore.beginConversationTurn(activeProjectId, prompt, "work.online")
        persistStore.appendEvent(activeProjectId, libraryStore.projectName(activeProjectId), "work_online_request", prompt)
        input.isEnabled = false
        input.hint = prompt
        showLiveStatus("☁", "Work Online", "Preparando contexto local. A rede Qwen permanece residente e o acesso à Internet ocorrerá somente no companion.")

        scope.launch {
            val context = withContext(Dispatchers.IO) {
                val projectContext = libraryStore.retrieve(prompt, activeProjectId, 8)
                val commonContext = libraryStore.retrieveNeuralLibrary(prompt, 8, excludeProjectId = activeProjectId)
                listOf(projectContext, commonContext)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                    .take(WORK_CONTEXT_LIMIT)
            }
            try {
                val intent = Intent(WORK_ACTION).apply {
                    setPackage(WORK_PACKAGE)
                    putExtra(WORK_EXTRA_INITIAL_REQUEST, prompt)
                    putExtra(WORK_EXTRA_PROJECT_NAME, libraryStore.projectName(activeProjectId))
                    putExtra(WORK_EXTRA_PROJECT_CONTEXT, context)
                }
                startActivityForResult(intent, REQUEST_WORK_ONLINE)
            } catch (t: Throwable) {
                ready = NeuralSession.isLoaded()
                engineModelLoaded = ready
                input.isEnabled = ready
                input.hint = if (ready) "Pergunte do seu jeito" else "IA indisponível"
                activateResultMode()
                answer.text = "Não foi possível abrir Work Online: ${t.message ?: t.javaClass.simpleName}"
                showLiveStatus("!", "Falha ao abrir Work Online", t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun workOnlineInstalled(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(WORK_PACKAGE, 0)
        true
    }.getOrDefault(false)

'''
main = replace_once(
    main,
    '    private fun submitTextQuestion(question: String) {\n',
    work_methods + '    private fun submitTextQuestion(question: String) {\n',
    'Work Online methods',
)

plugins_result = '''        if (requestCode == REQUEST_PLUGINS) {
            engineModelLoaded = NeuralSession.isLoaded()
            ready = engineModelLoaded
            input.isEnabled = ready
            input.hint = if (ready) "Pergunte do seu jeito" else "IA indisponível"
            refreshModeWorkspace()
            return
        }
'''
work_result = r'''        if (requestCode == REQUEST_WORK_ONLINE) {
            engineModelLoaded = NeuralSession.isLoaded()
            ready = engineModelLoaded
            input.isEnabled = ready
            input.hint = if (ready) "Pergunte do seu jeito" else "IA indisponível"
            attachButton.isEnabled = true

            if (resultCode == RESULT_OK && data != null) {
                val task = data.getStringExtra(WORK_EXTRA_TASK_TEXT).orEmpty()
                val resultText = data.getStringExtra(WORK_EXTRA_RESULT_TEXT).orEmpty()
                val sourceText = data.getStringExtra(WORK_EXTRA_SOURCE_TEXT).orEmpty()
                if (task.isNotBlank()) input.setText(task)
                if (resultText.isNotBlank()) {
                    activateResultMode()
                    generatedImage.visibility = View.GONE
                    answer.text = buildString {
                        append(resultText)
                        if (sourceText.isNotBlank()) {
                            append("\n\n")
                            append(sourceText)
                        }
                    }
                    val turnId = pendingWorkTurnId
                    val projectId = pendingWorkProjectId ?: activeProjectId
                    val persisted = answer.text.toString()
                    if (turnId != null) {
                        scope.launch(Dispatchers.IO) {
                            libraryStore.updateConversationTurn(turnId, persisted)
                            persistStore.appendEvent(projectId, libraryStore.projectName(projectId), "work_online_result", persisted)
                            syncProjectPersistence(projectId)
                        }
                    }
                    showLiveStatus("✓", "Work Online • concluído", "Entrega online devolvida ao projeto e salva no histórico local.")
                }
            }
            pendingWorkTurnId = null
            pendingWorkProjectId = null
            refreshModeWorkspace()
            return
        }

'''
main = replace_once(main, plugins_result, work_result + plugins_result, 'Work Online activity result')

main = replace_once(
    main,
    '''        private const val REQUEST_PLUGINS = 4013
''',
    '''        private const val REQUEST_PLUGINS = 4013
        private const val REQUEST_WORK_ONLINE = 4015
        private const val WORK_PACKAGE = "com.alysson.offlineai.workonline"
        private const val WORK_ACTION = "com.alysson.offlineai.WORK_ONLINE"
        private const val WORK_EXTRA_INITIAL_REQUEST = "work_initial_request"
        private const val WORK_EXTRA_PROJECT_NAME = "work_project_name"
        private const val WORK_EXTRA_PROJECT_CONTEXT = "work_project_context"
        private const val WORK_EXTRA_RESULT_TEXT = "work_result_text"
        private const val WORK_EXTRA_SOURCE_TEXT = "work_source_text"
        private const val WORK_EXTRA_TASK_TEXT = "work_task_text"
        private const val WORK_CONTEXT_LIMIT = 36_000
''',
    'Work Online constants',
)

main_path.write_text(main, encoding='utf-8')


# -----------------------------------------------------------------------------
# Plugin manager: expose Work Online alongside local plugins and Neural Library.
# It is a companion APK rather than an iapack because Android permissions are
# declared per APK and the offline main APK must not gain INTERNET permission.
# -----------------------------------------------------------------------------
plugin_path = Path('offlineai/src/main/java/com/alysson/offlineai/PluginManagerActivity.kt')
plugin = plugin_path.read_text(encoding='utf-8')
plugin = replace_once(
    plugin,
    '''        addNeuralLibraryCard()
        addImagePluginCard()
''',
    '''        addWorkOnlineCard()
        addNeuralLibraryCard()
        addImagePluginCard()
''',
    'Work Online card position',
)

plugin_methods = r'''    private fun addWorkOnlineCard() {
        val installed = workOnlineInstalled()
        addCard(
            title = "Work Online",
            subtitle = "online-work • ${if (installed) "companion instalado" else "não instalado"} • Internet isolada",
            description = "Ambiente para pesquisas web e trabalhos em múltiplas etapas. O APK principal continua offline; somente o companion Work Online possui android.permission.INTERNET.",
            onMenu = { workOnlineMenu(installed) },
        )
    }

    private fun workOnlineMenu(installed: Boolean) {
        val options = if (installed) arrayOf("Abrir Work Online", "Detalhes") else arrayOf("Detalhes")
        AlertDialog.Builder(this)
            .setTitle("Work Online")
            .setItems(options) { _, which ->
                if (installed && which == 0) {
                    runCatching {
                        startActivity(Intent(WORK_ACTION).apply { setPackage(WORK_PACKAGE) })
                    }.onFailure {
                        status.text = "Não foi possível abrir Work Online: ${it.message ?: it.javaClass.simpleName}"
                    }
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Work Online • Internet")
                        .setMessage(
                            if (installed) {
                                "Companion instalado. Ele mantém a chave da API no Android Keystore e usa OpenAI Responses API + web_search. O Qwen, Tiny-SD, Coder e Biblioteca Neural continuam locais."
                            } else {
                                "Instale Work-Online-v1.apk publicado na mesma release da Workspace v5.3. Ele é separado de propósito: plugins .iapack não podem conceder permissão de Internet a um APK offline sem alterar a fronteira de segurança do Android."
                            }
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
    }

    private fun workOnlineInstalled(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(WORK_PACKAGE, 0)
        true
    }.getOrDefault(false)

'''
plugin = replace_once(
    plugin,
    '    private fun addNeuralLibraryCard() {\n',
    plugin_methods + '    private fun addNeuralLibraryCard() {\n',
    'Work Online plugin methods',
)

plugin = replace_once(
    plugin,
    '''        private const val REQUEST_PACK = 7101
''',
    '''        private const val WORK_PACKAGE = "com.alysson.offlineai.workonline"
        private const val WORK_ACTION = "com.alysson.offlineai.WORK_ONLINE"
        private const val REQUEST_PACK = 7101
''',
    'Work Online plugin constants',
)
plugin_path.write_text(plugin, encoding='utf-8')


# -----------------------------------------------------------------------------
# Manifest package visibility. INTERNET remains explicitly removed from offlineai.
# -----------------------------------------------------------------------------
manifest_path = Path('offlineai/src/main/AndroidManifest.xml')
manifest = manifest_path.read_text(encoding='utf-8')
if 'com.alysson.offlineai.workonline' not in manifest:
    manifest = replace_once(
        manifest,
        '''    <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
''',
        '''    <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />

    <queries>
        <package android:name="com.alysson.offlineai.workonline" />
        <intent>
            <action android:name="com.alysson.offlineai.WORK_ONLINE" />
        </intent>
    </queries>
''',
        'Work Online package visibility',
    )
manifest_path.write_text(manifest, encoding='utf-8')


# -----------------------------------------------------------------------------
# Stable main package: v5.3 updates v5.2/v5.2.1 in place and keeps all local data.
# -----------------------------------------------------------------------------
gradle_path = Path('offlineai/build.gradle')
gradle = gradle_path.read_text(encoding='utf-8')
gradle, count = re.subn(r"versionCode\s+\d+", "versionCode 12", gradle, count=1)
if count != 1:
    raise SystemExit('v5.3 could not update offlineai versionCode')
gradle, count = re.subn(r"versionName\s+'[^']+'", "versionName '6.3.0-plugin-v5.3-work-online'", gradle, count=1)
if count != 1:
    raise SystemExit('v5.3 could not update offlineai versionName')
if "applicationId 'com.alysson.offlineai.pluginv52'" not in gradle:
    raise SystemExit('v5.3 requires stable applicationId com.alysson.offlineai.pluginv52')
gradle_path.write_text(gradle, encoding='utf-8')

print('Workspace v5.3 Work Online patch applied')
