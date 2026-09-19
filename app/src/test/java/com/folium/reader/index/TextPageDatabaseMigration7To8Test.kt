package com.folium.reader.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs [MIGRATION_7_8_STATEMENTS] — the exact SQL [TextPageDatabase.MIGRATION_7_8] executes against
 * a real Room database — against a plain SQLite file, the same way
 * [TextPageDatabaseMigration6To7Test] exercises the previous migration without an Android
 * [android.app.Instrumentation].
 */
class TextPageDatabaseMigration7To8Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun connect(): Connection {
        val databaseFile = tempFolder.newFile("text-page-index-v7.db")
        return DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}")
    }

    private fun migrate(connection: Connection) {
        connection.createStatement().use { statement ->
            MIGRATION_7_8_STATEMENTS.forEach(statement::execute)
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

    @Test
    fun `the text_layout_usage table exists after migration`() {
        val connection = connect()

        migrate(connection)

        assertEquals(
            listOf("book_id", "document_version", "layout_version", "sequence"),
            columnNames(connection, "text_layout_usage")
        )
    }

    @Test
    fun `the table starts empty since no earlier version recorded layout recency`() {
        val connection = connect()

        migrate(connection)

        connection.createStatement().use { statement ->
            val rows = statement.executeQuery("SELECT COUNT(*) FROM text_layout_usage")
            assertTrue(rows.next())
            assertEquals(0, rows.getInt(1))
        }
    }

    @Test
    fun `a book can now record one row per layout it has used`() {
        val connection = connect()
        migrate(connection)

        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO text_layout_usage(book_id, document_version, layout_version, sequence) " +
                    "VALUES ('book-1', 'doc-v1', 'layout-a', 1)"
            )
            statement.execute(
                "INSERT INTO text_layout_usage(book_id, document_version, layout_version, sequence) " +
                    "VALUES ('book-1', 'doc-v1', 'layout-b', 2)"
            )

            val rows = statement.executeQuery(
                "SELECT layout_version FROM text_layout_usage WHERE book_id='book-1' " +
                    "AND document_version='doc-v1' ORDER BY sequence DESC"
            )
            val ordered = mutableListOf<String>()
            while (rows.next()) ordered += rows.getString(1)
            assertEquals(listOf("layout-b", "layout-a"), ordered)
        }
    }
}
