package com.signalasi.chat

data class AgentPlanEditResult(
    val plan: AgentPlan? = null,
    val error: String = ""
) {
    val success: Boolean get() = plan != null && error.isBlank()
}

object AgentPlanEditor {
    fun inputKey(action: AgentAction): String? = when (action.kind) {
        AgentActionKind.CALL_CONNECTOR,
        AgentActionKind.CONTROL_DEVICE -> "prompt"
        AgentActionKind.TYPE_TEXT -> "text"
        AgentActionKind.CREATE_NOTIFICATION -> "text"
        AgentActionKind.SET_ALARM -> "message"
        else -> null
    }

    fun inputValue(action: AgentAction): String = inputKey(action)
        ?.let { action.parameters[it] }
        .orEmpty()

    fun updatePendingAction(
        plan: AgentPlan,
        actionId: String,
        description: String,
        input: String
    ): AgentPlanEditResult {
        val action = plan.actions.firstOrNull { it.id == actionId }
            ?: return failure("Action is no longer in the active plan")
        if (!action.isEditablePending()) return failure("Only pending actions can be edited")
        val cleanDescription = description.trim().take(MAX_DESCRIPTION_CHARACTERS)
        if (cleanDescription.isBlank()) return failure("Action description cannot be empty")
        val key = inputKey(action)
        val cleanInput = input.trim().take(maxInputCharacters(action.kind))
        if (key != null && cleanInput.isBlank()) return failure("Action input cannot be empty")
        val updatedAction = action.copy(
            description = cleanDescription,
            parameters = if (key == null) action.parameters else action.parameters + (key to cleanInput)
        )
        return validateEditedPlan(
            plan,
            plan.actions.map { if (it.id == actionId) updatedAction else it },
            "updated:$actionId"
        )
    }

    fun removePendingAction(plan: AgentPlan, actionId: String): AgentPlanEditResult {
        val action = plan.actions.firstOrNull { it.id == actionId }
            ?: return failure("Action is no longer in the active plan")
        if (!action.isEditablePending()) return failure("Only pending actions can be removed")
        if (plan.actions.size <= 1) return failure("A plan must contain at least one action")
        val dependent = plan.actions.firstOrNull { actionId in it.dependencyIds() }
        if (dependent != null) return failure("Remove dependent action ${dependent.description} first")
        return validateEditedPlan(
            plan,
            plan.actions.filterNot { it.id == actionId },
            "removed:$actionId"
        )
    }

    fun movePendingAction(plan: AgentPlan, actionId: String, offset: Int): AgentPlanEditResult {
        if (offset !in setOf(-1, 1)) return failure("Unsupported move")
        val currentIndex = plan.actions.indexOfFirst { it.id == actionId }
        if (currentIndex < 0) return failure("Action is no longer in the active plan")
        val action = plan.actions[currentIndex]
        if (!action.isEditablePending()) return failure("Only pending actions can be moved")
        val targetIndex = currentIndex + offset
        if (targetIndex !in plan.actions.indices) return failure("Action is already at the plan boundary")
        if (!plan.actions[targetIndex].isEditablePending()) {
            return failure("Completed or running actions cannot be reordered")
        }
        val reordered = plan.actions.toMutableList().apply {
            val moved = removeAt(currentIndex)
            add(targetIndex, moved)
        }
        return validateEditedPlan(plan, reordered, "moved:$actionId:$offset")
    }

    private fun validateEditedPlan(
        original: AgentPlan,
        actions: List<AgentAction>,
        editSummary: String
    ): AgentPlanEditResult {
        var candidate = original.copy(
            actions = actions,
            revision = original.revision + 1,
            routeRationale = original.routeRationale.substringBefore(" User edit:") +
                " User edit: $editSummary."
        )
        candidate = candidate.copy(validation = AgentPlanValidator.validate(candidate))
        return if (candidate.validation.valid) {
            AgentPlanEditResult(plan = candidate)
        } else {
            failure(candidate.validation.issues.joinToString(", ").take(300))
        }
    }

    private fun AgentAction.isEditablePending(): Boolean = status in setOf(
        AgentActionStatus.PROPOSED,
        AgentActionStatus.PENDING_CONFIRMATION
    )

    private fun maxInputCharacters(kind: AgentActionKind): Int = when (kind) {
        AgentActionKind.TYPE_TEXT -> 2_000
        AgentActionKind.CREATE_NOTIFICATION -> 1_000
        AgentActionKind.SET_ALARM -> 200
        else -> 4_000
    }

    private fun failure(message: String): AgentPlanEditResult = AgentPlanEditResult(error = message)

    private const val MAX_DESCRIPTION_CHARACTERS = 300
}
