package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPermissionGrantLedgerTest {
    @Test
    fun singleUseGrantIsConsumedExactlyOnce() {
        var now = 1_000L
        val store = InMemoryAgentPermissionGrantStore(clock = { now })
        store.grant(grant(lifetime = AgentPermissionGrantLifetime.SINGLE_USE))

        assertTrue(store.authorize(request(), consume = true).granted)
        assertFalse(store.authorize(request(), consume = true).granted)
        assertEquals(AgentPermissionGrantStatus.CONSUMED, store.list().single().status)
    }

    @Test
    fun temporaryGrantExpiresAtItsBoundary() {
        var now = 1_000L
        val store = InMemoryAgentPermissionGrantStore(clock = { now })
        store.grant(grant(
            lifetime = AgentPermissionGrantLifetime.TEMPORARY,
            expiresAtMillis = 2_000L,
            maxUses = 0
        ))

        assertTrue(store.authorize(request()).granted)
        now = 2_000L
        assertFalse(store.authorize(request()).granted)
        assertEquals(AgentPermissionGrantStatus.EXPIRED, store.list().single().status)
    }

    @Test
    fun constraintsPreventGrantFromAuthorizingAnotherResource() {
        val store = InMemoryAgentPermissionGrantStore(clock = { 1_000L })
        store.grant(grant(
            lifetime = AgentPermissionGrantLifetime.PERMANENT,
            resource = "content://documents/report.pdf",
            target = "local-runtime",
            maxUses = 0
        ))

        assertTrue(store.authorize(request(
            resource = "content://documents/report.pdf",
            target = "local-runtime"
        )).granted)
        assertFalse(store.authorize(request(
            resource = "content://documents/private.pdf",
            target = "local-runtime"
        )).granted)
    }

    @Test
    fun revocationAndSerializationSurviveStoreRecreation() {
        val first = InMemoryAgentPermissionGrantStore(clock = { 1_000L })
        val issued = first.grant(grant(lifetime = AgentPermissionGrantLifetime.PERMANENT, maxUses = 0))
        val recreated = InMemoryAgentPermissionGrantStore(first.serializedSnapshot(), clock = { 1_500L })

        assertTrue(recreated.authorize(request()).granted)
        val revocation = recreated.revokeGrant(issued.grantId, "user_revoked")
        val afterRestart = InMemoryAgentPermissionGrantStore(recreated.serializedSnapshot(), clock = { 2_000L })

        assertEquals(setOf(issued.grantId), revocation.revokedGrantIds)
        assertFalse(afterRestart.authorize(request()).granted)
        assertEquals("user_revoked", afterRestart.list().single().revocationReason)
    }

    @Test
    fun malformedOrSelfContradictoryGrantIsRejected() {
        val store = InMemoryAgentPermissionGrantStore(clock = { 1_000L })

        assertThrows(IllegalArgumentException::class.java) {
            store.grant(grant(
                lifetime = AgentPermissionGrantLifetime.TEMPORARY,
                expiresAtMillis = 999L,
                maxUses = 0
            ))
        }
    }

    @Test
    fun revocationPausesDependentWorkspaceAndRunAfterRestart() {
        val grantStore = InMemoryAgentPermissionGrantStore(clock = { 2_000L })
        val issued = grantStore.grant(grant(
            lifetime = AgentPermissionGrantLifetime.PERMANENT,
            maxUses = 0
        ))
        val workspaceStore = InMemoryAgentWorkspaceStore(clock = { 2_000L })
        workspaceStore.upsert(AgentWorkspace(
            workspaceId = "workspace-1",
            sessionId = "session-1",
            conversationId = "conversation-1",
            taskId = "task-1",
            goal = "Read location and continue",
            status = AgentWorkspaceStatus.RUNNING,
            permissionGrantIds = listOf(issued.grantId),
            permissionScopes = listOf(issued.scope),
            toolCalls = listOf(AgentToolCallRecord(
                id = "location-call",
                toolName = "android.location",
                status = AgentToolCallStatus.RUNNING,
                startedAtMillis = 1_500L
            ))
        ))
        val runStore = FakeRunControlStore("task-1")
        val report = AgentPermissionRevocationCoordinator(
            grantStore,
            workspaceStore,
            runStore
        ).revokeScope(issued.scope, "user_revoked")

        val restored = InMemoryAgentWorkspaceStore(
            AgentWorkspaceJsonCodec.decodeList(workspaceStore.serializedSnapshot()),
            clock = { 3_000L }
        ).find("workspace-1")!!
        assertEquals(setOf("workspace-1"), report.pausedWorkspaceIds)
        assertEquals(setOf("run-1"), report.pausedRunIds)
        assertEquals(AgentWorkspaceStatus.PAUSED, restored.status)
        assertEquals(AgentToolCallStatus.CANCELLED, restored.toolCalls.single().status)
        assertTrue(restored.checkpoints.last().stateJson.contains("user_revoked"))
        assertEquals(AgentRunControlEventType.PERMISSION_REVOKED, runStore.appended.single().type)
    }

    private fun grant(
        lifetime: AgentPermissionGrantLifetime,
        resource: String = "",
        target: String = "",
        expiresAtMillis: Long = 0L,
        maxUses: Int = if (lifetime == AgentPermissionGrantLifetime.SINGLE_USE) 1 else 0
    ) = AgentPermissionGrant(
        subjectType = AgentPermissionSubjectType.TOOL,
        subjectId = "android.location",
        scope = "location.foreground",
        action = "read",
        resource = resource,
        target = target,
        issuer = AgentPermissionGrantIssuer.USER,
        evidence = "approval-dialog:turn-1",
        lifetime = lifetime,
        maxUses = maxUses,
        expiresAtMillis = expiresAtMillis,
        createdAtMillis = 1_000L
    )

    private fun request(
        resource: String = "",
        target: String = ""
    ) = AgentPermissionRequest(
        subjectType = AgentPermissionSubjectType.TOOL,
        subjectId = "android.location",
        scope = "location.foreground",
        action = "read",
        resource = resource,
        target = target
    )
}

private class FakeRunControlStore(taskId: String) : AgentRunControlStore {
    val appended = mutableListOf<AgentRunControlEvent>()
    private val initial = AgentRunControlEvent(
        conversationId = "conversation-1",
        messageId = "message-1",
        taskId = taskId,
        runId = "run-1",
        agentId = "signalasi-mobile",
        deviceId = "phone-1",
        type = AgentRunControlEventType.TOOL_STARTED,
        sequence = 3L
    )

    override fun appendNext(event: AgentRunControlEvent): AgentRunControlEvent {
        val appendedEvent = event.copy(sequence = initial.sequence + appended.size + 1L)
        appended += appendedEvent
        return appendedEvent
    }

    override fun recoverableRuns(): List<AgentRunControlSnapshot> = listOf(
        AgentRunControlSnapshot(
            runId = initial.runId,
            taskId = initial.taskId,
            state = AgentRunControlState.RUNNING,
            agentId = initial.agentId,
            deviceId = initial.deviceId,
            lastSequence = initial.sequence,
            lastEvent = initial
        )
    )
}
