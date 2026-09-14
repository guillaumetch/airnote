package com.airnote.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object AirnoteProtocol {
    const val VERSION: Int = 1

    private val MINT_DOMAIN = "AIRNOTE_MINT_CORE_V1".toByteArray(Charsets.US_ASCII)
    private val CERT_DOMAIN = "AIRNOTE_MINT_CERT_V1".toByteArray(Charsets.US_ASCII)
    private val RECIPIENT_DOMAIN = "AIRNOTE_RECIPIENT_V1".toByteArray(Charsets.US_ASCII)
    private val TRANSFER_DOMAIN = "AIRNOTE_TRANSFER_V1".toByteArray(Charsets.US_ASCII)

    enum class Network(val code: Int) {
        DEVNET(1),
        MAINNET_BETA(2)
    }

    data class MintCore(
        val network: Network,
        val noteId: ByteArray,
        val programId: ByteArray,
        val noteAccount: ByteArray,
        val usdcMint: ByteArray,
        val amountBaseUnits: Long,
        val ownerP256Compressed: ByteArray,
        val issuedAtUnixSeconds: Long,
        val expiresAtUnixSeconds: Long,
        val deviceAttestationDigest: ByteArray,
        val riskTier: Int
    )

    data class MintCertificatePayload(
        val noteCommitment: ByteArray,
        val finalizedSlot: Long,
        val mintTransactionSignature: ByteArray,
        val attestorKeyId: Long
    )

    data class RecipientRequest(
        val recipientSolanaWallet: ByteArray,
        val recipientNonce: ByteArray,
        val requestCreatedAtUnixSeconds: Long,
        val requestExpiresAtUnixSeconds: Long
    )

    fun encodeMintCore(core: MintCore): ByteArray {
        requireBytes(core.noteId, 32, "noteId")
        requireBytes(core.programId, 32, "programId")
        requireBytes(core.noteAccount, 32, "noteAccount")
        requireBytes(core.usdcMint, 32, "usdcMint")
        requireP256Compressed(core.ownerP256Compressed)
        requireBytes(core.deviceAttestationDigest, 32, "deviceAttestationDigest")
        require(core.amountBaseUnits > 0) { "amountBaseUnits must be positive" }
        require(core.issuedAtUnixSeconds >= 0) { "issuedAtUnixSeconds must be non-negative" }
        require(core.expiresAtUnixSeconds > core.issuedAtUnixSeconds) { "note expiry must be after issue time" }
        require(core.riskTier in 0..255) { "riskTier must fit u8" }

        return bytes {
            write(MINT_DOMAIN)
            writeU8(VERSION)
            writeU8(core.network.code)
            write(core.noteId)
            write(core.programId)
            write(core.noteAccount)
            write(core.usdcMint)
            writeU64Le(core.amountBaseUnits)
            write(core.ownerP256Compressed)
            writeU64Le(core.issuedAtUnixSeconds)
            writeU64Le(core.expiresAtUnixSeconds)
            write(core.deviceAttestationDigest)
            writeU8(core.riskTier)
        }
    }

    fun noteCommitment(core: MintCore): ByteArray = sha256(encodeMintCore(core))

    fun encodeMintCertificatePayload(payload: MintCertificatePayload): ByteArray {
        requireBytes(payload.noteCommitment, 32, "noteCommitment")
        require(payload.finalizedSlot >= 0) { "finalizedSlot must be non-negative" }
        requireBytes(payload.mintTransactionSignature, 64, "mintTransactionSignature")
        require(payload.attestorKeyId in 0..0xffff_ffffL) { "attestorKeyId must fit u32" }

        return bytes {
            write(CERT_DOMAIN)
            write(payload.noteCommitment)
            writeU64Le(payload.finalizedSlot)
            write(payload.mintTransactionSignature)
            writeU32Le(payload.attestorKeyId)
        }
    }

    fun encodeRecipientRequest(request: RecipientRequest): ByteArray {
        requireBytes(request.recipientSolanaWallet, 32, "recipientSolanaWallet")
        requireBytes(request.recipientNonce, 32, "recipientNonce")
        require(request.requestCreatedAtUnixSeconds >= 0) { "requestCreatedAtUnixSeconds must be non-negative" }
        require(request.requestExpiresAtUnixSeconds > request.requestCreatedAtUnixSeconds) {
            "request expiry must be after creation"
        }

        return bytes {
            write(RECIPIENT_DOMAIN)
            writeU8(VERSION)
            write(request.recipientSolanaWallet)
            write(request.recipientNonce)
            writeU64Le(request.requestCreatedAtUnixSeconds)
            writeU64Le(request.requestExpiresAtUnixSeconds)
        }
    }

    fun encodeTransferPayload(
        noteCommitment: ByteArray,
        request: RecipientRequest
    ): ByteArray {
        requireBytes(noteCommitment, 32, "noteCommitment")
        requireBytes(request.recipientSolanaWallet, 32, "recipientSolanaWallet")
        requireBytes(request.recipientNonce, 32, "recipientNonce")
        require(request.requestExpiresAtUnixSeconds > request.requestCreatedAtUnixSeconds) {
            "request expiry must be after creation"
        }

        return bytes {
            write(TRANSFER_DOMAIN)
            writeU8(VERSION)
            write(noteCommitment)
            write(request.recipientSolanaWallet)
            write(request.recipientNonce)
            writeU64Le(request.requestExpiresAtUnixSeconds)
        }
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun requireBytes(value: ByteArray, expected: Int, name: String) {
        require(value.size == expected) { "$name must be exactly $expected bytes, got ${value.size}" }
    }

    private fun requireP256Compressed(value: ByteArray) {
        requireBytes(value, 33, "ownerP256Compressed")
        val prefix = value[0].toInt() and 0xff
        require(prefix == 0x02 || prefix == 0x03) { "compressed P-256 key must start with 0x02 or 0x03" }
    }

    private inline fun bytes(block: CanonicalWriter.() -> Unit): ByteArray {
        val writer = CanonicalWriter()
        writer.block()
        return writer.toByteArray()
    }

    private class CanonicalWriter {
        private val out = ByteArrayOutputStream()

        fun write(value: ByteArray) {
            out.write(value)
        }

        fun writeU8(value: Int) {
            require(value in 0..255) { "u8 out of range" }
            out.write(value)
        }

        fun writeU32Le(value: Long) {
            require(value in 0..0xffff_ffffL) { "u32 out of range" }
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())
        }

        fun writeU64Le(value: Long) {
            require(value >= 0) { "u64 semantic value must fit signed Long" }
            write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
