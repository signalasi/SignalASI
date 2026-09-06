package com.galaxyssi.chat

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
    @Volatile private var contactsCacheRevision = 0L
    private const val PREFS = "galaxyssi_app_store"
    private const val TRUST_PREFS = "galaxyssi_signal_trust"
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
        thread(name = "galaxyssi-initial-backup", isDaemon = true) {
            createInitialPrivateBackup(context.applicationContext)
        }
    }

    private fun initializeOnce(appContext: Context) {
        GalaxySSICrypto.initialize(appContext)
        val prefs = storage(appContext)
        if (!prefs.contains(KEY_PROFILE)) {
            prefs.writeString(KEY_PROFILE, defaultProfile(appContext).toString())
        }
        if (!prefs.contains(KEY_CONTACTS)) {
            prefs.writeString(KEY_CONTACTS, JSONArray().toString())
        }
        if (!prefs.contains(KEY_FRIEND_REQUESTS)) {
            prefs.writeString(KEY_FRIEND_REQUESTS, JSONArray().toString())
        }
        normalizeGalaxySSIIds(appContext)
        removeLegacyDesktopConnectorContacts(appContext)
        removeDesktopCloudModelContacts(appContext)
        normalizeCloudApiProviderContacts(appContext)
        removeContactsForMissingServerLinks(appContext)
        normalizeVerifiedPhoneRelationshipRoutes(appContext)
    }

    fun profile(context: Context): JSONObject {
        ensureInitialized(context)
        val current = readObject(context, KEY_PROFILE)
        var changed = false
        if (GalaxySSIDeviceIdentityName.isLegacyDefault(current.optString("name"))) {
            current.put("name", GalaxySSIDeviceIdentityName.current(context))
            changed = true
        }
        if (!GalaxySSILinkProtocol.validRouteId(current.optString("device_id"))) {
            current.put("device_id", GalaxySSILinkProtocol.newRouteId())
            changed = true
        }
        if (current.optLong("created_at") <= 0L) {
            current.put("created_at", System.currentTimeMillis())
            changed = true
        }
        if (changed) writeObject(context, KEY_PROFILE, current)
        return current.apply {
            putGalaxySSIId(this, GalaxySSICrypto.localGalaxySSIId())
            put("identity_fingerprint", GalaxySSICrypto.localIdentitySha256())
            put("identity_public_key", GalaxySSICrypto.localIdentityPublicKey())
        }
    }

    fun updateProfileName(context: Context, name: String): JSONObject {
        ensureInitialized(context)
        val cleaned = name.trim().ifBlank { GalaxySSIDeviceIdentityName.current(context) }
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
        return contactsSnapshot(context)
    }

    internal fun encodedContactsSnapshot(context: Context): AppStoreContactsSnapshot {
        ensureInitialized(context)
        ensureContactsCache(context)
        return synchronized(contactsCacheLock) {
            AppStoreContactsSnapshot(contactsCacheRevision, contactsCacheRaw)
        }
    }

    fun friendRequests(context: Context): JSONArray {
        ensureInitialized(context)
        return readArray(context, KEY_FRIEND_REQUESTS)
    }

    fun hasPendingFriendRequest(context: Context, galaxyssiId: String): Boolean {
        val requests = friendRequests(context)
        return (0 until requests.length()).any { index ->
            val request = requests.optJSONObject(index) ?: return@any false
            galaxyssiIdOf(request) == galaxyssiId && request.optString("status") == "pending"
        }
    }

    fun approvedIncomingPhoneContactIds(context: Context): List<String> {
        val requests = friendRequests(context)
        return buildList {
            for (index in 0 until requests.length()) {
                val request = requests.optJSONObject(index) ?: continue
                if (request.optString("status") != "approved" ||
                    request.optString("direction") != "incoming" ||
                    phoneRoutes(request) == null
                ) continue
                galaxyssiIdOf(request).takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct()
    }

    internal fun replaceDebugState(context: Context, state: JSONObject) {
        check(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "Debug state replacement is unavailable in release builds"
        }
        ensureInitialized(context)
        state.optJSONObject("profile")?.let { writeObject(context, KEY_PROFILE, JSONObject(it.toString())) }
        state.optJSONArray("contacts")?.let {
            require(it.length() <= 500) { "Debug contact fixture is too large" }
            writeArray(context, KEY_CONTACTS, JSONArray(it.toString()))
        }
        state.optJSONArray("friend_requests")?.let {
            require(it.length() <= 500) { "Debug friend request fixture is too large" }
            writeArray(context, KEY_FRIEND_REQUESTS, JSONArray(it.toString()))
        }
    }

    internal fun patchDebugContact(context: Context, contactId: String, patch: JSONObject): Boolean {
        check(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "Debug contact patching is unavailable in release builds"
        }
        require(contactId.isNotBlank()) { "Debug contact id is required" }
        ensureInitialized(context)
        val current = contacts(context)
        for (index in 0 until current.length()) {
            val contact = current.optJSONObject(index) ?: continue
            if (contact.optString("id") != contactId && galaxyssiIdOf(contact) != contactId) continue
            patch.keys().forEach { key -> contact.put(key, patch.opt(key)) }
            writeArray(context, KEY_CONTACTS, current)
            return true
        }
        return false
    }

    fun addFriendRequest(context: Context, request: JSONObject) {
        ensureInitialized(context)
        val requests = friendRequests(context)
        val galaxyssiId = galaxyssiIdOf(request)
        val existingContact = findContactByGalaxySSIId(contacts(context), galaxyssiId)
        val wasDeleted = existingContact?.let {
            it.optBoolean("deleted", false) || it.optString("trust_state") == "deleted"
        } ?: false
        val existing = (0 until requests.length()).firstOrNull {
            galaxyssiIdOf(requests.optJSONObject(it) ?: JSONObject()) == galaxyssiId
        }
        val previous = existing?.let(requests::optJSONObject)
        val stored = previous?.let { JSONObject(it.toString()) } ?: JSONObject()
        request.keys().forEach { key -> stored.put(key, request.opt(key)) }
        val direction = request.optString("direction")
            .ifBlank { previous?.optString("direction").orEmpty() }
            .ifBlank { "incoming" }
        stored
            .put(
                "id",
                previous?.optString("id").orEmpty()
                    .ifBlank { request.optString("id") }
                    .ifBlank { "req_${System.currentTimeMillis()}" }
            )
            .put("status", "pending")
            .put(
                "created_at",
                previous?.optLong("created_at")?.takeIf { it > 0L }
                    ?: System.currentTimeMillis()
            )
            .put(
                "direction",
                direction
            )
            .put("is_read", FriendRequestUnreadPolicy.isReadForPendingRequest(previous, direction))
            .put("previously_deleted", wasDeleted)
            .put("readd_required", wasDeleted)
        putGalaxySSIId(stored, galaxyssiId)
        if (existing == null) {
            requests.put(stored)
        } else {
            requests.put(existing, stored)
        }
        writeArray(context, KEY_FRIEND_REQUESTS, requests)
    }

    fun unreadFriendRequestCount(context: Context): Int =
        FriendRequestUnreadPolicy.unreadCount(friendRequests(context))

    fun markFriendRequestsRead(context: Context): Int {
        val requests = friendRequests(context)
        val changed = FriendRequestUnreadPolicy.markIncomingPendingRead(requests)
        if (changed > 0) writeArray(context, KEY_FRIEND_REQUESTS, requests)
        return changed
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
                GalaxySSICrypto.processPeerBundle(
                    signalBundle,
                    galaxyssiIdOf(request),
                    request.optString("identity_fingerprint")
                )
            } else {
                false
            }
            val contact = JSONObject()
                .put("id", galaxyssiIdOf(request))
                .put("name", request.optString("name", "Friend"))
                .put("avatar", request.optString("avatar", ""))
                .put("type", request.optString("type", "person"))
                .also { putGalaxySSIId(it, galaxyssiIdOf(request)) }
                .put("identity_public_key", request.optString("identity_public_key"))
                .put("identity_fingerprint", request.optString("identity_fingerprint"))
                .put("client_route_id", request.optString("client_route_id"))
                .put("link_secret", request.optString("link_secret"))
                .put("local_identity_fingerprint", request.optString("local_identity_fingerprint"))
                .put("signal_bundle", signalBundle)
                .apply {
                    request.optJSONObject("contact_card")?.let {
                        put("contact_card", JSONObject(it.toString()))
                    }
                }
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
            ChatHistoryStore.appendSystemNotification(
                context,
                context.getString(
                    R.string.phone_contact_added_notice,
                    request.optString("name", context.getString(R.string.fallback_contact_name))
                ),
                "phone-contact-approved:${request.optString("id")}"
            )
            if (!signalReady) {
                GalaxySSIMqttClient.requestSignalBundleForContact(context, galaxyssiIdOf(request))
            }
            return true
        }
        return false
    }

    fun approveFriendRequestForGalaxySSIId(context: Context, galaxyssiId: String): Boolean {
        ensureInitialized(context)
        val requests = friendRequests(context)
        for (i in 0 until requests.length()) {
            val request = requests.optJSONObject(i) ?: continue
            if (galaxyssiIdOf(request) == galaxyssiId && request.optString("status") == "pending") {
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

    fun rejectFriendRequestForGalaxySSIId(context: Context, galaxyssiId: String): Boolean {
        ensureInitialized(context)
        val requests = friendRequests(context)
        for (i in 0 until requests.length()) {
            val request = requests.optJSONObject(i) ?: continue
            if (galaxyssiIdOf(request) != galaxyssiId || request.optString("status") != "pending") continue
            request
                .put("status", "rejected")
                .put("rejected_at", System.currentTimeMillis())
            writeArray(context, KEY_FRIEND_REQUESTS, requests)
            return true
        }
        return false
    }

    fun deleteContact(context: Context, hermesId: String, deleteMessages: Boolean = false) {
        ensureInitialized(context)
        val targetContact = contactById(context, hermesId)
        val targetDesktopId = targetContact?.optString("desktop_id").orEmpty()
        if (targetContact?.optString("type") == "device" && targetDesktopId.isNotBlank()) {
            revokeDesktopConnector(context, targetDesktopId)
            if (deleteMessages) removeChatHistory(context, hermesId)
            return
        }
        if (hermesId == "hermes") {
            GalaxySSICrypto.clearPcTrust(context)
            GalaxySSIMqttClient.forgetSecureChannel()
        } else {
            GalaxySSICrypto.clearPeerTrust(context, hermesId)
        }
        val contacts = contacts(context)
        val deletedContactIds = linkedSetOf<String>()
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            val isTarget = galaxyssiIdOf(contact) == hermesId || contact.optString("id") == hermesId
            val isChildOfHermes = hermesId == "hermes" && (
                contact.optString("parent_contact") == "hermes" ||
                    contact.optString("delivery_mode") == "pc_connector"
            )
            if (isTarget || isChildOfHermes) {
                contact.optString("id").ifBlank { galaxyssiIdOf(contact) }
                    .takeIf(String::isNotBlank)
                    ?.let(deletedContactIds::add)
                if (isTarget && contact.optString("delivery_mode") == "cloud_api") {
                    clearCloudProviderCredentials(contact)
                }
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
            if (galaxyssiIdOf(request) == hermesId || request.optString("id") == hermesId) {
                request.put("status", "deleted")
                request.put("deleted_at", System.currentTimeMillis())
                request.put("readd_required", true)
                requestsChanged = true
            }
        }
        if (requestsChanged) writeArray(context, KEY_FRIEND_REQUESTS, requests)
        if (deleteMessages) {
            deletedContactIds.ifEmpty { setOf(hermesId) }.forEach { contactId ->
                removeChatHistory(context, contactId)
            }
        }
    }

    fun canCommunicateWith(context: Context, hermesId: String): Boolean {
        val contact = contactById(context, hermesId) ?: return false
        return !contact.optBoolean("deleted", false) &&
            contact.optString("trust_state") == "verified"
    }

    fun contactById(context: Context, hermesId: String): JSONObject? {
        ensureInitialized(context)
        ensureContactsCache(context)
        return contactsCacheById[hermesId]?.let { JSONObject(it) }
    }

    fun updateContactName(context: Context, hermesId: String, name: String): Boolean {
        if (hermesId.isBlank() || name.isBlank()) return false
        ensureInitialized(context)
        val contacts = contacts(context)
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            val id = galaxyssiIdOf(contact)
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
            val id = item.optString("id").ifBlank { galaxyssiIdOf(item) }
            id == contactId || (
                item.optString("delivery_mode") == "cloud_api" &&
                    providerKey(item.optString("cloud_provider")) == providerKey(providerName)
                )
        }
        val contact = existingIndex?.let { contacts.optJSONObject(it) } ?: JSONObject()
        contact.put("id", contactId)
            .also { putGalaxySSIId(it, contactId) }
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
        configureCloudProviderModel(
            contact = contact,
            model = cloudModelEntry(
                displayName.ifBlank { modelId },
                modelId,
                endpoint,
                apiKey.trim(),
                apiStyle,
                now
            ),
            selectedModelId = modelId,
            updatedAt = now
        )
        if (existingIndex == null) {
            upsertContact(contacts, contact)
        } else {
            contacts.put(existingIndex, contact)
        }
        writeArray(context, KEY_CONTACTS, contacts)
        markCloudProviderConfigurationAvailable(context, contact)
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
        val linkExisted = GalaxySSILinkProtocol.serverLink(context, desktopId) != null
        GalaxySSIMqttClient.publishServerRevocation(context, desktopId)
        val removed = DesktopPairingLifecycle.remove(context, desktopId)
        return removed.contactIds.isNotEmpty() || linkExisted
    }

    fun setSelectedCloudModel(context: Context, hermesId: String, modelId: String): Boolean {
        if (modelId.isBlank()) return false
        ensureInitialized(context)
        val contacts = contacts(context)
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            val id = contact.optString("id").ifBlank { galaxyssiIdOf(contact) }
            if (id != hermesId && galaxyssiIdOf(contact) != hermesId) continue
            val models = contact.optJSONArray("cloud_models") ?: return false
            if (findCloudModel(models, modelId) == null) return false
            contact.put("selected_cloud_model", modelId)
            applySelectedCloudModelFields(contact)
            writeArray(context, KEY_CONTACTS, contacts)
            markCloudProviderConfigurationAvailable(context, contact)
            return true
        }
        return false
    }

    fun outgoingTopicForContact(context: Context, hermesId: String): String? {
        if (hermesId.startsWith("group:")) return null
        normalizeVerifiedPhoneRelationshipRoutes(context)
        val contact = contactById(context, hermesId) ?: return null
        if (!canCommunicateWith(context, hermesId)) return null
        val desktopId = contact.optString("desktop_id")
        if (desktopId.isNotBlank()) {
            val link = GalaxySSILinkProtocol.serverLink(context, desktopId) ?: return null
            return link.takeIf { GalaxySSILinkProtocol.isCryptographicallyReady(context, it) }?.routes?.up
        }
        return phoneRoutes(contact)?.up
    }

    fun phoneRelationshipForTopic(context: Context, topic: String): JSONObject? {
        normalizeVerifiedPhoneRelationshipRoutes(context)
        val records = buildList {
            val contacts = contacts(context)
            for (index in 0 until contacts.length()) contacts.optJSONObject(index)?.let(::add)
            val requests = friendRequests(context)
            for (index in 0 until requests.length()) requests.optJSONObject(index)?.let(::add)
        }
        return records.firstOrNull { record ->
            phoneRoutes(record)?.receiveWindow?.contains(topic) == true
        }?.let { JSONObject(it.toString()) }
    }

    fun phoneReceiveTopics(context: Context): Set<String> {
        normalizeVerifiedPhoneRelationshipRoutes(context)
        val topics = linkedSetOf<String>()
        val contacts = contacts(context)
        for (index in 0 until contacts.length()) {
            contacts.optJSONObject(index)?.let(::phoneRoutes)?.receiveWindow?.let(topics::addAll)
        }
        val requests = friendRequests(context)
        for (index in 0 until requests.length()) {
            requests.optJSONObject(index)?.let(::phoneRoutes)?.receiveWindow?.let(topics::addAll)
        }
        return topics
    }

    fun phoneLinkSecretForOutgoingTopic(context: Context, topic: String): String? {
        normalizeVerifiedPhoneRelationshipRoutes(context)
        val records = buildList {
            val contacts = contacts(context)
            for (index in 0 until contacts.length()) contacts.optJSONObject(index)?.let(::add)
            val requests = friendRequests(context)
            for (index in 0 until requests.length()) requests.optJSONObject(index)?.let(::add)
        }
        return records.firstNotNullOfOrNull { record ->
            phoneRoutes(record)?.takeIf { topic in it.sendWindow }?.linkSecret
        }
    }

    fun phoneRoutesForIdentity(context: Context, galaxyssiId: String): GalaxySSILinkProtocol.Routes? {
        normalizeVerifiedPhoneRelationshipRoutes(context)
        return contactById(context, galaxyssiId)?.let(::phoneRoutes)
            ?: (0 until friendRequests(context).length()).firstNotNullOfOrNull { index ->
                val request = friendRequests(context).optJSONObject(index) ?: return@firstNotNullOfOrNull null
                phoneRoutes(request).takeIf { galaxyssiIdOf(request) == galaxyssiId }
            }
    }

    fun refreshTrustedPhoneRelationship(
        context: Context,
        remoteCard: JSONObject,
        linkSecret: String,
        clientRouteId: String
    ): Boolean {
        ensureInitialized(context)
        val remoteId = remoteCard.optString("galaxyssi_id")
        if (remoteId.isBlank()) return false
        val contacts = contacts(context)
        for (index in 0 until contacts.length()) {
            val existing = contacts.optJSONObject(index) ?: continue
            if (galaxyssiIdOf(existing) != remoteId && existing.optString("id") != remoteId) continue
            val refreshed = PhoneRelationshipRouteRefresh.apply(
                existing = existing,
                remoteCard = remoteCard,
                linkSecret = linkSecret,
                clientRouteId = clientRouteId,
                localFingerprint = GalaxySSICrypto.localIdentitySha256()
            ) ?: return false
            contacts.put(index, refreshed)
            writeArray(context, KEY_CONTACTS, contacts)
            return true
        }
        return false
    }

    private fun phoneRoutes(record: JSONObject): GalaxySSILinkProtocol.Routes? = runCatching {
        GalaxySSILinkProtocol.Routes(
            record.getString("client_route_id"),
            record.getString("link_secret"),
            record.getString("local_identity_fingerprint"),
            record.getString("identity_fingerprint")
        )
    }.getOrNull()

    fun deleteDesktopConnector(
        context: Context,
        desktopId: String,
        deleteMessages: Boolean = false
    ): Set<String> {
        if (desktopId.isBlank()) return emptySet()
        ensureInitialized(context)
        val contacts = contacts(context)
        val kept = JSONArray()
        val removedIds = linkedSetOf<String>()
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            val contactId = contact.optString("id").ifBlank { galaxyssiIdOf(contact) }
            val belongsToDesktop = DesktopPairingLifecycle.belongsToDesktop(
                contact,
                desktopId
            )
            if (belongsToDesktop) {
                contactId.takeIf(String::isNotBlank)?.let(removedIds::add)
            } else {
                kept.put(contact)
            }
        }
        if (removedIds.isNotEmpty()) writeArray(context, KEY_CONTACTS, kept)
        if (deleteMessages) removedIds.forEach { removeChatHistory(context, it) }
        return removedIds
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

    fun isDesktopDeviceContact(context: Context, contactId: String): Boolean {
        val contact = contactById(context, contactId) ?: return false
        return contact.optString("type") == "device" &&
            contact.optString("agent_kind") == "device" &&
            contact.optString("agent_id") == "desktop" &&
            contact.optString("desktop_id").isNotBlank()
    }

    fun isPersonContact(context: Context, contactId: String): Boolean {
        val contact = contactById(context, contactId) ?: return false
        return contact.optString("type") == "person" &&
            contact.optString("trust_state") == "verified" &&
            !contact.optBoolean("deleted", false)
    }

    fun isDirectPeerContact(context: Context, contactId: String): Boolean =
        isPersonContact(context, contactId) || isDesktopDeviceContact(context, contactId)

    fun contactCard(context: Context, contactId: String): JSONObject? =
        contactById(context, contactId)?.optJSONObject("contact_card")
            ?: friendRequests(context).let { requests ->
                (0 until requests.length())
                    .mapNotNull { requests.optJSONObject(it) }
                    .firstOrNull { galaxyssiIdOf(it) == contactId }
                    ?.optJSONObject("contact_card")
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
                GalaxySSICrypto.processPcBundleForDesktop(
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
            val contactId = galaxyssiIdOf(contact)
            if (contactId != from && contact.optString("id") != from) continue
            val expectedFingerprint = contact.optString("identity_fingerprint")
            val ready = GalaxySSICrypto.processPeerBundle(
                bundle,
                contactId,
                expectedFingerprint,
                replaceExisting = response.optBoolean("session_recovery", false)
            )
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
            if (galaxyssiIdOf(request) != from) continue
            if (!GalaxySSICrypto.signalBundleFingerprint(bundle).equals(
                    request.optString("identity_fingerprint"),
                    ignoreCase = true
                )
            ) return false
            request.put("signal_bundle", bundle)
            request.put("signal_bundle_updated_at", System.currentTimeMillis())
            val ready = GalaxySSICrypto.processPeerBundle(
                bundle,
                from,
                request.optString("identity_fingerprint"),
                replaceExisting = response.optBoolean("session_recovery", false)
            )
            request.put("signal_session", if (ready) "ready" else "missing")
            writeArray(context, KEY_FRIEND_REQUESTS, requests)
            return ready
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
        val link = GalaxySSILinkProtocol.ensureServerLink(context, pairingQr)
        val desktopId = pairingQr.optString("desktop_id")
            .ifBlank { "desktop_${pairingQr.optString("identity_key_sha256").take(16)}" }
        val desktopName = pairingQr.optString("desktop_name").ifBlank { context.getString(R.string.default_desktop_name) }
        val fingerprint = pairingQr.optString("identity_key_sha256")
            .ifBlank { pairingQr.optString("identity_fingerprint") }
        val now = System.currentTimeMillis()
        upsertDesktopDeviceContact(context, pairingQr, link, now)
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
            Triple("openclaw", "OpenClaw", "local-cli"),
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
                    .put("desktop_access_profile", link.accessProfile)
                    .put("desktop_access_scopes", JSONArray(link.accessScopes.sorted()))
                    .put("status", "unknown")
                    .put("detail", "Waiting for GalaxySSI Desktop status")
                    .put("setup", "")
                    .put("updated_at", now)
            )
        }
        updateConnectorAgentStatuses(context, fallbackAgents)
    }

    fun updateDesktopDeviceContact(context: Context, payload: JSONObject): Boolean {
        ensureInitialized(context)
        val desktopId = payload.optString("desktop_id")
        val link = GalaxySSILinkProtocol.serverLink(context, desktopId) ?: return false
        return upsertDesktopDeviceContact(context, payload, link, System.currentTimeMillis())
    }

    private fun upsertDesktopDeviceContact(
        context: Context,
        payload: JSONObject,
        link: GalaxySSILinkProtocol.ServerLink,
        now: Long
    ): Boolean {
        val desktopId = payload.optString("desktop_id").ifBlank { link.desktopId }
        if (desktopId.isBlank()) return false
        val device = payload.optJSONObject("desktop_device") ?: JSONObject()
        val defaultName = payload.optString("desktop_display_name")
            .ifBlank { device.optString("display_name") }
            .ifBlank { payload.optString("desktop_name") }
            .ifBlank { context.getString(R.string.default_desktop_name) }
        val fingerprint = payload.optString("desktop_fingerprint")
            .ifBlank { payload.optString("identity_key_sha256") }
            .ifBlank { payload.optString("identity_fingerprint") }
        val contacts = contacts(context)
        val existing = findContactByGalaxySSIId(contacts, desktopId)
        val contact = existing ?: JSONObject()
        if (!contact.optBoolean("user_renamed", false)) {
            contact.put("name", defaultName)
            contact.put("display_name", defaultName)
        }
        contact.put("id", desktopId)
        putGalaxySSIId(contact, desktopId)
        contact.put("default_display_name", defaultName)
        contact.put("type", "device")
        contact.put("agent_kind", "device")
        contact.put("agent_id", "desktop")
        contact.put("device_type", "desktop")
        contact.put("desktop_id", desktopId)
        contact.put("desktop_name", defaultName)
        contact.put("device_name", device.optString("device_name", defaultName))
        contact.put("device_manufacturer", device.optString("manufacturer"))
        contact.put("device_model", device.optString("model"))
        contact.put("platform", device.optString("platform", "desktop"))
        contact.put("platform_version", device.optString("platform_version"))
        contact.put("host_name", device.optString("host_name"))
        contact.put("delivery_mode", "pc_connector")
        contact.put("identity_fingerprint", fingerprint)
        contact.put("desktop_fingerprint", fingerprint)
        contact.put("trust_state", "verified")
        contact.put("signal_session", "pc_tunnel")
        contact.put("setup_status", "ready")
        contact.put("setup_detail", context.getString(R.string.common_paired))
        contact.put("updated_at", now)
        contact.put("created_at", contact.optLong("created_at").takeIf { it > 0 } ?: now)
        contact.put("deleted", false)
        upsertContact(contacts, contact)
        writeArray(context, KEY_CONTACTS, contacts)
        return true
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
                    val fp = agent.optString("desktop_fingerprint").ifBlank { GalaxySSICrypto.verifiedPcFingerprint() }
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
            val fingerprint = agent.optString("desktop_fingerprint").ifBlank { GalaxySSICrypto.verifiedDesktopFingerprint(desktopId) }
            var found = false
            for (j in 0 until contacts.length()) {
                val contact = contacts.optJSONObject(j) ?: continue
                val contactId = contact.optString("id").ifBlank { galaxyssiIdOf(contact) }
                if (contactId != id && galaxyssiIdOf(contact) != id) continue
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
                    agentId
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
        val defaultDisplayName = "$agentName · $desktopName"
        if (!contact.optBoolean("user_renamed", false)) {
            contact.put("name", defaultDisplayName)
            contact.put("display_name", defaultDisplayName)
        }
        contact.put("agent_name", agentName)
        contact.put("type", "agent")
        contact.put("desktop_id", desktopId)
        contact.put("desktop_name", desktopName)
        contact.put("agent_id", agentId)
        contact.put("parent_contact", desktopId)
        contact.put("delivery_mode", "pc_connector")
        putGalaxySSIId(contact, id)
        contact.put("identity_fingerprint", fingerprint)
        contact.put("desktop_fingerprint", fingerprint)
        val link = GalaxySSILinkProtocol.serverLink(context, desktopId)
        contact.put(
            "desktop_access_profile",
            agent.optString("desktop_access_profile").ifBlank {
                link?.accessProfile ?: GalaxySSILinkProtocol.ACCESS_RESTRICTED
            }
        )
        contact.put(
            "desktop_access_scopes",
            agent.optJSONArray("desktop_access_scopes")
                ?: JSONArray(link?.accessScopes?.sorted().orEmpty())
        )
        contact.put("agent_kind", agent.optString("kind", contact.optString("agent_kind", "custom-cli")))
        val adapter = agent.optJSONObject("adapter")
        if (adapter != null) {
            contact.put("adapter", JSONObject(adapter.toString()))
        }
        val capabilities = agent.optJSONArray("capabilities")
            ?: adapter?.optJSONArray("capabilities")
        if (capabilities != null) {
            contact.put("capabilities", JSONArray(capabilities.toString()))
            contact.put("capabilities_hash", capabilities.toString().hashCode().toUInt().toString(16))
        }
        agent.optJSONObject("provider_profile")?.let { profile ->
            contact.put("provider_profile", JSONObject(profile.toString()))
        }
        agent.optJSONObject("invocation_profile")?.let { profile ->
            contact.put("invocation_profile", JSONObject(profile.toString()))
        }
        val protocols = agent.optJSONArray("protocols")
            ?: adapter?.optJSONArray("protocols")
        if (protocols != null) {
            contact.put("protocols", JSONArray(protocols.toString()))
            contact.put("protocol_version", protocols.optString(0, "1.0"))
            contact.put("protocol_min_version", protocols.optString(protocols.length() - 1, "1.0"))
            contact.put("protocol_max_version", protocols.optString(0, "1.0"))
        }
        adapter?.optJSONArray("features")?.let { protocolFeatures ->
            contact.put("protocol_features", JSONArray(protocolFeatures.toString()))
        }
        contact.put("setup_status", agent.optString("status", "needs_setup"))
        contact.put("active_runs", agent.optInt("active_tasks", 0).coerceAtLeast(0))
        contact.put("setup_detail", agent.optString("detail"))
        contact.put("setup_next_step", agent.optString("setup"))
        agent.optJSONObject("reputation")?.let { reputation ->
            contact.put("agent_reputation", JSONObject(reputation.toString()))
        }
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
            .also { putGalaxySSIId(it, groupId) }
            .put("identity_fingerprint", "")
            .put("trust_state", "verified")
            .put("members", JSONArray().put(profile(context).getString("galaxyssi_id")))
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
        val selfId = profile(context).getString("galaxyssi_id")
        members.put(selfId)
        memberIds.distinct().filter { it.isNotBlank() && it != selfId }.forEach { members.put(it) }
        val group = JSONObject()
            .put("id", groupId)
            .put("name", name.ifBlank { "New Group" })
            .put("avatar", "")
            .put("type", "group")
            .also { putGalaxySSIId(it, groupId) }
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
        val selfId = profile(context).optString("galaxyssi_id")
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
        val selfId = profile(context).getString("galaxyssi_id")
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            val id = contact.optString("id").ifBlank { galaxyssiIdOf(contact) }
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
            .also { putGalaxySSIId(it, groupId) }
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
        return PhoneContactCard.createQr(context, profile(context))
    }

    fun importContactQrAsRequest(context: Context, contents: String): Boolean {
        val encoded = runCatching { JSONObject(contents) }.getOrNull() ?: return false
        val json = PhoneContactCard.normalizeQr(encoded) ?: encoded
        val type = json.optString("type")
        if (type != PhoneContactCard.TYPE) return false
        val fingerprint = json.optString("identity_fingerprint", json.optString("identity_key_sha256"))
        val publicKey = json.optString("identity_public_key", json.optString("identity_key"))
        if (fingerprint.isBlank() || publicKey.isBlank()) return false
        val galaxyssiId = json.optString("galaxyssi_id")
        if (galaxyssiId == GalaxySSICrypto.localGalaxySSIId()) return false
        if (type == PhoneContactCard.TYPE) {
            if (!PhoneContactCard.isQrOfferValid(json)) return false
        }
        val signalBundle = json.optJSONObject("signal_bundle")
        if (signalBundle != null &&
            !GalaxySSICrypto.processPeerBundle(signalBundle, galaxyssiId, fingerprint)
        ) return false
        val derivedRoutes = GalaxySSICrypto.derivePhoneRelationshipRoutes(
            publicKey,
            fingerprint
        ) ?: return false
        val localFingerprint = derivedRoutes.localFingerprint
        val linkSecret = derivedRoutes.linkSecret
        val existingContact = contactById(context, galaxyssiId)
        val existingRoutes = existingContact?.let(::phoneRoutes)
        if (existingContact != null &&
            existingContact.optString("trust_state") == "verified" &&
            !existingContact.optBoolean("deleted", false)
        ) {
            if (!existingContact.optString("identity_fingerprint").equals(fingerprint, ignoreCase = true)) {
                return false
            }
            val refreshed = refreshTrustedPhoneRelationship(
                context = context,
                remoteCard = json,
                linkSecret = linkSecret,
                clientRouteId = derivedRoutes.clientRouteId
            )
            if (!refreshed) return false
            return true
        }
        val request = JSONObject()
                .put("name", json.optString("name", "Friend"))
                .put("type", "person")
                .also { putGalaxySSIId(it, galaxyssiId) }
                .put("identity_public_key", publicKey)
                .put("identity_fingerprint", fingerprint)
                .apply { signalBundle?.let { put("signal_bundle", it) } }
                .put("client_route_id", derivedRoutes.clientRouteId)
                .put("link_secret", linkSecret)
                .put("local_identity_fingerprint", localFingerprint)
                .put("pairing_token", json.optString("pairing_token"))
                .put("pairing_secret", json.optString("pairing_secret"))
                .put("pairing_topic", json.optString("pairing_topic"))
                .put("source", "qr")
                .put("direction", "outgoing")
        request.put("contact_card", JSONObject(json.toString()))
        addFriendRequest(context, request)
        return true
    }

    fun importPhoneContactRequest(
        context: Context,
        payload: JSONObject,
        linkSecret: String,
        clientRouteId: String
    ): Boolean {
        if (payload.optString("type") !in setOf(
                PhoneContactCard.REQUEST_TYPE,
                PhoneContactCard.BUNDLE_RESPONSE_TYPE,
                PhoneContactCard.BUNDLE_REFRESH_TYPE,
                PhoneContactCard.APPROVAL_TYPE,
                PhoneContactCard.REJECTION_TYPE
            ) ||
            !PhoneContactCard.isAddressedToLocalIdentity(payload, GalaxySSICrypto.localGalaxySSIId())
        ) return false
        val card = PhoneContactCard.cardFromControlPayload(payload) ?: return false
        if (!GalaxySSICrypto.verifyPublicIdentitySignature(
                card.optString("identity_public_key"),
                card.optString("identity_fingerprint"),
                PhoneContactCard.canonicalBytes(card),
                card.optString("signature")
            )
        ) return false
        val bundle = card.optJSONObject("signal_bundle") ?: return false
        if (!GalaxySSICrypto.signalBundleFingerprint(bundle).equals(
                card.optString("identity_fingerprint"),
                ignoreCase = true
            )
        ) return false
        val senderId = card.optString("galaxyssi_id")
        if (payload.optString("type") == PhoneContactCard.REJECTION_TYPE) {
            return rejectFriendRequestForGalaxySSIId(context, senderId)
        }
        val localFingerprint = GalaxySSICrypto.localIdentitySha256()
        if (!GalaxySSILinkProtocol.validLinkSecret(linkSecret) ||
            !GalaxySSILinkProtocol.validRouteId(clientRouteId)
        ) return false
        val request = JSONObject()
            .put("name", card.optString("name", "Friend"))
            .put("type", "person")
            .also { putGalaxySSIId(it, senderId) }
            .put("identity_public_key", card.optString("identity_public_key"))
            .put("identity_fingerprint", card.optString("identity_fingerprint"))
            .put("signal_bundle", bundle)
            .put("client_route_id", clientRouteId)
            .put("link_secret", linkSecret)
            .put("local_identity_fingerprint", localFingerprint)
            .put("contact_card", JSONObject(card.toString()))
            .put("source", "opaque_pairing")
            .put(
                "direction",
                if (payload.optString("type") == PhoneContactCard.REQUEST_TYPE) {
                    "incoming"
                } else {
                    friendRequestForGalaxySSIId(context, senderId)
                        ?.optString("direction")
                        .orEmpty()
                        .ifBlank { "outgoing" }
                }
            )
        if (hasPendingFriendRequest(context, senderId) || !canCommunicateWith(context, senderId)) {
            addFriendRequest(context, request)
        }
        val sessionRecovery = PeerSignalBundlePolicy.replacesExistingSession(
            payload.optString("type")
        )
        return applySignalBundleResponse(
            context,
            JSONObject()
                .put("from", senderId)
                .put("signal_bundle", bundle)
                .put("session_recovery", sessionRecovery)
            )
    }

    fun friendRequestForGalaxySSIId(context: Context, galaxyssiId: String): JSONObject? {
        ensureInitialized(context)
        val requests = friendRequests(context)
        for (index in 0 until requests.length()) {
            val request = requests.optJSONObject(index) ?: continue
            if (galaxyssiIdOf(request) == galaxyssiId) return JSONObject(request.toString())
        }
        return null
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
            .put("identity", GalaxySSICrypto.exportSignalStoreJson(context))
            .put("profile", profile(context))
            .put("includes_contacts", includeContacts)
            .put("includes_messages", includeMessages)
            .put("includes_agent_data", true)
            .put(
                "privacy_manifest",
                AgentPrivateDataInventory.backupManifest(includeContacts, includeMessages)
            )
            .put("agent_data", AgentBackupData.export(context, includeSessionHistory = includeMessages))
        if (includeContacts) {
            payload.put("contacts", contacts(context))
            payload.put("friend_requests", friendRequests(context))
        }
        if (includeMessages) {
            payload.put("messages", ChatHistoryStore.readAll(context))
        }
        val encrypted = encryptBackup(payload.toString(), password)
        val backup = context.filesDir.resolve("backups").apply { mkdirs() }
            .resolve("galaxyssi_backup_${System.currentTimeMillis()}.hcbak")
        backup.writeText(encrypted.toString(), Charsets.UTF_8)
        return backup
    }

    fun importBackup(context: Context, file: File, password: String, includeMessages: Boolean = true) {
        require(file.isFile) { "Backup file not found." }
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val payload = JSONObject(decryptBackup(root, password))
        payload.optJSONObject("identity")?.let { GalaxySSICrypto.importSignalStoreJson(context, it) }
        payload.optJSONObject("profile")?.let { writeObject(context, KEY_PROFILE, it) }
        payload.optJSONArray("contacts")?.let { writeArray(context, KEY_CONTACTS, it) }
        payload.optJSONArray("friend_requests")?.let { writeArray(context, KEY_FRIEND_REQUESTS, it) }
        payload.optJSONObject("agent_data")?.let {
            AgentBackupData.restore(context, it)
            AgentWorkflowScheduler.restoreAll(context)
        }
        if (includeMessages) {
            payload.optJSONObject("messages")?.let {
                ChatHistoryStore.replaceAll(context, it)
            }
        }
    }

    fun destroyAllPrivateData(context: Context) {
        initialized = false
        contactsCacheRaw = ""
        contactsCacheById = emptyMap()
        AgentWorkflowScheduler.cancelAll(context)
        storage(context).clear()
        ChatHistoryStore.clear(context)
        ChatHistoryStore.close()
        AgentEncryptedPreferences(context, TRUST_PREFS).clear()
        AndroidPersistentSignalStore.clear(context)
        context.getSharedPreferences("galaxyssi_agent_runtime", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("galaxyssi_agent_memory", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("galaxyssi_agent_knowledge", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("galaxyssi_agent_knowledge_audit", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("galaxyssi_agent_tasks", Context.MODE_PRIVATE).edit().clear().commit()
        SQLiteAgentTaskStore(context).apply {
            clear()
            closeDefault()
        }
        context.getSharedPreferences(AgentTranscriptStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        AgentEncryptedDatabase(context, AgentTranscriptStore.PREFS).clear()
        AgentTranscriptEntryDatabase(context).clear()
        context.getSharedPreferences("galaxyssi_ui_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("galaxyssi_agent_safety", Context.MODE_PRIVATE).edit().clear().commit()
        SharedPreferencesAgentConfirmationConsentStore(context).clear()
        EncryptedAgentRunStartReceiptStore(context).clear()
        AgentEncryptedDatabase(context, EncryptedAgentDataDisclosureStore.DATABASE_NAME).clear()
        EncryptedAgentProviderHealthLedger(context).clear()
        GlobalAgentRepository(context).clear()
        context.getSharedPreferences("galaxyssi_agent_workflows", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("galaxyssi_agent_workflow_schedules", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("galaxyssi_agent_workflow_triggers", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("galaxyssi_agent_workflow_execution_history", Context.MODE_PRIVATE).edit().clear().commit()
        AgentWorkflowExecutionHistoryStore.clearAndCloseDefault(context)
        AgentConnectorResponseStore.clear(context)
        HomeAssistantSettingsStore.clear(context)
        CustomDeviceConnectorStore(context).clear()
        AgentModelPlannerSettingsStore(context).clear()
        EncryptedAgentSkillStore(context).clear()
        VoiceAssistantSettings.clear(context)
        GalaxySSILinkProtocol.clear(context)
        GalaxySSILinkDeliveryStore.clear(context)
        AgentEncryptedDatabase(context, "galaxyssi_agent_runs").clear()
        AgentSelfModelStore(context).clear()
        AgentEncryptedDatabase(context, EncryptedAgentWorkspaceStore.DATABASE_NAME).clear()
        context.databaseList().forEach { database -> runCatching { context.deleteDatabase(database) } }
        clearAllSharedPreferences(context)
        AgentRowStorageCipher.clearCachedKeys()
        runCatching { AgentStorageCipher.deleteMasterKey() }
        GalaxySSICrypto.resetLocalIdentity(context)
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
        val prefs = storage(context)
        prefs.writeString(KEY_PROFILE, defaultProfile(context).toString())
        prefs.writeString(KEY_CONTACTS, JSONArray().toString())
        prefs.writeString(KEY_FRIEND_REQUESTS, JSONArray().toString())
    }

    private fun createInitialPrivateBackup(context: Context) {
        val marker = context.filesDir.resolve("backups/.initial_backup_created")
        if (marker.exists()) return
        val prefs = storage(context)
        if (prefs.readString("initial_backup_in_progress", "false").toBoolean()) return
        runCatching {
            prefs.writeString("initial_backup_in_progress", "true")
            exportBackup(
                context,
                password = initialBackupPassword(context),
                includeContacts = true,
                includeMessages = false
            )
            marker.parentFile?.mkdirs()
            marker.writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
        }.also {
            prefs.writeString("initial_backup_in_progress", "false")
        }
    }

    private fun initialBackupPassword(context: Context): String {
        val prefs = storage(context)
        val existing = prefs.readString("initial_backup_secret", "")
        if (existing.isNotBlank()) return existing
        val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        prefs.writeString("initial_backup_secret", secret)
        return secret
    }

    private fun defaultProfile(context: Context): JSONObject =
        JSONObject()
            .put("name", GalaxySSIDeviceIdentityName.current(context))
            .put("device_id", GalaxySSILinkProtocol.newRouteId())
            .put("created_at", System.currentTimeMillis())

    private fun removeLegacyDesktopConnectorContacts(context: Context) {
        val contacts = readArray(context, KEY_CONTACTS)
        val cleaned = JSONArray()
        var changed = false
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            val id = contact.optString("id").ifBlank { galaxyssiIdOf(contact) }
            val hermesId = galaxyssiIdOf(contact).ifBlank { id }
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
            val isDesktopDeviceContact = id.startsWith("desktop_") &&
                !id.contains(":") &&
                contact.optString("type") == "device" &&
                contact.optString("desktop_id") == id
            if (shouldRemoveHermes || (isPcConnector && !isFlatDesktopContact && !isDesktopDeviceContact)) {
                changed = true
                continue
            }
            cleaned.put(contact)
        }
        if (changed) writeArray(context, KEY_CONTACTS, cleaned)
    }

    private fun removeContactsForMissingServerLinks(context: Context) {
        val activeDesktopIds = GalaxySSILinkProtocol.allServerLinks(context).map { it.desktopId }.toSet()
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
            val id = contact.optString("id").ifBlank { galaxyssiIdOf(contact) }
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
                    .also { putGalaxySSIId(it, cloudProviderContactId(providerName)) }
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
                    existingModels.optJSONObject(j)?.let { model ->
                        if (CloudModelCredentialPolicy.isDebugFixtureCredential(model.optString("api_key"))) {
                            changed = true
                        } else {
                            putUniqueCloudModel(models, model)
                        }
                    }
                }
            } else {
                val modelId = contact.optString("cloud_model")
                val apiKey = contact.optString("cloud_api_key")
                if (modelId.isNotBlank() && !CloudModelCredentialPolicy.isDebugFixtureCredential(apiKey)) {
                    putUniqueCloudModel(models, cloudModelEntry(
                        contact.optString("name", modelId),
                        modelId,
                        contact.optString("cloud_endpoint"),
                        apiKey,
                        contact.optString("cloud_api_style", "openai"),
                        contact.optLong("created_at", System.currentTimeMillis())
                    ))
                } else if (CloudModelCredentialPolicy.isDebugFixtureCredential(apiKey)) {
                    changed = true
                }
            }
            if (providerContact.optString("selected_cloud_model").isBlank()) {
                providerContact.put(
                    "selected_cloud_model",
                    contact.optString("selected_cloud_model").ifBlank { contact.optString("cloud_model") }
                )
            }
            val originalId = contact.optString("id").ifBlank { galaxyssiIdOf(contact) }
            if (originalId != providerContact.optString("id") || contact.optJSONArray("cloud_models") == null) {
                changed = true
            }
        }
        providers.values.forEach { providerContact ->
            if (providerContact.optJSONArray("cloud_models")?.length() == 0) {
                changed = true
                return@forEach
            }
            applySelectedCloudModelFields(providerContact)
            val desiredSetupStatus =
                if (CloudModelCredentialPolicy.isAutoRoutable(providerContact)) "ready" else "needs_setup"
            if (providerContact.optString("setup_status") != desiredSetupStatus) changed = true
            providerContact.put(
                "setup_status",
                desiredSetupStatus
            )
            cleaned.put(providerContact)
        }
        if (changed) writeArray(context, KEY_CONTACTS, cleaned)
    }

    private fun ensureConnectorAgents(context: Context) {
        val fingerprint = GalaxySSICrypto.verifiedPcFingerprint()
        if (fingerprint.isBlank()) return
        val contacts = readArray(context, KEY_CONTACTS)
        val hasVerifiedHermes = (0 until contacts.length()).any { index ->
            val contact = contacts.optJSONObject(index) ?: return@any false
            val isHermes = contact.optString("id") == "hermes" ||
                galaxyssiIdOf(contact) == "hermes" ||
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
                val existingId = galaxyssiIdOf(existing)
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
            .also { putGalaxySSIId(it, "hermes") }
            .put("identity_fingerprint", GalaxySSICrypto.verifiedPcFingerprint())
            .put("trust_state", if (approved) "verified" else "unverified")
            .put("created_at", System.currentTimeMillis())
            .put("deleted", false)

    private fun connectorAgentContacts(): List<JSONObject> {
        val fingerprint = GalaxySSICrypto.verifiedPcFingerprint()
        val now = System.currentTimeMillis()
        return listOf(
            connectorAgentContact("codex", "Codex Agent", "local-cli", fingerprint, now),
            connectorAgentContact("claude", "Claude Code", "local-cli", fingerprint, now),
            connectorAgentContact("openclaw", "OpenClaw", "local-cli", fingerprint, now),
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
        agentId: String = id
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
                .also { putGalaxySSIId(it, id) }
                .put("parent_contact", desktopId)
                .put("delivery_mode", "pc_connector")
                .put("identity_fingerprint", fingerprint)
                .put("desktop_fingerprint", fingerprint)
                .put("trust_state", "verified")
                .put("signal_session", "pc_tunnel")
                .put("setup_status", "unknown")
                .put("setup_detail", "Waiting for GalaxySSI Desktop status")
                .put("setup_next_step", "")
                .put("created_at", createdAt)
                .put("deleted", false)
        }

    private fun upsertContact(contacts: JSONArray, contact: JSONObject) {
        val id = galaxyssiIdOf(contact)
        for (i in 0 until contacts.length()) {
            val existing = contacts.optJSONObject(i) ?: continue
            if (galaxyssiIdOf(existing) == id || existing.optString("id") == id) {
                contacts.put(i, contact)
                return
            }
        }
        contacts.put(contact)
    }

    private fun findContactByGalaxySSIId(contacts: JSONArray, galaxyssiId: String): JSONObject? {
        if (galaxyssiId.isBlank()) return null
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            if (galaxyssiIdOf(contact) == galaxyssiId || contact.optString("id") == galaxyssiId) {
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

    internal fun configureCloudProviderModel(
        contact: JSONObject,
        model: JSONObject,
        selectedModelId: String,
        updatedAt: Long
    ): JSONObject {
        val models = contact.optJSONArray("cloud_models")
            ?: JSONArray().also { contact.put("cloud_models", it) }
        putUniqueCloudModel(models, JSONObject(model.toString()))
        val providerCredential = model.optString("api_key").trim()
        for (index in 0 until models.length()) {
            models.optJSONObject(index)?.apply {
                put("api_key", providerCredential)
                put("updated_at", updatedAt)
            }
        }
        contact.put("selected_cloud_model", selectedModelId)
        contact.put("deleted", false)
        contact.remove("deleted_at")
        applySelectedCloudModelFields(contact)
        contact.put(
            "setup_status",
            if (CloudModelCredentialPolicy.isStoredCredential(providerCredential)) "ready" else "needs_setup"
        )
        return contact
    }

    internal fun clearCloudProviderCredentials(contact: JSONObject): JSONObject {
        contact.remove("cloud_api_key")
        val models = contact.optJSONArray("cloud_models")
        if (models != null) {
            for (index in 0 until models.length()) {
                models.optJSONObject(index)?.remove("api_key")
            }
        }
        contact.put("setup_status", "needs_setup")
        return contact
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

    private fun markCloudProviderConfigurationAvailable(context: Context, contact: JSONObject) {
        val contactId = contact.optString("id").ifBlank { galaxyssiIdOf(contact) }
        val provider = contact.optString("cloud_provider")
        val health = AgentResourceHealthStore(context)
        if (contactId.isNotBlank()) health.markAvailable("target:$contactId")
        if (provider.isNotBlank()) health.markAvailable("domain:cloud:$provider")
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
            .put("type", "galaxyssi_backup")
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
        if (key == KEY_CONTACTS) return contactsSnapshot(context)
        val raw = storage(context).readString(key, "[]")
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun contactsSnapshot(context: Context): JSONArray {
        ensureContactsCache(context)
        return runCatching { JSONArray(contactsCacheRaw) }.getOrDefault(JSONArray())
    }

    private fun ensureContactsCache(context: Context) {
        if (contactsCacheRaw.isNotBlank()) return
        synchronized(contactsCacheLock) {
            if (contactsCacheRaw.isNotBlank()) return
            updateContactsCache(storage(context).readString(KEY_CONTACTS, "[]"))
        }
    }

    private fun updateContactsCache(raw: String) {
        val normalizedRaw = raw.ifBlank { "[]" }
        if (contactsCacheRaw == normalizedRaw && contactsCacheRevision > 0L) return
        val indexed = LinkedHashMap<String, String>()
        val contacts = runCatching { JSONArray(normalizedRaw) }.getOrDefault(JSONArray())
        for (index in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(index) ?: continue
            val serialized = contact.toString()
            galaxyssiIdOf(contact).takeIf { it.isNotBlank() }?.let { indexed[it] = serialized }
            contact.optString("id").takeIf { it.isNotBlank() }?.let { indexed[it] = serialized }
        }
        contactsCacheById = indexed
        contactsCacheRaw = normalizedRaw
        contactsCacheRevision += 1L
    }

    private fun galaxyssiIdOf(json: JSONObject): String =
        json.optString("galaxyssi_id")
            .ifBlank { json.optString("hermes_id") }
            .ifBlank { json.optString("id") }

    private fun putGalaxySSIId(json: JSONObject, id: String): JSONObject {
        if (id.isNotBlank()) json.put("galaxyssi_id", id)
        json.remove("hermes_id")
        return json
    }

    private fun normalizeGalaxySSIIds(context: Context) {
        val profile = readObject(context, KEY_PROFILE)
        var profileChanged = false
        if (profile.optString("galaxyssi_id").isBlank() && profile.optString("hermes_id").isNotBlank()) {
            putGalaxySSIId(profile, profile.optString("hermes_id"))
            profileChanged = true
        } else if (profile.has("hermes_id")) {
            profile.remove("hermes_id")
            profileChanged = true
        }
        if (profileChanged) writeObject(context, KEY_PROFILE, profile)

        val contacts = readArray(context, KEY_CONTACTS)
        if (normalizeGalaxySSIIdsInArray(contacts)) writeArray(context, KEY_CONTACTS, contacts)

        val requests = readArray(context, KEY_FRIEND_REQUESTS)
        if (normalizeGalaxySSIIdsInArray(requests)) writeArray(context, KEY_FRIEND_REQUESTS, requests)
    }

    private fun normalizeGalaxySSIIdsInArray(array: JSONArray): Boolean {
        var changed = false
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val oldId = item.optString("hermes_id")
            if (item.optString("galaxyssi_id").isBlank() && oldId.isNotBlank()) {
                putGalaxySSIId(item, oldId)
                changed = true
            } else if (item.has("hermes_id")) {
                item.remove("hermes_id")
                changed = true
            }
        }
        return changed
    }

    private fun normalizeVerifiedPhoneRelationshipRoutes(context: Context) {
        val contacts = readArray(context, KEY_CONTACTS)
        val requests = readArray(context, KEY_FRIEND_REQUESTS)
        if (normalizePhoneRelationshipRoutes(contacts, requireVerified = true)) {
            writeArray(context, KEY_CONTACTS, contacts)
        }
        if (normalizePhoneRelationshipRoutes(requests, requireVerified = false)) {
            writeArray(context, KEY_FRIEND_REQUESTS, requests)
        }
    }

    private fun normalizePhoneRelationshipRoutes(
        records: JSONArray,
        requireVerified: Boolean
    ): Boolean {
        var changed = false
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val remoteId = galaxyssiIdOf(record)
            if (!remoteId.startsWith("galaxyssi:") ||
                record.optString("type") != "person" ||
                record.optString("desktop_id").isNotBlank() ||
                record.optBoolean("deleted", false) ||
                (requireVerified && record.optString("trust_state") != "verified")
            ) continue
            val routes = GalaxySSICrypto.derivePhoneRelationshipRoutes(
                record.optString("identity_public_key"),
                record.optString("identity_fingerprint")
            ) ?: continue
            if (record.optString("link_secret") == routes.linkSecret &&
                record.optString("client_route_id") == routes.clientRouteId &&
                record.optString("local_identity_fingerprint") == routes.localFingerprint
            ) continue
            record
                .put("link_secret", routes.linkSecret)
                .put("client_route_id", routes.clientRouteId)
                .put("local_identity_fingerprint", routes.localFingerprint)
                .put("relationship_binding", "signal_identity_ecdh_v3")
                .put("relationship_repaired_at", System.currentTimeMillis())
            changed = true
        }
        return changed
    }

    private fun readObject(context: Context, key: String): JSONObject {
        val raw = storage(context).readString(key, "{}")
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun writeArray(context: Context, key: String, value: JSONArray) {
        val raw = value.toString()
        storage(context).writeString(key, raw)
        if (key == KEY_CONTACTS) {
            synchronized(contactsCacheLock) { updateContactsCache(raw) }
        }
    }

    private fun writeObject(context: Context, key: String, value: JSONObject) {
        storage(context).writeString(key, value.toString())
    }

    private fun storage(context: Context): AgentEncryptedPreferences =
        AgentEncryptedPreferences(context.applicationContext, PREFS)

    private fun removeChatHistory(context: Context, contactId: String) {
        val contactName = contactById(context, contactId)
            ?.optString("name")
            .orEmpty()
            .ifBlank { contactId }
        ChatHistoryStore.deleteContact(context, contactId)
        GlobalConversationEventBus.publishContactHistoryCleared(context, contactId, contactName)
    }

    private fun ByteArray.b64(): String =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

    private fun String.b64d(): ByteArray =
        android.util.Base64.decode(this, android.util.Base64.DEFAULT)
}

internal data class AppStoreContactsSnapshot(
    val revision: Long,
    val rawJson: String
)
