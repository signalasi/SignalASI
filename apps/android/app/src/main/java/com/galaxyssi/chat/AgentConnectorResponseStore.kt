package com.galaxyssi.chat

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

object AgentConnectorResponseStore {
    private val stores = ConcurrentHashMap<String, AgentConnectorResponseInbox>()

    private fun store(context: Context): AgentConnectorResponseInbox {
        val app = context.applicationContext
        val path = app.getDatabasePath(AgentConnectorResponseInbox.DATABASE_NAME).absolutePath
        return stores.computeIfAbsent(path) { AgentConnectorResponseInbox(app) }
    }

    fun append(context: Context, response: AgentConnectorResponse): Boolean {
        if (superseded(context, response)) return false
        return store(context).append(response)
    }

    internal fun observeExecution(context: Context, response: AgentConnectorResponse, finalReply: Boolean = false): Boolean =
        !superseded(context, response) && store(context).observeExecution(response, finalReply)

    internal fun isCurrentExecution(context: Context, response: AgentConnectorResponse): Boolean =
        store(context).isCurrentExecution(response)

    /** One bounded page, not the entire recoverable inbox. */
    fun pending(context: Context): List<AgentConnectorResponse> = pendingPage(context).responses

    internal fun pendingPage(context: Context, afterSequence: Long = 0,
                             throughSequence: Long = Long.MAX_VALUE): AgentConnectorInboxPage {
        val inbox = store(context)
        val page = inbox.page(afterSequence, throughSequence)
        return page.copy(responses = page.responses.filterNot { response ->
            (superseded(context, response) || !inbox.isCurrentExecution(response)).also { if (it) inbox.acknowledge(response) }
        })
    }

    internal fun highWatermark(context: Context): Long = store(context).highWatermark()

    internal fun wasRecorded(context: Context, response: AgentConnectorResponse): Boolean =
        store(context).wasRecorded(response)

    fun containsTurn(context: Context, conversationId: String, turnId: String): Boolean =
        store(context).containsTurn(conversationId, turnId)

    fun remove(context: Context, response: AgentConnectorResponse): Boolean = store(context).acknowledge(response)

    fun contains(context: Context, response: AgentConnectorResponse): Boolean =
        !superseded(context, response) && store(context).contains(response)

    internal fun find(context: Context, response: AgentConnectorResponse): AgentConnectorResponse? =
        if (superseded(context, response)) null else store(context).find(response)

    fun removeHandled(context: Context, response: AgentConnectorResponse, terminal: Boolean) {
        if (terminal && response.conversationId.isNotBlank() && response.turnId.isNotBlank()) {
            store(context).acknowledgeThrough(response)
        } else remove(context, response)
    }

    internal fun retainedAfterHandledResponse(responses: List<AgentConnectorResponse>,
                                             handled: AgentConnectorResponse, terminal: Boolean): List<AgentConnectorResponse> =
        responses.filterNot { candidate ->
            if (terminal && handled.conversationId.isNotBlank() && handled.turnId.isNotBlank()) {
                candidate.conversationId == handled.conversationId && candidate.turnId == handled.turnId
                    && !(AgentConnectorResponseCodec.scopeIdentity(candidate) == AgentConnectorResponseCodec.scopeIdentity(handled)
                        && candidate.executionGeneration > handled.executionGeneration)
            } else matches(candidate, handled)
        }

    internal fun matches(candidate: AgentConnectorResponse, expected: AgentConnectorResponse): Boolean =
        AgentConnectorResponseCodec.matches(candidate, expected)

    fun removeTurn(context: Context, conversationId: String, turnId: String) =
        store(context).acknowledgeTurn(conversationId, turnId)

    fun clear(context: Context) {
        store(context).clear()
        closeStore(context)
    }

    private fun closeStore(context: Context) {
        val path = context.applicationContext.getDatabasePath(AgentConnectorResponseInbox.DATABASE_NAME).absolutePath
        stores.remove(path)?.close()
    }

    private fun superseded(context: Context, response: AgentConnectorResponse): Boolean =
        AgentPendingDeliveryStore.isSuperseded(context, response.sourceMessageId, response.conversationId, response.turnId)
}
