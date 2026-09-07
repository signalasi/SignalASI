package com.galaxyssi.chat

import android.content.Context
import android.widget.VideoView
import kotlin.math.roundToInt

class AspectRatioVideoView(context: Context) : VideoView(context) {
    private var aspectRatio = 16.0 / 9.0

    fun setVideoDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val updated = width.toDouble() / height
        if (updated != aspectRatio) {
            aspectRatio = updated
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || width <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val height = (width / aspectRatio).roundToInt().coerceAtLeast(1)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }
}
