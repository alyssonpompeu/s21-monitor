package com.alysson.offlineai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LibraryStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), AutoCloseable {

    data class Section(val location: String, val text: String)
    data class Stats(val documents: Int, val chunks: Int, val characters: Long)
    data class Project(val id: Long, val name: String, val createdAt: Long)
    data class SearchHistory(val query: String, val mode: String, val searchedAt: Long)
    data class DocumentSource(
        val id: Long,
        val name: String,
        val mime: String,
        val characters: Long,
        val importedAt: Long,
        val sizeBytes: Long,
        val sha256: String,
        val sourceUri: String,
    )
    data class ConversationTurn(
        val id: Long,
        val projectId: Long,
        val user: String,
        val assistant: String,
        val pluginId: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val now = System.currentTimeMillis()
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS projects (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("INSERT OR IGNORE INTO projects(id, name, created_at, updated_at) VALUES(1, 'Geral', $now, $now)")
            addColumnIfMissing(db, "documents", "project_id", "INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS project_chunks_fts USING fts4(project_id, doc_id, source, location, text)")
            val oldFtsExists = db.rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='chunks_fts'",
                null
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) > 0 }
            val newCount = db.rawQuery("SELECT COUNT(*) FROM project_chunks_fts", null).use {
                it.moveToFirst(); it.getInt(0)
            }
            if (oldFtsExists && newCount == 0) {
                db.execSQL(
                    "INSERT INTO project_chunks_fts(project_id, doc_id, source, location, text) " +
                        "SELECT '1', doc_id, source, location, text FROM chunks_fts"
                )
            }
        }

        if (oldVersion < 3) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS search_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id INTEGER NOT NULL,
                    query TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    searched_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS search_history_project_idx ON search_history(project_id, searched_at DESC)")
        }

        if (oldVersion < 4) {
            addColumnIfMissing(db, "documents", "source_uri", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "documents", "size_bytes", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "documents", "sha256", "TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS conversation_turns (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id INTEGER NOT NULL,
                    user_text TEXT NOT NULL,
                    assistant_text TEXT NOT NULL DEFAULT '',
                    plugin_id TEXT NOT NULL DEFAULT 'text.qwen',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS conversation_project_idx ON conversation_turns(project_id, created_at DESC)")
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.execSQL(
            """
            CREATE TABLE projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO projects(id, name, created_at, updated_at) VALUES(1, 'Geral', $now, $now)")
        db.execSQL(
            """
            CREATE TABLE documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                mime TEXT NOT NULL,
                imported_at INTEGER NOT NULL,
                characters INTEGER NOT NULL,
                source_uri TEXT NOT NULL DEFAULT '',
                size_bytes INTEGER NOT NULL DEFAULT 0,
                sha256 TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX documents_project_idx ON documents(project_id)")
        db.execSQL("CREATE VIRTUAL TABLE project_chunks_fts USING fts4(project_id, doc_id, source, location, text)")
        db.execSQL(
            """
            CREATE TABLE search_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                query TEXT NOT NULL,
                mode TEXT NOT NULL,
                searched_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX search_history_project_idx ON search_history(project_id, searched_at DESC)")
        db.execSQL(
            """
            CREATE TABLE conversation_turns (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                user_text TEXT NOT NULL,
                assistant_text TEXT NOT NULL DEFAULT '',
                plugin_id TEXT NOT NULL DEFAULT 'text.qwen',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX conversation_project_idx ON conversation_turns(project_id, created_at DESC)")
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, declaration: String) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $declaration")
    }

    fun ensureDefaultProject(): Long {
        val db = writableDatabase
        val id = db.rawQuery("SELECT id FROM projects ORDER BY id LIMIT 1", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        }
        if (id > 0) return id
        return createProject("Geral")
    }

    fun projectExists(projectId: Long): Boolean = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM projects WHERE id = ?",
        arrayOf(projectId.toString())
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) > 0 }

    fun listProjects(): List<Project> {
        val result = mutableListOf<Project>()
        readableDatabase.rawQuery(
            "SELECT id, name, created_at FROM projects ORDER BY updated_at DESC, id DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) result += Project(cursor.getLong(0), cursor.getString(1), cursor.getLong(2))
        }
        return result
    }

    fun createProject(name: String): Long {
        val clean = name.trim().ifBlank { "Novo chat" }.take(80)
        val now = System.currentTimeMillis()
        return writableDatabase.insertOrThrow(
            "projects",
            null,
            ContentValues().apply {
                put("name", clean)
                put("created_at", now)
                put("updated_at", now)
            }
        )
    }

    fun renameProject(projectId: Long, name: String) {
        val clean = name.trim().ifBlank { "Projeto" }.take(80)
        writableDatabase.update(
            "projects",
            ContentValues().apply {
                put("name", clean)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(projectId.toString())
        )
    }

    fun autoRenameProjectFromQuestion(projectId: Long, question: String) {
        val current = projectName(projectId)
        if (!current.startsWith("Chat ") && current != "Novo chat") return
        val clean = question.replace(Regex("\\s+"), " ").trim()
            .removeSuffix("?")
            .take(58)
            .ifBlank { return }
        renameProject(projectId, clean)
    }

    fun deleteProject(projectId: Long): Long {
        require(projectExists(projectId)) { "Projeto local inválido." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("project_chunks_fts", "project_id = ?", arrayOf(projectId.toString()))
            db.delete("documents", "project_id = ?", arrayOf(projectId.toString()))
            db.delete("search_history", "project_id = ?", arrayOf(projectId.toString()))
            db.delete("conversation_turns", "project_id = ?", arrayOf(projectId.toString()))
            db.delete("projects", "id = ?", arrayOf(projectId.toString()))

            var replacement = db.rawQuery(
                "SELECT id FROM projects ORDER BY updated_at DESC, id DESC LIMIT 1",
                null
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }
            if (replacement <= 0L) replacement = createProjectInTransaction(db, "Geral")
            db.setTransactionSuccessful()
            return replacement
        } finally {
            db.endTransaction()
        }
    }

    private fun createProjectInTransaction(db: SQLiteDatabase, name: String): Long {
        val now = System.currentTimeMillis()
        return db.insertOrThrow(
            "projects",
            null,
            ContentValues().apply {
                put("name", name)
                put("created_at", now)
                put("updated_at", now)
            }
        )
    }

    fun projectName(projectId: Long): String = readableDatabase.rawQuery(
        "SELECT name FROM projects WHERE id = ? LIMIT 1",
        arrayOf(projectId.toString())
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "Geral" }

    fun recordSearch(projectId: Long, query: String, mode: String) {
        if (!projectExists(projectId)) return
        val clean = query.replace(Regex("\\s+"), " ").trim().take(MAX_SEARCH_CHARS)
        if (clean.isBlank()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("search_history", "project_id = ? AND mode = ? AND query = ?", arrayOf(projectId.toString(), mode, clean))
            db.insertOrThrow(
                "search_history",
                null,
                ContentValues().apply {
                    put("project_id", projectId)
                    put("query", clean)
                    put("mode", mode)
                    put("searched_at", System.currentTimeMillis())
                }
            )
            db.execSQL(
                "DELETE FROM search_history WHERE project_id = ? AND id NOT IN " +
                    "(SELECT id FROM search_history WHERE project_id = ? ORDER BY searched_at DESC LIMIT ?)",
                arrayOf<Any>(projectId, projectId, MAX_SEARCH_HISTORY)
            )
            touchProject(db, projectId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recentSearches(projectId: Long, mode: String? = null, limit: Int = 12): List<SearchHistory> {
        val result = mutableListOf<SearchHistory>()
        val safeLimit = limit.coerceIn(1, 30)
        val (where, args) = if (mode.isNullOrBlank()) {
            "project_id = ?" to arrayOf(projectId.toString(), safeLimit.toString())
        } else {
            "project_id = ? AND mode = ?" to arrayOf(projectId.toString(), mode, safeLimit.toString())
        }
        readableDatabase.rawQuery(
            "SELECT query, mode, searched_at FROM search_history WHERE $where ORDER BY searched_at DESC LIMIT ?",
            args
        ).use { cursor ->
            while (cursor.moveToNext()) result += SearchHistory(cursor.getString(0), cursor.getString(1), cursor.getLong(2))
        }
        return result
    }

    fun clearSearchHistory(projectId: Long) {
        writableDatabase.delete("search_history", "project_id = ?", arrayOf(projectId.toString()))
    }

    fun beginConversationTurn(projectId: Long, user: String, pluginId: String): Long {
        require(projectExists(projectId)) { "Projeto local inválido." }
        val now = System.currentTimeMillis()
        val id = writableDatabase.insertOrThrow(
            "conversation_turns",
            null,
            ContentValues().apply {
                put("project_id", projectId)
                put("user_text", user.trim().take(MAX_USER_CHARS))
                put("assistant_text", "")
                put("plugin_id", pluginId.take(80))
                put("created_at", now)
                put("updated_at", now)
            }
        )
        writableDatabase.let { touchProject(it, projectId) }
        return id
    }

    fun updateConversationTurn(turnId: Long, assistant: String) {
        writableDatabase.update(
            "conversation_turns",
            ContentValues().apply {
                put("assistant_text", assistant.take(MAX_ASSISTANT_CHARS))
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(turnId.toString())
        )
    }

    fun recordTurn(projectId: Long, user: String, assistant: String, pluginId: String = "text.qwen") {
        val id = beginConversationTurn(projectId, user, pluginId)
        updateConversationTurn(id, assistant)
    }

    fun recentConversationTurns(projectId: Long, limit: Int = 8): List<ConversationTurn> {
        val safeLimit = limit.coerceIn(1, 80)
        val reversed = mutableListOf<ConversationTurn>()
        readableDatabase.rawQuery(
            "SELECT id, project_id, user_text, assistant_text, plugin_id, created_at, updated_at " +
                "FROM conversation_turns WHERE project_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
            arrayOf(projectId.toString(), safeLimit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                reversed += ConversationTurn(
                    id = cursor.getLong(0),
                    projectId = cursor.getLong(1),
                    user = cursor.getString(2),
                    assistant = cursor.getString(3),
                    pluginId = cursor.getString(4),
                    createdAt = cursor.getLong(5),
                    updatedAt = cursor.getLong(6),
                )
            }
        }
        return reversed.asReversed()
    }

    fun clearConversation(projectId: Long) {
        writableDatabase.delete("conversation_turns", "project_id = ?", arrayOf(projectId.toString()))
    }

    fun addDocument(
        projectId: Long,
        name: String,
        mime: String,
        sections: List<Section>,
        sourceUri: String = "",
        sizeBytes: Long = 0L,
        sha256: String = "",
    ): Long {
        require(projectExists(projectId)) { "Projeto local inválido." }
        val cleaned = sections.mapNotNull { section ->
            val text = section.text.replace('\u0000', ' ').trim()
            if (text.isBlank()) null else section.copy(text = text)
        }
        require(cleaned.isNotEmpty()) { "Nenhum dado indexável foi encontrado no arquivo." }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val characters = cleaned.sumOf { it.text.length.toLong() }
            val docId = db.insertOrThrow(
                "documents",
                null,
                ContentValues().apply {
                    put("project_id", projectId)
                    put("name", name.take(240))
                    put("mime", mime.take(160))
                    put("imported_at", System.currentTimeMillis())
                    put("characters", characters)
                    put("source_uri", sourceUri.take(1600))
                    put("size_bytes", sizeBytes.coerceAtLeast(0L))
                    put("sha256", sha256.take(64))
                }
            )

            cleaned.forEach { section ->
                chunk(section.text).forEach { part ->
                    db.insertOrThrow(
                        "project_chunks_fts",
                        null,
                        ContentValues().apply {
                            put("project_id", projectId.toString())
                            put("doc_id", docId.toString())
                            put("source", name.take(240))
                            put("location", section.location.take(240))
                            put("text", part)
                        }
                    )
                }
            }
            touchProject(db, projectId)
            db.setTransactionSuccessful()
            return docId
        } finally {
            db.endTransaction()
        }
    }

    fun listDocuments(projectId: Long): List<DocumentSource> {
        val result = mutableListOf<DocumentSource>()
        readableDatabase.rawQuery(
            "SELECT id, name, mime, characters, imported_at, size_bytes, sha256, source_uri " +
                "FROM documents WHERE project_id = ? ORDER BY imported_at DESC, id DESC",
            arrayOf(projectId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += DocumentSource(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    mime = cursor.getString(2),
                    characters = cursor.getLong(3),
                    importedAt = cursor.getLong(4),
                    sizeBytes = cursor.getLong(5),
                    sha256 = cursor.getString(6),
                    sourceUri = cursor.getString(7),
                )
            }
        }
        return result
    }

    fun retrieve(query: String, projectId: Long, maxSnippets: Int = 7): String {
        val tokens = LexicalMemory.normalize(query)
            .split(' ')
            .asSequence()
            .filter { it.length >= 3 }
            .distinct()
            .take(8)
            .toList()
        if (tokens.isEmpty()) return ""

        val match = tokens.joinToString(" OR ") { "${escapeFts(it)}*" }
        val snippets = mutableListOf<Triple<String, String, String>>()
        readableDatabase.rawQuery(
            "SELECT source, location, text FROM project_chunks_fts " +
                "WHERE project_id = ? AND project_chunks_fts MATCH ? LIMIT ?",
            arrayOf(projectId.toString(), match, maxSnippets.coerceIn(1, 12).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) snippets += Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
        }
        if (snippets.isEmpty()) return ""

        return buildString {
            appendLine("<biblioteca_local_usuario>")
            appendLine("Trechos recuperados exclusivamente do projeto ativo. Arquivos de outros projetos não fazem parte deste contexto.")
            snippets.forEach { (source, location, text) ->
                append("[Fonte: ")
                append(source)
                if (location.isNotBlank()) {
                    append(" — ")
                    append(location)
                }
                appendLine("]")
                appendLine(text.take(1200))
            }
            append("</biblioteca_local_usuario>")
        }
    }

    fun stats(projectId: Long): Stats {
        val db = readableDatabase
        val documents = db.rawQuery("SELECT COUNT(*) FROM documents WHERE project_id = ?", arrayOf(projectId.toString())).use {
            it.moveToFirst(); it.getInt(0)
        }
        val chunks = db.rawQuery("SELECT COUNT(*) FROM project_chunks_fts WHERE project_id = ?", arrayOf(projectId.toString())).use {
            it.moveToFirst(); it.getInt(0)
        }
        val characters = db.rawQuery(
            "SELECT COALESCE(SUM(characters), 0) FROM documents WHERE project_id = ?",
            arrayOf(projectId.toString())
        ).use { it.moveToFirst(); it.getLong(0) }
        return Stats(documents, chunks, characters)
    }

    private fun touchProject(db: SQLiteDatabase, projectId: Long) {
        db.update(
            "projects",
            ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
            "id = ?",
            arrayOf(projectId.toString())
        )
    }

    private fun chunk(text: String): List<String> {
        val compact = text.replace(Regex("[\\t\\r ]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (compact.length <= CHUNK_CHARS) return listOf(compact)
        val out = mutableListOf<String>()
        var start = 0
        while (start < compact.length) {
            var end = minOf(compact.length, start + CHUNK_CHARS)
            if (end < compact.length) {
                val floor = start + CHUNK_CHARS / 2
                val newline = compact.lastIndexOf('\n', end)
                val period = compact.lastIndexOf(". ", end)
                val candidate = maxOf(newline, period)
                if (candidate >= floor) end = candidate + 1
            }
            out += compact.substring(start, end).trim()
            if (end >= compact.length) break
            start = maxOf(start + 1, end - CHUNK_OVERLAP)
        }
        return out.filter { it.isNotBlank() }
    }

    private fun escapeFts(token: String): String = token.replace("\"", "")

    companion object {
        private const val DB_NAME = "user_library_v1.db"
        private const val DB_VERSION = 4
        private const val CHUNK_CHARS = 1400
        private const val CHUNK_OVERLAP = 180
        private const val MAX_SEARCH_HISTORY = 60
        private const val MAX_SEARCH_CHARS = 600
        private const val MAX_USER_CHARS = 12_000
        private const val MAX_ASSISTANT_CHARS = 120_000
    }
}
