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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import java.time.ZoneId

/*
 * 홈 tab: the greeting and the four destinations over the city, today and this month as widgets, then the trip in progress and
 * "지금 할 일" in the car's order (safety → money the car measured → paperwork), one unclassified trip at a time, then the incident
 * buttons. Every number is a real count; a task card appears only when there is a task, and when the stack empties once in a
 * session the bar says so and points at 현황. In landscape the greeting sits beside the cards and widgets (40/60).
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

/** What the car does next, honest about the signal: automatic recording is promised only while the car is actually listening. */
internal fun autoRecordText(signal: SignalStatus?): String = when (signal) {
    SignalStatus.READY, SignalStatus.RECORDING, SignalStatus.DEMO -> "다음 운행도 자동 기록해요"
    else -> "차량 신호를 받으면 자동 기록해요"
}

/** The all-done line (now spoken by the settings' link card rather than an empty band on the home). */
internal fun homeAllDoneText(signal: SignalStatus?): String = "지금 할 일이 없어요 · ${autoRecordText(signal)}"

private val shownKeysSaver = listSaver<MutableState<Set<String>>, String>(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) })

/** Fades and lifts a card 12dp on its first appearance only (staggered, at most eight items); later recompositions and reduced motion show it at once. */
@Composable private fun HomeAppear(key: String, index: Int, shown: MutableState<Set<String>>, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    val first = remember(key) { key !in shown.value }
    LaunchedEffect(key) { if (first) shown.value = shown.value + key }
    if (reduce || !first) { content(); return }
    val state = remember(key) { MutableTransitionState(false).apply { targetState = true } }
    val lift = with(LocalDensity.current) { 12.dp.roundToPx() }
    val delay = (index * MOTION_STAGGER_MS).coerceAtMost(7 * MOTION_STAGGER_MS)
    AnimatedVisibility(visibleState = state, enter = fadeIn(tween(MOTION_ENTER_MS, delayMillis = delay, easing = FastOutSlowInEasing)) +
        slideInVertically(tween(MOTION_ENTER_MS, delayMillis = delay, easing = FastOutSlowInEasing)) { lift }, exit = fadeOut(tween(120))) { content() }
}

@Composable internal fun PhoneHomeTab(data: TripLogData, link: PhoneLink, paired: Boolean, busy: Boolean, now: Long, photos: PhotoStore, photoVersion: Int,
    capture: (String) -> Unit, fetchPhoto: (String) -> Unit, commands: PhoneCommands, pending: Map<String, PendingEdit>, modifier: Modifier = Modifier,
    onVehicle: () -> Unit = {}, onGreetingBottom: (Float) -> Unit = {}) {
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
        else if (paired && sawTasks && !celebrated) { celebrated = true; followUp(FollowUp("이달 정리가 끝났어요", "현황 보기") { commands.openTab(PhoneTab.REPORT.ordinal) }) }
    }
    val layout = LocalLayout.current
    val fontScale = LocalDensity.current.fontScale
    val scroll = rememberScrollState()
    // The haze under the greeting follows its resting position only; a scrolled hero does not drag the scrim with it.
    val greetingBottom: (Float) -> Unit = { if (scroll.value == 0) onGreetingBottom(it) }
    val onBook = { commands.openTab(PhoneTab.BOOK.ordinal) }
    val onLog = { commands.openTab(PhoneTab.TRIPS.ordinal) }
    val onMonth = { commands.openReport(YearMonth.now()) }
    var index = 0
    BoxWithConstraints(modifier.fillMaxSize()) {
        val split = phoneHomeSplit(maxWidth, maxHeight, fontScale)
        val contentWidth = maxWidth - layout.gutter * 2
        val cardsWidth = if (split) (contentWidth - layout.cardGap * 2) * .6f else contentWidth
        val columns = phoneHeroColumns(cardsWidth, fontScale)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = layout.gutter).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // The car's home in phone size: greeting and the four destinations over the city, then today and this month.
            // Landscape phone: the greeting takes the left 40 % (three headline lines in the narrower column), cards and widgets the right 60 %.
            if (split) Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(layout.cardGap * 2)) {
                PhoneHomeGreeting(data, link, paired, now, Modifier.weight(2f).padding(top = 8.dp), onBottom = greetingBottom, headlineLines = 3)
                Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(layout.cardGap)) {
                    PhoneHomeCards(data, link, now, columns, onVehicle, onBook, onLog, commands.openReport)
                    PhoneWidgets(data, now, onMonth, onToday = onLog)
                }
            } else {
                PhoneHomeHero(data, link, paired, now, onVehicle = onVehicle, onBook = onBook, onLog = onLog, onExport = commands.openReport,
                    columns = columns, onGreetingBottom = greetingBottom)
                PhoneWidgets(data, now, onMonth, onToday = onLog, stacked = columns == 1)
            }
            if (open != null) HomeAppear("open", index++, shown) { PhoneOpenTripCard(open, link) }
            if (paired) {
                if (taskCount > 0) {
                    HomeAppear("tasks-title", index++, shown) { PhoneSectionTitle("지금 할 일", trailing = "${taskCount}건") }
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
                }
            } else HomeAppear("unpaired", index++, shown) {
                Panel(padding = 20.dp) {
                    Eyebrow("파일 사본")
                    Text(if (unclassified.isNotEmpty()) "목적을 정하지 않은 운행 ${unclassified.size}건" else "보고 있는 사본은 가져온 파일 기준이에요", style = MaterialTheme.typography.titleSmall)
                    Muted("차량과 연결하면 여기서 바로 목적을 정하고 경비·특이사항을 입력할 수 있어요. 지금은 보기만 가능해요.")
                    TextButton(onClick = commands.openSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("설정에서 연결") }
                }
            }
            if (paired) HomeAppear("incident-buttons", index++, shown) { PhoneIncidentButtons(busy, commands.send, data, paired) }
        }
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
        Eyebrow(if (count > 1) "운행 목적 확인 · ${count}건 중 가장 최근" else "운행 목적 확인", Scene.negative)
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

/** Three equal purpose tiles with radio semantics, 56dp tall; the chosen one carries its purpose colour as fill plus a ring, never colour alone. */
@Composable internal fun PhonePurposeButtons(current: TripPurpose?, enabled: Boolean, onSelect: (TripPurpose) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        purposeChoices.forEach { purpose ->
            val selected = purpose == current
            val tint = purposeColor(purpose)
            Surface(shape = RoundedCornerShape(14.dp), color = if (selected) tint.copy(alpha = .2f) else LocalPleosColors.current.surfaceHigh,
                border = if (selected) BorderStroke(1.5.dp, tint) else null,
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
