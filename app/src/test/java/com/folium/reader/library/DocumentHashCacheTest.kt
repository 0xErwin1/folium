package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.index.DocumentContentVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private val HASH_A = DocumentContentVersion("aa".repeat(32))
private val HASH_B = DocumentContentVersion("bb".repeat(32))

class DocumentHashCacheTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val bookId = BookId("a-book")
    private val paths get() = LibraryPaths(tempFolder.root)
    private fun cache(): DocumentHashCache = DocumentHashCache(paths)

    private fun failingHasher(): (File) -> DocumentContentVersion = {
        fail("the file must not be read when a matching record exists")
        error("unreachable")
    }

    @Test fun firstResolveHashesAndStoresTheRecord() {
        val file = tempFolder.newFile("document.pdf").apply { writeText("content") }

        val version = cache().resolve(bookId, file) { HASH_A }

        assertEquals(HASH_A, version)
    }

    @Test fun aMatchingRecordIsReusedWithoutReadingTheFile() {
        val file = tempFolder.newFile("document.pdf").apply { writeText("content") }
        cache().resolve(bookId, file) { HASH_A }

        val version = cache().resolve(bookId, file, failingHasher())

        assertEquals(HASH_A, version)
    }

    @Test fun aChangedLengthIsRehashed() {
        val file = tempFolder.newFile("document.pdf").apply { writeText("content") }
        cache().resolve(bookId, file) { HASH_A }
        file.appendText(" more")

        val version = cache().resolve(bookId, file) { HASH_B }

        assertEquals(HASH_B, version)
    }

    @Test fun aChangedLastModifiedIsRehashed() {
        val file = tempFolder.newFile("document.pdf").apply { writeText("content") }
        cache().resolve(bookId, file) { HASH_A }
        assertTrue(file.setLastModified(file.lastModified() + 60_000L))

        val version = cache().resolve(bookId, file) { HASH_B }

        assertEquals(HASH_B, version)
    }

    @Test fun aRecordForADifferentPathIsNeverTrusted() {
        val file = tempFolder.newFile("document.pdf").apply { writeText("content") }
        cache().resolve(bookId, file) { HASH_A }

        val movedFile = tempFolder.newFile("moved.pdf").apply {
            writeText("content")
            setLastModified(file.lastModified())
        }

        val version = cache().resolve(bookId, movedFile) { HASH_B }

        assertEquals(HASH_B, version)
    }

    @Test fun aCorruptRecordIsRehashed() {
        val file = tempFolder.newFile("document.pdf").apply { writeText("content") }
        paths.documentHashFile(bookId).apply {
            parentFile?.mkdirs()
            writeText("garbage\nnot-a-record\n")
        }

        val version = cache().resolve(bookId, file) { HASH_A }

        assertEquals(HASH_A, version)
    }

    @Test fun anUnreadableRecordIsRehashed() {
        val file = tempFolder.newFile("document.pdf").apply { writeText("content") }
        val recordFile = paths.documentHashFile(bookId)
        recordFile.parentFile?.mkdirs()
        assertTrue(recordFile.mkdir())

        val version = cache().resolve(bookId, file) { HASH_A }

        assertEquals(HASH_A, version)
    }

    @Test fun aRecordFromAnotherAlgorithmVersionIsRehashed() {
        val file = tempFolder.newFile("document.pdf").apply { writeText("content") }
        cache().resolve(bookId, file) { HASH_A }
        val recordFile = paths.documentHashFile(bookId)
        val tamperedLines = recordFile.readLines().toMutableList()
        val algorithmVersionLineIndex = 4
        tamperedLines[algorithmVersionLineIndex] = "0"
        recordFile.writeText(tamperedLines.joinToString(separator = "\n", postfix = "\n"))

        val version = cache().resolve(bookId, file) { HASH_B }

        assertEquals(HASH_B, version)
    }

    @Test fun aMissAlwaysRewritesTheRecordSoTheNextResolveIsCached() {
        val file = tempFolder.newFile("document.pdf").apply { writeText("content") }
        cache().resolve(bookId, file) { HASH_A }
        file.appendText(" more")
        cache().resolve(bookId, file) { HASH_B }

        val version = cache().resolve(bookId, file, failingHasher())

        assertEquals(HASH_B, version)
    }
}
