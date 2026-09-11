package com.folium.reader

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.library.BookCatalogStore
import com.folium.reader.library.LibraryPaths
import com.folium.reader.library.OpenBookRequest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalDocumentIntentTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    @Test fun manifest_resolves_content_pdf_and_epub_view_intents() {
        listOf("application/pdf", "application/epub+zip").forEach { type ->
            val resolved = context.packageManager.queryIntentActivities(viewIntent(type, ExternalDocumentTestProvider.PDF_URI), PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue(resolved.any { it.activityInfo.name == FoliumActivity::class.java.name })
        }
    }

    @Test fun cold_and_warm_content_view_intents_import_and_open_documents() {
        val catalog = BookCatalogStore(LibraryPaths(context.filesDir))
        val initialCount = catalog.read().size
        ActivityScenario.launch<FoliumActivity>(viewIntent("application/pdf", ExternalDocumentTestProvider.PDF_URI)).use { scenario ->
            waitForCatalogSize(catalog, initialCount + 1)
            testContext.startActivity(
                viewIntent("application/epub+zip", ExternalDocumentTestProvider.EPUB_URI)
                    .setPackage(context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            waitForCatalogSize(catalog, initialCount + 2)
            assertTrue(catalog.read().last().format.name == "EPUB")
            scenario.onActivity { activity ->
                val request = activity.javaClass.getDeclaredField("openBook").apply { isAccessible = true }
                    .get(activity)
                assertTrue("warm VIEW must leave the reader open", request != null)
                assertTrue("warm EPUB must be the active reader document", (request as OpenBookRequest).book.format.name == "EPUB")
            }
        }
    }

    private fun viewIntent(type: String, uri: android.net.Uri) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun waitForCatalogSize(catalog: BookCatalogStore, expected: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (catalog.read().size >= expected) return
            Thread.sleep(50)
        }
        assertTrue("external document was not imported", catalog.read().size >= expected)
    }
}
