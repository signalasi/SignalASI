package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalAgentLongHorizonTest {
    @Test
    fun `plan review parser accepts bounded supported actions`() {
        val decision = GlobalRunReplanParser.parse(
            """{
              "goal_state":"ACTIVE",
              "summary":"A platform constraint changed the next step.",
              "cancel_action_ids":["obsolete"],
              "actions":[
                {"kind":"READ_ONLY_CHECK","goal":"Verify the current platform limit","priority":0.9},
                {"kind":"PAY","goal":"Buy more capacity","priority":1.0}
              ],
              "next_check_hours":6,
              "confidence":0.86
            }"""
        )

        assertNotNull(decision)
        assertEquals(GlobalGoalProgressState.ACTIVE, decision?.goalState)
        assertEquals(1, decision?.actions?.size)
        assertEquals(GlobalAutonomousActionKind.READ_ONLY_CHECK, decision?.actions?.first()?.kind)
        assertEquals(6, decision?.nextCheckHours)
    }

    @Test
    fun `invalid plan review prose cannot revise a run`() {
        assertNull(GlobalRunReplanParser.parse("The plan probably needs another step."))
    }

    @Test
    fun `discovery outcome triggers review when work remains`() {
        val action = action(GlobalAutonomousActionKind.READ_ONLY_CHECK, "Inspect constraints")
        val run = run(
            listOf(
                action,
                action(GlobalAutonomousActionKind.DRAFT, "Draft the implementation")
            )
        )

        assertTrue(GlobalAutonomousReplanPolicy.shouldReview(
            run,
            action,
            succeeded = true,
            result = "The constraints were verified",
            enabled = true,
            maxReplans = 3
        ))
    }

    @Test
    fun `ordinary successful final draft does not spend a review`() {
        val action = action(GlobalAutonomousActionKind.DRAFT, "Draft the implementation")
        val run = run(listOf(action))

        assertFalse(GlobalAutonomousReplanPolicy.shouldReview(
            run,
            action,
            succeeded = true,
            result = "Draft completed",
            enabled = true,
            maxReplans = 3
        ))
    }

    @Test
    fun `failed step triggers a new path review`() {
        val action = action(GlobalAutonomousActionKind.DRAFT, "Draft the implementation")

        assertTrue(GlobalAutonomousReplanPolicy.shouldReview(
            run(listOf(action)),
            action,
            succeeded = false,
            result = "The selected resource failed",
            enabled = true,
            maxReplans = 3
        ))
    }

    @Test
    fun `replan preserves completed evidence and normalizes host local work`() {
        val completed = action(GlobalAutonomousActionKind.ANALYZE, "Analyze constraints").copy(
            status = GlobalAutonomousActionStatus.COMPLETED,
            result = "Verified evidence"
        )
        val obsolete = action(GlobalAutonomousActionKind.DRAFT, "Draft obsolete design")
        val source = GlobalAutonomousReplanPolicy.requestReview(run(listOf(completed, obsolete)), "New evidence", 100L)
        val revised = GlobalAutonomousReplanPolicy.applyDecision(
            source,
            GlobalRunReplanDecision(
                summary = "Use the verified constraint in a revised design.",
                cancelActionIds = setOf(obsolete.id),
                actions = listOf(
                    GlobalAutonomousAction(
                        kind = GlobalAutonomousActionKind.CREATE_TOPIC,
                        goal = "Create an external project workspace",
                        externalEffect = true
                    )
                ),
                confidence = 0.9
            ),
            200L
        )

        assertEquals("Verified evidence", revised.actions.first { it.id == completed.id }.result)
        assertEquals(GlobalAutonomousActionStatus.SKIPPED, revised.actions.first { it.id == obsolete.id }.status)
        assertEquals(GlobalAutonomousActionStatus.PENDING, revised.actions.last().status)
        assertFalse(revised.actions.last().externalEffect)
        assertTrue(revised.actions.last().reversible)
        assertEquals(2, revised.revision)
        assertEquals(1, revised.replanCount)
    }

    @Test
    fun `expired plan review lease is recoverable without rerunning completed steps`() {
        val completed = action(GlobalAutonomousActionKind.ANALYZE, "Analyze").copy(
            status = GlobalAutonomousActionStatus.COMPLETED,
            result = "done"
        )
        val source = run(listOf(completed)).copy(
            status = GlobalAutonomousRunStatus.REPLANNING,
            review = GlobalAutonomousRunReview(
                status = GlobalRunReviewStatus.RUNNING,
                resourceId = "codex",
                sourceMessageId = 42L,
                leaseExpiresAtMillis = 100L
            )
        )

        val recovered = GlobalAutonomousReplanPolicy.recoverIfStale(source, 101L)

        assertEquals(GlobalRunReviewStatus.WAITING_FOR_RESOURCE, recovered.review.status)
        assertTrue("codex" in recovered.review.attemptedResourceIds)
        assertEquals("done", recovered.actions.single().result)
    }

    @Test
    fun `durable cognition creates a persistent long horizon goal`() {
        val task = cognition(
            durable = true,
            goal = "Ship a reliable on-device Agent runtime"
        )

        val goals = GlobalLongHorizonGoalPolicy.mergeCognition(task, emptyList(), 1_000L)

        assertEquals(1, goals.size)
        assertEquals("Ship a reliable on-device Agent runtime", goals.single().title)
        assertTrue(goals.single().nextCheckAtMillis > 1_000L)
    }

    @Test
    fun `non durable conversation does not become a long horizon goal`() {
        val task = cognition(durable = false, goal = "Answer this one question")

        assertTrue(GlobalLongHorizonGoalPolicy.mergeCognition(task, emptyList(), 1_000L).isEmpty())
    }

    @Test
    fun `repeated low cost world evidence creates a goal without model availability`() {
        val world = PersonalWorldModel(items = listOf(
            GlobalWorldItem(
                stableKey = "world-goal",
                kind = GlobalWorldItemKind.GOAL,
                layer = GlobalWorldLayer.TOPIC,
                topic = "SignalASI runtime",
                value = "Build a reliable on-device Agent runtime",
                confidence = 0.82,
                evidenceCount = 2,
                conversationIds = setOf("conversation-a", "conversation-b"),
                evidenceEventIds = listOf("event-a", "event-b")
            )
        ))

        val goals = GlobalLongHorizonGoalPolicy.mergeWorld(world, emptyList(), 1_000L)

        assertEquals(1, goals.size)
        assertEquals(2, goals.single().sourceConversationIds.size)
    }

    @Test
    fun `unchanged world evidence does not churn the durable goal store`() {
        val world = PersonalWorldModel(items = listOf(
            GlobalWorldItem(
                stableKey = "world-goal",
                kind = GlobalWorldItemKind.GOAL,
                layer = GlobalWorldLayer.TOPIC,
                topic = "SignalASI runtime",
                value = "Build the persistent global Agent",
                confidence = 0.84,
                evidenceCount = 2,
                conversationIds = setOf("conversation-a"),
                evidenceEventIds = listOf("event-a", "event-b")
            )
        ))
        val first = GlobalLongHorizonGoalPolicy.mergeWorld(world, emptyList(), 1_000L)

        val second = GlobalLongHorizonGoalPolicy.mergeWorld(world, first, 2_000L)

        assertEquals(first, second)
    }

    @Test
    fun `similar durable cognition updates one goal instead of duplicating it`() {
        val first = cognition(true, "Build a reliable on-device Agent runtime")
        val current = GlobalLongHorizonGoalPolicy.mergeCognition(first, emptyList(), 1_000L)
        val second = cognition(true, "Build a reliable on-device Agent runtime").copy(
            id = "cognition-2",
            sourceEvent = event("event-2", "Continue the same runtime project")
        )

        val merged = GlobalLongHorizonGoalPolicy.mergeCognition(second, current, 2_000L)

        assertEquals(1, merged.size)
        assertEquals(2, merged.single().sourceEventIds.size)
    }

    @Test
    fun `goal scheduler chooses urgent due goals before routine goals`() {
        val routine = goal("routine", 0.4, 100L)
        val urgent = goal("urgent", 0.95, 100L)
        val paused = goal("paused", 1.0, 50L).copy(status = GlobalLongHorizonGoalStatus.PAUSED)

        val due = GlobalLongHorizonGoalPolicy.nextDue(listOf(routine, paused, urgent), 101L)

        assertEquals(listOf("urgent", "routine"), due.map(GlobalLongHorizonGoal::title))
    }

    @Test
    fun `verified long horizon completion closes matching world goal`() {
        val item = GlobalWorldItem(
            stableKey = "world-goal",
            kind = GlobalWorldItemKind.GOAL,
            layer = GlobalWorldLayer.TOPIC,
            topic = "SignalASI",
            value = "Build the persistent global Agent",
            confidence = 0.8
        )
        val completed = goal("Build the persistent global Agent", 0.9, 0L).copy(
            topic = "SignalASI",
            status = GlobalLongHorizonGoalStatus.COMPLETED,
            confidence = 0.9,
            verifiedAtMillis = 1_500L
        )

        val world = GlobalLongHorizonGoalPolicy.applyGoalStatesToWorld(
            PersonalWorldModel(items = listOf(item)),
            listOf(completed),
            2_000L
        )

        assertEquals(GlobalWorldItemStatus.COMPLETED, world.items.single().status)
    }

    @Test
    fun `completed goal decision skips unnecessary remaining work`() {
        val completed = action(GlobalAutonomousActionKind.READ_ONLY_CHECK, "Verify completion").copy(
            status = GlobalAutonomousActionStatus.COMPLETED,
            result = "Goal is already satisfied",
            verificationStatus = GlobalActionVerificationStatus.SUPPORTED
        )
        val remaining = action(GlobalAutonomousActionKind.DRAFT, "Prepare another draft")
        val source = GlobalAutonomousReplanPolicy.requestReview(run(listOf(completed, remaining)), "Verify completion", 10L)

        val revised = GlobalAutonomousReplanPolicy.applyDecision(
            source,
            GlobalRunReplanDecision(
                goalState = GlobalGoalProgressState.COMPLETED,
                summary = "The verified result satisfies the goal.",
                confidence = 0.95
            ),
            20L
        )

        assertEquals(GlobalAutonomousRunStatus.COMPLETED, revised.status)
        assertEquals(GlobalAutonomousActionStatus.SKIPPED, revised.actions.first { it.id == remaining.id }.status)
    }

    private fun action(kind: GlobalAutonomousActionKind, goal: String) = GlobalAutonomousAction(
        kind = kind,
        goal = goal
    )

    private fun run(actions: List<GlobalAutonomousAction>) = GlobalAutonomousRun(
        sourceCognitionTaskId = "cognition",
        sourceEventId = "event",
        sourceConversationId = "conversation",
        topic = "SignalASI",
        goal = "Build the persistent Personal ASI",
        actions = actions
    )

    private fun cognition(durable: Boolean, goal: String): GlobalCognitionTask {
        val source = event("event-${goal.hashCode()}", goal)
        return GlobalCognitionTask(
            id = "cognition-${goal.hashCode()}",
            sourceEvent = source,
            baselineUnderstanding = GlobalUnderstanding(
                eventId = source.id,
                topic = "SignalASI",
                intent = "planning",
                goalCandidates = listOf(goal),
                complexity = 0.8,
                durableFollowUpUseful = durable
            ),
            status = GlobalCognitionTaskStatus.COMPLETED,
            result = GlobalModelUnderstanding(
                topic = "SignalASI",
                goals = listOf(goal),
                confidence = 0.9
            ),
            updatedAtMillis = 1_000L
        )
    }

    private fun event(id: String, content: String) = GlobalConversationEvent(
        id = id,
        type = GlobalConversationEventType.MESSAGE_CREATED,
        conversationId = "conversation",
        actor = GlobalConversationActor.USER,
        content = content,
        conversationTitle = "SignalASI"
    )

    private fun goal(title: String, priority: Double, dueAt: Long) = GlobalLongHorizonGoal(
        stableKey = title,
        topic = "SignalASI",
        title = title,
        priority = priority,
        nextCheckAtMillis = dueAt
    )
}
