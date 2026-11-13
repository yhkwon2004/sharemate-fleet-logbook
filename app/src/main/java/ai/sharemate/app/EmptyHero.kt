package ai.sharemate.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LocationOn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/*
 * The empty hero (2.15, docs/design/DESIGN_2_15.md §2.2). Where 운행기록's list is empty, 2.13 put one small card in the corner of
 * an empty page, and the driver read the page as "빈". The hero takes the whole row under the page's header instead: a title
 * on the scene like 홈's greeting, at most one line under it, up to three large tiles in the look of 홈's tall destination cards
 * and big buttons, so the page is filled by weighty elements and very little text. What it says is one pure decision
 * ([logEmptyHero]); the composable only draws it.
 */

/** A hero tile across the page: the least height of 홈's TALL destination card (2.14's 208 dp), whose look it takes. */
internal val HERO_TILE_TALL = 208.dp

/** A stacked hero tile: the least height of 홈's COMPACT destination card. */
internal val HERO_TILE_COMPACT = 104.dp

/** A tile's accent square (corner 16) and its glyph, as on 홈's tall destination cards. */
private val HERO_TILE_ICON = 56.dp
private val HERO_TILE_GLYPH = 28.dp

/** The effective page (its width ÷ the font scale) from which three tiles stand across, the split page's own threshold. */
internal val HERO_TILES_ACROSS_FROM = 900.dp

/**
 * Whether the hero's tiles stand across the page (pure, unit-tested): from [HERO_TILES_ACROSS_FROM] of effective page, as
 * [logListWidth] splits the page; below it they stack in the COMPACT look. A font smaller than 1× counts as 1×.
 */
internal fun heroTilesAcross(page: Dp, fontScale: Float): Boolean = page / fontScale.coerceAtLeast(1f) >= HERO_TILES_ACROSS_FROM

/** Which empty list the hero stands for, the rows of §2.2's table. */
internal enum class EmptyHeroCase { FIRST, MONTH, EMPLOYEE, ISSUES, MATCHED }

/** A tile's glyph, drawn onAccent on its accent square; the model names it so it stays free of painters. */
internal enum class HeroGlyph { DRIVE, PARK, PURPOSE, MONTH, COUNT, DISTANCE, RATIO, CARD, NOTIFY, AUTO }

/** What a hero tile or button does; the page that shows the hero carries it out (LogPane). */
internal enum class HeroAction { TUTORIAL, DEMO_SETTING, DEMO_DRIVE, PREVIOUS_MONTH, CLEAR_FILTER, OPEN_MONTH, CLOSE_MONTH, SHOW_ALL, CARD_SETTING }

/**
 * One hero tile: its [title] (titleLarge across, titleMedium stacked), one [line], its [glyph] and what TalkBack hears, in one
 * stop ([spoken]). A step tile carries its [step] number; a [tag] (마감됨) sits at its top end. A tile with an [action] is a
 * button ([clickLabel] names it; an OPEN_MONTH tile carries its [month]); the others are facts, not focusable as buttons.
 */
@Immutable
internal data class HeroTile(val title: String, val line: String, val glyph: HeroGlyph, val spoken: String, val step: Int? = null,
    val tag: String? = null, val action: HeroAction? = null, val month: YearMonth? = null, val clickLabel: String? = null)

/** One of the hero's ways on: the [primary] one is a [PrimaryAction], the rest are [CarPillButton]s. */
@Immutable
internal data class HeroButton(val label: String, val action: HeroAction, val primary: Boolean = false)

/**
 * What the hero says ([logEmptyHero]): the [case], the [title] and the long form TalkBack hears for it ([spoken], with the month
 * and the employee the title leaves out), at most one [line], the [tiles] (three at most) and the [actions] in order, the primary
 * one first.
 */
@Immutable
internal data class EmptyHero(val case: EmptyHeroCase, val title: String, val spoken: String, val line: String? = null,
    val tiles: List<HeroTile> = emptyList(), val actions: List<HeroButton> = emptyList())

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
 * What 운행기록 shows where the list under [filter] (0 전체, 1 점검 필요, 2 자동 반영) is empty in [month] (2.15 §2.2; pure,
 * unit-tested for every branch). [data] is the data set on screen (its trips are kept to its demo flag here as well);
 * [employeeId] is the employee chip's filter. In order:
 *
 * - MATCHED, 자동 반영 is empty: how a card payment reaches a trip in three steps, the first a button to 설정 › 기록, then 카드
 *   등록하러 가기 and 전체 보기.
 * - ISSUES, 점검 필요 is empty in a month whose list has trips: the existing line, three facts of the month (count, distance,
 *   ratio; not buttons), "10월 마감하러 가기" while the month is ready to close, and 전체 보기.
 * - EMPLOYEE, the month has trips but none of the chosen employee: 필터 해제 first, then 이전 달 보기.
 * - FIRST, no trip anywhere: the three steps of a first trip, 시연 체험 보기 and 시연 모드 켜러 가기, or, in demo mode, 홈에서 시연
 *   출발 alone. The demo switch is never flipped from here: on a real car it stops real recording. §2.2 draws FIRST under 전체
 *   without an employee; under 점검 필요 or an employee an empty log says the same, with 필터 해제 added.
 * - MONTH, the month has none and other months do: the nearest months with the list's trips as tiles that open them, 이전 달
 *   보기, and 필터 해제 while a filter or an employee narrows the list.
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
    return when {
        filter == 2 -> {
            val tiles = steps(LogCopy.HERO_CARD_STEPS, listOf(HeroGlyph.CARD, HeroGlyph.NOTIFY, HeroGlyph.AUTO)).mapIndexed { index, tile ->
                if (index == 0) tile.copy(action = HeroAction.CARD_SETTING, clickLabel = LogCopy.HERO_CARD_SETTING) else tile
            }
            EmptyHero(EmptyHeroCase.MATCHED, LogCopy.EMPTY_MATCHED, "$name ${LogCopy.EMPTY_MATCHED}", tiles = tiles,
                actions = listOf(HeroButton(LogCopy.HERO_CARD_SETTING, HeroAction.CARD_SETTING, primary = true), showAll))
        }
        filter == 1 && monthListed.isNotEmpty() -> {
            val totals = logbookTotals(monthListed)
            val facts = listOf(
                Triple("${totals.trips}건", LogCopy.FACT_TRIPS, HeroGlyph.COUNT),
                Triple(kmText(totals.totalKm), LogCopy.FACT_DISTANCE, HeroGlyph.DISTANCE),
                Triple(ratioText(totals.ratio), LogCopy.FACT_RATIO, HeroGlyph.RATIO),
            ).map { (figure, label, glyph) -> HeroTile(figure, label, glyph, "$label $figure") }
            val close = HeroButton(LogCopy.closeMonthAction(month, today), HeroAction.CLOSE_MONTH, primary = true)
                .takeIf { monthStatus(data, month, zone).ready }
            EmptyHero(EmptyHeroCase.ISSUES, LogCopy.EMPTY_ISSUES, "$name$who ${LogCopy.EMPTY_ISSUES}", LogCopy.EMPTY_ISSUES_LINE, facts,
                listOfNotNull(close, showAll))
        }
        employeeId != null && monthAll.isNotEmpty() && monthListed.isEmpty() -> {
            val title = LogCopy.employeeEmptyTitle(driver?.let { it.name.ifBlank { it.label } })
            EmptyHero(EmptyHeroCase.EMPLOYEE, title, if (driver != null) "$name$who 운행이 없어요" else "$name $title",
                actions = listOf(clear.copy(primary = true), previous))
        }
        trips.isEmpty() -> {
            val tiles = steps(LogCopy.HERO_FIRST_STEPS, listOf(HeroGlyph.DRIVE, HeroGlyph.PARK, HeroGlyph.PURPOSE))
            val ways = if (data.profile.demoMode) listOf(HeroButton(LogCopy.HERO_DEMO_DRIVE, HeroAction.DEMO_DRIVE, primary = true))
                else listOf(HeroButton(LogCopy.HERO_TUTORIAL, HeroAction.TUTORIAL, primary = true), HeroButton(LogCopy.HERO_DEMO_SETTING, HeroAction.DEMO_SETTING))
            EmptyHero(EmptyHeroCase.FIRST, LogCopy.HERO_FIRST, LogCopy.HERO_FIRST, LogCopy.HERO_START, tiles, ways + listOfNotNull(clear.takeIf { narrowed }))
        }
        else -> {
            val current = month == YearMonth.from(today)
            val title = LogCopy.emptyMonthTitle(month, today)
            val spoken = if (current) "$name$who 운행이 아직 없어요" else "$name$who 운행 기록이 없어요"
            val tiles = nearestTripMonths(listed, month, today, zone).map { shown ->
                val totals = logbookTotals(tripsIn(listed, shown.atDay(1), shown.atEndOfMonth(), zone))
                val closed = data.closedMonths.any { it.month == monthKey(shown) && it.demo == data.profile.demoMode }
                val label = monthLabel(shown, today)
                HeroTile(label, LogCopy.monthTileLine(totals), HeroGlyph.MONTH,
                    listOfNotNull(label, "${totals.trips}건", kmText(totals.totalKm), "업무 ${ratioText(totals.ratio)}", LogCopy.HERO_CLOSED.takeIf { closed })
                        .joinToString(", "),
                    tag = LogCopy.HERO_CLOSED.takeIf { closed }, action = HeroAction.OPEN_MONTH, month = shown, clickLabel = LogCopy.HERO_OPEN_MONTH)
            }
            EmptyHero(EmptyHeroCase.MONTH, title, spoken, LogCopy.HERO_START.takeIf { current }, tiles, listOfNotNull(previous, clear.takeIf { narrowed }))
        }
    }
}

/**
 * The empty hero (2.15 §2.2), in the place of 운행기록's list and detail ([modifier] gives it the row under the header): the
 * [hero]'s title (headlineMedium, bold, [Scene.text], the page's heading; TalkBack hears [EmptyHero.spoken]) and at most one
 * line (bodyLarge, [Scene.textSecondary]) on the scene, with no card behind them, as 홈's greeting stands; 24 dp; the tiles,
 * three across, equal widths and heights, 16 dp apart, where the effective page holds them ([heroTilesAcross]), else stacked in
 * the COMPACT look; 24 dp; the actions as a flow row 12 dp apart. About 376 dp of the 880 the FULL page gives at 1×, so it
 * never scrolls there; it scrolls as a whole where a large font or a short window needs it. It appears with the page, with no
 * stagger. A tile or a button reports its [HeroAction] to [onAction], an OPEN_MONTH tile with its month.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CarEmptyHero(hero: EmptyHero, modifier: Modifier = Modifier, onAction: (HeroAction, YearMonth?) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    val lines = carLineMaxLines(fontScale)
    BoxWithConstraints(modifier) {
        val across = heroTilesAcross(maxWidth, fontScale)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).semantics { isTraversalGroup = true }) {
            Text(hero.title, Modifier.semantics { heading(); contentDescription = hero.spoken }, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, color = Scene.text, maxLines = lines, overflow = TextOverflow.Ellipsis)
            hero.line?.let { line ->
                Spacer(Modifier.height(8.dp))
                Text(line, style = MaterialTheme.typography.bodyLarge, color = Scene.textSecondary, maxLines = lines, overflow = TextOverflow.Ellipsis)
            }
            if (hero.tiles.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                val tap: (HeroTile) -> (() -> Unit)? = { tile -> tile.action?.let { action -> { onAction(action, tile.month) } } }
                // Equal heights across: a tile is a CarCard over boxes, rows, icons and text, with no SubcomposeLayout, BoxWithConstraints
                // or lazy list inside, so the row may ask its tiles for their intrinsic height.
                if (across) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    hero.tiles.forEach { tile -> HeroTileCard(tile, tall = true, Modifier.weight(1f).fillMaxHeight(), tap(tile)) }
                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    hero.tiles.forEach { tile -> HeroTileCard(tile, tall = false, Modifier.fillMaxWidth(), tap(tile)) }
                }
            }
            if (hero.actions.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
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
 * destination card: [HERO_TILE_TALL] at least, its content centred, the glyph on the 56-dp accent square (corner 16, onAccent,
 * 4.93), 16 dp, the title in titleLarge, 6 dp, one centred [CarLine]; a step's [CarStepDisc] at its top start and a tag at its
 * top end. Stacked, the COMPACT look: [HERO_TILE_COMPACT] at least, the disc and the square at the start, the title (titleMedium)
 * over its line, the tag at the end. A tile that leads somewhere ([onClick]) ends its line in a chevron and is a button that
 * settles to .98 with the press fill (CarCard's), never a ripple; the others are not buttons.
 */
@Composable private fun HeroTileCard(tile: HeroTile, tall: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val glyph = heroGlyph(tile.glyph)
    val chevron: @Composable (Dp) -> Unit = { size ->
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, Modifier.size(size), tint = Scene.textSecondary)
    }
    CarCard(modifier, onClick = onClick, onClickLabel = tile.clickLabel, description = tile.spoken, padding = 0.dp) {
        if (tall) Box(Modifier.fillMaxWidth().heightIn(min = HERO_TILE_TALL)) {
            Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AccentSquare(glyph)
                Spacer(Modifier.height(16.dp))
                Text(tile.title, style = MaterialTheme.typography.titleLarge, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CarLine(tile.line, Modifier.weight(1f, fill = false))
                    if (onClick != null) {
                        Spacer(Modifier.width(4.dp))
                        chevron(20.dp)
                    }
                }
            }
            tile.step?.let { step -> Box(Modifier.align(Alignment.TopStart).padding(16.dp)) { CarStepDisc(step, done = false) } }
            tile.tag?.let { tag -> CarTag(tag, Modifier.align(Alignment.TopEnd).padding(16.dp)) }
        } else Row(Modifier.fillMaxWidth().heightIn(min = HERO_TILE_COMPACT).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            tile.step?.let { step ->
                CarStepDisc(step, done = false)
                Spacer(Modifier.width(12.dp))
            }
            AccentSquare(glyph)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tile.title, style = MaterialTheme.typography.titleMedium, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CarLine(tile.line)
            }
            tile.tag?.let { tag ->
                Spacer(Modifier.width(12.dp))
                CarTag(tag)
            }
            if (onClick != null) {
                Spacer(Modifier.width(8.dp))
                chevron(24.dp)
            }
        }
    }
}

/** A tile's glyph on its 56-dp accent square (corner 16), onAccent (4.93), as on 홈's tall destination cards. Decorative. */
@Composable private fun AccentSquare(glyph: Painter) {
    val skin = Car.skin
    Box(Modifier.size(HERO_TILE_ICON).clip(RoundedCornerShape(16.dp)).background(skin.accent), contentAlignment = Alignment.Center) {
        Icon(glyph, contentDescription = null, Modifier.size(HERO_TILE_GLYPH), tint = skin.onAccent)
    }
}

/** The painter of a [HeroGlyph]: the app's line icons, and Material's for a parked pin and a check. */
@Composable private fun heroGlyph(glyph: HeroGlyph): Painter = when (glyph) {
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
}
