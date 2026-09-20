package com.folium.reader.ink

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The zoom stepper's own range and step, as an integer percentage where 100% is [SheetViewport.MIN_ZOOM]
 * — the sheet's nominal width filling the view (`rail-spec.md` 2.2, VISTA panel: "ZOOM ... 140 %";
 * task instructions: "range 100 %..800 %, step 20 %").
 */
internal const val ZOOM_MIN_PERCENT: Int = 100
internal const val ZOOM_MAX_PERCENT: Int = 800
internal const val ZOOM_STEP_PERCENT: Int = 20

/** How close, in percentage points, a live zoom must sit to a fit-to target to still read as selected. */
private const val FIT_TO_SELECTION_TOLERANCE_PERCENT: Int = 1

private const val MILLIMETRES_PER_INCH: Float = 25.4f

/** The sheet's nominal width, matching [PenSettings]'s own 210mm reference. */
private const val SHEET_NOMINAL_WIDTH_MM: Float = 210f

/** Which direction a tap on the zoom stepper's minus or plus button steps. */
internal enum class ZoomStepDirection { DECREASE, INCREASE }

/** [zoom] as the integer percentage the zoom stepper shows, where `zoom == 1f` reads as 100%. */
internal fun zoomPercentOf(zoom: Float): Int = (zoom * 100f).roundToInt()

/** The [SheetViewport.zoom] equivalent to a stepper's own [percent] value. */
internal fun zoomFractionOf(percent: Int): Float = percent / 100f

/**
 * The next multiple of [ZOOM_STEP_PERCENT] from [currentPercent] in [direction], clamped to
 * [ZOOM_MIN_PERCENT]..[ZOOM_MAX_PERCENT]. A tap always snaps to the next step rather than rounding to
 * the nearest one, so a pinch that lands on 137% still moves a full step either way (task
 * instructions: "after a pinch to 137 % '+' goes to 140 %, '−' to 120 %").
 */
internal fun nextZoomStep(currentPercent: Int, direction: ZoomStepDirection): Int {
    val next = when (direction) {
        ZoomStepDirection.DECREASE -> Math.floorDiv(currentPercent - 1, ZOOM_STEP_PERCENT) * ZOOM_STEP_PERCENT
        ZoomStepDirection.INCREASE -> (Math.floorDiv(currentPercent, ZOOM_STEP_PERCENT) + 1) * ZOOM_STEP_PERCENT
    }
    return next.coerceIn(ZOOM_MIN_PERCENT, ZOOM_MAX_PERCENT)
}

/**
 * The zoom at which the sheet's nominal [SHEET_NOMINAL_WIDTH_MM]mm width measures that same width on
 * a screen of [xdpi] pixels per inch and [viewWidthPx] wide (task instructions: "the zoom at which the
 * sheet's nominal 210 mm width measures 210 mm on this screen"), clamped to [SheetViewport.MIN_ZOOM]..
 * [SheetViewport.MAX_ZOOM].
 */
internal fun actualSizeZoom(xdpi: Float, viewWidthPx: Float): Float {
    require(xdpi > 0f) { "xdpi must be positive, was $xdpi" }
    require(viewWidthPx > 0f) { "viewWidthPx must be positive, was $viewWidthPx" }

    val sheetWidthPx = (SHEET_NOMINAL_WIDTH_MM / MILLIMETRES_PER_INCH) * xdpi
    return (sheetWidthPx / viewWidthPx).coerceIn(SheetViewport.MIN_ZOOM, SheetViewport.MAX_ZOOM)
}

/**
 * The two one-shot fit actions the VIEW panel offers. The design's third option, PÁGINA (fit to
 * page), is omitted: an endless sheet has no fixed page height to fit to (`rail-spec.md` task
 * instructions).
 */
internal enum class FitToOption { WIDTH, ACTUAL_SIZE }

/**
 * Which [FitToOption], if any, the live zoom currently matches within [FIT_TO_SELECTION_TOLERANCE_PERCENT]
 * percentage point: neither fit action is a persistent choice, so it only reads as selected while the
 * zoom it left behind has not since moved away, from a pinch or from the other action
 * (`rail-spec.md` 2.2: "None drawn selected... INFERRED: they are one-shot actions").
 */
internal fun selectedFitToOption(currentZoomPercent: Int, actualSizeZoomPercent: Int): FitToOption? = when {
    abs(currentZoomPercent - ZOOM_MIN_PERCENT) <= FIT_TO_SELECTION_TOLERANCE_PERCENT -> FitToOption.WIDTH
    abs(currentZoomPercent - actualSizeZoomPercent) <= FIT_TO_SELECTION_TOLERANCE_PERCENT -> FitToOption.ACTUAL_SIZE
    else -> null
}

/**
 * The value a position [fraction] along a stepper's track stands for, between [min] and [max] and
 * rounded to the nearest multiple of [step] counted from [min], so dragging the track lands only on
 * values the − and + buttons can also reach.
 */
internal fun snapToStep(min: Int, max: Int, step: Int, fraction: Float): Int {
    val raw = min + (max - min) * fraction.coerceIn(0f, 1f)
    val steps = Math.round((raw - min) / step)

    return (min + steps * step).coerceIn(min, max)
}
