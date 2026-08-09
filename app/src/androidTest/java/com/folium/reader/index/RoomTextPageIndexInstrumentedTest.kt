package com.folium.reader.index

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.text.TextBlock
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextFont
import com.folium.reader.core.text.TextLine
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextWord
import com.folium.reader.reader.TextPageLoadResult
import com.folium.reader.reader.TextPageLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RoomTextPageIndexInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: TextPageDatabase
    private lateinit var index: RoomTextPageIndex
    private val book = BookId("room-book")
    private val document = DocumentContentVersion("12".repeat(32))
    private val nativeVersion = TextEngineVersion("native-v1")
    private val ocrVersion = TextEngineVersion("ocr-v1|eng:data")
    private val databasesToDelete = mutableListOf<String>()

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(context, TextPageDatabase::class.java)
            .allowMainThreadQueries().build()
        index = RoomTextPageIndex(database)
        prepare(index, key(0, TextSource.NATIVE_PDF, nativeVersion))
        assertEquals(
            TextPageIndexWriteOutcome.APPLIED,
            index.prepareSource(book, document, TextSource.OCR, 1, ocrVersion)
        )
    }

    @After fun close() {
        index.close()
        databasesToDelete.forEach(context::deleteDatabase)
    }

    @Test fun nativeAndOcrPagesRoundTripLosslesslyIncludingGeometryMetadataAndFontOrder() {
        val native = richPage(TextSource.NATIVE_PDF)
        val ocr = richPage(TextSource.OCR)
        val nativeKey = key(0, TextSource.NATIVE_PDF, nativeVersion)
        val ocrKey = key(1, TextSource.OCR, ocrVersion)

        index.complete(nativeKey, native)
        index.complete(ocrKey, ocr)

        assertEquals(native, index.load(nativeKey))
        assertEquals(ocr, index.load(ocrKey))
    }

    @Test fun completedEmptyPageRoundTripsWithoutSyntheticWords() {
        val key = key(2, TextSource.NATIVE_PDF, nativeVersion)
        val empty = TextPage(emptyList(), TextSource.NATIVE_PDF)
        index.complete(key, empty)
        assertEquals(empty, index.load(key))
    }

    @Test fun loaderPersistsAndPublishesOnMainWithoutAllowingMainThreadRoomQueries() {
        index.close()
        val name = "room-loader-main-delivery.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        val captureQueries = AtomicBoolean()
        val queryThreads = CopyOnWriteArrayList<String>()
        database = Room.databaseBuilder(context, TextPageDatabase::class.java, name)
            .setQueryCallback({ _, _ ->
                if (captureQueries.get()) queryThreads += Thread.currentThread().name
            }, Executor { command -> command.run() })
            .build()
        index = RoomTextPageIndex.named(database, context.getDatabasePath(name).absolutePath)
        val currentKey = key(15, TextSource.NATIVE_PDF, nativeVersion)
        prepare(index, currentKey)
        captureQueries.set(true)
        val extracted = oneWordPage("workerpublication", TextSource.NATIVE_PDF)
        val extractionOnReaderText = AtomicBoolean()
        val callbackOnMain = AtomicBoolean()
        val callbackResult = AtomicReference<TextPageLoadResult>()
        val reentrantWrite = AtomicReference<TextPageIndexWriteOutcome>()
        val delivered = CountDownLatch(1)
        val loader = TextPageLoader(
            document = InstrumentedTextDocument(extracted) {
                extractionOnReaderText.set(Thread.currentThread().name == "reader-text")
            },
            pageCount = 16,
            deliver = { Handler(Looper.getMainLooper()).post(it) },
            index = index,
            indexKey = { currentKey }
        )

        try {
            loader.load(15) { result ->
                callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                reentrantWrite.set(
                    index.prepareSource(
                        currentKey.bookId,
                        currentKey.documentVersion,
                        currentKey.source,
                        currentKey.textSchemaVersion,
                        currentKey.engineVersion
                    )
                )
                callbackResult.set(result)
                delivered.countDown()
            }

            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertTrue(extractionOnReaderText.get())
            assertTrue(callbackOnMain.get())
            assertEquals(TextPageIndexWriteOutcome.REJECTED_DURING_PUBLICATION, reentrantWrite.get())
            assertEquals(TextPageLoadResult.Loaded(extracted), callbackResult.get())
            loader.dispose()
            captureQueries.set(false)
            assertTrue(queryThreads.isNotEmpty())
            assertTrue(queryThreads.none { it == Looper.getMainLooper().thread.name })
            assertEquals(extracted, index.load(currentKey))
        } finally {
            captureQueries.set(false)
            loader.dispose()
        }
    }

    @Test fun mainPublicationCallbackCanCloseRoomIndexWithoutDeadlockingLoader() {
        index.close()
        val name = "room-loader-callback-close.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        val captureQueries = AtomicBoolean()
        val queryThreads = CopyOnWriteArrayList<String>()
        val closeCount = AtomicInteger()
        val closeThread = AtomicReference<String>()
        database = Room.databaseBuilder(context, TextPageDatabase::class.java, name)
            .setQueryCallback({ _, _ ->
                if (captureQueries.get()) queryThreads += Thread.currentThread().name
            }, Executor { command -> command.run() })
            .build()
        index = RoomTextPageIndex.named(database, context.getDatabasePath(name).absolutePath) {
            closeCount.incrementAndGet()
            closeThread.set(Thread.currentThread().name)
            database.close()
        }
        val currentKey = key(16, TextSource.NATIVE_PDF, nativeVersion)
        prepare(index, currentKey)
        captureQueries.set(true)
        val callbackReturned = CountDownLatch(1)
        val callbackOnMain = AtomicBoolean()
        val callbackResult = AtomicReference<TextPageLoadResult>()
        val loader = TextPageLoader(
            document = InstrumentedTextDocument(oneWordPage("callbackclose", TextSource.NATIVE_PDF)) {},
            pageCount = 17,
            deliver = { Handler(Looper.getMainLooper()).post(it) },
            index = index,
            indexKey = { currentKey }
        )

        try {
            loader.load(16) { result ->
                callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                callbackResult.set(result)
                index.close()
                callbackReturned.countDown()
            }

            assertTrue(callbackReturned.await(5, TimeUnit.SECONDS))
            assertTrue(callbackOnMain.get())
            assertTrue(callbackResult.get() is TextPageLoadResult.Loaded)
            index.close()
            loader.dispose()
            captureQueries.set(false)

            assertEquals(1, closeCount.get())
            assertEquals("reader-text", closeThread.get())
            assertFalse(database.isOpen)
            assertTrue(queryThreads.isNotEmpty())
            assertTrue(queryThreads.none { it == Looper.getMainLooper().thread.name })
        } finally {
            captureQueries.set(false)
            loader.dispose()
            index.close()
        }
    }

    @Test fun completingOcrAtomicallyReplacesNativeGeometryAndSearchText() {
        val nativeKey = key(3, TextSource.NATIVE_PDF, nativeVersion)
        val ocrKey = key(3, TextSource.OCR, ocrVersion)
        index.complete(nativeKey, oneWordPage("nativeonly", TextSource.NATIVE_PDF))
        index.complete(ocrKey, oneWordPage("ocronly", TextSource.OCR))

        assertNull(index.load(nativeKey))
        assertEquals("ocronly", index.load(ocrKey)?.text)
        assertTrue(search(index, document, "nativeonly").isEmpty())
        assertEquals(listOf(TextPageSearchHit(3, TextSource.OCR, "ocronly")), search(index, document, "ocronly"))
    }

    @Test fun exactVersionMismatchIsNeverReturnedAndPreparationInvalidatesStaleRows() {
        val old = key(4, TextSource.NATIVE_PDF, nativeVersion)
        val changed = old.copy(engineVersion = TextEngineVersion("native-v2"))
        index.complete(old, oneWordPage("old", TextSource.NATIVE_PDF))

        assertNull(index.load(changed))
        index.prepareSource(book, document, TextSource.NATIVE_PDF, 1, changed.engineVersion)
        assertNull(index.state(old))

        index.complete(changed, oneWordPage("new", TextSource.NATIVE_PDF))
        index.prepareDocument(book, DocumentContentVersion("34".repeat(32)))
        assertNull(index.state(changed))
    }

    @Test fun interruptedAndFailedStatesCanReturnToInProgressAndComplete() {
        val interrupted = key(5, TextSource.NATIVE_PDF, nativeVersion)
        assertEquals(null, index.markInProgress(interrupted).previousState)
        assertEquals(TextPageIndexState.IN_PROGRESS, index.markInProgress(interrupted).previousState)
        index.markFailed(interrupted)
        assertEquals(TextPageIndexState.FAILED, index.markInProgress(interrupted).previousState)
        index.complete(interrupted, oneWordPage("resumed", TextSource.NATIVE_PDF))
        assertEquals(TextPageIndexState.COMPLETE, index.state(interrupted))
    }

    @Test fun persistenceSurvivesDatabaseReopen() {
        index.close()
        val name = "room-text-page-reopen.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        database = Room.databaseBuilder(context, TextPageDatabase::class.java, name).allowMainThreadQueries().build()
        index = RoomTextPageIndex(database)
        val key = key(6, TextSource.NATIVE_PDF, nativeVersion)
        val page = richPage(TextSource.NATIVE_PDF)
        prepare(index, key)
        assertEquals(TextPageIndexWriteOutcome.APPLIED, index.complete(key, page))
        index.close()

        database = Room.databaseBuilder(context, TextPageDatabase::class.java, name).allowMainThreadQueries().build()
        index = RoomTextPageIndex(database)
        assertEquals(page, index.load(key))
    }

    @Test fun staleEngineWriterCannotReplaceCurrentPageOrFtsAcrossDatabaseInstances() {
        assertStaleWriterRejected(
            name = "room-stale-engine.db",
            oldKey = key(8, TextSource.NATIVE_PDF, nativeVersion),
            currentKey = key(8, TextSource.NATIVE_PDF, TextEngineVersion("native-v2"))
        )
    }

    @Test fun staleDocumentWriterCannotReplaceCurrentPageOrFtsAcrossDatabaseInstances() {
        assertStaleWriterRejected(
            name = "room-stale-document.db",
            oldKey = key(9, TextSource.NATIVE_PDF, nativeVersion),
            currentKey = key(9, TextSource.NATIVE_PDF, nativeVersion).copy(
                documentVersion = DocumentContentVersion("78".repeat(32))
            )
        )
    }

    @Test fun staleSchemaWriterCannotReplaceCurrentPageOrFtsAcrossDatabaseInstances() {
        assertStaleWriterRejected(
            name = "room-stale-schema.db",
            oldKey = key(10, TextSource.NATIVE_PDF, nativeVersion),
            currentKey = key(10, TextSource.NATIVE_PDF, nativeVersion).copy(textSchemaVersion = 2)
        )
    }

    @Test fun ftsFencesPhysicallyPresentRowsThatDoNotMatchActiveMetadata() {
        val currentKey = key(11, TextSource.NATIVE_PDF, TextEngineVersion("native-v2"))
        prepare(index, currentKey)
        index.complete(currentKey, oneWordPage("currentfenced", currentKey.source))
        val staleKey = currentKey.copy(engineVersion = nativeVersion)
        val staleId = database.textPageDao().insertPage(
            TextPageEntity(
                bookId = staleKey.bookId.value,
                documentVersion = staleKey.documentVersion.value,
                pageIndex = staleKey.pageIndex + 1,
                source = staleKey.source.name,
                textSchemaVersion = staleKey.textSchemaVersion,
                engineVersion = staleKey.engineVersion.value,
                state = TextPageIndexState.COMPLETE.name
            )
        )
        database.textPageDao().insertSearch(TextPageSearchEntity(staleId, "stalefenced"))

        assertTrue(search(index, document, "stalefenced").isEmpty())
        assertEquals(
            listOf(TextPageSearchHit(currentKey.pageIndex, currentKey.source, "currentfenced")),
            search(index, document, "currentfenced")
        )
    }

    @Test fun sharedNamedFenceOrdersPublicationBeforeInvalidationAndRejectsItAfterward() {
        index.close()
        val name = "room-publication-fence.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        val publishingDatabase = namedDatabase(name)
        val invalidatingDatabase = namedDatabase(name)
        val publishingIndex = RoomTextPageIndex.named(publishingDatabase, name)
        val invalidatingIndex = RoomTextPageIndex.named(invalidatingDatabase, name)
        index = invalidatingIndex
        val oldKey = key(12, TextSource.NATIVE_PDF, nativeVersion)
        val currentKey = oldKey.copy(engineVersion = TextEngineVersion("native-v2"))
        prepare(publishingIndex, oldKey)
        publishingIndex.complete(oldKey, oneWordPage("beforeinvalidate", oldKey.source))
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val invalidationFinished = CountDownLatch(1)
        val publicationOutcome = AtomicReference<TextPagePublicationOutcome>()

        val publicationThread = Thread {
            publicationOutcome.set(publishingIndex.publishIfCurrent(oldKey) {
                publicationEntered.countDown()
                releasePublication.await(2, TimeUnit.SECONDS)
            })
        }
        val invalidationThread = Thread {
            publicationEntered.await(2, TimeUnit.SECONDS)
            prepare(invalidatingIndex, currentKey)
            invalidationFinished.countDown()
        }
        publicationThread.start()
        invalidationThread.start()

        assertTrue(publicationEntered.await(2, TimeUnit.SECONDS))
        assertFalse(invalidationFinished.await(100, TimeUnit.MILLISECONDS))
        releasePublication.countDown()
        publicationThread.join(2_000)
        assertTrue(invalidationFinished.await(2, TimeUnit.SECONDS))
        invalidationThread.join(2_000)
        assertEquals(TextPagePublicationOutcome.CURRENT, publicationOutcome.get())
        assertEquals(TextPagePublicationOutcome.NOT_CURRENT, publishingIndex.publishIfCurrent(oldKey) {})
        invalidatingIndex.complete(currentKey, oneWordPage("afterinvalidate", currentKey.source))
        assertEquals("afterinvalidate", invalidatingIndex.load(currentKey)?.text)
        publishingIndex.close()
    }

    @Test fun searchCallbackRejectsReentrantInvalidationAndKeepsHitsCurrentForEntireCallback() {
        index.close()
        val name = "room-search-reentrant-invalidation.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        val queryingIndex = RoomTextPageIndex.named(namedDatabase(name), name)
        val invalidatingIndex = RoomTextPageIndex.named(namedDatabase(name), name)
        index = invalidatingIndex
        val oldKey = key(13, TextSource.NATIVE_PDF, nativeVersion)
        val currentKey = oldKey.copy(engineVersion = TextEngineVersion("native-v2"))
        prepare(queryingIndex, oldKey)
        queryingIndex.complete(oldKey, oneWordPage("oldsearchhit", oldKey.source))
        val callbackHits = mutableListOf<TextPageSearchHit>()

        val outcome = queryingIndex.searchIfCurrent(book, document, "oldsearchhit") { hits ->
            callbackHits += hits
            assertEquals(
                TextPageIndexWriteOutcome.REJECTED_DURING_PUBLICATION,
                invalidatingIndex.prepareSource(
                    currentKey.bookId,
                    currentKey.documentVersion,
                    currentKey.source,
                    currentKey.textSchemaVersion,
                    currentKey.engineVersion
                )
            )
            assertEquals("oldsearchhit", queryingIndex.load(oldKey)?.text)
        }

        assertEquals(TextPagePublicationOutcome.CURRENT, outcome)
        assertEquals(listOf(TextPageSearchHit(oldKey.pageIndex, oldKey.source, "oldsearchhit")), callbackHits)
        assertEquals("oldsearchhit", queryingIndex.load(oldKey)?.text)

        prepare(invalidatingIndex, currentKey)
        invalidatingIndex.complete(currentKey, oneWordPage("currentsearchhit", currentKey.source))
        assertEquals(
            listOf(TextPageSearchHit(currentKey.pageIndex, currentKey.source, "currentsearchhit")),
            search(invalidatingIndex, document, "currentsearchhit")
        )
        queryingIndex.close()
    }

    @Test fun pagePublicationCallbackRejectsReentrantSameKeyCompletionWithoutMakingDeliveredPageStale() {
        index.close()
        val name = "room-page-reentrant-close.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        val publishingIndex = RoomTextPageIndex.named(namedDatabase(name), name)
        val invalidatingIndex = RoomTextPageIndex.named(namedDatabase(name), name)
        index = invalidatingIndex
        val oldKey = key(14, TextSource.NATIVE_PDF, nativeVersion)
        prepare(publishingIndex, oldKey)
        publishingIndex.complete(oldKey, oneWordPage("oldcallback", oldKey.source))
        val callbackReached = AtomicReference(false)

        val outcome = publishingIndex.publishIfCurrent(oldKey) {
            callbackReached.set(true)
            assertThrows(IllegalStateException::class.java) {
                invalidatingIndex.prepareDocument(oldKey.bookId, DocumentContentVersion("90".repeat(32)))
            }
            assertEquals(
                TextPageIndexWriteOutcome.REJECTED_DURING_PUBLICATION,
                invalidatingIndex.prepareSource(
                    oldKey.bookId,
                    oldKey.documentVersion,
                    oldKey.source,
                    oldKey.textSchemaVersion,
                    oldKey.engineVersion
                )
            )
            assertEquals(
                TextPageIndexWriteOutcome.REJECTED_DURING_PUBLICATION,
                invalidatingIndex.markInProgress(oldKey).outcome
            )
            assertEquals(
                TextPageIndexWriteOutcome.REJECTED_DURING_PUBLICATION,
                invalidatingIndex.complete(oldKey, oneWordPage("replacement", oldKey.source))
            )
            assertEquals(
                TextPageIndexWriteOutcome.REJECTED_DURING_PUBLICATION,
                invalidatingIndex.markFailed(oldKey)
            )
            assertEquals("oldcallback", publishingIndex.load(oldKey)?.text)
        }

        assertTrue(callbackReached.get())
        assertEquals(TextPagePublicationOutcome.CURRENT, outcome)
        assertEquals("oldcallback", invalidatingIndex.load(oldKey)?.text)
        publishingIndex.close()
    }

    @Test fun pagePublicationCallbackMayReentrantlyCloseWithoutDeadlock() {
        index.close()
        val name = "room-page-reentrant-close.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        val publishingIndex = RoomTextPageIndex.named(namedDatabase(name), name)
        index = RoomTextPageIndex.named(namedDatabase(name), name)
        val currentKey = key(14, TextSource.NATIVE_PDF, nativeVersion)
        prepare(publishingIndex, currentKey)
        publishingIndex.complete(currentKey, oneWordPage("closingcallback", currentKey.source))

        val outcome = publishingIndex.publishIfCurrent(currentKey) { publishingIndex.close() }

        assertEquals(TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION, outcome)
    }

    @Test fun invalidationCascadesHierarchyAndSearchRows() {
        val key = key(7, TextSource.NATIVE_PDF, nativeVersion)
        index.complete(key, richPage(TextSource.NATIVE_PDF))
        index.prepareDocument(book, DocumentContentVersion("56".repeat(32)))

        assertEquals(0L, count("text_pages"))
        assertEquals(0L, count("text_words"))
        assertEquals(0L, count("text_fonts"))
        assertEquals(0L, count("text_page_search"))
    }

    @Test fun schemaContainsNoRasterPixelOrBlobStorage() {
        val sqlite = database.openHelper.readableDatabase
        val tables = listOf(
            "active_text_documents",
            "active_text_sources",
            "text_pages",
            "text_words",
            "text_fonts",
            "text_page_search"
        )
        assertTrue(tables.none { it.contains("raster", true) || it.contains("pixel", true) || it.contains("image", true) })
        tables.forEach { table ->
            sqlite.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val typeIndex = cursor.getColumnIndexOrThrow("type")
                while (cursor.moveToNext()) {
                    assertTrue("$table.${cursor.getString(nameIndex)} is BLOB", !cursor.getString(typeIndex).equals("BLOB", true))
                }
            }
        }
    }

    private fun key(page: Int, source: TextSource, engine: TextEngineVersion) =
        TextPageIndexKey(book, document, page, source, 1, engine)

    private fun assertStaleWriterRejected(
        name: String,
        oldKey: TextPageIndexKey,
        currentKey: TextPageIndexKey
    ) {
        index.close()
        databasesToDelete += name
        context.deleteDatabase(name)
        val oldDatabase = namedDatabase(name)
        val currentDatabase = namedDatabase(name)
        val oldIndex = RoomTextPageIndex.named(oldDatabase, name)
        val currentIndex = RoomTextPageIndex.named(currentDatabase, name)
        index = currentIndex

        prepare(oldIndex, oldKey)
        assertEquals(TextPageIndexWriteOutcome.APPLIED, oldIndex.markInProgress(oldKey).outcome)
        prepare(currentIndex, currentKey)
        assertEquals(
            TextPageIndexWriteOutcome.APPLIED,
            currentIndex.complete(currentKey, oneWordPage("currenttext", currentKey.source))
        )

        assertEquals(
            TextPageIndexWriteOutcome.STALE,
            oldIndex.complete(oldKey, oneWordPage("staletext", oldKey.source))
        )
        assertNull(oldIndex.state(oldKey))
        assertNull(rawExact(currentDatabase, oldKey))
        assertEquals("currenttext", currentIndex.load(currentKey)?.text)
        assertTrue(search(currentIndex, currentKey.documentVersion, "staletext").isEmpty())
        assertEquals(
            listOf(TextPageSearchHit(currentKey.pageIndex, currentKey.source, "currenttext")),
            search(currentIndex, currentKey.documentVersion, "currenttext")
        )
        oldIndex.close()
    }

    private fun prepare(target: RoomTextPageIndex, key: TextPageIndexKey) {
        target.prepareDocument(key.bookId, key.documentVersion)
        assertEquals(
            TextPageIndexWriteOutcome.APPLIED,
            target.prepareSource(
                key.bookId,
                key.documentVersion,
                key.source,
                key.textSchemaVersion,
                key.engineVersion
            )
        )
    }

    private fun namedDatabase(name: String) = Room.databaseBuilder(context, TextPageDatabase::class.java, name)
        .allowMainThreadQueries()
        .build()

    private fun search(
        target: RoomTextPageIndex,
        documentVersion: DocumentContentVersion,
        query: String
    ): List<TextPageSearchHit> {
        var hits: List<TextPageSearchHit>? = null
        assertEquals(
            TextPagePublicationOutcome.CURRENT,
            target.searchIfCurrent(book, documentVersion, query) { hits = it }
        )
        return requireNotNull(hits)
    }

    private fun rawExact(target: TextPageDatabase, key: TextPageIndexKey) = target.textPageDao().exact(
        key.bookId.value,
        key.documentVersion.value,
        key.pageIndex,
        key.source.name,
        key.textSchemaVersion,
        key.engineVersion.value
    )

    private fun count(table: String): Long = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM `$table`").use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
}

private fun richPage(source: TextSource) = TextPage(
    blocks = listOf(
        TextBlock(listOf(
            TextLine(listOf(
                TextWord(
                    "First", PageSpaceRect(.05f, .1f, .25f, .2f), 0,
                    fonts = listOf(
                        TextFont("Primary", bold = true, italic = false, serif = true, monospaced = false),
                        TextFont(null, bold = false, italic = true, serif = false, monospaced = true)
                    ),
                    languageTag = "en-US", confidence = .875f
                ),
                TextWord("second", PageSpaceRect(.3f, .1f, .55f, .2f), 1)
            ), 0),
            TextLine(listOf(TextWord("third", PageSpaceRect(.05f, .3f, .3f, .4f), 0)), 1)
        ), 0),
        TextBlock(listOf(TextLine(listOf(TextWord("final", PageSpaceRect(.6f, .8f, .9f, .9f), 0)), 0)), 1)
    ),
    source = source
)

private fun oneWordPage(text: String, source: TextSource) = TextPage(
    listOf(TextBlock(listOf(TextLine(listOf(TextWord(text, PageSpaceRect(.1f, .1f, .9f, .2f), 0)), 0)), 0)),
    source
)

private class InstrumentedTextDocument(
    private val page: TextPage,
    private val onExtract: () -> Unit
) : PdfDocument {
    override val pageCount = 16
    override fun pageInfo(index: Int) = PageInfo(index, 1f, 1f, 0)
    override fun buildDisplayList(index: Int): DisplayList = object : DisplayList {
        override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal) =
            Raster(1, 1, ByteArray(4))
        override fun close() = Unit
    }
    override fun extractText(index: Int): TextPage {
        onExtract()
        return page
    }
    override fun outline(): List<OutlineEntry> = emptyList()
    override fun close() = Unit
}
