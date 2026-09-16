package com.airnote.hardwareprobe

import android.net.Uri
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana

object AirnoteWalletAdapterFactory {
    fun create(): MobileWalletAdapter = MobileWalletAdapter(
        ConnectionIdentity(
            identityUri = Uri.parse("https://github.com/guillaumetch/airnote"),
            iconUri = Uri.parse("favicon.ico"),
            identityName = "AIRNOTE"
        )
    ).apply {
        blockchain = Solana.Devnet
    }
}
