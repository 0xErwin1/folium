package com.folium.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scheduled call, capturing its delay so a test can assert timing without a real clock. */
private data class Scheduled(val delayMillis: Long, val action: () -> Unit, var cancelled: Boolean = false)

/** Captures every [TypographyEditScheduler] scheduling call and lets a test fire or cancel each one by hand. */
private class FakePostDelayed {
    val scheduled = mutableListOf<Scheduled>()

    val seam: (Long, () -> Unit) -> (() -> Unit) = { delayMillis, action ->
        val entry = Scheduled(delayMillis, action)
        scheduled += entry
        { entry.cancelled = true }
    }

    /** Runs every pending, non-cancelled call scheduled at exactly [delayMillis], in submission order. */
    fun fire(delayMillis: Long) {
        scheduled.filter { it.delayMillis == delayMillis && !it.cancelled }.forEach { it.action() }
    }

    fun pendingCount(delayMillis: Long): Int = scheduled.count { it.delayMillis == delayMillis && !it.cancelled }
}

class TypographyEditSchedulerTest {

    private fun scheduler(
        fake: FakePostDelayed,
        measuredCostMillis: Long = 0L,
        onRequest: () -> Unit = {},
        onIndicatorChanged: (Boolean) -> Unit = {}
    ) = TypographyEditScheduler(
        measuredCostMillis = measuredCostMillis,
        postDelayed = fake.seam,
        onRequest = onRequest,
        onIndicatorChanged = onIndicatorChanged
    )

    @Test fun `three edits inside the debounce window produce one request`() {
        val fake = FakePostDelayed()
        var requests = 0
        val scheduler = scheduler(fake, onRequest = { requests++ })

        scheduler.edit()
        scheduler.edit()
        scheduler.edit()

        assertEquals(0, requests)
        assertEquals(1, fake.pendingCount(TypographyEditScheduler.DEBOUNCE_MILLIS))
        fake.fire(TypographyEditScheduler.DEBOUNCE_MILLIS)

        assertEquals(1, requests)
    }

    @Test fun `the indicator appears only past the indicator threshold`() {
        val fake = FakePostDelayed()
        val indicatorStates = mutableListOf<Boolean>()
        val scheduler = scheduler(fake, onIndicatorChanged = { indicatorStates += it })

        scheduler.edit()
        fake.fire(TypographyEditScheduler.DEBOUNCE_MILLIS)
        assertTrue(indicatorStates.isEmpty())

        fake.fire(TypographyEditScheduler.INDICATOR_MILLIS)
        assertEquals(listOf(true), indicatorStates)
    }

    @Test fun `a repagination finishing before the indicator threshold never shows one`() {
        val fake = FakePostDelayed()
        val indicatorStates = mutableListOf<Boolean>()
        val scheduler = scheduler(fake, onIndicatorChanged = { indicatorStates += it })

        scheduler.edit()
        fake.fire(TypographyEditScheduler.DEBOUNCE_MILLIS)
        scheduler.completed(elapsedMillis = 40L)

        fake.fire(TypographyEditScheduler.INDICATOR_MILLIS)
        assertTrue(indicatorStates.isEmpty())
    }

    @Test fun `the cutoff engages from a recorded cost above the cutoff`() {
        val fake = FakePostDelayed()
        var requests = 0
        val scheduler = scheduler(
            fake,
            measuredCostMillis = TypographyEditScheduler.MEASURED_COST_CUTOFF_MILLIS + 1,
            onRequest = { requests++ }
        )

        assertFalse(scheduler.appliesLive)
        scheduler.edit()
        fake.fire(TypographyEditScheduler.DEBOUNCE_MILLIS)

        assertEquals(0, requests)
    }

    @Test fun `the cutoff lifts once a completed repagination falls below it`() {
        val fake = FakePostDelayed()
        var requests = 0
        val scheduler = scheduler(
            fake,
            measuredCostMillis = TypographyEditScheduler.MEASURED_COST_CUTOFF_MILLIS + 1,
            onRequest = { requests++ }
        )

        scheduler.edit()
        scheduler.flush()
        assertEquals(1, requests)
        scheduler.completed(elapsedMillis = TypographyEditScheduler.MEASURED_COST_CUTOFF_MILLIS - 1)
        assertTrue(scheduler.appliesLive)

        scheduler.edit()
        fake.fire(TypographyEditScheduler.DEBOUNCE_MILLIS)
        assertEquals(2, requests)
    }

    @Test fun `flush fires a pending debounced edit immediately`() {
        val fake = FakePostDelayed()
        var requests = 0
        val scheduler = scheduler(fake, onRequest = { requests++ })

        scheduler.edit()
        scheduler.flush()

        assertEquals(1, requests)
        assertEquals(0, fake.pendingCount(TypographyEditScheduler.DEBOUNCE_MILLIS))
    }

    @Test fun `flush is a no-op when nothing is pending`() {
        val fake = FakePostDelayed()
        var requests = 0
        val scheduler = scheduler(fake, onRequest = { requests++ })

        scheduler.flush()

        assertEquals(0, requests)
    }

    @Test fun `an edit made while the cutoff holds is still flushed once on dismissal`() {
        val fake = FakePostDelayed()
        var requests = 0
        val scheduler = scheduler(
            fake,
            measuredCostMillis = TypographyEditScheduler.MEASURED_COST_CUTOFF_MILLIS + 1,
            onRequest = { requests++ }
        )

        scheduler.edit()
        assertEquals(0, requests)

        scheduler.flush()
        assertEquals(1, requests)
    }
}
