package com.signalasi.chat

import android.Manifest
import android.app.DownloadManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.biometrics.BiometricManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Bounded Android framework tools that operate outside UI automation. */
object AgentAndroidSystemNativeTools {
    const val TELEPHONY_STATUS = "signalasi.android.telephony.status"
    const val TELEPHONY_CALL_STATE = "signalasi.android.telephony.call_state"
    const val TELEPHONY_CALL_STATE_OBSERVE = "signalasi.android.telephony.call_state.observe"
    const val TELEPHONY_DIAL_HANDOFF = "signalasi.android.telephony.dial.handoff"
    const val SMS_LIST = "signalasi.android.sms.list"
    const val SMS_SEND = "signalasi.android.sms.send"
    const val SMS_COMPOSE_HANDOFF = "signalasi.android.sms.compose.handoff"
    const val CONTACTS_SEARCH = "signalasi.android.contacts.search"
    const val CONTACTS_UPSERT = "signalasi.android.contacts.upsert"
    const val CONTACTS_DELETE = "signalasi.android.contacts.delete"
    const val CALENDARS_LIST = "signalasi.android.calendar.calendars.list"
    const val CALENDAR_EVENTS_QUERY = "signalasi.android.calendar.events.query"
    const val CALENDAR_EVENT_UPSERT = "signalasi.android.calendar.event.upsert"
    const val CALENDAR_EVENT_DELETE = "signalasi.android.calendar.event.delete"
    const val WIFI_STATUS = "signalasi.android.wifi.status"
    const val WIFI_SCAN_RESULTS = "signalasi.android.wifi.scan_results"
    const val WIFI_SCAN_START = "signalasi.android.wifi.scan.start"
    const val WIFI_PANEL_OPEN = "signalasi.android.wifi.panel.open"
    const val WIFI_HOTSPOT_PANEL_OPEN = "signalasi.android.wifi.hotspot.panel.open"
    const val AUDIO_STATUS = "signalasi.android.audio.status"
    const val AUDIO_VOLUME_SET = "signalasi.android.audio.volume.set"
    const val AUDIO_MUTE_SET = "signalasi.android.audio.mute.set"
    const val DOWNLOAD_ENQUEUE = "signalasi.android.download.enqueue"
    const val DOWNLOAD_QUERY = "signalasi.android.download.query"
    const val DOWNLOAD_REMOVE = "signalasi.android.download.remove"
    const val BIOMETRIC_STATUS = "signalasi.android.biometric.status"
    const val BIOMETRIC_ENROLLMENT_OPEN = "signalasi.android.biometric.enrollment.open"
    const val VPN_STATUS = "signalasi.android.vpn.status"
    const val VPN_CONSENT_OPEN = "signalasi.android.vpn.consent.open"
    const val DEVICE_POLICY_STATUS = "signalasi.android.device_policy.status"
    const val DEVICE_POLICY_LOCK = "signalasi.android.device_policy.lock"
    const val DEVICE_POLICY_REBOOT = "signalasi.android.device_policy.reboot"

    const val CONSENT_PHONE_READ = "signalasi.consent.phone.read"
    const val CONSENT_SMS_READ = "signalasi.consent.sms.read"
    const val CONSENT_SMS_SEND = "signalasi.consent.sms.send"
    const val CONSENT_CONTACTS_WRITE = "signalasi.consent.contacts.write"
    const val CONSENT_CALENDAR_WRITE = "signalasi.consent.calendar.write"
    const val CONSENT_AUDIO_CHANGE = "signalasi.consent.audio.change"
    const val CONSENT_DOWNLOAD = "signalasi.consent.download"
    const val CONSENT_DEVICE_POLICY = "signalasi.consent.device_policy"

    private const val VERSION = "1.0.0"
    private const val EXECUTOR = "signalasi.android.system_native"

    val toolIds: Set<String> = linkedSetOf(
        TELEPHONY_STATUS, TELEPHONY_CALL_STATE, TELEPHONY_CALL_STATE_OBSERVE, TELEPHONY_DIAL_HANDOFF,
        SMS_LIST, SMS_SEND, SMS_COMPOSE_HANDOFF,
        CONTACTS_SEARCH, CONTACTS_UPSERT, CONTACTS_DELETE,
        CALENDARS_LIST, CALENDAR_EVENTS_QUERY, CALENDAR_EVENT_UPSERT, CALENDAR_EVENT_DELETE,
        WIFI_STATUS, WIFI_SCAN_RESULTS, WIFI_SCAN_START, WIFI_PANEL_OPEN, WIFI_HOTSPOT_PANEL_OPEN,
        AUDIO_STATUS, AUDIO_VOLUME_SET, AUDIO_MUTE_SET,
        DOWNLOAD_ENQUEUE, DOWNLOAD_QUERY, DOWNLOAD_REMOVE,
        BIOMETRIC_STATUS, BIOMETRIC_ENROLLMENT_OPEN,
        VPN_STATUS, VPN_CONSENT_OPEN,
        DEVICE_POLICY_STATUS, DEVICE_POLICY_LOCK, DEVICE_POLICY_REBOOT
    )

    fun definitions(context: Context): List<AgentNativeToolDefinition> {
        val app = context.applicationContext
        return listOf(
            readTool(app, TELEPHONY_STATUS, "Read phone service status", "Reads bounded SIM and mobile service state.",
                setOf("telephony.status"), listOf(Manifest.permission.READ_PHONE_STATE), ::emptyInput) {
                telephonyStatus(app)
            },
            readTool(app, TELEPHONY_CALL_STATE, "Read current call state", "Reads idle, ringing, or off-hook call state.",
                setOf("telephony.call_state"), listOf(Manifest.permission.READ_PHONE_STATE), ::emptyInput) {
                val manager = app.getSystemService(TelephonyManager::class.java)
                success(mapOf("call_state" to callStateName(manager.callState)), "Current call state read")
            },
            readTool(app, TELEPHONY_CALL_STATE_OBSERVE, "Observe call state transition",
                "Waits for one bounded incoming-call or call-state transition without an unbounded background listener.",
                setOf("telephony.call_state.observe"), listOf(Manifest.permission.READ_PHONE_STATE), {
                    input(mapOf("timeout_ms" to integer(1_000, 30_000)))
                }) { invocation -> observeCallState(app, invocation) },
            writeTool(app, TELEPHONY_DIAL_HANDOFF, "Open Android dialer", "Opens the system dialer with a bounded phone number.",
                AgentNativeToolRisk.MEDIUM, setOf("telephony.dial_handoff"), emptyList(),
                input(mapOf("phone_number" to string(64)), setOf("phone_number"))) { invocation ->
                val number = invocation.text("phone_number", 64)
                requirePhoneNumber(number)?.let { return@writeTool it }
                app.launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
                success(mapOf("launched" to true, "phone_number" to number), "Android dialer opened")
            },
            readTool(app, SMS_LIST, "Read recent SMS messages", "Reads a bounded recent SMS list after Android permission is granted.",
                setOf("sms.read"), listOf(Manifest.permission.READ_SMS), {
                    input(mapOf("limit" to integer(1, 100), "address" to string(128)))
                }) { invocation -> smsList(app, invocation) },
            writeTool(app, SMS_SEND, "Send SMS message", "Sends one bounded SMS message after explicit confirmation.",
                AgentNativeToolRisk.HIGH, setOf("sms.send"), listOf(Manifest.permission.SEND_SMS),
                input(mapOf("phone_number" to string(64), "message" to string(2_000)), setOf("phone_number", "message")),
                listOf(CONSENT_SMS_SEND)) { invocation -> smsSend(app, invocation) },
            writeTool(app, SMS_COMPOSE_HANDOFF, "Open SMS composer", "Opens the system SMS composer without silently sending.",
                AgentNativeToolRisk.MEDIUM, setOf("sms.compose_handoff"), emptyList(),
                input(mapOf("phone_number" to string(64), "message" to string(2_000)), setOf("phone_number"))) { invocation ->
                val number = invocation.text("phone_number", 64)
                requirePhoneNumber(number)?.let { return@writeTool it }
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
                    .putExtra("sms_body", invocation.text("message", 2_000))
                app.launch(intent)
                success(mapOf("launched" to true), "Android SMS composer opened")
            },
            readTool(app, CONTACTS_SEARCH, "Search Android contacts", "Searches bounded contact names and phone numbers.",
                setOf("contacts.read"), listOf(Manifest.permission.READ_CONTACTS), {
                    input(mapOf("query" to string(160), "limit" to integer(1, 100)))
                }) { invocation -> contactsSearch(app, invocation) },
            writeTool(app, CONTACTS_UPSERT, "Create or update Android contact", "Creates a contact or updates its display name and phone number.",
                AgentNativeToolRisk.HIGH, setOf("contacts.write"), listOf(Manifest.permission.WRITE_CONTACTS),
                input(mapOf("contact_id" to integer(1), "display_name" to string(160), "phone_number" to string(64)),
                    setOf("display_name")), listOf(CONSENT_CONTACTS_WRITE)) { invocation -> contactsUpsert(app, invocation) },
            writeTool(app, CONTACTS_DELETE, "Delete Android contact", "Deletes one contact selected by stable contact id.",
                AgentNativeToolRisk.HIGH, setOf("contacts.delete"), listOf(Manifest.permission.WRITE_CONTACTS),
                input(mapOf("contact_id" to integer(1)), setOf("contact_id")), listOf(CONSENT_CONTACTS_WRITE)) { invocation ->
                val id = invocation.long("contact_id", 0L)
                val deleted = app.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id), null, null)
                success(mapOf("contact_id" to id, "deleted_rows" to deleted), "Contact delete completed")
            },
            readTool(app, CALENDARS_LIST, "List Android calendars", "Lists bounded visible calendars.",
                setOf("calendar.read"), listOf(Manifest.permission.READ_CALENDAR), ::emptyInput) {
                calendarsList(app)
            },
            readTool(app, CALENDAR_EVENTS_QUERY, "Query Android calendar events", "Queries bounded events in a time range.",
                setOf("calendar.read"), listOf(Manifest.permission.READ_CALENDAR), {
                    input(mapOf("start_epoch_ms" to integer(0), "end_epoch_ms" to integer(0), "limit" to integer(1, 200)),
                        setOf("start_epoch_ms", "end_epoch_ms"))
                }) { invocation -> calendarEvents(app, invocation) },
            writeTool(app, CALENDAR_EVENT_UPSERT, "Create or update calendar event", "Creates or updates one bounded calendar event.",
                AgentNativeToolRisk.HIGH, setOf("calendar.write"), listOf(Manifest.permission.WRITE_CALENDAR),
                input(mapOf(
                    "event_id" to integer(1), "calendar_id" to integer(1), "title" to string(240),
                    "description" to string(2_000), "location" to string(240),
                    "start_epoch_ms" to integer(0), "end_epoch_ms" to integer(0), "timezone" to string(80)
                ), setOf("calendar_id", "title", "start_epoch_ms", "end_epoch_ms")),
                listOf(CONSENT_CALENDAR_WRITE)) { invocation -> calendarUpsert(app, invocation) },
            writeTool(app, CALENDAR_EVENT_DELETE, "Delete calendar event", "Deletes one event selected by stable event id.",
                AgentNativeToolRisk.HIGH, setOf("calendar.delete"), listOf(Manifest.permission.WRITE_CALENDAR),
                input(mapOf("event_id" to integer(1)), setOf("event_id")), listOf(CONSENT_CALENDAR_WRITE)) { invocation ->
                val id = invocation.long("event_id", 0L)
                val deleted = app.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
                success(mapOf("event_id" to id, "deleted_rows" to deleted), "Calendar event delete completed")
            },
            readTool(app, WIFI_STATUS, "Read Wi-Fi status", "Reads bounded Wi-Fi and active network state.",
                setOf("wifi.status"), listOf(Manifest.permission.ACCESS_WIFI_STATE), ::emptyInput) { wifiStatus(app) },
            readTool(app, WIFI_SCAN_RESULTS, "Read Wi-Fi scan results", "Reads bounded scan results already available to Android.",
                setOf("wifi.scan_results"), listOf(Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.ACCESS_FINE_LOCATION), {
                    input(mapOf("limit" to integer(1, 100)))
                }) { invocation -> wifiScanResults(app, invocation) },
            writeTool(app, WIFI_SCAN_START, "Start Wi-Fi scan", "Requests a Wi-Fi scan; Android may throttle repeated scans.",
                AgentNativeToolRisk.MEDIUM, setOf("wifi.scan.start"),
                listOf(Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.ACCESS_FINE_LOCATION),
                emptyInput()) {
                @Suppress("DEPRECATION")
                val accepted = app.getSystemService(WifiManager::class.java).startScan()
                success(mapOf("accepted" to accepted, "may_be_throttled" to !accepted), "Wi-Fi scan requested")
            },
            writeTool(app, WIFI_PANEL_OPEN, "Open Internet panel", "Opens the Android Internet connectivity panel.",
                AgentNativeToolRisk.MEDIUM, setOf("wifi.settings_handoff"), emptyList(), emptyInput()) {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                    else Settings.ACTION_WIFI_SETTINGS
                app.launch(Intent(action))
                success(mapOf("launched" to true, "action" to action), "Android Internet panel opened")
            },
            writeTool(app, WIFI_HOTSPOT_PANEL_OPEN, "Open hotspot settings",
                "Opens Android tethering settings so the user remains in control of hotspot changes.",
                AgentNativeToolRisk.MEDIUM, setOf("wifi.hotspot.settings_handoff"), emptyList(), emptyInput()) {
                val action = "android.settings.TETHER_SETTINGS"
                app.launch(Intent(action))
                success(mapOf("launched" to true, "action" to action), "Android hotspot settings opened")
            },
            readTool(app, AUDIO_STATUS, "Read audio status", "Reads bounded stream volume, ringer mode, and audio route state.",
                setOf("audio.status"), emptyList(), ::emptyInput) { audioStatus(app) },
            writeTool(app, AUDIO_VOLUME_SET, "Set Android stream volume", "Sets one Android audio stream to a bounded percentage.",
                AgentNativeToolRisk.MEDIUM, setOf("audio.volume"), emptyList(),
                input(mapOf("stream" to string(32), "percent" to integer(0, 100)), setOf("stream", "percent")),
                listOf(CONSENT_AUDIO_CHANGE)) { invocation -> audioSetVolume(app, invocation) },
            writeTool(app, AUDIO_MUTE_SET, "Set Android stream mute", "Mutes or unmutes one Android audio stream.",
                AgentNativeToolRisk.MEDIUM, setOf("audio.mute"), emptyList(),
                input(mapOf("stream" to string(32), "muted" to AgentNativeJsonSchema.boolean()), setOf("stream", "muted")),
                listOf(CONSENT_AUDIO_CHANGE)) { invocation -> audioSetMute(app, invocation) },
            writeTool(app, DOWNLOAD_ENQUEUE, "Enqueue Android download", "Enqueues one HTTPS download through DownloadManager.",
                AgentNativeToolRisk.MEDIUM, setOf("download.enqueue"), listOf(Manifest.permission.INTERNET),
                input(mapOf("url" to string(4_096), "title" to string(240), "description" to string(500)), setOf("url")),
                listOf(CONSENT_DOWNLOAD)) { invocation -> downloadEnqueue(app, invocation) },
            readTool(app, DOWNLOAD_QUERY, "Query Android download", "Queries one DownloadManager record.",
                setOf("download.query"), emptyList(), { input(mapOf("download_id" to integer(1)), setOf("download_id")) }) {
                    invocation -> downloadQuery(app, invocation)
            },
            writeTool(app, DOWNLOAD_REMOVE, "Remove Android download", "Removes one DownloadManager record and its managed file.",
                AgentNativeToolRisk.HIGH, setOf("download.remove"), emptyList(),
                input(mapOf("download_id" to integer(1)), setOf("download_id")), listOf(CONSENT_DOWNLOAD)) { invocation ->
                val id = invocation.long("download_id", 0L)
                val removed = app.getSystemService(DownloadManager::class.java).remove(id)
                success(mapOf("download_id" to id, "removed" to removed), "Download remove completed")
            },
            readTool(app, BIOMETRIC_STATUS, "Read biometric capability", "Reads biometric hardware and enrollment capability without authenticating.",
                setOf("biometric.status"), listOf(Manifest.permission.USE_BIOMETRIC), ::emptyInput) { biometricStatus(app) },
            writeTool(app, BIOMETRIC_ENROLLMENT_OPEN, "Open biometric enrollment", "Opens Android biometric enrollment settings.",
                AgentNativeToolRisk.MEDIUM, setOf("biometric.enrollment_handoff"), emptyList(), emptyInput()) {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Settings.ACTION_BIOMETRIC_ENROLL
                    else Settings.ACTION_SECURITY_SETTINGS
                app.launch(Intent(action))
                success(mapOf("launched" to true, "action" to action), "Biometric enrollment settings opened")
            },
            readTool(app, VPN_STATUS, "Read VPN status", "Reads whether Android reports an active VPN transport.",
                setOf("vpn.status"), emptyList(), ::emptyInput) { vpnStatus(app) },
            writeTool(app, VPN_CONSENT_OPEN, "Request Android VPN consent", "Opens the system-owned VPN consent flow when required.",
                AgentNativeToolRisk.MEDIUM, setOf("vpn.consent_handoff"), emptyList(), emptyInput()) {
                val intent = VpnService.prepare(app)
                if (intent != null) app.launch(intent)
                success(mapOf("consent_already_granted" to (intent == null), "launched" to (intent != null)),
                    if (intent == null) "VPN consent already granted" else "Android VPN consent opened")
            },
            readTool(app, DEVICE_POLICY_STATUS, "Read device policy status", "Reads active-admin and device-owner status for SignalASI.",
                setOf("device_policy.status"), emptyList(), ::emptyInput) { devicePolicyStatus(app) },
            writeTool(app, DEVICE_POLICY_LOCK, "Lock device through device policy", "Locks the device only when SignalASI is an active device admin.",
                AgentNativeToolRisk.HIGH, setOf("device_policy.lock"), emptyList(), emptyInput(),
                listOf(CONSENT_DEVICE_POLICY)) {
                val manager = app.getSystemService(DevicePolicyManager::class.java)
                val admin = adminComponent(app)
                if (!manager.isAdminActive(admin)) return@writeTool failure("device_admin_required", "SignalASI is not an active device admin")
                manager.lockNow()
                success(mapOf("locked" to true), "Device lock requested")
            },
            writeTool(app, DEVICE_POLICY_REBOOT, "Reboot device through device policy", "Reboots only when SignalASI is the provisioned device owner.",
                AgentNativeToolRisk.HIGH, setOf("device_policy.reboot"), emptyList(), emptyInput(),
                listOf(CONSENT_DEVICE_POLICY)) {
                val manager = app.getSystemService(DevicePolicyManager::class.java)
                if (!manager.isDeviceOwnerApp(app.packageName)) return@writeTool failure("device_owner_required", "SignalASI is not the device owner")
                manager.reboot(adminComponent(app))
                success(mapOf("reboot_requested" to true), "Device reboot requested")
            }
        )
    }

    private fun readTool(
        context: Context,
        id: String,
        title: String,
        description: String,
        capabilities: Set<String>,
        permissions: List<String>,
        schema: () -> AgentNativeJsonSchema,
        execute: (AgentNativeToolInvocation) -> AgentNativeToolExecutionResult
    ) = definition(context, id, title, description, AgentNativeToolRisk.LOW, capabilities, permissions, emptyList(), schema(), execute)

    private fun writeTool(
        context: Context,
        id: String,
        title: String,
        description: String,
        risk: AgentNativeToolRisk,
        capabilities: Set<String>,
        permissions: List<String>,
        schema: AgentNativeJsonSchema,
        consents: List<String> = emptyList(),
        execute: (AgentNativeToolInvocation) -> AgentNativeToolExecutionResult
    ) = definition(context, id, title, description, risk, capabilities, permissions, consents, schema, execute)

    private fun definition(
        context: Context,
        id: String,
        title: String,
        description: String,
        risk: AgentNativeToolRisk,
        capabilities: Set<String>,
        permissions: List<String>,
        consents: List<String>,
        schema: AgentNativeJsonSchema,
        execute: (AgentNativeToolInvocation) -> AgentNativeToolExecutionResult
    ): AgentNativeToolDefinition {
        val availability = permissionAvailability(context, permissions)
        val descriptor = AgentNativeToolDescriptor(
            id = id,
            version = VERSION,
            title = title,
            description = description,
            location = AgentNativeToolLocation.ANDROID_SYSTEM,
            inputSchema = schema,
            outputSchema = AgentNativeJsonSchema.objectSchema(),
            risk = risk,
            capabilities = capabilities,
            requiredPermissions = permissions.map { AgentNativePermissionRequirement(it, it.substringAfterLast('.')) },
            requiredConsents = consents.map { AgentNativeConsentRequirement(it, it.substringAfterLast('.').replace('_', ' ')) },
            timeoutMillis = 30_000L,
            idempotency = if (risk.weight >= AgentNativeToolRisk.HIGH.weight) AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED
                else AgentNativeToolIdempotency.NON_IDEMPOTENT,
            availability = availability
        )
        return AgentNativeToolDefinition(
            descriptor = descriptor,
            executor = AgentNativeToolExecutor { invocation ->
                runCatching { execute(invocation) }.getOrElse { failure("android_api_error", it.message ?: it.javaClass.simpleName) }
            },
            executorId = EXECUTOR,
            provenanceMetadata = mapOf("platform" to "android", "contract" to "bounded-system-api-v1"),
            availabilityProvider = AgentNativeToolAvailabilityProvider { permissionAvailability(context, permissions) }
        )
    }

    private fun telephonyStatus(context: Context): AgentNativeToolExecutionResult {
        val manager = context.getSystemService(TelephonyManager::class.java)
        return success(mapOf(
            "phone_type" to manager.phoneType,
            "sim_state" to manager.simState,
            "network_operator_name" to manager.networkOperatorName.orEmpty().take(160),
            "network_country_iso" to manager.networkCountryIso.orEmpty().take(8),
            "data_state" to manager.dataState,
            "call_state" to callStateName(manager.callState),
            "data_enabled" to runCatching { manager.isDataEnabled }.getOrNull()
        ), "Phone service status read")
    }

    private fun observeCallState(context: Context, invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult {
        val manager = context.getSystemService(TelephonyManager::class.java)
        val initial = manager.callState
        val timeout = invocation.long("timeout_ms", 10_000L).coerceIn(1_000L, 30_000L)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return success(mapOf(
                "initial_state" to callStateName(initial), "observed_state" to callStateName(initial),
                "changed" to false, "timed_out" to true, "continuous_listener_supported" to false
            ), "Current call state read; transition observation requires Android 12 or newer")
        }
        val latch = CountDownLatch(1)
        var observed = initial
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                observed = state
                if (state != initial) latch.countDown()
            }
        }
        manager.registerTelephonyCallback(context.mainExecutor, callback)
        val changed = try {
            latch.await(timeout, TimeUnit.MILLISECONDS)
        } finally {
            manager.unregisterTelephonyCallback(callback)
        }
        return success(mapOf(
            "initial_state" to callStateName(initial), "observed_state" to callStateName(observed),
            "changed" to changed, "timed_out" to !changed, "timeout_ms" to timeout,
            "continuous_listener_supported" to true
        ), if (changed) "Call state transition observed" else "No call state transition observed before timeout")
    }

    private fun smsList(context: Context, invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult {
        val limit = invocation.int("limit", 20).coerceIn(1, 100)
        val address = invocation.text("address", 128)
        val selection = if (address.isBlank()) null else "${Telephony.Sms.ADDRESS} = ?"
        val args = if (address.isBlank()) null else arrayOf(address)
        val items = mutableListOf<AgentNativeJsonObject>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            selection, args, "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext() && items.size < limit) {
                items += mapOf(
                    "id" to cursor.long(Telephony.Sms._ID),
                    "address" to cursor.text(Telephony.Sms.ADDRESS, 128),
                    "body" to cursor.text(Telephony.Sms.BODY, 2_000),
                    "date_epoch_ms" to cursor.long(Telephony.Sms.DATE),
                    "type" to cursor.int(Telephony.Sms.TYPE)
                )
            }
        }
        return success(mapOf("messages" to items, "count" to items.size), "Recent SMS messages read")
    }

    private fun smsSend(context: Context, invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult {
        val number = invocation.text("phone_number", 64)
        requirePhoneNumber(number)?.let { return it }
        val message = invocation.text("message", 2_000)
        if (message.isBlank()) return failure("invalid_message", "SMS message is empty")
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = manager.divideMessage(message)
        if (parts.size == 1) manager.sendTextMessage(number, null, message, null, null)
        else manager.sendMultipartTextMessage(number, null, parts, null, null)
        return success(mapOf("phone_number" to number, "parts" to parts.size), "SMS submitted to Android telephony")
    }

    private fun contactsSearch(context: Context, invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult {
        val query = invocation.text("query", 160)
        val limit = invocation.int("limit", 30).coerceIn(1, 100)
        val uri = if (query.isBlank()) ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            else Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
        val items = mutableListOf<AgentNativeJsonObject>()
        context.contentResolver.query(uri, arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        ), null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")?.use { cursor ->
            while (cursor.moveToNext() && items.size < limit) {
                items += mapOf(
                    "contact_id" to cursor.long(ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
                    "display_name" to cursor.text(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, 160),
                    "phone_number" to cursor.text(ContactsContract.CommonDataKinds.Phone.NUMBER, 64)
                )
            }
        }
        return success(mapOf("contacts" to items.distinctBy { it["contact_id"] to it["phone_number"] }, "count" to items.size), "Contacts search completed")
    }

    private fun contactsUpsert(context: Context, invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult {
        val id = invocation.long("contact_id", 0L)
        val name = invocation.text("display_name", 160)
        val phone = invocation.text("phone_number", 64)
        if (name.isBlank()) return failure("invalid_contact", "Contact display name is empty")
        val resolver = context.contentResolver
        if (id <= 0L) {
            val raw = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, null as String?)
                put(ContactsContract.RawContacts.ACCOUNT_NAME, null as String?)
            }) ?: return failure("contact_insert_failed", "Android did not create a raw contact")
            val rawId = ContentUris.parseId(raw)
            resolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            })
            if (phone.isNotBlank()) resolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            })
            return success(mapOf("raw_contact_id" to rawId, "created" to true), "Contact created")
        }
        val nameRows = resolver.update(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
        }, "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(id.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
        val phoneRows = if (phone.isBlank()) 0 else resolver.update(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
        }, "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(id.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE))
        return success(mapOf("contact_id" to id, "updated_name_rows" to nameRows, "updated_phone_rows" to phoneRows), "Contact updated")
    }

    private fun calendarsList(context: Context): AgentNativeToolExecutionResult {
        val items = mutableListOf<AgentNativeJsonObject>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.VISIBLE
        ), null, null, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC")?.use { cursor ->
            while (cursor.moveToNext() && items.size < 100) items += mapOf(
                "calendar_id" to cursor.long(CalendarContract.Calendars._ID),
                "display_name" to cursor.text(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, 160),
                "account_name" to cursor.text(CalendarContract.Calendars.ACCOUNT_NAME, 160),
                "visible" to (cursor.int(CalendarContract.Calendars.VISIBLE) == 1)
            )
        }
        return success(mapOf("calendars" to items, "count" to items.size), "Calendars listed")
    }

    private fun calendarEvents(context: Context, invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult {
        val start = invocation.long("start_epoch_ms", 0L)
        val end = invocation.long("end_epoch_ms", 0L)
        if (start <= 0L || end <= start) return failure("invalid_time_range", "Calendar time range is invalid")
        val limit = invocation.int("limit", 50).coerceIn(1, 200)
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start)
        ContentUris.appendId(builder, end)
        val items = mutableListOf<AgentNativeJsonObject>()
        context.contentResolver.query(builder.build(), arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_ID
        ), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cursor ->
            while (cursor.moveToNext() && items.size < limit) items += mapOf(
                "event_id" to cursor.long(CalendarContract.Instances.EVENT_ID),
                "title" to cursor.text(CalendarContract.Instances.TITLE, 240),
                "start_epoch_ms" to cursor.long(CalendarContract.Instances.BEGIN),
                "end_epoch_ms" to cursor.long(CalendarContract.Instances.END),
                "location" to cursor.text(CalendarContract.Instances.EVENT_LOCATION, 240),
                "calendar_id" to cursor.long(CalendarContract.Instances.CALENDAR_ID)
            )
        }
        return success(mapOf("events" to items, "count" to items.size), "Calendar events queried")
    }

    private fun calendarUpsert(context: Context, invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult {
        val eventId = invocation.long("event_id", 0L)
        val start = invocation.long("start_epoch_ms", 0L)
        val end = invocation.long("end_epoch_ms", 0L)
        if (start <= 0L || end <= start) return failure("invalid_time_range", "Calendar event time range is invalid")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, invocation.long("calendar_id", 0L))
            put(CalendarContract.Events.TITLE, invocation.text("title", 240))
            put(CalendarContract.Events.DESCRIPTION, invocation.text("description", 2_000))
            put(CalendarContract.Events.EVENT_LOCATION, invocation.text("location", 240))
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, invocation.text("timezone", 80).ifBlank { java.util.TimeZone.getDefault().id })
        }
        return if (eventId > 0L) {
            val rows = context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), values, null, null)
            success(mapOf("event_id" to eventId, "updated_rows" to rows), "Calendar event updated")
        } else {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return failure("event_insert_failed", "Android did not create the calendar event")
            success(mapOf("event_id" to ContentUris.parseId(uri), "created" to true), "Calendar event created")
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiStatus(context: Context): AgentNativeToolExecutionResult {
        val wifi = context.getSystemService(WifiManager::class.java)
        val connection = wifi.connectionInfo
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return success(mapOf(
            "wifi_enabled" to wifi.isWifiEnabled,
            "ssid" to connection?.ssid.orEmpty().removeSurrounding("\""),
            "bssid" to connection?.bssid.orEmpty(),
            "rssi" to (connection?.rssi ?: -127),
            "link_speed_mbps" to (connection?.linkSpeed ?: 0),
            "active_wifi_transport" to (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true),
            "validated" to (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        ), "Wi-Fi status read")
    }

    @Suppress("DEPRECATION")
    private fun wifiScanResults(context: Context, invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult {
        val limit = invocation.int("limit", 30).coerceIn(1, 100)
        val wifi = context.getSystemService(WifiManager::class.java)
        val results = wifi.scanResults.sortedByDescending { it.level }.take(limit).map {
            mapOf("ssid" to it.SSID.take(160), "bssid" to it.BSSID.orEmpty(), "rssi" to it.level,
                "frequency_mhz" to it.frequency, "capabilities" to it.capabilities.take(300))
        }
        return success(mapOf("networks" to results, "count" to results.size), "Wi-Fi scan results read")
    }

    private fun audioStatus(context: Context): AgentNativeToolExecutionResult {
        val audio = context.getSystemService(AudioManager::class.java)
        val streams = listOf("music", "ring", "alarm", "notification", "voice_call").associateWith { name ->
            val stream = audioStream(name)
            mapOf("current" to audio.getStreamVolume(stream), "max" to audio.getStreamMaxVolume(stream),
                "muted" to audio.isStreamMute(stream))
        }
        return success(mapOf("ringer_mode" to audio.ringerMode, "mode" to audio.mode,
            "speakerphone_on" to audio.isSpeakerphoneOn, "microphone_muted" to audio.isMicrophoneMute,
            "streams" to streams), "Audio status read")
    }

    private fun audioSetVolume(context: Context, invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult {
        val audio = context.getSystemService(AudioManager::class.java)
        val name = invocation.text("stream", 32).lowercase(Locale.US)
        val stream = audioStream(name)
        val percent = invocation.int("percent", 50).coerceIn(0, 100)
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val value = ((percent / 100.0) * max).toInt().coerceIn(0, max)
        audio.setStreamVolume(stream, value, AudioManager.FLAG_SHOW_UI)
        return success(mapOf("stream" to name, "percent" to percent, "volume" to value, "max" to max), "Audio volume changed")
    }

    private fun audioSetMute(context: Context, invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult {
        val audio = context.getSystemService(AudioManager::class.java)
        val name = invocation.text("stream", 32).lowercase(Locale.US)
        val stream = audioStream(name)
        val muted = invocation.bool("muted")
        audio.adjustStreamVolume(stream, if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        return success(mapOf("stream" to name, "muted" to audio.isStreamMute(stream)), "Audio mute state changed")
    }

    private fun downloadEnqueue(context: Context, invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult {
        val url = invocation.text("url", 4_096)
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri?.scheme?.lowercase(Locale.US) != "https") return failure("invalid_download_url", "Only HTTPS downloads are allowed")
        val request = DownloadManager.Request(uri)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        invocation.text("title", 240).takeIf { it.isNotBlank() }?.let(request::setTitle)
        invocation.text("description", 500).takeIf { it.isNotBlank() }?.let(request::setDescription)
        val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
        return success(mapOf("download_id" to id, "url" to url), "Download enqueued")
    }

    private fun downloadQuery(context: Context, invocation: AgentNativeToolInvocation): AgentNativeToolExecutionResult {
        val id = invocation.long("download_id", 0L)
        context.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return failure("download_not_found", "Download record was not found")
            return success(mapOf(
                "download_id" to id,
                "status" to cursor.int(DownloadManager.COLUMN_STATUS),
                "reason" to cursor.int(DownloadManager.COLUMN_REASON),
                "bytes_downloaded" to cursor.long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                "total_bytes" to cursor.long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                "local_uri" to cursor.text(DownloadManager.COLUMN_LOCAL_URI, 4_096),
                "media_type" to cursor.text(DownloadManager.COLUMN_MEDIA_TYPE, 255)
            ), "Download status read")
        }
        return failure("download_not_found", "Download record was not found")
    }

    private fun biometricStatus(context: Context): AgentNativeToolExecutionResult {
        val secure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(BiometricManager::class.java).canAuthenticate()
        } else null
        return success(mapOf("device_secure" to secure, "can_authenticate_code" to result,
            "framework" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "BiometricManager" else "KeyguardManager"),
            "Biometric capability read")
    }

    private fun vpnStatus(context: Context): AgentNativeToolExecutionResult {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val networks = connectivity.allNetworks.mapNotNull { network ->
            connectivity.getNetworkCapabilities(network)?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }?.let {
                mapOf("network" to network.toString(), "validated" to it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    "internet" to it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
        return success(mapOf("active" to networks.isNotEmpty(), "vpn_networks" to networks,
            "consent_granted" to (VpnService.prepare(context) == null)), "VPN status read")
    }

    private fun devicePolicyStatus(context: Context): AgentNativeToolExecutionResult {
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val admin = adminComponent(context)
        return success(mapOf("admin_active" to manager.isAdminActive(admin),
            "device_owner" to manager.isDeviceOwnerApp(context.packageName),
            "profile_owner" to manager.isProfileOwnerApp(context.packageName)), "Device policy status read")
    }

    private fun adminComponent(context: Context) = ComponentName(context, SignalASIDeviceAdminReceiver::class.java)

    private fun permissionAvailability(context: Context, permissions: List<String>): AgentNativeToolAvailability {
        val missing = permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        return if (missing.isEmpty()) AgentNativeToolAvailability.AVAILABLE else AgentNativeToolAvailability(
            AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
            "Android permission required: ${missing.joinToString(", ")}"
        )
    }

    private fun Context.launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun requirePhoneNumber(value: String): AgentNativeToolExecutionResult? =
        if (value.matches(Regex("[+0-9][0-9 ()-]{2,31}"))) null
        else failure("invalid_phone_number", "Phone number format is invalid")

    private fun callStateName(value: Int) = when (value) {
        TelephonyManager.CALL_STATE_IDLE -> "idle"
        TelephonyManager.CALL_STATE_RINGING -> "ringing"
        TelephonyManager.CALL_STATE_OFFHOOK -> "off_hook"
        else -> "unknown"
    }

    private fun audioStream(value: String) = when (value) {
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "voice_call" -> AudioManager.STREAM_VOICE_CALL
        "system" -> AudioManager.STREAM_SYSTEM
        else -> AudioManager.STREAM_MUSIC
    }

    private fun emptyInput() = AgentNativeJsonSchema.objectSchema(additionalProperties = false)
    private fun input(properties: Map<String, AgentNativeJsonSchema>, required: Set<String> = emptySet()) =
        AgentNativeJsonSchema.objectSchema(properties, required, additionalProperties = false)
    private fun string(max: Int) = AgentNativeJsonSchema.string(maxLength = max)
    private fun integer(min: Long? = null, max: Long? = null) = AgentNativeJsonSchema.integer(min, max)

    private fun success(output: AgentNativeJsonObject, message: String) = AgentNativeToolExecutionResult.success(output, message)
    private fun failure(code: String, message: String) = AgentNativeToolExecutionResult.failure(code, message)

    private fun AgentNativeToolInvocation.text(key: String, max: Int) = input[key]?.toString().orEmpty().trim().take(max)
    private fun AgentNativeToolInvocation.int(key: String, fallback: Int) = (input[key] as? Number)?.toInt() ?: fallback
    private fun AgentNativeToolInvocation.long(key: String, fallback: Long) = (input[key] as? Number)?.toLong() ?: fallback
    private fun AgentNativeToolInvocation.bool(key: String) = input[key] as? Boolean ?: false

    private fun Cursor.index(name: String) = getColumnIndex(name)
    private fun Cursor.text(name: String, max: Int): String = index(name).takeIf { it >= 0 && !isNull(it) }?.let { getString(it).orEmpty().take(max) }.orEmpty()
    private fun Cursor.long(name: String): Long = index(name).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L
    private fun Cursor.int(name: String): Int = index(name).takeIf { it >= 0 && !isNull(it) }?.let(::getInt) ?: 0
}
