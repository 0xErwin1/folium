package com.folium.reader.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which mark an on-page search match gets, per T-Reglas.dc.html's e-ink rule ("El subrayado es el
 * piso; el lavado es el extra") and the uniform underline-plus-wash both matches carry on
 * T-Busqueda.dc.html's two-page spread.
 */
class ReaderSearchMarksTest {

    @Test fun `a backlit panel shows the wash on top of the underline`() {
        assertTrue(searchMarkStyle(eInk = false).showsWash)
    }

    @Test fun `an e-ink panel drops the wash and keeps only the underline`() {
        assertFalse(searchMarkStyle(eInk = true).showsWash)
    }
}
