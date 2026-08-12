#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'v5.2.1 patch point missing: {label}')
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# LibraryStore: hidden global neural-library project + common retrieval.
# -----------------------------------------------------------------------------
library_path = Path('offlineai/src/main/java/com/alysson/offlineai/LibraryStore.kt')
library = library_path.read_text(encoding='utf-8')

library = replace_once(
    library,
    '''        val id = db.rawQuery("SELECT id FROM projects ORDER BY id LIMIT 1", null).use { cursor ->
''',
    '''        val id = db.rawQuery(
            "SELECT id FROM projects WHERE name != ? ORDER BY id LIMIT 1",
            arrayOf(NEURAL_LIBRARY_PROJECT),
        ).use { cursor ->
''',
    'default project excludes neural library',
)

library = replace_once(
    library,
    '''        readableDatabase.rawQuery(
            "SELECT id, name, created_at FROM projects ORDER BY updated_at DESC, id DESC",
            null
        ).use { cursor ->
''',
    '''        readableDatabase.rawQuery(
            "SELECT id, name, created_at FROM projects WHERE name != ? ORDER BY updated_at DESC, id DESC",
            arrayOf(NEURAL_LIBRARY_PROJECT),
        ).use { cursor ->
''',
    'hide neural library from project drawer',
)

library = replace_once(
    library,
    '''            var replacement = db.rawQuery(
                "SELECT id FROM projects ORDER BY updated_at DESC, id DESC LIMIT 1",
                null
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }
''',
    '''            var replacement = db.rawQuery(
                "SELECT id FROM projects WHERE name != ? ORDER BY updated_at DESC, id DESC LIMIT 1",
                arrayOf(NEURAL_LIBRARY_PROJECT),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }
''',
    'delete project replacement excludes neural library',
)

insert_before = '''    fun createProject(name: String): Long {
'''
methods = r'''    fun ensureNeuralLibraryProject(): Long {
        val db = writableDatabase
        val existing = db.rawQuery(
            "SELECT id FROM projects WHERE name = ? LIMIT 1",
            arrayOf(NEURAL_LIBRARY_PROJECT),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }
        if (existing > 0L) return existing

        val now = System.currentTimeMillis()
        return db.insertOrThrow(
            "projects",
            null,
            ContentValues().apply {
                put("name", NEURAL_LIBRARY_PROJECT)
                put("created_at", now)
                put("updated_at", now)
            },
        )
    }

    private fun legacyCommonProjectId(): Long? = readableDatabase.rawQuery(
        "SELECT id FROM projects WHERE id = 1 AND name != ? LIMIT 1",
        arrayOf(NEURAL_LIBRARY_PROJECT),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    fun neuralLibraryStats(): Stats {
        val global = stats(ensureNeuralLibraryProject())
        val legacyId = legacyCommonProjectId()
        if (legacyId == null) return global
        val legacy = stats(legacyId)
        return Stats(
            documents = global.documents + legacy.documents,
            chunks = global.chunks + legacy.chunks,
            characters = global.characters + legacy.characters,
        )
    }

    fun listNeuralLibraryDocuments(): List<DocumentSource> {
        val globalId = ensureNeuralLibraryProject()
        val result = ArrayList<DocumentSource>()
        result += listDocuments(globalId)
        legacyCommonProjectId()?.takeIf { it != globalId }?.let { result += listDocuments(it) }
        return result.distinctBy { "${it.sha256}|${it.name}|${it.sizeBytes}" }
    }

    fun retrieveNeuralLibrary(query: String, maxSnippets: Int = 7, excludeProjectId: Long? = null): String {
        val tokens = LexicalMemory.normalize(query)
            .split(' ')
            .asSequence()
            .filter { it.length >= 3 }
            .distinct()
            .take(8)
            .toList()
        if (tokens.isEmpty()) return ""

        val ids = buildList {
            add(ensureNeuralLibraryProject())
            legacyCommonProjectId()?.let { add(it) }
        }.distinct().filter { it != excludeProjectId }
        if (ids.isEmpty()) return ""

        val match = tokens.joinToString(" OR ") { "${escapeFts(it)}*" }
        val snippets = mutableListOf<Triple<String, String, String>>()
        val perCollection = maxOf(1, maxSnippets.coerceIn(1, 12) / ids.size)
        ids.forEach { projectId ->
            readableDatabase.rawQuery(
                "SELECT source, location, text FROM project_chunks_fts " +
                    "WHERE project_id = ? AND project_chunks_fts MATCH ? LIMIT ?",
                arrayOf(projectId.toString(), match, perCollection.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    snippets += Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                }
            }
        }
        val unique = snippets.distinctBy { "${it.first}|${it.second}|${it.third.take(180)}" }
            .take(maxSnippets.coerceIn(1, 12))
        if (unique.isEmpty()) return ""

        return buildString {
            appendLine("<biblioteca_neural_global>")
            appendLine("Fontes comuns adicionadas na área Plugins/Biblioteca Neural. Inclui também as fontes legadas do primeiro projeto Geral, quando aplicável. Use-as em qualquer chat somente quando forem relevantes à pergunta.")
            unique.forEach { (source, location, body) ->
                append("[Fonte comum: ")
                append(source)
                if (location.isNotBlank()) {
                    append(" — ")
                    append(location)
                }
                appendLine("]")
                appendLine(body.take(1200))
            }
            append("</biblioteca_neural_global>")
        }
    }

'''
library = replace_once(library, insert_before, methods + insert_before, 'neural library methods')

library = replace_once(
    library,
    '''        private const val DB_NAME = "user_library_v1.db"
''',
    '''        const val NEURAL_LIBRARY_PROJECT = "__neural_library_global__"
        private const val DB_NAME = "user_library_v1.db"
''',
    'neural library constant',
)

library_path.write_text(library, encoding='utf-8')


# -----------------------------------------------------------------------------
# MainActivity: every normal Qwen question gets project sources + neural library.
# -----------------------------------------------------------------------------
main_path = Path('offlineai/src/main/java/com/alysson/offlineai/MainActivity.kt')
main = main_path.read_text(encoding='utf-8')

main = replace_once(
    main,
    '''                val contexts = withContext(Dispatchers.IO) {
                    Pair(
                        lexicalMemory?.retrieve(question).orEmpty(),
                        libraryStore.retrieve(question, activeProjectId, settings.qualityProfile.maxLibrarySnippets)
                    )
                }
''',
    '''                val contexts = withContext(Dispatchers.IO) {
                    Triple(
                        lexicalMemory?.retrieve(question).orEmpty(),
                        libraryStore.retrieve(question, activeProjectId, settings.qualityProfile.maxLibrarySnippets),
                        libraryStore.retrieveNeuralLibrary(
                            question,
                            settings.qualityProfile.maxLibrarySnippets,
                            excludeProjectId = activeProjectId,
                        ),
                    )
                }
''',
    'main global library retrieval',
)

main = replace_once(
    main,
    '''                    lexicalContext = contexts.first,
                    libraryContext = contexts.second,
''',
    '''                    lexicalContext = contexts.first,
                    libraryContext = listOf(contexts.third, contexts.second)
                        .filter { it.isNotBlank() }
                        .joinToString("\\n\\n"),
''',
    'merge global and project library contexts',
)

main = replace_once(
    main,
    '''        val sourceCount = libraryStore.stats(activeProjectId).documents
        showLiveStatus("✦", "Qwen local • Alta qualidade", "$sourceCount fonte(s) do projeto • resposta em tempo real")
''',
    '''        val sourceCount = libraryStore.stats(activeProjectId).documents
        val commonCount = libraryStore.neuralLibraryStats().documents
        showLiveStatus(
            "✦",
            "Qwen local • Alta qualidade",
            "$sourceCount fonte(s) do projeto • $commonCount fonte(s) da Biblioteca Neural • resposta em tempo real",
        )
''',
    'show neural library source count',
)

main_path.write_text(main, encoding='utf-8')


# -----------------------------------------------------------------------------
# PluginManager: Biblioteca Neural lives in the same environment as plugins.
# -----------------------------------------------------------------------------
plugin_path = Path('offlineai/src/main/java/com/alysson/offlineai/PluginManagerActivity.kt')
plugin = plugin_path.read_text(encoding='utf-8')

plugin = replace_once(
    plugin,
    '''    private lateinit var imageGenerator: ImageGenerationManager
    private lateinit var list: LinearLayout
''',
    '''    private lateinit var imageGenerator: ImageGenerationManager
    private lateinit var libraryStore: LibraryStore
    private lateinit var libraryImporter: AttachmentImporter
    private lateinit var list: LinearLayout
''',
    'plugin neural library fields',
)

plugin = replace_once(
    plugin,
    '''        imageGenerator = ImageGenerationManager(applicationContext, ResourceGuard(applicationContext))
        buildUi()
''',
    '''        imageGenerator = ImageGenerationManager(applicationContext, ResourceGuard(applicationContext))
        libraryStore = LibraryStore(applicationContext)
        libraryStore.ensureNeuralLibraryProject()
        libraryImporter = AttachmentImporter(applicationContext, libraryStore)
        buildUi()
''',
    'plugin neural library init',
)

plugin = replace_once(
    plugin,
    '''        addImagePluginCard()
        installed.forEach { pack -> addPackCard(pack) }
''',
    '''        addNeuralLibraryCard()
        addImagePluginCard()
        installed.forEach { pack -> addPackCard(pack) }
''',
    'neural library card in plugin list',
)

insert_before = '''    private fun addImagePluginCard() {
'''
plugin_methods = r'''    private fun addNeuralLibraryCard() {
        val stats = libraryStore.neuralLibraryStats()
        addCard(
            title = "Biblioteca Neural",
            subtitle = "knowledge-library • ${stats.documents} fonte(s) • ${formatCharacters(stats.characters)}",
            description = "Livros, PDFs, imagens, textos, ZIP/APK e outros arquivos usados como fonte comum pelo Qwen em qualquer chat. As fontes antigas do primeiro projeto Geral também continuam disponíveis como biblioteca legada.",
            onMenu = { neuralLibraryMenu() },
        )
    }

    private fun neuralLibraryMenu() {
        val docs = libraryStore.listNeuralLibraryDocuments()
        AlertDialog.Builder(this)
            .setTitle("Biblioteca Neural")
            .setItems(arrayOf("Adicionar livros / fontes", "Ver fontes (${docs.size})")) { _, which ->
                when (which) {
                    0 -> openNeuralLibraryPicker()
                    else -> {
                        val body = if (docs.isEmpty()) {
                            "Nenhuma fonte comum ainda. Adicione livros, PDFs, imagens, ZIP, APK, textos ou outros documentos."
                        } else {
                            buildString {
                                appendLine("Fontes disponíveis para pesquisas comuns:")
                                appendLine()
                                docs.take(40).forEach { doc ->
                                    append("• ${doc.name}")
                                    if (doc.sizeBytes > 0L) append(" • ${formatSize(doc.sizeBytes)}")
                                    appendLine()
                                }
                                if (docs.size > 40) appendLine("… e mais ${docs.size - 40}")
                            }
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Fontes da Biblioteca Neural")
                            .setMessage(body)
                            .setNegativeButton("Fechar", null)
                            .setPositiveButton("Adicionar mais") { _, _ -> openNeuralLibraryPicker() }
                            .show()
                    }
                }
            }
            .show()
    }

    private fun openNeuralLibraryPicker() {
        if (importing) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_NEURAL_LIBRARY)
    }

    private fun importNeuralLibrary(uris: List<Uri>) {
        if (importing || uris.isEmpty()) return
        importing = true
        val libraryProjectId = libraryStore.ensureNeuralLibraryProject()
        status.text = "Preparando Biblioteca Neural…"
        scope.launch {
            var imported = 0
            try {
                uris.distinct().forEachIndexed { index, uri ->
                    runCatching {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val result = libraryImporter.import(uri, libraryProjectId) { progress ->
                        runOnUiThread {
                            status.text = "Biblioteca Neural • ${index + 1}/${uris.size} • $progress"
                        }
                    }
                    imported++
                    status.text = "Biblioteca Neural • ${result.name} indexado (${result.sections} seção(ões))"
                }
                status.text = "$imported fonte(s) adicionada(s) à Biblioteca Neural. Elas já podem ser usadas em perguntas comuns."
            } catch (t: Throwable) {
                status.text = "Falha ao indexar Biblioteca Neural: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                importing = false
                refresh()
            }
        }
    }

    private fun formatCharacters(value: Long): String = when {
        value >= 1_000_000 -> String.format("%.1f M caracteres", value / 1_000_000.0)
        value >= 1_000 -> String.format("%.1f mil caracteres", value / 1_000.0)
        else -> "$value caracteres"
    }

'''
plugin = replace_once(plugin, insert_before, plugin_methods + insert_before, 'neural library plugin methods')

# onActivityResult must support multiple library files while retaining single-file plugin/image import.
start = plugin.find('    @Deprecated("Deprecated API retained for Storage Access Framework compatibility")\n    override fun onActivityResult')
end = plugin.find('    private fun importPack(uri: Uri) {', start)
if start < 0 or end < 0:
    raise SystemExit('v5.2.1 plugin onActivityResult boundaries missing')
plugin = plugin[:start] + r'''    @Deprecated("Deprecated API retained for Storage Access Framework compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        if (requestCode == REQUEST_NEURAL_LIBRARY) {
            val uris = mutableListOf<Uri>()
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
            }
            data.data?.let { uris += it }
            importNeuralLibrary(uris)
            return
        }

        val uri: Uri = data.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        when (requestCode) {
            REQUEST_PACK -> importPack(uri)
            REQUEST_IMAGE_MODEL -> importImageModel(uri)
        }
    }

''' + plugin[end:]

plugin = replace_once(
    plugin,
    '''    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
''',
    '''    override fun onDestroy() {
        if (::libraryImporter.isInitialized) libraryImporter.close()
        if (::libraryStore.isInitialized) libraryStore.close()
        scope.cancel()
        super.onDestroy()
    }
''',
    'close neural library resources',
)

plugin = replace_once(
    plugin,
    '''        private const val REQUEST_IMAGE_MODEL = 7102
''',
    '''        private const val REQUEST_IMAGE_MODEL = 7102
        private const val REQUEST_NEURAL_LIBRARY = 7103
''',
    'neural library request code',
)

plugin_path.write_text(plugin, encoding='utf-8')

print('Workspace v5.2.1 patch applied: resident prompt fix companion + global Neural Library in Plugins')
