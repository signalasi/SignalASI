package com.signalasi.chat

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPrivateDataInventoryTest {
    @Test
    fun everyPrivateStoreHasOneExportAndEraseDecision() {
        val audit = AgentPrivateDataInventory.audit()

        assertTrue(audit.toString(), audit.complete)
        assertEquals(
            AgentPrivateDataInventory.descriptors.size,
            AgentPrivateDataInventory.descriptors.map { it.id }.distinct().size
        )
    }

    @Test
    fun minimalBackupExcludesHistoryContactsAndEphemeralExecutionState() {
        val manifest = AgentPrivateDataInventory.backupManifest(
            includeContacts = false,
            includeSessionHistory = false
        )
        val included = manifest.getJSONArray("included_store_ids").strings()
        val excluded = manifest.getJSONArray("excluded_store_ids").strings()

        assertTrue(manifest.getBoolean("encrypted_container_required"))
        assertFalse(manifest.getBoolean("private_mode_exported"))
        assertFalse(manifest.getBoolean("paused_tracking_exported"))
        assertTrue("identity" in included)
        assertTrue("memory" in included)
        assertTrue("personal_asi" in included)
        assertTrue("contacts" in excluded)
        assertTrue("chat_history" in excluded)
        assertTrue("transcript" in excluded)
        assertTrue("permission_grants" in excluded)
        assertTrue("run_start_receipts" in excluded)
        assertTrue("runtime_files" in excluded)
    }

    @Test
    fun fullBackupIncludesChosenUserDataButNeverLiveAuthorityOrReceipts() {
        val manifest = AgentPrivateDataInventory.backupManifest(
            includeContacts = true,
            includeSessionHistory = true
        )
        val included = manifest.getJSONArray("included_store_ids").strings()
        val excluded = manifest.getJSONArray("excluded_store_ids").strings()
        val secret = manifest.getJSONArray("secret_store_ids").strings()

        assertTrue("contacts" in included)
        assertTrue("chat_history" in included)
        assertTrue("transcript" in included)
        assertTrue("home_assistant" in included)
        assertTrue("identity" in secret)
        assertTrue("home_assistant" in secret)
        assertTrue("permission_grants" in excluded)
        assertTrue("run_start_receipts" in excluded)
        assertTrue("mcp_credentials" in excluded)
        assertEquals(
            AgentPrivateDataInventory.descriptors.map { it.id }.toSet(),
            included + excluded
        )
    }

    @Test
    fun exportedBackupPathsMatchTheProductionBackupSchema() {
        val exportedPaths = AgentPrivateDataInventory.descriptors
            .filter { it.exportPolicy != AgentPrivateDataExportPolicy.NEVER_EXPORT }
            .mapTo(linkedSetOf(), AgentPrivateDataDescriptor::backupPath)

        assertEquals(setOf(
            "root.identity",
            "root.profile",
            "root.contacts",
            "root.friend_requests",
            "root.messages",
            "agent.memory",
            "agent.knowledge",
            "agent.tasks",
            "agent.transcript",
            "agent.agent_conversations",
            "agent.active_agent_conversation",
            "agent.workflows",
            "agent.workflow_schedules",
            "agent.workflow_triggers",
            "agent.workflow_execution_history",
            "agent.safety",
            "agent.custom_device_connectors",
            "agent.global_super_agent",
            "agent.agent_self_model",
            "agent.model_planner",
            "agent.voice_assistant",
            "agent.home_assistant"
        ), exportedPaths)
    }

    private fun JSONArray.strings(): Set<String> = buildSet {
        for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
    }
}
