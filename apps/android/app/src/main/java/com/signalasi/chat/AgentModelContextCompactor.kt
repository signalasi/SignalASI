package com.signalasi.chat

data class CompactedAgentModelContext(
    val messages: List<AgentModelMessage>,
    val originalEstimatedTokens: Int,
    val compactedEstimatedTokens: Int,
    val compacted: Boolean
)

/**
 * Prunes model tool-loop history by token pressure while preserving protocol
 * structure. An assistant tool call and its matching tool results always move
 * together; older completed blocks become a reference-only system summary.
 */
object AgentModelContextCompactor {
    private const val SUMMARY_HEADER = "[EARLIER TOOL ACTIVITY - REFERENCE ONLY]"

    fun compact(
        messages: List<AgentModelMessage>,
        budget: ConversationContextBudget
    ): CompactedAgentModelContext {
        val originalTokens = estimate(messages)
        val trigger = (budget.inputBudgetTokens * budget.triggerRatio).toInt()
        if (originalTokens <= trigger) {
            return CompactedAgentModelContext(messages, originalTokens, originalTokens, false)
        }

        val systemMessages = messages.filter { it.role == AgentModelMessageRole.SYSTEM }
        val blocks = protocolBlocks(messages.filterNot { it.role == AgentModelMessageRole.SYSTEM })
        val targetTokens = (budget.inputBudgetTokens * budget.targetRatio).toInt()
        val summaryAllowance = minOf(
            budget.maximumSummaryTokens,
            (budget.inputBudgetTokens * 0.15).toInt()
        )
        val fixedTokens = estimate(systemMessages)
        val tailAllowance = (targetTokens - fixedTokens - summaryAllowance).coerceAtLeast(512)
        val retainedKeys = linkedSetOf<Int>()
        var retainedTokens = 0
        var retainedBlocks = 0
        blocks.asReversed().forEach { block ->
            val blockTokens = estimate(block.messages)
            val mustKeep = block.unresolvedToolCalls ||
                retainedBlocks < budget.minimumRecentGroups ||
                block.containsLatestUserRequest
            if (mustKeep || retainedTokens + blockTokens <= tailAllowance) {
                retainedKeys += block.index
                retainedTokens += blockTokens
                retainedBlocks += 1
            }
        }
        if (retainedKeys.isEmpty() && blocks.isNotEmpty()) {
            retainedKeys += blocks.last().index
        }

        val olderBlocks = blocks.filterNot { it.index in retainedKeys }
        val recent = blocks.filter { it.index in retainedKeys }.flatMap(ToolProtocolBlock::messages)
        val summary = toolSummary(olderBlocks, summaryAllowance)
        val assembled = buildList {
            addAll(systemMessages)
            if (summary.isNotBlank()) add(AgentModelMessage.system(summary))
            addAll(recent)
        }
        val bounded = if (estimate(assembled) <= budget.inputBudgetTokens) {
            assembled
        } else {
            shrinkOversizedMessages(assembled, budget.inputBudgetTokens)
        }
        return CompactedAgentModelContext(
            messages = bounded,
            originalEstimatedTokens = originalTokens,
            compactedEstimatedTokens = estimate(bounded),
            compacted = olderBlocks.isNotEmpty() || bounded != messages
        )
    }

    private fun protocolBlocks(messages: List<AgentModelMessage>): List<ToolProtocolBlock> {
        if (messages.isEmpty()) return emptyList()
        val latestUserIndex = messages.indexOfLast { it.role == AgentModelMessageRole.USER }
        val blocks = mutableListOf<ToolProtocolBlock>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (message.role == AgentModelMessageRole.ASSISTANT && message.toolCalls.isNotEmpty()) {
                val expected = message.toolCalls.mapTo(linkedSetOf(), AgentModelToolCall::callId)
                val blockMessages = mutableListOf(message)
                var cursor = index + 1
                val resolved = linkedSetOf<String>()
                while (cursor < messages.size && messages[cursor].role == AgentModelMessageRole.TOOL) {
                    val result = requireNotNull(messages[cursor].toolResult)
                    if (result.callId !in expected) break
                    blockMessages += messages[cursor]
                    resolved += result.callId
                    cursor += 1
                }
                blocks += ToolProtocolBlock(
                    index = blocks.size,
                    messages = blockMessages,
                    unresolvedToolCalls = resolved != expected,
                    containsLatestUserRequest = latestUserIndex in index until cursor
                )
                index = cursor
            } else {
                blocks += ToolProtocolBlock(
                    index = blocks.size,
                    messages = listOf(message),
                    unresolvedToolCalls = false,
                    containsLatestUserRequest = index == latestUserIndex
                )
                index += 1
            }
        }
        return blocks
    }

    private fun toolSummary(blocks: List<ToolProtocolBlock>, maximumTokens: Int): String {
        if (blocks.isEmpty() || maximumTokens <= 0) return ""
        val candidates = blocks.asSequence()
            .flatMap { block -> block.messages.asSequence() }
            .mapNotNull { message ->
                when (message.role) {
                    AgentModelMessageRole.SYSTEM -> null
                    AgentModelMessageRole.USER ->
                        message.text.cleanLine().takeIf(String::isNotBlank)?.let { "User goal: $it" }
                    AgentModelMessageRole.ASSISTANT -> when {
                        message.toolCalls.isNotEmpty() -> message.toolCalls.joinToString(
                            prefix = "Requested tools: ",
                            separator = ", "
                        ) { it.toolId }
                        else -> message.text.cleanLine()
                            .takeIf(String::isNotBlank)
                            ?.let { "Assistant outcome: $it" }
                    }
                    AgentModelMessageRole.TOOL -> requireNotNull(message.toolResult).let { result ->
                        val detail = result.message.ifBlank {
                            result.error?.message.orEmpty()
                        }.cleanLine()
                        buildString {
                            append("Tool ").append(result.toolId).append(": ").append(result.status)
                            if (detail.isNotBlank()) append(" - ").append(detail)
                        }
                    }
                }
            }
            .distinct()
            .toList()
        val lines = mutableListOf<String>()
        var usedTokens = 0
        candidates.asReversed().forEach { candidate ->
            val tokens = ConversationContextCompactor.estimateTokens(candidate) + 2
            if (lines.isEmpty() || usedTokens + tokens <= maximumTokens) {
                lines += candidate
                usedTokens += tokens
            }
        }
        lines.reverse()
        if (lines.isEmpty()) return ""
        return ConversationContextCompactor.fitTextToTokenBudget(
            buildString {
                append(SUMMARY_HEADER).append('\n')
                append("Completed earlier activity only; do not repeat it unless the current result requires it.\n")
                lines.forEach { append("- ").append(it).append('\n') }
            }.trim(),
            maximumTokens
        )
    }

    private fun shrinkOversizedMessages(
        messages: List<AgentModelMessage>,
        maximumTokens: Int
    ): List<AgentModelMessage> {
        var current = messages
        var estimate = estimate(current)
        if (estimate <= maximumTokens) return current
        val reducible = current.indices.filter { index ->
            current[index].role == AgentModelMessageRole.TOOL ||
                current[index].role == AgentModelMessageRole.ASSISTANT
        }
        for (index in reducible) {
            val message = current[index]
            val replacement = when (message.role) {
                AgentModelMessageRole.TOOL -> {
                    val result = requireNotNull(message.toolResult)
                    val summary = listOf(
                        result.message,
                        result.error?.message.orEmpty(),
                        runCatching { AgentNativeJsonCodec.stringify(result.output) }.getOrDefault("")
                    ).firstOrNull(String::isNotBlank).orEmpty()
                    message.copy(
                        toolResult = result.copy(
                            output = linkedMapOf(
                                "compacted" to true,
                                "summary" to ConversationContextCompactor.fitTextToTokenBudget(summary, 160)
                            ),
                            message = ConversationContextCompactor.fitTextToTokenBudget(
                                result.message.ifBlank { summary },
                                160
                            ),
                            receipt = null,
                            nativeResult = null
                        )
                    )
                }
                AgentModelMessageRole.ASSISTANT -> message.copy(
                    text = ConversationContextCompactor.fitTextToTokenBudget(message.text, 256),
                    toolCalls = message.toolCalls.map { call ->
                        call.copy(arguments = compactObject(call.arguments, 80))
                    }
                )
                else -> message
            }
            current = current.toMutableList().also { it[index] = replacement }
            estimate = estimate(current)
            if (estimate <= maximumTokens) return current
        }
        return current
    }

    private fun estimate(messages: List<AgentModelMessage>): Int = messages.sumOf { message ->
        var tokens = ConversationContextCompactor.estimateTokens(message.text) + 6
        message.toolCalls.forEach { call ->
            tokens += ConversationContextCompactor.estimateTokens(call.toolId)
            tokens += ConversationContextCompactor.estimateTokens(
                runCatching { AgentNativeJsonCodec.stringify(call.arguments) }.getOrDefault("")
            )
        }
        message.toolResult?.let { result ->
            tokens += ConversationContextCompactor.estimateTokens(
                runCatching { AgentNativeJsonCodec.stringify(result.toJsonValue()) }
                    .getOrDefault(result.message)
            )
        }
        tokens
    }

    private fun compactObject(value: AgentNativeJsonObject, tokenLimit: Int): AgentNativeJsonObject =
        value.mapValuesTo(linkedMapOf()) { (_, item) -> compactValue(item, tokenLimit) }

    private fun compactValue(value: Any?, tokenLimit: Int): Any? = when (value) {
        is String -> ConversationContextCompactor.fitTextToTokenBudget(value, tokenLimit)
        is Map<*, *> -> value.entries.associateTo(linkedMapOf()) { entry ->
            entry.key.toString() to compactValue(entry.value, tokenLimit)
        }
        is List<*> -> value.take(12).map { compactValue(it, tokenLimit) }
        else -> value
    }

    private fun String.cleanLine(): String =
        replace(Regex("\\s+"), " ").trim().let { clean ->
            if (clean.length <= 420) clean else clean.take(419) + "\u2026"
        }

    private data class ToolProtocolBlock(
        val index: Int,
        val messages: List<AgentModelMessage>,
        val unresolvedToolCalls: Boolean,
        val containsLatestUserRequest: Boolean
    )
}
