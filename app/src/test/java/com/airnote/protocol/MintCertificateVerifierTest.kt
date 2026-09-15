package com.airnote.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MintCertificateVerifierTest {
    private val attestorPublicKey =
        "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664".hex()
    private val attestorSignature =
        "af9ce7a59779d62228178371cfc2225efc9a31fa4b54032cb2d04f6223f8888b" +
            "165c5bf380843b29bd27cc3756c547bce62b459a7117d55b7c2cf4188f24a70e"

    private fun range(start: Int, count: Int): ByteArray =
        ByteArray(count) { index -> (start + index).toByte() }

    private fun fixtureCore() = AirnoteProtocol.MintCore(
        network = AirnoteProtocol.Network.DEVNET,
        noteId = range(0x00, 32),
        programId = range(0x20, 32),
        noteAccount = range(0x40, 32),
        usdcMint = range(0x60, 32),
        amountBaseUnits = 5_000_000,
        ownerP256Compressed = byteArrayOf(0x02) + range(0x80, 32),
        issuedAtUnixSeconds = 1_700_000_000,
        expiresAtUnixSeconds = 1_700_003_600,
        deviceAttestationDigest = range(0xa0, 32),
        riskTier = 1
    )

    private fun fixturePayload() = AirnoteProtocol.MintCertificatePayload(
        noteCommitment = AirnoteProtocol.noteCommitment(fixtureCore()),
        finalizedSlot = 250_000_000,
        mintTransactionSignature = range(0x00, 64),
        attestorKeyId = 7
    )

    private val keyring = MintCertificateVerifier.AttestorKeyring(
        mapOf(7L to attestorPublicKey)
    )

    @Test
    fun deterministicAttestorVectorVerifies() {
        val certificate = MintCertificateVerifier.SignedMintCertificate(
            payload = fixturePayload(),
            attestorSignature = attestorSignature.hex()
        )
        assertTrue(MintCertificateVerifier.verify(fixtureCore(), certificate, keyring))
    }

    @Test
    fun changedFinalizedSlotInvalidatesSignature() {
        val original = fixturePayload()
        val certificate = MintCertificateVerifier.SignedMintCertificate(
            payload = original.copy(finalizedSlot = original.finalizedSlot + 1),
            attestorSignature = attestorSignature.hex()
        )
        assertFalse(MintCertificateVerifier.verify(fixtureCore(), certificate, keyring))
    }

    @Test
    fun changedMintCoreInvalidatesCommitmentBinding() {
        val mutatedCore = fixtureCore().copy(amountBaseUnits = 1_000_000)
        val certificate = MintCertificateVerifier.SignedMintCertificate(
            payload = fixturePayload(),
            attestorSignature = attestorSignature.hex()
        )
        assertFalse(MintCertificateVerifier.verify(mutatedCore, certificate, keyring))
    }

    @Test
    fun unknownAttestorKeyIsRejected() {
        val payload = fixturePayload().copy(attestorKeyId = 8)
        val certificate = MintCertificateVerifier.SignedMintCertificate(
            payload = payload,
            attestorSignature = attestorSignature.hex()
        )
        assertFalse(MintCertificateVerifier.verify(fixtureCore(), certificate, keyring))
    }

    private fun String.hex(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
