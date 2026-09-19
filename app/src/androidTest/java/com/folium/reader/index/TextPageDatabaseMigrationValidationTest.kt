package com.folium.reader.index

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one assertion a plain-SQL migration test cannot make: that the schema the migration leaves
 * behind is the schema the entities declare.
 *
 * A migration whose SQL runs cleanly but produces a column or index the entities do not expect
 * fails at the moment a reader opens the app, not here — and by then their index is already on
 * disk in a shape nothing can read. `runMigrationsAndValidate` is what turns that into a test
 * failure instead.
 */
@RunWith(AndroidJUnit4::class)
class TextPageDatabaseMigrationValidationTest {

    private companion object {
        const val TEST_DB = "migration-validation.db"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TextPageDatabase::class.java
    )

    @Test fun the_schema_after_migrating_to_seven_is_the_schema_the_entities_declare() {
        helper.createDatabase(TEST_DB, 6).close()

        helper.runMigrationsAndValidate(TEST_DB, 7, true, TextPageDatabase.MIGRATION_6_7)
    }

    @Test fun a_database_carrying_rows_migrates_and_still_validates() {
        helper.createDatabase(TEST_DB, 6).use { database ->
            database.execSQL(
                "INSERT INTO active_text_documents (book_id, document_version, text_schema_version) " +
                    "VALUES ('book-1', '${"a".repeat(64)}', 1)"
            )
            database.execSQL(
                "INSERT INTO active_text_sources " +
                    "(book_id, source, document_version, text_schema_version, engine_version, native_engine_version, usability_policy_version) " +
                    "VALUES ('book-1', 'NATIVE_PDF', '${"a".repeat(64)}', 1, 'engine-v1', 'engine-v1', 1)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 7, true, TextPageDatabase.MIGRATION_6_7)
    }

    @Test fun the_schema_after_migrating_to_eight_is_the_schema_the_entities_declare() {
        helper.createDatabase(TEST_DB, 7).close()

        helper.runMigrationsAndValidate(TEST_DB, 8, true, TextPageDatabase.MIGRATION_7_8)
    }

    @Test fun a_database_carrying_layout_usage_rows_migrates_to_eight_and_still_validates() {
        helper.createDatabase(TEST_DB, 6).use { database ->
            database.execSQL(
                "INSERT INTO active_text_documents (book_id, document_version, text_schema_version) " +
                    "VALUES ('book-1', '${"a".repeat(64)}', 1)"
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DB, 8, true, TextPageDatabase.MIGRATION_6_7, TextPageDatabase.MIGRATION_7_8
        ).use { migrated ->
            migrated.execSQL(
                "INSERT INTO text_layout_usage(book_id, document_version, layout_version, sequence) " +
                    "VALUES ('book-1', '${"a".repeat(64)}', 'layout-a', 1)"
            )
            migrated.query("SELECT COUNT(*) FROM text_layout_usage").use {
                assertTrue(it.moveToFirst())
                assertEquals(1L, it.getLong(0))
            }
        }
    }
}
