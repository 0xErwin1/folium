package com.folium.reader.engine_mupdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.MultiArchive
import com.artifex.mupdf.fitz.TreeArchive
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W0 spike. Answers the two questions the design could not settle without a device: whether a
 * bookmark survives a re-pagination, and whether an archive the app supplies is consulted when the
 * user stylesheet names a font by a relative URL.
 */
@RunWith(AndroidJUnit4::class)
class BookmarkRoundTripSpikeTest {

    private fun fixture(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, name).also { output ->
            val stream = runCatching { context.assets.open("pdf/$name") }.getOrElse { context.assets.open(name) }
            stream.use { input -> output.outputStream().use(input::copyTo) }
        }
    }

    private fun textAt(document: Document, pageIndex: Int): String {
        val page = document.loadPage(pageIndex)
        return try {
            page.toStructuredText().let { text ->
                try { text.asText() } finally { text.destroy() }
            }
        } finally {
            page.destroy()
        }
    }

    /**
     * The question is not whether a bookmark resolves. It is what its granularity is: a chapter and
     * a page within it, or a place in the text. Anchoring mid-chapter is what tells them apart.
     */
    @Test fun bookmarkGranularityAcrossRelayout() {
        val report = StringBuilder("\n===== W0 BOOKMARK GRANULARITY =====\n")
        val document = Document.openDocument(fixture("reflowable-long.epub").absolutePath)
        try {
            document.layout(450f, 675f, 18f)
            val before = document.countPages()
            report.append("  em=18 -> $before pages\n")

            val anchors = (0 until before).map { page ->
                val location = document.locationFromPageNumber(page)
                Triple(page, location, document.makeBookmark(location))
            }
            val heads = anchors.map { (page, location, _) -> "p$page=c${location.chapter}.${location.page}" }
            report.append("  locations at em=18: ${heads.joinToString(" ")}\n")

            val firstWords = anchors.associate { (page, _, _) ->
                page to textAt(document, page).trim().take(28).replace("\n", " ")
            }

            document.layout(450f, 675f, 26f)
            val after = document.countPages()
            report.append("  em=26 -> $after pages\n")

            anchors.forEach { (page, location, bookmark) ->
                val recovered = document.pageNumberFromLocation(document.findBookmark(bookmark))
                val proportional = (page.toDouble() / before * after).toInt()
                val landed = if (recovered in 0 until after) textAt(document, recovered).trim().take(28).replace("\n", " ") else ""
                val exact = landed == firstWords[page]
                report.append("  page $page (c${location.chapter}.${location.page}) -> $recovered  proportional=$proportional  sameOpeningText=$exact\n")
            }
        } finally {
            document.destroy()
        }
        report.append("===================================\n")
        println(report)
    }

    /**
     * The refinement the granularity probe showed is needed: a chapter from the bookmark, and a
     * character offset into that chapter to place the reader inside it. Proves whether walking a
     * chapter's pages and accumulating extracted text lands on the same words after a re-pagination.
     */
    @Test fun chapterOffsetSurvivesRelayout() {
        val report = StringBuilder("\n===== W0 CHAPTER OFFSET =====\n")
        val document = Document.openDocument(fixture("reflowable-long.epub").absolutePath)
        try {
            fun pagesOfChapter(chapter: Int): List<Int> =
                (0 until document.countPages(chapter)).map {
                    document.pageNumberFromLocation(com.artifex.mupdf.fitz.Location(chapter, it))
                }

            fun offsetInChapter(page: Int): Pair<Int, Int> {
                val location = document.locationFromPageNumber(page)
                val before = pagesOfChapter(location.chapter).takeWhile { it < page }
                return location.chapter to before.sumOf { textAt(document, it).length }
            }

            document.layout(450f, 675f, 18f)
            val before = document.countPages()
            val probes = (0 until before).map { page ->
                val (chapter, offset) = offsetInChapter(page)
                Triple(page, chapter to offset, textAt(document, page).trim().take(30).replace("\n", " "))
            }
            report.append("  em=18 -> $before pages\n")

            document.layout(450f, 675f, 26f)
            val after = document.countPages()
            report.append("  em=26 -> $after pages\n")

            var exact = 0
            probes.forEach { (page, anchorPair, opening) ->
                val (chapter, offset) = anchorPair
                var consumed = 0
                var landed = pagesOfChapter(chapter).firstOrNull() ?: 0
                for (candidate in pagesOfChapter(chapter)) {
                    val length = textAt(document, candidate).length
                    if (consumed + length > offset) { landed = candidate; break }
                    consumed += length
                    landed = candidate
                }
                val landedText = textAt(document, landed).trim().replace("\n", " ")
                val hit = landedText.contains(opening.take(20))
                if (hit) exact++
                report.append("  page $page (c$chapter off=$offset) -> $landed  openingTextPresent=$hit\n")
            }
            report.append("  VERDICT $exact/${probes.size} landed on the page carrying the old opening text\n")
        } finally {
            document.destroy()
        }
        report.append("=============================\n")
        println(report)
    }

    @Test fun suppliedArchiveServesAFontToTheUserStylesheet() {
        val report = StringBuilder("\n===== W0 SUPPLIED ARCHIVE =====\n")
        val fontBytes = fixture("reflowable.epub").let { epub ->
            java.util.zip.ZipFile(epub).use { zip ->
                zip.entries().toList().firstOrNull { it.name.endsWith(".ttf", true) }
                    ?.let { zip.getInputStream(it).readBytes() }
            }
        }
        report.append("  a font inside the corpus: ${fontBytes?.size ?: 0} bytes\n")

        val supplied = TreeArchive()
        val probeFont = fontBytes ?: ByteArray(0)
        if (probeFont.isNotEmpty()) {
            supplied.add("folium/Probe.ttf", Buffer(probeFont.size).also { it.writeBytes(probeFont) })
        }
        val combined = MultiArchive().also { it.mountArchive(supplied, "") }

        val outcome = runCatching {
            val document = Document.openDocument(fixture("reflowable-long.epub").absolutePath, combined)
            try {
                document.style(true, "@font-face{font-family:\"Probe\";src:url(\"folium/Probe.ttf\")}body,p{font-family:\"Probe\" !important}")
                document.layout(450f, 675f, 18f)
                val page = document.loadPage(0)
                try {
                    val text = page.toStructuredText()
                    try { "opened, pages=${document.countPages()}" } finally { text.destroy() }
                } finally { page.destroy() }
            } finally { document.destroy() }
        }.getOrElse { "FAILED ${it.javaClass.simpleName}: ${it.message}" }

        report.append("  openDocument(path, suppliedArchive) -> $outcome\n")
        report.append("  read the logcat lines above for 'cannot locate font' to see whether the url resolved\n")
        report.append("===============================\n")
        println(report)
    }
}
