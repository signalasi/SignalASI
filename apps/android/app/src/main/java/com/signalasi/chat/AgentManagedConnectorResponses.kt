package com.signalasi.chat

import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet
import org.json.JSONArray
import org.json.JSONObject

enum class AgentManagedResponseState { PENDING, COMPLETED, APPLIED }

data class AgentManagedResponseRecord(
    val ownerRunId: String,
    val supervisorRunId: String,
    val agentId: String,
    val deliveryMode: AgentDeliveryMode,
    val sourceMessageId: Long,
    val contactId: String,
    val state: AgentManagedResponseState = AgentManagedResponseState.PENDING,
    val response: AgentConnectorResponse? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val completedAtMillis: Long = 0L
)

interface AgentManagedResponseLedger {
    fun register(record: AgentManagedResponseRecord)
    fun complete(response: AgentConnectorResponse): AgentManagedResponseRecord?
    fun acknowledge(response: AgentConnectorResponse): AgentManagedResponseRecord?
    fun pendingForSupervisor(supervisorRunId: String): List<AgentManagedResponseRecord>
    fun completedUnapplied(): List<AgentManagedResponseRecord>
    fun markApplied(ownerRunId: String)
    fun removeOwner(ownerRunId: String)
    fun clear()
}

class InMemoryAgentManagedResponseLedger : AgentManagedResponseLedger {
    private val records = linkedMapOf<String, AgentManagedResponseRecord>()

    @Synchronized
    override fun register(record: AgentManagedResponseRecord) {
        require(record.ownerRunId.isNotBlank() && record.sourceMessageId > 0L)
        records[record.ownerRunId] = record
    }

    @Synchronized
    override fun complete(response: AgentConnectorResponse): AgentManagedResponseRecord? {
        val entry = records.entries.firstOrNull { (_, record) -> record.correlates(response) } ?: return null
        if (entry.value.state != AgentManagedResponseState.PENDING) return entry.value
        val completed = entry.value.copy(
            state = AgentManagedResponseState.COMPLETED,
            response = response,
            completedAtMillis = response.receivedAtMillis
        )
        records[entry.key] = completed
        AgentLateManagedResponseBus.publish(completed)
        return completed
    }

    @Synchronized
    override fun acknowledge(response: AgentConnectorResponse): AgentManagedResponseRecord? {
        val entry = records.entries.firstOrNull { (_, record) -> record.correlates(response) } ?: return null
        val acknowledged = entry.value.copy(
            state = AgentManagedResponseState.APPLIED,
            response = response,
            completedAtMillis = response.receivedAtMillis
        )
        records[entry.key] = acknowledged
        return acknowledged
    }

    @Synchronized
    override fun pendingForSupervisor(supervisorRunId: String): List<AgentManagedResponseRecord> = records.values
        .filter { it.supervisorRunId == supervisorRunId && it.state == AgentManagedResponseState.PENDING }
        .sortedBy(AgentManagedResponseRecord::createdAtMillis)

    @Synchronized
    override fun completedUnapplied(): List<AgentManagedResponseRecord> = records.values
        .filter { it.state == AgentManagedResponseState.COMPLETED && it.response != null }
        .sortedBy(AgentManagedResponseRecord::completedAtMillis)

    @Synchronized
    override fun markApplied(ownerRunId: String) {
        records[ownerRunId]?.let { records[ownerRunId] = it.copy(state = AgentManagedResponseState.APPLIED) }
    }

    @Synchronized
    override fun removeOwner(ownerRunId: String) {
        records.remove(ownerRunId)
    }

    @Synchronized
    override fun clear() = records.clear()
}

class EncryptedAgentManagedResponseLedger(context: Context) : AgentManagedResponseLedger {
    private val database = AgentEncryptedDatabase(context.applicationContext, DATABASE)

    override fun register(record: AgentManagedResponseRecord) = synchronized(PROCESS_LOCK) {
        require(record.ownerRunId.isNotBlank() && record.sourceMessageId > 0L)
        val next = load().filterNot { it.ownerRunId == record.ownerRunId }
            .plus(record)
            .filterNot(AgentManagedResponseRecord::isStale)
            .takeLast(MAX_RECORDS)
        save(next)
    }

    override fun complete(response: AgentConnectorResponse): AgentManagedResponseRecord? = synchronized(PROCESS_LOCK) {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.correlates(response) }
        if (index < 0) return@synchronized null
        if (records[index].state != AgentManagedResponseState.PENDING) return@synchronized records[index]
        val completed = records[index].copy(
            state = AgentManagedResponseState.COMPLETED,
            response = response,
            completedAtMillis = response.receivedAtMillis
        )
        records[index] = completed
        save(records)
        AgentLateManagedResponseBus.publish(completed)
        completed
    }

    override fun acknowledge(response: AgentConnectorResponse): AgentManagedResponseRecord? =
        synchronized(PROCESS_LOCK) {
            val records = load().toMutableList()
            val index = records.indexOfFirst { it.correlates(response) }
            if (index < 0) return@synchronized null
            val acknowledged = records[index].copy(
                state = AgentManagedResponseState.APPLIED,
                response = response,
                completedAtMillis = response.receivedAtMillis
            )
            records[index] = acknowledged
            save(records)
            acknowledged
        }

    override fun pendingForSupervisor(supervisorRunId: String): List<AgentManagedResponseRecord> =
        synchronized(PROCESS_LOCK) {
            load().filter {
                it.supervisorRunId == supervisorRunId && it.state == AgentManagedResponseState.PENDING
            }.sortedBy(AgentManagedResponseRecord::createdAtMillis)
        }

    override fun completedUnapplied(): List<AgentManagedResponseRecord> = synchronized(PROCESS_LOCK) {
        load().filter { it.state == AgentManagedResponseState.COMPLETED && it.response != null }
            .sortedBy(AgentManagedResponseRecord::completedAtMillis)
    }

    override fun markApplied(ownerRunId: String) = synchronized(PROCESS_LOCK) {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.ownerRunId == ownerRunId }
        if (index >= 0 && records[index].state != AgentManagedResponseState.APPLIED) {
            records[index] = records[index].copy(state = AgentManagedResponseState.APPLIED)
            save(records)
        }
    }

    override fun removeOwner(ownerRunId: String) = synchronized(PROCESS_LOCK) {
        save(load().filterNot { it.ownerRunId == ownerRunId })
    }

    override fun clear() = synchronized(PROCESS_LOCK) { database.clear() }

    private fun load(): List<AgentManagedResponseRecord> = AgentManagedResponseCodec.decode(
        database.readString(KEY_RECORDS, "[]")
    ).filterNot(AgentManagedResponseRecord::isStale)

    private fun save(records: List<AgentManagedResponseRecord>) {
        database.writeString(KEY_RECORDS, AgentManagedResponseCodec.encode(records.takeLast(MAX_RECORDS)).toString())
    }

    private companion object {
        const val DATABASE = "signalasi_managed_connector_responses_v1"
        const val KEY_RECORDS = "records"
        const val MAX_RECORDS = 512
        val PROCESS_LOCK = Any()
    }
}

fun interface AgentLateManagedResponseListener {
    fun onLateResponse(record: AgentManagedResponseRecord)
}

object AgentLateManagedResponseBus {
    private val listeners = CopyOnWriteArraySet<AgentLateManagedResponseListener>()

    fun addListener(listener: AgentLateManagedResponseListener) {
        listeners += listener
    }

    fun removeListener(listener: AgentLateManagedResponseListener) {
        listeners -= listener
    }

    fun publish(record: AgentManagedResponseRecord) {
        listeners.forEach { it.onLateResponse(record) }
    }
}

private fun AgentManagedResponseRecord.correlates(response: AgentConnectorResponse): Boolean =
    sourceMessageId == response.sourceMessageId &&
        (contactId.isBlank() || response.contactId.isBlank() || contactId == response.contactId)

private fun AgentManagedResponseRecord.isStale(nowMillis: Long = System.currentTimeMillis()): Boolean =
    maxOf(createdAtMillis, completedAtMillis) < nowMillis - MAX_MANAGED_RESPONSE_AGE_MILLIS

private object AgentManagedResponseCodec {
    fun encode(records: List<AgentManagedResponseRecord>): JSONArray = JSONArray().apply {
        records.forEach { record ->
            put(JSONObject()
                .put("owner_run_id", record.ownerRunId)
                .put("supervisor_run_id", record.supervisorRunId)
                .put("agent_id", record.agentId)
                .put("delivery_mode", record.deliveryMode.name)
                .put("source_message_id", record.sourceMessageId)
                .put("contact_id", record.contactId)
                .put("state", record.state.name)
                .put("response", record.response?.let(::encodeResponse))
                .put("created_at_millis", record.createdAtMillis)
                .put("completed_at_millis", record.completedAtMillis))
        }
    }

    fun decode(raw: String): List<AgentManagedResponseRecord> = runCatching {
        val source = JSONArray(raw)
        buildList {
            for (index in 0 until source.length()) {
                val json = source.optJSONObject(index) ?: continue
                val ownerRunId = json.optString("owner_run_id")
                val sourceMessageId = json.optLong("source_message_id")
                if (ownerRunId.isBlank() || sourceMessageId <= 0L) continue
                add(AgentManagedResponseRecord(
                    ownerRunId = ownerRunId,
                    supervisorRunId = json.optString("supervisor_run_id"),
                    agentId = json.optString("agent_id"),
                    deliveryMode = enumValue(json.optString("delivery_mode"), AgentDeliveryMode.OBSERVE),
                    sourceMessageId = sourceMessageId,
                    contactId = json.optString("contact_id"),
                    state = enumValue(json.optString("state"), AgentManagedResponseState.PENDING),
                    response = decodeResponse(json.optJSONObject("response")),
                    createdAtMillis = json.optLong("created_at_millis"),
                    completedAtMillis = json.optLong("completed_at_millis")
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeResponse(response: AgentConnectorResponse): JSONObject = JSONObject()
        .put("source_message_id", response.sourceMessageId)
        .put("contact_id", response.contactId)
        .put("content", response.content.take(24_000))
        .put("conversation_id", response.conversationId)
        .put("turn_id", response.turnId)
        .put("task_id", response.taskId)
        .put("success", response.success)
        .put("input_tokens", response.inputTokens)
        .put("output_tokens", response.outputTokens)
        .put("cost_micros", response.costMicros)
        .put("rich_output", response.richOutputJson.take(48_000))
        .put("received_at_millis", response.receivedAtMillis)

    private fun decodeResponse(json: JSONObject?): AgentConnectorResponse? {
        json ?: return null
        val sourceMessageId = json.optLong("source_message_id")
        val content = json.optString("content")
        val richOutput = json.optString("rich_output")
        if (sourceMessageId <= 0L || (content.isBlank() && richOutput.isBlank())) return null
        return AgentConnectorResponse(
            sourceMessageId = sourceMessageId,
            contactId = json.optString("contact_id"),
            content = content,
            conversationId = json.optString("conversation_id"),
            turnId = json.optString("turn_id"),
            taskId = json.optString("task_id"),
            success = json.optBoolean("success", true),
            inputTokens = json.optLong("input_tokens"),
            outputTokens = json.optLong("output_tokens"),
            costMicros = json.optLong("cost_micros"),
            richOutputJson = richOutput,
            receivedAtMillis = json.optLong("received_at_millis")
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}

private const val MAX_MANAGED_RESPONSE_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
