package com.airnote.hardwareprobe

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class AirnoteReconciliationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = AirnotePaymentStore(applicationContext)
        val rpc = AirnoteSolanaRpc()
        val now = System.currentTimeMillis() / 1000L
        var hasPendingSubmittedTransaction = false
        var hadRpcFailure = false
        var finalizedBlockHeight: Long? = null

        val submitted = store.all().filter(AirnoteReconciliationPolicy::shouldReconcileSubmitted)
        for (payment in submitted) {
            val signature = payment.settlementSignature ?: continue
            try {
                val observation = rpc.signatureObservation(signature)
                when (observation.state) {
                    AirnoteSolanaRpc.SignatureState.FINALIZED -> {
                        val settled = store.reconcileFinalized(payment.id, signature, now)
                        AirnoteNotifications.notifySettled(applicationContext, settled)
                    }

                    AirnoteSolanaRpc.SignatureState.FAILED -> {
                        handleDefinitiveFailure(
                            store = store,
                            payment = payment,
                            signature = signature,
                            now = now,
                            errorCode = "CHAIN_REJECTED",
                            copy = "The submitted Solana transaction failed. Open AIRNOTE to retry with your wallet."
                        )
                    }

                    AirnoteSolanaRpc.SignatureState.PENDING -> {
                        val lastValidBlockHeight = payment.settlementLastValidBlockHeight
                        if (lastValidBlockHeight != null && runAttemptCount > 0) {
                            val currentHeight = finalizedBlockHeight
                                ?: rpc.finalizedBlockHeight().also { finalizedBlockHeight = it }
                            if (currentHeight > lastValidBlockHeight) {
                                handleDefinitiveFailure(
                                    store = store,
                                    payment = payment,
                                    signature = signature,
                                    now = now,
                                    errorCode = "BLOCKHASH_EXPIRED",
                                    copy = "The previous Solana submission expired before landing. Open AIRNOTE to authorize a fresh retry."
                                )
                                continue
                            }
                        }
                        hasPendingSubmittedTransaction = true
                    }
                }
            } catch (_: Exception) {
                hadRpcFailure = true
            }
        }

        // Only unsigned/unsubmitted payments may be expired from local time. Known signatures must
        // first resolve against Solana, which is why expireDue deliberately skips them.
        store.expireDue(now)

        store.all()
            .filter { AirnoteReconciliationPolicy.shouldOfferSettlement(it, now) }
            .forEach { AirnoteNotifications.notifyReadyToSettle(applicationContext, it) }

        val unresolved = hasPendingSubmittedTransaction || hadRpcFailure
        if (unresolved && runAttemptCount < MAX_BACKGROUND_ATTEMPTS) {
            return Result.retry()
        }

        if (unresolved) {
            store.all()
                .filter(AirnoteReconciliationPolicy::shouldReconcileSubmitted)
                .forEach {
                    AirnoteNotifications.notifyNeedsAttention(
                        applicationContext,
                        it,
                        "AIRNOTE could not confirm final settlement in the background. Open the payment to check Solana."
                    )
                }
        }

        return Result.success()
    }

    private fun handleDefinitiveFailure(
        store: AirnotePaymentStore,
        payment: AirnotePayment,
        signature: String,
        now: Long,
        errorCode: String,
        copy: String
    ) {
        store.reconcileSubmissionFailed(payment.id, signature, errorCode, now)
        store.expireDue(now)
        val current = store.get(payment.id) ?: return
        val message = if (current.status == PaymentStatus.EXPIRED) {
            "The previous Solana submission did not land and this Airnote is now expired."
        } else {
            copy
        }
        AirnoteNotifications.notifyNeedsAttention(applicationContext, current, message)
    }

    companion object {
        private const val MAX_BACKGROUND_ATTEMPTS = 8
    }
}
