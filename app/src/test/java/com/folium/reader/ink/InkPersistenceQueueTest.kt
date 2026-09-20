package com.folium.reader.ink

import com.folium.reader.core.ink.InkInputKind
import com.folium.reader.core.ink.InkSample
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTip
import com.folium.reader.core.ink.InkTool
import com.folium.reader.core.ink.SheetEdit
import com.folium.reader.core.ink.StrokeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

private fun stroke(id: String, sequence: Long): InkStroke = InkStroke(
    id = StrokeId(id),
    tool = InkTool.PEN,
    tip = InkTip.BALLPOINT,
    colorArgb = 0xFF000000.toInt(),
    widthSheetUnits = InkPenWidths.MEDIUM_SHEET_UNITS,
    inputKind = InkInputKind.FINGER,
    samples = listOf(InkSample(0f, 0f, 0)),
    sequence = sequence
)

class InkPersistenceQueueTest {

    @Test
    fun appliesEditsInEnqueueOrder() {
        val applied = CopyOnWriteArrayList<SheetEdit>()
        val queue = InkPersistenceQueue(sink = { edit -> applied += edit }, onFailure = { fail("unexpected failure: $it") })

        val editOne = SheetEdit.AddStrokes(listOf(stroke("a", 0)))
        val editTwo = SheetEdit.AddStrokes(listOf(stroke("b", 1)))
        val editThree = SheetEdit.RemoveStrokes(listOf(stroke("a", 0)))
        queue.enqueue(editOne)
        queue.enqueue(editTwo)
        queue.enqueue(editThree)

        assertTrue(queue.flushAndWait(timeoutMillis = 2_000))
        assertEquals(listOf(editOne, editTwo, editThree), applied)
    }

    @Test
    fun reportsAFailureExactlyOnceAndStopsApplyingFurtherEdits() {
        val applied = CopyOnWriteArrayList<SheetEdit>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val boom = IllegalStateException("disk is gone")
        val queue = InkPersistenceQueue(
            sink = { edit ->
                if (edit is SheetEdit.RemoveStrokes) throw boom
                applied += edit
            },
            onFailure = { failures += it }
        )

        queue.enqueue(SheetEdit.AddStrokes(listOf(stroke("a", 0))))
        queue.enqueue(SheetEdit.RemoveStrokes(listOf(stroke("a", 0))))
        queue.enqueue(SheetEdit.AddStrokes(listOf(stroke("b", 1))))

        assertTrue(queue.flushAndWait(timeoutMillis = 2_000))
        assertEquals(1, applied.size)
        assertEquals(listOf(boom), failures)
        assertTrue(queue.hasFailed)
    }

    @Test
    fun enqueueAfterFailureIsANoOp() {
        val applied = CopyOnWriteArrayList<SheetEdit>()
        val queue = InkPersistenceQueue(
            sink = { throw IllegalStateException("always fails") },
            onFailure = {}
        )

        queue.enqueue(SheetEdit.AddStrokes(listOf(stroke("a", 0))))
        assertTrue(queue.flushAndWait(timeoutMillis = 2_000))

        queue.enqueue(SheetEdit.AddStrokes(listOf(stroke("b", 1))))
        assertTrue(queue.flushAndWait(timeoutMillis = 2_000))

        assertEquals(0, applied.size)
    }

    @Test
    fun enqueueAfterShutdownIsRefusedWithoutThrowing() {
        val applied = CopyOnWriteArrayList<SheetEdit>()
        val queue = InkPersistenceQueue(sink = { edit -> applied += edit }, onFailure = { fail("unexpected failure: $it") })
        val accepted = SheetEdit.AddStrokes(listOf(stroke("a", 0)))

        assertTrue(queue.enqueue(accepted))
        queue.shutdown()

        assertFalse(queue.enqueue(SheetEdit.AddStrokes(listOf(stroke("b", 1)))))
        assertTrue(queue.isClosed)
        assertTrue(queue.flushAndWait(timeoutMillis = 2_000))
        assertEquals(listOf<SheetEdit>(accepted), applied)
    }

    @Test
    fun flushAfterShutdownWaitsForTheEditsAlreadyAccepted() {
        val release = java.util.concurrent.CountDownLatch(1)
        val applied = CopyOnWriteArrayList<SheetEdit>()
        val queue = InkPersistenceQueue(
            sink = { edit -> release.await(); applied += edit },
            onFailure = { fail("unexpected failure: $it") }
        )
        val edit = SheetEdit.AddStrokes(listOf(stroke("a", 0)))

        queue.enqueue(edit)
        queue.shutdown()

        assertFalse(queue.flushAndWait(timeoutMillis = 50))

        release.countDown()

        assertTrue(queue.flushAndWait(timeoutMillis = 2_000))
        assertEquals(listOf<SheetEdit>(edit), applied)
    }

    @Test
    fun anExecutorShutDownBehindTheQueuesBackIsRefusedWithoutThrowing() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val queue = InkPersistenceQueue(sink = { }, onFailure = { fail("unexpected failure: $it") }, executor = executor)

        executor.shutdown()

        assertFalse(queue.enqueue(SheetEdit.AddStrokes(listOf(stroke("a", 0)))))
        assertTrue(queue.flushAndWait(timeoutMillis = 2_000))
    }

    @Test
    fun freshQueueHasNotFailed() {
        val queue = InkPersistenceQueue(sink = {}, onFailure = {})

        assertFalse(queue.hasFailed)
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
