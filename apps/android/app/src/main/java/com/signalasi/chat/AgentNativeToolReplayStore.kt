package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AgentNativeToolReplayKey(
    val toolId: String,
    val toolVersion: String,
    val idempotencyKey: String
)

interface AgentNativeToolReplayStore {
    fun get(key: AgentNativeToolReplayKey): AgentNativeToolResult?
    fun put(key: AgentNativeToolReplayKey, result: AgentNativeToolResult)
    fun clear()
}

class InMemoryAgentNativeToolReplayStore : AgentNativeToolReplayStore {
    private val entries = LinkedHashMap<AgentNativeToolReplayKey, AgentNativeToolResult>()

    @Synchronized
    override fun get(key: AgentNativeToolReplayKey): AgentNativeToolResult? = entries[key]

    @Synchronized
    override fun put(key: AgentNativeToolReplayKey, result: AgentNativeToolResult) {
        entries[key] = result
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    @Synchronized
    override fun clear() = entries.clear()

    companion object {
        private const val MAX_ENTRIES = 2_000
    }
}

class EncryptedAgentNativeToolReplayStore(context: Context) : AgentNativeToolReplayStore {
    private val database = AgentEncryptedDatabase(
        context.applicationContext,
        DATABASE,
        legacyPreferencesName = UNUSED_LEGACY_PREFERENCES
    )

    @Synchronized
    override fun get(key: AgentNativeToolReplayKey): AgentNativeToolResult? {
        val now = System.currentTimeMillis()
        val loaded = load()
        val retained = loaded.filter { now - it.savedAtMillis <= RETENTION_MILLIS }
        if (retained.size != loaded.size) save(retained)
        return retained.lastOrNull { it.key == key }?.result
    }

    @Synchronized
    override fun put(key: AgentNativeToolReplayKey, result: AgentNativeToolResult) {
        require(result.isSuccess) { "Only successful native tool results may be replayed" }
        val now = System.currentTimeMillis()
        val entries = load()
            .filter { now - it.savedAtMillis <= RETENTION_MILLIS && it.key != key }
            .plus(StoredReplay(key, result, now))
            .takeLast(MAX_ENTRIES)
        save(entries)
    }

    @Synchronized
    override fun clear() = database.clear()

    private fun load(): List<StoredReplay> = decode(database.readString(KEY_ENTRIES, "[]"))

    private fun save(entries: List<StoredReplay>) {
        database.writeString(KEY_ENTRIES, JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject()
                    .put("tool_id", entry.key.toolId)
                    .put("tool_version", entry.key.toolVersion)
                    .put("idempotency_key", entry.key.idempotencyKey)
                    .put("saved_at_millis", entry.savedAtMillis)
                    .put("result", JSONObject(entry.result.toJson())))
            }
        }.toString())
    }

    private fun decode(raw: String): List<StoredReplay> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val result = item.optJSONObject("result")?.toNativeToolResult() ?: continue
                val key = AgentNativeToolReplayKey(
                    toolId = item.optString("tool_id"),
                    toolVersion = item.optString("tool_version"),
                    idempotencyKey = item.optString("idempotency_key")
                )
                if (key.toolId.isBlank() || key.toolVersion.isBlank() || key.idempotencyKey.isBlank()) continue
                add(StoredReplay(key, result, item.optLong("saved_at_millis")))
            }
        }
    }.getOrDefault(emptyList())

    private data class StoredReplay(
        val key: AgentNativeToolReplayKey,
        val result: AgentNativeToolResult,
        val savedAtMillis: Long
    )

    companion object {
        private const val DATABASE = "signalasi_native_tool_replay_v1"
        private const val UNUSED_LEGACY_PREFERENCES = "signalasi_native_tool_replay_v1_no_legacy"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 2_000
        private const val RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L
    }
}

private fun JSONObject.toNativeToolResult(): AgentNativeToolResult? = runCatching {
    val receiptJson = getJSONObject("receipt")
    val provenanceJson = getJSONObject("provenance")
    val errorJson = optJSONObject("error")
    val verificationJson = optJSONObject("verification")
    AgentNativeToolResult(
        status = resultStatus(optString("status")),
        output = optJSONObject("output").toNativeObject(),
        message = optString("message"),
        metadata = optJSONObject("metadata").toNativeObject(),
        error = errorJson?.let { error ->
            AgentNativeToolError(
                code = error.optString("code"),
                message = error.optString("message"),
                retryable = error.optBoolean("retryable"),
                details = error.optJSONObject("details").toNativeObject()
            )
        },
        verification = verificationJson?.let { verification ->
            AgentNativeToolVerification(
                status = verificationStatus(verification.optString("status")),
                message = verification.optString("message"),
                evidence = verification.optJSONObject("evidence").toNativeObject()
            )
        },
        receipt = AgentNativeToolReceipt(
            invocationId = receiptJson.getString("invocation_id"),
            idempotencyKey = receiptJson.optString("idempotency_key").takeIf(String::isNotBlank),
            startedAtEpochMillis = receiptJson.optLong("started_at_epoch_ms"),
            finishedAtEpochMillis = receiptJson.optLong("finished_at_epoch_ms"),
            durationMillis = receiptJson.optLong("duration_ms"),
            status = resultStatus(receiptJson.optString("status")),
            inputSha256 = receiptJson.optString("input_sha256"),
            outputSha256 = receiptJson.optString("output_sha256"),
            replayed = receiptJson.optBoolean("replayed"),
            originalInvocationId = receiptJson.optString("original_invocation_id").takeIf(String::isNotBlank)
        ),
        provenance = AgentNativeToolProvenance(
            toolId = provenanceJson.getString("tool_id"),
            toolVersion = provenanceJson.getString("tool_version"),
            location = nativeLocation(provenanceJson.optString("location")),
            executorId = provenanceJson.optString("executor_id"),
            contractVersion = provenanceJson.optString("contract_version"),
            legacyAgentActionId = provenanceJson.optString("legacy_agent_action_id").takeIf(String::isNotBlank),
            metadata = provenanceJson.optJSONObject("metadata").toNativeObject()
                .mapValues { it.value?.toString().orEmpty() }
        )
    )
}.getOrNull()

private fun JSONObject?.toNativeObject(): AgentNativeJsonObject {
    val source = this ?: return emptyMap()
    return source.keys().asSequence().associateWith { key -> source.opt(key).toNativeValue() }
}

private fun Any?.toNativeValue(): Any? = when (this) {
    null, JSONObject.NULL -> null
    is JSONObject -> toNativeObject()
    is JSONArray -> buildList {
        for (index in 0 until length()) add(opt(index).toNativeValue())
    }
    else -> this
}

private fun resultStatus(value: String): AgentNativeToolResultStatus =
    AgentNativeToolResultStatus.entries.firstOrNull { it.wireValue == value }
        ?: AgentNativeToolResultStatus.FAILED

private fun verificationStatus(value: String): AgentNativeVerificationStatus =
    AgentNativeVerificationStatus.entries.firstOrNull { it.wireValue == value }
        ?: AgentNativeVerificationStatus.SKIPPED

private fun nativeLocation(value: String): AgentNativeToolLocation =
    AgentNativeToolLocation.entries.firstOrNull { it.wireValue == value }
        ?: AgentNativeToolLocation.UNKNOWN
