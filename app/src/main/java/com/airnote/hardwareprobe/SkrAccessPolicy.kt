package com.airnote.hardwareprobe

object SkrAccessPolicy {
    const val STANDARD_MAX_AMOUNT_USDC = 5
    const val STANDARD_MAX_LIFETIME_SECONDS = 6L * 60L * 60L
    const val RESERVE_PLUS_MAX_AMOUNT_USDC = 10
    const val RESERVE_PLUS_MAX_LIFETIME_SECONDS = 24L * 60L * 60L

    fun requiresReservePlus(amountUsdc: Int, lifetimeSeconds: Long): Boolean =
        amountUsdc > STANDARD_MAX_AMOUNT_USDC || lifetimeSeconds > STANDARD_MAX_LIFETIME_SECONDS

    fun canCreate(amountUsdc: Int, lifetimeSeconds: Long, skrHolder: Boolean): Boolean =
        !requiresReservePlus(amountUsdc, lifetimeSeconds) || skrHolder

    fun denialMessage(amountUsdc: Int, lifetimeSeconds: Long): String =
        buildString {
            append("SKR RESERVE+ REQUIRED\n")
            append("Standard AIRNOTE is capped at ")
            append(STANDARD_MAX_AMOUNT_USDC)
            append(" USDC / ")
            append(STANDARD_MAX_LIFETIME_SECONDS / 3600L)
            append("h. Verify a wallet holding liquid SKR to unlock ")
            append(RESERVE_PLUS_MAX_AMOUNT_USDC)
            append(" USDC / ")
            append(RESERVE_PLUS_MAX_LIFETIME_SECONDS / 3600L)
            append("h.")
        }
}
