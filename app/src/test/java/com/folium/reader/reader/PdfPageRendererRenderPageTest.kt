package com.folium.reader.reader

import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.DocumentMetadata
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.RenderPriority
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.ViewportRenderRequest
import com.folium.reader.core.text.TextPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A render miss must reach the engine through exactly one [PdfDocument.renderPage] call rather
 * than the three separate [PdfDocument.buildDisplayList] / [DisplayList.render] /
 * [DisplayList.close] calls it used to make — see `folium/one-engine-hold-per-render`. This fake
 * throws [AssertionError] the moment [buildDisplayList] is invoked, so a regression to the old,
 * multi-call sequence fails loudly instead of merely widening the lock window again.
 */
private class RenderPageMarker : RuntimeException("halted after renderPage")

private class RenderPageRecordingDocument : PdfDocument {
    var renderPageCalls = 0
        private set
    var lastIndex: Int? = null
    var pageInfoCalls = 0
        private set

    override val pageCount = 1

    override fun pageInfo(index: Int): PageInfo {
        pageInfoCalls++
        return PageInfo(index, 100f, 200f, 0)
    }

    override fun buildDisplayList(index: Int): DisplayList =
        throw AssertionError("PdfPageRenderer must call renderPage, not buildDisplayList, on a cache miss")

    override fun extractText(index: Int): TextPage = throw UnsupportedOperationException()
    override fun outline(): List<OutlineEntry> = emptyList()
    override fun metadata() = DocumentMetadata.NONE
    override fun close() = Unit

    override fun renderPage(
        index: Int,
        spec: RenderSpec,
        cancellationSignal: CancellationSignal,
        beforeRender: () -> Unit
    ): Raster {
        renderPageCalls++
        lastIndex = index
        beforeRender()
        throw RenderPageMarker()
    }
}

class PdfPageRendererRenderPageTest {

    private fun renderer(document: RenderPageRecordingDocument, onPageMeasured: (Int, (Int) -> Float) -> Unit) =
        PdfPageRenderer(
            document = document,
            documentId = "doc",
            generation = 0L,
            cache = ByteBoundedPageCache(32L * 1024 * 1024),
            priorityGate = DocumentPriorityGate(),
            onPageMeasured = onPageMeasured
        )

    private fun request(pageIndex: Int = 3) = ViewportRenderRequest(
        requestId = 1L,
        pageIndex = pageIndex,
        priority = RenderPriority.VISIBLE,
        generation = 0L,
        spec = RenderSpec(10, 10)
    )

    @Test fun aCacheMissMakesExactlyOneRenderPageCallOnTheDocument() {
        val document = RenderPageRecordingDocument()
        val renderer = renderer(document) { pageIndex, measure -> measure(pageIndex) }

        assertThrows(RenderPageMarker::class.java) {
            renderer.render(request(pageIndex = 3), CancellationSignal { false })
        }

        assertEquals(1, document.renderPageCalls)
        assertEquals(3, document.lastIndex)
    }

    @Test fun anAlreadyMeasuredPageNeverCallsPageInfo() {
        val document = RenderPageRecordingDocument()
        val renderer = renderer(document) { _, _ -> /* already known: measure is never invoked */ }

        assertThrows(RenderPageMarker::class.java) {
            renderer.render(request(pageIndex = 5), CancellationSignal { false })
        }

        assertEquals(0, document.pageInfoCalls)
    }

    @Test fun anUnmeasuredPageCallsPageInfoInsideTheSameRenderPageCall() {
        val document = RenderPageRecordingDocument()
        val renderer = renderer(document) { pageIndex, measure -> measure(pageIndex) }

        assertThrows(RenderPageMarker::class.java) {
            renderer.render(request(pageIndex = 5), CancellationSignal { false })
        }

        assertEquals(1, document.pageInfoCalls)
        assertEquals(1, document.renderPageCalls)
    }
}
