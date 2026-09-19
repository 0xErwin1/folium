package com.folium.reader.core.pdf

import com.folium.reader.core.text.TextPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingDisplayList(
    private val onRender: () -> Raster,
    private val onClose: () -> Unit
) : DisplayList {
    var closed = false
        private set

    override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal): Raster = onRender()

    override fun close() {
        closed = true
        onClose()
    }
}

private class DefaultRenderPageDocument(
    private val onRender: (DisplayList) -> Raster
) : PdfDocument {
    var displayListsBuilt = 0
        private set
    var lastBuilt: RecordingDisplayList? = null

    override val pageCount = 1

    override fun pageInfo(index: Int) = PageInfo(index, 100f, 200f, 0)

    override fun buildDisplayList(index: Int): DisplayList {
        displayListsBuilt++
        val displayList = RecordingDisplayList(onRender = { onRender(lastBuilt!!) }, onClose = {})
        lastBuilt = displayList
        return displayList
    }

    override fun extractText(index: Int): TextPage = throw UnsupportedOperationException()
    override fun outline(): List<OutlineEntry> = emptyList()
    override fun metadata() = DocumentMetadata.NONE
    override fun close() = Unit
}

class PdfContractsTest {

    @Test fun defaultRenderPageBuildsRendersAndClosesTheDisplayList() {
        val raster = Raster(1, 1, byteArrayOf(0, 0, 0, 0))
        val document = DefaultRenderPageDocument(onRender = { raster })
        var beforeRenderCalls = 0

        val result = document.renderPage(0, RenderSpec(1, 1), beforeRender = { beforeRenderCalls++ })

        assertEquals(1, beforeRenderCalls)
        assertEquals(1, document.displayListsBuilt)
        assertSame(raster, result)
        assertTrue(requireNotNull(document.lastBuilt).closed)
    }

    @Test fun defaultRenderPageClosesTheDisplayListWhenRenderingThrows() {
        val failure = IllegalStateException("boom")
        val document = DefaultRenderPageDocument(onRender = { throw failure })

        val thrown = assertThrows(IllegalStateException::class.java) {
            document.renderPage(0, RenderSpec(1, 1))
        }

        assertSame(failure, thrown)
        assertTrue(requireNotNull(document.lastBuilt).closed)
    }

    @Test fun defaultRenderPageClosesTheDisplayListWhenCancelledDuringRender() {
        val document = DefaultRenderPageDocument(onRender = { throw PdfException(PdfFailure.Resource(retryable = true)) })

        val thrown = assertThrows(PdfException::class.java) {
            document.renderPage(0, RenderSpec(1, 1), cancellationSignal = CancellationSignal { true })
        }

        assertEquals(PdfFailure.Resource(retryable = true), thrown.failure)
        assertTrue(requireNotNull(document.lastBuilt).closed)
    }

    @Test fun pageSpaceAndRasterEnforceNeutralBounds() {
        assertEquals(PageSpacePoint(0f, 1f), PageSpacePoint(0f, 1f))
        assertEquals(PageSpaceRect(0f, 0f, 1f, 1f), RenderSpec(10, 10).pageSpace)
        assertThrows(IllegalArgumentException::class.java) { PageSpacePoint(-0.01f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { Raster(2, 2, ByteArray(3)) }
    }

    @Test fun renderSpecRejectsOverflowAndOversizedRasters() {
        assertEquals(RenderSpec(4_096, 4_096), RenderSpec(4_096, 4_096))
        assertThrows(IllegalArgumentException::class.java) { RenderSpec(Int.MAX_VALUE, Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { RenderSpec(Int.MAX_VALUE, 1) }
        assertThrows(IllegalArgumentException::class.java) { RenderSpec(4_097, 4_096) }
    }

    @Test fun failuresRemainTypedAndPasswordEntryIsNotPartOfContract() {
        assertEquals(PdfFailure.PasswordRequired, PdfException(PdfFailure.PasswordRequired).failure)
        assertEquals(PdfFailure.WrongPassword, PdfException(PdfFailure.WrongPassword).failure)
        assertEquals(PdfFailure.Closed, PdfException(PdfFailure.Closed).failure)
        val cause = IllegalStateException("native text failure")
        assertSame(cause, PdfException(PdfFailure.TextExtraction, cause).cause)
    }
}
