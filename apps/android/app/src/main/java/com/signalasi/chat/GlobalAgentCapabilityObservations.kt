package com.signalasi.chat

import java.security.MessageDigest
import java.util.Locale

/**
 * Converts durable authorization and capability state into redacted global observations.
 * Raw resource identifiers, endpoints, account fields, tokens, and device addresses never leave
 * their owning stores.
 */
object GlobalCapabilityObservationExtractor {
    fun snapshotReset(timestampMillis: Long = System.currentTimeMillis()): GlobalConversationEvent =
        GlobalConversationEvent(
            id = "capability-snapshot-reset:$timestampMillis",
            type = GlobalConversationEventType.CAPABILITY_SNAPSHOT_RESET,
            conversationId = CAPABILITY_CONVERSATION_ID,
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = timestampMillis,
            conversationTitle = CAPABILITY_TOPIC,
            metadata = mapOf(
                "origin" to "capability_reconciliation",
                "projection" to "reset_capabilities",
                "context_visibility" to GlobalWorldContextVisibility.LOCAL_ONLY.name
            )
        )

    fun authorizationMutations(
        before: Set<String>,
        after: Set<String>,
        timestampMillis: Long = System.currentTimeMillis()
    ): List<GlobalConversationEvent> = (before + after).sorted().mapNotNull { consentKey ->
        when {
            consentKey in before && consentKey in after -> null
            consentKey in after -> authorizationEvent(consentKey, granted = true, timestampMillis)
            else -> authorizationEvent(consentKey, granted = false, timestampMillis)
        }
    }

    fun safetyPolicyMutation(
        before: AgentSafetySettings?,
        after: AgentSafetySettings,
        timestampMillis: Long = System.currentTimeMillis()
    ): GlobalConversationEvent? {
        if (before == after) return null
        val stableKey = "capability:authorization:agent-safety-policy"
        val executionState = if (after.executionPaused) "paused" else "active"
        val summary = buildString {
            append("The local Agent safety policy uses ")
            append(after.permissionMode.name.lowercase(Locale.ROOT).replace('_', ' '))
            append(" mode; execution is ").append(executionState).append("; ")
            append("local actions are ").append(if (after.localActionsAllowed) "allowed" else "blocked").append("; ")
            append("connector calls are ").append(if (after.connectorCallsAllowed) "allowed" else "blocked").append("; ")
            append("device control is ").append(if (after.deviceControlAllowed) "allowed" else "blocked").append('.')
        }
        val fingerprint = GlobalAgentText.stableKey(
            after.permissionMode.name,
            after.highRiskGuard.toString(),
            after.memoryCapture.toString(),
            after.screenObservationAllowed.toString(),
            after.localActionsAllowed.toString(),
            after.connectorCallsAllowed.toString(),
            after.deviceControlAllowed.toString(),
            after.executionPaused.toString()
        )
        return GlobalConversationEvent(
            id = "capability-safety-policy:$fingerprint:$timestampMillis",
            type = GlobalConversationEventType.AUTHORIZATION_POLICY_CHANGED,
            conversationId = CAPABILITY_CONVERSATION_ID,
            messageId = "agent-safety-policy",
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = timestampMillis,
            content = summary,
            contentRef = "encrypted://agent-authorization/safety-policy",
            conversationTitle = AUTHORIZATION_TOPIC,
            topicHints = setOf(AUTHORIZATION_TOPIC),
            metadata = mapOf(
                "origin" to "agent_safety_policy",
                "permission_mode" to after.permissionMode.name.lowercase(Locale.ROOT),
                "high_risk_guard" to after.highRiskGuard.toString(),
                "memory_capture" to after.memoryCapture.toString(),
                "screen_observation_allowed" to after.screenObservationAllowed.toString(),
                "local_actions_allowed" to after.localActionsAllowed.toString(),
                "connector_calls_allowed" to after.connectorCallsAllowed.toString(),
                "device_control_allowed" to after.deviceControlAllowed.toString(),
                "execution_paused" to after.executionPaused.toString(),
                "identity_stable_key" to stableKey,
                "identity_summary" to summary,
                "identity_kind" to GlobalWorldItemKind.DECISION.name,
                "identity_layer" to GlobalWorldLayer.USER.name,
                "identity_topic" to AUTHORIZATION_TOPIC,
                "replace_stable_keys" to stableKey,
                "context_visibility" to GlobalWorldContextVisibility.LOCAL_ONLY.name,
                "projection" to "replace"
            )
        )
    }

    fun mcpMutations(
        before: List<AgentMcpConnection>,
        after: List<AgentMcpConnection>,
        timestampMillis: Long = System.currentTimeMillis()
    ): List<GlobalConversationEvent> = resourceMutations(
        before = before.associate { it.id to mcpSnapshot(it, timestampMillis) },
        after = after.associate { it.id to mcpSnapshot(it, timestampMillis) },
        timestampMillis = timestampMillis
    )

    fun agentMutations(
        before: List<AgentRegistration>,
        after: List<AgentRegistration>,
        timestampMillis: Long = System.currentTimeMillis()
    ): List<GlobalConversationEvent> = resourceMutations(
        before = before.associate { it.agentId to agentSnapshot(it) },
        after = after.associate { it.agentId to agentSnapshot(it) },
        timestampMillis = timestampMillis
    )

    fun homeAssistantMutations(
        before: HomeAssistantSettings,
        after: HomeAssistantSettings,
        timestampMillis: Long = System.currentTimeMillis()
    ): List<GlobalConversationEvent> {
        val previous = homeAssistantSnapshot(before)?.let { mapOf(HOME_ASSISTANT_ID to it) }.orEmpty()
        val current = homeAssistantSnapshot(after)?.let { mapOf(HOME_ASSISTANT_ID to it) }.orEmpty()
        return resourceMutations(previous, current, timestampMillis)
    }

    fun customDeviceMutations(
        before: List<CustomDeviceConnector>,
        after: List<CustomDeviceConnector>,
        timestampMillis: Long = System.currentTimeMillis()
    ): List<GlobalConversationEvent> = resourceMutations(
        before = before.associate { it.id to customDeviceSnapshot(it) },
        after = after.associate { it.id to customDeviceSnapshot(it) },
        timestampMillis = timestampMillis
    )

    fun resourceHealthTransition(
        resourceId: String,
        before: AgentResourceHealth,
        after: AgentResourceHealth,
        timestampMillis: Long = System.currentTimeMillis()
    ): GlobalConversationEvent? {
        if (resourceId.isBlank()) return null
        val previousState = healthState(before, timestampMillis)
        val currentState = healthState(after, timestampMillis)
        if (previousState == currentState) return null
        val idHash = safeId("health", resourceId)
        val stateKey = stateStableKey("health", idHash)
        val resourceClass = when {
            resourceId.startsWith("domain:") -> "failure domain"
            resourceId.startsWith("target:") -> "callable target"
            else -> "runtime resource"
        }
        val stateSummary = when (currentState) {
            ObservedHealthState.UNKNOWN -> "The $resourceClass ${idHash.take(8)} has no recent health evidence."
            ObservedHealthState.HEALTHY -> "The $resourceClass ${idHash.take(8)} is available."
            ObservedHealthState.DEGRADED ->
                "The $resourceClass ${idHash.take(8)} is degraded after ${after.consecutiveFailures} consecutive failures."
            ObservedHealthState.UNAVAILABLE ->
                "The $resourceClass ${idHash.take(8)} is temporarily unavailable after ${after.consecutiveFailures} consecutive failures."
        }
        val fingerprint = GlobalAgentText.stableKey(currentState.name, after.consecutiveFailures.toString())
        return GlobalConversationEvent(
            id = "capability-health:$idHash:$fingerprint:$timestampMillis",
            type = GlobalConversationEventType.RESOURCE_STATE_CHANGED,
            conversationId = CAPABILITY_CONVERSATION_ID,
            messageId = idHash,
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = timestampMillis,
            content = stateSummary,
            contentRef = "encrypted://agent-resource-health/$idHash",
            conversationTitle = CAPABILITY_TOPIC,
            topicHints = setOf(CAPABILITY_TOPIC),
            metadata = projectionMetadata(
                resourceKind = "health",
                resourceIdHash = idHash,
                identityKey = "",
                identitySummary = "",
                stateKey = stateKey,
                stateSummary = stateSummary,
                stateCode = currentState.name.lowercase(Locale.ROOT),
                replaceKeys = setOf(stateKey)
            ) + mapOf(
                "origin" to "resource_health",
                "consecutive_failures" to after.consecutiveFailures.coerceAtLeast(0).toString(),
                "reliability_percent" to after.reliabilityPercent.coerceIn(0, 100).toString()
            )
        )
    }

    fun selfModelTransition(
        before: AgentSelfModel,
        after: AgentSelfModel,
        belief: AgentSelfCapabilityBelief,
        run: AgentRecordedRun,
        timestampMillis: Long = System.currentTimeMillis()
    ): GlobalConversationEvent? {
        if (before == after || belief.key.isBlank()) return null
        val idHash = safeId("self-belief", belief.key)
        val stableKey = "capability:self:$idHash"
        val summary = buildString {
            append("SignalASI observed ").append(belief.evaluatedRuns).append(" evaluated runs for a generalized task family using ")
            append(belief.resourceKey).append(": ")
            append(belief.successfulRuns).append(" succeeded and ").append(belief.failedRuns).append(" failed")
            if (belief.correctionCount > 0) append(" with ").append(belief.correctionCount).append(" user corrections")
            append('.')
        }
        return GlobalConversationEvent(
            id = "capability-self-model:$idHash:${run.runId}:$timestampMillis",
            type = GlobalConversationEventType.RESOURCE_UPDATED,
            conversationId = CAPABILITY_CONVERSATION_ID,
            messageId = idHash,
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = timestampMillis,
            content = summary,
            contentRef = "encrypted://agent-self-model/$idHash",
            conversationTitle = SELF_MODEL_TOPIC,
            topicHints = setOf(SELF_MODEL_TOPIC),
            metadata = mapOf(
                "origin" to "agent_self_model",
                "identity_stable_key" to stableKey,
                "identity_summary" to summary,
                "identity_kind" to GlobalWorldItemKind.FACT.name,
                "identity_layer" to GlobalWorldLayer.TOPIC.name,
                "identity_topic" to SELF_MODEL_TOPIC,
                "replace_stable_keys" to stableKey,
                "context_visibility" to GlobalWorldContextVisibility.LOCAL_ONLY.name,
                "projection" to "replace",
                "evaluated_runs" to belief.evaluatedRuns.toString(),
                "success_rate_percent" to (belief.successRate * 100.0).toInt().coerceIn(0, 100).toString(),
                "confidence_percent" to (belief.confidence * 100.0).toInt().coerceIn(0, 100).toString(),
                "last_outcome" to belief.lastOutcome.name.lowercase(Locale.ROOT),
                "last_failure_category" to belief.lastFailureCategory
            )
        )
    }

    private fun authorizationEvent(
        consentKey: String,
        granted: Boolean,
        timestampMillis: Long
    ): GlobalConversationEvent {
        val idHash = safeId("authorization", consentKey)
        val stableKey = "capability:authorization:$idHash"
        val scope = authorizationScope(consentKey)
        val summary = if (granted) {
            "The user granted remembered confirmation consent for $scope."
        } else {
            "The user revoked remembered confirmation consent for $scope."
        }
        return GlobalConversationEvent(
            id = "capability-authorization:$idHash:${if (granted) "granted" else "revoked"}:$timestampMillis",
            type = if (granted) {
                GlobalConversationEventType.AUTHORIZATION_GRANTED
            } else {
                GlobalConversationEventType.AUTHORIZATION_REVOKED
            },
            conversationId = CAPABILITY_CONVERSATION_ID,
            messageId = idHash,
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = timestampMillis,
            content = summary,
            contentRef = "encrypted://agent-authorization/$idHash",
            conversationTitle = AUTHORIZATION_TOPIC,
            topicHints = setOf(AUTHORIZATION_TOPIC),
            metadata = mapOf(
                "origin" to "confirmation_consent",
                "authorization_scope" to scope,
                "authorization_state" to if (granted) "granted" else "revoked",
                "identity_stable_key" to stableKey,
                "identity_summary" to summary,
                "identity_kind" to GlobalWorldItemKind.DECISION.name,
                "identity_layer" to GlobalWorldLayer.USER.name,
                "identity_topic" to AUTHORIZATION_TOPIC,
                "replace_stable_keys" to stableKey,
                "context_visibility" to GlobalWorldContextVisibility.LOCAL_ONLY.name,
                "projection" to "replace"
            )
        )
    }

    private fun resourceMutations(
        before: Map<String, CapabilityResourceSnapshot>,
        after: Map<String, CapabilityResourceSnapshot>,
        timestampMillis: Long
    ): List<GlobalConversationEvent> = (before.keys + after.keys).sorted().mapNotNull { resourceId ->
        val previous = before[resourceId]
        val current = after[resourceId]
        when {
            previous?.materialFingerprint == current?.materialFingerprint -> null
            current == null && previous != null -> resourceRemoved(previous, timestampMillis)
            current != null -> resourceUpserted(previous, current, timestampMillis)
            else -> null
        }
    }

    private fun resourceUpserted(
        previous: CapabilityResourceSnapshot?,
        current: CapabilityResourceSnapshot,
        timestampMillis: Long
    ): GlobalConversationEvent {
        val type = if (previous == null) {
            GlobalConversationEventType.RESOURCE_REGISTERED
        } else {
            GlobalConversationEventType.RESOURCE_UPDATED
        }
        return GlobalConversationEvent(
            id = "capability-resource:${current.resourceKind}:${current.idHash}:${current.materialFingerprint}:$timestampMillis",
            type = type,
            conversationId = CAPABILITY_CONVERSATION_ID,
            messageId = current.idHash,
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = timestampMillis,
            content = "${current.identitySummary} ${current.stateSummary}".trim(),
            contentRef = "encrypted://agent-capabilities/${current.resourceKind}/${current.idHash}",
            conversationTitle = CAPABILITY_TOPIC,
            topicHints = setOf(CAPABILITY_TOPIC),
            metadata = projectionMetadata(
                resourceKind = current.resourceKind,
                resourceIdHash = current.idHash,
                identityKey = current.identityKey,
                identitySummary = current.identitySummary,
                stateKey = current.stateKey,
                stateSummary = current.stateSummary,
                stateCode = current.stateCode,
                replaceKeys = setOf(current.identityKey, current.stateKey)
            ) + current.safeMetadata + mapOf("origin" to "capability_registry")
        )
    }

    private fun resourceRemoved(
        previous: CapabilityResourceSnapshot,
        timestampMillis: Long
    ): GlobalConversationEvent = GlobalConversationEvent(
        id = "capability-resource-removed:${previous.resourceKind}:${previous.idHash}:$timestampMillis",
        type = GlobalConversationEventType.RESOURCE_REMOVED,
        conversationId = CAPABILITY_CONVERSATION_ID,
        messageId = previous.idHash,
        actor = GlobalConversationActor.SYSTEM,
        timestampMillis = timestampMillis,
        content = "The ${previous.resourceKind.replace('_', ' ')} resource ${previous.displayName} was removed.",
        contentRef = "encrypted://agent-capabilities/${previous.resourceKind}/${previous.idHash}",
        conversationTitle = CAPABILITY_TOPIC,
        metadata = mapOf(
            "origin" to "capability_registry",
            "resource_kind" to previous.resourceKind,
            "resource_id_hash" to previous.idHash,
            "replace_stable_keys" to listOf(previous.identityKey, previous.stateKey).joinToString(","),
            "context_visibility" to GlobalWorldContextVisibility.LOCAL_ONLY.name,
            "projection" to "retract_stable_keys"
        )
    )

    private fun projectionMetadata(
        resourceKind: String,
        resourceIdHash: String,
        identityKey: String,
        identitySummary: String,
        stateKey: String,
        stateSummary: String,
        stateCode: String,
        replaceKeys: Set<String>
    ): Map<String, String> = mapOf(
        "resource_kind" to resourceKind,
        "resource_id_hash" to resourceIdHash,
        "identity_stable_key" to identityKey,
        "identity_summary" to identitySummary,
        "identity_kind" to GlobalWorldItemKind.FACT.name,
        "identity_layer" to GlobalWorldLayer.USER.name,
        "identity_topic" to CAPABILITY_TOPIC,
        "state_stable_key" to stateKey,
        "state_summary" to stateSummary,
        "state_kind" to GlobalWorldItemKind.STATE.name,
        "state_layer" to GlobalWorldLayer.REALTIME.name,
        "state_topic" to CAPABILITY_TOPIC,
        "resource_state" to stateCode,
        "replace_stable_keys" to replaceKeys.filter(String::isNotBlank).sorted().joinToString(","),
        "context_visibility" to GlobalWorldContextVisibility.LOCAL_ONLY.name,
        "projection" to "replace"
    )

    private fun mcpSnapshot(connection: AgentMcpConnection, nowMillis: Long): CapabilityResourceSnapshot {
        val idHash = safeId("mcp", connection.id)
        val authState = connection.effectiveAuthState(nowMillis)
        val callable = connection.isCallable(nowMillis)
        val stateCode = when {
            !connection.enabled -> "disabled"
            authState in setOf(
                AgentMcpAuthState.NOT_CONFIGURED,
                AgentMcpAuthState.CHALLENGE_REQUIRED,
                AgentMcpAuthState.AUTHENTICATING,
                AgentMcpAuthState.REAUTHENTICATION_REQUIRED,
                AgentMcpAuthState.ERROR
            ) -> "needs_setup"
            connection.state == AgentMcpConnectionState.CONNECTING -> "connecting"
            connection.state == AgentMcpConnectionState.CONNECTED -> "available"
            connection.state == AgentMcpConnectionState.ERROR -> "error"
            connection.state == AgentMcpConnectionState.UNAVAILABLE -> "unavailable"
            connection.state == AgentMcpConnectionState.NEEDS_SETUP -> "needs_setup"
            callable -> "ready"
            else -> "unavailable"
        }
        val name = cleanLabel(connection.displayName, "MCP resource")
        val identity = "$name is an installed ${connection.distribution.wireValue.replace('_', ' ')} MCP resource with ${connection.toolIds.size} tools."
        val state = when (stateCode) {
            "available" -> "$name is connected and callable."
            "ready" -> "$name is ready when requested."
            "connecting" -> "$name is connecting."
            "needs_setup" -> "$name requires authentication or setup."
            "disabled" -> "$name is disabled."
            "error" -> "$name is unavailable because its connection failed."
            else -> "$name is unavailable."
        }
        val fingerprint = GlobalAgentText.stableKey(
            name,
            connection.catalogId,
            privateFingerprint(connection.endpoint),
            connection.distribution.name,
            connection.transport.name,
            connection.authProfile.method.name,
            authState.name,
            connection.state.name,
            connection.enabled.toString(),
            callable.toString(),
            connection.toolIds.distinct().sorted().joinToString("|"),
            connection.packageVersion,
            connection.packageSha256
        )
        return CapabilityResourceSnapshot(
            resourceKind = "mcp",
            idHash = idHash,
            displayName = name,
            identitySummary = identity,
            stateSummary = state,
            stateCode = stateCode,
            materialFingerprint = fingerprint,
            safeMetadata = mapOf(
                "distribution" to connection.distribution.wireValue,
                "transport" to connection.transport.wireValue,
                "auth_state" to authState.wireValue,
                "connection_state" to connection.state.wireValue,
                "enabled" to connection.enabled.toString(),
                "callable" to callable.toString(),
                "tool_count" to connection.toolIds.distinct().size.toString()
            )
        )
    }

    private fun agentSnapshot(registration: AgentRegistration): CapabilityResourceSnapshot {
        val idHash = safeId("agent", registration.agentId)
        val name = cleanLabel(registration.displayName, "Agent resource")
        val atCapacity = !registration.hasCapacity
        val stateCode = when (registration.status) {
            AgentEndpointStatus.ONLINE, AgentEndpointStatus.IDLE -> if (atCapacity) "busy" else "available"
            AgentEndpointStatus.BUSY -> "busy"
            AgentEndpointStatus.DEGRADED -> "degraded"
            AgentEndpointStatus.UPDATING -> "updating"
            AgentEndpointStatus.PERMISSION_REQUIRED -> "permission_required"
            AgentEndpointStatus.OFFLINE -> "offline"
            AgentEndpointStatus.UNREACHABLE -> "unreachable"
        }
        val location = registration.location.name.lowercase(Locale.ROOT).replace('_', ' ')
        val identity = "$name is a registered $location Agent resource with ${registration.capabilities.size} capabilities."
        val state = when (stateCode) {
            "available" -> "$name is available."
            "busy" -> "$name is busy${if (atCapacity) " and at capacity" else ""}."
            "degraded" -> "$name is degraded."
            "updating" -> "$name is updating."
            "permission_required" -> "$name requires local permission."
            "offline" -> "$name is offline."
            else -> "$name is unreachable."
        }
        val fingerprint = GlobalAgentText.stableKey(
            name,
            registration.kind.name,
            registration.location.name,
            registration.status.name,
            registration.capabilities.sortedBy(Enum<*>::name).joinToString("|") { it.name },
            registration.toolIds.sorted().joinToString("|"),
            registration.permissionScopes.size.toString(),
            registration.protocol.preferred,
            registration.connectionKind.name,
            registration.cost.name,
            registration.latency.name,
            registration.trust.name,
            atCapacity.toString(),
            registration.maxParallelRuns.toString(),
            registration.capabilitiesHash
        )
        return CapabilityResourceSnapshot(
            resourceKind = "agent",
            idHash = idHash,
            displayName = name,
            identitySummary = identity,
            stateSummary = state,
            stateCode = stateCode,
            materialFingerprint = fingerprint,
            safeMetadata = mapOf(
                "agent_kind" to registration.kind.name.lowercase(Locale.ROOT),
                "location" to registration.location.name.lowercase(Locale.ROOT),
                "endpoint_state" to registration.status.name.lowercase(Locale.ROOT),
                "connection_kind" to registration.connectionKind.name.lowercase(Locale.ROOT),
                "trust" to registration.trust.name.lowercase(Locale.ROOT),
                "capability_count" to registration.capabilities.size.toString(),
                "tool_count" to registration.toolIds.size.toString(),
                "at_capacity" to atCapacity.toString()
            )
        )
    }

    private fun homeAssistantSnapshot(settings: HomeAssistantSettings): CapabilityResourceSnapshot? {
        val present = settings.enabled || settings.baseUrl.isNotBlank() ||
            settings.accessToken.isNotBlank() || settings.defaultEntityId.isNotBlank()
        if (!present) return null
        val idHash = safeId("home_assistant", HOME_ASSISTANT_ID)
        val stateCode = when {
            settings.configured -> "ready"
            settings.enabled -> "needs_setup"
            else -> "disabled"
        }
        val identity = "Home Assistant is registered as a smart-device resource."
        val state = when (stateCode) {
            "ready" -> "Home Assistant is enabled and ready."
            "needs_setup" -> "Home Assistant is enabled but requires connection setup."
            else -> "Home Assistant is disabled."
        }
        return CapabilityResourceSnapshot(
            resourceKind = "home_assistant",
            idHash = idHash,
            displayName = "Home Assistant",
            identitySummary = identity,
            stateSummary = state,
            stateCode = stateCode,
            materialFingerprint = GlobalAgentText.stableKey(
                settings.enabled.toString(),
                settings.credentialsConfigured.toString(),
                privateFingerprint(settings.baseUrl),
                privateFingerprint(settings.defaultEntityId),
                settings.defaultEntityId.isNotBlank().toString()
            ),
            safeMetadata = mapOf(
                "enabled" to settings.enabled.toString(),
                "credentials_configured" to settings.credentialsConfigured.toString(),
                "default_target_configured" to settings.defaultEntityId.isNotBlank().toString()
            )
        )
    }

    private fun customDeviceSnapshot(connector: CustomDeviceConnector): CapabilityResourceSnapshot {
        val idHash = safeId("custom_device", connector.id)
        val name = cleanLabel(connector.name, "Custom device")
        val stateCode = when {
            !connector.enabled -> "disabled"
            connector.configured -> "ready"
            else -> "needs_setup"
        }
        val identity = "$name is a registered ${connector.transport.name.lowercase(Locale.ROOT).replace('_', ' ')} device resource."
        val state = when (stateCode) {
            "ready" -> "$name is enabled and configured."
            "disabled" -> "$name is disabled."
            else -> "$name requires connection setup."
        }
        return CapabilityResourceSnapshot(
            resourceKind = "custom_device",
            idHash = idHash,
            displayName = name,
            identitySummary = identity,
            stateSummary = state,
            stateCode = stateCode,
            materialFingerprint = GlobalAgentText.stableKey(
                name,
                connector.transport.name,
                privateFingerprint(connector.endpoint),
                privateFingerprint(connector.commandTarget),
                connector.username.isNotBlank().toString(),
                connector.authToken.isNotBlank().toString(),
                connector.risk.name,
                connector.enabled.toString(),
                connector.configured.toString()
            ),
            safeMetadata = mapOf(
                "transport" to connector.transport.name.lowercase(Locale.ROOT),
                "risk" to connector.risk.name.lowercase(Locale.ROOT),
                "enabled" to connector.enabled.toString(),
                "configured" to connector.configured.toString()
            )
        )
    }

    private fun healthState(health: AgentResourceHealth, nowMillis: Long): ObservedHealthState = when {
        health.successes + health.failures == 0 -> ObservedHealthState.UNKNOWN
        health.circuitOpenUntil > nowMillis || health.consecutiveFailures >= 3 -> ObservedHealthState.UNAVAILABLE
        health.consecutiveFailures > 0 -> ObservedHealthState.DEGRADED
        else -> ObservedHealthState.HEALTHY
    }

    private fun authorizationScope(consentKey: String): String = when {
        consentKey == "location" -> "location access"
        consentKey == "microphone" -> "microphone use"
        consentKey == "downloads" -> "downloads"
        consentKey == "contacts_write" -> "contact changes"
        consentKey == "calendar_write" -> "calendar changes"
        consentKey == "bluetooth_discovery" -> "Bluetooth discovery"
        consentKey == "wifi_scan" -> "Wi-Fi scanning"
        consentKey == "installed_apps_read" -> "installed-app inspection"
        consentKey.startsWith("device_control:") -> "a configured device target"
        else -> "a local action category"
    }

    private fun cleanLabel(value: String, fallback: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_LABEL_CHARACTERS)
        .ifBlank { fallback }

    private fun safeId(kind: String, id: String): String = privateFingerprint("$kind\u0000$id").take(32)

    private fun privateFingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun identityStableKey(kind: String, idHash: String): String =
        "capability:resource:$kind:$idHash:identity"

    private fun stateStableKey(kind: String, idHash: String): String =
        "capability:resource:$kind:$idHash:state"

    private data class CapabilityResourceSnapshot(
        val resourceKind: String,
        val idHash: String,
        val displayName: String,
        val identitySummary: String,
        val stateSummary: String,
        val stateCode: String,
        val materialFingerprint: String,
        val safeMetadata: Map<String, String>
    ) {
        val identityKey: String get() = identityStableKey(resourceKind, idHash)
        val stateKey: String get() = stateStableKey(resourceKind, idHash)
    }

    private enum class ObservedHealthState { UNKNOWN, HEALTHY, DEGRADED, UNAVAILABLE }

    private const val CAPABILITY_CONVERSATION_ID = "global-capabilities"
    private const val CAPABILITY_TOPIC = "Available capabilities"
    private const val AUTHORIZATION_TOPIC = "Local authorization"
    private const val SELF_MODEL_TOPIC = "Agent self model"
    private const val HOME_ASSISTANT_ID = "home-assistant"
    private const val MAX_LABEL_CHARACTERS = 120
}

fun GlobalConversationEventType.isCapabilityLifecycleEvent(): Boolean = this in setOf(
    GlobalConversationEventType.AUTHORIZATION_GRANTED,
    GlobalConversationEventType.AUTHORIZATION_REVOKED,
    GlobalConversationEventType.AUTHORIZATION_POLICY_CHANGED,
    GlobalConversationEventType.RESOURCE_REGISTERED,
    GlobalConversationEventType.RESOURCE_UPDATED,
    GlobalConversationEventType.RESOURCE_REMOVED,
    GlobalConversationEventType.RESOURCE_STATE_CHANGED,
    GlobalConversationEventType.CAPABILITY_SNAPSHOT_RESET
)
