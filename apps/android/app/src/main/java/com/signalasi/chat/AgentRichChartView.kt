package com.signalasi.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max

internal class AgentRichChartView(
    context: Context,
    private val columns: List<String>,
    rows: List<List<String>>
) : View(context) {
    private data class Point(val label: String, val values: List<Float>)

    private val density = resources.displayMetrics.density
    private val points = rows.take(MAX_POINTS).mapNotNull { row ->
        val values = row.drop(1).mapNotNull { it.replace(",", "").toFloatOrNull() }
        if (values.isEmpty()) null else Point(row.firstOrNull().orEmpty(), values.take(MAX_SERIES))
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DDE3E8")
        strokeWidth = density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66717D")
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var reveal = 0f

    init {
        contentDescription = resources.getString(R.string.rich_output_chart_description, points.size)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                reveal = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (230 * density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        val left = 12f * density
        val right = width - 12f * density
        val top = 18f * density
        val bottom = height - 34f * density
        val chartHeight = max(1f, bottom - top)
        val allValues = points.flatMap(Point::values)
        val minimum = minOf(0f, allValues.minOrNull() ?: 0f)
        val maximum = maxOf(0f, allValues.maxOrNull() ?: 0f)
        val range = max(1f, maximum - minimum)

        for (step in 0..4) {
            val y = top + chartHeight * step / 4f
            canvas.drawLine(left, y, right, y, axisPaint)
        }

        val groupWidth = (right - left) / points.size.coerceAtLeast(1)
        val seriesCount = points.maxOfOrNull { it.values.size }?.coerceAtLeast(1) ?: 1
        val gap = 2f * density
        val barWidth = ((groupWidth * 0.72f) - gap * (seriesCount - 1)) / seriesCount
        val zeroY = bottom - ((0f - minimum) / range) * chartHeight
        points.forEachIndexed { pointIndex, point ->
            val groupStart = left + pointIndex * groupWidth + groupWidth * 0.14f
            point.values.forEachIndexed { seriesIndex, value ->
                val x = groupStart + seriesIndex * (barWidth + gap)
                val valueY = bottom - ((value - minimum) / range) * chartHeight
                val animatedY = zeroY + (valueY - zeroY) * reveal
                barPaint.color = SERIES_COLORS[seriesIndex % SERIES_COLORS.size]
                canvas.drawRoundRect(
                    RectF(x, minOf(zeroY, animatedY), x + barWidth, maxOf(zeroY, animatedY)),
                    3f * density,
                    3f * density,
                    barPaint
                )
            }
            if (pointIndex % labelStride(points.size) == 0) {
                labelPaint.textAlign = Paint.Align.CENTER
                val label = point.label.take(12)
                canvas.drawText(label, left + pointIndex * groupWidth + groupWidth / 2f, height - 12f * density, labelPaint)
            }
        }

        val legend = columns.drop(1).take(seriesCount)
        if (legend.size > 1) {
            var x = left
            legend.forEachIndexed { index, label ->
                valuePaint.color = SERIES_COLORS[index % SERIES_COLORS.size]
                valuePaint.textAlign = Paint.Align.LEFT
                canvas.drawText(label.take(14), x, 11f * density, valuePaint)
                x += valuePaint.measureText(label.take(14)) + 18f * density
            }
        }
    }

    private fun labelStride(count: Int): Int = when {
        count <= 6 -> 1
        count <= 12 -> 2
        else -> 3
    }

    private companion object {
        const val MAX_POINTS = 24
        const val MAX_SERIES = 4
        val SERIES_COLORS = intArrayOf(
            Color.parseColor("#0A9480"),
            Color.parseColor("#3578E5"),
            Color.parseColor("#D28A16"),
            Color.parseColor("#8B5CF6")
        )
    }
}
