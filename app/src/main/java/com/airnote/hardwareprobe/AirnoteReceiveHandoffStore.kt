package com.airnote.hardwareprobe

import android.content.Context
import com.airnote.protocol.Gate04Wire

data class AirnoteReceiveHandoffSession(
    val sessionId: String,
    val requestQr: String,
    val expiresAtUnixSeconds: Long
)

/**
 * Persists the exact active receiver request so a handoff survives activity/process switches.
 *
 * This store contains no private key material: only the public recipient request QR, a local
 * correlation/session id, and its expiry. The request itself is canonical-decoded on restore.
 */
class AirnoteReceiveHandoffStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(sessionId: String, requestQr: String): AirnoteReceiveHandoffSession {
        require(sessionId.isNotBlank()) { "handoff session id must not be blank" }
        val request = Gate04Wire.decodeRequestQr(requestQr)
        val expiresAt = request.requestExpiresAtUnixSeconds
        check(
            prefs.edit()
                .putString(KEY_SESSION_ID, sessionId)
                .putString(KEY_REQUEST_QR, requestQr)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .commit()
        ) { "could not persist active receive handoff" }
        return AirnoteReceiveHandoffSession(sessionId, requestQr, expiresAt)
    }

    fun current(nowUnixSeconds: Long): AirnoteReceiveHandoffSession? {
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val requestQr = prefs.getString(KEY_REQUEST_QR, null) ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val request = runCatching { Gate04Wire.decodeRequestQr(requestQr) }.getOrElse {
            clear()
            return null
        }
        if (
            expiresAt <= nowUnixSeconds ||
            request.requestExpiresAtUnixSeconds != expiresAt
        ) {
            clear()
            return null
        }
        return AirnoteReceiveHandoffSession(sessionId, requestQr, expiresAt)
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREFS = "airnote_receive_handoff_v1"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_REQUEST_QR = "request_qr"
        private const val KEY_EXPIRES_AT = "expires_at"
    }
}
