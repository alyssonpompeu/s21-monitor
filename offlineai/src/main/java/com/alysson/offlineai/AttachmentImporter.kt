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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

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

    suspend fun import(uri: Uri, progress: (String) -> Unit): Result {
        val name = displayName(uri)
        val mime = resolver.getType(uri).orEmpty().ifBlank { guessMime(name) }
        progress("Lendo $name…")

        val extraction = when {
            mime == "application/pdf" || name.endsWith(".pdf", true) -> extractPdf(uri, progress)
            mime.startsWith("image/") -> extractImage(uri, progress)
            mime.startsWith("text/") || name.endsWith(".txt", true) || name.endsWith(".md", true) -> extractText(uri)
            else -> throw IllegalArgumentException("Formato não suportado: $mime")
        }

        library.addDocument(name, mime, extraction.sections)
        return Result(
            name = name,
            sections = extraction.sections.size,
            characters = extraction.sections.sumOf { it.text.length.toLong() },
            note = extraction.note
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
                require(total <= MAX_TEXT_BYTES) { "Arquivo de texto maior que 12 MiB." }
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
            require(text.isNotBlank()) { "Nenhum texto foi reconhecido na imagem." }
            Extraction(listOf(LibraryStore.Section("imagem", text)))
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
                            text = runCatching {
                                page.textContents.joinToString("\n") { it.text }.trim()
                            }.getOrDefault("")
                        }

                        if (text.isBlank()) {
                            if (ocrPages >= MAX_OCR_PDF_PAGES) {
                                truncated = true
                                return@use
                            }
                            val bitmap = renderPage(page)
                            try {
                                text = recognize(bitmap).trim()
                            } finally {
                                bitmap.recycle()
                            }
                            ocrPages++
                        }

                        if (text.isNotBlank()) {
                            sections += LibraryStore.Section("página ${index + 1}", text)
                        }
                    }
                    if (truncated) break
                }
                if (renderer.pageCount > nativeLimit) truncated = true
            }
        }

        require(sections.isNotEmpty()) { "Nenhum texto foi reconhecido no PDF." }
        val note = if (truncated) {
            "PDF indexado parcialmente para preservar memória e temperatura do aparelho."
        } else {
            ""
        }
        return Extraction(sections, note)
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
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) {
            sample *= 2
        }
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

    private fun guessMime(name: String): String = when {
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".md", true) -> "text/markdown"
        else -> "text/plain"
    }

    override fun close() {
        recognizer.close()
    }

    private data class Extraction(
        val sections: List<LibraryStore.Section>,
        val note: String = ""
    )

    private suspend fun <T> Task<T>.awaitValue(): T = suspendCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.resumeWithException(CancellationException("OCR cancelado")) }
    }

    companion object {
        private const val MAX_TEXT_BYTES = 12 * 1024 * 1024
        private const val MAX_IMAGE_DIMENSION = 2400
        private const val MAX_PDF_RENDER_DIMENSION = 1700
        private const val MAX_OCR_PDF_PAGES = 120
        private const val MAX_NATIVE_PDF_PAGES = 1500
    }
}
