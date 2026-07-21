package com.signalasi.chat

import android.Manifest
import android.content.Context
import org.json.JSONObject
import java.util.Locale

interface AgentHomeAssistantToolPlatform {
    val implementationId: String
    fun availability(): AgentNativeToolAvailability
    fun connectionStatus(): HomeAssistantCommandResult
    fun listEntities(query: String, domains: Set<String>, limit: Int): HomeAssistantEntityResult
    fun readEntity(entityId: String): HomeAssistantEntityResult
    fun callService(
        serviceDomain: String,
        service: String,
        entityId: String,
        serviceData: Map<String, Any?>
    ): HomeAssistantServiceCallResult
}

class AgentAndroidHomeAssistantToolPlatform(context: Context) : AgentHomeAssistantToolPlatform {
    private val appContext = context.applicationContext

    override val implementationId: String = "signalasi.android.home_assistant_rest"

    override fun availability(): AgentNativeToolAvailability {
        val settings = HomeAssistantSettingsStore.load(appContext)
        return when {
            !settings.credentialsConfigured -> AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
                "Home Assistant URL and access token are not configured"
            )
            !settings.enabled -> AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.UNAVAILABLE,
                "Home Assistant device control is disabled"
            )
            else -> AgentNativeToolAvailability.AVAILABLE
        }
    }

    override fun connectionStatus(): HomeAssistantCommandResult =
        HomeAssistantDeviceClient.connectionStatus(appContext)

    override fun listEntities(
        query: String,
        domains: Set<String>,
        limit: Int
    ): HomeAssistantEntityResult = HomeAssistantDeviceClient.listEntities(
        context = appContext,
        query = query,
        limit = limit,
        domains = domains
    )

    override fun readEntity(entityId: String): HomeAssistantEntityResult =
        HomeAssistantDeviceClient.readEntity(appContext, entityId)

    override fun callService(
        serviceDomain: String,
        service: String,
        entityId: String,
        serviceData: Map<String, Any?>
    ): HomeAssistantServiceCallResult = HomeAssistantDeviceClient.callService(
        context = appContext,
        serviceDomain = serviceDomain,
        service = service,
        entityId = entityId,
        serviceData = serviceData
    )
}

/** Typed, bounded Home Assistant tools with encrypted credentials and controller-state verification. */
object AgentHomeAssistantNativeTools {
    const val CONNECTION_STATUS = "signalasi.home_assistant.connection.status"
    const val ENTITIES_LIST = "signalasi.home_assistant.entities.list"
    const val ENTITY_READ = "signalasi.home_assistant.entity.read"
    const val SERVICE_CALL = "signalasi.home_assistant.service.call"

    const val INTERNET_PERMISSION = Manifest.permission.INTERNET
    const val READ_CONSENT = "signalasi.consent.home_assistant_read"
    const val CONTROL_CONSENT = "signalasi.consent.home_assistant_control"

    const val MAX_ENTITY_RESULTS = 100
    const val MAX_SERVICE_DATA_ENTRIES = 16
    const val MAX_SERVICE_DATA_CHARACTERS = 8_192

    val toolIds: Set<String> = setOf(CONNECTION_STATUS, ENTITIES_LIST, ENTITY_READ, SERVICE_CALL)

    fun definitions(
        platform: AgentHomeAssistantToolPlatform,
        clock: AgentNativeClock = AgentNativeClock.SYSTEM
    ): List<AgentNativeToolDefinition> = listOf(
        definition(
            platform = platform,
            descriptor = descriptor(
                id = CONNECTION_STATUS,
                title = "Check Home Assistant connection",
                description = "Checks the configured Home Assistant REST endpoint without exposing its URL or credentials.",
                inputSchema = objectSchema(),
                outputSchema = connectionOutputSchema(),
                risk = AgentNativeToolRisk.LOW,
                capabilities = setOf("smart_home.connection.read", "smart_home.credentials.redacted"),
                consentId = null,
                idempotency = AgentNativeToolIdempotency.IDEMPOTENT
            )
        ) { _ ->
            val result = platform.connectionStatus()
            if (!result.success) {
                failure(result.code, result.message, result.retryable)
            } else {
                AgentNativeToolExecutionResult.success(
                    output = mapOf(
                        "connected" to true,
                        "credentials_exposed" to false,
                        "checked_at_epoch_ms" to clock.nowEpochMillis()
                    ),
                    message = result.message,
                    metadata = secretSafeMetadata()
                )
            }
        },
        definition(
            platform = platform,
            descriptor = descriptor(
                id = ENTITIES_LIST,
                title = "List Home Assistant entities",
                description = "Lists a bounded set of configured entities; protected security and presence states remain redacted.",
                inputSchema = objectSchema(
                    properties = mapOf(
                        "query" to AgentNativeJsonSchema.string(maxLength = MAX_QUERY_CHARACTERS),
                        "domains" to AgentNativeJsonSchema.array(
                            AgentNativeJsonSchema.string(pattern = NAME_PATTERN, maxLength = MAX_NAME_CHARACTERS),
                            maxItems = MAX_DOMAIN_FILTERS
                        ),
                        "limit" to AgentNativeJsonSchema.integer(1, MAX_ENTITY_RESULTS.toLong())
                    )
                ),
                outputSchema = entityListOutputSchema(),
                risk = AgentNativeToolRisk.MEDIUM,
                capabilities = setOf("smart_home.entities.read", "smart_home.sensitive_state.redaction"),
                consentId = READ_CONSENT,
                idempotency = AgentNativeToolIdempotency.IDEMPOTENT
            )
        ) { invocation ->
            val input = invocation.input
            val result = platform.listEntities(
                query = input.string("query").take(MAX_QUERY_CHARACTERS),
                domains = input.stringSet("domains", MAX_DOMAIN_FILTERS),
                limit = input.int("limit", DEFAULT_ENTITY_LIMIT, 1, MAX_ENTITY_RESULTS)
            )
            if (!result.success) {
                failure(result.code, result.message, result.retryable)
            } else {
                val entities = result.entities.take(MAX_ENTITY_RESULTS).map(::entityValue)
                AgentNativeToolExecutionResult.success(
                    output = mapOf(
                        "entities" to entities,
                        "result_count" to entities.size,
                        "total_matched" to result.totalMatched.coerceAtLeast(entities.size),
                        "truncated" to result.truncated,
                        "observed_at_epoch_ms" to result.observedAtEpochMillis.coerceAtLeast(0L),
                        "protected_state_count" to result.entities.count(HomeAssistantEntityState::protected)
                    ),
                    message = result.message,
                    metadata = secretSafeMetadata() + mapOf("protected_states_redacted" to true)
                )
            }
        },
        definition(
            platform = platform,
            descriptor = descriptor(
                id = ENTITY_READ,
                title = "Read one Home Assistant entity",
                description = "Reads one exact Home Assistant entity; security and presence state values remain redacted.",
                inputSchema = objectSchema(
                    properties = mapOf("entity_id" to entityIdSchema()),
                    required = setOf("entity_id")
                ),
                outputSchema = entityReadOutputSchema(),
                risk = AgentNativeToolRisk.MEDIUM,
                capabilities = setOf("smart_home.entity.read", "smart_home.sensitive_state.redaction"),
                consentId = READ_CONSENT,
                idempotency = AgentNativeToolIdempotency.IDEMPOTENT
            )
        ) { invocation ->
            val result = platform.readEntity(invocation.input.string("entity_id"))
            val entity = result.entities.singleOrNull()
            if (!result.success || entity == null) {
                failure(result.code, result.message, result.retryable)
            } else {
                AgentNativeToolExecutionResult.success(
                    output = mapOf(
                        "entity" to entityValue(entity),
                        "observed_at_epoch_ms" to result.observedAtEpochMillis.coerceAtLeast(0L)
                    ),
                    message = result.message,
                    metadata = secretSafeMetadata() + mapOf("protected_state_redacted" to entity.protected)
                )
            }
        },
        serviceDefinition(platform)
    )

    fun androidDefinitions(
        context: Context,
        clock: AgentNativeClock = AgentNativeClock.SYSTEM
    ): List<AgentNativeToolDefinition> = definitions(
        AgentAndroidHomeAssistantToolPlatform(context.applicationContext),
        clock
    )

    fun createRegistry(
        platform: AgentHomeAssistantToolPlatform,
        clock: AgentNativeClock = AgentNativeClock.SYSTEM,
        replayStore: AgentNativeToolReplayStore = InMemoryAgentNativeToolReplayStore()
    ): AgentNativeToolRegistry = AgentNativeToolRegistry(clock, replayStore).registerAll(
        definitions(platform, clock)
    )

    fun requiresAlwaysConfirmation(inputJson: String): Boolean {
        val input = runCatching { JSONObject(inputJson) }.getOrNull() ?: return false
        return requiresAlwaysConfirmation(
            entityId = input.optString("entity_id"),
            serviceDomain = input.optString("service_domain"),
            service = input.optString("service")
        )
    }

    fun consentScope(inputJson: String): String {
        val entityId = runCatching { JSONObject(inputJson).optString("entity_id") }
            .getOrDefault("")
            .trim()
            .lowercase(Locale.US)
        return if (ENTITY_ID_REGEX.matches(entityId)) {
            "home_assistant_control:$entityId"
        } else {
            "home_assistant_control"
        }
    }

    private fun serviceDefinition(platform: AgentHomeAssistantToolPlatform): AgentNativeToolDefinition {
        val descriptor = descriptor(
            id = SERVICE_CALL,
            title = "Call one Home Assistant entity service",
            description = "Calls one bounded entity service, requires an idempotency key, and verifies controller state when the service has a deterministic state.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "service_domain" to AgentNativeJsonSchema.string(
                        pattern = NAME_PATTERN,
                        minLength = 1,
                        maxLength = MAX_NAME_CHARACTERS
                    ),
                    "service" to AgentNativeJsonSchema.string(
                        pattern = NAME_PATTERN,
                        minLength = 1,
                        maxLength = MAX_NAME_CHARACTERS
                    ),
                    "entity_id" to entityIdSchema(),
                    "service_data" to AgentNativeJsonSchema.objectSchema(additionalProperties = true)
                ),
                required = setOf("service_domain", "service", "entity_id")
            ),
            outputSchema = serviceOutputSchema(),
            risk = AgentNativeToolRisk.MEDIUM,
            capabilities = setOf(
                "smart_home.entity.control",
                "smart_home.single_entity_scope",
                "smart_home.controller_state.verify"
            ),
            consentId = CONTROL_CONSENT,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED
        )
        return definition(
            platform = platform,
            descriptor = descriptor,
            validator = ServiceCallValidator
        ) { invocation ->
            val input = invocation.input
            val result = platform.callService(
                serviceDomain = input.string("service_domain"),
                service = input.string("service"),
                entityId = input.string("entity_id"),
                serviceData = input.objectValue("service_data")
            )
            if (!result.success) {
                failure(result.code, result.message, result.retryable)
            } else {
                AgentNativeToolExecutionResult.success(
                    output = mapOf(
                        "request_accepted" to result.requestAccepted,
                        "service_domain" to result.serviceDomain,
                        "service" to result.service,
                        "entity_id" to result.entityId,
                        "verification_supported" to result.verificationSupported,
                        "controller_state_observed" to result.controllerStateObserved,
                        "controller_state_verified" to result.controllerStateVerified,
                        "previous_state" to result.previousState.take(MAX_STATE_CHARACTERS),
                        "current_state" to result.currentState.take(MAX_STATE_CHARACTERS),
                        "state_protected" to result.stateProtected,
                        "changed_state_count" to result.changedStateCount.coerceAtLeast(0),
                        "physical_outcome_verified" to false
                    ),
                    message = result.message,
                    metadata = secretSafeMetadata() + mapOf(
                        "single_entity_scope" to true,
                        "physical_outcome_verified" to false
                    )
                )
            }
        }.let { definition ->
            AgentNativeToolDefinition(
                descriptor = definition.descriptor,
                executor = definition.executor,
                validator = definition.validator,
                verifier = AgentNativeToolVerifier { _, execution ->
                    val accepted = execution.output["request_accepted"] == true
                    val supported = execution.output["verification_supported"] == true
                    val verified = execution.output["controller_state_verified"] == true
                    when {
                        !accepted -> AgentNativeToolVerification(
                            AgentNativeVerificationStatus.FAILED,
                            "Home Assistant did not accept the service call"
                        )
                        supported && !verified -> AgentNativeToolVerification(
                            AgentNativeVerificationStatus.FAILED,
                            "Home Assistant controller state did not match the requested deterministic state",
                            mapOf("physical_outcome_verified" to false)
                        )
                        supported -> AgentNativeToolVerification(
                            AgentNativeVerificationStatus.PASSED,
                            "Home Assistant controller state matched the requested deterministic state",
                            mapOf(
                                "controller_state_verified" to true,
                                "physical_outcome_verified" to false
                            )
                        )
                        else -> AgentNativeToolVerification(
                            AgentNativeVerificationStatus.SKIPPED,
                            "This Home Assistant service has no deterministic entity-state verification",
                            mapOf(
                                "request_accepted" to true,
                                "physical_outcome_verified" to false
                            )
                        )
                    }
                },
                executorId = definition.executorId,
                provenanceMetadata = definition.provenanceMetadata,
                availabilityProvider = definition.availabilityProvider
            )
        }
    }

    private fun definition(
        platform: AgentHomeAssistantToolPlatform,
        descriptor: AgentNativeToolDescriptor,
        validator: AgentNativeToolValidator = AgentNativeJsonSchemaValidator,
        execute: (AgentNativeToolInvocation) -> AgentNativeToolExecutionResult
    ) = AgentNativeToolDefinition(
        descriptor = descriptor.copy(availability = platform.availability()),
        executor = AgentNativeToolExecutor { invocation ->
            invocation.checkpoint()
            execute(invocation)
        },
        validator = validator,
        executorId = EXECUTOR_ID,
        provenanceMetadata = mapOf(
            "implementation" to platform.implementationId,
            "transport" to "home_assistant_rest",
            "credential_storage" to "android_encrypted_preferences",
            "credential_exposure" to "none",
            "target_scope" to "single_entity",
            "result_policy" to "bounded-v1"
        ),
        availabilityProvider = AgentNativeToolAvailabilityProvider { platform.availability() }
    )

    private fun descriptor(
        id: String,
        title: String,
        description: String,
        inputSchema: AgentNativeJsonSchema,
        outputSchema: AgentNativeJsonSchema,
        risk: AgentNativeToolRisk,
        capabilities: Set<String>,
        consentId: String?,
        idempotency: AgentNativeToolIdempotency
    ) = AgentNativeToolDescriptor(
        id = id,
        version = VERSION,
        title = title,
        description = description,
        location = AgentNativeToolLocation.APPLICATION,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        risk = risk,
        capabilities = capabilities,
        requiredPermissions = listOf(
            AgentNativePermissionRequirement(
                INTERNET_PERMISSION,
                "Internet access",
                "Allows the app to reach the user-configured Home Assistant server."
            )
        ),
        requiredConsents = consentId?.let {
            listOf(
                AgentNativeConsentRequirement(
                    it,
                    if (it == READ_CONSENT) "Read Home Assistant state" else "Control Home Assistant entity",
                    if (it == READ_CONSENT) {
                        "Allows bounded entity metadata and non-protected state reads."
                    } else {
                        "Authorizes this exact Home Assistant entity service call."
                    }
                )
            )
        }.orEmpty(),
        timeoutMillis = TOOL_TIMEOUT_MILLIS,
        idempotency = idempotency,
        availability = AgentNativeToolAvailability.AVAILABLE
    )

    private fun entityValue(entity: HomeAssistantEntityState): AgentNativeJsonObject = mapOf(
        "entity_id" to entity.entityId.take(MAX_ENTITY_ID_CHARACTERS),
        "friendly_name" to entity.friendlyName.take(MAX_FRIENDLY_NAME_CHARACTERS),
        "state" to if (entity.protected) "protected" else entity.state.take(MAX_STATE_CHARACTERS),
        "domain" to entity.domain.take(MAX_NAME_CHARACTERS),
        "protected" to entity.protected
    )

    private fun failure(code: String, message: String, retryable: Boolean) =
        AgentNativeToolExecutionResult.failure(
            code = code.ifBlank { "home_assistant_request_failed" }.take(96),
            message = message.ifBlank { "Home Assistant request failed" }.take(MAX_ERROR_CHARACTERS),
            retryable = retryable
        )

    private fun secretSafeMetadata(): AgentNativeJsonObject = mapOf(
        "credentials_exposed" to false,
        "base_url_exposed" to false,
        "access_token_exposed" to false
    )

    private fun requiresAlwaysConfirmation(
        entityId: String,
        serviceDomain: String,
        service: String
    ): Boolean {
        val cleanEntity = entityId.trim().lowercase(Locale.US)
        val entityDomain = cleanEntity.substringBefore('.', "")
        val identity = "$cleanEntity ${serviceDomain.lowercase(Locale.US)} ${service.lowercase(Locale.US)}"
        return entityDomain in ALWAYS_CONFIRM_DOMAINS ||
            service.lowercase(Locale.US) in ALWAYS_CONFIRM_SERVICES ||
            ALWAYS_CONFIRM_IDENTITY_TERMS.any(identity::contains)
    }

    private object ServiceCallValidator : AgentNativeToolValidator {
        override fun validate(schema: AgentNativeJsonSchema, value: Any?): AgentNativeValidationResult {
            val base = AgentNativeJsonSchemaValidator.validate(schema, value)
            if (!base.isValid) return base
            val input = value as? Map<*, *> ?: return base
            val serviceDomain = input["service_domain"]?.toString().orEmpty()
            val service = input["service"]?.toString().orEmpty()
            val entityId = input["entity_id"]?.toString().orEmpty()
            val entityDomain = entityId.substringBefore('.', "")
            val issues = mutableListOf<AgentNativeValidationIssue>()
            if (serviceDomain == "homeassistant" && service !in GENERIC_ENTITY_SERVICES) {
                issues += issue("$.service", "unsupported_core_service", "Only entity-scoped Home Assistant core services are allowed")
            } else if (serviceDomain != "homeassistant" && serviceDomain != entityDomain) {
                issues += issue("$.service_domain", "domain_mismatch", "Service domain must match the target entity")
            }
            if (serviceDomain == "homeassistant" && service in BLOCKED_ADMIN_SERVICES) {
                issues += issue("$.service", "administrative_service", "Administrative services are not exposed")
            }
            val serviceData = input["service_data"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            if (serviceData.size > MAX_SERVICE_DATA_ENTRIES) {
                issues += issue("$.service_data", "max_properties", "Too many Home Assistant service parameters")
            }
            if (!boundedServiceData(serviceData) || AgentNativeJsonCodec.stringify(serviceData).length > MAX_SERVICE_DATA_CHARACTERS) {
                issues += issue("$.service_data", "unbounded_service_data", "Service data exceeds the bounded JSON policy")
            }
            return AgentNativeValidationResult(base.issues + issues)
        }
    }

    private fun boundedServiceData(value: Any?, depth: Int = 0): Boolean {
        if (depth > MAX_SERVICE_DATA_DEPTH) return false
        return when (value) {
            null, is Boolean, is Number -> true
            is String -> value.length <= MAX_SERVICE_VALUE_CHARACTERS
            is Map<*, *> -> value.size <= MAX_SERVICE_DATA_ENTRIES && value.all { (key, item) ->
                key is String && NAME_REGEX.matches(key) && key !in SECRET_PARAMETER_NAMES &&
                    boundedServiceData(item, depth + 1)
            }
            is Iterable<*> -> value.count() <= MAX_SERVICE_ARRAY_ITEMS &&
                value.all { boundedServiceData(it, depth + 1) }
            is Array<*> -> value.size <= MAX_SERVICE_ARRAY_ITEMS &&
                value.all { boundedServiceData(it, depth + 1) }
            else -> false
        }
    }

    private fun issue(path: String, code: String, message: String) =
        AgentNativeValidationIssue(path, code, message)

    private fun connectionOutputSchema() = objectSchema(
        properties = mapOf(
            "connected" to AgentNativeJsonSchema.boolean(),
            "credentials_exposed" to AgentNativeJsonSchema.boolean(),
            "checked_at_epoch_ms" to AgentNativeJsonSchema.integer(minimum = 0)
        ),
        required = setOf("connected", "credentials_exposed", "checked_at_epoch_ms")
    )

    private fun entityListOutputSchema() = objectSchema(
        properties = mapOf(
            "entities" to AgentNativeJsonSchema.array(entitySchema(), maxItems = MAX_ENTITY_RESULTS),
            "result_count" to AgentNativeJsonSchema.integer(0, MAX_ENTITY_RESULTS.toLong()),
            "total_matched" to AgentNativeJsonSchema.integer(minimum = 0),
            "truncated" to AgentNativeJsonSchema.boolean(),
            "observed_at_epoch_ms" to AgentNativeJsonSchema.integer(minimum = 0),
            "protected_state_count" to AgentNativeJsonSchema.integer(0, MAX_ENTITY_RESULTS.toLong())
        ),
        required = setOf(
            "entities",
            "result_count",
            "total_matched",
            "truncated",
            "observed_at_epoch_ms",
            "protected_state_count"
        )
    )

    private fun entityReadOutputSchema() = objectSchema(
        properties = mapOf(
            "entity" to entitySchema(),
            "observed_at_epoch_ms" to AgentNativeJsonSchema.integer(minimum = 0)
        ),
        required = setOf("entity", "observed_at_epoch_ms")
    )

    private fun entitySchema() = objectSchema(
        properties = mapOf(
            "entity_id" to entityIdSchema(),
            "friendly_name" to AgentNativeJsonSchema.string(maxLength = MAX_FRIENDLY_NAME_CHARACTERS),
            "state" to AgentNativeJsonSchema.string(maxLength = MAX_STATE_CHARACTERS),
            "domain" to AgentNativeJsonSchema.string(pattern = NAME_PATTERN, maxLength = MAX_NAME_CHARACTERS),
            "protected" to AgentNativeJsonSchema.boolean()
        ),
        required = setOf("entity_id", "friendly_name", "state", "domain", "protected")
    )

    private fun serviceOutputSchema() = objectSchema(
        properties = mapOf(
            "request_accepted" to AgentNativeJsonSchema.boolean(),
            "service_domain" to AgentNativeJsonSchema.string(pattern = NAME_PATTERN, maxLength = MAX_NAME_CHARACTERS),
            "service" to AgentNativeJsonSchema.string(pattern = NAME_PATTERN, maxLength = MAX_NAME_CHARACTERS),
            "entity_id" to entityIdSchema(),
            "verification_supported" to AgentNativeJsonSchema.boolean(),
            "controller_state_observed" to AgentNativeJsonSchema.boolean(),
            "controller_state_verified" to AgentNativeJsonSchema.boolean(),
            "previous_state" to AgentNativeJsonSchema.string(maxLength = MAX_STATE_CHARACTERS),
            "current_state" to AgentNativeJsonSchema.string(maxLength = MAX_STATE_CHARACTERS),
            "state_protected" to AgentNativeJsonSchema.boolean(),
            "changed_state_count" to AgentNativeJsonSchema.integer(minimum = 0),
            "physical_outcome_verified" to AgentNativeJsonSchema.boolean()
        ),
        required = setOf(
            "request_accepted",
            "service_domain",
            "service",
            "entity_id",
            "verification_supported",
            "controller_state_observed",
            "controller_state_verified",
            "previous_state",
            "current_state",
            "state_protected",
            "changed_state_count",
            "physical_outcome_verified"
        )
    )

    private fun entityIdSchema() = AgentNativeJsonSchema.string(
        pattern = ENTITY_ID_PATTERN,
        minLength = 3,
        maxLength = MAX_ENTITY_ID_CHARACTERS
    )

    private fun objectSchema(
        properties: Map<String, AgentNativeJsonSchema> = emptyMap(),
        required: Set<String> = emptySet()
    ) = AgentNativeJsonSchema.objectSchema(
        properties = properties,
        required = required,
        additionalProperties = false
    )

    private fun AgentNativeJsonObject.string(key: String): String =
        this[key]?.toString().orEmpty().trim().lowercase(Locale.US)

    private fun AgentNativeJsonObject.int(key: String, fallback: Int, minimum: Int, maximum: Int): Int =
        ((this[key] as? Number)?.toInt() ?: fallback).coerceIn(minimum, maximum)

    private fun AgentNativeJsonObject.stringSet(key: String, maximum: Int): Set<String> =
        ((this[key] as? Iterable<*>)?.filterIsInstance<String>().orEmpty())
            .asSequence()
            .map { it.trim().lowercase(Locale.US) }
            .filter(NAME_REGEX::matches)
            .distinct()
            .take(maximum)
            .toCollection(linkedSetOf())

    private fun AgentNativeJsonObject.objectValue(key: String): AgentNativeJsonObject =
        (this[key] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (name, value) -> (name as? String)?.let { it to value } }
            ?.toMap()
            .orEmpty()

    private val NAME_REGEX = Regex(NAME_PATTERN)
    private val ENTITY_ID_REGEX = Regex(ENTITY_ID_PATTERN)

    private val GENERIC_ENTITY_SERVICES = setOf("turn_on", "turn_off", "toggle", "update_entity")
    private val BLOCKED_ADMIN_SERVICES = setOf(
        "check_config", "clear", "delete", "purge", "reload", "remove", "restart", "stop"
    )
    private val SECRET_PARAMETER_NAMES = setOf(
        "access_token", "api_key", "code", "password", "pin", "secret", "token"
    )
    private val ALWAYS_CONFIRM_DOMAINS = setOf(
        "alarm_control_panel", "automation", "camera", "lock", "script", "siren", "valve"
    )
    private val ALWAYS_CONFIRM_SERVICES = setOf(
        "alarm_arm_away", "alarm_arm_home", "alarm_arm_night", "alarm_disarm", "alarm_trigger", "unlock"
    )
    private val ALWAYS_CONFIRM_IDENTITY_TERMS = setOf(
        "alarm", "door", "gate", "garage", "lock", "security", "siren"
    )

    private const val VERSION = "1.0.0"
    private const val EXECUTOR_ID = "signalasi.android_home_assistant_tools"
    private const val TOOL_TIMEOUT_MILLIS = 16_000L
    private const val DEFAULT_ENTITY_LIMIT = 40
    private const val MAX_DOMAIN_FILTERS = 16
    private const val MAX_QUERY_CHARACTERS = 200
    private const val MAX_NAME_CHARACTERS = 64
    private const val MAX_ENTITY_ID_CHARACTERS = 160
    private const val MAX_FRIENDLY_NAME_CHARACTERS = 100
    private const val MAX_STATE_CHARACTERS = 100
    private const val MAX_ERROR_CHARACTERS = 240
    private const val MAX_SERVICE_DATA_DEPTH = 2
    private const val MAX_SERVICE_ARRAY_ITEMS = 32
    private const val MAX_SERVICE_VALUE_CHARACTERS = 1_024
    private const val NAME_PATTERN = "[a-z_][a-z0-9_]*"
    private const val ENTITY_ID_PATTERN = "[a-z_][a-z0-9_]*\\.[a-z0-9_]+"
}
