package com.folium.reader.ocr_tesseract

import com.folium.reader.core.ocr.OcrFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException

class TesseractDataFailureTest {
    @Test fun transientFilesystemAndReadFailuresRemainRetryableAndKeepCause() {
        listOf(IOException("read"), InterruptedIOException("interrupted"), SecurityException("permission"))
            .forEach { cause ->
                val mapped = mapLanguageDataInstallFailure(cause)

                assertEquals(OcrFailure.Resource(retryable = true), mapped.failure)
                assertSame(cause, mapped.cause)
            }
    }

    @Test fun missingBundledOrCorruptDataRemainDeterministicAndKeepCause() {
        val missing = MissingBundledLanguageDataException(FileNotFoundException("missing"))
        val corrupt = LanguageDataIntegrityException()

        listOf(missing, corrupt).forEach { cause ->
            val mapped = mapLanguageDataInstallFailure(cause)

            assertEquals(OcrFailure.LanguageData, mapped.failure)
            assertSame(cause, mapped.cause)
        }
    }
}
