package com.folium.reader.reader

/** One page's ink load request: [version] names the load, so a result for an older one is dropped. */
data class PageInkLoad(val page: Int, val version: Long)

/** What [PageInkWindow.show] changed: the pages to load, visible ones first, and the pages to forget. */
data class PageInkWindowChange(val entered: List<PageInkLoad>, val evicted: Set<Int>)

/**
 * Which pages' ink the reader keeps ready to draw: the visible pages and [show]'s margin either side
 * of them, so a swipe lands on ink that is already built. Every page in the window carries a version
 * that changes whenever the page enters the window again or its ink changes ([invalidate]); a load
 * started under one version is current only while the page still has that same version.
 *
 * Plain state with no threading of its own: the caller confines it to one thread.
 */
class PageInkWindow {
    private val versions = HashMap<Int, Long>()
    private var nextVersion = 0L

    val pages: Set<Int> get() = versions.keys.toSet()

    /**
     * Keeps [visible] and [margin] pages either side of them, within `0 until pageCount`. Pages that
     * left the window are evicted and forgotten; pages that entered it get a fresh version.
     */
    fun show(visible: Set<Int>, margin: Int, pageCount: Int): PageInkWindowChange {
        val wanted = windowOf(visible, margin, pageCount)

        val evicted = versions.keys.filterTo(HashSet()) { it !in wanted }
        versions.keys.removeAll(evicted)

        val ordered = visible.filter { it in wanted }.sorted() + wanted.filter { it !in visible }.sorted()
        val entered = ordered
            .filter { it !in versions }
            .map { page -> PageInkLoad(page, bump(page)) }

        return PageInkWindowChange(entered, evicted)
    }

    /** [page]'s ink changed: its new version, or `null` when the page is outside the window. */
    fun invalidate(page: Int): Long? = if (page in versions) bump(page) else null

    /** Whether a load of [page] started under [version] is still the one to show. */
    fun isCurrent(page: Int, version: Long): Boolean = versions[page] == version

    private fun bump(page: Int): Long {
        nextVersion += 1
        versions[page] = nextVersion
        return nextVersion
    }

    private fun windowOf(visible: Set<Int>, margin: Int, pageCount: Int): Set<Int> {
        if (pageCount <= 0) return emptySet()

        return visible.flatMapTo(HashSet()) { page -> (page - margin)..(page + margin) }
            .filterTo(HashSet()) { it in 0 until pageCount }
    }
}
