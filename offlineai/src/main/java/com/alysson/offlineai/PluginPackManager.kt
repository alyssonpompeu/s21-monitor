package com.alysson.offlineai

import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipInputStream

/**
 * Installs signed first-party capability packs without loading executable code into the
 * Workspace process. Packs contain models, templates and prebuilt builder shells.
 * The Ed25519 public key is generated together with each Workspace v3 release and embedded
 * in assets/plugin_pubkey.der. The private signing key never ships in the APK or packs.
 */
class PluginPackManager(private val context: Context) {

    data class PackFile(val path: String, val sha256: String, val size: Long)
    data class InstalledPack(
        val id: String,
        val name: String,
        val version: String,
        val type: String,
        val description: String,
        val dir: File,
        val totalBytes: Long,
    )

    private val root = File(context.filesDir, "iapacks").apply { mkdirs() }

    suspend fun install(uri: Uri, progress: (String) -> Unit): InstalledPack = withContext(Dispatchers.IO) {
        progress("Lendo manifesto do pacote…")
        val header = readHeader(uri)
        verifySignature(header.manifestBytes, header.signature)
        val manifest = parseManifest(header.manifestBytes.toString(Charsets.UTF_8))
        require(manifest.files.isNotEmpty()) { "Pacote sem arquivos úteis." }
        require(manifest.totalBytes <= MAX_EXTRACTED_BYTES) { "Pacote excede o limite de segurança local." }

        val available = StatFs(context.filesDir.absolutePath).availableBytes
        require(available - manifest.totalBytes >= STORAGE_RESERVE_BYTES) {
            "Espaço insuficiente. O app preserva pelo menos ${STORAGE_RESERVE_BYTES / MIB} MiB livres durante a instalação."
        }

        val temp = File(root, ".${manifest.id}.installing")
        temp.deleteRecursively()
        temp.mkdirs()

        val expected = manifest.files.associateBy { it.path }
        val completed = mutableSetOf<String>()
        var extractedTotal = 0L

        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered(1024 * 1024)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        if (name == "manifest.json" || name == "signature.bin" || entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }
                        val meta = expected[name] ?: throw IllegalArgumentException("Arquivo não declarado no manifesto: $name")
                        validatePath(name)
                        val out = File(temp, name)
                        require(out.canonicalPath.startsWith(temp.canonicalPath + File.separator)) { "Caminho inválido no pacote." }
                        out.parentFile?.mkdirs()

                        val digest = MessageDigest.getInstance("SHA-256")
                        var fileBytes = 0L
                        val buffer = ByteArray(1024 * 1024)
                        out.outputStream().buffered(1024 * 1024).use { output ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                fileBytes += read
                                extractedTotal += read
                                require(fileBytes <= meta.size) { "Arquivo maior que o declarado: $name" }
                                require(extractedTotal <= manifest.totalBytes) { "Pacote expandiu além do tamanho declarado." }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                if (extractedTotal % (64L * MIB) < read) {
                                    progress("Instalando ${manifest.name} • ${extractedTotal / MIB}/${manifest.totalBytes / MIB} MiB")
                                }
                            }
                        }
                        require(fileBytes == meta.size) { "Tamanho incorreto em $name." }
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        require(actual.equals(meta.sha256, ignoreCase = true)) { "SHA-256 inválido em $name." }
                        completed += name
                        zip.closeEntry()
                    }
                }
            } ?: throw IllegalArgumentException("Não foi possível abrir o pacote selecionado.")

            require(completed == expected.keys) { "Pacote incompleto: faltam arquivos declarados." }
            File(temp, "manifest.json").writeBytes(header.manifestBytes)
            File(temp, "signature.bin").writeBytes(header.signature)

            val target = File(root, manifest.id)
            val backup = File(root, ".${manifest.id}.backup")
            backup.deleteRecursively()
            if (target.exists()) {
                require(target.renameTo(backup)) { "Não foi possível preparar a atualização do plugin." }
            }
            if (!temp.renameTo(target)) {
                backup.renameTo(target)
                error("Não foi possível concluir a instalação do plugin.")
            }
            backup.deleteRecursively()
            progress("${manifest.name} instalado e verificado.")
            installedPack(target) ?: error("Manifesto instalado inválido.")
        } catch (t: Throwable) {
            temp.deleteRecursively()
            throw t
        }
    }

    fun listInstalled(): List<InstalledPack> = root.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        ?.mapNotNull { installedPack(it) }
        ?.sortedBy { it.name.lowercase() }
        ?.toList()
        .orEmpty()

    fun isInstalled(id: String): Boolean = installedPack(File(root, id)) != null

    fun payloadFile(packId: String, relativePath: String): File? {
        validatePath("payload/$relativePath")
        val dir = File(root, packId)
        if (installedPack(dir) == null) return null
        val file = File(dir, "payload/$relativePath")
        return file.takeIf { it.isFile && it.canonicalPath.startsWith(dir.canonicalPath + File.separator) }
    }

    fun remove(packId: String): Boolean {
        val target = File(root, packId)
        if (!target.exists()) return true
        return target.deleteRecursively()
    }

    fun coderModel(): File? = payloadFile(CODER_PACK_ID, "coder.gguf")
        ?.takeIf { it.length() in MIN_CODER_BYTES..MAX_CODER_BYTES }

    private data class Header(val manifestBytes: ByteArray, val signature: ByteArray)
    private data class ParsedManifest(
        val id: String,
        val name: String,
        val version: String,
        val type: String,
        val description: String,
        val files: List<PackFile>,
        val totalBytes: Long,
    )

    private fun readHeader(uri: Uri): Header {
        var manifest: ByteArray? = null
        var signature: ByteArray? = null
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered(256 * 1024)).use { zip ->
                var seen = 0
                while (seen < 8) {
                    val entry = zip.nextEntry ?: break
                    seen++
                    when (entry.name) {
                        "manifest.json" -> manifest = zip.readLimited(MAX_MANIFEST_BYTES)
                        "signature.bin" -> signature = zip.readLimited(MAX_SIGNATURE_BYTES)
                    }
                    zip.closeEntry()
                    if (manifest != null && signature != null) break
                }
            }
        } ?: throw IllegalArgumentException("Não foi possível abrir o pacote.")
        return Header(
            manifest ?: throw IllegalArgumentException("manifest.json ausente."),
            signature ?: throw IllegalArgumentException("signature.bin ausente."),
        )
    }

    private fun verifySignature(manifest: ByteArray, signatureBytes: ByteArray) {
        val publicKeyBytes = context.assets.open(PUBLIC_KEY_ASSET).use { it.readBytes() }
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(key)
        verifier.update(manifest)
        require(verifier.verify(signatureBytes)) { "Assinatura do pacote inválida. Instalação recusada." }
    }

    private fun parseManifest(text: String): ParsedManifest {
        val json = JSONObject(text)
        require(json.getInt("schema") == 1) { "Versão de pacote não suportada." }
        val id = json.getString("id")
        require(ID_REGEX.matches(id)) { "Identificador de plugin inválido." }
        val filesJson = json.getJSONArray("files")
        require(filesJson.length() in 1..MAX_FILES) { "Quantidade de arquivos inválida." }
        val files = ArrayList<PackFile>(filesJson.length())
        var total = 0L
        for (i in 0 until filesJson.length()) {
            val item = filesJson.getJSONObject(i)
            val path = item.getString("path")
            validatePath(path)
            require(path.startsWith("payload/")) { "Arquivos do plugin devem ficar em payload/." }
            val sha = item.getString("sha256").lowercase()
            require(SHA_REGEX.matches(sha)) { "SHA-256 inválido no manifesto." }
            val size = item.getLong("size")
            require(size in 0..MAX_SINGLE_FILE_BYTES) { "Tamanho de arquivo inválido." }
            total += size
            require(total <= MAX_EXTRACTED_BYTES) { "Pacote grande demais." }
            files += PackFile(path, sha, size)
        }
        return ParsedManifest(
            id = id,
            name = json.getString("name").take(80),
            version = json.getString("version").take(40),
            type = json.getString("type").take(60),
            description = json.optString("description").take(300),
            files = files,
            totalBytes = total,
        )
    }

    private fun installedPack(dir: File): InstalledPack? = runCatching {
        if (!dir.isDirectory) return@runCatching null
        val file = File(dir, "manifest.json")
        if (!file.isFile || file.length() > MAX_MANIFEST_BYTES) return@runCatching null
        val parsed = parseManifest(file.readText())
        if (parsed.id != dir.name) return@runCatching null
        parsed.files.forEach { meta ->
            val payload = File(dir, meta.path)
            if (!payload.isFile || payload.length() != meta.size) return@runCatching null
        }
        InstalledPack(parsed.id, parsed.name, parsed.version, parsed.type, parsed.description, dir, parsed.totalBytes)
    }.getOrNull()

    private fun validatePath(path: String) {
        require(path.isNotBlank() && path.length <= 180) { "Caminho inválido." }
        require(!path.startsWith('/') && !path.contains("\\") && !path.split('/').any { it == ".." || it.isBlank() }) {
            "Caminho inseguro no pacote."
        }
    }

    private fun ZipInputStream.readLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            require(total <= limit) { "Cabeçalho de pacote grande demais." }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        const val BUILDER_PACK_ID = "dev.builder.core"
        const val CODER_PACK_ID = "coder.qwen25.1_5b"
        private const val PUBLIC_KEY_ASSET = "plugin_pubkey.der"
        private const val MIB = 1024L * 1024L
        private const val STORAGE_RESERVE_BYTES = 768L * MIB
        private const val MAX_EXTRACTED_BYTES = 1_600L * MIB
        private const val MAX_SINGLE_FILE_BYTES = 1_400L * MIB
        private const val MIN_CODER_BYTES = 850L * MIB
        private const val MAX_CODER_BYTES = 1_300L * MIB
        private const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val MAX_SIGNATURE_BYTES = 1024
        private const val MAX_FILES = 64
        private val ID_REGEX = Regex("[a-z0-9][a-z0-9._-]{2,80}")
        private val SHA_REGEX = Regex("[0-9a-f]{64}")
    }
}
