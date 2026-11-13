package ai.sharemate.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * 2.15 (docs/design/DESIGN_2_15.md §7, replaced where it was hollow by §9.5): the two 설정 pages that ended in the top third of
 * the page reach its foot, and what reaches it is content. 직원: before the first employee, three tiles in the empty hero's look
 * under a small heading say what registering does ([DriverIntroTiles]); from the first one, 직원별 운행 lists every employee with
 * a share bar and fills the height it is stretched to with the month's facts ([EmployeeShareCard]). 정산·세무: a second pair,
 * 비용 참고 beside this year's 업무사용비율 (LogbookUi.kt's cards), the tax card drawing its comparison as bars ([TaxFill]).
 * [SettingsStack] sets each page's two blocks so the lower one reaches the section's foot in the full window and the section
 * scrolls as before where they are taller.
 */

// ───────────────────────── tiles in the hero's look ─────────────────────────

/** A tile across the page: the empty hero's TALL tile (2.14's tall destination card), at least. */
internal val SETTINGS_TILE_MIN = 208.dp

/** The most a tile grows to with the room (§9.1's hero rule): past it, the scene keeps the height. */
internal val SETTINGS_TILE_MAX = 360.dp

/** From this height a tile takes the larger look (§9.1): the 88-dp accent square with a 44-dp glyph, the title in headlineSmall. */
internal val SETTINGS_TILE_BIG = 300.dp

/** A stacked tile, or a short fact tile: the hero's COMPACT tile. */
internal val SETTINGS_TILE_COMPACT = 104.dp

/** The band §9.1 leaves the scene's city under tiles that stand on the scene. */
internal val SETTINGS_CITY_BAND = 200.dp

/** The effective page (its width ÷ the font scale) from which three tiles stand across, the hero's own threshold. */
internal val SETTINGS_TILES_ACROSS_FROM = 900.dp

/**
 * Whether three tiles stand across a block [width] wide at [fontScale] (pure, unit-tested): from [SETTINGS_TILES_ACROSS_FROM]
 * of effective width, as the empty hero decides; below it they stack in the COMPACT look. A font under 1× counts as 1×.
 */
internal fun settingsTilesAcross(width: Dp, fontScale: Float): Boolean = width / fontScale.coerceAtLeast(1f) >= SETTINGS_TILES_ACROSS_FROM

/**
 * How tall the tiles across stand on the scene in a block [room] tall whose heading takes [heading] with its gap (pure,
 * unit-tested; §9.1's rule for the hero): the rest less [SETTINGS_CITY_BAND] for the city, held between [SETTINGS_TILE_MIN]
 * and [SETTINGS_TILE_MAX]. In the FULL window at 1×, 직원 before its first employee leaves 623 dp under the 368-dp manager:
 * 623 − 42 − 200 = 381, so the tiles are 360 tall and take the larger look ([SETTINGS_TILE_BIG]).
 */
internal fun settingsTileHeight(room: Dp, heading: Dp): Dp = (room - heading - SETTINGS_CITY_BAND).coerceIn(SETTINGS_TILE_MIN, SETTINGS_TILE_MAX)

// ───────────────────────── 직원, before the first employee ─────────────────────────

/**
 * 설정 › 직원 before the first employee (2.15 §9.5): under the manager, on the scene, the small heading 직원을 등록하면 (a heading
 * for TalkBack) and three tiles in the empty hero's look that say what registering does: ④ 사용자 칸, 출발 전 확인 and 유류비 정산
 * ([SettingsCopy.DRIVERS_INTRO_TILES]). They explain and lead nowhere: no chevron, no press, one stop each that is not a button.
 * Across the page ([settingsTilesAcross]) they grow with the room SettingsStack hands the block, its minimum height
 * ([settingsTileHeight]); stacked, they keep the COMPACT look. A BoxWithConstraints: SettingsStack and the section's scroll never
 * ask this block for its intrinsic size.
 */
@Composable internal fun DriverIntroTiles(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val headingStyle = MaterialTheme.typography.titleMedium
    val glyphs = listOf(rememberVectorPainter(Icons.Outlined.Person), rememberVectorPainter(Icons.Outlined.CheckCircle), painterResource(R.drawable.ic_won))
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val across = settingsTilesAcross(maxWidth, fontScale)
        // The heading's line at the font in effect and the 12 dp under it.
        val tile = settingsTileHeight(minHeight, with(density) { headingStyle.lineHeight.toDp() } + 12.dp)
        Column(Modifier.fillMaxWidth().semantics { isTraversalGroup = true }) {
            Text(SettingsCopy.DRIVERS_INTRO, Modifier.semantics { heading() }, style = headingStyle, color = Scene.text,
                maxLines = carLineMaxLines(fontScale), overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            val tiles = SettingsCopy.DRIVERS_INTRO_TILES.zip(SettingsCopy.SPOKEN_DRIVERS_INTRO_TILES).zip(glyphs)
            if (across) Row(Modifier.fillMaxWidth().height(tile), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                tiles.forEach { (words, glyph) ->
                    SettingsTile(words.first.first, words.first.second, glyph, words.second, tall = true, big = tile >= SETTINGS_TILE_BIG,
                        modifier = Modifier.weight(1f).fillMaxHeight())
                }
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                tiles.forEach { (words, glyph) ->
                    SettingsTile(words.first.first, words.first.second, glyph, words.second, tall = false, big = false, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * A tile in the empty hero's look (§2.2, §9.1), written here so the hero's own file stays its package's: a [CarCard] that
 * TalkBack meets as one stop ([spoken]) and that is not a button. [tall]: the content centred in the card's height, the
 * [glyph] on the accent square (56 dp, or 88 when [big]), 16 dp, the [title] (titleLarge, or headlineSmall when [big]), 6 dp,
 * one centred [CarLine]. Otherwise the COMPACT look: the square at the start, the title (titleMedium) over its line.
 */
@Composable private fun SettingsTile(title: String, line: String, glyph: Painter, spoken: String, tall: Boolean, big: Boolean, modifier: Modifier) {
    CarCard(modifier, description = spoken, padding = 0.dp) {
        if (tall) Box(Modifier.fillMaxWidth().fillMaxHeight().heightIn(min = SETTINGS_TILE_MIN)) {
            Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                AccentSquare(glyph, big)
                Spacer(Modifier.height(16.dp))
                Text(title, style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, color = Scene.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                CarLine(line)
            }
        } else Row(Modifier.fillMaxWidth().heightIn(min = SETTINGS_TILE_COMPACT).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AccentSquare(glyph, big = false)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CarLine(line)
            }
        }
    }
}

/** A tile's glyph on its accent square (corner 16, 20 when [big]), onAccent (4.93), as on 홈's tall destination cards. Decorative. */
@Composable private fun AccentSquare(glyph: Painter, big: Boolean) {
    val skin = Car.skin
    Box(Modifier.size(if (big) 88.dp else 56.dp).clip(RoundedCornerShape(if (big) 20.dp else 16.dp)).background(skin.accent), contentAlignment = Alignment.Center) {
        Icon(glyph, contentDescription = null, Modifier.size(if (big) 44.dp else 28.dp), tint = skin.onAccent)
    }
}

// ───────────────────────── 직원별 운행 ─────────────────────────

/** One row of 설정 › 직원's 직원별 운행: 월간's row ([EmployeeUsage]) and its [share] of the month's distance, 0 to 1. */
@Immutable
internal data class UsageShare(val usage: EmployeeUsage, val share: Double) {
    /** The share as TalkBack hears it, in whole percent. */
    val percent: Int get() = (share * 100).roundToInt()

    /** A registered employee without a finished trip in the month: "운행 없음", an empty track, muted. */
    val none: Boolean get() = usage.totals.trips == 0
}

/**
 * The rows of 설정 › 직원's 직원별 운행 (2.15 §9.5, which replaces §7's; pure, unit-tested): every registered employee in
 * [drivers], the longest distance in [trips] (the month's) first and those at a tie in the order they were registered, with
 * zeros where they have no finished trip; then 사용자 미지정 (trips without a person, or whose person was deleted) when there is
 * such a trip. Each row's share is its distance over the month's, so the shares add up to 1 while the month has distance; a
 * month without distance gives every row 0. The figures are 월간's ([employeeUsage]); the fuel figures are not drawn here.
 */
internal fun employeeShares(trips: List<Trip>, drivers: List<Driver>): List<UsageShare> {
    val usage = employeeUsage(trips, drivers, 0)
    val people = drivers.map { driver -> usage.firstOrNull { it.driver?.id == driver.id } ?: EmployeeUsage(driver, logbookTotals(emptyList()), 0, 0) }
        .sortedByDescending { it.totals.totalKm }
    val rows = people + usage.filter { it.driver == null }
    val month = rows.sumOf { it.totals.totalKm }
    return rows.map { UsageShare(it, if (month > 0) it.totals.totalKm / month else 0.0) }
}

/** Which fact of the month a tile shows; the composable gives each its glyph. */
internal enum class ShareFactKind { TRIPS, DISTANCE, RATIO }

/** One fact of the month in 직원별 운행: the [figure] is the tile's title, the [label] its line, [spoken] its one TalkBack stop. */
@Immutable
internal data class ShareFact(val figure: String, val label: String, val kind: ShareFactKind, val spoken: String)

/**
 * The three facts of the month that fill 직원별 운행's stretched height (2.15 §9.5; pure, unit-tested), as the empty hero's
 * ISSUES tiles say them: the count / 운행, the distance / 총주행, the ratio / 업무사용비율, of the finished trips in [trips].
 * Before the first trip they read 0건, 0.0 km and –; TalkBack hears "업무사용비율 없음" for the dash.
 */
internal fun monthFacts(trips: List<Trip>): List<ShareFact> {
    val totals = logbookTotals(trips.filterNot { it.open })
    val trip = "${totals.trips}건"
    val km = kmText(totals.totalKm)
    val ratio = ratioText(totals.ratio)
    return listOf(
        ShareFact(trip, LogCopy.FACT_TRIPS, ShareFactKind.TRIPS, "${LogCopy.FACT_TRIPS} $trip"),
        ShareFact(km, LogCopy.FACT_DISTANCE, ShareFactKind.DISTANCE, "${LogCopy.FACT_DISTANCE} $km"),
        ShareFact(ratio, LogCopy.FACT_RATIO, ShareFactKind.RATIO, "${LogCopy.FACT_RATIO} ${if (totals.ratio == null) SettingsCopy.SPOKEN_FACT_NONE else ratio}"),
    )
}

/**
 * How the facts of the month stand in 직원별 운행 for the height [free] the card leaves them under its rows (pure, unit-tested):
 * none under [SETTINGS_TILE_COMPACT]; SHORT tiles (the figure over its label) from there; the hero's TALL look from
 * [SETTINGS_TILE_MIN] (§9.5's 208 dp); its larger look from [SETTINGS_TILE_BIG].
 */
internal enum class ShareFactsLook { NONE, SHORT, TALL, BIG }

internal fun shareFactsLook(free: Dp): ShareFactsLook = when {
    free >= SETTINGS_TILE_BIG -> ShareFactsLook.BIG
    free >= SETTINGS_TILE_MIN -> ShareFactsLook.TALL
    free >= SETTINGS_TILE_COMPACT -> ShareFactsLook.SHORT
    else -> ShareFactsLook.NONE
}

/** The facts' height in [free] (pure, unit-tested): all of it, up to [SETTINGS_TILE_MAX]; null where they do not stand. */
internal fun shareFactsHeight(free: Dp): Dp? = if (shareFactsLook(free) == ShareFactsLook.NONE) null else free.coerceAtMost(SETTINGS_TILE_MAX)

/** The share bar's height (spec §7): the ring track's 8 dp, as 업무사용비율's bars. */
private val SHARE_BAR = 8.dp

/** Where a row's text starts in [EmployeeShareCard]: the 40-dp avatar and CarRow's 12-dp gap. The bar starts there too. */
private val SHARE_TEXT_START = 40.dp + 12.dp

/**
 * 설정 › 직원's second card (2.15 §7, §9.5), across the page under the manager from the first employee on: 직원별 운행 for the
 * month [landingMonth] gives, named by the [month] tag (TalkBack hears [SettingsCopy.usageMonthSpoken]), or 아직 운행 없음 where
 * [month] is null, before the first finished trip. A row per [UsageShare] ([employeeShares]: every registered employee, then
 * 사용자 미지정), as 월간's card writes it (the person's label over [SettingsCopy.usageLine], the total distance at the end), led
 * by the person's avatar as the manager's list above draws it (the person glyph for 사용자 미지정), with the share bar under the
 * words: the row's distance over the month's on the ring-track token, 8 dp, in the chart ink, or in the negative ink for 사용자
 * 미지정, as 업무사용비율 draws 미분류. A share above 0 shows at least 2 % of the bar; an employee without trips reads 운행 없음 over
 * an empty track, muted. Each row is one TalkBack stop; the rows are facts, not buttons.
 *
 * Stretched by [SettingsStack] to the page's foot, the card uses the height (§9.5): its own height is the room or its content,
 * whichever is taller (`IntrinsicSize.Min`, which nothing in it refuses), the ⓘ row (where a past trip's person is fixed) sits
 * at its foot, and the height left under the rows holds the month's [facts] as three tiles ([shareFactsLook]). The card's body
 * ([ShareFillPolicy]) makes that call from the height it measures and hands it back, so the tiles are composed in the look that
 * fits and the decision never depends on them.
 */
@Composable internal fun EmployeeShareCard(shares: List<UsageShare>, facts: List<ShareFact>, month: String?, modifier: Modifier = Modifier) {
    val pleos = LocalPleosColors.current
    val track = Scene.ringTrack
    var look by remember { mutableStateOf(ShareFactsLook.NONE) }
    val policy = remember { ShareFillPolicy { decided -> look = decided } }
    val tag = month ?: SettingsCopy.USAGE_NO_TRIP_YET
    CarCard(modifier.height(IntrinsicSize.Min), title = {
        CarSectionTitle(ReportCopy.USAGE_TITLE, icon = rememberVectorPainter(Icons.Outlined.Person), trailing = {
            CarTag(tag, description = month?.let { SettingsCopy.usageMonthSpoken(it) } ?: tag)
        })
    }) {
        Layout(contents = listOf(
            {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    shares.forEach { share -> key(share.usage.driver?.id) { ShareRow(share, month, pleos.negative, pleos.chartActive, track) } }
                }
            },
            {
                val shown = look
                if (shown != ShareFactsLook.NONE) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    facts.forEach { fact -> ShareFactTile(fact, shown, Modifier.weight(1f).fillMaxHeight()) }
                }
            },
            { CarInfoRow(ReportCopy.USAGE_NOTE) },
        ), modifier = Modifier.weight(1f).fillMaxWidth(), measurePolicy = policy)
    }
}

/** One row of [EmployeeShareCard]: see there. */
@Composable private fun ShareRow(share: UsageShare, month: String?, unassigned: Color, ink: Color, track: Color) {
    val usage = share.usage
    val name = usage.driver?.label ?: IssueType.NO_DRIVER.label
    val line = if (share.none) SettingsCopy.USAGE_NONE else SettingsCopy.usageLine(usage.totals.trips, usage.totals.businessKm)
    val total = kmText(usage.totals.totalKm)
    val spoken = if (share.none) SettingsCopy.usageNoneSpoken(name, month)
        else SettingsCopy.usageShareSpoken(name, line, total, month.orEmpty(), share.percent)
    Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
        CarRow(name, line = line, lead = { DriverAvatar(usage.driver, 40.dp) }, titleColor = if (share.none) Scene.textSecondary else Scene.text, trailing = {
            Text(total, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = if (share.none) Scene.textMuted else Scene.text,
                maxLines = 1)
        })
        ShareBar(share.share, if (usage.driver == null) unassigned else ink, track, Modifier.padding(start = SHARE_TEXT_START))
    }
}

/** A share as a bar (decorative; the row's stop says the percent): [color] over [track], 8 dp, rounded, 2 % at least while above 0. */
@Composable private fun ShareBar(share: Double, color: Color, track: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(SHARE_BAR).clip(RoundedCornerShape(SHARE_BAR / 2)).background(track)) {
        if (share > 0) Box(Modifier.fillMaxWidth(share.toFloat().coerceIn(.02f, 1f)).fillMaxHeight().background(color))
    }
}

/**
 * A fact of the month inside 직원별 운행: a tile on the chip fill inside the chip border (corner 16, the setup tiles' look, since
 * it stands on a card), its content centred: in the TALL and BIG looks the glyph on the accent square (56 or 88 dp), 16 dp;
 * then the figure (titleLarge, headlineSmall in the BIG look, tabular digits) over its label ([CarLine]). One TalkBack stop
 * ([ShareFact.spoken]), not a button.
 */
@Composable private fun ShareFactTile(fact: ShareFact, look: ShareFactsLook, modifier: Modifier) {
    val skin = Car.skin
    val shape = RoundedCornerShape(16.dp)
    val big = look == ShareFactsLook.BIG
    val glyph = when (fact.kind) {
        ShareFactKind.TRIPS -> painterResource(R.drawable.ic_journal)
        ShareFactKind.DISTANCE -> painterResource(R.drawable.ic_gauge)
        ShareFactKind.RATIO -> painterResource(R.drawable.ic_report)
    }
    Box(modifier.clip(shape).background(skin.chipFill).border(1.dp, skin.chipBorder, shape).clearAndSetSemantics { contentDescription = fact.spoken },
        contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (look != ShareFactsLook.SHORT) {
                AccentSquare(glyph, big)
                Spacer(Modifier.height(16.dp))
            }
            val style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
            Text(fact.figure, style = style.copy(fontFeatureSettings = "tnum"), color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(if (look == ShareFactsLook.SHORT) 4.dp else 6.dp))
            CarLine(fact.label)
        }
    }
}

/**
 * [EmployeeShareCard]'s body: the rows at the top, the ⓘ row at the foot, and between them, 16 dp under the rows, the facts at
 * [shareFactsHeight] of what is left, where they stand ([shareFactsLook]); the decision goes to [onLook] each time the body is
 * measured, and the facts' own content never changes it. The card gives the body the rest of its height (the weight's share of
 * a card measured at its room). Asked for its intrinsic height, the body answers with the rows, the gap and the ⓘ row alone,
 * so the card's own least height leaves the facts out.
 */
private class ShareFillPolicy(private val onLook: (ShareFactsLook) -> Unit) : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val (rows, facts, info) = measurables
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val loose = Constraints(minWidth = width, maxWidth = width)
        val gap = 16.dp.roundToPx()
        val top = rows.map { it.measure(loose) }
        val foot = info.map { it.measure(loose) }
        val used = top.sumOf { it.height } + gap + foot.sumOf { it.height }
        val height = if (constraints.hasBoundedHeight) maxOf(constraints.maxHeight, used) else maxOf(constraints.minHeight, used)
        val free = (height - used - gap).coerceAtLeast(0)
        val look = shareFactsLook(free.toDp())
        onLook(look)
        val tile = shareFactsHeight(free.toDp())?.roundToPx()?.coerceAtMost(free)
        val drawn = if (tile == null) emptyList() else facts.map { it.measure(Constraints.fixed(width, tile)) }
        return layout(width, height) {
            var y = 0
            top.forEach { placeable -> placeable.place(0, y); y += placeable.height }
            drawn.forEach { it.place(0, y + gap) }
            foot.forEach { it.place(0, height - it.height) }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<List<IntrinsicMeasurable>>, width: Int): Int =
        measurables[0].sumOf { it.minIntrinsicHeight(width) } + 16.dp.roundToPx() + measurables[2].sumOf { it.minIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<List<IntrinsicMeasurable>>, width: Int): Int =
        measurables[0].sumOf { it.maxIntrinsicHeight(width) } + 16.dp.roundToPx() + measurables[2].sumOf { it.maxIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<List<IntrinsicMeasurable>>, height: Int): Int = 0
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<List<IntrinsicMeasurable>>, height: Int): Int = 0
}

// ───────────────────────── 비용 참고 in 정산·세무 ─────────────────────────

/** Which amount a bar of [TaxFill] stands for. */
internal enum class TaxBarKind { WITHOUT_LOG, WITH_LOG, LIMIT }

/** One bar of 비용 참고's comparison: its [label], the amount at its end ([won]) and its length, [fraction] of the larger. */
@Immutable
internal data class TaxBar(val label: String, val won: Long, val fraction: Float, val kind: TaxBarKind)

/**
 * 비용 참고's bars in 설정 (2.15 §9.5; pure, unit-tested). With a yearly [cost] and this year's [ratio], the comparison: 기록부
 * 없을 때 and 업무 비율 적용액 ([taxEffect]), the larger at full width and the other as its share of it (both 0 where both are 0).
 * Before the comparison can be made (no cost yet, or no trip this year), one bar at full width for the most a car without a
 * logbook is granted a year ([NO_LOGBOOK_CAP_WON], [SettingsCopy.TAX_LIMIT]).
 */
internal fun taxBars(cost: Long?, ratio: Double?): List<TaxBar> {
    if (cost == null || ratio == null) return listOf(TaxBar(SettingsCopy.TAX_LIMIT, NO_LOGBOOK_CAP_WON, 1f, TaxBarKind.LIMIT))
    val effect = taxEffect(cost, ratio)
    val top = maxOf(effect.withoutLogWon, effect.withLogWon)
    fun length(won: Long) = if (top > 0) (won.toDouble() / top).toFloat() else 0f
    return listOf(TaxBar(ReportCopy.TAX_WITHOUT_LOG, effect.withoutLogWon, length(effect.withoutLogWon), TaxBarKind.WITHOUT_LOG),
        TaxBar(ReportCopy.TAX_WITH_LOG, effect.withLogWon, length(effect.withLogWon), TaxBarKind.WITH_LOG))
}

/** The comparison's one TalkBack stop (pure, unit-tested): each bar's label and amount, in order. */
internal fun taxBarsSpoken(bars: List<TaxBar>): String = bars.joinToString(", ") { SettingsCopy.taxBarSpoken(it.label, it.won) }

/** A bar of [TaxFill]: 24 dp on the ring-track token (§9.5). */
private val TAX_BAR = 24.dp

/**
 * 비용 참고 in 설정 › 정산·세무 (2.15 §9.5), where TaxCard (LogbookUi.kt) is stretched beside 업무사용비율: what the card says
 * as it always does (the [blocker] line, or the money rows of the yearly [cost] at this year's [ratio], drawn as TaxCard draws
 * them), then the comparison as bars ([taxBars]) and, at the card's foot, the 참고 계산 ⓘ row. Stretched, the three blocks
 * spread over the card's height, the bars in the middle; at their own height (one up) they stand 24 dp apart. Before a yearly
 * cost the one bar for the limit comes with its line, [ReportCopy.TAX_CAP_NOTE] (what the cost covers behind ⓘ). 월간 keeps the
 * card as it was: only 설정 asks for this.
 */
@Composable internal fun TaxFill(cost: Long?, ratio: Double?, blocker: String?) {
    val bars = remember(cost, ratio) { taxBars(cost, ratio) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (blocker != null) {
                Text(blocker, style = MaterialTheme.typography.titleSmall, color = Scene.text)
                if (cost != null) CarMoneyRow(ReportCopy.TAX_COST, cost)
            } else if (cost != null && ratio != null) {
                val effect = taxEffect(cost, ratio)
                Column {
                    CarMoneyRow(ReportCopy.TAX_COST, cost)
                    CarMoneyRow(ReportCopy.TAX_WITHOUT_LOG, effect.withoutLogWon)
                    CarDivider()
                    CarMoneyRow(ReportCopy.TAX_WITH_LOG, effect.withLogWon, line = taxDifferenceLine(effect.differenceWon, ratio), total = true,
                        tone = if (effect.differenceWon > 0) MoneyTone.POSITIVE else MoneyTone.NEUTRAL)
                }
            }
        }
        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TaxBars(bars)
            if (bars.any { it.kind == TaxBarKind.LIMIT }) CarInfoRow(ReportCopy.TAX_CAP_NOTE, more = ReportCopy.TAX_CAP_MORE)
        }
        CarInfoRow(ReportCopy.TAX_NOTE, Modifier.padding(top = 24.dp), more = ReportCopy.TAX_NOTE_MORE)
    }
}

/**
 * The bars of [TaxFill], one TalkBack stop ([taxBarsSpoken]): each its label (bodyMedium) with the amount at the bar's end in
 * the money style ([CarAmount], ROW, neutral: the card's one tone stays its total row's), 8 dp, then the bar, 24 dp on the
 * ring-track token, rounded: 기록부 없을 때 and the limit in the 개인 bar's grey, 업무 비율 적용액 in the chart ink, as
 * 업무사용비율 draws its kinds. A length above 0 shows at least 2 % of the track.
 */
@Composable private fun TaxBars(bars: List<TaxBar>) {
    val pleos = LocalPleosColors.current
    val track = Scene.ringTrack
    val spoken = taxBarsSpoken(bars)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        bars.forEach { bar ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(bar.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Scene.text, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(12.dp))
                    CarAmount(bar.won)
                }
                val color = if (bar.kind == TaxBarKind.WITH_LOG) pleos.chartActive else pleos.textTertiary
                Box(Modifier.fillMaxWidth().height(TAX_BAR).clip(RoundedCornerShape(TAX_BAR / 2)).background(track)) {
                    if (bar.fraction > 0f) Box(Modifier.fillMaxWidth(bar.fraction.coerceIn(.02f, 1f)).fillMaxHeight().background(color))
                }
            }
        }
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
