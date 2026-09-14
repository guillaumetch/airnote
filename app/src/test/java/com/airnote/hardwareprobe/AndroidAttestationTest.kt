package com.airnote.hardwareprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAttestationTest {

    @Test
    fun parsesHighTagNumber405() {
        // [405] EXPLICIT INTEGER 1
        val der = byteArrayOf(
            0xBF.toByte(), 0x83.toByte(), 0x15,
            0x03,
            0x02, 0x01, 0x01
        )
        val tlv = AndroidAttestation.DerReader(der).readOne()
        assertEquals(2, tlv.tagClass)
        assertTrue(tlv.constructed)
        assertEquals(405, tlv.tagNumber)

        val inner = AndroidAttestation.DerReader(tlv.value).readOne()
        assertEquals(1, inner.asInteger().toInt())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedHighTag() {
        AndroidAttestation.DerReader(byteArrayOf(0xBF.toByte(), 0x83.toByte())).readOne()
    }
}
