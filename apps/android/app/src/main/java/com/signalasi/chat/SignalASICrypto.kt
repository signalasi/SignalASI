package com.signalasi.chat

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.security.MessageDigest

object SignalASICrypto {
    private const val TAG = "SignalASICrypto"
    private const val LOCAL_NAME = "android"
    private const val REMOTE_NAME = "pc"
    private const val REMOTE_DEVICE_ID = 1
    private const val DEFAULT_DEVICE_ID = 1
    private const val PREFS = "signalasi_signal_trust"
    private const val OLD_PREFS = "hermes_signal_trust"
    private const val SIGNAL_STORE_PREFS = "signalasi_signal_store"
    private const val OLD_SIGNAL_STORE_PREFS = "hermes_signal_store"
    private const val KEY_VERIFIED_PC_SHA256 = "verified_pc_identity_sha256"

    private val remoteAddress = SignalProtocolAddress(REMOTE_NAME, REMOTE_DEVICE_ID)
    private lateinit var appContext: Context
    private lateinit var store: AndroidPersistentSignalStore

    @Volatile private var hasPcBundle = false
    @Volatile private var pendingPcBundle: JSONObject? = null

    fun initialize(context: Context) {
        if (::store.isInitialized) return
        appContext = context.applicationContext
        migrateSharedPreferences(appContext, OLD_PREFS, PREFS)
        migrateSharedPreferences(appContext, OLD_SIGNAL_STORE_PREFS, SIGNAL_STORE_PREFS)
        store = AndroidPersistentSignalStore(appContext)
        Log.i(TAG, "Persistent Signal store initialized")
    }

    fun isReady(): Boolean = hasPcBundle

    fun verifiedPcFingerprint(): String =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VERIFIED_PC_SHA256, "")
            .orEmpty()

    fun verifiedDesktopFingerprint(desktopId: String): String =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("verified_desktop_identity_sha256_$desktopId", "")
            .orEmpty()

    fun debugSetVerifiedPcFingerprint(context: Context, fingerprint: String) {
        initialize(context.applicationContext)
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            Log.w(TAG, "Debug PC identity seed ignored in non-debuggable build")
            return
        }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VERIFIED_PC_SHA256, fingerprint)
            .apply()
        Log.i(TAG, "Debug PC identity seeded. sha256=${fingerprint.take(16)}")
    }

    @Synchronized
    fun clearPcTrust(context: Context) {
        initialize(context.applicationContext)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_VERIFIED_PC_SHA256)
            .apply()
        store.deleteSession(remoteAddress)
        store.deleteAllSessions(REMOTE_NAME)
        store.deleteIdentity(remoteAddress)
        hasPcBundle = false
        pendingPcBundle = null
        Log.i(TAG, "PC Signal trust and sessions cleared")
    }

    @Synchronized
    fun clearPeerTrust(context: Context, contactId: String) {
        if (contactId.isBlank() || contactId == "hermes") return
        initialize(context.applicationContext)
        val address = SignalProtocolAddress(contactId, DEFAULT_DEVICE_ID)
        store.deleteSession(address)
        store.deleteAllSessions(contactId)
        store.deleteIdentity(address)
        store.deleteSenderKeys(contactId)
        Log.i(TAG, "Peer Signal trust and sessions cleared contact=$contactId")
    }

    @Synchronized
    fun resetLocalIdentity(context: Context) {
        appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        appContext.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        appContext.getSharedPreferences(SIGNAL_STORE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        appContext.getSharedPreferences(OLD_SIGNAL_STORE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = AndroidPersistentSignalStore(appContext)
        hasPcBundle = false
        pendingPcBundle = null
        Log.i(TAG, "Local Signal identity reset")
    }

    fun localIdentityPublicKey(): String {
        ensureInitialized()
        return b64e(store.identityKeyPair.publicKey.serialize())
    }

    fun localIdentitySha256(): String {
        ensureInitialized()
        return sha256Hex(store.identityKeyPair.publicKey.serialize())
    }

    fun localSignalasiId(): String = "signalasi:${localIdentitySha256().take(16)}"

    fun localHermesId(): String = localSignalasiId()

    fun localSignalBundleJson(): JSONObject {
        ensureInitialized()
        return store.currentBundleJson(localSignalasiId(), DEFAULT_DEVICE_ID)
            .put("identityKeySha256", localIdentitySha256())
    }

    fun exportSignalStoreJson(context: Context): JSONObject {
        initialize(context.applicationContext)
        val prefs = context.applicationContext.getSharedPreferences(SIGNAL_STORE_PREFS, Context.MODE_PRIVATE)
        val root = JSONObject()
        for ((key, value) in prefs.all) {
            when (value) {
                is String -> root.put(key, value)
                is Int -> root.put(key, value)
                is Long -> root.put(key, value)
                is Boolean -> root.put(key, value)
            }
        }
        return root
            .put("exported_at", System.currentTimeMillis())
            .put("local_signalasi_id", localSignalasiId())
            .put("local_identity_sha256", localIdentitySha256())
    }

    fun importSignalStoreJson(context: Context, json: JSONObject) {
        val editor = context.applicationContext
            .getSharedPreferences(SIGNAL_STORE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
        json.keys().forEach { key ->
            if (key == "exported_at" || key == "local_signalasi_id" || key == "local_hermes_id" || key == "local_identity_sha256") return@forEach
            val value = json.opt(key)
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.apply()
        appContext = context.applicationContext
        store = AndroidPersistentSignalStore(appContext)
        hasPcBundle = false
    }

    fun verifyPcIdentityFromQr(contents: String): Boolean {
        ensureInitialized()
        val json = runCatching { JSONObject(contents) }.getOrNull() ?: return false
        if (json.optString("type") != "signalasi_verify") return false
        if (json.optString("device") != REMOTE_NAME) return false
        val identityKey = json.optString("identity_key")
        val declaredHash = json.optString("identity_key_sha256")
        if (identityKey.isBlank() || declaredHash.isBlank()) return false
        val computed = sha256Hex(b64d(identityKey))
        if (!computed.equals(declaredHash, ignoreCase = true)) return false
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VERIFIED_PC_SHA256, computed)
            .putString("verified_desktop_identity_sha256_${desktopIdFromQr(json)}", computed)
            .apply()
        json.optJSONObject("signal_bundle")?.let { bundle ->
            processPcBundleForDesktop(desktopIdFromQr(json), bundle, computed)
        }
        Log.i(TAG, "PC identity verified by QR. sha256=${computed.take(16)}")
        return true
    }

    fun desktopIdFromQr(json: JSONObject): String {
        val declared = json.optString("desktop_id")
        if (declared.isNotBlank()) return declared
        val fingerprint = json.optString("identity_key_sha256")
            .ifBlank { json.optString("identity_fingerprint") }
        return "desktop_${fingerprint.take(16)}"
    }

    @Synchronized
    fun processPeerBundle(bundleJson: JSONObject, expectedHermesId: String, expectedFingerprint: String): Boolean {
        ensureInitialized()
        return try {
            val remoteName = bundleJson.optString("name", expectedHermesId)
            val deviceId = bundleJson.optInt("deviceId", DEFAULT_DEVICE_ID)
            val bundleHash = pcBundleHash(bundleJson)
            if (remoteName != expectedHermesId) {
                Log.e(TAG, "Peer Signal name mismatch. expected=$expectedHermesId got=$remoteName")
                return false
            }
            if (expectedFingerprint.isNotBlank() && !bundleHash.equals(expectedFingerprint, ignoreCase = true)) {
                Log.e(TAG, "Peer Signal identity mismatch. expected=${expectedFingerprint.take(16)} got=${bundleHash.take(16)}")
                return false
            }
            val bundle = PreKeyBundle(
                bundleJson.getInt("registrationId"),
                deviceId,
                bundleJson.getInt("preKeyId"),
                ECPublicKey(b64d(bundleJson.getString("preKey"))),
                bundleJson.getInt("signedPreKeyId"),
                ECPublicKey(b64d(bundleJson.getString("signedPreKey"))),
                b64d(bundleJson.getString("signedPreKeySignature")),
                IdentityKey(b64d(bundleJson.getString("identityKey"))),
                bundleJson.getInt("kyberPreKeyId"),
                KEMPublicKey(b64d(bundleJson.getString("kyberPreKey"))),
                b64d(bundleJson.getString("kyberPreKeySignature"))
            )
            SessionBuilder(store, SignalProtocolAddress(remoteName, deviceId)).process(bundle)
            Log.i(TAG, "Peer Signal bundle processed. name=$remoteName sha256=${bundleHash.take(16)}")
            true
        } catch (exc: Exception) {
            Log.e(TAG, "Failed to process peer Signal bundle", exc)
            false
        }
    }

    @Synchronized
    fun processPcBundle(bundleJson: JSONObject): Boolean {
        ensureInitialized()
        try {
            val bundleHash = pcBundleHash(bundleJson)
            val verifiedHash = verifiedPcFingerprint()
            if (verifiedHash.isBlank()) {
                pendingPcBundle = JSONObject(bundleJson.toString())
                hasPcBundle = false
                Log.w(TAG, "PC Signal bundle received but identity is not verified yet")
                return false
            }
            if (!bundleHash.equals(verifiedHash, ignoreCase = true)) {
                pendingPcBundle = JSONObject(bundleJson.toString())
                hasPcBundle = false
                Log.e(TAG, "PC Signal identity mismatch. expected=${verifiedHash.take(16)} got=${bundleHash.take(16)}")
                return false
            }
            if (store.containsSession(remoteAddress)) {
                hasPcBundle = true
                pendingPcBundle = null
                Log.i(TAG, "Existing persistent Signal session loaded. sha256=${bundleHash.take(16)}")
                return true
            }
            val bundle = PreKeyBundle(
                bundleJson.getInt("registrationId"),
                bundleJson.optInt("deviceId", REMOTE_DEVICE_ID),
                bundleJson.getInt("preKeyId"),
                ECPublicKey(b64d(bundleJson.getString("preKey"))),
                bundleJson.getInt("signedPreKeyId"),
                ECPublicKey(b64d(bundleJson.getString("signedPreKey"))),
                b64d(bundleJson.getString("signedPreKeySignature")),
                IdentityKey(b64d(bundleJson.getString("identityKey"))),
                bundleJson.getInt("kyberPreKeyId"),
                KEMPublicKey(b64d(bundleJson.getString("kyberPreKey"))),
                b64d(bundleJson.getString("kyberPreKeySignature"))
            )
            SessionBuilder(store, remoteAddress).process(bundle)
            hasPcBundle = true
            pendingPcBundle = null
            Log.i(TAG, "PC Signal bundle processed. sha256=${bundleHash.take(16)}")
            return true
        } catch (exc: Exception) {
            hasPcBundle = false
            Log.e(TAG, "Failed to process PC Signal bundle", exc)
            return false
        }
    }

    @Synchronized
    fun processPcBundleForDesktop(desktopId: String, bundleJson: JSONObject, expectedFingerprint: String): Boolean {
        ensureInitialized()
        if (desktopId.isBlank()) return false
        return try {
            val bundleHash = pcBundleHash(bundleJson)
            if (expectedFingerprint.isNotBlank() && !bundleHash.equals(expectedFingerprint, ignoreCase = true)) {
                Log.e(TAG, "Desktop Signal identity mismatch. desktop=$desktopId expected=${expectedFingerprint.take(16)} got=${bundleHash.take(16)}")
                return false
            }
            val deviceId = bundleJson.optInt("deviceId", REMOTE_DEVICE_ID)
            val address = SignalProtocolAddress(desktopId, deviceId)
            if (!store.containsSession(address)) {
                val bundle = PreKeyBundle(
                    bundleJson.getInt("registrationId"),
                    deviceId,
                    bundleJson.getInt("preKeyId"),
                    ECPublicKey(b64d(bundleJson.getString("preKey"))),
                    bundleJson.getInt("signedPreKeyId"),
                    ECPublicKey(b64d(bundleJson.getString("signedPreKey"))),
                    b64d(bundleJson.getString("signedPreKeySignature")),
                    IdentityKey(b64d(bundleJson.getString("identityKey"))),
                    bundleJson.getInt("kyberPreKeyId"),
                    KEMPublicKey(b64d(bundleJson.getString("kyberPreKey"))),
                    b64d(bundleJson.getString("kyberPreKeySignature"))
                )
                SessionBuilder(store, address).process(bundle)
            }
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("verified_desktop_identity_sha256_$desktopId", bundleHash)
                .apply()
            Log.i(TAG, "Desktop Signal bundle processed. desktop=$desktopId sha256=${bundleHash.take(16)}")
            true
        } catch (exc: Exception) {
            Log.e(TAG, "Failed to process Desktop Signal bundle desktop=$desktopId", exc)
            false
        }
    }

    @Synchronized
    fun encryptPayload(payload: JSONObject): JSONObject? {
        if (!hasPcBundle) {
            Log.w(TAG, "Refusing plaintext publish: Signal session is not ready")
            return null
        }
        return try {
            val message = SessionCipher(store, remoteAddress).encrypt(payload.toString().toByteArray(Charsets.UTF_8))
            JSONObject()
                .put("version", 1)
                .put("scheme", "signal")
                .put("from", LOCAL_NAME)
                .put("to", REMOTE_NAME)
                .put("signal_type", if (message.type == CiphertextMessage.PREKEY_TYPE) "prekey" else "signal")
                .put("message_type", message.type)
                .put("body", b64e(message.serialize()))
                .put("time", System.currentTimeMillis())
                .also { Log.i(TAG, "Encrypted outgoing payload type=${message.type}") }
        } catch (exc: Exception) {
            Log.e(TAG, "Failed to encrypt outgoing payload", exc)
            null
        }
    }

    @Synchronized
    fun encryptPayloadForDesktop(desktopId: String, payload: JSONObject): JSONObject? {
        ensureInitialized()
        if (desktopId.isBlank()) return encryptPayload(payload)
        val address = SignalProtocolAddress(desktopId, REMOTE_DEVICE_ID)
        if (!store.containsSession(address)) {
            Log.w(TAG, "Refusing desktop publish: no Signal session for $desktopId")
            return null
        }
        return try {
            val message = SessionCipher(store, address).encrypt(payload.toString().toByteArray(Charsets.UTF_8))
            JSONObject()
                .put("version", 1)
                .put("scheme", "signal")
                .put("from", LOCAL_NAME)
                .put("to", desktopId)
                .put("signal_type", if (message.type == CiphertextMessage.PREKEY_TYPE) "prekey" else "signal")
                .put("message_type", message.type)
                .put("body", b64e(message.serialize()))
                .put("time", System.currentTimeMillis())
                .also { Log.i(TAG, "Encrypted desktop payload to=$desktopId type=${message.type}") }
        } catch (exc: Exception) {
            Log.e(TAG, "Failed to encrypt desktop payload to=$desktopId", exc)
            null
        }
    }

    @Synchronized
    fun encryptPayloadForContact(contactId: String, payload: JSONObject): JSONObject? {
        ensureInitialized()
        val address = SignalProtocolAddress(contactId, DEFAULT_DEVICE_ID)
        if (!store.containsSession(address)) {
            Log.w(TAG, "Refusing contact publish: no Signal session for $contactId")
            return null
        }
        return try {
            val message = SessionCipher(store, address).encrypt(payload.toString().toByteArray(Charsets.UTF_8))
            JSONObject()
                .put("version", 1)
                .put("scheme", "signal")
                .put("from", localSignalasiId())
                .put("to", contactId)
                .put("device_id", DEFAULT_DEVICE_ID)
                .put("signal_type", if (message.type == CiphertextMessage.PREKEY_TYPE) "prekey" else "signal")
                .put("message_type", message.type)
                .put("body", b64e(message.serialize()))
                .put("time", System.currentTimeMillis())
                .also { Log.i(TAG, "Encrypted contact payload to=$contactId type=${message.type}") }
        } catch (exc: Exception) {
            Log.e(TAG, "Failed to encrypt contact payload", exc)
            null
        }
    }

    @Synchronized
    fun decryptEnvelope(envelope: JSONObject): JSONObject? {
        if (envelope.optString("scheme") != "signal") return null
        return try {
            val body = b64d(envelope.getString("body"))
            val type = envelope.optString("signal_type", envelope.optString("type", "signal"))
            val from = envelope.optString("from", REMOTE_NAME)
            val address = if (from == REMOTE_NAME) {
                remoteAddress
            } else {
                SignalProtocolAddress(from, envelope.optInt("device_id", DEFAULT_DEVICE_ID))
            }
            val plaintext = if (type == "prekey" || envelope.optInt("message_type", -1) == CiphertextMessage.PREKEY_TYPE) {
                SessionCipher(store, address).decrypt(PreKeySignalMessage(body))
            } else {
                SessionCipher(store, address).decrypt(SignalMessage(body))
            }
            JSONObject(String(plaintext, Charsets.UTF_8)).also {
                Log.i(TAG, "Decrypted incoming Signal envelope")
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Failed to decrypt incoming Signal envelope", exc)
            null
        }
    }

    private fun b64e(value: ByteArray): String =
        Base64.encodeToString(value, Base64.NO_WRAP)

    private fun b64d(value: String): ByteArray =
        Base64.decode(value, Base64.DEFAULT)

    private fun ensureInitialized() {
        check(::store.isInitialized) { "SignalASICrypto.initialize(context) must be called first" }
    }

    private fun migrateSharedPreferences(context: Context, oldName: String, newName: String) {
        if (oldName == newName) return
        val oldPrefs = context.getSharedPreferences(oldName, Context.MODE_PRIVATE)
        val newPrefs = context.getSharedPreferences(newName, Context.MODE_PRIVATE)
        if (oldPrefs.all.isEmpty() || newPrefs.all.isNotEmpty()) return
        val editor = newPrefs.edit()
        copySharedPreferences(oldPrefs, editor)
        editor.commit()
    }

    private fun copySharedPreferences(from: SharedPreferences, to: SharedPreferences.Editor) {
        from.all.forEach { (key, value) ->
            when (value) {
                is String -> to.putString(key, value)
                is Int -> to.putInt(key, value)
                is Long -> to.putLong(key, value)
                is Boolean -> to.putBoolean(key, value)
                is Float -> to.putFloat(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    to.putStringSet(key, value as Set<String>)
                }
            }
        }
    }

    private fun pcBundleHash(bundleJson: JSONObject): String {
        val declared = bundleJson.optString("identityKeySha256")
        if (declared.isNotBlank()) return declared
        return sha256Hex(b64d(bundleJson.getString("identityKey")))
    }

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(it) }
}
