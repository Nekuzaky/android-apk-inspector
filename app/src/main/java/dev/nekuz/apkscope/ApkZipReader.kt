package dev.nekuz.apkscope

import java.io.DataInputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.Channels

enum class ZipReadStatus { COMPLETE, MALFORMED, UNREADABLE, ENTRY_LIMIT, TIME_LIMIT }

data class NativeLib(val abi: String, val name: String)

data class NativeScan(val libraries: List<NativeLib>, val entries: Long, val status: ZipReadStatus) {
    val abis: List<String> get() = libraries.map { it.abi }.distinct().sorted()

    fun merge(other: NativeScan) = NativeScan(
        (libraries + other.libraries).distinct(),
        entries + other.entries,
        if (other.status.ordinal > status.ordinal) other.status else status,
    )

    companion object {
        val EMPTY = NativeScan(emptyList(), 0, ZipReadStatus.COMPLETE)
    }
}

class ZipBudget(
    val maxEntries: Long = 200_000,
    val maxLibraries: Int = 4096,
    maxNanos: Long = 8_000_000_000,
    private val clock: () -> Long = System::nanoTime,
) {
    private val deadline = clock() + maxNanos
    private var entries = 0L
    private var libraries = 0

    fun timeUp() = clock() - deadline >= 0

    fun takeEntry(): Boolean {
        if (entries >= maxEntries) return false
        entries++
        return true
    }

    fun takeLibrary(): Boolean {
        if (libraries >= maxLibraries) return false
        libraries++
        return true
    }
}

object NativeLibNames {
    private const val MAX_FILE_NAME = 255
    private val ABI = Regex("[A-Za-z0-9_-]{1,32}")

    fun parse(entryName: String): NativeLib? {
        val parts = entryName.split('/')
        if (parts.size != 3 || parts[0] != "lib") return null
        val abi = parts[1]
        val file = parts[2]
        if (!ABI.matches(abi)) return null
        if (!file.endsWith(".so") || file.length <= 3 || '\\' in file) return null
        if (file == ".." || file == ".") return null
        val name = ForeignText.clean(file, MAX_FILE_NAME)
        if (name.isEmpty()) return null
        return NativeLib(abi, name)
    }
}

object ApkZipReader {
    private const val EOCD_SIZE = 22
    private const val MAX_COMMENT = 65_535
    private const val CENTRAL_HEADER_SIZE = 46
    private const val LOCATOR_SIZE = 20
    private const val ZIP64_EOCD_SIZE = 56
    private const val MAX_ENTRY_NAME = 512
    private const val BUFFER_SIZE = 65_536
    private const val SIG_EOCD = 0x06054B50L
    private const val SIG_CENTRAL = 0x02014B50L
    private const val SIG_LOCATOR = 0x07064B50L
    private const val SIG_ZIP64_EOCD = 0x06064B50L
    private const val U16_MAX = 0xFFFFL
    private const val U32_MAX = 0xFFFFFFFFL

    private class Directory(val offset: Long, val size: Long, val count: Long)

    fun readAll(files: List<File>, budget: ZipBudget): NativeScan =
        files.fold(NativeScan.EMPTY) { scan, file -> scan.merge(read(file, budget)) }

    fun read(file: File, budget: ZipBudget): NativeScan {
        val libraries = LinkedHashSet<NativeLib>()
        var entries = 0L
        val status = try {
            if (!file.isFile) {
                ZipReadStatus.UNREADABLE
            } else {
                RandomAccessFile(file, "r").use { handle ->
                    val directory = locate(handle)
                    if (directory == null) {
                        ZipReadStatus.MALFORMED
                    } else {
                        walk(handle, directory, budget, libraries) { entries++ }
                    }
                }
            }
        } catch (e: Throwable) {
            ZipReadStatus.UNREADABLE
        }
        return NativeScan(libraries.toList(), entries, status)
    }

    private fun locate(handle: RandomAccessFile): Directory? {
        val length = handle.length()
        if (length < EOCD_SIZE) return null
        val tailSize = minOf(length, (EOCD_SIZE + MAX_COMMENT).toLong()).toInt()
        val tailStart = length - tailSize
        val tail = ByteArray(tailSize)
        handle.seek(tailStart)
        handle.readFully(tail)
        var position = tailSize - EOCD_SIZE
        while (position >= 0) {
            if (u32(tail, position) == SIG_EOCD && u16(tail, position + 20) == tailSize - position - EOCD_SIZE) {
                return directoryFrom(handle, tail, position, tailStart + position, length)
            }
            position--
        }
        return null
    }

    private fun directoryFrom(
        handle: RandomAccessFile,
        tail: ByteArray,
        position: Int,
        eocdOffset: Long,
        length: Long,
    ): Directory? {
        val count = u16(tail, position + 10).toLong()
        val size = u32(tail, position + 12)
        val offset = u32(tail, position + 16)
        val needsZip64 = count == U16_MAX || size == U32_MAX || offset == U32_MAX
        val directory = (if (needsZip64) readZip64(handle, eocdOffset, length) else null)
            ?: Directory(offset, size, count).takeUnless { size == U32_MAX || offset == U32_MAX }
            ?: return null
        val valid = directory.offset >= 0 && directory.size >= 0 && directory.count >= 0 &&
            directory.size <= length && directory.offset <= length - directory.size
        return directory.takeIf { valid }
    }

    private fun readZip64(handle: RandomAccessFile, eocdOffset: Long, length: Long): Directory? {
        if (eocdOffset < LOCATOR_SIZE) return null
        val locator = ByteArray(LOCATOR_SIZE)
        handle.seek(eocdOffset - LOCATOR_SIZE)
        handle.readFully(locator)
        if (u32(locator, 0) != SIG_LOCATOR) return null
        val recordOffset = u64(locator, 8)
        if (recordOffset < 0 || recordOffset > length - ZIP64_EOCD_SIZE) return null
        val record = ByteArray(ZIP64_EOCD_SIZE)
        handle.seek(recordOffset)
        handle.readFully(record)
        if (u32(record, 0) != SIG_ZIP64_EOCD) return null
        return Directory(u64(record, 48), u64(record, 40), u64(record, 32))
    }

    private fun walk(
        handle: RandomAccessFile,
        directory: Directory,
        budget: ZipBudget,
        libraries: MutableSet<NativeLib>,
        onEntry: () -> Unit,
    ): ZipReadStatus {
        handle.seek(directory.offset)
        val input = DataInputStream(BufferedInputStream(Channels.newInputStream(handle.channel), BUFFER_SIZE))
        val header = ByteArray(CENTRAL_HEADER_SIZE)
        val nameBuffer = ByteArray(MAX_ENTRY_NAME)
        var consumed = 0L
        var seen = 0L
        try {
            while (consumed < directory.size) {
                if (budget.timeUp()) return ZipReadStatus.TIME_LIMIT
                if (!budget.takeEntry()) return ZipReadStatus.ENTRY_LIMIT
                if (directory.size - consumed < CENTRAL_HEADER_SIZE) return ZipReadStatus.MALFORMED
                input.readFully(header)
                if (u32(header, 0) != SIG_CENTRAL) return ZipReadStatus.MALFORMED
                val nameLength = u16(header, 28)
                val rest = u16(header, 30).toLong() + u16(header, 32)
                val total = CENTRAL_HEADER_SIZE + nameLength + rest
                if (total > directory.size - consumed) return ZipReadStatus.MALFORMED
                if (nameLength <= MAX_ENTRY_NAME) {
                    input.readFully(nameBuffer, 0, nameLength)
                    val library = NativeLibNames.parse(String(nameBuffer, 0, nameLength, Charsets.UTF_8))
                    if (library != null && library !in libraries) {
                        if (!budget.takeLibrary()) return ZipReadStatus.ENTRY_LIMIT
                        libraries.add(library)
                    }
                } else {
                    skipFully(input, nameLength.toLong())
                }
                skipFully(input, rest)
                consumed += total
                seen++
                onEntry()
            }
        } catch (e: java.io.IOException) {
            return ZipReadStatus.MALFORMED
        }
        return if (seen == directory.count) ZipReadStatus.COMPLETE else ZipReadStatus.MALFORMED
    }

    private fun skipFully(input: DataInputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skipBytes(minOf(remaining, Int.MAX_VALUE.toLong()).toInt())
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) throw EOFException()
                remaining--
            }
        }
    }

    private fun u16(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(data: ByteArray, at: Int): Long =
        u16(data, at).toLong() or (u16(data, at + 2).toLong() shl 16)

    private fun u64(data: ByteArray, at: Int): Long =
        u32(data, at) or (u32(data, at + 4) shl 32)
}
