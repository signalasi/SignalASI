package com.galaxyssi.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

internal class PeerImageAttachmentView(context: Context) : FrameLayout(context) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val transferShade = View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(AGENT_IMAGE_THUMBNAIL_RADIUS_DP).toFloat()
            setColor(Color.parseColor("#66000000"))
        }
    }
    private val progressRing = PeerImageTransferProgressView(context)
    private var requestKey = ""

    init {
        background = GradientDrawable().apply {
            cornerRadius = dp(AGENT_IMAGE_THUMBNAIL_RADIUS_DP).toFloat()
            setColor(Color.parseColor("#F4F6F8"))
        }
        clipToOutline = true
        isClickable = true
        isFocusable = true
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(transferShade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(progressRing, LayoutParams(dp(58), dp(58), Gravity.CENTER))
    }

    fun bind(
        attachment: PeerChatAttachment,
        onOpen: () -> Unit,
        onLongPress: () -> Unit
    ) {
        requestKey = "${attachment.artifactUri}|${attachment.uri}|${attachment.transferId}"
        contentDescription = attachment.name
        image.setImageResource(R.drawable.ic_process_image)
        image.imageTintList = ColorStateList.valueOf(Color.parseColor("#8B929A"))
        image.setPadding(dp(34), dp(50), dp(34), dp(50))

        val active = PeerAttachmentTransferProgress.isActive(
            attachment.transferProgress,
            attachment.transferState
        )
        transferShade.visibility = if (active) View.VISIBLE else View.GONE
        progressRing.visibility = if (active) View.VISIBLE else View.GONE
        progressRing.progress = attachment.transferProgress.coerceIn(0, 99)
        setOnClickListener { if (!active) onOpen() }
        setOnLongClickListener {
            onLongPress()
            true
        }

        val expectedKey = requestKey
        PeerImageThumbnailRepository.load(
            context.applicationContext,
            attachment,
            dp(AGENT_IMAGE_THUMBNAIL_HEIGHT_DP * 2),
            dp(AGENT_IMAGE_THUMBNAIL_HEIGHT_DP * 2)
        ) { bitmap ->
            if (requestKey == expectedKey && bitmap != null) {
                image.imageTintList = null
                image.setPadding(0, 0, 0, 0)
                image.setImageBitmap(bitmap)
                val size = agentImageThumbnailSize(bitmap.width, bitmap.height)
                layoutParams = layoutParams.apply {
                    width = dp(size.widthDp)
                    height = dp(size.heightDp)
                }
            }
        }
    }

    fun updateProgress(percent: Int) { progressRing.progress = percent.coerceIn(0, 99) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class PeerImageTransferProgressView(context: Context) : View(context) {
    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(3).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(track).apply { color = Color.WHITE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(11).toFloat()
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = track.strokeWidth / 2f + dp(3)
        val bounds = RectF(inset, inset, width - inset, height - inset)
        canvas.drawArc(bounds, -90f, 360f, false, track)
        canvas.drawArc(bounds, -90f, 360f * progress / 100f, false, arc)
        val baseline = height / 2f - (label.ascent() + label.descent()) / 2f
        canvas.drawText("$progress%", width / 2f, baseline, label)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
