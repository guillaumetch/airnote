package com.airnote.hardwareprobe

enum class AirnoteProofEventType {
    STRONGBOX_PROBE_PASS,
    STRONGBOX_PROBE_NOT_PASS,
    RECEIVER_REQUEST_QR_READY,
    SENDER_SIGNED_TRANSFER_QR_READY,
    OFFLINE_TRANSFER_ACCEPTED,
    MWA_RECIPIENT_AUTHORIZED,
    SETTLEMENT_SUBMITTED,
    SETTLEMENT_FINALIZED_FOREGROUND,
    SETTLEMENT_FINALIZED_BACKGROUND,
    SETTLEMENT_DEFINITIVE_FAILURE,
    READY_TO_SETTLE_BACKGROUND
}

data class AirnoteProofEvent(
    val id: String,
    val type: AirnoteProofEventType,
    val timestampUnixSeconds: Long,
    val handoffSessionId: String? = null,
    val paymentId: String? = null,
    val transactionSignature: String? = null,
    val validatedInternet: Boolean? = null,
    val detail: String? = null
)

data class AirnoteProofCheck(
    val label: String,
    val observed: Boolean,
    val evidence: String
)

object AirnoteProofChecklist {
    fun evaluate(events: List<AirnoteProofEvent>): List<AirnoteProofCheck> {
        val ordered = events.sortedBy { it.timestampUnixSeconds }
        val strongBox = ordered.lastOrNull { it.type == AirnoteProofEventType.STRONGBOX_PROBE_PASS }

        val offlineAccepted = ordered.lastOrNull { accepted ->
            if (accepted.type != AirnoteProofEventType.OFFLINE_TRANSFER_ACCEPTED || accepted.validatedInternet != false) return@lastOrNull false
            val session = accepted.handoffSessionId ?: return@lastOrNull false
            ordered.any { request ->
                request.type == AirnoteProofEventType.RECEIVER_REQUEST_QR_READY &&
                    request.handoffSessionId == session &&
                    request.validatedInternet == false &&
                    request.timestampUnixSeconds <= accepted.timestampUnixSeconds
            }
        }

        val latestPaymentId = offlineAccepted?.paymentId
            ?: ordered.lastOrNull { it.paymentId != null }?.paymentId

        fun eventForPayment(vararg types: AirnoteProofEventType): AirnoteProofEvent? =
            ordered.lastOrNull {
                it.type in types && (latestPaymentId == null || it.paymentId == latestPaymentId)
            }

        val walletAuth = eventForPayment(AirnoteProofEventType.MWA_RECIPIENT_AUTHORIZED)
        val submitted = eventForPayment(AirnoteProofEventType.SETTLEMENT_SUBMITTED)
        val finalized = eventForPayment(
            AirnoteProofEventType.SETTLEMENT_FINALIZED_FOREGROUND,
            AirnoteProofEventType.SETTLEMENT_FINALIZED_BACKGROUND
        )
        val backgroundFinalized = eventForPayment(AirnoteProofEventType.SETTLEMENT_FINALIZED_BACKGROUND)

        val sameTransactionFinalized = submitted != null && finalized != null &&
            submitted.transactionSignature != null &&
            submitted.transactionSignature == finalized.transactionSignature

        val submissionCount = if (latestPaymentId == null) 0 else ordered.count {
            it.type == AirnoteProofEventType.SETTLEMENT_SUBMITTED && it.paymentId == latestPaymentId
        }

        return listOf(
            AirnoteProofCheck(
                "StrongBox hardware probe",
                strongBox != null,
                strongBox?.detail ?: "No passing StrongBox probe recorded on this device."
            ),
            AirnoteProofCheck(
                "Two-scan handoff observed offline",
                offlineAccepted != null,
                offlineAccepted?.let { "Scan 1 and Scan 2 acceptance were recorded with validated internet unavailable." }
                    ?: "Need receiver request + accepted transfer in the same session with validated internet unavailable."
            ),
            AirnoteProofCheck(
                "Recipient wallet authorized through MWA",
                walletAuth != null,
                walletAuth?.detail ?: "No recipient-wallet authorization observed yet."
            ),
            AirnoteProofCheck(
                "Solana redemption submitted",
                submitted != null,
                submitted?.transactionSignature ?: "No submitted Solana signature recorded yet."
            ),
            AirnoteProofCheck(
                "Same transaction reached finalized",
                sameTransactionFinalized,
                if (sameTransactionFinalized) finalized!!.transactionSignature!! else "Need finalized evidence for the exact persisted submission signature."
            ),
            AirnoteProofCheck(
                "Background reconciliation finalized",
                backgroundFinalized != null,
                backgroundFinalized?.let { "WorkManager observed finalized settlement for ${it.transactionSignature}." }
                    ?: "Optional crash/recovery proof not observed yet."
            ),
            AirnoteProofCheck(
                "Single submission in this proof run",
                latestPaymentId != null && submissionCount == 1,
                if (latestPaymentId == null) "No payment proof run selected yet." else "Observed $submissionCount settlement submission event(s) for the latest payment."
            )
        )
    }
}
