package com.airnote.hardwareprobe

import android.net.Uri
import com.airnote.protocol.Base58
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionParams
import com.solana.mobilewalletadapter.clientlib.TransactionResult

/**
 * Product boundary for native settlement.
 *
 * Rules:
 * - AIRNOTE never imports a Solana private key.
 * - the wallet selected through MWA must equal the recipient bound into the offline transfer.
 * - wallet submission is not success; SETTLED is written only after independent finalized RPC.
 * - once a signature exists locally, reconciliation checks that signature before any resubmission.
 */
class AirnoteSettlementCoordinator(
    private val store: AirnotePaymentStore,
    private val rpc: AirnoteSolanaRpc = AirnoteSolanaRpc(),
    private val walletAdapter: MobileWalletAdapter = defaultWalletAdapter()
) {
    data class SettlementResult(
        val payment: AirnotePayment,
        val signature: String,
        val finalizedSlot: Long
    )

    suspend fun settle(paymentId: String, sender: ActivityResultSender): SettlementResult {
        val payment = requireNotNull(store.get(paymentId)) { "unknown AIRNOTE payment $paymentId" }
        require(payment.direction == PaymentDirection.RECEIVED) { "only received Airnotes can be settled here" }
        require(payment.status !in setOf(PaymentStatus.SETTLED, PaymentStatus.REJECTED, PaymentStatus.EXPIRED)) {
            "payment cannot settle from ${payment.status}"
        }
        val payload = requireNotNull(payment.protocolPayload) { "payment has no settlement payload" }

        // Idempotency fence: never submit a fresh redemption while a known signature may still land.
        payment.settlementSignature?.let { existing ->
            return reconcileExisting(paymentId, existing)
        }

        if (payment.status != PaymentStatus.SETTLING) {
            store.transition(paymentId, PaymentStatus.SETTLING)
        }

        // Do RPC, PDA and expiry work before opening a wallet association. The wallet session should
        // only prove the bound recipient and authorize a transaction that is already prepared.
        val plan = try {
            AirnoteSettlementBuilder.prepare(payload, rpc)
        } catch (error: AirnoteSettlementBuilder.AirnoteExpiredException) {
            if (PaymentLifecycle.canTransition(store.get(paymentId)!!.status, PaymentStatus.EXPIRED)) {
                store.transition(paymentId, PaymentStatus.EXPIRED)
            }
            throw error
        } catch (error: Throwable) {
            markRetryableIfPossible(paymentId, error)
            throw error
        }

        val signature = try {
            when (val result = walletAdapter.transact(sender) { authResult ->
                val wallet = authResult.accounts.firstOrNull()?.publicKey
                    ?: error("wallet authorization returned no account")
                AirnoteSettlementBuilder.requireAuthorizedRecipient(plan, wallet)
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
                ).signatures.firstOrNull() ?: error("wallet returned no transaction signature")
                Base58.encode(signatureBytes)
            }) {
                is TransactionResult.Success -> result.payload
                is TransactionResult.NoWalletFound -> throw WalletUnavailableException(result.message)
                is TransactionResult.Failure -> throw WalletSubmissionException(result.message, result.e)
            }
        } catch (error: Throwable) {
            markRetryableIfPossible(paymentId, error)
            throw error
        }

        // Critical ordering: persist before waiting for RPC. Process death after this point is safe.
        store.markSubmitted(
            paymentId,
            signature,
            lastValidBlockHeight = plan.lastValidBlockHeight
        )

        return try {
            val finalized = rpc.awaitFinalized(signature)
            val settled = store.markSettled(paymentId, signature)
            SettlementResult(settled, signature, finalized.slot)
        } catch (error: AirnoteSolanaRpc.TransactionFailedException) {
            // Chain explicitly proved failure, so a later fresh submission is safe.
            store.markSubmissionFailed(paymentId, "CHAIN_REJECTED")
            throw error
        } catch (error: Throwable) {
            // Signature may still land. Preserve it so the next tap reconciles rather than resubmits.
            markRetryableIfPossible(paymentId, error)
            throw error
        }
    }

    private suspend fun reconcileExisting(paymentId: String, signature: String): SettlementResult {
        val current = requireNotNull(store.get(paymentId)) { "unknown AIRNOTE payment $paymentId" }
        if (current.status == PaymentStatus.FAILED_RETRYABLE) {
            store.transition(paymentId, PaymentStatus.SETTLING)
        }
        return try {
            val finalized = rpc.awaitFinalized(signature)
            val settled = store.markSettled(paymentId, signature)
            SettlementResult(settled, signature, finalized.slot)
        } catch (error: AirnoteSolanaRpc.TransactionFailedException) {
            store.markSubmissionFailed(paymentId, "CHAIN_REJECTED")
            throw error
        } catch (error: Throwable) {
            markRetryableIfPossible(paymentId, error)
            throw error
        }
    }

    private fun markRetryableIfPossible(paymentId: String, error: Throwable) {
        val current = store.get(paymentId) ?: return
        if (current.status in setOf(PaymentStatus.PENDING, PaymentStatus.SETTLING, PaymentStatus.FAILED_RETRYABLE)) {
            runCatching {
                store.markRetryableFailure(paymentId, errorCode(error))
            }
        }
    }

    private fun errorCode(error: Throwable): String = when (error) {
        is AirnoteSettlementBuilder.WalletMismatchException -> "WALLET_MISMATCH"
        is WalletUnavailableException -> "WALLET_UNAVAILABLE"
        is WalletSubmissionException -> "WALLET_SUBMISSION_FAILED"
        is AirnoteSolanaRpc.FinalizationTimeoutException -> "FINALIZATION_TIMEOUT"
        is AirnoteSolanaRpc.RpcException -> "RPC_UNAVAILABLE"
        else -> error::class.java.simpleName.uppercase().take(64).ifBlank { "SETTLEMENT_ERROR" }
    }

    class WalletUnavailableException(message: String) : Exception(message)
    class WalletSubmissionException(message: String, cause: Throwable) : Exception(message, cause)

    companion object {
        fun defaultWalletAdapter(): MobileWalletAdapter = MobileWalletAdapter(
            ConnectionIdentity(
                identityUri = Uri.parse("https://github.com/guillaumetch/airnote"),
                iconUri = Uri.parse("favicon.ico"),
                identityName = "AIRNOTE"
            )
        ).apply {
            blockchain = Solana.Devnet
        }
    }
}
