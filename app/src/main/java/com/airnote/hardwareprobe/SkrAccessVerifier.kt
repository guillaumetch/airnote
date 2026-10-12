package com.airnote.hardwareprobe

import com.airnote.protocol.Base58
import java.math.BigDecimal
import java.math.BigInteger

class SkrAccessVerifier(
    private val rpc: AirnoteSolanaRpc = AirnoteSolanaRpc(AirnoteSolanaRpc.DEFAULT_MAINNET_RPC)
) {
    data class Result(
        val walletBase58: String,
        val rawBalance: BigInteger,
        val decimals: Int
    ) {
        val isHolder: Boolean get() = rawBalance > BigInteger.ZERO

        val displayBalance: String
            get() = if (decimals <= 0) {
                rawBalance.toString()
            } else {
                BigDecimal(rawBalance, decimals).stripTrailingZeros().toPlainString()
            }
    }

    suspend fun check(walletPublicKey: ByteArray): Result {
        require(walletPublicKey.size == 32) { "Solana wallet public key must be 32 bytes" }
        val wallet = Base58.encode(walletPublicKey)
        val balance = rpc.tokenBalanceByOwnerAndMint(wallet, SKR_MINT)
        return Result(
            walletBase58 = wallet,
            rawBalance = balance.amountBaseUnits,
            decimals = balance.decimals
        )
    }

    companion object {
        const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
    }
}
