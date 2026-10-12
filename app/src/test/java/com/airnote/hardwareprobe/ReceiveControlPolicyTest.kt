package com.airnote.hardwareprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveControlPolicyTest {
    @Test
    fun noWalletBindingCannotCreateRequest() {
        val state = ReceiveControlPolicy.derive(
            hasWalletBinding = false,
            hasActiveRequest = false,
            hasAcceptedPayment = false
        )

        assertTrue(state.selectWalletEnabled)
        assertFalse(state.requestEnabled)
        assertEquals(ReceiveControlPolicy.SHOW_REQUEST_LABEL, state.requestLabel)
    }

    @Test
    fun boundIdleReceiverCanStartScanOne() {
        val state = ReceiveControlPolicy.derive(
            hasWalletBinding = true,
            hasActiveRequest = false,
            hasAcceptedPayment = false
        )

        assertTrue(state.selectWalletEnabled)
        assertTrue(state.requestEnabled)
        assertEquals(ReceiveControlPolicy.SHOW_REQUEST_LABEL, state.requestLabel)
    }

    @Test
    fun activeRequestLocksWalletAndRequestRegeneration() {
        val state = ReceiveControlPolicy.derive(
            hasWalletBinding = true,
            hasActiveRequest = true,
            hasAcceptedPayment = false
        )

        assertFalse(state.selectWalletEnabled)
        assertFalse(state.requestEnabled)
        assertEquals(ReceiveControlPolicy.SHOW_REQUEST_LABEL, state.requestLabel)
    }

    @Test
    fun acceptedPaymentMakesNextReceiveExplicit() {
        val state = ReceiveControlPolicy.derive(
            hasWalletBinding = true,
            hasActiveRequest = false,
            hasAcceptedPayment = true
        )

        assertTrue(state.selectWalletEnabled)
        assertTrue(state.requestEnabled)
        assertEquals(ReceiveControlPolicy.RECEIVE_ANOTHER_LABEL, state.requestLabel)
    }
}
