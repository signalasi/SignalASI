package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeCapabilityMatrixTest {
    @Test
    fun unavailableAndBlockedToolsRemainVisibleButNeverExecutable() {
        val available = descriptor("signalasi.test.available")
        val setup = descriptor(
            "signalasi.test.setup",
            availability = AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
                "Permission missing"
            )
        )
        val unavailable = descriptor(
            "signalasi.test.unavailable",
            availability = AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.UNAVAILABLE,
                "Runtime missing"
            )
        )
        val blocked = descriptor("signalasi.test.blocked", risk = AgentNativeToolRisk.BLOCKED)

        val snapshot = AgentRuntimeCapabilityMatrix.build(
            nativeTools = listOf(available, setup, unavailable, blocked),
            systemTools = emptyList(),
            targets = emptyList()
        )

        assertEquals(setOf(available.id), snapshot.availableNativeToolIds)
        assertEquals(4, snapshot.entries.size)
        assertEquals(1, snapshot.setupRequiredEntries.size)
        assertEquals(2, snapshot.unavailableEntries.size)
        assertFalse(snapshot.isNativeToolExecutable(setup.id))
        assertFalse(snapshot.isNativeToolExecutable(unavailable.id))
        assertFalse(snapshot.isNativeToolExecutable(blocked.id))
    }

    @Test
    fun systemToolUsesTheLiveNativeAdapterState() {
        val actionId = AgentNativeToolAgentActionAdapter.defaultToolId(AgentActionKind.OPEN_APP)
        val native = descriptor(
            actionId,
            availability = AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
                "No matching activity"
            )
        )
        val action = AgentSystemTool(
            id = "open-app",
            title = "Open app",
            kind = AgentActionKind.OPEN_APP,
            risk = AgentRisk.LOW,
            capabilities = listOf(AgentCapability.APP_NAVIGATION),
            examples = emptyList()
        )
        val workflow = action.copy(id = "workflow:daily", kind = AgentActionKind.DRAFT_PLAN)

        val snapshot = AgentRuntimeCapabilityMatrix.build(
            nativeTools = listOf(native),
            systemTools = listOf(action, workflow),
            targets = emptyList()
        )

        assertEquals(
            AgentRuntimeCapabilityState.REQUIRES_SETUP,
            snapshot.entry(AgentRuntimeCapabilitySource.SYSTEM_TOOL, action.id)?.state
        )
        assertEquals(
            AgentRuntimeCapabilityState.AVAILABLE,
            snapshot.entry(AgentRuntimeCapabilitySource.SYSTEM_TOOL, workflow.id)?.state
        )
    }

    @Test
    fun connectorStateAndNativeCatalogStatusUseTheSameSnapshot() {
        val available = descriptor("signalasi.test.available")
        val unavailable = descriptor(
            "signalasi.test.unavailable",
            availability = AgentNativeToolAvailability(AgentNativeToolAvailabilityStatus.UNAVAILABLE, "Missing")
        )
        val target = AgentCallableTarget(
            id = "codex",
            title = "Codex",
            kind = AgentConnectorKind.AGENT,
            status = AgentConnectorStatus.DISCONNECTED,
            capabilities = listOf(AgentCapability.CODE)
        )

        val catalog = AgentResourceCatalog.build(
            targets = listOf(target),
            tools = emptyList(),
            nativeTools = listOf(available, unavailable)
        )

        assertEquals(AgentConnectorStatus.DISCONNECTED, catalog.first { it.id == "target:codex" }.status)
        assertEquals(AgentConnectorStatus.AVAILABLE, catalog.first { it.id == "native:${available.id}" }.status)
        assertEquals(AgentConnectorStatus.DISCONNECTED, catalog.first { it.id == "native:${unavailable.id}" }.status)
    }

    @Test
    fun registrySubsetResolvesCurrentAvailabilityInsteadOfRegistrationDefault() {
        var availability = AgentNativeToolAvailability.AVAILABLE
        val descriptor = descriptor("signalasi.test.dynamic")
        val registry = AgentNativeToolRegistry().register(
            AgentNativeToolDefinition(
                descriptor = descriptor,
                executor = AgentNativeToolExecutor { AgentNativeToolExecutionResult.success() },
                availabilityProvider = AgentNativeToolAvailabilityProvider { availability }
            )
        )

        assertNotNull(registry.subset { it.availability.status == AgentNativeToolAvailabilityStatus.AVAILABLE }.lookup(descriptor.id))

        availability = AgentNativeToolAvailability(
            AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
            "Permission revoked"
        )
        assertNull(registry.subset { it.availability.status == AgentNativeToolAvailabilityStatus.AVAILABLE }.lookup(descriptor.id))
        assertTrue(registry.descriptors().single().availability.reason.contains("revoked"))
    }

    @Test
    fun blockedDescriptorCannotExecuteEvenWhenProviderReportsAvailable() {
        var executed = false
        val descriptor = descriptor("signalasi.test.host_blocked", risk = AgentNativeToolRisk.BLOCKED)
        val registry = AgentNativeToolRegistry().register(
            AgentNativeToolDefinition(
                descriptor = descriptor,
                executor = AgentNativeToolExecutor {
                    executed = true
                    AgentNativeToolExecutionResult.success()
                },
                availabilityProvider = AgentNativeToolAvailabilityProvider {
                    AgentNativeToolAvailability.AVAILABLE
                }
            )
        )

        val result = registry.invoke(descriptor.id, emptyMap())

        assertFalse(executed)
        assertEquals(AgentNativeToolResultStatus.UNAVAILABLE, result.status)
        assertEquals("tool_blocked", result.error?.code)
    }

    private fun descriptor(
        id: String,
        risk: AgentNativeToolRisk = AgentNativeToolRisk.LOW,
        availability: AgentNativeToolAvailability = AgentNativeToolAvailability.AVAILABLE
    ) = AgentNativeToolDescriptor(
        id = id,
        version = "1.0.0",
        title = id,
        description = "Test capability",
        location = AgentNativeToolLocation.APPLICATION,
        inputSchema = AgentNativeJsonSchema.objectSchema(),
        outputSchema = AgentNativeJsonSchema.objectSchema(),
        risk = risk,
        capabilities = setOf("test.execute"),
        availability = availability
    )
}
