package com.folium.reader.core.ink

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SheetStrokeLogTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun textBox(id: String, sequence: Long, text: String = "note") = SheetTextBox(
        StrokeId(id), topLeft = SheetPoint(0.1f, 0.2f), widthSheetUnits = 0.5f, heightSheetUnits = 0.1f,
        text = text, font = SheetTextFont.SERIF, sizePt = 16f, style = SheetTextStyle.NORMAL, colorArgb = 0xFF112233.toInt(), sequence = sequence
    )

    /**
     * Byte-for-byte what a build before alignment existed wrote for [KIND_ADD_TEXT]: no alignment byte
     * at all, built independently of [SheetTextRecordCodec] itself so a future change to it cannot
     * silently make this fixture agree with the code under test.
     */
    private fun encodeAddTextPayload(
        id: String,
        sequence: Long,
        text: String,
        font: SheetTextFont = SheetTextFont.SERIF,
        sizePt: Float = 16f,
        style: SheetTextStyle = SheetTextStyle.NORMAL,
        topLeft: SheetPoint = SheetPoint(0.1f, 0.2f),
        widthSheetUnits: Float = 0.5f,
        heightSheetUnits: Float = 0.1f,
        colorArgb: Int = 0xFF112233.toInt()
    ): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(4) // KIND_ADD_TEXT
            out.writeUTF(id)
            out.writeLong(sequence)
            out.writeFloat(topLeft.x)
            out.writeFloat(topLeft.y)
            out.writeFloat(widthSheetUnits)
            out.writeFloat(heightSheetUnits)
            out.writeByte(font.ordinal)
            out.writeFloat(sizePt)
            out.writeByte(style.ordinal)
            out.writeInt(colorArgb)
            out.writeInt(textBytes.size)
            out.write(textBytes)
        }
        return buffer.toByteArray()
    }

    private fun assertTextBoxesMatch(expected: List<SheetTextBox>, actual: List<SheetTextBox>) {
        assertEquals(expected, actual)
    }

    private fun frame(kindAndPayload: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(kindAndPayload.size)
            out.write(kindAndPayload)
            val crc = CRC32()
            crc.update(kindAndPayload)
            out.writeInt(crc.value.toInt())
        }
        return buffer.toByteArray()
    }

    /** Byte-for-byte what the pre-text-box writer produced: header version 1, then plain ADD_STROKE records, built independently of [SheetStrokeLog] itself so a future change to it cannot silently make this fixture agree with the code under test. */
    private fun buildV1File(file: File, strokes: List<InkStroke>) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use { out ->
                out.writeInt(0x464F_4C4C)
                out.writeByte(1)
            }
            raf.write(header.toByteArray())

            for (s in strokes) raf.write(frame(encodeV1AddStrokePayload(s)))
        }
    }

    private fun encodeV1AddStrokePayload(stroke: InkStroke): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(1) // KIND_ADD_STROKE
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

    /**
     * Byte-for-byte what a development build wrote for [KIND_ADD_TEXT_LEGACY]: a single `style`
     * ordinal (0 = BODY, 1 = TITLE) in place of today's `font` + `sizePt` + `style`, built independently
     * of [SheetTextRecordCodec] itself so a future change to it cannot silently make this fixture agree
     * with the code under test.
     */
    private fun encodeLegacyAddTextPayload(
        id: String,
        sequence: Long,
        legacyStyleOrdinal: Int,
        text: String,
        topLeft: SheetPoint = SheetPoint(0.1f, 0.2f),
        widthSheetUnits: Float = 0.5f,
        heightSheetUnits: Float = 0.1f,
        colorArgb: Int = 0xFF112233.toInt()
    ): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(3) // KIND_ADD_TEXT_LEGACY
            out.writeUTF(id)
            out.writeLong(sequence)
            out.writeFloat(topLeft.x)
            out.writeFloat(topLeft.y)
            out.writeFloat(widthSheetUnits)
            out.writeFloat(heightSheetUnits)
            out.writeByte(legacyStyleOrdinal)
            out.writeInt(colorArgb)
            out.writeInt(textBytes.size)
            out.write(textBytes)
        }
        return buffer.toByteArray()
    }

    private fun appendRawRecord(file: File, kindAndPayload: ByteArray) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(frame(kindAndPayload))
        }
    }

    private fun recordKindsInOrder(file: File): List<Byte> {
        val kinds = mutableListOf<Byte>()
        RandomAccessFile(file, "r").use { raf ->
            var position = headerBytes()
            val length = raf.length()
            while (position < length) {
                raf.seek(position)
                val recordLength = raf.readInt()
                kinds += raf.readByte()
                position += 4L + recordLength + 4L
            }
        }
        return kinds
    }

    private fun writeIntAt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun stroke(id: String, sequence: Long, withOptionalChannels: Boolean = false, sampleCount: Int = 4) = InkStroke(
        StrokeId(id), InkTool.PEN, InkTip.BALLPOINT, colorArgb = 0xFF000000.toInt(),
        widthSheetUnits = 0.01f, inputKind = InkInputKind.STYLUS,
        samples = (0 until sampleCount).map { i ->
            InkSample(
                x = 0.1f * i, y = 0.2f * i, elapsedMillis = i * 10,
                pressure = if (withOptionalChannels) 0.5f else null,
                tiltRadians = if (withOptionalChannels) 0.1f else null,
                orientationRadians = if (withOptionalChannels) 0.2f else null
            )
        },
        sequence = sequence
    )

    /**
     * [InkSampleCodec] is a lossy, quantised format, so a stroke that survives an append and a reopen
     * matches its original only within that codec's own documented tolerance, not bit-for-bit.
     */
    private fun assertStrokesMatch(expected: List<InkStroke>, actual: List<InkStroke>) {
        assertEquals(expected.map { it.id }, actual.map { it.id })
        assertEquals(expected.map { it.sequence }, actual.map { it.sequence })
        for (index in expected.indices) {
            val e = expected[index]
            val a = actual[index]
            assertEquals(e.tool, a.tool)
            assertEquals(e.tip, a.tip)
            assertEquals(e.colorArgb, a.colorArgb)
            assertEquals(e.widthSheetUnits, a.widthSheetUnits, 1e-6f)
            assertEquals(e.inputKind, a.inputKind)
            assertEquals(e.samples.size, a.samples.size)
            for (sampleIndex in e.samples.indices) {
                val expectedSample = e.samples[sampleIndex]
                val actualSample = a.samples[sampleIndex]
                assertTrue(abs(expectedSample.x - actualSample.x) <= 1f / 65536f)
                assertTrue(abs(expectedSample.y - actualSample.y) <= 1f / 65536f)
                assertEquals(expectedSample.elapsedMillis, actualSample.elapsedMillis)
            }
        }
    }

    @Test fun addThenReopenRoundTripsStrokesWithAndWithoutOptionalChannels() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val a = stroke("a", sequence = 0, withOptionalChannels = false)
        val b = stroke("b", sequence = 1, withOptionalChannels = true)

        SheetStrokeLog.open(file).use { log ->
            log.append(SheetEdit.AddStrokes(listOf(a, b)))
        }

        SheetStrokeLog.open(file).use { log ->
            assertStrokesMatch(listOf(a, b), log.liveStrokes())
            assertEquals(0, log.replayReport.tornTailBytes)
        }
    }

    /**
     * [InkTool] is stored by [InkTool.ordinal], and [InkTool.PEN] is ordinal 0 both before and after
     * [InkTool.HIGHLIGHTER] was appended: a record an older build wrote, when [InkTool.PEN] was the
     * only entry, decodes to the same tool under this build.
     */
    @Test fun aPenStrokeWrittenBeforeTheHighlighterToolExistedStillDecodesToPen() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val penStroke = stroke("pen", sequence = 0)

        SheetStrokeLog.open(file).use { log ->
            log.append(SheetEdit.AddStrokes(listOf(penStroke)))
        }

        SheetStrokeLog.open(file).use { log ->
            assertEquals(InkTool.PEN, log.liveStrokes().single().tool)
        }
    }

    @Test fun orderingBySequenceSurvivesAnUndoThenRestore() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val first = stroke("first", sequence = 0)
        val second = stroke("second", sequence = 1)

        SheetStrokeLog.open(file).use { log ->
            log.append(SheetEdit.AddStrokes(listOf(first)))
            log.append(SheetEdit.AddStrokes(listOf(second)))
            log.append(SheetEdit.RemoveStrokes(listOf(first)))
            log.append(SheetEdit.AddStrokes(listOf(first)))
            assertStrokesMatch(listOf(first, second), log.liveStrokes())
        }

        SheetStrokeLog.open(file).use { log ->
            assertStrokesMatch(listOf(first, second), log.liveStrokes())
        }
    }

    @Test fun tornTailInsideTheLengthFieldIsReportedAndRepaired() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val a = stroke("a", sequence = 0)
        SheetStrokeLog.open(file).use { it.append(SheetEdit.AddStrokes(listOf(a))) }

        truncateTo(file, headerBytes() + 2L)

        val b = stroke("b", sequence = 1)
        SheetStrokeLog.open(file).use { log ->
            assertTrue(log.replayReport.tornTailBytes > 0)
            assertTrue(log.liveStrokes().isEmpty())
            log.append(SheetEdit.AddStrokes(listOf(b)))
        }

        SheetStrokeLog.open(file).use { log ->
            assertStrokesMatch(listOf(b), log.liveStrokes())
            assertEquals(0, log.replayReport.tornTailBytes)
        }
    }

    @Test fun tornTailInsideThePayloadIsReportedAndRepaired() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val a = stroke("a", sequence = 0)
        SheetStrokeLog.open(file).use { it.append(SheetEdit.AddStrokes(listOf(a))) }

        truncateTo(file, headerBytes() + 4L + 3L)

        val b = stroke("b", sequence = 1)
        SheetStrokeLog.open(file).use { log ->
            assertTrue(log.replayReport.tornTailBytes > 0)
            assertTrue(log.liveStrokes().isEmpty())
            log.append(SheetEdit.AddStrokes(listOf(b)))
        }

        SheetStrokeLog.open(file).use { log ->
            assertStrokesMatch(listOf(b), log.liveStrokes())
            assertEquals(0, log.replayReport.tornTailBytes)
        }
    }

    @Test fun tornTailInsideTheCrcIsReportedAndRepaired() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val a = stroke("a", sequence = 0)
        SheetStrokeLog.open(file).use { it.append(SheetEdit.AddStrokes(listOf(a))) }

        truncateTo(file, file.length() - 2L)

        val b = stroke("b", sequence = 1)
        SheetStrokeLog.open(file).use { log ->
            assertTrue(log.replayReport.tornTailBytes > 0)
            assertTrue(log.liveStrokes().isEmpty())
            log.append(SheetEdit.AddStrokes(listOf(b)))
        }

        SheetStrokeLog.open(file).use { log ->
            assertStrokesMatch(listOf(b), log.liveStrokes())
            assertEquals(0, log.replayReport.tornTailBytes)
        }
    }

    @Test fun midFileCorruptionThrowsWithTheOffsetAndLeavesTheFileUntouched() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val a = stroke("a", sequence = 0)
        val b = stroke("b", sequence = 1)
        SheetStrokeLog.open(file).use { it.append(SheetEdit.AddStrokes(listOf(a, b))) }

        val firstRecordOffset = headerBytes()
        flipOneByte(file, firstRecordOffset + 4L)
        val bytesBefore = file.readBytes()

        val exception = try {
            SheetStrokeLog.open(file)
            null
        } catch (e: SheetStrokeLogException.Corrupt) {
            e
        }

        assertTrue(exception != null)
        assertEquals(firstRecordOffset, exception!!.offsetBytes)
        assertTrue(bytesBefore.contentEquals(file.readBytes()))
    }

    @Test fun aDeclaredLengthLargerThanTheFileDoesNotAllocate() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        SheetStrokeLog.open(file).use { it.append(SheetEdit.AddStrokes(listOf(stroke("a", sequence = 0)))) }

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(headerBytes())
            raf.writeInt(2_000_000_000)
        }
        truncateTo(file, headerBytes() + 4L + 10L)

        SheetStrokeLog.open(file).use { log ->
            assertTrue(log.replayReport.tornTailBytes > 0)
            assertTrue(log.liveStrokes().isEmpty())
        }
    }

    @Test fun idempotentReplayCountsAreReported() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val a = stroke("a", sequence = 0)

        SheetStrokeLog.open(file).use { log ->
            log.append(SheetEdit.AddStrokes(listOf(a)))
            log.append(SheetEdit.AddStrokes(listOf(a)))
            log.append(SheetEdit.RemoveStrokes(listOf(a)))
            log.append(SheetEdit.RemoveStrokes(listOf(a)))
        }

        SheetStrokeLog.open(file).use { log ->
            assertEquals(1, log.replayReport.idempotentAddCount)
            assertEquals(1, log.replayReport.idempotentRemoveCount)
            assertTrue(log.liveStrokes().isEmpty())
        }
    }

    @Test fun compactionPreservesLiveStrokesAndOrderAndShrinksTheFile() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val kept = stroke("kept", sequence = 0)
        val removed = stroke("removed", sequence = 1)

        SheetStrokeLog.open(file).use { log ->
            log.append(SheetEdit.AddStrokes(listOf(kept, removed)))
            log.append(SheetEdit.RemoveStrokes(listOf(removed)))

            val sizeBeforeCompaction = log.totalBytes
            log.compact()

            assertStrokesMatch(listOf(kept), log.liveStrokes())
            assertTrue(log.totalBytes < sizeBeforeCompaction)
            assertEquals(0, log.deadRecordCount)
        }

        SheetStrokeLog.open(file).use { log ->
            assertStrokesMatch(listOf(kept), log.liveStrokes())
        }
    }

    @Test fun compactionSurvivesALeftoverTempFile() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val kept = stroke("kept", sequence = 0)
        File(file.parentFile, file.name + ".tmp").writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        SheetStrokeLog.open(file).use { log ->
            log.append(SheetEdit.AddStrokes(listOf(kept)))
            log.compact()
            assertStrokesMatch(listOf(kept), log.liveStrokes())
        }

        SheetStrokeLog.open(file).use { log ->
            assertStrokesMatch(listOf(kept), log.liveStrokes())
        }
    }

    @Test fun shouldCompactRequiresBothAMinimumSizeAndEnoughDeadWeight() {
        // Below the 256 KiB floor: never worth rewriting regardless of dead ratio.
        assertFalse(SheetStrokeLog.shouldCompact(liveBytes = 10, totalBytes = 100, deadRecordCount = 0))

        // Past the floor but under half dead, with too few dead records to trigger the other path.
        assertFalse(SheetStrokeLog.shouldCompact(liveBytes = 250_000, totalBytes = 300_000, deadRecordCount = 0))

        // Past the floor with two thirds of the log dead.
        assertTrue(SheetStrokeLog.shouldCompact(liveBytes = 100_000, totalBytes = 300_000, deadRecordCount = 0))

        // No dead bytes at all, but enough dead records on their own to slow a reopen.
        assertTrue(SheetStrokeLog.shouldCompact(liveBytes = 300_000, totalBytes = 300_000, deadRecordCount = 500))
    }

    @Test
    fun aNewLogReportsNoReplacedHeader() {
        val file = java.io.File(tempFolder.root, "fresh.log")

        SheetStrokeLog.open(file).use { log ->
            assertEquals(null, log.replayReport.replacedIncompleteHeaderBytes)
        }
    }

    @Test
    fun anExistingFileShorterThanTheHeaderIsReplacedAndReported() {
        val file = java.io.File(tempFolder.root, "short.log")
        file.writeBytes(byteArrayOf(0x46, 0x4F))

        SheetStrokeLog.open(file).use { log ->
            assertEquals(2L, log.replayReport.replacedIncompleteHeaderBytes)
            assertEquals(0, log.replayReport.recordCount)
        }

        SheetStrokeLog.open(file).use { log ->
            assertEquals(null, log.replayReport.replacedIncompleteHeaderBytes)
        }
    }

    @Test fun replayAfterAReplaceShowsOnlyTheAddedFragments() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val original = stroke("original", sequence = 0)
        val fragment = stroke("fragment", sequence = 1)

        SheetStrokeLog.open(file).use { log ->
            log.append(SheetEdit.AddStrokes(listOf(original)))
            log.append(SheetEdit.ReplaceStrokes(removed = listOf(original), added = listOf(fragment)))
            assertStrokesMatch(listOf(fragment), log.liveStrokes())
        }

        SheetStrokeLog.open(file).use { log ->
            assertStrokesMatch(listOf(fragment), log.liveStrokes())
        }
    }

    /**
     * [SheetStrokeLog.append] writes a [SheetEdit.ReplaceStrokes]'s ADD_STROKE records before its
     * REMOVE_STROKES record. A crash right between the two — simulated here by truncating the file at
     * the byte offset reached right after the adds, produced the same way a real replace would produce
     * it — must leave both the original stroke and its fragment live rather than losing the original,
     * and must not be mistaken for corruption.
     */
    @Test fun aCrashBetweenAReplacesAddsAndItsRemoveLeavesBothTheOriginalAndItsFragmentLive() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val original = stroke("original", sequence = 0)
        val fragment = stroke("fragment", sequence = 1)
        val offsetAfterAdds: Long

        SheetStrokeLog.open(file).use { log ->
            log.append(SheetEdit.AddStrokes(listOf(original)))
            log.append(SheetEdit.AddStrokes(listOf(fragment)))
            offsetAfterAdds = log.totalBytes
            log.append(SheetEdit.RemoveStrokes(listOf(original)))
        }

        val fullBytes = file.readBytes()
        val tornFile = File(tempFolder.newFolder(), "torn.log")
        tornFile.writeBytes(fullBytes.copyOfRange(0, offsetAfterAdds.toInt()))

        SheetStrokeLog.open(tornFile).use { log ->
            assertTrue(log.replayReport.tornTailBytes == 0L)
            assertStrokesMatch(listOf(original, fragment), log.liveStrokes())
        }
    }

    @Test fun aFreshLogIsCreatedAtVersion2() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        SheetStrokeLog.open(file).use { log -> assertEquals(2, log.version) }
    }

    @Test fun aV1FileWrittenByThePreTextBoxWriterStillOpens() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val a = stroke("a", sequence = 0)
        buildV1File(file, listOf(a))

        SheetStrokeLog.open(file).use { log ->
            assertEquals(1, log.version)
            assertStrokesMatch(listOf(a), log.liveStrokes())
            assertTrue(log.liveTexts().isEmpty())
        }
    }

    @Test fun aV1FileIsUpgradedToVersion2OnItsFirstTextRecordWithNothingLost() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val a = stroke("a", sequence = 0)
        buildV1File(file, listOf(a))
        val box = textBox("box", sequence = 1)

        SheetStrokeLog.open(file).use { log ->
            assertEquals(1, log.version)
            log.append(SheetEdit.ReplaceItems(removed = emptyList(), added = listOf(SheetItem.Text(box))))
            assertEquals(2, log.version)
            assertStrokesMatch(listOf(a), log.liveStrokes())
            assertTextBoxesMatch(listOf(box), log.liveTexts())
        }

        SheetStrokeLog.open(file).use { log ->
            assertEquals(2, log.version)
            assertStrokesMatch(listOf(a), log.liveStrokes())
            assertTextBoxesMatch(listOf(box), log.liveTexts())
        }
    }

    @Test fun aV1FileWithATornTailIsUpgradedOnItsFirstTextRecordWithNothingLost() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val a = stroke("a", sequence = 0)
        val torn = stroke("torn", sequence = 1)
        buildV1File(file, listOf(a, torn))
        truncateTo(file, file.length() - 3L)
        val box = textBox("box", sequence = 2)

        SheetStrokeLog.open(file).use { log ->
            assertEquals(1, log.version)
            assertTrue(log.replayReport.tornTailBytes > 0)
            log.append(SheetEdit.ReplaceItems(removed = emptyList(), added = listOf(SheetItem.Text(box))))
            assertEquals(2, log.version)
        }

        SheetStrokeLog.open(file).use { log ->
            assertEquals(2, log.version)
            assertEquals(0, log.replayReport.tornTailBytes)
            assertStrokesMatch(listOf(a), log.liveStrokes())
            assertTextBoxesMatch(listOf(box), log.liveTexts())
        }
    }

    @Test fun interleavedStrokesAndTextBoxesRoundTripThroughAV2File() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val a = stroke("a", sequence = 0)
        val box1 = textBox("box1", sequence = 1)
        val b = stroke("b", sequence = 2)
        val box2 = textBox("box2", sequence = 3, text = "second box")

        SheetStrokeLog.open(file).use { log ->
            log.append(SheetEdit.AddStrokes(listOf(a)))
            log.append(SheetEdit.ReplaceItems(removed = emptyList(), added = listOf(SheetItem.Text(box1))))
            log.append(SheetEdit.AddStrokes(listOf(b)))
            log.append(SheetEdit.ReplaceItems(removed = emptyList(), added = listOf(SheetItem.Text(box2))))
        }

        SheetStrokeLog.open(file).use { log ->
            assertStrokesMatch(listOf(a, b), log.liveStrokes())
            assertTextBoxesMatch(listOf(box1, box2), log.liveTexts())
            assertEquals(listOf(a.sequence, box1.sequence, b.sequence, box2.sequence), log.liveItems().map { it.sequence })
        }
    }

    @Test fun compactionKeepsTextBoxesAndEverySequence() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val keptStroke = stroke("keptStroke", sequence = 0)
        val removedStroke = stroke("removedStroke", sequence = 1)
        val keptText = textBox("keptText", sequence = 2)
        val removedText = textBox("removedText", sequence = 3)

        SheetStrokeLog.open(file).use { log ->
            log.append(SheetEdit.AddStrokes(listOf(keptStroke, removedStroke)))
            log.append(SheetEdit.ReplaceItems(removed = emptyList(), added = listOf(SheetItem.Text(keptText), SheetItem.Text(removedText))))
            log.append(SheetEdit.RemoveStrokes(listOf(removedStroke)))
            log.append(SheetEdit.ReplaceItems(removed = listOf(SheetItem.Text(removedText)), added = emptyList()))

            log.compact()

            assertStrokesMatch(listOf(keptStroke), log.liveStrokes())
            assertTextBoxesMatch(listOf(keptText), log.liveTexts())
            assertEquals(0, log.deadRecordCount)
        }

        SheetStrokeLog.open(file).use { log ->
            assertStrokesMatch(listOf(keptStroke), log.liveStrokes())
            assertTextBoxesMatch(listOf(keptText), log.liveTexts())
        }
    }

    /**
     * The same crash-safety invariant [aCrashBetweenAReplacesAddsAndItsRemoveLeavesBothTheOriginalAndItsFragmentLive]
     * proves for two strokes, but for a [SheetEdit.ReplaceItems] mixing kinds: a stroke is replaced by
     * a text box. A crash between the add and the remove must leave both live.
     */
    @Test fun aCrashBetweenAReplaceItemsAddsAndItsRemoveLeavesBothTheOriginalStrokeAndTheAddedTextLive() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val originalStroke = stroke("originalStroke", sequence = 0)
        val addedText = textBox("addedText", sequence = 1)
        val offsetAfterAdds: Long

        SheetStrokeLog.open(file).use { log ->
            log.append(SheetEdit.AddStrokes(listOf(originalStroke)))
            log.append(SheetEdit.ReplaceItems(removed = emptyList(), added = listOf(SheetItem.Text(addedText))))
            offsetAfterAdds = log.totalBytes
            log.append(SheetEdit.ReplaceItems(removed = listOf(SheetItem.Stroke(originalStroke)), added = emptyList()))
        }

        val fullBytes = file.readBytes()
        val tornFile = File(tempFolder.newFolder(), "torn.log")
        tornFile.writeBytes(fullBytes.copyOfRange(0, offsetAfterAdds.toInt()))

        SheetStrokeLog.open(tornFile).use { log ->
            assertTrue(log.replayReport.tornTailBytes == 0L)
            assertStrokesMatch(listOf(originalStroke), log.liveStrokes())
            assertTextBoxesMatch(listOf(addedText), log.liveTexts())
        }
    }

    @Test fun tornTailInsideATextRecordIsReportedAndRepaired() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val box = textBox("box", sequence = 0)
        SheetStrokeLog.open(file).use {
            it.append(SheetEdit.ReplaceItems(removed = emptyList(), added = listOf(SheetItem.Text(box))))
        }

        truncateTo(file, file.length() - 3L)

        val b = stroke("b", sequence = 1)
        SheetStrokeLog.open(file).use { log ->
            assertTrue(log.replayReport.tornTailBytes > 0)
            assertTrue(log.liveTexts().isEmpty())
            log.append(SheetEdit.AddStrokes(listOf(b)))
        }

        SheetStrokeLog.open(file).use { log ->
            assertStrokesMatch(listOf(b), log.liveStrokes())
            assertEquals(0, log.replayReport.tornTailBytes)
        }
    }

    @Test fun aCorruptTextByteCountInsideAnAddTextRecordIsReportedAsCorruptionRatherThanAllocatingFromIt() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        SheetStrokeLog.open(file).use { }

        val box = textBox("box", sequence = 0, text = "a")
        val payload = SheetTextRecordCodec.encode(box)
        val textByteCountOffset = payload.size - 1 - 4
        writeIntAt(payload, textByteCountOffset, 10_000_000)

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(frame(payload))
        }

        val exception = try {
            SheetStrokeLog.open(file)
            null
        } catch (e: SheetStrokeLogException.Corrupt) {
            e
        }

        assertTrue(exception != null)
    }

    @Test fun legacyKind3TextRecordsDecodeMappedToTheCurrentModelAlongsideStrokes() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val a = stroke("a", sequence = 0)
        SheetStrokeLog.open(file).use { it.append(SheetEdit.AddStrokes(listOf(a))) }

        val bodyText = "First line\nSecond line"
        val titleText = "note 📝 done 😀"
        appendRawRecord(file, encodeLegacyAddTextPayload("bodyBox", sequence = 1, legacyStyleOrdinal = 0, text = bodyText))
        appendRawRecord(file, encodeLegacyAddTextPayload("titleBox", sequence = 2, legacyStyleOrdinal = 1, text = titleText))

        SheetStrokeLog.open(file).use { log ->
            assertStrokesMatch(listOf(a), log.liveStrokes())

            val texts = log.liveTexts()
            assertEquals(listOf("bodyBox", "titleBox"), texts.map { it.id.value })

            val body = texts[0]
            assertEquals(SheetTextFont.SERIF, body.font)
            assertEquals(16f, body.sizePt, 1e-6f)
            assertEquals(SheetTextStyle.NORMAL, body.style)
            assertEquals(bodyText, body.text)

            val title = texts[1]
            assertEquals(SheetTextFont.SANS, title.font)
            assertEquals(19f, title.sizePt, 1e-6f)
            assertEquals(SheetTextStyle.BOLD, title.style)
            assertEquals(titleText, title.text)
        }
    }

    @Test fun removingATextBoxHeldInALegacyKind3RecordWorks() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        SheetStrokeLog.open(file).use { }
        appendRawRecord(file, encodeLegacyAddTextPayload("legacyBox", sequence = 0, legacyStyleOrdinal = 0, text = "note"))

        SheetStrokeLog.open(file).use { log ->
            val box = log.liveTexts().single()
            log.append(SheetEdit.ReplaceItems(removed = listOf(SheetItem.Text(box)), added = emptyList()))
            assertTrue(log.liveTexts().isEmpty())
        }

        SheetStrokeLog.open(file).use { log ->
            assertTrue(log.liveTexts().isEmpty())
        }
    }

    @Test fun compactionRewritesALegacyKind3RecordUnderTheCurrentKindWithNothingLost() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        SheetStrokeLog.open(file).use { }
        appendRawRecord(file, encodeLegacyAddTextPayload("legacyBox", sequence = 0, legacyStyleOrdinal = 1, text = "note"))

        SheetStrokeLog.open(file).use { log ->
            val mapped = log.liveTexts().single()
            log.compact()

            assertEquals(listOf(KIND_ADD_TEXT_ALIGNED), recordKindsInOrder(file))
            assertTextBoxesMatch(listOf(mapped), log.liveTexts())
        }

        SheetStrokeLog.open(file).use { log ->
            val reopened = log.liveTexts().single()
            assertEquals(SheetTextFont.SANS, reopened.font)
            assertEquals(19f, reopened.sizePt, 1e-6f)
            assertEquals(SheetTextStyle.BOLD, reopened.style)
            assertEquals(SheetTextAlignment.LEFT, reopened.alignment)
            assertEquals("note", reopened.text)
        }
    }

    @Test fun kind4TextRecordsWrittenBeforeAlignmentExistedDecodeWithLeftAlignmentAlongsideStrokes() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val a = stroke("a", sequence = 0)
        SheetStrokeLog.open(file).use { it.append(SheetEdit.AddStrokes(listOf(a))) }

        appendRawRecord(file, encodeAddTextPayload("box", sequence = 1, text = "hello"))

        SheetStrokeLog.open(file).use { log ->
            assertStrokesMatch(listOf(a), log.liveStrokes())
            val box = log.liveTexts().single()
            assertEquals(SheetTextAlignment.LEFT, box.alignment)
            assertEquals("hello", box.text)
        }
    }

    @Test fun compactionRewritesAKind4RecordUnderTheCurrentKindWithNothingLost() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        SheetStrokeLog.open(file).use { }
        appendRawRecord(file, encodeAddTextPayload("box", sequence = 0, text = "hello", font = SheetTextFont.MONO, sizePt = 22f, style = SheetTextStyle.ITALIC))

        SheetStrokeLog.open(file).use { log ->
            val mapped = log.liveTexts().single()
            log.compact()

            assertEquals(listOf(KIND_ADD_TEXT_ALIGNED), recordKindsInOrder(file))
            assertTextBoxesMatch(listOf(mapped), log.liveTexts())
        }

        SheetStrokeLog.open(file).use { log ->
            val reopened = log.liveTexts().single()
            assertEquals(SheetTextFont.MONO, reopened.font)
            assertEquals(22f, reopened.sizePt, 1e-6f)
            assertEquals(SheetTextStyle.ITALIC, reopened.style)
            assertEquals(SheetTextAlignment.LEFT, reopened.alignment)
            assertEquals("hello", reopened.text)
        }
    }

    @Test fun tornTailInsideALegacyKind3RecordIsReportedAndRepaired() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        SheetStrokeLog.open(file).use { }
        appendRawRecord(file, encodeLegacyAddTextPayload("legacyBox", sequence = 0, legacyStyleOrdinal = 0, text = "note"))

        truncateTo(file, file.length() - 3L)

        val b = stroke("b", sequence = 1)
        SheetStrokeLog.open(file).use { log ->
            assertTrue(log.replayReport.tornTailBytes > 0)
            assertTrue(log.liveTexts().isEmpty())
            log.append(SheetEdit.AddStrokes(listOf(b)))
        }

        SheetStrokeLog.open(file).use { log ->
            assertStrokesMatch(listOf(b), log.liveStrokes())
            assertEquals(0, log.replayReport.tornTailBytes)
        }
    }

    @Test fun aCorruptTextByteCountInsideALegacyKind3RecordIsReportedAsCorruptionRatherThanAllocatingFromIt() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        SheetStrokeLog.open(file).use { }

        val payload = encodeLegacyAddTextPayload("legacyBox", sequence = 0, legacyStyleOrdinal = 0, text = "a")
        val textByteCountOffset = payload.size - 1 - 4
        writeIntAt(payload, textByteCountOffset, 10_000_000)
        appendRawRecord(file, payload)

        val exception = try {
            SheetStrokeLog.open(file)
            null
        } catch (e: SheetStrokeLogException.Corrupt) {
            e
        }

        assertTrue(exception != null)
    }

    @Test fun newTextWritesNeverEmitTheLegacyKind3OrTheNowReadOnlyKind4() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val box = textBox("freshBox", sequence = 0)

        SheetStrokeLog.open(file).use { log ->
            log.append(SheetEdit.ReplaceItems(removed = emptyList(), added = listOf(SheetItem.Text(box))))
        }

        assertEquals(listOf(KIND_ADD_TEXT_ALIGNED), recordKindsInOrder(file))
    }

    @Test fun readReplaysLiveItemsWithoutOpeningForWrite() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val a = stroke("a", sequence = 0)
        val b = stroke("b", sequence = 1)
        val box = textBox("t", sequence = 2)

        SheetStrokeLog.open(file).use { log ->
            log.append(SheetEdit.AddStrokes(listOf(a, b)))
            log.append(SheetEdit.ReplaceItems(removed = listOf(SheetItem.Stroke(a)), added = listOf(SheetItem.Text(box))))
        }

        val snapshot = SheetStrokeLog.read(file)

        assertStrokesMatch(listOf(b), snapshot.strokes)
        assertEquals(listOf(box.id), snapshot.textBoxes.map { it.id })
        assertEquals(listOf(b.id, box.id), snapshot.items.map { it.id })
        assertEquals(2L, snapshot.maxSequenceSeen)
        assertEquals(0L, snapshot.replayReport.tornTailBytes)
    }

    @Test fun readOfATornTailReturnsTheValidPrefixAndLeavesTheBytesUntouched() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        val a = stroke("a", sequence = 0)
        val b = stroke("b", sequence = 1)
        SheetStrokeLog.open(file).use { it.append(SheetEdit.AddStrokes(listOf(a, b))) }

        truncateTo(file, file.length() - 3L)
        val before = file.readBytes()

        val snapshot = SheetStrokeLog.read(file)

        assertStrokesMatch(listOf(a), snapshot.strokes)
        assertTrue(snapshot.replayReport.tornTailBytes > 0)
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test fun readOfAMissingFileIsEmptyAndCreatesNothing() {
        val file = File(tempFolder.newFolder(), "absent/strokes.log")

        val snapshot = SheetStrokeLog.read(file)

        assertTrue(snapshot.items.isEmpty())
        assertEquals(-1L, snapshot.maxSequenceSeen)
        assertFalse(file.exists())
        assertFalse(file.parentFile.exists())
    }

    @Test fun readOfAFileShorterThanTheHeaderIsEmptyAndLeavesItUntouched() {
        val file = File(tempFolder.newFolder(), "strokes.log")
        file.writeBytes(byteArrayOf(0x46, 0x4F))

        val snapshot = SheetStrokeLog.read(file)

        assertTrue(snapshot.items.isEmpty())
        assertTrue(byteArrayOf(0x46, 0x4F).contentEquals(file.readBytes()))
    }

    private fun headerBytes(): Long = 5L

    private fun truncateTo(file: File, length: Long) {
        RandomAccessFile(file, "rw").use { it.setLength(length) }
    }

    private fun flipOneByte(file: File, offset: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            val original = raf.readByte()
            raf.seek(offset)
            raf.writeByte((original.toInt() xor 0xFF) and 0xFF)
        }
    }
}
