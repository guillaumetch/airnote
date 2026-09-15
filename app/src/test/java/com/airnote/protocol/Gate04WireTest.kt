package com.airnote.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gate04WireTest {
    private fun range(start: Int, count: Int): ByteArray =
        ByteArray(count) { index -> (start + index).toByte() }

    private fun core() = AirnoteProtocol.MintCore(
        network = AirnoteProtocol.Network.DEVNET,
        noteId = range(0x00, 32),
        programId = range(0x20, 32),
        noteAccount = range(0x40, 32),
        usdcMint = range(0x60, 32),
        amountBaseUnits = 1_000_000,
        ownerP256Compressed = byteArrayOf(0x02) + range(0x80, 32),
        issuedAtUnixSeconds = 1_700_000_000,
        expiresAtUnixSeconds = 1_700_003_600,
        deviceAttestationDigest = range(0xa0, 32),
        riskTier = 1
    )

    private fun request() = AirnoteProtocol.RecipientRequest(
        recipientSolanaWallet = range(0xc0, 32),
        recipientNonce = range(0xe0, 32),
        requestCreatedAtUnixSeconds = 1_700_000_010,
        requestExpiresAtUnixSeconds = 1_700_000_310
    )

    @Test
    fun requestQrRoundTripsCanonicalBytes() {
        val encoded = Gate04Wire.encodeRequestQr(request())
        assertTrue(encoded.startsWith(Gate04Wire.REQUEST_PREFIX))
        val decoded = Gate04Wire.decodeRequestQr(encoded)
        assertArrayEquals(
            AirnoteProtocol.encodeRecipientRequest(request()),
            AirnoteProtocol.encodeRecipientRequest(decoded)
        )
    }

    @Test
    fun transferQrRoundTripsAllCanonicalBytes() {
        val mintCore = core()
        val certificate = MintCertificateVerifier.SignedMintCertificate(
            payload = AirnoteProtocol.MintCertificatePayload(
                noteCommitment = AirnoteProtocol.noteCommitment(mintCore),
                finalizedSlot = 250_000_000,
                mintTransactionSignature = range(1, 64),
                attestorKeyId = 1
            ),
            attestorSignature = range(2, 64)
        )
        val transfer = Gate04Wire.OfflineTransfer(
            airnote = Gate04Wire.CertifiedAirnote(mintCore, certificate),
            recipientRequest = request(),
            transferSignature = range(3, 64)
        )

        val encoded = Gate04Wire.encodeTransferQr(transfer)
        assertTrue(encoded.startsWith(Gate04Wire.TRANSFER_PREFIX))
        val decoded = Gate04Wire.decodeTransferQr(encoded)

        assertArrayEquals(
            AirnoteProtocol.encodeMintCore(mintCore),
            AirnoteProtocol.encodeMintCore(decoded.airnote.mintCore)
        )
        assertArrayEquals(
            AirnoteProtocol.encodeMintCertificatePayload(certificate.payload),
            AirnoteProtocol.encodeMintCertificatePayload(decoded.airnote.certificate.payload)
        )
        assertArrayEquals(certificate.attestorSignature, decoded.airnote.certificate.attestorSignature)
        assertArrayEquals(
            AirnoteProtocol.encodeRecipientRequest(request()),
            AirnoteProtocol.encodeRecipientRequest(decoded.recipientRequest)
        )
        assertArrayEquals(transfer.transferSignature, decoded.transferSignature)
        assertEquals(807, encoded.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun requestRejectsWrongPrefix() {
        Gate04Wire.decodeRequestQr("airnote:t1:AA")
    }

    @Test(expected = IllegalArgumentException::class)
    fun transferRejectsTruncatedBundle() {
        Gate04Wire.decodeTransferQr(Gate04Wire.TRANSFER_PREFIX + "AA")
    }

    @Test
    fun base58RoundTripsLeadingZeros() {
        val value = byteArrayOf(0, 0, 1, 2, 3, 4, 5)
        assertArrayEquals(value, Base58.decode(Base58.encode(value)))
    }
}
