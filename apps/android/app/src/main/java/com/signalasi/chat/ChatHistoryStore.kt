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
    private const val CONTACT_HERMES = "hermes"
    private const val CONTACT_SYSTEM = "system"

    @Volatile
    private var databaseInstance: ChatHistoryDatabase? = null

    @Synchronized
    fun reserveMessageId(context: Context): Long = database(context).reserveMessageId()

    @Synchronized
    fun readAll(context: Context): JSONObject = database(context).readAll()

    @Synchronized
    fun readContact(context: Context, contactId: String): JSONArray =
        database(context).readContact(contactId)

    @Synchronized
    internal fun page(
        context: Context,
        contactId: String,
        beforeSequenceExclusive: Long? = null,
        pageSize: Int = 100
    ): ChatHistoryPage = database(context).page(contactId, beforeSequenceExclusive, pageSize)

    @Synchronized
    internal fun contactSummaries(context: Context): List<ChatHistoryContactSummary> =
        database(context).readContactSummaries()

    @Synchronized
    fun markContactRead(context: Context, contactId: String, readAtMillis: Long): Int =
        database(context).markContactRead(contactId, readAtMillis)

    @Synchronized
    fun updatedVersion(context: Context): Long = database(context).updatedVersion()

    @Synchronized
    fun mergeSnapshot(context: Context, root: JSONObject): Boolean =
        database(context).mergeSnapshot(root)

    @Synchronized
    fun upsertAll(context: Context, messages: Collection<JSONObject>): Boolean =
        database(context).upsertAll(messages)

    @Synchronized
    fun replaceAll(context: Context, root: JSONObject) {
        database(context).replaceAll(root)
    }

    @Synchronized
    fun deleteMessage(context: Context, messageId: Long): Boolean =
        database(context).deleteMessage(messageId)

    @Synchronized
    fun deleteContact(
        context: Context,
        contactId: String,
        pendingMessageIds: Collection<Long> = emptyList()
    ): Int = database(context).deleteContact(contactId, pendingMessageIds)

    @Synchronized
    fun clear(context: Context) {
        database(context).clear()
    }

    @Synchronized
    fun close() {
        databaseInstance?.close()
        databaseInstance = null
    }

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
        val nextId = database(appContext).reserveMessageId()
        val trace = copyArray(deliveryTrace)
        appendTrace(trace, "created", "agent_runtime")
        val timestamp = System.currentTimeMillis()
        val message = JSONObject()
            .put("id", nextId)
            .put("content", cleanContent)
            .put("isMine", true)
            .put("contactId", contactId)
            .put("isSystem", false)
            .put("timestamp", timestamp)
            .put("deliveryStatus", deliveryStatus)
            .put("taskId", "")
            .put("taskStatus", "")
            .put("taskStatusSeq", 0L)
            .put("remoteMessageId", "")
            .put("deliveryTrace", trace)
        check(database(appContext).upsert(message)) { "Outgoing chat message was not persisted" }
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
    fun inspectIncoming(context: Context, payload: String): StoredIncomingMessage? {
        val appContext = context.applicationContext
        AppStore.ensureInitialized(appContext)
        return parseIncoming(appContext, payload).takeIf { it.content.isNotBlank() }
    }

    @Synchronized
    fun appendIncoming(context: Context, payload: String): StoredIncomingMessage? {
        val appContext = context.applicationContext
        AppStore.ensureInitialized(appContext)
        val parsed = parseIncoming(appContext, payload)
        if (parsed.content.isBlank()) return null
        if (parsed.contactId == CONTACT_HERMES) AppStore.markHermesVerified(appContext)
        val incomingEnvelope = runCatching { JSONObject(payload) }.getOrNull()
        val deliveryTrace = parseDeliveryTrace(incomingEnvelope)
        appendTrace(deliveryTrace, "received", "background_service")
        appendTrace(deliveryTrace, "decrypted", "SignalASI Link")
        appendTrace(deliveryTrace, "persisted", "background_history")

        val history = database(appContext)
        if (
            history.hasIncomingDuplicate(
                parsed.contactId,
                parsed.remoteMessageId,
                parsed.taskId,
                parsed.content
            )
        ) return null
        val nextId = history.reserveMessageId()
        val timestamp = System.currentTimeMillis()
        val message = JSONObject()
            .put("id", nextId)
            .put("content", parsed.content)
            .put("isMine", false)
            .put("contactId", parsed.contactId)
            .put("isSystem", false)
            .put("timestamp", timestamp)
            .put("deliveryStatus", "")
            .put("taskId", parsed.taskId)
            .put("taskStatus", "")
            .put("taskStatusSeq", 0L)
            .put("remoteMessageId", parsed.remoteMessageId)
            .put("deliveryTrace", deliveryTrace)
        if (!history.upsert(message)) return null
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
    fun markOutgoingDelivery(
        context: Context,
        contactId: String,
        messageId: Long,
        stage: String,
        detail: String,
        status: String
    ) {
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
            "recovering" -> context.getString(R.string.agent_task_status_recovering)
            "running" -> context.getString(R.string.agent_task_status_running)
            "waiting_input" -> context.getString(R.string.agent_task_status_waiting_input)
            "completed" -> context.getString(R.string.agent_task_status_completed)
            "failed" -> context.getString(R.string.agent_task_status_failed)
            "cancelled" -> context.getString(R.string.agent_task_status_cancelled)
            "timed_out" -> context.getString(R.string.agent_task_status_timed_out)
            else -> status
        }
        val elapsedSeconds = payload.optLong("elapsed_ms", 0L) / 1_000L
        val label = if (status == "running" && elapsedSeconds > 0L) {
            context.getString(R.string.agent_task_status_running_elapsed, elapsedSeconds)
        } else baseLabel
        val incomingTrace = parseDeliveryTrace(payload)
        if (incomingTrace.length() == 0) {
            appendTrace(incomingTrace, "agent_$status", payload.optString("agent_id"))
        }
        mergeMessageTaskState(
            context,
            contactId,
            messageId,
            payload.optString("task_id"),
            status,
            statusSeq,
            label,
            incomingTrace
        )
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
        val history = database(context.applicationContext)
        val message = history.findMessage(messageId) ?: return
        if (message.optString("contactId") != contactId) return
        if (taskStatusSeq > 0L && taskStatusSeq < message.optLong("taskStatusSeq", 0L)) return
        val trace = message.optJSONArray("deliveryTrace") ?: JSONArray()
        for (traceIndex in 0 until incomingTrace.length()) {
            val event = incomingTrace.optJSONObject(traceIndex) ?: continue
            if (!hasTraceStage(trace, event.optString("stage"))) trace.put(JSONObject(event.toString()))
        }
        message.put("deliveryTrace", trace)
            .put("deliveryStatus", statusLabel)
            .put("taskId", taskId)
            .put("taskStatus", taskStatus)
            .put("taskStatusSeq", taskStatusSeq)
        history.upsert(message)
    }

    private fun markMessageTrace(
        context: Context,
        contactId: String,
        messageId: Long,
        stage: String,
        detail: String,
        status: String
    ) {
        if (contactId.isBlank() || messageId <= 0L) return
        val history = database(context.applicationContext)
        val message = history.findMessage(messageId) ?: return
        if (message.optString("contactId") != contactId) return
        val trace = message.optJSONArray("deliveryTrace") ?: JSONArray()
        var changed = false
        if (!hasTraceStage(trace, stage)) {
            appendTrace(trace, stage, detail)
            message.put("deliveryTrace", trace)
            changed = true
        }
        if (message.optString("deliveryStatus") != status) {
            message.put("deliveryStatus", status)
            changed = true
        }
        if (changed) history.upsert(message)
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
                    else -> json?.optString("contact_id", CONTACT_HERMES)?.takeIf { it.isNotBlank() }
                        ?: CONTACT_HERMES
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

    private fun parseDeliveryTrace(json: JSONObject?): JSONArray {
        val source = json?.optJSONArray("delivery_trace") ?: json?.optJSONArray("deliveryTrace")
        return copyArray(source ?: JSONArray())
    }

    private fun copyArray(source: JSONArray): JSONArray {
        val copy = JSONArray()
        for (index in 0 until source.length()) {
            source.optJSONObject(index)?.let { copy.put(JSONObject(it.toString())) }
        }
        return copy
    }

    private fun appendTrace(trace: JSONArray, stage: String, detail: String = "") {
        trace.put(
            JSONObject()
                .put("stage", stage)
                .put("at", System.currentTimeMillis())
                .put("detail", detail)
        )
    }

    private fun hasTraceStage(trace: JSONArray, stage: String): Boolean {
        for (index in 0 until trace.length()) {
            if (trace.optJSONObject(index)?.optString("stage") == stage) return true
        }
        return false
    }

    private fun database(context: Context): ChatHistoryDatabase {
        databaseInstance?.let { return it }
        return synchronized(this) {
            databaseInstance ?: ChatHistoryDatabase(context.applicationContext).also {
                databaseInstance = it
            }
        }
    }
}
