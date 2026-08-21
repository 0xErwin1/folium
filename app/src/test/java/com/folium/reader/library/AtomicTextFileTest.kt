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

        assertTrue(file.write(listOf("line-one", "line-two")))

        assertEquals(listOf("line-one", "line-two"), file.readLines())
        assertFalse(File(tempFolder.root, "catalog.tmp").exists())
    }

    @Test
    fun `write does not depend on the JVM temp directory, proving the temp file is a sibling of the destination`() {
        val target = File(tempFolder.root, "catalog")
        val file = AtomicTextFile(target)
        val originalTmpDir = System.getProperty("java.io.tmpdir")
        val nonexistentTmpDir = File(tempFolder.root, "no-such-tmp-dir").absolutePath

        System.setProperty("java.io.tmpdir", nonexistentTmpDir)
        try {
            assertTrue(file.write(listOf("line-one")))
        } finally {
            System.setProperty("java.io.tmpdir", originalTmpDir)
        }

        assertEquals(listOf("line-one"), file.readLines())
    }

    @Test
    fun `the temp file write stages into is a sibling of the destination, not a relocated one`() {
        val target = File(File(tempFolder.root, "sub"), "catalog")
        val file = AtomicTextFile(target)

        assertEquals(File(File(tempFolder.root, "sub"), "catalog.tmp"), file.tempFile)
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

    @Test
    fun `delete removes the target file`() {
        val target = File(tempFolder.root, "catalog")
        val file = AtomicTextFile(target)
        file.write(listOf("line-one"))

        assertTrue(file.delete())

        assertFalse(target.exists())
    }

    @Test
    fun `delete succeeds when the target never existed`() {
        val file = AtomicTextFile(File(tempFolder.root, "missing"))

        assertTrue(file.delete())
    }

    @Test
    fun `delete removes a temporary sibling a failed write left behind`() {
        val target = File(tempFolder.root, "catalog")
        val file = AtomicTextFile(target)
        file.tempFile.parentFile?.mkdirs()
        file.tempFile.writeText("leftover")

        assertTrue(file.delete())

        assertFalse(file.tempFile.exists())
    }
}
