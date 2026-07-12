package com.signalasi.chat

import android.content.Context
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
    val targetId: String = ""
)

data class AgentTaskRequirements(
    val capabilities: Set<AgentCapability>,
    val mode: AgentRoutingMode,
    val liveDataRequired: Boolean,
    val localOnly: Boolean,
    val complexReasoning: Boolean,
    val estimatedInputTokens: Int
)

data class AgentResourceCandidate(
    val resource: AgentResourceDescriptor,
    val score: Int,
    val reasons: List<String>
)

data class AgentRoutingDecision(
    val requirements: AgentTaskRequirements,
    val primary: AgentResourceCandidate?,
    val fallbacks: List<AgentResourceCandidate>
) {
    val orderedTargetIds: List<String>
        get() = listOfNotNull(primary?.resource?.targetId?.takeIf { it.isNotBlank() }) +
            fallbacks.mapNotNull { it.resource.targetId.takeIf(String::isNotBlank) }
}

object AgentTaskRequirementAnalyzer {
    private val liveTerms = listOf(
        "weather", "forecast", "today", "current", "latest", "news", "price", "traffic", "score", "now", "live",
        "\u5929\u6c14", "\u9884\u62a5", "\u4eca\u5929", "\u5f53\u524d", "\u6700\u65b0", "\u65b0\u95fb", "\u4ef7\u683c", "\u8def\u51b5", "\u6bd4\u5206", "\u73b0\u5728", "\u5b9e\u65f6"
    )
    private val codeTerms = listOf("code", "debug", "repository", "compile", "build", "codex", "\u4ee3\u7801", "\u7f16\u8bd1", "\u9879\u76ee", "\u4fee\u590d bug")
    private val deviceTerms = listOf("home assistant", "smart home", "light", "scene", "device", "\u667a\u80fd\u5bb6\u5c45", "\u5f00\u706f", "\u5173\u706f", "\u8bbe\u5907", "\u573a\u666f")
    private val screenTerms = listOf("screen", "tap", "click", "swipe", "open app", "\u5c4f\u5e55", "\u70b9\u51fb", "\u6ed1\u52a8", "\u6253\u5f00 app")
    private val knowledgeTerms = listOf("knowledge", "memory", "document", "pdf", "\u77e5\u8bc6\u5e93", "\u8bb0\u5fc6", "\u6587\u6863")
    private val mcpTerms = listOf("mcp", "model context protocol", "\u4e0a\u4e0b\u6587\u534f\u8bae")
    private val skillTerms = listOf("skill", "skills", "\u6280\u80fd")
    private val privateTerms = listOf("private", "local only", "offline", "password", "secret", "\u9690\u79c1", "\u4ec5\u672c\u5730", "\u79bb\u7ebf", "\u5bc6\u7801", "\u79d8\u5bc6")
    private val fastTerms = listOf("fast", "quick", "low latency", "\u5feb\u901f", "\u7acb\u5373", "\u4f4e\u5ef6\u8fdf")
    private val economyTerms = listOf("cheap", "save token", "few tokens", "economy", "\u7701 token", "\u7701\u8d39\u7528", "\u7ecf\u6d4e")
    private val qualityTerms = listOf("best", "strongest", "deep reasoning", "high quality", "\u6700\u5f3a", "\u6700\u597d", "\u6df1\u5ea6\u601d\u8003", "\u9ad8\u8d28\u91cf")

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
            estimatedInputTokens = max(64, goal.length / 3)
        )
    }

    private fun String.containsAny(terms: List<String>): Boolean = terms.any(::contains)
}

class AgentResourceHealthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("signalasi_agent_resource_health", Context.MODE_PRIVATE)

    fun snapshot(id: String): AgentResourceHealth {
        val raw = prefs.getString(id, "")?.takeIf { it.isNotBlank() } ?: return AgentResourceHealth()
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        return AgentResourceHealth(
            successes = json.optInt("successes"),
            failures = json.optInt("failures"),
            consecutiveFailures = json.optInt("consecutive_failures"),
            averageLatencyMs = json.optLong("average_latency_ms"),
            circuitOpenUntil = json.optLong("circuit_open_until")
        )
    }

    fun record(id: String, success: Boolean, latencyMs: Long) {
        val current = snapshot(id)
        val successes = current.successes + if (success) 1 else 0
        val failures = current.failures + if (success) 0 else 1
        val consecutive = if (success) 0 else current.consecutiveFailures + 1
        val samples = max(1, successes + failures)
        val average = if (current.averageLatencyMs <= 0) latencyMs else
            ((current.averageLatencyMs * (samples - 1)) + latencyMs) / samples
        val circuitUntil = if (consecutive >= 3) System.currentTimeMillis() + 60_000L else 0L
        prefs.edit().putString(id, JSONObject()
            .put("successes", successes)
            .put("failures", failures)
            .put("consecutive_failures", consecutive)
            .put("average_latency_ms", average)
            .put("circuit_open_until", circuitUntil)
            .toString()).apply()
    }
}

data class AgentResourceHealth(
    val successes: Int = 0,
    val failures: Int = 0,
    val consecutiveFailures: Int = 0,
    val averageLatencyMs: Long = 0,
    val circuitOpenUntil: Long = 0
) {
    val circuitOpen: Boolean get() = circuitOpenUntil > System.currentTimeMillis()
    val reliabilityPercent: Int get() = if (successes + failures == 0) 90 else successes * 100 / (successes + failures)
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
                supportsTools = true
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
            targetId = target.id
        )
    }
}

class AgentResourceRouter(context: Context) {
    private val healthStore = AgentResourceHealthStore(context)

    fun route(goal: String, targets: List<AgentCallableTarget>, tools: List<AgentSystemTool>): AgentRoutingDecision {
        val requirements = AgentTaskRequirementAnalyzer.analyze(goal)
        val preferredTargets = preferredTargetOrder(requirements)
        val candidates = AgentResourceCatalog.build(targets, tools)
            .asSequence()
            .filter { it.targetId.isNotBlank() }
            .map { resource -> score(resource, requirements) }
            .filter { it.score > -1_000 }
            .sortedWith(
                compareBy<AgentResourceCandidate> { preferredRank(it.resource.targetId, preferredTargets) }
                    .thenByDescending { it.score }
            )
            .toList()
        val fallbackLimit = 6
        return AgentRoutingDecision(requirements, candidates.firstOrNull(), candidates.drop(1).take(fallbackLimit))
    }

    private fun preferredTargetOrder(requirements: AgentTaskRequirements): List<String> {
        val remainder = when {
            AgentCapability.CODE in requirements.capabilities -> listOf("claude-code")
            AgentCapability.KNOWLEDGE_SEARCH in requirements.capabilities -> listOf("local-llm", "cloud-models")
            requirements.liveDataRequired -> listOf("cloud-models")
            requirements.mode == AgentRoutingMode.QUALITY -> listOf("cloud-models")
            requirements.mode == AgentRoutingMode.FAST -> listOf("local-llm")
            requirements.mode == AgentRoutingMode.ECONOMY -> listOf("local-llm", "claude-code", "cloud-models")
            requirements.mode == AgentRoutingMode.PRIVATE -> listOf("local-llm")
            else -> listOf("local-llm", "cloud-models")
        }
        return (listOf("codex", "hermes") + remainder)
            .map(::canonicalTargetId)
            .distinct()
    }

    private fun preferredRank(targetId: String, preferredTargets: List<String>): Int {
        val index = preferredTargets.indexOf(canonicalTargetId(targetId))
        return if (index >= 0) index else preferredTargets.size + 1
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

    private fun score(resource: AgentResourceDescriptor, requirements: AgentTaskRequirements): AgentResourceCandidate {
        val reasons = mutableListOf<String>()
        if (resource.status != AgentConnectorStatus.AVAILABLE) {
            return AgentResourceCandidate(resource, -10_000, listOf("unavailable"))
        }
        val health = healthStore.snapshot(resource.id)
        if (health.circuitOpen) return AgentResourceCandidate(resource, -9_000, listOf("circuit_open"))
        if (requirements.localOnly && resource.location == AgentResourceLocation.CLOUD) {
            return AgentResourceCandidate(resource, -8_000, listOf("privacy_boundary"))
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
        score += resource.quality.ordinal * if (requirements.complexReasoning || requirements.mode == AgentRoutingMode.QUALITY) 55 else 22
        score -= resource.latency.ordinal * if (requirements.mode == AgentRoutingMode.FAST) 75 else 25
        score -= resource.cost.ordinal * if (requirements.mode == AgentRoutingMode.ECONOMY) 80 else 20
        if (health.averageLatencyMs > 0) {
            val latencyDivisor = if (requirements.mode == AgentRoutingMode.FAST) 40L else 120L
            score -= (health.averageLatencyMs / latencyDivisor).coerceAtMost(180L).toInt()
        }
        if (requirements.estimatedInputTokens > 1_000 && resource.cost > AgentResourceCost.LOW) {
            score -= (requirements.estimatedInputTokens / 100).coerceAtMost(120)
        }
        if (resource.location != AgentResourceLocation.CLOUD) score += 35
        if (requirements.liveDataRequired && resource.supportsTools) score += 120
        if (requirements.mode == AgentRoutingMode.PRIVATE && resource.location == AgentResourceLocation.PHONE) score += 180
        if (requirements.mode == AgentRoutingMode.FAST && resource.latency <= AgentResourceLatency.FAST) score += 120
        if (requirements.mode == AgentRoutingMode.ECONOMY && resource.cost <= AgentResourceCost.LOW) score += 120
        if (requirements.mode == AgentRoutingMode.QUALITY && resource.quality == AgentResourceQuality.FRONTIER) score += 160
        if (missing.isEmpty()) reasons += "capability_match" else reasons += "partial_match:${missing.joinToString(",") { it.name }}"
        reasons += "health:${health.reliabilityPercent}"
        if (health.averageLatencyMs > 0) reasons += "observed_latency_ms:${health.averageLatencyMs}"
        reasons += "latency:${resource.latency.name.lowercase(Locale.US)}"
        reasons += "cost:${resource.cost.name.lowercase(Locale.US)}"
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
