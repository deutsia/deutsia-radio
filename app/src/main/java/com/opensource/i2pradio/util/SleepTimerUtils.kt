package com.opensource.i2pradio.util

/**
 * Utility functions for sleep timer functionality.
 */
object SleepTimerUtils {

    /** Minimum sleep timer duration in minutes */
    const val MIN_DURATION_MINUTES = 1

    /** Maximum sleep timer duration in minutes (12 hours) */
    const val MAX_DURATION_MINUTES = 720

    /**
     * Formats a duration in minutes to a human-readable string.
     *
     * Examples:
     * - 0 -> "Off"
     * - 5 -> "5 min"
     * - 60 -> "1h"
     * - 90 -> "1h 30m"
     * - 720 -> "12h"
     *
     * @param minutes The duration in minutes (0 = off, 1-720 = valid range)
     * @return A formatted string representation of the duration
     */
    fun formatDuration(minutes: Int): String {
        if (minutes <= 0) return "Off"

        val hours = minutes / 60
        val mins = minutes % 60

        return when {
            hours == 0 -> "$mins min"
            mins == 0 -> "${hours}h"
            else -> "${hours}h ${mins}m"
        }
    }

    /**
     * Validates that a duration is within the allowed range.
     *
     * @param minutes The duration in minutes to validate
     * @return true if the duration is valid (0 for off, or within MIN-MAX range)
     */
    fun isValidDuration(minutes: Int): Boolean {
        return minutes == 0 || (minutes in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES)
    }

    /**
     * Coerces a duration value to be within the valid range.
     *
     * @param minutes The duration in minutes
     * @return The duration coerced to be within MIN_DURATION_MINUTES..MAX_DURATION_MINUTES
     */
    fun coerceDuration(minutes: Int): Int {
        return minutes.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
    }

    /**
     * Formats remaining millis as a countdown clock. Rounds up so a timer
     * with 30s left still reads 00:01 rather than 00:00.
     *
     * Examples:
     * - 0 -> "00:00"
     * - 1500 -> "00:02"
     * - 65000 -> "01:05"
     * - 3_700_000 -> "1:01:40"
     */
    fun formatCountdown(remainingMs: Long): String {
        val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
