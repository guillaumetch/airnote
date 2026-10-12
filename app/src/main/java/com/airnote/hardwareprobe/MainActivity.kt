package com.airnote.hardwareprobe

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var localAirnoteStore: LocalAirnoteStore
    private lateinit var heroReserveAmountView: TextView
    private lateinit var heroReserveMetaView: TextView
    private lateinit var recentActivityContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localAirnoteStore = LocalAirnoteStore(this)
        localAirnoteStore.importLegacyMintRequests(getExternalFilesDir(null)?.listFiles().orEmpty())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                AirnoteUi.dp(this@MainActivity, 22),
                AirnoteUi.dp(this@MainActivity, 24),
                AirnoteUi.dp(this@MainActivity, 22),
                AirnoteUi.dp(this@MainActivity, 28)
            )
            setBackgroundColor(AirnoteUi.color(AirnoteUi.SKY_50))
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(
            AirnoteUi.title(this, "AIRNOTE", 25f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        brandRow.addView(AirnoteUi.body(this, "●", 16f).apply {
            setTextColor(AirnoteUi.color(AirnoteUi.SUCCESS))
        })
        root.addView(brandRow)
        root.addView(
            AirnoteUi.body(this, "Money that moves with you. Online or offline.", 13f),
            AirnoteUi.marginParams(this, top = 2, bottom = 14)
        )

        val heroFrame = FrameLayout(this)
        heroFrame.addView(
            AirnoteHeroView(this),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                AirnoteUi.dp(this, 178)
            )
        )
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                AirnoteUi.dp(this@MainActivity, 18),
                AirnoteUi.dp(this@MainActivity, 18),
                AirnoteUi.dp(this@MainActivity, 18),
                AirnoteUi.dp(this@MainActivity, 16)
            )
        }
        hero.addView(AirnoteUi.sectionLabel(this, "USDC Balance"))
        heroReserveAmountView = AirnoteUi.title(this, "0.00 USDC", 31f)
        hero.addView(heroReserveAmountView, AirnoteUi.marginParams(this, top = 4))
        hero.addView(AirnoteUi.statusPill(this, "●  Ready Offline", true), AirnoteUi.marginParams(this, top = 7))
        heroReserveMetaView = AirnoteUi.body(this, "Create an Airnote to prepare funds for offline use.", 11.5f)
        hero.addView(heroReserveMetaView, AirnoteUi.marginParams(this, top = 7))
        heroFrame.addView(
            hero,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(heroFrame, AirnoteUi.marginParams(this, height = AirnoteUi.dp(this, 178), bottom = 12))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }
        val tiles = listOf(
            Triple("＋", "Create\n& Fund", CreateFundActivity::class.java),
            Triple("➤", "Send", Gate04SenderActivity::class.java),
            Triple("⌗", "Receive", Gate04ReceiverActivity::class.java),
            Triple("▤", "Activity", AirnoteHistoryActivity::class.java)
        )
        tiles.forEach { (icon, label, target) ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(
                    AirnoteUi.dp(this@MainActivity, 4),
                    AirnoteUi.dp(this@MainActivity, 10),
                    AirnoteUi.dp(this@MainActivity, 4),
                    AirnoteUi.dp(this@MainActivity, 10)
                )
                background = AirnoteUi.rounded(this@MainActivity, "#FCFEFF", 18, "#DDE7F0")
                elevation = AirnoteUi.dp(this@MainActivity, 1).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener { startActivity(Intent(this@MainActivity, target)) }
            }
            tile.addView(AirnoteUi.iconBubble(this, icon, 40))
            tile.addView(TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(AirnoteUi.color(AirnoteUi.NIGHT))
            }, AirnoteUi.marginParams(this, top = 4))
            actions.addView(
                tile,
                LinearLayout.LayoutParams(0, AirnoteUi.dp(this, 84), 1f).apply {
                    marginStart = AirnoteUi.dp(this@MainActivity, 3)
                    marginEnd = AirnoteUi.dp(this@MainActivity, 3)
                }
            )
        }
        root.addView(actions, AirnoteUi.marginParams(this, bottom = 12))

        val readyCard = AirnoteUi.glassCard(this, 14)
        val readyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        readyRow.addView(AirnoteUi.iconBubble(this, "◌", 38))
        val readyCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        readyCopy.addView(AirnoteUi.title(this, "Ready Offline", 15f))
        readyCopy.addView(AirnoteUi.body(this, "Two scans. No internet required.", 12f))
        readyRow.addView(
            readyCopy,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = AirnoteUi.dp(this@MainActivity, 10)
            }
        )
        readyCard.addView(readyRow)
        root.addView(readyCard, AirnoteUi.marginParams(this, bottom = 18))

        val recentHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recentHeader.addView(
            AirnoteUi.title(this, "Recent Activity", 18f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        recentHeader.addView(AirnoteUi.body(this, "See all", 12f).apply {
            setTextColor(AirnoteUi.color(AirnoteUi.AIR))
            setOnClickListener { startActivity(Intent(this@MainActivity, AirnoteHistoryActivity::class.java)) }
        })
        root.addView(recentHeader, AirnoteUi.marginParams(this, bottom = 8))

        recentActivityContainer = AirnoteUi.glassCard(this, 12)
        root.addView(recentActivityContainer)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })

        refreshHome()
        AirnoteReconciliationScheduler.enqueue(this)
    }

    override fun onResume() {
        super.onResume()
        if (::recentActivityContainer.isInitialized) refreshHome()
        AirnoteReconciliationScheduler.enqueue(this)
    }

    private fun refreshHome() {
        val notes = localAirnoteStore.all()
        val readyNotes = notes.filter { it.state == LocalAirnoteState.READY_OFFLINE }
        val readyAmount = readyNotes.sumOf { it.amountUsdc }

        heroReserveAmountView.text = String.format("%.2f USDC", readyAmount.toDouble())
        heroReserveMetaView.text = when {
            readyNotes.isEmpty() -> "No prepared Airnotes yet."
            readyNotes.size == 1 -> "1 Airnote ready to move offline."
            else -> readyNotes.size.toString() + " Airnotes ready to move offline."
        }

        recentActivityContainer.removeAllViews()
        val paymentStore = AirnotePaymentStore(this)
        paymentStore.expireDue()
        val payments = paymentStore.all()
            .sortedByDescending { it.createdAtUnixSeconds }
            .take(3)

        if (payments.isEmpty() && notes.isEmpty()) {
            recentActivityContainer.addView(
                AirnoteUi.body(this, "No activity yet. Create your first Airnote.", 13f)
            )
            return
        }

        payments.forEachIndexed { index, payment ->
            if (index > 0) {
                recentActivityContainer.addView(
                    android.view.View(this).apply {
                        setBackgroundColor(AirnoteUi.color(AirnoteUi.MIST))
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        AirnoteUi.dp(this, 1)
                    ).apply {
                        topMargin = AirnoteUi.dp(this@MainActivity, 8)
                        bottomMargin = AirnoteUi.dp(this@MainActivity, 8)
                    }
                )
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val direction = if (payment.direction == PaymentDirection.RECEIVED) "Received" else "Sent"
            left.addView(AirnoteUi.title(this, direction, 14f))
            left.addView(AirnoteUi.body(this, statusLabel(payment.status), 11f))
            row.addView(
                left,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(
                AirnoteUi.title(
                    this,
                    String.format("%.2f USDC", payment.amountBaseUnits / 1_000_000.0),
                    14f
                )
            )
            recentActivityContainer.addView(row)
        }

        if (payments.isEmpty()) {
            notes.take(3).forEach { note ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                left.addView(AirnoteUi.title(this, note.amountUsdc.toString() + " USDC Airnote", 14f))
                left.addView(
                    AirnoteUi.body(
                        this,
                        note.state.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                        11f
                    )
                )
                row.addView(
                    left,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                recentActivityContainer.addView(row)
            }
        }
    }

    private fun statusLabel(status: PaymentStatus): String = when (status) {
        PaymentStatus.CREATED -> "Created"
        PaymentStatus.ACCEPTED_OFFLINE -> "Received offline"
        PaymentStatus.PENDING -> "Waiting to settle"
        PaymentStatus.SETTLING -> "Settling"
        PaymentStatus.SETTLED -> "Settled"
        PaymentStatus.FAILED_RETRYABLE -> "Needs retry"
        PaymentStatus.REJECTED -> "Rejected"
        PaymentStatus.EXPIRED -> "Expired"
    }
}
