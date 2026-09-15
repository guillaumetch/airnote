package com.airnote.protocol

import com.airnote.hardwareprobe.Ecdsa
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class P256TransferVerifierTest {
    @Test
    fun verifiesCompactLowSSignatureAndRejectsMutation() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val message = "AIRNOTE Gate 04 offline transfer".toByteArray()

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(message)
        val rs = Ecdsa.parseDerSignature(signer.sign())
        val compact = Ecdsa.compact(rs.r, Ecdsa.normalizeLowS(rs.s))
        val compressed = Ecdsa.compressedPublicKey(pair.public as ECPublicKey)

        assertTrue(P256TransferVerifier.verify(compressed, message, compact))
        val changed = message.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFalse(P256TransferVerifier.verify(compressed, changed, compact))
    }

    @Test
    fun rejectsMalformedSignatureAndKey() {
        assertFalse(P256TransferVerifier.verify(ByteArray(33), byteArrayOf(1), ByteArray(64)))
        assertFalse(P256TransferVerifier.verify(byteArrayOf(0x02) + ByteArray(32), byteArrayOf(1), ByteArray(63)))
    }
}
