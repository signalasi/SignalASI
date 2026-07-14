package com.signalasi.chat

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

typealias AgentNativeJsonObject = Map<String, Any?>

enum class AgentNativeToolLocation(val wireValue: String) {
    PHONE("phone"),
    APPLICATION("application"),
    ANDROID_SYSTEM("android_system"),
    ACCESSIBILITY_SERVICE("accessibility_service"),
    UNKNOWN("unknown")
}

enum class AgentNativeToolRisk(val wireValue: String, val weight: Int) {
    LOW("low", 1),
    MEDIUM("medium", 2),
    HIGH("high", 3),
    BLOCKED("blocked", 4)
}

enum class AgentNativeToolIdempotency(val wireValue: String) {
    NON_IDEMPOTENT("non_idempotent"),
    IDEMPOTENT("idempotent"),
    IDEMPOTENCY_KEY_REQUIRED("idempotency_key_required")
}

enum class AgentNativeToolAvailabilityStatus(val wireValue: String) {
    AVAILABLE("available"),
    REQUIRES_SETUP("requires_setup"),
    UNAVAILABLE("unavailable")
}

data class AgentNativeToolAvailability(
    val status: AgentNativeToolAvailabilityStatus,
    val reason: String = "",
    val checkedAtEpochMillis: Long? = null
) {
    companion object {
        val AVAILABLE = AgentNativeToolAvailability(AgentNativeToolAvailabilityStatus.AVAILABLE)
    }
}

data class AgentNativePermissionRequirement(
    val id: String,
    val title: String = id,
    val description: String = "",
    val required: Boolean = true
) {
    init {
        require(id.isNotBlank()) { "Permission id must not be blank" }
        require(title.isNotBlank()) { "Permission title must not be blank" }
    }
}

data class AgentNativeConsentRequirement(
    val id: String,
    val title: String = id,
    val description: String = "",
    val required: Boolean = true
) {
    init {
        require(id.isNotBlank()) { "Consent id must not be blank" }
        require(title.isNotBlank()) { "Consent title must not be blank" }
    }
}

data class AgentNativeJsonSchema(val document: AgentNativeJsonObject) {
    init {
        require(AgentNativeJsonCodec.isCompatible(document)) {
            "JSON schema must contain only JSON-compatible values"
        }
    }

    companion object {
        fun objectSchema(
            properties: Map<String, AgentNativeJsonSchema> = emptyMap(),
            required: Set<String> = emptySet(),
            additionalProperties: Boolean = true,
            title: String = "",
            description: String = ""
        ): AgentNativeJsonSchema {
            require(required.all(properties::containsKey)) {
                "Every required field must be declared in properties"
            }
            val value = linkedMapOf<String, Any?>(
                "type" to "object",
                "properties" to properties.mapValues { it.value.document },
                "required" to required.sorted(),
                "additionalProperties" to additionalProperties
            )
            if (title.isNotBlank()) value["title"] = title
            if (description.isNotBlank()) value["description"] = description
            return AgentNativeJsonSchema(value)
        }

        fun array(
            items: AgentNativeJsonSchema,
            minItems: Int? = null,
            maxItems: Int? = null,
            description: String = ""
        ): AgentNativeJsonSchema {
            require(minItems == null || minItems >= 0) { "minItems must be non-negative" }
            require(maxItems == null || maxItems >= 0) { "maxItems must be non-negative" }
            require(minItems == null || maxItems == null || minItems <= maxItems) {
                "minItems must not exceed maxItems"
            }
            val value = linkedMapOf<String, Any?>("type" to "array", "items" to items.document)
            minItems?.let { value["minItems"] = it }
            maxItems?.let { value["maxItems"] = it }
            if (description.isNotBlank()) value["description"] = description
            return AgentNativeJsonSchema(value)
        }

        fun string(
            enumValues: Collection<String> = emptyList(),
            minLength: Int? = null,
            maxLength: Int? = null,
            pattern: String = "",
            description: String = ""
        ): AgentNativeJsonSchema {
            require(minLength == null || minLength >= 0) { "minLength must be non-negative" }
            require(maxLength == null || maxLength >= 0) { "maxLength must be non-negative" }
            require(minLength == null || maxLength == null || minLength <= maxLength) {
                "minLength must not exceed maxLength"
            }
            if (pattern.isNotBlank()) Regex(pattern)
            val value = linkedMapOf<String, Any?>("type" to "string")
            if (enumValues.isNotEmpty()) value["enum"] = enumValues.distinct()
            minLength?.let { value["minLength"] = it }
            maxLength?.let { value["maxLength"] = it }
            if (pattern.isNotBlank()) value["pattern"] = pattern
            if (description.isNotBlank()) value["description"] = description
            return AgentNativeJsonSchema(value)
        }

        fun integer(
            minimum: Long? = null,
            maximum: Long? = null,
            description: String = ""
        ): AgentNativeJsonSchema = numericSchema("integer", minimum, maximum, description)

        fun number(
            minimum: Number? = null,
            maximum: Number? = null,
            description: String = ""
        ): AgentNativeJsonSchema = numericSchema("number", minimum, maximum, description)

        fun boolean(description: String = ""): AgentNativeJsonSchema {
            val value = linkedMapOf<String, Any?>("type" to "boolean")
            if (description.isNotBlank()) value["description"] = description
            return AgentNativeJsonSchema(value)
        }

        fun any(description: String = ""): AgentNativeJsonSchema = AgentNativeJsonSchema(
            if (description.isBlank()) emptyMap() else mapOf("description" to description)
        )

        private fun numericSchema(
            type: String,
            minimum: Number?,
            maximum: Number?,
            description: String
        ): AgentNativeJsonSchema {
            require(minimum == null || maximum == null || minimum.toDouble() <= maximum.toDouble()) {
                "minimum must not exceed maximum"
            }
            val value = linkedMapOf<String, Any?>("type" to type)
            minimum?.let { value["minimum"] = it }
            maximum?.let { value["maximum"] = it }
            if (description.isNotBlank()) value["description"] = description
            return AgentNativeJsonSchema(value)
        }
    }
}

data class AgentNativeValidationIssue(
    val path: String,
    val code: String,
    val message: String
)

data class AgentNativeValidationResult(val issues: List<AgentNativeValidationIssue> = emptyList()) {
    val isValid: Boolean get() = issues.isEmpty()

    companion object {
        val VALID = AgentNativeValidationResult()

        fun invalid(path: String, code: String, message: String) = AgentNativeValidationResult(
            listOf(AgentNativeValidationIssue(path, code, message))
        )
    }
}

fun interface AgentNativeToolValidator {
    fun validate(schema: AgentNativeJsonSchema, value: Any?): AgentNativeValidationResult
}

object AgentNativeJsonSchemaValidator : AgentNativeToolValidator {
    override fun validate(schema: AgentNativeJsonSchema, value: Any?): AgentNativeValidationResult {
        if (!AgentNativeJsonCodec.isCompatible(value)) {
            return AgentNativeValidationResult.invalid(
                path = "$",
                code = "invalid_json_value",
                message = "Value contains a type that JSON cannot represent"
            )
        }
        val issues = mutableListOf<AgentNativeValidationIssue>()
        validateNode(schema.document, value, "$", issues)
        return AgentNativeValidationResult(issues)
    }

    private fun validateNode(
        schema: Map<String, Any?>,
        value: Any?,
        path: String,
        issues: MutableList<AgentNativeValidationIssue>
    ) {
        val expectedTypes = when (val type = schema["type"]) {
            is String -> listOf(type)
            is Iterable<*> -> type.filterIsInstance<String>()
            else -> emptyList()
        }
        if (expectedTypes.isNotEmpty() && expectedTypes.none { matchesType(it, value) }) {
            issues += AgentNativeValidationIssue(
                path,
                "type_mismatch",
                "Expected ${expectedTypes.joinToString(" or ")}, received ${jsonTypeOf(value)}"
            )
            return
        }

        val enumValues = (schema["enum"] as? Iterable<*>)?.toList().orEmpty()
        if (enumValues.isNotEmpty() && enumValues.none { jsonEquals(it, value) }) {
            issues += AgentNativeValidationIssue(path, "not_in_enum", "Value is not one of the allowed values")
        }

        when (value) {
            is Map<*, *> -> validateObject(schema, value, path, issues)
            is Iterable<*> -> validateArray(schema, value.toList(), path, issues)
            is Array<*> -> validateArray(schema, value.toList(), path, issues)
            is String -> validateString(schema, value, path, issues)
            is Number -> validateNumber(schema, value, path, issues)
        }
    }

    private fun validateObject(
        schema: Map<String, Any?>,
        value: Map<*, *>,
        path: String,
        issues: MutableList<AgentNativeValidationIssue>
    ) {
        val properties = stringKeyedMap(schema["properties"])
        val required = (schema["required"] as? Iterable<*>)?.filterIsInstance<String>().orEmpty()
        required.filterNot(value::containsKey).forEach { name ->
            issues += AgentNativeValidationIssue(
                childPath(path, name),
                "required",
                "Required property is missing"
            )
        }
        value.forEach { (rawName, propertyValue) ->
            val name = rawName as? String ?: return@forEach
            val propertySchema = stringKeyedMap(properties[name])
            when {
                propertySchema.isNotEmpty() -> validateNode(
                    propertySchema,
                    propertyValue,
                    childPath(path, name),
                    issues
                )
                schema["additionalProperties"] == false -> issues += AgentNativeValidationIssue(
                    childPath(path, name),
                    "additional_property",
                    "Additional properties are not allowed"
                )
                stringKeyedMap(schema["additionalProperties"]).isNotEmpty() -> validateNode(
                    stringKeyedMap(schema["additionalProperties"]),
                    propertyValue,
                    childPath(path, name),
                    issues
                )
            }
        }
    }

    private fun validateArray(
        schema: Map<String, Any?>,
        value: List<*>,
        path: String,
        issues: MutableList<AgentNativeValidationIssue>
    ) {
        val minItems = (schema["minItems"] as? Number)?.toInt()
        val maxItems = (schema["maxItems"] as? Number)?.toInt()
        if (minItems != null && value.size < minItems) {
            issues += AgentNativeValidationIssue(path, "min_items", "Expected at least $minItems items")
        }
        if (maxItems != null && value.size > maxItems) {
            issues += AgentNativeValidationIssue(path, "max_items", "Expected at most $maxItems items")
        }
        val itemSchema = stringKeyedMap(schema["items"])
        if (itemSchema.isNotEmpty()) {
            value.forEachIndexed { index, item ->
                validateNode(itemSchema, item, "$path[$index]", issues)
            }
        }
    }

    private fun validateString(
        schema: Map<String, Any?>,
        value: String,
        path: String,
        issues: MutableList<AgentNativeValidationIssue>
    ) {
        val minLength = (schema["minLength"] as? Number)?.toInt()
        val maxLength = (schema["maxLength"] as? Number)?.toInt()
        if (minLength != null && value.length < minLength) {
            issues += AgentNativeValidationIssue(path, "min_length", "Expected at least $minLength characters")
        }
        if (maxLength != null && value.length > maxLength) {
            issues += AgentNativeValidationIssue(path, "max_length", "Expected at most $maxLength characters")
        }
        val pattern = schema["pattern"] as? String
        if (!pattern.isNullOrBlank() && !Regex(pattern).containsMatchIn(value)) {
            issues += AgentNativeValidationIssue(path, "pattern", "Value does not match the required pattern")
        }
    }

    private fun validateNumber(
        schema: Map<String, Any?>,
        value: Number,
        path: String,
        issues: MutableList<AgentNativeValidationIssue>
    ) {
        val number = value.toDouble()
        val minimum = (schema["minimum"] as? Number)?.toDouble()
        val maximum = (schema["maximum"] as? Number)?.toDouble()
        if (minimum != null && number < minimum) {
            issues += AgentNativeValidationIssue(path, "minimum", "Value must be at least $minimum")
        }
        if (maximum != null && number > maximum) {
            issues += AgentNativeValidationIssue(path, "maximum", "Value must be at most $maximum")
        }
    }

    private fun matchesType(type: String, value: Any?): Boolean = when (type) {
        "null" -> value == null
        "object" -> value is Map<*, *> && value.keys.all { it is String }
        "array" -> value is Iterable<*> || value is Array<*>
        "string" -> value is String
        "boolean" -> value is Boolean
        "number" -> value is Number && value.toDouble().isFinite()
        "integer" -> value is Byte || value is Short || value is Int || value is Long ||
            (value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0)
        else -> false
    }

    private fun jsonTypeOf(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> "object"
        is Iterable<*>, is Array<*> -> "array"
        is String -> "string"
        is Boolean -> "boolean"
        is Number -> if (matchesType("integer", value)) "integer" else "number"
        else -> value::class.java.simpleName
    }

    private fun jsonEquals(left: Any?, right: Any?): Boolean = when {
        left is Number && right is Number -> left.toDouble() == right.toDouble()
        else -> left == right
    }

    private fun childPath(parent: String, child: String): String =
        if (child.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) "$parent.$child" else "$parent['$child']"

    private fun stringKeyedMap(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.mapNotNull { (key, item) ->
            (key as? String)?.let { it to item }
        }?.toMap().orEmpty()
}

data class AgentNativeToolDescriptor(
    val id: String,
    val version: String,
    val title: String,
    val description: String,
    val location: AgentNativeToolLocation,
    val inputSchema: AgentNativeJsonSchema,
    val outputSchema: AgentNativeJsonSchema,
    val risk: AgentNativeToolRisk,
    val capabilities: Set<String> = emptySet(),
    val requiredPermissions: List<AgentNativePermissionRequirement> = emptyList(),
    val requiredConsents: List<AgentNativeConsentRequirement> = emptyList(),
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val idempotency: AgentNativeToolIdempotency = AgentNativeToolIdempotency.NON_IDEMPOTENT,
    val availability: AgentNativeToolAvailability = AgentNativeToolAvailability.AVAILABLE
) {
    init {
        require(ID_PATTERN.matches(id)) {
            "Tool id must be a stable lowercase dotted identifier: $id"
        }
        require(VERSION_PATTERN.matches(version)) { "Tool version must be semantic: $version" }
        require(title.isNotBlank()) { "Tool title must not be blank" }
        require(description.isNotBlank()) { "Tool description must not be blank" }
        require(timeoutMillis > 0) { "Tool timeout must be positive" }
        require(capabilities.none(String::isBlank)) { "Capability ids must not be blank" }
        require(requiredPermissions.map { it.id }.distinct().size == requiredPermissions.size) {
            "Permission ids must be unique"
        }
        require(requiredConsents.map { it.id }.distinct().size == requiredConsents.size) {
            "Consent ids must be unique"
        }
    }

    internal fun catalogValue(): AgentNativeJsonObject = linkedMapOf(
        "id" to id,
        "version" to version,
        "title" to title,
        "description" to description,
        "location" to location.wireValue,
        "input_schema" to inputSchema.document,
        "output_schema" to outputSchema.document,
        "risk" to risk.wireValue,
        "capabilities" to capabilities.sorted(),
        "required_permissions" to requiredPermissions.map { requirement ->
            linkedMapOf(
                "id" to requirement.id,
                "title" to requirement.title,
                "description" to requirement.description,
                "required" to requirement.required
            )
        },
        "required_consents" to requiredConsents.map { requirement ->
            linkedMapOf(
                "id" to requirement.id,
                "title" to requirement.title,
                "description" to requirement.description,
                "required" to requirement.required
            )
        },
        "timeout_ms" to timeoutMillis,
        "idempotency" to idempotency.wireValue,
        "availability" to linkedMapOf(
            "status" to availability.status.wireValue,
            "reason" to availability.reason,
            "checked_at_epoch_ms" to availability.checkedAtEpochMillis
        )
    )

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        private val ID_PATTERN = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
        private val VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?")
    }
}

data class AgentNativeToolInvocationContext(
    val invocationId: String = UUID.randomUUID().toString(),
    val sessionId: String = "",
    val conversationId: String = "",
    val turnId: String = "",
    val callerId: String = "signalasi.mobile_agent",
    val requestedAtEpochMillis: Long = System.currentTimeMillis(),
    val deadlineEpochMillis: Long? = null,
    val idempotencyKey: String? = null,
    val grantedPermissions: Set<String> = emptySet(),
    val grantedConsents: Set<String> = emptySet(),
    val attributes: Map<String, String> = emptyMap()
) {
    init {
        require(invocationId.isNotBlank()) { "Invocation id must not be blank" }
        require(callerId.isNotBlank()) { "Caller id must not be blank" }
    }
}

fun interface AgentNativeClock {
    fun nowEpochMillis(): Long

    companion object {
        val SYSTEM = AgentNativeClock(System::currentTimeMillis)
    }
}

fun interface AgentNativeToolCancellationRegistration {
    fun dispose()
}

interface AgentNativeToolCancellationToken {
    val isCancellationRequested: Boolean
    fun invokeOnCancellation(listener: () -> Unit): AgentNativeToolCancellationRegistration

    companion object {
        val NONE = object : AgentNativeToolCancellationToken {
            override val isCancellationRequested: Boolean = false
            override fun invokeOnCancellation(listener: () -> Unit) =
                AgentNativeToolCancellationRegistration { }
        }
    }
}

class AgentNativeToolCancellationSource {
    private val cancelled = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val token: AgentNativeToolCancellationToken = object : AgentNativeToolCancellationToken {
        override val isCancellationRequested: Boolean get() = cancelled.get()

        override fun invokeOnCancellation(listener: () -> Unit): AgentNativeToolCancellationRegistration {
            val active = AtomicBoolean(true)
            val guardedListener = {
                if (active.getAndSet(false)) listener()
            }
            listeners += guardedListener
            if (cancelled.get() && listeners.remove(guardedListener)) guardedListener()
            return AgentNativeToolCancellationRegistration {
                active.set(false)
                listeners.remove(guardedListener)
            }
        }
    }

    fun cancel(): Boolean {
        if (!cancelled.compareAndSet(false, true)) return false
        val pending = listeners.toList()
        listeners.clear()
        pending.forEach { it() }
        return true
    }
}

class AgentNativeToolCancelledException : RuntimeException("Native tool invocation was cancelled")
class AgentNativeToolTimeoutException : RuntimeException("Native tool invocation exceeded its deadline")

class AgentNativeToolInvocation internal constructor(
    val descriptor: AgentNativeToolDescriptor,
    val input: AgentNativeJsonObject,
    val context: AgentNativeToolInvocationContext,
    val deadlineEpochMillis: Long,
    val cancellationToken: AgentNativeToolCancellationToken,
    private val clock: AgentNativeClock
) {
    val remainingTimeMillis: Long
        get() = (deadlineEpochMillis - clock.nowEpochMillis()).coerceAtLeast(0L)

    val isCancellationRequested: Boolean get() = cancellationToken.isCancellationRequested
    val isTimedOut: Boolean get() = clock.nowEpochMillis() >= deadlineEpochMillis

    fun checkpoint() {
        if (isCancellationRequested) throw AgentNativeToolCancelledException()
        if (isTimedOut) throw AgentNativeToolTimeoutException()
    }
}

data class AgentNativeToolError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val details: AgentNativeJsonObject = emptyMap()
)

data class AgentNativeToolExecutionResult(
    val output: AgentNativeJsonObject = emptyMap(),
    val message: String = "",
    val metadata: AgentNativeJsonObject = emptyMap(),
    val error: AgentNativeToolError? = null
) {
    val isSuccess: Boolean get() = error == null

    companion object {
        fun success(
            output: AgentNativeJsonObject = emptyMap(),
            message: String = "",
            metadata: AgentNativeJsonObject = emptyMap()
        ) = AgentNativeToolExecutionResult(output, message, metadata)

        fun failure(
            code: String,
            message: String,
            retryable: Boolean = false,
            details: AgentNativeJsonObject = emptyMap()
        ) = AgentNativeToolExecutionResult(
            error = AgentNativeToolError(code, message, retryable, details)
        )
    }
}

fun interface AgentNativeToolExecutor {
    fun execute(invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult
}

enum class AgentNativeVerificationStatus(val wireValue: String) {
    PASSED("passed"),
    FAILED("failed"),
    SKIPPED("skipped")
}

data class AgentNativeToolVerification(
    val status: AgentNativeVerificationStatus,
    val message: String = "",
    val evidence: AgentNativeJsonObject = emptyMap()
)

fun interface AgentNativeToolVerifier {
    fun verify(
        invocation: AgentNativeToolInvocation,
        execution: AgentNativeToolExecutionResult
    ): AgentNativeToolVerification
}

fun interface AgentNativeToolAvailabilityProvider {
    fun current(context: AgentNativeToolInvocationContext?): AgentNativeToolAvailability
}

data class AgentNativeToolProvenance(
    val toolId: String,
    val toolVersion: String,
    val location: AgentNativeToolLocation,
    val executorId: String,
    val contractVersion: String,
    val legacyAgentActionId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

enum class AgentNativeToolResultStatus(val wireValue: String) {
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    VERIFICATION_FAILED("verification_failed"),
    REJECTED("rejected"),
    UNAVAILABLE("unavailable"),
    CANCELLED("cancelled"),
    TIMED_OUT("timed_out")
}

data class AgentNativeToolReceipt(
    val invocationId: String,
    val idempotencyKey: String?,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val durationMillis: Long,
    val status: AgentNativeToolResultStatus,
    val inputSha256: String,
    val outputSha256: String,
    val replayed: Boolean = false,
    val originalInvocationId: String? = null
)

data class AgentNativeToolResult(
    val status: AgentNativeToolResultStatus,
    val output: AgentNativeJsonObject,
    val message: String,
    val metadata: AgentNativeJsonObject,
    val error: AgentNativeToolError?,
    val verification: AgentNativeToolVerification?,
    val receipt: AgentNativeToolReceipt,
    val provenance: AgentNativeToolProvenance
) {
    val isSuccess: Boolean get() = status == AgentNativeToolResultStatus.SUCCEEDED

    fun toJson(): String = AgentNativeJsonCodec.stringify(toJsonValue())

    fun toJsonValue(): AgentNativeJsonObject = linkedMapOf(
        "status" to status.wireValue,
        "output" to output,
        "message" to message,
        "metadata" to metadata,
        "error" to error?.let {
            linkedMapOf(
                "code" to it.code,
                "message" to it.message,
                "retryable" to it.retryable,
                "details" to it.details
            )
        },
        "verification" to verification?.let {
            linkedMapOf(
                "status" to it.status.wireValue,
                "message" to it.message,
                "evidence" to it.evidence
            )
        },
        "receipt" to linkedMapOf(
            "invocation_id" to receipt.invocationId,
            "idempotency_key" to receipt.idempotencyKey,
            "started_at_epoch_ms" to receipt.startedAtEpochMillis,
            "finished_at_epoch_ms" to receipt.finishedAtEpochMillis,
            "duration_ms" to receipt.durationMillis,
            "status" to receipt.status.wireValue,
            "input_sha256" to receipt.inputSha256,
            "output_sha256" to receipt.outputSha256,
            "replayed" to receipt.replayed,
            "original_invocation_id" to receipt.originalInvocationId
        ),
        "provenance" to linkedMapOf(
            "tool_id" to provenance.toolId,
            "tool_version" to provenance.toolVersion,
            "location" to provenance.location.wireValue,
            "executor_id" to provenance.executorId,
            "contract_version" to provenance.contractVersion,
            "legacy_agent_action_id" to provenance.legacyAgentActionId,
            "metadata" to provenance.metadata
        )
    )
}

class AgentNativeToolInvocationHooks(
    val cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE,
    val onStarted: (AgentNativeToolInvocation) -> Unit = {},
    val onCancelled: (AgentNativeToolInvocation) -> Unit = {},
    val onTimeout: (AgentNativeToolInvocation) -> Unit = {},
    val onFinished: (AgentNativeToolResult) -> Unit = {}
)

class AgentNativeToolDefinition(
    val descriptor: AgentNativeToolDescriptor,
    val executor: AgentNativeToolExecutor,
    val verifier: AgentNativeToolVerifier? = null,
    val validator: AgentNativeToolValidator = AgentNativeJsonSchemaValidator,
    val executorId: String = "signalasi.phone_native",
    val provenanceMetadata: Map<String, String> = emptyMap(),
    val availabilityProvider: AgentNativeToolAvailabilityProvider =
        AgentNativeToolAvailabilityProvider { descriptor.availability }
) {
    init {
        require(executorId.isNotBlank()) { "Executor id must not be blank" }
    }
}

class AgentNativeToolRegistry(
    private val clock: AgentNativeClock = AgentNativeClock.SYSTEM
) {
    private val definitions = LinkedHashMap<String, AgentNativeToolDefinition>()
    private val replayCache = LinkedHashMap<ReplayKey, AgentNativeToolResult>()

    @Synchronized
    fun register(definition: AgentNativeToolDefinition): AgentNativeToolRegistry {
        require(!definitions.containsKey(definition.descriptor.id)) {
            "Native tool id is already registered: ${definition.descriptor.id}"
        }
        definitions[definition.descriptor.id] = definition
        return this
    }

    @Synchronized
    fun registerAll(toRegister: Iterable<AgentNativeToolDefinition>): AgentNativeToolRegistry {
        val incoming = toRegister.toList()
        val duplicateIncoming = incoming.groupingBy { it.descriptor.id }.eachCount()
            .filterValues { it > 1 }.keys
        require(duplicateIncoming.isEmpty()) {
            "Native tool ids are duplicated in registration batch: ${duplicateIncoming.sorted().joinToString()}"
        }
        val duplicateExisting = incoming.map { it.descriptor.id }.filter(definitions::containsKey)
        require(duplicateExisting.isEmpty()) {
            "Native tool ids are already registered: ${duplicateExisting.sorted().joinToString()}"
        }
        incoming.forEach { definitions[it.descriptor.id] = it }
        return this
    }

    @Synchronized
    fun lookup(id: String): AgentNativeToolDefinition? = definitions[id]

    /** Creates an independent registry view without exposing executor implementations to callers. */
    @Synchronized
    fun subset(predicate: (AgentNativeToolDescriptor) -> Boolean): AgentNativeToolRegistry =
        AgentNativeToolRegistry(clock).registerAll(
            definitions.values.filter { predicate(it.descriptor) }
        )

    @Synchronized
    fun descriptors(): List<AgentNativeToolDescriptor> = definitions.values
        .map { definition ->
            val availability = runCatching { definition.availabilityProvider.current(null) }
                .getOrElse {
                    AgentNativeToolAvailability(
                        AgentNativeToolAvailabilityStatus.UNAVAILABLE,
                        "Availability check failed: ${it.message.orEmpty()}"
                    )
                }
            definition.descriptor.copy(availability = availability)
        }
        .sortedBy { it.id }

    fun catalogJson(): String = AgentNativeJsonCodec.stringify(
        linkedMapOf(
            "contract_version" to CONTRACT_VERSION,
            "tools" to descriptors().map(AgentNativeToolDescriptor::catalogValue)
        )
    )

    fun validateInput(id: String, input: AgentNativeJsonObject): AgentNativeValidationResult {
        val definition = lookup(id) ?: return AgentNativeValidationResult.invalid(
            path = "$",
            code = "unknown_tool",
            message = "No native tool is registered with id $id"
        )
        return definition.validator.validate(definition.descriptor.inputSchema, input)
    }

    fun invoke(
        id: String,
        input: AgentNativeJsonObject,
        context: AgentNativeToolInvocationContext = AgentNativeToolInvocationContext(),
        hooks: AgentNativeToolInvocationHooks = AgentNativeToolInvocationHooks()
    ): AgentNativeToolResult {
        val definition = lookup(id)
            ?: return missingToolResult(id, input, context, hooks)
        val descriptor = definition.descriptor
        val startedAt = clock.nowEpochMillis()
        val deadline = minOf(
            context.deadlineEpochMillis ?: Long.MAX_VALUE,
            safeAdd(startedAt, descriptor.timeoutMillis)
        )
        val invocation = AgentNativeToolInvocation(
            descriptor = descriptor,
            input = input,
            context = context,
            deadlineEpochMillis = deadline,
            cancellationToken = hooks.cancellationToken,
            clock = clock
        )
        val completed = AtomicBoolean(false)
        val cancellationNotified = AtomicBoolean(false)
        val timeoutNotified = AtomicBoolean(false)
        runHook { hooks.onStarted(invocation) }
        val cancellationRegistration = hooks.cancellationToken.invokeOnCancellation {
            if (!completed.get() && cancellationNotified.compareAndSet(false, true)) {
                runHook { hooks.onCancelled(invocation) }
            }
        }

        fun finish(
            status: AgentNativeToolResultStatus,
            output: AgentNativeJsonObject = emptyMap(),
            message: String = "",
            metadata: AgentNativeJsonObject = emptyMap(),
            error: AgentNativeToolError? = null,
            verification: AgentNativeToolVerification? = null,
            replayed: Boolean = false,
            originalInvocationId: String? = null
        ): AgentNativeToolResult {
            completed.set(true)
            cancellationRegistration.dispose()
            val finishedAt = clock.nowEpochMillis()
            val result = AgentNativeToolResult(
                status = status,
                output = output,
                message = message,
                metadata = metadata,
                error = error,
                verification = verification,
                receipt = AgentNativeToolReceipt(
                    invocationId = context.invocationId,
                    idempotencyKey = context.idempotencyKey,
                    startedAtEpochMillis = startedAt,
                    finishedAtEpochMillis = finishedAt,
                    durationMillis = (finishedAt - startedAt).coerceAtLeast(0L),
                    status = status,
                    inputSha256 = digestOrEmpty(input),
                    outputSha256 = digestOrEmpty(output),
                    replayed = replayed,
                    originalInvocationId = originalInvocationId
                ),
                provenance = AgentNativeToolProvenance(
                    toolId = descriptor.id,
                    toolVersion = descriptor.version,
                    location = descriptor.location,
                    executorId = definition.executorId,
                    contractVersion = CONTRACT_VERSION,
                    legacyAgentActionId = context.attributes[LEGACY_ACTION_ID_ATTRIBUTE],
                    metadata = definition.provenanceMetadata
                )
            )
            runHook { hooks.onFinished(result) }
            return result
        }

        try {
            invocation.checkpoint()

            val availability = runCatching { definition.availabilityProvider.current(context) }
                .getOrElse {
                    AgentNativeToolAvailability(
                        AgentNativeToolAvailabilityStatus.UNAVAILABLE,
                        "Availability check failed: ${it.message.orEmpty()}"
                    )
                }
            invocation.checkpoint()
            if (availability.status != AgentNativeToolAvailabilityStatus.AVAILABLE) {
                return finish(
                    status = AgentNativeToolResultStatus.UNAVAILABLE,
                    error = AgentNativeToolError(
                        code = "tool_unavailable",
                        message = availability.reason.ifBlank { "Native tool is not currently available" },
                        retryable = availability.status == AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
                        details = mapOf("availability" to availability.status.wireValue)
                    )
                )
            }

            val inputValidation = definition.validator.validate(descriptor.inputSchema, input)
            if (!inputValidation.isValid) {
                return finish(
                    status = AgentNativeToolResultStatus.REJECTED,
                    error = AgentNativeToolError(
                        code = "invalid_input",
                        message = "Native tool input does not satisfy its JSON schema",
                        details = validationDetails(inputValidation)
                    )
                )
            }

            val missingPermissions = descriptor.requiredPermissions
                .filter { it.required && it.id !in context.grantedPermissions }
            if (missingPermissions.isNotEmpty()) {
                return finish(
                    status = AgentNativeToolResultStatus.REJECTED,
                    error = AgentNativeToolError(
                        code = "missing_permissions",
                        message = "Required phone permissions were not granted",
                        details = mapOf("permission_ids" to missingPermissions.map { it.id })
                    )
                )
            }

            val missingConsents = descriptor.requiredConsents
                .filter { it.required && it.id !in context.grantedConsents }
            if (missingConsents.isNotEmpty()) {
                return finish(
                    status = AgentNativeToolResultStatus.REJECTED,
                    error = AgentNativeToolError(
                        code = "missing_consents",
                        message = "Required user consents were not granted",
                        details = mapOf("consent_ids" to missingConsents.map { it.id })
                    )
                )
            }

            val idempotencyKey = context.idempotencyKey?.takeIf(String::isNotBlank)
            if (
                descriptor.idempotency == AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED &&
                idempotencyKey == null
            ) {
                return finish(
                    status = AgentNativeToolResultStatus.REJECTED,
                    error = AgentNativeToolError(
                        code = "missing_idempotency_key",
                        message = "This native tool requires an idempotency key"
                    )
                )
            }

            val replayKey = idempotencyKey?.takeIf {
                descriptor.idempotency != AgentNativeToolIdempotency.NON_IDEMPOTENT
            }?.let { ReplayKey(descriptor.id, descriptor.version, it) }
            val cached = replayKey?.let(::cachedResult)
            if (cached != null) {
                return finish(
                    status = cached.status,
                    output = cached.output,
                    message = cached.message,
                    metadata = cached.metadata,
                    error = cached.error,
                    verification = cached.verification,
                    replayed = true,
                    originalInvocationId = cached.receipt.originalInvocationId
                        ?: cached.receipt.invocationId
                )
            }

            val execution = definition.executor.execute(invocation)
            invocation.checkpoint()
            if (!execution.isSuccess) {
                return finish(
                    status = AgentNativeToolResultStatus.FAILED,
                    output = execution.output,
                    message = execution.message,
                    metadata = execution.metadata,
                    error = execution.error
                )
            }

            val outputValidation = definition.validator.validate(descriptor.outputSchema, execution.output)
            if (!outputValidation.isValid) {
                return finish(
                    status = AgentNativeToolResultStatus.FAILED,
                    output = execution.output,
                    message = execution.message,
                    metadata = execution.metadata,
                    error = AgentNativeToolError(
                        code = "invalid_output",
                        message = "Native tool output does not satisfy its JSON schema",
                        details = validationDetails(outputValidation)
                    )
                )
            }

            val verification = definition.verifier?.verify(invocation, execution)
            invocation.checkpoint()
            if (verification?.status == AgentNativeVerificationStatus.FAILED) {
                return finish(
                    status = AgentNativeToolResultStatus.VERIFICATION_FAILED,
                    output = execution.output,
                    message = execution.message,
                    metadata = execution.metadata,
                    error = AgentNativeToolError(
                        code = "verification_failed",
                        message = verification.message.ifBlank { "Native tool verification failed" }
                    ),
                    verification = verification
                )
            }

            val result = finish(
                status = AgentNativeToolResultStatus.SUCCEEDED,
                output = execution.output,
                message = execution.message,
                metadata = execution.metadata,
                verification = verification
            )
            if (replayKey != null) cacheResult(replayKey, result)
            return result
        } catch (_: AgentNativeToolCancelledException) {
            if (cancellationNotified.compareAndSet(false, true)) runHook { hooks.onCancelled(invocation) }
            return finish(
                status = AgentNativeToolResultStatus.CANCELLED,
                error = AgentNativeToolError("cancelled", "Native tool invocation was cancelled", retryable = true)
            )
        } catch (_: AgentNativeToolTimeoutException) {
            if (timeoutNotified.compareAndSet(false, true)) runHook { hooks.onTimeout(invocation) }
            return finish(
                status = AgentNativeToolResultStatus.TIMED_OUT,
                error = AgentNativeToolError("timeout", "Native tool invocation exceeded its deadline", retryable = true)
            )
        } catch (error: Throwable) {
            return finish(
                status = AgentNativeToolResultStatus.FAILED,
                error = AgentNativeToolError(
                    code = "tool_invocation_failed",
                    message = error.message ?: error::class.java.simpleName,
                    retryable = false,
                    details = mapOf("exception" to error::class.java.name)
                )
            )
        }
    }

    @Synchronized
    private fun cachedResult(key: ReplayKey): AgentNativeToolResult? = replayCache[key]

    @Synchronized
    private fun cacheResult(key: ReplayKey, result: AgentNativeToolResult) {
        replayCache[key] = result
    }

    private fun missingToolResult(
        id: String,
        input: AgentNativeJsonObject,
        context: AgentNativeToolInvocationContext,
        hooks: AgentNativeToolInvocationHooks
    ): AgentNativeToolResult {
        val now = clock.nowEpochMillis()
        val status = AgentNativeToolResultStatus.REJECTED
        val result = AgentNativeToolResult(
            status = status,
            output = emptyMap(),
            message = "",
            metadata = emptyMap(),
            error = AgentNativeToolError("unknown_tool", "No native tool is registered with id $id"),
            verification = null,
            receipt = AgentNativeToolReceipt(
                invocationId = context.invocationId,
                idempotencyKey = context.idempotencyKey,
                startedAtEpochMillis = now,
                finishedAtEpochMillis = now,
                durationMillis = 0,
                status = status,
                inputSha256 = digestOrEmpty(input),
                outputSha256 = digestOrEmpty(emptyMap<String, Any?>())
            ),
            provenance = AgentNativeToolProvenance(
                toolId = id,
                toolVersion = "",
                location = AgentNativeToolLocation.UNKNOWN,
                executorId = "",
                contractVersion = CONTRACT_VERSION
            )
        )
        runHook { hooks.onFinished(result) }
        return result
    }

    private fun validationDetails(result: AgentNativeValidationResult): AgentNativeJsonObject = mapOf(
        "issues" to result.issues.map {
            linkedMapOf("path" to it.path, "code" to it.code, "message" to it.message)
        }
    )

    private fun digestOrEmpty(value: Any?): String =
        runCatching { AgentNativeJsonCodec.sha256(value) }.getOrDefault("")

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun runHook(block: () -> Unit) {
        runCatching(block)
    }

    private data class ReplayKey(val id: String, val version: String, val key: String)

    companion object {
        const val CONTRACT_VERSION = "signalasi.phone-native-tools/1.0"
        const val LEGACY_ACTION_ID_ATTRIBUTE = "legacy_agent_action_id"
    }
}

data class AgentNativeToolCall(
    val toolId: String,
    val input: AgentNativeJsonObject,
    val context: AgentNativeToolInvocationContext
)

object AgentNativeToolAgentActionAdapter {
    fun defaultToolId(kind: AgentActionKind): String =
        "signalasi.agent_action.${kind.name.lowercase(Locale.ROOT).replace('_', '.')}"

    fun fromAgentAction(
        action: AgentAction,
        toolId: String = defaultToolId(action.kind),
        context: AgentNativeToolInvocationContext = AgentNativeToolInvocationContext(
            invocationId = action.id,
            attributes = mapOf(AgentNativeToolRegistry.LEGACY_ACTION_ID_ATTRIBUTE to action.id)
        )
    ): AgentNativeToolCall = AgentNativeToolCall(
        toolId = toolId,
        input = linkedMapOf(
            "target" to action.target,
            "description" to action.description,
            "parameters" to action.parameters,
            "requires_confirmation" to action.requiresConfirmation
        ),
        context = context.copy(
            attributes = context.attributes +
                (AgentNativeToolRegistry.LEGACY_ACTION_ID_ATTRIBUTE to action.id)
        )
    )

    fun toAgentAction(
        invocation: AgentNativeToolInvocation,
        kind: AgentActionKind,
        target: String = invocation.input["target"]?.toString().orEmpty(),
        description: String = invocation.input["description"]?.toString()
            ?: invocation.descriptor.description,
        parameters: Map<String, String> = defaultParameters(invocation.input)
    ): AgentAction = AgentAction(
        id = invocation.context.attributes[AgentNativeToolRegistry.LEGACY_ACTION_ID_ATTRIBUTE]
            ?: invocation.context.invocationId,
        kind = kind,
        target = target,
        risk = invocation.descriptor.risk.toLegacyRisk(),
        status = AgentActionStatus.RUNNING,
        description = description,
        parameters = parameters,
        requiresConfirmation = invocation.input["requires_confirmation"] == true ||
            invocation.descriptor.requiredConsents.any { it.required } ||
            invocation.descriptor.risk.weight >= AgentNativeToolRisk.MEDIUM.weight
    )

    fun fromAgentActionResult(result: AgentActionResult): AgentNativeToolExecutionResult {
        val output = linkedMapOf<String, Any?>(
            "action_id" to result.actionId,
            "success" to result.success,
            "message" to result.message,
            "metadata" to result.metadata
        )
        return if (result.success) {
            AgentNativeToolExecutionResult.success(output = output, message = result.message)
        } else {
            AgentNativeToolExecutionResult(
                output = output,
                message = result.message,
                error = AgentNativeToolError(
                    code = "agent_action_failed",
                    message = result.message.ifBlank { "Legacy AgentAction execution failed" }
                )
            )
        }
    }

    fun toAgentActionResult(result: AgentNativeToolResult, actionId: String): AgentActionResult =
        AgentActionResult(
            actionId = actionId,
            success = result.isSuccess,
            message = result.message.ifBlank { result.error?.message.orEmpty() },
            metadata = mapOf(
                "native_tool_id" to result.provenance.toolId,
                "native_tool_version" to result.provenance.toolVersion,
                "native_receipt_id" to result.receipt.invocationId,
                "native_status" to result.status.wireValue
            )
        )

    private fun defaultParameters(input: AgentNativeJsonObject): Map<String, String> {
        val nested = input["parameters"] as? Map<*, *>
        if (nested != null && nested.keys.all { it is String }) {
            return nested.entries.associate { (key, value) -> key as String to legacyString(value) }
        }
        return input.filterKeys { it != "target" && it != "description" }
            .mapValues { legacyString(it.value) }
    }

    private fun legacyString(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        else -> AgentNativeJsonCodec.stringify(value)
    }

    private fun AgentNativeToolRisk.toLegacyRisk(): AgentRisk = when (this) {
        AgentNativeToolRisk.LOW -> AgentRisk.LOW
        AgentNativeToolRisk.MEDIUM -> AgentRisk.MEDIUM
        AgentNativeToolRisk.HIGH -> AgentRisk.HIGH
        AgentNativeToolRisk.BLOCKED -> AgentRisk.BLOCKED
    }
}

class AgentActionNativeToolExecutor(
    private val delegate: AgentActionExecutor,
    private val screenProvider: (AgentNativeToolInvocation) -> ScreenContext,
    private val actionFactory: (AgentNativeToolInvocation) -> AgentAction
) : AgentNativeToolExecutor {
    override fun execute(invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult {
        invocation.checkpoint()
        val action = actionFactory(invocation)
        val result = delegate.execute(action, screenProvider(invocation))
        invocation.checkpoint()
        return AgentNativeToolAgentActionAdapter.fromAgentActionResult(result)
    }

    companion object {
        fun forKind(
            delegate: AgentActionExecutor,
            kind: AgentActionKind,
            screenProvider: (AgentNativeToolInvocation) -> ScreenContext,
            targetProvider: (AgentNativeToolInvocation) -> String = {
                it.input["target"]?.toString().orEmpty()
            },
            descriptionProvider: (AgentNativeToolInvocation) -> String = {
                it.input["description"]?.toString() ?: it.descriptor.description
            }
        ): AgentActionNativeToolExecutor = AgentActionNativeToolExecutor(
            delegate = delegate,
            screenProvider = screenProvider,
            actionFactory = { invocation ->
                AgentNativeToolAgentActionAdapter.toAgentAction(
                    invocation = invocation,
                    kind = kind,
                    target = targetProvider(invocation),
                    description = descriptionProvider(invocation)
                )
            }
        )
    }
}

object AgentNativeJsonCodec {
    fun stringify(value: Any?): String = buildString { appendJson(value) }

    fun isCompatible(value: Any?): Boolean = when (value) {
        null, is String, is Boolean, is Byte, is Short, is Int, is Long -> true
        is Float -> value.isFinite()
        is Double -> value.isFinite()
        is Number -> value.toDouble().isFinite()
        is Map<*, *> -> value.keys.all { it is String } && value.values.all(::isCompatible)
        is Iterable<*> -> value.all(::isCompatible)
        is Array<*> -> value.all(::isCompatible)
        else -> false
    }

    fun sha256(value: Any?): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stringify(value).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun StringBuilder.appendJson(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendQuoted(value)
            is Boolean -> append(value)
            is Byte, is Short, is Int, is Long -> append(value.toString())
            is Float -> {
                require(value.isFinite()) { "JSON numbers must be finite" }
                append(value.toString())
            }
            is Double -> {
                require(value.isFinite()) { "JSON numbers must be finite" }
                append(value.toString())
            }
            is Number -> {
                require(value.toDouble().isFinite()) { "JSON numbers must be finite" }
                append(value.toString())
            }
            is Map<*, *> -> {
                require(value.keys.all { it is String }) { "JSON object keys must be strings" }
                append('{')
                value.entries.sortedBy { it.key as String }.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendQuoted(entry.key as String)
                    append(':')
                    appendJson(entry.value)
                }
                append('}')
            }
            is Iterable<*> -> appendArray(value.toList())
            is Array<*> -> appendArray(value.toList())
            else -> throw IllegalArgumentException("Unsupported JSON value: ${value::class.java.name}")
        }
    }

    private fun StringBuilder.appendArray(values: List<*>) {
        append('[')
        values.forEachIndexed { index, item ->
            if (index > 0) append(',')
            appendJson(item)
        }
        append(']')
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
