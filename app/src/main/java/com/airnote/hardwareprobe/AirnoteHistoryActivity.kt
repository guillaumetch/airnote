package com.airnote.hardwareprobe

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AirnoteHistoryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val store = AirnotePaymentStore(this)
        store.expireDue()
        val payments = store.all().sortedByDescending { it.createdAtUnixSeconds }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                AirnoteUi.dp(this@AirnoteHistoryActivity, 22),
                AirnoteUi.dp(this@AirnoteHistoryActivity, 24),
                AirnoteUi.dp(this@AirnoteHistoryActivity, 22),
                AirnoteUi.dp(this@AirnoteHistoryActivity, 28)
            )
            setBackgroundColor(AirnoteUi.color(AirnoteUi.SKY_50))
        }

        root.addView(AirnoteUi.sectionLabel(this, "AIRNOTE"))
        root.addView(AirnoteUi.title(this, "Activity", 26f), AirnoteUi.marginParams(this, top = 2))
        root.addView(
            AirnoteUi.body(this, "Offline handoffs and Solana settlement history.", 13f),
            AirnoteUi.marginParams(this, top = 3, bottom = 16)
        )

        if (payments.isEmpty()) {
            val empty = AirnoteUi.card(this, 18)
            empty.addView(AirnoteUi.title(this, "No activity yet", 17f))
            empty.addView(
                AirnoteUi.body(
                    this,
                    "Received Airnotes appear here immediately after offline acceptance.",
                    13f
                ),
                AirnoteUi.marginParams(this, top = 6)
            )
            root.addView(empty)
        } else {
            payments.forEachIndexed { index, payment ->
                val card = AirnoteUi.card(this, 14).apply {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        startActivity(
                            Intent(this@AirnoteHistoryActivity, AirnotePaymentDetailActivity::class.java)
                                .putExtra(AirnotePaymentDetailActivity.EXTRA_PAYMENT_ID, payment.id)
                        )
                    }
                }

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val direction = if (payment.direction == PaymentDirection.RECEIVED) "Received" else "Sent"
                left.addView(AirnoteUi.title(this, direction, 15f))
                left.addView(
                    AirnoteUi.body(
                        this,
                        displayStatus(payment.status) + " · " + displayTime(payment.createdAtUnixSeconds),
                        11.5f
                    ),
                    AirnoteUi.marginParams(this, top = 2)
                )
                row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                val sign = if (payment.direction == PaymentDirection.RECEIVED) "+" else "−"
                val amount = payment.amountBaseUnits / 1_000_000.0
                row.addView(
                    AirnoteUi.title(this, sign + String.format("%.2f", amount) + " USDC", 15f).apply {
                        if (payment.direction == PaymentDirection.RECEIVED) {
                            setTextColor(AirnoteUi.color(AirnoteUi.SUCCESS))
                        }
                    }
                )

                card.addView(row)
                root.addView(card, AirnoteUi.marginParams(this, bottom = if (index == payments.lastIndex) 0 else 8))
            }
        }

        setContentView(
            ScrollView(this).apply {
                isFillViewport = true
                setBackgroundColor(AirnoteUi.color(AirnoteUi.SKY_50))
                addView(root)
            }
        )
    }

    private fun displayTime(epochSeconds: Long): String =
        DateTimeFormatter.ofPattern("MMM d · HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(epochSeconds))

    private fun displayStatus(status: PaymentStatus): String = when (status) {
        PaymentStatus.CREATED -> "Created"
        PaymentStatus.ACCEPTED_OFFLINE -> "Received offline"
        PaymentStatus.PENDING -> "Waiting to settle"
        PaymentStatus.SETTLING -> "Settling"
        PaymentStatus.SETTLED -> "Settled"
        PaymentStatus.FAILED_RETRYABLE -> "Retry needed"
        PaymentStatus.REJECTED -> "Rejected"
        PaymentStatus.EXPIRED -> "Expired"
    }
}
