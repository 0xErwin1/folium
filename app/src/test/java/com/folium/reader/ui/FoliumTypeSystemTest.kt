package com.folium.reader.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoliumTypeSystemTest {
    private val styles: List<TextStyle> = Typography::class.java.declaredFields
        .filter { TextStyle::class.java.isAssignableFrom(it.type) }
        .map { field -> field.isAccessible = true; field.get(FoliumTypography) as TextStyle }

    @Test fun `every material slot is filled so nothing falls back to the platform font`() {
        assertEquals(15, styles.size)
        styles.forEach { style ->
            assertNotNull("a slot left its family unset", style.fontFamily)
        }
        assertEquals(15, styles.count { it.fontFamily != null })
    }

    @Test fun `the ramp is six sizes and no more`() {
        assertEquals(setOf(32.sp, 21.sp, 15.sp, 13.sp, 11.sp, 10.sp), styles.map { it.fontSize }.toSet())
    }

    @Test fun `weights stay within the three the variable font is instanced at`() {
        assertEquals(
            setOf(FontWeight.Normal, FontWeight.Medium, FontWeight.Bold),
            styles.mapNotNull { it.fontWeight }.toSet()
        )
    }

    /**
     * Tracking in sp would stay fixed while the glyphs grew with the reader's font-size preference,
     * which opens the letters up at small sizes and jams them at large ones.
     */
    @Test fun `tracking is never absolute, so it scales with the reader's font size`() {
        styles.forEach { style ->
            assertTrue(
                "letter spacing in sp stays fixed while the glyphs grow: ${style.fontSize}",
                style.letterSpacing.type != TextUnitType.Sp
            )
        }
        assertTrue(styles.any { it.letterSpacing.type == TextUnitType.Em })
    }

    @Test fun `the label step is the only one set in caps tracking`() {
        assertEquals(11.sp, FoliumTypography.labelMedium.fontSize)
        assertEquals(FontWeight.Bold, FoliumTypography.labelMedium.fontWeight)
        assertTrue(FoliumTypography.labelMedium.letterSpacing.value > 0.1f)
    }

    @Test fun `every corner in the system is square`() {
        listOf(
            FoliumShapes.extraSmall, FoliumShapes.small, FoliumShapes.medium,
            FoliumShapes.large, FoliumShapes.extraLarge
        ).forEach { shape ->
            val size = androidx.compose.ui.geometry.Size(100f, 100f)
            val density = androidx.compose.ui.unit.Density(1f)
            assertEquals(0f, shape.topStart.toPx(size, density))
            assertEquals(0f, shape.topEnd.toPx(size, density))
            assertEquals(0f, shape.bottomStart.toPx(size, density))
            assertEquals(0f, shape.bottomEnd.toPx(size, density))
        }
    }

    @Test fun `the spacing scale is eight steps of four`() {
        val scale = listOf(
            FoliumSpacing.xxs, FoliumSpacing.xs, FoliumSpacing.s, FoliumSpacing.m,
            FoliumSpacing.l, FoliumSpacing.xl, FoliumSpacing.xxl, FoliumSpacing.xxxl
        )

        assertEquals(listOf(4.dp, 8.dp, 12.dp, 16.dp, 20.dp, 24.dp, 32.dp, 48.dp), scale)
        assertEquals(scale.sorted(), scale)
        scale.forEach { step -> assertEquals(0f, step.value % 4f) }
    }

    @Test fun `the touch floor is the one value that is not a spacing step`() {
        assertEquals(44.dp, FoliumSpacing.touchTarget)
    }

    /**
     * A fixed column count stretches its module as the window widens, so the column cannot be what
     * stays constant. The cover is: the span absorbs the drift, and covers grow with the screen
     * instead of multiplying at one size.
     */
    @Test fun `the cover grows with the screen and never leaves its readable band`() {
        val phone = cover(412.dp, FoliumGrid.compactMargin, FoliumGrid.compactGutter,
            FoliumGrid.COMPACT_COLUMNS, FoliumGrid.COMPACT_COVER_SPAN)
        val small = cover(720.dp, FoliumGrid.mediumMargin, FoliumGrid.mediumGutter,
            FoliumGrid.MEDIUM_COLUMNS, FoliumGrid.MEDIUM_COVER_SPAN)
        val tablet = cover(1365.dp, FoliumGrid.expandedMargin, FoliumGrid.expandedGutter,
            FoliumGrid.EXPANDED_COLUMNS, FoliumGrid.EXPANDED_COVER_SPAN)

        assertTrue("$phone then $small then $tablet is not monotonic", phone < small && small < tablet)
        listOf(phone, small, tablet).forEach { width ->
            assertTrue(
                "cover $width left the band ${FoliumGrid.minCover}..${FoliumGrid.maxCover}",
                width >= FoliumGrid.minCover.value && width <= FoliumGrid.maxCover.value
            )
        }
    }

    @Test fun `the column count steps up at every width class`() {
        assertTrue(FoliumGrid.COMPACT_COLUMNS < FoliumGrid.MEDIUM_COLUMNS)
        assertTrue(FoliumGrid.MEDIUM_COLUMNS < FoliumGrid.EXPANDED_COLUMNS)
    }

    private fun cover(
        width: androidx.compose.ui.unit.Dp,
        margin: androidx.compose.ui.unit.Dp,
        gutter: androidx.compose.ui.unit.Dp,
        columns: Int,
        span: Int
    ): Float = module(width, margin, gutter, columns) * span + gutter.value * (span - 1)

    private fun module(
        width: androidx.compose.ui.unit.Dp,
        margin: androidx.compose.ui.unit.Dp,
        gutter: androidx.compose.ui.unit.Dp,
        columns: Int
    ): Float = (width.value - margin.value * 2 - gutter.value * (columns - 1)) / columns
}
