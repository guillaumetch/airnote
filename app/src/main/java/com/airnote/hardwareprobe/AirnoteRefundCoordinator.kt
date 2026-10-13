package com.airnote.hardwareprobe

import com.airnote.protocol.Base58
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionParams
import com.solana.mobilewalletadapter.clientlib.TransactionResult

class AirnoteRefundCoordinator(
    private val store: LocalAirnoteStore,
    private val rpc: AirnoteSolanaRpc = AirnoteSolanaRpc(),
    private val walletAdapter: MobileWalletAdapter = AirnoteWalletAdapterFactory.create()
) {
    data class RefundResult(
        val note: LocalAirnote,
        val signature: String,
        val finalizedSlot: Long
    )

    suspend fun refund(noteIdHex: String, sender: ActivityResultSender): RefundResult {
        val note = requireNotNull(store.get(noteIdHex)) { "unknown local Airnote $noteIdHex" }
        require(note.state == LocalAirnoteState.READY_OFFLINE) {
            "Only an expired unsent Airnote can be refunded here"
        }

        val submission = when (val result = walletAdapter.transact(sender) { authResult ->
            val wallet = authResult.accounts.firstOrNull()?.publicKey
                ?: error("wallet authorization returned no account")
            val plan = AirnoteRefundBuilder.prepare(note, wallet, rpc)
            val params = TransactionParams(
                minContextSlot = plan.minContextSlot,
                commitment = "confirmed",
                skipPreflight = false,
                maxRetries = 5,
                waitForCommitmentToSendNextTransaction = null
            )
            val signatureBytes = signAndSendTransactions(
                arrayOf(plan.unsignedTransaction),
                params
            ).signatures.firstOrNull() ?: error("wallet returned no refund signature")
            Base58.encode(signatureBytes)
        }) {
            is TransactionResult.Success -> result.payload
            is TransactionResult.NoWalletFound -> throw WalletUnavailableException(result.message)
            is TransactionResult.Failure -> throw WalletSubmissionException(result.message, result.e)
        }

        val finalized = rpc.awaitFinalized(submission)
        val refunded = store.markRefunded(noteIdHex)
        return RefundResult(
            note = refunded,
            signature = submission,
            finalizedSlot = finalized.slot
        )
    }

    class WalletUnavailableException(message: String) : Exception(message)
    class WalletSubmissionException(message: String, cause: Throwable?) : Exception(message, cause)
}
