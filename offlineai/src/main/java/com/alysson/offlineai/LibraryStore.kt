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
            db.execSQL(
                "INSERT OR IGNORE INTO projects(id, name, created_at, updated_at) VALUES(1, 'Geral', $now, $now)"
            )

            val columns = mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info(documents)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) if (nameIndex >= 0) columns += cursor.getString(nameIndex)
            }
            if ("project_id" !in columns) {
                db.execSQL("ALTER TABLE documents ADD COLUMN project_id INTEGER NOT NULL DEFAULT 1")
            }

            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS project_chunks_fts USING fts4(project_id, doc_id, source, location, text)"
            )
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
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS search_history_project_idx ON search_history(project_id, searched_at DESC)"
            )
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
        db.execSQL(
            "INSERT INTO projects(id, name, created_at, updated_at) VALUES(1, 'Geral', $now, $now)"
        )
        db.execSQL(
            """
            CREATE TABLE documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                mime TEXT NOT NULL,
                imported_at INTEGER NOT NULL,
                characters INTEGER NOT NULL
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
    }

    fun ensureDefaultProject(): Long {
        val db = writableDatabase
        val id = db.rawQuery("SELECT id FROM projects ORDER BY id LIMIT 1", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        }
        if (id > 0) return id
        val now = System.currentTimeMillis()
        return db.insertOrThrow(
            "projects",
            null,
            ContentValues().apply {
                put("name", "Geral")
                put("created_at", now)
                put("updated_at", now)
            }
        )
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
            while (cursor.moveToNext()) {
                result += Project(cursor.getLong(0), cursor.getString(1), cursor.getLong(2))
            }
        }
        return result
    }

    fun createProject(name: String): Long {
        val clean = name.trim().ifBlank { "Novo projeto" }.take(80)
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

    fun deleteProject(projectId: Long): Long {
        require(projectExists(projectId)) { "Projeto local inválido." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("project_chunks_fts", "project_id = ?", arrayOf(projectId.toString()))
            db.delete("documents", "project_id = ?", arrayOf(projectId.toString()))
            db.delete("search_history", "project_id = ?", arrayOf(projectId.toString()))
            db.delete("projects", "id = ?", arrayOf(projectId.toString()))

            var replacement = db.rawQuery(
                "SELECT id FROM projects ORDER BY updated_at DESC, id DESC LIMIT 1",
                null
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }

            if (replacement <= 0L) {
                val now = System.currentTimeMillis()
                replacement = db.insertOrThrow(
                    "projects",
                    null,
                    ContentValues().apply {
                        put("name", "Geral")
                        put("created_at", now)
                        put("updated_at", now)
                    }
                )
            }
            db.setTransactionSuccessful()
            return replacement
        } finally {
            db.endTransaction()
        }
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
            db.delete(
                "search_history",
                "project_id = ? AND mode = ? AND query = ?",
                arrayOf(projectId.toString(), mode, clean)
            )
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
            db.update(
                "projects",
                ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
                "id = ?",
                arrayOf(projectId.toString())
            )
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
            while (cursor.moveToNext()) {
                result += SearchHistory(cursor.getString(0), cursor.getString(1), cursor.getLong(2))
            }
        }
        return result
    }

    fun clearSearchHistory(projectId: Long) {
        writableDatabase.delete("search_history", "project_id = ?", arrayOf(projectId.toString()))
    }

    fun addDocument(projectId: Long, name: String, mime: String, sections: List<Section>): Long {
        require(projectExists(projectId)) { "Projeto local inválido." }
        val cleaned = sections.mapNotNull { section ->
            val text = section.text.replace('\u0000', ' ').trim()
            if (text.isBlank()) null else section.copy(text = text)
        }
        require(cleaned.isNotEmpty()) { "Nenhum texto reconhecível foi encontrado no arquivo." }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val characters = cleaned.sumOf { it.text.length.toLong() }
            val docId = db.insertOrThrow(
                "documents",
                null,
                ContentValues().apply {
                    put("project_id", projectId)
                    put("name", name)
                    put("mime", mime)
                    put("imported_at", System.currentTimeMillis())
                    put("characters", characters)
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
                            put("source", name)
                            put("location", section.location)
                            put("text", part)
                        }
                    )
                }
            }
            db.update(
                "projects",
                ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
                "id = ?",
                arrayOf(projectId.toString())
            )
            db.setTransactionSuccessful()
            return docId
        } finally {
            db.endTransaction()
        }
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
            while (cursor.moveToNext()) {
                snippets += Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
            }
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
        ).use {
            it.moveToFirst(); it.getLong(0)
        }
        return Stats(documents, chunks, characters)
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
        private const val DB_VERSION = 3
        private const val CHUNK_CHARS = 1400
        private const val CHUNK_OVERLAP = 180
        private const val MAX_SEARCH_HISTORY = 60
        private const val MAX_SEARCH_CHARS = 600
    }
}
