package com.airnote.hardwareprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentLifecycleTest {
    private fun payment(status: PaymentStatus) = AirnotePayment(
        id = "abc",
        direction = PaymentDirection.RECEIVED,
        amountBaseUnits = 5_000_000,
        createdAtUnixSeconds = 100,
        expiresAtUnixSeconds = 1_000,
        status = status
    )

    @Test
    fun receivedPaymentCanProgressToSettled() {
        var value = payment(PaymentStatus.ACCEPTED_OFFLINE)
        value = PaymentLifecycle.transition(value, PaymentStatus.PENDING, 110)
        value = PaymentLifecycle.transition(value, PaymentStatus.SETTLING, 120)
        value = PaymentLifecycle.transition(value, PaymentStatus.SETTLED, 130)
        assertEquals(PaymentStatus.SETTLED, value.status)
        assertEquals(120, value.lastSettlementAttemptUnixSeconds)
    }

    @Test
    fun settledCannotRegress() {
        assertFalse(PaymentLifecycle.canTransition(PaymentStatus.SETTLED, PaymentStatus.PENDING))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRegressionThrows() {
        PaymentLifecycle.transition(payment(PaymentStatus.SETTLED), PaymentStatus.PENDING, 200)
    }

    @Test
    fun retryIncrementsOnlyWhenRetrying() {
        var value = payment(PaymentStatus.PENDING)
        value = PaymentLifecycle.transition(value, PaymentStatus.SETTLING, 120)
        value = PaymentLifecycle.transition(value, PaymentStatus.FAILED_RETRYABLE, 121)
        value = PaymentLifecycle.transition(value, PaymentStatus.SETTLING, 130)
        assertEquals(1, value.retryCount)
        assertEquals(130, value.lastSettlementAttemptUnixSeconds)
    }

    @Test
    fun terminalStatesRemainTerminal() {
        assertTrue(PaymentLifecycle.canTransition(PaymentStatus.EXPIRED, PaymentStatus.EXPIRED))
        assertFalse(PaymentLifecycle.canTransition(PaymentStatus.EXPIRED, PaymentStatus.SETTLING))
        assertFalse(PaymentLifecycle.canTransition(PaymentStatus.REJECTED, PaymentStatus.PENDING))
    }
}
