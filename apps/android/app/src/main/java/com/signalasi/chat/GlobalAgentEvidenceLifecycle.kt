package com.signalasi.chat

object GlobalAgentEvidenceLifecyclePolicy {
    private const val INVALIDATED_REASON = "Source evidence was revised or deleted"

    fun evidenceIdsForConversation(
        conversationId: String,
        cognitionTasks: List<GlobalCognitionTask>,
        researchTasks: List<GlobalResearchTask>,
        autonomousRuns: List<GlobalAutonomousRun>,
        proactiveMessages: List<GlobalProactiveMessage>,
        longHorizonGoals: List<GlobalLongHorizonGoal>
    ): Set<String> {
        if (conversationId.isBlank()) return emptySet()
        return buildSet {
            cognitionTasks.asSequence()
                .filter {
                    it.sourceEvent.conversationId == conversationId ||
                        conversationId in it.sourceEvent.metadata["source_conversation_ids"]
                            .orEmpty().split(',').map(String::trim)
                }
                .forEach { addAll(it.sourceEvent.evidenceRoots()) }
            researchTasks.asSequence()
                .filter { it.sourceConversationId == conversationId }
                .forEach { addAll(it.causalEventIds.ifEmpty { setOf(it.sourceEventId) }) }
            autonomousRuns.asSequence()
                .filter { it.sourceConversationId == conversationId }
                .forEach { addAll(it.causalEventIds.ifEmpty { setOf(it.sourceEventId) }) }
            proactiveMessages.asSequence()
                .filter { it.sourceConversationId == conversationId }
                .forEach { addAll(it.causalEventIds.ifEmpty { setOf(it.sourceEventId) }) }
            longHorizonGoals.asSequence()
                .filter { conversationId in it.sourceConversationIds }
                .forEach { addAll(it.sourceEventIds) }
        }
    }

    fun invalidateCognitionTasks(
        tasks: List<GlobalCognitionTask>,
        eventIds: Set<String>,
        nowMillis: Long
    ): List<GlobalCognitionTask> = tasks.map { task ->
        if (!task.sourceEvent.evidenceRoots().intersects(eventIds)) return@map task
        task.copy(
            status = GlobalCognitionTaskStatus.FAILED,
            sourceMessageId = 0L,
            nextAttemptAtMillis = 0L,
            leaseExpiresAtMillis = 0L,
            lastError = INVALIDATED_REASON,
            updatedAtMillis = nowMillis
        )
    }

    fun invalidateResearchTasks(
        tasks: List<GlobalResearchTask>,
        eventIds: Set<String>,
        nowMillis: Long
    ): List<GlobalResearchTask> = tasks.map { task ->
        val roots = task.causalEventIds.ifEmpty { setOf(task.sourceEventId) }
        if (!roots.intersects(eventIds)) return@map task
        task.copy(
            status = GlobalResearchTaskStatus.FAILED,
            sourceMessageId = 0L,
            nextAttemptAtMillis = 0L,
            leaseExpiresAtMillis = 0L,
            lastError = INVALIDATED_REASON,
            researchPlan = task.researchPlan.copy(
                units = task.researchPlan.units.map { unit ->
                    if (unit.status in setOf(GlobalResearchUnitStatus.PENDING, GlobalResearchUnitStatus.RUNNING)) {
                        unit.copy(
                            status = GlobalResearchUnitStatus.FAILED,
                            sourceMessageId = 0L,
                            leaseExpiresAtMillis = 0L,
                            lastError = INVALIDATED_REASON,
                            completedAtMillis = nowMillis
                        )
                    } else unit
                },
                synthesisSourceMessageId = 0L,
                synthesisLeaseExpiresAtMillis = 0L,
                updatedAtMillis = nowMillis
            ),
            updatedAtMillis = nowMillis
        )
    }

    fun invalidateAutonomousRuns(
        runs: List<GlobalAutonomousRun>,
        eventIds: Set<String>,
        nowMillis: Long
    ): List<GlobalAutonomousRun> = runs.map { run ->
        val roots = run.causalEventIds.ifEmpty { setOf(run.sourceEventId) }
        if (!roots.intersects(eventIds)) return@map run
        run.copy(
            status = GlobalAutonomousRunStatus.PAUSED,
            actions = run.actions.map { action ->
                if (action.status in setOf(
                        GlobalAutonomousActionStatus.PENDING,
                        GlobalAutonomousActionStatus.RUNNING,
                        GlobalAutonomousActionStatus.WAITING_CONFIRMATION
                    )
                ) {
                    action.copy(
                        status = GlobalAutonomousActionStatus.SKIPPED,
                        sourceMessageId = 0L,
                        leaseExpiresAtMillis = 0L,
                        lastError = INVALIDATED_REASON,
                        completedAtMillis = nowMillis
                    )
                } else action
            },
            nextAttemptAtMillis = 0L,
            leaseExpiresAtMillis = 0L,
            lastError = INVALIDATED_REASON,
            updatedAtMillis = nowMillis
        )
    }

    fun invalidateProactiveMessages(
        messages: List<GlobalProactiveMessage>,
        eventIds: Set<String>
    ): List<GlobalProactiveMessage> = messages.map { message ->
        val roots = message.causalEventIds.ifEmpty { setOf(message.sourceEventId) }
        if (roots.intersects(eventIds) && message.status in setOf(
                GlobalProactiveMessageStatus.PENDING,
                GlobalProactiveMessageStatus.NOTIFIED,
                GlobalProactiveMessageStatus.DELIVERING
            )
        ) message.copy(
            status = GlobalProactiveMessageStatus.DISMISSED,
            deliveryLeaseExpiresAtMillis = 0L,
            lastDeliveryError = INVALIDATED_REASON
        ) else message
    }

    fun invalidateLongHorizonGoals(
        goals: List<GlobalLongHorizonGoal>,
        eventIds: Set<String>,
        nowMillis: Long
    ): List<GlobalLongHorizonGoal> {
        val retainedGoals = goals.mapNotNull { goal ->
            if (!goal.sourceEventIds.toSet().intersects(eventIds)) return@mapNotNull goal
            val retained = goal.sourceEventIds.filterNot(eventIds::contains)
            if (retained.isEmpty()) return@mapNotNull null
            goal.copy(
                sourceEventIds = retained,
                activeCognitionTaskId = "",
                activeRunId = "",
                nextCheckAtMillis = nowMillis,
                updatedAtMillis = nowMillis
            )
        }
        val retainedIds = retainedGoals.map(GlobalLongHorizonGoal::id).toSet()
        return GlobalLongHorizonGoalGraphPolicy.reconcile(
            retainedGoals.map { goal ->
                goal.copy(dependencyGoalIds = goal.dependencyGoalIds.intersect(retainedIds))
            },
            nowMillis
        )
    }

    private fun Set<String>.intersects(other: Set<String>): Boolean = any(other::contains)
}
