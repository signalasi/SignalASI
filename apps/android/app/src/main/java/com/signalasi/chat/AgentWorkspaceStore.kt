package com.signalasi.chat

import android.content.Context

enum class AgentWorkspaceStatus {
    CREATED,
    QUEUED,
    RUNNING,
    WAITING_CONFIRMATION,
    WAITING_RESPONSE,
    PAUSED,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

enum class AgentToolCallStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

data class AgentWorkspaceKey(
    val workspaceId: String,
    val sessionId: String,
    val conversationId: String,
    val taskId: String
)

data class AgentWorkspaceEvent(
    val sequence: Long = 0L,
    val kind: String,
    val message: String = "",
    val payloadJson: String = "",
    val timestampMillis: Long = 0L
)

data class AgentToolCallRecord(
    val id: String,
    val toolName: String,
    val status: AgentToolCallStatus,
    val argumentsJson: String = "",
    val resultJson: String = "",
    val errorMessage: String = "",
    val startedAtMillis: Long = 0L,
    val completedAtMillis: Long = 0L
)

data class AgentWorkspaceCheckpoint(
    val id: String,
    val eventSequence: Long = 0L,
    val planSnapshot: String = "",
    val stateJson: String = "",
    val createdAtMillis: Long = 0L
)

data class AgentArtifactReference(
    val id: String,
    val uri: String,
    val name: String = "",
    val mimeType: String = "",
    val metadataJson: String = "",
    val createdAtMillis: Long = 0L
)

data class AgentWorkspace(
    val workspaceId: String,
    val sessionId: String,
    val conversationId: String,
    val taskId: String,
    val status: AgentWorkspaceStatus = AgentWorkspaceStatus.CREATED,
    val currentPlanSnapshot: String = "",
    val eventSequence: Long = 0L,
    val eventJournal: List<AgentWorkspaceEvent> = emptyList(),
    val toolCalls: List<AgentToolCallRecord> = emptyList(),
    val checkpoints: List<AgentWorkspaceCheckpoint> = emptyList(),
    val artifacts: List<AgentArtifactReference> = emptyList(),
    val cancellationRequested: Boolean = false,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val revision: Long = 0L
) {
    val key: AgentWorkspaceKey
        get() = AgentWorkspaceKey(workspaceId, sessionId, conversationId, taskId)
}

class AgentWorkspaceRevisionConflictException(
    val workspaceId: String,
    val expectedRevision: Long,
    val actualRevision: Long
) : IllegalStateException(
    "Agent workspace revision conflict for $workspaceId: expected $expectedRevision, actual $actualRevision"
)

object AgentWorkspaceLimits {
    const val MAX_WORKSPACES = 64
    const val MAX_EVENTS = 100
    const val MAX_TOOL_CALLS = 50
    const val MAX_CHECKPOINTS = 10
    const val MAX_ARTIFACTS = 50
    const val MAX_SERIALIZED_CHARS = 4 * 1024 * 1024

    internal const val MAX_IDENTIFIER_CHARS = 160
    internal const val MAX_EVENT_KIND_CHARS = 80
    internal const val MAX_EVENT_MESSAGE_CHARS = 1_024
    internal const val MAX_EVENT_PAYLOAD_CHARS = 4_096
    internal const val MAX_PLAN_CHARS = 32 * 1024
    internal const val MAX_TOOL_NAME_CHARS = 160
    internal const val MAX_TOOL_ARGUMENTS_CHARS = 4_096
    internal const val MAX_TOOL_RESULT_CHARS = 8_192
    internal const val MAX_TOOL_ERROR_CHARS = 1_024
    internal const val MAX_CHECKPOINT_PLAN_CHARS = MAX_PLAN_CHARS
    internal const val MAX_CHECKPOINT_STATE_CHARS = 8 * 1024
    internal const val MAX_ARTIFACT_URI_CHARS = 2_048
    internal const val MAX_ARTIFACT_NAME_CHARS = 512
    internal const val MAX_MIME_TYPE_CHARS = 160
    internal const val MAX_ARTIFACT_METADATA_CHARS = 2_048
}

interface AgentWorkspaceStore {
    fun list(): List<AgentWorkspace>
    fun find(workspaceId: String): AgentWorkspace?

    fun find(key: AgentWorkspaceKey): AgentWorkspace? =
        find(key.workspaceId)?.takeIf { it.key == key }

    fun upsert(
        workspace: AgentWorkspace,
        expectedRevision: Long = workspace.revision
    ): AgentWorkspace

    fun appendEvent(
        workspaceId: String,
        event: AgentWorkspaceEvent,
        expectedRevision: Long? = null
    ): AgentWorkspace?

    fun appendEvent(
        workspaceId: String,
        kind: String,
        message: String = "",
        payloadJson: String = "",
        expectedRevision: Long? = null,
        timestampMillis: Long = 0L
    ): AgentWorkspace? = appendEvent(
        workspaceId = workspaceId,
        event = AgentWorkspaceEvent(
            kind = kind,
            message = message,
            payloadJson = payloadJson,
            timestampMillis = timestampMillis
        ),
        expectedRevision = expectedRevision
    )

    fun checkpoint(
        workspaceId: String,
        checkpoint: AgentWorkspaceCheckpoint,
        expectedRevision: Long? = null
    ): AgentWorkspace?

    fun checkpoint(
        workspaceId: String,
        checkpointId: String,
        planSnapshot: String = "",
        stateJson: String = "",
        expectedRevision: Long? = null,
        createdAtMillis: Long = 0L
    ): AgentWorkspace? = checkpoint(
        workspaceId = workspaceId,
        checkpoint = AgentWorkspaceCheckpoint(
            id = checkpointId,
            planSnapshot = planSnapshot,
            stateJson = stateJson,
            createdAtMillis = createdAtMillis
        ),
        expectedRevision = expectedRevision
    )

    fun requestCancel(
        workspaceId: String,
        expectedRevision: Long? = null,
        timestampMillis: Long = 0L
    ): AgentWorkspace?

    fun delete(workspaceId: String, expectedRevision: Long? = null): Boolean
    fun clear()
    fun recoverable(): List<AgentWorkspace>
}

abstract class AbstractAgentWorkspaceStore(
    private val clock: () -> Long
) : AgentWorkspaceStore {
    protected abstract fun readPersisted(): List<AgentWorkspace>
    protected abstract fun writePersisted(workspaces: List<AgentWorkspace>)
    protected abstract fun clearPersisted()

    @Synchronized
    final override fun list(): List<AgentWorkspace> =
        load().sortedWith(NEWEST_FIRST)

    @Synchronized
    final override fun find(workspaceId: String): AgentWorkspace? {
        val cleanId = workspaceId.trim()
        if (cleanId.isBlank()) return null
        return load().firstOrNull { it.workspaceId == cleanId }
    }

    @Synchronized
    final override fun upsert(
        workspace: AgentWorkspace,
        expectedRevision: Long
    ): AgentWorkspace {
        val normalized = AgentWorkspaceBounds.normalizeOrNull(workspace)
        requireNotNull(normalized) { "Agent workspace fields are invalid or exceed storage limits" }

        val items = load()
        val existing = items.firstOrNull { it.workspaceId == normalized.workspaceId }
        val actualRevision = existing?.revision ?: 0L
        checkRevision(normalized.workspaceId, expectedRevision, actualRevision)
        require(actualRevision < Long.MAX_VALUE) { "Agent workspace revision exhausted" }
        if (existing != null) {
            require(existing.key == normalized.key) { "Agent workspace identity fields cannot change" }
            require(normalized.eventSequence >= existing.eventSequence) {
                "Agent workspace event sequence cannot move backwards"
            }
        }

        val now = now()
        val createdAt = existing?.createdAtMillis
            ?: normalized.createdAtMillis.takeIf { it > 0L }
            ?: now
        val updated = normalized.copy(
            createdAtMillis = createdAt,
            updatedAtMillis = maxOf(
                createdAt,
                normalized.updatedAtMillis,
                existing?.updatedAtMillis ?: 0L,
                now
            ),
            revision = actualRevision + 1L
        )
        persist(items.filterNot { it.workspaceId == updated.workspaceId } + updated)
        return updated
    }

    @Synchronized
    final override fun appendEvent(
        workspaceId: String,
        event: AgentWorkspaceEvent,
        expectedRevision: Long?
    ): AgentWorkspace? {
        val items = load()
        val current = items.firstOrNull { it.workspaceId == workspaceId.trim() } ?: return null
        checkRevisionIfPresent(current, expectedRevision)
        require(current.eventSequence < Long.MAX_VALUE) { "Agent workspace event sequence exhausted" }
        require(current.revision < Long.MAX_VALUE) { "Agent workspace revision exhausted" }

        val mutationTime = now()
        val timestamp = event.timestampMillis.takeIf { it > 0L } ?: mutationTime
        val nextSequence = current.eventSequence + 1L
        val appended = AgentWorkspaceBounds.normalizeEventOrNull(
            event.copy(sequence = nextSequence, timestampMillis = timestamp)
        )
        requireNotNull(appended) { "Agent workspace event is invalid or exceeds storage limits" }
        val updated = current.copy(
            eventSequence = nextSequence,
            eventJournal = (current.eventJournal + appended).takeLast(AgentWorkspaceLimits.MAX_EVENTS),
            updatedAtMillis = maxOf(current.updatedAtMillis, timestamp, mutationTime),
            revision = current.revision + 1L
        )
        persist(items.filterNot { it.workspaceId == current.workspaceId } + updated)
        return updated
    }

    @Synchronized
    final override fun checkpoint(
        workspaceId: String,
        checkpoint: AgentWorkspaceCheckpoint,
        expectedRevision: Long?
    ): AgentWorkspace? {
        val items = load()
        val current = items.firstOrNull { it.workspaceId == workspaceId.trim() } ?: return null
        checkRevisionIfPresent(current, expectedRevision)
        require(current.revision < Long.MAX_VALUE) { "Agent workspace revision exhausted" }

        val mutationTime = now()
        val timestamp = checkpoint.createdAtMillis.takeIf { it > 0L } ?: mutationTime
        val snapshot = checkpoint.planSnapshot.ifBlank { current.currentPlanSnapshot }
        val normalized = AgentWorkspaceBounds.normalizeCheckpointOrNull(
            checkpoint.copy(
                eventSequence = current.eventSequence,
                planSnapshot = snapshot,
                createdAtMillis = timestamp
            )
        )
        requireNotNull(normalized) { "Agent workspace checkpoint is invalid or exceeds storage limits" }
        val checkpoints = current.checkpoints
            .filterNot { it.id == normalized.id }
            .plus(normalized)
            .sortedWith(CHECKPOINT_OLDEST_FIRST)
            .takeLast(AgentWorkspaceLimits.MAX_CHECKPOINTS)
        val updated = current.copy(
            currentPlanSnapshot = normalized.planSnapshot,
            checkpoints = checkpoints,
            updatedAtMillis = maxOf(current.updatedAtMillis, timestamp, mutationTime),
            revision = current.revision + 1L
        )
        persist(items.filterNot { it.workspaceId == current.workspaceId } + updated)
        return updated
    }

    @Synchronized
    final override fun requestCancel(
        workspaceId: String,
        expectedRevision: Long?,
        timestampMillis: Long
    ): AgentWorkspace? {
        val items = load()
        val current = items.firstOrNull { it.workspaceId == workspaceId.trim() } ?: return null
        checkRevisionIfPresent(current, expectedRevision)
        if (current.cancellationRequested) return current
        require(current.revision < Long.MAX_VALUE) { "Agent workspace revision exhausted" }

        val mutationTime = now()
        val timestamp = timestampMillis.takeIf { it > 0L } ?: mutationTime
        val updated = current.copy(
            cancellationRequested = true,
            updatedAtMillis = maxOf(current.updatedAtMillis, timestamp, mutationTime),
            revision = current.revision + 1L
        )
        persist(items.filterNot { it.workspaceId == current.workspaceId } + updated)
        return updated
    }

    @Synchronized
    final override fun delete(workspaceId: String, expectedRevision: Long?): Boolean {
        val cleanId = workspaceId.trim()
        if (cleanId.isBlank()) return false
        val items = load()
        val current = items.firstOrNull { it.workspaceId == cleanId } ?: return false
        checkRevisionIfPresent(current, expectedRevision)
        persist(items.filterNot { it.workspaceId == cleanId })
        return true
    }

    @Synchronized
    final override fun clear() {
        clearPersisted()
    }

    @Synchronized
    final override fun recoverable(): List<AgentWorkspace> =
        load()
            .filter { !it.status.isTerminal && !it.cancellationRequested }
            .sortedWith(NEWEST_FIRST)

    private fun load(): List<AgentWorkspace> =
        AgentWorkspaceBounds.boundWorkspaces(readPersisted())

    private fun persist(workspaces: List<AgentWorkspace>) {
        val fitted = AgentWorkspaceBounds.fitSerializedLimit(workspaces)
        writePersisted(fitted)
    }

    private fun checkRevisionIfPresent(workspace: AgentWorkspace, expectedRevision: Long?) {
        expectedRevision?.let { checkRevision(workspace.workspaceId, it, workspace.revision) }
    }

    private fun checkRevision(workspaceId: String, expected: Long, actual: Long) {
        if (expected != actual) {
            throw AgentWorkspaceRevisionConflictException(workspaceId, expected, actual)
        }
    }

    private fun now(): Long = clock().coerceAtLeast(0L)

    private companion object {
        val NEWEST_FIRST = compareByDescending<AgentWorkspace> { it.updatedAtMillis }
            .thenByDescending { it.createdAtMillis }
            .thenBy { it.workspaceId }
        val CHECKPOINT_OLDEST_FIRST = compareBy<AgentWorkspaceCheckpoint> { it.eventSequence }
            .thenBy { it.createdAtMillis }
            .thenBy { it.id }
    }
}

class InMemoryAgentWorkspaceStore(
    initialWorkspaces: List<AgentWorkspace> = emptyList(),
    clock: () -> Long = { System.currentTimeMillis() }
) : AbstractAgentWorkspaceStore(clock) {
    private var document = AgentWorkspaceJsonCodec.encodeList(
        AgentWorkspaceBounds.fitSerializedLimit(initialWorkspaces)
    )

    override fun readPersisted(): List<AgentWorkspace> =
        AgentWorkspaceJsonCodec.decodeList(document)

    override fun writePersisted(workspaces: List<AgentWorkspace>) {
        document = AgentWorkspaceJsonCodec.encodeList(workspaces)
    }

    override fun clearPersisted() {
        document = AgentWorkspaceJsonCodec.emptyDocument()
    }

    @Synchronized
    fun serializedSnapshot(): String = document
}

class EncryptedAgentWorkspaceStore(
    context: Context,
    databaseName: String = DATABASE_NAME,
    clock: () -> Long = { System.currentTimeMillis() }
) : AbstractAgentWorkspaceStore(clock) {
    private val database = AgentEncryptedDatabase(context.applicationContext, databaseName)

    override fun readPersisted(): List<AgentWorkspace> =
        AgentWorkspaceJsonCodec.decodeList(database.readString(KEY_WORKSPACES, AgentWorkspaceJsonCodec.emptyDocument()))

    override fun writePersisted(workspaces: List<AgentWorkspace>) {
        database.writeString(KEY_WORKSPACES, AgentWorkspaceJsonCodec.encodeList(workspaces))
    }

    override fun clearPersisted() {
        database.clear()
    }

    companion object {
        const val DATABASE_NAME = "signalasi_agent_workspaces"
        const val KEY_WORKSPACES = "workspaces"
    }
}

object AgentWorkspaceJsonCodec {
    const val VERSION = 1

    fun emptyDocument(): String = "{\"version\":1,\"workspaces\":[]}"

    fun encode(workspace: AgentWorkspace): String {
        val normalized = AgentWorkspaceBounds.normalizeOrNull(workspace)
        requireNotNull(normalized) { "Agent workspace fields are invalid or exceed storage limits" }
        return jsonObject {
            number("version", VERSION.toLong())
            workspaceFields(normalized)
        }
    }

    fun decode(raw: String): AgentWorkspace? {
        if (raw.isBlank() || raw.length > AgentWorkspaceLimits.MAX_SERIALIZED_CHARS) return null
        val root = parseObject(raw) ?: return null
        if (root.long("version", VERSION.toLong()) > VERSION) return null
        return decodeWorkspace(root)
    }

    fun encodeList(workspaces: List<AgentWorkspace>): String = jsonObject {
        number("version", VERSION.toLong())
        array("workspaces") {
            AgentWorkspaceBounds.boundWorkspaces(workspaces).forEach { workspace ->
                objectValue { workspaceFields(workspace) }
            }
        }
    }

    fun decodeList(raw: String): List<AgentWorkspace> {
        if (raw.isBlank() || raw.length > AgentWorkspaceLimits.MAX_SERIALIZED_CHARS) return emptyList()
        val root = parseObject(raw) ?: return emptyList()
        if (root.long("version", VERSION.toLong()) > VERSION) return emptyList()
        return root.array("workspaces")
            .mapNotNull { (it as? WorkspaceJsonObject)?.let(::decodeWorkspace) }
            .let(AgentWorkspaceBounds::boundWorkspaces)
    }

    private fun WorkspaceJsonObjectWriter.workspaceFields(workspace: AgentWorkspace) {
        string("workspace_id", workspace.workspaceId)
        string("session_id", workspace.sessionId)
        string("conversation_id", workspace.conversationId)
        string("task_id", workspace.taskId)
        string("status", workspace.status.name)
        string("current_plan_snapshot", workspace.currentPlanSnapshot)
        number("event_sequence", workspace.eventSequence)
        array("event_journal") {
            workspace.eventJournal.forEach { event ->
                objectValue {
                    number("sequence", event.sequence)
                    string("kind", event.kind)
                    string("message", event.message)
                    string("payload_json", event.payloadJson)
                    number("timestamp", event.timestampMillis)
                }
            }
        }
        array("tool_calls") {
            workspace.toolCalls.forEach { toolCall ->
                objectValue {
                    string("id", toolCall.id)
                    string("tool_name", toolCall.toolName)
                    string("status", toolCall.status.name)
                    string("arguments_json", toolCall.argumentsJson)
                    string("result_json", toolCall.resultJson)
                    string("error_message", toolCall.errorMessage)
                    number("started_at", toolCall.startedAtMillis)
                    number("completed_at", toolCall.completedAtMillis)
                }
            }
        }
        array("checkpoints") {
            workspace.checkpoints.forEach { checkpoint ->
                objectValue {
                    string("id", checkpoint.id)
                    number("event_sequence", checkpoint.eventSequence)
                    string("plan_snapshot", checkpoint.planSnapshot)
                    string("state_json", checkpoint.stateJson)
                    number("created_at", checkpoint.createdAtMillis)
                }
            }
        }
        array("artifacts") {
            workspace.artifacts.forEach { artifact ->
                objectValue {
                    string("id", artifact.id)
                    string("uri", artifact.uri)
                    string("name", artifact.name)
                    string("mime_type", artifact.mimeType)
                    string("metadata_json", artifact.metadataJson)
                    number("created_at", artifact.createdAtMillis)
                }
            }
        }
        boolean("cancellation_requested", workspace.cancellationRequested)
        number("created_at", workspace.createdAtMillis)
        number("updated_at", workspace.updatedAtMillis)
        number("revision", workspace.revision)
    }

    private fun decodeWorkspace(json: WorkspaceJsonObject): AgentWorkspace? {
        val status = enumOrNull<AgentWorkspaceStatus>(json.string("status")) ?: return null
        return AgentWorkspaceBounds.normalizeOrNull(
            AgentWorkspace(
                workspaceId = json.string("workspace_id"),
                sessionId = json.string("session_id"),
                conversationId = json.string("conversation_id"),
                taskId = json.string("task_id"),
                status = status,
                currentPlanSnapshot = json.string("current_plan_snapshot"),
                eventSequence = json.long("event_sequence"),
                eventJournal = json.array("event_journal").mapNotNull { value ->
                    val item = value as? WorkspaceJsonObject ?: return@mapNotNull null
                    AgentWorkspaceBounds.normalizeEventOrNull(
                        AgentWorkspaceEvent(
                            sequence = item.long("sequence"),
                            kind = item.string("kind"),
                            message = item.string("message"),
                            payloadJson = item.string("payload_json"),
                            timestampMillis = item.long("timestamp")
                        )
                    )
                },
                toolCalls = json.array("tool_calls").mapNotNull { value ->
                    val item = value as? WorkspaceJsonObject ?: return@mapNotNull null
                    val toolStatus = enumOrNull<AgentToolCallStatus>(item.string("status"))
                        ?: return@mapNotNull null
                    AgentWorkspaceBounds.normalizeToolCallOrNull(
                        AgentToolCallRecord(
                            id = item.string("id"),
                            toolName = item.string("tool_name"),
                            status = toolStatus,
                            argumentsJson = item.string("arguments_json"),
                            resultJson = item.string("result_json"),
                            errorMessage = item.string("error_message"),
                            startedAtMillis = item.long("started_at"),
                            completedAtMillis = item.long("completed_at")
                        )
                    )
                },
                checkpoints = json.array("checkpoints").mapNotNull { value ->
                    val item = value as? WorkspaceJsonObject ?: return@mapNotNull null
                    AgentWorkspaceBounds.normalizeCheckpointOrNull(
                        AgentWorkspaceCheckpoint(
                            id = item.string("id"),
                            eventSequence = item.long("event_sequence"),
                            planSnapshot = item.string("plan_snapshot"),
                            stateJson = item.string("state_json"),
                            createdAtMillis = item.long("created_at")
                        )
                    )
                },
                artifacts = json.array("artifacts").mapNotNull { value ->
                    val item = value as? WorkspaceJsonObject ?: return@mapNotNull null
                    AgentWorkspaceBounds.normalizeArtifactOrNull(
                        AgentArtifactReference(
                            id = item.string("id"),
                            uri = item.string("uri"),
                            name = item.string("name"),
                            mimeType = item.string("mime_type"),
                            metadataJson = item.string("metadata_json"),
                            createdAtMillis = item.long("created_at")
                        )
                    )
                },
                cancellationRequested = json.boolean("cancellation_requested"),
                createdAtMillis = json.long("created_at"),
                updatedAtMillis = json.long("updated_at"),
                revision = json.long("revision")
            )
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(value: String): T? =
        runCatching { enumValueOf<T>(value) }.getOrNull()

    private fun parseObject(raw: String): WorkspaceJsonObject? =
        runCatching { WorkspaceJsonParser(raw).parse() as? WorkspaceJsonObject }.getOrNull()
}

internal object AgentWorkspaceBounds {
    private val WORKSPACE_OLDEST_FIRST = compareBy<AgentWorkspace> { it.updatedAtMillis }
        .thenBy { it.createdAtMillis }
        .thenBy { it.workspaceId }
    private val EVENT_OLDEST_FIRST = compareBy<AgentWorkspaceEvent> { it.sequence }
        .thenBy { it.timestampMillis }
    private val TOOL_OLDEST_FIRST = compareBy<AgentToolCallRecord> { it.startedAtMillis }
        .thenBy { it.completedAtMillis }
        .thenBy { it.id }
    private val CHECKPOINT_OLDEST_FIRST = compareBy<AgentWorkspaceCheckpoint> { it.eventSequence }
        .thenBy { it.createdAtMillis }
        .thenBy { it.id }
    private val ARTIFACT_OLDEST_FIRST = compareBy<AgentArtifactReference> { it.createdAtMillis }
        .thenBy { it.id }

    fun normalizeOrNull(workspace: AgentWorkspace): AgentWorkspace? {
        val workspaceId = identifier(workspace.workspaceId) ?: return null
        val sessionId = identifier(workspace.sessionId) ?: return null
        val conversationId = identifier(workspace.conversationId) ?: return null
        val taskId = identifier(workspace.taskId) ?: return null
        if (workspace.eventSequence < 0L || workspace.createdAtMillis < 0L ||
            workspace.updatedAtMillis < 0L || workspace.revision < 0L
        ) return null

        val events = dedupe(
            workspace.eventJournal.mapNotNull(::normalizeEventOrNull),
            AgentWorkspaceEvent::sequence,
            EVENT_OLDEST_FIRST,
            AgentWorkspaceLimits.MAX_EVENTS
        )
        val eventSequence = maxOf(workspace.eventSequence, events.maxOfOrNull { it.sequence } ?: 0L)
        val toolCalls = dedupe(
            workspace.toolCalls.mapNotNull(::normalizeToolCallOrNull),
            AgentToolCallRecord::id,
            TOOL_OLDEST_FIRST,
            AgentWorkspaceLimits.MAX_TOOL_CALLS
        )
        val checkpoints = dedupe(
            workspace.checkpoints.mapNotNull(::normalizeCheckpointOrNull)
                .filter { it.eventSequence <= eventSequence },
            AgentWorkspaceCheckpoint::id,
            CHECKPOINT_OLDEST_FIRST,
            AgentWorkspaceLimits.MAX_CHECKPOINTS
        )
        val artifacts = dedupe(
            workspace.artifacts.mapNotNull(::normalizeArtifactOrNull),
            AgentArtifactReference::id,
            ARTIFACT_OLDEST_FIRST,
            AgentWorkspaceLimits.MAX_ARTIFACTS
        )
        return workspace.copy(
            workspaceId = workspaceId,
            sessionId = sessionId,
            conversationId = conversationId,
            taskId = taskId,
            currentPlanSnapshot = workspace.currentPlanSnapshot.take(AgentWorkspaceLimits.MAX_PLAN_CHARS),
            eventSequence = eventSequence,
            eventJournal = events,
            toolCalls = toolCalls,
            checkpoints = checkpoints,
            artifacts = artifacts
        )
    }

    fun normalizeEventOrNull(event: AgentWorkspaceEvent): AgentWorkspaceEvent? {
        val kind = event.kind.trim().take(AgentWorkspaceLimits.MAX_EVENT_KIND_CHARS)
        if (event.sequence <= 0L || kind.isBlank() || event.timestampMillis < 0L) return null
        return event.copy(
            kind = kind,
            message = event.message.take(AgentWorkspaceLimits.MAX_EVENT_MESSAGE_CHARS),
            payloadJson = event.payloadJson.take(AgentWorkspaceLimits.MAX_EVENT_PAYLOAD_CHARS)
        )
    }

    fun normalizeToolCallOrNull(toolCall: AgentToolCallRecord): AgentToolCallRecord? {
        val id = identifier(toolCall.id) ?: return null
        val toolName = toolCall.toolName.trim().take(AgentWorkspaceLimits.MAX_TOOL_NAME_CHARS)
        if (toolName.isBlank() || toolCall.startedAtMillis < 0L || toolCall.completedAtMillis < 0L) return null
        if (toolCall.completedAtMillis != 0L && toolCall.completedAtMillis < toolCall.startedAtMillis) return null
        return toolCall.copy(
            id = id,
            toolName = toolName,
            argumentsJson = toolCall.argumentsJson.take(AgentWorkspaceLimits.MAX_TOOL_ARGUMENTS_CHARS),
            resultJson = toolCall.resultJson.take(AgentWorkspaceLimits.MAX_TOOL_RESULT_CHARS),
            errorMessage = toolCall.errorMessage.take(AgentWorkspaceLimits.MAX_TOOL_ERROR_CHARS)
        )
    }

    fun normalizeCheckpointOrNull(checkpoint: AgentWorkspaceCheckpoint): AgentWorkspaceCheckpoint? {
        val id = identifier(checkpoint.id) ?: return null
        if (checkpoint.eventSequence < 0L || checkpoint.createdAtMillis < 0L) return null
        return checkpoint.copy(
            id = id,
            planSnapshot = checkpoint.planSnapshot.take(AgentWorkspaceLimits.MAX_CHECKPOINT_PLAN_CHARS),
            stateJson = checkpoint.stateJson.take(AgentWorkspaceLimits.MAX_CHECKPOINT_STATE_CHARS)
        )
    }

    fun normalizeArtifactOrNull(artifact: AgentArtifactReference): AgentArtifactReference? {
        val id = identifier(artifact.id) ?: return null
        val uri = artifact.uri.trim().take(AgentWorkspaceLimits.MAX_ARTIFACT_URI_CHARS)
        if (uri.isBlank() || artifact.createdAtMillis < 0L) return null
        return artifact.copy(
            id = id,
            uri = uri,
            name = artifact.name.take(AgentWorkspaceLimits.MAX_ARTIFACT_NAME_CHARS),
            mimeType = artifact.mimeType.trim().take(AgentWorkspaceLimits.MAX_MIME_TYPE_CHARS),
            metadataJson = artifact.metadataJson.take(AgentWorkspaceLimits.MAX_ARTIFACT_METADATA_CHARS)
        )
    }

    fun boundWorkspaces(workspaces: List<AgentWorkspace>): List<AgentWorkspace> {
        val bestById = LinkedHashMap<String, AgentWorkspace>()
        workspaces.mapNotNull(::normalizeOrNull).forEach { workspace ->
            val previous = bestById[workspace.workspaceId]
            if (previous == null || workspace.revision > previous.revision ||
                (workspace.revision == previous.revision && workspace.updatedAtMillis >= previous.updatedAtMillis)
            ) {
                bestById[workspace.workspaceId] = workspace
            }
        }
        return bestById.values.sortedWith(WORKSPACE_OLDEST_FIRST).takeLast(AgentWorkspaceLimits.MAX_WORKSPACES)
    }

    fun fitSerializedLimit(workspaces: List<AgentWorkspace>): List<AgentWorkspace> {
        val bounded = boundWorkspaces(workspaces).toMutableList()
        while (bounded.size > 1 && encodedLength(bounded) > AgentWorkspaceLimits.MAX_SERIALIZED_CHARS) {
            val terminalIndex = bounded.indexOfFirst { it.status.isTerminal }
            bounded.removeAt(if (terminalIndex >= 0) terminalIndex else 0)
        }
        if (bounded.size == 1 && encodedLength(bounded) > AgentWorkspaceLimits.MAX_SERIALIZED_CHARS) {
            bounded[0] = shrinkSingle(bounded[0])
        }
        check(encodedLength(bounded) <= AgentWorkspaceLimits.MAX_SERIALIZED_CHARS) {
            "Agent workspace storage limit exceeded"
        }
        return bounded
    }

    private fun shrinkSingle(source: AgentWorkspace): AgentWorkspace {
        var workspace = source
        while (AgentWorkspaceJsonCodec.encodeList(listOf(workspace)).length > AgentWorkspaceLimits.MAX_SERIALIZED_CHARS) {
            workspace = when {
                workspace.eventJournal.isNotEmpty() -> workspace.copy(eventJournal = workspace.eventJournal.drop(1))
                workspace.toolCalls.isNotEmpty() -> workspace.copy(toolCalls = workspace.toolCalls.drop(1))
                workspace.checkpoints.isNotEmpty() -> workspace.copy(checkpoints = workspace.checkpoints.drop(1))
                workspace.artifacts.isNotEmpty() -> workspace.copy(artifacts = workspace.artifacts.drop(1))
                workspace.currentPlanSnapshot.length > 1_024 -> workspace.copy(
                    currentPlanSnapshot = workspace.currentPlanSnapshot.take(workspace.currentPlanSnapshot.length / 2)
                )
                else -> return workspace
            }
        }
        return workspace
    }

    private fun encodedLength(workspaces: List<AgentWorkspace>): Int =
        AgentWorkspaceJsonCodec.encodeList(workspaces).length

    private fun identifier(value: String): String? {
        val clean = value.trim()
        return clean.takeIf { it.isNotBlank() && it.length <= AgentWorkspaceLimits.MAX_IDENTIFIER_CHARS }
    }

    private fun <T, K> dedupe(
        items: List<T>,
        key: (T) -> K,
        comparator: Comparator<T>,
        limit: Int
    ): List<T> {
        val byKey = LinkedHashMap<K, T>()
        items.sortedWith(comparator).forEach { item -> byKey[key(item)] = item }
        return byKey.values.sortedWith(comparator).takeLast(limit)
    }
}

private sealed class WorkspaceJsonValue
private data class WorkspaceJsonObject(val values: Map<String, WorkspaceJsonValue>) : WorkspaceJsonValue()
private data class WorkspaceJsonArray(val values: List<WorkspaceJsonValue>) : WorkspaceJsonValue()
private data class WorkspaceJsonString(val value: String) : WorkspaceJsonValue()
private data class WorkspaceJsonNumber(val value: String) : WorkspaceJsonValue()
private data class WorkspaceJsonBoolean(val value: Boolean) : WorkspaceJsonValue()
private data object WorkspaceJsonNull : WorkspaceJsonValue()

private fun WorkspaceJsonObject.string(name: String, default: String = ""): String =
    (values[name] as? WorkspaceJsonString)?.value ?: default

private fun WorkspaceJsonObject.long(name: String, default: Long = 0L): Long =
    (values[name] as? WorkspaceJsonNumber)?.value?.toLongOrNull() ?: default

private fun WorkspaceJsonObject.boolean(name: String, default: Boolean = false): Boolean =
    (values[name] as? WorkspaceJsonBoolean)?.value ?: default

private fun WorkspaceJsonObject.array(name: String): List<WorkspaceJsonValue> =
    (values[name] as? WorkspaceJsonArray)?.values.orEmpty()

private class WorkspaceJsonParser(private val source: String) {
    private var index = 0

    fun parse(): WorkspaceJsonValue {
        val value = parseValue(0)
        skipWhitespace()
        require(index == source.length) { "Unexpected trailing JSON content" }
        return value
    }

    private fun parseValue(depth: Int): WorkspaceJsonValue {
        require(depth <= MAX_DEPTH) { "JSON nesting is too deep" }
        skipWhitespace()
        require(index < source.length) { "Unexpected end of JSON" }
        return when (source[index]) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> WorkspaceJsonString(parseString())
            't' -> parseLiteral("true", WorkspaceJsonBoolean(true))
            'f' -> parseLiteral("false", WorkspaceJsonBoolean(false))
            'n' -> parseLiteral("null", WorkspaceJsonNull)
            '-', in '0'..'9' -> WorkspaceJsonNumber(parseNumber())
            else -> error("Invalid JSON value")
        }
    }

    private fun parseObject(depth: Int): WorkspaceJsonObject {
        expect('{')
        skipWhitespace()
        val values = LinkedHashMap<String, WorkspaceJsonValue>()
        if (consume('}')) return WorkspaceJsonObject(values)
        while (true) {
            skipWhitespace()
            require(index < source.length && source[index] == '"') { "Expected JSON object key" }
            val name = parseString()
            skipWhitespace()
            expect(':')
            values[name] = parseValue(depth)
            skipWhitespace()
            if (consume('}')) return WorkspaceJsonObject(values)
            expect(',')
        }
    }

    private fun parseArray(depth: Int): WorkspaceJsonArray {
        expect('[')
        skipWhitespace()
        val values = mutableListOf<WorkspaceJsonValue>()
        if (consume(']')) return WorkspaceJsonArray(values)
        while (true) {
            values += parseValue(depth)
            skipWhitespace()
            if (consume(']')) return WorkspaceJsonArray(values)
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when (character) {
                '"' -> return result.toString()
                '\\' -> {
                    require(index < source.length) { "Incomplete JSON escape" }
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(index + 4 <= source.length) { "Incomplete unicode escape" }
                            val codePoint = source.substring(index, index + 4).toIntOrNull(16)
                            requireNotNull(codePoint) { "Invalid unicode escape" }
                            result.append(codePoint.toChar())
                            index += 4
                        }
                        else -> error("Invalid JSON escape")
                    }
                }
                else -> {
                    require(character.code >= 0x20) { "Unescaped JSON control character" }
                    result.append(character)
                }
            }
        }
        error("Unterminated JSON string")
    }

    private fun parseNumber(): String {
        val start = index
        consume('-')
        require(index < source.length) { "Incomplete JSON number" }
        if (consume('0')) {
            require(index >= source.length || !source[index].isDigit()) { "Invalid JSON number" }
        } else {
            require(index < source.length && source[index] in '1'..'9') { "Invalid JSON number" }
            while (index < source.length && source[index].isDigit()) index++
        }
        if (consume('.')) {
            require(index < source.length && source[index].isDigit()) { "Invalid JSON fraction" }
            while (index < source.length && source[index].isDigit()) index++
        }
        if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
            index++
            if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
            require(index < source.length && source[index].isDigit()) { "Invalid JSON exponent" }
            while (index < source.length && source[index].isDigit()) index++
        }
        return source.substring(start, index)
    }

    private fun parseLiteral(expected: String, value: WorkspaceJsonValue): WorkspaceJsonValue {
        require(source.regionMatches(index, expected, 0, expected.length)) { "Invalid JSON literal" }
        index += expected.length
        return value
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index] in setOf(' ', '\n', '\r', '\t')) index++
    }

    private fun expect(character: Char) {
        require(consume(character)) { "Expected '$character'" }
    }

    private fun consume(character: Char): Boolean {
        if (index >= source.length || source[index] != character) return false
        index++
        return true
    }

    private companion object {
        const val MAX_DEPTH = 32
    }
}

private fun jsonObject(block: WorkspaceJsonObjectWriter.() -> Unit): String =
    StringBuilder().also { output ->
        output.append('{')
        WorkspaceJsonObjectWriter(output).block()
        output.append('}')
    }.toString()

private class WorkspaceJsonObjectWriter(private val output: StringBuilder) {
    private var first = true

    fun string(name: String, value: String) {
        name(name)
        output.appendJsonString(value)
    }

    fun number(name: String, value: Long) {
        name(name)
        output.append(value)
    }

    fun boolean(name: String, value: Boolean) {
        name(name)
        output.append(if (value) "true" else "false")
    }

    fun array(name: String, block: WorkspaceJsonArrayWriter.() -> Unit) {
        name(name)
        output.append('[')
        WorkspaceJsonArrayWriter(output).block()
        output.append(']')
    }

    private fun name(name: String) {
        if (!first) output.append(',')
        first = false
        output.appendJsonString(name)
        output.append(':')
    }
}

private class WorkspaceJsonArrayWriter(private val output: StringBuilder) {
    private var first = true

    fun objectValue(block: WorkspaceJsonObjectWriter.() -> Unit) {
        if (!first) output.append(',')
        first = false
        output.append('{')
        WorkspaceJsonObjectWriter(output).block()
        output.append('}')
    }
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}
