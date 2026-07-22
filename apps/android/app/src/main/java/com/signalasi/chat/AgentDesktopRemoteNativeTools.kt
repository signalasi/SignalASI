package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Typed proxies for tools executed by a paired SignalASI Desktop. */
object AgentDesktopRemoteNativeTools {
    const val SYSTEM_STATUS = "signalasi.desktop.windows.system.status"
    const val PROCESS_LIST = "signalasi.desktop.windows.process.list"
    const val FILE_LIST = "signalasi.desktop.workspace.file.list"
    const val FILE_READ_TEXT = "signalasi.desktop.workspace.file.read.text"
    const val FILE_WRITE_TEXT = "signalasi.desktop.workspace.file.write.text"
    const val FILE_SHA256 = "signalasi.desktop.workspace.file.sha256"
    const val ARCHIVE_CREATE = "signalasi.desktop.workspace.archive.create"
    const val TERMINAL_RUN = "signalasi.desktop.terminal.run"
    const val OFFICE_INSPECT = "signalasi.desktop.office.document.inspect"
    const val OFFICE_CONVERT = "signalasi.desktop.office.document.convert"

    const val READ_CONSENT = "signalasi.consent.desktop.read"
    const val WRITE_CONSENT = "signalasi.consent.desktop.write"
    const val EXECUTE_CONSENT = "signalasi.consent.desktop.execute"

    private const val VERSION = "1.0.0"
    private const val MAX_PATH = 4_096
    internal val workspaceToolIds = setOf(
        FILE_LIST,
        FILE_READ_TEXT,
        FILE_WRITE_TEXT,
        FILE_SHA256,
        ARCHIVE_CREATE,
        TERMINAL_RUN,
        OFFICE_INSPECT,
        OFFICE_CONVERT
    )

    val toolIds: Set<String> = linkedSetOf(
        SYSTEM_STATUS,
        PROCESS_LIST,
        FILE_LIST,
        FILE_READ_TEXT,
        FILE_WRITE_TEXT,
        FILE_SHA256,
        ARCHIVE_CREATE,
        TERMINAL_RUN,
        OFFICE_INSPECT,
        OFFICE_CONVERT
    )

    fun definitions(context: Context): List<AgentNativeToolDefinition> = listOf(
        definition(
            context,
            SYSTEM_STATUS,
            "Read Windows system status",
            "Reads bounded operating-system, CPU, and memory status from a paired Desktop.",
            schema(),
            AgentNativeToolRisk.LOW,
            AgentNativeToolIdempotency.IDEMPOTENT,
            emptyList(),
            30_000L,
            setOf("windows.status.read")
        ),
        definition(
            context,
            PROCESS_LIST,
            "List Windows processes",
            "Lists bounded process names, identifiers, and memory use without command lines.",
            schema(
                "query" to AgentNativeJsonSchema.string(maxLength = 128),
                "max_entries" to AgentNativeJsonSchema.integer(1, 200)
            ),
            AgentNativeToolRisk.LOW,
            AgentNativeToolIdempotency.IDEMPOTENT,
            emptyList(),
            30_000L,
            setOf("windows.process.list")
        ),
        definition(
            context,
            FILE_LIST,
            "List Desktop workspace",
            "Lists bounded entries inside a paired Desktop task workspace.",
            schema(
                "desktop_id" to desktopIdSchema(),
                "path" to pathSchema(),
                "recursive" to AgentNativeJsonSchema.boolean(),
                "max_entries" to AgentNativeJsonSchema.integer(1, 1_000)
            ),
            AgentNativeToolRisk.LOW,
            AgentNativeToolIdempotency.IDEMPOTENT,
            emptyList(),
            30_000L,
            setOf("desktop.workspace.read")
        ),
        definition(
            context,
            FILE_READ_TEXT,
            "Read Desktop workspace text",
            "Reads bounded UTF-8 text from a paired Desktop task workspace.",
            schema(
                "desktop_id" to desktopIdSchema(),
                "path" to pathSchema(),
                "max_bytes" to AgentNativeJsonSchema.integer(1, 131_072),
                required = setOf("path")
            ),
            AgentNativeToolRisk.LOW,
            AgentNativeToolIdempotency.IDEMPOTENT,
            emptyList(),
            30_000L,
            setOf("desktop.workspace.read")
        ),
        definition(
            context,
            FILE_WRITE_TEXT,
            "Write Desktop workspace text",
            "Atomically writes bounded UTF-8 text in a paired Desktop task workspace.",
            schema(
                "desktop_id" to desktopIdSchema(),
                "path" to pathSchema(),
                "content" to AgentNativeJsonSchema.string(maxLength = 1_048_576),
                "mode" to AgentNativeJsonSchema.string(enumValues = listOf("create", "overwrite")),
                "expected_sha256" to AgentNativeJsonSchema.string(maxLength = 64),
                required = setOf("path", "content", "mode")
            ),
            AgentNativeToolRisk.MEDIUM,
            AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
            listOf(consent(WRITE_CONSENT, "Write paired Desktop workspace")),
            30_000L,
            setOf("desktop.workspace.write")
        ),
        definition(
            context,
            FILE_SHA256,
            "Hash Desktop workspace file",
            "Calculates SHA-256 for a file in a paired Desktop task workspace.",
            workspacePathSchema(),
            AgentNativeToolRisk.LOW,
            AgentNativeToolIdempotency.IDEMPOTENT,
            emptyList(),
            30_000L,
            setOf("desktop.workspace.read", "hash.sha256")
        ),
        definition(
            context,
            ARCHIVE_CREATE,
            "Create Desktop workspace archive",
            "Creates a bounded ZIP from explicit files in a paired Desktop workspace.",
            schema(
                "desktop_id" to desktopIdSchema(),
                "paths" to AgentNativeJsonSchema.array(pathSchema(), maxItems = 256),
                "output_path" to pathSchema(),
                required = setOf("paths", "output_path")
            ),
            AgentNativeToolRisk.MEDIUM,
            AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
            listOf(consent(WRITE_CONSENT, "Create archive on paired Desktop")),
            60_000L,
            setOf("desktop.workspace.read", "desktop.workspace.write", "archive.zip")
        ),
        definition(
            context,
            TERMINAL_RUN,
            "Run Desktop workspace command",
            "Runs an allowlisted executable with an argument array and no command shell on a paired Desktop.",
            schema(
                "desktop_id" to desktopIdSchema(),
                "argv" to AgentNativeJsonSchema.array(AgentNativeJsonSchema.string(maxLength = 1_024), maxItems = 64),
                "cwd" to pathSchema(),
                "timeout_seconds" to AgentNativeJsonSchema.integer(1, 180),
                required = setOf("argv")
            ),
            AgentNativeToolRisk.HIGH,
            AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
            listOf(consent(EXECUTE_CONSENT, "Execute command on paired Desktop")),
            185_000L,
            setOf("desktop.terminal.execute", "desktop.workspace.read", "desktop.workspace.write")
        ),
        definition(
            context,
            OFFICE_INSPECT,
            "Inspect Office document",
            "Extracts bounded structure and text from XLSX, DOCX, or PPTX on a paired Desktop.",
            schema(
                "desktop_id" to desktopIdSchema(),
                "path" to pathSchema(),
                "max_items" to AgentNativeJsonSchema.integer(1, 200),
                required = setOf("path")
            ),
            AgentNativeToolRisk.LOW,
            AgentNativeToolIdempotency.IDEMPOTENT,
            emptyList(),
            30_000L,
            setOf("desktop.office.inspect", "desktop.workspace.read")
        ),
        definition(
            context,
            OFFICE_CONVERT,
            "Convert Office document",
            "Converts a Desktop workspace Office document to PDF, CSV, or text and verifies the artifact.",
            schema(
                "desktop_id" to desktopIdSchema(),
                "path" to pathSchema(),
                "output_format" to AgentNativeJsonSchema.string(enumValues = listOf("pdf", "csv", "txt")),
                "output_path" to pathSchema(),
                required = setOf("path", "output_format")
            ),
            AgentNativeToolRisk.MEDIUM,
            AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
            listOf(consent(WRITE_CONSENT, "Create converted document on paired Desktop")),
            90_000L,
            setOf("desktop.office.convert", "desktop.workspace.read", "desktop.workspace.write")
        )
    )

    fun updateManifest(context: Context, payload: JSONObject) {
        DesktopToolCapabilityStore.update(context, payload)
    }

    fun removeDesktop(context: Context, desktopId: String) {
        DesktopToolCapabilityStore.remove(context, desktopId)
    }

    fun handleInbound(payload: JSONObject): Boolean = DesktopToolTransport.handleInbound(payload)

    private fun definition(
        context: Context,
        id: String,
        title: String,
        description: String,
        inputSchema: AgentNativeJsonSchema,
        risk: AgentNativeToolRisk,
        idempotency: AgentNativeToolIdempotency,
        consents: List<AgentNativeConsentRequirement>,
        timeoutMillis: Long,
        capabilities: Set<String>
    ): AgentNativeToolDefinition {
        val appContext = context.applicationContext
        return AgentNativeToolDefinition(
            descriptor = AgentNativeToolDescriptor(
                id = id,
                version = VERSION,
                title = title,
                description = description,
                location = AgentNativeToolLocation.DESKTOP,
                inputSchema = inputSchema,
                outputSchema = AgentNativeJsonSchema.objectSchema(additionalProperties = true),
                risk = risk,
                capabilities = capabilities,
                requiredConsents = consents,
                timeoutMillis = timeoutMillis,
                idempotency = idempotency,
                availability = AgentNativeToolAvailability(
                    AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
                    "Waiting for a paired Desktop capability manifest"
                )
            ),
            executor = AgentNativeToolExecutor { invocation ->
                DesktopToolTransport.invoke(appContext, invocation)
            },
            verifier = AgentNativeToolVerifier { _, execution ->
                val status = execution.metadata["remote_verification_status"]?.toString().orEmpty()
                when (status) {
                    "passed" -> AgentNativeToolVerification(
                        AgentNativeVerificationStatus.PASSED,
                        "Paired Desktop returned host-observed verification evidence",
                        (execution.metadata["remote_verification_evidence"] as? Map<*, *>)
                            ?.entries?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
                            ?.toMap().orEmpty()
                    )
                    "failed" -> AgentNativeToolVerification(
                        AgentNativeVerificationStatus.FAILED,
                        "Paired Desktop verification failed"
                    )
                    else -> AgentNativeToolVerification(
                        AgentNativeVerificationStatus.SKIPPED,
                        "Paired Desktop did not claim verified completion"
                    )
                }
            },
            executorId = "signalasi.desktop_remote",
            provenanceMetadata = mapOf("transport" to "signalasi-link-v1"),
            availabilityProvider = AgentNativeToolAvailabilityProvider {
                DesktopToolCapabilityStore.availability(appContext, id)
            }
        )
    }

    private fun schema(
        vararg properties: Pair<String, AgentNativeJsonSchema>,
        required: Set<String> = emptySet()
    ) = AgentNativeJsonSchema.objectSchema(properties.toMap(), required, additionalProperties = false)

    private fun workspacePathSchema() = schema(
        "desktop_id" to desktopIdSchema(),
        "path" to pathSchema(),
        required = setOf("path")
    )

    private fun desktopIdSchema() = AgentNativeJsonSchema.string(maxLength = 160)
    private fun pathSchema() = AgentNativeJsonSchema.string(maxLength = MAX_PATH)

    private fun consent(id: String, title: String) = AgentNativeConsentRequirement(
        id = id,
        title = title,
        description = "Approval is bound to the exact paired Desktop tool call."
    )
}

private object DesktopToolCapabilityStore {
    private const val PREFERENCES = "signalasi_desktop_native_tools_v1"
    private const val MANIFESTS = "manifests"

    fun update(context: Context, payload: JSONObject) {
        if (payload.optString("type") != "capability_manifest") return
        val desktopId = payload.optJSONObject("server")?.optString("id")
            .orEmpty().ifBlank { payload.optString("desktop_id") }
        val native = payload.optJSONObject("desktop_native_tools") ?: return
        val tools = native.optJSONArray("tools") ?: return
        if (desktopId.isBlank()) return
        val root = read(context)
        val ids = JSONArray()
        for (index in 0 until tools.length()) {
            val id = tools.optJSONObject(index)?.optString("id").orEmpty()
            if (id in AgentDesktopRemoteNativeTools.toolIds) ids.put(id)
        }
        root.put(desktopId, JSONObject()
            .put("desktop_id", desktopId)
            .put("desktop_name", payload.optJSONObject("server")?.optString("name").orEmpty())
            .put("contract_version", native.optString("contract_version"))
            .put("updated_at", System.currentTimeMillis())
            .put("tools", ids))
        write(context, root)
    }

    fun remove(context: Context, desktopId: String) {
        val root = read(context)
        root.remove(desktopId)
        write(context, root)
    }

    fun availability(context: Context, toolId: String): AgentNativeToolAvailability {
        if (!SignalASIMqttClient.isConnected() || !SignalASIMqttClient.isSecureReady()) {
            return AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.UNAVAILABLE,
                "No secure paired Desktop connection is online",
                System.currentTimeMillis()
            )
        }
        val matches = availableDesktopIds(context, toolId)
        return if (matches.isNotEmpty()) {
            AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.AVAILABLE,
                "Available on ${matches.size} paired Desktop${if (matches.size == 1) "" else "s"}",
                System.currentTimeMillis()
            )
        } else {
            AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
                "No paired Desktop currently advertises this tool",
                System.currentTimeMillis()
            )
        }
    }

    fun selectDesktop(context: Context, requestedId: String, toolId: String): String {
        val matches = availableDesktopIds(context, toolId)
        if (requestedId.isNotBlank()) {
            require(requestedId in matches) { "The selected Desktop does not advertise $toolId" }
            return requestedId
        }
        require(matches.isNotEmpty()) { "No paired Desktop advertises $toolId" }
        require(matches.size == 1) { "Multiple Desktops advertise $toolId; select desktop_id" }
        return matches.single()
    }

    private fun availableDesktopIds(context: Context, toolId: String): List<String> {
        val paired = SignalASILinkProtocol.allServerLinks(context)
            .filter { it.paired && SignalASICrypto.hasDesktopSession(context, it.desktopId) }
            .mapTo(linkedSetOf()) { it.desktopId }
        val root = read(context)
        return root.keys().asSequence().filter { desktopId ->
            desktopId in paired && root.optJSONObject(desktopId)?.optJSONArray("tools")?.contains(toolId) == true
        }.sorted().toList()
    }

    private fun read(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(MANIFESTS, "{}").orEmpty()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun write(context: Context, value: JSONObject) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(MANIFESTS, value.toString()).apply()
    }

    private fun JSONArray.contains(value: String): Boolean {
        for (index in 0 until length()) if (optString(index) == value) return true
        return false
    }
}

private object DesktopToolTransport {
    private data class Pending(
        val desktopId: String,
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var response: JSONObject? = null
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    fun handleInbound(payload: JSONObject): Boolean {
        return when (payload.optString("type")) {
            "desktop_tool_call_result" -> {
                val callId = payload.optString("call_id")
                val item = pending[callId]
                if (item != null && payload.optString("desktop_id") == item.desktopId) {
                    item.response = payload
                    item.latch.countDown()
                }
                true
            }
            "desktop_tool_cancel_ack" -> true
            else -> false
        }
    }

    fun invoke(context: Context, invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult {
        invocation.checkpoint()
        val arguments = LinkedHashMap(invocation.input)
        val requestedDesktop = arguments.remove("desktop_id")?.toString().orEmpty()
        val desktopId = runCatching {
            DesktopToolCapabilityStore.selectDesktop(context, requestedDesktop, invocation.descriptor.id)
        }.getOrElse {
            return AgentNativeToolExecutionResult.failure("desktop_tool_unavailable", it.message.orEmpty(), retryable = true)
        }
        if (invocation.descriptor.id in AgentDesktopRemoteNativeTools.workspaceToolIds) {
            val workspaceId = invocation.context.attributes["workspace_id"].orEmpty()
            if (workspaceId.isBlank()) return AgentNativeToolExecutionResult.failure(
                "desktop_workspace_unavailable",
                "The current task has no Desktop workspace scope"
            )
            arguments["workspace_id"] = workspaceId
        }
        val callId = invocation.context.invocationId.ifBlank { UUID.randomUUID().toString() }
        val taskId = invocation.context.attributes["task_id"]
            .orEmpty().ifBlank { invocation.context.attributes["step_id"].orEmpty() }
            .ifBlank { "native-$callId" }
        val inputDigest = AgentNativeJsonCodec.sha256(arguments)
        val confirmation = invocation.descriptor.requiredConsents.takeIf { it.isNotEmpty() }?.let { required ->
            if (required.any { it.id !in invocation.context.grantedConsents }) return AgentNativeToolExecutionResult.failure(
                "confirmation_required",
                "Paired Desktop action requires confirmation"
            )
            JSONObject()
                .put("decision", "approved")
                .put("tool_id", invocation.descriptor.id)
                .put("tool_version", invocation.descriptor.version)
                .put("arguments_sha256", inputDigest)
                .put("confirmation_id", invocation.context.attributes["confirmation_id"].orEmpty())
                .put("expires_at", System.currentTimeMillis() + invocation.remainingTimeMillis.coerceAtMost(60_000L))
        }
        val conversationId = invocation.context.conversationId.ifBlank { invocation.context.sessionId }
        val payload = JSONObject()
            .put("type", "desktop_tool_call_request")
            .put("call_id", callId)
            .put("invocation_id", callId)
            .put("task_id", taskId)
            .put("conversation_id", conversationId)
            .put("workspace_id", arguments["workspace_id"]?.toString().orEmpty())
            .put("tool_id", invocation.descriptor.id)
            .put("tool_version", invocation.descriptor.version)
            .put("arguments", JSONObject(arguments))
            .put("idempotency_key", invocation.context.idempotencyKey.orEmpty())
            .put("confirmation", confirmation)
            .put("time", System.currentTimeMillis())
        val waiting = Pending(desktopId)
        check(pending.putIfAbsent(callId, waiting) == null) { "Duplicate Desktop tool invocation id" }
        val cancellationRegistration = invocation.cancellationToken.invokeOnCancellation {
            SignalASIMqttClient.publishDesktopToolCancel(desktopId, callId, taskId, conversationId)
            waiting.latch.countDown()
        }
        try {
            if (!SignalASIMqttClient.publishDesktopToolCall(desktopId, payload)) {
                return AgentNativeToolExecutionResult.failure(
                    "desktop_tool_publish_failed",
                    "Could not send the tool call to the paired Desktop",
                    retryable = true
                )
            }
            invocation.reportProgress("waiting_desktop", "Waiting for paired Desktop tool result")
            val completed = waiting.latch.await(invocation.remainingTimeMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            invocation.checkpoint()
            if (!completed) {
                SignalASIMqttClient.publishDesktopToolCancel(desktopId, callId, taskId, conversationId)
                return AgentNativeToolExecutionResult.failure(
                    "desktop_tool_timeout",
                    "Paired Desktop tool did not return before the deadline",
                    retryable = true
                )
            }
            val remote = waiting.response?.optJSONObject("result")
                ?: return AgentNativeToolExecutionResult.failure(
                    "desktop_tool_no_result",
                    "Paired Desktop returned no tool result",
                    retryable = true
                )
            val error = remote.optJSONObject("error")
            if (remote.optString("status") != "succeeded") {
                return AgentNativeToolExecutionResult.failure(
                    error?.optString("code").orEmpty().ifBlank { "desktop_tool_failed" },
                    error?.optString("message").orEmpty().ifBlank { remote.optString("message", "Desktop tool failed") },
                    error?.optBoolean("retryable", false) == true,
                    error?.optJSONObject("details").toNativeMap()
                )
            }
            val verification = remote.optJSONObject("verification")
            val output = LinkedHashMap(remote.optJSONObject("output").toNativeMap()).apply {
                put("desktop_id", desktopId)
                put("remote_receipt", remote.optJSONObject("receipt").toNativeMap())
                put("remote_provenance", remote.optJSONObject("provenance").toNativeMap())
                put("remote_artifacts", remote.optJSONArray("artifacts").toNativeList())
            }
            return AgentNativeToolExecutionResult.success(
                output = output,
                message = remote.optString("message"),
                metadata = mapOf(
                    "desktop_id" to desktopId,
                    "remote_verification_status" to verification?.optString("status").orEmpty(),
                    "remote_verification_evidence" to verification?.optJSONObject("evidence").toNativeMap()
                )
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            SignalASIMqttClient.publishDesktopToolCancel(desktopId, callId, taskId, conversationId)
            return AgentNativeToolExecutionResult.failure("desktop_tool_interrupted", "Desktop tool wait was interrupted", true)
        } finally {
            cancellationRegistration.dispose()
            pending.remove(callId, waiting)
        }
    }

    private fun JSONObject?.toNativeMap(): AgentNativeJsonObject {
        val source = this ?: return emptyMap()
        return source.keys().asSequence().associateWith { key -> source.opt(key).toNativeValue() }
    }

    private fun JSONArray?.toNativeList(): List<Any?> {
        val source = this ?: return emptyList()
        return buildList { for (index in 0 until source.length()) add(source.opt(index).toNativeValue()) }
    }

    private fun Any?.toNativeValue(): Any? = when (this) {
        null, JSONObject.NULL -> null
        is JSONObject -> toNativeMap()
        is JSONArray -> toNativeList()
        is String, is Boolean, is Number -> this
        else -> toString()
    }
}
