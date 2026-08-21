package com.folium.reader.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.folium.reader.R
import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.ReflowFontFamily
import com.folium.reader.core.pdf.ReflowTextAlign
import com.folium.reader.core.pdf.TypographyPreset
import com.folium.reader.library.LibraryPaths
import com.folium.reader.library.TypographyCostStore
import com.folium.reader.library.TypographyPresetStore
import com.folium.reader.library.documentWork
import com.folium.reader.ui.FoliumDialog
import com.folium.reader.ui.FoliumSpacing
import kotlin.math.roundToInt

object TypographySheetTestTags {
    const val SHEET = "typography-sheet"
    const val WORKING_INDICATOR = "typography-working"
    const val USE_FOR_ALL = "typography-use-for-all"
    const val RESET_TO_GLOBAL = "typography-reset-to-global"
    const val ABANDONED_DIALOG = "typography-abandoned"
    const val ABANDONED_ACTION = "typography-abandoned-action"

    fun fontOption(family: ReflowFontFamily): String = "typography-font/${family.name}"
    fun alignOption(align: ReflowTextAlign): String = "typography-align/${align.name}"
    const val HANDLE = "typography-handle"
}

/**
 * How much of the reader's height the sheet takes at rest, leaving the page above it visible, and
 * how much it takes once expanded — far enough to hold every control at once, and no further than
 * the reader's own title bar so the book being changed never leaves the screen entirely.
 */
private const val SHEET_HEIGHT_FRACTION = 0.52f
private const val SHEET_EXPANDED_FRACTION = 0.92f

/** The slider wears the shelf's own square, unrounded language rather than the platform's. */
private val HANDLE_WIDTH = 44.dp
private val HANDLE_HEIGHT = 3.dp
private const val DRAG_SNAP_PX = 6f

private val SLIDER_TRACK_HEIGHT = 2.dp
private val SLIDER_THUMB_WIDTH = 4.dp
private val SLIDER_THUMB_HEIGHT = 24.dp

/**
 * The reading-settings surface: every control is bound straight to a [TypographyPreset] field and
 * applies to the page immediately, while [TypographySheetController] decides when that change is
 * worth an actual re-pagination. Owns its own controller instance for as long as it is composed,
 * matching [ReaderHost]'s own worker-plus-main-post shape.
 */
@Composable
internal fun TypographySettingsSheet(
    bookId: BookId,
    applyPreset: (TypographyPreset, (RepaginationResult) -> Unit) -> Unit,
    onDismissRequest: () -> Unit,
    onLeaveReader: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    var phase by remember(bookId) { mutableStateOf<TypographySheetPhase>(TypographySheetPhase.Loading) }
    var abandoned by remember(bookId) { mutableStateOf(false) }

    val controller = remember(bookId) {
        val paths = LibraryPaths(context.filesDir)
        TypographySheetController(
            bookId = bookId,
            presetStore = TypographyPresetStore(paths),
            costStore = TypographyCostStore(paths),
            worker = documentWork,
            mainPost = { Handler(Looper.getMainLooper()).post(it) },
            applyPreset = applyPreset,
            onState = { phase = it },
            onAbandoned = { abandoned = true }
        )
    }

    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.dispose() }
    }

    BackHandler {
        controller.dismiss()
        onDismissRequest()
    }

    if (abandoned) {
        FoliumDialog(
            onDismissRequest = onLeaveReader,
            modifier = Modifier.testTag(TypographySheetTestTags.ABANDONED_DIALOG),
            title = { Text(stringResource(R.string.reader_typography_abandoned_title)) },
            text = { Text(stringResource(R.string.reader_typography_abandoned_body)) },
            confirmButton = {
                TextButton(
                    onClick = onLeaveReader,
                    modifier = Modifier.testTag(TypographySheetTestTags.ABANDONED_ACTION)
                ) {
                    Text(stringResource(R.string.reader_typography_abandoned_action))
                }
            }
        )
        return
    }

    val ready = phase as? TypographySheetPhase.Ready

    Box(modifier.fillMaxSize()) {
        if (ready?.indicatorVisible == true) {
            WorkingIndicator(Modifier.align(Alignment.TopCenter).padding(top = FoliumSpacing.xl))
        }

        if (ready != null) {
            TypographyControlsSheet(
                preset = ready.preset,
                appliesLive = ready.appliesLive,
                onEdit = controller::edit,
                onUseForAllBooks = controller::useForAllBooks,
                onResetToGlobal = controller::resetToGlobal,
                onClose = {
                    controller.dismiss()
                    onDismissRequest()
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun WorkingIndicator(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .testTag(TypographySheetTestTags.WORKING_INDICATOR)
            .border(2.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.surface
    ) {
        Text(
            text = stringResource(R.string.reader_typography_working),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = FoliumSpacing.m, vertical = FoliumSpacing.xs)
        )
    }
}

@Composable
private fun TypographyControlsSheet(
    preset: TypographyPreset,
    appliesLive: Boolean,
    onEdit: (TypographyPreset) -> Unit,
    onUseForAllBooks: () -> Unit,
    onResetToGlobal: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val fraction by animateFloatAsState(
        targetValue = if (expanded) SHEET_EXPANDED_FRACTION else SHEET_HEIGHT_FRACTION,
        label = "typography-sheet-height"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(fraction)
            .testTag(TypographySheetTestTags.SHEET)
            .border(2.dp, MaterialTheme.colorScheme.onSurface),
        color = MaterialTheme.colorScheme.surface
    ) {
        // Only the bottom and side insets: the sheet is anchored to the bottom of the reader, so the
        // status bar's inset would reserve a band of nothing across its own top edge and push every
        // control down out of reach.
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState())
                .padding(FoliumSpacing.m)
        ) {
            // One gesture covers the sheet's whole range: up expands it, down settles it back, and
            // down again from rest puts it away. A separate confirm button would be a second way to
            // say what dragging already says, and the reader has to learn the drag regardless.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = FoliumSpacing.touchTarget)
                    .clickable { expanded = !expanded }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            when {
                                delta < -DRAG_SNAP_PX -> expanded = true
                                delta > DRAG_SNAP_PX && expanded -> expanded = false
                                delta > DRAG_SNAP_PX -> onClose()
                            }
                        }
                    )
                    .testTag(TypographySheetTestTags.HANDLE),
                contentAlignment = Alignment.Center
            ) {
                Spacer(
                    Modifier
                        .size(width = HANDLE_WIDTH, height = HANDLE_HEIGHT)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }

            Text(
                text = stringResource(R.string.reader_typography),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!appliesLive) {
                Text(
                    text = stringResource(R.string.reader_typography_working),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(FoliumSpacing.s))

            LabeledSection(R.string.reader_typography_font) {
                OptionRow(
                    options = ReflowFontFamily.entries,
                    selected = preset.fontFamily,
                    label = ::fontFamilyLabel,
                    testTag = TypographySheetTestTags::fontOption,
                    onSelect = { onEdit(preset.copy(fontFamily = it)) }
                )
            }

            LabeledSection(R.string.reader_typography_size) {
                LabeledSlider(
                    value = preset.fontSizePoints,
                    range = MIN_FONT_SIZE_POINTS..MAX_FONT_SIZE_POINTS,
                    display = { "${it.roundToInt()} pt" },
                    onChange = { onEdit(preset.copy(fontSizePoints = it)) }
                )
            }

            LabeledSection(R.string.reader_typography_line_spacing) {
                PublisherOrCustomRow(
                    isPublisher = preset.lineHeight == null,
                    onPublisher = { onEdit(preset.copy(lineHeight = null)) },
                    onCustom = { onEdit(preset.copy(lineHeight = preset.lineHeight ?: DEFAULT_LINE_HEIGHT)) }
                )
                preset.lineHeight?.let { current ->
                    LabeledSlider(
                        value = current,
                        range = MIN_LINE_HEIGHT..MAX_LINE_HEIGHT,
                        display = { "%.1f×".format(it) },
                        onChange = { onEdit(preset.copy(lineHeight = it)) }
                    )
                }
            }

            LabeledSection(R.string.reader_typography_margins) {
                LabeledSlider(
                    value = preset.marginEm,
                    range = MIN_MARGIN_EM..MAX_MARGIN_EM,
                    display = { "%.1f em".format(it) },
                    onChange = { onEdit(preset.copy(marginEm = it)) }
                )
            }

            LabeledSection(R.string.reader_typography_alignment) {
                OptionRow(
                    options = ReflowTextAlign.entries,
                    selected = preset.textAlign,
                    label = ::textAlignLabel,
                    testTag = TypographySheetTestTags::alignOption,
                    onSelect = { onEdit(preset.copy(textAlign = it)) }
                )
            }

            LabeledSection(R.string.reader_typography_indent) {
                PublisherOrCustomRow(
                    isPublisher = preset.paragraphIndentEm == null,
                    onPublisher = { onEdit(preset.copy(paragraphIndentEm = null)) },
                    onCustom = {
                        onEdit(preset.copy(paragraphIndentEm = preset.paragraphIndentEm ?: DEFAULT_INDENT_EM))
                    }
                )
                preset.paragraphIndentEm?.let { current ->
                    LabeledSlider(
                        value = current,
                        range = MIN_INDENT_EM..MAX_INDENT_EM,
                        display = { "%.1f em".format(it) },
                        onChange = { onEdit(preset.copy(paragraphIndentEm = it)) }
                    )
                }
            }

            Spacer(Modifier.height(FoliumSpacing.s))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(FoliumSpacing.s))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onResetToGlobal, modifier = Modifier.testTag(TypographySheetTestTags.RESET_TO_GLOBAL)) {
                    Text(stringResource(R.string.reader_typography_reset_to_global))
                }
                TextButton(onClick = onUseForAllBooks, modifier = Modifier.testTag(TypographySheetTestTags.USE_FOR_ALL)) {
                    Text(stringResource(R.string.reader_typography_use_for_all))
                }
            }
        }
    }
}

@Composable
private fun LabeledSection(label: Int, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = FoliumSpacing.xs)) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun <T> OptionRow(
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    testTag: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FoliumSpacing.xs)) {
        options.forEach { option ->
            val isSelected = option == selected
            TextButton(
                onClick = { onSelect(option) },
                modifier = Modifier
                    .heightIn(min = FoliumSpacing.touchTarget)
                    .then(
                        if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.small)
                        else Modifier
                    )
                    .testTag(testTag(option))
            ) {
                Text(
                    text = stringResource(label(option)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PublisherOrCustomRow(isPublisher: Boolean, onPublisher: () -> Unit, onCustom: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FoliumSpacing.xs)) {
        TextButton(
            onClick = onPublisher,
            modifier = if (isPublisher) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.small) else Modifier
        ) {
            Text(
                text = stringResource(R.string.reader_typography_line_spacing_publisher),
                color = if (isPublisher) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(
            onClick = onCustom,
            modifier = if (!isPublisher) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.small) else Modifier
        ) {
            Text(
                text = stringResource(R.string.reader_typography_line_spacing_custom),
                color = if (!isPublisher) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabeledSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onChange: (Float) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            thumb = {
                Spacer(
                    Modifier
                        .size(width = SLIDER_THUMB_WIDTH, height = SLIDER_THUMB_HEIGHT)
                        .background(MaterialTheme.colorScheme.onSurface)
                )
            },
            track = { state ->
                val fraction = if (state.valueRange.endInclusive > state.valueRange.start) {
                    (state.value - state.valueRange.start) /
                        (state.valueRange.endInclusive - state.valueRange.start)
                } else {
                    0f
                }

                Box(Modifier.fillMaxWidth().height(SLIDER_TRACK_HEIGHT)) {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    Spacer(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.onSurface)
                    )
                }
            }
        )
        Spacer(Modifier.width(FoliumSpacing.s))
        Text(display(value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun fontFamilyLabel(family: ReflowFontFamily): Int = when (family) {
    ReflowFontFamily.PUBLISHER -> R.string.reader_typography_font_publisher
    ReflowFontFamily.SERIF -> R.string.reader_typography_font_serif
    ReflowFontFamily.SANS -> R.string.reader_typography_font_sans
    ReflowFontFamily.MONOSPACE -> R.string.reader_typography_font_monospace
}

private fun textAlignLabel(align: ReflowTextAlign): Int = when (align) {
    ReflowTextAlign.PUBLISHER -> R.string.reader_typography_alignment_publisher
    ReflowTextAlign.LEFT -> R.string.reader_typography_alignment_left
    ReflowTextAlign.JUSTIFY -> R.string.reader_typography_alignment_justify
}

private const val MIN_FONT_SIZE_POINTS = 12f
private const val MAX_FONT_SIZE_POINTS = 32f
private const val MIN_LINE_HEIGHT = 1.0f
private const val MAX_LINE_HEIGHT = 2.4f
private const val DEFAULT_LINE_HEIGHT = 1.4f
private const val MIN_MARGIN_EM = 0f
private const val MAX_MARGIN_EM = 4f
private const val MIN_INDENT_EM = 0f
private const val MAX_INDENT_EM = 3f
private const val DEFAULT_INDENT_EM = 1f
