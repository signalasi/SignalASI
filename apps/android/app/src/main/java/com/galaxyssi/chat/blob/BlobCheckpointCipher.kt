package com.galaxyssi.chat.blob

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.galaxyssi.chat.AttachmentLocalStore
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Protects transport keys and bearer tokens, not attachment content. */
internal object BlobCheckpointCipher {
    private val magic = "GSSIBCP1".toByteArray(Charsets.US_ASCII)
    private const val MAX_BYTES = 256 * 1024
    private const val KEY_ALIAS = "galaxyssi_blob_checkpoint_v1"
    data class Metadata(val plaintextLength: Long)

    fun encryptBytes(bytes: ByteArray, destination: File, keyOverride: SecretKey? = null) {
        require(bytes.size <= MAX_BYTES)
        val header = ByteBuffer.allocate(16).put(magic).putLong(bytes.size.toLong()).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, keyOverride ?: key())
            updateAAD(header)
        }
        require(cipher.iv.size == 12)
        val encoded = header + cipher.iv + cipher.doFinal(bytes)
        try { AttachmentLocalStore.storeBytes(encoded, destination) } finally { encoded.fill(0) }
    }

    fun metadata(file: File): Metadata {
        require(file.isFile && file.length() in 44..(MAX_BYTES + 44).toLong())
        val header = ByteArray(16)
        file.inputStream().use { java.io.DataInputStream(it).readFully(header) }
        require(header.copyOfRange(0, 8).contentEquals(magic))
        val size = ByteBuffer.wrap(header, 8, 8).long
        require(size in 0..MAX_BYTES.toLong() && file.length() == size + 44)
        return Metadata(size)
    }

    fun isEncrypted(file: File): Boolean = runCatching { metadata(file) }.isSuccess

    fun decryptBytes(file: File, keyOverride: SecretKey? = null): ByteArray {
        metadata(file)
        val bytes = file.readBytes()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, keyOverride ?: key(), GCMParameterSpec(128, bytes.copyOfRange(16, 28)))
                updateAAD(bytes, 0, 16)
            }
            return cipher.doFinal(bytes, 28, bytes.size - 28)
        } finally { bytes.fill(0) }
    }

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
