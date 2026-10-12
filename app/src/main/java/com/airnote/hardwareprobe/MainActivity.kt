package com.airnote.hardwareprobe

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airnote.protocol.SenderLifetimePolicy
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

class MainActivity : AppCompatActivity() {
    private lateinit var createPreviewView: TextView
    private lateinit var activityView: TextView
    private lateinit var localAirnoteStore: LocalAirnoteStore
    private lateinit var walletActivityResultSender: ActivityResultSender
    private lateinit var skrStatusView: TextView
    private lateinit var tenUsdcButton: Button
    private lateinit var twentyFourHourButton: Button
    private var selectedAmountUsdc = 1
    private var selectedLifetimeSeconds = SenderLifetimePolicy.DEFAULT_SECONDS
    private var skrHolder = false
    private var verifiedSkrWalletBase58: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            this,
            if (granted) "Settlement alerts enabled" else "Settlement alerts remain off",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        walletActivityResultSender = ActivityResultSender(this)
        localAirnoteStore = LocalAirnoteStore(this)
        localAirnoteStore.importLegacyMintRequests(getExternalFilesDir(null)?.listFiles().orEmpty())
        title = "AIRNOTE"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 56)
        }

        root.addView(TextView(this).apply {
            text = "AIRNOTE"
            textSize = 32f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(TextView(this).apply {
            text = "Offline USDC on Solana"
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(TextView(this).apply {
            text = "Prepare value while online. Exchange with two scans offline. Settle when connectivity returns."
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 6, 0, 28)
        })

        root.addView(TextView(this).apply {
            text = "PAY OFFLINE"
            textSize = 13f
        })
        root.addView(Button(this).apply {
            text = "SEND AIRNOTE"
            setOnClickListener { startActivity(Intent(this@MainActivity, Gate04SenderActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "RECEIVE AIRNOTE"
            setOnClickListener { startActivity(Intent(this@MainActivity, Gate04ReceiverActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "ACTIVITY"
            setOnClickListener { startActivity(Intent(this@MainActivity, AirnoteHistoryActivity::class.java)) }
        })

        if (Build.VERSION.SDK_INT >= 33 && !AirnoteNotifications.canNotify(this)) {
            root.addView(Button(this).apply {
                text = "ENABLE SETTLEMENT ALERTS"
                setOnClickListener {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            })
        }

        root.addView(TextView(this).apply {
            text = "CREATE & FUND AIRNOTE"
            textSize = 20f
            setPadding(0, 30, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = "Choose value and lifetime. Your wallet approves one USDC funding transaction; AIRNOTE waits for finality and backing certification automatically."
            textSize = 14f
            setPadding(0, 0, 0, 12)
        })

        root.addView(TextView(this).apply {
            text = "SKR RESERVE+"
            textSize = 16f
            setPadding(0, 8, 0, 2)
        })
        skrStatusView = TextView(this).apply {
            text = "Standard: up to 5 USDC / 6h. Verify a wallet holding liquid SKR to unlock 10 USDC / 24h."
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        root.addView(skrStatusView)
        root.addView(Button(this).apply {
            text = "VERIFY SKR ACCESS"
            setOnClickListener { verifySkrAccess(this) }
        })

        val amountRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(1, 5, 10).forEach { amount ->
            val amountButton = Button(this).apply {
                text = if (amount == 10) "$10 • SKR+" else "$" + amount
                setOnClickListener {
                    selectedAmountUsdc = amount
                    refreshCreatePreview()
                }
            }
            if (amount == 10) tenUsdcButton = amountButton
            amountRow.addView(amountButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(amountRow)

        val lifetimeRowOne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lifetimeRowTwo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        SenderLifetimePolicy.presets.forEachIndexed { index, preset ->
            val button = Button(this).apply {
                text = if (preset.seconds == SkrAccessPolicy.RESERVE_PLUS_MAX_LIFETIME_SECONDS) {
                    "24 hours • SKR+"
                } else {
                    preset.label
                }
                setOnClickListener {
                    selectedLifetimeSeconds = preset.seconds
                    refreshCreatePreview()
                }
            }
            if (preset.seconds == SkrAccessPolicy.RESERVE_PLUS_MAX_LIFETIME_SECONDS) {
                twentyFourHourButton = button
            }
            val row = if (index < 2) lifetimeRowOne else lifetimeRowTwo
            row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(lifetimeRowOne)
        root.addView(lifetimeRowTwo)

        val customMinutes = EditText(this).apply {
            hint = "Custom minutes (5–1440)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(customMinutes)
        root.addView(Button(this).apply {
            text = "USE CUSTOM LIFETIME"
            setOnClickListener {
                val minutes = customMinutes.text.toString().toLongOrNull()
                val lifetime = runCatching {
                    requireNotNull(minutes) { "Enter a number of minutes" }
                    SenderLifetimePolicy.fromMinutes(minutes)
                }.getOrElse { error ->
                    Toast.makeText(this@MainActivity, error.message ?: "Invalid lifetime", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (lifetime.seconds > SkrAccessPolicy.STANDARD_MAX_LIFETIME_SECONDS && !skrHolder) {
                    Toast.makeText(
                        this@MainActivity,
                        "SKR Reserve+ is required for lifetimes above 6 hours.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                selectedLifetimeSeconds = lifetime.seconds
                refreshCreatePreview()
            }
        })

        createPreviewView = TextView(this).apply {
            textSize = 15f
            setPadding(0, 12, 0, 12)
        }
        root.addView(createPreviewView)

        val createAndFund = Button(this).apply {
            text = "CREATE & FUND AIRNOTE"
            setOnClickListener { createAndFund(this) }
        }
        root.addView(createAndFund)

        val continueButton = Button(this).apply {
            text = "CONTINUE LATEST AIRNOTE"
            setOnClickListener {
                val note = localAirnoteStore.latestIncomplete()
                if (note == null) {
                    Toast.makeText(this@MainActivity, "No Airnote is waiting for funding or certification.", Toast.LENGTH_LONG).show()
                } else {
                    fundOrCertify(note.noteIdHex, this)
                }
            }
        }
        root.addView(continueButton)

        root.addView(TextView(this).apply {
            text = "LOCAL AIRNOTES"
            textSize = 20f
            setPadding(0, 30, 0, 4)
        })
        activityView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 12)
        }
        root.addView(activityView)

        root.addView(Button(this).apply {
            text = "REFRESH"
            setOnClickListener { refreshLocalActivity() }
        })

        root.addView(Button(this).apply {
            text = "DEVELOPER TOOLS"
            setOnClickListener { startActivity(Intent(this@MainActivity, DeveloperToolsActivity::class.java)) }
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshSkrAccessControls()
        refreshCreatePreview()
        refreshLocalActivity()
        AirnoteReconciliationScheduler.enqueue(this)
    }

    override fun onResume() {
        super.onResume()
        if (::activityView.isInitialized) refreshLocalActivity()
        AirnoteReconciliationScheduler.enqueue(this)
    }

    private fun createAndFund(button: Button) {
        if (!SkrAccessPolicy.canCreate(selectedAmountUsdc, selectedLifetimeSeconds, skrHolder)) {
            Toast.makeText(
                this,
                SkrAccessPolicy.denialMessage(selectedAmountUsdc, selectedLifetimeSeconds),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        button.isEnabled = false
        button.text = "CREATING STRONGBOX KEY…"
        val amount = selectedAmountUsdc
        val lifetimeSeconds = selectedLifetimeSeconds

        Thread {
            val result = runCatching {
                val material = NoteOwnerKeyManager(this@MainActivity).create()
                val request = buildSenderMintRequest(material, amount, lifetimeSeconds)
                persistMintRequest(request)
                localAirnoteStore.create(
                    material = material,
                    amountUsdc = amount,
                    lifetimeSeconds = lifetimeSeconds,
                    skrAccessWalletBase58 = if (SkrAccessPolicy.requiresReservePlus(amount, lifetimeSeconds)) {
                        requireNotNull(verifiedSkrWalletBase58) { "SKR Reserve+ wallet verification is missing" }
                    } else {
                        null
                    }
                )
            }
            runOnUiThread {
                result.onSuccess { note ->
                    refreshLocalActivity()
                    button.text = "OPENING WALLET…"
                    fundOrCertify(note.noteIdHex, button)
                }.onFailure { error ->
                    button.text = "CREATE & FUND AIRNOTE"
                    button.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        "Create failed: " + (error.message ?: error::class.java.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun fundOrCertify(noteIdHex: String, button: Button) {
        val idleLabel = if (button.text == "CONTINUE LATEST AIRNOTE") {
            "CONTINUE LATEST AIRNOTE"
        } else {
            "CREATE & FUND AIRNOTE"
        }
        button.isEnabled = false
        val before = localAirnoteStore.get(noteIdHex)
        button.text = when (before?.state) {
            LocalAirnoteState.FUNDED_FINALIZED -> "CERTIFYING BACKING…"
            LocalAirnoteState.FUNDING -> "CHECKING FUNDING…"
            else -> "OPENING WALLET…"
        }

        lifecycleScope.launch {
            val result = runCatching {
                AirnoteFundingCoordinator(localAirnoteStore)
                    .createReadyOffline(noteIdHex, walletActivityResultSender)
            }

            result.onSuccess { ready ->
                Toast.makeText(
                    this@MainActivity,
                    ready.note.amountUsdc.toString() + " USDC AIRNOTE READY OFFLINE",
                    Toast.LENGTH_LONG
                ).show()
                activityView.text = buildString {
                    appendLine("READY OFFLINE  ✓")
                    appendLine(ready.note.amountUsdc.toString() + " USDC")
                    appendLine("Note " + ready.note.noteIdHex.take(12) + "…")
                    appendLine("Funding finalized at slot " + ready.finalizedSlot)
                    append("Open SEND AIRNOTE — no certificate file import is needed.")
                }
            }.onFailure { error ->
                val current = localAirnoteStore.get(noteIdHex)
                val message = when (error) {
                    is AirnoteFundingCoordinator.CertificationPendingException ->
                        "Funding is FINALIZED. Certification is pending; retry will not fund again."
                    else -> error.message ?: error::class.java.simpleName
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                if (current != null) {
                    activityView.text = buildString {
                        appendLine(current.amountUsdc.toString() + " USDC • " + current.state)
                        appendLine("Note " + current.noteIdHex.take(12) + "…")
                        current.fundingSignature?.let { appendLine("Funding tx: " + it) }
                        current.lastError?.let { append("Last status: " + it) }
                    }
                }
            }

            button.text = idleLabel
            button.isEnabled = true
            refreshLocalActivity()
        }
    }

    private fun verifySkrAccess(button: Button) {
        button.isEnabled = false
        button.text = "CHECKING SKR…"
        skrStatusView.text = "Opening wallet, then checking the official SKR mint on Solana mainnet…"

        lifecycleScope.launch {
            val result = runCatching {
                when (val walletResult = AirnoteWalletAdapterFactory.create().connect(walletActivityResultSender)) {
                    is TransactionResult.Success -> {
                        val account = walletResult.authResult.accounts.firstOrNull()
                            ?: error("wallet authorization returned no account")
                        SkrAccessVerifier().check(account.publicKey)
                    }
                    is TransactionResult.NoWalletFound -> error(walletResult.message)
                    is TransactionResult.Failure -> error(walletResult.message)
                }
            }

            result.onSuccess { access ->
                skrHolder = access.isHolder
                verifiedSkrWalletBase58 = access.walletBase58.takeIf { access.isHolder }
                skrStatusView.text = if (access.isHolder) {
                    buildString {
                        appendLine("SKR HOLDER  ✓  •  RESERVE+ UNLOCKED")
                        appendLine(access.displayBalance + " liquid SKR")
                        append("Wallet " + access.walletBase58.take(6) + "…" + access.walletBase58.takeLast(6))
                    }
                } else {
                    "No liquid SKR found for this wallet. Standard access remains 5 USDC / 6h."
                }
            }.onFailure { error ->
                skrHolder = false
                verifiedSkrWalletBase58 = null
                skrStatusView.text = "SKR CHECK FAILED\n" + (error.message ?: error::class.java.simpleName)
            }

            refreshSkrAccessControls()
            refreshCreatePreview()
            button.text = "VERIFY SKR ACCESS"
            button.isEnabled = true
        }
    }

    private fun refreshSkrAccessControls() {
        if (::tenUsdcButton.isInitialized) tenUsdcButton.isEnabled = skrHolder
        if (::twentyFourHourButton.isInitialized) twentyFourHourButton.isEnabled = skrHolder

        if (!skrHolder) {
            if (selectedAmountUsdc > SkrAccessPolicy.STANDARD_MAX_AMOUNT_USDC) {
                selectedAmountUsdc = SkrAccessPolicy.STANDARD_MAX_AMOUNT_USDC
            }
            if (selectedLifetimeSeconds > SkrAccessPolicy.STANDARD_MAX_LIFETIME_SECONDS) {
                selectedLifetimeSeconds = SkrAccessPolicy.STANDARD_MAX_LIFETIME_SECONDS
            }
        }
    }

    private fun refreshCreatePreview() {
        if (!::createPreviewView.isInitialized) return
        val lifetime = SenderLifetimePolicy.lifetime(selectedLifetimeSeconds)
        createPreviewView.text = buildString {
            val reservePlus = SkrAccessPolicy.requiresReservePlus(selectedAmountUsdc, lifetime.seconds)
            appendLine(selectedAmountUsdc.toString() + " USDC • " + lifetime.label)
            appendLine("Offline exposure: " + lifetime.exposureLabel)
            appendLine(
                if (reservePlus) {
                    if (skrHolder) "SKR Reserve+ access: UNLOCKED ✓" else "SKR Reserve+ access: REQUIRED"
                } else {
                    "Access tier: STANDARD"
                }
            )
            appendLine("Estimated expiry: " + Instant.now().plusSeconds(lifetime.seconds))
            append("Creation/funding requires connectivity. The two-scan handoff does not.")
        }
    }

    private fun refreshLocalActivity() {
        if (!::activityView.isInitialized) return

        val notes = localAirnoteStore.all()
        val prepared = notes.count { it.state == LocalAirnoteState.PREPARED }
        val funding = notes.count { it.state == LocalAirnoteState.FUNDING }
        val certificationPending = notes.count { it.state == LocalAirnoteState.FUNDED_FINALIZED }
        val ready = notes.count { it.state == LocalAirnoteState.READY_OFFLINE }
        val handedOff = notes.count { it.state == LocalAirnoteState.HANDOFF_AUTHORIZED }

        val paymentStore = AirnotePaymentStore(this)
        paymentStore.expireDue()
        val payments = paymentStore.all()
        val pendingSettlement = payments.count {
            it.status in setOf(
                PaymentStatus.ACCEPTED_OFFLINE,
                PaymentStatus.PENDING,
                PaymentStatus.SETTLING,
                PaymentStatus.FAILED_RETRYABLE
            )
        }
        val settled = payments.count { it.status == PaymentStatus.SETTLED }

        activityView.text = buildString {
            appendLine("Ready offline: " + ready)
            appendLine("Prepared: " + prepared)
            appendLine("Funding / reconciling: " + funding)
            appendLine("Certification pending: " + certificationPending)
            appendLine("Handoff authorized: " + handedOff)
            appendLine()
            appendLine("Received waiting to settle: " + pendingSettlement)
            appendLine("Settled: " + settled)
            notes.firstOrNull()?.let {
                appendLine()
                appendLine("Latest: " + it.amountUsdc + " USDC • " + it.state)
                append("Note " + it.noteIdHex.take(12) + "…")
            }
        }
    }

    private fun buildSenderMintRequest(
        material: NoteOwnerKeyManager.Material,
        amountUsdc: Int,
        lifetimeSeconds: Long
    ): String {
        val lifetime = SenderLifetimePolicy.lifetime(lifetimeSeconds)
        val createdAt = Instant.now()
        return buildString {
            appendLine("AIRNOTE — SENDER MINT REQUEST")
            appendLine()
            appendLine("VERSION=1")
            appendLine("CREATED_AT=" + createdAt)
            appendLine("AMOUNT_USDC=" + amountUsdc)
            appendLine("LIFETIME_SECONDS=" + lifetime.seconds)
            appendLine("LIFETIME_LABEL=" + lifetime.label)
            appendLine("OFFLINE_EXPOSURE=" + lifetime.exposureLabel)
            appendLine("ESTIMATED_EXPIRES_AT=" + createdAt.plusSeconds(lifetime.seconds))
            appendLine("MINT_LIFETIME_ARGUMENT=--lifetime-seconds " + lifetime.seconds)
            appendLine()
            append(material.report())
            appendLine()
            appendLine("MINT_STATUS=STRONGBOX_KEY_READY_FUNDING_REQUIRED")
            appendLine("Final expiry is derived from Solana chain time when funding succeeds.")
        }
    }

    private fun persistMintRequest(request: String): File {
        val noteId = Regex("NOTE_ID_HEX=([0-9a-f]{64})")
            .find(request)?.groupValues?.get(1) ?: "unknown"
        return File(getExternalFilesDir(null), "airnote-mint-request-" + noteId + ".txt").apply {
            writeText(request)
        }
    }
}
