package com.signalasi.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end acceptance for protected phone, paired Desktop, and smart-home resources. */
@RunWith(AndroidJUnit4::class)
class AgentCrossResourceDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val reportFile = File(
        requireNotNull(context.getExternalFilesDir("cross-resource-tests")),
        "results.json"
    )

    @Test
    fun executesCrossResourceProductionPaths() {
        val report = JSONObject()
            .put("device", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("started_at_epoch_ms", System.currentTimeMillis())
        val originalHomeAssistant = HomeAssistantSettingsStore.load(context)
        try {
            startSignalASI()
            report.put("notification", verifyNotificationReadAndReply())
            report.put("home_assistant", verifyHomeAssistant())
            report.put("desktop", verifyPairedDesktopTools())
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false)
                .put("error", error.message.orEmpty().take(2_000))
            throw error
        } finally {
            HomeAssistantSettingsStore.save(context, originalHomeAssistant)
            report.put("finished_at_epoch_ms", System.currentTimeMillis())
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(report.toString())
            println("SIGNALASI_CROSS_RESOURCE_REPORT=${reportFile.absolutePath}")
        }
    }

    private fun startSignalASI() {
        context.startForegroundService(Intent(context, MessageService::class.java))
        NotificationListenerService.requestRebind(
            android.content.ComponentName(context, SignalASINotificationListenerService::class.java)
        )
        waitUntil("notification listener", NOTIFICATION_TIMEOUT_MILLIS) {
            SignalASINotificationListenerService.currentContext().hasAccess
        }
    }

    private fun verifyNotificationReadAndReply(): JSONObject {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val marker = "SignalASI notification acceptance ${UUID.randomUUID()}"
        val replyText = "SignalASI reply ${UUID.randomUUID()}"
        val receivedReply = AtomicReference<String>()
        val replyLatch = CountDownLatch(1)
        val action = "${context.packageName}.CROSS_RESOURCE_REPLY.${UUID.randomUUID()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val value = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REPLY_RESULT_KEY)
                    ?.toString()
                    .orEmpty()
                receivedReply.set(value)
                replyLatch.countDown()
            }
        }
        registerReceiver(receiver, IntentFilter(action))
        val notificationId = (System.currentTimeMillis() and 0x7fffffff).toInt()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "SignalASI acceptance",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            val replyIntent = Intent(action).setPackage(context.packageName)
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val remoteInput = RemoteInput.Builder(REPLY_RESULT_KEY)
                .setLabel("Reply")
                .build()
            val replyAction = Notification.Action.Builder(
                android.R.drawable.ic_menu_send,
                "Reply",
                replyPendingIntent
            ).addRemoteInput(remoteInput).build()
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            notificationManager.notify(
                notificationId,
                builder
                    .setSmallIcon(R.drawable.ic_settings_notification)
                    .setContentTitle(marker)
                    .setContentText("Cross-resource notification")
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .addAction(replyAction)
                    .build()
            )

            var observedKey = ""
            waitUntil("posted acceptance notification", NOTIFICATION_TIMEOUT_MILLIS) {
                val item = SignalASINotificationListenerService.currentContext(12).items.firstOrNull {
                    it.title == marker && it.packageName == context.packageName && it.canReply
                }
                if (item != null) observedKey = item.key
                observedKey.isNotBlank()
            }

            val registry = registry()
            val listed = invoke(
                registry,
                AgentNotificationNativeTools.NOTIFICATIONS_LIST,
                mapOf("limit" to 12, "reply_capable_only" to true),
                workspaceId = ""
            )
            val listedItems = listed.output["notifications"] as? List<*> ?: emptyList<Any?>()
            val listedAcceptance = listedItems.filterIsInstance<Map<*, *>>().firstOrNull {
                it["notification_key"] == observedKey && it["title"] == marker && it["redacted"] == false
            }
            assertNotNull("The live notification was not returned by the typed read tool", listedAcceptance)

            val replied = invoke(
                registry,
                AgentNotificationNativeTools.NOTIFICATION_REPLY,
                mapOf("notification_key" to observedKey, "reply_text" to replyText),
                workspaceId = ""
            )
            assertEquals(true, replied.output["dispatch_accepted"])
            assertTrue("RemoteInput reply was not delivered", replyLatch.await(10, TimeUnit.SECONDS))
            assertEquals(replyText, receivedReply.get())

            notificationManager.cancel(notificationId)
            waitUntil("notification removal", NOTIFICATION_TIMEOUT_MILLIS) {
                SignalASINotificationListenerService.currentContext(12).items.none { it.key == observedKey }
            }
            val stale = invokeAllowingFailure(
                registry,
                AgentNotificationNativeTools.NOTIFICATION_REPLY,
                mapOf("notification_key" to observedKey, "reply_text" to "stale reply"),
                workspaceId = ""
            )
            assertEquals(AgentNativeToolResultStatus.FAILED, stale.status)
            assertEquals("notification_stale", stale.error?.code)

            return JSONObject()
                .put("read_succeeded", true)
                .put("reply_dispatched", true)
                .put("remote_input_received", true)
                .put("stale_target_rejected", true)
                .put("notification_key_sha256", AgentNativeJsonCodec.sha256(observedKey))
        } finally {
            notificationManager.cancel(notificationId)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun verifyHomeAssistant(): JSONObject {
        val port = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString(HOME_ASSISTANT_PORT_ARGUMENT)
            .orEmpty()
            .toIntOrNull()
            ?: DEFAULT_HOME_ASSISTANT_PORT
        HomeAssistantSettingsStore.save(
            context,
            HomeAssistantSettings(
                enabled = true,
                baseUrl = "http://127.0.0.1:$port",
                accessToken = HOME_ASSISTANT_TOKEN,
                defaultEntityId = HOME_ASSISTANT_ENTITY
            )
        )

        val connected = HomeAssistantDeviceClient.connectionStatus(context)
        assertTrue(connected.message, connected.handled && connected.success)
        val listed = HomeAssistantDeviceClient.listEntities(context)
        assertTrue(listed.message, listed.handled && listed.success)
        assertTrue(listed.entities.any { it.entityId == HOME_ASSISTANT_ENTITY && it.state == "off" })
        val controlled = HomeAssistantDeviceClient.control(context, "turn on $HOME_ASSISTANT_ENTITY")
        assertTrue(controlled.message, controlled.handled && controlled.success)
        val readBack = HomeAssistantDeviceClient.readEntity(context, HOME_ASSISTANT_ENTITY)
        assertTrue(readBack.message, readBack.handled && readBack.success)
        assertEquals("on", readBack.entities.single { it.entityId == HOME_ASSISTANT_ENTITY }.state)

        return JSONObject()
            .put("connection_succeeded", true)
            .put("entity_list_succeeded", true)
            .put("service_call_succeeded", true)
            .put("post_action_state", "on")
            .put("entity_id", HOME_ASSISTANT_ENTITY)
    }

    private fun verifyPairedDesktopTools(): JSONObject {
        waitUntil("secure paired Desktop", DESKTOP_CONNECTION_TIMEOUT_MILLIS) {
            SignalASIMqttClient.isConnected() && SignalASIMqttClient.isSecureReady()
        }
        val registry = registry()
        waitUntil("Desktop capability manifest", DESKTOP_CONNECTION_TIMEOUT_MILLIS) {
            registry.descriptors().firstOrNull { it.id == AgentDesktopRemoteNativeTools.SYSTEM_STATUS }
                ?.availability?.status == AgentNativeToolAvailabilityStatus.AVAILABLE
        }
        val workspaceId = "cross-resource-${UUID.randomUUID()}"

        val system = invoke(registry, AgentDesktopRemoteNativeTools.SYSTEM_STATUS, emptyMap(), workspaceId)
        assertTrue((system.output["logical_cpu_count"] as? Number)?.toInt()?.let { it > 0 } == true)
        val processes = invoke(
            registry,
            AgentDesktopRemoteNativeTools.PROCESS_LIST,
            mapOf("max_entries" to 8),
            workspaceId
        )
        assertTrue((processes.output["count"] as? Number)?.toInt()?.let { it > 0 } == true)

        val fileText = "SignalASI paired Desktop acceptance"
        val written = invoke(
            registry,
            AgentDesktopRemoteNativeTools.FILE_WRITE_TEXT,
            mapOf("path" to "acceptance.txt", "content" to fileText, "mode" to "create"),
            workspaceId
        )
        assertEquals("passed", written.verification?.status?.wireValue)
        val read = invoke(
            registry,
            AgentDesktopRemoteNativeTools.FILE_READ_TEXT,
            mapOf("path" to "acceptance.txt", "max_bytes" to 4_096),
            workspaceId
        )
        assertEquals(fileText, read.output["text"])
        val hashed = invoke(
            registry,
            AgentDesktopRemoteNativeTools.FILE_SHA256,
            mapOf("path" to "acceptance.txt"),
            workspaceId
        )
        assertEquals(read.output["sha256"], hashed.output["sha256"])

        val documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body><w:p><w:r><w:t>SignalASI paired Office acceptance</w:t></w:r></w:p></w:body></w:document>"
        val pythonScript = "import zipfile;z=zipfile.ZipFile('report.docx','w');" +
            "z.writestr('word/document.xml',${pythonString(documentXml)});z.close();print('created')"
        val terminal = invoke(
            registry,
            AgentDesktopRemoteNativeTools.TERMINAL_RUN,
            mapOf("argv" to listOf("python", "-c", pythonScript), "cwd" to ".", "timeout_seconds" to 30),
            workspaceId
        )
        assertEquals(0, (terminal.output["exit_code"] as? Number)?.toInt())
        assertTrue(terminal.output["stdout"].toString().contains("created"))

        val inspected = invoke(
            registry,
            AgentDesktopRemoteNativeTools.OFFICE_INSPECT,
            mapOf("path" to "report.docx", "max_items" to 20),
            workspaceId
        )
        assertEquals("word", inspected.output["document_type"])
        assertTrue(inspected.output["text_items"].toString().contains("SignalASI paired Office acceptance"))
        val converted = invoke(
            registry,
            AgentDesktopRemoteNativeTools.OFFICE_CONVERT,
            mapOf("path" to "report.docx", "output_format" to "txt", "output_path" to "outputs/report.txt"),
            workspaceId
        )
        assertEquals("text/plain", converted.output["mime_type"])
        assertEquals("passed", converted.verification?.status?.wireValue)

        return JSONObject()
            .put("secure_link", true)
            .put("windows_status", true)
            .put("process_inventory", true)
            .put("workspace_write_read_hash", true)
            .put("terminal_execution", true)
            .put("office_inspection", true)
            .put("office_conversion", true)
            .put("remote_verification", true)
            .put("workspace_id", workspaceId)
    }

    private fun registry(): AgentNativeToolRegistry = AgentPhoneNativeToolCatalog.defaultRegistry(
        context = context,
        screenProvider = {
            ScreenContext(
                foregroundApp = context.packageName,
                activityName = MainActivity::class.java.name,
                pageTitle = "SignalASI cross-resource acceptance"
            )
        }
    )

    private fun invoke(
        registry: AgentNativeToolRegistry,
        toolId: String,
        input: AgentNativeJsonObject,
        workspaceId: String
    ): AgentNativeToolResult {
        val result = invokeAllowingFailure(registry, toolId, input, workspaceId)
        assertTrue(
            "$toolId failed: ${result.error?.code} ${result.error?.message}",
            result.isSuccess
        )
        return result
    }

    private fun invokeAllowingFailure(
        registry: AgentNativeToolRegistry,
        toolId: String,
        input: AgentNativeJsonObject,
        workspaceId: String
    ): AgentNativeToolResult {
        val definition = requireNotNull(registry.lookup(toolId))
        val invocationId = "acceptance-${UUID.randomUUID()}"
        return registry.invoke(
            toolId,
            input,
            AgentNativeToolInvocationContext(
                invocationId = invocationId,
                sessionId = "cross-resource-session",
                conversationId = "cross-resource-conversation",
                turnId = "cross-resource-turn",
                idempotencyKey = invocationId,
                grantedPermissions = definition.descriptor.requiredPermissions.mapTo(linkedSetOf()) { it.id },
                grantedConsents = definition.descriptor.requiredConsents.mapTo(linkedSetOf()) { it.id },
                attributes = buildMap {
                    put("task_id", "cross-resource-task")
                    put("step_id", "cross-resource-step")
                    put("confirmation_id", "cross-resource-confirmation")
                    if (workspaceId.isNotBlank()) put("workspace_id", workspaceId)
                }
            )
        )
    }

    private fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun pythonString(value: String): String = "'" + value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "'"

    private fun waitUntil(label: String, timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(250)
        }
        assertFalse("Timed out waiting for $label", true)
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "signalasi_cross_resource_acceptance"
        private const val REPLY_RESULT_KEY = "signalasi_acceptance_reply"
        private const val HOME_ASSISTANT_PORT_ARGUMENT = "signalasi_home_assistant_port"
        private const val DEFAULT_HOME_ASSISTANT_PORT = 18_123
        private const val HOME_ASSISTANT_TOKEN = "signalasi-cross-resource-acceptance"
        private const val HOME_ASSISTANT_ENTITY = "light.qa_lamp"
        private const val NOTIFICATION_TIMEOUT_MILLIS = 20_000L
        private const val DESKTOP_CONNECTION_TIMEOUT_MILLIS = 90_000L
    }
}
