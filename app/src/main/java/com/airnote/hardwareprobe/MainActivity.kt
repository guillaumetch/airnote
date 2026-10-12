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
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var createPreviewView: TextView
    private lateinit var activityView: TextView
    private lateinit var localAirnoteStore: LocalAirnoteStore
    private lateinit var walletActivityResultSender: ActivityResultSender
    private lateinit var skrStatusView: TextView
    private lateinit var tenUsdcButton: Button
    private lateinit var twentyFourHourButton: Button
    private lateinit var heroReserveAmountView: TextView
    private lateinit var heroReserveMetaView: TextView
    private lateinit var screenScroll: ScrollView
    private lateinit var createSectionAnchor: TextView
    private val amountButtons = linkedMapOf<Int, Button>()
    private val lifetimeButtons = linkedMapOf<Long, Button>()
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
            setPadding(AirnoteUi.dp(this@MainActivity, 24), AirnoteUi.dp(this@MainActivity, 28), AirnoteUi.dp(this@MainActivity, 24), AirnoteUi.dp(this@MainActivity, 56))
            setBackgroundColor(AirnoteUi.color(AirnoteUi.SKY_50))
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(AirnoteUi.title(this, "AIRNOTE", 27f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        brandRow.addView(AirnoteUi.statusPill(this, "USDC · OFFLINE", false))
        root.addView(brandRow)
        root.addView(AirnoteUi.title(this, "Money that moves with you.", 22f), AirnoteUi.marginParams(this, top = 4))
        root.addView(AirnoteUi.body(this, "Online or offline.", 13f), AirnoteUi.marginParams(this, top = 2, bottom = 14))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AirnoteUi.dp(this@MainActivity, 18), AirnoteUi.dp(this@MainActivity, 16), AirnoteUi.dp(this@MainActivity, 18), AirnoteUi.dp(this@MainActivity, 16))
            background = AirnoteUi.heroBackground(this@MainActivity)
        }
        hero.addView(AirnoteUi.sectionLabel(this, "USDC offline reserve"))
        heroReserveAmountView = AirnoteUi.title(this, "0.00 USDC", 31f)
        hero.addView(heroReserveAmountView, AirnoteUi.marginParams(this, top = 2))
        val heroState = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroState.addView(AirnoteUi.statusPill(this, "●  READY OFFLINE", true))
        heroReserveMetaView = AirnoteUi.body(this, "No Airnotes ready", 12f)
        heroState.addView(heroReserveMetaView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = AirnoteUi.dp(this@MainActivity, 10)
        })
        hero.addView(heroState, AirnoteUi.marginParams(this, top = 8))
        root.addView(hero, AirnoteUi.marginParams(this, bottom = 10))

        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }
        fun quick(label: String, primary: Boolean = false, action: () -> Unit): Button =
            (if (primary) AirnoteUi.primaryButton(this, label) else AirnoteUi.compactButton(this, label)).apply {
                textSize = 11.5f
                minHeight = AirnoteUi.dp(this@MainActivity, 58)
                setPadding(AirnoteUi.dp(this@MainActivity, 3), 0, AirnoteUi.dp(this@MainActivity, 3), 0)
                setOnClickListener { action() }
            }

        val createQuick = quick("+\nCreate", true) {
            if (::createSectionAnchor.isInitialized) {
                screenScroll.post { screenScroll.smoothScrollTo(0, createSectionAnchor.top) }
            }
        }
        val sendQuick = quick("✈\nSend") {
            startActivity(Intent(this@MainActivity, Gate04SenderActivity::class.java))
        }
        val receiveQuick = quick("⌗\nReceive") {
            startActivity(Intent(this@MainActivity, Gate04ReceiverActivity::class.java))
        }
        val activityQuick = quick("▤\nActivity") {
            startActivity(Intent(this@MainActivity, AirnoteHistoryActivity::class.java))
        }
        listOf(createQuick, sendQuick, receiveQuick, activityQuick).forEach { button ->
            quickRow.addView(button, LinearLayout.LayoutParams(0, AirnoteUi.dp(this, 60), 1f).apply {
                marginStart = AirnoteUi.dp(this@MainActivity, 2)
                marginEnd = AirnoteUi.dp(this@MainActivity, 2)
            })
        }
        root.addView(quickRow, AirnoteUi.marginParams(this, bottom = 14))

        val offlineStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AirnoteUi.dp(this@MainActivity, 14), AirnoteUi.dp(this@MainActivity, 12), AirnoteUi.dp(this@MainActivity, 14), AirnoteUi.dp(this@MainActivity, 12))
            background = AirnoteUi.rounded(this@MainActivity, AirnoteUi.CLOUD, 18, AirnoteUi.MIST)
        }
        offlineStrip.addView(AirnoteUi.title(this, "Ready Offline", 15f))
        offlineStrip.addView(AirnoteUi.body(this, "Two scans move a funded Airnote with no internet. Settle on Solana when you reconnect.", 12f), AirnoteUi.marginParams(this, top = 3))
        root.addView(offlineStrip, AirnoteUi.marginParams(this, bottom = 20))

        createSectionAnchor = AirnoteUi.sectionLabel(this, "Create & Fund")
        root.addView(createSectionAnchor)
        root.addView(AirnoteUi.title(this, "Prepare an Airnote", 20f), AirnoteUi.marginParams(this, top = 3))
        root.addView(AirnoteUi.body(this, "Choose the amount and how long it can stay offline.", 13f), AirnoteUi.marginParams(this, top = 4, bottom = 12))

        val skrCard = AirnoteUi.card(this, 13).apply {
            background = AirnoteUi.rounded(this@MainActivity, "#FFF9EB", 18, "#F2E3B8")
        }
        val skrHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        skrHeader.addView(AirnoteUi.sectionLabel(this, "SKR Reserve+"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        skrHeader.addView(AirnoteUi.compactButton(this, "Verify").apply {
            minWidth = AirnoteUi.dp(this@MainActivity, 84)
            minHeight = AirnoteUi.dp(this@MainActivity, 38)
            setOnClickListener { verifySkrAccess(this) }
        }, LinearLayout.LayoutParams(AirnoteUi.dp(this, 92), AirnoteUi.dp(this, 40)))
        skrCard.addView(skrHeader)
        skrStatusView = AirnoteUi.body(this, "Standard 5 USDC / 6h  ·  Reserve+ 10 USDC / 24h", 11.5f)
        skrCard.addView(skrStatusView, AirnoteUi.marginParams(this, top = 4))
        root.addView(skrCard, AirnoteUi.marginParams(this, bottom = 10))

        val amountRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(1, 5, 10).forEach { amount ->
            val amountButton = AirnoteUi.secondaryButton(this, if (amount == 10) "10 USDC · SKR+" else "$amount USDC").apply {
                setOnClickListener {
                    selectedAmountUsdc = amount
                    refreshCreatePreview()
                }
            }
            amountButtons[amount] = amountButton
            if (amount == 10) tenUsdcButton = amountButton
            amountRow.addView(amountButton, LinearLayout.LayoutParams(0, AirnoteUi.dp(this, 46), 1f).apply {
                marginStart = AirnoteUi.dp(this@MainActivity, 3)
                marginEnd = AirnoteUi.dp(this@MainActivity, 3)
            })
        }
        root.addView(amountRow)

        val lifetimeRowOne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lifetimeRowTwo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        SenderLifetimePolicy.presets.forEachIndexed { index, preset ->
            val button = AirnoteUi.secondaryButton(this, if (preset.seconds == SkrAccessPolicy.RESERVE_PLUS_MAX_LIFETIME_SECONDS) {
                    "24 hours · SKR+"
                } else {
                    preset.label
                }).apply {
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
            lifetimeButtons[preset.seconds] = button
            if (preset.seconds == SkrAccessPolicy.RESERVE_PLUS_MAX_LIFETIME_SECONDS) {
                twentyFourHourButton = button
            }
            val row = if (index < 2) lifetimeRowOne else lifetimeRowTwo
            row.addView(button, LinearLayout.LayoutParams(0, AirnoteUi.dp(this, 46), 1f).apply {
                marginStart = AirnoteUi.dp(this@MainActivity, 3)
                marginEnd = AirnoteUi.dp(this@MainActivity, 3)
            })
        }
        root.addView(lifetimeRowOne)
        root.addView(lifetimeRowTwo)

        val customMinutes = EditText(this).apply {
            hint = "Custom minutes (5–1440)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(AirnoteUi.color(AirnoteUi.NIGHT))
            setHintTextColor(AirnoteUi.color(AirnoteUi.SLATE))
            setPadding(AirnoteUi.dp(this@MainActivity, 14), 0, AirnoteUi.dp(this@MainActivity, 14), 0)
            background = AirnoteUi.rounded(this@MainActivity, AirnoteUi.CLOUD, 16, AirnoteUi.MIST)
            minHeight = AirnoteUi.dp(this@MainActivity, 46)
        }
        root.addView(customMinutes, AirnoteUi.marginParams(this, top = 8))
        root.addView(AirnoteUi.compactButton(this, "Use custom lifetime").apply {
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

        createPreviewView = AirnoteUi.body(this, "", 14f).apply {
            setPadding(AirnoteUi.dp(this@MainActivity, 14), AirnoteUi.dp(this@MainActivity, 14), AirnoteUi.dp(this@MainActivity, 14), AirnoteUi.dp(this@MainActivity, 14))
            background = AirnoteUi.rounded(this@MainActivity, AirnoteUi.SKY_100, 16)
        }
        root.addView(createPreviewView, AirnoteUi.marginParams(this, top = 10, bottom = 10))

        val createAndFund = AirnoteUi.primaryButton(this, "Create & Fund Airnote").apply {
            setOnClickListener { createAndFund(this) }
        }
        root.addView(createAndFund)

        val continueButton = AirnoteUi.secondaryButton(this, "Continue latest Airnote").apply {
            setOnClickListener {
                val note = localAirnoteStore.latestIncomplete()
                if (note == null) {
                    Toast.makeText(this@MainActivity, "No Airnote is waiting for funding or certification.", Toast.LENGTH_LONG).show()
                } else {
                    fundOrCertify(note.noteIdHex, this)
                }
            }
        }
        root.addView(continueButton, AirnoteUi.marginParams(this, top = 8))

        root.addView(AirnoteUi.sectionLabel(this, "Local Airnotes"), AirnoteUi.marginParams(this, top = 28))
        activityView = AirnoteUi.body(this, "", 14f).apply {
            setPadding(AirnoteUi.dp(this@MainActivity, 16), AirnoteUi.dp(this@MainActivity, 16), AirnoteUi.dp(this@MainActivity, 16), AirnoteUi.dp(this@MainActivity, 16))
            background = AirnoteUi.rounded(this@MainActivity, AirnoteUi.CLOUD, 20, AirnoteUi.MIST)
        }
        root.addView(activityView)

        root.addView(AirnoteUi.secondaryButton(this, "Refresh").apply {
            setOnClickListener { refreshLocalActivity() }
        }, AirnoteUi.marginParams(this, top = 10))

        if (Build.VERSION.SDK_INT >= 33 && !AirnoteNotifications.canNotify(this)) {
            root.addView(AirnoteUi.compactButton(this, "Enable settlement alerts").apply {
                setOnClickListener {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }, AirnoteUi.marginParams(this, top = 14))
        }

        root.addView(AirnoteUi.compactButton(this, "Developer tools").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, DeveloperToolsActivity::class.java)) }
        }, AirnoteUi.marginParams(this, top = 8))

        screenScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(screenScroll)
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
        amountButtons.forEach { (amount, button) ->
            AirnoteUi.selectedChoice(button, this, amount == selectedAmountUsdc)
        }
        lifetimeButtons.forEach { (seconds, button) ->
            AirnoteUi.selectedChoice(button, this, seconds == selectedLifetimeSeconds)
        }
        val lifetime = SenderLifetimePolicy.lifetime(selectedLifetimeSeconds)
        createPreviewView.text = buildString {
            val reservePlus = SkrAccessPolicy.requiresReservePlus(selectedAmountUsdc, lifetime.seconds)
            append(selectedAmountUsdc.toString() + " USDC  ·  " + lifetime.label)
            append("\n")
            append(if (reservePlus) {
                if (skrHolder) "Reserve+ unlocked ✓" else "Reserve+ verification required"
            } else {
                "Standard offline reserve"
            })
        }
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
        val readyNotes = notes.filter { it.state == LocalAirnoteState.READY_OFFLINE }
        val readyAmount = readyNotes.sumOf { it.amountUsdc }
        if (::heroReserveAmountView.isInitialized) {
            heroReserveAmountView.text = String.format(Locale.US, "%.2f USDC", readyAmount.toDouble())
            heroReserveMetaView.text = if (readyNotes.isEmpty()) {
                "No Airnotes ready"
            } else {
                readyNotes.size.toString() + if (readyNotes.size == 1) " Airnote ready" else " Airnotes ready"
            }
        }

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
