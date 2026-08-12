package com.alysson.offlineai

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Durable workspace journal.
 *
 * This is intentionally NOT an Android/Linux swap file. Android controls zRAM and paging. V5 uses
 * a disk-backed, append-only spill/checkpoint area capped at 5 GiB so a partial chat or generated
 * source can be recovered after process death without forcing 5 GiB of writes at startup.
 *
 * Every event is first committed to app-internal storage. If the user grants a Storage Access
 * Framework directory, the same project is mirrored into Unilaw/Projeto-<id>/ where it remains
 * user-visible even if the APK is later removed.
 */
class UnilawPersistStore(
    private val context: Context,
    private val preferences: AppPreferences,
) {
    private val internalRoot = File(context.filesDir, "unilaw-persist")
    private val spillRoot = File(internalRoot, "spill")

    fun ensureInitialized() {
        internalRoot.mkdirs()
        spillRoot.mkdirs()
        File(internalRoot, "spill.meta.json").writeText(
            JSONObject()
                .put("format", "unilaw-spill-v1")
                .put("logical_capacity_bytes", MAX_SPILL_BYTES)
                .put("allocation", "grow-on-demand")
                .put("purpose", "chat/source checkpoints; not kernel swap")
                .toString(2),
            Charsets.UTF_8,
        )
        trimSpillIfNeeded()
    }

    fun isSharedFolderConfigured(): Boolean = preferences.persistentTreeUri().isNotBlank()

    fun sharedFolderDescription(): String = if (isSharedFolderConfigured()) {
        "Pasta Unilaw persistente configurada"
    } else {
        "Somente persistência interna; escolha uma pasta em Documentos para cópia durável"
    }

    fun configureSharedFolder(uri: Uri) {
        preferences.setPersistentTreeUri(uri.toString())
        preferences.setPersistenceFolderPrompted(true)
        ensureSharedUnilawRoot() ?: error("A pasta escolhida não permite criar a estrutura Unilaw.")
    }

    fun appendEvent(projectId: Long, projectName: String, type: String, payload: String) {
        ensureInitialized()
        val event = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("project_id", projectId)
            .put("project_name", projectName)
            .put("type", type)
            .put("payload", payload)
            .toString() + "\n"

        val localDir = internalProjectDir(projectId)
        File(localDir, JOURNAL_FILE).appendText(event, Charsets.UTF_8)
        runCatching { appendShared(projectId, event) }
    }

    fun checkpoint(
        projectId: Long,
        projectName: String,
        pluginId: String,
        question: String,
        partialAnswer: String,
    ) {
        ensureInitialized()
        val value = JSONObject()
            .put("format", "unilaw-checkpoint-v1")
            .put("ts", System.currentTimeMillis())
            .put("project_id", projectId)
            .put("project_name", projectName)
            .put("plugin", pluginId)
            .put("question", question)
            .put("partial_answer", partialAnswer)
            .toString()
        val file = File(spillRoot, "project-$projectId.checkpoint.json")
        atomicWrite(file, value)
        runCatching { writeShared(projectId, CHECKPOINT_FILE, "application/json", value) }
        trimSpillIfNeeded()
    }

    fun clearCheckpoint(projectId: Long) {
        File(spillRoot, "project-$projectId.checkpoint.json").delete()
        runCatching { sharedProjectDir(projectId)?.findFile(CHECKPOINT_FILE)?.delete() }
    }

    fun recordSource(projectId: Long, projectName: String, source: LibraryStore.DocumentSource) {
        val payload = JSONObject()
            .put("id", source.id)
            .put("name", source.name)
            .put("mime", source.mime)
            .put("size_bytes", source.sizeBytes)
            .put("sha256", source.sha256)
            .put("uri", source.sourceUri)
            .toString()
        appendEvent(projectId, projectName, "source", payload)
        runCatching {
            val directory = sharedSourcesDir(projectId) ?: return@runCatching
            val file = directory.findFile(SOURCE_INDEX_FILE)
                ?: directory.createFile("application/x-ndjson", SOURCE_INDEX_FILE)
                ?: return@runCatching
            context.contentResolver.openOutputStream(file.uri, "wa")?.use {
                it.write((payload + "\n").toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    fun syncTranscript(projectId: Long, projectName: String, turns: List<LibraryStore.ConversationTurn>) {
        ensureInitialized()
        val markdown = buildString {
            appendLine("# $projectName")
            appendLine()
            appendLine("Projeto Unilaw #$projectId")
            appendLine()
            turns.forEach { turn ->
                appendLine("## Usuário")
                appendLine(turn.user)
                appendLine()
                appendLine("## Assistente · ${turn.pluginId}")
                appendLine(turn.assistant)
                appendLine()
            }
        }
        val manifest = JSONObject()
            .put("format", "unilaw-project-v1")
            .put("project_id", projectId)
            .put("name", projectName)
            .put("updated_at", System.currentTimeMillis())
            .put("turns", turns.size)
            .put("spill_capacity_bytes", MAX_SPILL_BYTES)
            .toString(2)

        val localDir = internalProjectDir(projectId)
        atomicWrite(File(localDir, TRANSCRIPT_FILE), markdown)
        atomicWrite(File(localDir, MANIFEST_FILE), manifest)
        runCatching {
            writeShared(projectId, TRANSCRIPT_FILE, "text/markdown", markdown)
            writeShared(projectId, MANIFEST_FILE, "application/json", manifest)
        }
    }

    fun deleteProject(projectId: Long) {
        File(internalRoot, "projects/project-$projectId").deleteRecursively()
        File(spillRoot, "project-$projectId.checkpoint.json").delete()
        runCatching { sharedProjectDir(projectId)?.delete() }
    }

    fun spillCapacityBytes(): Long = MAX_SPILL_BYTES

    private fun internalProjectDir(projectId: Long): File =
        File(internalRoot, "projects/project-$projectId").apply { mkdirs() }

    private fun ensureSharedUnilawRoot(): DocumentFile? {
        val tree = preferences.persistentTreeUri().takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        val root = DocumentFile.fromTreeUri(context, tree) ?: return null
        return root.findFile(SHARED_ROOT_NAME) ?: root.createDirectory(SHARED_ROOT_NAME)
    }

    private fun sharedProjectDir(projectId: Long): DocumentFile? {
        val root = ensureSharedUnilawRoot() ?: return null
        val name = "Projeto-$projectId"
        return root.findFile(name) ?: root.createDirectory(name)
    }

    private fun sharedSourcesDir(projectId: Long): DocumentFile? {
        val project = sharedProjectDir(projectId) ?: return null
        return project.findFile("sources") ?: project.createDirectory("sources")
    }

    private fun appendShared(projectId: Long, text: String) {
        val project = sharedProjectDir(projectId) ?: return
        val file = project.findFile(JOURNAL_FILE)
            ?: project.createFile("application/x-ndjson", JOURNAL_FILE)
            ?: return
        context.contentResolver.openOutputStream(file.uri, "wa")?.use {
            it.write(text.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun writeShared(projectId: Long, name: String, mime: String, text: String) {
        val project = sharedProjectDir(projectId) ?: return
        val file = project.findFile(name) ?: project.createFile(mime, name) ?: return
        context.contentResolver.openOutputStream(file.uri, "w")?.use {
            it.write(text.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun trimSpillIfNeeded() {
        val files = spillRoot.listFiles()?.filter { it.isFile }.orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= MAX_SPILL_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= MAX_SPILL_BYTES) return
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    companion object {
        const val MAX_SPILL_BYTES = 5L * 1024L * 1024L * 1024L
        private const val SHARED_ROOT_NAME = "Unilaw"
        private const val JOURNAL_FILE = "chat.unilaw.persist"
        private const val TRANSCRIPT_FILE = "chat.md"
        private const val MANIFEST_FILE = "manifest.json"
        private const val CHECKPOINT_FILE = "checkpoint.json"
        private const val SOURCE_INDEX_FILE = "sources.jsonl"
    }
}
