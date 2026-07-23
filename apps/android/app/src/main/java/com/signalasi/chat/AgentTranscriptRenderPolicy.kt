package com.signalasi.chat

data class AgentTranscriptRenderDiff(
    val reset: Boolean,
    val replacementIndices: List<Int>,
    val appendFromIndex: Int
)

object AgentTranscriptRenderPolicy {
    fun signature(entry: AgentTranscriptEntry): Int = entry.hashCode()

    fun diff(
        renderedIds: List<String>,
        renderedSignatures: Map<String, Int>,
        incoming: List<AgentTranscriptEntry>
    ): AgentTranscriptRenderDiff {
        val incomingIds = incoming.map(AgentTranscriptEntry::id)
        val hasStablePrefix = renderedIds.size <= incomingIds.size &&
            incomingIds.take(renderedIds.size) == renderedIds
        if (!hasStablePrefix) {
            return AgentTranscriptRenderDiff(
                reset = true,
                replacementIndices = emptyList(),
                appendFromIndex = 0
            )
        }
        val replacements = renderedIds.indices.filter { index ->
            val entry = incoming[index]
            renderedSignatures[entry.id] != signature(entry)
        }
        return AgentTranscriptRenderDiff(
            reset = false,
            replacementIndices = replacements,
            appendFromIndex = renderedIds.size
        )
    }
}
