package com.airnote.protocol

import androidx.annotation.RequiresApi

/** Offline policy + cryptographic verification for the live Gate 04 Devnet demo. */
object Gate04Verifier {
    const val PROGRAM_ID = "DyT3zjATxr4Fg28Ne3DjjV5Q6rd6JWx2Vo7agZZxZFx9"
    const val DEVNET_USDC = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    const val ATTESTOR_KEY_ID = 1L
    const val ATTESTOR_PUBLIC_KEY_HEX = "57d04f590d2d5f62a32bf6c98421c0aa75c394cad648ae46b3c7ecc97d1a55c6"
    const val REQUEST_MAX_LIFETIME_SECONDS = 600L

    private val expectedProgram = Base58.decode32(PROGRAM_ID, "AIRNOTE program id")
    private val expectedUsdc = Base58.decode32(DEVNET_USDC, "Devnet USDC mint")
    private val allowedAmounts = setOf(1_000_000L, 5_000_000L, 10_000_000L)
    private val keyring = MintCertificateVerifier.AttestorKeyring(
        mapOf(ATTESTOR_KEY_ID to ATTESTOR_PUBLIC_KEY_HEX.hexBytes(32, "attestor public key"))
    )

    data class Check(
        val accepted: Boolean,
        val reason: String,
        val noteCommitment: ByteArray? = null
    )

    @RequiresApi(33)
    fun verifyCertificate(
        airnote: Gate04Wire.CertifiedAirnote,
        nowUnixSeconds: Long
    ): Check {
        val core = airnote.mintCore
        val certificate = airnote.certificate

        if (core.network != AirnoteProtocol.Network.DEVNET) return fail("wrong network")
        if (!core.programId.contentEquals(expectedProgram)) return fail("wrong AIRNOTE program")
        if (!core.usdcMint.contentEquals(expectedUsdc)) return fail("wrong USDC mint")
        if (core.amountBaseUnits !in allowedAmounts) return fail("unsupported denomination")
        if (core.riskTier !in 0..1) return fail("risk tier exceeds Gate 04 policy")
        if (core.issuedAtUnixSeconds > nowUnixSeconds + 300) return fail("note issue time is in the future")
        if (core.expiresAtUnixSeconds <= nowUnixSeconds) return fail("note is expired")
        if (core.expiresAtUnixSeconds - core.issuedAtUnixSeconds > 86_400) return fail("note lifetime exceeds protocol maximum")
        if (certificate.payload.finalizedSlot <= 0) return fail("invalid finalized slot")
        if (certificate.payload.attestorKeyId != ATTESTOR_KEY_ID) return fail("unknown attestor key id")

        val commitment = AirnoteProtocol.noteCommitment(core)
        if (!commitment.contentEquals(certificate.payload.noteCommitment)) return fail("certificate commitment mismatch")
        if (!MintCertificateVerifier.verify(core, certificate, keyring)) return fail("invalid mint certificate signature")
        return pass("mint certificate verified", commitment)
    }

    fun validateRecipientRequest(
        core: AirnoteProtocol.MintCore,
        request: AirnoteProtocol.RecipientRequest,
        nowUnixSeconds: Long
    ): Check {
        if (request.recipientSolanaWallet.all { it == 0.toByte() }) return fail("recipient wallet is zero")
        if (request.requestCreatedAtUnixSeconds > nowUnixSeconds + 120) return fail("recipient request is from the future")
        if (request.requestExpiresAtUnixSeconds <= nowUnixSeconds) return fail("recipient request expired")
        if (request.requestExpiresAtUnixSeconds > core.expiresAtUnixSeconds) return fail("recipient request outlives note")
        val lifetime = request.requestExpiresAtUnixSeconds - request.requestCreatedAtUnixSeconds
        if (lifetime !in 1..REQUEST_MAX_LIFETIME_SECONDS) return fail("recipient request lifetime exceeds policy")
        return pass("recipient request valid")
    }

    @RequiresApi(33)
    fun verifyOfflineTransfer(
        transfer: Gate04Wire.OfflineTransfer,
        expectedRequest: AirnoteProtocol.RecipientRequest,
        nowUnixSeconds: Long
    ): Check {
        val certificateCheck = verifyCertificate(transfer.airnote, nowUnixSeconds)
        if (!certificateCheck.accepted) return certificateCheck

        val actualRequestBytes = AirnoteProtocol.encodeRecipientRequest(transfer.recipientRequest)
        val expectedRequestBytes = AirnoteProtocol.encodeRecipientRequest(expectedRequest)
        if (!actualRequestBytes.contentEquals(expectedRequestBytes)) return fail("transfer is not bound to this receiver request")

        val requestCheck = validateRecipientRequest(transfer.airnote.mintCore, transfer.recipientRequest, nowUnixSeconds)
        if (!requestCheck.accepted) return requestCheck

        val commitment = certificateCheck.noteCommitment ?: return fail("missing note commitment")
        val payload = AirnoteProtocol.encodeTransferPayload(commitment, transfer.recipientRequest)
        if (!P256TransferVerifier.verify(
                transfer.airnote.mintCore.ownerP256Compressed,
                payload,
                transfer.transferSignature
            )
        ) return fail("invalid StrongBox P-256 transfer signature")

        return pass("offline transfer verified", commitment)
    }

    private fun pass(reason: String, commitment: ByteArray? = null) = Check(true, reason, commitment)
    private fun fail(reason: String) = Check(false, reason)

    private fun String.hexBytes(expectedBytes: Int, label: String): ByteArray {
        require(length == expectedBytes * 2 && all { it.digitToIntOrNull(16) != null }) {
            "$label must be exactly $expectedBytes bytes of hex"
        }
        return ByteArray(expectedBytes) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
