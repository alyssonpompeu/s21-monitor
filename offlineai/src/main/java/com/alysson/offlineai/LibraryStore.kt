package com.alysson.offlineai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LibraryStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), AutoCloseable {

    data class Section(val location: String, val text: String)
    data class Stats(val documents: Int, val chunks: Int, val characters: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                mime TEXT NOT NULL,
                imported_at INTEGER NOT NULL,
                characters INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE VIRTUAL TABLE chunks_fts USING fts4(doc_id, source, location, text)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS chunks_fts")
        db.execSQL("DROP TABLE IF EXISTS documents")
        onCreate(db)
    }

    fun addDocument(name: String, mime: String, sections: List<Section>): Long {
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
                    put("name", name)
                    put("mime", mime)
                    put("imported_at", System.currentTimeMillis())
                    put("characters", characters)
                }
            )

            cleaned.forEach { section ->
                chunk(section.text).forEach { part ->
                    db.insertOrThrow(
                        "chunks_fts",
                        null,
                        ContentValues().apply {
                            put("doc_id", docId.toString())
                            put("source", name)
                            put("location", section.location)
                            put("text", part)
                        }
                    )
                }
            }
            db.setTransactionSuccessful()
            return docId
        } finally {
            db.endTransaction()
        }
    }

    fun retrieve(query: String): String {
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
            "SELECT source, location, text FROM chunks_fts WHERE chunks_fts MATCH ? LIMIT 7",
            arrayOf(match)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                snippets += Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
            }
        }
        if (snippets.isEmpty()) return ""

        return buildString {
            appendLine("<biblioteca_local_usuario>")
            appendLine("Trechos recuperados de arquivos que o usuário anexou e armazenou localmente no aparelho. Use-os como fonte para responder à pergunta e cite o nome do arquivo quando uma afirmação depender deles.")
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

    fun stats(): Stats {
        val db = readableDatabase
        val documents = db.rawQuery("SELECT COUNT(*) FROM documents", null).use {
            it.moveToFirst(); it.getInt(0)
        }
        val chunks = db.rawQuery("SELECT COUNT(*) FROM chunks_fts", null).use {
            it.moveToFirst(); it.getInt(0)
        }
        val characters = db.rawQuery("SELECT COALESCE(SUM(characters), 0) FROM documents", null).use {
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
        private const val DB_VERSION = 1
        private const val CHUNK_CHARS = 1400
        private const val CHUNK_OVERLAP = 180
    }
}
