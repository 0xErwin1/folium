package com.folium.reader.saf

import com.folium.reader.core.library.RecoveryReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Pins every row of the exception-to-[RecoveryReason] catch table design §3.2 requires. */
class DocumentCopyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun destination() = File(tempFolder.newFolder(), "document.pdf")

    @Test
    fun `SecurityException maps to PermissionRevoked`() {
        val reason = DocumentCopy.copyStream({ throw SecurityException() }, destination())
        assertEquals(RecoveryReason.PermissionRevoked, reason)
    }

    @Test
    fun `FileNotFoundException maps to SourceMissing`() {
        val reason = DocumentCopy.copyStream({ throw FileNotFoundException() }, destination())
        assertEquals(RecoveryReason.SourceMissing, reason)
    }

    @Test
    fun `IllegalStateException maps to ProviderUnavailable`() {
        val reason = DocumentCopy.copyStream({ throw IllegalStateException() }, destination())
        assertEquals(RecoveryReason.ProviderUnavailable, reason)
    }

    @Test
    fun `IOException maps to DocumentUnreadable`() {
        val reason = DocumentCopy.copyStream({ throw IOException() }, destination())
        assertEquals(RecoveryReason.DocumentUnreadable, reason)
    }

    @Test
    fun `a generic RuntimeException maps to TransientQueryFailure`() {
        val reason = DocumentCopy.copyStream({ throw RuntimeException() }, destination())
        assertEquals(RecoveryReason.TransientQueryFailure, reason)
    }

    @Test
    fun `success returns null`() {
        val reason = DocumentCopy.copyStream({ byteArrayOf(1, 2, 3).inputStream() }, destination())
        assertNull(reason)
    }

    @Test
    fun `readPrefix returns the requested byte count from a long enough source`() {
        val bytes = ByteArray(100) { it.toByte() }

        val result = DocumentCopy.readPrefix({ bytes.inputStream() }, 58)

        val read = result as PrefixReadResult.Bytes
        assertArrayEquals(bytes.copyOf(58), read.bytes)
    }

    @Test
    fun `readPrefix returns whatever a short source has rather than failing`() {
        val bytes = byteArrayOf(1, 2, 3)

        val result = DocumentCopy.readPrefix({ bytes.inputStream() }, 58)

        val read = result as PrefixReadResult.Bytes
        assertArrayEquals(bytes, read.bytes)
    }

    @Test
    fun `readPrefix maps a SecurityException through the same ladder as copyStream`() {
        val result = DocumentCopy.readPrefix({ throw SecurityException() }, 58)

        val failed = result as PrefixReadResult.Failed
        assertEquals(RecoveryReason.PermissionRevoked, failed.reason)
    }

    @Test
    fun `readPrefix maps a FileNotFoundException to SourceMissing`() {
        val result = DocumentCopy.readPrefix({ throw FileNotFoundException() }, 58)

        val failed = result as PrefixReadResult.Failed
        assertEquals(RecoveryReason.SourceMissing, failed.reason)
    }
}
