package com.airnote.hardwareprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class AirnoteProofJournal(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(
        type: AirnoteProofEventType,
        handoffSessionId: String? = null,
        paymentId: String? = null,
        transactionSignature: String? = null,
        detail: String? = null,
        captureConnectivity: Boolean = true,
        timestampUnixSeconds: Long = System.currentTimeMillis() / 1000L
    ): AirnoteProofEvent {
        val event = AirnoteProofEvent(
            id = UUID.randomUUID().toString(),
            type = type,
            timestampUnixSeconds = timestampUnixSeconds,
            handoffSessionId = handoffSessionId,
            paymentId = paymentId,
            transactionSignature = transactionSignature,
            validatedInternet = if (captureConnectivity) hasValidatedInternet() else null,
            detail = detail
        )
        check(prefs.edit().putString(KEY_PREFIX + event.id, encode(event)).commit()) {
            "could not persist AIRNOTE proof event"
        }
        return event
    }

    fun events(): List<AirnoteProofEvent> = prefs.all.entries
        .asSequence()
        .filter { it.key.startsWith(KEY_PREFIX) }
        .mapNotNull { (_, value) -> (value as? String)?.let(::decode) }
        .sortedBy { it.timestampUnixSeconds }
        .toList()

    fun clear() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach(editor::remove)
        editor.commit()
    }

    fun exportText(): String {
        val events = events()
        val checks = AirnoteProofChecklist.evaluate(events)
        return buildString {
            appendLine("AIRNOTE — SEEKER PHYSICAL PROOF JOURNAL")
            appendLine("Generated: ${Instant.now()}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine()
            appendLine("CHECKLIST")
            checks.forEach { check ->
                appendLine("${if (check.observed) "OBSERVED" else "NOT OBSERVED"} — ${check.label}")
                appendLine("  ${check.evidence}")
            }
            appendLine()
            appendLine("EVENTS")
            if (events.isEmpty()) appendLine("No proof events recorded.")
            events.forEach { event ->
                appendLine("${Instant.ofEpochSecond(event.timestampUnixSeconds)}  ${event.type}")
                event.handoffSessionId?.let { appendLine("  handoff_session=$it") }
                event.paymentId?.let { appendLine("  payment_id=$it") }
                event.transactionSignature?.let { appendLine("  solana_signature=$it") }
                event.validatedInternet?.let { appendLine("  validated_internet=$it") }
                event.detail?.let { appendLine("  detail=$it") }
            }
            appendLine()
            appendLine("BOUNDARY")
            appendLine("This journal contains observed app/device events only. It stores no raw transfer payloads, wallet private keys, StrongBox private material, or seed phrases.")
            appendLine("A background-finalized event proves WorkManager reconciliation ran; pair it with screen recording/device actions if you want to demonstrate process-death recovery to judges.")
        }
    }

    private fun hasValidatedInternet(): Boolean {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun encode(event: AirnoteProofEvent): String = JSONObject().apply {
        put("id", event.id)
        put("type", event.type.name)
        put("timestamp", event.timestampUnixSeconds)
        put("handoffSessionId", event.handoffSessionId ?: JSONObject.NULL)
        put("paymentId", event.paymentId ?: JSONObject.NULL)
        put("transactionSignature", event.transactionSignature ?: JSONObject.NULL)
        put("validatedInternet", event.validatedInternet ?: JSONObject.NULL)
        put("detail", event.detail ?: JSONObject.NULL)
    }.toString()

    private fun decode(value: String): AirnoteProofEvent? = runCatching {
        val json = JSONObject(value)
        AirnoteProofEvent(
            id = json.getString("id"),
            type = AirnoteProofEventType.valueOf(json.getString("type")),
            timestampUnixSeconds = json.getLong("timestamp"),
            handoffSessionId = json.optStringOrNull("handoffSessionId"),
            paymentId = json.optStringOrNull("paymentId"),
            transactionSignature = json.optStringOrNull("transactionSignature"),
            validatedInternet = if (json.isNull("validatedInternet")) null else json.getBoolean("validatedInternet"),
            detail = json.optStringOrNull("detail")
        )
    }.getOrNull()

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    companion object {
        private const val PREFS = "airnote_seeker_proof_v1"
        private const val KEY_PREFIX = "event:"
    }
}
