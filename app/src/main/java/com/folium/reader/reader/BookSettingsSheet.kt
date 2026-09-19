package com.folium.reader.reader

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.folium.reader.R
import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.ReflowFontFamily
import com.folium.reader.core.pdf.ReflowPageBackground
import com.folium.reader.core.pdf.ReflowTextAlign
import com.folium.reader.core.pdf.TypographyPreset
import com.folium.reader.library.LibraryPaths
import com.folium.reader.library.TypographyCostStore
import com.folium.reader.library.TypographyPresetStore
import com.folium.reader.library.documentWork
import com.folium.reader.ui.FoliumBottomSheet
import com.folium.reader.ui.FoliumDialog
import com.folium.reader.ui.FoliumSheetAnchor
import com.folium.reader.ui.FoliumSpacing
import com.folium.reader.ui.FoliumWidthClass
import kotlin.math.roundToInt

object BookSettingsSheetTestTags {
    const val SHEET = "book-settings-sheet"
    const val WORKING_INDICATOR = "typography-working"
    const val USE_FOR_ALL = "typography-use-for-all"
    const val RESET_TO_GLOBAL = "typography-reset-to-global"
    const val ABANDONED_DIALOG = "typography-abandoned"
    const val ABANDONED_ACTION = "typography-abandoned-action"
    const val TWO_PAGES = "book-settings-two-pages"

    fun fontOption(family: ReflowFontFamily): String = "typography-font/${family.name}"
    fun alignOption(align: ReflowTextAlign): String = "typography-align/${align.name}"
    fun pageBackgroundOption(background: ReflowPageBackground): String = "typography-page-background/${background.name}"
}

/**
 * Which of a [BookSettingsSheet]'s sections and rows a given document actually shows.
 *
 * A fixed-layout document has no typography to edit, so it shows only "Two pages" — never hidden,
 * only disabled, so a PDF reader still learns the control exists once their window is wide enough for
 * it. A reflowable document shows everything: its own preset editing plus the same "Two pages" row,
 * which stays a reader-wide preference the typography preset never touches.
 */
internal data class BookSettingsSections(
    val showsTextSection: Boolean,
    val showsPageBackgroundOption: Boolean,
    val showsScopeFooter: Boolean,
    val twoPagesRowEnabled: Boolean
)

internal fun resolveBookSettingsSections(reflowable: Boolean, spread: ReaderSpreadState): BookSettingsSections =
    BookSettingsSections(
        showsTextSection = reflowable,
        showsPageBackgroundOption = reflowable,
        showsScopeFooter = reflowable,
        twoPagesRowEnabled = spread.windowQualifies
    )

/**
 * The book-settings surface: a reflowable document's every typography control is bound straight to a
 * [TypographyPreset] field and applies to the page immediately, while [TypographySheetController]
 * decides when that change is worth an actual re-pagination; a fixed-layout document skips all of
 * that and shows only the page-level "Two pages" row. Owns its own controller instance for as long as
 * a reflowable document keeps it composed, matching [ReaderHost]'s own worker-plus-main-post shape.
 */
@Composable
internal fun BookSettingsSheet(
    bookId: BookId,
    reflowable: Boolean,
    spread: ReaderSpreadState,
    onSpreadToggle: (Boolean) -> Unit,
    reducedMotion: Boolean,
    applyPreset: (TypographyPreset, (RepaginationResult) -> Unit) -> Unit,
    onDismissRequest: () -> Unit,
    onLeaveReader: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!reflowable) {
        FoliumBottomSheet(
            visible = true,
            onDismissRequest = onDismissRequest,
            paneTitle = stringResource(R.string.reader_book_settings),
            handleContentDescription = stringResource(R.string.foliumsheet_handle),
            testTag = BookSettingsSheetTestTags.SHEET,
            reducedMotion = reducedMotion,
            modifier = modifier,
            header = { anchor, onToggle -> SheetTitleRow(anchor, onToggle) }
        ) {
            PageSection(
                sections = resolveBookSettingsSections(reflowable = false, spread = spread),
                spread = spread,
                pageBackground = null,
                onEdit = {},
                onSpreadToggle = onSpreadToggle
            )
        }
        return
    }

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

    if (abandoned) {
        FoliumDialog(
            onDismissRequest = onLeaveReader,
            modifier = Modifier.testTag(BookSettingsSheetTestTags.ABANDONED_DIALOG),
            title = { Text(stringResource(R.string.reader_typography_abandoned_title)) },
            text = { Text(stringResource(R.string.reader_typography_abandoned_body)) },
            confirmButton = {
                TextButton(
                    onClick = onLeaveReader,
                    modifier = Modifier.testTag(BookSettingsSheetTestTags.ABANDONED_ACTION)
                ) {
                    Text(stringResource(R.string.reader_typography_abandoned_action))
                }
            }
        )
        return
    }

    val ready = phase as? TypographySheetPhase.Ready

    Box(modifier) {
        if (ready?.indicatorVisible == true) {
            WorkingIndicator(Modifier.align(Alignment.TopCenter).padding(top = FoliumSpacing.xl))
        }

        if (ready != null) {
            FoliumBottomSheet(
                visible = true,
                onDismissRequest = {
                    controller.dismiss()
                    onDismissRequest()
                },
                paneTitle = stringResource(R.string.reader_book_settings),
                handleContentDescription = stringResource(R.string.foliumsheet_handle),
                testTag = BookSettingsSheetTestTags.SHEET,
                reducedMotion = reducedMotion,
                header = { anchor, onToggle -> SheetTitleRow(anchor, onToggle) },
                footer = { ScopeFooter(onResetToGlobal = controller::resetToGlobal, onUseForAllBooks = controller::useForAllBooks) }
            ) {
                if (!ready.appliesLive) {
                    Text(
                        text = stringResource(R.string.reader_typography_working),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(FoliumSpacing.s))
                }

                ReflowableSections(
                    preset = ready.preset,
                    spread = spread,
                    onEditPreset = controller::edit,
                    onSpreadToggle = onSpreadToggle
                )
            }
        }
    }
}

/**
 * The "Text" and "Page" sections, side by side once the sheet is wider than
 * [FoliumWidthClass.COMPACT], which is a tablet held upright and anything wider, and in one column
 * on a phone, where the reader scrolls to reach the second.
 */
@Composable
private fun ReflowableSections(
    preset: TypographyPreset,
    spread: ReaderSpreadState,
    onEditPreset: (TypographyPreset) -> Unit,
    onSpreadToggle: (Boolean) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val twoColumns = FoliumWidthClass.of(maxWidth) != FoliumWidthClass.COMPACT
        val sections = resolveBookSettingsSections(reflowable = true, spread = spread)

        if (twoColumns) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FoliumSpacing.xxl)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FoliumSpacing.m)) {
                    TextSectionPrimary(preset, onEditPreset)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FoliumSpacing.m)) {
                    TextSectionSecondary(preset, onEditPreset)
                    PageSection(sections, spread, preset.pageBackground, { onEditPreset(preset.copy(pageBackground = it)) }, onSpreadToggle)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FoliumSpacing.m)) {
                TextSectionPrimary(preset, onEditPreset)
                TextSectionSecondary(preset, onEditPreset)
                PageSection(sections, spread, preset.pageBackground, { onEditPreset(preset.copy(pageBackground = it)) }, onSpreadToggle)
            }
        }
    }
}

@Composable
private fun SheetTitleRow(anchor: FoliumSheetAnchor, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = FoliumSpacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.reader_book_settings),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        ChevronButton(pointsUp = anchor != FoliumSheetAnchor.EXPANDED, onClick = onToggle)
    }
}

@Composable
private fun ChevronButton(pointsUp: Boolean, onClick: () -> Unit) {
    val expandDescription = stringResource(R.string.foliumsheet_action_expand)
    val collapseDescription = stringResource(R.string.foliumsheet_action_collapse)
    val ink = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val strokePx = with(density) { 1.6.dp.toPx() }

    Box(
        Modifier
            .size(FoliumSpacing.touchTarget)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (pointsUp) expandDescription else collapseDescription },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(20.dp)
                .drawBehind {
                    val midX = size.width / 2f
                    val top = if (pointsUp) size.height * 0.65f else size.height * 0.35f
                    val bottom = if (pointsUp) size.height * 0.35f else size.height * 0.65f
                    drawLine(ink, Offset(size.width * 0.25f, top), Offset(midX, bottom), strokePx, cap = Stroke.DefaultCap)
                    drawLine(ink, Offset(midX, bottom), Offset(size.width * 0.75f, top), strokePx, cap = Stroke.DefaultCap)
                }
        )
    }
}

@Composable
private fun WorkingIndicator(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .testTag(BookSettingsSheetTestTags.WORKING_INDICATOR)
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

/** Font, size and line spacing — the left column once the sheet flows in two. */
@Composable
private fun TextSectionPrimary(preset: TypographyPreset, onEdit: (TypographyPreset) -> Unit) {
    SettingBlock(label = stringResource(R.string.reader_typography_font)) {
        SegmentedRow(
            options = ReflowFontFamily.entries,
            selectedOption = preset.fontFamily,
            label = ::fontFamilyLabel,
            testTag = BookSettingsSheetTestTags::fontOption,
            onSelect = { onEdit(preset.copy(fontFamily = it)) }
        )
    }

    SettingBlock(label = stringResource(R.string.reader_typography_size), value = "${preset.fontSizePoints.roundToInt()} pt") {
        SteppedSlider(
            value = preset.fontSizePoints,
            range = MIN_FONT_SIZE_POINTS..MAX_FONT_SIZE_POINTS,
            step = 1f,
            onChange = { onEdit(preset.copy(fontSizePoints = it)) }
        )
    }

    SettingBlock(
        label = stringResource(R.string.reader_typography_line_spacing),
        value = if (preset.lineHeight == null) stringResource(R.string.reader_typography_line_spacing_publisher) else "%.1f×".format(preset.lineHeight)
    ) {
        SegmentedRow(
            options = listOf(true, false),
            selectedOption = preset.lineHeight == null,
            label = { isPublisher -> if (isPublisher) R.string.reader_typography_line_spacing_publisher else R.string.reader_typography_line_spacing_custom },
            testTag = { isPublisher -> "typography-line-spacing/${if (isPublisher) "publisher" else "custom"}" },
            onSelect = { isPublisher ->
                onEdit(preset.copy(lineHeight = if (isPublisher) null else preset.lineHeight ?: DEFAULT_LINE_HEIGHT))
            }
        )
        preset.lineHeight?.let { current ->
            SteppedSlider(
                value = current,
                range = MIN_LINE_HEIGHT..MAX_LINE_HEIGHT,
                step = 0.1f,
                onChange = { onEdit(preset.copy(lineHeight = it)) }
            )
        }
    }
}

/** Alignment, margins and paragraph indent — the right column once the sheet flows in two. */
@Composable
private fun TextSectionSecondary(preset: TypographyPreset, onEdit: (TypographyPreset) -> Unit) {
    SettingBlock(label = stringResource(R.string.reader_typography_alignment)) {
        SegmentedRow(
            options = ReflowTextAlign.entries,
            selectedOption = preset.textAlign,
            label = ::textAlignLabel,
            testTag = BookSettingsSheetTestTags::alignOption,
            onSelect = { onEdit(preset.copy(textAlign = it)) }
        )
    }

    SettingBlock(label = stringResource(R.string.reader_typography_margins), value = "%.1f em".format(preset.marginEm)) {
        SteppedSlider(
            value = preset.marginEm,
            range = MIN_MARGIN_EM..MAX_MARGIN_EM,
            step = 0.1f,
            onChange = { onEdit(preset.copy(marginEm = it)) }
        )
    }

    SettingBlock(
        label = stringResource(R.string.reader_typography_indent),
        value = if (preset.paragraphIndentEm == null) stringResource(R.string.reader_typography_indent_publisher) else "%.1f em".format(preset.paragraphIndentEm)
    ) {
        SegmentedRow(
            options = listOf(true, false),
            selectedOption = preset.paragraphIndentEm == null,
            label = { isPublisher -> if (isPublisher) R.string.reader_typography_indent_publisher else R.string.reader_typography_indent_custom },
            testTag = { isPublisher -> "typography-indent/${if (isPublisher) "publisher" else "custom"}" },
            onSelect = { isPublisher ->
                onEdit(preset.copy(paragraphIndentEm = if (isPublisher) null else preset.paragraphIndentEm ?: DEFAULT_INDENT_EM))
            }
        )
        preset.paragraphIndentEm?.let { current ->
            SteppedSlider(
                value = current,
                range = MIN_INDENT_EM..MAX_INDENT_EM,
                step = 0.1f,
                onChange = { onEdit(preset.copy(paragraphIndentEm = it)) }
            )
        }
    }
}

/**
 * The "Page" section: a reflowable document's own page background alongside "Two pages", which every
 * document shows since a facing-page spread reads a fixed PDF page and a reflowable book's own fixed
 * box exactly the same way. [pageBackground] is `null` for a fixed-layout document, which has no page
 * background of its own to choose.
 */
@Composable
private fun PageSection(
    sections: BookSettingsSections,
    spread: ReaderSpreadState,
    pageBackground: ReflowPageBackground?,
    onEdit: (ReflowPageBackground) -> Unit,
    onSpreadToggle: (Boolean) -> Unit
) {
    if (sections.showsPageBackgroundOption && pageBackground != null) {
        SettingBlock(label = stringResource(R.string.reader_typography_page_background), value = stringResource(pageBackgroundLabel(pageBackground))) {
            SegmentedRow(
                options = ReflowPageBackground.entries,
                selectedOption = pageBackground,
                label = ::pageBackgroundLabel,
                testTag = BookSettingsSheetTestTags::pageBackgroundOption,
                onSelect = onEdit
            )
        }
    }

    SettingBlock(
        label = stringResource(R.string.reader_two_pages),
        value = stringResource(if (spread.twoPageSpreadEnabled) R.string.reader_two_pages_on else R.string.reader_two_pages_off)
    ) {
        SegmentedRow(
            options = listOf(true, false),
            selectedOption = spread.twoPageSpreadEnabled,
            label = { on -> if (on) R.string.reader_two_pages_on else R.string.reader_two_pages_off },
            testTag = { BookSettingsSheetTestTags.TWO_PAGES },
            enabled = sections.twoPagesRowEnabled,
            onSelect = onSpreadToggle
        )
        if (!sections.twoPagesRowEnabled) {
            Text(
                text = stringResource(R.string.reader_two_pages_unavailable),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ScopeFooter(onResetToGlobal: () -> Unit, onUseForAllBooks: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FoliumSpacing.s)
    ) {
        OutlinedButton(
            onClick = onUseForAllBooks,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.heightIn(min = FoliumSpacing.touchTarget).testTag(BookSettingsSheetTestTags.USE_FOR_ALL)
        ) {
            Text(stringResource(R.string.reader_typography_use_for_all), color = MaterialTheme.colorScheme.onSurface)
        }
        TextButton(onClick = onResetToGlobal, modifier = Modifier.testTag(BookSettingsSheetTestTags.RESET_TO_GLOBAL)) {
            Text(
                text = stringResource(R.string.reader_typography_reset_to_global),
                textDecoration = TextDecoration.Underline,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * One setting's own block: a hairline above it, its UPPERCASE label at the left and, when it applies,
 * the control's current value at the right, then the control itself.
 */
@Composable
private fun SettingBlock(label: String, value: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(FoliumSpacing.xs))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(FoliumSpacing.xs))
        content()
    }
}

private val SegmentLabelStyle = androidx.compose.ui.text.TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.05.em
)

/**
 * An equal-width grid of options: the selected cell filled ink-on-paper, every other cell outlined by
 * a hairline. [enabled] draws every cell in the muted [androidx.compose.material3.ColorScheme.outline]
 * tone and stops it from responding to a tap, for a control the current window does not allow yet.
 */
@Composable
private fun <T> SegmentedRow(
    options: List<T>,
    selectedOption: T,
    label: (T) -> Int,
    testTag: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FoliumSpacing.xs)) {
        options.forEach { option ->
            val isSelected = enabled && option == selectedOption
            val ink = MaterialTheme.colorScheme.onSurface
            val paper = MaterialTheme.colorScheme.surface
            val line = if (enabled) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outline
            val textColor = when {
                isSelected -> paper
                enabled -> ink
                else -> MaterialTheme.colorScheme.outline
            }

            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = FoliumSpacing.touchTarget)
                    .background(if (isSelected) ink else paper)
                    .border(1.dp, if (isSelected) ink else line)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(option) }
                    .semantics { selected = isSelected }
                    .testTag(testTag(option)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(label(option)).uppercase(),
                    style = SegmentLabelStyle,
                    color = textColor
                )
            }
        }
    }
}

/**
 * A slider flanked by a step-down and a step-up button, its track drawn in the field tone with the
 * moved distance filled in the design system's signal accent, and its thumb a thin ink bar rather
 * than a circle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SteppedSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onChange: (Float) -> Unit
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val strokePx = with(density) { 1.6.dp.toPx() }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(FoliumSpacing.touchTarget)
                .clickable { onChange((value - step).coerceIn(range)) },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(20.dp).drawBehind {
                drawLine(ink, Offset(size.width * 0.2f, size.height / 2f), Offset(size.width * 0.8f, size.height / 2f), strokePx, cap = Stroke.DefaultCap)
            })
        }

        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            thumb = {
                Spacer(Modifier.size(width = 3.dp, height = 14.dp).background(ink))
            },
            track = { state ->
                val fraction = if (state.valueRange.endInclusive > state.valueRange.start) {
                    (state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)
                } else {
                    0f
                }
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    Spacer(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                    Spacer(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                }
            }
        )

        Box(
            Modifier
                .size(FoliumSpacing.touchTarget)
                .clickable { onChange((value + step).coerceIn(range)) },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(20.dp).drawBehind {
                drawLine(ink, Offset(size.width * 0.2f, size.height / 2f), Offset(size.width * 0.8f, size.height / 2f), strokePx, cap = Stroke.DefaultCap)
                drawLine(ink, Offset(size.width / 2f, size.height * 0.2f), Offset(size.width / 2f, size.height * 0.8f), strokePx, cap = Stroke.DefaultCap)
            })
        }
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

private fun pageBackgroundLabel(background: ReflowPageBackground): Int = when (background) {
    ReflowPageBackground.MATCH_APP_THEME -> R.string.reader_typography_page_background_match_app_theme
    ReflowPageBackground.LIGHT -> R.string.reader_typography_page_background_light
    ReflowPageBackground.DARK -> R.string.reader_typography_page_background_dark
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
