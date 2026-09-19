package com.folium.reader.reader

import com.folium.reader.core.diskcache.DiskPageCacheEntry
import com.folium.reader.core.diskcache.DiskPageCacheKey
import com.folium.reader.core.diskcache.DiskPageCacheStore
import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.DocumentMetadata
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.RenderPriority
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.ViewportRenderRequest
import com.folium.reader.core.text.TextPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises [PdfPageRenderer]'s disk-cache read-through and write-through without ever letting
 * execution reach [android.graphics.Bitmap] conversion: this module's JVM unit tests run without
 * Robolectric, so [Raster.toBitmap] and [DiskPageCacheEntry.toBitmap] are not usable here — see
 * [PdfPageRendererRenderPageTest]'s own doc for the same constraint on the engine path. Every test
 * below halts the call, via a thrown marker, at the last point before either conversion would run.
 */
private class DiskCacheMarker : RuntimeException("halted before bitmap conversion")

private class RecordingDocument(private val pageWidth: Float = 100f, private val pageHeight: Float = 200f) : PdfDocument {
    var renderPageCalls = 0
        private set
    var pageInfoCalls = 0
        private set

    override val pageCount = 1
    override fun pageInfo(index: Int): PageInfo {
        pageInfoCalls++
        return PageInfo(index, pageWidth, pageHeight, 0)
    }

    override fun buildDisplayList(index: Int): DisplayList = throw AssertionError("must not build a display list directly")
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
        beforeRender()
        return Raster(spec.width, spec.height, ByteArray(spec.width * spec.height * 4) { it.toByte() })
    }
}

/** A store whose every method throws unless explicitly stubbed, so an unexpected call fails loudly. */
private class FakeDiskStore(
    private val onRead: (DiskPageCacheKey) -> DiskPageCacheEntry? = { throw AssertionError("read must not be called") },
    private val onEnqueueWrite: (DiskPageCacheKey, ByteArray, Float) -> Unit = { _, _, _ -> throw AssertionError("enqueueWrite must not be called") },
    private val onContainsKey: (DiskPageCacheKey) -> Boolean = { throw AssertionError("containsKey must not be called") }
) : DiskPageCacheStore {
    var readCalls = 0
        private set
    var enqueueWriteCalls = 0
        private set

    override fun markOpen(contentId: String) = Unit
    override fun markClosed(contentId: String) = Unit
    override fun containsKey(key: DiskPageCacheKey): Boolean = onContainsKey(key)
    override fun read(key: DiskPageCacheKey): DiskPageCacheEntry? {
        readCalls++
        return onRead(key)
    }
    override fun enqueueWrite(key: DiskPageCacheKey, rgba: ByteArray, pageAspect: Float) {
        enqueueWriteCalls++
        onEnqueueWrite(key, rgba, pageAspect)
    }
}

class PdfPageRendererDiskCacheTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val wholePageSpec = RenderSpec(40, 20, PageSpaceRect(0f, 0f, 1f, 1f))
    private val croppedSpec = RenderSpec(40, 20, PageSpaceRect(0.1f, 0.1f, 0.9f, 0.9f))

    private fun renderer(
        document: RecordingDocument,
        persistentCache: PersistentPageCacheContext?,
        onPageMeasured: (Int, (Int) -> Float) -> Unit = { _, _ -> }
    ) = PdfPageRenderer(
        document = document,
        documentId = "doc",
        generation = 0L,
        cache = ByteBoundedPageCache(32L * 1024 * 1024),
        priorityGate = DocumentPriorityGate(),
        onPageMeasured = onPageMeasured,
        persistentCache = persistentCache
    )

    private fun request(spec: RenderSpec, pageIndex: Int = 0) = ViewportRenderRequest(
        requestId = 1L, pageIndex = pageIndex, priority = RenderPriority.VISIBLE, generation = 0L, spec = spec
    )

    @Test fun aDiskHitReportsThePageShapeWithoutCallingTheDocumentAtAll() {
        val document = RecordingDocument()
        val entry = DiskPageCacheEntry(ByteArray(wholePageSpec.width * wholePageSpec.height * 4), wholePageSpec.width, wholePageSpec.height, wholePageSpec.pageSpace, pageAspect = 2.5f)
        val store = FakeDiskStore(onRead = { entry })
        var reportedAspect: Float? = null
        val onPageMeasured: (Int, (Int) -> Float) -> Unit = { pageIndex, measure ->
            reportedAspect = measure(pageIndex)
            throw DiskCacheMarker()
        }
        val persistent = PersistentPageCacheContext("content-1", "engine-1", null, store)
        val renderer = renderer(document, persistent, onPageMeasured)

        assertThrows(DiskCacheMarker::class.java) {
            renderer.render(request(wholePageSpec), CancellationSignal { false })
        }

        assertEquals(2.5f, reportedAspect)
        assertEquals(0, document.renderPageCalls)
        assertEquals(0, document.pageInfoCalls)
        assertEquals(1, store.readCalls)
    }

    @Test fun aDiskMissFallsThroughToTheEngineAndEnqueuesAWrite() {
        val document = RecordingDocument()
        val store = FakeDiskStore(
            onRead = { null },
            onEnqueueWrite = { _, _, _ -> throw DiskCacheMarker() }
        )
        val persistent = PersistentPageCacheContext("content-1", "engine-1", null, store)
        val renderer = renderer(document, persistent)

        assertThrows(DiskCacheMarker::class.java) {
            renderer.render(request(wholePageSpec), CancellationSignal { false })
        }

        assertEquals(1, document.renderPageCalls)
        assertEquals(1, store.enqueueWriteCalls)
    }

    // A non-whole-page spec's disk ineligibility is asserted at the pure key level instead of here:
    // com.folium.reader.core.diskcache.DiskPageCacheKeyTest.aCroppedSpecIsNeverStorable and
    // FileDiskPageCacheStoreTest.nonWholePageSpecsAreNeverWrittenOrLookedUp. Once
    // enqueueDiskWrite/diskCandidate decline a cropped spec they fall straight through to the
    // engine's own bitmap conversion, which this module's Robolectric-free JVM tests cannot drive —
    // see this file's own doc — so there is no marker point left to halt on for that case here.

    @Test fun cancellationBeforeTheDiskReadSkipsTheStoreEntirely() {
        val document = RecordingDocument()
        val store = FakeDiskStore()
        val persistent = PersistentPageCacheContext("content-1", "engine-1", null, store)
        val renderer = renderer(document, persistent)

        var call = 0
        val signal = CancellationSignal {
            call++
            call >= 2
        }

        val failure = assertThrows(com.folium.reader.core.pdf.PdfException::class.java) {
            renderer.render(request(wholePageSpec), signal)
        }

        assertEquals(0, store.readCalls)
        assertEquals(true, (failure.failure as com.folium.reader.core.pdf.PdfFailure.Resource).retryable)
    }

    @Test fun aWholePageRenderOffersExactlyOneWholePagePreview() {
        val document = RecordingDocument()
        val store = FakeDiskStore(onRead = { null }, onEnqueueWrite = { _, _, _ -> throw DiskCacheMarker() })
        val previews = PagePreviews.open(tempFolder.newFolder(), "engine-1", "content-1", layoutVersion = null, pageCount = 1)
        val persistent = PersistentPageCacheContext("content-1", "engine-1", null, store, pagePreviews = previews)
        val renderer = renderer(document, persistent)

        assertThrows(DiskCacheMarker::class.java) {
            renderer.render(request(wholePageSpec), CancellationSignal { false })
        }
        assertTrue(previews.awaitIdleForTest())

        assertEquals(1, previews.version)
        previews.close()
    }

    // A cropped spec's disk ineligibility already gates enqueueDiskWrite before offerPreview ever
    // runs, and both are decided by the same RenderSpec.isWholePage() check — see
    // PdfPageRenderer.offerPreview — so there is no separate marker point to halt this Robolectric-
    // free test on for the cropped case; it is instead asserted at the pure predicate level by
    // com.folium.reader.core.diskcache.DiskPageCacheKeyTest.aCroppedSpecIsNeverStorable.

    @Test fun aDiskHitOffersAPreviewWhenNoneExistsYetAndSkipsItWhenOneAlreadyDoes() {
        val document = RecordingDocument()
        val entry = DiskPageCacheEntry(ByteArray(wholePageSpec.width * wholePageSpec.height * 4), wholePageSpec.width, wholePageSpec.height, wholePageSpec.pageSpace, pageAspect = 1f)
        val store = FakeDiskStore(onRead = { entry })
        val previews = PagePreviews.open(tempFolder.newFolder(), "engine-1", "content-1", layoutVersion = null, pageCount = 1)
        val persistent = PersistentPageCacheContext("content-1", "engine-1", null, store, pagePreviews = previews)
        val renderer = renderer(document, persistent) { _, measure -> measure(0); throw DiskCacheMarker() }

        assertThrows(DiskCacheMarker::class.java) {
            renderer.render(request(wholePageSpec), CancellationSignal { false })
        }
        assertTrue(previews.awaitIdleForTest())
        assertEquals(1, previews.version)

        assertThrows(DiskCacheMarker::class.java) {
            renderer.render(request(wholePageSpec), CancellationSignal { false })
        }
        assertTrue(previews.awaitIdleForTest())
        assertEquals(1, previews.version)

        previews.close()
    }

    @Test fun cancellationAfterTheDiskReadNeverReportsAndNeverConverts() {
        val document = RecordingDocument()
        val entry = DiskPageCacheEntry(ByteArray(wholePageSpec.width * wholePageSpec.height * 4), wholePageSpec.width, wholePageSpec.height, wholePageSpec.pageSpace, pageAspect = 1f)
        val store = FakeDiskStore(onRead = { entry })
        var measured = false
        val persistent = PersistentPageCacheContext("content-1", "engine-1", null, store)
        val renderer = renderer(document, persistent) { _, measure -> measured = true; measure(0) }

        var call = 0
        val signal = CancellationSignal {
            call++
            call >= 3
        }

        assertThrows(com.folium.reader.core.pdf.PdfException::class.java) {
            renderer.render(request(wholePageSpec), signal)
        }

        assertEquals(1, store.readCalls)
        assertEquals(false, measured)
    }
}
