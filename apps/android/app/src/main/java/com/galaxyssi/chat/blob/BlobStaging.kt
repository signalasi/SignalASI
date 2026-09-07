package com.galaxyssi.chat.blob

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey

/** Worker-owned ciphertext staging. No plaintext attachment or capability is written unencrypted. */
internal class BlobStaging private constructor(
    val directory: File,
    val private: BlobPrivate,
    val chunks: List<BlobChunk>,
    var remote: JSONObject,
    private val storageKey: SecretKey?
) : AutoCloseable {
    val manifest: JSONObject get() = BlobProtocol.manifest(chunks)
    private var locked = false

    fun checkBinding(binding: Map<String, String>) {
        private.checkOpen()
        if (private.bindingHash != BlobProtocol.bindingHash(binding)) BlobProtocol.fail("transfer_binding_mismatch")
    }

    fun <T> exclusive(block: () -> T): T {
        private.checkOpen()
        val lockFile = File(directory, "transfer.lock")
        if (Files.isSymbolicLink(lockFile.toPath())) BlobProtocol.fail("invalid_transfer_lock")
        RandomAccessFile(lockFile, "rw").use { file ->
            val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw BlobFailure("transfer_busy", 409)
            lock.use {
                openUnchecked(directory, storageKey).use { current ->
                    if (current.private.json().toString() != private.json().toString() || current.chunks != chunks) {
                        BlobProtocol.fail("transfer_checkpoint_changed")
                    }
                    remote = JSONObject(current.remote.toString())
                }
                locked = true
                try { return block() } finally { locked = false }
            }
        }
    }

    fun save() {
        check(locked) { "Blob checkpoint update requires transfer ownership" }
        writeCheckpoint()
    }

    private fun writeCheckpoint() {
        private.checkOpen()
        val bytes = JSONObject().put("private", private.json()).put("manifest", manifest)
            .put("remote", remote).toString().toByteArray(Charsets.UTF_8)
        val temporary = File(directory, ".checkpoint-${BlobProtocol.hex(BlobProtocol.randomBytes(8))}")
        try {
            if (bytes.size > MAX_CHECKPOINT_BYTES) BlobProtocol.fail("transfer_checkpoint_too_large")
            // The storage helper commits to a fresh file first. Replacing an old
            // checkpoint is one atomic move, never delete-then-rename.
            BlobCheckpointCipher.encryptBytes(bytes, temporary, storageKey)
            Files.move(temporary.toPath(), File(directory, STATE_FILE).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            bytes.fill(0)
            temporary.delete()
        }
    }

    private fun chunkFile(index: Int): File {
        if (index !in chunks.indices) BlobProtocol.fail("invalid_chunk_index")
        return File(directory, index.toString().padStart(8, '0') + ".blob")
    }

    fun readChunk(index: Int): ByteArray {
        val file = chunkFile(index)
        val expected = chunks[index]
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() != expected.size.toLong()) {
            throw BlobFailure("local_chunk_missing_or_corrupt", 409)
        }
        val bytes = file.inputStream().use { readBounded(it, expected.size) }
        if (bytes.size != expected.size || BlobProtocol.hash(bytes) != expected.sha256) {
            bytes.fill(0)
            throw BlobFailure("local_chunk_missing_or_corrupt", 409)
        }
        return bytes
    }

    fun hasChunk(index: Int): Boolean = try {
        readChunk(index).fill(0)
        true
    } catch (_: java.io.IOException) { false } catch (_: BlobFailure) { false }

    fun storeChunk(index: Int, bytes: ByteArray) {
        check(locked) { "Blob writes require transfer ownership" }
        val expected = chunks.getOrNull(index) ?: BlobProtocol.fail("invalid_chunk_index")
        if (bytes.size != expected.size || BlobProtocol.hash(bytes) != expected.sha256) {
            throw BlobFailure("ciphertext_hash_mismatch", 409)
        }
        atomicWrite(chunkFile(index), bytes)
    }

    /** The caller must not publish its output until this method returns successfully. */
    fun copyPlaintext(output: OutputStream, binding: Map<String, String>, checkCancelled: () -> Unit = {}): Long {
        checkBinding(binding)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        chunks.indices.forEach { index ->
            checkCancelled()
            val encrypted = readChunk(index)
            val plain = try { BlobProtocol.crypt(private, index, encrypted, false) }
                catch (_: AEADBadTagException) { throw BlobFailure("chunk_authentication_failed", 409) }
                finally { encrypted.fill(0) }
            try {
                digest.update(plain)
                output.write(plain)
                total += plain.size
            } finally { plain.fill(0) }
        }
        if (total != private.size || BlobProtocol.hex(digest.digest()) != private.plaintextHash) {
            throw BlobFailure("plaintext_hash_mismatch", 409)
        }
        return total
    }

    /** Bounded plaintext bridge to encrypted local storage; consume through EOF before committing. */
    fun openPlaintext(binding: Map<String, String>, checkCancelled: () -> Unit = {}): InputStream {
        checkBinding(binding)
        val load: (Int) -> ByteArray = { index ->
            private.checkOpen()
            checkCancelled()
            val encrypted = readChunk(index)
            try { BlobProtocol.crypt(private, index, encrypted, false) }
            catch (_: AEADBadTagException) { throw BlobFailure("chunk_authentication_failed", 409) }
            finally { encrypted.fill(0) }
        }
        val input = if (private.size == 0L) java.io.ByteArrayInputStream(load(0)) else
            BlobChunkInputStream(chunks.size, private.size, load)
        return BlobVerifiedInputStream(input, private.size, private.plaintextHash, checkCancelled)
    }

    override fun close() {
        private.close()
        remote = JSONObject()
    }

    companion object {
        const val STATE_FILE = "transfer.sasie"
        private const val MAX_CHECKPOINT_BYTES = 256 * 1024

        fun exists(directory: File): Boolean = File(directory, STATE_FILE).isFile

        fun prepare(directory: File, size: Long, hash: String, binding: Map<String, String>,
            source: () -> InputStream, storageKey: SecretKey? = null, checkCancelled: () -> Unit = {}): BlobStaging {
            if (size !in 0..BlobProtocol.MAX_FILE_BYTES) BlobProtocol.fail("file_too_large")
            val bindingHash = BlobProtocol.bindingHash(binding)
            BlobProtocol.unhex(hash, 32).fill(0)
            val private = BlobPrivate(BlobProtocol.hex(BlobProtocol.randomBytes(16)), BlobProtocol.randomBytes(32),
                BlobProtocol.randomBytes(8), size, hash, bindingHash, "")
            try {
                // Never reuse a preparation key after interruption or a source change.
                Files.createDirectory(directory.toPath())
                val chunks = mutableListOf<BlobChunk>()
                val digest = MessageDigest.getInstance("SHA-256")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val plain = ByteArray(minOf(size, BlobProtocol.CHUNK_BYTES.toLong()).toInt())
                val encrypted = ByteArray(plain.size + BlobProtocol.TAG_BYTES)
                try { source().use { input ->
                    val count = maxOf(1L, (size + BlobProtocol.CHUNK_BYTES - 1) / BlobProtocol.CHUNK_BYTES).toInt()
                    repeat(count) { index ->
                        checkCancelled()
                        val expected = minOf(BlobProtocol.CHUNK_BYTES.toLong(), size - index.toLong() * BlobProtocol.CHUNK_BYTES).toInt()
                        try {
                            readExact(input, plain, expected)
                            digest.update(plain, 0, expected)
                            val length = BlobProtocol.cryptInto(private, index, plain, expected, encrypted, true, cipher)
                            atomicWrite(File(directory, index.toString().padStart(8, '0') + ".blob"), encrypted, length)
                            chunks.add(BlobChunk(BlobProtocol.hash(encrypted, length), length))
                        } finally { plain.fill(0); encrypted.fill(0) }
                    }
                    if (input.read() != -1 || BlobProtocol.hex(digest.digest()) != hash) BlobProtocol.fail("source_changed")
                } } finally { plain.fill(0); encrypted.fill(0) }
                private.manifestHash = BlobProtocol.hash(BlobProtocol.canonical(BlobProtocol.manifest(chunks)))
                return BlobStaging(directory, private, chunks, JSONObject(), storageKey).also { it.writeCheckpoint() }
            } catch (error: Exception) {
                private.close()
                throw error
            }
        }

        fun receive(directory: File, descriptor: JSONObject, manifest: JSONObject, remote: JSONObject,
            binding: Map<String, String>, storageKey: SecretKey? = null): BlobStaging {
            val private = BlobPrivate.parse(descriptor, manifest)
            try {
                val staged = BlobStaging(directory, private, BlobProtocol.parseManifest(manifest), remote, storageKey)
                staged.checkBinding(binding)
                Files.createDirectory(directory.toPath())
                staged.writeCheckpoint()
                return staged
            } catch (error: Exception) { private.close(); throw error }
        }

        fun open(directory: File, binding: Map<String, String>, storageKey: SecretKey? = null): BlobStaging =
            openUnchecked(directory, storageKey).also { staged ->
                try { staged.checkBinding(binding) } catch (error: Exception) { staged.close(); throw error }
            }

        private fun openUnchecked(directory: File, storageKey: SecretKey?): BlobStaging {
            val file = File(directory, STATE_FILE)
            if (Files.isSymbolicLink(directory.toPath()) || Files.isSymbolicLink(file.toPath()) ||
                !file.isFile || file.length() > MAX_CHECKPOINT_BYTES + 128 ||
                BlobCheckpointCipher.metadata(file).plaintextLength > MAX_CHECKPOINT_BYTES) {
                BlobProtocol.fail("invalid_transfer_checkpoint")
            }
            val bytes = BlobCheckpointCipher.decryptBytes(file, storageKey)
            try {
                val state = JSONObject(bytes.toString(Charsets.UTF_8))
                BlobProtocol.keys(state, setOf("private", "manifest", "remote"))
                val manifest = state.getJSONObject("manifest")
                val chunks = BlobProtocol.parseManifest(manifest)
                val remote = state.getJSONObject("remote")
                return BlobStaging(directory, BlobPrivate.parse(state.getJSONObject("private"), manifest),
                    chunks, remote, storageKey)
            } catch (_: org.json.JSONException) {
                throw BlobFailure("invalid_transfer_checkpoint", 409)
            } finally { bytes.fill(0) }
        }

        private fun atomicWrite(file: File, bytes: ByteArray, length: Int = bytes.size) {
            require(length in 0..bytes.size)
            val temporary = File(file.parentFile, ".${file.name}-${BlobProtocol.hex(BlobProtocol.randomBytes(8))}")
            try {
                FileOutputStream(temporary).use { output -> output.write(bytes, 0, length); output.fd.sync() }
                Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { temporary.delete() }
        }

        internal fun readBounded(input: InputStream, limit: Int): ByteArray {
            val buffer = ByteArray(limit + 1)
            try {
                var offset = 0
                while (offset < buffer.size) {
                    val count = input.read(buffer, offset, buffer.size - offset)
                    if (count < 0) break
                    if (count == 0) {
                        val one = input.read()
                        if (one < 0) break
                        buffer[offset++] = one.toByte()
                    } else offset += count
                }
                if (offset > limit) BlobProtocol.fail("body_too_large")
                return buffer.copyOf(offset)
            } finally { buffer.fill(0) }
        }

        private fun readExact(input: InputStream, buffer: ByteArray, size: Int) {
            try {
                var offset = 0
                while (offset < size) {
                    val count = input.read(buffer, offset, size - offset)
                    if (count < 0) BlobProtocol.fail("source_changed")
                    if (count == 0) {
                        val one = input.read()
                        if (one < 0) BlobProtocol.fail("source_changed")
                        buffer[offset++] = one.toByte()
                    } else offset += count
                }
            } catch (error: Exception) { buffer.fill(0); throw error }
        }
    }
}
