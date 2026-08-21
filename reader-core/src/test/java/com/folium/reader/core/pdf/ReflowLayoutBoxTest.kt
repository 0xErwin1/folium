package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReflowLayoutBoxTest {

    @Test fun boxRejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            ReflowLayoutBox(widthPoints = 0f, heightPoints = 675f, emPoints = 18f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReflowLayoutBox(widthPoints = 450f, heightPoints = -1f, emPoints = 18f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReflowLayoutBox(widthPoints = 450f, heightPoints = 675f, emPoints = 0f)
        }
    }

    @Test fun box1IsTheFrozenOneUsedToday() {
        assertEquals(450f, ReflowLayoutBox.BOX_1.widthPoints, 0f)
        assertEquals(675f, ReflowLayoutBox.BOX_1.heightPoints, 0f)
        assertEquals(18f, ReflowLayoutBox.BOX_1.emPoints, 0f)
    }
}
