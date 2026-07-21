package com.signalasi.chat

data class AgentRuntimeContext(
    val sessionId: String,
    val goal: String,
    val screen: ScreenContext,
    val permissionMode: PermissionMode,
    val highRiskGuard: Boolean,
    val memoryCapture: Boolean,
    val systemTools: List<AgentSystemTool>,
    val nativeTools: List<AgentNativeToolDescriptor> = emptyList(),
    val callableTargets: List<AgentCallableTarget>,
    val memories: List<AgentMemoryItem>,
    val knowledgeItems: List<AgentKnowledgeItem>,
    val knowledgeStats: AgentKnowledgeStats,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val capabilityMatrix: AgentRuntimeCapabilitySnapshot = AgentRuntimeCapabilitySnapshot.EMPTY
) {
    val callableCount: Int
        get() = if (capabilityMatrix.entries.isEmpty()) {
            nativeTools.count { it.availability.status == AgentNativeToolAvailabilityStatus.AVAILABLE } +
                callableTargets.count { it.status == AgentConnectorStatus.AVAILABLE }
        } else {
            capabilityMatrix.availableEntries.count {
                it.source == AgentRuntimeCapabilitySource.NATIVE_TOOL ||
                    it.source == AgentRuntimeCapabilitySource.CONNECTOR
            }
        }

    fun isNativeToolExecutable(id: String): Boolean = if (capabilityMatrix.entries.isEmpty()) {
        nativeTools.firstOrNull { it.id == id }?.let { descriptor ->
            descriptor.availability.status == AgentNativeToolAvailabilityStatus.AVAILABLE &&
                descriptor.risk != AgentNativeToolRisk.BLOCKED
        } == true
    } else {
        capabilityMatrix.isNativeToolExecutable(id)
    }

    fun compactSummary(): String = buildString {
        append("session=").append(sessionId)
        append("; screen=").append(screen.foregroundApp)
        append("; texts=").append(screen.visibleTextCount)
        append("; actions=").append(screen.clickableNodeCount)
        append("; notifications=").append(screen.notifications.items.size)
        append("; apps=").append(screen.installedApps.size)
        append("; battery=").append(screen.deviceStatus.batteryPercent)
        append("; network=").append(screen.deviceStatus.network)
        append("; tools=").append(systemTools.size)
        append("; native_tools=").append(nativeTools.size)
        append("; native_tools_available=").append(capabilityMatrix.availableNativeToolIds.size)
        append("; native_tools_setup=").append(
            capabilityMatrix.setupRequiredEntries.count { it.source == AgentRuntimeCapabilitySource.NATIVE_TOOL }
        )
        append("; targets=").append(callableTargets.size)
        append("; targets_available=").append(
            capabilityMatrix.availableEntries.count { it.source == AgentRuntimeCapabilitySource.CONNECTOR }
        )
        append("; memories=").append(memories.size)
        append("; knowledge=").append(knowledgeStats.itemCount)
        append("; knowledge_hits=").append(knowledgeItems.size)
        append("; mode=").append(permissionMode.name)
        append("; memory_capture=").append(memoryCapture)
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
        memoryCapture: Boolean,
        callableTargets: List<AgentCallableTarget>,
        memories: List<AgentMemoryItem>,
        systemTools: List<AgentSystemTool> = AgentSystemToolPlanner.availableTools(),
        nativeTools: List<AgentNativeToolDescriptor> = emptyList(),
        knowledgeItems: List<AgentKnowledgeItem> = emptyList(),
        knowledgeStats: AgentKnowledgeStats = AgentKnowledgeStats()
    ): AgentRuntimeContext {
        val capabilityMatrix = AgentRuntimeCapabilityMatrix.build(
            nativeTools = nativeTools,
            systemTools = systemTools,
            targets = callableTargets
        )
        return AgentRuntimeContext(
            sessionId = sessionId,
            goal = goal,
            screen = screen,
            permissionMode = permissionMode,
            highRiskGuard = highRiskGuard,
            memoryCapture = memoryCapture,
            systemTools = systemTools,
            nativeTools = nativeTools,
            callableTargets = callableTargets,
            memories = memories,
            knowledgeItems = knowledgeItems,
            knowledgeStats = knowledgeStats,
            capabilityMatrix = capabilityMatrix
        )
    }
}
