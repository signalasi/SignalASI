package com.signalasi.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class AgentWorkflowTriggerKind {
    NOTIFICATION_PACKAGE,
    NOTIFICATION_TEXT,
    POWER_CONNECTED,
    BATTERY_LOW
}

data class AgentWorkflowTrigger(
    val id: String = UUID.randomUUID().toString(),
    val workflowId: String,
    val workflowName: String,
    val kind: AgentWorkflowTriggerKind,
    val condition: String = "",
    val enabled: Boolean = true,
    val cooldownMinutes: Int = 5,
    val lastTriggeredAtMillis: Long = 0L,
    val createdAtMillis: Long = System.currentTimeMillis()
)

private data class AgentWorkflowTriggerIdentity(
    val workflowId: String,
    val kind: AgentWorkflowTriggerKind,
    val condition: String
)

class AgentWorkflowTriggerStore(context: Context) {
    private val preferences = AgentEncryptedPreferences(context, PREFS)

    fun list(): List<AgentWorkflowTrigger> = load().sortedByDescending { it.createdAtMillis }

    fun findById(id: String): AgentWorkflowTrigger? = load().firstOrNull { it.id == id }

    fun findByWorkflowName(name: String): List<AgentWorkflowTrigger> {
        val clean = name.trim()
        if (clean.isBlank()) return emptyList()
        return load().filter {
            it.workflowName.equals(clean, ignoreCase = true) ||
                it.workflowName.contains(clean, ignoreCase = true)
        }
    }

    fun upsert(trigger: AgentWorkflowTrigger) {
        val normalized = normalizeForWrite(trigger)
        save(
            load()
                .filterNot {
                    it.id == normalized.id || identity(it) == identity(normalized)
                }
                .plus(normalized)
                .sortedBy { it.createdAtMillis }
        )
    }

    fun markTriggered(id: String, timestampMillis: Long = System.currentTimeMillis()) {
        val items = load()
        if (items.none { it.id == id }) return
        val safeTimestamp = timestampMillis.coerceAtLeast(0L)
        save(items.map { if (it.id == id) it.copy(lastTriggeredAtMillis = safeTimestamp) else it })
    }

    fun delete(id: String): Int {
        val items = load()
        val kept = items.filterNot { it.id == id }
        if (kept.size != items.size) save(kept)
        return items.size - kept.size
    }

    fun deleteForWorkflow(workflowId: String): Int {
        val items = load()
        val kept = items.filterNot { it.workflowId == workflowId }
        if (kept.size != items.size) save(kept)
        return items.size - kept.size
    }

    fun clear() = preferences.clear()

    private fun load(): List<AgentWorkflowTrigger> {
        val raw = preferences.readString(KEY_ITEMS, "[]")
        if (raw.length > MAX_SERIALIZED_CHARS) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val firstIndex = (array.length() - MAX_LOAD_CANDIDATES).coerceAtLeast(0)
            val decoded = buildList {
                for (index in firstIndex until array.length()) {
                    decode(array.optJSONObject(index) ?: continue)?.let(::add)
                }
            }
            deduplicateNewest(decoded)
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<AgentWorkflowTrigger>) {
        val array = JSONArray()
        deduplicateNewest(items.mapNotNull(::normalizeOrNull)).forEach { trigger ->
            array.put(
                JSONObject()
                    .put("id", trigger.id)
                    .put("workflow_id", trigger.workflowId)
                    .put("workflow_name", trigger.workflowName)
                    .put("kind", trigger.kind.name)
                    .put("condition", trigger.condition)
                    .put("enabled", trigger.enabled)
                    .put("cooldown_minutes", trigger.cooldownMinutes)
                    .put("last_triggered_at", trigger.lastTriggeredAtMillis)
                    .put("created_at", trigger.createdAtMillis)
            )
        }
        val serialized = array.toString()
        check(serialized.length <= MAX_SERIALIZED_CHARS) { "Agent workflow trigger storage limit exceeded" }
        preferences.writeString(KEY_ITEMS, serialized)
    }

    private fun decode(json: JSONObject): AgentWorkflowTrigger? {
        val kind = enumOrNull<AgentWorkflowTriggerKind>(json.optString("kind")) ?: return null
        return normalizeOrNull(
            AgentWorkflowTrigger(
                id = json.optString("id"),
                workflowId = json.optString("workflow_id"),
                workflowName = json.optString("workflow_name"),
                kind = kind,
                condition = json.optString("condition"),
                enabled = json.optBoolean("enabled", true),
                cooldownMinutes = json.optInt("cooldown_minutes", 5).coerceIn(1, MAX_COOLDOWN_MINUTES),
                lastTriggeredAtMillis = json.optLong("last_triggered_at").coerceAtLeast(0L),
                createdAtMillis = json.optLong("created_at", System.currentTimeMillis()).coerceAtLeast(0L)
            )
        )
    }

    private fun normalizeForWrite(trigger: AgentWorkflowTrigger): AgentWorkflowTrigger {
        require(!trigger.kind.requiresCondition() || trigger.condition.isNotBlank()) {
            "Notification workflow trigger condition must not be blank"
        }
        val normalized = normalizeOrNull(trigger)
        requireNotNull(normalized) {
            "Workflow trigger fields are blank, invalid, or exceed storage limits"
        }
        return normalized
    }

    private fun normalizeOrNull(trigger: AgentWorkflowTrigger): AgentWorkflowTrigger? {
        val id = trigger.id.trim()
        val workflowId = trigger.workflowId.trim()
        val workflowName = trigger.workflowName.trim()
        val condition = trigger.condition.trim()
        if (id.isBlank() || id.length > MAX_IDENTIFIER_LENGTH) return null
        if (workflowId.isBlank() || workflowId.length > MAX_IDENTIFIER_LENGTH) return null
        if (workflowName.isBlank() || workflowName.length > MAX_WORKFLOW_NAME_LENGTH) return null
        if (condition.length > MAX_CONDITION_LENGTH) return null
        if (trigger.kind.requiresCondition() && condition.isBlank()) return null
        return trigger.copy(
            id = id,
            workflowId = workflowId,
            workflowName = workflowName,
            condition = condition,
            cooldownMinutes = trigger.cooldownMinutes.coerceIn(1, MAX_COOLDOWN_MINUTES),
            lastTriggeredAtMillis = trigger.lastTriggeredAtMillis.coerceAtLeast(0L),
            createdAtMillis = trigger.createdAtMillis.coerceAtLeast(0L)
        )
    }

    private fun deduplicateNewest(items: List<AgentWorkflowTrigger>): List<AgentWorkflowTrigger> {
        val seenIds = HashSet<String>()
        val seenIdentities = HashSet<AgentWorkflowTriggerIdentity>()
        val newestFirst = ArrayList<AgentWorkflowTrigger>(minOf(items.size, MAX_ITEMS))
        for (index in items.indices.reversed()) {
            val trigger = items[index]
            val identity = identity(trigger)
            if (trigger.id in seenIds || identity in seenIdentities) continue
            seenIds += trigger.id
            seenIdentities += identity
            newestFirst += trigger
            if (newestFirst.size == MAX_ITEMS) break
        }
        newestFirst.reverse()
        return newestFirst
    }

    private fun identity(trigger: AgentWorkflowTrigger): AgentWorkflowTriggerIdentity =
        AgentWorkflowTriggerIdentity(
            workflowId = trigger.workflowId,
            kind = trigger.kind,
            condition = if (trigger.kind.requiresCondition()) {
                trigger.condition.lowercase(Locale.ROOT)
            } else {
                ""
            }
        )

    private fun AgentWorkflowTriggerKind.requiresCondition(): Boolean =
        this == AgentWorkflowTriggerKind.NOTIFICATION_PACKAGE ||
            this == AgentWorkflowTriggerKind.NOTIFICATION_TEXT

    private inline fun <reified T : Enum<T>> enumOrNull(value: String): T? =
        runCatching { enumValueOf<T>(value) }.getOrNull()

    private companion object {
        const val PREFS = "signalasi_agent_workflow_triggers"
        const val KEY_ITEMS = "items"
        const val MAX_ITEMS = 100
        const val MAX_LOAD_CANDIDATES = MAX_ITEMS * 4
        const val MAX_SERIALIZED_CHARS = 512 * 1024
        const val MAX_IDENTIFIER_LENGTH = 128
        const val MAX_WORKFLOW_NAME_LENGTH = 80
        const val MAX_CONDITION_LENGTH = 240
        const val MAX_COOLDOWN_MINUTES = 7 * 24 * 60
    }
}

object AgentWorkflowTriggerEngine {
    const val ACTION_RUN_TRIGGER = "com.signalasi.chat.action.RUN_AGENT_WORKFLOW_TRIGGER"
    const val EXTRA_TRIGGER_ID = "agent_trigger_id"

    @Synchronized
    fun onNotification(context: Context, item: AgentNotificationItem) {
        val now = System.currentTimeMillis()
        val store = AgentWorkflowTriggerStore(context)
        store.list()
            .filter { it.enabled && isReady(it, now) }
            .filter { trigger ->
                when (trigger.kind) {
                    AgentWorkflowTriggerKind.NOTIFICATION_PACKAGE ->
                        trigger.condition.isNotBlank() &&
                            item.packageName.take(MAX_NOTIFICATION_PACKAGE_CHARS)
                                .contains(trigger.condition, ignoreCase = true)
                    AgentWorkflowTriggerKind.NOTIFICATION_TEXT ->
                        matchesNotificationText(item, trigger.condition)
                    AgentWorkflowTriggerKind.POWER_CONNECTED,
                    AgentWorkflowTriggerKind.BATTERY_LOW -> false
                }
            }
            .forEach { trigger -> dispatch(context, store, trigger, now) }
    }

    @Synchronized
    fun onDeviceEvent(context: Context, kind: AgentWorkflowTriggerKind) {
        if (kind != AgentWorkflowTriggerKind.POWER_CONNECTED &&
            kind != AgentWorkflowTriggerKind.BATTERY_LOW
        ) {
            return
        }
        val now = System.currentTimeMillis()
        val store = AgentWorkflowTriggerStore(context)
        store.list()
            .filter { it.enabled && it.kind == kind && isReady(it, now) }
            .forEach { trigger -> dispatch(context, store, trigger, now) }
    }

    private fun isReady(trigger: AgentWorkflowTrigger, now: Long): Boolean {
        if (trigger.lastTriggeredAtMillis <= 0L) return true
        if (now < trigger.lastTriggeredAtMillis) return false
        val cooldownMillis = trigger.cooldownMinutes
            .coerceIn(1, MAX_COOLDOWN_MINUTES)
            .toLong() * 60_000L
        return now - trigger.lastTriggeredAtMillis >= cooldownMillis
    }

    private fun matchesNotificationText(item: AgentNotificationItem, condition: String): Boolean {
        if (condition.isBlank() || item.sensitiveFlags.isNotEmpty()) return false
        return item.title.take(MAX_NOTIFICATION_TEXT_CHARS).contains(condition, ignoreCase = true) ||
            item.textPreview.take(MAX_NOTIFICATION_TEXT_CHARS).contains(condition, ignoreCase = true)
    }

    private fun dispatch(
        context: Context,
        store: AgentWorkflowTriggerStore,
        trigger: AgentWorkflowTrigger,
        now: Long
    ) {
        store.markTriggered(trigger.id, now)
        val intent = Intent(context, MessageService::class.java)
            .setAction(ACTION_RUN_TRIGGER)
            .putExtra(EXTRA_TRIGGER_ID, trigger.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private const val MAX_NOTIFICATION_PACKAGE_CHARS = 512
    private const val MAX_NOTIFICATION_TEXT_CHARS = 4_096
    private const val MAX_COOLDOWN_MINUTES = 7 * 24 * 60
}

class AgentWorkflowEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED ->
                AgentWorkflowTriggerEngine.onDeviceEvent(context, AgentWorkflowTriggerKind.POWER_CONNECTED)
            Intent.ACTION_BATTERY_LOW ->
                AgentWorkflowTriggerEngine.onDeviceEvent(context, AgentWorkflowTriggerKind.BATTERY_LOW)
        }
    }
}
