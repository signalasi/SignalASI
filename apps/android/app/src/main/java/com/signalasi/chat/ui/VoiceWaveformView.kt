package com.signalasi.chat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.ln
import kotlin.math.min

class VoiceWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val samples = FloatArray(24) { BASELINE }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RECORDING_COLOR
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var smoothed = BASELINE

    fun pushAmplitude(rawAmplitude: Int) {
        val safe = rawAmplitude.coerceIn(0, 32767)
        val normalized = if (safe == 0) BASELINE else {
            (BASELINE + (ln(safe.toDouble() + 1.0) / ln(32768.0)).toFloat() * (1f - BASELINE))
                .coerceIn(BASELINE, 1f)
        }
        smoothed = (smoothed * 0.58f + normalized * 0.42f).coerceIn(BASELINE, 1f)
        System.arraycopy(samples, 1, samples, 0, samples.lastIndex)
        samples[samples.lastIndex] = smoothed
        invalidate()
    }

    fun setCancelPending(cancelPending: Boolean) {
        paint.color = if (cancelPending) CANCEL_COLOR else RECORDING_COLOR
        invalidate()
    }

    fun reset() {
        samples.fill(BASELINE)
        smoothed = BASELINE
        paint.color = RECORDING_COLOR
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val step = width.toFloat() / samples.size
        paint.strokeWidth = min(2.8f * density, step * 0.38f)
        val centerY = height / 2f
        val minHeight = 4f * density
        val maxHeight = (height - 4f * density).coerceAtLeast(minHeight)
        samples.forEachIndexed { index, amplitude ->
            val barHeight = minHeight + (maxHeight - minHeight) * amplitude
            val x = step * index + step / 2f
            canvas.drawLine(x, centerY - barHeight / 2f, x, centerY + barHeight / 2f, paint)
        }
    }

    private companion object {
        const val BASELINE = 0.10f
        val RECORDING_COLOR = Color.parseColor("#2D7DFF")
        val CANCEL_COLOR = Color.parseColor("#FF3B30")
    }
}
