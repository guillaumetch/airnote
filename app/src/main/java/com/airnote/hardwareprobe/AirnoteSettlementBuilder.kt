package com.airnote.hardwareprobe

import com.airnote.protocol.AirnoteProtocol
import com.airnote.protocol.Gate04Wire
import com.solana.programs.AssociatedTokenProgram
import com.solana.programs.SystemProgram
import com.solana.programs.TokenProgram
import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.Message
import com.solana.transaction.TransactionInstruction
import com.solana.transaction.toUnsignedTransaction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Builds the exact Gate 05 redeem_note transaction that tools/devnet/redeem-airnote.mjs builds.
 *
 * This class never holds an Ed25519 private key. The resulting unsigned transaction is handed to
 * a Mobile Wallet Adapter wallet which must authorize the recipient account already bound into
 * the offline Airnote.
 */
object AirnoteSettlementBuilder {
    data class Plan(
        val unsignedTransaction: ByteArray,
        val recipient: SolanaPublicKey,
        val programId: SolanaPublicKey,
        val noteAccount: SolanaPublicKey,
        val recipientTokenAccount: SolanaPublicKey,
        val noteCommitmentHex: String,
        val noteExpiresAtUnixSeconds: Long,
        val minContextSlot: Int,
        val lastValidBlockHeight: Long
    )

    suspend fun prepare(
        transferPayload: String,
        authorizedWalletPublicKey: ByteArray,
        rpc: AirnoteSolanaRpc
    ): Plan {
        require(authorizedWalletPublicKey.size == 32) { "authorized Solana wallet must be 32 bytes" }
        val transfer = Gate04Wire.decodeTransferQr(transferPayload.trim())
        val core = transfer.airnote.mintCore
        val request = transfer.recipientRequest
        val recipient = SolanaPublicKey(request.recipientSolanaWallet)

        if (!recipient.bytes.contentEquals(authorizedWalletPublicKey)) {
            throw WalletMismatchException(recipient.base58(), SolanaPublicKey(authorizedWalletPublicKey).base58())
        }
        require(core.network == AirnoteProtocol.Network.DEVNET) { "Gate 09 currently settles devnet Airnotes only" }

        val programId = SolanaPublicKey(core.programId)
        val noteAccount = SolanaPublicKey(core.noteAccount)
        val usdcMint = SolanaPublicKey(core.usdcMint)
        require(usdcMint == DEVNET_USDC) { "Airnote is not backed by Circle Solana Devnet USDC" }
        require(core.amountBaseUnits in ALLOWED_AMOUNTS) { "unsupported AIRNOTE amount ${core.amountBaseUnits}" }
        require(core.riskTier <= 1) { "risk tier ${core.riskTier} exceeds Gate 09 policy" }

        val expectedCommitment = AirnoteProtocol.noteCommitment(core)
        require(transfer.airnote.certificate.payload.noteCommitment.contentEquals(expectedCommitment)) {
            "mint certificate commitment does not match mint core"
        }

        val note = ProgramDerivedAddress.find(
            listOf(NOTE_SEED, core.noteId),
            programId
        ).getOrThrow()
        require(note.bytes.contentEquals(noteAccount.bytes)) {
            "transfer note account ${noteAccount.base58()} does not match derived PDA ${note.base58()}"
        }
        val vault = ProgramDerivedAddress.find(
            listOf(VAULT_SEED, core.noteId),
            programId
        ).getOrThrow()
        val recipientToken = ProgramDerivedAddress.find(
            listOf(recipient.bytes, TokenProgram.PROGRAM_ID.bytes, usdcMint.bytes),
            AssociatedTokenProgram.PROGRAM_ID
        ).getOrThrow()

        val owner = rpc.accountOwner(note.base58())
            ?: throw NoteUnavailableException("AIRNOTE note account does not exist: ${note.base58()}")
        require(owner == programId.base58()) { "AIRNOTE note account is owned by the wrong program" }

        val chainTime = rpc.finalizedChainTimeSeconds()
        if (chainTime >= core.expiresAtUnixSeconds) {
            throw AirnoteExpiredException(core.expiresAtUnixSeconds, chainTime)
        }
        require(request.requestExpiresAtUnixSeconds >= core.issuedAtUnixSeconds) {
            "receiver request expiry predates note issuance"
        }
        require(request.requestExpiresAtUnixSeconds <= core.expiresAtUnixSeconds) {
            "receiver request expiry outlives Airnote"
        }

        val latest = rpc.latestBlockhash()
        val transferMessage = AirnoteProtocol.encodeTransferPayload(expectedCommitment, request)
        val verifyInstruction = TransactionInstruction(
            SECP256R1_PROGRAM_ID,
            emptyList(),
            p256InstructionData(core.ownerP256Compressed, transfer.transferSignature, transferMessage)
        )
        val redeemInstruction = TransactionInstruction(
            programId,
            listOf(
                // Recipient is also fee payer in Gate 09, matching the default Gate 05 CLI path.
                AccountMeta(recipient, true, true),
                AccountMeta(recipient, true, false),
                AccountMeta(note, false, true),
                AccountMeta(usdcMint, false, false),
                AccountMeta(vault, false, true),
                AccountMeta(recipientToken, false, true),
                AccountMeta(TokenProgram.PROGRAM_ID, false, false),
                AccountMeta(AssociatedTokenProgram.PROGRAM_ID, false, false),
                AccountMeta(SystemProgram.PROGRAM_ID, false, false),
                AccountMeta(INSTRUCTIONS_SYSVAR, false, false)
            ),
            redeemInstructionData(request.recipientNonce, request.requestExpiresAtUnixSeconds)
        )

        val unsigned = Message.Builder()
            .addFeePayer(recipient)
            .setRecentBlockhash(latest.blockhash)
            .addInstruction(verifyInstruction)
            .addInstruction(redeemInstruction)
            .build()
            .toUnsignedTransaction()
            .serialize()

        return Plan(
            unsignedTransaction = unsigned,
            recipient = recipient,
            programId = programId,
            noteAccount = note,
            recipientTokenAccount = recipientToken,
            noteCommitmentHex = expectedCommitment.hex(),
            noteExpiresAtUnixSeconds = core.expiresAtUnixSeconds,
            minContextSlot = latest.contextSlot,
            lastValidBlockHeight = latest.lastValidBlockHeight
        )
    }

    internal fun p256InstructionData(
        ownerP256Compressed: ByteArray,
        signature: ByteArray,
        message: ByteArray
    ): ByteArray {
        require(ownerP256Compressed.size == 33) { "P-256 owner key must be 33 bytes" }
        require(ownerP256Compressed[0] == 0x02.toByte() || ownerP256Compressed[0] == 0x03.toByte()) {
            "P-256 owner key is not compressed SEC1"
        }
        require(signature.size == 64) { "P-256 compact signature must be 64 bytes" }
        require(message.size <= 0xffff) { "P-256 message is too large" }

        val signatureOffset = 16
        val publicKeyOffset = 80
        val messageOffset = 113
        val currentInstruction = 0xffff
        return byteArrayOf(1, 0) +
            u16le(signatureOffset) + u16le(currentInstruction) +
            u16le(publicKeyOffset) + u16le(currentInstruction) +
            u16le(messageOffset) + u16le(message.size) + u16le(currentInstruction) +
            signature + ownerP256Compressed + message
    }

    internal fun redeemInstructionData(recipientNonce: ByteArray, requestExpiresAt: Long): ByteArray {
        require(recipientNonce.size == 32) { "recipient nonce must be 32 bytes" }
        return discriminator("global:redeem_note") + recipientNonce + u64le(requestExpiresAt)
    }

    private fun discriminator(label: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(label.toByteArray(Charsets.UTF_8)).copyOfRange(0, 8)

    private fun u16le(value: Int): ByteArray {
        require(value in 0..0xffff) { "u16 out of range" }
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    }

    private fun u64le(value: Long): ByteArray {
        require(value >= 0) { "u64 semantic value must fit signed Long" }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    class WalletMismatchException(expected: String, actual: String) :
        Exception("This Airnote is bound to $expected, but the selected wallet is $actual")

    class NoteUnavailableException(message: String) : Exception(message)
    class AirnoteExpiredException(expiresAt: Long, chainTime: Long) :
        Exception("Airnote expired at $expiresAt (finalized Solana time $chainTime)")

    private val NOTE_SEED = "note".toByteArray(Charsets.UTF_8)
    private val VAULT_SEED = "vault".toByteArray(Charsets.UTF_8)
    private val ALLOWED_AMOUNTS = setOf(1_000_000L, 5_000_000L, 10_000_000L)
    private val DEVNET_USDC = SolanaPublicKey.from("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")
    private val SECP256R1_PROGRAM_ID = SolanaPublicKey.from("Secp256r1SigVerify1111111111111111111111111")
    private val INSTRUCTIONS_SYSVAR = SolanaPublicKey.from("Sysvar1nstructions1111111111111111111111111")
}
