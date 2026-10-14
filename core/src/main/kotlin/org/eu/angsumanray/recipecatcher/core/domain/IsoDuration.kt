package org.eu.angsumanray.recipecatcher.core.domain

import java.time.Duration
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.regex.Pattern

object IsoDuration {

    private val ISO_REGEX = Pattern.compile(
        "^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?$",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Parses an ISO 8601 duration string (e.g. "PT1H15M", "PT45M", "P1D") to total minutes.
     * Returns null if string is blank or cannot be parsed.
     */
    fun parseToMinutes(text: String?): Int? {
        val totalSeconds = parseToSeconds(text) ?: return null
        return (totalSeconds / 60L).toInt()
    }

    /**
     * Parses an ISO 8601 duration string to total seconds.
     * Returns null if string is blank or cannot be parsed.
     */
    fun parseToSeconds(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim().uppercase(Locale.ROOT)

        // Try standard java.time.Duration first
        try {
            val d = Duration.parse(trimmed)
            return d.seconds
        } catch (_: DateTimeParseException) {
            // Fall through to regex parser
        }

        // Try regex match
        val matcher = ISO_REGEX.matcher(trimmed)
        if (matcher.matches()) {
            val days = matcher.group(1)?.toLongOrNull() ?: 0L
            val hours = matcher.group(2)?.toLongOrNull() ?: 0L
            val minutes = matcher.group(3)?.toLongOrNull() ?: 0L
            val seconds = matcher.group(4)?.toDoubleOrNull()?.toLong() ?: 0L
            return (days * 86400L) + (hours * 3600L) + (minutes * 60L) + seconds
        }

        // Lenient fallback for strings like "1H15M" or "45M" (missing leading P or PT)
        val withPrefix = when {
            trimmed.startsWith("T") -> "P$trimmed"
            !trimmed.startsWith("P") -> if (trimmed.contains("H") || trimmed.contains("M") || trimmed.contains("S")) "PT$trimmed" else null
            else -> null
        }
        if (withPrefix != null) {
            return parseToSeconds(withPrefix)
        }

        return null
    }

    /**
     * Converts a duration in minutes to an ISO 8601 duration string (e.g. 75 -> "PT1H15M", 45 -> "PT45M").
     */
    fun toIsoDuration(minutes: Int): String {
        if (minutes <= 0) return "PT0M"
        val hours = minutes / 60
        val mins = minutes % 60
        val sb = StringBuilder("PT")
        if (hours > 0) sb.append("${hours}H")
        if (mins > 0 || hours == 0) sb.append("${mins}M")
        return sb.toString()
    }

    /**
     * Formats minutes into human-readable duration, e.g. "1 hr 15 min", "45 min", "2 hr".
     */
    fun formatDisplay(minutes: Int?): String {
        if (minutes == null || minutes <= 0) return ""
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours > 0 && mins > 0 -> "$hours hr $mins min"
            hours > 0 -> if (hours == 1) "1 hr" else "$hours hrs"
            else -> "$mins min"
        }
    }
}
