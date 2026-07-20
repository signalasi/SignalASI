package com.signalasi.chat

object GlobalConversationMergeLifecycle {
    const val SOURCE_CONVERSATION_ID = "source_conversation_id"
    const val TARGET_CONVERSATION_ID = "target_conversation_id"

    fun sourceConversationId(event: GlobalConversationEvent): String =
        event.metadata[SOURCE_CONVERSATION_ID].orEmpty().trim()

    fun targetConversationId(event: GlobalConversationEvent): String =
        event.metadata[TARGET_CONVERSATION_ID].orEmpty().trim().ifBlank { event.conversationId }

    fun valid(event: GlobalConversationEvent): Boolean {
        if (event.type != GlobalConversationEventType.CONVERSATION_MERGED) return false
        val source = sourceConversationId(event)
        val target = targetConversationId(event)
        return source.isNotBlank() && target.isNotBlank() && source != target
    }

    fun rebindEvent(
        event: GlobalConversationEvent,
        sourceConversationId: String,
        targetConversationId: String
    ): GlobalConversationEvent = if (event.conversationId != sourceConversationId) {
        event
    } else {
        event.copy(
            conversationId = targetConversationId,
            metadata = event.metadata + mapOf(
                "merged_from_conversation_id" to sourceConversationId,
                TARGET_CONVERSATION_ID to targetConversationId
            )
        )
    }

    fun rebindWorld(
        world: PersonalWorldModel,
        event: GlobalConversationEvent
    ): PersonalWorldModel {
        if (!valid(event)) return world
        val source = sourceConversationId(event)
        val target = targetConversationId(event)
        val items = world.items.map { item ->
            item.copy(
                conversationIds = replaceConversation(item.conversationIds, source, target),
                evidenceProvenance = item.evidenceProvenance.map { it.rebind(source, target) }
            )
        }
        val links = world.links.mapNotNull { link ->
            val left = if (link.leftConversationId == source) target else link.leftConversationId
            val right = if (link.rightConversationId == source) target else link.rightConversationId
            if (left == right) null else link.copy(
                leftConversationId = left,
                rightConversationId = right,
                evidenceProvenance = link.evidenceProvenance.map { it.rebind(source, target) }
            )
        }.groupBy { link ->
            listOf(link.leftConversationId, link.rightConversationId).sorted().joinToString("|") +
                "|${GlobalAgentText.normalize(link.topic)}"
        }.values.map { matches ->
            val primary = matches.maxByOrNull(GlobalConversationLink::lastSeenAtMillis) ?: matches.first()
            val evidence = matches.flatMap(GlobalConversationLink::evidenceProvenance)
                .distinctBy(GlobalEvidenceRef::eventId)
                .takeLast(24)
            primary.copy(
                strength = matches.maxOf(GlobalConversationLink::strength),
                evidenceCount = evidence.size.coerceAtLeast(matches.maxOf(GlobalConversationLink::evidenceCount)),
                evidenceProvenance = evidence,
                lastSeenAtMillis = matches.maxOf(GlobalConversationLink::lastSeenAtMillis)
            )
        }
        return world.copy(
            items = items,
            links = links,
            processedEventIds = (world.processedEventIds + event.id).distinct().takeLast(4_000),
            updatedAtMillis = maxOf(world.updatedAtMillis, event.timestampMillis)
        )
    }

    fun rebindTopicGraph(
        graph: GlobalTopicProjectGraph,
        event: GlobalConversationEvent
    ): GlobalTopicProjectGraph {
        if (!valid(event)) return graph
        val source = sourceConversationId(event)
        val target = targetConversationId(event)
        return graph.copy(
            nodes = graph.nodes.map { node ->
                node.copy(
                    conversationIds = replaceConversation(node.conversationIds, source, target),
                    evidenceProvenance = node.evidenceProvenance.map { it.rebind(source, target) }
                )
            },
            relations = graph.relations.map { relation ->
                relation.copy(
                    evidenceProvenance = relation.evidenceProvenance.map { it.rebind(source, target) }
                )
            },
            updatedAtMillis = maxOf(graph.updatedAtMillis, event.timestampMillis)
        )
    }

    fun rebindResearchTasks(
        tasks: List<GlobalResearchTask>,
        event: GlobalConversationEvent
    ): List<GlobalResearchTask> = rebind(event, tasks) { task, source, target ->
        if (task.sourceConversationId == source) {
            task.copy(sourceConversationId = target, updatedAtMillis = maxOf(task.updatedAtMillis, event.timestampMillis))
        } else task
    }

    fun rebindCognitionTasks(
        tasks: List<GlobalCognitionTask>,
        event: GlobalConversationEvent
    ): List<GlobalCognitionTask> = rebind(event, tasks) { task, source, target ->
        val rebound = rebindEvent(task.sourceEvent, source, target)
        if (rebound == task.sourceEvent) task else task.copy(
            sourceEvent = rebound,
            updatedAtMillis = maxOf(task.updatedAtMillis, event.timestampMillis)
        )
    }

    fun rebindAutonomousRuns(
        runs: List<GlobalAutonomousRun>,
        event: GlobalConversationEvent
    ): List<GlobalAutonomousRun> = rebind(event, runs) { run, source, target ->
        if (run.sourceConversationId == source) {
            run.copy(sourceConversationId = target, updatedAtMillis = maxOf(run.updatedAtMillis, event.timestampMillis))
        } else run
    }

    fun rebindProactiveMessages(
        messages: List<GlobalProactiveMessage>,
        event: GlobalConversationEvent
    ): List<GlobalProactiveMessage> = rebind(event, messages) { message, source, target ->
        message.copy(
            sourceConversationId = message.sourceConversationId.replaceConversation(source, target),
            deliveryConversationId = message.deliveryConversationId.replaceConversation(source, target),
            deliveredConversationId = message.deliveredConversationId.replaceConversation(source, target)
        )
    }

    fun rebindLongHorizonGoals(
        goals: List<GlobalLongHorizonGoal>,
        event: GlobalConversationEvent
    ): List<GlobalLongHorizonGoal> = rebind(event, goals) { goal, source, target ->
        val conversations = replaceConversation(goal.sourceConversationIds, source, target)
        if (conversations == goal.sourceConversationIds) goal else goal.copy(
            sourceConversationIds = conversations,
            updatedAtMillis = maxOf(goal.updatedAtMillis, event.timestampMillis)
        )
    }

    fun rebindJournal(
        events: Collection<GlobalConversationEvent>,
        mergeEvent: GlobalConversationEvent
    ): List<GlobalConversationEvent> {
        if (!valid(mergeEvent)) return events.toList()
        val source = sourceConversationId(mergeEvent)
        val target = targetConversationId(mergeEvent)
        return events.map { rebindEvent(it, source, target) }
    }

    private inline fun <T> rebind(
        event: GlobalConversationEvent,
        values: List<T>,
        transform: (T, String, String) -> T
    ): List<T> {
        if (!valid(event)) return values
        val source = sourceConversationId(event)
        val target = targetConversationId(event)
        return values.map { transform(it, source, target) }
    }

    private fun replaceConversation(
        values: Set<String>,
        source: String,
        target: String
    ): Set<String> = values.mapTo(linkedSetOf()) { if (it == source) target else it }

    private fun String.replaceConversation(source: String, target: String): String =
        if (this == source) target else this

    private fun GlobalEvidenceRef.rebind(source: String, target: String): GlobalEvidenceRef =
        if (conversationId == source) copy(conversationId = target) else this
}
