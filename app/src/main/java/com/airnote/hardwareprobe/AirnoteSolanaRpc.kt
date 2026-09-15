package com.airnote.hardwareprobe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Deliberately small Solana JSON-RPC client for Gate 09.
 *
 * The wallet submits the signed transaction through Mobile Wallet Adapter. AIRNOTE only uses
 * its own RPC connection to obtain a recent blockhash, validate the known note account/chain
 * clock, and independently wait for finalization before showing Settled.
 */
class AirnoteSolanaRpc(
    private val rpcUrl: String = DEFAULT_DEVNET_RPC
) {
    data class LatestBlockhash(
        val blockhash: String,
        val contextSlot: Int,
        val lastValidBlockHeight: Long
    )

    data class FinalizedSignature(
        val slot: Long
    )

    suspend fun latestBlockhash(): LatestBlockhash {
        val result = call(
            "getLatestBlockhash",
            JSONArray().put(JSONObject().put("commitment", "confirmed"))
        ) as JSONObject
        val contextSlotLong = result.getJSONObject("context").getLong("slot")
        require(contextSlotLong in 0..Int.MAX_VALUE.toLong()) { "Solana context slot does not fit MWA parameter" }
        val value = result.getJSONObject("value")
        return LatestBlockhash(
            blockhash = value.getString("blockhash"),
            contextSlot = contextSlotLong.toInt(),
            lastValidBlockHeight = value.getLong("lastValidBlockHeight")
        )
    }

    suspend fun accountOwner(address: String): String? {
        val result = call(
            "getAccountInfo",
            JSONArray()
                .put(address)
                .put(JSONObject().put("commitment", "confirmed").put("encoding", "base64"))
        ) as JSONObject
        if (result.isNull("value")) return null
        return result.getJSONObject("value").getString("owner")
    }

    suspend fun finalizedChainTimeSeconds(): Long {
        val slot = (call("getSlot", JSONArray().put("finalized")) as Number).toLong()
        val blockTime = call("getBlockTime", JSONArray().put(slot))
        require(blockTime != JSONObject.NULL) { "Solana finalized block time unavailable for slot $slot" }
        return (blockTime as Number).toLong()
    }

    suspend fun awaitFinalized(
        signature: String,
        timeoutMillis: Long = FINALIZATION_TIMEOUT_MS
    ): FinalizedSignature {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMillis) {
            val result = call(
                "getSignatureStatuses",
                JSONArray()
                    .put(JSONArray().put(signature))
                    .put(JSONObject().put("searchTransactionHistory", true))
            ) as JSONObject
            val values = result.getJSONArray("value")
            if (!values.isNull(0)) {
                val status = values.getJSONObject(0)
                if (status.has("err") && !status.isNull("err")) {
                    throw TransactionFailedException(status.get("err").toString())
                }
                if (status.optString("confirmationStatus") == "finalized") {
                    return FinalizedSignature(status.getLong("slot"))
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        throw FinalizationTimeoutException(signature)
    }

    private suspend fun call(method: String, params: JSONArray): Any = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)
            .toString()

        val connection = (URL(rpcUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }
                ?: throw RpcException("Solana RPC returned HTTP ${connection.responseCode} with no body")
            val response = JSONObject(responseText)
            if (response.has("error") && !response.isNull("error")) {
                throw RpcException(response.getJSONObject("error").toString())
            }
            require(response.has("result")) { "Solana RPC response missing result for $method" }
            response.get("result")
        } finally {
            connection.disconnect()
        }
    }

    class RpcException(message: String) : Exception(message)
    class TransactionFailedException(message: String) : Exception("Solana transaction failed: $message")
    class FinalizationTimeoutException(signature: String) : Exception("Transaction was submitted but did not finalize in time: $signature")

    companion object {
        const val DEFAULT_DEVNET_RPC = "https://api.devnet.solana.com"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 25_000
        private const val FINALIZATION_TIMEOUT_MS = 120_000L
        private const val POLL_INTERVAL_MS = 1_000L
    }
}
