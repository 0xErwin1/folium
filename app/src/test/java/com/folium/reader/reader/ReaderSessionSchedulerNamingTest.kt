package com.folium.reader.reader

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ReaderSession] wires two [com.folium.reader.core.pdf.ViewportScheduler] instances -- one for the
 * base tier, one for the detail tier -- and passes each a distinct `workerPoolName` so a stack dump
 * can tell their pooled threads apart (`ViewportSchedulerWorkerPoolTest` covers that the constructor
 * argument reaches the pool; this covers that [ReaderSession] itself actually supplies two different
 * names, not the same one twice).
 *
 * Exercising [ReaderSession.open] directly would require a real opened document and Android context,
 * which is out of scope for a naming regression, so this reads the source instead: brittle to a
 * rename of the constant names, robust to everything else, and it fails loudly rather than silently
 * if the file it expects to find has moved.
 *
 * [ReaderSession] builds a presenter in two places — [ReaderSession.Companion] on open and
 * `repaginate` on a re-pagination — each constructing its own pair of schedulers, so the literal
 * count of `workerPoolName = "..."` occurrences is four; what matters is that every occurrence names
 * one of exactly two distinct pools.
 */
class ReaderSessionSchedulerNamingTest {
    @Test fun theBaseAndDetailSchedulersAreConstructedWithDistinctWorkerPoolNames() {
        val source = readerSessionSource()

        val workerPoolNames = Regex("""workerPoolName\s*=\s*"([^"]+)"""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()

        assertTrue(
            "expected ReaderSession to construct at least one ViewportScheduler pair with an " +
                "explicit workerPoolName, found $workerPoolNames",
            workerPoolNames.isNotEmpty()
        )
        val distinctNames = workerPoolNames.distinct()
        assertTrue(
            "expected exactly two distinct worker pool names across every scheduler ReaderSession " +
                "constructs, found $distinctNames",
            distinctNames.size == 2
        )
        assertNotEquals(
            "the base and detail tier schedulers must not share a workerPoolName, or a stack dump " +
                "could not tell their workers apart",
            distinctNames[0],
            distinctNames[1]
        )
    }

    private fun readerSessionSource(): String {
        val candidates = listOf(
            File("src/main/java/com/folium/reader/reader/ReaderSession.kt"),
            File("app/src/main/java/com/folium/reader/reader/ReaderSession.kt")
        )
        val source = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("could not locate ReaderSession.kt from any of: $candidates")
        return source.readText()
    }
}
