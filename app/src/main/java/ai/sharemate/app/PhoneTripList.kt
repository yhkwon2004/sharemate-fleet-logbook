package ai.sharemate.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/*
 * 운행 tab. One list, newest first, with a sticky month header; older months join as the user scrolls (the data is
 * local, so nothing spins). Each day is one glass panel with hairline dividers between its rows (2.10). A row swipes
 * right to record the suggested or general business purpose and left for personal; the value appears in the background
 * before the finger lets go, the row never leaves, and the bar at the bottom offers undo. Long-press selects several
 * rows for one purpose or one driver. Open trips and an unpaired phone cannot classify, because only the car writes.
 */

// ───────────────────────── 2.10: layout and selection helpers shared by the phone tabs ─────────────────────────

/** Material's widest bottom sheet; wider windows centre the sheet under it. */
internal val PHONE_SHEET_MAX = 640.dp

/** The widest a phone sheet gets: Material's 640 dp, or the layout's content maximum when that caps content narrower. */
internal fun sheetMaxWidthFor(contentMax: Dp): Dp = if (contentMax == Dp.Infinity) PHONE_SHEET_MAX else contentMax.coerceAtMost(PHONE_SHEET_MAX)

/** The phone tokens for a window, chosen the way ProvideLayout chooses them: the width divided by the font scale, the height as is. */
internal fun fallbackPhoneTokens(width: Dp, height: Dp, fontScale: Float): LayoutTokens = phoneLayout(width / fontScale.coerceAtLeast(1f), height)

/**
 * The phone's layout tokens. The shell provides them with ProvideLayout(compactApp = true); a screen composed without
 * it (a preview, a host of its own) would read the car's full-window default, so that case takes the phone set for the window.
 */
@Composable internal fun phoneTokens(): LayoutTokens {
    val tokens = LocalLayout.current
    if (!tokens.startAnchored) return tokens
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    return with(density) { fallbackPhoneTokens(window.width.toDp(), window.height.toDp(), fontScale) }
}

/** The widest a sheet gets on this phone; see [sheetMaxWidthFor]. */
@Composable internal fun phoneSheetMaxWidth(): Dp = sheetMaxWidthFor(phoneTokens().contentMax)

/** The 1.5 dp ring of a selected chip, tile or row (its fill is [Scene.selectionFill]); the glass edge when not selected. */
@Composable internal fun selectionBorder(selected: Boolean): BorderStroke = if (selected) BorderStroke(1.5.dp, Scene.selectionRing) else Scene.edge

/** Filter chips in the scene's selection look: fill + ring + glow label when chosen, outline token otherwise. */
@Composable internal fun sceneChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent, labelColor = Scene.text, selectedContainerColor = Scene.selectionFill, selectedLabelColor = Scene.glow)

@Composable internal fun sceneChipBorder(selected: Boolean, enabled: Boolean = true): BorderStroke =
    FilterChipDefaults.filterChipBorder(enabled = enabled, selected = selected, borderColor = Scene.outline, selectedBorderColor = Scene.selectionRing,
        borderWidth = 1.dp, selectedBorderWidth = 1.5.dp)

/**
 * Outlined text fields in the scene's palette: the outline token at rest (4.1:1 on the day background), the glow when
 * focused, the negative colour for an error, the row fill behind the text; muted and flat when disabled.
 */
@Composable internal fun sceneTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Scene.glow, unfocusedBorderColor = Scene.outline, disabledBorderColor = Scene.divider, errorBorderColor = Scene.negative,
    focusedLabelColor = Scene.glow, unfocusedLabelColor = Scene.textMuted, disabledLabelColor = Scene.textMuted, errorLabelColor = Scene.negative,
    focusedTextColor = Scene.text, unfocusedTextColor = Scene.text, disabledTextColor = Scene.textMuted, errorTextColor = Scene.text,
    cursorColor = Scene.glow, errorCursorColor = Scene.negative,
    focusedContainerColor = Scene.fill, unfocusedContainerColor = Scene.fill, disabledContainerColor = Color.Transparent, errorContainerColor = Scene.fill,
    focusedSupportingTextColor = Scene.textMuted, unfocusedSupportingTextColor = Scene.textMuted, disabledSupportingTextColor = Scene.textMuted,
    errorSupportingTextColor = Scene.negative, focusedPlaceholderColor = Scene.textMuted, unfocusedPlaceholderColor = Scene.textMuted)

/** How many list rows stagger in on the phone before the rest arrive together (spec C: 8 on the phone, 5 on the car). */
internal const val PHONE_APPEAR_CAP = 8

/** The stagger index for the [position]-th row: rows past the cap share the last slot instead of queueing for seconds. */
internal fun appearIndex(position: Int): Int = position.coerceIn(0, PHONE_APPEAR_CAP - 1)

/** The room left under a tab's last item once it has scrolled clear of the floating tab bar. */
internal val TAB_SCROLL_END_SPACE = 16.dp

/**
 * The bottom content padding of a tab's own scroll: the height the floating tab bar covers ([LocalNavInset]) plus 16 dp,
 * plus [extra] for something that floats over the list (경비's add button). The shell no longer pads a tab by the bar, so
 * content scrolls on under the glass and the last item still comes to rest above it.
 */
internal fun tabScrollBottom(navInset: Dp, extra: Dp = 0.dp): Dp = navInset + TAB_SCROLL_END_SPACE + extra

/** [tabScrollBottom] for the bar this tab sits above. */
@Composable internal fun phoneTabScrollBottom(extra: Dp = 0.dp): Dp = tabScrollBottom(LocalNavInset.current, extra)

// ───────────────────────── pure helpers ─────────────────────────

/** Trips of one month, newest first. */
internal data class TripMonthGroup(val month: YearMonth, val trips: List<Trip>)

/** Months to page through: the current month first (even when empty), then every earlier month that has trips. */
internal fun tripMonthGroups(trips: List<Trip>, current: YearMonth, zone: ZoneId): List<TripMonthGroup> {
    val closed = trips.filter { !it.open }
    val byMonth = closed.groupBy { YearMonth.from(localDate(it.startAt, zone)) }
    val months = (byMonth.keys + current).filter { it <= current }.sortedDescending()
    return months.map { month -> TripMonthGroup(month, byMonth[month].orEmpty().sortedByDescending { it.startAt }) }
}

internal fun localDate(at: Long, zone: ZoneId): LocalDate = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

/** "2026년 10월": the sticky header's title line. */
internal fun monthTitleText(month: YearMonth): String = "${month.year}년 ${month.monthValue}월"

/** "23건 · 412.3 km · 업무 78%": the header's muted second line, so the numbers keep a line of their own; "운행 없음" for an empty month. */
internal fun monthStatsText(group: TripMonthGroup): String {
    if (group.trips.isEmpty()) return "운행 없음"
    val totals = logbookTotals(group.trips)
    return "${totals.trips}건 · ${kmText(totals.totalKm)} · 업무 ${ratioText(totals.ratio)}"
}

/** "2026년 10월 · 23건 · 412.3 km · 업무 78%": both header lines as one sentence, what a screen reader hears for the header. */
internal fun monthHeaderText(group: TripMonthGroup): String = "${monthTitleText(group.month)} · ${monthStatsText(group)}"

/** The one line that stands in for the current month while it has no trips: "11월 운행은 아직 없어요". */
internal fun emptyMonthNote(month: YearMonth): String = "${month.monthValue}월 운행은 아직 없어요"

/** Right and left swipe targets for a row: the suggestion (or 일반 업무) one way, 개인 the other; a direction that would change nothing is off. */
internal fun swipeTargets(trip: Trip, history: List<Trip>): Pair<Pair<TripPurpose, String>?, TripPurpose?> {
    val suggestion = suggestPurpose(trip, history)?.purpose
    val right = when {
        suggestion != null && suggestion != trip.purpose && suggestion != TripPurpose.PERSONAL -> suggestion to "이전 기록과 같게"
        trip.purpose != TripPurpose.BUSINESS -> TripPurpose.BUSINESS to ""
        else -> TripPurpose.COMMUTE to ""
    }
    val left = TripPurpose.PERSONAL.takeIf { it != trip.purpose }
    return right to left
}

/** Which rows of a day close its panel: true for the last trip of each day, in list order. */
internal fun dayPanelEnds(trips: List<Trip>, zone: ZoneId): List<Boolean> {
    val days = trips.map { localDate(it.startAt, zone) }
    return days.indices.map { index -> index == days.lastIndex || days[index + 1] != days[index] }
}

/** One item of the 운행 list, in the order the list shows them. */
internal sealed interface TripListEntry {
    /** A month's sticky header; [first] is the topmost header, which needs no gap above it. */
    data class Month(val group: TripMonthGroup, val first: Boolean) : TripListEntry
    /** The current month while it has no trips: one quiet line, never a second sticky header with a picker of its own. */
    data class EmptyMonth(val month: YearMonth) : TripListEntry
    data class Day(val date: LocalDate, val trips: List<Trip>) : TripListEntry
    /** [last] closes the day's panel with rounded corners. */
    data class Row(val trip: Trip, val last: Boolean) : TripListEntry
    data class End(val first: YearMonth?) : TripListEntry
}

/** True for the entry where [month] starts: its header, or the line standing in for it. */
internal fun TripListEntry.opens(month: YearMonth): Boolean = when (this) {
    is TripListEntry.Month -> group.month == month
    is TripListEntry.EmptyMonth -> this.month == month
    else -> false
}

/**
 * The list for the first [shown] months: each month's sticky header and day panels, then the closing line once every month
 * is in. An empty current month folds into one line above the first month with trips; when it is the only month, the
 * closing line already says there is nothing yet, so it adds nothing.
 */
internal fun tripListEntries(groups: List<TripMonthGroup>, shown: Int, firstMonth: YearMonth?, zone: ZoneId): List<TripListEntry> = buildList {
    groups.take(shown).forEach { group ->
        if (group.trips.isEmpty()) {
            if (groups.size > 1) add(TripListEntry.EmptyMonth(group.month))
            return@forEach
        }
        add(TripListEntry.Month(group, first = none { it is TripListEntry.Month }))
        val ends = dayPanelEnds(group.trips, zone)
        var index = 0
        group.trips.groupBy { localDate(it.startAt, zone) }.forEach { (date, list) ->
            add(TripListEntry.Day(date, list))
            list.forEach { add(TripListEntry.Row(it, ends[index])); index++ }
        }
    }
    if (shown >= groups.size) add(TripListEntry.End(firstMonth))
}

private val dayPanelTop = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private val dayPanelBottom = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun PhoneTripList(data: TripLogData, employeeFilter: String?, paired: Boolean, busy: Boolean, now: Long, pending: Map<String, PendingEdit>,
    commands: PhoneCommands, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()
    val tokens = phoneTokens()
    val all = remember(data.trips, data.profile.demoMode) { phoneTrips(data) }
    val filtered = remember(all, employeeFilter) { all.filter { matchesEmployee(it, employeeFilter) } }
    val current = remember(now) { YearMonth.from(localDate(now, zone)) }
    val groups = remember(filtered, current) { tripMonthGroups(filtered, current, zone) }
    val firstMonth = remember(groups) { groups.lastOrNull { it.trips.isNotEmpty() }?.month }
    val flagged = remember(all) { auditTrips(all).map { it.tripId }.toSet() }
    var shown by rememberSaveable { mutableIntStateOf(1) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(emptySet()) }
    var purposeSheet by rememberSaveable { mutableStateOf(false) }
    var hintShown by rememberSaveable { mutableStateOf(swipeHintShown(context)) }
    val listState = rememberLazyListState()
    // Rows that left the mirror leave the selection too.
    val liveSelected = remember(selected, all) { selected.filter { id -> all.any { it.id == id } }.toSet() }
    val selecting = liveSelected.isNotEmpty()
    val entriesShown = remember(groups, shown, firstMonth) { tripListEntries(groups, shown.coerceIn(1, maxOf(1, groups.size)), firstMonth, zone) }
    BackHandler(enabled = selecting) { selected = emptySet() }
    // Older months join when the last row is near; local data, so no spinner and no delay.
    LaunchedEffect(listState, groups.size) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to listState.layoutInfo.totalItemsCount }.distinctUntilChanged().collect { (last, total) ->
            if (total > 0 && last >= total - 3 && shown < groups.size) shown++
        }
    }
    val unpairedNote = !paired && filtered.isNotEmpty()
    val showHint = paired && !hintShown && filtered.any { it.purpose == TripPurpose.UNCLASSIFIED }
    fun jumpTo(month: YearMonth) {
        val index = groups.indexOfFirst { it.month == month }
        if (index < 0) return
        if (shown < index + 1) shown = index + 1
        val target = tripListEntries(groups, maxOf(shown, index + 1), firstMonth, zone).indexOfFirst { it.opens(month) }
        // The note and the hint sit above the entries in the list.
        val lead = (if (unpairedNote) 1 else 0) + (if (showHint) 1 else 0)
        pickerOpen = false
        if (target >= 0) scope.launch { listState.animateScrollToItem(lead + target) }
    }
    val selectedTrips = all.filter { it.id in liveSelected }
    // Hoisted for the draw lambdas below: the panel glass and the hairline between a day's rows.
    val glass = Scene.glass
    val divider = Scene.divider
    val gap = tokens.cardGap
    // The tab bar floats over the bottom of the list: the list scrolls on under it, the selection bar stands above it.
    val navInset = LocalNavInset.current

    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(Modifier.contentWidth().fillMaxHeight()) {
            if (selecting) Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = tokens.gutter, vertical = 4.dp), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${liveSelected.size}건 선택", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onClick = { selected = emptySet() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("모두 해제") }
                }
            }
            if (filtered.isEmpty() && groups.size <= 1) PhoneEmpty(if (all.isEmpty()) "아직 운행이 없어요" else "이 직원의 운행이 없어요", Modifier.weight(1f).padding(bottom = navInset),
                if (all.isEmpty()) "차량이 출발하면 운행이 여기에 나타나요" else "다른 직원을 고르거나 ‘전체 직원’으로 바꿔 보세요")
            else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                contentPadding = PaddingValues(start = tokens.gutter, end = tokens.gutter, bottom = if (selecting) TAB_SCROLL_END_SPACE else tabScrollBottom(navInset))) {
                if (unpairedNote) item(key = "unpaired") {
                    Muted("파일 사본이에요 · 차량과 연결되면 밀어서 목적을 정할 수 있어요", Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp))
                }
                if (showHint) item(key = "hint") {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("옆으로 밀어 목적을 정할 수 있어요 · 오른쪽 업무, 왼쪽 개인 · 길게 누르면 여러 건", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                            TextButton(onClick = { hintShown = true; markSwipeHintShown(context) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("확인") }
                        }
                    }
                }
                entriesShown.forEach { entry ->
                    when (entry) {
                        is TripListEntry.Month -> stickyHeader(key = "m${entry.group.month}") {
                            MonthHeader(entry.group, groups, pickerOpen, onToggle = { pickerOpen = !pickerOpen }, onPick = ::jumpTo,
                                modifier = Modifier.padding(top = if (entry.first) 0.dp else gap))
                        }
                        // An empty current month is one line, so a single sticky header (and one 월 선택) heads the list.
                        is TripListEntry.EmptyMonth -> item(key = "e${entry.month}") {
                            Muted(emptyMonthNote(entry.month), Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), maxLines = 1)
                        }
                        // The day's heading opens its glass panel; the rows below continue it and the last one closes it.
                        is TripListEntry.Day -> item(key = "d${entry.date}") {
                            Row(Modifier.fillMaxWidth().padding(top = gap).clip(dayPanelTop).background(glass).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(dayLabel(entry.date), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).semantics { heading() })
                                Muted("${entry.trips.size}건 · ${kmText(entry.trips.sumOf { it.distanceKm })}")
                            }
                        }
                        is TripListEntry.Row -> item(key = entry.trip.id) {
                            val trip = entry.trip
                            val shape = if (entry.last) dayPanelBottom else RectangleShape
                            SwipeClassifyRow(trip, all, enabled = paired && !trip.open && !selecting && !busy, onClassify = { purpose -> commands.classify(trip, purpose) },
                                modifier = Modifier.fillMaxWidth().clip(shape).background(glass).drawBehind {
                                    val inset = 16.dp.toPx()
                                    val stroke = 1.dp.toPx()
                                    drawLine(divider, Offset(inset, stroke / 2), Offset(size.width - inset, stroke / 2), stroke)
                                }, shape = shape) {
                                PhoneTripRow(trip, data, flagged = trip.id in flagged, pendingPurpose = pending[trip.id]?.purpose, selecting = selecting, selected = trip.id in liveSelected,
                                    onClick = { if (selecting) selected = if (trip.id in liveSelected) liveSelected - trip.id else liveSelected + trip.id else commands.openTrip(trip.id) },
                                    onLongClick = { if (!trip.open) selected = liveSelected + trip.id },
                                    swipeActions = if (paired && !trip.open) swipeTargets(trip, all).let { (right, left) ->
                                        listOfNotNull(right?.let { CustomAccessibilityAction("${withRo(it.first.label)} 기록") { commands.classify(trip, it.first); true } },
                                            left?.let { CustomAccessibilityAction("${withRo(it.label)} 기록") { commands.classify(trip, it); true } })
                                    } else emptyList(), shape = shape, standalone = false)
                            }
                        }
                        is TripListEntry.End -> item(key = "end") {
                            Muted(entry.first?.let { "${it.year}년 ${it.monthValue}월부터 기록이 시작됐어요" } ?: "아직 완료된 운행이 없어요", Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 6.dp))
                        }
                    }
                }
            }
            // The selection bar stands on the tab bar's top edge rather than under the glass.
            if (selecting) Column(Modifier.fillMaxWidth().padding(bottom = navInset)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = tokens.gutter, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { purposeSheet = true }, enabled = paired && !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text("목적 정하기") }
                    OutlinedButton(onClick = { commands.pickDriver(liveSelected.toList()); selected = emptySet() }, enabled = paired && !busy && data.drivers.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text("직원 지정") }
                }
                if (!paired) Muted("차량과 연결되면 분류할 수 있어요", Modifier.padding(horizontal = tokens.gutter + 4.dp, vertical = 4.dp))
            }
        }
    }
    if (purposeSheet) PhonePurposeSheet(selectedTrips.size, onApply = { purpose -> purposeSheet = false; commands.classifyMany(selectedTrips, purpose); selected = emptySet() },
        onDismiss = { purposeSheet = false })
}

/**
 * The sticky header for one month: the month as its title and the numbers as a muted second line (so the key figure is never
 * cut short), a 48-dp calendar button at the end, and the month picker folded underneath.
 */
@Composable private fun MonthHeader(group: TripMonthGroup, groups: List<TripMonthGroup>, open: Boolean, onToggle: () -> Unit, onPick: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    // Near-opaque glass, not flat black: the header stays readable over rows scrolling under it and matches every other surface.
    Surface(color = Scene.glass.copy(alpha = .96f), shape = RoundedCornerShape(18.dp), border = Scene.edge, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // One heading for both lines: "2026년 10월 · 9건 · 54.0 km · 업무 83%".
                Column(Modifier.weight(1f).clearAndSetSemantics { heading(); contentDescription = monthHeaderText(group) }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(monthTitleText(group.month), style = MaterialTheme.typography.titleSmall, color = Scene.text, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(monthStatsText(group), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = Scene.textMuted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                MonthPickerButton(open, onToggle)
            }
            if (open) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.filter { it.trips.isNotEmpty() || it == groups.first() }.forEach { candidate ->
                    val chosen = candidate.month == group.month
                    FilterChip(selected = chosen, onClick = { onPick(candidate.month) }, colors = sceneChipColors(), border = sceneChipBorder(chosen),
                        label = { Text("${monthTitleText(candidate.month)} · ${candidate.trips.size}건") }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }
}

/** The month picker as a 48-dp icon button; while the picker is open it carries the selection fill, ring and glow (never colour alone). */
@Composable private fun MonthPickerButton(open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val press = remember { MutableInteractionSource() }
    Surface(onClick = onToggle, shape = CircleShape, color = if (open) Scene.selectionFill else Color.Transparent,
        border = if (open) BorderStroke(1.5.dp, Scene.selectionRing) else null, interactionSource = press,
        modifier = modifier.pressable(press).size(48.dp).semantics { contentDescription = "월 선택"; stateDescription = if (open) "펼침" else "접힘"; role = Role.Button }) {
        Box(Modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.DateRange, contentDescription = null, tint = if (open) Scene.glow else Scene.textSecondary, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * The swipe wrapper: the row never dismisses (confirmValueChange is always false), the background names the value about
 * to be applied, and the action fires once the finger releases past 40 % of the width. The background stays clear while
 * the row rests, so a press (which scales the row a little) shows the panel behind it and not a swipe colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SwipeClassifyRow(trip: Trip, history: List<Trip>, enabled: Boolean, onClassify: (TripPurpose) -> Unit, modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp), content: @Composable () -> Unit) {
    val (right, left) = remember(trip.id, trip.purpose, history.size) { swipeTargets(trip, history) }
    val latestClassify by rememberUpdatedState(onClassify)
    val latestRight by rememberUpdatedState(right)
    val latestLeft by rememberUpdatedState(left)
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        when (value) {
            SwipeToDismissBoxValue.StartToEnd -> latestRight?.let { latestClassify(it.first) }
            SwipeToDismissBoxValue.EndToStart -> latestLeft?.let { latestClassify(it) }
            SwipeToDismissBoxValue.Settled -> {}
        }
        false
    }, positionalThreshold = { it * 0.4f })
    val colors = MaterialTheme.colorScheme
    SwipeToDismissBox(state, modifier = modifier.clip(shape), gesturesEnabled = enabled,
        enableDismissFromStartToEnd = enabled && right != null, enableDismissFromEndToStart = enabled && left != null, backgroundContent = {
            val direction = state.dismissDirection
            val toRight = direction == SwipeToDismissBoxValue.StartToEnd
            val swiping = direction != SwipeToDismissBoxValue.Settled
            val armed = state.targetValue != SwipeToDismissBoxValue.Settled
            val purpose = if (toRight) right?.first else left
            val tone = if (purpose == null || purpose == TripPurpose.PERSONAL || trip.purpose != TripPurpose.UNCLASSIFIED) colors.surfaceVariant else colors.tertiaryContainer
            Box(Modifier.fillMaxSize().background(if (swiping) tone else Color.Transparent).padding(horizontal = 20.dp), contentAlignment = if (toRight) Alignment.CenterStart else Alignment.CenterEnd) {
                if (purpose != null && swiping) Text(
                    if (trip.purpose == TripPurpose.UNCLASSIFIED) listOf(purpose.label, right?.second?.takeIf { toRight && it.isNotBlank() }).filterNotNull().joinToString(" · ")
                    else "목적 변경: ${purpose.label}",
                    style = MaterialTheme.typography.labelLarge, fontWeight = if (armed) FontWeight.Bold else FontWeight.Medium,
                    color = if (trip.purpose == TripPurpose.UNCLASSIFIED && purpose != TripPurpose.PERSONAL) colors.onTertiaryContainer else colors.onSurfaceVariant)
            }
        }) { content() }
}

/**
 * One 88dp trip row: time, route, the numbers line, the purpose chip; unclassified rows carry a 3dp edge in the negative
 * colour. A press settles the row to 97 % (ripple stays); [standalone] rows bring their own glass, rows inside a day panel do not.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun PhoneTripRow(trip: Trip, data: TripLogData, flagged: Boolean, pendingPurpose: TripPurpose?, selecting: Boolean, selected: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, swipeActions: List<CustomAccessibilityAction> = emptyList(), shape: Shape = RoundedCornerShape(20.dp), standalone: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val driver = data.drivers.firstOrNull { it.id == trip.driverId }
    val unclassified = (pendingPurpose ?: trip.purpose) == TripPurpose.UNCLASSIFIED
    val press = remember { MutableInteractionSource() }
    val fill = when { selected -> Scene.selectionFill; standalone -> Scene.glass; else -> Color.Transparent }
    Row(Modifier.fillMaxWidth().pressable(press).clip(shape).background(fill)
        .semantics { if (selecting) { this.selected = selected; role = Role.Checkbox } ; if (flagged) stateDescription = "점검 필요"; if (swipeActions.isNotEmpty()) customActions = swipeActions }
        .combinedClickable(interactionSource = press, indication = LocalIndication.current, onClickLabel = if (selecting) "선택 바꾸기" else "운행 상세 보기",
            role = if (selecting) Role.Checkbox else Role.Button, onLongClickLabel = "여러 건 선택", onLongClick = onLongClick, onClick = onClick)
        .heightIn(min = 88.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().heightIn(min = 88.dp).background(if (unclassified) Scene.negative.copy(alpha = .8f) else Color.Transparent))
        if (selecting) Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(start = 6.dp))
        // A minimum, not a fixed width: the times line up across rows and still have room at a 1.3x font scale.
        Column(Modifier.padding(start = 14.dp).widthIn(min = 52.dp)) {
            Text(hm(trip.startAt), style = MaterialTheme.typography.titleSmall)
            Text(if (trip.open) "기록 중" else hm(trip.endAt ?: trip.updatedAt), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(routeText(trip), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(kmText(trip.distanceKm), durationText(trip.durationMillis), driver?.label, if (trip.expenses.isNotEmpty()) formatWon(trip.expenseWon) else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (phoneEditedAfterClose(trip)) Surface(shape = RoundedCornerShape(8.dp), color = colors.surfaceVariant) {
                Text("마감 후 수정", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        if (flagged) Box(Modifier.size(8.dp).clip(CircleShape).background(Scene.negative))
        Box(Modifier.padding(start = 8.dp, end = 14.dp)) { PendingPurposeChip(trip.purpose, pendingPurpose) }
    }
}

/** The purpose chip, showing a just-sent value with a small dot until the car's copy confirms it. */
@Composable internal fun PendingPurposeChip(purpose: TripPurpose, pending: TripPurpose?) {
    val shownPurpose = pending ?: purpose
    Row(Modifier.semantics(mergeDescendants = true) { if (pending != null) stateDescription = "차량에 보내는 중" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (pending != null) Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
        PurposeChip(shownPurpose)
    }
}

/** Bulk purpose: three large radio tiles, nothing pre-selected, one button that names the count. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PhonePurposeSheet(count: Int, onApply: (TripPurpose) -> Unit, onDismiss: () -> Unit) {
    var choice by rememberSaveable { mutableStateOf<String?>(null) }
    val tokens = phoneTokens()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = phoneSheetMaxWidth(),
        containerColor = Scene.rail, contentColor = Scene.text) {
        Column(Modifier.fillMaxWidth().padding(horizontal = tokens.gutter).padding(bottom = 28.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("선택한 ${count}건의 운행 목적", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Muted("차량에 ‘휴대폰 · 일괄’ 출처로 기록되고, 적용 뒤 실행 취소할 수 있어요.")
            purposeChoices.forEach { purpose ->
                val selected = choice == purpose.name
                val press = remember { MutableInteractionSource() }
                Surface(shape = RoundedCornerShape(16.dp), color = if (selected) Scene.selectionFill else Scene.glass, border = selectionBorder(selected),
                    modifier = Modifier.fillMaxWidth().pressable(press).heightIn(min = 56.dp)
                        .selectable(selected = selected, interactionSource = press, indication = LocalIndication.current, role = Role.RadioButton) { choice = purpose.name }) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = selected, onClick = null)
                        Column { Text(purpose.label, style = MaterialTheme.typography.titleSmall, color = if (selected) Scene.glow else Scene.text); Muted(if (purpose.business) "업무 거리에 포함" else "업무 합계에서 제외") }
                    }
                }
            }
            Button(onClick = { choice?.let { onApply(TripPurpose.valueOf(it)) } }, enabled = choice != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                Text("선택한 ${count}건에 적용")
            }
        }
    }
}

/** Driver picker as a bottom sheet; one tap sends SetTripDriver for every trip the caller named. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PhoneDriverSheet(data: TripLogData, title: String, current: String?, onPick: (Driver) -> Unit, onDismiss: () -> Unit) {
    val tokens = phoneTokens()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = phoneSheetMaxWidth(),
        containerColor = Scene.rail, contentColor = Scene.text) {
        Column(Modifier.fillMaxWidth().padding(horizontal = tokens.gutter).padding(bottom = 28.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp))
            if (data.drivers.isEmpty()) Muted("등록된 직원이 없어요. 차량 설정 › 사용자에서 부서·성명을 추가해 주세요.")
            data.drivers.forEach { driver ->
                val chosen = driver.id == current
                val press = remember { MutableInteractionSource() }
                Row(Modifier.fillMaxWidth().pressable(press).clip(RoundedCornerShape(14.dp)).background(if (chosen) Scene.selectionFill else Color.Transparent)
                    .selectable(selected = chosen, interactionSource = press, indication = LocalIndication.current, role = Role.RadioButton) { onPick(driver) }
                    .heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chosen, onClick = null)
                    Text(driver.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
            Spacer(Modifier.size(4.dp))
            Muted("④ 사용자 칸에 들어가는 이름이에요. 차량 기록에 ‘휴대폰’ 출처로 남아요.")
        }
    }
}
