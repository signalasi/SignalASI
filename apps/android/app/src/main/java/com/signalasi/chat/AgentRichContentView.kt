package com.signalasi.chat

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil

class AgentRichContentView(
    private val activity: Activity,
    private val onTextViewReady: (TextView) -> Unit,
    private val onAction: (AgentRichAction) -> Unit,
    private val onFormSubmit: (AgentRichBlock, Map<String, String>) -> Unit
) {
    fun create(entry: AgentTranscriptEntry): View {
        val explicit = AgentRichContentCodec.decode(entry.richOutputJson)
        val blocks = explicit.ifEmpty { AgentRichContentCodec.fromText(entry.text) }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
            blocks.forEachIndexed { index, block ->
                val width = if (block.type == AgentRichBlockType.APPROVAL) {
                    (activity.resources.displayMetrics.widthPixels * MAX_ASSISTANT_WIDTH_RATIO).toInt()
                } else {
                    ViewGroup.LayoutParams.MATCH_PARENT
                }
                addView(blockView(block), LinearLayout.LayoutParams(
                    width,
                    if (block.type == AgentRichBlockType.DIVIDER) dp(1) else ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.START
                    if (index > 0) topMargin = dp(blockSpacing(block))
                })
            }
        }
    }

    private fun blockView(block: AgentRichBlock): View = when (block.type) {
        AgentRichBlockType.TEXT -> selectableText(block.text, 16f)
        AgentRichBlockType.HEADING -> selectableText(
            block.text.ifBlank { block.title },
            when (block.metadata["level"]?.toIntOrNull() ?: 2) {
                1 -> 23f
                2 -> 20f
                else -> 17f
            }
        ).apply {
            setTypeface(typeface, Typeface.BOLD)
        }
        AgentRichBlockType.QUOTE -> quoteBlock(block)
        AgentRichBlockType.LIST -> listBlock(block)
        AgentRichBlockType.DIVIDER -> dividerBlock()
        AgentRichBlockType.CODE -> codeBlock(block)
        AgentRichBlockType.JSON -> codeBlock(block.copy(language = "json"))
        AgentRichBlockType.KEY_VALUE -> keyValueBlock(block)
        AgentRichBlockType.TABLE -> tableBlock(block)
        AgentRichBlockType.IMAGE -> imageBlock(block)
        AgentRichBlockType.GALLERY -> galleryBlock(block)
        AgentRichBlockType.VIDEO -> videoBlock(block)
        AgentRichBlockType.AUDIO -> audioBlock(block)
        AgentRichBlockType.FILE -> artifactBlock(block)
        AgentRichBlockType.LINK -> artifactBlock(block)
        AgentRichBlockType.CITATION -> artifactBlock(block)
        AgentRichBlockType.STATUS -> statusBlock(block)
        AgentRichBlockType.PROGRESS -> progressBlock(block)
        AgentRichBlockType.METRIC -> metricBlock(block)
        AgentRichBlockType.TOOL -> statusBlock(block)
        AgentRichBlockType.DIFF -> codeBlock(block.copy(language = block.language.ifBlank { "diff" }))
        AgentRichBlockType.CHART -> chartBlock(block)
        AgentRichBlockType.TIMELINE -> timelineBlock(block)
        AgentRichBlockType.NOTICE -> noticeBlock(block)
        AgentRichBlockType.HTML -> htmlAnimationBlock(block)
        AgentRichBlockType.WEBPAGE -> webPageBlock(block)
        AgentRichBlockType.ACTIONS -> actionBlock(block, approval = false)
        AgentRichBlockType.APPROVAL -> actionBlock(block, approval = true)
        AgentRichBlockType.FORM -> formBlock(block)
        AgentRichBlockType.UNKNOWN -> artifactBlock(block)
    }

    private fun blockSpacing(block: AgentRichBlock): Int = when (block.type) {
        AgentRichBlockType.HEADING -> 18
        AgentRichBlockType.DIVIDER -> 14
        AgentRichBlockType.TEXT, AgentRichBlockType.LIST, AgentRichBlockType.QUOTE -> 8
        else -> 12
    }

    private fun quoteBlock(block: AgentRichBlock): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(View(activity).apply { setBackgroundColor(Color.parseColor("#8EA0B1")) },
            LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(selectableText(block.text, 15f).apply {
            setTextColor(Color.parseColor("#53606E"))
            setPadding(dp(12), dp(4), 0, dp(4))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun listBlock(block: AgentRichBlock): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        block.rows.take(MAX_VISIBLE_LIST_ITEMS).forEachIndexed { index, row ->
            val marker = row.firstOrNull().orEmpty()
            val label = when (marker) {
                "checked" -> "\u2713"
                "unchecked" -> "\u25CB"
                "bullet" -> "\u2022"
                else -> "$marker."
            }
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                addView(TextView(activity).apply {
                    text = label
                    textSize = 15f
                    gravity = Gravity.END
                    setTextColor(Color.parseColor(if (marker == "checked") "#0A9480" else "#53606E"))
                    setPadding(0, dp(1), dp(8), 0)
                }, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(selectableText(row.getOrNull(1).orEmpty(), 16f),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = dp(5)
            })
        }
    }

    private fun dividerBlock(): View = View(activity).apply {
        setBackgroundColor(Color.parseColor("#E4E9ED"))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)) }

    private fun keyValueBlock(block: AgentRichBlock): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground("#FFFFFF", 7f, "#E1E6EA")
        block.title.takeIf(String::isNotBlank)?.let { title ->
            addView(selectableText(title, 15f).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(10), dp(12), dp(7))
            })
        }
        block.rows.take(MAX_VISIBLE_TABLE_ROWS).forEachIndexed { index, row ->
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                if (index > 0 || block.title.isNotBlank()) {
                    background = topBorderBackground(if (index % 2 == 0) "#FFFFFF" else "#FAFBFC")
                }
                addView(selectableText(row.firstOrNull().orEmpty(), 13f).apply {
                    setTextColor(Color.parseColor("#66717D"))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f))
                addView(selectableText(row.getOrNull(1).orEmpty(), 14f).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f))
            })
        }
    }

    private fun codeBlock(block: AgentRichBlock): View {
        val source = block.text
        val lineCount = source.count { it == '\n' } + 1
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground("#F5F7F9", 7f, "#DDE3E8")
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(6), dp(5))
            addView(TextView(activity).apply {
                text = block.title.ifBlank {
                    block.language.uppercase(Locale.ROOT).ifBlank {
                        formatLabel(AgentRichFormatRegistry.describe(block))
                    }
                }
                textSize = 11f
                setTextColor(Color.parseColor("#66717D"))
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton(
                R.drawable.ic_rich_copy,
                activity.getString(R.string.rich_output_copy)
            ) { copyText(source) }, LinearLayout.LayoutParams(dp(36), dp(34)))
        }
        container.addView(header)
        val codeText = selectableText(source, 13.5f).apply {
            text = codeSpannable(source, block.language)
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#14202B"))
            setPadding(dp(12), dp(7), dp(12), dp(11))
            maxLines = if (lineCount > MAX_COLLAPSED_CODE_LINES) MAX_COLLAPSED_CODE_LINES else Int.MAX_VALUE
        }
        container.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(codeText, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        if (lineCount > MAX_COLLAPSED_CODE_LINES) {
            container.addView(TextView(activity).apply {
                text = activity.getString(R.string.rich_output_show_more)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#087F69"))
                setPadding(dp(12), dp(8), dp(12), dp(9))
                background = topBorderBackground("#F5F7F9")
                setOnClickListener {
                    val expand = codeText.maxLines != Int.MAX_VALUE
                    codeText.maxLines = if (expand) Int.MAX_VALUE else MAX_COLLAPSED_CODE_LINES
                    text = activity.getString(if (expand) R.string.rich_output_show_less else R.string.rich_output_show_more)
                }
            })
        }
        return container
    }

    private fun tableBlock(block: AgentRichBlock): View {
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        block.title.takeIf(String::isNotBlank)?.let { title ->
            container.addView(selectableText(title, 16f).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(7))
            })
        }
        val table = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground("#FFFFFF", 6f, "#DDE3E8")
        }
        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(table)
        }
        container.addView(scroll)
        var expanded = false
        fun renderRows() {
            table.removeAllViews()
            val columnCount = maxOf(1, block.columns.size, block.rows.maxOfOrNull { it.size } ?: 0)
            if (block.columns.isNotEmpty()) table.addView(tableRow(block.columns, true, columnCount = columnCount))
            val rows = if (expanded) block.rows else block.rows.take(MAX_VISIBLE_TABLE_ROWS)
            rows.forEachIndexed { index, row -> table.addView(tableRow(row, false, index, columnCount)) }
        }
        renderRows()
        if (block.rows.size > MAX_VISIBLE_TABLE_ROWS) {
            container.addView(TextView(activity).apply {
                text = activity.getString(R.string.rich_output_more_rows, block.rows.size - MAX_VISIBLE_TABLE_ROWS)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#087F69"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    expanded = !expanded
                    renderRows()
                    text = if (expanded) {
                        activity.getString(R.string.rich_output_show_less)
                    } else {
                        activity.getString(R.string.rich_output_more_rows, block.rows.size - MAX_VISIBLE_TABLE_ROWS)
                    }
                }
            })
        }
        return container
    }

    private fun tableRow(
        values: List<String>,
        header: Boolean,
        rowIndex: Int = 0,
        columnCount: Int = values.size.coerceAtLeast(1)
    ): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(
                Color.parseColor(if (header) "#EDF2F6" else if (rowIndex % 2 == 0) "#FFFFFF" else "#F8FAFB")
            )
            values.forEach { value ->
                addView(selectableText(value, if (header) 13f else 14f).apply {
                    gravity = Gravity.CENTER_VERTICAL or if (!header && isNumeric(value)) Gravity.END else Gravity.START
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    if (header) setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(tableColumnWidth(columnCount), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

    private fun imageBlock(block: AgentRichBlock): View {
        if (block.dataB64.isBlank() && !isPreviewableUri(block.uri)) return artifactBlock(block)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        block.title.takeIf(String::isNotBlank)?.let { addView(selectableText(it, 15f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(7))
        }) }
        val image = ImageView(activity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = block.title.ifBlank { block.text }
            background = roundedBackground("#F6F8FA", 7f, "#E1E6EA")
            setPadding(dp(1), dp(1), dp(1), dp(1))
            minimumHeight = dp(120)
            maxHeight = dp(420)
            setOnClickListener { showImageFullscreen(block) }
        }
        val loading = ProgressBar(activity).apply {
            isIndeterminate = true
            contentDescription = activity.getString(R.string.rich_output_loading)
        }
        addView(FrameLayout(activity).apply {
            addView(image, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(loading, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        loadImage(block, image) { success ->
            loading.visibility = View.GONE
            if (!success) {
                image.setImageResource(android.R.drawable.ic_menu_report_image)
                image.contentDescription = activity.getString(R.string.rich_output_load_failed)
            }
        }
            if (block.text.isNotBlank()) addView(selectableText(block.text, 13f).apply {
                setTextColor(Color.parseColor("#66717D"))
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun galleryBlock(block: AgentRichBlock): View {
        val items = buildList {
            if (block.uri.isNotBlank()) add(listOf(block.uri, block.title, block.mimeType))
            addAll(block.rows.filter { it.firstOrNull().orEmpty().isNotBlank() })
        }.take(MAX_GALLERY_ITEMS)
        if (items.size <= 1) {
            val item = items.firstOrNull()
            return imageBlock(block.copy(
                uri = item?.firstOrNull().orEmpty().ifBlank { block.uri },
                title = item?.getOrNull(1).orEmpty().ifBlank { block.title }
            ))
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (block.title.isNotBlank()) addView(selectableText(block.title, 16f).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(7))
            })
            addView(HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    items.forEachIndexed { index, row ->
                        val itemBlock = AgentRichBlock(
                            id = "${block.id}-$index",
                            type = AgentRichBlockType.IMAGE,
                            uri = row.firstOrNull().orEmpty(),
                            title = row.getOrNull(1).orEmpty(),
                            mimeType = row.getOrNull(2).orEmpty()
                        )
                        addView(ImageView(activity).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            contentDescription = itemBlock.title.ifBlank {
                                "${activity.getString(R.string.rich_output_type_image)} ${index + 1}"
                            }
                            background = roundedBackground("#F6F8FA", 7f, "#E1E6EA")
                            setOnClickListener { showImageFullscreen(itemBlock) }
                            loadImage(itemBlock.uri, this)
                        }, LinearLayout.LayoutParams(dp(168), dp(168)).apply {
                            if (index > 0) marginStart = dp(8)
                        })
                    }
                })
            })
        }
    }

    private fun chartBlock(block: AgentRichBlock): View {
        val hasNumbers = block.rows.any { row -> row.drop(1).any { it.replace(",", "").toFloatOrNull() != null } }
        if (!hasNumbers) return tableBlock(block.copy(type = AgentRichBlockType.TABLE))
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (block.title.isNotBlank()) addView(selectableText(block.title, 16f).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(7))
            })
            addView(AgentRichChartView(activity, block.columns, block.rows),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))
        }
    }

    private fun videoBlock(block: AgentRichBlock): View {
        if (!isPreviewableUri(block.uri)) return artifactBlock(block)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            block.title.takeIf(String::isNotBlank)?.let { addView(selectableText(it, 15f).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(7))
            }) }
            val video = VideoView(activity).apply {
                val controls = MediaController(activity)
                controls.setAnchorView(this)
                setMediaController(controls)
                setVideoURI(Uri.parse(block.uri))
                contentDescription = block.title.ifBlank { activity.getString(R.string.rich_output_type_video) }
                setOnPreparedListener { player ->
                    player.isLooping = false
                    setOnClickListener {
                        AgentRichPlaybackCoordinator.activate(this)
                        if (isPlaying) pause() else start()
                    }
                }
                setOnCompletionListener { AgentRichPlaybackCoordinator.detach(this) }
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = Unit
                    override fun onViewDetachedFromWindow(view: View) {
                        AgentRichPlaybackCoordinator.detach(view as VideoView)
                    }
                })
            }
            addView(video, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        }
    }

    private fun audioBlock(block: AgentRichBlock): View {
        if (!isPreviewableUri(block.uri)) return artifactBlock(block)
        val handler = Handler(Looper.getMainLooper())
        var player: MediaPlayer? = null
        var prepared = false
        var pendingPlay = false
        val playButton = iconButton(R.drawable.ic_rich_play, activity.getString(R.string.rich_output_play)) {}
        val elapsed = TextView(activity).apply {
            text = "0:00"
            textSize = 11f
            setTextColor(Color.parseColor("#66717D"))
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val seek = SeekBar(activity).apply {
            max = 1000
            progress = 0
            setPadding(0, 0, 0, 0)
        }
        val updater = object : Runnable {
            override fun run() {
                val active = player
                if (prepared && active != null) {
                    val duration = active.duration.coerceAtLeast(1)
                    seek.progress = (active.currentPosition * 1000L / duration).toInt()
                    elapsed.text = "${formatDuration(active.currentPosition)} / ${formatDuration(duration)}"
                    if (active.isPlaying) handler.postDelayed(this, 250)
                }
            }
        }
        fun updateButton(isPlaying: Boolean) {
            playButton.setImageResource(if (isPlaying) R.drawable.ic_rich_pause else R.drawable.ic_rich_play)
            playButton.contentDescription = activity.getString(
                if (isPlaying) R.string.rich_output_pause else R.string.rich_output_play
            )
        }
        fun toggle() {
            val active = player
            if (!prepared || active == null) {
                pendingPlay = true
                return
            }
            if (active.isPlaying) {
                active.pause()
                updateButton(false)
            } else {
                AgentRichPlaybackCoordinator.activate(active)
                active.start()
                updateButton(true)
                handler.post(updater)
            }
        }
        playButton.setOnClickListener { toggle() }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = roundedBackground("#F6F8FA", 7f, "#DDE3E8")
            addView(playButton, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = displayFileName(block)
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#14202B"))
                    maxLines = 1
                })
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(seek, LinearLayout.LayoutParams(0, dp(28), 1f))
                    addView(elapsed, LinearLayout.LayoutParams(dp(82), dp(28)))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            })
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val active = player
                if (fromUser && prepared && active != null) {
                    active.seekTo((active.duration * progress / 1000f).toInt())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        player = MediaPlayer().apply {
            setOnPreparedListener {
                prepared = true
                elapsed.text = "0:00 / ${formatDuration(duration)}"
                if (pendingPlay) {
                    pendingPlay = false
                    toggle()
                }
            }
            setOnCompletionListener {
                seek.progress = 0
                updateButton(false)
                AgentRichPlaybackCoordinator.detach(this)
            }
            setOnErrorListener { _, _, _ ->
                prepared = false
                updateButton(false)
                Toast.makeText(activity, R.string.rich_output_load_failed, Toast.LENGTH_SHORT).show()
                true
            }
            runCatching {
                setDataSource(activity, Uri.parse(block.uri))
                prepareAsync()
            }.onFailure {
                prepared = false
                updateButton(false)
            }
        }
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                handler.removeCallbacks(updater)
                player?.let(AgentRichPlaybackCoordinator::detach)
                player?.release()
                player = null
            }
        })
        return container
    }

    private fun htmlAnimationBlock(block: AgentRichBlock): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        block.title.takeIf(String::isNotBlank)?.let { addView(selectableText(it, 15f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(7))
        }) }
        val webView = WebView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            configureEmbeddedWebContent()
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                @Deprecated("Deprecated in Android")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.let(AgentRichPlaybackCoordinator::sync)
                }
            }
            contentDescription = block.title.ifBlank {
                block.fallbackText.ifBlank { activity.getString(R.string.rich_output_type_interactive) }
            }
            loadDataWithBaseURL(null, isolatedHtmlDocument(block.text), "text/html", "utf-8", null)
        }
        coordinatePlayback(webView)
        addView(SignalASIPinchZoomViewport(activity).apply {
            attach(webView)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)))
        if (block.fallbackText.isNotBlank()) addView(selectableText(block.fallbackText, 12f).apply {
            setTextColor(Color.parseColor("#66717D"))
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun isolatedHtmlDocument(fragment: String): String = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=5,user-scalable=yes">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; media-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'none'; frame-src 'none'; font-src 'none'; form-action 'none'; base-uri 'none'">
        <style>html,body{margin:0;padding:0;width:100%;min-height:100%;overflow:auto;background:transparent;color:#14202b;font-family:system-ui,sans-serif;touch-action:pan-x pan-y pinch-zoom}*{box-sizing:border-box;max-width:100%}</style>
        </head><body>${fragment.take(32_000)}</body></html>
    """.trimIndent()

    private fun webPageBlock(block: AgentRichBlock): View {
        val uri = Uri.parse(block.uri)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return artifactBlock(block)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground("#FFFFFF", 7f, "#DDE3E8")
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(5), dp(5), dp(4))
                addView(TextView(activity).apply {
                    text = block.title.ifBlank { uri.host.orEmpty() }
                    textSize = 14f
                    setTextColor(Color.parseColor("#14202B"))
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(iconButton(
                    R.drawable.ic_rich_open,
                    activity.getString(R.string.rich_output_open)
                ) { openUri(block.uri, "text/html") }, LinearLayout.LayoutParams(dp(40), dp(40)))
            })
            val loading = TextView(activity).apply {
                text = activity.getString(R.string.rich_output_loading)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#66717D"))
                setBackgroundColor(Color.WHITE)
            }
            val webView = WebView(activity).apply {
                setBackgroundColor(Color.WHITE)
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                isNestedScrollingEnabled = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                settings.javaScriptEnabled = true
                configureEmbeddedWebContent()
                settings.domStorageEnabled = false
                settings.databaseEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = false
                settings.loadsImagesAutomatically = true
                settings.mediaPlaybackRequiresUserGesture = true
                settings.setGeolocationEnabled(false)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                }
                CookieManager.getInstance().setAcceptCookie(false)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    settings.safeBrowsingEnabled = true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                        request?.url?.scheme != "https"

                    @Deprecated("Deprecated in Android")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                        Uri.parse(url.orEmpty()).scheme != "https"

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                        if (request?.url?.scheme == "https") super.shouldInterceptRequest(view, request)
                        else WebResourceResponse("text/plain", "utf-8", null)

                    override fun onPageFinished(view: WebView?, url: String?) {
                        loading.visibility = View.GONE
                        view?.let(AgentRichPlaybackCoordinator::sync)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            loading.text = activity.getString(R.string.rich_output_load_failed)
                            loading.visibility = View.VISIBLE
                        }
                    }
                }
                contentDescription = block.title.ifBlank { activity.getString(R.string.rich_output_type_web) }
                loadUrl(block.uri)
            }
            coordinatePlayback(webView)
            addView(FrameLayout(activity).apply {
                addView(SignalASIPinchZoomViewport(activity).apply {
                    attach(webView)
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(loading, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)))
            addView(selectableText(block.uri, 11f).apply {
                setTextColor(Color.parseColor("#66717D"))
                maxLines = 1
                setPadding(dp(12), dp(7), dp(12), dp(9))
            })
        }
    }

    private fun coordinatePlayback(webView: WebView) {
        webView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    AgentRichPlaybackCoordinator.activate(view as WebView)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        webView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                AgentRichPlaybackCoordinator.detach(view as WebView)
            }
        })
        webView.post { AgentRichPlaybackCoordinator.activate(webView) }
    }

    private fun WebView.configureEmbeddedWebContent() {
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        isLongClickable = false
        setOnLongClickListener { true }
    }

    private fun artifactBlock(block: AgentRichBlock): View {
        val descriptor = AgentRichFormatRegistry.describe(block)
        val canOpen = isOpenableUri(block.uri)
        val detailParts = buildList {
            add(formatLabel(descriptor))
            block.metadata["size"]?.takeIf(String::isNotBlank)?.let(::add)
            block.mimeType.takeIf(String::isNotBlank)?.let(::add)
            if (descriptor.family == AgentRichFormatFamily.UNKNOWN) {
                add(activity.getString(R.string.rich_output_unknown_file))
            }
        }.distinct()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(10), dp(7), dp(10))
            background = roundedBackground("#F7F9FA", 7f, "#DDE3E8")
            addView(TextView(activity).apply {
                val badge = formatBadge(descriptor)
                text = badge
                textSize = if (badge.length > 3) 9f else 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                background = roundedBackground(formatColor(descriptor.family), 6f, formatColor(descriptor.family))
                contentDescription = formatLabel(descriptor)
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = displayFileName(block)
                    textSize = 14f
                    setTextColor(Color.parseColor("#14202B"))
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                })
                val detail = detailParts.joinToString(" \u00B7 ").ifBlank {
                    block.text.ifBlank { block.fallbackText }
                }
                if (detail.isNotBlank()) addView(TextView(activity).apply {
                    text = detail
                    textSize = 11f
                    setTextColor(Color.parseColor("#66717D"))
                    maxLines = 2
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            })
            if (canOpen) addView(iconButton(
                R.drawable.ic_rich_open,
                activity.getString(R.string.rich_output_open)
            ) { openUri(block.uri, block.mimeType) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            isClickable = canOpen
            isFocusable = canOpen
            if (canOpen) setOnClickListener { openUri(block.uri, block.mimeType) }
        }
    }

    private fun timelineBlock(block: AgentRichBlock): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        if (block.title.isNotBlank()) addView(selectableText(block.title, 16f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(7))
        })
        val rows = block.rows.take(MAX_TIMELINE_ITEMS)
        rows.forEachIndexed { index, row ->
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                addView(TextView(activity).apply {
                    text = if (index == rows.lastIndex) "\u25CF" else "\u25CF\n\u2502"
                    textSize = 12f
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextColor(Color.parseColor("#0A9480"))
                    setLineSpacing(0f, 1.45f)
                }, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    val primary = row.getOrNull(1).orEmpty().ifBlank { row.firstOrNull().orEmpty() }
                    val secondary = row.getOrNull(2).orEmpty()
                    addView(selectableText(primary, 14f).apply { setTypeface(typeface, Typeface.BOLD) })
                    if (secondary.isNotBlank()) addView(selectableText(secondary, 12f).apply {
                        setTextColor(Color.parseColor("#66717D"))
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.firstOrNull()?.takeIf { row.size > 1 && it.isNotBlank() }?.let { time ->
                    addView(TextView(activity).apply {
                        text = time
                        textSize = 11f
                        setTextColor(Color.parseColor("#8A949E"))
                    })
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = dp(6)
            })
        }
    }

    private fun noticeBlock(block: AgentRichBlock): View {
        val style = block.metadata["style"].orEmpty().lowercase(Locale.ROOT)
        val palette = when (style) {
            "success" -> Triple("#EAF8F4", "#0A9480", "#087F69")
            "warning" -> Triple("#FFF7E6", "#E1A12B", "#7A5200")
            "error" -> Triple("#FFF0F1", "#D24D57", "#9F2330")
            else -> Triple("#EEF5FF", "#5A8FE6", "#315F9B")
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBackground(palette.first, 7f, palette.second)
            addView(View(activity).apply { setBackgroundColor(Color.parseColor(palette.second)) },
                LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(9), dp(11), dp(9))
                if (block.title.isNotBlank()) addView(selectableText(block.title, 14f).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor(palette.third))
                })
                if (block.text.isNotBlank()) addView(selectableText(block.text, 13f).apply {
                    setTextColor(Color.parseColor(palette.third))
                    if (block.title.isNotBlank()) setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun statusBlock(block: AgentRichBlock): View = TextView(activity).apply {
        text = listOf(block.title, block.text).filter(String::isNotBlank).joinToString(" · ")
        textSize = 13f
        setTextColor(Color.parseColor("#087F69"))
        setPadding(dp(11), dp(8), dp(11), dp(8))
        background = roundedBackground("#EAF8F4", 6f, "#BFE7DB")
        onTextViewReady(this)
    }

    private fun progressBlock(block: AgentRichBlock): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val label = listOf(block.title, block.text).filter(String::isNotBlank).joinToString(" · ")
        if (label.isNotBlank()) addView(selectableText(label, 13f))
        addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = block.maximum.coerceAtLeast(1)
            progress = block.value.coerceIn(0, max)
            isIndeterminate = block.value < 0
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply {
            if (label.isNotBlank()) topMargin = dp(7)
        })
    }

    private fun metricBlock(block: AgentRichBlock): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(10), dp(13), dp(10))
        background = roundedBackground("#F6F8FA", 7f, "#DDE3E8")
        addView(selectableText(block.text.ifBlank { block.value.toString() }, 22f).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        if (block.title.isNotBlank()) addView(selectableText(block.title, 12f).apply {
            setTextColor(Color.parseColor("#66717D"))
        })
    }

    private fun actionBlock(block: AgentRichBlock, approval: Boolean): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(11), dp(12), dp(11))
        background = roundedBackground(if (approval) "#FFFFFF" else "#F6F8FA", 6f, "#DDE3E8")
        if (approval) {
            addView(approvalHeader(block))
        } else {
            val heading = block.title.ifBlank { activity.getString(R.string.rich_output_actions) }
            addView(selectableText(heading, 15f).apply { setTypeface(typeface, Typeface.BOLD) })
            if (block.text.isNotBlank()) addView(selectableText(block.text, 14f).apply {
                setPadding(0, dp(5), 0, dp(5))
            })
        }
        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        block.actions.forEachIndexed { index, action ->
            actionRow.addView(Button(activity).apply {
                text = action.label
                textSize = 14f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(6), 0, dp(6), 0)
                val confirm = action.verb == "approve_task"
                setTextColor(Color.parseColor(if (confirm) "#087F69" else "#33404D"))
                background = roundedBackground(
                    if (confirm) "#EFFAF8" else "#F4F6F8",
                    6f,
                    if (confirm) "#0A9480" else "#D9E0E7"
                )
                setOnClickListener { onAction(action) }
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
    }

    private fun approvalHeader(block: AgentRichBlock): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(activity).apply {
            setImageResource(android.R.drawable.ic_lock_idle_alarm)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A2733"))
            contentDescription = block.title
            setPadding(dp(2), dp(2), dp(8), dp(2))
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(selectableText(block.title, 14f).apply {
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
            })
            if (block.text.isNotBlank()) addView(selectableText(block.text, 11f).apply {
                setTextColor(Color.parseColor("#66717D"))
                maxLines = 2
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (block.fallbackText.isNotBlank()) addView(TextView(activity).apply {
            text = block.fallbackText
            textSize = 10f
            setTextColor(Color.parseColor("#66717D"))
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = roundedBackground("#F4F6F8", 5f, "#D9E0E7")
        })
    }

    private fun formBlock(block: AgentRichBlock): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = roundedBackground("#F6F8FA", 7f, "#DDE3E8")
        addView(selectableText(block.title.ifBlank { activity.getString(R.string.rich_output_input_required) }, 15f).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        if (block.text.isNotBlank()) addView(selectableText(block.text, 13f).apply {
            setPadding(0, dp(4), 0, dp(5))
        })
        val values = linkedMapOf<String, () -> String>()
        block.fields.forEach { field ->
            val input = when (field.inputType) {
                "boolean", "checkbox" -> CheckBox(activity).apply {
                    text = field.label
                    textSize = 15f
                    isChecked = field.value.equals("true", true) || field.value == "1"
                    values[field.id] = { isChecked.toString() }
                }
                "select", "choice", "enum" -> Spinner(activity).apply {
                    val choices = field.options.ifEmpty { listOf(field.value).filter(String::isNotBlank) }
                    adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, choices)
                    val selected = choices.indexOf(field.value)
                    if (selected >= 0) setSelection(selected)
                    background = roundedBackground("#FFFFFF", 6f, "#C9D4DD")
                    contentDescription = field.label
                    values[field.id] = { selectedItem?.toString().orEmpty() }
                }
                else -> EditText(activity).apply {
                    hint = field.label + if (field.required) " *" else ""
                    setText(field.value)
                    textSize = 15f
                    setSingleLine(field.inputType !in setOf("multiline", "textarea"))
                    inputType = when (field.inputType) {
                        "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        "number", "integer", "decimal" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                        "phone" -> InputType.TYPE_CLASS_PHONE
                        "multiline", "textarea" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    }
                    setPadding(dp(11), dp(9), dp(11), dp(9))
                    background = roundedBackground("#FFFFFF", 6f, "#C9D4DD")
                    values[field.id] = { text?.toString().orEmpty() }
                }
            }
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(7)
            })
        }
        addView(Button(activity).apply {
            text = block.actions.firstOrNull()?.label ?: activity.getString(R.string.rich_output_submit)
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBackground("#087F69", 6f, "#087F69")
            setOnClickListener {
                val submitted = values.mapValues { it.value.invoke() }
                val missing = block.fields.any { it.required && submitted[it.id].isNullOrBlank() }
                if (missing) Toast.makeText(activity, R.string.rich_output_complete_required, Toast.LENGTH_SHORT).show()
                else onFormSubmit(block, submitted)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(9) })
    }

    private fun selectableText(value: String, sizeSp: Float): TextView = TextView(activity).apply {
        text = inlineMarkdown(value)
        textSize = sizeSp
        setTextColor(Color.parseColor("#14202B"))
        setLinkTextColor(Color.parseColor("#087F69"))
        setLineSpacing(dp(4).toFloat(), 1f)
        setTextIsSelectable(true)
        movementMethod = LinkMovementMethod.getInstance()
        highlightColor = Color.TRANSPARENT
        onTextViewReady(this)
    }

    private fun inlineMarkdown(value: String): CharSequence {
        val output = SpannableStringBuilder()
        AgentInlineMarkdown.parse(value).forEach { segment ->
            val start = output.length
            output.append(segment.text)
            val end = output.length
            when (segment.style) {
                AgentInlineStyle.BOLD -> output.setSpan(
                    StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                AgentInlineStyle.ITALIC -> output.setSpan(
                    StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                AgentInlineStyle.STRIKE -> output.setSpan(
                    StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                AgentInlineStyle.CODE -> {
                    output.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    output.setSpan(
                        BackgroundColorSpan(Color.parseColor("#F0F3F6")),
                        start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                AgentInlineStyle.LINK -> {
                    output.setSpan(URLSpan(segment.url), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    output.setSpan(
                        ForegroundColorSpan(Color.parseColor("#087F69")),
                        start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                AgentInlineStyle.NORMAL -> Unit
            }
        }
        return output
    }

    private fun codeSpannable(value: String, language: String): CharSequence {
        val output = SpannableStringBuilder(value)
        if (language.equals("diff", ignoreCase = true) || language.equals("patch", ignoreCase = true)) {
            var offset = 0
            value.lineSequence().forEach { line ->
                val color = when {
                    line.startsWith("+") && !line.startsWith("+++") -> "#087F69"
                    line.startsWith("-") && !line.startsWith("---") -> "#B83246"
                    line.startsWith("@@") -> "#315F9B"
                    else -> null
                }
                color?.let {
                    output.setSpan(
                        ForegroundColorSpan(Color.parseColor(it)),
                        offset,
                        (offset + line.length).coerceAtMost(output.length),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                offset += line.length + 1
            }
        } else if (language.equals("json", ignoreCase = true)) {
            Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)").findAll(value).forEach { match ->
                output.setSpan(
                    ForegroundColorSpan(Color.parseColor("#087F69")),
                    match.range.first,
                    match.range.last + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            Regex("(?<![A-Za-z])(?:true|false|null|-?\\d+(?:\\.\\d+)?)(?![A-Za-z])").findAll(value).forEach { match ->
                output.setSpan(
                    ForegroundColorSpan(Color.parseColor("#7B4AB5")),
                    match.range.first,
                    match.range.last + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return output
    }

    private fun iconButton(drawableRes: Int, description: String, onClick: () -> Unit): ImageButton =
        ImageButton(activity).apply {
            setImageResource(drawableRes)
            contentDescription = description
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun copyText(value: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SignalASI", value))
        Toast.makeText(activity, R.string.rich_output_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showImageFullscreen(block: AgentRichBlock) {
        if (block.dataB64.isBlank() && !isPreviewableUri(block.uri)) return
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = block.title.ifBlank { activity.getString(R.string.rich_output_type_image) }
            setBackgroundColor(Color.BLACK)
        }
        val viewport = SignalASIPinchZoomViewport(activity).apply {
            setBackgroundColor(Color.BLACK)
            attach(image)
        }
        dialog.setContentView(viewport)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        dialog.setOnShowListener { loadImage(block, image) }
        dialog.show()
    }

    private fun formatDuration(milliseconds: Int): String {
        val seconds = (milliseconds / 1000).coerceAtLeast(0)
        return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }

    private fun formatBadge(descriptor: AgentRichFormatDescriptor): String = when (descriptor.family) {
        AgentRichFormatFamily.IMAGE -> "IMG"
        AgentRichFormatFamily.VIDEO -> "VID"
        AgentRichFormatFamily.AUDIO -> "AUD"
        AgentRichFormatFamily.WEB -> "WEB"
        AgentRichFormatFamily.ARCHIVE -> "ZIP"
        AgentRichFormatFamily.CODE -> "</>"
        AgentRichFormatFamily.STRUCTURED_DATA -> "{}"
        else -> descriptor.extension.removePrefix(".").uppercase(Locale.ROOT).take(4).ifBlank { "FILE" }
    }

    private fun formatColor(family: AgentRichFormatFamily): String = when (family) {
        AgentRichFormatFamily.IMAGE -> "#2C8B69"
        AgentRichFormatFamily.VIDEO -> "#6851B5"
        AgentRichFormatFamily.AUDIO -> "#3578E5"
        AgentRichFormatFamily.DOCUMENT -> "#C04F52"
        AgentRichFormatFamily.ARCHIVE -> "#B87916"
        AgentRichFormatFamily.CODE, AgentRichFormatFamily.STRUCTURED_DATA -> "#33404D"
        AgentRichFormatFamily.WEB -> "#087F69"
        else -> "#66717D"
    }

    private fun formatLabel(descriptor: AgentRichFormatDescriptor): String = activity.getString(when (descriptor.family) {
        AgentRichFormatFamily.TEXT -> R.string.rich_output_type_text
        AgentRichFormatFamily.CODE -> R.string.rich_output_type_code
        AgentRichFormatFamily.STRUCTURED_DATA -> R.string.rich_output_type_data
        AgentRichFormatFamily.TABLE -> R.string.rich_output_type_table
        AgentRichFormatFamily.IMAGE -> R.string.rich_output_type_image
        AgentRichFormatFamily.VIDEO -> R.string.rich_output_type_video
        AgentRichFormatFamily.AUDIO -> R.string.rich_output_type_audio
        AgentRichFormatFamily.DOCUMENT -> R.string.rich_output_type_document
        AgentRichFormatFamily.ARCHIVE -> R.string.rich_output_type_archive
        AgentRichFormatFamily.WEB -> R.string.rich_output_type_web
        AgentRichFormatFamily.INTERACTIVE -> R.string.rich_output_type_interactive
        AgentRichFormatFamily.UNKNOWN -> R.string.rich_output_type_file
    })

    private fun displayFileName(block: AgentRichBlock): String =
        if (block.title.isBlank() && block.uri.isBlank()) {
            formatLabel(AgentRichFormatRegistry.describe(block))
        } else {
            AgentRichFormatRegistry.fileName(block)
        }

    private fun loadImage(block: AgentRichBlock, image: ImageView, onResult: (Boolean) -> Unit = {}) {
        if (block.dataB64.isBlank()) {
            loadImage(block.uri, image, onResult)
            return
        }
        IMAGE_EXECUTOR.execute {
            val bytes = runCatching { Base64.decode(block.dataB64, Base64.DEFAULT) }
                .getOrNull()
                ?.takeIf { it.size <= MAX_IMAGE_BYTES }
            val decoded = bytes?.let { runCatching { decodeImageBytes(it) }.getOrNull() }
            Handler(Looper.getMainLooper()).post {
                if (!activity.isDestroyed) {
                    when (decoded) {
                        is AnimatedImageDrawable -> {
                            decoded.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            image.setImageDrawable(decoded)
                            coordinateAnimatedImage(image, decoded)
                        }
                        is android.graphics.drawable.Drawable -> image.setImageDrawable(decoded)
                        is Bitmap -> image.setImageBitmap(decoded)
                    }
                    onResult(decoded != null)
                }
            }
        }
    }

    private fun loadImage(uri: String, image: ImageView, onResult: (Boolean) -> Unit = {}) {
        if (!isPreviewableUri(uri)) {
            onResult(false)
            return
        }
        when (Uri.parse(uri).scheme?.lowercase()) {
            "android.resource" -> {
                val success = runCatching { image.setImageURI(Uri.parse(uri)) }.isSuccess && image.drawable != null
                onResult(success)
            }
            "content", "file", "https" -> IMAGE_EXECUTOR.execute {
                val bytes = runCatching {
                    when (Uri.parse(uri).scheme?.lowercase()) {
                        "https" -> {
                            val request = Request.Builder().url(uri).get().build()
                            HTTP.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) return@use null
                                val body = response.body ?: return@use null
                                if (body.contentLength() > MAX_IMAGE_BYTES) return@use null
                                body.byteStream().use { readBounded(it, MAX_IMAGE_BYTES) }
                            }
                        }
                        else -> activity.contentResolver.openInputStream(Uri.parse(uri))?.use {
                            readBounded(it, MAX_IMAGE_BYTES)
                        }
                    }
                }.getOrNull()
                val decoded = bytes?.let { runCatching { decodeImageBytes(it) }.getOrNull() }
                Handler(Looper.getMainLooper()).post {
                    if (!activity.isDestroyed) {
                        if (decoded != null) {
                            when (decoded) {
                                is AnimatedImageDrawable -> {
                                    decoded.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                    image.setImageDrawable(decoded)
                                    coordinateAnimatedImage(image, decoded)
                                }
                                is android.graphics.drawable.Drawable -> image.setImageDrawable(decoded)
                                is Bitmap -> image.setImageBitmap(decoded)
                            }
                        }
                        onResult(decoded != null)
                    }
                }
            }
            else -> onResult(false)
        }
    }

    private fun decodeImageBytes(bytes: ByteArray): Any? {
        val timedBytes = AgentAnimatedImageTiming.normalizeZeroFrameDelays(bytes)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(timedBytes))) { decoder, info, _ ->
                val largest = maxOf(info.size.width, info.size.height)
                if (largest > MAX_IMAGE_DIMENSION) {
                    decoder.setTargetSampleSize(ceil(largest.toDouble() / MAX_IMAGE_DIMENSION).toInt())
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(timedBytes, 0, timedBytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_DIMENSION) sample *= 2
            BitmapFactory.decodeByteArray(
                timedBytes,
                0,
                timedBytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }
    }

    private fun readBounded(input: InputStream, maximum: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maximum, 256 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maximum) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun coordinateAnimatedImage(image: ImageView, drawable: AnimatedImageDrawable) {
        image.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                AgentRichPlaybackCoordinator.activate(view as ImageView, drawable)
            }
            false
        }
        image.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                AgentRichPlaybackCoordinator.detach(view as ImageView)
            }
        })
        image.post { AgentRichPlaybackCoordinator.activate(image, drawable) }
    }

    private fun openUri(value: String, mimeType: String) {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { null })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivity(intent) }.onFailure {
            Toast.makeText(activity, value, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isPreviewableUri(value: String): Boolean =
        Uri.parse(value).scheme?.lowercase() in setOf("https", "content", "file", "android.resource")

    private fun isOpenableUri(value: String): Boolean =
        Uri.parse(value).scheme?.lowercase() in setOf("https", "content", "file", "android.resource")

    private fun roundedBackground(fill: String, radiusDp: Float, stroke: String): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setColor(Color.parseColor(fill))
            setStroke(dp(1), Color.parseColor(stroke))
        }

    private fun topBorderBackground(fill: String): GradientDrawable =
        roundedBackground(fill, 0f, "#E1E6EA")

    private fun tableColumnWidth(columnCount: Int): Int {
        val available = activity.resources.displayMetrics.widthPixels - dp(48)
        return (available / columnCount.coerceAtLeast(1)).coerceIn(dp(120), dp(280))
    }

    private fun isNumeric(value: String): Boolean =
        value.trim().removeSuffix("%").replace(",", "").toDoubleOrNull() != null

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_ASSISTANT_WIDTH_RATIO = 0.78f
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        private const val MAX_IMAGE_DIMENSION = 2_048
        private const val MAX_COLLAPSED_CODE_LINES = 28
        private const val MAX_VISIBLE_TABLE_ROWS = 12
        private const val MAX_VISIBLE_LIST_ITEMS = 100
        private const val MAX_GALLERY_ITEMS = 10
        private const val MAX_TIMELINE_ITEMS = 50
        private val HTTP = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        private val IMAGE_EXECUTOR = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "signalasi-rich-image").apply { isDaemon = true }
        }
    }
}

internal class SignalASIPinchZoomViewport(context: Context) : FrameLayout(context) {
    private var zoomTarget: View? = null
    private var zoomScale = 1f
    internal val currentZoomScale: Float get() = zoomScale
    private var multiTouchActive = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var lastTapAt = 0L
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return zoomTarget != null
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = zoomTarget ?: return false
                val oldScale = zoomScale
                val nextScale = (oldScale * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                val focusDeltaX = detector.focusX - lastFocusX
                val focusDeltaY = detector.focusY - lastFocusY
                val centerX = width / 2f
                val centerY = height / 2f
                val scaleRatio = nextScale / oldScale

                target.translationX += focusDeltaX
                target.translationY += focusDeltaY
                target.translationX = detector.focusX - centerX -
                    scaleRatio * (detector.focusX - centerX - target.translationX)
                target.translationY = detector.focusY - centerY -
                    scaleRatio * (detector.focusY - centerY - target.translationY)
                zoomScale = nextScale
                target.scaleX = nextScale
                target.scaleY = nextScale
                clampTranslation(target)
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }
        }
    )

    init {
        clipChildren = true
        clipToPadding = true
    }

    internal fun attach(target: View) {
        removeAllViews()
        zoomTarget = target
        addView(target, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouchActive = true
            parent?.requestDisallowInterceptTouchEvent(true)
            val cancelEvent = MotionEvent.obtain(event)
            cancelEvent.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancelEvent)
            cancelEvent.recycle()
        }
        if (multiTouchActive || event.pointerCount > 1 || scaleDetector.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(true)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                multiTouchActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        val target = zoomTarget
        if (zoomScale > MIN_ZOOM && target != null && event.pointerCount == 1) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastTapAt < DOUBLE_TAP_TIMEOUT_MS) {
                        zoomScale = MIN_ZOOM
                        target.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(180).start()
                        lastTapAt = 0L
                    } else {
                        lastTapAt = now
                    }
                    lastPanX = event.x
                    lastPanY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    target.translationX += event.x - lastPanX
                    target.translationY += event.y - lastPanY
                    lastPanX = event.x
                    lastPanY = event.y
                    clampTranslation(target)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun clampTranslation(target: View) {
        val maxTranslationX = width * (zoomScale - 1f) / 2f
        val maxTranslationY = height * (zoomScale - 1f) / 2f
        target.translationX = target.translationX.coerceIn(-maxTranslationX, maxTranslationX)
        target.translationY = target.translationY.coerceIn(-maxTranslationY, maxTranslationY)
    }

    private companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
        const val DOUBLE_TAP_TIMEOUT_MS = 320L
    }
}

private object AgentRichPlaybackCoordinator {
    private var active = WeakReference<WebView>(null)
    private var activeImage = WeakReference<ImageView>(null)
    private var activeDrawable = WeakReference<AnimatedImageDrawable>(null)
    private var activeVideo = WeakReference<VideoView>(null)
    private var activeAudio = WeakReference<MediaPlayer>(null)

    fun activate(view: WebView) {
        activeDrawable.get()?.stop()
        activeImage.clear()
        activeDrawable.clear()
        activeVideo.get()?.pause()
        activeVideo.clear()
        activeAudio.get()?.let { runCatching { if (it.isPlaying) it.pause() } }
        activeAudio.clear()
        val previous = active.get()
        if (previous !== view) previous?.let(::pause)
        active = WeakReference(view)
        resume(view)
    }

    fun sync(view: WebView) {
        if (active.get() === view && activeDrawable.get() == null) resume(view) else pause(view)
    }

    fun activate(view: ImageView, drawable: AnimatedImageDrawable) {
        active.get()?.let(::pause)
        active.clear()
        if (activeImage.get() !== view) {
            activeDrawable.get()?.stop()
        }
        activeVideo.get()?.pause()
        activeVideo.clear()
        activeAudio.get()?.let { runCatching { if (it.isPlaying) it.pause() } }
        activeAudio.clear()
        activeImage = WeakReference(view)
        activeDrawable = WeakReference(drawable)
        drawable.start()
    }

    fun activate(view: VideoView) {
        active.get()?.let(::pause)
        active.clear()
        activeDrawable.get()?.stop()
        activeImage.clear()
        activeDrawable.clear()
        activeAudio.get()?.let { runCatching { if (it.isPlaying) it.pause() } }
        activeAudio.clear()
        val previous = activeVideo.get()
        if (previous !== view) previous?.pause()
        activeVideo = WeakReference(view)
    }

    fun activate(player: MediaPlayer) {
        active.get()?.let(::pause)
        active.clear()
        activeDrawable.get()?.stop()
        activeImage.clear()
        activeDrawable.clear()
        activeVideo.get()?.pause()
        activeVideo.clear()
        val previous = activeAudio.get()
        if (previous !== player) previous?.let { runCatching { if (it.isPlaying) it.pause() } }
        activeAudio = WeakReference(player)
    }

    fun detach(view: WebView) {
        pause(view)
        if (active.get() === view) active.clear()
    }

    fun detach(view: ImageView) {
        if (activeImage.get() !== view) return
        activeDrawable.get()?.stop()
        activeImage.clear()
        activeDrawable.clear()
    }

    fun detach(view: VideoView) {
        if (activeVideo.get() === view) activeVideo.clear()
        runCatching { view.pause() }
    }

    fun detach(player: MediaPlayer) {
        if (activeAudio.get() === player) activeAudio.clear()
        runCatching { if (player.isPlaying) player.pause() }
    }

    private fun pause(view: WebView) {
        view.onPause()
        view.evaluateJavascript(PAUSE_SCRIPT, null)
    }

    private fun resume(view: WebView) {
        view.onResume()
        view.evaluateJavascript(RESUME_SCRIPT, null)
    }

    private const val PAUSE_SCRIPT = """
        (() => {
          let style = document.getElementById('signalasi-playback-pause');
          if (!style) {
            style = document.createElement('style');
            style.id = 'signalasi-playback-pause';
            style.textContent = '*,*::before,*::after{animation-play-state:paused!important}';
            document.documentElement.appendChild(style);
          }
          document.querySelectorAll('video,audio').forEach(media => {
            if (!media.paused) media.dataset.signalasiResume = '1';
            media.pause();
          });
        })()
    """

    private const val RESUME_SCRIPT = """
        (() => {
          document.getElementById('signalasi-playback-pause')?.remove();
          document.querySelectorAll('video,audio[data-signalasi-resume="1"]').forEach(media => {
            delete media.dataset.signalasiResume;
            media.play().catch(() => {});
          });
        })()
    """
}
