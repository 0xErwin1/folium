package com.folium.reader.core.library

internal fun requireOpaque(value: String, label: String) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.none { it.isISOControl() }) { "$label must not contain control characters" }
}

/** Why a picked import source could not be read, typed by the exception class that reported it. */
enum class RecoveryReason {
    PermissionRevoked,
    ProviderUnavailable,
    SourceMissing,
    TransientQueryFailure,
    DocumentUnreadable
}
