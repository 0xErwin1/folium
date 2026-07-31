package com.folium.reader.core.library

private fun requireOpaque(value: String, label: String) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.none { it.isISOControl() }) { "$label must not contain control characters" }
}

data class LibraryRootIdentity(
    val providerAuthority: String,
    val treeDocumentId: String
) {
    init {
        requireOpaque(providerAuthority, "providerAuthority")
        requireOpaque(treeDocumentId, "treeDocumentId")
    }
}

data class ProviderDocumentIdentity(
    val providerAuthority: String,
    val documentId: String
) {
    init {
        requireOpaque(providerAuthority, "providerAuthority")
        requireOpaque(documentId, "documentId")
    }
}

data class RootVersion(val value: String) {
    init { requireOpaque(value, "rootVersion") }
}

data class DocumentVersion(val value: String) {
    init { requireOpaque(value, "documentVersion") }
}

data class PersistedGrantState(
    val root: LibraryRootIdentity,
    val rootVersion: RootVersion,
    val readGranted: Boolean
)

enum class RecoveryReason {
    RootNotSelected,
    PermissionRevoked,
    ProviderUnavailable,
    RootOrDocumentMissing,
    MalformedMetadata,
    UnsupportedMetadata,
    TransientQueryFailure,
    DocumentUnreadable
}

enum class RecoveryAction { SelectRoot, RebindRoot, Retry, SkipDocument }

data class RecoveryState(val reason: RecoveryReason) {
    val action: RecoveryAction = when (reason) {
        RecoveryReason.RootNotSelected -> RecoveryAction.SelectRoot
        RecoveryReason.PermissionRevoked, RecoveryReason.ProviderUnavailable, RecoveryReason.RootOrDocumentMissing -> RecoveryAction.RebindRoot
        RecoveryReason.TransientQueryFailure -> RecoveryAction.Retry
        RecoveryReason.MalformedMetadata, RecoveryReason.UnsupportedMetadata, RecoveryReason.DocumentUnreadable -> RecoveryAction.SkipDocument
    }
}

data class CandidateMetadata(val mimeType: String?, val isDirectory: Boolean, val isOpenable: Boolean)

object LibraryCandidateFilter {
    fun accepts(candidate: CandidateMetadata): Boolean =
        !candidate.isDirectory && candidate.isOpenable && candidate.mimeType == "application/pdf"
}

data class LibraryDocumentCandidate(
    val identity: ProviderDocumentIdentity,
    val version: DocumentVersion,
    val mimeType: String,
    val isOpenable: Boolean
)

data class DocumentProbeFailure(
    val recovery: RecoveryState,
    val identity: ProviderDocumentIdentity? = null
)
