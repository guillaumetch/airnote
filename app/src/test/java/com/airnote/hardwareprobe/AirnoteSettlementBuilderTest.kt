package com.airnote.hardwareprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AirnoteSettlementBuilderTest {
    @Test
    fun p256InstructionMatchesGate05Layout() {
        val owner = ByteArray(33).also {
            it[0] = 0x02
            for (i in 1 until it.size) it[i] = i.toByte()
        }
        val signature = ByteArray(64) { (0x40 + it).toByte() }
        val message = ByteArray(124) { (it xor 0x5a).toByte() }

        val data = AirnoteSettlementBuilder.p256InstructionData(owner, signature, message)

        assertEquals(113 + message.size, data.size)
        assertEquals(1, data[0].toInt())
        assertEquals(0, data[1].toInt())
        assertEquals(16, u16(data, 2))
        assertEquals(0xffff, u16(data, 4))
        assertEquals(80, u16(data, 6))
        assertEquals(0xffff, u16(data, 8))
        assertEquals(113, u16(data, 10))
        assertEquals(124, u16(data, 12))
        assertEquals(0xffff, u16(data, 14))
        assertArrayEquals(signature, data.copyOfRange(16, 80))
        assertArrayEquals(owner, data.copyOfRange(80, 113))
        assertArrayEquals(message, data.copyOfRange(113, data.size))
    }

    @Test
    fun redeemInstructionMatchesAnchorDiscriminatorAndWidths() {
        val nonce = ByteArray(32) { it.toByte() }
        val expiresAt = 1_900_000_000L

        val data = AirnoteSettlementBuilder.redeemInstructionData(nonce, expiresAt)

        assertEquals(48, data.size)
        assertEquals("d61abce427f0195a", data.copyOfRange(0, 8).hex())
        assertArrayEquals(nonce, data.copyOfRange(8, 40))
        assertEquals(expiresAt, ByteBuffer.wrap(data, 40, 8).order(ByteOrder.LITTLE_ENDIAN).long)
    }

    @Test(expected = IllegalArgumentException::class)
    fun p256RejectsWrongSignatureWidth() {
        val owner = ByteArray(33).also { it[0] = 0x02 }
        AirnoteSettlementBuilder.p256InstructionData(owner, ByteArray(63), ByteArray(124))
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
