package com.galaxyssi.chat

internal fun MobileNativeAgent.acceptConnectorOutcome(
    response: AgentConnectorResponse,
    conversationId: String = response.conversationId,
    turnId: String = response.turnId,
    taskId: String = response.taskId,
    expectedSourceMessageId: Long = response.sourceMessageId
): AgentUiState? {
    if (response.executionGeneration < (lastActionResult?.metadata?.get("remote_execution_generation")?.toLongOrNull() ?: 1L)) {
        return snapshot()
    }
    if (response.remoteFailure) return acceptConnectorTerminalStatus(
        sourceMessageId = response.sourceMessageId, contactId = response.contactId,
        taskId = taskId, taskStatus = response.taskStatus, statusSeq = response.statusSequence,
        message = response.content, conversationId = conversationId, turnId = turnId,
        expectedSourceMessageId = expectedSourceMessageId, executionGeneration = response.executionGeneration,
        canonicalReply = true)
    return acceptConnectorResponse(
        sourceMessageId = response.sourceMessageId, contactId = response.contactId, content = response.content,
        success = response.success, richOutputJson = response.richOutputJson, conversationId = conversationId,
        turnId = turnId, taskId = taskId, providerAttempts = response.providerAttempts,
        inputTokens = response.inputTokens, outputTokens = response.outputTokens, costMicros = response.costMicros,
        networkBytes = (response.content.toByteArray(Charsets.UTF_8).size +
            response.richOutputJson.toByteArray(Charsets.UTF_8).size).toLong(),
        expectedSourceMessageId = expectedSourceMessageId)
}
