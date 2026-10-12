package com.airnote.hardwareprobe

import com.airnote.protocol.Base58
import com.airnote.protocol.Gate04Verifier
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionParams
import com.solana.mobilewalletadapter.clientlib.TransactionResult

class AirnoteFundingCoordinator(
    private val store: LocalAirnoteStore,
    private val rpc: AirnoteSolanaRpc = AirnoteSolanaRpc(),
    private val walletAdapter: MobileWalletAdapter = AirnoteWalletAdapterFactory.create(),
    private val attestor: AirnoteAttestorClient = AirnoteAttestorClient()
) {
    data class Result(
        val note: LocalAirnote,
        val fundingSignature: String,
        val finalizedSlot: Long
    )

    private data class Submission(
        val signature: String,
        val plan: AirnoteFundingBuilder.Plan
    )

    suspend fun createReadyOffline(noteIdHex: String, sender: ActivityResultSender): Result {
        var note = requireNotNull(store.get(noteIdHex)) { "unknown local Airnote $noteIdHex" }

        if (note.state == LocalAirnoteState.READY_OFFLINE || note.state == LocalAirnoteState.HANDOFF_AUTHORIZED) {
            return Result(
                note = note,
                fundingSignature = requireNotNull(note.fundingSignature),
                finalizedSlot = requireNotNull(note.finalizedSlot)
            )
        }

        if (note.state == LocalAirnoteState.FUNDED_FINALIZED) {
            return certifyFinalized(note)
        }

        if (note.fundingSignature != null) {
            return finalizeAndCertify(note)
        }

        store.markFundingStarted(noteIdHex)

        val submission = try {
            when (val result = walletAdapter.transact(sender) { authResult ->
                val wallet = authResult.accounts.firstOrNull()?.publicKey
                    ?: error("wallet authorization returned no account")
                val currentNote = requireNotNull(store.get(noteIdHex))
                currentNote.skrAccessWalletBase58?.let { expectedWallet ->
                    val fundingWallet = Base58.encode(wallet)
                    require(fundingWallet == expectedWallet) {
                        "SKR Reserve+ must be funded by the same wallet that unlocked access"
                    }
                    val skr = SkrAccessVerifier().check(wallet)
                    require(skr.isHolder) {
                        "SKR Reserve+ wallet no longer holds liquid SKR"
                    }
                }
                val plan = AirnoteFundingBuilder.prepare(
                    currentNote,
                    wallet,
                    rpc
                )
                val params = TransactionParams(
                    minContextSlot = plan.minContextSlot,
                    commitment = "confirmed",
                    skipPreflight = false,
                    maxRetries = 5,
                    waitForCommitmentToSendNextTransaction = null
                )
                val signatureBytes = signAndSendTransactions(
                    arrayOf(plan.unsignedTransaction),
                    params
                ).signatures.firstOrNull() ?: error("wallet returned no funding signature")
                Submission(Base58.encode(signatureBytes), plan)
            }) {
                is TransactionResult.Success -> result.payload
                is TransactionResult.NoWalletFound -> throw WalletUnavailableException(result.message)
                is TransactionResult.Failure -> throw WalletSubmissionException(result.message, result.e)
            }
        } catch (error: Throwable) {
            store.markRetryableError(noteIdHex, error.message ?: error::class.java.simpleName)
            throw error
        }

        note = store.markFundingSubmitted(
            noteIdHex = noteIdHex,
            signature = submission.signature,
            lastValidBlockHeight = submission.plan.lastValidBlockHeight,
            noteAccount = Base58.encode(submission.plan.noteAccount.bytes),
            vaultAccount = Base58.encode(submission.plan.vaultAccount.bytes)
        )

        return finalizeAndCertify(note)
    }

    private suspend fun finalizeAndCertify(note: LocalAirnote): Result {
        val signature = requireNotNull(note.fundingSignature)
        return try {
            val finalized = rpc.awaitFinalized(signature)
            val funded = store.markFundingFinalized(note.noteIdHex, finalized.slot)
            certifyFinalized(funded)
        } catch (error: AirnoteSolanaRpc.TransactionFailedException) {
            store.resetFundingAfterDefinitiveFailure(
                note.noteIdHex,
                error.message ?: "funding transaction failed"
            )
            throw error
        } catch (error: Throwable) {
            store.markRetryableError(note.noteIdHex, error.message ?: error::class.java.simpleName)
            throw error
        }
    }

    private suspend fun certifyFinalized(note: LocalAirnote): Result {
        val signature = requireNotNull(note.fundingSignature) { "finalized Airnote has no funding signature" }
        val slot = requireNotNull(note.finalizedSlot) { "finalized Airnote has no finalized slot" }
        val noteAccount = requireNotNull(note.noteAccount) { "finalized Airnote has no note account" }
        val vaultAccount = requireNotNull(note.vaultAccount) { "finalized Airnote has no vault account" }

        return try {
            val certificateJson = attestor.certify(
                note = note,
                programId = Gate04Verifier.PROGRAM_ID,
                noteAccount = noteAccount,
                vaultAccount = vaultAccount,
                mintSignature = signature
            )
            val ready = store.markReadyOffline(note.noteIdHex, certificateJson)
            Result(ready, signature, slot)
        } catch (error: Throwable) {
            store.markRetryableError(
                note.noteIdHex,
                "CERTIFICATION_PENDING: " + (error.message ?: error::class.java.simpleName)
            )
            throw CertificationPendingException(
                "USDC funding is finalized, but AIRNOTE certification is still pending. Retry without funding again.",
                error
            )
        }
    }

    class WalletUnavailableException(message: String) : Exception(message)
    class WalletSubmissionException(message: String, cause: Throwable) : Exception(message, cause)
    class CertificationPendingException(message: String, cause: Throwable) : Exception(message, cause)
}
