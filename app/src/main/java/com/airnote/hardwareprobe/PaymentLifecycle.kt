package com.airnote.hardwareprobe

enum class PaymentDirection { SENT, RECEIVED }

enum class PaymentStatus {
    CREATED,
    ACCEPTED_OFFLINE,
    PENDING,
    SETTLING,
    SETTLED,
    FAILED_RETRYABLE,
    REJECTED,
    EXPIRED
}

data class AirnotePayment(
    val id: String,
    val direction: PaymentDirection,
    val amountBaseUnits: Long,
    val createdAtUnixSeconds: Long,
    val expiresAtUnixSeconds: Long,
    val status: PaymentStatus,
    val counterparty: String? = null,
    val settlementSignature: String? = null,
    val lastSettlementAttemptUnixSeconds: Long? = null,
    val retryCount: Int = 0,
    val errorCode: String? = null,
    val protocolPayload: String? = null
)

object PaymentLifecycle {
    private val allowed = mapOf(
        PaymentStatus.CREATED to setOf(PaymentStatus.ACCEPTED_OFFLINE, PaymentStatus.PENDING, PaymentStatus.EXPIRED),
        PaymentStatus.ACCEPTED_OFFLINE to setOf(PaymentStatus.PENDING, PaymentStatus.EXPIRED),
        PaymentStatus.PENDING to setOf(PaymentStatus.SETTLING, PaymentStatus.EXPIRED, PaymentStatus.REJECTED),
        PaymentStatus.SETTLING to setOf(PaymentStatus.SETTLED, PaymentStatus.FAILED_RETRYABLE, PaymentStatus.REJECTED, PaymentStatus.EXPIRED),
        PaymentStatus.FAILED_RETRYABLE to setOf(PaymentStatus.SETTLING, PaymentStatus.EXPIRED, PaymentStatus.REJECTED),
        PaymentStatus.SETTLED to emptySet(),
        PaymentStatus.REJECTED to emptySet(),
        PaymentStatus.EXPIRED to emptySet()
    )

    fun canTransition(from: PaymentStatus, to: PaymentStatus): Boolean =
        from == to || allowed[from].orEmpty().contains(to)

    fun transition(payment: AirnotePayment, to: PaymentStatus, nowUnixSeconds: Long): AirnotePayment {
        require(canTransition(payment.status, to)) { "invalid AIRNOTE payment transition ${payment.status} -> $to" }
        require(payment.status != PaymentStatus.SETTLED || to == PaymentStatus.SETTLED) { "settled payment cannot regress" }
        return payment.copy(
            status = to,
            lastSettlementAttemptUnixSeconds = if (to == PaymentStatus.SETTLING) nowUnixSeconds else payment.lastSettlementAttemptUnixSeconds,
            retryCount = if (to == PaymentStatus.SETTLING && payment.status == PaymentStatus.FAILED_RETRYABLE) payment.retryCount + 1 else payment.retryCount
        )
    }
}
