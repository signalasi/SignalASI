package com.signalasi.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

enum class AgentResourceType {
    ON_DEVICE_MODEL,
    REMOTE_LOCAL_MODEL,
    CLOUD_MODEL,
    LOCAL_AGENT,
    REMOTE_AGENT,
    LOCAL_TOOL,
    LOCAL_MCP,
    REMOTE_MCP,
    CLOUD_MCP,
    LOCAL_SKILL,
    REMOTE_SKILL,
    CLOUD_SKILL,
    HOME_ASSISTANT,
    CUSTOM_DEVICE,
    KNOWLEDGE
}

enum class AgentResourceLocation { PHONE, TRUSTED_DESKTOP, PRIVATE_NETWORK, CLOUD }
enum class AgentResourceCost { FREE, LOW, MEDIUM, HIGH }
enum class AgentResourceLatency { INSTANT, FAST, NORMAL, SLOW }
enum class AgentResourceQuality { BASIC, STANDARD, STRONG, FRONTIER }
enum class AgentRoutingMode { BALANCED, FAST, ECONOMY, QUALITY, PRIVATE }
enum class AgentResourceTrust { PHONE_SYSTEM, VERIFIED_PAIRED, PRIVATE_CONFIGURED, CLOUD_CONFIGURED, UNKNOWN }
enum class AgentResourceEnergy { MINIMAL, LOW, MODERATE, HIGH }
enum class AgentDataSensitivity { PUBLIC, PERSONAL, CONFIDENTIAL, RESTRICTED }
enum class AgentExecutionHorizon { INTERACTIVE, BACKGROUND, LONG_RUNNING }

data class AgentResourceDescriptor(
    val id: String,
    val title: String,
    val type: AgentResourceType,
    val location: AgentResourceLocation,
    val status: AgentConnectorStatus,
    val capabilities: Set<AgentCapability>,
    val cost: AgentResourceCost,
    val latency: AgentResourceLatency,
    val quality: AgentResourceQuality,
    val supportsTools: Boolean,
    val targetId: String = "",
    val trust: AgentResourceTrust = AgentResourceTrust.UNKNOWN,
    val energy: AgentResourceEnergy = AgentResourceEnergy.LOW,
    val contextWindowTokens: Int = 8_192,
    val supportsStreaming: Boolean = false,
    val supportsBackground: Boolean = false,
    val activeTasks: Int = 0,
    val maxParallelTasks: Int = 1,
    val failureDomain: String = ""
)

data class AgentTaskRequirements(
    val capabilities: Set<AgentCapability>,
    val mode: AgentRoutingMode,
    val liveDataRequired: Boolean,
    val localOnly: Boolean,
    val complexReasoning: Boolean,
    val estimatedInputTokens: Int,
    val dataSensitivity: AgentDataSensitivity = AgentDataSensitivity.PERSONAL,
    val executionHorizon: AgentExecutionHorizon = AgentExecutionHorizon.INTERACTIVE
)

data class AgentRuntimeEnvironment(
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    val powerSaveMode: Boolean = false,
    val networkAvailable: Boolean = false,
    val networkValidated: Boolean = false,
    val networkMetered: Boolean = false
) {
    val energyConstrained: Boolean get() = powerSaveMode || (!charging && batteryPercent in 0..19)
}

data class AgentResourceCandidate(
    val resource: AgentResourceDescriptor,
    val score: Int,
    val reasons: List<String>
)

data class AgentRoutingDecision(
    val requirements: AgentTaskRequirements,
    val primary: AgentResourceCandidate?,
    val fallbacks: List<AgentResourceCandidate>,
    val environment: AgentRuntimeEnvironment = AgentRuntimeEnvironment()
) {
    val orderedTargetIds: List<String>
        get() = listOfNotNull(primary?.resource?.targetId?.takeIf { it.isNotBlank() }) +
            fallbacks.mapNotNull { it.resource.targetId.takeIf(String::isNotBlank) }
}

object AgentFailoverPolicy {
    fun fallbackTier(primary: AgentResourceDescriptor?, candidate: AgentResourceDescriptor): Int {
        if (primary?.location != AgentResourceLocation.TRUSTED_DESKTOP) return 0
        return when {
            candidate.location == AgentResourceLocation.CLOUD -> 0
            candidate.location == AgentResourceLocation.PHONE -> 0
            candidate.failureDomain != primary.failureDomain -> 1
            else -> 2
        }
    }

    fun shouldFailOver(stage: AgentConnectorTimeoutStage, status: String, liveReadOnly: Boolean): Boolean = when (stage) {
        AgentConnectorTimeoutStage.NOT_ACCEPTED -> status.isBlank()
        AgentConnectorTimeoutStage.NOT_RUNNING -> status.isBlank() || status in setOf("accepted", "queued", "starting")
        AgentConnectorTimeoutStage.READ_ONLY_STALE -> liveReadOnly && status == "running"
    }

    fun shouldKeepOnlyResourceAlive(
        stage: AgentConnectorTimeoutStage,
        status: String,
        hasFallback: Boolean
    ): Boolean {
        if (hasFallback) return false
        return when (stage) {
            AgentConnectorTimeoutStage.NOT_ACCEPTED -> status.isBlank()
            AgentConnectorTimeoutStage.NOT_RUNNING ->
                status.isBlank() || status in setOf("accepted", "queued", "starting")
            AgentConnectorTimeoutStage.READ_ONLY_STALE -> false
        }
    }

    fun domainCooldownMs(consecutiveFailures: Int): Long = when (consecutiveFailures.coerceAtLeast(1)) {
        1 -> 60_000L
        2 -> 5 * 60_000L
        3 -> 15 * 60_000L
        else -> 60 * 60_000L
    }
}

data class AgentConnectorTimeoutSchedule(
    val acceptedMs: Long,
    val runningMs: Long,
    val liveStaleMs: Long
)

object AgentConnectorTimingPolicy {
    private val interactive = AgentConnectorTimeoutSchedule(
        acceptedMs = 5_000L,
        runningMs = 8_000L,
        liveStaleMs = 15_000L
    )
    private val attachment = AgentConnectorTimeoutSchedule(
        acceptedMs = 15_000L,
        runningMs = 30_000L,
        liveStaleMs = 45_000L
    )

    fun deadlines(hasAttachments: Boolean): AgentConnectorTimeoutSchedule =
        if (hasAttachments) attachment else interactive
}

object AgentTaskRequirementAnalyzer {
    private val liveTerms = listOf(
        "weather", "forecast", "today", "current", "latest", "news", "price", "traffic", "score", "now", "live",
        "search the web", "web search", "search online", "look up online",
        "\u5929\u6c14", "\u9884\u62a5", "\u4eca\u5929", "\u5f53\u524d", "\u6700\u65b0", "\u65b0\u95fb", "\u4ef7\u683c", "\u8def\u51b5", "\u6bd4\u5206", "\u73b0\u5728", "\u5b9e\u65f6",
        "\u8054\u7f51\u641c\u7d22", "\u7f51\u4e0a\u641c\u7d22", "\u7f51\u7edc\u641c\u7d22"
    )
    private val codeTerms = listOf(
        "code", "python", "program", "script", "debug", "repository", "compile", "build", "codex",
        "verify the program", "test the program", "\u4ee3\u7801", "\u7a0b\u5e8f", "\u811a\u672c", "\u7f16\u7a0b", "\u5f00\u53d1",
        "\u8fd0\u884c\u9a8c\u8bc1", "\u7f16\u8bd1", "\u9879\u76ee", "\u4fee\u590d bug"
    )
    private val deviceTerms = listOf("home assistant", "smart home", "light", "scene", "device", "\u667a\u80fd\u5bb6\u5c45", "\u5f00\u706f", "\u5173\u706f", "\u8bbe\u5907", "\u573a\u666f")
    private val screenTerms = listOf("screen", "tap", "click", "swipe", "open app", "\u5c4f\u5e55", "\u70b9\u51fb", "\u6ed1\u52a8", "\u6253\u5f00 app")
    private val knowledgeTerms = listOf("knowledge", "memory", "document", "pdf", "\u77e5\u8bc6\u5e93", "\u8bb0\u5fc6", "\u6587\u6863")
    private val mcpTerms = listOf("mcp", "model context protocol", "\u4e0a\u4e0b\u6587\u534f\u8bae")
    private val skillTerms = listOf("skill", "skills", "\u6280\u80fd")
    private val privateTerms = listOf("private", "local only", "offline", "password", "secret", "\u9690\u79c1", "\u4ec5\u672c\u5730", "\u79bb\u7ebf", "\u5bc6\u7801", "\u79d8\u5bc6")
    private val fastTerms = listOf("fast", "quick", "low latency", "\u5feb\u901f", "\u7acb\u5373", "\u4f4e\u5ef6\u8fdf")
    private val economyTerms = listOf("cheap", "save token", "few tokens", "economy", "\u7701 token", "\u7701\u8d39\u7528", "\u7ecf\u6d4e")
    private val qualityTerms = listOf("best", "strongest", "deep reasoning", "high quality", "\u6700\u5f3a", "\u6700\u597d", "\u6df1\u5ea6\u601d\u8003", "\u9ad8\u8d28\u91cf")
    private val backgroundTerms = listOf("background", "later", "schedule", "monitor", "overnight", "\u540e\u53f0", "\u7a0d\u540e", "\u5b9a\u65f6", "\u76d1\u63a7", "\u6574\u591c")
    private val longRunningTerms = listOf("long running", "keep running", "until complete", "\u957f\u65f6\u95f4", "\u6301\u7eed\u8fd0\u884c", "\u76f4\u5230\u5b8c\u6210")
    private val confidentialTerms = listOf("sms", "contact", "calendar", "health", "medical", "private message", "\u77ed\u4fe1", "\u8054\u7cfb\u4eba", "\u65e5\u5386", "\u5065\u5eb7", "\u533b\u7597", "\u79c1\u4fe1")
    private val restrictedTerms = listOf("password", "private key", "seed phrase", "biometric", "payment", "bank", "identity document", "\u5bc6\u7801", "\u79c1\u94a5", "\u52a9\u8bb0\u8bcd", "\u751f\u7269\u8bc6\u522b", "\u652f\u4ed8", "\u94f6\u884c", "\u8eab\u4efd\u8bc1")

    fun analyze(goal: String): AgentTaskRequirements {
        val lower = goal.lowercase(Locale.US)
        val live = lower.containsAny(liveTerms)
        val code = lower.containsAny(codeTerms)
        val device = lower.containsAny(deviceTerms)
        val screen = lower.containsAny(screenTerms)
        val knowledge = lower.containsAny(knowledgeTerms)
        val mcp = lower.containsAny(mcpTerms)
        val skill = lower.containsAny(skillTerms)
        val localOnly = lower.containsAny(privateTerms)
        val complex = code || lower.length > 220 || qualityTerms.any(lower::contains)
        val sensitivity = when {
            lower.containsAny(restrictedTerms) -> AgentDataSensitivity.RESTRICTED
            lower.containsAny(confidentialTerms) -> AgentDataSensitivity.CONFIDENTIAL
            lower.containsAny(privateTerms) -> AgentDataSensitivity.CONFIDENTIAL
            else -> AgentDataSensitivity.PERSONAL
        }
        val horizon = when {
            lower.containsAny(longRunningTerms) -> AgentExecutionHorizon.LONG_RUNNING
            lower.containsAny(backgroundTerms) -> AgentExecutionHorizon.BACKGROUND
            else -> AgentExecutionHorizon.INTERACTIVE
        }
        val capabilities = buildSet {
            add(AgentCapability.CHAT)
            if (live) {
                add(AgentCapability.LIVE_DATA)
                add(AgentCapability.TOOL_USE)
            }
            if (code) {
                add(AgentCapability.CODE)
                add(AgentCapability.TASK_EXECUTION)
            }
            if (device) add(AgentCapability.DEVICE_CONTROL)
            if (screen) add(AgentCapability.APP_NAVIGATION)
            if (knowledge) add(AgentCapability.KNOWLEDGE_SEARCH)
            if (mcp) {
                add(AgentCapability.MCP)
                add(AgentCapability.TOOL_USE)
            }
            if (skill) {
                add(AgentCapability.SKILL)
                add(AgentCapability.TOOL_USE)
            }
            if (complex) add(AgentCapability.REASONING)
        }
        val mode = when {
            localOnly -> AgentRoutingMode.PRIVATE
            lower.containsAny(fastTerms) -> AgentRoutingMode.FAST
            lower.containsAny(economyTerms) -> AgentRoutingMode.ECONOMY
            lower.containsAny(qualityTerms) -> AgentRoutingMode.QUALITY
            else -> AgentRoutingMode.BALANCED
        }
        return AgentTaskRequirements(
            capabilities = capabilities,
            mode = mode,
            liveDataRequired = live,
            localOnly = localOnly,
            complexReasoning = complex,
            estimatedInputTokens = max(64, goal.length / 3),
            dataSensitivity = sensitivity,
            executionHorizon = horizon
        )
    }

    private fun String.containsAny(terms: List<String>): Boolean = terms.any(::contains)
}

class AgentResourceHealthStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("signalasi_agent_resource_health", Context.MODE_PRIVATE)

    fun snapshot(id: String): AgentResourceHealth {
        val raw = prefs.getString(id, "")?.takeIf { it.isNotBlank() } ?: return AgentResourceHealth()
        return decode(raw)
    }

    fun snapshots(): Map<String, AgentResourceHealth> = prefs.all.mapNotNull { (id, raw) ->
        val value = raw as? String ?: return@mapNotNull null
        id.takeIf(String::isNotBlank)?.let { it to decode(value) }
    }.toMap()

    fun record(id: String, success: Boolean, latencyMs: Long) {
        val current = snapshot(id)
        val successes = current.successes + if (success) 1 else 0
        val failures = current.failures + if (success) 0 else 1
        val consecutive = if (success) 0 else current.consecutiveFailures + 1
        val samples = max(1, successes + failures)
        val average = if (current.averageLatencyMs <= 0) latencyMs else
            ((current.averageLatencyMs * (samples - 1)) + latencyMs) / samples
        val backoffMultiplier = if (consecutive < 3) 0L else 1L shl (consecutive - 3).coerceIn(0, 4)
        val circuitUntil = if (backoffMultiplier > 0) {
            System.currentTimeMillis() + (60_000L * backoffMultiplier).coerceAtMost(15L * 60_000L)
        } else 0L
        val now = System.currentTimeMillis()
        persist(id, current, AgentResourceHealth(
            successes = successes,
            failures = failures,
            consecutiveFailures = consecutive,
            averageLatencyMs = average,
            circuitOpenUntil = circuitUntil,
            lastUpdatedAt = now
        ), now)
    }

    fun markAvailable(id: String) {
        val current = snapshot(id)
        val now = System.currentTimeMillis()
        persist(id, current, current.copy(
            consecutiveFailures = 0,
            circuitOpenUntil = 0L,
            lastUpdatedAt = now
        ), now)
    }

    fun recordFailureDomainTimeout(id: String, latencyMs: Long) {
        val current = snapshot(id)
        val consecutive = current.consecutiveFailures + 1
        val failures = current.failures + 1
        val samples = max(1, current.successes + failures)
        val average = if (current.averageLatencyMs <= 0) latencyMs else
            ((current.averageLatencyMs * (samples - 1)) + latencyMs) / samples
        val cooldown = AgentFailoverPolicy.domainCooldownMs(consecutive)
        val now = System.currentTimeMillis()
        persist(id, current, AgentResourceHealth(
            successes = current.successes,
            failures = failures,
            consecutiveFailures = consecutive,
            averageLatencyMs = average,
            circuitOpenUntil = now + cooldown,
            lastUpdatedAt = now
        ), now)
    }

    private fun persist(
        id: String,
        before: AgentResourceHealth,
        after: AgentResourceHealth,
        timestampMillis: Long
    ) {
        if (id.isBlank()) return
        prefs.edit().putString(id, encode(after)).apply()
        GlobalCapabilityObservationExtractor.resourceHealthTransition(
            id,
            before,
            after,
            timestampMillis
        )?.let { event ->
            GlobalConversationEventBus.publishCapabilityEvents(appContext, listOf(event))
        }
    }

    private fun encode(value: AgentResourceHealth): String = JSONObject()
        .put("successes", value.successes)
        .put("failures", value.failures)
        .put("consecutive_failures", value.consecutiveFailures)
        .put("average_latency_ms", value.averageLatencyMs)
        .put("circuit_open_until", value.circuitOpenUntil)
        .put("last_updated_at", value.lastUpdatedAt)
        .toString()

    private fun decode(raw: String): AgentResourceHealth {
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        return AgentResourceHealth(
            successes = json.optInt("successes"),
            failures = json.optInt("failures"),
            consecutiveFailures = json.optInt("consecutive_failures"),
            averageLatencyMs = json.optLong("average_latency_ms"),
            circuitOpenUntil = json.optLong("circuit_open_until"),
            lastUpdatedAt = json.optLong("last_updated_at")
        )
    }
}

data class AgentResourceHealth(
    val successes: Int = 0,
    val failures: Int = 0,
    val consecutiveFailures: Int = 0,
    val averageLatencyMs: Long = 0,
    val circuitOpenUntil: Long = 0,
    val lastUpdatedAt: Long = 0
) {
    val circuitOpen: Boolean get() = circuitOpenUntil > System.currentTimeMillis()
    val reliabilityPercent: Int get() = if (successes + failures == 0) 90 else successes * 100 / (successes + failures)
}

object AgentRuntimeEnvironmentProbe {
    fun probe(context: Context): AgentRuntimeEnvironment {
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return AgentRuntimeEnvironment(
            batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            charging = battery.isCharging,
            powerSaveMode = power.isPowerSaveMode,
            networkAvailable = capabilities != null,
            networkValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            networkMetered = connectivity.isActiveNetworkMetered
        )
    }
}

object AgentResourceCatalog {
    fun build(targets: List<AgentCallableTarget>, tools: List<AgentSystemTool>): List<AgentResourceDescriptor> {
        val callable = targets.map(::fromTarget)
        val localTools = tools.map { tool ->
            AgentResourceDescriptor(
                id = "tool:${tool.id}",
                title = tool.title,
                type = when {
                    tool.id.startsWith("workflow:") || tool.id.startsWith("template:") -> AgentResourceType.LOCAL_SKILL
                    tool.id.contains("mcp", ignoreCase = true) -> AgentResourceType.LOCAL_MCP
                    else -> AgentResourceType.LOCAL_TOOL
                },
                location = AgentResourceLocation.PHONE,
                status = AgentConnectorStatus.AVAILABLE,
                capabilities = tool.capabilities.toSet(),
                cost = AgentResourceCost.FREE,
                latency = AgentResourceLatency.INSTANT,
                quality = AgentResourceQuality.STANDARD,
                supportsTools = true,
                trust = AgentResourceTrust.PHONE_SYSTEM,
                energy = AgentResourceEnergy.MINIMAL,
                contextWindowTokens = 0,
                supportsStreaming = false,
                supportsBackground = false,
                maxParallelTasks = 4,
                failureDomain = "phone"
            )
        }
        return callable + localTools
    }

    private fun fromTarget(target: AgentCallableTarget): AgentResourceDescriptor {
        val id = target.id.lowercase(Locale.US)
        val type = when {
            id == "home-assistant" -> AgentResourceType.HOME_ASSISTANT
            id.startsWith("custom-device:") -> AgentResourceType.CUSTOM_DEVICE
            id.contains("mcp") -> AgentResourceType.REMOTE_MCP
            id.contains("skill") -> AgentResourceType.REMOTE_SKILL
            target.kind == AgentConnectorKind.KNOWLEDGE -> AgentResourceType.KNOWLEDGE
            target.kind == AgentConnectorKind.MODEL &&
                (id == "local-llm" || AgentCapability.LOCAL_INFERENCE in target.capabilities) -> AgentResourceType.REMOTE_LOCAL_MODEL
            target.kind == AgentConnectorKind.MODEL -> AgentResourceType.CLOUD_MODEL
            target.kind == AgentConnectorKind.AGENT -> AgentResourceType.REMOTE_AGENT
            else -> AgentResourceType.CUSTOM_DEVICE
        }
        val location = when (type) {
            AgentResourceType.ON_DEVICE_MODEL, AgentResourceType.LOCAL_AGENT, AgentResourceType.LOCAL_TOOL,
            AgentResourceType.LOCAL_MCP, AgentResourceType.LOCAL_SKILL, AgentResourceType.KNOWLEDGE -> AgentResourceLocation.PHONE
            AgentResourceType.REMOTE_LOCAL_MODEL, AgentResourceType.REMOTE_AGENT,
            AgentResourceType.REMOTE_MCP, AgentResourceType.REMOTE_SKILL -> AgentResourceLocation.TRUSTED_DESKTOP
            AgentResourceType.HOME_ASSISTANT, AgentResourceType.CUSTOM_DEVICE -> AgentResourceLocation.PRIVATE_NETWORK
            else -> AgentResourceLocation.CLOUD
        }
        val profile = when (type) {
            AgentResourceType.CLOUD_MODEL -> Triple(AgentResourceCost.MEDIUM, AgentResourceLatency.NORMAL, AgentResourceQuality.FRONTIER)
            AgentResourceType.REMOTE_AGENT, AgentResourceType.REMOTE_MCP, AgentResourceType.REMOTE_SKILL -> Triple(AgentResourceCost.LOW, AgentResourceLatency.SLOW, AgentResourceQuality.STRONG)
            AgentResourceType.REMOTE_LOCAL_MODEL -> Triple(AgentResourceCost.FREE, AgentResourceLatency.FAST, AgentResourceQuality.STANDARD)
            AgentResourceType.HOME_ASSISTANT, AgentResourceType.CUSTOM_DEVICE -> Triple(AgentResourceCost.FREE, AgentResourceLatency.FAST, AgentResourceQuality.STANDARD)
            else -> Triple(AgentResourceCost.FREE, AgentResourceLatency.INSTANT, AgentResourceQuality.STANDARD)
        }
        val trust = when (location) {
            AgentResourceLocation.PHONE -> AgentResourceTrust.PHONE_SYSTEM
            AgentResourceLocation.TRUSTED_DESKTOP -> AgentResourceTrust.VERIFIED_PAIRED
            AgentResourceLocation.PRIVATE_NETWORK -> AgentResourceTrust.PRIVATE_CONFIGURED
            AgentResourceLocation.CLOUD -> AgentResourceTrust.CLOUD_CONFIGURED
        }
        val energy = when (type) {
            AgentResourceType.ON_DEVICE_MODEL -> AgentResourceEnergy.HIGH
            AgentResourceType.LOCAL_AGENT, AgentResourceType.LOCAL_MCP, AgentResourceType.LOCAL_SKILL -> AgentResourceEnergy.MODERATE
            AgentResourceType.LOCAL_TOOL, AgentResourceType.KNOWLEDGE -> AgentResourceEnergy.MINIMAL
            else -> AgentResourceEnergy.LOW
        }
        val contextWindow = when (type) {
            AgentResourceType.CLOUD_MODEL -> 128_000
            AgentResourceType.REMOTE_AGENT -> 64_000
            AgentResourceType.REMOTE_LOCAL_MODEL, AgentResourceType.ON_DEVICE_MODEL -> 16_000
            else -> 8_192
        }
        return AgentResourceDescriptor(
            id = "target:${target.id}",
            title = target.title,
            type = type,
            location = location,
            status = target.status,
            capabilities = target.capabilities.toSet(),
            cost = profile.first,
            latency = profile.second,
            quality = profile.third,
            supportsTools = AgentCapability.TOOL_USE in target.capabilities || AgentCapability.RESEARCH in target.capabilities,
            targetId = target.id,
            trust = trust,
            energy = energy,
            contextWindowTokens = contextWindow,
            supportsStreaming = type == AgentResourceType.CLOUD_MODEL || type == AgentResourceType.REMOTE_AGENT ||
                type == AgentResourceType.REMOTE_LOCAL_MODEL,
            supportsBackground = type == AgentResourceType.REMOTE_AGENT || type == AgentResourceType.REMOTE_LOCAL_MODEL ||
                type == AgentResourceType.REMOTE_MCP || type == AgentResourceType.REMOTE_SKILL,
            maxParallelTasks = when (type) {
                AgentResourceType.REMOTE_AGENT -> 4
                AgentResourceType.CLOUD_MODEL -> 3
                AgentResourceType.REMOTE_LOCAL_MODEL -> 2
                else -> 1
            },
            failureDomain = target.failureDomain.ifBlank {
                when (location) {
                    AgentResourceLocation.PHONE -> "phone"
                    AgentResourceLocation.CLOUD -> "cloud:${target.id}"
                    AgentResourceLocation.TRUSTED_DESKTOP -> "desktop:${target.id.substringBefore(':')}"
                    AgentResourceLocation.PRIVATE_NETWORK -> "private:${target.id}"
                }
            }
        )
    }
}

class AgentResourceRouter(context: Context) {
    private val appContext = context.applicationContext
    private val healthStore = AgentResourceHealthStore(appContext)
    private val modelUsageStore = GlobalModelCallBudgetStore(appContext)
    private val selfModelStore = AgentSelfModelStore(appContext)

    fun route(goal: String, targets: List<AgentCallableTarget>, tools: List<AgentSystemTool>): AgentRoutingDecision {
        val requirements = AgentTaskRequirementAnalyzer.analyze(goal)
        val environment = AgentRuntimeEnvironmentProbe.probe(appContext)
        val hasPairedDesktop = SignalASILinkProtocol.allServerLinks(appContext).any { it.paired }
        val preferredTargets = preferredTargetOrder(requirements, hasPairedDesktop)
        val registrations = EncryptedAgentRegistry(appContext).list()
        val observedUsage = modelUsageStore.resourceUsageSnapshots()
        val selfModel = selfModelStore.snapshot()
        val candidates = AgentResourceCatalog.build(targets, tools)
            .asSequence()
            .map { resource -> projectRegistration(resource, registrations) }
            .filter { it.targetId.isNotBlank() }
            .distinctBy { resource -> "${canonicalTargetId(resource.targetId)}|${resource.failureDomain}" }
            .map { resource ->
                val candidate = score(
                    resource,
                    requirements,
                    environment,
                    observedUsage[resource.targetId] ?: GlobalModelResourceUsageSnapshot(resource.targetId)
                )
                val preference = preferenceBonus(resource.targetId, preferredTargets)
                val selfCalibration = AgentSelfModelReducer.calibration(
                    model = selfModel,
                    goal = goal,
                    resourceId = resource.targetId,
                    requirements = requirements
                )
                candidate.copy(
                    score = candidate.score + preference + selfCalibration.scoreAdjustment,
                    reasons = candidate.reasons + "preference_bonus:$preference" + listOfNotNull(
                        selfCalibration.reason.takeIf(String::isNotBlank)?.let { reason ->
                            "$reason:${selfCalibration.scoreAdjustment}"
                        }
                    )
                )
            }
            .filter { it.score > -1_000 }
            .sortedByDescending { it.score }
            .toList()
        val fallbackLimit = 6
        val primary = candidates.firstOrNull()
        val fallbacks = candidates.drop(1)
            .sortedWith(
                compareBy<AgentResourceCandidate> { AgentFailoverPolicy.fallbackTier(primary?.resource, it.resource) }
                    .thenByDescending { it.score }
            )
            .take(fallbackLimit)
        return AgentRoutingDecision(requirements, primary, fallbacks, environment)
    }

    private fun projectRegistration(
        resource: AgentResourceDescriptor,
        registrations: List<AgentRegistration>
    ): AgentResourceDescriptor {
        val exact = registrations.firstOrNull { it.agentId == resource.targetId }
        val canonical = canonicalTargetId(resource.targetId)
        val registration = exact ?: registrations.firstOrNull { candidate ->
            canonicalTargetId(candidate.agentId) == canonical &&
                (resource.failureDomain.isBlank() || candidate.failureDomain.isBlank() ||
                    candidate.failureDomain == resource.failureDomain)
        } ?: return resource
        val projectedStatus = when (registration.status) {
            AgentEndpointStatus.ONLINE,
            AgentEndpointStatus.IDLE,
            AgentEndpointStatus.BUSY -> AgentConnectorStatus.AVAILABLE
            AgentEndpointStatus.PERMISSION_REQUIRED,
            AgentEndpointStatus.UPDATING -> AgentConnectorStatus.NEEDS_SETUP
            AgentEndpointStatus.OFFLINE,
            AgentEndpointStatus.DEGRADED,
            AgentEndpointStatus.UNREACHABLE -> AgentConnectorStatus.DISCONNECTED
        }
        return resource.copy(
            status = projectedStatus,
            capabilities = registration.capabilities,
            cost = registration.cost,
            latency = registration.latency,
            trust = registration.trust,
            activeTasks = registration.activeRuns,
            maxParallelTasks = registration.maxParallelRuns,
            failureDomain = registration.failureDomain.ifBlank { resource.failureDomain }
        )
    }

    private fun preferredTargetOrder(
        requirements: AgentTaskRequirements,
        hasPairedDesktop: Boolean
    ): List<String> {
        val remainder = when {
            AgentCapability.CODE in requirements.capabilities -> listOf("cloud-models", "local-llm")
            AgentCapability.KNOWLEDGE_SEARCH in requirements.capabilities -> listOf("cloud-models", "local-llm")
            requirements.liveDataRequired -> listOf("cloud-models")
            requirements.mode == AgentRoutingMode.QUALITY -> listOf("cloud-models")
            requirements.mode == AgentRoutingMode.FAST -> listOf("local-llm")
            requirements.mode == AgentRoutingMode.ECONOMY -> listOf("local-llm", "cloud-models")
            requirements.mode == AgentRoutingMode.PRIVATE -> listOf("local-llm")
            else -> listOf("cloud-models", "local-llm")
        }
        val desktopAgents = if (!hasPairedDesktop) emptyList() else buildList {
            add("codex")
            if (AgentCapability.CODE in requirements.capabilities) add("claude-code")
        }
        return (desktopAgents + remainder + if (hasPairedDesktop) listOf("hermes") else emptyList())
            .map(::canonicalTargetId)
            .distinct()
    }

    private fun preferenceBonus(targetId: String, preferredTargets: List<String>): Int {
        val index = preferredTargets.indexOf(canonicalTargetId(targetId))
        return when (index) {
            0 -> 180
            1 -> 130
            2 -> 90
            3 -> 50
            in 4..Int.MAX_VALUE -> 20
            else -> 0
        }
    }

    private fun canonicalTargetId(targetId: String): String {
        val id = targetId.lowercase(Locale.US)
        return when {
            id == "claude" || id == "claude-code" -> "claude-code"
            id == "cloud-models" || id.startsWith("cloud-model:") -> "cloud-models"
            id.contains(":codex") || id == "codex" -> "codex"
            id.contains(":hermes") || id == "hermes" -> "hermes"
            id.contains(":local-llm") || id == "local-llm" -> "local-llm"
            else -> id
        }
    }

    private fun score(
        resource: AgentResourceDescriptor,
        requirements: AgentTaskRequirements,
        environment: AgentRuntimeEnvironment,
        observedUsage: GlobalModelResourceUsageSnapshot
    ): AgentResourceCandidate {
        val reasons = mutableListOf<String>()
        if (resource.status != AgentConnectorStatus.AVAILABLE) {
            return AgentResourceCandidate(resource, -10_000, listOf("unavailable"))
        }
        if (resource.activeTasks >= resource.maxParallelTasks.coerceAtLeast(1)) {
            return AgentResourceCandidate(resource, -9_500, listOf("at_capacity"))
        }
        val health = healthStore.snapshot(resource.id)
        if (health.circuitOpen) return AgentResourceCandidate(resource, -9_000, listOf("circuit_open"))
        val domainHealth = resource.failureDomain.takeIf { it.isNotBlank() }
            ?.let { healthStore.snapshot("domain:$it") }
            ?: AgentResourceHealth()
        if (domainHealth.circuitOpen) {
            return AgentResourceCandidate(resource, -9_100, listOf("failure_domain_circuit_open:${resource.failureDomain}"))
        }
        if (requirements.localOnly && resource.location == AgentResourceLocation.CLOUD) {
            return AgentResourceCandidate(resource, -8_000, listOf("privacy_boundary"))
        }
        if (requirements.dataSensitivity == AgentDataSensitivity.RESTRICTED &&
            resource.trust != AgentResourceTrust.PHONE_SYSTEM &&
            resource.trust != AgentResourceTrust.VERIFIED_PAIRED
        ) {
            return AgentResourceCandidate(resource, -8_500, listOf("restricted_data_boundary"))
        }
        if (!environment.networkAvailable && resource.location != AgentResourceLocation.PHONE) {
            return AgentResourceCandidate(resource, -8_200, listOf("network_unavailable"))
        }
        val missing = requirements.capabilities - resource.capabilities
        val mandatory = requirements.capabilities.intersect(MANDATORY_CAPABILITIES)
        val missingMandatory = mandatory - resource.capabilities
        if (missingMandatory.isNotEmpty()) {
            return AgentResourceCandidate(
                resource,
                -7_000,
                listOf("mandatory_capability_missing:${missingMandatory.joinToString(",") { it.name }}")
            )
        }
        var score = 500 - (missing.size * 180)
        score += health.reliabilityPercent
        score += domainHealth.reliabilityPercent / 2
        score += resource.quality.ordinal * if (requirements.complexReasoning || requirements.mode == AgentRoutingMode.QUALITY) 55 else 22
        score -= resource.latency.ordinal * if (requirements.mode == AgentRoutingMode.FAST) 75 else 25
        score -= resource.cost.ordinal * if (requirements.mode == AgentRoutingMode.ECONOMY) 80 else 20
        if (health.averageLatencyMs > 0) {
            val latencyDivisor = if (requirements.mode == AgentRoutingMode.FAST) 40L else 120L
            score -= (health.averageLatencyMs / latencyDivisor).coerceAtMost(180L).toInt()
        }
        if (domainHealth.averageLatencyMs > 0) {
            score -= (domainHealth.averageLatencyMs / 180L).coerceAtMost(120L).toInt()
        }
        if (requirements.dataSensitivity == AgentDataSensitivity.CONFIDENTIAL) {
            score += when (resource.trust) {
                AgentResourceTrust.PHONE_SYSTEM -> 220
                AgentResourceTrust.VERIFIED_PAIRED -> 100
                AgentResourceTrust.PRIVATE_CONFIGURED -> 40
                AgentResourceTrust.CLOUD_CONFIGURED -> -160
                AgentResourceTrust.UNKNOWN -> -240
            }
        }
        if (requirements.executionHorizon != AgentExecutionHorizon.INTERACTIVE) {
            if (resource.supportsBackground) score += 140 else score -= 120
        }
        if (environment.energyConstrained) {
            score -= resource.energy.ordinal * 70
            if (resource.energy == AgentResourceEnergy.MINIMAL) score += 80
        }
        if (environment.networkMetered && resource.location == AgentResourceLocation.CLOUD) score -= 70
        if (environment.networkAvailable && !environment.networkValidated && resource.location != AgentResourceLocation.PHONE) score -= 180
        if (resource.contextWindowTokens in 1 until requirements.estimatedInputTokens) score -= 300
        if (requirements.estimatedInputTokens > 1_000 && resource.cost > AgentResourceCost.LOW) {
            score -= (requirements.estimatedInputTokens / 100).coerceAtMost(120)
        }
        if (observedUsage.averageTotalTokens > 0L) {
            val multiplier = if (requirements.mode == AgentRoutingMode.ECONOMY) 2 else 1
            score -= ((observedUsage.averageTotalTokens / 1_000L).coerceAtMost(60L) * multiplier).toInt()
        }
        if (observedUsage.averageReportedCostMicros > 0L) {
            val multiplier = if (requirements.mode == AgentRoutingMode.ECONOMY) 2 else 1
            score -= ((observedUsage.averageReportedCostMicros / 5_000L).coerceAtMost(120L) * multiplier).toInt()
        }
        if (resource.location != AgentResourceLocation.CLOUD) score += 35
        if (requirements.liveDataRequired && resource.supportsTools) score += 120
        if (requirements.mode == AgentRoutingMode.PRIVATE && resource.location == AgentResourceLocation.PHONE) score += 180
        if (requirements.mode == AgentRoutingMode.FAST && resource.latency <= AgentResourceLatency.FAST) score += 120
        if (requirements.mode == AgentRoutingMode.ECONOMY && resource.cost <= AgentResourceCost.LOW) score += 120
        if (requirements.mode == AgentRoutingMode.QUALITY && resource.quality == AgentResourceQuality.FRONTIER) score += 160
        if (missing.isEmpty()) reasons += "capability_match" else reasons += "partial_match:${missing.joinToString(",") { it.name }}"
        reasons += "health:${health.reliabilityPercent}"
        reasons += "domain:${resource.failureDomain.ifBlank { "none" }}"
        reasons += "domain_health:${domainHealth.reliabilityPercent}"
        if (health.averageLatencyMs > 0) reasons += "observed_latency_ms:${health.averageLatencyMs}"
        if (observedUsage.averageTotalTokens > 0L) {
            reasons += "observed_average_tokens:${observedUsage.averageTotalTokens}"
        }
        if (observedUsage.averageReportedCostMicros > 0L) {
            reasons += "observed_average_reported_cost_micros:${observedUsage.averageReportedCostMicros}"
        }
        reasons += "latency:${resource.latency.name.lowercase(Locale.US)}"
        reasons += "cost:${resource.cost.name.lowercase(Locale.US)}"
        reasons += "trust:${resource.trust.name.lowercase(Locale.US)}"
        reasons += "energy:${resource.energy.name.lowercase(Locale.US)}"
        reasons += "sensitivity:${requirements.dataSensitivity.name.lowercase(Locale.US)}"
        if (requirements.executionHorizon != AgentExecutionHorizon.INTERACTIVE) {
            reasons += "background:${resource.supportsBackground}"
        }
        if (environment.energyConstrained) reasons += "energy_constrained"
        if (environment.networkMetered) reasons += "metered_network"
        return AgentResourceCandidate(resource, score, reasons)
    }

    private companion object {
        val MANDATORY_CAPABILITIES = setOf(
            AgentCapability.LIVE_DATA,
            AgentCapability.CODE,
            AgentCapability.DEVICE_CONTROL,
            AgentCapability.APP_NAVIGATION,
            AgentCapability.KNOWLEDGE_SEARCH,
            AgentCapability.MCP,
            AgentCapability.SKILL
        )
    }
}
