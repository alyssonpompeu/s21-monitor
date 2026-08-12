#!/usr/bin/env python3
from pathlib import Path

path = Path('offlineai/src/main/java/com/alysson/offlineai/ArtifactBuilder.kt')
text = path.read_text(encoding='utf-8')

old_import = 'import java.io.FileOutputStream\n'
new_import = 'import java.io.FileOutputStream\nimport java.io.OutputStream\n'
if old_import not in text:
    raise SystemExit('v5.1: ArtifactBuilder import point not found')
text = text.replace(old_import, new_import, 1)

old = '''    private fun rewriteApk(template: File, output: File, html: String) {
        ZipFile(template).use { input ->
            ZipOutputStream(output.outputStream().buffered(1024 * 1024)).use { zip ->
                val entries = input.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (name == "assets/index.html" || name.startsWith("META-INF/")) continue
                    val copy = ZipEntry(name).apply {
                        time = 0L
                        method = ZipEntry.DEFLATED
                    }
                    zip.putNextEntry(copy)
                    if (!entry.isDirectory) input.getInputStream(entry).use { it.copyTo(zip, 256 * 1024) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("assets/index.html").apply { time = 0L })
                zip.write(html.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
'''

new = '''    private fun rewriteApk(template: File, output: File, html: String) {
        ZipFile(template).use { input ->
            val counted = CountingOutputStream(output.outputStream().buffered(1024 * 1024))
            ZipOutputStream(counted).use { zip ->
                val entries = input.entries()
                var resourceTableSeen = false
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (name == "assets/index.html" || name.startsWith("META-INF/")) continue

                    val method = if (entry.method == ZipEntry.STORED) ZipEntry.STORED else ZipEntry.DEFLATED
                    val alignment = when {
                        method != ZipEntry.STORED -> 1
                        name.endsWith(".so", ignoreCase = true) -> 16 * 1024
                        else -> 4
                    }
                    val copy = ZipEntry(name).apply {
                        time = if (entry.time >= DOS_EPOCH_MILLIS) entry.time else DOS_EPOCH_MILLIS
                        this.method = method
                        if (method == ZipEntry.STORED) {
                            size = entry.size
                            compressedSize = entry.size
                            crc = entry.crc
                            extra = alignmentExtra(counted.count, name, alignment)
                        }
                    }
                    zip.putNextEntry(copy)

                    if (method == ZipEntry.STORED) {
                        check(counted.count % alignment == 0L) {
                            "Falha de alinhamento ZIP em $name: offset ${counted.count}, alinhamento $alignment."
                        }
                    }
                    if (name == "resources.arsc") {
                        resourceTableSeen = true
                        check(method == ZipEntry.STORED) {
                            "resources.arsc ficou compactado; o Android recusaria este APK."
                        }
                        check(counted.count % 4L == 0L) {
                            "resources.arsc não está alinhado a 4 bytes; o Android recusaria este APK."
                        }
                    }

                    if (!entry.isDirectory) input.getInputStream(entry).use { it.copyTo(zip, 256 * 1024) }
                    zip.closeEntry()
                }
                check(resourceTableSeen) { "Template Android inválido: resources.arsc ausente." }

                zip.putNextEntry(ZipEntry("assets/index.html").apply { time = DOS_EPOCH_MILLIS })
                zip.write(html.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun alignmentExtra(currentOffset: Long, name: String, alignment: Int): ByteArray? {
        if (alignment <= 1) return null
        val base = currentOffset + LOCAL_FILE_HEADER_SIZE + name.toByteArray(Charsets.UTF_8).size
        val needed = ((alignment - (base % alignment).toInt()) % alignment)
        if (needed == 0) return null

        // ZIP extra fields require a 4-byte id/length header. If the exact padding is smaller,
        // add one whole alignment unit; the resulting data offset remains aligned.
        val total = if (needed >= EXTRA_FIELD_HEADER_SIZE) needed else needed + alignment
        val payload = total - EXTRA_FIELD_HEADER_SIZE
        require(total <= 0xFFFF && payload >= 0) { "Padding ZIP excedeu o limite do formato." }
        return ByteArray(total).also { extra ->
            // Private/unknown extra-field id 0xCAFE; readers ignore it while retaining alignment.
            extra[0] = 0xFE.toByte()
            extra[1] = 0xCA.toByte()
            extra[2] = (payload and 0xFF).toByte()
            extra[3] = ((payload ushr 8) and 0xFF).toByte()
        }
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len.toLong()
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
'''

if old not in text:
    raise SystemExit('v5.1: original rewriteApk block not found')
text = text.replace(old, new, 1)

old_companion = '''        private const val MAX_SOURCE_FILE_CHARS = 180_000
        private const val WINDOWS_MARKER = "\\nIAPAYLOAD1\\n"
'''
new_companion = '''        private const val MAX_SOURCE_FILE_CHARS = 180_000
        private const val LOCAL_FILE_HEADER_SIZE = 30L
        private const val EXTRA_FIELD_HEADER_SIZE = 4
        private const val DOS_EPOCH_MILLIS = 315532800000L
        private const val WINDOWS_MARKER = "\\nIAPAYLOAD1\\n"
'''
if old_companion not in text:
    raise SystemExit('v5.1: companion constants point not found')
text = text.replace(old_companion, new_companion, 1)

path.write_text(text, encoding='utf-8')
print('Builder APK installability fix applied: preserve STORED entries + 4/16K ZIP alignment before signing')
