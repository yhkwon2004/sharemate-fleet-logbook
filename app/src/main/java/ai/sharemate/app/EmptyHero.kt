package ai.sharemate.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.compose.ui.unit.isUnspecified
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/*
 * The empty hero (2.15, docs/design/DESIGN_2_15.md §2.2, corrected by §9.1–§9.3). Where 운행기록's list is empty, 2.13 put one
 * small card in the corner of an empty page, and the driver read the page as "빈". The hero takes the whole row under the
 * page's header instead: a title on the scene like 홈's greeting, at most one line under it, up to three large tiles in the look
 * of 홈's tall destination cards, grown to the room the page gives, and big buttons, so the page is filled by weighty elements
 * and very little text. 월간·Excel shows it without a title where the period has no trip. What it says is one pure decision
 * ([logEmptyHero], [reportEmptyHero]); the composable only draws it.
 */

/** A hero tile across the page at its least: the least height of 홈's TALL destination card (2.14's 208 dp), whose look it takes. */
internal val HERO_TILE_TALL = 208.dp

/** A stacked hero tile, and every month row: the least height of 홈's COMPACT destination card. */
internal val HERO_TILE_COMPACT = 104.dp

/** The tallest a tile across grows with the room the page gives (2.15 §9.1). */
internal val HERO_TILE_MAX = 360.dp

/** From this height a tile across carries the larger square and glyph and its title in headlineSmall (2.15 §9.1). */
internal val HERO_TILE_LARGE_FROM = 300.dp

/** The band of the page the tiles leave under the hero for the scene's city (2.15 §9.1). */
internal val HERO_CITY_BAND = 200.dp

/** The gap between the hero's blocks: the title with its line, the tiles, the actions. */
internal val HERO_BLOCK_GAP = 24.dp

/** The gap between the title and its line. */
internal val HERO_LINE_GAP = 8.dp

/** The actions' row: a [PrimaryAction]'s 56 dp, a [CarPillButton]'s 56-dp target, one row across the page. */
internal val HERO_ACTION_ROW = 56.dp

/** A tile's accent square and its glyph, as on 홈's tall destination cards (corner 16), and from [HERO_TILE_LARGE_FROM] up (corner 24). */
private val HERO_TILE_ICON = 56.dp
private val HERO_TILE_GLYPH = 28.dp
private val HERO_TILE_ICON_LARGE = 88.dp
private val HERO_TILE_GLYPH_LARGE = 44.dp

/** The effective page (its width ÷ the font scale) from which three tiles stand across, the split page's own threshold. */
internal val HERO_TILES_ACROSS_FROM = 900.dp

/** The three purposes the third first-trip step shows, as the list draws them ([PurposeChip]). */
private val HERO_PURPOSES = listOf(TripPurpose.COMMUTE, TripPurpose.BUSINESS, TripPurpose.PERSONAL)

/**
 * Whether the hero's tiles stand across the page (pure, unit-tested): from [HERO_TILES_ACROSS_FROM] of effective page, as
 * [logListWidth] splits the page; below it they stack in the COMPACT look. A font smaller than 1× counts as 1×.
 */
internal fun heroTilesAcross(page: Dp, fontScale: Float): Boolean = page / fontScale.coerceAtLeast(1f) >= HERO_TILES_ACROSS_FROM

/**
 * The hero's own height besides its tiles (pure, unit-tested; 2.15 §9.1): the [title]'s line, then [HERO_LINE_GAP] and the
 * [line]'s where there is one, the action row ([HERO_ACTION_ROW]) where there are [actions], and [HERO_BLOCK_GAP] between the
 * blocks present (the title with its line, the [tiles], the actions). [CarEmptyHero] passes the styles' line heights at the
 * font in use: at 1× headlineMedium's 40 and bodyLarge's 30, so FIRST's chrome is 40 + 8 + 30 + 24 + 24 + 56 = 182 dp and
 * MATCHED's (no line) 144. The text as drawn trims its first line's top and its last line's foot, so the FULL window's capture
 * of the first build measures about 172 dp: the estimate errs by about 10 dp toward a shorter tile, never toward a scroll.
 */
internal fun heroChrome(title: Dp?, line: Dp?, tiles: Boolean, actions: Boolean): Dp {
    val header = (title ?: 0.dp) + (line?.let { HERO_LINE_GAP + it } ?: 0.dp)
    val blocks = listOf(header > 0.dp, tiles, actions).count { it }
    return header + (if (actions) HERO_ACTION_ROW else 0.dp) + HERO_BLOCK_GAP * (blocks - 1).coerceAtLeast(0)
}

/**
 * The height of the tiles across (pure, unit-tested; 2.15 §9.1): the hero's bounded height [room] less its own [chrome]
 * ([heroChrome]) and less [HERO_CITY_BAND] for the city, held between [HERO_TILE_TALL] and [HERO_TILE_MAX]. A hero whose
 * height is not bounded (월간·Excel's, inside the pane's scroll) keeps [HERO_TILE_TALL]. In the FULL window at 1× 운행기록
 * gives the hero the 1097-dp page less the 66-dp header row and the 16-dp gap, 1015 dp, or 971 under the 32-dp tag row and
 * its 12-dp gap: 1015 − 182 − 200 = 633, held at 360, so FIRST is 182 + 360 = 542 dp there and never scrolls.
 */
internal fun heroTileHeight(room: Dp, chrome: Dp): Dp =
    if (room.isUnspecified || !room.isFinite) HERO_TILE_TALL else (room - chrome - HERO_CITY_BAND).coerceIn(HERO_TILE_TALL, HERO_TILE_MAX)

/** Whether a tile across of [height] takes the larger square and headlineSmall (pure, unit-tested): from [HERO_TILE_LARGE_FROM]. */
internal fun heroTileLarge(height: Dp): Boolean = height >= HERO_TILE_LARGE_FROM

/**
 * Whether 운행기록's empty list takes both panes of the split page (pure, unit-tested; 2.15 §9.2): only while no trip is
 * [showing]. With an empty list nothing follows the list ([logTopId] is null), so a trip shows only where the driver pinned it,
 * editing it in the pane (the last 점검 필요 fixed there): the detail then keeps its pane, with what it holds (a typed amount,
 * a note), and the hero stands in the list column, stacked. Narrow ([twoPane] false) the hero takes the list's place under the
 * side sheet.
 */
internal fun logHeroAcross(twoPane: Boolean, empty: Boolean, showing: Boolean): Boolean = twoPane && empty && !showing

/** Which empty list the hero stands for, the rows of §2.2's table. */
internal enum class EmptyHeroCase { FIRST, MONTH, EMPLOYEE, ISSUES, MATCHED }

/** A tile's glyph, drawn onAccent on its accent square; the model names it so it stays free of painters. */
internal enum class HeroGlyph { DRIVE, PARK, PURPOSE, MONTH, COUNT, DISTANCE, RATIO, CARD, NOTIFY, AUTO, PERSON, CLOSE, EXCEL }

/**
 * A first-trip step's small picture under its line (2.15 §9.1), decorative: the gear strip with D lit (① D로 출발), with P lit
 * (② P로 주차), the three purpose chips (③ 목적 확인). Other tiles have none.
 */
internal enum class HeroOrnament { GEAR_DRIVE, GEAR_PARK, PURPOSES }

/** What a hero tile or button does; the page that shows the hero carries it out (LogPane, ReportPane). */
internal enum class HeroAction { TUTORIAL, DEMO_SETTING, DEMO_DRIVE, PREVIOUS_MONTH, CLEAR_FILTER, OPEN_MONTH, CLOSE_MONTH, SHOW_ALL, CARD_SETTING, PICK_EMPLOYEE }

/**
 * One hero tile: its [title] (titleLarge across, headlineSmall from [HERO_TILE_LARGE_FROM], titleMedium stacked), one [line],
 * its [glyph] and what TalkBack hears, in one stop ([spoken]). A step tile carries its [step] number; a [tag] (마감됨) sits at
 * its top end, cleared for TalkBack since [spoken] says it. A tile with an [action] is a button ([clickLabel] names it; an
 * OPEN_MONTH tile carries its [month], a PICK_EMPLOYEE tile its person's [driverId] and the month to open with them, if any);
 * the others are facts, not focusable as buttons. [ornament] is a first-trip step's picture (2.15 §9.1).
 */
@Immutable
internal data class HeroTile(val title: String, val line: String, val glyph: HeroGlyph, val spoken: String, val step: Int? = null,
    val tag: String? = null, val action: HeroAction? = null, val month: YearMonth? = null, val clickLabel: String? = null,
    val ornament: HeroOrnament? = null, val driverId: String? = null)

/** One of the hero's ways on: the [primary] one is a [PrimaryAction], the rest are [CarPillButton]s. */
@Immutable
internal data class HeroButton(val label: String, val action: HeroAction, val primary: Boolean = false)

/**
 * What the hero says ([logEmptyHero], [reportEmptyHero]): the [case], the [title] and the long form TalkBack hears for it
 * ([spoken], with the month and the employee the title leaves out), at most one [line], the [tiles] (three at most) and the
 * [actions] in order, the primary one first. 월간·Excel's hero has no title (2.15 §9.3): the month-close card above it says
 * the month is empty. [rows]: the tiles are months, which always stand as the stacked COMPACT rows, whatever the width (§9.1).
 */
@Immutable
internal data class EmptyHero(val case: EmptyHeroCase, val title: String?, val spoken: String?, val line: String? = null,
    val tiles: List<HeroTile> = emptyList(), val actions: List<HeroButton> = emptyList(), val rows: Boolean = false)

/**
 * The months nearest [month] that have finished trips among [trips] (pure, unit-tested), for MONTH's tiles: never [month] itself
 * nor a month after [today]'s (a clock set back), nearest first, the later of two at the same distance first, [limit] at most.
 */
internal fun nearestTripMonths(trips: List<Trip>, month: YearMonth, today: LocalDate, zone: ZoneId, limit: Int = 3): List<YearMonth> {
    val last = YearMonth.from(today)
    return trips.asSequence().filter { !it.open }.map { YearMonth.from(Instant.ofEpochMilli(it.startAt).atZone(zone)) }
        .filter { it <= last && it != month }.distinct()
        .sortedWith(compareBy<YearMonth> { abs(ChronoUnit.MONTHS.between(it, month)) }.thenByDescending { it })
        .take(limit).toList()
}

/**
 * A month that has trips among [trips] as a hero row (pure): "10월" over "9건 · 54.0 km · 업무 80%", 마감됨 as its tag while
 * the month is closed, a tap that opens it ([clickLabel] says how).
 */
private fun monthTile(trips: List<Trip>, shown: YearMonth, data: TripLogData, today: LocalDate, zone: ZoneId, clickLabel: String): HeroTile {
    val totals = logbookTotals(tripsIn(trips, shown.atDay(1), shown.atEndOfMonth(), zone))
    val closed = data.closedMonths.any { it.month == monthKey(shown) && it.demo == data.profile.demoMode }
    val label = monthLabel(shown, today)
    return HeroTile(label, LogCopy.monthTileLine(totals), HeroGlyph.MONTH,
        listOfNotNull(label, "${totals.trips}건", kmText(totals.totalKm), "업무 ${ratioText(totals.ratio)}", LogCopy.HERO_CLOSED.takeIf { closed }).joinToString(", "),
        tag = LogCopy.HERO_CLOSED.takeIf { closed }, action = HeroAction.OPEN_MONTH, month = shown, clickLabel = clickLabel)
}

/**
 * The people who drove among [trips] as hero tiles (pure; 2.15 §9.1), three at most, the most trips first: the name over "9건 ·
 * 54.0 km" ([LogCopy.personTileLine], led by the [month] when the tile opens one), a tap that filters the list by that person
 * and, with a [month], opens it; TalkBack hears 부서 · 성명. A trip without a person, or whose person is no longer registered,
 * has nobody to filter by and is left out.
 */
private fun peopleTiles(trips: List<Trip>, drivers: List<Driver>, month: YearMonth?, today: LocalDate): List<HeroTile> {
    val label = month?.let { monthLabel(it, today) }
    return trips.groupBy { it.driverId }.mapNotNull { (id, own) -> drivers.firstOrNull { it.id == id }?.let { it to logbookTotals(own) } }
        .sortedWith(compareByDescending<Pair<Driver, LogbookTotals>> { it.second.trips }.thenByDescending { it.second.totalKm }.thenBy { it.first.label })
        .take(3).map { (driver, totals) ->
            HeroTile(driver.name.ifBlank { driver.label }, LogCopy.personTileLine(totals, label), HeroGlyph.PERSON,
                listOfNotNull(driver.label, label, "${totals.trips}건", kmText(totals.totalKm)).joinToString(", "),
                action = HeroAction.PICK_EMPLOYEE, month = month, clickLabel = LogCopy.HERO_PICK_EMPLOYEE, driverId = driver.id)
        }
}

/**
 * The three facts of [trips] as hero tiles that lead nowhere (pure): the count, the distance and the ratio, worded as 설정 ›
 * 직원 words them ([monthFacts]), so the same month reads the same on both pages.
 */
private fun factTiles(trips: List<Trip>): List<HeroTile> = monthFacts(trips).map { fact -> HeroTile(fact.figure, fact.label, fact.kind.glyph, fact.spoken) }

/**
 * What 운행기록 shows where the list under [filter] (0 전체, 1 점검 필요, 2 자동 반영) is empty in [month] (2.15 §2.2 and §9.1;
 * pure, unit-tested for every branch). [data] is the data set on screen (its trips are kept to its demo flag here as well);
 * [employeeId] is the employee chip's filter. In order:
 *
 * - MATCHED, 자동 반영 is empty: how a card payment reaches a trip in three steps, the first a button to 설정 › 기록, then 카드
 *   등록하러 가기 and 전체 보기.
 * - ISSUES, 점검 필요 is empty in a month whose list has trips: the existing line, three facts of the month (count, distance,
 *   ratio; not buttons), "10월 마감하러 가기" while the month is ready to close, and 전체 보기.
 * - EMPLOYEE, the month has trips but none of the chosen employee: that employee's nearest months with trips as rows (a tap
 *   opens the month, the filter kept), or, for an employee without any trip, the people who drove in the month as tiles (a tap
 *   filters by that person); 필터 해제 first, then 이전 달 보기. No hero without tiles (§9.1).
 * - FIRST, no trip anywhere: the three steps of a first trip, each with its picture ([HeroOrnament]), 시연 체험 보기 and 시연 모드
 *   켜러 가기, or, in demo mode, 홈에서 시연 출발 alone. The demo switch is never flipped from here: on a real car it stops real
 *   recording. §2.2 draws FIRST under 전체 without an employee; under 점검 필요 or an employee an empty log says the same, with
 *   필터 해제 added.
 * - MONTH, the month has none and other months do: the nearest months with the list's trips as rows that open them, 이전 달
 *   보기, and 필터 해제 while a filter or an employee narrows the list. Under an employee without any trip, the people who drove
 *   in the nearest month with trips as tiles, a tap filtering by that person and opening that month.
 */
internal fun logEmptyHero(filter: Int, month: YearMonth, data: TripLogData, employeeId: String?, today: LocalDate, zone: ZoneId): EmptyHero {
    val name = monthLabel(month, today)
    val driver = employeeId?.let { id -> data.drivers.firstOrNull { it.id == id } }
    // TalkBack hears the employee as the chip names them (부서 · 성명); EMPLOYEE's title shows the name alone.
    val who = driver?.label?.let { " $it 님의" }.orEmpty()
    val narrowed = filter != 0 || employeeId != null
    val trips = data.trips.filter { it.demo == data.profile.demoMode && !it.open }
    val listed = trips.filter { employeeId == null || it.driverId == employeeId }
    val monthAll = tripsIn(trips, month.atDay(1), month.atEndOfMonth(), zone)
    val monthListed = monthAll.filter { employeeId == null || it.driverId == employeeId }
    val previous = HeroButton(LogCopy.PREVIOUS_MONTH, HeroAction.PREVIOUS_MONTH)
    val clear = HeroButton(LogCopy.CLEAR_FILTER, HeroAction.CLEAR_FILTER)
    val showAll = HeroButton(LogCopy.SHOW_ALL, HeroAction.SHOW_ALL)
    val steps: (List<Pair<String, String>>, List<HeroGlyph>) -> List<HeroTile> = { words, glyphs ->
        words.mapIndexed { index, (title, line) -> HeroTile(title, line, glyphs[index], LogCopy.heroStepSpoken(index + 1, title, line), step = index + 1) }
    }
    // The nearest months with the list's trips, as rows that open them (the employee filter kept).
    val monthRows = nearestTripMonths(listed, month, today, zone).map { monthTile(listed, it, data, today, zone, LogCopy.HERO_OPEN_MONTH) }
    return when {
        filter == 2 -> {
            val tiles = steps(LogCopy.HERO_CARD_STEPS, listOf(HeroGlyph.CARD, HeroGlyph.NOTIFY, HeroGlyph.AUTO)).mapIndexed { index, tile ->
                if (index == 0) tile.copy(action = HeroAction.CARD_SETTING, clickLabel = LogCopy.HERO_CARD_SETTING) else tile
            }
            EmptyHero(EmptyHeroCase.MATCHED, LogCopy.EMPTY_MATCHED, "$name ${LogCopy.EMPTY_MATCHED}", tiles = tiles,
                actions = listOf(HeroButton(LogCopy.HERO_CARD_SETTING, HeroAction.CARD_SETTING, primary = true), showAll))
        }
        filter == 1 && monthListed.isNotEmpty() -> {
            val facts = factTiles(monthListed)
            val close = HeroButton(LogCopy.closeMonthAction(month, today), HeroAction.CLOSE_MONTH, primary = true)
                .takeIf { monthStatus(data, month, zone).ready }
            EmptyHero(EmptyHeroCase.ISSUES, LogCopy.EMPTY_ISSUES, "$name$who ${LogCopy.EMPTY_ISSUES}", LogCopy.EMPTY_ISSUES_LINE, facts,
                listOfNotNull(close, showAll))
        }
        employeeId != null && monthAll.isNotEmpty() && monthListed.isEmpty() -> {
            val title = LogCopy.employeeEmptyTitle(driver?.let { it.name.ifBlank { it.label } })
            // 2.15 §9.1: never a hero without tiles: the employee's own months, or who did drive this month, or, when every trip
            // of the month has no person to filter by (사용자 미지정), the month's three facts, which lead nowhere.
            val people = if (monthRows.isEmpty()) peopleTiles(monthAll, data.drivers, null, today).ifEmpty { factTiles(monthAll) } else emptyList()
            EmptyHero(EmptyHeroCase.EMPLOYEE, title, if (driver != null) "$name$who 운행이 없어요" else "$name $title", tiles = monthRows.ifEmpty { people },
                actions = listOf(clear.copy(primary = true), previous), rows = monthRows.isNotEmpty())
        }
        trips.isEmpty() -> {
            // 홈's no-trip steps, heard as 홈 says them ([HomeCopy.spokenIdleStep]), each with its picture (§9.1).
            val ornaments = listOf(HeroOrnament.GEAR_DRIVE, HeroOrnament.GEAR_PARK, HeroOrnament.PURPOSES)
            val tiles = steps(LogCopy.HERO_FIRST_STEPS, listOf(HeroGlyph.DRIVE, HeroGlyph.PARK, HeroGlyph.PURPOSE))
                .mapIndexed { index, tile -> tile.copy(spoken = HomeCopy.spokenIdleStep(index + 1, HomeCopy.IDLE_STEPS[index]), ornament = ornaments[index]) }
            val ways = if (data.profile.demoMode) listOf(HeroButton(LogCopy.HERO_DEMO_DRIVE, HeroAction.DEMO_DRIVE, primary = true))
                else listOf(HeroButton(LogCopy.HERO_TUTORIAL, HeroAction.TUTORIAL, primary = true), HeroButton(LogCopy.HERO_DEMO_SETTING, HeroAction.DEMO_SETTING))
            EmptyHero(EmptyHeroCase.FIRST, LogCopy.HERO_FIRST, LogCopy.HERO_FIRST, LogCopy.HERO_START, tiles, ways + listOfNotNull(clear.takeIf { narrowed }))
        }
        else -> {
            val current = month == YearMonth.from(today)
            val title = LogCopy.emptyMonthTitle(month, today)
            val spoken = if (current) "$name$who 운행이 아직 없어요" else "$name$who 운행 기록이 없어요"
            // An employee without any trip has no months of their own: who drove in the nearest month that has trips (§9.1).
            // Where that month's trips have no person either, its three facts stand in, so the hero is never bare.
            val people = if (employeeId != null && listed.isEmpty()) nearestTripMonths(trips, month, today, zone, limit = 1).firstOrNull()?.let { nearest ->
                val nearestTrips = tripsIn(trips, nearest.atDay(1), nearest.atEndOfMonth(), zone)
                peopleTiles(nearestTrips, data.drivers, nearest, today).ifEmpty { factTiles(nearestTrips) }
            }.orEmpty() else emptyList()
            EmptyHero(EmptyHeroCase.MONTH, title, spoken, LogCopy.HERO_START.takeIf { current }, monthRows.ifEmpty { people },
                listOfNotNull(previous, clear.takeIf { narrowed }), rows = monthRows.isNotEmpty())
        }
    }
}

/**
 * What 월간·Excel shows in place of the two cards of 운행 현황, 경비·정산 and 검토 where the [period] on screen has no trip (2.15
 * §9.3; pure, unit-tested): a hero without a title, since the month-close card above them already says the month is empty.
 * On an empty log the three steps of a month (목적 확인, 월 마감, Excel 저장) and 시연 체험 보기, or, in demo mode, 홈에서 시연
 * 출발 as 운행기록's FIRST says it; otherwise the nearest months with trips as rows (a tap moves the page to that month) and
 * the step back by the period's unit, 이전 달 보기 (이전 해 보기 under 연간). Null while the period has a trip. The Excel view
 * keeps its empty sheet and never shows it.
 */
internal fun reportEmptyHero(data: TripLogData, period: ReportPeriod, today: LocalDate, zone: ZoneId): EmptyHero? {
    val trips = data.trips.filter { it.demo == data.profile.demoMode && !it.open }
    val (from, to) = reportRange(period, today)
    if (tripsIn(trips, from, to, zone).isNotEmpty()) return null
    if (trips.isEmpty()) {
        val glyphs = listOf(HeroGlyph.PURPOSE, HeroGlyph.CLOSE, HeroGlyph.EXCEL)
        val tiles = ReportCopy.HERO_STEPS.mapIndexed { index, (title, line) ->
            HeroTile(title, line, glyphs[index], LogCopy.heroStepSpoken(index + 1, title, line), step = index + 1)
        }
        val way = if (data.profile.demoMode) HeroButton(LogCopy.HERO_DEMO_DRIVE, HeroAction.DEMO_DRIVE, primary = true)
            else HeroButton(LogCopy.HERO_TUTORIAL, HeroAction.TUTORIAL, primary = true)
        return EmptyHero(EmptyHeroCase.FIRST, null, null, tiles = tiles, actions = listOf(way))
    }
    val rows = nearestTripMonths(trips, period.month, today, zone).map { monthTile(trips, it, data, today, zone, ReportCopy.HERO_OPEN_MONTH) }
    val back = HeroButton(if (period.yearly) ReportCopy.HERO_PREVIOUS_YEAR else LogCopy.PREVIOUS_MONTH, HeroAction.PREVIOUS_MONTH)
    return EmptyHero(EmptyHeroCase.MONTH, null, null, tiles = rows, actions = listOf(back), rows = true)
}

/**
 * The empty hero (2.15 §2.2, §9.1), in the place [modifier] gives it: the [hero]'s title, if it has one (headlineMedium, bold,
 * [Scene.text], the page's heading; TalkBack hears [EmptyHero.spoken]) and at most one line (bodyLarge, [Scene.textSecondary])
 * on the scene, with no card behind them, as 홈's greeting stands; 24 dp; the tiles; 24 dp; the actions as a flow row 12 dp
 * apart. Where the effective page holds them ([heroTilesAcross]) the tiles stand three across, equal widths and heights, 16 dp
 * apart, as tall as the room leaves ([heroTileHeight]: 360 dp in the FULL window at 1×, the larger square from 300); else, and
 * always for months ([EmptyHero.rows]), they stack in the COMPACT look. Where its height is bounded (운행기록) the hero scrolls
 * as a whole when a large font or a short window needs it, and never in the FULL window at 1× (542 of 1015 dp); inside
 * 월간·Excel's scroll it does not scroll itself and its tiles keep 208. It appears with the page, with no stagger. A tile or
 * a button reports its [HeroAction] to [onAction], a tile with itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CarEmptyHero(hero: EmptyHero, modifier: Modifier = Modifier, onAction: (HeroAction, HeroTile?) -> Unit) {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val lines = carLineMaxLines(fontScale)
    val typography = MaterialTheme.typography
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier) {
        val across = !hero.rows && heroTilesAcross(maxWidth, fontScale)
        val bounded = constraints.hasBoundedHeight
        // The tiles across take the room the page leaves under the title and over the actions, less the city's band (§9.1).
        val chrome = with(density) {
            heroChrome(hero.title?.let { typography.headlineMedium.lineHeight.toDp() }, hero.line?.let { typography.bodyLarge.lineHeight.toDp() },
                hero.tiles.isNotEmpty(), hero.actions.isNotEmpty())
        }
        val tileHeight = if (across) heroTileHeight(if (bounded) maxHeight else Dp.Infinity, chrome) else HERO_TILE_COMPACT
        Column(Modifier.fillMaxWidth().then(if (bounded) Modifier.fillMaxHeight().verticalScroll(scroll) else Modifier).semantics { isTraversalGroup = true },
            verticalArrangement = Arrangement.spacedBy(HERO_BLOCK_GAP)) {
            hero.title?.let { title ->
                Column(verticalArrangement = Arrangement.spacedBy(HERO_LINE_GAP)) {
                    Text(title, Modifier.semantics { heading(); contentDescription = hero.spoken ?: title }, style = typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = Scene.text, maxLines = lines, overflow = TextOverflow.Ellipsis)
                    hero.line?.let { line ->
                        Text(line, style = typography.bodyLarge, color = Scene.textSecondary, maxLines = lines, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (hero.tiles.isNotEmpty()) {
                val tap: (HeroTile) -> (() -> Unit)? = { tile -> tile.action?.let { action -> { onAction(action, tile) } } }
                // Equal heights across: a tile is a CarCard over boxes, rows, flow rows, icons and text, with no SubcomposeLayout,
                // BoxWithConstraints or lazy list inside, so the row may ask its tiles for their intrinsic height.
                if (across) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    hero.tiles.forEach { tile -> HeroTileCard(tile, tall = true, tileHeight, Modifier.weight(1f).fillMaxHeight(), tap(tile)) }
                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    hero.tiles.forEach { tile -> HeroTileCard(tile, tall = false, HERO_TILE_COMPACT, Modifier.fillMaxWidth(), tap(tile)) }
                }
            }
            if (hero.actions.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    hero.actions.forEach { button ->
                        if (button.primary) PrimaryAction(button.label) { onAction(button.action, null) }
                        else CarPillButton(button.label, { onAction(button.action, null) })
                    }
                }
            }
        }
    }
}

/**
 * One hero tile, a [CarCard] that TalkBack meets as one stop ([HeroTile.spoken]). Across ([tall]) it takes the look of 홈's TALL
 * destination card, [minHeight] at least ([heroTileHeight]) and filling the row's height, its content centred: the glyph on the
 * 56-dp accent square (corner 16, onAccent, 4.93), 16 dp, the title in titleLarge, 6 dp, one centred [CarLine]; from
 * [HERO_TILE_LARGE_FROM] up the 88-dp square with a 44-dp glyph (corner 24), 20 dp, the title in headlineSmall, 8 dp. A first-trip
 * step's picture stands under its line ([HeroOrnamentView]). A step's [CarStepDisc] sits at its top start and a tag at its top
 * end. Stacked, the COMPACT look: [HERO_TILE_COMPACT] at least, the disc and the square at the start, the title (titleMedium)
 * over its line, the tag at the end, no picture. The tag is cleared for TalkBack: the spoken form already ends with it
 * (2.15 §9.1). A tile that leads somewhere ([onClick]) ends its line, or its row, in a chevron and is a button that settles to
 * .98 with the press fill (CarCard's), never a ripple; the others are not buttons. 설정 › 직원's tiles before the first employee
 * ([DriverIntroTiles], §9.5) are these tiles too, at the height the same rule gives them ([heroTileHeight]).
 */
@Composable internal fun HeroTileCard(tile: HeroTile, tall: Boolean, minHeight: Dp, modifier: Modifier, onClick: (() -> Unit)?) {
    val glyph = heroGlyph(tile.glyph)
    val large = tall && heroTileLarge(minHeight)
    val chevron: @Composable (Dp) -> Unit = { size ->
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, Modifier.size(size), tint = Scene.textSecondary)
    }
    val tag: @Composable (Modifier) -> Unit = { place -> tile.tag?.let { text -> Box(place.clearAndSetSemantics {}) { CarTag(text) } } }
    CarCard(modifier, onClick = onClick, onClickLabel = tile.clickLabel, description = tile.spoken, padding = 0.dp) {
        if (tall) Box(Modifier.fillMaxWidth().heightIn(min = minHeight).fillMaxHeight()) {
            Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HeroAccentSquare(glyph, large)
                Spacer(Modifier.height(if (large) 20.dp else 16.dp))
                Text(tile.title, style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, color = Scene.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Spacer(Modifier.height(if (large) 8.dp else 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CarLine(tile.line, Modifier.weight(1f, fill = false))
                    if (onClick != null) {
                        Spacer(Modifier.width(4.dp))
                        chevron(20.dp)
                    }
                }
                tile.ornament?.let { ornament ->
                    Spacer(Modifier.height(if (large) 24.dp else 16.dp))
                    HeroOrnamentView(ornament)
                }
            }
            tile.step?.let { step -> Box(Modifier.align(Alignment.TopStart).padding(16.dp)) { CarStepDisc(step, done = false) } }
            tag(Modifier.align(Alignment.TopEnd).padding(16.dp))
        } else Row(Modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            tile.step?.let { step ->
                CarStepDisc(step, done = false)
                Spacer(Modifier.width(12.dp))
            }
            HeroAccentSquare(glyph, large = false)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tile.title, style = MaterialTheme.typography.titleMedium, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CarLine(tile.line)
            }
            if (tile.tag != null) {
                Spacer(Modifier.width(12.dp))
                tag(Modifier)
            }
            if (onClick != null) {
                Spacer(Modifier.width(8.dp))
                chevron(24.dp)
            }
        }
    }
}

/**
 * A first-trip step's small picture (2.15 §9.1), so a tall tile says what it means without more words; decorative, cleared for
 * TalkBack (the tile's spoken form says the step). ① and ② a gear selector's strip ([GearStrip]) with D or P lit; ③ the three
 * purposes as the list draws them ([PurposeChip]: the tag with its purpose's dot), not buttons, wrapping centred where a narrow
 * tile needs it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun HeroOrnamentView(ornament: HeroOrnament) {
    Box(Modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        when (ornament) {
            HeroOrnament.GEAR_DRIVE -> GearStrip(lit = LogCopy.HERO_GEARS.lastIndex)
            HeroOrnament.GEAR_PARK -> GearStrip(lit = 0)
            HeroOrnament.PURPOSES -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HERO_PURPOSES.forEach { PurposeChip(it) }
            }
        }
    }
}

/**
 * A gear selector's strip, [LogCopy.HERO_GEARS] (P R N D), 48 dp tall: a stadium on the chip fill with the chip border, four
 * 40-dp positions 4 dp apart, the [lit] one a disc in the accent with its letter in onAccent (4.93), the others textSecondary on
 * the chip fill (7.19 / 9.27).
 */
@Composable private fun GearStrip(lit: Int) {
    val skin = Car.skin
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).background(skin.chipFill).border(1.dp, skin.chipBorder, shape).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LogCopy.HERO_GEARS.forEachIndexed { index, gear ->
            val on = index == lit
            Box(Modifier.size(40.dp).clip(CircleShape).then(if (on) Modifier.background(skin.accent) else Modifier), contentAlignment = Alignment.Center) {
                Text(gear, style = MaterialTheme.typography.titleMedium, color = if (on) skin.onAccent else Scene.textSecondary, maxLines = 1)
            }
        }
    }
}

/**
 * A tile's glyph on its accent square, onAccent (4.93), as on 홈's tall destination cards: 56 dp (corner 16), or 88 (corner 24)
 * when [large]. Decorative. 설정 › 직원's fact tiles (SettingsFill.kt) take it too, so one square stands for a tile everywhere.
 */
@Composable internal fun HeroAccentSquare(glyph: Painter, large: Boolean) {
    val skin = Car.skin
    Box(Modifier.size(if (large) HERO_TILE_ICON_LARGE else HERO_TILE_ICON).clip(RoundedCornerShape(if (large) 24.dp else 16.dp)).background(skin.accent),
        contentAlignment = Alignment.Center) {
        Icon(glyph, contentDescription = null, Modifier.size(if (large) HERO_TILE_GLYPH_LARGE else HERO_TILE_GLYPH), tint = skin.onAccent)
    }
}

/**
 * The painter of a [HeroGlyph]: the app's line icons, and Material's for a parked pin, a check, a person and a lock. 설정 ›
 * 직원's fact tiles draw theirs from here too ([ShareFactKind.glyph]).
 */
@Composable internal fun heroGlyph(glyph: HeroGlyph): Painter = when (glyph) {
    HeroGlyph.DRIVE -> painterResource(R.drawable.ic_vehicle)
    HeroGlyph.PARK -> rememberVectorPainter(Icons.Outlined.LocationOn)
    HeroGlyph.PURPOSE -> rememberVectorPainter(Icons.Outlined.CheckCircle)
    HeroGlyph.MONTH -> painterResource(R.drawable.ic_calendar)
    HeroGlyph.COUNT -> painterResource(R.drawable.ic_journal)
    HeroGlyph.DISTANCE -> painterResource(R.drawable.ic_gauge)
    HeroGlyph.RATIO -> painterResource(R.drawable.ic_report)
    HeroGlyph.CARD -> painterResource(R.drawable.ic_won)
    HeroGlyph.NOTIFY -> painterResource(R.drawable.ic_phone)
    HeroGlyph.AUTO -> painterResource(R.drawable.ic_autofill)
    HeroGlyph.PERSON -> rememberVectorPainter(Icons.Outlined.Person)
    HeroGlyph.CLOSE -> rememberVectorPainter(Icons.Outlined.Lock)
    HeroGlyph.EXCEL -> painterResource(R.drawable.ic_export)
}
