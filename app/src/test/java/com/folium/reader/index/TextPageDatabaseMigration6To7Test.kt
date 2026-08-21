package com.folium.reader.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs [MIGRATION_6_7_STATEMENTS] — the exact SQL [TextPageDatabase.MIGRATION_6_7] executes against
 * a real Room database — against a plain SQLite file seeded with the v6 schema and real rows.
 *
 * Room's own [androidx.room.testing.MigrationTestHelper] needs an Android [android.app.Instrumentation]
 * in every constructor this project's Room version exposes, so it only runs instrumented; this test
 * exercises the identical migration SQL through a JDBC connection instead, which is a genuine host
 * JVM unit test and needs no device.
 */
class TextPageDatabaseMigration6To7Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun connect(): Connection {
        val databaseFile = tempFolder.newFile("text-page-index-v6.db")
        return DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}")
    }

    /** Exactly what Room's schema export recorded for v6, before this migration touched either table. */
    private fun seedV6Schema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE active_text_sources (
                    book_id TEXT NOT NULL, source TEXT NOT NULL, document_version TEXT NOT NULL,
                    text_schema_version INTEGER NOT NULL, engine_version TEXT NOT NULL,
                    native_engine_version TEXT, usability_policy_version TEXT,
                    PRIMARY KEY(book_id, source)
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE text_pages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, book_id TEXT NOT NULL,
                    document_version TEXT NOT NULL, page_index INTEGER NOT NULL, source TEXT NOT NULL,
                    text_schema_version INTEGER NOT NULL, engine_version TEXT NOT NULL, state TEXT NOT NULL,
                    native_usability TEXT NOT NULL DEFAULT 'UNKNOWN'
                )
                """.trimIndent()
            )
            statement.execute(
                "CREATE UNIQUE INDEX index_text_pages_book_id_document_version_page_index_source_text_schema_version_engine_version " +
                    "ON text_pages(book_id, document_version, page_index, source, text_schema_version, engine_version)"
            )
            statement.execute(
                "CREATE INDEX index_text_pages_book_id_document_version_page_index " +
                    "ON text_pages(book_id, document_version, page_index)"
            )
        }
    }

    private fun seedV6Rows(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO active_text_sources(book_id, source, document_version, text_schema_version, engine_version) " +
                    "VALUES ('book-1', 'NATIVE_PDF', 'doc-v1', 2, 'native-v1')"
            )
            statement.execute(
                "INSERT INTO text_pages(book_id, document_version, page_index, source, text_schema_version, engine_version, state) " +
                    "VALUES ('book-1', 'doc-v1', 0, 'NATIVE_PDF', 2, 'native-v1', 'COMPLETE')"
            )
        }
    }

    private fun migrate(connection: Connection) {
        connection.createStatement().use { statement ->
            MIGRATION_6_7_STATEMENTS.forEach(statement::execute)
        }
    }

    @Test
    fun `the layout_version column exists on both tables after migration`() {
        val connection = connect()
        seedV6Schema(connection)
        seedV6Rows(connection)

        migrate(connection)

        assertTrue(columnNames(connection, "text_pages").contains("layout_version"))
        assertTrue(columnNames(connection, "active_text_sources").contains("layout_version"))
    }

    @Test
    fun `existing v6 rows read back with an empty layout_version`() {
        val connection = connect()
        seedV6Schema(connection)
        seedV6Rows(connection)

        migrate(connection)

        connection.createStatement().use { statement ->
            val textPageRows = statement.executeQuery("SELECT layout_version FROM text_pages")
            assertTrue(textPageRows.next())
            assertEquals("", textPageRows.getString("layout_version"))

            val sourceRows = statement.executeQuery("SELECT layout_version FROM active_text_sources")
            assertTrue(sourceRows.next())
            assertEquals("", sourceRows.getString("layout_version"))
        }
    }

    /**
     * A PDF's cached text was found by the six original key columns before this migration; the same
     * lookup, now bound to an empty [layoutVersion][com.folium.reader.core.pdf], must still find it.
     */
    @Test
    fun `a pdfs cached text is still found by the same lookup after migration`() {
        val connection = connect()
        seedV6Schema(connection)
        seedV6Rows(connection)

        migrate(connection)

        connection.prepareStatement(
            "SELECT COUNT(*) FROM text_pages WHERE book_id = ? AND document_version = ? AND page_index = ? " +
                "AND source = ? AND text_schema_version = ? AND engine_version = ? AND layout_version = ?"
        ).use { statement ->
            statement.setString(1, "book-1")
            statement.setString(2, "doc-v1")
            statement.setInt(3, 0)
            statement.setString(4, "NATIVE_PDF")
            statement.setInt(5, 2)
            statement.setString(6, "native-v1")
            statement.setString(7, "")

            val result = statement.executeQuery()
            assertTrue(result.next())
            assertEquals(1, result.getInt(1))
        }
    }

    @Test
    fun `two rows differing only by layout_version can now both exist`() {
        val connection = connect()
        seedV6Schema(connection)
        seedV6Rows(connection)

        migrate(connection)

        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO text_pages(book_id, document_version, page_index, source, text_schema_version, " +
                    "engine_version, state, layout_version) VALUES " +
                    "('book-1', 'doc-v1', 0, 'NATIVE_PDF', 2, 'native-v1', 'COMPLETE', 'layout-a')"
            )

            val rows = statement.executeQuery(
                "SELECT COUNT(*) FROM text_pages WHERE book_id = 'book-1' AND document_version = 'doc-v1' " +
                    "AND page_index = 0 AND source = 'NATIVE_PDF' AND text_schema_version = 2 AND engine_version = 'native-v1'"
            )
            assertTrue(rows.next())
            assertEquals(2, rows.getInt(1))
        }
    }

    private fun columnNames(connection: Connection, table: String): List<String> {
        connection.createStatement().use { statement ->
            val result = statement.executeQuery("PRAGMA table_info($table)")
            val names = mutableListOf<String>()
            while (result.next()) names += result.getString("name")
            return names
        }
    }
}
