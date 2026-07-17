package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

object AppStore {
    @Volatile private var initialized = false
    private val initializationLock = Any()
    private val contactsCacheLock = Any()
    @Volatile private var contactsCacheRaw = ""
    @Volatile private var contactsCacheById: Map<String, String> = emptyMap()
    private const val PREFS = "signalasi_app_store"
    private const val HISTORY_PREFS = "signalasi_chat_history"
    private const val TRUST_PREFS = "signalasi_signal_trust"
    private const val SIGNAL_STORE_PREFS = "signalasi_signal_store"
    private const val KEY_CONTACTS = "contacts"
    private const val KEY_FRIEND_REQUESTS = "friend_requests"
    private const val KEY_PROFILE = "profile"
    private const val BACKUP_VERSION = 1
    private const val PBKDF2_ITERATIONS = 180_000
    private const val KEY_SIZE_BITS = 256
    private const val GCM_TAG_BITS = 128

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(initializationLock) {
            if (initialized) return
            initializeOnce(context.applicationContext)
            initialized = true
        }
        thread(name = "signalasi-initial-backup", isDaemon = true) {
            createInitialPrivateBackup(context.applicationContext)
        }
    }

    private fun initializeOnce(appContext: Context) {
        SignalASICrypto.initialize(appContext)
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PROFILE)) {
            prefs.edit().putString(KEY_PROFILE, defaultProfile(appContext).toString()).apply()
        }
        if (!prefs.contains(KEY_CONTACTS)) {
            prefs.edit().putString(KEY_CONTACTS, JSONArray().toString()).apply()
        }
        if (!prefs.contains(KEY_FRIEND_REQUESTS)) {
            prefs.edit().putString(KEY_FRIEND_REQUESTS, JSONArray().toString()).apply()
        }
        normalizeSignalasiIds(appContext)
        removeLegacyDesktopConnectorContacts(appContext)
        removeDesktopCloudModelContacts(appContext)
        normalizeCloudApiProviderContacts(appContext)
        removeContactsForMissingServerLinks(appContext)
    }

    fun profile(context: Context): JSONObject {
        ensureInitialized(context)
        val current = readObject(context, KEY_PROFILE)
        var changed = false
        if (current.optString("name").isBlank()) {
            current.put("name", "Me")
            changed = true
        }
        if (changed) writeObject(context, KEY_PROFILE, current)
        return current.apply {
            putSignalasiId(this, SignalASICrypto.localSignalasiId())
            put("identity_fingerprint", SignalASICrypto.localIdentitySha256())
            put("identity_public_key", SignalASICrypto.localIdentityPublicKey())
        }
    }

    fun updateProfileName(context: Context, name: String): JSONObject {
        ensureInitialized(context)
        val cleaned = name.trim().ifBlank { "Me" }
        val current = readObject(context, KEY_PROFILE)
        current.put("name", cleaned)
        current.put("updated_at", System.currentTimeMillis())
        writeObject(context, KEY_PROFILE, current)
        return profile(context)
    }

    fun updateProfile(context: Context, profile: JSONObject) {
        ensureInitialized(context)
        writeObject(context, KEY_PROFILE, profile)
    }

    fun contacts(context: Context): JSONArray {
        ensureInitialized(context)
        return readArray(context, KEY_CONTACTS)
    }

    fun friendRequests(context: Context): JSONArray {
        ensureInitialized(context)
        return readArray(context, KEY_FRIEND_REQUESTS)
    }

    fun addFriendRequest(context: Context, request: JSONObject) {
        ensureInitialized(context)
        val requests = friendRequests(context)
        val signalasiId = signalasiIdOf(request)
        val existingContact = findContactBySignalasiId(contacts(context), signalasiId)
        val wasDeleted = existingContact?.let {
            it.optBoolean("deleted", false) || it.optString("trust_state") == "deleted"
        } ?: false
        val existing = (0 until requests.length()).firstOrNull {
            signalasiIdOf(requests.optJSONObject(it) ?: JSONObject()) == signalasiId
        }
        val stored = JSONObject(request.toString())
            .put("id", request.optString("id").ifBlank { "req_${System.currentTimeMillis()}" })
            .put("status", "pending")
            .put("created_at", System.currentTimeMillis())
            .put("previously_deleted", wasDeleted)
            .put("readd_required", wasDeleted)
        putSignalasiId(stored, signalasiId)
        if (existing == null) {
            requests.put(stored)
        } else {
            requests.put(existing, stored)
        }
        writeArray(context, KEY_FRIEND_REQUESTS, requests)
    }

    fun approveFriendRequest(context: Context, requestId: String): Boolean {
        ensureInitialized(context)
        val requests = friendRequests(context)
        val contacts = contacts(context)
        for (i in 0 until requests.length()) {
            val request = requests.optJSONObject(i) ?: continue
            if (request.optString("id") != requestId) continue
            request.put("status", "approved")
            request.put("approved_at", System.currentTimeMillis())
            if (request.optBoolean("previously_deleted", false)) {
                request.put("readded_at", System.currentTimeMillis())
            }
            val signalBundle = request.optJSONObject("signal_bundle")
            val signalReady = if (signalBundle != null) {
                SignalASICrypto.processPeerBundle(
                    signalBundle,
                    signalasiIdOf(request),
                    request.optString("identity_fingerprint")
                )
            } else {
                false
            }
            val contact = JSONObject()
                .put("id", signalasiIdOf(request))
                .put("name", request.optString("name", "Friend"))
                .put("avatar", request.optString("avatar", ""))
                .put("type", request.optString("type", "person"))
                .also { putSignalasiId(it, signalasiIdOf(request)) }
                .put("identity_public_key", request.optString("identity_public_key"))
                .put("identity_fingerprint", request.optString("identity_fingerprint"))
                .put("mqtt_topic", request.optString("mqtt_topic"))
                .put("mqtt_inbox_topic", request.optString("mqtt_inbox_topic", request.optString("mqtt_topic")))
                .put("signal_bundle", signalBundle)
                .put("signal_session", if (signalReady) "ready" else "missing")
                .put("trust_state", "verified")
                .put("created_at", System.currentTimeMillis())
                .put("approved_from_request", true)
                .put("last_friend_request_id", request.optString("id"))
                .put("deleted", false)
            if (request.optBoolean("previously_deleted", false)) {
                contact.put("readded_at", System.currentTimeMillis())
            }
            upsertContact(contacts, contact)
            writeArray(context, KEY_FRIEND_REQUESTS, requests)
            writeArray(context, KEY_CONTACTS, contacts)
            if (!signalReady) {
                SignalASIMqttClient.requestSignalBundleForContact(context, signalasiIdOf(request))
            }
            return true
        }
        return false
    }

    fun approveFriendRequestForSignalasiId(context: Context, signalasiId: String): Boolean {
        ensureInitialized(context)
        val requests = friendRequests(context)
        for (i in 0 until requests.length()) {
            val request = requests.optJSONObject(i) ?: continue
            if (signalasiIdOf(request) == signalasiId && request.optString("status") == "pending") {
                return approveFriendRequest(context, request.optString("id"))
            }
        }
        return false
    }

    fun rejectFriendRequest(context: Context, requestId: String): Boolean {
        ensureInitialized(context)
        val requests = friendRequests(context)
        for (i in 0 until requests.length()) {
            val request = requests.optJSONObject(i) ?: continue
            if (request.optString("id") != requestId) continue
            request.put("status", "rejected")
            writeArray(context, KEY_FRIEND_REQUESTS, requests)
            return true
        }
        return false
    }

    fun deleteContact(context: Context, hermesId: String, deleteMessages: Boolean = false) {
        ensureInitialized(context)
        if (hermesId == "hermes") {
            SignalASICrypto.clearPcTrust(context)
            SignalASIMqttClient.forgetSecureChannel()
        } else {
            SignalASICrypto.clearPeerTrust(context, hermesId)
        }
        val contacts = contacts(context)
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            val isTarget = signalasiIdOf(contact) == hermesId || contact.optString("id") == hermesId
            val isChildOfHermes = hermesId == "hermes" && (
                contact.optString("parent_contact") == "hermes" ||
                    contact.optString("delivery_mode") == "pc_connector"
                )
            if (isTarget || isChildOfHermes) {
                contact.put("deleted", true)
                contact.put("trust_state", "deleted")
                contact.put("deleted_at", System.currentTimeMillis())
            }
        }
        writeArray(context, KEY_CONTACTS, contacts)
        val requests = friendRequests(context)
        var requestsChanged = false
        for (i in 0 until requests.length()) {
            val request = requests.optJSONObject(i) ?: continue
            if (signalasiIdOf(request) == hermesId || request.optString("id") == hermesId) {
                request.put("status", "deleted")
                request.put("deleted_at", System.currentTimeMillis())
                request.put("readd_required", true)
                requestsChanged = true
            }
        }
        if (requestsChanged) writeArray(context, KEY_FRIEND_REQUESTS, requests)
        if (deleteMessages) {
            removeChatHistory(context, hermesId)
        }
    }

    fun canCommunicateWith(context: Context, hermesId: String): Boolean {
        val contact = contactById(context, hermesId) ?: return false
        return !contact.optBoolean("deleted", false) &&
            contact.optString("trust_state") == "verified"
    }

    fun contactById(context: Context, hermesId: String): JSONObject? {
        ensureInitialized(context)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONTACTS, "[]") ?: "[]"
        if (raw != contactsCacheRaw) {
            synchronized(contactsCacheLock) {
                if (raw != contactsCacheRaw) {
                    val indexed = LinkedHashMap<String, String>()
                    val contacts = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
                    for (index in 0 until contacts.length()) {
                        val contact = contacts.optJSONObject(index) ?: continue
                        val serialized = contact.toString()
                        signalasiIdOf(contact).takeIf { it.isNotBlank() }?.let { indexed[it] = serialized }
                        contact.optString("id").takeIf { it.isNotBlank() }?.let { indexed[it] = serialized }
                    }
                    contactsCacheById = indexed
                    contactsCacheRaw = raw
                }
            }
        }
        return contactsCacheById[hermesId]?.let { JSONObject(it) }
    }

    fun updateContactName(context: Context, hermesId: String, name: String): Boolean {
        if (hermesId.isBlank() || name.isBlank()) return false
        ensureInitialized(context)
        val contacts = contacts(context)
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            val id = signalasiIdOf(contact)
            if (id == hermesId || contact.optString("id") == hermesId) {
                contact.put("name", name.trim())
                contact.put("display_name", name.trim())
                contact.put("user_renamed", true)
                contact.put("profile_updated_at", System.currentTimeMillis())
                writeArray(context, KEY_CONTACTS, contacts)
                return true
            }
        }
        return false
    }

    fun addCloudModelContact(
        context: Context,
        displayName: String,
        provider: String,
        modelId: String,
        endpoint: String,
        apiKey: String,
        apiStyle: String
    ): JSONObject {
        ensureInitialized(context)
        val now = System.currentTimeMillis()
        val providerName = provider.trim().ifBlank { "Custom" }
        val contactId = cloudProviderContactId(providerName)
        val contacts = contacts(context)
        val existingIndex = (0 until contacts.length()).firstOrNull { index ->
            val item = contacts.optJSONObject(index) ?: return@firstOrNull false
            val id = item.optString("id").ifBlank { signalasiIdOf(item) }
            id == contactId || (
                item.optString("delivery_mode") == "cloud_api" &&
                    providerKey(item.optString("cloud_provider")) == providerKey(providerName)
                )
        }
        val contact = existingIndex?.let { contacts.optJSONObject(it) } ?: JSONObject()
        contact.put("id", contactId)
            .also { putSignalasiId(it, contactId) }
            .put("name", providerName)
            .put("display_name", providerName)
            .put("default_display_name", providerName)
            .put("avatar", "")
            .put("type", "agent")
            .put("agent_kind", "cloud-api")
            .put("delivery_mode", "cloud_api")
            .put("cloud_provider", providerName)
            .put("identity_fingerprint", "")
            .put("trust_state", "verified")
            .put("setup_status", "ready")
            .put("setup_detail", "Mobile direct cloud model API")
            .put("created_at", contact.optLong("created_at", now))
            .put("deleted", false)
        val models = contact.optJSONArray("cloud_models") ?: JSONArray().also { contact.put("cloud_models", it) }
        putUniqueCloudModel(models, cloudModelEntry(displayName.ifBlank { modelId }, modelId, endpoint, apiKey, apiStyle, now))
        if (contact.optString("selected_cloud_model").isBlank()) {
            contact.put("selected_cloud_model", modelId)
        }
        applySelectedCloudModelFields(contact)
        if (existingIndex == null) {
            upsertContact(contacts, contact)
        } else {
            contacts.put(existingIndex, contact)
        }
        writeArray(context, KEY_CONTACTS, contacts)
        return JSONObject(contact.toString())
    }
    fun isCloudApiContact(context: Context, hermesId: String): Boolean {
        val contact = contactById(context, hermesId) ?: return false
        return contact.optString("delivery_mode") == "cloud_api"
    }

    fun selectedCloudModelContact(context: Context, hermesId: String): JSONObject? {
        val contact = contactById(context, hermesId) ?: return null
        if (contact.optString("delivery_mode") != "cloud_api") return null
        applySelectedCloudModelFields(contact)
        return contact
    }

    fun cloudModels(context: Context, hermesId: String): JSONArray {
        val contact = contactById(context, hermesId) ?: return JSONArray()
        return contact.optJSONArray("cloud_models") ?: JSONArray()
    }

    fun selectedCloudModelId(context: Context, hermesId: String): String {
        val contact = contactById(context, hermesId) ?: return ""
        return contact.optString("selected_cloud_model").ifBlank {
            contact.optJSONArray("cloud_models")?.optJSONObject(0)?.optString("model_id").orEmpty()
        }
    }

    fun revokeDesktopConnector(context: Context, desktopId: String): Boolean {
        ensureInitialized(context)
        if (desktopId.isBlank()) return false
        val linkExisted = SignalASILinkProtocol.serverLink(context, desktopId) != null
        SignalASIMqttClient.publishServerRevocation(context, desktopId)
        val contacts = contacts(context)
        var changed = false
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            val isTarget = contact.optString("desktop_id") == desktopId ||
                contact.optString("parent_contact") == desktopId ||
                contact.optString("id").ifBlank { signalasiIdOf(contact) }.startsWith("$desktopId:")
            if (!isTarget) continue
            if (contact.optString("delivery_mode") == "pc_connector") {
                contact.put("deleted", true)
                contact.put("trust_state", "deleted")
                contact.put("deleted_at", System.currentTimeMillis())
                changed = true
            }
        }
        if (changed) {
            writeArray(context, KEY_CONTACTS, contacts)
        }
        if (linkExisted) {
            SignalASICrypto.clearDesktopTrust(context, desktopId)
            SignalASILinkProtocol.removeServer(context, desktopId)
            SignalASIMqttClient.forgetSecureChannel()
        }
        return changed || linkExisted
    }

    fun setSelectedCloudModel(context: Context, hermesId: String, modelId: String): Boolean {
        if (modelId.isBlank()) return false
        ensureInitialized(context)
        val contacts = contacts(context)
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            val id = contact.optString("id").ifBlank { signalasiIdOf(contact) }
            if (id != hermesId && signalasiIdOf(contact) != hermesId) continue
            val models = contact.optJSONArray("cloud_models") ?: return false
            if (findCloudModel(models, modelId) == null) return false
            contact.put("selected_cloud_model", modelId)
            applySelectedCloudModelFields(contact)
            writeArray(context, KEY_CONTACTS, contacts)
            return true
        }
        return false
    }

    fun localInboxTopic(context: Context): String {
        return SignalASILinkProtocol.allServerLinks(context).firstOrNull { it.paired }?.routes?.down.orEmpty()
    }

    fun outgoingTopicForContact(context: Context, hermesId: String): String? {
        if (hermesId.startsWith("group:")) return null
        val contact = contactById(context, hermesId) ?: return null
        if (!canCommunicateWith(context, hermesId)) return null
        val desktopId = contact.optString("desktop_id")
        if (desktopId.isNotBlank()) {
            return SignalASILinkProtocol.serverLink(context, desktopId)?.takeIf { it.paired }?.routes?.up
        }
        val directTopic = contact.optString("mqtt_topic").ifBlank { contact.optString("mqtt_inbox_topic") }
        return directTopic.takeIf { it.isNotBlank() }
    }

    fun deleteDesktopConnector(context: Context, desktopId: String, deleteMessages: Boolean = false) {
        if (desktopId.isBlank()) return
        ensureInitialized(context)
        val contacts = contacts(context)
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            if (contact.optString("desktop_id") == desktopId || contact.optString("parent_contact") == desktopId) {
                contact.put("deleted", true)
                contact.put("trust_state", "deleted")
                contact.put("deleted_at", System.currentTimeMillis())
                if (deleteMessages) removeChatHistory(context, contact.optString("id").ifBlank { signalasiIdOf(contact) })
            }
        }
        writeArray(context, KEY_CONTACTS, contacts)
    }

    fun renameContact(context: Context, contactId: String, displayName: String): Boolean =
        updateContactName(context, contactId, displayName)

    fun usesPcConnectorTunnel(context: Context, hermesId: String): Boolean {
        if (hermesId == "hermes") return true
        val contact = contactById(context, hermesId) ?: return false
        return contact.optString("delivery_mode") == "pc_connector" ||
            contact.optString("parent_contact") == "hermes" ||
            contact.optString("signal_session") == "pc_tunnel"
    }

    fun desktopIdForContact(context: Context, hermesId: String): String {
        val contact = contactById(context, hermesId) ?: return ""
        return contact.optString("desktop_id")
    }

    fun agentIdForContact(context: Context, hermesId: String): String {
        val contact = contactById(context, hermesId) ?: return hermesId
        return contact.optString("agent_id").ifBlank {
            if (hermesId.startsWith("desktop_") && hermesId.contains(":")) hermesId.substringAfter(":") else hermesId
        }
    }

    fun applySignalBundleResponse(context: Context, response: JSONObject): Boolean {
        ensureInitialized(context)
        val from = response.optString("from")
        val bundle = response.optJSONObject("signal_bundle") ?: return false
        val contacts = contacts(context)
        val responseDesktopId = response.optString("desktop_id")
        if (responseDesktopId.isNotBlank()) {
            val desktopContacts = buildList {
                for (index in 0 until contacts.length()) {
                    val contact = contacts.optJSONObject(index) ?: continue
                    if (contact.optString("desktop_id") == responseDesktopId) add(contact)
                }
            }
            val expectedFingerprint = desktopContacts.firstNotNullOfOrNull { contact ->
                contact.optString("desktop_fingerprint").takeIf { it.isNotBlank() }
            }.orEmpty()
            if (expectedFingerprint.isNotBlank() &&
                SignalASICrypto.processPcBundleForDesktop(
                    responseDesktopId,
                    bundle,
                    expectedFingerprint,
                    replaceExisting = response.optBoolean("session_recovery", false)
                )
            ) {
                desktopContacts.forEach { contact ->
                    contact.put("signal_session", "pc_tunnel")
                    contact.put("signal_bundle", bundle)
                    contact.put("signal_bundle_updated_at", System.currentTimeMillis())
                }
                writeArray(context, KEY_CONTACTS, contacts)
                return true
            }
        }
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            val contactId = signalasiIdOf(contact)
            if (contactId != from && contact.optString("id") != from) continue
            val expectedFingerprint = contact.optString("identity_fingerprint")
            val ready = SignalASICrypto.processPeerBundle(bundle, contactId, expectedFingerprint)
            if (ready) {
                contact.put("signal_bundle", bundle)
                contact.put("signal_session", "ready")
                contact.put("signal_bundle_updated_at", System.currentTimeMillis())
                writeArray(context, KEY_CONTACTS, contacts)
            }
            return ready
        }
        val requests = friendRequests(context)
        for (i in 0 until requests.length()) {
            val request = requests.optJSONObject(i) ?: continue
            if (signalasiIdOf(request) != from) continue
            request.put("signal_bundle", bundle)
            request.put("signal_bundle_updated_at", System.currentTimeMillis())
            writeArray(context, KEY_FRIEND_REQUESTS, requests)
            return true
        }
        return false
    }

    fun markHermesVerified(context: Context) {
        ensureInitialized(context)
        val contacts = contacts(context)
        val hermes = hermesContact(context, approved = true)
        upsertContact(contacts, hermes)
        writeArray(context, KEY_CONTACTS, contacts)
    }

    fun markDesktopVerified(context: Context, pairingQr: JSONObject) {
        ensureInitialized(context)
        val link = SignalASILinkProtocol.ensureServerLink(context, pairingQr)
        val contacts = contacts(context)
        val desktopId = pairingQr.optString("desktop_id")
            .ifBlank { "desktop_${pairingQr.optString("identity_key_sha256").take(16)}" }
        val desktopName = pairingQr.optString("desktop_name").ifBlank { context.getString(R.string.default_desktop_name) }
        val fingerprint = pairingQr.optString("identity_key_sha256")
            .ifBlank { pairingQr.optString("identity_fingerprint") }
        val now = System.currentTimeMillis()
        val agents = pairingQr.optJSONArray("connector_agents")
        if (agents != null && agents.length() > 0) {
            updateConnectorAgentStatuses(context, agents)
            return
        }
        val fallbackAgents = JSONArray()
        listOf(
            Triple("hermes", "Hermes Agent", "local-cli"),
            Triple("codex", "Codex Agent", "local-cli"),
            Triple("claude", "Claude Code", "local-cli"),
            Triple("local-llm", "Local LLM", "local-model"),
            Triple("custom-agent", "Custom Agent", "custom-cli")
        ).forEach { (agentId, name, kind) ->
            fallbackAgents.put(
                JSONObject()
                    .put("id", "$desktopId:$agentId")
                    .put("agent_id", agentId)
                    .put("name", name)
                    .put("display_name", "$name · $desktopName")
                    .put("kind", kind)
                    .put("desktop_id", desktopId)
                    .put("desktop_name", desktopName)
                    .put("desktop_fingerprint", fingerprint)
                    .put("status", "unknown")
                    .put("detail", "Waiting for SignalASI Desktop status")
                    .put("setup", "")
                    .put("mqtt_topic", link.routes.up)
                    .put("updated_at", now)
            )
        }
        updateConnectorAgentStatuses(context, fallbackAgents)
    }

    fun updateConnectorAgentStatuses(context: Context, agents: JSONArray): Boolean {
        ensureInitialized(context)
        val contacts = contacts(context)
        var changed = false
        val now = System.currentTimeMillis()
        for (i in 0 until agents.length()) {
            val agent = agents.optJSONObject(i) ?: continue
            val agentId = agent.optString("agent_id").ifBlank {
                agent.optString("mobile_contact_id").ifBlank { agent.optString("id").substringAfter(":", agent.optString("id")) }
            }
            if (agentId == "cloud-model" || agent.optString("kind") == "cloud-model") continue
            val desktopId = agent.optString("desktop_id").ifBlank {
                if (agent.optString("id").startsWith("desktop_") && agent.optString("id").contains(":")) {
                    agent.optString("id").substringBefore(":")
                } else {
                    val fp = agent.optString("desktop_fingerprint").ifBlank { SignalASICrypto.verifiedPcFingerprint() }
                    "desktop_${fp.take(16)}"
                }
            }
            val rawId = agent.optString("id")
            val id = if (rawId.startsWith("desktop_") && rawId.contains(":")) {
                rawId
            } else {
                "$desktopId:$agentId"
            }
            if (id.isBlank()) continue
            val desktopName = agent.optString("desktop_name").ifBlank { context.getString(R.string.default_desktop_name) }
            val fingerprint = agent.optString("desktop_fingerprint").ifBlank { SignalASICrypto.verifiedDesktopFingerprint(desktopId) }
            var found = false
            for (j in 0 until contacts.length()) {
                val contact = contacts.optJSONObject(j) ?: continue
                val contactId = contact.optString("id").ifBlank { signalasiIdOf(contact) }
                if (contactId != id && signalasiIdOf(contact) != id) continue
                applyConnectorAgentStatus(context, contact, agent, id, now, desktopId, desktopName, fingerprint, agentId)
                changed = true
                found = true
                break
            }
            if (!found) {
                val created = connectorAgentContact(
                    id,
                    agent.optString("name", agentId),
                    agent.optString("kind", "custom-cli"),
                    fingerprint,
                    now,
                    desktopId,
                    desktopName,
                    agentId,
                    agent.optString("mqtt_topic").ifBlank {
                        SignalASILinkProtocol.serverLink(context, desktopId)?.routes?.up.orEmpty()
                    }
                )
                applyConnectorAgentStatus(context, created, agent, id, now, desktopId, desktopName, fingerprint, agentId)
                contacts.put(created)
                changed = true
            }
        }
        if (changed) writeArray(context, KEY_CONTACTS, contacts)
        return changed
    }

    private fun applyConnectorAgentStatus(
        context: Context,
        contact: JSONObject,
        agent: JSONObject,
        id: String,
        now: Long,
        desktopId: String,
        desktopName: String,
        fingerprint: String,
        agentId: String
    ) {
        val agentName = agent.optString("name", contact.optString("agent_name", id))
        val defaultDisplayName = agent.optString("display_name").ifBlank { "$agentName · $desktopName" }
        if (!contact.optBoolean("user_renamed", false)) {
            contact.put("name", defaultDisplayName)
            contact.put("display_name", defaultDisplayName)
        }
        contact.put("agent_name", agentName)
        contact.put("desktop_id", desktopId)
        contact.put("desktop_name", desktopName)
        contact.put("agent_id", agentId)
        putSignalasiId(contact, id)
        contact.put("mqtt_topic", agent.optString("mqtt_topic").ifBlank {
            SignalASILinkProtocol.serverLink(context, desktopId)?.routes?.up.orEmpty()
        })
        contact.put("identity_fingerprint", fingerprint)
        contact.put("desktop_fingerprint", fingerprint)
        contact.put("agent_kind", agent.optString("kind", contact.optString("agent_kind", "custom-cli")))
        contact.put("setup_status", agent.optString("status", "needs_setup"))
        contact.put("setup_detail", agent.optString("detail"))
        contact.put("setup_next_step", agent.optString("setup"))
        val rawUpdatedAt = agent.optLong("updated_at", now)
        val updatedAtMillis = if (rawUpdatedAt in 1L..9_999_999_999L) {
            rawUpdatedAt * 1_000L
        } else {
            rawUpdatedAt
        }
        contact.put("setup_updated_at", updatedAtMillis)
        contact.put("deleted", false)
        contact.put("trust_state", "verified")
        contact.put("signal_session", "pc_tunnel")
    }

    fun createGroup(context: Context, name: String): JSONObject {
        ensureInitialized(context)
        val groupId = "group:${System.currentTimeMillis()}"
        val group = JSONObject()
            .put("id", groupId)
            .put("name", name.ifBlank { "New Group" })
            .put("avatar", "")
            .put("type", "group")
            .also { putSignalasiId(it, groupId) }
            .put("identity_fingerprint", "")
            .put("trust_state", "verified")
            .put("members", JSONArray().put(profile(context).getString("signalasi_id")))
            .put("created_at", System.currentTimeMillis())
            .put("deleted", false)
        val contacts = contacts(context)
        upsertContact(contacts, group)
        writeArray(context, KEY_CONTACTS, contacts)
        return group
    }

    fun createGroupWithMembers(context: Context, name: String, memberIds: List<String>): JSONObject {
        ensureInitialized(context)
        val groupId = "group:${System.currentTimeMillis()}"
        val members = JSONArray()
        val selfId = profile(context).getString("signalasi_id")
        members.put(selfId)
        memberIds.distinct().filter { it.isNotBlank() && it != selfId }.forEach { members.put(it) }
        val group = JSONObject()
            .put("id", groupId)
            .put("name", name.ifBlank { "New Group" })
            .put("avatar", "")
            .put("type", "group")
            .also { putSignalasiId(it, groupId) }
            .put("identity_fingerprint", "")
            .put("trust_state", "verified")
            .put("members", members)
            .put("delivery_mode", "per_member_signal")
            .put("group_key_state", "fanout_v1")
            .put("created_at", System.currentTimeMillis())
            .put("deleted", false)
        val contacts = contacts(context)
        upsertContact(contacts, group)
        writeArray(context, KEY_CONTACTS, contacts)
        return group
    }

    fun groupMemberIds(context: Context, groupId: String): List<String> {
        val group = contactById(context, groupId) ?: return emptyList()
        val members = group.optJSONArray("members") ?: return emptyList()
        val selfId = profile(context).optString("signalasi_id")
        return (0 until members.length())
            .mapNotNull { members.optString(it).takeIf { id -> id.isNotBlank() && id != selfId } }
    }

    fun groupDeliverableMembers(context: Context, groupId: String): List<JSONObject> {
        return groupMemberIds(context, groupId)
            .mapNotNull { memberId -> contactById(context, memberId) }
            .filter { contact ->
                !contact.optBoolean("deleted", false) &&
                    contact.optString("trust_state") == "verified" &&
                    contact.optString("signal_session") == "ready"
            }
    }

    fun ensureIncomingGroup(context: Context, groupId: String, groupName: String, senderId: String): JSONObject? {
        if (groupId.isBlank() || !groupId.startsWith("group:")) return null
        ensureInitialized(context)
        val contacts = contacts(context)
        val selfId = profile(context).getString("signalasi_id")
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            val id = contact.optString("id").ifBlank { signalasiIdOf(contact) }
            if (id != groupId) continue
            val existingMembers = contact.optJSONArray("members") ?: JSONArray().also { contact.put("members", it) }
            val known = (0 until existingMembers.length()).map { existingMembers.optString(it) }.toSet()
            if (senderId.isNotBlank() && senderId !in known) existingMembers.put(senderId)
            contact.put("deleted", false)
            writeArray(context, KEY_CONTACTS, contacts)
            return JSONObject(contact.toString())
        }
        val members = JSONArray().put(selfId)
        if (senderId.isNotBlank() && senderId != selfId) members.put(senderId)
        val group = JSONObject()
            .put("id", groupId)
            .put("name", groupName.ifBlank { "Group" })
            .put("avatar", "")
            .put("type", "group")
            .also { putSignalasiId(it, groupId) }
            .put("identity_fingerprint", "")
            .put("trust_state", "verified")
            .put("members", members)
            .put("delivery_mode", "per_member_signal")
            .put("group_key_state", "fanout_v1")
            .put("created_at", System.currentTimeMillis())
            .put("deleted", false)
        contacts.put(group)
        writeArray(context, KEY_CONTACTS, contacts)
        return group
    }

    fun myQrPayload(context: Context): JSONObject {
        val profile = profile(context)
        return JSONObject()
            .put("type", "signalasi_contact")
            .put("version", 1)
            .put("name", profile.optString("name", "Me"))
            .put("signalasi_id", profile.getString("signalasi_id"))
            .put("identity_public_key", profile.getString("identity_public_key"))
            .put("identity_fingerprint", profile.getString("identity_fingerprint"))
            .put("mqtt_topic", localInboxTopic(context))
            .put("mqtt_inbox_topic", localInboxTopic(context))
            .put("signal_bundle_ref", "mqtt:${localInboxTopic(context)}:${profile.getString("signalasi_id")}")
            .put("device_id", profile.optString("device_id"))
            .put("created_at", profile.optLong("created_at"))
    }

    fun importContactQrAsRequest(context: Context, contents: String): Boolean {
        val json = runCatching { JSONObject(contents) }.getOrNull() ?: return false
        val type = json.optString("type")
        if (type != "signalasi_contact" && type != "hermes_contact" && type != "signalasi_verify") return false
        val fingerprint = json.optString("identity_fingerprint", json.optString("identity_key_sha256"))
        val publicKey = json.optString("identity_public_key", json.optString("identity_key"))
        if (fingerprint.isBlank() || publicKey.isBlank()) return false
        val signalasiId = json.optString("signalasi_id")
            .ifBlank { json.optString("hermes_id") }
            .ifBlank { "signalasi:${fingerprint.take(16)}" }
        val mqttTopic = json.optString("mqtt_topic")
            .ifBlank { json.optString("mqtt_inbox_topic") }
            .ifBlank { json.optString("mqtt_recv_topic") }
        val signalBundle = json.optJSONObject("signal_bundle")
        addFriendRequest(
            context,
            JSONObject()
                .put("name", json.optString("name", if (type == "signalasi_verify") "Hermes" else "Friend"))
                .put("type", if (type == "signalasi_verify") "hermes" else "person")
                .also { putSignalasiId(it, signalasiId) }
                .put("identity_public_key", publicKey)
                .put("identity_fingerprint", fingerprint)
                .put("mqtt_topic", mqttTopic)
                .put("mqtt_inbox_topic", mqttTopic)
                .put("signal_bundle", signalBundle)
                .put("source", "qr")
        )
        return true
    }

    fun exportBackup(
        context: Context,
        password: String,
        includeContacts: Boolean,
        includeMessages: Boolean
    ): File {
        require(password.length >= 8) { "Backup password must be at least 8 characters." }
        ensureInitialized(context)
        val payload = JSONObject()
            .put("identity", SignalASICrypto.exportSignalStoreJson(context))
            .put("profile", profile(context))
            .put("includes_contacts", includeContacts)
            .put("includes_messages", includeMessages)
            .put("includes_agent_data", true)
            .put("agent_data", AgentBackupData.export(context, includeSessionHistory = includeMessages))
        if (includeContacts) {
            payload.put("contacts", contacts(context))
            payload.put("friend_requests", friendRequests(context))
        }
        if (includeMessages) {
            val rawMessages = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
                .getString("messages", "{}")
            payload.put("messages", JSONObject(rawMessages ?: "{}"))
        }
        val encrypted = encryptBackup(payload.toString(), password)
        val backup = context.filesDir.resolve("backups").apply { mkdirs() }
            .resolve("signalasi_backup_${System.currentTimeMillis()}.hcbak")
        backup.writeText(encrypted.toString(), Charsets.UTF_8)
        return backup
    }

    fun importBackup(context: Context, file: File, password: String, includeMessages: Boolean = true) {
        require(file.isFile) { "Backup file not found." }
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val payload = JSONObject(decryptBackup(root, password))
        payload.optJSONObject("identity")?.let { SignalASICrypto.importSignalStoreJson(context, it) }
        payload.optJSONObject("profile")?.let { writeObject(context, KEY_PROFILE, it) }
        payload.optJSONArray("contacts")?.let { writeArray(context, KEY_CONTACTS, it) }
        payload.optJSONArray("friend_requests")?.let { writeArray(context, KEY_FRIEND_REQUESTS, it) }
        payload.optJSONObject("agent_data")?.let {
            AgentBackupData.restore(context, it)
            AgentWorkflowScheduler.restoreAll(context)
        }
        if (includeMessages) {
            payload.optJSONObject("messages")?.let {
                context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("messages", it.toString())
                    .apply()
            }
        }
    }

    fun destroyAllPrivateData(context: Context) {
        initialized = false
        contactsCacheRaw = ""
        contactsCacheById = emptyMap()
        AgentWorkflowScheduler.cancelAll(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(TRUST_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(SIGNAL_STORE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("signalasi_agent_runtime", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("signalasi_agent_memory", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("signalasi_agent_knowledge", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("signalasi_agent_knowledge_audit", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("signalasi_agent_tasks", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(AgentTranscriptStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        AgentEncryptedDatabase(context, AgentTranscriptStore.PREFS).clear()
        context.getSharedPreferences("signalasi_ui_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("signalasi_agent_safety", Context.MODE_PRIVATE).edit().clear().commit()
        SharedPreferencesAgentConfirmationConsentStore(context).clear()
        context.getSharedPreferences("signalasi_agent_workflows", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("signalasi_agent_workflow_schedules", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("signalasi_agent_workflow_triggers", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("signalasi_agent_workflow_execution_history", Context.MODE_PRIVATE).edit().clear().commit()
        AgentConnectorResponseStore.clear(context)
        HomeAssistantSettingsStore.clear(context)
        CustomDeviceConnectorStore(context).clear()
        AgentModelPlannerSettingsStore(context).clear()
        EncryptedAgentSkillStore(context).clear()
        VoiceAssistantSettings.clear(context)
        SignalASILinkProtocol.clear(context)
        SignalASILinkDeliveryStore.clear(context)
        AgentEncryptedDatabase(context, "signalasi_agent_runs").clear()
        AgentEncryptedDatabase(context, EncryptedAgentWorkspaceStore.DATABASE_NAME).clear()
        context.databaseList().forEach { database -> runCatching { context.deleteDatabase(database) } }
        clearAllSharedPreferences(context)
        runCatching { AgentStorageCipher.deleteMasterKey() }
        SignalASICrypto.resetLocalIdentity(context)
        context.cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        context.externalCacheDirs.filterNotNull().forEach { directory ->
            directory.listFiles().orEmpty().forEach { it.deleteRecursively() }
        }
        context.filesDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        context.noBackupFilesDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        context.getExternalFilesDirs(null).filterNotNull().forEach { directory ->
            directory.listFiles().orEmpty().forEach { it.deleteRecursively() }
        }
        resetToFreshInstall(context)
        ensureInitialized(context)
    }

    private fun clearAllSharedPreferences(context: Context) {
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        directory.listFiles()
            .orEmpty()
            .filter { it.extension == "xml" }
            .map { it.nameWithoutExtension }
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
    }

    private fun resetToFreshInstall(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PROFILE, defaultProfile(context).toString())
            .putString(KEY_CONTACTS, JSONArray().toString())
            .putString(KEY_FRIEND_REQUESTS, JSONArray().toString())
            .commit()
    }

    private fun createInitialPrivateBackup(context: Context) {
        val marker = context.filesDir.resolve("backups/.initial_backup_created")
        if (marker.exists()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("initial_backup_in_progress", false)) return
        runCatching {
            prefs.edit().putBoolean("initial_backup_in_progress", true).apply()
            exportBackup(
                context,
                password = initialBackupPassword(context),
                includeContacts = true,
                includeMessages = false
            )
            marker.parentFile?.mkdirs()
            marker.writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
        }.also {
            prefs.edit().putBoolean("initial_backup_in_progress", false).apply()
        }
    }

    private fun initialBackupPassword(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString("initial_backup_secret", null)
        if (existing != null) return existing
        val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        prefs.edit().putString("initial_backup_secret", secret).apply()
        return secret
    }

    private fun defaultProfile(context: Context): JSONObject =
        JSONObject()
            .put("name", "Me")
            .put("device_id", SignalASILinkProtocol.newRouteId())
            .put("created_at", System.currentTimeMillis())

    private fun removeLegacyDesktopConnectorContacts(context: Context) {
        val contacts = readArray(context, KEY_CONTACTS)
        val cleaned = JSONArray()
        var changed = false
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            val id = contact.optString("id").ifBlank { signalasiIdOf(contact) }
            val hermesId = signalasiIdOf(contact).ifBlank { id }
            val isLegacyHermes = id == "hermes" ||
                hermesId == "hermes" ||
                contact.optString("type") == "hermes"
            val shouldRemoveHermes = isLegacyHermes && (
                contact.optBoolean("deleted", false) ||
                    contact.optString("trust_state") == "deleted" ||
                    contact.optString("trust_state").isBlank()
                )
            val isPcConnector = contact.optString("delivery_mode") == "pc_connector" ||
                contact.optString("parent_contact") == "hermes"
            val isFlatDesktopContact = id.startsWith("desktop_") &&
                id.contains(":") &&
                contact.optString("desktop_id").isNotBlank()
            if (shouldRemoveHermes || (isPcConnector && !isFlatDesktopContact)) {
                changed = true
                continue
            }
            cleaned.put(contact)
        }
        if (changed) writeArray(context, KEY_CONTACTS, cleaned)
    }

    private fun removeContactsForMissingServerLinks(context: Context) {
        val activeDesktopIds = SignalASILinkProtocol.allServerLinks(context).map { it.desktopId }.toSet()
        val contacts = readArray(context, KEY_CONTACTS)
        val cleaned = JSONArray()
        var changed = false
        for (index in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(index) ?: continue
            val desktopId = contact.optString("desktop_id")
            if (desktopId.isNotBlank() && desktopId !in activeDesktopIds) {
                changed = true
                continue
            }
            cleaned.put(contact)
        }
        if (changed) writeArray(context, KEY_CONTACTS, cleaned)
    }

    private fun removeDesktopCloudModelContacts(context: Context) {
        val contacts = readArray(context, KEY_CONTACTS)
        var changed = false
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            val id = contact.optString("id").ifBlank { signalasiIdOf(contact) }
            val isDesktopCloud = contact.optString("delivery_mode") == "pc_connector" && (
                contact.optString("agent_id") == "cloud-model" ||
                    contact.optString("agent_kind") == "cloud-model" ||
                    id == "cloud-model" ||
                    id.endsWith(":cloud-model")
                )
            if (isDesktopCloud) {
                contact.put("deleted", true)
                contact.put("trust_state", "deleted")
                contact.put("deleted_at", System.currentTimeMillis())
                changed = true
            }
        }
        if (changed) writeArray(context, KEY_CONTACTS, contacts)
    }

    private fun normalizeCloudApiProviderContacts(context: Context) {
        val contacts = readArray(context, KEY_CONTACTS)
        val cleaned = JSONArray()
        val providers = LinkedHashMap<String, JSONObject>()
        var changed = false
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            if (contact.optString("delivery_mode") != "cloud_api") {
                cleaned.put(contact)
                continue
            }
            val providerName = contact.optString("cloud_provider").ifBlank {
                contact.optString("name").substringBefore(" ").ifBlank { "Custom" }
            }
            val providerKey = providerKey(providerName)
            val providerContact = providers.getOrPut(providerKey) {
                JSONObject()
                    .put("id", cloudProviderContactId(providerName))
                    .also { putSignalasiId(it, cloudProviderContactId(providerName)) }
                    .put("name", providerName)
                    .put("display_name", providerName)
                    .put("default_display_name", providerName)
                    .put("avatar", "")
                    .put("type", "agent")
                    .put("agent_kind", "cloud-api")
                    .put("delivery_mode", "cloud_api")
                    .put("cloud_provider", providerName)
                    .put("cloud_models", JSONArray())
                    .put("identity_fingerprint", "")
                    .put("trust_state", "verified")
                    .put("setup_status", "ready")
                    .put("setup_detail", "Mobile direct cloud model API")
                    .put("created_at", contact.optLong("created_at", System.currentTimeMillis()))
                    .put("deleted", false)
            }
            val models = providerContact.optJSONArray("cloud_models") ?: JSONArray().also {
                providerContact.put("cloud_models", it)
            }
            val existingModels = contact.optJSONArray("cloud_models")
            if (existingModels != null && existingModels.length() > 0) {
                for (j in 0 until existingModels.length()) {
                    existingModels.optJSONObject(j)?.let { putUniqueCloudModel(models, it) }
                }
            } else {
                val modelId = contact.optString("cloud_model")
                if (modelId.isNotBlank()) {
                    putUniqueCloudModel(models, cloudModelEntry(
                        contact.optString("name", modelId),
                        modelId,
                        contact.optString("cloud_endpoint"),
                        contact.optString("cloud_api_key"),
                        contact.optString("cloud_api_style", "openai"),
                        contact.optLong("created_at", System.currentTimeMillis())
                    ))
                }
            }
            if (providerContact.optString("selected_cloud_model").isBlank()) {
                providerContact.put(
                    "selected_cloud_model",
                    contact.optString("selected_cloud_model").ifBlank { contact.optString("cloud_model") }
                )
            }
            val originalId = contact.optString("id").ifBlank { signalasiIdOf(contact) }
            if (originalId != providerContact.optString("id") || contact.optJSONArray("cloud_models") == null) {
                changed = true
            }
        }
        providers.values.forEach { providerContact ->
            applySelectedCloudModelFields(providerContact)
            cleaned.put(providerContact)
        }
        if (changed) writeArray(context, KEY_CONTACTS, cleaned)
    }

    private fun ensureConnectorAgents(context: Context) {
        val fingerprint = SignalASICrypto.verifiedPcFingerprint()
        if (fingerprint.isBlank()) return
        val contacts = readArray(context, KEY_CONTACTS)
        val hasVerifiedHermes = (0 until contacts.length()).any { index ->
            val contact = contacts.optJSONObject(index) ?: return@any false
            val isHermes = contact.optString("id") == "hermes" ||
                signalasiIdOf(contact) == "hermes" ||
                contact.optString("type") == "hermes"
            isHermes &&
                !contact.optBoolean("deleted", false) &&
                contact.optString("trust_state") != "deleted" &&
                contact.optString("identity_fingerprint").equals(fingerprint, ignoreCase = true)
        }
        if (!hasVerifiedHermes) return
        var changed = false
        connectorAgentContacts().forEach { candidate ->
            val id = candidate.optString("id")
            var existingIndex = -1
            for (i in 0 until contacts.length()) {
                val existing = contacts.optJSONObject(i) ?: continue
                val existingId = signalasiIdOf(existing)
                if (existingId == id || existing.optString("id") == id) {
                    existingIndex = i
                    break
                }
            }
            if (existingIndex < 0) {
                contacts.put(candidate)
                changed = true
            } else {
                val existing = contacts.optJSONObject(existingIndex) ?: return@forEach
                if (!existing.optBoolean("deleted", false) && existing.optString("delivery_mode") != "pc_connector") {
                    contacts.put(existingIndex, candidate)
                    changed = true
                }
            }
        }
        if (changed) writeArray(context, KEY_CONTACTS, contacts)
    }

    private fun hermesContact(context: Context, approved: Boolean): JSONObject =
        JSONObject()
            .put("id", "hermes")
            .put("name", "Hermes")
            .put("avatar", "")
            .put("type", "hermes")
            .also { putSignalasiId(it, "hermes") }
            .put("identity_fingerprint", SignalASICrypto.verifiedPcFingerprint())
            .put("trust_state", if (approved) "verified" else "unverified")
            .put("created_at", System.currentTimeMillis())
            .put("deleted", false)

    private fun connectorAgentContacts(): List<JSONObject> {
        val fingerprint = SignalASICrypto.verifiedPcFingerprint()
        val now = System.currentTimeMillis()
        return listOf(
            connectorAgentContact("codex", "Codex Agent", "local-cli", fingerprint, now),
            connectorAgentContact("claude", "Claude Code", "local-cli", fingerprint, now),
            connectorAgentContact("local-llm", "Local LLM", "local-model", fingerprint, now),
            connectorAgentContact("custom-agent", "Custom Agent", "custom-cli", fingerprint, now),
        )
    }

    private fun connectorAgentContact(
        id: String,
        name: String,
        kind: String,
        fingerprint: String,
        createdAt: Long,
        desktopId: String = "desktop_${fingerprint.take(16)}",
        desktopName: String = "Computer",
        agentId: String = id,
        topic: String = ""
    ): JSONObject =
        run {
            val displayName = if (desktopId.isNotBlank()) "$name · $desktopName" else name
            JSONObject()
                .put("id", id)
                .put("name", displayName)
                .put("display_name", displayName)
                .put("default_display_name", displayName)
                .put("agent_name", name)
                .put("desktop_name", desktopName)
                .put("desktop_id", desktopId)
                .put("agent_id", agentId)
                .put("avatar", "")
                .put("type", "agent")
                .put("agent_kind", kind)
                .also { putSignalasiId(it, id) }
                .put("parent_contact", desktopId)
                .put("delivery_mode", "pc_connector")
                .put("mqtt_topic", topic)
                .put("identity_fingerprint", fingerprint)
                .put("desktop_fingerprint", fingerprint)
                .put("trust_state", "verified")
                .put("signal_session", "pc_tunnel")
                .put("setup_status", "unknown")
                .put("setup_detail", "Waiting for SignalASI Desktop status")
                .put("setup_next_step", "")
                .put("created_at", createdAt)
                .put("deleted", false)
        }

    private fun upsertContact(contacts: JSONArray, contact: JSONObject) {
        val id = signalasiIdOf(contact)
        for (i in 0 until contacts.length()) {
            val existing = contacts.optJSONObject(i) ?: continue
            if (signalasiIdOf(existing) == id || existing.optString("id") == id) {
                contacts.put(i, contact)
                return
            }
        }
        contacts.put(contact)
    }

    private fun findContactBySignalasiId(contacts: JSONArray, signalasiId: String): JSONObject? {
        if (signalasiId.isBlank()) return null
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            if (signalasiIdOf(contact) == signalasiId || contact.optString("id") == signalasiId) {
                return contact
            }
        }
        return null
    }

    private fun cloudProviderContactId(provider: String): String =
        "cloud:${providerKey(provider)}"

    private fun providerKey(provider: String): String =
        provider.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "custom" }

    private fun cloudModelEntry(
        name: String,
        modelId: String,
        endpoint: String,
        apiKey: String,
        apiStyle: String,
        updatedAt: Long
    ): JSONObject =
        JSONObject()
            .put("name", name.ifBlank { modelId })
            .put("model_id", modelId)
            .put("endpoint", endpoint)
            .put("api_key", apiKey)
            .put("api_style", apiStyle.ifBlank { "openai" })
            .put("updated_at", updatedAt)

    private fun putUniqueCloudModel(models: JSONArray, model: JSONObject) {
        val modelId = model.optString("model_id")
        if (modelId.isBlank()) return
        for (i in 0 until models.length()) {
            val existing = models.optJSONObject(i) ?: continue
            if (existing.optString("model_id") == modelId) {
                models.put(i, model)
                return
            }
        }
        models.put(model)
    }

    private fun findCloudModel(models: JSONArray, modelId: String): JSONObject? {
        for (i in 0 until models.length()) {
            val model = models.optJSONObject(i) ?: continue
            if (model.optString("model_id") == modelId) return model
        }
        return null
    }

    private fun applySelectedCloudModelFields(contact: JSONObject) {
        val models = contact.optJSONArray("cloud_models") ?: return
        val selected = contact.optString("selected_cloud_model")
        val model = findCloudModel(models, selected) ?: models.optJSONObject(0) ?: return
        contact.put("selected_cloud_model", model.optString("model_id"))
        contact.put("cloud_model", model.optString("model_id"))
        contact.put("cloud_endpoint", model.optString("endpoint"))
        contact.put("cloud_api_key", model.optString("api_key"))
        contact.put("cloud_api_style", model.optString("api_style", "openai"))
    }

    private fun encryptBackup(plaintext: String, password: String): JSONObject {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("version", BACKUP_VERSION)
            .put("type", "signalasi_backup")
            .put("kdf", "pbkdf2-hmac-sha256")
            .put("iterations", PBKDF2_ITERATIONS)
            .put("cipher", "aes-256-gcm")
            .put("salt", salt.b64())
            .put("iv", iv.b64())
            .put("ciphertext", ciphertext.b64())
            .put("created_at", System.currentTimeMillis())
    }

    private fun decryptBackup(root: JSONObject, password: String): String {
        val salt = root.getString("salt").b64d()
        val iv = root.getString("iv").b64d()
        val ciphertext = root.getString("ciphertext").b64d()
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun readArray(context: Context, key: String): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun signalasiIdOf(json: JSONObject): String =
        json.optString("signalasi_id")
            .ifBlank { json.optString("hermes_id") }
            .ifBlank { json.optString("id") }

    private fun putSignalasiId(json: JSONObject, id: String): JSONObject {
        if (id.isNotBlank()) json.put("signalasi_id", id)
        json.remove("hermes_id")
        return json
    }

    private fun normalizeSignalasiIds(context: Context) {
        val profile = readObject(context, KEY_PROFILE)
        var profileChanged = false
        if (profile.optString("signalasi_id").isBlank() && profile.optString("hermes_id").isNotBlank()) {
            putSignalasiId(profile, profile.optString("hermes_id"))
            profileChanged = true
        } else if (profile.has("hermes_id")) {
            profile.remove("hermes_id")
            profileChanged = true
        }
        if (profileChanged) writeObject(context, KEY_PROFILE, profile)

        val contacts = readArray(context, KEY_CONTACTS)
        if (normalizeSignalasiIdsInArray(contacts)) writeArray(context, KEY_CONTACTS, contacts)

        val requests = readArray(context, KEY_FRIEND_REQUESTS)
        if (normalizeSignalasiIdsInArray(requests)) writeArray(context, KEY_FRIEND_REQUESTS, requests)
    }

    private fun normalizeSignalasiIdsInArray(array: JSONArray): Boolean {
        var changed = false
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val oldId = item.optString("hermes_id")
            if (item.optString("signalasi_id").isBlank() && oldId.isNotBlank()) {
                putSignalasiId(item, oldId)
                changed = true
            } else if (item.has("hermes_id")) {
                item.remove("hermes_id")
                changed = true
            }
        }
        return changed
    }

    private fun readObject(context: Context, key: String): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "{}") ?: "{}"
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun writeArray(context: Context, key: String, value: JSONArray) {
        val raw = value.toString()
        if (key == KEY_CONTACTS) {
            contactsCacheRaw = ""
            contactsCacheById = emptyMap()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, raw).apply()
    }

    private fun writeObject(context: Context, key: String, value: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value.toString()).apply()
    }

    private fun removeChatHistory(context: Context, contactId: String) {
        val prefs = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        val root = runCatching { JSONObject(prefs.getString("messages", "{}") ?: "{}") }.getOrDefault(JSONObject())
        root.remove(contactId)
        prefs.edit().putString("messages", root.toString()).apply()
    }

    private fun ByteArray.b64(): String =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

    private fun String.b64d(): ByteArray =
        android.util.Base64.decode(this, android.util.Base64.DEFAULT)
}

