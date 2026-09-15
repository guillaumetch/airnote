package com.airnote.hardwareprobe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var reportView: TextView
    private var latestReport: String = "AIRNOTE is ready. Run Gate 00 or create a Gate 03 note-owner key."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "AIRNOTE\nSeeker Security Lab"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
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

        val createGate03Key = Button(this).apply {
            text = "CREATE GATE 03 NOTE OWNER"
            setOnClickListener {
                isEnabled = false
                reportView.text = "Creating persistent StrongBox note-owner key…"
                Thread {
                    latestReport = runCatching {
                        NoteOwnerKeyManager(this@MainActivity).create().report()
                    }.getOrElse { t ->
                        "AIRNOTE — GATE 03 NOTE OWNER\n\nFAIL\n${t::class.java.name}: ${t.message.orEmpty()}"
                    }
                    runOnUiThread {
                        reportView.text = latestReport
                        isEnabled = true
                    }
                }.start()
            }
        }
        root.addView(createGate03Key)

        root.addView(Button(this).apply {
            text = "RESET VIEW"
            setOnClickListener {
                latestReport = "AIRNOTE is ready. Run Gate 00 or create a Gate 03 note-owner key."
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
    }
}
