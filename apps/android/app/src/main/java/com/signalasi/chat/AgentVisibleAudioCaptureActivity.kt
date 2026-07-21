package com.signalasi.chat

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class AgentVisibleAudioCaptureActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var stopButton: Button
    private var recorder: MediaRecorder? = null
    private var temporaryFile: File? = null
    private var requestId: String = ""
    private var maxDurationSeconds: Int = DEFAULT_DURATION_SECONDS
    private var startedAtElapsedMillis: Long = 0L
    private val outcomeReported = AtomicBoolean(false)
    private val stopStarted = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(AgentVisibleCaptureContract.EXTRA_REQUEST_ID).orEmpty()
        maxDurationSeconds = intent.getIntExtra(
            AgentVisibleCaptureContract.EXTRA_MAX_DURATION_SECONDS,
            DEFAULT_DURATION_SECONDS
        ).coerceIn(1, MAX_DURATION_SECONDS)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(createContent())
        if (requestId.isBlank() || !AgentVisibleCaptureCoordinator.attach(
                requestId,
                AgentVisibleCaptureKind.AUDIO,
                this
            )
        ) {
            finishWithFailure("capture_request_missing", getString(R.string.agent_audio_capture_request_missing))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        }
    }

    private fun createContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(32), dp(48), dp(32), dp(48))
        setBackgroundColor(Color.BLACK)

        addView(ImageView(this@AgentVisibleAudioCaptureActivity).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
            bottomMargin = dp(28)
        })

        addView(TextView(this@AgentVisibleAudioCaptureActivity).apply {
            text = getString(R.string.agent_audio_capture_title)
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        status = TextView(this@AgentVisibleAudioCaptureActivity).apply {
            text = getString(R.string.agent_audio_capture_preparing)
            setTextColor(0xFFB8C0CC.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
        }
        addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
            bottomMargin = dp(36)
        })

        addView(LinearLayout(this@AgentVisibleAudioCaptureActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(actionButton(getString(R.string.agent_audio_capture_cancel), false) {
                cancelCapture("user_cancelled")
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginEnd = dp(8)
            })
            stopButton = actionButton(getString(R.string.agent_audio_capture_stop), true) {
                stopAndComplete("user_stop")
            }.apply { isEnabled = false }
            addView(stopButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(8)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        setTextColor(if (primary) Color.WHITE else 0xFFCBD2DC.toInt())
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (primary) 0xFF0A84FF.toInt() else 0xFF252A32.toInt())
        }
        setOnClickListener { onClick() }
    }

    private fun startRecording() {
        if (recorder != null || outcomeReported.get()) return
        val file = File(cacheDir, "agent_audio_${System.currentTimeMillis()}.m4a")
        var candidate: MediaRecorder? = null
        runCatching {
            candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            candidate!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                setMaxDuration(maxDurationSeconds * 1_000)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        runOnUiThread { stopAndComplete("max_duration") }
                    }
                }
                prepare()
                start()
            }
            recorder = candidate
            temporaryFile = file
            startedAtElapsedMillis = SystemClock.elapsedRealtime()
            status.text = getString(R.string.agent_audio_capture_recording, maxDurationSeconds)
            handler.postDelayed({ stopButton.isEnabled = true }, MIN_STOP_DELAY_MILLIS)
            handler.post(::updateCountdown)
        }.onFailure { error ->
            runCatching { candidate?.reset() }
            runCatching { candidate?.release() }
            file.delete()
            finishWithFailure(
                "microphone_unavailable",
                error.message ?: getString(R.string.agent_audio_capture_failed)
            )
        }
    }

    private fun updateCountdown() {
        if (recorder == null || stopStarted.get()) return
        val elapsed = SystemClock.elapsedRealtime() - startedAtElapsedMillis
        val remaining = (maxDurationSeconds - elapsed / 1_000L).coerceAtLeast(0L)
        status.text = getString(R.string.agent_audio_capture_recording, remaining)
        handler.postDelayed(::updateCountdown, 250L)
    }

    private fun stopAndComplete(completedBy: String) {
        if (!stopStarted.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        val activeRecorder = recorder
        recorder = null
        val stopped = runCatching {
            activeRecorder?.stop()
            true
        }.getOrDefault(false)
        runCatching { activeRecorder?.reset() }
        runCatching { activeRecorder?.release() }
        val file = temporaryFile
        temporaryFile = null
        if (!stopped || file == null || !file.exists() || file.length() <= 0L) {
            file?.delete()
            finishWithFailure("audio_capture_failed", getString(R.string.agent_audio_capture_failed))
            return
        }
        val durationMillis = (SystemClock.elapsedRealtime() - startedAtElapsedMillis).coerceAtLeast(1L)
        runCatching {
            val sizeBytes = file.length()
            val uri = persistAudio(file)
            file.delete()
            reportOutcome(
                AgentVisibleCaptureOutcome(
                    AgentVisibleCaptureStatus.SUCCEEDED,
                    artifact = AgentVisibleCaptureArtifact(
                        kind = AgentVisibleCaptureKind.AUDIO,
                        contentUri = uri.toString(),
                        mimeType = "audio/mp4",
                        sizeBytes = sizeBytes,
                        durationMillis = durationMillis,
                        capturedAtEpochMillis = System.currentTimeMillis(),
                        completedBy = completedBy
                    )
                )
            )
            finish()
        }.onFailure { error ->
            file.delete()
            finishWithFailure(
                "audio_save_failed",
                error.message ?: getString(R.string.agent_audio_capture_save_failed)
            )
        }
    }

    private fun persistAudio(source: File): Uri {
        val name = "SignalASI_${System.currentTimeMillis()}.m4a"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/SignalASI")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore did not create the audio artifact")
            contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Could not open the audio artifact output")
            contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null
            )
            return uri
        }
        val directory = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "SignalASI").apply { mkdirs() }
        val target = File(directory, name)
        source.inputStream().use { input -> FileOutputStream(target).use(input::copyTo) }
        MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf("audio/mp4"), null)
        return Uri.fromFile(target)
    }

    private fun cancelCapture(reason: String) {
        if (!stopStarted.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        val activeRecorder = recorder
        recorder = null
        runCatching { activeRecorder?.stop() }
        runCatching { activeRecorder?.reset() }
        runCatching { activeRecorder?.release() }
        temporaryFile?.delete()
        temporaryFile = null
        reportOutcome(
            AgentVisibleCaptureOutcome(
                AgentVisibleCaptureStatus.CANCELLED,
                code = "capture_cancelled",
                message = "The user cancelled the audio capture ($reason)"
            )
        )
        finish()
    }

    private fun finishWithFailure(code: String, message: String) {
        reportOutcome(AgentVisibleCaptureOutcome(AgentVisibleCaptureStatus.FAILED, code = code, message = message))
        if (::status.isInitialized) status.text = message
        handler.postDelayed(::finish, 500L)
    }

    private fun reportOutcome(outcome: AgentVisibleCaptureOutcome) {
        if (requestId.isBlank() || !outcomeReported.compareAndSet(false, true)) return
        AgentVisibleCaptureCoordinator.complete(requestId, outcome)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            finishWithFailure("microphone_permission_required", getString(R.string.agent_audio_capture_permission_required))
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        cancelCapture("back")
    }

    override fun onPause() {
        if (!isFinishing && recorder != null) cancelCapture("left_foreground")
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (recorder != null || temporaryFile != null) {
            val activeRecorder = recorder
            recorder = null
            runCatching { activeRecorder?.stop() }
            runCatching { activeRecorder?.reset() }
            runCatching { activeRecorder?.release() }
            temporaryFile?.delete()
            temporaryFile = null
        }
        if (requestId.isNotBlank() && !outcomeReported.get()) {
            reportOutcome(
                AgentVisibleCaptureOutcome(
                    AgentVisibleCaptureStatus.CANCELLED,
                    code = "capture_surface_closed",
                    message = "The visible audio capture surface closed before completion"
                )
            )
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_AUDIO = 4201
        const val DEFAULT_DURATION_SECONDS = 5
        const val MAX_DURATION_SECONDS = 30
        const val MIN_STOP_DELAY_MILLIS = 500L
    }
}
