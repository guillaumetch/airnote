package com.airnote.hardwareprobe

import android.content.Context
import com.airnote.protocol.Gate04CertificateJson
import org.json.JSONObject
import java.io.File

enum class LocalAirnoteState {
    PREPARED,
    FUNDING,
    FUNDED_FINALIZED,
    READY_OFFLINE,
    HANDOFF_AUTHORIZED,
    REFUNDED
}

data class LocalAirnote(
    val noteIdHex: String,
    val keyAlias: String,
    val ownerP256CompressedHex: String,
    val deviceAttestationDigestHex: String,
    val amountUsdc: Int,
    val lifetimeSeconds: Long,
    val createdAtUnixSeconds: Long,
    val state: LocalAirnoteState,
    val fundingSignature: String? = null,
    val fundingLastValidBlockHeight: Long? = null,
    val finalizedSlot: Long? = null,
    val noteAccount: String? = null,
    val vaultAccount: String? = null,
    val certificateJson: String? = null,
    val skrAccessWalletBase58: String? = null,
    val lastError: String? = null
) {
    val amountBaseUnits: Long get() = amountUsdc * 1_000_000L
}

class LocalAirnoteStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun create(
        material: NoteOwnerKeyManager.Material,
        amountUsdc: Int,
        lifetimeSeconds: Long,
        skrAccessWalletBase58: String? = null
    ): LocalAirnote {
        require(amountUsdc in setOf(1, 5, 10)) { "unsupported AIRNOTE denomination" }
        val note = LocalAirnote(
            noteIdHex = material.noteId.hex(),
            keyAlias = material.alias,
            ownerP256CompressedHex = material.compressedPublicKey.hex(),
            deviceAttestationDigestHex = material.attestationDigest.hex(),
            amountUsdc = amountUsdc,
            lifetimeSeconds = lifetimeSeconds,
            createdAtUnixSeconds = System.currentTimeMillis() / 1000L,
            state = LocalAirnoteState.PREPARED,
            skrAccessWalletBase58 = skrAccessWalletBase58
        )
        put(note)
        return note
    }

    fun get(noteIdHex: String): LocalAirnote? =
        prefs.getString(key(noteIdHex), null)?.let(::decode)

    fun all(): List<LocalAirnote> =
        prefs.all
            .filterKeys { it.startsWith(KEY_PREFIX) }
            .values
            .mapNotNull { (it as? String)?.let(::decode) }
            .sortedByDescending { it.createdAtUnixSeconds }

    fun sendable(nowUnixSeconds: Long = System.currentTimeMillis() / 1000L): List<LocalAirnote> =
        all().filter {
            it.state in setOf(LocalAirnoteState.READY_OFFLINE, LocalAirnoteState.HANDOFF_AUTHORIZED) &&
                !it.isExpired(nowUnixSeconds)
        }

    fun expiredUnsent(nowUnixSeconds: Long = System.currentTimeMillis() / 1000L): List<LocalAirnote> =
        all().filter {
            it.state == LocalAirnoteState.READY_OFFLINE && it.isExpired(nowUnixSeconds)
        }

    fun latestIncomplete(): LocalAirnote? =
        all().firstOrNull { it.state !in setOf(LocalAirnoteState.READY_OFFLINE, LocalAirnoteState.HANDOFF_AUTHORIZED) }

    fun markFundingStarted(noteIdHex: String): LocalAirnote =
        mutate(noteIdHex) { it.copy(state = LocalAirnoteState.FUNDING, lastError = null) }

    fun markFundingSubmitted(
        noteIdHex: String,
        signature: String,
        lastValidBlockHeight: Long,
        noteAccount: String,
        vaultAccount: String
    ): LocalAirnote = mutate(noteIdHex) {
        it.copy(
            state = LocalAirnoteState.FUNDING,
            fundingSignature = signature,
            fundingLastValidBlockHeight = lastValidBlockHeight,
            noteAccount = noteAccount,
            vaultAccount = vaultAccount,
            lastError = null
        )
    }

    fun markFundingFinalized(noteIdHex: String, finalizedSlot: Long): LocalAirnote =
        mutate(noteIdHex) {
            it.copy(
                state = LocalAirnoteState.FUNDED_FINALIZED,
                finalizedSlot = finalizedSlot,
                lastError = null
            )
        }

    fun markReadyOffline(noteIdHex: String, certificateJson: String): LocalAirnote =
        mutate(noteIdHex) {
            val certified = Gate04CertificateJson.parse(certificateJson)
            require(certified.mintCore.noteId.hex() == noteIdHex) { "certificate note id does not match local Airnote" }
            it.copy(
                state = LocalAirnoteState.READY_OFFLINE,
                certificateJson = certificateJson,
                lastError = null
            )
        }

    fun markRefunded(noteIdHex: String): LocalAirnote =
        mutate(noteIdHex) { it.copy(state = LocalAirnoteState.REFUNDED, lastError = null) }

    fun markHandoffAuthorized(noteIdHex: String): LocalAirnote =
        mutate(noteIdHex) {
            if (it.state == LocalAirnoteState.HANDOFF_AUTHORIZED) it
            else {
                require(it.state == LocalAirnoteState.READY_OFFLINE) { "Airnote is not ready to hand off" }
                it.copy(state = LocalAirnoteState.HANDOFF_AUTHORIZED)
            }
        }

    fun markRetryableError(noteIdHex: String, message: String): LocalAirnote =
        mutate(noteIdHex) { it.copy(lastError = message.take(240)) }

    fun resetFundingAfterDefinitiveFailure(noteIdHex: String, message: String): LocalAirnote =
        mutate(noteIdHex) {
            it.copy(
                state = LocalAirnoteState.PREPARED,
                fundingSignature = null,
                fundingLastValidBlockHeight = null,
                finalizedSlot = null,
                noteAccount = null,
                vaultAccount = null,
                lastError = message.take(240)
            )
        }

    fun importLegacyMintRequests(files: Array<out File>) {
        files
            .filter { it.isFile && it.name.startsWith("airnote-mint-request-") && it.name.endsWith(".txt") }
            .forEach { file ->
                runCatching {
                    val fields = parseFields(file.readText())
                    val id = requireNotNull(fields["NOTE_ID_HEX"])
                    if (get(id) != null) return@runCatching
                    val amount = requireNotNull(fields["AMOUNT_USDC"]).toInt()
                    val lifetime = requireNotNull(fields["LIFETIME_SECONDS"]).toLong()
                    val alias = requireNotNull(fields["KEY_ALIAS"])
                    val owner = requireNotNull(fields["OWNER_P256_COMPRESSED_HEX"])
                    val digest = requireNotNull(fields["DEVICE_ATTESTATION_DIGEST_HEX"])
                    require(id.length == 64 && owner.length == 66 && digest.length == 64)
                    put(
                        LocalAirnote(
                            noteIdHex = id,
                            keyAlias = alias,
                            ownerP256CompressedHex = owner,
                            deviceAttestationDigestHex = digest,
                            amountUsdc = amount,
                            lifetimeSeconds = lifetime,
                            createdAtUnixSeconds = file.lastModified().takeIf { it > 0 }?.div(1000L)
                                ?: System.currentTimeMillis() / 1000L,
                            state = LocalAirnoteState.PREPARED
                        )
                    )
                }
            }
    }

    private fun mutate(noteIdHex: String, block: (LocalAirnote) -> LocalAirnote): LocalAirnote {
        val current = requireNotNull(get(noteIdHex)) { "unknown local Airnote $noteIdHex" }
        return block(current).also(::put)
    }

    private fun put(note: LocalAirnote) {
        check(prefs.edit().putString(key(note.noteIdHex), encode(note)).commit()) {
            "could not persist local Airnote"
        }
    }

    private fun encode(note: LocalAirnote): String = JSONObject()
        .put("noteIdHex", note.noteIdHex)
        .put("keyAlias", note.keyAlias)
        .put("ownerP256CompressedHex", note.ownerP256CompressedHex)
        .put("deviceAttestationDigestHex", note.deviceAttestationDigestHex)
        .put("amountUsdc", note.amountUsdc)
        .put("lifetimeSeconds", note.lifetimeSeconds)
        .put("createdAtUnixSeconds", note.createdAtUnixSeconds)
        .put("state", note.state.name)
        .putNullable("fundingSignature", note.fundingSignature)
        .putNullable("fundingLastValidBlockHeight", note.fundingLastValidBlockHeight)
        .putNullable("finalizedSlot", note.finalizedSlot)
        .putNullable("noteAccount", note.noteAccount)
        .putNullable("vaultAccount", note.vaultAccount)
        .putNullable("certificateJson", note.certificateJson)
        .putNullable("skrAccessWalletBase58", note.skrAccessWalletBase58)
        .putNullable("lastError", note.lastError)
        .toString()

    private fun decode(raw: String): LocalAirnote? = runCatching {
        val json = JSONObject(raw)
        LocalAirnote(
            noteIdHex = json.getString("noteIdHex"),
            keyAlias = json.getString("keyAlias"),
            ownerP256CompressedHex = json.getString("ownerP256CompressedHex"),
            deviceAttestationDigestHex = json.getString("deviceAttestationDigestHex"),
            amountUsdc = json.getInt("amountUsdc"),
            lifetimeSeconds = json.getLong("lifetimeSeconds"),
            createdAtUnixSeconds = json.getLong("createdAtUnixSeconds"),
            state = LocalAirnoteState.valueOf(json.getString("state")),
            fundingSignature = json.optNullableString("fundingSignature"),
            fundingLastValidBlockHeight = json.optNullableLong("fundingLastValidBlockHeight"),
            finalizedSlot = json.optNullableLong("finalizedSlot"),
            noteAccount = json.optNullableString("noteAccount"),
            vaultAccount = json.optNullableString("vaultAccount"),
            certificateJson = json.optNullableString("certificateJson"),
            skrAccessWalletBase58 = json.optNullableString("skrAccessWalletBase58"),
            lastError = json.optNullableString("lastError")
        )
    }.getOrNull()

    private fun LocalAirnote.isExpired(nowUnixSeconds: Long): Boolean {
        val certificate = certificateJson ?: return false
        return runCatching {
            Gate04CertificateJson.parse(certificate).mintCore.expiresAtUnixSeconds <= nowUnixSeconds
        }.getOrDefault(false)
    }

    private fun parseFields(text: String): Map<String, String> =
        text.lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
            }
            .toMap()

    private fun key(noteIdHex: String) = KEY_PREFIX + noteIdHex

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        if (value == null) put(name, JSONObject.NULL) else put(name, value)

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (!has(name) || isNull(name)) null else getLong(name)

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val PREFS = "airnote_local_inventory_v1"
        private const val KEY_PREFIX = "note:"
    }
}
