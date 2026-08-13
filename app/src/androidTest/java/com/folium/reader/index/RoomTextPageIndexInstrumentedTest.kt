package com.folium.reader.index

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
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
import com.folium.reader.core.text.NATIVE_TEXT_USABILITY_POLICY_VERSION
import com.folium.reader.core.ocr.OcrCancellationReason
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
        assertEquals(OcrTransitionOutcome.APPLIED, index.prepareOcr(ocrKey(0)))
    }

    @After fun close() {
        index.close()
        databasesToDelete.forEach(context::deleteDatabase)
    }

    @Test fun nativeAndOcrPagesRoundTripLosslesslyIncludingGeometryMetadataAndFontOrder() {
        val native = richPage(TextSource.NATIVE_PDF)
        val ocr = richPage(TextSource.OCR)
        val nativeKey = key(0, TextSource.NATIVE_PDF, nativeVersion)
        val ocrOwnership = ocrKey(1)
        val nativeEmpty = key(1, TextSource.NATIVE_PDF, nativeVersion)

        index.complete(nativeKey, native)
        index.completeNativeAndReconcile(nativeEmpty, TextPage(emptyList(), TextSource.NATIVE_PDF), ocrOwnership)
        val attempt = requireNotNull(index.claimOcr(ocrOwnership).attempt)
        assertEquals(OcrTransitionOutcome.APPLIED, index.completeOcr(attempt, ocr).outcome)

        assertEquals(native, index.load(nativeKey))
        assertEquals(ocr, index.loadSelected(nativeEmpty, ocrOwnership))
    }

    @Test fun completedEmptyPageRoundTripsWithoutSyntheticWords() {
        val key = key(2, TextSource.NATIVE_PDF, nativeVersion)
        val empty = TextPage(emptyList(), TextSource.NATIVE_PDF)
        index.complete(key, empty)
        assertEquals(empty, index.load(key))
    }

    @Test fun roomCoverageTracksSelectedWinnerAndTerminalOcrStates() {
        val nativeKey = key(12, TextSource.NATIVE_PDF, nativeVersion)
        val ownership = ocrKey(12)
        index.completeNativeAndReconcile(
            nativeKey,
            TextPage(emptyList(), TextSource.NATIVE_PDF),
            ownership
        )
        assertEquals(
            TextSearchPageCoverage.PENDING,
            index.searchCoverageIfCurrent(nativeKey, ownership)?.pages?.get(12)
        )

        val failed = requireNotNull(index.claimOcr(ownership).attempt)
        index.failOcr(failed, "recognition", retryable = true)
        assertEquals(
            TextSearchPageCoverage.FAILED,
            index.searchCoverageIfCurrent(nativeKey, ownership)?.pages?.get(12)
        )

        index.retryOcr(ownership)
        val completed = requireNotNull(index.claimOcr(ownership).attempt)
        index.completeOcr(completed, TextPage(emptyList(), TextSource.OCR))
        assertEquals(
            TextSearchPageCoverage.PROCESSED,
            index.searchCoverageIfCurrent(nativeKey, ownership)?.pages?.get(12)
        )
        assertEquals(TextSource.OCR, index.loadSelected(nativeKey, ownership)?.source)
        assertTrue(index.planOcr(ownership, 12, 11, 13, 1).pageIndexes.isEmpty())
    }

    @Test fun coverageSnapshotRejectedWhenCloseWinsBeforeCoverageLock() {
        index.close()
        database = Room.inMemoryDatabaseBuilder(context, TextPageDatabase::class.java)
            .allowMainThreadQueries().build()
        val coverageEntered = CountDownLatch(1)
        val releaseCoverage = CountDownLatch(1)
        index = RoomTextPageIndex(
            database,
            beforeCoverageLock = {
                coverageEntered.countDown()
                releaseCoverage.await(2, TimeUnit.SECONDS)
            }
        )
        val nativeKey = key(13, TextSource.NATIVE_PDF, nativeVersion)
        val ownership = ocrKey(13)
        prepare(index, nativeKey)
        index.prepareOcr(ownership)
        index.completeNativeAndReconcile(
            nativeKey,
            oneWordPage("native", TextSource.NATIVE_PDF),
            ownership
        )
        val snapshot = AtomicReference<TextSearchCoverageSnapshot?>()
        val coverageThread = Thread {
            snapshot.set(index.searchCoverageIfCurrent(nativeKey, ownership))
        }
        coverageThread.start()
        assertTrue(coverageEntered.await(2, TimeUnit.SECONDS))

        index.close()
        releaseCoverage.countDown()
        coverageThread.join(2_000)

        assertFalse(coverageThread.isAlive)
        assertNull(snapshot.get())
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
        val currentOcrKey = ocrKey(15)
        index.prepareOcr(currentOcrKey)
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
            indexKey = { currentKey },
            ocrKey = { currentOcrKey }
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
            val statusDelivered = CountDownLatch(1)
            val statusOnMain = AtomicBoolean()
            loader.ocrStatus(15) {
                statusOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                statusDelivered.countDown()
            }
            assertTrue(statusDelivered.await(5, TimeUnit.SECONDS))
            assertTrue(statusOnMain.get())
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

    @Test fun searchSqlCallbackNeverRunsOnAndroidMain() {
        index.close()
        val name = "room-search-off-main.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        val searchThreads = CopyOnWriteArrayList<String>()
        database = Room.databaseBuilder(context, TextPageDatabase::class.java, name)
            .setQueryCallback({ sql, _ ->
                if (sql.trimStart().startsWith("SELECT", ignoreCase = true) &&
                    sql.contains("text_page_grams", ignoreCase = true)) {
                    searchThreads += Thread.currentThread().name
                }
            }, Executor { command -> command.run() })
            .build()
        index = RoomTextPageIndex.named(database, context.getDatabasePath(name).absolutePath)
        val current = key(0, TextSource.NATIVE_PDF, nativeVersion)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        Thread({
            try {
                prepare(index, current)
                index.complete(current, oneWordPage("banana", current.source))
                assertEquals(2, search(index, document, "ana").size)
            } catch (caught: Throwable) {
                failure.set(caught)
            } finally {
                completed.countDown()
            }
        }, "room-search-worker").start()

        assertTrue(completed.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError(it) }
        assertTrue(searchThreads.isNotEmpty())
        assertTrue(searchThreads.none { it == Looper.getMainLooper().thread.name })
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

    @Test fun usableNativeAtomicallySuppressesCompletedOcrGeometryAndSearchText() {
        val nativeKey = key(3, TextSource.NATIVE_PDF, nativeVersion)
        val ownership = ocrKey(3)
        index.completeNativeAndReconcile(nativeKey, TextPage(emptyList(), TextSource.NATIVE_PDF), ownership)
        val attempt = requireNotNull(index.claimOcr(ownership).attempt)
        index.completeOcr(attempt, oneWordPage("ocronly", TextSource.OCR))

        index.completeNativeAndReconcile(nativeKey, oneWordPage("nativeonly", TextSource.NATIVE_PDF), ownership)

        assertEquals("nativeonly", index.loadSelected(nativeKey, ownership)?.text)
        assertEquals(OcrPageState.CANCELLED, index.ocrStatus(ownership)?.state)
        assertEquals(listOf(expectedHit(3, TextSource.NATIVE_PDF, "nativeonly")), search(index, document, "nativeonly"))
        assertTrue(search(index, document, "ocronly").isEmpty())
    }

    @Test fun unusableNativeAllowsCompletedOcrAsSingleLoadAndSearchWinner() {
        val nativeKey = key(4, TextSource.NATIVE_PDF, nativeVersion)
        val ownership = ocrKey(4)
        index.completeNativeAndReconcile(nativeKey, oneWordPage("!?", TextSource.NATIVE_PDF), ownership)
        val attempt = requireNotNull(index.claimOcr(ownership).attempt)
        index.completeOcr(attempt, oneWordPage("recognized", TextSource.OCR))

        assertEquals("recognized", index.loadSelected(nativeKey, ownership)?.text)
        assertEquals(listOf(expectedHit(4, TextSource.OCR, "recognized")), search(index, document, "recognized"))
        assertTrue(search(index, document, "!?").isEmpty())
    }

    @Test fun nativeTakeoverAfterOcrMatchCollectionSuppressesStaleSearchPublication() {
        index.close()
        database = Room.inMemoryDatabaseBuilder(context, TextPageDatabase::class.java)
            .allowMainThreadQueries().build()
        val nativeKey = key(5, TextSource.NATIVE_PDF, nativeVersion)
        val ownership = ocrKey(5)
        index = RoomTextPageIndex(database, beforeSearchPublication = {
            assertEquals(
                TextPageIndexWriteOutcome.APPLIED,
                index.completeNativeAndReconcile(
                    nativeKey,
                    oneWordPage("native-takeover", TextSource.NATIVE_PDF),
                    ownership
                )
            )
        })
        prepare(index, nativeKey)
        index.prepareOcr(ownership)
        index.completeNativeAndReconcile(nativeKey, oneWordPage("!?", TextSource.NATIVE_PDF), ownership)
        val attempt = requireNotNull(index.claimOcr(ownership).attempt)
        index.completeOcr(attempt, oneWordPage("recognized", TextSource.OCR))
        assertEquals(TextSource.OCR, index.loadSelected(nativeKey, ownership)?.source)
        var published = false

        val outcome = index.searchIfCurrent(book, document, "recognized") { published = true }

        assertEquals(TextPagePublicationOutcome.NOT_CURRENT, outcome)
        assertFalse(published)
        assertEquals(TextSource.NATIVE_PDF, index.loadSelected(nativeKey, ownership)?.source)
        assertEquals("native-takeover", index.loadSelected(nativeKey, ownership)?.text)
    }

    @Test fun incompatibleNativeAndOcrKeysRejectEveryDimensionWithoutRoomMutation() {
        val nativeKey = key(6, TextSource.NATIVE_PDF, nativeVersion)
        val ownership = ocrKey(6)
        index.completeNativeAndReconcile(
            nativeKey, oneWordPage("before", TextSource.NATIVE_PDF), ownership
        )
        val statusBefore = index.ocrStatus(ownership)
        val mismatches = listOf(
            ownership.copy(bookId = BookId("other-book")),
            ownership.copy(documentVersion = DocumentContentVersion("cd".repeat(32))),
            ownership.copy(pageIndex = 7),
            ownership.copy(textSchemaVersion = nativeKey.textSchemaVersion + 1),
            ownership.copy(nativeEngineVersion = TextEngineVersion("native-v2"))
        )

        mismatches.forEach { incompatible ->
            assertFalse(nativeKey.isCompatibleNativeOwner(incompatible))
            assertEquals(TextPageIndexWriteOutcome.STALE,
                index.completeNativeAndReconcile(
                    nativeKey, oneWordPage("mutated", TextSource.NATIVE_PDF), incompatible
                ))
            assertEquals("before", index.loadSelected(nativeKey, ownership)?.text)
            assertEquals(statusBefore, index.ocrStatus(ownership))
            assertTrue(search(index, document, "mutated").isEmpty())
            assertEquals(1, search(index, document, "before").size)
        }
    }

    @Test fun selectedPublicationMovesFromUnusableNativeToCompletedOcrInRoom() {
        val nativeKey = key(7, TextSource.NATIVE_PDF, nativeVersion)
        val ownership = ocrKey(7)
        index.completeNativeAndReconcile(
            nativeKey, TextPage(emptyList(), TextSource.NATIVE_PDF), ownership
        )
        assertEquals(TextPagePublicationOutcome.CURRENT, index.publishIfSelected(nativeKey) {})
        val attempt = requireNotNull(index.claimOcr(ownership).attempt)
        index.completeOcr(attempt, oneWordPage("recognized", TextSource.OCR))

        assertEquals(TextPagePublicationOutcome.NOT_CURRENT, index.publishIfSelected(nativeKey) {})
        assertEquals(TextPagePublicationOutcome.CURRENT,
            index.publishIfSelected(ownership.textKey()) {})
    }

    @Test fun searchIsUnicodeAccentCaseInsensitiveLiteralAndOccurrenceOrdered() {
        val first = key(0, TextSource.NATIVE_PDF, nativeVersion)
        val second = key(2, TextSource.NATIVE_PDF, nativeVersion)
        index.complete(first, oneWordPage("Café café", first.source))
        index.complete(second, oneWordPage("CAFÉ", second.source))

        val hits = search(index, document, "cAfÉ")

        assertEquals(listOf(0, 0, 2), hits.map { it.pageIndex })
        assertEquals(listOf(0, 1, 0), hits.map { it.occurrenceIndex })
        assertTrue(hits.all { it.wordRange == 0..0 })
        assertTrue(hits.all { it.boxes == listOf(PageSpaceRect(.1f, .1f, .9f, .2f)) })
        assertEquals(listOf("Café café", "Café café", "CAFÉ"), hits.map { it.snippet })
    }

    @Test fun searchDoesNotReturnMatchingUnusableNativeWhenCompletedOcrWins() {
        val nativeKey = key(8, TextSource.NATIVE_PDF, nativeVersion)
        val ownership = ocrKey(8)
        assertEquals(OcrTransitionOutcome.APPLIED, index.prepareOcr(ownership))
        index.completeNativeAndReconcile(nativeKey, oneWordPage("…!?", TextSource.NATIVE_PDF), ownership)
        val attempt = requireNotNull(index.claimOcr(ownership).attempt)
        index.completeOcr(attempt, oneWordPage("selectedocr", TextSource.OCR))

        assertEquals(TextPagePublicationOutcome.NOT_CURRENT, index.publishIfSelected(nativeKey) {})
        assertEquals(TextPagePublicationOutcome.CURRENT,
            index.publishIfSelected(ownership.textKey()) {})
        assertTrue(search(index, document, "…").isEmpty())
        assertEquals(
            listOf(expectedHit(nativeKey.pageIndex, TextSource.OCR, "selectedocr")),
            search(index, document, "selectedocr")
        )
    }

    @Test fun unusableNativeSearchRemainsSelectedFallbackUntilExactOcrCompletes() {
        fun assertFallback(pageIndex: Int, text: String, prepareState: (OcrPageKey) -> Unit) {
            val nativeKey = key(pageIndex, TextSource.NATIVE_PDF, nativeVersion)
            val ownership = ocrKey(pageIndex)
            index.complete(nativeKey, oneWordPage(text, TextSource.NATIVE_PDF))
            prepareState(ownership)

            assertEquals(TextPagePublicationOutcome.CURRENT, index.publishIfSelected(nativeKey) {})
            assertEquals(
                listOf(expectedHit(pageIndex, TextSource.NATIVE_PDF, text)),
                search(index, document, text)
            )
        }

        assertFallback(20, "§") {}
        assertFallback(21, "¶") { ownership ->
            assertEquals(OcrTransitionOutcome.APPLIED, index.prepareOcr(ownership))
            index.completeNativeAndReconcile(
                key(21, TextSource.NATIVE_PDF, nativeVersion),
                oneWordPage("¶", TextSource.NATIVE_PDF),
                ownership
            )
            assertEquals(OcrPageState.QUEUED, index.ocrStatus(ownership)?.state)
        }
        assertFallback(22, "†") { ownership ->
            assertEquals(OcrTransitionOutcome.APPLIED, index.prepareOcr(ownership))
            index.completeNativeAndReconcile(
                key(22, TextSource.NATIVE_PDF, nativeVersion),
                oneWordPage("†", TextSource.NATIVE_PDF),
                ownership
            )
            requireNotNull(index.claimOcr(ownership).attempt)
            assertEquals(OcrPageState.RUNNING, index.ocrStatus(ownership)?.state)
        }
        assertFallback(23, "‡") { ownership ->
            assertEquals(OcrTransitionOutcome.APPLIED, index.prepareOcr(ownership))
            index.completeNativeAndReconcile(
                key(23, TextSource.NATIVE_PDF, nativeVersion),
                oneWordPage("‡", TextSource.NATIVE_PDF),
                ownership
            )
            val attempt = requireNotNull(index.claimOcr(ownership).attempt)
            index.failOcr(attempt, "recognition", retryable = true)
            assertEquals(OcrPageState.FAILED, index.ocrStatus(ownership)?.state)
        }
        assertFallback(24, "※") { ownership ->
            assertEquals(OcrTransitionOutcome.APPLIED, index.prepareOcr(ownership))
            index.completeNativeAndReconcile(
                key(24, TextSource.NATIVE_PDF, nativeVersion),
                oneWordPage("※", TextSource.NATIVE_PDF),
                ownership
            )
            val attempt = requireNotNull(index.claimOcr(ownership).attempt)
            index.cancelOcr(attempt)
            assertEquals(OcrPageState.CANCELLED, index.ocrStatus(ownership)?.state)
        }
    }

    @Test fun searchDoesNotReturnMatchingOcrWhenUsableNativeWins() {
        val nativeKey = key(9, TextSource.NATIVE_PDF, nativeVersion)
        val ownership = ocrKey(9)
        assertEquals(OcrTransitionOutcome.APPLIED, index.prepareOcr(ownership))
        index.completeNativeAndReconcile(
            nativeKey, TextPage(emptyList(), TextSource.NATIVE_PDF), ownership
        )
        val attempt = requireNotNull(index.claimOcr(ownership).attempt)
        index.completeOcr(attempt, oneWordPage("losingocr", TextSource.OCR))
        index.complete(nativeKey, oneWordPage("selectednative", TextSource.NATIVE_PDF))

        assertTrue(search(index, document, "losingocr").isEmpty())
        assertEquals(
            listOf(expectedHit(nativeKey.pageIndex, TextSource.NATIVE_PDF, "selectednative")),
            search(index, document, "selectednative")
        )
    }

    @Test fun userFtsOperatorsQuotesAndPunctuationAreEscapedAndNeverThrow() {
        val current = key(1, TextSource.NATIVE_PDF, nativeVersion)
        index.complete(current, oneWordPage("literal OR \"quoted\" * value", current.source))

        assertEquals(1, search(index, document, "OR \"quoted\" *").size)
        assertTrue(search(index, document, "\" OR NOT (").isEmpty())
    }

    @Test fun substringCandidatesIncludeExactAndInfixTokensWithEveryOrderedOccurrence() {
        val exact = key(0, TextSource.NATIVE_PDF, nativeVersion)
        val infix = key(1, TextSource.NATIVE_PDF, nativeVersion)
        val accented = key(2, TextSource.NATIVE_PDF, nativeVersion)
        index.complete(exact, oneWordPage("ana", exact.source))
        index.complete(infix, oneWordPage("banana", infix.source))
        index.complete(accented, oneWordPage("BÁNANA! ana", accented.source))

        val hits = search(index, document, "ÁnA")

        assertEquals(listOf(0, 1, 1, 2, 2, 2), hits.map { it.pageIndex })
        assertEquals(listOf(0, 0, 1, 0, 1, 2), hits.map { it.occurrenceIndex })
        assertEquals(1, search(index, document, "ANA!").size)
    }

    @Test fun transientAndRoomUseIdenticalPostValidationMatches() {
        val pages = listOf(
            key(4, TextSource.NATIVE_PDF, nativeVersion) to "ana",
            key(5, TextSource.NATIVE_PDF, nativeVersion) to "banana",
            key(6, TextSource.NATIVE_PDF, nativeVersion) to "Bánana! ana"
        )
        pages.forEach { (key, text) -> index.complete(key, oneWordPage(text, key.source)) }
        val transient = TransientTextPageIndex()
        transient.prepareDocument(book, document)
        val current = pages.first().first
        transient.prepareSource(book, document, current.source, current.textSchemaVersion, current.engineVersion)
        pages.forEach { (key, text) -> transient.complete(key, oneWordPage(text, key.source)) }
        try {
            listOf("ANA", "ana!", "\" OR *").forEach { query ->
                assertEquals(search(index, document, query), search(transient, document, query))
            }
        } finally {
            transient.close()
        }
    }

    @Test fun migrationOneToTwoPreservesActiveDocumentAndClearsDerivedTextState() {
        val name = "room-migration-1-2.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            TextPageDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory()
        )
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO active_text_documents(book_id,document_version,text_schema_version) VALUES('room-book','${document.value}',1)")
            close()
        }

        helper.runMigrationsAndValidate(name, 2, true, TextPageDatabase.MIGRATION_1_2).use { migrated ->
            migrated.query("SELECT document_version,text_schema_version FROM active_text_documents WHERE book_id='room-book'").use {
                assertTrue(it.moveToFirst())
                assertEquals(document.value, it.getString(0))
                assertTrue(it.isNull(1))
            }
            migrated.query("SELECT COUNT(*) FROM text_pages").use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
        }
    }

    @Test fun migrationTwoToThreePreservesCurrentNativeRowsAndAddsEmptyOcrStateTable() {
        val name = "room-migration-2-3.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(), TextPageDatabase::class.java,
            emptyList(), FrameworkSQLiteOpenHelperFactory()
        )
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO active_text_documents(book_id,document_version,text_schema_version) VALUES('room-book','${document.value}',1)")
            execSQL("INSERT INTO active_text_sources(book_id,source,document_version,text_schema_version,engine_version) VALUES('room-book','NATIVE_PDF','${document.value}',1,'${nativeVersion.value}')")
            execSQL("INSERT INTO text_pages(id,book_id,document_version,page_index,source,text_schema_version,engine_version,state) VALUES(7,'room-book','${document.value}',0,'NATIVE_PDF',1,'${nativeVersion.value}','COMPLETE')")
            close()
        }

        helper.runMigrationsAndValidate(name, 3, true, TextPageDatabase.MIGRATION_2_3).use { migrated ->
            migrated.query("SELECT state FROM text_pages WHERE id=7").use {
                assertTrue(it.moveToFirst())
                assertEquals("COMPLETE", it.getString(0))
            }
            migrated.query("SELECT COUNT(*) FROM ocr_page_states").use {
                assertTrue(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
            migrated.query("SELECT native_engine_version,usability_policy_version FROM active_text_sources").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
                assertTrue(it.isNull(1))
            }
            migrated.query("PRAGMA table_info(ocr_page_states)").use { columns ->
                val name = columns.getColumnIndexOrThrow("name")
                val names = mutableSetOf<String>()
                while (columns.moveToNext()) names += columns.getString(name)
                assertTrue("cancellation_reason" in names)
            }
        }
    }

    @Test fun migrationThreeToFourPreservesNativeOcrAndStateWhileLeavingDerivedMetadataEmpty() {
        val name = "room-migration-3-4.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(), TextPageDatabase::class.java,
            emptyList(), FrameworkSQLiteOpenHelperFactory()
        )
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO active_text_documents(book_id,document_version,text_schema_version) VALUES('room-book','${document.value}',2)")
            execSQL("INSERT INTO active_text_sources(book_id,source,document_version,text_schema_version,engine_version,native_engine_version,usability_policy_version) VALUES('room-book','NATIVE_PDF','${document.value}',2,'${nativeVersion.value}',NULL,NULL)")
            execSQL("INSERT INTO active_text_sources(book_id,source,document_version,text_schema_version,engine_version,native_engine_version,usability_policy_version) VALUES('room-book','OCR','${document.value}',2,'${ocrVersion.value}','${nativeVersion.value}','policy-v1')")
            execSQL("INSERT INTO text_pages(id,book_id,document_version,page_index,source,text_schema_version,engine_version,state) VALUES(7,'room-book','${document.value}',0,'NATIVE_PDF',2,'${nativeVersion.value}','COMPLETE')")
            execSQL("INSERT INTO text_pages(id,book_id,document_version,page_index,source,text_schema_version,engine_version,state) VALUES(8,'room-book','${document.value}',0,'OCR',2,'${ocrVersion.value}','COMPLETE')")
            execSQL("INSERT INTO text_words(page_id,block_ordinal,line_ordinal,word_ordinal,text,left,top,right,bottom,language_tag,confidence) VALUES(7,0,0,0,'native',0,0,1,1,NULL,NULL)")
            execSQL("INSERT INTO text_words(page_id,block_ordinal,line_ordinal,word_ordinal,text,left,top,right,bottom,language_tag,confidence) VALUES(8,0,0,0,'ocr',0,0,1,1,NULL,NULL)")
            execSQL("INSERT INTO ocr_page_states(book_id,document_version,page_index,text_schema_version,native_engine_version,usability_policy_version,ocr_engine_version,generation,state,cancellation_reason,failure_kind,retryable) VALUES('room-book','${document.value}',0,2,'${nativeVersion.value}','policy-v1','${ocrVersion.value}',3,'COMPLETED',NULL,NULL,NULL)")
            close()
        }

        helper.runMigrationsAndValidate(name, 4, true, TextPageDatabase.MIGRATION_3_4).use { migrated ->
            migrated.query("SELECT COUNT(*) FROM text_pages").use { it.moveToFirst(); assertEquals(2L, it.getLong(0)) }
            migrated.query("SELECT COUNT(*) FROM text_words").use { it.moveToFirst(); assertEquals(2L, it.getLong(0)) }
            migrated.query("SELECT generation,state FROM ocr_page_states").use {
                assertTrue(it.moveToFirst()); assertEquals(3L, it.getLong(0)); assertEquals("COMPLETED", it.getString(1))
            }
            migrated.query("SELECT DISTINCT native_usability FROM text_pages").use {
                assertTrue(it.moveToFirst()); assertEquals("UNKNOWN", it.getString(0))
            }
            migrated.query("SELECT COUNT(*) FROM text_page_grams").use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
        }
    }

    @Test fun migrationFourToFivePreservesOcrStateAndAddsRangePlanningIndex() {
        val name = "room-migration-4-5.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(), TextPageDatabase::class.java,
            emptyList(), FrameworkSQLiteOpenHelperFactory()
        )
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO ocr_page_states(book_id,document_version,page_index,text_schema_version,native_engine_version,usability_policy_version,ocr_engine_version,generation,state,cancellation_reason,failure_kind,retryable) VALUES('room-book','${document.value}',9,2,'${nativeVersion.value}','policy-v1','${ocrVersion.value}',3,'QUEUED',NULL,NULL,NULL)")
            close()
        }

        helper.runMigrationsAndValidate(name, 5, true, TextPageDatabase.MIGRATION_4_5).use { migrated ->
            migrated.query("SELECT generation,state FROM ocr_page_states WHERE page_index=9").use {
                assertTrue(it.moveToFirst())
                assertEquals(3L, it.getLong(0))
                assertEquals(OcrPageState.QUEUED.name, it.getString(1))
            }
            migrated.query("PRAGMA index_list(ocr_page_states)").use { indexes ->
                val indexName = indexes.getColumnIndexOrThrow("name")
                val names = mutableSetOf<String>()
                while (indexes.moveToNext()) names += indexes.getString(indexName)
                assertTrue("index_ocr_page_states_planning" in names)
            }
        }
    }

    @Test fun ocrPlanningQueryPlanUsesCompositeRangeIndexWithoutScanOrTempSort() {
        listOf(
            "state='QUEUED' AND cancellation_reason IS NULL",
            "state='CANCELLED' AND cancellation_reason='SEARCH_PAUSE'"
        ).forEach { stateClause ->
            val details = mutableListOf<String>()
            database.openHelper.readableDatabase.query("""
                EXPLAIN QUERY PLAN
                SELECT * FROM ocr_page_states INDEXED BY index_ocr_page_states_planning
                WHERE book_id='${book.value}' AND document_version='${document.value}'
                    AND text_schema_version=1 AND native_engine_version='${nativeVersion.value}'
                    AND usability_policy_version='policy-v1' AND ocr_engine_version='${ocrVersion.value}'
                    AND $stateClause AND page_index>20 AND page_index<50
                ORDER BY page_index LIMIT 16
            """.trimIndent()).use { plan ->
                val detail = plan.getColumnIndexOrThrow("detail")
                while (plan.moveToNext()) details += plan.getString(detail)
            }

            assertTrue(details.any { it.contains("index_ocr_page_states_planning") })
            assertTrue(details.none { it.contains("SCAN", ignoreCase = true) })
            assertTrue(details.none { it.contains("TEMP B-TREE", ignoreCase = true) })
        }
    }

    @Test fun migratedUnknownNativeBlocksOcrUntilCompactMaintenanceSelectsUsableNative() {
        index.close()
        val name = "room-migration-unknown-winner.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(), TextPageDatabase::class.java,
            emptyList(), FrameworkSQLiteOpenHelperFactory()
        )
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO active_text_documents(book_id,document_version,text_schema_version) VALUES('room-book','${document.value}',2)")
            execSQL("INSERT INTO active_text_sources(book_id,source,document_version,text_schema_version,engine_version,native_engine_version,usability_policy_version) VALUES('room-book','NATIVE_PDF','${document.value}',2,'${nativeVersion.value}',NULL,NULL)")
            execSQL("INSERT INTO active_text_sources(book_id,source,document_version,text_schema_version,engine_version,native_engine_version,usability_policy_version) VALUES('room-book','OCR','${document.value}',2,'${ocrVersion.value}','${nativeVersion.value}','policy-v1')")
            execSQL("INSERT INTO text_pages(id,book_id,document_version,page_index,source,text_schema_version,engine_version,state) VALUES(71,'room-book','${document.value}',0,'NATIVE_PDF',2,'${nativeVersion.value}','COMPLETE')")
            execSQL("INSERT INTO text_pages(id,book_id,document_version,page_index,source,text_schema_version,engine_version,state) VALUES(72,'room-book','${document.value}',0,'OCR',2,'${ocrVersion.value}','COMPLETE')")
            execSQL("INSERT INTO text_words(page_id,block_ordinal,line_ordinal,word_ordinal,text,left,top,right,bottom,language_tag,confidence) VALUES(71,0,0,0,'nativewinner',0,0,1,1,NULL,NULL)")
            execSQL("INSERT INTO text_words(page_id,block_ordinal,line_ordinal,word_ordinal,text,left,top,right,bottom,language_tag,confidence) VALUES(72,0,0,0,'ocrwinner',0,0,1,1,NULL,NULL)")
            execSQL("INSERT INTO text_page_search(rowid,page_text,normalized_text) VALUES(71,'nativewinner','NATIVEWINNER')")
            execSQL("INSERT INTO text_page_search(rowid,page_text,normalized_text) VALUES(72,'ocrwinner','OCRWINNER')")
            execSQL("INSERT INTO ocr_page_states(book_id,document_version,page_index,text_schema_version,native_engine_version,usability_policy_version,ocr_engine_version,generation,state,cancellation_reason,failure_kind,retryable) VALUES('room-book','${document.value}',0,2,'${nativeVersion.value}','policy-v1','${ocrVersion.value}',3,'COMPLETED',NULL,NULL,NULL)")
            execSQL("INSERT INTO text_pages(id,book_id,document_version,page_index,source,text_schema_version,engine_version,state) VALUES(73,'room-book','${document.value}',1,'NATIVE_PDF',2,'${nativeVersion.value}','COMPLETE')")
            execSQL("INSERT INTO text_pages(id,book_id,document_version,page_index,source,text_schema_version,engine_version,state) VALUES(74,'room-book','${document.value}',1,'OCR',2,'${ocrVersion.value}','COMPLETE')")
            execSQL("INSERT INTO text_words(page_id,block_ordinal,line_ordinal,word_ordinal,text,left,top,right,bottom,language_tag,confidence) VALUES(73,0,0,0,'§',0,0,1,1,NULL,NULL)")
            execSQL("INSERT INTO text_words(page_id,block_ordinal,line_ordinal,word_ordinal,text,left,top,right,bottom,language_tag,confidence) VALUES(74,0,0,0,'ocrwinner',0,0,1,1,NULL,NULL)")
            execSQL("INSERT INTO text_page_search(rowid,page_text,normalized_text) VALUES(73,'§','§')")
            execSQL("INSERT INTO text_page_search(rowid,page_text,normalized_text) VALUES(74,'ocrwinner','OCRWINNER')")
            execSQL("INSERT INTO ocr_page_states(book_id,document_version,page_index,text_schema_version,native_engine_version,usability_policy_version,ocr_engine_version,generation,state,cancellation_reason,failure_kind,retryable) VALUES('room-book','${document.value}',1,2,'${nativeVersion.value}','policy-v1','${ocrVersion.value}',4,'COMPLETED',NULL,NULL,NULL)")
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true, TextPageDatabase.MIGRATION_3_4).close()
        database = namedDatabase(name)
        val maintenanceEntered = CountDownLatch(1)
        val releaseMaintenance = CountDownLatch(1)
        val coverageCalls = AtomicInteger()
        index = RoomTextPageIndex(
            database,
            beforeCoverageLock = { coverageCalls.incrementAndGet() },
            beforeDerivedMaintenance = {
                maintenanceEntered.countDown()
                releaseMaintenance.await(2, TimeUnit.SECONDS)
            }
        )
        val nativeKey = TextPageIndexKey(book, document, 0, TextSource.NATIVE_PDF, 2, nativeVersion)
        val ownership = OcrPageKey(book, document, 0, 2, nativeVersion, "policy-v1", ocrVersion)
        val callbacks = CopyOnWriteArrayList<com.folium.reader.reader.TextSearchProgress>()
        val initial = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val loader = TextPageLoader(
            InstrumentedTextDocument(oneWordPage("unused", TextSource.NATIVE_PDF)) {
                error("legacy COMPLETE page must not be extracted")
            },
            2,
            deliver = { it() },
            index = index,
            indexKey = { nativeKey.copy(pageIndex = it) },
            ocrKey = { ownership.copy(pageIndex = it) }
        )
        loader.search("winner") { progress ->
            callbacks += progress
            initial.countDown()
            if (!progress.running) terminal.countDown()
        }
        assertTrue(maintenanceEntered.await(2, TimeUnit.SECONDS))
        assertTrue(initial.await(2, TimeUnit.SECONDS))
        assertEquals(1, callbacks.size)
        assertTrue(callbacks.single().running)
        assertEquals(0, callbacks.single().indexedPages)
        assertEquals(2, callbacks.single().incompletePages)
        assertTrue(callbacks.single().matches.isEmpty())
        releaseMaintenance.countDown()
        assertTrue(terminal.await(2, TimeUnit.SECONDS))

        assertEquals(2, callbacks.size)
        assertEquals(2, callbacks.last().indexedPages)
        assertEquals(0, callbacks.last().incompletePages)
        assertEquals(
            listOf(TextSource.NATIVE_PDF, TextSource.OCR),
            callbacks.last().matches.map { it.source }
        )
        assertEquals(1, coverageCalls.get())
        assertEquals(
            listOf(1),
            search(index, document, "ocrwinner").map(TextPageSearchHit::pageIndex)
        )
        loader.dispose()
    }

    @Test fun derivedMaintenanceUsesConstantBulkReadsForOneAndThirtyTwoUnknownPages() {
        val readCounts = listOf(1, 32).map { pageCount ->
            index.close()
            database = Room.inMemoryDatabaseBuilder(context, TextPageDatabase::class.java)
                .allowMainThreadQueries().build()
            val reads = AtomicInteger()
            index = RoomTextPageIndex(database, onDerivedMaintenanceRead = reads::incrementAndGet)
            val nativeKey = key(0, TextSource.NATIVE_PDF, nativeVersion)
            prepare(index, nativeKey)
            assertEquals(OcrTransitionOutcome.APPLIED, index.prepareOcr(ocrKey(0)))
            val sqlite = database.openHelper.writableDatabase
            repeat(pageCount) { pageIndex ->
                val id = pageIndex + 1L
                sqlite.execSQL("INSERT INTO text_pages(id,book_id,document_version,page_index,source,text_schema_version,engine_version,state,native_usability) VALUES($id,'${book.value}','${document.value}',$pageIndex,'NATIVE_PDF',1,'${nativeVersion.value}','COMPLETE','UNKNOWN')")
                sqlite.execSQL("INSERT INTO text_page_search(rowid,page_text,normalized_text) VALUES($id,'usable','USABLE')")
            }

            val result = index.maintainDerivedDataBatch(nativeKey, ocrKey(0))

            assertEquals(
                (0 until pageCount).associateWith { TextSearchPageCoverage.PROCESSED },
                requireNotNull(result.coverage).pages
            )
            reads.get()
        }

        assertEquals(listOf(5, 5), readCounts)
    }

    @Test fun maintenanceMutationAndConcurrentSearchPublishOneAuthoritativeRevision() {
        index.close()
        database = Room.inMemoryDatabaseBuilder(context, TextPageDatabase::class.java)
            .allowMainThreadQueries().build()
        val mutationApplied = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        index = RoomTextPageIndex(database, afterDerivedMaintenanceMutation = {
            mutationApplied.countDown()
            releaseSnapshot.await(2, TimeUnit.SECONDS)
        })
        val nativeKey = key(0, TextSource.NATIVE_PDF, nativeVersion)
        prepare(index, nativeKey)
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("INSERT INTO text_pages(id,book_id,document_version,page_index,source,text_schema_version,engine_version,state,native_usability) VALUES(1,'${book.value}','${document.value}',0,'NATIVE_PDF',1,'${nativeVersion.value}','COMPLETE','UNKNOWN')")
        sqlite.execSQL("INSERT INTO text_words(page_id,block_ordinal,line_ordinal,word_ordinal,text,left,top,right,bottom,language_tag,confidence) VALUES(1,0,0,0,'needle',0,0,1,1,NULL,NULL)")
        sqlite.execSQL("INSERT INTO text_page_search(rowid,page_text,normalized_text) VALUES(1,'needle','NEEDLE')")
        val maintenance = AtomicReference<DerivedMaintenanceResult>()
        val search = AtomicReference<TextPageSearchResult>()
        val maintenanceThread = Thread {
            maintenance.set(index.maintainDerivedDataBatch(nativeKey, null))
        }
        val searchThread = Thread {
            index.searchIfCurrent(book, document, "needle") { search.set(it) }
        }

        maintenanceThread.start()
        assertTrue(mutationApplied.await(2, TimeUnit.SECONDS))
        searchThread.start()
        Thread.sleep(50)
        assertTrue(searchThread.isAlive)
        assertNull(search.get())
        releaseSnapshot.countDown()
        maintenanceThread.join(2_000)
        searchThread.join(2_000)

        val maintenanceResult = requireNotNull(maintenance.get())
        val searchResult = requireNotNull(search.get())
        assertEquals(maintenanceResult.coverage?.revision, searchResult.coverage?.revision)
        assertEquals(TextSearchPageCoverage.PROCESSED, searchResult.coverage?.pages?.get(0))
        assertEquals(listOf(0), searchResult.hits.map(TextPageSearchHit::pageIndex))
    }

    @Test fun invalidationChunksMoreThanFifteenHundredNativeAndOcrRowsWithChildren() {
        val sqlite = database.openHelper.writableDatabase
        sqlite.beginTransaction()
        try {
            repeat(801) { page ->
                listOf(TextSource.NATIVE_PDF to nativeVersion, TextSource.OCR to ocrVersion).forEachIndexed { sourceOffset, (source, engine) ->
                    val id = page * 2L + sourceOffset + 1L
                    sqlite.execSQL("INSERT INTO text_pages(id,book_id,document_version,page_index,source,text_schema_version,engine_version,state,native_usability) VALUES($id,'${book.value}','${document.value}',$page,'${source.name}',1,'${engine.value}','COMPLETE','UNUSABLE')")
                    sqlite.execSQL("INSERT INTO text_words(page_id,block_ordinal,line_ordinal,word_ordinal,text,left,top,right,bottom,language_tag,confidence) VALUES($id,0,0,0,'dense',0,0,1,1,NULL,NULL)")
                    sqlite.execSQL("INSERT INTO text_page_search(rowid,page_text,normalized_text) VALUES($id,'dense','DENSE')")
                    sqlite.execSQL("INSERT INTO text_page_grams(page_id,gram_hash) VALUES($id,$id)")
                }
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }

        index.prepareDocument(book, DocumentContentVersion("56".repeat(32)))

        listOf("text_pages", "text_words", "text_fonts", "text_page_search", "text_page_grams").forEach { table ->
            sqlite.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("$table was not fully cleaned", 0L, cursor.getLong(0))
            }
        }
    }

    @Test fun legacyQueryIsReadOnlyUntilBoundedMaintenanceCreatesGrams() {
        val current = key(21, TextSource.NATIVE_PDF, nativeVersion)
        index.complete(current, oneWordPage("banána", TextSource.NATIVE_PDF))
        val pageId = database.textPageDao().exact(
            book.value, document.value, 21, TextSource.NATIVE_PDF.name, 1, nativeVersion.value
        )!!.id
        database.textPageDao().deleteGrams(listOf(pageId))

        val nanaBeforeMaintenance = search(index, document, "NANA")
        assertEquals(listOf(21), nanaBeforeMaintenance.map { it.pageIndex })
        assertEquals(listOf(0), nanaBeforeMaintenance.map { it.occurrenceIndex })
        assertEquals(listOf(0..0), nanaBeforeMaintenance.map { it.wordRange })
        assertEquals(listOf("banána"), nanaBeforeMaintenance.map { it.snippet })

        val accentHits = search(index, document, "á")
        assertEquals(listOf(21, 21, 21), accentHits.map { it.pageIndex })
        assertEquals(listOf(0, 1, 2), accentHits.map { it.occurrenceIndex })
        assertEquals(listOf(0..0, 0..0, 0..0), accentHits.map { it.wordRange })
        assertEquals(listOf("banána", "banána", "banána"), accentHits.map { it.snippet })
        assertFalse(database.textPageDao().pageIdsMatchingAllGrams(
            book.value, document.value, normalizedTrigramHashes("BANANA").toList(),
            normalizedTrigramHashes("BANANA").size
        ).contains(pageId))

        index.maintainDerivedData(current, null)

        assertTrue(database.textPageDao().pageIdsMatchingAllGrams(
            book.value, document.value, normalizedTrigramHashes("BANANA").toList(),
            normalizedTrigramHashes("BANANA").size
        ).contains(pageId))
        assertEquals(nanaBeforeMaintenance, search(index, document, "NANA"))
    }

    @Test fun legacyQueryDoesNotMutateGramsAndMaintenanceConvergesInBoundedSlices() {
        index.close()
        database = Room.inMemoryDatabaseBuilder(context, TextPageDatabase::class.java)
            .allowMainThreadQueries().build()
        val gramMutations = AtomicInteger()
        val legacyChunks = CopyOnWriteArrayList<Int>()
        index = RoomTextPageIndex(
            database,
            onGramMutation = gramMutations::addAndGet,
            onLegacySearchChunk = legacyChunks::add
        )
        val first = key(0, TextSource.NATIVE_PDF, nativeVersion)
        prepare(index, first)
        val sqlite = database.openHelper.writableDatabase
        sqlite.beginTransaction()
        try {
            repeat(1_500) { page ->
                val id = page + 1L
                sqlite.execSQL("INSERT INTO text_pages(id,book_id,document_version,page_index,source,text_schema_version,engine_version,state,native_usability) VALUES($id,'${book.value}','${document.value}',$page,'NATIVE_PDF',1,'${nativeVersion.value}','COMPLETE','USABLE')")
                sqlite.execSQL("INSERT INTO text_words(page_id,block_ordinal,line_ordinal,word_ordinal,text,left,top,right,bottom,language_tag,confidence) VALUES($id,0,0,0,'legacy needle',0,0,1,1,NULL,NULL)")
                sqlite.execSQL("INSERT INTO text_page_search(rowid,page_text,normalized_text) VALUES($id,'legacy needle','LEGACY NEEDLE')")
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }

        assertEquals(1_500, search(index, document, "needle").size)
        assertEquals(0, gramMutations.get())
        assertTrue(legacyChunks.size >= 12)
        assertTrue(legacyChunks.all { it in 1..128 })
        sqlite.query("SELECT COUNT(*) FROM text_page_grams").use {
            assertTrue(it.moveToFirst())
            assertEquals(0L, it.getLong(0))
        }

        var slices = 0
        while (index.maintainDerivedData(first, null)) {
            slices++
            assertTrue(slices <= 48)
        }
        assertEquals(1_500, gramMutations.get())
        sqlite.query("SELECT COUNT(DISTINCT page_id) FROM text_page_grams").use {
            assertTrue(it.moveToFirst())
            assertEquals(1_500L, it.getLong(0))
        }

        legacyChunks.clear()
        assertEquals(1_500, search(index, document, "needle").size)
        assertTrue(legacyChunks.isEmpty())
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

    @Test fun ocrClaimCasLateReportsFailureRetryAndReopenRecoveryAreGenerationFenced() {
        index.close()
        val name = "room-ocr-reopen.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        database = namedDatabase(name)
        index = RoomTextPageIndex(database)
        val native = key(0, TextSource.NATIVE_PDF, nativeVersion)
        val ownership = ocrKey(0)
        prepare(index, native)
        index.prepareOcr(ownership)
        index.completeNativeAndReconcile(native, TextPage(emptyList(), TextSource.NATIVE_PDF), ownership)
        val interrupted = requireNotNull(index.claimOcr(ownership).attempt)
        assertNull(index.claimOcr(ownership).attempt)
        index.close()

        database = namedDatabase(name)
        index = RoomTextPageIndex(database)
        prepare(index, native)
        index.prepareOcr(ownership)
        assertEquals(OcrPageState.QUEUED, index.ocrStatus(ownership)?.state)
        assertEquals(interrupted.generation + 1, index.ocrStatus(ownership)?.generation)
        assertEquals(OcrTransitionOutcome.GENERATION_MISMATCH,
            index.completeOcr(interrupted, oneWordPage("late", TextSource.OCR)).outcome)

        val current = requireNotNull(index.claimOcr(ownership).attempt)
        assertEquals(OcrTransitionOutcome.APPLIED,
            index.failOcr(current, "recognition", retryable = true).outcome)
        index.completeNativeAndReconcile(native, TextPage(emptyList(), TextSource.NATIVE_PDF), ownership)
        assertEquals(OcrPageState.FAILED, index.ocrStatus(ownership)?.state)
        assertEquals(OcrTransitionOutcome.APPLIED, index.retryOcr(ownership).outcome)
        assertEquals(current.generation + 1, index.ocrStatus(ownership)?.generation)
        val cancellation = requireNotNull(index.claimOcr(ownership).attempt)
        assertEquals(OcrTransitionOutcome.APPLIED, index.cancelOcr(cancellation).outcome)
        assertEquals(OcrPageState.CANCELLED, index.ocrStatus(ownership)?.state)
        assertEquals(OcrCancellationReason.USER, index.ocrStatus(ownership)?.cancellationReason)
        index.close()

        database = namedDatabase(name)
        index = RoomTextPageIndex(database)
        prepare(index, native)
        index.prepareOcr(ownership)
        index.completeNativeAndReconcile(native, TextPage(emptyList(), TextSource.NATIVE_PDF), ownership)
        assertEquals(OcrCancellationReason.USER, index.ocrStatus(ownership)?.cancellationReason)
        assertEquals(OcrTransitionOutcome.GENERATION_MISMATCH,
            index.failOcr(current, "late-failure", retryable = true).outcome)
        assertEquals(OcrTransitionOutcome.APPLIED, index.retryOcr(ownership).outcome)
    }

    @Test fun nonRetryableOcrFailureRemainsTerminalAcrossDatabaseReopen() {
        index.close()
        val name = "room-ocr-terminal-failure.db"
        databasesToDelete += name
        context.deleteDatabase(name)
        database = namedDatabase(name)
        index = RoomTextPageIndex(database)
        val native = key(0, TextSource.NATIVE_PDF, nativeVersion)
        val ownership = ocrKey(0)
        prepare(index, native)
        index.prepareOcr(ownership)
        index.completeNativeAndReconcile(native, TextPage(emptyList(), TextSource.NATIVE_PDF), ownership)
        val attempt = requireNotNull(index.claimOcr(ownership).attempt)
        index.failOcr(attempt, "data", retryable = false)
        index.close()

        database = namedDatabase(name)
        index = RoomTextPageIndex(database)
        prepare(index, native)
        index.prepareOcr(ownership)

        assertEquals(OcrPageState.FAILED, index.ocrStatus(ownership)?.state)
        assertEquals(OcrTransitionOutcome.INVALID_STATE, index.retryOcr(ownership).outcome)
        assertEquals(attempt.generation, index.ocrStatus(ownership)?.generation)
    }

    @Test fun roomOcrPlanningUsesOneBoundedMetadataQueryWithVisiblePriority() {
        repeat(50) { pageIndex ->
            index.completeNativeAndReconcile(
                key(pageIndex, TextSource.NATIVE_PDF, nativeVersion),
                TextPage(emptyList(), TextSource.NATIVE_PDF),
                ocrKey(pageIndex)
            )
        }
        val completed = requireNotNull(index.claimOcr(ocrKey(2)).attempt)
        index.completeOcr(completed, oneWordPage("done", TextSource.OCR))
        val failed = requireNotNull(index.claimOcr(ocrKey(3)).attempt)
        index.failOcr(failed, "data", retryable = false)
        val paused = requireNotNull(index.claimOcr(ocrKey(4)).attempt)
        index.cancelOcr(paused, OcrCancellationReason.SEARCH_PAUSE)

        val plan = index.planOcr(
            ocrKey(37), preferredPage = 37, afterPage = 20, beforePage = 50, limit = 8
        )

        assertEquals(8, plan.pageIndexes.size)
        assertEquals(37, plan.pageIndexes.first())
        assertTrue(plan.pageIndexes.none { it == 2 || it == 3 })
        assertTrue(4 in index.planOcr(
            ocrKey(4), preferredPage = 4, afterPage = -1, beforePage = 50, limit = 8
        ).pageIndexes)
    }

    @Test fun changedNativeAndOcrOwnershipMarksOldStateStaleWithoutPublishingIt() {
        val native = key(0, TextSource.NATIVE_PDF, nativeVersion)
        val old = ocrKey(0)
        index.completeNativeAndReconcile(native, TextPage(emptyList(), TextSource.NATIVE_PDF), old)
        assertEquals(OcrPageState.QUEUED, index.ocrStatus(old)?.state)

        val changedNative = native.copy(engineVersion = TextEngineVersion("native-v2"))
        index.prepareSource(book, document, TextSource.NATIVE_PDF, 1, changedNative.engineVersion)
        val current = old.copy(nativeEngineVersion = changedNative.engineVersion,
            ocrEngineVersion = TextEngineVersion("ocr-v2|eng:data-v2"))
        assertEquals(OcrTransitionOutcome.APPLIED, index.prepareOcr(current))

        assertNull(index.ocrStatus(old))
        database.openHelper.readableDatabase.query(
            "SELECT state FROM ocr_page_states WHERE native_engine_version='${nativeVersion.value}'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(OcrPageState.STALE.name, it.getString(0))
        }
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
        database.textPageDao().insertSearch(TextPageSearchEntity(staleId, "stalefenced", "stalefenced"))

        assertTrue(search(index, document, "stalefenced").isEmpty())
        assertEquals(
            listOf(expectedHit(currentKey.pageIndex, currentKey.source, "currentfenced")),
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
            callbackHits += hits.hits
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
        assertEquals(listOf(expectedHit(oldKey.pageIndex, oldKey.source, "oldsearchhit")), callbackHits)
        assertEquals("oldsearchhit", queryingIndex.load(oldKey)?.text)

        prepare(invalidatingIndex, currentKey)
        invalidatingIndex.complete(currentKey, oneWordPage("currentsearchhit", currentKey.source))
        assertEquals(
            listOf(expectedHit(currentKey.pageIndex, currentKey.source, "currentsearchhit")),
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

    private fun ocrKey(page: Int) = OcrPageKey(
        book, document, page, 1, nativeVersion, NATIVE_TEXT_USABILITY_POLICY_VERSION, ocrVersion
    )

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
            listOf(expectedHit(currentKey.pageIndex, currentKey.source, "currenttext")),
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
        .addMigrations(TextPageDatabase.MIGRATION_4_5)
        .allowMainThreadQueries()
        .build()

    private fun search(
        target: TextPageIndex,
        documentVersion: DocumentContentVersion,
        query: String
    ): List<TextPageSearchHit> {
        var hits: List<TextPageSearchHit>? = null
        assertEquals(
            TextPagePublicationOutcome.CURRENT,
            target.searchIfCurrent(book, documentVersion, query) { hits = it.hits }
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

private fun expectedHit(pageIndex: Int, source: TextSource, text: String) = TextPageSearchHit(
    pageIndex,
    source,
    0,
    0..0,
    listOf(PageSpaceRect(.1f, .1f, .9f, .2f)),
    text
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
