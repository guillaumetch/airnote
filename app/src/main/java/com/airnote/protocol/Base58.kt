package com.airnote.protocol

import java.math.BigInteger

/** Minimal dependency-free Bitcoin/Solana Base58 codec. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val RADIX = BigInteger.valueOf(58)
    private val indexes = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    fun decode(value: String): ByteArray {
        require(value.isNotEmpty()) { "base58 value is empty" }
        var number = BigInteger.ZERO
        value.forEach { char ->
            require(char.code < indexes.size && indexes[char.code] >= 0) {
                "invalid base58 character: $char"
            }
            number = number.multiply(RADIX).add(BigInteger.valueOf(indexes[char.code].toLong()))
        }

        var raw = number.toByteArray()
        if (raw.size > 1 && raw[0] == 0.toByte()) raw = raw.copyOfRange(1, raw.size)
        if (number == BigInteger.ZERO) raw = ByteArray(0)

        val leadingZeros = value.takeWhile { it == '1' }.length
        return ByteArray(leadingZeros) + raw
    }

    fun decode32(value: String, label: String = "Solana public key"): ByteArray {
        val decoded = decode(value)
        require(decoded.size == 32) { "$label must decode to exactly 32 bytes, got ${decoded.size}" }
        return decoded
    }

    fun encode(bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "cannot base58 encode an empty byte array" }
        val leadingZeros = bytes.takeWhile { it == 0.toByte() }.size
        var number = BigInteger(1, bytes)
        val body = StringBuilder()
        while (number > BigInteger.ZERO) {
            val parts = number.divideAndRemainder(RADIX)
            body.append(ALPHABET[parts[1].toInt()])
            number = parts[0]
        }
        repeat(leadingZeros) { body.append('1') }
        return body.reverse().toString()
    }
}
