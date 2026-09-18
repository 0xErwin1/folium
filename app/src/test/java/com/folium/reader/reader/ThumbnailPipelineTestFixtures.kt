package com.folium.reader.reader

import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.ViewportRenderRequest
import com.folium.reader.core.pdf.ViewportRenderer
import com.folium.reader.core.pdf.ViewportScheduler

/**
 * A [ThumbnailPipeline] that never actually renders anything, for the repagination/appearance test
 * suites that construct a [ReaderSession] directly and never exercise its page grid.
 */
internal fun noOpThumbnailPipeline(): ThumbnailPipeline<BorrowedThumbnail> {
    val renderer = ViewportRenderer<BorrowedThumbnail> { _: ViewportRenderRequest, _: CancellationSignal ->
        error("no thumbnail render expected")
    }
    return ThumbnailPipeline(
        releaseValue = BorrowedThumbnail::release,
        deliverToPresenter = { it() },
        onChanged = {}
    ) { onOutcome -> ViewportScheduler(1, renderer, workerPoolName = "thumb-test", onOutcome = onOutcome) }
}
