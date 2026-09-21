package com.folium.reader.ink

import android.content.Context
import android.graphics.Matrix
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.folium.reader.core.ink.SheetItem
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetRect
import com.folium.reader.core.ink.SheetTemplate
import com.folium.reader.core.ink.SheetTextBox
import com.folium.reader.core.ink.SheetTextFont
import com.folium.reader.core.ink.SheetTextStyle
import com.folium.reader.core.ink.StrokeId
import com.folium.reader.core.ink.recognizeShape
import com.folium.reader.core.ink.resizeRecognizedShape
import com.folium.reader.core.ink.scaleStrokes
import com.folium.reader.core.ink.selectByLasso
import com.folium.reader.core.ink.selectByRectangle
import com.folium.reader.core.ink.selectionBounds
import com.folium.reader.core.ink.shapeSampleTimesMillis
import com.folium.reader.core.ink.shapeSamples
import com.folium.reader.core.ink.sheetItemContentBounds
import com.folium.reader.core.ink.strokeGroupAtTap
import com.folium.reader.core.ink.strokesHitBy
import com.folium.reader.core.ink.translateStrokes
import java.util.UUID

private const val FRONT_BUFFER_PROBE_WIDTH: Int = 800
private const val FRONT_BUFFER_PROBE_HEIGHT: Int = 1280
private const val CLOSE_DRAIN_TIMEOUT_MILLIS: Long = 5_000L

/** The [InkStroke.sequence] the shape tool's own live preview is built under: never committed, so its value only has to satisfy [InkStroke]'s own non-negative requirement. */
private const val SHAPE_PREVIEW_SEQUENCE: Long = 0L

/** How much wider than the platform's own touch slop a two-finger gesture's pan/zoom decision waits before committing to a scroll. */
private const val PAN_SLOP_TOUCH_SLOP_MULTIPLIER: Float = 2f

/** A resize handle's own hit radius, in device-independent pixels: half of [com.folium.reader.ui.FoliumSpacing.touchTarget], so its own diameter meets the platform's minimum touch target regardless of how small its drawn square is. */
private const val SELECTION_HANDLE_HIT_RADIUS_DP: Float = 22f

/** How far right and down [InkDrawingSurface.copySelection] offsets a copy from its own originals, in millimetres (`rail-spec.md` task instructions). */
private const val SELECTION_COPY_OFFSET_MM: Float = 5f

/** How much room the open text editor's own caret line keeps above the keyboard once [InkDrawingSurface] scrolls it into view, in device-independent pixels. */
private const val TEXT_EDITOR_IME_MARGIN_DP: Float = 12f

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
    private var highlighterStraightenMode = InkStraightenMode.NEVER
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

    private val liveTextBoxes = LinkedHashMap<StrokeId, SheetTextBox>()
    private val textLayoutEngine = TextLayoutEngine(context)
    private val textEditingSession = TextEditingSession(host = this, layoutEngine = textLayoutEngine)
    private var textFont = SheetTextFont.SERIF
    private var textSizePt = 16f
    private var textStyle = SheetTextStyle.NORMAL
    private var textColorArgb = STROKE_THEME_INK_SENTINEL_ARGB
    private var textTapDownPoint: SheetPoint? = null

    /** The text box [textEditingSession] is currently editing, hidden from [committedView]'s own list for as long as the session stays open; `null` while placing a brand-new box or while no session is open. */
    private var hiddenTextBoxId: StrokeId? = null

    private var selectMode = PenSelectMode.LASSO
    private var selectionSession: SelectionGestureSession? = null
    private var selectPreviewScheduled = false
    private var selectedStrokeIds: Set<StrokeId> = emptySet()
    private var selectionBoundsSheet: SheetRect? = null

    private var selectionEditSession: SelectionEditSession? = null
    private var selectionEditBaseModels: List<InkStroke> = emptyList()
    private var selectionEditPreviewScheduled = false

    /** Bumped every time a move or resize drag's own replacement strokes start building; a stale mesh batch from an earlier drag checks this before touching the screen, in case a second drag started before the first one's meshes finished. */
    private var selectionEditGeneration = 0

    private val selectionHandleHitRadiusPx = SELECTION_HANDLE_HIT_RADIUS_DP * resources.displayMetrics.density

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
        for (textBox in openSheet.textBoxes()) liveTextBoxes[textBox.id] = textBox
        committedView.textBoxes = liveTextBoxes.values.toList()

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            handleImeInsets(insets)
            insets
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val contentBottom = sheetItemContentBounds(currentItems())?.bottom ?: 0f
        val wasUnmeasured = oldw <= 0 || oldh <= 0
        viewport = if (wasUnmeasured) {
            SheetViewport.initial(w.toFloat(), h.toFloat(), contentBottom)
        } else {
            viewport.resized(w.toFloat(), h.toFloat())
        }
        committedView.viewport = viewport
        listener?.onViewportChanged(viewport)
        if (selectedStrokeIds.isNotEmpty()) notifySelectionChanged()
        if (textEditingSession.isOpen) textEditingSession.reposition(viewport)

        if (wasUnmeasured) scheduleMeshBuild()
    }

    /** Every live stroke and text box, wrapped as [SheetItem], for the handful of call sites — content bounds, undo/redo replay — that need a mix of both rather than either alone. */
    private fun currentItems(): List<SheetItem> =
        liveStrokes.values.map(SheetItem::Stroke) + liveTextBoxes.values.map(SheetItem::Text)

    /**
     * Notifies [InkSurfaceListener.onStrokeCountChanged] of the sheet's own total item count: strokes
     * and text boxes together, since the eraser panel's own "clear all" — the one thing a host uses
     * this count for — now clears both in [clearAll]'s own single edit.
     */
    private fun notifyItemCount() {
        listener?.onStrokeCountChanged(liveStrokes.size + liveTextBoxes.size)
    }

    private fun scheduleMeshBuild() {
        val center = viewport.viewToSheet(ViewPoint(viewport.viewWidthPx / 2f, viewport.viewHeightPx / 2f))
        meshBuilder.build(liveStrokes.values.toList(), center, colors.themeInk) { batch ->
            mainPost {
                for ((model, built) in stillLive(batch) { liveStrokes.containsKey(it.id) }) {
                    builtCache[model.id] = built
                    committedView.putBuiltStroke(model, built)
                }
                notifyItemCount()
            }
        }
    }

    // region touch dispatch

    /**
     * Intercepts every touch for this surface's own gesture handling, except a fresh
     * [MotionEvent.ACTION_DOWN] that lands inside the open text editor's own bounds: letting that one
     * pointer sequence dispatch normally to the [android.widget.EditText] child is what lets a tap move
     * the caret or select text inside it, since [handleFirstPointerDown] otherwise treats every TEXT
     * gesture as a tap on the sheet itself.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val bounds = textEditingSession.boundsViewPx()
            if (bounds != null && ev.x >= bounds.left && ev.x <= bounds.right && ev.y >= bounds.top && ev.y <= bounds.bottom) {
                return false
            }
        }
        return true
    }

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
            InkGesture.SELECT -> {
                listener?.onStrokeStarted()
                startSelect(event)
            }
            InkGesture.TEXT -> {
                listener?.onStrokeStarted()
                startText(event)
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
            InkGesture.SELECT -> continueSelect(event)
            InkGesture.TEXT -> Unit
            InkGesture.PAN_ZOOM -> continuePanZoom(event)
            InkGesture.IGNORE -> Unit
        }
    }

    private fun handleLastPointerUp(event: MotionEvent) {
        when (gestureArbiter.gesture) {
            InkGesture.DRAW -> finishDraw(event)
            InkGesture.ERASE -> finishErase()
            InkGesture.SHAPE -> finishShape(event)
            InkGesture.SELECT -> finishSelect()
            InkGesture.TEXT -> finishText()
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
            InkGesture.SELECT -> cancelSelect()
            InkGesture.TEXT -> textTapDownPoint = null
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

        if (activeStraightenMode == InkStraightenMode.ALWAYS) {
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

    /** Which of [straightenMode] or [highlighterStraightenMode] governs the stroke [tool] is currently drawing; see [straightenModeFor]. */
    private val activeStraightenMode: InkStraightenMode
        get() = straightenModeFor(tool, straightenMode, highlighterStraightenMode)

    /**
     * Starts tracking [event]'s own points for [InkStraightenMode]: [activeStraightenMode] resolves
     * to [InkStraightenMode.NEVER] for every tool but [InkSurfaceTool.PEN] and
     * [InkSurfaceTool.HIGHLIGHTER], so those skip tracking outright rather than paying for points
     * nothing will ever read.
     */
    private fun startStraightening(event: MotionEvent) {
        cancelStraightening()
        if (activeStraightenMode == InkStraightenMode.NEVER) return

        currentDrawInputKind = inkInputKindOfMotionEventToolType(event.getToolType(0))
        straightenTracker.onDown(viewport.viewToSheet(ViewPoint(event.x, event.y)), event.x, event.y, event.eventTime)
        if (activeStraightenMode == InkStraightenMode.ON_HOLD) scheduleStraightenCheck()
    }

    /**
     * Feeds [event]'s own move into [straightenTracker], once the current stroke is still eligible;
     * once [straightenPreviewActive] instead resizes the shape [trySnapToShapeOnHold] already snapped
     * to, through [continueStraightenResize].
     */
    private fun continueStraightening(event: MotionEvent) {
        if (activeStraightenMode == InkStraightenMode.NEVER) return

        if (straightenPreviewActive) {
            continueStraightenResize(event)
            return
        }

        collectStraightenSamples(event)
        if (activeStraightenMode == InkStraightenMode.ON_HOLD) scheduleStraightenCheck()
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
        val style = straightenStyle()
        committedView.shapePreview = buildShapeInkStrokes(
            recognized.start, recognized.end, recognized.shape, style.colorArgb, style.widthSheetUnits, style.tip,
            currentDrawInputKind, recognized.vertices, style.tool
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
        if (currentStrokeId == null || activeStraightenMode != InkStraightenMode.ON_HOLD) return
        if (straightenTracker.isHeld(SystemClock.uptimeMillis())) trySnapToShapeOnHold()
    }

    /**
     * The [InkTool], tip, colour and width to build a straightened shape with, matching whichever
     * tool — [InkSurfaceTool.PEN] or [InkSurfaceTool.HIGHLIGHTER] — is drawing the stroke being
     * straightened right now, the same pairing [brushAndMetaForCurrentTool] uses for an ordinary
     * freehand stroke.
     */
    private fun straightenStyle(): StraightenStyle =
        if (tool == InkSurfaceTool.HIGHLIGHTER) {
            StraightenStyle(InkTool.HIGHLIGHTER, InkTip.BALLPOINT, highlighterColorArgb, highlighterWidthSheetUnits)
        } else {
            StraightenStyle(InkTool.PEN, penTip, penColorArgb, penWidthSheetUnits)
        }

    private data class StraightenStyle(val tool: InkTool, val tip: InkTip, val colorArgb: Int, val widthSheetUnits: Float)

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
        val style = straightenStyle()
        committedView.shapePreview = buildShapeInkStrokes(
            recognized.start, recognized.end, recognized.shape, style.colorArgb, style.widthSheetUnits, style.tip,
            currentDrawInputKind, recognized.vertices, style.tool
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
        val style = straightenStyle()
        val models = shapeModels(
            recognized.start, recognized.end, recognized.shape, style.colorArgb, style.widthSheetUnits, style.tip,
            currentDrawInputKind, recognized.vertices, style.tool
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
            notifyItemCount()
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
        removeVisible(newlyHitModels)
        notifyItemCount()
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
            notifyItemCount()
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
        notifyItemCount()
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
        notifyItemCount()
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
     * SHAPE tool's own drag, or the pen's or the highlighter's own for a straightened stroke
     * (`rail-spec.md` 2.2, FORMA panel, and ENDEREZAR): independent of each other so a THEME-coloured
     * shape and a THEME-coloured pen stroke each keep following their own choice. [vertices] is a
     * recognised [InkShape.TRIANGLE]'s own three real corners, empty for every other shape and for a
     * SHAPE-tool drag. [tool] is [InkTool.PEN] for the SHAPE tool's own drag and a straightened pen
     * stroke, [InkTool.HIGHLIGHTER] for a straightened highlighter stroke.
     */
    private fun buildShapeInkStrokes(
        start: SheetPoint,
        end: SheetPoint,
        shape: InkShape,
        colorArgb: Int,
        widthSheetUnits: Float,
        tip: InkTip,
        inputKind: InkInputKind,
        vertices: List<SheetPoint> = emptyList(),
        tool: InkTool = InkTool.PEN
    ): List<Stroke> =
        shapeModels(start, end, shape, colorArgb, widthSheetUnits, tip, inputKind, vertices, tool) { SHAPE_PREVIEW_SEQUENCE }
            .map { model -> toInkStroke(model, colors.themeInk) }

    /**
     * One [InkStroke] per polyline [shapeSamples] returns for the drag from [start] to [end], each an
     * [InkStroke] of [tool] — [InkTool.PEN] for the SHAPE tool's own drag and a straightened pen
     * stroke, [InkTool.HIGHLIGHTER] for a straightened highlighter stroke — in [colorArgb],
     * [widthSheetUnits] and [tip], with its own consecutive sample times ([shapeSampleTimesMillis], a
     * slow constant pen speed) and its own [InkStroke.sequence] from [sequenceFor], called once per
     * stroke so a multi-stroke shape — an arrow's shaft and head — still gets consecutive draw order.
     * [vertices] is forwarded to [shapeSamples] as a recognised [InkShape.TRIANGLE]'s own three real
     * corners.
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
        tool: InkTool = InkTool.PEN,
        sequenceFor: () -> Long
    ): List<InkStroke> =
        shapeSamples(shape, start, end, widthSheetUnits, vertices).map { polyline ->
            InkStroke(
                id = StrokeId(UUID.randomUUID().toString()),
                tool = tool,
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

        showModelsAsLive(models)

        if (!commitEdit(SheetEdit.AddStrokes(models))) removeUncommitted(models)
    }

    /**
     * Shows every model in [models] as a live, built stroke on screen, built inline on the UI thread
     * rather than through [InkMeshBuilder]: cheap enough for the handful of strokes a shape drag or a
     * [copySelection] ever produces at once, unlike a move or resize drag's own no-gap swap, which can
     * carry an arbitrarily large selection and always builds off the UI thread instead.
     */
    private fun showModelsAsLive(models: List<InkStroke>) {
        for (model in models) {
            val built = toInkStroke(model, colors.themeInk)
            builtCache[model.id] = built
            liveStrokes[model.id] = model
            committedView.putBuiltStroke(model, built)
        }
        notifyItemCount()
    }

    /** Takes strokes that were shown ahead of their commit back off the sheet once the writer refused them. */
    private fun removeUncommitted(models: List<InkStroke>) {
        val ids = models.map { it.id }

        for (id in ids) {
            liveStrokes.remove(id)
            builtCache.remove(id)
        }
        committedView.removeStrokes(ids)
        notifyItemCount()
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

    // region selecting

    /**
     * Starts a SELECT-tool gesture. Once a selection already exists and [acceptsEdits], the pointer's
     * down point is checked against it first — [selectionTouchTarget] — and a corner handle or the
     * selection's own body starts a move or resize drag instead of an ordinary tap/lasso/box
     * re-selection. Falls through to an ordinary selecting gesture whenever neither applies.
     */
    private fun startSelect(event: MotionEvent) {
        val viewPoint = ViewPoint(event.x, event.y)
        val point = viewport.viewToSheet(viewPoint)
        val boundsSheet = selectionBoundsSheet

        if (boundsSheet != null && selectedStrokeIds.isNotEmpty() && acceptsEdits) {
            val boundsView = viewport.sheetToView(boundsSheet)
            when (val target = selectionTouchTarget(viewPoint, boundsView, selectionHandleHitRadiusPx)) {
                is SelectionTouchTarget.Handle -> {
                    startSelectionEdit(SelectionEditKind.Resize(target.corner), point, boundsSheet)
                    return
                }
                SelectionTouchTarget.Body -> {
                    startSelectionEdit(SelectionEditKind.Move, point, boundsSheet)
                    return
                }
                SelectionTouchTarget.None -> Unit
            }
        }

        val session = SelectionGestureSession(touchSlopPx)
        session.onDown(point, event.x, event.y)
        selectionSession = session
    }

    private fun continueSelect(event: MotionEvent) {
        val editSession = selectionEditSession
        if (editSession != null) {
            editSession.onMove(viewport.viewToSheet(ViewPoint(event.x, event.y)))
            scheduleSelectionEditPreviewRebuild()
            return
        }

        val session = selectionSession ?: return
        session.onMove(viewport.viewToSheet(ViewPoint(event.x, event.y)), event.x, event.y)
        scheduleSelectPreviewRebuild()
    }

    /** Coalesces live lasso/box preview rebuilds to at most one per frame, the same way [scheduleShapePreviewRebuild] does for the SHAPE tool's own drag. */
    private fun scheduleSelectPreviewRebuild() {
        if (selectPreviewScheduled) return

        selectPreviewScheduled = true
        postOnAnimation {
            selectPreviewScheduled = false
            rebuildSelectPreview()
        }
    }

    private fun rebuildSelectPreview() {
        val session = selectionSession ?: return
        if (!session.isDragging) return

        when (selectMode) {
            PenSelectMode.LASSO -> committedView.selectionLassoPreview = session.lassoPoints
            PenSelectMode.BOX -> {
                val down = session.downPoint ?: return
                val current = session.currentPoint ?: return
                committedView.selectionBoxPreview = boundingRectOf(down, current)
            }
            PenSelectMode.TAP -> Unit
        }
    }

    /**
     * A gesture ends the moment it lifts: a completed tap, lasso or box drag replaces whatever was
     * selected before, exactly [PenSelectMode]'s own contract, including a tap or an empty lasso/box
     * that hits nothing, which clears the selection outright. Falls back to a tap regardless of
     * [selectMode] once [SelectionGestureSession.isDragging] never latched.
     */
    private fun finishSelect() {
        if (selectionEditSession != null) {
            finishSelectionEdit()
            return
        }

        val session = selectionSession ?: return
        selectionSession = null
        clearSelectPreview()

        val newSelection = if (!session.isDragging) {
            val point = session.downPoint ?: return
            strokeGroupAtTap(liveStrokes.values.toList(), point, currentSelectTapToleranceSheetUnits())
        } else {
            when (selectMode) {
                PenSelectMode.LASSO -> selectByLasso(liveStrokes.values.toList(), session.lassoPoints)
                PenSelectMode.BOX -> {
                    val down = session.downPoint
                    val current = session.currentPoint
                    if (down == null || current == null) emptySet() else selectByRectangle(liveStrokes.values.toList(), down, current)
                }
                PenSelectMode.TAP -> {
                    val point = session.downPoint
                    if (point == null) emptySet() else strokeGroupAtTap(liveStrokes.values.toList(), point, currentSelectTapToleranceSheetUnits())
                }
            }
        }

        setSelection(newSelection)
    }

    private fun cancelSelect() {
        if (selectionEditSession != null) {
            cancelSelectionEdit()
            return
        }

        selectionSession = null
        clearSelectPreview()
    }

    private fun clearSelectPreview() {
        selectPreviewScheduled = false
        committedView.selectionLassoPreview = emptyList()
        committedView.selectionBoxPreview = null
    }

    // region selection editing (move and resize)

    /**
     * Snapshots the current selection's own models — in ascending [InkStroke.sequence] order, so a
     * later [translateStrokes]/[scaleStrokes] call keeps the originals' own relative z-order — and
     * starts showing them dragged live through [InkCommittedStrokesView.SelectionDragPreview], hidden
     * from [InkCommittedStrokesView]'s own ordinary committed-stroke drawing for as long as the drag,
     * and its own no-gap mesh swap once it lifts, lasts.
     */
    private fun startSelectionEdit(kind: SelectionEditKind, point: SheetPoint, boundsSheet: SheetRect) {
        // A previous drag's own no-gap swap can still be waiting on its own meshes to finish building
        // when a fresh one starts: its own originals are already gone from `liveStrokes`, so evicting
        // them from `committedView` now, rather than waiting for that stale build to finish, never
        // shows them again — the fresh preview below is about to hide a different set of ids instead.
        committedView.selectionDragPreview?.let { leftover -> committedView.removeStrokes(leftover.hiddenIds) }

        selectionEditBaseModels = selectedStrokeIds.mapNotNull { liveStrokes[it] }.sortedBy { it.sequence }
        selectionEditSession = SelectionEditSession(kind, boundsSheet, point)

        val builtStrokes = selectionEditBaseModels.map { model -> builtCache[model.id] ?: toInkStroke(model, colors.themeInk) }
        committedView.selectionDragPreview = InkCommittedStrokesView.SelectionDragPreview(
            hiddenIds = selectionEditBaseModels.map { it.id }.toSet(),
            strokes = builtStrokes,
            transform = Matrix()
        )
        listener?.onSelectionEditingChanged(true)
    }

    /** Coalesces live move/resize preview rebuilds to at most one per frame, the same way [scheduleShapePreviewRebuild] does for the SHAPE tool's own drag. */
    private fun scheduleSelectionEditPreviewRebuild() {
        if (selectionEditPreviewScheduled) return

        selectionEditPreviewScheduled = true
        postOnAnimation {
            selectionEditPreviewScheduled = false
            rebuildSelectionEditPreview()
        }
    }

    private fun rebuildSelectionEditPreview() {
        val session = selectionEditSession ?: return
        val preview = committedView.selectionDragPreview ?: return

        committedView.selectionDragPreview = preview.copy(transform = selectionEditViewTransform(session))
        committedView.selectionOutline = session.previewBounds()
    }

    /**
     * The view-space [Matrix] that reproduces [session]'s own sheet-space translation or scale: since
     * [SheetViewport.scale] applies the same factor to both axes, a sheet-space affine transform maps
     * directly to the equivalent view-space one, with no extra work beyond converting its own anchor or
     * offset from sheet units to view pixels.
     */
    private fun selectionEditViewTransform(session: SelectionEditSession): Matrix {
        val matrix = Matrix()

        when (session.kind) {
            SelectionEditKind.Move -> {
                val delta = session.translation
                matrix.postTranslate(delta.x * viewport.scale, delta.y * viewport.scale)
            }
            is SelectionEditKind.Resize -> {
                val scale = session.resizeScale()
                val anchorView = viewport.sheetToView(scale.anchor)
                matrix.postScale(scale.scaleX, scale.scaleY, anchorView.x, anchorView.y)
            }
        }

        return matrix
    }

    /**
     * Ends a move or resize drag: a drag that never moved or resized anything commits nothing
     * ([SelectionEditSession.hasChanged]), otherwise its own translated or scaled copies replace the
     * originals as one [SheetEdit.ReplaceStrokes] through [commitSelectionReplace].
     */
    private fun finishSelectionEdit() {
        val session = selectionEditSession ?: return
        selectionEditSession = null
        listener?.onSelectionEditingChanged(false)

        if (!session.hasChanged()) {
            committedView.selectionDragPreview = null
            committedView.selectionOutline = selectionBoundsSheet
            return
        }

        val removed = selectionEditBaseModels
        val newId = { StrokeId(UUID.randomUUID().toString()) }
        val added = when (session.kind) {
            SelectionEditKind.Move -> {
                val delta = session.translation
                translateStrokes(removed, delta.x, delta.y, newId, openSheet::nextSequence)
            }
            is SelectionEditKind.Resize -> {
                val scale = session.resizeScale()
                scaleStrokes(removed, scale.anchor, scale.scaleX, scale.scaleY, newId, openSheet::nextSequence)
            }
        }

        commitSelectionReplace(removed, added)
    }

    private fun cancelSelectionEdit() {
        selectionEditSession = null
        selectionEditBaseModels = emptyList()
        listener?.onSelectionEditingChanged(false)
        committedView.selectionDragPreview = null
        committedView.selectionOutline = selectionBoundsSheet
    }

    /**
     * Commits [removed] replaced by [added] and swaps the screen over to the real thing with no
     * visible gap: [InkCommittedStrokesView.selectionDragPreview] keeps showing [removed]'s own
     * already-built meshes, transformed to [added]'s own final position or size, until
     * [InkMeshBuilder] finishes building [added]'s own meshes off the UI thread — the same builder
     * [scheduleMeshBuild] uses for a freshly opened sheet — at which point every batch is shown and the
     * preview is cleared in the same frame. A refused commit leaves every model and every pixel exactly
     * as it stood before the drag: neither [liveStrokes] nor [builtCache] nor the selection is touched
     * unless the writer accepted the edit.
     */
    private fun commitSelectionReplace(removed: List<InkStroke>, added: List<InkStroke>) {
        val accepted = commitEdit(SheetEdit.ReplaceStrokes(removed = removed, added = added))
        if (!accepted) {
            committedView.selectionDragPreview = null
            committedView.selectionOutline = selectionBoundsSheet
            return
        }

        for (model in removed) {
            liveStrokes.remove(model.id)
            builtCache.remove(model.id)
        }
        for (model in added) liveStrokes[model.id] = model
        notifyItemCount()

        setSelection(added.map { it.id }.toSet())

        val generation = ++selectionEditGeneration
        val removedIds = removed.map { it.id }
        var strokesStillBuilding = added.size
        val center = viewport.viewToSheet(ViewPoint(viewport.viewWidthPx / 2f, viewport.viewHeightPx / 2f))

        meshBuilder.build(added, center, colors.themeInk) { batch ->
            mainPost {
                if (generation != selectionEditGeneration) return@mainPost

                for ((model, built) in stillLive(batch) { liveStrokes.containsKey(it.id) }) {
                    builtCache[model.id] = built
                    committedView.putBuiltStroke(model, built)
                }

                strokesStillBuilding -= batch.size
                if (strokesStillBuilding <= 0) {
                    committedView.removeStrokes(removedIds)
                    committedView.selectionDragPreview = null
                }
            }
        }
    }

    /** The current selection's own strokes, in z-order (ascending [InkStroke.sequence]); empty when nothing is selected. */
    fun selectedStrokesInZOrder(): List<InkStroke> = selectedStrokeIds.mapNotNull { liveStrokes[it] }.sortedBy { it.sequence }

    /**
     * Copies the current selection, offset [SELECTION_COPY_OFFSET_MM] right and down, as one
     * [SheetEdit.AddStrokes]; the copy becomes the new selection. A no-op with nothing selected or once
     * the surface no longer [acceptsEdits].
     */
    fun copySelection() {
        if (!acceptsEdits || selectedStrokeIds.isEmpty()) return

        val models = selectedStrokesInZOrder()
        if (models.isEmpty()) return

        val offset = mmToSheetUnits(SELECTION_COPY_OFFSET_MM)
        val copies = translateStrokes(models, offset, offset, { StrokeId(UUID.randomUUID().toString()) }, openSheet::nextSequence)

        showModelsAsLive(copies)

        if (commitEdit(SheetEdit.AddStrokes(copies))) {
            setSelection(copies.map { it.id }.toSet())
        } else {
            removeUncommitted(copies)
        }
    }

    /**
     * Removes the current selection as one [SheetEdit.RemoveStrokes] and clears it; a refused commit
     * puts every stroke back exactly as [cancelWholeStrokeErase] already does for a whole-stroke erase.
     * A no-op with nothing selected or once the surface no longer [acceptsEdits].
     */
    fun deleteSelection() {
        if (!acceptsEdits || selectedStrokeIds.isEmpty()) return

        val models = selectedStrokesInZOrder()
        if (models.isEmpty()) return

        clearSelectionInternal()
        removeVisible(models)
        notifyItemCount()

        if (!commitEdit(SheetEdit.RemoveStrokes(models))) {
            for (model in models) {
                liveStrokes[model.id] = model
                builtCache[model.id]?.let { built -> committedView.putBuiltStroke(model, built) }
            }
            notifyItemCount()
        }
    }

    // endregion

    private fun boundingRectOf(a: SheetPoint, b: SheetPoint): SheetRect = SheetRect(
        left = minOf(a.x, b.x),
        top = minOf(a.y, b.y),
        right = maxOf(a.x, b.x),
        bottom = maxOf(a.y, b.y)
    )

    /**
     * A tap's own hit tolerance: the platform's touch slop, converted to sheet units at the live
     * viewport, the same way [currentEraserRadiusSheetUnits] is derived from the eraser's own size —
     * a finger-sized allowance rather than the pointer's own exact, sub-pixel point.
     */
    private fun currentSelectTapToleranceSheetUnits(): Float = viewport.lengthToSheetUnits(touchSlopPx)

    /** Replaces the current selection with [ids], rebuilding its own bounding box and notifying [listener]. */
    private fun setSelection(ids: Set<StrokeId>) {
        selectedStrokeIds = ids
        selectionBoundsSheet = selectionBounds(ids.mapNotNull { liveStrokes[it] })
        committedView.selectionOutline = selectionBoundsSheet
        notifySelectionChanged()
    }

    /** Clears the current selection, if any; does nothing when nothing is selected. */
    private fun clearSelectionInternal() {
        if (selectedStrokeIds.isEmpty()) return
        selectedStrokeIds = emptySet()
        selectionBoundsSheet = null
        committedView.selectionOutline = null
        notifySelectionChanged()
    }

    /** Drops [removedIds] from the current selection, if any of them were part of it. */
    private fun pruneSelection(removedIds: Collection<StrokeId>) {
        if (selectedStrokeIds.isEmpty()) return
        val remaining = selectedStrokeIds - removedIds.toSet()
        if (remaining != selectedStrokeIds) setSelection(remaining)
    }

    private fun notifySelectionChanged() {
        val boundsViewPx = selectionBoundsSheet?.let { viewport.sheetToView(it) }
        listener?.onSelectionChanged(selectedStrokeIds, boundsViewPx)
    }

    /** Clears the current selection from outside a gesture, for a host that wants to dismiss it, e.g. after acting on its own selection menu. */
    fun clearSelection() {
        clearSelectionInternal()
    }

    /** Sets whether a selecting gesture decides by tap, lasso or box; takes effect on the next selecting gesture, never mid-gesture. */
    fun setSelectMode(newMode: PenSelectMode) {
        selectMode = newMode
    }

    // endregion

    // region text

    /** Records a TEXT-tool tap's own down point; the actual placement or edit decision waits for [finishText], since a second pointer arriving in between cancels the tap into a pan/zoom instead. */
    private fun startText(event: MotionEvent) {
        if (!acceptsEdits) return
        textTapDownPoint = viewport.viewToSheet(ViewPoint(event.x, event.y))
    }

    /**
     * Ends a TEXT-tool tap: any session already open is committed first — this tap already reached
     * here only because [onInterceptTouchEvent] found it outside the open editor's own bounds, so it
     * always means "tap elsewhere" — then the tap edits whichever live text box it landed on, topmost
     * by [SheetTextBox.bounds] and [SheetItem.sequence]. On empty paper it places a brand-new box only
     * when nothing was being edited: a tap that ends an edit just ends it, so finishing a box never
     * leaves a stray empty editor wherever the finger happened to land.
     */
    private fun finishText() {
        val point = textTapDownPoint
        textTapDownPoint = null
        if (point == null || !acceptsEdits) return

        val wasEditing = textEditingSession.isOpen
        commitTextEditingIfOpen()

        val tolerance = currentSelectTapToleranceSheetUnits()
        val existing = liveTextBoxes.values
            .filter { it.bounds.inflate(tolerance).contains(point) }
            .maxByOrNull { it.sequence }

        when {
            existing != null -> openTextEditing(existing)
            !wasEditing -> openNewTextEditing(point)
        }
    }

    private fun openTextEditing(box: SheetTextBox) {
        hiddenTextBoxId = box.id
        refreshTextBoxesOnCommittedView()

        val placement = TextEditingPlacement(box.topLeft, box.widthSheetUnits, box.font, box.sizePt, box.style, box.colorArgb)
        textEditingSession.open(box, placement, viewport, resolveTextColor(box.colorArgb, colors.themeInk))
        listener?.onTextEditingChanged(true)
    }

    /**
     * A brand-new box's own left edge and width come from [newTextBoxGeometry], its own top from
     * [snappedTextBoxTop]: see those functions for the exact rules. Styled and coloured from this
     * surface's own current [textFont], [textSizePt], [textStyle] and [textColorArgb], the text
     * panel's own live settings.
     */
    private fun openNewTextEditing(tapPoint: SheetPoint) {
        val geometry = newTextBoxGeometry(
            tapXSheetUnits = tapPoint.x,
            rightMarginSheetUnits = mmToSheetUnits(NEW_TEXT_BOX_RIGHT_MARGIN_MM),
            minWidthSheetUnits = mmToSheetUnits(NEW_TEXT_BOX_MIN_WIDTH_MM)
        )
        val topLeft = SheetPoint(geometry.left, snappedTextBoxTop(tapPoint.y))
        val placement = TextEditingPlacement(topLeft, geometry.widthSheetUnits, textFont, textSizePt, textStyle, textColorArgb)

        textEditingSession.open(null, placement, viewport, resolveTextColor(textColorArgb, colors.themeInk))
        listener?.onTextEditingChanged(true)
    }

    /**
     * Ends whatever [textEditingSession] holds, if anything, applying its own [TextCommitDecision] the
     * same way every other edit in this class is committed: shown live first when the decision adds or
     * replaces a box, then handed to the writer, with a refusal leaving [liveTextBoxes] exactly as it
     * was before this call. A no-op with no session open.
     */
    fun commitTextEditingIfOpen() {
        if (!textEditingSession.isOpen) return

        val edit = textEditingSession.commit(newId = { StrokeId(UUID.randomUUID().toString()) }, newSequence = openSheet::nextSequence)
        hiddenTextBoxId = null
        listener?.onTextEditingChanged(false)

        if (edit == null) {
            refreshTextBoxesOnCommittedView()
            return
        }

        val removedBoxes = edit.removed.filterIsInstance<SheetItem.Text>().map { it.textBox }
        val addedBoxes = edit.added.filterIsInstance<SheetItem.Text>().map { it.textBox }

        if (!commitEdit(edit)) {
            refreshTextBoxesOnCommittedView()
            return
        }

        for (box in removedBoxes) liveTextBoxes.remove(box.id)
        for (box in addedBoxes) liveTextBoxes[box.id] = box
        notifyItemCount()
        refreshTextBoxesOnCommittedView()
    }

    private fun addVisibleText(models: List<SheetTextBox>) {
        for (model in models) liveTextBoxes[model.id] = model
        refreshTextBoxesOnCommittedView()
    }

    private fun removeVisibleText(models: List<SheetTextBox>) {
        val ids = models.map { it.id }
        for (id in ids) liveTextBoxes.remove(id)
        if (hiddenTextBoxId in ids) hiddenTextBoxId = null
        refreshTextBoxesOnCommittedView()
    }

    private fun refreshTextBoxesOnCommittedView() {
        val hidden = hiddenTextBoxId
        committedView.textBoxes = if (hidden == null) liveTextBoxes.values.toList() else liveTextBoxes.values.filter { it.id != hidden }
    }

    /**
     * [update] is called on every recomposition of the host that wires this surface to
     * [PenSettings][com.folium.reader.ink.PenSettings], whether or not the panel's own text attributes
     * actually changed since the last call: each setter below only pushes a live update into
     * [textEditingSession] when its own value actually moves, so opening an existing box whose own
     * attributes differ from the panel's last remembered choice is never immediately overwritten by
     * that same, unchanged choice on the very next recomposition.
     */
    fun setTextFont(newFont: SheetTextFont) {
        if (textFont == newFont) return
        textFont = newFont
        applyLiveTextAttributes()
    }

    fun setTextSizePt(newSizePt: Float) {
        if (textSizePt == newSizePt) return
        textSizePt = newSizePt
        applyLiveTextAttributes()
    }

    fun setTextStyle(newStyle: SheetTextStyle) {
        if (textStyle == newStyle) return
        textStyle = newStyle
        applyLiveTextAttributes()
    }

    fun setTextColorArgb(newColorArgb: Int) {
        if (textColorArgb == newColorArgb) return
        textColorArgb = newColorArgb
        applyLiveTextAttributes()
    }

    /**
     * Pushes this surface's own current [textFont], [textSizePt], [textStyle] and [textColorArgb]
     * into [textEditingSession] while it is open, so the panel's own live changes reach whichever box
     * is being placed or edited immediately, rather than only the next box. A no-op while no session
     * is open: [openNewTextEditing] reads these same fields itself once one starts.
     */
    private fun applyLiveTextAttributes() {
        if (!textEditingSession.isOpen) return
        textEditingSession.updateAttributes(
            font = textFont,
            sizePt = textSizePt,
            style = textStyle,
            colorArgb = textColorArgb,
            displayColorArgb = resolveTextColor(textColorArgb, colors.themeInk),
            viewport = viewport
        )
    }

    /**
     * Scrolls the sheet up just far enough to keep the open editor's own caret line clear of the
     * keyboard, once [insets] reports a non-zero IME inset: the manifest sets no
     * `windowSoftInputMode` and nothing else on this surface reacts to the keyboard, so this is the
     * only place that does. Does nothing once the editor's own bottom edge already sits above the
     * keyboard, and restores nothing of its own once the keyboard closes — [SheetViewport]'s own pan
     * clamp is all that keeps the sheet in bounds after that.
     */
    private fun handleImeInsets(insets: WindowInsetsCompat) {
        if (!textEditingSession.isOpen) return

        val imeBottomPx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        if (imeBottomPx <= 0) return

        val editorBottomPx = textEditingSession.boundsViewPx()?.bottom ?: return
        val visibleBottomPx = viewport.viewHeightPx - imeBottomPx
        if (editorBottomPx <= visibleBottomPx) return

        val marginPx = TEXT_EDITOR_IME_MARGIN_DP * resources.displayMetrics.density
        val dyPx = editorBottomPx - visibleBottomPx + marginPx

        viewport = viewport.pannedBy(0f, dyPx)
        committedView.viewport = viewport
        listener?.onViewportChanged(viewport)
        textEditingSession.reposition(viewport)
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
        if (selectedStrokeIds.isNotEmpty()) notifySelectionChanged()
        if (textEditingSession.isOpen) textEditingSession.reposition(viewport)
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
        if (selectedStrokeIds.isNotEmpty()) notifySelectionChanged()
        if (textEditingSession.isOpen) textEditingSession.reposition(viewport)
    }

    /** Resets the zoom to [SheetViewport.MIN_ZOOM] — the sheet's nominal width filling the view — keeping the current top of the view. */
    fun fitWidth() {
        viewport = viewport.fittedToWidth()
        committedView.viewport = viewport
        listener?.onViewportChanged(viewport)
        if (selectedStrokeIds.isNotEmpty()) notifySelectionChanged()
        if (textEditingSession.isOpen) textEditingSession.reposition(viewport)
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
        commitTextEditingIfOpen()

        val edit = committer.undo() ?: return
        clearSelectionInternal()
        applyVisible(edit)
        refreshContentBottom()
        listener?.onHistoryChanged(committer.canUndo, committer.canRedo)
        notifyItemCount()
    }

    fun redo() {
        if (!acceptsEdits) return
        commitTextEditingIfOpen()

        val edit = committer.redo() ?: return
        clearSelectionInternal()
        applyVisible(edit)
        refreshContentBottom()
        listener?.onHistoryChanged(committer.canUndo, committer.canRedo)
        notifyItemCount()
    }

    /**
     * Removes every live stroke and text box on the sheet as one [SheetEdit.ReplaceItems] with an
     * empty [SheetEdit.ReplaceItems.added], so both kinds are persisted and undone with a single undo —
     * the eraser panel's own "clear all" promises exactly this, one edit regardless of what the sheet
     * holds. Does nothing, and returns `true`, when the sheet already has nothing to clear; returns
     * `false` without touching anything when the surface no longer accepts edits or the writer refuses
     * the removal. Any open [textEditingSession] is committed first, so a box mid-edit is cleared too
     * rather than surviving the sweep in the editor alone.
     */
    fun clearAll(): Boolean {
        if (!acceptsEdits) return false
        commitTextEditingIfOpen()

        val strokeModels = liveStrokes.values.toList()
        val textModels = liveTextBoxes.values.toList()
        if (strokeModels.isEmpty() && textModels.isEmpty()) return true

        clearSelectionInternal()
        removeVisible(strokeModels)
        removeVisibleText(textModels)
        notifyItemCount()

        val allItems = strokeModels.map(SheetItem::Stroke) + textModels.map(SheetItem::Text)
        val accepted = commitEdit(SheetEdit.ReplaceItems(removed = allItems, added = emptyList()))
        if (!accepted) {
            for (model in strokeModels) {
                liveStrokes[model.id] = model
                builtCache[model.id]?.let { built -> committedView.putBuiltStroke(model, built) }
            }
            addVisibleText(textModels)
            notifyItemCount()
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
            is SheetEdit.ReplaceItems -> {
                removeVisible(edit.removed.filterIsInstance<SheetItem.Stroke>().map { it.stroke })
                removeVisibleText(edit.removed.filterIsInstance<SheetItem.Text>().map { it.textBox })
                addVisible(edit.added.filterIsInstance<SheetItem.Stroke>().map { it.stroke })
                addVisibleText(edit.added.filterIsInstance<SheetItem.Text>().map { it.textBox })
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
        pruneSelection(ids)
    }

    private fun refreshContentBottom() {
        val contentBottom = sheetItemContentBounds(currentItems())?.bottom ?: 0f
        viewport = viewport.withContentBottom(contentBottom)
        committedView.viewport = viewport
    }

    fun setTool(newTool: InkSurfaceTool) {
        if (newTool == tool) return
        commitTextEditingIfOpen()
        tool = newTool
        cancelStraightenCheck()
        clearSelectionInternal()
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
     * Sets whether the highlighter tool straightens a recognised stroke into a shape, and when,
     * independent of the pen's own [setStraightenMode]. See [setStraightenMode] for why cancelling
     * [straightenCheckRunnable] rather than the stroke in progress itself is enough.
     */
    fun setHighlighterStraightenMode(newMode: InkStraightenMode) {
        if (newMode == highlighterStraightenMode) return
        highlighterStraightenMode = newMode
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
        commitTextEditingIfOpen()
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
