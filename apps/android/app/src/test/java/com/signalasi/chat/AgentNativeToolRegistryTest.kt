package com.signalasi.chat

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentNativeToolRegistryTest {
    @Test
    fun canonicalDigestMatchesDesktopConfirmationContract() {
        val value = mapOf(
            "workspace_id" to "abc",
            "path" to "a b.txt",
            "recursive" to false,
            "max_entries" to 10
        )

        assertEquals(
            "e68f4ceb5babcb632bb3dfa6422c5382a62b406df06c5bc060be8f49be395171",
            AgentNativeJsonCodec.sha256(value)
        )
    }

    @Test
    fun protectsStableIdsAndSupportsLookup() {
        val first = definition(descriptor(id = "phone.test.echo"))
        val registry = AgentNativeToolRegistry().register(first)

        assertSame(first, registry.lookup("phone.test.echo"))
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(definition(descriptor(id = "phone.test.echo", version = "2.0.0")))
        }
    }

    @Test
    fun listsStableIdsWithoutRunningAvailabilityChecks() {
        val availabilityChecks = AtomicInteger()
        val descriptor = descriptor(id = "phone.test.fast-id")
        val registry = AgentNativeToolRegistry().register(
            AgentNativeToolDefinition(
                descriptor = descriptor,
                executor = AgentNativeToolExecutor { AgentNativeToolExecutionResult.success() },
                availabilityProvider = AgentNativeToolAvailabilityProvider {
                    availabilityChecks.incrementAndGet()
                    AgentNativeToolAvailability(AgentNativeToolAvailabilityStatus.AVAILABLE)
                }
            )
        )

        assertEquals(setOf("phone.test.fast-id"), registry.ids())
        assertEquals(0, availabilityChecks.get())
        assertEquals(1, registry.descriptors().size)
        assertEquals(1, availabilityChecks.get())
    }

    @Test
    fun cachesResolvedDescriptorsAndInvalidatesAfterRegistration() {
        var now = 1_000L
        val availabilityChecks = AtomicInteger()
        val registry = AgentNativeToolRegistry(
            clock = AgentNativeClock { now },
            descriptorCacheTtlMillis = 100L
        ).register(
            AgentNativeToolDefinition(
                descriptor = descriptor(id = "phone.test.cached"),
                executor = AgentNativeToolExecutor { AgentNativeToolExecutionResult.success() },
                availabilityProvider = AgentNativeToolAvailabilityProvider {
                    availabilityChecks.incrementAndGet()
                    AgentNativeToolAvailability(AgentNativeToolAvailabilityStatus.AVAILABLE)
                }
            )
        )

        registry.descriptors()
        registry.descriptors()
        assertEquals(1, availabilityChecks.get())

        now += 101L
        registry.descriptors()
        assertEquals(2, availabilityChecks.get())

        registry.register(definition(descriptor(id = "phone.test.second")))
        registry.descriptors()
        assertEquals(3, availabilityChecks.get())
    }

    @Test
    fun serializesCompleteDeterministicCatalog() {
        val descriptor = descriptor(
            id = "phone.contacts.lookup",
            location = AgentNativeToolLocation.ANDROID_SYSTEM,
            risk = AgentNativeToolRisk.MEDIUM,
            capabilities = setOf("contacts.read", "phone.local"),
            permissions = listOf(
                AgentNativePermissionRequirement("android.permission.READ_CONTACTS", "Read contacts")
            ),
            consents = listOf(AgentNativeConsentRequirement("contacts.lookup", "Look up contact")),
            timeoutMillis = 4_500,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT,
            availability = AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
                "Contacts permission is disabled",
                123L
            ),
            inputSchema = AgentNativeJsonSchema.objectSchema(
                properties = mapOf("query" to AgentNativeJsonSchema.string(minLength = 1)),
                required = setOf("query"),
                additionalProperties = false
            )
        )
        val registry = AgentNativeToolRegistry().register(definition(descriptor))

        val first = registry.catalogJson()
        val second = registry.catalogJson()

        assertEquals(first, second)
        assertTrue(first.contains("\"contract_version\":\"signalasi.phone-native-tools/1.0\""))
        assertTrue(first.contains("\"id\":\"phone.contacts.lookup\""))
        assertTrue(first.contains("\"input_schema\""))
        assertTrue(first.contains("\"output_schema\""))
        assertTrue(first.contains("\"required_permissions\""))
        assertTrue(first.contains("\"required_consents\""))
        assertTrue(first.contains("\"idempotency\":\"idempotent\""))
        assertTrue(first.contains("\"status\":\"requires_setup\""))
        assertTrue(first.indexOf("contacts.read") < first.indexOf("phone.local"))
    }

    @Test
    fun validatesJsonSchemaTypesRequiredFieldsAndAdditionalProperties() {
        val schema = AgentNativeJsonSchema.objectSchema(
            properties = mapOf(
                "name" to AgentNativeJsonSchema.string(minLength = 2),
                "count" to AgentNativeJsonSchema.integer(minimum = 1),
                "tags" to AgentNativeJsonSchema.array(
                    AgentNativeJsonSchema.string(),
                    maxItems = 2
                )
            ),
            required = setOf("name", "count"),
            additionalProperties = false
        )
        val registry = AgentNativeToolRegistry().register(
            definition(descriptor(inputSchema = schema))
        )

        val invalid = registry.validateInput(
            "phone.test.tool",
            mapOf("count" to "one", "tags" to listOf("a", "b", "c"), "extra" to true)
        )
        val codes = invalid.issues.map { it.code }.toSet()

        assertFalse(invalid.isValid)
        assertTrue("required" in codes)
        assertTrue("type_mismatch" in codes)
        assertTrue("max_items" in codes)
        assertTrue("additional_property" in codes)
        assertTrue(registry.validateInput("phone.test.tool", mapOf("name" to "ok", "count" to 1)).isValid)
        assertEquals(
            "unknown_tool",
            registry.validateInput("phone.missing", emptyMap()).issues.single().code
        )
    }

    @Test
    fun gatesExecutionOnPermissionsAndConsents() {
        val executions = AtomicInteger()
        val descriptor = descriptor(
            permissions = listOf(AgentNativePermissionRequirement("android.permission.CAMERA")),
            consents = listOf(AgentNativeConsentRequirement("camera.capture"))
        )
        val registry = AgentNativeToolRegistry().register(
            AgentNativeToolDefinition(
                descriptor,
                AgentNativeToolExecutor {
                    executions.incrementAndGet()
                    AgentNativeToolExecutionResult.success(mapOf("captured" to true))
                }
            )
        )

        val missingPermission = registry.invoke(descriptor.id, emptyMap())
        val missingConsent = registry.invoke(
            descriptor.id,
            emptyMap(),
            AgentNativeToolInvocationContext(
                grantedPermissions = setOf("android.permission.CAMERA")
            )
        )
        val success = registry.invoke(
            descriptor.id,
            emptyMap(),
            AgentNativeToolInvocationContext(
                invocationId = "capture-1",
                grantedPermissions = setOf("android.permission.CAMERA"),
                grantedConsents = setOf("camera.capture")
            )
        )

        assertEquals("missing_permissions", missingPermission.error?.code)
        assertEquals("missing_consents", missingConsent.error?.code)
        assertEquals(AgentNativeToolResultStatus.SUCCEEDED, success.status)
        assertEquals(1, executions.get())
        assertEquals("capture-1", success.receipt.invocationId)
    }

    @Test
    fun returnsReceiptProvenanceAndVerification() {
        val clock = MutableClock(1_000L)
        val started = AtomicInteger()
        val progress = mutableListOf<AgentNativeToolProgressUpdate>()
        val finished = AtomicInteger()
        val descriptor = descriptor(outputSchema = AgentNativeJsonSchema.objectSchema(
            properties = mapOf("value" to AgentNativeJsonSchema.string()),
            required = setOf("value"),
            additionalProperties = false
        ))
        val registry = AgentNativeToolRegistry(clock).register(
            AgentNativeToolDefinition(
                descriptor = descriptor,
                executor = AgentNativeToolExecutor { invocation ->
                    invocation.reportProgress("working", "Preparing output", 40, sequence = 3)
                    clock.now += 7
                    AgentNativeToolExecutionResult.success(
                        output = mapOf("value" to "done"),
                        message = "Completed",
                        metadata = mapOf("native_call" to "local")
                    )
                },
                verifier = AgentNativeToolVerifier { _, execution ->
                    AgentNativeToolVerification(
                        AgentNativeVerificationStatus.PASSED,
                        evidence = mapOf("observed" to execution.output["value"])
                    )
                },
                executorId = "test.executor",
                provenanceMetadata = mapOf("implementation" to "fake")
            )
        )

        val result = registry.invoke(
            descriptor.id,
            emptyMap(),
            AgentNativeToolInvocationContext(invocationId = "invoke-7"),
            AgentNativeToolInvocationHooks(
                onStarted = { started.incrementAndGet() },
                onProgress = { _, update -> progress += update },
                onFinished = { finished.incrementAndGet() }
            )
        )

        assertTrue(result.isSuccess)
        assertEquals(7L, result.receipt.durationMillis)
        assertEquals(64, result.receipt.inputSha256.length)
        assertEquals(64, result.receipt.outputSha256.length)
        assertEquals(AgentNativeVerificationStatus.PASSED, result.verification?.status)
        assertEquals("test.executor", result.provenance.executorId)
        assertEquals("1.0.0", result.provenance.toolVersion)
        assertEquals(1, started.get())
        assertEquals("working", progress.single().stage)
        assertEquals(40, progress.single().percent)
        assertEquals(3L, progress.single().sequence)
        assertEquals(1, finished.get())
        assertTrue(result.toJson().contains("\"invocation_id\":\"invoke-7\""))
    }

    @Test
    fun exposesCancellationAndCooperativeTimeoutHooks() {
        val cancelledExecutions = AtomicInteger()
        val cancelledHooks = AtomicInteger()
        val cancellation = AgentNativeToolCancellationSource()
        cancellation.cancel()
        val cancelledRegistry = AgentNativeToolRegistry().register(
            AgentNativeToolDefinition(
                descriptor(),
                AgentNativeToolExecutor {
                    cancelledExecutions.incrementAndGet()
                    AgentNativeToolExecutionResult.success()
                }
            )
        )

        val cancelled = cancelledRegistry.invoke(
            "phone.test.tool",
            emptyMap(),
            hooks = AgentNativeToolInvocationHooks(
                cancellationToken = cancellation.token,
                onCancelled = { cancelledHooks.incrementAndGet() }
            )
        )

        assertEquals(AgentNativeToolResultStatus.CANCELLED, cancelled.status)
        assertEquals(0, cancelledExecutions.get())
        assertEquals(1, cancelledHooks.get())

        val clock = MutableClock(10L)
        val timeoutHooks = AtomicInteger()
        val timeoutRegistry = AgentNativeToolRegistry(clock).register(
            AgentNativeToolDefinition(
                descriptor(timeoutMillis = 5),
                AgentNativeToolExecutor { invocation ->
                    clock.now += 5
                    invocation.checkpoint()
                    AgentNativeToolExecutionResult.success()
                }
            )
        )

        val timedOut = timeoutRegistry.invoke(
            "phone.test.tool",
            emptyMap(),
            hooks = AgentNativeToolInvocationHooks(
                onTimeout = { timeoutHooks.incrementAndGet() }
            )
        )

        assertEquals(AgentNativeToolResultStatus.TIMED_OUT, timedOut.status)
        assertEquals(1, timeoutHooks.get())
    }

    @Test
    fun replaysSuccessfulKeyedIdempotentInvocation() {
        val executions = AtomicInteger()
        val descriptor = descriptor(
            idempotency = AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED
        )
        val registry = AgentNativeToolRegistry().register(
            AgentNativeToolDefinition(
                descriptor,
                AgentNativeToolExecutor {
                    AgentNativeToolExecutionResult.success(
                        mapOf("execution" to executions.incrementAndGet())
                    )
                }
            )
        )

        val missingKey = registry.invoke(descriptor.id, emptyMap())
        val first = registry.invoke(
            descriptor.id,
            emptyMap(),
            AgentNativeToolInvocationContext(invocationId = "first", idempotencyKey = "request-1")
        )
        val second = registry.invoke(
            descriptor.id,
            emptyMap(),
            AgentNativeToolInvocationContext(invocationId = "second", idempotencyKey = "request-1")
        )

        assertEquals("missing_idempotency_key", missingKey.error?.code)
        assertEquals(1, executions.get())
        assertEquals(first.output, second.output)
        assertTrue(second.receipt.replayed)
        assertEquals("first", second.receipt.originalInvocationId)
        assertEquals("second", second.receipt.invocationId)
    }

    @Test
    fun sharedReplayStoreSurvivesRegistryRecreation() {
        val executions = AtomicInteger()
        val replayStore = InMemoryAgentNativeToolReplayStore()
        val descriptor = descriptor(
            idempotency = AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
            inputSchema = AgentNativeJsonSchema.objectSchema(
                properties = mapOf("value" to AgentNativeJsonSchema.integer()),
                required = setOf("value"),
                additionalProperties = false
            )
        )
        fun registry() = AgentNativeToolRegistry(replayStore = replayStore).register(
            AgentNativeToolDefinition(
                descriptor,
                AgentNativeToolExecutor {
                    AgentNativeToolExecutionResult.success(
                        mapOf("execution" to executions.incrementAndGet())
                    )
                }
            )
        )

        val first = registry().invoke(
            descriptor.id,
            mapOf("value" to 1),
            AgentNativeToolInvocationContext(invocationId = "first", idempotencyKey = "durable-key")
        )
        val replay = registry().invoke(
            descriptor.id,
            mapOf("value" to 1),
            AgentNativeToolInvocationContext(invocationId = "replay", idempotencyKey = "durable-key")
        )

        assertEquals(1, executions.get())
        assertEquals(first.output, replay.output)
        assertTrue(replay.receipt.replayed)
    }

    @Test
    fun rejectsReusingIdempotencyKeyWithDifferentInput() {
        val descriptor = descriptor(
            idempotency = AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
            inputSchema = AgentNativeJsonSchema.objectSchema(
                properties = mapOf("value" to AgentNativeJsonSchema.integer()),
                required = setOf("value"),
                additionalProperties = false
            )
        )
        val registry = AgentNativeToolRegistry().register(
            AgentNativeToolDefinition(
                descriptor,
                AgentNativeToolExecutor { AgentNativeToolExecutionResult.success(mapOf("ok" to true)) }
            )
        )
        registry.invoke(
            descriptor.id,
            mapOf("value" to 1),
            AgentNativeToolInvocationContext(idempotencyKey = "same-key")
        )

        val conflict = registry.invoke(
            descriptor.id,
            mapOf("value" to 2),
            AgentNativeToolInvocationContext(idempotencyKey = "same-key")
        )

        assertEquals(AgentNativeToolResultStatus.REJECTED, conflict.status)
        assertEquals("idempotency_key_conflict", conflict.error?.code)
    }

    @Test
    fun adaptsExistingAgentActionExecutorWithoutMobileNativeAgentChanges() {
        var capturedAction: AgentAction? = null
        val legacyExecutor = object : AgentActionExecutor {
            override fun execute(action: AgentAction, screen: ScreenContext): AgentActionResult {
                capturedAction = action
                assertEquals("Settings", screen.pageTitle)
                return AgentActionResult(
                    actionId = action.id,
                    success = true,
                    message = "Tapped",
                    metadata = mapOf("screen" to screen.pageTitle)
                )
            }
        }
        val descriptor = descriptor(
            id = "phone.screen.tap",
            risk = AgentNativeToolRisk.MEDIUM,
            inputSchema = AgentNativeJsonSchema.objectSchema(
                properties = mapOf(
                    "target" to AgentNativeJsonSchema.string(),
                    "description" to AgentNativeJsonSchema.string(),
                    "parameters" to AgentNativeJsonSchema.objectSchema(),
                    "requires_confirmation" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf("target", "parameters"),
                additionalProperties = false
            ),
            outputSchema = legacyOutputSchema()
        )
        val registry = AgentNativeToolRegistry().register(
            AgentNativeToolDefinition(
                descriptor = descriptor,
                executor = AgentActionNativeToolExecutor.forKind(
                    delegate = legacyExecutor,
                    kind = AgentActionKind.TAP,
                    screenProvider = { ScreenContext("SignalASI", pageTitle = "Settings") }
                ),
                executorId = "legacy.agent_action"
            )
        )
        val legacy = AgentAction(
            id = "legacy-9",
            kind = AgentActionKind.TAP,
            target = "Wi-Fi",
            risk = AgentRisk.MEDIUM,
            status = AgentActionStatus.PROPOSED,
            description = "Tap Wi-Fi",
            parameters = mapOf("bounds" to "[0,0][10,10]")
        )
        val call = AgentNativeToolAgentActionAdapter.fromAgentAction(legacy, descriptor.id)

        val nativeResult = registry.invoke(call.toolId, call.input, call.context)
        val roundTripped = AgentNativeToolAgentActionAdapter.toAgentActionResult(nativeResult, legacy.id)

        assertTrue(nativeResult.toJson(), nativeResult.isSuccess)
        assertEquals(AgentActionKind.TAP, capturedAction?.kind)
        assertEquals("Wi-Fi", capturedAction?.target)
        assertEquals("[0,0][10,10]", capturedAction?.parameters?.get("bounds"))
        assertTrue(capturedAction?.requiresConfirmation == true)
        assertEquals("legacy-9", nativeResult.provenance.legacyAgentActionId)
        assertTrue(roundTripped.success)
        assertEquals(descriptor.id, roundTripped.metadata["native_tool_id"])
        assertNotNull(roundTripped.metadata["native_receipt_id"])
    }

    private fun descriptor(
        id: String = "phone.test.tool",
        version: String = "1.0.0",
        location: AgentNativeToolLocation = AgentNativeToolLocation.PHONE,
        risk: AgentNativeToolRisk = AgentNativeToolRisk.LOW,
        capabilities: Set<String> = setOf("phone.test"),
        permissions: List<AgentNativePermissionRequirement> = emptyList(),
        consents: List<AgentNativeConsentRequirement> = emptyList(),
        timeoutMillis: Long = 1_000,
        idempotency: AgentNativeToolIdempotency = AgentNativeToolIdempotency.NON_IDEMPOTENT,
        availability: AgentNativeToolAvailability = AgentNativeToolAvailability.AVAILABLE,
        inputSchema: AgentNativeJsonSchema = AgentNativeJsonSchema.objectSchema(
            additionalProperties = false
        ),
        outputSchema: AgentNativeJsonSchema = AgentNativeJsonSchema.objectSchema()
    ) = AgentNativeToolDescriptor(
        id = id,
        version = version,
        title = "Test tool",
        description = "A focused native tool used by the registry tests.",
        location = location,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        risk = risk,
        capabilities = capabilities,
        requiredPermissions = permissions,
        requiredConsents = consents,
        timeoutMillis = timeoutMillis,
        idempotency = idempotency,
        availability = availability
    )

    private fun definition(descriptor: AgentNativeToolDescriptor) = AgentNativeToolDefinition(
        descriptor,
        AgentNativeToolExecutor { AgentNativeToolExecutionResult.success() }
    )

    private fun legacyOutputSchema() = AgentNativeJsonSchema.objectSchema(
        properties = mapOf(
            "action_id" to AgentNativeJsonSchema.string(),
            "success" to AgentNativeJsonSchema.boolean(),
            "message" to AgentNativeJsonSchema.string(),
            "metadata" to AgentNativeJsonSchema.objectSchema()
        ),
        required = setOf("action_id", "success", "message", "metadata"),
        additionalProperties = false
    )

    private class MutableClock(var now: Long) : AgentNativeClock {
        override fun nowEpochMillis(): Long = now
    }
}
