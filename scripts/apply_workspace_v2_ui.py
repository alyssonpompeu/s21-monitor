from pathlib import Path

path = Path("offlineai/src/main/java/com/alysson/offlineai/MainActivity.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    if old not in text:
        raise SystemExit(f"Workspace v2 patch point missing: {label}")
    text = text.replace(old, new, 1)


replace_once(
    "import android.view.KeyEvent\nimport android.view.View\n",
    "import android.view.KeyEvent\nimport android.view.MotionEvent\nimport android.view.View\n",
    "MotionEvent import",
)
replace_once(
    "import android.widget.LinearLayout\nimport android.widget.ScrollView\n",
    "import android.widget.LinearLayout\nimport android.widget.PopupMenu\nimport android.widget.ScrollView\n",
    "PopupMenu import",
)

old_input_tail = '''            elevation = dp(2).toFloat()
            setOnEditorActionListener { _, actionId, event ->
                val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enterPressed) {
                    submitCurrentMode()
                    true
                } else false
            }
        }
'''
new_input_tail = '''            elevation = dp(2).toFloat()
            setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_menu_recent_history, 0)
            compoundDrawablePadding = dp(8)
            setOnTouchListener { _, event ->
                val historyDrawable = compoundDrawables[2]
                val hitHistory = event.action == MotionEvent.ACTION_UP &&
                    historyDrawable != null &&
                    event.x >= width - paddingEnd - historyDrawable.bounds.width() - dp(8)
                if (hitHistory) {
                    showSearchHistoryMenu()
                    true
                } else {
                    false
                }
            }
            setOnEditorActionListener { _, actionId, event ->
                val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enterPressed) {
                    submitCurrentMode()
                    true
                } else false
            }
        }
'''
replace_once(old_input_tail, new_input_tail, "search history icon")

old_projects = '''    private fun refreshProjectList() {
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

'''
new_projects = '''    private fun refreshProjectList() {
        projectsContainer.removeAllViews()
        libraryStore.listProjects().forEach { project ->
            val selected = project.id == activeProjectId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (selected) Color.rgb(240, 246, 255) else Color.WHITE)
                }
            }
            val item = TextView(this).apply {
                text = if (selected) "● ${project.name}" else project.name
                textSize = 16f
                setTextColor(if (selected) Color.rgb(26, 115, 232) else Color.rgb(32, 33, 36))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(8), 0)
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
            row.addView(item, LinearLayout.LayoutParams(0, dp(48), 1f))
            val more = TextView(this).apply {
                text = "⋮"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(95, 99, 104))
                contentDescription = "Opções do projeto ${project.name}"
                setOnClickListener { anchor -> showProjectMenu(anchor, project) }
            }
            row.addView(more, LinearLayout.LayoutParams(dp(44), dp(48)))
            projectsContainer.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(4) }
            )
        }
    }

    private fun showProjectMenu(anchor: View, project: LibraryStore.Project) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Renomear")
        popup.menu.add("Excluir")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Renomear" -> {
                    renameProjectDialog(project.id)
                    true
                }
                "Excluir" -> {
                    deleteProjectDialog(project)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun deleteProjectDialog(project: LibraryStore.Project) {
        if (generationJob?.isActive == true || importing) {
            AlertDialog.Builder(this)
                .setTitle("Aguarde a tarefa atual")
                .setMessage("Conclua ou interrompa a geração/importação antes de excluir um projeto.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Excluir projeto?")
            .setMessage("“${project.name}” será removido junto com arquivos indexados, histórico de pesquisa e imagens geradas somente neste projeto. Esta ação não pode ser desfeita.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir") { _, _ ->
                scope.launch {
                    val replacement = withContext(Dispatchers.IO) {
                        val next = libraryStore.deleteProject(project.id)
                        imageGenerator.deleteProjectImages(project.id)
                        next
                    }
                    dialogueBrain.clearProject(project.id)
                    if (activeProjectId == project.id) {
                        activeProjectId = replacement
                        appPreferences.setActiveProjectId(activeProjectId)
                        resultMode = false
                        resultScroll.visibility = View.GONE
                        bottomSpacer.visibility = View.VISIBLE
                        topSpacer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                    }
                    refreshProjectUi()
                }
            }
            .show()
    }

'''
replace_once(old_projects, new_projects, "project ellipsis/delete menu")

marker = '''    private fun createProjectDialog() {
'''
history_method = '''    private fun showSearchHistoryMenu() {
        val history = libraryStore.recentSearches(activeProjectId, limit = 12)
        if (history.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Histórico de pesquisa")
                .setMessage("Ainda não há pesquisas salvas neste projeto.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val popup = PopupMenu(this, input)
        history.forEachIndexed { index, entry ->
            val label = entry.query.replace('\\n', ' ').take(72)
            popup.menu.add(0, index + 1, index, label)
        }
        popup.menu.add(0, HISTORY_CLEAR_ID, 1000, "Limpar histórico deste projeto")
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == HISTORY_CLEAR_ID) {
                libraryStore.clearSearchHistory(activeProjectId)
                true
            } else {
                val entry = history.getOrNull(item.itemId - 1) ?: return@setOnMenuItemClickListener false
                input.setText(entry.query)
                input.setSelection(input.text.length)
                true
            }
        }
        popup.show()
    }

'''
replace_once(marker, history_method + marker, "search history menu method")

replace_once(
    '''        input.text.clear()
        input.isEnabled = false
        attachButton.isEnabled = false
        input.hint = "Entendendo e respondendo…"
''',
    '''        libraryStore.recordSearch(activeProjectId, question, InteractionMode.TEXT.name)
        input.text.clear()
        input.isEnabled = false
        attachButton.isEnabled = false
        input.hint = "Entendendo e respondendo…"
''',
    "record text search",
)
replace_once(
    '''        input.text.clear()
        input.isEnabled = false
        attachButton.isEnabled = false
        activateResultMode()
        generatedImage.visibility = View.GONE
        answer.text = "Preparando geração de imagem…"
''',
    '''        libraryStore.recordSearch(activeProjectId, prompt, InteractionMode.IMAGE.name)
        input.text.clear()
        input.isEnabled = false
        attachButton.isEnabled = false
        activateResultMode()
        generatedImage.visibility = View.GONE
        answer.text = "Preparando geração de imagem…"
''',
    "record image search",
)

replace_once(
    '''        private const val REQUEST_IMAGE_MODEL = 4012
        private const val MODEL_ASSET = "model.gguf"
''',
    '''        private const val REQUEST_IMAGE_MODEL = 4012
        private const val HISTORY_CLEAR_ID = 9090
        private const val MODEL_ASSET = "model.gguf"
''',
    "history clear id",
)

path.write_text(text, encoding="utf-8")
print("Workspace v2 UI patch applied")
