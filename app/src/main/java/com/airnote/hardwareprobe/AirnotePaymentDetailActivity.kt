package com.airnote.hardwareprobe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.time.Instant

class AirnotePaymentDetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AIRNOTE — Payment"
        render()
    }

    private fun render() {
        val id = intent.getStringExtra(EXTRA_PAYMENT_ID) ?: return finish()
        val store = AirnotePaymentStore(this)
        store.expireDue()
        val payment = store.get(id) ?: return finish()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
        }
        val amount = payment.amountBaseUnits / 1_000_000.0
        root.addView(TextView(this).apply {
            text = "${"%.2f".format(amount)} USDC"
            textSize = 30f
        })
        root.addView(TextView(this).apply {
            text = if (payment.direction == PaymentDirection.RECEIVED) "Received" else "Sent"
            textSize = 18f
        })
        root.addView(TextView(this).apply {
            text = statusLabel(payment.status)
            textSize = 22f
            setPadding(0, 10, 0, 24)
        })

        root.addView(TextView(this).apply {
            text = buildString {
                appendLine("Created\n${Instant.ofEpochSecond(payment.createdAtUnixSeconds)}")
                appendLine()
                if (payment.direction == PaymentDirection.RECEIVED) appendLine("Accepted offline\n${Instant.ofEpochSecond(payment.createdAtUnixSeconds)}\n")
                payment.lastSettlementAttemptUnixSeconds?.let {
                    appendLine("Settlement attempt\n${Instant.ofEpochSecond(it)}\n")
                }
                if (payment.status == PaymentStatus.SETTLED) appendLine("Settled on Solana")
                if (payment.status == PaymentStatus.EXPIRED) appendLine("This Airnote expired before settlement could complete.")
                appendLine()
                appendLine("Expires\n${Instant.ofEpochSecond(payment.expiresAtUnixSeconds)}")
                payment.settlementSignature?.let { appendLine("\nSolana transaction\n$it") }
            }
            textSize = 15f
        })

        payment.protocolPayload?.let { payload ->
            root.addView(Button(this).apply {
                text = "COPY SETTLEMENT PAYLOAD"
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("AIRNOTE settlement payload", payload))
                    Toast.makeText(this@AirnotePaymentDetailActivity, "Settlement payload copied", Toast.LENGTH_SHORT).show()
                }
            })
        }

        root.addView(TextView(this).apply {
            text = buildString {
                appendLine("TECHNICAL DETAILS")
                appendLine("Airnote ID: ${payment.id}")
                appendLine("Status: ${payment.status}")
                appendLine("Retries: ${payment.retryCount}")
                payment.counterparty?.let { appendLine("Recipient: $it") }
                payment.errorCode?.let { appendLine("Last error: $it") }
            }
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, 28, 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun statusLabel(status: PaymentStatus): String = when (status) {
        PaymentStatus.CREATED -> "Created"
        PaymentStatus.ACCEPTED_OFFLINE -> "Received offline"
        PaymentStatus.PENDING -> "Waiting to settle"
        PaymentStatus.SETTLING -> "Settling on Solana"
        PaymentStatus.SETTLED -> "Settled"
        PaymentStatus.FAILED_RETRYABLE -> "Settlement needs retry"
        PaymentStatus.REJECTED -> "Rejected"
        PaymentStatus.EXPIRED -> "Expired"
    }

    companion object {
        const val EXTRA_PAYMENT_ID = "airnote_payment_id"
    }
}
