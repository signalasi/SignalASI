package com.signalasi.chat

import android.content.Context

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
    private val actionExecutor: AgentActionExecutor = PendingConfirmationActionExecutor(),
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
        runningTaskCount = if (currentGoal.isBlank()) 0 else 1,
        steps = currentPlan?.steps ?: defaultSteps(),
        lastEvent = if (currentGoal.isBlank()) AgentEvent.WAITING_FOR_GOAL else AgentEvent.GOAL_RECEIVED,
        plan = currentPlan,
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
        lastActionResult = actionExecutor.execute(nextAction, currentScreen)
        currentPlan = plan.markAction(nextAction.id, AgentActionStatus.COMPLETED)
        phase = AgentPhase.COMPLETED
        recordAudit(AgentAuditEvent.ACTION_EXECUTED, "action:${nextAction.kind}")
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
    override fun capture(): ScreenContext = ScreenContext(
        foregroundApp = "SignalASI",
        pageTitle = context.getString(R.string.tab_agent),
        visibleTextCount = 0,
        clickableNodeCount = 0,
        sensitiveFlagCount = 0
    )

    override fun capture(foregroundApp: String, pageTitle: String): ScreenContext = ScreenContext(
        foregroundApp = foregroundApp,
        pageTitle = pageTitle,
        visibleTextCount = 0,
        clickableNodeCount = 0,
        sensitiveFlagCount = 0
    )
}

interface AgentPlanner {
    fun plan(request: AgentRequest): AgentPlan
}

class RuleBasedAgentPlanner : AgentPlanner {
    override fun plan(request: AgentRequest): AgentPlan {
        val risk = when {
            request.goal.contains("delete", ignoreCase = true) -> AgentRisk.HIGH
            request.goal.contains("send", ignoreCase = true) -> AgentRisk.MEDIUM
            request.goal.contains("pay", ignoreCase = true) -> AgentRisk.HIGH
            else -> AgentRisk.LOW
        }
        return AgentPlan(
            goal = request.goal,
            screen = request.screen,
            steps = listOf(
                AgentStep(1, AgentStepKind.OBSERVE_SCREEN, AgentStepStatus.DONE),
                AgentStep(2, AgentStepKind.ANALYZE_GOAL, AgentStepStatus.CURRENT),
                AgentStep(3, AgentStepKind.BUILD_PLAN, AgentStepStatus.WAITING),
                AgentStep(4, AgentStepKind.CONFIRM_AND_ACT, AgentStepStatus.SAFE)
            ),
            actions = listOf(
                AgentAction(
                    id = "draft-plan",
                    kind = AgentActionKind.DRAFT_PLAN,
                    target = "local-agent-runtime",
                    risk = risk,
                    status = AgentActionStatus.PENDING_CONFIRMATION
                )
            )
        )
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

class PendingConfirmationActionExecutor : AgentActionExecutor {
    override fun execute(action: AgentAction, screen: ScreenContext): AgentActionResult = AgentActionResult(
        actionId = action.id,
        success = true,
        message = "Action accepted by framework scaffold on ${screen.foregroundApp}"
    )
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
    val pageTitle: String,
    val visibleTextCount: Int = 0,
    val clickableNodeCount: Int = 0,
    val sensitiveFlagCount: Int = 0
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
            when (step.kind) {
                AgentStepKind.ANALYZE_GOAL -> step.copy(status = AgentStepStatus.DONE)
                AgentStepKind.BUILD_PLAN -> step.copy(status = AgentStepStatus.DONE)
                AgentStepKind.CONFIRM_AND_ACT -> step.copy(status = AgentStepStatus.DONE)
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
    val status: AgentActionStatus
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
    COMPLETED
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
