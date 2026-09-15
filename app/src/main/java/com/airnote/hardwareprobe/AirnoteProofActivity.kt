package com.airnote.hardwareprobe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class AirnoteProofActivity : AppCompatActivity() {
    private lateinit var reportView: TextView
    private lateinit var journal: AirnoteProofJournal

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AIRNOTE — Seeker Proof"
        journal = AirnoteProofJournal(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
        }

        root.addView(TextView(this).apply {
            text = "SEEKER PHYSICAL PROOF"
            textSize = 26f
        })
        root.addView(TextView(this).apply {
            text = "Observed evidence only. AIRNOTE does not let you manually mark cryptographic or settlement checks as passed."
            textSize = 14f
            setPadding(0, 8, 0, 20)
        })

        root.addView(Button(this).apply {
            text = "REFRESH OBSERVED EVIDENCE"
            setOnClickListener { refresh() }
        })

        root.addView(Button(this).apply {
            text = "COPY PROOF JOURNAL"
            setOnClickListener {
                val text = journal.exportText()
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AIRNOTE Seeker proof", text))
                Toast.makeText(this@AirnoteProofActivity, "Proof journal copied", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(Button(this).apply {
            text = "EXPORT PROOF JOURNAL"
            setOnClickListener {
                val file = File(getExternalFilesDir(null), "airnote-seeker-proof.txt")
                file.writeText(journal.exportText())
                Toast.makeText(this@AirnoteProofActivity, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        })

        root.addView(Button(this).apply {
            text = "RESET PROOF RUN"
            setOnClickListener {
                AlertDialog.Builder(this@AirnoteProofActivity)
                    .setTitle("Reset proof journal?")
                    .setMessage("This clears only Gate 12 proof events. Payment history, keys, handoff locks, and Solana state are untouched.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Reset") { _, _ ->
                        journal.clear()
                        refresh()
                    }
                    .show()
            }
        })

        reportView = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 24, 0, 48)
        }
        root.addView(reportView)

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::reportView.isInitialized) refresh()
    }

    private fun refresh() {
        reportView.text = journal.exportText()
    }
}
