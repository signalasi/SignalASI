package com.signalasi.chat

import java.util.concurrent.ConcurrentHashMap

/**
 * Projects installed deterministic Skills into the host-owned native tool contract.
 * Skill metadata selects a workflow; every concrete step is still resolved and enforced
 * by the current native-tool registry at execution time.
 */
class GlobalAutonomousSkillHost(
    private val runtimeProvider: (Set<String>) -> AgentSkillRuntime
) {
    private val executionRegistries = ConcurrentHashMap<String, AgentNativeToolRegistry>()

    fun descriptors(nativeRegistry: AgentNativeToolRegistry): List<AgentNativeToolDescriptor> =
        snapshots(nativeRegistry).map(SkillSnapshot::descriptor).sortedBy(AgentNativeToolDescriptor::id)

    fun isSkillToolId(toolId: String): Boolean = toolId.startsWith(TOOL_ID_PREFIX)

    fun descriptor(
        toolId: String,
        nativeRegistry: AgentNativeToolRegistry
    ): AgentNativeToolDescriptor? = snapshot(toolId, nativeRegistry)?.descriptor

    fun validateInput(
        toolId: String,
        input: AgentNativeJsonObject,
        nativeRegistry: AgentNativeToolRegistry
    ): AgentNativeValidationResult {
        val descriptor = descriptor(toolId, nativeRegistry)
            ?: return AgentNativeValidationResult.invalid(
                path = "$",
                code = "unknown_skill",
                message = "No autonomous Skill is registered with id $toolId"
            )
        return AgentNativeJsonSchemaValidator.validate(descriptor.inputSchema, input)
    }

    fun invoke(
        toolId: String,
        input: AgentNativeJsonObject,
        nativeRegistry: AgentNativeToolRegistry,
        context: AgentNativeToolInvocationContext,
        hooks: AgentNativeToolInvocationHooks = AgentNativeToolInvocationHooks()
    ): AgentNativeToolResult {
        val snapshot = snapshot(toolId, nativeRegistry)
        if (snapshot != null) {
            executionRegistries.computeIfAbsent(toolId) {
                AgentNativeToolRegistry().register(definition(snapshot, nativeRegistry))
            }
        }
        return executionRegistries[toolId]?.invoke(toolId, input, context, hooks)
            ?: AgentNativeToolRegistry().invoke(toolId, input, context, hooks)
    }

    internal fun toolId(skillId: String, version: String): String =
        TOOL_ID_PREFIX + GlobalAgentText.stableKey(skillId, version).take(24)

    private fun snapshots(nativeRegistry: AgentNativeToolRegistry): List<SkillSnapshot> {
        val descriptors = nativeRegistry.descriptors().associateBy(AgentNativeToolDescriptor::id)
        val runtime = runtime(descriptors.keys) ?: return emptyList()
        return runCatching {
            runtime.list(enabledOnly = true).asSequence()
                .filter(AgentSkillInstallation::autoInvoke)
                .filterNot { installation ->
                    installation.manifest.steps.any { it.toolId == AGENT_ORCHESTRATION_TOOL_ID }
                }
                .map { installation -> snapshot(runtime, installation, descriptors) }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun snapshot(
        toolId: String,
        nativeRegistry: AgentNativeToolRegistry
    ): SkillSnapshot? = snapshots(nativeRegistry).firstOrNull { it.descriptor.id == toolId }

    private fun snapshot(
        runtime: AgentSkillRuntime,
        installation: AgentSkillInstallation,
        nativeDescriptors: Map<String, AgentNativeToolDescriptor>
    ): SkillSnapshot {
        val manifest = installation.manifest
        val stepDescriptors = manifest.steps.mapNotNull { nativeDescriptors[it.toolId] }
        val missingToolIds = manifest.steps.map(AgentSkillStep::toolId)
            .filterNot(nativeDescriptors::containsKey)
            .distinct()
        val unavailable = stepDescriptors.filter {
            it.availability.status != AgentNativeToolAvailabilityStatus.AVAILABLE
        }
        val availability = when {
            missingToolIds.isNotEmpty() -> AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.UNAVAILABLE,
                "Skill dependency is not registered: ${missingToolIds.joinToString()}"
            )
            unavailable.isNotEmpty() -> AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.UNAVAILABLE,
                unavailable.first().availability.reason.ifBlank {
                    "A Skill dependency is not currently available"
                }
            )
            else -> AgentNativeToolAvailability.AVAILABLE
        }
        return SkillSnapshot(
            runtime = runtime,
            installation = installation,
            descriptor = AgentNativeToolDescriptor(
                id = toolId(manifest.id, manifest.version),
                version = ADAPTER_VERSION,
                title = clean(manifest.title, 160),
                description = skillDescription(manifest),
                location = AgentNativeToolLocation.APPLICATION,
                inputSchema = manifest.parameters.toNativeSchema(),
                outputSchema = outputSchema(),
                risk = stepDescriptors.maxByOrNull { it.risk.weight }?.risk
                    ?: if (missingToolIds.isEmpty()) AgentNativeToolRisk.LOW else AgentNativeToolRisk.HIGH,
                capabilities = buildSet {
                    add("skill.workflow")
                    add("skill.${manifest.id}")
                    stepDescriptors.flatMapTo(this) { it.capabilities }
                },
                requiredPermissions = mergePermissions(stepDescriptors),
                requiredConsents = mergeConsents(stepDescriptors),
                timeoutMillis = aggregateTimeout(stepDescriptors),
                idempotency = AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
                availability = availability
            )
        )
    }

    private fun definition(
        initial: SkillSnapshot,
        nativeRegistry: AgentNativeToolRegistry
    ) = AgentNativeToolDefinition(
        descriptor = initial.descriptor,
        executor = AgentNativeToolExecutor { invocation ->
            val current = snapshot(invocation.descriptor.id, nativeRegistry)
                ?: return@AgentNativeToolExecutor AgentNativeToolExecutionResult.failure(
                    code = "skill_unavailable",
                    message = "The installed Skill is disabled, removed, or no longer eligible for autonomous use"
                )
            execute(current, nativeRegistry, invocation)
        },
        verifier = AgentNativeToolVerifier { _, execution -> verify(initial, execution) },
        executorId = "signalasi.skill_runtime",
        provenanceMetadata = mapOf(
            "skill_id" to initial.installation.id,
            "skill_version" to initial.installation.version,
            "skill_source" to initial.installation.manifest.source,
            "workflow_sha256" to GlobalAgentText.stableKey(
                AgentSkillManifestCodec.encode(initial.installation.manifest)
            )
        ),
        availabilityProvider = AgentNativeToolAvailabilityProvider {
            snapshot(initial.descriptor.id, nativeRegistry)?.descriptor?.availability
                ?: AgentNativeToolAvailability(
                    AgentNativeToolAvailabilityStatus.UNAVAILABLE,
                    "The installed Skill is disabled, removed, or no longer eligible for autonomous use"
                )
        }
    )

    private fun execute(
        snapshot: SkillSnapshot,
        nativeRegistry: AgentNativeToolRegistry,
        invocation: AgentNativeToolInvocation
    ): AgentNativeToolExecutionResult {
        val installation = snapshot.installation
        val expansion = runCatching {
            snapshot.runtime.expand(installation.id, installation.version, invocation.input)
        }.getOrElse { error ->
            return AgentNativeToolExecutionResult.failure(
                code = "skill_expansion_failed",
                message = error.message ?: "The Skill input could not be expanded"
            )
        }
        val stepResults = mutableListOf<AgentNativeJsonObject>()
        var finalOutput: AgentNativeJsonObject = emptyMap()
        expansion.orderedSteps.forEachIndexed { index, step ->
            invocation.checkpoint()
            val descriptor = nativeRegistry.descriptors().firstOrNull { it.id == step.toolId }
                ?: return failedStep(step, stepResults, "skill_dependency_missing", "Skill dependency is unavailable")
            val workspaceId = invocation.context.attributes["workspace_id"].orEmpty()
            val scopedInput = if (workspaceId.isBlank()) step.input else {
                AgentWorkspaceScope.bindToolInput(step.toolId, step.input, workspaceId)
            }
            invocation.reportProgress(
                stage = "skill_step",
                message = "${index + 1}/${expansion.orderedSteps.size}: ${descriptor.title}",
                percent = ((index * 100.0) / expansion.orderedSteps.size.coerceAtLeast(1)).toInt(),
                sequence = index.toLong()
            )
            val childResult = nativeRegistry.invoke(
                id = step.toolId,
                input = scopedInput,
                context = childContext(invocation, descriptor, step, index),
                hooks = AgentNativeToolInvocationHooks(
                    cancellationToken = invocation.cancellationToken,
                    onProgress = { _, progress ->
                        invocation.reportProgress(
                            stage = "skill_step.${step.id}.${progress.stage}",
                            message = progress.message,
                            percent = progress.percent,
                            sequence = index.toLong()
                        )
                    }
                )
            )
            val stepRecord = linkedMapOf<String, Any?>(
                "step_id" to step.id,
                "tool_id" to step.toolId,
                "status" to childResult.status.wireValue,
                "message" to childResult.message,
                "output" to childResult.output,
                "receipt" to mapOf(
                    "invocation_id" to childResult.receipt.invocationId,
                    "duration_ms" to childResult.receipt.durationMillis,
                    "input_sha256" to childResult.receipt.inputSha256,
                    "output_sha256" to childResult.receipt.outputSha256
                ),
                "provenance" to mapOf(
                    "tool_id" to childResult.provenance.toolId,
                    "tool_version" to childResult.provenance.toolVersion,
                    "executor_id" to childResult.provenance.executorId
                )
            )
            stepResults += stepRecord
            if (!childResult.isSuccess) {
                return failedStep(
                    step,
                    stepResults,
                    childResult.error?.code ?: "skill_step_failed",
                    childResult.error?.message ?: childResult.message.ifBlank { "A Skill step failed" },
                    childResult.error?.retryable == true
                )
            }
            finalOutput = childResult.output
        }
        snapshot.runtime.recordUse(installation.id, installation.version)
        return AgentNativeToolExecutionResult.success(
            output = linkedMapOf(
                "skill_id" to installation.id,
                "skill_version" to installation.version,
                "completed_steps" to stepResults.size,
                "total_steps" to expansion.orderedSteps.size,
                "steps" to stepResults,
                "final_output" to finalOutput
            ),
            message = stepResults.lastOrNull()?.get("message")?.toString().orEmpty()
                .ifBlank { "${clean(installation.manifest.title, 160)} completed" },
            metadata = mapOf("execution_contract" to "host_validated_skill_v1")
        )
    }

    private fun failedStep(
        step: AgentSkillExpandedStep,
        completed: List<AgentNativeJsonObject>,
        code: String,
        message: String,
        retryable: Boolean = false
    ) = AgentNativeToolExecutionResult(
        output = mapOf(
            "completed_steps" to completed.size,
            "steps" to completed
        ),
        error = AgentNativeToolError(
            code = code,
            message = message,
            retryable = retryable,
            details = mapOf("step_id" to step.id, "tool_id" to step.toolId)
        )
    )

    private fun childContext(
        parent: AgentNativeToolInvocation,
        descriptor: AgentNativeToolDescriptor,
        step: AgentSkillExpandedStep,
        index: Int
    ) = AgentNativeToolInvocationContext(
        invocationId = "${parent.context.invocationId}:skill:${index + 1}",
        sessionId = parent.context.sessionId,
        conversationId = parent.context.conversationId,
        turnId = parent.context.turnId,
        callerId = "signalasi.global_super_agent.skill",
        requestedAtEpochMillis = parent.context.requestedAtEpochMillis,
        deadlineEpochMillis = parent.deadlineEpochMillis,
        idempotencyKey = if (descriptor.idempotency == AgentNativeToolIdempotency.NON_IDEMPOTENT) {
            null
        } else {
            "${parent.context.idempotencyKey ?: parent.context.invocationId}:${step.id}"
        },
        grantedPermissions = descriptor.requiredPermissions
            .filter { it.required && it.id in parent.context.grantedPermissions }
            .mapTo(linkedSetOf(), AgentNativePermissionRequirement::id),
        grantedConsents = descriptor.requiredConsents
            .filter { it.required && it.id in parent.context.grantedConsents }
            .mapTo(linkedSetOf(), AgentNativeConsentRequirement::id),
        attributes = parent.context.attributes + mapOf(
            "parent_skill_id" to parent.descriptor.id,
            "skill_step_id" to step.id,
            "skill_step_index" to index.toString()
        )
    )

    private fun verify(
        snapshot: SkillSnapshot,
        execution: AgentNativeToolExecutionResult
    ): AgentNativeToolVerification {
        val completed = (execution.output["completed_steps"] as? Number)?.toInt() ?: -1
        val total = (execution.output["total_steps"] as? Number)?.toInt() ?: -1
        val steps = execution.output["steps"] as? List<*>
        val valid = execution.isSuccess && total > 0 && completed == total && steps?.size == total
        return AgentNativeToolVerification(
            status = if (valid) AgentNativeVerificationStatus.PASSED else AgentNativeVerificationStatus.FAILED,
            message = if (valid) "Every Skill step completed with a native receipt" else "Skill step evidence is incomplete",
            evidence = mapOf(
                "skill_id" to snapshot.installation.id,
                "skill_version" to snapshot.installation.version,
                "completed_steps" to completed,
                "total_steps" to total
            )
        )
    }

    private fun runtime(nativeToolIds: Set<String>): AgentSkillRuntime? = runCatching {
        runtimeProvider(nativeToolIds + AGENT_ORCHESTRATION_TOOL_ID)
    }.getOrNull()

    private fun skillDescription(manifest: AgentSkillManifest): String = buildString {
        append("Installed host-validated Skill workflow. ")
        append(clean(manifest.description.ifBlank { manifest.instructions }, 800))
        if (manifest.triggerExamples.isNotEmpty()) {
            append(" Relevant requests: ")
            append(manifest.triggerExamples.take(8).joinToString("; ") { clean(it, 160) })
        }
    }.take(1_800)

    private fun clean(value: String, maximum: Int): String = value
        .replace(Regex("[\\u0000-\\u001f\\u007f]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maximum)

    private fun mergePermissions(
        descriptors: List<AgentNativeToolDescriptor>
    ): List<AgentNativePermissionRequirement> = descriptors.flatMap { it.requiredPermissions }
        .groupBy(AgentNativePermissionRequirement::id)
        .map { (_, requirements) ->
            requirements.first().copy(required = requirements.any(AgentNativePermissionRequirement::required))
        }
        .sortedBy(AgentNativePermissionRequirement::id)

    private fun mergeConsents(
        descriptors: List<AgentNativeToolDescriptor>
    ): List<AgentNativeConsentRequirement> = descriptors.flatMap { it.requiredConsents }
        .groupBy(AgentNativeConsentRequirement::id)
        .map { (_, requirements) ->
            requirements.first().copy(required = requirements.any(AgentNativeConsentRequirement::required))
        }
        .sortedBy(AgentNativeConsentRequirement::id)

    private fun aggregateTimeout(descriptors: List<AgentNativeToolDescriptor>): Long = descriptors
        .fold(0L) { total, descriptor ->
            if (total >= MAX_WORKFLOW_TIMEOUT_MILLIS - descriptor.timeoutMillis) {
                MAX_WORKFLOW_TIMEOUT_MILLIS
            } else {
                total + descriptor.timeoutMillis
            }
        }
        .coerceIn(AgentNativeToolDescriptor.DEFAULT_TIMEOUT_MILLIS, MAX_WORKFLOW_TIMEOUT_MILLIS)

    private fun AgentSkillParameterSchema.toNativeSchema(): AgentNativeJsonSchema {
        val document = linkedMapOf<String, Any?>("type" to type.wireValue)
        when (type) {
            AgentSkillParameterType.OBJECT -> {
                document["properties"] = properties.mapValues { it.value.toNativeSchema().document }
                document["required"] = required.sorted()
                document["additionalProperties"] = additionalProperties
            }
            AgentSkillParameterType.ARRAY -> {
                document["items"] = requireNotNull(items).toNativeSchema().document
                minItems?.let { document["minItems"] = it }
                maxItems?.let { document["maxItems"] = it }
            }
            AgentSkillParameterType.STRING -> {
                minLength?.let { document["minLength"] = it }
                maxLength?.let { document["maxLength"] = it }
            }
            AgentSkillParameterType.INTEGER,
            AgentSkillParameterType.NUMBER -> {
                minimum?.let { document["minimum"] = it }
                maximum?.let { document["maximum"] = it }
            }
            AgentSkillParameterType.BOOLEAN -> Unit
        }
        if (enumValues.isNotEmpty()) document["enum"] = enumValues
        return AgentNativeJsonSchema(document)
    }

    private fun outputSchema() = AgentNativeJsonSchema.objectSchema(
        properties = mapOf(
            "skill_id" to AgentNativeJsonSchema.string(minLength = 1),
            "skill_version" to AgentNativeJsonSchema.string(minLength = 1),
            "completed_steps" to AgentNativeJsonSchema.integer(minimum = 0),
            "total_steps" to AgentNativeJsonSchema.integer(minimum = 1),
            "steps" to AgentNativeJsonSchema.array(AgentNativeJsonSchema.objectSchema()),
            "final_output" to AgentNativeJsonSchema.objectSchema()
        ),
        required = setOf(
            "skill_id",
            "skill_version",
            "completed_steps",
            "total_steps",
            "steps",
            "final_output"
        ),
        additionalProperties = false
    )

    private data class SkillSnapshot(
        val runtime: AgentSkillRuntime,
        val installation: AgentSkillInstallation,
        val descriptor: AgentNativeToolDescriptor
    )

    private companion object {
        const val TOOL_ID_PREFIX = "signalasi.skill."
        const val ADAPTER_VERSION = "1.0.0"
        const val MAX_WORKFLOW_TIMEOUT_MILLIS = 15L * 60L * 1_000L
    }
}
