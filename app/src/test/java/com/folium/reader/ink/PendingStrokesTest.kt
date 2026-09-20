package com.folium.reader.ink

import com.folium.reader.core.ink.InkTip
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingStrokesTest {

    private val thinBlack = PendingStrokeMeta(InkTip.BALLPOINT, 0xFF000000.toInt(), 0.002f)
    private val wideRed = PendingStrokeMeta(InkTip.FOUNTAIN, 0xFFB3261E.toInt(), 0.007f)
    private val fallback = PendingStrokeMeta(InkTip.PENCIL, 0xFF1F4E9A.toInt(), 0.004f)

    @Test
    fun aLateDeliveryKeepsTheSettingsItsStrokeWasStartedWith() {
        val pending = PendingStrokes<String>()

        pending.register("first", thinBlack)
        pending.register("second", wideRed)

        assertEquals(thinBlack, pending.resolve("first") { fallback })
        assertEquals(wideRed, pending.resolve("second") { fallback })
    }

    @Test
    fun anUnknownStrokeIsResolvedThroughTheFallbackInsteadOfBeingDropped() {
        val pending = PendingStrokes<String>()

        assertEquals(fallback, pending.resolve("never-registered") { fallback })
    }

    @Test
    fun aDiscardedStrokeNoLongerAnswersWithItsOldSettings() {
        val pending = PendingStrokes<String>()

        pending.register("cancelled", thinBlack)
        pending.discard("cancelled")

        assertEquals(fallback, pending.resolve("cancelled") { fallback })
    }

    @Test
    fun resolvingForgetsTheStroke() {
        val pending = PendingStrokes<String>()

        pending.register("only", thinBlack)
        pending.resolve("only") { fallback }

        assertEquals(0, pending.size)
    }
}
