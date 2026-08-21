package com.folium.reader.reader

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
    const val CLOSE = "typography-close"
    const val ABANDONED_DIALOG = "typography-abandoned"
    const val ABANDONED_ACTION = "typography-abandoned-action"

    fun fontOption(family: ReflowFontFamily): String = "typography-font/${family.name}"
    fun alignOption(align: ReflowTextAlign): String = "typography-align/${align.name}"
}

/** How much of the reader's height the sheet takes, leaving the page above it visible. */
private const val SHEET_HEIGHT_FRACTION = 0.45f

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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(SHEET_HEIGHT_FRACTION)
            .testTag(TypographySheetTestTags.SHEET)
            .border(2.dp, MaterialTheme.colorScheme.onSurface),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(FoliumSpacing.m)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.reader_typography),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onClose, modifier = Modifier.testTag(TypographySheetTestTags.CLOSE)) {
                    Text(stringResource(R.string.reader_typography_close))
                }
            }

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

@Composable
private fun LabeledSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onChange: (Float) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(FoliumSpacing.xs))
        Text(display(value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
