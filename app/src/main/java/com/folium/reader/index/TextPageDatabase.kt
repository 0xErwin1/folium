package com.folium.reader.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ActiveTextDocumentEntity::class,
        ActiveTextSourceEntity::class,
        TextPageEntity::class,
        TextWordEntity::class,
        TextFontEntity::class,
        TextPageSearchEntity::class
    ],
    version = 1,
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
        ).build()
    }
}
