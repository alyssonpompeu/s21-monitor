#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'v5 patch point missing: {label}')
    return text.replace(old, new, 1)


main_path = Path('offlineai/src/main/java/com/alysson/offlineai/MainActivity.kt')
text = main_path.read_text(encoding='utf-8')

text = replace_once(text, 'import android.graphics.Color\n', 'import android.graphics.Color\nimport android.graphics.Typeface\n', 'Typeface import')
text = replace_once(text, 'import android.os.Bundle\n', 'import android.os.Bundle\nimport android.os.SystemClock\n', 'SystemClock import')
text = replace_once(
    text,
    'import java.security.MessageDigest\nimport kotlin.math.min\n',
    'import java.security.MessageDigest\nimport java.text.SimpleDateFormat\nimport java.util.Date\nimport java.util.Locale\nimport kotlin.math.min\n',
    'date imports',
)

text = replace_once(
    text,
    '''    private enum class InteractionMode(val label: String) {
        TEXT("Texto"),
        IMAGE("Criar imagem")
    }
''',
    '''    private enum class InteractionMode(val label: String) {
        TEXT("Texto • Qwen"),
        IMAGE("Imagem • Tiny-SD"),
        CODER("Programação • Coder"),
        BUILDER("Builder • APK/EXE")
    }
''',
    'plugin selector enum',
)

text = replace_once(
    text,
    '''    private lateinit var qualitySpinner: Spinner
    private lateinit var modeSpinner: Spinner
''',
    '''    private lateinit var qualitySpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var liveCard: LinearLayout
    private lateinit var liveStatus: TextView
    private lateinit var codePreview: TextView
''',
    'live UI fields',
)
text = replace_once(
    text,
    '''    private lateinit var imageGenerator: ImageGenerationManager
''',
    '''    private lateinit var imageGenerator: ImageGenerationManager
    private lateinit var persistStore: UnilawPersistStore
''',
    'persist store field',
)
text = replace_once(
    text,
    '''    private var resultMode = false
    private var importing = false
''',
    '''    private var resultMode = false
    private var importing = false
    private var attachmentTargetProjectId: Long? = null
    private var scrollScheduled = false
''',
    'v5 state fields',
)

text = replace_once(
    text,
    '''        resourceGuard = ResourceGuard(applicationContext)
        dialogueBrain = DialogueBrain()
        imageGenerator = ImageGenerationManager(applicationContext, resourceGuard)

        buildUi()
        refreshProjectUi()
        startResourceMonitor()
        prepareOfflineEngine()
''',
    '''        resourceGuard = ResourceGuard(applicationContext)
        dialogueBrain = DialogueBrain(libraryStore)
        imageGenerator = ImageGenerationManager(applicationContext, resourceGuard)
        persistStore = UnilawPersistStore(applicationContext, appPreferences)
        persistStore.ensureInitialized()

        buildUi()
        refreshProjectUi()
        startResourceMonitor()
        maybeRequestPersistenceFolder()
        prepareOfflineEngine()
''',
    'v5 initialization',
)

old_selector = '''        val selectorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                InteractionMode.entries.map { it.label }
            )
        }
        selectorRow.addView(modeSpinner, LinearLayout.LayoutParams(0, dp(50), 0.42f).apply { marginEnd = dp(8) })

        qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                AppPreferences.QualityProfile.entries.map { it.label }
            )
            val current = appPreferences.load().qualityProfile
            setSelection(AppPreferences.QualityProfile.entries.indexOf(current))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    appPreferences.setQualityProfile(AppPreferences.QualityProfile.entries[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        selectorRow.addView(qualitySpinner, LinearLayout.LayoutParams(0, dp(50), 0.58f))
        content.addView(selectorRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
'''
new_selector = '''        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                InteractionMode.entries.map { it.label }
            )
            contentDescription = "Escolher plugin local"
        }
        content.addView(modeSpinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
'''
text = replace_once(text, old_selector, new_selector, 'remove quality selector')
text = text.replace(
    'Perfis locais do Qwen • sem nuvem • ◇ abre plugins e Builder Studio',
    'Alta qualidade fixa • escolha acima o plugin local que vai processar este chat • sem nuvem',
    1,
)

text = replace_once(
    text,
    '''            setOnClickListener { openAttachmentPicker() }
''',
    '''            setOnClickListener {
                attachmentTargetProjectId = activeProjectId
                openAttachmentPicker()
            }
''',
    'main attachment target',
)

text = replace_once(
    text,
    '''        content.addView(libraryStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        resultScroll = ScrollView(this).apply {
''',
    '''        content.addView(libraryStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        liveCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setBackgroundColor(Color.rgb(248, 249, 250))
            visibility = View.GONE
        }
        liveStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(60, 64, 67))
        }
        liveCard.addView(liveStatus)
        content.addView(liveCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(7)
        })

        resultScroll = ScrollView(this).apply {
''',
    'live processing card',
)

text = replace_once(
    text,
    '''        resultContainer.addView(generatedImage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        resultContainer.addView(answer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
''',
    '''        resultContainer.addView(generatedImage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        codePreview = TextView(this).apply {
            visibility = View.GONE
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(32, 33, 36))
            setBackgroundColor(Color.rgb(248, 249, 250))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(true)
        }
        resultContainer.addView(codePreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        resultContainer.addView(answer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
''',
    'code preview',
)

text = replace_once(
    text,
    '''            text = "+ Novo projeto"
            textSize = 16f
            setTextColor(Color.rgb(26, 115, 232))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { createProjectDialog() }
''',
    '''            text = "+ Novo chat"
            textSize = 16f
            setTextColor(Color.rgb(26, 115, 232))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { startNewChat() }
''',
    'new chat action',
)

rename_block = '''        val rename = TextView(this).apply {
            text = "✎ Renomear projeto atual"
            textSize = 15f
            setTextColor(Color.rgb(60, 64, 67))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { renameProjectDialog(activeProjectId) }
        }
        drawer.addView(rename, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))

'''
if rename_block in text:
    text = text.replace(rename_block, '', 1)

text = text.replace(
    'text = if (selected) "● ${project.name}" else project.name',
    'text = if (selected) "📂 ${project.name}" else "📁 ${project.name}"',
    1,
)

text = replace_once(
    text,
    '''        popup.menu.add("Renomear")
        popup.menu.add("Excluir")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Renomear" -> {
''',
    '''        popup.menu.add("Fontes de pesquisa")
        popup.menu.add("Renomear")
        popup.menu.add("Excluir")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Fontes de pesquisa" -> {
                    showProjectSourcesDialog(project)
                    true
                }
                "Renomear" -> {
''',
    'project sources menu',
)

text = replace_once(
    text,
    '''                        val next = libraryStore.deleteProject(project.id)
                        imageGenerator.deleteProjectImages(project.id)
                        next
''',
    '''                        val next = libraryStore.deleteProject(project.id)
                        imageGenerator.deleteProjectImages(project.id)
                        persistStore.deleteProject(project.id)
                        next
''',
    'delete persisted project',
)

insert_before = '''    private fun createProjectDialog() {
'''
methods = '''    private fun startNewChat() {
        if (importing || generationJob?.isActive == true) return
        val stamp = SimpleDateFormat("dd-MM HH:mm", Locale.getDefault()).format(Date())
        activeProjectId = libraryStore.createProject("Chat $stamp")
        appPreferences.setActiveProjectId(activeProjectId)
        drawer.visibility = View.GONE
        resultMode = false
        resultScroll.visibility = View.GONE
        liveCard.visibility = View.GONE
        codePreview.visibility = View.GONE
        bottomSpacer.visibility = View.VISIBLE
        topSpacer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        input.setText("")
        input.hint = if (ready) "Pergunte do seu jeito" else input.hint
        persistStore.appendEvent(activeProjectId, libraryStore.projectName(activeProjectId), "project_created", "novo chat")
        refreshProjectUi()
        input.requestFocus()
    }

    private fun showProjectSourcesDialog(project: LibraryStore.Project) {
        val sources = libraryStore.listDocuments(project.id)
        val body = if (sources.isEmpty()) {
            "Nenhuma fonte anexada. Você pode adicionar PDF, JPG, ZIP, APK, EXE, código, texto ou qualquer outro arquivo. Binários são apenas inspecionados; nunca são executados."
        } else {
            buildString {
                appendLine("${sources.size} fonte(s) neste projeto:")
                appendLine()
                sources.take(30).forEach { source ->
                    append("• ${source.name}")
                    if (source.sizeBytes > 0) append(" • ${formatBytes(source.sizeBytes)}")
                    if (source.sha256.isNotBlank()) append(" • ${source.sha256.take(10)}…")
                    appendLine()
                }
                if (sources.size > 30) appendLine("… e mais ${sources.size - 30}")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Fontes de pesquisa • ${project.name}")
            .setMessage(body)
            .setNegativeButton("Fechar", null)
            .setNeutralButton("Pasta Unilaw") { _, _ -> openPersistenceFolderPicker() }
            .setPositiveButton("Adicionar fonte") { _, _ ->
                attachmentTargetProjectId = project.id
                openAttachmentPicker()
            }
            .show()
    }

    private fun maybeRequestPersistenceFolder() {
        if (persistStore.isSharedFolderConfigured() || appPreferences.persistenceFolderPrompted()) return
        AlertDialog.Builder(this)
            .setTitle("Salvar chats fora do APK")
            .setMessage("Escolha uma pasta em Documentos para manter uma cópia Unilaw de chats, fontes e checkpoints. Mesmo sem escolher, tudo continua sendo salvo internamente no SQLite e no journal local.")
            .setNegativeButton("Depois") { _, _ -> appPreferences.setPersistenceFolderPrompted(true) }
            .setPositiveButton("Escolher pasta") { _, _ -> openPersistenceFolderPicker() }
            .show()
    }

    private fun openPersistenceFolderPicker() {
        appPreferences.setPersistenceFolderPrompted(true)
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }, REQUEST_PERSIST_ROOT)
    }

    private fun showLiveStatus(icon: String, title: String, detail: String) {
        liveCard.visibility = View.VISIBLE
        liveStatus.text = "$icon  $title\n$detail"
    }

    private fun updateCodePreview(text: String) {
        val firstFence = text.indexOf("```")
        if (firstFence < 0) {
            if (InteractionMode.entries[modeSpinner.selectedItemPosition] == InteractionMode.CODER ||
                InteractionMode.entries[modeSpinner.selectedItemPosition] == InteractionMode.BUILDER) {
                codePreview.text = text.takeLast(6000)
                codePreview.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            }
            return
        }
        val afterFence = text.indexOf('\\n', firstFence + 3).let { if (it >= 0) it + 1 else firstFence + 3 }
        var code = text.substring(afterFence)
        val close = code.lastIndexOf("```")
        if (close >= 0) code = code.substring(0, close)
        codePreview.text = code.takeLast(6000)
        codePreview.visibility = if (code.isBlank()) View.GONE else View.VISIBLE
    }

    private fun scheduleScroll() {
        if (scrollScheduled) return
        scrollScheduled = true
        resultScroll.postOnAnimation {
            resultScroll.fullScroll(View.FOCUS_DOWN)
            scrollScheduled = false
        }
    }

    private fun syncProjectPersistence(projectId: Long) {
        val name = libraryStore.projectName(projectId)
        persistStore.syncTranscript(projectId, name, libraryStore.recentConversationTurns(projectId, 80))
    }

    private fun openBuilderForPrompt(prompt: String, sourceOnly: Boolean) {
        val packs = PluginPackManager(applicationContext)
        if (!packs.isInstalled(PluginPackManager.BUILDER_PACK_ID)) {
            activateResultMode()
            answer.text = "Instale o Developer Builder Core em ◇ Plugins locais para usar programação/Builder."
            openPluginManager()
            return
        }
        libraryStore.autoRenameProjectFromQuestion(activeProjectId, prompt)
        libraryStore.recordSearch(activeProjectId, prompt, if (sourceOnly) "CODER" else "BUILDER")
        persistStore.appendEvent(activeProjectId, libraryStore.projectName(activeProjectId), "builder_request", prompt)
        showLiveStatus("⌘", if (sourceOnly) "Coder local" else "Developer Builder Core", "Abrindo editor com geração em tempo real…")
        ready = false
        input.isEnabled = false
        scope.launch {
            try {
                if (engineModelLoaded && ::engine.isInitialized) {
                    withContext(Dispatchers.IO) { engine.cleanUp() }
                    engineModelLoaded = false
                }
                startActivityForResult(Intent(this@MainActivity, BuilderStudioActivity::class.java).apply {
                    putExtra(BuilderStudioActivity.EXTRA_INITIAL_REQUEST, prompt)
                    putExtra(BuilderStudioActivity.EXTRA_TARGET_INDEX, if (sourceOnly) 3 else 0)
                    putExtra(BuilderStudioActivity.EXTRA_PROJECT_ID, activeProjectId)
                }, REQUEST_PLUGINS)
            } catch (t: Throwable) {
                ready = true
                input.isEnabled = true
                answer.text = "Não foi possível abrir o Builder: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    private fun formatBytes(value: Long): String = when {
        value >= 1024L * 1024L * 1024L -> String.format("%.2f GiB", value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024L * 1024L -> String.format("%.1f MiB", value / (1024.0 * 1024.0))
        value >= 1024L -> String.format("%.1f KiB", value / 1024.0)
        else -> "$value B"
    }

'''
text = replace_once(text, insert_before, methods + insert_before, 'v5 helper methods')

old_picker_mimes = '''            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/pdf", "text/plain", "text/markdown", "image/jpeg", "image/png", "image/webp")
            )
'''
if old_picker_mimes not in text:
    raise SystemExit('v5: attachment MIME filter block missing')
text = text.replace(old_picker_mimes, '', 1)

text = replace_once(
    text,
    '''        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PLUGINS) {
''',
    '''        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PERSIST_ROOT) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    persistStore.configureSharedFolder(uri)
                    syncProjectPersistence(activeProjectId)
                    activateResultMode()
                    answer.text = "Pasta Unilaw configurada. Chats e checkpoints agora também serão espelhados nessa pasta."
                }.onFailure {
                    activateResultMode()
                    answer.text = "Não foi possível configurar a pasta persistente: ${it.message ?: it.javaClass.simpleName}"
                }
            }
            return
        }
        if (requestCode == REQUEST_PLUGINS) {
''',
    'persistence folder result',
)

start = text.find('    private fun importAttachments(uris: List<Uri>) {')
end = text.find('    private fun updateLibraryStatus() {', start)
if start < 0 or end < 0:
    raise SystemExit('v5: importAttachments method boundaries missing')
new_import = '''    private fun importAttachments(uris: List<Uri>) {
        if (importing) return
        val targetProjectId = attachmentTargetProjectId?.takeIf { libraryStore.projectExists(it) } ?: activeProjectId
        attachmentTargetProjectId = null
        importing = true
        attachButton.isEnabled = false
        attachButton.alpha = 0.45f
        activateResultMode()
        generatedImage.visibility = View.GONE
        answer.text = "Preparando fontes de pesquisa do projeto…"
        showLiveStatus("📎", "Indexação local", "Lendo ${uris.size} fonte(s) sem executar arquivos binários…")

        scope.launch {
            val messages = mutableListOf<String>()
            try {
                uris.forEachIndexed { index, uri ->
                    val result = withContext(Dispatchers.IO) {
                        attachmentImporter.import(uri, targetProjectId) { progress ->
                            runOnUiThread {
                                libraryStatus.text = "${index + 1}/${uris.size} • $progress"
                                liveStatus.text = "📎  Indexação local\\n${index + 1}/${uris.size} • $progress"
                            }
                        }
                    }
                    messages += buildString {
                        append("✓ ${result.name}: ${result.sections} seção(ões), ${formatCharacters(result.characters)}")
                        if (result.note.isNotBlank()) append(" — ${result.note}")
                    }
                }
                val sources = withContext(Dispatchers.IO) { libraryStore.listDocuments(targetProjectId) }
                withContext(Dispatchers.IO) {
                    sources.take(uris.size).forEach { persistStore.recordSource(targetProjectId, libraryStore.projectName(targetProjectId), it) }
                    syncProjectPersistence(targetProjectId)
                }
                if (targetProjectId == activeProjectId) updateLibraryStatus()
                answer.text = buildString {
                    appendLine("Fontes adicionadas ao projeto “${libraryStore.projectName(targetProjectId)}”:")
                    append(messages.joinToString("\\n"))
                }
                showLiveStatus("✓", "Fontes prontas", "${sources.size} fonte(s) disponíveis neste projeto")
            } catch (t: Throwable) {
                if (targetProjectId == activeProjectId) updateLibraryStatus()
                answer.text = "Falha ao importar: ${t.message ?: t.javaClass.simpleName}"
                showLiveStatus("!", "Falha na indexação", t.message ?: t.javaClass.simpleName)
            } finally {
                importing = false
                attachButton.isEnabled = true
                attachButton.alpha = 1f
            }
        }
    }

'''
text = text[:start] + new_import + text[end:]

start = text.find('    private fun submitCurrentMode() {')
end = text.find('    private fun submitTextQuestion(question: String) {', start)
if start < 0 or end < 0:
    raise SystemExit('v5: submitCurrentMode boundaries missing')
text = text[:start] + '''    private fun submitCurrentMode() {
        val prompt = input.text.toString().trim()
        if (prompt.isEmpty() || importing || generationJob?.isActive == true) return
        when (InteractionMode.entries[modeSpinner.selectedItemPosition]) {
            InteractionMode.TEXT -> submitTextQuestion(prompt)
            InteractionMode.IMAGE -> submitImagePrompt(prompt)
            InteractionMode.CODER -> openBuilderForPrompt(prompt, sourceOnly = true)
            InteractionMode.BUILDER -> openBuilderForPrompt(prompt, sourceOnly = false)
        }
    }

''' + text[end:]

text = replace_once(
    text,
    '''        libraryStore.recordSearch(activeProjectId, question, InteractionMode.TEXT.name)
        input.text.clear()
        input.isEnabled = false
''',
    '''        libraryStore.autoRenameProjectFromQuestion(activeProjectId, question)
        libraryStore.recordSearch(activeProjectId, question, InteractionMode.TEXT.name)
        val turnId = libraryStore.beginConversationTurn(activeProjectId, question, "text.qwen")
        val projectNameForTurn = libraryStore.projectName(activeProjectId)
        persistStore.appendEvent(activeProjectId, projectNameForTurn, "user", question)
        val sourceCount = libraryStore.stats(activeProjectId).documents
        showLiveStatus("✦", "Qwen local • Alta qualidade", "$sourceCount fonte(s) do projeto • resposta em tempo real")
        input.isEnabled = false
''',
    'text submit persistence',
)

old_loop = '''                val generated = StringBuilder()
                val pacingDelayMs = PerformanceSettings(applicationContext).tokenPacingDelayMs()
                engine.sendUserPrompt(prompt, predictionBudget(settings)).collect { token ->
                    val liveGuard = resourceGuard.state(ResourceGuard.TaskKind.CHAT)
                    if (!liveGuard.safe) throw IllegalStateException(liveGuard.reason ?: "Geração interrompida para proteger o aparelho.")
                    generated.append(token)
                    answer.append(token)
                    resultScroll.post { resultScroll.fullScroll(View.FOCUS_DOWN) }
                    if (pacingDelayMs > 0L) delay(pacingDelayMs)
                }
                if (generated.isNotBlank()) dialogueBrain.recordTurn(activeProjectId, question, generated.toString())
'''
new_loop = '''                val visibleGenerated = StringBuilder()
                val filter = StreamingOutputFilter()
                val pacingDelayMs = PerformanceSettings(applicationContext).tokenPacingDelayMs()
                var checkpointJob: Job? = null
                var lastCheckpointAt = SystemClock.elapsedRealtime()
                engine.sendUserPrompt(prompt, predictionBudget(settings)).collect { token ->
                    val liveGuard = resourceGuard.state(ResourceGuard.TaskKind.CHAT)
                    if (!liveGuard.safe) throw IllegalStateException(liveGuard.reason ?: "Geração interrompida para proteger o aparelho.")
                    val visible = filter.push(token)
                    if (visible.isNotEmpty()) {
                        visibleGenerated.append(visible)
                        answer.append(visible)
                        updateCodePreview(visibleGenerated.toString())
                        scheduleScroll()
                    }
                    val now = SystemClock.elapsedRealtime()
                    if ((liveGuard.checkpointSuggested || now - lastCheckpointAt >= 900L) && checkpointJob?.isActive != true) {
                        val snapshot = visibleGenerated.toString()
                        checkpointJob = launch(Dispatchers.IO) {
                            libraryStore.updateConversationTurn(turnId, snapshot)
                            persistStore.checkpoint(activeProjectId, projectNameForTurn, "text.qwen", question, snapshot)
                        }
                        lastCheckpointAt = now
                    }
                    if (pacingDelayMs > 0L) delay(pacingDelayMs)
                }
                val tail = filter.finish()
                if (tail.isNotEmpty()) {
                    visibleGenerated.append(tail)
                    answer.append(tail)
                    updateCodePreview(visibleGenerated.toString())
                }
                checkpointJob?.join()
                val finalAnswer = visibleGenerated.toString().trim()
                withContext(Dispatchers.IO) {
                    libraryStore.updateConversationTurn(turnId, finalAnswer)
                    persistStore.appendEvent(activeProjectId, libraryStore.projectName(activeProjectId), "assistant", finalAnswer)
                    syncProjectPersistence(activeProjectId)
                    persistStore.clearCheckpoint(activeProjectId)
                }
                showLiveStatus("✓", "Qwen local • concluído", "Resposta e histórico persistidos no projeto")
'''
text = replace_once(text, old_loop, new_loop, 'live sanitized text generation')

text = replace_once(
    text,
    '''            } catch (t: Throwable) {
                if (answer.text.isNotEmpty()) answer.append("\\n\\n")
                answer.append("Interrompido: ${t.message ?: t.javaClass.simpleName}")
            } finally {
''',
    '''            } catch (t: Throwable) {
                val partial = answer.text.toString()
                withContext(Dispatchers.IO) {
                    libraryStore.updateConversationTurn(turnId, partial)
                    persistStore.checkpoint(activeProjectId, projectNameForTurn, "text.qwen", question, partial)
                }
                if (answer.text.isNotEmpty()) answer.append("\\n\\n")
                answer.append("Interrompido: ${t.message ?: t.javaClass.simpleName}")
                showLiveStatus("!", "Checkpoint salvo", t.message ?: t.javaClass.simpleName)
            } finally {
''',
    'text failure checkpoint',
)

text = replace_once(
    text,
    '''        libraryStore.recordSearch(activeProjectId, prompt, InteractionMode.IMAGE.name)
        input.text.clear()
        input.isEnabled = false
''',
    '''        libraryStore.autoRenameProjectFromQuestion(activeProjectId, prompt)
        libraryStore.recordSearch(activeProjectId, prompt, InteractionMode.IMAGE.name)
        val imageTurnId = libraryStore.beginConversationTurn(activeProjectId, prompt, "image.tinysd")
        val imageProjectName = libraryStore.projectName(activeProjectId)
        persistStore.appendEvent(activeProjectId, imageProjectName, "image_request", prompt)
        showLiveStatus("▧", "Tiny-SD local", "Preparando geração 512×512 e protegendo memória…")
        input.isEnabled = false
''',
    'image submit persistence',
)

text = replace_once(
    text,
    '''                generatedImage.setImageBitmap(bitmap)
                generatedImage.visibility = View.VISIBLE
                answer.text = "Imagem criada localmente no projeto “${libraryStore.projectName(activeProjectId)}”.\\n${file.name}"
''',
    '''                generatedImage.setImageBitmap(bitmap)
                generatedImage.visibility = View.VISIBLE
                answer.text = "Imagem criada localmente no projeto “${libraryStore.projectName(activeProjectId)}”.\\n${file.name}"
                withContext(Dispatchers.IO) {
                    libraryStore.updateConversationTurn(imageTurnId, answer.text.toString())
                    persistStore.appendEvent(activeProjectId, imageProjectName, "image_created", file.absolutePath)
                    syncProjectPersistence(activeProjectId)
                    persistStore.clearCheckpoint(activeProjectId)
                }
                showLiveStatus("✓", "Tiny-SD • concluído", file.name)
''',
    'image success persistence',
)

text = replace_once(
    text,
    '''            } catch (t: Throwable) {
                answer.text = "Falha na geração de imagem: ${t.message ?: t.javaClass.simpleName}"
            } finally {
''',
    '''            } catch (t: Throwable) {
                answer.text = "Falha na geração de imagem: ${t.message ?: t.javaClass.simpleName}"
                withContext(Dispatchers.IO) {
                    libraryStore.updateConversationTurn(imageTurnId, answer.text.toString())
                    persistStore.checkpoint(activeProjectId, imageProjectName, "image.tinysd", prompt, answer.text.toString())
                }
                showLiveStatus("!", "Imagem interrompida / checkpoint salvo", t.message ?: t.javaClass.simpleName)
            } finally {
''',
    'image failure persistence',
)

text = replace_once(
    text,
    '''        private const val REQUEST_PLUGINS = 4013
''',
    '''        private const val REQUEST_PLUGINS = 4013
        private const val REQUEST_PERSIST_ROOT = 4014
''',
    'persist request code',
)

text = text.replace(
    '- Perfis chamados Avançado, Intermediário e Rápido são modos locais deste app; nunca alegue ser GPT-5.6, GPT-5.5, o3 ou outro modelo de nuvem.',
    '- O app usa sempre o perfil local de Alta qualidade. Nunca alegue ser GPT-5.6, GPT-5.5, o3 ou outro modelo de nuvem.',
    1,
)

# Add persistent-folder status to Personalização below the existing v4 performance entry.
needle = '''        box.addView(TextView(this).apply {
            text = "Avançado, Intermediário e Rápido são perfis de execução do Qwen local. Eles não são GPT-5.6, GPT-5.5 nem o3 e não usam a internet."
'''
replacement = '''        box.addView(TextView(this).apply {
            text = "Persistência Unilaw  ›\\n${persistStore.sharedFolderDescription()} • spill/checkpoints até 5 GiB"
            textSize = 14f
            setTextColor(Color.rgb(26, 115, 232))
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener { openPersistenceFolderPicker() }
        })

        box.addView(TextView(this).apply {
            text = "Alta qualidade é fixa no Qwen local. Não há perfil Avançado/Intermediário/Rápido na tela principal e nenhuma resposta usa nuvem."
'''
text = replace_once(text, needle, replacement, 'personalization persistence row')

# Make onDestroy flush a readable transcript before closing SQLite.
text = replace_once(
    text,
    '''        if (::attachmentImporter.isInitialized) attachmentImporter.close()
        if (::libraryStore.isInitialized) libraryStore.close()
''',
    '''        if (::attachmentImporter.isInitialized) attachmentImporter.close()
        if (::persistStore.isInitialized && ::libraryStore.isInitialized) {
            runCatching { syncProjectPersistence(activeProjectId) }
        }
        if (::libraryStore.isInitialized) libraryStore.close()
''',
    'destroy transcript sync',
)

main_path.write_text(text, encoding='utf-8')

# Plugin manager: controls must be visible directly below the plugin list, not only behind the gear.
plugin_path = Path('offlineai/src/main/java/com/alysson/offlineai/PluginManagerActivity.kt')
plugin = plugin_path.read_text(encoding='utf-8')
plugin = replace_once(
    plugin,
    '''        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
''',
    '''        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildInlinePerformancePanel(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
''',
    'inline performance panel insertion',
)

plugin_method_needle = '''    private fun showPerformanceDialog() {
'''
plugin_method = '''    private fun buildInlinePerformancePanel(): LinearLayout {
        val current = performance.limits()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
            setBackgroundColor(Color.rgb(248, 249, 250))
        }
        panel.addView(TextView(this).apply {
            text = "Recursos globais"
            textSize = 14f
            setTextColor(Color.rgb(32, 33, 36))
        })

        val cpuLabel = TextView(this)
        val cpu = seek(PerformanceSettings.MIN_CPU, PerformanceSettings.MAX_CPU, current.cpuPercent) { value -> cpuLabel.text = "CPU: $value%" }
        cpuLabel.text = "CPU: ${current.cpuPercent}%"
        panel.addView(cpuLabel); panel.addView(cpu)

        val gpuLabel = TextView(this)
        val gpu = seek(PerformanceSettings.MIN_GPU, PerformanceSettings.MAX_GPU, current.gpuPercent) { value -> gpuLabel.text = "GPU / Vulkan: $value%" }
        gpuLabel.text = "GPU / Vulkan: ${current.gpuPercent}%"
        panel.addView(gpuLabel); panel.addView(gpu)

        val ramLabel = TextView(this)
        val ram = seek(PerformanceSettings.MIN_RAM, PerformanceSettings.MAX_RAM, current.ramPercent) { value -> ramLabel.text = "RAM: $value%" }
        ramLabel.text = "RAM: ${current.ramPercent}%"
        panel.addView(ramLabel); panel.addView(ram)

        panel.addView(pill("Salvar limites") {
            performance.setLimits(
                PerformanceSettings.MIN_CPU + cpu.progress,
                PerformanceSettings.MIN_GPU + gpu.progress,
                PerformanceSettings.MIN_RAM + ram.progress,
            )
            refresh()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        panel.addView(TextView(this).apply {
            text = "RAM usa limite de admissão + checkpoint antecipado perto de 2 GiB residentes. O spill persistente de até 5 GiB fica em disco; não é swap do kernel."
            textSize = 11f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(0, dp(4), 0, 0)
        })
        return panel
    }

'''
plugin = replace_once(plugin, plugin_method_needle, plugin_method + plugin_method_needle, 'inline performance method')
plugin_path.write_text(plugin, encoding='utf-8')

# Builder Studio: central plugin selector can hand a prompt directly to the Coder/Builder screen.
builder_path = Path('offlineai/src/main/java/com/alysson/offlineai/BuilderStudioActivity.kt')
builder = builder_path.read_text(encoding='utf-8')
builder = replace_once(
    builder,
    '''        setContentView(root)
    }

    private fun generate() {
''',
    '''        setContentView(root)
        val initialRequest = intent.getStringExtra(EXTRA_INITIAL_REQUEST).orEmpty().trim()
        if (initialRequest.isNotBlank()) {
            requestField.setText(initialRequest)
            targetSpinner.setSelection(intent.getIntExtra(EXTRA_TARGET_INDEX, 0).coerceIn(0, Target.entries.lastIndex))
            if (packs.isInstalled(PluginPackManager.BUILDER_PACK_ID)) {
                requestField.post { generate() }
            }
        }
    }

    private fun generate() {
''',
    'builder initial request',
)

# v4 already changed this loop to CODER guard + pacing. Filter <think> without delaying visible code.
old_builder_loop = '''                val generated = StringBuilder()
                val pacingDelayMs = PerformanceSettings(applicationContext).tokenPacingDelayMs()
                status.text = "Gerando localmente • ${modelStatusText()}"
                engine.sendUserPrompt(prompt, budget).collect { token ->
                    val live = guard.state(ResourceGuard.TaskKind.CODER)
                    if (!live.safe) throw IllegalStateException(live.reason ?: "Geração interrompida para proteger o aparelho.")
                    generated.append(token)
                    sourceField.append(token)
                    if (pacingDelayMs > 0L) kotlinx.coroutines.delay(pacingDelayMs)
                }
                val normalized = normalizeGenerated(target, generated.toString())
'''
new_builder_loop = '''                val generated = StringBuilder()
                val filter = StreamingOutputFilter()
                val pacingDelayMs = PerformanceSettings(applicationContext).tokenPacingDelayMs()
                status.text = "Gerando localmente • ${modelStatusText()} • visualização em tempo real"
                engine.sendUserPrompt(prompt, budget).collect { token ->
                    val live = guard.state(ResourceGuard.TaskKind.CODER)
                    if (!live.safe) throw IllegalStateException(live.reason ?: "Geração interrompida para proteger o aparelho.")
                    val visible = filter.push(token)
                    if (visible.isNotEmpty()) {
                        generated.append(visible)
                        sourceField.append(visible)
                    }
                    if (pacingDelayMs > 0L) kotlinx.coroutines.delay(pacingDelayMs)
                }
                val tail = filter.finish()
                if (tail.isNotEmpty()) {
                    generated.append(tail)
                    sourceField.append(tail)
                }
                val normalized = normalizeGenerated(target, generated.toString())
'''
builder = replace_once(builder, old_builder_loop, new_builder_loop, 'builder think filter')

# Public extras for MainActivity handoff.
companion_marker = '''    companion object {
'''
if companion_marker not in builder:
    raise SystemExit('v5: BuilderStudio companion object missing')
builder = builder.replace(
    companion_marker,
    '''    companion object {
        const val EXTRA_INITIAL_REQUEST = "builder_initial_request"
        const val EXTRA_TARGET_INDEX = "builder_target_index"
        const val EXTRA_PROJECT_ID = "builder_project_id"
''',
    1,
)
builder_path.write_text(builder, encoding='utf-8')

print('Workspace v5 persistent projects/live plugin UI patch applied')
