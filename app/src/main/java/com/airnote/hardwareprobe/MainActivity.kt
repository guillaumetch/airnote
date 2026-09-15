package com.airnote.hardwareprobe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    private lateinit var reportView: TextView
    private lateinit var createPreviewView: TextView
    private var latestReport: String = "AIRNOTE is ready. Create, send, or receive below."
    private var selectedAmountUsdc = 1
    private var selectedLifetimeSeconds = SenderLifetimePolicy.DEFAULT_SECONDS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "AIRNOTE\nSeeker Lab"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        })

        root.addView(TextView(this).apply {
            text = "CREATE AIRNOTE"
            textSize = 20f
            setPadding(0, 24, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = "Sender chooses the note value and how long the funded Airnote remains redeemable."
            textSize = 14f
            setPadding(0, 0, 0, 12)
        })

        val amountRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(1, 5, 10).forEach { amount ->
            amountRow.addView(Button(this).apply {
                text = "$$amount"
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
            hint = "Custom lifetime in minutes (5–1440)"
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
            text = "CREATE AIRNOTE OWNER KEY"
            setOnClickListener {
                isEnabled = false
                reportView.text = "Creating persistent StrongBox note-owner key…"
                val amount = selectedAmountUsdc
                val lifetimeSeconds = selectedLifetimeSeconds
                Thread {
                    latestReport = runCatching {
                        val material = NoteOwnerKeyManager(this@MainActivity).create()
                        buildSenderMintRequest(material, amount, lifetimeSeconds)
                    }.getOrElse { t ->
                        "AIRNOTE — CREATE FAILED\n\n${t::class.java.name}: ${t.message.orEmpty()}"
                    }
                    runOnUiThread {
                        reportView.text = latestReport
                        if (latestReport.startsWith("AIRNOTE — SENDER MINT REQUEST")) {
                            val file = persistMintRequest(latestReport)
                            Toast.makeText(this@MainActivity, "Mint request saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                        }
                        isEnabled = true
                    }
                }.start()
            }
        }
        root.addView(createAirnote)

        root.addView(TextView(this).apply {
            text = "TRANSFER"
            textSize = 20f
            setPadding(0, 28, 0, 4)
        })
        root.addView(Button(this).apply {
            text = "SEND AIRNOTE — SEEKER A"
            setOnClickListener { startActivity(Intent(this@MainActivity, Gate04SenderActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "RECEIVE AIRNOTE — SEEKER B"
            setOnClickListener { startActivity(Intent(this@MainActivity, Gate04ReceiverActivity::class.java)) }
        })

        val runGate00 = Button(this).apply {
            text = "RUN GATE 00 HARDWARE PROBE"
            setOnClickListener {
                isEnabled = false
                reportView.text = "Running Gate 00 hardware probe…"
                Thread {
                    val result = ProbeEngine(this@MainActivity).run()
                    latestReport = result.report
                    runOnUiThread {
                        reportView.text = latestReport
                        isEnabled = true
                    }
                }.start()
            }
        }
        root.addView(runGate00)

        val createRawKey = Button(this).apply {
            text = "CREATE RAW NOTE OWNER — LAB"
            setOnClickListener {
                isEnabled = false
                reportView.text = "Creating persistent StrongBox note-owner key…"
                Thread {
                    latestReport = runCatching {
                        NoteOwnerKeyManager(this@MainActivity).create().report()
                    }.getOrElse { t ->
                        "AIRNOTE — NOTE OWNER\n\nFAIL\n${t::class.java.name}: ${t.message.orEmpty()}"
                    }
                    runOnUiThread {
                        reportView.text = latestReport
                        isEnabled = true
                    }
                }.start()
            }
        }
        root.addView(createRawKey)

        root.addView(Button(this).apply {
            text = "RESET VIEW"
            setOnClickListener {
                latestReport = "AIRNOTE is ready. Create, send, or receive above."
                reportView.text = latestReport
            }
        })

        root.addView(Button(this).apply {
            text = "COPY REPORT"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AIRNOTE report", latestReport))
                Toast.makeText(this@MainActivity, "Report copied", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(Button(this).apply {
            text = "EXPORT REPORT"
            setOnClickListener {
                val file = File(getExternalFilesDir(null), "airnote-report.txt")
                file.writeText(latestReport)
                Toast.makeText(this@MainActivity, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        })

        reportView = TextView(this).apply {
            text = latestReport
            setTextIsSelectable(true)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 24, 0, 48)
        }
        root.addView(reportView)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshCreatePreview()
    }

    private fun refreshCreatePreview() {
        if (!::createPreviewView.isInitialized) return
        val lifetime = SenderLifetimePolicy.lifetime(selectedLifetimeSeconds)
        createPreviewView.text = buildString {
            appendLine("$selectedAmountUsdc USDC • ${lifetime.label}")
            appendLine("Risk window: ${lifetime.exposureLabel}")
            appendLine("Estimated expiry: ${Instant.now().plusSeconds(lifetime.seconds)}")
            append("Receiver request expiry is separate and only controls handoff freshness.")
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
