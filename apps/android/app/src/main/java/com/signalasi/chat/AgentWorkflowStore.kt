package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AgentWorkflow(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val goal: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val runCount: Int = 0,
    val lastRunAtMillis: Long = 0L
)

data class AgentWorkflowTemplate(
    val id: String,
    val name: String,
    val goal: String
)

interface AgentWorkflowStore {
    fun list(): List<AgentWorkflow>
    fun find(name: String): AgentWorkflow?
    fun save(name: String, goal: String): AgentWorkflow
    fun delete(name: String): Int
    fun markRun(id: String)
}

class SharedPreferencesAgentWorkflowStore(context: Context) : AgentWorkflowStore {
    private val preferences = AgentEncryptedPreferences(context, PREFS)

    override fun list(): List<AgentWorkflow> = loadItems().sortedByDescending { it.updatedAtMillis }

    override fun find(name: String): AgentWorkflow? {
        val cleanName = normalizeName(name)
        if (cleanName.isBlank()) return null
        return loadItems().firstOrNull { normalizeName(it.name) == cleanName }
            ?: loadItems().firstOrNull { normalizeName(it.name).contains(cleanName) }
    }

    override fun save(name: String, goal: String): AgentWorkflow {
        val cleanName = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_CHARACTERS)
        val cleanGoal = goal.trim().take(MAX_GOAL_CHARACTERS)
        require(cleanName.isNotBlank()) { "Workflow name is required" }
        require(cleanGoal.isNotBlank()) { "Workflow goal is required" }
        require(!cleanGoal.startsWith("run workflow ", ignoreCase = true)) { "Nested workflow execution is not allowed" }
        require(!cleanGoal.startsWith("run template ", ignoreCase = true)) { "Nested template execution is not allowed" }
        require(WORKFLOW_MANAGEMENT_PREFIXES.none { cleanGoal.startsWith(it, ignoreCase = true) }) {
            "Workflow management commands cannot be saved as workflows"
        }
        require(cleanGoal.lowercase() !in RESERVED_CONTROL_GOALS) { "Task control commands cannot be saved as workflows" }
        val existing = loadItems().firstOrNull { normalizeName(it.name) == normalizeName(cleanName) }
        val next = AgentWorkflow(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = cleanName,
            goal = cleanGoal,
            createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis(),
            runCount = existing?.runCount ?: 0,
            lastRunAtMillis = existing?.lastRunAtMillis ?: 0L
        )
        saveItems(
            loadItems()
                .filterNot { it.id == next.id || normalizeName(it.name) == normalizeName(next.name) }
                .plus(next)
                .sortedBy { it.updatedAtMillis }
                .takeLast(MAX_ITEMS)
        )
        return next
    }

    override fun delete(name: String): Int {
        val match = find(name) ?: return 0
        val items = loadItems()
        saveItems(items.filterNot { it.id == match.id })
        return 1
    }

    override fun markRun(id: String) {
        val items = loadItems()
        if (items.none { it.id == id }) return
        saveItems(
            items.map { item ->
                if (item.id == id) {
                    item.copy(
                        runCount = item.runCount + 1,
                        lastRunAtMillis = System.currentTimeMillis(),
                        updatedAtMillis = System.currentTimeMillis()
                    )
                } else {
                    item
                }
            }
        )
    }

    private fun loadItems(): List<AgentWorkflow> {
        val raw = preferences.readString(KEY_ITEMS, "[]")
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    decode(array.optJSONObject(index) ?: continue)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveItems(items: List<AgentWorkflow>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("goal", item.goal)
                    .put("created_at", item.createdAtMillis)
                    .put("updated_at", item.updatedAtMillis)
                    .put("run_count", item.runCount)
                    .put("last_run_at", item.lastRunAtMillis)
            )
        }
        preferences.writeString(KEY_ITEMS, array.toString())
    }

    private fun decode(json: JSONObject): AgentWorkflow? {
        val name = json.optString("name").trim().take(MAX_NAME_CHARACTERS)
        val goal = json.optString("goal").trim().take(MAX_GOAL_CHARACTERS)
        if (name.isBlank() || goal.isBlank()) return null
        return AgentWorkflow(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = name,
            goal = goal,
            createdAtMillis = json.optLong("created_at", System.currentTimeMillis()),
            updatedAtMillis = json.optLong("updated_at", System.currentTimeMillis()),
            runCount = json.optInt("run_count").coerceAtLeast(0),
            lastRunAtMillis = json.optLong("last_run_at")
        )
    }

    private fun normalizeName(value: String): String = value.trim().lowercase().replace(Regex("\\s+"), " ")

    private companion object {
        const val PREFS = "signalasi_agent_workflows"
        const val KEY_ITEMS = "items"
        const val MAX_ITEMS = 100
        const val MAX_NAME_CHARACTERS = 80
        const val MAX_GOAL_CHARACTERS = 2_000
        val RESERVED_CONTROL_GOALS = setOf(
            "approve",
            "confirm",
            "pause",
            "resume",
            "cancel",
            "retry",
            "try again"
        )
        val WORKFLOW_MANAGEMENT_PREFIXES = listOf(
            "save workflow ",
            "create workflow ",
            "delete workflow ",
            "remove workflow "
        )
    }
}

object AgentWorkflowTemplates {
    val all = listOf(
        AgentWorkflowTemplate("screen-briefing", "Screen Briefing", "summarize screen"),
        AgentWorkflowTemplate("save-screen", "Save Screen Knowledge", "save screen to knowledge"),
        AgentWorkflowTemplate("device-health", "Device Health", "device status"),
        AgentWorkflowTemplate("notification-review", "Notification Review", "read notifications"),
        AgentWorkflowTemplate("knowledge-overview", "Knowledge Overview", "knowledge status"),
        AgentWorkflowTemplate("permission-check", "Permission Check", "check permissions")
    )

    fun find(name: String): AgentWorkflowTemplate? {
        val clean = name.trim().lowercase().replace(Regex("\\s+"), " ")
        return all.firstOrNull { it.id == clean || it.name.lowercase() == clean }
            ?: all.firstOrNull { it.name.lowercase().contains(clean) }
    }
}
