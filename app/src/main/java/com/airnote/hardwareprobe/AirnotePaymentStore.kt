package com.airnote.hardwareprobe

import android.content.Context
import android.util.Base64
import org.json.JSONObject

class AirnotePaymentStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<AirnotePayment> = prefs.all.entries
        .asSequence()
        .filter { it.key.startsWith(PREFIX) }
        .mapNotNull { (_, value) -> (value as? String)?.let(::decode) }
        .sortedByDescending { it.createdAtUnixSeconds }
        .toList()

    fun get(id: String): AirnotePayment? = prefs.getString(PREFIX + id, null)?.let(::decode)

    fun recordReceived(
        id: String,
        amountBaseUnits: Long,
        createdAtUnixSeconds: Long,
        expiresAtUnixSeconds: Long,
        counterparty: String?,
        protocolPayload: String
    ): AirnotePayment {
        get(id)?.let { return it }
        val accepted = AirnotePayment(
            id = id,
            direction = PaymentDirection.RECEIVED,
            amountBaseUnits = amountBaseUnits,
            createdAtUnixSeconds = createdAtUnixSeconds,
            expiresAtUnixSeconds = expiresAtUnixSeconds,
            status = PaymentStatus.ACCEPTED_OFFLINE,
            counterparty = counterparty,
            protocolPayload = protocolPayload
        )
        val pending = PaymentLifecycle.transition(accepted, PaymentStatus.PENDING, createdAtUnixSeconds)
        save(pending)
        // If the receiver is offline this waits for connectivity, then surfaces "ready to settle".
        AirnoteReconciliationScheduler.enqueue(appContext)
        return pending
    }

    fun transition(id: String, to: PaymentStatus, nowUnixSeconds: Long = System.currentTimeMillis() / 1000L): AirnotePayment {
        val current = requireNotNull(get(id)) { "unknown AIRNOTE payment $id" }
        val next = PaymentLifecycle.transition(current, to, nowUnixSeconds)
        save(next)
        return next
    }

    /**
     * Persist the Solana signature before waiting for finalization.
     *
     * This is the local idempotency fence: after the wallet reports submission, an app restart,
     * process death, or RPC timeout reconciles this exact signature instead of constructing a fresh
     * redemption transaction.
     */
    fun markSubmitted(
        id: String,
        signature: String,
        nowUnixSeconds: Long = System.currentTimeMillis() / 1000L
    ): AirnotePayment {
        require(signature.isNotBlank()) { "settlement signature is empty" }
        val current = requireNotNull(get(id)) { "unknown AIRNOTE payment $id" }
        val settling = when (current.status) {
            PaymentStatus.PENDING, PaymentStatus.FAILED_RETRYABLE ->
                PaymentLifecycle.transition(current, PaymentStatus.SETTLING, nowUnixSeconds)
            PaymentStatus.SETTLING -> current
            else -> error("payment is not ready to submit from ${current.status}")
        }
        val submitted = settling.copy(settlementSignature = signature, errorCode = null)
        save(submitted)
        // Persisted WorkManager job survives process death and reconciles this exact signature.
        AirnoteReconciliationScheduler.enqueue(appContext)
        return submitted
    }

    fun markSettled(id: String, signature: String, nowUnixSeconds: Long = System.currentTimeMillis() / 1000L): AirnotePayment {
        val current = requireNotNull(get(id)) { "unknown AIRNOTE payment $id" }
        require(current.status in setOf(PaymentStatus.SETTLING, PaymentStatus.PENDING, PaymentStatus.FAILED_RETRYABLE)) {
            "payment is not ready to settle"
        }
        val settling = when (current.status) {
            PaymentStatus.PENDING, PaymentStatus.FAILED_RETRYABLE ->
                PaymentLifecycle.transition(current, PaymentStatus.SETTLING, nowUnixSeconds)
            else -> current
        }
        val settled = PaymentLifecycle.transition(settling, PaymentStatus.SETTLED, nowUnixSeconds)
            .copy(settlementSignature = signature, errorCode = null)
        save(settled)
        return settled
    }

    /** Temporary failure where a submitted signature may still be live. Preserve that signature. */
    fun markRetryableFailure(id: String, errorCode: String, nowUnixSeconds: Long = System.currentTimeMillis() / 1000L): AirnotePayment {
        val current = requireNotNull(get(id)) { "unknown AIRNOTE payment $id" }
        val settling = when (current.status) {
            PaymentStatus.PENDING, PaymentStatus.FAILED_RETRYABLE ->
                PaymentLifecycle.transition(current, PaymentStatus.SETTLING, nowUnixSeconds)
            PaymentStatus.SETTLING -> current
            else -> error("payment is not in a retryable settlement state")
        }
        val failed = PaymentLifecycle.transition(settling, PaymentStatus.FAILED_RETRYABLE, nowUnixSeconds)
            .copy(errorCode = errorCode)
        save(failed)
        if (failed.settlementSignature != null) AirnoteReconciliationScheduler.enqueue(appContext)
        return failed
    }

    /**
     * A chain status explicitly proved the previous submission failed. Clear the signature so a
     * later user-initiated retry is allowed to build and submit a fresh transaction.
     */
    fun markSubmissionFailed(id: String, errorCode: String, nowUnixSeconds: Long = System.currentTimeMillis() / 1000L): AirnotePayment {
        val current = requireNotNull(get(id)) { "unknown AIRNOTE payment $id" }
        val settling = when (current.status) {
            PaymentStatus.PENDING, PaymentStatus.FAILED_RETRYABLE ->
                PaymentLifecycle.transition(current, PaymentStatus.SETTLING, nowUnixSeconds)
            PaymentStatus.SETTLING -> current
            else -> error("payment is not in a submitted settlement state")
        }
        val failed = PaymentLifecycle.transition(settling, PaymentStatus.FAILED_RETRYABLE, nowUnixSeconds)
            .copy(settlementSignature = null, errorCode = errorCode)
        save(failed)
        return failed
    }

    fun expireDue(nowUnixSeconds: Long = System.currentTimeMillis() / 1000L) {
        all().filter {
            it.status !in setOf(PaymentStatus.SETTLED, PaymentStatus.REJECTED, PaymentStatus.EXPIRED) &&
                !AirnoteReconciliationPolicy.shouldDeferLocalExpiry(it) &&
                nowUnixSeconds >= it.expiresAtUnixSeconds
        }.forEach { payment ->
            if (PaymentLifecycle.canTransition(payment.status, PaymentStatus.EXPIRED)) {
                save(PaymentLifecycle.transition(payment, PaymentStatus.EXPIRED, nowUnixSeconds))
            }
        }
    }

    private fun save(payment: AirnotePayment) {
        prefs.edit().putString(PREFIX + payment.id, encode(payment)).apply()
    }

    private fun encode(payment: AirnotePayment): String = JSONObject().apply {
        put("id", payment.id)
        put("direction", payment.direction.name)
        put("amountBaseUnits", payment.amountBaseUnits)
        put("createdAt", payment.createdAtUnixSeconds)
        put("expiresAt", payment.expiresAtUnixSeconds)
        put("status", payment.status.name)
        put("counterparty", payment.counterparty ?: JSONObject.NULL)
        put("settlementSignature", payment.settlementSignature ?: JSONObject.NULL)
        put("lastSettlementAttempt", payment.lastSettlementAttemptUnixSeconds ?: JSONObject.NULL)
        put("retryCount", payment.retryCount)
        put("errorCode", payment.errorCode ?: JSONObject.NULL)
        put("protocolPayload", payment.protocolPayload?.let { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) } ?: JSONObject.NULL)
    }.toString()

    private fun decode(value: String): AirnotePayment? = runCatching {
        val json = JSONObject(value)
        AirnotePayment(
            id = json.getString("id"),
            direction = PaymentDirection.valueOf(json.getString("direction")),
            amountBaseUnits = json.getLong("amountBaseUnits"),
            createdAtUnixSeconds = json.getLong("createdAt"),
            expiresAtUnixSeconds = json.getLong("expiresAt"),
            status = PaymentStatus.valueOf(json.getString("status")),
            counterparty = json.optStringOrNull("counterparty"),
            settlementSignature = json.optStringOrNull("settlementSignature"),
            lastSettlementAttemptUnixSeconds = if (json.isNull("lastSettlementAttempt")) null else json.getLong("lastSettlementAttempt"),
            retryCount = json.optInt("retryCount", 0),
            errorCode = json.optStringOrNull("errorCode"),
            protocolPayload = json.optStringOrNull("protocolPayload")?.let {
                String(Base64.decode(it, Base64.NO_WRAP))
            }
        )
    }.getOrNull()

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    companion object {
        private const val PREFS = "airnote_payment_history_v1"
        private const val PREFIX = "payment:"
    }
}
