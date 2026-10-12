package com.airnote.hardwareprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirnoteProofChecklistTest {
    private fun event(
        type: AirnoteProofEventType,
        t: Long,
        session: String? = null,
        payment: String? = null,
        signature: String? = null,
        internet: Boolean? = null,
        detail: String? = null
    ) = AirnoteProofEvent(
        id = "$type-$t",
        type = type,
        timestampUnixSeconds = t,
        handoffSessionId = session,
        paymentId = payment,
        transactionSignature = signature,
        validatedInternet = internet,
        detail = detail
    )

    @Test
    fun offlineTwoScanRequiresSameSessionAndNoValidatedInternet() {
        val events = listOf(
            event(AirnoteProofEventType.RECEIVER_REQUEST_QR_READY, 10, session = "run", internet = false),
            event(AirnoteProofEventType.OFFLINE_TRANSFER_ACCEPTED, 20, session = "run", payment = "p", internet = false)
        )
        val check = AirnoteProofChecklist.evaluate(events).first { it.label == "Two-scan handoff observed offline" }
        assertTrue(check.observed)
    }

    @Test
    fun onlineAcceptanceDoesNotCountAsOfflineProof() {
        val events = listOf(
            event(AirnoteProofEventType.RECEIVER_REQUEST_QR_READY, 10, session = "run", internet = false),
            event(AirnoteProofEventType.OFFLINE_TRANSFER_ACCEPTED, 20, session = "run", payment = "p", internet = true)
        )
        val check = AirnoteProofChecklist.evaluate(events).first { it.label == "Two-scan handoff observed offline" }
        assertFalse(check.observed)
    }

    @Test
    fun finalizationMustMatchPersistedSubmissionSignature() {
        val events = listOf(
            event(AirnoteProofEventType.OFFLINE_TRANSFER_ACCEPTED, 10, session = "run", payment = "p", internet = false),
            event(AirnoteProofEventType.SETTLEMENT_SUBMITTED, 20, payment = "p", signature = "sig-a"),
            event(AirnoteProofEventType.SETTLEMENT_FINALIZED_FOREGROUND, 30, payment = "p", signature = "sig-b")
        )
        val check = AirnoteProofChecklist.evaluate(events).first { it.label == "Same transaction reached finalized" }
        assertFalse(check.observed)
    }

    @Test
    fun backgroundFinalizationAndSingleSubmissionAreObserved() {
        val events = listOf(
            event(AirnoteProofEventType.RECEIVER_REQUEST_QR_READY, 1, session = "run", internet = false),
            event(AirnoteProofEventType.OFFLINE_TRANSFER_ACCEPTED, 2, session = "run", payment = "p", internet = false),
            event(AirnoteProofEventType.MWA_RECIPIENT_AUTHORIZED, 3, payment = "p"),
            event(AirnoteProofEventType.SETTLEMENT_SUBMITTED, 4, payment = "p", signature = "sig"),
            event(AirnoteProofEventType.SETTLEMENT_FINALIZED_BACKGROUND, 5, payment = "p", signature = "sig")
        )
        val checks = AirnoteProofChecklist.evaluate(events)
        assertTrue(checks.first { it.label == "Same transaction reached finalized" }.observed)
        assertTrue(checks.first { it.label == "Background reconciliation finalized" }.observed)
        assertTrue(checks.first { it.label == "Single submission in this proof run" }.observed)
    }
}
