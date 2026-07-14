package com.signalasi.chat

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentPhoneNativeToolCatalogTest {
    private lateinit var storageRoot: Path
    private lateinit var fileTools: AgentWorkspaceFileTools

    @Before
    fun setUp() {
        storageRoot = Files.createTempDirectory("agent-phone-native-catalog-")
        fileTools = AgentWorkspaceFileTools(storageRoot)
    }

    @After
    fun tearDown() {
        if (!::storageRoot.isInitialized || !Files.exists(storageRoot)) return
        Files.walk(storageRoot).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun registersTheStableDefaultCatalogIds() {
        val registry = registry()
        val expected = setOf(
            "signalasi.workspace.initialize",
            "signalasi.workspace.directory.create",
            "signalasi.workspace.directory.list",
            "signalasi.workspace.file.stat",
            "signalasi.workspace.file.read.text",
            "signalasi.workspace.file.read.bytes",
            "signalasi.workspace.file.write.text",
            "signalasi.workspace.file.create.text",
            "signalasi.workspace.file.append.text",
            "signalasi.workspace.file.write.bytes",
            "signalasi.workspace.file.create.bytes",
            "signalasi.workspace.file.append.bytes",
            "signalasi.workspace.entry.move",
            "signalasi.workspace.entry.copy",
            "signalasi.workspace.entry.delete",
            "signalasi.workspace.file.search.text",
            "signalasi.workspace.file.patch.exact",
            "signalasi.workspace.file.diff.summary",
            "signalasi.workspace.file.sha256",
            "signalasi.workspace.zip.create",
            "signalasi.workspace.zip.list",
            "signalasi.workspace.zip.extract",
            "signalasi.agent_action.read.screen",
            "signalasi.agent_action.tap",
            "signalasi.agent_action.type.text",
            "signalasi.agent_action.swipe",
            "signalasi.agent_action.long.press",
            "signalasi.agent_action.delete.text",
            "signalasi.agent_action.paste.text",
            "signalasi.agent_action.copy.screen.text",
            "signalasi.agent_action.back",
            "signalasi.agent_action.home",
            "signalasi.agent_action.recents",
            "signalasi.agent_action.lock.screen",
            "signalasi.agent_action.open.app",
            "signalasi.agent_action.open.url",
            "signalasi.agent_action.set.alarm",
            "signalasi.agent_action.reply.notification"
        )

        assertEquals(expected, AgentPhoneNativeToolCatalog.toolIds)
        assertEquals(expected, registry.descriptors().map { it.id }.toSet())
        assertEquals(expected.size, registry.descriptors().size)
    }

    @Test
    fun everyDefinitionCarriesCompletePolicyAndProvenance() {
        val registry = registry()

        registry.descriptors().forEach { descriptor ->
            assertTrue(descriptor.id, descriptor.inputSchema.document.isNotEmpty())
            assertTrue(descriptor.id, descriptor.outputSchema.document.isNotEmpty())
            assertTrue(descriptor.id, descriptor.capabilities.isNotEmpty())
            assertTrue(descriptor.id, descriptor.requiredPermissions.isNotEmpty())
            assertTrue(descriptor.id, descriptor.requiredConsents.isNotEmpty())
            assertTrue(descriptor.id, descriptor.timeoutMillis in 1..30_000)
            assertNotNull(descriptor.availability)

            val definition = registry.lookup(descriptor.id)
            assertNotNull(descriptor.id, definition)
            assertTrue(descriptor.id, definition!!.executorId.isNotBlank())
            assertTrue(descriptor.id, definition.provenanceMetadata.isNotEmpty())
        }
    }

    @Test
    fun executesBoundedWorkspaceFileAndZipToolsThroughRegistry() {
        val registry = registry()

        val initialized = registry.invoke(
            AgentPhoneNativeToolCatalog.WORKSPACE_INITIALIZE,
            mapOf("workspace_id" to "task-7"),
            workspaceContext(AgentPhoneNativeToolCatalog.WORKSPACE_WRITE_CONSENT)
        )
        assertTrue(initialized.toJson(), initialized.isSuccess)

        val written = registry.invoke(
            AgentPhoneNativeToolCatalog.WORKSPACE_WRITE_TEXT,
            mapOf(
                "workspace_id" to "task-7",
                "path" to "docs/note.txt",
                "text" to "hello phone registry",
                "create_parents" to true
            ),
            workspaceContext(AgentPhoneNativeToolCatalog.WORKSPACE_WRITE_CONSENT)
        )
        assertTrue(written.toJson(), written.isSuccess)
        assertEquals("write", written.output["kind"])
        assertEquals("signalasi.workspace_file_tools", written.provenance.executorId)
        assertEquals("app_private", written.provenance.metadata["storage_scope"])

        val deniedRead = registry.invoke(
            AgentPhoneNativeToolCatalog.WORKSPACE_READ_TEXT,
            mapOf("workspace_id" to "task-7", "path" to "docs/note.txt"),
            AgentNativeToolInvocationContext(
                grantedPermissions = setOf(AgentPhoneNativeToolCatalog.WORKSPACE_PRIVATE_PERMISSION)
            )
        )
        assertEquals("missing_consents", deniedRead.error?.code)

        val read = registry.invoke(
            AgentPhoneNativeToolCatalog.WORKSPACE_READ_TEXT,
            mapOf("workspace_id" to "task-7", "path" to "docs/note.txt"),
            workspaceContext(AgentPhoneNativeToolCatalog.WORKSPACE_READ_CONSENT)
        )
        assertTrue(read.toJson(), read.isSuccess)
        assertEquals("hello phone registry", read.output["text"])
        assertEquals(64, (read.output["sha256"] as String).length)

        val zipped = registry.invoke(
            AgentPhoneNativeToolCatalog.WORKSPACE_ZIP_CREATE,
            mapOf(
                "workspace_id" to "task-7",
                "archive_path" to "artifacts/docs.zip",
                "source_paths" to listOf("docs"),
                "create_parents" to true
            ),
            workspaceContext(AgentPhoneNativeToolCatalog.WORKSPACE_WRITE_CONSENT, "zip-create-1")
        )
        assertTrue(zipped.toJson(), zipped.isSuccess)
        assertTrue((zipped.output["entries"] as List<*>).isNotEmpty())

        val listed = registry.invoke(
            AgentPhoneNativeToolCatalog.WORKSPACE_ZIP_LIST,
            mapOf("workspace_id" to "task-7", "archive_path" to "artifacts/docs.zip"),
            workspaceContext(AgentPhoneNativeToolCatalog.WORKSPACE_READ_CONSENT)
        )
        assertTrue(listed.toJson(), listed.isSuccess)
        assertEquals("artifacts/docs.zip", listed.output["archive_path"])

        val extracted = registry.invoke(
            AgentPhoneNativeToolCatalog.WORKSPACE_ZIP_EXTRACT,
            mapOf(
                "workspace_id" to "task-7",
                "archive_path" to "artifacts/docs.zip",
                "destination_path" to "restored"
            ),
            workspaceContext(AgentPhoneNativeToolCatalog.WORKSPACE_WRITE_CONSENT, "zip-extract-1")
        )
        assertTrue(extracted.toJson(), extracted.isSuccess)
        assertTrue((extracted.output["extracted_entries"] as Number).toInt() > 0)

        val restored = registry.invoke(
            AgentPhoneNativeToolCatalog.WORKSPACE_READ_TEXT,
            mapOf("workspace_id" to "task-7", "path" to "restored/docs/note.txt"),
            workspaceContext(AgentPhoneNativeToolCatalog.WORKSPACE_READ_CONSENT)
        )
        assertEquals("hello phone registry", restored.output["text"])
    }

    @Test
    fun adaptsExistingActionExecutorAndBoundsItsResult() {
        var captured: AgentAction? = null
        val longMessage = "m".repeat(3_000)
        val executor = object : AgentActionExecutor {
            override fun execute(action: AgentAction, screen: ScreenContext): AgentActionResult {
                captured = action
                return AgentActionResult(
                    actionId = action.id,
                    success = true,
                    message = longMessage,
                    metadata = (1..50).associate { "key-$it" to "v".repeat(2_000) }
                )
            }
        }
        val registry = registry(executor, readyCapabilityStatuses())
        val toolId = AgentNativeToolAgentActionAdapter.defaultToolId(AgentActionKind.READ_SCREEN)
        val descriptor = registry.lookup(toolId)!!.descriptor
        val result = registry.invoke(
            toolId,
            mapOf("target" to "current screen", "parameters" to emptyMap<String, String>()),
            AgentNativeToolInvocationContext(
                invocationId = "screen-read-1",
                grantedPermissions = descriptor.requiredPermissions.filter { it.required }.mapTo(mutableSetOf()) { it.id },
                grantedConsents = descriptor.requiredConsents.filter { it.required }.mapTo(mutableSetOf()) { it.id }
            )
        )

        assertTrue(result.toJson(), result.isSuccess)
        assertEquals(AgentActionKind.READ_SCREEN, captured?.kind)
        assertTrue(captured?.requiresConfirmation == true)
        assertEquals(2_048, result.message.length)
        assertEquals(32, (result.output["metadata"] as Map<*, *>).size)
        assertTrue((result.output["metadata"] as Map<*, *>).values.all { it.toString().length <= 1_024 })
        assertEquals("signalasi.android_agent_action", result.provenance.executorId)
        assertEquals("READ_SCREEN", result.provenance.metadata["legacy_action_kind"])
    }

    private fun registry(
        executor: AgentActionExecutor = successfulActionExecutor(),
        statuses: List<AgentPhoneCapabilityStatus>? = null
    ): AgentNativeToolRegistry = AgentPhoneNativeToolCatalog.createRegistry(
        workspaceFileTools = fileTools,
        actionExecutor = executor,
        screenProvider = { ScreenContext("SignalASI", pageTitle = "Agent") },
        capabilityStatusProvider = statuses?.let { captured -> { captured } }
            ?: { AgentPhoneCapabilityCatalog.capabilities.map { boundary ->
                AgentPhoneCapabilityStatus(boundary, boundary.availability, boundary.limitation)
            } }
    )

    private fun workspaceContext(
        consent: String,
        idempotencyKey: String? = null
    ) = AgentNativeToolInvocationContext(
        idempotencyKey = idempotencyKey,
        grantedPermissions = setOf(AgentPhoneNativeToolCatalog.WORKSPACE_PRIVATE_PERMISSION),
        grantedConsents = setOf(consent)
    )

    private fun successfulActionExecutor() = object : AgentActionExecutor {
        override fun execute(action: AgentAction, screen: ScreenContext) = AgentActionResult(
            actionId = action.id,
            success = true,
            message = "Executed"
        )
    }

    private fun readyCapabilityStatuses() = AgentPhoneCapabilityCatalog.capabilities.map { boundary ->
        AgentPhoneCapabilityStatus(
            boundary = boundary,
            availability = AgentPhoneCapabilityAvailability.READY,
            evidence = "Ready for test"
        )
    }
}
