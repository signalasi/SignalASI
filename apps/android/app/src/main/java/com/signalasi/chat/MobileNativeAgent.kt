package com.signalasi.chat

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Phone-native Agent runtime scaffold.
 *
 * The first implementation keeps every capability behind a small interface so
 * screen perception, local actions, memory, and remote agents can be upgraded
 * independently without turning MainActivity into the agent runtime.
 */
class MobileNativeAgent(
    context: Context,
    private val perceptionProvider: ScreenPerceptionProvider = AndroidScreenPerceptionProvider(context),
    private val planner: AgentPlanner = RuleBasedAgentPlanner(),
    private val safetyPolicy: AgentSafetyPolicy = DefaultAgentSafetyPolicy(),
    private val actionExecutor: AgentActionExecutor = AndroidAgentActionExecutor(context),
    private val memoryStore: AgentMemoryStore = InMemoryAgentMemoryStore(),
    private val connectorRegistry: AgentConnectorRegistry = StaticAgentConnectorRegistry()
) {
    private var phase: AgentPhase = AgentPhase.OBSERVING
    private var currentGoal: String = ""
    private var currentScreen: ScreenContext = perceptionProvider.capture()
    private var currentPlan: AgentPlan? = null
    private var lastActionResult: AgentActionResult? = null
    private val auditTrail = mutableListOf<AgentAuditEntry>()

    fun snapshot(): AgentUiState = AgentUiState(
        phase = phase,
        currentGoal = currentGoal,
        currentScreen = currentScreen,
        permissionMode = safetyPolicy.permissionMode(),
        highRiskGuard = safetyPolicy.highRiskGuardEnabled(),
        callableTargets = connectorRegistry.availableTargets(),
        runningTaskCount = if (phase == AgentPhase.PLANNING || phase == AgentPhase.WAITING_CONFIRMATION || phase == AgentPhase.EXECUTING) 1 else 0,
        steps = currentPlan?.steps ?: defaultSteps(),
        lastEvent = if (currentGoal.isBlank()) AgentEvent.WAITING_FOR_GOAL else AgentEvent.GOAL_RECEIVED,
        plan = currentPlan,
        pendingAction = currentPlan?.actions?.firstOrNull { it.status == AgentActionStatus.PENDING_CONFIRMATION },
        auditTrail = auditTrail.toList(),
        lastActionResult = lastActionResult
    )

    fun observeCurrentScreen(): AgentUiState {
        currentScreen = perceptionProvider.capture()
        phase = AgentPhase.OBSERVING
        currentGoal = ""
        currentPlan = null
        lastActionResult = null
        recordAudit(AgentAuditEvent.SCREEN_OBSERVED, "screen:${currentScreen.foregroundApp}")
        return snapshot()
    }

    fun observeCurrentScreen(foregroundApp: String, pageTitle: String): AgentUiState {
        currentScreen = perceptionProvider.capture(foregroundApp, pageTitle)
        phase = AgentPhase.OBSERVING
        currentGoal = ""
        currentPlan = null
        lastActionResult = null
        recordAudit(AgentAuditEvent.SCREEN_OBSERVED, "screen:${currentScreen.foregroundApp}")
        return snapshot()
    }

    fun submitGoal(goal: String): AgentUiState {
        currentGoal = goal.trim()
        if (currentGoal.isBlank()) {
            return observeCurrentScreen()
        }

        currentScreen = perceptionProvider.capture()
        val draftPlan = planner.plan(
            request = AgentRequest(
                goal = currentGoal,
                screen = currentScreen,
                targets = connectorRegistry.availableTargets(),
                memories = memoryStore.recall(currentGoal)
            )
        )
        val safetyReview = safetyPolicy.review(draftPlan)
        currentPlan = draftPlan.withSafetyReview(safetyReview)
        phase = if (safetyReview.requiresConfirmation) {
            AgentPhase.WAITING_CONFIRMATION
        } else {
            AgentPhase.PLANNING
        }
        lastActionResult = null
        memoryStore.remember(AgentMemoryItem(kind = AgentMemoryKind.TASK, value = currentGoal))
        recordAudit(AgentAuditEvent.GOAL_RECEIVED, "goal:${currentGoal.take(48)}")
        return snapshot()
    }

    fun approveNextAction(): AgentUiState {
        val plan = currentPlan ?: return snapshot()
        val nextAction = plan.actions.firstOrNull { it.status == AgentActionStatus.PENDING_CONFIRMATION }
            ?: return snapshot()
        phase = AgentPhase.EXECUTING
        currentPlan = plan.markAction(nextAction.id, AgentActionStatus.RUNNING)
        currentScreen = perceptionProvider.capture()
        lastActionResult = actionExecutor.execute(nextAction, currentScreen)
        val finalStatus = if (lastActionResult?.success == true) {
            AgentActionStatus.COMPLETED
        } else {
            AgentActionStatus.FAILED
        }
        currentPlan = currentPlan?.markAction(nextAction.id, finalStatus)
        phase = if (lastActionResult?.success == true) AgentPhase.COMPLETED else AgentPhase.FAILED
        recordAudit(AgentAuditEvent.ACTION_EXECUTED, "action:${nextAction.kind}:${finalStatus}")
        return snapshot()
    }

    fun cancelCurrentTask(): AgentUiState {
        phase = AgentPhase.OBSERVING
        currentGoal = ""
        currentPlan = null
        lastActionResult = null
        recordAudit(AgentAuditEvent.TASK_CANCELLED, "cancelled")
        return snapshot()
    }

    private fun defaultSteps(): List<AgentStep> = listOf(
        AgentStep(1, AgentStepKind.OBSERVE_SCREEN, AgentStepStatus.CURRENT),
        AgentStep(2, AgentStepKind.ANALYZE_GOAL, AgentStepStatus.WAITING),
        AgentStep(3, AgentStepKind.BUILD_PLAN, AgentStepStatus.WAITING),
        AgentStep(4, AgentStepKind.CONFIRM_AND_ACT, AgentStepStatus.SAFE)
    )

    private fun recordAudit(event: AgentAuditEvent, detail: String) {
        auditTrail.add(AgentAuditEntry(event = event, detail = detail, timestampMillis = System.currentTimeMillis()))
        if (auditTrail.size > MAX_AUDIT_ITEMS) {
            auditTrail.removeAt(0)
        }
    }

    companion object {
        private const val MAX_AUDIT_ITEMS = 20
    }
}

interface ScreenPerceptionProvider {
    fun capture(): ScreenContext
    fun capture(foregroundApp: String, pageTitle: String): ScreenContext
}

class AndroidScreenPerceptionProvider(private val context: Context) : ScreenPerceptionProvider {
    override fun capture(): ScreenContext = ScreenPerceptionState.current(
        defaultApp = "SignalASI",
        defaultTitle = context.getString(R.string.tab_agent)
    )

    override fun capture(foregroundApp: String, pageTitle: String): ScreenContext = ScreenPerceptionState.current(
        defaultApp = foregroundApp,
        defaultTitle = pageTitle
    )
}

interface AgentPlanner {
    fun plan(request: AgentRequest): AgentPlan
}

class RuleBasedAgentPlanner : AgentPlanner {
    override fun plan(request: AgentRequest): AgentPlan {
        val action = actionFor(request)
        return AgentPlan(
            goal = request.goal,
            screen = request.screen,
            steps = listOf(
                AgentStep(1, AgentStepKind.OBSERVE_SCREEN, AgentStepStatus.DONE),
                AgentStep(2, AgentStepKind.ANALYZE_GOAL, AgentStepStatus.DONE),
                AgentStep(3, AgentStepKind.BUILD_PLAN, AgentStepStatus.DONE),
                AgentStep(4, AgentStepKind.CONFIRM_AND_ACT, AgentStepStatus.CURRENT)
            ),
            actions = listOf(action)
        )
    }

    private fun actionFor(request: AgentRequest): AgentAction {
        val goal = request.goal.trim()
        val lower = goal.lowercase()
        return when {
            lower.contains("open settings") || lower == "settings" -> AgentAction(
                id = "open-settings",
                kind = AgentActionKind.OPEN_APP,
                target = "Android Settings",
                risk = AgentRisk.LOW,
                status = AgentActionStatus.PENDING_CONFIRMATION,
                description = "Open Android Settings",
                parameters = mapOf("intent_action" to Settings.ACTION_SETTINGS)
            )
            lower == "back" || lower.contains("go back") -> AgentAction(
                id = "go-back",
                kind = AgentActionKind.BACK,
                target = request.screen.foregroundApp,
                risk = AgentRisk.LOW,
                status = AgentActionStatus.PENDING_CONFIRMATION,
                description = "Go back one screen"
            )
            lower.contains("read screen") || lower.contains("scan screen") -> AgentAction(
                id = "read-screen",
                kind = AgentActionKind.READ_SCREEN,
                target = request.screen.foregroundApp,
                risk = AgentRisk.LOW,
                status = AgentActionStatus.PENDING_CONFIRMATION,
                description = "Read current screen structure"
            )
            lower.startsWith("type ") -> AgentAction(
                id = "type-text",
                kind = AgentActionKind.TYPE_TEXT,
                target = request.screen.foregroundApp,
                risk = AgentRisk.MEDIUM,
                status = AgentActionStatus.PENDING_CONFIRMATION,
                description = "Type text into the focused field",
                parameters = mapOf("text" to goal.removePrefix("type ").trim())
            )
            lower.contains("tap first") || lower.contains("click first") -> {
                val firstElement = request.screen.clickableElements.firstOrNull()
                AgentAction(
                    id = "tap-first-action",
                    kind = AgentActionKind.TAP,
                    target = firstElement?.label?.ifBlank { request.screen.foregroundApp } ?: request.screen.foregroundApp,
                    risk = AgentRisk.MEDIUM,
                    status = AgentActionStatus.PENDING_CONFIRMATION,
                    description = "Tap the first clickable element",
                    parameters = mapOf("bounds" to firstElement?.bounds.orEmpty())
                )
            }
            lower.contains("swipe up") -> AgentAction(
                id = "swipe-up",
                kind = AgentActionKind.SWIPE,
                target = request.screen.foregroundApp,
                risk = AgentRisk.LOW,
                status = AgentActionStatus.PENDING_CONFIRMATION,
                description = "Swipe up on the current screen",
                parameters = mapOf("from_x" to "540", "from_y" to "1700", "to_x" to "540", "to_y" to "700")
            )
            else -> AgentAction(
                id = "draft-plan",
                kind = AgentActionKind.DRAFT_PLAN,
                target = "local-agent-runtime",
                risk = riskFor(lower),
                status = AgentActionStatus.PENDING_CONFIRMATION,
                description = "Create a safe local task plan"
            )
        }
    }

    private fun riskFor(goal: String): AgentRisk = when {
        goal.contains("delete") -> AgentRisk.HIGH
        goal.contains("send") -> AgentRisk.MEDIUM
        goal.contains("pay") -> AgentRisk.HIGH
        else -> AgentRisk.LOW
    }
}

interface AgentSafetyPolicy {
    fun permissionMode(): PermissionMode
    fun highRiskGuardEnabled(): Boolean
    fun review(plan: AgentPlan): AgentSafetyReview
}

class DefaultAgentSafetyPolicy : AgentSafetyPolicy {
    override fun permissionMode(): PermissionMode = PermissionMode.ASK_BEFORE_ACTION

    override fun highRiskGuardEnabled(): Boolean = true

    override fun review(plan: AgentPlan): AgentSafetyReview {
        val highestRisk = plan.actions.maxByOrNull { it.risk.weight }?.risk ?: AgentRisk.LOW
        return AgentSafetyReview(
            risk = highestRisk,
            requiresConfirmation = highestRisk.weight >= AgentRisk.LOW.weight,
            blocked = highestRisk == AgentRisk.BLOCKED
        )
    }
}

interface AgentActionExecutor {
    fun execute(action: AgentAction, screen: ScreenContext): AgentActionResult
}

class AndroidAgentActionExecutor(private val context: Context) : AgentActionExecutor {
    override fun execute(action: AgentAction, screen: ScreenContext): AgentActionResult = when (action.kind) {
        AgentActionKind.READ_SCREEN -> AgentActionResult(
            actionId = action.id,
            success = true,
            message = "Read ${screen.visibleTextCount} text items and ${screen.clickableNodeCount} actions"
        )
        AgentActionKind.DRAFT_PLAN -> AgentActionResult(
            actionId = action.id,
            success = true,
            message = "Task plan confirmed"
        )
        AgentActionKind.OPEN_APP -> openApp(action)
        AgentActionKind.BACK -> serviceAction(action.id, "Back action executed") {
            SignalASIAccessibilityService.performGlobalBack()
        }
        AgentActionKind.TAP -> {
            val bounds = action.parameters["bounds"].orEmpty()
            if (bounds.isBlank()) {
                AgentActionResult(action.id, false, "No clickable target is available")
            } else {
                serviceAction(action.id, "Tap action executed") {
                    SignalASIAccessibilityService.performTap(bounds)
                }
            }
        }
        AgentActionKind.TYPE_TEXT -> {
            val text = action.parameters["text"].orEmpty()
            if (text.isBlank()) {
                AgentActionResult(action.id, false, "No text was provided")
            } else {
                serviceAction(action.id, "Text input executed") {
                    SignalASIAccessibilityService.performTextInput(text)
                }
            }
        }
        AgentActionKind.SWIPE -> {
            val fromX = action.parameters["from_x"]?.toIntOrNull() ?: 0
            val fromY = action.parameters["from_y"]?.toIntOrNull() ?: 0
            val toX = action.parameters["to_x"]?.toIntOrNull() ?: 0
            val toY = action.parameters["to_y"]?.toIntOrNull() ?: 0
            serviceAction(action.id, "Swipe action executed") {
                SignalASIAccessibilityService.performSwipe(fromX, fromY, toX, toY)
            }
        }
        AgentActionKind.CALL_CONNECTOR,
        AgentActionKind.CONTROL_DEVICE -> AgentActionResult(
            actionId = action.id,
            success = false,
            message = "This executor is not connected yet"
        )
    }

    private fun openApp(action: AgentAction): AgentActionResult {
        val intentAction = action.parameters["intent_action"].orEmpty()
        val packageName = action.parameters["package"].orEmpty()
        val intent = when {
            intentAction.isNotBlank() -> Intent(intentAction)
            packageName.isNotBlank() -> context.packageManager.getLaunchIntentForPackage(packageName)
            else -> null
        } ?: return AgentActionResult(action.id, false, "No launch target is available")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return AgentActionResult(action.id, true, "Opened ${action.target}")
    }

    private fun serviceAction(actionId: String, successMessage: String, block: () -> Boolean): AgentActionResult {
        if (!SignalASIAccessibilityService.isActive()) {
            return AgentActionResult(actionId, false, "Screen Agent permission is required")
        }
        val success = block()
        return AgentActionResult(
            actionId = actionId,
            success = success,
            message = if (success) successMessage else "Screen Agent could not perform the action"
        )
    }
}

interface AgentMemoryStore {
    fun remember(item: AgentMemoryItem)
    fun recall(query: String): List<AgentMemoryItem>
}

class InMemoryAgentMemoryStore : AgentMemoryStore {
    private val items = mutableListOf<AgentMemoryItem>()

    override fun remember(item: AgentMemoryItem) {
        items.add(item)
    }

    override fun recall(query: String): List<AgentMemoryItem> = items
        .filter { it.value.contains(query, ignoreCase = true) || query.contains(it.value, ignoreCase = true) }
        .takeLast(5)
}

interface AgentConnectorRegistry {
    fun availableTargets(): List<String>
}

class StaticAgentConnectorRegistry : AgentConnectorRegistry {
    override fun availableTargets(): List<String> = listOf(
        "Cloud Models",
        "Local LLM",
        "Hermes",
        "Codex",
        "Claude Code",
        "Home Assistant"
    )
}

data class AgentUiState(
    val phase: AgentPhase,
    val currentGoal: String,
    val currentScreen: ScreenContext,
    val permissionMode: PermissionMode,
    val highRiskGuard: Boolean,
    val callableTargets: List<String>,
    val runningTaskCount: Int,
    val steps: List<AgentStep>,
    val lastEvent: AgentEvent,
    val plan: AgentPlan? = null,
    val pendingAction: AgentAction? = null,
    val auditTrail: List<AgentAuditEntry> = emptyList(),
    val lastActionResult: AgentActionResult? = null
)

data class AgentRequest(
    val goal: String,
    val screen: ScreenContext,
    val targets: List<String>,
    val memories: List<AgentMemoryItem>
)

data class ScreenContext(
    val foregroundApp: String,
    val activityName: String = "",
    val pageTitle: String,
    val visibleTextCount: Int = 0,
    val clickableNodeCount: Int = 0,
    val inputFieldCount: Int = 0,
    val scrollableRegionCount: Int = 0,
    val sensitiveFlagCount: Int = 0,
    val visibleTexts: List<String> = emptyList(),
    val clickableElements: List<ScreenElement> = emptyList(),
    val inputFields: List<ScreenElement> = emptyList(),
    val scrollableRegions: List<ScreenElement> = emptyList(),
    val sensitiveFlags: List<String> = emptyList(),
    val isAccessibilityEnabled: Boolean = false,
    val snapshotAgeMillis: Long = 0L
)

data class AgentPlan(
    val goal: String,
    val screen: ScreenContext,
    val steps: List<AgentStep>,
    val actions: List<AgentAction>,
    val safetyReview: AgentSafetyReview = AgentSafetyReview()
) {
    fun withSafetyReview(review: AgentSafetyReview): AgentPlan = copy(safetyReview = review)

    fun markAction(actionId: String, status: AgentActionStatus): AgentPlan = copy(
        actions = actions.map { action ->
            if (action.id == actionId) action.copy(status = status) else action
        },
        steps = steps.map { step ->
            when {
                status == AgentActionStatus.COMPLETED && step.kind == AgentStepKind.CONFIRM_AND_ACT -> {
                    step.copy(status = AgentStepStatus.DONE)
                }
                status == AgentActionStatus.FAILED && step.kind == AgentStepKind.CONFIRM_AND_ACT -> {
                    step.copy(status = AgentStepStatus.CURRENT)
                }
                step.kind == AgentStepKind.ANALYZE_GOAL || step.kind == AgentStepKind.BUILD_PLAN -> {
                    step.copy(status = AgentStepStatus.DONE)
                }
                step.kind == AgentStepKind.CONFIRM_AND_ACT && status == AgentActionStatus.RUNNING -> {
                    step.copy(status = AgentStepStatus.CURRENT)
                }
                else -> step
            }
        }
    )
}

data class AgentStep(
    val order: Int,
    val kind: AgentStepKind,
    val status: AgentStepStatus
)

data class AgentAction(
    val id: String,
    val kind: AgentActionKind,
    val target: String,
    val risk: AgentRisk,
    val status: AgentActionStatus,
    val description: String,
    val parameters: Map<String, String> = emptyMap()
)

data class AgentSafetyReview(
    val risk: AgentRisk = AgentRisk.LOW,
    val requiresConfirmation: Boolean = true,
    val blocked: Boolean = false
)

data class AgentActionResult(
    val actionId: String,
    val success: Boolean,
    val message: String
)

data class AgentMemoryItem(
    val kind: AgentMemoryKind,
    val value: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class AgentAuditEntry(
    val event: AgentAuditEvent,
    val detail: String,
    val timestampMillis: Long
)

enum class AgentPhase {
    OBSERVING,
    PLANNING,
    WAITING_CONFIRMATION,
    EXECUTING,
    COMPLETED,
    FAILED
}

enum class AgentStepKind {
    OBSERVE_SCREEN,
    ANALYZE_GOAL,
    BUILD_PLAN,
    CONFIRM_AND_ACT
}

enum class AgentStepStatus {
    CURRENT,
    DONE,
    WAITING,
    SAFE
}

enum class AgentActionKind {
    READ_SCREEN,
    DRAFT_PLAN,
    TAP,
    TYPE_TEXT,
    SWIPE,
    BACK,
    OPEN_APP,
    CALL_CONNECTOR,
    CONTROL_DEVICE
}

enum class AgentActionStatus {
    PROPOSED,
    PENDING_CONFIRMATION,
    RUNNING,
    COMPLETED,
    FAILED,
    BLOCKED
}

enum class AgentRisk(val weight: Int) {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    BLOCKED(4)
}

enum class AgentMemoryKind {
    IDENTITY,
    CONTACT,
    TASK,
    PREFERENCE,
    KNOWLEDGE,
    SAFETY
}

enum class AgentAuditEvent {
    SCREEN_OBSERVED,
    GOAL_RECEIVED,
    ACTION_EXECUTED,
    TASK_CANCELLED
}

enum class AgentEvent {
    WAITING_FOR_GOAL,
    GOAL_RECEIVED
}

enum class PermissionMode {
    OBSERVE_ONLY,
    SUGGEST_ONLY,
    ASK_BEFORE_ACTION,
    AUTO_LOW_RISK
}
