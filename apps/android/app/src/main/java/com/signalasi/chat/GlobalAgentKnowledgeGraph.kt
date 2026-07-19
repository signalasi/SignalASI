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
    val firstSeenAtMillis: Long = System.currentTimeMillis(),
    val lastSeenAtMillis: Long = System.currentTimeMillis()
)

data class GlobalTopicProjectGraph(
    val nodes: List<GlobalTopicNode> = emptyList(),
    val relations: List<GlobalTopicRelation> = emptyList(),
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
        if (event.type == GlobalConversationEventType.CONVERSATION_DELETED) {
            return removeConversation(graph, event.conversationId, event.timestampMillis)
        }
        val topicName = cleanName(understanding.topic)
        if (topicName.isBlank()) return graph
        val now = event.timestampMillis
        val nodes = graph.nodes.toMutableList()
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
            existing.copy(
                name = preferName(existing.name, topicName),
                kind = if (existing.kind == GlobalTopicNodeKind.PROJECT) existing.kind else inferredKind,
                status = GlobalTopicNodeStatus.ACTIVE,
                conversationIds = (existing.conversationIds + conversationIds)
                    .take(MAX_CONVERSATIONS_PER_NODE).toSet(),
                entityKeys = (existing.entityKeys + entityKeys).take(MAX_ENTITIES_PER_NODE).toSet(),
                worldItemIds = (existing.worldItemIds + changedWorldIds).take(MAX_WORLD_ITEMS_PER_NODE).toSet(),
                evidenceEventIds = (existing.evidenceEventIds + event.id).distinct().takeLast(MAX_EVIDENCE_PER_NODE),
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
                confidence = baseConfidence(understanding),
                firstSeenAtMillis = now,
                lastSeenAtMillis = now
            ).also(nodes::add)
        }

        val relations = graph.relations.toMutableList()
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
                upsertRelation(relations, from, to, relationKind, score, event.id, now)
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
                    event.id,
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
                event.id,
                now
            )
        }
        return GlobalTopicProjectGraph(
            nodes = nodes.sortedByDescending(GlobalTopicNode::lastSeenAtMillis).take(MAX_NODES),
            relations = relations
                .filter { relation -> nodes.any { it.id == relation.fromNodeId } && nodes.any { it.id == relation.toNodeId } }
                .sortedByDescending(GlobalTopicRelation::lastSeenAtMillis)
                .take(MAX_RELATIONS),
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
        eventId: String,
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
            relations[index] = existing.copy(
                strength = (maxOf(existing.strength, strength) + 0.03).coerceAtMost(1.0),
                evidenceEventIds = (existing.evidenceEventIds + eventId).distinct().takeLast(MAX_EVIDENCE_PER_RELATION),
                lastSeenAtMillis = nowMillis
            )
        } else {
            relations += GlobalTopicRelation(
                id = GlobalAgentText.stableKey("topic-relation", kind.name, pair[0], pair[1]),
                fromNodeId = pair[0],
                toNodeId = pair[1],
                kind = kind,
                strength = strength,
                evidenceEventIds = listOf(eventId),
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
            return existing.copy(
                name = preferName(existing.name, name),
                kind = GlobalTopicNodeKind.PROJECT,
                conversationIds = (existing.conversationIds + topicNode.conversationIds).take(MAX_CONVERSATIONS_PER_NODE).toSet(),
                entityKeys = (existing.entityKeys + topicNode.entityKeys).take(MAX_ENTITIES_PER_NODE).toSet(),
                evidenceEventIds = (existing.evidenceEventIds + event.id).distinct().takeLast(MAX_EVIDENCE_PER_NODE),
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
            return existing.copy(
                evidenceEventIds = (existing.evidenceEventIds + event.id).distinct().takeLast(MAX_EVIDENCE_PER_NODE),
                confidence = (existing.confidence + 0.02).coerceAtMost(0.94),
                lastSeenAtMillis = nowMillis
            ).also { nodes[index] = it }
        }
        return GlobalTopicNode(
            stableKey = GlobalAgentText.stableKey("topic-project-node", name),
            name = name,
            kind = GlobalTopicNodeKind.TOPIC,
            evidenceEventIds = listOf(event.id),
            confidence = 0.64,
            firstSeenAtMillis = nowMillis,
            lastSeenAtMillis = nowMillis
        ).also(nodes::add)
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
            firstSeenAtMillis = json.optLong("first_seen_at_millis"),
            lastSeenAtMillis = json.optLong("last_seen_at_millis")
        )
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
