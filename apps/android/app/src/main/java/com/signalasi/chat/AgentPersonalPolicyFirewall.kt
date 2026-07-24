package com.signalasi.chat

import android.content.Context
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class AgentExternalRequestDirection {
    OUTBOUND,
    INBOUND
}

enum class AgentPolicyFirewallVerdict {
    ALLOW,
    REQUIRE_CONFIRMATION,
    DENY
}

data class AgentDelegationDisclosure(
    val contextKeys: Set<String> = emptySet(),
    val artifactIds: Set<String> = emptySet(),
    val includesConversationHistory: Boolean = false,
    val includesInternalMemory: Boolean = false,
    val includesSystemPrompt: Boolean = false,
    val includesCredentials: Boolean = false
)

data class AgentExternalPolicyRequest(
    val requestId: String,
    val nonce: String,
    val direction: AgentExternalRequestDirection,
    val sourceTeamId: String,
    val destinationTeamId: String,
    val requesterAgentId: String,
    val targetAgentIds: Set<String>,
    val goal: String,
    val requiredCapabilities: Set<AgentCapability> = emptySet(),
    val disclosure: AgentDelegationDisclosure = AgentDelegationDisclosure(),
    val dataSensitivity: AgentDataSensitivity = AgentDataSensitivity.PERSONAL,
    val risk: AgentRisk = AgentRisk.LOW,
    val delegationDepth: Int = 0,
    val estimatedCostUnits: Int = 0,
    val secureTransport: Boolean,
    val identityProofVerified: Boolean,
    val createdAtMillis: Long,
    val expiresAtMillis: Long
)

data class AgentPersonalPolicy(
    val maxDelegationDepth: Int = 3,
    val maxTargets: Int = 12,
    val maxArtifacts: Int = 20,
    val maxGoalCharacters: Int = 8_000,
    val maxEstimatedCostUnits: Int = 32,
    val maxRequestLifetimeMillis: Long = 10L * 60L * 1_000L,
    val allowedContextKeys: Set<String> = DEFAULT_ALLOWED_CONTEXT_KEYS,
    val automaticallyAllowedOutboundTrust: Set<AgentResourceTrust> = setOf(
        AgentResourceTrust.PHONE_SYSTEM,
        AgentResourceTrust.VERIFIED_PAIRED
    )
) {
    companion object {
        val DEFAULT_ALLOWED_CONTEXT_KEYS = setOf(
            "objective",
            "constraints",
            "expected_output",
            "evidence",
            "artifact_manifest",
            "trace_parent",
            "locale",
            "deadline",
            "budget"
        )
    }
}

data class AgentPolicyFirewallDecision(
    val verdict: AgentPolicyFirewallVerdict,
    val requestId: String,
    val reasonCodes: List<String>,
    val requiredGrants: List<AgentPermissionRequest> = emptyList(),
    val matchedGrantIds: Set<String> = emptySet(),
    val evaluatedAtMillis: Long,
    val replayClaimed: Boolean = false
) {
    val allowed: Boolean
        get() = verdict == AgentPolicyFirewallVerdict.ALLOW
}

data class AgentPolicyFirewallAuditEvent(
    val eventId: String,
    val requestId: String,
    val direction: AgentExternalRequestDirection,
    val sourceTeamId: String,
    val destinationTeamId: String,
    val requesterAgentId: String,
    val targetAgentIds: Set<String>,
    val verdict: AgentPolicyFirewallVerdict,
    val reasonCodes: List<String>,
    val dataSensitivity: AgentDataSensitivity,
    val risk: AgentRisk,
    val capabilityNames: Set<String>,
    val artifactCount: Int,
    val goalHash: String,
    val evaluatedAtMillis: Long
)

interface AgentPolicyReplayStore {
    fun claim(
        requestId: String,
        nonce: String,
        expiresAtMillis: Long,
        nowMillis: Long
    ): Boolean

    fun clear()
}

class InMemoryAgentPolicyReplayStore : AgentPolicyReplayStore {
    private val claims = linkedMapOf<String, AgentPolicyReplayClaim>()

    @Synchronized
    override fun claim(
        requestId: String,
        nonce: String,
        expiresAtMillis: Long,
        nowMillis: Long
    ): Boolean {
        purge(nowMillis)
        val nonceHash = sha256(nonce)
        if (requestId in claims || claims.values.any { it.nonceHash == nonceHash }) return false
        claims[requestId] = AgentPolicyReplayClaim(requestId, nonceHash, expiresAtMillis)
        return true
    }

    @Synchronized
    override fun clear() = claims.clear()

    private fun purge(nowMillis: Long) {
        claims.entries.removeAll { (_, claim) -> claim.expiresAtMillis <= nowMillis }
    }
}

class EncryptedAgentPolicyReplayStore(context: Context) : AgentPolicyReplayStore {
    private val database = AgentEncryptedDatabase(context.applicationContext, DATABASE)

    @Synchronized
    override fun claim(
        requestId: String,
        nonce: String,
        expiresAtMillis: Long,
        nowMillis: Long
    ): Boolean {
        val nonceHash = sha256(nonce)
        val active = read().filter { it.expiresAtMillis > nowMillis }
        if (active.any { it.requestId == requestId || it.nonceHash == nonceHash }) {
            write(active)
            return false
        }
        write((active + AgentPolicyReplayClaim(requestId, nonceHash, expiresAtMillis)).takeLast(MAX_CLAIMS))
        return true
    }

    @Synchronized
    override fun clear() = database.clear()

    private fun read(): List<AgentPolicyReplayClaim> = runCatching {
        val array = JSONArray(database.readString(KEY_CLAIMS, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val requestId = item.optString("request_id")
                val nonceHash = item.optString("nonce_hash")
                val expiresAtMillis = item.optLong("expires_at_millis")
                if (requestId.isNotBlank() && nonceHash.isNotBlank() && expiresAtMillis > 0L) {
                    add(AgentPolicyReplayClaim(requestId, nonceHash, expiresAtMillis))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun write(claims: List<AgentPolicyReplayClaim>) {
        database.writeString(KEY_CLAIMS, JSONArray().apply {
            claims.forEach { claim ->
                put(JSONObject()
                    .put("request_id", claim.requestId)
                    .put("nonce_hash", claim.nonceHash)
                    .put("expires_at_millis", claim.expiresAtMillis))
            }
        }.toString())
    }

    private companion object {
        const val DATABASE = "signalasi_policy_firewall_replay_v1"
        const val KEY_CLAIMS = "claims"
        const val MAX_CLAIMS = 4_096
    }
}

interface AgentPolicyFirewallAuditStore {
    fun append(event: AgentPolicyFirewallAuditEvent)
    fun list(): List<AgentPolicyFirewallAuditEvent>
    fun clear()
}

class InMemoryAgentPolicyFirewallAuditStore : AgentPolicyFirewallAuditStore {
    private val events = mutableListOf<AgentPolicyFirewallAuditEvent>()

    @Synchronized
    override fun append(event: AgentPolicyFirewallAuditEvent) {
        events += event
    }

    @Synchronized
    override fun list(): List<AgentPolicyFirewallAuditEvent> = events.toList()

    @Synchronized
    override fun clear() = events.clear()
}

class EncryptedAgentPolicyFirewallAuditStore(context: Context) : AgentPolicyFirewallAuditStore {
    private val database = AgentEncryptedDatabase(context.applicationContext, DATABASE)

    @Synchronized
    override fun append(event: AgentPolicyFirewallAuditEvent) {
        write((list() + event).takeLast(MAX_EVENTS))
    }

    @Synchronized
    override fun list(): List<AgentPolicyFirewallAuditEvent> = runCatching {
        val array = JSONArray(database.readString(KEY_EVENTS, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toPolicyAuditEvent()?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    override fun clear() = database.clear()

    private fun write(events: List<AgentPolicyFirewallAuditEvent>) {
        database.writeString(KEY_EVENTS, JSONArray().apply {
            events.forEach { put(it.toJson()) }
        }.toString())
    }

    private companion object {
        const val DATABASE = "signalasi_policy_firewall_audit_v1"
        const val KEY_EVENTS = "events"
        const val MAX_EVENTS = 2_000
    }
}

/**
 * Local admission controller for any task that crosses a phone, team, device,
 * or trust boundary. It evaluates metadata only; raw memory, prompts, and
 * credentials are deliberately outside this API.
 */
class AgentPersonalPolicyFirewall(
    private val grantStore: AgentPermissionGrantStore,
    private val replayStore: AgentPolicyReplayStore,
    private val auditStore: AgentPolicyFirewallAuditStore,
    private val policy: AgentPersonalPolicy = AgentPersonalPolicy(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun evaluate(
        request: AgentExternalPolicyRequest,
        registrations: Collection<AgentRegistration>
    ): AgentPolicyFirewallDecision = decide(request, registrations, consume = false)

    fun admit(
        request: AgentExternalPolicyRequest,
        registrations: Collection<AgentRegistration>
    ): AgentPolicyFirewallDecision = decide(request, registrations, consume = true)

    private fun decide(
        request: AgentExternalPolicyRequest,
        registrations: Collection<AgentRegistration>,
        consume: Boolean
    ): AgentPolicyFirewallDecision {
        val now = clock().coerceAtLeast(0L)
        val registrationsById = registrations.associateBy(AgentRegistration::agentId)
        val hardDenials = hardDenials(request, registrationsById, now)
        if (hardDenials.isNotEmpty()) {
            return decision(
                request,
                AgentPolicyFirewallVerdict.DENY,
                hardDenials,
                now
            ).also { audit(request, it) }
        }

        val participants = policyParticipants(request, registrationsById)
        val grantRequests = participants.map { registration ->
            AgentPermissionRequest(
                subjectType = AgentPermissionSubjectType.AGENT,
                subjectId = registration.agentId,
                scope = DELEGATION_SCOPE,
                action = request.direction.name.lowercase(Locale.US),
                resource = request.sourceTeamId,
                target = request.destinationTeamId
            )
        }
        val grantDecisions = grantRequests.map { permission ->
            permission to grantStore.authorize(permission, consume = false)
        }
        val matchedGrants = grantDecisions.mapNotNullTo(linkedSetOf()) {
            it.second.grant?.grantId
        }
        val allGranted = grantDecisions.all { it.second.granted }
        val freshSingleUseRequired = requiresFreshSingleUseGrant(request)
        val freshSingleUseGranted = allGranted && grantDecisions.all {
            it.second.grant?.lifetime == AgentPermissionGrantLifetime.SINGLE_USE
        }
        val confirmationReasons = confirmationReasons(
            request = request,
            participants = participants,
            allGranted = allGranted,
            freshSingleUseRequired = freshSingleUseRequired,
            freshSingleUseGranted = freshSingleUseGranted
        )
        if (confirmationReasons.isNotEmpty()) {
            return decision(
                request = request,
                verdict = AgentPolicyFirewallVerdict.REQUIRE_CONFIRMATION,
                reasons = confirmationReasons,
                nowMillis = now,
                requiredGrants = grantRequests,
                matchedGrantIds = matchedGrants
            ).also { audit(request, it) }
        }

        if (!consume) {
            return decision(
                request,
                AgentPolicyFirewallVerdict.ALLOW,
                listOf(if (allGranted) "explicit_grant_active" else "trusted_low_risk_outbound"),
                now,
                matchedGrantIds = matchedGrants
            ).also { audit(request, it) }
        }
        val replayClaimed = replayStore.claim(
            requestId = request.requestId,
            nonce = request.nonce,
            expiresAtMillis = request.expiresAtMillis,
            nowMillis = now
        )
        if (!replayClaimed) {
            return decision(
                request,
                AgentPolicyFirewallVerdict.DENY,
                listOf("replay_detected"),
                now
            ).also { audit(request, it) }
        }
        val consumedGrantIds = linkedSetOf<String>()
        if (allGranted) {
            grantRequests.forEach { permission ->
                val consumed = grantStore.authorize(permission, consume = true)
                if (!consumed.granted) {
                    return decision(
                        request,
                        AgentPolicyFirewallVerdict.DENY,
                        listOf("grant_consumption_failed"),
                        now,
                        matchedGrantIds = consumedGrantIds,
                        replayClaimed = true
                    ).also { audit(request, it) }
                }
                consumed.grant?.grantId?.let(consumedGrantIds::add)
            }
        }
        return decision(
            request,
            AgentPolicyFirewallVerdict.ALLOW,
            listOf(if (allGranted) "explicit_grant_consumed" else "trusted_low_risk_outbound"),
            now,
            matchedGrantIds = consumedGrantIds,
            replayClaimed = true
        ).also { audit(request, it) }
    }

    private fun hardDenials(
        request: AgentExternalPolicyRequest,
        registrationsById: Map<String, AgentRegistration>,
        now: Long
    ): List<String> = buildList {
        if (request.requestId.isBlank() || request.requestId.length > MAX_ID_CHARS) {
            add("request_id_invalid")
        }
        if (request.nonce.length !in MIN_NONCE_CHARS..MAX_NONCE_CHARS) add("nonce_invalid")
        if (request.sourceTeamId.isBlank() || request.destinationTeamId.isBlank() ||
            request.sourceTeamId.length > MAX_ID_CHARS ||
            request.destinationTeamId.length > MAX_ID_CHARS
        ) {
            add("team_identity_invalid")
        }
        if (request.sourceTeamId == request.destinationTeamId) add("cross_team_boundary_missing")
        if (request.requesterAgentId.isBlank() || request.requesterAgentId.length > MAX_ID_CHARS) {
            add("requester_identity_invalid")
        }
        if (request.targetAgentIds.isEmpty() || request.targetAgentIds.size > policy.maxTargets) {
            add("target_count_invalid")
        }
        if (request.targetAgentIds.any { it.isBlank() || it.length > MAX_ID_CHARS }) {
            add("target_identity_invalid")
        }
        if (request.goal.isBlank() || request.goal.length > policy.maxGoalCharacters) {
            add("goal_boundary_invalid")
        }
        if (!request.secureTransport) add("secure_transport_required")
        if (!request.identityProofVerified) add("identity_proof_required")
        if (request.createdAtMillis > now + MAX_CLOCK_SKEW_MILLIS) add("request_from_future")
        if (request.expiresAtMillis <= now) add("request_expired")
        if (request.expiresAtMillis <= request.createdAtMillis ||
            request.expiresAtMillis - request.createdAtMillis > policy.maxRequestLifetimeMillis
        ) {
            add("request_lifetime_invalid")
        }
        if (request.delegationDepth !in 0..policy.maxDelegationDepth) add("delegation_depth_exceeded")
        if (request.estimatedCostUnits !in 0..policy.maxEstimatedCostUnits) add("budget_exceeded")
        if (request.disclosure.artifactIds.size > policy.maxArtifacts) add("artifact_count_exceeded")
        if (request.disclosure.includesConversationHistory) add("conversation_history_forbidden")
        if (request.disclosure.includesInternalMemory) add("internal_memory_forbidden")
        if (request.disclosure.includesSystemPrompt) add("system_prompt_forbidden")
        if (request.disclosure.includesCredentials) add("credentials_forbidden")
        if (!policy.allowedContextKeys.containsAll(request.disclosure.contextKeys)) {
            add("context_boundary_violation")
        }
        if (request.risk == AgentRisk.BLOCKED) add("blocked_risk")

        val participantIds = when (request.direction) {
            AgentExternalRequestDirection.OUTBOUND -> request.targetAgentIds
            AgentExternalRequestDirection.INBOUND -> setOf(request.requesterAgentId)
        }
        val participants = participantIds.mapNotNull(registrationsById::get)
        if (participants.size != participantIds.size) add("participant_not_registered")
        if (participants.any { it.trust == AgentResourceTrust.UNKNOWN }) add("participant_not_trusted")
        if (participants.any {
                it.status !in setOf(
                    AgentEndpointStatus.ONLINE,
                    AgentEndpointStatus.IDLE,
                    AgentEndpointStatus.BUSY
                )
            }
        ) {
            add("participant_not_routable")
        }
        val targetRegistrations = request.targetAgentIds.mapNotNull(registrationsById::get)
        if (targetRegistrations.size != request.targetAgentIds.size) add("target_not_registered")
        if (!targetRegistrations.flatMapTo(linkedSetOf(), AgentRegistration::capabilities)
                .containsAll(request.requiredCapabilities)
        ) {
            add("capability_contract_unmet")
        }
        if (request.dataSensitivity == AgentDataSensitivity.RESTRICTED &&
            targetRegistrations.any {
                it.trust !in setOf(
                    AgentResourceTrust.PHONE_SYSTEM,
                    AgentResourceTrust.VERIFIED_PAIRED
                )
            }
        ) {
            add("restricted_data_boundary")
        }
    }.distinct()

    private fun policyParticipants(
        request: AgentExternalPolicyRequest,
        registrationsById: Map<String, AgentRegistration>
    ): List<AgentRegistration> = when (request.direction) {
        AgentExternalRequestDirection.OUTBOUND ->
            request.targetAgentIds.mapNotNull(registrationsById::get)
        AgentExternalRequestDirection.INBOUND ->
            listOfNotNull(registrationsById[request.requesterAgentId])
    }.distinctBy(AgentRegistration::agentId)

    private fun confirmationReasons(
        request: AgentExternalPolicyRequest,
        participants: List<AgentRegistration>,
        allGranted: Boolean,
        freshSingleUseRequired: Boolean,
        freshSingleUseGranted: Boolean
    ): List<String> = buildList {
        if (freshSingleUseRequired && !freshSingleUseGranted) {
            add("fresh_single_use_grant_required")
            return@buildList
        }
        if (allGranted) return@buildList
        if (request.direction == AgentExternalRequestDirection.INBOUND) {
            add("inbound_request_requires_grant")
        }
        if (request.dataSensitivity in setOf(
                AgentDataSensitivity.CONFIDENTIAL,
                AgentDataSensitivity.RESTRICTED
            )
        ) {
            add("sensitive_data_requires_grant")
        }
        if (request.disclosure.artifactIds.isNotEmpty()) add("artifacts_require_grant")
        if (participants.any { it.trust !in policy.automaticallyAllowedOutboundTrust }) {
            add("external_trust_boundary_requires_grant")
        }
    }.distinct()

    private fun requiresFreshSingleUseGrant(request: AgentExternalPolicyRequest): Boolean =
        request.dataSensitivity == AgentDataSensitivity.RESTRICTED ||
            request.risk.weight >= AgentRisk.HIGH.weight ||
            request.requiredCapabilities.any {
                it in setOf(
                    AgentCapability.DEVICE_CONTROL,
                    AgentCapability.APP_NAVIGATION,
                    AgentCapability.SYSTEM_SETTINGS
                )
            }

    private fun decision(
        request: AgentExternalPolicyRequest,
        verdict: AgentPolicyFirewallVerdict,
        reasons: List<String>,
        nowMillis: Long,
        requiredGrants: List<AgentPermissionRequest> = emptyList(),
        matchedGrantIds: Set<String> = emptySet(),
        replayClaimed: Boolean = false
    ) = AgentPolicyFirewallDecision(
        verdict = verdict,
        requestId = request.requestId,
        reasonCodes = reasons.distinct(),
        requiredGrants = requiredGrants,
        matchedGrantIds = matchedGrantIds,
        evaluatedAtMillis = nowMillis,
        replayClaimed = replayClaimed
    )

    private fun audit(
        request: AgentExternalPolicyRequest,
        decision: AgentPolicyFirewallDecision
    ) {
        auditStore.append(AgentPolicyFirewallAuditEvent(
            eventId = "${request.requestId}:${decision.evaluatedAtMillis}:${decision.verdict.name}",
            requestId = request.requestId,
            direction = request.direction,
            sourceTeamId = request.sourceTeamId,
            destinationTeamId = request.destinationTeamId,
            requesterAgentId = request.requesterAgentId,
            targetAgentIds = request.targetAgentIds,
            verdict = decision.verdict,
            reasonCodes = decision.reasonCodes,
            dataSensitivity = request.dataSensitivity,
            risk = request.risk,
            capabilityNames = request.requiredCapabilities.mapTo(linkedSetOf(), AgentCapability::name),
            artifactCount = request.disclosure.artifactIds.size,
            goalHash = sha256(request.goal),
            evaluatedAtMillis = decision.evaluatedAtMillis
        ))
    }

    companion object {
        const val DELEGATION_SCOPE = "signalasi.agent.external_delegate"
        private const val MIN_NONCE_CHARS = 16
        private const val MAX_NONCE_CHARS = 256
        private const val MAX_ID_CHARS = 256
        private const val MAX_CLOCK_SKEW_MILLIS = 30_000L

        fun encrypted(
            context: Context,
            policy: AgentPersonalPolicy = AgentPersonalPolicy()
        ): AgentPersonalPolicyFirewall = AgentPersonalPolicyFirewall(
            grantStore = EncryptedAgentPermissionGrantStore(context),
            replayStore = EncryptedAgentPolicyReplayStore(context),
            auditStore = EncryptedAgentPolicyFirewallAuditStore(context),
            policy = policy
        )
    }
}

private data class AgentPolicyReplayClaim(
    val requestId: String,
    val nonceHash: String,
    val expiresAtMillis: Long
)

private fun AgentPolicyFirewallAuditEvent.toJson(): JSONObject = JSONObject()
    .put("event_id", eventId)
    .put("request_id", requestId)
    .put("direction", direction.name)
    .put("source_team_id", sourceTeamId)
    .put("destination_team_id", destinationTeamId)
    .put("requester_agent_id", requesterAgentId)
    .put("target_agent_ids", JSONArray(targetAgentIds.sorted()))
    .put("verdict", verdict.name)
    .put("reason_codes", JSONArray(reasonCodes))
    .put("data_sensitivity", dataSensitivity.name)
    .put("risk", risk.name)
    .put("capabilities", JSONArray(capabilityNames.sorted()))
    .put("artifact_count", artifactCount)
    .put("goal_hash", goalHash)
    .put("evaluated_at_millis", evaluatedAtMillis)

private fun JSONObject.toPolicyAuditEvent(): AgentPolicyFirewallAuditEvent? = runCatching {
    AgentPolicyFirewallAuditEvent(
        eventId = getString("event_id"),
        requestId = getString("request_id"),
        direction = AgentExternalRequestDirection.valueOf(getString("direction")),
        sourceTeamId = optString("source_team_id"),
        destinationTeamId = optString("destination_team_id"),
        requesterAgentId = optString("requester_agent_id"),
        targetAgentIds = optJSONArray("target_agent_ids").stringValues(),
        verdict = AgentPolicyFirewallVerdict.valueOf(getString("verdict")),
        reasonCodes = optJSONArray("reason_codes").stringValues().toList(),
        dataSensitivity = AgentDataSensitivity.valueOf(getString("data_sensitivity")),
        risk = AgentRisk.valueOf(getString("risk")),
        capabilityNames = optJSONArray("capabilities").stringValues(),
        artifactCount = optInt("artifact_count").coerceAtLeast(0),
        goalHash = getString("goal_hash"),
        evaluatedAtMillis = getLong("evaluated_at_millis")
    )
}.getOrNull()

private fun JSONArray?.stringValues(): Set<String> = buildSet {
    val values = this@stringValues ?: return@buildSet
    for (index in 0 until values.length()) {
        values.optString(index).takeIf(String::isNotBlank)?.let(::add)
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
