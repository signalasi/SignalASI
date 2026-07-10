package com.signalasi.chat

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AgentEncryptedPreferences(context: Context, private val preferencesName: String) {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @Synchronized
    fun readString(key: String, defaultValue: String): String {
        val raw = preferences.getString(key, null) ?: return defaultValue
        if (AgentStorageCipher.isEncrypted(raw)) {
            return AgentStorageCipher.decrypt(raw, associatedData(key)) ?: defaultValue
        }
        writeString(key, raw)
        return raw
    }

    @Synchronized
    fun writeString(key: String, value: String) {
        val encrypted = AgentStorageCipher.encrypt(value, associatedData(key))
        preferences.edit().putString(key, encrypted).apply()
    }

    @Synchronized
    fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().commit()
    }

    private fun associatedData(key: String): ByteArray = "$preferencesName:$key".toByteArray(Charsets.UTF_8)
}

object AgentStorageCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "signalasi_agent_storage_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    @Synchronized
    fun encrypt(plaintext: String, associatedData: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData)
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Unexpected Agent storage IV size" }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return buildString {
            append(PREFIX)
            append(iv.toBase64())
            append(':')
            append(ciphertext.toBase64())
        }
    }

    @Synchronized
    fun decrypt(value: String, associatedData: ByteArray): String? {
        if (!isEncrypted(value)) return value
        return runCatching {
            val parts = value.removePrefix(PREFIX).split(':', limit = 2)
            require(parts.size == 2) { "Invalid Agent encrypted storage envelope" }
            val iv = parts[0].fromBase64()
            val ciphertext = parts[1].fromBase64()
            require(iv.size == IV_BYTES) { "Invalid Agent storage IV" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(associatedData)
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun deleteMasterKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        runCatching { keyStore.getKey(KEY_ALIAS, null) as? SecretKey }
            .getOrNull()
            ?.let { return it }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
