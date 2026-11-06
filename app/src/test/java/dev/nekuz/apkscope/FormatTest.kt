package dev.nekuz.apkscope

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class FormatTest {
    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun formatsSizes() {
        assertEquals(Format.UNKNOWN, Format.size(null))
        assertEquals(Format.UNKNOWN, Format.size(-1))
        assertEquals("0 B", Format.size(0))
        assertEquals("1023 B", Format.size(1023))
        assertEquals("1.0 KiB", Format.size(1024))
        assertEquals("1.5 KiB", Format.size(1536))
        assertEquals("10.0 MiB", Format.size(10L * 1024 * 1024))
        assertEquals("2.0 GiB", Format.size(2L * 1024 * 1024 * 1024))
        assertEquals("5120.0 TiB", Format.size(5120L * 1024 * 1024 * 1024 * 1024))
    }

    @Test
    fun formatsExactSizes() {
        assertEquals("512 B", Format.sizeExact(512))
        assertEquals("1.5 KiB (1536 bytes)", Format.sizeExact(1536))
        assertEquals(Format.UNKNOWN, Format.sizeExact(null))
    }

    @Test
    fun formatsDatesInTheGivenZone() {
        assertEquals("2024-01-02 03:04", Format.dateTime(1704164640000, utc))
        assertEquals("2024-01-02 04:04", Format.dateTime(1704164640000, ZoneOffset.ofHours(1)))
        assertEquals("2024-01-02", Format.date(1704164640000, utc))
    }

    @Test
    fun treatsMissingDatesAsUnknown() {
        assertEquals(Format.UNKNOWN, Format.dateTime(null, utc))
        assertEquals(Format.UNKNOWN, Format.dateTime(0, utc))
        assertEquals(Format.UNKNOWN, Format.date(-5, utc))
    }

    @Test
    fun formatsSdkLevels() {
        assertEquals("29 (Android 10)", Format.sdk(29))
        assertEquals("35 (Android 15)", Format.sdk(35))
        assertEquals("32 (Android 12L)", Format.sdk(32))
        assertEquals("99", Format.sdk(99))
        assertEquals(Format.UNKNOWN, Format.sdk(0))
    }

    @Test
    fun formatsBooleans() {
        assertEquals("yes", Format.yesNo(true))
        assertEquals("no", Format.yesNo(false))
    }
}
