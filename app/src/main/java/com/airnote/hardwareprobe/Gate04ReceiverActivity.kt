package com.airnote.hardwareprobe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.airnote.protocol.AirnoteProtocol
import com.airnote.protocol.Base58
import com.airnote.protocol.Gate04Verifier
import com.airnote.protocol.Gate04Wire
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.security.SecureRandom
import java.util.UUID

class Gate04ReceiverActivity : AppCompatActivity() {
    private lateinit var walletInput: EditText
    private lateinit var phase: TextView
    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var scanTransferButton: Button
    private lateinit var openPaymentButton: Button
    private lateinit var copyAcceptedButton: Button
    private var currentRequest: AirnoteProtocol.RecipientRequest? = null
    private var handoffSessionId: String? = null
    private var acceptedPayload: String? = null
    private var acceptedPaymentId: String? = null

    private val transferScanner = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        handleTransfer(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AIRNOTE — Receive"

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
        }
        root.addView(TextView(this).apply {
            text = "RECEIVE AIRNOTE"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(TextView(this).apply {
            text = "Two scans. No internet required."
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 4, 0, 24)
        })

        phase = TextView(this).apply {
            text = "SCAN 1 OF 2  •  CREATE YOUR REQUEST"
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 8, 0, 20)
        }
        root.addView(phase)

        walletInput = EditText(this).apply {
            hint = "Solana wallet that will receive USDC"
            setSingleLine(true)
            setText(prefs.getString(KEY_WALLET, ""))
        }
        root.addView(walletInput)

        root.addView(Button(this).apply {
            text = "SHOW REQUEST QR — 1 OF 2"
            setOnClickListener { generateRequest() }
        })

        qr = ImageView(this).apply {
            adjustViewBounds = true
            setPadding(0, 24, 0, 24)
        }
        root.addView(qr)

        scanTransferButton = Button(this).apply {
            text = "SCAN PAYMENT — 2 OF 2"
            isEnabled = false
            setOnClickListener {
                transferScanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan 2 of 2 • signed Airnote payment")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                )
            }
        }
        root.addView(scanTransferButton)

        openPaymentButton = Button(this).apply {
            text = "OPEN PAYMENT / SETTLE"
            isEnabled = false
            setOnClickListener {
                val id = acceptedPaymentId ?: return@setOnClickListener
                startActivity(
                    Intent(this@Gate04ReceiverActivity, AirnotePaymentDetailActivity::class.java)
                        .putExtra(AirnotePaymentDetailActivity.EXTRA_PAYMENT_ID, id)
                )
            }
        }
        root.addView(openPaymentButton)

        root.addView(Button(this).apply {
            text = "VIEW ALL ACTIVITY"
            setOnClickListener { startActivity(Intent(this@Gate04ReceiverActivity, AirnoteHistoryActivity::class.java)) }
        })

        status = TextView(this).apply {
            text = "Enter your Solana wallet and create the first QR."
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, 12, 0, 12)
        }
        root.addView(status)

        copyAcceptedButton = Button(this).apply {
            text = "TECHNICAL • COPY SETTLEMENT PAYLOAD"
            isEnabled = false
            setOnClickListener {
                val payload = acceptedPayload ?: return@setOnClickListener
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AIRNOTE accepted transfer", payload))
            }
        }
        root.addView(copyAcceptedButton)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun generateRequest() {
        runCatching {
            val walletText = walletInput.text.toString().trim()
            val wallet = Base58.decode32(walletText, "recipient wallet")
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_WALLET, walletText).apply()
            val created = now()
            val sessionId = UUID.randomUUID().toString()
            val request = AirnoteProtocol.RecipientRequest(
                recipientSolanaWallet = wallet,
                recipientNonce = ByteArray(32).also { SecureRandom().nextBytes(it) },
                requestCreatedAtUnixSeconds = created,
                requestExpiresAtUnixSeconds = created + Gate04Verifier.REQUEST_MAX_LIFETIME_SECONDS
            )
            currentRequest = request
            handoffSessionId = sessionId
            acceptedPayload = null
            acceptedPaymentId = null
            copyAcceptedButton.isEnabled = false
            openPaymentButton.isEnabled = false
            val encoded = Gate04Wire.encodeRequestQr(request)
            qr.setImageBitmap(QrSupport.render(encoded))
            scanTransferButton.isEnabled = true
            runCatching {
                AirnoteProofJournal(this).record(
                    type = AirnoteProofEventType.RECEIVER_REQUEST_QR_READY,
                    handoffSessionId = sessionId,
                    detail = "Receiver request QR created; request TTL=${Gate04Verifier.REQUEST_MAX_LIFETIME_SECONDS}s"
                )
            }
            phase.text = "SCAN 1 OF 2  •  SHOW THIS QR"
            status.text = buildString {
                appendLine("REQUEST READY")
                appendLine()
                appendLine("Show this QR to the sender.")
                appendLine("They scan it to bind the Airnote to this wallet:")
                appendLine(walletText)
                appendLine()
                appendLine("Then tap SCAN PAYMENT — 2 OF 2 and scan the QR they show back to you.")
                append("Internet is not required for either scan.")
            }
        }.onFailure { error ->
            currentRequest = null
            handoffSessionId = null
            scanTransferButton.isEnabled = false
            phase.text = "SCAN 1 OF 2  •  CREATE YOUR REQUEST"
            status.text = "REQUEST FAILED\n${error.message ?: error::class.java.simpleName}"
        }
    }

    private fun handleTransfer(scanned: String) {
        runCatching {
            require(Build.VERSION.SDK_INT >= 33) { "AIRNOTE requires Android API 33+ for Ed25519 verification" }
            val expected = currentRequest ?: error("create a receive request first")
            val transfer = Gate04Wire.decodeTransferQr(scanned.trim())
            val check = Gate04Verifier.verifyOfflineTransfer(transfer, expected, now())
            require(check.accepted) { check.reason }
            val commitment = check.noteCommitment ?: error("verified transfer missing commitment")
            val commitmentHex = commitment.hex()
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val store = AirnotePaymentStore(this)

            val existing = store.get(commitmentHex)
            if (existing == null) {
                require(!prefs.contains("accepted:$commitmentHex")) { "LOCAL DUPLICATE: this Airnote was already accepted on this receiver" }
                prefs.edit()
                    .putString("accepted:$commitmentHex", scanned.trim())
                    .putLong("accepted_at:$commitmentHex", now())
                    .apply()
                java.io.File(filesDir, "gate04-accepted-$commitmentHex.txt").writeText(scanned.trim())
                store.recordReceived(
                    id = commitmentHex,
                    amountBaseUnits = transfer.airnote.mintCore.amountBaseUnits,
                    createdAtUnixSeconds = now(),
                    expiresAtUnixSeconds = transfer.airnote.mintCore.expiresAtUnixSeconds,
                    counterparty = walletInput.text.toString().trim(),
                    protocolPayload = scanned.trim()
                )
            }

            runCatching {
                AirnoteProofJournal(this).record(
                    type = AirnoteProofEventType.OFFLINE_TRANSFER_ACCEPTED,
                    handoffSessionId = handoffSessionId,
                    paymentId = commitmentHex,
                    detail = "Certificate, recipient binding, P-256 authorization, and duplicate guard verified; amount_base_units=${transfer.airnote.mintCore.amountBaseUnits}"
                )
            }

            acceptedPayload = scanned.trim()
            acceptedPaymentId = commitmentHex
            copyAcceptedButton.isEnabled = true
            openPaymentButton.isEnabled = true
            scanTransferButton.isEnabled = false
            qr.setImageDrawable(null)
            phase.text = "OFFLINE HANDOFF COMPLETE  ✓"
            status.text = buildString {
                appendLine(if (existing == null) "AIRNOTE RECEIVED" else "AIRNOTE ALREADY RECEIVED")
                appendLine("${transfer.airnote.mintCore.amountBaseUnits / 1_000_000.0} USDC")
                appendLine()
                appendLine("Backing certificate verified.")
                appendLine("Recipient binding verified.")
                appendLine("StrongBox P-256 authorization verified.")
                appendLine("Duplicate protection passed.")
                appendLine()
                appendLine("No network was required for acceptance.")
                appendLine()
                append("When you are online, open this payment and settle it on Solana.")
            }
        }.onFailure { error ->
            phase.text = "PAYMENT NOT ACCEPTED"
            status.text = "PAYMENT REJECTED\n${error.message ?: error::class.java.simpleName}"
        }
    }

    private fun now(): Long = System.currentTimeMillis() / 1000L

    companion object {
        private const val PREFS = "airnote_gate04"
        private const val KEY_WALLET = "recipient_wallet"
    }
}
