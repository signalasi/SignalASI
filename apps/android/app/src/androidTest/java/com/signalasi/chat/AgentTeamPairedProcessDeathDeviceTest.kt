package com.signalasi.chat

import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentTeamPairedProcessDeathDeviceTest {
    @Test
    fun recoversNaturallyLatePairedDesktopResponse() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString(PHASE_ARGUMENT).orEmpty()
        assumeTrue("Run through tools/dev/test-android-team-paired-process-death.js", phase in setOf(PHASE_SEED, PHASE_RECOVER))
        val context = ApplicationProvider.getApplicationContext<Context>()
        when (phase) {
            PHASE_SEED -> seedPairedTeam(context)
            PHASE_RECOVER -> recoverPairedTeam(
                context,
                arguments.getString(FORCED_AT_ARGUMENT).orEmpty().toLongOrNull() ?: 0L
            )
        }
    }

    private fun seedPairedTeam(context: Context) {
        cleanupPreviousAcceptance(context)
        removeAcceptanceHistory(context)
        startMessageService(context)
        waitUntil("secure MQTT connection", CONNECTION_TIMEOUT_MILLIS) {
            SignalASIMqttClient.isConnected() && SignalASIMqttClient.isSecureReady()
        }

        var pair: Pair<AgentRegistration, AgentRegistration>? = null
        waitUntil("two routable Codex registrations", CONNECTOR_TIMEOUT_MILLIS) {
            val registrations = AppStoreAgentConnectorRegistry(context).registrations()
                .filter(::isRoutable)
            val builtIn = registrations.firstOrNull { it.agentId == CODEX_AGENT_ID }
            val paired = registrations.firstOrNull {
                it.agentId != CODEX_AGENT_ID && it.agentId.endsWith(":$CODEX_AGENT_ID")
            }
            if (builtIn != null && paired != null) pair = builtIn to paired
            pair != null
        }
        val (primaryRegistration, observerRegistration) = requireNotNull(pair)
        assertNotEquals(primaryRegistration.agentId, observerRegistration.agentId)

        val supervisorRunId = "$SUPERVISOR_PREFIX${UUID.randomUUID()}"
        val teamId = "$TEAM_PREFIX${UUID.randomUUID()}"
        val conversationId = "$CONVERSATION_PREFIX${UUID.randomUUID()}"
        val turnId = "$TURN_PREFIX${UUID.randomUUID()}"
        val observerToken = "$ACCEPTANCE_MARKER observer $supervisorRunId"
        val primaryToken = "$ACCEPTANCE_MARKER primary $supervisorRunId"
        val historyContactId = observerRegistration.agentId
        val initialHistoryCount = historyMessageCount(context, historyContactId)
        val seedPid = Process.myPid()
        val seedTime = System.currentTimeMillis()
        val preferences = acceptancePreferences(context)
        assertTrue(preferences.edit()
            .putString(SUPERVISOR_RUN_ID_KEY, supervisorRunId)
            .putString(TEAM_ID_KEY, teamId)
            .putString(CONVERSATION_ID_KEY, conversationId)
            .putString(TURN_ID_KEY, turnId)
            .putString(PRIMARY_AGENT_ID_KEY, primaryRegistration.agentId)
            .putString(OBSERVER_AGENT_ID_KEY, observerRegistration.agentId)
            .putString(HISTORY_CONTACT_ID_KEY, historyContactId)
            .putInt(INITIAL_HISTORY_COUNT_KEY, initialHistoryCount)
            .putInt(SEED_PID_KEY, seedPid)
            .putLong(SEED_TIME_KEY, seedTime)
            .commit())

        val definition = AgentTeamDefinition(
            teamId = teamId,
            primaryAgentId = primaryRegistration.agentId,
            members = listOf(
                AgentTeamMember(
                    agentId = observerRegistration.agentId,
                    deliveryMode = AgentDeliveryMode.OBSERVE,
                    role = "paired evidence observer",
                    objective = "Return this exact acceptance token and nothing else: $observerToken"
                ),
                AgentTeamMember(
                    agentId = primaryRegistration.agentId,
                    deliveryMode = AgentDeliveryMode.RESPOND,
                    role = "paired primary synthesizer",
                    objective = "Use a terminal command to wait 20 seconds before answering. " +
                        "After the wait, return this exact acceptance token and nothing else: $primaryToken",
                    dependsOnAgentIds = setOf(observerRegistration.agentId)
                )
            ),
            visibilityMode = AgentTeamVisibilityMode.BACKGROUND
        )
        val request = AgentRunRequest(
            conversationId = conversationId,
            messageId = turnId,
            taskId = turnId,
            runId = supervisorRunId,
            goal = "Verify a naturally late paired Desktop response after Android process death",
            requiredCapabilities = setOf(AgentCapability.CHAT),
            idempotencyKey = supervisorRunId,
            createdAtMillis = seedTime
        )
        GlobalSuperAgentRuntime.get(context).startAgentTeam(definition, request)

        var pendingPrimary: AgentManagedResponseRecord? = null
        waitUntil("observer completion and primary Desktop dispatch", AGENT_TIMEOUT_MILLIS) {
            val snapshot = EncryptedAgentTeamExecutionStore(context).snapshot(supervisorRunId)
            pendingPrimary = EncryptedAgentManagedResponseLedger(context)
                .pendingForSupervisor(supervisorRunId)
                .firstOrNull { it.agentId == primaryRegistration.agentId }
            snapshot?.members?.firstOrNull { it.agentId == observerRegistration.agentId }
                ?.status == AgentSubagentStatus.SUCCEEDED &&
                snapshot.members.firstOrNull { it.agentId == primaryRegistration.agentId }
                    ?.status == AgentSubagentStatus.RUNNING &&
                pendingPrimary != null &&
                AgentConnectorResponseStore.pending(context).none {
                    it.sourceMessageId == AgentTeamDispatchIds.sourceMessageId(supervisorRunId)
                }
        }
        val pending = requireNotNull(pendingPrimary)
        assertTrue(preferences.edit()
            .putString(PRIMARY_OWNER_RUN_ID_KEY, pending.ownerRunId)
            .putLong(PRIMARY_SOURCE_MESSAGE_ID_KEY, pending.sourceMessageId)
            .commit())
        assertEquals(initialHistoryCount, historyMessageCount(context, historyContactId))

        writeReport(context, JSONObject()
            .put("phase", PHASE_SEED)
            .put("seed_pid", seedPid)
            .put("seed_time", seedTime)
            .put("team_state", AgentTeamExecutionState.RUNNING.name)
            .put("observer_completed", true)
            .put("primary_dispatched", true)
            .put("ordinary_chat_leak_count", 0))
    }

    private fun recoverPairedTeam(context: Context, forcedAtMillis: Long) {
        val preferences = acceptancePreferences(context)
        val supervisorRunId = preferences.getString(SUPERVISOR_RUN_ID_KEY, "").orEmpty()
        val teamId = preferences.getString(TEAM_ID_KEY, "").orEmpty()
        val primaryAgentId = preferences.getString(PRIMARY_AGENT_ID_KEY, "").orEmpty()
        val observerAgentId = preferences.getString(OBSERVER_AGENT_ID_KEY, "").orEmpty()
        val historyContactId = preferences.getString(HISTORY_CONTACT_ID_KEY, "").orEmpty()
        val initialHistoryCount = preferences.getInt(INITIAL_HISTORY_COUNT_KEY, -1)
        val seedPid = preferences.getInt(SEED_PID_KEY, 0)
        val recoveryPid = Process.myPid()
        assertTrue("The paired seed phase did not persist a Run", supervisorRunId.isNotBlank() && teamId.isNotBlank())
        assertTrue("The host did not provide a force-stop timestamp", forcedAtMillis > 0L)
        assertTrue("The paired seed phase did not persist a process identity", seedPid > 0)
        assertNotEquals("The Android process was not recreated", seedPid, recoveryPid)

        val runtime = GlobalSuperAgentRuntime.get(context)
        startMessageService(context)
        waitUntil("secure MQTT reconnection", CONNECTION_TIMEOUT_MILLIS) {
            SignalASIMqttClient.isConnected() && SignalASIMqttClient.isSecureReady()
        }
        waitUntil("naturally late paired Desktop completion", AGENT_TIMEOUT_MILLIS) {
            runtime.agentTeamSnapshot(supervisorRunId)?.state == AgentTeamExecutionState.SUCCEEDED
        }

        val snapshot = requireNotNull(runtime.agentTeamSnapshot(supervisorRunId))
        val primary = requireNotNull(snapshot.members.firstOrNull { it.agentId == primaryAgentId })
        val observer = requireNotNull(snapshot.members.firstOrNull { it.agentId == observerAgentId })
        val syntheticSourceMessageId = AgentTeamDispatchIds.sourceMessageId(supervisorRunId)
        val syntheticContactId = AgentTeamDispatchIds.responseContactId(teamId)
        val syntheticResponses = AgentConnectorResponseStore.pending(context).filter {
            it.sourceMessageId == syntheticSourceMessageId && it.contactId == syntheticContactId
        }
        val chatLeakCount = historyMessageCount(context, historyContactId) - initialHistoryCount
        val observerOutputVerified = observer.status == AgentSubagentStatus.SUCCEEDED && observer.output.isNotBlank()
        val primaryOutputVerified = primary.status == AgentSubagentStatus.SUCCEEDED &&
            snapshot.finalOutput.isNotBlank() && snapshot.finalOutput == primary.output
        val completedUnappliedCount = EncryptedAgentManagedResponseLedger(context).completedUnapplied().size

        writeReport(context, JSONObject()
            .put("phase", PHASE_RECOVER)
            .put("seed_pid", seedPid)
            .put("recovery_pid", recoveryPid)
            .put("process_recreated", seedPid != recoveryPid)
            .put("forced_at", forcedAtMillis)
            .put("primary_completed_at", primary.completedAtMillis)
            .put("late_response_delay_ms", primary.completedAtMillis - forcedAtMillis)
            .put("team_state", snapshot.state.name)
            .put("observer_output_verified", observerOutputVerified)
            .put("observer_output_chars", observer.output.length)
            .put("primary_output_verified", primaryOutputVerified)
            .put("primary_output_chars", snapshot.finalOutput.length)
            .put("synthetic_response_count", syntheticResponses.size)
            .put("ordinary_chat_leak_count", chatLeakCount)
            .put("completed_unapplied_count", completedUnappliedCount))

        assertEquals(AgentTeamExecutionState.SUCCEEDED, snapshot.state)
        assertTrue("Observer evidence did not come from the paired Desktop", observerOutputVerified)
        assertTrue("Primary result did not come from the paired Desktop", primaryOutputVerified)
        assertTrue("Primary response was not late relative to force-stop", primary.completedAtMillis >= forcedAtMillis)
        assertEquals(1, syntheticResponses.size)
        assertEquals(snapshot.finalOutput, syntheticResponses.single().content)
        assertEquals(0, chatLeakCount)
        assertEquals(0, completedUnappliedCount)

        cleanupRun(context, supervisorRunId, teamId, primaryAgentId, observerAgentId)
        removeAcceptanceHistory(context)
        preferences.edit().clear().commit()
    }

    private fun isRoutable(registration: AgentRegistration): Boolean =
        registration.status in setOf(
            AgentEndpointStatus.ONLINE,
            AgentEndpointStatus.IDLE,
            AgentEndpointStatus.BUSY,
            AgentEndpointStatus.DEGRADED
        ) && registration.hasCapacity && AgentCapability.CHAT in registration.capabilities

    private fun startMessageService(context: Context) {
        context.startForegroundService(Intent(context, MessageService::class.java))
    }

    private fun cleanupPreviousAcceptance(context: Context) {
        val preferences = acceptancePreferences(context)
        val supervisorRunId = preferences.getString(SUPERVISOR_RUN_ID_KEY, "").orEmpty()
        val teamId = preferences.getString(TEAM_ID_KEY, "").orEmpty()
        val primaryAgentId = preferences.getString(PRIMARY_AGENT_ID_KEY, "").orEmpty()
        val observerAgentId = preferences.getString(OBSERVER_AGENT_ID_KEY, "").orEmpty()
        cleanupRun(context, supervisorRunId, teamId, primaryAgentId, observerAgentId)
        preferences.edit().clear().commit()
    }

    private fun cleanupRun(
        context: Context,
        supervisorRunId: String,
        teamId: String,
        primaryAgentId: String,
        observerAgentId: String
    ) {
        if (supervisorRunId.isBlank()) return
        EncryptedAgentTeamExecutionStore(context).remove(supervisorRunId)
        EncryptedAgentManagedResponseLedger(context).apply {
            if (primaryAgentId.isNotBlank()) removeOwner(childRunId(supervisorRunId, primaryAgentId))
            if (observerAgentId.isNotBlank()) removeOwner(childRunId(supervisorRunId, observerAgentId))
        }
        AgentConnectorTeamCompletionSink(context).remove(supervisorRunId)
        if (teamId.isNotBlank()) {
            AgentConnectorResponseStore.remove(context, AgentConnectorResponse(
                sourceMessageId = AgentTeamDispatchIds.sourceMessageId(supervisorRunId),
                contactId = AgentTeamDispatchIds.responseContactId(teamId),
                content = ACCEPTANCE_MARKER
            ))
        }
    }

    private fun childRunId(supervisorRunId: String, agentId: String): String =
        UUID.nameUUIDFromBytes("$supervisorRunId\u001f$agentId".toByteArray(Charsets.UTF_8)).toString()

    private fun historyMessageCount(context: Context, contactId: String): Int =
        if (contactId.isBlank()) -1 else chatHistoryRoot(context).optJSONArray(contactId)?.length() ?: 0

    private fun removeAcceptanceHistory(context: Context) {
        val preferences = context.getSharedPreferences(CHAT_HISTORY_PREFERENCES, Context.MODE_PRIVATE)
        val root = chatHistoryRoot(context)
        root.keys().asSequence().toList().forEach { contactId ->
            val source = root.optJSONArray(contactId) ?: JSONArray()
            val kept = JSONArray()
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                if (!item.optString("content").contains(ACCEPTANCE_MARKER)) kept.put(item)
            }
            root.put(contactId, kept)
        }
        preferences.edit().putString(CHAT_HISTORY_KEY, root.toString()).commit()
    }

    private fun chatHistoryRoot(context: Context): JSONObject {
        val raw = context.getSharedPreferences(CHAT_HISTORY_PREFERENCES, Context.MODE_PRIVATE)
            .getString(CHAT_HISTORY_KEY, "{}")
            .orEmpty()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun acceptancePreferences(context: Context) =
        context.getSharedPreferences(ACCEPTANCE_PREFERENCES, Context.MODE_PRIVATE)

    private fun writeReport(context: Context, report: JSONObject) {
        val directory = requireNotNull(context.getExternalFilesDir(null))
        File(directory, REPORT_FILENAME).writeText(report.toString(2), Charsets.UTF_8)
    }

    private fun waitUntil(label: String, timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100L)
        }
        assertTrue("Timed out waiting for $label", condition())
    }

    private companion object {
        const val PHASE_ARGUMENT = "signalasi_phase"
        const val FORCED_AT_ARGUMENT = "signalasi_forced_at"
        const val PHASE_SEED = "seed"
        const val PHASE_RECOVER = "recover"
        const val CODEX_AGENT_ID = "codex"
        const val ACCEPTANCE_MARKER = "SIGNALASI_PAIRED_TEAM_ACCEPTANCE"
        const val SUPERVISOR_PREFIX = "paired-team-acceptance-run-"
        const val TEAM_PREFIX = "paired-team-acceptance-team-"
        const val CONVERSATION_PREFIX = "paired-team-acceptance-conversation-"
        const val TURN_PREFIX = "paired-team-acceptance-turn-"
        const val ACCEPTANCE_PREFERENCES = "signalasi_paired_team_process_death_acceptance"
        const val SUPERVISOR_RUN_ID_KEY = "supervisor_run_id"
        const val TEAM_ID_KEY = "team_id"
        const val CONVERSATION_ID_KEY = "conversation_id"
        const val TURN_ID_KEY = "turn_id"
        const val PRIMARY_AGENT_ID_KEY = "primary_agent_id"
        const val OBSERVER_AGENT_ID_KEY = "observer_agent_id"
        const val HISTORY_CONTACT_ID_KEY = "history_contact_id"
        const val INITIAL_HISTORY_COUNT_KEY = "initial_history_count"
        const val PRIMARY_OWNER_RUN_ID_KEY = "primary_owner_run_id"
        const val PRIMARY_SOURCE_MESSAGE_ID_KEY = "primary_source_message_id"
        const val SEED_PID_KEY = "seed_pid"
        const val SEED_TIME_KEY = "seed_time"
        const val CHAT_HISTORY_PREFERENCES = "signalasi_chat_history"
        const val CHAT_HISTORY_KEY = "messages"
        const val REPORT_FILENAME = "agent-team-paired-process-death-report.json"
        const val CONNECTION_TIMEOUT_MILLIS = 45_000L
        const val CONNECTOR_TIMEOUT_MILLIS = 60_000L
        const val AGENT_TIMEOUT_MILLIS = 4L * 60L * 1_000L
    }
}
