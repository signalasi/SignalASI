package com.signalasi.chat

import java.util.Locale
import java.util.UUID

enum class AgentDynamicTeamOutcome {
    SINGLE_AGENT,
    TEAM,
    UNAVAILABLE,
    BLOCKED
}

enum class AgentDynamicTeamRole {
    LEAD,
    RESEARCHER,
    IMPLEMENTER,
    KNOWLEDGE_SPECIALIST,
    DEVICE_OPERATOR,
    TOOL_SPECIALIST,
    ANALYST,
    VERIFIER,
    REQUESTED_SPECIALIST
}

enum class AgentTeamVerificationMode {
    DISABLED,
    AUTO,
    REQUIRED
}

data class AgentTeamCompilationBudget(
    val maxMembers: Int = 5,
    val maxCloudMembers: Int = 1,
    val maximumMemberCost: AgentResourceCost = AgentResourceCost.HIGH,
    val maximumMemberLatency: AgentResourceLatency = AgentResourceLatency.SLOW,
    val maxEstimatedCostUnits: Int = 16
)

data class AgentDynamicTeamPolicy(
    val forceTeam: Boolean = false,
    val trustedOnly: Boolean = true,
    val preferFailureDomainDiversity: Boolean = true,
    val verificationMode: AgentTeamVerificationMode = AgentTeamVerificationMode.AUTO,
    val visibilityMode: AgentTeamVisibilityMode = AgentTeamVisibilityMode.BACKGROUND,
    val pinnedAgentIds: Set<String> = emptySet(),
    val excludedAgentIds: Set<String> = emptySet(),
    val budget: AgentTeamCompilationBudget = AgentTeamCompilationBudget()
)

data class AgentDynamicTeamRequest(
    val goal: String,
    val teamId: String = UUID.randomUUID().toString(),
    val policy: AgentDynamicTeamPolicy = AgentDynamicTeamPolicy()
)

data class AgentDynamicTeamAssignment(
    val role: AgentDynamicTeamRole,
    val registration: AgentRegistration,
    val score: Int,
    val requiredCapabilities: Set<AgentCapability>,
    val objective: String,
    val reasons: List<String>
) {
    val failureDomain: String
        get() = registration.effectiveTeamFailureDomain()
}

data class AgentDynamicTeamCompilation(
    val outcome: AgentDynamicTeamOutcome,
    val goal: String,
    val requirements: AgentTaskRequirements,
    val definition: AgentTeamDefinition?,
    val primaryAgentId: String?,
    val assignments: List<AgentDynamicTeamAssignment>,
    val unfilledRoles: Set<AgentDynamicTeamRole>,
    val warnings: List<String>,
    val estimatedCostUnits: Int,
    val failureDomains: Set<String>,
    val rationale: List<String>
)

/**
 * Builds a temporary team from named Agent registrations. It does not invent
 * generic providers or merge identities: every assignment remains bound to a
 * concrete Agent, installation, device, trust level, and failure domain.
 */
class AgentDynamicTeamCompiler(
    private val reputation: AgentReputationSnapshotProvider = AgentReputationSnapshotProvider.NONE
) {
    fun compile(
        request: AgentDynamicTeamRequest,
        registrations: Collection<AgentRegistration>,
        nowMillis: Long = System.currentTimeMillis()
    ): AgentDynamicTeamCompilation {
        val goal = request.goal.trim()
        require(goal.isNotBlank()) { "Dynamic Agent team goal must not be blank" }
        require(request.teamId.isNotBlank()) { "Dynamic Agent team id must not be blank" }
        val policy = request.policy
        val budget = policy.budget.normalized()
        val requirements = AgentTaskRequirementAnalyzer.analyze(goal)
        val index = AgentNetworkIndex(registrations, reputation)
        val warnings = mutableListOf<String>()
        val unfilledRoles = linkedSetOf<AgentDynamicTeamRole>()
        val assignments = mutableListOf<AgentDynamicTeamAssignment>()
        val leadSpec = RoleSpec(
            role = AgentDynamicTeamRole.LEAD,
            requiredAny = LEAD_CAPABILITIES,
            preferred = requirements.capabilities + AgentCapability.CHAT + AgentCapability.REASONING,
            objective = "Own the task, synthesize specialist evidence, and return one final result.",
            priority = 1_000
        )
        val lead = select(
            role = leadSpec,
            goal = goal,
            index = index,
            requirements = requirements,
            policy = policy,
            budget = budget,
            selected = assignments,
            reservedSlots = 0,
            nowMillis = nowMillis
        ) ?: return unavailable(
            request = request,
            requirements = requirements,
            warnings = listOf("No trusted and routable lead Agent satisfies the task boundary."),
            unfilledRoles = setOf(AgentDynamicTeamRole.LEAD)
        )
        val collectiveCapabilities = mandatoryCapabilities(requirements)
        assignments += lead.copy(
            requiredCapabilities = collectiveCapabilities.intersect(lead.registration.capabilities)
        )

        val verificationWanted = verificationWanted(goal, requirements, policy.verificationMode)
        val reserveVerifier = if (verificationWanted && budget.maxMembers > 1) 1 else 0
        addPinnedAssignments(
            policy = policy,
            index = index,
            goal = goal,
            requirements = requirements,
            budget = budget,
            assignments = assignments,
            warnings = warnings,
            reservedSlots = reserveVerifier,
            nowMillis = nowMillis
        )

        val roleSpecs = roleSpecs(goal, requirements)
        roleSpecs.forEach { role ->
            val leadCoversRole = assignments.first().registration.satisfies(role)
            val independentSpecialistWanted = policy.forceTeam || requirements.mode == AgentRoutingMode.QUALITY
            if (leadCoversRole && !independentSpecialistWanted) return@forEach
            val selected = select(
                role = role,
                goal = goal,
                index = index,
                requirements = requirements,
                policy = policy,
                budget = budget,
                selected = assignments,
                reservedSlots = reserveVerifier,
                nowMillis = nowMillis
            )
            if (selected == null) {
                if (!leadCoversRole) unfilledRoles += role.role
            } else {
                assignments += selected
            }
        }

        if (verificationWanted && assignments.size < budget.maxMembers) {
            val verifier = select(
                role = verifierSpec(goal, requirements),
                goal = "$goal independent verification",
                index = index,
                requirements = requirements,
                policy = policy,
                budget = budget,
                selected = assignments,
                reservedSlots = 0,
                nowMillis = nowMillis,
                requireIndependentFailureDomain = policy.verificationMode == AgentTeamVerificationMode.REQUIRED
            )
            if (verifier == null) {
                unfilledRoles += AgentDynamicTeamRole.VERIFIER
                warnings += "No independent verifier is currently available."
            } else {
                assignments += verifier
            }
        }

        val coveredCapabilities = assignments.flatMapTo(linkedSetOf()) {
            it.registration.capabilities
        }
        val missingCapabilities = collectiveCapabilities - coveredCapabilities
        if (missingCapabilities.isNotEmpty()) {
            warnings += "Missing task capabilities: ${missingCapabilities.sortedBy { it.name }.joinToString(",") { it.name }}"
            return unavailable(request, requirements, warnings, unfilledRoles, assignments)
        }
        if (policy.verificationMode == AgentTeamVerificationMode.REQUIRED &&
            assignments.none { it.role == AgentDynamicTeamRole.VERIFIER }
        ) {
            return blocked(request, requirements, warnings, unfilledRoles, assignments)
        }
        if (policy.forceTeam && assignments.size < 2) {
            warnings += "The requested team cannot be formed within the current trust and budget boundary."
            return blocked(request, requirements, warnings, unfilledRoles, assignments)
        }

        val teamAssignments = assignments.distinctBy { it.registration.agentId }
        val shouldUseTeam = teamAssignments.size >= 2 && (
            policy.forceTeam ||
                teamAssignments.any { it.role == AgentDynamicTeamRole.VERIFIER } ||
                teamAssignments.any { it.role != AgentDynamicTeamRole.LEAD }
            )
        if (!shouldUseTeam) {
            return result(
                outcome = AgentDynamicTeamOutcome.SINGLE_AGENT,
                request = request,
                requirements = requirements,
                definition = null,
                assignments = teamAssignments,
                unfilledRoles = unfilledRoles,
                warnings = warnings,
                collectiveCapabilities = collectiveCapabilities
            )
        }
        val definition = buildDefinition(
            request = request,
            assignments = teamAssignments,
            collectiveCapabilities = collectiveCapabilities
        )
        return result(
            outcome = AgentDynamicTeamOutcome.TEAM,
            request = request,
            requirements = requirements,
            definition = definition,
            assignments = teamAssignments,
            unfilledRoles = unfilledRoles,
            warnings = warnings,
            collectiveCapabilities = collectiveCapabilities
        )
    }

    private fun addPinnedAssignments(
        policy: AgentDynamicTeamPolicy,
        index: AgentNetworkIndex,
        goal: String,
        requirements: AgentTaskRequirements,
        budget: AgentTeamCompilationBudget,
        assignments: MutableList<AgentDynamicTeamAssignment>,
        warnings: MutableList<String>,
        reservedSlots: Int,
        nowMillis: Long
    ) {
        policy.pinnedAgentIds.sorted().forEach { agentId ->
            if (assignments.any { it.registration.agentId == agentId }) return@forEach
            if (assignments.size >= budget.maxMembers - reservedSlots) {
                warnings += "Pinned Agent $agentId exceeds the team member budget."
                return@forEach
            }
            val registration = index.get(agentId, nowMillis)
            if (registration == null ||
                !registration.isEligible(requirements, policy, budget, assignments)
            ) {
                warnings += "Pinned Agent $agentId is unavailable or outside the task boundary."
                return@forEach
            }
            assignments += AgentDynamicTeamAssignment(
                role = AgentDynamicTeamRole.REQUESTED_SPECIALIST,
                registration = registration,
                score = PINNED_AGENT_SCORE,
                requiredCapabilities = collectiveCapabilitiesForPinned(requirements, registration),
                objective = "Contribute the explicitly requested Agent expertise to: ${goal.take(MAX_OBJECTIVE_CHARACTERS)}",
                reasons = listOf("user_pinned", "identity:${registration.agentId}")
            )
        }
    }

    private fun select(
        role: RoleSpec,
        goal: String,
        index: AgentNetworkIndex,
        requirements: AgentTaskRequirements,
        policy: AgentDynamicTeamPolicy,
        budget: AgentTeamCompilationBudget,
        selected: List<AgentDynamicTeamAssignment>,
        reservedSlots: Int,
        nowMillis: Long,
        requireIndependentFailureDomain: Boolean = false
    ): AgentDynamicTeamAssignment? {
        if (selected.size >= budget.maxMembers - reservedSlots) return null
        val usedAgentIds = selected.mapTo(linkedSetOf()) { it.registration.agentId }
        val page = index.search(
            AgentNetworkSearchQuery(
                text = goal,
                requiredCapabilities = role.requiredAll,
                preferredCapabilities = role.preferred + role.requiredAny,
                kinds = role.allowedKinds,
                excludedAgentIds = policy.excludedAgentIds + usedAgentIds,
                trustedOnly = policy.trustedOnly,
                routableOnly = true,
                includeAtCapacity = false,
                maximumCost = budget.maximumMemberCost,
                maximumLatency = budget.maximumMemberLatency,
                pageSize = AgentNetworkSearchQuery.MAX_PAGE_SIZE
            ),
            nowMillis
        )
        val searchHits = buildList {
            addAll(page.hits)
            policy.pinnedAgentIds.sorted().forEach { agentId ->
                if (none { it.registration.agentId == agentId }) {
                    index.get(agentId, nowMillis)?.let { registration ->
                        add(AgentNetworkSearchHit(
                            registration = registration,
                            score = 0,
                            matchedCapabilities = registration.capabilities.intersect(role.preferred),
                            reasons = listOf("pinned_agent")
                        ))
                    }
                }
            }
        }
        val selectedDomains = selected.mapTo(linkedSetOf()) { it.failureDomain }
        val candidates = searchHits.asSequence()
            .filter { hit -> hit.registration.satisfies(role) }
            .filter { hit -> hit.registration.isEligible(requirements, policy, budget, selected) }
            .map { hit ->
                val domain = hit.registration.effectiveTeamFailureDomain()
                val independent = domain !in selectedDomains
                val diversityBonus =
                    if (policy.preferFailureDomainDiversity && independent) 180 else 0
                val pinnedAgentBonus =
                    if (hit.registration.agentId in policy.pinnedAgentIds) PINNED_AGENT_SCORE else 0
                RankedRoleCandidate(
                    hit = hit,
                    score = hit.score +
                        role.priority +
                        role.preferred.intersect(hit.registration.capabilities).size * 120 +
                        diversityBonus +
                        pinnedAgentBonus,
                    independent = independent
                )
            }
            .sortedWith(
                compareByDescending<RankedRoleCandidate> { it.score }
                    .thenBy { it.hit.registration.displayName.lowercase(Locale.ROOT) }
                    .thenBy { it.hit.registration.agentId }
            )
            .toList()
        val selectedCandidate = when {
            requireIndependentFailureDomain -> candidates.firstOrNull { it.independent }
            role.role == AgentDynamicTeamRole.VERIFIER ->
                candidates.firstOrNull { it.independent } ?: candidates.firstOrNull()
            else -> candidates.firstOrNull()
        } ?: return null
        val registration = selectedCandidate.hit.registration
        val anyCapability = role.requiredAny
            .intersect(registration.capabilities)
            .sortedBy { it.name }
            .firstOrNull()
        val requiredCapabilities = role.requiredAll + listOfNotNull(anyCapability)
        return AgentDynamicTeamAssignment(
            role = role.role,
            registration = registration,
            score = selectedCandidate.score,
            requiredCapabilities = requiredCapabilities,
            objective = role.objective,
            reasons = (
                selectedCandidate.hit.reasons +
                    "role:${role.role.name.lowercase(Locale.US)}" +
                    "failure_domain:${registration.effectiveTeamFailureDomain()}" +
                    if (selectedCandidate.independent) listOf("independent_failure_domain") else emptyList()
                ).distinct()
        )
    }

    private fun buildDefinition(
        request: AgentDynamicTeamRequest,
        assignments: List<AgentDynamicTeamAssignment>,
        collectiveCapabilities: Set<AgentCapability>
    ): AgentTeamDefinition {
        val lead = assignments.first { it.role == AgentDynamicTeamRole.LEAD }
        val observers = assignments.filterNot { it.role == AgentDynamicTeamRole.LEAD }
        val nonVerifierObservers = observers
            .filterNot { it.role == AgentDynamicTeamRole.VERIFIER }
            .mapTo(linkedSetOf()) { it.registration.agentId }
        val observerIds = observers.mapTo(linkedSetOf()) { it.registration.agentId }
        val members = assignments.map { assignment ->
            val dependencies = when (assignment.role) {
                AgentDynamicTeamRole.LEAD -> observerIds
                AgentDynamicTeamRole.VERIFIER -> nonVerifierObservers
                else -> emptySet()
            }
            AgentTeamMember(
                agentId = assignment.registration.agentId,
                deliveryMode = if (assignment.role == AgentDynamicTeamRole.LEAD) {
                    AgentDeliveryMode.RESPOND
                } else {
                    AgentDeliveryMode.OBSERVE
                },
                requiredCapabilities = assignment.requiredCapabilities,
                role = assignment.role.roleName(),
                objective = assignment.objective.take(MAX_OBJECTIVE_CHARACTERS),
                dependsOnAgentIds = dependencies,
                context = mapOf(
                    "compiled_role" to assignment.role.name.lowercase(Locale.US),
                    "agent_display_name" to assignment.registration.displayName,
                    "agent_failure_domain" to assignment.failureDomain
                )
            )
        }
        return AgentTeamDefinition(
            teamId = request.teamId,
            primaryAgentId = lead.registration.agentId,
            members = members,
            visibilityMode = request.policy.visibilityMode,
            collectiveCapabilities = collectiveCapabilities
        )
    }

    private fun result(
        outcome: AgentDynamicTeamOutcome,
        request: AgentDynamicTeamRequest,
        requirements: AgentTaskRequirements,
        definition: AgentTeamDefinition?,
        assignments: List<AgentDynamicTeamAssignment>,
        unfilledRoles: Set<AgentDynamicTeamRole>,
        warnings: List<String>,
        collectiveCapabilities: Set<AgentCapability>
    ): AgentDynamicTeamCompilation {
        val domains = assignments.mapTo(linkedSetOf(), AgentDynamicTeamAssignment::failureDomain)
        return AgentDynamicTeamCompilation(
            outcome = outcome,
            goal = request.goal.trim(),
            requirements = requirements,
            definition = definition,
            primaryAgentId = assignments.firstOrNull { it.role == AgentDynamicTeamRole.LEAD }
                ?.registration?.agentId,
            assignments = assignments,
            unfilledRoles = unfilledRoles,
            warnings = warnings.distinct(),
            estimatedCostUnits = assignments.sumOf { it.registration.cost.teamCostUnits() },
            failureDomains = domains,
            rationale = listOf(
                "outcome:${outcome.name.lowercase(Locale.US)}",
                "members:${assignments.size}",
                "collective_capabilities:${collectiveCapabilities.sortedBy { it.name }.joinToString(",") { it.name }}",
                "failure_domains:${domains.size}",
                "verification:${request.policy.verificationMode.name.lowercase(Locale.US)}"
            )
        )
    }

    private fun unavailable(
        request: AgentDynamicTeamRequest,
        requirements: AgentTaskRequirements,
        warnings: List<String>,
        unfilledRoles: Set<AgentDynamicTeamRole>,
        assignments: List<AgentDynamicTeamAssignment> = emptyList()
    ): AgentDynamicTeamCompilation = result(
        outcome = AgentDynamicTeamOutcome.UNAVAILABLE,
        request = request,
        requirements = requirements,
        definition = null,
        assignments = assignments,
        unfilledRoles = unfilledRoles,
        warnings = warnings,
        collectiveCapabilities = mandatoryCapabilities(requirements)
    )

    private fun blocked(
        request: AgentDynamicTeamRequest,
        requirements: AgentTaskRequirements,
        warnings: List<String>,
        unfilledRoles: Set<AgentDynamicTeamRole>,
        assignments: List<AgentDynamicTeamAssignment>
    ): AgentDynamicTeamCompilation = result(
        outcome = AgentDynamicTeamOutcome.BLOCKED,
        request = request,
        requirements = requirements,
        definition = null,
        assignments = assignments,
        unfilledRoles = unfilledRoles,
        warnings = warnings,
        collectiveCapabilities = mandatoryCapabilities(requirements)
    )

    private data class RankedRoleCandidate(
        val hit: AgentNetworkSearchHit,
        val score: Int,
        val independent: Boolean
    )

    private data class RoleSpec(
        val role: AgentDynamicTeamRole,
        val requiredAll: Set<AgentCapability> = emptySet(),
        val requiredAny: Set<AgentCapability> = emptySet(),
        val preferred: Set<AgentCapability> = emptySet(),
        val allowedKinds: Set<AgentConnectorKind> = setOf(AgentConnectorKind.AGENT),
        val objective: String,
        val priority: Int
    )

    private fun roleSpecs(goal: String, requirements: AgentTaskRequirements): List<RoleSpec> = buildList {
        if (AgentCapability.LIVE_DATA in requirements.capabilities) {
            add(RoleSpec(
                role = AgentDynamicTeamRole.RESEARCHER,
                requiredAll = setOf(AgentCapability.LIVE_DATA),
                preferred = setOf(AgentCapability.RESEARCH, AgentCapability.TOOL_USE),
                objective = "Collect current, attributable evidence needed for: ${goal.take(MAX_OBJECTIVE_CHARACTERS)}",
                priority = 760
            ))
        }
        if (AgentCapability.CODE in requirements.capabilities) {
            add(RoleSpec(
                role = AgentDynamicTeamRole.IMPLEMENTER,
                requiredAll = setOf(AgentCapability.CODE),
                preferred = setOf(AgentCapability.TASK_EXECUTION, AgentCapability.TOOL_USE),
                objective = "Implement and validate the technical work required for: ${goal.take(MAX_OBJECTIVE_CHARACTERS)}",
                priority = 800
            ))
        }
        if (AgentCapability.KNOWLEDGE_SEARCH in requirements.capabilities) {
            add(RoleSpec(
                role = AgentDynamicTeamRole.KNOWLEDGE_SPECIALIST,
                requiredAll = setOf(AgentCapability.KNOWLEDGE_SEARCH),
                preferred = setOf(AgentCapability.REASONING),
                objective = "Retrieve relevant private knowledge with evidence for: ${goal.take(MAX_OBJECTIVE_CHARACTERS)}",
                priority = 720
            ))
        }
        val deviceCapabilities = requirements.capabilities.intersect(
            setOf(AgentCapability.DEVICE_CONTROL, AgentCapability.APP_NAVIGATION)
        )
        if (deviceCapabilities.isNotEmpty()) {
            add(RoleSpec(
                role = AgentDynamicTeamRole.DEVICE_OPERATOR,
                requiredAll = deviceCapabilities,
                preferred = setOf(AgentCapability.TOOL_USE),
                allowedKinds = setOf(AgentConnectorKind.AGENT, AgentConnectorKind.DEVICE),
                objective = "Plan and perform the authorized device actions required for: ${goal.take(MAX_OBJECTIVE_CHARACTERS)}",
                priority = 820
            ))
        }
        val toolCapabilities = requirements.capabilities.intersect(
            setOf(AgentCapability.MCP, AgentCapability.SKILL)
        )
        if (toolCapabilities.isNotEmpty()) {
            add(RoleSpec(
                role = AgentDynamicTeamRole.TOOL_SPECIALIST,
                requiredAll = toolCapabilities,
                preferred = setOf(AgentCapability.TOOL_USE),
                objective = "Use the required MCP or Skill capabilities for: ${goal.take(MAX_OBJECTIVE_CHARACTERS)}",
                priority = 700
            ))
        }
        if (isEmpty() && requirements.complexReasoning) {
            add(RoleSpec(
                role = AgentDynamicTeamRole.ANALYST,
                requiredAny = setOf(AgentCapability.REASONING, AgentCapability.RESEARCH),
                preferred = setOf(AgentCapability.REASONING),
                objective = "Independently analyze assumptions and solution paths for: ${goal.take(MAX_OBJECTIVE_CHARACTERS)}",
                priority = 680
            ))
        }
    }

    private fun verifierSpec(goal: String, requirements: AgentTaskRequirements): RoleSpec {
        val preferred = buildSet {
            add(AgentCapability.REASONING)
            add(AgentCapability.RESEARCH)
            if (AgentCapability.CODE in requirements.capabilities) add(AgentCapability.CODE)
            if (AgentCapability.LIVE_DATA in requirements.capabilities) add(AgentCapability.LIVE_DATA)
        }
        return RoleSpec(
            role = AgentDynamicTeamRole.VERIFIER,
            requiredAny = preferred,
            preferred = preferred,
            objective = "Independently verify the team evidence, execution, and claims for: ${goal.take(MAX_OBJECTIVE_CHARACTERS)}",
            priority = 900
        )
    }

    private fun verificationWanted(
        goal: String,
        requirements: AgentTaskRequirements,
        mode: AgentTeamVerificationMode
    ): Boolean = when (mode) {
        AgentTeamVerificationMode.DISABLED -> false
        AgentTeamVerificationMode.REQUIRED -> true
        AgentTeamVerificationMode.AUTO -> {
            AgentCapability.CODE in requirements.capabilities ||
                requirements.mode == AgentRoutingMode.QUALITY ||
                requirements.dataSensitivity == AgentDataSensitivity.RESTRICTED ||
                VERIFICATION_TERMS.any(goal.lowercase(Locale.ROOT)::contains)
        }
    }

    private fun mandatoryCapabilities(requirements: AgentTaskRequirements): Set<AgentCapability> =
        requirements.capabilities.intersect(MANDATORY_COLLECTIVE_CAPABILITIES)

    private fun collectiveCapabilitiesForPinned(
        requirements: AgentTaskRequirements,
        registration: AgentRegistration
    ): Set<AgentCapability> = mandatoryCapabilities(requirements).intersect(registration.capabilities)

    private fun AgentRegistration.satisfies(role: RoleSpec): Boolean =
        capabilities.containsAll(role.requiredAll) &&
            (role.requiredAny.isEmpty() || capabilities.any(role.requiredAny::contains))

    private fun AgentRegistration.isEligible(
        requirements: AgentTaskRequirements,
        policy: AgentDynamicTeamPolicy,
        budget: AgentTeamCompilationBudget,
        selected: List<AgentDynamicTeamAssignment>
    ): Boolean {
        if (agentId in policy.excludedAgentIds) return false
        if (status !in TEAM_ROUTABLE_STATES || !hasCapacity) return false
        if (policy.trustedOnly && trust == AgentResourceTrust.UNKNOWN) return false
        if (requirements.localOnly && location == AgentResourceLocation.CLOUD) return false
        if (requirements.dataSensitivity == AgentDataSensitivity.RESTRICTED &&
            trust !in setOf(AgentResourceTrust.PHONE_SYSTEM, AgentResourceTrust.VERIFIED_PAIRED)
        ) return false
        if (selected.any {
                it.registration.effectiveTeamRuntimeIdentity() == effectiveTeamRuntimeIdentity()
            }
        ) return false
        if (cost.ordinal > budget.maximumMemberCost.ordinal) return false
        if (latency.ordinal > budget.maximumMemberLatency.ordinal) return false
        if (location == AgentResourceLocation.CLOUD &&
            selected.count { it.registration.location == AgentResourceLocation.CLOUD } >= budget.maxCloudMembers
        ) return false
        val projectedCost = selected.sumOf { it.registration.cost.teamCostUnits() } + cost.teamCostUnits()
        return projectedCost <= budget.maxEstimatedCostUnits
    }

    private fun AgentTeamCompilationBudget.normalized(): AgentTeamCompilationBudget = copy(
        maxMembers = maxMembers.coerceIn(1, MAX_TEAM_MEMBERS),
        maxCloudMembers = maxCloudMembers.coerceIn(0, MAX_TEAM_MEMBERS),
        maxEstimatedCostUnits = maxEstimatedCostUnits.coerceAtLeast(0)
    )

    private fun AgentResourceCost.teamCostUnits(): Int = when (this) {
        AgentResourceCost.FREE -> 0
        AgentResourceCost.LOW -> 1
        AgentResourceCost.MEDIUM -> 3
        AgentResourceCost.HIGH -> 8
    }

    private fun AgentDynamicTeamRole.roleName(): String = when (this) {
        AgentDynamicTeamRole.LEAD -> "lead synthesizer"
        AgentDynamicTeamRole.RESEARCHER -> "research specialist"
        AgentDynamicTeamRole.IMPLEMENTER -> "implementation specialist"
        AgentDynamicTeamRole.KNOWLEDGE_SPECIALIST -> "knowledge specialist"
        AgentDynamicTeamRole.DEVICE_OPERATOR -> "device operator"
        AgentDynamicTeamRole.TOOL_SPECIALIST -> "tool specialist"
        AgentDynamicTeamRole.ANALYST -> "analysis specialist"
        AgentDynamicTeamRole.VERIFIER -> "independent verifier"
        AgentDynamicTeamRole.REQUESTED_SPECIALIST -> "requested specialist"
    }

    private companion object {
        const val MAX_TEAM_MEMBERS = 12
        const val MAX_OBJECTIVE_CHARACTERS = 4_000
        const val PINNED_AGENT_SCORE = 4_000
        val LEAD_CAPABILITIES = setOf(
            AgentCapability.CHAT,
            AgentCapability.REASONING,
            AgentCapability.RESEARCH,
            AgentCapability.CODE,
            AgentCapability.TASK_EXECUTION,
            AgentCapability.TOOL_USE
        )
        val MANDATORY_COLLECTIVE_CAPABILITIES = setOf(
            AgentCapability.LIVE_DATA,
            AgentCapability.CODE,
            AgentCapability.DEVICE_CONTROL,
            AgentCapability.APP_NAVIGATION,
            AgentCapability.KNOWLEDGE_SEARCH,
            AgentCapability.MCP,
            AgentCapability.SKILL
        )
        val TEAM_ROUTABLE_STATES = setOf(
            AgentEndpointStatus.ONLINE,
            AgentEndpointStatus.IDLE,
            AgentEndpointStatus.BUSY
        )
        val VERIFICATION_TERMS = listOf(
            "verify", "validate", "audit", "double check", "critical", "security",
            "\u9a8c\u8bc1", "\u6821\u9a8c", "\u5ba1\u8ba1", "\u590d\u6838",
            "\u5173\u952e", "\u5b89\u5168"
        )
    }
}

internal fun AgentRegistration.effectiveTeamFailureDomain(): String =
    failureDomain.ifBlank {
        runtimeFailureDomain.ifBlank {
            "${location.name.lowercase(Locale.US)}:${deviceId.ifBlank { installationId }}"
        }
    }

internal fun AgentRegistration.effectiveTeamRuntimeIdentity(): String =
    runtimeFailureDomain.ifBlank {
        "${effectiveTeamFailureDomain()}:${adapterType.ifBlank { agentId }}"
    }
