package com.airnote.protocol

import androidx.annotation.RequiresApi
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object MintCertificateVerifier {
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    )

    data class SignedMintCertificate(
        val payload: AirnoteProtocol.MintCertificatePayload,
        val attestorSignature: ByteArray
    )

    class AttestorKeyring(keys: Map<Long, ByteArray>) {
        private val rawKeys = keys.mapValues { (_, key) ->
            require(key.size == 32) { "Ed25519 attestor public key must be 32 bytes" }
            key.copyOf()
        }

        fun keyFor(id: Long): ByteArray? = rawKeys[id]?.copyOf()
    }

    /**
     * Verifies only the cryptographic mint-certificate binding.
     * Network/program/mint/expiry/risk policy checks remain caller responsibilities.
     *
     * Android exposes the Ed25519 Signature algorithm from API 33 onward.
     */
    @RequiresApi(33)
    fun verify(
        mintCore: AirnoteProtocol.MintCore,
        certificate: SignedMintCertificate,
        keyring: AttestorKeyring
    ): Boolean {
        if (certificate.attestorSignature.size != 64) return false

        val commitment = AirnoteProtocol.noteCommitment(mintCore)
        if (!commitment.contentEquals(certificate.payload.noteCommitment)) return false

        val rawPublicKey = keyring.keyFor(certificate.payload.attestorKeyId) ?: return false
        return runCatching {
            val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                X509EncodedKeySpec(ED25519_SPKI_PREFIX + rawPublicKey)
            )
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update(AirnoteProtocol.encodeMintCertificatePayload(certificate.payload))
            verifier.verify(certificate.attestorSignature)
        }.getOrDefault(false)
    }
}
