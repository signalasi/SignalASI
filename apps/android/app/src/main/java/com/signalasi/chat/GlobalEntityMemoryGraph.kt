package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class GlobalEntityNodeKind {
    USER,
    DEVICE,
    APPLICATION,
    FEATURE,
    SETTING,
    AGENT,
    MODEL,
    TOOL,
    PROJECT,
    CONCEPT,
    STATE
}

enum class GlobalEntityRelationKind {
    OWNS,
    USES,
    SUPPORTS,
    HAS_COMPONENT,
    HAS_STATE,
    NAMED_AS,
    DEPENDS_ON,
    CONNECTED_TO,
    PREFERS,
    REMOVED,
    RELATED_TO
}

data class GlobalEntityNode(
    val id: String,
    val stableKey: String,
    val label: String,
    val kind: GlobalEntityNodeKind,
    val aliases: Set<String> = emptySet(),
    val temporalState: GlobalMemoryTemporalState = GlobalMemoryTemporalState.CURRENT,
    val confidence: Double = 0.5,
    val evidence: List<GlobalEvidenceRef> = emptyList(),
    val firstSeenAtMillis: Long = System.currentTimeMillis(),
    val lastSeenAtMillis: Long = System.currentTimeMillis()
)

data class GlobalEntityRelation(
    val id: String,
    val fromNodeId: String,
    val toNodeId: String,
    val kind: GlobalEntityRelationKind,
    val temporalState: GlobalMemoryTemporalState = GlobalMemoryTemporalState.CURRENT,
    val confidence: Double = 0.5,
    val evidence: List<GlobalEvidenceRef> = emptyList(),
    val validFromMillis: Long = System.currentTimeMillis(),
    val validUntilMillis: Long = 0L,
    val lastSeenAtMillis: Long = System.currentTimeMillis()
)

data class GlobalEntityMemoryGraph(
    val nodes: List<GlobalEntityNode> = emptyList(),
    val relations: List<GlobalEntityRelation> = emptyList(),
    val processedEventIds: List<String> = emptyList(),
    val retractedEventIds: List<String> = emptyList(),
    val updatedAtMillis: Long = 0L
) {
    fun relevant(
        query: String,
        hops: Int = 2,
        limit: Int = 24,
        includeHistorical: Boolean = false
    ): GlobalEntityGraphSelection {
        val tokens = GlobalAgentText.tokens(query)
        val allowedStates = if (includeHistorical) {
            GlobalMemoryTemporalState.entries.toSet()
        } else setOf(
            GlobalMemoryTemporalState.CURRENT,
            GlobalMemoryTemporalState.PLANNED,
            GlobalMemoryTemporalState.CONFLICTED,
            GlobalMemoryTemporalState.PENDING
        )
        val rankedSeeds = nodes.asSequence()
            .filter { it.temporalState in allowedStates }
            .map { node ->
                val text = (listOf(node.label) + node.aliases).joinToString(" ")
                node to (GlobalAgentText.overlap(tokens, GlobalAgentText.tokens(text)) + node.confidence * 0.15)
            }
            .filter { (_, score) -> score >= 0.16 }
            .sortedByDescending(Pair<GlobalEntityNode, Double>::second)
            .take(8)
            .map(Pair<GlobalEntityNode, Double>::first)
            .toList()
        if (rankedSeeds.isEmpty()) return GlobalEntityGraphSelection()
        val selectedIds = rankedSeeds.mapTo(mutableSetOf(), GlobalEntityNode::id)
        repeat(hops.coerceIn(0, 3)) {
            val neighbors = relations.asSequence()
                .filter { it.temporalState in allowedStates }
                .filter { it.fromNodeId in selectedIds || it.toNodeId in selectedIds }
                .sortedByDescending(GlobalEntityRelation::confidence)
                .flatMap { sequenceOf(it.fromNodeId, it.toNodeId) }
                .filterNot(selectedIds::contains)
                .take(limit)
                .toList()
            if (neighbors.isEmpty()) return@repeat
            selectedIds += neighbors
        }
        val selectedNodes = nodes.filter { it.id in selectedIds }
            .sortedByDescending(GlobalEntityNode::lastSeenAtMillis)
            .take(limit.coerceIn(1, 60))
        val boundedIds = selectedNodes.map(GlobalEntityNode::id).toSet()
        val selectedRelations = relations.filter {
            it.fromNodeId in boundedIds && it.toNodeId in boundedIds && it.temporalState in allowedStates
        }.sortedByDescending(GlobalEntityRelation::confidence).take(limit.coerceIn(1, 60) * 2)
        return GlobalEntityGraphSelection(selectedNodes, selectedRelations)
    }
}

data class GlobalEntityGraphSelection(
    val nodes: List<GlobalEntityNode> = emptyList(),
    val relations: List<GlobalEntityRelation> = emptyList()
)

object GlobalEntityMemoryGraphReducer {
    fun reduce(
        graph: GlobalEntityMemoryGraph,
        event: GlobalConversationEvent,
        understanding: GlobalUnderstanding,
        reduction: GlobalWorldReduction
    ): GlobalEntityMemoryGraph {
        if (event.id in graph.processedEventIds) return graph
        val retractions = event.effectiveRetractions()
        var working = retract(graph, retractions, event.timestampMillis)
        val processed = (working.processedEventIds + event.id).distinct().takeLast(MAX_PROCESSED_EVENTS)
        val retracted = (working.retractedEventIds + retractions).distinct().takeLast(MAX_PROCESSED_EVENTS)
        if (event.sensitivity == GlobalConversationSensitivity.SESSION_PRIVATE ||
            event.evidenceRoots().any(retracted::contains) ||
            event.excludesConversationFromGlobalModel()
        ) {
            return working.copy(
                processedEventIds = processed,
                retractedEventIds = retracted,
                updatedAtMillis = maxOf(working.updatedAtMillis, event.timestampMillis)
            )
        }
        val acceptedItems = reduction.changedItems.filter { item ->
            item.evidenceEventIds.contains(event.id) || item.evidenceProvenance.any { it.eventId == event.id }
        }
        if (acceptedItems.isEmpty()) {
            return working.copy(
                processedEventIds = processed,
                retractedEventIds = retracted,
                updatedAtMillis = maxOf(working.updatedAtMillis, event.timestampMillis)
            )
        }
        val acceptedContent = acceptedItems.joinToString(". ") { item ->
            item.value.ifBlank { item.topic }
        }.take(4_800)
        val acceptedEvent = event.copy(content = acceptedContent)
        val evidence = acceptedEvent.evidenceRef()
        val nodes = working.nodes.toMutableList()
        val relations = working.relations.toMutableList()
        val topic = acceptedItems.firstOrNull()?.topic.orEmpty().ifBlank { understanding.topic }.trim().take(160)
        val project = understanding.project.trim().take(160).takeIf { candidate ->
            candidate.isNotBlank() && GlobalAgentText.overlap(
                GlobalAgentText.tokens(candidate),
                GlobalAgentText.tokens(acceptedContent)
            ) > 0.0
        }.orEmpty()
        val acceptedTokens = GlobalAgentText.tokens(acceptedContent)
        val labels = buildList {
            addAll(understanding.entities.filter { entity ->
                GlobalAgentText.overlap(GlobalAgentText.tokens(entity), acceptedTokens) > 0.0
            })
            topic.takeIf(String::isNotBlank)?.let(::add)
            project.takeIf(String::isNotBlank)?.let(::add)
            acceptedItems.map(GlobalWorldItem::topic).filter(String::isNotBlank).forEach(::add)
        }.map(::cleanLabel).filter { it.length >= 2 }.distinctBy(GlobalAgentText::normalize).take(MAX_NODES_PER_EVENT)
        val eventNodes = labels.associateWith { label ->
            upsertNode(nodes, label, classify(label, project), evidence, acceptedEvent)
        }
        if (topic.isNotBlank()) {
            val topicNode = eventNodes[cleanLabel(topic)] ?: upsertNode(
                nodes,
                topic,
                if (project.isNotBlank() && GlobalAgentText.normalize(project) == GlobalAgentText.normalize(topic)) {
                    GlobalEntityNodeKind.PROJECT
                } else GlobalEntityNodeKind.CONCEPT,
                evidence,
                acceptedEvent
            )
            eventNodes.values.filter { it.id != topicNode.id }.take(MAX_RELATIONS_PER_EVENT).forEach { entity ->
                upsertRelation(
                    relations,
                    topicNode.id,
                    entity.id,
                    GlobalEntityRelationKind.RELATED_TO,
                    evidence,
                    event.timestampMillis,
                    0.66
                )
            }
        }
        explicitTriples(acceptedContent).take(MAX_RELATIONS_PER_EVENT).forEach { triple ->
            val from = upsertNode(nodes, triple.from, classify(triple.from, project), evidence, acceptedEvent)
            val to = upsertNode(nodes, triple.to, classify(triple.to, project), evidence, acceptedEvent)
            closeSupersededRelations(relations, from.id, triple.kind, event.timestampMillis)
            upsertRelation(
                relations,
                from.id,
                to.id,
                triple.kind,
                evidence,
                event.timestampMillis,
                if (triple.explicit) 0.92 else 0.72
            )
        }
        val boundedNodes = nodes.sortedByDescending(GlobalEntityNode::lastSeenAtMillis).take(MAX_NODES)
        val nodeIds = boundedNodes.map(GlobalEntityNode::id).toSet()
        val boundedRelations = relations.filter { it.fromNodeId in nodeIds && it.toNodeId in nodeIds }
            .sortedByDescending(GlobalEntityRelation::lastSeenAtMillis)
            .take(MAX_RELATIONS)
        working = working.copy(
            nodes = boundedNodes,
            relations = boundedRelations,
            processedEventIds = processed,
            retractedEventIds = retracted,
            updatedAtMillis = maxOf(working.updatedAtMillis, event.timestampMillis)
        )
        return working
    }

    private fun upsertNode(
        nodes: MutableList<GlobalEntityNode>,
        rawLabel: String,
        kind: GlobalEntityNodeKind,
        evidence: GlobalEvidenceRef,
        event: GlobalConversationEvent
    ): GlobalEntityNode {
        val label = cleanLabel(rawLabel)
        val stableKey = GlobalAgentText.stableKey("entity", kind.name, label)
        val index = nodes.indexOfFirst { it.stableKey == stableKey || aliasesMatch(it, label) }
        val temporal = temporalState(event.content)
        if (index >= 0) {
            val current = nodes[index]
            val mergedEvidence = (current.evidence + evidence).distinctBy(GlobalEvidenceRef::eventId).takeLast(MAX_EVIDENCE)
            return current.copy(
                label = if (label.length in 2 until current.label.length) label else current.label,
                aliases = (current.aliases + label).take(MAX_ALIASES).toSet(),
                temporalState = if (event.timestampMillis >= current.lastSeenAtMillis) temporal else current.temporalState,
                confidence = (maxOf(current.confidence, 0.68) + 0.02).coerceAtMost(0.98),
                evidence = mergedEvidence,
                lastSeenAtMillis = maxOf(current.lastSeenAtMillis, event.timestampMillis)
            ).also { nodes[index] = it }
        }
        return GlobalEntityNode(
            id = stableKey,
            stableKey = stableKey,
            label = label,
            kind = kind,
            aliases = setOf(label),
            temporalState = temporal,
            confidence = 0.68,
            evidence = listOf(evidence),
            firstSeenAtMillis = event.timestampMillis,
            lastSeenAtMillis = event.timestampMillis
        ).also(nodes::add)
    }

    private fun upsertRelation(
        relations: MutableList<GlobalEntityRelation>,
        from: String,
        to: String,
        kind: GlobalEntityRelationKind,
        evidence: GlobalEvidenceRef,
        nowMillis: Long,
        confidence: Double
    ) {
        if (from == to) return
        val id = GlobalAgentText.stableKey("entity-relation", from, kind.name, to)
        val index = relations.indexOfFirst { it.id == id && it.temporalState != GlobalMemoryTemporalState.DEPRECATED }
        if (index >= 0) {
            val current = relations[index]
            relations[index] = current.copy(
                confidence = (maxOf(current.confidence, confidence) + 0.02).coerceAtMost(0.99),
                evidence = (current.evidence + evidence).distinctBy(GlobalEvidenceRef::eventId).takeLast(MAX_EVIDENCE),
                lastSeenAtMillis = maxOf(current.lastSeenAtMillis, nowMillis)
            )
        } else {
            relations += GlobalEntityRelation(
                id = id,
                fromNodeId = from,
                toNodeId = to,
                kind = kind,
                temporalState = if (kind == GlobalEntityRelationKind.REMOVED) {
                    GlobalMemoryTemporalState.DEPRECATED
                } else GlobalMemoryTemporalState.CURRENT,
                confidence = confidence,
                evidence = listOf(evidence),
                validFromMillis = nowMillis,
                lastSeenAtMillis = nowMillis
            )
        }
    }

    private fun closeSupersededRelations(
        relations: MutableList<GlobalEntityRelation>,
        fromNodeId: String,
        incomingKind: GlobalEntityRelationKind,
        nowMillis: Long
    ) {
        if (incomingKind !in setOf(
                GlobalEntityRelationKind.NAMED_AS,
                GlobalEntityRelationKind.HAS_STATE,
                GlobalEntityRelationKind.REMOVED
            )
        ) return
        relations.indices.forEach { index ->
            val current = relations[index]
            if (current.fromNodeId == fromNodeId && current.kind in setOf(
                    GlobalEntityRelationKind.NAMED_AS,
                    GlobalEntityRelationKind.HAS_STATE
                ) && current.temporalState == GlobalMemoryTemporalState.CURRENT
            ) {
                relations[index] = current.copy(
                    temporalState = GlobalMemoryTemporalState.DEPRECATED,
                    validUntilMillis = nowMillis,
                    lastSeenAtMillis = maxOf(current.lastSeenAtMillis, nowMillis)
                )
            }
        }
    }

    private fun explicitTriples(content: String): List<ExplicitTriple> {
        val clean = content.replace(Regex("\\s+"), " ").trim().take(2_400)
        if (clean.isBlank() || AgentLearningAnalyzer.containsSensitiveData(clean)) return emptyList()
        val triples = mutableListOf<ExplicitTriple>()
        RELATION_PATTERNS.forEach { pattern ->
            pattern.regex.findAll(clean).take(4).forEach { match ->
                val from = cleanLabel(match.groupValues.getOrNull(1).orEmpty())
                val to = cleanLabel(
                    match.groupValues.getOrNull(2).orEmpty().ifBlank {
                        if (pattern.kind == GlobalEntityRelationKind.REMOVED) "removed" else ""
                    }
                )
                if (from.length >= 2 && to.length >= 1) triples += ExplicitTriple(from, pattern.kind, to, true)
            }
        }
        return triples.distinctBy { "${GlobalAgentText.normalize(it.from)}:${it.kind}:${GlobalAgentText.normalize(it.to)}" }
    }

    private fun classify(label: String, project: String): GlobalEntityNodeKind {
        val lower = label.lowercase(Locale.ROOT)
        return when {
            project.isNotBlank() && GlobalAgentText.normalize(label) == GlobalAgentText.normalize(project) -> GlobalEntityNodeKind.PROJECT
            lower in setOf("user", "me", "owner", "\u7528\u6237", "\u6211") -> GlobalEntityNodeKind.USER
            lower.containsAny("phone", "device", "android", "iphone", "\u624b\u673a", "\u8bbe\u5907") -> GlobalEntityNodeKind.DEVICE
            lower.containsAny("signalasi", "agent", "codex", "hermes", "claude code", "openclaw", "\u667a\u80fd\u4f53") -> GlobalEntityNodeKind.AGENT
            lower.containsAny("model", "llm", "gpt", "gemma", "qwen", "deepseek", "\u6a21\u578b") -> GlobalEntityNodeKind.MODEL
            lower.containsAny("setting", "settings", "page", "menu", "\u8bbe\u7f6e", "\u9875", "\u83dc\u5355") -> GlobalEntityNodeKind.SETTING
            lower.containsAny("tool", "mcp", "skill", "api", "runtime", "\u5de5\u5177", "\u6280\u80fd") -> GlobalEntityNodeKind.TOOL
            lower.containsAny("feature", "tts", "asr", "ocr", "linux", "\u529f\u80fd") -> GlobalEntityNodeKind.FEATURE
            else -> GlobalEntityNodeKind.CONCEPT
        }
    }

    private fun temporalState(content: String): GlobalMemoryTemporalState = when {
        GlobalMemoryEvolutionPolicy.replacementSignal(content) -> GlobalMemoryTemporalState.CURRENT
        content.lowercase(Locale.ROOT).containsAny("planned", "plan to", "\u8ba1\u5212", "\u5c06\u8981") -> GlobalMemoryTemporalState.PLANNED
        content.lowercase(Locale.ROOT).containsAny("previously", "formerly", "\u4e4b\u524d", "\u66fe\u7ecf") -> GlobalMemoryTemporalState.HISTORICAL
        else -> GlobalMemoryTemporalState.CURRENT
    }

    private fun retract(
        graph: GlobalEntityMemoryGraph,
        eventIds: Set<String>,
        nowMillis: Long
    ): GlobalEntityMemoryGraph {
        if (eventIds.isEmpty()) return graph
        val nodes = graph.nodes.mapNotNull { node ->
            val evidence = node.evidence.filterNot { it.invalidatedBy(eventIds) }
            if (node.evidence.isNotEmpty() && evidence.isEmpty()) null else node.copy(evidence = evidence)
        }
        val nodeIds = nodes.map(GlobalEntityNode::id).toSet()
        val relations = graph.relations.mapNotNull { relation ->
            if (relation.fromNodeId !in nodeIds || relation.toNodeId !in nodeIds) return@mapNotNull null
            val evidence = relation.evidence.filterNot { it.invalidatedBy(eventIds) }
            if (relation.evidence.isNotEmpty() && evidence.isEmpty()) null else relation.copy(evidence = evidence)
        }
        return graph.copy(nodes = nodes, relations = relations, updatedAtMillis = maxOf(graph.updatedAtMillis, nowMillis))
    }

    private fun aliasesMatch(node: GlobalEntityNode, label: String): Boolean {
        val normalized = GlobalAgentText.normalize(label)
        return GlobalAgentText.normalize(node.label) == normalized || node.aliases.any {
            GlobalAgentText.normalize(it) == normalized
        }
    }

    private fun cleanLabel(value: String): String = value
        .replace(Regex("[\\r\\n]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.', ',', ':', ';', '\u3002', '\uff0c', '\uff1a', '\uff1b')
        .take(160)

    private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)

    private data class ExplicitTriple(
        val from: String,
        val kind: GlobalEntityRelationKind,
        val to: String,
        val explicit: Boolean
    )

    private data class RelationPattern(val regex: Regex, val kind: GlobalEntityRelationKind)

    private val RELATION_PATTERNS = listOf(
        RelationPattern(Regex("(?i)([^.!?;]{2,80}?)\\s+(?:owns|has)\\s+([^.!?;]{1,120})"), GlobalEntityRelationKind.OWNS),
        RelationPattern(Regex("(?i)([^.!?;]{2,80}?)\\s+(?:uses|use)\\s+([^.!?;]{1,120})"), GlobalEntityRelationKind.USES),
        RelationPattern(Regex("(?i)([^.!?;]{2,80}?)\\s+supports\\s+([^.!?;]{1,120})"), GlobalEntityRelationKind.SUPPORTS),
        RelationPattern(Regex("(?i)([^.!?;]{2,80}?)\\s+(?:renamed to|changed to)\\s+([^.!?;]{1,120})"), GlobalEntityRelationKind.NAMED_AS),
        RelationPattern(Regex("(?i)([^.!?;]{2,80}?)\\s+(?:depends on|requires)\\s+([^.!?;]{1,120})"), GlobalEntityRelationKind.DEPENDS_ON),
        RelationPattern(Regex("(?i)([^.!?;]{2,80}?)\\s+(?:has been removed|was removed|is removed|removed)\\b"), GlobalEntityRelationKind.REMOVED),
        RelationPattern(Regex("([^\u3002\uff01\uff1f\uff1b]{2,80}?)\\s*(?:\u62e5\u6709|\u5305\u542b)\\s*([^\u3002\uff01\uff1f\uff1b]{1,120})"), GlobalEntityRelationKind.OWNS),
        RelationPattern(Regex("([^\u3002\uff01\uff1f\uff1b]{2,80}?)\\s*\u4f7f\u7528\\s*([^\u3002\uff01\uff1f\uff1b]{1,120})"), GlobalEntityRelationKind.USES),
        RelationPattern(Regex("([^\u3002\uff01\uff1f\uff1b]{2,80}?)\\s*\u652f\u6301\\s*([^\u3002\uff01\uff1f\uff1b]{1,120})"), GlobalEntityRelationKind.SUPPORTS),
        RelationPattern(Regex("([^\u3002\uff01\uff1f\uff1b]{2,80}?)\\s*(?:\u6539\u6210|\u66f4\u540d\u4e3a)\\s*([^\u3002\uff01\uff1f\uff1b]{1,120})"), GlobalEntityRelationKind.NAMED_AS),
        RelationPattern(Regex("([^\u3002\uff01\uff1f\uff1b]{2,80}?)\\s*(?:\u4f9d\u8d56|\u9700\u8981)\\s*([^\u3002\uff01\uff1f\uff1b]{1,120})"), GlobalEntityRelationKind.DEPENDS_ON),
        RelationPattern(Regex("([^\u3002\uff01\uff1f\uff1b]{2,80}?)\\s*(?:\u5df2\u79fb\u9664|\u5df2\u5220\u9664|\u53bb\u6389)"), GlobalEntityRelationKind.REMOVED)
    )

    private const val MAX_NODES_PER_EVENT = 32
    private const val MAX_RELATIONS_PER_EVENT = 24
    private const val MAX_ALIASES = 12
    private const val MAX_EVIDENCE = 30
    private const val MAX_NODES = 2_000
    private const val MAX_RELATIONS = 8_000
    private const val MAX_PROCESSED_EVENTS = 4_000
}

class GlobalEntityMemoryGraphStore(context: Context) {
    private val database = AgentEncryptedDatabase(context.applicationContext, GlobalAgentRepository.DATABASE_NAME)

    fun load(): GlobalEntityMemoryGraph = synchronized(STORE_LOCK) {
        GlobalEntityMemoryGraphCodec.decode(database.readString(KEY_GRAPH, ""))
    }

    fun save(graph: GlobalEntityMemoryGraph) = synchronized(STORE_LOCK) {
        database.writeString(KEY_GRAPH, GlobalEntityMemoryGraphCodec.encode(graph).toString())
    }

    fun export(): JSONObject = synchronized(STORE_LOCK) { GlobalEntityMemoryGraphCodec.encode(load()) }

    fun restore(payload: JSONObject) = synchronized(STORE_LOCK) { save(GlobalEntityMemoryGraphCodec.decode(payload.toString())) }

    private companion object {
        const val KEY_GRAPH = "entity_memory_graph"
        val STORE_LOCK = Any()
    }
}

private object GlobalEntityMemoryGraphCodec {
    fun encode(graph: GlobalEntityMemoryGraph): JSONObject = JSONObject()
        .put("nodes", JSONArray().apply { graph.nodes.forEach { put(encodeNode(it)) } })
        .put("relations", JSONArray().apply { graph.relations.forEach { put(encodeRelation(it)) } })
        .put("processed_event_ids", JSONArray(graph.processedEventIds))
        .put("retracted_event_ids", JSONArray(graph.retractedEventIds))
        .put("updated_at_millis", graph.updatedAtMillis)

    fun decode(raw: String): GlobalEntityMemoryGraph = runCatching {
        if (raw.isBlank()) return@runCatching GlobalEntityMemoryGraph()
        val root = JSONObject(raw)
        GlobalEntityMemoryGraph(
            nodes = objects(root.optJSONArray("nodes")).mapNotNull(::decodeNode).take(2_000),
            relations = objects(root.optJSONArray("relations")).mapNotNull(::decodeRelation).take(8_000),
            processedEventIds = strings(root.optJSONArray("processed_event_ids")).takeLast(4_000),
            retractedEventIds = strings(root.optJSONArray("retracted_event_ids")).takeLast(4_000),
            updatedAtMillis = root.optLong("updated_at_millis")
        )
    }.getOrDefault(GlobalEntityMemoryGraph())

    private fun encodeNode(node: GlobalEntityNode): JSONObject = JSONObject()
        .put("id", node.id)
        .put("stable_key", node.stableKey)
        .put("label", node.label)
        .put("kind", node.kind.name)
        .put("aliases", JSONArray(node.aliases.toList()))
        .put("temporal_state", node.temporalState.name)
        .put("confidence", node.confidence)
        .put("evidence", JSONArray().apply { node.evidence.forEach { put(encodeEvidence(it)) } })
        .put("first_seen_at_millis", node.firstSeenAtMillis)
        .put("last_seen_at_millis", node.lastSeenAtMillis)

    private fun decodeNode(json: JSONObject): GlobalEntityNode? {
        val id = json.optString("id")
        val label = json.optString("label").take(160)
        if (id.isBlank() || label.isBlank()) return null
        return GlobalEntityNode(
            id = id,
            stableKey = json.optString("stable_key").ifBlank { id },
            label = label,
            kind = enumValue(json.optString("kind"), GlobalEntityNodeKind.CONCEPT),
            aliases = strings(json.optJSONArray("aliases")).take(12).toSet(),
            temporalState = enumValue(json.optString("temporal_state"), GlobalMemoryTemporalState.CURRENT),
            confidence = json.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
            evidence = objects(json.optJSONArray("evidence")).mapNotNull(::decodeEvidence).takeLast(30),
            firstSeenAtMillis = json.optLong("first_seen_at_millis"),
            lastSeenAtMillis = json.optLong("last_seen_at_millis")
        )
    }

    private fun encodeRelation(relation: GlobalEntityRelation): JSONObject = JSONObject()
        .put("id", relation.id)
        .put("from_node_id", relation.fromNodeId)
        .put("to_node_id", relation.toNodeId)
        .put("kind", relation.kind.name)
        .put("temporal_state", relation.temporalState.name)
        .put("confidence", relation.confidence)
        .put("evidence", JSONArray().apply { relation.evidence.forEach { put(encodeEvidence(it)) } })
        .put("valid_from_millis", relation.validFromMillis)
        .put("valid_until_millis", relation.validUntilMillis)
        .put("last_seen_at_millis", relation.lastSeenAtMillis)

    private fun decodeRelation(json: JSONObject): GlobalEntityRelation? {
        val id = json.optString("id")
        val from = json.optString("from_node_id")
        val to = json.optString("to_node_id")
        if (id.isBlank() || from.isBlank() || to.isBlank() || from == to) return null
        return GlobalEntityRelation(
            id = id,
            fromNodeId = from,
            toNodeId = to,
            kind = enumValue(json.optString("kind"), GlobalEntityRelationKind.RELATED_TO),
            temporalState = enumValue(json.optString("temporal_state"), GlobalMemoryTemporalState.CURRENT),
            confidence = json.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
            evidence = objects(json.optJSONArray("evidence")).mapNotNull(::decodeEvidence).takeLast(30),
            validFromMillis = json.optLong("valid_from_millis"),
            validUntilMillis = json.optLong("valid_until_millis"),
            lastSeenAtMillis = json.optLong("last_seen_at_millis")
        )
    }

    private fun encodeEvidence(evidence: GlobalEvidenceRef): JSONObject = JSONObject()
        .put("event_id", evidence.eventId)
        .put("causal_event_ids", JSONArray(evidence.causalEventIds.toList()))
        .put("conversation_id", evidence.conversationId)
        .put("timestamp_millis", evidence.timestampMillis)

    private fun decodeEvidence(json: JSONObject): GlobalEvidenceRef? {
        val id = json.optString("event_id")
        if (id.isBlank()) return null
        return GlobalEvidenceRef(
            eventId = id,
            causalEventIds = strings(json.optJSONArray("causal_event_ids")).toSet().ifEmpty { setOf(id) },
            conversationId = json.optString("conversation_id"),
            timestampMillis = json.optLong("timestamp_millis")
        )
    }

    private fun objects(array: JSONArray?): List<JSONObject> {
        if (array == null) return emptyList()
        return buildList { for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add) }
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList { for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add) }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
