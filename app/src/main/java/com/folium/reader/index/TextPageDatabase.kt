package com.folium.reader.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ActiveTextDocumentEntity::class,
        ActiveTextSourceEntity::class,
        TextPageEntity::class,
        TextWordEntity::class,
        TextFontEntity::class,
        TextPageSearchEntity::class,
        TextPageGramEntity::class,
        OcrPageStateEntity::class
    ],
    version = 5,
    exportSchema = true
)
internal abstract class TextPageDatabase : RoomDatabase() {
    abstract fun textPageDao(): TextPageDao

    companion object {
        internal const val DATABASE_NAME = "text-page-index.db"

        fun identity(context: Context): String =
            context.applicationContext.getDatabasePath(DATABASE_NAME).absolutePath

        fun open(context: Context): TextPageDatabase = Room.databaseBuilder(
            context.applicationContext,
            TextPageDatabase::class.java,
            DATABASE_NAME
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Text is derived. Keep the active document/version identity while invalidating all
                // source/page state so schema-2 extraction can repopulate it safely.
                db.execSQL("DELETE FROM active_text_sources")
                db.execSQL("DELETE FROM text_page_search")
                db.execSQL("DELETE FROM text_fonts")
                db.execSQL("DELETE FROM text_words")
                db.execSQL("DELETE FROM text_pages")
                db.execSQL("UPDATE active_text_documents SET text_schema_version=NULL")
                db.execSQL("DROP TABLE text_page_search")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS text_page_search USING FTS4(page_text TEXT NOT NULL, normalized_text TEXT NOT NULL, tokenize=unicode61 `remove_diacritics=2`)")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE active_text_sources ADD COLUMN native_engine_version TEXT")
                db.execSQL("ALTER TABLE active_text_sources ADD COLUMN usability_policy_version TEXT")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ocr_page_states (
                        book_id TEXT NOT NULL,
                        document_version TEXT NOT NULL,
                        page_index INTEGER NOT NULL,
                        text_schema_version INTEGER NOT NULL,
                        native_engine_version TEXT NOT NULL,
                        usability_policy_version TEXT NOT NULL,
                        ocr_engine_version TEXT NOT NULL,
                        generation INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        cancellation_reason TEXT,
                        failure_kind TEXT,
                        retryable INTEGER,
                        PRIMARY KEY(book_id, document_version, page_index, text_schema_version,
                            native_engine_version, usability_policy_version, ocr_engine_version)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ocr_page_states_book_id_document_version_page_index ON ocr_page_states(book_id, document_version, page_index)")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Both additions are derived metadata. Preserve every v3 text/OCR row and fill them
                // incrementally after open instead of blocking startup on a corpus-sized rebuild.
                db.execSQL("ALTER TABLE text_pages ADD COLUMN native_usability TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS text_page_grams (
                        page_id INTEGER NOT NULL,
                        gram_hash INTEGER NOT NULL,
                        PRIMARY KEY(page_id, gram_hash),
                        FOREIGN KEY(page_id) REFERENCES text_pages(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_text_page_grams_gram_hash_page_id ON text_page_grams(gram_hash, page_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_text_page_grams_page_id ON text_page_grams(page_id)")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ocr_page_states_planning
                    ON ocr_page_states(
                        book_id, document_version, text_schema_version, native_engine_version,
                        usability_policy_version, ocr_engine_version, state, cancellation_reason,
                        page_index
                    )
                """.trimIndent())
            }
        }
    }
}
