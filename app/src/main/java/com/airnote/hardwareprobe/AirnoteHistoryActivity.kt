package com.airnote.hardwareprobe

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AirnoteHistoryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AIRNOTE — Activity"
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val store = AirnotePaymentStore(this)
        store.expireDue()
        val payments = store.all()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
        }
        root.addView(TextView(this).apply {
            text = "ACTIVITY"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(TextView(this).apply {
            text = "Offline acceptance and Solana settlement history"
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 6, 0, 24)
        })

        if (payments.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "No Airnotes yet.\nReceived Airnotes will appear here immediately after offline acceptance."
                textSize = 15f
                setPadding(0, 24, 0, 24)
            })
        } else {
            payments.forEach { payment ->
                root.addView(Button(this).apply {
                    text = rowText(payment)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    isAllCaps = false
                    setOnClickListener {
                        startActivity(Intent(this@AirnoteHistoryActivity, AirnotePaymentDetailActivity::class.java).apply {
                            putExtra(AirnotePaymentDetailActivity.EXTRA_PAYMENT_ID, payment.id)
                        })
                    }
                })
            }
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun rowText(payment: AirnotePayment): String {
        val sign = if (payment.direction == PaymentDirection.RECEIVED) "+" else "-"
        val amount = payment.amountBaseUnits / 1_000_000.0
        val direction = if (payment.direction == PaymentDirection.RECEIVED) "Received" else "Sent"
        val time = DateTimeFormatter.ofPattern("MMM d • HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(payment.createdAtUnixSeconds))
        return "$direction    $sign${"%.2f".format(amount)} USDC\n${displayStatus(payment.status)}    $time"
    }

    private fun displayStatus(status: PaymentStatus): String = when (status) {
        PaymentStatus.CREATED -> "Created"
        PaymentStatus.ACCEPTED_OFFLINE -> "Received offline"
        PaymentStatus.PENDING -> "Pending"
        PaymentStatus.SETTLING -> "Settling"
        PaymentStatus.SETTLED -> "Settled"
        PaymentStatus.FAILED_RETRYABLE -> "Retry needed"
        PaymentStatus.REJECTED -> "Rejected"
        PaymentStatus.EXPIRED -> "Expired"
    }
}
