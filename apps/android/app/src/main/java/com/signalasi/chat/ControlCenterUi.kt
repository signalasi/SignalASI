package com.signalasi.chat

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

enum class ControlCenterTone {
    NEUTRAL,
    GREEN,
    BLUE,
    AMBER,
    RED,
    VIOLET
}

enum class ControlCenterRoute(val wireValue: String) {
    PROFILE("profile"),
    SYSTEM_STATUS("system_status"),
    AGENT_CORE("agent_core"),
    EXECUTION_POLICY("execution_policy"),
    RESOURCE_ROUTING("resource_routing"),
    MEMORY("memory"),
    KNOWLEDGE("knowledge"),
    SKILLS("skills"),
    TASKS("tasks"),
    PHONE_CAPABILITIES("phone_capabilities"),
    APP_TOOLS("app_tools"),
    SMART_SPACES("smart_spaces"),
    NODES("nodes"),
    SECURITY("security"),
    PERMISSIONS_AUDIT("permissions_audit"),
    VOICE("voice"),
    DATA_BACKUP("data_backup"),
    GENERAL("general"),
    APP_SERVICES("app_services"),
    ADVANCED("advanced"),
    RESET("reset");

    companion object {
        fun fromWireValue(value: String): ControlCenterRoute? = entries.firstOrNull {
            it.wireValue == value.trim().lowercase()
        }
    }
}

data class ControlCenterBadgeSpec(
    val text: String,
    val tone: ControlCenterTone = ControlCenterTone.NEUTRAL
)

data class ControlCenterMetricSpec(
    val value: String,
    val label: String
)

data class ControlCenterHeroSpec(
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val badges: List<ControlCenterBadgeSpec> = emptyList(),
    val metrics: List<ControlCenterMetricSpec> = emptyList(),
    val actionId: String = "",
    val preserveIconColor: Boolean = false
)

data class ControlCenterBannerSpec(
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val tone: ControlCenterTone = ControlCenterTone.BLUE,
    val actionId: String = ""
)

data class ControlCenterRowSpec(
    val actionId: String,
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val status: String = "",
    val tone: ControlCenterTone = ControlCenterTone.NEUTRAL,
    val switchValue: Boolean? = null,
    val showChevron: Boolean = true,
    val enabled: Boolean = true,
    val preserveIconColor: Boolean = false
)

data class ControlCenterSectionSpec(
    val title: String,
    val rows: List<ControlCenterRowSpec>
)

data class ControlCenterPageSpec(
    val hero: ControlCenterHeroSpec? = null,
    val banner: ControlCenterBannerSpec? = null,
    val sections: List<ControlCenterSectionSpec>,
    val footer: String = ""
)

class ControlCenterRenderer(private val context: Context) {
    fun render(
        content: LinearLayout,
        page: ControlCenterPageSpec,
        onAction: (String) -> Unit
    ) {
        content.removeAllViews()
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.NO_GRAVITY
        content.setPadding(dp(14), dp(12), dp(14), dp(28))

        page.banner?.let { content.addView(banner(it, onAction)) }
        page.hero?.let { content.addView(hero(it, onAction)) }
        page.sections.forEach { section ->
            if (section.rows.isEmpty()) return@forEach
            content.addView(sectionTitle(section.title))
            content.addView(sectionCard(section.rows, onAction))
        }
        if (page.footer.isNotBlank()) {
            content.addView(TextView(context).apply {
                text = page.footer
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.text_secondary))
                setPadding(dp(14), dp(16), dp(14), dp(4))
            })
        }
    }

    private fun hero(spec: ControlCenterHeroSpec, onAction: (String) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = cardBackground(dp(14), color(R.color.surface_bg), dividerColor())
            isClickable = spec.actionId.isNotBlank()
            isFocusable = isClickable
            if (isClickable) setOnClickListener { onAction(spec.actionId) }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(iconView(spec.iconRes, ControlCenterTone.BLUE, 56, spec.preserveIconColor))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, dp(8), 0)
                    addView(TextView(context).apply {
                        text = spec.title
                        textSize = 18f
                        setTextColor(color(R.color.text_primary))
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(TextView(context).apply {
                        text = spec.subtitle
                        textSize = 11.5f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(color(R.color.text_secondary))
                        setPadding(0, dp(4), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (spec.actionId.isNotBlank()) addView(chevron())
            })

            if (spec.badges.isNotEmpty()) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(12), 0, 0)
                    spec.badges.take(3).forEachIndexed { index, badge ->
                        addView(badge(badge), LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            dp(24)
                        ).apply { if (index > 0) marginStart = dp(7) })
                    }
                })
            }

            if (spec.metrics.isNotEmpty()) {
                addView(View(context).apply { setBackgroundColor(dividerColor()) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(13) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, 0)
                    spec.metrics.take(3).forEachIndexed { index, metric ->
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            minimumHeight = dp(48)
                            setPadding(0, 0, 0, dp(4))
                            addView(TextView(context).apply {
                                text = metric.value
                                textSize = 16f
                                gravity = Gravity.CENTER
                                setTextColor(color(R.color.text_primary))
                                setTypeface(typeface, Typeface.BOLD)
                            })
                            addView(TextView(context).apply {
                                text = metric.label
                                textSize = 9.5f
                                gravity = Gravity.CENTER
                                maxLines = 2
                                ellipsize = TextUtils.TruncateAt.END
                                setTextColor(color(R.color.text_secondary))
                                setPadding(dp(2), dp(3), dp(2), 0)
                            })
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        if (index < spec.metrics.take(3).lastIndex) {
                            addView(View(context).apply { setBackgroundColor(dividerColor()) },
                                LinearLayout.LayoutParams(1, dp(39)))
                        }
                    }
                })
            }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(3) }
        }

    private fun banner(spec: ControlCenterBannerSpec, onAction: (String) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            val palette = palette(spec.tone)
            background = cardBackground(dp(12), palette.soft, palette.border)
            isClickable = spec.actionId.isNotBlank()
            isFocusable = isClickable
            if (isClickable) setOnClickListener { onAction(spec.actionId) }
            addView(iconView(spec.iconRes, spec.tone, 34, false))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, dp(4), 0)
                addView(TextView(context).apply {
                    text = spec.title
                    textSize = 13.5f
                    setTextColor(color(R.color.text_primary))
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = spec.subtitle
                    textSize = 10.5f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(color(R.color.text_secondary))
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (spec.actionId.isNotBlank()) addView(chevron())
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

    private fun sectionTitle(title: String): TextView = TextView(context).apply {
        text = title
        textSize = 12.5f
        setTextColor(color(R.color.text_secondary))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(14), 0, dp(7))
    }

    private fun sectionCard(rows: List<ControlCenterRowSpec>, onAction: (String) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(dp(12), color(R.color.surface_bg), dividerColor())
            rows.forEachIndexed { index, spec ->
                addView(row(spec, onAction))
                if (index < rows.lastIndex) {
                    addView(View(context).apply { setBackgroundColor(dividerColor()) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                            marginStart = dp(58)
                        })
                }
            }
        }

    private fun row(spec: ControlCenterRowSpec, onAction: (String) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(66)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            alpha = if (spec.enabled) 1f else 0.48f
            isEnabled = spec.enabled
            isClickable = spec.enabled && spec.actionId.isNotBlank()
            isFocusable = isClickable
            if (isClickable) setOnClickListener { onAction(spec.actionId) }
            addView(iconView(spec.iconRes, spec.tone, 32, spec.preserveIconColor))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(TextView(context).apply {
                    text = spec.title
                    textSize = 15f
                    setTextColor(color(R.color.text_primary))
                    setTypeface(typeface, Typeface.NORMAL)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (spec.subtitle.isNotBlank()) {
                    addView(TextView(context).apply {
                        text = spec.subtitle
                        textSize = 11f
                        setTextColor(color(R.color.text_secondary))
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(3), 0, 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (spec.status.isNotBlank()) {
                addView(TextView(context).apply {
                    text = spec.status
                    textSize = 10.5f
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    maxLines = 2
                    setTextColor(palette(spec.tone).strong)
                    setPadding(dp(4), 0, dp(3), 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
            }
            if (spec.switchValue != null) {
                addView(switchPill(spec.switchValue))
            } else if (spec.showChevron && spec.actionId.isNotBlank()) {
                addView(chevron())
            }
        }

    private fun iconView(
        iconRes: Int,
        tone: ControlCenterTone,
        sizeDp: Int,
        preserveColor: Boolean
    ): ImageView = ImageView(context).apply {
        setImageResource(iconRes)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        if (preserveColor) {
            background = null
            setPadding(0, 0, 0, 0)
            imageTintList = null
        } else {
            val palette = palette(tone)
            background = cardBackground(if (sizeDp >= 48) dp(14) else dp(9), palette.soft, Color.TRANSPARENT)
            setPadding(dp(if (sizeDp >= 48) 11 else 7), dp(if (sizeDp >= 48) 11 else 7), dp(if (sizeDp >= 48) 11 else 7), dp(if (sizeDp >= 48) 11 else 7))
            imageTintList = ColorStateList.valueOf(palette.strong)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun badge(spec: ControlCenterBadgeSpec): TextView {
        val palette = palette(spec.tone)
        return TextView(context).apply {
            text = spec.text
            textSize = 10.5f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(palette.strong)
            setPadding(dp(8), 0, dp(8), 0)
            background = cardBackground(dp(8), palette.soft, Color.TRANSPARENT)
        }
    }

    private fun switchPill(checked: Boolean): View = FrameLayout(context).apply {
        background = cardBackground(
            dp(12),
            if (checked) Color.parseColor("#18B979") else Color.parseColor("#D0D5DD"),
            Color.TRANSPARENT
        )
        addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }, FrameLayout.LayoutParams(dp(18), dp(18)).apply {
            gravity = (if (checked) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(22))
    }

    private fun chevron(): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_arrow_right)
        imageTintList = ColorStateList.valueOf(color(R.color.icon_gray))
        scaleType = ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(20), dp(36))
    }

    private fun cardBackground(radius: Int, fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fill)
            if (Color.alpha(stroke) > 0) setStroke(1, stroke)
        }

    private data class Palette(val soft: Int, val strong: Int, val border: Int)

    private fun palette(tone: ControlCenterTone): Palette = if (isNight()) {
        when (tone) {
            ControlCenterTone.GREEN -> Palette(Color.parseColor("#18382A"), Color.parseColor("#54D89A"), Color.parseColor("#24553D"))
            ControlCenterTone.BLUE -> Palette(Color.parseColor("#1D2E4A"), Color.parseColor("#76A9FF"), Color.parseColor("#29436B"))
            ControlCenterTone.AMBER -> Palette(Color.parseColor("#3D2D14"), Color.parseColor("#F4B95D"), Color.parseColor("#5C4320"))
            ControlCenterTone.RED -> Palette(Color.parseColor("#44211F"), Color.parseColor("#FF8A83"), Color.parseColor("#62302D"))
            ControlCenterTone.VIOLET -> Palette(Color.parseColor("#30274A"), Color.parseColor("#B8A1FF"), Color.parseColor("#493A70"))
            ControlCenterTone.NEUTRAL -> Palette(Color.parseColor("#2D323A"), Color.parseColor("#C7CED8"), Color.parseColor("#3E4651"))
        }
    } else {
        when (tone) {
            ControlCenterTone.GREEN -> Palette(Color.parseColor("#E8F8F0"), Color.parseColor("#14875A"), Color.parseColor("#CBEBD9"))
            ControlCenterTone.BLUE -> Palette(Color.parseColor("#EAF2FF"), Color.parseColor("#286FD6"), Color.parseColor("#D2E2FB"))
            ControlCenterTone.AMBER -> Palette(Color.parseColor("#FFF4DE"), Color.parseColor("#B26B00"), Color.parseColor("#F3D9A6"))
            ControlCenterTone.RED -> Palette(Color.parseColor("#FFEDEC"), Color.parseColor("#C7372F"), Color.parseColor("#F5CECB"))
            ControlCenterTone.VIOLET -> Palette(Color.parseColor("#F0ECFF"), Color.parseColor("#7052CC"), Color.parseColor("#DDD4FA"))
            ControlCenterTone.NEUTRAL -> Palette(Color.parseColor("#F0F3F6"), Color.parseColor("#475467"), Color.parseColor("#E2E7ED"))
        }
    }

    private fun isNight(): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun dividerColor(): Int = color(R.color.separator)

    private fun color(resourceId: Int): Int = ContextCompat.getColor(context, resourceId)
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
