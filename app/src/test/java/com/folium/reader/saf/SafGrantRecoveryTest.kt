package com.folium.reader.saf

import com.folium.reader.core.library.RecoveryAction
import com.folium.reader.core.library.RecoveryReason
import com.folium.reader.core.library.RecoveryState
import org.junit.Assert.assertEquals
import org.junit.Test

class SafGrantRecoveryTest {

    private val expectedActionByReason: Map<RecoveryReason, RecoveryAction> = mapOf(
        RecoveryReason.RootNotSelected to RecoveryAction.SelectRoot,
        RecoveryReason.PermissionRevoked to RecoveryAction.RebindRoot,
        RecoveryReason.ProviderUnavailable to RecoveryAction.RebindRoot,
        RecoveryReason.RootOrDocumentMissing to RecoveryAction.RebindRoot,
        RecoveryReason.MalformedMetadata to RecoveryAction.SkipDocument,
        RecoveryReason.UnsupportedMetadata to RecoveryAction.SkipDocument,
        RecoveryReason.TransientQueryFailure to RecoveryAction.Retry,
        RecoveryReason.DocumentUnreadable to RecoveryAction.SkipDocument
    )

    @Test fun every_recovery_reason_has_a_safe_explicit_action() {
        assertEquals(
            "expectedActionByReason must cover every RecoveryReason so a newly added reason fails this test rather than going uncovered",
            RecoveryReason.entries.toSet(),
            expectedActionByReason.keys
        )

        RecoveryReason.entries.forEach { reason ->
            val expectedAction = requireNotNull(expectedActionByReason[reason]) { "No expected action mapped for $reason" }
            assertEquals("$reason must resolve to $expectedAction", expectedAction, RecoveryState(reason).action)
        }
    }
}
