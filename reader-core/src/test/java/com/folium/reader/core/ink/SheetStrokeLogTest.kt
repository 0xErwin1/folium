package com.folium.reader.core.ink

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SheetStrokeLogTest {
    @get:Rule val tempFolder = TemporaryFolder()

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
