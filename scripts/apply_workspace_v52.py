#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'v5.2 patch point missing: {label}')
    return text.replace(old, new, 1)


main_path = Path('offlineai/src/main/java/com/alysson/offlineai/MainActivity.kt')
text = main_path.read_text(encoding='utf-8')

# Compact the main workspace: resource controls remain in Plugins/Settings, not above the prompt.
text = replace_once(
    text,
    '''        content.addView(resourceStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
''',
    '''        resourceStatus.visibility = View.GONE
        content.addView(resourceStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
''',
    'hide main resource row',
)
text = replace_once(
    text,
    '''        content.addView(safetyStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
''',
    '''        safetyStatus.visibility = View.GONE
        content.addView(safetyStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
''',
    'hide main safety row',
)
text = replace_once(
    text,
    '''        content.addView(topSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
''',
    '''        content.addView(topSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)))
''',
    'raise prompt top spacer',
)
text = replace_once(
    text,
    '''        content.addView(profileNote, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
''',
    '''        profileNote.visibility = View.GONE
        content.addView(profileNote, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
''',
    'hide profile note',
)
text = replace_once(
    text,
    '''        bottomSpacer = Space(this)
        content.addView(bottomSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.25f))
''',
    '''        bottomSpacer = Space(this).apply { visibility = View.GONE }
        content.addView(bottomSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
''',
    'remove bottom centering spacer',
)

# Any navigation reset must keep the prompt near the top instead of recentering it.
text = text.replace(
    'topSpacer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)',
    'topSpacer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10))',
)
text = text.replace('bottomSpacer.visibility = View.VISIBLE', 'bottomSpacer.visibility = View.GONE')

# Turn the v5 live card into the large middle workspace requested for Builder/Coder.
text = replace_once(
    text,
    '''        liveCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setBackgroundColor(Color.rgb(248, 249, 250))
            visibility = View.GONE
        }
        liveStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(60, 64, 67))
        }
''',
    '''        liveCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.rgb(245, 240, 255))
            visibility = View.GONE
        }
        liveStatus = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(55, 48, 75))
            setLineSpacing(0f, 1.18f)
            setTextIsSelectable(true)
            gravity = Gravity.TOP or Gravity.START
        }
''',
    'large purple live card',
)
text = replace_once(
    text,
    '''        content.addView(liveCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(7)
        })

        resultScroll = ScrollView(this).apply {
''',
    '''        content.addView(liveCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(7)
        })
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshModeWorkspace()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        refreshModeWorkspace()

        resultScroll = ScrollView(this).apply {
''',
    'mode live workspace listener',
)

old_live = '''    private fun showLiveStatus(icon: String, title: String, detail: String) {
        liveCard.visibility = View.VISIBLE
        liveStatus.text = "$icon  $title\\n$detail"
    }
'''
new_live = '''    private fun showLiveStatus(icon: String, title: String, detail: String) {
        val buildMode = InteractionMode.entries[modeSpinner.selectedItemPosition].let {
            it == InteractionMode.CODER || it == InteractionMode.BUILDER
        }
        liveCard.visibility = View.VISIBLE
        liveCard.layoutParams = if (buildMode) {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(7) }
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) }
        }
        val entry = "$icon  $title\\n$detail"
        liveStatus.text = if (buildMode && liveStatus.text.isNotBlank()) {
            (liveStatus.text.toString() + "\\n\\n" + entry).takeLast(12000)
        } else {
            entry
        }
    }

    private fun refreshModeWorkspace() {
        if (!::liveCard.isInitialized || !::modeSpinner.isInitialized) return
        val mode = InteractionMode.entries[modeSpinner.selectedItemPosition]
        val buildMode = mode == InteractionMode.CODER || mode == InteractionMode.BUILDER
        if (buildMode) {
            liveCard.visibility = View.VISIBLE
            liveCard.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply { topMargin = dp(7) }
            if (liveStatus.text.isBlank() || liveStatus.text.toString().startsWith("✦") || liveStatus.text.toString().startsWith("▧")) {
                liveStatus.text = if (mode == InteractionMode.BUILDER) {
                    "⌘  Builder • processo em tempo real\\nAguardando sua solicitação. A rede Qwen continua residente na RAM e o processo de geração/build aparecerá aqui."
                } else {
                    "⌘  Coder • processo em tempo real\\nAguardando sua solicitação. A rede neural da sessão não será descarregada ao navegar."
                }
            }
        } else if (!resultMode) {
            liveCard.visibility = View.GONE
            liveCard.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(7) }
        }
    }
'''
text = replace_once(text, old_live, new_live, 'live status workspace methods')

# New chats and project switches must restore the selected mode workspace instead of hiding it.
text = text.replace(
    '''        liveCard.visibility = View.GONE
        codePreview.visibility = View.GONE
''',
    '''        codePreview.visibility = View.GONE
        refreshModeWorkspace()
''',
    1,
)

# Main Qwen is initialized and loaded through a process-scoped singleton.
old_init = '''                input.hint = "Inicializando CPU…"
                engine = AiChat.getInferenceEngine(applicationContext)
                val initializedState = engine.state.first {
                    it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                }
                if (initializedState is InferenceEngine.State.Error) throw initializedState.exception

                loadChatModel()
'''
text = replace_once(text, old_init, '''                loadChatModel()
''', 'main engine initialization')

old_load = '''    private suspend fun loadChatModel() {
        val model = localModelFile ?: error("Modelo local não preparado.")
        input.hint = "Carregando rede neural…"
        engine.loadModel(model.absolutePath)
        engine.setSystemPrompt(SYSTEM_PROMPT)
        engineModelLoaded = true
        ready = true
        input.isEnabled = true
        input.hint = "Pergunte do seu jeito"
    }
'''
new_load = '''    private suspend fun loadChatModel() {
        val model = localModelFile ?: error("Modelo local não preparado.")
        engine = NeuralSession.acquire(applicationContext, model, SYSTEM_PROMPT) { progress ->
            runOnUiThread { input.hint = progress }
        }
        engineModelLoaded = NeuralSession.isLoaded()
        ready = engineModelLoaded
        input.isEnabled = ready
        input.hint = if (ready) "Pergunte do seu jeito" else "IA indisponível"
        if (ready && InteractionMode.entries[modeSpinner.selectedItemPosition].let { it == InteractionMode.BUILDER || it == InteractionMode.CODER }) {
            showLiveStatus("●", "Sessão neural pronta", NeuralSession.sessionDescription())
        }
    }
'''
text = replace_once(text, old_load, new_load, 'resident loadChatModel')

# Plugins no longer evict the main Qwen just because another Activity is opened.
start = text.find('    private fun openPluginManager() {')
end = text.find('    private fun showPersonalizationDialog() {', start)
if start < 0 or end < 0:
    raise SystemExit('v5.2: openPluginManager boundaries missing')
text = text[:start] + '''    private fun openPluginManager() {
        if (importing || generationJob?.isActive == true) return
        input.isEnabled = false
        input.hint = "Sessão neural mantida na RAM…"
        showLiveStatus("◇", "Plugins locais", "Abrindo configurações sem descarregar ${NeuralSession.sessionDescription()}.")
        try {
            startActivityForResult(Intent(this@MainActivity, PluginManagerActivity::class.java), REQUEST_PLUGINS)
        } catch (t: Throwable) {
            ready = NeuralSession.isLoaded()
            engineModelLoaded = ready
            input.isEnabled = ready
            input.hint = if (ready) "Pergunte do seu jeito" else "IA indisponível"
            activateResultMode()
            answer.text = "Não foi possível abrir os plugins: ${t.message ?: t.javaClass.simpleName}"
        }
    }

''' + text[end:]

old_plugins_result = '''        if (requestCode == REQUEST_PLUGINS) {
            scope.launch {
                try {
                    if (!engineModelLoaded && ::engine.isInitialized) loadChatModel()
                } catch (t: Throwable) {
                    ready = false
                    input.isEnabled = false
                    input.hint = "IA offline indisponível"
                    activateResultMode()
                    answer.text = "Não foi possível recarregar a IA após o Builder Studio: ${t.message ?: t.javaClass.simpleName}"
                }
            }
            return
        }
'''
new_plugins_result = '''        if (requestCode == REQUEST_PLUGINS) {
            engineModelLoaded = NeuralSession.isLoaded()
            ready = engineModelLoaded
            input.isEnabled = ready
            input.hint = if (ready) "Pergunte do seu jeito" else "IA indisponível"
            refreshModeWorkspace()
            return
        }
'''
text = replace_once(text, old_plugins_result, new_plugins_result, 'plugin return without reload')

# Builder handoff keeps the resident model. BuilderStudio borrows the same process engine.
start = text.find('    private fun openBuilderForPrompt(prompt: String, sourceOnly: Boolean) {')
end = text.find('    private fun formatBytes(value: Long): String', start)
if start < 0 or end < 0:
    raise SystemExit('v5.2: openBuilderForPrompt boundaries missing')
text = text[:start] + '''    private fun openBuilderForPrompt(prompt: String, sourceOnly: Boolean) {
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
        showLiveStatus(
            "1/4",
            if (sourceOnly) "Coder local" else "Developer Builder Core",
            "Solicitação recebida • ${NeuralSession.sessionDescription()} • abrindo processo visual sem descarregar o Qwen…",
        )
        input.isEnabled = false
        input.hint = prompt
        try {
            startActivityForResult(Intent(this@MainActivity, BuilderStudioActivity::class.java).apply {
                putExtra(BuilderStudioActivity.EXTRA_INITIAL_REQUEST, prompt)
                putExtra(BuilderStudioActivity.EXTRA_TARGET_INDEX, if (sourceOnly) 3 else 0)
                putExtra(BuilderStudioActivity.EXTRA_PROJECT_ID, activeProjectId)
            }, REQUEST_PLUGINS)
        } catch (t: Throwable) {
            ready = NeuralSession.isLoaded()
            engineModelLoaded = ready
            input.isEnabled = ready
            answer.text = "Não foi possível abrir o Builder: ${t.message ?: t.javaClass.simpleName}"
            showLiveStatus("!", "Falha ao abrir Builder", t.message ?: t.javaClass.simpleName)
        }
    }

''' + text[end:]

# Image generation may be blocked by RAM protection, but it no longer unloads/reloads Qwen.
start = text.find('    private fun submitImagePrompt(prompt: String) {')
end = text.find('    private fun predictionBudget(settings: AppPreferences.Settings): Int {', start)
if start < 0 or end < 0:
    raise SystemExit('v5.2: submitImagePrompt boundaries missing')
image_method = '''    private fun submitImagePrompt(prompt: String) {
        if (!imageGenerator.hasModel()) {
            activateResultMode()
            answer.text = "Instale o Tiny-SD em ◇ Plugins locais antes de criar imagens."
            openPluginManager()
            return
        }
        val admission = resourceGuard.state(ResourceGuard.TaskKind.IMAGE)
        if (!admission.safe) {
            showProtectedMessage(admission.reason)
            return
        }

        libraryStore.autoRenameProjectFromQuestion(activeProjectId, prompt)
        libraryStore.recordSearch(activeProjectId, prompt, InteractionMode.IMAGE.name)
        val imageTurnId = libraryStore.beginConversationTurn(activeProjectId, prompt, "image.tinysd")
        val imageProjectName = libraryStore.projectName(activeProjectId)
        persistStore.appendEvent(activeProjectId, imageProjectName, "image_request", prompt)
        input.isEnabled = false
        attachButton.isEnabled = false
        activateResultMode()
        generatedImage.visibility = View.GONE
        answer.text = "Preparando geração de imagem…"
        showLiveStatus("▧", "Tiny-SD local", "Qwen permanece residente. Tiny-SD só inicia se a proteção de RAM/temperatura permitir.")

        generationJob = scope.launch {
            try {
                ready = false
                val quality = appPreferences.load().qualityProfile
                val file = imageGenerator.generate(activeProjectId, prompt, quality) { progress ->
                    runOnUiThread {
                        answer.text = progress
                        liveStatus.text = "▧  Tiny-SD local\\n$progress\\n\\n● Qwen continua residente nesta sessão."
                    }
                }
                val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
                generatedImage.setImageBitmap(bitmap)
                generatedImage.visibility = View.VISIBLE
                answer.text = "Imagem criada localmente no projeto “${libraryStore.projectName(activeProjectId)}”.\\n${file.name}"
                withContext(Dispatchers.IO) {
                    libraryStore.updateConversationTurn(imageTurnId, answer.text.toString())
                    persistStore.appendEvent(activeProjectId, imageProjectName, "image_created", file.absolutePath)
                    syncProjectPersistence(activeProjectId)
                    persistStore.clearCheckpoint(activeProjectId)
                }
                showLiveStatus("✓", "Tiny-SD • concluído", "${file.name} • rede Qwen não foi recarregada")
            } catch (t: Throwable) {
                answer.text = "Falha na geração de imagem: ${t.message ?: t.javaClass.simpleName}"
                withContext(Dispatchers.IO) {
                    libraryStore.updateConversationTurn(imageTurnId, answer.text.toString())
                    persistStore.checkpoint(activeProjectId, imageProjectName, "image.tinysd", prompt, answer.text.toString())
                }
                showLiveStatus("!", "Imagem interrompida / checkpoint salvo", t.message ?: t.javaClass.simpleName)
            } finally {
                engineModelLoaded = NeuralSession.isLoaded()
                ready = engineModelLoaded
                input.isEnabled = ready
                attachButton.isEnabled = !importing
                input.hint = if (ready) "Pergunte do seu jeito" else "IA indisponível"
                refreshModeWorkspace()
            }
        }
    }

'''
text = text[:start] + image_method + text[end:]

# Builder may have changed the shared system prompt; restore the conversational prompt before chat.
needle = '                engine.sendUserPrompt(prompt, predictionBudget(settings)).collect { token ->\n'
text = replace_once(
    text,
    needle,
    '                NeuralSession.applySystemPrompt(SYSTEM_PROMPT)\n' + needle,
    'restore chat system prompt',
)

# Activity lifecycle must not destroy the process-scoped inference engine.
text = text.replace(
    '        if (::engine.isInitialized) runCatching { engine.destroy() }\n',
    '        if (isFinishing) NeuralSession.clearDiagnosticMarkerIfFinishing(applicationContext)\n',
    1,
)

main_path.write_text(text, encoding='utf-8')


# Builder Studio: borrow the already-resident network and show a dedicated live build pipeline.
builder_path = Path('offlineai/src/main/java/com/alysson/offlineai/BuilderStudioActivity.kt')
builder = builder_path.read_text(encoding='utf-8')

builder = replace_once(
    builder,
    '''    private lateinit var status: TextView
    private lateinit var generateButton: TextView
''',
    '''    private lateinit var status: TextView
    private lateinit var processScroll: ScrollView
    private lateinit var processLog: TextView
    private lateinit var generateButton: TextView
''',
    'builder process fields',
)

old_buttons_to_source = '''        root.addView(buttons)

        val scroll = ScrollView(this)
        sourceField = EditText(this).apply {
'''
new_buttons_to_source = '''        root.addView(buttons)

        root.addView(TextView(this).apply {
            text = "Processo em tempo real"
            textSize = 13f
            setTextColor(Color.rgb(82, 63, 120))
            setPadding(dp(4), dp(6), dp(4), dp(4))
        })
        processLog = TextView(this).apply {
            text = "Aguardando geração/build…"
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.rgb(55, 48, 75))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextIsSelectable(true)
        }
        processScroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(245, 240, 255))
            addView(processLog, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(processScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(210)).apply {
            bottomMargin = dp(8)
        })

        val scroll = ScrollView(this)
        sourceField = EditText(this).apply {
'''
builder = replace_once(builder, old_buttons_to_source, new_buttons_to_source, 'builder live process panel')

# Add process helpers immediately before generate().
builder = replace_once(
    builder,
    '''    private fun generate() {
''',
    '''    private fun resetBuildProcess(message: String) {
        processLog.text = message
        processScroll.post { processScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendBuildProcess(message: String) {
        val current = processLog.text.toString()
        processLog.text = if (current.isBlank() || current == "Aguardando geração/build…") {
            message
        } else {
            (current + "\\n" + message).takeLast(16000)
        }
        processScroll.postOnAnimation { processScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun generate() {
''',
    'builder process helpers',
)

builder = replace_once(
    builder,
    '''        busy = true
        updateButtons()
        sourceField.setText("")
        status.text = "Preparando modelo especializado…"
''',
    '''        busy = true
        updateButtons()
        sourceField.setText("")
        resetBuildProcess("1/4 • Solicitação recebida\\n${request.take(240)}")
        status.text = "Preparando sessão neural residente…"
''',
    'builder generation start log',
)

old_prepare = '''    private suspend fun prepareEngine() {
        if (modelLoaded) return
        val model = findModel() ?: error("Abra a tela principal uma vez para instalar o modelo local, ou importe o Coder Pack.")
        val admission = guard.state(ResourceGuard.TaskKind.CODER)
        require(admission.safe) { admission.reason ?: "RAM/temperatura insuficientes." }
        engine = AiChat.getInferenceEngine(applicationContext)
        val initialized = engine.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error }
        if (initialized is InferenceEngine.State.Error) throw initialized.exception
        status.text = "Carregando ${if (packs.coderModel() != null) "Coder 1.5B" else "Qwen local"}…"
        engine.loadModel(model.absolutePath)
        engine.setSystemPrompt(SYSTEM_PROMPT)
        modelLoaded = true
    }
'''
new_prepare = '''    private suspend fun prepareEngine() {
        val admission = guard.state(ResourceGuard.TaskKind.CODER)
        require(admission.safe) { admission.reason ?: "RAM/temperatura insuficientes." }
        val model = NeuralSession.loadedModelFile()
            ?: findModel()
            ?: error("Abra a tela principal uma vez para instalar/carregar o modelo local.")
        engine = NeuralSession.acquire(applicationContext, model, SYSTEM_PROMPT) { message ->
            runOnUiThread {
                status.text = message
                appendBuildProcess("2/4 • $message")
            }
        }
        NeuralSession.applySystemPrompt(SYSTEM_PROMPT)
        modelLoaded = NeuralSession.isLoaded()
        appendBuildProcess("2/4 • ${NeuralSession.sessionDescription()}")
    }
'''
builder = replace_once(builder, old_prepare, new_prepare, 'builder shared neural session')

builder = builder.replace(
    '                status.text = "Gerando localmente • ${modelStatusText()} • visualização em tempo real"\n',
    '                status.text = "Gerando localmente • ${modelStatusText()} • visualização em tempo real"\n                appendBuildProcess("3/4 • Gerando interface/código token a token…")\n                var lastProgressChars = 0\n',
    1,
)
builder = builder.replace(
    '''                        generated.append(visible)
                        sourceField.append(visible)
''',
    '''                        generated.append(visible)
                        sourceField.append(visible)
                        if (generated.length - lastProgressChars >= 320) {
                            lastProgressChars = generated.length
                            appendBuildProcess("3/4 • ${generated.length} caracteres gerados…")
                        }
''',
    1,
)
builder = builder.replace(
    '''                sourceField.setText(normalized)
                status.text = "Pronto para revisar, visualizar e criar o arquivo."
''',
    '''                sourceField.setText(normalized)
                appendBuildProcess("4/4 • Estrutura validada • pronta para Prévia ou Criar arquivo")
                status.text = "Pronto para revisar, visualizar e criar o arquivo."
''',
    1,
)

# Every native packaging/signing stage is mirrored into the purple process panel.
builder = builder.replace(
    '{ p -> runOnUiThread { status.text = p } }',
    '{ p -> runOnUiThread { status.text = p; appendBuildProcess(p) } }',
)
builder = builder.replace(
    '''        busy = true
        updateButtons()
        val target = Target.entries[targetSpinner.selectedItemPosition]
''',
    '''        busy = true
        updateButtons()
        resetBuildProcess("Build iniciado • validação → montagem → assinatura → verificação")
        val target = Target.entries[targetSpinner.selectedItemPosition]
''',
    1,
)
builder = builder.replace(
    '''                status.text = "Artefato criado: ${artifact.name} • ${formatSize(artifact.length())}"
                exportArtifact(artifact)
''',
    '''                status.text = "Artefato criado: ${artifact.name} • ${formatSize(artifact.length())}"
                appendBuildProcess("✓ Artefato validado: ${artifact.name} • ${formatSize(artifact.length())}")
                exportArtifact(artifact)
''',
    1,
)

# Status must describe what actually happens now: Builder reuses the resident Qwen session.
start = builder.find('    private fun modelStatusText(): String =')
end = builder.find('    private fun updateButtons() {', start)
if start < 0 or end < 0:
    raise SystemExit('v5.2: modelStatusText boundaries missing')
builder = builder[:start] + '''    private fun modelStatusText(): String = when {
        NeuralSession.isLoaded() -> "Sessão compartilhada • ${NeuralSession.sessionDescription()}"
        packs.coderModel() != null -> "Coder Pack disponível • será usado somente se não houver sessão residente"
        else -> "Qwen local • sessão será inicializada uma única vez"
    }

''' + builder[end:]

builder = builder.replace(
    '        if (::engine.isInitialized) runCatching { engine.destroy() }\n',
    '        // NeuralSession owns the engine for the entire app process; do not destroy it here.\n',
    1,
)

builder_path.write_text(builder, encoding='utf-8')

print('Workspace v5.2 resident neural session + compact prompt + live build process patch applied')
