package com.folium.reader.saf

import com.folium.reader.core.library.RecoveryReason
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** The bytes read from a source's prefix, or the typed reason none could be read. */
sealed class PrefixReadResult {
    data class Bytes(val bytes: ByteArray) : PrefixReadResult()
    data class Failed(val reason: RecoveryReason) : PrefixReadResult()
}

/**
 * Copies an already-opened input stream into a private destination file, typing every failure by
 * the exception class that produced it.
 *
 * This is the surviving half of the old SAF document source: the identity check, the persisted-root
 * lookup and the result type built around them are gone with the tree-grant flow, but the copy
 * primitive and its exception-to-[RecoveryReason] discipline are still exactly what an import needs
 * once the caller has an [InputStream] in hand, whatever produced it.
 */
object DocumentCopy {

    /** `null` on success; the typed [RecoveryReason] the caught exception maps to on failure. */
    fun copyStream(open: () -> InputStream, destination: File): RecoveryReason? = try {
        destination.parentFile?.mkdirs()
        open().use { source -> destination.outputStream().use { sink -> source.copyTo(sink) } }
        null
    } catch (error: Exception) {
        recoveryReasonFor(error)
    }

    /**
     * Reads up to [byteCount] bytes from the start of a freshly opened stream, for a caller that
     * needs to look at a source's content before deciding what to do with the rest of it. A source
     * shorter than [byteCount] yields whatever it had, not a failure — a short file simply matches
     * no signature the caller is looking for.
     */
    fun readPrefix(open: () -> InputStream, byteCount: Int): PrefixReadResult = try {
        PrefixReadResult.Bytes(open().use { it.readPrefixBytes(byteCount) })
    } catch (error: Exception) {
        PrefixReadResult.Failed(recoveryReasonFor(error))
    }

    private fun InputStream.readPrefixBytes(byteCount: Int): ByteArray {
        val buffer = ByteArray(byteCount)
        var read = 0
        while (read < byteCount) {
            val count = read(buffer, read, byteCount - read)
            if (count == -1) break
            read += count
        }
        return buffer.copyOf(read)
    }

    /**
     * The one exception-to-[RecoveryReason] ladder both [copyStream] and [readPrefix] route
     * through, so a caught class is classified identically whichever call caught it.
     */
    private fun recoveryReasonFor(error: Exception): RecoveryReason = when (error) {
        is SecurityException -> RecoveryReason.PermissionRevoked
        is FileNotFoundException -> RecoveryReason.SourceMissing
        is IllegalStateException -> RecoveryReason.ProviderUnavailable
        is IOException -> RecoveryReason.DocumentUnreadable
        else -> RecoveryReason.TransientQueryFailure
    }
}
