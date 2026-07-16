package com.signalasi.chat

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class AgentHardwareCapability {
    BATTERY,
    POWER,
    STORAGE,
    NETWORK,
    FOREGROUND_LOCATION,
    SENSOR_LIST,
    SENSOR_SAMPLE,
    FLASHLIGHT,
    BLUETOOTH_STATUS,
    BLUETOOTH_DISCOVERY,
    BLUETOOTH_PAIRING_HANDOFF,
    NFC_STATUS,
    INSTALLED_APPS,
    PACKAGE_DETAIL
}

data class AgentBatterySnapshot(
    val percent: Int?,
    val charging: Boolean,
    val plugged: String,
    val status: String,
    val health: String,
    val temperatureCelsius: Double?,
    val voltageMillivolts: Int?,
    val chargeCounterMicroampHours: Long?,
    val observedAtEpochMillis: Long
)

data class AgentPowerSnapshot(
    val interactive: Boolean,
    val powerSaveMode: Boolean,
    val deviceIdleMode: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val observedAtEpochMillis: Long
)

data class AgentStorageSnapshot(
    val scope: String,
    val totalBytes: Long,
    val availableBytes: Long,
    val lowStorage: Boolean,
    val observedAtEpochMillis: Long
)

data class AgentNetworkSnapshot(
    val connected: Boolean,
    val validated: Boolean,
    val metered: Boolean,
    val roaming: Boolean,
    val transports: List<String>,
    val downstreamKbps: Int,
    val upstreamKbps: Int,
    val observedAtEpochMillis: Long
)

data class AgentForegroundLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val altitudeMeters: Double?,
    val bearingDegrees: Double?,
    val speedMetersPerSecond: Double?,
    val provider: String,
    val fixAtEpochMillis: Long,
    val observedAtEpochMillis: Long,
    val source: String
)

data class AgentSensorDescriptor(
    val type: String,
    val androidType: Int,
    val name: String,
    val vendor: String,
    val version: Int,
    val maximumRange: Double,
    val resolution: Double,
    val powerMilliamps: Double,
    val reportingMode: String,
    val wakeUp: Boolean,
    val runtimePermission: String? = null
)

data class AgentSensorSample(
    val type: String,
    val androidType: Int,
    val values: List<Double>,
    val accuracy: Int,
    val observedAtEpochMillis: Long
)

data class AgentFlashlightRequestResult(
    val requestedEnabled: Boolean,
    val requestAccepted: Boolean,
    val stateVerified: Boolean = false
)

data class AgentBluetoothStatusSnapshot(
    val supported: Boolean,
    val enabled: Boolean,
    val discovering: Boolean,
    val bondedDeviceCount: Int?,
    val observedAtEpochMillis: Long
)

data class AgentBluetoothDeviceObservation(
    val address: String,
    val name: String?,
    val bondState: String,
    val deviceType: String
)

data class AgentBluetoothDiscoveryResult(
    val devices: List<AgentBluetoothDeviceObservation>,
    val completed: Boolean,
    val timedOut: Boolean,
    val observedAtEpochMillis: Long
)

data class AgentSystemHandoffResult(
    val launched: Boolean,
    val action: String,
    val completed: Boolean = false
)

data class AgentNfcStatusSnapshot(
    val supported: Boolean,
    val enabled: Boolean,
    val secureNfcSupported: Boolean,
    val secureNfcEnabled: Boolean,
    val observedAtEpochMillis: Long
)

data class AgentInstalledAppSummary(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val enabled: Boolean,
    val systemApp: Boolean,
    val launchable: Boolean
)

data class AgentPackageDetail(
    val packageName: String,
    val visible: Boolean,
    val label: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val enabled: Boolean? = null,
    val systemApp: Boolean? = null,
    val launchable: Boolean? = null,
    val firstInstallTimeEpochMillis: Long? = null,
    val lastUpdateTimeEpochMillis: Long? = null,
    val targetSdk: Int? = null,
    val minSdk: Int? = null,
    val requestedPermissions: List<String> = emptyList()
)

class AgentHardwareNativeException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
    val details: AgentNativeJsonObject = emptyMap(),
    cause: Throwable? = null
) : RuntimeException(message, cause)

interface AgentHardwarePlatformFacade {
    val implementationId: String

    fun availability(capability: AgentHardwareCapability): AgentNativeToolAvailability
    fun battery(): AgentBatterySnapshot
    fun power(): AgentPowerSnapshot
    fun storage(): AgentStorageSnapshot
    fun network(): AgentNetworkSnapshot

    fun foregroundLocation(
        timeoutMillis: Long,
        cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE
    ): AgentForegroundLocationSnapshot

    fun sensors(limit: Int): List<AgentSensorDescriptor>

    fun sampleSensor(
        type: String,
        timeoutMillis: Long,
        cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE
    ): AgentSensorSample

    fun setFlashlight(enabled: Boolean): AgentFlashlightRequestResult
    fun bluetoothStatus(): AgentBluetoothStatusSnapshot

    fun discoverBluetooth(
        timeoutMillis: Long,
        limit: Int,
        cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE
    ): AgentBluetoothDiscoveryResult

    fun handoffBluetoothPairing(): AgentSystemHandoffResult
    fun nfcStatus(): AgentNfcStatusSnapshot
    fun installedApps(query: String, limit: Int): List<AgentInstalledAppSummary>
    fun packageDetail(packageName: String): AgentPackageDetail
}

class AgentAndroidHardwarePlatformFacade(
    context: Context,
    private val clock: AgentNativeClock = AgentNativeClock.SYSTEM
) : AgentHardwarePlatformFacade {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    override val implementationId: String = "signalasi.android.hardware_facade"

    override fun availability(capability: AgentHardwareCapability): AgentNativeToolAvailability {
        val unavailable = when (capability) {
            AgentHardwareCapability.BATTERY ->
                serviceUnavailable(BatteryManager::class.java, "Battery service is unavailable")

            AgentHardwareCapability.POWER ->
                serviceUnavailable(PowerManager::class.java, "Power service is unavailable")

            AgentHardwareCapability.STORAGE ->
                if (appContext.filesDir == null) "App-private storage is unavailable" else null

            AgentHardwareCapability.NETWORK ->
                serviceUnavailable(ConnectivityManager::class.java, "Connectivity service is unavailable")
                    ?: permissionSetup(Manifest.permission.ACCESS_NETWORK_STATE)

            AgentHardwareCapability.FOREGROUND_LOCATION ->
                serviceUnavailable(LocationManager::class.java, "Location service is unavailable")
                    ?: permissionSetupAny(
                        setOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    )
                    ?: foregroundSetup("Location is limited to a single foreground request")

            AgentHardwareCapability.SENSOR_LIST ->
                serviceUnavailable(SensorManager::class.java, "Sensor service is unavailable")

            AgentHardwareCapability.SENSOR_SAMPLE ->
                serviceUnavailable(SensorManager::class.java, "Sensor service is unavailable")
                    ?: foregroundSetup("Sensor sampling is limited to a single foreground sample")

            AgentHardwareCapability.FLASHLIGHT ->
                when {
                    !packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) ->
                        "This device does not report a camera flash"

                    else -> serviceUnavailable(CameraManager::class.java, "Camera service is unavailable")
                        ?: permissionSetup(Manifest.permission.CAMERA)
                        ?: foregroundSetup("Flashlight control requires the app to be foreground")
                }

            AgentHardwareCapability.BLUETOOTH_STATUS ->
                bluetoothUnavailable()
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permissionSetup(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        permissionSetup(Manifest.permission.BLUETOOTH)
                    }

            AgentHardwareCapability.BLUETOOTH_DISCOVERY ->
                bluetoothUnavailable()
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permissionSetup(Manifest.permission.BLUETOOTH_SCAN)
                            ?: permissionSetup(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        permissionSetup(Manifest.permission.BLUETOOTH_ADMIN)
                            ?: permissionSetup(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    ?: foregroundSetup("Bluetooth discovery is limited to one foreground scan")

            AgentHardwareCapability.BLUETOOTH_PAIRING_HANDOFF ->
                bluetoothUnavailable()
                    ?: foregroundSetup("Bluetooth pairing is a foreground Android Settings handoff")

            AgentHardwareCapability.NFC_STATUS ->
                if (!packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)) {
                    "This device does not report NFC hardware"
                } else {
                    permissionSetup(Manifest.permission.NFC)
                }

            AgentHardwareCapability.INSTALLED_APPS,
            AgentHardwareCapability.PACKAGE_DETAIL -> null
        }
        return when {
            unavailable == null -> AgentNativeToolAvailability.AVAILABLE
            unavailable.startsWith(SETUP_PREFIX) -> AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
                unavailable.removePrefix(SETUP_PREFIX)
            )
            else -> AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.UNAVAILABLE,
                unavailable
            )
        }
    }

    override fun battery(): AgentBatterySnapshot {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: throw AgentHardwareNativeException("battery_unavailable", "Android did not return battery state")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) {
            ((level.toDouble() / scale.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else {
            null
        }
        val statusCode = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = statusCode == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusCode == BatteryManager.BATTERY_STATUS_FULL
        val chargeCounter = appContext.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeUnless { it == Int.MIN_VALUE }
            ?.toLong()
        return AgentBatterySnapshot(
            percent = percent,
            charging = charging,
            plugged = pluggedName(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)),
            status = batteryStatusName(statusCode),
            health = batteryHealthName(
                intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ),
            temperatureCelsius = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                .takeUnless { it == Int.MIN_VALUE }?.div(10.0),
            voltageMillivolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
                .takeUnless { it == Int.MIN_VALUE || it < 0 },
            chargeCounterMicroampHours = chargeCounter,
            observedAtEpochMillis = clock.nowEpochMillis()
        )
    }

    override fun power(): AgentPowerSnapshot {
        val manager = appContext.getSystemService(PowerManager::class.java)
            ?: throw AgentHardwareNativeException("power_unavailable", "Power service is unavailable")
        return AgentPowerSnapshot(
            interactive = manager.isInteractive,
            powerSaveMode = manager.isPowerSaveMode,
            deviceIdleMode = manager.isDeviceIdleMode,
            ignoringBatteryOptimizations = manager.isIgnoringBatteryOptimizations(appContext.packageName),
            observedAtEpochMillis = clock.nowEpochMillis()
        )
    }

    override fun storage(): AgentStorageSnapshot {
        val stats = StatFs(appContext.filesDir.absolutePath)
        val total = stats.totalBytes.coerceAtLeast(0L)
        val available = stats.availableBytes.coerceIn(0L, total)
        val lowThreshold = maxOf(64L * 1_048_576L, total / 20L)
        return AgentStorageSnapshot(
            scope = "app_private_volume",
            totalBytes = total,
            availableBytes = available,
            lowStorage = available <= lowThreshold,
            observedAtEpochMillis = clock.nowEpochMillis()
        )
    }

    override fun network(): AgentNetworkSnapshot {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
            ?: throw AgentHardwareNativeException("network_unavailable", "Connectivity service is unavailable")
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val transports = buildList {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("bluetooth")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) == true) add("wifi_aware")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN) == true
            ) {
                add("lowpan")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_USB) == true
            ) {
                add("usb")
            }
        }
        return AgentNetworkSnapshot(
            connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = manager.isActiveNetworkMetered,
            roaming = capabilities != null &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
            transports = transports,
            downstreamKbps = capabilities?.linkDownstreamBandwidthKbps?.coerceAtLeast(0) ?: 0,
            upstreamKbps = capabilities?.linkUpstreamBandwidthKbps?.coerceAtLeast(0) ?: 0,
            observedAtEpochMillis = clock.nowEpochMillis()
        )
    }

    override fun foregroundLocation(
        timeoutMillis: Long,
        cancellationToken: AgentNativeToolCancellationToken
    ): AgentForegroundLocationSnapshot {
        requireForeground("location")
        val manager = appContext.getSystemService(LocationManager::class.java)
            ?: throw AgentHardwareNativeException("location_unavailable", "Location service is unavailable")
        val enabledProviders = manager.getProviders(true)
        val provider = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).firstOrNull(enabledProviders::contains) ?: enabledProviders.firstOrNull()
        ?: throw AgentHardwareNativeException(
            "location_provider_disabled",
            "No enabled Android location provider is available",
            retryable = true
        )
        val now = clock.nowEpochMillis()
        val recent = enabledProviders.asSequence()
            .mapNotNull { enabled -> runCatching { manager.getLastKnownLocation(enabled) }.getOrNull() }
            .filter { candidate -> now - candidate.time in 0..MAX_CACHED_LOCATION_AGE_MILLIS }
            .maxByOrNull(Location::getTime)
        val location = recent ?: currentLocation(manager, provider, timeoutMillis, cancellationToken)
            ?: throw AgentHardwareNativeException(
                "location_fix_unavailable",
                "A foreground location fix was not available within the requested time",
                retryable = true
            )
        return location.toSnapshot(clock.nowEpochMillis(), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "current_fix"
        } else {
            "single_update"
        })
    }

    override fun sensors(limit: Int): List<AgentSensorDescriptor> {
        val manager = appContext.getSystemService(SensorManager::class.java)
            ?: throw AgentHardwareNativeException("sensors_unavailable", "Sensor service is unavailable")
        return manager.getSensorList(Sensor.TYPE_ALL)
            .sortedWith(compareBy<Sensor>({ it.type }, { it.name }))
            .take(limit)
            .map(::sensorDescriptor)
    }

    override fun sampleSensor(
        type: String,
        timeoutMillis: Long,
        cancellationToken: AgentNativeToolCancellationToken
    ): AgentSensorSample {
        requireForeground("sensor sampling")
        val androidType = SENSOR_TYPES[type]
            ?: throw AgentHardwareNativeException(
                "unsupported_sensor_type",
                "Sensor type is not in the foreground-safe allowlist",
                details = mapOf("type" to type)
            )
        val manager = appContext.getSystemService(SensorManager::class.java)
            ?: throw AgentHardwareNativeException("sensors_unavailable", "Sensor service is unavailable")
        val sensor = manager.getDefaultSensor(androidType)
            ?: throw AgentHardwareNativeException(
                "sensor_unavailable",
                "The requested sensor is not available on this device",
                details = mapOf("type" to type)
            )
        val thread = HandlerThread("SignalASI-OneShotSensor").apply { start() }
        val sample = AtomicReference<AgentSensorSample?>()
        val latch = CountDownLatch(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                sample.compareAndSet(
                    null,
                    AgentSensorSample(
                        type = type,
                        androidType = event.sensor.type,
                        values = event.values.take(MAX_SENSOR_VALUES).map(Float::toDouble),
                        accuracy = event.accuracy,
                        observedAtEpochMillis = clock.nowEpochMillis()
                    )
                )
                latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val cancellation = cancellationToken.invokeOnCancellation(latch::countDown)
        try {
            val registered = manager.registerListener(
                listener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                Handler(thread.looper)
            )
            if (!registered) {
                throw AgentHardwareNativeException(
                    "sensor_registration_failed",
                    "Android rejected the one-shot sensor listener",
                    retryable = true
                )
            }
            awaitBounded(latch, timeoutMillis, cancellationToken)
            cancellationToken.throwIfCancelled()
            return sample.get() ?: throw AgentHardwareNativeException(
                "sensor_sample_timeout",
                "No sensor sample arrived within the requested time",
                retryable = true
            )
        } finally {
            manager.unregisterListener(listener)
            cancellation.dispose()
            thread.quitSafely()
        }
    }

    override fun setFlashlight(enabled: Boolean): AgentFlashlightRequestResult {
        requireForeground("flashlight control")
        val manager = appContext.getSystemService(CameraManager::class.java)
            ?: throw AgentHardwareNativeException("flashlight_unavailable", "Camera service is unavailable")
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw AgentHardwareNativeException("flashlight_unavailable", "No controllable camera flash was found")
        try {
            manager.setTorchMode(cameraId, enabled)
        } catch (error: SecurityException) {
            throw AgentHardwareNativeException(
                "camera_permission_denied",
                "Android denied camera access for flashlight control",
                cause = error
            )
        } catch (error: Exception) {
            throw AgentHardwareNativeException(
                "flashlight_request_failed",
                error.message ?: "Android rejected the flashlight request",
                retryable = true,
                cause = error
            )
        }
        return AgentFlashlightRequestResult(
            requestedEnabled = enabled,
            requestAccepted = true,
            stateVerified = false
        )
    }

    override fun bluetoothStatus(): AgentBluetoothStatusSnapshot {
        val adapter = bluetoothAdapter()
        return try {
            AgentBluetoothStatusSnapshot(
                supported = true,
                enabled = adapter.isEnabled,
                discovering = adapter.isDiscovering,
                bondedDeviceCount = adapter.bondedDevices?.size,
                observedAtEpochMillis = clock.nowEpochMillis()
            )
        } catch (error: SecurityException) {
            throw AgentHardwareNativeException(
                "bluetooth_permission_denied",
                "Android denied access to Bluetooth status",
                cause = error
            )
        }
    }

    override fun discoverBluetooth(
        timeoutMillis: Long,
        limit: Int,
        cancellationToken: AgentNativeToolCancellationToken
    ): AgentBluetoothDiscoveryResult {
        requireForeground("Bluetooth discovery")
        val adapter = bluetoothAdapter()
        if (!adapter.isEnabled) {
            throw AgentHardwareNativeException(
                "bluetooth_disabled",
                "Bluetooth is disabled; SignalASI will not change that setting",
                retryable = true
            )
        }
        val found = linkedMapOf<String, AgentBluetoothDeviceObservation>()
        val finished = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return
                        val observation = runCatching { deviceObservation(device) }.getOrNull() ?: return
                        synchronized(found) {
                            if (found.size < limit || found.containsKey(observation.address)) {
                                found[observation.address] = observation
                            }
                        }
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finished.countDown()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        val cancellation = cancellationToken.invokeOnCancellation(finished::countDown)
        var completed = false
        try {
            runCatching { adapter.cancelDiscovery() }
            if (!adapter.startDiscovery()) {
                throw AgentHardwareNativeException(
                    "bluetooth_discovery_failed",
                    "Android did not start Bluetooth discovery",
                    retryable = true
                )
            }
            completed = awaitBounded(finished, timeoutMillis, cancellationToken)
            cancellationToken.throwIfCancelled()
        } catch (error: SecurityException) {
            throw AgentHardwareNativeException(
                "bluetooth_permission_denied",
                "Android denied foreground Bluetooth discovery",
                cause = error
            )
        } finally {
            runCatching { adapter.cancelDiscovery() }
            runCatching { appContext.unregisterReceiver(receiver) }
            cancellation.dispose()
        }
        val devices = synchronized(found) { found.values.take(limit) }
        return AgentBluetoothDiscoveryResult(
            devices = devices,
            completed = completed,
            timedOut = !completed,
            observedAtEpochMillis = clock.nowEpochMillis()
        )
    }

    override fun handoffBluetoothPairing(): AgentSystemHandoffResult {
        requireForeground("Bluetooth pairing handoff")
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            AgentSystemHandoffResult(true, Settings.ACTION_BLUETOOTH_SETTINGS, completed = false)
        } catch (error: Exception) {
            throw AgentHardwareNativeException(
                "bluetooth_settings_unavailable",
                "Android Bluetooth settings could not be opened",
                cause = error
            )
        }
    }

    override fun nfcStatus(): AgentNfcStatusSnapshot {
        val adapter = NfcAdapter.getDefaultAdapter(appContext)
            ?: return AgentNfcStatusSnapshot(false, false, false, false, clock.nowEpochMillis())
        return try {
            val secureSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && adapter.isSecureNfcSupported
            AgentNfcStatusSnapshot(
                supported = true,
                enabled = adapter.isEnabled,
                secureNfcSupported = secureSupported,
                secureNfcEnabled = secureSupported && adapter.isSecureNfcEnabled,
                observedAtEpochMillis = clock.nowEpochMillis()
            )
        } catch (error: SecurityException) {
            throw AgentHardwareNativeException(
                "nfc_permission_denied",
                "Android denied access to NFC status",
                cause = error
            )
        }
    }

    override fun installedApps(query: String, limit: Int): List<AgentInstalledAppSummary> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .plus(
                listOfNotNull(
                    runCatching { packageManager.getApplicationInfo(appContext.packageName, 0) }.getOrNull()
                ).asSequence()
            )
            .distinctBy(ApplicationInfo::packageName)
            .mapNotNull { info -> runCatching { appSummary(info) }.getOrNull() }
            .filter { app ->
                normalizedQuery.isBlank() ||
                    app.packageName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                    app.label.lowercase(Locale.ROOT).contains(normalizedQuery)
            }
            .sortedWith(compareBy<AgentInstalledAppSummary>({ it.label.lowercase(Locale.ROOT) }, { it.packageName }))
            .take(limit)
            .toList()
    }

    override fun packageDetail(packageName: String): AgentPackageDetail {
        val info = try {
            packageInfo(packageName, PackageManager.GET_PERMISSIONS.toLong())
        } catch (_: PackageManager.NameNotFoundException) {
            return AgentPackageDetail(packageName = packageName, visible = false)
        }
        val application = info.applicationInfo
            ?: return AgentPackageDetail(packageName = packageName, visible = true)
        return AgentPackageDetail(
            packageName = packageName,
            visible = true,
            label = application.loadLabel(packageManager).toString(),
            versionName = info.versionName,
            versionCode = info.compatLongVersionCode(),
            enabled = application.enabled,
            systemApp = application.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            launchable = packageManager.getLaunchIntentForPackage(packageName) != null,
            firstInstallTimeEpochMillis = info.firstInstallTime.takeIf { it >= 0L },
            lastUpdateTimeEpochMillis = info.lastUpdateTime.takeIf { it >= 0L },
            targetSdk = application.targetSdkVersion,
            minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) application.minSdkVersion else null,
            requestedPermissions = info.requestedPermissions.orEmpty()
                .distinct()
                .sorted()
                .take(MAX_PACKAGE_PERMISSIONS)
        )
    }

    private fun currentLocation(
        manager: LocationManager,
        provider: String,
        timeoutMillis: Long,
        cancellationToken: AgentNativeToolCancellationToken
    ): Location? {
        val result = AtomicReference<Location?>()
        val latch = CountDownLatch(1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            val registration = cancellationToken.invokeOnCancellation {
                signal.cancel()
                latch.countDown()
            }
            try {
                manager.getCurrentLocation(provider, signal, { command -> command.run() }) { location ->
                    result.set(location)
                    latch.countDown()
                }
                awaitBounded(latch, timeoutMillis, cancellationToken)
                cancellationToken.throwIfCancelled()
                return result.get()
            } finally {
                signal.cancel()
                registration.dispose()
            }
        }

        val thread = HandlerThread("SignalASI-OneShotLocation").apply { start() }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                result.compareAndSet(null, location)
                latch.countDown()
            }

            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        val registration = cancellationToken.invokeOnCancellation(latch::countDown)
        try {
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, listener, thread.looper)
            awaitBounded(latch, timeoutMillis, cancellationToken)
            cancellationToken.throwIfCancelled()
            return result.get()
        } finally {
            manager.removeUpdates(listener)
            registration.dispose()
            thread.quitSafely()
        }
    }

    private fun sensorDescriptor(sensor: Sensor): AgentSensorDescriptor = AgentSensorDescriptor(
        type = SENSOR_TYPE_NAMES[sensor.type] ?: "android_type_${sensor.type}",
        androidType = sensor.type,
        name = sensor.name,
        vendor = sensor.vendor,
        version = sensor.version,
        maximumRange = sensor.maximumRange.toDouble(),
        resolution = sensor.resolution.toDouble(),
        powerMilliamps = sensor.power.toDouble(),
        reportingMode = when (sensor.reportingMode) {
            Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
            Sensor.REPORTING_MODE_ON_CHANGE -> "on_change"
            Sensor.REPORTING_MODE_ONE_SHOT -> "one_shot"
            Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "special_trigger"
            else -> "unknown"
        },
        wakeUp = sensor.isWakeUpSensor,
        runtimePermission = null
    )

    private fun appSummary(application: ApplicationInfo): AgentInstalledAppSummary {
        val info = packageInfo(application.packageName, 0)
        return AgentInstalledAppSummary(
            packageName = application.packageName,
            label = application.loadLabel(packageManager).toString(),
            versionName = info.versionName,
            versionCode = info.compatLongVersionCode(),
            enabled = application.enabled,
            systemApp = application.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            launchable = packageManager.getLaunchIntentForPackage(application.packageName) != null
        )
    }

    private fun packageInfo(packageName: String, flags: Long): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags.toInt())
        }

    private fun PackageInfo.compatLongVersionCode(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private fun Location.toSnapshot(observedAt: Long, source: String) = AgentForegroundLocationSnapshot(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy.toDouble().coerceAtLeast(0.0),
        altitudeMeters = altitude.takeIf { hasAltitude() },
        bearingDegrees = bearing.toDouble().takeIf { hasBearing() },
        speedMetersPerSecond = speed.toDouble().takeIf { hasSpeed() },
        provider = provider.orEmpty(),
        fixAtEpochMillis = time.coerceAtLeast(0L),
        observedAtEpochMillis = observedAt,
        source = source
    )

    private fun bluetoothAdapter(): BluetoothAdapter =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw AgentHardwareNativeException("bluetooth_unavailable", "Bluetooth hardware is unavailable")

    private fun deviceObservation(device: BluetoothDevice): AgentBluetoothDeviceObservation =
        AgentBluetoothDeviceObservation(
            address = device.address.orEmpty(),
            name = device.name,
            bondState = when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> "bonded"
                BluetoothDevice.BOND_BONDING -> "bonding"
                BluetoothDevice.BOND_NONE -> "none"
                else -> "unknown"
            },
            deviceType = when (device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
                BluetoothDevice.DEVICE_TYPE_LE -> "low_energy"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                else -> "unknown"
            }
        )

    private fun serviceUnavailable(type: Class<*>, reason: String): String? =
        if (appContext.getSystemService(type) == null) reason else null

    private fun bluetoothUnavailable(): String? =
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            "This device does not report Bluetooth hardware"
        } else if (appContext.getSystemService(BluetoothManager::class.java)?.adapter == null) {
            "Bluetooth adapter is unavailable"
        } else {
            null
        }

    private fun permissionSetup(permission: String): String? = when {
        permission !in declaredPermissions() -> "$SETUP_PREFIX$permission is not declared by the app"
        appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED ->
            "$SETUP_PREFIX$permission has not been granted"
        else -> null
    }

    private fun permissionSetupAny(permissions: Set<String>): String? {
        val declared = declaredPermissions()
        if (permissions.none(declared::contains)) {
            return "${SETUP_PREFIX}None of ${permissions.sorted().joinToString()} are declared by the app"
        }
        if (permissions.none { appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            return "${SETUP_PREFIX}A foreground location runtime permission has not been granted"
        }
        return null
    }

    private fun declaredPermissions(): Set<String> = runCatching {
        packageInfo(appContext.packageName, PackageManager.GET_PERMISSIONS.toLong())
            .requestedPermissions.orEmpty().toSet()
    }.getOrDefault(emptySet())

    private fun foregroundSetup(reason: String): String? =
        if (isAppForeground()) null else "$SETUP_PREFIX$reason"

    private fun requireForeground(operation: String) {
        if (!isAppForeground()) {
            throw AgentHardwareNativeException(
                "foreground_required",
                "${operation.replaceFirstChar { it.titlecase(Locale.ROOT) }} is not allowed from the background",
                retryable = true
            )
        }
    }

    private fun isAppForeground(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun awaitBounded(
        latch: CountDownLatch,
        timeoutMillis: Long,
        cancellationToken: AgentNativeToolCancellationToken
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            cancellationToken.throwIfCancelled()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return latch.count == 0L
            if (latch.await(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100L)), TimeUnit.NANOSECONDS)) {
                return true
            }
        }
    }

    private fun AgentNativeToolCancellationToken.throwIfCancelled() {
        if (isCancellationRequested) throw AgentNativeToolCancelledException()
    }

    private fun pluggedName(code: Int): String = when (code) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
        0 -> "none"
        else -> "unknown"
    }

    private fun batteryStatusName(code: Int): String = when (code) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    private fun batteryHealthName(code: Int): String = when (code) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "unspecified_failure"
        else -> "unknown"
    }

    companion object {
        private const val SETUP_PREFIX = "setup:"
        private const val MAX_CACHED_LOCATION_AGE_MILLIS = 5 * 60 * 1_000L
        private const val MAX_SENSOR_VALUES = 16
        private const val MAX_PACKAGE_PERMISSIONS = 128

        private val SENSOR_TYPES = linkedMapOf(
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "ambient_temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
            "game_rotation_vector" to Sensor.TYPE_GAME_ROTATION_VECTOR,
            "gravity" to Sensor.TYPE_GRAVITY,
            "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "light" to Sensor.TYPE_LIGHT,
            "linear_acceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
            "magnetic_field" to Sensor.TYPE_MAGNETIC_FIELD,
            "pressure" to Sensor.TYPE_PRESSURE,
            "proximity" to Sensor.TYPE_PROXIMITY,
            "relative_humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
            "rotation_vector" to Sensor.TYPE_ROTATION_VECTOR
        )
        private val SENSOR_TYPE_NAMES = SENSOR_TYPES.entries.associate { (name, type) -> type to name }
    }
}

object AgentHardwareNativeTools {
    const val BATTERY_STATUS = "signalasi.hardware.battery.status"
    const val POWER_STATUS = "signalasi.hardware.power.status"
    const val STORAGE_STATUS = "signalasi.hardware.storage.status"
    const val NETWORK_STATUS = "signalasi.hardware.network.status"
    const val LOCATION_FOREGROUND_READ = "signalasi.hardware.location.foreground.read"
    const val SENSORS_LIST = "signalasi.hardware.sensors.list"
    const val SENSOR_SAMPLE = "signalasi.hardware.sensor.sample"
    const val FLASHLIGHT_SET = "signalasi.hardware.flashlight.set"
    const val BLUETOOTH_STATUS = "signalasi.hardware.bluetooth.status"
    const val BLUETOOTH_DISCOVERY_FOREGROUND = "signalasi.hardware.bluetooth.discovery.foreground"
    const val BLUETOOTH_PAIRING_HANDOFF = "signalasi.hardware.bluetooth.pairing.handoff"
    const val NFC_STATUS = "signalasi.hardware.nfc.status"
    const val INSTALLED_APPS_LIST = "signalasi.hardware.apps.installed.list"
    const val PACKAGE_DETAIL = "signalasi.hardware.apps.package.detail"

    const val ACCESS_NETWORK_STATE_PERMISSION = "android.permission.ACCESS_NETWORK_STATE"
    const val ACCESS_COARSE_LOCATION_PERMISSION = "android.permission.ACCESS_COARSE_LOCATION"
    const val ACCESS_FINE_LOCATION_PERMISSION = "android.permission.ACCESS_FINE_LOCATION"
    const val BODY_SENSORS_PERMISSION = "android.permission.BODY_SENSORS"
    const val CAMERA_PERMISSION = "android.permission.CAMERA"
    const val BLUETOOTH_CONNECT_PERMISSION = "android.permission.BLUETOOTH_CONNECT"
    const val BLUETOOTH_SCAN_PERMISSION = "android.permission.BLUETOOTH_SCAN"
    const val NFC_PERMISSION = "android.permission.NFC"

    const val FOREGROUND_LOCATION_CONSENT = "signalasi.consent.location.foreground_once"
    const val SENSOR_SAMPLE_CONSENT = "signalasi.consent.sensor.foreground_once"
    const val FLASHLIGHT_CONTROL_CONSENT = "signalasi.consent.flashlight.control"
    const val BLUETOOTH_DISCOVERY_CONSENT = "signalasi.consent.bluetooth.discovery.foreground_once"
    const val BLUETOOTH_PAIRING_HANDOFF_CONSENT = "signalasi.consent.bluetooth.pairing_handoff"
    const val INSTALLED_APPS_CONSENT = "signalasi.consent.installed_apps.query_visible"
    const val PACKAGE_DETAIL_CONSENT = "signalasi.consent.package_detail.query_visible"

    const val MAX_TOOL_TIMEOUT_MILLIS = 15_000L
    const val MAX_LOCATION_TIMEOUT_MILLIS = 10_000L
    const val MAX_SENSOR_TIMEOUT_MILLIS = 5_000L
    const val MAX_BLUETOOTH_DISCOVERY_MILLIS = 15_000L
    const val MAX_SENSOR_RESULTS = 64
    const val MAX_SENSOR_VALUES = 16
    const val MAX_BLUETOOTH_RESULTS = 32
    const val MAX_INSTALLED_APP_RESULTS = 100
    const val MAX_PACKAGE_PERMISSIONS = 128

    private const val VERSION = "1.0.0"
    private const val EXECUTOR_ID = "signalasi.android.hardware_native"
    private const val MAX_NAME_CHARS = 160
    private const val MAX_PACKAGE_NAME_CHARS = 255
    private const val MAX_QUERY_CHARS = 128

    val toolIds: Set<String> = linkedSetOf(
        BATTERY_STATUS,
        POWER_STATUS,
        STORAGE_STATUS,
        NETWORK_STATUS,
        LOCATION_FOREGROUND_READ,
        SENSORS_LIST,
        SENSOR_SAMPLE,
        FLASHLIGHT_SET,
        BLUETOOTH_STATUS,
        BLUETOOTH_DISCOVERY_FOREGROUND,
        BLUETOOTH_PAIRING_HANDOFF,
        NFC_STATUS,
        INSTALLED_APPS_LIST,
        PACKAGE_DETAIL
    )

    fun androidFacade(
        context: Context,
        clock: AgentNativeClock = AgentNativeClock.SYSTEM
    ): AgentHardwarePlatformFacade = AgentAndroidHardwarePlatformFacade(context, clock)

    fun createRegistry(
        platform: AgentHardwarePlatformFacade,
        clock: AgentNativeClock = AgentNativeClock.SYSTEM
    ): AgentNativeToolRegistry = AgentNativeToolRegistry(clock).registerAll(definitions(platform))

    fun definitions(platform: AgentHardwarePlatformFacade): List<AgentNativeToolDefinition> = listOf(
        batteryDefinition(platform),
        powerDefinition(platform),
        storageDefinition(platform),
        networkDefinition(platform),
        locationDefinition(platform),
        sensorListDefinition(platform),
        sensorSampleDefinition(platform),
        flashlightDefinition(platform),
        bluetoothStatusDefinition(platform),
        bluetoothDiscoveryDefinition(platform),
        bluetoothPairingHandoffDefinition(platform),
        nfcStatusDefinition(platform),
        installedAppsDefinition(platform),
        packageDetailDefinition(platform)
    )

    private fun batteryDefinition(platform: AgentHardwarePlatformFacade) = definition(
        platform = platform,
        capability = AgentHardwareCapability.BATTERY,
        descriptor = descriptor(
            id = BATTERY_STATUS,
            title = "Read battery status",
            description = "Reads bounded app-visible battery, charging, temperature, voltage, and health signals.",
            outputSchema = objectSchema(
                properties = mapOf(
                    "percent" to nullable(AgentNativeJsonSchema.integer(0, 100)),
                    "charging" to AgentNativeJsonSchema.boolean(),
                    "plugged" to AgentNativeJsonSchema.string(
                        enumValues = listOf("none", "ac", "usb", "wireless", "dock", "unknown")
                    ),
                    "status" to AgentNativeJsonSchema.string(
                        enumValues = listOf("charging", "discharging", "full", "not_charging", "unknown")
                    ),
                    "health" to AgentNativeJsonSchema.string(
                        enumValues = listOf(
                            "good",
                            "cold",
                            "dead",
                            "overheat",
                            "over_voltage",
                            "unspecified_failure",
                            "unknown"
                        )
                    ),
                    "temperature_celsius" to nullable(AgentNativeJsonSchema.number(-100, 200)),
                    "voltage_millivolts" to nullable(AgentNativeJsonSchema.integer(0)),
                    "charge_counter_microamp_hours" to nullable(AgentNativeJsonSchema.integer()),
                    "observed_at_epoch_ms" to epochSchema(),
                    "scope" to AgentNativeJsonSchema.string(enumValues = listOf("app_visible"))
                ),
                required = setOf(
                    "percent",
                    "charging",
                    "plugged",
                    "status",
                    "health",
                    "temperature_celsius",
                    "voltage_millivolts",
                    "charge_counter_microamp_hours",
                    "observed_at_epoch_ms",
                    "scope"
                )
            ),
            capabilities = setOf("battery.read", "battery.app_visible")
        ),
        execute = {
            val snapshot = platform.battery()
            requireRange(snapshot.percent, 0, 100, "battery percent")
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "percent" to snapshot.percent,
                    "charging" to snapshot.charging,
                    "plugged" to snapshot.plugged.enumValue(
                        setOf("none", "ac", "usb", "wireless", "dock", "unknown"),
                        "unknown"
                    ),
                    "status" to snapshot.status.enumValue(
                        setOf("charging", "discharging", "full", "not_charging", "unknown"),
                        "unknown"
                    ),
                    "health" to snapshot.health.enumValue(
                        setOf(
                            "good",
                            "cold",
                            "dead",
                            "overheat",
                            "over_voltage",
                            "unspecified_failure",
                            "unknown"
                        ),
                        "unknown"
                    ),
                    "temperature_celsius" to snapshot.temperatureCelsius?.finite("battery temperature"),
                    "voltage_millivolts" to snapshot.voltageMillivolts?.coerceAtLeast(0),
                    "charge_counter_microamp_hours" to snapshot.chargeCounterMicroampHours,
                    "observed_at_epoch_ms" to snapshot.observedAtEpochMillis.coerceAtLeast(0L),
                    "scope" to "app_visible"
                ),
                message = "Battery ${snapshot.percent}% / charging=${snapshot.charging} / status=${snapshot.status}"
            )
        }
    )

    private fun powerDefinition(platform: AgentHardwarePlatformFacade) = definition(
        platform = platform,
        capability = AgentHardwareCapability.POWER,
        descriptor = descriptor(
            id = POWER_STATUS,
            title = "Read power policy status",
            description = "Reads Android interactive, battery-saver, idle, and app battery-optimization state without changing settings.",
            outputSchema = objectSchema(
                properties = mapOf(
                    "interactive" to AgentNativeJsonSchema.boolean(),
                    "power_save_mode" to AgentNativeJsonSchema.boolean(),
                    "device_idle_mode" to AgentNativeJsonSchema.boolean(),
                    "ignoring_battery_optimizations" to AgentNativeJsonSchema.boolean(),
                    "observed_at_epoch_ms" to epochSchema(),
                    "settings_changed" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf(
                    "interactive",
                    "power_save_mode",
                    "device_idle_mode",
                    "ignoring_battery_optimizations",
                    "observed_at_epoch_ms",
                    "settings_changed"
                )
            ),
            capabilities = setOf("power.read", "settings.read_only")
        ),
        execute = {
            val snapshot = platform.power()
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "interactive" to snapshot.interactive,
                    "power_save_mode" to snapshot.powerSaveMode,
                    "device_idle_mode" to snapshot.deviceIdleMode,
                    "ignoring_battery_optimizations" to snapshot.ignoringBatteryOptimizations,
                    "observed_at_epoch_ms" to snapshot.observedAtEpochMillis.coerceAtLeast(0L),
                    "settings_changed" to false
                ),
                message = "Power policy status read"
            )
        }
    )

    private fun storageDefinition(platform: AgentHardwarePlatformFacade) = definition(
        platform = platform,
        capability = AgentHardwareCapability.STORAGE,
        descriptor = descriptor(
            id = STORAGE_STATUS,
            title = "Read app storage volume status",
            description = "Reads capacity for the app-private storage volume; it does not enumerate shared files.",
            outputSchema = objectSchema(
                properties = mapOf(
                    "scope" to AgentNativeJsonSchema.string(enumValues = listOf("app_private_volume")),
                    "total_bytes" to AgentNativeJsonSchema.integer(0),
                    "available_bytes" to AgentNativeJsonSchema.integer(0),
                    "used_bytes" to AgentNativeJsonSchema.integer(0),
                    "low_storage" to AgentNativeJsonSchema.boolean(),
                    "observed_at_epoch_ms" to epochSchema()
                ),
                required = setOf(
                    "scope",
                    "total_bytes",
                    "available_bytes",
                    "used_bytes",
                    "low_storage",
                    "observed_at_epoch_ms"
                )
            ),
            capabilities = setOf("storage.app_private_volume.read", "storage.no_file_enumeration")
        ),
        execute = {
            val snapshot = platform.storage()
            if (snapshot.totalBytes < 0L || snapshot.availableBytes !in 0L..snapshot.totalBytes) {
                throw AgentHardwareNativeException(
                    "invalid_platform_result",
                    "Platform storage values were outside valid bounds"
                )
            }
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "scope" to "app_private_volume",
                    "total_bytes" to snapshot.totalBytes,
                    "available_bytes" to snapshot.availableBytes,
                    "used_bytes" to snapshot.totalBytes - snapshot.availableBytes,
                    "low_storage" to snapshot.lowStorage,
                    "observed_at_epoch_ms" to snapshot.observedAtEpochMillis.coerceAtLeast(0L)
                ),
                message = "App-private storage volume status read"
            )
        }
    )

    private fun networkDefinition(platform: AgentHardwarePlatformFacade) = definition(
        platform = platform,
        capability = AgentHardwareCapability.NETWORK,
        descriptor = descriptor(
            id = NETWORK_STATUS,
            title = "Read active network status",
            description = "Reads bounded active-network capabilities without SSID, address, subscriber, or traffic capture.",
            outputSchema = objectSchema(
                properties = mapOf(
                    "connected" to AgentNativeJsonSchema.boolean(),
                    "validated" to AgentNativeJsonSchema.boolean(),
                    "metered" to AgentNativeJsonSchema.boolean(),
                    "roaming" to AgentNativeJsonSchema.boolean(),
                    "transports" to AgentNativeJsonSchema.array(
                        AgentNativeJsonSchema.string(
                            enumValues = listOf(
                                "wifi",
                                "cellular",
                                "ethernet",
                                "vpn",
                                "bluetooth",
                                "wifi_aware",
                                "lowpan",
                                "usb"
                            )
                        ),
                        maxItems = 8
                    ),
                    "downstream_kbps" to AgentNativeJsonSchema.integer(0),
                    "upstream_kbps" to AgentNativeJsonSchema.integer(0),
                    "observed_at_epoch_ms" to epochSchema(),
                    "identifiers_included" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf(
                    "connected",
                    "validated",
                    "metered",
                    "roaming",
                    "transports",
                    "downstream_kbps",
                    "upstream_kbps",
                    "observed_at_epoch_ms",
                    "identifiers_included"
                )
            ),
            capabilities = setOf("network.state.read", "network.no_identifiers", "network.no_traffic_capture"),
            permissions = listOf(
                permission(
                    ACCESS_NETWORK_STATE_PERMISSION,
                    "Read network state",
                    "Normal Android permission used only for current connectivity capabilities."
                )
            )
        ),
        execute = {
            val snapshot = platform.network()
            val allowed = setOf("wifi", "cellular", "ethernet", "vpn", "bluetooth", "wifi_aware", "lowpan", "usb")
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "connected" to snapshot.connected,
                    "validated" to snapshot.validated,
                    "metered" to snapshot.metered,
                    "roaming" to snapshot.roaming,
                    "transports" to snapshot.transports.distinct().filter(allowed::contains).take(8),
                    "downstream_kbps" to snapshot.downstreamKbps.coerceAtLeast(0),
                    "upstream_kbps" to snapshot.upstreamKbps.coerceAtLeast(0),
                    "observed_at_epoch_ms" to snapshot.observedAtEpochMillis.coerceAtLeast(0L),
                    "identifiers_included" to false
                ),
                message = "Active network capabilities read"
            )
        }
    )

    private fun locationDefinition(platform: AgentHardwarePlatformFacade) = definition(
        platform = platform,
        capability = AgentHardwareCapability.FOREGROUND_LOCATION,
        descriptor = descriptor(
            id = LOCATION_FOREGROUND_READ,
            title = "Read one foreground location",
            description = "Requests one bounded foreground location fix and unregisters immediately; background tracking is not supported.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "timeout_ms" to AgentNativeJsonSchema.integer(1_000, MAX_LOCATION_TIMEOUT_MILLIS)
                )
            ),
            outputSchema = objectSchema(
                properties = mapOf(
                    "latitude" to AgentNativeJsonSchema.number(-90, 90),
                    "longitude" to AgentNativeJsonSchema.number(-180, 180),
                    "accuracy_meters" to AgentNativeJsonSchema.number(0, 1_000_000),
                    "altitude_meters" to nullable(AgentNativeJsonSchema.number()),
                    "bearing_degrees" to nullable(AgentNativeJsonSchema.number(0, 360)),
                    "speed_meters_per_second" to nullable(AgentNativeJsonSchema.number(0)),
                    "provider" to AgentNativeJsonSchema.string(maxLength = 64),
                    "fix_at_epoch_ms" to epochSchema(),
                    "observed_at_epoch_ms" to epochSchema(),
                    "age_ms" to AgentNativeJsonSchema.integer(0),
                    "source" to AgentNativeJsonSchema.string(maxLength = 32),
                    "capture_mode" to AgentNativeJsonSchema.string(enumValues = listOf("single_foreground_fix")),
                    "background_capture" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf(
                    "latitude",
                    "longitude",
                    "accuracy_meters",
                    "altitude_meters",
                    "bearing_degrees",
                    "speed_meters_per_second",
                    "provider",
                    "fix_at_epoch_ms",
                    "observed_at_epoch_ms",
                    "age_ms",
                    "source",
                    "capture_mode",
                    "background_capture"
                )
            ),
            risk = AgentNativeToolRisk.HIGH,
            capabilities = setOf("location.foreground.single_fix", "location.no_background_tracking"),
            permissions = listOf(
                permission(
                    ACCESS_COARSE_LOCATION_PERMISSION,
                    "Approximate foreground location",
                    "Android runtime permission for a single foreground fix."
                ),
                permission(
                    ACCESS_FINE_LOCATION_PERMISSION,
                    "Precise foreground location",
                    "Optional Android runtime permission; Android may still return approximate location.",
                    required = false
                )
            ),
            consents = listOf(FOREGROUND_LOCATION_CONSENT),
            timeoutMillis = MAX_LOCATION_TIMEOUT_MILLIS,
            idempotency = AgentNativeToolIdempotency.NON_IDEMPOTENT
        ),
        execute = { invocation ->
            val timeout = invocation.input.long("timeout_ms", MAX_LOCATION_TIMEOUT_MILLIS)
                .coerceAtMost(invocation.remainingTimeMillis.coerceAtLeast(1L))
            val snapshot = platform.foregroundLocation(timeout, invocation.cancellationToken)
            val latitude = snapshot.latitude.finite("latitude")
            val longitude = snapshot.longitude.finite("longitude")
            val accuracy = snapshot.accuracyMeters.finite("location accuracy")
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || accuracy < 0.0) {
                throw AgentHardwareNativeException(
                    "invalid_platform_result",
                    "Platform location values were outside geographic bounds"
                )
            }
            val bearing = snapshot.bearingDegrees?.finite("bearing")?.let { ((it % 360.0) + 360.0) % 360.0 }
            val observedAt = snapshot.observedAtEpochMillis.coerceAtLeast(0L)
            val fixAt = snapshot.fixAtEpochMillis.coerceAtLeast(0L)
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "latitude" to latitude,
                    "longitude" to longitude,
                    "accuracy_meters" to accuracy,
                    "altitude_meters" to snapshot.altitudeMeters?.finite("altitude"),
                    "bearing_degrees" to bearing,
                    "speed_meters_per_second" to snapshot.speedMetersPerSecond
                        ?.finite("speed")?.coerceAtLeast(0.0),
                    "provider" to snapshot.provider.take(64),
                    "fix_at_epoch_ms" to fixAt,
                    "observed_at_epoch_ms" to observedAt,
                    "age_ms" to (observedAt - fixAt).coerceAtLeast(0L),
                    "source" to snapshot.source.take(32),
                    "capture_mode" to "single_foreground_fix",
                    "background_capture" to false
                ),
                message = "Single foreground location fix read",
                metadata = mapOf("retained_listener" to false)
            )
        }
    )

    private fun sensorListDefinition(platform: AgentHardwarePlatformFacade) = definition(
        platform = platform,
        capability = AgentHardwareCapability.SENSOR_LIST,
        descriptor = descriptor(
            id = SENSORS_LIST,
            title = "List device sensors",
            description = "Lists bounded Android sensor metadata without registering listeners or collecting samples.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "limit" to AgentNativeJsonSchema.integer(1, MAX_SENSOR_RESULTS.toLong())
                )
            ),
            outputSchema = objectSchema(
                properties = mapOf(
                    "sensors" to AgentNativeJsonSchema.array(sensorDescriptorSchema(), maxItems = MAX_SENSOR_RESULTS),
                    "result_count" to AgentNativeJsonSchema.integer(0, MAX_SENSOR_RESULTS.toLong()),
                    "truncated" to AgentNativeJsonSchema.boolean(),
                    "sampling_started" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf("sensors", "result_count", "truncated", "sampling_started")
            ),
            capabilities = setOf("sensors.metadata.read", "sensors.no_sampling")
        ),
        execute = { invocation ->
            val limit = invocation.input.int("limit", MAX_SENSOR_RESULTS)
            val values = platform.sensors(limit + 1)
            val bounded = values.take(limit).map { it.boundedValue() }
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "sensors" to bounded,
                    "result_count" to bounded.size,
                    "truncated" to (values.size > limit),
                    "sampling_started" to false
                ),
                message = "Device sensor metadata listed"
            )
        }
    )

    private fun sensorSampleDefinition(platform: AgentHardwarePlatformFacade) = definition(
        platform = platform,
        capability = AgentHardwareCapability.SENSOR_SAMPLE,
        descriptor = descriptor(
            id = SENSOR_SAMPLE,
            title = "Read one foreground sensor sample",
            description = "Reads one sample from a non-health sensor allowlist and unregisters immediately; streaming and background capture are not supported.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "type" to AgentNativeJsonSchema.string(
                        enumValues = FOREGROUND_SENSOR_TYPES
                    ),
                    "timeout_ms" to AgentNativeJsonSchema.integer(250, MAX_SENSOR_TIMEOUT_MILLIS)
                ),
                required = setOf("type")
            ),
            outputSchema = objectSchema(
                properties = mapOf(
                    "type" to AgentNativeJsonSchema.string(enumValues = FOREGROUND_SENSOR_TYPES),
                    "android_type" to AgentNativeJsonSchema.integer(1),
                    "values" to AgentNativeJsonSchema.array(
                        AgentNativeJsonSchema.number(),
                        minItems = 1,
                        maxItems = MAX_SENSOR_VALUES
                    ),
                    "accuracy" to AgentNativeJsonSchema.integer(),
                    "observed_at_epoch_ms" to epochSchema(),
                    "capture_mode" to AgentNativeJsonSchema.string(enumValues = listOf("single_foreground_sample")),
                    "background_capture" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf(
                    "type",
                    "android_type",
                    "values",
                    "accuracy",
                    "observed_at_epoch_ms",
                    "capture_mode",
                    "background_capture"
                )
            ),
            risk = AgentNativeToolRisk.MEDIUM,
            capabilities = setOf(
                "sensors.foreground.single_sample",
                "sensors.non_health_allowlist",
                "sensors.no_background_stream"
            ),
            permissions = listOf(
                permission(
                    BODY_SENSORS_PERMISSION,
                    "Body sensors",
                    "Not used by this tool; body and health sensor types are explicitly outside its allowlist.",
                    required = false
                )
            ),
            consents = listOf(SENSOR_SAMPLE_CONSENT),
            timeoutMillis = MAX_SENSOR_TIMEOUT_MILLIS,
            idempotency = AgentNativeToolIdempotency.NON_IDEMPOTENT
        ),
        execute = { invocation ->
            val type = invocation.input.string("type")
            val timeout = invocation.input.long("timeout_ms", MAX_SENSOR_TIMEOUT_MILLIS)
                .coerceAtMost(invocation.remainingTimeMillis.coerceAtLeast(1L))
            val sample = platform.sampleSensor(type, timeout, invocation.cancellationToken)
            if (sample.type != type || sample.androidType <= 0 || sample.values.isEmpty()) {
                throw AgentHardwareNativeException(
                    "invalid_platform_result",
                    "Platform returned a mismatched or empty sensor sample"
                )
            }
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "type" to type,
                    "android_type" to sample.androidType,
                    "values" to sample.values.take(MAX_SENSOR_VALUES).map { it.finite("sensor value") },
                    "accuracy" to sample.accuracy,
                    "observed_at_epoch_ms" to sample.observedAtEpochMillis.coerceAtLeast(0L),
                    "capture_mode" to "single_foreground_sample",
                    "background_capture" to false
                ),
                message = "Single foreground sensor sample read",
                metadata = mapOf("retained_listener" to false)
            )
        }
    )

    private fun flashlightDefinition(platform: AgentHardwarePlatformFacade) = definition(
        platform = platform,
        capability = AgentHardwareCapability.FLASHLIGHT,
        descriptor = descriptor(
            id = FLASHLIGHT_SET,
            title = "Request flashlight state",
            description = "Requests an explicit flashlight state after consent; it does not change camera or system settings silently.",
            inputSchema = objectSchema(
                properties = mapOf("enabled" to AgentNativeJsonSchema.boolean()),
                required = setOf("enabled")
            ),
            outputSchema = objectSchema(
                properties = mapOf(
                    "requested_enabled" to AgentNativeJsonSchema.boolean(),
                    "request_accepted" to AgentNativeJsonSchema.boolean(),
                    "state_verified" to AgentNativeJsonSchema.boolean(),
                    "settings_changed" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf(
                    "requested_enabled",
                    "request_accepted",
                    "state_verified",
                    "settings_changed"
                )
            ),
            risk = AgentNativeToolRisk.MEDIUM,
            capabilities = setOf("flashlight.explicit_control", "flashlight.no_camera_capture"),
            permissions = listOf(
                permission(
                    CAMERA_PERMISSION,
                    "Camera hardware access",
                    "Android runtime permission used only for the torch API; no image is captured."
                )
            ),
            consents = listOf(FLASHLIGHT_CONTROL_CONSENT),
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT
        ),
        execute = { invocation ->
            val enabled = invocation.input.boolean("enabled")
            val result = platform.setFlashlight(enabled)
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "requested_enabled" to enabled,
                    "request_accepted" to result.requestAccepted,
                    "state_verified" to false,
                    "settings_changed" to false
                ),
                message = "Flashlight request submitted",
                metadata = mapOf(
                    "camera_capture" to false,
                    "continuous_state_guarantee" to false
                )
            )
        }
    )

    private fun bluetoothStatusDefinition(platform: AgentHardwarePlatformFacade) = definition(
        platform = platform,
        capability = AgentHardwareCapability.BLUETOOTH_STATUS,
        descriptor = descriptor(
            id = BLUETOOTH_STATUS,
            title = "Read Bluetooth status",
            description = "Reads adapter state and a count of app-visible bonded devices without addresses or names.",
            outputSchema = objectSchema(
                properties = mapOf(
                    "supported" to AgentNativeJsonSchema.boolean(),
                    "enabled" to AgentNativeJsonSchema.boolean(),
                    "discovering" to AgentNativeJsonSchema.boolean(),
                    "bonded_device_count" to nullable(AgentNativeJsonSchema.integer(0)),
                    "device_identifiers_included" to AgentNativeJsonSchema.boolean(),
                    "observed_at_epoch_ms" to epochSchema()
                ),
                required = setOf(
                    "supported",
                    "enabled",
                    "discovering",
                    "bonded_device_count",
                    "device_identifiers_included",
                    "observed_at_epoch_ms"
                )
            ),
            capabilities = setOf("bluetooth.status.read", "bluetooth.no_device_identifiers"),
            permissions = listOf(
                permission(
                    BLUETOOTH_CONNECT_PERMISSION,
                    "Nearby Bluetooth devices",
                    "Android 12+ runtime permission for adapter and bonded-device status."
                )
            )
        ),
        execute = {
            val snapshot = platform.bluetoothStatus()
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "supported" to snapshot.supported,
                    "enabled" to snapshot.enabled,
                    "discovering" to snapshot.discovering,
                    "bonded_device_count" to snapshot.bondedDeviceCount?.coerceAtLeast(0),
                    "device_identifiers_included" to false,
                    "observed_at_epoch_ms" to snapshot.observedAtEpochMillis.coerceAtLeast(0L)
                ),
                message = "Bluetooth adapter status read"
            )
        }
    )

    private fun bluetoothDiscoveryDefinition(platform: AgentHardwarePlatformFacade) = definition(
        platform = platform,
        capability = AgentHardwareCapability.BLUETOOTH_DISCOVERY,
        descriptor = descriptor(
            id = BLUETOOTH_DISCOVERY_FOREGROUND,
            title = "Discover nearby Bluetooth devices once",
            description = "Runs one bounded foreground discovery, returns at most 32 observations, then cancels and unregisters.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "timeout_ms" to AgentNativeJsonSchema.integer(1_000, MAX_BLUETOOTH_DISCOVERY_MILLIS),
                    "limit" to AgentNativeJsonSchema.integer(1, MAX_BLUETOOTH_RESULTS.toLong())
                )
            ),
            outputSchema = objectSchema(
                properties = mapOf(
                    "devices" to AgentNativeJsonSchema.array(
                        bluetoothDeviceSchema(),
                        maxItems = MAX_BLUETOOTH_RESULTS
                    ),
                    "result_count" to AgentNativeJsonSchema.integer(0, MAX_BLUETOOTH_RESULTS.toLong()),
                    "completed" to AgentNativeJsonSchema.boolean(),
                    "timed_out" to AgentNativeJsonSchema.boolean(),
                    "truncated" to AgentNativeJsonSchema.boolean(),
                    "observed_at_epoch_ms" to epochSchema(),
                    "capture_mode" to AgentNativeJsonSchema.string(enumValues = listOf("single_foreground_discovery")),
                    "background_capture" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf(
                    "devices",
                    "result_count",
                    "completed",
                    "timed_out",
                    "truncated",
                    "observed_at_epoch_ms",
                    "capture_mode",
                    "background_capture"
                )
            ),
            risk = AgentNativeToolRisk.HIGH,
            capabilities = setOf(
                "bluetooth.discovery.foreground_bounded",
                "bluetooth.discovery.no_background_receiver"
            ),
            permissions = listOf(
                permission(
                    BLUETOOTH_SCAN_PERMISSION,
                    "Discover nearby Bluetooth devices",
                    "Android 12+ runtime permission for one foreground discovery."
                ),
                permission(
                    BLUETOOTH_CONNECT_PERMISSION,
                    "Read discovered Bluetooth identity",
                    "Android 12+ runtime permission for bounded device names and addresses."
                )
            ),
            consents = listOf(BLUETOOTH_DISCOVERY_CONSENT),
            timeoutMillis = MAX_BLUETOOTH_DISCOVERY_MILLIS,
            idempotency = AgentNativeToolIdempotency.NON_IDEMPOTENT
        ),
        execute = { invocation ->
            val limit = invocation.input.int("limit", MAX_BLUETOOTH_RESULTS)
            val timeout = invocation.input.long("timeout_ms", MAX_BLUETOOTH_DISCOVERY_MILLIS)
                .coerceAtMost(invocation.remainingTimeMillis.coerceAtLeast(1L))
            val discovery = platform.discoverBluetooth(
                timeout,
                minOf(limit + 1, MAX_BLUETOOTH_RESULTS + 1),
                invocation.cancellationToken
            )
            val devices = discovery.devices
                .distinctBy { it.address.uppercase(Locale.ROOT) }
                .take(limit)
                .map { it.boundedValue() }
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "devices" to devices,
                    "result_count" to devices.size,
                    "completed" to discovery.completed,
                    "timed_out" to discovery.timedOut,
                    "truncated" to (discovery.devices.size > limit),
                    "observed_at_epoch_ms" to discovery.observedAtEpochMillis.coerceAtLeast(0L),
                    "capture_mode" to "single_foreground_discovery",
                    "background_capture" to false
                ),
                message = "Foreground Bluetooth discovery ended",
                metadata = mapOf("receiver_unregistered" to true, "discovery_cancelled_after_call" to true)
            )
        }
    )

    private fun bluetoothPairingHandoffDefinition(platform: AgentHardwarePlatformFacade) = definition(
        platform = platform,
        capability = AgentHardwareCapability.BLUETOOTH_PAIRING_HANDOFF,
        descriptor = descriptor(
            id = BLUETOOTH_PAIRING_HANDOFF,
            title = "Open Bluetooth pairing settings",
            description = "Hands pairing to Android Settings; SignalASI neither pairs silently nor claims the user completed pairing.",
            outputSchema = objectSchema(
                properties = mapOf(
                    "launched" to AgentNativeJsonSchema.boolean(),
                    "action" to AgentNativeJsonSchema.string(maxLength = 160),
                    "completed" to AgentNativeJsonSchema.boolean(),
                    "settings_changed_by_signalasi" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf("launched", "action", "completed", "settings_changed_by_signalasi")
            ),
            risk = AgentNativeToolRisk.HIGH,
            capabilities = setOf(
                "bluetooth.pairing.system_ui_handoff",
                "bluetooth.no_silent_pairing",
                "settings.user_controlled"
            ),
            consents = listOf(BLUETOOTH_PAIRING_HANDOFF_CONSENT),
            idempotency = AgentNativeToolIdempotency.NON_IDEMPOTENT
        ),
        execute = {
            val result = platform.handoffBluetoothPairing()
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "launched" to result.launched,
                    "action" to result.action.take(160),
                    "completed" to false,
                    "settings_changed_by_signalasi" to false
                ),
                message = "Bluetooth pairing handed off to Android Settings",
                metadata = mapOf("completion_semantics" to "handoff_only")
            )
        }
    )

    private fun nfcStatusDefinition(platform: AgentHardwarePlatformFacade) = definition(
        platform = platform,
        capability = AgentHardwareCapability.NFC_STATUS,
        descriptor = descriptor(
            id = NFC_STATUS,
            title = "Read NFC capability status",
            description = "Reads NFC and secure-NFC capability flags without polling tags, transactions, or changing settings.",
            outputSchema = objectSchema(
                properties = mapOf(
                    "supported" to AgentNativeJsonSchema.boolean(),
                    "enabled" to AgentNativeJsonSchema.boolean(),
                    "secure_nfc_supported" to AgentNativeJsonSchema.boolean(),
                    "secure_nfc_enabled" to AgentNativeJsonSchema.boolean(),
                    "tag_capture_started" to AgentNativeJsonSchema.boolean(),
                    "settings_changed" to AgentNativeJsonSchema.boolean(),
                    "observed_at_epoch_ms" to epochSchema()
                ),
                required = setOf(
                    "supported",
                    "enabled",
                    "secure_nfc_supported",
                    "secure_nfc_enabled",
                    "tag_capture_started",
                    "settings_changed",
                    "observed_at_epoch_ms"
                )
            ),
            capabilities = setOf("nfc.status.read", "nfc.no_tag_capture", "nfc.no_transaction"),
            permissions = listOf(
                permission(
                    NFC_PERMISSION,
                    "NFC state",
                    "Normal Android permission used only to read adapter capability and enabled state."
                )
            )
        ),
        execute = {
            val snapshot = platform.nfcStatus()
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "supported" to snapshot.supported,
                    "enabled" to snapshot.enabled,
                    "secure_nfc_supported" to snapshot.secureNfcSupported,
                    "secure_nfc_enabled" to snapshot.secureNfcEnabled,
                    "tag_capture_started" to false,
                    "settings_changed" to false,
                    "observed_at_epoch_ms" to snapshot.observedAtEpochMillis.coerceAtLeast(0L)
                ),
                message = "NFC capability status read"
            )
        }
    )

    private fun installedAppsDefinition(platform: AgentHardwarePlatformFacade) = definition(
        platform = platform,
        capability = AgentHardwareCapability.INSTALLED_APPS,
        descriptor = descriptor(
            id = INSTALLED_APPS_LIST,
            title = "List query-visible installed apps",
            description = "Lists at most 100 launcher/query-visible packages; Android filtering means this is never a complete device census.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "query" to AgentNativeJsonSchema.string(maxLength = MAX_QUERY_CHARS),
                    "limit" to AgentNativeJsonSchema.integer(1, MAX_INSTALLED_APP_RESULTS.toLong())
                )
            ),
            outputSchema = objectSchema(
                properties = mapOf(
                    "apps" to AgentNativeJsonSchema.array(
                        installedAppSchema(),
                        maxItems = MAX_INSTALLED_APP_RESULTS
                    ),
                    "result_count" to AgentNativeJsonSchema.integer(0, MAX_INSTALLED_APP_RESULTS.toLong()),
                    "truncated" to AgentNativeJsonSchema.boolean(),
                    "visibility_scope" to AgentNativeJsonSchema.string(enumValues = listOf("query_visible_only")),
                    "complete_device_census" to AgentNativeJsonSchema.boolean()
                ),
                required = setOf(
                    "apps",
                    "result_count",
                    "truncated",
                    "visibility_scope",
                    "complete_device_census"
                )
            ),
            risk = AgentNativeToolRisk.MEDIUM,
            capabilities = setOf("packages.query_visible.list", "packages.partial_visibility"),
            consents = listOf(INSTALLED_APPS_CONSENT)
        ),
        execute = { invocation ->
            val query = invocation.input.string("query", "").trim().take(MAX_QUERY_CHARS)
            val limit = invocation.input.int("limit", MAX_INSTALLED_APP_RESULTS)
            val values = platform.installedApps(query, limit + 1)
            val apps = values
                .distinctBy(AgentInstalledAppSummary::packageName)
                .take(limit)
                .map { it.boundedValue() }
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "apps" to apps,
                    "result_count" to apps.size,
                    "truncated" to (values.size > limit),
                    "visibility_scope" to "query_visible_only",
                    "complete_device_census" to false
                ),
                message = "Query-visible installed apps listed",
                metadata = mapOf("android_package_visibility_filtered" to true)
            )
        }
    )

    private fun packageDetailDefinition(platform: AgentHardwarePlatformFacade) = definition(
        platform = platform,
        capability = AgentHardwareCapability.PACKAGE_DETAIL,
        descriptor = descriptor(
            id = PACKAGE_DETAIL,
            title = "Read query-visible package detail",
            description = "Reads bounded metadata for one package if Android makes it visible; not-visible does not prove not-installed.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "package_name" to AgentNativeJsonSchema.string(
                        minLength = 1,
                        maxLength = MAX_PACKAGE_NAME_CHARS,
                        pattern = "^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*$"
                    )
                ),
                required = setOf("package_name")
            ),
            outputSchema = packageDetailSchema(),
            risk = AgentNativeToolRisk.MEDIUM,
            capabilities = setOf("packages.query_visible.detail", "packages.partial_visibility"),
            consents = listOf(PACKAGE_DETAIL_CONSENT)
        ),
        execute = { invocation ->
            val packageName = invocation.input.string("package_name")
            val detail = platform.packageDetail(packageName)
            if (detail.packageName != packageName) {
                throw AgentHardwareNativeException(
                    "invalid_platform_result",
                    "Platform returned detail for a different package"
                )
            }
            AgentNativeToolExecutionResult.success(
                output = detail.boundedValue() + mapOf(
                    "visibility_scope" to "query_visible_only",
                    "not_visible_means_not_installed" to false
                ),
                message = if (detail.visible) {
                    "Query-visible package detail read"
                } else {
                    "Package is not visible to SignalASI; installation state is unknown"
                },
                metadata = mapOf("android_package_visibility_filtered" to true)
            )
        }
    )

    private fun definition(
        platform: AgentHardwarePlatformFacade,
        capability: AgentHardwareCapability,
        descriptor: AgentNativeToolDescriptor,
        execute: (AgentNativeToolInvocation) -> AgentNativeToolExecutionResult
    ) = AgentNativeToolDefinition(
        descriptor = descriptor.copy(availability = platform.availability(capability)),
        executor = AgentNativeToolExecutor { invocation ->
            executePlatform {
                invocation.checkpoint()
                execute(invocation)
            }
        },
        executorId = EXECUTOR_ID,
        provenanceMetadata = mapOf(
            "implementation" to platform.implementationId,
            "platform" to "android",
            "result_policy" to "bounded-v1",
            "background_capture" to "false",
            "silent_settings_changes" to "false"
        ),
        availabilityProvider = AgentNativeToolAvailabilityProvider {
            platform.availability(capability)
        }
    )

    private fun descriptor(
        id: String,
        title: String,
        description: String,
        inputSchema: AgentNativeJsonSchema = objectSchema(),
        outputSchema: AgentNativeJsonSchema,
        risk: AgentNativeToolRisk = AgentNativeToolRisk.LOW,
        capabilities: Set<String>,
        permissions: List<AgentNativePermissionRequirement> = emptyList(),
        consents: List<String> = emptyList(),
        timeoutMillis: Long = MAX_TOOL_TIMEOUT_MILLIS,
        idempotency: AgentNativeToolIdempotency = AgentNativeToolIdempotency.IDEMPOTENT
    ) = AgentNativeToolDescriptor(
        id = id,
        version = VERSION,
        title = title,
        description = description,
        location = AgentNativeToolLocation.ANDROID_SYSTEM,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        risk = risk,
        capabilities = capabilities,
        requiredPermissions = permissions,
        requiredConsents = consents.map(::consent),
        timeoutMillis = timeoutMillis,
        idempotency = idempotency,
        availability = AgentNativeToolAvailability.AVAILABLE
    )

    private fun permission(
        id: String,
        title: String,
        description: String,
        required: Boolean = true
    ) = AgentNativePermissionRequirement(id, title, description, required)

    private fun consent(id: String) = AgentNativeConsentRequirement(
        id = id,
        title = when (id) {
            FOREGROUND_LOCATION_CONSENT -> "Read location once while foreground"
            SENSOR_SAMPLE_CONSENT -> "Read one sensor sample while foreground"
            FLASHLIGHT_CONTROL_CONSENT -> "Control flashlight for this request"
            BLUETOOTH_DISCOVERY_CONSENT -> "Discover nearby Bluetooth devices once"
            BLUETOOTH_PAIRING_HANDOFF_CONSENT -> "Open Android Bluetooth pairing settings"
            INSTALLED_APPS_CONSENT -> "List query-visible installed apps"
            PACKAGE_DETAIL_CONSENT -> "Read query-visible package detail"
            else -> id
        },
        description = "Authorizes this invocation only; it does not authorize background capture or silent settings changes."
    )

    private fun executePlatform(block: () -> AgentNativeToolExecutionResult): AgentNativeToolExecutionResult = try {
        block()
    } catch (error: AgentHardwareNativeException) {
        AgentNativeToolExecutionResult.failure(error.code, error.message, error.retryable, error.details)
    } catch (error: AgentNativeToolCancelledException) {
        throw error
    } catch (error: AgentNativeToolTimeoutException) {
        throw error
    } catch (error: Exception) {
        AgentNativeToolExecutionResult.failure(
            "native_adapter_failed",
            error.message ?: "Android hardware adapter failed",
            details = mapOf("exception" to error::class.java.name)
        )
    }

    private fun AgentSensorDescriptor.boundedValue(): AgentNativeJsonObject = mapOf(
        "type" to type.take(64),
        "android_type" to androidType.coerceAtLeast(0),
        "name" to name.take(MAX_NAME_CHARS),
        "vendor" to vendor.take(MAX_NAME_CHARS),
        "version" to version,
        "maximum_range" to maximumRange.finite("sensor maximum range"),
        "resolution" to resolution.finite("sensor resolution"),
        "power_milliamps" to powerMilliamps.finite("sensor power").coerceAtLeast(0.0),
        "reporting_mode" to reportingMode.enumValue(
            setOf("continuous", "on_change", "one_shot", "special_trigger", "unknown"),
            "unknown"
        ),
        "wake_up" to wakeUp,
        "runtime_permission" to runtimePermission?.take(160)
    )

    private fun AgentBluetoothDeviceObservation.boundedValue(): AgentNativeJsonObject {
        val normalizedAddress = address.uppercase(Locale.ROOT)
        if (!BLUETOOTH_ADDRESS.matches(normalizedAddress)) {
            throw AgentHardwareNativeException(
                "invalid_platform_result",
                "Platform returned an invalid Bluetooth device address"
            )
        }
        return mapOf(
            "address" to normalizedAddress,
            "name" to name?.take(MAX_NAME_CHARS),
            "bond_state" to bondState.enumValue(setOf("none", "bonding", "bonded", "unknown"), "unknown"),
            "device_type" to deviceType.enumValue(
                setOf("classic", "low_energy", "dual", "unknown"),
                "unknown"
            )
        )
    }

    private fun AgentInstalledAppSummary.boundedValue(): AgentNativeJsonObject {
        requirePackageName(packageName)
        return mapOf(
            "package_name" to packageName,
            "label" to label.take(MAX_NAME_CHARS),
            "version_name" to versionName?.take(MAX_NAME_CHARS),
            "version_code" to versionCode.coerceAtLeast(0L),
            "enabled" to enabled,
            "system_app" to systemApp,
            "launchable" to launchable
        )
    }

    private fun AgentPackageDetail.boundedValue(): AgentNativeJsonObject {
        requirePackageName(packageName)
        return mapOf(
            "package_name" to packageName,
            "visible" to visible,
            "label" to label?.take(MAX_NAME_CHARS),
            "version_name" to versionName?.take(MAX_NAME_CHARS),
            "version_code" to versionCode?.coerceAtLeast(0L),
            "enabled" to enabled,
            "system_app" to systemApp,
            "launchable" to launchable,
            "first_install_time_epoch_ms" to firstInstallTimeEpochMillis?.coerceAtLeast(0L),
            "last_update_time_epoch_ms" to lastUpdateTimeEpochMillis?.coerceAtLeast(0L),
            "target_sdk" to targetSdk?.coerceAtLeast(1),
            "min_sdk" to minSdk?.coerceAtLeast(1),
            "requested_permissions" to requestedPermissions
                .distinct()
                .map { it.take(160) }
                .sorted()
                .take(MAX_PACKAGE_PERMISSIONS)
        )
    }

    private fun sensorDescriptorSchema() = objectSchema(
        properties = mapOf(
            "type" to AgentNativeJsonSchema.string(maxLength = 64),
            "android_type" to AgentNativeJsonSchema.integer(0),
            "name" to AgentNativeJsonSchema.string(maxLength = MAX_NAME_CHARS),
            "vendor" to AgentNativeJsonSchema.string(maxLength = MAX_NAME_CHARS),
            "version" to AgentNativeJsonSchema.integer(),
            "maximum_range" to AgentNativeJsonSchema.number(),
            "resolution" to AgentNativeJsonSchema.number(),
            "power_milliamps" to AgentNativeJsonSchema.number(0),
            "reporting_mode" to AgentNativeJsonSchema.string(
                enumValues = listOf("continuous", "on_change", "one_shot", "special_trigger", "unknown")
            ),
            "wake_up" to AgentNativeJsonSchema.boolean(),
            "runtime_permission" to nullable(AgentNativeJsonSchema.string(maxLength = 160))
        ),
        required = setOf(
            "type",
            "android_type",
            "name",
            "vendor",
            "version",
            "maximum_range",
            "resolution",
            "power_milliamps",
            "reporting_mode",
            "wake_up",
            "runtime_permission"
        )
    )

    private fun bluetoothDeviceSchema() = objectSchema(
        properties = mapOf(
            "address" to AgentNativeJsonSchema.string(
                maxLength = 17,
                pattern = "^[0-9A-F]{2}(?::[0-9A-F]{2}){5}$"
            ),
            "name" to nullable(AgentNativeJsonSchema.string(maxLength = MAX_NAME_CHARS)),
            "bond_state" to AgentNativeJsonSchema.string(
                enumValues = listOf("none", "bonding", "bonded", "unknown")
            ),
            "device_type" to AgentNativeJsonSchema.string(
                enumValues = listOf("classic", "low_energy", "dual", "unknown")
            )
        ),
        required = setOf("address", "name", "bond_state", "device_type")
    )

    private fun installedAppSchema() = objectSchema(
        properties = mapOf(
            "package_name" to packageNameSchema(),
            "label" to AgentNativeJsonSchema.string(maxLength = MAX_NAME_CHARS),
            "version_name" to nullable(AgentNativeJsonSchema.string(maxLength = MAX_NAME_CHARS)),
            "version_code" to AgentNativeJsonSchema.integer(0),
            "enabled" to AgentNativeJsonSchema.boolean(),
            "system_app" to AgentNativeJsonSchema.boolean(),
            "launchable" to AgentNativeJsonSchema.boolean()
        ),
        required = setOf(
            "package_name",
            "label",
            "version_name",
            "version_code",
            "enabled",
            "system_app",
            "launchable"
        )
    )

    private fun packageDetailSchema() = objectSchema(
        properties = mapOf(
            "package_name" to packageNameSchema(),
            "visible" to AgentNativeJsonSchema.boolean(),
            "label" to nullable(AgentNativeJsonSchema.string(maxLength = MAX_NAME_CHARS)),
            "version_name" to nullable(AgentNativeJsonSchema.string(maxLength = MAX_NAME_CHARS)),
            "version_code" to nullable(AgentNativeJsonSchema.integer(0)),
            "enabled" to nullable(AgentNativeJsonSchema.boolean()),
            "system_app" to nullable(AgentNativeJsonSchema.boolean()),
            "launchable" to nullable(AgentNativeJsonSchema.boolean()),
            "first_install_time_epoch_ms" to nullable(epochSchema()),
            "last_update_time_epoch_ms" to nullable(epochSchema()),
            "target_sdk" to nullable(AgentNativeJsonSchema.integer(1)),
            "min_sdk" to nullable(AgentNativeJsonSchema.integer(1)),
            "requested_permissions" to AgentNativeJsonSchema.array(
                AgentNativeJsonSchema.string(maxLength = 160),
                maxItems = MAX_PACKAGE_PERMISSIONS
            ),
            "visibility_scope" to AgentNativeJsonSchema.string(enumValues = listOf("query_visible_only")),
            "not_visible_means_not_installed" to AgentNativeJsonSchema.boolean()
        ),
        required = setOf(
            "package_name",
            "visible",
            "label",
            "version_name",
            "version_code",
            "enabled",
            "system_app",
            "launchable",
            "first_install_time_epoch_ms",
            "last_update_time_epoch_ms",
            "target_sdk",
            "min_sdk",
            "requested_permissions",
            "visibility_scope",
            "not_visible_means_not_installed"
        )
    )

    private fun packageNameSchema() = AgentNativeJsonSchema.string(
        minLength = 1,
        maxLength = MAX_PACKAGE_NAME_CHARS,
        pattern = "^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*$"
    )

    private fun epochSchema() = AgentNativeJsonSchema.integer(0)

    private fun objectSchema(
        properties: Map<String, AgentNativeJsonSchema> = emptyMap(),
        required: Set<String> = emptySet()
    ) = AgentNativeJsonSchema.objectSchema(
        properties = properties,
        required = required,
        additionalProperties = false
    )

    private fun nullable(schema: AgentNativeJsonSchema): AgentNativeJsonSchema {
        val document = LinkedHashMap(schema.document)
        val type = document["type"]
        document["type"] = when (type) {
            is String -> listOf(type, "null")
            is Iterable<*> -> type.filterIsInstance<String>().plus("null").distinct()
            else -> listOf("null")
        }
        return AgentNativeJsonSchema(document)
    }

    private fun AgentNativeJsonObject.string(name: String, default: String? = null): String =
        (this[name] as? String) ?: default
        ?: throw AgentHardwareNativeException("invalid_input", "$name must be a string")

    private fun AgentNativeJsonObject.long(name: String, default: Long): Long =
        (this[name] as? Number)?.toLong() ?: default

    private fun AgentNativeJsonObject.int(name: String, default: Int): Int =
        (this[name] as? Number)?.toInt() ?: default

    private fun AgentNativeJsonObject.boolean(name: String): Boolean =
        this[name] as? Boolean
            ?: throw AgentHardwareNativeException("invalid_input", "$name must be a boolean")

    private fun Double.finite(label: String): Double {
        if (!isFinite()) {
            throw AgentHardwareNativeException("invalid_platform_result", "$label was not finite")
        }
        return this
    }

    private fun String.enumValue(allowed: Set<String>, fallback: String): String =
        lowercase(Locale.ROOT).takeIf(allowed::contains) ?: fallback

    private fun requireRange(value: Int?, minimum: Int, maximum: Int, label: String) {
        if (value != null && value !in minimum..maximum) {
            throw AgentHardwareNativeException("invalid_platform_result", "$label was outside valid bounds")
        }
    }

    private fun requirePackageName(value: String) {
        if (value.length !in 1..MAX_PACKAGE_NAME_CHARS || !PACKAGE_NAME.matches(value)) {
            throw AgentHardwareNativeException(
                "invalid_platform_result",
                "Platform returned an invalid package name"
            )
        }
    }

    private val FOREGROUND_SENSOR_TYPES = listOf(
        "accelerometer",
        "ambient_temperature",
        "game_rotation_vector",
        "gravity",
        "gyroscope",
        "light",
        "linear_acceleration",
        "magnetic_field",
        "pressure",
        "proximity",
        "relative_humidity",
        "rotation_vector"
    )
    private val BLUETOOTH_ADDRESS = Regex("^[0-9A-F]{2}(?::[0-9A-F]{2}){5}$")
    private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*$")
}
