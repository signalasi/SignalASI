package com.signalasi.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class AgentAutoCaptureActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewRequest: CaptureRequest.Builder? = null
    private var cameraId: String = ""
    private var sensorOrientation: Int = 90
    private var lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    private var supportsAutoFocus = false
    private var openingCamera = false
    private var requestId: String = ""
    private var requestedFacing: String = "back"
    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    private val captureStarted = AtomicBoolean(false)
    private val outcomeReported = AtomicBoolean(false)
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(AgentVisibleCaptureContract.EXTRA_REQUEST_ID).orEmpty()
        requestedFacing = intent.getStringExtra(AgentVisibleCaptureContract.EXTRA_CAMERA_FACING)
            ?.takeIf { it in setOf("back", "front", "any") }
            ?: "back"
        if (requestId.isBlank() && !acceptCaptureLaunch()) {
            Log.i(LOG_TAG, "Ignoring duplicate automatic capture launch")
            finish()
            return
        }
        Log.i(LOG_TAG, "Starting automatic focus capture")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        cameraManager = getSystemService(CameraManager::class.java)
        preview = TextureView(this).apply { surfaceTextureListener = this@AgentAutoCaptureActivity }
        status = TextView(this).apply {
            text = getString(R.string.agent_camera_focusing)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setBackgroundColor(0x66000000)
            setPadding(24, 16, 24, 16)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)
        if (requestId.isNotBlank() && !AgentVisibleCaptureCoordinator.attach(
                requestId,
                AgentVisibleCaptureKind.PHOTO,
                this
            )
        ) {
            failAndFinish("The camera capture request is no longer active", "capture_request_missing")
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        startWorker()
        if (preview.isAvailable && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.i(LOG_TAG, "Ignoring duplicate automatic capture intent")
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        reportOutcome(
            AgentVisibleCaptureOutcome(
                AgentVisibleCaptureStatus.CANCELLED,
                code = "capture_cancelled",
                message = "The user cancelled the photo capture"
            )
        )
        super.onBackPressed()
    }

    override fun onPause() {
        closeCamera()
        stopWorker()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (preview.isAvailable) openCamera()
        } else {
            failAndFinish(getString(R.string.agent_camera_permission_required))
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = openCamera()
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (openingCamera || cameraDevice != null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val candidates = cameraManager.cameraIdList
            .map { it to cameraManager.getCameraCharacteristics(it) }
        val desiredFacing = when (requestedFacing) {
            "front" -> CameraCharacteristics.LENS_FACING_FRONT
            "back" -> CameraCharacteristics.LENS_FACING_BACK
            else -> null
        }
        val selected = desiredFacing?.let { facing ->
            candidates.firstOrNull { (_, characteristics) ->
                characteristics.get(CameraCharacteristics.LENS_FACING) == facing
            }
        } ?: candidates.firstOrNull()
        if (selected == null) {
            failAndFinish(getString(R.string.agent_camera_unavailable))
            return
        }
        cameraId = selected.first
        val characteristics = selected.second
        lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        supportsAutoFocus = modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.ImageFormat.JPEG)
            .orEmpty()
        val captureSize = sizes.filter { it.width.toLong() * it.height <= MAX_CAPTURE_PIXELS }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height }
        if (captureSize == null) {
            failAndFinish(getString(R.string.agent_camera_unavailable))
            return
        }
        captureWidth = captureSize.width
        captureHeight = captureSize.height
        imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, android.graphics.ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val bytes = image.use {
                    val buffer = it.planes.first().buffer
                    ByteArray(buffer.remaining()).also(buffer::get)
                }
                val uri = runCatching { savePhoto(bytes) }.getOrElse {
                    runOnUiThread { failAndFinish(it.message ?: getString(R.string.agent_camera_save_failed)) }
                    return@setOnImageAvailableListener
                }
                runOnUiThread {
                    status.text = getString(R.string.agent_camera_saved)
                    reportOutcome(
                        AgentVisibleCaptureOutcome(
                            AgentVisibleCaptureStatus.SUCCEEDED,
                            artifact = AgentVisibleCaptureArtifact(
                                kind = AgentVisibleCaptureKind.PHOTO,
                                contentUri = uri.toString(),
                                mimeType = "image/jpeg",
                                sizeBytes = bytes.size.toLong(),
                                widthPixels = captureWidth,
                                heightPixels = captureHeight,
                                capturedAtEpochMillis = System.currentTimeMillis(),
                                completedBy = "autofocus_capture"
                            )
                        )
                    )
                    setResult(RESULT_OK, android.content.Intent().setData(uri))
                    status.postDelayed(::finish, 700L)
                }
            }, worker)
        }
        openingCamera = true
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                openingCamera = false
                cameraDevice = camera
                createPreviewSession(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                openingCamera = false
                camera.close()
                runOnUiThread { failAndFinish(getString(R.string.agent_camera_unavailable)) }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                openingCamera = false
                camera.close()
                runOnUiThread { failAndFinish(getString(R.string.agent_camera_unavailable)) }
            }
        }, worker)
    }

    private fun createPreviewSession(camera: CameraDevice) {
        val texture = preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(preview.width.coerceAtLeast(1), preview.height.coerceAtLeast(1))
        val previewSurface = Surface(texture)
        val readerSurface = imageReader?.surface ?: return
        previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_AF_MODE, if (supportsAutoFocus) CaptureRequest.CONTROL_AF_MODE_AUTO else CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        camera.createCaptureSession(listOf(previewSurface, readerSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cameraDevice == null) return
                captureSession = session
                val builder = previewRequest ?: return
                session.setRepeatingRequest(builder.build(), null, worker)
                runOnUiThread { status.text = getString(R.string.agent_camera_focusing) }
                if (supportsAutoFocus) startFocusLock(session, builder) else worker?.postDelayed(::capturePhoto, NO_AF_DELAY_MS)
                worker?.postDelayed(::capturePhoto, FOCUS_TIMEOUT_MS)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                runOnUiThread { failAndFinish(getString(R.string.agent_camera_unavailable)) }
            }
        }, worker)
    }

    private fun startFocusLock(session: CameraCaptureSession, builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
                handleFocusState(partialResult.get(CaptureResult.CONTROL_AF_STATE))
            }

            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                handleFocusState(result.get(CaptureResult.CONTROL_AF_STATE))
            }
        }, worker)
    }

    private fun handleFocusState(state: Int?) {
        if (state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
        ) capturePhoto()
    }

    private fun capturePhoto() {
        if (!captureStarted.compareAndSet(false, true)) return
        Log.i(LOG_TAG, "Focus complete; capturing one photo")
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = imageReader?.surface ?: return
        runOnUiThread { status.text = getString(R.string.agent_camera_capturing) }
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_AF_MODE, if (supportsAutoFocus) CaptureRequest.CONTROL_AF_MODE_AUTO else CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
        }
        runCatching {
            session.stopRepeating()
            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {}, worker)
        }.onFailure { error ->
            runOnUiThread { failAndFinish(error.message ?: getString(R.string.agent_camera_capture_failed)) }
        }
    }

    private fun jpegOrientation(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: Surface.ROTATION_0
        else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        val deviceOrientation = when (rotation) {
            Surface.ROTATION_90 -> 0
            Surface.ROTATION_180 -> 270
            Surface.ROTATION_270 -> 180
            else -> 90
        }
        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation - deviceOrientation + 360) % 360
        } else {
            (deviceOrientation + sensorOrientation + 270) % 360
        }
    }

    private fun savePhoto(bytes: ByteArray): Uri {
        val name = "SignalASI_${System.currentTimeMillis()}.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SignalASI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore did not create the photo")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not open photo output")
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
        val directory = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "SignalASI").apply { mkdirs() }
        val file = File(directory, name)
        FileOutputStream(file).use { it.write(bytes) }
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
        return Uri.fromFile(file)
    }

    private fun failAndFinish(message: String, code: String = "camera_capture_failed") {
        reportOutcome(
            AgentVisibleCaptureOutcome(
                AgentVisibleCaptureStatus.FAILED,
                code = code,
                message = message
            )
        )
        status.text = message
        status.postDelayed(::finish, 1_200L)
    }

    private fun reportOutcome(outcome: AgentVisibleCaptureOutcome) {
        if (requestId.isBlank() || !outcomeReported.compareAndSet(false, true)) return
        AgentVisibleCaptureCoordinator.complete(requestId, outcome)
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        openingCamera = false
    }

    private fun startWorker() {
        if (workerThread != null) return
        workerThread = HandlerThread("signalasi-auto-camera").also { it.start() }
        worker = Handler(workerThread!!.looper)
    }

    private fun stopWorker() {
        workerThread?.quitSafely()
        runCatching { workerThread?.join(1_000L) }
        workerThread = null
        worker = null
    }

    override fun onDestroy() {
        if (requestId.isNotBlank() && !outcomeReported.get()) {
            reportOutcome(
                AgentVisibleCaptureOutcome(
                    AgentVisibleCaptureStatus.CANCELLED,
                    code = "capture_surface_closed",
                    message = "The visible camera surface closed before capture completed"
                )
            )
        }
        super.onDestroy()
    }

    private companion object {
        const val LOG_TAG = "SignalASICamera"
        const val REQUEST_CAMERA = 4101
        const val FOCUS_TIMEOUT_MS = 4_000L
        const val NO_AF_DELAY_MS = 800L
        const val MAX_CAPTURE_PIXELS = 12_000_000L
        const val DUPLICATE_LAUNCH_WINDOW_MS = 8_000L

        private var lastAcceptedLaunchAt = 0L

        @Synchronized
        fun acceptCaptureLaunch(now: Long = SystemClock.elapsedRealtime()): Boolean {
            if (now - lastAcceptedLaunchAt < DUPLICATE_LAUNCH_WINDOW_MS) return false
            lastAcceptedLaunchAt = now
            return true
        }
    }
}
