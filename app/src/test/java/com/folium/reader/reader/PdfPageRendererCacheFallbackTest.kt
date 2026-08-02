package com.folium.reader.reader

import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.CachedPage
import com.folium.reader.core.pdf.PageCacheKey
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.RenderSpec
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Isolates, without the [android.graphics.Bitmap] dependency that keeps this module's JVM unit
 * tests from driving [PdfPageRenderer] directly, the exact mechanism [PdfPageRenderer.rasterize]
 * fixes: [ByteBoundedPageCache.put] declining to retain a raster because live borrows held
 * elsewhere already crowd the budget out.
 *
 * The construction pins four viewport-sized (10MB) rasters against a 32MB budget — the same
 * threshold the diagnosis (Engram `folium/stuck-detail-render`) measured as deterministic (1/1) for
 * the exact defect this fixes: a settled viewport that never receives its detail render because
 * every render attempt is refused. [oldContract] reproduces the prior behaviour byte for byte — a
 * decline surfaces as a thrown, retryable [PdfException], which is what [ReaderPresenter] eventually
 * turns into a permanent [ReaderUiState.failedPages] entry. [newContract] is
 * [PdfPageRenderer.rasterize]'s own decision, replicated here: a decline is never thrown, only
 * reported as not retained, exactly as [PdfPageRenderer.render] now hands back an uncached
 * [BorrowedPage.Uncached] instead.
 */
class PdfPageRendererCacheFallbackTest {

    private class Marker(val pageIndex: Int)

    private val viewportSizedBytes = 10L * 1024 * 1024
    private val budgetBytes = 32L * 1024 * 1024

    private fun key(pageIndex: Int) =
        PageCacheKey("doc", pageIndex, 0L, RenderSpec(1080, 2400, PageSpaceRect(0f, pageIndex * 0.001f, 1f, 1f)))

    /** [PdfPageRenderer.render]'s behaviour before this fix: a decline is thrown as a retryable resource failure. */
    private fun oldContract(cache: ByteBoundedPageCache<Marker>, key: PageCacheKey, pageIndex: Int) {
        val retained = cache.put(key, RenderCandidate(Marker(pageIndex)) {}, viewportSizedBytes)
        if (!retained) throw PdfException(PdfFailure.Resource(retryable = true))
    }

    /** [PdfPageRenderer.rasterize]'s own decision: a decline is reported, never thrown. */
    private fun newContract(cache: ByteBoundedPageCache<Marker>, key: PageCacheKey, pageIndex: Int): Boolean =
        cache.put(key, RenderCandidate(Marker(pageIndex)) {}, viewportSizedBytes)

    @Test fun theOldContractThrowsOnceLiveBorrowsCrowdOutTheBudget() {
        val cache = ByteBoundedPageCache<Marker>(budgetBytes)
        val pinned = mutableListOf<CachedPage<Marker>>()
        var thrown = 0

        for (pageIndex in 0..3) {
            val pageKey = key(pageIndex)
            try {
                oldContract(cache, pageKey, pageIndex)
                pinned += requireNotNull(cache.acquire(pageKey))
            } catch (failure: PdfException) {
                assertTrue((failure.failure as PdfFailure.Resource).retryable)
                thrown++
            }
        }

        assertTrue(
            "pinning four viewport-sized rasters against a $budgetBytes byte budget must eventually be refused, was $thrown throws",
            thrown > 0
        )
        pinned.forEach { it.release() }
    }

    @Test fun theNewContractNeverThrowsUnderIdenticalPressureAndStillDeclinesWhenThereIsNoRoom() {
        val cache = ByteBoundedPageCache<Marker>(budgetBytes)
        val pinned = mutableListOf<CachedPage<Marker>>()
        var declined = 0

        for (pageIndex in 0..3) {
            val pageKey = key(pageIndex)
            val retained = newContract(cache, pageKey, pageIndex)
            if (retained) {
                pinned += requireNotNull(cache.acquire(pageKey))
            } else {
                declined++
            }
        }

        assertTrue(
            "the identical pressure that throws under the old contract must decline, never throw, here, was $declined declines",
            declined > 0
        )
        pinned.forEach { it.release() }
    }
}
