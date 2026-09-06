package com.galaxyssi.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal class PeerFileAttachmentView(context: Context) : FrameLayout(context) {
    private val fileIcon = ImageView(context).apply {
        setImageResource(R.drawable.ic_rich_file)
        imageTintList = ColorStateList.valueOf(context.getColor(R.color.galaxyssi_blue))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }
    private val progressRing = PeerTransferProgressRingView(context)
    private val nameView = TextView(context).apply {
        setTextColor(context.getColor(R.color.text_primary))
        textSize = 14f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
    }
    private val detailView = TextView(context).apply {
        setTextColor(context.getColor(R.color.text_secondary))
        textSize = 12f
        maxLines = 1
    }

    init {
        minimumHeight = dp(68)
        isClickable = true
        isFocusable = true
        addView(fileIcon, LayoutParams(dp(40), dp(40), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(13)
        })
        addView(progressRing, LayoutParams(dp(40), dp(40), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(13)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(nameView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(detailView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(65)
            marginEnd = dp(13)
        })
    }

    fun bind(
        attachment: PeerChatAttachment,
        mine: Boolean,
        maxWidthPx: Int,
        onOpen: () -> Unit,
        onLongPress: () -> Unit
    ) {
        val active = PeerAttachmentTransferProgress.isActive(
            attachment.transferProgress,
            attachment.transferState
        )
        val available = attachment.transferState == PeerAttachmentTransferProgress.STATE_AVAILABLE
        val failed = attachment.transferState == PeerAttachmentTransferProgress.STATE_FAILED
        val needsDownload = !mine && (available || failed)
        nameView.text = attachment.name
        val size = AgentInputAttachment.humanSize(attachment.sizeBytes)
        detailView.text = when {
            active -> context.getString(
                R.string.peer_attachment_downloading_progress,
                size,
                attachment.transferProgress.coerceIn(0, 99)
            )
            !mine && available -> context.getString(R.string.peer_attachment_not_downloaded, size)
            !mine && failed -> context.getString(R.string.peer_attachment_download_retry, size)
            else -> size
        }
        fileIcon.setImageResource(
            if (needsDownload) R.drawable.ic_rich_download else R.drawable.ic_rich_file
        )
        fileIcon.visibility = if (active) View.INVISIBLE else View.VISIBLE
        progressRing.visibility = if (active) View.VISIBLE else View.INVISIBLE
        progressRing.progress = attachment.transferProgress.coerceIn(0, 100)
        background = context.getDrawable(
            if (mine) R.drawable.bubble_self_background else R.drawable.bubble_other_background
        )
        minimumWidth = dp(200).coerceAtMost(maxWidthPx)
        setOnClickListener { if (!active) onOpen() }
        setOnLongClickListener {
            onLongPress()
            true
        }
    }

    fun updateProgress(attachment: PeerChatAttachment) {
        progressRing.progress = attachment.transferProgress.coerceIn(0, 99)
        detailView.text = context.getString(R.string.peer_attachment_downloading_progress,
            AgentInputAttachment.humanSize(attachment.sizeBytes), attachment.transferProgress.coerceIn(0, 99))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

internal class PeerTransferProgressRingView(context: Context) : View(context) {
    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.separator)
        style = Paint.Style.STROKE
        strokeWidth = dp(3).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(track).apply { color = context.getColor(R.color.galaxyssi_blue) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.galaxyssi_blue)
        textAlign = Paint.Align.CENTER
        textSize = dp(10).toFloat()
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = track.strokeWidth / 2f + dp(2)
        val bounds = RectF(inset, inset, width - inset, height - inset)
        canvas.drawArc(bounds, -90f, 360f, false, track)
        canvas.drawArc(bounds, -90f, 360f * progress / 100f, false, arc)
        val baseline = height / 2f - (label.ascent() + label.descent()) / 2f
        canvas.drawText("$progress%", width / 2f, baseline, label)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
