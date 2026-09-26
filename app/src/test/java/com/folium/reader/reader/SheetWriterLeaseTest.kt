package com.folium.reader.reader

import com.folium.reader.core.ink.OpenSheet
import com.folium.reader.core.ink.Sheet
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.ink.SheetStore
import com.folium.reader.core.ink.SheetTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

class SheetWriterLeaseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sheetA = SheetId("a")
    private val sheetB = SheetId("b")

    private val work = QueuedExecutor()
    private val main = QueuedExecutor()
    private val events = mutableListOf<String>()
    private val states = mutableListOf<SheetLeaseState>()

    private lateinit var store: SheetStore
    private lateinit var lease: SheetWriterLease

    @Before
    fun setUp() {
        store = SheetStore(tempFolder.newFolder("sheets"))
        store.create(sheet(sheetA)).close()
        store.create(sheet(sheetB)).close()

        lease = SheetWriterLease(
            open = { id ->
                events += "open ${id.value}"
                store.open(id)
            },
            writeThumbnail = { open -> events += "thumbnail ${open.sheet.id.value}" },
            close = { open ->
                events += "close ${open.sheet.id.value}"
                open.close()
            },
            work = work,
            main = main,
            onState = { states += it }
        )
    }

    @Test fun `moving on before an open completes closes and discards the late sheet, then opens the next`() {
        lease.acquire(sheetA)
        lease.acquire(sheetB)

        assertEquals(SheetLeaseState.Opening(sheetB), lease.state)

        settle()

        assertEquals(listOf("open a", "close a", "open b"), events)
        assertEquals(sheetB, (lease.state as SheetLeaseState.Open).id)
        assertReopenable(sheetA)
    }

    @Test fun `a sheet on screen is released only once its pane lets go, before the next one opens`() {
        lease.acquire(sheetA)
        settle()
        val openA = (lease.state as SheetLeaseState.Open).sheet
        lease.attach(openA)
        events.clear()

        lease.acquire(sheetB)
        settle()

        assertEquals(emptyList<String>(), events)
        assertEquals(SheetLeaseState.Opening(sheetB), lease.state)

        lease.release(openA)
        settle()

        assertEquals(listOf("thumbnail a", "close a", "open b"), events)
        assertEquals(sheetB, (lease.state as SheetLeaseState.Open).id)
    }

    @Test fun `a sheet that never reached the screen is released as soon as another one is wanted`() {
        lease.acquire(sheetA)
        settle()
        events.clear()

        lease.acquire(sheetB)
        settle()

        assertEquals(listOf("thumbnail a", "close a", "open b"), events)
        assertEquals(sheetB, (lease.state as SheetLeaseState.Open).id)
    }

    @Test fun `acquiring the same sheet twice opens it once`() {
        lease.acquire(sheetA)
        lease.acquire(sheetA)
        settle()
        val first = (lease.state as SheetLeaseState.Open).sheet

        lease.acquire(sheetA)
        settle()

        assertEquals(listOf("open a"), events)
        assertSame(first, (lease.state as SheetLeaseState.Open).sheet)
    }

    @Test fun `a sheet open elsewhere reports unavailable and opens on the next attempt once it is free`() {
        val elsewhere = store.open(sheetA)

        lease.acquire(sheetA)
        settle()

        assertEquals(SheetLeaseState.Unavailable(sheetA, openElsewhere = true), lease.state)

        elsewhere.close()
        lease.acquire(sheetA)
        settle()

        assertEquals(sheetA, (lease.state as SheetLeaseState.Open).id)
    }

    @Test fun `wanting no sheet releases one that never reached the screen`() {
        lease.acquire(sheetA)
        settle()
        events.clear()

        lease.acquire(null)
        settle()

        assertEquals(SheetLeaseState.Idle, lease.state)
        assertEquals(listOf("thumbnail a", "close a"), events)
        assertReopenable(sheetA)
    }

    @Test fun `disposing leaves a sheet on screen to its pane, which then writes its thumbnail and closes it`() {
        lease.acquire(sheetA)
        settle()
        val openA = (lease.state as SheetLeaseState.Open).sheet
        lease.attach(openA)
        events.clear()

        lease.dispose()
        settle()

        assertEquals(emptyList<String>(), events)

        lease.release(openA)
        settle()

        assertEquals(listOf("thumbnail a", "close a"), events)
        assertReopenable(sheetA)
    }

    @Test fun `a pane released just before the reader is disposed does not reopen its sheet`() {
        lease.acquire(sheetA)
        settle()
        val openA = (lease.state as SheetLeaseState.Open).sheet
        lease.attach(openA)
        events.clear()

        lease.release(openA)
        lease.dispose()
        settle()

        assertEquals(listOf("thumbnail a", "close a"), events)
        assertReopenable(sheetA)
    }

    @Test fun `a pane released while its sheet is still wanted reopens that sheet`() {
        lease.acquire(sheetA)
        settle()
        val openA = (lease.state as SheetLeaseState.Open).sheet
        lease.attach(openA)
        events.clear()

        lease.release(openA)

        assertEquals(SheetLeaseState.Opening(sheetA), lease.state)

        settle()

        assertEquals(listOf("thumbnail a", "close a", "open a"), events)
        assertEquals(sheetA, (lease.state as SheetLeaseState.Open).id)
    }

    @Test fun `disposing writes the thumbnail of a sheet that never reached the screen, then closes it`() {
        lease.acquire(sheetA)
        settle()
        events.clear()

        lease.dispose()
        settle()

        assertEquals(listOf("thumbnail a", "close a"), events)
        assertReopenable(sheetA)
    }

    @Test fun `an open that completes after disposal is closed without a thumbnail`() {
        lease.acquire(sheetA)
        lease.dispose()
        settle()

        assertEquals(listOf("open a", "close a"), events)
        assertReopenable(sheetA)
    }

    @Test fun `releasing the same sheet twice closes it once`() {
        lease.acquire(sheetA)
        settle()
        val openA = (lease.state as SheetLeaseState.Open).sheet
        lease.attach(openA)
        events.clear()

        lease.acquire(null)
        lease.release(openA)
        lease.release(openA)
        settle()

        assertEquals(listOf("thumbnail a", "close a"), events)
    }

    @Test fun `state changes are published in order`() {
        lease.acquire(sheetA)
        settle()

        assertEquals(SheetLeaseState.Opening(sheetA), states.first())
        assertTrue(states.last() is SheetLeaseState.Open)
    }

    private fun settle() {
        while (work.runOne() || main.runOne()) Unit
    }

    private fun assertReopenable(id: SheetId) {
        val reopened: OpenSheet = store.open(id)
        reopened.close()
    }

    private fun sheet(id: SheetId) = Sheet(
        id = id,
        title = id.value,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        template = SheetTemplate.BLANK,
        anchor = null
    )

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
