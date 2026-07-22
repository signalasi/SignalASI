package com.signalasi.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHomeAssistantNativeToolsTest {
    @Test
    fun definitionsExposeCompleteSecretSafePolicies() {
        val platform = FakeHomeAssistantPlatform()
        val definitions = AgentHomeAssistantNativeTools.definitions(platform)

        assertEquals(AgentHomeAssistantNativeTools.toolIds, definitions.map { it.descriptor.id }.toSet())
        definitions.forEach { definition ->
            val descriptor = definition.descriptor
            assertTrue(descriptor.id, descriptor.inputSchema.document.isNotEmpty())
            assertTrue(descriptor.id, descriptor.outputSchema.document.isNotEmpty())
            assertTrue(descriptor.id, descriptor.capabilities.isNotEmpty())
            assertEquals(
                descriptor.id,
                listOf(AgentHomeAssistantNativeTools.INTERNET_PERMISSION),
                descriptor.requiredPermissions.map { it.id }
            )
            assertEquals(descriptor.id, "signalasi.android_home_assistant_tools", definition.executorId)
            assertEquals(descriptor.id, "none", definition.provenanceMetadata["credential_exposure"])
            assertFalse(descriptor.id, definition.provenanceMetadata.values.any { it.contains("secret-token") })
        }

        val connection = definitions.single {
            it.descriptor.id == AgentHomeAssistantNativeTools.CONNECTION_STATUS
        }.descriptor
        assertEquals(AgentNativeToolRisk.LOW, connection.risk)
        assertTrue(connection.requiredConsents.isEmpty())

        val service = definitions.single {
            it.descriptor.id == AgentHomeAssistantNativeTools.SERVICE_CALL
        }.descriptor
        assertEquals(AgentNativeToolRisk.MEDIUM, service.risk)
        assertEquals(AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED, service.idempotency)
        assertEquals(
            listOf(AgentHomeAssistantNativeTools.CONTROL_CONSENT),
            service.requiredConsents.map { it.id }
        )
    }

    @Test
    fun liveAvailabilityBlocksRequestsUntilConfigurationIsReady() {
        val platform = FakeHomeAssistantPlatform().apply {
            currentAvailability = AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
                "Configuration required"
            )
        }
        val registry = AgentHomeAssistantNativeTools.createRegistry(platform)
        val descriptor = descriptor(registry, AgentHomeAssistantNativeTools.CONNECTION_STATUS)

        val blocked = registry.invoke(
            AgentHomeAssistantNativeTools.CONNECTION_STATUS,
            emptyMap(),
            grantedContext(descriptor)
        )

        assertEquals(AgentNativeToolResultStatus.UNAVAILABLE, blocked.status)
        assertEquals("tool_unavailable", blocked.error?.code)
        assertEquals(0, platform.connectionCalls)

        platform.currentAvailability = AgentNativeToolAvailability.AVAILABLE
        val connected = registry.invoke(
            AgentHomeAssistantNativeTools.CONNECTION_STATUS,
            emptyMap(),
            grantedContext(descriptor).copy(invocationId = "connected")
        )
        assertTrue(connected.toJson(), connected.isSuccess)
        assertEquals(true, connected.output["connected"])
        assertEquals(false, connected.output["credentials_exposed"])
        assertEquals(1, platform.connectionCalls)
    }

    @Test
    fun entityReadsRequireConsentAndRedactProtectedState() {
        val platform = FakeHomeAssistantPlatform()
        val registry = AgentHomeAssistantNativeTools.createRegistry(platform)
        val listDescriptor = descriptor(registry, AgentHomeAssistantNativeTools.ENTITIES_LIST)

        val denied = registry.invoke(
            AgentHomeAssistantNativeTools.ENTITIES_LIST,
            mapOf("limit" to 5),
            grantedContext(listDescriptor).copy(grantedConsents = emptySet())
        )
        assertEquals("missing_consents", denied.error?.code)
        assertEquals(0, platform.listCalls)

        val listed = registry.invoke(
            AgentHomeAssistantNativeTools.ENTITIES_LIST,
            mapOf("query" to "front", "domains" to listOf("lock"), "limit" to 5),
            grantedContext(listDescriptor).copy(invocationId = "entities-list")
        )
        assertTrue(listed.toJson(), listed.isSuccess)
        val entity = (listed.output["entities"] as List<*>).single() as Map<*, *>
        assertEquals("protected", entity["state"])
        assertEquals(true, entity["protected"])
        assertEquals(1, listed.output["protected_state_count"])
        assertEquals(false, listed.metadata["access_token_exposed"])
        assertEquals(setOf("lock"), platform.lastDomains)
        assertEquals("front", platform.lastQuery)
    }

    @Test
    fun deterministicServiceCallRequiresConsentAndIdempotencyThenVerifiesControllerState() {
        val platform = FakeHomeAssistantPlatform()
        val registry = AgentHomeAssistantNativeTools.createRegistry(platform)
        val descriptor = descriptor(registry, AgentHomeAssistantNativeTools.SERVICE_CALL)
        val input = serviceInput("light", "turn_on", "light.office")

        val missingConsent = registry.invoke(
            AgentHomeAssistantNativeTools.SERVICE_CALL,
            input,
            grantedContext(descriptor, "light-on-1").copy(grantedConsents = emptySet())
        )
        val missingKey = registry.invoke(
            AgentHomeAssistantNativeTools.SERVICE_CALL,
            input,
            grantedContext(descriptor).copy(invocationId = "missing-key")
        )
        assertEquals("missing_consents", missingConsent.error?.code)
        assertEquals("missing_idempotency_key", missingKey.error?.code)
        assertEquals(0, platform.serviceCalls)

        val context = grantedContext(descriptor, "light-on-1").copy(invocationId = "service-first")
        val first = registry.invoke(AgentHomeAssistantNativeTools.SERVICE_CALL, input, context)
        val replay = registry.invoke(
            AgentHomeAssistantNativeTools.SERVICE_CALL,
            input,
            context.copy(invocationId = "service-replay")
        )

        assertTrue(first.toJson(), first.isSuccess)
        assertEquals(AgentNativeVerificationStatus.PASSED, first.verification?.status)
        assertEquals(true, first.output["controller_state_verified"])
        assertEquals(false, first.output["physical_outcome_verified"])
        assertEquals(false, first.metadata["physical_outcome_verified"])
        assertTrue(replay.receipt.replayed)
        assertEquals(1, platform.serviceCalls)
    }

    @Test
    fun mismatchedDeterministicStateFailsVerificationWithoutClaimingPhysicalOutcome() {
        val platform = FakeHomeAssistantPlatform().apply {
            serviceResult = serviceResult(
                verificationSupported = true,
                controllerStateVerified = false,
                currentState = "off"
            )
        }
        val registry = AgentHomeAssistantNativeTools.createRegistry(platform)
        val descriptor = descriptor(registry, AgentHomeAssistantNativeTools.SERVICE_CALL)

        val result = registry.invoke(
            AgentHomeAssistantNativeTools.SERVICE_CALL,
            serviceInput("light", "turn_on", "light.office"),
            grantedContext(descriptor, "light-mismatch")
        )

        assertEquals(AgentNativeToolResultStatus.VERIFICATION_FAILED, result.status)
        assertEquals("verification_failed", result.error?.code)
        assertEquals(false, result.output["physical_outcome_verified"])
    }

    @Test
    fun nonDeterministicServiceReportsAcceptedWithSkippedVerification() {
        val platform = FakeHomeAssistantPlatform().apply {
            serviceResult = serviceResult(
                serviceDomain = "scene",
                service = "turn_on",
                entityId = "scene.movie",
                verificationSupported = false,
                controllerStateVerified = false,
                currentState = "scening"
            )
        }
        val registry = AgentHomeAssistantNativeTools.createRegistry(platform)
        val descriptor = descriptor(registry, AgentHomeAssistantNativeTools.SERVICE_CALL)

        val result = registry.invoke(
            AgentHomeAssistantNativeTools.SERVICE_CALL,
            serviceInput("scene", "turn_on", "scene.movie"),
            grantedContext(descriptor, "scene-movie")
        )

        assertTrue(result.toJson(), result.isSuccess)
        assertEquals(AgentNativeVerificationStatus.SKIPPED, result.verification?.status)
        assertEquals(true, result.output["request_accepted"])
        assertEquals(false, result.output["physical_outcome_verified"])
    }

    @Test
    fun serviceSchemaRejectsCrossDomainAdministrativeAndSecretInputsBeforeExecution() {
        val platform = FakeHomeAssistantPlatform()
        val registry = AgentHomeAssistantNativeTools.createRegistry(platform)
        val descriptor = descriptor(registry, AgentHomeAssistantNativeTools.SERVICE_CALL)
        val context = grantedContext(descriptor, "invalid-service")

        val crossDomain = registry.invoke(
            AgentHomeAssistantNativeTools.SERVICE_CALL,
            serviceInput("switch", "turn_on", "light.office"),
            context
        )
        val administrative = registry.invoke(
            AgentHomeAssistantNativeTools.SERVICE_CALL,
            serviceInput("homeassistant", "restart", "light.office"),
            context.copy(invocationId = "invalid-admin", idempotencyKey = "invalid-admin")
        )
        val secret = registry.invoke(
            AgentHomeAssistantNativeTools.SERVICE_CALL,
            serviceInput(
                "alarm_control_panel",
                "alarm_disarm",
                "alarm_control_panel.home",
                mapOf("code" to "1234")
            ),
            context.copy(invocationId = "invalid-secret", idempotencyKey = "invalid-secret")
        )

        assertEquals("invalid_input", crossDomain.error?.code)
        assertEquals("invalid_input", administrative.error?.code)
        assertEquals("invalid_input", secret.error?.code)
        assertEquals(0, platform.serviceCalls)
        assertFalse(secret.toJson().contains("1234"))
    }

    @Test
    fun confirmationPolicyUsesPerEntityConfirmOnceAndAlwaysConfirmsSecurityTargets() {
        val light = actionFor(serviceInput("light", "turn_on", "light.office"))
        val lock = actionFor(serviceInput("lock", "unlock", "lock.front_door"))
        val automation = actionFor(serviceInput("automation", "trigger", "automation.leave_home"))

        assertEquals(AgentConfirmationTier.CONFIRM_ONCE, AgentConfirmationPolicy.tier(light))
        assertEquals(
            "home_assistant_control:light.office",
            AgentConfirmationPolicy.consentKey(light)
        )
        assertEquals(AgentConfirmationTier.CONFIRM_ALWAYS, AgentConfirmationPolicy.tier(lock))
        assertEquals(AgentConfirmationTier.CONFIRM_ALWAYS, AgentConfirmationPolicy.tier(automation))
    }

    @Test
    fun smartHomeChineseGoalSelectsTypedTools() {
        val selected = GlobalAutonomousToolCatalogPolicy.select(
            descriptors = AgentHomeAssistantNativeTools.definitions(FakeHomeAssistantPlatform()).map { it.descriptor },
            goal = "\u6253\u5f00\u5ba2\u5385\u7684\u706f",
            maximumTools = 4
        )

        assertTrue(selected.any { it.id == AgentHomeAssistantNativeTools.SERVICE_CALL })
    }

    private fun descriptor(
        registry: AgentNativeToolRegistry,
        id: String
    ): AgentNativeToolDescriptor = requireNotNull(registry.lookup(id)).descriptor

    private fun grantedContext(
        descriptor: AgentNativeToolDescriptor,
        idempotencyKey: String? = null
    ) = AgentNativeToolInvocationContext(
        idempotencyKey = idempotencyKey,
        grantedPermissions = descriptor.requiredPermissions.map { it.id }.toSet(),
        grantedConsents = descriptor.requiredConsents.map { it.id }.toSet()
    )

    private fun serviceInput(
        domain: String,
        service: String,
        entityId: String,
        serviceData: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> = mapOf(
        "service_domain" to domain,
        "service" to service,
        "entity_id" to entityId,
        "service_data" to serviceData
    )

    private fun actionFor(input: Map<String, Any?>) = AgentAction(
        id = "home-assistant-service",
        kind = AgentActionKind.CALL_NATIVE_TOOL,
        target = "Home Assistant service",
        risk = AgentRisk.MEDIUM,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = "Control one Home Assistant entity",
        parameters = mapOf(
            "tool_id" to AgentHomeAssistantNativeTools.SERVICE_CALL,
            "input_json" to JSONObject(input).toString()
        )
    )

    private fun serviceResult(
        serviceDomain: String = "light",
        service: String = "turn_on",
        entityId: String = "light.office",
        verificationSupported: Boolean = true,
        controllerStateVerified: Boolean = true,
        currentState: String = "on"
    ) = HomeAssistantServiceCallResult(
        handled = true,
        success = true,
        message = "Service accepted",
        serviceDomain = serviceDomain,
        service = service,
        entityId = entityId,
        requestAccepted = true,
        verificationSupported = verificationSupported,
        controllerStateObserved = true,
        controllerStateVerified = controllerStateVerified,
        previousState = "off",
        currentState = currentState,
        changedStateCount = 1,
        physicalOutcomeVerified = false
    )

    private class FakeHomeAssistantPlatform : AgentHomeAssistantToolPlatform {
        override val implementationId = "fake.home_assistant"
        var currentAvailability = AgentNativeToolAvailability.AVAILABLE
        var connectionCalls = 0
        var listCalls = 0
        var serviceCalls = 0
        var lastQuery = ""
        var lastDomains: Set<String> = emptySet()
        var serviceResult = HomeAssistantServiceCallResult(
            handled = true,
            success = true,
            message = "Service accepted",
            serviceDomain = "light",
            service = "turn_on",
            entityId = "light.office",
            requestAccepted = true,
            verificationSupported = true,
            controllerStateObserved = true,
            controllerStateVerified = true,
            previousState = "off",
            currentState = "on",
            changedStateCount = 1,
            physicalOutcomeVerified = false
        )

        override fun availability(): AgentNativeToolAvailability = currentAvailability

        override fun connectionStatus(): HomeAssistantCommandResult {
            connectionCalls += 1
            return HomeAssistantCommandResult(true, true, "Connected")
        }

        override fun listEntities(
            query: String,
            domains: Set<String>,
            limit: Int
        ): HomeAssistantEntityResult {
            listCalls += 1
            lastQuery = query
            lastDomains = domains
            return HomeAssistantEntityResult(
                handled = true,
                success = true,
                message = "Loaded one entity",
                entities = listOf(
                    HomeAssistantEntityState(
                        entityId = "lock.front_door",
                        friendlyName = "Front door",
                        state = "locked",
                        domain = "lock",
                        protected = true
                    )
                ).take(limit),
                totalMatched = 1
            )
        }

        override fun readEntity(entityId: String): HomeAssistantEntityResult =
            HomeAssistantEntityResult(
                handled = true,
                success = true,
                message = "Read entity",
                entities = listOf(
                    HomeAssistantEntityState(entityId, "Entity", "on", entityId.substringBefore('.'))
                )
            )

        override fun callService(
            serviceDomain: String,
            service: String,
            entityId: String,
            serviceData: Map<String, Any?>
        ): HomeAssistantServiceCallResult {
            serviceCalls += 1
            return serviceResult.copy(
                serviceDomain = serviceDomain,
                service = service,
                entityId = entityId
            )
        }
    }
}
