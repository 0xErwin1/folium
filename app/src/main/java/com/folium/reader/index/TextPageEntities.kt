package com.folium.reader.index

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
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
    @ColumnInfo(name = "engine_version") val engineVersion: String
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
    val state: String
)

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

@Fts4
@Entity(tableName = "text_page_search")
internal data class TextPageSearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    @ColumnInfo(name = "page_text") val pageText: String
)
