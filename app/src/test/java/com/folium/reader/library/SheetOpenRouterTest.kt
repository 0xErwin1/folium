package com.folium.reader.library

import com.folium.reader.core.ink.OpenSheet
import com.folium.reader.core.ink.Sheet
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.ink.SheetStore
import com.folium.reader.core.ink.SheetTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SheetOpenRouterTest {
    private val discarded = mutableListOf<OpenSheet>()


    @get:Rule val temp = TemporaryFolder()

    private fun sheet(id: String) = Sheet(
        id = SheetId(id),
        title = "Untitled sheet",
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        template = SheetTemplate.BLANK,
        anchor = null
    )

    private fun router(store: SheetStore): SheetOpenRouter = SheetOpenRouter(
        openSheet = { id, callback -> callback(runCatching { store.open(id) }.getOrNull()) },
        createSheet = { sheet, callback -> callback(runCatching { store.create(sheet) }.getOrNull()) },
        discard = { discarded += it; it.close() }
    )

    @Test fun `create hands back the newly opened sheet`() {
        val store = SheetStore(temp.newFolder())
        val router = router(store)
        val opened = mutableListOf<OpenSheet>()
        router.rebind(onOpened = { opened += it }, onFailed = { throw AssertionError("did not expect a failure") })

        router.create(sheet("s1"))

        assertEquals(listOf(SheetId("s1")), opened.map { it.sheet.id })
        assertNull(router.openingId)
    }

    @Test fun `a second create for the id already opening is dropped`() {
        val callbacks = mutableMapOf<SheetId, (OpenSheet?) -> Unit>()
        val router = SheetOpenRouter(
            openSheet = { _, _ -> throw AssertionError("open should not be called") },
            createSheet = { sheet, callback -> callbacks[sheet.id] = callback },
            discard = { discarded += it; it.close() }
        )
        val opened = mutableListOf<SheetId>()
        router.rebind(onOpened = { opened += it.sheet.id }, onFailed = {})

        router.create(sheet("s1"))
        router.create(sheet("s1"))
        assertEquals(SheetId("s1"), router.openingId)

        callbacks.getValue(SheetId("s1"))(fakeOpenSheet(temp.newFolder(), "s1"))

        assertEquals(listOf(SheetId("s1")), opened)
        assertNull(router.openingId)
    }

    @Test fun `a store failure reports failure and clears the in-flight guard`() {
        val store = SheetStore(temp.newFolder())
        val router = router(store)
        var failed = false
        router.rebind(onOpened = { throw AssertionError("did not expect success") }, onFailed = { failed = true })

        // A sheet not stored anywhere fails with SheetNotFoundException.
        router.open(SheetId("missing"))

        assertTrue(failed)
        assertNull(router.openingId)
    }

    @Test fun `cancel makes a late result land silently`() {
        val callbacks = mutableMapOf<SheetId, (OpenSheet?) -> Unit>()
        val router = SheetOpenRouter(
            openSheet = { _, _ -> throw AssertionError("open should not be called") },
            createSheet = { sheet, callback -> callbacks[sheet.id] = callback },
            discard = { discarded += it; it.close() }
        )
        var opened = 0
        router.rebind(onOpened = { opened += 1 }, onFailed = {})

        router.create(sheet("s1"))
        router.cancel()
        callbacks.getValue(SheetId("s1"))(fakeOpenSheet(temp.newFolder(), "s1"))

        assertEquals(0, opened)
        assertNull(router.openingId)
        assertEquals(1, discarded.size)
    }

    private fun fakeOpenSheet(root: File, id: String): OpenSheet = SheetStore(root).create(sheet(id))
}
