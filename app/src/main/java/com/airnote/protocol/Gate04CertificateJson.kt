package com.airnote.protocol

import org.json.JSONObject

/** Parses the local certificate JSON emitted by tools/devnet/certify-airnote.sh. */
object Gate04CertificateJson {
    fun parse(json: String): Gate04Wire.CertifiedAirnote {
        val root = JSONObject(json)
        require(root.getInt("version") == AirnoteProtocol.VERSION) { "unsupported certificate package version" }

        val mint = root.getJSONObject("mint_core")
        val networkCode = mint.getInt("network")
        val network = AirnoteProtocol.Network.entries.firstOrNull { it.code == networkCode }
            ?: throw IllegalArgumentException("unsupported AIRNOTE network code $networkCode")
        val core = AirnoteProtocol.MintCore(
            network = network,
            noteId = mint.getString("note_id_hex").hexBytes(32, "note id"),
            programId = Base58.decode32(mint.getString("program_id"), "program id"),
            noteAccount = Base58.decode32(mint.getString("note_account"), "note account"),
            usdcMint = Base58.decode32(mint.getString("usdc_mint"), "USDC mint"),
            amountBaseUnits = mint.getLong("amount_base_units"),
            ownerP256Compressed = mint.getString("owner_p256_compressed_hex").hexBytes(33, "owner P-256 key"),
            issuedAtUnixSeconds = mint.getLong("issued_at_unix_s"),
            expiresAtUnixSeconds = mint.getLong("expires_at_unix_s"),
            deviceAttestationDigest = mint.getString("device_attestation_digest_hex").hexBytes(32, "attestation digest"),
            riskTier = mint.getInt("risk_tier")
        )
        // Force all canonical core validations now.
        AirnoteProtocol.encodeMintCore(core)

        val cert = root.getJSONObject("certificate")
        val payload = AirnoteProtocol.MintCertificatePayload(
            noteCommitment = cert.getString("note_commitment_hex").hexBytes(32, "note commitment"),
            finalizedSlot = cert.getLong("finalized_slot"),
            mintTransactionSignature = cert.getString("mint_tx_signature_hex").hexBytes(64, "mint transaction signature"),
            attestorKeyId = cert.getLong("attestor_key_id")
        )
        val encodedPayload = AirnoteProtocol.encodeMintCertificatePayload(payload)
        if (cert.has("certificate_payload_hex")) {
            val packaged = cert.getString("certificate_payload_hex").hexBytes(encodedPayload.size, "certificate payload")
            require(packaged.contentEquals(encodedPayload)) { "certificate payload JSON does not match canonical fields" }
        }

        return Gate04Wire.CertifiedAirnote(
            mintCore = core,
            certificate = MintCertificateVerifier.SignedMintCertificate(
                payload = payload,
                attestorSignature = cert.getString("attestor_signature_hex").hexBytes(64, "attestor signature")
            )
        )
    }

    private fun String.hexBytes(expectedBytes: Int, label: String): ByteArray {
        require(length == expectedBytes * 2 && all { it.digitToIntOrNull(16) != null }) {
            "$label must be exactly $expectedBytes bytes of hex"
        }
        return ByteArray(expectedBytes) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
