package com.airnote.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/** Verifies AIRNOTE compact low-S secp256r1 signatures over canonical transfer bytes. */
object P256TransferVerifier {
    private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    private val N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    private val HALF_N = N.shiftRight(1)
    private val SQRT_EXPONENT = P.add(BigInteger.ONE).shiftRight(2)

    fun verify(compressedPublicKey: ByteArray, message: ByteArray, compactSignature: ByteArray): Boolean =
        runCatching {
            require(compressedPublicKey.size == 33) { "compressed P-256 public key must be 33 bytes" }
            require(compactSignature.size == 64) { "compact P-256 signature must be 64 bytes" }
            val prefix = compressedPublicKey[0].toInt() and 0xff
            require(prefix == 0x02 || prefix == 0x03) { "invalid compressed P-256 prefix" }

            val r = BigInteger(1, compactSignature.copyOfRange(0, 32))
            val s = BigInteger(1, compactSignature.copyOfRange(32, 64))
            require(r.signum() > 0 && r < N) { "P-256 r out of range" }
            require(s.signum() > 0 && s <= HALF_N) { "P-256 signature is not canonical low-S" }

            val x = BigInteger(1, compressedPublicKey.copyOfRange(1, 33))
            require(x < P) { "P-256 x coordinate out of range" }
            val alpha = x.modPow(BigInteger.valueOf(3), P)
                .subtract(x.multiply(BigInteger.valueOf(3)))
                .add(B)
                .mod(P)
            var y = alpha.modPow(SQRT_EXPONENT, P)
            require(y.multiply(y).mod(P) == alpha) { "compressed P-256 point is not on curve" }
            val wantsOdd = prefix == 0x03
            if (y.testBit(0) != wantsOdd) y = P.subtract(y)

            val parameters = AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec("secp256r1"))
            }.getParameterSpec(ECParameterSpec::class.java)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                ECPublicKeySpec(ECPoint(x, y), parameters)
            )

            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(message)
            verifier.verify(compactToDer(r, s))
        }.getOrDefault(false)

    private fun compactToDer(r: BigInteger, s: BigInteger): ByteArray {
        val rBytes = derInteger(r)
        val sBytes = derInteger(s)
        val bodyLength = 2 + rBytes.size + 2 + sBytes.size
        require(bodyLength < 128)
        return byteArrayOf(0x30, bodyLength.toByte(), 0x02, rBytes.size.toByte()) + rBytes +
            byteArrayOf(0x02, sBytes.size.toByte()) + sBytes
    }

    private fun derInteger(value: BigInteger): ByteArray {
        var bytes = value.toByteArray()
        while (bytes.size > 1 && bytes[0] == 0.toByte() && bytes[1].toInt() and 0x80 == 0) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        return if (bytes[0].toInt() and 0x80 != 0) byteArrayOf(0) + bytes else bytes
    }
}
