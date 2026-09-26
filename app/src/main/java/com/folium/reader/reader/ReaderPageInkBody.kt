package com.folium.reader.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.folium.reader.R
import com.folium.reader.core.ink.OpenPageInk
import com.folium.reader.core.ink.StrokeId
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.ink.InkDrawingSurface
import com.folium.reader.ink.InkPersistenceBanner
import com.folium.reader.ink.InkSelectionMenu
import com.folium.reader.ink.InkSurfaceColors
import com.folium.reader.ink.InkSurfaceListener
import com.folium.reader.ink.InkSurfaceMode
import com.folium.reader.ink.InkSurfaceUiState
import com.folium.reader.ink.PanZoomStep
import com.folium.reader.ink.PenSettings
import com.folium.reader.ink.SelectedTextAttributes
import com.folium.reader.ink.SheetPaneHistory
import com.folium.reader.ink.SheetSelectorEvent
import com.folium.reader.ink.SheetTools
import com.folium.reader.ink.SheetViewport
import com.folium.reader.ink.ViewRect
import com.folium.reader.ink.storedArgb
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/** Test tags a UI test finds a reader page's live drawing surface by. */
object ReaderPageInkTestTags {
    fun surface(page: Int) = "reader-page-ink-surface-$page"
    fun persistenceBanner(page: Int) = "reader-page-ink-persistence-banner-$page"
}

/**
 * What [ReaderPageInkBody] asks of the reader for one page it draws on: the page's writers through
 * [lease] and their [states], the page's size in points through [pageInfo] (blocking, run on [work]),
 * the rail's [tools] and the top bar's [history], and the reader itself for pan and zoom through
 * [onIntents] and [onFitRequested]. [onMounted] reports each page whose surface enters or leaves.
 */
internal class ReaderPageInkAccess(
    val lease: PageInkLease,
    val states: Map<Int, PageInkState>,
    val pageInfo: (Int) -> PageInfo?,
    val work: Executor,
    val tools: SheetTools,
    val history: SheetPaneHistory,
    val penSettings: PenSettings,
    val onIntents: (List<GestureIntent>) -> Unit,
    val onFitRequested: () -> Unit,
    val onMounted: (Int, Boolean) -> Unit
)

/**
 * The live drawing surface over one book page's whole cell while writing, laid out by [layoutIn]
 * exactly as the page under it, once the lease holds [page]'s writer open and its size is known;
 * nothing before then, while the page's committed ink is still drawn from the reader's cache.
 *
 * The surface is keyed on its [OpenPageInk], with the lease's release declared before the surface.
 * Compose forgets a removed group's remembered objects in the reverse of the order it remembered
 * them, so the surface's own disposal — which flushes and closes it — always runs before
 * [PageInkLease.release] closes the writer; the same order [ReaderSheetBody] keeps for a sheet.
 *
 * A page beside a sheet is not [zoomable], since its unit takes no pinch: its surface's pan and zoom
 * requests are dropped.
 */
@Composable
internal fun ReaderPageInkBody(
    page: Int,
    layoutIn: (ReaderViewport) -> ViewportLayout,
    zoomable: Boolean,
    access: ReaderPageInkAccess
) {
    val info by produceState<PageInfo?>(null, page) {
        value = withContext(access.work.asCoroutineDispatcher()) { access.pageInfo(page) }
    }

    val live = (access.states[page] as? PageInkState.Live)?.ink ?: return
    val size = info ?: return

    key(live) {
        DisposableEffect(Unit) {
            access.lease.attach(live)
            onDispose { access.lease.release(live) }
        }

        PageInkSurface(
            ink = live,
            mode = pageInkMode(size),
            page = page,
            layoutIn = layoutIn,
            zoomable = zoomable,
            access = access
        )
    }
}

/**
 * One page's [InkDrawingSurface], pinned to the page through [InkDrawingSurface.setPageFrame] on every
 * layout, driven by the rail's tools and the top bar's history whenever it is the surface last drawn
 * on — or the first one mounted while neither is bound — handing both back to a sheet beside it when
 * it goes away while bound ([SheetPaneHistory.release]), and drawing THEME ink in
 * [PAGE_INK_THEME_INK_ARGB], exactly as the page's committed ink is drawn, so a stroke never changes
 * colour when its page goes live or back.
 *
 * Over the surface it draws what a sheet pane draws over its own: the selection menu, anchored to the
 * selection within this page's cell, the save-failure banner once the writer refuses an edit, and
 * back closing an open text editor — committing it, as leaving the screen does — before it leaves
 * writing. A surface with a selection claims the rail, so the menu's text panel acts on this page.
 */
@Composable
private fun PageInkSurface(
    ink: OpenPageInk,
    mode: InkSurfaceMode.Page,
    page: Int,
    layoutIn: (ReaderViewport) -> ViewportLayout,
    zoomable: Boolean,
    access: ReaderPageInkAccess
) {
    var surface by remember { mutableStateOf<InkDrawingSurface?>(null) }
    val binding = remember { SurfaceBinding() }
    val tools = access.tools
    val history = access.history
    val latestTools by rememberUpdatedState(tools)
    val latestHistory by rememberUpdatedState(history)
    val latestZoomable by rememberUpdatedState(zoomable)
    val latestAccess by rememberUpdatedState(access)
    val surfaceState = remember { InkSurfaceUiState() }

    BackHandler(enabled = surfaceState.textEditing) { surface?.commitTextEditingIfOpen() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) surface?.commitTextEditingIfOpen()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val colors = InkSurfaceColors(
        paper = MaterialTheme.colorScheme.surface.toArgb(),
        field = MaterialTheme.colorScheme.surfaceVariant.toArgb(),
        rule = MaterialTheme.colorScheme.outlineVariant.toArgb(),
        themeInk = PAGE_INK_THEME_INK_ARGB
    )

    DisposableEffect(Unit) {
        access.onMounted(page, true)

        onDispose {
            latestAccess.onMounted(page, false)
            surface?.let { view ->
                view.flushAndWait(PAGE_INK_CLOSE_TIMEOUT_MILLIS)
                view.close()
            }
        }
    }

    DisposableEffect(tools, history, surface) {
        val bound = surface
        if (bound != null && tools.surface == null) binding.claim(bound, tools, history)

        onDispose {
            if (bound != null) {
                if (tools.surface === bound) tools.bind(null)
                history.release(bound)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = ReaderViewport.of(constraints.maxWidth, constraints.maxHeight) ?: return@BoxWithConstraints
        val layout = layoutIn(viewport)
        val latestLayout by rememberUpdatedState(layout)

        AndroidView(
            modifier = Modifier.fillMaxSize().testTag(ReaderPageInkTestTags.surface(page)),
            factory = { context ->
                InkDrawingSurface(context, ink, mode = mode).apply {
                    setColors(colors)
                    val view = this

                    listener = object : InkSurfaceListener {
                        override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
                            binding.canUndo = canUndo
                            binding.canRedo = canRedo
                            latestHistory.report(view, canUndo, canRedo)
                        }

                        override fun onViewportChanged(viewport: SheetViewport) {
                            binding.viewport = viewport
                            if (latestTools.surface === view) latestTools.viewport = viewport
                        }

                        override fun onStrokeCountChanged(count: Int) {
                            binding.strokeCount = count
                            if (latestTools.surface === view) latestTools.strokeCount = count
                        }

                        override fun onStrokeStarted() {
                            latestTools.reduce(SheetSelectorEvent.StrokeStarted)
                            binding.claim(view, latestTools, latestHistory)
                        }

                        override fun onPersistenceFailure(error: Throwable) {
                            surfaceState.onPersistenceFailure()
                        }

                        override fun onSelectionChanged(strokeIds: Set<StrokeId>, boundsViewPx: ViewRect?, hasTextBoxes: Boolean) {
                            surfaceState.onSelectionChanged(strokeIds, boundsViewPx, hasTextBoxes)
                            if (strokeIds.isNotEmpty()) binding.claim(view, latestTools, latestHistory)
                        }

                        override fun onSelectionEditingChanged(editing: Boolean) {
                            surfaceState.onSelectionEditingChanged(editing)
                        }

                        override fun onTextEditingChanged(editing: Boolean, attributes: SelectedTextAttributes?) {
                            surfaceState.onTextEditingChanged(editing)
                            if (latestTools.surface === view) latestTools.editingTextAttributes = attributes
                        }

                        override fun onPanZoomRequested(step: PanZoomStep) {
                            if (!latestZoomable) return
                            latestAccess.onIntents(pageSurfaceIntents(step, view.width.toFloat(), view.height.toFloat(), latestLayout, page))
                        }

                        override fun onZoomRequested(zoom: Float) {
                            if (!latestZoomable) return
                            latestAccess.onIntents(pageZoomIntents(zoom, latestLayout, page))
                        }

                        override fun onFitWidthRequested() {
                            if (latestZoomable) latestAccess.onFitRequested()
                        }
                    }

                    surface = this
                }
            },
            update = { view ->
                view.setPageFrame(layout)
                view.setColors(colors)
                view.applyPenSettings(access.penSettings, tools, mode)
            }
        )

        if (surfaceState.persistenceFailed) {
            InkPersistenceBanner(
                message = stringResource(R.string.reader_page_ink_persistence_failure),
                testTag = ReaderPageInkTestTags.persistenceBanner(page),
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        InkSelectionMenu(
            state = surfaceState,
            surface = surface,
            tools = tools,
            paneWidthPx = constraints.maxWidth.toFloat(),
            paneHeightPx = constraints.maxHeight.toFloat()
        )
    }
}

/**
 * What one page surface last reported, kept so that claiming the shared rail and history — see
 * [claim] — shows this surface's own state at once rather than the previous surface's.
 */
private class SurfaceBinding {
    var canUndo = false
    var canRedo = false
    var viewport: SheetViewport? = null
    var strokeCount = 0

    /** Binds [tools] and [history] to [surface], with what [surface] last reported. */
    fun claim(surface: InkDrawingSurface, tools: SheetTools, history: SheetPaneHistory) {
        if (tools.surface !== surface) {
            tools.bind(surface)
            tools.viewport = viewport
            tools.strokeCount = strokeCount
        }

        if (!history.isBoundTo(surface)) {
            history.bind(surface)
            history.update(canUndo, canRedo)
        }
    }
}

/**
 * The rail's current tool and [settings] on a page surface, with every width given in millimetres
 * converted to the page's own ink units through [mode], so a 0.5mm pen draws 0.5mm on the printed page.
 */
private fun InkDrawingSurface.applyPenSettings(settings: PenSettings, tools: SheetTools, mode: InkSurfaceMode.Page) {
    setTool(tools.surfaceTool)
    setPenTip(settings.tip)
    setPenColorArgb(settings.colorChoice.storedArgb())
    setPenWidthSheetUnits(mode.mmToUnits(settings.widthTenthsMm / 10f))
    setHighlighterColorArgb(settings.highlighterColorChoice.storedArgb)
    setHighlighterWidthSheetUnits(mode.mmToUnits(settings.highlighterWidthMm.toFloat()))
    setShape(settings.shape)
    setShapeColorArgb(settings.shapeColorChoice.storedArgb())
    setShapeWidthSheetUnits(mode.mmToUnits(settings.shapeWidthTenthsMm / 10f))
    setEraserSizeMm(settings.eraserSizeMm.toFloat())
    setEraserMode(settings.eraserMode)
    setStraightenMode(settings.straightenMode)
    setHighlighterStraightenMode(settings.highlighterStraightenMode)
    setSelectMode(settings.selectMode)
    setTextFont(settings.textFont)
    setTextSizePt(settings.textSizePt.toFloat())
    setTextStyle(settings.textStyle)
    setTextAlignment(settings.textAlignment)
    setTextColorArgb(settings.textColorChoice.storedArgb())
}

private const val PAGE_INK_CLOSE_TIMEOUT_MILLIS = 5_000L
