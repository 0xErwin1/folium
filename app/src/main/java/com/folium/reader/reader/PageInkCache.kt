package com.folium.reader.reader

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.ContextCompat
import com.folium.reader.core.ink.PageInkStore
import com.folium.reader.core.ink.SheetItem
import com.folium.reader.core.library.BookId
import com.folium.reader.index.sha256
import com.folium.reader.library.DocumentHashCache
import com.folium.reader.library.LibraryPaths
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The identity a book's page ink is bound to ([PageInkStore.bind]): the document's content hash,
 * resolved through [DocumentHashCache], so reopening a book already opened once costs a stat and a
 * small read, never a rehash. Blocking I/O: call it off the main thread.
 */
internal fun pageInkIdentity(paths: LibraryPaths, bookId: BookId, file: File): String =
    DocumentHashCache(paths).resolve(bookId, file, ::sha256).value

/**
 * Keeps the committed ink of the pages around the reader's position built and ready to draw, for one
 * book, owned by the reader host.
 *
 * Every public method runs on the main thread. Reading a page's log and building its render run on
 * [work], a serial executor this cache owns and shuts down on [dispose]; results come back through
 * [main] and land in a Compose-observable map, so [renderFor] read during composition or drawing
 * recomposes or redraws once a page's ink is built.
 *
 * Nothing is shown unless the store is bound to [identity]: ink drawn on another version of the file
 * would sit on the wrong text. The binding, and the set of pages that have any ink at all
 * ([PageInkStore.pagesWithInk]), are read once on [work] and read again only after
 * [onPageInkChanged], so a page with no ink never reads a log.
 */
class PageInkCache internal constructor(
    private val store: PageInkStore,
    private val identity: () -> String?,
    private val buildRender: (List<SheetItem>) -> PageInkRender,
    private val work: ExecutorService,
    private val main: Executor
) {
    private val renders = mutableStateMapOf<Int, PageInkRender>()
    private val window = PageInkWindow()
    private var disposed = false

    private var boundOnWork: Boolean? = null
    private var listingOnWork: Set<Int>? = null

    /** [page]'s built ink, or `null` while it is loading, has none, or is outside the window. */
    fun renderFor(page: Int): PageInkRender? = renders[page]

    /**
     * The reader shows [visible]: keeps those pages and [margin] pages either side of them built,
     * within `0 until pageCount`, and forgets every other page.
     */
    fun show(visible: Set<Int>, margin: Int, pageCount: Int) {
        if (disposed) return

        val change = window.show(visible, margin, pageCount)

        for (page in change.evicted) renders.remove(page)
        for (load in change.entered) load(load, refresh = false)
    }

    /**
     * [page]'s ink was written elsewhere, a live surface having just closed it: the binding and the
     * list of inked pages are read again, and [page] is rebuilt if it is in the window.
     */
    fun onPageInkChanged(page: Int) {
        if (disposed) return

        val version = window.invalidate(page)
        if (version == null) {
            work.execute { forgetDiskState() }
            return
        }

        load(PageInkLoad(page, version), refresh = true)
    }

    /** The reader is going away: nothing more is loaded or shown, and [work] is shut down. */
    fun dispose() {
        disposed = true
        renders.clear()
        work.shutdown()
    }

    private fun load(load: PageInkLoad, refresh: Boolean) {
        work.execute {
            if (refresh) forgetDiskState()

            val render = runCatching { renderOnWork(load.page) }.getOrNull()

            main.execute { publish(load, render) }
        }
    }

    private fun publish(load: PageInkLoad, render: PageInkRender?) {
        if (disposed || !window.isCurrent(load.page, load.version)) return

        if (render == null || render.isEmpty) renders.remove(load.page) else renders[load.page] = render
    }

    private fun renderOnWork(page: Int): PageInkRender? {
        val listing = listingOnWork ?: store.pagesWithInk().also { listingOnWork = it }
        if (page !in listing) return null

        if (!boundOnWork()) return null

        return buildRender(store.read(page).items)
    }

    private fun boundOnWork(): Boolean {
        boundOnWork?.let { return it }

        val bound = runCatching {
            val expected = identity()
            expected != null && store.boundIdentity() == expected
        }.getOrDefault(false)

        boundOnWork = bound
        return bound
    }

    private fun forgetDiskState() {
        boundOnWork = null
        listingOnWork = null
    }

    companion object {
        /**
         * The cache for [bookId]'s page ink kept in [store], bound to [file]'s identity through
         * [pageInkIdentity]. [store] is the one instance the book's writers open pages through too.
         */
        fun forBook(context: Context, bookId: BookId, file: File, store: PageInkStore): PageInkCache {
            val appContext = context.applicationContext
            val paths = LibraryPaths(appContext.filesDir)
            val builder = PageInkRenderBuilder(appContext)

            return PageInkCache(
                store = store,
                identity = { pageInkIdentity(paths, bookId, file) },
                buildRender = builder::build,
                work = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "folium-page-ink-cache") },
                main = ContextCompat.getMainExecutor(appContext)
            )
        }
    }
}
