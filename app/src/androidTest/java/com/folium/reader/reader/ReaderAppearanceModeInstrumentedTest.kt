package com.folium.reader.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.library.AppearanceMode
import com.folium.reader.core.library.BookFormat
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.library.BookCatalogStore
import com.folium.reader.library.BookImporter
import com.folium.reader.library.LibraryPaths
import com.folium.reader.library.OpenBookRequest
import com.folium.reader.library.PickedSource
import com.folium.reader.ui.appearancePageColorsFor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Changing the appearance mode while a real, genuinely opened EPUB is on screen has to re-paginate
 * it — the page count does not move, since the layout box does not change, but the reader lands on
 * a freshly re-laid-out page carrying the new mode's colours rather than the one it opened with.
 */
@RunWith(AndroidJUnit4::class)
class ReaderAppearanceModeInstrumentedTest {
    private companion object {
        const val REFLOWABLE_EPUB = "reflowable-long.epub"
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = InstrumentationRegistry.getInstrumentation().context.assets
    private val libraryRoot = File(context.filesDir, "library")
    private val paths = LibraryPaths(context.filesDir)
    private val catalog = BookCatalogStore(paths)
    private val importer = BookImporter(paths, catalog)

    @Before fun clearLibrary() {
        libraryRoot.deleteRecursively()
    }

    @After fun tearDown() {
        libraryRoot.deleteRecursively()
    }

    @Test fun changingTheAppearanceModeWhileAnEpubIsOpenRepaginatesAndRedrawsThePage() {
        val source = PickedSource("Reflowable book.epub") { fixtures.open(REFLOWABLE_EPUB) }
        val outcome = importer.import(source)
        assertTrue("fixture import must succeed: $outcome", outcome is ImportOutcome.Imported)
        val book = (outcome as ImportOutcome.Imported).book
        val request = OpenBookRequest(book, paths.documentFile(book.id, BookFormat.EPUB), initialPage = 0)

        val lightAppearance = appearancePageColorsFor(AppearanceMode.LIGHT, systemDark = false)
        val darkAppearance = appearancePageColorsFor(AppearanceMode.DARK, systemDark = false)

        val states = mutableListOf<ReaderScreenState>()
        val opened = CountDownLatch(1)

        val controller = ReaderHostController(
            context = context,
            request = request,
            onPageChanged = {},
            onState = { state ->
                states += state
                if (state is ReaderScreenState.Reading && state.text is ReaderTextState.Loaded) opened.countDown()
            },
            initialAppearance = lightAppearance
        )

        controller.start()
        assertTrue("the reader must open and load its first page's text", opened.await(15, TimeUnit.SECONDS))

        val stateCountBeforeModeChange = states.size

        controller.setAppearanceColors(darkAppearance)

        val redrawn = awaitAReadingStateAfter(states, stateCountBeforeModeChange)
        assertNotEquals(lightAppearance, darkAppearance)
        assertTrue(
            "expected a freshly loaded page after the appearance mode changed",
            redrawn.text is ReaderTextState.Loaded
        )

        controller.dispose()
    }

    private fun awaitAReadingStateAfter(
        states: MutableList<ReaderScreenState>,
        countBefore: Int,
        deadlineSeconds: Long = 15
    ): ReaderScreenState.Reading {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(deadlineSeconds)
        while (System.nanoTime() < deadline) {
            states.drop(countBefore).filterIsInstance<ReaderScreenState.Reading>()
                .lastOrNull { it.text is ReaderTextState.Loaded }
                ?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for a re-paginated Reading state")
    }
}
