package com.folium.reader.core.ink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.TreeMap
import java.util.zip.CRC32

private const val STROKE_LOG_MAGIC: Int = 0x464F_4C4C // "FOLL"
private const val STROKE_LOG_VERSION: Int = 1
private const val STROKE_LOG_HEADER_BYTES: Int = 5 // magic (4) + version (1)
private const val STROKE_LOG_COMPACT_SUFFIX = ".tmp"

private const val LENGTH_FIELD_BYTES: Int = 4
private const val CRC_FIELD_BYTES: Int = 4

/** A record's kind and payload must be at least this many bytes; anything shorter is an impossible declared length. */
private const val MIN_RECORD_BYTES: Int = 1

private const val KIND_ADD_STROKE: Byte = 1
private const val KIND_REMOVE_STROKES: Byte = 2

/** How aggressively [SheetStrokeLog] forces a just-appended record to disk. */
enum class SheetStrokeLogDurability {
    /**
     * `FileChannel.force(false)` after every appended record. The safest choice, and the default:
     * a crash right after any [SheetStrokeLog.append] call still leaves that edit durable, at the
     * cost of one fsync per stroke edit.
     */
    EVERY_RECORD,

    /**
     * No implicit fsync on append. The caller must call [SheetStrokeLog.flush] at its own cadence —
     * for instance once per drawn stroke rather than once per sample batch — trading a wider window
     * in which a crash can lose the most recent edits for materially higher append throughput during
     * a fast drawing session.
     */
    ON_CLOSE_AND_FLUSH
}

/** What replaying [SheetStrokeLog]'s records on open found. */
data class SheetStrokeLogReplayReport(
    /** Bytes at the end of the file that did not form a complete, valid record; `0` when the file ended cleanly. */
    val tornTailBytes: Long,
    /** How many ADD_STROKE records named a stroke id that was already live; tolerated as a no-op. */
    val idempotentAddCount: Int,
    /** How many REMOVE_STROKES records named no id that was actually live; tolerated as a no-op. */
    val idempotentRemoveCount: Int,
    /** How many complete, valid records were read, including the idempotent ones counted above. */
    val recordCount: Int,
    /**
     * The length of a file that already existed but was shorter than the header, and so was given a
     * fresh one; `null` when the file was new or its header was intact. No stroke can precede a
     * complete header, so nothing is lost by this, but a sheet that was not just created and reports
     * a value here did lose its log to something outside this store, and its owner should be told.
     */
    val replacedIncompleteHeaderBytes: Long? = null
)

/** A [SheetStrokeLog] record failed to parse. */
sealed class SheetStrokeLogException(message: String) : Exception(message) {
    /** [file]'s first bytes are not this format's magic and version; the file is foreign or unreadable, and is left untouched. */
    class InvalidHeader(val file: File, reason: String) : SheetStrokeLogException("invalid stroke log header in $file: $reason")

    /**
     * A record at [offsetBytes] into [file] failed its checksum, or declared an impossible length,
     * while more file content followed it — this is not a truncated tail, since a truncation can only
     * ever land at the true end of the file. The file is left byte-for-byte untouched.
     */
    class Corrupt(val file: File, val offsetBytes: Long, reason: String) :
        SheetStrokeLogException("corrupt stroke log record in $file at offset $offsetBytes: $reason")
}

private enum class RecordOutcome { APPLIED, ADD_IDEMPOTENT, REMOVE_IDEMPOTENT }

/**
 * An append-only log of [SheetEdit]s for one [Sheet], replayed on [open] into the live [InkStroke]
 * set it describes.
 *
 * ## File layout
 * A 5-byte header (magic, then version), followed by records of the shape
 * `[recordLength: Int32][kind: Byte][payload][crc32: Int32]`, where `recordLength` covers `kind` and
 * `payload` but neither the length field itself nor the trailing checksum. `kind` is either
 * `ADD_STROKE`, whose payload fully describes one [InkStroke], or `REMOVE_STROKES`, whose payload is
 * a count followed by that many stroke ids. Undoing an add is a `REMOVE_STROKES` record; undoing a
 * removal is a fresh `ADD_STROKE` record carrying the stroke's original [InkStroke.sequence], so
 * [strokes] always reflects true draw order regardless of how many times a stroke was undone and
 * redone.
 *
 * ## Crash safety
 * [append] writes a whole record with one channel write and then, under [SheetStrokeLogDurability.EVERY_RECORD],
 * forces it to disk before returning; under [SheetStrokeLogDurability.ON_CLOSE_AND_FLUSH] the caller
 * is responsible for calling [flush]. Either way, a crash can only ever leave a torn *final* record —
 * appends only ever grow the file — and [open] treats that as an interrupted append rather than
 * corruption: it reports [SheetStrokeLogReplayReport.tornTailBytes] and the next [append] truncates
 * the torn bytes away before writing. A checksum failure or an impossible declared length anywhere
 * else in the file — meaning more file content follows the bad record — can only be genuine
 * corruption, and is reported as [SheetStrokeLogException.Corrupt] without ever truncating or
 * rewriting anything.
 *
 * ## Threading
 * Blocking, synchronous I/O with no internal locking: the caller owns whatever single thread drives
 * a given open sheet, the same contract [SheetEditHistory] documents for the edits this log records.
 */
class SheetStrokeLog private constructor(
    private val file: File,
    initialChannel: FileChannel,
    private val durability: SheetStrokeLogDurability
) : Closeable {

    private var channel: FileChannel = initialChannel

    private val liveStrokesBySequence = TreeMap<Long, InkStroke>()
    private val liveSequenceById = HashMap<String, Long>()
    private val liveRecordSpanById = HashMap<String, Long>()

    private var totalRecordCount: Int = 0
    private var highestSequenceSeen: Long = -1L
    private var lastGoodOffset: Long = STROKE_LOG_HEADER_BYTES.toLong()
    private var pendingTruncationOffset: Long? = null

    lateinit var replayReport: SheetStrokeLogReplayReport
        private set

    /** The highest [InkStroke.sequence] ever recorded, live or since removed; `-1` when the log is empty. */
    val maxSequenceSeen: Long get() = highestSequenceSeen

    /** The log file's current size on disk, including any dead records not yet reclaimed by [compact]. */
    val totalBytes: Long get() = channel.size()

    /** The bytes occupied, on disk, by records for strokes that are still live. */
    val liveBytes: Long get() = liveRecordSpanById.values.sum()

    /** How many recorded records are no longer contributing a live stroke. */
    val deadRecordCount: Int get() = totalRecordCount - liveRecordSpanById.size

    /** Every live stroke, ordered by [InkStroke.sequence]. */
    fun liveStrokes(): List<InkStroke> = liveStrokesBySequence.values.toList()

    /**
     * Appends [edit] as one record per added stroke, or one record listing every removed stroke's
     * id, then updates the live set in memory to match. An empty [SheetEdit.RemoveStrokes] is a
     * no-op: nothing is written for an edit with nothing to record.
     */
    fun append(edit: SheetEdit) {
        when (edit) {
            is SheetEdit.AddStrokes -> for (stroke in edit.strokes) {
                val recordSpan = writeRecord(encodeAddPayload(stroke))
                applyAdd(stroke, recordSpan)
                totalRecordCount++
            }

            is SheetEdit.RemoveStrokes -> if (edit.strokes.isNotEmpty()) {
                writeRecord(encodeRemovePayload(edit.strokes))
                for (stroke in edit.strokes) applyRemove(stroke.id)
                totalRecordCount++
            }
        }
    }

    /** Forces every appended record to disk. Implicit after each [append] under [SheetStrokeLogDurability.EVERY_RECORD]. */
    fun flush() = channel.force(false)

    /**
     * Rewrites the log to contain only the currently live strokes, in their existing sequence order,
     * discarding every dead record. The rewrite lands in a `.tmp` sibling first, is fsynced, and is
     * then renamed over the real file, so a crash mid-compaction leaves the original, still-valid log
     * in place. Never called implicitly by [append]; a caller decides when to compact, typically by
     * consulting [shouldCompact] with [liveBytes], [totalBytes] and [deadRecordCount].
     */
    fun compact() {
        val liveInOrder = liveStrokesBySequence.values.toList()
        val tempFile = File(file.parentFile, file.name + STROKE_LOG_COMPACT_SUFFIX)
        val newSpanById = HashMap<String, Long>()

        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.setLength(0)
            raf.write(frameHeader())
            for (stroke in liveInOrder) {
                val framed = frameRecord(encodeAddPayload(stroke))
                raf.write(framed)
                newSpanById[stroke.id.value] = framed.size.toLong()
            }
            raf.fd.sync()
        }

        channel.close()
        Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(file.parentFile)

        val raf = RandomAccessFile(file, "rw")
        channel = raf.channel
        channel.position(channel.size())
        pendingTruncationOffset = null

        liveRecordSpanById.clear()
        liveRecordSpanById.putAll(newSpanById)
        totalRecordCount = liveInOrder.size
    }

    override fun close() {
        if (durability == SheetStrokeLogDurability.ON_CLOSE_AND_FLUSH) channel.force(false)
        channel.close()
    }

    private fun writeRecord(kindAndPayload: ByteArray): Long {
        pendingTruncationOffset?.let { offset ->
            channel.truncate(offset)
            pendingTruncationOffset = null
        }

        val framed = frameRecord(kindAndPayload)
        channel.position(channel.size())
        channel.write(ByteBuffer.wrap(framed))
        if (durability == SheetStrokeLogDurability.EVERY_RECORD) channel.force(false)
        return framed.size.toLong()
    }

    private fun applyAdd(stroke: InkStroke, recordSpanBytes: Long): Boolean {
        if (stroke.sequence > highestSequenceSeen) highestSequenceSeen = stroke.sequence

        val id = stroke.id.value
        if (liveSequenceById.containsKey(id)) return false

        liveSequenceById[id] = stroke.sequence
        liveRecordSpanById[id] = recordSpanBytes
        liveStrokesBySequence[stroke.sequence] = stroke
        return true
    }

    private fun applyRemove(id: StrokeId): Boolean {
        val sequence = liveSequenceById.remove(id.value) ?: return false
        liveRecordSpanById.remove(id.value)
        liveStrokesBySequence.remove(sequence)
        return true
    }

    /**
     * Replays every complete record from just past the header to the end of [raf], applying each one
     * to this log's live state. Stops at the first incomplete or invalid record: an incomplete one
     * that reaches all the way to the end of the file is a torn tail; an invalid one followed by more
     * file content is corruption and is thrown instead of silently swallowed.
     */
    private fun replay(raf: RandomAccessFile): SheetStrokeLogReplayReport {
        val fileLength = raf.length()
        var position = STROKE_LOG_HEADER_BYTES.toLong()
        var tornTailBytes = 0L
        var idempotentAdds = 0
        var idempotentRemoves = 0

        while (position < fileLength) {
            val remaining = fileLength - position

            if (remaining < LENGTH_FIELD_BYTES) {
                tornTailBytes = remaining
                break
            }

            raf.seek(position)
            val recordLength = raf.readInt()
            val remainingAfterLength = remaining - LENGTH_FIELD_BYTES

            if (recordLength < MIN_RECORD_BYTES) {
                if (remainingAfterLength == 0L) {
                    tornTailBytes = remaining
                    break
                }
                throw SheetStrokeLogException.Corrupt(file, position, "impossible record length $recordLength")
            }

            val neededAfterLength = recordLength.toLong() + CRC_FIELD_BYTES
            if (remainingAfterLength < neededAfterLength) {
                tornTailBytes = remaining
                break
            }

            val body = ByteArray(recordLength)
            raf.readFully(body)
            val storedCrc = raf.readInt()

            if (crc32Of(body) != storedCrc) {
                val isLastRecord = remainingAfterLength == neededAfterLength
                if (isLastRecord) {
                    tornTailBytes = remaining
                    break
                }
                throw SheetStrokeLogException.Corrupt(file, position, "checksum mismatch")
            }

            val recordSpan = LENGTH_FIELD_BYTES.toLong() + neededAfterLength
            val outcome = try {
                decodeAndApply(body, recordSpan)
            } catch (e: Exception) {
                throw SheetStrokeLogException.Corrupt(file, position, "malformed record body: ${e.message}")
            }

            when (outcome) {
                RecordOutcome.ADD_IDEMPOTENT -> idempotentAdds++
                RecordOutcome.REMOVE_IDEMPOTENT -> idempotentRemoves++
                RecordOutcome.APPLIED -> Unit
            }

            totalRecordCount++
            position += recordSpan
        }

        lastGoodOffset = position
        return SheetStrokeLogReplayReport(tornTailBytes, idempotentAdds, idempotentRemoves, totalRecordCount)
    }

    private fun decodeAndApply(body: ByteArray, recordSpan: Long): RecordOutcome {
        val input = DataInputStream(ByteArrayInputStream(body))

        return when (val kind = input.readByte()) {
            KIND_ADD_STROKE -> {
                val stroke = decodeAddPayload(input)
                if (applyAdd(stroke, recordSpan)) RecordOutcome.APPLIED else RecordOutcome.ADD_IDEMPOTENT
            }

            KIND_REMOVE_STROKES -> {
                val ids = decodeRemovePayload(input)
                val anyApplied = ids.map { applyRemove(it) }.any { it }
                if (anyApplied) RecordOutcome.APPLIED else RecordOutcome.REMOVE_IDEMPOTENT
            }

            else -> throw IOException("unknown record kind $kind")
        }
    }

    private fun encodeAddPayload(stroke: InkStroke): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(KIND_ADD_STROKE.toInt())
            out.writeUTF(stroke.id.value)
            out.writeByte(stroke.tool.ordinal)
            out.writeByte(stroke.tip.ordinal)
            out.writeInt(stroke.colorArgb)
            out.writeFloat(stroke.widthSheetUnits)
            out.writeByte(stroke.inputKind.ordinal)
            out.writeLong(stroke.sequence)
            out.writeFloat(stroke.bounds.left)
            out.writeFloat(stroke.bounds.top)
            out.writeFloat(stroke.bounds.right)
            out.writeFloat(stroke.bounds.bottom)

            val sampleBytes = InkSampleCodec.encode(stroke.samples)
            out.writeInt(sampleBytes.size)
            out.write(sampleBytes)
        }
        return buffer.toByteArray()
    }

    /** [InkStroke.bounds] is stored but not trusted here: it is fully determined by the width and the decoded samples, which [InkStroke]'s own factory recomputes it from. */
    private fun decodeAddPayload(input: DataInputStream): InkStroke {
        val id = StrokeId(input.readUTF())
        val tool = InkTool.entries[input.readByte().toInt() and 0xFF]
        val tip = InkTip.entries[input.readByte().toInt() and 0xFF]
        val colorArgb = input.readInt()
        val widthSheetUnits = input.readFloat()
        val inputKind = InkInputKind.entries[input.readByte().toInt() and 0xFF]
        val sequence = input.readLong()

        repeat(4) { input.readFloat() }

        val sampleByteCount = input.readInt()
        if (sampleByteCount < 0) throw IOException("negative sample byte count $sampleByteCount")
        val sampleBytes = ByteArray(sampleByteCount)
        input.readFully(sampleBytes)

        return InkStroke(id, tool, tip, colorArgb, widthSheetUnits, inputKind, InkSampleCodec.decode(sampleBytes), sequence)
    }

    private fun encodeRemovePayload(strokes: List<InkStroke>): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(KIND_REMOVE_STROKES.toInt())
            out.writeInt(strokes.size)
            for (stroke in strokes) out.writeUTF(stroke.id.value)
        }
        return buffer.toByteArray()
    }

    private fun decodeRemovePayload(input: DataInputStream): List<StrokeId> {
        val count = input.readInt()
        if (count < 0) throw IOException("negative stroke count $count")
        return List(count) { StrokeId(input.readUTF()) }
    }

    companion object {
        private const val COMPACTION_MIN_TOTAL_BYTES = 256L * 1024
        private const val COMPACTION_DEAD_RATIO_THRESHOLD = 0.5
        private const val COMPACTION_MIN_DEAD_RECORDS = 500

        /**
         * Whether a log with these stats is worth [compact]ing: it must have grown past a floor worth
         * rewriting at all ([COMPACTION_MIN_TOTAL_BYTES]), and then either more than half of it is
         * dead weight, or replay has accumulated enough individually small dead records on their own
         * ([COMPACTION_MIN_DEAD_RECORDS]) to slow down a future reopen regardless of their total size.
         */
        fun shouldCompact(liveBytes: Long, totalBytes: Long, deadRecordCount: Int): Boolean {
            require(liveBytes in 0..totalBytes) { "liveBytes must be within 0..totalBytes, was $liveBytes/$totalBytes" }
            require(deadRecordCount >= 0) { "deadRecordCount must be non-negative, was $deadRecordCount" }

            if (totalBytes < COMPACTION_MIN_TOTAL_BYTES) return false

            val deadBytes = totalBytes - liveBytes
            val deadRatio = deadBytes.toDouble() / totalBytes.toDouble()
            return deadRatio > COMPACTION_DEAD_RATIO_THRESHOLD || deadRecordCount >= COMPACTION_MIN_DEAD_RECORDS
        }

        /**
         * Opens [file], creating it with a fresh header when absent, and replays it into a new
         * [SheetStrokeLog]. A file shorter than the header is treated the same as a missing file
         * rather than as corruption: nothing can have been appended before a header finished writing,
         * so there is no user data such a file could be losing.
         */
        fun open(file: File, durability: SheetStrokeLogDurability = SheetStrokeLogDurability.EVERY_RECORD): SheetStrokeLog {
            file.parentFile?.mkdirs()
            val existedBeforeOpen = file.exists()
            val raf = RandomAccessFile(file, "rw")
            try {
                val lengthBeforeOpen = raf.length()
                val replacedIncompleteHeaderBytes =
                    lengthBeforeOpen.takeIf { existedBeforeOpen && it < STROKE_LOG_HEADER_BYTES }

                if (lengthBeforeOpen < STROKE_LOG_HEADER_BYTES) {
                    raf.setLength(0)
                    raf.seek(0)
                    raf.write(frameHeader())
                    raf.fd.sync()
                } else {
                    validateHeader(file, raf)
                }

                val log = SheetStrokeLog(file, raf.channel, durability)
                val report = log.replay(raf).copy(replacedIncompleteHeaderBytes = replacedIncompleteHeaderBytes)
                log.replayReport = report
                log.pendingTruncationOffset = if (report.tornTailBytes > 0) log.lastGoodOffset else null
                log.channel.position(if (report.tornTailBytes > 0) log.lastGoodOffset else log.channel.size())
                return log
            } catch (e: Exception) {
                raf.close()
                throw e
            }
        }

        private fun validateHeader(file: File, raf: RandomAccessFile) {
            raf.seek(0)
            val header = ByteArray(STROKE_LOG_HEADER_BYTES)
            raf.readFully(header)

            val input = DataInputStream(ByteArrayInputStream(header))
            if (input.readInt() != STROKE_LOG_MAGIC) throw SheetStrokeLogException.InvalidHeader(file, "bad magic")

            val version = input.readByte().toInt() and 0xFF
            if (version != STROKE_LOG_VERSION) throw SheetStrokeLogException.InvalidHeader(file, "unsupported version $version")
        }

        private fun frameHeader(): ByteArray {
            val buffer = ByteArrayOutputStream(STROKE_LOG_HEADER_BYTES)
            DataOutputStream(buffer).use { out ->
                out.writeInt(STROKE_LOG_MAGIC)
                out.writeByte(STROKE_LOG_VERSION)
            }
            return buffer.toByteArray()
        }

        private fun frameRecord(kindAndPayload: ByteArray): ByteArray {
            val buffer = ByteArrayOutputStream(LENGTH_FIELD_BYTES + kindAndPayload.size + CRC_FIELD_BYTES)
            DataOutputStream(buffer).use { out ->
                out.writeInt(kindAndPayload.size)
                out.write(kindAndPayload)
                out.writeInt(crc32Of(kindAndPayload))
            }
            return buffer.toByteArray()
        }

        private fun crc32Of(bytes: ByteArray): Int {
            val crc = CRC32()
            crc.update(bytes)
            return crc.value.toInt()
        }
    }
}
