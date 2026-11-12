package ai.sharemate.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Layout tokens (2.10, 2.11): gutters grow with the window, and the same rules hold when the window changes shape. Classes are
 * chosen on the width divided by the font scale, so larger text gets the layout of a narrower window: a 1.3x font on the
 * full Pleos window (1680 / 1.3 = 1292 dp) is EXPANDED, not FULL, which gives the five-tab pill and the bigger text the room
 * they need. Gutters and maxima stay in raw dp. Since 2.11 the car's rail takes the whole window between the gutters: a
 * 1200-dp column left a third of the full window as empty sky, so on a wide, tall window the rail splits into the page
 * column and the 지금 column instead ([carColumns]). The whitespace lives inside the layout: the gutters, the gap between
 * the two columns and the 720-dp reading measure. The phone still centres a capped column.
 */

enum class WidthClass { COMPACT, MEDIUM, EXPANDED, FULL }

enum class HeightClass { SHORT, MEDIUM, TALL }

@Immutable
data class LayoutTokens(
    val widthClass: WidthClass,
    val heightClass: HeightClass,
    /** The space between the window edge and any content. */
    val gutter: Dp,
    /** The widest a block of content gets; the rest of the window stays backdrop. Infinite on the car, whose rail fills the window. */
    val contentMax: Dp,
    /** The widest a column of reading text (a greeting, a sentence) gets. */
    val readingMax: Dp,
    /** Space between cards in a row or a grid. */
    val cardGap: Dp,
    /** Space between sections of a screen. */
    val sectionGap: Dp,
    /** Padding inside a glass panel. */
    val panelPadding: Dp,
    /**
     * True on the car, in every class: content starts at the start gutter, and the flag is also the car's mark for its touch
     * rules (no ripple, 56-dp dialog buttons, the .98 press). False on the phone, which centres its column.
     */
    val startAnchored: Boolean,
) {
    val compact get() = widthClass == WidthClass.COMPACT
    val expanded get() = widthClass == WidthClass.EXPANDED || widthClass == WidthClass.FULL
    val full get() = widthClass == WidthClass.FULL
    val short get() = heightClass == HeightClass.SHORT
}

internal fun heightClassFor(height: Dp): HeightClass = when {
    height < 600.dp -> HeightClass.SHORT
    height < 900.dp -> HeightClass.MEDIUM
    else -> HeightClass.TALL
}

/**
 * The car's classes: a narrow split window, a medium one, a wide one, and the full Pleos window (1680 dp at 160 dpi). In
 * every class the rail runs from gutter to gutter (no content maximum); on a wide, tall window [carColumns] splits it into
 * the page column and the 지금 column, so the right of the window carries the driver's now instead of empty sky.
 */
internal fun carLayout(width: Dp, height: Dp = 1200.dp): LayoutTokens {
    val h = heightClassFor(height)
    return when {
        width < 760.dp -> LayoutTokens(WidthClass.COMPACT, h, gutter = 20.dp, contentMax = Dp.Infinity, readingMax = 560.dp, cardGap = 12.dp, sectionGap = 20.dp, panelPadding = 22.dp, startAnchored = true)
        width < 1100.dp -> LayoutTokens(WidthClass.MEDIUM, h, gutter = 32.dp, contentMax = Dp.Infinity, readingMax = 640.dp, cardGap = 16.dp, sectionGap = 24.dp, panelPadding = 26.dp, startAnchored = true)
        // 2.13: one card gap of 16 dp from EXPANDED up (2.12 had 20 and 24 on the pages beside 16 in the 지금 column), the mock's one gap.
        width < 1400.dp -> LayoutTokens(WidthClass.EXPANDED, h, gutter = 48.dp, contentMax = Dp.Infinity, readingMax = 720.dp, cardGap = 16.dp, sectionGap = 28.dp, panelPadding = 28.dp, startAnchored = true)
        else -> LayoutTokens(WidthClass.FULL, h, gutter = 72.dp, contentMax = Dp.Infinity, readingMax = 720.dp, cardGap = 16.dp, sectionGap = 32.dp, panelPadding = 28.dp, startAnchored = true)
    }
}

/** The 지금 column's width at a 1× font: room for who is driving, the next booking, today and this month. */
internal val NOW_COLUMN_WIDTH = 392.dp

/** The least effective width (dp ÷ font scale) the page column keeps beside the 지금 column: the hero's cards and the panes need it. */
private val PAGE_COLUMN_MIN = 780.dp

/** How the car rail divides: the [page] column the five pages slide in, and the [side] 지금 column [gap] beside it (none: 0). */
@Immutable
internal data class CarColumns(val page: Dp, val side: Dp, val gap: Dp) {
    val split: Boolean get() = side > 0.dp
}

/**
 * Splits the car [rail] (its width between the gutters) into the page column and the 지금 column, on a wide, tall window
 * only: EXPANDED or FULL, never SHORT, where the height belongs to the page. The column grows with the font (392 dp × the
 * scale, held to 1–1.3×) and sits a section gap from the page, and it is kept only while the page keeps [PAGE_COLUMN_MIN] of
 * effective width. Otherwise the page takes the whole rail: 1112 · 32 · 392 at 1680 dp, 832 at 1400, none under 1296.
 */
internal fun carColumns(layout: LayoutTokens, rail: Dp, fontScale: Float): CarColumns {
    val whole = CarColumns(rail, 0.dp, 0.dp)
    if (!layout.expanded || layout.short) return whole
    val side = NOW_COLUMN_WIDTH * fontScale.coerceIn(1f, 1.3f)
    val gap = layout.sectionGap
    val page = rail - gap - side
    return if (page / fontScale.coerceAtLeast(1f) >= PAGE_COLUMN_MIN) CarColumns(page, side, gap) else whole
}

/** The phone's classes: a phone in portrait, a phone in landscape or a small tablet, a tablet. Content is centred. */
internal fun phoneLayout(width: Dp, height: Dp = 800.dp): LayoutTokens {
    val h = heightClassFor(height)
    return when {
        width < 600.dp -> LayoutTokens(WidthClass.COMPACT, h, gutter = 20.dp, contentMax = Dp.Infinity, readingMax = 420.dp, cardGap = 12.dp, sectionGap = 24.dp, panelPadding = 18.dp, startAnchored = false)
        width < 840.dp -> LayoutTokens(WidthClass.MEDIUM, h, gutter = 32.dp, contentMax = 600.dp, readingMax = 480.dp, cardGap = 12.dp, sectionGap = 24.dp, panelPadding = 20.dp, startAnchored = false)
        else -> LayoutTokens(WidthClass.EXPANDED, h, gutter = 48.dp, contentMax = 760.dp, readingMax = 520.dp, cardGap = 16.dp, sectionGap = 24.dp, panelPadding = 22.dp, startAnchored = false)
    }
}

val LocalLayout = compositionLocalOf { carLayout(1680.dp) }

/**
 * The height the phone's floating tab bar (or the gesture bar in landscape) covers at the bottom of a tab. The shell no longer
 * pads its content by the bar; each tab adds this as the bottom content padding of its own scroll, so content scrolls under
 * the glass bar instead of ending on a hard line above it. Zero outside the phone shell.
 */
val LocalNavInset = compositionLocalOf { 0.dp }

/**
 * Measures the window once and provides the matching tokens to [content]; the shell calls this at its root. The width
 * is divided by the font scale before the class is chosen (see the file comment); gutters and maxima stay in raw dp.
 */
@Composable fun ProvideLayout(compactApp: Boolean, modifier: Modifier = Modifier, content: @Composable BoxWithConstraintsScope.(LayoutTokens) -> Unit) {
    BoxWithConstraints(modifier) {
        val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val effective = maxWidth / scale
        val tokens = if (compactApp) phoneLayout(effective, maxHeight) else carLayout(effective, maxHeight)
        CompositionLocalProvider(LocalLayout provides tokens) { content(tokens) }
    }
}

/** Fills the width up to the scene's content maximum. Place it in a Box aligned to the start on the car and to the centre on the phone. */
@Composable fun Modifier.contentWidth(): Modifier = widthIn(max = LocalLayout.current.contentMax).fillMaxWidth()

/** Keeps a column of reading text to a comfortable measure. */
@Composable fun Modifier.readingWidth(): Modifier = widthIn(max = LocalLayout.current.readingMax).fillMaxWidth()
