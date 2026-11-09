package ai.sharemate.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/*
 * 현황 tab of the phone app, for the fleet manager: how far this month's logbook has come, what still needs a person,
 * who drove how much, the care deadlines the car knows about, and the Excel export. The same real counts as the car's
 * 월간 screen; month close and reopen go to the car as commands and come back through the mirror. 2.10: the month
 * stepper stays put while the cards page sideways to the new month; two columns from a landscape phone up.
 */

// ───────────────────────── pure helpers ─────────────────────────

/** The car rule: no paging into months that have not happened yet. */
internal fun canGoForward(month: YearMonth, today: LocalDate) = month < YearMonth.from(today)

/** Which way the cards page when the month changes: forward (slide in from the right) for a later month. */
internal fun monthSwapForward(from: YearMonth, to: YearMonth): Boolean = to > from

internal data class ReportStep(val label: String, val done: Boolean)

/** The four steps of a month close, with what is left in each; the last two complete together when the month is closed. */
internal fun phoneCloseSteps(status: MonthStatus): List<ReportStep> = listOf(
    ReportStep("목적·사용자 확인 ${status.unfinished}건", status.unfinished == 0),
    ReportStep("카드 확인 ${status.reviews}건", status.reviews == 0),
    ReportStep("Excel 저장", status.closed != null),
    ReportStep("마감", status.closed != null),
)

private val issuePriority = listOf(IssueType.UNCLASSIFIED, IssueType.NO_DRIVER, IssueType.CARD_ON_PERSONAL, IssueType.ODOMETER_GAP,
    IssueType.ODOMETER_OVERLAP, IssueType.ZERO_DISTANCE, IssueType.IMPLAUSIBLE_SPEED, IssueType.RECOVERED)

/** Issues grouped by type, the ones the phone can act on first. */
internal fun groupIssues(issues: List<TripIssue>): List<Pair<IssueType, List<TripIssue>>> =
    issues.groupBy { it.type }.toList().sortedBy { (type, _) -> issuePriority.indexOf(type).let { if (it < 0) issuePriority.size else it } }

/** What one tap does for an issue row on the phone; null means only the car can fix it (no command exists). */
internal fun issueActionLabel(type: IssueType): String? = when (type) {
    IssueType.UNCLASSIFIED -> "목적 정하기"
    IssueType.NO_DRIVER -> "직원 지정"
    IssueType.CARD_ON_PERSONAL -> "운행 보기"
    else -> null
}

internal fun closedLabel(close: MonthClose): String = "마감됨 · ${phoneTime(close.closedAt, "M/d HH:mm")}"

/** The repository marks edits to a sealed month in the trip's history; the phone shows that as a chip, never as a lock. */
internal fun phoneEditedAfterClose(trip: Trip): Boolean = editedAfterClose(trip)

private fun ratioLabel(totals: LogbookTotals) = totals.ratio?.let { "업무 ${(it * 100).toInt()}%" } ?: "업무 비율 –"

/** Trips of [driverId] among [trips]; null means the unassigned ones (no driver, or a driver no longer registered). */
internal fun tripsOfDriver(trips: List<Trip>, drivers: List<Driver>, driverId: String?): List<Trip> {
    val known = drivers.map { it.id }.toSet()
    return trips.filter { trip -> if (driverId == null) trip.driverId == null || trip.driverId !in known else trip.driverId == driverId }
}

// ───────────────────────── the tab ─────────────────────────

/**
 * 현황 tab body. [onMonth] moves the month; [export] starts the Excel export the shell owns (its progress is [exporting]);
 * [openTrip] and [openEmployee] push the shell's routes.
 */
@Composable internal fun PhoneReportTab(
    data: TripLogData, state: TripLogState, month: YearMonth, onMonth: (YearMonth) -> Unit, busy: Boolean, exporting: Boolean,
    send: (SyncCommand) -> Unit, export: () -> Unit, openTrip: (String) -> Unit, openEmployee: (String?) -> Unit, modifier: Modifier = Modifier,
    employeeFilter: String? = null, paired: Boolean = true, liveOdometerKm: Double? = null, zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
) {
    val tokens = phoneTokens()
    val filterDriver = employeeFilter?.let { id -> data.drivers.firstOrNull { it.id == id } }
    var driverFor by rememberSaveable { mutableStateOf<String?>(null) }
    val swapForward = pageSwap(forward = true)
    val swapBack = pageSwap(forward = false)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // The scroll runs on under the floating tab bar; its last line comes to rest above it.
        Column(Modifier.contentWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = tokens.gutter).padding(bottom = phoneTabScrollBottom()),
            verticalArrangement = Arrangement.spacedBy(tokens.cardGap + 4.dp)) {
            MonthRow(month, today, onMonth)
            // The stepper stays; the cards page sideways to the month that was chosen (a snap under reduced motion).
            AnimatedContent(targetState = month, label = "report-month", transitionSpec = { if (monthSwapForward(initialState, targetState)) swapForward else swapBack }) { shown ->
                ReportBody(data, shown, busy, exporting, send, export, openTrip, openEmployee, employeeFilter, filterDriver, paired, liveOdometerKm, zone, today,
                    twoColumns = !tokens.compact, onDriver = { driverFor = it })
            }
        }
    }
    driverFor?.let { tripId -> DriverSheet(tripId, data, busy, send, onDismiss = { driverFor = null }) }
}

/** The cards of one month: status, issues, care and employees (two columns from a landscape phone up), then the Excel preview and export. */
@Composable private fun ReportBody(
    data: TripLogData, month: YearMonth, busy: Boolean, exporting: Boolean, send: (SyncCommand) -> Unit, export: () -> Unit, openTrip: (String) -> Unit,
    openEmployee: (String?) -> Unit, employeeFilter: String?, filterDriver: Driver?, paired: Boolean, liveOdometerKm: Double?, zone: ZoneId, today: LocalDate,
    twoColumns: Boolean, onDriver: (String) -> Unit,
) {
    val tokens = phoneTokens()
    val demo = data.profile.demoMode
    val monthTrips = remember(data.trips, month, demo) { tripsIn(data.trips.filter { it.demo == demo }, month.atDay(1), month.atEndOfMonth(), zone) }
    val trips = remember(monthTrips, employeeFilter) { monthTrips.filter { employeeFilter == null || it.driverId.orEmpty() == employeeFilter } }
    val status = remember(data, month) { monthStatus(data, month, zone) }
    val summary = remember(data, month) { autopilotSummary(data, month.atDay(1), month.atEndOfMonth(), zone) }
    val issues = remember(monthTrips) { auditTrips(monthTrips) }
    val employees = remember(monthTrips, data.drivers, data.profile.reimbursePerKmWon) { employeeUsage(monthTrips, data.drivers, data.profile.reimbursePerKmWon) }
    val alerts = remember(data.care, liveOdometerKm, data.vehicle.odometerKm, today) { careAlerts(data.care, liveOdometerKm ?: data.vehicle.odometerKm, today) }
    val gap = tokens.cardGap + 4.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        val left: @Composable ColumnScope.() -> Unit = {
            PhoneMonthStatusCard(status, summary, busy, paired, send, exporting, export)
            if (issues.isNotEmpty()) PhoneIssuesCard(issues, data, openTrip, onDriver = onDriver)
        }
        val right: @Composable ColumnScope.() -> Unit = {
            PhoneCareCard(alerts, liveOdometerKm != null)
            if (employees.isNotEmpty()) PhoneEmployeeRows(employees, data.profile.reimbursePerKmWon, openEmployee)
        }
        if (twoColumns) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokens.cardGap)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap), content = left)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap), content = right)
        } else { left(); right() }
        Panel(padding = tokens.panelPadding) {
            Eyebrow("Excel 미리보기 · ${month.year}년 ${month.monthValue}월${filterDriver?.let { " · ${it.name}" } ?: if (employeeFilter == "") " · 직원 미지정" else ""}")
            ExcelPreview(data, trips, month.atDay(1), month.atEndOfMonth(), Modifier.fillMaxWidth().height(540.dp), compact = true)
        }
        val unclassified = trips.count { it.purpose == TripPurpose.UNCLASSIFIED }
        if (unclassified > 0) Muted("정리되지 않은 운행 ${unclassified}건이 비고에 ‘목적 미분류’로 표시돼요.")
        Button(onClick = export, enabled = trips.isNotEmpty() && !exporting && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
            LoadingLabel(if (filterDriver != null) "${filterDriver.name} Excel 내보내기" else if (employeeFilter == "") "직원 미지정 Excel 내보내기" else "Excel로 내보내기", exporting, "만드는 중…")
        }
        Muted("Excel에는 선택한 월 기록과 직원별 요약, 경비·검토 내역, 차량 관리 내역을 담아요. 신고·지급 확정 자료가 아니에요.")
    }
}

@Composable private fun MonthRow(month: YearMonth, today: LocalDate, onMonth: (YearMonth) -> Unit) {
    val forward = canGoForward(month, today)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onMonth(month.minusMonths(1)) }, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp).semantics { contentDescription = "이전 달" }) {
            Text("‹", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
        }
        Text("${month.year}년 ${month.monthValue}월", Modifier.weight(1f).semantics { heading(); liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        TextButton(onClick = { onMonth(month.plusMonths(1)) }, enabled = forward, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp)
            .semantics { contentDescription = if (forward) "다음 달" else "다음 달 · 아직 오지 않은 달" }) {
            Text("›", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** Progress and the four steps to a closed month. The close itself is one command and can be undone from the bar. */
@Composable internal fun PhoneMonthStatusCard(status: MonthStatus, summary: AutopilotSummary, busy: Boolean, paired: Boolean, send: (SyncCommand) -> Unit,
    exporting: Boolean = false, export: (() -> Unit)? = null) {
    val followUp = LocalFollowUp.current
    val tokens = phoneTokens()
    val key = monthKey(status.month)
    val name = "${status.month.monthValue}월"
    val steps = phoneCloseSteps(status)
    Panel(padding = tokens.panelPadding) {
        Eyebrow("이달 운행일지")
        if (status.trips == 0) {
            Text("$name 운행이 없어요", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Muted("차량이 운행을 기록하면 여기서 완성률과 마감 단계를 볼 수 있어요.")
            return@Panel
        }
        val percent = status.percent ?: 0
        // The number cross-fades to its new value; the bar sweeps there (320 ms, a snap under reduced motion). No count-up: a percentage is data.
        val progress by animateFloatAsState(percent / 100f, enterSpec(320), label = "month-progress")
        DigitChange("$name 운행일지 ${percent}% 완성", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
            .semantics { contentDescription = "완성률 ${percent}퍼센트" }, color = Scene.positive, trackColor = Scene.ringTrack)
        Muted(listOfNotNull("운행 ${status.trips}건", "목적 자동 분류 ${summary.autoPurpose}건", "카드 자동 반영 ${summary.cardMatched}건 · ${formatWon(summary.cardWon)}",
            if (summary.waiting > 0) "카드 결제 ${summary.waiting}건 대기 중" else null).joinToString(" · "))
        HorizontalDivider(color = Scene.divider)
        steps.forEachIndexed { index, step ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { stateDescription = if (step.done) "완료" else "남음" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(if (step.done) Scene.positive else Scene.fill), contentAlignment = Alignment.Center) {
                    Text(if (step.done) "✓" else "${index + 1}", style = MaterialTheme.typography.labelSmall, color = if (step.done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clearAndSetSemantics {})
                }
                Text(step.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (index == 2 && export != null && status.closed == null) TextButton(onClick = export, enabled = !exporting && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    LoadingLabel("저장", exporting, "만드는 중…")
                }
            }
        }
        val closed = status.closed
        if (closed != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.weight(1f)) {
                    Text(closedLabel(closed), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = {
                    send(SyncCommand.ReopenMonth(key))
                    followUp(FollowUp("$name 마감을 해제했어요", "다시 마감") { send(SyncCommand.CloseMonth(key)) })
                }, enabled = !busy && paired, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("마감 해제") }
            }
            Muted("마감 뒤에 고친 운행은 ‘마감 후 수정’으로 표시돼요. 기록은 잠기지 않아요.")
        } else {
            Button(onClick = {
                send(SyncCommand.CloseMonth(key))
                followUp(FollowUp("${name}을 마감했어요", "마감 해제") { send(SyncCommand.ReopenMonth(key)) })
            }, enabled = status.ready && paired && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text("$name 마감") }
            Muted(when {
                !paired -> "차량과 연결되면 마감할 수 있어요."
                status.ready -> "확인할 일이 없어요. 마감하면 이 달의 합계가 기록으로 남아요."
                else -> "확인 ${status.issues}건을 끝내면 마감할 수 있어요."
            })
        }
    }
}

/** What still needs a person this month, grouped by kind, with the one tap that handles it where the phone can. */
@Composable internal fun PhoneIssuesCard(issues: List<TripIssue>, data: TripLogData, openTrip: (String) -> Unit, onDriver: (String) -> Unit) {
    val groups = remember(issues) { groupIssues(issues) }
    var expanded by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    Panel(padding = phoneTokens().panelPadding) {
        Eyebrow("확인사항 ${issues.size}건 · 바로 처리")
        groups.forEachIndexed { groupIndex, (type, items) ->
            if (groupIndex > 0) HorizontalDivider(color = Scene.divider)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${type.label} ${items.size}건", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                val action = issueActionLabel(type)
                if (action == null) Muted("${type.detail} 차량에서 처리할 수 있어요.")
                val open = type.name in expanded
                val shown = if (open) items else items.take(3)
                shown.forEach { issue ->
                    val trip = data.trips.firstOrNull { it.id == issue.tripId } ?: return@forEach
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${dayLabel(localDate(trip.startAt))} ${hm(trip.startAt)} · ${kmText(trip.distanceKm)}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted(listOfNotNull(trip.destination ?: trip.endAddress, issue.gapKm?.let { "%,.1f km 비어 있음".format(Locale.KOREA, it) }).joinToString(" · ").ifBlank { "주소 미기록" })
                        }
                        if (action != null) TextButton(onClick = { if (type == IssueType.NO_DRIVER) onDriver(trip.id) else openTrip(trip.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
                    }
                }
                if (items.size > 3) TextButton(onClick = { expanded = if (open) expanded - type.name else expanded + type.name }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (open) "접기" else "${items.size - 3}건 더 보기")
                }
            }
        }
    }
}

/** The car's care deadlines, read-only on the phone: dates the manager typed and the odometer the car reported, nothing estimated. */
@Composable internal fun PhoneCareCard(alerts: List<CareAlert>, liveOdometer: Boolean = false) {
    Panel(padding = phoneTokens().panelPadding) {
        Eyebrow("차량 관리 · 등록된 날짜 기준 · 자동 조회는 하지 않아요")
        if (alerts.isEmpty()) Muted("차량 설정 › 법인·차량에서 검사·보험·리스 만기와 정비 주기를 적으면 여기서 함께 보여요.")
        alerts.forEach { alert ->
            // Status colours by scene: negative past the deadline, warn when it is near, muted while there is time; the text says the same.
            val color = when (alert.level) { CareLevel.OVERDUE -> Scene.negative; CareLevel.SOON -> Scene.warn; CareLevel.INFO -> Scene.textMuted }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {
                stateDescription = when (alert.level) { CareLevel.OVERDUE -> "기한 지남"; CareLevel.SOON -> "곧 기한"; CareLevel.INFO -> "여유 있음" } },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(alert.title, style = MaterialTheme.typography.titleSmall, color = if (alert.level == CareLevel.OVERDUE) Scene.negative else MaterialTheme.colorScheme.onSurface)
                    Muted(alert.detail + if (alert.kind == CareKind.SERVICE && !liveOdometer) " · 마지막 수신 계기판 기준" else "")
                }
            }
        }
    }
}

/** One row per employee; tapping drills into that person's month. The settlement figure is a reference, never a payout. */
@Composable internal fun PhoneEmployeeRows(employees: List<EmployeeUsage>, perKmWon: Long, openEmployee: (String?) -> Unit) {
    Panel(padding = phoneTokens().panelPadding) {
        Eyebrow("직원별 현황 · 선택하면 운행 보기")
        employees.forEach { employee ->
            val label = employee.driver?.label ?: "직원 미지정"
            val press = remember { MutableInteractionSource() }
            Surface(onClick = { openEmployee(employee.driver?.id) }, shape = RoundedCornerShape(14.dp), color = Scene.fill, interactionSource = press,
                modifier = Modifier.fillMaxWidth().pressable(press).heightIn(min = 64.dp).semantics { role = Role.Button; contentDescription = "$label 현황 보기" }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted("${kmText(employee.totals.totalKm)} · ${employee.totals.trips}건 · ${ratioLabel(employee.totals)} · 경비 ${formatWon(employee.totals.expenseWon)}")
                        if (perKmWon > 0 && employee.driver != null) Muted("정산 참고 ${formatWon(employee.reimbursementWon)} · 지급 확정 아님")
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
        if (perKmWon > 0) Muted("정산 참고는 업무 거리 × ${formatWon(perKmWon)}/km이고 등록 경비와 별도예요.")
    }
}

/** Driver choice for a trip without one; the shell has no dialog for this on the phone, so the sheet lives here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DriverSheet(tripId: String, data: TripLogData, busy: Boolean, send: (SyncCommand) -> Unit, onDismiss: () -> Unit) {
    val followUp = LocalFollowUp.current
    val tokens = phoneTokens()
    val trip = data.trips.firstOrNull { it.id == tripId }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetMaxWidth = phoneSheetMaxWidth(), containerColor = Scene.rail, contentColor = Scene.text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = tokens.gutter).padding(bottom = 32.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("이 운행의 사용자", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            trip?.let { Muted("${dayLabel(localDate(it.startAt))} ${hm(it.startAt)} · ${kmText(it.distanceKm)} · ${it.destination ?: it.endAddress ?: "주소 미기록"}") }
            if (data.drivers.isEmpty()) Muted("등록된 사용자가 없어요. 차량 설정에서 부서·성명을 추가해 주세요.")
            data.drivers.forEach { driver ->
                val selected = trip?.driverId == driver.id
                val press = remember { MutableInteractionSource() }
                Row(Modifier.fillMaxWidth().pressable(press).clip(RoundedCornerShape(14.dp)).background(if (selected) Scene.selectionFill else Color.Transparent)
                    .selectable(selected = selected, interactionSource = press, indication = LocalIndication.current, enabled = !busy, role = Role.RadioButton) {
                        val previous = trip?.driverId
                        send(SyncCommand.SetTripDriver(tripId, driver.id))
                        followUp(if (previous != null && previous != driver.id) FollowUp("${driver.name} 님으로 바꿨어요", "실행 취소") { send(SyncCommand.SetTripDriver(tripId, previous)) }
                            else FollowUp("${driver.name} 님으로 지정했어요"))
                        onDismiss()
                    }.heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = selected, onClick = null, enabled = !busy)
                    Text(driver.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** A labelled number whose value cross-fades when the figure changes (no count-up: the figure is data). */
@Composable private fun StatChange(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DigitChange(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurface)
    }
}

// ───────────────────────── 현황 › 직원 ─────────────────────────

/** One employee's month, reached from 직원별 현황: a breadcrumb back, the totals, and the trips to open one by one. */
@Composable internal fun PhoneEmployeePage(driverId: String?, data: TripLogData, month: YearMonth, busy: Boolean, send: (SyncCommand) -> Unit,
    openTrip: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, zone: ZoneId = ZoneId.systemDefault(),
    exporting: Boolean = false, export: (() -> Unit)? = null) {
    val tokens = phoneTokens()
    val demo = data.profile.demoMode
    val driver = driverId?.let { id -> data.drivers.firstOrNull { it.id == id } }
    val name = driver?.label ?: "직원 미지정"
    val monthTrips = remember(data.trips, month, demo) { tripsIn(data.trips.filter { it.demo == demo }, month.atDay(1), month.atEndOfMonth(), zone) }
    val trips = remember(monthTrips, driverId, data.drivers) { tripsOfDriver(monthTrips, data.drivers, driverId).sortedByDescending { it.startAt } }
    val totals = remember(trips) { logbookTotals(trips) }
    val reimbursement = if (driver != null && data.profile.reimbursePerKmWon > 0) reimbursements(trips, data.profile.reimbursePerKmWon).sumOf { it.amountWon } else null
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // Like the tabs: the trips scroll on under the floating tab bar when one is showing, and the last comes to rest above it.
        Column(Modifier.contentWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = tokens.gutter).padding(bottom = phoneTabScrollBottom()),
            verticalArrangement = Arrangement.spacedBy(tokens.cardGap + 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "현황으로 돌아가기" }) {
                    Text("‹", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
                }
                Text("현황 › $name", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Panel(padding = tokens.panelPadding) {
                Eyebrow("${month.year}년 ${month.monthValue}월 · $name")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatChange("전체", kmText(totals.totalKm), Modifier.weight(1f))
                    StatChange("업무", kmText(totals.businessKm), Modifier.weight(1f))
                    StatChange("운행", "${totals.trips}건", Modifier.weight(.8f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatChange("업무 비율", ratioText(totals.ratio), Modifier.weight(1f))
                    StatChange("등록 경비", formatWon(totals.expenseWon), Modifier.weight(1f))
                    if (reimbursement != null) StatChange("정산 참고", formatWon(reimbursement), Modifier.weight(.8f))
                }
                if (reimbursement != null) Muted("정산 참고는 업무 거리 × ${formatWon(data.profile.reimbursePerKmWon)}/km · 지급 확정 아님")
                if (driverId == null) Muted("사용자가 없는 운행이에요. 운행 상세에서 직원을 지정하면 그 직원 현황으로 옮겨가요.")
                if (export != null) OutlinedButton(onClick = export, enabled = trips.isNotEmpty() && !exporting && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                    LoadingLabel("$name Excel 내보내기", exporting, "만드는 중…")
                }
            }
            if (trips.isEmpty()) Panel(padding = tokens.panelPadding) { Text("이 달의 운행이 없어요", style = MaterialTheme.typography.titleMedium); Muted("다른 달을 보려면 현황에서 월을 바꿔 주세요.") }
            trips.forEach { trip ->
                val flagged = trip.purpose == TripPurpose.UNCLASSIFIED
                val sealedEdit = phoneEditedAfterClose(trip)
                val press = remember(trip.id) { MutableInteractionSource() }
                Surface(onClick = { openTrip(trip.id) }, shape = RoundedCornerShape(18.dp), color = Scene.glass, contentColor = Scene.text, border = Scene.edge, interactionSource = press,
                    modifier = Modifier.fillMaxWidth().pressable(press).heightIn(min = 72.dp).semantics {
                        role = Role.Button; contentDescription = "운행 상세 보기"
                        listOfNotNull(if (flagged) "목적 미분류" else null, if (sealedEdit) "마감 후 수정" else null).takeIf { it.isNotEmpty() }?.let { stateDescription = it.joinToString(" · ") }
                    }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${dayLabel(localDate(trip.startAt))} ${hm(trip.startAt)} · ${kmText(trip.distanceKm)}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted(listOfNotNull(trip.destination ?: trip.endAddress ?: "주소 미기록", trip.expenses.takeIf { it.isNotEmpty() }?.let { formatWon(trip.expenseWon) }).joinToString(" · "))
                            if (sealedEdit) Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.clearAndSetSemantics {}) {
                                Text("마감 후 수정", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                        PurposeChip(trip.purpose)
                    }
                }
            }
        }
    }
}
