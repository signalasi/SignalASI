package com.signalasi.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
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
    val createdAtMillis: Long = System.currentTimeMillis(),
    val conditions: List<AgentWorkflowCondition> = emptyList()
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
        val bounded = deduplicateNewest(items.mapNotNull(::normalizeOrNull)).toMutableList()
        while (true) {
            val array = JSONArray()
            bounded.forEach { trigger -> array.put(encodeTrigger(trigger)) }
            val serialized = array.toString()
            if (serialized.length <= MAX_SERIALIZED_CHARS) {
                preferences.writeString(KEY_ITEMS, serialized)
                return
            }
            check(bounded.isNotEmpty()) { "Agent workflow trigger storage limit exceeded" }
            bounded.removeAt(0)
        }
    }

    private fun encodeTrigger(trigger: AgentWorkflowTrigger): JSONObject = JSONObject()
        .put("id", trigger.id)
        .put("workflow_id", trigger.workflowId)
        .put("workflow_name", trigger.workflowName)
        .put("kind", trigger.kind.name)
        .put("condition", trigger.condition)
        .put("conditions", encodeConditions(trigger.conditions))
        .put("enabled", trigger.enabled)
        .put("cooldown_minutes", trigger.cooldownMinutes)
        .put("last_triggered_at", trigger.lastTriggeredAtMillis)
        .put("created_at", trigger.createdAtMillis)

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
                createdAtMillis = json.optLong("created_at", System.currentTimeMillis()).coerceAtLeast(0L),
                conditions = decodeConditions(json) ?: return null
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
        if (trigger.conditions.size > MAX_ADDITIONAL_CONDITIONS) return null
        val conditions = trigger.conditions.map { normalizeConditionOrNull(it) ?: return null }
        return trigger.copy(
            id = id,
            workflowId = workflowId,
            workflowName = workflowName,
            condition = condition,
            conditions = conditions,
            cooldownMinutes = trigger.cooldownMinutes.coerceIn(1, MAX_COOLDOWN_MINUTES),
            lastTriggeredAtMillis = trigger.lastTriggeredAtMillis.coerceAtLeast(0L),
            createdAtMillis = trigger.createdAtMillis.coerceAtLeast(0L)
        )
    }

    private fun encodeConditions(conditions: List<AgentWorkflowCondition>): JSONArray =
        JSONArray().apply {
            conditions.forEach { condition -> put(encodeCondition(condition)) }
        }

    private fun encodeCondition(condition: AgentWorkflowCondition): JSONObject = when (condition) {
        is AgentWorkflowCondition.Text -> JSONObject()
            .put("type", CONDITION_TYPE_TEXT)
            .put("expected", condition.expected)
            .put("match", condition.match.name)
            .put("ignore_case", condition.ignoreCase)

        is AgentWorkflowCondition.PackageName -> JSONObject()
            .put("type", CONDITION_TYPE_PACKAGE_NAME)
            .put("expected", condition.expected)
            .put("match", condition.match.name)
            .put("ignore_case", condition.ignoreCase)

        is AgentWorkflowCondition.DeviceCharging -> JSONObject()
            .put("type", CONDITION_TYPE_DEVICE_CHARGING)
            .put("required", condition.required)

        is AgentWorkflowCondition.BatteryThreshold -> JSONObject()
            .put("type", CONDITION_TYPE_BATTERY_THRESHOLD)
            .put("percent", condition.percent)
            .put("comparison", condition.comparison.name)

        is AgentWorkflowCondition.NetworkAvailable -> JSONObject()
            .put("type", CONDITION_TYPE_NETWORK_AVAILABLE)
            .put("required", condition.required)

        is AgentWorkflowCondition.TimeWindow -> JSONObject()
            .put("type", CONDITION_TYPE_TIME_WINDOW)
            .put("start_minute_of_day", condition.startMinuteOfDay)
            .put("end_minute_of_day", condition.endMinuteOfDay)
    }

    private fun decodeConditions(json: JSONObject): List<AgentWorkflowCondition>? {
        if (!json.has("conditions")) return emptyList()
        val array = json.optJSONArray("conditions") ?: return null
        if (array.length() > MAX_ADDITIONAL_CONDITIONS) return null
        return buildList {
            for (index in 0 until array.length()) {
                val conditionJson = array.optJSONObject(index) ?: return null
                add(decodeCondition(conditionJson) ?: return null)
            }
        }
    }

    private fun decodeCondition(json: JSONObject): AgentWorkflowCondition? = runCatching {
        when (json.optString("type")) {
            CONDITION_TYPE_TEXT -> AgentWorkflowCondition.Text(
                expected = json.optString("expected"),
                match = enumOrNull<AgentWorkflowStringMatch>(json.optString("match"))
                    ?: return null,
                ignoreCase = json.optBoolean("ignore_case", true)
            )

            CONDITION_TYPE_PACKAGE_NAME -> AgentWorkflowCondition.PackageName(
                expected = json.optString("expected"),
                match = enumOrNull<AgentWorkflowStringMatch>(json.optString("match"))
                    ?: return null,
                ignoreCase = json.optBoolean("ignore_case", true)
            )

            CONDITION_TYPE_DEVICE_CHARGING -> AgentWorkflowCondition.DeviceCharging(
                required = json.optBoolean("required", true)
            )

            CONDITION_TYPE_BATTERY_THRESHOLD -> AgentWorkflowCondition.BatteryThreshold(
                percent = json.optInt("percent", -1),
                comparison = enumOrNull<AgentWorkflowBatteryComparison>(json.optString("comparison"))
                    ?: return null
            )

            CONDITION_TYPE_NETWORK_AVAILABLE -> AgentWorkflowCondition.NetworkAvailable(
                required = json.optBoolean("required", true)
            )

            CONDITION_TYPE_TIME_WINDOW -> AgentWorkflowCondition.TimeWindow(
                startMinuteOfDay = json.optInt("start_minute_of_day", -1),
                endMinuteOfDay = json.optInt("end_minute_of_day", -1)
            )

            else -> return null
        }
    }.getOrNull()?.let(::normalizeConditionOrNull)

    private fun normalizeConditionOrNull(
        condition: AgentWorkflowCondition
    ): AgentWorkflowCondition? = when (condition) {
        is AgentWorkflowCondition.Text -> condition.expected.trim().let { expected ->
            if (expected.isBlank() || expected.length > MAX_CONDITION_LENGTH) null
            else condition.copy(expected = expected)
        }

        is AgentWorkflowCondition.PackageName -> condition.expected.trim().let { expected ->
            if (expected.isBlank() || expected.length > MAX_CONDITION_LENGTH) null
            else condition.copy(expected = expected)
        }

        is AgentWorkflowCondition.DeviceCharging,
        is AgentWorkflowCondition.BatteryThreshold,
        is AgentWorkflowCondition.NetworkAvailable,
        is AgentWorkflowCondition.TimeWindow -> condition
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
        const val MAX_ADDITIONAL_CONDITIONS = 32
        const val MAX_COOLDOWN_MINUTES = 7 * 24 * 60
        const val CONDITION_TYPE_TEXT = "text"
        const val CONDITION_TYPE_PACKAGE_NAME = "package_name"
        const val CONDITION_TYPE_DEVICE_CHARGING = "device_charging"
        const val CONDITION_TYPE_BATTERY_THRESHOLD = "battery_threshold"
        const val CONDITION_TYPE_NETWORK_AVAILABLE = "network_available"
        const val CONDITION_TYPE_TIME_WINDOW = "time_window"
    }
}

object AgentWorkflowTriggerEngine {
    const val ACTION_RUN_TRIGGER = "com.signalasi.chat.action.RUN_AGENT_WORKFLOW_TRIGGER"
    const val EXTRA_TRIGGER_ID = "agent_trigger_id"

    @Synchronized
    fun onNotification(context: Context, item: AgentNotificationItem) {
        val now = System.currentTimeMillis()
        val store = AgentWorkflowTriggerStore(context)
        val snapshot = conditionSnapshot(
            context = context,
            text = if (item.sensitiveFlags.isEmpty()) {
                listOf(item.title, item.textPreview)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .take(MAX_NOTIFICATION_TEXT_CHARS)
            } else {
                null
            },
            packageName = item.packageName.take(MAX_NOTIFICATION_PACKAGE_CHARS),
            now = now
        )
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
            .filter { trigger ->
                AgentWorkflowConditionEvaluator.evaluateAll(trigger.conditions, snapshot)
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
        val snapshot = conditionSnapshot(context = context, now = now)
        store.list()
            .filter { it.enabled && it.kind == kind && isReady(it, now) }
            .filter { trigger ->
                AgentWorkflowConditionEvaluator.evaluateAll(trigger.conditions, snapshot)
            }
            .forEach { trigger -> dispatch(context, store, trigger, now) }
    }

    private fun conditionSnapshot(
        context: Context,
        text: String? = null,
        packageName: String? = null,
        now: Long
    ): AgentWorkflowConditionSnapshot {
        val batteryIntent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale).toInt().coerceIn(0, 100)
        } else {
            null
        }
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = when (batteryStatus) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        return AgentWorkflowConditionSnapshot(
            text = text?.takeIf { it.isNotBlank() },
            packageName = packageName?.takeIf { it.isNotBlank() },
            isDeviceCharging = isCharging,
            batteryPercent = batteryPercent,
            isNetworkAvailable = isNetworkAvailable(context),
            minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        )
    }

    private fun isNetworkAvailable(context: Context): Boolean? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching null
        val network = connectivity.activeNetwork ?: return@runCatching false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@runCatching null
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrNull()

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
