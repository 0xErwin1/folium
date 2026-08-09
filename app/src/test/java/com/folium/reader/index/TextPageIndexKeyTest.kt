package com.folium.reader.index

import com.folium.reader.core.library.BookId
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextPageIndexKeyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun sha256IsDeterministicAndChangesOnlyWithContent() {
        val first = temporaryFolder.newFile("first.pdf").apply { writeText("same bytes") }
        val second = temporaryFolder.newFile("second.pdf").apply { writeText("same bytes") }

        assertEquals(sha256(first), sha256(first))
        assertEquals(sha256(first), sha256(second))
        second.appendText(" changed")
        assertNotEquals(sha256(first), sha256(second))
    }

    @Test fun exactKeyAccountsForEveryDurableVersionDimension() {
        val base = TextPageIndexKey(
            BookId("book"),
            DocumentContentVersion("00".repeat(32)),
            4,
            TextSource.NATIVE_PDF,
            TEXT_PAGE_SCHEMA_VERSION,
            TextEngineVersion("native-v1")
        )

        assertNotEquals(base, base.copy(bookId = BookId("other")))
        assertNotEquals(base, base.copy(documentVersion = DocumentContentVersion("11".repeat(32))))
        assertNotEquals(base, base.copy(pageIndex = 5))
        assertNotEquals(base, base.copy(source = TextSource.OCR))
        assertNotEquals(base, base.copy(textSchemaVersion = TEXT_PAGE_SCHEMA_VERSION + 1))
        assertNotEquals(base, base.copy(engineVersion = TextEngineVersion("native-v2")))
    }
}
