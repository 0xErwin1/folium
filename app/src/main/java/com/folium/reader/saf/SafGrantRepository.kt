package com.folium.reader.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.folium.reader.core.library.CandidateMetadata
import com.folium.reader.core.library.DocumentProbeFailure
import com.folium.reader.core.library.DocumentVersion
import com.folium.reader.core.library.LibraryCandidateFilter
import com.folium.reader.core.library.LibraryDocumentCandidate
import com.folium.reader.core.library.LibraryRootIdentity
import com.folium.reader.core.library.PersistedGrantState
import com.folium.reader.core.library.ProviderDocumentIdentity
import com.folium.reader.core.library.RecoveryReason
import com.folium.reader.core.library.RecoveryState
import com.folium.reader.core.library.RootVersion

const val REQUIRED_TREE_GRANT_FLAGS: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

fun persistedReadGrantFlags(returnedFlags: Int): Int = returnedFlags and REQUIRED_TREE_GRANT_FLAGS

enum class SafDiagnosticStage { Grant, PersistedPermission, RootQuery }

data class SafFailure(val recovery: RecoveryState, val stage: SafDiagnosticStage)
sealed class SafRootResult {
    data class Ready(val grant: PersistedGrantState) : SafRootResult()
    data class Unavailable(val failure: SafFailure) : SafRootResult()
}

data class StoredSafRoot(val treeUri: String, val identity: LibraryRootIdentity, val version: RootVersion)
interface SafRootStorage {
    fun read(): StoredSafRoot?
    fun write(root: StoredSafRoot)
    fun clear()
}

class SharedPreferencesSafRootStorage(context: Context) : SafRootStorage {
    private val preferences = context.getSharedPreferences("saf-root", Context.MODE_PRIVATE)

    override fun read(): StoredSafRoot? {
        if (preferences.getInt("schema", 0) != 2) return clearAndNull()
        val treeUri = preferences.getString("tree-uri", null) ?: return clearAndNull()
        val authority = preferences.getString("authority", null) ?: return clearAndNull()
        val treeId = preferences.getString("tree-id", null) ?: return clearAndNull()
        val version = preferences.getString("root-version", null) ?: return clearAndNull()
        return try {
            val parsed = Uri.parse(treeUri)
            if (parsed.scheme != ContentResolver.SCHEME_CONTENT || parsed.authority != authority ||
                !DocumentsContract.isTreeUri(parsed) || DocumentsContract.getTreeDocumentId(parsed) != treeId
            ) return clearAndNull()
            StoredSafRoot(treeUri, LibraryRootIdentity(authority, treeId), RootVersion(version))
        } catch (_: IllegalArgumentException) {
            clearAndNull()
        }
    }

    override fun write(root: StoredSafRoot) {
        preferences.edit()
            .clear()
            .putInt("schema", 2)
            .putString("tree-uri", root.treeUri)
            .putString("authority", root.identity.providerAuthority)
            .putString("tree-id", root.identity.treeDocumentId)
            .putString("root-version", root.version.value)
            .apply()
    }

    override fun clear() { preferences.edit().clear().apply() }
    private fun clearAndNull(): StoredSafRoot? { clear(); return null }
}

class SafGrantRepository(
    private val resolver: ContentResolver,
    private val storage: SafRootStorage
) {
    fun selectionIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(REQUIRED_TREE_GRANT_FLAGS)

    /**
     * `returnedFlags` is accepted for API/call-site stability but is not used to gate the
     * bind. `ACTION_OPEN_DOCUMENT_TREE` only guarantees grant flags on the *request* Intent,
     * not on the result Intent; `takePersistableUriPermission` is the actual source of truth
     * for whether the persisted read grant was obtained.
     */
    fun bind(treeUri: Uri, returnedFlags: Int): SafRootResult {
        return try {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val root = rootBinding(treeUri) ?: return unavailable(RecoveryReason.MalformedMetadata, SafDiagnosticStage.RootQuery)
            if (!hasReadPermission(treeUri)) return unavailable(RecoveryReason.PermissionRevoked, SafDiagnosticStage.PersistedPermission)
            storage.write(StoredSafRoot(treeUri.toString(), root.identity, root.version))
            SafRootResult.Ready(PersistedGrantState(root.identity, root.version, true))
        } catch (_: SecurityException) { unavailable(RecoveryReason.PermissionRevoked, SafDiagnosticStage.Grant) }
        catch (_: java.io.FileNotFoundException) { unavailable(RecoveryReason.RootOrDocumentMissing, SafDiagnosticStage.RootQuery) }
        catch (_: IllegalArgumentException) { unavailable(RecoveryReason.MalformedMetadata, SafDiagnosticStage.RootQuery) }
        catch (_: IllegalStateException) { unavailable(RecoveryReason.ProviderUnavailable, SafDiagnosticStage.RootQuery) }
        catch (_: RuntimeException) { unavailable(RecoveryReason.TransientQueryFailure, SafDiagnosticStage.RootQuery) }
    }

    fun recover(): SafRootResult {
        val stored = storage.read() ?: return unavailable(RecoveryReason.RootNotSelected, SafDiagnosticStage.PersistedPermission)
        val uri = Uri.parse(stored.treeUri)
        if (!hasReadPermission(uri)) return unavailable(RecoveryReason.PermissionRevoked, SafDiagnosticStage.PersistedPermission)
        return try {
            val root = rootBinding(uri) ?: return unavailable(RecoveryReason.MalformedMetadata, SafDiagnosticStage.RootQuery)
            if (root.identity != stored.identity) unavailable(RecoveryReason.RootOrDocumentMissing, SafDiagnosticStage.RootQuery)
            else SafRootResult.Ready(PersistedGrantState(root.identity, root.version, true))
        } catch (_: SecurityException) { unavailable(RecoveryReason.PermissionRevoked, SafDiagnosticStage.RootQuery) }
        catch (_: java.io.FileNotFoundException) { unavailable(RecoveryReason.RootOrDocumentMissing, SafDiagnosticStage.RootQuery) }
        catch (_: IllegalArgumentException) { unavailable(RecoveryReason.MalformedMetadata, SafDiagnosticStage.RootQuery) }
        catch (_: IllegalStateException) { unavailable(RecoveryReason.ProviderUnavailable, SafDiagnosticStage.RootQuery) }
        catch (_: RuntimeException) { unavailable(RecoveryReason.TransientQueryFailure, SafDiagnosticStage.RootQuery) }
    }

    fun clear() = storage.clear()

    fun probePdfCandidates(): SafCandidateProbeResult {
        val stored = storage.read() ?: return SafCandidateProbeResult.Failure(RecoveryState(RecoveryReason.RootNotSelected))
        val treeUri = Uri.parse(stored.treeUri)
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, stored.identity.treeDocumentId)
            resolver.query(children, CHILD_PROJECTION, null, null, null).use { cursor ->
                if (cursor == null) return SafCandidateProbeResult.Failure(RecoveryState(RecoveryReason.TransientQueryFailure))
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val versionIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idIndex < 0 || mimeIndex < 0 || versionIndex < 0) return SafCandidateProbeResult.Failure(RecoveryState(RecoveryReason.MalformedMetadata))
                val candidates = mutableListOf<LibraryDocumentCandidate>()
                val skipped = mutableListOf<DocumentProbeFailure>()
                while (cursor.moveToNext()) {
                    val documentId = if (cursor.isNull(idIndex)) null else cursor.getString(idIndex)
                    val identity = documentId?.let { id ->
                        try { ProviderDocumentIdentity(stored.identity.providerAuthority, id) } catch (_: IllegalArgumentException) { null }
                    }
                    if (identity == null) {
                        skipped += DocumentProbeFailure(RecoveryState(RecoveryReason.MalformedMetadata))
                        continue
                    }
                    if (cursor.isNull(mimeIndex) || cursor.isNull(versionIndex)) {
                        skipped += DocumentProbeFailure(RecoveryState(RecoveryReason.MalformedMetadata), identity)
                        continue
                    }
                    val mime = try {
                        cursor.getString(mimeIndex)
                    } catch (_: RuntimeException) {
                        skipped += DocumentProbeFailure(RecoveryState(RecoveryReason.DocumentUnreadable), identity)
                        continue
                    }
                    val version = try { DocumentVersion(cursor.getLong(versionIndex).toString()) } catch (_: RuntimeException) { null }
                    if (mime.isBlank() || version == null) {
                        skipped += DocumentProbeFailure(RecoveryState(RecoveryReason.UnsupportedMetadata), identity)
                        continue
                    }
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR || mime != "application/pdf") continue
                    val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    val openable = try {
                        resolver.openFileDescriptor(document, "r")?.use { true } ?: false
                    } catch (_: SecurityException) {
                        skipped += DocumentProbeFailure(RecoveryState(RecoveryReason.DocumentUnreadable), identity)
                        continue
                    } catch (_: java.io.FileNotFoundException) {
                        skipped += DocumentProbeFailure(RecoveryState(RecoveryReason.DocumentUnreadable), identity)
                        continue
                    } catch (_: RuntimeException) {
                        skipped += DocumentProbeFailure(RecoveryState(RecoveryReason.DocumentUnreadable), identity)
                        continue
                    }
                    if (!LibraryCandidateFilter.accepts(CandidateMetadata(mime, false, openable))) {
                        skipped += DocumentProbeFailure(RecoveryState(RecoveryReason.DocumentUnreadable), identity)
                        continue
                    }
                    candidates += LibraryDocumentCandidate(identity, version, mime, true)
                }
                SafCandidateProbeResult.Candidates(candidates, skipped)
            }
        } catch (_: SecurityException) { SafCandidateProbeResult.Failure(RecoveryState(RecoveryReason.PermissionRevoked)) }
        catch (_: java.io.FileNotFoundException) { SafCandidateProbeResult.Failure(RecoveryState(RecoveryReason.RootOrDocumentMissing)) }
        catch (_: IllegalStateException) { SafCandidateProbeResult.Failure(RecoveryState(RecoveryReason.ProviderUnavailable)) }
        catch (_: RuntimeException) { SafCandidateProbeResult.Failure(RecoveryState(RecoveryReason.TransientQueryFailure)) }
    }

    private fun hasReadPermission(uri: Uri): Boolean = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun rootBinding(uri: Uri): RootBinding? {
        val authority = uri.authority ?: return null
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, treeId)
        resolver.query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) throw java.io.FileNotFoundException()
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (index < 0 || cursor.isNull(index)) return null
            return RootBinding(LibraryRootIdentity(authority, treeId), RootVersion(cursor.getLong(index).toString()))
        }
    }

    private fun unavailable(reason: RecoveryReason, stage: SafDiagnosticStage): SafRootResult.Unavailable {
        if (reason == RecoveryReason.PermissionRevoked || reason == RecoveryReason.RootOrDocumentMissing) storage.clear()
        return SafRootResult.Unavailable(SafFailure(RecoveryState(reason), stage))
    }

    private data class RootBinding(val identity: LibraryRootIdentity, val version: RootVersion)
    private companion object {
        val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}

sealed class SafCandidateProbeResult {
    data class Candidates(
        val candidates: List<LibraryDocumentCandidate>,
        val skipped: List<DocumentProbeFailure>
    ) : SafCandidateProbeResult()
    data class Failure(val recovery: RecoveryState) : SafCandidateProbeResult()
}
