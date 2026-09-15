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
                        store.reconcileSubmissionFailed(
                            payment.id,
                            signature,
                            "CHAIN_REJECTED",
                            now
                        )
                        store.expireDue(now)
                        val current = store.get(payment.id) ?: continue
                        val copy = if (current.status == PaymentStatus.EXPIRED) {
                            "The submitted transaction failed and this Airnote is now expired."
                        } else {
                            "The submitted Solana transaction failed. Open AIRNOTE to retry with your wallet."
                        }
                        AirnoteNotifications.notifyNeedsAttention(applicationContext, current, copy)
                    }

                    AirnoteSolanaRpc.SignatureState.PENDING -> {
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

    companion object {
        private const val MAX_BACKGROUND_ATTEMPTS = 8
    }
}
