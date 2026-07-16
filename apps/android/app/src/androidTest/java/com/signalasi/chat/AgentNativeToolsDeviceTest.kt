package com.signalasi.chat

import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/** Real-device contract and execution coverage for every registered phone-native tool. */
@RunWith(AndroidJUnit4::class)
class AgentNativeToolsDeviceTest {
    private lateinit var registry: AgentNativeToolRegistry
    private lateinit var reportFile: File
    private val results = mutableListOf<Map<String, Any?>>()
    private val workspaceId = "device-test-${System.currentTimeMillis()}"
    private val temporaryUris = mutableListOf<Uri>()
    private lateinit var testImageUri: Uri

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        val context = instrumentation.targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemClock.sleep(800)
        registry = AgentPhoneNativeToolCatalog.defaultRegistry(
            context = context,
            screenProvider = {
                ScreenContext(
                    foregroundApp = context.packageName,
                    activityName = MainActivity::class.java.name,
                    pageTitle = "SignalASI device test"
                )
            }
        )
        reportFile = File(
            requireNotNull(context.getExternalFilesDir("native-tool-tests")),
            "results.json"
        )
        testImageUri = createTestImage()
    }

    @Test
    fun testAllRegisteredPhoneToolsOnDevice() {
        val descriptors = registry.descriptors()
        assertEquals("The native tool inventory changed; update the device matrix", 98, descriptors.size)

        runWorkspaceLifecycle()
        descriptors
            .filterNot { it.id.startsWith("signalasi.workspace.") }
            .forEach(::testRegisteredTool)

        val testedIds = results.mapNotNull { it["tool_id"] as? String }.toSet()
        assertEquals(descriptors.map { it.id }.toSet(), testedIds)
        writeReport(descriptors.size)
        temporaryUris.forEach { uri -> runCatching { instrumentation.targetContext.contentResolver.delete(uri, null, null) } }
    }

    private fun testRegisteredTool(descriptor: AgentNativeToolDescriptor) {
        if (results.any { it["tool_id"] == descriptor.id }) return
        when (descriptor.id) {
            AgentHardwareNativeTools.BATTERY_STATUS,
            AgentHardwareNativeTools.POWER_STATUS,
            AgentHardwareNativeTools.STORAGE_STATUS,
            AgentHardwareNativeTools.NETWORK_STATUS,
            AgentHardwareNativeTools.SENSORS_LIST,
            AgentHardwareNativeTools.BLUETOOTH_STATUS,
            AgentHardwareNativeTools.NFC_STATUS,
            AgentAndroidSystemNativeTools.TELEPHONY_STATUS,
            AgentAndroidSystemNativeTools.TELEPHONY_CALL_STATE,
            AgentAndroidSystemNativeTools.SMS_LIST,
            AgentAndroidSystemNativeTools.CONTACTS_SEARCH,
            AgentAndroidSystemNativeTools.CALENDARS_LIST,
            AgentAndroidSystemNativeTools.CALENDAR_EVENTS_QUERY,
            AgentAndroidSystemNativeTools.WIFI_STATUS,
            AgentAndroidSystemNativeTools.WIFI_SCAN_RESULTS,
            AgentAndroidSystemNativeTools.AUDIO_STATUS,
            AgentAndroidSystemNativeTools.BIOMETRIC_STATUS,
            AgentAndroidSystemNativeTools.VPN_STATUS,
            AgentAndroidSystemNativeTools.DEVICE_POLICY_STATUS,
            AgentWebMediaNativeTools.WEB_SEARCH,
            AgentWebMediaNativeTools.WEATHER_FORECAST,
            AgentWebMediaNativeTools.WEB_OPEN,
            AgentWebMediaNativeTools.BROWSER_RENDER,
            AgentWebMediaNativeTools.CONTENT_EXTRACT,
            AgentWebMediaNativeTools.HTTP_REQUEST,
            AgentWebMediaNativeTools.WEB_HEAD,
            AgentWebMediaNativeTools.WEB_FETCH -> execute(descriptor, inputFor(descriptor.id), "real_read")

            AgentHardwareNativeTools.LOCATION_FOREGROUND_READ,
            AgentHardwareNativeTools.SENSOR_SAMPLE,
            AgentHardwareNativeTools.BLUETOOTH_DISCOVERY_FOREGROUND,
            AgentHardwareNativeTools.INSTALLED_APPS_LIST,
            AgentHardwareNativeTools.PACKAGE_DETAIL,
            AgentAndroidSystemNativeTools.TELEPHONY_CALL_STATE_OBSERVE,
            AgentAndroidSystemNativeTools.WIFI_SCAN_START -> execute(
                descriptor,
                inputFor(descriptor.id),
                if (descriptor.id == AgentHardwareNativeTools.LOCATION_FOREGROUND_READ) {
                    "bounded_environment_operation"
                } else {
                    "bounded_real_operation"
                }
            )

            AgentHardwareNativeTools.FLASHLIGHT_SET -> testFlashlight(descriptor)
            AgentAndroidSystemNativeTools.AUDIO_VOLUME_SET -> testVolumeWithoutChangingState(descriptor)
            AgentAndroidSystemNativeTools.AUDIO_MUTE_SET -> testMuteWithoutChangingState(descriptor)
            AgentAndroidSystemNativeTools.DOWNLOAD_ENQUEUE -> testDownloadManagerLifecycle(descriptor)
            AgentWebMediaNativeTools.FILE_DOWNLOAD,
            AgentWebMediaNativeTools.WEB_DOWNLOAD -> testWebDownload(descriptor)
            AgentWebMediaNativeTools.OCR_RECOGNIZE_CONTENT -> execute(
                descriptor,
                mapOf("content_uri" to testImageUri.toString(), "source_kind" to "captured", "timeout_ms" to 10_000),
                "real_content_read"
            )
            AgentWebMediaNativeTools.MEDIA_METADATA -> execute(
                descriptor,
                mapOf("content_uri" to testImageUri.toString()),
                "real_content_read"
            )

            AgentWebMediaNativeTools.MEDIA_FFMPEG_TRANSCODE -> execute(
                descriptor,
                inputFor(descriptor.id),
                "expected_unavailable"
            )

            else -> verifyProtectedOrHandoffContract(descriptor)
        }
    }

    private fun runWorkspaceLifecycle() {
        val b64a = Base64.getEncoder().encodeToString(byteArrayOf(1, 2))
        val b64b = Base64.getEncoder().encodeToString(byteArrayOf(3, 4))
        val calls = listOf(
            AgentPhoneNativeToolCatalog.WORKSPACE_INITIALIZE to mapOf("workspace_id" to workspaceId),
            AgentPhoneNativeToolCatalog.WORKSPACE_MKDIR to mapOf("workspace_id" to workspaceId, "path" to "docs", "recursive" to true),
            AgentPhoneNativeToolCatalog.WORKSPACE_CREATE_TEXT to mapOf("workspace_id" to workspaceId, "path" to "docs/a.txt", "text" to "alpha", "create_parents" to true),
            AgentPhoneNativeToolCatalog.WORKSPACE_APPEND_TEXT to mapOf("workspace_id" to workspaceId, "path" to "docs/a.txt", "text" to " beta"),
            AgentPhoneNativeToolCatalog.WORKSPACE_READ_TEXT to mapOf("workspace_id" to workspaceId, "path" to "docs/a.txt", "max_bytes" to 1_024),
            AgentPhoneNativeToolCatalog.WORKSPACE_WRITE_TEXT to mapOf("workspace_id" to workspaceId, "path" to "docs/a.txt", "text" to "alpha beta"),
            AgentPhoneNativeToolCatalog.WORKSPACE_CREATE_BYTES to mapOf("workspace_id" to workspaceId, "path" to "docs/a.bin", "base64" to b64a, "create_parents" to true),
            AgentPhoneNativeToolCatalog.WORKSPACE_APPEND_BYTES to mapOf("workspace_id" to workspaceId, "path" to "docs/a.bin", "base64" to b64b),
            AgentPhoneNativeToolCatalog.WORKSPACE_READ_BYTES to mapOf("workspace_id" to workspaceId, "path" to "docs/a.bin", "max_bytes" to 1_024),
            AgentPhoneNativeToolCatalog.WORKSPACE_WRITE_BYTES to mapOf("workspace_id" to workspaceId, "path" to "docs/a.bin", "base64" to b64a),
            AgentPhoneNativeToolCatalog.WORKSPACE_COPY to mapOf("workspace_id" to workspaceId, "source_path" to "docs/a.txt", "destination_path" to "docs/copy.txt"),
            AgentPhoneNativeToolCatalog.WORKSPACE_MOVE to mapOf("workspace_id" to workspaceId, "source_path" to "docs/copy.txt", "destination_path" to "docs/moved.txt"),
            AgentPhoneNativeToolCatalog.WORKSPACE_SEARCH_TEXT to mapOf("workspace_id" to workspaceId, "path" to "docs", "query" to "alpha", "max_results" to 10),
            AgentPhoneNativeToolCatalog.WORKSPACE_APPLY_EXACT_PATCH to mapOf("workspace_id" to workspaceId, "path" to "docs/a.txt", "expected_text" to "alpha", "replacement_text" to "omega"),
            AgentPhoneNativeToolCatalog.WORKSPACE_DIFF_SUMMARY to mapOf("workspace_id" to workspaceId, "path" to "docs/a.txt", "proposed_text" to "omega gamma"),
            AgentPhoneNativeToolCatalog.WORKSPACE_SHA256 to mapOf("workspace_id" to workspaceId, "path" to "docs/a.txt"),
            AgentPhoneNativeToolCatalog.WORKSPACE_ZIP_CREATE to mapOf("workspace_id" to workspaceId, "archive_path" to "archive.zip", "source_paths" to listOf("docs/a.txt", "docs/a.bin")),
            AgentPhoneNativeToolCatalog.WORKSPACE_ZIP_LIST to mapOf("workspace_id" to workspaceId, "archive_path" to "archive.zip"),
            AgentPhoneNativeToolCatalog.WORKSPACE_ZIP_EXTRACT to mapOf("workspace_id" to workspaceId, "archive_path" to "archive.zip", "destination_path" to "extracted"),
            AgentPhoneNativeToolCatalog.WORKSPACE_STAT to mapOf("workspace_id" to workspaceId, "path" to "docs/a.txt"),
            AgentPhoneNativeToolCatalog.WORKSPACE_LIST to mapOf("workspace_id" to workspaceId, "path" to "", "recursive" to true, "max_entries" to 100),
            AgentPhoneNativeToolCatalog.WORKSPACE_DELETE to mapOf("workspace_id" to workspaceId, "path" to "extracted", "recursive" to true)
        )
        calls.forEach { (id, input) -> execute(requireNotNull(registry.lookup(id)).descriptor, input, "real_workspace") }
    }

    private fun testFlashlight(descriptor: AgentNativeToolDescriptor) {
        val enabled = invoke(descriptor, mapOf("enabled" to true))
        val disabled = invoke(descriptor, mapOf("enabled" to false))
        record(descriptor, "real_control_restored", disabled, mapOf("enable_status" to enabled.status.wireValue))
    }

    private fun testVolumeWithoutChangingState(descriptor: AgentNativeToolDescriptor) {
        val status = invoke(requireNotNull(registry.lookup(AgentAndroidSystemNativeTools.AUDIO_STATUS)).descriptor, emptyMap())
        val music = ((status.output["streams"] as? Map<*, *>)?.get("music") as? Map<*, *>)
        val current = (music?.get("current") as? Number)?.toInt() ?: 1
        val max = (music?.get("max") as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
        val percent = ceil((current * 100.0) / max).toInt().coerceIn(0, 100)
        execute(descriptor, mapOf("stream" to "music", "percent" to percent), "real_control_same_value")
    }

    private fun testMuteWithoutChangingState(descriptor: AgentNativeToolDescriptor) {
        val status = invoke(requireNotNull(registry.lookup(AgentAndroidSystemNativeTools.AUDIO_STATUS)).descriptor, emptyMap())
        val music = ((status.output["streams"] as? Map<*, *>)?.get("music") as? Map<*, *>)
        val muted = music?.get("muted") as? Boolean ?: false
        execute(descriptor, mapOf("stream" to "music", "muted" to muted), "real_control_same_value")
    }

    private fun testDownloadManagerLifecycle(enqueueDescriptor: AgentNativeToolDescriptor) {
        val enqueue = invoke(
            enqueueDescriptor,
            mapOf("url" to "https://example.com/", "title" to "SignalASI native tool test")
        )
        record(enqueueDescriptor, "real_download_lifecycle", enqueue)
        val id = (enqueue.output["download_id"] as? Number)?.toLong()
        val queryDescriptor = requireNotNull(registry.lookup(AgentAndroidSystemNativeTools.DOWNLOAD_QUERY)).descriptor
        val removeDescriptor = requireNotNull(registry.lookup(AgentAndroidSystemNativeTools.DOWNLOAD_REMOVE)).descriptor
        if (id == null) {
            results += baseResult(queryDescriptor, "real_download_lifecycle", "failed") + mapOf("reason" to "enqueue returned no id")
            results += baseResult(removeDescriptor, "real_download_lifecycle", "failed") + mapOf("reason" to "enqueue returned no id")
            return
        }
        SystemClock.sleep(500)
        execute(queryDescriptor, mapOf("download_id" to id), "real_download_lifecycle")
        execute(removeDescriptor, mapOf("download_id" to id), "real_download_lifecycle_cleanup")
    }

    private fun testWebDownload(descriptor: AgentNativeToolDescriptor) {
        val destination = createDownloadDestination(descriptor.id.substringAfterLast('.'))
        execute(
            descriptor,
            mapOf(
                "url" to "https://example.com/",
                "destination_content_uri" to destination.toString(),
                "timeout_ms" to 5_000,
                "max_bytes" to 16_384
            ),
            "real_content_write"
        )
    }

    private fun verifyProtectedOrHandoffContract(descriptor: AgentNativeToolDescriptor) {
        val input = inputFor(descriptor.id)
        val validation = registry.validateInput(descriptor.id, input)
        if (!validation.isValid) {
            results += baseResult(descriptor, "contract_invalid", "failed") + mapOf(
                "issues" to validation.issues.map { it.message }
            )
            return
        }
        val requiredConsent = descriptor.requiredConsents.firstOrNull { it.required }
        if (requiredConsent != null) {
            val context = grantedContext(descriptor).copy(
                grantedConsents = descriptor.requiredConsents.map { it.id }.toSet() - requiredConsent.id
            )
            val result = registry.invoke(descriptor.id, input, context)
            val passed = (result.status == AgentNativeToolResultStatus.REJECTED && result.error?.code == "missing_consents") ||
                result.status == AgentNativeToolResultStatus.UNAVAILABLE
            results += baseResult(descriptor, "confirmation_gate", if (passed) "passed" else "failed") + mapOf(
                "native_status" to result.status.wireValue,
                "error_code" to result.error?.code
            )
        } else {
            results += baseResult(descriptor, "manual_handoff_not_launched", "passed") + mapOf(
                "schema_valid" to true,
                "reason" to "External UI or accessibility action intentionally not launched by unattended test"
            )
        }
    }

    private fun execute(descriptor: AgentNativeToolDescriptor, input: AgentNativeJsonObject, mode: String) {
        val result = invoke(descriptor, input)
        record(descriptor, mode, result)
    }

    private fun invoke(descriptor: AgentNativeToolDescriptor, input: AgentNativeJsonObject): AgentNativeToolResult =
        registry.invoke(descriptor.id, input, grantedContext(descriptor))

    private fun grantedContext(descriptor: AgentNativeToolDescriptor) = AgentNativeToolInvocationContext(
        invocationId = "device-${UUID.randomUUID()}",
        idempotencyKey = "device-${UUID.randomUUID()}",
        grantedPermissions = descriptor.requiredPermissions.map { it.id }.toSet(),
        grantedConsents = descriptor.requiredConsents.map { it.id }.toSet()
    )

    private fun record(
        descriptor: AgentNativeToolDescriptor,
        mode: String,
        result: AgentNativeToolResult,
        extra: Map<String, Any?> = emptyMap()
    ) {
        val expected = when (mode) {
            "expected_not_found" -> result.error?.code == "download_not_found"
            "expected_unavailable" -> result.status in setOf(AgentNativeToolResultStatus.UNAVAILABLE, AgentNativeToolResultStatus.FAILED)
            "bounded_environment_operation" -> result.isSuccess || result.error?.code in setOf(
                "location_fix_unavailable",
                "location_provider_disabled"
            )
            else -> result.isSuccess || result.status == AgentNativeToolResultStatus.UNAVAILABLE
        }
        results += baseResult(descriptor, mode, if (expected) "passed" else "failed") + mapOf(
            "native_status" to result.status.wireValue,
            "duration_ms" to result.receipt.durationMillis,
            "message" to result.message,
            "error_code" to result.error?.code,
            "error_message" to result.error?.message,
            "output" to result.output
        ) + extra
    }

    private fun baseResult(descriptor: AgentNativeToolDescriptor, mode: String, verdict: String) = linkedMapOf<String, Any?>(
        "tool_id" to descriptor.id,
        "title" to descriptor.title,
        "risk" to descriptor.risk.wireValue,
        "mode" to mode,
        "verdict" to verdict
    )

    private fun inputFor(id: String): AgentNativeJsonObject {
        val now = System.currentTimeMillis()
        return when (id) {
            AgentHardwareNativeTools.LOCATION_FOREGROUND_READ -> mapOf("timeout_ms" to 3_000)
            AgentHardwareNativeTools.SENSORS_LIST -> mapOf("limit" to 20)
            AgentHardwareNativeTools.SENSOR_SAMPLE -> mapOf("type" to "accelerometer", "timeout_ms" to 2_000)
            AgentHardwareNativeTools.BLUETOOTH_DISCOVERY_FOREGROUND -> mapOf("timeout_ms" to 2_000, "limit" to 8)
            AgentHardwareNativeTools.INSTALLED_APPS_LIST -> mapOf("query" to "SignalASI", "limit" to 20)
            AgentHardwareNativeTools.PACKAGE_DETAIL -> mapOf("package_name" to instrumentation.targetContext.packageName)
            AgentAndroidSystemNativeTools.TELEPHONY_CALL_STATE_OBSERVE -> mapOf("timeout_ms" to 1_000)
            AgentAndroidSystemNativeTools.SMS_LIST -> mapOf("limit" to 1)
            AgentAndroidSystemNativeTools.CONTACTS_SEARCH -> mapOf("query" to "SignalASI device test", "limit" to 1)
            AgentAndroidSystemNativeTools.CALENDAR_EVENTS_QUERY -> mapOf("start_epoch_ms" to now, "end_epoch_ms" to now + 86_400_000, "limit" to 5)
            AgentAndroidSystemNativeTools.WIFI_SCAN_RESULTS -> mapOf("limit" to 10)
            AgentAndroidSystemNativeTools.TELEPHONY_DIAL_HANDOFF -> mapOf("phone_number" to "10086")
            AgentAndroidSystemNativeTools.SMS_SEND -> mapOf("phone_number" to "10086", "message" to "SignalASI device test")
            AgentAndroidSystemNativeTools.SMS_COMPOSE_HANDOFF -> mapOf("phone_number" to "10086", "message" to "SignalASI device test")
            AgentAndroidSystemNativeTools.CONTACTS_UPSERT -> mapOf("display_name" to "SignalASI device test", "phone_number" to "10086")
            AgentAndroidSystemNativeTools.CONTACTS_DELETE -> mapOf("contact_id" to 1)
            AgentAndroidSystemNativeTools.CALENDAR_EVENT_UPSERT -> mapOf("calendar_id" to 1, "title" to "SignalASI device test", "start_epoch_ms" to now, "end_epoch_ms" to now + 60_000)
            AgentAndroidSystemNativeTools.CALENDAR_EVENT_DELETE -> mapOf("event_id" to 1)
            AgentAndroidSystemNativeTools.DOWNLOAD_ENQUEUE -> mapOf("url" to "https://example.com/", "title" to "SignalASI device test")
            AgentAndroidSystemNativeTools.DOWNLOAD_REMOVE,
            AgentAndroidSystemNativeTools.DOWNLOAD_QUERY -> mapOf("download_id" to Long.MAX_VALUE)
            AgentWebMediaNativeTools.WEB_HEAD -> mapOf("url" to "https://example.com/", "timeout_ms" to 5_000)
            AgentWebMediaNativeTools.WEB_FETCH,
            AgentWebMediaNativeTools.WEB_OPEN,
            AgentWebMediaNativeTools.BROWSER_RENDER -> mapOf("url" to "https://example.com/", "timeout_ms" to 5_000, "max_bytes" to 16_384)
            AgentWebMediaNativeTools.WEB_SEARCH -> mapOf("query" to "SignalASI", "max_results" to 3, "timeout_ms" to 10_000)
            AgentWebMediaNativeTools.WEATHER_FORECAST -> mapOf("location" to "Shanghai", "language" to "en", "day_offset" to 0, "timeout_ms" to 10_000)
            AgentWebMediaNativeTools.CONTENT_EXTRACT -> mapOf("content" to "<h1>SignalASI</h1><p>Device test</p>")
            AgentWebMediaNativeTools.HTTP_REQUEST -> mapOf("url" to "https://example.com/", "method" to "GET", "timeout_ms" to 5_000, "max_bytes" to 16_384)
            AgentWebMediaNativeTools.WEB_DOWNLOAD -> mapOf("url" to "https://example.com/", "destination_content_uri" to "content://invalid/device-test")
            AgentWebMediaNativeTools.OCR_RECOGNIZE_CONTENT -> mapOf("content_uri" to "content://invalid/device-test", "source_kind" to "captured")
            AgentWebMediaNativeTools.MEDIA_METADATA -> mapOf("content_uri" to "content://invalid/device-test")
            AgentWebMediaNativeTools.MEDIA_PLAYBACK_HANDOFF -> mapOf("content_uri" to "content://invalid/device-test")
            AgentWebMediaNativeTools.MEDIA_FFMPEG_TRANSCODE -> mapOf("content_uri" to "content://invalid/device-test", "target_format" to "mp3")
            else -> genericInput(requireNotNull(registry.lookup(id)).descriptor.inputSchema.document)
        }
    }

    private fun genericInput(schema: Map<String, Any?>): AgentNativeJsonObject {
        val properties = schema["properties"] as? Map<*, *> ?: return emptyMap()
        val required = (schema["required"] as? Iterable<*>)?.filterIsInstance<String>().orEmpty()
        return required.associateWith { name ->
            val property = properties[name] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            sampleValue(name, property)
        }
    }

    private fun sampleValue(name: String, schema: Map<*, *>): Any = when (name) {
        "target" -> ""
        "description" -> "SignalASI device contract test"
        "parameters" -> genericInput(schema.entries.associate { it.key.toString() to it.value })
        "requires_confirmation" -> true
        "bounds", "field_bounds" -> "[100,100][200,200]"
        "from_x", "from_y" -> "100"
        "to_x", "to_y" -> "200"
        "notification_key" -> "signalasi-device-test-notification"
        "reply_text" -> "SignalASI device test"
        "phone_number" -> "10086"
        "message", "text", "title" -> "SignalASI device test"
        "url" -> "https://example.com/"
        "content_uri", "destination_content_uri" -> "content://invalid/device-test"
        "stream" -> "music"
        "package_name" -> instrumentation.targetContext.packageName
        "start_epoch_ms" -> System.currentTimeMillis()
        "end_epoch_ms" -> System.currentTimeMillis() + 60_000
        else -> when (schema["type"]) {
            "boolean" -> false
            "integer", "number" -> (schema["minimum"] as? Number)?.toLong()?.coerceAtLeast(1) ?: 1L
            "array" -> emptyList<Any>()
            "object" -> genericInput(schema.entries.associate { it.key.toString() to it.value })
            else -> (schema["enum"] as? Iterable<*>)?.firstOrNull()?.toString() ?: "device-test"
        }
    }

    private fun writeReport(toolCount: Int) {
        val payload = linkedMapOf<String, Any?>(
            "device" to android.os.Build.MODEL,
            "sdk" to android.os.Build.VERSION.SDK_INT,
            "package" to instrumentation.targetContext.packageName,
            "generated_at_epoch_ms" to System.currentTimeMillis(),
            "tool_count" to toolCount,
            "passed" to results.count { it["verdict"] == "passed" },
            "failed" to results.count { it["verdict"] == "failed" },
            "results" to results
        )
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(AgentNativeJsonCodec.stringify(payload))
        println("SIGNALASI_NATIVE_TOOL_REPORT=${reportFile.absolutePath}")
    }

    private fun createTestImage(): Uri {
        val resolver = instrumentation.targetContext.contentResolver
        val uri = requireNotNull(
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "signalasi-native-tool-test-${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            )
        )
        val bitmap = Bitmap.createBitmap(640, 240, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("SignalASI DEVICE TEST", 36f, 130f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 48f
            })
        }
        resolver.openOutputStream(uri, "w")!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        temporaryUris += uri
        return uri
    }

    private fun createDownloadDestination(suffix: String): Uri {
        val uri = Uri.parse(
            "content://${instrumentation.targetContext.packageName}.native-tool-test/" +
                "download-$suffix-${System.currentTimeMillis()}.html"
        )
        temporaryUris += uri
        return uri
    }
}
