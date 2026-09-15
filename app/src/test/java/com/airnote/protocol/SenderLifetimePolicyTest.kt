package com.airnote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SenderLifetimePolicyTest {
    @Test
    fun presetsAreStable() {
        assertEquals(listOf(900L, 3600L, 21600L, 86400L), SenderLifetimePolicy.presets.map { it.seconds })
        assertEquals(3600L, SenderLifetimePolicy.DEFAULT_SECONDS)
    }

    @Test
    fun customMinutesRespectFiveMinuteToTwentyFourHourPolicy() {
        assertEquals(300L, SenderLifetimePolicy.fromMinutes(5).seconds)
        assertEquals(5400L, SenderLifetimePolicy.fromMinutes(90).seconds)
        assertEquals(86400L, SenderLifetimePolicy.fromMinutes(1440).seconds)
        assertThrows(IllegalArgumentException::class.java) { SenderLifetimePolicy.fromMinutes(4) }
        assertThrows(IllegalArgumentException::class.java) { SenderLifetimePolicy.fromMinutes(1441) }
    }

    @Test
    fun exposureLabelsTrackLongerOfflineRiskWindows() {
        assertEquals("LOW OFFLINE EXPOSURE", SenderLifetimePolicy.lifetime(900).exposureLabel)
        assertEquals("STANDARD", SenderLifetimePolicy.lifetime(3600).exposureLabel)
        assertEquals("EXTENDED", SenderLifetimePolicy.lifetime(21600).exposureLabel)
        assertEquals("MAXIMUM", SenderLifetimePolicy.lifetime(86400).exposureLabel)
    }

    @Test
    fun durationLabelsAreHumanReadable() {
        assertEquals("15 min", SenderLifetimePolicy.lifetime(900).label)
        assertEquals("1 hour", SenderLifetimePolicy.lifetime(3600).label)
        assertEquals("1h 30m", SenderLifetimePolicy.fromMinutes(90).label)
        assertEquals("24 hours", SenderLifetimePolicy.lifetime(86400).label)
    }
}
