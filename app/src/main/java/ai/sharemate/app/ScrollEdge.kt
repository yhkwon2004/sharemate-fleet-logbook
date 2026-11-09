package ai.sharemate.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/*
 * The top edge of a scrolled car page. Content that scrolls up under the header dissolves over a short band instead of being
 * cut on a hard line, where the last stroke of a card (the search pill's border, a panel's edge) used to linger as a stray
 * line under the driver row. Nothing at rest: the band grows with the first [SCROLL_EDGE] of scroll, so a page that has not
 * moved shows its first row whole, and it is a property of the scroll position, not a motion (nothing to reduce).
 */

/** The band at the top of a scrolled page in which its content fades out under the header. */
internal val SCROLL_EDGE = 32.dp

/** How strongly the band applies after [scrolled] px of scroll over an [edge]-px band: 0 at rest, 1 once a full band has passed. */
internal fun scrollEdgeStrength(scrolled: Float, edge: Float): Float = if (edge <= 0f) 0f else (scrolled / edge).coerceIn(0f, 1f)

/**
 * How much of the page shows at [t] down the band (0 at its top, 1 at its foot) under a band of [strength]. Ease-in, so the
 * last few px under the header are all but gone: a bright stroke resting there (the search pill's outline) disappears instead
 * of lingering at a fifth of its strength, while the rest of the band still fades smoothly into the page.
 */
internal fun scrollEdgeAlpha(t: Float, strength: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return 1f - strength.coerceIn(0f, 1f) * (1f - c * c)
}

private val EDGE_STOPS = floatArrayOf(0f, .25f, .5f, .75f, 1f)

/** Fades the top of this scroll container's viewport by [state]'s offset. Put it before `verticalScroll(state)` so it sees the viewport. */
internal fun Modifier.fadingTopEdge(state: ScrollState, edge: Dp = SCROLL_EDGE): Modifier = fadingTopEdge(edge) { state.value.toFloat() }

/** The same for a lazy list, whose offset is per item: any item past the first counts as a full band. */
internal fun Modifier.fadingTopEdge(state: LazyListState, edge: Dp = SCROLL_EDGE): Modifier =
    fadingTopEdge(edge) { if (state.firstVisibleItemIndex > 0) Float.MAX_VALUE else state.firstVisibleItemScrollOffset.toFloat() }

/**
 * How far the band's layer reaches past each side of the page. An offscreen layer clips to its bounds, while verticalScroll
 * lets the cards' day shadows spill sideways on purpose; without this room a widget's shadow at the column edge would be cut
 * flat on the first scrolled pixel and come back at rest.
 */
private val EDGE_SHADOW_ROOM = 12.dp

private fun Modifier.fadingTopEdge(edge: Dp, scrolled: () -> Float): Modifier = this
    // The layer below is laid out EDGE_SHADOW_ROOM wider on both sides; the page inside keeps its own size and place.
    .layout { measurable, constraints ->
        val room = EDGE_SHADOW_ROOM.roundToPx()
        val placeable = measurable.measure(constraints.offset(horizontal = 2 * room))
        layout((placeable.width - 2 * room).coerceAtLeast(0), placeable.height) { placeable.place(-room, 0) }
    }
    // Offscreen only while the band shows, so the fade takes the page's own pixels (the sky behind stays whole) and a page at
    // rest costs no extra layer. Both lambdas read the scroll position in their own phase: scrolling never recomposes the page.
    .graphicsLayer { compositingStrategy = if (scrolled() > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
    .drawWithContent {
        drawContent()
        val band = edge.toPx()
        val strength = scrollEdgeStrength(scrolled(), band)
        if (strength > 0f) drawRect(Brush.verticalGradient(*EDGE_STOPS.map { t -> t to Color.Black.copy(alpha = scrollEdgeAlpha(t, strength)) }.toTypedArray(),
            startY = 0f, endY = band), size = Size(size.width, band), blendMode = BlendMode.DstIn)
    }
    .layout { measurable, constraints ->
        val room = EDGE_SHADOW_ROOM.roundToPx()
        val placeable = measurable.measure(constraints.offset(horizontal = -2 * room))
        layout(placeable.width + 2 * room, placeable.height) { placeable.place(room, 0) }
    }
