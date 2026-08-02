package com.folium.reader.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.folium.reader.core.library.ProviderDocumentIdentity
import com.folium.reader.core.library.RecoveryReason
import com.folium.reader.core.library.RecoveryState
import java.io.File

sealed class SafDocumentResult {
    data object Copied : SafDocumentResult()
    data class Unavailable(val recovery: RecoveryState) : SafDocumentResult()
}

/**
 * Streams a document out of the persisted SAF root and into a private file the caller owns.
 *
 * A rendering engine needs a seekable path, while the Storage Access Framework only ever hands out
 * a provider-scoped URI. Copying is what bridges the two without asking for a storage permission:
 * the read still goes through the persisted tree grant, and the copy lands in the app's own private
 * storage, which nothing else on the device can read.
 *
 * The document is addressed by its stable [ProviderDocumentIdentity] rather than by a URI carried
 * over from whoever listed it, so a reader can re-resolve the same document across relaunches. Its
 * authority is checked against the persisted root's, because an identity from a different provider
 * is not covered by the grant this class reads through.
 */
class SafDocumentSource(
    private val resolver: ContentResolver,
    private val storage: SafRootStorage
) {
    fun copyTo(identity: ProviderDocumentIdentity, destination: File): SafDocumentResult {
        val stored = storage.read()
            ?: return unavailable(RecoveryReason.RootNotSelected)

        if (stored.identity.providerAuthority != identity.providerAuthority) {
            return unavailable(RecoveryReason.RootOrDocumentMissing)
        }

        return try {
            val treeUri = Uri.parse(stored.treeUri)
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, identity.documentId)
            copyStream(documentUri, destination)
            SafDocumentResult.Copied
        } catch (_: SecurityException) {
            unavailable(RecoveryReason.PermissionRevoked)
        } catch (_: java.io.FileNotFoundException) {
            unavailable(RecoveryReason.RootOrDocumentMissing)
        } catch (_: IllegalArgumentException) {
            unavailable(RecoveryReason.MalformedMetadata)
        } catch (_: IllegalStateException) {
            unavailable(RecoveryReason.ProviderUnavailable)
        } catch (_: java.io.IOException) {
            unavailable(RecoveryReason.DocumentUnreadable)
        } catch (_: RuntimeException) {
            unavailable(RecoveryReason.TransientQueryFailure)
        }
    }

    private fun copyStream(documentUri: Uri, destination: File) {
        destination.parentFile?.mkdirs()
        val input = resolver.openInputStream(documentUri) ?: throw java.io.FileNotFoundException()
        input.use { source -> destination.outputStream().use { sink -> source.copyTo(sink) } }
    }

    private fun unavailable(reason: RecoveryReason) =
        SafDocumentResult.Unavailable(RecoveryState(reason))
}
