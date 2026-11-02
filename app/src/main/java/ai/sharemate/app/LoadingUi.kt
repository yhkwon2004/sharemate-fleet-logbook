package ai.sharemate.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/*
 * Loading without pretending (CORPORATE_LOGBOOK 2.3): a button keeps its size and says what it is doing while real work
 * runs, and a table that is still being built shows the shape of the table instead of a nameless spinner. No artificial
 * delay anywhere; with reduced motion the skeleton is static.
 */

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
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = LocalContentColor.current)
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
 * The shape of a table that is still being computed: a header band and [rows] rows of [columns] blocks in surfaceHigh,
 * pulsing together once every 1.2 s, or static under reduced motion. One accessibility node that says what is happening.
 */
@Composable internal fun SkeletonTable(columns: Int, rows: Int, modifier: Modifier = Modifier, compact: Boolean = false, label: String = "미리보기 만드는 중") {
    val colors = MaterialTheme.colorScheme
    val block = LocalPleosColors.current.surfaceHigh
    val alpha = if (LocalReduceMotion.current) 1f else {
        val pulse = rememberInfiniteTransition(label = "skeleton-table")
        val value by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Reverse), label = "skeleton-table-alpha")
        value
    }
    val rowHeight = if (compact) 64.dp else 72.dp
    val cell = if (compact) 14.dp else 16.dp
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label; liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, colors.outlineVariant), color = colors.surface) {
            Column {
                Row(Modifier.fillMaxWidth().background(colors.primaryContainer.copy(alpha = .5f)).padding(horizontal = 16.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(columns.coerceIn(1, 8)) { index -> Box(Modifier.weight(if (index == columns - 1) 1.8f else 1f).height(cell).alpha(alpha).background(block, RoundedCornerShape(6.dp))) }
                }
                repeat(rows.coerceIn(1, 8)) { row ->
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
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
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}
