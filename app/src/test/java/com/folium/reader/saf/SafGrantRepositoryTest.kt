package com.folium.reader.saf

import android.content.Intent
import com.folium.reader.core.library.LibraryRootIdentity
import com.folium.reader.core.library.RootVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafGrantRepositoryTest {
    @Test fun returned_flags_require_exact_read_and_persistable_bits_without_write() {
        assertEquals(REQUIRED_TREE_GRANT_FLAGS, persistedReadGrantFlags(REQUIRED_TREE_GRANT_FLAGS or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertFalse(persistedReadGrantFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) == REQUIRED_TREE_GRANT_FLAGS)
    }

    @Test fun private_store_rebind_and_clear_are_idempotent() {
        val storage = FakeStorage()
        val root = StoredSafRoot("content://provider/tree/root", LibraryRootIdentity("provider", "root"), RootVersion("1"))
        storage.write(root)
        storage.write(root)
        assertEquals(root, storage.read())
        storage.clear()
        storage.clear()
        assertNull(storage.read())
    }

    @Test fun diagnostics_are_opaque_stages_not_user_metadata() {
        assertTrue(SafDiagnosticStage.entries.none { it.name.contains("Uri") || it.name.contains("Name") || it.name.contains("DocumentId") })
    }

    private class FakeStorage : SafRootStorage {
        private var root: StoredSafRoot? = null
        override fun read(): StoredSafRoot? = root
        override fun write(root: StoredSafRoot) { this.root = root }
        override fun clear() { root = null }
    }
}
