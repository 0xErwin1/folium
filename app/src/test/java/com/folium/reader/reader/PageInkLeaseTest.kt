package com.folium.reader.reader

import com.folium.reader.core.ink.OpenPageInk
import com.folium.reader.core.ink.PageInkStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

class PageInkLeaseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val work = QueuedExecutor()
    private val main = QueuedExecutor()
    private val events = mutableListOf<String>()
    private val changed = mutableListOf<Int>()

    private lateinit var store: PageInkStore
    private lateinit var lease: PageInkLease

    @Before
    fun setUp() {
        store = PageInkStore(tempFolder.newFolder("page-ink"))

        lease = PageInkLease(
            open = { page ->
                events += "open $page"
                store.open(page)
            },
            close = { ink ->
                events += "close ${ink.pageIndex}"
                ink.close()
            },
            work = work,
            main = main,
            onState = {},
            onPageInkChanged = { changed += it }
        )
    }

    @Test fun `a two-page spread opens both pages at once`() {
        lease.want(setOf(4, 5))
        settle()

        assertEquals(listOf("open 4", "open 5"), events)
        assertTrue(lease.states[4] is PageInkState.Live)
        assertTrue(lease.states[5] is PageInkState.Live)
    }

    @Test fun `more than two pages at once is refused`() {
        val refused = runCatching { lease.want(setOf(1, 2, 3)) }

        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `moving to other pages closes the pages no longer wanted before opening the new ones`() {
        lease.want(setOf(4, 5))
        settle()
        events.clear()

        lease.want(setOf(6, 7))
        settle()

        assertEquals(listOf("close 4", "close 5", "open 6", "open 7"), events)
        assertEquals(setOf(6, 7), lease.states.keys)
        assertReopenable(4)
        assertReopenable(5)
    }

    @Test fun `a page kept across a move stays open while the other one is swapped`() {
        lease.want(setOf(4, 5))
        settle()
        val four = live(4)
        events.clear()

        lease.want(setOf(4, 3))
        settle()

        assertEquals(listOf("close 5", "open 3"), events)
        assertSame(four, live(4))
    }

    @Test fun `an attached page is closed only by its release, which reports the page changed`() {
        lease.want(setOf(4))
        settle()
        val four = live(4)
        lease.attach(four)
        events.clear()

        lease.want(setOf(6))
        settle()

        assertEquals(listOf("open 6"), events)
        assertEquals(emptyList<Int>(), changed)

        lease.release(four)
        settle()

        assertEquals(listOf("open 6", "close 4"), events)
        assertEquals(listOf(4), changed)
        assertReopenable(4)
    }

    @Test fun `a page released while still wanted is closed before it is reopened`() {
        lease.want(setOf(4))
        settle()
        val four = live(4)
        lease.attach(four)
        events.clear()

        lease.release(four)

        assertEquals(PageInkState.Opening(4), lease.states[4])

        settle()

        assertEquals(listOf("close 4", "open 4"), events)
        assertTrue(lease.states[4] is PageInkState.Live)
    }

    @Test fun `an open that completes after its page is no longer wanted is closed and discarded`() {
        lease.want(setOf(4))
        lease.want(setOf(6))
        settle()

        assertEquals(listOf("open 4", "open 6", "close 4"), events)
        assertEquals(setOf(6), lease.states.keys)
        assertReopenable(4)
    }

    @Test fun `a page wanted again while its first open is in flight opens once`() {
        lease.want(setOf(4))
        lease.want(emptySet())
        lease.want(setOf(4))
        settle()

        assertEquals(listOf("open 4"), events)
        assertTrue(lease.states[4] is PageInkState.Live)
    }

    @Test fun `a release followed by dispose in the same teardown opens nothing more`() {
        lease.want(setOf(4))
        settle()
        val four = live(4)
        lease.attach(four)
        events.clear()

        lease.release(four)
        lease.dispose()
        settle()

        assertEquals(listOf("close 4"), events)
        assertReopenable(4)
    }

    @Test fun `a dispose followed by release closes the attached page once`() {
        lease.want(setOf(4, 5))
        settle()
        val four = live(4)
        lease.attach(four)
        events.clear()

        lease.dispose()
        settle()

        assertEquals(listOf("close 5"), events)

        lease.release(four)
        lease.release(four)
        settle()

        assertEquals(listOf("close 5", "close 4"), events)
        assertReopenable(4)
        assertReopenable(5)
    }

    @Test fun `an open that completes after dispose is closed`() {
        lease.want(setOf(4))
        lease.dispose()
        settle()

        assertEquals(listOf("open 4", "close 4"), events)
        assertReopenable(4)
    }

    @Test fun `a page open elsewhere is unavailable and retried when wanted again`() {
        val elsewhere = store.open(4)

        lease.want(setOf(4))
        settle()

        assertEquals(PageInkState.Unavailable(4, openElsewhere = true), lease.states[4])

        elsewhere.close()
        lease.want(setOf(4))
        settle()

        assertTrue(lease.states[4] is PageInkState.Live)
    }

    private fun live(page: Int): OpenPageInk = (lease.states.getValue(page) as PageInkState.Live).ink

    private fun settle() {
        while (work.runOne() || main.runOne()) Unit
    }

    private fun assertReopenable(page: Int) {
        store.open(page).close()
    }

    private class QueuedExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queue.addLast(command)
        }

        fun runOne(): Boolean {
            val next = queue.removeFirstOrNull() ?: return false
            next.run()
            return true
        }
    }
}
