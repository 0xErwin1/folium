package com.folium.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.library.BookCatalogStore
import com.folium.reader.library.LibraryPaths
import com.folium.reader.library.OpenBookRequest
import com.folium.reader.library.ProgressStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoliumActivityRestoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun recreation_reopens_the_reader_at_the_saved_nonzero_page() {
        val paths = LibraryPaths(context.filesDir)
        val catalog = BookCatalogStore(paths)
        val before = catalog.read().map { it.id }.toSet()
        ActivityScenario.launch<FoliumActivity>(externalViewIntent()).use { scenario ->
            val book = waitForImportedBook(catalog, before)
            assertEquals(book.id, waitForOpenBook(scenario)?.book?.id)
            ProgressStore(paths).put(book.id, 2, book.pageCount)

            repeat(2) {
                scenario.recreate()
                val request = waitForOpenBook(scenario)
                assertNotNull(request)
                assertEquals(book.id, request?.book?.id)
                assertEquals(2, request?.initialPage)
            }
        }
    }

    private fun waitForOpenBook(scenario: ActivityScenario<FoliumActivity>): OpenBookRequest? {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            var request: OpenBookRequest? = null
            scenario.onActivity { activity ->
                request = activity.javaClass.getDeclaredField("openBook").apply { isAccessible = true }
                    .get(activity) as OpenBookRequest?
            }
            if (request != null) return request
            Thread.sleep(50)
        }
        return null
    }

    private fun externalViewIntent() = Intent(Intent.ACTION_VIEW)
        .setDataAndType(ExternalDocumentTestProvider.PDF_URI, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun waitForImportedBook(catalog: BookCatalogStore, before: Set<com.folium.reader.core.library.BookId>): com.folium.reader.core.library.LibraryBook {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            catalog.read().lastOrNull { it.id !in before }?.let { return it }
            Thread.sleep(50)
        }
        error("external document was not imported")
    }
}
