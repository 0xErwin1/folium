package com.folium.reader.reader

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.ReflowFontFamily
import com.folium.reader.core.pdf.ReflowTextAlign
import com.folium.reader.core.pdf.TypographyPreset
import com.folium.reader.library.LibraryPaths
import com.folium.reader.library.TypographyCostStore
import com.folium.reader.library.TypographyPresetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

private class TypographyDirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

private val custom = TypographyPreset(
    fontFamily = ReflowFontFamily.SERIF,
    fontSizePoints = 24f,
    lineHeight = 1.4f,
    marginEm = 1f,
    textAlign = ReflowTextAlign.JUSTIFY,
    paragraphIndentEm = 1.2f,
    pageColors = false
)

/**
 * Exercises [TypographySheetController] wiring: the scope actions against the stores, and the
 * dismissal flush against both the store and a pending re-pagination.
 */
class TypographySheetControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val bookId = BookId("book-1")
    private val paths get() = LibraryPaths(tempFolder.root)
    private fun presetStore() = TypographyPresetStore(paths)
    private fun costStore() = TypographyCostStore(paths)

    private fun controller(
        presetStore: TypographyPresetStore = presetStore(),
        costStore: TypographyCostStore = costStore(),
        applyPreset: (TypographyPreset, (RepaginationResult) -> Unit) -> Unit = { _, onResult ->
            onResult(RepaginationResult.Repaginated(0, 1, null, resolved = true, elapsedMillis = 1L))
        },
        onAbandoned: () -> Unit = {},
        states: MutableList<TypographySheetPhase> = mutableListOf()
    ): TypographySheetController {
        presetStore.writeOverride(bookId, custom)
        return TypographySheetController(
            bookId = bookId,
            presetStore = presetStore,
            costStore = costStore,
            worker = TypographyDirectExecutor(),
            mainPost = { it() },
            applyPreset = applyPreset,
            onState = { states += it },
            onAbandoned = onAbandoned,
            // Never fired: every test dismisses through `flush`, which bypasses the debounce timer
            // entirely rather than waiting on it — see TypographyEditSchedulerTest for timing itself.
            postDelayed = { _, _ -> {} }
        )
    }

    @Test fun `dismissal flushes a pending edit and writes the override`() {
        val presetStore = presetStore()
        var repaginateCalls = 0
        val controller = controller(
            presetStore = presetStore,
            applyPreset = { _, onResult ->
                repaginateCalls++
                onResult(RepaginationResult.Repaginated(0, 1, null, resolved = true, elapsedMillis = 1L))
            }
        )

        controller.start()
        val edited = custom.copy(fontSizePoints = 20f)
        controller.edit(edited)
        controller.dismiss()

        assertEquals(1, repaginateCalls)
        assertEquals(edited, presetStore.readOverride(bookId))
    }

    @Test fun `use for all books writes global and keeps the override`() {
        val presetStore = presetStore()
        val controller = controller(presetStore = presetStore)

        controller.start()
        val edited = custom.copy(fontSizePoints = 26f)
        controller.edit(edited)
        controller.useForAllBooks()

        assertEquals(edited, presetStore.readGlobal())
        assertEquals(custom, presetStore.readOverride(bookId))
    }

    @Test fun `reset to global clears the override only`() {
        val presetStore = presetStore()
        presetStore.writeGlobal(TypographyPreset.DEFAULT)
        val controller = controller(presetStore = presetStore)

        controller.start()
        controller.resetToGlobal()

        assertNull(presetStore.readOverride(bookId))
        assertEquals(TypographyPreset.DEFAULT, presetStore.readGlobal())
    }

    @Test fun `an abandoned repagination is reported rather than left silent`() {
        var abandoned = false
        val controller = controller(
            applyPreset = { _, onResult -> onResult(RepaginationResult.Abandoned) },
            onAbandoned = { abandoned = true }
        )

        controller.start()
        controller.edit(custom.copy(fontSizePoints = 20f))
        controller.dismiss()

        assertTrue(abandoned)
    }

    @Test fun `a completed repagination records its cost for the cutoff decision`() {
        val costStore = costStore()
        val controller = controller(
            costStore = costStore,
            applyPreset = { _, onResult ->
                onResult(RepaginationResult.Repaginated(0, 1, null, resolved = true, elapsedMillis = 2000L))
            }
        )

        controller.start()
        controller.edit(custom.copy(fontSizePoints = 20f))
        controller.dismiss()

        assertEquals(2000L, costStore.read(bookId))
    }
}
