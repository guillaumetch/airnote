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
    private var latestReport: String = "AIRNOTE Gate 00 has not been run yet."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "AIRNOTE\nHardware Gate 00"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val run = Button(this).apply {
            text = "RUN COMPLETE PROBE"
            setOnClickListener {
                isEnabled = false
                reportView.text = "Running hardware probe…"
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
        root.addView(run)

        root.addView(Button(this).apply {
            text = "RESET"
            setOnClickListener {
                latestReport = "AIRNOTE Gate 00 has not been run yet."
                reportView.text = latestReport
            }
        })

        root.addView(Button(this).apply {
            text = "COPY REPORT"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AIRNOTE Gate 00", latestReport))
                Toast.makeText(this@MainActivity, "Report copied", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(Button(this).apply {
            text = "EXPORT REPORT"
            setOnClickListener {
                val file = File(getExternalFilesDir(null), "airnote-gate00-report.txt")
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
