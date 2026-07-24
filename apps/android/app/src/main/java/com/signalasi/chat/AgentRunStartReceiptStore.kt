package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class AgentRunStartReceiptStatus {
    RESERVED,
    ACCEPTED,
    OUTCOME_UNKNOWN,
    CANCELLED
}

data class AgentRunStartReceipt(
    val agentId: String,
    val installationId: String,
    val idempotencyKey: String,
    val requestDigest: String,
    val runId: String,
    val taskId: String,
    val status: AgentRunStartReceiptStatus,
    val handle: AgentRunHandle? = null,
    val error: String = "",
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

interface AgentRunStartReceiptStore {
    fun find(agentId: String, idempotencyKey: String): AgentRunStartReceipt?
    fun reserve(registration: AgentRegistration, request: AgentRunRequest): AgentRunStartReceipt
    fun accept(agentId: String, idempotencyKey: String, handle: AgentRunHandle): AgentRunStartReceipt
    fun markOutcomeUnknown(agentId: String, idempotencyKey: String, error: String): AgentRunStartReceipt?
    fun markCancelledByRun(agentId: String, runId: String): Int
    fun list(): List<AgentRunStartReceipt>
    fun clear()
}

abstract class AbstractAgentRunStartReceiptStore(
    private val clock: () -> Long
) : AgentRunStartReceiptStore {
    protected abstract fun readPersisted(): List<AgentRunStartReceipt>
    protected abstract fun writePersisted(receipts: List<AgentRunStartReceipt>)
    protected abstract fun clearPersisted()

    @Synchronized
    final override fun find(agentId: String, idempotencyKey: String): AgentRunStartReceipt? =
        readPersisted().firstOrNull {
            it.agentId == agentId.trim() && it.idempotencyKey == idempotencyKey.trim()
        }

    @Synchronized
    final override fun reserve(
        registration: AgentRegistration,
        request: AgentRunRequest
    ): AgentRunStartReceipt {
        val agentId = required(registration.agentId, "agent id")
        val installationId = required(registration.installationId, "installation id")
        val key = required(request.idempotencyKey, "idempotency key")
        val digest = AgentRunStartIdentity.requestDigest(request)
        val receipts = readPersisted().toMutableList()
        val existing = receipts.firstOrNull { it.agentId == agentId && it.idempotencyKey == key }
        if (existing != null) {
            require(existing.installationId == installationId) {
                "Run idempotency key belongs to a different Agent installation"
            }
            require(existing.requestDigest == digest) {
                "Run idempotency key was reused with different request content"
            }
            return existing
        }
        val now = now()
        val receipt = AgentRunStartReceipt(
            agentId = agentId,
            installationId = installationId,
            idempotencyKey = key,
            requestDigest = digest,
            runId = required(request.runId, "run id"),
            taskId = required(request.taskId, "task id"),
            status = AgentRunStartReceiptStatus.RESERVED,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        writePersisted(bound(receipts + receipt))
        return receipt
    }

    @Synchronized
    final override fun accept(
        agentId: String,
        idempotencyKey: String,
        handle: AgentRunHandle
    ): AgentRunStartReceipt {
        val receipts = readPersisted().toMutableList()
        val index = receipts.indexOfFirst {
            it.agentId == agentId.trim() && it.idempotencyKey == idempotencyKey.trim()
        }
        require(index >= 0) { "Run start was not reserved" }
        val current = receipts[index]
        require(current.runId == handle.runId && current.taskId == handle.taskId) {
            "Agent returned a handle for a different Run"
        }
        require(handle.agentId == current.agentId) { "Agent returned a handle for a different identity" }
        val accepted = current.copy(
            status = AgentRunStartReceiptStatus.ACCEPTED,
            handle = handle,
            error = "",
            updatedAtMillis = now()
        )
        receipts[index] = accepted
        writePersisted(bound(receipts))
        return accepted
    }

    @Synchronized
    final override fun markOutcomeUnknown(
        agentId: String,
        idempotencyKey: String,
        error: String
    ): AgentRunStartReceipt? = update(agentId, idempotencyKey) { current ->
        if (current.status == AgentRunStartReceiptStatus.ACCEPTED ||
            current.status == AgentRunStartReceiptStatus.CANCELLED
        ) current else current.copy(
            status = AgentRunStartReceiptStatus.OUTCOME_UNKNOWN,
            error = error.trim().take(MAX_ERROR_CHARS),
            updatedAtMillis = now()
        )
    }

    @Synchronized
    final override fun markCancelledByRun(agentId: String, runId: String): Int {
        val now = now()
        var changed = 0
        val receipts = readPersisted().map { receipt ->
            if (receipt.agentId == agentId.trim() && receipt.runId == runId.trim() &&
                receipt.status != AgentRunStartReceiptStatus.CANCELLED
            ) {
                changed += 1
                receipt.copy(status = AgentRunStartReceiptStatus.CANCELLED, updatedAtMillis = now)
            } else receipt
        }
        if (changed > 0) writePersisted(bound(receipts))
        return changed
    }

    @Synchronized
    final override fun list(): List<AgentRunStartReceipt> = readPersisted()
        .sortedWith(compareByDescending<AgentRunStartReceipt> { it.updatedAtMillis }.thenBy { it.idempotencyKey })

    @Synchronized
    final override fun clear() = clearPersisted()

    private fun update(
        agentId: String,
        idempotencyKey: String,
        transform: (AgentRunStartReceipt) -> AgentRunStartReceipt
    ): AgentRunStartReceipt? {
        val receipts = readPersisted().toMutableList()
        val index = receipts.indexOfFirst {
            it.agentId == agentId.trim() && it.idempotencyKey == idempotencyKey.trim()
        }
        if (index < 0) return null
        val updated = transform(receipts[index])
        receipts[index] = updated
        writePersisted(bound(receipts))
        return updated
    }

    private fun required(value: String, label: String): String {
        val clean = value.trim().take(MAX_ID_CHARS)
        require(clean.isNotBlank()) { "Run $label must not be blank" }
        return clean
    }

    private fun bound(receipts: List<AgentRunStartReceipt>): List<AgentRunStartReceipt> = receipts
        .sortedWith(compareBy<AgentRunStartReceipt> { it.updatedAtMillis }.thenBy { it.idempotencyKey })
        .takeLast(MAX_RECEIPTS)

    private fun now(): Long = clock().coerceAtLeast(0L)

    private companion object {
        const val MAX_RECEIPTS = 4_000
        const val MAX_ID_CHARS = 512
        const val MAX_ERROR_CHARS = 2_048
    }
}

class InMemoryAgentRunStartReceiptStore(
    serialized: String = "[]",
    clock: () -> Long = { System.currentTimeMillis() }
) : AbstractAgentRunStartReceiptStore(clock) {
    private var document = serialized

    override fun readPersisted(): List<AgentRunStartReceipt> = AgentRunStartReceiptJsonCodec.decode(document)

    override fun writePersisted(receipts: List<AgentRunStartReceipt>) {
        document = AgentRunStartReceiptJsonCodec.encode(receipts)
    }

    override fun clearPersisted() {
        document = "[]"
    }

    fun serializedSnapshot(): String = document
}

class EncryptedAgentRunStartReceiptStore(
    context: Context,
    clock: () -> Long = { System.currentTimeMillis() }
) : AbstractAgentRunStartReceiptStore(clock) {
    private val database = AgentEncryptedDatabase(context.applicationContext, DATABASE)

    override fun readPersisted(): List<AgentRunStartReceipt> =
        AgentRunStartReceiptJsonCodec.decode(database.readString(KEY_RECEIPTS, "[]"))

    override fun writePersisted(receipts: List<AgentRunStartReceipt>) {
        database.writeString(KEY_RECEIPTS, AgentRunStartReceiptJsonCodec.encode(receipts))
    }

    override fun clearPersisted() = database.clear()

    private companion object {
        const val DATABASE = "signalasi_run_start_receipts_v1"
        const val KEY_RECEIPTS = "receipts"
    }
}

object AgentRunStartIdentity {
    fun requestDigest(request: AgentRunRequest): String = AgentNativeJsonCodec.sha256(linkedMapOf(
        "conversation_id" to request.conversationId,
        "message_id" to request.messageId,
        "task_id" to request.taskId,
        "parent_run_id" to request.parentRunId,
        "goal" to request.goal,
        "delivery_mode" to request.deliveryMode.name,
        "required_capabilities" to request.requiredCapabilities.map { it.name }.sorted(),
        "context" to request.context,
        "idempotency_key" to request.idempotencyKey
    ))
}

object AgentRunStartReceiptJsonCodec {
    fun encode(receipts: List<AgentRunStartReceipt>): String = JSONArray().apply {
        receipts.forEach { receipt ->
            put(JSONObject()
                .put("agent_id", receipt.agentId)
                .put("installation_id", receipt.installationId)
                .put("idempotency_key", receipt.idempotencyKey)
                .put("request_digest", receipt.requestDigest)
                .put("run_id", receipt.runId)
                .put("task_id", receipt.taskId)
                .put("status", receipt.status.name)
                .put("handle", receipt.handle?.let { handle ->
                    JSONObject()
                        .put("run_id", handle.runId)
                        .put("task_id", handle.taskId)
                        .put("agent_id", handle.agentId)
                        .put("remote_run_id", handle.remoteRunId)
                        .put("accepted_at_millis", handle.acceptedAtMillis)
                })
                .put("error", receipt.error)
                .put("created_at_millis", receipt.createdAtMillis)
                .put("updated_at_millis", receipt.updatedAtMillis))
        }
    }.toString()

    fun decode(raw: String): List<AgentRunStartReceipt> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val status = runCatching {
                    AgentRunStartReceiptStatus.valueOf(item.optString("status"))
                }.getOrNull() ?: continue
                val handleJson = item.optJSONObject("handle")
                val handle = handleJson?.let {
                    AgentRunHandle(
                        runId = it.optString("run_id"),
                        taskId = it.optString("task_id"),
                        agentId = it.optString("agent_id"),
                        remoteRunId = it.optString("remote_run_id"),
                        acceptedAtMillis = it.optLong("accepted_at_millis")
                    )
                }
                add(AgentRunStartReceipt(
                    agentId = item.optString("agent_id"),
                    installationId = item.optString("installation_id"),
                    idempotencyKey = item.optString("idempotency_key"),
                    requestDigest = item.optString("request_digest"),
                    runId = item.optString("run_id"),
                    taskId = item.optString("task_id"),
                    status = status,
                    handle = handle,
                    error = item.optString("error"),
                    createdAtMillis = item.optLong("created_at_millis"),
                    updatedAtMillis = item.optLong("updated_at_millis")
                ))
            }
        }
    }.getOrDefault(emptyList())
}
