package com.folium.reader.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
internal abstract class TextPageDao {
    internal data class PageStateRow(val pageIndex: Int, val state: String)
    internal data class NativeCoverageRow(
        val pageIndex: Int,
        val state: String,
        val nativeUsability: String
    )
    internal data class SearchTextRow(val rowId: Long, val pageText: String, val normalizedText: String)

    @Query("SELECT * FROM active_text_documents WHERE book_id=:bookId LIMIT 1")
    abstract fun activeDocument(bookId: String): ActiveTextDocumentEntity?

    @Query("SELECT * FROM active_text_sources WHERE book_id=:bookId AND source=:source LIMIT 1")
    abstract fun activeSource(bookId: String, source: String): ActiveTextSourceEntity?

    @Query("SELECT * FROM active_text_sources WHERE book_id=:bookId ORDER BY source")
    abstract fun activeSources(bookId: String): List<ActiveTextSourceEntity>

    @Upsert abstract fun upsertActiveDocument(document: ActiveTextDocumentEntity)
    @Upsert abstract fun upsertActiveSource(source: ActiveTextSourceEntity)
    @Upsert abstract fun upsertOcrState(state: OcrPageStateEntity)

    @Query("SELECT * FROM ocr_page_states WHERE book_id=:bookId AND document_version=:documentVersion AND page_index=:pageIndex AND text_schema_version=:schemaVersion AND native_engine_version=:nativeEngineVersion AND usability_policy_version=:policyVersion AND ocr_engine_version=:ocrEngineVersion LIMIT 1")
    abstract fun exactOcrState(bookId: String, documentVersion: String, pageIndex: Int, schemaVersion: Int,
                               nativeEngineVersion: String, policyVersion: String, ocrEngineVersion: String): OcrPageStateEntity?

    @Query("SELECT * FROM ocr_page_states WHERE book_id=:bookId")
    abstract fun ocrStatesForBook(bookId: String): List<OcrPageStateEntity>

    @Query("SELECT * FROM ocr_page_states WHERE book_id=:bookId AND document_version=:documentVersion AND page_index=:pageIndex AND text_schema_version=:schemaVersion AND native_engine_version=:nativeEngineVersion AND usability_policy_version=:policyVersion AND ocr_engine_version=:ocrEngineVersion AND state='COMPLETED' LIMIT 1")
    abstract fun completedOcrState(bookId: String, documentVersion: String, pageIndex: Int, schemaVersion: Int,
                                   nativeEngineVersion: String, policyVersion: String,
                                   ocrEngineVersion: String): OcrPageStateEntity?

    @Query("""
        SELECT * FROM ocr_page_states INDEXED BY index_ocr_page_states_planning
        WHERE book_id=:bookId AND document_version=:documentVersion
            AND text_schema_version=:schemaVersion
            AND native_engine_version=:nativeEngineVersion
            AND usability_policy_version=:policyVersion
            AND ocr_engine_version=:ocrEngineVersion
            AND state='QUEUED' AND cancellation_reason IS NULL
            AND page_index>:afterPage AND page_index<:beforePage
        ORDER BY page_index
        LIMIT :limit
    """)
    abstract fun queuedOcrPlanningSlice(
        bookId: String,
        documentVersion: String,
        schemaVersion: Int,
        nativeEngineVersion: String,
        policyVersion: String,
        ocrEngineVersion: String,
        afterPage: Int,
        beforePage: Int,
        limit: Int
    ): List<OcrPageStateEntity>

    @Query("""
        SELECT * FROM ocr_page_states INDEXED BY index_ocr_page_states_planning
        WHERE book_id=:bookId AND document_version=:documentVersion
            AND text_schema_version=:schemaVersion
            AND native_engine_version=:nativeEngineVersion
            AND usability_policy_version=:policyVersion
            AND ocr_engine_version=:ocrEngineVersion
            AND state='CANCELLED' AND cancellation_reason='SEARCH_PAUSE'
            AND page_index>:afterPage AND page_index<:beforePage
        ORDER BY page_index
        LIMIT :limit
    """)
    abstract fun pausedOcrPlanningSlice(
        bookId: String,
        documentVersion: String,
        schemaVersion: Int,
        nativeEngineVersion: String,
        policyVersion: String,
        ocrEngineVersion: String,
        afterPage: Int,
        beforePage: Int,
        limit: Int
    ): List<OcrPageStateEntity>

    @Query("DELETE FROM active_text_sources WHERE book_id=:bookId")
    abstract fun deleteActiveSources(bookId: String)

    @Query("SELECT * FROM text_pages WHERE book_id=:bookId AND document_version=:documentVersion AND page_index=:pageIndex AND source=:source AND text_schema_version=:schemaVersion AND engine_version=:engineVersion LIMIT 1")
    abstract fun exact(bookId: String, documentVersion: String, pageIndex: Int, source: String, schemaVersion: Int, engineVersion: String): TextPageEntity?

    @Query("SELECT * FROM text_words WHERE page_id=:pageId ORDER BY block_ordinal, line_ordinal, word_ordinal")
    abstract fun words(pageId: Long): List<TextWordEntity>

    @Query("SELECT * FROM text_fonts WHERE page_id=:pageId ORDER BY block_ordinal, line_ordinal, word_ordinal, font_ordinal")
    abstract fun fonts(pageId: Long): List<TextFontEntity>

    @Insert abstract fun insertPage(page: TextPageEntity): Long
    @Insert abstract fun insertWords(words: List<TextWordEntity>)
    @Insert abstract fun insertFonts(fonts: List<TextFontEntity>)
    @Insert abstract fun insertSearch(search: TextPageSearchEntity)
    @Insert abstract fun insertGrams(grams: List<TextPageGramEntity>)

    @Query("UPDATE text_pages SET state=:state WHERE id=:id")
    abstract fun updateState(id: Long, state: String)

    @Query("UPDATE text_pages SET native_usability=:usability WHERE id=:id AND native_usability='UNKNOWN'")
    abstract fun setNativeUsabilityIfUnknown(id: Long, usability: String): Int

    @Query("SELECT id FROM text_pages WHERE book_id=:bookId AND document_version=:documentVersion AND text_schema_version=:schemaVersion AND page_index=:pageIndex")
    abstract fun pageIds(bookId: String, documentVersion: String, schemaVersion: Int, pageIndex: Int): List<Long>

    @Query("SELECT id FROM text_pages WHERE book_id=:bookId AND document_version=:documentVersion AND text_schema_version=:schemaVersion AND page_index=:pageIndex AND source=:source")
    abstract fun sourcePageIds(bookId: String, documentVersion: String, schemaVersion: Int, pageIndex: Int, source: String): List<Long>

    @Query("SELECT id FROM text_pages WHERE book_id=:bookId")
    abstract fun bookPageIds(bookId: String): List<Long>

    @Query("SELECT id FROM text_pages WHERE book_id=:bookId AND document_version=:documentVersion")
    abstract fun documentPageIds(bookId: String, documentVersion: String): List<Long>

    @Query("SELECT id FROM text_pages WHERE book_id=:bookId AND document_version=:documentVersion AND source=:source AND (text_schema_version!=:schemaVersion OR engine_version!=:engineVersion)")
    abstract fun staleSourceIds(bookId: String, documentVersion: String, source: String, schemaVersion: Int, engineVersion: String): List<Long>

    @Query("DELETE FROM text_page_search WHERE rowid IN (:ids)")
    abstract fun deleteSearch(ids: List<Long>)

    @Query("DELETE FROM text_page_grams WHERE page_id IN (:ids)")
    abstract fun deleteGrams(ids: List<Long>)

    @Query("DELETE FROM text_pages WHERE id IN (:ids)")
    abstract fun deletePages(ids: List<Long>)

    @Query("DELETE FROM text_words WHERE page_id=:pageId")
    abstract fun deleteWords(pageId: Long)

    @Query("DELETE FROM text_fonts WHERE page_id=:pageId")
    abstract fun deleteFonts(pageId: Long)

    @Query("""
        SELECT text_pages.*
        FROM text_page_search
        JOIN text_pages ON text_pages.id=text_page_search.rowid
        JOIN active_text_documents ON active_text_documents.book_id=text_pages.book_id
            AND active_text_documents.document_version=text_pages.document_version
            AND active_text_documents.text_schema_version=text_pages.text_schema_version
        JOIN active_text_sources ON active_text_sources.book_id=text_pages.book_id
            AND active_text_sources.document_version=text_pages.document_version
            AND active_text_sources.source=text_pages.source
            AND active_text_sources.text_schema_version=text_pages.text_schema_version
            AND active_text_sources.engine_version=text_pages.engine_version
        WHERE text_pages.book_id=:bookId AND text_pages.document_version=:documentVersion
            AND text_pages.state='COMPLETE'
            AND instr(text_page_search.normalized_text, :normalizedQuery) > 0
        ORDER BY text_pages.page_index, text_pages.source
    """)
    abstract fun search(
        bookId: String,
        documentVersion: String,
        normalizedQuery: String
    ): List<TextPageEntity>

    @Query("SELECT text_page_grams.page_id FROM text_page_grams JOIN text_pages ON text_pages.id=text_page_grams.page_id WHERE text_pages.book_id=:bookId AND text_pages.document_version=:documentVersion AND text_pages.state='COMPLETE' AND text_page_grams.gram_hash IN (:hashes) GROUP BY text_page_grams.page_id HAVING COUNT(DISTINCT text_page_grams.gram_hash)=:gramCount")
    abstract fun pageIdsMatchingAllGrams(
        bookId: String,
        documentVersion: String,
        hashes: List<Long>,
        gramCount: Int
    ): List<Long>

    @Query("SELECT rowid AS rowId, page_text AS pageText, normalized_text AS normalizedText FROM text_page_search WHERE rowid IN (:pageIds)")
    abstract fun searchTextForPages(pageIds: List<Long>): List<SearchTextRow>

    @Query("SELECT text_page_search.rowid AS rowId, text_page_search.page_text AS pageText, text_page_search.normalized_text AS normalizedText FROM text_page_search JOIN text_pages ON text_pages.id=text_page_search.rowid WHERE text_pages.book_id=:bookId AND text_pages.document_version=:documentVersion AND text_pages.state='COMPLETE' AND text_pages.id NOT IN (SELECT DISTINCT page_id FROM text_page_grams) LIMIT :limit")
    abstract fun searchTextMissingGrams(bookId: String, documentVersion: String, limit: Int): List<SearchTextRow>

    @Query("SELECT text_pages.* FROM text_pages WHERE id IN (:ids) ORDER BY page_index, source")
    abstract fun pagesByIds(ids: List<Long>): List<TextPageEntity>

    @Query("SELECT text_pages.* FROM text_pages JOIN text_page_search ON text_page_search.rowid=text_pages.id WHERE text_pages.book_id=:bookId AND text_pages.document_version=:documentVersion AND text_pages.state='COMPLETE' AND text_pages.id NOT IN (SELECT DISTINCT page_id FROM text_page_grams) AND instr(text_page_search.normalized_text, :normalizedQuery)>0 AND (text_pages.page_index>:afterPage OR (text_pages.page_index=:afterPage AND text_pages.id>:afterId)) ORDER BY text_pages.page_index, text_pages.id LIMIT :limit")
    abstract fun matchingPagesMissingGramsAfter(
        bookId: String,
        documentVersion: String,
        normalizedQuery: String,
        afterPage: Int,
        afterId: Long,
        limit: Int
    ): List<TextPageEntity>

    @Query("SELECT text_pages.* FROM text_pages JOIN active_text_documents ON active_text_documents.book_id=text_pages.book_id AND active_text_documents.document_version=text_pages.document_version AND active_text_documents.text_schema_version=text_pages.text_schema_version JOIN active_text_sources ON active_text_sources.book_id=text_pages.book_id AND active_text_sources.document_version=text_pages.document_version AND active_text_sources.source=text_pages.source AND active_text_sources.text_schema_version=text_pages.text_schema_version AND active_text_sources.engine_version=text_pages.engine_version WHERE text_pages.book_id=:bookId AND text_pages.document_version=:documentVersion AND text_pages.state='COMPLETE' ORDER BY text_pages.page_index, text_pages.source")
    abstract fun allCurrentCompletePages(bookId: String, documentVersion: String): List<TextPageEntity>

    @Query("SELECT * FROM text_pages WHERE book_id=:bookId AND document_version=:documentVersion AND source='NATIVE_PDF' AND text_schema_version=:schemaVersion AND engine_version=:engineVersion AND state='COMPLETE' AND native_usability='UNKNOWN' ORDER BY page_index LIMIT :limit")
    abstract fun unknownNativePages(bookId: String, documentVersion: String, schemaVersion: Int,
                                    engineVersion: String, limit: Int): List<TextPageEntity>

    @Query("""
        SELECT text_pages.* FROM text_pages
        JOIN active_text_documents ON active_text_documents.book_id=text_pages.book_id
            AND active_text_documents.document_version=text_pages.document_version
            AND active_text_documents.text_schema_version=text_pages.text_schema_version
        JOIN active_text_sources ON active_text_sources.book_id=text_pages.book_id
            AND active_text_sources.document_version=text_pages.document_version
            AND active_text_sources.source=text_pages.source
            AND active_text_sources.text_schema_version=text_pages.text_schema_version
            AND active_text_sources.engine_version=text_pages.engine_version
        WHERE text_pages.book_id=:bookId AND text_pages.document_version=:documentVersion
            AND text_pages.page_index IN (:pageIndexes) AND text_pages.state='COMPLETE'
        ORDER BY text_pages.page_index, text_pages.source
    """)
    abstract fun completePagesForIndexes(
        bookId: String,
        documentVersion: String,
        pageIndexes: List<Int>
    ): List<TextPageEntity>

    @Query("SELECT * FROM text_words WHERE page_id IN (:pageIds) ORDER BY page_id, block_ordinal, line_ordinal, word_ordinal")
    abstract fun wordsForPages(pageIds: List<Long>): List<TextWordEntity>

    @Query("SELECT * FROM text_fonts WHERE page_id IN (:pageIds) ORDER BY page_id, block_ordinal, line_ordinal, word_ordinal, font_ordinal")
    abstract fun fontsForPages(pageIds: List<Long>): List<TextFontEntity>

    @Query("SELECT * FROM ocr_page_states WHERE book_id=:bookId AND document_version=:documentVersion AND page_index IN (:pageIndexes) AND state='COMPLETED'")
    abstract fun completedOcrStatesForPages(
        bookId: String,
        documentVersion: String,
        pageIndexes: List<Int>
    ): List<OcrPageStateEntity>

    @Query("SELECT * FROM ocr_page_states WHERE book_id=:bookId AND document_version=:documentVersion AND text_schema_version=:schemaVersion AND native_engine_version=:nativeEngineVersion AND usability_policy_version=:policyVersion AND ocr_engine_version=:ocrEngineVersion AND page_index IN (:pageIndexes)")
    abstract fun exactOcrStatesForPages(
        bookId: String,
        documentVersion: String,
        schemaVersion: Int,
        nativeEngineVersion: String,
        policyVersion: String,
        ocrEngineVersion: String,
        pageIndexes: List<Int>
    ): List<OcrPageStateEntity>

    @Query("""
        SELECT text_pages.page_index AS pageIndex, text_pages.state AS state FROM text_pages
        JOIN active_text_documents ON active_text_documents.book_id=text_pages.book_id
            AND active_text_documents.document_version=text_pages.document_version
            AND active_text_documents.text_schema_version=text_pages.text_schema_version
        JOIN active_text_sources ON active_text_sources.book_id=text_pages.book_id
            AND active_text_sources.document_version=text_pages.document_version
            AND active_text_sources.source=text_pages.source
            AND active_text_sources.text_schema_version=text_pages.text_schema_version
            AND active_text_sources.engine_version=text_pages.engine_version
        WHERE text_pages.book_id=:bookId AND text_pages.document_version=:documentVersion
            AND text_pages.source=:source AND text_pages.text_schema_version=:schemaVersion
            AND text_pages.engine_version=:engineVersion
        ORDER BY text_pages.page_index, text_pages.source
    """)
    abstract fun pageStates(
        bookId: String,
        documentVersion: String,
        source: String,
        schemaVersion: Int,
        engineVersion: String
    ): List<PageStateRow>

    @Query("SELECT page_index AS pageIndex, state, native_usability AS nativeUsability FROM text_pages WHERE book_id=:bookId AND document_version=:documentVersion AND source='NATIVE_PDF' AND text_schema_version=:schemaVersion AND engine_version=:engineVersion ORDER BY page_index")
    abstract fun nativeCoverage(
        bookId: String,
        documentVersion: String,
        schemaVersion: Int,
        engineVersion: String
    ): List<NativeCoverageRow>

    @Query("SELECT * FROM ocr_page_states INDEXED BY index_ocr_page_states_planning WHERE book_id=:bookId AND document_version=:documentVersion AND text_schema_version=:schemaVersion AND native_engine_version=:nativeEngineVersion AND usability_policy_version=:policyVersion AND ocr_engine_version=:ocrEngineVersion ORDER BY page_index")
    abstract fun ocrCoverage(
        bookId: String,
        documentVersion: String,
        schemaVersion: Int,
        nativeEngineVersion: String,
        policyVersion: String,
        ocrEngineVersion: String
    ): List<OcrPageStateEntity>

    @Query("SELECT * FROM text_pages WHERE book_id=:bookId AND document_version=:documentVersion AND source='NATIVE_PDF' AND text_schema_version=:schemaVersion AND engine_version=:engineVersion AND state='COMPLETE' ORDER BY page_index")
    abstract fun completedNativePages(bookId: String, documentVersion: String, schemaVersion: Int,
                                      engineVersion: String): List<TextPageEntity>

    @Transaction
    open fun deleteRows(ids: List<Long>) {
        ids.chunked(900).forEach { chunk ->
            deleteGrams(chunk)
            deleteSearch(chunk)
            deletePages(chunk)
        }
    }
}
