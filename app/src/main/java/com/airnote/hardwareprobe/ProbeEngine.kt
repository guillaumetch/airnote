package com.airnote.hardwareprobe

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

class ProbeEngine(private val context: Context) {

    enum class Verdict { PASS, FAIL, INCONCLUSIVE }

    data class Result(
        val verdict: Verdict,
        val report: String
    )

    fun run(forceStrongBox: Boolean = false): Result {
        val pm = context.packageManager
        val singleUseFeature = pm.hasSystemFeature(PackageManager.FEATURE_KEYSTORE_SINGLE_USE_KEY)
        val limitedUseFeature = pm.hasSystemFeature(PackageManager.FEATURE_KEYSTORE_LIMITED_USE_KEY)
        val strongBoxFeature = pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        val alias = "airnote-gate00-${if (forceStrongBox) "strongbox" else "default"}-${UUID.randomUUID()}"
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sessionId = UUID.randomUUID().toString()

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        var keyGenerated = false
        var securityLevel = "UNKNOWN"
        var hardwareBacked = false
        var privateExportable = false
        var compressedPub = ""
        var certChainLength = 0
        var certChainBase64 = emptyList<String>()

        var attestationParsed = false
        var attestationParseError = ""
        var attestationVersion: Int? = null
        var attestationSecurity = "UNPARSED"
        var keyMintVersion: Int? = null
        var keyMintSecurity = "UNPARSED"
        var challengeMatches = false
        var softwareUsageCount: Long? = null
        var hardwareUsageCount: Long? = null

        var sign1 = "NOT_RUN"
        var sign1Verified = false
        var compactSig = ""
        var lowS = false
        var sign2 = "NOT_RUN"
        var sign2Exception = ""
        var aliasExistsAfter = false
        var fatalFailure: String? = null

        try {
            if (forceStrongBox && !strongBoxFeature) {
                throw StrongBoxUnavailableException("FEATURE_STRONGBOX_KEYSTORE not present")
            }

            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setMaxUsageCount(1)
                .setAttestationChallenge(challenge)

            if (forceStrongBox) builder.setIsStrongBoxBacked(true)

            generator.initialize(builder.build())
            val pair = generator.generateKeyPair()
            keyGenerated = true

            val privateKey = pair.private
            privateExportable = privateKey.encoded != null
            val publicKey = pair.public as ECPublicKey
            compressedPub = Ecdsa.compressedPublicKey(publicKey).hex()

            val keyFactory = KeyFactory.getInstance(privateKey.algorithm, "AndroidKeyStore")
            val info = keyFactory.getKeySpec(privateKey, KeyInfo::class.java)
            securityLevel = if (Build.VERSION.SDK_INT >= 31) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TRUSTED_ENVIRONMENT"
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
                    else -> "UNKNOWN(${info.securityLevel})"
                }
            } else if (info.isInsideSecureHardware) {
                "HARDWARE_LEGACY"
            } else {
                "SOFTWARE"
            }
            hardwareBacked = securityLevel == "TRUSTED_ENVIRONMENT" || securityLevel == "STRONGBOX" || securityLevel == "HARDWARE_LEGACY"

            val chain = ks.getCertificateChain(alias)?.toList().orEmpty()
            certChainLength = chain.size
            certChainBase64 = chain.map { Base64.getEncoder().encodeToString(it.encoded) }

            val leaf = chain.firstOrNull() as? X509Certificate
            if (leaf != null) {
                try {
                    val parsed = AndroidAttestation.parse(leaf, challenge)
                    attestationParsed = true
                    attestationVersion = parsed.attestationVersion
                    attestationSecurity = parsed.attestationSecurityLevel
                    keyMintVersion = parsed.keyMintVersion
                    keyMintSecurity = parsed.keyMintSecurityLevel
                    challengeMatches = parsed.challengeMatches
                    softwareUsageCount = parsed.softwareUsageCountLimit
                    hardwareUsageCount = parsed.hardwareUsageCountLimit
                } catch (t: Throwable) {
                    attestationParseError = "${t::class.java.name}: ${t.message.orEmpty()}"
                }
            } else {
                attestationParseError = "No X509 leaf certificate"
            }

            val message1 = "AIRNOTE_GATE00_V1:$sessionId".toByteArray(Charsets.UTF_8)
            val signer1 = Signature.getInstance("SHA256withECDSA")
            signer1.initSign(privateKey)
            signer1.update(message1)
            val der = signer1.sign()
            sign1 = "SUCCESS"

            val rs = Ecdsa.parseDerSignature(der)
            val normalizedS = Ecdsa.normalizeLowS(rs.s)
            lowS = normalizedS == rs.s
            compactSig = Ecdsa.compact(rs.r, normalizedS).hex()

            val verifier = Signature.getInstance("SHA256withECDSA")
            val independentPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey.encoded))
            verifier.initVerify(independentPub)
            verifier.update(message1)
            val derForVerify = derEncode(rs.r, normalizedS)
            sign1Verified = verifier.verify(derForVerify)

            val message2 = "AIRNOTE_GATE00_SECOND_USE:$sessionId".toByteArray(Charsets.UTF_8)
            try {
                val signer2 = Signature.getInstance("SHA256withECDSA")
                signer2.initSign(privateKey)
                signer2.update(message2)
                signer2.sign()
                sign2 = "UNEXPECTED_SUCCESS"
            } catch (t: Throwable) {
                sign2 = "EXPECTED_FAILURE"
                sign2Exception = "${t::class.java.name}: ${t.message.orEmpty()}"
            }
            aliasExistsAfter = ks.containsAlias(alias)
        } catch (t: Throwable) {
            fatalFailure = "${t::class.java.name}: ${t.message.orEmpty()}"
        }

        val hardwareUsageEnforced = attestationParsed && challengeMatches && hardwareUsageCount == 1L
        val softwareOnlyUsageEnforced = attestationParsed && softwareUsageCount == 1L && hardwareUsageCount != 1L

        val definitiveFailure = fatalFailure != null ||
            !keyGenerated ||
            privateExportable ||
            !hardwareBacked ||
            sign1 != "SUCCESS" ||
            !sign1Verified ||
            sign2 == "UNEXPECTED_SUCCESS" ||
            (attestationParsed && !challengeMatches) ||
            softwareOnlyUsageEnforced

        val verdict = when {
            definitiveFailure -> Verdict.FAIL
            keyGenerated && hardwareBacked && sign1Verified && sign2 == "EXPECTED_FAILURE" && hardwareUsageEnforced -> Verdict.PASS
            else -> Verdict.INCONCLUSIVE
        }

        val report = buildString {
            appendLine("AIRNOTE — SEEKER HARDWARE GATE 00")
            appendLine()
            appendLine("PROBE MODE")
            appendLine(if (forceStrongBox) "STRONGBOX_REQUESTED" else "DEFAULT_KEYSTORE")
            appendLine()
            appendLine("TIMESTAMP")
            appendLine(Instant.now().toString())
            appendLine()
            appendLine("DEVICE")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE}")
            appendLine("API: ${Build.VERSION.SDK_INT}")
            appendLine()
            appendLine("FEATURES")
            appendLine("Single-use key feature: $singleUseFeature")
            appendLine("Limited-use key feature: $limitedUseFeature")
            appendLine("StrongBox: $strongBoxFeature")
            appendLine()
            appendLine("KEY")
            appendLine("Alias: $alias")
            appendLine("Algorithm: EC")
            appendLine("Curve: secp256r1")
            appendLine("Requested maxUsageCount: 1")
            appendLine("Requested StrongBox: $forceStrongBox")
            appendLine("Security level: $securityLevel")
            appendLine("Hardware backed: $hardwareBacked")
            appendLine("Public key compressed: $compressedPub")
            appendLine()
            appendLine("ATTESTATION")
            appendLine("Chain length: $certChainLength")
            appendLine("Parsed: $attestationParsed")
            appendLine("Parse error: $attestationParseError")
            appendLine("Attestation version: ${attestationVersion ?: "UNKNOWN"}")
            appendLine("Attestation security level: $attestationSecurity")
            appendLine("KeyMint version: ${keyMintVersion ?: "UNKNOWN"}")
            appendLine("KeyMint security level: $keyMintSecurity")
            appendLine("Challenge matches: $challengeMatches")
            appendLine("usageCountLimit softwareEnforced: ${softwareUsageCount ?: "ABSENT"}")
            appendLine("usageCountLimit hardwareEnforced: ${hardwareUsageCount ?: "ABSENT"}")
            appendLine("Hardware one-use enforced: $hardwareUsageEnforced")
            appendLine("Certificate chain SHA-256:")
            certChainBase64.forEachIndexed { index, b64 ->
                val hash = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(b64)).hex()
                appendLine("  [$index] $hash")
            }
            appendLine()
            appendLine("SIGNATURE 1")
            appendLine("Result: $sign1")
            appendLine("Compact r||s: $compactSig")
            appendLine("Low-S: $lowS")
            appendLine("Local verification: $sign1Verified")
            appendLine()
            appendLine("SIGNATURE 2")
            appendLine("Result: $sign2")
            appendLine("Exception: $sign2Exception")
            appendLine("Alias exists afterwards: $aliasExistsAfter")
            appendLine()
            appendLine("SECURITY")
            appendLine("Private key exportable: $privateExportable")
            appendLine("MUST BE FALSE")
            if (fatalFailure != null) appendLine("Fatal error: $fatalFailure")
            appendLine()
            appendLine("FINAL")
            appendLine(verdict.name)
            when {
                verdict == Verdict.PASS -> appendLine("Reason: usageCountLimit=1 is attested in the hardware-enforced authorization list and second use is rejected.")
                softwareOnlyUsageEnforced -> appendLine("Reason: usageCountLimit=1 is software-enforced, not hardware-enforced. This does not satisfy AIRNOTE's offline double-spend threat model.")
                verdict == Verdict.INCONCLUSIVE -> appendLine("Reason: hardware one-use enforcement could not be conclusively established from attestation.")
            }
        }
        return Result(verdict, report)
    }

    private fun derEncode(r: java.math.BigInteger, s: java.math.BigInteger): ByteArray {
        fun integer(v: java.math.BigInteger): ByteArray {
            val bytes = v.toByteArray()
            return byteArrayOf(0x02, bytes.size.toByte()) + bytes
        }
        val body = integer(r) + integer(s)
        require(body.size < 128) { "Unexpected ECDSA DER size" }
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
