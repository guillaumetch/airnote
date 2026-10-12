package com.airnote.hardwareprobe

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object AirnoteUi {
    const val AIR = "#2478F3"
    const val AIR_DEEP = "#0B2A5B"
    const val SKY_50 = "#F5FAFF"
    const val SKY_100 = "#EAF5FF"
    const val CLOUD = "#FFFFFF"
    const val MIST = "#E6ECF3"
    const val SLATE = "#68788D"
    const val NIGHT = "#101D33"
    const val SUCCESS = "#20C983"
    const val WARNING = "#F3B94F"
    const val ERROR = "#E65C67"

    fun color(hex: String): Int = Color.parseColor(hex)

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun rounded(
        context: Context,
        fill: String,
        radiusDp: Int = 20,
        stroke: String? = null,
        strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color(fill))
        cornerRadius = dp(context, radiusDp).toFloat()
        if (stroke != null) setStroke(dp(context, strokeDp), color(stroke))
    }

    fun heroBackground(context: Context): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(color("#DDF0FF"), color("#F8FCFF"), color("#FFF4E8"))
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 24).toFloat()
            setStroke(dp(context, 1), color("#D9E7F5"))
        }

    fun card(context: Context, paddingDp: Int = 18): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp))
            background = rounded(context, "#FCFEFF", 20, "#DDE7F0")
            elevation = dp(context, 1).toFloat()
        }

    fun glassCard(context: Context, paddingDp: Int = 18): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp))
            background = rounded(context, "#F8FCFF", 22, "#D7E5F1")
            elevation = 0f
        }

    fun iconBubble(context: Context, glyph: String, sizeDp: Int = 42): TextView =
        TextView(context).apply {
            text = glyph
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setTextColor(color(AIR))
            background = rounded(context, "#EAF5FF", 999)
            minWidth = dp(context, sizeDp)
            minHeight = dp(context, sizeDp)
        }

    fun sectionLabel(context: Context, value: String): TextView =
        TextView(context).apply {
            text = value.uppercase()
            textSize = 12f
            setTextColor(color(SLATE))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
        }

    fun title(context: Context, value: String, size: Float = 26f): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color(NIGHT))
            setTypeface(typeface, Typeface.BOLD)
        }

    fun body(context: Context, value: String, size: Float = 14f): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color(SLATE))
        }

    fun statusPill(context: Context, value: String, success: Boolean = true): TextView =
        TextView(context).apply {
            text = value
            textSize = 11.5f
            setTextColor(color(if (success) "#087B50" else AIR_DEEP))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7))
            background = rounded(context, if (success) "#E7FAF2" else SKY_100, 999)
        }

    fun primaryButton(context: Context, value: String): Button =
        Button(context).apply {
            text = value
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(color("#2478F3"), color("#3C91FF"))
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 18).toFloat()
            }
            minHeight = dp(context, 54)
            elevation = 0f
            stateListAnimator = null
        }

    fun secondaryButton(context: Context, value: String): Button =
        Button(context).apply {
            text = value
            isAllCaps = false
            textSize = 14f
            setTextColor(color(AIR_DEEP))
            background = rounded(context, "#FCFEFF", 18, "#DDE7F0")
            minHeight = dp(context, 52)
            elevation = 0f
            stateListAnimator = null
        }

    fun compactButton(context: Context, value: String): Button =
        secondaryButton(context, value).apply {
            textSize = 13f
            minHeight = dp(context, 44)
        }

    fun selectedChoice(button: Button, context: Context, selected: Boolean) {
        button.setTextColor(color(if (selected) CLOUD else AIR_DEEP))
        button.background = rounded(
            context,
            if (selected) AIR else CLOUD,
            16,
            if (selected) AIR else MIST
        )
    }

    fun spacer(context: Context, heightDp: Int): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(context, heightDp))
        }

    fun marginParams(
        context: Context,
        width: Int = LinearLayout.LayoutParams.MATCH_PARENT,
        height: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
        top: Int = 0,
        bottom: Int = 0
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height).apply {
            topMargin = dp(context, top)
            bottomMargin = dp(context, bottom)
        }
}
