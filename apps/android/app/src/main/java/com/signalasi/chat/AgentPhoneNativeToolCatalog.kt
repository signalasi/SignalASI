package com.signalasi.chat

import android.content.Context
import java.util.Base64
import java.util.Locale

/** Default, bounded phone-native tools exposed to the mobile Agent runtime. */
object AgentPhoneNativeToolCatalog {
    const val WORKSPACE_INITIALIZE = "signalasi.workspace.initialize"
    const val WORKSPACE_MKDIR = "signalasi.workspace.directory.create"
    const val WORKSPACE_LIST = "signalasi.workspace.directory.list"
    const val WORKSPACE_STAT = "signalasi.workspace.file.stat"
    const val WORKSPACE_READ_TEXT = "signalasi.workspace.file.read.text"
    const val WORKSPACE_READ_BYTES = "signalasi.workspace.file.read.bytes"
    const val WORKSPACE_WRITE_TEXT = "signalasi.workspace.file.write.text"
    const val WORKSPACE_CREATE_TEXT = "signalasi.workspace.file.create.text"
    const val WORKSPACE_APPEND_TEXT = "signalasi.workspace.file.append.text"
    const val WORKSPACE_WRITE_BYTES = "signalasi.workspace.file.write.bytes"
    const val WORKSPACE_CREATE_BYTES = "signalasi.workspace.file.create.bytes"
    const val WORKSPACE_APPEND_BYTES = "signalasi.workspace.file.append.bytes"
    const val WORKSPACE_MOVE = "signalasi.workspace.entry.move"
    const val WORKSPACE_COPY = "signalasi.workspace.entry.copy"
    const val WORKSPACE_DELETE = "signalasi.workspace.entry.delete"
    const val WORKSPACE_SEARCH_TEXT = "signalasi.workspace.file.search.text"
    const val WORKSPACE_APPLY_EXACT_PATCH = "signalasi.workspace.file.patch.exact"
    const val WORKSPACE_DIFF_SUMMARY = "signalasi.workspace.file.diff.summary"
    const val WORKSPACE_SHA256 = "signalasi.workspace.file.sha256"
    const val WORKSPACE_ZIP_CREATE = "signalasi.workspace.zip.create"
    const val WORKSPACE_ZIP_LIST = "signalasi.workspace.zip.list"
    const val WORKSPACE_ZIP_EXTRACT = "signalasi.workspace.zip.extract"

    const val WORKSPACE_PRIVATE_PERMISSION = "signalasi.scope.app_private_workspace"
    const val WORKSPACE_READ_CONSENT = "signalasi.consent.workspace_read"
    const val WORKSPACE_WRITE_CONSENT = "signalasi.consent.workspace_write"

    private const val VERSION = "1.0.0"
    private const val FILE_EXECUTOR_ID = "signalasi.workspace_file_tools"
    private const val ACTION_EXECUTOR_ID = "signalasi.android_agent_action"
    private const val MAX_PATH_CHARS = 4_096
    private const val MAX_INPUT_PATH_CHARS = 1_024
    private const val MAX_TEXT_BYTES = 1_048_576
    private const val MAX_BINARY_BYTES = 8 * 1_048_576
    private const val MAX_WRITE_BYTES = 16 * 1_048_576
    private const val MAX_BASE64_READ_CHARS = 11_184_812
    private const val MAX_BASE64_WRITE_CHARS = 22_369_624
    private const val MAX_LIST_ENTRIES = 10_000
    private const val MAX_SEARCH_RESULTS = 500
    private const val MAX_ZIP_ENTRIES = 2_048
    private const val MAX_ACTION_PARAMETERS = 32
    private const val MAX_ACTION_MESSAGE_CHARS = 2_048
    private const val MAX_ACTION_METADATA_KEY_CHARS = 128
    private const val MAX_ACTION_METADATA_VALUE_CHARS = 1_024

    private val supportedActionKinds = listOf(
        AgentActionKind.READ_SCREEN,
        AgentActionKind.TAP,
        AgentActionKind.TYPE_TEXT,
        AgentActionKind.SWIPE,
        AgentActionKind.LONG_PRESS,
        AgentActionKind.DELETE_TEXT,
        AgentActionKind.PASTE_TEXT,
        AgentActionKind.COPY_SCREEN_TEXT,
        AgentActionKind.BACK,
        AgentActionKind.HOME,
        AgentActionKind.RECENTS,
        AgentActionKind.LOCK_SCREEN,
        AgentActionKind.OPEN_APP,
        AgentActionKind.OPEN_URL,
        AgentActionKind.SET_ALARM,
        AgentActionKind.REPLY_NOTIFICATION
    )

    val toolIds: Set<String> = linkedSetOf(
        WORKSPACE_INITIALIZE,
        WORKSPACE_MKDIR,
        WORKSPACE_LIST,
        WORKSPACE_STAT,
        WORKSPACE_READ_TEXT,
        WORKSPACE_READ_BYTES,
        WORKSPACE_WRITE_TEXT,
        WORKSPACE_CREATE_TEXT,
        WORKSPACE_APPEND_TEXT,
        WORKSPACE_WRITE_BYTES,
        WORKSPACE_CREATE_BYTES,
        WORKSPACE_APPEND_BYTES,
        WORKSPACE_MOVE,
        WORKSPACE_COPY,
        WORKSPACE_DELETE,
        WORKSPACE_SEARCH_TEXT,
        WORKSPACE_APPLY_EXACT_PATCH,
        WORKSPACE_DIFF_SUMMARY,
        WORKSPACE_SHA256,
        WORKSPACE_ZIP_CREATE,
        WORKSPACE_ZIP_LIST,
        WORKSPACE_ZIP_EXTRACT
    ).apply {
        supportedActionKinds.mapTo(this, AgentNativeToolAgentActionAdapter::defaultToolId)
    }

    fun defaultRegistry(
        context: Context,
        screenProvider: (AgentNativeToolInvocation) -> ScreenContext,
        actionExecutor: AgentActionExecutor = AndroidAgentActionExecutor(context),
        clock: AgentNativeClock = AgentNativeClock.SYSTEM
    ): AgentNativeToolRegistry {
        val registry = createRegistry(
            workspaceFileTools = AgentWorkspaceFileTools(
                context.filesDir.toPath().resolve("agent-native-workspaces")
            ),
            actionExecutor = actionExecutor,
            screenProvider = screenProvider,
            capabilityStatusProvider = { AgentPhoneCapabilityCatalog.probe(context) },
            clock = clock
        )
        return registry.registerAll(
            AgentWebMediaNativeTools.definitions(
                AgentWebMediaNativeTools.androidServices(context.applicationContext, clock = clock)
            )
        ).registerAll(
            AgentHardwareNativeTools.definitions(
                AgentHardwareNativeTools.androidFacade(context.applicationContext, clock)
            )
        )
    }

    fun createDefault(
        context: Context,
        screenProvider: (AgentNativeToolInvocation) -> ScreenContext,
        actionExecutor: AgentActionExecutor = AndroidAgentActionExecutor(context),
        clock: AgentNativeClock = AgentNativeClock.SYSTEM
    ): AgentNativeToolRegistry = defaultRegistry(context, screenProvider, actionExecutor, clock)

    fun createRegistry(
        workspaceFileTools: AgentWorkspaceFileTools,
        actionExecutor: AgentActionExecutor,
        screenProvider: (AgentNativeToolInvocation) -> ScreenContext,
        capabilityStatusProvider: () -> List<AgentPhoneCapabilityStatus> = ::declaredCapabilityStatuses,
        clock: AgentNativeClock = AgentNativeClock.SYSTEM
    ): AgentNativeToolRegistry = AgentNativeToolRegistry(clock).registerAll(
        definitions(
            workspaceFileTools,
            actionExecutor,
            screenProvider,
            capabilityStatusProvider
        )
    )

    fun definitions(
        workspaceFileTools: AgentWorkspaceFileTools,
        actionExecutor: AgentActionExecutor,
        screenProvider: (AgentNativeToolInvocation) -> ScreenContext,
        capabilityStatusProvider: () -> List<AgentPhoneCapabilityStatus> = ::declaredCapabilityStatuses
    ): List<AgentNativeToolDefinition> =
        workspaceDefinitions(workspaceFileTools) + actionDefinitions(
            actionExecutor,
            screenProvider,
            capabilityStatusProvider
        )

    private fun workspaceDefinitions(tools: AgentWorkspaceFileTools): List<AgentNativeToolDefinition> = listOf(
        workspaceDefinition(
            id = WORKSPACE_INITIALIZE,
            title = "Initialize app-private workspace",
            description = "Creates or reopens one isolated Agent workspace under SignalASI app-private storage.",
            risk = AgentNativeToolRisk.LOW,
            consentId = WORKSPACE_WRITE_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT,
            inputSchema = objectSchema(
                properties = mapOf("workspace_id" to workspaceIdSchema()),
                required = setOf("workspace_id")
            ),
            outputSchema = mutationSchema(),
            execute = { input -> tools.initializeWorkspace(input.string("workspace_id")) },
            encode = ::mutationValue
        ),
        workspaceDefinition(
            id = WORKSPACE_MKDIR,
            title = "Create workspace directory",
            description = "Creates a directory without leaving the selected app-private workspace.",
            risk = AgentNativeToolRisk.LOW,
            consentId = WORKSPACE_WRITE_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT,
            inputSchema = objectSchema(
                properties = workspacePathProperties() + ("recursive" to AgentNativeJsonSchema.boolean()),
                required = setOf("workspace_id", "path")
            ),
            outputSchema = mutationSchema(),
            execute = { input ->
                tools.mkdir(
                    input.string("workspace_id"),
                    input.string("path"),
                    input.boolean("recursive", true)
                )
            },
            encode = ::mutationValue
        ),
        workspaceDefinition(
            id = WORKSPACE_LIST,
            title = "List workspace directory",
            description = "Lists a bounded number of files and directories in an app-private workspace.",
            risk = AgentNativeToolRisk.LOW,
            consentId = WORKSPACE_READ_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT,
            inputSchema = objectSchema(
                properties = mapOf(
                    "workspace_id" to workspaceIdSchema(),
                    "path" to pathSchema(),
                    "recursive" to AgentNativeJsonSchema.boolean(),
                    "max_entries" to AgentNativeJsonSchema.integer(1, MAX_LIST_ENTRIES.toLong())
                ),
                required = setOf("workspace_id")
            ),
            outputSchema = directoryListingSchema(),
            execute = { input ->
                tools.list(
                    input.string("workspace_id"),
                    input.string("path", ""),
                    input.boolean("recursive", false),
                    input.integer("max_entries", MAX_LIST_ENTRIES)
                )
            },
            encode = ::directoryListingValue
        ),
        workspaceDefinition(
            id = WORKSPACE_STAT,
            title = "Inspect workspace entry",
            description = "Returns bounded metadata for one app-private workspace entry.",
            risk = AgentNativeToolRisk.LOW,
            consentId = WORKSPACE_READ_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT,
            inputSchema = workspacePathInputSchema(),
            outputSchema = metadataSchema(),
            execute = { input -> tools.stat(input.string("workspace_id"), input.string("path")) },
            encode = ::metadataValue
        ),
        workspaceDefinition(
            id = WORKSPACE_READ_TEXT,
            title = "Read workspace text file",
            description = "Reads a bounded UTF-8 file from an app-private workspace.",
            risk = AgentNativeToolRisk.LOW,
            consentId = WORKSPACE_READ_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT,
            inputSchema = objectSchema(
                properties = workspacePathProperties() +
                    ("max_bytes" to AgentNativeJsonSchema.integer(1, MAX_TEXT_BYTES.toLong())),
                required = setOf("workspace_id", "path")
            ),
            outputSchema = textReadSchema(),
            execute = { input ->
                tools.readText(
                    input.string("workspace_id"),
                    input.string("path"),
                    input.long("max_bytes", MAX_TEXT_BYTES.toLong())
                )
            },
            encode = ::textReadValue
        ),
        workspaceDefinition(
            id = WORKSPACE_READ_BYTES,
            title = "Read workspace binary file",
            description = "Reads bounded bytes from an app-private workspace and returns base64 data.",
            risk = AgentNativeToolRisk.LOW,
            consentId = WORKSPACE_READ_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT,
            inputSchema = objectSchema(
                properties = workspacePathProperties() +
                    ("max_bytes" to AgentNativeJsonSchema.integer(1, MAX_BINARY_BYTES.toLong())),
                required = setOf("workspace_id", "path")
            ),
            outputSchema = bytesReadSchema(),
            execute = { input ->
                tools.readBytes(
                    input.string("workspace_id"),
                    input.string("path"),
                    input.long("max_bytes", MAX_BINARY_BYTES.toLong())
                )
            },
            encode = ::bytesReadValue
        ),
        textMutationDefinition(WORKSPACE_WRITE_TEXT, "Write workspace text file", AgentWorkspaceMutationKind.WRITE) {
                tools, input ->
            tools.writeText(
                input.string("workspace_id"),
                input.string("path"),
                input.string("text"),
                input.boolean("create_parents", false)
            )
        }.withTools(tools),
        textMutationDefinition(WORKSPACE_CREATE_TEXT, "Create workspace text file", AgentWorkspaceMutationKind.CREATE) {
                tools, input ->
            tools.createText(
                input.string("workspace_id"),
                input.string("path"),
                input.string("text"),
                input.boolean("create_parents", false)
            )
        }.withTools(tools),
        textMutationDefinition(WORKSPACE_APPEND_TEXT, "Append workspace text file", AgentWorkspaceMutationKind.APPEND) {
                tools, input ->
            tools.appendText(input.string("workspace_id"), input.string("path"), input.string("text"))
        }.withTools(tools),
        bytesMutationDefinition(WORKSPACE_WRITE_BYTES, "Write workspace binary file", AgentWorkspaceMutationKind.WRITE) {
                tools, input, bytes ->
            tools.write(
                input.string("workspace_id"),
                input.string("path"),
                bytes,
                input.boolean("create_parents", false)
            )
        }.withTools(tools),
        bytesMutationDefinition(WORKSPACE_CREATE_BYTES, "Create workspace binary file", AgentWorkspaceMutationKind.CREATE) {
                tools, input, bytes ->
            tools.create(
                input.string("workspace_id"),
                input.string("path"),
                bytes,
                input.boolean("create_parents", false)
            )
        }.withTools(tools),
        bytesMutationDefinition(WORKSPACE_APPEND_BYTES, "Append workspace binary file", AgentWorkspaceMutationKind.APPEND) {
                tools, input, bytes ->
            tools.append(input.string("workspace_id"), input.string("path"), bytes)
        }.withTools(tools),
        moveCopyDefinition(WORKSPACE_MOVE, "Move workspace entry", AgentWorkspaceMutationKind.MOVE) {
                tools, input ->
            tools.move(
                input.string("workspace_id"),
                input.string("source_path"),
                input.string("destination_path"),
                input.boolean("overwrite", false),
                input.boolean("create_parents", false)
            )
        }.withTools(tools),
        moveCopyDefinition(WORKSPACE_COPY, "Copy workspace entry", AgentWorkspaceMutationKind.COPY) {
                tools, input ->
            tools.copy(
                input.string("workspace_id"),
                input.string("source_path"),
                input.string("destination_path"),
                input.boolean("overwrite", false),
                input.boolean("create_parents", false)
            )
        }.withTools(tools),
        workspaceDefinition(
            id = WORKSPACE_DELETE,
            title = "Delete workspace entry",
            description = "Deletes one bounded app-private workspace entry or tree after explicit write consent.",
            risk = AgentNativeToolRisk.MEDIUM,
            consentId = WORKSPACE_WRITE_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
            inputSchema = objectSchema(
                properties = workspacePathProperties() + ("recursive" to AgentNativeJsonSchema.boolean()),
                required = setOf("workspace_id", "path")
            ),
            outputSchema = mutationSchema(),
            execute = { input ->
                tools.delete(
                    input.string("workspace_id"),
                    input.string("path"),
                    input.boolean("recursive", false)
                )
            },
            encode = ::mutationValue
        ),
        workspaceDefinition(
            id = WORKSPACE_SEARCH_TEXT,
            title = "Search workspace text",
            description = "Searches bounded UTF-8 workspace content and returns capped excerpts.",
            risk = AgentNativeToolRisk.LOW,
            consentId = WORKSPACE_READ_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT,
            inputSchema = objectSchema(
                properties = workspacePathProperties() + mapOf(
                    "query" to AgentNativeJsonSchema.string(minLength = 1, maxLength = 4_096),
                    "case_sensitive" to AgentNativeJsonSchema.boolean(),
                    "max_results" to AgentNativeJsonSchema.integer(1, MAX_SEARCH_RESULTS.toLong())
                ),
                required = setOf("workspace_id", "path", "query")
            ),
            outputSchema = searchSchema(),
            execute = { input ->
                tools.searchText(
                    input.string("workspace_id"),
                    input.string("path"),
                    input.string("query"),
                    input.boolean("case_sensitive", false),
                    input.integer("max_results", MAX_SEARCH_RESULTS)
                )
            },
            encode = ::searchValue
        ),
        workspaceDefinition(
            id = WORKSPACE_APPLY_EXACT_PATCH,
            title = "Apply exact workspace patch",
            description = "Replaces an exact bounded text occurrence in one app-private workspace file.",
            risk = AgentNativeToolRisk.MEDIUM,
            consentId = WORKSPACE_WRITE_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
            inputSchema = objectSchema(
                properties = workspacePathProperties() + mapOf(
                    "expected_text" to AgentNativeJsonSchema.string(minLength = 1, maxLength = MAX_TEXT_BYTES),
                    "replacement_text" to AgentNativeJsonSchema.string(maxLength = MAX_TEXT_BYTES),
                    "expected_occurrences" to AgentNativeJsonSchema.integer(1, 10_000)
                ),
                required = setOf("workspace_id", "path", "expected_text", "replacement_text")
            ),
            outputSchema = patchSchema(),
            execute = { input ->
                tools.applyExactPatch(
                    input.string("workspace_id"),
                    input.string("path"),
                    input.string("expected_text"),
                    input.string("replacement_text"),
                    input.integer("expected_occurrences", 1)
                )
            },
            encode = ::patchValue
        ),
        workspaceDefinition(
            id = WORKSPACE_DIFF_SUMMARY,
            title = "Summarize workspace diff",
            description = "Computes a bounded line summary without mutating the workspace file.",
            risk = AgentNativeToolRisk.LOW,
            consentId = WORKSPACE_READ_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT,
            inputSchema = objectSchema(
                properties = workspacePathProperties() +
                    ("proposed_text" to AgentNativeJsonSchema.string(maxLength = MAX_TEXT_BYTES)),
                required = setOf("workspace_id", "path", "proposed_text")
            ),
            outputSchema = diffSchema(),
            execute = { input ->
                tools.diffSummary(
                    input.string("workspace_id"),
                    input.string("path"),
                    input.string("proposed_text")
                )
            },
            encode = ::diffValue
        ),
        workspaceDefinition(
            id = WORKSPACE_SHA256,
            title = "Hash workspace file",
            description = "Computes a bounded SHA-256 digest for one app-private workspace file.",
            risk = AgentNativeToolRisk.LOW,
            consentId = WORKSPACE_READ_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT,
            inputSchema = workspacePathInputSchema(),
            outputSchema = digestSchema(),
            execute = { input -> tools.sha256(input.string("workspace_id"), input.string("path")) },
            encode = ::digestValue
        ),
        workspaceDefinition(
            id = WORKSPACE_ZIP_CREATE,
            title = "Create workspace ZIP",
            description = "Creates a bounded ZIP archive from app-private workspace entries.",
            risk = AgentNativeToolRisk.MEDIUM,
            consentId = WORKSPACE_WRITE_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
            inputSchema = objectSchema(
                properties = mapOf(
                    "workspace_id" to workspaceIdSchema(),
                    "archive_path" to pathSchema(),
                    "source_paths" to AgentNativeJsonSchema.array(
                        pathSchema(),
                        minItems = 1,
                        maxItems = MAX_ZIP_ENTRIES
                    ),
                    "overwrite" to AgentNativeJsonSchema.boolean(),
                    "create_parents" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf("workspace_id", "archive_path", "source_paths")
            ),
            outputSchema = zipListingSchema(),
            execute = { input ->
                tools.createZip(
                    input.string("workspace_id"),
                    input.string("archive_path"),
                    input.stringList("source_paths"),
                    input.boolean("overwrite", false),
                    input.boolean("create_parents", false)
                )
            },
            encode = ::zipListingValue
        ),
        workspaceDefinition(
            id = WORKSPACE_ZIP_LIST,
            title = "Inspect workspace ZIP",
            description = "Lists bounded metadata for a ZIP archive without extracting it.",
            risk = AgentNativeToolRisk.LOW,
            consentId = WORKSPACE_READ_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT,
            inputSchema = objectSchema(
                properties = mapOf(
                    "workspace_id" to workspaceIdSchema(),
                    "archive_path" to pathSchema()
                ),
                required = setOf("workspace_id", "archive_path")
            ),
            outputSchema = zipListingSchema(),
            execute = { input -> tools.listZip(input.string("workspace_id"), input.string("archive_path")) },
            encode = ::zipListingValue
        ),
        workspaceDefinition(
            id = WORKSPACE_ZIP_EXTRACT,
            title = "Extract workspace ZIP",
            description = "Safely extracts a bounded ZIP into the same app-private workspace.",
            risk = AgentNativeToolRisk.MEDIUM,
            consentId = WORKSPACE_WRITE_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
            inputSchema = objectSchema(
                properties = mapOf(
                    "workspace_id" to workspaceIdSchema(),
                    "archive_path" to pathSchema(),
                    "destination_path" to pathSchema(),
                    "overwrite" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf("workspace_id", "archive_path", "destination_path")
            ),
            outputSchema = zipExtractionSchema(),
            execute = { input ->
                tools.extractZip(
                    input.string("workspace_id"),
                    input.string("archive_path"),
                    input.string("destination_path"),
                    input.boolean("overwrite", false)
                )
            },
            encode = ::zipExtractionValue
        )
    )

    private fun actionDefinitions(
        delegate: AgentActionExecutor,
        screenProvider: (AgentNativeToolInvocation) -> ScreenContext,
        statusProvider: () -> List<AgentPhoneCapabilityStatus>
    ): List<AgentNativeToolDefinition> = supportedActionKinds.map { kind ->
        val capabilityIds = capabilitiesFor(kind)
        val boundaries = capabilityIds.map(AgentPhoneCapabilityCatalog::find)
        val descriptor = AgentNativeToolDescriptor(
            id = AgentNativeToolAgentActionAdapter.defaultToolId(kind),
            version = VERSION,
            title = actionTitle(kind),
            description = actionDescription(kind),
            location = nativeLocation(boundaries),
            inputSchema = actionInputSchema(kind),
            outputSchema = actionOutputSchema(),
            risk = boundaries.maxByOrNull { it.risk.weight }?.risk.toNativeRisk(),
            capabilities = capabilityIds.mapTo(linkedSetOf()) { it.capabilityWireId() },
            requiredPermissions = permissionRequirements(boundaries),
            requiredConsents = consentRequirements(boundaries),
            timeoutMillis = 15_000,
            idempotency = AgentNativeToolIdempotency.NON_IDEMPOTENT,
            availability = capabilityAvailability(capabilityIds, statusProvider())
        )
        val adapted = AgentActionNativeToolExecutor.forKind(delegate, kind, screenProvider)
        AgentNativeToolDefinition(
            descriptor = descriptor,
            executor = AgentNativeToolExecutor { invocation ->
                boundedActionResult(adapted.execute(invocation))
            },
            validator = BoundedActionValidator,
            executorId = ACTION_EXECUTOR_ID,
            provenanceMetadata = mapOf(
                "adapter" to AgentActionNativeToolExecutor::class.java.name,
                "delegate_contract" to AgentActionExecutor::class.java.name,
                "legacy_action_kind" to kind.name,
                "result_policy" to "bounded-v1"
            ),
            availabilityProvider = AgentNativeToolAvailabilityProvider {
                capabilityAvailability(capabilityIds, statusProvider())
            }
        )
    }

    private fun <T> workspaceDefinition(
        id: String,
        title: String,
        description: String,
        risk: AgentNativeToolRisk,
        consentId: String,
        idempotency: AgentNativeToolIdempotency,
        inputSchema: AgentNativeJsonSchema,
        outputSchema: AgentNativeJsonSchema,
        execute: (AgentNativeJsonObject) -> AgentWorkspaceFileResult<T>,
        encode: (T) -> AgentNativeJsonObject
    ): AgentNativeToolDefinition = AgentNativeToolDefinition(
        descriptor = AgentNativeToolDescriptor(
            id = id,
            version = VERSION,
            title = title,
            description = description,
            location = AgentNativeToolLocation.APPLICATION,
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            risk = risk,
            capabilities = setOf("workspace.app_private", "workspace.file.bounded"),
            requiredPermissions = listOf(
                AgentNativePermissionRequirement(
                    id = WORKSPACE_PRIVATE_PERMISSION,
                    title = "App-private workspace scope",
                    description = "Restricts access to SignalASI-owned workspace storage."
                )
            ),
            requiredConsents = listOf(
                AgentNativeConsentRequirement(
                    id = consentId,
                    title = if (consentId == WORKSPACE_READ_CONSENT) {
                        "Read app-private workspace"
                    } else {
                        "Modify app-private workspace"
                    },
                    description = "Authorizes this invocation to access the selected Agent workspace."
                )
            ),
            timeoutMillis = 15_000,
            idempotency = idempotency,
            availability = AgentNativeToolAvailability.AVAILABLE
        ),
        executor = AgentNativeToolExecutor { invocation ->
            invocation.checkpoint()
            when (val result = execute(invocation.input)) {
                is AgentWorkspaceFileResult.Success -> AgentNativeToolExecutionResult.success(
                    output = encode(result.value),
                    message = "Workspace operation completed",
                    metadata = mapOf("storage_scope" to "app_private")
                )
                is AgentWorkspaceFileResult.Failure -> workspaceFailure(result.error)
            }
        },
        executorId = FILE_EXECUTOR_ID,
        provenanceMetadata = mapOf(
            "implementation" to AgentWorkspaceFileTools::class.java.name,
            "storage_scope" to "app_private",
            "path_policy" to "workspace_relative_no_symlinks",
            "result_policy" to "bounded-v1"
        )
    )

    private class DeferredWorkspaceDefinition(
        private val create: (AgentWorkspaceFileTools) -> AgentNativeToolDefinition
    ) {
        fun withTools(tools: AgentWorkspaceFileTools): AgentNativeToolDefinition = create(tools)
    }

    private fun textMutationDefinition(
        id: String,
        title: String,
        kind: AgentWorkspaceMutationKind,
        execute: (AgentWorkspaceFileTools, AgentNativeJsonObject) -> AgentWorkspaceFileResult<AgentWorkspaceMutation>
    ) = DeferredWorkspaceDefinition { tools ->
        workspaceDefinition(
            id = id,
            title = title,
            description = "${kind.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }}s bounded UTF-8 content inside an app-private workspace.",
            risk = AgentNativeToolRisk.MEDIUM,
            consentId = WORKSPACE_WRITE_CONSENT,
            idempotency = if (kind == AgentWorkspaceMutationKind.WRITE) {
                AgentNativeToolIdempotency.IDEMPOTENT
            } else {
                AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED
            },
            inputSchema = objectSchema(
                properties = workspacePathProperties() + mapOf(
                    "text" to AgentNativeJsonSchema.string(maxLength = MAX_WRITE_BYTES),
                    "create_parents" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf("workspace_id", "path", "text")
            ),
            outputSchema = mutationSchema(),
            execute = { input -> execute(tools, input) },
            encode = ::mutationValue
        )
    }

    private fun bytesMutationDefinition(
        id: String,
        title: String,
        kind: AgentWorkspaceMutationKind,
        execute: (
            AgentWorkspaceFileTools,
            AgentNativeJsonObject,
            ByteArray
        ) -> AgentWorkspaceFileResult<AgentWorkspaceMutation>
    ) = DeferredWorkspaceDefinition { tools ->
        workspaceDefinition(
            id = id,
            title = title,
            description = "${kind.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }}s bounded base64-decoded content inside an app-private workspace.",
            risk = AgentNativeToolRisk.MEDIUM,
            consentId = WORKSPACE_WRITE_CONSENT,
            idempotency = if (kind == AgentWorkspaceMutationKind.WRITE) {
                AgentNativeToolIdempotency.IDEMPOTENT
            } else {
                AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED
            },
            inputSchema = objectSchema(
                properties = workspacePathProperties() + mapOf(
                    "base64" to AgentNativeJsonSchema.string(maxLength = MAX_BASE64_WRITE_CHARS),
                    "create_parents" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf("workspace_id", "path", "base64")
            ),
            outputSchema = mutationSchema(),
            execute = { input ->
                val bytes = try {
                    Base64.getDecoder().decode(input.string("base64"))
                } catch (error: IllegalArgumentException) {
                    return@workspaceDefinition AgentWorkspaceFileResult.Failure(
                        AgentWorkspaceFileError(
                            AgentWorkspaceFileErrorCode.INVALID_TEXT,
                            "decode_base64",
                            input.string("path"),
                            "Binary input is not valid base64"
                        )
                    )
                }
                if (bytes.size > MAX_WRITE_BYTES) {
                    AgentWorkspaceFileResult.Failure(
                        AgentWorkspaceFileError(
                            AgentWorkspaceFileErrorCode.LIMIT_EXCEEDED,
                            "decode_base64",
                            input.string("path"),
                            "Decoded binary input exceeds $MAX_WRITE_BYTES bytes"
                        )
                    )
                } else {
                    execute(tools, input, bytes)
                }
            },
            encode = ::mutationValue
        )
    }

    private fun moveCopyDefinition(
        id: String,
        title: String,
        kind: AgentWorkspaceMutationKind,
        execute: (AgentWorkspaceFileTools, AgentNativeJsonObject) -> AgentWorkspaceFileResult<AgentWorkspaceMutation>
    ) = DeferredWorkspaceDefinition { tools ->
        workspaceDefinition(
            id = id,
            title = title,
            description = "${kind.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }}s a bounded entry within one app-private workspace.",
            risk = AgentNativeToolRisk.MEDIUM,
            consentId = WORKSPACE_WRITE_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
            inputSchema = objectSchema(
                properties = mapOf(
                    "workspace_id" to workspaceIdSchema(),
                    "source_path" to pathSchema(),
                    "destination_path" to pathSchema(),
                    "overwrite" to AgentNativeJsonSchema.boolean(),
                    "create_parents" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf("workspace_id", "source_path", "destination_path")
            ),
            outputSchema = mutationSchema(),
            execute = { input -> execute(tools, input) },
            encode = ::mutationValue
        )
    }

    private fun workspaceFailure(error: AgentWorkspaceFileError): AgentNativeToolExecutionResult =
        AgentNativeToolExecutionResult.failure(
            code = "workspace_${error.code.name.lowercase(Locale.ROOT)}",
            message = error.message.take(MAX_ACTION_MESSAGE_CHARS),
            retryable = error.code == AgentWorkspaceFileErrorCode.IO_ERROR,
            details = mapOf(
                "operation" to error.operation.take(64),
                "path" to error.path.take(MAX_PATH_CHARS),
                "workspace_error" to error.code.name.lowercase(Locale.ROOT)
            )
        )

    private fun boundedActionResult(result: AgentNativeToolExecutionResult): AgentNativeToolExecutionResult {
        val outputMetadata = (result.output["metadata"] as? Map<*, *>)
            .orEmpty()
            .entries
            .asSequence()
            .mapNotNull { (key, value) -> (key as? String)?.let { it to value?.toString().orEmpty() } }
            .sortedBy { it.first }
            .take(MAX_ACTION_PARAMETERS)
            .associate { (key, value) ->
                key.take(MAX_ACTION_METADATA_KEY_CHARS) to value.take(MAX_ACTION_METADATA_VALUE_CHARS)
            }
        val output = linkedMapOf<String, Any?>(
            "action_id" to result.output["action_id"]?.toString().orEmpty().take(128),
            "success" to (result.output["success"] as? Boolean ?: result.isSuccess),
            "message" to result.output["message"]?.toString().orEmpty().take(MAX_ACTION_MESSAGE_CHARS),
            "metadata" to outputMetadata
        )
        val boundedError = result.error?.copy(
            code = result.error.code.take(128),
            message = result.error.message.take(MAX_ACTION_MESSAGE_CHARS),
            details = result.error.details.entries.take(MAX_ACTION_PARAMETERS).associate { (key, value) ->
                key.take(MAX_ACTION_METADATA_KEY_CHARS) to value.toString().take(MAX_ACTION_METADATA_VALUE_CHARS)
            }
        )
        return AgentNativeToolExecutionResult(
            output = output,
            message = result.message.take(MAX_ACTION_MESSAGE_CHARS),
            metadata = result.metadata.entries.take(MAX_ACTION_PARAMETERS).associate { (key, value) ->
                key.take(MAX_ACTION_METADATA_KEY_CHARS) to value.toString().take(MAX_ACTION_METADATA_VALUE_CHARS)
            },
            error = boundedError
        )
    }

    private object BoundedActionValidator : AgentNativeToolValidator {
        override fun validate(schema: AgentNativeJsonSchema, value: Any?): AgentNativeValidationResult {
            val base = AgentNativeJsonSchemaValidator.validate(schema, value)
            if (!base.isValid) return base
            val input = value as? Map<*, *> ?: return base
            val parameters = input["parameters"] as? Map<*, *> ?: return base
            if (parameters.size <= MAX_ACTION_PARAMETERS) return base
            return AgentNativeValidationResult(
                base.issues + AgentNativeValidationIssue(
                    "$.parameters",
                    "max_properties",
                    "Expected at most $MAX_ACTION_PARAMETERS action parameters"
                )
            )
        }
    }

    private fun capabilitiesFor(kind: AgentActionKind): Set<AgentPhoneCapabilityId> = when (kind) {
        AgentActionKind.READ_SCREEN -> setOf(AgentPhoneCapabilityId.ACCESSIBILITY_UI_TREE)
        AgentActionKind.COPY_SCREEN_TEXT -> setOf(
            AgentPhoneCapabilityId.ACCESSIBILITY_UI_TREE,
            AgentPhoneCapabilityId.CLIPBOARD
        )
        AgentActionKind.PASTE_TEXT -> setOf(
            AgentPhoneCapabilityId.ACCESSIBILITY_GESTURES,
            AgentPhoneCapabilityId.CLIPBOARD
        )
        AgentActionKind.TAP,
        AgentActionKind.TYPE_TEXT,
        AgentActionKind.SWIPE,
        AgentActionKind.LONG_PRESS,
        AgentActionKind.DELETE_TEXT,
        AgentActionKind.BACK,
        AgentActionKind.HOME,
        AgentActionKind.RECENTS,
        AgentActionKind.LOCK_SCREEN -> setOf(AgentPhoneCapabilityId.ACCESSIBILITY_GESTURES)
        AgentActionKind.OPEN_APP,
        AgentActionKind.OPEN_URL,
        AgentActionKind.SET_ALARM -> setOf(AgentPhoneCapabilityId.INTENT_LAUNCH)
        AgentActionKind.REPLY_NOTIFICATION -> setOf(AgentPhoneCapabilityId.NOTIFICATION_REPLY)
        else -> error("Unsupported phone-native action kind: $kind")
    }

    private fun permissionRequirements(
        boundaries: List<AgentPhoneCapabilityBoundary>
    ): List<AgentNativePermissionRequirement> {
        val requirements = linkedMapOf<String, AgentNativePermissionRequirement>()
        boundaries.forEach { boundary ->
            boundary.androidPermissions.sorted().forEach { permission ->
                requirements[permission] = AgentNativePermissionRequirement(
                    id = permission,
                    title = permission.substringAfterLast('.').replace('_', ' '),
                    description = "Android permission required by ${boundary.id.capabilityWireId()}."
                )
            }
            boundary.specialAccess.sortedBy { it.name }.forEach { access ->
                val id = "android.special_access.${access.name.lowercase(Locale.ROOT)}"
                requirements[id] = AgentNativePermissionRequirement(
                    id = id,
                    title = access.name.replace('_', ' ').lowercase(Locale.ROOT),
                    description = "Android special access required by ${boundary.id.capabilityWireId()}."
                )
            }
        }
        if (requirements.isEmpty()) {
            requirements["android.permission.NORMAL_APP_EXECUTION"] = AgentNativePermissionRequirement(
                id = "android.permission.NORMAL_APP_EXECUTION",
                title = "Normal app execution",
                description = "No Android runtime or special-access grant is required.",
                required = false
            )
        }
        return requirements.values.toList()
    }

    private fun consentRequirements(
        boundaries: List<AgentPhoneCapabilityBoundary>
    ): List<AgentNativeConsentRequirement> {
        val consents = boundaries.flatMap { it.userConsent }.distinct().sortedBy { it.name }
        if (consents == listOf(AgentPhoneUserConsent.NONE) || consents.isEmpty()) {
            return listOf(
                AgentNativeConsentRequirement(
                    id = "signalasi.consent.none",
                    title = "No additional consent",
                    description = "This capability has no additional interactive consent requirement.",
                    required = false
                )
            )
        }
        return consents.filter { it != AgentPhoneUserConsent.NONE }.map { consent ->
            AgentNativeConsentRequirement(
                id = "signalasi.consent.${consent.name.lowercase(Locale.ROOT)}",
                title = consent.name.replace('_', ' ').lowercase(Locale.ROOT),
                description = "User consent required by the phone capability boundary."
            )
        }
    }

    private fun capabilityAvailability(
        ids: Set<AgentPhoneCapabilityId>,
        statuses: List<AgentPhoneCapabilityStatus>
    ): AgentNativeToolAvailability {
        val byId = statuses.associateBy { it.boundary.id }
        val resolved = ids.map { id ->
            byId[id] ?: AgentPhoneCapabilityStatus(
                boundary = AgentPhoneCapabilityCatalog.find(id),
                availability = AgentPhoneCapabilityAvailability.UNKNOWN,
                evidence = "Capability status was not provided"
            )
        }
        val unavailable = resolved.firstOrNull { it.availability.toNativeAvailabilityStatus() == AgentNativeToolAvailabilityStatus.UNAVAILABLE }
        val setup = resolved.firstOrNull { it.availability.toNativeAvailabilityStatus() == AgentNativeToolAvailabilityStatus.REQUIRES_SETUP }
        val selected = unavailable ?: setup
        return if (selected == null) {
            AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.AVAILABLE,
                resolved.filter { it.availability == AgentPhoneCapabilityAvailability.LIMITED }
                    .joinToString("; ") { it.boundary.limitation }
                    .take(MAX_ACTION_MESSAGE_CHARS)
            )
        } else {
            AgentNativeToolAvailability(
                selected.availability.toNativeAvailabilityStatus(),
                selected.evidence.ifBlank { selected.boundary.limitation }.take(MAX_ACTION_MESSAGE_CHARS)
            )
        }
    }

    private fun declaredCapabilityStatuses(): List<AgentPhoneCapabilityStatus> =
        AgentPhoneCapabilityCatalog.capabilities.map { boundary ->
            AgentPhoneCapabilityStatus(
                boundary = boundary,
                availability = boundary.availability,
                evidence = boundary.limitation
            )
        }

    private fun nativeLocation(boundaries: List<AgentPhoneCapabilityBoundary>): AgentNativeToolLocation = when {
        boundaries.any { it.executionLocation == AgentPhoneExecutionLocation.ACCESSIBILITY_SERVICE } ->
            AgentNativeToolLocation.ACCESSIBILITY_SERVICE
        boundaries.any {
            it.executionLocation == AgentPhoneExecutionLocation.ANDROID_SYSTEM_SERVICE ||
                it.executionLocation == AgentPhoneExecutionLocation.SYSTEM_UI_HANDOFF ||
                it.executionLocation == AgentPhoneExecutionLocation.NOTIFICATION_LISTENER_SERVICE ||
                it.executionLocation == AgentPhoneExecutionLocation.SCREEN_CAPTURE_SERVICE
        } -> AgentNativeToolLocation.ANDROID_SYSTEM
        boundaries.all { it.executionLocation == AgentPhoneExecutionLocation.APP_PROCESS } ->
            AgentNativeToolLocation.APPLICATION
        else -> AgentNativeToolLocation.PHONE
    }

    private fun actionTitle(kind: AgentActionKind): String =
        kind.name.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }

    private fun actionDescription(kind: AgentActionKind): String = when (kind) {
        AgentActionKind.READ_SCREEN -> "Reads the current bounded screen context through the existing Android Agent executor."
        AgentActionKind.TAP -> "Taps one bounded accessibility target through the existing Android Agent executor."
        AgentActionKind.TYPE_TEXT -> "Types bounded text into the selected accessibility field."
        AgentActionKind.SWIPE -> "Performs one bounded accessibility swipe gesture."
        AgentActionKind.LONG_PRESS -> "Long-presses one bounded accessibility target."
        AgentActionKind.DELETE_TEXT -> "Clears text in the selected accessibility field."
        AgentActionKind.PASTE_TEXT -> "Pastes clipboard text into the selected accessibility field."
        AgentActionKind.COPY_SCREEN_TEXT -> "Copies bounded visible screen text through the existing Android Agent executor."
        AgentActionKind.BACK -> "Requests the Android accessibility Back global action."
        AgentActionKind.HOME -> "Requests the Android accessibility Home global action."
        AgentActionKind.RECENTS -> "Requests the Android accessibility Recents global action."
        AgentActionKind.LOCK_SCREEN -> "Requests the Android accessibility lock-screen global action."
        AgentActionKind.OPEN_APP -> "Hands an explicit bounded app or intent request to Android."
        AgentActionKind.OPEN_URL -> "Hands a bounded URL to an Android activity."
        AgentActionKind.SET_ALARM -> "Hands a bounded alarm or timer request to Android."
        AgentActionKind.REPLY_NOTIFICATION -> "Replies to one reply-capable notification through the existing listener service."
        else -> error("Unsupported phone-native action kind: $kind")
    }

    private fun actionInputSchema(kind: AgentActionKind): AgentNativeJsonSchema = objectSchema(
        properties = mapOf(
            "target" to AgentNativeJsonSchema.string(maxLength = 512),
            "description" to AgentNativeJsonSchema.string(maxLength = 2_048),
            "parameters" to actionParametersSchema(kind),
            "requires_confirmation" to AgentNativeJsonSchema.boolean()
        ),
        required = setOf("target", "parameters")
    )

    private fun actionParametersSchema(kind: AgentActionKind): AgentNativeJsonSchema {
        val short = AgentNativeJsonSchema.string(maxLength = 512)
        val bounds = AgentNativeJsonSchema.string(minLength = 1, maxLength = 128)
        val coordinate = AgentNativeJsonSchema.string(minLength = 1, maxLength = 12, pattern = "^-?[0-9]+$")
        val properties: Map<String, AgentNativeJsonSchema>
        val required: Set<String>
        when (kind) {
            AgentActionKind.TAP, AgentActionKind.LONG_PRESS -> {
                properties = mapOf("bounds" to bounds)
                required = setOf("bounds")
            }
            AgentActionKind.TYPE_TEXT -> {
                properties = mapOf(
                    "text" to AgentNativeJsonSchema.string(minLength = 1, maxLength = 16_384),
                    "field_bounds" to AgentNativeJsonSchema.string(maxLength = 128),
                    "field_origin" to AgentNativeJsonSchema.string(maxLength = 64)
                )
                required = setOf("text")
            }
            AgentActionKind.SWIPE -> {
                properties = mapOf(
                    "from_x" to coordinate,
                    "from_y" to coordinate,
                    "to_x" to coordinate,
                    "to_y" to coordinate
                )
                required = properties.keys
            }
            AgentActionKind.DELETE_TEXT, AgentActionKind.PASTE_TEXT -> {
                properties = mapOf("field_bounds" to AgentNativeJsonSchema.string(maxLength = 128))
                required = emptySet()
            }
            AgentActionKind.OPEN_APP -> {
                properties = mapOf(
                    "intent_action" to short,
                    "package" to AgentNativeJsonSchema.string(maxLength = 255),
                    "uri" to AgentNativeJsonSchema.string(maxLength = 2_048),
                    "type" to AgentNativeJsonSchema.string(maxLength = 255),
                    "category" to short,
                    "extra_text" to AgentNativeJsonSchema.string(maxLength = 16_384),
                    "title" to short,
                    "calendar_title" to short,
                    "contact_name" to short,
                    "sms_body" to AgentNativeJsonSchema.string(maxLength = 4_096)
                )
                required = emptySet()
            }
            AgentActionKind.OPEN_URL -> {
                properties = mapOf("url" to AgentNativeJsonSchema.string(minLength = 1, maxLength = 2_048))
                required = setOf("url")
            }
            AgentActionKind.SET_ALARM -> {
                properties = mapOf(
                    "timer_seconds" to coordinate,
                    "hour" to coordinate,
                    "minute" to coordinate
                )
                required = emptySet()
            }
            AgentActionKind.REPLY_NOTIFICATION -> {
                properties = mapOf(
                    "notification_key" to AgentNativeJsonSchema.string(minLength = 1, maxLength = 1_024),
                    "reply_text" to AgentNativeJsonSchema.string(minLength = 1, maxLength = 16_384),
                    "notification_package" to AgentNativeJsonSchema.string(maxLength = 255)
                )
                required = setOf("notification_key", "reply_text")
            }
            else -> {
                properties = emptyMap()
                required = emptySet()
            }
        }
        return objectSchema(properties, required)
    }

    private fun objectSchema(
        properties: Map<String, AgentNativeJsonSchema> = emptyMap(),
        required: Set<String> = emptySet()
    ): AgentNativeJsonSchema = AgentNativeJsonSchema.objectSchema(
        properties = properties,
        required = required,
        additionalProperties = false
    )

    private fun workspaceIdSchema() = AgentNativeJsonSchema.string(
        minLength = 1,
        maxLength = 64,
        pattern = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"
    )

    private fun pathSchema() = AgentNativeJsonSchema.string(maxLength = MAX_INPUT_PATH_CHARS)

    private fun outputPathSchema() = AgentNativeJsonSchema.string(maxLength = MAX_PATH_CHARS)

    private fun workspacePathProperties() = mapOf(
        "workspace_id" to workspaceIdSchema(),
        "path" to pathSchema()
    )

    private fun workspacePathInputSchema() = objectSchema(
        properties = workspacePathProperties(),
        required = setOf("workspace_id", "path")
    )

    private fun metadataSchema() = objectSchema(
        properties = mapOf(
            "path" to outputPathSchema(),
            "type" to AgentNativeJsonSchema.string(enumValues = listOf("file", "directory")),
            "size_bytes" to AgentNativeJsonSchema.integer(minimum = 0),
            "last_modified_epoch_ms" to AgentNativeJsonSchema.integer(minimum = 0)
        ),
        required = setOf("path", "type", "size_bytes", "last_modified_epoch_ms")
    )

    private fun mutationSchema() = objectSchema(
        properties = mapOf(
            "kind" to AgentNativeJsonSchema.string(
                enumValues = AgentWorkspaceMutationKind.entries.map { it.name.lowercase(Locale.ROOT) }
            ),
            "path" to outputPathSchema(),
            "source_path" to outputPathSchema(),
            "affected_entries" to AgentNativeJsonSchema.integer(minimum = 0, maximum = 20_000),
            "affected_bytes" to AgentNativeJsonSchema.integer(minimum = 0),
            "metadata" to metadataSchema()
        ),
        required = setOf("kind", "path", "source_path", "affected_entries", "affected_bytes")
    )

    private fun directoryListingSchema() = objectSchema(
        properties = mapOf(
            "path" to outputPathSchema(),
            "recursive" to AgentNativeJsonSchema.boolean(),
            "entries" to AgentNativeJsonSchema.array(metadataSchema(), maxItems = MAX_LIST_ENTRIES)
        ),
        required = setOf("path", "recursive", "entries")
    )

    private fun textReadSchema() = objectSchema(
        properties = mapOf(
            "path" to outputPathSchema(),
            "text" to AgentNativeJsonSchema.string(maxLength = MAX_TEXT_BYTES),
            "size_bytes" to AgentNativeJsonSchema.integer(0, MAX_TEXT_BYTES.toLong()),
            "sha256" to sha256Schema()
        ),
        required = setOf("path", "text", "size_bytes", "sha256")
    )

    private fun bytesReadSchema() = objectSchema(
        properties = mapOf(
            "path" to outputPathSchema(),
            "base64" to AgentNativeJsonSchema.string(maxLength = MAX_BASE64_READ_CHARS),
            "metadata" to metadataSchema(),
            "sha256" to sha256Schema()
        ),
        required = setOf("path", "base64", "metadata", "sha256")
    )

    private fun searchSchema() = objectSchema(
        properties = mapOf(
            "query" to AgentNativeJsonSchema.string(maxLength = 4_096),
            "matches" to AgentNativeJsonSchema.array(
                objectSchema(
                    properties = mapOf(
                        "path" to outputPathSchema(),
                        "line" to AgentNativeJsonSchema.integer(minimum = 1),
                        "column" to AgentNativeJsonSchema.integer(minimum = 1),
                        "excerpt" to AgentNativeJsonSchema.string(maxLength = 512)
                    ),
                    required = setOf("path", "line", "column", "excerpt")
                ),
                maxItems = MAX_SEARCH_RESULTS
            ),
            "scanned_files" to AgentNativeJsonSchema.integer(minimum = 0, maximum = 20_000),
            "skipped_files" to AgentNativeJsonSchema.integer(minimum = 0, maximum = 20_000),
            "scanned_bytes" to AgentNativeJsonSchema.integer(minimum = 0),
            "truncated" to AgentNativeJsonSchema.boolean()
        ),
        required = setOf(
            "query",
            "matches",
            "scanned_files",
            "skipped_files",
            "scanned_bytes",
            "truncated"
        )
    )

    private fun diffSchema() = objectSchema(
        properties = mapOf(
            "before_sha256" to sha256Schema(),
            "after_sha256" to sha256Schema(),
            "before_bytes" to AgentNativeJsonSchema.integer(minimum = 0),
            "after_bytes" to AgentNativeJsonSchema.integer(minimum = 0),
            "before_lines" to AgentNativeJsonSchema.integer(minimum = 0),
            "after_lines" to AgentNativeJsonSchema.integer(minimum = 0),
            "added_lines" to AgentNativeJsonSchema.integer(minimum = 0),
            "deleted_lines" to AgentNativeJsonSchema.integer(minimum = 0),
            "changed_line_pairs" to AgentNativeJsonSchema.integer(minimum = 0),
            "first_changed_line" to AgentNativeJsonSchema.integer(minimum = 1)
        ),
        required = setOf(
            "before_sha256",
            "after_sha256",
            "before_bytes",
            "after_bytes",
            "before_lines",
            "after_lines",
            "added_lines",
            "deleted_lines",
            "changed_line_pairs"
        )
    )

    private fun patchSchema() = objectSchema(
        properties = mapOf(
            "path" to outputPathSchema(),
            "replacements" to AgentNativeJsonSchema.integer(minimum = 1, maximum = 10_000),
            "diff" to diffSchema(),
            "metadata" to metadataSchema()
        ),
        required = setOf("path", "replacements", "diff", "metadata")
    )

    private fun digestSchema() = objectSchema(
        properties = mapOf(
            "path" to outputPathSchema(),
            "algorithm" to AgentNativeJsonSchema.string(enumValues = listOf("SHA-256")),
            "hex" to sha256Schema(),
            "size_bytes" to AgentNativeJsonSchema.integer(minimum = 0)
        ),
        required = setOf("path", "algorithm", "hex", "size_bytes")
    )

    private fun zipEntrySchema() = objectSchema(
        properties = mapOf(
            "path" to AgentNativeJsonSchema.string(maxLength = 512),
            "directory" to AgentNativeJsonSchema.boolean(),
            "compressed_bytes" to AgentNativeJsonSchema.integer(minimum = 0),
            "uncompressed_bytes" to AgentNativeJsonSchema.integer(minimum = 0),
            "compression_ratio" to AgentNativeJsonSchema.number(minimum = 0),
            "crc32" to AgentNativeJsonSchema.integer(minimum = 0),
            "last_modified_epoch_ms" to AgentNativeJsonSchema.integer(minimum = 0)
        ),
        required = setOf(
            "path",
            "directory",
            "compressed_bytes",
            "uncompressed_bytes",
            "compression_ratio",
            "crc32",
            "last_modified_epoch_ms"
        )
    )

    private fun zipListingSchema() = objectSchema(
        properties = mapOf(
            "archive_path" to outputPathSchema(),
            "archive_bytes" to AgentNativeJsonSchema.integer(minimum = 0),
            "total_compressed_bytes" to AgentNativeJsonSchema.integer(minimum = 0),
            "total_uncompressed_bytes" to AgentNativeJsonSchema.integer(minimum = 0),
            "entries" to AgentNativeJsonSchema.array(zipEntrySchema(), maxItems = MAX_ZIP_ENTRIES)
        ),
        required = setOf(
            "archive_path",
            "archive_bytes",
            "total_compressed_bytes",
            "total_uncompressed_bytes",
            "entries"
        )
    )

    private fun zipExtractionSchema() = objectSchema(
        properties = mapOf(
            "archive_path" to outputPathSchema(),
            "destination_path" to outputPathSchema(),
            "extracted_entries" to AgentNativeJsonSchema.integer(0, MAX_ZIP_ENTRIES.toLong()),
            "extracted_bytes" to AgentNativeJsonSchema.integer(minimum = 0)
        ),
        required = setOf("archive_path", "destination_path", "extracted_entries", "extracted_bytes")
    )

    private fun actionOutputSchema() = objectSchema(
        properties = mapOf(
            "action_id" to AgentNativeJsonSchema.string(maxLength = 128),
            "success" to AgentNativeJsonSchema.boolean(),
            "message" to AgentNativeJsonSchema.string(maxLength = MAX_ACTION_MESSAGE_CHARS),
            "metadata" to AgentNativeJsonSchema(
                mapOf(
                    "type" to "object",
                    "additionalProperties" to AgentNativeJsonSchema.string(
                        maxLength = MAX_ACTION_METADATA_VALUE_CHARS
                    ).document,
                    "maxProperties" to MAX_ACTION_PARAMETERS
                )
            )
        ),
        required = setOf("action_id", "success", "message", "metadata")
    )

    private fun sha256Schema() = AgentNativeJsonSchema.string(
        minLength = 64,
        maxLength = 64,
        pattern = "^[0-9a-f]{64}$"
    )

    private fun mutationValue(value: AgentWorkspaceMutation): AgentNativeJsonObject = linkedMapOf<String, Any?>(
        "kind" to value.kind.name.lowercase(Locale.ROOT),
        "path" to value.path.take(MAX_PATH_CHARS),
        "source_path" to value.sourcePath.take(MAX_PATH_CHARS),
        "affected_entries" to value.affectedEntries,
        "affected_bytes" to value.affectedBytes
    ).apply {
        value.metadata?.let { put("metadata", metadataValue(it)) }
    }

    private fun metadataValue(value: AgentWorkspaceFileMetadata): AgentNativeJsonObject = linkedMapOf(
        "path" to value.path.take(MAX_PATH_CHARS),
        "type" to value.type.name.lowercase(Locale.ROOT),
        "size_bytes" to value.sizeBytes,
        "last_modified_epoch_ms" to value.lastModifiedMillis.coerceAtLeast(0)
    )

    private fun directoryListingValue(value: AgentWorkspaceDirectoryListing): AgentNativeJsonObject = linkedMapOf(
        "path" to value.path.take(MAX_PATH_CHARS),
        "recursive" to value.recursive,
        "entries" to value.entries.take(MAX_LIST_ENTRIES).map(::metadataValue)
    )

    private fun textReadValue(value: AgentWorkspaceTextRead): AgentNativeJsonObject = linkedMapOf(
        "path" to value.path.take(MAX_PATH_CHARS),
        "text" to value.text.take(MAX_TEXT_BYTES),
        "size_bytes" to value.sizeBytes,
        "sha256" to value.sha256
    )

    private fun bytesReadValue(value: AgentWorkspaceBytesRead): AgentNativeJsonObject = linkedMapOf(
        "path" to value.path.take(MAX_PATH_CHARS),
        "base64" to Base64.getEncoder().encodeToString(value.bytes).take(MAX_BASE64_READ_CHARS),
        "metadata" to metadataValue(value.metadata),
        "sha256" to value.sha256
    )

    private fun searchValue(value: AgentWorkspaceTextSearchResult): AgentNativeJsonObject = linkedMapOf(
        "query" to value.query.take(4_096),
        "matches" to value.matches.take(MAX_SEARCH_RESULTS).map { match ->
            linkedMapOf(
                "path" to match.path.take(MAX_PATH_CHARS),
                "line" to match.line,
                "column" to match.column,
                "excerpt" to match.excerpt.take(512)
            )
        },
        "scanned_files" to value.scannedFiles,
        "skipped_files" to value.skippedFiles,
        "scanned_bytes" to value.scannedBytes,
        "truncated" to (value.truncated || value.matches.size > MAX_SEARCH_RESULTS)
    )

    private fun diffValue(value: AgentWorkspaceDiffSummary): AgentNativeJsonObject = linkedMapOf<String, Any?>(
        "before_sha256" to value.beforeSha256,
        "after_sha256" to value.afterSha256,
        "before_bytes" to value.beforeBytes,
        "after_bytes" to value.afterBytes,
        "before_lines" to value.beforeLines,
        "after_lines" to value.afterLines,
        "added_lines" to value.addedLines,
        "deleted_lines" to value.deletedLines,
        "changed_line_pairs" to value.changedLinePairs
    ).apply {
        value.firstChangedLine?.let { put("first_changed_line", it) }
    }

    private fun patchValue(value: AgentWorkspacePatchResult): AgentNativeJsonObject = linkedMapOf(
        "path" to value.path.take(MAX_PATH_CHARS),
        "replacements" to value.replacements,
        "diff" to diffValue(value.diff),
        "metadata" to metadataValue(value.metadata)
    )

    private fun digestValue(value: AgentWorkspaceDigest): AgentNativeJsonObject = linkedMapOf(
        "path" to value.path.take(MAX_PATH_CHARS),
        "algorithm" to value.algorithm,
        "hex" to value.hex,
        "size_bytes" to value.sizeBytes
    )

    private fun zipListingValue(value: AgentWorkspaceZipListing): AgentNativeJsonObject = linkedMapOf(
        "archive_path" to value.archivePath.take(MAX_PATH_CHARS),
        "archive_bytes" to value.archiveBytes,
        "total_compressed_bytes" to value.totalCompressedBytes,
        "total_uncompressed_bytes" to value.totalUncompressedBytes,
        "entries" to value.entries.take(MAX_ZIP_ENTRIES).map { entry ->
            linkedMapOf(
                "path" to entry.path.take(512),
                "directory" to entry.directory,
                "compressed_bytes" to entry.compressedBytes,
                "uncompressed_bytes" to entry.uncompressedBytes,
                "compression_ratio" to entry.compressionRatio,
                "crc32" to entry.crc32.coerceAtLeast(0),
                "last_modified_epoch_ms" to entry.lastModifiedMillis.coerceAtLeast(0)
            )
        }
    )

    private fun zipExtractionValue(value: AgentWorkspaceZipExtraction): AgentNativeJsonObject = linkedMapOf(
        "archive_path" to value.archivePath.take(MAX_PATH_CHARS),
        "destination_path" to value.destinationPath.take(MAX_PATH_CHARS),
        "extracted_entries" to value.extractedEntries,
        "extracted_bytes" to value.extractedBytes
    )

    private fun AgentNativeJsonObject.string(name: String, default: String? = null): String =
        this[name] as? String ?: default ?: error("Missing validated string input: $name")

    private fun AgentNativeJsonObject.boolean(name: String, default: Boolean): Boolean =
        this[name] as? Boolean ?: default

    private fun AgentNativeJsonObject.integer(name: String, default: Int): Int =
        (this[name] as? Number)?.toInt() ?: default

    private fun AgentNativeJsonObject.long(name: String, default: Long): Long =
        (this[name] as? Number)?.toLong() ?: default

    private fun AgentNativeJsonObject.stringList(name: String): List<String> =
        (this[name] as? Iterable<*>)?.map { it as String } ?: error("Missing validated string array: $name")

    private fun AgentPhoneCapabilityId.capabilityWireId(): String =
        "phone.${name.lowercase(Locale.ROOT).replace('_', '.')}"

    private fun AgentRisk?.toNativeRisk(): AgentNativeToolRisk = when (this) {
        AgentRisk.LOW -> AgentNativeToolRisk.LOW
        AgentRisk.MEDIUM -> AgentNativeToolRisk.MEDIUM
        AgentRisk.HIGH -> AgentNativeToolRisk.HIGH
        AgentRisk.BLOCKED -> AgentNativeToolRisk.BLOCKED
        null -> AgentNativeToolRisk.BLOCKED
    }

    private fun AgentPhoneCapabilityAvailability.toNativeAvailabilityStatus(): AgentNativeToolAvailabilityStatus =
        when (this) {
            AgentPhoneCapabilityAvailability.READY,
            AgentPhoneCapabilityAvailability.LIMITED -> AgentNativeToolAvailabilityStatus.AVAILABLE
            AgentPhoneCapabilityAvailability.NEEDS_RUNTIME_PERMISSION,
            AgentPhoneCapabilityAvailability.NEEDS_SPECIAL_ACCESS,
            AgentPhoneCapabilityAvailability.NEEDS_USER_CONSENT,
            AgentPhoneCapabilityAvailability.NEEDS_CONFIGURATION ->
                AgentNativeToolAvailabilityStatus.REQUIRES_SETUP
            AgentPhoneCapabilityAvailability.NOT_IMPLEMENTED,
            AgentPhoneCapabilityAvailability.PRIVILEGED_ONLY,
            AgentPhoneCapabilityAvailability.UNSUPPORTED,
            AgentPhoneCapabilityAvailability.BLOCKED_BY_POLICY,
            AgentPhoneCapabilityAvailability.UNKNOWN -> AgentNativeToolAvailabilityStatus.UNAVAILABLE
        }
}
