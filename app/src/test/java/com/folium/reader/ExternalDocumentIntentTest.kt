package com.folium.reader

import android.content.Intent
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.library.OpenBookRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExternalDocumentIntentTest {
    @Test fun `router delivers a delayed open to the rebound activity`() {
        val callbacks = mutableMapOf<BookId, (OpenBookRequest?) -> Unit>()
        val delivered = mutableListOf<String>()
        val router = BookOpenRouter { id, callback -> callbacks[id] = callback }
        router.rebind { delivered += "old:${it.book.id.value}" }

        router.request(BookId("book"))
        router.rebind { delivered += "new:${it.book.id.value}" }
        callbacks.getValue(BookId("book"))(request("book"))

        assertEquals(listOf("new:book"), delivered)
        assertNull(router.pendingBookId)
    }

    @Test fun `router ignores superseded and cancelled delayed opens`() {
        val callbacks = mutableMapOf<BookId, (OpenBookRequest?) -> Unit>()
        val delivered = mutableListOf<BookId>()
        val router = BookOpenRouter { id, callback -> callbacks[id] = callback }
        router.rebind { delivered += it.book.id }

        router.request(BookId("first"))
        router.request(BookId("second"))
        callbacks.getValue(BookId("first"))(request("first"))
        assertEquals(BookId("second"), router.pendingBookId)
        callbacks.getValue(BookId("second"))(request("second"))
        router.request(BookId("third"))
        router.cancel()
        callbacks.getValue(BookId("third"))(request("third"))

        assertEquals(listOf(BookId("second")), delivered)
        assertNull(router.pendingBookId)
    }

    @Test fun `router survives repeated rebinds while preserving the pending ID`() {
        val callbacks = mutableMapOf<BookId, (OpenBookRequest?) -> Unit>()
        val delivered = mutableListOf<String>()
        val router = BookOpenRouter { id, callback -> callbacks[id] = callback }

        router.request(BookId("book"))
        repeat(3) { rotation -> router.rebind { delivered += "$rotation:${it.book.id.value}" } }
        assertEquals(BookId("book"), router.pendingBookId)
        callbacks.getValue(BookId("book"))(request("book"))

        assertEquals(listOf("2:book"), delivered)
        assertNull(router.pendingBookId)
    }

    @Test fun `accepts content PDF and EPUB view intents`() {
        assertTrue(isSupportedExternalDocument(Intent.ACTION_VIEW, "content", "application/pdf"))
        assertTrue(isSupportedExternalDocument(Intent.ACTION_VIEW, "content", "application/epub+zip"))
    }

    @Test fun `rejects non-view unsupported and non-content intents`() {
        assertFalse(isSupportedExternalDocument(Intent.ACTION_SEND, "content", "application/pdf"))
        assertFalse(isSupportedExternalDocument(Intent.ACTION_VIEW, "content", "text/plain"))
        assertFalse(isSupportedExternalDocument(Intent.ACTION_VIEW, "file", "application/pdf"))
    }

    private fun request(id: String): OpenBookRequest {
        val book = LibraryBook(BookId(id), "Book $id", 3, 0)
        return OpenBookRequest(book, File("$id.pdf"), 0)
    }
}
