package com.folium.reader.reader

import android.graphics.Path
import com.folium.reader.core.ink.InkInputKind
import com.folium.reader.core.ink.InkSample
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTip
import com.folium.reader.core.ink.InkTool
import com.folium.reader.core.ink.PageInkStore
import com.folium.reader.core.ink.SheetEdit
import com.folium.reader.core.ink.SheetItem
import com.folium.reader.core.ink.StrokeId
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val IDENTITY = "sha256:book"
private const val PAGE_COUNT = 20

class PageInkCacheTest {
    @get:Rule val tempFolder = TemporaryFolder()

    /** Runs nothing until [drain], so a test decides exactly when work and main-thread steps happen. */
    private class QueuedExecutor : AbstractExecutorService() {
        private val queue = ArrayDeque<Runnable>()
        private var shutdown = false

        override fun execute(command: Runnable) {
            if (shutdown) throw java.util.concurrent.RejectedExecutionException("shut down")
            queue.add(command)
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.poll().run()
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return queue.toMutableList().also { queue.clear() }
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && queue.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }

    private val work = QueuedExecutor()
    private val mainQueue = ArrayDeque<Runnable>()
    private val main = Executor { mainQueue.add(it) }

    private fun runAll() {
        while (true) {
            work.drain()
            if (mainQueue.isEmpty()) return
            while (mainQueue.isNotEmpty()) mainQueue.poll().run()
        }
    }

    private fun stroke(id: String, sequence: Long) = InkStroke(
        StrokeId(id), InkTool.PEN, InkTip.BALLPOINT, colorArgb = 0xFF000000.toInt(),
        widthSheetUnits = 0.002f, inputKind = InkInputKind.STYLUS,
        samples = (0 until 4).map { i -> InkSample(0.1f * i, 0.2f * i, elapsedMillis = i * 5) },
        sequence = sequence
    )

    private fun inkedStore(vararg pages: Int, boundTo: String? = IDENTITY): PageInkStore {
        val store = PageInkStore(tempFolder.newFolder())
        for (page in pages) store.open(page).use { it.apply(SheetEdit.AddStrokes(listOf(stroke("s$page", 0)))) }
        if (boundTo != null) store.bind(boundTo)
        return store
    }

    private fun render(): PageInkRender = PageInkRender(listOf(PageInkDrawable.Polyline(Path(), 0, 1f)))

    private fun cache(
        store: PageInkStore,
        identity: () -> String? = { IDENTITY },
        buildRender: (Int, List<SheetItem>) -> PageInkRender? = { _, _ -> render() }
    ) = PageInkCache(store, identity, buildRender, work, main)

    @Test fun aBoundInkedPageInTheWindowIsBuilt() {
        val cache = cache(inkedStore(3))

        cache.show(setOf(3), margin = 0, pageCount = PAGE_COUNT)
        runAll()

        assertNotNull(cache.renderFor(3))
    }

    @Test fun aResultForAPageEvictedBeforeItArrivedIsDropped() {
        val cache = cache(inkedStore(3))

        cache.show(setOf(3), margin = 0, pageCount = PAGE_COUNT)
        work.drain()
        cache.show(setOf(10), margin = 0, pageCount = PAGE_COUNT)
        runAll()

        assertNull(cache.renderFor(3))
    }

    @Test fun aResultForAStaleVersionIsDropped() {
        val first = render()
        val second = render()
        var next = first
        val cache = cache(inkedStore(3), buildRender = { _, _ -> next })

        cache.show(setOf(3), margin = 0, pageCount = PAGE_COUNT)
        work.drain()
        cache.onPageInkChanged(3)
        while (mainQueue.isNotEmpty()) mainQueue.poll().run()
        assertNull(cache.renderFor(3))

        next = second
        runAll()
        assertSame(second, cache.renderFor(3))
    }

    @Test fun aStoreBoundToAnotherDocumentShowsNoInk() {
        val cache = cache(inkedStore(3, boundTo = "sha256:other"))

        cache.show(setOf(3), margin = 0, pageCount = PAGE_COUNT)
        runAll()

        assertNull(cache.renderFor(3))
    }

    @Test fun anUnknownDocumentIdentityShowsNoInk() {
        val cache = cache(inkedStore(3), identity = { null })

        cache.show(setOf(3), margin = 0, pageCount = PAGE_COUNT)
        runAll()

        assertNull(cache.renderFor(3))
    }

    @Test fun anEmptyOrFailedRebuildRemovesThePage() {
        var build: () -> PageInkRender? = { render() }
        val cache = cache(inkedStore(3, 4), buildRender = { _, _ -> build() })
        cache.show(setOf(3, 4), margin = 0, pageCount = PAGE_COUNT)
        runAll()
        assertNotNull(cache.renderFor(3))
        assertNotNull(cache.renderFor(4))

        build = { PageInkRender(emptyList()) }
        cache.onPageInkChanged(3)
        runAll()
        assertNull(cache.renderFor(3))

        build = { throw IllegalStateException("render failed") }
        cache.onPageInkChanged(4)
        runAll()
        assertNull(cache.renderFor(4))
    }

    @Test fun nothingIsPublishedAfterDispose() {
        val cache = cache(inkedStore(3))

        cache.show(setOf(3), margin = 0, pageCount = PAGE_COUNT)
        work.drain()
        cache.dispose()
        while (mainQueue.isNotEmpty()) mainQueue.poll().run()

        assertNull(cache.renderFor(3))
    }

    @Test fun aTransientIdentityFailureIsRetriedOnTheNextWindowChange() {
        var failures = 1
        val cache = cache(inkedStore(3, 4), identity = {
            if (failures > 0) {
                failures -= 1
                throw java.io.IOException("transient")
            }
            IDENTITY
        })

        cache.show(setOf(3), margin = 0, pageCount = PAGE_COUNT)
        runAll()
        assertNull(cache.renderFor(3))

        cache.show(setOf(4), margin = 0, pageCount = PAGE_COUNT)
        runAll()
        assertNotNull(cache.renderFor(4))
    }

    @Test fun aBindingCheckThatKeepsFailingStopsBeingRetriedUntilTheInkChanges() {
        var identityCalls = 0
        var failing = true
        val cache = cache(inkedStore(*IntArray(6) { it }), identity = {
            identityCalls += 1
            if (failing) throw java.io.IOException("unreadable")
            IDENTITY
        })

        for (page in 0 until 5) {
            cache.show(setOf(page), margin = 0, pageCount = PAGE_COUNT)
            runAll()
        }
        org.junit.Assert.assertEquals(3, identityCalls)

        failing = false
        cache.onPageInkChanged(4)
        runAll()
        assertNotNull(cache.renderFor(4))
    }

    @Test fun aDefiniteMismatchIsNotAskedAgainUntilTheInkChanges() {
        var identityCalls = 0
        val cache = cache(inkedStore(3, 4, boundTo = "sha256:other"), identity = {
            identityCalls += 1
            IDENTITY
        })

        cache.show(setOf(3), margin = 0, pageCount = PAGE_COUNT)
        runAll()
        cache.show(setOf(4), margin = 0, pageCount = PAGE_COUNT)
        runAll()

        assertNull(cache.renderFor(4))
        org.junit.Assert.assertEquals(1, identityCalls)
    }
}
