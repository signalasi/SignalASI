package com.galaxyssi.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.galaxyssi.chat.blob.AndroidBlobArtifactReceives
import com.galaxyssi.chat.blob.BlobAgentArtifactKey
import com.galaxyssi.chat.blob.BlobArtifactCardUpdates
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class BlobAgentArtifactSnapshot(val resolved: AgentRichBlock? = null, val event: JSONObject? = null)

/** A stable card during transfer. Completion replaces only this card, never its transcript row. */
internal class BlobAgentArtifactCardView(
    context: Context,
    private val source: AgentRichBlock,
    private val key: BlobAgentArtifactKey?,
    private val renderResolved: (AgentRichBlock) -> View,
    private val load: (Context, AgentRichBlock, BlobAgentArtifactKey) -> BlobAgentArtifactSnapshot = ::readSnapshot,
    private val compact: Boolean = false
) : FrameLayout(context), BlobArtifactCardUpdates.Listener {
    private val status = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.parseColor("#777C84"))
        maxLines = 2
    }
    private val ring = PeerTransferProgressRingView(context)
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val placeholder = LinearLayout(context).apply {
        orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(if (compact) 168 else 58)
        setPadding(dp(10), dp(8), dp(7), dp(8))
        background = GradientDrawable().apply { setColor(Color.parseColor("#F7F7F8")); cornerRadius = dp(8).toFloat() }
        addView(ImageView(context).apply {
            visibility = if (compact) View.GONE else View.VISIBLE
            setImageResource(R.drawable.ic_rich_file)
            setColorFilter(context.getColor(R.color.galaxyssi_blue))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = source.title.ifBlank { source.text }.ifBlank { context.getString(R.string.rich_output_download) }
                textSize = 15f
                maxLines = 2
                setTextColor(Color.parseColor("#14202B"))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(status)
        }, LinearLayout.LayoutParams(if (compact) ViewGroup.LayoutParams.MATCH_PARENT else 0,
            ViewGroup.LayoutParams.WRAP_CONTENT, if (compact) 0f else 1f).apply { if (!compact) marginStart = dp(9) })
        addView(FrameLayout(context).apply {
            addView(ring, LayoutParams(dp(40), dp(40), Gravity.CENTER))
            addView(spinner, LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
    }
    private var ready = false
    private var verifying = false
    private var attachmentEpoch = 0L
    private var eventVersion = 0L
    private var readInFlight = false
    private var readAgain = false
    private var readTask: Future<*>? = null

    init {
        isSaveEnabled = false
        addView(placeholder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        showStatus(if (key == null) R.string.blob_artifact_unavailable else R.string.rich_output_download_preparing)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachmentEpoch++
        if (key != null) {
            BlobArtifactCardUpdates.add(this)
            requestSnapshot()
        }
    }

    override fun onDetachedFromWindow() {
        attachmentEpoch++
        readInFlight = false
        readAgain = false
        readTask?.let { task -> task.cancel(false); (task as? Runnable)?.let(io::remove) }
        readTask = null
        BlobArtifactCardUpdates.remove(this)
        super.onDetachedFromWindow()
    }

    override fun onArtifactEvent(event: JSONObject) {
        if (!isAttachedToWindow || key?.matches(event) != true || ready) return
        if (verifying) return
        eventVersion++
        when (event.optString("type")) {
            "artifact_available" -> {
                verifying = true
                showStatus(R.string.blob_artifact_verifying)
                requestSnapshot()
            }
            "artifact_download_failed" -> if (!verifying) showStatus(R.string.blob_artifact_failed)
            else -> if (!verifying) {
                val progress = event.optInt("progress").coerceIn(0, 99)
                ring.progress = progress
                ring.visibility = View.VISIBLE
                spinner.visibility = View.GONE
                status.text = context.getString(R.string.blob_artifact_progress, progress)
            }
        }
    }

    private fun requestSnapshot() {
        val identity = key ?: return
        if (readInFlight) { readAgain = true; return }
        readInFlight = true
        val epoch = attachmentEpoch
        val version = eventVersion
        val reference = WeakReference(this)
        val app = context.applicationContext
        val block = source
        val loader = load
        readTask = io.submit {
            val result = runCatching { loader(app, block, identity) }
            main.post {
                reference.get()?.takeIf { it.isAttachedToWindow && it.attachmentEpoch == epoch }
                    ?.receiveSnapshot(result, version)
            }
        }
    }

    private fun receiveSnapshot(result: Result<BlobAgentArtifactSnapshot>, version: Long) {
        readInFlight = false
        readTask = null
        val snapshot = result.getOrNull()
        if (snapshot?.resolved != null) {
            if (!ready) {
                ready = true
                verifying = false
                removeAllViews()
                addView(renderResolved(snapshot.resolved), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        } else if (version == eventVersion) {
            // An older disk snapshot must not roll back a newer live progress event.
            val event = snapshot?.event
            val failed = result.isFailure || verifying || event?.optString("type") in
                setOf("artifact_download_failed", "artifact_available")
            verifying = false
            if (ready) {
                ready = false
                removeAllViews()
                addView(placeholder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            showStatus(if (failed) R.string.blob_artifact_failed else R.string.rich_output_download_preparing)
        }
        if (readAgain && !ready) { readAgain = false; requestSnapshot() } else readAgain = false
    }

    private fun showStatus(label: Int) {
        status.setText(label)
        ring.visibility = View.GONE
        spinner.visibility = if (label in setOf(R.string.rich_output_download_preparing, R.string.blob_artifact_verifying)) {
            View.VISIBLE
        } else View.GONE
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    companion object {
        private val io = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(),
            { task -> Thread(task, "blob-card-read") })
        private val main = Handler(Looper.getMainLooper())

        private fun readSnapshot(context: Context, source: AgentRichBlock, key: BlobAgentArtifactKey): BlobAgentArtifactSnapshot {
            check(Looper.myLooper() != Looper.getMainLooper())
            val resolved = AgentDesktopArtifactStore.resolveBlock(context, source.copy(uri = key.uri,
                metadata = source.metadata + ("artifact_source_uri" to key.uri)))
                .takeIf { it.uri.startsWith("content://") }
            if (resolved != null) return BlobAgentArtifactSnapshot(resolved)
            val event = AndroidBlobArtifactReceives.presentation(context, key.transfer)?.takeIf(key::matches)
            return BlobAgentArtifactSnapshot(event = event)
        }
    }
}
