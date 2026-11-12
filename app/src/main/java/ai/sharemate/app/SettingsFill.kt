package ai.sharemate.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * 2.15 (docs/design/DESIGN_2_15.md §7): the two 설정 pages that ended in the top third of the page reach its foot. 정산·세무 gains
 * a second pair (비용 참고 beside this year's 업무사용비율, drawn by LogbookUi.kt's own cards); 직원 gains 직원별 운행 under the
 * manager, 월간's rows with a share bar each ([usageShares], [EmployeeShareCard]). [SettingsStack] sets each page's two blocks
 * so the lower one reaches the section's foot in the full window and the section scrolls as before where they are taller.
 */

// ───────────────────────── 직원별 운행 ─────────────────────────

/** One row of 설정 › 직원's 직원별 운행: 월간's row ([EmployeeUsage]) and its [share] of the month's distance, 0 to 1. */
@Immutable
internal data class UsageShare(val usage: EmployeeUsage, val share: Double) {
    /** The share as TalkBack hears it, in whole percent. */
    val percent: Int get() = (share * 100).roundToInt()
}

/**
 * The rows of 설정 › 직원's 직원별 운행 (2.15 §7; pure, unit-tested): 월간's rows ([employeeUsage]: one per person with a finished
 * trip in [trips], the trips without a known person as one row of their own, 사용자 미지정, the longest distance first), each
 * with its distance over the month's, the sum of the rows. The shares add up to 1 while the month has distance; trips without
 * distance give every row 0, and a month without a finished trip gives no row, so the card is left out. The fuel figures of
 * [EmployeeUsage] are not drawn here, so no km rate is applied.
 */
internal fun usageShares(trips: List<Trip>, drivers: List<Driver>): List<UsageShare> {
    val rows = employeeUsage(trips, drivers, 0)
    val month = rows.sumOf { it.totals.totalKm }
    return rows.map { UsageShare(it, if (month > 0) it.totals.totalKm / month else 0.0) }
}

/** The share bar's height (spec §7): the ring track's 8 dp, as 업무사용비율's bars. */
private val SHARE_BAR = 8.dp

/** Where a row's text starts in [EmployeeShareCard]: the 40-dp avatar and CarRow's 12-dp gap. The bar starts there too. */
private val SHARE_TEXT_START = 40.dp + 12.dp

/**
 * 설정 › 직원's second card (2.15 §7), across the page under the manager: 직원별 운행 for the month [landingMonth] gives, named
 * by the [month] tag (TalkBack hears [SettingsCopy.usageMonthSpoken]). A row per [UsageShare], as 월간's card writes it (the
 * person's label over [SettingsCopy.usageLine], the total distance at the end), led by the person's avatar as the manager's
 * list above draws it (the person glyph for 사용자 미지정), with the share bar under the words: the row's distance over the
 * month's on the ring-track token, 8 dp, in the chart ink, or in the negative ink for 사용자 미지정, which 운행기록 asks to fix,
 * as 업무사용비율 draws 미분류. A share above 0 shows at least 2 % of the bar. Each row is one TalkBack stop
 * ([SettingsCopy.usageShareSpoken]); the rows are facts, not buttons. Where a past trip's person is fixed sits in the ⓘ row,
 * as on 월간. Stretched by [SettingsStack], the card keeps its rows at the top, as every stretched card of 2.13 does.
 */
@Composable internal fun EmployeeShareCard(shares: List<UsageShare>, month: String, modifier: Modifier = Modifier) {
    val pleos = LocalPleosColors.current
    val track = Scene.ringTrack
    CarCard(modifier, title = {
        CarSectionTitle(ReportCopy.USAGE_TITLE, icon = rememberVectorPainter(Icons.Outlined.Person), trailing = {
            CarTag(month, description = SettingsCopy.usageMonthSpoken(month))
        })
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            shares.forEach { share ->
                val usage = share.usage
                key(usage.driver?.id) {
                    val name = usage.driver?.label ?: IssueType.NO_DRIVER.label
                    val line = SettingsCopy.usageLine(usage.totals.trips, usage.totals.businessKm)
                    val total = kmText(usage.totals.totalKm)
                    Column(Modifier.clearAndSetSemantics { contentDescription = SettingsCopy.usageShareSpoken(name, line, total, month, share.percent) }) {
                        CarRow(name, line = line, lead = { DriverAvatar(usage.driver, 40.dp) }, trailing = {
                            Text(total, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = Scene.text, maxLines = 1)
                        })
                        ShareBar(share.share, if (usage.driver == null) pleos.negative else pleos.chartActive, track, Modifier.padding(start = SHARE_TEXT_START))
                    }
                }
            }
        }
        CarInfoRow(ReportCopy.USAGE_NOTE)
    }
}

/** A share as a bar (decorative; the row's stop says the percent): [color] over [track], 8 dp, rounded, 2 % at least while above 0. */
@Composable private fun ShareBar(share: Double, color: Color, track: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(SHARE_BAR).clip(RoundedCornerShape(SHARE_BAR / 2)).background(track)) {
        if (share > 0) Box(Modifier.fillMaxWidth(share.toFloat().coerceIn(.02f, 1f)).fillMaxHeight().background(color))
    }
}

// ───────────────────────── the page's foot ─────────────────────────

/**
 * What a 설정 section's blocks may fill without scrolling, in pixels (pure, unit-tested): the [pane]'s height less what
 * SettingsPane sets above and under a section, each rounded as the layout rounds it: the sub-tabs (CarSegmented's 56-dp
 * segments inside 5 dp of padding), the 16-dp gap under them and the section's 8-dp foot. In the FULL window at 1× the pane
 * is 1097 dp, so 1007. An unbounded pane gives 0, so nothing stretches.
 */
internal fun Density.settingsFillPx(pane: Dp): Int {
    if (!pane.value.isFinite()) return 0
    val tabs = 2 * 5.dp.roundToPx() + 56.dp.roundToPx()
    return (pane.roundToPx() - tabs - 16.dp.roundToPx() - 8.dp.roundToPx()).coerceAtLeast(0)
}

/**
 * A 설정 section's two blocks (2.15 §7), [top] over [bottom] [gap] apart, the lower one at least as tall as the room the
 * [pane] leaves under the upper one ([settingsFillPx]), so the page ends at its foot in the full window instead of in its top
 * third. A pair of cards side by side ([SideBySide]'s equal-height row) takes the height and both cards fill it; a single card
 * stretches with its content at the top. Where the blocks are taller than the room the lower one keeps its own height and
 * the section scrolls as before. A small Layout rather than a weighted Column: the section scrolls, so a Column there has no
 * height to share, and nothing here asks a block for its intrinsic size (the 직원 manager holds a BoxWithConstraints). Each
 * slot emits one node; [bottom] null leaves [top] alone, without the gap. [top] keeps its place in the composition either
 * way, so the manager's editor keeps what is typed in it when the first finished trip brings 직원별 운행 in under it.
 */
@Composable internal fun SettingsStack(pane: Dp, gap: Dp, top: @Composable () -> Unit, bottom: (@Composable () -> Unit)?) {
    Layout(contents = listOf(top, bottom ?: {})) { (uppers, lowers), constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val above = uppers.map { it.measure(loose) }
        val gapPx = if (lowers.isEmpty()) 0 else gap.roundToPx()
        val room = (settingsFillPx(pane) - above.sumOf { it.height } - gapPx).coerceAtLeast(0)
        // The room goes to the lower block when it is one node; several (never in 설정) stack at their own heights.
        val below = lowers.map { it.measure(if (lowers.size == 1) loose.copy(minHeight = room) else loose) }
        val width = (above + below).maxOfOrNull { it.width }?.coerceIn(constraints.minWidth, constraints.maxWidth) ?: constraints.minWidth
        val height = above.sumOf { it.height } + gapPx + below.sumOf { it.height }
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            above.forEach { it.placeRelative(0, y); y += it.height }
            y += gapPx
            below.forEach { it.placeRelative(0, y); y += it.height }
        }
    }
}
