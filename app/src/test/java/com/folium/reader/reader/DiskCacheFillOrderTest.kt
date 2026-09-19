package com.folium.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val RADIUS = 30

class DiskCacheFillOrderTest {

    @Test fun offersTheCurrentPageFirstWhenItIsMissing() {
        assertEquals(10, nextDiskCacheFillPage(10, 200, RADIUS) { false })
    }

    @Test fun alternatesForwardAndBackwardNearestFirst() {
        val present = mutableSetOf(10)
        val order = mutableListOf<Int>()
        repeat(6) {
            val next = nextDiskCacheFillPage(10, 200, RADIUS) { it in present } ?: return@repeat
            order += next
            present += next
        }

        assertEquals(listOf(11, 9, 12, 8, 13, 7), order)
    }

    @Test fun ordersTheNearWindowBeforeTheRestOfTheDocument() {
        // Only a page far outside the window and one just inside it are missing: the near one wins.
        val far = 10 + RADIUS + 50
        val near = 10 + 5
        val next = nextDiskCacheFillPage(10, 400, RADIUS) { it != far && it != near }

        assertEquals(near, next)
    }

    @Test fun continuesPastTheWindowStillNearestFirstOnceItIsSatisfied() {
        val present = (0 until 200).filter { kotlin.math.abs(it - 10) <= RADIUS }.toMutableSet()
        val next = nextDiskCacheFillPage(10, 200, RADIUS) { it in present }

        assertEquals(10 + RADIUS + 1, next)
    }

    @Test fun clampsAtTheStartOfTheDocument() {
        val present = mutableSetOf<Int>()
        val order = mutableListOf<Int>()
        repeat(5) {
            val next = nextDiskCacheFillPage(1, 200, RADIUS) { it in present } ?: return@repeat
            order += next
            present += next
        }

        // Page -1 does not exist, so every step after the second is forward-only.
        assertEquals(listOf(1, 2, 0, 3, 4), order)
    }

    @Test fun clampsAtTheEndOfTheDocument() {
        val present = mutableSetOf<Int>()
        val order = mutableListOf<Int>()
        repeat(5) {
            val next = nextDiskCacheFillPage(8, 10, RADIUS) { it in present } ?: return@repeat
            order += next
            present += next
        }

        assertEquals(listOf(8, 9, 7, 6, 5), order)
    }

    @Test fun skipsPagesAlreadyPresent() {
        assertEquals(11, nextDiskCacheFillPage(10, 200, RADIUS) { it == 10 || it == 9 })
    }

    @Test fun followsTheCurrentPageWhenItChanges() {
        val fromTen = nextDiskCacheFillPage(10, 200, RADIUS) { it != 50 }
        val fromFifty = nextDiskCacheFillPage(50, 200, RADIUS) { it != 50 }

        assertEquals(50, fromTen)
        assertEquals(50, fromFifty)
    }

    @Test fun terminatesWhenEveryPageIsPresent() {
        assertNull(nextDiskCacheFillPage(10, 200, RADIUS) { true })
    }

    @Test fun terminatesForAnEmptyDocument() {
        assertNull(nextDiskCacheFillPage(0, 0, RADIUS) { false })
    }
}
