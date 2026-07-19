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
    val sourceConversationId: String,
    val messageIds: Set<String>
)

class GlobalAgentRepository(context: Context) {
    private val database = AgentEncryptedDatabase(context.applicationContext, DATABASE_NAME)
    private val deliberationStore = GlobalAgentDeliberationStore(context.applicationContext)
    private val longHorizonStore = GlobalLongHorizonGoalStore(context.applicationContext)
    private val topicGraphStore = GlobalTopicProjectGraphStore(context.applicationContext)

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
            .put("version", 7)
            .put("events", JSONArray().apply { loadEvents().forEach { put(encodeEvent(it)) } })
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
            .put("feedback", JSONArray().apply { loadFeedback().forEach { put(encodeFeedback(it)) } })
    }

    fun restoreSnapshot(payload: JSONObject) = synchronized(STORE_LOCK) {
        payload.optJSONArray("events")?.let { array ->
            saveEvents(buildList {
                for (index in 0 until array.length()) decodeEvent(array.optJSONObject(index))?.let(::add)
            }.takeLast(MAX_PENDING_EVENTS))
        }
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
        payload.optJSONArray("feedback")?.let { array ->
            saveFeedback(buildList {
                for (index in 0 until array.length()) decodeFeedback(array.optJSONObject(index))?.let(::add)
            }.takeLast(MAX_FEEDBACK_ITEMS))
        }
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

    private fun loadFeedback(): List<GlobalAgentFeedback> = runCatching {
        val array = JSONArray(database.readString(KEY_FEEDBACK, "[]"))
        buildList {
            for (index in 0 until array.length()) decodeFeedback(array.optJSONObject(index))?.let(::add)
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
        .put("phase", plan.phase.name)
        .put("units", JSONArray().apply { plan.units.forEach { put(encodeResearchUnit(it)) } })
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
            phase = enumValue(json.optString("phase"), GlobalResearchPlanPhase.UNPLANNED),
            units = buildList {
                if (units != null) for (index in 0 until units.length()) {
                    decodeResearchUnit(units.optJSONObject(index))?.let(::add)
                }
            },
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
        .put("corroborated_claim_count", ledger.corroboratedClaimCount)
        .put("contested_claim_count", ledger.contestedClaimCount)
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
            corroboratedClaimCount = json.optInt("corroborated_claim_count"),
            contestedClaimCount = json.optInt("contested_claim_count"),
            overallConfidence = json.optDouble("overall_confidence").coerceIn(0.0, 1.0),
            verified = json.optBoolean("verified"),
            updatedAtMillis = json.optLong("updated_at_millis")
        )
    }

    private fun encodeEvidenceSource(source: GlobalEvidenceSource): JSONObject = JSONObject()
        .put("uri", source.uri)
        .put("kind", source.kind.name)
        .put("quality_score", source.qualityScore)
        .put("contributing_unit_ids", JSONArray(source.contributingUnitIds.toList()))
        .put("retrieved_at_millis", source.retrievedAtMillis)

    private fun decodeEvidenceSource(json: JSONObject?): GlobalEvidenceSource? {
        if (json == null || json.optString("uri").isBlank()) return null
        return GlobalEvidenceSource(
            uri = json.optString("uri"),
            kind = enumValue(json.optString("kind"), GlobalEvidenceSourceKind.UNKNOWN),
            qualityScore = json.optDouble("quality_score").coerceIn(0.0, 1.0),
            contributingUnitIds = json.optJSONArray("contributing_unit_ids").strings().toSet(),
            retrievedAtMillis = json.optLong("retrieved_at_millis")
        )
    }

    private fun encodeEvidenceClaim(claim: GlobalEvidenceClaim): JSONObject = JSONObject()
        .put("id", claim.id)
        .put("statement", claim.statement)
        .put("source_uris", JSONArray(claim.sourceUris.toList()))
        .put("contributing_unit_ids", JSONArray(claim.contributingUnitIds.toList()))
        .put("corroboration_count", claim.corroborationCount)
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
        .put("delivered_at_millis", message.deliveredAtMillis)
        .put("delivered_conversation_id", message.deliveredConversationId)
        .put("delivery_group_id", message.deliveryGroupId)

    private fun decodeProactiveMessage(json: JSONObject?): GlobalProactiveMessage? {
        if (json == null) return null
        val id = json.optString("id")
        if (id.isBlank()) return null
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
            status = enumValue(json.optString("status"), GlobalProactiveMessageStatus.PENDING),
            createdAtMillis = json.optLong("created_at_millis"),
            deliveredAtMillis = json.optLong("delivered_at_millis"),
            deliveredConversationId = json.optString("delivered_conversation_id"),
            deliveryGroupId = json.optString("delivery_group_id")
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
            }
        )
    }.getOrDefault(GlobalInterventionHistory())

    private fun encodeInterventionHistory(history: GlobalInterventionHistory): JSONObject {
        val topics = JSONObject()
        history.lastTopicNotificationMillis.forEach(topics::put)
        return JSONObject()
            .put("notification_timestamps", JSONArray(history.notificationTimestamps.takeLast(100)))
            .put("last_topic_notification_millis", topics)
    }

    private fun encodeSettings(settings: GlobalAgentSettings): JSONObject = JSONObject()
        .put("enabled", settings.enabled)
        .put("proactive_insights_enabled", settings.proactiveInsightsEnabled)
        .put("model_understanding_enabled", settings.modelUnderstandingEnabled)
        .put("autonomous_preparation_enabled", settings.autonomousPreparationEnabled)
        .put("dynamic_autonomous_replanning_enabled", settings.dynamicAutonomousReplanningEnabled)
        .put("long_horizon_planning_enabled", settings.longHorizonPlanningEnabled)
        .put("max_autonomous_replans", settings.maxAutonomousReplans)
        .put("allow_cloud_cognition", settings.allowCloudCognition)
        .put("autonomous_research_enabled", settings.autonomousResearchEnabled)
        .put("auto_create_conversations_enabled", settings.autoCreateConversationsEnabled)
        .put("notifications_enabled", settings.notificationsEnabled)
        .put("adaptive_learning_enabled", settings.adaptiveLearningEnabled)
        .put("daily_message_budget", settings.dailyMessageBudget)
        .put("topic_cooldown_millis", settings.topicCooldownMillis)
        .put("monitor_interval_millis", settings.monitorIntervalMillis)

    private fun decodeSettings(raw: String): GlobalAgentSettings = runCatching {
        if (raw.isBlank()) return@runCatching GlobalAgentSettings()
        val json = JSONObject(raw)
        GlobalAgentSettings(
            enabled = json.optBoolean("enabled", true),
            proactiveInsightsEnabled = json.optBoolean("proactive_insights_enabled", true),
            modelUnderstandingEnabled = json.optBoolean("model_understanding_enabled", true),
            autonomousPreparationEnabled = json.optBoolean("autonomous_preparation_enabled", true),
            dynamicAutonomousReplanningEnabled = json.optBoolean("dynamic_autonomous_replanning_enabled", true),
            longHorizonPlanningEnabled = json.optBoolean("long_horizon_planning_enabled", true),
            maxAutonomousReplans = json.optInt("max_autonomous_replans", 3).coerceIn(1, 5),
            allowCloudCognition = json.optBoolean("allow_cloud_cognition", false),
            autonomousResearchEnabled = json.optBoolean("autonomous_research_enabled", true),
            autoCreateConversationsEnabled = json.optBoolean("auto_create_conversations_enabled", true),
            notificationsEnabled = json.optBoolean("notifications_enabled", true),
            adaptiveLearningEnabled = json.optBoolean("adaptive_learning_enabled", true),
            dailyMessageBudget = json.optInt("daily_message_budget", 4).coerceIn(0, 20),
            topicCooldownMillis = json.optLong("topic_cooldown_millis", 6L * 60L * 60L * 1_000L)
                .coerceIn(15L * 60L * 1_000L, 7L * 24L * 60L * 60L * 1_000L),
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
        private const val KEY_WORLD = "personal_world_model"
        private const val KEY_RESEARCH_TASKS = "research_tasks"
        private const val KEY_PROACTIVE_MESSAGES = "proactive_messages"
        private const val KEY_INTERVENTION_HISTORY = "intervention_history"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_FEEDBACK = "feedback"
        private const val MAX_PENDING_EVENTS = 2_000
        private const val MAX_PROCESS_BATCH = 250
        private const val MAX_RESEARCH_TASKS = 300
        private const val MAX_PROACTIVE_MESSAGES = 300
        private const val MAX_FEEDBACK_ITEMS = 800
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

    fun processPending(maxEvents: Int = 100): GlobalAgentProcessingBatch = synchronized(PROCESS_LOCK) {
        val settings = repository.settings()
        if (!settings.enabled) {
            return@synchronized GlobalAgentProcessingBatch(0, 0, emptyList(), emptyList())
        }
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
            val retractedEvidence = event.effectiveRetractions()
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
                val currentRuns = deliberationStore.autonomousRuns()
                val invalidatedRuns = GlobalAgentEvidenceLifecyclePolicy.invalidateAutonomousRuns(
                    currentRuns,
                    retractedEvidence,
                    event.timestampMillis
                )
                if (invalidatedRuns != currentRuns) deliberationStore.saveAutonomousRuns(invalidatedRuns)
                val currentGoals = longHorizonStore.goals()
                val invalidatedGoals = GlobalAgentEvidenceLifecyclePolicy.invalidateLongHorizonGoals(
                    currentGoals,
                    retractedEvidence,
                    event.timestampMillis
                )
                if (invalidatedGoals != currentGoals) longHorizonStore.save(invalidatedGoals)
            }
            val lifecycleRetraction = event.effectiveRetractions().isNotEmpty() ||
                event.type == GlobalConversationEventType.CONVERSATION_DELETED
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
        val block = GlobalAgentContextSelector.buildWithGraph(
            repository.loadWorld(),
            repository.topicGraph(),
            query,
            context.conversationId
        )
        return context.copy(globalContext = block)
    }

    fun worldSnapshot(): PersonalWorldModel = repository.loadWorld()

    fun topicGraphSnapshot(): GlobalTopicProjectGraph = repository.topicGraph()

    fun researchTasks(): List<GlobalResearchTask> = repository.researchTasks()

    fun cognitionTasks(): List<GlobalCognitionTask> = deliberationStore.cognitionTasks()

    fun autonomousRuns(): List<GlobalAutonomousRun> = deliberationStore.autonomousRuns()

    fun longHorizonGoals(): List<GlobalLongHorizonGoal> = longHorizonCoordinator.goals()

    fun processLongHorizonCycle(): GlobalLongHorizonCycleResult = longHorizonCoordinator.processDue()

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
            message.copy(status = when (kind) {
                GlobalAgentFeedbackKind.HELPFUL -> if (message.deliveredAtMillis > 0L) {
                    GlobalProactiveMessageStatus.DELIVERED
                } else GlobalProactiveMessageStatus.PENDING
                GlobalAgentFeedbackKind.NOT_RELEVANT,
                GlobalAgentFeedbackKind.TOO_FREQUENT -> GlobalProactiveMessageStatus.DISMISSED
            })
        })
        GlobalConversationEventBus.requestProcessing(appContext)
        return targets.size
    }

    fun clearAdaptiveFeedback() {
        repository.saveFeedback(emptyList())
    }

    fun updateSettings(transform: (GlobalAgentSettings) -> GlobalAgentSettings): GlobalAgentSettings {
        val updated = transform(repository.settings())
        repository.saveSettings(updated)
        if (updated.enabled) GlobalConversationEventBus.requestProcessing(appContext)
        scheduleNextWake()
        return updated
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

    fun pendingProactiveMessages(): List<GlobalProactiveMessage> = repository.proactiveMessages()
        .filter { it.status in setOf(GlobalProactiveMessageStatus.PENDING, GlobalProactiveMessageStatus.NOTIFIED) }
        .sortedBy(GlobalProactiveMessage::createdAtMillis)

    fun nextNotificationCandidate(nowMillis: Long = System.currentTimeMillis()): GlobalAgentNotificationCandidate? {
        val settings = repository.settings()
        if (!settings.enabled || !settings.proactiveInsightsEnabled || !settings.notificationsEnabled) return null
        val pending = repository.proactiveMessages()
            .filter { it.status == GlobalProactiveMessageStatus.PENDING }
            .sortedBy(GlobalProactiveMessage::createdAtMillis)
        pending.lastOrNull { it.target != GlobalProactiveTarget.GLOBAL_DIGEST }?.let { message ->
            return GlobalAgentNotificationCandidate(
                title = message.title.ifBlank { "SignalASI" },
                content = message.content.take(240),
                sourceConversationId = message.sourceConversationId,
                messageIds = setOf(message.id)
            )
        }
        val digest = pending.filter { it.target == GlobalProactiveTarget.GLOBAL_DIGEST }
        val digestReady = digest.size >= MIN_DIGEST_ITEMS ||
            digest.firstOrNull()?.let { nowMillis - it.createdAtMillis >= DIGEST_MAX_WAIT_MILLIS } == true
        if (!digestReady) return null
        val chinese = digest.any { GlobalAgentText.containsCjk(it.content) }
        val title = if (chinese) "Signal \u6709 ${digest.size} \u6761\u65b0\u53d1\u73b0" else "Signal has ${digest.size} new insights"
        val content = digest
            .distinctBy { GlobalAgentText.normalize(it.topic) }
            .take(3)
            .joinToString(" \u00b7 ") { it.topic.ifBlank { it.content.take(48) } }
        return GlobalAgentNotificationCandidate(title, content, digest.first().sourceConversationId, digest.map { it.id }.toSet())
    }

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
                notificationTimestamps = (previous.notificationTimestamps + now).takeLast(100),
                lastTopicNotificationMillis = previous.lastTopicNotificationMillis +
                    notified.associate { GlobalAgentText.normalize(it.topic) to now }
            ))
        }
    }

    fun deliverPending(transcriptStore: AgentTranscriptStore): List<GlobalProactiveMessage> = synchronized(PROCESS_LOCK) {
        val settings = repository.settings()
        if (!settings.enabled || !settings.proactiveInsightsEnabled) return@synchronized emptyList()
        val messages = pendingProactiveMessages()
        if (messages.isEmpty()) return@synchronized emptyList()
        val delivered = mutableListOf<GlobalProactiveMessage>()
        messages.filterNot { it.target == GlobalProactiveTarget.GLOBAL_DIGEST }.forEach { message ->
            val targetConversationId = resolveTargetConversation(transcriptStore, message)
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
                    deliveredAtMillis = System.currentTimeMillis(),
                    deliveredConversationId = targetConversationId,
                    deliveryGroupId = message.id
                )
            }
        }
        val digestMessages = messages.filter { it.target == GlobalProactiveTarget.GLOBAL_DIGEST }
        val digestReady = digestMessages.size >= MIN_DIGEST_ITEMS ||
            digestMessages.minOfOrNull(GlobalProactiveMessage::createdAtMillis)?.let {
                System.currentTimeMillis() - it >= DIGEST_MAX_WAIT_MILLIS
            } == true
        if (digestReady) {
            val grouped = digestMessages
                .sortedByDescending(GlobalProactiveMessage::createdAtMillis)
                .distinctBy { GlobalAgentText.normalize(it.topic) }
                .take(MAX_DIGEST_ITEMS)
                .sortedBy(GlobalProactiveMessage::createdAtMillis)
            val chinese = grouped.any { GlobalAgentText.containsCjk(it.content) }
            val title = if (chinese) "Signal \u6458\u8981" else "Signal digest"
            val content = grouped.mapIndexed { index, message ->
                "${index + 1}. ${message.content.trim()}"
            }.joinToString("\n\n").take(MAX_DIGEST_CHARACTERS)
            val digestKey = GlobalAgentText.stableKey(*digestMessages.map(GlobalProactiveMessage::id).sorted().toTypedArray())
            val targetConversationId = transcriptStore.agentConversationForTopic(title)?.id
                ?: transcriptStore.createAgentConversation(title).id
            val persisted = transcriptStore.append(
                role = AgentTranscriptRole.ASSISTANT,
                text = "$title\n\n$content",
                dedupeKey = "global-agent-digest:$digestKey",
                conversationId = targetConversationId,
                taskId = "digest:$digestKey"
            )
            if (persisted || transcriptStore.list(targetConversationId).any {
                    it.dedupeKey == "global-agent-digest:$digestKey"
                }
            ) {
                val deliveredAt = System.currentTimeMillis()
                delivered += digestMessages.map { message ->
                    message.copy(
                        status = GlobalProactiveMessageStatus.DELIVERED,
                        deliveredAtMillis = deliveredAt,
                        deliveredConversationId = targetConversationId,
                        deliveryGroupId = digestKey
                    )
                }
            }
        }
        if (delivered.isNotEmpty()) {
            val deliveredById = delivered.associateBy(GlobalProactiveMessage::id)
            repository.saveProactiveMessages(repository.proactiveMessages().map { deliveredById[it.id] ?: it })
            val countedOnDelivery = delivered.filter { deliveredMessage ->
                messages.firstOrNull { it.id == deliveredMessage.id }?.status == GlobalProactiveMessageStatus.PENDING
            }
            if (countedOnDelivery.isNotEmpty()) {
                val now = countedOnDelivery.maxOf(GlobalProactiveMessage::deliveredAtMillis)
                val previous = repository.interventionHistory()
                repository.saveInterventionHistory(previous.copy(
                    notificationTimestamps = (previous.notificationTimestamps + now).takeLast(100),
                    lastTopicNotificationMillis = previous.lastTopicNotificationMillis +
                        countedOnDelivery.associate { GlobalAgentText.normalize(it.topic) to now }
                ))
            }
        }
        delivered
    }

    private fun resolveTargetConversation(
        transcriptStore: AgentTranscriptStore,
        message: GlobalProactiveMessage
    ): String {
        val settings = repository.settings()
        return when (message.target) {
        GlobalProactiveTarget.CURRENT_CONVERSATION -> transcriptStore.conversations(includeArchived = true)
            .firstOrNull {
                it.id == message.sourceConversationId &&
                    it.status == AgentConversationStatus.ACTIVE &&
                    !it.trackingPaused
            }
            ?.id
            ?: transcriptStore.agentConversationForTopic(message.topic)?.id
            ?: transcriptStore.createAgentConversation(message.topic).id
        GlobalProactiveTarget.NEW_CONVERSATION -> if (settings.autoCreateConversationsEnabled) {
            transcriptStore.agentConversationForTopic(message.topic)?.id
                ?: transcriptStore.createAgentConversation(
                    title = message.topic,
                    parentConversationId = message.sourceConversationId
                ).id
        } else {
            transcriptStore.conversations(includeArchived = true)
                .firstOrNull { it.id == message.sourceConversationId && it.status == AgentConversationStatus.ACTIVE }
                ?.id
                ?: transcriptStore.activeConversation().id
        }
        GlobalProactiveTarget.GLOBAL_DIGEST -> error("Digest messages are delivered as a merged batch")
        }
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
            pendingInsightCount = pendingProactiveMessages().size,
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
        private const val MIN_WAKE_DELAY_MILLIS = 60_000L
        private val PROCESS_LOCK = Any()
        @Volatile private var instance: GlobalSuperAgentRuntime? = null

        fun get(context: Context): GlobalSuperAgentRuntime = instance ?: synchronized(this) {
            instance ?: GlobalSuperAgentRuntime(context.applicationContext).also { instance = it }
        }
    }
}

object GlobalConversationEventBus {
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

    fun publishTranscriptEntry(
        context: Context,
        conversation: AgentConversation,
        entry: AgentTranscriptEntry,
        updated: Boolean = false,
        supersededEntryId: String = ""
    ): Boolean {
        if (conversation.trackingPaused) return false
        val repository = GlobalAgentRepository(context)
        if (!repository.settings().enabled) return false
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
                "superseded_event_id" to supersededEntryId.takeIf(String::isNotBlank)
                    ?.let { "transcript:$it" }.orEmpty(),
                "origin" to if (actor == GlobalConversationActor.GLOBAL_AGENT) "global_agent" else "conversation"
            ),
            retractedEventIds = supersededEntryId.takeIf(String::isNotBlank)
                ?.let { setOf("transcript:$it") }
                .orEmpty()
        )
        val enqueued = repository.enqueue(event)
        if (enqueued) requestProcessing(context)
        return enqueued
    }

    fun publishTranscriptEntryDeleted(
        context: Context,
        conversation: AgentConversation,
        entry: AgentTranscriptEntry,
        timestampMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (conversation.trackingPaused) return false
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
            conversationTitle = conversation.title,
            sensitivity = if (conversation.privateMode) {
                GlobalConversationSensitivity.SESSION_PRIVATE
            } else GlobalConversationSensitivity.PERSONAL
        )
        val enqueued = repository.enqueue(event)
        if (enqueued) requestProcessing(context)
        return enqueued
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
