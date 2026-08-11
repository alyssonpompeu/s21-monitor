package com.alysson.offlineai

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.Normalizer

class LexicalMemory private constructor(
    private val database: SQLiteDatabase
) : AutoCloseable {

    fun retrieve(query: String): String {
        val tokens = normalize(query)
            .split(' ')
            .asSequence()
            .filter { it.length >= 3 }
            .distinct()
            .take(8)
            .toList()

        if (tokens.isEmpty()) return ""

        val knownWords = findKnownWords(tokens)
        val dictionaryLines = findDictionaryLines(tokens)

        if (knownWords.isEmpty() && dictionaryLines.isEmpty()) return ""

        return buildString {
            appendLine("<memoria_lexical_local>")
            appendLine("Esta memória é apenas apoio lexical offline. A lista é PT-BR sem diacríticos; o dicionário é uma fonte histórica de 1913 e não deve ser tratado como norma contemporânea.")
            if (knownWords.isNotEmpty()) {
                append("Vocábulos reconhecidos na lista PT-BR: ")
                appendLine(knownWords.joinToString(", "))
            }
            if (dictionaryLines.isNotEmpty()) {
                appendLine("Trechos recuperados do Novo Dicionário da Língua Portuguesa (1913):")
                dictionaryLines.forEach { line ->
                    append("- ")
                    appendLine(line.take(320))
                }
            }
            append("</memoria_lexical_local>")
        }
    }

    private fun findKnownWords(tokens: List<String>): List<String> {
        val placeholders = tokens.joinToString(",") { "?" }
        val sql = "SELECT word FROM lexicon WHERE word IN ($placeholders) LIMIT 16"
        val found = mutableListOf<String>()
        database.rawQuery(sql, tokens.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) found += cursor.getString(0)
        }
        return found.sorted()
    }

    private fun findDictionaryLines(tokens: List<String>): List<String> {
        val match = tokens.take(6).joinToString(" OR ") { "\"$it\"" }
        val sql = """
            SELECT d.line
            FROM dictionary_fts
            JOIN dictionary d ON d.rowid = dictionary_fts.rowid
            WHERE dictionary_fts MATCH ?
            LIMIT 10
        """.trimIndent()

        val lines = mutableListOf<String>()
        database.rawQuery(sql, arrayOf(match)).use { cursor ->
            while (cursor.moveToNext()) lines += cursor.getString(0)
        }
        return lines
    }

    override fun close() {
        database.close()
    }

    companion object {
        private const val ASSET_DB = "ptbr_memory.db"
        private const val LOCAL_DB = "ptbr_memory_v1.db"

        fun open(context: Context): LexicalMemory {
            val target = File(context.filesDir, LOCAL_DB)
            if (!target.exists() || target.length() < 1024L) {
                val tmp = File(context.filesDir, "$LOCAL_DB.tmp")
                context.assets.open(ASSET_DB).use { input ->
                    tmp.outputStream().buffered().use { output -> input.copyTo(output, 256 * 1024) }
                }
                if (target.exists()) target.delete()
                check(tmp.renameTo(target)) { "Não foi possível instalar a memória lexical local." }
            }
            return LexicalMemory(
                SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            )
        }

        fun normalize(text: String): String {
            val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
            val out = StringBuilder(decomposed.length)
            var lastWasSpace = true
            for (ch in decomposed) {
                if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
                val lower = ch.lowercaseChar()
                if (lower in 'a'..'z' || lower in '0'..'9') {
                    out.append(lower)
                    lastWasSpace = false
                } else if (!lastWasSpace) {
                    out.append(' ')
                    lastWasSpace = true
                }
            }
            return out.toString().trim()
        }
    }
}
