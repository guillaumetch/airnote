package com.airnote.hardwareprobe

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

object AirnoteBottomNav {
    enum class Tab { HOME, SEND, RECEIVE, ACTIVITY }

    fun attach(
        activity: Activity,
        content: android.view.View,
        selected: Tab
    ): LinearLayout {
        val shell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AirnoteUi.color(AirnoteUi.SKY_50))
        }
        shell.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val nav = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AirnoteUi.rounded(activity, "#FCFEFF", 0, "#DDE7F0")
            elevation = AirnoteUi.dp(activity, 6).toFloat()
        }

        val items = listOf(
            Triple(Tab.HOME, Pair("⌂", "Home"), MainActivity::class.java),
            Triple(Tab.SEND, Pair("➤", "Send"), Gate04SenderActivity::class.java),
            Triple(Tab.RECEIVE, Pair("⌗", "Receive"), Gate04ReceiverActivity::class.java),
            Triple(Tab.ACTIVITY, Pair("▤", "Activity"), AirnoteHistoryActivity::class.java)
        )

        items.forEach { (tab, copy, target) ->
            val active = tab == selected
            val item = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                minimumHeight = AirnoteUi.dp(activity, 52)
                setOnClickListener {
                    if (tab == selected) return@setOnClickListener
                    val intent = Intent(activity, target)
                    if (tab == Tab.HOME) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    activity.startActivity(intent)
                }
            }
            item.addView(TextView(activity).apply {
                text = copy.first
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(AirnoteUi.color(if (active) AirnoteUi.AIR else "#8EA0B5"))
            })
            item.addView(TextView(activity).apply {
                text = copy.second
                textSize = 9.5f
                gravity = Gravity.CENTER
                setTextColor(AirnoteUi.color(if (active) AirnoteUi.AIR else "#8EA0B5"))
            })
            nav.addView(
                item,
                LinearLayout.LayoutParams(0, AirnoteUi.dp(activity, 52), 1f)
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(nav) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(
                AirnoteUi.dp(activity, 10),
                AirnoteUi.dp(activity, 5),
                AirnoteUi.dp(activity, 10),
                AirnoteUi.dp(activity, 6) + bottom
            )
            insets
        }

        shell.addView(
            nav,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        ViewCompat.requestApplyInsets(nav)
        return shell
    }
}
