package com.folium.reader.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

internal data class SearchRow(val pageIndex: Int, val source: String, val pageText: String)

@Dao
internal abstract class TextPageDao {
    @Query("SELECT * FROM active_text_documents WHERE book_id=:bookId LIMIT 1")
    abstract fun activeDocument(bookId: String): ActiveTextDocumentEntity?

    @Query("SELECT * FROM active_text_sources WHERE book_id=:bookId AND source=:source LIMIT 1")
    abstract fun activeSource(bookId: String, source: String): ActiveTextSourceEntity?

    @Query("SELECT * FROM active_text_sources WHERE book_id=:bookId ORDER BY source")
    abstract fun activeSources(bookId: String): List<ActiveTextSourceEntity>

    @Upsert abstract fun upsertActiveDocument(document: ActiveTextDocumentEntity)
    @Upsert abstract fun upsertActiveSource(source: ActiveTextSourceEntity)

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

    @Query("UPDATE text_pages SET state=:state WHERE id=:id")
    abstract fun updateState(id: Long, state: String)

    @Query("SELECT id FROM text_pages WHERE book_id=:bookId AND document_version=:documentVersion AND text_schema_version=:schemaVersion AND page_index=:pageIndex")
    abstract fun pageIds(bookId: String, documentVersion: String, schemaVersion: Int, pageIndex: Int): List<Long>

    @Query("SELECT id FROM text_pages WHERE book_id=:bookId")
    abstract fun bookPageIds(bookId: String): List<Long>

    @Query("SELECT id FROM text_pages WHERE book_id=:bookId AND document_version=:documentVersion")
    abstract fun documentPageIds(bookId: String, documentVersion: String): List<Long>

    @Query("SELECT id FROM text_pages WHERE book_id=:bookId AND document_version=:documentVersion AND source=:source AND (text_schema_version!=:schemaVersion OR engine_version!=:engineVersion)")
    abstract fun staleSourceIds(bookId: String, documentVersion: String, source: String, schemaVersion: Int, engineVersion: String): List<Long>

    @Query("DELETE FROM text_page_search WHERE rowid IN (:ids)")
    abstract fun deleteSearch(ids: List<Long>)

    @Query("DELETE FROM text_pages WHERE id IN (:ids)")
    abstract fun deletePages(ids: List<Long>)

    @Query("DELETE FROM text_words WHERE page_id=:pageId")
    abstract fun deleteWords(pageId: Long)

    @Query("DELETE FROM text_fonts WHERE page_id=:pageId")
    abstract fun deleteFonts(pageId: Long)

    @Query("""
        SELECT text_pages.page_index AS pageIndex, text_pages.source AS source,
            text_page_search.page_text AS pageText
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
            AND text_pages.state='COMPLETE' AND text_page_search MATCH :query
        ORDER BY text_pages.page_index
    """)
    abstract fun search(bookId: String, documentVersion: String, query: String): List<SearchRow>

    @Transaction
    open fun deleteRows(ids: List<Long>) {
        if (ids.isEmpty()) return
        deleteSearch(ids)
        deletePages(ids)
    }
}
