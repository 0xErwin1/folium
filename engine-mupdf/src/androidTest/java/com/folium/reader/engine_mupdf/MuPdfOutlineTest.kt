package com.folium.reader.engine_mupdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.pdf.PdfSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MuPdfOutlineTest {
    private fun fixture(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, name).also { output ->
            context.assets.open("pdf/$name").use { input -> output.outputStream().use(input::copyTo) }
        }
    }

    @Test fun outlineFixtureYieldsTitlesHierarchyAndResolvedPages() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()

        MuPdfEngine().open(PdfSource(fixture("outline.pdf").absolutePath)).use { document ->
            val outline = document.outline()
            assertEquals(2, outline.size)

            val chapter = outline[0]
            assertEquals("Chapter 1", chapter.title)
            assertEquals(0, chapter.pageIndex)
            assertEquals(1, chapter.children.size)

            val section = chapter.children[0]
            assertEquals("Section 1.1", section.title)
            assertEquals(1, section.pageIndex)
            assertTrue(section.children.isEmpty())

            val unresolvable = outline[1]
            assertEquals("Unresolvable", unresolvable.title)
            assertNull(unresolvable.pageIndex)
            assertTrue(unresolvable.children.isEmpty())
        }

        assertEquals(baseline, MuPdfNativeOwnerTracker.snapshot())
    }

    @Test fun documentWithoutOutlineYieldsEmptyList() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()

        MuPdfEngine().open(PdfSource(fixture("native-english.pdf").absolutePath)).use { document ->
            assertTrue(document.outline().isEmpty())
        }

        assertEquals(baseline, MuPdfNativeOwnerTracker.snapshot())
    }
}
