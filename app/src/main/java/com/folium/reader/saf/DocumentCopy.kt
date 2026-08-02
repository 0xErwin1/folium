package com.folium.reader.saf

import com.folium.reader.core.library.RecoveryReason
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

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
    } catch (_: SecurityException) {
        RecoveryReason.PermissionRevoked
    } catch (_: FileNotFoundException) {
        RecoveryReason.SourceMissing
    } catch (_: IllegalStateException) {
        RecoveryReason.ProviderUnavailable
    } catch (_: IOException) {
        RecoveryReason.DocumentUnreadable
    } catch (_: RuntimeException) {
        RecoveryReason.TransientQueryFailure
    }
}
