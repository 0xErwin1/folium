package com.folium.reader.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicTextFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `write then readLines round trips content and leaves no temp file`() {
        val target = File(tempFolder.root, "catalog")
        val file = AtomicTextFile(target)

        file.write(listOf("line-one", "line-two"))

        assertEquals(listOf("line-one", "line-two"), file.readLines())
        assertFalse(File(tempFolder.root, "catalog.tmp").exists())
    }

    @Test
    fun `readLines is empty when the file is missing`() {
        val file = AtomicTextFile(File(tempFolder.root, "missing"))

        assertEquals(emptyList<String>(), file.readLines())
    }

    @Test
    fun `write replaces the previous content on a second successful write`() {
        val target = File(tempFolder.root, "catalog")
        val file = AtomicTextFile(target)

        file.write(listOf("original"))
        file.write(listOf("replacement"))

        assertEquals(listOf("replacement"), file.readLines())
    }

    @Test
    fun `previous content survives a write that cannot create its temp file`() {
        val target = File(tempFolder.root, "catalog")
        val file = AtomicTextFile(target)
        file.write(listOf("original"))

        assertTrue(tempFolder.root.setWritable(false))
        try {
            file.write(listOf("replacement"))
        } finally {
            tempFolder.root.setWritable(true)
        }

        assertEquals(listOf("original"), file.readLines())
        assertFalse(File(tempFolder.root, "catalog.tmp").exists())
    }
}
