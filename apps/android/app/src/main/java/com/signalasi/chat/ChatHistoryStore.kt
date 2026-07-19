package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class StoredIncomingMessage(
    val contactId: String,
    val contactName: String,
    val content: String,
    val messageId: Long = 0L,
    val notify: Boolean = true,
    val taskId: String = "",
    val remoteMessageId: String = ""
)

object ChatHistoryStore {
    private const val HISTORY_PREFS = "signalasi_chat_history"
    private const val HISTORY_KEY = "messages"
    private const val HISTORY_UPDATED_KEY = "updated_at"
    private const val MAX_SAVED_MESSAGES_PER_CONTACT = 500
    private const val CONTACT_HERMES = "hermes"
    private const val CONTACT_SYSTEM = "system"

    @Synchronized
    fun appendOutgoing(
        context: Context,
        contactId: String,
        content: String,
        deliveryStatus: String = "",
        deliveryTrace: JSONArray = JSONArray()
    ): Long {
        val cleanContent = content.trim()
        if (contactId.isBlank() || cleanContent.isBlank()) return 0L
        val appContext = context.applicationContext
        AppStore.ensureInitialized(appContext)
        val prefs = appContext.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        val root = runCatching {
            JSONObject(prefs.getString(HISTORY_KEY, null).orEmpty())
        }.getOrElse { JSONObject() }
        val nextId = maxMessageId(root) + 1L
        val trace = JSONArray()
        for (index in 0 until deliveryTrace.length()) {
            deliveryTrace.optJSONObject(index)?.let { trace.put(it) }
        }
        appendTrace(trace, "created", "agent_runtime")
        val array = root.optJSONArray(contactId) ?: JSONArray()
        val timestamp = System.currentTimeMillis()
        array.put(JSONObject()
            .put("id", nextId)
            .put("content", cleanContent)
            .put("isMine", true)
            .put("contactId", contactId)
            .put("isSystem", false)
            .put("timestamp", timestamp)
            .put("deliveryStatus", deliveryStatus)
            .put("deliveryTrace", trace))
        root.put(contactId, trim(array))
        prefs.edit()
            .putString(HISTORY_KEY, root.toString())
            .putLong(HISTORY_UPDATED_KEY, System.currentTimeMillis())
            .apply()
        val contactName = AppStore.contactById(appContext, contactId)
            ?.optString("name")
            .orEmpty()
            .ifBlank { contactId }
        GlobalConversationEventBus.publishChatMessage(
            appContext,
            contactId,
            contactName,
            nextId,
            cleanContent,
            GlobalConversationActor.USER,
            timestamp,
            mapOf("direction" to "outgoing")
        )
        return nextId
    }

    @Synchronized
    fun appendIncoming(context: Context, payload: String): StoredIncomingMessage? {
        val appContext = context.applicationContext
        AppStore.ensureInitialized(appContext)
        val parsed = parseIncoming(appContext, payload)
        if (parsed.content.isBlank()) return null
        if (parsed.contactId == CONTACT_HERMES) {
            AppStore.markHermesVerified(appContext)
        }
        val incomingEnvelope = runCatching { JSONObject(payload) }.getOrNull()
        val deliveryTrace = parseDeliveryTrace(incomingEnvelope)
        appendTrace(deliveryTrace, "received", "background_service")
        appendTrace(deliveryTrace, "decrypted", "SignalASI Link")
        appendTrace(deliveryTrace, "persisted", "background_history")

        val prefs = appContext.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        val root = runCatching {
            JSONObject(prefs.getString(HISTORY_KEY, null).orEmpty())
        }.getOrElse { JSONObject() }
        val nextId = maxMessageId(root) + 1L
        val array = root.optJSONArray(parsed.contactId) ?: JSONArray()
        if (hasIncomingDuplicate(array, parsed)) return null
        val timestamp = System.currentTimeMillis()
        array.put(JSONObject()
            .put("id", nextId)
            .put("content", parsed.content)
            .put("isMine", false)
            .put("contactId", parsed.contactId)
            .put("isSystem", false)
            .put("timestamp", timestamp)
            .put("deliveryStatus", "")
            .put("taskId", parsed.taskId)
            .put("remoteMessageId", parsed.remoteMessageId)
            .put("deliveryTrace", deliveryTrace))
        root.put(parsed.contactId, trim(array))
        prefs.edit()
            .putString(HISTORY_KEY, root.toString())
            .putLong(HISTORY_UPDATED_KEY, System.currentTimeMillis())
            .apply()
        if (parsed.contactId != CONTACT_SYSTEM) {
            GlobalConversationEventBus.publishChatMessage(
                appContext,
                parsed.contactId,
                parsed.contactName,
                nextId,
                parsed.content,
                GlobalConversationActor.ASSISTANT,
                timestamp,
                mapOf(
                    "direction" to "incoming",
                    "task_id" to parsed.taskId,
                    "remote_message_id" to parsed.remoteMessageId
                )
            )
        }
        return parsed.copy(messageId = nextId)
    }

    @Synchronized
    fun markNotified(context: Context, contactId: String, messageId: Long) {
        markMessageTrace(
            context,
            contactId,
            messageId,
            "notified",
            "system_notification",
            context.getString(R.string.delivery_status_notified)
        )
    }

    @Synchronized
    fun markOutgoingDelivery(context: Context, contactId: String, messageId: Long, stage: String, detail: String, status: String) {
        markMessageTrace(context, contactId, messageId, stage, detail, status)
    }

    @Synchronized
    fun applyAgentTaskEvent(context: Context, payload: JSONObject): Boolean {
        if (payload.optString("type") != "agent_task_event") return false
        val contactId = payload.optString("contact_id")
        val messageId = payload.optString("source_message_id").toLongOrNull()
            ?: payload.optLong("source_message_id", 0L).takeIf { it > 0L }
            ?: return true
        if (contactId.isBlank()) return true
        val status = payload.optString("task_status")
        val statusSeq = payload.optLong("status_seq", 0L)
        val baseLabel = when (status) {
            "accepted" -> context.getString(R.string.agent_task_status_accepted)
            "queued" -> context.getString(R.string.agent_task_status_queued)
            "running" -> context.getString(R.string.agent_task_status_running)
            "waiting_input" -> context.getString(R.string.agent_task_status_waiting_input)
            "completed" -> context.getString(R.string.agent_task_status_completed)
            "failed" -> context.getString(R.string.agent_task_status_failed)
            "cancelled" -> context.getString(R.string.agent_task_status_cancelled)
            "timed_out" -> context.getString(R.string.agent_task_status_timed_out)
            else -> status
        }
        val elapsedSeconds = payload.optLong("elapsed_ms", 0L) / 1000L
        val label = if (status == "running" && elapsedSeconds > 0L) {
            context.getString(R.string.agent_task_status_running_elapsed, elapsedSeconds)
        } else baseLabel
        val trace = parseDeliveryTrace(payload)
        if (trace.length() == 0) {
            appendTrace(trace, "agent_$status", payload.optString("agent_id"))
        }
        mergeMessageTaskState(context, contactId, messageId, payload.optString("task_id"), status, statusSeq, label, trace)
        GlobalConversationEventBus.publishTaskStatus(
            context,
            contactId,
            payload.optString("task_id"),
            messageId,
            status,
            statusSeq,
            label
        )
        return true
    }

    private fun mergeMessageTaskState(
        context: Context,
        contactId: String,
        messageId: Long,
        taskId: String,
        taskStatus: String,
        taskStatusSeq: Long,
        statusLabel: String,
        incomingTrace: JSONArray
    ) {
        val prefs = context.applicationContext.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        val root = runCatching { JSONObject(prefs.getString(HISTORY_KEY, null).orEmpty()) }.getOrElse { JSONObject() }
        val messages = root.optJSONArray(contactId) ?: return
        for (index in 0 until messages.length()) {
            val item = messages.optJSONObject(index) ?: continue
            if (item.optLong("id", -1L) != messageId) continue
            if (taskStatusSeq > 0L && taskStatusSeq < item.optLong("taskStatusSeq", 0L)) return
            val trace = item.optJSONArray("deliveryTrace") ?: JSONArray()
            for (traceIndex in 0 until incomingTrace.length()) {
                val event = incomingTrace.optJSONObject(traceIndex) ?: continue
                if (!hasTraceStage(trace, event.optString("stage"))) trace.put(event)
            }
            item.put("deliveryTrace", trace)
                .put("deliveryStatus", statusLabel)
                .put("taskId", taskId)
                .put("taskStatus", taskStatus)
                .put("taskStatusSeq", taskStatusSeq)
            break
        }
        root.put(contactId, messages)
        prefs.edit().putString(HISTORY_KEY, root.toString())
            .putLong(HISTORY_UPDATED_KEY, System.currentTimeMillis()).apply()
    }

    private fun markMessageTrace(context: Context, contactId: String, messageId: Long, stage: String, detail: String, status: String) {
        if (contactId.isBlank() || messageId <= 0L) return
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        val root = runCatching {
            JSONObject(prefs.getString(HISTORY_KEY, null).orEmpty())
        }.getOrElse { JSONObject() }
        val array = root.optJSONArray(contactId) ?: return
        var changed = false
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optLong("id", -1L) != messageId) continue
            val trace = item.optJSONArray("deliveryTrace") ?: JSONArray()
            if (!hasTraceStage(trace, stage)) {
                appendTrace(trace, stage, detail)
                item.put("deliveryTrace", trace)
                changed = true
            }
            if (item.optString("deliveryStatus") != status) {
                item.put("deliveryStatus", status)
                changed = true
            }
            break
        }
        if (!changed) return
        root.put(contactId, array)
        prefs.edit()
            .putString(HISTORY_KEY, root.toString())
            .putLong(HISTORY_UPDATED_KEY, System.currentTimeMillis())
            .apply()
    }

    private fun parseIncoming(context: Context, payload: String): StoredIncomingMessage {
        val json = runCatching { JSONObject(payload) }.getOrNull()
        return when (json?.optString("type").orEmpty()) {
            "delivery_ack", "agent_task_event" -> StoredIncomingMessage(
                CONTACT_SYSTEM,
                context.getString(R.string.system_contact_name),
                "",
                notify = false
            )
            "capability_manifest" -> {
                json?.optJSONArray("connector_agents")?.let { AppStore.updateConnectorAgentStatuses(context, it) }
                StoredIncomingMessage(
                    CONTACT_SYSTEM,
                    context.getString(R.string.system_contact_name),
                    "",
                    notify = false
                )
            }
            "pairing_revoked" -> {
                AppStore.deleteContact(context, CONTACT_HERMES, deleteMessages = false)
                StoredIncomingMessage(
                    CONTACT_SYSTEM,
                    context.getString(R.string.system_contact_name),
                    json?.optString("content")
                        ?.ifBlank { context.getString(R.string.system_pairing_revoked_default) }
                        ?: context.getString(R.string.system_pairing_revoked_default)
                )
            }
            "pairing_confirmed", "connector_status" -> {
                json?.optJSONArray("connector_agents")?.let { AppStore.updateConnectorAgentStatuses(context, it) }
                StoredIncomingMessage(
                    CONTACT_SYSTEM,
                    context.getString(R.string.system_contact_name),
                    json?.optString("content")?.ifBlank { context.getString(R.string.system_connector_status_updated) }
                        ?: context.getString(R.string.system_connector_status_updated)
                )
            }
            "profile_update" -> {
                val profile = json ?: JSONObject()
                val senderId = profile.optString("sender")
                    .ifBlank { profile.optString("signalasi_id") }
                    .ifBlank { profile.optString("hermes_id") }
                val newName = profile.optString("name")
                if (senderId.isNotBlank() && newName.isNotBlank()) {
                    AppStore.updateContactName(context, senderId, newName)
                }
                val label = newName.ifBlank { senderId.ifBlank { context.getString(R.string.fallback_contact_name) } }
                StoredIncomingMessage(
                    CONTACT_SYSTEM,
                    context.getString(R.string.system_contact_name),
                    context.getString(R.string.system_profile_updated, label)
                )
            }
            else -> {
                val content = json?.optString("content", payload)?.takeIf { it.isNotBlank() } ?: payload
                val sender = json?.optString("sender", CONTACT_HERMES) ?: CONTACT_HERMES
                val contactId = when {
                    sender == "system" -> CONTACT_SYSTEM
                    else -> json?.optString("contact_id", CONTACT_HERMES)?.takeIf { it.isNotBlank() } ?: CONTACT_HERMES
                }
                StoredIncomingMessage(
                    contactId,
                    contactName(context, contactId),
                    content,
                    taskId = json?.optString("task_id").orEmpty(),
                    remoteMessageId = json?.optString("message_id").orEmpty()
                )
            }
        }
    }

    private fun hasIncomingDuplicate(array: JSONArray, incoming: StoredIncomingMessage): Boolean {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optBoolean("isMine")) continue
            if (incoming.remoteMessageId.isNotBlank() &&
                item.optString("remoteMessageId") == incoming.remoteMessageId
            ) return true
            if (incoming.taskId.isNotBlank() &&
                item.optString("taskId") == incoming.taskId &&
                item.optString("content") == incoming.content
            ) return true
        }
        return false
    }

    private fun contactName(context: Context, contactId: String): String {
        if (contactId == CONTACT_SYSTEM) return context.getString(R.string.system_contact_name)
        return AppStore.contactById(context, contactId)
            ?.optString("name")
            ?.takeIf { it.isNotBlank() }
            ?: when (contactId) {
                CONTACT_HERMES -> "Hermes Agent"
                "codex" -> "Codex Agent"
                "claude" -> "Claude Code"
                "local-llm" -> "Local LLM"
                "cloud-model" -> "Cloud Model"
                "custom-agent" -> "Custom Agent"
                else -> contactId
            }
    }

    private fun maxMessageId(root: JSONObject): Long {
        var max = 0L
        val keys = root.keys()
        while (keys.hasNext()) {
            val array = root.optJSONArray(keys.next()) ?: continue
            for (i in 0 until array.length()) {
                max = maxOf(max, array.optJSONObject(i)?.optLong("id", 0L) ?: 0L)
            }
        }
        return max
    }

    private fun parseDeliveryTrace(json: JSONObject?): JSONArray {
        val source = json?.optJSONArray("delivery_trace") ?: json?.optJSONArray("deliveryTrace")
        val trace = JSONArray()
        if (source != null) {
            for (i in 0 until source.length()) {
                source.optJSONObject(i)?.let { trace.put(it) }
            }
        }
        return trace
    }

    private fun appendTrace(trace: JSONArray, stage: String, detail: String = "") {
        trace.put(JSONObject()
            .put("stage", stage)
            .put("at", System.currentTimeMillis())
            .put("detail", detail))
    }

    private fun hasTraceStage(trace: JSONArray, stage: String): Boolean {
        for (i in 0 until trace.length()) {
            if (trace.optJSONObject(i)?.optString("stage") == stage) return true
        }
        return false
    }

    private fun trim(array: JSONArray): JSONArray {
        if (array.length() <= MAX_SAVED_MESSAGES_PER_CONTACT) return array
        val trimmed = JSONArray()
        val start = array.length() - MAX_SAVED_MESSAGES_PER_CONTACT
        for (i in start until array.length()) {
            trimmed.put(array.optJSONObject(i))
        }
        return trimmed
    }
}
