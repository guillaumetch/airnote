package com.airnote.hardwareprobe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

class Gate04ReceiverActivity : AppCompatActivity() {
    private lateinit var walletInput: EditText
    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var scanTransferButton: Button
    private lateinit var copyAcceptedButton: Button
    private var currentRequest: AirnoteProtocol.RecipientRequest? = null
    private var acceptedPayload: String? = null

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
            text = "1. Confirm the Solana wallet that should receive USDC.\n2. Show your request QR to the sender.\n3. Scan their signed payment QR.\nInternet is not required for the handoff."
            textSize = 15f
            setPadding(0, 20, 0, 12)
        })

        walletInput = EditText(this).apply {
            hint = "Recipient Solana wallet"
            setSingleLine(true)
            setText(prefs.getString(KEY_WALLET, ""))
        }
        root.addView(walletInput)

        root.addView(Button(this).apply {
            text = "SHOW RECEIVE REQUEST"
            setOnClickListener { generateRequest() }
        })

        scanTransferButton = Button(this).apply {
            text = "SCAN SIGNED PAYMENT"
            isEnabled = false
            setOnClickListener {
                transferScanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan signed AIRNOTE payment")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                )
            }
        }
        root.addView(scanTransferButton)

        copyAcceptedButton = Button(this).apply {
            text = "COPY SETTLEMENT PAYLOAD"
            isEnabled = false
            setOnClickListener {
                val payload = acceptedPayload ?: return@setOnClickListener
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AIRNOTE accepted transfer", payload))
            }
        }
        root.addView(copyAcceptedButton)

        qr = ImageView(this).apply {
            adjustViewBounds = true
            setPadding(0, 24, 0, 24)
        }
        root.addView(qr)

        status = TextView(this).apply {
            text = "Create a receive request to begin."
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun generateRequest() {
        runCatching {
            val walletText = walletInput.text.toString().trim()
            val wallet = Base58.decode32(walletText, "recipient wallet")
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_WALLET, walletText).apply()
            val created = now()
            val request = AirnoteProtocol.RecipientRequest(
                recipientSolanaWallet = wallet,
                recipientNonce = ByteArray(32).also { SecureRandom().nextBytes(it) },
                requestCreatedAtUnixSeconds = created,
                requestExpiresAtUnixSeconds = created + Gate04Verifier.REQUEST_MAX_LIFETIME_SECONDS
            )
            currentRequest = request
            acceptedPayload = null
            copyAcceptedButton.isEnabled = false
            val encoded = Gate04Wire.encodeRequestQr(request)
            qr.setImageBitmap(QrSupport.render(encoded))
            scanTransferButton.isEnabled = true
            status.text = buildString {
                appendLine("RECEIVE REQUEST READY")
                appendLine("Wallet: $walletText")
                appendLine("Request expires: ${request.requestExpiresAtUnixSeconds}")
                appendLine()
                appendLine("Show this QR to the sender. This expiry protects handoff freshness only.")
            }
        }.onFailure { error ->
            currentRequest = null
            scanTransferButton.isEnabled = false
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
            require(!prefs.contains("accepted:$commitmentHex")) { "LOCAL DUPLICATE: this Airnote was already accepted on this receiver" }

            prefs.edit()
                .putString("accepted:$commitmentHex", scanned.trim())
                .putLong("accepted_at:$commitmentHex", now())
                .apply()
            val file = java.io.File(filesDir, "gate04-accepted-$commitmentHex.txt")
            file.writeText(scanned.trim())
            acceptedPayload = scanned.trim()
            copyAcceptedButton.isEnabled = true
            qr.setImageDrawable(null)
            status.text = buildString {
                appendLine("ACCEPTED OFFLINE ✓")
                appendLine("PENDING SOLANA SETTLEMENT")
                appendLine()
                appendLine("Amount: ${transfer.airnote.mintCore.amountBaseUnits / 1_000_000} USDC")
                appendLine("Commitment: $commitmentHex")
                appendLine("Backing certificate: VERIFIED")
                appendLine("Recipient binding: VERIFIED")
                appendLine("StrongBox P-256 signature: VERIFIED")
                appendLine("Local duplicate check: PASS")
                appendLine()
                appendLine("No network was required for acceptance.")
                appendLine("Reconnect when ready to settle on Solana.")
            }
        }.onFailure { error ->
            status.text = "PAYMENT REJECTED\n${error.message ?: error::class.java.simpleName}"
        }
    }

    private fun now(): Long = System.currentTimeMillis() / 1000L

    companion object {
        private const val PREFS = "airnote_gate04"
        private const val KEY_WALLET = "recipient_wallet"
    }
}
