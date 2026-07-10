package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class AgentKnowledgeEvidenceMode {
    FULL,
    SUMMARY
}

data class AgentKnowledgeCitation(
    val index: Int,
    val itemId: String,
    val title: String,
    val source: String,
    val excerpt: String,
    val score: Double,
    val evidenceMode: AgentKnowledgeEvidenceMode
)

data class AgentKnowledgeRagContext(
    val query: String,
    val targetId: String,
    val citations: List<AgentKnowledgeCitation>,
    val blockedMatchCount: Int = 0
) {
    val sourceCount: Int get() = citations.map { it.source }.distinct().size
}

data class AgentKnowledgeSourceGroup(
    val source: String,
    val title: String,
    val itemIds: Set<String>,
    val chunkCount: Int,
    val cloudAccess: AgentKnowledgeCloudAccess,
    val agentAccess: AgentKnowledgeAgentAccess,
    val allowedAgentIds: List<String>,
    val updatedAtMillis: Long
)

object AgentKnowledgeRetriever {
    fun retrieve(
        store: AgentKnowledgeStore,
        query: String,
        targetId: String,
        limit: Int = 8
    ): AgentKnowledgeRagContext {
        val ranked = store.searchRanked(query, limit = MAX_CANDIDATES)
        val allowed = mutableListOf<Pair<AgentKnowledgeHit, AgentKnowledgeEvidenceMode>>()
        var blocked = 0
        ranked.forEach { hit ->
            val mode = evidenceMode(hit.item, targetId)
            if (mode == null) blocked += 1 else allowed += hit to mode
        }
        val citations = allowed.take(limit.coerceIn(1, MAX_CITATIONS)).mapIndexed { index, (hit, mode) ->
            val evidence = when (mode) {
                AgentKnowledgeEvidenceMode.FULL -> hit.excerpt
                AgentKnowledgeEvidenceMode.SUMMARY -> hit.item.summary.ifBlank { hit.excerpt }.take(MAX_SUMMARY_EVIDENCE)
            }
            AgentKnowledgeCitation(
                index = index + 1,
                itemId = hit.item.id,
                title = hit.item.title,
                source = sourceLabel(hit.item.source),
                excerpt = evidence,
                score = hit.score,
                evidenceMode = mode
            )
        }
        return AgentKnowledgeRagContext(query, targetId, citations, blocked)
    }

    fun sourceGroups(store: AgentKnowledgeStore): List<AgentKnowledgeSourceGroup> = store.list(MAX_SOURCE_ITEMS)
        .groupBy { it.source.ifBlank { "local:${it.kind.name.lowercase(Locale.US)}:${it.title}" } }
        .map { (source, items) ->
            val latest = items.maxByOrNull { it.updatedAtMillis } ?: items.first()
            AgentKnowledgeSourceGroup(
                source = source,
                title = latest.title.substringBeforeLast(" [").ifBlank { latest.title },
                itemIds = items.map { it.id }.toSet(),
                chunkCount = items.size,
                cloudAccess = latest.cloudAccess,
                agentAccess = latest.agentAccess,
                allowedAgentIds = latest.allowedAgentIds,
                updatedAtMillis = items.maxOf { it.updatedAtMillis }
            )
        }
        .sortedByDescending { it.updatedAtMillis }

    private fun evidenceMode(item: AgentKnowledgeItem, targetId: String): AgentKnowledgeEvidenceMode? = when {
        targetId == "local-llm" || targetId == "agent-knowledge-local" -> AgentKnowledgeEvidenceMode.FULL
        targetId == "cloud-models" || targetId.startsWith("cloud-model:") -> when (item.cloudAccess) {
            AgentKnowledgeCloudAccess.DENY -> null
            AgentKnowledgeCloudAccess.SUMMARY_ONLY -> AgentKnowledgeEvidenceMode.SUMMARY
            AgentKnowledgeCloudAccess.FULL -> AgentKnowledgeEvidenceMode.FULL
        }
        item.agentAccess == AgentKnowledgeAgentAccess.ANY_PAIRED_AGENT -> AgentKnowledgeEvidenceMode.FULL
        item.agentAccess == AgentKnowledgeAgentAccess.SELECTED_AGENTS &&
            item.allowedAgentIds.any { it == targetId } -> AgentKnowledgeEvidenceMode.FULL
        else -> null
    }

    fun sourceLabel(source: String): String = when {
        source.isBlank() -> "Local knowledge"
        source.startsWith("http://", true) || source.startsWith("https://", true) -> source.take(240)
        source.startsWith("content://", true) -> "Imported document ${source.hashCode()}"
        source.startsWith("local:") -> "Local knowledge"
        else -> source.replace(Regex("\\s+"), " ").take(180)
    }

    private const val MAX_CANDIDATES = 24
    private const val MAX_CITATIONS = 10
    private const val MAX_SUMMARY_EVIDENCE = 700
    private const val MAX_SOURCE_ITEMS = 500
}

data class AgentKnowledgeAccessAuditEntry(
    val queryHash: Int,
    val targetId: String,
    val itemIdHashes: List<Int>,
    val sourceCount: Int,
    val evidenceModes: List<AgentKnowledgeEvidenceMode>,
    val blockedMatchCount: Int,
    val timestampMillis: Long = System.currentTimeMillis()
)

class AgentKnowledgeAccessAuditStore(context: Context) {
    private val preferences = AgentEncryptedPreferences(context, PREFS)

    @Synchronized
    fun record(context: AgentKnowledgeRagContext) {
        val entry = AgentKnowledgeAccessAuditEntry(
            queryHash = context.query.hashCode(),
            targetId = context.targetId.take(MAX_TARGET_LENGTH),
            itemIdHashes = context.citations.map { it.itemId.hashCode() },
            sourceCount = context.sourceCount,
            evidenceModes = context.citations.map { it.evidenceMode }.distinct(),
            blockedMatchCount = context.blockedMatchCount
        )
        val entries = load().plus(entry).takeLast(MAX_ENTRIES)
        save(entries)
    }

    @Synchronized
    fun recent(limit: Int = 20): List<AgentKnowledgeAccessAuditEntry> = load()
        .takeLast(limit.coerceIn(0, MAX_ENTRIES))
        .asReversed()

    @Synchronized
    fun clear() = preferences.clear()

    private fun load(): List<AgentKnowledgeAccessAuditEntry> = runCatching {
        val array = JSONArray(preferences.readString(KEY_ENTRIES, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    AgentKnowledgeAccessAuditEntry(
                        queryHash = item.optInt("query_hash"),
                        targetId = item.optString("target_id"),
                        itemIdHashes = item.optJSONArray("item_hashes").intValues(),
                        sourceCount = item.optInt("source_count"),
                        evidenceModes = item.optJSONArray("evidence_modes").enumValues(AgentKnowledgeEvidenceMode.FULL),
                        blockedMatchCount = item.optInt("blocked_match_count"),
                        timestampMillis = item.optLong("timestamp_millis")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun save(entries: List<AgentKnowledgeAccessAuditEntry>) {
        preferences.writeString(
            KEY_ENTRIES,
            JSONArray().also { array ->
                entries.forEach { entry ->
                    array.put(
                        JSONObject()
                            .put("query_hash", entry.queryHash)
                            .put("target_id", entry.targetId)
                            .put("item_hashes", JSONArray(entry.itemIdHashes))
                            .put("source_count", entry.sourceCount)
                            .put("evidence_modes", JSONArray(entry.evidenceModes.map { it.name }))
                            .put("blocked_match_count", entry.blockedMatchCount)
                            .put("timestamp_millis", entry.timestampMillis)
                    )
                }
            }.toString()
        )
    }

    private fun JSONArray?.intValues(): List<Int> {
        if (this == null) return emptyList()
        return buildList { for (index in 0 until length()) add(optInt(index)) }
    }

    private inline fun <reified T : Enum<T>> JSONArray?.enumValues(default: T): List<T> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                add(runCatching { enumValueOf<T>(optString(index)) }.getOrDefault(default))
            }
        }
    }

    companion object {
        private const val PREFS = "signalasi_agent_knowledge_audit"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 200
        private const val MAX_TARGET_LENGTH = 160
    }
}
