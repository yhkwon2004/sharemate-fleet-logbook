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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Layout tokens (2.10): the screen is not filled edge to edge. Content keeps to a readable width with the backdrop
 * breathing around it, gutters grow with the window, and the same rules hold when the window changes shape.
 */

enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

@Immutable
data class LayoutTokens(
    val widthClass: WidthClass,
    /** The space between the window edge and any content. */
    val gutter: Dp,
    /** The widest a block of content gets; the rest of the window stays backdrop. */
    val contentMax: Dp,
    /** The widest a column of reading text (a greeting, a sentence) gets. */
    val readingMax: Dp,
    /** Space between cards in a row or a grid. */
    val cardGap: Dp,
    /** Space between sections of a screen. */
    val sectionGap: Dp,
    /** Padding inside a glass panel. */
    val panelPadding: Dp,
) {
    val compact get() = widthClass == WidthClass.COMPACT
    val expanded get() = widthClass == WidthClass.EXPANDED
}

/** The car's classes: a narrow split window, a medium one, and the full Pleos window (1680 dp wide at 160 dpi). */
internal fun carLayout(width: Dp): LayoutTokens = when {
    width < 760.dp -> LayoutTokens(WidthClass.COMPACT, gutter = 20.dp, contentMax = Dp.Infinity, readingMax = 560.dp, cardGap = 14.dp, sectionGap = 18.dp, panelPadding = 22.dp)
    width < 1280.dp -> LayoutTokens(WidthClass.MEDIUM, gutter = 40.dp, contentMax = 1080.dp, readingMax = 640.dp, cardGap = 18.dp, sectionGap = 24.dp, panelPadding = 26.dp)
    else -> LayoutTokens(WidthClass.EXPANDED, gutter = 64.dp, contentMax = 1320.dp, readingMax = 720.dp, cardGap = 20.dp, sectionGap = 32.dp, panelPadding = 28.dp)
}

/** The phone's classes: a phone in portrait, a phone in landscape or a small tablet, a tablet. */
internal fun phoneLayout(width: Dp): LayoutTokens = when {
    width < 600.dp -> LayoutTokens(WidthClass.COMPACT, gutter = 20.dp, contentMax = Dp.Infinity, readingMax = 420.dp, cardGap = 10.dp, sectionGap = 16.dp, panelPadding = 18.dp)
    width < 840.dp -> LayoutTokens(WidthClass.MEDIUM, gutter = 32.dp, contentMax = 600.dp, readingMax = 480.dp, cardGap = 12.dp, sectionGap = 18.dp, panelPadding = 20.dp)
    else -> LayoutTokens(WidthClass.EXPANDED, gutter = 48.dp, contentMax = 680.dp, readingMax = 520.dp, cardGap = 14.dp, sectionGap = 20.dp, panelPadding = 22.dp)
}

val LocalLayout = compositionLocalOf { carLayout(1680.dp) }

/** Measures the window once and provides the matching tokens to [content]; the shell calls this at its root. */
@Composable fun ProvideLayout(compactApp: Boolean, modifier: Modifier = Modifier, content: @Composable BoxWithConstraintsScope.(LayoutTokens) -> Unit) {
    BoxWithConstraints(modifier) {
        val tokens = if (compactApp) phoneLayout(maxWidth) else carLayout(maxWidth)
        CompositionLocalProvider(LocalLayout provides tokens) { content(tokens) }
    }
}

/** Fills the width up to the scene's content maximum; centre it in its parent for the whitespace to split evenly. */
@Composable fun Modifier.contentWidth(): Modifier = widthIn(max = LocalLayout.current.contentMax).fillMaxWidth()

/** Keeps a column of reading text to a comfortable measure. */
@Composable fun Modifier.readingWidth(): Modifier = widthIn(max = LocalLayout.current.readingMax).fillMaxWidth()
