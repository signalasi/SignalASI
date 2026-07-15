package com.signalasi.chat

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
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
import kotlin.concurrent.thread

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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
                marginEnd = dp(10)
            }
            blocks.forEachIndexed { index, block ->
                val width = if (block.type == AgentRichBlockType.APPROVAL) {
                    (activity.resources.displayMetrics.widthPixels * MAX_ASSISTANT_WIDTH_RATIO).toInt()
                } else {
                    ViewGroup.LayoutParams.MATCH_PARENT
                }
                addView(blockView(block), LinearLayout.LayoutParams(
                    width,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.START
                    if (index > 0) topMargin = dp(10)
                })
            }
        }
    }

    private fun blockView(block: AgentRichBlock): View = when (block.type) {
        AgentRichBlockType.TEXT -> selectableText(block.text, 16f)
        AgentRichBlockType.HEADING -> selectableText(block.text.ifBlank { block.title }, 19f).apply {
            setTypeface(typeface, Typeface.BOLD)
        }
        AgentRichBlockType.QUOTE -> selectableText(block.text, 15f).apply {
            setTextColor(Color.parseColor("#53606E"))
            setPadding(dp(14), dp(10), dp(12), dp(10))
            background = roundedBackground("#F3F6F9", 6f, "#D9E0E7")
        }
        AgentRichBlockType.CODE -> codeBlock(block)
        AgentRichBlockType.TABLE -> tableBlock(block)
        AgentRichBlockType.IMAGE -> imageBlock(block)
        AgentRichBlockType.VIDEO -> videoBlock(block)
        AgentRichBlockType.AUDIO -> artifactBlock(block, "Audio")
        AgentRichBlockType.FILE -> artifactBlock(block, "File")
        AgentRichBlockType.LINK -> artifactBlock(block, "Link")
        AgentRichBlockType.CITATION -> artifactBlock(block, "Source")
        AgentRichBlockType.STATUS -> statusBlock(block)
        AgentRichBlockType.PROGRESS -> progressBlock(block)
        AgentRichBlockType.METRIC -> metricBlock(block)
        AgentRichBlockType.TOOL -> statusBlock(block)
        AgentRichBlockType.DIFF -> codeBlock(block.copy(language = block.language.ifBlank { "diff" }))
        AgentRichBlockType.CHART -> tableBlock(block)
        AgentRichBlockType.HTML -> htmlAnimationBlock(block)
        AgentRichBlockType.WEBPAGE -> webPageBlock(block)
        AgentRichBlockType.ACTIONS -> actionBlock(block, approval = false)
        AgentRichBlockType.APPROVAL -> actionBlock(block, approval = true)
        AgentRichBlockType.FORM -> formBlock(block)
        AgentRichBlockType.UNKNOWN -> selectableText(
            block.fallbackText.ifBlank { block.text.ifBlank { block.title } }, 15f
        )
    }

    private fun codeBlock(block: AgentRichBlock): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(9), dp(12), dp(11))
        background = roundedBackground("#F5F7F9", 7f, "#DDE3E8")
        if (block.language.isNotBlank() || block.title.isNotBlank()) {
            addView(TextView(activity).apply {
                text = block.title.ifBlank { block.language.uppercase() }
                textSize = 11f
                setTextColor(Color.parseColor("#66717D"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(7))
            })
        }
        addView(selectableText(block.text, 14f).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#14202B"))
        })
    }

    private fun tableBlock(block: AgentRichBlock): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        block.title.takeIf(String::isNotBlank)?.let { title ->
            addView(selectableText(title, 16f).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(7))
            })
        }
        addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = true
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBackground("#FFFFFF", 6f, "#DDE3E8")
                if (block.columns.isNotEmpty()) addView(tableRow(block.columns, true))
                block.rows.forEachIndexed { index, row -> addView(tableRow(row, false, index)) }
            })
        })
    }

    private fun tableRow(values: List<String>, header: Boolean, rowIndex: Int = 0): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(
                Color.parseColor(if (header) "#EDF2F6" else if (rowIndex % 2 == 0) "#FFFFFF" else "#F8FAFB")
            )
            values.forEach { value ->
                addView(selectableText(value, if (header) 13f else 14f).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    if (header) setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

    private fun imageBlock(block: AgentRichBlock): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        block.title.takeIf(String::isNotBlank)?.let { addView(selectableText(it, 15f)) }
        val image = ImageView(activity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = block.title.ifBlank { block.text }
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)).apply {
                if (block.title.isNotBlank()) topMargin = dp(7)
            }
        }
        addView(image)
        loadImage(block.uri, image)
        if (block.text.isNotBlank()) addView(selectableText(block.text, 13f).apply {
            setTextColor(Color.parseColor("#66717D"))
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun videoBlock(block: AgentRichBlock): View {
        if (!isPreviewableUri(block.uri)) return artifactBlock(block, "Video")
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            block.title.takeIf(String::isNotBlank)?.let { addView(selectableText(it, 15f)) }
            addView(VideoView(activity).apply {
                val controls = MediaController(activity)
                controls.setAnchorView(this)
                setMediaController(controls)
                setVideoURI(Uri.parse(block.uri))
                contentDescription = block.title.ifBlank { "Video" }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply {
                if (block.title.isNotBlank()) topMargin = dp(7)
            })
        }
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
            contentDescription = block.title.ifBlank { block.fallbackText.ifBlank { "Animated content" } }
            loadDataWithBaseURL(null, isolatedHtmlDocument(block.text), "text/html", "utf-8", null)
        }
        coordinatePlayback(webView)
        addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)))
        if (block.fallbackText.isNotBlank()) addView(selectableText(block.fallbackText, 12f).apply {
            setTextColor(Color.parseColor("#66717D"))
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun isolatedHtmlDocument(fragment: String): String = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; media-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'none'; frame-src 'none'; font-src 'none'; form-action 'none'; base-uri 'none'">
        <style>html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:transparent;color:#14202b;font-family:system-ui,sans-serif}*{box-sizing:border-box}</style>
        </head><body>${fragment.take(32_000)}</body></html>
    """.trimIndent()

    private fun webPageBlock(block: AgentRichBlock): View {
        val uri = Uri.parse(block.uri)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return artifactBlock(block, "Web page")
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground("#FFFFFF", 7f, "#DDE3E8")
            addView(TextView(activity).apply {
                text = block.title.ifBlank { uri.host.orEmpty() }
                textSize = 14f
                setTextColor(Color.parseColor("#14202B"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(9), dp(12), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { openUri(block.uri, "text/html") }
            })
            val webView = WebView(activity).apply {
                setBackgroundColor(Color.WHITE)
                settings.javaScriptEnabled = true
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
                        view?.let(AgentRichPlaybackCoordinator::sync)
                    }
                }
                contentDescription = block.title.ifBlank { "Web page preview" }
                loadUrl(block.uri)
            }
            coordinatePlayback(webView)
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)))
            addView(selectableText(block.uri, 11f).apply {
                setTextColor(Color.parseColor("#66717D"))
                maxLines = 1
                setPadding(dp(12), dp(7), dp(12), dp(9))
            })
        }
    }

    private fun coordinatePlayback(webView: WebView) {
        webView.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                AgentRichPlaybackCoordinator.activate(view as WebView)
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

    private fun artifactBlock(block: AgentRichBlock, fallbackTitle: String): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = roundedBackground("#F6F8FA", 7f, "#DDE3E8")
            addView(TextView(activity).apply {
                text = block.title.ifBlank { fallbackTitle }
                textSize = 15f
                setTextColor(Color.parseColor("#14202B"))
                setTypeface(typeface, Typeface.BOLD)
            })
            val detail = block.text.ifBlank { block.uri }
            if (detail.isNotBlank()) addView(selectableText(detail, 12f).apply {
                setTextColor(Color.parseColor("#66717D"))
                maxLines = 2
                setPadding(0, dp(3), 0, 0)
            })
            if (isOpenableUri(block.uri)) {
                isClickable = true
                isFocusable = true
                setOnClickListener { openUri(block.uri, block.mimeType) }
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
            val heading = block.title.ifBlank { "Actions" }
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
        addView(selectableText(block.title.ifBlank { "Input required" }, 15f).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        if (block.text.isNotBlank()) addView(selectableText(block.text, 13f).apply {
            setPadding(0, dp(4), 0, dp(5))
        })
        val inputs = linkedMapOf<String, EditText>()
        block.fields.forEach { field ->
            addView(EditText(activity).apply {
                hint = field.label + if (field.required) " *" else ""
                setText(field.value)
                textSize = 15f
                setSingleLine(field.inputType !in setOf("multiline", "textarea"))
                setPadding(dp(11), dp(9), dp(11), dp(9))
                background = roundedBackground("#FFFFFF", 6f, "#C9D4DD")
                inputs[field.id] = this
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(7)
            })
        }
        addView(Button(activity).apply {
            text = block.actions.firstOrNull()?.label ?: "Submit"
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBackground("#087F69", 6f, "#087F69")
            setOnClickListener {
                val values = inputs.mapValues { it.value.text?.toString().orEmpty() }
                val missing = block.fields.any { it.required && values[it.id].isNullOrBlank() }
                if (missing) Toast.makeText(activity, "Complete required fields", Toast.LENGTH_SHORT).show()
                else onFormSubmit(block, values)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(9) })
    }

    private fun selectableText(value: String, sizeSp: Float): TextView = TextView(activity).apply {
        text = value
        textSize = sizeSp
        setTextColor(Color.parseColor("#14202B"))
        setLineSpacing(dp(4).toFloat(), 1f)
        setTextIsSelectable(true)
        onTextViewReady(this)
    }

    private fun loadImage(uri: String, image: ImageView) {
        if (!isPreviewableUri(uri)) return
        when (Uri.parse(uri).scheme?.lowercase()) {
            "content", "file", "android.resource" -> runCatching { image.setImageURI(Uri.parse(uri)) }
            "https" -> thread(name = "signalasi-rich-image") {
                val request = Request.Builder().url(uri).get().build()
                val decoded = runCatching {
                    HTTP.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val body = response.body ?: return@use null
                        val length = body.contentLength()
                        if (length > MAX_IMAGE_BYTES) return@use null
                        val bytes = body.bytes()
                        if (bytes.size > MAX_IMAGE_BYTES) return@use null
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                        } else {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    }
                }.getOrNull()
                if (decoded != null) Handler(Looper.getMainLooper()).post {
                    if (activity.isDestroyed) return@post
                    when (decoded) {
                        is AnimatedImageDrawable -> {
                            decoded.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            image.setImageDrawable(decoded)
                            coordinateAnimatedImage(image, decoded)
                        }
                        is android.graphics.drawable.Drawable -> image.setImageDrawable(decoded)
                        is android.graphics.Bitmap -> image.setImageBitmap(decoded)
                    }
                }
            }
        }
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

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_ASSISTANT_WIDTH_RATIO = 0.78f
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        private val HTTP = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

private object AgentRichPlaybackCoordinator {
    private var active = WeakReference<WebView>(null)
    private var activeImage = WeakReference<ImageView>(null)
    private var activeDrawable = WeakReference<AnimatedImageDrawable>(null)

    fun activate(view: WebView) {
        activeDrawable.get()?.stop()
        activeImage.get()?.visibility = View.GONE
        activeImage.clear()
        activeDrawable.clear()
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
            activeImage.get()?.visibility = View.GONE
        }
        activeImage = WeakReference(view)
        activeDrawable = WeakReference(drawable)
        view.visibility = View.VISIBLE
        drawable.start()
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

    private fun pause(view: WebView) {
        view.onPause()
        view.evaluateJavascript(PAUSE_SCRIPT, null)
        view.visibility = View.GONE
    }

    private fun resume(view: WebView) {
        view.visibility = View.VISIBLE
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
