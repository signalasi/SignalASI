package com.signalasi.chat

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

interface AgentVisibleCapturePlatform {
    val implementationId: String

    fun availability(kind: AgentVisibleCaptureKind): AgentNativeToolAvailability

    fun capturePhoto(
        facing: String,
        invocation: AgentNativeToolInvocation
    ): AgentVisibleCaptureOutcome

    fun recordAudio(
        maxDurationSeconds: Int,
        invocation: AgentNativeToolInvocation
    ): AgentVisibleCaptureOutcome
}

class AgentAndroidVisibleCapturePlatform(context: Context) : AgentVisibleCapturePlatform {
    private val appContext = context.applicationContext

    override val implementationId: String = "signalasi.android.visible_capture"

    override fun availability(kind: AgentVisibleCaptureKind): AgentNativeToolAvailability {
        val feature = when (kind) {
            AgentVisibleCaptureKind.PHOTO -> PackageManager.FEATURE_CAMERA_ANY
            AgentVisibleCaptureKind.AUDIO -> PackageManager.FEATURE_MICROPHONE
        }
        val permission = when (kind) {
            AgentVisibleCaptureKind.PHOTO -> Manifest.permission.CAMERA
            AgentVisibleCaptureKind.AUDIO -> Manifest.permission.RECORD_AUDIO
        }
        return when {
            !appContext.packageManager.hasSystemFeature(feature) -> AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.UNAVAILABLE,
                "This device does not report the required capture hardware"
            )
            appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED ->
                AgentNativeToolAvailability(
                    AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
                    "Android runtime permission is required: $permission"
                )
            !isAppVisible() -> AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
                "User-visible capture requires SignalASI to be in the foreground"
            )
            AgentVisibleCaptureCoordinator.isBusy() -> AgentNativeToolAvailability(
                AgentNativeToolAvailabilityStatus.UNAVAILABLE,
                "Another user-visible capture is already active"
            )
            else -> AgentNativeToolAvailability.AVAILABLE
        }
    }

    override fun capturePhoto(
        facing: String,
        invocation: AgentNativeToolInvocation
    ): AgentVisibleCaptureOutcome = launchAndAwait(
        kind = AgentVisibleCaptureKind.PHOTO,
        invocation = invocation,
        intent = Intent(appContext, AgentAutoCaptureActivity::class.java)
            .putExtra(AgentVisibleCaptureContract.EXTRA_CAMERA_FACING, facing)
    )

    override fun recordAudio(
        maxDurationSeconds: Int,
        invocation: AgentNativeToolInvocation
    ): AgentVisibleCaptureOutcome = launchAndAwait(
        kind = AgentVisibleCaptureKind.AUDIO,
        invocation = invocation,
        intent = Intent(appContext, AgentVisibleAudioCaptureActivity::class.java)
            .putExtra(AgentVisibleCaptureContract.EXTRA_MAX_DURATION_SECONDS, maxDurationSeconds)
    )

    private fun launchAndAwait(
        kind: AgentVisibleCaptureKind,
        invocation: AgentNativeToolInvocation,
        intent: Intent
    ): AgentVisibleCaptureOutcome {
        val requestId = invocation.context.invocationId
        if (!AgentVisibleCaptureCoordinator.begin(requestId, kind)) {
            return AgentVisibleCaptureOutcome(
                AgentVisibleCaptureStatus.FAILED,
                code = "capture_busy",
                message = "Another user-visible capture is already active"
            )
        }
        return try {
            invocation.reportProgress(
                stage = "opening_visible_capture",
                message = "Opening the user-visible ${kind.wireValue} capture surface",
                percent = 10
            )
            appContext.startActivity(
                intent.putExtra(AgentVisibleCaptureContract.EXTRA_REQUEST_ID, requestId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            invocation.reportProgress(
                stage = "waiting_for_capture",
                message = "Waiting for the foreground capture result",
                percent = 35
            )
            AgentVisibleCaptureCoordinator.await(requestId, invocation)
        } catch (error: AgentNativeToolCancelledException) {
            throw error
        } catch (error: AgentNativeToolTimeoutException) {
            throw error
        } catch (error: Throwable) {
            AgentVisibleCaptureOutcome(
                AgentVisibleCaptureStatus.FAILED,
                code = "capture_launch_failed",
                message = error.message ?: "Could not open the visible capture surface"
            )
        } finally {
            AgentVisibleCaptureCoordinator.release(requestId)
        }
    }

    private fun isAppVisible(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }
}

/** Explicit, user-visible camera and microphone tools. Silent background capture is not exposed. */
object AgentVisibleCaptureNativeTools {
    const val CAMERA_CAPTURE = "signalasi.camera.capture.visible"
    const val MICROPHONE_RECORD = "signalasi.microphone.record.visible"

    const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    const val MICROPHONE_PERMISSION = Manifest.permission.RECORD_AUDIO
    const val RUNTIME_PERMISSION_CONSENT = "signalasi.consent.runtime_permission_dialog"
    const val USER_VISIBLE_CAPTURE_CONSENT = "signalasi.consent.user_visible_capture"

    val toolIds: Set<String> = setOf(CAMERA_CAPTURE, MICROPHONE_RECORD)

    fun definitions(platform: AgentVisibleCapturePlatform): List<AgentNativeToolDefinition> = listOf(
        definition(
            platform = platform,
            kind = AgentVisibleCaptureKind.PHOTO,
            descriptor = descriptor(
                id = CAMERA_CAPTURE,
                title = "Capture a user-visible photo",
                description = "Opens a foreground camera surface, waits for autofocus, captures one photo, and returns its content URI.",
                permission = permission(CAMERA_PERMISSION, "Camera", "Allows one foreground, user-visible photo capture."),
                capabilities = setOf("camera.capture.user_visible", "artifact.image.content_uri"),
                inputSchema = objectSchema(
                    properties = mapOf(
                        "facing" to AgentNativeJsonSchema.string(
                            enumValues = listOf("back", "front", "any")
                        )
                    )
                )
            )
        ) { invocation ->
            platform.capturePhoto(invocation.input.string("facing", "back"), invocation)
        },
        definition(
            platform = platform,
            kind = AgentVisibleCaptureKind.AUDIO,
            descriptor = descriptor(
                id = MICROPHONE_RECORD,
                title = "Record user-visible audio",
                description = "Opens a foreground recording surface for a bounded duration and returns the audio content URI.",
                permission = permission(
                    MICROPHONE_PERMISSION,
                    "Microphone",
                    "Allows one foreground, user-visible audio recording."
                ),
                capabilities = setOf("microphone.record.user_visible", "artifact.audio.content_uri"),
                inputSchema = objectSchema(
                    properties = mapOf(
                        "max_duration_seconds" to AgentNativeJsonSchema.integer(
                            minimum = 1,
                            maximum = MAX_AUDIO_DURATION_SECONDS.toLong()
                        )
                    )
                ),
                timeoutMillis = (MAX_AUDIO_DURATION_SECONDS + 15L) * 1_000L
            )
        ) { invocation ->
            platform.recordAudio(
                maxDurationSeconds = invocation.input.int("max_duration_seconds", DEFAULT_AUDIO_DURATION_SECONDS),
                invocation = invocation
            )
        }
    )

    fun androidDefinitions(context: Context): List<AgentNativeToolDefinition> =
        definitions(AgentAndroidVisibleCapturePlatform(context.applicationContext))

    private fun definition(
        platform: AgentVisibleCapturePlatform,
        kind: AgentVisibleCaptureKind,
        descriptor: AgentNativeToolDescriptor,
        capture: (AgentNativeToolInvocation) -> AgentVisibleCaptureOutcome
    ) = AgentNativeToolDefinition(
        descriptor = descriptor.copy(availability = platform.availability(kind)),
        executor = AgentNativeToolExecutor { invocation ->
            invocation.checkpoint()
            val outcome = capture(invocation)
            when (outcome.status) {
                AgentVisibleCaptureStatus.SUCCEEDED -> {
                    val artifact = outcome.artifact
                        ?: return@AgentNativeToolExecutor AgentNativeToolExecutionResult.failure(
                            "capture_result_missing",
                            "The visible capture returned no artifact"
                        )
                    invocation.reportProgress(
                        stage = "capture_complete",
                        message = "User-visible capture completed",
                        percent = 100
                    )
                    AgentNativeToolExecutionResult.success(
                        output = artifactValue(artifact),
                        message = if (artifact.kind == AgentVisibleCaptureKind.PHOTO) {
                            "Captured one user-visible photo"
                        } else {
                            "Recorded user-visible audio"
                        },
                        metadata = mapOf(
                            "background_capture" to false,
                            "raw_media_in_receipt" to false
                        )
                    )
                }
                AgentVisibleCaptureStatus.CANCELLED -> AgentNativeToolExecutionResult.failure(
                    code = outcome.code.ifBlank { "capture_cancelled" },
                    message = outcome.message.ifBlank { "The user cancelled the visible capture" }
                )
                AgentVisibleCaptureStatus.FAILED -> AgentNativeToolExecutionResult.failure(
                    code = outcome.code.ifBlank { "capture_failed" },
                    message = outcome.message.ifBlank { "The visible capture failed" },
                    retryable = outcome.code in setOf("capture_busy", "camera_unavailable", "microphone_unavailable")
                )
            }
        },
        verifier = AgentNativeToolVerifier { _, execution ->
            val uri = execution.output["content_uri"]?.toString().orEmpty()
            val visible = execution.output["user_visible"] as? Boolean == true
            if (uri.startsWith("content://") || uri.startsWith("file://")) {
                AgentNativeToolVerification(
                    status = if (visible) AgentNativeVerificationStatus.PASSED else AgentNativeVerificationStatus.FAILED,
                    message = if (visible) "A user-visible capture artifact URI was returned" else "Capture was not marked user-visible",
                    evidence = mapOf("uri_scheme" to uri.substringBefore(':'))
                )
            } else {
                AgentNativeToolVerification(
                    AgentNativeVerificationStatus.FAILED,
                    "Capture did not return a supported artifact URI"
                )
            }
        },
        executorId = EXECUTOR_ID,
        provenanceMetadata = mapOf(
            "implementation" to platform.implementationId,
            "capture_surface" to "foreground_activity",
            "privacy_indicator" to "android_managed",
            "background_capture" to "false",
            "artifact_contract" to "content-uri-v1"
        ),
        availabilityProvider = AgentNativeToolAvailabilityProvider { platform.availability(kind) }
    )

    private fun descriptor(
        id: String,
        title: String,
        description: String,
        permission: AgentNativePermissionRequirement,
        capabilities: Set<String>,
        inputSchema: AgentNativeJsonSchema,
        timeoutMillis: Long = 30_000L
    ) = AgentNativeToolDescriptor(
        id = id,
        version = VERSION,
        title = title,
        description = description,
        location = AgentNativeToolLocation.APPLICATION,
        inputSchema = inputSchema,
        outputSchema = artifactSchema(),
        risk = AgentNativeToolRisk.HIGH,
        capabilities = capabilities,
        requiredPermissions = listOf(permission),
        requiredConsents = listOf(
            consent(
                RUNTIME_PERMISSION_CONSENT,
                "Android runtime permission",
                "The Android runtime permission dialog must be accepted before capture."
            ),
            consent(
                USER_VISIBLE_CAPTURE_CONSENT,
                "User-visible capture",
                "Capture must stay visible and may be cancelled by the user."
            )
        ),
        timeoutMillis = timeoutMillis,
        idempotency = AgentNativeToolIdempotency.NON_IDEMPOTENT,
        availability = AgentNativeToolAvailability.AVAILABLE
    )

    private fun artifactValue(artifact: AgentVisibleCaptureArtifact): AgentNativeJsonObject = linkedMapOf(
        "kind" to artifact.kind.wireValue,
        "content_uri" to artifact.contentUri,
        "mime_type" to artifact.mimeType,
        "size_bytes" to artifact.sizeBytes.coerceAtLeast(0L),
        "width_px" to artifact.widthPixels.coerceAtLeast(0),
        "height_px" to artifact.heightPixels.coerceAtLeast(0),
        "duration_ms" to artifact.durationMillis.coerceAtLeast(0L),
        "captured_at_epoch_ms" to artifact.capturedAtEpochMillis.coerceAtLeast(0L),
        "user_visible" to true,
        "completed_by" to artifact.completedBy.take(64)
    )

    private fun artifactSchema() = objectSchema(
        properties = mapOf(
            "kind" to AgentNativeJsonSchema.string(enumValues = AgentVisibleCaptureKind.entries.map { it.wireValue }),
            "content_uri" to AgentNativeJsonSchema.string(minLength = 1, maxLength = 4_096),
            "mime_type" to AgentNativeJsonSchema.string(minLength = 1, maxLength = 128),
            "size_bytes" to AgentNativeJsonSchema.integer(minimum = 0),
            "width_px" to AgentNativeJsonSchema.integer(minimum = 0, maximum = 100_000),
            "height_px" to AgentNativeJsonSchema.integer(minimum = 0, maximum = 100_000),
            "duration_ms" to AgentNativeJsonSchema.integer(minimum = 0, maximum = 60_000),
            "captured_at_epoch_ms" to AgentNativeJsonSchema.integer(minimum = 0),
            "user_visible" to AgentNativeJsonSchema.boolean(),
            "completed_by" to AgentNativeJsonSchema.string(minLength = 1, maxLength = 64)
        ),
        required = setOf(
            "kind",
            "content_uri",
            "mime_type",
            "size_bytes",
            "width_px",
            "height_px",
            "duration_ms",
            "captured_at_epoch_ms",
            "user_visible",
            "completed_by"
        )
    )

    private fun objectSchema(
        properties: Map<String, AgentNativeJsonSchema> = emptyMap(),
        required: Set<String> = emptySet()
    ) = AgentNativeJsonSchema.objectSchema(properties, required, additionalProperties = false)

    private fun permission(id: String, title: String, description: String) =
        AgentNativePermissionRequirement(id, title, description)

    private fun consent(id: String, title: String, description: String) =
        AgentNativeConsentRequirement(id, title, description)

    private fun AgentNativeJsonObject.string(key: String, fallback: String): String =
        this[key]?.toString()?.trim()?.takeIf(String::isNotBlank) ?: fallback

    private fun AgentNativeJsonObject.int(key: String, fallback: Int): Int =
        ((this[key] as? Number)?.toInt() ?: fallback).coerceIn(1, MAX_AUDIO_DURATION_SECONDS)

    private const val VERSION = "1.0.0"
    private const val EXECUTOR_ID = "signalasi.android_visible_capture"
    private const val DEFAULT_AUDIO_DURATION_SECONDS = 5
    private const val MAX_AUDIO_DURATION_SECONDS = 30
}
