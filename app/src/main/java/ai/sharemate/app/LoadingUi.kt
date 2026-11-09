package ai.sharemate.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Loading without pretending (CORPORATE_LOGBOOK 2.3): a button keeps its size and says what it is doing while real work
 * runs, and a table that is still being built shows the shape of the table instead of a nameless spinner. No artificial
 * delay anywhere; with reduced motion the skeleton is static and the activity mark stands still.
 */

/** A progress bar or ring sweeps from its previous value to the new one (spec C): 600 ms the first time, 120 ms after the card lands; 320 ms later. */
internal const val MOTION_SWEEP_FIRST_MS = 600
internal const val MOTION_SWEEP_MS = 320
internal const val MOTION_SWEEP_DELAY_MS = 120

/** The rings and bars that have already made their first sweep in this process, by key (spec C: a first appearance never replays). */
internal object IntroducedSweeps {
    private val keys = mutableSetOf<String>()
    @Synchronized fun seen(key: String): Boolean = key in keys
    @Synchronized fun mark(key: String) { keys += key }
}

/**
 * A 0..1 progress that sweeps to [target]: from empty over 600 ms the first time (starting 120 ms after the card lands),
 * from the previous value over 320 ms after that; the exact value at once under reduced motion. Read the state inside a
 * `progress = { }` lambda or a draw lambda so only the bar redraws while it moves.
 *
 * Give [key] a stable name for the ring (e.g. "home-month-ring"): once that ring has swept in this process it starts at its
 * value when the page is shown again (returning to 홈, switching a view) instead of replaying the first sweep from empty.
 */
@Composable internal fun sweepProgress(target: Float, key: String? = null): State<Float> {
    val reduce = LocalReduceMotion.current
    val clamped = target.coerceIn(0f, 1f)
    val introduced = key != null && IntroducedSweeps.seen(key)
    val value = remember { Animatable(if (reduce || introduced) clamped else 0f) }
    var landed by remember { mutableStateOf(introduced) }
    LaunchedEffect(clamped, reduce) {
        key?.let(IntroducedSweeps::mark)
        if (reduce) { value.snapTo(clamped); return@LaunchedEffect }
        val first = !landed
        landed = true
        if (!first && value.value == clamped) return@LaunchedEffect
        value.animateTo(clamped, if (first) tween(MOTION_SWEEP_FIRST_MS, delayMillis = MOTION_SWEEP_DELAY_MS, easing = FastOutSlowInEasing)
            else tween(MOTION_SWEEP_MS, easing = FastOutSlowInEasing))
    }
    return value.asState()
}

/** A small mark for real work in progress: a spinning ring, or a still dot under reduced motion (nothing pretends to move). */
@Composable internal fun ActivityMark(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = LocalContentColor.current) {
    if (LocalReduceMotion.current) Box(modifier.size(size).padding(size / 4).clip(CircleShape).background(color))
    else CircularProgressIndicator(modifier.size(size), strokeWidth = 2.dp, color = color)
}

/**
 * [PrimaryAction] that shows progress while [loading]: same 64dp height, a 20dp ring and [loadingLabel] in place of the
 * label. The button is disabled while loading so one tap cannot start the work twice; TalkBack hears the state change.
 */
@Composable internal fun LoadingAction(label: String, loading: Boolean, modifier: Modifier = Modifier, loadingLabel: String = "저장 중…",
    enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !loading, shape = RoundedCornerShape(20.dp),
        modifier = modifier.heightIn(min = 64.dp).semantics { if (loading) { stateDescription = "진행 중"; liveRegion = LiveRegionMode.Polite } },
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                ActivityMark(size = 20.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(if (loading) loadingLabel else label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * [LoadingAction] that tracks its own progress: [block] runs through [act] (so errors reach the app's error dialog) and the
 * button shows [loadingLabel] until it finishes. For work that is over in a few milliseconds the ring never has time to show.
 */
@Composable internal fun LoadingAction(label: String, loadingLabel: String, act: Act, modifier: Modifier = Modifier, enabled: Boolean = true,
    block: suspend () -> Unit) {
    var loading by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(block)
    LoadingAction(label, loading, modifier, loadingLabel, enabled) {
        if (!loading) {
            loading = true
            act { try { latest() } finally { loading = false } }
        }
    }
}

/**
 * The shape of a table that is still being computed: a header band and [rows] rows of [columns] blocks in the scene's
 * row fill, pulsing together once every 1.2 s, or static under reduced motion. One accessibility node that says what is happening.
 */
@Composable internal fun SkeletonTable(columns: Int, rows: Int, modifier: Modifier = Modifier, compact: Boolean = false, label: String = "미리보기 만드는 중") {
    val block = LocalPleosColors.current.surfaceHigh
    val header = Scene.rail
    val divider = Scene.divider
    val muted = Scene.textSecondary
    val alpha = if (LocalReduceMotion.current) 1f else {
        val pulse = rememberInfiniteTransition(label = "skeleton-table")
        val value by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Reverse), label = "skeleton-table-alpha")
        value
    }
    val rowHeight = if (compact) 64.dp else 72.dp
    val cell = if (compact) 14.dp else 16.dp
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label; liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), border = Scene.edge, color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth().background(header).padding(horizontal = 16.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(columns.coerceIn(1, 8)) { index -> Box(Modifier.weight(if (index == columns - 1) 1.8f else 1f).height(cell).alpha(alpha).background(block, RoundedCornerShape(6.dp))) }
                }
                repeat(rows.coerceIn(1, 8)) { row ->
                    HorizontalDivider(color = divider)
                    Row(Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        repeat(columns.coerceIn(1, 8)) { index ->
                            // Widths vary by row so the block reads as rows of data, not a grid of identical boxes.
                            val fraction = if (index == columns - 1) 1.8f else listOf(1f, .8f, .9f, .7f)[(row + index) % 4]
                            Box(Modifier.weight(fraction).height(cell).alpha(alpha).background(block, RoundedCornerShape(6.dp)))
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActivityMark(size = 16.dp, color = muted)
            Text(label, style = MaterialTheme.typography.labelMedium, color = muted)
        }
    }
}
