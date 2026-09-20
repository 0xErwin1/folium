package com.folium.reader.ink

import android.content.Context
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import com.folium.reader.core.ink.InkInputKind
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTip
import com.folium.reader.core.ink.OpenSheet
import com.folium.reader.core.ink.SheetEdit
import com.folium.reader.core.ink.SheetEditHistory
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetTemplate
import com.folium.reader.core.ink.StrokeId
import com.folium.reader.core.ink.sheetContentBounds
import com.folium.reader.core.ink.strokesHitBy
import java.util.UUID

private const val ERASER_RADIUS_VIEW_PX: Float = 12f
private const val FRONT_BUFFER_PROBE_WIDTH: Int = 800
private const val FRONT_BUFFER_PROBE_HEIGHT: Int = 1280
private const val CLOSE_DRAIN_TIMEOUT_MILLIS: Long = 5_000L

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
    private var penColorArgb = 0xFF000000.toInt()
    private var penWidthSheetUnits = InkPenWidths.MEDIUM_SHEET_UNITS

    private var currentStrokeId: InProgressStrokeId? = null
    private var currentPointerId: Int = -1
    private val pendingStrokes = PendingStrokes<InProgressStrokeId>()

    private val eraserPath = mutableListOf<SheetPoint>()
    private val eraserRemovedModels = mutableListOf<InkStroke>()

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
        meshBuilder.build(liveStrokes.values.toList(), center) { batch ->
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
            InkGesture.PAN_ZOOM -> continuePanZoom(event)
            InkGesture.IGNORE -> Unit
        }
    }

    private fun handleLastPointerUp(event: MotionEvent) {
        when (gestureArbiter.gesture) {
            InkGesture.DRAW -> finishDraw(event)
            InkGesture.ERASE -> finishErase()
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
            InkGesture.DRAW -> currentStrokeId?.let { id ->
                inProgressView.cancelStroke(id, event)
                pendingStrokes.discard(id)
                currentStrokeId = null
            }
            InkGesture.ERASE -> cancelErase()
            else -> Unit
        }
    }

    // endregion

    // region drawing

    private fun startDraw(event: MotionEvent, pointerId: Int) {
        if (!acceptsEdits) return

        currentPointerId = pointerId
        val brush = brushFor(penTip, penColorArgb, penWidthSheetUnits)
        val transform = motionEventToStrokeSpaceTransform(viewport)
        val strokeId = inProgressView.startStroke(event, pointerId, brush, motionEventToWorldTransform = transform)

        pendingStrokes.register(strokeId, PendingStrokeMeta(penTip, penColorArgb, penWidthSheetUnits))
        currentStrokeId = strokeId
    }

    private fun continueDraw(event: MotionEvent) {
        val strokeId = currentStrokeId ?: return
        predictor.record(event)
        inProgressView.addToStroke(event, currentPointerId, strokeId, predictor.predict())
    }

    private fun finishDraw(event: MotionEvent) {
        val strokeId = currentStrokeId ?: return
        inProgressView.finishStroke(event, currentPointerId, strokeId)
        currentStrokeId = null
    }

    private inner class FinishedStrokesListener : InProgressStrokesFinishedListener {
        override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
            val newModels = strokes.map { (strokeId, built) ->
                val pending = pendingStrokes.resolve(strokeId) { metaFromBrush(built) }
                val model = fromAndroidxStroke(built, StrokeId(UUID.randomUUID().toString()), openSheet.nextSequence(), pending.tip, pending.colorArgb, pending.widthSheetUnits)
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
        eraserPath.clear()
        eraserRemovedModels.clear()
        eraserPath += viewport.viewToSheet(ViewPoint(event.x, event.y))
        applyEraserHits()
    }

    private fun continueErase(event: MotionEvent) {
        if (eraserPath.isEmpty()) return
        eraserPath += viewport.viewToSheet(ViewPoint(event.x, event.y))
        applyEraserHits()
    }

    private fun applyEraserHits() {
        val radiusSheetUnits = viewport.lengthToSheetUnits(ERASER_RADIUS_VIEW_PX)
        val alreadyRemovedIds = eraserRemovedModels.mapTo(mutableSetOf()) { it.id }
        val hitIds = strokesHitBy(eraserPath, radiusSheetUnits, liveStrokes.values.toList())
        val newlyHitIds = hitIds - alreadyRemovedIds
        if (newlyHitIds.isEmpty()) return

        val newlyHitModels = newlyHitIds.mapNotNull { liveStrokes[it] }
        eraserRemovedModels += newlyHitModels
        for (model in newlyHitModels) liveStrokes.remove(model.id)
        committedView.removeStrokes(newlyHitIds)
        listener?.onStrokeCountChanged(liveStrokes.size)
    }

    private fun finishErase() {
        val committed = eraserRemovedModels.isEmpty() || commitEdit(SheetEdit.RemoveStrokes(eraserRemovedModels.toList()))
        if (!committed) {
            cancelErase()
            return
        }

        eraserPath.clear()
        eraserRemovedModels.clear()
    }

    private fun cancelErase() {
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
     * The settings to record a finished stroke under when its start was not registered here, read
     * back from the brush it was actually drawn with.
     */
    private fun metaFromBrush(built: Stroke): PendingStrokeMeta = PendingStrokeMeta(
        tip = penTip,
        colorArgb = built.brush.colorIntArgb,
        widthSheetUnits = StrokeSpace.strokeSpaceToSheet(built.brush.size)
    )

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

    private fun applyVisible(edit: SheetEdit) {
        when (edit) {
            is SheetEdit.AddStrokes -> for (model in edit.strokes) {
                liveStrokes[model.id] = model
                builtCache[model.id]?.let { built -> committedView.putBuiltStroke(model, built) }
            }
            is SheetEdit.RemoveStrokes -> {
                val ids = edit.strokes.map { it.id }
                for (id in ids) liveStrokes.remove(id)
                committedView.removeStrokes(ids)
            }
        }
    }

    private fun refreshContentBottom() {
        val contentBottom = sheetContentBounds(liveStrokes.values.toList())?.bottom ?: 0f
        viewport = viewport.withContentBottom(contentBottom)
        committedView.viewport = viewport
    }

    fun setTool(newTool: InkSurfaceTool) {
        tool = newTool
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

    fun setColors(colors: InkSurfaceColors) {
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
