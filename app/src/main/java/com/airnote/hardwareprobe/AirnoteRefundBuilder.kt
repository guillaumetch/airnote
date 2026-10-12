package com.airnote.hardwareprobe

import com.airnote.protocol.Base58
import com.airnote.protocol.Gate04CertificateJson
import com.airnote.protocol.Gate04Verifier
import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.Message
import com.solana.transaction.TransactionInstruction
import com.solana.transaction.toUnsignedTransaction
import java.security.MessageDigest

object AirnoteRefundBuilder {
    data class Plan(
        val unsignedTransaction: ByteArray,
        val minContextSlot: Int,
        val lastValidBlockHeight: Long
    )

    suspend fun prepare(
        note: LocalAirnote,
        authorizedWalletPublicKey: ByteArray,
        rpc: AirnoteSolanaRpc
    ): Plan {
        require(note.state == LocalAirnoteState.READY_OFFLINE) {
            "Only an unsent READY OFFLINE Airnote can be refunded from this screen"
        }
        require(authorizedWalletPublicKey.size == 32) { "refund wallet must be 32 bytes" }

        val certificate = Gate04CertificateJson.parse(
            requireNotNull(note.certificateJson) { "Airnote is missing its backing certificate" }
        )
        val chainTime = rpc.finalizedChainTimeSeconds()
        require(chainTime >= certificate.mintCore.expiresAtUnixSeconds) {
            "Airnote has not expired yet"
        }

        val funder = SolanaPublicKey(authorizedWalletPublicKey)
        val programId = SolanaPublicKey.from(Gate04Verifier.PROGRAM_ID)
        val usdcMint = SolanaPublicKey.from(Gate04Verifier.DEVNET_USDC)
        val noteId = note.noteIdHex.hexBytes(32, "note id")

        val notePda = ProgramDerivedAddress.find(
            listOf(NOTE_SEED, noteId),
            programId
        ).getOrThrow()
        val vault = ProgramDerivedAddress.find(
            listOf(VAULT_SEED, noteId),
            programId
        ).getOrThrow()
        val funderToken = ProgramDerivedAddress.find(
            listOf(funder.bytes, TOKEN_PROGRAM_ID.bytes, usdcMint.bytes),
            ASSOCIATED_TOKEN_PROGRAM_ID
        ).getOrThrow()

        val noteOwner = rpc.accountOwner(Base58.encode(notePda.bytes))
            ?: error("Airnote note account no longer exists")
        require(noteOwner == Gate04Verifier.PROGRAM_ID) {
            "Airnote note account is owned by the wrong program"
        }

        val latest = rpc.latestBlockhash()
        val instruction = TransactionInstruction(
            programId,
            listOf(
                AccountMeta(funder, true, false),
                AccountMeta(notePda, false, true),
                AccountMeta(usdcMint, false, false),
                AccountMeta(vault, false, true),
                AccountMeta(funderToken, false, true),
                AccountMeta(TOKEN_PROGRAM_ID, false, false)
            ),
            discriminator("global:refund_note")
        )

        val unsigned = Message.Builder()
            .setRecentBlockhash(latest.blockhash)
            .addInstruction(instruction)
            .build()
            .toUnsignedTransaction()
            .serialize()

        return Plan(
            unsignedTransaction = unsigned,
            minContextSlot = latest.contextSlot,
            lastValidBlockHeight = latest.lastValidBlockHeight
        )
    }

    private fun discriminator(label: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(label.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 8)

    private fun String.hexBytes(expectedBytes: Int, label: String): ByteArray {
        require(length == expectedBytes * 2 && all { it.digitToIntOrNull(16) != null }) {
            "$label must be exactly $expectedBytes bytes of hex"
        }
        return ByteArray(expectedBytes) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private val NOTE_SEED = "note".toByteArray(Charsets.UTF_8)
    private val VAULT_SEED = "vault".toByteArray(Charsets.UTF_8)
    private val TOKEN_PROGRAM_ID = SolanaPublicKey.from("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    private val ASSOCIATED_TOKEN_PROGRAM_ID = SolanaPublicKey.from("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
}
