package com.folium.reader.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.folium.reader.core.ink.InkInputKind
import com.folium.reader.core.ink.InkSample
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTip
import com.folium.reader.core.ink.InkTool
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetTiles
import com.folium.reader.core.ink.StrokeId
import com.folium.reader.core.ink.TileKey
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

private fun inputToolTypeFor(inputKind: InkInputKind): InputToolType = when (inputKind) {
    InkInputKind.FINGER -> InputToolType.TOUCH
    InkInputKind.STYLUS -> InputToolType.STYLUS
    InkInputKind.MOUSE -> InputToolType.MOUSE
    InkInputKind.UNKNOWN -> InputToolType.UNKNOWN
}

private fun inkInputKindFor(toolType: InputToolType): InkInputKind = when (toolType) {
    InputToolType.TOUCH -> InkInputKind.FINGER
    InputToolType.STYLUS -> InkInputKind.STYLUS
    InputToolType.MOUSE -> InkInputKind.MOUSE
    else -> InkInputKind.UNKNOWN
}

/**
 * Rebuilds the model [InkStroke] a just-finished `androidx.ink` [Stroke] represents, reusing that
 * same [Stroke] instance for rendering rather than building a second one from the model: the brush
 * metadata a [Stroke] carries cannot be inverted back into [InkTip] on its own (1.0.0 has no pencil
 * family, so [InkTip.PENCIL] and [InkTip.BALLPOINT] both produce a marker brush), so [tool], [tip],
 * [colorArgb] and [widthSheetUnits] are the values the surface actually drew with, not derived from
 * [stroke].
 */
fun fromAndroidxStroke(
    stroke: Stroke,
    id: StrokeId,
    sequence: Long,
    tool: InkTool,
    tip: InkTip,
    colorArgb: Int,
    widthSheetUnits: Float
): InkStroke {
    val inputs = stroke.inputs
    val inputKind = if (inputs.isEmpty()) InkInputKind.UNKNOWN else inkInputKindFor(inputs.getToolType())

    val samples = (0 until inputs.size).map { index ->
        val input = inputs.get(index)
        val sheetPoint = StrokeSpace.strokeSpaceToSheet(StrokeSpacePoint(input.x, input.y))
        InkSample(
            x = sheetPoint.x,
            y = sheetPoint.y,
            elapsedMillis = input.elapsedTimeMillis.toInt(),
            pressure = if (input.hasPressure) input.pressure else null,
            tiltRadians = if (input.hasTilt) input.tiltRadians else null,
            orientationRadians = if (input.hasOrientation) input.orientationRadians else null
        )
    }

    return InkStroke(
        id = id,
        tool = tool,
        tip = tip,
        colorArgb = colorArgb,
        widthSheetUnits = widthSheetUnits,
        inputKind = inputKind,
        samples = samples,
        sequence = sequence
    )
}

/**
 * Builds the `androidx.ink` [Stroke] that renders [stroke], in [StrokeSpace] rather than sheet units.
 * A [InkTool.PEN] stroke's brush colour is [resolveStrokeColor] of its stored colour under
 * [themeInkArgb], never the stored colour directly, so a stroke drawn under the pen panel's THEME
 * choice renders in whichever ink is current rather than the one it was drawn under. A
 * [InkTool.HIGHLIGHTER] stroke never resolves against the theme at all — [resolveStrokeColor] is a
 * no-op for it — and is built with [highlighterBrushFor] instead of [brushFor].
 */
fun toInkStroke(stroke: InkStroke, themeInkArgb: Int): Stroke {
    val resolvedColorArgb = resolveStrokeColor(stroke.colorArgb, themeInkArgb, stroke.tool)
    val brush = when (stroke.tool) {
        InkTool.PEN -> brushFor(stroke.tip, resolvedColorArgb, stroke.widthSheetUnits)
        InkTool.HIGHLIGHTER -> highlighterBrushFor(resolvedColorArgb, stroke.widthSheetUnits)
    }
    val toolType = inputToolTypeFor(stroke.inputKind)
    val batch = MutableStrokeInputBatch()

    for (sample in stroke.samples) {
        batch.add(
            type = toolType,
            x = StrokeSpace.sheetToStrokeSpace(sample.x),
            y = StrokeSpace.sheetToStrokeSpace(sample.y),
            elapsedTimeMillis = sample.elapsedMillis.toLong(),
            pressure = sample.pressure ?: androidx.ink.strokes.StrokeInput.NO_PRESSURE,
            tiltRadians = sample.tiltRadians ?: androidx.ink.strokes.StrokeInput.NO_TILT,
            orientationRadians = sample.orientationRadians ?: androidx.ink.strokes.StrokeInput.NO_ORIENTATION
        )
    }

    return Stroke(brush, batch)
}

private fun primaryTileOf(stroke: InkStroke): TileKey {
    val centerX = (stroke.bounds.left + stroke.bounds.right) / 2f
    val centerY = (stroke.bounds.top + stroke.bounds.bottom) / 2f
    return TileKey(SheetTiles.colFor(centerX), SheetTiles.rowFor(centerY))
}

private fun tileDistanceSquared(tile: TileKey, center: SheetPoint): Float {
    val tileCenterX = tile.col + 0.5f
    val tileCenterY = tile.row + 0.5f
    val dx = tileCenterX - center.x
    val dy = tileCenterY - center.y
    return dx * dx + dy * dy
}

/**
 * Converts [InkStroke]s into `androidx.ink` [Stroke]s off the UI thread — roughly 4ms per stroke on
 * the target tablet, too slow to do inline while the reader is interactive — one tile's worth at a
 * time, ordered by distance from [viewportCenter] so the tiles a reader is actually looking at
 * arrive first. A stroke spanning several tiles is grouped and built once, under the tile its own
 * bounding box is centered in, rather than once per tile it touches.
 *
 * Every batch is delivered through [onBatchReady] on this builder's own single background thread;
 * the caller is responsible for hopping back to the UI thread before touching any view state.
 */
class InkMeshBuilder(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "folium-ink-mesh-builder") }
) {
    fun build(
        strokes: List<InkStroke>,
        viewportCenter: SheetPoint,
        themeInkArgb: Int,
        onBatchReady: (List<Pair<InkStroke, Stroke>>) -> Unit
    ): Future<*> = executor.submit {
        val byTile = strokes.groupBy(::primaryTileOf)
        val orderedTiles = byTile.keys.sortedBy { tileDistanceSquared(it, viewportCenter) }

        for (tile in orderedTiles) {
            val built = byTile.getValue(tile).map { stroke -> stroke to toInkStroke(stroke, themeInkArgb) }
            onBatchReady(built)
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
