package com.signalasi.chat

import org.json.JSONArray
import org.json.JSONObject

enum class AgentPrivateDataExportPolicy {
    ALWAYS_ENCRYPTED,
    OPTIONAL_CONTACTS,
    OPTIONAL_SESSION_HISTORY,
    NEVER_EXPORT
}

enum class AgentPrivateDataSensitivity {
    PERSONAL,
    SECRET,
    EPHEMERAL
}

enum class AgentPrivateDataErasePolicy {
    DELETE,
    DELETE_AND_ROTATE_IDENTITY
}

data class AgentPrivateDataDescriptor(
    val id: String,
    val category: String,
    val storageIds: Set<String>,
    val backupPath: String = "",
    val exportPolicy: AgentPrivateDataExportPolicy,
    val sensitivity: AgentPrivateDataSensitivity,
    val erasePolicy: AgentPrivateDataErasePolicy = AgentPrivateDataErasePolicy.DELETE
)

data class AgentPrivateDataInventoryAudit(
    val duplicateIds: Set<String>,
    val descriptorsWithoutStorage: Set<String>,
    val exportedDescriptorsWithoutPath: Set<String>,
    val nonExportedDescriptorsWithPath: Set<String>,
    val identityRotationCount: Int
) {
    val complete: Boolean
        get() = duplicateIds.isEmpty() &&
            descriptorsWithoutStorage.isEmpty() &&
            exportedDescriptorsWithoutPath.isEmpty() &&
            nonExportedDescriptorsWithPath.isEmpty() &&
            identityRotationCount == 1
}

/** Host-owned inventory used by encrypted backup, reset, and privacy acceptance tests. */
object AgentPrivateDataInventory {
    const val POLICY_VERSION = 1

    val descriptors: List<AgentPrivateDataDescriptor> = listOf(
        item(
            "identity",
            "Identity keys and installation identity",
            "shared_prefs:signalasi_signal_store",
            "shared_prefs:signalasi_signal_trust",
            backupPath = "root.identity",
            exportPolicy = AgentPrivateDataExportPolicy.ALWAYS_ENCRYPTED,
            sensitivity = AgentPrivateDataSensitivity.SECRET,
            erasePolicy = AgentPrivateDataErasePolicy.DELETE_AND_ROTATE_IDENTITY
        ),
        item("profile", "Local profile", "shared_prefs:signalasi_app_store", backupPath = "root.profile"),
        item(
            "contacts",
            "Contacts, paired endpoints, and cloud model credentials",
            "shared_prefs:signalasi_app_store",
            backupPath = "root.contacts",
            exportPolicy = AgentPrivateDataExportPolicy.OPTIONAL_CONTACTS,
            sensitivity = AgentPrivateDataSensitivity.SECRET
        ),
        item(
            "friend_requests",
            "Pending trust requests",
            "shared_prefs:signalasi_app_store",
            backupPath = "root.friend_requests",
            exportPolicy = AgentPrivateDataExportPolicy.OPTIONAL_CONTACTS
        ),
        item(
            "chat_history",
            "Contact message history",
            "database:signalasi_chat_history",
            backupPath = "root.messages",
            exportPolicy = AgentPrivateDataExportPolicy.OPTIONAL_SESSION_HISTORY
        ),
        item("memory", "Long-term memory", "database:signalasi_agent_memory_v2", backupPath = "agent.memory"),
        item("knowledge", "Personal knowledge index", "encrypted_prefs:signalasi_agent_knowledge", backupPath = "agent.knowledge"),
        item(
            "tasks",
            "Task history",
            "database:signalasi_agent_tasks",
            backupPath = "agent.tasks",
            exportPolicy = AgentPrivateDataExportPolicy.OPTIONAL_SESSION_HISTORY
        ),
        item(
            "transcript",
            "Agent transcript",
            "database:signalasi_agent_transcript",
            "database:signalasi_agent_transcript_entries",
            backupPath = "agent.transcript",
            exportPolicy = AgentPrivateDataExportPolicy.OPTIONAL_SESSION_HISTORY
        ),
        item(
            "agent_conversations",
            "Agent conversation metadata",
            "database:signalasi_agent_transcript",
            backupPath = "agent.agent_conversations",
            exportPolicy = AgentPrivateDataExportPolicy.OPTIONAL_SESSION_HISTORY
        ),
        item(
            "active_agent_conversation",
            "Active conversation pointer",
            "database:signalasi_agent_transcript",
            backupPath = "agent.active_agent_conversation",
            exportPolicy = AgentPrivateDataExportPolicy.OPTIONAL_SESSION_HISTORY,
            sensitivity = AgentPrivateDataSensitivity.EPHEMERAL
        ),
        item("workflows", "Saved workflows", "encrypted_prefs:signalasi_agent_workflows", backupPath = "agent.workflows"),
        item("workflow_schedules", "Workflow schedules", "encrypted_prefs:signalasi_agent_workflow_schedules", backupPath = "agent.workflow_schedules"),
        item("workflow_triggers", "Workflow triggers", "encrypted_prefs:signalasi_agent_workflow_triggers", backupPath = "agent.workflow_triggers"),
        item(
            "workflow_history",
            "Workflow execution history",
            "database:signalasi_agent_workflow_execution_history",
            backupPath = "agent.workflow_execution_history"
        ),
        item("safety", "Safety and execution policy", "shared_prefs:signalasi_agent_safety", backupPath = "agent.safety"),
        item(
            "custom_devices",
            "Custom device connectors and credentials",
            "encrypted_prefs:signalasi_custom_device_connectors",
            backupPath = "agent.custom_device_connectors",
            sensitivity = AgentPrivateDataSensitivity.SECRET
        ),
        item("personal_asi", "Personal ASI world, graph, event, and autonomy state", "database:signalasi_global_super_agent", backupPath = "agent.global_super_agent"),
        item("self_model", "Learned Agent self model", "database:signalasi_agent_self_model", backupPath = "agent.agent_self_model"),
        item("model_planner", "Model planner settings", "encrypted_prefs:signalasi_agent_model_planner", backupPath = "agent.model_planner"),
        item("voice_assistant", "Wake, ASR, and TTS settings", "shared_prefs:signalasi_voice_assistant", backupPath = "agent.voice_assistant"),
        item(
            "home_assistant",
            "Home Assistant endpoint and access token",
            "encrypted_prefs:signalasi_home_assistant",
            backupPath = "agent.home_assistant",
            sensitivity = AgentPrivateDataSensitivity.SECRET
        ),
        localOnly("permission_grants", "Host permission grants", "database:signalasi_permission_grants_v1", AgentPrivateDataSensitivity.SECRET),
        localOnly("run_start_receipts", "Cross-end idempotency receipts", "database:signalasi_run_start_receipts_v1", AgentPrivateDataSensitivity.EPHEMERAL),
        localOnly("provider_health", "Per-runtime health and circuit state", "database:signalasi_agent_provider_health", AgentPrivateDataSensitivity.EPHEMERAL),
        localOnly("run_workspaces", "Active Run workspaces and checkpoints", "database:signalasi_agent_workspaces", AgentPrivateDataSensitivity.EPHEMERAL),
        localOnly("run_events", "Run event ledger", "database:signalasi_agent_runs", AgentPrivateDataSensitivity.EPHEMERAL),
        localOnly("connector_responses", "Pending connector responses", "encrypted_prefs:signalasi_agent_connector_responses", AgentPrivateDataSensitivity.EPHEMERAL),
        localOnly("mcp_credentials", "MCP connections and credentials", "encrypted_prefs:signalasi_mcp_connections", AgentPrivateDataSensitivity.SECRET),
        localOnly("mcp_packages", "Installed MCP packages", "encrypted_prefs:signalasi_mcp_packages", AgentPrivateDataSensitivity.SECRET),
        localOnly("installed_skills", "Installed executable Skill packages", "encrypted_prefs:signalasi_agent_skills", AgentPrivateDataSensitivity.SECRET),
        localOnly("link_delivery", "Signal Link outbox and inbox receipts", "shared_prefs:signalasi_link_delivery_v1", AgentPrivateDataSensitivity.EPHEMERAL),
        localOnly("runtime_files", "On-device Linux workspaces, exports, downloads, and caches", "files:agent-runtime", AgentPrivateDataSensitivity.EPHEMERAL)
    )

    fun backupManifest(
        includeContacts: Boolean,
        includeSessionHistory: Boolean
    ): JSONObject {
        val included = descriptors.filter { shouldExport(it, includeContacts, includeSessionHistory) }
        val excluded = descriptors - included.toSet()
        return JSONObject()
            .put("policy_version", POLICY_VERSION)
            .put("encrypted_container_required", true)
            .put("private_mode_exported", false)
            .put("paused_tracking_exported", false)
            .put("identity_rotated_on_reset", true)
            .put("included_store_ids", JSONArray(included.map(AgentPrivateDataDescriptor::id)))
            .put("secret_store_ids", JSONArray(included.filter {
                it.sensitivity == AgentPrivateDataSensitivity.SECRET
            }.map(AgentPrivateDataDescriptor::id)))
            .put("excluded_store_ids", JSONArray(excluded.map(AgentPrivateDataDescriptor::id)))
            .put("erase_store_ids", JSONArray(descriptors.map(AgentPrivateDataDescriptor::id)))
    }

    fun shouldExport(
        descriptor: AgentPrivateDataDescriptor,
        includeContacts: Boolean,
        includeSessionHistory: Boolean
    ): Boolean = when (descriptor.exportPolicy) {
        AgentPrivateDataExportPolicy.ALWAYS_ENCRYPTED -> true
        AgentPrivateDataExportPolicy.OPTIONAL_CONTACTS -> includeContacts
        AgentPrivateDataExportPolicy.OPTIONAL_SESSION_HISTORY -> includeSessionHistory
        AgentPrivateDataExportPolicy.NEVER_EXPORT -> false
    }

    fun audit(): AgentPrivateDataInventoryAudit {
        val idCounts = descriptors.groupingBy(AgentPrivateDataDescriptor::id).eachCount()
        return AgentPrivateDataInventoryAudit(
            duplicateIds = idCounts.filterValues { it > 1 }.keys,
            descriptorsWithoutStorage = descriptors.filter { it.storageIds.isEmpty() }.mapTo(mutableSetOf()) { it.id },
            exportedDescriptorsWithoutPath = descriptors.filter {
                it.exportPolicy != AgentPrivateDataExportPolicy.NEVER_EXPORT && it.backupPath.isBlank()
            }.mapTo(mutableSetOf()) { it.id },
            nonExportedDescriptorsWithPath = descriptors.filter {
                it.exportPolicy == AgentPrivateDataExportPolicy.NEVER_EXPORT && it.backupPath.isNotBlank()
            }.mapTo(mutableSetOf()) { it.id },
            identityRotationCount = descriptors.count {
                it.erasePolicy == AgentPrivateDataErasePolicy.DELETE_AND_ROTATE_IDENTITY
            }
        )
    }

    private fun item(
        id: String,
        category: String,
        vararg storageIds: String,
        backupPath: String,
        exportPolicy: AgentPrivateDataExportPolicy = AgentPrivateDataExportPolicy.ALWAYS_ENCRYPTED,
        sensitivity: AgentPrivateDataSensitivity = AgentPrivateDataSensitivity.PERSONAL,
        erasePolicy: AgentPrivateDataErasePolicy = AgentPrivateDataErasePolicy.DELETE
    ) = AgentPrivateDataDescriptor(
        id = id,
        category = category,
        storageIds = storageIds.toSet(),
        backupPath = backupPath,
        exportPolicy = exportPolicy,
        sensitivity = sensitivity,
        erasePolicy = erasePolicy
    )

    private fun localOnly(
        id: String,
        category: String,
        storageId: String,
        sensitivity: AgentPrivateDataSensitivity
    ) = AgentPrivateDataDescriptor(
        id = id,
        category = category,
        storageIds = setOf(storageId),
        exportPolicy = AgentPrivateDataExportPolicy.NEVER_EXPORT,
        sensitivity = sensitivity
    )
}
