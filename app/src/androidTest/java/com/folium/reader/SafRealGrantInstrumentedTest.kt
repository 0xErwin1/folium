package com.folium.reader

import android.app.Activity
import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.folium.reader.core.library.RecoveryReason
import com.folium.reader.saf.SafCandidateProbeResult
import com.folium.reader.saf.SafGrantRepository
import com.folium.reader.saf.SafRootResult
import com.folium.reader.saf.SharedPreferencesSafRootStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafRealGrantInstrumentedTest {
    @Test fun real_documents_ui_tree_grant_survives_recreation_and_recovers_typed_failures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testContext = instrumentation.context
        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.Normal)
        FixtureDocumentsProvider.assertRootProjectionContract()
        FixtureDocumentsProvider.assertAncestryContract()
        val provider = FixtureDocumentsProvider()
        assertTrue(provider.isChildDocument(FixtureDocumentsProvider.ROOT, FixtureDocumentsProvider.PDF))
        assertTrue(provider.isChildDocument(FixtureDocumentsProvider.ROOT, FixtureDocumentsProvider.DIRECTORY))
        assertFalse(provider.isChildDocument(FixtureDocumentsProvider.ROOT, FixtureDocumentsProvider.ROOT))
        assertFalse(provider.isChildDocument(FixtureDocumentsProvider.ROOT, "unknown"))
        assertFalse(provider.isChildDocument("unknown", FixtureDocumentsProvider.PDF))
        val storage = SharedPreferencesSafRootStorage(context)
        storage.clear()
        val repository = SafGrantRepository(context.contentResolver, storage)
        val result = selectWithDocumentsUi(repository.selectionIntent())
        assertEquals(Activity.RESULT_OK, result.first)
        val returned = requireNotNull(result.second)
        val treeUri = requireNotNull(returned.data)
        assertTrue(DocumentsContract.isTreeUri(treeUri))
        val bound = repository.bind(treeUri, returned.flags)
        assertTrue("bind failure=${(bound as? SafRootResult.Unavailable)?.failure?.recovery?.reason}", bound is SafRootResult.Ready)
        assertReadOnlyPersisted(context, treeUri)

        ActivityScenario.launch<HarnessActivity>(Intent(context, HarnessActivity::class.java)).use { scenario -> scenario.recreate() }
        val recreated = SafGrantRepository(context.contentResolver, SharedPreferencesSafRootStorage(context))
        val ready = recreated.recover() as SafRootResult.Ready
        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.Renamed)
        val renamed = recreated.recover() as SafRootResult.Ready
        assertEquals(ready.grant.root, renamed.grant.root)
        assertFalse(ready.grant.rootVersion == renamed.grant.rootVersion)
        assertReadOnlyPersisted(context, treeUri)

        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.Normal)
        val candidates = recreated.probePdfCandidates() as SafCandidateProbeResult.Candidates
        assertEquals(2, candidates.candidates.size)
        assertTrue(candidates.candidates.all { it.mimeType == "application/pdf" && it.isOpenable })
        assertTrue(candidates.candidates.any { it.identity.documentId == FixtureDocumentsProvider.ODD_NAME_PDF })
        val pdf = candidates.candidates.single { it.identity.documentId == FixtureDocumentsProvider.PDF }
        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.Renamed)
        val renamedPdf = (recreated.probePdfCandidates() as SafCandidateProbeResult.Candidates).candidates.single { it.identity.documentId == FixtureDocumentsProvider.PDF }
        assertEquals(pdf.identity, renamedPdf.identity)
        assertFalse(pdf.version == renamedPdf.version)
        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.Unreadable)
        val unreadable = recreated.probePdfCandidates() as SafCandidateProbeResult.Candidates
        assertEquals(1, unreadable.candidates.size)
        assertTrue(unreadable.skipped.any { it.recovery.reason == RecoveryReason.DocumentUnreadable && it.identity?.documentId == FixtureDocumentsProvider.PDF })

        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.UnstablePdf)
        val unstable = recreated.probePdfCandidates() as SafCandidateProbeResult.Candidates
        assertEquals(1, unstable.candidates.size)
        assertTrue(unstable.candidates.none { it.identity.documentId == FixtureDocumentsProvider.PDF })
        assertTrue(unstable.skipped.any { it.recovery.reason == RecoveryReason.DocumentUnreadable && it.identity?.documentId == FixtureDocumentsProvider.PDF })

        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.MalformedChild)
        val malformedChild = recreated.probePdfCandidates() as SafCandidateProbeResult.Candidates
        assertEquals(2, malformedChild.candidates.size)
        assertTrue(malformedChild.skipped.any { it.recovery.reason == RecoveryReason.MalformedMetadata })

        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.RootMalformed)
        assertFailure(recreated.recover(), RecoveryReason.MalformedMetadata)
        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.Unavailable)
        assertFailure(recreated.recover(), RecoveryReason.ProviderUnavailable)
        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.Missing)
        assertFailure(recreated.recover(), RecoveryReason.RootOrDocumentMissing)

        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.Normal)
        assertTrue(recreated.bind(treeUri, returned.flags) is SafRootResult.Ready)
        context.contentResolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFailure(recreated.recover(), RecoveryReason.PermissionRevoked)
        assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == treeUri })
        recreated.clear()
        recreated.clear()
    }

    private fun selectWithDocumentsUi(intent: Intent): Pair<Int?, Intent?> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SafPickerHostActivity.clearResult(context)
        var code: Int? = null
        var data: Intent? = null
        ActivityScenario.launch<SafPickerHostActivity>(Intent(context, SafPickerHostActivity::class.java).putExtra(SafPickerHostActivity.EXTRA_INTENT, intent)).use { scenario ->
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 5_000))
            device.wait(Until.findObject(By.desc("Show roots")), 5_000)?.click()
            val fixtureSelector = By.res("android", "title").text("Folium SAF Fixture")
            assertNotNull(device.wait(Until.findObject(fixtureSelector), 5_000))
            var fixtureClicked = false
            repeat(3) {
                if (!fixtureClicked) try {
                    val bounds = requireNotNull(device.findObject(fixtureSelector)).visibleBounds
                    fixtureClicked = bounds.width() > 0 && bounds.height() > 0 && device.click(bounds.centerX(), bounds.centerY())
                } catch (_: androidx.test.uiautomator.StaleObjectException) { }
            }
            assertTrue(fixtureClicked)
            assertNotNull(device.wait(Until.findObject(By.res("com.google.android.documentsui", "dir_list")), 5_000))
            assertFalse(device.hasObject(By.text("Can’t load content at the moment")))
            val select = device.wait(Until.findObject(By.res("android", "button1").text("USE THIS FOLDER").enabled(true)), 5_000)
                ?: device.wait(Until.findObject(By.res("com.google.android.documentsui", "action_menu_select").enabled(true)), 5_000)
            assertNotNull(select)
            select!!.click()
            val allow = device.wait(Until.findObject(By.text("ALLOW")), 5_000)
            assertNotNull(allow)
            allow!!.click()
            assertTrue(device.wait(Until.gone(By.pkg("com.google.android.documentsui")), 5_000))
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && code == null) {
                val stored = SafPickerHostActivity.result(context)
                code = stored.first
                data = stored.second
                if (code == null) Thread.sleep(100)
            }
        }
        return code to data
    }

    private fun assertReadOnlyPersisted(context: android.content.Context, uri: android.net.Uri) {
        val permission = context.contentResolver.persistedUriPermissions.single { it.uri == uri }
        assertTrue(permission.isReadPermission)
        assertFalse(permission.isWritePermission)
    }

    private fun assertFailure(result: SafRootResult, reason: RecoveryReason) {
        assertTrue(result is SafRootResult.Unavailable)
        assertEquals(reason, (result as SafRootResult.Unavailable).failure.recovery.reason)
    }
}
