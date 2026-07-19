package com.signalasi.chat

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Converts rich UI state and executed runs into bounded, non-binary global observations. */
object GlobalRichObservationExtractor {
    fun transcriptEventType(
        entry: AgentTranscriptEntry,
        updated: Boolean
    ): GlobalConversationEventType {
        if (entry.role == AgentTranscriptRole.PROCESS &&
            AgentTranscriptPresentationPolicy.processContentKind(entry) ==
            AgentTranscriptPresentationPolicy.ProcessContentKind.TOOL_ACTIVITY
        ) {
            return toolLifecycleType(entry.text, emptyMap()) ?: GlobalConversationEventType.TOOL_STARTED
        }
        return if (updated) {
            GlobalConversationEventType.MESSAGE_UPDATED
        } else {
            GlobalConversationEventType.MESSAGE_CREATED
        }
    }

    fun extract(
        conversation: AgentConversation,
        entry: AgentTranscriptEntry,
        rootEventId: String
    ): List<GlobalConversationEvent> {
        if (entry.richOutputJson.isBlank() || conversation.privateMode || conversation.trackingPaused) {
            return emptyList()
        }
        val sensitivity = if (conversation.privateMode) {
            GlobalConversationSensitivity.SESSION_PRIVATE
        } else {
            GlobalConversationSensitivity.PERSONAL
        }
        val actor = when {
            entry.dedupeKey.startsWith("global-agent:") -> GlobalConversationActor.GLOBAL_AGENT
            entry.role == AgentTranscriptRole.USER -> GlobalConversationActor.USER
            entry.role == AgentTranscriptRole.ASSISTANT -> GlobalConversationActor.ASSISTANT
            else -> GlobalConversationActor.TOOL
        }
        val seen = mutableSetOf<String>()
        return AgentRichContentCodec.decode(entry.richOutputJson).mapNotNull { block ->
            val eventType = classify(entry.role, block) ?: return@mapNotNull null
            val fingerprint = GlobalAgentText.stableKey(
                block.id,
                block.type.name,
                block.title,
                block.uri,
                block.mimeType,
                block.text.take(512),
                block.fallbackText.take(256)
            )
            if (!seen.add("${eventType.name}:$fingerprint")) return@mapNotNull null
            val resourceName = resourceName(block)
            val content = observationSummary(eventType, block, resourceName)
            val toolStatus = when (eventType) {
                GlobalConversationEventType.TOOL_COMPLETED -> "completed"
                GlobalConversationEventType.TOOL_CANCELLED -> "cancelled"
                GlobalConversationEventType.TOOL_FAILED -> "failed"
                GlobalConversationEventType.TOOL_STARTED -> "started"
                else -> ""
            }
            val metadata = buildMap {
                put("origin", "rich_transcript")
                put("block_id", block.id.take(120))
                put("block_type", block.type.name.lowercase(Locale.ROOT))
                put("resource_name", resourceName.take(240))
                put("mime_type", block.mimeType.take(160))
                put("resource_scheme", resourceScheme(block.uri))
                put("inline_data", block.dataB64.isNotBlank().toString())
                put("turn_id", entry.turnId)
                put("task_id", entry.taskId)
                if (toolStatus.isNotBlank()) put("tool_status", toolStatus)
                if (eventType in TOOL_EVENTS) put("tool_key", toolKey(entry, block))
                block.metadata.entries.asSequence()
                    .filter { (key, _) -> safeMetadataKey(key) }
                    .take(MAX_COPIED_METADATA)
                    .forEach { (key, value) -> put("rich_${key.take(64)}", value.take(256)) }
            }
            GlobalConversationEvent(
                id = "rich:${entry.id}:${eventType.name.lowercase(Locale.ROOT)}:${fingerprint.take(24)}",
                type = eventType,
                conversationId = entry.conversationId,
                messageId = entry.id,
                actor = if (eventType in TOOL_EVENTS) GlobalConversationActor.TOOL else actor,
                timestampMillis = entry.timestampMillis,
                content = content.take(MAX_OBSERVATION_CONTENT),
                contentRef = "encrypted://agent-transcript/${entry.conversationId}/${entry.id}#rich=${fingerprint.take(24)}",
                conversationTitle = conversation.title,
                topicHints = setOf(conversation.title)
                    .filterNot { it.equals("New session", ignoreCase = true) }
                    .toSet(),
                sensitivity = sensitivity,
                metadata = metadata,
                causalEventIds = setOf(rootEventId)
            )
        }
    }

    fun toolLifecycleType(
        content: String,
        metadata: Map<String, String>
    ): GlobalConversationEventType? {
        val state = buildString {
            append(content)
            metadata["status"]?.let { append(' ').append(it) }
            metadata["state"]?.let { append(' ').append(it) }
            metadata["phase"]?.let { append(' ').append(it) }
        }.lowercase(Locale.ROOT)
        if (state.isBlank()) return null
        return when {
            CANCELLATION_SIGNALS.any(state::contains) -> GlobalConversationEventType.TOOL_CANCELLED
            FAILURE_SIGNALS.any(state::contains) -> GlobalConversationEventType.TOOL_FAILED
            COMPLETION_SIGNALS.any(state::contains) -> GlobalConversationEventType.TOOL_COMPLETED
            else -> GlobalConversationEventType.TOOL_STARTED
        }
    }

    private fun classify(
        role: AgentTranscriptRole,
        block: AgentRichBlock
    ): GlobalConversationEventType? {
        if (block.type in TOOL_BLOCKS) {
            return toolLifecycleType(
                listOf(block.title, block.text, block.fallbackText).joinToString(" "),
                block.metadata
            )
        }
        if (role == AgentTranscriptRole.USER && block.type in ATTACHMENT_BLOCKS) {
            return GlobalConversationEventType.ATTACHMENT_ADDED
        }
        if (role != AgentTranscriptRole.USER && isArtifact(block)) {
            return GlobalConversationEventType.ARTIFACT_CREATED
        }
        return null
    }

    private fun isArtifact(block: AgentRichBlock): Boolean =
        block.type in ARTIFACT_BLOCKS ||
            (block.type in CONDITIONAL_ARTIFACT_BLOCKS && (
                block.uri.isNotBlank() ||
                    block.dataB64.isNotBlank() ||
                    block.title.isNotBlank() ||
                    block.metadata["artifact"] == "true" ||
                    block.metadata["runtime_artifact"] == "true"
                ))

    private fun observationSummary(
        type: GlobalConversationEventType,
        block: AgentRichBlock,
        resourceName: String
    ): String {
        val details = when (block.type) {
            AgentRichBlockType.TABLE -> {
                val columns = block.columns.filter(String::isNotBlank).take(8).joinToString(", ")
                "${block.rows.size} rows${columns.takeIf(String::isNotBlank)?.let { "; columns: $it" }.orEmpty()}"
            }
            AgentRichBlockType.CODE,
            AgentRichBlockType.DIFF,
            AgentRichBlockType.JSON -> block.language.takeIf(String::isNotBlank).orEmpty()
            else -> listOf(block.mimeType, block.metadata["size"], block.metadata["size_bytes"])
                .filterNotNull().filter(String::isNotBlank).distinct().joinToString(", ")
        }
        val suffix = details.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()
        return when (type) {
            GlobalConversationEventType.ATTACHMENT_ADDED -> "Attached ${blockLabel(block)}: $resourceName$suffix"
            GlobalConversationEventType.ARTIFACT_CREATED -> "Created ${blockLabel(block)}: $resourceName$suffix"
            GlobalConversationEventType.TOOL_STARTED -> toolSummary("started", block, resourceName)
            GlobalConversationEventType.TOOL_COMPLETED -> toolSummary("completed", block, resourceName)
            GlobalConversationEventType.TOOL_CANCELLED -> toolSummary("cancelled", block, resourceName)
            GlobalConversationEventType.TOOL_FAILED -> toolSummary("failed", block, resourceName)
            else -> resourceName
        }
    }

    private fun toolSummary(status: String, block: AgentRichBlock, fallback: String): String {
        val detail = listOf(block.title, block.text, block.fallbackText)
            .firstOrNull(String::isNotBlank)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(800)
            .orEmpty()
        return "Tool $status: ${detail.ifBlank { fallback }}"
    }

    private fun resourceName(block: AgentRichBlock): String = listOf(
        block.title,
        block.metadata["display_name"],
        block.metadata["name"],
        block.fallbackText,
        block.uri.substringAfterLast('/').substringBefore('?').substringBefore('#')
    ).filterNotNull().firstOrNull(String::isNotBlank)
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(240)
        .orEmpty()
        .ifBlank { blockLabel(block) }

    private fun blockLabel(block: AgentRichBlock): String = when (block.type) {
        AgentRichBlockType.IMAGE, AgentRichBlockType.GALLERY -> "image"
        AgentRichBlockType.VIDEO -> "video"
        AgentRichBlockType.AUDIO -> "audio"
        AgentRichBlockType.TABLE -> "table"
        AgentRichBlockType.CHART -> "chart"
        AgentRichBlockType.CODE -> "code"
        AgentRichBlockType.DIFF -> "diff"
        AgentRichBlockType.JSON -> "JSON"
        AgentRichBlockType.HTML, AgentRichBlockType.WEBPAGE -> "web artifact"
        AgentRichBlockType.TOOL, AgentRichBlockType.STATUS, AgentRichBlockType.PROGRESS -> "operation"
        else -> "file"
    }

    private fun resourceScheme(uri: String): String = uri.substringBefore(':', "")
        .lowercase(Locale.ROOT)
        .take(24)

    private fun toolKey(entry: AgentTranscriptEntry, block: AgentRichBlock): String = listOf(
        block.metadata["tool_run_id"],
        block.metadata["tool_call_id"],
        block.metadata["tool_id"],
        block.metadata["tool_name"],
        block.id,
        entry.taskId,
        entry.turnId
    ).filterNotNull().firstOrNull(String::isNotBlank)?.take(160).orEmpty()

    private fun safeMetadataKey(key: String): Boolean = key.lowercase(Locale.ROOT) in SAFE_METADATA_KEYS

    private val ATTACHMENT_BLOCKS = setOf(
        AgentRichBlockType.FILE,
        AgentRichBlockType.IMAGE,
        AgentRichBlockType.GALLERY,
        AgentRichBlockType.VIDEO,
        AgentRichBlockType.AUDIO
    )
    private val ARTIFACT_BLOCKS = ATTACHMENT_BLOCKS + setOf(
        AgentRichBlockType.TABLE,
        AgentRichBlockType.CHART,
        AgentRichBlockType.DIFF,
        AgentRichBlockType.HTML,
        AgentRichBlockType.WEBPAGE
    )
    private val CONDITIONAL_ARTIFACT_BLOCKS = setOf(
        AgentRichBlockType.CODE,
        AgentRichBlockType.JSON
    )
    private val TOOL_BLOCKS = setOf(
        AgentRichBlockType.TOOL,
        AgentRichBlockType.STATUS,
        AgentRichBlockType.PROGRESS
    )
    private val TOOL_EVENTS = setOf(
        GlobalConversationEventType.TOOL_STARTED,
        GlobalConversationEventType.TOOL_COMPLETED,
        GlobalConversationEventType.TOOL_CANCELLED,
        GlobalConversationEventType.TOOL_FAILED
    )
    private val FAILURE_SIGNALS = listOf(
        "failed", "failure", "error", "timed out", "timeout", "blocked",
        "\u5931\u8d25", "\u9519\u8bef", "\u8d85\u65f6", "\u963b\u585e"
    )
    private val CANCELLATION_SIGNALS = listOf(
        "cancelled", "canceled", "aborted", "stopped by user", "\u53d6\u6d88", "\u7528\u6237\u505c\u6b62"
    )
    private val COMPLETION_SIGNALS = listOf(
        "completed", "complete", "succeeded", "success", "finished", "done", "ready",
        "\u5df2\u5b8c\u6210", "\u5b8c\u6210", "\u6210\u529f", "\u5df2\u5c31\u7eea"
    )
    private val SAFE_METADATA_KEYS = setOf(
        "artifact", "artifact_kind", "detail", "display_name", "duration", "duration_ms", "file_count",
        "format", "language", "name", "phase", "runtime_artifact", "sha256", "size", "size_bytes",
        "state", "status", "tool_call_id", "tool_id", "tool_name", "tool_run_id", "verified"
    )
    private const val MAX_COPIED_METADATA = 12
    private const val MAX_OBSERVATION_CONTENT = 1_200
}

object GlobalRecordedRunObservationExtractor {
    fun started(run: AgentRecordedRun, conversationTitle: String = ""): GlobalConversationEvent =
        GlobalConversationEvent(
            id = startedEventId(run.runId),
            type = GlobalConversationEventType.TASK_UPDATED,
            conversationId = run.conversationId,
            messageId = run.runId,
            actor = GlobalConversationActor.TOOL,
            timestampMillis = run.createdAtMillis,
            content = "Agent run started: ${run.originalRequest.replace(Regex("\\s+"), " ").trim().take(800)}",
            contentRef = "encrypted://agent-runs/${run.runId}",
            conversationTitle = conversationTitle.take(160),
            metadata = runMetadata(run, "running")
        )

    fun completed(run: AgentRecordedRun, conversationTitle: String = ""): List<GlobalConversationEvent> {
        val completedEventId = "recorded-run:${run.runId}:${run.status.name.lowercase(Locale.ROOT)}"
        val terminalType = when (run.status) {
            AgentRecordedRunStatus.COMPLETED -> GlobalConversationEventType.TASK_UPDATED
            AgentRecordedRunStatus.CANCELLED -> GlobalConversationEventType.TOOL_CANCELLED
            AgentRecordedRunStatus.FAILED -> GlobalConversationEventType.TOOL_FAILED
            AgentRecordedRunStatus.RUNNING -> GlobalConversationEventType.TOOL_STARTED
        }
        val terminalStatus = run.status.name.lowercase(Locale.ROOT)
        val terminal = GlobalConversationEvent(
            id = completedEventId,
            type = terminalType,
            conversationId = run.conversationId,
            messageId = run.runId,
            actor = GlobalConversationActor.TOOL,
            timestampMillis = run.completedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
            content = "Agent run $terminalStatus: ${run.originalRequest.replace(Regex("\\s+"), " ").trim().take(800)}",
            contentRef = "encrypted://agent-runs/${run.runId}",
            conversationTitle = conversationTitle.take(160),
            metadata = runMetadata(run, terminalStatus),
            causalEventIds = setOf(startedEventId(run.runId))
        )
        val roots = setOf(startedEventId(run.runId), completedEventId)
        val toolEvents = run.toolCalls.map { call ->
            val eventType = when (call.status) {
                AgentToolCallStatus.PENDING,
                AgentToolCallStatus.RUNNING -> GlobalConversationEventType.TOOL_STARTED
                AgentToolCallStatus.SUCCEEDED -> GlobalConversationEventType.TOOL_COMPLETED
                AgentToolCallStatus.FAILED -> GlobalConversationEventType.TOOL_FAILED
                AgentToolCallStatus.CANCELLED -> GlobalConversationEventType.TOOL_CANCELLED
            }
            val detail = when {
                call.errorMessage.isNotBlank() -> call.errorMessage
                call.resultJson.isNotBlank() && call.resultJson != "{}" -> summarizeJson(call.resultJson)
                else -> call.status.name.lowercase(Locale.ROOT)
            }.replace(Regex("\\s+"), " ").trim().take(800)
            GlobalConversationEvent(
                id = "recorded-tool:${run.runId}:${call.id}:${call.status.name.lowercase(Locale.ROOT)}",
                type = eventType,
                conversationId = run.conversationId,
                messageId = run.runId,
                actor = GlobalConversationActor.TOOL,
                timestampMillis = call.completedAtMillis.takeIf { it > 0L }
                    ?: call.startedAtMillis.takeIf { it > 0L }
                    ?: terminal.timestampMillis,
                content = "${call.toolName} ${call.status.name.lowercase(Locale.ROOT)}${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}",
                contentRef = "encrypted://agent-runs/${run.runId}/tools/${call.id}",
                conversationTitle = conversationTitle.take(160),
                metadata = mapOf(
                    "origin" to "agent_run",
                    "run_id" to run.runId,
                    "task_id" to run.runId,
                    "task_thread_id" to run.taskThreadId,
                    "tool_call_id" to call.id,
                    "tool_key" to call.id,
                    "tool_name" to call.toolName.take(160),
                    "tool_status" to call.status.name.lowercase(Locale.ROOT),
                    "verified" to (call.status == AgentToolCallStatus.SUCCEEDED).toString()
                ),
                causalEventIds = roots
            )
        }
        val artifactEvents = run.artifacts.map { artifact ->
            val metadata = parseArtifactMetadata(artifact.metadataJson)
            GlobalConversationEvent(
                id = "recorded-artifact:${run.runId}:${artifact.id}",
                type = GlobalConversationEventType.ARTIFACT_CREATED,
                conversationId = run.conversationId,
                messageId = run.runId,
                actor = GlobalConversationActor.TOOL,
                timestampMillis = artifact.createdAtMillis.takeIf { it > 0L } ?: terminal.timestampMillis,
                content = "Created artifact: ${artifact.name.ifBlank { artifact.id }.take(240)}${artifact.mimeType.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()}",
                contentRef = "encrypted://agent-runs/${run.runId}/artifacts/${artifact.id}",
                conversationTitle = conversationTitle.take(160),
                metadata = mapOf(
                    "origin" to "agent_run",
                    "run_id" to run.runId,
                    "task_id" to run.runId,
                    "task_thread_id" to run.taskThreadId,
                    "artifact_id" to artifact.id,
                    "resource_name" to artifact.name.take(240),
                    "mime_type" to artifact.mimeType.take(160),
                    "resource_scheme" to artifact.uri.substringBefore(':', "").take(24)
                ) + metadata,
                causalEventIds = roots
            )
        }
        return listOf(terminal) + toolEvents + artifactEvents
    }

    fun feedback(
        run: AgentRecordedRun,
        feedback: String,
        timestampMillis: Long = System.currentTimeMillis(),
        conversationTitle: String = ""
    ): GlobalConversationEvent {
        val clean = feedback.replace(Regex("\\s+"), " ").trim().take(1_200)
        val fingerprint = GlobalAgentText.stableKey(run.runId, clean, timestampMillis.toString())
        return GlobalConversationEvent(
            id = "recorded-run-feedback:${run.runId}:${fingerprint.take(24)}",
            type = GlobalConversationEventType.USER_FEEDBACK,
            conversationId = run.conversationId,
            messageId = run.runId,
            actor = GlobalConversationActor.USER,
            timestampMillis = timestampMillis,
            content = clean,
            contentRef = "encrypted://agent-runs/${run.runId}/feedback",
            conversationTitle = conversationTitle.take(160),
            metadata = mapOf(
                "origin" to "agent_run",
                "run_id" to run.runId,
                "task_id" to run.runId,
                "task_thread_id" to run.taskThreadId,
                "feedback_kind" to "run_feedback"
            ),
            causalEventIds = setOf(startedEventId(run.runId))
        )
    }

    private fun runMetadata(run: AgentRecordedRun, status: String): Map<String, String> = mapOf(
        "origin" to "agent_run",
        "run_id" to run.runId,
        "task_id" to run.runId,
        "task_thread_id" to run.taskThreadId,
        "task_status" to status,
        "active_skill_id" to run.activeSkillId.take(160),
        "revision" to run.revisionNumber.toString()
    )

    private fun startedEventId(runId: String): String = "recorded-run:$runId:started"

    private fun summarizeJson(raw: String): String {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return "result available"
        return root.keys().asSequence()
            .filterNot(::sensitiveKey)
            .mapNotNull { key -> scalarSummary(root.opt(key))?.let { "$key=$it" } }
            .take(8)
            .joinToString(", ")
            .ifBlank { "result available" }
            .take(800)
    }

    private fun scalarSummary(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.replace(Regex("\\s+"), " ").trim().take(160)
        is Number, is Boolean -> value.toString()
        is JSONArray -> "${value.length()} items"
        is JSONObject -> "${value.length()} fields"
        else -> null
    }

    private fun parseArtifactMetadata(raw: String): Map<String, String> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return root.keys().asSequence()
            .filter { it.lowercase(Locale.ROOT) in SAFE_ARTIFACT_METADATA_KEYS }
            .mapNotNull { key -> scalarSummary(root.opt(key))?.let { "artifact_${key.take(64)}" to it.take(256) } }
            .take(12)
            .toMap()
    }

    private fun sensitiveKey(key: String): Boolean {
        val lower = key.lowercase(Locale.ROOT)
        return listOf("authorization", "password", "secret", "token", "cookie", "api_key", "apikey", "uri", "path")
            .any(lower::contains)
    }

    private val SAFE_ARTIFACT_METADATA_KEYS = setOf(
        "artifact_kind", "duration", "duration_ms", "file_count", "format", "hash", "height",
        "language", "sha256", "size", "size_bytes", "width"
    )
}
