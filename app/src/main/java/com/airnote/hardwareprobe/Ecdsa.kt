package com.airnote.hardwareprobe

import java.math.BigInteger
import java.security.interfaces.ECPublicKey

object Ecdsa {
    val P256_ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    private val HALF_ORDER = P256_ORDER.shiftRight(1)

    data class Rs(val r: BigInteger, val s: BigInteger)

    fun parseDerSignature(der: ByteArray): Rs {
        require(der.size >= 8) { "DER signature too short" }
        var i = 0
        require((der[i++].toInt() and 0xff) == 0x30) { "Expected DER SEQUENCE" }
        val sequenceLength = readLength(der, i).also { i = it.second }.first
        require(i + sequenceLength == der.size) { "Invalid DER sequence length" }

        require((der[i++].toInt() and 0xff) == 0x02) { "Expected INTEGER r" }
        val rLen = readLength(der, i).also { i = it.second }.first
        require(rLen > 0 && i + rLen <= der.size) { "Invalid r length" }
        val r = BigInteger(1, der.copyOfRange(i, i + rLen))
        i += rLen

        require(i < der.size && (der[i++].toInt() and 0xff) == 0x02) { "Expected INTEGER s" }
        val sLen = readLength(der, i).also { i = it.second }.first
        require(sLen > 0 && i + sLen == der.size) { "Invalid s length" }
        val s = BigInteger(1, der.copyOfRange(i, i + sLen))

        require(r.signum() > 0 && r < P256_ORDER) { "r out of range" }
        require(s.signum() > 0 && s < P256_ORDER) { "s out of range" }
        return Rs(r, s)
    }

    fun normalizeLowS(s: BigInteger): BigInteger = if (s > HALF_ORDER) P256_ORDER - s else s

    fun compact(r: BigInteger, s: BigInteger): ByteArray = to32(r) + to32(s)

    fun compressedPublicKey(publicKey: ECPublicKey): ByteArray {
        val x = to32(publicKey.w.affineX)
        val prefix: Byte = if (publicKey.w.affineY.testBit(0)) 0x03 else 0x02
        return byteArrayOf(prefix) + x
    }

    fun to32(value: BigInteger): ByteArray {
        require(value.signum() >= 0) { "Negative integer" }
        val raw = value.toByteArray()
        val unsigned = if (raw.size > 32 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        require(unsigned.size <= 32) { "Integer does not fit 32 bytes" }
        return ByteArray(32 - unsigned.size) + unsigned
    }

    private fun readLength(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        require(offset < bytes.size) { "Missing DER length" }
        val first = bytes[offset].toInt() and 0xff
        if (first and 0x80 == 0) return first to (offset + 1)
        val count = first and 0x7f
        require(count in 1..2) { "Unsupported DER length encoding" }
        require(offset + count < bytes.size) { "Truncated DER length" }
        var value = 0
        repeat(count) { idx -> value = (value shl 8) or (bytes[offset + 1 + idx].toInt() and 0xff) }
        return value to (offset + 1 + count)
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
