package com.airnote.hardwareprobe

import android.content.Intent
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

class MainActivity : AppCompatActivity() {
    private lateinit var createPreviewView: TextView
    private lateinit var activityView: TextView
    private var selectedAmountUsdc = 1
    private var selectedLifetimeSeconds = SenderLifetimePolicy.DEFAULT_SECONDS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            setPadding(0, 4, 0, 28)
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

        root.addView(TextView(this).apply {
            text = "CREATE AIRNOTE"
            textSize = 20f
            setPadding(0, 30, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = "Choose the value and how long this Airnote can settle after it is funded."
            textSize = 14f
            setPadding(0, 0, 0, 12)
        })

        val amountRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(1, 5, 10).forEach { amount ->
            amountRow.addView(Button(this).apply {
                text = "$${amount}"
                setOnClickListener {
                    selectedAmountUsdc = amount
                    refreshCreatePreview()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(amountRow)

        val lifetimeRowOne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lifetimeRowTwo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        SenderLifetimePolicy.presets.forEachIndexed { index, preset ->
            val button = Button(this).apply {
                text = preset.label
                setOnClickListener {
                    selectedLifetimeSeconds = preset.seconds
                    refreshCreatePreview()
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
                selectedLifetimeSeconds = lifetime.seconds
                refreshCreatePreview()
            }
        })

        createPreviewView = TextView(this).apply {
            textSize = 15f
            setPadding(0, 12, 0, 12)
        }
        root.addView(createPreviewView)

        val createAirnote = Button(this).apply {
            text = "CREATE AIRNOTE"
            setOnClickListener {
                isEnabled = false
                text = "CREATING STRONGBOX KEY…"
                val amount = selectedAmountUsdc
                val lifetimeSeconds = selectedLifetimeSeconds
                Thread {
                    val result = runCatching {
                        val material = NoteOwnerKeyManager(this@MainActivity).create()
                        buildSenderMintRequest(material, amount, lifetimeSeconds)
                    }
                    runOnUiThread {
                        result.onSuccess { request ->
                            val file = persistMintRequest(request)
                            Toast.makeText(
                                this@MainActivity,
                                "Airnote prepared. Funding request saved locally.",
                                Toast.LENGTH_LONG
                            ).show()
                            refreshLocalActivity()
                            val shortId = Regex("NOTE_ID_HEX=([0-9a-f]{64})")
                                .find(request)?.groupValues?.get(1)?.take(12) ?: "created"
                            activityView.text = "READY TO FUND\n$amount USDC • ${SenderLifetimePolicy.lifetime(lifetimeSeconds).label}\nNote $shortId…\n${file.name}"
                        }.onFailure { t ->
                            Toast.makeText(
                                this@MainActivity,
                                "Create failed: ${t.message ?: t::class.java.simpleName}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        text = "CREATE AIRNOTE"
                        isEnabled = true
                    }
                }.start()
            }
        }
        root.addView(createAirnote)

        root.addView(TextView(this).apply {
            text = "LOCAL ACTIVITY"
            textSize = 20f
            setPadding(0, 30, 0, 4)
        })
        activityView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 12)
        }
        root.addView(activityView)

        root.addView(Button(this).apply {
            text = "REFRESH LOCAL STATUS"
            setOnClickListener { refreshLocalActivity() }
        })

        root.addView(Button(this).apply {
            text = "DEVELOPER TOOLS"
            setOnClickListener { startActivity(Intent(this@MainActivity, DeveloperToolsActivity::class.java)) }
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshCreatePreview()
        refreshLocalActivity()
    }

    override fun onResume() {
        super.onResume()
        if (::activityView.isInitialized) refreshLocalActivity()
    }

    private fun refreshCreatePreview() {
        if (!::createPreviewView.isInitialized) return
        val lifetime = SenderLifetimePolicy.lifetime(selectedLifetimeSeconds)
        createPreviewView.text = buildString {
            appendLine("$selectedAmountUsdc USDC • ${lifetime.label}")
            appendLine("Offline exposure: ${lifetime.exposureLabel}")
            appendLine("Estimated expiry: ${Instant.now().plusSeconds(lifetime.seconds)}")
            append("Receiver request TTL is separate and only protects handoff freshness.")
        }
    }

    private fun refreshLocalActivity() {
        if (!::activityView.isInitialized) return
        val prefs = getSharedPreferences("airnote_gate04", MODE_PRIVATE)
        val accepted = prefs.all.keys.count { it.startsWith("accepted:") }
        val mintRequests = getExternalFilesDir(null)
            ?.listFiles()
            .orEmpty()
            .count { it.name.startsWith("airnote-mint-request-") }
        activityView.text = buildString {
            appendLine("Pending offline acceptances: $accepted")
            appendLine("Prepared sender notes: $mintRequests")
            appendLine()
            append("Settlement remains final on Solana.")
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
            appendLine("CREATED_AT=$createdAt")
            appendLine("AMOUNT_USDC=$amountUsdc")
            appendLine("LIFETIME_SECONDS=${lifetime.seconds}")
            appendLine("LIFETIME_LABEL=${lifetime.label}")
            appendLine("OFFLINE_EXPOSURE=${lifetime.exposureLabel}")
            appendLine("ESTIMATED_EXPIRES_AT=${createdAt.plusSeconds(lifetime.seconds)}")
            appendLine("MINT_LIFETIME_ARGUMENT=--lifetime-seconds ${lifetime.seconds}")
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
        return File(getExternalFilesDir(null), "airnote-mint-request-$noteId.txt").apply {
            writeText(request)
        }
    }
}
