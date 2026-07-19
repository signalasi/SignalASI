package com.signalasi.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class GlobalAgentProcessingBatch(
    val processedEventCount: Int,
    val changedWorldItemCount: Int,
    val queuedResearchTasks: List<GlobalResearchTask>,
    val proactiveMessages: List<GlobalProactiveMessage>
)

data class GlobalAgentDashboardSnapshot(
    val pendingEventCount: Int,
    val worldItemCount: Int,
    val topicCount: Int,
    val crossConversationLinkCount: Int,
    val activeGoalCount: Int,
    val activeTaskCount: Int,
    val unresolvedConflictCount: Int,
    val queuedResearchCount: Int,
    val pendingInsightCount: Int,
    val updatedAtMillis: Long
)

class GlobalAgentRepository(context: Context) {
    private val database = AgentEncryptedDatabase(context.applicationContext, DATABASE_NAME)

    fun enqueue(event: GlobalConversationEvent): Boolean = synchronized(STORE_LOCK) {
        val events = loadEvents().toMutableList()
        if (events.any { it.id == event.id }) return@synchronized false
        events += event
        saveEvents(events.takeLast(MAX_PENDING_EVENTS))
        true
    }

    fun pendingEvents(limit: Int = 100): List<GlobalConversationEvent> = synchronized(STORE_LOCK) {
        loadEvents().take(limit.coerceIn(1, MAX_PROCESS_BATCH))
    }

    fun removeEvents(eventIds: Set<String>) = synchronized(STORE_LOCK) {
        if (eventIds.isEmpty()) return@synchronized
        saveEvents(loadEvents().filterNot { it.id in eventIds })
    }

    fun loadWorld(): PersonalWorldModel = synchronized(STORE_LOCK) {
        decodeWorld(database.readString(KEY_WORLD, ""))
    }

    fun saveWorld(world: PersonalWorldModel) = synchronized(STORE_LOCK) {
        database.writeString(KEY_WORLD, encodeWorld(world).toString())
    }

    fun researchTasks(): List<GlobalResearchTask> = synchronized(STORE_LOCK) { loadResearchTasks() }

    fun saveResearchTasks(tasks: List<GlobalResearchTask>) = synchronized(STORE_LOCK) {
        val array = JSONArray()
        tasks.sortedBy(GlobalResearchTask::createdAtMillis).takeLast(MAX_RESEARCH_TASKS).forEach { array.put(encodeResearchTask(it)) }
        database.writeString(KEY_RESEARCH_TASKS, array.toString())
    }

    fun upsertResearchTask(task: GlobalResearchTask) = synchronized(STORE_LOCK) {
        saveResearchTasks(loadResearchTasks().filterNot { it.id == task.id } + task)
    }

    fun claimResearchTask(nowMillis: Long = System.currentTimeMillis()): GlobalResearchTask? = synchronized(STORE_LOCK) {
        val tasks = loadResearchTasks().toMutableList()
        val index = tasks.indexOfFirst { task ->
            task.status in setOf(GlobalResearchTaskStatus.QUEUED, GlobalResearchTaskStatus.WAITING_FOR_RESOURCE) &&
                task.nextAttemptAtMillis <= nowMillis
        }
        if (index < 0) return@synchronized null
        val claimed = tasks[index].copy(
            status = GlobalResearchTaskStatus.RUNNING,
            attemptCount = tasks[index].attemptCount + 1,
            updatedAtMillis = nowMillis
        )
        tasks[index] = claimed
        saveResearchTasks(tasks)
        claimed
    }

    fun proactiveMessages(): List<GlobalProactiveMessage> = synchronized(STORE_LOCK) { loadProactiveMessages() }

    fun saveProactiveMessages(messages: List<GlobalProactiveMessage>) = synchronized(STORE_LOCK) {
        val array = JSONArray()
        messages.sortedBy(GlobalProactiveMessage::createdAtMillis).takeLast(MAX_PROACTIVE_MESSAGES)
            .forEach { array.put(encodeProactiveMessage(it)) }
        database.writeString(KEY_PROACTIVE_MESSAGES, array.toString())
    }

    fun appendProactiveMessage(message: GlobalProactiveMessage): Boolean = synchronized(STORE_LOCK) {
        val messages = loadProactiveMessages()
        if (messages.any { it.id == message.id || it.sourceEventId == message.sourceEventId && it.content == message.content }) {
            return@synchronized false
        }
        saveProactiveMessages(messages + message)
        true
    }

    fun interventionHistory(): GlobalInterventionHistory = synchronized(STORE_LOCK) {
        decodeInterventionHistory(database.readString(KEY_INTERVENTION_HISTORY, ""))
    }

    fun saveInterventionHistory(history: GlobalInterventionHistory) = synchronized(STORE_LOCK) {
        val topics = JSONObject()
        history.lastTopicNotificationMillis.forEach(topics::put)
        database.writeString(
            KEY_INTERVENTION_HISTORY,
            JSONObject()
                .put("notification_timestamps", JSONArray(history.notificationTimestamps.takeLast(100)))
                .put("last_topic_notification_millis", topics)
                .toString()
        )
    }

    fun clear() = synchronized(STORE_LOCK) { database.clear() }

    private fun loadEvents(): List<GlobalConversationEvent> = runCatching {
        val array = JSONArray(database.readString(KEY_EVENTS, "[]"))
        buildList {
            for (index in 0 until array.length()) decodeEvent(array.optJSONObject(index))?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun saveEvents(events: List<GlobalConversationEvent>) {
        val array = JSONArray()
        events.forEach { array.put(encodeEvent(it)) }
        database.writeString(KEY_EVENTS, array.toString())
    }

    private fun loadResearchTasks(): List<GlobalResearchTask> = runCatching {
        val array = JSONArray(database.readString(KEY_RESEARCH_TASKS, "[]"))
        buildList {
            for (index in 0 until array.length()) decodeResearchTask(array.optJSONObject(index))?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun loadProactiveMessages(): List<GlobalProactiveMessage> = runCatching {
        val array = JSONArray(database.readString(KEY_PROACTIVE_MESSAGES, "[]"))
        buildList {
            for (index in 0 until array.length()) decodeProactiveMessage(array.optJSONObject(index))?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun encodeEvent(event: GlobalConversationEvent): JSONObject = JSONObject()
        .put("id", event.id)
        .put("type", event.type.name)
        .put("conversation_id", event.conversationId)
        .put("message_id", event.messageId)
        .put("actor", event.actor.name)
        .put("timestamp_millis", event.timestampMillis)
        .put("content", event.content.take(MAX_EVENT_CONTENT_CHARACTERS))
        .put("content_ref", event.contentRef)
        .put("conversation_title", event.conversationTitle)
        .put("topic_hints", JSONArray(event.topicHints.toList()))
        .put("sensitivity", event.sensitivity.name)
        .put("metadata", JSONObject(event.metadata))

    private fun decodeEvent(json: JSONObject?): GlobalConversationEvent? {
        if (json == null) return null
        val id = json.optString("id")
        val conversationId = json.optString("conversation_id")
        if (id.isBlank() || conversationId.isBlank()) return null
        return GlobalConversationEvent(
            id = id,
            type = enumValue(json.optString("type"), GlobalConversationEventType.MESSAGE_CREATED),
            conversationId = conversationId,
            messageId = json.optString("message_id"),
            actor = enumValue(json.optString("actor"), GlobalConversationActor.SYSTEM),
            timestampMillis = json.optLong("timestamp_millis", System.currentTimeMillis()),
            content = json.optString("content").take(MAX_EVENT_CONTENT_CHARACTERS),
            contentRef = json.optString("content_ref"),
            conversationTitle = json.optString("conversation_title").take(160),
            topicHints = json.optJSONArray("topic_hints").strings().toSet(),
            sensitivity = enumValue(json.optString("sensitivity"), GlobalConversationSensitivity.PERSONAL),
            metadata = json.optJSONObject("metadata").stringMap()
        )
    }

    private fun encodeWorld(world: PersonalWorldModel): JSONObject = JSONObject()
        .put("items", JSONArray().apply { world.items.forEach { put(encodeWorldItem(it)) } })
        .put("links", JSONArray().apply { world.links.forEach { put(encodeLink(it)) } })
        .put("processed_event_ids", JSONArray(world.processedEventIds))
        .put("updated_at_millis", world.updatedAtMillis)

    private fun decodeWorld(raw: String): PersonalWorldModel = runCatching {
        if (raw.isBlank()) return@runCatching PersonalWorldModel()
        val root = JSONObject(raw)
        PersonalWorldModel(
            items = buildList {
                val array = root.optJSONArray("items") ?: JSONArray()
                for (index in 0 until array.length()) decodeWorldItem(array.optJSONObject(index))?.let(::add)
            },
            links = buildList {
                val array = root.optJSONArray("links") ?: JSONArray()
                for (index in 0 until array.length()) decodeLink(array.optJSONObject(index))?.let(::add)
            },
            processedEventIds = root.optJSONArray("processed_event_ids").strings(),
            updatedAtMillis = root.optLong("updated_at_millis")
        )
    }.getOrDefault(PersonalWorldModel())

    private fun encodeWorldItem(item: GlobalWorldItem): JSONObject = JSONObject()
        .put("id", item.id)
        .put("stable_key", item.stableKey)
        .put("kind", item.kind.name)
        .put("layer", item.layer.name)
        .put("topic", item.topic)
        .put("value", item.value)
        .put("confidence", item.confidence)
        .put("evidence_count", item.evidenceCount)
        .put("conversation_ids", JSONArray(item.conversationIds.toList()))
        .put("evidence_event_ids", JSONArray(item.evidenceEventIds))
        .put("status", item.status.name)
        .put("conflict_group_id", item.conflictGroupId)
        .put("first_seen_at_millis", item.firstSeenAtMillis)
        .put("last_seen_at_millis", item.lastSeenAtMillis)
        .put("expires_at_millis", item.expiresAtMillis)

    private fun decodeWorldItem(json: JSONObject?): GlobalWorldItem? {
        if (json == null) return null
        val stableKey = json.optString("stable_key")
        val value = json.optString("value")
        if (stableKey.isBlank() || value.isBlank()) return null
        return GlobalWorldItem(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            stableKey = stableKey,
            kind = enumValue(json.optString("kind"), GlobalWorldItemKind.FACT),
            layer = enumValue(json.optString("layer"), GlobalWorldLayer.TOPIC),
            topic = json.optString("topic").take(160),
            value = value.take(1_200),
            confidence = json.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
            evidenceCount = json.optInt("evidence_count", 1).coerceAtLeast(1),
            conversationIds = json.optJSONArray("conversation_ids").strings().toSet(),
            evidenceEventIds = json.optJSONArray("evidence_event_ids").strings().takeLast(20),
            status = enumValue(json.optString("status"), GlobalWorldItemStatus.ACTIVE),
            conflictGroupId = json.optString("conflict_group_id"),
            firstSeenAtMillis = json.optLong("first_seen_at_millis"),
            lastSeenAtMillis = json.optLong("last_seen_at_millis"),
            expiresAtMillis = json.optLong("expires_at_millis")
        )
    }

    private fun encodeLink(link: GlobalConversationLink): JSONObject = JSONObject()
        .put("id", link.id)
        .put("left_conversation_id", link.leftConversationId)
        .put("right_conversation_id", link.rightConversationId)
        .put("topic", link.topic)
        .put("strength", link.strength)
        .put("evidence_count", link.evidenceCount)
        .put("last_seen_at_millis", link.lastSeenAtMillis)

    private fun decodeLink(json: JSONObject?): GlobalConversationLink? {
        if (json == null) return null
        val left = json.optString("left_conversation_id")
        val right = json.optString("right_conversation_id")
        if (left.isBlank() || right.isBlank()) return null
        return GlobalConversationLink(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            leftConversationId = left,
            rightConversationId = right,
            topic = json.optString("topic"),
            strength = json.optDouble("strength", 0.5).coerceIn(0.0, 1.0),
            evidenceCount = json.optInt("evidence_count", 1).coerceAtLeast(1),
            lastSeenAtMillis = json.optLong("last_seen_at_millis")
        )
    }

    private fun encodeResearchTask(task: GlobalResearchTask): JSONObject = JSONObject()
        .put("id", task.id)
        .put("source_event_id", task.sourceEventId)
        .put("source_conversation_id", task.sourceConversationId)
        .put("topic", task.topic)
        .put("question", task.question)
        .put("depth", task.depth.name)
        .put("preferred_sources", JSONArray(task.preferredSources))
        .put("status", task.status.name)
        .put("resource_id", task.resourceId)
        .put("fallback_resource_ids", JSONArray(task.fallbackResourceIds))
        .put("source_message_id", task.sourceMessageId)
        .put("attempt_count", task.attemptCount)
        .put("next_attempt_at_millis", task.nextAttemptAtMillis)
        .put("last_error", task.lastError)
        .put("result", task.result)
        .put("evidence_uris", JSONArray(task.evidenceUris))
        .put("created_at_millis", task.createdAtMillis)
        .put("updated_at_millis", task.updatedAtMillis)

    private fun decodeResearchTask(json: JSONObject?): GlobalResearchTask? {
        if (json == null) return null
        val id = json.optString("id")
        val sourceEventId = json.optString("source_event_id")
        if (id.isBlank() || sourceEventId.isBlank()) return null
        return GlobalResearchTask(
            id = id,
            sourceEventId = sourceEventId,
            sourceConversationId = json.optString("source_conversation_id"),
            topic = json.optString("topic"),
            question = json.optString("question"),
            depth = enumValue(json.optString("depth"), GlobalResearchDepth.QUICK_FACT),
            preferredSources = json.optJSONArray("preferred_sources").strings(),
            status = enumValue(json.optString("status"), GlobalResearchTaskStatus.QUEUED),
            resourceId = json.optString("resource_id"),
            fallbackResourceIds = json.optJSONArray("fallback_resource_ids").strings(),
            sourceMessageId = json.optLong("source_message_id"),
            attemptCount = json.optInt("attempt_count"),
            nextAttemptAtMillis = json.optLong("next_attempt_at_millis"),
            lastError = json.optString("last_error"),
            result = json.optString("result"),
            evidenceUris = json.optJSONArray("evidence_uris").strings(),
            createdAtMillis = json.optLong("created_at_millis"),
            updatedAtMillis = json.optLong("updated_at_millis")
        )
    }

    private fun encodeProactiveMessage(message: GlobalProactiveMessage): JSONObject = JSONObject()
        .put("id", message.id)
        .put("source_event_id", message.sourceEventId)
        .put("source_conversation_id", message.sourceConversationId)
        .put("target", message.target.name)
        .put("title", message.title)
        .put("content", message.content)
        .put("topic", message.topic)
        .put("urgent", message.urgent)
        .put("status", message.status.name)
        .put("created_at_millis", message.createdAtMillis)
        .put("delivered_at_millis", message.deliveredAtMillis)

    private fun decodeProactiveMessage(json: JSONObject?): GlobalProactiveMessage? {
        if (json == null) return null
        val id = json.optString("id")
        if (id.isBlank()) return null
        return GlobalProactiveMessage(
            id = id,
            sourceEventId = json.optString("source_event_id"),
            sourceConversationId = json.optString("source_conversation_id"),
            target = enumValue(json.optString("target"), GlobalProactiveTarget.CURRENT_CONVERSATION),
            title = json.optString("title"),
            content = json.optString("content"),
            topic = json.optString("topic"),
            urgent = json.optBoolean("urgent"),
            status = enumValue(json.optString("status"), GlobalProactiveMessageStatus.PENDING),
            createdAtMillis = json.optLong("created_at_millis"),
            deliveredAtMillis = json.optLong("delivered_at_millis")
        )
    }

    private fun decodeInterventionHistory(raw: String): GlobalInterventionHistory = runCatching {
        if (raw.isBlank()) return@runCatching GlobalInterventionHistory()
        val root = JSONObject(raw)
        val topics = root.optJSONObject("last_topic_notification_millis")
        GlobalInterventionHistory(
            notificationTimestamps = root.optJSONArray("notification_timestamps").longs(),
            lastTopicNotificationMillis = buildMap {
                if (topics != null) topics.keys().forEach { key -> put(key, topics.optLong(key)) }
            }
        )
    }.getOrDefault(GlobalInterventionHistory())

    private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun JSONArray?.longs(): List<Long> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optLong(index).takeIf { it > 0L }?.let(::add)
        }
    }

    private fun JSONObject?.stringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap { keys().forEach { key -> put(key, optString(key)) } }
    }

    companion object {
        const val DATABASE_NAME = "signalasi_global_super_agent"
        private const val KEY_EVENTS = "conversation_events"
        private const val KEY_WORLD = "personal_world_model"
        private const val KEY_RESEARCH_TASKS = "research_tasks"
        private const val KEY_PROACTIVE_MESSAGES = "proactive_messages"
        private const val KEY_INTERVENTION_HISTORY = "intervention_history"
        private const val MAX_PENDING_EVENTS = 2_000
        private const val MAX_PROCESS_BATCH = 250
        private const val MAX_RESEARCH_TASKS = 300
        private const val MAX_PROACTIVE_MESSAGES = 300
        private const val MAX_EVENT_CONTENT_CHARACTERS = 12_000
        private val STORE_LOCK = Any()
    }
}

class GlobalSuperAgentRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val repository = GlobalAgentRepository(appContext)
    private val understandingPipeline = GlobalUnderstandingPipeline()
    private val researchExecutor by lazy { GlobalResearchExecutor(appContext) }

    fun processPending(maxEvents: Int = 100): GlobalAgentProcessingBatch = synchronized(PROCESS_LOCK) {
        val events = repository.pendingEvents(maxEvents)
        if (events.isEmpty()) return@synchronized GlobalAgentProcessingBatch(0, 0, emptyList(), emptyList())
        var world = repository.loadWorld()
        val history = repository.interventionHistory()
        val existingTasks = repository.researchTasks().toMutableList()
        val existingMessages = repository.proactiveMessages().toMutableList()
        val newTasks = mutableListOf<GlobalResearchTask>()
        val newMessages = mutableListOf<GlobalProactiveMessage>()
        var changedItems = 0
        events.forEach { event ->
            if (event.sensitivity == GlobalConversationSensitivity.SESSION_PRIVATE) {
                world = world.copy(
                    processedEventIds = (world.processedEventIds + event.id).takeLast(4_000),
                    updatedAtMillis = event.timestampMillis
                )
                return@forEach
            }
            val understanding = understandingPipeline.understand(event, world)
            val reduction = GlobalWorldModelReducer.reduce(world, event, understanding)
            world = reduction.world
            changedItems += reduction.changedItems.size
            val decision = GlobalInterventionPolicy.decide(event, understanding, reduction, history)
            if (decision.researchRequired || decision.autonomousPreparationAllowed) {
                GlobalResearchPlanner.plan(event, understanding)?.takeIf { candidate ->
                    (existingTasks + newTasks).none { task ->
                        task.status !in setOf(GlobalResearchTaskStatus.COMPLETED, GlobalResearchTaskStatus.FAILED) &&
                            GlobalAgentText.normalize(task.topic) == GlobalAgentText.normalize(candidate.topic) &&
                            GlobalAgentText.overlap(
                                GlobalAgentText.tokens(task.question),
                                GlobalAgentText.tokens(candidate.question)
                            ) >= 0.72
                    }
                }?.let(newTasks::add)
            }
            GlobalProactiveMessageFactory.create(event, understanding, reduction, decision)
                ?.takeIf { candidate ->
                    (existingMessages + newMessages).none { message ->
                        message.sourceEventId == candidate.sourceEventId ||
                            (message.status != GlobalProactiveMessageStatus.DISMISSED &&
                                GlobalAgentText.normalize(message.topic) == GlobalAgentText.normalize(candidate.topic) &&
                                event.timestampMillis - message.createdAtMillis in 0 until MESSAGE_DEDUPE_WINDOW_MILLIS)
                    }
                }
                ?.let(newMessages::add)
        }
        repository.saveWorld(world)
        if (newTasks.isNotEmpty()) repository.saveResearchTasks(existingTasks + newTasks)
        if (newMessages.isNotEmpty()) repository.saveProactiveMessages(existingMessages + newMessages)
        repository.removeEvents(events.map(GlobalConversationEvent::id).toSet())
        GlobalAgentProcessingBatch(events.size, changedItems, newTasks, newMessages)
    }

    fun augmentContext(context: AgentConversationContext, query: String): AgentConversationContext {
        if (context.privateMode) return context.copy(globalContext = "")
        val block = GlobalAgentContextSelector.build(repository.loadWorld(), query, context.conversationId)
        return context.copy(globalContext = block)
    }

    fun worldSnapshot(): PersonalWorldModel = repository.loadWorld()

    fun researchTasks(): List<GlobalResearchTask> = repository.researchTasks()

    fun executeResearchCycle(): GlobalResearchExecutionResult? = researchExecutor.executeNext()

    fun consumeResearchResponse(response: AgentConnectorResponse): Boolean =
        researchExecutor.consumeConnectorResponse(response)

    fun pendingProactiveMessages(): List<GlobalProactiveMessage> = repository.proactiveMessages()
        .filter { it.status in setOf(GlobalProactiveMessageStatus.PENDING, GlobalProactiveMessageStatus.NOTIFIED) }
        .sortedBy(GlobalProactiveMessage::createdAtMillis)

    fun markNotified(messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        val now = System.currentTimeMillis()
        val messages = repository.proactiveMessages().map { message ->
            if (message.id in messageIds && message.status == GlobalProactiveMessageStatus.PENDING) {
                message.copy(status = GlobalProactiveMessageStatus.NOTIFIED)
            } else message
        }
        repository.saveProactiveMessages(messages)
        val notified = messages.filter { it.id in messageIds }
        if (notified.isNotEmpty()) {
            val previous = repository.interventionHistory()
            repository.saveInterventionHistory(previous.copy(
                notificationTimestamps = (previous.notificationTimestamps + notified.map { now }).takeLast(100),
                lastTopicNotificationMillis = previous.lastTopicNotificationMillis +
                    notified.associate { GlobalAgentText.normalize(it.topic) to now }
            ))
        }
    }

    fun deliverPending(transcriptStore: AgentTranscriptStore): List<GlobalProactiveMessage> = synchronized(PROCESS_LOCK) {
        val messages = pendingProactiveMessages()
        if (messages.isEmpty()) return@synchronized emptyList()
        val delivered = mutableListOf<GlobalProactiveMessage>()
        messages.forEach { message ->
            if (message.target == GlobalProactiveTarget.GLOBAL_DIGEST &&
                messages.count { it.target == GlobalProactiveTarget.GLOBAL_DIGEST } < MIN_DIGEST_ITEMS &&
                System.currentTimeMillis() - message.createdAtMillis < DIGEST_MAX_WAIT_MILLIS
            ) return@forEach
            val targetConversationId = when (message.target) {
                GlobalProactiveTarget.CURRENT_CONVERSATION -> message.sourceConversationId
                GlobalProactiveTarget.NEW_CONVERSATION -> transcriptStore.createAgentConversation(
                    title = message.topic,
                    parentConversationId = message.sourceConversationId
                ).id
                GlobalProactiveTarget.GLOBAL_DIGEST -> message.sourceConversationId
            }
            val persisted = transcriptStore.append(
                role = AgentTranscriptRole.ASSISTANT,
                text = "${message.title}\n\n${message.content}",
                dedupeKey = "global-agent:${message.id}",
                conversationId = targetConversationId,
                taskId = message.id
            )
            if (persisted || transcriptStore.list(targetConversationId).any { it.dedupeKey == "global-agent:${message.id}" }) {
                delivered += message.copy(
                    status = GlobalProactiveMessageStatus.DELIVERED,
                    deliveredAtMillis = System.currentTimeMillis()
                )
            }
        }
        if (delivered.isNotEmpty()) {
            val deliveredById = delivered.associateBy(GlobalProactiveMessage::id)
            repository.saveProactiveMessages(repository.proactiveMessages().map { deliveredById[it.id] ?: it })
        }
        delivered
    }

    fun dashboard(): GlobalAgentDashboardSnapshot {
        val world = repository.loadWorld()
        val active = world.items.filter { it.status == GlobalWorldItemStatus.ACTIVE }
        return GlobalAgentDashboardSnapshot(
            pendingEventCount = repository.pendingEvents(250).size,
            worldItemCount = active.size,
            topicCount = active.map { GlobalAgentText.normalize(it.topic) }.filter(String::isNotBlank).distinct().size,
            crossConversationLinkCount = world.links.size,
            activeGoalCount = active.count { it.kind == GlobalWorldItemKind.GOAL },
            activeTaskCount = active.count { it.kind == GlobalWorldItemKind.TASK },
            unresolvedConflictCount = world.items.count { it.status == GlobalWorldItemStatus.CONFLICTED } / 2,
            queuedResearchCount = repository.researchTasks().count {
                it.status in setOf(GlobalResearchTaskStatus.QUEUED, GlobalResearchTaskStatus.RUNNING, GlobalResearchTaskStatus.WAITING_FOR_RESOURCE)
            },
            pendingInsightCount = pendingProactiveMessages().size,
            updatedAtMillis = world.updatedAtMillis
        )
    }

    fun clear() = repository.clear()

    companion object {
        private const val MESSAGE_DEDUPE_WINDOW_MILLIS = 6L * 60L * 60L * 1_000L
        private const val MIN_DIGEST_ITEMS = 3
        private const val DIGEST_MAX_WAIT_MILLIS = 12L * 60L * 60L * 1_000L
        private val PROCESS_LOCK = Any()
        @Volatile private var instance: GlobalSuperAgentRuntime? = null

        fun get(context: Context): GlobalSuperAgentRuntime = instance ?: synchronized(this) {
            instance ?: GlobalSuperAgentRuntime(context.applicationContext).also { instance = it }
        }
    }
}

object GlobalConversationEventBus {
    fun publishTranscriptEntry(
        context: Context,
        conversation: AgentConversation,
        entry: AgentTranscriptEntry,
        updated: Boolean = false
    ): Boolean {
        val privateMode = conversation.privateMode
        val actor = when {
            entry.dedupeKey.startsWith("global-agent:") -> GlobalConversationActor.GLOBAL_AGENT
            entry.role == AgentTranscriptRole.USER -> GlobalConversationActor.USER
            entry.role == AgentTranscriptRole.ASSISTANT -> GlobalConversationActor.ASSISTANT
            else -> GlobalConversationActor.TOOL
        }
        val event = GlobalConversationEvent(
            id = "transcript:${entry.id}",
            type = if (updated) GlobalConversationEventType.MESSAGE_UPDATED else GlobalConversationEventType.MESSAGE_CREATED,
            conversationId = entry.conversationId,
            messageId = entry.id,
            actor = actor,
            timestampMillis = entry.timestampMillis,
            content = if (privateMode) "" else entry.text,
            contentRef = "encrypted://agent-transcript/${entry.conversationId}/${entry.id}",
            conversationTitle = conversation.title,
            topicHints = setOf(conversation.title).filterNot { it.equals("New session", ignoreCase = true) }.toSet(),
            sensitivity = if (privateMode) {
                GlobalConversationSensitivity.SESSION_PRIVATE
            } else GlobalConversationSensitivity.PERSONAL,
            metadata = mapOf(
                "turn_id" to entry.turnId,
                "task_id" to entry.taskId,
                "role" to entry.role.name,
                "origin" to if (actor == GlobalConversationActor.GLOBAL_AGENT) "global_agent" else "conversation"
            )
        )
        val enqueued = GlobalAgentRepository(context).enqueue(event)
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishConversationDeleted(context: Context, conversation: AgentConversation): Boolean {
        val event = GlobalConversationEvent(
            id = "conversation-deleted:${conversation.id}:${System.currentTimeMillis()}",
            type = GlobalConversationEventType.CONVERSATION_DELETED,
            conversationId = conversation.id,
            actor = GlobalConversationActor.SYSTEM,
            content = "",
            conversationTitle = conversation.title,
            sensitivity = if (conversation.privateMode) {
                GlobalConversationSensitivity.SESSION_PRIVATE
            } else GlobalConversationSensitivity.PERSONAL
        )
        val enqueued = GlobalAgentRepository(context).enqueue(event)
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    private fun requestProcessing(context: Context) {
        val intent = Intent(context.applicationContext, MessageService::class.java)
            .setAction(MessageService.ACTION_PROCESS_GLOBAL_AGENT)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }
    }
}
