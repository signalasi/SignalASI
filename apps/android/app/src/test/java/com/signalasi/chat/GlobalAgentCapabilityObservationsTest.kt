package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalAgentCapabilityObservationsTest {
    private val pipeline = GlobalUnderstandingPipeline()

    @Test
    fun rememberedAuthorizationIsLocalOnlyAndRevocationReplacesTheGrant() {
        val consent = "device_control:private-device-id"
        val granted = GlobalCapabilityObservationExtractor.authorizationMutations(
            emptySet(),
            setOf(consent),
            1_000L
        ).single()
        val grantedWorld = reduce(PersonalWorldModel(), granted)

        assertEquals(GlobalConversationEventType.AUTHORIZATION_GRANTED, granted.type)
        assertFalse(eventText(granted).contains("private-device-id"))
        assertEquals(1, grantedWorld.items.size)
        assertEquals(GlobalWorldItemKind.DECISION, grantedWorld.items.single().kind)
        assertEquals(GlobalWorldLayer.USER, grantedWorld.items.single().layer)
        assertEquals(GlobalWorldContextVisibility.LOCAL_ONLY, grantedWorld.items.single().contextVisibility)

        val revoked = GlobalCapabilityObservationExtractor.authorizationMutations(
            setOf(consent),
            emptySet(),
            2_000L
        ).single()
        val revokedWorld = reduce(grantedWorld, revoked)

        assertEquals(GlobalConversationEventType.AUTHORIZATION_REVOKED, revoked.type)
        assertEquals(1, revokedWorld.items.size)
        assertTrue(revokedWorld.items.single().value.contains("revoked"))
        assertFalse(revokedWorld.items.single().value.contains("private-device-id"))
    }

    @Test
    fun safetyPolicyChangesReplaceOneLocalAuthorizationDecision() {
        val initial = AgentSafetySettings()
        val autonomous = initial.copy(
            permissionMode = PermissionMode.AUTO_LOW_RISK,
            connectorCallsAllowed = false,
            deviceControlAllowed = false
        )
        val first = GlobalCapabilityObservationExtractor.safetyPolicyMutation(null, initial, 1_000L)
        val changed = GlobalCapabilityObservationExtractor.safetyPolicyMutation(initial, autonomous, 2_000L)

        assertNotNull(first)
        assertNotNull(changed)
        var world = reduce(PersonalWorldModel(), first!!)
        world = reduce(world, changed!!)

        assertEquals(1, world.items.size)
        assertEquals(GlobalWorldItemKind.DECISION, world.items.single().kind)
        assertEquals(GlobalWorldContextVisibility.LOCAL_ONLY, world.items.single().contextVisibility)
        assertTrue(world.items.single().value.contains("auto low risk"))
        assertTrue(world.items.single().value.contains("device control is blocked"))
        assertNull(GlobalCapabilityObservationExtractor.safetyPolicyMutation(autonomous, autonomous, 3_000L))
    }

    @Test
    fun mcpLifecycleRedactsSecretsAndSuppressesTimestampOnlyUpdates() {
        val connection = mcpConnection()
        val installed = GlobalCapabilityObservationExtractor.mcpMutations(
            emptyList(),
            listOf(connection),
            1_000L
        ).single()

        assertEquals(GlobalConversationEventType.RESOURCE_REGISTERED, installed.type)
        assertFalse(eventText(installed).contains("private.example.internal"))
        assertFalse(eventText(installed).contains("access-token"))
        assertFalse(eventText(installed).contains("secret backend error"))
        assertEquals("true", installed.metadata["callable"])

        val timestampOnly = connection.copy(
            updatedAtMillis = 2_000L,
            lastValidatedAtMillis = 2_000L
        )
        assertTrue(
            GlobalCapabilityObservationExtractor.mcpMutations(
                listOf(connection),
                listOf(timestampOnly),
                2_000L
            ).isEmpty()
        )
    }

    @Test
    fun mcpFailureAndRecoveryReplaceOneRealtimeState() {
        val connected = mcpConnection()
        val failed = connected.copy(
            state = AgentMcpConnectionState.ERROR,
            lastError = "token=never-export-this",
            updatedAtMillis = 2_000L
        )
        val recovered = connected.copy(updatedAtMillis = 3_000L, lastValidatedAtMillis = 3_000L)
        var world = reduce(
            PersonalWorldModel(),
            GlobalCapabilityObservationExtractor.mcpMutations(emptyList(), listOf(connected), 1_000L).single()
        )
        world = reduce(
            world,
            GlobalCapabilityObservationExtractor.mcpMutations(listOf(connected), listOf(failed), 2_000L).single()
        )

        assertEquals(1, resourceStates(world, "mcp").size)
        assertTrue(resourceStates(world, "mcp").single().value.contains("failed"))
        assertFalse(world.items.any { it.kind == GlobalWorldItemKind.RISK })

        world = reduce(
            world,
            GlobalCapabilityObservationExtractor.mcpMutations(listOf(failed), listOf(recovered), 3_000L).single()
        )
        assertEquals(1, resourceStates(world, "mcp").size)
        assertTrue(resourceStates(world, "mcp").single().value.contains("connected"))
    }

    @Test
    fun agentHeartbeatsPublishOnlyMaterialAvailabilityOrCapacityChanges() {
        val online = agentRegistration(status = AgentEndpointStatus.ONLINE, activeRuns = 0)
        val sameAvailability = online.copy(
            activeRuns = 1,
            lastHeartbeatMillis = 2_000L,
            updatedAtMillis = 2_000L
        )
        val atCapacity = sameAvailability.copy(
            activeRuns = 2,
            lastHeartbeatMillis = 3_000L,
            updatedAtMillis = 3_000L
        )

        assertTrue(
            GlobalCapabilityObservationExtractor.agentMutations(
                listOf(online),
                listOf(sameAvailability),
                2_000L
            ).isEmpty()
        )
        val capacityEvent = GlobalCapabilityObservationExtractor.agentMutations(
            listOf(sameAvailability),
            listOf(atCapacity),
            3_000L
        ).single()

        assertEquals(GlobalConversationEventType.RESOURCE_UPDATED, capacityEvent.type)
        assertTrue(capacityEvent.metadata["at_capacity"] == "true")
        assertFalse(eventText(capacityEvent).contains("installation-secret"))
        assertFalse(eventText(capacityEvent).contains("device-secret"))
        assertFalse(eventText(capacityEvent).contains("provider-secret"))
    }

    @Test
    fun customDeviceRemovalRetractsIdentityAndStateWithoutExposingConnectionData() {
        val connector = CustomDeviceConnector(
            id = "device-private-id",
            name = "Workshop relay",
            transport = CustomDeviceTransport.HTTP_REST,
            endpoint = "https://192.0.2.10/private-control",
            commandTarget = "relay/private-channel",
            username = "private-user",
            authToken = "private-token"
        )
        val registered = GlobalCapabilityObservationExtractor.customDeviceMutations(
            emptyList(),
            listOf(connector),
            1_000L
        ).single()
        val world = reduce(PersonalWorldModel(), registered)

        assertEquals(2, world.items.size)
        assertFalse(eventText(registered).contains("192.0.2.10"))
        assertFalse(eventText(registered).contains("private-channel"))
        assertFalse(eventText(registered).contains("private-user"))
        assertFalse(eventText(registered).contains("private-token"))

        val removed = GlobalCapabilityObservationExtractor.customDeviceMutations(
            listOf(connector),
            emptyList(),
            2_000L
        ).single()
        val finalWorld = reduce(world, removed)

        assertEquals(GlobalConversationEventType.RESOURCE_REMOVED, removed.type)
        assertTrue(finalWorld.items.isEmpty())
    }

    @Test
    fun homeAssistantObservationNeverContainsServerOrCredentialMaterial() {
        val configured = HomeAssistantSettings(
            enabled = true,
            baseUrl = "https://home.private.example/api",
            accessToken = "long-lived-access-token",
            defaultEntityId = "lock.private_front_door"
        )
        val event = GlobalCapabilityObservationExtractor.homeAssistantMutations(
            HomeAssistantSettings(),
            configured,
            1_000L
        ).single()

        assertFalse(eventText(event).contains("home.private.example"))
        assertFalse(eventText(event).contains("long-lived-access-token"))
        assertFalse(eventText(event).contains("private_front_door"))
        assertEquals("true", event.metadata["credentials_configured"])
    }

    @Test
    fun healthTransitionsAreLowNoiseRealtimeStateAndRecoveryReplacesFailure() {
        val healthy = AgentResourceHealth(successes = 1, lastUpdatedAt = 1_000L)
        val degraded = AgentResourceHealth(
            successes = 1,
            failures = 1,
            consecutiveFailures = 1,
            lastUpdatedAt = 2_000L
        )
        val repeatedDegraded = degraded.copy(failures = 2, consecutiveFailures = 2, lastUpdatedAt = 3_000L)
        val unavailable = repeatedDegraded.copy(
            failures = 3,
            consecutiveFailures = 3,
            circuitOpenUntil = 100_000L,
            lastUpdatedAt = 4_000L
        )
        val recovered = unavailable.copy(
            successes = 2,
            consecutiveFailures = 0,
            circuitOpenUntil = 0L,
            lastUpdatedAt = 5_000L
        )
        val healthyEvent = GlobalCapabilityObservationExtractor.resourceHealthTransition(
            "target:private-agent-id",
            AgentResourceHealth(),
            healthy,
            1_000L
        )
        assertNotNull(healthyEvent)
        val degradedEvent = GlobalCapabilityObservationExtractor.resourceHealthTransition(
            "target:private-agent-id",
            healthy,
            degraded,
            2_000L
        )
        assertNotNull(degradedEvent)
        assertNull(
            GlobalCapabilityObservationExtractor.resourceHealthTransition(
                "target:private-agent-id",
                degraded,
                repeatedDegraded,
                3_000L
            )
        )
        val unavailableEvent = GlobalCapabilityObservationExtractor.resourceHealthTransition(
            "target:private-agent-id",
            repeatedDegraded,
            unavailable,
            4_000L
        )
        assertNotNull(unavailableEvent)
        val recoveredEvent = GlobalCapabilityObservationExtractor.resourceHealthTransition(
            "target:private-agent-id",
            unavailable,
            recovered,
            5_000L
        )
        assertNotNull(recoveredEvent)

        var world = reduce(PersonalWorldModel(), healthyEvent!!)
        world = reduce(world, degradedEvent!!)
        world = reduce(world, unavailableEvent!!)
        world = reduce(world, recoveredEvent!!)
        assertEquals(1, world.items.size)
        assertTrue(world.items.single().value.contains("available"))
        assertEquals(GlobalWorldLayer.REALTIME, world.items.single().layer)
        assertEquals(GlobalWorldContextVisibility.LOCAL_ONLY, world.items.single().contextVisibility)
        assertFalse(eventText(unavailableEvent).contains("private-agent-id"))
        assertFalse(world.items.any { it.kind == GlobalWorldItemKind.RISK })
    }

    @Test
    fun snapshotResetRemovesOnlyCapabilityProjectionAndDoesNotCreateTopicNodes() {
        val mcpEvent = GlobalCapabilityObservationExtractor.mcpMutations(
            emptyList(),
            listOf(mcpConnection()),
            1_000L
        ).single()
        val reduction = reduceResult(PersonalWorldModel(), mcpEvent)
        val graph = GlobalTopicProjectGraphReducer.reduce(
            GlobalTopicProjectGraph(),
            mcpEvent,
            pipeline.understand(mcpEvent, PersonalWorldModel()),
            reduction
        )
        val ordinary = GlobalWorldItem(
            stableKey = "ordinary-fact",
            kind = GlobalWorldItemKind.FACT,
            layer = GlobalWorldLayer.USER,
            topic = "Preferences",
            value = "Keep this fact",
            confidence = 0.9
        )
        val world = reduction.world.copy(items = reduction.world.items + ordinary)
        val reset = GlobalCapabilityObservationExtractor.snapshotReset(2_000L)
        val resetWorld = reduce(world, reset)

        assertTrue(graph.nodes.isEmpty())
        assertEquals(listOf(ordinary), resetWorld.items)
    }

    private fun reduce(world: PersonalWorldModel, event: GlobalConversationEvent): PersonalWorldModel =
        reduceResult(world, event).world

    private fun reduceResult(world: PersonalWorldModel, event: GlobalConversationEvent): GlobalWorldReduction =
        GlobalWorldModelReducer.reduce(world, event, pipeline.understand(event, world))

    private fun resourceStates(world: PersonalWorldModel, kind: String): List<GlobalWorldItem> =
        world.items.filter {
            it.kind == GlobalWorldItemKind.STATE && it.stableKey.contains(":resource:$kind:")
        }

    private fun eventText(event: GlobalConversationEvent): String =
        (event.content + " " + event.contentRef + " " + event.metadata.entries.joinToString(" ")).lowercase()

    private fun mcpConnection() = AgentMcpConnection(
        id = "mcp-private-id",
        catalogId = "example.private",
        displayName = "Private research MCP",
        endpoint = "https://private.example.internal/mcp?access-token=secret",
        distribution = AgentMcpDistribution.REMOTE,
        transport = AgentMcpTransportKind.STREAMABLE_HTTP,
        authProfile = AgentMcpAuthProfile(AgentMcpAuthMethod.BEARER_TOKEN),
        authState = AgentMcpAuthState.AUTHENTICATED,
        state = AgentMcpConnectionState.CONNECTED,
        enabled = true,
        installedAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        lastError = "secret backend error",
        toolIds = listOf("research.search")
    )

    private fun agentRegistration(
        status: AgentEndpointStatus,
        activeRuns: Int
    ) = AgentRegistration(
        agentId = "agent-private-id",
        installationId = "installation-secret",
        deviceId = "device-secret",
        providerId = "provider-secret",
        displayName = "Codex workstation",
        kind = AgentConnectorKind.AGENT,
        location = AgentResourceLocation.TRUSTED_DESKTOP,
        status = status,
        capabilities = setOf(AgentCapability.CHAT, AgentCapability.CODE, AgentCapability.TASK_EXECUTION),
        protocol = AgentProtocolRange("1.0", "1.0", "1.0"),
        connectionKind = AgentConnectionKind.SIGNALASI_LINK,
        trust = AgentResourceTrust.VERIFIED_PAIRED,
        activeRuns = activeRuns,
        maxParallelRuns = 2,
        lastHeartbeatMillis = 1_000L,
        updatedAtMillis = 1_000L
    )
}
