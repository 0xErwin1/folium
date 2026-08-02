package com.folium.reader.library

import androidx.annotation.StringRes
import com.folium.reader.R
import com.folium.reader.core.library.RecoveryAction
import com.folium.reader.core.library.RecoveryReason

/**
 * Maps typed recovery outcomes to the copy the reader sees.
 *
 * Deliberately free of Compose so the mapping stays exhaustively unit-testable, and deliberately
 * exhaustive over both enums so a newly added [RecoveryReason] or [RecoveryAction] fails to
 * compile here instead of rendering an unexplained blank state.
 */
object LibraryCopy {

    @StringRes
    fun rootTitle(reason: RecoveryReason): Int = when (reason) {
        RecoveryReason.RootNotSelected -> R.string.library_root_title_not_selected
        RecoveryReason.PermissionRevoked -> R.string.library_root_title_permission_revoked
        RecoveryReason.ProviderUnavailable -> R.string.library_root_title_provider_unavailable
        RecoveryReason.RootOrDocumentMissing -> R.string.library_root_title_missing
        RecoveryReason.SourceMissing -> R.string.library_root_title_source_missing
        RecoveryReason.MalformedMetadata -> R.string.library_root_title_malformed
        RecoveryReason.UnsupportedMetadata -> R.string.library_root_title_unsupported
        RecoveryReason.TransientQueryFailure -> R.string.library_root_title_transient
        RecoveryReason.DocumentUnreadable -> R.string.library_root_title_unreadable
    }

    @StringRes
    fun rootBody(reason: RecoveryReason): Int = when (reason) {
        RecoveryReason.RootNotSelected -> R.string.library_root_body_not_selected
        RecoveryReason.PermissionRevoked -> R.string.library_root_body_permission_revoked
        RecoveryReason.ProviderUnavailable -> R.string.library_root_body_provider_unavailable
        RecoveryReason.RootOrDocumentMissing -> R.string.library_root_body_missing
        RecoveryReason.SourceMissing -> R.string.library_root_body_source_missing
        RecoveryReason.MalformedMetadata -> R.string.library_root_body_malformed
        RecoveryReason.UnsupportedMetadata -> R.string.library_root_body_unsupported
        RecoveryReason.TransientQueryFailure -> R.string.library_root_body_transient
        RecoveryReason.DocumentUnreadable -> R.string.library_root_body_unreadable
    }

    @StringRes
    fun skipExplanation(reason: RecoveryReason): Int = when (reason) {
        RecoveryReason.RootNotSelected -> R.string.library_skip_not_selected
        RecoveryReason.PermissionRevoked -> R.string.library_skip_permission_revoked
        RecoveryReason.ProviderUnavailable -> R.string.library_skip_provider_unavailable
        RecoveryReason.RootOrDocumentMissing -> R.string.library_skip_missing
        RecoveryReason.SourceMissing -> R.string.library_skip_source_missing
        RecoveryReason.MalformedMetadata -> R.string.library_skip_malformed
        RecoveryReason.UnsupportedMetadata -> R.string.library_skip_unsupported
        RecoveryReason.TransientQueryFailure -> R.string.library_skip_transient
        RecoveryReason.DocumentUnreadable -> R.string.library_skip_unreadable
    }

    @StringRes
    fun actionLabel(action: RecoveryAction): Int = when (action) {
        RecoveryAction.SelectRoot -> R.string.library_action_select_root
        RecoveryAction.RebindRoot -> R.string.library_action_rebind_root
        RecoveryAction.Retry -> R.string.library_action_retry
        RecoveryAction.SkipDocument -> R.string.library_action_skip_document
    }

    /**
     * Distinguishes "the reader has not chosen a folder yet" from a genuine failure. Both reach
     * the UI through the same unavailable state, but only one of them is the reader's fault to fix
     * and neither should be phrased like the other.
     */
    fun isFirstSelection(reason: RecoveryReason): Boolean = reason == RecoveryReason.RootNotSelected
}
