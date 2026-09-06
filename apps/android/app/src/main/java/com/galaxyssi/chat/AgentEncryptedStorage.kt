package com.galaxyssi.chat

import android.content.Context
import android.content.SharedPreferences
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
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
        val cacheKey = cacheKey(key)
        AgentEncryptedPreferenceCache.get(cacheKey, raw)
            ?.let { cached -> return cached }
        return (AgentStorageCipher.decrypt(raw, associatedData(key)) ?: defaultValue).also { plaintext ->
            AgentEncryptedPreferenceCache.put(cacheKey, raw, plaintext)
        }
    }

    @Synchronized
    fun writeString(key: String, value: String) {
        val encrypted = AgentStorageCipher.encrypt(value, associatedData(key))
        check(preferences.edit().putString(key, encrypted).commit()) { "Agent encrypted storage write failed" }
        AgentEncryptedPreferenceCache.put(cacheKey(key), encrypted, value)
    }

    @Synchronized
    fun remove(key: String) {
        preferences.edit().remove(key).apply()
        AgentEncryptedPreferenceCache.remove(cacheKey(key))
    }

    @Synchronized
    fun encodedValueLength(key: String): Int = preferences.getString(key, null)?.length ?: 0

    @Synchronized
    fun contains(key: String): Boolean {
        val raw = preferences.getString(key, null) ?: return false
        return AgentStorageCipher.decrypt(raw, associatedData(key)) != null
    }

    @Synchronized
    fun keys(): Set<String> = preferences.all.keys.toSet()

    @Synchronized
    fun clear() {
        preferences.edit().clear().commit()
        AgentEncryptedPreferenceCache.clearNamespace(preferencesName)
    }

    private fun associatedData(key: String): ByteArray = "$preferencesName:$key".toByteArray(Charsets.UTF_8)

    private fun cacheKey(key: String): String = "$preferencesName\u0000$key"

}

internal object AgentEncryptedPreferenceCache {
    private data class CachedValue(
        val encryptedValue: String,
        val plaintext: CharArray,
        val expiresAtNanos: Long
    )

    private val values = linkedMapOf<String, CachedValue>()
    private var clockNanos: () -> Long = System::nanoTime

    @Synchronized
    fun get(cacheKey: String, encryptedValue: String): String? {
        val cached = values[cacheKey] ?: return null
        if (cached.encryptedValue != encryptedValue || cached.expiresAtNanos <= clockNanos()) {
            values.remove(cacheKey)?.wipe()
            return null
        }
        return String(cached.plaintext)
    }

    @Synchronized
    fun put(cacheKey: String, encryptedValue: String, plaintext: String) {
        val now = clockNanos()
        pruneExpired(now)
        values.put(
            cacheKey,
            CachedValue(
                encryptedValue = encryptedValue,
                plaintext = plaintext.toCharArray(),
                expiresAtNanos = now + CACHE_TTL_NANOS
            )
        )?.wipe()
        while (values.size > MAX_CACHE_ENTRIES) {
            val eldest = values.entries.firstOrNull() ?: break
            values.remove(eldest.key)?.wipe()
        }
    }

    @Synchronized
    fun remove(cacheKey: String) {
        values.remove(cacheKey)?.wipe()
    }

    @Synchronized
    fun clearNamespace(namespace: String) {
        val prefix = "$namespace\u0000"
        values.keys.filter { cacheKey -> cacheKey.startsWith(prefix) }
            .forEach { cacheKey -> values.remove(cacheKey)?.wipe() }
    }

    @Synchronized
    fun clearAll() {
        values.values.forEach { cached -> cached.wipe() }
        values.clear()
    }

    @Synchronized
    internal fun clearForTest() {
        clearAll()
        clockNanos = System::nanoTime
    }

    @Synchronized
    internal fun setClockForTest(clock: () -> Long) {
        clearAll()
        clockNanos = clock
    }

    @Synchronized
    internal fun sizeForTest(): Int = values.size

    private fun pruneExpired(now: Long) {
        values.entries
            .filter { (_, cached) -> cached.expiresAtNanos <= now }
            .map(Map.Entry<String, CachedValue>::key)
            .forEach { cacheKey -> values.remove(cacheKey)?.wipe() }
    }

    private fun CachedValue.wipe() {
        plaintext.fill('\u0000')
    }

    private const val MAX_CACHE_ENTRIES = 128
    internal const val CACHE_TTL_NANOS = 30L * 1_000_000_000L
}

internal fun ByteArray.wipeSensitive() {
    fill(0)
}

internal fun ShortArray.wipeSensitive() {
    fill(0)
}

internal fun CharArray.wipeSensitive() {
    fill('\u0000')
}

class AgentEncryptedDatabase(
    context: Context,
    private val databaseName: String
) {
    private val database = sharedDatabase(context.applicationContext, databaseName)

    fun readString(key: String, defaultValue: String): String = synchronized(database) {
        val encrypted = readEncryptedValue(database.readableDatabase, key) ?: return@synchronized defaultValue
        AgentStorageCipher.decrypt(encrypted, associatedData(key)) ?: defaultValue
    }

    fun readStrings(keys: Collection<String>): Map<String, String> = synchronized(database) {
        val requested = keys.distinct()
        if (requested.isEmpty()) return@synchronized emptyMap()
        buildMap {
            requested.forEach { key ->
                readEncryptedValue(database.readableDatabase, key)
                    ?.let { encrypted -> AgentStorageCipher.decrypt(encrypted, associatedData(key)) }
                    ?.let { value -> put(key, value) }
            }
        }
    }

    fun writeString(key: String, value: String) = synchronized(database) {
        val encrypted = AgentStorageCipher.encrypt(value, associatedData(key))
        val values = ContentValues().apply {
            put("storage_key", key)
            put("encrypted_value", encrypted)
        }
        check(database.writableDatabase.insertWithOnConflict(
            TABLE_VALUES, null, values, SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L) { "Agent encrypted database write failed" }
    }

    fun mutateStrings(
        upserts: Map<String, String>,
        removeKeys: Collection<String> = emptyList()
    ): Unit = synchronized(database) {
        if (upserts.isEmpty() && removeKeys.isEmpty()) return@synchronized
        val encryptedValues = upserts.mapValues { (key, value) ->
            AgentStorageCipher.encrypt(value, associatedData(key))
        }
        val writable = database.writableDatabase
        writable.beginTransaction()
        try {
            removeKeys.toSet().forEach { key ->
                writable.delete(TABLE_VALUES, "storage_key = ?", arrayOf(key))
            }
            encryptedValues.forEach { (key, encrypted) ->
                val values = ContentValues().apply {
                    put("storage_key", key)
                    put("encrypted_value", encrypted)
                }
                check(writable.insertWithOnConflict(
                    TABLE_VALUES,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                ) != -1L) { "Agent encrypted database transaction failed" }
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
    }

    fun remove(key: String): Unit = synchronized(database) {
        database.writableDatabase.delete(TABLE_VALUES, "storage_key = ?", arrayOf(key))
        Unit
    }

    fun removeAll(keys: Collection<String>): Unit = synchronized(database) {
        if (keys.isEmpty()) return@synchronized
        val writable = database.writableDatabase
        writable.beginTransaction()
        try {
            keys.forEach { key ->
                writable.delete(TABLE_VALUES, "storage_key = ?", arrayOf(key))
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
    }

    fun clear(): Unit = synchronized(database) {
        database.writableDatabase.delete(TABLE_VALUES, null, null)
        Unit
    }

    fun contains(key: String): Boolean = synchronized(database) {
        database.readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE_VALUES WHERE storage_key = ? LIMIT 1", arrayOf(key)
        ).use { it.moveToFirst() }
    }

    fun keys(prefix: String = ""): List<String> = synchronized(database) {
        val selection = if (prefix.isBlank()) null else "storage_key >= ? AND storage_key < ?"
        val selectionArgs = if (prefix.isBlank()) null else arrayOf(prefix, "$prefix\uffff")
        database.readableDatabase.query(
            TABLE_VALUES,
            arrayOf("storage_key"),
            selection,
            selectionArgs,
            null,
            null,
            "storage_key ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    fun recentKeys(prefix: String, limit: Int): List<String> = synchronized(database) {
        val boundedLimit = limit.coerceAtLeast(0)
        if (boundedLimit == 0) return@synchronized emptyList()
        val selection = if (prefix.isBlank()) null else "storage_key >= ? AND storage_key < ?"
        val selectionArgs = if (prefix.isBlank()) null else arrayOf(prefix, "$prefix\uffff")
        database.readableDatabase.query(
            TABLE_VALUES,
            arrayOf("storage_key"),
            selection,
            selectionArgs,
            null,
            null,
            "rowid DESC",
            boundedLimit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    fun oldestKeys(prefix: String, limit: Int): List<String> = synchronized(database) {
        val boundedLimit = limit.coerceAtLeast(0)
        if (boundedLimit == 0) return@synchronized emptyList()
        val selection = if (prefix.isBlank()) null else "storage_key >= ? AND storage_key < ?"
        val selectionArgs = if (prefix.isBlank()) null else arrayOf(prefix, "$prefix\uffff")
        database.readableDatabase.query(
            TABLE_VALUES,
            arrayOf("storage_key"),
            selection,
            selectionArgs,
            null,
            null,
            "rowid ASC",
            boundedLimit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    fun entries(prefix: String = ""): List<Pair<String, String>> = synchronized(database) {
        buildList {
            keys(prefix).forEach { key ->
                readEncryptedValue(database.readableDatabase, key)
                    ?.let { encrypted -> AgentStorageCipher.decrypt(encrypted, associatedData(key)) }
                    ?.let { value -> add(key to value) }
            }
        }
    }

    /**
     * Connections are process-scoped and shared by database name. Individual
     * repository wrappers can be short-lived without creating leaked pools.
     */
    fun close() = Unit

    private fun associatedData(key: String): ByteArray =
        "database:$databaseName:$key".toByteArray(Charsets.UTF_8)

    private fun readEncryptedValue(database: SQLiteDatabase, key: String): String? {
        val length = database.rawQuery(
            "SELECT length(encrypted_value) FROM $TABLE_VALUES WHERE storage_key = ? LIMIT 1",
            arrayOf(key)
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else return null
        }
        if (length <= CURSOR_TEXT_CHUNK_CHARS) {
            return database.rawQuery(
                "SELECT encrypted_value FROM $TABLE_VALUES WHERE storage_key = ? LIMIT 1",
                arrayOf(key)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }
        val value = StringBuilder(length)
        var offset = 1
        while (offset <= length) {
            val chunk = database.rawQuery(
                "SELECT substr(encrypted_value, ?, ?) FROM $TABLE_VALUES " +
                    "WHERE storage_key = ? LIMIT 1",
                arrayOf(offset.toString(), CURSOR_TEXT_CHUNK_CHARS.toString(), key)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: return null
            value.append(chunk)
            offset += chunk.length
            if (chunk.isEmpty()) return null
        }
        return value.toString()
    }

    private class SharedEncryptedDatabase(
        context: Context,
        databaseName: String
    ) : SQLiteOpenHelper(context, "$databaseName.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_VALUES (" +
                    "storage_key TEXT PRIMARY KEY NOT NULL, " +
                    "encrypted_value TEXT NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val TABLE_VALUES = "encrypted_values"
        const val CURSOR_TEXT_CHUNK_CHARS = 256 * 1024
        val DATABASES = ConcurrentHashMap<String, SharedEncryptedDatabase>()

        fun sharedDatabase(context: Context, databaseName: String): SharedEncryptedDatabase =
            DATABASES.computeIfAbsent(context.getDatabasePath("$databaseName.db").absolutePath) {
                SharedEncryptedDatabase(context.applicationContext, databaseName)
            }
    }
}

object AgentStorageCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "galaxyssi_agent_storage_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Volatile
    private var cachedKey: SecretKey? = null

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

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

    fun decrypt(value: String, associatedData: ByteArray): String? {
        if (!isEncrypted(value)) return null
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
        cachedKey = null
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        runCatching { keyStore.getKey(KEY_ALIAS, null) as? SecretKey }
            .getOrNull()
            ?.let { return it.also { key -> cachedKey = key } }
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
        return generator.generateKey().also { cachedKey = it }
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
