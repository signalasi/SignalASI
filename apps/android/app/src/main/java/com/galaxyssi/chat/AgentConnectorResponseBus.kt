package com.galaxyssi.chat

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

data class AgentConnectorResponse(
    val sourceMessageId: Long,
    val contactId: String,
    val content: String,
    val conversationId: String = "",
    val turnId: String = "",
    val taskId: String = "",
    val success: Boolean = true,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val costMicros: Long = 0L,
    val richOutputJson: String = "",
    val receivedAtMillis: Long = System.currentTimeMillis(),
    val resolvedContactId: String = "",
    val providerAttempts: AgentProviderAttemptReport? = null,
    val taskStatus: String = "",
    val executionGeneration: Long = 1L,
    val statusSequence: Long = -1L
) {
    val executionContactId: String
        get() = resolvedContactId.ifBlank { contactId }

    val remoteFailure: Boolean
        get() = taskStatus in AgentRemoteOutcomeCodec.FAILURES

    val executionVersion: AgentRemoteExecutionVersion
        get() = AgentRemoteExecutionVersion(executionGeneration, statusSequence)
}

data class AgentConnectorStreamUpdate(
    val sourceMessageId: Long,
    val contactId: String,
    val content: String,
    val conversationId: String = "",
    val turnId: String = "",
    val taskId: String = "",
    val firstDelta: Boolean = false,
    val attemptOrdinal: Int = 0,
    val receivedAtMillis: Long = System.currentTimeMillis()
)

fun interface AgentConnectorResponseListener {
    fun onConnectorResponse(response: AgentConnectorResponse)
}

fun interface AgentConnectorStreamListener {
    fun onConnectorStreamUpdate(update: AgentConnectorStreamUpdate)
}

/**
 * Ephemeral model output for the active UI. Final connector responses still use the durable,
 * one-shot response bus below so an interrupted stream cannot become conversation history.
 */
object AgentConnectorStreamBus {
    private val listeners = CopyOnWriteArraySet<AgentConnectorStreamListener>()

    fun addListener(listener: AgentConnectorStreamListener) {
        listeners += listener
    }

    fun removeListener(listener: AgentConnectorStreamListener) {
        listeners -= listener
    }

    fun publish(update: AgentConnectorStreamUpdate): Boolean {
        if (update.sourceMessageId <= 0L) return false
        if (AgentManagedConnectorResponseRegistry.contains(update)) return true
        listeners.forEach { listener -> listener.onConnectorStreamUpdate(update) }
        return listeners.isNotEmpty()
    }

    fun publish(context: Context, update: AgentConnectorStreamUpdate): Boolean {
        AgentGlobalRunSlotStore(context).touchBySourceMessageId(update.sourceMessageId, update.receivedAtMillis)
        return publish(update)
    }

    fun recordActivity(context: Context, update: AgentConnectorStreamUpdate) {
        AgentGlobalRunSlotStore(context).touchBySourceMessageId(update.sourceMessageId, update.receivedAtMillis)
    }
}

object AgentConnectorResponseBus {
    private val listeners = CopyOnWriteArraySet<AgentConnectorResponseListener>()

    fun addListener(listener: AgentConnectorResponseListener) {
        listeners += listener
    }

    fun removeListener(listener: AgentConnectorResponseListener) {
        listeners -= listener
    }

    fun publish(context: Context, response: AgentConnectorResponse): Boolean {
        if (response.sourceMessageId <= 0L) return false
        AgentGlobalRunSlotStore(context).releaseBySourceMessageId(response.sourceMessageId)
        val richOutput = AgentRichContentMaterializer.materialize(context, response.richOutputJson)
        val normalized = response.copy(
            content = response.content.ifBlank { AgentRichContentCodec.fallbackText(richOutput) },
            richOutputJson = richOutput
        )
        if (normalized.content.isBlank() && normalized.richOutputJson.isBlank()) return false
        if (!AgentConnectorResponseStore.isCurrentExecution(context, normalized)) return false
        if (AgentManagedConnectorResponseRegistry.consume(normalized)) return true
        if (EncryptedAgentManagedResponseLedger(context).complete(normalized) != null) return true
        val durable = if (AgentConnectorResponseStore.append(context, normalized)) normalized
            else AgentConnectorResponseStore.find(context, normalized)
        if (durable != null) {
            listeners.forEach { listener -> listener.onConnectorResponse(durable) }
        }
        return false
    }
}

/**
 * One-shot response interception for host-managed Agent runs. Internal team
 * replies must return to their supervisor instead of appearing as independent
 * assistant messages in the user transcript.
 */
internal object AgentManagedConnectorResponseRegistry {
    private data class Interceptor(
        val ownerId: String,
        val conversationId: String,
        val turnId: String,
        val taskId: String,
        val consume: (AgentConnectorResponse) -> Boolean
    )

    private val interceptors = ConcurrentHashMap<String, Interceptor>()

    fun register(
        sourceMessageId: Long,
        contactId: String,
        ownerId: String,
        conversationId: String = "",
        turnId: String = "",
        taskId: String = "",
        consume: (AgentConnectorResponse) -> Boolean
    ) {
        require(sourceMessageId > 0L) { "Managed response source id must be positive" }
        require(ownerId.isNotBlank()) { "Managed response owner id must not be blank" }
        interceptors[key(sourceMessageId, contactId)] = Interceptor(
            ownerId = ownerId,
            conversationId = conversationId,
            turnId = turnId,
            taskId = taskId,
            consume = consume
        )
    }

    fun consume(response: AgentConnectorResponse): Boolean {
        val entry = matchingEntry(
            sourceMessageId = response.sourceMessageId,
            contactId = response.contactId,
            conversationId = response.conversationId,
            turnId = response.turnId,
            taskId = response.taskId
        ) ?: return false
        val interceptor = entry.second
        if (!interceptors.remove(entry.first, interceptor)) return false
        return runCatching { interceptor.consume(response) }.getOrDefault(false)
    }

    fun contains(response: AgentConnectorResponse): Boolean = matchingEntry(
        sourceMessageId = response.sourceMessageId,
        contactId = response.contactId,
        conversationId = response.conversationId,
        turnId = response.turnId,
        taskId = response.taskId
    ) != null

    fun contains(update: AgentConnectorStreamUpdate): Boolean {
        return matchingEntry(
            sourceMessageId = update.sourceMessageId,
            contactId = update.contactId,
            conversationId = update.conversationId,
            turnId = update.turnId,
            taskId = update.taskId
        ) != null
    }

    fun unregisterOwner(ownerId: String) {
        if (ownerId.isBlank()) return
        interceptors.entries.removeIf { it.value.ownerId == ownerId }
    }

    fun clear() = interceptors.clear()

    private fun key(sourceMessageId: Long, contactId: String): String =
        "$sourceMessageId:${contactId.trim()}"

    private fun matchingEntry(
        sourceMessageId: Long,
        contactId: String,
        conversationId: String,
        turnId: String,
        taskId: String
    ): Pair<String, Interceptor>? {
        if (sourceMessageId <= 0L) return null
        val exactKey = key(sourceMessageId, contactId)
        val wildcardKey = key(sourceMessageId, "")
        interceptors[exactKey]?.takeIf {
            managedIdentityMatches(it, conversationId, turnId, taskId)
        }?.let { return exactKey to it }
        interceptors[wildcardKey]?.takeIf {
            managedIdentityMatches(it, conversationId, turnId, taskId)
        }?.let { return wildcardKey to it }

        // Connector aliases can resolve to the same paired runtime under another
        // contact id. Accept only one unambiguous source-and-turn match.
        return interceptors.entries.asSequence()
            .filter { it.key.startsWith("$sourceMessageId:") }
            .filter { managedIdentityMatches(it.value, conversationId, turnId, taskId) }
            .take(2)
            .map { it.key to it.value }
            .toList()
            .singleOrNull()
    }

    private fun managedIdentityMatches(
        interceptor: Interceptor,
        response: AgentConnectorResponse
    ): Boolean = managedIdentityMatches(
        interceptor = interceptor,
        conversationId = response.conversationId,
        turnId = response.turnId,
        taskId = response.taskId
    )

    private fun managedIdentityMatches(
        interceptor: Interceptor,
        conversationId: String,
        turnId: String,
        taskId: String
    ): Boolean {
        val expectedHasTurnIdentity =
            interceptor.conversationId.isNotBlank() || interceptor.turnId.isNotBlank()
        val actualHasTurnIdentity = conversationId.isNotBlank() || turnId.isNotBlank()
        if (!expectedHasTurnIdentity && !actualHasTurnIdentity) return true
        if (!expectedHasTurnIdentity || !actualHasTurnIdentity) return false
        if (interceptor.conversationId != conversationId ||
            interceptor.turnId != turnId
        ) return false
        return interceptor.taskId.isBlank() || taskId.isBlank() || interceptor.taskId == taskId
    }
}
