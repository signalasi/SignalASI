package com.signalasi.chat

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.bluetooth.BluetoothManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import java.io.File

enum class AgentPhoneCapabilityId {
    ACCESSIBILITY_UI_TREE,
    ACCESSIBILITY_GESTURES,
    MEDIA_PROJECTION_OCR,
    NOTIFICATION_READ,
    NOTIFICATION_REPLY,
    CLIPBOARD,
    CAMERA,
    MICROPHONE,
    LOCATION,
    SENSORS,
    BLUETOOTH,
    NFC,
    BATTERY,
    NETWORK,
    INSTALLED_APPS,
    INTENT_LAUNCH,
    SYSTEM_SETTINGS,
    PACKAGE_INSTALL_HANDOFF,
    DEVICE_OWNER,
    SHIZUKU,
    ROOT,
    HOME_ASSISTANT,
    MEDIA_PLAYBACK,
    MEDIA_TRANSCODE
}

enum class AgentPhoneExecutionLocation {
    APP_PROCESS,
    ACCESSIBILITY_SERVICE,
    SCREEN_CAPTURE_SERVICE,
    NOTIFICATION_LISTENER_SERVICE,
    ANDROID_SYSTEM_SERVICE,
    SYSTEM_UI_HANDOFF,
    PRIVILEGED_BRIDGE,
    HOME_ASSISTANT_SERVER
}

enum class AgentPhoneCapabilityAvailability {
    READY,
    LIMITED,
    NEEDS_RUNTIME_PERMISSION,
    NEEDS_SPECIAL_ACCESS,
    NEEDS_USER_CONSENT,
    NEEDS_CONFIGURATION,
    NOT_IMPLEMENTED,
    PRIVILEGED_ONLY,
    UNSUPPORTED,
    BLOCKED_BY_POLICY,
    UNKNOWN
}

enum class AgentPhoneSpecialAccess {
    ACCESSIBILITY_SERVICE,
    MEDIA_PROJECTION_SESSION,
    NOTIFICATION_LISTENER,
    PACKAGE_VISIBILITY_DECLARATIONS,
    INSTALL_UNKNOWN_APPS,
    DEVICE_OWNER_ROLE,
    SHIZUKU_SERVICE,
    ROOT_GRANT
}

enum class AgentPhoneUserConsent {
    NONE,
    RUNTIME_PERMISSION_DIALOG,
    ENABLE_IN_SYSTEM_SETTINGS,
    PER_SESSION_SCREEN_CAPTURE,
    SENSITIVE_ACTION_CONFIRMATION,
    USER_VISIBLE_CAPTURE,
    PHYSICAL_PROXIMITY,
    PACKAGE_INSTALLER_CONFIRMATION,
    DEVICE_PROVISIONING,
    PRIVILEGED_BRIDGE_AUTHORIZATION,
    SUPERUSER_PROMPT,
    EXTERNAL_SERVICE_CREDENTIALS
}

data class AgentPhoneCapabilityBoundary(
    val id: AgentPhoneCapabilityId,
    val executionLocation: AgentPhoneExecutionLocation,
    val availability: AgentPhoneCapabilityAvailability,
    val androidPermissions: Set<String> = emptySet(),
    val specialAccess: Set<AgentPhoneSpecialAccess> = emptySet(),
    val userConsent: Set<AgentPhoneUserConsent> = setOf(AgentPhoneUserConsent.NONE),
    val risk: AgentRisk,
    val normalAppCanExecute: Boolean,
    val limitation: String
)

data class AgentPhoneCapabilityObservation(
    val probeSucceeded: Boolean = true,
    val platformSupported: Boolean = true,
    val implementationPresent: Boolean = true,
    val permissionsGranted: Boolean = true,
    val specialAccessGranted: Boolean = true,
    val userConsentGranted: Boolean = true,
    val configured: Boolean = true,
    val limited: Boolean = false,
    val evidence: String = ""
)

data class AgentPhoneCapabilityStatus(
    val boundary: AgentPhoneCapabilityBoundary,
    val availability: AgentPhoneCapabilityAvailability,
    val evidence: String
) {
    val advertisedAsReady: Boolean
        get() = availability == AgentPhoneCapabilityAvailability.READY && boundary.normalAppCanExecute
}

object AgentPhoneCapabilityNativeCoverage {
    val toolIdsByCapability: Map<AgentPhoneCapabilityId, Set<String>> = mapOf(
        AgentPhoneCapabilityId.NOTIFICATION_READ to setOf(
            AgentNotificationNativeTools.NOTIFICATIONS_LIST
        ),
        AgentPhoneCapabilityId.NOTIFICATION_REPLY to setOf(
            AgentNotificationNativeTools.NOTIFICATION_REPLY
        ),
        AgentPhoneCapabilityId.CAMERA to setOf(
            AgentVisibleCaptureNativeTools.CAMERA_CAPTURE
        ),
        AgentPhoneCapabilityId.MICROPHONE to setOf(
            AgentVisibleCaptureNativeTools.MICROPHONE_RECORD
        ),
        AgentPhoneCapabilityId.LOCATION to setOf(
            AgentHardwareNativeTools.LOCATION_FOREGROUND_READ
        ),
        AgentPhoneCapabilityId.SENSORS to setOf(
            AgentHardwareNativeTools.SENSORS_LIST,
            AgentHardwareNativeTools.SENSOR_SAMPLE
        ),
        AgentPhoneCapabilityId.BLUETOOTH to setOf(
            AgentHardwareNativeTools.BLUETOOTH_STATUS,
            AgentHardwareNativeTools.BLUETOOTH_DISCOVERY_FOREGROUND,
            AgentHardwareNativeTools.BLUETOOTH_PAIRING_HANDOFF
        ),
        AgentPhoneCapabilityId.NFC to setOf(
            AgentHardwareNativeTools.NFC_STATUS
        ),
        AgentPhoneCapabilityId.BATTERY to setOf(
            AgentHardwareNativeTools.BATTERY_STATUS,
            AgentHardwareNativeTools.POWER_STATUS
        ),
        AgentPhoneCapabilityId.NETWORK to setOf(
            AgentHardwareNativeTools.NETWORK_STATUS,
            AgentAndroidSystemNativeTools.WIFI_STATUS,
            AgentAndroidSystemNativeTools.WIFI_SCAN_RESULTS
        ),
        AgentPhoneCapabilityId.INSTALLED_APPS to setOf(
            AgentHardwareNativeTools.INSTALLED_APPS_LIST,
            AgentHardwareNativeTools.PACKAGE_DETAIL
        ),
        AgentPhoneCapabilityId.MEDIA_PLAYBACK to setOf(
            AgentWebMediaNativeTools.MEDIA_PLAYBACK_HANDOFF
        )
    )

    fun isImplemented(id: AgentPhoneCapabilityId): Boolean = toolIdsByCapability[id].orEmpty().isNotEmpty()
}

object AgentPhoneCapabilityPolicy {
    fun resolve(
        boundary: AgentPhoneCapabilityBoundary,
        observation: AgentPhoneCapabilityObservation
    ): AgentPhoneCapabilityAvailability {
        when (boundary.availability) {
            AgentPhoneCapabilityAvailability.BLOCKED_BY_POLICY,
            AgentPhoneCapabilityAvailability.PRIVILEGED_ONLY,
            AgentPhoneCapabilityAvailability.NOT_IMPLEMENTED,
            AgentPhoneCapabilityAvailability.UNSUPPORTED,
            AgentPhoneCapabilityAvailability.UNKNOWN -> return boundary.availability

            else -> Unit
        }
        if (!boundary.normalAppCanExecute) return AgentPhoneCapabilityAvailability.PRIVILEGED_ONLY
        if (!observation.probeSucceeded) return AgentPhoneCapabilityAvailability.UNKNOWN
        if (!observation.platformSupported) return AgentPhoneCapabilityAvailability.UNSUPPORTED
        if (!observation.implementationPresent) return AgentPhoneCapabilityAvailability.NOT_IMPLEMENTED
        if (!observation.permissionsGranted) return AgentPhoneCapabilityAvailability.NEEDS_RUNTIME_PERMISSION
        if (!observation.specialAccessGranted) return AgentPhoneCapabilityAvailability.NEEDS_SPECIAL_ACCESS
        if (!observation.userConsentGranted) return AgentPhoneCapabilityAvailability.NEEDS_USER_CONSENT
        if (!observation.configured) return AgentPhoneCapabilityAvailability.NEEDS_CONFIGURATION
        if (boundary.availability == AgentPhoneCapabilityAvailability.LIMITED || observation.limited) {
            return AgentPhoneCapabilityAvailability.LIMITED
        }
        return AgentPhoneCapabilityAvailability.READY
    }
}

object AgentPhoneCapabilityCatalog {
    val capabilities: List<AgentPhoneCapabilityBoundary> = listOf(
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.ACCESSIBILITY_UI_TREE,
            executionLocation = AgentPhoneExecutionLocation.ACCESSIBILITY_SERVICE,
            availability = AgentPhoneCapabilityAvailability.NEEDS_SPECIAL_ACCESS,
            androidPermissions = setOf(Manifest.permission.BIND_ACCESSIBILITY_SERVICE),
            specialAccess = setOf(AgentPhoneSpecialAccess.ACCESSIBILITY_SERVICE),
            userConsent = setOf(AgentPhoneUserConsent.ENABLE_IN_SYSTEM_SETTINGS),
            risk = AgentRisk.HIGH,
            normalAppCanExecute = true,
            limitation = "Only the active accessibility window is visible; WebView, secure, virtualized, and custom-drawn content may expose little or no tree data."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.ACCESSIBILITY_GESTURES,
            executionLocation = AgentPhoneExecutionLocation.ACCESSIBILITY_SERVICE,
            availability = AgentPhoneCapabilityAvailability.NEEDS_SPECIAL_ACCESS,
            androidPermissions = setOf(Manifest.permission.BIND_ACCESSIBILITY_SERVICE),
            specialAccess = setOf(AgentPhoneSpecialAccess.ACCESSIBILITY_SERVICE),
            userConsent = setOf(
                AgentPhoneUserConsent.ENABLE_IN_SYSTEM_SETTINGS,
                AgentPhoneUserConsent.SENSITIVE_ACTION_CONFIRMATION
            ),
            risk = AgentRisk.HIGH,
            normalAppCanExecute = true,
            limitation = "Gestures are coordinate- and focus-dependent and cannot bypass lock screens, secure surfaces, app policy, or confirmation UI."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.MEDIA_PROJECTION_OCR,
            executionLocation = AgentPhoneExecutionLocation.SCREEN_CAPTURE_SERVICE,
            availability = AgentPhoneCapabilityAvailability.NEEDS_USER_CONSENT,
            androidPermissions = setOf(
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            specialAccess = setOf(AgentPhoneSpecialAccess.MEDIA_PROJECTION_SESSION),
            userConsent = setOf(AgentPhoneUserConsent.PER_SESSION_SCREEN_CAPTURE),
            risk = AgentRisk.HIGH,
            normalAppCanExecute = true,
            limitation = "Authorization is revocable and session-scoped; FLAG_SECURE content is hidden and OCR can misread, omit, or mislocate text."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.NOTIFICATION_READ,
            executionLocation = AgentPhoneExecutionLocation.NOTIFICATION_LISTENER_SERVICE,
            availability = AgentPhoneCapabilityAvailability.NEEDS_SPECIAL_ACCESS,
            androidPermissions = setOf(Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE),
            specialAccess = setOf(AgentPhoneSpecialAccess.NOTIFICATION_LISTENER),
            userConsent = setOf(AgentPhoneUserConsent.ENABLE_IN_SYSTEM_SETTINGS),
            risk = AgentRisk.HIGH,
            normalAppCanExecute = true,
            limitation = "Only posted notification fields are visible; apps may redact content and sensitive data must not be treated as general agent context."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.NOTIFICATION_REPLY,
            executionLocation = AgentPhoneExecutionLocation.NOTIFICATION_LISTENER_SERVICE,
            availability = AgentPhoneCapabilityAvailability.NEEDS_SPECIAL_ACCESS,
            androidPermissions = setOf(Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE),
            specialAccess = setOf(AgentPhoneSpecialAccess.NOTIFICATION_LISTENER),
            userConsent = setOf(
                AgentPhoneUserConsent.ENABLE_IN_SYSTEM_SETTINGS,
                AgentPhoneUserConsent.SENSITIVE_ACTION_CONFIRMATION
            ),
            risk = AgentRisk.HIGH,
            normalAppCanExecute = true,
            limitation = "Reply works only while a live notification exposes a free-form RemoteInput; system, call, and sensitive notifications remain blocked."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.CLIPBOARD,
            executionLocation = AgentPhoneExecutionLocation.APP_PROCESS,
            availability = AgentPhoneCapabilityAvailability.LIMITED,
            risk = AgentRisk.HIGH,
            normalAppCanExecute = true,
            limitation = "Android restricts background clipboard reads and may clear sensitive clips; writes can replace user data and do not prove a later paste succeeded."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.CAMERA,
            executionLocation = AgentPhoneExecutionLocation.APP_PROCESS,
            availability = AgentPhoneCapabilityAvailability.NEEDS_RUNTIME_PERMISSION,
            androidPermissions = setOf(Manifest.permission.CAMERA),
            userConsent = setOf(
                AgentPhoneUserConsent.RUNTIME_PERMISSION_DIALOG,
                AgentPhoneUserConsent.USER_VISIBLE_CAPTURE
            ),
            risk = AgentRisk.HIGH,
            normalAppCanExecute = true,
            limitation = "The current app supports user-visible camera flows, not silent or unattended background capture."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.MICROPHONE,
            executionLocation = AgentPhoneExecutionLocation.APP_PROCESS,
            availability = AgentPhoneCapabilityAvailability.NEEDS_RUNTIME_PERMISSION,
            androidPermissions = setOf(Manifest.permission.RECORD_AUDIO),
            userConsent = setOf(
                AgentPhoneUserConsent.RUNTIME_PERMISSION_DIALOG,
                AgentPhoneUserConsent.USER_VISIBLE_CAPTURE
            ),
            risk = AgentRisk.HIGH,
            normalAppCanExecute = true,
            limitation = "Recording is subject to foreground/privacy indicators, audio focus, hardware routing, and other apps holding the microphone."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.LOCATION,
            executionLocation = AgentPhoneExecutionLocation.ANDROID_SYSTEM_SERVICE,
            availability = AgentPhoneCapabilityAvailability.NEEDS_RUNTIME_PERMISSION,
            androidPermissions = setOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            ),
            userConsent = setOf(AgentPhoneUserConsent.RUNTIME_PERMISSION_DIALOG),
            risk = AgentRisk.HIGH,
            normalAppCanExecute = true,
            limitation = "SignalASI supports one bounded foreground location fix; Android can return approximate, stale, disabled, or unavailable results and no background tracking is exposed."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.SENSORS,
            executionLocation = AgentPhoneExecutionLocation.ANDROID_SYSTEM_SERVICE,
            availability = AgentPhoneCapabilityAvailability.LIMITED,
            userConsent = setOf(AgentPhoneUserConsent.SENSITIVE_ACTION_CONFIRMATION),
            risk = AgentRisk.MEDIUM,
            normalAppCanExecute = true,
            limitation = "SignalASI lists sensor metadata and samples one bounded non-health sensor in the foreground; continuous, background, body, and health streams are excluded."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.BLUETOOTH,
            executionLocation = AgentPhoneExecutionLocation.APP_PROCESS,
            availability = AgentPhoneCapabilityAvailability.LIMITED,
            androidPermissions = setOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ),
            userConsent = setOf(
                AgentPhoneUserConsent.RUNTIME_PERMISSION_DIALOG,
                AgentPhoneUserConsent.SENSITIVE_ACTION_CONFIRMATION
            ),
            risk = AgentRisk.HIGH,
            normalAppCanExecute = true,
            limitation = "SignalASI reads adapter status, performs one bounded foreground discovery, and hands pairing to Android Settings; silent pairing, connection, and protocol traffic are excluded."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.NFC,
            executionLocation = AgentPhoneExecutionLocation.APP_PROCESS,
            availability = AgentPhoneCapabilityAvailability.LIMITED,
            androidPermissions = setOf(Manifest.permission.NFC),
            userConsent = setOf(AgentPhoneUserConsent.PHYSICAL_PROXIMITY),
            risk = AgentRisk.HIGH,
            normalAppCanExecute = true,
            limitation = "SignalASI reads NFC adapter availability and enabled state only; tag capture, writes, secure-element access, and payment operations are excluded."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.BATTERY,
            executionLocation = AgentPhoneExecutionLocation.ANDROID_SYSTEM_SERVICE,
            availability = AgentPhoneCapabilityAvailability.READY,
            risk = AgentRisk.LOW,
            normalAppCanExecute = true,
            limitation = "Only app-visible battery and charging signals are available; health, per-app attribution, and vendor diagnostics may be absent or privileged."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.NETWORK,
            executionLocation = AgentPhoneExecutionLocation.APP_PROCESS,
            availability = AgentPhoneCapabilityAvailability.READY,
            androidPermissions = setOf(
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE
            ),
            risk = AgentRisk.MEDIUM,
            normalAppCanExecute = true,
            limitation = "Network availability does not guarantee internet reachability; VPN, captive portal, metering, TLS, server policy, and background limits still apply."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.INSTALLED_APPS,
            executionLocation = AgentPhoneExecutionLocation.APP_PROCESS,
            availability = AgentPhoneCapabilityAvailability.LIMITED,
            specialAccess = setOf(AgentPhoneSpecialAccess.PACKAGE_VISIBILITY_DECLARATIONS),
            risk = AgentRisk.MEDIUM,
            normalAppCanExecute = true,
            limitation = "Android package visibility limits the inventory to declared queries, interaction history, and otherwise visible launcher apps; it is never a complete device census."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.INTENT_LAUNCH,
            executionLocation = AgentPhoneExecutionLocation.SYSTEM_UI_HANDOFF,
            availability = AgentPhoneCapabilityAvailability.READY,
            userConsent = setOf(AgentPhoneUserConsent.SENSITIVE_ACTION_CONFIRMATION),
            risk = AgentRisk.MEDIUM,
            normalAppCanExecute = true,
            limitation = "An intent only hands work to a matching activity; target availability, chooser UI, URI grants, and completion cannot be assumed."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.SYSTEM_SETTINGS,
            executionLocation = AgentPhoneExecutionLocation.SYSTEM_UI_HANDOFF,
            availability = AgentPhoneCapabilityAvailability.READY,
            userConsent = setOf(
                AgentPhoneUserConsent.ENABLE_IN_SYSTEM_SETTINGS,
                AgentPhoneUserConsent.SENSITIVE_ACTION_CONFIRMATION
            ),
            risk = AgentRisk.MEDIUM,
            normalAppCanExecute = true,
            limitation = "The app can open settings screens but cannot silently change protected settings or know that the user completed the requested change."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.PACKAGE_INSTALL_HANDOFF,
            executionLocation = AgentPhoneExecutionLocation.SYSTEM_UI_HANDOFF,
            availability = AgentPhoneCapabilityAvailability.NOT_IMPLEMENTED,
            androidPermissions = setOf(Manifest.permission.REQUEST_INSTALL_PACKAGES),
            specialAccess = setOf(AgentPhoneSpecialAccess.INSTALL_UNKNOWN_APPS),
            userConsent = setOf(
                AgentPhoneUserConsent.ENABLE_IN_SYSTEM_SETTINGS,
                AgentPhoneUserConsent.PACKAGE_INSTALLER_CONFIRMATION
            ),
            risk = AgentRisk.HIGH,
            normalAppCanExecute = true,
            limitation = "SignalASI has no installer handoff; an ordinary app can only request the system installer and cannot silently install, approve, or verify arbitrary packages."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.DEVICE_OWNER,
            executionLocation = AgentPhoneExecutionLocation.PRIVILEGED_BRIDGE,
            availability = AgentPhoneCapabilityAvailability.PRIVILEGED_ONLY,
            androidPermissions = setOf(Manifest.permission.BIND_DEVICE_ADMIN),
            specialAccess = setOf(AgentPhoneSpecialAccess.DEVICE_OWNER_ROLE),
            userConsent = setOf(AgentPhoneUserConsent.DEVICE_PROVISIONING),
            risk = AgentRisk.BLOCKED,
            normalAppCanExecute = false,
            limitation = "Device-owner status must be provisioned by Android on an eligible device; this app cannot self-elevate and contains no device-policy controller."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.SHIZUKU,
            executionLocation = AgentPhoneExecutionLocation.PRIVILEGED_BRIDGE,
            availability = AgentPhoneCapabilityAvailability.PRIVILEGED_ONLY,
            specialAccess = setOf(AgentPhoneSpecialAccess.SHIZUKU_SERVICE),
            userConsent = setOf(AgentPhoneUserConsent.PRIVILEGED_BRIDGE_AUTHORIZATION),
            risk = AgentRisk.BLOCKED,
            normalAppCanExecute = false,
            limitation = "No Shizuku API is bundled; a separately installed and running service plus explicit authorization would still not make privileged calls ordinary app execution."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.ROOT,
            executionLocation = AgentPhoneExecutionLocation.PRIVILEGED_BRIDGE,
            availability = AgentPhoneCapabilityAvailability.BLOCKED_BY_POLICY,
            specialAccess = setOf(AgentPhoneSpecialAccess.ROOT_GRANT),
            userConsent = setOf(AgentPhoneUserConsent.SUPERUSER_PROMPT),
            risk = AgentRisk.BLOCKED,
            normalAppCanExecute = false,
            limitation = "SignalASI does not execute su commands; root presence is device-specific, bypasses Android's app boundary, and is intentionally never advertised as ready."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.HOME_ASSISTANT,
            executionLocation = AgentPhoneExecutionLocation.HOME_ASSISTANT_SERVER,
            availability = AgentPhoneCapabilityAvailability.NEEDS_CONFIGURATION,
            androidPermissions = setOf(Manifest.permission.INTERNET),
            userConsent = setOf(
                AgentPhoneUserConsent.EXTERNAL_SERVICE_CREDENTIALS,
                AgentPhoneUserConsent.SENSITIVE_ACTION_CONFIRMATION
            ),
            risk = AgentRisk.HIGH,
            normalAppCanExecute = true,
            limitation = "Only configured Home Assistant entities and services are reachable; server permissions, entity state, network reachability, and physical outcome remain external."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.MEDIA_PLAYBACK,
            executionLocation = AgentPhoneExecutionLocation.APP_PROCESS,
            availability = AgentPhoneCapabilityAvailability.READY,
            risk = AgentRisk.LOW,
            normalAppCanExecute = true,
            limitation = "Playback depends on a supported codec and valid source and remains subject to audio focus, output routing, volume, and other app controls."
        ),
        AgentPhoneCapabilityBoundary(
            id = AgentPhoneCapabilityId.MEDIA_TRANSCODE,
            executionLocation = AgentPhoneExecutionLocation.APP_PROCESS,
            availability = AgentPhoneCapabilityAvailability.NOT_IMPLEMENTED,
            risk = AgentRisk.MEDIUM,
            normalAppCanExecute = true,
            limitation = "SignalASI decodes selected audio for ASR but has no general media transcode pipeline; codec availability alone is not an executable transcode capability."
        )
    )

    fun find(id: AgentPhoneCapabilityId): AgentPhoneCapabilityBoundary =
        capabilities.first { it.id == id }

    fun probe(context: Context): List<AgentPhoneCapabilityStatus> {
        val appContext = context.applicationContext
        return capabilities.map { boundary ->
            val observation = runCatching { observe(appContext, boundary.id) }
                .getOrElse { error ->
                    AgentPhoneCapabilityObservation(
                        probeSucceeded = false,
                        evidence = "Probe failed: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
            AgentPhoneCapabilityStatus(
                boundary = boundary,
                availability = AgentPhoneCapabilityPolicy.resolve(boundary, observation),
                evidence = observation.evidence
            )
        }
    }

    private fun observe(
        context: Context,
        id: AgentPhoneCapabilityId
    ): AgentPhoneCapabilityObservation = when (id) {
        AgentPhoneCapabilityId.ACCESSIBILITY_UI_TREE,
        AgentPhoneCapabilityId.ACCESSIBILITY_GESTURES -> {
            val active = SignalASIAccessibilityService.isActive()
            AgentPhoneCapabilityObservation(
                specialAccessGranted = active,
                evidence = if (active) "Accessibility service connected" else "Accessibility service not connected"
            )
        }

        AgentPhoneCapabilityId.MEDIA_PROJECTION_OCR -> {
            val active = AgentScreenCaptureService.isActive()
            AgentPhoneCapabilityObservation(
                permissionsGranted = mediaProjectionPermissionsGranted(context),
                specialAccessGranted = active,
                userConsentGranted = active,
                evidence = if (active) "MediaProjection session active" else "No active MediaProjection consent session"
            )
        }

        AgentPhoneCapabilityId.NOTIFICATION_READ -> {
            val connected = SignalASINotificationListenerService.currentContext().hasAccess
            AgentPhoneCapabilityObservation(
                implementationPresent = AgentPhoneCapabilityNativeCoverage.isImplemented(id),
                specialAccessGranted = connected,
                evidence = if (connected) {
                    "Typed, bounded notification-list tool is registered and the listener is connected"
                } else {
                    "Notification listener not connected"
                }
            )
        }

        AgentPhoneCapabilityId.NOTIFICATION_REPLY -> {
            val notificationContext = SignalASINotificationListenerService.currentContext()
            AgentPhoneCapabilityObservation(
                implementationPresent = AgentPhoneCapabilityNativeCoverage.isImplemented(id),
                specialAccessGranted = notificationContext.hasAccess,
                limited = notificationContext.hasAccess,
                evidence = when {
                    !notificationContext.hasAccess -> "Notification listener not connected"
                    notificationContext.items.any { it.canReply } -> "At least one current notification exposes RemoteInput"
                    else -> "Listener connected, but no current notification exposes RemoteInput"
                }
            )
        }

        AgentPhoneCapabilityId.CLIPBOARD -> AgentPhoneCapabilityObservation(
            platformSupported = context.getSystemService(ClipboardManager::class.java) != null,
            limited = true,
            evidence = "Clipboard access is foreground- and platform-limited"
        )

        AgentPhoneCapabilityId.CAMERA -> AgentPhoneCapabilityObservation(
            platformSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
            implementationPresent = AgentPhoneCapabilityNativeCoverage.isImplemented(id),
            permissionsGranted = permissionGranted(context, Manifest.permission.CAMERA),
            evidence = if (permissionGranted(context, Manifest.permission.CAMERA)) {
                "Foreground autofocus capture tool is registered; silent background capture remains excluded"
            } else {
                permissionEvidence(context, Manifest.permission.CAMERA)
            }
        )

        AgentPhoneCapabilityId.MICROPHONE -> AgentPhoneCapabilityObservation(
            platformSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            implementationPresent = AgentPhoneCapabilityNativeCoverage.isImplemented(id),
            permissionsGranted = permissionGranted(context, Manifest.permission.RECORD_AUDIO),
            evidence = if (permissionGranted(context, Manifest.permission.RECORD_AUDIO)) {
                "Bounded foreground recording tool is registered; silent background recording remains excluded"
            } else {
                permissionEvidence(context, Manifest.permission.RECORD_AUDIO)
            }
        )

        AgentPhoneCapabilityId.LOCATION -> {
            val manager = context.getSystemService(LocationManager::class.java)
            val enabled = manager?.let { locationManager ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    locationManager.isLocationEnabled
                } else {
                    locationManager.allProviders.any(locationManager::isProviderEnabled)
                }
            } == true
            AgentPhoneCapabilityObservation(
                platformSupported = manager != null,
                implementationPresent = AgentPhoneCapabilityNativeCoverage.isImplemented(id),
                permissionsGranted = anyPermissionGranted(
                    context,
                    setOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                ),
                configured = enabled,
                evidence = when {
                    manager == null -> "Location service is unavailable"
                    !enabled -> "Location services are disabled"
                    else -> "Bounded foreground location executor is registered"
                }
            )
        }

        AgentPhoneCapabilityId.SENSORS -> {
            val sensors = context.getSystemService(SensorManager::class.java)
                ?.getSensorList(Sensor.TYPE_ALL)
                .orEmpty()
            AgentPhoneCapabilityObservation(
                platformSupported = sensors.isNotEmpty(),
                implementationPresent = AgentPhoneCapabilityNativeCoverage.isImplemented(id),
                limited = true,
                evidence = "${sensors.size} device sensors reported; bounded non-health sampling is registered"
            )
        }

        AgentPhoneCapabilityId.BLUETOOTH -> {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            } else {
                emptySet()
            }
            AgentPhoneCapabilityObservation(
                platformSupported = adapter != null,
                implementationPresent = AgentPhoneCapabilityNativeCoverage.isImplemented(id),
                permissionsGranted = permissionsGranted(context, permissions),
                limited = true,
                evidence = if (adapter == null) {
                    "Bluetooth adapter is unavailable"
                } else {
                    "Bluetooth status, bounded discovery, and system pairing handoff are registered"
                }
            )
        }

        AgentPhoneCapabilityId.NFC -> {
            val adapter = NfcAdapter.getDefaultAdapter(context)
            AgentPhoneCapabilityObservation(
                platformSupported = adapter != null,
                implementationPresent = AgentPhoneCapabilityNativeCoverage.isImplemented(id),
                permissionsGranted = permissionGranted(context, Manifest.permission.NFC),
                limited = true,
                evidence = if (adapter == null) {
                    "NFC adapter is unavailable"
                } else {
                    "NFC status executor is registered; tag operations remain excluded"
                }
            )
        }

        AgentPhoneCapabilityId.BATTERY -> AgentPhoneCapabilityObservation(
            platformSupported = context.getSystemService(BatteryManager::class.java) != null,
            evidence = "BatteryManager available"
        )

        AgentPhoneCapabilityId.NETWORK -> AgentPhoneCapabilityObservation(
            platformSupported = context.getSystemService(ConnectivityManager::class.java) != null,
            permissionsGranted = permissionsGranted(
                context,
                setOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE)
            ),
            evidence = "Connectivity service and manifest permissions probed"
        )

        AgentPhoneCapabilityId.INSTALLED_APPS -> {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val visibleCount = context.packageManager.queryIntentActivities(launcherIntent, 0).size
            AgentPhoneCapabilityObservation(
                limited = true,
                evidence = "$visibleCount launcher activities visible under package visibility rules"
            )
        }

        AgentPhoneCapabilityId.INTENT_LAUNCH -> {
            val resolvable = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .resolveActivity(context.packageManager) != null
            AgentPhoneCapabilityObservation(
                platformSupported = resolvable,
                evidence = if (resolvable) "Launcher intent resolvable" else "No launcher intent resolver"
            )
        }

        AgentPhoneCapabilityId.SYSTEM_SETTINGS -> {
            val resolvable = Intent(Settings.ACTION_SETTINGS).resolveActivity(context.packageManager) != null
            AgentPhoneCapabilityObservation(
                platformSupported = resolvable,
                evidence = if (resolvable) "System settings activity resolvable" else "System settings activity unavailable"
            )
        }

        AgentPhoneCapabilityId.PACKAGE_INSTALL_HANDOFF -> AgentPhoneCapabilityObservation(
            implementationPresent = false,
            specialAccessGranted = context.packageManager.canRequestPackageInstalls(),
            userConsentGranted = false,
            evidence = "No installer handoff; unknown-app-source access is ${if (context.packageManager.canRequestPackageInstalls()) "enabled" else "disabled"}"
        )

        AgentPhoneCapabilityId.DEVICE_OWNER -> {
            val manager = context.getSystemService(DevicePolicyManager::class.java)
            val isOwner = manager?.isDeviceOwnerApp(context.packageName) == true
            AgentPhoneCapabilityObservation(
                specialAccessGranted = isOwner,
                evidence = if (isOwner) "App is device owner, but no controller is implemented" else "App is not device owner"
            )
        }

        AgentPhoneCapabilityId.SHIZUKU -> {
            val apiPresent = runCatching { Class.forName("rikka.shizuku.Shizuku") }.isSuccess
            AgentPhoneCapabilityObservation(
                platformSupported = apiPresent,
                implementationPresent = false,
                specialAccessGranted = false,
                evidence = if (apiPresent) "Shizuku API class present, but no integration exists" else "Shizuku API not bundled"
            )
        }

        AgentPhoneCapabilityId.ROOT -> {
            val suPresent = ROOT_PATHS.any { File(it).exists() }
            AgentPhoneCapabilityObservation(
                platformSupported = suPresent,
                specialAccessGranted = false,
                evidence = if (suPresent) "su binary detected but execution is policy-blocked" else "No common su binary detected"
            )
        }

        AgentPhoneCapabilityId.HOME_ASSISTANT -> {
            val configured = HomeAssistantSettingsStore.load(context).configured
            AgentPhoneCapabilityObservation(
                permissionsGranted = permissionGranted(context, Manifest.permission.INTERNET),
                configured = configured,
                evidence = if (configured) "Home Assistant credentials configured" else "Home Assistant not configured"
            )
        }

        AgentPhoneCapabilityId.MEDIA_PLAYBACK -> AgentPhoneCapabilityObservation(
            platformSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT),
            evidence = "Android media playback API and audio output probed"
        )

        AgentPhoneCapabilityId.MEDIA_TRANSCODE -> AgentPhoneCapabilityObservation(
            implementationPresent = false,
            evidence = "No general SignalASI transcode pipeline"
        )
    }

    private fun permissionsGranted(context: Context, permissions: Set<String>): Boolean =
        permissions.all { permissionGranted(context, it) }

    private fun anyPermissionGranted(context: Context, permissions: Set<String>): Boolean =
        permissions.any { permissionGranted(context, it) }

    private fun mediaProjectionPermissionsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !permissionGranted(context, Manifest.permission.FOREGROUND_SERVICE)
        ) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !permissionGranted(context, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
        ) return false
        return true
    }

    private fun permissionGranted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun permissionEvidence(context: Context, permission: String): String =
        if (permissionGranted(context, permission)) "$permission granted" else "$permission not granted"

    private val ROOT_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/adb/magisk/busybox"
    )
}
