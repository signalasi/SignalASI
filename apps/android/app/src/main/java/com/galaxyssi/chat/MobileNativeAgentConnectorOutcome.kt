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
    if (response.deliveryFailureCode.isNotBlank()) {
        val pending = lastActionResult?.metadata ?: return null
        if (response.success || response.deliveryFailureCode !in com.galaxyssi.chat.blob.BlobFailureContract.terminalCodes ||
            conversationId.isBlank() || turnId.isBlank() || taskId.isBlank() ||
            response.conversationId != conversationId || response.turnId != turnId || response.taskId != taskId ||
            pending["conversation_id"] != conversationId || pending["turn_id"] != turnId ||
            pending["remote_task_id"].orEmpty().ifBlank { pending["task_id"].orEmpty() } != taskId ||
            expectedSourceMessageId != response.sourceMessageId || !canAcceptConnectorResponse(
                response.sourceMessageId, response.contactId, conversationId, turnId, taskId)) return null
        return handleConnectorDeliveryFailure(response.sourceMessageId, response.content, response.deliveryFailureCode)
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
