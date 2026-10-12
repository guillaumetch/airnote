package com.airnote.hardwareprobe

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airnote.protocol.SenderLifetimePolicy
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

class CreateFundActivity : AppCompatActivity() {
    private lateinit var localAirnoteStore: LocalAirnoteStore
    private lateinit var walletActivityResultSender: ActivityResultSender
    private lateinit var skrStatusView: TextView
    private lateinit var tenUsdcButton: Button
    private lateinit var twentyFourHourButton: Button
    private lateinit var previewView: TextView
    private lateinit var stateView: TextView
    private lateinit var continueButton: Button

    private val amountButtons = linkedMapOf<Int, Button>()
    private val lifetimeButtons = linkedMapOf<Long, Button>()

    private var selectedAmountUsdc = 1
    private var selectedLifetimeSeconds = SenderLifetimePolicy.DEFAULT_SECONDS
    private var skrHolder = false
    private var verifiedSkrWalletBase58: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        walletActivityResultSender = ActivityResultSender(this)
        localAirnoteStore = LocalAirnoteStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                AirnoteUi.dp(this@CreateFundActivity, 22),
                AirnoteUi.dp(this@CreateFundActivity, 24),
                AirnoteUi.dp(this@CreateFundActivity, 22),
                AirnoteUi.dp(this@CreateFundActivity, 30)
            )
            setBackgroundColor(AirnoteUi.color(AirnoteUi.SKY_50))
        }
        AirnoteUi.applyTopInset(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(AirnoteUi.title(this, "Create & Fund", 24f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(AirnoteUi.body(this, "✕", 18f).apply {
            setTextColor(AirnoteUi.color(AirnoteUi.SLATE))
            setPadding(AirnoteUi.dp(this@CreateFundActivity, 10), AirnoteUi.dp(this@CreateFundActivity, 8), AirnoteUi.dp(this@CreateFundActivity, 2), AirnoteUi.dp(this@CreateFundActivity, 8))
            setOnClickListener { finish() }
        })
        root.addView(top)
        root.addView(
            AirnoteUi.body(this, "Add USDC to your Airnote to pay offline, anytime.", 13f),
            AirnoteUi.marginParams(this, top = 4, bottom = 14)
        )

        val createCard = AirnoteUi.glassCard(this, 16)
        createCard.addView(AirnoteUi.sectionLabel(this, "Amount"))

        val amountRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        listOf(1, 5, 10).forEach { amount ->
            val button = AirnoteUi.secondaryButton(
                this,
                if (amount == 10) "10 USDC\nSKR+" else "$amount USDC"
            ).apply {
                setOnClickListener {
                    selectedAmountUsdc = amount
                    refreshUi()
                }
            }
            amountButtons[amount] = button
            if (amount == 10) tenUsdcButton = button
            amountRow.addView(
                button,
                LinearLayout.LayoutParams(0, AirnoteUi.dp(this, 56), 1f).apply {
                    marginStart = AirnoteUi.dp(this@CreateFundActivity, 3)
                    marginEnd = AirnoteUi.dp(this@CreateFundActivity, 3)
                }
            )
        }
        createCard.addView(amountRow, AirnoteUi.marginParams(this, top = 8))

        createCard.addView(AirnoteUi.sectionLabel(this, "Offline lifetime"), AirnoteUi.marginParams(this, top = 16))
        val lifeRowOne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lifeRowTwo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        SenderLifetimePolicy.presets.forEachIndexed { index, preset ->
            val label = if (preset.seconds == SkrAccessPolicy.RESERVE_PLUS_MAX_LIFETIME_SECONDS) {
                "24 hours\nSKR+"
            } else {
                preset.label
            }
            val button = AirnoteUi.secondaryButton(this, label).apply {
                setOnClickListener {
                    selectedLifetimeSeconds = preset.seconds
                    refreshUi()
                }
            }
            lifetimeButtons[preset.seconds] = button
            if (preset.seconds == SkrAccessPolicy.RESERVE_PLUS_MAX_LIFETIME_SECONDS) {
                twentyFourHourButton = button
            }
            val row = if (index < 2) lifeRowOne else lifeRowTwo
            row.addView(
                button,
                LinearLayout.LayoutParams(0, AirnoteUi.dp(this, 54), 1f).apply {
                    marginStart = AirnoteUi.dp(this@CreateFundActivity, 3)
                    marginEnd = AirnoteUi.dp(this@CreateFundActivity, 3)
                }
            )
        }
        createCard.addView(lifeRowOne, AirnoteUi.marginParams(this, top = 8))
        createCard.addView(lifeRowTwo, AirnoteUi.marginParams(this, top = 6))

        previewView = AirnoteUi.body(this, "", 13f).apply {
            setPadding(
                AirnoteUi.dp(this@CreateFundActivity, 14),
                AirnoteUi.dp(this@CreateFundActivity, 12),
                AirnoteUi.dp(this@CreateFundActivity, 14),
                AirnoteUi.dp(this@CreateFundActivity, 12)
            )
            background = AirnoteUi.rounded(this@CreateFundActivity, AirnoteUi.SKY_100, 16)
        }
        createCard.addView(previewView, AirnoteUi.marginParams(this, top = 12))
        root.addView(createCard)

        val skrCard = AirnoteUi.glassCard(this, 14).apply {
            background = AirnoteUi.rounded(this@CreateFundActivity, "#FFF9EB", 20, "#F2E3B8")
        }
        val skrHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        skrHeader.addView(AirnoteUi.sectionLabel(this, "SKR Reserve+"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        skrHeader.addView(AirnoteUi.body(this, "10 USDC / 24h", 11f))
        skrCard.addView(skrHeader)
        skrStatusView = AirnoteUi.body(this, "Verify a wallet with liquid SKR to unlock Reserve+.", 12f)
        skrCard.addView(skrStatusView, AirnoteUi.marginParams(this, top = 6, bottom = 8))
        skrCard.addView(AirnoteUi.compactButton(this, "Verify SKR access").apply {
            setOnClickListener { verifySkrAccess(this) }
        })
        root.addView(skrCard, AirnoteUi.marginParams(this, top = 12))

        val createButton = AirnoteUi.primaryButton(this, "Create & Fund Airnote").apply {
            setOnClickListener { createAndFund(this) }
        }
        root.addView(createButton, AirnoteUi.marginParams(this, top = 14))

        continueButton = AirnoteUi.secondaryButton(this, "Continue latest Airnote").apply {
            setOnClickListener {
                val note = localAirnoteStore.latestIncomplete()
                if (note == null) {
                    Toast.makeText(this@CreateFundActivity, "No Airnote is waiting for funding or certification.", Toast.LENGTH_LONG).show()
                } else {
                    fundOrCertify(note.noteIdHex, this)
                }
            }
        }
        root.addView(continueButton, AirnoteUi.marginParams(this, top = 8))

        stateView = AirnoteUi.body(this, "", 12.5f).apply {
            setPadding(
                AirnoteUi.dp(this@CreateFundActivity, 14),
                AirnoteUi.dp(this@CreateFundActivity, 14),
                AirnoteUi.dp(this@CreateFundActivity, 14),
                AirnoteUi.dp(this@CreateFundActivity, 14)
            )
            background = AirnoteUi.rounded(this@CreateFundActivity, "#F8FCFF", 18, "#DDE7F0")
        }
        root.addView(stateView, AirnoteUi.marginParams(this, top = 12))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(AirnoteUi.color(AirnoteUi.SKY_50))
            addView(root)
        }
        setContentView(
            AirnoteBottomNav.attach(
                this,
                scroll,
                null
            )
        )

        refreshSkrAccessControls()
        refreshUi()
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        if (::stateView.isInitialized) refreshState()
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
        button.text = "Creating StrongBox key…"
        val amount = selectedAmountUsdc
        val lifetimeSeconds = selectedLifetimeSeconds

        Thread {
            val result = runCatching {
                val material = NoteOwnerKeyManager(this@CreateFundActivity).create()
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
                    refreshState()
                    button.text = "Opening wallet…"
                    fundOrCertify(note.noteIdHex, button)
                }.onFailure { error ->
                    button.text = "Create & Fund Airnote"
                    button.isEnabled = true
                    Toast.makeText(
                        this@CreateFundActivity,
                        "Create failed: " + (error.message ?: error::class.java.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun fundOrCertify(noteIdHex: String, button: Button) {
        val idleLabel = if (button.text.toString().contains("Continue", ignoreCase = true)) {
            "Continue latest Airnote"
        } else {
            "Create & Fund Airnote"
        }
        button.isEnabled = false
        val before = localAirnoteStore.get(noteIdHex)
        button.text = when (before?.state) {
            LocalAirnoteState.FUNDED_FINALIZED -> "Certifying backing…"
            LocalAirnoteState.FUNDING -> "Checking funding…"
            else -> "Opening wallet…"
        }

        lifecycleScope.launch {
            val result = runCatching {
                AirnoteFundingCoordinator(localAirnoteStore)
                    .createReadyOffline(noteIdHex, walletActivityResultSender)
            }

            result.onSuccess { ready ->
                stateView.text = buildString {
                    appendLine("READY OFFLINE  ✓")
                    appendLine(ready.note.amountUsdc.toString() + " USDC")
                    append("Funded, finalized and backing-certified. Open Send to hand it off offline.")
                }
                stateView.background = AirnoteUi.rounded(this@CreateFundActivity, "#E7FAF2", 18, "#BCEBD8")
                Toast.makeText(
                    this@CreateFundActivity,
                    ready.note.amountUsdc.toString() + " USDC Airnote ready offline",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { error ->
                val message = when (error) {
                    is AirnoteFundingCoordinator.CertificationPendingException ->
                        "Funding finalized. Backing certification is pending; Continue will retry certification only."
                    else -> error.message ?: error::class.java.simpleName
                }
                stateView.text = message
                Toast.makeText(this@CreateFundActivity, message, Toast.LENGTH_LONG).show()
            }

            button.text = idleLabel
            button.isEnabled = true
            refreshState()
        }
    }

    private fun verifySkrAccess(button: Button) {
        button.isEnabled = false
        button.text = "Checking SKR…"
        skrStatusView.text = "Opening wallet and checking liquid SKR on Solana mainnet…"

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
                    "SKR HOLDER ✓  ·  RESERVE+ UNLOCKED\n" +
                        access.displayBalance + " liquid SKR  ·  " +
                        access.walletBase58.take(6) + "…" + access.walletBase58.takeLast(6)
                } else {
                    "No liquid SKR found. Standard access remains 5 USDC / 6h."
                }
            }.onFailure { error ->
                skrHolder = false
                verifiedSkrWalletBase58 = null
                skrStatusView.text = "SKR check failed · " + (error.message ?: error::class.java.simpleName)
            }

            refreshSkrAccessControls()
            refreshUi()
            button.text = "Verify SKR access"
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

    private fun refreshUi() {
        if (!::previewView.isInitialized) return
        amountButtons.forEach { (amount, button) ->
            AirnoteUi.selectedChoice(button, this, amount == selectedAmountUsdc)
        }
        lifetimeButtons.forEach { (seconds, button) ->
            AirnoteUi.selectedChoice(button, this, seconds == selectedLifetimeSeconds)
        }

        val lifetime = SenderLifetimePolicy.lifetime(selectedLifetimeSeconds)
        val reservePlus = SkrAccessPolicy.requiresReservePlus(selectedAmountUsdc, lifetime.seconds)
        previewView.text = buildString {
            append(selectedAmountUsdc.toString() + " USDC  ·  " + lifetime.label)
            append("  ·  ")
            append(
                if (reservePlus) {
                    if (skrHolder) "Reserve+ ✓" else "Reserve+ required"
                } else {
                    "Standard"
                }
            )
            appendLine()
            append("Online to prepare. Offline to hand off. Online to settle.")
        }
    }

    private fun refreshState() {
        if (!::stateView.isInitialized) return
        val note = localAirnoteStore.latestIncomplete()
        if (::continueButton.isInitialized) {
            continueButton.visibility = if (note != null) android.view.View.VISIBLE else android.view.View.GONE
        }
        if (note == null) {
            stateView.visibility = android.view.View.GONE
            return
        }
        stateView.visibility = android.view.View.VISIBLE
        stateView.text = note.amountUsdc.toString() + " USDC · " + note.state.name.replace('_', ' ')
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
