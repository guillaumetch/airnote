package com.airnote.hardwareprobe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airnote.protocol.AirnoteProtocol
import com.airnote.protocol.Base58
import com.airnote.protocol.Gate04Verifier
import com.airnote.protocol.Gate04Wire
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.UUID

class Gate04ReceiverActivity : AppCompatActivity() {
    private lateinit var walletBindingStore: AirnoteReceiveWalletBindingStore
    private lateinit var handoffStore: AirnoteReceiveHandoffStore
    private lateinit var walletActivityResultSender: ActivityResultSender
    private lateinit var walletStatus: TextView
    private lateinit var selectWalletButton: Button
    private lateinit var requestButton: Button
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
        walletActivityResultSender = ActivityResultSender(this)
        title = "AIRNOTE — Receive"
        walletBindingStore = AirnoteReceiveWalletBindingStore(this)
        handoffStore = AirnoteReceiveHandoffStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AirnoteUi.dp(this@Gate04ReceiverActivity, 24), AirnoteUi.dp(this@Gate04ReceiverActivity, 28), AirnoteUi.dp(this@Gate04ReceiverActivity, 24), AirnoteUi.dp(this@Gate04ReceiverActivity, 48))
            setBackgroundColor(AirnoteUi.color(AirnoteUi.SKY_50))
        }
        AirnoteUi.applyTopInset(root)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(AirnoteUi.body(this, "‹", 28f).apply {
            setTextColor(AirnoteUi.color(AirnoteUi.AIR_DEEP))
            setPadding(0, 0, AirnoteUi.dp(this@Gate04ReceiverActivity, 8), 0)
            setOnClickListener { finish() }
        })
        top.addView(AirnoteUi.title(this, "Receive", 26f))
        root.addView(top)
        root.addView(AirnoteUi.body(this, "Create your request, then scan the signed payment.", 13f), AirnoteUi.marginParams(this, top = 3, bottom = 12))

        val steps = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        steps.addView(AirnoteUi.statusPill(this, "1  Show request", false), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        steps.addView(AirnoteUi.body(this, "  →  ", 13f))
        steps.addView(AirnoteUi.statusPill(this, "2  Scan payment", false), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(steps, AirnoteUi.marginParams(this, bottom = 14))

        val receiveCard = AirnoteUi.glassCard(this, 16)
        val receiveHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        receiveHeader.addView(AirnoteUi.iconBubble(this, "⌗", 42))
        val receiveCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        receiveCopy.addView(AirnoteUi.title(this, "Receive", 17f))
        receiveCopy.addView(AirnoteUi.body(this, "Show your code to receive USDC.", 11.5f))
        receiveHeader.addView(receiveCopy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = AirnoteUi.dp(this@Gate04ReceiverActivity, 10)
        })
        receiveCard.addView(receiveHeader)

        phase = AirnoteUi.statusPill(this, "Scan 1 of 2 · Create your request", false)
        receiveCard.addView(phase, AirnoteUi.marginParams(this, top = 12, bottom = 12))

        walletStatus = AirnoteUi.body(this, "", 13f).apply {
            setTextIsSelectable(true)
            setPadding(AirnoteUi.dp(this@Gate04ReceiverActivity, 16), AirnoteUi.dp(this@Gate04ReceiverActivity, 16), AirnoteUi.dp(this@Gate04ReceiverActivity, 16), AirnoteUi.dp(this@Gate04ReceiverActivity, 16))
            background = AirnoteUi.rounded(this@Gate04ReceiverActivity, "#F8FCFF", 20, "#DDE7F0")
        }
        receiveCard.addView(walletStatus)

        selectWalletButton = AirnoteUi.secondaryButton(this, "Select receive wallet").apply {
            setOnClickListener { connectReceiveWallet() }
        }
        receiveCard.addView(selectWalletButton, AirnoteUi.marginParams(this, top = 8))

        requestButton = AirnoteUi.primaryButton(this, "Show request QR · 1 of 2").apply {
            isEnabled = false
            alpha = 0.38f
            setOnClickListener { generateRequest() }
        }
        receiveCard.addView(requestButton, AirnoteUi.marginParams(this, top = 8))

        qr = ImageView(this).apply {
            adjustViewBounds = true
            setPadding(0, 24, 0, 24)
        }
        receiveCard.addView(qr)

        scanTransferButton = AirnoteUi.primaryButton(this, "Scan payment · 2 of 2").apply {
            isEnabled = false
            alpha = 0.38f
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
        receiveCard.addView(scanTransferButton, AirnoteUi.marginParams(this, top = 8))

        openPaymentButton = AirnoteUi.primaryButton(this, "Open payment / Settle").apply {
            isEnabled = false
            alpha = 0.38f
            setOnClickListener {
                val id = acceptedPaymentId ?: return@setOnClickListener
                startActivity(
                    Intent(this@Gate04ReceiverActivity, AirnotePaymentDetailActivity::class.java)
                        .putExtra(AirnotePaymentDetailActivity.EXTRA_PAYMENT_ID, id)
                )
            }
        }
        receiveCard.addView(openPaymentButton, AirnoteUi.marginParams(this, top = 8))

        receiveCard.addView(AirnoteUi.compactButton(this, "View activity").apply {
            setOnClickListener { startActivity(Intent(this@Gate04ReceiverActivity, AirnoteHistoryActivity::class.java)) }
        })

        status = AirnoteUi.body(this, "", 14f).apply {
            setTextIsSelectable(true)
            setPadding(AirnoteUi.dp(this@Gate04ReceiverActivity, 16), AirnoteUi.dp(this@Gate04ReceiverActivity, 16), AirnoteUi.dp(this@Gate04ReceiverActivity, 16), AirnoteUi.dp(this@Gate04ReceiverActivity, 16))
            background = AirnoteUi.rounded(this@Gate04ReceiverActivity, AirnoteUi.CLOUD, 20, AirnoteUi.MIST)
        }
        receiveCard.addView(status, AirnoteUi.marginParams(this, top = 8))
        root.addView(receiveCard)

        copyAcceptedButton = AirnoteUi.compactButton(this, "Technical · Copy settlement payload").apply {
            isEnabled = false
            alpha = 0.38f
            setOnClickListener {
                val payload = acceptedPayload ?: return@setOnClickListener
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AIRNOTE accepted transfer", payload))
            }
        }
        copyAcceptedButton.visibility = android.view.View.GONE

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(AirnoteUi.color(AirnoteUi.SKY_50))
            addView(root)
        }
        setContentView(
            AirnoteBottomNav.attach(
                this,
                scroll,
                AirnoteBottomNav.Tab.RECEIVE
            )
        )
        restoreActiveRequest()
        renderWalletBinding()
    }

    private fun connectReceiveWallet() {
        if (currentRequest != null && acceptedPaymentId == null) {
            status.text = "FINISH CURRENT HANDOFF\nScan the sender's payment QR before changing the receive wallet."
            return
        }

        selectWalletButton.isEnabled = false
        requestButton.isEnabled = false
        status.text = "Opening a Solana wallet to choose the address that will receive settled USDC…"

        lifecycleScope.launch {
            val result = runCatching {
                AirnoteWalletAdapterFactory.create().connect(walletActivityResultSender)
            }

            result.onSuccess { walletResult ->
                when (walletResult) {
                    is TransactionResult.Success -> {
                        val account = walletResult.authResult.accounts.firstOrNull()
                        if (account == null) {
                            status.text = "WALLET NOT BOUND\nThe wallet returned no authorized account."
                        } else {
                            val binding = walletBindingStore.save(account.publicKey, null)
                            runCatching {
                                AirnoteProofJournal(this@Gate04ReceiverActivity).record(
                                    type = AirnoteProofEventType.RECEIVE_WALLET_BOUND,
                                    detail = "recipient=${binding.publicKeyBase58}; source=MWA"
                                )
                            }
                            renderWalletBinding()
                            status.text = buildString {
                                appendLine("RECEIVE WALLET BOUND  ✓")
                                appendLine(binding.publicKeyBase58)
                                appendLine()
                                appendLine("Only this public key is cached by AIRNOTE.")
                                append("You can now disconnect from the internet and create Scan 1 later.")
                            }
                        }
                    }

                    is TransactionResult.NoWalletFound -> {
                        status.text = "NO COMPATIBLE WALLET\n${walletResult.message}"
                    }

                    is TransactionResult.Failure -> {
                        status.text = "WALLET CONNECTION FAILED\n${walletResult.message}"
                    }
                }
            }.onFailure { error ->
                status.text = "WALLET CONNECTION FAILED\n${error.message ?: error::class.java.simpleName}"
            }

            selectWalletButton.isEnabled = true
            requestButton.isEnabled = walletBindingStore.current() != null
            requestButton.alpha = if (requestButton.isEnabled) 1f else 0.38f
        }
    }

    private fun renderWalletBinding() {
        val binding = walletBindingStore.current()
        val handoffActive = currentRequest != null && acceptedPaymentId == null
        val paymentAccepted = acceptedPaymentId != null
        val controls = ReceiveControlPolicy.derive(
            hasWalletBinding = binding != null,
            hasActiveRequest = handoffActive,
            hasAcceptedPayment = paymentAccepted
        )

        selectWalletButton.isEnabled = controls.selectWalletEnabled
        requestButton.isEnabled = controls.requestEnabled
        requestButton.alpha = if (controls.requestEnabled) 1f else 0.38f
        requestButton.text = controls.requestLabel

        if (binding == null) {
            walletStatus.text = buildString {
                appendLine("RECEIVE WALLET NOT SET")
                appendLine("Choose an MWA-compatible Solana wallet once before receiving offline.")
                append("AIRNOTE stores only the selected public key.")
            }
            selectWalletButton.text = "SELECT RECEIVE WALLET"
            if (currentRequest == null && !paymentAccepted) {
                status.text = "Select the wallet that should receive USDC after settlement."
            }
        } else {
            walletStatus.text = buildString {
                appendLine("OFFLINE RECEIVE READY  ✓")
                appendLine(binding.publicKeyBase58.take(6) + "…" + binding.publicKeyBase58.takeLast(6))
                appendLine()
                append("Wallet binding is cached locally. You can disconnect and complete both scans offline.")
            }
            selectWalletButton.text = "CHANGE RECEIVE WALLET"
            if (!handoffActive && !paymentAccepted) {
                status.text = "Ready for Scan 1. No internet is required for the handoff."
            }
        }
    }

    private fun generateRequest() {
        runCatching {
            val binding = walletBindingStore.current() ?: error("select a receive wallet first")
            val wallet = Base58.decode32(binding.publicKeyBase58, "recipient wallet")
            val created = now()
            val sessionId = UUID.randomUUID().toString()
            val request = AirnoteProtocol.RecipientRequest(
                recipientSolanaWallet = wallet,
                recipientNonce = ByteArray(32).also { SecureRandom().nextBytes(it) },
                requestCreatedAtUnixSeconds = created,
                requestExpiresAtUnixSeconds = created + Gate04Verifier.REQUEST_MAX_LIFETIME_SECONDS
            )
            val encoded = Gate04Wire.encodeRequestQr(request)
            handoffStore.save(sessionId, encoded)
            currentRequest = request
            handoffSessionId = sessionId
            acceptedPayload = null
            acceptedPaymentId = null
            copyAcceptedButton.isEnabled = false
            openPaymentButton.isEnabled = false
            selectWalletButton.isEnabled = false
            requestButton.isEnabled = false
            qr.setImageBitmap(QrSupport.render(encoded))
            scanTransferButton.isEnabled = true
            scanTransferButton.alpha = 1f
            runCatching {
                AirnoteProofJournal(this).record(
                    type = AirnoteProofEventType.RECEIVER_REQUEST_QR_READY,
                    handoffSessionId = sessionId,
                    detail = "recipient=${binding.publicKeyBase58}; source=cached_mwa_binding; request_ttl=${Gate04Verifier.REQUEST_MAX_LIFETIME_SECONDS}s"
                )
            }
            phase.text = "SCAN 1 OF 2  •  SHOW THIS QR"
            status.text = buildString {
                appendLine("REQUEST READY")
                appendLine()
                appendLine("Show this QR to the sender.")
                appendLine("They scan it to bind the Airnote to your selected wallet:")
                appendLine(binding.publicKeyBase58)
                appendLine()
                appendLine("Then tap SCAN PAYMENT — 2 OF 2 and scan the QR they show back to you.")
                append("No wallet session or internet connection is required for either scan.")
            }
        }.onFailure { error ->
            handoffStore.clear()
            currentRequest = null
            handoffSessionId = null
            scanTransferButton.isEnabled = false
            scanTransferButton.alpha = 0.38f
            selectWalletButton.isEnabled = true
            requestButton.isEnabled = walletBindingStore.current() != null
            phase.text = "SCAN 1 OF 2  •  CREATE YOUR REQUEST"
            status.text = "REQUEST FAILED\n${error.message ?: error::class.java.simpleName}"
        }
    }

    private fun restoreActiveRequest() {
        val persisted = handoffStore.current(now()) ?: return
        val binding = walletBindingStore.current() ?: run {
            handoffStore.clear()
            return
        }

        val request = runCatching { Gate04Wire.decodeRequestQr(persisted.requestQr) }.getOrElse {
            handoffStore.clear()
            return
        }
        val requestRecipient = Base58.encode(request.recipientSolanaWallet)
        if (requestRecipient != binding.publicKeyBase58) {
            handoffStore.clear()
            return
        }

        currentRequest = request
        handoffSessionId = persisted.sessionId
        acceptedPayload = null
        acceptedPaymentId = null
        copyAcceptedButton.isEnabled = false
        openPaymentButton.isEnabled = false
        selectWalletButton.isEnabled = false
        requestButton.isEnabled = false
        scanTransferButton.isEnabled = true
        qr.setImageBitmap(QrSupport.render(persisted.requestQr))
        phase.text = "SCAN 1 OF 2  •  ACTIVE REQUEST RESTORED"
        status.text = buildString {
            appendLine("REQUEST RESTORED  ✓")
            appendLine()
            appendLine("This is the exact same receiver request and nonce created earlier.")
            appendLine("Show this QR to the sender, then return here and scan the signed payment.")
            appendLine()
            append("Expires at unix ${request.requestExpiresAtUnixSeconds}.")
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
            val requestRecipient = Base58.encode(expected.recipientSolanaWallet)

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
                    counterparty = requestRecipient,
                    protocolPayload = scanned.trim()
                )
            }

            runCatching {
                AirnoteProofJournal(this).record(
                    type = AirnoteProofEventType.OFFLINE_TRANSFER_ACCEPTED,
                    handoffSessionId = handoffSessionId,
                    paymentId = commitmentHex,
                    detail = "recipient=$requestRecipient; certificate, recipient binding, P-256 authorization, and duplicate guard verified; amount_base_units=${transfer.airnote.mintCore.amountBaseUnits}"
                )
            }

            handoffStore.clear()
            currentRequest = null
            handoffSessionId = null
            acceptedPayload = scanned.trim()
            acceptedPaymentId = commitmentHex
            copyAcceptedButton.isEnabled = true
            copyAcceptedButton.alpha = 1f
            openPaymentButton.isEnabled = true
            openPaymentButton.alpha = 1f
            scanTransferButton.isEnabled = false
            scanTransferButton.alpha = 0.38f
            qr.setImageDrawable(null)
            phase.text = "OFFLINE HANDOFF COMPLETE  ✓"
            renderWalletBinding()
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
    }
}
