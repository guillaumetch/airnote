package com.airnote.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Compact QR transport for Gate 04.
 *
 * Request QR: canonical 101-byte AIRNOTE recipient request.
 * Transfer QR: mint core | mint certificate payload | attestor signature |
 *              recipient request | compact P-256 transfer signature.
 *
 * All fields have fixed canonical widths, so there is no JSON ambiguity in the
 * actual offline handoff.
 */
object Gate04Wire {
    const val REQUEST_PREFIX = "airnote:r1:"
    const val TRANSFER_PREFIX = "airnote:t1:"

    private const val MINT_CORE_BYTES = 240
    private const val CERT_PAYLOAD_BYTES = 128
    private const val ATTESTOR_SIGNATURE_BYTES = 64
    private const val RECIPIENT_REQUEST_BYTES = 101
    private const val TRANSFER_SIGNATURE_BYTES = 64
    private const val TRANSFER_BUNDLE_BYTES =
        MINT_CORE_BYTES + CERT_PAYLOAD_BYTES + ATTESTOR_SIGNATURE_BYTES + RECIPIENT_REQUEST_BYTES + TRANSFER_SIGNATURE_BYTES

    private val MINT_DOMAIN = "AIRNOTE_MINT_CORE_V1".toByteArray(Charsets.US_ASCII)
    private val CERT_DOMAIN = "AIRNOTE_MINT_CERT_V1".toByteArray(Charsets.US_ASCII)
    private val RECIPIENT_DOMAIN = "AIRNOTE_RECIPIENT_V1".toByteArray(Charsets.US_ASCII)

    data class CertifiedAirnote(
        val mintCore: AirnoteProtocol.MintCore,
        val certificate: MintCertificateVerifier.SignedMintCertificate
    )

    data class OfflineTransfer(
        val airnote: CertifiedAirnote,
        val recipientRequest: AirnoteProtocol.RecipientRequest,
        val transferSignature: ByteArray
    )

    fun encodeRequestQr(request: AirnoteProtocol.RecipientRequest): String =
        REQUEST_PREFIX + b64(AirnoteProtocol.encodeRecipientRequest(request))

    fun decodeRequestQr(value: String): AirnoteProtocol.RecipientRequest {
        require(value.startsWith(REQUEST_PREFIX)) { "not an AIRNOTE recipient request QR" }
        val raw = unb64(value.removePrefix(REQUEST_PREFIX))
        return decodeRecipientRequest(raw)
    }

    fun encodeTransferQr(transfer: OfflineTransfer): String {
        require(transfer.certificateSignature().size == ATTESTOR_SIGNATURE_BYTES) {
            "attestor signature must be 64 bytes"
        }
        require(transfer.transferSignature.size == TRANSFER_SIGNATURE_BYTES) {
            "transfer signature must be 64 bytes"
        }
        val raw = AirnoteProtocol.encodeMintCore(transfer.airnote.mintCore) +
            AirnoteProtocol.encodeMintCertificatePayload(transfer.airnote.certificate.payload) +
            transfer.certificateSignature() +
            AirnoteProtocol.encodeRecipientRequest(transfer.recipientRequest) +
            transfer.transferSignature
        check(raw.size == TRANSFER_BUNDLE_BYTES)
        return TRANSFER_PREFIX + b64(raw)
    }

    fun decodeTransferQr(value: String): OfflineTransfer {
        require(value.startsWith(TRANSFER_PREFIX)) { "not an AIRNOTE transfer QR" }
        val raw = unb64(value.removePrefix(TRANSFER_PREFIX))
        require(raw.size == TRANSFER_BUNDLE_BYTES) {
            "AIRNOTE transfer bundle must be $TRANSFER_BUNDLE_BYTES bytes, got ${raw.size}"
        }
        var offset = 0
        fun take(count: Int): ByteArray = raw.copyOfRange(offset, offset + count).also { offset += count }

        val mintCore = decodeMintCore(take(MINT_CORE_BYTES))
        val certPayload = decodeCertificatePayload(take(CERT_PAYLOAD_BYTES))
        val attestorSignature = take(ATTESTOR_SIGNATURE_BYTES)
        val request = decodeRecipientRequest(take(RECIPIENT_REQUEST_BYTES))
        val transferSignature = take(TRANSFER_SIGNATURE_BYTES)
        check(offset == raw.size)

        return OfflineTransfer(
            airnote = CertifiedAirnote(
                mintCore = mintCore,
                certificate = MintCertificateVerifier.SignedMintCertificate(certPayload, attestorSignature)
            ),
            recipientRequest = request,
            transferSignature = transferSignature
        )
    }

    fun decodeMintCore(encoded: ByteArray): AirnoteProtocol.MintCore {
        require(encoded.size == MINT_CORE_BYTES) { "mint core must be exactly $MINT_CORE_BYTES bytes" }
        val reader = Reader(encoded)
        reader.expect(MINT_DOMAIN, "mint domain")
        require(reader.u8() == AirnoteProtocol.VERSION) { "unsupported mint-core version" }
        val networkCode = reader.u8()
        val network = AirnoteProtocol.Network.entries.firstOrNull { it.code == networkCode }
            ?: throw IllegalArgumentException("unsupported AIRNOTE network code $networkCode")
        val result = AirnoteProtocol.MintCore(
            network = network,
            noteId = reader.bytes(32),
            programId = reader.bytes(32),
            noteAccount = reader.bytes(32),
            usdcMint = reader.bytes(32),
            amountBaseUnits = reader.u64Le(),
            ownerP256Compressed = reader.bytes(33),
            issuedAtUnixSeconds = reader.u64Le(),
            expiresAtUnixSeconds = reader.u64Le(),
            deviceAttestationDigest = reader.bytes(32),
            riskTier = reader.u8()
        )
        reader.requireFinished()
        // Re-encoding applies every canonical semantic validation in AirnoteProtocol.
        require(AirnoteProtocol.encodeMintCore(result).contentEquals(encoded)) { "non-canonical mint core" }
        return result
    }

    fun decodeCertificatePayload(encoded: ByteArray): AirnoteProtocol.MintCertificatePayload {
        require(encoded.size == CERT_PAYLOAD_BYTES) { "certificate payload must be exactly $CERT_PAYLOAD_BYTES bytes" }
        val reader = Reader(encoded)
        reader.expect(CERT_DOMAIN, "certificate domain")
        val result = AirnoteProtocol.MintCertificatePayload(
            noteCommitment = reader.bytes(32),
            finalizedSlot = reader.u64Le(),
            mintTransactionSignature = reader.bytes(64),
            attestorKeyId = reader.u32Le()
        )
        reader.requireFinished()
        require(AirnoteProtocol.encodeMintCertificatePayload(result).contentEquals(encoded)) {
            "non-canonical certificate payload"
        }
        return result
    }

    fun decodeRecipientRequest(encoded: ByteArray): AirnoteProtocol.RecipientRequest {
        require(encoded.size == RECIPIENT_REQUEST_BYTES) { "recipient request must be exactly $RECIPIENT_REQUEST_BYTES bytes" }
        val reader = Reader(encoded)
        reader.expect(RECIPIENT_DOMAIN, "recipient domain")
        require(reader.u8() == AirnoteProtocol.VERSION) { "unsupported recipient-request version" }
        val result = AirnoteProtocol.RecipientRequest(
            recipientSolanaWallet = reader.bytes(32),
            recipientNonce = reader.bytes(32),
            requestCreatedAtUnixSeconds = reader.u64Le(),
            requestExpiresAtUnixSeconds = reader.u64Le()
        )
        reader.requireFinished()
        require(AirnoteProtocol.encodeRecipientRequest(result).contentEquals(encoded)) {
            "non-canonical recipient request"
        }
        return result
    }

    private fun OfflineTransfer.certificateSignature(): ByteArray = airnote.certificate.attestorSignature

    private fun b64(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun unb64(value: String): ByteArray = runCatching {
        Base64.getUrlDecoder().decode(value)
    }.getOrElse { throw IllegalArgumentException("invalid AIRNOTE base64url payload", it) }

    private class Reader(private val data: ByteArray) {
        private var offset = 0

        fun bytes(count: Int): ByteArray {
            require(count >= 0 && offset + count <= data.size) { "truncated AIRNOTE payload" }
            return data.copyOfRange(offset, offset + count).also { offset += count }
        }

        fun expect(expected: ByteArray, label: String) {
            require(bytes(expected.size).contentEquals(expected)) { "invalid $label" }
        }

        fun u8(): Int = bytes(1)[0].toInt() and 0xff

        fun u32Le(): Long = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL

        fun u64Le(): Long {
            val value = ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).long
            require(value >= 0) { "AIRNOTE u64 exceeds supported signed Long range" }
            return value
        }

        fun requireFinished() = require(offset == data.size) { "trailing AIRNOTE payload bytes" }
    }
}
