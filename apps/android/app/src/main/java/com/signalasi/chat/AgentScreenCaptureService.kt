package com.signalasi.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

class AgentScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private val captureRequested = AtomicBoolean(false)
    private val ocr by lazy { AgentScreenOcr(applicationContext) }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseProjection()
            ScreenPerceptionState.clearVisual(getString(R.string.agent_screen_capture_permission_ended))
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("SignalASI-AgentScreenCapture").apply { start() }
        captureHandler = Handler(captureThread.looper)
        ensureNotificationChannel()
        startCaptureForeground()
        active = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProjection(
                intent.getIntExtra(EXTRA_RESULT_CODE, 0),
                projectionData(intent)
            )
            ACTION_CAPTURE -> requestNextFrame()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        releaseProjection()
        ocr.close()
        captureThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProjection(resultCode: Int, resultData: Intent?) {
        if (resultCode == 0 || resultData == null) {
            ScreenPerceptionState.clearVisual(getString(R.string.agent_screen_capture_missing_authorization))
            stopSelf()
            return
        }
        releaseProjection()
        val manager = getSystemService(MediaProjectionManager::class.java)
        val nextProjection = runCatching { manager.getMediaProjection(resultCode, resultData) }.getOrNull()
        if (nextProjection == null) {
            ScreenPerceptionState.clearVisual(getString(R.string.agent_screen_capture_failed))
            stopSelf()
            return
        }
        projection = nextProjection.apply { registerCallback(projectionCallback, captureHandler) }
        createDisplay(nextProjection)
        captureHandler.postDelayed({ requestNextFrame() }, INITIAL_CAPTURE_DELAY_MILLIS)
    }

    private fun createDisplay(mediaProjection: MediaProjection) {
        val size = captureSize()
        val reader = ImageReader.newInstance(size.first, size.second, PixelFormat.RGBA_8888, MAX_IMAGES)
        imageReader = reader
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "SignalASI-AgentScreen",
            size.first,
            size.second,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )
    }

    private fun requestNextFrame() {
        if (projection == null || imageReader == null) {
            ScreenPerceptionState.updateVisual(
                AgentVisualScreenResult.failure(getString(R.string.agent_screen_capture_missing_authorization))
            )
            return
        }
        if (!captureRequested.compareAndSet(false, true)) return
        captureHandler.postDelayed({ captureLatestFrame(0) }, FRAME_READY_DELAY_MILLIS)
    }

    private fun captureLatestFrame(attempt: Int) {
        val image = runCatching { imageReader?.acquireLatestImage() }.getOrNull()
        if (image != null) {
            captureRequested.set(false)
            processImage(image)
            return
        }
        if (attempt < MAX_CAPTURE_RETRIES && projection != null) {
            captureHandler.postDelayed(
                { captureLatestFrame(attempt + 1) },
                FRAME_RETRY_DELAY_MILLIS
            )
        } else {
            captureRequested.set(false)
            ScreenPerceptionState.updateVisual(
                AgentVisualScreenResult.failure(getString(R.string.agent_screen_capture_image_failed))
            )
        }
    }

    private fun processImage(image: Image) {
        val sourcePackage = ScreenPerceptionState.currentPackageName()
        val bitmap = runCatching { image.toBitmap() }.getOrNull()
        image.close()
        if (bitmap == null) {
            ScreenPerceptionState.updateVisual(
                AgentVisualScreenResult.failure(getString(R.string.agent_screen_capture_image_failed))
            )
            return
        }
        ocr.process(bitmap, sourcePackage) { result ->
            bitmap.recycle()
            ScreenPerceptionState.updateVisual(result)
        }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.first()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) return padded
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }

    private fun captureSize(): Pair<Int, Int> {
        val windowManager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width().coerceAtLeast(MIN_CAPTURE_SIZE) to bounds.height().coerceAtLeast(MIN_CAPTURE_SIZE)
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.widthPixels.coerceAtLeast(MIN_CAPTURE_SIZE) to
                resources.displayMetrics.heightPixels.coerceAtLeast(MIN_CAPTURE_SIZE)
        }
    }

    private fun releaseProjection() {
        val currentProjection = projection
        projection = null
        virtualDisplay?.release()
        imageReader?.close()
        currentProjection?.unregisterCallback(projectionCallback)
        currentProjection?.stop()
        virtualDisplay = null
        imageReader = null
        captureRequested.set(false)
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.agent_screen_capture_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    private fun startCaptureForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val captureIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentScreenCaptureService::class.java).setAction(ACTION_CAPTURE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_agent_filled)
            .setContentTitle(getString(R.string.agent_screen_capture_notification_title))
            .setContentText(getString(R.string.agent_screen_capture_notification_text))
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_scan,
                    getString(R.string.agent_screen_capture_action),
                    captureIntent
                ).build()
            )
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun projectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    companion object {
        private const val ACTION_START = "com.signalasi.chat.action.START_AGENT_SCREEN_CAPTURE"
        private const val ACTION_CAPTURE = "com.signalasi.chat.action.CAPTURE_AGENT_SCREEN"
        private const val ACTION_STOP = "com.signalasi.chat.action.STOP_AGENT_SCREEN_CAPTURE"
        private const val EXTRA_RESULT_CODE = "screen_capture_result_code"
        private const val EXTRA_RESULT_DATA = "screen_capture_result_data"
        private const val CHANNEL_ID = "signalasi_agent_screen_capture"
        private const val NOTIFICATION_ID = 43001
        private const val MAX_IMAGES = 2
        private const val MIN_CAPTURE_SIZE = 320
        private const val INITIAL_CAPTURE_DELAY_MILLIS = 250L
        private const val FRAME_READY_DELAY_MILLIS = 80L
        private const val FRAME_RETRY_DELAY_MILLIS = 80L
        private const val MAX_CAPTURE_RETRIES = 4

        @Volatile
        private var active = false

        fun isActive(): Boolean = active

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, AgentScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun requestCapture(context: Context): Boolean {
            if (!active) return false
            context.startService(
                Intent(context, AgentScreenCaptureService::class.java).setAction(ACTION_CAPTURE)
            )
            return true
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AgentScreenCaptureService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

private class AgentScreenOcr(private val context: Context) {
    private val recognizer: TextRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
    private val processing = AtomicBoolean(false)

    fun process(
        bitmap: Bitmap,
        sourcePackage: String,
        onResult: (AgentVisualScreenResult) -> Unit
    ) {
        if (!processing.compareAndSet(false, true)) {
            onResult(AgentVisualScreenResult.failure(context.getString(R.string.agent_screen_ocr_busy)))
            return
        }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val lines = text.textBlocks
                    .flatMap { it.lines }
                    .map { it.text.replace(Regex("\\s+"), " ").trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(MAX_OCR_LINES)
                onResult(
                    AgentVisualScreenResult.success(
                        lines,
                        bitmap.width,
                        bitmap.height,
                        sourcePackage
                    )
                )
            }
            .addOnFailureListener { error ->
                onResult(
                    AgentVisualScreenResult.failure(
                        error.message ?: context.getString(R.string.agent_screen_ocr_failed)
                    )
                )
            }
            .addOnCompleteListener { processing.set(false) }
    }

    fun close() = recognizer.close()

    private companion object {
        const val MAX_OCR_LINES = 120
    }
}
