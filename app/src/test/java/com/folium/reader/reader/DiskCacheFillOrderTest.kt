package com.folium.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

private const val RADIUS = 30

class DiskCacheFillOrderTest {

    @Test fun offersTheCurrentPageFirstWhenItIsMissing() {
        val candidate = nextDiskCacheFillPage(10, 200, RADIUS) { false }
        assertEquals(10, candidate?.pageIndex)
        assertTrue(candidate!!.isWithinWindow)
    }

    @Test fun alternatesForwardAndBackwardNearestFirst() {
        val present = mutableSetOf(10)
        val order = mutableListOf<Int>()
        repeat(6) {
            val candidate = nextDiskCacheFillPage(10, 200, RADIUS) { it in present } ?: return@repeat
            order += candidate.pageIndex
            present += candidate.pageIndex
        }

        assertEquals(listOf(11, 9, 12, 8, 13, 7), order)
    }

    @Test fun ordersTheNearWindowBeforeTheRestOfTheDocument() {
        // Only a page far outside the window and one just inside it are missing: the near one wins.
        val far = 10 + RADIUS + 50
        val near = 10 + 5
        val candidate = nextDiskCacheFillPage(10, 400, RADIUS) { it != far && it != near }

        assertEquals(near, candidate?.pageIndex)
        assertTrue(candidate!!.isWithinWindow)
    }

    @Test fun continuesPastTheWindowStillNearestFirstOnceItIsSatisfied() {
        val present = (0 until 200).filter { kotlin.math.abs(it - 10) <= RADIUS }.toMutableSet()
        val candidate = nextDiskCacheFillPage(10, 200, RADIUS) { it in present }

        assertEquals(10 + RADIUS + 1, candidate?.pageIndex)
        assertFalse(candidate!!.isWithinWindow)
    }

    @Test fun clampsAtTheStartOfTheDocument() {
        val present = mutableSetOf<Int>()
        val order = mutableListOf<Int>()
        repeat(5) {
            val candidate = nextDiskCacheFillPage(1, 200, RADIUS) { it in present } ?: return@repeat
            order += candidate.pageIndex
            present += candidate.pageIndex
        }

        // Page -1 does not exist, so every step after the second is forward-only.
        assertEquals(listOf(1, 2, 0, 3, 4), order)
    }

    @Test fun clampsAtTheEndOfTheDocument() {
        val present = mutableSetOf<Int>()
        val order = mutableListOf<Int>()
        repeat(5) {
            val candidate = nextDiskCacheFillPage(8, 10, RADIUS) { it in present } ?: return@repeat
            order += candidate.pageIndex
            present += candidate.pageIndex
        }

        assertEquals(listOf(8, 9, 7, 6, 5), order)
    }

    @Test fun skipsPagesAlreadyPresent() {
        assertEquals(11, nextDiskCacheFillPage(10, 200, RADIUS) { it == 10 || it == 9 }?.pageIndex)
    }

    @Test fun followsTheCurrentPageWhenItChanges() {
        val fromTen = nextDiskCacheFillPage(10, 200, RADIUS) { it != 50 }
        val fromFifty = nextDiskCacheFillPage(50, 200, RADIUS) { it != 50 }

        assertEquals(50, fromTen?.pageIndex)
        assertEquals(50, fromFifty?.pageIndex)
    }

    @Test fun reportsWhetherTheChosenPageFallsInsideTheWindowRadius() {
        val justInside = nextDiskCacheFillPage(10, 200, RADIUS) { it != 10 + RADIUS }
        val justOutside = nextDiskCacheFillPage(10, 200, RADIUS) { it != 10 + RADIUS + 1 }

        assertTrue(justInside!!.isWithinWindow)
        assertFalse(justOutside!!.isWithinWindow)
    }

    @Test fun terminatesWhenEveryPageIsPresent() {
        assertNull(nextDiskCacheFillPage(10, 200, RADIUS) { true })
    }

    @Test fun terminatesForAnEmptyDocument() {
        assertNull(nextDiskCacheFillPage(0, 0, RADIUS) { false })
    }
}
