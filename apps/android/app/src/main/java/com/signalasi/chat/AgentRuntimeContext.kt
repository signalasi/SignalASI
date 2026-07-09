package com.signalasi.chat

data class AgentRuntimeContext(
    val sessionId: String,
    val goal: String,
    val screen: ScreenContext,
    val permissionMode: PermissionMode,
    val highRiskGuard: Boolean,
    val systemTools: List<AgentSystemTool>,
    val callableTargets: List<AgentCallableTarget>,
    val memories: List<AgentMemoryItem>,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    val callableCount: Int
        get() = systemTools.size + callableTargets.size

    fun compactSummary(): String = buildString {
        append("session=").append(sessionId)
        append("; screen=").append(screen.foregroundApp)
        append("; texts=").append(screen.visibleTextCount)
        append("; actions=").append(screen.clickableNodeCount)
        append("; tools=").append(systemTools.size)
        append("; targets=").append(callableTargets.size)
        append("; memories=").append(memories.size)
        append("; mode=").append(permissionMode.name)
    }
}

data class AgentSystemTool(
    val id: String,
    val title: String,
    val kind: AgentActionKind,
    val risk: AgentRisk,
    val capabilities: List<AgentCapability>,
    val examples: List<String>
)

object AgentRuntimeContextBuilder {
    fun build(
        sessionId: String,
        goal: String,
        screen: ScreenContext,
        permissionMode: PermissionMode,
        highRiskGuard: Boolean,
        callableTargets: List<AgentCallableTarget>,
        memories: List<AgentMemoryItem>
    ): AgentRuntimeContext = AgentRuntimeContext(
        sessionId = sessionId,
        goal = goal,
        screen = screen,
        permissionMode = permissionMode,
        highRiskGuard = highRiskGuard,
        systemTools = AgentSystemToolPlanner.availableTools(),
        callableTargets = callableTargets,
        memories = memories
    )
}
