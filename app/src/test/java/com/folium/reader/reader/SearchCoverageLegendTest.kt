package com.folium.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchCoverageLegendTest {

    @Test
    fun aFullyReadDocumentHasNoLegend() {
        val coverage = ReaderSearchCoverage(indexedPages = 407, failedPages = 0, totalPages = 407, running = false)

        assertEquals(emptyList<SearchCoverageLegendEntry>(), searchCoverageLegend(coverage))
    }

    @Test
    fun anErrorOrAnEmptyDocumentHasNoLegend() {
        val failed = ReaderSearchCoverage(indexedPages = 3, failedPages = 0, totalPages = 10, running = false, error = true)
        val empty = ReaderSearchCoverage(indexedPages = 0, failedPages = 0, totalPages = 0, running = true)

        assertEquals(emptyList<SearchCoverageLegendEntry>(), searchCoverageLegend(failed))
        assertEquals(emptyList<SearchCoverageLegendEntry>(), searchCoverageLegend(empty))
    }

    @Test
    fun readPagesLeadAndEmptyStatesAreLeftOut() {
        val coverage = ReaderSearchCoverage(indexedPages = 402, failedPages = 0, totalPages = 407, running = false)

        assertEquals(
            listOf(
                SearchCoverageLegendEntry(SearchCoverageLegendKind.READ, 402),
                SearchCoverageLegendEntry(SearchCoverageLegendKind.PENDING, 5)
            ),
            searchCoverageLegend(coverage)
        )
    }

    @Test
    fun everyStateWithPagesAppearsInAFixedOrder() {
        val coverage = ReaderSearchCoverage(
            indexedPages = 10,
            failedPages = 2,
            totalPages = 20,
            running = true,
            pendingPages = 5,
            cancelledPages = 3
        )

        assertEquals(
            listOf(
                SearchCoverageLegendKind.READ,
                SearchCoverageLegendKind.PENDING,
                SearchCoverageLegendKind.FAILED,
                SearchCoverageLegendKind.CANCELLED
            ),
            searchCoverageLegend(coverage).map { it.kind }
        )
    }

    @Test
    fun aRunningSearchShowsItsLegendEvenBeforeAnyPageIsPending() {
        val coverage = ReaderSearchCoverage(indexedPages = 20, failedPages = 0, totalPages = 20, running = true)

        assertEquals(listOf(SearchCoverageLegendEntry(SearchCoverageLegendKind.READ, 20)), searchCoverageLegend(coverage))
    }
}
