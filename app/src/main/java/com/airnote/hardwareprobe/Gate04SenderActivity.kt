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
    private lateinit var phase: TextView
    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var scanRequestButton: Button
    private lateinit var copyTransferButton: Button
    private lateinit var localNotesContainer: LinearLayout
    private lateinit var localAirnoteStore: LocalAirnoteStore
    private var airnote: Gate04Wire.CertifiedAirnote? = null
    private var transferQr: String? = null

    private val openCertificate = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("could not read certificate file")
            loadCertifiedAirnote(Gate04CertificateJson.parse(json))
        }.onFailure(::renderImportFailure)
    }

    private val requestScanner = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        handleRecipientRequest(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AIRNOTE — Send"
        localAirnoteStore = LocalAirnoteStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AirnoteUi.dp(this@Gate04SenderActivity, 24), AirnoteUi.dp(this@Gate04SenderActivity, 28), AirnoteUi.dp(this@Gate04SenderActivity, 24), AirnoteUi.dp(this@Gate04SenderActivity, 48))
            setBackgroundColor(AirnoteUi.color(AirnoteUi.SKY_50))
        }
        root.addView(AirnoteUi.sectionLabel(this, "Send Airnote"))
        root.addView(AirnoteUi.title(this, "Two scans. No internet required.", 24f), AirnoteUi.marginParams(this, top = 4, bottom = 16))

        phase = AirnoteUi.statusPill(this, "CHOOSE A READY AIRNOTE", false)
        root.addView(phase, AirnoteUi.marginParams(this, bottom = 16))

        root.addView(AirnoteUi.sectionLabel(this, "Your offline Airnotes"))
        localNotesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 12)
        }
        root.addView(localNotesContainer)

        root.addView(AirnoteUi.secondaryButton(this, "Import certificate · recovery").apply {
            setOnClickListener { openCertificate.launch(arrayOf("application/json", "text/plain", "*/*")) }
        }, AirnoteUi.marginParams(this, top = 8, bottom = 8))

        scanRequestButton = AirnoteUi.primaryButton(this, "Scan request · 1 of 2").apply {
            isEnabled = false
            alpha = 0.38f
            setOnClickListener {
                requestScanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan 1 of 2 • receiver request")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                )
            }
        }
        root.addView(scanRequestButton)

        qr = ImageView(this).apply {
            adjustViewBounds = true
            setPadding(0, 24, 0, 24)
        }
        root.addView(qr)

        status = AirnoteUi.body(this, "Choose a READY OFFLINE Airnote. File import is recovery-only.", 14f).apply {
            setTextIsSelectable(true)
            setPadding(AirnoteUi.dp(this@Gate04SenderActivity, 16), AirnoteUi.dp(this@Gate04SenderActivity, 16), AirnoteUi.dp(this@Gate04SenderActivity, 16), AirnoteUi.dp(this@Gate04SenderActivity, 16))
            background = AirnoteUi.rounded(this@Gate04SenderActivity, AirnoteUi.CLOUD, 20, AirnoteUi.MIST)
        }
        root.addView(status)

        copyTransferButton = AirnoteUi.compactButton(this, "Technical · Copy signed payload").apply {
            isEnabled = false
            alpha = 0.38f
            setOnClickListener {
                val payload = transferQr ?: return@setOnClickListener
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AIRNOTE transfer", payload))
            }
        }
        root.addView(copyTransferButton)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(AirnoteUi.color(AirnoteUi.SKY_50))
            addView(root)
        })
        renderLocalNotes()
    }

    override fun onResume() {
        super.onResume()
        if (::localNotesContainer.isInitialized) renderLocalNotes()
    }

    private fun renderLocalNotes() {
        localNotesContainer.removeAllViews()
        val notes = localAirnoteStore.sendable()
        if (notes.isEmpty()) {
            localNotesContainer.addView(TextView(this).apply {
                text = "No READY OFFLINE Airnotes yet. Create & fund one from the AIRNOTE home screen."
                textSize = 13f
            })
            return
        }

        notes.forEach { note ->
            localNotesContainer.addView(AirnoteUi.secondaryButton(this, note.amountUsdc.toString() + " USDC · " +
                    if (note.state == LocalAirnoteState.HANDOFF_AUTHORIZED) "HANDOFF AUTHORIZED" else "READY OFFLINE").apply {
                text = note.amountUsdc.toString() + " USDC • " +
                    if (note.state == LocalAirnoteState.HANDOFF_AUTHORIZED) "HANDOFF AUTHORIZED" else "READY OFFLINE"
                setOnClickListener {
                    runCatching {
                        val certificateJson = requireNotNull(note.certificateJson) {
                            "local Airnote is missing its backing certificate"
                        }
                        loadCertifiedAirnote(Gate04CertificateJson.parse(certificateJson))
                    }.onFailure(::renderImportFailure)
                }
            })
        }
    }

    private fun loadCertifiedAirnote(imported: Gate04Wire.CertifiedAirnote) {
        require(Build.VERSION.SDK_INT >= 33) { "AIRNOTE requires Android API 33+ for Ed25519 verification" }
        val check = Gate04Verifier.verifyCertificate(imported, now())
        require(check.accepted) { check.reason }
        val alias = aliasFor(imported.mintCore.noteId)
        require(NoteOwnerKeyManager(this).contains(alias)) {
            "matching StrongBox note-owner key is not present on this device"
        }

        airnote = imported
        val noteIdHex = imported.mintCore.noteId.hex()
        val lockedTransfer = handoffPrefs().getString(lockKey(noteIdHex), null)
        if (lockedTransfer != null) {
            val locked = Gate04Wire.decodeTransferQr(lockedTransfer)
            require(locked.airnote.mintCore.noteId.contentEquals(imported.mintCore.noteId)) {
                "stored handoff lock does not match loaded Airnote"
            }
            transferQr = lockedTransfer
            qr.setImageBitmap(QrSupport.render(lockedTransfer))
            scanRequestButton.isEnabled = false
            scanRequestButton.alpha = 0.38f
            copyTransferButton.isEnabled = true
            copyTransferButton.alpha = 1f
            phase.text = "SCAN 2 OF 2  •  SHOW LOCKED PAYMENT QR"
            status.text = buildString {
                appendLine("HANDOFF ALREADY AUTHORIZED")
                appendLine(imported.mintCore.amountBaseUnits.div(1_000_000.0).toString() + " USDC")
                appendLine()
                appendLine("This Airnote is already bound to:")
                appendLine(Base58.encode(locked.recipientRequest.recipientSolanaWallet))
                appendLine()
                appendLine("The original signed payment QR has been restored for receiver rescans.")
                append("AIRNOTE will not create a second recipient binding for this note on this device.")
            }
        } else {
            transferQr = null
            qr.setImageDrawable(null)
            scanRequestButton.isEnabled = true
            scanRequestButton.alpha = 1f
            copyTransferButton.isEnabled = false
            copyTransferButton.alpha = 0.38f
            phase.text = "SCAN 1 OF 2  •  SCAN RECEIVER REQUEST"
            status.text = buildString {
                appendLine("AIRNOTE READY OFFLINE  ✓")
                appendLine(imported.mintCore.amountBaseUnits.div(1_000_000.0).toString() + " USDC")
                appendLine()
                appendLine("Backing certificate verified.")
                appendLine("StrongBox owner key found on this Seeker.")
                appendLine()
                appendLine("Ask the receiver to show their AIRNOTE request QR, then scan it here.")
                append("Internet is not required for the two-scan handoff.")
            }
        }
    }

    private fun renderImportFailure(error: Throwable) {
        airnote = null
        transferQr = null
        qr.setImageDrawable(null)
        scanRequestButton.isEnabled = false
        scanRequestButton.alpha = 0.38f
        copyTransferButton.isEnabled = false
        copyTransferButton.alpha = 0.38f
        phase.text = "CHOOSE A READY AIRNOTE"
        status.text = "AIRNOTE LOAD FAILED\n" + (error.message ?: error::class.java.simpleName)
    }

    private fun handleRecipientRequest(scanned: String) {
        runCatching {
            check(transferQr == null) {
                "This Airnote is already locked to a receiver handoff. Load a fresh funded Airnote to start another payment."
            }
            val current = airnote ?: error("choose a READY OFFLINE Airnote first")
            val noteIdHex = current.mintCore.noteId.hex()
            check(handoffPrefs().getString(lockKey(noteIdHex), null) == null) {
                "This Airnote already has a recipient handoff lock on this device"
            }
            val request = Gate04Wire.decodeRequestQr(scanned.trim())
            val requestCheck = Gate04Verifier.validateRecipientRequest(current.mintCore, request, now())
            require(requestCheck.accepted) { requestCheck.reason }

            val commitment = AirnoteProtocol.noteCommitment(current.mintCore)
            val commitmentHex = commitment.hex()
            val transferPayload = AirnoteProtocol.encodeTransferPayload(commitment, request)
            val alias = aliasFor(current.mintCore.noteId)
            val signature = NoteOwnerKeyManager(this).signTransfer(alias, transferPayload)
            val transfer = Gate04Wire.OfflineTransfer(current, request, signature)
            val encoded = Gate04Wire.encodeTransferQr(transfer)

            handoffPrefs().edit().putString(lockKey(noteIdHex), encoded).commit().also { saved ->
                check(saved) { "could not persist sender handoff lock" }
            }

            runCatching {
                localAirnoteStore.get(noteIdHex)?.let {
                    localAirnoteStore.markHandoffAuthorized(noteIdHex)
                }
            }

            runCatching {
                AirnoteProofJournal(this).record(
                    type = AirnoteProofEventType.SENDER_SIGNED_TRANSFER_QR_READY,
                    paymentId = commitmentHex,
                    detail = "Recipient request verified; StrongBox P-256 transfer authorization created; sender handoff lock persisted"
                )
            }

            transferQr = encoded
            qr.setImageBitmap(QrSupport.render(encoded))
            copyTransferButton.isEnabled = true
            copyTransferButton.alpha = 1f
            scanRequestButton.isEnabled = false
            scanRequestButton.alpha = 0.38f
            phase.text = "SCAN 2 OF 2  •  SHOW PAYMENT QR"
            status.text = buildString {
                appendLine("SIGNED PAYMENT READY")
                appendLine(current.mintCore.amountBaseUnits.div(1_000_000.0).toString() + " USDC")
                appendLine()
                appendLine("Bound recipient: " + Base58.encode(request.recipientSolanaWallet))
                appendLine("StrongBox P-256 authorization created.")
                appendLine()
                appendLine("Show this QR to the receiver. They scan it to accept the Airnote offline.")
                appendLine()
                appendLine("HANDOFF LOCKED")
                append("The same signed QR will be restored if this Airnote is reopened on this device.")
            }
            renderLocalNotes()
        }.onFailure { error ->
            status.text = "SEND FAILED\n" + (error.message ?: error::class.java.simpleName)
        }
    }

    private fun handoffPrefs() = getSharedPreferences(HANDOFF_PREFS, MODE_PRIVATE)
    private fun lockKey(noteIdHex: String) = "handoff:" + noteIdHex
    private fun aliasFor(noteId: ByteArray): String = "airnote-note-v1-" + noteId.hex()
    private fun now(): Long = System.currentTimeMillis() / 1000L

    companion object {
        private const val HANDOFF_PREFS = "airnote_sender_handoff_locks_v1"
    }
}
