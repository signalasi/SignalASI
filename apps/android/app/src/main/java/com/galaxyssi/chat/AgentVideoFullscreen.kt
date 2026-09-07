package com.galaxyssi.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.MediaController
import android.widget.VideoView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object AgentVideoFullscreen {
    fun attach(
        activity: Activity,
        inline: VideoView,
        controls: MediaController,
        uri: Uri,
        activate: (VideoView) -> Unit,
        detach: (VideoView) -> Unit
    ) {
        var activeDialog: Dialog? = null
        bindGestures(inline, controls) {
            if (activeDialog != null || activity.isFinishing || activity.isDestroyed) return@bindGestures
            val position = inline.currentPosition
            val wasPlaying = inline.isPlaying
            inline.pause()
            val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
            val video = VideoView(activity).apply { contentDescription = inline.contentDescription }
            val fullscreenControls = MediaController(activity).apply { setAnchorView(video) }
            video.setMediaController(fullscreenControls)
            var prepared = false
            var resumePosition = position
            var resumePlaying = wasPlaying
            val dialog = object : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
                override fun dismiss() {
                    // Surface teardown resets VideoView before OnDismissListener runs.
                    if (isShowing && prepared) {
                        resumePosition = video.currentPosition
                        resumePlaying = video.isPlaying
                    }
                    super.dismiss()
                }
            }
            activeDialog = dialog
            video.setOnPreparedListener {
                prepared = true
                video.seekTo(position)
                if (wasPlaying) { activate(video); video.start() }
            }
            video.setOnClickListener {
                if (prepared) {
                    activate(video)
                    if (video.isPlaying) video.pause() else video.start()
                }
            }
            video.setOnCompletionListener { detach(video) }
            bindGestures(video, fullscreenControls) { dialog.dismiss() }
            root.addView(video, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            val buttonSize = (48 * activity.resources.displayMetrics.density).toInt()
            root.addView(ImageButton(activity).apply {
                setImageResource(R.drawable.ic_agent_progress_close)
                setColorFilter(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = activity.getString(R.string.common_close)
                setOnClickListener { dialog.dismiss() }
            }, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP or Gravity.END))
            dialog.setContentView(root)
            dialog.setOnDismissListener {
                fullscreenControls.hide()
                detach(video)
                video.stopPlayback()
                activeDialog = null
                if (inline.isAttachedToWindow && !activity.isFinishing && !activity.isDestroyed) {
                    inline.seekTo(resumePosition)
                    if (resumePlaying) { activate(inline); inline.start() }
                }
            }
            dialog.show()
            dialog.window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.setDecorFitsSystemWindows(this, false)
                WindowInsetsControllerCompat(this, decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            video.setVideoURI(uri)
        }
        inline.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { activeDialog?.dismiss() }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindGestures(video: VideoView, controls: MediaController, doubleTap: () -> Unit) {
        val gestures = GestureDetector(video.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                video.performClick()
                controls.show(3000)
                return true
            }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                controls.hide()
                doubleTap()
                return true
            }
        })
        video.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
    }
}
