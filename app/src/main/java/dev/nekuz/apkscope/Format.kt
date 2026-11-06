package dev.nekuz.apkscope

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    const val UNKNOWN = "unknown"

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val UNITS = arrayOf("KiB", "MiB", "GiB", "TiB")
    private val ANDROID_VERSIONS = mapOf(
        1 to "1.0", 2 to "1.1", 3 to "1.5", 4 to "1.6", 5 to "2.0", 6 to "2.0.1", 7 to "2.1", 8 to "2.2",
        9 to "2.3", 10 to "2.3.3", 11 to "3.0", 12 to "3.1", 13 to "3.2", 14 to "4.0", 15 to "4.0.3",
        16 to "4.1", 17 to "4.2", 18 to "4.3", 19 to "4.4", 20 to "4.4W", 21 to "5.0", 22 to "5.1",
        23 to "6", 24 to "7.0", 25 to "7.1", 26 to "8.0", 27 to "8.1", 28 to "9", 29 to "10", 30 to "11",
        31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16",
    )

    fun size(bytes: Long?): String {
        if (bytes == null || bytes < 0) return UNKNOWN
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < UNITS.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit])
    }

    fun sizeExact(bytes: Long?): String {
        if (bytes == null || bytes < 0) return UNKNOWN
        if (bytes < 1024) return "$bytes B"
        return "${size(bytes)} ($bytes bytes)"
    }

    fun dateTime(millis: Long?, zone: ZoneId): String = format(millis, zone, DATE_TIME)

    fun date(millis: Long?, zone: ZoneId): String = format(millis, zone, DATE)

    private fun format(millis: Long?, zone: ZoneId, formatter: DateTimeFormatter): String {
        if (millis == null || millis <= 0) return UNKNOWN
        return try {
            formatter.format(Instant.ofEpochMilli(millis).atZone(zone))
        } catch (e: RuntimeException) {
            UNKNOWN
        }
    }

    fun sdk(level: Int): String {
        if (level <= 0) return UNKNOWN
        val version = ANDROID_VERSIONS[level] ?: return level.toString()
        return "$level (Android $version)"
    }

    fun yesNo(value: Boolean) = if (value) "yes" else "no"
}
