package com.folium.reader.core.ink

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.hypot

/** How many bisection halvings [crossingSample] runs to land on a segment's erased/kept boundary; float precision saturates well before this many. */
private const val CROSSING_BISECTION_ITERATIONS = 40

/**
 * Splits [stroke] around wherever [eraserPath] touches it, returning the surviving fragments, or
 * `null` when the eraser does not touch [stroke] at all — the same bounds-then-distance pre-filter
 * [strokesHitBy] uses, so a caller can skip an untouched stroke just as cheaply here.
 *
 * A stroke sample is erased when its distance to [eraserPath] is at most [eraserRadius] plus half of
 * [InkStroke.widthSheetUnits], exactly [strokesHitBy]'s own threshold. Wherever two consecutive
 * samples fall on opposite sides of that threshold, a new sample is interpolated at the crossing —
 * position, [InkSample.elapsedMillis] and every optional channel linearly — so a surviving fragment
 * ends exactly at the eraser's edge rather than at whichever original sample happened to be nearest.
 *
 * Each fragment is rebuilt through [InkStroke]'s own factory: same tool, tip, colour, width and
 * [InkStroke.inputKind] as [stroke], its own id from [newId], and its samples re-timed so the first
 * one reads `elapsedMillis == 0`. A fragment shorter than [InkStroke.widthSheetUnits] is dropped as a
 * crumb, and a stroke erased everywhere returns an empty list rather than `null` — `null` means "not
 * touched at all", not "erased down to nothing".
 *
 * Every fragment takes a FRESH [newSequence], never one derived from [stroke]'s own: a fragment is a
 * new stroke, not a continuation of the old one, so it draws on top of every other stroke of its tool
 * the way freshly drawn ink would. Callers that persist strokes indexed by sequence — [SheetStrokeLog]
 * keys its live set that way — must never mint the same sequence for two strokes that are both still
 * live at once, and a fragment from this function is exactly such a still-live stroke.
 */
fun erasePartially(
    stroke: InkStroke,
    eraserPath: List<SheetPoint>,
    eraserRadius: Float,
    newId: () -> StrokeId,
    newSequence: () -> Long
): List<InkStroke>? {
    require(eraserPath.isNotEmpty()) { "eraserPath must have at least one point" }
    require(eraserRadius >= 0f) { "eraserRadius must be non-negative, was $eraserRadius" }

    val eraserBounds = boundsOf(eraserPath).inflate(eraserRadius)
    if (!eraserBounds.intersects(stroke.bounds)) return null

    val threshold = eraserRadius + stroke.widthSheetUnits / 2f
    val inserted = Collections.newSetFromMap(IdentityHashMap<InkSample, Boolean>())
    val samples = densifiedNearEraser(stroke.samples, eraserPath, threshold, inserted)
    val erasedFlags = samples.map { sample -> distanceToEraser(sample, eraserPath) <= threshold }

    if (erasedFlags.none { it }) return null

    val runs = survivingRuns(samples, erasedFlags, eraserPath, threshold)
    return runs.mapNotNull { run -> fragmentFrom(run.filterNot { it in inserted }, stroke, newId, newSequence) }
}

/**
 * [samples] with extra interpolated samples along every segment the eraser comes within [threshold]
 * of, spaced no farther apart than [threshold]. Erasing is decided sample by sample, so a long segment
 * whose two ends are both clear of the eraser would otherwise be left whole while the eraser visibly
 * crosses its middle: a straight line or a box side is exactly two samples. With samples at most one
 * threshold apart, any crossing lies within half a threshold of one of them. Segments the eraser
 * never approaches are left as they were, so an untouched part of the stroke keeps its own samples.
 * Every sample added here is recorded in [inserted]: they only serve to find where the eraser cuts, and
 * are dropped from the surviving fragments, which lie on the same straight segments without them.
 */
private fun densifiedNearEraser(
    samples: List<InkSample>,
    eraserPath: List<SheetPoint>,
    threshold: Float,
    inserted: MutableSet<InkSample>
): List<InkSample> {
    if (samples.size < 2 || threshold <= 0f) return samples

    val dense = ArrayList<InkSample>(samples.size)
    dense += samples.first()

    for ((from, to) in samples.zipWithNext()) {
        val segment = listOf(SheetPoint(from.x, from.y), SheetPoint(to.x, to.y))
        val length = hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble()).toFloat()
        val near = length > threshold && minPolylineDistance(segment, eraserPath) <= threshold

        if (near) {
            val pieces = minOf(ceil(length / threshold).toInt(), MAX_DENSIFY_PIECES_PER_SEGMENT)
            for (piece in 1 until pieces) {
                val extra = interpolateSample(from, to, piece.toFloat() / pieces)
                inserted += extra
                dense += extra
            }
        }

        dense += to
    }

    return dense
}

/** A ceiling on how finely one segment is subdivided, so a tiny eraser over a sheet-long line stays bounded. */
private const val MAX_DENSIFY_PIECES_PER_SEGMENT = 4096

private fun distanceToEraser(sample: InkSample, eraserPath: List<SheetPoint>): Float =
    minPolylineDistance(listOf(SheetPoint(sample.x, sample.y)), eraserPath)

/**
 * Walks [samples] once, grouping the kept (not erased) ones into maximal runs, each of which becomes
 * one surviving fragment. An interpolated boundary sample is spliced in wherever [erasedFlags] changes
 * between two consecutive samples, and always lands in whichever run it borders — the run that is
 * ending, the one that is starting, or both when the run on one side closes exactly as the other opens.
 */
private fun survivingRuns(
    samples: List<InkSample>,
    erasedFlags: List<Boolean>,
    eraserPath: List<SheetPoint>,
    threshold: Float
): List<List<InkSample>> {
    val runs = mutableListOf<MutableList<InkSample>>()
    var currentRun: MutableList<InkSample>? = null

    fun push(sample: InkSample, keep: Boolean) {
        if (!keep) {
            currentRun = null
            return
        }
        val run = currentRun ?: mutableListOf<InkSample>().also {
            currentRun = it
            runs += it
        }
        run += sample
    }

    push(samples[0], !erasedFlags[0])
    for (i in 0 until samples.size - 1) {
        if (erasedFlags[i] != erasedFlags[i + 1]) {
            val boundary = crossingSample(samples[i], samples[i + 1], erasedFlags[i], eraserPath, threshold)
            push(boundary, true)
        }
        push(samples[i + 1], !erasedFlags[i + 1])
    }

    return runs
}

/**
 * The interpolated sample on segment [a]..[b] where the eraser's erased/kept state flips, found by
 * bisection rather than by assuming the eraser path is straight: [aErased] is that state at [a], and
 * the search narrows until it can no longer tell the two halves of the segment apart.
 */
private fun crossingSample(a: InkSample, b: InkSample, aErased: Boolean, eraserPath: List<SheetPoint>, threshold: Float): InkSample {
    var lo = 0f
    var hi = 1f

    repeat(CROSSING_BISECTION_ITERATIONS) {
        val mid = (lo + hi) / 2f
        val midSample = interpolateSample(a, b, mid)
        val midErased = distanceToEraser(midSample, eraserPath) <= threshold
        if (midErased == aErased) lo = mid else hi = mid
    }

    return interpolateSample(a, b, (lo + hi) / 2f)
}

private fun interpolateSample(a: InkSample, b: InkSample, t: Float): InkSample {
    fun lerp(from: Float, to: Float): Float = from + (to - from) * t
    fun lerpOptional(from: Float?, to: Float?): Float? = if (from != null && to != null) lerp(from, to) else null

    return InkSample(
        x = lerp(a.x, b.x),
        y = lerp(a.y, b.y),
        elapsedMillis = lerp(a.elapsedMillis.toFloat(), b.elapsedMillis.toFloat()).toInt(),
        pressure = lerpOptional(a.pressure, b.pressure),
        tiltRadians = lerpOptional(a.tiltRadians, b.tiltRadians),
        orientationRadians = lerpOptional(a.orientationRadians, b.orientationRadians)
    )
}

/**
 * The fragment [run] becomes, or `null` when it is too short to keep: [survivingRuns] never hands
 * back a run of fewer than two samples, but a run whose own path is shorter than [InkStroke.widthSheetUnits]
 * is a crumb rather than a stroke worth keeping.
 */
private fun fragmentFrom(run: List<InkSample>, original: InkStroke, newId: () -> StrokeId, newSequence: () -> Long): InkStroke? {
    if (run.size < 2) return null
    if (pathLength(run) < original.widthSheetUnits) return null

    val firstElapsed = run[0].elapsedMillis
    val rebasedSamples = run.map { sample -> sample.copy(elapsedMillis = sample.elapsedMillis - firstElapsed) }

    return InkStroke(
        id = newId(),
        tool = original.tool,
        tip = original.tip,
        colorArgb = original.colorArgb,
        widthSheetUnits = original.widthSheetUnits,
        inputKind = original.inputKind,
        samples = rebasedSamples,
        sequence = newSequence()
    )
}

private fun pathLength(samples: List<InkSample>): Float {
    var length = 0f
    for (i in 0 until samples.size - 1) {
        length += hypot((samples[i + 1].x - samples[i].x).toDouble(), (samples[i + 1].y - samples[i].y).toDouble()).toFloat()
    }
    return length
}
