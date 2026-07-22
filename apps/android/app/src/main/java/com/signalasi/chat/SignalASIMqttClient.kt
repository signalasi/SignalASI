package com.signalasi.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object SignalASIMqttClient {
    private const val TAG = "SignalASILink"
    private const val SERVER_URI = "ssl://broker.emqx.io:8883"
    private const val MQTT_QOS = 1
    private const val MAX_INLINE_ATTACHMENT_BYTES = 320 * 1024
    private const val PAIRING_CLAIM_MAX_AGE_MILLIS = 9 * 60_000L

    private data class PendingPairingClaim(
        val desktopId: String,
        val topic: String,
        val wirePayload: String,
        val queuedAtMillis: Long
    )

    private val connecting = AtomicBoolean(false)
    private val retryHandler = Handler(Looper.getMainLooper())
    private val retryRunnable = object : Runnable {
        override fun run() {
            if (connected) {
                retryPendingMessages()
                retryHandler.postDelayed(this, 30_000L)
            }
        }
    }
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val deliveryMessageIds = ConcurrentHashMap<Int, String>()
    private val pairingClaimLock = Any()
    private var client: MqttAsyncClient? = null
    private var pendingPairingClaim: PendingPairingClaim? = null
    @Volatile private var connected = false
    @Volatile private var secureReady = false
    @Volatile private var lastConnectorStatusRequestAt = 0L
    @Volatile private var appContext: Context? = null

    interface Listener {
        fun onConnectionChanged(isConnected: Boolean) = Unit
        fun onSecureChannelChanged(isReady: Boolean) = Unit
        fun onMessage(payload: String) = Unit
        fun onPcInfo(ip: String, port: Int) = Unit
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onConnectionChanged(connected)
        listener.onSecureChannelChanged(secureReady)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun isConnected(): Boolean = connected

    fun isSecureReady(): Boolean = secureReady

    fun forgetSecureChannel() {
        val context = appContext
        setSecureReady(context != null && SignalASILinkProtocol.allServerLinks(context).any { it.paired })
    }

    fun publishServerRevocation(context: Context, desktopId: String): Boolean {
        val link = SignalASILinkProtocol.serverLink(context, desktopId) ?: return false
        val mqtt = client ?: return false
        if (!mqtt.isConnected || !link.paired) return false
        val payload = JSONObject()
            .put("type", "client_revoked")
            .put("desktop_id", desktopId)
            .put("reason", "forgotten_by_client")
            .put("time", System.currentTimeMillis())
        val envelope = SignalASILinkProtocol.makeEnvelope(
            payload, SignalASICrypto.localSignalasiId(), desktopId
        )
        val encrypted = SignalASICrypto.encryptPayloadForDesktop(desktopId, envelope) ?: return false
        val messageId = envelope.getString("message_id")
        val wirePayload = encrypted.toString()
        SignalASILinkDeliveryStore.enqueue(context, messageId, link.routes.control, wirePayload)
        SignalASILinkDeliveryStore.markAttempt(context, messageId)
        val token = mqtt.publish(link.routes.control, MqttMessage(wirePayload.toByteArray(Charsets.UTF_8)).apply {
            qos = MQTT_QOS
            isRetained = false
        })
        deliveryMessageIds[token.messageId] = messageId
        return true
    }

    fun publishDesktopToolCall(desktopId: String, payload: JSONObject): Boolean =
        publishDesktopControlPayload(desktopId, payload)

    fun publishDesktopToolCancel(
        desktopId: String,
        callId: String,
        taskId: String,
        conversationId: String
    ): Boolean = publishDesktopControlPayload(
        desktopId,
        JSONObject()
            .put("type", "desktop_tool_call_cancel")
            .put("call_id", callId)
            .put("invocation_id", callId)
            .put("task_id", taskId)
            .put("conversation_id", conversationId)
            .put("time", System.currentTimeMillis())
    )

    private fun publishDesktopControlPayload(desktopId: String, payload: JSONObject): Boolean {
        val context = appContext ?: return false
        val link = SignalASILinkProtocol.serverLink(context, desktopId) ?: return false
        val mqtt = client ?: return false
        if (!mqtt.isConnected || !link.paired || !SignalASICrypto.hasDesktopSession(context, desktopId)) return false
        payload.put("desktop_id", desktopId)
        val envelope = runCatching {
            SignalASILinkProtocol.makeEnvelope(payload, SignalASICrypto.localSignalasiId(), desktopId)
        }.getOrNull() ?: return false
        val encrypted = SignalASICrypto.encryptPayloadForDesktop(desktopId, envelope) ?: return false
        val messageId = envelope.getString("message_id")
        val wirePayload = encrypted.toString()
        SignalASILinkDeliveryStore.enqueue(context, messageId, link.routes.control, wirePayload)
        SignalASILinkDeliveryStore.markAttempt(context, messageId)
        return runCatching {
            val token = mqtt.publish(
                link.routes.control,
                MqttMessage(wirePayload.toByteArray(Charsets.UTF_8)).apply {
                    qos = MQTT_QOS
                    isRetained = false
                }
            )
            deliveryMessageIds[token.messageId] = messageId
            true
        }.getOrDefault(false)
    }

    fun verifyPcIdentityFromQr(contents: String): Boolean {
        val context = appContext ?: return false
        val qr = runCatching { JSONObject(contents) }.getOrNull() ?: return false
        if (!SignalASILinkProtocol.validatePairingQr(qr)) return false
        if (!SignalASICrypto.verifyPcIdentityFromQr(contents)) return false
        SignalASILinkProtocol.ensureServerLink(context, qr)
        return true
    }

    fun connect(context: Context) {
        appContext = context.applicationContext
        SignalASICrypto.initialize(context.applicationContext)
        val current = client
        if (current?.isConnected == true || !connecting.compareAndSet(false, true)) return

        val mqtt = current ?: MqttAsyncClient(
            SERVER_URI,
            stableClientId(),
            MemoryPersistence()
        ).also {
            client = it
            it.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "MQTT connectComplete reconnect=$reconnect")
                    setConnected(true)
                    subscribe()
                    flushPendingPairingClaim()
                    scheduleOutboxRetries()
                    scheduleConnectorStatusRequest()
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT connection lost", cause)
                    setConnected(false)
                    retryHandler.removeCallbacks(retryRunnable)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.payload?.toString(Charsets.UTF_8).orEmpty()
                    if (payload.isBlank()) return
                    runCatching { handleIncoming(topic.orEmpty(), JSONObject(payload)) }
                        .onFailure { Log.e(TAG, "Failed to handle incoming MQTT message", it) }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    val context = appContext ?: return
                    val messageId = token?.messageId?.let { deliveryMessageIds.remove(it) } ?: return
                    SignalASILinkDeliveryStore.markPublished(context, messageId)
                }
            })
        }

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = false
            keepAliveInterval = 30
            connectionTimeout = 10
        }

        mqtt.connect(options, context.applicationContext, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                Log.i(TAG, "MQTT connected")
                connecting.set(false)
                setConnected(true)
                subscribe()
                scheduleOutboxRetries()
                Handler(Looper.getMainLooper()).postDelayed(
                    {
                        requestMissingSignalSessions(context.applicationContext)
                        requestConnectorStatuses(context.applicationContext)
                        retryPendingMessages()
                    },
                    800L
                )
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                Log.e(TAG, "MQTT connect failed", exception)
                connecting.set(false)
                setConnected(false)
            }
        })
    }

    fun reconnect(context: Context) {
        connecting.set(false)
        setSecureReady(false)
        runCatching { client?.disconnectForcibly(0, 0) }
        runCatching { client?.close() }
        client = null
        setConnected(false)
        connect(context)
    }

    fun publishUserMessage(
        content: String,
        contactId: String = "hermes",
        topicOverride: String? = null,
        clientMessageId: Long? = null,
        deliveryTrace: org.json.JSONArray? = null,
        conversationId: String = "",
        turnId: String = ""
    ): Boolean {
        val context = appContext
        val payload = JSONObject()
            .put("type", "text")
            .put("content", content)
            .put("contact_id", contactId)
            .put("time", System.currentTimeMillis())
        clientMessageId?.let { payload.put("client_message_id", it) }
        if (conversationId.isNotBlank()) payload.put("conversation_id", conversationId)
        if (turnId.isNotBlank()) {
            payload.put("turn_id", turnId)
            inlineTurnAttachments(context, turnId).takeIf { it.length() > 0 }
                ?.let { payload.put("attachments", it) }
        }
        deliveryTrace?.let { payload.put("delivery_trace", it) }
        if (context != null) {
            AppStore.contactById(context, contactId)?.let { contact ->
                payload
                    .put("agent_id", contact.optString("agent_id").ifBlank { AppStore.agentIdForContact(context, contactId) })
                    .put("desktop_id", contact.optString("desktop_id"))
                    .put("desktop_name", contact.optString("desktop_name"))
            }
        }
        return publishJson(payload, topicOverride ?: outgoingTopic(contactId), contactId)
    }

    private fun inlineTurnAttachments(context: Context?, turnId: String): JSONArray {
        if (context == null) return JSONArray()
        var remaining = MAX_INLINE_ATTACHMENT_BYTES
        val result = JSONArray()
        AgentTurnAttachmentRegistry.get(turnId).forEach { attachment ->
            val item = attachment.descriptor()
            item.remove("uri")
            if (attachment.isImage) {
                val encoded = AgentImagePipeline.encodeForTransport(context, attachment, remaining)
                if (encoded != null && encoded.bytes.isNotEmpty() && encoded.bytes.size <= remaining) {
                    val transportName = encoded.transportName(attachment.displayName)
                    if (transportName != attachment.displayName) {
                        item.put("original_name", attachment.displayName)
                        item.put("name", transportName)
                    }
                    item.put("mime_type", encoded.mimeType)
                    item.put("transport_size", encoded.bytes.size)
                    item.put("transport_lossless", encoded.lossless)
                    item.put("data_b64", Base64.encodeToString(encoded.bytes, Base64.NO_WRAP))
                    remaining -= encoded.bytes.size
                } else {
                    item.put("inline_status", "metadata_only")
                }
            } else {
                val bytes = if (attachment.sizeBytes in 1..remaining.toLong()) {
                    readBoundedBytes(context, attachment, remaining)
                } else null
                if (bytes != null && bytes.isNotEmpty() && bytes.size <= remaining) {
                    item.put("data_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    remaining -= bytes.size
                } else {
                    item.put("inline_status", "metadata_only")
                }
            }
            result.put(item)
        }
        return result
    }

    private fun readBoundedBytes(
        context: Context,
        attachment: AgentInputAttachment,
        limit: Int
    ): ByteArray? = runCatching {
        context.contentResolver.openInputStream(attachment.uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (output.size() + read > limit) return@use null
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }.getOrNull()

    fun publishAgentTaskCancel(
        taskId: String,
        contactId: String,
        sourceMessageId: Long,
        topicOverride: String? = null
    ): Boolean {
        if (taskId.isBlank()) return false
        val payload = JSONObject()
            .put("type", "agent_task_cancel")
            .put("task_id", taskId)
            .put("contact_id", contactId)
            .put("source_message_id", sourceMessageId)
            .put("time", System.currentTimeMillis())
        appContext?.let { context ->
            AppStore.contactById(context, contactId)?.let { contact ->
                payload
                    .put("agent_id", contact.optString("agent_id").ifBlank { AppStore.agentIdForContact(context, contactId) })
                    .put("desktop_id", contact.optString("desktop_id"))
            }
        }
        return publishJson(payload, topicOverride ?: outgoingTopic(contactId), contactId)
    }

    fun publishAgentConversationDelete(conversationId: String, taskIds: Set<String>): Boolean {
        if (conversationId.isBlank()) return false
        val payload = JSONObject()
            .put("type", "agent_conversation_delete")
            .put("conversation_id", conversationId)
            .put("task_ids", org.json.JSONArray(taskIds.toList()))
            .put("cleanup_scope", "records_and_temporary_files")
            .put("time", System.currentTimeMillis())
        return publishJson(payload, outgoingTopic("hermes"), "hermes")
    }

    fun publishProfileUpdate(contactId: String, topicOverride: String? = null): Boolean {
        val context = appContext ?: return false
        val profile = AppStore.profile(context)
        val topic = topicOverride ?: AppStore.outgoingTopicForContact(context, contactId) ?: return false
        return publishJson(JSONObject()
            .put("type", "profile_update")
            .put("contact_id", contactId)
            .put("sender", SignalASICrypto.localSignalasiId())
            .put("name", profile.optString("name", "Me"))
            .put("signalasi_id", profile.optString("signalasi_id"))
            .put("identity_fingerprint", profile.optString("identity_fingerprint"))
            .put("time", System.currentTimeMillis()), topic, contactId)
    }

    fun publishPairingClaim(pairingQr: JSONObject): Boolean {
        val context = appContext ?: return false
        if (!SignalASILinkProtocol.validatePairingQr(pairingQr)) return false
        val link = SignalASILinkProtocol.ensureServerLink(
            context,
            pairingQr,
            rotateClientRoute = true
        )
        subscribeLink(link)
        val profile = AppStore.profile(context)
        val payload = JSONObject()
            .put("protocol", SignalASILinkProtocol.NAME)
            .put("version", SignalASILinkProtocol.VERSION)
            .put("type", "signalasi_pairing_claim")
            .put("pairing_token", pairingQr.optString("pairing_token"))
            .put("from", SignalASICrypto.localSignalasiId())
            .put("signal_name", SignalASICrypto.localSignalasiId())
            .put("signal_device_id", 1)
            .put("server_route_id", link.routes.serverRouteId)
            .put("client_route_id", link.routes.clientRouteId)
            .put("client_name", profile.optString("name", "Me"))
            .put("platform", "android")
            .put("signalasi_id", profile.optString("signalasi_id"))
            .put("identity_fingerprint", SignalASICrypto.localIdentitySha256())
            .put("identity_public_key", SignalASICrypto.localIdentityPublicKey())
            .put("signal_bundle", SignalASICrypto.localSignalBundleJson())
            .put("time", System.currentTimeMillis())
        val encryptedClaim = runCatching { SignalASILinkProtocol.encryptPairingClaim(payload, pairingQr) }
            .getOrElse {
                Log.e(TAG, "Pairing claim encryption failed", it)
                return false
            }
        synchronized(pairingClaimLock) {
            pendingPairingClaim = PendingPairingClaim(
                desktopId = link.desktopId,
                topic = link.routes.pairing,
                wirePayload = encryptedClaim.toString(),
                queuedAtMillis = System.currentTimeMillis()
            )
        }
        if (client?.isConnected == true) flushPendingPairingClaim() else connect(context)
        return true
    }

    private fun flushPendingPairingClaim() {
        val pending = synchronized(pairingClaimLock) { pendingPairingClaim } ?: return
        if (System.currentTimeMillis() - pending.queuedAtMillis > PAIRING_CLAIM_MAX_AGE_MILLIS) {
            synchronized(pairingClaimLock) {
                if (pendingPairingClaim == pending) pendingPairingClaim = null
            }
            Log.w(TAG, "Discarded expired pending pairing claim")
            return
        }
        val payload = runCatching { JSONObject(pending.wirePayload) }.getOrElse {
            synchronized(pairingClaimLock) {
                if (pendingPairingClaim == pending) pendingPairingClaim = null
            }
            Log.e(TAG, "Discarded invalid pending pairing claim", it)
            return
        }
        if (!publishPublicJson(pending.topic, payload)) return
        synchronized(pairingClaimLock) {
            if (pendingPairingClaim == pending) pendingPairingClaim = null
        }
        Log.i(TAG, "Published pending pairing claim desktop=${pending.desktopId.takeLast(8)}")
    }

    fun publishGroupTextMessage(
        content: String,
        groupId: String,
        groupName: String,
        memberId: String,
        memberTopic: String
    ): Boolean {
        return publishJson(JSONObject()
            .put("type", "text")
            .put("content", content)
            .put("sender", SignalASICrypto.localSignalasiId())
            .put("contact_id", groupId)
            .put("group_id", groupId)
            .put("group_name", groupName)
            .put("delivery_mode", "per_member_signal")
            .put("time", System.currentTimeMillis()), memberTopic, memberId)
    }

    fun publishFileMessage(
        fileId: String,
        name: String,
        size: Long,
        contentType: String,
        caption: String = "",
        contactId: String = "hermes",
        topicOverride: String? = null
    ): Boolean {
        val type = when {
            contentType.startsWith("image/") -> "image"
            contentType.startsWith("audio/") -> "audio"
            else -> "file_notify"
        }
        return publishJson(JSONObject()
            .put("type", type)
            .put("file_id", fileId)
            .put("name", name)
            .put("size", size)
            .put("caption", caption)
            .put("content", caption)
            .put("contact_id", contactId)
            .put("time", System.currentTimeMillis()), topicOverride ?: outgoingTopic(contactId), contactId)
    }

    fun requestSignalBundleForContact(context: Context, contactId: String): Boolean {
        appContext = context.applicationContext
        val contact = AppStore.contactById(context, contactId) ?: return false
        val topic = contact.optString("mqtt_topic")
            .ifBlank { contact.optString("mqtt_inbox_topic") }
        if (topic.isBlank()) return false
        val request = JSONObject()
            .put("version", 1)
            .put("type", "signal_bundle_request")
            .put("from", SignalASICrypto.localSignalasiId())
            .put("to", contactId)
            .put("reply_topic", incomingTopicForContact(context, contactId))
            .put("requested_fingerprint", contact.optString("identity_fingerprint"))
            .put("identity_fingerprint", SignalASICrypto.localIdentitySha256())
            .put("signal_bundle", SignalASICrypto.localSignalBundleJson())
            .put("time", System.currentTimeMillis())
        return publishPublicJson(topic, request)
    }

    private fun publishJson(payload: JSONObject, topic: String?, contactId: String = "hermes"): Boolean {
        val mqtt = client ?: run {
            Log.w(TAG, "Publish rejected: MQTT client is null")
            return false
        }
        if (!mqtt.isConnected) {
            Log.w(TAG, "Publish rejected: MQTT is disconnected")
            return false
        }
        if (topic.isNullOrBlank()) {
            Log.w(TAG, "Publish rejected: target topic is blank")
            return false
        }
        val targetId = if (usesPcConnectorTunnel(contactId)) {
            appContext?.let { AppStore.desktopIdForContact(it, contactId) }.orEmpty()
        } else contactId
        val publishStartedAt = System.currentTimeMillis()
        payload.put("client_sent_at_ms", publishStartedAt)
        val trace = payload.optJSONArray("delivery_trace") ?: JSONArray().also {
            payload.put("delivery_trace", it)
        }
        trace.put(JSONObject()
            .put("stage", "phone_publish_started")
            .put("at", publishStartedAt)
            .put("detail", contactId))
        val applicationEnvelope = SignalASILinkProtocol.makeEnvelope(
            payload,
            SignalASICrypto.localSignalasiId(),
            targetId
        )
        val encrypted = if (usesPcConnectorTunnel(contactId)) {
            val desktopId = appContext?.let { AppStore.desktopIdForContact(it, contactId) }.orEmpty()
            if (desktopId.isNotBlank()) {
                SignalASICrypto.encryptPayloadForDesktop(desktopId, applicationEnvelope)
            } else {
                null
            }
        } else {
            SignalASICrypto.encryptPayloadForContact(contactId, applicationEnvelope)
        } ?: run {
            appContext?.let { requestSignalBundleForContact(it, contactId) }
            Log.w(TAG, "Encrypted publish deferred: secure session refresh requested for $contactId")
            return false
        }
        val context = appContext ?: return false
        val messageId = applicationEnvelope.getString("message_id")
        val wirePayload = encrypted.toString()
        SignalASILinkDeliveryStore.enqueue(context, messageId, topic, wirePayload)
        SignalASILinkDeliveryStore.markAttempt(context, messageId)
        val token = mqtt.publish(topic, MqttMessage(wirePayload.toByteArray(Charsets.UTF_8)).apply {
            qos = MQTT_QOS
            isRetained = false
        })
        deliveryMessageIds[token.messageId] = messageId
        Log.i(TAG, "Published encrypted MQTT message topic=$topic bytes=${encrypted.optString("body").length}")
        return true
    }

    private fun retryPendingMessages() {
        val context = appContext ?: return
        val mqtt = client ?: return
        if (!mqtt.isConnected) return
        SignalASILinkDeliveryStore.pending(context).forEach { pending ->
            if (pending.topic.isBlank() || pending.wirePayload.isBlank()) return@forEach
            runCatching {
                SignalASILinkDeliveryStore.markAttempt(context, pending.messageId)
                val token = mqtt.publish(pending.topic, MqttMessage(pending.wirePayload.toByteArray(Charsets.UTF_8)).apply {
                    qos = MQTT_QOS
                    isRetained = false
                })
                deliveryMessageIds[token.messageId] = pending.messageId
            }.onFailure { Log.w(TAG, "Outbox retry failed message=${pending.messageId}", it) }
        }
    }

    private fun scheduleOutboxRetries() {
        retryHandler.removeCallbacks(retryRunnable)
        retryHandler.postDelayed(retryRunnable, 3_000L)
    }

    private fun outgoingTopic(contactId: String): String? =
        appContext?.let { AppStore.outgoingTopicForContact(it, contactId) }

    private fun incomingTopicForContact(context: Context, contactId: String): String {
        val desktopId = AppStore.desktopIdForContact(context, contactId)
        return SignalASILinkProtocol.serverLink(context, desktopId)?.routes?.down.orEmpty()
    }

    private fun publishPublicJson(topic: String, payload: JSONObject): Boolean {
        val mqtt = client ?: run {
            Log.w(TAG, "Public publish rejected: MQTT client is null")
            return false
        }
        if (!mqtt.isConnected || topic.isBlank()) {
            Log.w(TAG, "Public publish rejected: connected=${mqtt.isConnected} topic=$topic")
            return false
        }
        mqtt.publish(topic, MqttMessage(payload.toString().toByteArray(Charsets.UTF_8)).apply {
            qos = MQTT_QOS
            isRetained = false
        })
        Log.i(TAG, "Published public MQTT control type=${payload.optString("type")} topic=$topic")
        return true
    }

    private fun usesPcConnectorTunnel(contactId: String): Boolean {
        if (contactId == "hermes") return true
        val context = appContext ?: return false
        return AppStore.usesPcConnectorTunnel(context, contactId)
    }

    private fun handleIncoming(topic: String, wire: JSONObject) {
        val context = appContext ?: return
        val link = SignalASILinkProtocol.allServerLinks(context).firstOrNull {
            topic == it.routes.down || topic == it.routes.control
        } ?: run {
            Log.w(TAG, "Rejected message on unknown relationship topic")
            return
        }
        if (wire.optString("type") == "pairing_confirmed") {
            handlePairingConfirmation(link, wire)
            return
        }
        if (!link.paired || wire.optString("scheme") != "signal") {
            Log.w(TAG, "Rejected non-Signal traffic on paired relationship")
            return
        }
        if (wire.optString("from") == SignalASICrypto.localSignalasiId() &&
            wire.optString("to") == link.desktopId
        ) {
            return
        }
        if (wire.optString("from") != link.desktopId || wire.optString("to") != SignalASICrypto.localSignalasiId()) {
            Log.w(TAG, "Rejected Signal envelope with mismatched endpoint identity")
            return
        }
        val decrypted = SignalASICrypto.decryptEnvelope(wire) ?: return
        if (decrypted.optString("source_id") != link.desktopId ||
            decrypted.optString("target_id") != SignalASICrypto.localSignalasiId()
        ) {
            Log.w(TAG, "Rejected application envelope with mismatched endpoint identity")
            return
        }
        val payload = SignalASILinkProtocol.unwrapEnvelope(decrypted) ?: return
        if (!SignalASILinkDeliveryStore.claimIncoming(context, payload.optString("message_id"))) {
            Log.i(TAG, "Ignored duplicate inbound message ${payload.optString("message_id")}")
            return
        }
        if (payload.optString("type") == "delivery_ack") {
            val acknowledgedId = payload.optString("source_message_id").ifBlank { payload.optString("reply_to") }
            SignalASILinkDeliveryStore.acknowledge(context, acknowledgedId)
        } else {
            publishInboundReceipt(link, payload.optString("message_id"))
        }
        if (payload.optString("type") == "capability_manifest") {
            AgentDesktopRemoteNativeTools.updateManifest(context, payload)
        }
        if (AgentDesktopRemoteNativeTools.handleInbound(payload)) return
        if (handleSecureControlMessage(payload)) {
            listeners.forEach { it.onMessage(payload.toString()) }
            return
        }
        payload.optJSONArray("connector_agents")?.let { AppStore.updateConnectorAgentStatuses(context, it) }
        listeners.forEach { it.onMessage(payload.toString()) }
    }

    private fun publishInboundReceipt(link: SignalASILinkProtocol.ServerLink, receivedMessageId: String) {
        if (receivedMessageId.isBlank()) return
        val mqtt = client ?: return
        if (!mqtt.isConnected) return
        val payload = JSONObject()
            .put("type", "delivery_ack")
            .put("source_message_id", receivedMessageId)
            .put("delivery_status", "accepted")
            .put("sender", "system")
            .put("time", System.currentTimeMillis())
        val envelope = SignalASILinkProtocol.makeEnvelope(
            payload,
            SignalASICrypto.localSignalasiId(),
            link.desktopId
        )
        val encrypted = SignalASICrypto.encryptPayloadForDesktop(link.desktopId, envelope) ?: return
        mqtt.publish(link.routes.control, MqttMessage(encrypted.toString().toByteArray(Charsets.UTF_8)).apply {
            qos = MQTT_QOS
            isRetained = false
        })
    }

    private fun handlePairingConfirmation(link: SignalASILinkProtocol.ServerLink, json: JSONObject) {
        val context = appContext ?: return
        if (json.optString("protocol") != SignalASILinkProtocol.NAME ||
            json.optInt("version") != SignalASILinkProtocol.VERSION ||
            json.optString("server_route_id") != link.routes.serverRouteId ||
            json.optString("client_route_id") != link.routes.clientRouteId
        ) return
        val desktopId = json.optString("desktop_id")
        if (desktopId != link.desktopId) return
        val expected = json.optString("desktop_fingerprint")
        json.optJSONObject("signal_bundle")?.let { bundle ->
            val ready = SignalASICrypto.processPcBundleForDesktop(desktopId, bundle, expected, replaceExisting = true)
            if (ready) {
                synchronized(pairingClaimLock) {
                    if (pendingPairingClaim?.desktopId == desktopId) pendingPairingClaim = null
                }
                SignalASILinkProtocol.markPaired(context, desktopId)
                setSecureReady(true)
            }
        }
        json.optJSONArray("connector_agents")?.let { AppStore.updateConnectorAgentStatuses(context, it) }
        listeners.forEach { listener -> listener.onMessage(json.toString()) }
    }

    private fun handleSecureControlMessage(json: JSONObject): Boolean {
        return when (json.optString("type")) {
            "pairing_revoked" -> {
                val context = appContext ?: return true
                Log.w(TAG, "Pairing revoked by desktop connector")
                val desktopId = json.optString("desktop_id")
                if (desktopId.isNotBlank()) {
                    AgentDesktopRemoteNativeTools.removeDesktop(context, desktopId)
                    AppStore.deleteDesktopConnector(context, desktopId, deleteMessages = false)
                    SignalASICrypto.clearDesktopTrust(context, desktopId)
                    SignalASILinkProtocol.removeServer(context, desktopId)
                } else {
                    AppStore.deleteContact(context, "hermes", deleteMessages = false)
                }
                setSecureReady(SignalASILinkProtocol.allServerLinks(context).any { it.paired })
                true
            }
            else -> false
        }
    }

    private fun requestMissingSignalSessions(context: Context) {
        setSecureReady(SignalASILinkProtocol.allServerLinks(context).any { link ->
            link.paired && SignalASICrypto.hasDesktopSession(context, link.desktopId)
        })
    }

    private fun scheduleConnectorStatusRequest() {
        retryHandler.postDelayed({
            appContext?.let(::requestConnectorStatuses)
        }, 500L)
    }

    private fun requestConnectorStatuses(context: Context) {
        val mqtt = client ?: return
        if (!mqtt.isConnected) return
        val now = System.currentTimeMillis()
        if (now - lastConnectorStatusRequestAt < 5_000L) return
        lastConnectorStatusRequestAt = now
        SignalASILinkProtocol.allServerLinks(context)
            .filter { it.paired && SignalASICrypto.hasDesktopSession(context, it.desktopId) }
            .forEach { link ->
                val payload = JSONObject()
                    .put("type", "connector_status_request")
                    .put("contact_id", "system")
                    .put("desktop_id", link.desktopId)
                    .put("time", now)
                val envelope = SignalASILinkProtocol.makeEnvelope(
                    payload,
                    SignalASICrypto.localSignalasiId(),
                    link.desktopId
                )
                val encrypted = SignalASICrypto.encryptPayloadForDesktop(link.desktopId, envelope)
                    ?: return@forEach
                mqtt.publish(
                    link.routes.control,
                    MqttMessage(encrypted.toString().toByteArray(Charsets.UTF_8)).apply {
                        qos = MQTT_QOS
                        isRetained = false
                    }
                )
                Log.i(TAG, "Requested connector status desktop=${link.desktopId.takeLast(8)}")
            }
    }

    private fun subscribe() {
        val mqtt = client ?: return
        if (!mqtt.isConnected) return
        runCatching {
            SignalASILinkProtocol.allServerLinks(appContext ?: return).forEach { subscribeLink(it) }
            Log.i(TAG, "Subscribed to SignalASI Link v1 relationship topics")
        }.onFailure { Log.e(TAG, "MQTT subscribe failed", it) }
    }

    private fun subscribeLink(link: SignalASILinkProtocol.ServerLink) {
        val mqtt = client ?: return
        if (!mqtt.isConnected) return
        mqtt.subscribe(link.routes.down, MQTT_QOS)
        mqtt.subscribe(link.routes.control, MQTT_QOS)
    }

    private fun stableClientId(): String {
        val identity = runCatching { SignalASICrypto.localIdentitySha256().take(16) }.getOrDefault("unknown")
        return "signalasi-android-$identity"
    }

    private fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        listeners.forEach { it.onConnectionChanged(value) }
    }

    private fun setSecureReady(value: Boolean) {
        if (secureReady == value) return
        secureReady = value
        listeners.forEach { it.onSecureChannelChanged(value) }
    }
}
