package ai.sharemate.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/*
 * 운행 tab. One list, newest first, with a sticky month header; older months join as the user scrolls (the data is
 * local, so nothing spins). A row swipes right to record the suggested or general business purpose and left for
 * personal; the value appears in the background before the finger lets go, the row never leaves, and the bar at the
 * bottom offers undo. Long-press selects several rows for one purpose or one driver. Open trips and an unpaired phone
 * cannot classify, because only the car writes.
 */

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

/** "2026년 10월 · 23건 · 412.3 km · 업무 78%" — the sticky header's one line of numbers. */
internal fun monthHeaderText(group: TripMonthGroup): String {
    val totals = logbookTotals(group.trips)
    return if (group.trips.isEmpty()) "${group.month.year}년 ${group.month.monthValue}월 · 운행 없음"
    else "${group.month.year}년 ${group.month.monthValue}월 · ${totals.trips}건 · ${kmText(totals.totalKm)} · 업무 ${ratioText(totals.ratio)}"
}

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

/** A trip edited after its month was closed carries the car's sealed-month history line. */
internal fun phoneEditedAfterClose(trip: Trip) = trip.history.any { it.contains("마감된 달의 기록 수정") }

private sealed interface ListEntry {
    data class Month(val group: TripMonthGroup) : ListEntry
    data class Day(val date: LocalDate, val trips: List<Trip>) : ListEntry
    data class Row(val trip: Trip) : ListEntry
    data class End(val first: YearMonth?) : ListEntry
}

private fun entries(groups: List<TripMonthGroup>, shown: Int, firstMonth: YearMonth?, zone: ZoneId): List<ListEntry> = buildList {
    groups.take(shown).forEach { group ->
        add(ListEntry.Month(group))
        group.trips.groupBy { localDate(it.startAt, zone) }.forEach { (date, list) -> add(ListEntry.Day(date, list)); list.forEach { add(ListEntry.Row(it)) } }
    }
    if (shown >= groups.size) add(ListEntry.End(firstMonth))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun PhoneTripList(data: TripLogData, employeeFilter: String?, paired: Boolean, busy: Boolean, now: Long, pending: Map<String, PendingEdit>,
    commands: PhoneCommands, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()
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
    val entriesShown = remember(groups, shown, firstMonth) { entries(groups, shown.coerceIn(1, maxOf(1, groups.size)), firstMonth, zone) }
    BackHandler(enabled = selecting) { selected = emptySet() }
    // Older months join when the last row is near; local data, so no spinner and no delay.
    LaunchedEffect(listState, groups.size) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to listState.layoutInfo.totalItemsCount }.distinctUntilChanged().collect { (last, total) ->
            if (total > 0 && last >= total - 3 && shown < groups.size) shown++
        }
    }
    fun jumpTo(month: YearMonth) {
        val index = groups.indexOfFirst { it.month == month }
        if (index < 0) return
        if (shown < index + 1) shown = index + 1
        val target = entries(groups, maxOf(shown, index + 1), firstMonth, zone).indexOfFirst { it is ListEntry.Month && it.group.month == month }
        pickerOpen = false
        if (target >= 0) scope.launch { listState.animateScrollToItem(target) }
    }
    val showHint = paired && !hintShown && filtered.any { it.purpose == TripPurpose.UNCLASSIFIED }
    val selectedTrips = all.filter { it.id in liveSelected }

    Column(modifier) {
        if (selecting) Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${liveSelected.size}건 선택", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = { selected = emptySet() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("모두 해제") }
            }
        }
        if (filtered.isEmpty() && groups.size <= 1) PhoneEmpty(if (all.isEmpty()) "아직 운행이 없어요" else "이 직원의 운행이 없어요",
            if (all.isEmpty()) "차량이 출발하면 운행이 여기에 나타나요" else "다른 직원을 고르거나 ‘전체 직원’으로 바꿔 보세요", Modifier.weight(1f))
        else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!paired && filtered.isNotEmpty()) item(key = "unpaired") {
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
                    is ListEntry.Month -> stickyHeader(key = "m${entry.group.month}") {
                        MonthHeader(entry.group, groups, pickerOpen, onToggle = { pickerOpen = !pickerOpen }, onPick = ::jumpTo)
                    }
                    is ListEntry.Day -> item(key = "d${entry.date}") {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp, start = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(dayLabel(entry.date), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).semantics { heading() })
                            Muted("${entry.trips.size}건 · ${kmText(entry.trips.sumOf { it.distanceKm })}")
                        }
                    }
                    is ListEntry.Row -> item(key = entry.trip.id) {
                        val trip = entry.trip
                        SwipeClassifyRow(trip, all, enabled = paired && !trip.open && !selecting && !busy, onClassify = { purpose -> commands.classify(trip, purpose) }) {
                            PhoneTripRow(trip, data, flagged = trip.id in flagged, pendingPurpose = pending[trip.id]?.purpose, selecting = selecting, selected = trip.id in liveSelected,
                                onClick = { if (selecting) selected = if (trip.id in liveSelected) liveSelected - trip.id else liveSelected + trip.id else commands.openTrip(trip.id) },
                                onLongClick = { if (!trip.open) selected = liveSelected + trip.id },
                                swipeActions = if (paired && !trip.open) swipeTargets(trip, all).let { (right, left) ->
                                    listOfNotNull(right?.let { CustomAccessibilityAction("${withRo(it.first.label)} 기록") { commands.classify(trip, it.first); true } },
                                        left?.let { CustomAccessibilityAction("${withRo(it.label)} 기록") { commands.classify(trip, it); true } })
                                } else emptyList())
                        }
                    }
                    is ListEntry.End -> item(key = "end") {
                        Muted(entry.first?.let { "${it.year}년 ${it.monthValue}월부터 기록이 시작됐어요" } ?: "아직 완료된 운행이 없어요", Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 6.dp))
                    }
                }
            }
        }
        if (selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { purposeSheet = true }, enabled = paired && !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text("목적 정하기") }
            OutlinedButton(onClick = { commands.pickDriver(liveSelected.toList()); selected = emptySet() }, enabled = paired && !busy && data.drivers.isNotEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text("직원 지정") }
        }
        if (selecting && !paired) Muted("차량과 연결되면 분류할 수 있어요", Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
    }
    if (purposeSheet) PhonePurposeSheet(selectedTrips.size, onApply = { purpose -> purposeSheet = false; commands.classifyMany(selectedTrips, purpose); selected = emptySet() },
        onDismiss = { purposeSheet = false })
}

/** The sticky line for one month, with the month picker folded underneath it. */
@Composable private fun MonthHeader(group: TripMonthGroup, groups: List<TripMonthGroup>, open: Boolean, onToggle: () -> Unit, onPick: (YearMonth) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(monthHeaderText(group), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (open) "닫기" else "월 선택 ▾") }
            }
            if (open) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.filter { it.trips.isNotEmpty() || it == groups.first() }.forEach { candidate ->
                    FilterChip(selected = candidate.month == group.month, onClick = { onPick(candidate.month) },
                        label = { Text("${candidate.month.year}년 ${candidate.month.monthValue}월 · ${candidate.trips.size}건") }, modifier = Modifier.heightIn(min = 40.dp))
                }
            }
        }
    }
}

/**
 * The swipe wrapper: the row never dismisses (confirmValueChange is always false), the background names the value about
 * to be applied, and the action fires once the finger releases past 40 % of the width.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SwipeClassifyRow(trip: Trip, history: List<Trip>, enabled: Boolean, onClassify: (TripPurpose) -> Unit, content: @Composable () -> Unit) {
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
    SwipeToDismissBox(state, modifier = Modifier.clip(RoundedCornerShape(20.dp)), gesturesEnabled = enabled,
        enableDismissFromStartToEnd = enabled && right != null, enableDismissFromEndToStart = enabled && left != null, backgroundContent = {
            val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val armed = state.targetValue != SwipeToDismissBoxValue.Settled
            val purpose = if (toRight) right?.first else left
            val tone = if (purpose == null || purpose == TripPurpose.PERSONAL || trip.purpose != TripPurpose.UNCLASSIFIED) colors.surfaceVariant else colors.tertiaryContainer
            Box(Modifier.fillMaxSize().background(tone).padding(horizontal = 20.dp), contentAlignment = if (toRight) Alignment.CenterStart else Alignment.CenterEnd) {
                if (purpose != null) Text(
                    if (trip.purpose == TripPurpose.UNCLASSIFIED) listOf(purpose.label, right?.second?.takeIf { toRight && it.isNotBlank() }).filterNotNull().joinToString(" · ")
                    else "목적 변경: ${purpose.label}",
                    style = MaterialTheme.typography.labelLarge, fontWeight = if (armed) FontWeight.Bold else FontWeight.Medium,
                    color = if (trip.purpose == TripPurpose.UNCLASSIFIED && purpose != TripPurpose.PERSONAL) colors.onTertiaryContainer else colors.onSurfaceVariant)
            }
        }) { content() }
}

/** One 88dp trip row: time, route, the numbers line, the purpose chip; unclassified rows carry a 2dp edge in the error colour. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun PhoneTripRow(trip: Trip, data: TripLogData, flagged: Boolean, pendingPurpose: TripPurpose?, selecting: Boolean, selected: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, swipeActions: List<CustomAccessibilityAction> = emptyList()) {
    val colors = MaterialTheme.colorScheme
    val driver = data.drivers.firstOrNull { it.id == trip.driverId }
    val unclassified = (pendingPurpose ?: trip.purpose) == TripPurpose.UNCLASSIFIED
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (selected) LocalPleosColors.current.surfaceHigh else colors.surface)
        .semantics { if (selecting) { this.selected = selected; role = Role.Checkbox } ; if (flagged) stateDescription = "점검 필요"; if (swipeActions.isNotEmpty()) customActions = swipeActions }
        .combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = if (selecting) "선택 바꾸기" else "운행 상세 보기", onLongClickLabel = "여러 건 선택", role = if (selecting) Role.Checkbox else Role.Button)
        .heightIn(min = 88.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().heightIn(min = 88.dp).background(if (unclassified) colors.error.copy(alpha = .8f) else Color.Transparent))
        if (selecting) Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(start = 6.dp))
        Column(Modifier.padding(start = 14.dp).width(52.dp)) {
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
        if (flagged) Box(Modifier.size(8.dp).clip(CircleShape).background(LocalPleosColors.current.negative))
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("선택한 ${count}건의 운행 목적", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Muted("차량에 ‘휴대폰 · 일괄’ 출처로 기록되고, 적용 뒤 실행 취소할 수 있어요.")
            purposeChoices.forEach { purpose ->
                val selected = choice == purpose.name
                Surface(shape = RoundedCornerShape(16.dp), color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else LocalPleosColors.current.surfaceHigh,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton) { choice = purpose.name }) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = selected, onClick = null)
                        Column { Text(purpose.label, style = MaterialTheme.typography.titleSmall); Muted(if (purpose.business) "업무 거리에 포함" else "업무 합계에서 제외") }
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp))
            if (data.drivers.isEmpty()) Muted("등록된 직원이 없어요. 차량 설정 › 사용자에서 부서·성명을 추가해 주세요.")
            data.drivers.forEach { driver ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).selectable(selected = driver.id == current, role = Role.RadioButton) { onPick(driver) }
                    .heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = driver.id == current, onClick = null)
                    Text(driver.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
            Spacer(Modifier.size(4.dp))
            Muted("④ 사용자 칸에 들어가는 이름이에요. 차량 기록에 ‘휴대폰’ 출처로 남아요.")
        }
    }
}
