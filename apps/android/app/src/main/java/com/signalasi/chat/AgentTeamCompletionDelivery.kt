package com.signalasi.chat

import android.content.Context
import org.json.JSONArray

interface AgentTeamCompletionSink {
    fun publish(snapshot: AgentTeamExecutionSnapshot): Boolean

    fun clear() = Unit
}

/** Delivers exactly one supervised team result into the originating Agent task. */
internal class AgentConnectorTeamCompletionSink(
    context: Context,
    private val ledger: AgentTeamCompletionDeliveryLedger = AgentTeamCompletionDeliveryLedger(context)
) : AgentTeamCompletionSink {
    private val appContext = context.applicationContext

    override fun publish(snapshot: AgentTeamExecutionSnapshot): Boolean {
        if (snapshot.state !in DELIVERABLE_STATES || ledger.contains(snapshot.supervisorRunId)) return false
        val primaryOutput = snapshot.finalOutput.trim()
        val successful = primaryOutput.isNotBlank() && snapshot.state in setOf(
            AgentTeamExecutionState.SUCCEEDED,
            AgentTeamExecutionState.COMPLETED_WITH_FAILURES
        )
        val content = if (successful) {
            primaryOutput
        } else {
            snapshot.members.asSequence()
                .map(AgentTeamMemberSnapshot::errorMessage)
                .firstOrNull(String::isNotBlank)
                ?.let {
                    appContext.getString(
                        R.string.agent_team_failed_response_with_reason,
                        it.take(MAX_ERROR_CHARACTERS)
                    )
                }
                ?: appContext.getString(R.string.agent_team_failed_response)
        }
        AgentConnectorResponseBus.publish(
            appContext,
            AgentConnectorResponse(
                sourceMessageId = AgentTeamDispatchIds.sourceMessageId(snapshot.supervisorRunId),
                contactId = AgentTeamDispatchIds.responseContactId(snapshot.teamId),
                content = content.take(MAX_OUTPUT_CHARACTERS),
                conversationId = snapshot.conversationId,
                turnId = snapshot.taskId,
                taskId = snapshot.taskId,
                success = successful,
                receivedAtMillis = snapshot.updatedAtMillis.coerceAtLeast(System.currentTimeMillis())
            )
        )
        ledger.mark(snapshot.supervisorRunId)
        return true
    }

    override fun clear() = ledger.clear()

    private companion object {
        val DELIVERABLE_STATES = setOf(
            AgentTeamExecutionState.SUCCEEDED,
            AgentTeamExecutionState.COMPLETED_WITH_FAILURES,
            AgentTeamExecutionState.FAILED,
            AgentTeamExecutionState.CANCELLED
        )
        const val MAX_OUTPUT_CHARACTERS = 24_000
        const val MAX_ERROR_CHARACTERS = 1_000
    }
}

internal class AgentTeamCompletionDeliveryLedger(context: Context) {
    private val database = AgentEncryptedPreferences(context.applicationContext, DATABASE)

    @Synchronized
    fun contains(supervisorRunId: String): Boolean = supervisorRunId.trim() in read()

    @Synchronized
    fun mark(supervisorRunId: String) {
        val clean = supervisorRunId.trim()
        if (clean.isBlank()) return
        val values = read().filterNot { it == clean }.plus(clean).takeLast(MAX_RECORDS)
        database.writeString(KEY_DELIVERED, JSONArray(values).toString())
    }

    @Synchronized
    fun clear() = database.clear()

    private fun read(): List<String> = runCatching {
        val array = JSONArray(database.readString(KEY_DELIVERED, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val DATABASE = "signalasi_agent_team_completion_v1"
        const val KEY_DELIVERED = "delivered_supervisor_runs"
        const val MAX_RECORDS = 512
    }
}
