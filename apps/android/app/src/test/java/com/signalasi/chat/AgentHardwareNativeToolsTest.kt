package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHardwareNativeToolsTest {
    @Test
    fun definitionsExposeStableBoundedPolicyCompleteTools() {
        val platform = FakeHardwarePlatform()
        val definitions = AgentHardwareNativeTools.definitions(platform)

        assertEquals(AgentHardwareNativeTools.toolIds, definitions.map { it.descriptor.id }.toSet())
        assertEquals(AgentHardwareNativeTools.toolIds.size, definitions.size)
        definitions.forEach { definition ->
            val descriptor = definition.descriptor
            assertTrue(descriptor.id, descriptor.inputSchema.document.isNotEmpty())
            assertTrue(descriptor.id, descriptor.outputSchema.document.isNotEmpty())
            assertTrue(descriptor.id, descriptor.capabilities.isNotEmpty())
            assertTrue(descriptor.id, descriptor.timeoutMillis in 1..AgentHardwareNativeTools.MAX_TOOL_TIMEOUT_MILLIS)
            assertEquals(descriptor.id, "signalasi.android.hardware_native", definition.executorId)
            assertEquals(descriptor.id, "bounded-v1", definition.provenanceMetadata["result_policy"])
            assertEquals(descriptor.id, "false", definition.provenanceMetadata["background_capture"])
            assertEquals(descriptor.id, "false", definition.provenanceMetadata["silent_settings_changes"])
        }

        val location = descriptor(definitions, AgentHardwareNativeTools.LOCATION_FOREGROUND_READ)
        assertEquals(AgentNativeToolRisk.HIGH, location.risk)
        assertEquals(
            setOf(
                AgentHardwareNativeTools.ACCESS_COARSE_LOCATION_PERMISSION,
                AgentHardwareNativeTools.ACCESS_FINE_LOCATION_PERMISSION
            ),
            location.requiredPermissions.map { it.id }.toSet()
        )
        assertFalse(
            location.requiredPermissions.single {
                it.id == AgentHardwareNativeTools.ACCESS_FINE_LOCATION_PERMISSION
            }.required
        )
        assertEquals(
            listOf(AgentHardwareNativeTools.FOREGROUND_LOCATION_CONSENT),
            location.requiredConsents.map { it.id }
        )

        val sample = descriptor(definitions, AgentHardwareNativeTools.SENSOR_SAMPLE)
        assertFalse(
            sample.requiredPermissions.single {
                it.id == AgentHardwareNativeTools.BODY_SENSORS_PERMISSION
            }.required
        )
        assertTrue(sample.capabilities.contains("sensors.non_health_allowlist"))

        val pairing = descriptor(definitions, AgentHardwareNativeTools.BLUETOOTH_PAIRING_HANDOFF)
        assertEquals(AgentNativeToolRisk.HIGH, pairing.risk)
        assertEquals(AgentNativeToolIdempotency.NON_IDEMPOTENT, pairing.idempotency)
        assertTrue(pairing.capabilities.contains("bluetooth.no_silent_pairing"))
    }

    @Test
    fun readsBatteryPowerStorageAndIdentifierFreeNetworkState() {
        val platform = FakeHardwarePlatform()
        val registry = AgentHardwareNativeTools.createRegistry(platform)

        val battery = registry.invoke(AgentHardwareNativeTools.BATTERY_STATUS, emptyMap())
        val power = registry.invoke(AgentHardwareNativeTools.POWER_STATUS, emptyMap())
        val storage = registry.invoke(AgentHardwareNativeTools.STORAGE_STATUS, emptyMap())
        val network = registry.invoke(
            AgentHardwareNativeTools.NETWORK_STATUS,
            emptyMap(),
            context(permissions = setOf(AgentHardwareNativeTools.ACCESS_NETWORK_STATE_PERMISSION))
        )

        assertTrue(battery.toJson(), battery.isSuccess)
        assertEquals(73, battery.output["percent"])
        assertEquals("usb", battery.output["plugged"])
        assertEquals("app_visible", battery.output["scope"])
        assertTrue(power.toJson(), power.isSuccess)
        assertEquals(false, power.output["settings_changed"])
        assertTrue(storage.toJson(), storage.isSuccess)
        assertEquals(700L, storage.output["used_bytes"])
        assertEquals("app_private_volume", storage.output["scope"])
        assertTrue(network.toJson(), network.isSuccess)
        assertEquals(listOf("wifi", "vpn"), network.output["transports"])
        assertEquals(false, network.output["identifiers_included"])
    }

    @Test
    fun foregroundLocationRequiresPermissionAndPerInvocationConsent() {
        val platform = FakeHardwarePlatform()
        val registry = AgentHardwareNativeTools.createRegistry(platform)

        val missingPermission = registry.invoke(
            AgentHardwareNativeTools.LOCATION_FOREGROUND_READ,
            mapOf("timeout_ms" to 2_000),
            context(consents = setOf(AgentHardwareNativeTools.FOREGROUND_LOCATION_CONSENT))
        )
        val missingConsent = registry.invoke(
            AgentHardwareNativeTools.LOCATION_FOREGROUND_READ,
            mapOf("timeout_ms" to 2_000),
            context(permissions = setOf(AgentHardwareNativeTools.ACCESS_COARSE_LOCATION_PERMISSION))
        )

        assertEquals("missing_permissions", missingPermission.error?.code)
        assertEquals("missing_consents", missingConsent.error?.code)
        assertEquals(0, platform.locationCalls)

        val result = registry.invoke(
            AgentHardwareNativeTools.LOCATION_FOREGROUND_READ,
            mapOf("timeout_ms" to 2_000),
            context(
                permissions = setOf(AgentHardwareNativeTools.ACCESS_COARSE_LOCATION_PERMISSION),
                consents = setOf(AgentHardwareNativeTools.FOREGROUND_LOCATION_CONSENT)
            )
        )

        assertTrue(result.toJson(), result.isSuccess)
        assertEquals(1, platform.locationCalls)
        assertEquals(2_000L, platform.lastLocationTimeout)
        assertEquals("single_foreground_fix", result.output["capture_mode"])
        assertEquals(false, result.output["background_capture"])
        assertEquals(500L, result.output["age_ms"])
        assertEquals(false, result.metadata["retained_listener"])
    }

    @Test
    fun liveAvailabilityPreventsExecutionUntilSetupIsReady() {
        val platform = FakeHardwarePlatform()
        val registry = AgentHardwareNativeTools.createRegistry(platform)
        platform.availability[AgentHardwareCapability.FOREGROUND_LOCATION] = AgentNativeToolAvailability(
            AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
            "App must be foreground"
        )

        val blocked = registry.invoke(
            AgentHardwareNativeTools.LOCATION_FOREGROUND_READ,
            emptyMap(),
            context(
                permissions = setOf(AgentHardwareNativeTools.ACCESS_COARSE_LOCATION_PERMISSION),
                consents = setOf(AgentHardwareNativeTools.FOREGROUND_LOCATION_CONSENT)
            )
        )

        assertEquals(AgentNativeToolResultStatus.UNAVAILABLE, blocked.status)
        assertEquals("tool_unavailable", blocked.error?.code)
        assertTrue(blocked.error?.retryable == true)
        assertEquals(0, platform.locationCalls)
        assertEquals(
            AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
            registry.descriptors().single {
                it.id == AgentHardwareNativeTools.LOCATION_FOREGROUND_READ
            }.availability.status
        )
    }

    @Test
    fun sensorsAreMetadataOnlyOrOneBoundedForegroundSample() {
        val platform = FakeHardwarePlatform().apply {
            sensorList = (1..5).map { index ->
                AgentSensorDescriptor(
                    type = "android_type_$index",
                    androidType = index,
                    name = "Sensor $index",
                    vendor = "Vendor",
                    version = 1,
                    maximumRange = 100.0,
                    resolution = 0.1,
                    powerMilliamps = 0.5,
                    reportingMode = "continuous",
                    wakeUp = false
                )
            }
            sensorSample = AgentSensorSample(
                type = "accelerometer",
                androidType = 1,
                values = (1..24).map(Int::toDouble),
                accuracy = 3,
                observedAtEpochMillis = 2_000L
            )
        }
        val registry = AgentHardwareNativeTools.createRegistry(platform)

        val listed = registry.invoke(
            AgentHardwareNativeTools.SENSORS_LIST,
            mapOf("limit" to 2)
        )
        val denied = registry.invoke(
            AgentHardwareNativeTools.SENSOR_SAMPLE,
            mapOf("type" to "accelerometer")
        )
        val sampled = registry.invoke(
            AgentHardwareNativeTools.SENSOR_SAMPLE,
            mapOf("type" to "accelerometer", "timeout_ms" to 1_000),
            context(consents = setOf(AgentHardwareNativeTools.SENSOR_SAMPLE_CONSENT))
        )

        assertTrue(listed.toJson(), listed.isSuccess)
        assertEquals(2, listed.output["result_count"])
        assertEquals(true, listed.output["truncated"])
        assertEquals(false, listed.output["sampling_started"])
        assertEquals("missing_consents", denied.error?.code)
        assertTrue(sampled.toJson(), sampled.isSuccess)
        assertEquals(1, platform.sampleCalls)
        assertEquals(AgentHardwareNativeTools.MAX_SENSOR_VALUES, (sampled.output["values"] as List<*>).size)
        assertEquals("single_foreground_sample", sampled.output["capture_mode"])
        assertEquals(false, sampled.output["background_capture"])
    }

    @Test
    fun flashlightIsExplicitConsentGatedAndNeverClaimsVerifiedState() {
        val platform = FakeHardwarePlatform()
        val registry = AgentHardwareNativeTools.createRegistry(platform)
        val permissions = setOf(AgentHardwareNativeTools.CAMERA_PERMISSION)

        val denied = registry.invoke(
            AgentHardwareNativeTools.FLASHLIGHT_SET,
            mapOf("enabled" to true),
            context(permissions = permissions)
        )
        assertEquals("missing_consents", denied.error?.code)
        assertEquals(0, platform.flashlightCalls)

        val accepted = registry.invoke(
            AgentHardwareNativeTools.FLASHLIGHT_SET,
            mapOf("enabled" to true),
            context(
                permissions = permissions,
                consents = setOf(AgentHardwareNativeTools.FLASHLIGHT_CONTROL_CONSENT)
            )
        )

        assertTrue(accepted.toJson(), accepted.isSuccess)
        assertEquals(1, platform.flashlightCalls)
        assertEquals(true, platform.lastFlashlightEnabled)
        assertEquals(true, accepted.output["request_accepted"])
        assertEquals(false, accepted.output["state_verified"])
        assertEquals(false, accepted.output["settings_changed"])
        assertEquals(false, accepted.metadata["continuous_state_guarantee"])
    }

    @Test
    fun bluetoothDiscoveryIsBoundedAndPairingRemainsHandoffOnly() {
        val platform = FakeHardwarePlatform().apply {
            bluetoothDevices = listOf(
                device("00:11:22:33:44:55", "Keyboard"),
                device("AA:BB:CC:DD:EE:FF", "Headphones"),
                device("12:34:56:78:9A:BC", "Display")
            )
        }
        val registry = AgentHardwareNativeTools.createRegistry(platform)
        val bluetoothPermissions = setOf(
            AgentHardwareNativeTools.BLUETOOTH_SCAN_PERMISSION,
            AgentHardwareNativeTools.BLUETOOTH_CONNECT_PERMISSION
        )

        val discovery = registry.invoke(
            AgentHardwareNativeTools.BLUETOOTH_DISCOVERY_FOREGROUND,
            mapOf("timeout_ms" to 3_000, "limit" to 2),
            context(
                permissions = bluetoothPermissions,
                consents = setOf(AgentHardwareNativeTools.BLUETOOTH_DISCOVERY_CONSENT)
            )
        )
        val pairing = registry.invoke(
            AgentHardwareNativeTools.BLUETOOTH_PAIRING_HANDOFF,
            emptyMap(),
            context(consents = setOf(AgentHardwareNativeTools.BLUETOOTH_PAIRING_HANDOFF_CONSENT))
        )

        assertTrue(discovery.toJson(), discovery.isSuccess)
        assertEquals(2, discovery.output["result_count"])
        assertEquals(true, discovery.output["truncated"])
        assertEquals(false, discovery.output["background_capture"])
        assertEquals(true, discovery.metadata["receiver_unregistered"])
        assertEquals(3_000L, platform.lastBluetoothTimeout)
        assertTrue(pairing.toJson(), pairing.isSuccess)
        assertEquals(true, pairing.output["launched"])
        assertEquals(false, pairing.output["completed"])
        assertEquals(false, pairing.output["settings_changed_by_signalasi"])
        assertEquals("handoff_only", pairing.metadata["completion_semantics"])
    }

    @Test
    fun nfcReadsStatusWithoutTagCaptureOrSettingMutation() {
        val platform = FakeHardwarePlatform()
        val registry = AgentHardwareNativeTools.createRegistry(platform)

        val denied = registry.invoke(AgentHardwareNativeTools.NFC_STATUS, emptyMap())
        val result = registry.invoke(
            AgentHardwareNativeTools.NFC_STATUS,
            emptyMap(),
            context(permissions = setOf(AgentHardwareNativeTools.NFC_PERMISSION))
        )

        assertEquals("missing_permissions", denied.error?.code)
        assertTrue(result.toJson(), result.isSuccess)
        assertEquals(true, result.output["enabled"])
        assertEquals(false, result.output["tag_capture_started"])
        assertEquals(false, result.output["settings_changed"])
    }

    @Test
    fun installedAppsAndPackageDetailStayQueryVisibleAndBounded() {
        val platform = FakeHardwarePlatform().apply {
            apps = listOf(
                app("com.example.alpha", "Alpha"),
                app("com.example.beta", "Beta"),
                app("com.example.gamma", "Gamma")
            )
            detail = AgentPackageDetail(
                packageName = "com.example.alpha",
                visible = true,
                label = "Alpha",
                versionName = "1.2.3",
                versionCode = 12,
                enabled = true,
                systemApp = false,
                launchable = true,
                firstInstallTimeEpochMillis = 100,
                lastUpdateTimeEpochMillis = 200,
                targetSdk = 34,
                minSdk = 26,
                requestedPermissions = (1..150).map { "android.permission.TEST_$it" }
            )
        }
        val registry = AgentHardwareNativeTools.createRegistry(platform)

        val apps = registry.invoke(
            AgentHardwareNativeTools.INSTALLED_APPS_LIST,
            mapOf("query" to "example", "limit" to 2),
            context(consents = setOf(AgentHardwareNativeTools.INSTALLED_APPS_CONSENT))
        )
        val detail = registry.invoke(
            AgentHardwareNativeTools.PACKAGE_DETAIL,
            mapOf("package_name" to "com.example.alpha"),
            context(consents = setOf(AgentHardwareNativeTools.PACKAGE_DETAIL_CONSENT))
        )

        assertTrue(apps.toJson(), apps.isSuccess)
        assertEquals("example", platform.lastAppQuery)
        assertEquals(2, apps.output["result_count"])
        assertEquals(true, apps.output["truncated"])
        assertEquals("query_visible_only", apps.output["visibility_scope"])
        assertEquals(false, apps.output["complete_device_census"])
        assertTrue(detail.toJson(), detail.isSuccess)
        assertEquals(true, detail.output["visible"])
        assertEquals("query_visible_only", detail.output["visibility_scope"])
        assertEquals(false, detail.output["not_visible_means_not_installed"])
        assertEquals(
            AgentHardwareNativeTools.MAX_PACKAGE_PERMISSIONS,
            (detail.output["requested_permissions"] as List<*>).size
        )

        platform.detail = AgentPackageDetail("com.example.hidden", visible = false)
        val hidden = registry.invoke(
            AgentHardwareNativeTools.PACKAGE_DETAIL,
            mapOf("package_name" to "com.example.hidden"),
            context(consents = setOf(AgentHardwareNativeTools.PACKAGE_DETAIL_CONSENT))
        )
        assertTrue(hidden.toJson(), hidden.isSuccess)
        assertEquals(false, hidden.output["visible"])
        assertTrue(hidden.message.contains("unknown"))
    }

    @Test
    fun schemasRejectUnboundedInputsBeforeFacadeCalls() {
        val platform = FakeHardwarePlatform()
        val registry = AgentHardwareNativeTools.createRegistry(platform)

        val tooManyApps = registry.invoke(
            AgentHardwareNativeTools.INSTALLED_APPS_LIST,
            mapOf("limit" to AgentHardwareNativeTools.MAX_INSTALLED_APP_RESULTS + 1),
            context(consents = setOf(AgentHardwareNativeTools.INSTALLED_APPS_CONSENT))
        )
        val invalidPackage = registry.invoke(
            AgentHardwareNativeTools.PACKAGE_DETAIL,
            mapOf("package_name" to "../secret"),
            context(consents = setOf(AgentHardwareNativeTools.PACKAGE_DETAIL_CONSENT))
        )
        val healthSensor = registry.invoke(
            AgentHardwareNativeTools.SENSOR_SAMPLE,
            mapOf("type" to "heart_rate"),
            context(consents = setOf(AgentHardwareNativeTools.SENSOR_SAMPLE_CONSENT))
        )

        assertEquals("invalid_input", tooManyApps.error?.code)
        assertEquals("invalid_input", invalidPackage.error?.code)
        assertEquals("invalid_input", healthSensor.error?.code)
        assertEquals(0, platform.installedAppsCalls)
        assertEquals(0, platform.packageDetailCalls)
        assertEquals(0, platform.sampleCalls)
    }

    @Test
    fun platformFailuresReturnStableNativeErrors() {
        val platform = FakeHardwarePlatform().apply {
            batteryError = AgentHardwareNativeException(
                "battery_probe_failed",
                "Battery service stopped",
                retryable = true
            )
        }
        val registry = AgentHardwareNativeTools.createRegistry(platform)
        val result = registry.invoke(AgentHardwareNativeTools.BATTERY_STATUS, emptyMap())

        assertEquals(AgentNativeToolResultStatus.FAILED, result.status)
        assertEquals("battery_probe_failed", result.error?.code)
        assertTrue(result.error?.retryable == true)
    }

    private fun descriptor(
        definitions: List<AgentNativeToolDefinition>,
        id: String
    ): AgentNativeToolDescriptor = definitions.single { it.descriptor.id == id }.descriptor

    private fun context(
        permissions: Set<String> = emptySet(),
        consents: Set<String> = emptySet()
    ) = AgentNativeToolInvocationContext(
        grantedPermissions = permissions,
        grantedConsents = consents
    )

    private fun device(address: String, name: String) = AgentBluetoothDeviceObservation(
        address = address,
        name = name,
        bondState = "none",
        deviceType = "dual"
    )

    private fun app(packageName: String, label: String) = AgentInstalledAppSummary(
        packageName = packageName,
        label = label,
        versionName = "1.0",
        versionCode = 1,
        enabled = true,
        systemApp = false,
        launchable = true
    )

    private class FakeHardwarePlatform : AgentHardwarePlatformFacade {
        override val implementationId = "test.fake_hardware"
        val availability = AgentHardwareCapability.entries.associateWith {
            AgentNativeToolAvailability.AVAILABLE
        }.toMutableMap()

        var locationCalls = 0
        var lastLocationTimeout = 0L
        var sampleCalls = 0
        var flashlightCalls = 0
        var lastFlashlightEnabled: Boolean? = null
        var lastBluetoothTimeout = 0L
        var installedAppsCalls = 0
        var lastAppQuery = ""
        var packageDetailCalls = 0
        var batteryError: AgentHardwareNativeException? = null

        var sensorList: List<AgentSensorDescriptor> = listOf(
            AgentSensorDescriptor(
                "accelerometer",
                1,
                "Accelerometer",
                "Vendor",
                1,
                20.0,
                0.01,
                0.2,
                "continuous",
                false
            )
        )
        var sensorSample = AgentSensorSample(
            "accelerometer",
            1,
            listOf(1.0, 2.0, 3.0),
            3,
            1_000L
        )
        var bluetoothDevices: List<AgentBluetoothDeviceObservation> = emptyList()
        var apps: List<AgentInstalledAppSummary> = emptyList()
        var detail = AgentPackageDetail("com.example.app", visible = false)

        override fun availability(capability: AgentHardwareCapability): AgentNativeToolAvailability =
            availability.getValue(capability)

        override fun battery(): AgentBatterySnapshot {
            batteryError?.let { throw it }
            return AgentBatterySnapshot(
                percent = 73,
                charging = true,
                plugged = "usb",
                status = "charging",
                health = "good",
                temperatureCelsius = 31.5,
                voltageMillivolts = 4_100,
                chargeCounterMicroampHours = 2_000_000,
                observedAtEpochMillis = 1_000L
            )
        }

        override fun power() = AgentPowerSnapshot(
            interactive = true,
            powerSaveMode = false,
            deviceIdleMode = false,
            ignoringBatteryOptimizations = false,
            observedAtEpochMillis = 1_000L
        )

        override fun storage() = AgentStorageSnapshot(
            scope = "app_private_volume",
            totalBytes = 1_000L,
            availableBytes = 300L,
            lowStorage = false,
            observedAtEpochMillis = 1_000L
        )

        override fun network() = AgentNetworkSnapshot(
            connected = true,
            validated = true,
            metered = false,
            roaming = false,
            transports = listOf("wifi", "vpn"),
            downstreamKbps = 10_000,
            upstreamKbps = 2_000,
            observedAtEpochMillis = 1_000L
        )

        override fun foregroundLocation(
            timeoutMillis: Long,
            cancellationToken: AgentNativeToolCancellationToken
        ): AgentForegroundLocationSnapshot {
            locationCalls += 1
            lastLocationTimeout = timeoutMillis
            return AgentForegroundLocationSnapshot(
                latitude = 31.2304,
                longitude = 121.4737,
                accuracyMeters = 12.0,
                altitudeMeters = 4.0,
                bearingDegrees = null,
                speedMetersPerSecond = null,
                provider = "test",
                fixAtEpochMillis = 1_000L,
                observedAtEpochMillis = 1_500L,
                source = "current_fix"
            )
        }

        override fun sensors(limit: Int): List<AgentSensorDescriptor> = sensorList.take(limit)

        override fun sampleSensor(
            type: String,
            timeoutMillis: Long,
            cancellationToken: AgentNativeToolCancellationToken
        ): AgentSensorSample {
            sampleCalls += 1
            return sensorSample.copy(type = type)
        }

        override fun setFlashlight(enabled: Boolean): AgentFlashlightRequestResult {
            flashlightCalls += 1
            lastFlashlightEnabled = enabled
            return AgentFlashlightRequestResult(enabled, requestAccepted = true, stateVerified = true)
        }

        override fun bluetoothStatus() = AgentBluetoothStatusSnapshot(
            supported = true,
            enabled = true,
            discovering = false,
            bondedDeviceCount = 2,
            observedAtEpochMillis = 1_000L
        )

        override fun discoverBluetooth(
            timeoutMillis: Long,
            limit: Int,
            cancellationToken: AgentNativeToolCancellationToken
        ): AgentBluetoothDiscoveryResult {
            lastBluetoothTimeout = timeoutMillis
            return AgentBluetoothDiscoveryResult(
                devices = bluetoothDevices.take(limit),
                completed = true,
                timedOut = false,
                observedAtEpochMillis = 2_000L
            )
        }

        override fun handoffBluetoothPairing() = AgentSystemHandoffResult(
            launched = true,
            action = "android.settings.BLUETOOTH_SETTINGS",
            completed = true
        )

        override fun nfcStatus() = AgentNfcStatusSnapshot(
            supported = true,
            enabled = true,
            secureNfcSupported = true,
            secureNfcEnabled = false,
            observedAtEpochMillis = 1_000L
        )

        override fun installedApps(query: String, limit: Int): List<AgentInstalledAppSummary> {
            installedAppsCalls += 1
            lastAppQuery = query
            return apps.take(limit)
        }

        override fun packageDetail(packageName: String): AgentPackageDetail {
            packageDetailCalls += 1
            return detail.copy(packageName = packageName)
        }
    }
}
