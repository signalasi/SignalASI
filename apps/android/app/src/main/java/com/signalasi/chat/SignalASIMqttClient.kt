package com.signalasi.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

object SignalASIMqttClient {
    private const val TAG = "HermesMqtt"
    private const val SERVER_URI = "tcp://broker.emqx.io:1883"
    private const val DEVICE_ID = "android"
    private const val SEND_TOPIC = "signalasichat/$DEVICE_ID/send"
    private const val RECV_TOPIC = "signalasichat/$DEVICE_ID/recv"
    private const val PC_TOPIC = "signalasichat/$DEVICE_ID/pc"
    private const val MQTT_QOS = 1

    private val connecting = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<Listener>()
    private var client: MqttAsyncClient? = null
    @Volatile private var connected = false
    @Volatile private var secureReady = false
    @Volatile private var lastPcSignalBundle: JSONObject? = null
    @Volatile private var localInboxTopic: String = RECV_TOPIC
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
        setSecureReady(false)
    }

    fun verifyPcIdentityFromQr(contents: String): Boolean {
        val verified = SignalASICrypto.verifyPcIdentityFromQr(contents)
        if (verified) {
            lastPcSignalBundle?.let { bundle ->
                setSecureReady(SignalASICrypto.processPcBundle(bundle))
            }
        }
        return verified
    }

    fun connect(context: Context) {
        appContext = context.applicationContext
        SignalASICrypto.initialize(context.applicationContext)
        localInboxTopic = AppStore.localInboxTopic(context.applicationContext)
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
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT connection lost", cause)
                    setConnected(false)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.payload?.toString(Charsets.UTF_8).orEmpty()
                    if (payload.isBlank()) return
                    if (topic == PC_TOPIC) {
                        runCatching {
                            val json = JSONObject(payload)
                            val ip = json.optString("ip")
                            val port = json.optInt("port", 18765)
                            json.optJSONObject("signal")?.let {
                                lastPcSignalBundle = JSONObject(it.toString())
                                setSecureReady(SignalASICrypto.processPcBundle(it))
                            } ?: Log.w(TAG, "PC info received without Signal bundle")
                            if (ip.isNotBlank()) listeners.forEach { listener -> listener.onPcInfo(ip, port) }
                        }.onFailure { Log.e(TAG, "Failed to handle PC info", it) }
                    } else {
                        runCatching {
                            val json = JSONObject(payload)
                            if (handlePublicControlMessage(json)) return
                            SignalASICrypto.decryptEnvelope(json)?.let { decrypted ->
                                if (handleSecureControlMessage(decrypted)) {
                                    listeners.forEach { listener -> listener.onMessage(decrypted.toString()) }
                                    return@let
                                }
                                listeners.forEach { listener -> listener.onMessage(decrypted.toString()) }
                            }
                        }.onFailure { Log.e(TAG, "Failed to handle incoming MQTT message", it) }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
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
                Handler(Looper.getMainLooper()).postDelayed(
                    { requestMissingSignalSessions(context.applicationContext) },
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
        deliveryTrace: org.json.JSONArray? = null
    ): Boolean {
        val context = appContext
        val payload = JSONObject()
            .put("type", "text")
            .put("content", content)
            .put("contact_id", contactId)
            .put("time", System.currentTimeMillis())
        clientMessageId?.let { payload.put("client_message_id", it) }
        deliveryTrace?.let { payload.put("delivery_trace", it) }
        if (context != null) {
            AppStore.contactById(context, contactId)?.let { contact ->
                payload
                    .put("agent_id", contact.optString("agent_id").ifBlank { AppStore.agentIdForContact(context, contactId) })
                    .put("desktop_id", contact.optString("desktop_id"))
                    .put("desktop_name", contact.optString("desktop_name"))
            }
        }
        return publishJson(payload, topicOverride ?: SEND_TOPIC, contactId)
    }

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
        return publishJson(payload, topicOverride ?: SEND_TOPIC, contactId)
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
        val profile = AppStore.profile(context)
        val payload = JSONObject()
            .put("version", 1)
            .put("type", "signalasi_pairing_claim")
            .put("pairing_token", pairingQr.optString("pairing_token"))
            .put("from", SignalASICrypto.localSignalasiId())
            .put("name", profile.optString("name", "Me"))
            .put("signalasi_id", profile.optString("signalasi_id"))
            .put("identity_fingerprint", SignalASICrypto.localIdentitySha256())
            .put("identity_public_key", SignalASICrypto.localIdentityPublicKey())
            .put("signal_bundle", SignalASICrypto.localSignalBundleJson())
            .put("time", System.currentTimeMillis())
        return publishPublicJson(SEND_TOPIC, payload)
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
            .put("time", System.currentTimeMillis()), topicOverride ?: SEND_TOPIC, contactId)
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
            .put("reply_topic", AppStore.localInboxTopic(context))
            .put("requested_fingerprint", contact.optString("identity_fingerprint"))
            .put("identity_fingerprint", SignalASICrypto.localIdentitySha256())
            .put("signal_bundle", SignalASICrypto.localSignalBundleJson())
            .put("time", System.currentTimeMillis())
        return publishPublicJson(topic, request)
    }

    private fun publishJson(payload: JSONObject, topic: String = SEND_TOPIC, contactId: String = "hermes"): Boolean {
        val mqtt = client ?: run {
            Log.w(TAG, "Publish rejected: MQTT client is null")
            return false
        }
        if (!mqtt.isConnected) {
            Log.w(TAG, "Publish rejected: MQTT is disconnected")
            return false
        }
        if (topic.isBlank()) {
            Log.w(TAG, "Publish rejected: target topic is blank")
            return false
        }
        val encrypted = if (usesPcConnectorTunnel(contactId)) {
            val desktopId = appContext?.let { AppStore.desktopIdForContact(it, contactId) }.orEmpty()
            if (desktopId.isNotBlank()) {
                SignalASICrypto.encryptPayloadForDesktop(desktopId, payload)
            } else {
                SignalASICrypto.encryptPayload(payload)
            }
        } else {
            SignalASICrypto.encryptPayloadForContact(contactId, payload)
        } ?: run {
            appContext?.let { requestSignalBundleForContact(it, contactId) }
            Log.w(TAG, "Encrypted publish deferred: secure session refresh requested for $contactId")
            return false
        }
        mqtt.publish(topic, MqttMessage(encrypted.toString().toByteArray(Charsets.UTF_8)).apply {
            qos = MQTT_QOS
            isRetained = false
        })
        Log.i(TAG, "Published encrypted MQTT message topic=$topic bytes=${encrypted.optString("body").length}")
        return true
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

    private fun handlePublicControlMessage(json: JSONObject): Boolean {
        return when (json.optString("type")) {
            "pairing_confirmed", "connector_status" -> {
                handlePublicDesktopControl(json)
                true
            }
            "signal_bundle_request" -> {
                handleSignalBundleRequest(json)
                true
            }
            "signal_bundle_response" -> {
                handleSignalBundleResponse(json)
                true
            }
            else -> false
        }
    }

    private fun handlePublicDesktopControl(json: JSONObject) {
        val context = appContext ?: return
        val desktopId = json.optString("desktop_id")
        val expected = json.optString("desktop_fingerprint")
        json.optJSONObject("signal_bundle")?.let { bundle ->
            val ready = SignalASICrypto.processPcBundleForDesktop(desktopId, bundle, expected)
            if (ready) setSecureReady(true)
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
                    AppStore.deleteDesktopConnector(context, desktopId, deleteMessages = false)
                } else {
                    AppStore.deleteContact(context, "hermes", deleteMessages = false)
                }
                setSecureReady(false)
                true
            }
            else -> false
        }
    }

    private fun handleSignalBundleRequest(json: JSONObject) {
        val context = appContext ?: return
        val to = json.optString("to")
        val localId = SignalASICrypto.localSignalasiId()
        if (to.isNotBlank() && to != localId) return
        val replyTopic = json.optString("reply_topic")
        if (replyTopic.isBlank()) return
        val response = JSONObject()
            .put("version", 1)
            .put("type", "signal_bundle_response")
            .put("from", localId)
            .put("to", json.optString("from"))
            .put("identity_fingerprint", SignalASICrypto.localIdentitySha256())
            .put("signal_bundle", SignalASICrypto.localSignalBundleJson())
            .put("time", System.currentTimeMillis())
        publishPublicJson(replyTopic, response)
    }

    private fun handleSignalBundleResponse(json: JSONObject) {
        val context = appContext ?: return
        val to = json.optString("to")
        val localId = SignalASICrypto.localSignalasiId()
        if (to.isNotBlank() && to != localId) return
        val applied = AppStore.applySignalBundleResponse(context, json)
        Log.i(TAG, "Signal bundle response applied=$applied from=${json.optString("from")}")
    }

    private fun requestMissingSignalSessions(context: Context) {
        val contacts = AppStore.contacts(context)
        val requestedDesktopIds = mutableSetOf<String>()
        for (index in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(index) ?: continue
            if (contact.optBoolean("deleted", false)) continue
            val contactId = contact.optString("id").ifBlank { contact.optString("signalasi_id") }
            if (contactId.isBlank() || AppStore.outgoingTopicForContact(context, contactId) == null) continue
            val sessionReady = if (AppStore.usesPcConnectorTunnel(context, contactId)) {
                val desktopId = AppStore.desktopIdForContact(context, contactId)
                if (desktopId.isBlank() || !requestedDesktopIds.add(desktopId)) continue
                false
            } else {
                SignalASICrypto.hasPeerSession(context, contactId)
            }
            if (!sessionReady) requestSignalBundleForContact(context, contactId)
        }
    }

    private fun subscribe() {
        val mqtt = client ?: return
        if (!mqtt.isConnected) return
        runCatching {
            mqtt.subscribe(RECV_TOPIC, MQTT_QOS)
            if (localInboxTopic.isNotBlank() && localInboxTopic != RECV_TOPIC) {
                mqtt.subscribe(localInboxTopic, MQTT_QOS)
            }
            mqtt.subscribe(PC_TOPIC, MQTT_QOS)
            Log.i(TAG, "Subscribed to encrypted topics with persistent QoS$MQTT_QOS session")
        }.onFailure { Log.e(TAG, "MQTT subscribe failed", it) }
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
