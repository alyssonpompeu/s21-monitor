#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'v8 docs patch point missing: {label}')
    return text.replace(old, new, 1)

p = Path('offlineai/src/main/java/com/alysson/offlineai/AttachmentImporter.kt')
s = p.read_text(encoding='utf-8')

# Imports for bundled image labeling, read-only SQLite inspection and APK package metadata.
s = replace_once(s, 'import android.content.Context\n', 'import android.content.Context\nimport android.content.pm.PackageManager\nimport android.database.sqlite.SQLiteDatabase\n', 'imports android')
s = replace_once(s, 'import com.google.mlkit.vision.common.InputImage\n', 'import com.google.mlkit.vision.common.InputImage\nimport com.google.mlkit.vision.label.ImageLabeling\nimport com.google.mlkit.vision.label.defaults.ImageLabelerOptions\n', 'ML Kit image label imports')
s = replace_once(s, 'import java.security.MessageDigest\n', 'import java.io.File\nimport java.security.MessageDigest\n', 'File import')

s = replace_once(
    s,
    '    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)\n',
    '    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)\n    private val imageLabeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)\n',
    'image labeler field',
)

s = replace_once(
    s,
    '''        val extraction = when {
            mime == "application/pdf" || name.endsWith(".pdf", true) -> extractPdf(uri, progress)
            mime.startsWith("image/") -> extractImage(uri, progress)
            isArchive(name, mime) -> extractArchive(uri, name, mime, size, sha256, progress)
            isTextLike(name, mime) -> extractText(uri)
            else -> extractBinaryMetadata(uri, name, mime, size, sha256, progress)
        }
''',
    '''        val extraction = when {
            mime == "application/pdf" || name.endsWith(".pdf", true) -> extractPdf(uri, progress)
            mime.startsWith("image/") -> extractImage(uri, progress)
            isSqlite(name, mime) -> extractSqlite(uri, name, size, progress)
            isArchive(name, mime) -> extractArchive(uri, name, mime, size, sha256, progress)
            isTextLike(name, mime) -> extractText(uri)
            else -> extractBinaryMetadata(uri, name, mime, size, sha256, progress)
        }
''',
    'sqlite dispatch',
)

old_image = '''    private suspend fun extractImage(uri: Uri, progress: (String) -> Unit): Extraction {
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
'''
new_image = '''    private suspend fun extractImage(uri: Uri, progress: (String) -> Unit): Extraction {
        progress("Analisando imagem localmente: OCR + visão leve…")
        val bitmap = decodeScaledBitmap(uri, MAX_IMAGE_DIMENSION)
            ?: throw IllegalArgumentException("Não foi possível decodificar a imagem.")
        return try {
            val text = recognize(bitmap).trim()
            val labels = recognizeLabels(bitmap)
            val sections = mutableListOf<LibraryStore.Section>()
            if (text.isNotBlank()) sections += LibraryStore.Section("imagem/OCR", text)
            if (labels.isNotBlank()) sections += LibraryStore.Section("imagem/visão", labels)
            if (sections.isEmpty()) {
                sections += LibraryStore.Section("imagem", "Imagem anexada. OCR e rotulagem visual local não encontraram conteúdo textual/objetos com confiança suficiente.")
            }
            Extraction(sections, "Análise visual feita offline com modelos ML Kit empacotados; não é um VLM generativo.")
        } finally {
            bitmap.recycle()
        }
    }
'''
s = replace_once(s, old_image, new_image, 'image OCR+labels')

# Prefix APK package metadata before safe ZIP listing.
s = replace_once(
    s,
    '''        val listing = StringBuilder()
        listing.appendLine("Arquivo-contêiner: $name")
''',
    '''        val listing = StringBuilder()
        if (name.endsWith(".apk", ignoreCase = true)) {
            runCatching { inspectApk(uri, size) }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                listing.appendLine(it)
                listing.appendLine()
            }
        }
        listing.appendLine("Arquivo-contêiner: $name")
''',
    'APK metadata prefix',
)

# Office/EPUB are ZIP containers too.
s = replace_once(
    s,
    '''        return lower.endsWith(".zip") || lower.endsWith(".apk") || lower.endsWith(".jar") ||
            mime == "application/zip" || mime == "application/vnd.android.package-archive" || mime == "application/java-archive"
''',
    '''        return lower.endsWith(".zip") || lower.endsWith(".apk") || lower.endsWith(".jar") ||
            lower.endsWith(".docx") || lower.endsWith(".xlsx") || lower.endsWith(".pptx") || lower.endsWith(".epub") ||
            mime == "application/zip" || mime == "application/vnd.android.package-archive" || mime == "application/java-archive" ||
            mime.contains("openxmlformats") || mime == "application/epub+zip"
''',
    'Office archives',
)

# Better text from Office XML without importing an entire office parser runtime.
old_decode = '''    private fun decodeLikelyText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val zeros = bytes.count { it == 0.toByte() }
        if (zeros > bytes.size / 8) return ""
        return bytes.toString(Charsets.UTF_8).replace('\\u0000', ' ').trim().take(MAX_ARCHIVE_ENTRY_CHARS)
    }
'''
new_decode = '''    private fun decodeLikelyText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val zeros = bytes.count { it == 0.toByte() }
        if (zeros > bytes.size / 8) return ""
        var text = bytes.toString(Charsets.UTF_8).replace('\\u0000', ' ').trim()
        if (text.startsWith("<?xml") || text.startsWith("<w:") || text.startsWith("<worksheet") || text.startsWith("<p:")) {
            text = text
                .replace(Regex("</(?:w:p|a:p|row|si|item)>"), "\\n")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace(Regex("[ \\t]{2,}"), " ")
                .replace(Regex("\\n{3,}"), "\\n\\n")
        }
        return text.trim().take(MAX_ARCHIVE_ENTRY_CHARS)
    }
'''
s = replace_once(s, old_decode, new_decode, 'Office XML text cleanup')

# Add SQLite/APK/vision helpers before hash().
helpers = r'''    private fun isSqlite(name: String, mime: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3") ||
            mime == "application/vnd.sqlite3" || mime == "application/x-sqlite3"
    }

    private fun extractSqlite(uri: Uri, name: String, size: Long, progress: (String) -> Unit): Extraction {
        require(size < 0 || size <= MAX_SQLITE_BYTES) { "SQLite maior que ${MAX_SQLITE_BYTES / MIB} MiB; use uma cópia reduzida para inspeção local." }
        progress("Inspecionando SQLite em modo somente leitura…")
        val temp = File.createTempFile("unilaw-sqlite-", ".db", context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().buffered(256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        total += n
                        require(total <= MAX_SQLITE_BYTES) { "Banco excedeu o limite de inspeção." }
                        output.write(buffer, 0, n)
                    }
                }
            } ?: throw IllegalArgumentException("Não foi possível abrir o SQLite.")

            val db = SQLiteDatabase.openDatabase(temp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            db.use {
                val sections = mutableListOf<LibraryStore.Section>()
                val schema = StringBuilder("Banco SQLite: $name\nModo: somente leitura\n\n")
                val tables = mutableListOf<String>()
                it.rawQuery("SELECT name, sql FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name LIMIT 80", null).use { c ->
                    while (c.moveToNext()) {
                        val table = c.getString(0).orEmpty()
                        val sql = c.getString(1).orEmpty()
                        if (table.isNotBlank()) tables += table
                        schema.appendLine("$table")
                        if (sql.isNotBlank()) schema.appendLine(sql.take(4000))
                        schema.appendLine()
                    }
                }
                sections += LibraryStore.Section("sqlite/esquema", schema.toString())

                tables.take(MAX_SQLITE_SAMPLE_TABLES).forEach { table ->
                    val safe = table.replace("\"", "\"\"")
                    val sample = StringBuilder("Tabela: $table\n")
                    runCatching {
                        it.rawQuery("SELECT * FROM \"$safe\" LIMIT $MAX_SQLITE_SAMPLE_ROWS", null).use { c ->
                            sample.appendLine(c.columnNames.joinToString(" | "))
                            while (c.moveToNext()) {
                                val values = c.columnNames.indices.map { index ->
                                    when (c.getType(index)) {
                                        android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                                        android.database.Cursor.FIELD_TYPE_BLOB -> "<BLOB ${c.getBlob(index)?.size ?: 0} B>"
                                        else -> c.getString(index).orEmpty().replace(Regex("\\s+"), " ").take(240)
                                    }
                                }
                                sample.appendLine(values.joinToString(" | "))
                            }
                        }
                    }
                    sections += LibraryStore.Section("sqlite/$table/amostra", sample.toString())
                }
                return Extraction(sections, "SQLite aberto somente para leitura; nenhuma instrução SQL externa foi executada.")
            }
        } finally {
            temp.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun inspectApk(uri: Uri, size: Long): String {
        if (size > MAX_APK_INSPECT_BYTES) return "APK Inspector: pacote grande; metadados PackageManager omitidos, ZIP ainda será listado com limites."
        val temp = File.createTempFile("unilaw-apk-", ".apk", context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().buffered(256 * 1024).use { output -> input.copyTo(output, 256 * 1024) }
            } ?: return ""
            val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
            val info = context.packageManager.getPackageArchiveInfo(temp.absolutePath, flags) ?: return "APK Inspector: manifesto binário não pôde ser interpretado."
            val app = info.applicationInfo
            val certs = info.signingInfo?.apkContentsSigners.orEmpty()
            val certHashes = certs.map { cert ->
                MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
            }
            return buildString {
                appendLine("APK Inspector")
                appendLine("Pacote: ${info.packageName}")
                appendLine("Versão: ${info.versionName.orEmpty()} (${info.longVersionCode})")
                appendLine("minSdk: ${app?.minSdkVersion ?: 0} • targetSdk: ${app?.targetSdkVersion ?: 0}")
                val perms = info.requestedPermissions.orEmpty().take(80)
                if (perms.isNotEmpty()) appendLine("Permissões: ${perms.joinToString()}")
                certHashes.forEachIndexed { i, hash -> appendLine("Certificado ${i + 1} SHA-256: $hash") }
            }
        } finally {
            temp.delete()
        }
    }

    private suspend fun recognizeLabels(bitmap: Bitmap): String {
        val labels = imageLabeler.process(InputImage.fromBitmap(bitmap, 0)).awaitValue()
            .filter { it.confidence >= 0.55f }
            .sortedByDescending { it.confidence }
            .take(12)
        if (labels.isEmpty()) return ""
        return buildString {
            appendLine("Rótulos visuais locais:")
            labels.forEach { label -> appendLine("- ${label.text}: ${String.format(Locale.ROOT, "%.0f%%", label.confidence * 100f)}") }
        }
    }

'''
s = replace_once(s, '    private fun hash(uri: Uri): String {\n', helpers + '    private fun hash(uri: Uri): String {\n', 'SQLite/APK/vision helpers')

# MIME guesses.
s = replace_once(
    s,
    '''            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".exe") -> "application/vnd.microsoft.portable-executable"
''',
    '''            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            lower.endsWith(".pptx") -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            lower.endsWith(".epub") -> "application/epub+zip"
            lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3") -> "application/vnd.sqlite3"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".exe") -> "application/vnd.microsoft.portable-executable"
''',
    'MIME office/sqlite',
)

s = replace_once(
    s,
    '''    override fun close() {
        recognizer.close()
    }
''',
    '''    override fun close() {
        recognizer.close()
        imageLabeler.close()
    }
''',
    'close labeler',
)

s = replace_once(
    s,
    '''        private const val MAX_BINARY_SCAN_BYTES = 12L * MIB
        private const val MAX_FULL_HASH_BYTES = 512L * MIB
''',
    '''        private const val MAX_BINARY_SCAN_BYTES = 12L * MIB
        private const val MAX_FULL_HASH_BYTES = 512L * MIB
        private const val MAX_SQLITE_BYTES = 256L * MIB
        private const val MAX_SQLITE_SAMPLE_TABLES = 24
        private const val MAX_SQLITE_SAMPLE_ROWS = 5
        private const val MAX_APK_INSPECT_BYTES = 768L * MIB
''',
    'limits sqlite/apk',
)

p.write_text(s, encoding='utf-8')

# Bundle ML Kit image labeling model in APK; no runtime download and no INTERNET required.
g = Path('offlineai/build.gradle')
t = g.read_text(encoding='utf-8')
if "com.google.mlkit:image-labeling:17.0.9" not in t:
    t = replace_once(
        t,
        "    implementation 'com.google.mlkit:text-recognition:16.0.1'\n",
        "    implementation 'com.google.mlkit:text-recognition:16.0.1'\n    implementation 'com.google.mlkit:image-labeling:17.0.9'\n",
        'image labeling dependency',
    )
g.write_text(t, encoding='utf-8')

print('v8 docs/vision patch applied: bundled vision labels, Office/EPUB, SQLite read-only, APK metadata')
