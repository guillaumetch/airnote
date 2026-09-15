package com.airnote.hardwareprobe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.airnote.protocol.AirnoteProtocol
import com.airnote.protocol.Base58
import com.airnote.protocol.Gate04CertificateJson
import com.airnote.protocol.Gate04Verifier
import com.airnote.protocol.Gate04Wire
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class Gate04SenderActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var scanRequestButton: Button
    private lateinit var copyTransferButton: Button
    private var airnote: Gate04Wire.CertifiedAirnote? = null
    private var transferQr: String? = null

    private val openCertificate = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("could not read certificate file")
            require(Build.VERSION.SDK_INT >= 33) { "AIRNOTE requires Android API 33+ for Ed25519 verification" }
            val imported = Gate04CertificateJson.parse(json)
            val check = Gate04Verifier.verifyCertificate(imported, now())
            require(check.accepted) { check.reason }
            val alias = aliasFor(imported.mintCore.noteId)
            require(NoteOwnerKeyManager(this).contains(alias)) {
                "matching StrongBox note-owner key is not present on this device"
            }
            airnote = imported
            transferQr = null
            qr.setImageDrawable(null)
            scanRequestButton.isEnabled = true
            copyTransferButton.isEnabled = false
            status.text = buildString {
                appendLine("AIRNOTE READY")
                appendLine("Backing certificate: VERIFIED")
                appendLine("StrongBox owner key: FOUND")
                appendLine("Amount: ${imported.mintCore.amountBaseUnits / 1_000_000} USDC")
                appendLine("Note: ${imported.mintCore.noteId.hex().take(16)}…")
                appendLine("Expires: ${imported.mintCore.expiresAtUnixSeconds}")
                appendLine()
                appendLine("You can go offline now. Ask the receiver to show their request QR.")
            }
        }.onFailure { error ->
            airnote = null
            scanRequestButton.isEnabled = false
            status.text = "IMPORT FAILED\n${error.message ?: error::class.java.simpleName}"
        }
    }

    private val requestScanner = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        handleRecipientRequest(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AIRNOTE — Send"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
        }
        root.addView(TextView(this).apply {
            text = "SEND AIRNOTE"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(TextView(this).apply {
            text = "1. Load the funded Airnote while online.\n2. Go offline if needed.\n3. Scan the receiver request.\n4. Show the signed payment QR back to them."
            textSize = 15f
            setPadding(0, 20, 0, 20)
        })

        root.addView(Button(this).apply {
            text = "LOAD FUNDED AIRNOTE"
            setOnClickListener { openCertificate.launch(arrayOf("application/json", "text/plain", "*/*")) }
        })

        scanRequestButton = Button(this).apply {
            text = "SCAN RECEIVER REQUEST"
            isEnabled = false
            setOnClickListener {
                requestScanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan AIRNOTE receive request")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                )
            }
        }
        root.addView(scanRequestButton)

        copyTransferButton = Button(this).apply {
            text = "COPY SIGNED PAYMENT PAYLOAD"
            isEnabled = false
            setOnClickListener {
                val payload = transferQr ?: return@setOnClickListener
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AIRNOTE transfer", payload))
            }
        }
        root.addView(copyTransferButton)

        qr = ImageView(this).apply {
            adjustViewBounds = true
            setPadding(0, 24, 0, 24)
        }
        root.addView(qr)

        status = TextView(this).apply {
            text = "Load a funded AIRNOTE to begin."
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun handleRecipientRequest(scanned: String) {
        runCatching {
            val current = airnote ?: error("load an Airnote first")
            val request = Gate04Wire.decodeRequestQr(scanned.trim())
            val requestCheck = Gate04Verifier.validateRecipientRequest(current.mintCore, request, now())
            require(requestCheck.accepted) { requestCheck.reason }

            val commitment = AirnoteProtocol.noteCommitment(current.mintCore)
            val transferPayload = AirnoteProtocol.encodeTransferPayload(commitment, request)
            val alias = aliasFor(current.mintCore.noteId)
            val signature = NoteOwnerKeyManager(this).signTransfer(alias, transferPayload)
            val transfer = Gate04Wire.OfflineTransfer(current, request, signature)
            val encoded = Gate04Wire.encodeTransferQr(transfer)
            transferQr = encoded
            qr.setImageBitmap(QrSupport.render(encoded))
            copyTransferButton.isEnabled = true
            status.text = buildString {
                appendLine("SIGNED OFFLINE")
                appendLine("Amount: ${current.mintCore.amountBaseUnits / 1_000_000} USDC")
                appendLine("Recipient: ${Base58.encode(request.recipientSolanaWallet)}")
                appendLine("Request expires: ${request.requestExpiresAtUnixSeconds}")
                appendLine("StrongBox P-256 signature: CREATED")
                appendLine()
                appendLine("Show this QR to the receiver to complete the offline handshake.")
            }
        }.onFailure { error ->
            status.text = "SEND FAILED\n${error.message ?: error::class.java.simpleName}"
        }
    }

    private fun aliasFor(noteId: ByteArray): String = "airnote-note-v1-${noteId.hex()}"
    private fun now(): Long = System.currentTimeMillis() / 1000L
}
