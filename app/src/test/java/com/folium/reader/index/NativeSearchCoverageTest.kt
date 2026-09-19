package com.folium.reader.index

import com.folium.reader.core.ocr.OcrCancellationReason
import com.folium.reader.core.ocr.OcrPageState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [nativeSearchCoverage] is the one rule shared by [RoomTextPageIndex], [TransientTextPageIndex],
 * and [com.folium.reader.reader.TextPageLoader]'s in-memory tracker; these cases exercise it
 * directly rather than through any one of those three plumbings.
 */
class NativeSearchCoverageTest {
    @Test fun unusableNativePageWithoutOcrFinishesWithoutText() {
        assertEquals(
            TextSearchPageCoverage.WITHOUT_TEXT,
            nativeSearchCoverage(
                nativeState = TextPageIndexState.COMPLETE,
                nativeUsable = false,
                hasOcr = false
            )
        )
    }

    @Test fun unusableNativePageWithOcrStaysPendingUntilOcrResolves() {
        assertEquals(
            TextSearchPageCoverage.PENDING,
            nativeSearchCoverage(
                nativeState = TextPageIndexState.COMPLETE,
                nativeUsable = false,
                hasOcr = true
            )
        )
        assertEquals(
            TextSearchPageCoverage.PROCESSED,
            nativeSearchCoverage(
                nativeState = TextPageIndexState.COMPLETE,
                nativeUsable = false,
                hasOcr = true,
                ocrState = OcrPageState.COMPLETED
            )
        )
        assertEquals(
            TextSearchPageCoverage.FAILED,
            nativeSearchCoverage(
                nativeState = TextPageIndexState.COMPLETE,
                nativeUsable = false,
                hasOcr = true,
                ocrState = OcrPageState.FAILED
            )
        )
        assertEquals(
            TextSearchPageCoverage.CANCELLED,
            nativeSearchCoverage(
                nativeState = TextPageIndexState.COMPLETE,
                nativeUsable = false,
                hasOcr = true,
                ocrState = OcrPageState.CANCELLED,
                ocrCancellationReason = OcrCancellationReason.USER
            )
        )
        assertEquals(
            TextSearchPageCoverage.PENDING,
            nativeSearchCoverage(
                nativeState = TextPageIndexState.COMPLETE,
                nativeUsable = false,
                hasOcr = true,
                ocrState = OcrPageState.CANCELLED,
                ocrCancellationReason = OcrCancellationReason.NATIVE_TEXT
            )
        )
    }

    @Test fun undecidedNativeUsabilityStaysPendingRegardlessOfOcr() {
        assertEquals(
            TextSearchPageCoverage.PENDING,
            nativeSearchCoverage(
                nativeState = TextPageIndexState.COMPLETE,
                nativeUsable = null,
                hasOcr = false
            )
        )
        assertEquals(
            TextSearchPageCoverage.PENDING,
            nativeSearchCoverage(
                nativeState = TextPageIndexState.COMPLETE,
                nativeUsable = null,
                hasOcr = true
            )
        )
    }

    @Test fun usableNativePageIsProcessedRegardlessOfOcr() {
        assertEquals(
            TextSearchPageCoverage.PROCESSED,
            nativeSearchCoverage(
                nativeState = TextPageIndexState.COMPLETE,
                nativeUsable = true,
                hasOcr = false
            )
        )
        assertEquals(
            TextSearchPageCoverage.PROCESSED,
            nativeSearchCoverage(
                nativeState = TextPageIndexState.COMPLETE,
                nativeUsable = true,
                hasOcr = true
            )
        )
    }

    @Test fun nativeExtractionStillRunningOrFailedShortCircuitsUsability() {
        assertEquals(
            TextSearchPageCoverage.PENDING,
            nativeSearchCoverage(
                nativeState = TextPageIndexState.IN_PROGRESS,
                nativeUsable = null,
                hasOcr = false
            )
        )
        assertEquals(
            TextSearchPageCoverage.FAILED,
            nativeSearchCoverage(
                nativeState = TextPageIndexState.FAILED,
                nativeUsable = null,
                hasOcr = true
            )
        )
    }
}
