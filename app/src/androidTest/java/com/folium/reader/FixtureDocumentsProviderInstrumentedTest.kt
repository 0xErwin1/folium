package com.folium.reader

import android.content.Context
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the single-document metadata path that `FoliumActivity.displayNameOf` actually exercises
 * after picking a file: a `contentResolver.query` against the picked document's own URI, which
 * resolves through [FixtureDocumentsProvider.queryDocument], not [FixtureDocumentsProvider.queryChildDocuments].
 */
@RunWith(AndroidJUnit4::class)
class FixtureDocumentsProviderInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun selectNormalMode() {
        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.Normal)
    }

    @Test fun a_picked_child_document_answers_its_own_display_name_query() {
        val uri = DocumentsContract.buildDocumentUri(FixtureDocumentsProvider.AUTHORITY, FixtureDocumentsProvider.PDF)

        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

        assertEquals("original.pdf", name)
    }
}
