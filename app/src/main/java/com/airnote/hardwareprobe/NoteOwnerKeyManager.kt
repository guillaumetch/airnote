package com.airnote.hardwareprobe

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant

/**
 * Creates one persistent StrongBox P-256 owner key per Airnote.
 *
 * Unlike Gate 00, these keys deliberately do NOT request maxUsageCount=1. Gate 00
 * proved that Seeker enforces that counter in Android software, not StrongBox.
 * AIRNOTE therefore uses StrongBox for non-exportable signing identity and leaves
 * uniqueness/finality to the bounded-risk protocol + Solana settlement.
 */
class NoteOwnerKeyManager(private val context: Context) {

    data class Material(
        val alias: String,
        val noteId: ByteArray,
        val compressedPublicKey: ByteArray,
        val attestationChallenge: ByteArray,
        val attestationDigest: ByteArray,
        val attestationChainLength: Int,
        val attestationVersion: Int,
        val attestationSecurityLevel: String,
        val keyMintVersion: Int,
        val keyMintSecurityLevel: String,
        val privateKeyExportable: Boolean
    ) {
        fun report(): String = buildString {
            appendLine("AIRNOTE — GATE 03 NOTE OWNER")
            appendLine()
            appendLine("TIMESTAMP=${Instant.now()}")
            appendLine("NOTE_ID_HEX=${noteId.hex()}")
            appendLine("KEY_ALIAS=$alias")
            appendLine("OWNER_P256_COMPRESSED_HEX=${compressedPublicKey.hex()}")
            appendLine("ATTESTATION_CHALLENGE_HEX=${attestationChallenge.hex()}")
            appendLine("DEVICE_ATTESTATION_DIGEST_HEX=${attestationDigest.hex()}")
            appendLine("ATTESTATION_CHAIN_LENGTH=$attestationChainLength")
            appendLine("ATTESTATION_VERSION=$attestationVersion")
            appendLine("ATTESTATION_SECURITY_LEVEL=$attestationSecurityLevel")
            appendLine("KEYMINT_VERSION=$keyMintVersion")
            appendLine("KEYMINT_SECURITY_LEVEL=$keyMintSecurityLevel")
            appendLine("PRIVATE_KEY_EXPORTABLE=$privateKeyExportable")
            appendLine("MAX_USAGE_COUNT_REQUESTED=false")
            appendLine("OFFLINE_UNIQUENESS=NOT_HARDWARE_ENFORCED")
            appendLine()
            appendLine("Keep this key alias on the Seeker until the note is redeemed or refunded.")
        }
    }

    fun create(): Material {
        require(context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
            "AIRNOTE Gate 03 requires StrongBox on this device"
        }

        val noteId = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val alias = "airnote-note-v1-${noteId.hex()}"
        val challenge = sha256(ATTESTATION_CHALLENGE_DOMAIN + noteId)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        try {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .setIsStrongBoxBacked(true)
                .build()
            generator.initialize(spec)
            val pair = generator.generateKeyPair()

            val privateKey = pair.private
            val privateExportable = privateKey.encoded != null
            require(!privateExportable) { "StrongBox private key unexpectedly exportable" }

            val keyInfo = KeyFactory.getInstance(privateKey.algorithm, "AndroidKeyStore")
                .getKeySpec(privateKey, KeyInfo::class.java)
            require(keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX) {
                "note-owner key is not StrongBox-backed: securityLevel=${keyInfo.securityLevel}"
            }

            val chain = keyStore.getCertificateChain(alias)?.toList().orEmpty()
            require(chain.isNotEmpty()) { "StrongBox attestation chain missing" }
            val leaf = chain.first() as? X509Certificate
                ?: error("StrongBox attestation leaf is not X509")
            val parsed = AndroidAttestation.parse(leaf, challenge)
            require(parsed.challengeMatches) { "StrongBox attestation challenge mismatch" }
            require(parsed.attestationSecurityLevel == "STRONGBOX") {
                "attestation security level is ${parsed.attestationSecurityLevel}, expected STRONGBOX"
            }
            require(parsed.keyMintSecurityLevel == "STRONGBOX") {
                "KeyMint security level is ${parsed.keyMintSecurityLevel}, expected STRONGBOX"
            }

            val publicKey = pair.public as ECPublicKey
            return Material(
                alias = alias,
                noteId = noteId,
                compressedPublicKey = Ecdsa.compressedPublicKey(publicKey),
                attestationChallenge = challenge,
                attestationDigest = attestationDigest(chain),
                attestationChainLength = chain.size,
                attestationVersion = parsed.attestationVersion,
                attestationSecurityLevel = parsed.attestationSecurityLevel,
                keyMintVersion = parsed.keyMintVersion,
                keyMintSecurityLevel = parsed.keyMintSecurityLevel,
                privateKeyExportable = privateExportable
            )
        } catch (t: Throwable) {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            throw t
        }
    }

    /** Signs a canonical AIRNOTE transfer payload and returns compact low-S r||s. */
    fun signTransfer(alias: String, payload: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
            ?: error("AIRNOTE note-owner private key not found: $alias")
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(payload)
        val rs = Ecdsa.parseDerSignature(signer.sign())
        return Ecdsa.compact(rs.r, Ecdsa.normalizeLowS(rs.s))
    }

    fun contains(alias: String): Boolean =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)

    private fun attestationDigest(chain: List<Certificate>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ATTESTATION_DIGEST_DOMAIN)
        digest.update(u32Le(chain.size))
        chain.forEach { certificate ->
            val der = certificate.encoded
            digest.update(u32Le(der.size))
            digest.update(der)
        }
        return digest.digest()
    }

    private fun u32Le(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    companion object {
        private val ATTESTATION_CHALLENGE_DOMAIN =
            "AIRNOTE_NOTE_ATTEST_CHALLENGE_V1".toByteArray(Charsets.US_ASCII)
        private val ATTESTATION_DIGEST_DOMAIN =
            "AIRNOTE_ANDROID_ATTESTATION_CHAIN_V1".toByteArray(Charsets.US_ASCII)
    }
}
