package com.airnote.hardwareprobe

data class ReceiveControlState(
    val selectWalletEnabled: Boolean,
    val requestEnabled: Boolean,
    val requestLabel: String
)

object ReceiveControlPolicy {
    const val SHOW_REQUEST_LABEL = "SHOW REQUEST QR — 1 OF 2"
    const val RECEIVE_ANOTHER_LABEL = "RECEIVE ANOTHER AIRNOTE"

    fun derive(
        hasWalletBinding: Boolean,
        hasActiveRequest: Boolean,
        hasAcceptedPayment: Boolean
    ): ReceiveControlState {
        if (!hasWalletBinding) {
            return ReceiveControlState(
                selectWalletEnabled = !hasActiveRequest,
                requestEnabled = false,
                requestLabel = SHOW_REQUEST_LABEL
            )
        }

        if (hasActiveRequest) {
            return ReceiveControlState(
                selectWalletEnabled = false,
                requestEnabled = false,
                requestLabel = SHOW_REQUEST_LABEL
            )
        }

        return ReceiveControlState(
            selectWalletEnabled = true,
            requestEnabled = true,
            requestLabel = if (hasAcceptedPayment) RECEIVE_ANOTHER_LABEL else SHOW_REQUEST_LABEL
        )
    }
}
