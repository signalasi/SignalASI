package com.signalasi.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentManagedResponsePersistenceTest {
    @Test
    fun encryptedLateResponseReturnsToInterruptedTeamAfterStoreRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val originalStore = EncryptedAgentTeamExecutionStore(context)
        val originalLedger = EncryptedAgentManagedResponseLedger(context)
        originalStore.clear()
        originalLedger.clear()
        AgentConnectorResponseStore.clear(context)

        val request = AgentRunRequest(
            conversationId = "instrumented-conversation",
            messageId = "instrumented-message",
            taskId = "instrumented-task",
            runId = "instrumented-supervisor",
            goal = "Return a durable result",
            idempotencyKey = "instrumented-idempotency"
        )
        val definition = AgentTeamDefinition(
            teamId = "instrumented-team",
            primaryAgentId = "primary",
            members = listOf(AgentTeamMember("primary", AgentDeliveryMode.RESPOND))
        )
        originalStore.create(definition, request)
        originalStore.append(AgentSubagentEvent(
            sequence = 1L,
            supervisorId = request.runId,
            kind = AgentSubagentEventKinds.SUPERVISOR_STARTED,
            timestampMillis = 1_000L
        ))
        originalStore.append(AgentSubagentEvent(
            sequence = 2L,
            supervisorId = request.runId,
            childId = "primary",
            kind = AgentSubagentEventKinds.CHILD_RUNNING,
            childStatus = AgentSubagentStatus.RUNNING,
            timestampMillis = 1_100L
        ))
        originalStore.markNonTerminalInterrupted(1_200L)
        originalLedger.register(AgentManagedResponseRecord(
            ownerRunId = "instrumented-child",
            supervisorRunId = request.runId,
            agentId = "primary",
            deliveryMode = AgentDeliveryMode.RESPOND,
            sourceMessageId = 9_001_337L,
            contactId = "primary"
        ))
        val response = AgentConnectorResponse(
            sourceMessageId = 9_001_337L,
            contactId = "primary",
            content = "durable final answer"
        )
        assertTrue(EncryptedAgentManagedResponseLedger(context).complete(response) != null)

        val recreatedStore = EncryptedAgentTeamExecutionStore(context)
        val recreatedLedger = EncryptedAgentManagedResponseLedger(context)
        val controller = AgentProductionTeamController(
            context = context,
            store = recreatedStore,
            worker = AgentTeamMemberWorker { AgentSubagentOutput() },
            managedResponses = recreatedLedger
        )
        try {
            val snapshot = requireNotNull(controller.snapshot(request.runId))
            assertEquals(AgentTeamExecutionState.SUCCEEDED, snapshot.state)
            assertEquals("durable final answer", snapshot.finalOutput)
            assertTrue(recreatedLedger.completedUnapplied().isEmpty())

            assertTrue(AgentConnectorResponseBus.publish(context, response.copy(content = "duplicate")))
            assertTrue(AgentConnectorResponseStore.pending(context).isEmpty())
            assertEquals("durable final answer", controller.snapshot(request.runId)?.finalOutput)
        } finally {
            controller.close()
            recreatedStore.clear()
            recreatedLedger.clear()
            AgentConnectorResponseStore.clear(context)
        }
    }
}
