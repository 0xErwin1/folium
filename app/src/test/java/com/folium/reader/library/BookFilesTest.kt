package com.folium.reader.library

import com.folium.reader.core.library.BookFormat
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookFilesTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val paths get() = LibraryPaths(tempFolder.root)

    @Test fun documentResolvesByTheBooksOwnFormat() {
        val files = BookFiles(paths)
        val pdfBook = LibraryBook(BookId("a"), "Title", pageCount = 1, addedAtMillis = 0L)
        val epubBook = LibraryBook(BookId("b"), "Title", pageCount = 1, addedAtMillis = 0L, format = BookFormat.EPUB)

        assertEquals(paths.documentFile(pdfBook.id, BookFormat.PDF), files.document(pdfBook))
        assertEquals(paths.documentFile(epubBook.id, BookFormat.EPUB), files.document(epubBook))
    }

    /**
     * The per-book typography files live inside [LibraryPaths.bookDir], which [BookFiles.deleteBook]
     * already removes recursively — this asserts that fact rather than trusting it.
     */
    @Test fun deletingABookRemovesItsTypographyFilesToo() {
        val files = BookFiles(paths)
        val id = BookId("a")
        paths.typographyFile(id).apply { parentFile?.mkdirs(); writeText("preset") }
        paths.typographyCostFile(id).writeText("120")

        files.deleteBook(id)

        assertFalse(paths.typographyFile(id).exists())
        assertFalse(paths.typographyCostFile(id).exists())
        assertFalse(paths.bookDir(id).exists())
    }

    @Test fun deletingABookRemovesItsPageInkToo() {
        val files = BookFiles(paths)
        val id = BookId("a")
        paths.pageInkDir(id).apply { mkdirs() }.resolve("p0.log").writeText("ink")

        files.deleteBook(id)

        assertFalse(paths.pageInkDir(id).exists())
    }

    @Test fun inkedPagesCountOnlyThePageLogsInTheBooksPageInkDir() {
        val files = BookFiles(paths)
        val id = BookId("a")
        val dir = paths.pageInkDir(id).apply { mkdirs() }
        dir.resolve("p0.log").writeText("ink")
        dir.resolve("p12.log").writeText("ink")
        dir.resolve("page-ink.meta").writeText("meta")
        dir.resolve("notes.txt").writeText("other")

        assertEquals(2, files.inkedPageCount(id))
        assertEquals(0, files.inkedPageCount(BookId("never-written")))
    }
}
