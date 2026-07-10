package com.signalasi.chat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

enum class AgentWorkflowScheduleKind {
    DAILY,
    INTERVAL
}

data class AgentWorkflowSchedule(
    val id: String = UUID.randomUUID().toString(),
    val workflowId: String,
    val workflowName: String,
    val kind: AgentWorkflowScheduleKind,
    val hour: Int = -1,
    val minute: Int = -1,
    val intervalMinutes: Int = 0,
    val enabled: Boolean = true,
    val nextRunAtMillis: Long = 0L,
    val lastRunAtMillis: Long = 0L,
    val createdAtMillis: Long = System.currentTimeMillis()
)

class AgentWorkflowScheduleStore(context: Context) {
    private val preferences = AgentEncryptedPreferences(context, PREFS)

    fun list(): List<AgentWorkflowSchedule> = load().sortedBy { it.nextRunAtMillis }

    fun findById(id: String): AgentWorkflowSchedule? = load().firstOrNull { it.id == id }

    fun findByWorkflowName(name: String): AgentWorkflowSchedule? {
        val clean = name.trim().lowercase()
        return load().firstOrNull { it.workflowName.lowercase() == clean }
            ?: load().firstOrNull { it.workflowName.lowercase().contains(clean) }
    }

    fun upsert(schedule: AgentWorkflowSchedule) {
        save(
            load()
                .filterNot { it.id == schedule.id || it.workflowId == schedule.workflowId }
                .plus(schedule)
                .sortedBy { it.createdAtMillis }
                .takeLast(MAX_ITEMS)
        )
    }

    fun delete(id: String): Int {
        val items = load()
        val kept = items.filterNot { it.id == id }
        if (kept.size != items.size) save(kept)
        return items.size - kept.size
    }

    fun clear() = preferences.clear()

    private fun load(): List<AgentWorkflowSchedule> {
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

    private fun save(items: List<AgentWorkflowSchedule>) {
        val array = JSONArray()
        items.forEach { schedule ->
            array.put(
                JSONObject()
                    .put("id", schedule.id)
                    .put("workflow_id", schedule.workflowId)
                    .put("workflow_name", schedule.workflowName)
                    .put("kind", schedule.kind.name)
                    .put("hour", schedule.hour)
                    .put("minute", schedule.minute)
                    .put("interval_minutes", schedule.intervalMinutes)
                    .put("enabled", schedule.enabled)
                    .put("next_run_at", schedule.nextRunAtMillis)
                    .put("last_run_at", schedule.lastRunAtMillis)
                    .put("created_at", schedule.createdAtMillis)
            )
        }
        preferences.writeString(KEY_ITEMS, array.toString())
    }

    private fun decode(json: JSONObject): AgentWorkflowSchedule? {
        val id = json.optString("id")
        val workflowId = json.optString("workflow_id")
        val workflowName = json.optString("workflow_name").take(80)
        if (id.isBlank() || workflowId.isBlank() || workflowName.isBlank()) return null
        return AgentWorkflowSchedule(
            id = id,
            workflowId = workflowId,
            workflowName = workflowName,
            kind = enumOrDefault(json.optString("kind"), AgentWorkflowScheduleKind.DAILY),
            hour = json.optInt("hour", -1),
            minute = json.optInt("minute", -1),
            intervalMinutes = json.optInt("interval_minutes").coerceIn(0, MAX_INTERVAL_MINUTES),
            enabled = json.optBoolean("enabled", true),
            nextRunAtMillis = json.optLong("next_run_at"),
            lastRunAtMillis = json.optLong("last_run_at"),
            createdAtMillis = json.optLong("created_at", System.currentTimeMillis())
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrElse { default }

    private companion object {
        const val PREFS = "signalasi_agent_workflow_schedules"
        const val KEY_ITEMS = "items"
        const val MAX_ITEMS = 100
        const val MAX_INTERVAL_MINUTES = 7 * 24 * 60
    }
}

object AgentWorkflowScheduler {
    const val ACTION_RUN_SCHEDULE = "com.signalasi.chat.action.RUN_AGENT_WORKFLOW_SCHEDULE"
    const val EXTRA_SCHEDULE_ID = "agent_schedule_id"
    private const val WINDOW_MILLIS = 5L * 60L * 1000L
    private const val MIN_INTERVAL_MINUTES = 15
    private const val MAX_INTERVAL_MINUTES = 7 * 24 * 60

    fun scheduleDaily(context: Context, workflow: AgentWorkflow, hour: Int, minute: Int): AgentWorkflowSchedule {
        require(hour in 0..23 && minute in 0..59) { "Daily workflow time is invalid" }
        val schedule = AgentWorkflowSchedule(
            workflowId = workflow.id,
            workflowName = workflow.name,
            kind = AgentWorkflowScheduleKind.DAILY,
            hour = hour,
            minute = minute,
            nextRunAtMillis = nextDailyRun(hour, minute, System.currentTimeMillis())
        )
        replaceSchedule(context, schedule)
        register(context, schedule)
        return schedule
    }

    fun scheduleInterval(context: Context, workflow: AgentWorkflow, intervalMinutes: Int): AgentWorkflowSchedule {
        require(intervalMinutes in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES) {
            "Workflow interval must be between 15 minutes and 7 days"
        }
        val schedule = AgentWorkflowSchedule(
            workflowId = workflow.id,
            workflowName = workflow.name,
            kind = AgentWorkflowScheduleKind.INTERVAL,
            intervalMinutes = intervalMinutes,
            nextRunAtMillis = System.currentTimeMillis() + intervalMinutes * 60_000L
        )
        replaceSchedule(context, schedule)
        register(context, schedule)
        return schedule
    }

    fun cancel(context: Context, schedule: AgentWorkflowSchedule) {
        alarmManager(context).cancel(pendingIntent(context, schedule.id))
        AgentWorkflowScheduleStore(context).delete(schedule.id)
    }

    fun cancelAll(context: Context) {
        val store = AgentWorkflowScheduleStore(context)
        store.list().forEach { schedule -> alarmManager(context).cancel(pendingIntent(context, schedule.id)) }
        store.clear()
    }

    fun restoreAll(context: Context) {
        val store = AgentWorkflowScheduleStore(context)
        store.list().filter { it.enabled }.forEach { schedule ->
            val now = System.currentTimeMillis()
            val nextRunAt = when {
                schedule.kind == AgentWorkflowScheduleKind.DAILY -> nextDailyRun(schedule.hour, schedule.minute, now)
                schedule.nextRunAtMillis > now -> schedule.nextRunAtMillis
                else -> nextRun(schedule, now)
            }
            val next = schedule.copy(nextRunAtMillis = nextRunAt)
            store.upsert(next)
            register(context, next)
        }
    }

    fun handleTrigger(context: Context, scheduleId: String) {
        val store = AgentWorkflowScheduleStore(context)
        val schedule = store.findById(scheduleId)?.takeIf { it.enabled } ?: return
        val now = System.currentTimeMillis()
        val next = schedule.copy(
            lastRunAtMillis = now,
            nextRunAtMillis = nextRun(schedule, now)
        )
        store.upsert(next)
        register(context, next)
        val serviceIntent = Intent(context, MessageService::class.java)
            .setAction(ACTION_RUN_SCHEDULE)
            .putExtra(EXTRA_SCHEDULE_ID, schedule.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun register(context: Context, schedule: AgentWorkflowSchedule) {
        val triggerAt = schedule.nextRunAtMillis.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        alarmManager(context).setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            WINDOW_MILLIS,
            pendingIntent(context, schedule.id)
        )
    }

    private fun replaceSchedule(context: Context, schedule: AgentWorkflowSchedule) {
        val store = AgentWorkflowScheduleStore(context)
        store.list().firstOrNull { it.workflowId == schedule.workflowId }?.let { existing ->
            alarmManager(context).cancel(pendingIntent(context, existing.id))
        }
        store.upsert(schedule)
    }

    private fun pendingIntent(context: Context, scheduleId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        scheduleId.hashCode(),
        Intent(context, AgentWorkflowAlarmReceiver::class.java)
            .setAction(ACTION_RUN_SCHEDULE)
            .putExtra(EXTRA_SCHEDULE_ID, scheduleId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun alarmManager(context: Context): AlarmManager =
        context.applicationContext.getSystemService(AlarmManager::class.java)

    private fun nextRun(schedule: AgentWorkflowSchedule, now: Long): Long = when (schedule.kind) {
        AgentWorkflowScheduleKind.DAILY -> nextDailyRun(schedule.hour, schedule.minute, now)
        AgentWorkflowScheduleKind.INTERVAL -> now + schedule.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES) * 60_000L
    }

    private fun nextDailyRun(hour: Int, minute: Int, now: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }
}

class AgentWorkflowAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AgentWorkflowScheduler.ACTION_RUN_SCHEDULE) return
        val scheduleId = intent.getStringExtra(AgentWorkflowScheduler.EXTRA_SCHEDULE_ID).orEmpty()
        if (scheduleId.isNotBlank()) AgentWorkflowScheduler.handleTrigger(context, scheduleId)
    }
}
