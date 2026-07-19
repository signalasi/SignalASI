package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class GlobalTopicNodeKind {
    TOPIC,
    PROJECT
}

enum class GlobalTopicNodeStatus {
    ACTIVE,
    ARCHIVED
}

enum class GlobalTopicRelationKind {
    CONTAINS,
    RELATED_TO,
    SUPPORTS,
    CONFLICTS_WITH
}

data class GlobalTopicNode(
    val id: String = UUID.randomUUID().toString(),
    val stableKey: String,
    val name: String,
    val kind: GlobalTopicNodeKind = GlobalTopicNodeKind.TOPIC,
    val status: GlobalTopicNodeStatus = GlobalTopicNodeStatus.ACTIVE,
    val conversationIds: Set<String> = emptySet(),
    val entityKeys: Set<String> = emptySet(),
    val worldItemIds: Set<String> = emptySet(),
    val evidenceEventIds: List<String> = emptyList(),
    val evidenceProvenance: List<GlobalEvidenceRef> = emptyList(),
    val confidence: Double = 0.5,
    val firstSeenAtMillis: Long = System.currentTimeMillis(),
    val lastSeenAtMillis: Long = System.currentTimeMillis()
)

data class GlobalTopicRelation(
    val id: String = UUID.randomUUID().toString(),
    val fromNodeId: String,
    val toNodeId: String,
    val kind: GlobalTopicRelationKind,
    val strength: Double = 0.5,
    val evidenceEventIds: List<String> = emptyList(),
    val evidenceProvenance: List<GlobalEvidenceRef> = emptyList(),
    val firstSeenAtMillis: Long = System.currentTimeMillis(),
    val lastSeenAtMillis: Long = System.currentTimeMillis()
)

data class GlobalTopicProjectGraph(
    val nodes: List<GlobalTopicNode> = emptyList(),
    val relations: List<GlobalTopicRelation> = emptyList(),
    val retractedEventIds: List<String> = emptyList(),
    val updatedAtMillis: Long = 0L
) {
    fun activeNodes(): List<GlobalTopicNode> = nodes.filter { it.status == GlobalTopicNodeStatus.ACTIVE }

    fun relevant(
        query: String,
        conversationId: String,
        limit: Int = 8
    ): List<GlobalTopicNode> {
        val queryTokens = GlobalAgentText.tokens(query)
        return activeNodes().asSequence()
            .map { node ->
                val overlap = GlobalAgentText.overlap(queryTokens, GlobalAgentText.tokens(node.name))
                val conversationBoost = if (conversationId in node.conversationIds) 0.42 else 0.0
                val projectBoost = if (node.kind == GlobalTopicNodeKind.PROJECT) 0.10 else 0.0
                node to (overlap + conversationBoost + projectBoost + node.confidence * 0.12)
            }
            .filter { (_, score) -> score >= 0.18 }
            .sortedWith(compareByDescending<Pair<GlobalTopicNode, Double>> { it.second }
                .thenByDescending { it.first.lastSeenAtMillis })
            .map(Pair<GlobalTopicNode, Double>::first)
            .take(limit.coerceIn(1, 20))
            .toList()
    }
}

object GlobalTopicProjectGraphReducer {
    fun reduce(
        graph: GlobalTopicProjectGraph,
        event: GlobalConversationEvent,
        understanding: GlobalUnderstanding,
        reduction: GlobalWorldReduction
    ): GlobalTopicProjectGraph {
        val incomingRetractions = event.effectiveRetractions()
        val retractedEventIds = (graph.retractedEventIds + incomingRetractions)
            .filter(String::isNotBlank)
            .distinct()
            .takeLast(MAX_RETRACTED_EVENT_IDS)
        val retractedGraph = retractEvidence(graph, incomingRetractions, event.timestampMillis).copy(
            retractedEventIds = retractedEventIds
        )
        if (event.evidenceRoots().any(retractedEventIds::contains)) return retractedGraph
        if (event.excludesConversationFromGlobalModel()) {
            return removeConversation(retractedGraph, event.conversationId, event.timestampMillis)
        }
        if (event.type == GlobalConversationEventType.MESSAGE_DELETED ||
            event.type == GlobalConversationEventType.MEMORY_DELETED ||
            event.type == GlobalConversationEventType.KNOWLEDGE_DELETED ||
            event.metadata["projection"] == "retract_only"
        ) return retractedGraph
        if (event.type in setOf(
                GlobalConversationEventType.CONVERSATION_CREATED,
                GlobalConversationEventType.CONVERSATION_UPDATED
            ) && (
                event.conversationTitle.isBlank() ||
                    event.conversationTitle.equals("New session", ignoreCase = true)
                )
        ) return retractedGraph
        val topicName = cleanName(understanding.topic)
        if (topicName.isBlank()) return retractedGraph
        val now = event.timestampMillis
        val nodes = retractedGraph.nodes.toMutableList()
        val topicIndex = matchingNodeIndex(nodes, topicName)
        val inferredKind = if (
            understanding.durableFollowUpUseful &&
            (understanding.crossConversationIds.isNotEmpty() ||
                understanding.goalCandidates.size + understanding.taskCandidates.size >= 2)
        ) GlobalTopicNodeKind.PROJECT else GlobalTopicNodeKind.TOPIC
        val conversationIds = (setOf(event.conversationId) + understanding.crossConversationIds)
            .filter(String::isNotBlank)
            .take(MAX_CONVERSATIONS_PER_NODE)
            .toSet()
        val entityKeys = understanding.entities
            .map(GlobalAgentText::normalize)
            .filter(String::isNotBlank)
            .take(MAX_ENTITIES_PER_NODE)
            .toSet()
        val changedWorldIds = reduction.changedItems.map(GlobalWorldItem::id).toSet()
        val topicNode = if (topicIndex >= 0) {
            val existing = nodes[topicIndex]
            val evidence = appendNodeEvidence(existing, event)
            existing.copy(
                name = preferName(existing.name, topicName),
                kind = if (existing.kind == GlobalTopicNodeKind.PROJECT) existing.kind else inferredKind,
                status = GlobalTopicNodeStatus.ACTIVE,
                conversationIds = (existing.conversationIds + conversationIds)
                    .take(MAX_CONVERSATIONS_PER_NODE).toSet(),
                entityKeys = (existing.entityKeys + entityKeys).take(MAX_ENTITIES_PER_NODE).toSet(),
                worldItemIds = (existing.worldItemIds + changedWorldIds).take(MAX_WORLD_ITEMS_PER_NODE).toSet(),
                evidenceEventIds = evidence.map(GlobalEvidenceRef::eventId),
                evidenceProvenance = evidence,
                confidence = (maxOf(existing.confidence, baseConfidence(understanding)) + 0.02).coerceAtMost(0.98),
                lastSeenAtMillis = now
            ).also { nodes[topicIndex] = it }
        } else {
            GlobalTopicNode(
                stableKey = GlobalAgentText.stableKey("topic-project-node", topicName),
                name = topicName,
                kind = inferredKind,
                conversationIds = conversationIds,
                entityKeys = entityKeys,
                worldItemIds = changedWorldIds.take(MAX_WORLD_ITEMS_PER_NODE).toSet(),
                evidenceEventIds = listOf(event.id),
                evidenceProvenance = listOf(event.evidenceRef()),
                confidence = baseConfidence(understanding),
                firstSeenAtMillis = now,
                lastSeenAtMillis = now
            ).also(nodes::add)
        }

        val relations = retractedGraph.relations.toMutableList()
        nodes.asSequence()
            .filter { it.id != topicNode.id && it.status == GlobalTopicNodeStatus.ACTIVE }
            .map { candidate -> candidate to relationScore(topicNode, candidate, understanding) }
            .filter { (_, score) -> score >= MIN_RELATION_SCORE }
            .sortedByDescending(Pair<GlobalTopicNode, Double>::second)
            .take(MAX_RELATIONS_PER_EVENT)
            .forEach { (candidate, score) ->
                val conflict = reduction.conflicts.any { (left, right) ->
                    listOf(left.topic, right.topic).any {
                        GlobalAgentText.overlap(GlobalAgentText.tokens(it), GlobalAgentText.tokens(candidate.name)) >= 0.45
                    }
                }
                val relationKind = when {
                    conflict -> GlobalTopicRelationKind.CONFLICTS_WITH
                    topicNode.kind == GlobalTopicNodeKind.PROJECT && candidate.kind == GlobalTopicNodeKind.TOPIC ->
                        GlobalTopicRelationKind.CONTAINS
                    topicNode.kind == GlobalTopicNodeKind.TOPIC && candidate.kind == GlobalTopicNodeKind.PROJECT ->
                        GlobalTopicRelationKind.CONTAINS
                    understanding.goalCandidates.isNotEmpty() && candidate.entityKeys.intersect(topicNode.entityKeys).isNotEmpty() ->
                        GlobalTopicRelationKind.SUPPORTS
                    else -> GlobalTopicRelationKind.RELATED_TO
                }
                val from = if (relationKind == GlobalTopicRelationKind.CONTAINS &&
                    candidate.kind == GlobalTopicNodeKind.PROJECT
                ) candidate.id else topicNode.id
                val to = if (from == topicNode.id) candidate.id else topicNode.id
                upsertRelation(relations, from, to, relationKind, score, event.evidenceRef(), now)
            }

        understanding.relatedTopics.asSequence()
            .map(::cleanName)
            .filter(String::isNotBlank)
            .filter { GlobalAgentText.normalize(it) != GlobalAgentText.normalize(topicNode.name) }
            .distinctBy(GlobalAgentText::normalize)
            .take(MAX_INFERRED_TOPICS_PER_EVENT)
            .forEach { relatedName ->
                val related = upsertRelatedTopic(nodes, relatedName, event, now)
                upsertRelation(
                    relations,
                    topicNode.id,
                    related.id,
                    GlobalTopicRelationKind.RELATED_TO,
                    0.68,
                    event.evidenceRef(),
                    now
                )
            }

        val explicitProject = cleanName(understanding.project.ifBlank { event.metadata["project"].orEmpty() })
        if (explicitProject.isNotBlank() &&
            GlobalAgentText.normalize(explicitProject) != GlobalAgentText.normalize(topicNode.name)
        ) {
            val project = upsertExplicitProject(nodes, explicitProject, topicNode, event, now)
            upsertRelation(
                relations,
                project.id,
                topicNode.id,
                GlobalTopicRelationKind.CONTAINS,
                0.92,
                event.evidenceRef(),
                now
            )
        }
        val activeWorldItemIds = reduction.world.items.map(GlobalWorldItem::id).toSet()
        val boundedNodes = nodes.map { node ->
            node.copy(worldItemIds = node.worldItemIds.intersect(activeWorldItemIds))
        }.sortedByDescending(GlobalTopicNode::lastSeenAtMillis).take(MAX_NODES)
        val boundedNodeIds = boundedNodes.map(GlobalTopicNode::id).toSet()
        return GlobalTopicProjectGraph(
            nodes = boundedNodes,
            relations = relations
                .filter { relation ->
                    relation.fromNodeId in boundedNodeIds && relation.toNodeId in boundedNodeIds
                }
                .sortedByDescending(GlobalTopicRelation::lastSeenAtMillis)
                .take(MAX_RELATIONS),
            retractedEventIds = retractedGraph.retractedEventIds,
            updatedAtMillis = now
        )
    }

    private fun removeConversation(
        graph: GlobalTopicProjectGraph,
        conversationId: String,
        nowMillis: Long
    ): GlobalTopicProjectGraph {
        val nodes = graph.nodes.mapNotNull { node ->
            if (conversationId !in node.conversationIds) return@mapNotNull node
            val remaining = node.conversationIds - conversationId
            if (remaining.isEmpty() && node.evidenceEventIds.size <= 1) null else node.copy(
                conversationIds = remaining,
                lastSeenAtMillis = nowMillis
            )
        }
        val ids = nodes.map(GlobalTopicNode::id).toSet()
        return graph.copy(
            nodes = nodes,
            relations = graph.relations.filter { it.fromNodeId in ids && it.toNodeId in ids },
            updatedAtMillis = nowMillis
        )
    }

    private fun matchingNodeIndex(nodes: List<GlobalTopicNode>, name: String): Int {
        val normalized = GlobalAgentText.normalize(name)
        val exact = nodes.indexOfFirst { GlobalAgentText.normalize(it.name) == normalized }
        if (exact >= 0) return exact
        val tokens = GlobalAgentText.tokens(name)
        return nodes.indices.maxByOrNull { index ->
            GlobalAgentText.overlap(tokens, GlobalAgentText.tokens(nodes[index].name))
        }?.takeIf { index ->
            GlobalAgentText.overlap(tokens, GlobalAgentText.tokens(nodes[index].name)) >= 0.84
        } ?: -1
    }

    private fun relationScore(
        current: GlobalTopicNode,
        candidate: GlobalTopicNode,
        understanding: GlobalUnderstanding
    ): Double {
        val topicOverlap = GlobalAgentText.overlap(
            GlobalAgentText.tokens(current.name),
            GlobalAgentText.tokens(candidate.name)
        )
        val entityOverlap = if (current.entityKeys.isEmpty() || candidate.entityKeys.isEmpty()) 0.0 else {
            current.entityKeys.intersect(candidate.entityKeys).size.toDouble() /
                minOf(current.entityKeys.size, candidate.entityKeys.size).coerceAtLeast(1)
        }
        val explicitConversation = candidate.conversationIds.any { it in understanding.crossConversationIds }
        return (topicOverlap * 0.52 + entityOverlap * 0.30 + if (explicitConversation) 0.42 else 0.0)
            .coerceIn(0.0, 1.0)
    }

    private fun upsertRelation(
        relations: MutableList<GlobalTopicRelation>,
        fromNodeId: String,
        toNodeId: String,
        kind: GlobalTopicRelationKind,
        strength: Double,
        evidenceRef: GlobalEvidenceRef,
        nowMillis: Long
    ) {
        if (fromNodeId == toNodeId) return
        val directional = kind == GlobalTopicRelationKind.CONTAINS || kind == GlobalTopicRelationKind.SUPPORTS
        val pair = if (directional) listOf(fromNodeId, toNodeId) else listOf(fromNodeId, toNodeId).sorted()
        val index = relations.indexOfFirst {
            it.kind == kind && it.fromNodeId == pair[0] && it.toNodeId == pair[1]
        }
        if (index >= 0) {
            val existing = relations[index]
            val evidence = (relationEvidence(existing) + evidenceRef)
                .distinctBy(GlobalEvidenceRef::eventId)
                .takeLast(MAX_EVIDENCE_PER_RELATION)
            relations[index] = existing.copy(
                strength = (maxOf(existing.strength, strength) + 0.03).coerceAtMost(1.0),
                evidenceEventIds = evidence.map(GlobalEvidenceRef::eventId),
                evidenceProvenance = evidence,
                lastSeenAtMillis = nowMillis
            )
        } else {
            relations += GlobalTopicRelation(
                id = GlobalAgentText.stableKey("topic-relation", kind.name, pair[0], pair[1]),
                fromNodeId = pair[0],
                toNodeId = pair[1],
                kind = kind,
                strength = strength,
                evidenceEventIds = listOf(evidenceRef.eventId),
                evidenceProvenance = listOf(evidenceRef),
                firstSeenAtMillis = nowMillis,
                lastSeenAtMillis = nowMillis
            )
        }
    }

    private fun upsertExplicitProject(
        nodes: MutableList<GlobalTopicNode>,
        name: String,
        topicNode: GlobalTopicNode,
        event: GlobalConversationEvent,
        nowMillis: Long
    ): GlobalTopicNode {
        val index = matchingNodeIndex(nodes, name)
        if (index >= 0) {
            val existing = nodes[index]
            val evidence = appendNodeEvidence(existing, event)
            return existing.copy(
                name = preferName(existing.name, name),
                kind = GlobalTopicNodeKind.PROJECT,
                conversationIds = (existing.conversationIds + topicNode.conversationIds).take(MAX_CONVERSATIONS_PER_NODE).toSet(),
                entityKeys = (existing.entityKeys + topicNode.entityKeys).take(MAX_ENTITIES_PER_NODE).toSet(),
                evidenceEventIds = evidence.map(GlobalEvidenceRef::eventId),
                evidenceProvenance = evidence,
                confidence = maxOf(existing.confidence, 0.88),
                lastSeenAtMillis = nowMillis
            ).also { nodes[index] = it }
        }
        return GlobalTopicNode(
            stableKey = GlobalAgentText.stableKey("project-node", name),
            name = name,
            kind = GlobalTopicNodeKind.PROJECT,
            conversationIds = topicNode.conversationIds,
            entityKeys = topicNode.entityKeys,
            evidenceEventIds = listOf(event.id),
            evidenceProvenance = listOf(event.evidenceRef()),
            confidence = 0.88,
            firstSeenAtMillis = nowMillis,
            lastSeenAtMillis = nowMillis
        ).also(nodes::add)
    }

    private fun upsertRelatedTopic(
        nodes: MutableList<GlobalTopicNode>,
        name: String,
        event: GlobalConversationEvent,
        nowMillis: Long
    ): GlobalTopicNode {
        val index = matchingNodeIndex(nodes, name)
        if (index >= 0) {
            val existing = nodes[index]
            val evidence = appendNodeEvidence(existing, event)
            return existing.copy(
                evidenceEventIds = evidence.map(GlobalEvidenceRef::eventId),
                evidenceProvenance = evidence,
                confidence = (existing.confidence + 0.02).coerceAtMost(0.94),
                lastSeenAtMillis = nowMillis
            ).also { nodes[index] = it }
        }
        return GlobalTopicNode(
            stableKey = GlobalAgentText.stableKey("topic-project-node", name),
            name = name,
            kind = GlobalTopicNodeKind.TOPIC,
            evidenceEventIds = listOf(event.id),
            evidenceProvenance = listOf(event.evidenceRef()),
            confidence = 0.64,
            firstSeenAtMillis = nowMillis,
            lastSeenAtMillis = nowMillis
        ).also(nodes::add)
    }

    private fun appendNodeEvidence(
        node: GlobalTopicNode,
        event: GlobalConversationEvent
    ): List<GlobalEvidenceRef> = (nodeEvidence(node) + event.evidenceRef())
        .distinctBy(GlobalEvidenceRef::eventId)
        .takeLast(MAX_EVIDENCE_PER_NODE)

    private fun nodeEvidence(node: GlobalTopicNode): List<GlobalEvidenceRef> =
        node.evidenceProvenance.ifEmpty {
            node.evidenceEventIds.map { eventId ->
                GlobalEvidenceRef(
                    eventId = eventId,
                    causalEventIds = setOf(eventId),
                    conversationId = node.conversationIds.singleOrNull().orEmpty(),
                    timestampMillis = node.lastSeenAtMillis
                )
            }
        }

    private fun relationEvidence(relation: GlobalTopicRelation): List<GlobalEvidenceRef> =
        relation.evidenceProvenance.ifEmpty {
            relation.evidenceEventIds.map { eventId ->
                GlobalEvidenceRef(
                    eventId = eventId,
                    causalEventIds = setOf(eventId),
                    timestampMillis = relation.lastSeenAtMillis
                )
            }
        }

    private fun retractEvidence(
        graph: GlobalTopicProjectGraph,
        eventIds: Set<String>,
        nowMillis: Long
    ): GlobalTopicProjectGraph {
        if (eventIds.isEmpty()) return graph
        val nodes = graph.nodes.mapNotNull { node ->
            val evidence = nodeEvidence(node)
            if (evidence.isEmpty()) return@mapNotNull node
            val retained = evidence.filterNot { it.invalidatedBy(eventIds) }
            when {
                retained.size == evidence.size -> node
                retained.isEmpty() -> null
                else -> {
                    val conversations = retained.map(GlobalEvidenceRef::conversationId)
                        .filter(String::isNotBlank)
                        .toSet()
                    node.copy(
                        conversationIds = conversations.ifEmpty { node.conversationIds },
                        evidenceEventIds = retained.map(GlobalEvidenceRef::eventId),
                        evidenceProvenance = retained,
                        lastSeenAtMillis = retained.maxOfOrNull(GlobalEvidenceRef::timestampMillis)
                            ?.takeIf { it > 0L }
                            ?: node.lastSeenAtMillis
                    )
                }
            }
        }
        val nodeIds = nodes.map(GlobalTopicNode::id).toSet()
        val relations = graph.relations.mapNotNull { relation ->
            if (relation.fromNodeId !in nodeIds || relation.toNodeId !in nodeIds) return@mapNotNull null
            val evidence = relationEvidence(relation)
            if (evidence.isEmpty()) return@mapNotNull relation
            val retained = evidence.filterNot { it.invalidatedBy(eventIds) }
            when {
                retained.size == evidence.size -> relation
                retained.isEmpty() -> null
                else -> relation.copy(
                    evidenceEventIds = retained.map(GlobalEvidenceRef::eventId),
                    evidenceProvenance = retained,
                    lastSeenAtMillis = retained.maxOfOrNull(GlobalEvidenceRef::timestampMillis)
                        ?.takeIf { it > 0L }
                        ?: relation.lastSeenAtMillis
                )
            }
        }
        return graph.copy(nodes = nodes, relations = relations, updatedAtMillis = nowMillis)
    }

    private fun baseConfidence(understanding: GlobalUnderstanding): Double = (
        0.58 + understanding.complexity * 0.12 +
            if (understanding.crossConversationIds.isNotEmpty()) 0.12 else 0.0
        ).coerceIn(0.58, 0.90)

    private fun preferName(current: String, candidate: String): String = when {
        current.isBlank() -> candidate
        candidate.length in 4..120 && candidate.length < current.length -> candidate
        else -> current
    }

    private fun cleanName(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(160)

    private const val MIN_RELATION_SCORE = 0.34
    private const val MAX_RELATIONS_PER_EVENT = 8
    private const val MAX_INFERRED_TOPICS_PER_EVENT = 8
    private const val MAX_CONVERSATIONS_PER_NODE = 40
    private const val MAX_ENTITIES_PER_NODE = 40
    private const val MAX_WORLD_ITEMS_PER_NODE = 80
    private const val MAX_EVIDENCE_PER_NODE = 60
    private const val MAX_EVIDENCE_PER_RELATION = 40
    private const val MAX_NODES = 600
    private const val MAX_RELATIONS = 2_000
    private const val MAX_RETRACTED_EVENT_IDS = 4_000
}

class GlobalTopicProjectGraphStore(context: Context) {
    private val database = AgentEncryptedDatabase(
        context.applicationContext,
        GlobalAgentRepository.DATABASE_NAME
    )

    fun load(): GlobalTopicProjectGraph = synchronized(STORE_LOCK) {
        decode(database.readString(KEY_GRAPH, ""))
    }

    fun save(graph: GlobalTopicProjectGraph) = synchronized(STORE_LOCK) {
        database.writeString(KEY_GRAPH, encode(graph).toString())
    }

    fun export(): JSONObject = synchronized(STORE_LOCK) { encode(load()) }

    fun restore(payload: JSONObject) = synchronized(STORE_LOCK) { save(decode(payload.toString())) }

    private fun encode(graph: GlobalTopicProjectGraph): JSONObject = JSONObject()
        .put("nodes", JSONArray().apply { graph.nodes.forEach { put(encodeNode(it)) } })
        .put("relations", JSONArray().apply { graph.relations.forEach { put(encodeRelation(it)) } })
        .put("retracted_event_ids", JSONArray(graph.retractedEventIds))
        .put("updated_at_millis", graph.updatedAtMillis)

    private fun decode(raw: String): GlobalTopicProjectGraph = runCatching {
        if (raw.isBlank()) return@runCatching GlobalTopicProjectGraph()
        val root = JSONObject(raw)
        GlobalTopicProjectGraph(
            nodes = buildList {
                val array = root.optJSONArray("nodes") ?: JSONArray()
                for (index in 0 until array.length()) decodeNode(array.optJSONObject(index))?.let(::add)
            },
            relations = buildList {
                val array = root.optJSONArray("relations") ?: JSONArray()
                for (index in 0 until array.length()) decodeRelation(array.optJSONObject(index))?.let(::add)
            },
            retractedEventIds = strings(root.optJSONArray("retracted_event_ids")).takeLast(4_000),
            updatedAtMillis = root.optLong("updated_at_millis")
        )
    }.getOrDefault(GlobalTopicProjectGraph())

    private fun encodeNode(node: GlobalTopicNode): JSONObject = JSONObject()
        .put("id", node.id)
        .put("stable_key", node.stableKey)
        .put("name", node.name)
        .put("kind", node.kind.name)
        .put("status", node.status.name)
        .put("conversation_ids", JSONArray(node.conversationIds.toList()))
        .put("entity_keys", JSONArray(node.entityKeys.toList()))
        .put("world_item_ids", JSONArray(node.worldItemIds.toList()))
        .put("evidence_event_ids", JSONArray(node.evidenceEventIds))
        .put("evidence_provenance", JSONArray().apply {
            node.evidenceProvenance.forEach { put(encodeEvidenceRef(it)) }
        })
        .put("confidence", node.confidence)
        .put("first_seen_at_millis", node.firstSeenAtMillis)
        .put("last_seen_at_millis", node.lastSeenAtMillis)

    private fun decodeNode(json: JSONObject?): GlobalTopicNode? {
        if (json == null) return null
        val name = json.optString("name").take(160)
        if (name.isBlank()) return null
        return GlobalTopicNode(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            stableKey = json.optString("stable_key").ifBlank {
                GlobalAgentText.stableKey("topic-project-node", name)
            },
            name = name,
            kind = enumValue(json.optString("kind"), GlobalTopicNodeKind.TOPIC),
            status = enumValue(json.optString("status"), GlobalTopicNodeStatus.ACTIVE),
            conversationIds = strings(json.optJSONArray("conversation_ids")).take(40).toSet(),
            entityKeys = strings(json.optJSONArray("entity_keys")).take(40).toSet(),
            worldItemIds = strings(json.optJSONArray("world_item_ids")).take(80).toSet(),
            evidenceEventIds = strings(json.optJSONArray("evidence_event_ids")).takeLast(60),
            evidenceProvenance = decodeEvidenceRefs(json.optJSONArray("evidence_provenance")).takeLast(60),
            confidence = json.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
            firstSeenAtMillis = json.optLong("first_seen_at_millis"),
            lastSeenAtMillis = json.optLong("last_seen_at_millis")
        )
    }

    private fun encodeRelation(relation: GlobalTopicRelation): JSONObject = JSONObject()
        .put("id", relation.id)
        .put("from_node_id", relation.fromNodeId)
        .put("to_node_id", relation.toNodeId)
        .put("kind", relation.kind.name)
        .put("strength", relation.strength)
        .put("evidence_event_ids", JSONArray(relation.evidenceEventIds))
        .put("evidence_provenance", JSONArray().apply {
            relation.evidenceProvenance.forEach { put(encodeEvidenceRef(it)) }
        })
        .put("first_seen_at_millis", relation.firstSeenAtMillis)
        .put("last_seen_at_millis", relation.lastSeenAtMillis)

    private fun decodeRelation(json: JSONObject?): GlobalTopicRelation? {
        if (json == null) return null
        val from = json.optString("from_node_id")
        val to = json.optString("to_node_id")
        if (from.isBlank() || to.isBlank() || from == to) return null
        return GlobalTopicRelation(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            fromNodeId = from,
            toNodeId = to,
            kind = enumValue(json.optString("kind"), GlobalTopicRelationKind.RELATED_TO),
            strength = json.optDouble("strength", 0.5).coerceIn(0.0, 1.0),
            evidenceEventIds = strings(json.optJSONArray("evidence_event_ids")).takeLast(40),
            evidenceProvenance = decodeEvidenceRefs(json.optJSONArray("evidence_provenance")).takeLast(40),
            firstSeenAtMillis = json.optLong("first_seen_at_millis"),
            lastSeenAtMillis = json.optLong("last_seen_at_millis")
        )
    }

    private fun encodeEvidenceRef(evidence: GlobalEvidenceRef): JSONObject = JSONObject()
        .put("event_id", evidence.eventId)
        .put("causal_event_ids", JSONArray(evidence.causalEventIds.toList()))
        .put("conversation_id", evidence.conversationId)
        .put("timestamp_millis", evidence.timestampMillis)

    private fun decodeEvidenceRefs(array: JSONArray?): List<GlobalEvidenceRef> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val eventId = json.optString("event_id")
                if (eventId.isBlank()) continue
                add(GlobalEvidenceRef(
                    eventId = eventId,
                    causalEventIds = strings(json.optJSONArray("causal_event_ids")).toSet()
                        .ifEmpty { setOf(eventId) },
                    conversationId = json.optString("conversation_id"),
                    timestampMillis = json.optLong("timestamp_millis")
                ))
            }
        }
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private companion object {
        const val KEY_GRAPH = "topic_project_graph"
        val STORE_LOCK = Any()
    }
}
