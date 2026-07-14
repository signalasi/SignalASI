package com.signalasi.chat

import java.util.Locale
import java.util.UUID

typealias AgentRemoteExecutionPayload = Map<String, Any?>

enum class AgentRemoteExecutionTool(
    val id: String,
    val wireValue: String
) {
    SHELL("signalasi.desktop.exec.shell", "shell"),
    PYTHON("signalasi.desktop.exec.python", "python"),
    UV("signalasi.desktop.exec.uv", "uv"),
    FFMPEG("signalasi.desktop.exec.ffmpeg", "ffmpeg")
}

enum class AgentRemoteShellMode(val wireValue: String) {
    DIRECT_ARGV("direct_argv"),
    SHELL_SCRIPT("shell_script")
}

enum class AgentRemoteShellDialect(val wireValue: String) {
    POSIX_SH("posix_sh"),
    POWERSHELL("powershell"),
    WINDOWS_CMD("windows_cmd")
}

enum class AgentRemoteExecutionStatus(val wireValue: String) {
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    TIMED_OUT("timed_out"),
    REJECTED("rejected"),
    TRANSPORT_FAILED("transport_failed")
}

data class AgentRemoteExecutionTarget(
    val desktopId: String,
    val desktopFingerprint: String,
    val relationshipId: String,
    val paired: Boolean,
    val trusted: Boolean,
    val revoked: Boolean = false,
    val encryptedLinkAvailable: Boolean = true
) {
    val isTrustedPairedDesktop: Boolean
        get() = paired && trusted && !revoked && encryptedLinkAvailable
}

data class AgentRemoteExecutionLimits(
    val maxStdoutBytes: Int = DEFAULT_STDOUT_BYTES,
    val maxStderrBytes: Int = DEFAULT_STDERR_BYTES,
    val maxArtifactCount: Int = DEFAULT_ARTIFACT_COUNT,
    val maxArtifactBytes: Long = DEFAULT_ARTIFACT_BYTES,
    val maxTotalArtifactBytes: Long = DEFAULT_TOTAL_ARTIFACT_BYTES
) {
    companion object {
        const val DEFAULT_STDOUT_BYTES = 128 * 1024
        const val DEFAULT_STDERR_BYTES = 128 * 1024
        const val DEFAULT_ARTIFACT_COUNT = 8
        const val DEFAULT_ARTIFACT_BYTES = 32L * 1024 * 1024
        const val DEFAULT_TOTAL_ARTIFACT_BYTES = 64L * 1024 * 1024
    }
}

data class AgentRemoteExecutionPolicy(
    val environmentAllowlist: Set<String> = DEFAULT_ENVIRONMENT_ALLOWLIST,
    val maxTimeoutMillis: Long = 15 * 60 * 1000L,
    val maxScriptBytes: Int = 64 * 1024,
    val maxCommandBytes: Int = 16 * 1024,
    val maxArguments: Int = 256,
    val maxEnvironmentEntries: Int = 16,
    val maxEnvironmentValueBytes: Int = 4 * 1024,
    val maxCwdBytes: Int = 1024,
    val maxStdoutBytes: Int = 512 * 1024,
    val maxStderrBytes: Int = 512 * 1024,
    val maxArtifactCount: Int = 32,
    val maxArtifactBytes: Long = 128L * 1024 * 1024,
    val maxTotalArtifactBytes: Long = 256L * 1024 * 1024
) {
    init {
        require(maxTimeoutMillis > 0) { "Maximum timeout must be positive" }
        require(maxScriptBytes > 0 && maxCommandBytes > 0) { "Input byte limits must be positive" }
        require(maxArguments > 0 && maxEnvironmentEntries >= 0) { "Collection limits are invalid" }
        require(maxEnvironmentValueBytes > 0 && maxCwdBytes > 0) { "String limits must be positive" }
        require(maxStdoutBytes > 0 && maxStderrBytes > 0) { "Output limits must be positive" }
        require(maxArtifactCount >= 0 && maxArtifactBytes >= 0 && maxTotalArtifactBytes >= 0) {
            "Artifact limits must be non-negative"
        }
        require(environmentAllowlist.none(String::isBlank)) { "Environment allowlist contains a blank name" }
    }

    internal val normalizedEnvironmentAllowlist: Set<String>
        get() = environmentAllowlist.mapTo(linkedSetOf()) { it.uppercase(Locale.ROOT) }

    companion object {
        val DEFAULT_ENVIRONMENT_ALLOWLIST: Set<String> = setOf(
            "AV_LOG_FORCE_NOCOLOR",
            "CI",
            "LANG",
            "LC_ALL",
            "NO_COLOR",
            "PYTHONDONTWRITEBYTECODE",
            "PYTHONIOENCODING",
            "PYTHONUTF8",
            "TERM",
            "TZ",
            "UV_NO_CACHE",
            "UV_NO_PROGRESS",
            "UV_OFFLINE"
        )
    }
}

sealed interface AgentRemoteExecutionSpec {
    val tool: AgentRemoteExecutionTool
    val cwd: String
    val environment: Map<String, String>
    val timeoutMillis: Long
    val limits: AgentRemoteExecutionLimits
    val artifactPaths: List<String>
}

data class AgentRemoteShellSpec(
    val command: List<String> = emptyList(),
    val script: String = "",
    val mode: AgentRemoteShellMode = AgentRemoteShellMode.DIRECT_ARGV,
    val dialect: AgentRemoteShellDialect? = null,
    override val cwd: String = ".",
    override val environment: Map<String, String> = emptyMap(),
    override val timeoutMillis: Long = 60_000L,
    override val limits: AgentRemoteExecutionLimits = AgentRemoteExecutionLimits(),
    override val artifactPaths: List<String> = emptyList()
) : AgentRemoteExecutionSpec {
    override val tool = AgentRemoteExecutionTool.SHELL
}

data class AgentRemotePythonSpec(
    val script: String,
    val arguments: List<String> = emptyList(),
    override val cwd: String = ".",
    override val environment: Map<String, String> = emptyMap(),
    override val timeoutMillis: Long = 60_000L,
    override val limits: AgentRemoteExecutionLimits = AgentRemoteExecutionLimits(),
    override val artifactPaths: List<String> = emptyList()
) : AgentRemoteExecutionSpec {
    override val tool = AgentRemoteExecutionTool.PYTHON
}

data class AgentRemoteUvSpec(
    val script: String,
    val arguments: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val offline: Boolean = true,
    override val cwd: String = ".",
    override val environment: Map<String, String> = emptyMap(),
    override val timeoutMillis: Long = 60_000L,
    override val limits: AgentRemoteExecutionLimits = AgentRemoteExecutionLimits(),
    override val artifactPaths: List<String> = emptyList()
) : AgentRemoteExecutionSpec {
    override val tool = AgentRemoteExecutionTool.UV
}

data class AgentRemoteFfmpegSpec(
    val arguments: List<String>,
    override val cwd: String = ".",
    override val environment: Map<String, String> = emptyMap(),
    override val timeoutMillis: Long = 5 * 60_000L,
    override val limits: AgentRemoteExecutionLimits = AgentRemoteExecutionLimits(),
    override val artifactPaths: List<String> = emptyList()
) : AgentRemoteExecutionSpec {
    override val tool = AgentRemoteExecutionTool.FFMPEG
}

data class AgentRemoteExecutionRequest(
    val target: AgentRemoteExecutionTarget,
    val spec: AgentRemoteExecutionSpec,
    val requestId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val conversationId: String,
    val turnId: String,
    val taskId: String,
    val workspaceId: String,
    val sequence: Long = 1,
    val sentAtEpochMillis: Long = System.currentTimeMillis(),
    val expiresAtEpochMillis: Long = sentAtEpochMillis + DEFAULT_MESSAGE_TTL_MILLIS,
    val cancellationId: String = requestId
) {
    val toolId: String get() = spec.tool.id

    fun toEncryptedLinkPayload(
        registry: AgentRemoteExecutionToolRegistry = AgentRemoteExecutionTools.registry
    ): AgentRemoteExecutionPayload = registry.requestPayload(this)

    companion object {
        const val DEFAULT_MESSAGE_TTL_MILLIS = 10 * 60_000L
    }
}

data class AgentRemoteExecutionCancellationRequest(
    val request: AgentRemoteExecutionRequest,
    val reason: String = "user_cancelled",
    val sequence: Long = request.sequence + 1,
    val sentAtEpochMillis: Long = System.currentTimeMillis(),
    val expiresAtEpochMillis: Long = sentAtEpochMillis + 60_000L
) {
    fun toEncryptedLinkPayload(
        registry: AgentRemoteExecutionToolRegistry = AgentRemoteExecutionTools.registry
    ): AgentRemoteExecutionPayload = registry.cancellationPayload(this)
}

fun interface AgentRemoteExecutionCancellationToken {
    fun isCancellationRequested(): Boolean

    companion object {
        val NONE = AgentRemoteExecutionCancellationToken { false }
    }
}

data class AgentRemoteExecutionArtifact(
    val artifactId: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val encryptedReference: String,
    val expiresAtEpochMillis: Long
)

data class AgentRemoteExecutionProvenance(
    val desktopId: String,
    val desktopFingerprint: String,
    val relationshipId: String,
    val executorId: String,
    val executorVersion: String,
    val toolId: String,
    val toolVersion: String,
    val workspaceId: String,
    val workspaceOwnership: String = "desktop",
    val transport: String = AgentRemoteExecutionTools.REQUIRED_TRANSPORT,
    val encrypted: Boolean = true,
    val executedOnAndroid: Boolean = false
) {
    companion object {
        fun forDesktop(
            request: AgentRemoteExecutionRequest,
            executorId: String,
            executorVersion: String
        ): AgentRemoteExecutionProvenance = AgentRemoteExecutionProvenance(
            desktopId = request.target.desktopId,
            desktopFingerprint = request.target.desktopFingerprint,
            relationshipId = request.target.relationshipId,
            executorId = executorId,
            executorVersion = executorVersion,
            toolId = request.toolId,
            toolVersion = AgentRemoteExecutionTools.TOOL_VERSION,
            workspaceId = request.workspaceId
        )
    }
}

data class AgentRemoteExecutionError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val details: AgentRemoteExecutionPayload = emptyMap()
)

data class AgentRemoteExecutionResponse(
    val requestId: String,
    val cancellationId: String,
    val status: AgentRemoteExecutionStatus,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val durationMillis: Long = 0,
    val artifacts: List<AgentRemoteExecutionArtifact> = emptyList(),
    val provenance: AgentRemoteExecutionProvenance,
    val error: AgentRemoteExecutionError? = null
) {
    fun toEncryptedLinkPayload(
        request: AgentRemoteExecutionRequest,
        registry: AgentRemoteExecutionToolRegistry = AgentRemoteExecutionTools.registry
    ): AgentRemoteExecutionPayload = registry.responsePayload(request, this)
}

data class AgentRemoteExecutionValidationIssue(
    val path: String,
    val code: String,
    val message: String
)

data class AgentRemoteExecutionValidationResult(
    val issues: List<AgentRemoteExecutionValidationIssue> = emptyList()
) {
    val isValid: Boolean get() = issues.isEmpty()
}

class AgentRemoteExecutionContractException(
    val issues: List<AgentRemoteExecutionValidationIssue>
) : IllegalArgumentException(
    issues.joinToString(separator = "; ") { "${it.path}: ${it.message}" }
)

data class AgentRemoteExecutionToolDefinition(
    val id: String,
    val version: String,
    val title: String,
    val description: String,
    val tool: AgentRemoteExecutionTool,
    val risk: String,
    val inputContract: AgentRemoteExecutionPayload,
    val outputContract: AgentRemoteExecutionPayload,
    val location: String = AgentRemoteExecutionTools.REQUIRED_LOCATION,
    val transport: String = AgentRemoteExecutionTools.REQUIRED_TRANSPORT,
    val supportsCancellation: Boolean = true,
    val localAndroidExecution: Boolean = false
) {
    init {
        require(id == tool.id) { "Definition id must match the tool id" }
        require(version.isNotBlank() && title.isNotBlank() && description.isNotBlank()) {
            "Remote execution definition metadata must not be blank"
        }
        require(location == AgentRemoteExecutionTools.REQUIRED_LOCATION) {
            "Remote execution tools may only target a trusted paired Desktop"
        }
        require(transport == AgentRemoteExecutionTools.REQUIRED_TRANSPORT) {
            "Remote execution tools require encrypted SignalASI Link transport"
        }
        require(!localAndroidExecution) { "Remote execution must never invoke an Android shell" }
    }

    fun toCatalogPayload(): AgentRemoteExecutionPayload = linkedMapOf(
        "id" to id,
        "version" to version,
        "title" to title,
        "description" to description,
        "tool" to tool.wireValue,
        "location" to location,
        "transport" to transport,
        "risk" to risk,
        "supports_cancellation" to supportsCancellation,
        "local_android_execution" to localAndroidExecution,
        "input_schema" to inputContract,
        "output_schema" to outputContract
    )
}

/**
 * Transport boundary for a paired Desktop. Implementations encrypt and send the supplied payload
 * through SignalASI Link; this contract intentionally exposes no local process or Android shell API.
 */
interface AgentRemoteExecutionDispatcher {
    fun dispatch(
        target: AgentRemoteExecutionTarget,
        encryptedLinkRequestPayload: AgentRemoteExecutionPayload,
        cancellationToken: AgentRemoteExecutionCancellationToken
    ): AgentRemoteExecutionResponse

    fun dispatchCancellation(
        target: AgentRemoteExecutionTarget,
        encryptedLinkCancellationPayload: AgentRemoteExecutionPayload
    ): AgentRemoteExecutionResponse
}

object AgentRemoteExecutionTools {
    const val CONTRACT_PROTOCOL = "signalasi.remote-execution"
    const val CONTRACT_VERSION = 1
    const val TOOL_VERSION = "1.0.0"
    const val REQUIRED_LOCATION = "trusted_paired_desktop"
    const val REQUIRED_TRANSPORT = "signalasi-link-encrypted"
    const val REQUEST_TYPE = "agent_remote_execution_request"
    const val RESPONSE_TYPE = "agent_remote_execution_response"
    const val CANCELLATION_TYPE = "agent_remote_execution_cancel"

    val definitions: List<AgentRemoteExecutionToolDefinition> = AgentRemoteExecutionTool.entries.map {
        definition(it)
    }

    val registry: AgentRemoteExecutionToolRegistry by lazy {
        AgentRemoteExecutionToolRegistry(definitions = definitions)
    }

    fun catalogPayload(): AgentRemoteExecutionPayload = linkedMapOf(
        "protocol" to CONTRACT_PROTOCOL,
        "version" to CONTRACT_VERSION,
        "location" to REQUIRED_LOCATION,
        "transport" to REQUIRED_TRANSPORT,
        "tools" to definitions.map(AgentRemoteExecutionToolDefinition::toCatalogPayload)
    )

    private fun definition(tool: AgentRemoteExecutionTool): AgentRemoteExecutionToolDefinition {
        val commandSchema = arraySchema(stringSchema(maxLength = 4_096), maxItems = 256)
        val scriptSchema = stringSchema(maxLength = 64 * 1024)
        val commonProperties = linkedMapOf<String, Any?>(
            "cwd" to stringSchema(maxLength = 1_024),
            "environment" to mapOf(
                "type" to "object",
                "additionalProperties" to stringSchema(maxLength = 4_096)
            ),
            "timeout_ms" to integerSchema(1, 15 * 60_000),
            "limits" to limitsSchema(),
            "artifact_paths" to arraySchema(stringSchema(maxLength = 1_024), maxItems = 32)
        )
        val toolProperties: Map<String, Any?> = when (tool) {
            AgentRemoteExecutionTool.SHELL -> mapOf(
                "command_mode" to stringSchema(enumValues = AgentRemoteShellMode.entries.map { it.wireValue }),
                "command" to commandSchema,
                "script" to scriptSchema,
                "shell_dialect" to stringSchema(enumValues = AgentRemoteShellDialect.entries.map { it.wireValue })
            )
            AgentRemoteExecutionTool.PYTHON -> mapOf(
                "script" to scriptSchema,
                "arguments" to commandSchema
            )
            AgentRemoteExecutionTool.UV -> mapOf(
                "script" to scriptSchema,
                "arguments" to commandSchema,
                "dependencies" to arraySchema(stringSchema(maxLength = 256), maxItems = 64),
                "offline" to mapOf("type" to "boolean")
            )
            AgentRemoteExecutionTool.FFMPEG -> mapOf("arguments" to commandSchema)
        }
        val required = when (tool) {
            AgentRemoteExecutionTool.SHELL -> listOf("command_mode")
            AgentRemoteExecutionTool.PYTHON, AgentRemoteExecutionTool.UV -> listOf("script")
            AgentRemoteExecutionTool.FFMPEG -> listOf("arguments")
        } + listOf("cwd", "environment", "timeout_ms", "limits", "artifact_paths")
        return AgentRemoteExecutionToolDefinition(
            id = tool.id,
            version = TOOL_VERSION,
            title = when (tool) {
                AgentRemoteExecutionTool.SHELL -> "Run Desktop command"
                AgentRemoteExecutionTool.PYTHON -> "Run Desktop Python script"
                AgentRemoteExecutionTool.UV -> "Run Desktop uv Python script"
                AgentRemoteExecutionTool.FFMPEG -> "Run Desktop FFmpeg"
            },
            description = when (tool) {
                AgentRemoteExecutionTool.SHELL ->
                    "Run a typed command in a task-scoped Desktop workspace"
                AgentRemoteExecutionTool.PYTHON ->
                    "Run a bounded Python script in a task-scoped Desktop workspace"
                AgentRemoteExecutionTool.UV ->
                    "Run a bounded Python script through uv in a task-scoped Desktop workspace"
                AgentRemoteExecutionTool.FFMPEG ->
                    "Run FFmpeg with direct arguments in a task-scoped Desktop workspace"
            },
            tool = tool,
            risk = if (tool == AgentRemoteExecutionTool.FFMPEG) "medium" else "high",
            inputContract = linkedMapOf(
                "type" to "object",
                "properties" to (commonProperties + toolProperties),
                "required" to required,
                "additionalProperties" to false
            ),
            outputContract = outputSchema()
        )
    }

    private fun outputSchema(): AgentRemoteExecutionPayload = linkedMapOf(
        "type" to "object",
        "properties" to linkedMapOf(
            "status" to stringSchema(enumValues = AgentRemoteExecutionStatus.entries.map { it.wireValue }),
            "exit_code" to mapOf("type" to listOf("integer", "null")),
            "stdout" to stringSchema(),
            "stderr" to stringSchema(),
            "stdout_truncated" to mapOf("type" to "boolean"),
            "stderr_truncated" to mapOf("type" to "boolean"),
            "duration_ms" to integerSchema(0, Long.MAX_VALUE),
            "artifacts" to arraySchema(mapOf("type" to "object"), maxItems = 32),
            "provenance" to mapOf("type" to "object")
        ),
        "required" to listOf(
            "status",
            "exit_code",
            "stdout",
            "stderr",
            "stdout_truncated",
            "stderr_truncated",
            "duration_ms",
            "artifacts",
            "provenance"
        ),
        "additionalProperties" to false
    )

    private fun limitsSchema(): AgentRemoteExecutionPayload = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "max_stdout_bytes" to integerSchema(1, 512 * 1024),
            "max_stderr_bytes" to integerSchema(1, 512 * 1024),
            "max_artifact_count" to integerSchema(0, 32),
            "max_artifact_bytes" to integerSchema(0, 128L * 1024 * 1024),
            "max_total_artifact_bytes" to integerSchema(0, 256L * 1024 * 1024)
        ),
        "required" to listOf(
            "max_stdout_bytes",
            "max_stderr_bytes",
            "max_artifact_count",
            "max_artifact_bytes",
            "max_total_artifact_bytes"
        ),
        "additionalProperties" to false
    )

    private fun stringSchema(
        maxLength: Int? = null,
        enumValues: List<String> = emptyList()
    ): AgentRemoteExecutionPayload = linkedMapOf<String, Any?>("type" to "string").apply {
        maxLength?.let { put("maxLength", it) }
        if (enumValues.isNotEmpty()) put("enum", enumValues)
    }

    private fun integerSchema(minimum: Number, maximum: Number): AgentRemoteExecutionPayload = mapOf(
        "type" to "integer",
        "minimum" to minimum,
        "maximum" to maximum
    )

    private fun arraySchema(
        items: AgentRemoteExecutionPayload,
        maxItems: Int
    ): AgentRemoteExecutionPayload = mapOf(
        "type" to "array",
        "items" to items,
        "maxItems" to maxItems
    )
}

class AgentRemoteExecutionToolRegistry(
    val policy: AgentRemoteExecutionPolicy = AgentRemoteExecutionPolicy(),
    definitions: List<AgentRemoteExecutionToolDefinition> = AgentRemoteExecutionTools.definitions
) {
    private val definitionsById = definitions.associateBy { it.id }

    init {
        require(definitionsById.size == definitions.size) { "Remote execution tool ids must be unique" }
        require(definitions.all { !it.localAndroidExecution }) {
            "Remote execution registry cannot contain a local Android executor"
        }
    }

    fun definitions(): List<AgentRemoteExecutionToolDefinition> =
        definitionsById.values.sortedBy { it.id }

    fun lookup(id: String): AgentRemoteExecutionToolDefinition? = definitionsById[id]

    fun validate(request: AgentRemoteExecutionRequest): AgentRemoteExecutionValidationResult {
        val issues = mutableListOf<AgentRemoteExecutionValidationIssue>()
        validateTarget(request.target, issues)
        if (lookup(request.toolId) == null) {
            issues.addIssue("$.tool_id", "unknown_tool", "Remote execution tool is not registered")
        }
        validateIdentity(request, issues)
        validateWorkspacePath(request.spec.cwd, "$.payload.cwd", allowRoot = true, issues = issues)
        validateEnvironment(request.spec.environment, issues)
        validateTimeout(request.spec.timeoutMillis, issues)
        validateLimits(request.spec.limits, issues)
        if (request.spec.artifactPaths.size > request.spec.limits.maxArtifactCount) {
            issues.addIssue(
                "$.payload.artifact_paths",
                "artifact_count_limit",
                "Requested artifact paths exceed the request artifact limit"
            )
        }
        request.spec.artifactPaths.forEachIndexed { index, path ->
            validateWorkspacePath(path, "$.payload.artifact_paths[$index]", allowRoot = false, issues = issues)
        }
        when (val spec = request.spec) {
            is AgentRemoteShellSpec -> validateShell(spec, issues)
            is AgentRemotePythonSpec -> {
                validateScript(spec.script, "$.payload.script", issues)
                validateArguments(spec.arguments, "$.payload.arguments", issues)
            }
            is AgentRemoteUvSpec -> validateUv(spec, issues)
            is AgentRemoteFfmpegSpec -> validateFfmpeg(spec, issues)
        }
        return AgentRemoteExecutionValidationResult(issues)
    }

    fun requireValid(request: AgentRemoteExecutionRequest) {
        val result = validate(request)
        if (!result.isValid) throw AgentRemoteExecutionContractException(result.issues)
    }

    fun requestPayload(request: AgentRemoteExecutionRequest): AgentRemoteExecutionPayload {
        requireValid(request)
        return requestPayloadUnchecked(request)
    }

    fun cancellationPayload(request: AgentRemoteExecutionCancellationRequest): AgentRemoteExecutionPayload {
        requireValid(request.request)
        require(request.reason.isNotBlank()) { "Cancellation reason must not be blank" }
        require(request.sequence > request.request.sequence) { "Cancellation sequence must advance" }
        require(request.expiresAtEpochMillis > request.sentAtEpochMillis) {
            "Cancellation payload must expire after it is sent"
        }
        return commonEnvelope(
            request = request.request,
            type = AgentRemoteExecutionTools.CANCELLATION_TYPE,
            sequence = request.sequence,
            sentAt = request.sentAtEpochMillis,
            expiresAt = request.expiresAtEpochMillis,
            payload = linkedMapOf(
                "request_id" to request.request.requestId,
                "cancellation_id" to request.request.cancellationId,
                "reason" to request.reason
            )
        )
    }

    fun validateResponse(
        request: AgentRemoteExecutionRequest,
        response: AgentRemoteExecutionResponse
    ): AgentRemoteExecutionValidationResult {
        val issues = mutableListOf<AgentRemoteExecutionValidationIssue>()
        if (response.requestId != request.requestId) {
            issues.addIssue("$.request_id", "request_mismatch", "Response request id does not match")
        }
        if (response.cancellationId != request.cancellationId) {
            issues.addIssue(
                "$.cancellation_id",
                "cancellation_mismatch",
                "Response cancellation id does not match"
            )
        }
        if (response.durationMillis < 0) {
            issues.addIssue("$.duration_ms", "invalid_duration", "Duration must be non-negative")
        }
        if (
            response.status in setOf(
                AgentRemoteExecutionStatus.SUCCEEDED,
                AgentRemoteExecutionStatus.FAILED
            ) && response.exitCode == null
        ) {
            issues.addIssue("$.exit_code", "missing_exit_code", "Terminal process result requires an exit code")
        }
        if (utf8Size(response.stdout) > request.spec.limits.maxStdoutBytes) {
            issues.addIssue("$.stdout", "stdout_limit", "stdout exceeds the requested byte limit")
        }
        if (utf8Size(response.stderr) > request.spec.limits.maxStderrBytes) {
            issues.addIssue("$.stderr", "stderr_limit", "stderr exceeds the requested byte limit")
        }
        validateResponseArtifacts(request, response, issues)
        validateProvenance(request, response.provenance, issues)
        return AgentRemoteExecutionValidationResult(issues)
    }

    fun responsePayload(
        request: AgentRemoteExecutionRequest,
        response: AgentRemoteExecutionResponse
    ): AgentRemoteExecutionPayload {
        requireValid(request)
        val validation = validateResponse(request, response)
        if (!validation.isValid) throw AgentRemoteExecutionContractException(validation.issues)
        return responsePayloadUnchecked(request, response)
    }

    fun dispatch(
        request: AgentRemoteExecutionRequest,
        dispatcher: AgentRemoteExecutionDispatcher,
        cancellationToken: AgentRemoteExecutionCancellationToken =
            AgentRemoteExecutionCancellationToken.NONE
    ): AgentRemoteExecutionPayload {
        val validation = validate(request)
        if (!validation.isValid) {
            return responsePayloadUnchecked(
                request,
                gateResponse(
                    request,
                    AgentRemoteExecutionStatus.REJECTED,
                    "request_rejected",
                    "Remote execution request was rejected by Android policy",
                    validation.issues
                )
            )
        }
        if (cancellationToken.isCancellationRequested()) {
            return responsePayloadUnchecked(
                request,
                gateResponse(
                    request,
                    AgentRemoteExecutionStatus.CANCELLED,
                    "cancelled",
                    "Remote execution was cancelled before dispatch"
                )
            )
        }
        val response = try {
            dispatcher.dispatch(request.target, requestPayloadUnchecked(request), cancellationToken)
        } catch (error: Exception) {
            return responsePayloadUnchecked(
                request,
                gateResponse(
                    request,
                    AgentRemoteExecutionStatus.TRANSPORT_FAILED,
                    "encrypted_link_dispatch_failed",
                    error.message?.take(512).orEmpty().ifBlank { "Encrypted Link dispatch failed" }
                )
            )
        }
        val responseValidation = validateResponse(request, response)
        if (!responseValidation.isValid) {
            return responsePayloadUnchecked(
                request,
                gateResponse(
                    request,
                    AgentRemoteExecutionStatus.REJECTED,
                    "invalid_remote_response",
                    "Desktop returned a response that violates the execution contract",
                    responseValidation.issues
                )
            )
        }
        return responsePayloadUnchecked(request, response)
    }

    fun dispatchCancellation(
        cancellation: AgentRemoteExecutionCancellationRequest,
        dispatcher: AgentRemoteExecutionDispatcher
    ): AgentRemoteExecutionPayload {
        val request = cancellation.request
        val validation = validate(request)
        if (!validation.isValid) {
            return responsePayloadUnchecked(
                request,
                gateResponse(
                    request,
                    AgentRemoteExecutionStatus.REJECTED,
                    "cancellation_rejected",
                    "Remote cancellation target was rejected by Android policy",
                    validation.issues
                )
            )
        }
        val response = try {
            dispatcher.dispatchCancellation(request.target, cancellationPayload(cancellation))
        } catch (error: Exception) {
            return responsePayloadUnchecked(
                request,
                gateResponse(
                    request,
                    AgentRemoteExecutionStatus.TRANSPORT_FAILED,
                    "encrypted_link_cancel_failed",
                    error.message?.take(512).orEmpty().ifBlank { "Encrypted Link cancellation failed" }
                )
            )
        }
        val responseValidation = validateResponse(request, response)
        return if (responseValidation.isValid) {
            responsePayloadUnchecked(request, response)
        } else {
            responsePayloadUnchecked(
                request,
                gateResponse(
                    request,
                    AgentRemoteExecutionStatus.REJECTED,
                    "invalid_remote_response",
                    "Desktop returned an invalid cancellation response",
                    responseValidation.issues
                )
            )
        }
    }

    private fun validateTarget(
        target: AgentRemoteExecutionTarget,
        issues: MutableList<AgentRemoteExecutionValidationIssue>
    ) {
        if (target.desktopId.isBlank() || target.desktopFingerprint.isBlank() || target.relationshipId.isBlank()) {
            issues.addIssue("$.target", "invalid_target_identity", "Desktop trust identity is incomplete")
        }
        if (!target.paired) {
            issues.addIssue("$.target", "unpaired_target", "Desktop is not paired")
        }
        if (!target.trusted) {
            issues.addIssue("$.target", "untrusted_target", "Desktop is not trusted")
        }
        if (target.revoked) {
            issues.addIssue("$.target", "revoked_target", "Desktop relationship has been revoked")
        }
        if (!target.encryptedLinkAvailable) {
            issues.addIssue(
                "$.target",
                "encrypted_link_unavailable",
                "Trusted Desktop does not have an encrypted Link route"
            )
        }
    }

    private fun validateIdentity(
        request: AgentRemoteExecutionRequest,
        issues: MutableList<AgentRemoteExecutionValidationIssue>
    ) {
        mapOf(
            "request_id" to request.requestId,
            "session_id" to request.sessionId,
            "conversation_id" to request.conversationId,
            "turn_id" to request.turnId,
            "task_id" to request.taskId,
            "workspace_id" to request.workspaceId,
            "cancellation_id" to request.cancellationId
        ).forEach { (name, value) ->
            if (value.isBlank()) issues.addIssue("$.$name", "required", "$name must not be blank")
        }
        if (request.sequence <= 0) {
            issues.addIssue("$.sequence", "invalid_sequence", "Sequence must be positive")
        }
        if (request.sentAtEpochMillis <= 0 || request.expiresAtEpochMillis <= request.sentAtEpochMillis) {
            issues.addIssue("$.expires_at", "invalid_expiry", "Request expiry must follow its sent time")
        }
        if (
            request.expiresAtEpochMillis - request.sentAtEpochMillis >
            AgentRemoteExecutionRequest.DEFAULT_MESSAGE_TTL_MILLIS
        ) {
            issues.addIssue("$.expires_at", "expiry_limit", "Request lifetime exceeds the contract limit")
        }
    }

    private fun validateEnvironment(
        environment: Map<String, String>,
        issues: MutableList<AgentRemoteExecutionValidationIssue>
    ) {
        if (environment.size > policy.maxEnvironmentEntries) {
            issues.addIssue(
                "$.payload.environment",
                "environment_count_limit",
                "Environment entry count exceeds policy"
            )
        }
        environment.forEach { (rawName, value) ->
            val name = rawName.uppercase(Locale.ROOT)
            val path = "$.payload.environment.$rawName"
            when {
                !ENVIRONMENT_NAME_PATTERN.matches(rawName) ->
                    issues.addIssue(path, "invalid_environment_name", "Environment name is invalid")
                isSecretEnvironmentName(name) ->
                    issues.addIssue(path, "secret_environment_name", "Secret environment names are prohibited")
                name !in policy.normalizedEnvironmentAllowlist ->
                    issues.addIssue(path, "environment_not_allowed", "Environment name is not allowlisted")
            }
            if (value.indexOf('\u0000') >= 0) {
                issues.addIssue(path, "nul_byte", "Environment value contains a NUL byte")
            }
            if (utf8Size(value) > policy.maxEnvironmentValueBytes) {
                issues.addIssue(path, "environment_value_limit", "Environment value exceeds policy")
            }
        }
    }

    private fun validateTimeout(
        timeoutMillis: Long,
        issues: MutableList<AgentRemoteExecutionValidationIssue>
    ) {
        if (timeoutMillis <= 0 || timeoutMillis > policy.maxTimeoutMillis) {
            issues.addIssue(
                "$.payload.timeout_ms",
                "timeout_limit",
                "Timeout must be positive and no greater than ${policy.maxTimeoutMillis} ms"
            )
        }
    }

    private fun validateLimits(
        limits: AgentRemoteExecutionLimits,
        issues: MutableList<AgentRemoteExecutionValidationIssue>
    ) {
        if (limits.maxStdoutBytes <= 0 || limits.maxStdoutBytes > policy.maxStdoutBytes) {
            issues.addIssue("$.payload.limits.max_stdout_bytes", "output_limit", "stdout limit is invalid")
        }
        if (limits.maxStderrBytes <= 0 || limits.maxStderrBytes > policy.maxStderrBytes) {
            issues.addIssue("$.payload.limits.max_stderr_bytes", "output_limit", "stderr limit is invalid")
        }
        if (limits.maxArtifactCount < 0 || limits.maxArtifactCount > policy.maxArtifactCount) {
            issues.addIssue(
                "$.payload.limits.max_artifact_count",
                "artifact_limit",
                "Artifact count limit is invalid"
            )
        }
        if (limits.maxArtifactBytes < 0 || limits.maxArtifactBytes > policy.maxArtifactBytes) {
            issues.addIssue(
                "$.payload.limits.max_artifact_bytes",
                "artifact_limit",
                "Per-artifact byte limit is invalid"
            )
        }
        if (
            limits.maxTotalArtifactBytes < 0 ||
            limits.maxTotalArtifactBytes > policy.maxTotalArtifactBytes ||
            limits.maxArtifactBytes > limits.maxTotalArtifactBytes
        ) {
            issues.addIssue(
                "$.payload.limits.max_total_artifact_bytes",
                "artifact_limit",
                "Total artifact byte limit is invalid"
            )
        }
    }

    private fun validateShell(
        spec: AgentRemoteShellSpec,
        issues: MutableList<AgentRemoteExecutionValidationIssue>
    ) {
        when (spec.mode) {
            AgentRemoteShellMode.DIRECT_ARGV -> {
                if (spec.command.isEmpty()) {
                    issues.addIssue("$.payload.command", "required", "Direct command argv must not be empty")
                }
                if (spec.script.isNotBlank() || spec.dialect != null) {
                    issues.addIssue(
                        "$.payload.command_mode",
                        "ambiguous_shell_mode",
                        "Direct argv cannot include a shell script or dialect"
                    )
                }
                validateArguments(spec.command, "$.payload.command", issues)
                if (spec.command.firstOrNull()?.substringAfterLast('/')?.substringAfterLast('\\')
                        ?.lowercase(Locale.ROOT) in SHELL_LAUNCHERS
                ) {
                    issues.addIssue(
                        "$.payload.command[0]",
                        "implicit_shell",
                        "Shell launchers require explicit shell_script mode"
                    )
                }
                spec.command.forEachIndexed { index, argument ->
                    if (containsShellControlSyntax(argument)) {
                        issues.addIssue(
                            "$.payload.command[$index]",
                            "shell_chaining_not_typed",
                            "Shell control syntax requires explicit shell_script mode"
                        )
                    }
                }
            }
            AgentRemoteShellMode.SHELL_SCRIPT -> {
                if (spec.command.isNotEmpty()) {
                    issues.addIssue(
                        "$.payload.command",
                        "ambiguous_shell_mode",
                        "Explicit shell_script mode accepts script, not argv"
                    )
                }
                if (spec.dialect == null) {
                    issues.addIssue(
                        "$.payload.shell_dialect",
                        "required",
                        "Explicit shell_script mode requires a typed shell dialect"
                    )
                }
                validateScript(spec.script, "$.payload.script", issues)
            }
        }
    }

    private fun validateUv(
        spec: AgentRemoteUvSpec,
        issues: MutableList<AgentRemoteExecutionValidationIssue>
    ) {
        validateScript(spec.script, "$.payload.script", issues)
        validateArguments(spec.arguments, "$.payload.arguments", issues)
        if (spec.dependencies.size > 64) {
            issues.addIssue("$.payload.dependencies", "dependency_limit", "uv dependency count exceeds policy")
        }
        spec.dependencies.forEachIndexed { index, dependency ->
            if (!UV_DEPENDENCY_PATTERN.matches(dependency) || utf8Size(dependency) > 256) {
                issues.addIssue(
                    "$.payload.dependencies[$index]",
                    "invalid_dependency",
                    "uv dependency must be a bounded package requirement, not an option or URL"
                )
            }
        }
    }

    private fun validateFfmpeg(
        spec: AgentRemoteFfmpegSpec,
        issues: MutableList<AgentRemoteExecutionValidationIssue>
    ) {
        if (spec.arguments.isEmpty()) {
            issues.addIssue("$.payload.arguments", "required", "FFmpeg arguments must not be empty")
        }
        validateArguments(spec.arguments, "$.payload.arguments", issues)
        if (spec.arguments.firstOrNull()?.lowercase(Locale.ROOT) in setOf("ffmpeg", "ffmpeg.exe")) {
            issues.addIssue(
                "$.payload.arguments[0]",
                "executable_not_allowed",
                "FFmpeg executable is pinned by the Desktop gateway; send arguments only"
            )
        }
    }

    private fun validateScript(
        script: String,
        path: String,
        issues: MutableList<AgentRemoteExecutionValidationIssue>
    ) {
        if (script.isBlank()) issues.addIssue(path, "required", "Script must not be blank")
        if (script.indexOf('\u0000') >= 0) issues.addIssue(path, "nul_byte", "Script contains a NUL byte")
        if (utf8Size(script) > policy.maxScriptBytes) {
            issues.addIssue(path, "script_size_limit", "Script exceeds the ${policy.maxScriptBytes}-byte limit")
        }
    }

    private fun validateArguments(
        arguments: List<String>,
        path: String,
        issues: MutableList<AgentRemoteExecutionValidationIssue>
    ) {
        if (arguments.size > policy.maxArguments) {
            issues.addIssue(path, "argument_count_limit", "Argument count exceeds policy")
        }
        if (arguments.any { it.indexOf('\u0000') >= 0 }) {
            issues.addIssue(path, "nul_byte", "Arguments contain a NUL byte")
        }
        val bytes = arguments.sumOf { utf8Size(it).toLong() + 1 }
        if (bytes > policy.maxCommandBytes) {
            issues.addIssue(path, "command_size_limit", "Command arguments exceed the byte limit")
        }
    }

    private fun validateWorkspacePath(
        path: String,
        field: String,
        allowRoot: Boolean,
        issues: MutableList<AgentRemoteExecutionValidationIssue>
    ) {
        val bytes = utf8Size(path)
        when {
            path.isBlank() -> issues.addIssue(field, "invalid_workspace_path", "Workspace path is blank")
            bytes > policy.maxCwdBytes ->
                issues.addIssue(field, "workspace_path_limit", "Workspace path exceeds policy")
            path.indexOf('\u0000') >= 0 ->
                issues.addIssue(field, "nul_byte", "Workspace path contains a NUL byte")
            isAbsolutePath(path) ->
                issues.addIssue(field, "absolute_workspace_path", "Absolute workspace paths are prohibited")
            path.split('/', '\\').any { it == ".." } ->
                issues.addIssue(field, "workspace_traversal", "Workspace traversal is prohibited")
            !allowRoot && path.replace('\\', '/').trimEnd('/') in setOf("", ".") ->
                issues.addIssue(field, "invalid_artifact_path", "Artifact path must identify a file")
        }
    }

    private fun validateResponseArtifacts(
        request: AgentRemoteExecutionRequest,
        response: AgentRemoteExecutionResponse,
        issues: MutableList<AgentRemoteExecutionValidationIssue>
    ) {
        val limits = request.spec.limits
        if (response.artifacts.size > limits.maxArtifactCount) {
            issues.addIssue("$.artifacts", "artifact_count_limit", "Response has too many artifacts")
        }
        var totalBytes = 0L
        response.artifacts.forEachIndexed { index, artifact ->
            val path = "$.artifacts[$index]"
            validateWorkspacePath(artifact.relativePath, "$path.relative_path", false, issues)
            if (artifact.artifactId.isBlank() || artifact.mimeType.isBlank()) {
                issues.addIssue(path, "invalid_artifact", "Artifact identity and MIME type are required")
            }
            if (artifact.sizeBytes < 0 || artifact.sizeBytes > limits.maxArtifactBytes) {
                issues.addIssue("$path.size_bytes", "artifact_size_limit", "Artifact exceeds its byte limit")
            }
            totalBytes = if (Long.MAX_VALUE - totalBytes < artifact.sizeBytes) {
                Long.MAX_VALUE
            } else {
                totalBytes + artifact.sizeBytes.coerceAtLeast(0)
            }
            if (!SHA256_PATTERN.matches(artifact.sha256)) {
                issues.addIssue("$path.sha256", "invalid_sha256", "Artifact SHA-256 is invalid")
            }
            if (artifact.encryptedReference.isBlank()) {
                issues.addIssue(
                    "$path.encrypted_reference",
                    "missing_encrypted_reference",
                    "Artifact requires an encrypted task-scoped reference"
                )
            }
            if (artifact.expiresAtEpochMillis <= 0) {
                issues.addIssue("$path.expires_at", "invalid_expiry", "Artifact reference must expire")
            }
        }
        if (totalBytes > limits.maxTotalArtifactBytes) {
            issues.addIssue("$.artifacts", "artifact_total_limit", "Artifact total exceeds the byte limit")
        }
    }

    private fun validateProvenance(
        request: AgentRemoteExecutionRequest,
        provenance: AgentRemoteExecutionProvenance,
        issues: MutableList<AgentRemoteExecutionValidationIssue>
    ) {
        if (
            provenance.desktopId != request.target.desktopId ||
            provenance.desktopFingerprint != request.target.desktopFingerprint ||
            provenance.relationshipId != request.target.relationshipId
        ) {
            issues.addIssue("$.provenance", "target_mismatch", "Provenance does not match the paired Desktop")
        }
        if (provenance.toolId != request.toolId || provenance.toolVersion != AgentRemoteExecutionTools.TOOL_VERSION) {
            issues.addIssue("$.provenance", "tool_mismatch", "Provenance tool identity does not match")
        }
        if (provenance.workspaceId != request.workspaceId || provenance.workspaceOwnership != "desktop") {
            issues.addIssue(
                "$.provenance.workspace_id",
                "workspace_mismatch",
                "Provenance must identify the Desktop-owned task workspace"
            )
        }
        if (
            provenance.transport != AgentRemoteExecutionTools.REQUIRED_TRANSPORT ||
            !provenance.encrypted
        ) {
            issues.addIssue("$.provenance.transport", "unencrypted_transport", "Encrypted Link provenance is required")
        }
        if (provenance.executedOnAndroid) {
            issues.addIssue(
                "$.provenance.executed_on_android",
                "android_execution_prohibited",
                "Remote execution must never run in a local Android shell"
            )
        }
        if (provenance.executorId.isBlank() || provenance.executorVersion.isBlank()) {
            issues.addIssue("$.provenance.executor_id", "invalid_executor", "Desktop executor identity is required")
        }
    }

    private fun requestPayloadUnchecked(request: AgentRemoteExecutionRequest): AgentRemoteExecutionPayload {
        val spec = request.spec
        val payload = linkedMapOf<String, Any?>(
            "tool_id" to request.toolId,
            "tool_version" to AgentRemoteExecutionTools.TOOL_VERSION,
            "execution_location" to AgentRemoteExecutionTools.REQUIRED_LOCATION,
            "cwd" to normalizeRelativePath(spec.cwd),
            "environment" to spec.environment.toSortedMap(),
            "timeout_ms" to spec.timeoutMillis,
            "limits" to limitsPayload(spec.limits),
            "artifact_paths" to spec.artifactPaths.map(::normalizeRelativePath),
            "cancellation_id" to request.cancellationId
        )
        when (spec) {
            is AgentRemoteShellSpec -> {
                payload["command_mode"] = spec.mode.wireValue
                payload["command"] = spec.command
                payload["script"] = spec.script
                payload["shell_dialect"] = spec.dialect?.wireValue
            }
            is AgentRemotePythonSpec -> {
                payload["script"] = spec.script
                payload["arguments"] = spec.arguments
            }
            is AgentRemoteUvSpec -> {
                payload["script"] = spec.script
                payload["arguments"] = spec.arguments
                payload["dependencies"] = spec.dependencies
                payload["offline"] = spec.offline
            }
            is AgentRemoteFfmpegSpec -> payload["arguments"] = spec.arguments
        }
        return commonEnvelope(
            request = request,
            type = AgentRemoteExecutionTools.REQUEST_TYPE,
            sequence = request.sequence,
            sentAt = request.sentAtEpochMillis,
            expiresAt = request.expiresAtEpochMillis,
            payload = payload
        )
    }

    private fun responsePayloadUnchecked(
        request: AgentRemoteExecutionRequest,
        response: AgentRemoteExecutionResponse
    ): AgentRemoteExecutionPayload = commonEnvelope(
        request = request,
        type = AgentRemoteExecutionTools.RESPONSE_TYPE,
        sequence = request.sequence,
        sentAt = request.sentAtEpochMillis,
        expiresAt = request.expiresAtEpochMillis,
        payload = linkedMapOf(
            "request_id" to response.requestId,
            "cancellation_id" to response.cancellationId,
            "status" to response.status.wireValue,
            "exit_code" to response.exitCode,
            "stdout" to response.stdout,
            "stderr" to response.stderr,
            "stdout_truncated" to response.stdoutTruncated,
            "stderr_truncated" to response.stderrTruncated,
            "duration_ms" to response.durationMillis,
            "artifacts" to response.artifacts.map(::artifactPayload),
            "provenance" to provenancePayload(response.provenance),
            "error" to response.error?.let(::errorPayload)
        )
    )

    private fun commonEnvelope(
        request: AgentRemoteExecutionRequest,
        type: String,
        sequence: Long,
        sentAt: Long,
        expiresAt: Long,
        payload: AgentRemoteExecutionPayload
    ): AgentRemoteExecutionPayload = linkedMapOf(
        "protocol" to AgentRemoteExecutionTools.CONTRACT_PROTOCOL,
        "version" to AgentRemoteExecutionTools.CONTRACT_VERSION,
        "type" to type,
        "content" to "",
        "message_id" to request.requestId,
        "session_id" to request.sessionId,
        "conversation_id" to request.conversationId,
        "turn_id" to request.turnId,
        "task_id" to request.taskId,
        "workspace_id" to request.workspaceId,
        "sequence" to sequence,
        "sent_at" to sentAt,
        "expires_at" to expiresAt,
        "encrypted_link_required" to true,
        "target" to linkedMapOf(
            "kind" to AgentRemoteExecutionTools.REQUIRED_LOCATION,
            "desktop_id" to request.target.desktopId,
            "desktop_fingerprint" to request.target.desktopFingerprint,
            "relationship_id" to request.target.relationshipId
        ),
        "payload" to payload
    )

    private fun limitsPayload(limits: AgentRemoteExecutionLimits): AgentRemoteExecutionPayload = linkedMapOf(
        "max_stdout_bytes" to limits.maxStdoutBytes,
        "max_stderr_bytes" to limits.maxStderrBytes,
        "max_artifact_count" to limits.maxArtifactCount,
        "max_artifact_bytes" to limits.maxArtifactBytes,
        "max_total_artifact_bytes" to limits.maxTotalArtifactBytes
    )

    private fun artifactPayload(artifact: AgentRemoteExecutionArtifact): AgentRemoteExecutionPayload = linkedMapOf(
        "artifact_id" to artifact.artifactId,
        "relative_path" to normalizeRelativePath(artifact.relativePath),
        "mime_type" to artifact.mimeType,
        "size_bytes" to artifact.sizeBytes,
        "sha256" to artifact.sha256.lowercase(Locale.ROOT),
        "encrypted_reference" to artifact.encryptedReference,
        "expires_at" to artifact.expiresAtEpochMillis,
        "ownership" to "desktop"
    )

    private fun provenancePayload(provenance: AgentRemoteExecutionProvenance): AgentRemoteExecutionPayload =
        linkedMapOf(
            "desktop_id" to provenance.desktopId,
            "desktop_fingerprint" to provenance.desktopFingerprint,
            "relationship_id" to provenance.relationshipId,
            "executor_id" to provenance.executorId,
            "executor_version" to provenance.executorVersion,
            "tool_id" to provenance.toolId,
            "tool_version" to provenance.toolVersion,
            "workspace_id" to provenance.workspaceId,
            "workspace_ownership" to provenance.workspaceOwnership,
            "transport" to provenance.transport,
            "encrypted" to provenance.encrypted,
            "executed_on_android" to provenance.executedOnAndroid
        )

    private fun errorPayload(error: AgentRemoteExecutionError): AgentRemoteExecutionPayload = linkedMapOf(
        "code" to error.code,
        "message" to error.message,
        "retryable" to error.retryable,
        "details" to error.details
    )

    private fun gateResponse(
        request: AgentRemoteExecutionRequest,
        status: AgentRemoteExecutionStatus,
        code: String,
        message: String,
        issues: List<AgentRemoteExecutionValidationIssue> = emptyList()
    ): AgentRemoteExecutionResponse = AgentRemoteExecutionResponse(
        requestId = request.requestId,
        cancellationId = request.cancellationId,
        status = status,
        provenance = AgentRemoteExecutionProvenance.forDesktop(
            request = request,
            executorId = "signalasi.android.remote_execution_gate",
            executorVersion = AgentRemoteExecutionTools.TOOL_VERSION
        ),
        error = AgentRemoteExecutionError(
            code = code,
            message = message,
            retryable = status == AgentRemoteExecutionStatus.TRANSPORT_FAILED,
            details = if (issues.isEmpty()) emptyMap() else mapOf(
                "issues" to issues.map {
                    linkedMapOf("path" to it.path, "code" to it.code, "message" to it.message)
                }
            )
        )
    )

    private fun isAbsolutePath(path: String): Boolean =
        path.startsWith('/') ||
            path.startsWith('\\') ||
            WINDOWS_DRIVE_PATTERN.containsMatchIn(path) ||
            URI_SCHEME_PATTERN.containsMatchIn(path)

    private fun normalizeRelativePath(path: String): String {
        val normalized = path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        return normalized.joinToString("/").ifBlank { "." }
    }

    private fun containsShellControlSyntax(argument: String): Boolean =
        SHELL_CONTROL_MARKERS.any(argument::contains)

    private fun isSecretEnvironmentName(name: String): Boolean {
        val compact = name.replace("_", "")
        if (SECRET_COMPACT_MARKERS.any(compact::contains)) return true
        return name.split('_').any { it in SECRET_ENVIRONMENT_TOKENS }
    }

    private fun utf8Size(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    private fun MutableList<AgentRemoteExecutionValidationIssue>.addIssue(
        path: String,
        code: String,
        message: String
    ) {
        add(AgentRemoteExecutionValidationIssue(path, code, message))
    }

    companion object {
        private val ENVIRONMENT_NAME_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
        private val WINDOWS_DRIVE_PATTERN = Regex("^[A-Za-z]:")
        private val URI_SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
        private val SHA256_PATTERN = Regex("[0-9A-Fa-f]{64}")
        private val UV_DEPENDENCY_PATTERN = Regex(
            "[A-Za-z0-9][A-Za-z0-9._-]*(?:\\[[A-Za-z0-9,._-]+])?(?:[<>=!~]{1,2}[A-Za-z0-9.*+_-]+)?"
        )
        private val SHELL_LAUNCHERS = setOf(
            "bash",
            "cmd",
            "cmd.exe",
            "powershell",
            "powershell.exe",
            "pwsh",
            "sh",
            "zsh"
        )
        private val SHELL_CONTROL_MARKERS = listOf(
            "&&",
            "||",
            ";",
            "|",
            ">",
            "<",
            "\n",
            "\r",
            "`",
            "$("
        )
        private val SECRET_ENVIRONMENT_TOKENS = setOf(
            "AUTH",
            "COOKIE",
            "CREDENTIAL",
            "CREDENTIALS",
            "KEY",
            "PASS",
            "PASSWD",
            "PASSWORD",
            "PRIVATE",
            "SECRET",
            "SESSION",
            "TOKEN"
        )
        private val SECRET_COMPACT_MARKERS = setOf(
            "ACCESSKEY",
            "APIKEY",
            "CLIENTSECRET",
            "PRIVATEKEY"
        )
    }
}
