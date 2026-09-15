package com.airnote.hardwareprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirnoteReconciliationPolicyTest {
    private fun payment(
        status: PaymentStatus = PaymentStatus.PENDING,
        direction: PaymentDirection = PaymentDirection.RECEIVED,
        signature: String? = null,
        expiresAt: Long = 2_000L,
        payload: String? = "payload"
    ) = AirnotePayment(
        id = "note",
        direction = direction,
        amountBaseUnits = 5_000_000L,
        createdAtUnixSeconds = 1_000L,
        expiresAtUnixSeconds = expiresAt,
        status = status,
        settlementSignature = signature,
        protocolPayload = payload
    )

    @Test
    fun unsignedPendingPaymentIsOfferedWhenStillLive() {
        assertTrue(AirnoteReconciliationPolicy.shouldOfferSettlement(payment(), 1_500L))
        assertFalse(AirnoteReconciliationPolicy.shouldOfferSettlement(payment(), 2_000L))
    }

    @Test
    fun submittedPaymentIsReconciledNotOfferedForFreshSigning() {
        val submitted = payment(status = PaymentStatus.SETTLING, signature = "solana-signature")
        assertTrue(AirnoteReconciliationPolicy.shouldReconcileSubmitted(submitted))
        assertTrue(AirnoteReconciliationPolicy.shouldDeferLocalExpiry(submitted))
        assertFalse(AirnoteReconciliationPolicy.shouldOfferSettlement(submitted, 1_500L))
    }

    @Test
    fun legacyExpiredSubmissionCanBeRepairedFromChainTruth() {
        val expired = payment(status = PaymentStatus.EXPIRED, signature = "solana-signature")
        assertTrue(AirnoteReconciliationPolicy.shouldReconcileSubmitted(expired))
        assertFalse(AirnoteReconciliationPolicy.shouldDeferLocalExpiry(expired))
    }

    @Test
    fun senderHistoryNeverTriggersRecipientSettlementWork() {
        val sent = payment(direction = PaymentDirection.SENT, signature = "solana-signature")
        assertFalse(AirnoteReconciliationPolicy.shouldReconcileSubmitted(sent))
        assertFalse(AirnoteReconciliationPolicy.shouldOfferSettlement(sent, 1_500L))
    }
}
