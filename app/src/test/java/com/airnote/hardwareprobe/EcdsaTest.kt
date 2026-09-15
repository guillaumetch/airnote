package com.airnote.hardwareprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class EcdsaTest {
    @Test
    fun `to32 left pads`() {
        val out = Ecdsa.to32(BigInteger.ONE)
        assertEquals(32, out.size)
        assertEquals(1, out.last().toInt())
        assertTrue(out.dropLast(1).all { it == 0.toByte() })
    }

    @Test
    fun `low-S normalization is bounded`() {
        val high = Ecdsa.P256_ORDER - BigInteger.ONE
        val normalized = Ecdsa.normalizeLowS(high)
        assertEquals(BigInteger.ONE, normalized)
        assertTrue(normalized <= Ecdsa.P256_ORDER.shiftRight(1))
    }

    @Test
    fun `compressed public key is 33 bytes with valid prefix`() {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val pub = gen.generateKeyPair().public as ECPublicKey
        val compressed = Ecdsa.compressedPublicKey(pub)
        assertEquals(33, compressed.size)
        assertTrue(compressed[0] == 0x02.toByte() || compressed[0] == 0x03.toByte())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed DER is rejected`() {
        Ecdsa.parseDerSignature(byteArrayOf(0x30, 0x01, 0x00))
    }

    @Test
    fun `compact signature is exactly 64 bytes`() {
        val compact = Ecdsa.compact(BigInteger.ONE, BigInteger.TWO)
        assertEquals(64, compact.size)
        assertArrayEquals(Ecdsa.to32(BigInteger.ONE), compact.copyOfRange(0, 32))
        assertArrayEquals(Ecdsa.to32(BigInteger.TWO), compact.copyOfRange(32, 64))
    }

    @Test
    fun `normalize does not change low S`() {
        val low = BigInteger.valueOf(42)
        assertFalse(low > Ecdsa.P256_ORDER.shiftRight(1))
        assertEquals(low, Ecdsa.normalizeLowS(low))
    }
}
