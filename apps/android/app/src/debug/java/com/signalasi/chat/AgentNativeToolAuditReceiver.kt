package com.signalasi.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Debug-only ADB entry point for non-destructive native-tool audits on a real device. */
class AgentNativeToolAuditReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        Thread {
            try {
                val toolId = intent.getStringExtra("tool_id").orEmpty()
                val encodedGoal = intent.getStringExtra("goal_base64").orEmpty()
                val report = when {
                    encodedGoal.isNotBlank() -> runGoal(context.applicationContext, encodedGoal)
                    toolId.isNotBlank() -> runSingleTool(
                        context.applicationContext,
                        toolId,
                        intent.getStringExtra("input_base64").orEmpty()
                    )
                    else -> runAudit(context.applicationContext)
                }
                val fileName = when {
                    encodedGoal.isNotBlank() -> GOAL_REPORT_FILE
                    toolId.isNotBlank() -> SINGLE_REPORT_FILE
                    else -> REPORT_FILE
                }
                File(context.filesDir, fileName).writeText(report.toString(2))
                Log.i(TAG, "Native tool audit complete: ${toolId.ifBlank { if (encodedGoal.isBlank()) "catalog" else "goal" }}")
            } catch (error: Throwable) {
                Log.e(TAG, "Native tool audit failed", error)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun runGoal(context: Context, encodedGoal: String): JSONObject {
        val goal = String(Base64.decode(encodedGoal, Base64.NO_WRAP), Charsets.UTF_8)
        val started = System.currentTimeMillis()
        val state = MobileNativeAgent(
            context,
            sessionStore = SharedPreferencesAgentSessionStore(context, "debug-native-goal")
        ).submitGoal(
            goal = goal,
            conversationContext = AgentConversationContext("debug-native-goal", "Debug native goal", emptyList(), true),
            turnId = UUID.randomUUID().toString()
        )
        return JSONObject()
            .put("goal", goal)
            .put("phase", state.phase.name)
            .put("tool_id", state.lastActionResult?.metadata?.get("native_tool_id").orEmpty())
            .put("message", state.lastActionResult?.message.orEmpty())
            .put("elapsed_ms", System.currentTimeMillis() - started)
    }

    private fun runSingleTool(context: Context, toolId: String, encodedInput: String): JSONObject {
        val registry = AgentPhoneNativeToolCatalog.defaultRegistry(
            context = context,
            screenProvider = { ScreenContext(foregroundApp = "SignalASI", pageTitle = "Agent") }
        )
        val descriptor = registry.lookup(toolId)?.descriptor
            ?: return JSONObject().put("tool_id", toolId).put("error", "unknown_tool")
        val json = if (encodedInput.isBlank()) JSONObject() else JSONObject(
            String(Base64.decode(encodedInput, Base64.NO_WRAP), Charsets.UTF_8)
        )
        val input = json.keys().asSequence().associateWith { key -> nativeJsonValue(json.opt(key)) }
        val started = System.currentTimeMillis()
        val result = registry.invoke(
            id = toolId,
            input = input,
            context = AgentNativeToolInvocationContext(
                sessionId = "native-audit",
                conversationId = "native-audit",
                turnId = UUID.randomUUID().toString(),
                callerId = "signalasi.debug.native_audit",
                idempotencyKey = "audit-$toolId-${UUID.randomUUID()}",
                grantedPermissions = descriptor.requiredPermissions.mapTo(linkedSetOf()) { it.id },
                grantedConsents = descriptor.requiredConsents.mapTo(linkedSetOf()) { it.id },
                attributes = mapOf("execution_authority" to "signalasi-phone")
            )
        )
        return JSONObject(result.toJson())
            .put("tool_id", toolId)
            .put("elapsed_ms", System.currentTimeMillis() - started)
    }

    private fun nativeJsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { key -> nativeJsonValue(value.opt(key)) }
        is JSONArray -> (0 until value.length()).map { index -> nativeJsonValue(value.opt(index)) }
        else -> value
    }

    private fun runAudit(context: Context): JSONObject {
        val registry = AgentPhoneNativeToolCatalog.defaultRegistry(
            context = context,
            screenProvider = { ScreenContext(foregroundApp = "SignalASI", pageTitle = "Agent") }
        )
        val started = System.currentTimeMillis()
        val rows = JSONArray()
        var executed = 0
        var succeeded = 0
        var protected = 0
        var unavailable = 0
        var skipped = 0

        registry.descriptors().sortedBy { it.id }.forEach { descriptor ->
            val row = JSONObject()
                .put("tool_id", descriptor.id)
                .put("risk", descriptor.risk.wireValue)
                .put("location", descriptor.location.wireValue)
                .put("availability", descriptor.availability.status.wireValue)
            val input = safeInput(descriptor.id, started)
            val safe = descriptor.id in SAFE_REAL_DEVICE_TOOLS
            when {
                !safe -> {
                    protected += 1
                    row.put("test_status", "protected_not_executed")
                }
                input == null -> {
                    skipped += 1
                    row.put("test_status", "fixture_unavailable")
                }
                else -> {
                    val invocationStarted = System.currentTimeMillis()
                    val result = registry.invoke(
                        id = descriptor.id,
                        input = input,
                        context = AgentNativeToolInvocationContext(
                            sessionId = "native-audit",
                            conversationId = "native-audit",
                            turnId = UUID.randomUUID().toString(),
                            callerId = "signalasi.debug.native_audit",
                            idempotencyKey = "audit-${descriptor.id}-${UUID.randomUUID()}",
                            grantedPermissions = descriptor.requiredPermissions.mapTo(linkedSetOf()) { it.id },
                            grantedConsents = descriptor.requiredConsents.mapTo(linkedSetOf()) { it.id },
                            attributes = mapOf("execution_authority" to "signalasi-phone")
                        )
                    )
                    val elapsed = System.currentTimeMillis() - invocationStarted
                    executed += 1
                    if (result.isSuccess) succeeded += 1
                    if (result.error?.code == "tool_unavailable" || result.error?.code == "missing_permissions") unavailable += 1
                    row.put("test_status", if (result.isSuccess) "passed" else "failed")
                        .put("elapsed_ms", elapsed)
                        .put("result_status", result.status.wireValue)
                        .put("message", result.message.take(500))
                        .put("error_code", result.error?.code.orEmpty())
                        .put("error_message", result.error?.message.orEmpty().take(500))
                        .put("error_details", JSONObject(result.error?.details.orEmpty()))
                        .put("output", JSONObject(result.output))
                }
            }
            rows.put(row)
        }
        return JSONObject()
            .put("device_epoch_ms", System.currentTimeMillis())
            .put("catalog_size", registry.descriptors().size)
            .put("executed", executed)
            .put("succeeded", succeeded)
            .put("failed", executed - succeeded)
            .put("unavailable", unavailable)
            .put("protected", protected)
            .put("skipped", skipped)
            .put("elapsed_ms", System.currentTimeMillis() - started)
            .put("results", rows)
    }

    private fun safeInput(id: String, now: Long): AgentNativeJsonObject? = when (id) {
        AgentHardwareNativeTools.BATTERY_STATUS,
        AgentHardwareNativeTools.POWER_STATUS,
        AgentHardwareNativeTools.STORAGE_STATUS,
        AgentHardwareNativeTools.NETWORK_STATUS,
        AgentHardwareNativeTools.BLUETOOTH_STATUS,
        AgentHardwareNativeTools.NFC_STATUS,
        AgentAndroidSystemNativeTools.TELEPHONY_STATUS,
        AgentAndroidSystemNativeTools.TELEPHONY_CALL_STATE,
        AgentAndroidSystemNativeTools.CALENDARS_LIST,
        AgentAndroidSystemNativeTools.WIFI_STATUS,
        AgentAndroidSystemNativeTools.WIFI_SCAN_START,
        AgentAndroidSystemNativeTools.AUDIO_STATUS,
        AgentAndroidSystemNativeTools.BIOMETRIC_STATUS,
        AgentAndroidSystemNativeTools.VPN_STATUS,
        AgentAndroidSystemNativeTools.DEVICE_POLICY_STATUS -> emptyMap()

        AgentHardwareNativeTools.LOCATION_FOREGROUND_READ -> mapOf("timeout_ms" to 5_000)
        AgentHardwareNativeTools.SENSORS_LIST -> mapOf("limit" to 16)
        AgentHardwareNativeTools.SENSOR_SAMPLE -> mapOf("type" to "accelerometer", "timeout_ms" to 3_000)
        AgentHardwareNativeTools.BLUETOOTH_DISCOVERY_FOREGROUND -> mapOf("timeout_ms" to 5_000, "limit" to 8)
        AgentHardwareNativeTools.INSTALLED_APPS_LIST -> mapOf("query" to "", "limit" to 20)
        AgentHardwareNativeTools.PACKAGE_DETAIL -> mapOf("package_name" to contextPackageName)
        AgentAndroidSystemNativeTools.TELEPHONY_CALL_STATE_OBSERVE -> mapOf("timeout_ms" to 1_000)
        AgentAndroidSystemNativeTools.SMS_LIST -> mapOf("limit" to 3)
        AgentAndroidSystemNativeTools.CONTACTS_SEARCH -> mapOf("query" to "", "limit" to 3)
        AgentAndroidSystemNativeTools.CALENDAR_EVENTS_QUERY -> mapOf(
            "start_epoch_ms" to now - 86_400_000L,
            "end_epoch_ms" to now + 604_800_000L,
            "limit" to 5
        )
        AgentAndroidSystemNativeTools.WIFI_SCAN_RESULTS -> mapOf("limit" to 8)
        AgentAndroidSystemNativeTools.DOWNLOAD_QUERY -> mapOf("download_id" to Long.MAX_VALUE)
        AgentWebMediaNativeTools.WEB_SEARCH -> mapOf("query" to "SignalASI", "max_results" to 2, "timeout_ms" to 10_000)
        AgentWebMediaNativeTools.WEB_OPEN,
        AgentWebMediaNativeTools.BROWSER_RENDER,
        AgentWebMediaNativeTools.WEB_FETCH -> mapOf("url" to "https://example.com", "max_bytes" to 65_536, "timeout_ms" to 5_000)
        AgentWebMediaNativeTools.WEB_HEAD -> mapOf("url" to "https://example.com", "timeout_ms" to 5_000)
        AgentWebMediaNativeTools.HTTP_REQUEST -> mapOf("url" to "https://example.com", "method" to "HEAD", "timeout_ms" to 5_000)
        AgentWebMediaNativeTools.CONTENT_EXTRACT -> mapOf("content" to "<h1>SignalASI</h1><p>Native audit</p>")
        else -> null
    }

    companion object {
        private const val ACTION = "com.signalasi.chat.DEBUG_NATIVE_TOOL_AUDIT"
        private const val REPORT_FILE = "native-tool-audit.json"
        private const val SINGLE_REPORT_FILE = "native-tool-single.json"
        private const val GOAL_REPORT_FILE = "native-goal-audit.json"
        private const val TAG = "SignalASI-NativeAudit"
        private const val contextPackageName = "com.signalasi.chat"

        private val SAFE_REAL_DEVICE_TOOLS = setOf(
            AgentHardwareNativeTools.BATTERY_STATUS,
            AgentHardwareNativeTools.POWER_STATUS,
            AgentHardwareNativeTools.STORAGE_STATUS,
            AgentHardwareNativeTools.NETWORK_STATUS,
            AgentHardwareNativeTools.LOCATION_FOREGROUND_READ,
            AgentHardwareNativeTools.SENSORS_LIST,
            AgentHardwareNativeTools.SENSOR_SAMPLE,
            AgentHardwareNativeTools.BLUETOOTH_STATUS,
            AgentHardwareNativeTools.BLUETOOTH_DISCOVERY_FOREGROUND,
            AgentHardwareNativeTools.NFC_STATUS,
            AgentHardwareNativeTools.INSTALLED_APPS_LIST,
            AgentHardwareNativeTools.PACKAGE_DETAIL,
            AgentAndroidSystemNativeTools.TELEPHONY_STATUS,
            AgentAndroidSystemNativeTools.TELEPHONY_CALL_STATE,
            AgentAndroidSystemNativeTools.TELEPHONY_CALL_STATE_OBSERVE,
            AgentAndroidSystemNativeTools.SMS_LIST,
            AgentAndroidSystemNativeTools.CONTACTS_SEARCH,
            AgentAndroidSystemNativeTools.CALENDARS_LIST,
            AgentAndroidSystemNativeTools.CALENDAR_EVENTS_QUERY,
            AgentAndroidSystemNativeTools.WIFI_STATUS,
            AgentAndroidSystemNativeTools.WIFI_SCAN_RESULTS,
            AgentAndroidSystemNativeTools.WIFI_SCAN_START,
            AgentAndroidSystemNativeTools.AUDIO_STATUS,
            AgentAndroidSystemNativeTools.DOWNLOAD_QUERY,
            AgentAndroidSystemNativeTools.BIOMETRIC_STATUS,
            AgentAndroidSystemNativeTools.VPN_STATUS,
            AgentAndroidSystemNativeTools.DEVICE_POLICY_STATUS,
            AgentWebMediaNativeTools.WEB_SEARCH,
            AgentWebMediaNativeTools.WEB_OPEN,
            AgentWebMediaNativeTools.BROWSER_RENDER,
            AgentWebMediaNativeTools.CONTENT_EXTRACT,
            AgentWebMediaNativeTools.HTTP_REQUEST,
            AgentWebMediaNativeTools.WEB_HEAD,
            AgentWebMediaNativeTools.WEB_FETCH
        )
    }
}
