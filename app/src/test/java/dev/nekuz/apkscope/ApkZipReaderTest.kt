package dev.nekuz.apkscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkZipReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun zip(names: List<String>, comment: String? = null): File {
        val file = folder.newFile()
        ZipOutputStream(file.outputStream()).use { output ->
            comment?.let { output.setComment(it) }
            names.forEach { name ->
                output.putNextEntry(ZipEntry(name))
                output.write(1)
                output.closeEntry()
            }
        }
        return file
    }

    private fun centralEntry(name: String): ByteArray {
        val encoded = name.toByteArray()
        val buffer = ByteBuffer.allocate(46 + encoded.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 0x02014B50)
        buffer.putShort(28, encoded.size.toShort())
        buffer.position(46)
        buffer.put(encoded)
        return buffer.array()
    }

    private fun eocd(count: Int, size: Long, offset: Long): ByteArray {
        val buffer = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 0x06054B50)
        buffer.putShort(10, count.toShort())
        buffer.putInt(12, size.toInt())
        buffer.putInt(16, offset.toInt())
        return buffer.array()
    }

    private fun bytesFile(bytes: ByteArray): File = folder.newFile().apply { writeBytes(bytes) }

    @Test
    fun findsLibrariesAndAbis() {
        val file = zip(
            listOf(
                "classes.dex",
                "lib/arm64-v8a/libfoo.so",
                "lib/arm64-v8a/libbar.so",
                "lib/x86_64/libfoo.so",
                "res/raw/lib.so",
                "assets/lib/arm64-v8a/libhidden.so",
                "lib/arm64-v8a/readme.txt",
            ),
        )
        val scan = ApkZipReader.read(file, ZipBudget())
        assertEquals(ZipReadStatus.COMPLETE, scan.status)
        assertEquals(7L, scan.entries)
        assertEquals(listOf("arm64-v8a", "x86_64"), scan.abis)
        assertEquals(
            setOf(
                NativeLib("arm64-v8a", "libfoo.so"),
                NativeLib("arm64-v8a", "libbar.so"),
                NativeLib("x86_64", "libfoo.so"),
            ),
            scan.libraries.toSet(),
        )
    }

    @Test
    fun readsZipWithComment() {
        val scan = ApkZipReader.read(zip(listOf("lib/x86/liba.so"), comment = "trailing comment"), ZipBudget())
        assertEquals(ZipReadStatus.COMPLETE, scan.status)
        assertEquals(listOf(NativeLib("x86", "liba.so")), scan.libraries)
    }

    @Test
    fun ignoresHostileEntryNames() {
        val names = listOf(
            "lib/../../../etc/evil.so",
            "lib/arm64-v8a/../evil.so",
            "/lib/arm64-v8a/evil.so",
            "lib/arm64-v8a/sub/evil.so",
            "lib/arm..64/evil.so",
            "lib/arm64-v8a/.so",
            "lib/arm64-v8a/evil\\.so",
        )
        val entries = names.map { centralEntry(it) }
        val directory = entries.fold(ByteArray(0)) { all, entry -> all + entry }
        val file = bytesFile(directory + eocd(names.size, directory.size.toLong(), 0))
        val scan = ApkZipReader.read(file, ZipBudget())
        assertEquals(ZipReadStatus.COMPLETE, scan.status)
        assertEquals(emptyList<NativeLib>(), scan.libraries)
    }

    @Test
    fun cleansLibraryNames() {
        assertEquals(
            NativeLib("arm64-v8a", "libevil.so"),
            NativeLibNames.parse("lib/arm64-v8a/lib‮evil.so"),
        )
    }

    @Test
    fun skipsOverlongEntryNames() {
        val long = centralEntry("lib/x86/" + "a".repeat(1000) + ".so")
        val good = centralEntry("lib/x86/libok.so")
        val directory = long + good
        val scan = ApkZipReader.read(bytesFile(directory + eocd(2, directory.size.toLong(), 0)), ZipBudget())
        assertEquals(ZipReadStatus.COMPLETE, scan.status)
        assertEquals(listOf(NativeLib("x86", "libok.so")), scan.libraries)
    }

    @Test
    fun stopsAtEntryLimit() {
        val file = zip((1..100).map { "lib/x86/lib$it.so" })
        val scan = ApkZipReader.read(file, ZipBudget(maxEntries = 10))
        assertEquals(ZipReadStatus.ENTRY_LIMIT, scan.status)
        assertEquals(10L, scan.entries)
        assertEquals(10, scan.libraries.size)
    }

    @Test
    fun entryBudgetIsSharedAcrossApks() {
        val first = zip((1..6).map { "lib/x86/a$it.so" })
        val second = zip((1..6).map { "lib/x86/b$it.so" })
        val third = zip((1..6).map { "lib/x86/c$it.so" })
        val scan = ApkZipReader.readAll(listOf(first, second, third), ZipBudget(maxEntries = 10))
        assertEquals(ZipReadStatus.ENTRY_LIMIT, scan.status)
        assertEquals(10L, scan.entries)
    }

    @Test
    fun stopsAtLibraryLimit() {
        val file = zip((1..20).map { "lib/x86/lib$it.so" })
        val scan = ApkZipReader.read(file, ZipBudget(maxLibraries = 5))
        assertEquals(ZipReadStatus.ENTRY_LIMIT, scan.status)
        assertEquals(5, scan.libraries.size)
    }

    @Test
    fun stopsAtTimeLimit() {
        val file = zip((1..50).map { "lib/x86/lib$it.so" })
        var now = 0L
        val budget = ZipBudget(maxNanos = 10, clock = { now++ })
        val scan = ApkZipReader.read(file, budget)
        assertEquals(ZipReadStatus.TIME_LIMIT, scan.status)
        assertTrue(scan.entries < 50)
    }

    @Test
    fun rejectsGarbageAndEmptyFiles() {
        assertEquals(ZipReadStatus.MALFORMED, ApkZipReader.read(bytesFile(ByteArray(4096) { 7 }), ZipBudget()).status)
        assertEquals(ZipReadStatus.MALFORMED, ApkZipReader.read(bytesFile(ByteArray(0)), ZipBudget()).status)
    }

    @Test
    fun missingFileAndDirectoryAreUnreadable() {
        assertEquals(ZipReadStatus.UNREADABLE, ApkZipReader.read(File(folder.root, "absent.apk"), ZipBudget()).status)
        assertEquals(ZipReadStatus.UNREADABLE, ApkZipReader.read(folder.newFolder(), ZipBudget()).status)
    }

    @Test
    fun rejectsDirectoryOutsideTheFile() {
        assertEquals(ZipReadStatus.MALFORMED, ApkZipReader.read(bytesFile(eocd(1, 100, 0)), ZipBudget()).status)
        assertEquals(ZipReadStatus.MALFORMED, ApkZipReader.read(bytesFile(eocd(1, 0x7FFFFFFF, 0x7FFFFFFF)), ZipBudget()).status)
    }

    @Test
    fun declaredCountCannotOutrunTheDirectory() {
        val directory = centralEntry("lib/x86/liba.so")
        val scan = ApkZipReader.read(bytesFile(directory + eocd(60_000, directory.size.toLong(), 0)), ZipBudget())
        assertEquals(ZipReadStatus.MALFORMED, scan.status)
        assertEquals(1L, scan.entries)
        assertEquals(listOf(NativeLib("x86", "liba.so")), scan.libraries)
    }

    @Test
    fun entryLengthCannotRunPastTheDirectory() {
        val entry = centralEntry("lib/x86/liba.so")
        ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN).putShort(30, 0x7FFF.toShort())
        val scan = ApkZipReader.read(bytesFile(entry + eocd(1, entry.size.toLong(), 0)), ZipBudget())
        assertEquals(ZipReadStatus.MALFORMED, scan.status)
        assertEquals(0L, scan.entries)
    }

    @Test
    fun badEntrySignatureStopsTheWalk() {
        val good = centralEntry("lib/x86/liba.so")
        val bad = ByteArray(60)
        val directory = good + bad
        val scan = ApkZipReader.read(bytesFile(directory + eocd(2, directory.size.toLong(), 0)), ZipBudget())
        assertEquals(ZipReadStatus.MALFORMED, scan.status)
        assertEquals(listOf(NativeLib("x86", "liba.so")), scan.libraries)
    }

    @Test
    fun readsZip64Directory() {
        val directory = centralEntry("lib/riscv64/libz.so")
        val record = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
        record.putInt(0, 0x06064B50)
        record.putLong(32, 1)
        record.putLong(40, directory.size.toLong())
        record.putLong(48, 0)
        val locator = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        locator.putInt(0, 0x07064B50)
        locator.putLong(8, directory.size.toLong())
        val bytes = ByteArrayOutputStream()
        bytes.write(directory)
        bytes.write(record.array())
        bytes.write(locator.array())
        bytes.write(eocd(0xFFFF, 0xFFFFFFFFL, 0xFFFFFFFFL))
        val scan = ApkZipReader.read(bytesFile(bytes.toByteArray()), ZipBudget())
        assertEquals(ZipReadStatus.COMPLETE, scan.status)
        assertEquals(listOf(NativeLib("riscv64", "libz.so")), scan.libraries)
    }

    @Test
    fun rejectsZip64WithNegativeValues() {
        val record = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
        record.putInt(0, 0x06064B50)
        record.putLong(32, 1)
        record.putLong(40, -1)
        record.putLong(48, -1)
        val locator = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        locator.putInt(0, 0x07064B50)
        locator.putLong(8, 0)
        val bytes = record.array() + locator.array() + eocd(0xFFFF, 0xFFFFFFFFL, 0xFFFFFFFFL)
        assertEquals(ZipReadStatus.MALFORMED, ApkZipReader.read(bytesFile(bytes), ZipBudget()).status)
    }

    @Test
    fun neverWritesToDisk() {
        val file = zip(listOf("lib/x86/liba.so", "classes.dex"))
        val modified = file.lastModified()
        val before = folder.root.walkTopDown().map { it.path }.toSet()
        ApkZipReader.read(file, ZipBudget())
        assertEquals(before, folder.root.walkTopDown().map { it.path }.toSet())
        assertEquals(modified, file.lastModified())
    }

    @Test
    fun mergeKeepsTheWorstStatusAndUniqueLibraries() {
        val a = NativeScan(listOf(NativeLib("x86", "a.so")), 2, ZipReadStatus.COMPLETE)
        val b = NativeScan(listOf(NativeLib("x86", "a.so"), NativeLib("x86", "b.so")), 3, ZipReadStatus.TIME_LIMIT)
        val merged = a.merge(b)
        assertEquals(5L, merged.entries)
        assertEquals(ZipReadStatus.TIME_LIMIT, merged.status)
        assertEquals(2, merged.libraries.size)
    }
}
