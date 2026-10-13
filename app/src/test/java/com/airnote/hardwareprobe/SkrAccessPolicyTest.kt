package com.airnote.hardwareprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkrAccessPolicyTest {
    @Test
    fun standardTierAllowsFiveUsdcForSixHours() {
        assertTrue(SkrAccessPolicy.canCreate(5, 6L * 60L * 60L, skrHolder = false))
    }

    @Test
    fun standardTierRejectsTenUsdc() {
        assertFalse(SkrAccessPolicy.canCreate(10, 60L * 60L, skrHolder = false))
    }

    @Test
    fun standardTierRejectsTwentyFourHours() {
        assertFalse(SkrAccessPolicy.canCreate(1, 24L * 60L * 60L, skrHolder = false))
    }

    @Test
    fun skrHolderUnlocksReservePlusEnvelope() {
        assertTrue(SkrAccessPolicy.canCreate(10, 24L * 60L * 60L, skrHolder = true))
    }
}
