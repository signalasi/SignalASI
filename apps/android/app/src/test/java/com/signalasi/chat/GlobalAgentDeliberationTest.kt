package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalAgentDeliberationTest {
    @Test
    fun `trivial conversation stays on low cost understanding path`() {
        val event = event("hello")
        val understanding = understanding(event, complexity = 0.05)

        assertFalse(GlobalCognitionTaskPolicy.shouldDeliberate(
            event,
            understanding,
            GlobalWorldReduction(PersonalWorldModel(), emptyList(), emptyList())
        ))
    }

    @Test
    fun `cross conversation work is queued for model deliberation`() {
        val event = event("Compare this architecture with the decision from the runtime conversation")
        val understanding = understanding(
            event,
            complexity = 0.48,
            crossConversationIds = setOf("conversation-b")
        )

        assertTrue(GlobalCognitionTaskPolicy.shouldDeliberate(
            event,
            understanding,
            GlobalWorldReduction(PersonalWorldModel(), emptyList(), emptyList())
        ))
    }

    @Test
    fun `global worker results do not recursively queue more cognition`() {
        val source = event("Autonomous research completed with a durable project recommendation").copy(
            type = GlobalConversationEventType.TOOL_RESULT,
            actor = GlobalConversationActor.TOOL,
            metadata = mapOf("autonomous_run_id" to "run-1")
        )

        assertFalse(GlobalCognitionTaskPolicy.shouldDeliberate(
            source,
            understanding(source, complexity = 0.9),
            GlobalWorldReduction(PersonalWorldModel(), emptyList(), emptyList())
        ))
    }

    @Test
    fun `structured cognition parser accepts only supported safe action vocabulary`() {
        val parsed = GlobalModelUnderstandingParser.parse(
            """```json
            {
              "topic":"SignalASI runtime",
              "intent":"planning",
              "goals":["Build a resilient phone runtime"],
              "research_questions":["Which Android process limits apply?"],
              "actions":[
                {"kind":"READ_ONLY_CHECK","goal":"Audit current runtime state","priority":0.9},
                {"kind":"PAY","goal":"Buy a server","priority":1.0}
              ],
              "confidence":0.84
            }
            ```"""
        )

        assertNotNull(parsed)
        assertEquals("SignalASI runtime", parsed?.topic)
        assertEquals(1, parsed?.actions?.size)
        assertEquals(GlobalAutonomousActionKind.READ_ONLY_CHECK, parsed?.actions?.first()?.kind)
        assertEquals(0.84, parsed?.confidence ?: 0.0, 0.001)
    }

    @Test
    fun `invalid model prose cannot become global cognition`() {
        assertNull(GlobalModelUnderstandingParser.parse("I think the user should do more research."))
    }

    @Test
    fun `model understanding enriches but does not erase deterministic evidence`() {
        val source = event("We need to finish the Android runtime")
        val baseline = understanding(source, complexity = 0.42).copy(
            goalCandidates = listOf("Finish the Android runtime"),
            riskCandidates = listOf("The build is blocked")
        )
        val task = GlobalCognitionTask(sourceEvent = source, baselineUnderstanding = baseline)
        val merged = GlobalCognitionMerger.merge(task, GlobalModelUnderstanding(
            topic = "Mobile Agent Runtime",
            tasks = listOf("Validate recovery after process death"),
            opportunities = listOf("Reuse durable workspaces"),
            confidence = 0.88
        ))

        assertEquals("Mobile Agent Runtime", merged.topic)
        assertTrue("Finish the Android runtime" in merged.goalCandidates)
        assertTrue("The build is blocked" in merged.riskCandidates)
        assertTrue("Validate recovery after process death" in merged.taskCandidates)
        assertTrue("Reuse durable workspaces" in merged.opportunityCandidates)
    }

    @Test
    fun `external effect remains behind confirmation while safe preparation can run`() {
        val source = event("Prepare the release plan")
        val task = GlobalCognitionTask(
            sourceEvent = source,
            baselineUnderstanding = understanding(source, complexity = 0.7),
            status = GlobalCognitionTaskStatus.COMPLETED,
            result = GlobalModelUnderstanding(
                topic = "Release",
                actions = listOf(
                    GlobalAutonomousAction(
                        kind = GlobalAutonomousActionKind.DRAFT,
                        goal = "Draft release notes"
                    ),
                    GlobalAutonomousAction(
                        kind = GlobalAutonomousActionKind.CREATE_TOPIC,
                        goal = "Publish the release announcement",
                        externalEffect = true
                    )
                )
            )
        )

        val run = requireNotNull(GlobalAutonomousRunPlanner.plan(task))
        assertEquals(GlobalAutonomousRunStatus.QUEUED, run.status)
        assertEquals(GlobalAutonomousActionStatus.PENDING, run.actions[0].status)
        assertEquals(GlobalAutonomousActionStatus.WAITING_CONFIRMATION, run.actions[1].status)
    }

    @Test
    fun `expired cognition lease is recoverable on another resource`() {
        val source = event("Analyze a long-running project")
        val task = GlobalCognitionTask(
            sourceEvent = source,
            baselineUnderstanding = understanding(source, complexity = 0.8),
            status = GlobalCognitionTaskStatus.RUNNING,
            resourceId = "codex",
            sourceMessageId = 42L,
            leaseExpiresAtMillis = 100L
        )

        val recovered = GlobalCognitionTaskPolicy.recoverIfStale(task, 101L)

        assertEquals(GlobalCognitionTaskStatus.WAITING_FOR_RESOURCE, recovered.status)
        assertTrue("codex" in recovered.attemptedResourceIds)
        assertEquals(0L, recovered.sourceMessageId)
    }

    @Test
    fun `expired autonomous action preserves completed work and retries only running step`() {
        val run = GlobalAutonomousRun(
            sourceCognitionTaskId = "cognition",
            sourceEventId = "event",
            sourceConversationId = "conversation",
            topic = "Runtime",
            goal = "Complete preparation",
            status = GlobalAutonomousRunStatus.RUNNING,
            leaseExpiresAtMillis = 100L,
            actions = listOf(
                GlobalAutonomousAction(
                    kind = GlobalAutonomousActionKind.ANALYZE,
                    goal = "Analyze constraints",
                    status = GlobalAutonomousActionStatus.COMPLETED,
                    result = "done"
                ),
                GlobalAutonomousAction(
                    kind = GlobalAutonomousActionKind.DRAFT,
                    goal = "Draft implementation",
                    status = GlobalAutonomousActionStatus.RUNNING,
                    resourceId = "codex",
                    sourceMessageId = 99L,
                    leaseExpiresAtMillis = 100L
                )
            )
        )

        val recovered = GlobalAutonomousRunPolicy.recoverIfStale(run, 101L)

        assertEquals(GlobalAutonomousActionStatus.COMPLETED, recovered.actions[0].status)
        assertEquals("done", recovered.actions[0].result)
        assertEquals(GlobalAutonomousActionStatus.PENDING, recovered.actions[1].status)
        assertTrue("codex" in recovered.actions[1].attemptedResourceIds)
    }

    @Test
    fun `partial autonomous completion is represented explicitly`() {
        val status = GlobalAutonomousRunPolicy.terminalStatus(listOf(
            GlobalAutonomousAction(
                kind = GlobalAutonomousActionKind.ANALYZE,
                goal = "Analyze",
                status = GlobalAutonomousActionStatus.COMPLETED
            ),
            GlobalAutonomousAction(
                kind = GlobalAutonomousActionKind.DRAFT,
                goal = "Draft",
                status = GlobalAutonomousActionStatus.FAILED
            )
        ))

        assertEquals(GlobalAutonomousRunStatus.PARTIAL, status)
    }

    @Test
    fun `model cognition enters world model as inferred evidence`() {
        val source = event("Continue the global Agent project")
        val cognitionEvent = source.copy(
            id = "cognition-result",
            type = GlobalConversationEventType.COGNITION_RESULT,
            actor = GlobalConversationActor.GLOBAL_AGENT
        )
        val enriched = understanding(source, complexity = 0.8).copy(
            goalCandidates = listOf("Build a persistent global Agent"),
            riskCandidates = listOf("Rules alone miss cross-topic intent")
        )

        val reduction = GlobalWorldModelReducer.reduce(PersonalWorldModel(), cognitionEvent, enriched)

        assertTrue(reduction.world.items.any {
            it.kind == GlobalWorldItemKind.GOAL && it.value == "Build a persistent global Agent"
        })
        assertTrue(reduction.world.items.any {
            it.kind == GlobalWorldItemKind.RISK && it.value == "Rules alone miss cross-topic intent"
        })
    }

    private fun event(content: String) = GlobalConversationEvent(
        id = "event-${content.hashCode()}",
        type = GlobalConversationEventType.MESSAGE_CREATED,
        conversationId = "conversation-a",
        actor = GlobalConversationActor.USER,
        content = content,
        conversationTitle = "SignalASI"
    )

    private fun understanding(
        event: GlobalConversationEvent,
        complexity: Double,
        crossConversationIds: Set<String> = emptySet()
    ) = GlobalUnderstanding(
        eventId = event.id,
        topic = "SignalASI",
        intent = "planning",
        complexity = complexity,
        crossConversationIds = crossConversationIds
    )
}
