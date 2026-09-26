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

/** The only version every reader ever written could produce or read: strokes alone, no text boxes. */
private const val STROKE_LOG_VERSION_1: Int = 1

/** Adds [KIND_ADD_TEXT] to the format; a v1 file is upgraded to this in place the first time a text box is appended to it. */
private const val STROKE_LOG_VERSION_2: Int = 2

/** The version every newly created log is written as. */
private const val STROKE_LOG_VERSION_CURRENT: Int = STROKE_LOG_VERSION_2

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

/**
 * The live items of a [SheetStrokeLog] as [SheetStrokeLog.read] replayed them, without the log ever
 * being opened for writing.
 */
data class SheetStrokeLogSnapshot(
    /** Every live stroke and text box, ordered by their shared sequence. */
    val items: List<SheetItem>,
    val replayReport: SheetStrokeLogReplayReport,
    /** The highest sequence, stroke or text box, ever recorded, live or since removed; `-1` when none was. */
    val maxSequenceSeen: Long
) {
    /** Every live stroke, ordered by [InkStroke.sequence]. */
    val strokes: List<InkStroke> get() = items.filterIsInstance<SheetItem.Stroke>().map { it.stroke }

    /** Every live text box, ordered by [SheetTextBox.sequence]. */
    val textBoxes: List<SheetTextBox> get() = items.filterIsInstance<SheetItem.Text>().map { it.textBox }

    companion object {
        /** A log with no records at all: what a missing file, or one shorter than its header, reads as. */
        val EMPTY = SheetStrokeLogSnapshot(emptyList(), SheetStrokeLogReplayReport(0L, 0, 0, 0), -1L)
    }
}

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
 * `payload` but neither the length field itself nor the trailing checksum. `kind` is `ADD_STROKE`,
 * whose payload fully describes one [InkStroke]; `ADD_TEXT_ALIGNED` (see [SheetTextRecordCodec], which
 * also documents the two now read-only text kinds it must still decode), whose payload fully describes
 * one [SheetTextBox]; or `REMOVE_STROKES`, whose payload is a count followed by that many ids, each
 * naming either a stroke or a text box since both share [StrokeId]'s own id space. Undoing an add is a
 * `REMOVE_STROKES` record; undoing a removal is a fresh `ADD_STROKE` or `ADD_TEXT_ALIGNED` record
 * carrying the item's original sequence, so [strokes] and [textBoxes] always reflect true draw order
 * regardless of how many times an item was undone and redone.
 *
 * A file opens at version 1 (strokes only) or version 2 (`ADD_TEXT` understood); every newly created
 * log is written at version 2. A version 1 file is upgraded to version 2 in place, through the same
 * atomic rewrite [compact] already uses, the first time [append] is asked to add a [SheetTextBox] to
 * it — never before, since a version 1 file cannot yet contain one to lose by staying at version 1.
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
    private val durability: SheetStrokeLogDurability,
    initialVersion: Int
) : Closeable {

    private var channel: FileChannel = initialChannel

    private val liveStrokesBySequence = TreeMap<Long, InkStroke>()
    private val liveTextsBySequence = TreeMap<Long, SheetTextBox>()
    private val liveSequenceById = HashMap<String, Long>()
    private val liveRecordSpanById = HashMap<String, Long>()

    private var totalRecordCount: Int = 0
    private var highestSequenceSeen: Long = -1L
    private var lastGoodOffset: Long = STROKE_LOG_HEADER_BYTES.toLong()
    private var pendingTruncationOffset: Long? = null

    /** This log's own header version: [STROKE_LOG_VERSION_1] or [STROKE_LOG_VERSION_2]. Bumped in place by [compact] and by the implicit upgrade [append] performs before the first [SheetTextBox] it is asked to add. */
    var version: Int = initialVersion
        private set

    lateinit var replayReport: SheetStrokeLogReplayReport
        private set

    /** The highest sequence, stroke or text box, ever recorded, live or since removed; `-1` when the log is empty. */
    val maxSequenceSeen: Long get() = highestSequenceSeen

    /** The log file's current size on disk, including any dead records not yet reclaimed by [compact]. */
    val totalBytes: Long get() = channel.size()

    /** The bytes occupied, on disk, by records for items that are still live. */
    val liveBytes: Long get() = liveRecordSpanById.values.sum()

    /** How many recorded records are no longer contributing a live item. */
    val deadRecordCount: Int get() = totalRecordCount - liveRecordSpanById.size

    /** Every live stroke, ordered by [InkStroke.sequence]. */
    fun liveStrokes(): List<InkStroke> = liveStrokesBySequence.values.toList()

    /** Every live text box, ordered by [SheetTextBox.sequence]. */
    fun liveTexts(): List<SheetTextBox> = liveTextsBySequence.values.toList()

    /** Every live stroke and text box, ordered by their shared sequence. */
    fun liveItems(): List<SheetItem> = combinedLiveItemsInOrder()

    /**
     * Appends [edit] as one record per added stroke, or one record listing every removed stroke's
     * id, then updates the live set in memory to match. An empty [SheetEdit.RemoveStrokes] is a
     * no-op: nothing is written for an edit with nothing to record.
     *
     * [SheetEdit.ReplaceStrokes] writes its ADD_STROKE records before its REMOVE_STROKES record — the
     * reverse of the order a reader would expect from "replace" — as a crash-safety invariant: a crash
     * between the two leaves both the original stroke and its fragments live rather than losing the
     * original before its fragments are durable. That torn state is a duplicate, not data loss, and
     * [liveStrokes] simply shows both until the next edit touches them; had the remove landed first, a
     * crash before the adds could lose the stroke outright.
     *
     * [SheetEdit.ReplaceItems] follows the exact same adds-then-remove ordering, generalised to a mix
     * of strokes and text boxes; when [SheetEdit.ReplaceItems.added] carries a [SheetItem.Text] and
     * this log is still at [STROKE_LOG_VERSION_1], it is first upgraded to [STROKE_LOG_VERSION_2] via
     * [compactToVersion], the same atomic rewrite [compact] uses, before any of this edit's own
     * records are written.
     */
    fun append(edit: SheetEdit) {
        when (edit) {
            is SheetEdit.AddStrokes -> for (stroke in edit.strokes) {
                val recordSpan = writeRecord(encodeAddPayload(stroke))
                applyAdd(stroke, recordSpan)
                totalRecordCount++
            }

            is SheetEdit.RemoveStrokes -> if (edit.strokes.isNotEmpty()) {
                writeRecord(encodeRemovePayload(edit.strokes.map { it.id }))
                for (stroke in edit.strokes) applyRemove(stroke.id)
                totalRecordCount++
            }

            is SheetEdit.ReplaceStrokes -> {
                for (stroke in edit.added) {
                    val recordSpan = writeRecord(encodeAddPayload(stroke))
                    applyAdd(stroke, recordSpan)
                    totalRecordCount++
                }

                if (edit.removed.isNotEmpty()) {
                    writeRecord(encodeRemovePayload(edit.removed.map { it.id }))
                    for (stroke in edit.removed) applyRemove(stroke.id)
                    totalRecordCount++
                }
            }

            is SheetEdit.ReplaceItems -> {
                if (version < STROKE_LOG_VERSION_2 && edit.added.any { it is SheetItem.Text }) {
                    compactToVersion(STROKE_LOG_VERSION_2)
                }

                for (item in edit.added) {
                    val recordSpan = writeRecord(encodeAddItemPayload(item))
                    applyAddItem(item, recordSpan)
                    totalRecordCount++
                }

                if (edit.removed.isNotEmpty()) {
                    writeRecord(encodeRemovePayload(edit.removed.map { it.id }))
                    for (item in edit.removed) applyRemove(item.id)
                    totalRecordCount++
                }
            }
        }
    }

    /** Forces every appended record to disk. Implicit after each [append] under [SheetStrokeLogDurability.EVERY_RECORD]. */
    fun flush() = channel.force(false)

    /**
     * Rewrites the log to contain only the currently live items — strokes and text boxes alike, each
     * keeping its own sequence — discarding every dead record. The rewrite lands in a `.tmp` sibling
     * first, is fsynced, and is then renamed over the real file, so a crash mid-compaction leaves the
     * original, still-valid log in place. Never called implicitly by [append] for this reason alone; a
     * caller decides when to compact, typically by consulting [shouldCompact] with [liveBytes],
     * [totalBytes] and [deadRecordCount]. Keeps this log's own [version] unchanged; see
     * [compactToVersion] for the one case that does not.
     */
    fun compact() = compactToVersion(version)

    /**
     * [compact]'s own rewrite, additionally free to raise [version] to [targetVersion]: [append] calls
     * this directly, rather than [compact], to upgrade a [STROKE_LOG_VERSION_1] file to
     * [STROKE_LOG_VERSION_2] the moment it is first asked to add a [SheetTextBox], since that upgrade
     * must be durable and atomic before the text record itself is written.
     */
    private fun compactToVersion(targetVersion: Int) {
        val liveInOrder = combinedLiveItemsInOrder()
        val tempFile = File(file.parentFile, file.name + STROKE_LOG_COMPACT_SUFFIX)
        val newSpanById = HashMap<String, Long>()

        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.setLength(0)
            raf.write(frameHeader(targetVersion))
            for (item in liveInOrder) {
                val framed = frameRecord(encodeAddItemPayload(item))
                raf.write(framed)
                newSpanById[item.id.value] = framed.size.toLong()
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
        version = targetVersion
    }

    private fun combinedLiveItemsInOrder(): List<SheetItem> =
        (liveStrokesBySequence.values.map(SheetItem::Stroke) + liveTextsBySequence.values.map(SheetItem::Text))
            .sortedBy { it.sequence }

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

    private fun applyAddText(textBox: SheetTextBox, recordSpanBytes: Long): Boolean {
        if (textBox.sequence > highestSequenceSeen) highestSequenceSeen = textBox.sequence

        val id = textBox.id.value
        if (liveSequenceById.containsKey(id)) return false

        liveSequenceById[id] = textBox.sequence
        liveRecordSpanById[id] = recordSpanBytes
        liveTextsBySequence[textBox.sequence] = textBox
        return true
    }

    private fun applyAddItem(item: SheetItem, recordSpanBytes: Long): Boolean = when (item) {
        is SheetItem.Stroke -> applyAdd(item.stroke, recordSpanBytes)
        is SheetItem.Text -> applyAddText(item.textBox, recordSpanBytes)
    }

    /** Removes [id] from whichever of [liveStrokesBySequence] or [liveTextsBySequence] is currently holding it, since both kinds share [id]'s own id space and never collide on a sequence. */
    private fun applyRemove(id: StrokeId): Boolean {
        val sequence = liveSequenceById.remove(id.value) ?: return false
        liveRecordSpanById.remove(id.value)
        liveStrokesBySequence.remove(sequence)
        liveTextsBySequence.remove(sequence)
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

            KIND_ADD_TEXT_ALIGNED -> {
                val textBox = SheetTextRecordCodec.decodeAligned(input)
                if (applyAddText(textBox, recordSpan)) RecordOutcome.APPLIED else RecordOutcome.ADD_IDEMPOTENT
            }

            KIND_ADD_TEXT -> {
                val textBox = SheetTextRecordCodec.decode(input)
                if (applyAddText(textBox, recordSpan)) RecordOutcome.APPLIED else RecordOutcome.ADD_IDEMPOTENT
            }

            KIND_ADD_TEXT_LEGACY -> {
                val textBox = SheetTextRecordCodec.decodeLegacy(input)
                if (applyAddText(textBox, recordSpan)) RecordOutcome.APPLIED else RecordOutcome.ADD_IDEMPOTENT
            }

            else -> throw IOException("unknown record kind $kind")
        }
    }

    private fun encodeAddItemPayload(item: SheetItem): ByteArray = when (item) {
        is SheetItem.Stroke -> encodeAddPayload(item.stroke)
        is SheetItem.Text -> SheetTextRecordCodec.encode(item.textBox)
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

    /** Named for the record kind it writes, [KIND_REMOVE_STROKES], but the ids it lists may equally name a live [SheetTextBox], since both share one id space. */
    private fun encodeRemovePayload(ids: List<StrokeId>): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(KIND_REMOVE_STROKES.toInt())
            out.writeInt(ids.size)
            for (id in ids) out.writeUTF(id.value)
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

                val version = if (lengthBeforeOpen < STROKE_LOG_HEADER_BYTES) {
                    raf.setLength(0)
                    raf.seek(0)
                    raf.write(frameHeader(STROKE_LOG_VERSION_CURRENT))
                    raf.fd.sync()
                    STROKE_LOG_VERSION_CURRENT
                } else {
                    validateHeader(file, raf)
                }

                val log = SheetStrokeLog(file, raf.channel, durability, version)
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

        /**
         * Replays [file] read-only into a [SheetStrokeLogSnapshot]. Unlike [open], this never opens the
         * file for writing, never creates it or its directory, never replaces an incomplete header and
         * never truncates a torn tail: a torn tail yields the valid prefix before it, reported through
         * [SheetStrokeLogReplayReport.tornTailBytes], with every byte of the file left as it was. A
         * missing file, or one shorter than the header, reads as [SheetStrokeLogSnapshot.EMPTY].
         * [SheetStrokeLogException] is thrown exactly as [open] throws it.
         */
        fun read(file: File): SheetStrokeLogSnapshot {
            if (!file.isFile) return SheetStrokeLogSnapshot.EMPTY

            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < STROKE_LOG_HEADER_BYTES) return SheetStrokeLogSnapshot.EMPTY

                val version = validateHeader(file, raf)
                val log = SheetStrokeLog(file, raf.channel, SheetStrokeLogDurability.EVERY_RECORD, version)
                val report = log.replay(raf)

                return SheetStrokeLogSnapshot(log.liveItems(), report, log.maxSequenceSeen)
            }
        }

        /** Returns the header's own version so [open] can pass it on to the new [SheetStrokeLog]; [STROKE_LOG_VERSION_1] and [STROKE_LOG_VERSION_2] are the only versions any reader has ever written. */
        private fun validateHeader(file: File, raf: RandomAccessFile): Int {
            raf.seek(0)
            val header = ByteArray(STROKE_LOG_HEADER_BYTES)
            raf.readFully(header)

            val input = DataInputStream(ByteArrayInputStream(header))
            if (input.readInt() != STROKE_LOG_MAGIC) throw SheetStrokeLogException.InvalidHeader(file, "bad magic")

            val version = input.readByte().toInt() and 0xFF
            if (version != STROKE_LOG_VERSION_1 && version != STROKE_LOG_VERSION_2) {
                throw SheetStrokeLogException.InvalidHeader(file, "unsupported version $version")
            }
            return version
        }

        private fun frameHeader(version: Int): ByteArray {
            val buffer = ByteArrayOutputStream(STROKE_LOG_HEADER_BYTES)
            DataOutputStream(buffer).use { out ->
                out.writeInt(STROKE_LOG_MAGIC)
                out.writeByte(version)
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
