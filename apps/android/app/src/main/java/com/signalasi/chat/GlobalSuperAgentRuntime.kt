package com.signalasi.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class GlobalAgentProcessingBatch(
    val processedEventCount: Int,
    val changedWorldItemCount: Int,
    val queuedResearchTasks: List<GlobalResearchTask>,
    val proactiveMessages: List<GlobalProactiveMessage>,
    val queuedCognitionTasks: List<GlobalCognitionTask> = emptyList()
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
    val queuedCognitionCount: Int,
    val activeAutonomousRunCount: Int,
    val replanningRunCount: Int,
    val waitingConfirmationCount: Int,
    val longHorizonGoalCount: Int,
    val blockedLongHorizonGoalCount: Int,
    val pendingInsightCount: Int,
    val feedbackCount: Int,
    val learnedTopicCount: Int,
    val updatedAtMillis: Long
)

data class GlobalAgentNotificationCandidate(
    val title: String,
    val content: String,
    val conversationId: String,
    val messageIds: Set<String>
)

class GlobalAgentRepository(context: Context) {
    private val database = AgentEncryptedDatabase(context.applicationContext, DATABASE_NAME)
    private val deliberationStore = GlobalAgentDeliberationStore(context.applicationContext)
    private val longHorizonStore = GlobalLongHorizonGoalStore(context.applicationContext)
    private val topicGraphStore = GlobalTopicProjectGraphStore(context.applicationContext)
    private val proactiveDiscoveryStore = GlobalProactiveDiscoveryStore(context.applicationContext)

    fun enqueue(event: GlobalConversationEvent): Boolean = synchronized(STORE_LOCK) {
        enqueueAllLocked(listOf(event)) > 0
    }

    fun enqueueAll(incoming: List<GlobalConversationEvent>): Int = synchronized(STORE_LOCK) {
        enqueueAllLocked(incoming)
    }

    private fun enqueueAllLocked(incoming: List<GlobalConversationEvent>): Int {
        if (incoming.isEmpty()) return 0
        val events = loadEvents().toMutableList()
        val knownIds = events.mapTo(mutableSetOf(), GlobalConversationEvent::id)
        val additions = incoming.asSequence()
            .filter { it.id.isNotBlank() && knownIds.add(it.id) }
            .toList()
        if (additions.isEmpty()) return 0
        saveContextJournal(
            GlobalConversationContextJournalPolicy.apply(loadContextJournal(), additions)
        )
        events += additions
        saveEvents(events.takeLast(MAX_PENDING_EVENTS))
        return additions.size
    }

    fun pendingEvents(limit: Int = 100): List<GlobalConversationEvent> = synchronized(STORE_LOCK) {
        loadEvents().take(limit.coerceIn(1, MAX_PROCESS_BATCH))
    }

    fun removeEvents(eventIds: Set<String>) = synchronized(STORE_LOCK) {
        if (eventIds.isEmpty()) return@synchronized
        saveEvents(loadEvents().filterNot { it.id in eventIds })
    }

    fun recentConversationContext(
        event: GlobalConversationEvent,
        maximumEvents: Int = GlobalConversationContextJournalPolicy.DEFAULT_SELECTION_EVENTS,
        maximumCharacters: Int = GlobalConversationContextJournalPolicy.DEFAULT_SELECTION_CHARACTERS
    ): String = recentConversationContext(
        conversationId = event.conversationId,
        beforeOrAtMillis = event.timestampMillis,
        excludedEventIds = setOf(event.id),
        maximumEvents = maximumEvents,
        maximumCharacters = maximumCharacters
    )

    fun recentConversationContext(
        conversationId: String,
        beforeOrAtMillis: Long,
        excludedEventIds: Set<String> = emptySet(),
        maximumEvents: Int = GlobalConversationContextJournalPolicy.DEFAULT_SELECTION_EVENTS,
        maximumCharacters: Int = GlobalConversationContextJournalPolicy.DEFAULT_SELECTION_CHARACTERS
    ): String = synchronized(STORE_LOCK) {
        GlobalConversationContextJournalPolicy.render(
            GlobalConversationContextJournalPolicy.select(
                events = loadContextJournal(),
                conversationId = conversationId,
                beforeOrAtMillis = beforeOrAtMillis,
                excludedEventIds = excludedEventIds,
                maximumEvents = maximumEvents,
                maximumCharacters = maximumCharacters
            ),
            maximumCharacters
        )
    }

    fun loadWorld(): PersonalWorldModel = synchronized(STORE_LOCK) {
        decodeWorld(database.readString(KEY_WORLD, ""))
    }

    fun saveWorld(world: PersonalWorldModel) = synchronized(STORE_LOCK) {
        database.writeString(KEY_WORLD, encodeWorld(world).toString())
    }

    fun topicGraph(): GlobalTopicProjectGraph = topicGraphStore.load()

    fun saveTopicGraph(graph: GlobalTopicProjectGraph) = topicGraphStore.save(graph)

    fun researchTasks(): List<GlobalResearchTask> = synchronized(STORE_LOCK) { loadResearchTasks() }

    fun saveResearchTasks(tasks: List<GlobalResearchTask>) = synchronized(STORE_LOCK) {
        val array = JSONArray()
        tasks.sortedBy(GlobalResearchTask::createdAtMillis).takeLast(MAX_RESEARCH_TASKS).forEach { array.put(encodeResearchTask(it)) }
        database.writeString(KEY_RESEARCH_TASKS, array.toString())
    }

    fun upsertResearchTask(task: GlobalResearchTask) = synchronized(STORE_LOCK) {
        saveResearchTasks(loadResearchTasks().filterNot { it.id == task.id } + task)
    }

    fun updateResearchTask(
        taskId: String,
        transform: (GlobalResearchTask) -> GlobalResearchTask
    ): GlobalResearchTask? = synchronized(STORE_LOCK) {
        val tasks = loadResearchTasks().toMutableList()
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index < 0) return@synchronized null
        val updated = transform(tasks[index])
        tasks[index] = updated
        saveResearchTasks(tasks)
        updated
    }

    fun claimResearchTask(nowMillis: Long = System.currentTimeMillis()): GlobalResearchTask? = synchronized(STORE_LOCK) {
        val tasks = loadResearchTasks()
            .map { GlobalResearchTaskPolicy.recoverIfStale(it, nowMillis) }
            .toMutableList()
        val index = tasks.indexOfFirst { task ->
            task.status in setOf(
                GlobalResearchTaskStatus.QUEUED,
                GlobalResearchTaskStatus.SCHEDULED,
                GlobalResearchTaskStatus.WAITING_FOR_RESOURCE
            ) &&
                task.nextAttemptAtMillis <= nowMillis
        }
        if (index < 0) {
            saveResearchTasks(tasks)
            return@synchronized null
        }
        val claimed = tasks[index].copy(
            status = GlobalResearchTaskStatus.RUNNING,
            attemptCount = tasks[index].attemptCount + 1,
            sourceMessageId = 0L,
            leaseExpiresAtMillis = nowMillis + GlobalResearchTaskPolicy.leaseMillis(tasks[index].depth),
            updatedAtMillis = nowMillis
        )
        tasks[index] = claimed
        saveResearchTasks(tasks)
        claimed
    }

    fun cognitionTasks(): List<GlobalCognitionTask> = deliberationStore.cognitionTasks()

    fun saveCognitionTasks(tasks: List<GlobalCognitionTask>) = deliberationStore.saveCognitionTasks(tasks)

    fun upsertCognitionTask(task: GlobalCognitionTask) = deliberationStore.upsertCognitionTask(task)

    fun updateCognitionTask(
        taskId: String,
        transform: (GlobalCognitionTask) -> GlobalCognitionTask
    ): GlobalCognitionTask? = deliberationStore.updateCognitionTask(taskId, transform)

    fun claimCognitionTask(nowMillis: Long = System.currentTimeMillis()): GlobalCognitionTask? =
        deliberationStore.claimCognitionTask(nowMillis)

    fun autonomousRuns(): List<GlobalAutonomousRun> = deliberationStore.autonomousRuns()

    fun saveAutonomousRuns(runs: List<GlobalAutonomousRun>) = deliberationStore.saveAutonomousRuns(runs)

    fun upsertAutonomousRun(run: GlobalAutonomousRun) = deliberationStore.upsertAutonomousRun(run)

    fun updateAutonomousRun(
        runId: String,
        transform: (GlobalAutonomousRun) -> GlobalAutonomousRun
    ): GlobalAutonomousRun? = deliberationStore.updateAutonomousRun(runId, transform)

    fun claimAutonomousWork(nowMillis: Long = System.currentTimeMillis()): GlobalAutonomousWorkClaim? =
        deliberationStore.claimAutonomousWork(nowMillis)

    fun longHorizonGoals(): List<GlobalLongHorizonGoal> = longHorizonStore.goals()

    fun saveLongHorizonGoals(goals: List<GlobalLongHorizonGoal>) = longHorizonStore.save(goals)

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

    fun claimProactiveMessages(
        messageIds: Set<String>,
        conversationId: String,
        deliveryGroupId: String,
        nowMillis: Long,
        leaseMillis: Long
    ): List<GlobalProactiveMessage> = synchronized(STORE_LOCK) {
        if (messageIds.isEmpty() || conversationId.isBlank() || deliveryGroupId.isBlank()) {
            return@synchronized emptyList()
        }
        val messages = loadProactiveMessages().toMutableList()
        val claimed = mutableListOf<GlobalProactiveMessage>()
        messages.indices.forEach { index ->
            val message = messages[index]
            if (message.id !in messageIds || !GlobalProactiveDeliveryPolicy.isRecoverable(message, nowMillis)) {
                return@forEach
            }
            val updated = message.copy(
                status = GlobalProactiveMessageStatus.DELIVERING,
                deliveryConversationId = conversationId,
                deliveryLeaseExpiresAtMillis = nowMillis + leaseMillis.coerceAtLeast(1L),
                deliveryAttemptCount = message.deliveryAttemptCount + 1,
                deliveryGroupId = deliveryGroupId,
                lastDeliveryError = ""
            )
            messages[index] = updated
            claimed += updated
        }
        if (claimed.size != messageIds.size) return@synchronized emptyList()
        saveProactiveMessages(messages)
        claimed
    }

    fun completeProactiveDelivery(
        messageIds: Set<String>,
        conversationId: String,
        deliveryGroupId: String,
        countBudget: Boolean,
        nowMillis: Long
    ): List<GlobalProactiveMessage> = synchronized(STORE_LOCK) {
        if (messageIds.isEmpty() || conversationId.isBlank() || deliveryGroupId.isBlank()) {
            return@synchronized emptyList()
        }
        val messages = loadProactiveMessages().toMutableList()
        val selected = messages.filter { it.id in messageIds }
        if (selected.size != messageIds.size) return@synchronized emptyList()
        val history = interventionHistory()
        val alreadyCounted = deliveryGroupId in history.countedDeliveryGroupIds
        if (countBudget && !alreadyCounted) {
            saveInterventionHistory(history.copy(
                notificationTimestamps = (history.notificationTimestamps + nowMillis).takeLast(100),
                lastTopicNotificationMillis = history.lastTopicNotificationMillis +
                    selected.associate { GlobalAgentText.normalize(it.topic) to nowMillis },
                countedDeliveryGroupIds = (history.countedDeliveryGroupIds + deliveryGroupId)
                    .takeLast(MAX_COUNTED_DELIVERY_GROUPS)
            ))
        }
        val completed = mutableListOf<GlobalProactiveMessage>()
        messages.indices.forEach { index ->
            val message = messages[index]
            if (message.id !in messageIds) return@forEach
            val updated = message.copy(
                status = GlobalProactiveMessageStatus.DELIVERED,
                deliveryConversationId = conversationId,
                deliveryLeaseExpiresAtMillis = 0L,
                deliveryBudgetCounted = message.deliveryBudgetCounted || countBudget || alreadyCounted,
                lastDeliveryError = "",
                deliveredAtMillis = nowMillis,
                deliveredConversationId = conversationId,
                deliveryGroupId = deliveryGroupId
            )
            messages[index] = updated
            completed += updated
        }
        saveProactiveMessages(messages)
        completed
    }

    fun releaseProactiveDelivery(messageIds: Set<String>, reason: String) = synchronized(STORE_LOCK) {
        if (messageIds.isEmpty()) return@synchronized
        saveProactiveMessages(loadProactiveMessages().map { message ->
            if (message.id !in messageIds || message.status != GlobalProactiveMessageStatus.DELIVERING) {
                message
            } else {
                message.copy(
                    status = if (message.notifiedAtMillis > 0L) {
                        GlobalProactiveMessageStatus.NOTIFIED
                    } else GlobalProactiveMessageStatus.PENDING,
                    deliveryLeaseExpiresAtMillis = 0L,
                    lastDeliveryError = reason.take(600)
                )
            }
        })
    }

    fun dismissProactiveMessages(messageIds: Set<String>, reason: String) = synchronized(STORE_LOCK) {
        if (messageIds.isEmpty()) return@synchronized
        saveProactiveMessages(loadProactiveMessages().map { message ->
            if (message.id !in messageIds || message.status in setOf(
                    GlobalProactiveMessageStatus.DELIVERED,
                    GlobalProactiveMessageStatus.DISMISSED
                )
            ) message else message.copy(
                status = GlobalProactiveMessageStatus.DISMISSED,
                deliveryLeaseExpiresAtMillis = 0L,
                lastDeliveryError = reason.take(600)
            )
        })
    }

    fun markDeliveryArtifactsRetracted(messageIds: Set<String>) = synchronized(STORE_LOCK) {
        if (messageIds.isEmpty()) return@synchronized
        saveProactiveMessages(loadProactiveMessages().map { message ->
            if (message.id !in messageIds) message else message.copy(
                deliveryConversationId = "",
                deliveryLeaseExpiresAtMillis = 0L
            )
        })
    }

    fun markProactiveNotified(messageIds: Set<String>, nowMillis: Long) = synchronized(STORE_LOCK) {
        if (messageIds.isEmpty()) return@synchronized
        val messages = loadProactiveMessages()
        val targets = messages.filter { it.id in messageIds }
        if (targets.isEmpty()) return@synchronized
        val groupId = targets.map(GlobalProactiveMessage::deliveryGroupId)
            .firstOrNull(String::isNotBlank)
            ?: "notification:${GlobalAgentText.stableKey(*targets.map(GlobalProactiveMessage::id).sorted().toTypedArray())}"
        val shouldCountBudget = targets.none(GlobalProactiveMessage::deliveryBudgetCounted)
        val history = interventionHistory()
        val budgetCountedForGroup = shouldCountBudget || groupId in history.countedDeliveryGroupIds
        if (shouldCountBudget && groupId !in history.countedDeliveryGroupIds) {
            saveInterventionHistory(history.copy(
                notificationTimestamps = (history.notificationTimestamps + nowMillis).takeLast(100),
                lastTopicNotificationMillis = history.lastTopicNotificationMillis +
                    targets.associate { GlobalAgentText.normalize(it.topic) to nowMillis },
                countedDeliveryGroupIds = (history.countedDeliveryGroupIds + groupId)
                    .takeLast(MAX_COUNTED_DELIVERY_GROUPS)
            ))
        }
        saveProactiveMessages(messages.map { message ->
            if (message.id !in messageIds) message else message.copy(
                status = if (message.status == GlobalProactiveMessageStatus.PENDING) {
                    GlobalProactiveMessageStatus.NOTIFIED
                } else message.status,
                notifiedAtMillis = nowMillis,
                deliveryBudgetCounted = message.deliveryBudgetCounted || budgetCountedForGroup
            )
        })
    }

    fun markProactiveViewed(messageIds: Set<String>, nowMillis: Long): Int = synchronized(STORE_LOCK) {
        if (messageIds.isEmpty() || nowMillis <= 0L) return@synchronized 0
        val messages = loadProactiveMessages()
        val updated = GlobalProactiveInboxPolicy.markViewed(messages, messageIds, nowMillis)
        val changed = updated.indices.count { index -> updated[index] != messages[index] }
        if (changed > 0) saveProactiveMessages(updated)
        changed
    }

    fun conversationTombstones(): Set<String> = synchronized(STORE_LOCK) { loadConversationTombstones() }

    fun excludedConversationIds(): Set<String> = synchronized(STORE_LOCK) {
        loadConversationTombstones() + loadEvents().asSequence()
            .filter { it.type == GlobalConversationEventType.CONVERSATION_DELETED || it.excludesConversationFromGlobalModel() }
            .map(GlobalConversationEvent::conversationId)
            .filter(String::isNotBlank)
            .toSet()
    }

    fun markConversationDeleted(conversationId: String) = synchronized(STORE_LOCK) {
        val cleanId = conversationId.trim()
        if (cleanId.isBlank()) return@synchronized
        saveConversationTombstones(
            (loadConversationTombstones() + cleanId).toList()
                .takeLast(MAX_CONVERSATION_TOMBSTONES)
                .toSet()
        )
    }

    fun markConversationCreated(conversationId: String) = synchronized(STORE_LOCK) {
        val cleanId = conversationId.trim()
        if (cleanId.isBlank()) return@synchronized
        val retained = loadConversationTombstones() - cleanId
        saveConversationTombstones(retained)
    }

    fun interventionHistory(): GlobalInterventionHistory = synchronized(STORE_LOCK) {
        decodeInterventionHistory(database.readString(KEY_INTERVENTION_HISTORY, ""))
    }

    fun saveInterventionHistory(history: GlobalInterventionHistory) = synchronized(STORE_LOCK) {
        database.writeString(
            KEY_INTERVENTION_HISTORY,
            encodeInterventionHistory(history).toString()
        )
    }

    fun settings(): GlobalAgentSettings = synchronized(STORE_LOCK) {
        decodeSettings(database.readString(KEY_SETTINGS, ""))
    }

    fun saveSettings(settings: GlobalAgentSettings) = synchronized(STORE_LOCK) {
        database.writeString(KEY_SETTINGS, encodeSettings(settings).toString())
    }

    fun persistentContextSyncVersion(): Int = synchronized(STORE_LOCK) {
        database.readString(KEY_PERSISTENT_CONTEXT_SYNC_VERSION, "0").toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    fun savePersistentContextSyncVersion(version: Int) = synchronized(STORE_LOCK) {
        database.writeString(KEY_PERSISTENT_CONTEXT_SYNC_VERSION, version.coerceAtLeast(0).toString())
    }

    fun feedback(): List<GlobalAgentFeedback> = synchronized(STORE_LOCK) { loadFeedback() }

    fun saveFeedback(feedback: List<GlobalAgentFeedback>) = synchronized(STORE_LOCK) {
        val array = JSONArray()
        feedback.sortedBy(GlobalAgentFeedback::createdAtMillis).takeLast(MAX_FEEDBACK_ITEMS)
            .forEach { array.put(encodeFeedback(it)) }
        database.writeString(KEY_FEEDBACK, array.toString())
    }

    fun replaceFeedback(item: GlobalAgentFeedback) = synchronized(STORE_LOCK) {
        saveFeedback(loadFeedback().filterNot { it.proactiveMessageId == item.proactiveMessageId } + item)
    }

    fun exportSnapshot(): JSONObject = synchronized(STORE_LOCK) {
        JSONObject()
            .put("version", 15)
            .put("events", JSONArray().apply { loadEvents().forEach { put(encodeEvent(it)) } })
            .put("context_journal", JSONArray().apply {
                loadContextJournal().forEach { put(encodeEvent(it)) }
            })
            .put("world", encodeWorld(loadWorld()))
            .put("topic_project_graph", topicGraphStore.export())
            .put("research_tasks", JSONArray().apply {
                loadResearchTasks().forEach { put(encodeResearchTask(it)) }
            })
            .put("cognition_tasks", deliberationStore.exportCognitionTasks())
            .put("autonomous_runs", deliberationStore.exportAutonomousRuns())
            .put("long_horizon_goals", longHorizonStore.export())
            .put("proactive_messages", JSONArray().apply {
                loadProactiveMessages().forEach { put(encodeProactiveMessage(it)) }
            })
            .put("intervention_history", encodeInterventionHistory(interventionHistory()))
            .put("settings", encodeSettings(settings()))
            .put("persistent_context_sync_version", persistentContextSyncVersion())
            .put("conversation_tombstones", JSONArray(conversationTombstones().toList()))
            .put("feedback", JSONArray().apply { loadFeedback().forEach { put(encodeFeedback(it)) } })
            .put("proactive_discovery", proactiveDiscoveryStore.export())
    }

    fun restoreSnapshot(payload: JSONObject) = synchronized(STORE_LOCK) {
        payload.optJSONArray("events")?.let { array ->
            saveEvents(buildList {
                for (index in 0 until array.length()) decodeEvent(array.optJSONObject(index))?.let(::add)
            }.takeLast(MAX_PENDING_EVENTS))
        }
        val contextJournal = payload.optJSONArray("context_journal")
        saveContextJournal(if (contextJournal == null) {
            emptyList()
        } else {
            GlobalConversationContextJournalPolicy.apply(
                existing = emptyList(),
                incoming = buildList {
                    for (index in 0 until contextJournal.length()) {
                        decodeEvent(contextJournal.optJSONObject(index))?.let(::add)
                    }
                }
            )
        })
        payload.optJSONObject("world")?.let { saveWorld(decodeWorld(it.toString())) }
        payload.optJSONObject("topic_project_graph")?.let(topicGraphStore::restore)
        payload.optJSONArray("research_tasks")?.let { array ->
            saveResearchTasks(buildList {
                for (index in 0 until array.length()) decodeResearchTask(array.optJSONObject(index))?.let(::add)
            }.takeLast(MAX_RESEARCH_TASKS))
        }
        payload.optJSONArray("cognition_tasks")?.let(deliberationStore::restoreCognitionTasks)
        payload.optJSONArray("autonomous_runs")?.let(deliberationStore::restoreAutonomousRuns)
        payload.optJSONArray("long_horizon_goals")?.let(longHorizonStore::restore)
        payload.optJSONArray("proactive_messages")?.let { array ->
            saveProactiveMessages(buildList {
                for (index in 0 until array.length()) decodeProactiveMessage(array.optJSONObject(index))?.let(::add)
            }.takeLast(MAX_PROACTIVE_MESSAGES))
        }
        payload.optJSONObject("intervention_history")?.let {
            saveInterventionHistory(decodeInterventionHistory(it.toString()))
        }
        payload.optJSONObject("settings")?.let { saveSettings(decodeSettings(it.toString())) }
        savePersistentContextSyncVersion(0)
        payload.optJSONArray("conversation_tombstones")?.let { array ->
            saveConversationTombstones(array.strings().takeLast(MAX_CONVERSATION_TOMBSTONES).toSet())
        }
        payload.optJSONArray("feedback")?.let { array ->
            saveFeedback(buildList {
                for (index in 0 until array.length()) decodeFeedback(array.optJSONObject(index))?.let(::add)
            }.takeLast(MAX_FEEDBACK_ITEMS))
        }
        payload.optJSONObject("proactive_discovery")?.let(proactiveDiscoveryStore::restore)
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

    private fun loadContextJournal(): List<GlobalConversationEvent> = runCatching {
        val array = JSONArray(database.readString(KEY_CONTEXT_JOURNAL, "[]"))
        buildList {
            for (index in 0 until array.length()) decodeEvent(array.optJSONObject(index))?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun saveContextJournal(events: List<GlobalConversationEvent>) {
        val array = JSONArray()
        events.forEach { array.put(encodeEvent(it)) }
        database.writeString(KEY_CONTEXT_JOURNAL, array.toString())
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

    private fun loadFeedback(): List<GlobalAgentFeedback> = runCatching {
        val array = JSONArray(database.readString(KEY_FEEDBACK, "[]"))
        buildList {
            for (index in 0 until array.length()) decodeFeedback(array.optJSONObject(index))?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun loadConversationTombstones(): Set<String> = runCatching {
        JSONArray(database.readString(KEY_CONVERSATION_TOMBSTONES, "[]")).strings()
            .takeLast(MAX_CONVERSATION_TOMBSTONES)
            .toSet()
    }.getOrDefault(emptySet())

    private fun saveConversationTombstones(conversationIds: Set<String>) {
        database.writeString(
            KEY_CONVERSATION_TOMBSTONES,
            JSONArray(conversationIds.filter(String::isNotBlank).takeLast(MAX_CONVERSATION_TOMBSTONES)).toString()
        )
    }

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
        .put("causal_event_ids", JSONArray(event.causalEventIds.toList()))
        .put("retracted_event_ids", JSONArray(event.retractedEventIds.toList()))

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
            metadata = json.optJSONObject("metadata").stringMap(),
            causalEventIds = json.optJSONArray("causal_event_ids").strings().toSet(),
            retractedEventIds = json.optJSONArray("retracted_event_ids").strings().toSet()
        )
    }

    private fun encodeWorld(world: PersonalWorldModel): JSONObject = JSONObject()
        .put("items", JSONArray().apply { world.items.forEach { put(encodeWorldItem(it)) } })
        .put("links", JSONArray().apply { world.links.forEach { put(encodeLink(it)) } })
        .put("processed_event_ids", JSONArray(world.processedEventIds))
        .put("retracted_event_ids", JSONArray(world.retractedEventIds))
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
            retractedEventIds = root.optJSONArray("retracted_event_ids").strings(),
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
        .put("context_visibility", item.contextVisibility.name)
        .put("evidence_count", item.evidenceCount)
        .put("conversation_ids", JSONArray(item.conversationIds.toList()))
        .put("evidence_event_ids", JSONArray(item.evidenceEventIds))
        .put("evidence_provenance", JSONArray().apply {
            item.evidenceProvenance.forEach { put(encodeEvidenceRef(it)) }
        })
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
            contextVisibility = enumValue(
                json.optString("context_visibility"),
                GlobalWorldContextVisibility.SHAREABLE
            ),
            evidenceCount = json.optInt("evidence_count", 1).coerceAtLeast(1),
            conversationIds = json.optJSONArray("conversation_ids").strings().toSet(),
            evidenceEventIds = json.optJSONArray("evidence_event_ids").strings().takeLast(20),
            evidenceProvenance = decodeEvidenceRefs(json.optJSONArray("evidence_provenance")),
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
        .put("evidence_provenance", JSONArray().apply {
            link.evidenceProvenance.forEach { put(encodeEvidenceRef(it)) }
        })
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
            evidenceProvenance = decodeEvidenceRefs(json.optJSONArray("evidence_provenance")),
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
                    causalEventIds = json.optJSONArray("causal_event_ids").strings().toSet()
                        .ifEmpty { setOf(eventId) },
                    conversationId = json.optString("conversation_id"),
                    timestampMillis = json.optLong("timestamp_millis")
                ))
            }
        }
    }

    private fun encodeResearchTask(task: GlobalResearchTask): JSONObject = JSONObject()
        .put("id", task.id)
        .put("source_event_id", task.sourceEventId)
        .put("causal_event_ids", JSONArray(task.causalEventIds.toList()))
        .put("source_conversation_id", task.sourceConversationId)
        .put("topic", task.topic)
        .put("question", task.question)
        .put("depth", task.depth.name)
        .put("preferred_sources", JSONArray(task.preferredSources))
        .put("status", task.status.name)
        .put("resource_id", task.resourceId)
        .put("fallback_resource_ids", JSONArray(task.fallbackResourceIds))
        .put("attempted_resource_ids", JSONArray(task.attemptedResourceIds))
        .put("source_message_id", task.sourceMessageId)
        .put("attempt_count", task.attemptCount)
        .put("next_attempt_at_millis", task.nextAttemptAtMillis)
        .put("lease_expires_at_millis", task.leaseExpiresAtMillis)
        .put("monitor_interval_millis", task.monitorIntervalMillis)
        .put("last_completed_at_millis", task.lastCompletedAtMillis)
        .put("last_result_fingerprint", task.lastResultFingerprint)
        .put("last_error", task.lastError)
        .put("result", task.result)
        .put("evidence_uris", JSONArray(task.evidenceUris))
        .put("research_plan", encodeResearchPlan(task.researchPlan))
        .put("evidence_ledger", encodeEvidenceLedger(task.evidenceLedger))
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
            causalEventIds = json.optJSONArray("causal_event_ids").strings().toSet()
                .ifEmpty { setOf(sourceEventId) },
            sourceConversationId = json.optString("source_conversation_id"),
            topic = json.optString("topic"),
            question = json.optString("question"),
            depth = enumValue(json.optString("depth"), GlobalResearchDepth.QUICK_FACT),
            preferredSources = json.optJSONArray("preferred_sources").strings(),
            status = enumValue(json.optString("status"), GlobalResearchTaskStatus.QUEUED),
            resourceId = json.optString("resource_id"),
            fallbackResourceIds = json.optJSONArray("fallback_resource_ids").strings(),
            attemptedResourceIds = json.optJSONArray("attempted_resource_ids").strings(),
            sourceMessageId = json.optLong("source_message_id"),
            attemptCount = json.optInt("attempt_count"),
            nextAttemptAtMillis = json.optLong("next_attempt_at_millis"),
            leaseExpiresAtMillis = json.optLong("lease_expires_at_millis"),
            monitorIntervalMillis = json.optLong("monitor_interval_millis"),
            lastCompletedAtMillis = json.optLong("last_completed_at_millis"),
            lastResultFingerprint = json.optString("last_result_fingerprint"),
            lastError = json.optString("last_error"),
            result = json.optString("result"),
            evidenceUris = json.optJSONArray("evidence_uris").strings(),
            researchPlan = decodeResearchPlan(json.optJSONObject("research_plan")),
            evidenceLedger = decodeEvidenceLedger(json.optJSONObject("evidence_ledger")),
            createdAtMillis = json.optLong("created_at_millis"),
            updatedAtMillis = json.optLong("updated_at_millis")
        )
    }

    private fun encodeResearchPlan(plan: GlobalResearchPlan): JSONObject = JSONObject()
        .put("id", plan.id)
        .put("depth", plan.depth.name)
        .put("phase", plan.phase.name)
        .put("units", JSONArray().apply { plan.units.forEach { put(encodeResearchUnit(it)) } })
        .put("quality_expansion_count", plan.qualityExpansionCount)
        .put("synthesis_resource_id", plan.synthesisResourceId)
        .put("synthesis_source_message_id", plan.synthesisSourceMessageId)
        .put("synthesis_lease_expires_at_millis", plan.synthesisLeaseExpiresAtMillis)
        .put("synthesis_attempt_count", plan.synthesisAttemptCount)
        .put("created_at_millis", plan.createdAtMillis)
        .put("updated_at_millis", plan.updatedAtMillis)

    private fun decodeResearchPlan(json: JSONObject?): GlobalResearchPlan {
        if (json == null || json.optString("id").isBlank()) return GlobalResearchPlan()
        val units = json.optJSONArray("units")
        return GlobalResearchPlan(
            id = json.optString("id"),
            depth = enumValue(json.optString("depth"), GlobalResearchDepth.QUICK_FACT),
            phase = enumValue(json.optString("phase"), GlobalResearchPlanPhase.UNPLANNED),
            units = buildList {
                if (units != null) for (index in 0 until units.length()) {
                    decodeResearchUnit(units.optJSONObject(index))?.let(::add)
                }
            },
            qualityExpansionCount = json.optInt("quality_expansion_count"),
            synthesisResourceId = json.optString("synthesis_resource_id"),
            synthesisSourceMessageId = json.optLong("synthesis_source_message_id"),
            synthesisLeaseExpiresAtMillis = json.optLong("synthesis_lease_expires_at_millis"),
            synthesisAttemptCount = json.optInt("synthesis_attempt_count"),
            createdAtMillis = json.optLong("created_at_millis"),
            updatedAtMillis = json.optLong("updated_at_millis")
        )
    }

    private fun encodeResearchUnit(unit: GlobalResearchUnit): JSONObject = JSONObject()
        .put("id", unit.id)
        .put("purpose", unit.purpose.name)
        .put("question", unit.question)
        .put("source_focus", unit.sourceFocus)
        .put("query_candidates", JSONArray(unit.queryCandidates))
        .put("minimum_independent_sources", unit.minimumIndependentSources)
        .put("required_source_kinds", JSONArray(unit.requiredSourceKinds.map { it.name }))
        .put("freshness_window_millis", unit.freshnessWindowMillis)
        .put("status", unit.status.name)
        .put("resource_id", unit.resourceId)
        .put("attempted_resource_ids", JSONArray(unit.attemptedResourceIds))
        .put("source_message_id", unit.sourceMessageId)
        .put("attempt_count", unit.attemptCount)
        .put("lease_expires_at_millis", unit.leaseExpiresAtMillis)
        .put("result", unit.result)
        .put("evidence_uris", JSONArray(unit.evidenceUris))
        .put("last_error", unit.lastError)
        .put("started_at_millis", unit.startedAtMillis)
        .put("completed_at_millis", unit.completedAtMillis)

    private fun decodeResearchUnit(json: JSONObject?): GlobalResearchUnit? {
        if (json == null || json.optString("id").isBlank()) return null
        return GlobalResearchUnit(
            id = json.optString("id"),
            purpose = enumValue(json.optString("purpose"), GlobalResearchUnitPurpose.CURRENT_FACTS),
            question = json.optString("question"),
            sourceFocus = json.optString("source_focus"),
            queryCandidates = json.optJSONArray("query_candidates").strings(),
            minimumIndependentSources = json.optInt("minimum_independent_sources", 1).coerceAtLeast(1),
            requiredSourceKinds = json.optJSONArray("required_source_kinds").strings().mapNotNull { value ->
                runCatching { GlobalEvidenceSourceKind.valueOf(value) }.getOrNull()
            }.toSet(),
            freshnessWindowMillis = json.optLong("freshness_window_millis").coerceAtLeast(0L),
            status = enumValue(json.optString("status"), GlobalResearchUnitStatus.PENDING),
            resourceId = json.optString("resource_id"),
            attemptedResourceIds = json.optJSONArray("attempted_resource_ids").strings(),
            sourceMessageId = json.optLong("source_message_id"),
            attemptCount = json.optInt("attempt_count"),
            leaseExpiresAtMillis = json.optLong("lease_expires_at_millis"),
            result = json.optString("result"),
            evidenceUris = json.optJSONArray("evidence_uris").strings(),
            lastError = json.optString("last_error"),
            startedAtMillis = json.optLong("started_at_millis"),
            completedAtMillis = json.optLong("completed_at_millis")
        )
    }

    private fun encodeEvidenceLedger(ledger: GlobalEvidenceLedger): JSONObject = JSONObject()
        .put("sources", JSONArray().apply { ledger.sources.forEach { put(encodeEvidenceSource(it)) } })
        .put("claims", JSONArray().apply { ledger.claims.forEach { put(encodeEvidenceClaim(it)) } })
        .put("independent_source_count", ledger.independentSourceCount)
        .put("primary_source_count", ledger.primarySourceCount)
        .put("fresh_source_count", ledger.freshSourceCount)
        .put("stale_source_count", ledger.staleSourceCount)
        .put("undated_source_count", ledger.undatedSourceCount)
        .put("corroborated_claim_count", ledger.corroboratedClaimCount)
        .put("contested_claim_count", ledger.contestedClaimCount)
        .put("quality_issues", JSONArray(ledger.qualityIssues.map { it.name }))
        .put("overall_confidence", ledger.overallConfidence)
        .put("verified", ledger.verified)
        .put("updated_at_millis", ledger.updatedAtMillis)

    private fun decodeEvidenceLedger(json: JSONObject?): GlobalEvidenceLedger {
        if (json == null) return GlobalEvidenceLedger()
        val sources = json.optJSONArray("sources")
        val claims = json.optJSONArray("claims")
        return GlobalEvidenceLedger(
            sources = buildList {
                if (sources != null) for (index in 0 until sources.length()) {
                    decodeEvidenceSource(sources.optJSONObject(index))?.let(::add)
                }
            },
            claims = buildList {
                if (claims != null) for (index in 0 until claims.length()) {
                    decodeEvidenceClaim(claims.optJSONObject(index))?.let(::add)
                }
            },
            independentSourceCount = json.optInt("independent_source_count"),
            primarySourceCount = json.optInt("primary_source_count"),
            freshSourceCount = json.optInt("fresh_source_count"),
            staleSourceCount = json.optInt("stale_source_count"),
            undatedSourceCount = json.optInt("undated_source_count"),
            corroboratedClaimCount = json.optInt("corroborated_claim_count"),
            contestedClaimCount = json.optInt("contested_claim_count"),
            qualityIssues = json.optJSONArray("quality_issues").strings().mapNotNull { value ->
                runCatching { GlobalEvidenceQualityIssue.valueOf(value) }.getOrNull()
            }.toSet(),
            overallConfidence = json.optDouble("overall_confidence").coerceIn(0.0, 1.0),
            verified = json.optBoolean("verified"),
            updatedAtMillis = json.optLong("updated_at_millis")
        )
    }

    private fun encodeEvidenceSource(source: GlobalEvidenceSource): JSONObject = JSONObject()
        .put("uri", source.uri)
        .put("kind", source.kind.name)
        .put("quality_score", source.qualityScore)
        .put("authority", source.authority)
        .put("contributing_unit_ids", JSONArray(source.contributingUnitIds.toList()))
        .put("published_at_millis", source.publishedAtMillis)
        .put("freshness", source.freshness.name)
        .put("retrieved_at_millis", source.retrievedAtMillis)

    private fun decodeEvidenceSource(json: JSONObject?): GlobalEvidenceSource? {
        if (json == null || json.optString("uri").isBlank()) return null
        return GlobalEvidenceSource(
            uri = json.optString("uri"),
            kind = enumValue(json.optString("kind"), GlobalEvidenceSourceKind.UNKNOWN),
            qualityScore = json.optDouble("quality_score").coerceIn(0.0, 1.0),
            authority = json.optString("authority").ifBlank {
                GlobalEvidenceEvaluator.sourceAuthority(json.optString("uri"))
            },
            contributingUnitIds = json.optJSONArray("contributing_unit_ids").strings().toSet(),
            publishedAtMillis = json.optLong("published_at_millis"),
            freshness = enumValue(json.optString("freshness"), GlobalEvidenceFreshness.UNKNOWN),
            retrievedAtMillis = json.optLong("retrieved_at_millis")
        )
    }

    private fun encodeEvidenceClaim(claim: GlobalEvidenceClaim): JSONObject = JSONObject()
        .put("id", claim.id)
        .put("statement", claim.statement)
        .put("source_uris", JSONArray(claim.sourceUris.toList()))
        .put("contributing_unit_ids", JSONArray(claim.contributingUnitIds.toList()))
        .put("corroboration_count", claim.corroborationCount)
        .put("independent_source_count", claim.independentSourceCount)
        .put("primary_source_count", claim.primarySourceCount)
        .put("confidence", claim.confidence)
        .put("contested", claim.contested)

    private fun decodeEvidenceClaim(json: JSONObject?): GlobalEvidenceClaim? {
        if (json == null || json.optString("statement").isBlank()) return null
        return GlobalEvidenceClaim(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            statement = json.optString("statement"),
            sourceUris = json.optJSONArray("source_uris").strings().toSet(),
            contributingUnitIds = json.optJSONArray("contributing_unit_ids").strings().toSet(),
            corroborationCount = json.optInt("corroboration_count", 1).coerceAtLeast(1),
            independentSourceCount = json.optInt("independent_source_count"),
            primarySourceCount = json.optInt("primary_source_count"),
            confidence = json.optDouble("confidence").coerceIn(0.0, 1.0),
            contested = json.optBoolean("contested")
        )
    }

    private fun encodeProactiveMessage(message: GlobalProactiveMessage): JSONObject = JSONObject()
        .put("id", message.id)
        .put("source_event_id", message.sourceEventId)
        .put("causal_event_ids", JSONArray(message.causalEventIds.toList()))
        .put("source_conversation_id", message.sourceConversationId)
        .put("target", message.target.name)
        .put("title", message.title)
        .put("content", message.content)
        .put("topic", message.topic)
        .put("urgent", message.urgent)
        .put("status", message.status.name)
        .put("created_at_millis", message.createdAtMillis)
        .put("notified_at_millis", message.notifiedAtMillis)
        .put("delivery_conversation_id", message.deliveryConversationId)
        .put("delivery_lease_expires_at_millis", message.deliveryLeaseExpiresAtMillis)
        .put("delivery_attempt_count", message.deliveryAttemptCount)
        .put("delivery_budget_counted", message.deliveryBudgetCounted)
        .put("last_delivery_error", message.lastDeliveryError)
        .put("delivered_at_millis", message.deliveredAtMillis)
        .put("delivered_conversation_id", message.deliveredConversationId)
        .put("delivery_group_id", message.deliveryGroupId)
        .put("viewed_at_millis", message.viewedAtMillis)

    private fun decodeProactiveMessage(json: JSONObject?): GlobalProactiveMessage? {
        if (json == null) return null
        val id = json.optString("id")
        if (id.isBlank()) return null
        val status = enumValue(json.optString("status"), GlobalProactiveMessageStatus.PENDING)
        val createdAtMillis = json.optLong("created_at_millis")
        val notifiedAtMillis = if (json.has("notified_at_millis")) {
            json.optLong("notified_at_millis")
        } else if (status in setOf(
                GlobalProactiveMessageStatus.NOTIFIED,
                GlobalProactiveMessageStatus.DELIVERED
            )
        ) {
            json.optLong("delivered_at_millis").takeIf { it > 0L } ?: createdAtMillis
        } else 0L
        return GlobalProactiveMessage(
            id = id,
            sourceEventId = json.optString("source_event_id"),
            causalEventIds = json.optJSONArray("causal_event_ids").strings().toSet()
                .ifEmpty { setOf(json.optString("source_event_id")) },
            sourceConversationId = json.optString("source_conversation_id"),
            target = enumValue(json.optString("target"), GlobalProactiveTarget.CURRENT_CONVERSATION),
            title = json.optString("title"),
            content = json.optString("content"),
            topic = json.optString("topic"),
            urgent = json.optBoolean("urgent"),
            status = status,
            createdAtMillis = createdAtMillis,
            notifiedAtMillis = notifiedAtMillis,
            deliveryConversationId = json.optString("delivery_conversation_id"),
            deliveryLeaseExpiresAtMillis = json.optLong("delivery_lease_expires_at_millis"),
            deliveryAttemptCount = json.optInt("delivery_attempt_count").coerceAtLeast(0),
            deliveryBudgetCounted = if (json.has("delivery_budget_counted")) {
                json.optBoolean("delivery_budget_counted")
            } else {
                status == GlobalProactiveMessageStatus.NOTIFIED
            },
            lastDeliveryError = json.optString("last_delivery_error").take(600),
            deliveredAtMillis = json.optLong("delivered_at_millis"),
            deliveredConversationId = json.optString("delivered_conversation_id"),
            deliveryGroupId = json.optString("delivery_group_id"),
            viewedAtMillis = json.optLong("viewed_at_millis")
        )
    }

    private fun encodeFeedback(feedback: GlobalAgentFeedback): JSONObject = JSONObject()
        .put("id", feedback.id)
        .put("proactive_message_id", feedback.proactiveMessageId)
        .put("delivery_group_id", feedback.deliveryGroupId)
        .put("conversation_id", feedback.conversationId)
        .put("topic", feedback.topic)
        .put("target", feedback.target.name)
        .put("kind", feedback.kind.name)
        .put("created_at_millis", feedback.createdAtMillis)

    private fun decodeFeedback(json: JSONObject?): GlobalAgentFeedback? {
        if (json == null) return null
        val messageId = json.optString("proactive_message_id")
        if (messageId.isBlank()) return null
        return GlobalAgentFeedback(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            proactiveMessageId = messageId,
            deliveryGroupId = json.optString("delivery_group_id"),
            conversationId = json.optString("conversation_id"),
            topic = json.optString("topic"),
            target = enumValue(json.optString("target"), GlobalProactiveTarget.CURRENT_CONVERSATION),
            kind = enumValue(json.optString("kind"), GlobalAgentFeedbackKind.HELPFUL),
            createdAtMillis = json.optLong("created_at_millis")
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
            },
            countedDeliveryGroupIds = root.optJSONArray("counted_delivery_group_ids").strings()
                .takeLast(MAX_COUNTED_DELIVERY_GROUPS)
        )
    }.getOrDefault(GlobalInterventionHistory())

    private fun encodeInterventionHistory(history: GlobalInterventionHistory): JSONObject {
        val topics = JSONObject()
        history.lastTopicNotificationMillis.forEach(topics::put)
        return JSONObject()
            .put("notification_timestamps", JSONArray(history.notificationTimestamps.takeLast(100)))
            .put("last_topic_notification_millis", topics)
            .put(
                "counted_delivery_group_ids",
                JSONArray(history.countedDeliveryGroupIds.takeLast(MAX_COUNTED_DELIVERY_GROUPS))
            )
    }

    private fun encodeSettings(settings: GlobalAgentSettings): JSONObject = JSONObject()
        .put("enabled", settings.enabled)
        .put("proactive_insights_enabled", settings.proactiveInsightsEnabled)
        .put("proactive_discovery_enabled", settings.proactiveDiscoveryEnabled)
        .put("model_understanding_enabled", settings.modelUnderstandingEnabled)
        .put("autonomous_preparation_enabled", settings.autonomousPreparationEnabled)
        .put("autonomous_tool_execution_enabled", settings.autonomousToolExecutionEnabled)
        .put("dynamic_autonomous_replanning_enabled", settings.dynamicAutonomousReplanningEnabled)
        .put("long_horizon_planning_enabled", settings.longHorizonPlanningEnabled)
        .put("max_autonomous_replans", settings.maxAutonomousReplans)
        .put("allow_cloud_cognition", settings.allowCloudCognition)
        .put("autonomous_research_enabled", settings.autonomousResearchEnabled)
        .put("auto_create_conversations_enabled", settings.autoCreateConversationsEnabled)
        .put("notifications_enabled", settings.notificationsEnabled)
        .put("adaptive_learning_enabled", settings.adaptiveLearningEnabled)
        .put("daily_message_budget", settings.dailyMessageBudget)
        .put("daily_discovery_task_budget", settings.dailyDiscoveryTaskBudget)
        .put("topic_cooldown_millis", settings.topicCooldownMillis)
        .put("discovery_interval_millis", settings.discoveryIntervalMillis)
        .put("monitor_interval_millis", settings.monitorIntervalMillis)

    private fun decodeSettings(raw: String): GlobalAgentSettings = runCatching {
        if (raw.isBlank()) return@runCatching GlobalAgentSettings()
        val json = JSONObject(raw)
        GlobalAgentSettings(
            enabled = json.optBoolean("enabled", true),
            proactiveInsightsEnabled = json.optBoolean("proactive_insights_enabled", true),
            proactiveDiscoveryEnabled = json.optBoolean("proactive_discovery_enabled", true),
            modelUnderstandingEnabled = json.optBoolean("model_understanding_enabled", true),
            autonomousPreparationEnabled = json.optBoolean("autonomous_preparation_enabled", true),
            autonomousToolExecutionEnabled = json.optBoolean("autonomous_tool_execution_enabled", true),
            dynamicAutonomousReplanningEnabled = json.optBoolean("dynamic_autonomous_replanning_enabled", true),
            longHorizonPlanningEnabled = json.optBoolean("long_horizon_planning_enabled", true),
            maxAutonomousReplans = json.optInt("max_autonomous_replans", 3).coerceIn(1, 5),
            allowCloudCognition = json.optBoolean("allow_cloud_cognition", false),
            autonomousResearchEnabled = json.optBoolean("autonomous_research_enabled", true),
            autoCreateConversationsEnabled = json.optBoolean("auto_create_conversations_enabled", true),
            notificationsEnabled = json.optBoolean("notifications_enabled", true),
            adaptiveLearningEnabled = json.optBoolean("adaptive_learning_enabled", true),
            dailyMessageBudget = json.optInt("daily_message_budget", 4).coerceIn(0, 20),
            dailyDiscoveryTaskBudget = json.optInt("daily_discovery_task_budget", 3).coerceIn(1, 12),
            topicCooldownMillis = json.optLong("topic_cooldown_millis", 6L * 60L * 60L * 1_000L)
                .coerceIn(15L * 60L * 1_000L, 7L * 24L * 60L * 60L * 1_000L),
            discoveryIntervalMillis = GlobalProactiveDiscoveryPolicy.intervalMillis(
                json.optLong("discovery_interval_millis", 6L * 60L * 60L * 1_000L)
            ),
            monitorIntervalMillis = GlobalResearchTaskPolicy.monitorIntervalMillis(
                json.optLong("monitor_interval_millis")
            )
        )
    }.getOrDefault(GlobalAgentSettings())

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
        private const val KEY_CONTEXT_JOURNAL = "conversation_context_journal"
        private const val KEY_WORLD = "personal_world_model"
        private const val KEY_RESEARCH_TASKS = "research_tasks"
        private const val KEY_PROACTIVE_MESSAGES = "proactive_messages"
        private const val KEY_INTERVENTION_HISTORY = "intervention_history"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_PERSISTENT_CONTEXT_SYNC_VERSION = "persistent_context_sync_version"
        private const val KEY_FEEDBACK = "feedback"
        private const val KEY_CONVERSATION_TOMBSTONES = "conversation_tombstones"
        private const val MAX_PENDING_EVENTS = 2_000
        private const val MAX_PROCESS_BATCH = 250
        private const val MAX_RESEARCH_TASKS = 300
        private const val MAX_PROACTIVE_MESSAGES = 300
        private const val MAX_FEEDBACK_ITEMS = 800
        private const val MAX_COUNTED_DELIVERY_GROUPS = 800
        private const val MAX_CONVERSATION_TOMBSTONES = 500
        private const val MAX_EVENT_CONTENT_CHARACTERS = 12_000
        private val STORE_LOCK = Any()
    }
}

class GlobalSuperAgentRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val repository = GlobalAgentRepository(appContext)
    private val understandingPipeline = GlobalUnderstandingPipeline()
    private val researchExecutor by lazy { GlobalResearchExecutor(appContext) }
    private val cognitionExecutor by lazy { GlobalCognitionExecutor(appContext) }
    private val autonomousRunExecutor by lazy { GlobalAutonomousRunExecutor(appContext) }
    private val deliberationStore by lazy { GlobalAgentDeliberationStore(appContext) }
    private val longHorizonCoordinator by lazy { GlobalLongHorizonCoordinator(appContext) }
    private val longHorizonStore by lazy { GlobalLongHorizonGoalStore(appContext) }
    private val proactiveDiscoveryCoordinator by lazy { GlobalProactiveDiscoveryCoordinator(appContext) }
    private val realtimeContext by lazy { GlobalRealtimeContextProvider(appContext) }

    init {
        val settings = repository.settings()
        val initialDiscoveryDue = settings.proactiveDiscoveryEnabled &&
            settings.modelUnderstandingEnabled &&
            proactiveDiscoveryCoordinator.state().nextScanAtMillis <= 0L
        if (settings.enabled && (
                repository.persistentContextSyncVersion() < PERSISTENT_CONTEXT_SYNC_VERSION ||
                    initialDiscoveryDue
                )
        ) {
            GlobalConversationEventBus.requestProcessing(appContext)
        }
    }

    fun processPending(maxEvents: Int = 100): GlobalAgentProcessingBatch = synchronized(PROCESS_LOCK) {
        val settings = repository.settings()
        if (!settings.enabled) {
            return@synchronized GlobalAgentProcessingBatch(0, 0, emptyList(), emptyList())
        }
        synchronizePersistentContext()
        val events = repository.pendingEvents(maxEvents)
        if (events.isEmpty()) return@synchronized GlobalAgentProcessingBatch(0, 0, emptyList(), emptyList())
        var world = repository.loadWorld()
        var topicGraph = repository.topicGraph()
        val history = repository.interventionHistory()
        val adaptiveProfile = if (settings.adaptiveLearningEnabled) {
            GlobalAgentLearningPolicy.profile(repository.feedback())
        } else GlobalAgentAdaptiveProfile()
        val existingTasks = repository.researchTasks().toMutableList()
        val existingCognitionTasks = deliberationStore.cognitionTasks().toMutableList()
        val existingMessages = repository.proactiveMessages().toMutableList()
        val newTasks = mutableListOf<GlobalResearchTask>()
        val newCognitionTasks = mutableListOf<GlobalCognitionTask>()
        val newMessages = mutableListOf<GlobalProactiveMessage>()
        var researchTasksInvalidated = false
        var cognitionTasksInvalidated = false
        var proactiveMessagesInvalidated = false
        var changedItems = 0
        events.forEach { event ->
            if (GlobalConversationMergeLifecycle.valid(event)) {
                val sourceConversationId = GlobalConversationMergeLifecycle.sourceConversationId(event)
                val targetConversationId = GlobalConversationMergeLifecycle.targetConversationId(event)
                repository.markConversationDeleted(sourceConversationId)
                repository.markConversationCreated(targetConversationId)

                val reboundResearch = GlobalConversationMergeLifecycle.rebindResearchTasks(existingTasks, event)
                if (reboundResearch != existingTasks) {
                    existingTasks.clear()
                    existingTasks.addAll(reboundResearch)
                    researchTasksInvalidated = true
                }
                val reboundNewResearch = GlobalConversationMergeLifecycle.rebindResearchTasks(newTasks, event)
                if (reboundNewResearch != newTasks) {
                    newTasks.clear()
                    newTasks.addAll(reboundNewResearch)
                }
                val reboundCognition = GlobalConversationMergeLifecycle.rebindCognitionTasks(existingCognitionTasks, event)
                if (reboundCognition != existingCognitionTasks) {
                    existingCognitionTasks.clear()
                    existingCognitionTasks.addAll(reboundCognition)
                    cognitionTasksInvalidated = true
                }
                val reboundNewCognition = GlobalConversationMergeLifecycle.rebindCognitionTasks(newCognitionTasks, event)
                if (reboundNewCognition != newCognitionTasks) {
                    newCognitionTasks.clear()
                    newCognitionTasks.addAll(reboundNewCognition)
                }
                val reboundMessages = GlobalConversationMergeLifecycle.rebindProactiveMessages(existingMessages, event)
                if (reboundMessages != existingMessages) {
                    existingMessages.clear()
                    existingMessages.addAll(reboundMessages)
                    proactiveMessagesInvalidated = true
                }
                val reboundNewMessages = GlobalConversationMergeLifecycle.rebindProactiveMessages(newMessages, event)
                if (reboundNewMessages != newMessages) {
                    newMessages.clear()
                    newMessages.addAll(reboundNewMessages)
                }
                val currentRuns = deliberationStore.autonomousRuns()
                val reboundRuns = GlobalConversationMergeLifecycle.rebindAutonomousRuns(currentRuns, event)
                if (reboundRuns != currentRuns) deliberationStore.saveAutonomousRuns(reboundRuns)
                val currentGoals = longHorizonStore.goals()
                val reboundGoals = GlobalConversationMergeLifecycle.rebindLongHorizonGoals(currentGoals, event)
                if (reboundGoals != currentGoals) longHorizonStore.save(reboundGoals)
            }
            when (event.type) {
                GlobalConversationEventType.CONVERSATION_DELETED ->
                    repository.markConversationDeleted(event.conversationId)
                GlobalConversationEventType.CONVERSATION_CREATED ->
                    repository.markConversationCreated(event.conversationId)
                else -> Unit
            }
            val currentRunsForLifecycle = if (event.excludesConversationFromGlobalModel()) {
                deliberationStore.autonomousRuns()
            } else emptyList()
            val currentGoalsForLifecycle = if (event.excludesConversationFromGlobalModel()) {
                longHorizonStore.goals()
            } else emptyList()
            val conversationEvidence = if (event.excludesConversationFromGlobalModel()) {
                GlobalAgentEvidenceLifecyclePolicy.evidenceIdsForConversation(
                    conversationId = event.conversationId,
                    cognitionTasks = existingCognitionTasks + newCognitionTasks,
                    researchTasks = existingTasks + newTasks,
                    autonomousRuns = currentRunsForLifecycle,
                    proactiveMessages = existingMessages + newMessages,
                    longHorizonGoals = currentGoalsForLifecycle
                )
            } else emptySet()
            val retractedEvidence = event.effectiveRetractions() + conversationEvidence
            if (retractedEvidence.isNotEmpty()) {
                fun <T> replaceIfChanged(target: MutableList<T>, updated: List<T>): Boolean {
                    if (target == updated) return false
                    target.clear()
                    target.addAll(updated)
                    return true
                }
                researchTasksInvalidated = replaceIfChanged(
                    existingTasks,
                    GlobalAgentEvidenceLifecyclePolicy.invalidateResearchTasks(
                        existingTasks,
                        retractedEvidence,
                        event.timestampMillis
                    )
                ) || researchTasksInvalidated
                replaceIfChanged(
                    newTasks,
                    GlobalAgentEvidenceLifecyclePolicy.invalidateResearchTasks(
                        newTasks,
                        retractedEvidence,
                        event.timestampMillis
                    )
                )
                cognitionTasksInvalidated = replaceIfChanged(
                    existingCognitionTasks,
                    GlobalAgentEvidenceLifecyclePolicy.invalidateCognitionTasks(
                        existingCognitionTasks,
                        retractedEvidence,
                        event.timestampMillis
                    )
                ) || cognitionTasksInvalidated
                replaceIfChanged(
                    newCognitionTasks,
                    GlobalAgentEvidenceLifecyclePolicy.invalidateCognitionTasks(
                        newCognitionTasks,
                        retractedEvidence,
                        event.timestampMillis
                    )
                )
                proactiveMessagesInvalidated = replaceIfChanged(
                    existingMessages,
                    GlobalAgentEvidenceLifecyclePolicy.invalidateProactiveMessages(
                        existingMessages,
                        retractedEvidence
                    )
                ) || proactiveMessagesInvalidated
                replaceIfChanged(
                    newMessages,
                    GlobalAgentEvidenceLifecyclePolicy.invalidateProactiveMessages(
                        newMessages,
                        retractedEvidence
                    )
                )
                val currentRuns = currentRunsForLifecycle.ifEmpty { deliberationStore.autonomousRuns() }
                val invalidatedRuns = GlobalAgentEvidenceLifecyclePolicy.invalidateAutonomousRuns(
                    currentRuns,
                    retractedEvidence,
                    event.timestampMillis
                )
                if (invalidatedRuns != currentRuns) deliberationStore.saveAutonomousRuns(invalidatedRuns)
                val currentGoals = currentGoalsForLifecycle.ifEmpty { longHorizonStore.goals() }
                val invalidatedGoals = GlobalAgentEvidenceLifecyclePolicy.invalidateLongHorizonGoals(
                    currentGoals,
                    retractedEvidence,
                    event.timestampMillis
                )
                if (invalidatedGoals != currentGoals) longHorizonStore.save(invalidatedGoals)
            }
            val lifecycleRetraction = event.effectiveRetractions().isNotEmpty() ||
                event.excludesConversationFromGlobalModel()
            if (event.sensitivity == GlobalConversationSensitivity.SESSION_PRIVATE && !lifecycleRetraction) {
                world = world.copy(
                    processedEventIds = (world.processedEventIds + event.id).takeLast(4_000),
                    updatedAtMillis = event.timestampMillis
                )
                return@forEach
            }
            if (event.type == GlobalConversationEventType.TOOL_RESULT) {
                autonomousRunExecutor.consumeResearchEvent(event)
            }
            val understanding = understandingPipeline.understand(event, world)
            val reduction = GlobalWorldModelReducer.reduce(world, event, understanding)
            world = reduction.world
            topicGraph = GlobalTopicProjectGraphReducer.reduce(topicGraph, event, understanding, reduction)
            changedItems += reduction.changedItems.size
            val decision = GlobalInterventionPolicy.decide(
                event,
                understanding,
                reduction,
                history,
                settings = settings,
                adaptiveProfile = adaptiveProfile
            )
            val plannedCognition = if (settings.modelUnderstandingEnabled &&
                GlobalCognitionTaskPolicy.shouldDeliberate(event, understanding, reduction) &&
                (existingCognitionTasks + newCognitionTasks).none { it.sourceEvent.id == event.id }
            ) {
                GlobalCognitionTask(
                    sourceEvent = event,
                    baselineUnderstanding = understanding,
                    createdAtMillis = event.timestampMillis,
                    updatedAtMillis = event.timestampMillis
                )
            } else null
            plannedCognition?.let(newCognitionTasks::add)
            val plannedTask = if (decision.researchRequired || decision.autonomousPreparationAllowed) {
                GlobalResearchPlanner.plan(event, understanding)?.let { candidate ->
                    if (candidate.depth == GlobalResearchDepth.CONTINUOUS_MONITOR) {
                        candidate.copy(
                            monitorIntervalMillis = GlobalAgentLearningPolicy.monitorIntervalMillis(
                                settings,
                                adaptiveProfile,
                                candidate.topic
                            )
                        )
                    } else candidate
                }?.takeIf { candidate ->
                    (existingTasks + newTasks).none { task ->
                        task.status !in setOf(GlobalResearchTaskStatus.COMPLETED, GlobalResearchTaskStatus.FAILED) &&
                            GlobalAgentText.normalize(task.topic) == GlobalAgentText.normalize(candidate.topic) &&
                            GlobalAgentText.overlap(
                                GlobalAgentText.tokens(task.question),
                                GlobalAgentText.tokens(candidate.question)
                            ) >= 0.72
                    }
                }
            } else null
            plannedTask?.let(newTasks::add)
            val deferGenericMessageUntilResearch = plannedTask != null &&
                reduction.conflicts.isEmpty() &&
                understanding.riskCandidates.isEmpty() &&
                understanding.opportunityCandidates.isEmpty() &&
                decision.mode != GlobalInterventionMode.IMMEDIATE
            val deferGenericMessageUntilCognition = plannedCognition != null &&
                decision.mode != GlobalInterventionMode.IMMEDIATE
            if (!deferGenericMessageUntilResearch && !deferGenericMessageUntilCognition) {
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
        }
        repository.saveWorld(world)
        repository.saveTopicGraph(topicGraph)
        if (researchTasksInvalidated || newTasks.isNotEmpty()) {
            repository.saveResearchTasks(existingTasks + newTasks)
        }
        if (cognitionTasksInvalidated || newCognitionTasks.isNotEmpty()) {
            deliberationStore.saveCognitionTasks(existingCognitionTasks + newCognitionTasks)
        }
        if (proactiveMessagesInvalidated || newMessages.isNotEmpty()) {
            repository.saveProactiveMessages(existingMessages + newMessages)
        }
        repository.removeEvents(events.map(GlobalConversationEvent::id).toSet())
        GlobalAgentProcessingBatch(events.size, changedItems, newTasks, newMessages, newCognitionTasks)
    }

    fun augmentContext(context: AgentConversationContext, query: String): AgentConversationContext {
        if (context.privateMode) return context.copy(globalContext = "")
        val durableContext = GlobalAgentContextSelector.buildWithGraph(
            repository.loadWorld(),
            repository.topicGraph(),
            query,
            context.conversationId,
            maxCharacters = 5_500
        )
        val realtimeState = realtimeContext.build(
            query = query,
            currentConversationId = context.conversationId,
            maximumItems = 10,
            maximumCharacters = 2_500
        )
        return context.copy(globalContext = listOf(durableContext, realtimeState)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
            .take(8_000)
            .trim())
    }

    fun worldSnapshot(): PersonalWorldModel = repository.loadWorld()

    fun topicGraphSnapshot(): GlobalTopicProjectGraph = repository.topicGraph()

    fun researchTasks(): List<GlobalResearchTask> = repository.researchTasks()

    fun cognitionTasks(): List<GlobalCognitionTask> = deliberationStore.cognitionTasks()

    fun autonomousRuns(): List<GlobalAutonomousRun> = deliberationStore.autonomousRuns()

    fun longHorizonGoals(): List<GlobalLongHorizonGoal> = longHorizonCoordinator.goals()

    fun processLongHorizonCycle(): GlobalLongHorizonCycleResult = longHorizonCoordinator.processDue()

    fun processProactiveDiscoveryCycle(
        force: Boolean = false
    ): GlobalProactiveDiscoveryCycleResult = proactiveDiscoveryCoordinator.processDue(force = force)

    fun proactiveDiscoveryState(): GlobalProactiveDiscoveryState = proactiveDiscoveryCoordinator.state()

    fun pauseLongHorizonGoal(goalId: String): Boolean = longHorizonCoordinator.pause(goalId)

    fun resumeLongHorizonGoal(goalId: String): Boolean {
        val resumed = longHorizonCoordinator.resume(goalId)
        if (resumed) GlobalConversationEventBus.requestProcessing(appContext)
        return resumed
    }

    fun approveAutonomousRun(runId: String): Boolean {
        val now = System.currentTimeMillis()
        val updated = deliberationStore.updateAutonomousRun(runId) { run ->
            val actions = run.actions.map { action ->
                if (action.status == GlobalAutonomousActionStatus.WAITING_CONFIRMATION) {
                    action.copy(
                        confirmationGranted = true,
                        status = GlobalAutonomousActionStatus.PENDING,
                        lastError = ""
                    )
                } else action
            }
            run.copy(
                actions = actions,
                status = if (actions.any { it.status == GlobalAutonomousActionStatus.PENDING }) {
                    GlobalAutonomousRunStatus.QUEUED
                } else run.status,
                nextAttemptAtMillis = now,
                updatedAtMillis = now
            )
        } ?: return false
        if (updated.status == GlobalAutonomousRunStatus.QUEUED) {
            GlobalConversationEventBus.requestProcessing(appContext)
        }
        return true
    }

    fun rejectAutonomousRun(runId: String): Boolean {
        val now = System.currentTimeMillis()
        return deliberationStore.updateAutonomousRun(runId) { run ->
            val actions = run.actions.map { action ->
                if (action.status == GlobalAutonomousActionStatus.WAITING_CONFIRMATION) {
                    action.copy(
                        status = GlobalAutonomousActionStatus.SKIPPED,
                        lastError = "The user declined this external effect",
                        completedAtMillis = now
                    )
                } else action
            }
            run.copy(
                actions = actions,
                status = GlobalAutonomousRunStatus.PAUSED,
                updatedAtMillis = now
            )
        } != null
    }

    fun settings(): GlobalAgentSettings = repository.settings()

    fun adaptiveProfile(): GlobalAgentAdaptiveProfile =
        GlobalAgentLearningPolicy.profile(repository.feedback())

    fun recordProactiveFeedback(dedupeKey: String, kind: GlobalAgentFeedbackKind): Int {
        val normalizedKey = dedupeKey.trim()
        val digestPrefix = "global-agent-digest:"
        val messagePrefix = "global-agent:"
        val groupId = when {
            normalizedKey.startsWith(digestPrefix) -> normalizedKey.removePrefix(digestPrefix)
            normalizedKey.startsWith(messagePrefix) -> normalizedKey.removePrefix(messagePrefix)
            else -> return 0
        }
        if (groupId.isBlank()) return 0
        val allMessages = repository.proactiveMessages()
        val targets = if (normalizedKey.startsWith(digestPrefix)) {
            allMessages.filter { it.deliveryGroupId == groupId }
        } else {
            allMessages.filter { it.id == groupId }
        }
        if (targets.isEmpty()) return 0
        val now = System.currentTimeMillis()
        targets.forEach { message ->
            val feedback = GlobalAgentFeedback(
                proactiveMessageId = message.id,
                deliveryGroupId = message.deliveryGroupId.ifBlank { groupId },
                conversationId = message.deliveredConversationId.ifBlank { message.sourceConversationId },
                topic = message.topic,
                target = message.target,
                kind = kind,
                createdAtMillis = now
            )
            repository.replaceFeedback(feedback)
            repository.enqueue(GlobalConversationEvent(
                id = "global-feedback:${message.id}:${feedback.id}",
                type = GlobalConversationEventType.USER_FEEDBACK,
                conversationId = feedback.conversationId,
                messageId = message.id,
                actor = GlobalConversationActor.USER,
                timestampMillis = now,
                content = kind.name.lowercase(),
                contentRef = "encrypted://global-agent-feedback/${feedback.id}",
                conversationTitle = message.topic,
                topicHints = setOf(message.topic).filter(String::isNotBlank).toSet(),
                metadata = mapOf(
                    "feedback_kind" to kind.name,
                    "proactive_message_id" to message.id,
                    "delivery_group_id" to feedback.deliveryGroupId
                )
            ))
        }
        val ids = targets.map(GlobalProactiveMessage::id).toSet()
        repository.saveProactiveMessages(allMessages.map { message ->
            if (message.id !in ids) return@map message
            message.copy(
                status = when (kind) {
                    GlobalAgentFeedbackKind.HELPFUL -> if (message.deliveredAtMillis > 0L) {
                        GlobalProactiveMessageStatus.DELIVERED
                    } else GlobalProactiveMessageStatus.PENDING
                    GlobalAgentFeedbackKind.NOT_RELEVANT,
                    GlobalAgentFeedbackKind.TOO_FREQUENT -> GlobalProactiveMessageStatus.DISMISSED
                },
                viewedAtMillis = now
            )
        })
        GlobalConversationEventBus.requestProcessing(appContext)
        return targets.size
    }

    fun clearAdaptiveFeedback() {
        repository.saveFeedback(emptyList())
    }

    fun updateSettings(transform: (GlobalAgentSettings) -> GlobalAgentSettings): GlobalAgentSettings {
        val previous = repository.settings()
        val updated = transform(previous)
        repository.saveSettings(updated)
        if (updated.proactiveDiscoveryEnabled && updated.modelUnderstandingEnabled && (
                !previous.proactiveDiscoveryEnabled || !previous.modelUnderstandingEnabled
            )
        ) {
            proactiveDiscoveryCoordinator.requestImmediateScan()
        }
        if (updated.enabled) {
            if (!previous.enabled) repository.savePersistentContextSyncVersion(0)
            GlobalConversationEventBus.requestProcessing(appContext)
        }
        scheduleNextWake()
        return updated
    }

    private fun synchronizePersistentContext() {
        if (!repository.settings().enabled) return
        if (repository.persistentContextSyncVersion() >= PERSISTENT_CONTEXT_SYNC_VERSION) return
        val memorySnapshot = EncryptedAgentMemoryStore(appContext).snapshot()
        val memories = (
            memorySnapshot.activeItems +
                memorySnapshot.conflicts.flatMap(AgentMemoryConflict::candidates) +
                memorySnapshot.historyItems
            ).distinctBy(AgentMemoryItem::id)
        val knowledge = SharedPreferencesAgentKnowledgeStore(appContext).list(limit = 500)
        val now = System.currentTimeMillis()
        val events = GlobalPersistentContextObservationExtractor.memoryMutations(
            emptyList(),
            memories,
            now
        ) + GlobalPersistentContextObservationExtractor.knowledgeMutations(
            emptyList(),
            knowledge,
            now
        ) + buildList {
            add(GlobalCapabilityObservationExtractor.snapshotReset(now))
            addAll(GlobalCapabilityObservationExtractor.authorizationMutations(
                emptySet(),
                SharedPreferencesAgentConfirmationConsentStore(appContext).rememberedKeys(),
                now
            ))
            GlobalCapabilityObservationExtractor.safetyPolicyMutation(
                null,
                SharedPreferencesAgentSafetySettingsStore(appContext).load(),
                now
            )?.let(::add)
            addAll(GlobalCapabilityObservationExtractor.mcpMutations(
                emptyList(),
                EncryptedAgentMcpStore(appContext).list(),
                now
            ))
            addAll(GlobalCapabilityObservationExtractor.agentMutations(
                emptyList(),
                EncryptedAgentRegistry(appContext).list(now),
                now
            ))
            addAll(GlobalCapabilityObservationExtractor.homeAssistantMutations(
                HomeAssistantSettings(),
                HomeAssistantSettingsStore.load(appContext),
                now
            ))
            addAll(GlobalCapabilityObservationExtractor.customDeviceMutations(
                emptyList(),
                CustomDeviceConnectorStore(appContext).list(),
                now
            ))
            AgentResourceHealthStore(appContext).snapshots().forEach { (resourceId, health) ->
                GlobalCapabilityObservationExtractor.resourceHealthTransition(
                    resourceId,
                    AgentResourceHealth(),
                    health,
                    now
                )?.let(::add)
            }
        }
        repository.enqueueAll(events)
        repository.savePersistentContextSyncVersion(PERSISTENT_CONTEXT_SYNC_VERSION)
    }

    fun executeResearchCycle(): GlobalResearchExecutionResult? {
        val settings = repository.settings()
        if (!settings.enabled || !settings.autonomousResearchEnabled) return null
        return researchExecutor.executeNext()
    }

    fun executeCognitionCycle(): GlobalCognitionExecutionResult? {
        val settings = repository.settings()
        if (!settings.enabled || !settings.modelUnderstandingEnabled) return null
        return cognitionExecutor.executeNext()
    }

    fun executeAutonomousCycle(): GlobalAutonomousExecutionResult? {
        val settings = repository.settings()
        if (!settings.enabled || !settings.autonomousPreparationEnabled) return null
        return autonomousRunExecutor.executeNext()
    }

    fun scheduleNextWake(nowMillis: Long = System.currentTimeMillis()): Long {
        val settings = repository.settings()
        if (!settings.enabled) {
            GlobalAgentWakeScheduler.schedule(appContext, 0L)
            return 0L
        }
        val candidates = buildList {
            repository.researchTasks().forEach { task ->
                when (task.status) {
                    GlobalResearchTaskStatus.QUEUED -> add(nowMillis + MIN_WAKE_DELAY_MILLIS)
                    GlobalResearchTaskStatus.RUNNING -> task.leaseExpiresAtMillis.takeIf { it > 0L }?.let(::add)
                    GlobalResearchTaskStatus.SCHEDULED,
                    GlobalResearchTaskStatus.WAITING_FOR_RESOURCE -> task.nextAttemptAtMillis.takeIf { it > 0L }?.let(::add)
                    else -> Unit
                }
            }
            deliberationStore.cognitionTasks().forEach { task ->
                when (task.status) {
                    GlobalCognitionTaskStatus.QUEUED -> add(nowMillis + MIN_WAKE_DELAY_MILLIS)
                    GlobalCognitionTaskStatus.RUNNING -> task.leaseExpiresAtMillis.takeIf { it > 0L }?.let(::add)
                    GlobalCognitionTaskStatus.WAITING_FOR_RESOURCE -> task.nextAttemptAtMillis.takeIf { it > 0L }?.let(::add)
                    else -> Unit
                }
            }
            deliberationStore.autonomousRuns().forEach { run ->
                when (run.status) {
                    GlobalAutonomousRunStatus.QUEUED -> add(nowMillis + MIN_WAKE_DELAY_MILLIS)
                    GlobalAutonomousRunStatus.RUNNING -> {
                        if (GlobalAutonomousActionGraphPolicy.readyActions(run.actions).isNotEmpty()) {
                            add(nowMillis + MIN_WAKE_DELAY_MILLIS)
                        }
                        run.actions.asSequence()
                            .filter { it.status == GlobalAutonomousActionStatus.RUNNING }
                            .map(GlobalAutonomousAction::leaseExpiresAtMillis)
                            .filter { it > 0L }
                            .forEach(::add)
                    }
                    GlobalAutonomousRunStatus.REPLANNING -> {
                        val at = when (run.review.status) {
                            GlobalRunReviewStatus.RUNNING -> run.review.leaseExpiresAtMillis
                            GlobalRunReviewStatus.PENDING -> nowMillis + MIN_WAKE_DELAY_MILLIS
                            GlobalRunReviewStatus.WAITING_FOR_RESOURCE -> run.review.nextAttemptAtMillis
                            else -> run.nextAttemptAtMillis
                        }
                        at.takeIf { it > 0L }?.let(::add)
                    }
                    GlobalAutonomousRunStatus.WAITING_FOR_RESOURCE -> run.nextAttemptAtMillis.takeIf { it > 0L }?.let(::add)
                    else -> Unit
                }
            }
            if (settings.longHorizonPlanningEnabled) {
                longHorizonCoordinator.goals().asSequence()
                    .filter { it.status in setOf(GlobalLongHorizonGoalStatus.ACTIVE, GlobalLongHorizonGoalStatus.BLOCKED) }
                    .map(GlobalLongHorizonGoal::nextCheckAtMillis)
                    .filter { it > 0L }
                    .forEach { add(it) }
            }
            if (settings.proactiveDiscoveryEnabled && settings.modelUnderstandingEnabled) {
                proactiveDiscoveryCoordinator.nextWakeAt(nowMillis).takeIf { it > 0L }?.let(::add)
            }
            if (settings.proactiveInsightsEnabled) {
                val proactiveMessages = repository.proactiveMessages()
                val profile = adaptiveProfile()
                val history = repository.interventionHistory()
                val recoverableDigestCount = proactiveMessages.count {
                    it.target == GlobalProactiveTarget.GLOBAL_DIGEST &&
                        GlobalProactiveDeliveryPolicy.isRecoverable(it, nowMillis)
                }
                proactiveMessages.forEach { message ->
                    when (message.status) {
                        GlobalProactiveMessageStatus.DELIVERING ->
                            message.deliveryLeaseExpiresAtMillis.takeIf { it > 0L }?.let(::add)
                        GlobalProactiveMessageStatus.PENDING,
                        GlobalProactiveMessageStatus.NOTIFIED -> {
                            val eligibleAt = GlobalProactiveDeliveryPolicy.nextEligibleAtMillis(
                                message,
                                settings,
                                profile,
                                history,
                                nowMillis
                            )
                            if (eligibleAt > 0L) {
                                val digestReadyAt = if (
                                    message.target == GlobalProactiveTarget.GLOBAL_DIGEST &&
                                    recoverableDigestCount < MIN_DIGEST_ITEMS
                                ) message.createdAtMillis + DIGEST_MAX_WAIT_MILLIS else eligibleAt
                                add(maxOf(eligibleAt, digestReadyAt))
                            }
                        }
                        GlobalProactiveMessageStatus.DELIVERED,
                        GlobalProactiveMessageStatus.DISMISSED -> Unit
                    }
                }
            }
        }
        val next = candidates.minOrNull()?.coerceAtLeast(nowMillis + MIN_WAKE_DELAY_MILLIS) ?: 0L
        GlobalAgentWakeScheduler.schedule(appContext, next)
        return next
    }

    fun consumeConnectorResponse(response: AgentConnectorResponse): Boolean =
        cognitionExecutor.consumeConnectorResponse(response) ||
            autonomousRunExecutor.consumeConnectorResponse(response) ||
            researchExecutor.consumeConnectorResponse(response)

    fun consumeResearchResponse(response: AgentConnectorResponse): Boolean =
        consumeConnectorResponse(response)

    fun pendingProactiveMessages(nowMillis: Long = System.currentTimeMillis()): List<GlobalProactiveMessage> =
        repository.proactiveMessages()
            .filter { GlobalProactiveDeliveryPolicy.isRecoverable(it, nowMillis) }
            .sortedWith(
                compareByDescending<GlobalProactiveMessage> { it.urgent }
                    .thenBy(GlobalProactiveMessage::createdAtMillis)
            )

    fun proactiveInboxItems(limit: Int = 50): List<GlobalProactiveInboxItem> =
        GlobalProactiveInboxPolicy.project(
            messages = repository.proactiveMessages(),
            feedback = repository.feedback(),
            limit = limit
        )

    fun newProactiveInsightCount(): Int =
        GlobalProactiveInboxPolicy.newCount(proactiveInboxItems(limit = 100))

    fun markProactiveInboxViewed(messageIds: Set<String>): Int =
        repository.markProactiveViewed(messageIds, System.currentTimeMillis())

    fun notificationCandidateForDelivered(
        delivered: List<GlobalProactiveMessage>
    ): GlobalAgentNotificationCandidate? {
        val settings = repository.settings()
        if (!settings.enabled || !settings.proactiveInsightsEnabled || !settings.notificationsEnabled) return null
        val messages = delivered.filter { it.notifiedAtMillis <= 0L && it.deliveredConversationId.isNotBlank() }
        if (messages.isEmpty()) return null
        val primary = messages.lastOrNull(GlobalProactiveMessage::urgent) ?: messages.last()
        if (messages.size == 1) {
            return GlobalAgentNotificationCandidate(
                title = primary.title.ifBlank { "SignalASI" },
                content = primary.content.take(240),
                conversationId = primary.deliveredConversationId,
                messageIds = setOf(primary.id)
            )
        }
        val chinese = messages.any { GlobalAgentText.containsCjk(it.content) }
        val title = if (chinese) {
            "Signal \u6709 ${messages.size} \u6761\u65b0\u53d1\u73b0"
        } else {
            "Signal has ${messages.size} new insights"
        }
        val content = messages.asReversed()
            .distinctBy { GlobalAgentText.normalize(it.topic) }
            .take(3)
            .joinToString(" \u00b7 ") { it.topic.ifBlank { it.content.take(48) } }
        return GlobalAgentNotificationCandidate(
            title = title,
            content = content,
            conversationId = primary.deliveredConversationId,
            messageIds = messages.map(GlobalProactiveMessage::id).toSet()
        )
    }

    fun unnotifiedDeliveredMessages(limit: Int = 24): List<GlobalProactiveMessage> =
        repository.proactiveMessages()
            .asSequence()
            .filter {
                it.status == GlobalProactiveMessageStatus.DELIVERED &&
                    it.notifiedAtMillis <= 0L &&
                    it.deliveredConversationId.isNotBlank()
            }
            .sortedBy(GlobalProactiveMessage::deliveredAtMillis)
            .toList()
            .takeLast(limit.coerceIn(1, 100))

    fun markNotified(messageIds: Set<String>) {
        repository.markProactiveNotified(messageIds, System.currentTimeMillis())
    }

    fun deliverPending(
        transcriptStore: AgentTranscriptStore,
        nowMillis: Long = System.currentTimeMillis()
    ): List<GlobalProactiveMessage> = synchronized(PROCESS_LOCK) {
        val settings = repository.settings()
        retractInvalidatedDeliveryArtifacts(transcriptStore)
        if (!settings.enabled || !settings.proactiveInsightsEnabled) return@synchronized emptyList()
        val messages = pendingProactiveMessages(nowMillis)
        if (messages.isEmpty()) return@synchronized emptyList()
        val delivered = mutableListOf<GlobalProactiveMessage>()
        val excludedConversationIds = repository.excludedConversationIds()

        messages.asSequence()
            .filterNot { it.target == GlobalProactiveTarget.GLOBAL_DIGEST }
            .forEach { message ->
                val profile = adaptiveProfile()
                val history = repository.interventionHistory()
                if (!GlobalProactiveDeliveryPolicy.canDeliver(message, settings, profile, history, nowMillis)) {
                    return@forEach
                }
                val currentConversations = transcriptStore.conversations(includeArchived = true)
                val route = resolveTargetConversation(
                    transcriptStore = transcriptStore,
                    message = message,
                    conversations = currentConversations,
                    excludedConversationIds = excludedConversationIds,
                    settings = settings
                )
                if (route == null) {
                    val sourceIsUnavailable = currentConversations
                        .firstOrNull { it.id == message.sourceConversationId }
                        ?.let { !GlobalProactiveConversationRouter.isEligible(it) } == true
                    if (message.sourceConversationId in excludedConversationIds || sourceIsUnavailable) {
                        repository.dismissProactiveMessages(setOf(message.id), "Source conversation is unavailable")
                    }
                    return@forEach
                }
                val groupId = message.deliveryGroupId.ifBlank { message.id }
                val claimed = repository.claimProactiveMessages(
                    messageIds = setOf(message.id),
                    conversationId = route.conversationId,
                    deliveryGroupId = groupId,
                    nowMillis = nowMillis,
                    leaseMillis = PROACTIVE_DELIVERY_LEASE_MILLIS
                )
                if (claimed.isEmpty()) return@forEach
                val dedupeKey = "global-agent:${message.id}"
                val persisted = runCatching {
                    transcriptStore.append(
                        role = AgentTranscriptRole.ASSISTANT,
                        text = proactiveTranscriptText(message),
                        dedupeKey = dedupeKey,
                        conversationId = route.conversationId,
                        taskId = message.id
                    ) || transcriptStore.list(route.conversationId).any { it.dedupeKey == dedupeKey }
                }.getOrElse { error ->
                    repository.releaseProactiveDelivery(setOf(message.id), error.message.orEmpty())
                    false
                }
                if (!persisted) {
                    repository.releaseProactiveDelivery(setOf(message.id), "Transcript persistence failed")
                    return@forEach
                }
                delivered += repository.completeProactiveDelivery(
                    messageIds = setOf(message.id),
                    conversationId = route.conversationId,
                    deliveryGroupId = groupId,
                    countBudget = claimed.none(GlobalProactiveMessage::deliveryBudgetCounted),
                    nowMillis = nowMillis
                )
            }

        val currentMessages = pendingProactiveMessages(nowMillis)
        val staleDigestSources = currentMessages.asSequence()
            .filter { it.target == GlobalProactiveTarget.GLOBAL_DIGEST }
            .filter { it.sourceConversationId in excludedConversationIds }
            .map(GlobalProactiveMessage::id)
            .toSet()
        repository.dismissProactiveMessages(staleDigestSources, "Source conversation is unavailable")
        val digestMessages = GlobalProactiveDeliveryPolicy.digestBatch(
            messages = pendingProactiveMessages(nowMillis),
            settings = settings,
            profile = adaptiveProfile(),
            history = repository.interventionHistory(),
            nowMillis = nowMillis,
            minimumItems = MIN_DIGEST_ITEMS,
            maximumItems = MAX_DIGEST_ITEMS,
            maximumWaitMillis = DIGEST_MAX_WAIT_MILLIS
        )
        if (digestMessages.isNotEmpty()) {
            delivered += deliverDigest(transcriptStore, digestMessages, settings, nowMillis)
        }
        delivered
    }

    private fun retractInvalidatedDeliveryArtifacts(transcriptStore: AgentTranscriptStore) {
        val invalidated = repository.proactiveMessages().filter { message ->
            message.status == GlobalProactiveMessageStatus.DISMISSED &&
                message.deliveredAtMillis <= 0L &&
                message.deliveryConversationId.isNotBlank()
        }
        if (invalidated.isEmpty()) return
        invalidated.groupBy { message ->
            val dedupeKey = if (
                message.target == GlobalProactiveTarget.GLOBAL_DIGEST &&
                message.deliveryGroupId.isNotBlank()
            ) {
                "global-agent-digest:${message.deliveryGroupId}"
            } else {
                "global-agent:${message.id}"
            }
            message.deliveryConversationId to dedupeKey
        }.forEach { (target, _) ->
            transcriptStore.deleteByDedupeKey(target.first, target.second)
        }
        repository.markDeliveryArtifactsRetracted(invalidated.map(GlobalProactiveMessage::id).toSet())
    }

    private fun resolveTargetConversation(
        transcriptStore: AgentTranscriptStore,
        message: GlobalProactiveMessage,
        conversations: List<AgentConversation>,
        excludedConversationIds: Set<String>,
        settings: GlobalAgentSettings
    ): GlobalProactiveDeliveryRoute? {
        val relatedConversationIds = repository.topicGraph()
            .relevant(message.topic, message.sourceConversationId, 12)
            .flatMap(GlobalTopicNode::conversationIds)
            .distinct()
        val route = GlobalProactiveConversationRouter.resolve(
            message = message,
            conversations = conversations,
            relatedConversationIds = relatedConversationIds,
            autoCreateConversationsEnabled = settings.autoCreateConversationsEnabled,
            excludedConversationIds = excludedConversationIds
        ) ?: return null
        val resolved = if (route.createConversation) {
            val conversation = transcriptStore.createAgentConversation(
                title = route.title,
                parentConversationId = route.parentConversationId,
                globalTopicKey = route.topicKey
            )
            route.copy(conversationId = conversation.id, createConversation = false)
        } else route
        if (resolved.bindTopic) transcriptStore.bindGlobalTopic(resolved.conversationId, resolved.topicKey)
        return resolved
    }

    private fun deliverDigest(
        transcriptStore: AgentTranscriptStore,
        messages: List<GlobalProactiveMessage>,
        settings: GlobalAgentSettings,
        nowMillis: Long
    ): List<GlobalProactiveMessage> {
        val chinese = messages.any { GlobalAgentText.containsCjk(it.content) }
        val title = if (chinese) "Signal \u6458\u8981" else "Signal digest"
        val topicKey = GlobalProactiveConversationRouter.topicKey("global-digest")
        val target = transcriptStore.agentConversationForTopic(title, globalTopicKey = topicKey)
            ?: if (settings.autoCreateConversationsEnabled) {
                transcriptStore.createAgentConversation(title, globalTopicKey = topicKey)
            } else {
                transcriptStore.conversations(includeArchived = true)
                    .firstOrNull(GlobalProactiveConversationRouter::isEligible)
            }
            ?: return emptyList()
        val groupId = messages.firstNotNullOfOrNull { it.deliveryGroupId.takeIf(String::isNotBlank) }
            ?: GlobalAgentText.stableKey(*messages.map(GlobalProactiveMessage::id).sorted().toTypedArray())
        val messageIds = messages.map(GlobalProactiveMessage::id).toSet()
        val claimed = repository.claimProactiveMessages(
            messageIds = messageIds,
            conversationId = target.id,
            deliveryGroupId = groupId,
            nowMillis = nowMillis,
            leaseMillis = PROACTIVE_DELIVERY_LEASE_MILLIS
        )
        if (claimed.size != messageIds.size) return emptyList()
        val content = messages.mapIndexed { index, message ->
            val topicPrefix = message.topic.trim().takeIf(String::isNotBlank)?.let { "[$it] " }.orEmpty()
            "${index + 1}. $topicPrefix${message.content.trim()}"
        }.joinToString("\n\n").take(MAX_DIGEST_CHARACTERS)
        val dedupeKey = "global-agent-digest:$groupId"
        val persisted = runCatching {
            transcriptStore.append(
                role = AgentTranscriptRole.ASSISTANT,
                text = "$title\n\n$content",
                dedupeKey = dedupeKey,
                conversationId = target.id,
                taskId = "digest:$groupId"
            ) || transcriptStore.list(target.id).any { it.dedupeKey == dedupeKey }
        }.getOrElse { error ->
            repository.releaseProactiveDelivery(messageIds, error.message.orEmpty())
            false
        }
        if (!persisted) {
            repository.releaseProactiveDelivery(messageIds, "Transcript persistence failed")
            return emptyList()
        }
        return repository.completeProactiveDelivery(
            messageIds = messageIds,
            conversationId = target.id,
            deliveryGroupId = groupId,
            countBudget = claimed.none(GlobalProactiveMessage::deliveryBudgetCounted),
            nowMillis = nowMillis
        )
    }

    private fun proactiveTranscriptText(message: GlobalProactiveMessage): String = buildString {
        message.title.trim().takeIf(String::isNotBlank)?.let {
            append(it)
            append("\n\n")
        }
        append(message.content.trim())
    }

    fun dashboard(): GlobalAgentDashboardSnapshot {
        val world = repository.loadWorld()
        val topicGraph = repository.topicGraph()
        val active = world.items.filter { it.status == GlobalWorldItemStatus.ACTIVE }
        val profile = adaptiveProfile()
        val cognition = deliberationStore.cognitionTasks()
        val runs = deliberationStore.autonomousRuns()
        val longGoals = longHorizonCoordinator.goals()
        return GlobalAgentDashboardSnapshot(
            pendingEventCount = repository.pendingEvents(250).size,
            worldItemCount = active.size,
            topicCount = topicGraph.activeNodes().size.coerceAtLeast(
                active.map { GlobalAgentText.normalize(it.topic) }.filter(String::isNotBlank).distinct().size
            ),
            crossConversationLinkCount = topicGraph.relations.size.coerceAtLeast(world.links.size),
            activeGoalCount = active.count { it.kind == GlobalWorldItemKind.GOAL },
            activeTaskCount = active.count { it.kind == GlobalWorldItemKind.TASK },
            unresolvedConflictCount = world.items.count { it.status == GlobalWorldItemStatus.CONFLICTED } / 2,
            queuedResearchCount = repository.researchTasks().count {
                it.status in setOf(GlobalResearchTaskStatus.QUEUED, GlobalResearchTaskStatus.RUNNING, GlobalResearchTaskStatus.WAITING_FOR_RESOURCE)
            },
            queuedCognitionCount = cognition.count {
                it.status in setOf(
                    GlobalCognitionTaskStatus.QUEUED,
                    GlobalCognitionTaskStatus.RUNNING,
                    GlobalCognitionTaskStatus.WAITING_FOR_RESOURCE
                )
            },
            activeAutonomousRunCount = runs.count {
                it.status in setOf(
                    GlobalAutonomousRunStatus.QUEUED,
                    GlobalAutonomousRunStatus.RUNNING,
                    GlobalAutonomousRunStatus.REPLANNING,
                    GlobalAutonomousRunStatus.WAITING_FOR_RESOURCE
                )
            },
            replanningRunCount = runs.count { it.status == GlobalAutonomousRunStatus.REPLANNING },
            waitingConfirmationCount = runs.count { it.status == GlobalAutonomousRunStatus.WAITING_CONFIRMATION },
            longHorizonGoalCount = longGoals.count {
                it.status !in setOf(GlobalLongHorizonGoalStatus.COMPLETED, GlobalLongHorizonGoalStatus.PAUSED)
            },
            blockedLongHorizonGoalCount = longGoals.count { it.status == GlobalLongHorizonGoalStatus.BLOCKED },
            pendingInsightCount = repository.proactiveMessages().count {
                it.status in setOf(
                    GlobalProactiveMessageStatus.PENDING,
                    GlobalProactiveMessageStatus.NOTIFIED,
                    GlobalProactiveMessageStatus.DELIVERING
                )
            },
            feedbackCount = profile.sampleCount,
            learnedTopicCount = profile.topicAffinity.size,
            updatedAtMillis = world.updatedAtMillis
        )
    }

    fun clear() = repository.clear()

    companion object {
        private const val MESSAGE_DEDUPE_WINDOW_MILLIS = 6L * 60L * 60L * 1_000L
        private const val MIN_DIGEST_ITEMS = 3
        private const val MAX_DIGEST_ITEMS = 12
        private const val MAX_DIGEST_CHARACTERS = 12_000
        private const val DIGEST_MAX_WAIT_MILLIS = 12L * 60L * 60L * 1_000L
        private const val PROACTIVE_DELIVERY_LEASE_MILLIS = 2L * 60L * 1_000L
        private const val MIN_WAKE_DELAY_MILLIS = 60_000L
        private const val PERSISTENT_CONTEXT_SYNC_VERSION = 2
        private val PROCESS_LOCK = Any()
        @Volatile private var instance: GlobalSuperAgentRuntime? = null

        fun get(context: Context): GlobalSuperAgentRuntime = instance ?: synchronized(this) {
            instance ?: GlobalSuperAgentRuntime(context.applicationContext).also { instance = it }
        }
    }
}

object GlobalConversationEventBus {
    fun publishCapabilityEvents(
        context: Context,
        events: List<GlobalConversationEvent>
    ): Boolean {
        if (events.isEmpty()) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val accepted = repository.enqueueAll(events) > 0
        if (accepted) requestProcessing(context)
        return accepted
    }

    fun publishChatMessage(
        context: Context,
        contactId: String,
        contactName: String,
        messageId: Long,
        content: String,
        actor: GlobalConversationActor,
        timestampMillis: Long = System.currentTimeMillis(),
        metadata: Map<String, String> = emptyMap()
    ): Boolean {
        if (contactId.isBlank() || content.isBlank() || actor == GlobalConversationActor.SYSTEM) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val conversationId = "contact:${contactId.take(160)}"
        val event = GlobalConversationEvent(
            id = "chat:$contactId:$messageId",
            type = GlobalConversationEventType.MESSAGE_CREATED,
            conversationId = conversationId,
            messageId = messageId.toString(),
            actor = actor,
            timestampMillis = timestampMillis,
            content = content,
            contentRef = "encrypted://chat-history/$contactId/$messageId",
            conversationTitle = contactName.ifBlank { contactId }.take(160),
            topicHints = setOf(contactName.ifBlank { contactId }.take(160)),
            metadata = metadata + mapOf("contact_id" to contactId, "origin" to "contact_chat")
        )
        val enqueued = repository.enqueue(event)
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishTaskStatus(
        context: Context,
        contactId: String,
        taskId: String,
        sourceMessageId: Long,
        status: String,
        statusSequence: Long,
        detail: String
    ): Boolean {
        if (contactId.isBlank() || status.isBlank()) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val contactName = AppStore.contactById(context, contactId)
            ?.optString("name")
            .orEmpty()
            .ifBlank { contactId }
        val event = GlobalConversationEvent(
            id = "task:$contactId:${taskId.ifBlank { sourceMessageId.toString() }}:$status:$statusSequence",
            type = GlobalConversationEventType.TASK_UPDATED,
            conversationId = "contact:${contactId.take(160)}",
            messageId = sourceMessageId.toString(),
            actor = GlobalConversationActor.TOOL,
            content = "$contactName: ${detail.ifBlank { status }}",
            contentRef = "encrypted://chat-history/$contactId/$sourceMessageId",
            conversationTitle = contactName,
            topicHints = setOf(contactName),
            metadata = mapOf(
                "contact_id" to contactId,
                "task_id" to taskId,
                "task_status" to status,
                "status_sequence" to statusSequence.toString(),
                "origin" to "agent_task_event"
            )
        )
        val enqueued = repository.enqueue(event)
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishChatMessageDeleted(
        context: Context,
        contactId: String,
        messageId: Long,
        timestampMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (contactId.isBlank() || messageId <= 0L) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val event = GlobalConversationEvent(
            id = "chat-deleted:$contactId:$messageId:$timestampMillis",
            type = GlobalConversationEventType.MESSAGE_DELETED,
            conversationId = "contact:${contactId.take(160)}",
            messageId = messageId.toString(),
            actor = GlobalConversationActor.SYSTEM,
            metadata = mapOf(
                "deleted_event_id" to "chat:$contactId:$messageId",
                "contact_id" to contactId,
                "origin" to "contact_chat"
            ),
            retractedEventIds = setOf("chat:$contactId:$messageId")
        )
        val enqueued = repository.enqueue(event)
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishContactHistoryCleared(
        context: Context,
        contactId: String,
        contactName: String = "",
        timestampMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (contactId.isBlank()) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val event = GlobalConversationEvent(
            id = "contact-history-cleared:$contactId:$timestampMillis",
            type = GlobalConversationEventType.CONVERSATION_UPDATED,
            conversationId = "contact:${contactId.take(160)}",
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = timestampMillis,
            content = "",
            conversationTitle = contactName.ifBlank { contactId }.take(160),
            metadata = mapOf(
                "contact_id" to contactId,
                "origin" to "contact_history_lifecycle",
                "changed_fields" to "history",
                "global_visibility" to "excluded"
            )
        )
        val enqueued = repository.enqueue(event)
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishTranscriptEntry(
        context: Context,
        conversation: AgentConversation,
        entry: AgentTranscriptEntry,
        updated: Boolean = false,
        supersededEntryId: String = ""
    ): Boolean {
        if (conversation.trackingPaused || conversation.privateMode) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val privateMode = conversation.privateMode
        val actor = when {
            entry.dedupeKey.startsWith("global-agent:") -> GlobalConversationActor.GLOBAL_AGENT
            entry.role == AgentTranscriptRole.USER -> GlobalConversationActor.USER
            entry.role == AgentTranscriptRole.ASSISTANT -> GlobalConversationActor.ASSISTANT
            else -> GlobalConversationActor.TOOL
        }
        val eventType = GlobalRichObservationExtractor.transcriptEventType(entry, updated)
        val toolStatus = when (eventType) {
            GlobalConversationEventType.TOOL_STARTED -> "started"
            GlobalConversationEventType.TOOL_COMPLETED -> "completed"
            GlobalConversationEventType.TOOL_CANCELLED -> "cancelled"
            GlobalConversationEventType.TOOL_FAILED -> "failed"
            else -> ""
        }
        val event = GlobalConversationEvent(
            id = "transcript:${entry.id}",
            type = eventType,
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
                "superseded_event_id" to supersededEntryId.takeIf(String::isNotBlank)
                    ?.let { "transcript:$it" }.orEmpty(),
                "tool_status" to toolStatus,
                "tool_key" to entry.taskId.ifBlank { entry.dedupeKey }.take(160),
                "origin" to if (actor == GlobalConversationActor.GLOBAL_AGENT) "global_agent" else "conversation"
            ),
            retractedEventIds = supersededEntryId.takeIf(String::isNotBlank)
                ?.let { setOf("transcript:$it") }
                .orEmpty()
        )
        val observations = GlobalRichObservationExtractor.extract(conversation, entry, event.id)
        val enqueued = repository.enqueueAll(listOf(event) + observations) > 0
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishTranscriptEntryDeleted(
        context: Context,
        conversation: AgentConversation,
        entry: AgentTranscriptEntry,
        timestampMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (conversation.trackingPaused || conversation.privateMode) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val deletedEventId = "transcript:${entry.id}"
        val event = GlobalConversationEvent(
            id = "transcript-deleted:${entry.id}:$timestampMillis",
            type = GlobalConversationEventType.MESSAGE_DELETED,
            conversationId = entry.conversationId,
            messageId = entry.id,
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = timestampMillis,
            conversationTitle = conversation.title,
            sensitivity = if (conversation.privateMode) {
                GlobalConversationSensitivity.SESSION_PRIVATE
            } else GlobalConversationSensitivity.PERSONAL,
            metadata = mapOf(
                "deleted_event_id" to deletedEventId,
                "turn_id" to entry.turnId,
                "task_id" to entry.taskId,
                "role" to entry.role.name,
                "origin" to "conversation"
            ),
            retractedEventIds = setOf(deletedEventId)
        )
        val enqueued = repository.enqueue(event)
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishConversationDeleted(context: Context, conversation: AgentConversation): Boolean {
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val event = GlobalConversationEvent(
            id = "conversation-deleted:${conversation.id}:${System.currentTimeMillis()}",
            type = GlobalConversationEventType.CONVERSATION_DELETED,
            conversationId = conversation.id,
            actor = GlobalConversationActor.SYSTEM,
            content = "",
            conversationTitle = if (conversation.privateMode) "" else conversation.title,
            sensitivity = if (conversation.privateMode) {
                GlobalConversationSensitivity.SESSION_PRIVATE
            } else GlobalConversationSensitivity.PERSONAL
        )
        val enqueued = repository.enqueue(event)
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishConversationMerged(
        context: Context,
        result: AgentConversationMergeResult
    ): Boolean {
        if (!result.merged) return false
        val source = result.sourceConversation ?: return false
        val target = result.targetConversation ?: return false
        if (source.privateMode || target.privateMode) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val event = GlobalConversationEvent(
            id = "conversation-merged:${source.id}:${target.id}:${source.mergedAtMillis}",
            type = GlobalConversationEventType.CONVERSATION_MERGED,
            conversationId = target.id,
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = source.mergedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
            content = "",
            contentRef = "encrypted://agent-conversations/${target.id}",
            conversationTitle = target.title,
            topicHints = setOf(target.title, source.title).filterNot {
                it.equals("New session", ignoreCase = true)
            }.toSet(),
            sensitivity = conversationSensitivity(target),
            metadata = conversationMetadata(target) + mapOf(
                "origin" to "conversation_merge",
                GlobalConversationMergeLifecycle.SOURCE_CONVERSATION_ID to source.id,
                GlobalConversationMergeLifecycle.TARGET_CONVERSATION_ID to target.id,
                "source_conversation_title" to source.title,
                "copied_entry_count" to result.copiedEntryCount.toString(),
                "skipped_entry_count" to result.skippedEntryCount.toString()
            )
        )
        val enqueued = repository.enqueue(event)
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishConversationCreated(context: Context, conversation: AgentConversation): Boolean {
        if (conversation.privateMode || conversation.trackingPaused) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val privateMode = conversation.privateMode
        val event = GlobalConversationEvent(
            id = "conversation-created:${conversation.id}",
            type = GlobalConversationEventType.CONVERSATION_CREATED,
            conversationId = conversation.id,
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = conversation.createdAt,
            content = if (privateMode) "" else "Conversation created: ${conversation.title}",
            contentRef = "encrypted://agent-conversations/${conversation.id}",
            conversationTitle = if (privateMode) "" else conversation.title,
            topicHints = if (privateMode || conversation.title.equals("New session", ignoreCase = true)) {
                emptySet()
            } else {
                setOf(conversation.title)
            },
            sensitivity = conversationSensitivity(conversation),
            metadata = conversationMetadata(conversation) + mapOf("origin" to "conversation_lifecycle")
        )
        val enqueued = repository.enqueue(event)
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishConversationUpdated(
        context: Context,
        previous: AgentConversation,
        current: AgentConversation
    ): Boolean {
        val changes = conversationChanges(previous, current)
        if (changes.isEmpty()) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val privateMode = current.privateMode
        val fingerprint = GlobalAgentText.stableKey(
            current.id,
            changes.sorted().joinToString(","),
            current.title,
            current.status.name,
            current.pinned.toString(),
            current.privateMode.toString(),
            current.trackingPaused.toString(),
            current.selectedModelOrAgent,
            current.contextPolicy,
            current.updatedAt.toString()
        )
        val content = when {
            privateMode -> ""
            "title" in changes -> "Conversation renamed: ${previous.title} -> ${current.title}"
            else -> "Conversation updated: ${changes.sorted().joinToString(", ")}"
        }
        val event = GlobalConversationEvent(
            id = "conversation-updated:${current.id}:$fingerprint",
            type = GlobalConversationEventType.CONVERSATION_UPDATED,
            conversationId = current.id,
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = current.updatedAt,
            content = content,
            contentRef = "encrypted://agent-conversations/${current.id}",
            conversationTitle = if (privateMode) "" else current.title,
            topicHints = if (privateMode || current.title.equals("New session", ignoreCase = true)) {
                emptySet()
            } else {
                setOf(current.title)
            },
            sensitivity = conversationSensitivity(current),
            metadata = conversationMetadata(current) + mapOf(
                "origin" to "conversation_lifecycle",
                "changed_fields" to changes.sorted().joinToString(",")
            )
        )
        val enqueued = repository.enqueue(event)
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishRecordedRunStarted(
        context: Context,
        run: AgentRecordedRun,
        conversationTitle: String = ""
    ): Boolean {
        val conversation = AgentTranscriptStore(context).conversation(run.conversationId) ?: return false
        if (conversation.privateMode || conversation.trackingPaused) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val title = conversationTitle.ifBlank { conversation.title }
        val enqueued = repository.enqueue(GlobalRecordedRunObservationExtractor.started(run, title))
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishRecordedRunCompleted(
        context: Context,
        run: AgentRecordedRun,
        conversationTitle: String = ""
    ): Boolean {
        val conversation = AgentTranscriptStore(context).conversation(run.conversationId) ?: return false
        if (conversation.privateMode || conversation.trackingPaused) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val title = conversationTitle.ifBlank { conversation.title }
        val enqueued = repository.enqueueAll(
            GlobalRecordedRunObservationExtractor.completed(run, title)
        ) > 0
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishRecordedRunFeedback(
        context: Context,
        run: AgentRecordedRun,
        feedback: String,
        conversationTitle: String = ""
    ): Boolean {
        if (feedback.isBlank()) return false
        val conversation = AgentTranscriptStore(context).conversation(run.conversationId) ?: return false
        if (conversation.privateMode || conversation.trackingPaused) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val title = conversationTitle.ifBlank { conversation.title }
        val event = GlobalRecordedRunObservationExtractor.feedback(run, feedback, conversationTitle = title)
        val enqueued = repository.enqueue(event)
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishMemoryMutations(
        context: Context,
        before: List<AgentMemoryItem>,
        after: List<AgentMemoryItem>,
        timestampMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (before == after) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val transcriptStore by lazy { AgentTranscriptStore(context) }
        val events = GlobalPersistentContextObservationExtractor
            .memoryMutations(before, after, timestampMillis)
            .filterNot { event ->
                if (event.metadata["memory_scope"] != AgentMemoryScope.CONVERSATION.name) return@filterNot false
                val scopeId = event.metadata["memory_scope_id"].orEmpty()
                val conversation = transcriptStore.conversation(scopeId) ?: return@filterNot true
                conversation.privateMode || conversation.trackingPaused
            }
        val enqueued = repository.enqueueAll(events) > 0
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishKnowledgeMutations(
        context: Context,
        before: List<AgentKnowledgeItem>,
        after: List<AgentKnowledgeItem>,
        timestampMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (before == after) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
        val events = GlobalPersistentContextObservationExtractor.knowledgeMutations(
            before,
            after,
            timestampMillis
        )
        val enqueued = repository.enqueueAll(events) > 0
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    private fun conversationSensitivity(conversation: AgentConversation): GlobalConversationSensitivity =
        if (conversation.privateMode) {
            GlobalConversationSensitivity.SESSION_PRIVATE
        } else {
            GlobalConversationSensitivity.PERSONAL
        }

    private fun conversationMetadata(conversation: AgentConversation): Map<String, String> = mapOf(
        "conversation_status" to conversation.status.name.lowercase(Locale.ROOT),
        "pinned" to conversation.pinned.toString(),
        "private_mode" to conversation.privateMode.toString(),
        "tracking_paused" to conversation.trackingPaused.toString(),
        "global_visibility" to if (conversation.privateMode || conversation.trackingPaused) "excluded" else "included",
        "created_by_agent" to conversation.createdByAgent.toString(),
        "parent_conversation_id" to conversation.parentConversationId,
        "merged_into_conversation_id" to conversation.mergedIntoConversationId,
        "merged_at_millis" to conversation.mergedAtMillis.toString(),
        "selected_resource" to conversation.selectedModelOrAgent,
        "context_policy" to conversation.contextPolicy
    )

    private fun conversationChanges(
        previous: AgentConversation,
        current: AgentConversation
    ): Set<String> = buildSet {
        if (previous.title != current.title) add("title")
        if (previous.status != current.status) add("status")
        if (previous.pinned != current.pinned) add("pinned")
        if (previous.privateMode != current.privateMode) add("private_mode")
        if (previous.trackingPaused != current.trackingPaused) add("tracking_paused")
        if (previous.selectedModelOrAgent != current.selectedModelOrAgent) add("selected_resource")
        if (previous.contextPolicy != current.contextPolicy) add("context_policy")
        if (previous.createdByAgent != current.createdByAgent) add("created_by_agent")
        if (previous.parentConversationId != current.parentConversationId) add("parent_conversation_id")
        if (previous.mergedIntoConversationId != current.mergedIntoConversationId) add("merged_into_conversation_id")
        if (previous.mergedAtMillis != current.mergedAtMillis) add("merged_at_millis")
    }

    fun requestProcessing(context: Context) {
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
