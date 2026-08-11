package com.alysson.offlineai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Spinner
import android.widget.TextView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.min

class MainActivity : Activity() {

    private enum class InteractionMode(val label: String) {
        TEXT("Texto"),
        IMAGE("Criar imagem")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var input: EditText
    private lateinit var answer: TextView
    private lateinit var generatedImage: ImageView
    private lateinit var resultScroll: ScrollView
    private lateinit var resultContainer: LinearLayout
    private lateinit var topSpacer: Space
    private lateinit var bottomSpacer: Space
    private lateinit var attachButton: TextView
    private lateinit var libraryStatus: TextView
    private lateinit var resourceStatus: TextView
    private lateinit var safetyStatus: TextView
    private lateinit var projectTitle: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var projectsContainer: LinearLayout
    private lateinit var qualitySpinner: Spinner
    private lateinit var modeSpinner: Spinner

    private lateinit var engine: InferenceEngine
    private lateinit var libraryStore: LibraryStore
    private lateinit var attachmentImporter: AttachmentImporter
    private lateinit var resourceMonitor: ResourceMonitor
    private lateinit var resourceGuard: ResourceGuard
    private lateinit var dialogueBrain: DialogueBrain
    private lateinit var appPreferences: AppPreferences
    private lateinit var imageGenerator: ImageGenerationManager

    private var lexicalMemory: LexicalMemory? = null
    private var localModelFile: File? = null
    private var activeProjectId = 1L
    private var ready = false
    private var engineModelLoaded = false
    private var generationJob: Job? = null
    private var resultMode = false
    private var importing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        appPreferences = AppPreferences(applicationContext)
        libraryStore = LibraryStore(applicationContext)
        activeProjectId = appPreferences.load().activeProjectId
        if (!libraryStore.projectExists(activeProjectId)) {
            activeProjectId = libraryStore.ensureDefaultProject()
            appPreferences.setActiveProjectId(activeProjectId)
        }

        attachmentImporter = AttachmentImporter(applicationContext, libraryStore)
        resourceMonitor = ResourceMonitor(applicationContext)
        resourceGuard = ResourceGuard(applicationContext)
        dialogueBrain = DialogueBrain()
        imageGenerator = ImageGenerationManager(applicationContext, resourceGuard)

        buildUi()
        refreshProjectUi()
        startResourceMonitor()
        prepareOfflineEngine()
    }

    private fun buildUi() {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
            setBackgroundColor(Color.WHITE)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val menuButton = smallAction("☰", "Abrir projetos") { drawer.visibility = View.VISIBLE }
        toolbar.addView(menuButton, LinearLayout.LayoutParams(dp(44), dp(44)))

        projectTitle = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.rgb(32, 33, 36))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            maxLines = 1
        }
        toolbar.addView(projectTitle, LinearLayout.LayoutParams(0, dp(44), 1f))

        val settingsButton = smallAction("⚙", "Personalização") { showPersonalizationDialog() }
        toolbar.addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))

        resourceStatus = TextView(this).apply {
            text = "CPU —   GPU —   RAM —"
            textSize = 12f
            setTextColor(Color.rgb(95, 99, 104))
            gravity = Gravity.END
        }
        content.addView(resourceStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        safetyStatus = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(95, 99, 104))
            gravity = Gravity.END
            setPadding(0, dp(2), 0, 0)
        }
        content.addView(safetyStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        topSpacer = Space(this)
        content.addView(topSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val selectorRow = LinearLayout(this).apply {
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

        val profileNote = TextView(this).apply {
            text = "Perfis locais do Qwen: Avançado, Intermediário e Rápido • sem nuvem"
            textSize = 11f
            setTextColor(Color.rgb(110, 110, 110))
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        content.addView(profileNote, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        attachButton = TextView(this).apply {
            text = "+"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(60, 64, 67))
            contentDescription = "Anexar PDF, imagem ou texto somente ao projeto atual"
            background = circleDrawable()
            elevation = dp(1).toFloat()
            setOnClickListener { openAttachmentPicker() }
        }
        searchRow.addView(attachButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })

        input = EditText(this).apply {
            isSingleLine = true
            textSize = 17f
            setTextColor(Color.rgb(32, 33, 36))
            setHintTextColor(Color.rgb(110, 110, 110))
            hint = "Preparando IA offline…"
            isEnabled = false
            setPadding(dp(20), 0, dp(20), 0)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = roundedFieldDrawable()
            elevation = dp(2).toFloat()
            setOnEditorActionListener { _, actionId, event ->
                val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enterPressed) {
                    submitCurrentMode()
                    true
                } else false
            }
        }
        searchRow.addView(input, LinearLayout.LayoutParams(0, dp(56), 1f))
        content.addView(searchRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        libraryStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(dp(60), dp(7), dp(4), 0)
        }
        content.addView(libraryStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        resultScroll = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = true
        }
        resultContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        generatedImage = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(18), dp(4), dp(8))
        }
        answer = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.rgb(32, 33, 36))
            setLineSpacing(0f, 1.18f)
            setPadding(dp(6), dp(16), dp(6), dp(32))
            setTextIsSelectable(true)
        }
        resultContainer.addView(generatedImage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        resultContainer.addView(answer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        resultScroll.addView(resultContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        content.addView(resultScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        bottomSpacer = Space(this)
        content.addView(bottomSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.25f))

        frame.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        buildDrawer(frame)
        setContentView(frame)
    }

    private fun buildDrawer(frame: FrameLayout) {
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
            elevation = dp(16).toFloat()
            visibility = View.GONE
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Projetos"
            textSize = 21f
            setTextColor(Color.rgb(32, 33, 36))
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        header.addView(smallAction("×", "Fechar projetos") { drawer.visibility = View.GONE }, LinearLayout.LayoutParams(dp(44), dp(44)))
        drawer.addView(header)

        val newProject = TextView(this).apply {
            text = "+ Novo projeto"
            textSize = 16f
            setTextColor(Color.rgb(26, 115, 232))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { createProjectDialog() }
        }
        drawer.addView(newProject, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        val rename = TextView(this).apply {
            text = "✎ Renomear projeto atual"
            textSize = 15f
            setTextColor(Color.rgb(60, 64, 67))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { renameProjectDialog(activeProjectId) }
        }
        drawer.addView(rename, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))

        val scroll = ScrollView(this)
        projectsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(projectsContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        drawer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val imagePack = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(60, 64, 67))
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { showImagePackDialog() }
        }
        imagePack.tag = "imagePackStatus"
        drawer.addView(imagePack, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        frame.addView(
            drawer,
            FrameLayout.LayoutParams(dp(320), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
        )
    }

    private fun refreshProjectUi() {
        val projectName = libraryStore.projectName(activeProjectId)
        projectTitle.text = projectName
        updateLibraryStatus()
        refreshProjectList()
        val imagePack = drawer.findViewWithTag<TextView>("imagePackStatus")
        imagePack?.text = "Gerador de imagens: ${imageGenerator.modelDescription()}\nToque para importar/validar o pacote local."
    }

    private fun refreshProjectList() {
        projectsContainer.removeAllViews()
        libraryStore.listProjects().forEach { project ->
            val selected = project.id == activeProjectId
            val item = TextView(this).apply {
                text = if (selected) "● ${project.name}" else project.name
                textSize = 16f
                setTextColor(if (selected) Color.rgb(26, 115, 232) else Color.rgb(32, 33, 36))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(8), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (selected) Color.rgb(240, 246, 255) else Color.WHITE)
                }
                setOnClickListener {
                    activeProjectId = project.id
                    appPreferences.setActiveProjectId(activeProjectId)
                    drawer.visibility = View.GONE
                    resultMode = false
                    resultScroll.visibility = View.GONE
                    bottomSpacer.visibility = View.VISIBLE
                    topSpacer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                    refreshProjectUi()
                }
                setOnLongClickListener {
                    renameProjectDialog(project.id)
                    true
                }
            }
            projectsContainer.addView(item, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(4) })
        }
    }

    private fun createProjectDialog() {
        val field = EditText(this).apply { hint = "Nome do projeto"; isSingleLine = true }
        AlertDialog.Builder(this)
            .setTitle("Novo projeto")
            .setView(field)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Criar") { _, _ ->
                activeProjectId = libraryStore.createProject(field.text.toString())
                appPreferences.setActiveProjectId(activeProjectId)
                drawer.visibility = View.GONE
                refreshProjectUi()
            }
            .show()
    }

    private fun renameProjectDialog(projectId: Long) {
        val field = EditText(this).apply {
            setText(libraryStore.projectName(projectId))
            selectAll()
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Renomear projeto")
            .setView(field)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                libraryStore.renameProject(projectId, field.text.toString())
                refreshProjectUi()
            }
            .show()
    }

    private fun showPersonalizationDialog() {
        val current = appPreferences.load()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        box.addView(TextView(this).apply { text = "Seu nome"; textSize = 13f })
        val name = EditText(this).apply {
            setText(current.userName)
            hint = "Como a IA pode te chamar"
            isSingleLine = true
        }
        box.addView(name)

        box.addView(TextView(this).apply { text = "Extensão da resposta"; textSize = 13f; setPadding(0, dp(14), 0, 0) })
        val length = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                AppPreferences.AnswerLength.entries.map { it.label }
            )
            setSelection(AppPreferences.AnswerLength.entries.indexOf(current.answerLength))
        }
        box.addView(length)

        val specific = EditText(this).apply {
            setText(current.specificInstruction)
            hint = "Opcional: formato, tom ou foco específico"
            minLines = 2
            maxLines = 4
        }
        box.addView(specific)

        box.addView(TextView(this).apply {
            text = "Avançado, Intermediário e Rápido são perfis de execução do Qwen local. Eles não são GPT-5.6, GPT-5.5 nem o3 e não usam a internet."
            textSize = 12f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(0, dp(14), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("Personalização")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                appPreferences.setUserName(name.text.toString())
                appPreferences.setAnswerLength(AppPreferences.AnswerLength.entries[length.selectedItemPosition])
                appPreferences.setSpecificInstruction(specific.text.toString())
            }
            .show()
    }

    private fun showImagePackDialog() {
        val message = if (imageGenerator.hasModel()) {
            "O pacote Tiny-SD Q4_K está instalado. As imagens são geradas em 512×512, localmente e com proteção de RAM/temperatura."
        } else {
            "Para criar imagens sem internet, importe o arquivo ${ImageGenerationManager.MODEL_FILE}. O pacote é separado do APK para manter a instalação abaixo do limite de tamanho."
        }
        AlertDialog.Builder(this)
            .setTitle("Geração local de imagens")
            .setMessage(message)
            .setNegativeButton("Fechar", null)
            .setPositiveButton("Importar pacote") { _, _ -> openImageModelPicker() }
            .show()
    }

    private fun openAttachmentPicker() {
        if (importing || generationJob?.isActive == true) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/pdf", "text/plain", "text/markdown", "image/jpeg", "image/png", "image/webp")
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_ATTACH)
    }

    private fun openImageModelPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMAGE_MODEL)
    }

    @Deprecated("Deprecated in Android API, retained for minSdk-compatible document picker handling")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        if (requestCode == REQUEST_IMAGE_MODEL) {
            val uri = data.data ?: return
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            importImageModel(uri)
            return
        }
        if (requestCode != REQUEST_ATTACH) return

        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
        data.data?.let { uris += it }
        val unique = uris.distinct()
        if (unique.isEmpty()) return

        unique.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        importAttachments(unique)
    }

    private fun importImageModel(uri: Uri) {
        if (importing) return
        importing = true
        activateResultMode()
        generatedImage.visibility = View.GONE
        answer.text = "Validando pacote de geração de imagens…"
        scope.launch {
            try {
                val result = imageGenerator.importModel(uri) { progress ->
                    runOnUiThread { answer.text = progress }
                }
                answer.text = "Pacote de imagens instalado e verificado.\nSHA-256: ${result.sha256}\nAgora selecione “Criar imagem” na tela principal."
            } catch (t: Throwable) {
                answer.text = "Falha ao importar pacote de imagens: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                importing = false
                refreshProjectUi()
            }
        }
    }

    private fun importAttachments(uris: List<Uri>) {
        if (importing) return
        importing = true
        attachButton.isEnabled = false
        attachButton.alpha = 0.45f
        activateResultMode()
        generatedImage.visibility = View.GONE
        answer.text = "Preparando biblioteca do projeto…"

        scope.launch {
            val messages = mutableListOf<String>()
            try {
                uris.forEachIndexed { index, uri ->
                    val result = withContext(Dispatchers.IO) {
                        attachmentImporter.import(uri, activeProjectId) { progress ->
                            runOnUiThread { libraryStatus.text = "${index + 1}/${uris.size} • $progress" }
                        }
                    }
                    messages += buildString {
                        append("✓ ${result.name}: ${result.sections} seção(ões), ${formatCharacters(result.characters)}")
                        if (result.note.isNotBlank()) append(" — ${result.note}")
                    }
                }
                updateLibraryStatus()
                answer.text = buildString {
                    appendLine("Arquivos adicionados somente ao projeto “${libraryStore.projectName(activeProjectId)}”:")
                    append(messages.joinToString("\n"))
                }
            } catch (t: Throwable) {
                updateLibraryStatus()
                answer.text = "Falha ao importar: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                importing = false
                attachButton.isEnabled = true
                attachButton.alpha = 1f
            }
        }
    }

    private fun updateLibraryStatus() {
        val stats = libraryStore.stats(activeProjectId)
        libraryStatus.text = if (stats.documents == 0) {
            "Projeto sem arquivos • toque em + para anexar"
        } else {
            "${stats.documents} arquivo(s) • ${stats.chunks} trecho(s) • ${formatCharacters(stats.characters)}"
        }
    }

    private fun startResourceMonitor() {
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                val sample = resourceMonitor.sample()
                val guard = resourceGuard.state(ResourceGuard.TaskKind.CHAT)
                withContext(Dispatchers.Main) {
                    val cpu = sample.cpuPercent?.let { "$it%" } ?: "—"
                    val gpu = sample.gpuPercent?.let { "$it%" } ?: "N/D"
                    resourceStatus.text = "CPU $cpu   GPU $gpu   RAM ${sample.ramPercent}%"
                    safetyStatus.text = resourceGuard.shortStatus()
                    safetyStatus.setTextColor(if (guard.safe) Color.rgb(95, 99, 104) else Color.rgb(176, 0, 32))
                }
                delay(1200)
            }
        }
    }

    private fun prepareOfflineEngine() {
        scope.launch {
            try {
                input.hint = "Validando modelo local…"
                localModelFile = withContext(Dispatchers.IO) { installVerifiedModel() }

                input.hint = "Indexando português local…"
                lexicalMemory = withContext(Dispatchers.IO) { LexicalMemory.open(applicationContext) }

                input.hint = "Inicializando CPU…"
                engine = AiChat.getInferenceEngine(applicationContext)
                val initializedState = engine.state.first {
                    it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                }
                if (initializedState is InferenceEngine.State.Error) throw initializedState.exception

                loadChatModel()
            } catch (t: Throwable) {
                showFatalError(t)
            }
        }
    }

    private suspend fun loadChatModel() {
        val model = localModelFile ?: error("Modelo local não preparado.")
        input.hint = "Carregando rede neural…"
        engine.loadModel(model.absolutePath)
        engine.setSystemPrompt(SYSTEM_PROMPT)
        engineModelLoaded = true
        ready = true
        input.isEnabled = true
        input.hint = "Pergunte do seu jeito"
    }

    private fun submitCurrentMode() {
        val prompt = input.text.toString().trim()
        if (prompt.isEmpty() || importing || generationJob?.isActive == true) return
        when (InteractionMode.entries[modeSpinner.selectedItemPosition]) {
            InteractionMode.TEXT -> submitTextQuestion(prompt)
            InteractionMode.IMAGE -> submitImagePrompt(prompt)
        }
    }

    private fun submitTextQuestion(question: String) {
        if (!ready || !engineModelLoaded) return
        val admission = resourceGuard.state(ResourceGuard.TaskKind.CHAT)
        if (!admission.safe) {
            showProtectedMessage(admission.reason)
            return
        }

        input.text.clear()
        input.isEnabled = false
        attachButton.isEnabled = false
        input.hint = "Entendendo e respondendo…"
        activateResultMode()
        generatedImage.visibility = View.GONE
        answer.text = ""

        generationJob = scope.launch {
            try {
                val settings = appPreferences.load()
                val projectName = libraryStore.projectName(activeProjectId)
                val contexts = withContext(Dispatchers.IO) {
                    Pair(
                        lexicalMemory?.retrieve(question).orEmpty(),
                        libraryStore.retrieve(question, activeProjectId, settings.qualityProfile.maxLibrarySnippets)
                    )
                }
                val prompt = dialogueBrain.buildPrompt(
                    projectId = activeProjectId,
                    projectName = projectName,
                    originalQuestion = question,
                    lexicalContext = contexts.first,
                    libraryContext = contexts.second,
                    userName = settings.userName,
                    answerLength = settings.answerLength,
                    qualityProfile = settings.qualityProfile,
                    specificInstruction = settings.specificInstruction,
                )

                val generated = StringBuilder()
                engine.sendUserPrompt(prompt, predictionBudget(settings)).collect { token ->
                    val liveGuard = resourceGuard.state(ResourceGuard.TaskKind.CHAT)
                    if (!liveGuard.safe) throw IllegalStateException(liveGuard.reason ?: "Geração interrompida para proteger o aparelho.")
                    generated.append(token)
                    answer.append(token)
                    resultScroll.post { resultScroll.fullScroll(View.FOCUS_DOWN) }
                }
                if (generated.isNotBlank()) dialogueBrain.recordTurn(activeProjectId, question, generated.toString())
            } catch (t: Throwable) {
                if (answer.text.isNotEmpty()) answer.append("\n\n")
                answer.append("Interrompido: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                input.isEnabled = ready
                attachButton.isEnabled = !importing
                input.hint = if (ready) "Pergunte do seu jeito" else "IA indisponível"
            }
        }
    }

    private fun submitImagePrompt(prompt: String) {
        if (!imageGenerator.hasModel()) {
            showImagePackDialog()
            return
        }
        val admission = resourceGuard.state(ResourceGuard.TaskKind.IMAGE)
        if (!admission.safe) {
            showProtectedMessage(admission.reason)
            return
        }

        input.text.clear()
        input.isEnabled = false
        attachButton.isEnabled = false
        activateResultMode()
        generatedImage.visibility = View.GONE
        answer.text = "Preparando geração de imagem…"

        generationJob = scope.launch {
            try {
                ready = false
                if (engineModelLoaded) {
                    answer.text = "Liberando memória da IA de texto antes da imagem…"
                    withContext(Dispatchers.IO) { engine.cleanUp() }
                    engineModelLoaded = false
                }

                val quality = appPreferences.load().qualityProfile
                val file = imageGenerator.generate(activeProjectId, prompt, quality) { progress ->
                    runOnUiThread { answer.text = progress }
                }
                val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
                generatedImage.setImageBitmap(bitmap)
                generatedImage.visibility = View.VISIBLE
                answer.text = "Imagem criada localmente no projeto “${libraryStore.projectName(activeProjectId)}”.\n${file.name}"
            } catch (t: Throwable) {
                answer.text = "Falha na geração de imagem: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                try {
                    if (!engineModelLoaded && ::engine.isInitialized) {
                        answer.append("\n\nRecarregando IA de texto…")
                        loadChatModel()
                    }
                } catch (t: Throwable) {
                    ready = false
                    answer.append("\nNão foi possível recarregar a IA de texto: ${t.message ?: t.javaClass.simpleName}")
                }
                input.isEnabled = ready
                attachButton.isEnabled = !importing
                input.hint = if (ready) "Pergunte do seu jeito" else "IA indisponível"
            }
        }
    }

    private fun predictionBudget(settings: AppPreferences.Settings): Int {
        val desired = when (settings.answerLength) {
            AppPreferences.AnswerLength.VERY_LONG -> 1800
            AppPreferences.AnswerLength.MEDIUM -> 900
            AppPreferences.AnswerLength.SUMMARY -> 420
            AppPreferences.AnswerLength.SPECIFIC -> 760
        }
        return min(desired, settings.qualityProfile.maxPredictTokens)
    }

    private fun showProtectedMessage(reason: String?) {
        activateResultMode()
        generatedImage.visibility = View.GONE
        answer.text = buildString {
            appendLine("A proteção de recursos bloqueou esta tarefa para evitar travamento.")
            if (!reason.isNullOrBlank()) append(reason)
        }
    }

    private fun activateResultMode() {
        if (resultMode) return
        resultMode = true
        topSpacer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
        bottomSpacer.visibility = View.GONE
        resultScroll.visibility = View.VISIBLE
    }

    private fun showFatalError(t: Throwable) {
        ready = false
        input.isEnabled = false
        input.hint = "IA offline indisponível"
        activateResultMode()
        generatedImage.visibility = View.GONE
        answer.text = "Não foi possível iniciar o motor local.\n\n${t.message ?: t.javaClass.simpleName}"
    }

    private fun installVerifiedModel(): File {
        val modelDir = File(filesDir, "models").apply { mkdirs() }
        val target = File(modelDir, MODEL_FILE)
        val marker = File(modelDir, "$MODEL_FILE.sha256")

        if (target.exists() && marker.exists() && marker.readText().trim() == MODEL_SHA256) return target

        val tmp = File(modelDir, "$MODEL_FILE.tmp")
        if (tmp.exists()) tmp.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)

        assets.open(MODEL_ASSET).use { inputStream ->
            tmp.outputStream().buffered(1024 * 1024).use { output ->
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == MODEL_SHA256) {
            tmp.delete()
            "Integridade do modelo inválida: $actual"
        }

        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "Não foi possível instalar o modelo neural local." }
        marker.writeText(MODEL_SHA256)
        return target
    }

    private fun smallAction(label: String, description: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 24f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(60, 64, 67))
        contentDescription = description
        setOnClickListener { action() }
    }

    private fun roundedFieldDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(28).toFloat()
        setColor(Color.WHITE)
        setStroke(dp(1), Color.rgb(218, 220, 224))
    }

    private fun circleDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
        setStroke(dp(1), Color.rgb(218, 220, 224))
    }

    private fun formatCharacters(value: Long): String = when {
        value >= 1_000_000 -> String.format("%.1f M caracteres", value / 1_000_000.0)
        value >= 1_000 -> String.format("%.1f mil caracteres", value / 1_000.0)
        else -> "$value caracteres"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        generationJob?.cancel()
        lexicalMemory?.close()
        if (::attachmentImporter.isInitialized) attachmentImporter.close()
        if (::libraryStore.isInitialized) libraryStore.close()
        if (::engine.isInitialized) runCatching { engine.destroy() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_ATTACH = 4011
        private const val REQUEST_IMAGE_MODEL = 4012
        private const val MODEL_ASSET = "model.gguf"
        private const val MODEL_FILE = "Qwen3.5-0.8B-Q4_K_M.gguf"
        private const val MODEL_SHA256 = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517"

        private val SYSTEM_PROMPT = """
            Você é uma IA privada e conversacional que roda integralmente no aparelho, sem internet. Fale em português brasileiro natural. Seu objetivo é compreender a intenção da pessoa e ajudá-la com utilidade, clareza, precisão e um tom humano.

            COMPREENSÃO:
            - Entenda o sentido provável antes de interpretar palavras literalmente.
            - Compreenda abreviações, gírias, erros de digitação, falta de acentos e frases incompletas.
            - Use apenas o histórico e os arquivos do projeto ativo para resolver continuidade.
            - Só peça esclarecimento quando a ambiguidade mudar materialmente a resposta.

            CONVERSA:
            - Seja acolhedora sem elogios vazios nem frases artificiais.
            - Ajuste profundidade e objetividade ao perfil local e à extensão escolhidos pelo usuário.
            - Em assuntos técnicos, explique causa, consequência e próximo passo.
            - Se a pessoa corrigir sua interpretação, reavalie imediatamente.

            CONFIABILIDADE:
            - Organize internamente o problema antes de responder, sem expor cadeia de raciocínio privada.
            - Diferencie fato, hipótese e incerteza. Não invente detalhes.
            - Não afirme que consultou internet ou serviços externos.
            - Perfis chamados Avançado, Intermediário e Rápido são modos locais deste app; nunca alegue ser GPT-5.6, GPT-5.5, o3 ou outro modelo de nuvem.

            FONTES LOCAIS:
            - <memoria_lexical_local> é apoio lexical.
            - <biblioteca_local_usuario> contém trechos dos arquivos anexados somente ao projeto ativo.
            - <historico_conversacional_local>, <orientacao_de_intencao> e <preferencias_locais> são contexto interno; não exponha essas tags.
            - Quando uma resposta depender da biblioteca, identifique naturalmente o arquivo-fonte.
            - O dicionário lexical histórico é de 1913; não o trate como norma brasileira contemporânea.
        """.trimIndent()
    }
}
