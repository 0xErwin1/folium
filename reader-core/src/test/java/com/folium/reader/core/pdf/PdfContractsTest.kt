package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfContractsTest {
    @Test fun pageSpaceAndRasterEnforceNeutralBounds() {
        assertEquals(PageSpacePoint(0f, 1f), PageSpacePoint(0f, 1f))
        assertEquals(PageSpaceRect(0f, 0f, 1f, 1f), RenderSpec(10, 10).pageSpace)
        assertThrows(IllegalArgumentException::class.java) { PageSpacePoint(-0.01f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { Raster(2, 2, ByteArray(3)) }
    }

    @Test fun renderSpecRejectsOverflowAndOversizedRasters() {
        assertEquals(RenderSpec(4_096, 4_096), RenderSpec(4_096, 4_096))
        assertThrows(IllegalArgumentException::class.java) { RenderSpec(Int.MAX_VALUE, Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { RenderSpec(Int.MAX_VALUE, 1) }
        assertThrows(IllegalArgumentException::class.java) { RenderSpec(4_097, 4_096) }
    }

    @Test fun failuresRemainTypedAndPasswordEntryIsNotPartOfContract() {
        assertEquals(PdfFailure.PasswordRequired, PdfException(PdfFailure.PasswordRequired).failure)
        assertEquals(PdfFailure.WrongPassword, PdfException(PdfFailure.WrongPassword).failure)
        assertEquals(PdfFailure.Closed, PdfException(PdfFailure.Closed).failure)
    }
}
