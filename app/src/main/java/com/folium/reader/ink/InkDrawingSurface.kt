package com.folium.reader.ink

import android.content.Context
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import com.folium.reader.core.ink.InkInputKind
import com.folium.reader.core.ink.InkSample
import com.folium.reader.core.ink.InkShape
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTip
import com.folium.reader.core.ink.InkTool
import com.folium.reader.core.ink.OpenSheet
import com.folium.reader.core.ink.RecognizedShape
import com.folium.reader.core.ink.SheetEdit
import com.folium.reader.core.ink.SheetEditHistory
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetTemplate
import com.folium.reader.core.ink.StrokeId
import com.folium.reader.core.ink.recognizeShape
import com.folium.reader.core.ink.resizeRecognizedShape
import com.folium.reader.core.ink.shapeSampleTimesMillis
import com.folium.reader.core.ink.shapeSamples
import com.folium.reader.core.ink.sheetContentBounds
import com.folium.reader.core.ink.strokesHitBy
import java.util.UUID

private const val FRONT_BUFFER_PROBE_WIDTH: Int = 800
private const val FRONT_BUFFER_PROBE_HEIGHT: Int = 1280
private const val CLOSE_DRAIN_TIMEOUT_MILLIS: Long = 5_000L

/** The [InkStroke.sequence] the shape tool's own live preview is built under: never committed, so its value only has to satisfy [InkStroke]'s own non-negative requirement. */
private const val SHAPE_PREVIEW_SEQUENCE: Long = 0L

/** How much wider than the platform's own touch slop a two-finger gesture's pan/zoom decision waits before committing to a scroll. */
private const val PAN_SLOP_TOUCH_SLOP_MULTIPLIER: Float = 2f

/** What a stroke was drawn with, stashed at [InProgressStrokesView.startStroke] time and consumed when it finishes. */

/**
 * The ink drawing surface for one open [Sheet][com.folium.reader.core.ink.Sheet]: touch input,
 * pan/zoom, tool state, undo/redo and durable persistence, with no screen, toolbar, or navigation of
 * its own. A future host hosts this behind `AndroidView` and owns [openSheet]'s lifecycle; this view
 * never closes it.
 *
 * Two child views do the actual drawing: [InkCommittedStrokesView] renders every dry stroke and the
 * sheet's own background, and an `androidx.ink` `InProgressStrokesView` renders whatever is still
 * being drawn. This view itself only interprets touch input and coordinates the two.
 */
class InkDrawingSurface(
    context: Context,
    private val openSheet: OpenSheet,
    private val mainPost: (() -> Unit) -> Unit = { action -> Handler(Looper.getMainLooper()).post(action) }
) : FrameLayout(context) {

    private val committedView = InkCommittedStrokesView(context)
    private val inProgressView = InProgressStrokesView(context)
    private val predictor = MotionEventPredictor.newInstance(inProgressView)
    private val gestureArbiter = InkGestureArbiter()
    private val meshBuilder = InkMeshBuilder()
    private val persistenceQueue = InkPersistenceQueue(
        sink = OpenSheetEditSink(openSheet),
        onFailure = { error -> mainPost { listener?.onPersistenceFailure(error) } }
    )

    private val committer = InkEditCommitter(SheetEditHistory(), persistenceQueue::enqueue)

    private val liveStrokes = LinkedHashMap<StrokeId, InkStroke>()
    private val builtCache = HashMap<StrokeId, Stroke>()

    private var viewport = SheetViewport.initial(viewWidthPx = 1f, viewHeightPx = 1f)
    private var tool = InkSurfaceTool.PEN
    private var penTip = InkTip.BALLPOINT
    private var penColorArgb = STROKE_THEME_INK_SENTINEL_ARGB
    private var penWidthSheetUnits = InkPenWidths.MEDIUM_SHEET_UNITS
    private var highlighterColorArgb = HighlighterColorChoice.YELLOW.storedArgb
    private var highlighterWidthSheetUnits = mmToSheetUnits(HIGHLIGHTER_WIDTH_DEFAULT_MM.toFloat())
    private var eraserSizeMm = ERASER_SIZE_DEFAULT_MM.toFloat()
    private var eraserMode = InkEraserMode.WHOLE_STROKE
    private var shape = InkShape.LINE
    private var shapeColorArgb = STROKE_THEME_INK_SENTINEL_ARGB
    private var shapeWidthSheetUnits = InkPenWidths.MEDIUM_SHEET_UNITS

    private var straightenMode = InkStraightenMode.NEVER
    private var currentDrawInputKind = InkInputKind.UNKNOWN
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val straightenTracker = PenStraightenTracker(slopPx = touchSlopPx)
    private var straightenPreviewActive = false
    private var straightenPreviewRecognized: RecognizedShape? = null
    private var straightenCheckRunnable: Runnable? = null

    /** Set once an [ON_HOLD][InkStraightenMode.ON_HOLD] snap fires, to what it snapped: [straightenPreviewRecognized] itself is resized from this immutable snapshot on every move, never from its own previous, already-resized value. */
    private var straightenSnapRecognized: RecognizedShape? = null
    private var straightenFingerAtSnapViewPx: ViewPoint? = null
    private var straightenFingerAtSnapSheet: SheetPoint? = null
    private var straightenResizeGate: StraightenResizeGate? = null
    private var straightenPreviewScheduled = false

    /** Set through [setColors], never read from Compose: see [InkSurfaceColors.themeInk]. */
    private var colors = InkSurfaceColors.NEUTRAL_PLACEHOLDER

    private var currentStrokeId: InProgressStrokeId? = null
    private var currentPointerId: Int = -1
    private val pendingStrokes = PendingStrokes<InProgressStrokeId>()

    private val eraserPath = mutableListOf<SheetPoint>()
    private val eraserRemovedModels = mutableListOf<InkStroke>()

    private var partialEraseSession: PartialEraseSession? = null
    private var partialErasePreviousPoint: SheetPoint? = null

    private var shapeStartPoint: SheetPoint? = null
    private var shapeEndPoint: SheetPoint? = null
    private var shapeInputKind = InkInputKind.UNKNOWN
    private var shapePreviewScheduled = false

    private val panZoomTracker = PanZoomTracker(
        panSlopPx = ViewConfiguration.get(context).scaledTouchSlop * PAN_SLOP_TOUCH_SLOP_MULTIPLIER
    )

    var listener: InkSurfaceListener? = null

    init {
        addView(committedView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(inProgressView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        @Suppress("DEPRECATION")
        inProgressView.useHighLatencyRenderHelper = prefersStandardInkRenderer(Build.VERSION.SDK_INT, isFrontBufferSupported())

        inProgressView.addFinishedStrokesListener(FinishedStrokesListener())

        for (stroke in openSheet.strokes()) liveStrokes[stroke.id] = stroke
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val contentBottom = sheetContentBounds(liveStrokes.values.toList())?.bottom ?: 0f
        val wasUnmeasured = oldw <= 0 || oldh <= 0
        viewport = if (wasUnmeasured) {
            SheetViewport.initial(w.toFloat(), h.toFloat(), contentBottom)
        } else {
            viewport.resized(w.toFloat(), h.toFloat())
        }
        committedView.viewport = viewport
        listener?.onViewportChanged(viewport)

        if (wasUnmeasured) scheduleMeshBuild()
    }

    private fun scheduleMeshBuild() {
        val center = viewport.viewToSheet(ViewPoint(viewport.viewWidthPx / 2f, viewport.viewHeightPx / 2f))
        meshBuilder.build(liveStrokes.values.toList(), center, colors.themeInk) { batch ->
            mainPost {
                for ((model, built) in stillLive(batch) { liveStrokes.containsKey(it.id) }) {
                    builtCache[model.id] = built
                    committedView.putBuiltStroke(model, built)
                }
                listener?.onStrokeCountChanged(liveStrokes.size)
            }
        }
    }

    // region touch dispatch

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleFirstPointerDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> handleAdditionalPointerDown(event)
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP -> handleLastPointerUp(event)
            MotionEvent.ACTION_POINTER_UP -> handleNonLastPointerUp(event)
            MotionEvent.ACTION_CANCEL -> handleCancel(event)
        }
        return true
    }

    private fun handleFirstPointerDown(event: MotionEvent) {
        val pointerId = event.getPointerId(0)
        val toolType = inkInputKindOfMotionEventToolType(event.getToolType(0))
        gestureArbiter.onPointerDown(toolType, tool)
        requestUnbufferedDispatch(event)
        predictor.record(event)

        when (gestureArbiter.gesture) {
            InkGesture.DRAW -> {
                listener?.onStrokeStarted()
                startDraw(event, pointerId)
            }
            InkGesture.ERASE -> {
                listener?.onStrokeStarted()
                startErase(event)
            }
            InkGesture.SHAPE -> {
                listener?.onStrokeStarted()
                startShape(event)
            }
            InkGesture.PAN_ZOOM -> rebaselinePanZoom(event)
            InkGesture.IGNORE -> Unit
        }
    }

    private fun handleAdditionalPointerDown(event: MotionEvent) {
        val previousGesture = gestureArbiter.gesture
        val toolType = inkInputKindOfMotionEventToolType(event.getToolType(event.actionIndex))
        val canceled = gestureArbiter.onPointerDown(toolType, tool)

        if (canceled) cancelActiveGesture(event, previousGesture)
        if (gestureArbiter.gesture == InkGesture.PAN_ZOOM) rebaselinePanZoom(event)
    }

    private fun handleMove(event: MotionEvent) {
        when (gestureArbiter.gesture) {
            InkGesture.DRAW -> continueDraw(event)
            InkGesture.ERASE -> continueErase(event)
            InkGesture.SHAPE -> continueShape(event)
            InkGesture.PAN_ZOOM -> continuePanZoom(event)
            InkGesture.IGNORE -> Unit
        }
    }

    private fun handleLastPointerUp(event: MotionEvent) {
        when (gestureArbiter.gesture) {
            InkGesture.DRAW -> finishDraw(event)
            InkGesture.ERASE -> finishErase()
            InkGesture.SHAPE -> finishShape(event)
            else -> Unit
        }
        gestureArbiter.onPointerUp(remainingPointerCount = 0)
    }

    private fun handleNonLastPointerUp(event: MotionEvent) {
        gestureArbiter.onPointerUp(remainingPointerCount = event.pointerCount - 1)
        if (gestureArbiter.gesture == InkGesture.PAN_ZOOM) rebaselinePanZoom(event, excludingPointerAtIndex = event.actionIndex)
    }

    private fun handleCancel(event: MotionEvent) {
        cancelActiveGesture(event, gestureArbiter.gesture)
        gestureArbiter.onCancel()
    }

    private fun cancelActiveGesture(event: MotionEvent, gesture: InkGesture) {
        when (gesture) {
            InkGesture.DRAW -> {
                currentStrokeId?.let { id ->
                    inProgressView.cancelStroke(id, event)
                    pendingStrokes.discard(id)
                    currentStrokeId = null
                }
                cancelStraightening()
            }
            InkGesture.ERASE -> cancelErase()
            InkGesture.SHAPE -> cancelShape()
            else -> Unit
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelStraightenCheck()
    }

    // endregion

    // region drawing

    private fun startDraw(event: MotionEvent, pointerId: Int) {
        if (!acceptsEdits) return

        currentPointerId = pointerId
        val (brush, meta) = brushAndMetaForCurrentTool()
        val transform = motionEventToStrokeSpaceTransform(viewport)
        val strokeId = inProgressView.startStroke(event, pointerId, brush, motionEventToWorldTransform = transform)

        pendingStrokes.register(strokeId, meta)
        currentStrokeId = strokeId
        startStraightening(event)
    }

    /**
     * The brush to start a stroke with, and the [PendingStrokeMeta] to record it under once it
     * finishes, for the currently selected [tool]. [InkStroke.tip] has no meaning for a highlighter
     * stroke — its brush is [highlighterBrushFor], never [brushFor] — so it is always stored as
     * [InkTip.BALLPOINT] rather than reusing whatever the pen's own tip happens to be set to.
     */
    private fun brushAndMetaForCurrentTool(): Pair<Brush, PendingStrokeMeta> =
        if (tool == InkSurfaceTool.HIGHLIGHTER) {
            highlighterBrushFor(highlighterColorArgb, highlighterWidthSheetUnits) to
                PendingStrokeMeta(InkTool.HIGHLIGHTER, InkTip.BALLPOINT, highlighterColorArgb, highlighterWidthSheetUnits)
        } else {
            brushFor(penTip, resolveStrokeColor(penColorArgb, colors.themeInk, InkTool.PEN), penWidthSheetUnits) to
                PendingStrokeMeta(InkTool.PEN, penTip, penColorArgb, penWidthSheetUnits)
        }

    private fun continueDraw(event: MotionEvent) {
        currentStrokeId?.let { strokeId ->
            predictor.record(event)
            inProgressView.addToStroke(event, currentPointerId, strokeId, predictor.predict())
        }
        continueStraightening(event)
    }

    private fun finishDraw(event: MotionEvent) {
        if (straightenPreviewActive) {
            commitStraightenedPreview()
            return
        }

        val strokeId = currentStrokeId ?: return

        if (tool == InkSurfaceTool.PEN && straightenMode == InkStraightenMode.ALWAYS) {
            collectStraightenSamples(event)
            val recognized = recognizeShape(straightenTracker.points)
            if (recognized != null) {
                inProgressView.cancelStroke(strokeId)
                pendingStrokes.discard(strokeId)
                currentStrokeId = null
                cancelStraightenCheck()
                commitStraightenedShape(recognized)
                return
            }
        }

        inProgressView.finishStroke(event, currentPointerId, strokeId)
        currentStrokeId = null
        cancelStraightenCheck()
    }

    // region straightening

    /**
     * Starts tracking [event]'s own points for [InkStraightenMode]: only the [InkSurfaceTool.PEN]
     * ever straightens, never the highlighter, so [InkStraightenMode.NEVER] and every other tool skip
     * tracking outright rather than paying for points nothing will ever read.
     */
    private fun startStraightening(event: MotionEvent) {
        cancelStraightening()
        if (tool != InkSurfaceTool.PEN || straightenMode == InkStraightenMode.NEVER) return

        currentDrawInputKind = inkInputKindOfMotionEventToolType(event.getToolType(0))
        straightenTracker.onDown(viewport.viewToSheet(ViewPoint(event.x, event.y)), event.x, event.y, event.eventTime)
        if (straightenMode == InkStraightenMode.ON_HOLD) scheduleStraightenCheck()
    }

    /**
     * Feeds [event]'s own move into [straightenTracker], once the current stroke is still eligible;
     * once [straightenPreviewActive] instead resizes the shape [trySnapToShapeOnHold] already snapped
     * to, through [continueStraightenResize].
     */
    private fun continueStraightening(event: MotionEvent) {
        if (tool != InkSurfaceTool.PEN || straightenMode == InkStraightenMode.NEVER) return

        if (straightenPreviewActive) {
            continueStraightenResize(event)
            return
        }

        collectStraightenSamples(event)
        if (straightenMode == InkStraightenMode.ON_HOLD) scheduleStraightenCheck()
    }

    /**
     * Resizes the shape shown by [straightenPreviewRecognized], once [event]'s own pointer has moved
     * past [straightenResizeGate]'s slop from where it was at the snap: before that, holding or
     * trembling never touches the shape, matching [StraightenHoldDetector]'s own slop for the hold
     * that led here. Recomputed every time from [straightenSnapRecognized], the shape exactly as
     * [trySnapToShapeOnHold] left it, rather than from the shape's own last resized value, so the
     * anchor corner [resizeRecognizedShape] picked at the snap never drifts across moves.
     */
    private fun continueStraightenResize(event: MotionEvent) {
        val fingerAtSnapViewPx = straightenFingerAtSnapViewPx ?: return
        val fingerAtSnapSheet = straightenFingerAtSnapSheet ?: return
        val snapRecognized = straightenSnapRecognized ?: return
        val gate = straightenResizeGate ?: return

        val resizing = gate.hasExceededSlop(event.x - fingerAtSnapViewPx.x, event.y - fingerAtSnapViewPx.y)
        if (!resizing) return

        val fingerNowSheet = viewport.viewToSheet(ViewPoint(event.x, event.y))
        straightenPreviewRecognized = resizeRecognizedShape(snapRecognized, fingerAtSnapSheet, fingerNowSheet)
        scheduleStraightenPreviewRebuild()
    }

    /** Coalesces straighten-resize preview rebuilds to at most one per frame, the same way [scheduleShapePreviewRebuild] does for the SHAPE tool's own drag. */
    private fun scheduleStraightenPreviewRebuild() {
        if (straightenPreviewScheduled) return

        straightenPreviewScheduled = true
        postOnAnimation {
            straightenPreviewScheduled = false
            rebuildStraightenPreview()
        }
    }

    private fun rebuildStraightenPreview() {
        val recognized = straightenPreviewRecognized ?: return
        committedView.shapePreview = buildShapeInkStrokes(
            recognized.start, recognized.end, recognized.shape, penColorArgb, penWidthSheetUnits, penTip, currentDrawInputKind, recognized.vertices
        )
    }

    /** Every sheet-space sample [event] carries, its own historical batch included, fed to [straightenTracker] in order. */
    private fun collectStraightenSamples(event: MotionEvent) {
        for (i in 0 until event.historySize) {
            val historicalX = event.getHistoricalX(i)
            val historicalY = event.getHistoricalY(i)
            val point = viewport.viewToSheet(ViewPoint(historicalX, historicalY))

            straightenTracker.onMove(point, historicalX, historicalY, event.getHistoricalEventTime(i))
        }

        straightenTracker.onMove(viewport.viewToSheet(ViewPoint(event.x, event.y)), event.x, event.y, event.eventTime)
    }

    private fun scheduleStraightenCheck() {
        cancelStraightenCheck()
        val nextCheckAtMillis = straightenTracker.nextCheckAtMillis() ?: return

        val runnable = Runnable(::onStraightenCheck)
        straightenCheckRunnable = runnable
        postDelayed(runnable, (nextCheckAtMillis - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private fun cancelStraightenCheck() {
        straightenCheckRunnable?.let(::removeCallbacks)
        straightenCheckRunnable = null
    }

    private fun onStraightenCheck() {
        straightenCheckRunnable = null
        if (currentStrokeId == null || straightenMode != InkStraightenMode.ON_HOLD) return
        if (straightenTracker.isHeld(SystemClock.uptimeMillis())) trySnapToShapeOnHold()
    }

    /** [ON_HOLD][InkStraightenMode.ON_HOLD]'s own hold firing: cancels the freehand stroke in progress and shows its snapped replacement instead, uncommitted until [commitStraightenedPreview]. */
    private fun trySnapToShapeOnHold() {
        val strokeId = currentStrokeId ?: return
        val recognized = recognizeShape(straightenTracker.points) ?: return

        inProgressView.cancelStroke(strokeId)
        pendingStrokes.discard(strokeId)
        currentStrokeId = null

        straightenPreviewActive = true
        straightenPreviewRecognized = recognized
        straightenSnapRecognized = recognized
        straightenResizeGate = StraightenResizeGate(touchSlopPx)
        val (fingerAtSnapViewX, fingerAtSnapViewY) = straightenTracker.lastPositionPx()
        straightenFingerAtSnapViewPx = ViewPoint(fingerAtSnapViewX, fingerAtSnapViewY)
        straightenFingerAtSnapSheet = straightenTracker.points.last()
        committedView.shapePreview = buildShapeInkStrokes(
            recognized.start, recognized.end, recognized.shape, penColorArgb, penWidthSheetUnits, penTip, currentDrawInputKind, recognized.vertices
        )
    }

    /** Commits whatever [trySnapToShapeOnHold] or [continueStraightenResize] last showed, or does nothing if it was cleared by a cancellation first. */
    private fun commitStraightenedPreview() {
        val recognized = straightenPreviewRecognized
        clearShapePreview()
        clearStraightenResizeState()
        cancelStraightenCheck()

        if (recognized != null) commitStraightenedShape(recognized)
    }

    private fun commitStraightenedShape(recognized: RecognizedShape) {
        val models = shapeModels(
            recognized.start, recognized.end, recognized.shape, penColorArgb, penWidthSheetUnits, penTip, currentDrawInputKind, recognized.vertices
        ) { openSheet.nextSequence() }
        commitShapeModels(models)
    }

    /** Discards whatever [straightenTracker] and any snapped, uncommitted preview were holding for the current stroke. */
    private fun cancelStraightening() {
        cancelStraightenCheck()
        if (straightenPreviewActive) clearShapePreview()
        clearStraightenResizeState()
        straightenTracker.reset()
    }

    /** Resets [trySnapToShapeOnHold]'s and [continueStraightenResize]'s own state, once a straightened preview has been committed or cancelled. */
    private fun clearStraightenResizeState() {
        straightenPreviewActive = false
        straightenPreviewRecognized = null
        straightenSnapRecognized = null
        straightenFingerAtSnapViewPx = null
        straightenFingerAtSnapSheet = null
        straightenResizeGate = null
        straightenPreviewScheduled = false
    }

    // endregion

    private inner class FinishedStrokesListener : InProgressStrokesFinishedListener {
        override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
            val newModels = strokes.map { (strokeId, built) ->
                val pending = pendingStrokes.resolve(strokeId) { metaFromBrush(built) }
                val model = fromAndroidxStroke(
                    built, StrokeId(UUID.randomUUID().toString()), openSheet.nextSequence(),
                    pending.tool, pending.tip, pending.colorArgb, pending.widthSheetUnits
                )
                builtCache[model.id] = built
                liveStrokes[model.id] = model
                committedView.putBuiltStroke(model, built)
                model
            }
            committedView.invalidate()
            inProgressView.removeFinishedStrokes(strokes.keys)

            if (newModels.isNotEmpty()) commitEdit(SheetEdit.AddStrokes(newModels))
            listener?.onStrokeCountChanged(liveStrokes.size)
        }
    }

    // endregion

    // region erasing

    private fun startErase(event: MotionEvent) {
        if (!acceptsEdits) return
        when (eraserMode) {
            InkEraserMode.WHOLE_STROKE -> startWholeStrokeErase(event)
            InkEraserMode.PARTIAL -> startPartialErase(event)
        }
    }

    private fun continueErase(event: MotionEvent) {
        when (eraserMode) {
            InkEraserMode.WHOLE_STROKE -> continueWholeStrokeErase(event)
            InkEraserMode.PARTIAL -> continuePartialErase(event)
        }
    }

    private fun finishErase() {
        committedView.eraserFootprint = null
        when (eraserMode) {
            InkEraserMode.WHOLE_STROKE -> finishWholeStrokeErase()
            InkEraserMode.PARTIAL -> finishPartialErase()
        }
    }

    private fun cancelErase() {
        committedView.eraserFootprint = null
        when (eraserMode) {
            InkEraserMode.WHOLE_STROKE -> cancelWholeStrokeErase()
            InkEraserMode.PARTIAL -> cancelPartialErase()
        }
    }

    private fun updateEraserFootprint(event: MotionEvent) {
        committedView.eraserFootprint = InkCommittedStrokesView.EraserFootprint(
            centerXPx = event.x,
            centerYPx = event.y,
            radiusPx = currentEraserRadiusSheetUnits() * viewport.scale
        )
    }

    // region whole-stroke erasing

    private fun startWholeStrokeErase(event: MotionEvent) {
        eraserPath.clear()
        eraserRemovedModels.clear()
        eraserPath += viewport.viewToSheet(ViewPoint(event.x, event.y))
        updateEraserFootprint(event)
        applyEraserHits()
    }

    private fun continueWholeStrokeErase(event: MotionEvent) {
        if (eraserPath.isEmpty()) return
        eraserPath += viewport.viewToSheet(ViewPoint(event.x, event.y))
        updateEraserFootprint(event)
        applyEraserHits()
    }

    private fun applyEraserHits() {
        val alreadyRemovedIds = eraserRemovedModels.mapTo(mutableSetOf()) { it.id }
        val hitIds = strokesHitBy(eraserPath, currentEraserRadiusSheetUnits(), liveStrokes.values.toList())
        val newlyHitIds = hitIds - alreadyRemovedIds
        if (newlyHitIds.isEmpty()) return

        val newlyHitModels = newlyHitIds.mapNotNull { liveStrokes[it] }
        eraserRemovedModels += newlyHitModels
        for (model in newlyHitModels) liveStrokes.remove(model.id)
        committedView.removeStrokes(newlyHitIds)
        listener?.onStrokeCountChanged(liveStrokes.size)
    }

    private fun finishWholeStrokeErase() {
        val committed = eraserRemovedModels.isEmpty() || commitEdit(SheetEdit.RemoveStrokes(eraserRemovedModels.toList()))
        if (!committed) {
            cancelWholeStrokeErase()
            return
        }

        eraserPath.clear()
        eraserRemovedModels.clear()
    }

    private fun cancelWholeStrokeErase() {
        if (eraserRemovedModels.isNotEmpty()) {
            for (model in eraserRemovedModels) {
                liveStrokes[model.id] = model
                builtCache[model.id]?.let { built -> committedView.putBuiltStroke(model, built) }
            }
            listener?.onStrokeCountChanged(liveStrokes.size)
        }
        eraserPath.clear()
        eraserRemovedModels.clear()
    }

    // endregion

    // region partial erasing

    private fun startPartialErase(event: MotionEvent) {
        val point = viewport.viewToSheet(ViewPoint(event.x, event.y))
        partialEraseSession = PartialEraseSession(liveStrokes.values.toList())
        partialErasePreviousPoint = point
        updateEraserFootprint(event)
        applyPartialEraseSegment(listOf(point))
    }

    private fun continuePartialErase(event: MotionEvent) {
        partialEraseSession ?: return
        val previousPoint = partialErasePreviousPoint ?: return
        val point = viewport.viewToSheet(ViewPoint(event.x, event.y))

        updateEraserFootprint(event)
        applyPartialEraseSegment(listOf(previousPoint, point))
        partialErasePreviousPoint = point
    }

    /** Erases along [segment] against [partialEraseSession]'s own live strokes and mirrors the change on screen right away, so a slow drag shows fragments splitting off in real time rather than only once the gesture lifts. */
    private fun applyPartialEraseSegment(segment: List<SheetPoint>) {
        val session = partialEraseSession ?: return
        val step = session.apply(
            eraserSegment = segment,
            eraserRadius = currentEraserRadiusSheetUnits(),
            newId = { StrokeId(UUID.randomUUID().toString()) },
            newSequence = openSheet::nextSequence
        )

        if (step.removedNow.isEmpty() && step.addedNow.isEmpty()) return

        removeVisible(step.removedNow)
        addVisible(step.addedNow)
        listener?.onStrokeCountChanged(liveStrokes.size)
    }

    private fun finishPartialErase() {
        val session = partialEraseSession ?: return
        partialEraseSession = null
        partialErasePreviousPoint = null

        val edit = session.result() ?: return
        if (!commitEdit(edit)) restorePartialEraseSession(session)
    }

    private fun cancelPartialErase() {
        val session = partialEraseSession ?: return
        partialEraseSession = null
        partialErasePreviousPoint = null
        restorePartialEraseSession(session)
    }

    /**
     * Takes [session]'s net effect back off the screen — the fragments it produced removed, its
     * original strokes put back — the exact inverse of what [applyPartialEraseSegment] already showed
     * across the gesture's own moves, whether the gesture was cancelled outright or its result was
     * refused at commit time.
     */
    private fun restorePartialEraseSession(session: PartialEraseSession) {
        val edit = session.result() ?: return
        removeVisible(edit.added)
        addVisible(edit.removed)
        listener?.onStrokeCountChanged(liveStrokes.size)
    }

    // endregion

    // region shapes

    private fun startShape(event: MotionEvent) {
        if (!acceptsEdits) return

        shapeInputKind = inkInputKindOfMotionEventToolType(event.getToolType(0))
        val point = viewport.viewToSheet(ViewPoint(event.x, event.y))
        shapeStartPoint = point
        shapeEndPoint = point
        scheduleShapePreviewRebuild()
    }

    private fun continueShape(event: MotionEvent) {
        if (shapeStartPoint == null) return

        shapeEndPoint = viewport.viewToSheet(ViewPoint(event.x, event.y))
        scheduleShapePreviewRebuild()
    }

    /** Coalesces preview rebuilds to at most one per frame: several `ACTION_MOVE` events land between two frames, and only the last one's endpoint matters. */
    private fun scheduleShapePreviewRebuild() {
        if (shapePreviewScheduled) return

        shapePreviewScheduled = true
        postOnAnimation {
            shapePreviewScheduled = false
            rebuildShapePreview()
        }
    }

    private fun rebuildShapePreview() {
        val start = shapeStartPoint ?: return
        val end = shapeEndPoint ?: return
        committedView.shapePreview = buildShapeInkStrokes(start, end, shape, shapeColorArgb, shapeWidthSheetUnits, InkTip.BALLPOINT, shapeInputKind)
    }

    /**
     * A shape's own [Stroke]s for a drag spanning [start] to [end], built through the exact same
     * [toInkStroke] path a committed stroke is, so this preview and its eventual commit are
     * pixel-identical: neither is routed through [InkMeshBuilder], which builds asynchronously and
     * would show a visible gap between a shape's last preview frame and its first committed one.
     * [colorArgb], [widthSheetUnits] and [tip] are the shape tool's own current settings for the
     * SHAPE tool's own drag, or the pen's for a straightened pen stroke (`rail-spec.md` 2.2, FORMA
     * panel, and ENDEREZAR): independent of each other so a THEME-coloured shape and a THEME-coloured
     * pen stroke each keep following their own choice. [vertices] is a recognised [InkShape.TRIANGLE]'s
     * own three real corners, empty for every other shape and for a SHAPE-tool drag.
     */
    private fun buildShapeInkStrokes(
        start: SheetPoint,
        end: SheetPoint,
        shape: InkShape,
        colorArgb: Int,
        widthSheetUnits: Float,
        tip: InkTip,
        inputKind: InkInputKind,
        vertices: List<SheetPoint> = emptyList()
    ): List<Stroke> =
        shapeModels(start, end, shape, colorArgb, widthSheetUnits, tip, inputKind, vertices) { SHAPE_PREVIEW_SEQUENCE }
            .map { model -> toInkStroke(model, colors.themeInk) }

    /**
     * One [InkStroke] per polyline [shapeSamples] returns for the drag from [start] to [end], each an
     * ordinary [InkTool.PEN] stroke in [colorArgb], [widthSheetUnits] and [tip] — its own consecutive
     * sample times ([shapeSampleTimesMillis], a slow constant pen speed) and its own [InkStroke.sequence]
     * from [sequenceFor], called once per stroke so a multi-stroke shape — an arrow's shaft and head —
     * still gets consecutive draw order. [vertices] is forwarded to [shapeSamples] as a recognised
     * [InkShape.TRIANGLE]'s own three real corners.
     */
    private fun shapeModels(
        start: SheetPoint,
        end: SheetPoint,
        shape: InkShape,
        colorArgb: Int,
        widthSheetUnits: Float,
        tip: InkTip,
        inputKind: InkInputKind,
        vertices: List<SheetPoint> = emptyList(),
        sequenceFor: () -> Long
    ): List<InkStroke> =
        shapeSamples(shape, start, end, widthSheetUnits, vertices).map { polyline ->
            InkStroke(
                id = StrokeId(UUID.randomUUID().toString()),
                tool = InkTool.PEN,
                tip = tip,
                colorArgb = colorArgb,
                widthSheetUnits = widthSheetUnits,
                inputKind = inputKind,
                samples = polyline.zip(shapeSampleTimesMillis(polyline)) { point, elapsed -> InkSample(x = point.x, y = point.y, elapsedMillis = elapsed) },
                sequence = sequenceFor()
            )
        }

    private fun finishShape(event: MotionEvent) {
        val start = shapeStartPoint
        shapeEndPoint = viewport.viewToSheet(ViewPoint(event.x, event.y))
        val end = shapeEndPoint
        clearShapePreview()
        shapeStartPoint = null
        shapeEndPoint = null

        if (start == null || end == null || !acceptsEdits) return

        val models = shapeModels(start, end, shape, shapeColorArgb, shapeWidthSheetUnits, InkTip.BALLPOINT, shapeInputKind) { openSheet.nextSequence() }
        commitShapeModels(models)
    }

    /** Shows every model in [models] as a live, built, committed stroke and hands them to the writer as one [SheetEdit.AddStrokes]. */
    private fun commitShapeModels(models: List<InkStroke>) {
        if (models.isEmpty() || !acceptsEdits) return

        for (model in models) {
            val built = toInkStroke(model, colors.themeInk)
            builtCache[model.id] = built
            liveStrokes[model.id] = model
            committedView.putBuiltStroke(model, built)
        }
        listener?.onStrokeCountChanged(liveStrokes.size)

        if (!commitEdit(SheetEdit.AddStrokes(models))) removeUncommitted(models)
    }

    /** Takes strokes that were shown ahead of their commit back off the sheet once the writer refused them. */
    private fun removeUncommitted(models: List<InkStroke>) {
        val ids = models.map { it.id }

        for (id in ids) {
            liveStrokes.remove(id)
            builtCache.remove(id)
        }
        committedView.removeStrokes(ids)
        listener?.onStrokeCountChanged(liveStrokes.size)
    }

    private fun cancelShape() {
        clearShapePreview()
        shapeStartPoint = null
        shapeEndPoint = null
    }

    private fun clearShapePreview() {
        shapePreviewScheduled = false
        committedView.shapePreview = emptyList()
    }

    // endregion

    // region pan and zoom

    private fun rebaselinePanZoom(event: MotionEvent, excludingPointerAtIndex: Int = -1) {
        panZoomTracker.rebaseline(activePointerPositions(event, excludingPointerAtIndex))
    }

    private fun continuePanZoom(event: MotionEvent) {
        val step = panZoomTracker.onMove(activePointerPositions(event))
        viewport = viewport.pannedBy(dxPx = -step.panDxPx, dyPx = -step.panDyPx)

        if (step.zoomFactor != 1f) viewport = viewport.zoomedBy(step.zoomFactor, ViewPoint(step.focalX, step.focalY))

        committedView.viewport = viewport
        listener?.onViewportChanged(viewport)
    }

    /**
     * Sets the viewport's zoom to [zoom] directly, about the view's own centre, clamped by
     * [SheetViewport]; notifies [InkSurfaceListener.onViewportChanged] the same way a pinch does.
     */
    fun setZoom(zoom: Float) {
        val focal = ViewPoint(viewport.viewWidthPx / 2f, viewport.viewHeightPx / 2f)
        viewport = viewport.zoomedTo(zoom, focal)
        committedView.viewport = viewport
        listener?.onViewportChanged(viewport)
    }

    /** Resets the zoom to [SheetViewport.MIN_ZOOM] — the sheet's nominal width filling the view — keeping the current top of the view. */
    fun fitWidth() {
        viewport = viewport.fittedToWidth()
        committedView.viewport = viewport
        listener?.onViewportChanged(viewport)
    }

    /**
     * The still-down pointers of [event], as [PanZoomTracker] input. On `ACTION_POINTER_UP`,
     * [excludingPointerAtIndex] is the lifting pointer's index, which [MotionEvent] still reports as
     * present; it must be left out so the focal and span this move is rebaselined against reflect
     * only the pointers that remain.
     */
    private fun activePointerPositions(event: MotionEvent, excludingPointerAtIndex: Int = -1): List<PointerPosition> =
        (0 until event.pointerCount)
            .filter { index -> index != excludingPointerAtIndex }
            .map { index -> PointerPosition(event.getX(index), event.getY(index)) }

    // endregion

    // region history, persistence and public state

    /**
     * Whether an edit made now could still be persisted. Once persistence has failed or this surface
     * has been closed, no new stroke, erase, undo or redo is started, so the screen never shows an
     * edit that the sheet will not contain.
     */
    private val acceptsEdits: Boolean get() = !persistenceQueue.hasFailed && !persistenceQueue.isClosed

    /**
     * The settings to record a finished stroke under when its start was not registered here: the
     * width comes back from the brush it was actually drawn with, but the colour is the current pen's
     * own stored colour, [penColorArgb], rather than [Stroke.brush]'s resolved pixel colour — the
     * brush only ever carries the ink a stroke was actually painted with, never the
     * [STROKE_THEME_INK_SENTINEL_ARGB] a THEME-choice stroke is stored under.
     */
    private fun metaFromBrush(built: Stroke): PendingStrokeMeta =
        if (tool == InkSurfaceTool.HIGHLIGHTER) {
            PendingStrokeMeta(
                tool = InkTool.HIGHLIGHTER,
                tip = InkTip.BALLPOINT,
                colorArgb = highlighterColorArgb,
                widthSheetUnits = StrokeSpace.strokeSpaceToSheet(built.brush.size)
            )
        } else {
            PendingStrokeMeta(
                tool = InkTool.PEN,
                tip = penTip,
                colorArgb = penColorArgb,
                widthSheetUnits = StrokeSpace.strokeSpaceToSheet(built.brush.size)
            )
        }

    /**
     * Hands [edit] to the writer and the history, and returns whether it was accepted. A refused
     * edit is reported as a persistence failure: an added stroke stays on screen, as unsaved ink the
     * host has just been told about, and a refused erase is put back by its caller.
     */
    private fun commitEdit(edit: SheetEdit): Boolean {
        val accepted = committer.commit(edit)
        if (!accepted) listener?.onPersistenceFailure(InkEditRefusedException(edit))

        refreshContentBottom()
        listener?.onHistoryChanged(committer.canUndo, committer.canRedo)
        return accepted
    }

    fun undo() {
        if (!acceptsEdits) return

        val edit = committer.undo() ?: return
        applyVisible(edit)
        refreshContentBottom()
        listener?.onHistoryChanged(committer.canUndo, committer.canRedo)
        listener?.onStrokeCountChanged(liveStrokes.size)
    }

    fun redo() {
        if (!acceptsEdits) return

        val edit = committer.redo() ?: return
        applyVisible(edit)
        refreshContentBottom()
        listener?.onHistoryChanged(committer.canUndo, committer.canRedo)
        listener?.onStrokeCountChanged(liveStrokes.size)
    }

    /**
     * Removes every live stroke on the sheet as one [SheetEdit.RemoveStrokes], the same edit an
     * ordinary erase commits, so it is persisted and undone with a single undo. Does nothing, and
     * returns `true`, when the sheet already has no strokes; returns `false` without touching
     * anything when the surface no longer accepts edits or the writer refuses the removal.
     */
    fun clearAll(): Boolean {
        if (!acceptsEdits) return false

        val allModels = liveStrokes.values.toList()
        if (allModels.isEmpty()) return true

        for (model in allModels) liveStrokes.remove(model.id)
        committedView.removeStrokes(allModels.map { it.id })
        listener?.onStrokeCountChanged(liveStrokes.size)

        val accepted = commitEdit(SheetEdit.RemoveStrokes(allModels))
        if (!accepted) {
            for (model in allModels) {
                liveStrokes[model.id] = model
                builtCache[model.id]?.let { built -> committedView.putBuiltStroke(model, built) }
            }
            listener?.onStrokeCountChanged(liveStrokes.size)
        }

        return accepted
    }

    private fun applyVisible(edit: SheetEdit) {
        when (edit) {
            is SheetEdit.AddStrokes -> addVisible(edit.strokes)
            is SheetEdit.RemoveStrokes -> removeVisible(edit.strokes)
            is SheetEdit.ReplaceStrokes -> {
                removeVisible(edit.removed)
                addVisible(edit.added)
            }
        }
    }

    /**
     * Shows [models] as live strokes, building any that [builtCache] does not already hold —
     * undoing/redoing a [SheetEdit.ReplaceStrokes] can bring back an [InkStroke] this surface has
     * never rendered before, unlike a plain [SheetEdit.AddStrokes] whose strokes were already built
     * ahead of their commit.
     */
    private fun addVisible(models: List<InkStroke>) {
        for (model in models) {
            liveStrokes[model.id] = model
            val built = builtCache.getOrPut(model.id) { toInkStroke(model, colors.themeInk) }
            committedView.putBuiltStroke(model, built)
        }
    }

    private fun removeVisible(models: List<InkStroke>) {
        val ids = models.map { it.id }
        for (id in ids) liveStrokes.remove(id)
        committedView.removeStrokes(ids)
    }

    private fun refreshContentBottom() {
        val contentBottom = sheetContentBounds(liveStrokes.values.toList())?.bottom ?: 0f
        viewport = viewport.withContentBottom(contentBottom)
        committedView.viewport = viewport
    }

    fun setTool(newTool: InkSurfaceTool) {
        if (newTool == tool) return
        tool = newTool
        cancelStraightenCheck()
    }

    fun setPenTip(newTip: InkTip) {
        penTip = newTip
    }

    fun setPenColorArgb(newColorArgb: Int) {
        penColorArgb = newColorArgb
    }

    fun setPenWidthSheetUnits(newWidthSheetUnits: Float) {
        require(newWidthSheetUnits > 0f) { "newWidthSheetUnits must be positive, was $newWidthSheetUnits" }
        penWidthSheetUnits = newWidthSheetUnits
    }

    fun setHighlighterColorArgb(newColorArgb: Int) {
        highlighterColorArgb = newColorArgb
    }

    fun setHighlighterWidthSheetUnits(newWidthSheetUnits: Float) {
        require(newWidthSheetUnits > 0f) { "newWidthSheetUnits must be positive, was $newWidthSheetUnits" }
        highlighterWidthSheetUnits = newWidthSheetUnits
    }

    fun setShape(newShape: InkShape) {
        shape = newShape
    }

    fun setShapeColorArgb(newColorArgb: Int) {
        shapeColorArgb = newColorArgb
    }

    fun setShapeWidthSheetUnits(newWidthSheetUnits: Float) {
        require(newWidthSheetUnits > 0f) { "newWidthSheetUnits must be positive, was $newWidthSheetUnits" }
        shapeWidthSheetUnits = newWidthSheetUnits
    }

    /** Sets the eraser's diameter in millimetres on the sheet; see [currentEraserRadiusSheetUnits] for how it is applied. */
    fun setEraserSizeMm(newSizeMm: Float) {
        require(newSizeMm > 0f) { "newSizeMm must be positive, was $newSizeMm" }
        eraserSizeMm = newSizeMm
    }

    /** Sets whether the eraser tool takes a whole stroke or only the ink it passes over; takes effect on the next erase gesture, never mid-gesture. */
    fun setEraserMode(newMode: InkEraserMode) {
        eraserMode = newMode
    }

    /**
     * Sets whether the pen tool straightens a recognised stroke into a shape, and when. Cancels
     * [straightenCheckRunnable] rather than the stroke in progress itself, so a mode picked while a
     * stroke is already held still simply stops that hold from being checked again.
     */
    fun setStraightenMode(newMode: InkStraightenMode) {
        if (newMode == straightenMode) return
        straightenMode = newMode
        cancelStraightenCheck()
    }

    /**
     * The eraser's hit radius for the gesture in hand, derived from the live viewport every time it is
     * asked for. Nothing caches it: a radius computed for one zoom and applied at another, or before
     * the view has a size at all, can span the whole sheet and take every stroke with it.
     */
    private fun currentEraserRadiusSheetUnits(): Float {
        val viewPxPerSheetUnit = viewport.scale
        if (viewPxPerSheetUnit <= 0f) return mmToSheetUnits(eraserSizeMm / 2f)

        return eraserHitRadiusSheetUnits(eraserSizeMm, viewPxPerSheetUnit)
    }

    fun setColors(colors: InkSurfaceColors) {
        this.colors = colors
        committedView.colors = colors
    }

    fun setTemplate(template: SheetTemplate) {
        committedView.template = template
    }

    /** Blocks until every enqueued edit has been persisted, or [timeoutMillis] elapses; see [InkPersistenceQueue.flushAndWait]. */
    fun flushAndWait(timeoutMillis: Long): Boolean = persistenceQueue.flushAndWait(timeoutMillis)

    /**
     * Drains the persistence queue and stops this surface's own background work. Never touches
     * [openSheet]: the host opened it and the host alone decides when to close it.
     */
    fun close() {
        persistenceQueue.flushAndWait(CLOSE_DRAIN_TIMEOUT_MILLIS)
        persistenceQueue.shutdown()
        meshBuilder.shutdown()
    }

    // endregion

    private fun isFrontBufferSupported(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_FRONT_BUFFER
        return HardwareBuffer.isSupported(FRONT_BUFFER_PROBE_WIDTH, FRONT_BUFFER_PROBE_HEIGHT, HardwareBuffer.RGBA_8888, 1, usage)
    }
}
