package com.airnote.hardwareprobe

/** Pure policy for Gate 11 background work. */
object AirnoteReconciliationPolicy {
    fun shouldReconcileSubmitted(payment: AirnotePayment): Boolean =
        payment.direction == PaymentDirection.RECEIVED &&
            payment.settlementSignature != null &&
            payment.status in setOf(
                PaymentStatus.PENDING,
                PaymentStatus.SETTLING,
                PaymentStatus.FAILED_RETRYABLE,
                // Gate 11 can repair legacy local expiry guesses when a known tx later proves final.
                PaymentStatus.EXPIRED
            )

    fun shouldOfferSettlement(payment: AirnotePayment, nowUnixSeconds: Long): Boolean =
        payment.direction == PaymentDirection.RECEIVED &&
            payment.protocolPayload != null &&
            payment.settlementSignature == null &&
            payment.status in setOf(PaymentStatus.PENDING, PaymentStatus.FAILED_RETRYABLE) &&
            nowUnixSeconds < payment.expiresAtUnixSeconds

    fun shouldDeferLocalExpiry(payment: AirnotePayment): Boolean =
        payment.settlementSignature != null &&
            payment.status in setOf(
                PaymentStatus.PENDING,
                PaymentStatus.SETTLING,
                PaymentStatus.FAILED_RETRYABLE
            )
}
