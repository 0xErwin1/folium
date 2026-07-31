package com.folium.reader

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folium.reader.core.library.RecoveryReason
import com.folium.reader.saf.SafGrantRepository
import com.folium.reader.saf.SharedPreferencesSafRootStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafRecoveryInstrumentedTest {
    @Test fun open_tree_contract_is_read_persistable_only_and_unselected_root_recovers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = SharedPreferencesSafRootStorage(context)
        storage.clear()
        val repository = SafGrantRepository(context.contentResolver, storage)

        val intent = repository.selectionIntent()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        val result = repository.recover()
        assertTrue(result is com.folium.reader.saf.SafRootResult.Unavailable)
        assertEquals(RecoveryReason.RootNotSelected, (result as com.folium.reader.saf.SafRootResult.Unavailable).failure.recovery.reason)
    }
}
