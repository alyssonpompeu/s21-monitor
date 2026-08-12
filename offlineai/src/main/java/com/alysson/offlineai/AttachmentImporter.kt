package com.alysson.offlineai

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Project source importer. V5 accepts any file type as a data source without executing it.
 * Text/PDF/images are extracted normally; ZIP/APK containers are inspected with anti-zip-bomb
 * limits; opaque binaries such as EXE are represented by metadata plus bounded printable strings.
 */
class AttachmentImporter(
    private val context: Context,
    private val library: LibraryStore
) : AutoCloseable {

    data class Result(
        val name: String,
        val sections: Int,
        val characters: Long,
        val note: String = ""
    )

    private val resolver: ContentResolver = context.contentResolver
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun import(uri: Uri, projectId: Long, progress: (String) -> Unit): Result {
        val name = displayName(uri)
        val mime = resolver.getType(uri).orEmpty().ifBlank { guessMime(name) }
        val size = fileSize(uri)
        progress("Lendo $name…")

        val sha256 = if (size in 0..MAX_FULL_HASH_BYTES) {
            progress("Calculando integridade de $name…")
            hash(uri)
        } else ""

        val extraction = when {
            mime == "application/pdf" || name.endsWith(".pdf", true) -> extractPdf(uri, progress)
            mime.startsWith("image/") -> extractImage(uri, progress)
            isArchive(name, mime) -> extractArchive(uri, name, mime, size, sha256, progress)
            isTextLike(name, mime) -> extractText(uri)
            else -> extractBinaryMetadata(uri, name, mime, size, sha256, progress)
        }

        library.addDocument(
            projectId = projectId,
            name = name,
            mime = mime,
            sections = extraction.sections,
            sourceUri = uri.toString(),
            sizeBytes = size.coerceAtLeast(0L),
            sha256 = sha256,
        )
        return Result(
            name = name,
            sections = extraction.sections.size,
            characters = extraction.sections.sumOf { it.text.length.toLong() },
            note = buildString {
                append(extraction.note)
                if (sha256.isBlank() && size > MAX_FULL_HASH_BYTES) {
                    if (isNotEmpty()) append(' ')
                    append("Hash completo omitido por tamanho para preservar bateria/temperatura.")
                }
            }
        )
    }

    private fun extractText(uri: Uri): Extraction {
        val output = ByteArrayOutputStream()
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                total += n
                require(total <= MAX_TEXT_BYTES) { "Texto maior que ${MAX_TEXT_BYTES / MIB} MiB; use uma versão dividida para indexação segura." }
                output.write(buffer, 0, n)
            }
        } ?: throw IllegalArgumentException("Não foi possível abrir o arquivo.")
        val text = output.toString(Charsets.UTF_8.name()).trim()
        require(text.isNotBlank()) { "O arquivo não contém texto reconhecível." }
        return Extraction(listOf(LibraryStore.Section("texto", text)))
    }

    private suspend fun extractImage(uri: Uri, progress: (String) -> Unit): Extraction {
        progress("Reconhecendo texto da imagem…")
        val bitmap = decodeScaledBitmap(uri, MAX_IMAGE_DIMENSION)
            ?: throw IllegalArgumentException("Não foi possível decodificar a imagem.")
        return try {
            val text = recognize(bitmap)
            val value = if (text.isBlank()) {
                "Imagem anexada como fonte visual. Nenhum texto OCR foi reconhecido."
            } else text
            Extraction(listOf(LibraryStore.Section("imagem/OCR", value)))
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun extractPdf(uri: Uri, progress: (String) -> Unit): Extraction {
        val pfd = resolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Não foi possível abrir o PDF.")
        val sections = mutableListOf<LibraryStore.Section>()
        var ocrPages = 0
        var truncated = false

        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val nativeLimit = minOf(renderer.pageCount, MAX_NATIVE_PDF_PAGES)
                for (index in 0 until nativeLimit) {
                    progress("PDF: página ${index + 1}/${renderer.pageCount}")
                    renderer.openPage(index).use { page ->
                        var text = ""
                        if (Build.VERSION.SDK_INT >= 35) {
                            text = runCatching { page.textContents.joinToString("\n") { it.text }.trim() }.getOrDefault("")
                        }
                        if (text.isBlank()) {
                            if (ocrPages >= MAX_OCR_PDF_PAGES) {
                                truncated = true
                                return@use
                            }
                            val bitmap = renderPage(page)
                            try { text = recognize(bitmap).trim() } finally { bitmap.recycle() }
                            ocrPages++
                        }
                        if (text.isNotBlank()) sections += LibraryStore.Section("página ${index + 1}", text)
                    }
                    if (truncated) break
                }
                if (renderer.pageCount > nativeLimit) truncated = true
            }
        }
        require(sections.isNotEmpty()) { "Nenhum texto foi reconhecido no PDF." }
        return Extraction(
            sections,
            if (truncated) "PDF indexado parcialmente para preservar memória e temperatura do aparelho." else ""
        )
    }

    private fun extractArchive(
        uri: Uri,
        name: String,
        mime: String,
        size: Long,
        sha256: String,
        progress: (String) -> Unit,
    ): Extraction {
        progress("Inspecionando conteúdo de $name…")
        val sections = mutableListOf<LibraryStore.Section>()
        val listing = StringBuilder()
        listing.appendLine("Arquivo-contêiner: $name")
        listing.appendLine("Tipo: $mime")
        if (size >= 0) listing.appendLine("Tamanho: $size bytes")
        if (sha256.isNotBlank()) listing.appendLine("SHA-256: $sha256")

        var entries = 0
        var indexedBytes = 0L
        var truncated = false
        resolver.openInputStream(uri)?.buffered()?.use { base ->
            ZipInputStream(base).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ARCHIVE_ENTRIES) {
                        truncated = true
                        break
                    }
                    val safeName = entry.name.replace('\u0000', ' ').take(300)
                    listing.appendLine("${if (entry.isDirectory) "DIR" else "FILE"} $safeName${if (entry.size >= 0) " (${entry.size} B)" else ""}")
                    if (!entry.isDirectory && isArchiveTextEntry(safeName) && indexedBytes < MAX_ARCHIVE_TEXT_TOTAL_BYTES) {
                        val maxForEntry = minOf(MAX_ARCHIVE_ENTRY_BYTES, MAX_ARCHIVE_TEXT_TOTAL_BYTES - indexedBytes)
                        val bytes = readBounded(zip, maxForEntry)
                        indexedBytes += bytes.size
                        val text = decodeLikelyText(bytes)
                        if (text.isNotBlank()) sections += LibraryStore.Section("arquivo $safeName", text)
                        if (entry.size > maxForEntry || bytes.size.toLong() >= maxForEntry) truncated = true
                    }
                    zip.closeEntry()
                }
            }
        } ?: throw IllegalArgumentException("Não foi possível abrir o arquivo compactado.")

        sections.add(0, LibraryStore.Section("conteúdo do pacote", listing.toString()))
        return Extraction(
            sections,
            if (truncated) "Pacote indexado com limites anti-zip-bomb; conteúdo executável nunca é executado." else "Conteúdo executável nunca é executado."
        )
    }

    private fun extractBinaryMetadata(
        uri: Uri,
        name: String,
        mime: String,
        size: Long,
        sha256: String,
        progress: (String) -> Unit,
    ): Extraction {
        progress("Extraindo metadados e strings seguras de $name…")
        val prefix = resolver.openInputStream(uri)?.use { input -> readBounded(input, MAX_BINARY_SCAN_BYTES) }
            ?: throw IllegalArgumentException("Não foi possível abrir o arquivo.")
        val strings = extractPrintableStrings(prefix)
        val text = buildString {
            appendLine("Fonte binária anexada; o aplicativo não executa este arquivo.")
            appendLine("Nome: $name")
            appendLine("MIME: $mime")
            if (size >= 0) appendLine("Tamanho: $size bytes")
            if (sha256.isNotBlank()) appendLine("SHA-256: $sha256")
            appendLine("Bytes inspecionados: ${prefix.size}")
            if (strings.isNotBlank()) {
                appendLine()
                appendLine("Strings imprimíveis encontradas no trecho inspecionado:")
                append(strings)
            }
        }
        return Extraction(
            listOf(LibraryStore.Section("metadados/strings", text)),
            "Arquivo tratado somente como fonte de dados; nenhuma instrução binária foi executada."
        )
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var remaining = maxBytes.coerceAtLeast(0L)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n <= 0) break
            out.write(buffer, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun decodeLikelyText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val zeros = bytes.count { it == 0.toByte() }
        if (zeros > bytes.size / 8) return ""
        return bytes.toString(Charsets.UTF_8).replace('\u0000', ' ').trim().take(MAX_ARCHIVE_ENTRY_CHARS)
    }

    private fun extractPrintableStrings(bytes: ByteArray): String {
        val out = StringBuilder()
        val current = StringBuilder()
        fun flush() {
            if (current.length >= MIN_BINARY_STRING) {
                out.appendLine(current.toString().take(MAX_SINGLE_BINARY_STRING))
            }
            current.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 32..126 || c in 160..255) {
                current.append(c.toChar())
                if (current.length >= MAX_SINGLE_BINARY_STRING) flush()
            } else flush()
            if (out.length >= MAX_BINARY_STRINGS_CHARS) break
        }
        flush()
        return out.toString().take(MAX_BINARY_STRINGS_CHARS)
    }

    private fun hash(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        } ?: throw IllegalArgumentException("Não foi possível abrir o arquivo para calcular integridade.")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fileSize(uri: Uri): Long {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return -1L
    }

    private fun isArchive(name: String, mime: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".zip") || lower.endsWith(".apk") || lower.endsWith(".jar") ||
            mime == "application/zip" || mime == "application/vnd.android.package-archive" || mime == "application/java-archive"
    }

    private fun isTextLike(name: String, mime: String): Boolean {
        if (mime.startsWith("text/")) return true
        val lower = name.lowercase(Locale.ROOT)
        return TEXT_EXTENSIONS.any(lower::endsWith)
    }

    private fun isArchiveTextEntry(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return TEXT_EXTENSIONS.any(lower::endsWith) || lower.endsWith("androidmanifest.xml")
    }

    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val scale = minOf(
            MAX_PDF_RENDER_DIMENSION.toFloat() / page.width.coerceAtLeast(1),
            MAX_PDF_RENDER_DIMENSION.toFloat() / page.height.coerceAtLeast(1),
            2.2f
        ).coerceAtLeast(1f)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun decodeScaledBitmap(uri: Uri, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private suspend fun recognize(bitmap: Bitmap): String {
        return recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitValue().text
    }

    private fun displayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: "arquivo"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "arquivo"
    }

    private fun guessMime(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".exe") -> "application/vnd.microsoft.portable-executable"
            isTextLike(name, "") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    override fun close() {
        recognizer.close()
    }

    private data class Extraction(val sections: List<LibraryStore.Section>, val note: String = "")

    private suspend fun <T> Task<T>.awaitValue(): T = suspendCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.resumeWithException(CancellationException("OCR cancelado")) }
    }

    companion object {
        private const val MIB = 1024L * 1024L
        private const val MAX_TEXT_BYTES = 20L * MIB
        private const val MAX_IMAGE_DIMENSION = 2400
        private const val MAX_PDF_RENDER_DIMENSION = 1700
        private const val MAX_OCR_PDF_PAGES = 120
        private const val MAX_NATIVE_PDF_PAGES = 1500
        private const val MAX_ARCHIVE_ENTRIES = 400
        private const val MAX_ARCHIVE_ENTRY_BYTES = 2L * MIB
        private const val MAX_ARCHIVE_TEXT_TOTAL_BYTES = 18L * MIB
        private const val MAX_ARCHIVE_ENTRY_CHARS = 2_000_000
        private const val MAX_BINARY_SCAN_BYTES = 12L * MIB
        private const val MAX_FULL_HASH_BYTES = 512L * MIB
        private const val MIN_BINARY_STRING = 5
        private const val MAX_SINGLE_BINARY_STRING = 480
        private const val MAX_BINARY_STRINGS_CHARS = 220_000
        private val TEXT_EXTENSIONS = listOf(
            ".txt", ".md", ".json", ".xml", ".html", ".htm", ".css", ".js", ".ts", ".tsx", ".jsx",
            ".kt", ".kts", ".java", ".gradle", ".properties", ".py", ".c", ".cc", ".cpp", ".h", ".hpp",
            ".cs", ".go", ".rs", ".sh", ".bat", ".ps1", ".yml", ".yaml", ".toml", ".ini", ".cfg", ".csv",
            ".sql", ".smali", ".log", ".pro", ".manifest"
        )
    }
}
