package com.signalasi.chat

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class AgentSkillPackageInspection(
    val manifest: AgentSkillManifest,
    val entries: Set<String>,
    val packageBytes: Long,
    val integrityVerified: Boolean,
    val signer: String = ""
)

class AgentSkillPackageException(message: String) : IllegalArgumentException(message)

object AgentSkillPackageExporter {
    fun export(manifest: AgentSkillManifest): ByteArray {
        val rawManifest = AgentSkillManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)
        val integrity = JSONObject()
            .put("manifest_sha256", sha256(rawManifest))
            .put("signer", "SignalASI local export")
            .toString(2)
            .toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(AgentSkillPackageInstaller.MANIFEST_FILE))
                zip.write(rawManifest)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(AgentSkillPackageInstaller.INTEGRITY_FILE))
                zip.write(integrity)
                zip.closeEntry()
            }
            output.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

class AgentSkillPackageInstaller(private val runtime: AgentSkillRuntime) {
    fun inspect(input: InputStream): AgentSkillPackageInspection {
        val archive = input.readBounded(MAX_PACKAGE_BYTES)
        val entries = linkedMapOf<String, ByteArray>()
        var expandedBytes = 0L
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = normalizeEntry(entry.name)
                if (entry.isDirectory) continue
                if (entries.size >= MAX_ENTRIES) throw AgentSkillPackageException("Skill package contains too many files")
                rejectExecutable(name)
                if (entries.containsKey(name)) throw AgentSkillPackageException("Duplicate Skill package entry: $name")
                val content = zip.readBounded(MAX_ENTRY_BYTES)
                expandedBytes += content.size
                if (expandedBytes > MAX_EXPANDED_BYTES) throw AgentSkillPackageException("Expanded Skill package exceeds its byte limit")
                entries[name] = content
            }
        }
        val rawManifest = entries[MANIFEST_FILE]?.toString(Charsets.UTF_8)
            ?: throw AgentSkillPackageException("Skill package is missing $MANIFEST_FILE")
        val manifest = AgentSkillManifestCodec.decode(rawManifest)
            ?: throw AgentSkillPackageException("Skill manifest is malformed")
        val validation = runtime.validate(manifest)
        if (!validation.isValid) throw AgentSkillValidationException(validation)
        if (manifest.source !in ALLOWED_PACKAGE_SOURCES) {
            throw AgentSkillPackageException("Third-party Skill package declares an invalid installation source")
        }

        val integrity = entries[INTEGRITY_FILE]?.toString(Charsets.UTF_8)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrElse { throw AgentSkillPackageException("Integrity document is malformed") }
        }
        val expectedHash = integrity?.optString("manifest_sha256").orEmpty().lowercase(Locale.ROOT)
        val actualHash = sha256(rawManifest.toByteArray(Charsets.UTF_8))
        if (expectedHash.isNotBlank() && expectedHash != actualHash) {
            throw AgentSkillPackageException("Skill manifest integrity check failed")
        }
        return AgentSkillPackageInspection(
            manifest = manifest,
            entries = entries.keys,
            packageBytes = archive.size.toLong(),
            integrityVerified = expectedHash.isNotBlank(),
            signer = integrity?.optString("signer").orEmpty()
        )
    }

    fun install(input: InputStream, allowUnsignedLocalPackage: Boolean = false): AgentSkillInstallation {
        val inspected = inspect(input)
        if (!inspected.integrityVerified && !allowUnsignedLocalPackage) {
            throw AgentSkillPackageException("Unsigned Skill package requires explicit local-install approval")
        }
        return runtime.install(inspected.manifest, enabled = false)
    }

    private fun normalizeEntry(rawName: String): String {
        val name = rawName.replace('\\', '/').trim().removePrefix("./")
        if (name.isBlank() || name.startsWith('/') || DRIVE_PREFIX.matches(name) ||
            name.split('/').any { it.isBlank() || it == "." || it == ".." } ||
            name.contains('%')) {
            throw AgentSkillPackageException("Unsafe Skill package path: $rawName")
        }
        return name
    }

    private fun rejectExecutable(name: String) {
        val lower = name.lowercase(Locale.ROOT)
        if (EXECUTABLE_EXTENSIONS.any(lower::endsWith)) {
            throw AgentSkillPackageException("Executable content is not allowed in declarative Skill packages: $name")
        }
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw AgentSkillPackageException("Skill package content exceeds its byte limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val INTEGRITY_FILE = "integrity.json"
        const val MAX_PACKAGE_BYTES = 16 * 1024 * 1024
        const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 24L * 1024L * 1024L
        const val MAX_ENTRIES = 128
        private val DRIVE_PREFIX = Regex("^[A-Za-z]:.*")
        private val EXECUTABLE_EXTENSIONS = setOf(
            ".exe", ".dll", ".so", ".dylib", ".apk", ".jar", ".dex", ".class",
            ".sh", ".bash", ".cmd", ".bat", ".ps1", ".py", ".pyc", ".js", ".mjs",
            ".ts", ".kt", ".kts", ".java", ".rb", ".php", ".pl", ".lua", ".wasm"
        )
        private val ALLOWED_PACKAGE_SOURCES = setOf("third_party", "official_store", "repository", "url", "conversation")
    }
}
