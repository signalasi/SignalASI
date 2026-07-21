package com.signalasi.chat

import java.util.Locale

enum class AgentRuntimeCapabilitySource {
    NATIVE_TOOL,
    SYSTEM_TOOL,
    CONNECTOR
}

enum class AgentRuntimeCapabilityState {
    AVAILABLE,
    REQUIRES_SETUP,
    UNAVAILABLE,
    BLOCKED
}

data class AgentRuntimeCapabilityEntry(
    val id: String,
    val title: String,
    val source: AgentRuntimeCapabilitySource,
    val state: AgentRuntimeCapabilityState,
    val capabilities: Set<String>,
    val location: String,
    val risk: String,
    val reason: String = "",
    val requiredPermissions: Set<String> = emptySet(),
    val requiredConsents: Set<String> = emptySet()
) {
    val executable: Boolean
        get() = state == AgentRuntimeCapabilityState.AVAILABLE && risk != AgentNativeToolRisk.BLOCKED.wireValue
}

data class AgentRuntimeCapabilitySnapshot(
    val entries: List<AgentRuntimeCapabilityEntry>
) {
    val availableEntries: List<AgentRuntimeCapabilityEntry>
        get() = entries.filter(AgentRuntimeCapabilityEntry::executable)

    val availableNativeToolIds: Set<String>
        get() = entries.asSequence()
            .filter { it.source == AgentRuntimeCapabilitySource.NATIVE_TOOL && it.executable }
            .mapTo(linkedSetOf(), AgentRuntimeCapabilityEntry::id)

    val setupRequiredEntries: List<AgentRuntimeCapabilityEntry>
        get() = entries.filter { it.state == AgentRuntimeCapabilityState.REQUIRES_SETUP }

    val unavailableEntries: List<AgentRuntimeCapabilityEntry>
        get() = entries.filter {
            it.state == AgentRuntimeCapabilityState.UNAVAILABLE ||
                it.state == AgentRuntimeCapabilityState.BLOCKED
        }

    fun entry(source: AgentRuntimeCapabilitySource, id: String): AgentRuntimeCapabilityEntry? =
        entries.firstOrNull { it.source == source && it.id == id }

    fun isNativeToolExecutable(id: String): Boolean =
        entry(AgentRuntimeCapabilitySource.NATIVE_TOOL, id)?.executable == true

    companion object {
        val EMPTY = AgentRuntimeCapabilitySnapshot(emptyList())
    }
}

/**
 * One host-owned view of what is installed, configured, permitted, and executable now.
 * Unavailable entries stay visible for diagnostics but never become planning candidates.
 */
object AgentRuntimeCapabilityMatrix {
    fun build(
        nativeTools: List<AgentNativeToolDescriptor>,
        systemTools: List<AgentSystemTool>,
        targets: List<AgentCallableTarget>
    ): AgentRuntimeCapabilitySnapshot {
        val nativeById = nativeTools.associateBy(AgentNativeToolDescriptor::id)
        val entries = buildList {
            addAll(nativeTools.map(::nativeEntry))
            addAll(systemTools.map { systemEntry(it, nativeById) })
            addAll(targets.map(::connectorEntry))
        }.distinctBy { "${it.source.name}:${it.id}" }
            .sortedWith(compareBy<AgentRuntimeCapabilityEntry> { it.source.ordinal }.thenBy { it.id })
        return AgentRuntimeCapabilitySnapshot(entries)
    }

    fun availableNativeTools(
        nativeTools: List<AgentNativeToolDescriptor>,
        systemTools: List<AgentSystemTool> = emptyList(),
        targets: List<AgentCallableTarget> = emptyList()
    ): List<AgentNativeToolDescriptor> {
        val snapshot = build(nativeTools, systemTools, targets)
        return nativeTools.filter { snapshot.isNativeToolExecutable(it.id) }
    }

    private fun nativeEntry(tool: AgentNativeToolDescriptor): AgentRuntimeCapabilityEntry {
        val state = when {
            tool.risk == AgentNativeToolRisk.BLOCKED -> AgentRuntimeCapabilityState.BLOCKED
            tool.availability.status == AgentNativeToolAvailabilityStatus.AVAILABLE ->
                AgentRuntimeCapabilityState.AVAILABLE
            tool.availability.status == AgentNativeToolAvailabilityStatus.REQUIRES_SETUP ->
                AgentRuntimeCapabilityState.REQUIRES_SETUP
            else -> AgentRuntimeCapabilityState.UNAVAILABLE
        }
        return AgentRuntimeCapabilityEntry(
            id = tool.id,
            title = tool.title,
            source = AgentRuntimeCapabilitySource.NATIVE_TOOL,
            state = state,
            capabilities = tool.capabilities,
            location = tool.location.wireValue,
            risk = tool.risk.wireValue,
            reason = tool.availability.reason,
            requiredPermissions = tool.requiredPermissions.filter { it.required }.mapTo(linkedSetOf()) { it.id },
            requiredConsents = tool.requiredConsents.filter { it.required }.mapTo(linkedSetOf()) { it.id }
        )
    }

    private fun systemEntry(
        tool: AgentSystemTool,
        nativeById: Map<String, AgentNativeToolDescriptor>
    ): AgentRuntimeCapabilityEntry {
        val hostOwnedWorkflow = tool.id.startsWith("workflow:") || tool.id.startsWith("template:")
        val native = nativeById[AgentNativeToolAgentActionAdapter.defaultToolId(tool.kind)]
        val state = when {
            tool.risk == AgentRisk.BLOCKED -> AgentRuntimeCapabilityState.BLOCKED
            hostOwnedWorkflow -> AgentRuntimeCapabilityState.AVAILABLE
            native == null -> AgentRuntimeCapabilityState.UNAVAILABLE
            native.risk == AgentNativeToolRisk.BLOCKED -> AgentRuntimeCapabilityState.BLOCKED
            native.availability.status == AgentNativeToolAvailabilityStatus.AVAILABLE ->
                AgentRuntimeCapabilityState.AVAILABLE
            native.availability.status == AgentNativeToolAvailabilityStatus.REQUIRES_SETUP ->
                AgentRuntimeCapabilityState.REQUIRES_SETUP
            else -> AgentRuntimeCapabilityState.UNAVAILABLE
        }
        return AgentRuntimeCapabilityEntry(
            id = tool.id,
            title = tool.title,
            source = AgentRuntimeCapabilitySource.SYSTEM_TOOL,
            state = state,
            capabilities = tool.capabilities.mapTo(linkedSetOf()) { it.name.lowercase(Locale.US) },
            location = AgentResourceLocation.PHONE.name.lowercase(Locale.US),
            risk = tool.risk.name.lowercase(Locale.US),
            reason = when {
                hostOwnedWorkflow -> "Host-owned workflow is installed"
                native == null -> "No executable native adapter is registered"
                else -> native.availability.reason
            },
            requiredPermissions = native?.requiredPermissions.orEmpty()
                .filter { it.required }.mapTo(linkedSetOf()) { it.id },
            requiredConsents = native?.requiredConsents.orEmpty()
                .filter { it.required }.mapTo(linkedSetOf()) { it.id }
        )
    }

    private fun connectorEntry(target: AgentCallableTarget): AgentRuntimeCapabilityEntry =
        AgentRuntimeCapabilityEntry(
            id = target.id,
            title = target.title,
            source = AgentRuntimeCapabilitySource.CONNECTOR,
            state = when (target.status) {
                AgentConnectorStatus.AVAILABLE -> AgentRuntimeCapabilityState.AVAILABLE
                AgentConnectorStatus.NEEDS_SETUP -> AgentRuntimeCapabilityState.REQUIRES_SETUP
                AgentConnectorStatus.DISCONNECTED -> AgentRuntimeCapabilityState.UNAVAILABLE
            },
            capabilities = target.capabilities.mapTo(linkedSetOf()) { it.name.lowercase(Locale.US) },
            location = target.failureDomain.ifBlank { "external" },
            risk = AgentNativeToolRisk.MEDIUM.wireValue,
            reason = target.status.name.lowercase(Locale.US)
        )
}
