package com.airnote.hardwareprobe

import android.content.Context
import com.airnote.protocol.Base58

data class AirnoteReceiveWalletBinding(
    val publicKeyBase58: String,
    val accountLabel: String?,
    val boundAtUnixSeconds: Long
)

/**
 * Stores only the public recipient identity used to build offline receive requests.
 *
 * No seed phrase, private key or MWA auth token is persisted here. Settlement still re-opens
 * the wallet through MWA and independently verifies that the approving account equals the
 * recipient embedded in the Airnote transfer.
 */
class AirnoteReceiveWalletBindingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(): AirnoteReceiveWalletBinding? {
        val publicKey = prefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        runCatching { Base58.decode32(publicKey, "receive wallet binding") }.getOrElse {
            clear()
            return null
        }
        return AirnoteReceiveWalletBinding(
            publicKeyBase58 = publicKey,
            accountLabel = prefs.getString(KEY_LABEL, null),
            boundAtUnixSeconds = prefs.getLong(KEY_BOUND_AT, 0L)
        )
    }

    fun save(publicKey: ByteArray, accountLabel: String?): AirnoteReceiveWalletBinding {
        require(publicKey.size == 32) { "Solana receive wallet public key must be 32 bytes" }
        val encoded = Base58.encode(publicKey)
        val now = System.currentTimeMillis() / 1000L
        val normalizedLabel = accountLabel?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
        check(
            prefs.edit()
                .putString(KEY_PUBLIC_KEY, encoded)
                .putString(KEY_LABEL, normalizedLabel)
                .putLong(KEY_BOUND_AT, now)
                .commit()
        ) { "could not persist receive wallet binding" }
        return AirnoteReceiveWalletBinding(encoded, normalizedLabel, now)
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREFS = "airnote_receive_wallet_binding_v1"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_LABEL = "account_label"
        private const val KEY_BOUND_AT = "bound_at"
    }
}
