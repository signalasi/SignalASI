package com.signalasi.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

class DesktopRemoteScreenView(context: Context) : FrameLayout(context) {
    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var zoomScale = MIN_ZOOM
    private var contentTranslationX = 0f
    private var contentTranslationY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var scaledDuringGesture = false

    var onImageTap: ((xRatio: Float, yRatio: Float) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaledDuringGesture = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return imageView.drawable != null
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val previousScale = zoomScale
                val nextScale = (previousScale * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                if (nextScale == previousScale) return true

                val ratio = nextScale / previousScale
                val centerX = width / 2f
                val centerY = height / 2f
                contentTranslationX = detector.focusX - centerX -
                    ratio * (detector.focusX - centerX - contentTranslationX)
                contentTranslationY = detector.focusY - centerY -
                    ratio * (detector.focusY - centerY - contentTranslationY)
                zoomScale = nextScale
                applyTransform()
                return true
            }
        }
    )

    init {
        clipChildren = true
        clipToPadding = true
        isClickable = true
        addView(
            imageView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    fun setScreenshot(bitmap: Bitmap) {
        imageView.setImageBitmap(bitmap)
        resetTransform()
    }

    fun setScreenContentDescription(description: CharSequence) {
        contentDescription = description
        imageView.contentDescription = description
    }

    fun resetTransform() {
        zoomScale = MIN_ZOOM
        contentTranslationX = 0f
        contentTranslationY = 0f
        applyTransform()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                scaledDuringGesture = false
                if (zoomScale > MIN_ZOOM) parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                scaledDuringGesture = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1 && zoomScale > MIN_ZOOM) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                        moved = true
                    }
                    contentTranslationX += dx
                    contentTranslationY += dy
                    applyTransform()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!moved && !scaledDuringGesture) {
                    imagePoint(event.x, event.y)?.let { (xRatio, yRatio) ->
                        performClick()
                        onImageTap?.invoke(xRatio, yRatio)
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }

            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun applyTransform() {
        clampTranslation()
        imageView.pivotX = width / 2f
        imageView.pivotY = height / 2f
        imageView.scaleX = zoomScale
        imageView.scaleY = zoomScale
        imageView.translationX = contentTranslationX
        imageView.translationY = contentTranslationY
    }

    private fun clampTranslation() {
        val bounds = fittedImageBounds() ?: run {
            contentTranslationX = 0f
            contentTranslationY = 0f
            return
        }
        val maxX = ((bounds.width() * zoomScale - width) / 2f).coerceAtLeast(0f)
        val maxY = ((bounds.height() * zoomScale - height) / 2f).coerceAtLeast(0f)
        contentTranslationX = contentTranslationX.coerceIn(-maxX, maxX)
        contentTranslationY = contentTranslationY.coerceIn(-maxY, maxY)
    }

    private fun imagePoint(x: Float, y: Float): Pair<Float, Float>? {
        val bounds = fittedImageBounds() ?: return null
        val centerX = width / 2f
        val centerY = height / 2f
        val unscaledX = (x - centerX - contentTranslationX) / zoomScale + centerX
        val unscaledY = (y - centerY - contentTranslationY) / zoomScale + centerY
        if (!bounds.contains(unscaledX, unscaledY)) return null
        return ((unscaledX - bounds.left) / bounds.width()).coerceIn(0f, 1f) to
            ((unscaledY - bounds.top) / bounds.height()).coerceIn(0f, 1f)
    }

    private fun fittedImageBounds(): RectF? {
        val drawable = imageView.drawable ?: return null
        if (width <= 0 || height <= 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            return null
        }
        val fitScale = min(
            width.toFloat() / drawable.intrinsicWidth,
            height.toFloat() / drawable.intrinsicHeight
        )
        val fittedWidth = drawable.intrinsicWidth * fitScale
        val fittedHeight = drawable.intrinsicHeight * fitScale
        val left = (width - fittedWidth) / 2f
        val top = (height - fittedHeight) / 2f
        return RectF(left, top, left + fittedWidth, top + fittedHeight)
    }

    private companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
    }
}
