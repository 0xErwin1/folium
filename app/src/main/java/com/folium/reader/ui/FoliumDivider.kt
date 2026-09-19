package com.folium.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A crisp, one-axis rule to use in place of Material 3's `HorizontalDivider` and `VerticalDivider`.
 *
 * `Modifier.height`/`Modifier.width` already resolve a [Dp] thickness to a whole number of pixels
 * with `Math.round`, the same rounding [foliumStrokePx] uses, so the divider's own layout size is
 * already the right pixel count. Material 3's dividers throw that away at paint time: they redo the
 * conversion with a raw `toPx()` and stroke a line centred on it, which straddles two pixel rows
 * whenever the density is not a whole number. Filling the already-correctly-sized box with a flat
 * colour instead of stroking a line keeps the rule confined to the pixels its own layout reserved.
 */
internal object FoliumDivider {

    @Composable
    fun Horizontal(
        modifier: Modifier = Modifier,
        thickness: Dp = 1.dp,
        color: Color = MaterialTheme.colorScheme.outlineVariant
    ) {
        Spacer(modifier.fillMaxWidth().height(thickness).background(color))
    }

    @Composable
    fun Vertical(
        modifier: Modifier = Modifier,
        thickness: Dp = 1.dp,
        color: Color = MaterialTheme.colorScheme.outlineVariant
    ) {
        Spacer(modifier.fillMaxHeight().width(thickness).background(color))
    }
}
