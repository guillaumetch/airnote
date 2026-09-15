package com.airnote.hardwareprobe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class DeveloperToolsActivity : AppCompatActivity() {
    private lateinit var reportView: TextView
    private var latestReport = "Developer tools are isolated from the normal AIRNOTE payment flow."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AIRNOTE — Developer Tools"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
        }

        root.addView(TextView(this).apply {
            text = "DEVELOPER TOOLS"
            textSize = 26f
        })
        root.addView(TextView(this).apply {
            text = "Hardware diagnostics and raw StrongBox tooling. These controls are not part of the production payment UX."
            textSize = 14f
            setPadding(0, 8, 0, 20)
        })

        root.addView(Button(this).apply {
            text = "SEEKER PHYSICAL PROOF"
            setOnClickListener {
                startActivity(Intent(this@DeveloperToolsActivity, AirnoteProofActivity::class.java))
            }
        })

        val runGate00 = Button(this).apply {
            text = "RUN STRONGBOX HARDWARE PROBE"
            setOnClickListener {
                isEnabled = false
                reportView.text = "Running StrongBox hardware probe…"
                Thread {
                    val result = ProbeEngine(this@DeveloperToolsActivity).run()
                    latestReport = result.report
                    runCatching {
                        AirnoteProofJournal(this@DeveloperToolsActivity).record(
                            type = if (result.verdict == ProbeEngine.Verdict.PASS) {
                                AirnoteProofEventType.STRONGBOX_PROBE_PASS
                            } else {
                                AirnoteProofEventType.STRONGBOX_PROBE_NOT_PASS
                            },
                            detail = "Gate 00 verdict=${result.verdict}; StrongBox requested=true",
                            captureConnectivity = false
                        )
                    }
                    runOnUiThread {
                        reportView.text = latestReport
                        isEnabled = true
                    }
                }.start()
            }
        }
        root.addView(runGate00)

        val createRawKey = Button(this).apply {
            text = "CREATE RAW NOTE OWNER KEY"
            setOnClickListener {
                isEnabled = false
                reportView.text = "Creating persistent StrongBox note-owner key…"
                Thread {
                    latestReport = runCatching {
                        NoteOwnerKeyManager(this@DeveloperToolsActivity).create().report()
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
            text = "COPY REPORT"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AIRNOTE developer report", latestReport))
                Toast.makeText(this@DeveloperToolsActivity, "Report copied", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(Button(this).apply {
            text = "EXPORT REPORT"
            setOnClickListener {
                val file = File(getExternalFilesDir(null), "airnote-developer-report.txt")
                file.writeText(latestReport)
                Toast.makeText(this@DeveloperToolsActivity, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
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
    }
}
