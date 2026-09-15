package com.airnote.hardwareprobe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.airnote.protocol.SenderLifetimePolicy
import java.io.File
import java.time.Instant

class CreateAirnoteActivity : AppCompatActivity() {
    private var selectedAmountUsdc = 1
    private var selectedLifetimeSeconds = SenderLifetimePolicy.DEFAULT_SECONDS
    private lateinit var previewView: TextView
    private lateinit var reportView: TextView
    private var latestMintRequest: String? = null
    private var latestRequestFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
        }

        root.addView(TextView(this).apply {
            text = "CREATE AIRNOTE"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(TextView(this).apply {
            text = "Choose the note value and how long the sender wants the funded Airnote to remain redeemable."
            textSize = 15f
            setPadding(0, 12, 0, 24)
        })

        root.addView(TextView(this).apply {
            text = "Amount"
            textSize = 18f
        })
        val amountRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(1, 5, 10).forEach { amount ->
            amountRow.addView(Button(this).apply {
                text = "$$amount"
                setOnClickListener {
                    selectedAmountUsdc = amount
                    refreshPreview()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(amountRow)

        root.addView(TextView(this).apply {
            text = "Valid for"
            textSize = 18f
            setPadding(0, 24, 0, 0)
        })

        val lifetimeRowOne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lifetimeRowTwo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        SenderLifetimePolicy.presets.forEachIndexed { index, preset ->
            val button = Button(this).apply {
                text = preset.label
                setOnClickListener {
                    selectedLifetimeSeconds = preset.seconds
                    refreshPreview()
                }
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
            text = "APPLY CUSTOM LIFETIME"
            setOnClickListener {
                val minutes = customMinutes.text.toString().toLongOrNull()
                val lifetime = runCatching {
                    requireNotNull(minutes) { "Enter a number of minutes" }
                    SenderLifetimePolicy.fromMinutes(minutes)
                }.getOrElse { error ->
                    Toast.makeText(this@CreateAirnoteActivity, error.message ?: "Invalid lifetime", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                selectedLifetimeSeconds = lifetime.seconds
                refreshPreview()
            }
        })

        previewView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }
        root.addView(previewView)

        val createButton = Button(this).apply {
            text = "CREATE AIRNOTE OWNER KEY"
            setOnClickListener {
                isEnabled = false
                reportView.text = "Creating a persistent StrongBox note-owner key…"
                val amount = selectedAmountUsdc
                val lifetimeSeconds = selectedLifetimeSeconds
                Thread {
                    val result = runCatching {
                        val material = NoteOwnerKeyManager(this@CreateAirnoteActivity).create()
                        buildMintRequest(material, amount, lifetimeSeconds)
                    }
                    runOnUiThread {
                        result.onSuccess { request ->
                            latestMintRequest = request
                            reportView.text = request
                            latestRequestFile = persistMintRequest(request)
                            Toast.makeText(
                                this@CreateAirnoteActivity,
                                "StrongBox key ready. Sender lifetime locked into mint request.",
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure { t ->
                            reportView.text = "AIRNOTE — CREATE FAILED\n\n${t::class.java.name}: ${t.message.orEmpty()}"
                        }
                        isEnabled = true
                    }
                }.start()
            }
        }
        root.addView(createButton)

        root.addView(Button(this).apply {
            text = "COPY MINT REQUEST"
            setOnClickListener {
                val request = latestMintRequest
                if (request == null) {
                    Toast.makeText(this@CreateAirnoteActivity, "Create the Airnote first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AIRNOTE mint request", request))
                Toast.makeText(this@CreateAirnoteActivity, "Mint request copied", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(Button(this).apply {
            text = "SHOW EXPORTED REQUEST PATH"
            setOnClickListener {
                val file = latestRequestFile
                Toast.makeText(
                    this@CreateAirnoteActivity,
                    file?.absolutePath ?: "Create the Airnote first",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        reportView = TextView(this).apply {
            text = "No mint request yet."
            setTextIsSelectable(true)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 24, 0, 48)
        }
        root.addView(reportView)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshPreview()
    }

    private fun refreshPreview() {
        if (!::previewView.isInitialized) return
        val lifetime = SenderLifetimePolicy.lifetime(selectedLifetimeSeconds)
        val estimatedExpiry = Instant.now().plusSeconds(lifetime.seconds)
        previewView.text = buildString {
            appendLine("$selectedAmountUsdc USDC • ${lifetime.label}")
            appendLine("Risk window: ${lifetime.exposureLabel}")
            appendLine("Estimated expiry: $estimatedExpiry")
            append("Receiver-request TTL is separate and remains a short handoff-freshness check.")
        }
    }

    private fun buildMintRequest(
        material: NoteOwnerKeyManager.Material,
        amountUsdc: Int,
        lifetimeSeconds: Long
    ): String {
        val lifetime = SenderLifetimePolicy.lifetime(lifetimeSeconds)
        val createdAt = Instant.now()
        val estimatedExpiry = createdAt.plusSeconds(lifetime.seconds)
        return buildString {
            appendLine("AIRNOTE — SENDER MINT REQUEST")
            appendLine()
            appendLine("VERSION=1")
            appendLine("CREATED_AT=$createdAt")
            appendLine("AMOUNT_USDC=$amountUsdc")
            appendLine("LIFETIME_SECONDS=${lifetime.seconds}")
            appendLine("LIFETIME_LABEL=${lifetime.label}")
            appendLine("OFFLINE_EXPOSURE=${lifetime.exposureLabel}")
            appendLine("ESTIMATED_EXPIRES_AT=$estimatedExpiry")
            appendLine("MINT_LIFETIME_ARGUMENT=--lifetime-seconds ${lifetime.seconds}")
            appendLine()
            append(material.report())
            appendLine()
            appendLine("MINT_STATUS=STRONGBOX_KEY_READY_FUNDING_REQUIRED")
            appendLine("The final expiry is derived from Solana chain time when the note is funded.")
        }
    }

    private fun persistMintRequest(request: String): File {
        val noteId = Regex("NOTE_ID_HEX=([0-9a-f]{64})")
            .find(request)?.groupValues?.get(1) ?: "unknown"
        return File(getExternalFilesDir(null), "airnote-mint-request-$noteId.txt").apply {
            writeText(request)
        }
    }
}
