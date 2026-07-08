package com.signalasi.chat

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.UUID

class AndroidPersistentSignalStore(context: Context) : SignalProtocolStore {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        openPrefs(appContext)
    private val identityKeyPair: IdentityKeyPair
    private val registrationId: Int

    init {
        val identityRaw = prefs.getString(KEY_IDENTITY, null)
        if (identityRaw == null) {
            identityKeyPair = IdentityKeyPair.generate()
            registrationId = KeyHelper.generateRegistrationId(false)
            prefs.edit()
                .putString(KEY_IDENTITY, b64e(identityKeyPair.serialize()))
                .putInt(KEY_REGISTRATION_ID, registrationId)
                .commit()
        } else {
            identityKeyPair = IdentityKeyPair(b64d(identityRaw))
            registrationId = prefs.getInt(KEY_REGISTRATION_ID, KeyHelper.generateRegistrationId(false))
        }
        ensurePreKeyMaterial()
    }

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair

    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val key = addressKey(address)
        val encoded = b64e(identityKey.serialize())
        val identities = readJson(KEY_IDENTITIES)
        val existing = identities.optString(key, "")
        identities.put(key, encoded)
        writeJson(KEY_IDENTITIES, identities)
        return if (existing.isNotBlank() && existing != encoded) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        val existing = readJson(KEY_IDENTITIES).optString(addressKey(address), "")
        return existing.isBlank() || existing == b64e(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val encoded = readJson(KEY_IDENTITIES).optString(addressKey(address), "")
        return if (encoded.isBlank()) null else runCatching { IdentityKey(b64d(encoded)) }.getOrNull()
    }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val encoded = readJson(KEY_PRE_KEYS).optString(preKeyId.toString(), "")
        if (encoded.isBlank()) throw InvalidKeyIdException("No pre-key: $preKeyId")
        return PreKeyRecord(b64d(encoded))
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        putRecord(KEY_PRE_KEYS, preKeyId.toString(), record.serialize())
    }

    override fun containsPreKey(preKeyId: Int): Boolean =
        readJson(KEY_PRE_KEYS).has(preKeyId.toString())

    override fun removePreKey(preKeyId: Int) {
        removeRecord(KEY_PRE_KEYS, preKeyId.toString())
    }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val encoded = readJson(KEY_SESSIONS).optString(addressKey(address), "")
        return if (encoded.isBlank()) SessionRecord() else runCatching { SessionRecord(b64d(encoded)) }.getOrDefault(SessionRecord())
    }

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> {
        val result = mutableListOf<SessionRecord>()
        addresses.forEach { address ->
            if (!containsSession(address)) throw NoSessionException(address, "No session")
            result.add(loadSession(address))
        }
        return result
    }

    override fun getSubDeviceSessions(name: String): MutableList<Int> {
        val prefix = "$name|"
        val sessions = readJson(KEY_SESSIONS)
        return sessions.keys().asSequence()
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).toIntOrNull() }
            .toMutableList()
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        putRecord(KEY_SESSIONS, addressKey(address), record.serialize())
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        readJson(KEY_SESSIONS).has(addressKey(address))

    override fun deleteSession(address: SignalProtocolAddress) {
        removeRecord(KEY_SESSIONS, addressKey(address))
    }

    override fun deleteAllSessions(name: String) {
        val prefix = "$name|"
        val sessions = readJson(KEY_SESSIONS)
        sessions.keys().asSequence().filter { it.startsWith(prefix) }.toList().forEach { sessions.remove(it) }
        writeJson(KEY_SESSIONS, sessions)
    }

    fun deleteIdentity(address: SignalProtocolAddress) {
        val identities = readJson(KEY_IDENTITIES)
        identities.remove(addressKey(address))
        writeJson(KEY_IDENTITIES, identities)
    }

    fun deleteSenderKeys(name: String) {
        val senderKeys = readJson(KEY_SENDER_KEYS)
        val prefix = "$name|"
        senderKeys.keys().asSequence()
            .filter { it.startsWith(prefix) }
            .toList()
            .forEach { senderKeys.remove(it) }
        writeJson(KEY_SENDER_KEYS, senderKeys)
    }

    fun currentBundleJson(name: String, deviceId: Int): JSONObject {
        val preKey = loadPreKey(DEFAULT_PRE_KEY_ID)
        val signedPreKey = loadSignedPreKey(DEFAULT_SIGNED_PRE_KEY_ID)
        val kyberPreKey = loadKyberPreKey(DEFAULT_KYBER_PRE_KEY_ID)
        return JSONObject()
            .put("version", 1)
            .put("scheme", "signal")
            .put("name", name)
            .put("deviceId", deviceId)
            .put("registrationId", registrationId)
            .put("identityKey", b64e(identityKeyPair.publicKey.serialize()))
            .put("preKeyId", DEFAULT_PRE_KEY_ID)
            .put("preKey", b64e(preKey.keyPair.publicKey.serialize()))
            .put("signedPreKeyId", DEFAULT_SIGNED_PRE_KEY_ID)
            .put("signedPreKey", b64e(signedPreKey.keyPair.publicKey.serialize()))
            .put("signedPreKeySignature", b64e(signedPreKey.signature))
            .put("kyberPreKeyId", DEFAULT_KYBER_PRE_KEY_ID)
            .put("kyberPreKey", b64e(kyberPreKey.keyPair.publicKey.serialize()))
            .put("kyberPreKeySignature", b64e(kyberPreKey.signature))
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val encoded = readJson(KEY_SIGNED_PRE_KEYS).optString(signedPreKeyId.toString(), "")
        if (encoded.isBlank()) throw InvalidKeyIdException("No signed pre-key: $signedPreKeyId")
        return SignedPreKeyRecord(b64d(encoded))
    }

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> {
        val records = readJson(KEY_SIGNED_PRE_KEYS)
        return records.keys().asSequence()
            .mapNotNull { runCatching { SignedPreKeyRecord(b64d(records.getString(it))) }.getOrNull() }
            .toMutableList()
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        putRecord(KEY_SIGNED_PRE_KEYS, signedPreKeyId.toString(), record.serialize())
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        readJson(KEY_SIGNED_PRE_KEYS).has(signedPreKeyId.toString())

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        removeRecord(KEY_SIGNED_PRE_KEYS, signedPreKeyId.toString())
    }

    override fun storeSenderKey(address: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        putRecord(KEY_SENDER_KEYS, "${addressKey(address)}|$distributionId", record.serialize())
    }

    override fun loadSenderKey(address: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? {
        val encoded = readJson(KEY_SENDER_KEYS).optString("${addressKey(address)}|$distributionId", "")
        return if (encoded.isBlank()) null else runCatching { SenderKeyRecord(b64d(encoded)) }.getOrNull()
    }

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val encoded = readJson(KEY_KYBER_PRE_KEYS).optString(kyberPreKeyId.toString(), "")
        if (encoded.isBlank()) throw InvalidKeyIdException("No kyber pre-key: $kyberPreKeyId")
        return KyberPreKeyRecord(b64d(encoded))
    }

    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> {
        val records = readJson(KEY_KYBER_PRE_KEYS)
        return records.keys().asSequence()
            .mapNotNull { runCatching { KyberPreKeyRecord(b64d(records.getString(it))) }.getOrNull() }
            .toMutableList()
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        putRecord(KEY_KYBER_PRE_KEYS, kyberPreKeyId.toString(), record.serialize())
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        readJson(KEY_KYBER_PRE_KEYS).has(kyberPreKeyId.toString())

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) = Unit

    private fun ensurePreKeyMaterial() {
        if (!containsPreKey(DEFAULT_PRE_KEY_ID)) {
            storePreKey(DEFAULT_PRE_KEY_ID, PreKeyRecord(DEFAULT_PRE_KEY_ID, ECKeyPair.generate()))
        }
        if (!containsSignedPreKey(DEFAULT_SIGNED_PRE_KEY_ID)) {
            val signedPreKeyPair = ECKeyPair.generate()
            val signature = identityKeyPair.privateKey.calculateSignature(signedPreKeyPair.publicKey.serialize())
            storeSignedPreKey(
                DEFAULT_SIGNED_PRE_KEY_ID,
                SignedPreKeyRecord(DEFAULT_SIGNED_PRE_KEY_ID, System.currentTimeMillis(), signedPreKeyPair, signature)
            )
        }
        if (!containsKyberPreKey(DEFAULT_KYBER_PRE_KEY_ID)) {
            val kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val signature = identityKeyPair.privateKey.calculateSignature(kyberPair.publicKey.serialize())
            storeKyberPreKey(
                DEFAULT_KYBER_PRE_KEY_ID,
                KyberPreKeyRecord(DEFAULT_KYBER_PRE_KEY_ID, System.currentTimeMillis(), kyberPair, signature)
            )
        }
    }

    private fun putRecord(prefKey: String, recordKey: String, bytes: ByteArray) {
        val json = readJson(prefKey)
        json.put(recordKey, b64e(bytes))
        writeJson(prefKey, json)
    }

    private fun removeRecord(prefKey: String, recordKey: String) {
        val json = readJson(prefKey)
        json.remove(recordKey)
        writeJson(prefKey, json)
    }

    private fun readJson(prefKey: String): JSONObject =
        runCatching { JSONObject(prefs.getString(prefKey, "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun writeJson(prefKey: String, json: JSONObject) {
        prefs.edit().putString(prefKey, json.toString()).apply()
    }

    companion object {
        private const val PREFS = "signalasi_signal_store"
        private const val OLD_PREFS = "hermes_signal_store"
        private const val KEY_IDENTITY = "identity_key_pair"
        private const val KEY_REGISTRATION_ID = "registration_id"
        private const val KEY_IDENTITIES = "identities"
        private const val KEY_PRE_KEYS = "pre_keys"
        private const val KEY_SIGNED_PRE_KEYS = "signed_pre_keys"
        private const val KEY_KYBER_PRE_KEYS = "kyber_pre_keys"
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_SENDER_KEYS = "sender_keys"
        private const val DEFAULT_PRE_KEY_ID = 1
        private const val DEFAULT_SIGNED_PRE_KEY_ID = 1
        private const val DEFAULT_KYBER_PRE_KEY_ID = 1

        private fun openPrefs(context: Context): SharedPreferences {
            val oldPrefs = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
            val newPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (oldPrefs.all.isNotEmpty() && newPrefs.all.isEmpty()) {
                val editor = newPrefs.edit()
                oldPrefs.all.forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            editor.putStringSet(key, value as Set<String>)
                        }
                    }
                }
                editor.commit()
            }
            return newPrefs
        }

        private fun addressKey(address: SignalProtocolAddress): String =
            "${address.name}|${address.deviceId}"

        private fun b64e(value: ByteArray): String =
            Base64.encodeToString(value, Base64.NO_WRAP)

        private fun b64d(value: String): ByteArray =
            Base64.decode(value, Base64.DEFAULT)
    }
}
