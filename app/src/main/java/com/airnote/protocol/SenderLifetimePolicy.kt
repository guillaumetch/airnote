package com.airnote.protocol

data class SenderLifetime(
    val seconds: Long,
    val label: String,
    val exposureLabel: String
)

object SenderLifetimePolicy {
    const val MIN_SECONDS = 5L * 60L
    const val MAX_SECONDS = 24L * 60L * 60L
    const val DEFAULT_SECONDS = 60L * 60L

    val presets: List<SenderLifetime> = listOf(
        lifetime(15L * 60L),
        lifetime(60L * 60L),
        lifetime(6L * 60L * 60L),
        lifetime(24L * 60L * 60L)
    )

    fun fromMinutes(minutes: Long): SenderLifetime {
        val seconds = Math.multiplyExact(minutes, 60L)
        require(seconds in MIN_SECONDS..MAX_SECONDS) {
            "Airnote lifetime must be between 5 minutes and 24 hours"
        }
        return lifetime(seconds)
    }

    fun lifetime(seconds: Long): SenderLifetime {
        require(seconds in MIN_SECONDS..MAX_SECONDS) {
            "Airnote lifetime must be between 5 minutes and 24 hours"
        }
        return SenderLifetime(
            seconds = seconds,
            label = formatDuration(seconds),
            exposureLabel = exposure(seconds)
        )
    }

    private fun exposure(seconds: Long): String = when {
        seconds <= 15L * 60L -> "LOW OFFLINE EXPOSURE"
        seconds <= 60L * 60L -> "STANDARD"
        seconds <= 6L * 60L * 60L -> "EXTENDED"
        else -> "MAXIMUM"
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60L
        return when {
            minutes < 60L -> "$minutes min"
            minutes % 60L == 0L -> {
                val hours = minutes / 60L
                if (hours == 1L) "1 hour" else "$hours hours"
            }
            else -> {
                val hours = minutes / 60L
                val remainingMinutes = minutes % 60L
                "${hours}h ${remainingMinutes}m"
            }
        }
    }
}
