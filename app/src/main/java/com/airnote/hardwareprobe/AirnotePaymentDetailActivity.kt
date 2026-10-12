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
import androidx.lifecycle.lifecycleScope
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.launch
import java.time.Instant

class AirnotePaymentDetailActivity : AppCompatActivity() {
    private lateinit var walletActivityResultSender: ActivityResultSender
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        walletActivityResultSender = ActivityResultSender(this)
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
            setPadding(AirnoteUi.dp(this@AirnotePaymentDetailActivity, 24), AirnoteUi.dp(this@AirnotePaymentDetailActivity, 28), AirnoteUi.dp(this@AirnotePaymentDetailActivity, 24), AirnoteUi.dp(this@AirnotePaymentDetailActivity, 48))
            setBackgroundColor(AirnoteUi.color(AirnoteUi.SKY_50))
        }
        AirnoteUi.applyTopInset(root)
        val amount = payment.amountBaseUnits / 1_000_000.0
        root.addView(AirnoteUi.sectionLabel(this, if (payment.direction == PaymentDirection.RECEIVED) "Received Airnote" else "Sent Airnote"))
        root.addView(AirnoteUi.title(this, "${"%.2f".format(amount)} USDC", 34f), AirnoteUi.marginParams(this, top = 4))
        root.addView(
            AirnoteUi.statusPill(
                this,
                statusLabel(payment.status),
                payment.status == PaymentStatus.SETTLED || payment.status == PaymentStatus.ACCEPTED_OFFLINE
            ),
            AirnoteUi.marginParams(this, top = 8, bottom = 8)
        )
        root.addView(
            AirnoteUi.networkBadge(this),
            AirnoteUi.marginParams(this, bottom = 4)
        )
        root.addView(
            AirnoteUi.body(
                this,
                "AIRNOTE beta settles on Devnet. Switch your wallet to Solana Devnet before settlement.",
                11.5f
            ),
            AirnoteUi.marginParams(this, bottom = 16)
        )

        root.addView(AirnoteUi.body(this, "", 14f).apply {
            text = buildString {
                appendLine("Created\n${Instant.ofEpochSecond(payment.createdAtUnixSeconds)}")
                appendLine()
                if (payment.direction == PaymentDirection.RECEIVED) appendLine("Accepted offline\n${Instant.ofEpochSecond(payment.createdAtUnixSeconds)}\n")
                payment.lastSettlementAttemptUnixSeconds?.let {
                    appendLine("Settlement attempt\n${Instant.ofEpochSecond(it)}\n")
                }
                payment.settlementSignature?.let { signature ->
                    if (payment.status != PaymentStatus.SETTLED) {
                        appendLine("Submitted to Solana\n$signature\n")
                    }
                }
                if (payment.status == PaymentStatus.SETTLED) appendLine("Settled on Solana")
                if (payment.status == PaymentStatus.EXPIRED) appendLine("This Airnote expired before settlement could complete.")
                appendLine()
                appendLine("Expires\n${Instant.ofEpochSecond(payment.expiresAtUnixSeconds)}")
                if (payment.status == PaymentStatus.SETTLED) {
                    payment.settlementSignature?.let { appendLine("\nSolana transaction\n$it") }
                }
            }
            setTextIsSelectable(true)
            setPadding(AirnoteUi.dp(this@AirnotePaymentDetailActivity, 16), AirnoteUi.dp(this@AirnotePaymentDetailActivity, 16), AirnoteUi.dp(this@AirnotePaymentDetailActivity, 16), AirnoteUi.dp(this@AirnotePaymentDetailActivity, 16))
            background = AirnoteUi.rounded(this@AirnotePaymentDetailActivity, AirnoteUi.CLOUD, 20, AirnoteUi.MIST)
        })

        if (canSettle(payment)) {
            root.addView(
                AirnoteUi.devnetNotice(
                    this,
                    "Use the same recipient wallet on Solana Devnet to settle this Airnote."
                ),
                AirnoteUi.marginParams(this, top = 12, bottom = 10)
            )
            root.addView(AirnoteUi.primaryButton(this, settlementActionLabel(payment)).apply {
                setOnClickListener {
                    isEnabled = false
                    text = if (payment.settlementSignature == null) "OPENING SOLANA WALLET…" else "CHECKING SOLANA…"
                    lifecycleScope.launch {
                        val result = runCatching {
                            AirnoteSettlementCoordinator(
                                store = store,
                                proofJournal = AirnoteProofJournal(this@AirnotePaymentDetailActivity)
                            ).settle(id, walletActivityResultSender)
                        }
                        result.onSuccess { settled ->
                            Toast.makeText(
                                this@AirnotePaymentDetailActivity,
                                "Airnote settled on Solana • slot ${settled.finalizedSlot}",
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure { error ->
                            Toast.makeText(
                                this@AirnotePaymentDetailActivity,
                                settlementErrorCopy(error),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        render()
                    }
                }
            })
            root.addView(AirnoteUi.body(this, "", 13f).apply {
                text = if (payment.settlementSignature == null) {
                    "Your Solana wallet will open for AIRNOTE Devnet settlement. If your wallet is showing Mainnet, switch it to Devnet before approving. AIRNOTE never receives your wallet private key."
                } else {
                    "A transaction was already submitted. AIRNOTE will reconcile that transaction before any new submission."
                }
                textSize = 13f
                setPadding(0, 4, 0, 16)
            })
        }

        payment.protocolPayload?.let { payload ->
            root.addView(AirnoteUi.secondaryButton(this, "Copy").apply {
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("AIRNOTE settlement payload", payload))
                    Toast.makeText(this@AirnotePaymentDetailActivity, "Settlement payload copied", Toast.LENGTH_SHORT).show()
                }
            })
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun canSettle(payment: AirnotePayment): Boolean =
        payment.direction == PaymentDirection.RECEIVED &&
            payment.protocolPayload != null &&
            payment.status in setOf(PaymentStatus.PENDING, PaymentStatus.SETTLING, PaymentStatus.FAILED_RETRYABLE)

    private fun settlementActionLabel(payment: AirnotePayment): String = when {
        payment.settlementSignature != null -> "CHECK SOLANA SETTLEMENT"
        payment.status == PaymentStatus.FAILED_RETRYABLE -> "RETRY SETTLEMENT"
        else -> "SETTLE ON SOLANA"
    }

    private fun settlementErrorCopy(error: Throwable): String = when (error) {
        is AirnoteSettlementBuilder.WalletMismatchException ->
            "Choose the same Solana wallet that was bound into this Airnote when you received it."
        is AirnoteSettlementBuilder.AirnoteExpiredException ->
            "This Airnote expired before settlement could complete."
        is AirnoteSettlementCoordinator.WalletUnavailableException ->
            "No compatible Solana wallet is available on this device."
        is AirnoteSolanaRpc.FinalizationTimeoutException ->
            "Settlement was submitted but finalization is still pending. Check it again — AIRNOTE will not resubmit it."
        is AirnoteSolanaRpc.RpcException ->
            "Solana RPC is unavailable. Your Airnote remains safe to retry or reconcile."
        else -> "Settlement could not complete: ${error.message ?: error::class.java.simpleName}"
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
