package ai.sharemate.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 홈's idle card, its two bodies (2.15, docs/design/DESIGN_2_15.md §4). 2.14's idle card held one figure and one button in
 * 240 dp; it now lists the newest trips as rows ([IdleTripRows]) and, for a car without any trip, shows how one is recorded
 * as three step tiles ([IdleStepTiles]). The card around them is HomeAttention.kt's IdleBody; the heights are HomeFrame.kt's
 * ([IDLE_ROW], [IDLE_HAIRLINE], [SETUP_TILES], [idleCardHeight]), which HomeFrameTest holds to the 1097-dp page.
 */

/**
 * How far a row's press fill reaches into the card's 24-dp padding on each side: the words stay in line with the title row's
 * icon, and the fill still leaves them 12 dp of room.
 */
private val IDLE_ROW_BLEED = 12.dp

/** Under this much card width a row puts its day under its title, and the step tiles stack (the setup tiles' own threshold). */
private val IDLE_STACK_UNDER = 560.dp

/**
 * 최근 운행's rows (2.15 §4), newest first, a hairline ([CarDivider]) between two. Where the card is 560 dp or wider a row reads
 * across in one line: the day and the start in tabular digits (textSecondary), in a column as wide as its widest entry so the
 * titles start on one line, then the title, then the purpose chip; narrower, the day goes under the title, still in 56 dp.
 * Each row opens 운행기록 on its trip ([onOpen], the trip's id).
 */
@Composable internal fun IdleTripRows(rows: List<IdleRow>, onOpen: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < IDLE_STACK_UNDER
        val dayStyle = (if (stacked) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium).copy(fontFeatureSettings = "tnum")
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // Measured, not guessed, so the column follows the font and the font scale; a min width, so a rounding never cuts a digit.
        val dayWidth = remember(rows, dayStyle, density, stacked) {
            if (stacked) 0.dp
            else with(density) { (rows.maxOfOrNull { measurer.measure(it.day, dayStyle, maxLines = 1, softWrap = false).size.width } ?: 0).toDp() }
        }
        Column(Modifier.fillMaxWidth().bleed(IDLE_ROW_BLEED)) {
            rows.forEachIndexed { index, row ->
                if (index > 0) CarDivider(Modifier.padding(horizontal = IDLE_ROW_BLEED))
                key(row.id) { IdleTripRow(row, stacked, dayWidth, dayStyle) { onOpen(row.id) } }
            }
        }
    }
}

/**
 * One row (2.15 §4): [IDLE_ROW] tall at the least, a button that settles to .98 under the press fill, no ripple, clipped to
 * 12 dp. TalkBack hears one stop, the row's [IdleRow.spoken] (the places, the day and the start, the purpose), and the click
 * names what it does, 운행 상세 보기; the parts are drawn only.
 */
@Composable private fun IdleTripRow(row: IdleRow, stacked: Boolean, dayWidth: Dp, dayStyle: TextStyle, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().pressable(interaction, .98f).clip(RoundedCornerShape(12.dp))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = LogCopy.ROW_ACTION, onClick = onClick)
        .semantics { contentDescription = row.spoken }
        .pressFill(interaction).heightIn(min = IDLE_ROW).padding(horizontal = IDLE_ROW_BLEED), verticalAlignment = Alignment.CenterVertically) {
        val title: @Composable (Modifier) -> Unit = { modifier ->
            Text(row.title, modifier, style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (stacked) Column(Modifier.weight(1f).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            title(Modifier)
            Text(row.day, style = dayStyle, color = Scene.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text(row.day, Modifier.widthIn(min = dayWidth).clearAndSetSemantics {}, style = dayStyle, color = Scene.textSecondary, maxLines = 1)
            Spacer(Modifier.width(16.dp))
            title(Modifier.weight(1f).clearAndSetSemantics {})
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.clearAndSetSemantics {}) { PurposeChip(row.purpose) }
    }
}

/**
 * How a trip is recorded, for a car without any (2.15 §4): the three [steps] as tiles across, 12 dp apart (stacked under 560 dp
 * of card), each in the setup tiles' look ([SETUP_TILES] tall, the chip fill inside the chip border, the step disc, the title
 * over one line). They explain and lead nowhere: no chevron, no press, one TalkBack stop each that is not a button.
 */
@Composable internal fun IdleStepTiles(steps: List<IdleStep>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 12.dp
        if (maxWidth < IDLE_STACK_UNDER) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            steps.forEachIndexed { index, step -> IdleStepTile(index + 1, step, Modifier.fillMaxWidth()) }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            steps.forEachIndexed { index, step -> IdleStepTile(index + 1, step, Modifier.weight(1f)) }
        }
    }
}

/** One step tile: the [number] on its disc, 12 dp, the title (titleSmall) over its line (bodySmall textMuted, two lines above 1.05×). */
@Composable private fun IdleStepTile(number: Int, step: IdleStep, modifier: Modifier) {
    val skin = Car.skin
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.heightIn(min = SETUP_TILES).clip(shape).background(skin.chipFill).border(1.dp, skin.chipBorder, shape)
        .clearAndSetSemantics { contentDescription = HomeCopy.spokenIdleStep(number, step) }
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CarStepDisc(number, done = false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(step.title, style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(step.line, style = MaterialTheme.typography.bodySmall, color = Scene.textMuted, maxLines = carLineMaxLines(LocalDensity.current.fontScale),
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Lays the content out [x] wider than its slot on each side, reaching into the padding around it (a plain layout, no
 * intrinsics). Without a bounded width there is nothing to reach into, and the content keeps its own.
 */
private fun Modifier.bleed(x: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val side = x.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + side * 2, maxWidth = constraints.maxWidth + side * 2))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-side, 0) }
}
