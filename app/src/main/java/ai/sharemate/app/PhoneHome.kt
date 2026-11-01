package ai.sharemate.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.util.Locale

/*
 * 홈 tab: the trip in progress, then "지금 할 일" in the car's order (safety → money the car measured → paperwork), one
 * unclassified trip at a time, then this month's logbook with its completion ring, then the incident buttons. Every
 * number is a real count; when the stack empties once in a session the bar says so and points at 현황.
 */

/** Trips the home stack still asks about: closed, unclassified, and not already answered in flight. */
internal fun homeUnclassified(data: TripLogData, pending: Map<String, PendingEdit>): List<Trip> = phoneTrips(data)
    .filter { it.purpose == TripPurpose.UNCLASSIFIED && !it.open && (pending[it.id]?.purpose ?: TripPurpose.UNCLASSIFIED) == TripPurpose.UNCLASSIFIED }
    .sortedByDescending { it.startAt }

/** How many earlier trips to the same place a person recorded with [purpose]; automatic and suggested answers do not count as evidence. */
internal fun phoneSameDestinationCount(trip: Trip, history: List<Trip>, purpose: TripPurpose): Int {
    val key = trip.destination?.takeIf { it.isNotBlank() } ?: trip.endAddress?.takeIf { it.isNotBlank() } ?: return 0
    return history.count { it.id != trip.id && !it.open && it.demo == trip.demo && it.startAt < trip.startAt && it.purpose == purpose &&
        it.purposeSource != EditSource.AUTO && it.purposeSource != EditSource.SUGGESTED && (it.destination ?: it.endAddress) == key }
}

/** The all-done line, honest about the signal: automatic recording is promised only while the car is actually listening. */
internal fun homeAllDoneText(signal: SignalStatus?): String = when (signal) {
    SignalStatus.READY, SignalStatus.RECORDING, SignalStatus.DEMO -> "지금 할 일이 없어요 · 다음 운행도 자동 기록해요"
    else -> "지금 할 일이 없어요 · 차량 신호를 받으면 자동 기록해요"
}

private val shownKeysSaver = listSaver<MutableState<Set<String>>, String>(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) })

/** Fades and lifts a card 12dp on its first appearance only; later recompositions and reduced motion show it at once. */
@Composable private fun HomeAppear(key: String, index: Int, shown: MutableState<Set<String>>, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    val first = remember(key) { key !in shown.value }
    LaunchedEffect(key) { if (first) shown.value = shown.value + key }
    if (reduce || !first) { content(); return }
    val state = remember(key) { MutableTransitionState(false).apply { targetState = true } }
    val lift = with(LocalDensity.current) { 12.dp.roundToPx() }
    val delay = (index * MOTION_STAGGER_MS).coerceAtMost(240)
    AnimatedVisibility(visibleState = state, enter = fadeIn(tween(MOTION_ENTER_MS, delayMillis = delay, easing = FastOutSlowInEasing)) +
        slideInVertically(tween(MOTION_ENTER_MS, delayMillis = delay, easing = FastOutSlowInEasing)) { lift }, exit = fadeOut(tween(120))) { content() }
}

@Composable internal fun PhoneHomeTab(data: TripLogData, link: PhoneLink, paired: Boolean, busy: Boolean, now: Long, photos: PhotoStore, photoVersion: Int,
    capture: (String) -> Unit, fetchPhoto: (String) -> Unit, commands: PhoneCommands, pending: Map<String, PendingEdit>, modifier: Modifier = Modifier) {
    val zone = remember { ZoneId.systemDefault() }
    val followUp = LocalFollowUp.current
    val demo = data.profile.demoMode
    val open = data.open?.takeIf { it.demo == demo }
    val incidents = remember(data) { openIncidents(data) }
    val reviews = remember(data) { data.charges.filter { it.state == ChargeState.REVIEW && it.demo == demo }.sortedBy { it.at } }
    val charge = data.pendingCharge?.takeIf { it.demo == demo }
    val unclassified = remember(data, pending) { homeUnclassified(data, pending) }
    val taskCount = incidents.size + reviews.size + (if (charge != null) 1 else 0) + unclassified.size
    val shown = rememberSaveable(saver = shownKeysSaver) { mutableStateOf(emptySet()) }
    var reviewsOpen by rememberSaveable { mutableStateOf(false) }
    // The bar celebrates once per session, and only after the stack was seen with something in it.
    var sawTasks by rememberSaveable { mutableStateOf(false) }
    var celebrated by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(taskCount, paired) {
        if (taskCount > 0) sawTasks = true
        else if (paired && sawTasks && !celebrated) { celebrated = true; followUp(FollowUp("이달 정리가 끝났어요", "현황 보기") { commands.openTab(3) }) }
    }
    var index = 0
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (open != null) HomeAppear("open", index++, shown) { PhoneOpenTripCard(open, link) }
        if (paired) {
            if (taskCount > 0) {
                HomeAppear("tasks-title", index++, shown) { PhoneSectionTitle("지금 할 일", "${taskCount}건") }
                incidents.forEach { incident ->
                    HomeAppear("incident:${incident.id}", index++, shown) { PhoneIncidentCard(incident, data, busy, commands.send, photos, photoVersion, capture, fetchPhoto) }
                }
                if (reviews.size == 1) HomeAppear("review:${reviews[0].id}", index++, shown) { PhoneChargeReviewCard(reviews[0], data, busy, commands.send) }
                else if (reviews.size > 1) HomeAppear("reviews", index++, shown) {
                    ReviewStack(reviews, reviewsOpen, onToggle = { reviewsOpen = !reviewsOpen }) { PhoneChargeReviewCard(it, data, busy, commands.send) }
                }
                if (charge != null) HomeAppear("charge:${charge.endAt}", index++, shown) { PhoneChargeCard(charge, busy, commands.send) }
                if (unclassified.isNotEmpty()) HomeAppear("purpose", index++, shown) {
                    // The next trip slides up into the same slot after each answer.
                    val reduce = LocalReduceMotion.current
                    AnimatedContent(targetState = unclassified.first().id, label = "home-purpose", transitionSpec = {
                        if (reduce) fadeIn(snap()) togetherWith fadeOut(snap())
                        else fadeIn(tween(MOTION_ENTER_MS, easing = FastOutSlowInEasing)) + slideInVertically(tween(MOTION_ENTER_MS, easing = FastOutSlowInEasing)) { it / 6 } togetherWith fadeOut(tween(120))
                    }) { id ->
                        val trip = unclassified.firstOrNull { it.id == id } ?: unclassified.first()
                        PhoneUnclassifiedCard(trip, unclassified.size, data, enabled = !busy, onClassify = { commands.classify(trip, it) }, onOpen = { commands.openTrip(trip.id) },
                            onDriver = { commands.pickDriver(listOf(trip.id)) })
                    }
                }
            } else HomeAppear("done", index++, shown) {
                Surface(shape = RoundedCornerShape(16.dp), color = LocalPleosColors.current.surfaceHigh, modifier = Modifier.fillMaxWidth()) {
                    Text(homeAllDoneText(link.signal), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
                }
            }
        } else HomeAppear("unpaired", index++, shown) {
            Panel(padding = 20.dp) {
                Eyebrow("파일 사본")
                Text(if (unclassified.isNotEmpty()) "목적을 정하지 않은 운행 ${unclassified.size}건" else "보고 있는 사본은 가져온 파일 기준이에요", style = MaterialTheme.typography.titleSmall)
                Muted("차량과 연결하면 여기서 바로 목적을 정하고 경비·특이사항을 입력할 수 있어요. 지금은 보기만 가능해요.")
                TextButton(onClick = commands.openSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("설정에서 연결") }
            }
        }
        HomeAppear("month", index++, shown) { MonthLogbookCard(data, now, zone) }
        if (paired) HomeAppear("incident-buttons", index++, shown) { PhoneIncidentButtons(busy, commands.send) }
        if (paired) Muted("차량이 원본이에요. 여기서 바꾼 것은 차량에 ‘휴대폰’ 출처로 기록되고 다시 내려와요.", Modifier.padding(horizontal = 4.dp))
    }
}

/** Several card questions fold into one line with the full count; opening it shows every card, not the first three. */
@Composable private fun ReviewStack(reviews: List<CardCharge>, open: Boolean, onToggle: () -> Unit, card: @Composable (CardCharge) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 18.dp, end = 6.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                    Text("카드 결제 확인 ${reviews.size}건", style = MaterialTheme.typography.titleSmall)
                    Text("총 ${formatWon(reviews.sumOf { it.amountWon })} · 한 건씩 맞는 운행을 확인해요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (open) "접기" else "펼치기") }
            }
        }
        if (open) reviews.forEach { card(it) }
    }
}

/** One unclassified trip, three purpose buttons, and the suggestion with the reason it is suggested. */
@Composable internal fun PhoneUnclassifiedCard(trip: Trip, count: Int, data: TripLogData, enabled: Boolean, onClassify: (TripPurpose) -> Unit, onOpen: () -> Unit, onDriver: () -> Unit) {
    val history = remember(data.trips) { phoneTrips(data) }
    val suggestion = remember(trip.id, history.size) { suggestPurpose(trip, history) }
    val sameCount = remember(trip.id, suggestion?.purpose, history.size) { suggestion?.let { phoneSameDestinationCount(trip, history, it.purpose) } ?: 0 }
    val driver = data.drivers.firstOrNull { it.id == trip.driverId }
    val zone = remember { ZoneId.systemDefault() }
    val date = localDate(trip.startAt, zone)
    Panel(padding = 20.dp) {
        Eyebrow(if (count > 1) "운행 목적 확인 · ${count}건 중 가장 최근" else "운행 목적 확인", LocalPleosColors.current.negative)
        Text("${date.monthValue}월 ${date.dayOfMonth}일 ${hm(trip.startAt)} · ${kmText(trip.distanceKm)} · ${trip.destination ?: trip.endAddress ?: "주소 미기록"}",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Muted(listOfNotNull(trip.startAddress?.let { "$it 출발" }, durationText(trip.durationMillis)).joinToString(" · ").ifBlank { "계기판 거리로 기록" })
        suggestion?.let { previous ->
            Surface(shape = RoundedCornerShape(16.dp), color = LocalPleosColors.current.surfaceHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (sameCount > 1) "‘${previous.destination ?: previous.endAddress}’ 운행을 지난 ${sameCount}회 ${previous.purpose.label}로 기록했어요"
                        else "‘${previous.destination ?: previous.endAddress}’ 운행은 지난번 ${previous.purpose.label}였어요",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                    TextButton(onClick = { onClassify(previous.purpose) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("같게 기록") }
                }
            }
        }
        PhonePurposeButtons(current = null, enabled = enabled, onSelect = onClassify)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Muted("사용자 ${driver?.label ?: "미지정"}", Modifier.weight(1f))
            if (data.drivers.isNotEmpty()) TextButton(onClick = onDriver, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("변경") }
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("상세 보기") }
        }
    }
}

/** Three equal purpose tiles with radio semantics, 56dp tall, coloured by purpose when selected. */
@Composable internal fun PhonePurposeButtons(current: TripPurpose?, enabled: Boolean, onSelect: (TripPurpose) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        purposeChoices.forEach { purpose ->
            val selected = purpose == current
            val tint = purposeColor(purpose)
            Surface(shape = RoundedCornerShape(14.dp), color = if (selected) tint.copy(alpha = .2f) else LocalPleosColors.current.surfaceHigh,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onSelect(purpose) }
                    .semantics { this.selected = selected; role = Role.RadioButton }) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(purpose.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (purpose.business) "업무 포함" else "업무 제외", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** This month's logbook: completion ring from the autopilot summary, its numbers, then the distance hero. */
@Composable private fun MonthLogbookCard(data: TripLogData, now: Long, zone: ZoneId) {
    val today = localDate(now, zone)
    val first = today.withDayOfMonth(1)
    val summary = remember(data, today) { autopilotSummary(data, first, today, zone) }
    val totals = remember(data, today) { logbookTotals(tripsIn(phoneTrips(data), first, today, zone)) }
    Panel(padding = 20.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("이달 운행일지 · ${today.monthValue}월")
            Text("${summary.trips}건", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RatioRing(summary.percent?.let { it / 100.0 }, 96.dp, "완성률")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("남은 확인", if (summary.trips == 0) "운행 없음" else "${summary.left}건")
                Stat("목적 자동 분류", "${summary.autoPurpose}건")
                Stat("카드 자동 반영", "${summary.cardMatched}건 · ${formatWon(summary.cardWon)}")
            }
        }
        if (summary.waiting > 0) Muted("카드 결제 ${summary.waiting}건이 차량 기록을 기다리고 있어요")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SharemateTheme(compact = true, dark = true) {
            Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF1D1E21), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Eyebrow(if (data.profile.demoMode) "DEMO / 시연 월간 현황" else "MONTHLY / 이달의 이동", Color(0xFFE1B69C))
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("%.1f".format(Locale.KOREA, totals.totalKm), style = scaledDisplay(MaterialTheme.typography.displaySmall, MaterialTheme.typography.headlineMedium).copy(fontFeatureSettings = "tnum"))
                        Text("km · 전체 운행", style = MaterialTheme.typography.labelMedium, color = Color(0xFFC7C9CE), modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Stat("업무 거리", kmText(totals.businessKm), Modifier.weight(1.2f), valueColor = Color(0xFFE1B69C))
                        Stat("업무 비율", ratioText(totals.ratio), Modifier.weight(0.8f))
                        Stat("등록 경비", formatWon(totals.expenseWon), Modifier.weight(1.2f))
                    }
                }
            }
        }
        Muted(when {
            summary.trips == 0 -> "이번 달 운행이 아직 없어요."
            summary.left > 0 -> "미분류·사용자 미지정 운행은 업무 합계에서 빠져요. 현황 탭에서 한눈에 볼 수 있어요."
            else -> "이달 운행의 목적을 모두 확인했어요. 현황 탭에서 Excel로 저장할 수 있어요."
        })
    }
}
