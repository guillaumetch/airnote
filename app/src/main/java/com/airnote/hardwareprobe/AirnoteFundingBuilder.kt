package com.airnote.hardwareprobe

import com.airnote.protocol.Base58
import com.airnote.protocol.Gate04Verifier
import com.solana.programs.SystemProgram
import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.Message
import com.solana.transaction.TransactionInstruction
import com.solana.transaction.toUnsignedTransaction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object AirnoteFundingBuilder {
    data class Plan(
        val unsignedTransaction: ByteArray,
        val funder: SolanaPublicKey,
        val noteAccount: SolanaPublicKey,
        val vaultAccount: SolanaPublicKey,
        val minContextSlot: Int,
        val lastValidBlockHeight: Long
    )

    suspend fun prepare(
        note: LocalAirnote,
        authorizedWalletPublicKey: ByteArray,
        rpc: AirnoteSolanaRpc
    ): Plan {
        require(note.state in setOf(LocalAirnoteState.PREPARED, LocalAirnoteState.FUNDING)) {
            "Airnote cannot be funded from " + note.state
        }
        require(authorizedWalletPublicKey.size == 32) { "funding wallet must be 32 bytes" }
        require(note.amountUsdc in setOf(1, 5, 10)) { "unsupported AIRNOTE denomination" }
        require(note.lifetimeSeconds in 300L..86_400L) { "Airnote lifetime must be 5 minutes through 24 hours" }

        val funder = SolanaPublicKey(authorizedWalletPublicKey)
        val programId = SolanaPublicKey.from(Gate04Verifier.PROGRAM_ID)
        val usdcMint = SolanaPublicKey.from(Gate04Verifier.DEVNET_USDC)
        val noteId = note.noteIdHex.hexBytes(32, "note id")
        val owner = note.ownerP256CompressedHex.hexBytes(33, "P-256 owner")
        val attestation = note.deviceAttestationDigestHex.hexBytes(32, "attestation digest")

        val config = ProgramDerivedAddress.find(listOf(CONFIG_SEED), programId).getOrThrow()
        val notePda = ProgramDerivedAddress.find(listOf(NOTE_SEED, noteId), programId).getOrThrow()
        val vault = ProgramDerivedAddress.find(listOf(VAULT_SEED, noteId), programId).getOrThrow()
        val funderToken = ProgramDerivedAddress.find(
            listOf(funder.bytes, TOKEN_PROGRAM_ID.bytes, usdcMint.bytes),
            ASSOCIATED_TOKEN_PROGRAM_ID
        ).getOrThrow()

        val configOwner = rpc.accountOwner(Base58.encode(config.bytes))
            ?: throw FundingUnavailableException("AIRNOTE devnet config is unavailable")
        require(configOwner == Gate04Verifier.PROGRAM_ID) { "AIRNOTE config is owned by the wrong program" }
        require(rpc.accountOwner(Base58.encode(notePda.bytes)) == null) { "this Airnote note already exists on-chain" }

        val available = rpc.tokenBalanceBaseUnits(Base58.encode(funderToken.bytes))
            ?: throw FundingUnavailableException(
                "Selected wallet has no Circle Devnet USDC token account (" + Base58.encode(funderToken.bytes) + ")"
            )
        require(available >= note.amountBaseUnits) {
            "Insufficient Devnet USDC: have $available base units, need " + note.amountBaseUnits
        }

        val chainTime = rpc.finalizedChainTimeSeconds()
        val expiresAt = Math.addExact(chainTime, note.lifetimeSeconds)
        val latest = rpc.latestBlockhash()

        val data = discriminator("global:create_note") +
            noteId +
            u64le(note.amountBaseUnits) +
            owner +
            u64le(expiresAt) +
            attestation +
            byteArrayOf(1)

        val createNote = TransactionInstruction(
            programId,
            listOf(
                AccountMeta(funder, true, true),
                AccountMeta(config, false, false),
                AccountMeta(usdcMint, false, false),
                AccountMeta(funderToken, false, true),
                AccountMeta(notePda, false, true),
                AccountMeta(vault, false, true),
                AccountMeta(TOKEN_PROGRAM_ID, false, false),
                AccountMeta(SystemProgram.PROGRAM_ID, false, false)
            ),
            data
        )

        val unsigned = Message.Builder()
            .setRecentBlockhash(latest.blockhash)
            .addInstruction(createNote)
            .build()
            .toUnsignedTransaction()
            .serialize()

        return Plan(
            unsignedTransaction = unsigned,
            funder = funder,
            noteAccount = notePda,
            vaultAccount = vault,
            minContextSlot = latest.contextSlot,
            lastValidBlockHeight = latest.lastValidBlockHeight
        )
    }

    private fun discriminator(label: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(label.toByteArray(Charsets.UTF_8)).copyOfRange(0, 8)

    private fun u64le(value: Long): ByteArray {
        require(value >= 0) { "u64 semantic value must fit signed Long" }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    }

    private fun String.hexBytes(expectedBytes: Int, label: String): ByteArray {
        require(length == expectedBytes * 2 && all { it.digitToIntOrNull(16) != null }) {
            "$label must be exactly $expectedBytes bytes of hex"
        }
        return ByteArray(expectedBytes) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    class FundingUnavailableException(message: String) : Exception(message)

    private val CONFIG_SEED = "config".toByteArray(Charsets.UTF_8)
    private val NOTE_SEED = "note".toByteArray(Charsets.UTF_8)
    private val VAULT_SEED = "vault".toByteArray(Charsets.UTF_8)
    private val TOKEN_PROGRAM_ID = SolanaPublicKey.from("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    private val ASSOCIATED_TOKEN_PROGRAM_ID = SolanaPublicKey.from("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
}
