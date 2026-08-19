package com.folium.reader.index

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "active_text_documents")
internal data class ActiveTextDocumentEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "document_version") val documentVersion: String,
    @ColumnInfo(name = "text_schema_version") val textSchemaVersion: Int?
)

@Entity(
    tableName = "active_text_sources",
    primaryKeys = ["book_id", "source"]
)
internal data class ActiveTextSourceEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    val source: String,
    @ColumnInfo(name = "document_version") val documentVersion: String,
    @ColumnInfo(name = "text_schema_version") val textSchemaVersion: Int,
    @ColumnInfo(name = "engine_version") val engineVersion: String,
    @ColumnInfo(name = "native_engine_version") val nativeEngineVersion: String? = null,
    @ColumnInfo(name = "usability_policy_version") val usabilityPolicyVersion: String? = null
)

@Entity(
    tableName = "text_pages",
    indices = [
        Index(
            value = ["book_id", "document_version", "page_index", "source", "text_schema_version", "engine_version"],
            unique = true
        ),
        Index(value = ["book_id", "document_version", "page_index"])
    ]
)
internal data class TextPageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "document_version") val documentVersion: String,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    val source: String,
    @ColumnInfo(name = "text_schema_version") val textSchemaVersion: Int,
    @ColumnInfo(name = "engine_version") val engineVersion: String,
    val state: String,
    @ColumnInfo(name = "native_usability", defaultValue = "'UNKNOWN'")
    val nativeUsability: String = NativeTextUsability.UNKNOWN.name
)

internal enum class NativeTextUsability { UNKNOWN, USABLE, UNUSABLE }

@Entity(
    tableName = "text_words",
    primaryKeys = ["page_id", "block_ordinal", "line_ordinal", "word_ordinal"],
    foreignKeys = [ForeignKey(
        entity = TextPageEntity::class,
        parentColumns = ["id"],
        childColumns = ["page_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("page_id")]
)
internal data class TextWordEntity(
    @ColumnInfo(name = "page_id") val pageId: Long,
    @ColumnInfo(name = "block_ordinal") val blockOrdinal: Int,
    @ColumnInfo(name = "line_ordinal") val lineOrdinal: Int,
    @ColumnInfo(name = "word_ordinal") val wordOrdinal: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    @ColumnInfo(name = "language_tag") val languageTag: String?,
    val confidence: Float?
)

@Entity(
    tableName = "text_fonts",
    primaryKeys = ["page_id", "block_ordinal", "line_ordinal", "word_ordinal", "font_ordinal"],
    foreignKeys = [ForeignKey(
        entity = TextPageEntity::class,
        parentColumns = ["id"],
        childColumns = ["page_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("page_id")]
)
internal data class TextFontEntity(
    @ColumnInfo(name = "page_id") val pageId: Long,
    @ColumnInfo(name = "block_ordinal") val blockOrdinal: Int,
    @ColumnInfo(name = "line_ordinal") val lineOrdinal: Int,
    @ColumnInfo(name = "word_ordinal") val wordOrdinal: Int,
    @ColumnInfo(name = "font_ordinal") val fontOrdinal: Int,
    val name: String?,
    val bold: Boolean,
    val italic: Boolean,
    val serif: Boolean,
    val monospaced: Boolean
)

/**
 * Page text kept alongside the structured words, for candidate filtering and for snippets.
 *
 * This is deliberately not a full-text table. Search here is substring matching — "ana" has to find
 * "banana" — which an FTS index cannot answer: its tokens are words, so it can only match whole
 * words or prefixes. Candidate filtering is done by [TextPageGramEntity] instead, which is the
 * structure that does answer substring queries. The table was declared FTS4 for a long time and
 * never once queried with MATCH, so it paid for tokenizing and maintaining an inverted index over
 * both of its columns, for every page, to serve queries that all ran as instr() table scans.
 */
@Entity(tableName = "text_page_search")
internal data class TextPageSearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    @ColumnInfo(name = "page_text") val pageText: String,
    @ColumnInfo(name = "normalized_text") val normalizedText: String
)

@Entity(
    tableName = "text_page_grams",
    primaryKeys = ["page_id", "gram_hash"],
    foreignKeys = [ForeignKey(
        entity = TextPageEntity::class,
        parentColumns = ["id"],
        childColumns = ["page_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["gram_hash", "page_id"]), Index("page_id")]
)
internal data class TextPageGramEntity(
    @ColumnInfo(name = "page_id") val pageId: Long,
    @ColumnInfo(name = "gram_hash") val gramHash: Long
)

@Entity(
    tableName = "ocr_page_states",
    primaryKeys = [
        "book_id", "document_version", "page_index", "text_schema_version",
        "native_engine_version", "usability_policy_version", "ocr_engine_version"
    ],
    indices = [
        Index(value = ["book_id", "document_version", "page_index"]),
        Index(
            name = "index_ocr_page_states_planning",
            value = [
                "book_id", "document_version", "text_schema_version", "native_engine_version",
                "usability_policy_version", "ocr_engine_version", "state", "cancellation_reason",
                "page_index"
            ]
        )
    ]
)
internal data class OcrPageStateEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "document_version") val documentVersion: String,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    @ColumnInfo(name = "text_schema_version") val textSchemaVersion: Int,
    @ColumnInfo(name = "native_engine_version") val nativeEngineVersion: String,
    @ColumnInfo(name = "usability_policy_version") val usabilityPolicyVersion: String,
    @ColumnInfo(name = "ocr_engine_version") val ocrEngineVersion: String,
    val generation: Long,
    val state: String,
    @ColumnInfo(name = "cancellation_reason") val cancellationReason: String?,
    @ColumnInfo(name = "failure_kind") val failureKind: String?,
    val retryable: Boolean?
)
