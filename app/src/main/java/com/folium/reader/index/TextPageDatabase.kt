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
        TextPageSearchEntity::class
    ],
    version = 2,
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
        ).addMigrations(MIGRATION_1_2).build()

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
    }
}
