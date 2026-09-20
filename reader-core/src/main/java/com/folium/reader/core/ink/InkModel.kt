package com.folium.reader.core.ink

import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.requireOpaque
import kotlin.math.max
import kotlin.math.min

/** Opaque, generated identity for a sheet. Minting a value is the caller's job; this type never generates one itself. */
@JvmInline
value class SheetId(val value: String) {
    init { requireOpaque(value, "SheetId") }
}

/**
 * Opaque, generated identity for a stroke, scoped to the sheet it was drawn on. Reused as-is for a
 * [SheetTextBox]'s own id rather than a separate type, since both live in one shared id space on a
 * sheet — see [SheetItem] — and a fresh type here would only ripple through every call site that
 * already keys on [StrokeId] without adding a distinction that matters at this level.
 */
@JvmInline
value class StrokeId(val value: String) {
    init { requireOpaque(value, "StrokeId") }
}

/** The nib shape a pen stroke was drawn with. */
enum class InkTip { BALLPOINT, FOUNTAIN, PENCIL }

/**
 * The drawing tool a stroke was made with, stored in [SheetStrokeLog] by [InkTool.ordinal]: a new
 * entry is always appended after every existing one, so a value an older build already wrote to disk
 * keeps decoding to the same tool under a newer build that has since grown more entries. [PEN] and
 * [HIGHLIGHTER] are the only tools with a working engine behind them today; an eraser stroke recorded
 * as its own tool is a future additive `entries` change rather than a signature change anywhere that
 * switches on it.
 */
enum class InkTool { PEN, HIGHLIGHTER }

/** The kind of pointer a stroke's samples were captured from. */
enum class InkInputKind { FINGER, STYLUS, MOUSE, UNKNOWN }

/**
 * One raw point of a stroke's path. [elapsedMillis] is time since the stroke's first sample, not a
 * wall-clock timestamp, so a stroke's timing survives being replayed or persisted independently of
 * when it was drawn. [pressure] is `0..1` when the digitizer reports it and `null` otherwise; the
 * same is true of [tiltRadians] and [orientationRadians], which are angles the digitizer may or may
 * not measure at all.
 */
data class InkSample(
    val x: Float,
    val y: Float,
    val elapsedMillis: Int,
    val pressure: Float? = null,
    val tiltRadians: Float? = null,
    val orientationRadians: Float? = null
) {
    init {
        require(elapsedMillis >= 0) { "elapsedMillis must be non-negative, was $elapsedMillis" }
        require(pressure == null || pressure in 0f..1f) { "pressure must be in 0..1 when present, was $pressure" }
    }
}

/**
 * One freehand stroke on a sheet. [bounds] always includes half of [widthSheetUnits] on every
 * side of the path the samples describe, so a caller never has to re-derive the ink's true footprint
 * from the centerline alone. [sequence] is this stroke's draw order among the other strokes on its
 * sheet, which [SheetEditHistory] preserves across an undo/redo round trip.
 *
 * The constructor is private; strokes are always built through the [invoke] factory, which is the
 * only place [bounds] is computed and which rejects an empty [samples] list rather than deriving a
 * meaningless bounding box from it.
 */
@ConsistentCopyVisibility
data class InkStroke private constructor(
    val id: StrokeId,
    val tool: InkTool,
    val tip: InkTip,
    val colorArgb: Int,
    val widthSheetUnits: Float,
    val inputKind: InkInputKind,
    val samples: List<InkSample>,
    val bounds: SheetRect,
    val sequence: Long
) {
    companion object {
        operator fun invoke(
            id: StrokeId,
            tool: InkTool,
            tip: InkTip,
            colorArgb: Int,
            widthSheetUnits: Float,
            inputKind: InkInputKind,
            samples: List<InkSample>,
            sequence: Long
        ): InkStroke {
            require(samples.isNotEmpty()) { "a stroke needs at least one sample" }
            require(widthSheetUnits > 0f) { "widthSheetUnits must be positive, was $widthSheetUnits" }
            require(sequence >= 0) { "sequence must be non-negative, was $sequence" }
            return InkStroke(
                id, tool, tip, colorArgb, widthSheetUnits, inputKind, samples,
                boundsOf(samples, widthSheetUnits), sequence
            )
        }

        private fun boundsOf(samples: List<InkSample>, widthSheetUnits: Float): SheetRect {
            val half = widthSheetUnits / 2f

            var left = samples[0].x
            var right = samples[0].x
            var top = samples[0].y
            var bottom = samples[0].y

            for (sample in samples) {
                left = min(left, sample.x)
                right = max(right, sample.x)
                top = min(top, sample.y)
                bottom = max(bottom, sample.y)
            }

            return SheetRect(left - half, top - half, right + half, bottom + half)
        }
    }
}

/**
 * How a text box's characters are rendered: [BODY] for ordinary paragraph text, [TITLE] for a
 * heading. Stored in [SheetStrokeLog] by ordinal: a new style is always appended after every existing
 * one, the same convention [InkTool] and [InkTip] already follow.
 */
enum class SheetTextStyle { BODY, TITLE }

/**
 * One typed text box on a sheet, drawn and selected alongside [InkStroke]s through [SheetItem].
 * [widthSheetUnits] is the width the text wraps at; [heightSheetUnits] is not derived here, because
 * `:reader-core` cannot measure text, so it is measured by the caller from the wrapped, styled text
 * and stored as-is, rather than recomputed on every read. [sequence] shares [InkStroke.sequence]'s
 * own numbering: this item's draw order among every stroke and text box on its sheet, preserved by
 * [SheetEditHistory] across an undo/redo round trip exactly as a stroke's own sequence is. [id]
 * shares [StrokeId]'s own id space with every stroke on the same sheet, so one removal record can
 * name a mix of both.
 *
 * The constructor is private; a box is always built through the [invoke] factory, which is the only
 * place [bounds] logic is derived from [topLeft], [widthSheetUnits] and [heightSheetUnits].
 */
@ConsistentCopyVisibility
data class SheetTextBox private constructor(
    val id: StrokeId,
    val topLeft: SheetPoint,
    val widthSheetUnits: Float,
    val heightSheetUnits: Float,
    val text: String,
    val style: SheetTextStyle,
    val colorArgb: Int,
    val sequence: Long
) {
    /** This box's own footprint: [topLeft] extended by [widthSheetUnits] and [heightSheetUnits]. */
    val bounds: SheetRect
        get() = SheetRect(topLeft.x, topLeft.y, topLeft.x + widthSheetUnits, topLeft.y + heightSheetUnits)

    companion object {
        operator fun invoke(
            id: StrokeId,
            topLeft: SheetPoint,
            widthSheetUnits: Float,
            heightSheetUnits: Float,
            text: String,
            style: SheetTextStyle,
            colorArgb: Int,
            sequence: Long
        ): SheetTextBox {
            require(topLeft.x.isFinite() && topLeft.y.isFinite()) { "topLeft must be finite, was $topLeft" }
            require(widthSheetUnits > 0f) { "widthSheetUnits must be positive, was $widthSheetUnits" }
            require(heightSheetUnits >= 0f) { "heightSheetUnits must be non-negative, was $heightSheetUnits" }
            require(sequence >= 0) { "sequence must be non-negative, was $sequence" }
            return SheetTextBox(id, topLeft, widthSheetUnits, heightSheetUnits, text, style, colorArgb, sequence)
        }
    }
}

/**
 * Either an [InkStroke] or a [SheetTextBox], for code that must handle a sheet's drawn items without
 * caring which kind each one is — selection and geometry in particular. [id], [sequence] and [bounds]
 * always read through to the wrapped value's own property of the same name.
 */
sealed interface SheetItem {
    val id: StrokeId
    val sequence: Long
    val bounds: SheetRect

    data class Stroke(val stroke: InkStroke) : SheetItem {
        override val id: StrokeId get() = stroke.id
        override val sequence: Long get() = stroke.sequence
        override val bounds: SheetRect get() = stroke.bounds
    }

    data class Text(val textBox: SheetTextBox) : SheetItem {
        override val id: StrokeId get() = textBox.id
        override val sequence: Long get() = textBox.sequence
        override val bounds: SheetRect get() = textBox.bounds
    }
}

/** The background a sheet is drawn against. */
enum class SheetTemplate { BLANK, RULED }

/**
 * Where a sheet lives relative to a book. A `null` anchor is a standalone sheet that lives in the
 * library on its own; a non-null one ties the sheet to [pageIndex] of [bookId], the way a margin
 * note is tied to the page it was written next to.
 */
data class SheetAnchor(val bookId: BookId, val pageIndex: Int) {
    init { require(pageIndex >= 0) { "pageIndex must be non-negative, was $pageIndex" } }
}

/**
 * The fixed-width, endless-height space a [Sheet]'s content lives in: `x` confined to `0..1`, `y`
 * confined to `0` and up.
 *
 * This is deliberately the only thing in this package that knows a sheet is shaped like a column.
 * [InkStroke], [InkSampleCodec], [SheetTiles] and the eraser hit-testing in this package all operate
 * over the fully unbounded, signed coordinate space [SheetPoint] describes and never consult this
 * object, so a future surface with a different shape — an infinite whiteboard, for instance — can
 * reuse every one of them by pairing them with its own constraint instead of this one.
 */
object SheetColumn {
    /** [point] moved onto the column's allowed space; a no-op for a point already inside it. */
    fun clamp(point: SheetPoint): SheetPoint = SheetPoint(point.x.coerceIn(0f, 1f), point.y.coerceAtLeast(0f))

    /** Whether every point of [rect] already lies within the column's allowed space. */
    fun allows(rect: SheetRect): Boolean = rect.left >= 0f && rect.right <= 1f && rect.top >= 0f
}

/**
 * A handwriting canvas: either standalone in the library, or [anchor]ed to a book page.
 * [createdAtEpochMillis] and [updatedAtEpochMillis] are wall-clock timestamps minted by the caller;
 * this type only checks that the sheet was not updated before it was created. A sheet is always a
 * [SheetColumn]; there is no other shape to record.
 */
data class Sheet(
    val id: SheetId,
    val title: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val template: SheetTemplate,
    val anchor: SheetAnchor?
) {
    init {
        require(title.isNotBlank() && title.none { it.isISOControl() }) {
            "title must be non-blank and free of control characters"
        }
        require(createdAtEpochMillis >= 0) { "createdAtEpochMillis must be non-negative, was $createdAtEpochMillis" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "updatedAtEpochMillis must be >= createdAtEpochMillis"
        }
    }
}
