package com.airnote.hardwareprobe

import com.airnote.protocol.Gate04CertificateJson
import com.airnote.protocol.Gate04Verifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AirnoteAttestorClient(
    private val endpoint: String = DEFAULT_ENDPOINT
) {
    suspend fun certify(
        note: LocalAirnote,
        programId: String,
        noteAccount: String,
        vaultAccount: String,
        mintSignature: String
    ): String = withContext(Dispatchers.IO) {
        val request = JSONObject()
            .put("programId", programId)
            .put("noteAccount", noteAccount)
            .put("vaultAccount", vaultAccount)
            .put("mintSignature", mintSignature)
            .toString()

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(request.toByteArray(Charsets.UTF_8)) }
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                ?: throw AttestorException("AIRNOTE attestor returned HTTP " + connection.responseCode)

            if (connection.responseCode !in 200..299) {
                throw AttestorException("AIRNOTE attestor rejected certification: $body")
            }

            val certified = Gate04CertificateJson.parse(body)
            val check = Gate04Verifier.verifyCertificate(
                certified,
                System.currentTimeMillis() / 1000L
            )
            require(check.accepted) { "attestor returned certificate rejected by AIRNOTE: " + check.reason }
            require(certified.mintCore.noteId.hex() == note.noteIdHex) { "certified note id does not match local Airnote" }
            require(certified.mintCore.amountBaseUnits == note.amountBaseUnits) { "certified amount does not match local Airnote" }
            require(certified.mintCore.ownerP256Compressed.hex() == note.ownerP256CompressedHex) {
                "certified P-256 owner does not match StrongBox key"
            }
            require(certified.mintCore.deviceAttestationDigest.hex() == note.deviceAttestationDigestHex) {
                "certified attestation digest does not match local Airnote"
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    class AttestorException(message: String) : Exception(message)

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val DEFAULT_ENDPOINT = "https://airnote.cash/api/certify"
    }
}
