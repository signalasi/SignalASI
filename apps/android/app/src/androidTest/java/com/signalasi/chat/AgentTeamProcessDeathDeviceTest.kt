package com.signalasi.chat

import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentTeamProcessDeathDeviceTest {
    @Test
    fun recoversManagedTeamResponsesAcrossRealProcessDeath() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT).orEmpty()
        assumeTrue("Run through tools/dev/test-android-team-process-death.js", phase in setOf(PHASE_SEED, PHASE_RECOVER))
        val context = ApplicationProvider.getApplicationContext<Context>()
        when (phase) {
            PHASE_SEED -> seedInterruptedTeam(context)
            PHASE_RECOVER -> recoverLateResponses(context)
        }
    }

    private suspend fun seedInterruptedTeam(context: Context) {
        cleanupAcceptanceState(context)
        val store = EncryptedAgentTeamExecutionStore(context)
        val ledger = EncryptedAgentManagedResponseLedger(context)
        val now = System.currentTimeMillis()
        val request = AgentRunRequest(
            conversationId = CONVERSATION_ID,
            messageId = TURN_ID,
            taskId = TASK_ID,
            runId = SUPERVISOR_RUN_ID,
            goal = "Recover a supervised Agent team after Android process death",
            idempotencyKey = "acceptance-process-death",
            createdAtMillis = now
        )
        val definition = AgentTeamDefinition(
            teamId = TEAM_ID,
            primaryAgentId = PRIMARY_AGENT_ID,
            members = listOf(
                AgentTeamMember(
                    agentId = OBSERVER_AGENT_ID,
                    deliveryMode = AgentDeliveryMode.OBSERVE,
                    role = "evidence observer"
                ),
                AgentTeamMember(
                    agentId = PRIMARY_AGENT_ID,
                    deliveryMode = AgentDeliveryMode.RESPOND,
                    role = "primary synthesizer",
                    dependsOnAgentIds = setOf(OBSERVER_AGENT_ID)
                )
            )
        )
        store.create(definition, request)
        store.append(AgentSubagentEvent(
            sequence = 1L,
            supervisorId = SUPERVISOR_RUN_ID,
            kind = AgentSubagentEventKinds.SUPERVISOR_STARTED,
            timestampMillis = now
        ))
        store.append(AgentSubagentEvent(
            sequence = 2L,
            supervisorId = SUPERVISOR_RUN_ID,
            childId = OBSERVER_AGENT_ID,
            kind = AgentSubagentEventKinds.CHILD_RUNNING,
            childStatus = AgentSubagentStatus.RUNNING,
            timestampMillis = now + 1L
        ))
        store.append(AgentSubagentEvent(
            sequence = 3L,
            supervisorId = SUPERVISOR_RUN_ID,
            childId = PRIMARY_AGENT_ID,
            kind = AgentSubagentEventKinds.CHILD_RUNNING,
            childStatus = AgentSubagentStatus.RUNNING,
            timestampMillis = now + 2L
        ))
        ledger.register(managedRecord(
            ownerRunId = OBSERVER_OWNER_RUN_ID,
            agentId = OBSERVER_AGENT_ID,
            deliveryMode = AgentDeliveryMode.OBSERVE,
            sourceMessageId = OBSERVER_SOURCE_MESSAGE_ID,
            contactId = OBSERVER_CONTACT_ID,
            createdAtMillis = now + 3L
        ))
        ledger.register(managedRecord(
            ownerRunId = PRIMARY_OWNER_RUN_ID,
            agentId = PRIMARY_AGENT_ID,
            deliveryMode = AgentDeliveryMode.RESPOND,
            sourceMessageId = PRIMARY_SOURCE_MESSAGE_ID,
            contactId = PRIMARY_CONTACT_ID,
            createdAtMillis = now + 4L
        ))
        val snapshot = requireNotNull(store.snapshot(SUPERVISOR_RUN_ID))
        assertEquals(AgentTeamExecutionState.RUNNING, snapshot.state)
        val pid = Process.myPid()
        assertTrue(context.getSharedPreferences(ACCEPTANCE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putInt(SEED_PID_KEY, pid)
            .putLong(SEED_TIME_KEY, now)
            .commit())
        writeReport(context, JSONObject()
            .put("phase", PHASE_SEED)
            .put("seed_pid", pid)
            .put("state", snapshot.state.name)
            .put("pending_managed_responses", 2))
    }

    private fun recoverLateResponses(context: Context) {
        val preferences = context.getSharedPreferences(ACCEPTANCE_PREFERENCES, Context.MODE_PRIVATE)
        val seedPid = preferences.getInt(SEED_PID_KEY, 0)
        val recoveryPid = Process.myPid()
        assertTrue("The seed phase did not persist a process identity", seedPid > 0)
        assertNotEquals("The Android process was not recreated", seedPid, recoveryPid)

        deliverThroughBackgroundService(context, responsePayload(
            sourceMessageId = OBSERVER_SOURCE_MESSAGE_ID,
            contactId = OBSERVER_CONTACT_ID,
            messageId = "acceptance-observer-response",
            content = OBSERVER_OUTPUT
        ))
        waitUntil("observer response reconciliation") {
            EncryptedAgentTeamExecutionStore(context).snapshot(SUPERVISOR_RUN_ID)
                ?.members
                ?.firstOrNull { it.agentId == OBSERVER_AGENT_ID }
                ?.status == AgentSubagentStatus.SUCCEEDED
        }

        deliverThroughBackgroundService(context, responsePayload(
            sourceMessageId = PRIMARY_SOURCE_MESSAGE_ID,
            contactId = PRIMARY_CONTACT_ID,
            messageId = "acceptance-primary-response",
            content = PRIMARY_OUTPUT
        ))
        waitUntil("terminal team recovery") {
            EncryptedAgentTeamExecutionStore(context).snapshot(SUPERVISOR_RUN_ID)
                ?.state == AgentTeamExecutionState.SUCCEEDED
        }
        val syntheticSourceId = AgentTeamDispatchIds.sourceMessageId(SUPERVISOR_RUN_ID)
        val syntheticContactId = AgentTeamDispatchIds.responseContactId(TEAM_ID)
        waitUntil("single durable team result") {
            AgentConnectorResponseStore.pending(context).count {
                it.sourceMessageId == syntheticSourceId && it.contactId == syntheticContactId
            } == 1
        }

        deliverThroughBackgroundService(context, responsePayload(
            sourceMessageId = PRIMARY_SOURCE_MESSAGE_ID,
            contactId = PRIMARY_CONTACT_ID,
            messageId = "acceptance-primary-response-duplicate",
            content = "duplicate result that must be ignored"
        ))
        SystemClock.sleep(500L)

        val snapshot = requireNotNull(EncryptedAgentTeamExecutionStore(context).snapshot(SUPERVISOR_RUN_ID))
        val syntheticResponses = AgentConnectorResponseStore.pending(context).filter {
            it.sourceMessageId == syntheticSourceId && it.contactId == syntheticContactId
        }
        val leakedMessages = acceptanceChatMessageCount(context)
        assertEquals(AgentTeamExecutionState.SUCCEEDED, snapshot.state)
        assertEquals(PRIMARY_OUTPUT, snapshot.finalOutput)
        assertTrue(EncryptedAgentManagedResponseLedger(context).completedUnapplied().isEmpty())
        assertEquals(1, syntheticResponses.size)
        assertEquals(PRIMARY_OUTPUT, syntheticResponses.single().content)
        assertEquals(0, leakedMessages)

        writeReport(context, JSONObject()
            .put("phase", PHASE_RECOVER)
            .put("seed_pid", seedPid)
            .put("recovery_pid", recoveryPid)
            .put("process_recreated", seedPid != recoveryPid)
            .put("team_state", snapshot.state.name)
            .put("final_output", snapshot.finalOutput)
            .put("synthetic_response_count", syntheticResponses.size)
            .put("ordinary_chat_leak_count", leakedMessages)
            .put("completed_unapplied_count", 0)
            .put("duplicate_suppressed", true))
        cleanupAcceptanceState(context)
    }

    private fun managedRecord(
        ownerRunId: String,
        agentId: String,
        deliveryMode: AgentDeliveryMode,
        sourceMessageId: Long,
        contactId: String,
        createdAtMillis: Long
    ) = AgentManagedResponseRecord(
        ownerRunId = ownerRunId,
        supervisorRunId = SUPERVISOR_RUN_ID,
        agentId = agentId,
        deliveryMode = deliveryMode,
        sourceMessageId = sourceMessageId,
        contactId = contactId,
        createdAtMillis = createdAtMillis
    )

    private fun responsePayload(
        sourceMessageId: Long,
        contactId: String,
        messageId: String,
        content: String
    ): String = JSONObject()
        .put("type", "text")
        .put("sender", contactId)
        .put("contact_id", contactId)
        .put("source_message_id", sourceMessageId)
        .put("message_id", messageId)
        .put("conversation_id", CONVERSATION_ID)
        .put("turn_id", TURN_ID)
        .put("task_id", TASK_ID)
        .put("content", content)
        .toString()

    private fun deliverThroughBackgroundService(context: Context, payload: String) {
        context.startForegroundService(Intent(context, MessageService::class.java).apply {
            putExtra(DEBUG_SERVICE_PAYLOAD, payload)
        })
    }

    private fun waitUntil(label: String, timeoutMillis: Long = 20_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100L)
        }
        assertTrue("Timed out waiting for $label", condition())
    }

    private fun acceptanceChatMessageCount(context: Context): Int {
        val raw = context.getSharedPreferences(CHAT_HISTORY_PREFERENCES, Context.MODE_PRIVATE)
            .getString(CHAT_HISTORY_KEY, "{}")
            .orEmpty()
        val root = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        return listOf(OBSERVER_CONTACT_ID, PRIMARY_CONTACT_ID)
            .sumOf { contactId -> root.optJSONArray(contactId)?.length() ?: 0 }
    }

    private fun cleanupAcceptanceState(context: Context) {
        EncryptedAgentTeamExecutionStore(context).remove(SUPERVISOR_RUN_ID)
        EncryptedAgentManagedResponseLedger(context).apply {
            removeOwner(OBSERVER_OWNER_RUN_ID)
            removeOwner(PRIMARY_OWNER_RUN_ID)
        }
        AgentConnectorTeamCompletionSink(context).remove(SUPERVISOR_RUN_ID)
        AgentConnectorResponseStore.remove(context, AgentConnectorResponse(
            sourceMessageId = AgentTeamDispatchIds.sourceMessageId(SUPERVISOR_RUN_ID),
            contactId = AgentTeamDispatchIds.responseContactId(TEAM_ID),
            content = PRIMARY_OUTPUT
        ))
        val preferences = context.getSharedPreferences(CHAT_HISTORY_PREFERENCES, Context.MODE_PRIVATE)
        val root = runCatching {
            JSONObject(preferences.getString(CHAT_HISTORY_KEY, "{}").orEmpty())
        }.getOrElse { JSONObject() }
        root.remove(OBSERVER_CONTACT_ID)
        root.remove(PRIMARY_CONTACT_ID)
        preferences.edit().putString(CHAT_HISTORY_KEY, root.toString()).commit()
    }

    private fun writeReport(context: Context, report: JSONObject) {
        val directory = requireNotNull(context.getExternalFilesDir(null))
        File(directory, REPORT_FILENAME).writeText(report.toString(2), Charsets.UTF_8)
    }

    private companion object {
        const val PHASE_ARGUMENT = "signalasi_phase"
        const val PHASE_SEED = "seed"
        const val PHASE_RECOVER = "recover"
        const val DEBUG_SERVICE_PAYLOAD = "signalasi_debug_service_payload"
        const val ACCEPTANCE_PREFERENCES = "signalasi_team_process_death_acceptance"
        const val SEED_PID_KEY = "seed_pid"
        const val SEED_TIME_KEY = "seed_time"
        const val REPORT_FILENAME = "agent-team-process-death-report.json"
        const val CHAT_HISTORY_PREFERENCES = "signalasi_chat_history"
        const val CHAT_HISTORY_KEY = "messages"
        const val TEAM_ID = "acceptance-process-death-team"
        const val SUPERVISOR_RUN_ID = "acceptance-process-death-supervisor"
        const val CONVERSATION_ID = "acceptance-process-death-conversation"
        const val TURN_ID = "acceptance-process-death-turn"
        const val TASK_ID = "acceptance-process-death-task"
        const val OBSERVER_AGENT_ID = "acceptance-observer"
        const val PRIMARY_AGENT_ID = "acceptance-primary"
        const val OBSERVER_OWNER_RUN_ID = "acceptance-observer-child"
        const val PRIMARY_OWNER_RUN_ID = "acceptance-primary-child"
        const val OBSERVER_CONTACT_ID = "acceptance-observer-contact"
        const val PRIMARY_CONTACT_ID = "acceptance-primary-contact"
        const val OBSERVER_SOURCE_MESSAGE_ID = 7_100_001L
        const val PRIMARY_SOURCE_MESSAGE_ID = 7_100_002L
        const val OBSERVER_OUTPUT = "durable observer evidence"
        const val PRIMARY_OUTPUT = "durable primary answer"
    }
}
