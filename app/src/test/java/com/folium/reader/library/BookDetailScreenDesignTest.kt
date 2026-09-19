package com.folium.reader.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The no-contents block draws its own dashed stroke rather than using `Modifier.border`, which has
 * no dashed variant. A stroke centred on the composable's own edge needs to move inward by half its
 * own width first, or the outer half of it is clipped away by whatever sits above it in the tree.
 */
class BookDetailScreenDesignTest {

    @Test fun `a stroke moves inward by exactly half its own width`() {
        assertEquals(0.5f, dashedBorderInset(1f))
        assertEquals(1f, dashedBorderInset(2f))
        assertEquals(0f, dashedBorderInset(0f))
    }
}
