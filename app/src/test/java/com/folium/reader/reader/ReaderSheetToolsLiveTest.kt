package com.folium.reader.reader

import com.folium.reader.core.ink.OpenSheet
import com.folium.reader.core.ink.Sheet
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.ink.SheetStore
import com.folium.reader.core.ink.SheetTemplate
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** When the reader's rail may act on a sheet: only while the current unit's own sheet is open live. */
class ReaderSheetToolsLiveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sheetA = SheetId("a")
    private val sheetB = SheetId("b")

    private lateinit var openA: OpenSheet

    @Before
    fun setUp() {
        val store = SheetStore(tempFolder.newFolder("sheets"))
        openA = store.create(sheet(sheetA))
    }

    @After
    fun tearDown() {
        openA.close()
    }

    @Test fun `the rail is live while the current unit's sheet is open`() {
        assertTrue(sheetToolsLive(SheetLeaseState.Open(sheetA, openA), currentSheet = sheetA))
    }

    @Test fun `the rail is not live while the current unit's sheet is still opening`() {
        assertFalse(sheetToolsLive(SheetLeaseState.Opening(sheetA), currentSheet = sheetA))
    }

    @Test fun `the rail is not live while the current unit's sheet is open elsewhere or failed to open`() {
        assertFalse(sheetToolsLive(SheetLeaseState.Unavailable(sheetA, openElsewhere = true), currentSheet = sheetA))
        assertFalse(sheetToolsLive(SheetLeaseState.Unavailable(sheetA, openElsewhere = false), currentSheet = sheetA))
    }

    @Test fun `the rail is not live while the open sheet belongs to another unit`() {
        assertFalse(sheetToolsLive(SheetLeaseState.Open(sheetA, openA), currentSheet = sheetB))
        assertFalse(sheetToolsLive(SheetLeaseState.Open(sheetA, openA), currentSheet = null))
    }

    @Test fun `the rail is not live while no sheet is wanted`() {
        assertFalse(sheetToolsLive(SheetLeaseState.Idle, currentSheet = sheetA))
    }

    private fun sheet(id: SheetId) = Sheet(
        id = id,
        title = id.value,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        template = SheetTemplate.BLANK,
        anchor = null
    )
}
