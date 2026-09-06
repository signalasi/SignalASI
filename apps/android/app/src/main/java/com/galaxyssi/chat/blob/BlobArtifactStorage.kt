package com.galaxyssi.chat.blob

import com.galaxyssi.chat.AttachmentLocalStore
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.crypto.SecretKey

/** Worker-side commit into the existing artifact directory, with encrypted immutable metadata. */
internal class BlobArtifactStorage(private val root: File, private val storageKey: SecretKey? = null) {
    fun verified(manifest: JSONObject, checkCurrent: () -> Unit = {}): Boolean {
        val value = BlobArtifactContract.validateManifest(manifest)
        checkCurrent()
        val record = readRecord(value.getString("artifact_uri")) ?: return false
        if (record.getString("transfer_id") != value.getString("transfer_id")) {
            throw BlobFailure("artifact_blob_uri_conflict", 409)
        }
        val file = safeFile("blob-files", value.getString("transfer_id") + ".bin", createParent = false)
        return file.isFile && verifyFile(file, value, checkCurrent)
    }

    fun ingest(staged: BlobStaging, manifest: JSONObject, checkCurrent: () -> Unit = {}): JSONObject {
        val value = BlobArtifactContract.validateManifest(manifest)
        val uri = value.getString("artifact_uri")
        val binding = BlobArtifactContract.binding(value)
        staged.checkBinding(binding)
        if (staged.private.size != value.getLong("size_bytes") ||
            staged.private.plaintextHash != value.getString("sha256")) BlobProtocol.fail("artifact_blob_binding_mismatch")
        val recordPath = recordFile(uri)
        val lockPath = safeFile("blob-locks", key(uri) + ".lock")
        RandomAccessFile(lockPath, "rw").use { lockFile ->
            val lock = try { lockFile.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw BlobFailure("transfer_busy", 409)
            lock.use {
                checkCurrent()
                readRecord(uri)?.let { previous ->
                    if (previous.getString("transfer_id") != value.getString("transfer_id")) {
                        throw BlobFailure("artifact_blob_uri_conflict", 409)
                    }
                    val stored = safeFile("blob-files", value.getString("transfer_id") + ".bin")
                    // A repeated offer must not acknowledge a local copy that has become corrupt.
                    if (stored.isFile && verifyFile(stored, value, checkCurrent)) return previous
                }
                val target = safeFile("blob-files", value.getString("transfer_id") + ".bin")
                val temporary = safeFile("blob-files", ".incoming-" + BlobProtocol.hex(BlobProtocol.randomBytes(16)))
                try {
                    staged.openPlaintext(binding, checkCurrent).use { plaintext ->
                        AttachmentLocalStore.storeStream(plaintext, staged.private.size, temporary)
                    }
                    checkCurrent()
                    Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    val record = JSONObject(value.toString())
                        .put("relative_file", "blob-files/${target.name}")
                        .put("stored_at", System.currentTimeMillis())
                        .put("transport", "encrypted-blob")
                    checkCurrent()
                    writeRecord(recordPath, record)
                    return record
                } finally { temporary.delete() }
            }
        }
    }

    fun readRecord(uri: String): JSONObject? {
        val file = recordFile(uri, createParent = false)
        if (!file.exists()) return null
        val bytes = try {
            if (!file.isFile || file.length() > BlobArtifactContract.MAX_CONTROL_BYTES + 128 ||
                BlobCheckpointCipher.metadata(file).plaintextLength > BlobArtifactContract.MAX_CONTROL_BYTES) {
                throw BlobFailure("artifact_blob_record_invalid", 409)
            }
            BlobCheckpointCipher.decryptBytes(file, storageKey)
        }
            catch (_: javax.crypto.AEADBadTagException) { throw BlobFailure("artifact_blob_record_invalid", 409) }
            catch (_: IllegalArgumentException) { throw BlobFailure("artifact_blob_record_invalid", 409) }
        try {
            val record = JSONObject(bytes.toString(Charsets.UTF_8))
            val metadata = JSONObject(record.toString()).also { copy ->
                listOf("relative_file", "stored_at", "transport", "saved_to_downloads", "saved_uri", "saved_at").forEach(copy::remove)
            }
            val manifest = BlobArtifactContract.validateManifest(metadata)
            if (manifest.getString("artifact_uri") != uri || record.getString("relative_file") !=
                "blob-files/${manifest.getString("transfer_id")}.bin" || record.getString("transport") != "encrypted-blob") {
                throw BlobFailure("artifact_blob_record_invalid", 409)
            }
            return record
        } catch (_: org.json.JSONException) {
            throw BlobFailure("artifact_blob_record_invalid", 409)
        } finally { bytes.fill(0) }
    }

    fun markSaved(uri: String, transferId: String, savedUri: String, savedAt: Long) {
        val lockPath = safeFile("blob-locks", key(uri) + ".lock")
        RandomAccessFile(lockPath, "rw").use { lockFile ->
            val lock = try { lockFile.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw BlobFailure("transfer_busy", 409)
            lock.use {
                val record = readRecord(uri) ?: throw BlobFailure("artifact_blob_record_missing", 404)
                if (record.getString("transfer_id") != transferId) throw BlobFailure("artifact_blob_uri_conflict", 409)
                require(savedUri.startsWith("content://") && savedUri.length <= 2048 && savedAt > 0)
                writeRecord(recordFile(uri), record.put("saved_to_downloads", true)
                    .put("saved_uri", savedUri).put("saved_at", savedAt))
            }
        }
    }

    private fun verifyFile(file: File, manifest: JSONObject, checkCurrent: () -> Unit): Boolean {
        val input = try { AttachmentLocalStore.openInput(file) }
            catch (_: java.io.IOException) { return false }
            catch (_: IllegalArgumentException) { return false }
        try {
            BlobVerifiedInputStream(input, manifest.getLong("size_bytes"), manifest.getString("sha256"), checkCurrent).use { stream ->
                val buffer = ByteArray(64 * 1024)
                try { while (stream.read(buffer) >= 0) { /* Verification drains a bounded buffer. */ } }
                finally { buffer.fill(0) }
            }
            return true
        } catch (error: BlobFailure) {
            if (error.code == "plaintext_hash_mismatch") return false
            throw error
        } catch (_: java.io.IOException) { return false }
          catch (_: IllegalArgumentException) { return false }
    }

    private fun writeRecord(target: File, record: JSONObject) {
        val bytes = record.toString().toByteArray(Charsets.UTF_8)
        val temporary = safeFile("blob-metadata", ".record-" + BlobProtocol.hex(BlobProtocol.randomBytes(16)))
        try {
            BlobCheckpointCipher.encryptBytes(bytes, temporary, storageKey)
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { bytes.fill(0); temporary.delete() }
    }

    private fun recordFile(uri: String, createParent: Boolean = true): File =
        safeFile("blob-metadata", key(uri) + ".sasie", createParent)
    private fun key(uri: String): String = BlobProtocol.hash(uri.toByteArray(Charsets.UTF_8))

    private fun safeFile(directory: String, name: String, createParent: Boolean = true): File {
        val parent = File(root, directory)
        if (Files.isSymbolicLink(root.toPath()) || Files.isSymbolicLink(parent.toPath())) BlobProtocol.fail("invalid_transfer_path")
        if (createParent) check(parent.mkdirs() || parent.isDirectory)
        val target = File(parent, name)
        if (Files.isSymbolicLink(target.toPath()) || target.canonicalFile.parentFile != parent.canonicalFile) {
            BlobProtocol.fail("invalid_transfer_path")
        }
        return target
    }
}
