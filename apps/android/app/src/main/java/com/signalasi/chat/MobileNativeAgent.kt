package com.signalasi.chat

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
    private val memoryStore: AgentMemoryStore = SharedPreferencesAgentMemoryStore(context),
    private val connectorRegistry: AgentConnectorRegistry = StaticAgentConnectorRegistry(),
    private val sessionStore: AgentSessionStore = SharedPreferencesAgentSessionStore(context)
) {
    private var sessionId: String = UUID.randomUUID().toString()
    private var phase: AgentPhase = AgentPhase.OBSERVING
    private var currentGoal: String = ""
    private var currentScreen: ScreenContext = perceptionProvider.capture()
    private var currentPlan: AgentPlan? = null
    private var lastActionResult: AgentActionResult? = null
    private val auditTrail = mutableListOf<AgentAuditEntry>()

    init {
        restoreSession(sessionStore.load())
    }

    fun snapshot(): AgentUiState {
        val targets = connectorRegistry.availableTargets()
        val context = buildRuntimeContext(
            goal = currentGoal,
            screen = currentScreen,
            targets = targets,
            memories = emptyList()
        )
        return AgentUiState(
            phase = phase,
            currentGoal = currentGoal,
            currentScreen = currentScreen,
            permissionMode = context.permissionMode,
            highRiskGuard = context.highRiskGuard,
            callableTargets = targets,
            runtimeContext = context,
            runningTaskCount = if (phase == AgentPhase.PLANNING ||
                phase == AgentPhase.WAITING_CONFIRMATION ||
                phase == AgentPhase.EXECUTING ||
                phase == AgentPhase.VERIFYING) 1 else 0,
            steps = currentPlan?.steps ?: defaultSteps(),
            lastEvent = if (currentGoal.isBlank()) AgentEvent.WAITING_FOR_GOAL else AgentEvent.GOAL_RECEIVED,
            sessionId = sessionId,
            plan = currentPlan,
            pendingAction = currentPlan?.actions?.firstOrNull { it.status == AgentActionStatus.PENDING_CONFIRMATION },
            auditTrail = auditTrail.toList(),
            lastActionResult = lastActionResult
        )
    }

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
        val targets = connectorRegistry.availableTargets()
        val memories = memoryStore.recall(currentGoal)
        val context = buildRuntimeContext(
            goal = currentGoal,
            screen = currentScreen,
            targets = targets,
            memories = memories
        )
        val draftPlan = planner.plan(
            request = AgentRequest(
                goal = currentGoal,
                screen = currentScreen,
                targets = targets,
                memories = memories,
                runtimeContext = context
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
        val executionScreen = currentScreen
        lastActionResult = actionExecutor.execute(nextAction, currentScreen)
        phase = AgentPhase.VERIFYING
        val verificationScreen = captureVerificationScreen(
            action = nextAction,
            beforeAction = executionScreen,
            actionResult = lastActionResult
        )
        currentScreen = verificationScreen
        val finalStatus = if (lastActionResult?.success == true) {
            AgentActionStatus.COMPLETED
        } else {
            AgentActionStatus.FAILED
        }
        currentPlan = currentPlan?.markAction(nextAction.id, finalStatus, lastActionResult)
            ?.addVerification(AgentVerificationResult.from(nextAction.id, lastActionResult, verificationScreen))
        phase = if (lastActionResult?.success == true) AgentPhase.COMPLETED else AgentPhase.FAILED
        recordAudit(AgentAuditEvent.ACTION_EXECUTED, "action:${nextAction.kind}:${finalStatus}")
        return snapshot()
    }

    private fun captureVerificationScreen(
        action: AgentAction,
        beforeAction: ScreenContext,
        actionResult: AgentActionResult?
    ): ScreenContext {
        var latest = perceptionProvider.capture()
        if (actionResult?.success != true || !action.kind.mayChangeScreen()) {
            return latest
        }

        repeat(8) {
            if (latest.isDifferentFrom(beforeAction)) {
                return latest
            }
            runCatching { Thread.sleep(250) }
            latest = perceptionProvider.capture()
        }
        return latest
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

    private fun buildRuntimeContext(
        goal: String,
        screen: ScreenContext,
        targets: List<AgentCallableTarget>,
        memories: List<AgentMemoryItem>
    ): AgentRuntimeContext = AgentRuntimeContextBuilder.build(
        sessionId = sessionId,
        goal = goal,
        screen = screen,
        permissionMode = safetyPolicy.permissionMode(),
        highRiskGuard = safetyPolicy.highRiskGuardEnabled(),
        callableTargets = targets,
        memories = memories
    )

    private fun recordAudit(event: AgentAuditEvent, detail: String) {
        auditTrail.add(AgentAuditEntry(event = event, detail = detail, timestampMillis = System.currentTimeMillis()))
        if (auditTrail.size > MAX_AUDIT_ITEMS) {
            auditTrail.removeAt(0)
        }
        persistSession()
    }

    private fun restoreSession(session: AgentSessionSnapshot?) {
        if (session == null) return
        sessionId = session.sessionId.ifBlank { UUID.randomUUID().toString() }
        phase = session.phase
        currentGoal = session.currentGoal
        currentScreen = session.currentScreen
        currentPlan = session.currentPlan
        lastActionResult = session.lastActionResult
        auditTrail.clear()
        auditTrail.addAll(session.auditTrail.takeLast(MAX_AUDIT_ITEMS))
    }

    private fun persistSession() {
        sessionStore.save(
            AgentSessionSnapshot(
                sessionId = sessionId,
                phase = phase,
                currentGoal = currentGoal,
                currentScreen = currentScreen,
                currentPlan = currentPlan,
                auditTrail = auditTrail.toList(),
                lastActionResult = lastActionResult,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
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
    override fun capture(): ScreenContext {
        val defaultTitle = context.getString(R.string.tab_agent)
        return SignalASIAccessibilityService.captureCurrentScreen(
            defaultApp = "SignalASI",
            defaultTitle = defaultTitle
        ) ?: ScreenPerceptionState.current(
            defaultApp = "SignalASI",
            defaultTitle = defaultTitle
        )
    }

    override fun capture(foregroundApp: String, pageTitle: String): ScreenContext =
        SignalASIAccessibilityService.captureCurrentScreen(
            defaultApp = foregroundApp,
            defaultTitle = pageTitle
        ) ?: ScreenPerceptionState.current(
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
        return AgentPlanFactory.singleAction(request, action)
    }

    private fun actionFor(request: AgentRequest): AgentAction {
        val goal = request.goal.trim()
        val lower = goal.lowercase()
        AgentSystemToolPlanner.actionFor(request)?.let { return it }
        return when {
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
            lower.contains("codex") -> connectorAction(request, "codex", "Send task to Codex")
            lower.contains("claude") -> connectorAction(request, "claude-code", "Send task to Claude Code")
            lower.contains("hermes") -> connectorAction(request, "hermes", "Send task to Hermes")
            lower.contains("home assistant") || lower.contains("smart home") || lower.contains("device") -> deviceAction(request)
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

    private fun connectorAction(request: AgentRequest, connectorId: String, description: String): AgentAction {
        val target = request.targets.firstOrNull { it.id == connectorId }
        return AgentAction(
            id = "connector-$connectorId",
            kind = AgentActionKind.CALL_CONNECTOR,
            target = target?.title ?: connectorId,
            risk = AgentRisk.MEDIUM,
            status = AgentActionStatus.PENDING_CONFIRMATION,
            description = description,
            parameters = mapOf(
                "connector_id" to connectorId,
                "prompt" to request.goal
            )
        )
    }

    private fun deviceAction(request: AgentRequest): AgentAction {
        val target = request.targets.firstOrNull { it.kind == AgentConnectorKind.DEVICE }
        return AgentAction(
            id = "device-control",
            kind = AgentActionKind.CONTROL_DEVICE,
            target = target?.title ?: "Home Assistant",
            risk = AgentRisk.HIGH,
            status = AgentActionStatus.PENDING_CONFIRMATION,
            description = "Control a trusted device connector",
            parameters = mapOf("prompt" to request.goal)
        )
    }

    private fun riskFor(goal: String): AgentRisk = when {
        goal.contains("delete") -> AgentRisk.HIGH
        goal.contains("send") -> AgentRisk.MEDIUM
        goal.contains("pay") -> AgentRisk.HIGH
        else -> AgentRisk.LOW
    }
}

object AgentPlanFactory {
    fun singleAction(request: AgentRequest, action: AgentAction): AgentPlan {
        val plan = AgentPlan(
            goal = request.goal,
            screen = request.screen,
            steps = listOf(
                AgentStep(1, AgentStepKind.OBSERVE_SCREEN, AgentStepStatus.DONE),
                AgentStep(2, AgentStepKind.ANALYZE_GOAL, AgentStepStatus.DONE),
                AgentStep(3, AgentStepKind.BUILD_PLAN, AgentStepStatus.DONE),
                AgentStep(4, AgentStepKind.CONFIRM_AND_ACT, AgentStepStatus.CURRENT)
            ),
            actions = listOf(action),
            selectedAgentOrModel = selectedAgentOrModel(action),
            requiredPermissions = permissionsFor(action, request),
            confirmationRequired = true,
            rollbackStrategy = rollbackStrategyFor(action),
            expectedResult = expectedResultFor(action),
            timeoutSeconds = timeoutFor(action),
            plannerProfile = "rule-based-local",
            contextDigest = request.runtimeContext.compactSummary().hashCode().toString()
        )
        return plan.copy(validation = AgentPlanValidator.validate(plan))
    }

    private fun selectedAgentOrModel(action: AgentAction): String = when (action.kind) {
        AgentActionKind.CALL_CONNECTOR,
        AgentActionKind.CONTROL_DEVICE -> action.target
        else -> "Mobile Executor"
    }

    private fun permissionsFor(action: AgentAction, request: AgentRequest): List<AgentPermissionRequirement> {
        val permissions = mutableListOf<AgentPermissionRequirement>()
        when (action.kind) {
            AgentActionKind.READ_SCREEN,
            AgentActionKind.COPY_SCREEN_TEXT,
            AgentActionKind.TAP,
            AgentActionKind.LONG_PRESS,
            AgentActionKind.TYPE_TEXT,
            AgentActionKind.SWIPE,
            AgentActionKind.BACK,
            AgentActionKind.HOME,
            AgentActionKind.RECENTS -> permissions += AgentPermissionRequirement(
                id = "accessibility_service",
                title = "Screen Agent permission",
                granted = request.screen.isAccessibilityEnabled
            )
            AgentActionKind.OPEN_APP,
            AgentActionKind.OPEN_URL,
            AgentActionKind.SET_ALARM -> permissions += AgentPermissionRequirement(
                id = "android_intent",
                title = "Android system intent",
                granted = true
            )
            AgentActionKind.CALL_CONNECTOR,
            AgentActionKind.CONTROL_DEVICE -> permissions += AgentPermissionRequirement(
                id = "paired_contact",
                title = "Verified SignalASI contact",
                granted = false
            )
            AgentActionKind.DRAFT_PLAN -> Unit
        }
        if (action.kind == AgentActionKind.COPY_SCREEN_TEXT) {
            permissions += AgentPermissionRequirement(
                id = "clipboard_write",
                title = "Clipboard write",
                granted = true
            )
        }
        return permissions
    }

    private fun rollbackStrategyFor(action: AgentAction): String = when (action.kind) {
        AgentActionKind.TYPE_TEXT -> "Stop before sending or submitting anything."
        AgentActionKind.TAP,
        AgentActionKind.LONG_PRESS,
        AgentActionKind.SWIPE -> "Observe the result and go back if the page changed unexpectedly."
        AgentActionKind.CALL_CONNECTOR,
        AgentActionKind.CONTROL_DEVICE -> "Keep the task in chat history and report delivery failure."
        else -> "Stop execution and ask the user before retrying."
    }

    private fun expectedResultFor(action: AgentAction): String = when (action.kind) {
        AgentActionKind.OPEN_APP -> "The requested Android screen opens."
        AgentActionKind.OPEN_URL -> "The requested URL opens in a browser or matching app."
        AgentActionKind.SET_ALARM -> "Android alarm setup is opened or handed off."
        AgentActionKind.COPY_SCREEN_TEXT -> "Visible screen text is copied to the clipboard."
        AgentActionKind.CALL_CONNECTOR -> "The task is sent to the paired agent contact."
        AgentActionKind.CONTROL_DEVICE -> "The trusted device connector receives the task."
        else -> action.description
    }

    private fun timeoutFor(action: AgentAction): Int = when (action.kind) {
        AgentActionKind.CALL_CONNECTOR,
        AgentActionKind.CONTROL_DEVICE -> 120
        AgentActionKind.OPEN_URL,
        AgentActionKind.OPEN_APP,
        AgentActionKind.SET_ALARM -> 30
        else -> 20
    }
}

object AgentPlanValidator {
    fun validate(plan: AgentPlan): AgentPlanValidation {
        val issues = mutableListOf<String>()
        if (plan.goal.isBlank()) issues += "goal_blank"
        if (plan.actions.isEmpty()) issues += "actions_empty"
        plan.actions.forEach { action ->
            if (action.description.isBlank()) issues += "action_description_blank:${action.id}"
            if ((action.kind == AgentActionKind.TAP || action.kind == AgentActionKind.LONG_PRESS) &&
                action.parameters["bounds"].isNullOrBlank()) {
                issues += "action_bounds_missing:${action.id}"
            }
            if (action.kind == AgentActionKind.TYPE_TEXT && action.parameters["text"].isNullOrBlank()) {
                issues += "action_text_missing:${action.id}"
            }
        }
        if (plan.safetyReview.risk.weight >= AgentRisk.HIGH.weight && !plan.confirmationRequired) {
            issues += "high_risk_without_confirmation"
        }
        return AgentPlanValidation(
            valid = issues.isEmpty(),
            issues = issues
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
        AgentActionKind.LONG_PRESS -> {
            val bounds = action.parameters["bounds"].orEmpty()
            if (bounds.isBlank()) {
                AgentActionResult(action.id, false, "No long-press target is available")
            } else {
                serviceAction(action.id, "Long press action executed") {
                    SignalASIAccessibilityService.performLongPress(bounds)
                }
            }
        }
        AgentActionKind.HOME -> serviceAction(action.id, "Home action executed") {
            SignalASIAccessibilityService.performGlobalHome()
        }
        AgentActionKind.RECENTS -> serviceAction(action.id, "Recent apps opened") {
            SignalASIAccessibilityService.performGlobalRecents()
        }
        AgentActionKind.COPY_SCREEN_TEXT -> copyScreenText(action, screen)
        AgentActionKind.OPEN_URL -> openUrl(action)
        AgentActionKind.SET_ALARM -> setAlarm(action)
        AgentActionKind.CALL_CONNECTOR -> dispatchConnectorTask(action)
        AgentActionKind.CONTROL_DEVICE -> dispatchDeviceTask(action)
    }

    private fun openApp(action: AgentAction): AgentActionResult {
        val intentAction = action.parameters["intent_action"].orEmpty()
        val packageName = action.parameters["package"].orEmpty()
        val intent = when {
            intentAction.isNotBlank() -> Intent(intentAction)
            packageName.isNotBlank() -> context.packageManager.getLaunchIntentForPackage(packageName)
            else -> null
        } ?: return AgentActionResult(action.id, false, "No launch target is available")
        return launchIntent(action.id, intent, "Opened ${action.target}")
    }

    private fun copyScreenText(action: AgentAction, screen: ScreenContext): AgentActionResult {
        val latestScreen = SignalASIAccessibilityService.captureCurrentScreen(
            defaultApp = screen.foregroundApp,
            defaultTitle = screen.pageTitle
        ) ?: screen
        val text = latestScreen.visibleTexts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(separator = "\n")
        if (text.isBlank()) {
            return AgentActionResult(action.id, false, "No screen text is available")
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return AgentActionResult(action.id, false, "Clipboard is not available")
        clipboard.setPrimaryClip(ClipData.newPlainText("SignalASI screen text", text))
        return AgentActionResult(
            actionId = action.id,
            success = true,
            message = "Copied ${latestScreen.visibleTextCount} screen text items"
        )
    }

    private fun openUrl(action: AgentAction): AgentActionResult {
        val url = action.parameters["url"].orEmpty()
        if (url.isBlank()) {
            return AgentActionResult(action.id, false, "No URL was provided")
        }
        return launchIntent(
            actionId = action.id,
            intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            successMessage = "Opened $url"
        )
    }

    private fun setAlarm(action: AgentAction): AgentActionResult {
        val hour = action.parameters["hour"]?.toIntOrNull()
        val minute = action.parameters["minute"]?.toIntOrNull()
        val intent = if (hour != null && minute != null) {
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "SignalASI")
        } else {
            Intent(AlarmClock.ACTION_SHOW_ALARMS)
        }
        return launchIntent(
            actionId = action.id,
            intent = intent,
            successMessage = if (hour != null && minute != null) "Alarm handoff started" else "Opened alarm app"
        )
    }

    private fun launchIntent(actionId: String, intent: Intent, successMessage: String): AgentActionResult {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AgentActionResult(actionId, true, successMessage)
        } catch (error: Exception) {
            AgentActionResult(actionId, false, error.message ?: "Could not start system activity")
        }
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

    private fun dispatchConnectorTask(action: AgentAction): AgentActionResult {
        val connectorId = action.parameters["connector_id"].orEmpty()
        val prompt = action.parameters["prompt"].orEmpty().ifBlank { action.description }
        val contactId = resolveConnectorContactId(connectorId)
            ?: return AgentActionResult(action.id, false, "${action.target} is not paired")
        return dispatchContactTask(action, contactId, prompt)
    }

    private fun dispatchDeviceTask(action: AgentAction): AgentActionResult {
        val prompt = action.parameters["prompt"].orEmpty().ifBlank { action.description }
        val contactId = resolveConnectorContactId("home-assistant")
            ?: resolveConnectorContactId("home_hub")
            ?: return AgentActionResult(action.id, false, "Home Assistant is not configured")
        return dispatchContactTask(action, contactId, prompt)
    }

    private fun dispatchContactTask(action: AgentAction, contactId: String, prompt: String): AgentActionResult {
        val topic = AppStore.outgoingTopicForContact(context, contactId)
            ?: return AgentActionResult(action.id, false, "${action.target} is not verified")
        val trace = JSONArray()
            .put(JSONObject()
                .put("stage", "agent_confirmed")
                .put("at", System.currentTimeMillis())
                .put("detail", action.target))
        val messageId = ChatHistoryStore.appendOutgoing(
            context = context,
            contactId = contactId,
            content = prompt,
            deliveryStatus = context.getString(R.string.delivery_status_sending),
            deliveryTrace = trace
        )
        val published = SignalASIMqttClient.publishUserMessage(
            content = prompt,
            contactId = contactId,
            topicOverride = topic,
            clientMessageId = messageId.takeIf { it > 0L },
            deliveryTrace = trace
        )
        ChatHistoryStore.markOutgoingDelivery(
            context = context,
            contactId = contactId,
            messageId = messageId,
            stage = if (published) "mqtt_published" else "publish_failed",
            detail = topic,
            status = context.getString(if (published) R.string.delivery_status_sent else R.string.delivery_status_failed)
        )
        return AgentActionResult(
            actionId = action.id,
            success = published,
            message = if (published) "Sent task to ${action.target}" else "Could not send task to ${action.target}"
        )
    }

    private fun resolveConnectorContactId(connectorId: String): String? {
        val aliases = connectorAliases(connectorId)
        if ("hermes" in aliases && AppStore.contactById(context, "hermes") != null) return "hermes"
        val contacts = AppStore.contacts(context)
        for (index in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(index) ?: continue
            if (contact.optBoolean("deleted", false)) continue
            val id = contact.optString("id")
            val agentId = contact.optString("agent_id")
            val signalasiId = contact.optString("signalasi_id").ifBlank { contact.optString("hermes_id") }
            if (id in aliases || agentId in aliases || signalasiId in aliases) {
                return id.ifBlank { signalasiId.ifBlank { agentId } }
            }
        }
        return null
    }

    private fun connectorAliases(connectorId: String): Set<String> = when (connectorId) {
        "claude-code" -> setOf("claude-code", "claude")
        "home-assistant" -> setOf("home-assistant", "home_hub", "home-hub", "living-room-hub")
        "cloud-models" -> setOf("cloud-models", "cloud-model")
        else -> setOf(connectorId)
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

class SharedPreferencesAgentMemoryStore(context: Context) : AgentMemoryStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun remember(item: AgentMemoryItem) {
        val cleanValue = item.value.trim()
        if (cleanValue.isBlank()) return
        val nextItem = item.copy(value = cleanValue)
        val items = loadItems()
            .filterNot { it.kind == nextItem.kind && it.value.equals(nextItem.value, ignoreCase = true) }
            .plus(nextItem)
            .sortedBy { it.timestampMillis }
            .takeLast(MAX_ITEMS)
        saveItems(items)
    }

    override fun recall(query: String): List<AgentMemoryItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        return loadItems()
            .map { item -> item to score(item, cleanQuery) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<AgentMemoryItem, Int>> { it.second }.thenByDescending { it.first.timestampMillis })
            .map { it.first }
            .take(MAX_RECALL_ITEMS)
    }

    private fun score(item: AgentMemoryItem, query: String): Int {
        val value = item.value.lowercase()
        val cleanQuery = query.lowercase()
        var score = 0
        if (value == cleanQuery) score += 12
        if (value.contains(cleanQuery) || cleanQuery.contains(value)) score += 8
        cleanQuery
            .split(Regex("\\s+"))
            .filter { it.length >= MIN_TOKEN_LENGTH }
            .forEach { token ->
                if (value.contains(token)) score += 1
            }
        return score
    }

    private fun loadItems(): List<AgentMemoryItem> {
        val raw = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    decodeMemoryItem(array.optJSONObject(index) ?: continue)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveItems(items: List<AgentMemoryItem>) {
        val array = JSONArray()
        items.forEach { array.put(encodeMemoryItem(it)) }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun encodeMemoryItem(item: AgentMemoryItem): JSONObject = JSONObject()
        .put("id", item.id)
        .put("kind", item.kind.name)
        .put("value", item.value)
        .put("source", item.source)
        .put("timestamp_millis", item.timestampMillis)

    private fun decodeMemoryItem(json: JSONObject): AgentMemoryItem? {
        val value = json.optString("value").trim()
        if (value.isBlank()) return null
        return AgentMemoryItem(
            kind = enumOrDefault(json.optString("kind"), AgentMemoryKind.TASK),
            value = value,
            timestampMillis = json.optLong("timestamp_millis", System.currentTimeMillis()),
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            source = json.optString("source", "agent")
        )
    }

    companion object {
        private const val PREFS = "signalasi_agent_memory"
        private const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 200
        private const val MAX_RECALL_ITEMS = 8
        private const val MIN_TOKEN_LENGTH = 3
    }
}

interface AgentConnectorRegistry {
    fun availableTargets(): List<AgentCallableTarget>
}

class StaticAgentConnectorRegistry : AgentConnectorRegistry {
    override fun availableTargets(): List<AgentCallableTarget> = listOf(
        AgentCallableTarget(
            id = "cloud-models",
            title = "Cloud Models",
            kind = AgentConnectorKind.MODEL,
            status = AgentConnectorStatus.AVAILABLE,
            capabilities = listOf(AgentCapability.CHAT, AgentCapability.REASONING)
        ),
        AgentCallableTarget(
            id = "local-llm",
            title = "Local LLM",
            kind = AgentConnectorKind.MODEL,
            status = AgentConnectorStatus.NEEDS_SETUP,
            capabilities = listOf(AgentCapability.CHAT, AgentCapability.LOCAL_INFERENCE)
        ),
        AgentCallableTarget(
            id = "hermes",
            title = "Hermes",
            kind = AgentConnectorKind.AGENT,
            status = AgentConnectorStatus.AVAILABLE,
            capabilities = listOf(AgentCapability.CHAT, AgentCapability.RESEARCH)
        ),
        AgentCallableTarget(
            id = "codex",
            title = "Codex",
            kind = AgentConnectorKind.AGENT,
            status = AgentConnectorStatus.AVAILABLE,
            capabilities = listOf(AgentCapability.CODE, AgentCapability.TASK_EXECUTION)
        ),
        AgentCallableTarget(
            id = "claude-code",
            title = "Claude Code",
            kind = AgentConnectorKind.AGENT,
            status = AgentConnectorStatus.NEEDS_SETUP,
            capabilities = listOf(AgentCapability.CODE, AgentCapability.TASK_EXECUTION)
        ),
        AgentCallableTarget(
            id = "home-assistant",
            title = "Home Assistant",
            kind = AgentConnectorKind.DEVICE,
            status = AgentConnectorStatus.NEEDS_SETUP,
            capabilities = listOf(AgentCapability.SMART_HOME, AgentCapability.DEVICE_CONTROL)
        )
    )
}

interface AgentSessionStore {
    fun load(): AgentSessionSnapshot?
    fun save(snapshot: AgentSessionSnapshot)
    fun clear()
}

class SharedPreferencesAgentSessionStore(context: Context) : AgentSessionStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): AgentSessionSnapshot? {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            decodeSession(JSONObject(raw))
        }.getOrNull()
    }

    override fun save(snapshot: AgentSessionSnapshot) {
        prefs.edit().putString(KEY_SESSION, encodeSession(snapshot).toString()).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encodeSession(snapshot: AgentSessionSnapshot): JSONObject = JSONObject()
        .put("version", 1)
        .put("session_id", snapshot.sessionId)
        .put("phase", snapshot.phase.name)
        .put("current_goal", snapshot.currentGoal)
        .put("current_screen", encodeScreen(snapshot.currentScreen))
        .put("current_plan", snapshot.currentPlan?.let { encodePlan(it) })
        .put("audit_trail", JSONArray().also { array ->
            snapshot.auditTrail.forEach { array.put(encodeAudit(it)) }
        })
        .put("last_action_result", snapshot.lastActionResult?.let { encodeActionResult(it) })
        .put("updated_at", snapshot.updatedAtMillis)

    private fun decodeSession(json: JSONObject): AgentSessionSnapshot = AgentSessionSnapshot(
        sessionId = json.optString("session_id"),
        phase = enumOrDefault(json.optString("phase"), AgentPhase.OBSERVING),
        currentGoal = json.optString("current_goal"),
        currentScreen = decodeScreen(json.optJSONObject("current_screen")),
        currentPlan = json.optJSONObject("current_plan")?.let { decodePlan(it) },
        auditTrail = decodeAuditTrail(json.optJSONArray("audit_trail")),
        lastActionResult = json.optJSONObject("last_action_result")?.let { decodeActionResult(it) },
        updatedAtMillis = json.optLong("updated_at", 0L)
    )

    private fun encodeScreen(screen: ScreenContext): JSONObject = JSONObject()
        .put("foreground_app", screen.foregroundApp)
        .put("activity_name", screen.activityName)
        .put("page_title", screen.pageTitle)
        .put("visible_text_count", screen.visibleTextCount)
        .put("clickable_node_count", screen.clickableNodeCount)
        .put("input_field_count", screen.inputFieldCount)
        .put("scrollable_region_count", screen.scrollableRegionCount)
        .put("sensitive_flag_count", screen.sensitiveFlagCount)
        .put("visible_texts", JSONArray().also { array ->
            screen.visibleTexts.forEach { array.put(it) }
        })
        .put("clickable_elements", encodeElements(screen.clickableElements))
        .put("input_fields", encodeElements(screen.inputFields))
        .put("scrollable_regions", encodeElements(screen.scrollableRegions))
        .put("sensitive_flags", JSONArray().also { array ->
            screen.sensitiveFlags.forEach { array.put(it) }
        })
        .put("is_accessibility_enabled", screen.isAccessibilityEnabled)
        .put("snapshot_age_millis", screen.snapshotAgeMillis)

    private fun decodeScreen(json: JSONObject?): ScreenContext {
        if (json == null) return ScreenContext(foregroundApp = "SignalASI", pageTitle = "Agent")
        return ScreenContext(
            foregroundApp = json.optString("foreground_app", "SignalASI"),
            activityName = json.optString("activity_name"),
            pageTitle = json.optString("page_title", "Agent"),
            visibleTextCount = json.optInt("visible_text_count"),
            clickableNodeCount = json.optInt("clickable_node_count"),
            inputFieldCount = json.optInt("input_field_count"),
            scrollableRegionCount = json.optInt("scrollable_region_count"),
            sensitiveFlagCount = json.optInt("sensitive_flag_count"),
            visibleTexts = decodeStringList(json.optJSONArray("visible_texts")),
            clickableElements = decodeElements(json.optJSONArray("clickable_elements")),
            inputFields = decodeElements(json.optJSONArray("input_fields")),
            scrollableRegions = decodeElements(json.optJSONArray("scrollable_regions")),
            sensitiveFlags = decodeStringList(json.optJSONArray("sensitive_flags")),
            isAccessibilityEnabled = json.optBoolean("is_accessibility_enabled"),
            snapshotAgeMillis = json.optLong("snapshot_age_millis")
        )
    }

    private fun encodePlan(plan: AgentPlan): JSONObject = JSONObject()
        .put("goal", plan.goal)
        .put("screen", encodeScreen(plan.screen))
        .put("plan_id", plan.planId)
        .put("selected_agent_or_model", plan.selectedAgentOrModel)
        .put("required_permissions", encodePermissions(plan.requiredPermissions))
        .put("confirmation_required", plan.confirmationRequired)
        .put("rollback_strategy", plan.rollbackStrategy)
        .put("expected_result", plan.expectedResult)
        .put("timeout_seconds", plan.timeoutSeconds)
        .put("planner_profile", plan.plannerProfile)
        .put("context_digest", plan.contextDigest)
        .put("validation", encodePlanValidation(plan.validation))
        .put("verification_results", JSONArray().also { array ->
            plan.verificationResults.forEach { array.put(encodeVerificationResult(it)) }
        })
        .put("steps", JSONArray().also { array ->
            plan.steps.forEach { array.put(encodeStep(it)) }
        })
        .put("actions", JSONArray().also { array ->
            plan.actions.forEach { array.put(encodeAction(it)) }
        })
        .put("safety_review", encodeSafetyReview(plan.safetyReview))

    private fun decodePlan(json: JSONObject): AgentPlan = AgentPlan(
        goal = json.optString("goal"),
        screen = decodeScreen(json.optJSONObject("screen")),
        steps = decodeSteps(json.optJSONArray("steps")),
        actions = decodeActions(json.optJSONArray("actions")),
        planId = json.optString("plan_id").ifBlank { UUID.randomUUID().toString() },
        selectedAgentOrModel = json.optString("selected_agent_or_model"),
        requiredPermissions = decodePermissions(json.optJSONArray("required_permissions")),
        confirmationRequired = json.optBoolean("confirmation_required", true),
        rollbackStrategy = json.optString("rollback_strategy", "Stop execution and ask the user before retrying."),
        expectedResult = json.optString("expected_result"),
        timeoutSeconds = json.optInt("timeout_seconds", 60),
        plannerProfile = json.optString("planner_profile", "rule-based-local"),
        contextDigest = json.optString("context_digest"),
        validation = decodePlanValidation(json.optJSONObject("validation")),
        verificationResults = decodeVerificationResults(json.optJSONArray("verification_results")),
        safetyReview = decodeSafetyReview(json.optJSONObject("safety_review"))
    )

    private fun encodePermissions(permissions: List<AgentPermissionRequirement>): JSONArray = JSONArray().also { array ->
        permissions.forEach { permission ->
            array.put(JSONObject()
                .put("id", permission.id)
                .put("title", permission.title)
                .put("required", permission.required)
                .put("granted", permission.granted))
        }
    }

    private fun decodePermissions(array: JSONArray?): List<AgentPermissionRequirement> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    AgentPermissionRequirement(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        required = item.optBoolean("required", true),
                        granted = item.optBoolean("granted")
                    )
                )
            }
        }
    }

    private fun encodePlanValidation(validation: AgentPlanValidation): JSONObject = JSONObject()
        .put("valid", validation.valid)
        .put("issues", JSONArray().also { array ->
            validation.issues.forEach { array.put(it) }
        })

    private fun decodePlanValidation(json: JSONObject?): AgentPlanValidation {
        if (json == null) return AgentPlanValidation()
        return AgentPlanValidation(
            valid = json.optBoolean("valid", true),
            issues = decodeStringList(json.optJSONArray("issues"))
        )
    }

    private fun encodeVerificationResult(result: AgentVerificationResult): JSONObject = JSONObject()
        .put("action_id", result.actionId)
        .put("success", result.success)
        .put("observed_app", result.observedApp)
        .put("observed_title", result.observedTitle)
        .put("visible_text_count", result.visibleTextCount)
        .put("clickable_node_count", result.clickableNodeCount)
        .put("evidence", result.evidence)
        .put("timestamp_millis", result.timestampMillis)

    private fun decodeVerificationResults(array: JSONArray?): List<AgentVerificationResult> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    AgentVerificationResult(
                        actionId = item.optString("action_id"),
                        success = item.optBoolean("success"),
                        observedApp = item.optString("observed_app"),
                        observedTitle = item.optString("observed_title"),
                        visibleTextCount = item.optInt("visible_text_count"),
                        clickableNodeCount = item.optInt("clickable_node_count"),
                        evidence = item.optString("evidence"),
                        timestampMillis = item.optLong("timestamp_millis")
                    )
                )
            }
        }
    }

    private fun encodeStep(step: AgentStep): JSONObject = JSONObject()
        .put("order", step.order)
        .put("kind", step.kind.name)
        .put("status", step.status.name)

    private fun decodeSteps(array: JSONArray?): List<AgentStep> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    AgentStep(
                        order = item.optInt("order"),
                        kind = enumOrDefault(item.optString("kind"), AgentStepKind.OBSERVE_SCREEN),
                        status = enumOrDefault(item.optString("status"), AgentStepStatus.WAITING)
                    )
                )
            }
        }
    }

    private fun encodeAction(action: AgentAction): JSONObject = JSONObject()
        .put("id", action.id)
        .put("kind", action.kind.name)
        .put("target", action.target)
        .put("risk", action.risk.name)
        .put("status", action.status.name)
        .put("description", action.description)
        .put("parameters", JSONObject(action.parameters))
        .put("requires_confirmation", action.requiresConfirmation)
        .put("result", action.result)
        .put("evidence", action.evidence)

    private fun decodeActions(array: JSONArray?): List<AgentAction> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    AgentAction(
                        id = item.optString("id"),
                        kind = enumOrDefault(item.optString("kind"), AgentActionKind.DRAFT_PLAN),
                        target = item.optString("target"),
                        risk = enumOrDefault(item.optString("risk"), AgentRisk.LOW),
                        status = enumOrDefault(item.optString("status"), AgentActionStatus.PENDING_CONFIRMATION),
                        description = item.optString("description"),
                        parameters = decodeStringMap(item.optJSONObject("parameters")),
                        requiresConfirmation = item.optBoolean("requires_confirmation", true),
                        result = item.optString("result"),
                        evidence = item.optString("evidence")
                    )
                )
            }
        }
    }

    private fun encodeSafetyReview(review: AgentSafetyReview): JSONObject = JSONObject()
        .put("risk", review.risk.name)
        .put("requires_confirmation", review.requiresConfirmation)
        .put("blocked", review.blocked)

    private fun decodeSafetyReview(json: JSONObject?): AgentSafetyReview {
        if (json == null) return AgentSafetyReview()
        return AgentSafetyReview(
            risk = enumOrDefault(json.optString("risk"), AgentRisk.LOW),
            requiresConfirmation = json.optBoolean("requires_confirmation", true),
            blocked = json.optBoolean("blocked")
        )
    }

    private fun encodeActionResult(result: AgentActionResult): JSONObject = JSONObject()
        .put("action_id", result.actionId)
        .put("success", result.success)
        .put("message", result.message)

    private fun decodeActionResult(json: JSONObject): AgentActionResult = AgentActionResult(
        actionId = json.optString("action_id"),
        success = json.optBoolean("success"),
        message = json.optString("message")
    )

    private fun encodeAudit(audit: AgentAuditEntry): JSONObject = JSONObject()
        .put("event", audit.event.name)
        .put("detail", audit.detail)
        .put("timestamp_millis", audit.timestampMillis)

    private fun decodeAuditTrail(array: JSONArray?): List<AgentAuditEntry> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    AgentAuditEntry(
                        event = enumOrDefault(item.optString("event"), AgentAuditEvent.SCREEN_OBSERVED),
                        detail = item.optString("detail"),
                        timestampMillis = item.optLong("timestamp_millis")
                    )
                )
            }
        }
    }

    private fun encodeElements(elements: List<ScreenElement>): JSONArray = JSONArray().also { array ->
        elements.forEach { element ->
            array.put(
                JSONObject()
                    .put("label", element.label)
                    .put("view_id", element.viewId)
                    .put("class_name", element.className)
                    .put("bounds", element.bounds)
            )
        }
    }

    private fun decodeElements(array: JSONArray?): List<ScreenElement> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    ScreenElement(
                        label = item.optString("label"),
                        viewId = item.optString("view_id"),
                        className = item.optString("class_name"),
                        bounds = item.optString("bounds")
                    )
                )
            }
        }
    }

    private fun decodeStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private fun decodeStringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        return buildMap {
            json.keys().forEach { key ->
                put(key, json.optString(key))
            }
        }
    }

    companion object {
        private const val PREFS = "signalasi_agent_runtime"
        private const val KEY_SESSION = "session"
    }
}

private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
    runCatching { enumValueOf<T>(value) }.getOrElse { default }

data class AgentUiState(
    val phase: AgentPhase,
    val currentGoal: String,
    val currentScreen: ScreenContext,
    val permissionMode: PermissionMode,
    val highRiskGuard: Boolean,
    val callableTargets: List<AgentCallableTarget>,
    val runtimeContext: AgentRuntimeContext,
    val runningTaskCount: Int,
    val steps: List<AgentStep>,
    val lastEvent: AgentEvent,
    val sessionId: String,
    val plan: AgentPlan? = null,
    val pendingAction: AgentAction? = null,
    val auditTrail: List<AgentAuditEntry> = emptyList(),
    val lastActionResult: AgentActionResult? = null
)

data class AgentSessionSnapshot(
    val sessionId: String,
    val phase: AgentPhase,
    val currentGoal: String,
    val currentScreen: ScreenContext,
    val currentPlan: AgentPlan?,
    val auditTrail: List<AgentAuditEntry>,
    val lastActionResult: AgentActionResult?,
    val updatedAtMillis: Long
)

data class AgentRequest(
    val goal: String,
    val screen: ScreenContext,
    val targets: List<AgentCallableTarget>,
    val memories: List<AgentMemoryItem>,
    val runtimeContext: AgentRuntimeContext
)

data class AgentCallableTarget(
    val id: String,
    val title: String,
    val kind: AgentConnectorKind,
    val status: AgentConnectorStatus,
    val capabilities: List<AgentCapability>
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

private fun ScreenContext.isDifferentFrom(other: ScreenContext): Boolean =
    foregroundApp != other.foregroundApp ||
        pageTitle != other.pageTitle ||
        visibleTextCount != other.visibleTextCount ||
        clickableNodeCount != other.clickableNodeCount ||
        inputFieldCount != other.inputFieldCount

private fun AgentActionKind.mayChangeScreen(): Boolean = when (this) {
    AgentActionKind.TAP,
    AgentActionKind.SWIPE,
    AgentActionKind.LONG_PRESS,
    AgentActionKind.BACK,
    AgentActionKind.HOME,
    AgentActionKind.RECENTS,
    AgentActionKind.OPEN_APP,
    AgentActionKind.OPEN_URL,
    AgentActionKind.SET_ALARM -> true
    AgentActionKind.READ_SCREEN,
    AgentActionKind.DRAFT_PLAN,
    AgentActionKind.TYPE_TEXT,
    AgentActionKind.COPY_SCREEN_TEXT,
    AgentActionKind.CALL_CONNECTOR,
    AgentActionKind.CONTROL_DEVICE -> false
}

data class AgentPlan(
    val goal: String,
    val screen: ScreenContext,
    val steps: List<AgentStep>,
    val actions: List<AgentAction>,
    val planId: String = UUID.randomUUID().toString(),
    val selectedAgentOrModel: String = actions.firstOrNull()?.target.orEmpty(),
    val requiredPermissions: List<AgentPermissionRequirement> = emptyList(),
    val confirmationRequired: Boolean = true,
    val rollbackStrategy: String = "Stop execution and ask the user before retrying.",
    val expectedResult: String = actions.firstOrNull()?.description.orEmpty(),
    val timeoutSeconds: Int = 60,
    val plannerProfile: String = "rule-based-local",
    val contextDigest: String = "",
    val validation: AgentPlanValidation = AgentPlanValidation(),
    val verificationResults: List<AgentVerificationResult> = emptyList(),
    val safetyReview: AgentSafetyReview = AgentSafetyReview()
) {
    fun withSafetyReview(review: AgentSafetyReview): AgentPlan {
        val next = copy(
            safetyReview = review,
            confirmationRequired = review.requiresConfirmation
        )
        return next.copy(validation = AgentPlanValidator.validate(next))
    }

    fun markAction(
        actionId: String,
        status: AgentActionStatus,
        result: AgentActionResult? = null
    ): AgentPlan = copy(
        actions = actions.map { action ->
            if (action.id == actionId) {
                action.copy(
                    status = status,
                    result = result?.message ?: action.result,
                    evidence = result?.let { if (it.success) "executor_success" else "executor_failure" } ?: action.evidence
                )
            } else {
                action
            }
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

    fun addVerification(result: AgentVerificationResult): AgentPlan = copy(
        verificationResults = verificationResults
            .filterNot { it.actionId == result.actionId }
            .plus(result)
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
    val parameters: Map<String, String> = emptyMap(),
    val requiresConfirmation: Boolean = true,
    val result: String = "",
    val evidence: String = ""
)

data class AgentPermissionRequirement(
    val id: String,
    val title: String,
    val required: Boolean = true,
    val granted: Boolean = false
)

data class AgentPlanValidation(
    val valid: Boolean = true,
    val issues: List<String> = emptyList()
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

data class AgentVerificationResult(
    val actionId: String,
    val success: Boolean,
    val observedApp: String,
    val observedTitle: String,
    val visibleTextCount: Int,
    val clickableNodeCount: Int,
    val evidence: String,
    val timestampMillis: Long = System.currentTimeMillis()
) {
    companion object {
        fun from(
            actionId: String,
            actionResult: AgentActionResult?,
            screen: ScreenContext
        ): AgentVerificationResult = AgentVerificationResult(
            actionId = actionId,
            success = actionResult?.success == true,
            observedApp = screen.foregroundApp,
            observedTitle = screen.pageTitle,
            visibleTextCount = screen.visibleTextCount,
            clickableNodeCount = screen.clickableNodeCount,
            evidence = actionResult?.message.orEmpty()
        )
    }
}

data class AgentMemoryItem(
    val kind: AgentMemoryKind,
    val value: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString(),
    val source: String = "agent"
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
    VERIFYING,
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
    LONG_PRESS,
    BACK,
    HOME,
    RECENTS,
    OPEN_APP,
    OPEN_URL,
    SET_ALARM,
    COPY_SCREEN_TEXT,
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

enum class AgentConnectorKind {
    MODEL,
    AGENT,
    DEVICE,
    KNOWLEDGE
}

enum class AgentConnectorStatus {
    AVAILABLE,
    NEEDS_SETUP,
    DISCONNECTED
}

enum class AgentCapability {
    CHAT,
    REASONING,
    LOCAL_INFERENCE,
    RESEARCH,
    CODE,
    TASK_EXECUTION,
    SMART_HOME,
    DEVICE_CONTROL,
    KNOWLEDGE_SEARCH,
    SCREEN_READING,
    CLIPBOARD,
    SYSTEM_SETTINGS,
    APP_NAVIGATION,
    ALARM
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
