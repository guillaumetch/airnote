package com.airnote.hardwareprobe

import java.math.BigInteger
import java.security.cert.X509Certificate

object AndroidAttestation {
    private const val OID = "1.3.6.1.4.1.11129.2.1.17"
    private const val TAG_USAGE_COUNT_LIMIT = 405

    data class Parsed(
        val attestationVersion: Int,
        val attestationSecurityLevel: String,
        val keyMintVersion: Int,
        val keyMintSecurityLevel: String,
        val challengeMatches: Boolean,
        val softwareUsageCountLimit: Long?,
        val hardwareUsageCountLimit: Long?
    )

    fun parse(cert: X509Certificate, expectedChallenge: ByteArray): Parsed {
        val wrapped = cert.getExtensionValue(OID)
            ?: error("Android Key Attestation extension missing")

        val outer = DerReader(wrapped).readOne()
        require(outer.tagClass == 0 && outer.tagNumber == 4) { "Attestation extension must be OCTET STRING" }

        val root = DerReader(outer.value).readOne()
        require(root.tagClass == 0 && root.tagNumber == 16 && root.constructed) { "KeyDescription must be SEQUENCE" }

        val children = DerReader(root.value).readAll()
        require(children.size >= 8) { "KeyDescription missing required fields: ${children.size}" }

        val attestationVersion = children[0].asInteger().toInt()
        val attestationSecurity = securityLevel(children[1].asInteger().toInt())
        val keyMintVersion = children[2].asInteger().toInt()
        val keyMintSecurity = securityLevel(children[3].asInteger().toInt())

        val challenge = children[4]
        require(challenge.tagClass == 0 && challenge.tagNumber == 4) { "attestationChallenge must be OCTET STRING" }

        val software = children[6]
        val hardware = children[7]
        require(software.tagClass == 0 && software.tagNumber == 16) { "softwareEnforced must be SEQUENCE" }
        require(hardware.tagClass == 0 && hardware.tagNumber == 16) { "hardwareEnforced must be SEQUENCE" }

        return Parsed(
            attestationVersion = attestationVersion,
            attestationSecurityLevel = attestationSecurity,
            keyMintVersion = keyMintVersion,
            keyMintSecurityLevel = keyMintSecurity,
            challengeMatches = challenge.value.contentEquals(expectedChallenge),
            softwareUsageCountLimit = findExplicitInteger(software.value, TAG_USAGE_COUNT_LIMIT),
            hardwareUsageCountLimit = findExplicitInteger(hardware.value, TAG_USAGE_COUNT_LIMIT)
        )
    }

    private fun findExplicitInteger(sequenceBody: ByteArray, contextTag: Int): Long? {
        for (field in DerReader(sequenceBody).readAll()) {
            if (field.tagClass == 2 && field.tagNumber == contextTag) {
                val inner = DerReader(field.value).readOne()
                return inner.asInteger().longValueExact()
            }
        }
        return null
    }

    private fun securityLevel(value: Int): String = when (value) {
        0 -> "SOFTWARE"
        1 -> "TRUSTED_ENVIRONMENT"
        2 -> "STRONGBOX"
        else -> "UNKNOWN($value)"
    }

    internal data class Tlv(
        val tagClass: Int,
        val constructed: Boolean,
        val tagNumber: Int,
        val value: ByteArray
    ) {
        fun asInteger(): BigInteger {
            require(tagClass == 0 && (tagNumber == 2 || tagNumber == 10)) {
                "Expected INTEGER/ENUMERATED, got class=$tagClass tag=$tagNumber"
            }
            return BigInteger(value)
        }
    }

    internal class DerReader(private val bytes: ByteArray) {
        private var offset = 0

        fun readOne(): Tlv {
            require(offset < bytes.size) { "Unexpected end of DER" }
            val first = bytes[offset++].toInt() and 0xff
            val tagClass = first ushr 6
            val constructed = (first and 0x20) != 0
            var tagNumber = first and 0x1f

            if (tagNumber == 0x1f) {
                tagNumber = 0
                var seen = 0
                while (true) {
                    require(offset < bytes.size) { "Truncated high-tag-number" }
                    val b = bytes[offset++].toInt() and 0xff
                    tagNumber = (tagNumber shl 7) or (b and 0x7f)
                    seen++
                    require(seen <= 5) { "DER tag number too large" }
                    if ((b and 0x80) == 0) break
                }
            }

            require(offset < bytes.size) { "Missing DER length" }
            val firstLen = bytes[offset++].toInt() and 0xff
            val length = if ((firstLen and 0x80) == 0) {
                firstLen
            } else {
                val count = firstLen and 0x7f
                require(count in 1..4) { "Unsupported DER length-of-length: $count" }
                require(offset + count <= bytes.size) { "Truncated DER length" }
                var result = 0
                repeat(count) {
                    result = (result shl 8) or (bytes[offset++].toInt() and 0xff)
                }
                result
            }

            require(length >= 0 && offset + length <= bytes.size) { "DER value exceeds buffer" }
            val value = bytes.copyOfRange(offset, offset + length)
            offset += length
            return Tlv(tagClass, constructed, tagNumber, value)
        }

        fun readAll(): List<Tlv> {
            val out = mutableListOf<Tlv>()
            while (offset < bytes.size) out += readOne()
            return out
        }
    }
}
