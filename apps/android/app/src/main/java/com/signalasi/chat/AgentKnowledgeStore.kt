package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class AgentKnowledgeItem(
    val id: String = UUID.randomUUID().toString(),
    val kind: AgentKnowledgeKind,
    val title: String,
    val content: String,
    val source: String = "",
    val tags: List<String> = emptyList(),
    val summary: String = "",
    val cloudAccess: AgentKnowledgeCloudAccess = AgentKnowledgeCloudAccess.DENY,
    val agentAccess: AgentKnowledgeAgentAccess = AgentKnowledgeAgentAccess.LOCAL_ONLY,
    val allowedAgentIds: List<String> = emptyList(),
    val chunkIndex: Int = 0,
    val chunkCount: Int = 1,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

data class AgentKnowledgeHit(
    val item: AgentKnowledgeItem,
    val score: Double,
    val excerpt: String,
    val matchedTerms: List<String>
)

data class AgentKnowledgeStats(
    val itemCount: Int = 0,
    val sourceCount: Int = 0,
    val lastUpdatedAtMillis: Long = 0L
)

enum class AgentKnowledgeKind {
    NOTE,
    DOCUMENT,
    SCREEN,
    CHAT,
    TASK
}

enum class AgentKnowledgeCloudAccess {
    DENY,
    SUMMARY_ONLY,
    FULL
}

enum class AgentKnowledgeAgentAccess {
    LOCAL_ONLY,
    SELECTED_AGENTS,
    ANY_PAIRED_AGENT
}

interface AgentKnowledgeStore {
    fun upsert(item: AgentKnowledgeItem)
    fun replaceSource(source: String, items: List<AgentKnowledgeItem>)
    fun search(query: String, limit: Int = 5): List<AgentKnowledgeItem>
    fun searchRanked(query: String, limit: Int = 8): List<AgentKnowledgeHit>
    fun list(limit: Int = 100): List<AgentKnowledgeItem>
    fun findByIds(ids: Set<String>): List<AgentKnowledgeItem>
    fun updateAccess(
        itemIds: Set<String>,
        cloudAccess: AgentKnowledgeCloudAccess,
        agentAccess: AgentKnowledgeAgentAccess,
        allowedAgentIds: List<String>
    ): Int
    fun delete(query: String): Int
    fun stats(): AgentKnowledgeStats
}

class SharedPreferencesAgentKnowledgeStore(context: Context) : AgentKnowledgeStore {
    private val prefs = AgentEncryptedPreferences(context, PREFS)

    override fun upsert(item: AgentKnowledgeItem) {
        val cleanTitle = item.title.trim()
        val cleanContent = item.content.trim()
        if (cleanTitle.isBlank() || cleanContent.isBlank()) return
        val next = item.copy(
            title = cleanTitle,
            content = cleanContent,
            summary = item.summary.trim().ifBlank { summarize(cleanContent) },
            tags = item.tags.map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.distinct().take(MAX_TAGS),
            allowedAgentIds = item.allowedAgentIds.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(MAX_ALLOWED_AGENTS),
            chunkIndex = item.chunkIndex.coerceAtLeast(0),
            chunkCount = item.chunkCount.coerceAtLeast(1)
        )
        val items = loadItems()
            .filterNot { it.id == next.id || (it.kind == next.kind && it.title.equals(next.title, ignoreCase = true)) }
            .plus(next)
            .sortedBy { it.updatedAtMillis }
            .takeLast(MAX_ITEMS)
        saveItems(items)
    }

    override fun replaceSource(source: String, items: List<AgentKnowledgeItem>) {
        val cleanSource = source.trim()
        if (cleanSource.isBlank() || items.isEmpty()) return
        val previousPolicy = loadItems().firstOrNull { it.source == cleanSource }
        val replacements = items
            .filter { it.source == cleanSource && it.title.isNotBlank() && it.content.isNotBlank() }
            .map { item ->
                item.copy(
                    summary = item.summary.trim().ifBlank { summarize(item.content) },
                    cloudAccess = previousPolicy?.cloudAccess ?: item.cloudAccess,
                    agentAccess = previousPolicy?.agentAccess ?: item.agentAccess,
                    allowedAgentIds = previousPolicy?.allowedAgentIds ?: item.allowedAgentIds
                )
            }
            .distinctBy { it.id }
        if (replacements.isEmpty()) return
        val next = loadItems()
            .filterNot { it.source == cleanSource }
            .plus(replacements)
            .sortedBy { it.updatedAtMillis }
            .takeLast(MAX_ITEMS)
        saveItems(next)
    }

    override fun search(query: String, limit: Int): List<AgentKnowledgeItem> {
        return searchRanked(query, limit).map { it.item }
    }

    override fun searchRanked(query: String, limit: Int): List<AgentKnowledgeHit> {
        val cleanQuery = query.trim()
        val items = loadItems()
        if (cleanQuery.isBlank()) {
            return items.sortedByDescending { it.updatedAtMillis }.take(limit.coerceAtLeast(0)).map {
                AgentKnowledgeHit(it, 0.0, excerpt(it.content, emptyList()), emptyList())
            }
        }
        val queryTokens = AgentKnowledgeTextAnalyzer.tokens(cleanQuery)
        val queryTrigrams = AgentKnowledgeTextAnalyzer.trigrams(cleanQuery)
        return items.asSequence()
            .map { item ->
                val matchedTerms = queryTokens.filter { token -> item.searchText().contains(token) }.distinct()
                val score = semanticScore(item, cleanQuery, queryTokens, queryTrigrams)
                AgentKnowledgeHit(item, score, excerpt(item.content, matchedTerms), matchedTerms)
            }
            .filter { it.score >= MIN_SEARCH_SCORE }
            .sortedWith(compareByDescending<AgentKnowledgeHit> { it.score }.thenByDescending { it.item.updatedAtMillis })
            .take(limit.coerceIn(0, MAX_SEARCH_RESULTS))
            .toList()
    }

    override fun list(limit: Int): List<AgentKnowledgeItem> = loadItems()
        .sortedByDescending { it.updatedAtMillis }
        .take(limit.coerceIn(0, MAX_ITEMS))

    override fun findByIds(ids: Set<String>): List<AgentKnowledgeItem> {
        if (ids.isEmpty()) return emptyList()
        return loadItems().filter { it.id in ids }
    }

    override fun updateAccess(
        itemIds: Set<String>,
        cloudAccess: AgentKnowledgeCloudAccess,
        agentAccess: AgentKnowledgeAgentAccess,
        allowedAgentIds: List<String>
    ): Int {
        if (itemIds.isEmpty()) return 0
        val normalizedAgents = allowedAgentIds.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(MAX_ALLOWED_AGENTS)
        var updated = 0
        val items = loadItems().map { item ->
            if (item.id !in itemIds) item else {
                updated += 1
                item.copy(
                    cloudAccess = cloudAccess,
                    agentAccess = agentAccess,
                    allowedAgentIds = if (agentAccess == AgentKnowledgeAgentAccess.SELECTED_AGENTS) normalizedAgents else emptyList(),
                    updatedAtMillis = System.currentTimeMillis()
                )
            }
        }
        if (updated > 0) saveItems(items)
        return updated
    }

    override fun delete(query: String): Int {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return 0
        val tokens = AgentKnowledgeTextAnalyzer.tokens(cleanQuery)
        val items = loadItems()
        val kept = items.filter { item ->
            semanticScore(item, cleanQuery, tokens, AgentKnowledgeTextAnalyzer.trigrams(cleanQuery)) < MIN_SEARCH_SCORE
        }
        if (kept.size != items.size) saveItems(kept)
        return items.size - kept.size
    }

    override fun stats(): AgentKnowledgeStats {
        val items = loadItems()
        return AgentKnowledgeStats(
            itemCount = items.size,
            sourceCount = items.map { it.source }.filter { it.isNotBlank() }.distinct().size,
            lastUpdatedAtMillis = items.maxOfOrNull { it.updatedAtMillis } ?: 0L
        )
    }

    private fun semanticScore(
        item: AgentKnowledgeItem,
        query: String,
        queryTokens: List<String>,
        queryTrigrams: Set<String>
    ): Double {
        val title = item.title.lowercase(Locale.US)
        val summary = item.summary.lowercase(Locale.US)
        val content = item.content.lowercase(Locale.US)
        val tags = item.tags.joinToString(" ").lowercase(Locale.US)
        val phrase = query.lowercase(Locale.US).replace(Regex("\\s+"), " ").trim()
        var score = 0.0
        if (title.contains(phrase)) score += 14.0
        if (summary.contains(phrase)) score += 10.0
        if (content.contains(phrase)) score += 7.0
        queryTokens.forEach { token ->
            if (title.contains(token)) score += 4.5
            if (tags.contains(token)) score += 3.5
            if (summary.contains(token)) score += 2.5
            if (content.contains(token)) score += 1.2
        }
        if (queryTokens.isNotEmpty()) {
            val matched = queryTokens.count { item.searchText().contains(it) }
            score += matched.toDouble() / queryTokens.size * 6.0
        }
        val itemTrigrams = AgentKnowledgeTextAnalyzer.trigrams("${item.title} ${item.summary} ${item.content.take(1_200)}")
        if (queryTrigrams.isNotEmpty() && itemTrigrams.isNotEmpty()) {
            val intersection = queryTrigrams.count { it in itemTrigrams }
            val union = queryTrigrams.size + itemTrigrams.size - intersection
            if (union > 0) score += intersection.toDouble() / union * 9.0
        }
        return score
    }

    private fun AgentKnowledgeItem.searchText(): String =
        "$title $summary ${tags.joinToString(" ")} $content".lowercase(Locale.US)

    private fun summarize(content: String): String = content
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_SUMMARY_CHARACTERS)

    private fun excerpt(content: String, terms: List<String>): String {
        val normalized = content.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return ""
        val lower = normalized.lowercase(Locale.US)
        val matchIndex = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (matchIndex - EXCERPT_CONTEXT_BEFORE).coerceAtLeast(0)
        val end = (matchIndex + MAX_EXCERPT_CHARACTERS).coerceAtMost(normalized.length)
        return buildString {
            if (start > 0) append("...")
            append(normalized.substring(start, end))
            if (end < normalized.length) append("...")
        }
    }

    private fun loadItems(): List<AgentKnowledgeItem> {
        val raw = prefs.readString(KEY_ITEMS, "[]")
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    decodeItem(array.optJSONObject(index) ?: continue)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveItems(items: List<AgentKnowledgeItem>) {
        val array = JSONArray()
        items.forEach { array.put(encodeItem(it)) }
        prefs.writeString(KEY_ITEMS, array.toString())
    }

    private fun encodeItem(item: AgentKnowledgeItem): JSONObject = JSONObject()
        .put("id", item.id)
        .put("kind", item.kind.name)
        .put("title", item.title)
        .put("content", item.content)
        .put("source", item.source)
        .put("tags", JSONArray().also { array -> item.tags.forEach { array.put(it) } })
        .put("summary", item.summary)
        .put("cloud_access", item.cloudAccess.name)
        .put("agent_access", item.agentAccess.name)
        .put("allowed_agent_ids", JSONArray().also { array -> item.allowedAgentIds.forEach { array.put(it) } })
        .put("chunk_index", item.chunkIndex)
        .put("chunk_count", item.chunkCount)
        .put("updated_at_millis", item.updatedAtMillis)

    private fun decodeItem(json: JSONObject): AgentKnowledgeItem? {
        val title = json.optString("title")
        val content = json.optString("content")
        if (title.isBlank() || content.isBlank()) return null
        return AgentKnowledgeItem(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            kind = enumOrDefault(json.optString("kind"), AgentKnowledgeKind.NOTE),
            title = title,
            content = content,
            source = json.optString("source"),
            tags = decodeStringList(json.optJSONArray("tags")),
            summary = json.optString("summary").ifBlank { summarize(content) },
            cloudAccess = enumOrDefault(json.optString("cloud_access"), AgentKnowledgeCloudAccess.DENY),
            agentAccess = enumOrDefault(json.optString("agent_access"), AgentKnowledgeAgentAccess.LOCAL_ONLY),
            allowedAgentIds = decodeStringList(json.optJSONArray("allowed_agent_ids")),
            chunkIndex = json.optInt("chunk_index", 0).coerceAtLeast(0),
            chunkCount = json.optInt("chunk_count", 1).coerceAtLeast(1),
            updatedAtMillis = json.optLong("updated_at_millis", System.currentTimeMillis())
        )
    }

    private fun decodeStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrElse { default }

    private companion object {
        const val PREFS = "signalasi_agent_knowledge"
        const val KEY_ITEMS = "items"
        const val MAX_ITEMS = 500
        const val MAX_TAGS = 24
        const val MAX_ALLOWED_AGENTS = 24
        const val MAX_SEARCH_RESULTS = 24
        const val MAX_SUMMARY_CHARACTERS = 480
        const val MAX_EXCERPT_CHARACTERS = 420
        const val EXCERPT_CONTEXT_BEFORE = 120
        const val MIN_SEARCH_SCORE = 1.2
    }
}

object AgentKnowledgeTextAnalyzer {
    fun tokens(value: String): List<String> {
        val normalized = value.lowercase(Locale.US)
        val words = Regex("[\\p{L}\\p{N}]{2,}")
            .findAll(normalized)
            .map { it.value }
            .filter { it !in STOP_WORDS }
            .toList()
        val cjk = normalized.filter { it.isCjk() }
            .windowed(size = 2, step = 1, partialWindows = false)
        return (words + cjk).distinct().take(MAX_QUERY_TOKENS)
    }

    fun trigrams(value: String): Set<String> {
        val normalized = value.lowercase(Locale.US).filter { it.isLetterOrDigit() }
        if (normalized.length < 3) return emptySet()
        return normalized.windowed(3).take(MAX_TRIGRAMS).toSet()
    }

    private fun Char.isCjk(): Boolean = code in 0x3400..0x9FFF

    private val STOP_WORDS = setOf("the", "and", "for", "with", "from", "this", "that", "what", "when", "where")
    private const val MAX_QUERY_TOKENS = 64
    private const val MAX_TRIGRAMS = 512
}
