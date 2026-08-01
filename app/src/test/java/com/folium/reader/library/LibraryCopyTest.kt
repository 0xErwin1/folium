package com.folium.reader.library

import com.folium.reader.core.library.RecoveryAction
import com.folium.reader.core.library.RecoveryReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCopyTest {

    @Test fun every_recovery_reason_has_its_own_root_level_title_and_body() {
        val titles = RecoveryReason.entries.associateWith { LibraryCopy.rootTitle(it) }
        val bodies = RecoveryReason.entries.associateWith { LibraryCopy.rootBody(it) }

        assertTrue("no root title may be unresolved", titles.values.none { it == 0 })
        assertTrue("no root body may be unresolved", bodies.values.none { it == 0 })
        assertEquals("each reason needs its own title, not a shared catch-all", titles.size, titles.values.toSet().size)
        assertEquals("each reason needs its own body, not a shared catch-all", bodies.size, bodies.values.toSet().size)
        assertTrue("a title must never be reused as a body", titles.values.toSet().intersect(bodies.values.toSet()).isEmpty())
    }

    @Test fun every_recovery_reason_explains_why_a_document_was_skipped() {
        val explanations = RecoveryReason.entries.associateWith { LibraryCopy.skipExplanation(it) }

        assertTrue("no skip explanation may be unresolved", explanations.values.none { it == 0 })
        assertEquals("each reason needs its own explanation", explanations.size, explanations.values.toSet().size)
    }

    @Test fun every_recovery_action_has_its_own_button_label() {
        val labels = RecoveryAction.entries.associateWith { LibraryCopy.actionLabel(it) }

        assertTrue("no action label may be unresolved", labels.values.none { it == 0 })
        assertEquals("each action needs its own label", labels.size, labels.values.toSet().size)
    }

    @Test fun the_unselected_root_is_an_invitation_rather_than_a_recovery_prompt() {
        assertTrue(LibraryCopy.isFirstSelection(RecoveryReason.RootNotSelected))
        RecoveryReason.entries
            .filter { it != RecoveryReason.RootNotSelected }
            .forEach { assertTrue("$it must be presented as a failure", !LibraryCopy.isFirstSelection(it)) }
    }
}
