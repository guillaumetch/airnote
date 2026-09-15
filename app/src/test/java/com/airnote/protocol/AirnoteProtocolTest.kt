package com.airnote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirnoteProtocolTest {

    private fun range(start: Int, count: Int): ByteArray =
        ByteArray(count) { index -> (start + index).toByte() }

    private fun fixtureCore(
        amount: Long = 5_000_000,
        owner: ByteArray = byteArrayOf(0x02) + range(0x80, 32),
        expiresAt: Long = 1_700_003_600
    ) = AirnoteProtocol.MintCore(
        network = AirnoteProtocol.Network.DEVNET,
        noteId = range(0x00, 32),
        programId = range(0x20, 32),
        noteAccount = range(0x40, 32),
        usdcMint = range(0x60, 32),
        amountBaseUnits = amount,
        ownerP256Compressed = owner,
        issuedAtUnixSeconds = 1_700_000_000,
        expiresAtUnixSeconds = expiresAt,
        deviceAttestationDigest = range(0xa0, 32),
        riskTier = 1
    )

    private fun fixtureRequest(
        wallet: ByteArray = range(0xc0, 32),
        nonce: ByteArray = range(0xe0, 32),
        expiresAt: Long = 1_700_000_300
    ) = AirnoteProtocol.RecipientRequest(
        recipientSolanaWallet = wallet,
        recipientNonce = nonce,
        requestCreatedAtUnixSeconds = 1_700_000_001,
        requestExpiresAtUnixSeconds = expiresAt
    )

    @Test
    fun mintCoreGoldenVector() {
        val encoded = AirnoteProtocol.encodeMintCore(fixtureCore())
        assertEquals(240, encoded.size)
        assertEquals(
            "d6dfd317a4c51dde7d2b824fc09dbb8b6f9721efb9d6c9044f6a527c854ff650",
            AirnoteProtocol.sha256(encoded).hex()
        )
    }

    @Test
    fun certificateGoldenVector() {
        val commitment = AirnoteProtocol.noteCommitment(fixtureCore())
        val encoded = AirnoteProtocol.encodeMintCertificatePayload(
            AirnoteProtocol.MintCertificatePayload(
                noteCommitment = commitment,
                finalizedSlot = 250_000_000,
                mintTransactionSignature = range(0x00, 64),
                attestorKeyId = 7
            )
        )
        assertEquals(128, encoded.size)
        assertEquals(
            "31da2d818a0230418cbddda05a788452d8555dde8c94154bb4b323838f615272",
            AirnoteProtocol.sha256(encoded).hex()
        )
    }

    @Test
    fun recipientRequestGoldenVector() {
        val encoded = AirnoteProtocol.encodeRecipientRequest(fixtureRequest())
        assertEquals(101, encoded.size)
        assertEquals(
            "e7bbede178de83cf12f71692c61383c0e9f82c9377200ce02b9647f92e6155ff",
            AirnoteProtocol.sha256(encoded).hex()
        )
    }

    @Test
    fun transferGoldenVector() {
        val encoded = AirnoteProtocol.encodeTransferPayload(
            AirnoteProtocol.noteCommitment(fixtureCore()),
            fixtureRequest()
        )
        assertEquals(124, encoded.size)
        assertEquals(
            "badd1898e2fb123b79c877a0d03374a6f229f5e2dab3d8bb0363d033fe815c4e",
            AirnoteProtocol.sha256(encoded).hex()
        )
    }

    @Test
    fun amountMutationChangesNoteCommitment() {
        val a = AirnoteProtocol.noteCommitment(fixtureCore(amount = 5_000_000))
        val b = AirnoteProtocol.noteCommitment(fixtureCore(amount = 5_000_001))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun ownerKeyMutationChangesNoteCommitment() {
        val ownerA = byteArrayOf(0x02) + range(0x80, 32)
        val ownerB = ownerA.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val a = AirnoteProtocol.noteCommitment(fixtureCore(owner = ownerA))
        val b = AirnoteProtocol.noteCommitment(fixtureCore(owner = ownerB))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun noteExpiryMutationChangesNoteCommitment() {
        val a = AirnoteProtocol.noteCommitment(fixtureCore(expiresAt = 1_700_003_600))
        val b = AirnoteProtocol.noteCommitment(fixtureCore(expiresAt = 1_700_003_601))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun recipientWalletMutationChangesTransferPayload() {
        val commitment = AirnoteProtocol.noteCommitment(fixtureCore())
        val walletA = range(0xc0, 32)
        val walletB = walletA.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val a = AirnoteProtocol.encodeTransferPayload(commitment, fixtureRequest(wallet = walletA))
        val b = AirnoteProtocol.encodeTransferPayload(commitment, fixtureRequest(wallet = walletB))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun nonceMutationChangesTransferPayload() {
        val commitment = AirnoteProtocol.noteCommitment(fixtureCore())
        val nonceA = range(0xe0, 32)
        val nonceB = nonceA.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }
        val a = AirnoteProtocol.encodeTransferPayload(commitment, fixtureRequest(nonce = nonceA))
        val b = AirnoteProtocol.encodeTransferPayload(commitment, fixtureRequest(nonce = nonceB))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun requestExpiryMutationChangesTransferPayload() {
        val commitment = AirnoteProtocol.noteCommitment(fixtureCore())
        val a = AirnoteProtocol.encodeTransferPayload(commitment, fixtureRequest(expiresAt = 1_700_000_300))
        val b = AirnoteProtocol.encodeTransferPayload(commitment, fixtureRequest(expiresAt = 1_700_000_301))
        assertFalse(a.contentEquals(b))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongSolanaKeyLength() {
        AirnoteProtocol.encodeRecipientRequest(
            fixtureRequest(wallet = ByteArray(31))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUncompressedP256Key() {
        AirnoteProtocol.encodeMintCore(
            fixtureCore(owner = byteArrayOf(0x04) + range(0x80, 32))
        )
    }

    @Test
    fun commitmentIsExactly32Bytes() {
        assertTrue(AirnoteProtocol.noteCommitment(fixtureCore()).size == 32)
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
