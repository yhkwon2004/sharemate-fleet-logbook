package ai.sharemate.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/*
 * The parked home screen: one card at a time, with the small surfaces around it. The stepper above the card counts the
 * same list the driving screen announced, the finished-trip card tells the driver the car already did the work, the
 * purpose question offers the driver's own earlier answer as its default, several card payments arriving at once get a
 * banner with per-line reversal, and the driver check asks once a day. Nothing here is shown while the car is moving.
 */

// ───────────────────────── 한 장씩 보여 주는 할 일 ─────────────────────────

/** The one remembered answer to "do you want to type the amount now or wait for the card?", reset for each new stop. */
@Composable internal fun rememberChargePrompt(data: TripLogData): MutableState<ChargePrompt> =
    rememberSaveable(pendingChargeSession(data)?.endAt ?: 0L) { mutableStateOf(ChargePrompt.AUTO) }

/**
 * What is on the card right now; one value per visible card so AnimatedContent swaps exactly when the question changes. All
 * three null is the empty stack, which draws no card (the all-done card where the stack offers one).
 */
internal data class StackKey(val kind: TaskKind?, val finishedId: String?, val reclassifyId: String?) {
    /** True when this names a card to draw. */
    val showsCard: Boolean get() = kind != null || finishedId != null || reclassifyId != null
}

/** Ids or day keys kept as one saveable string (the stack's answers, the shell's answered days): joined by commas, and split back. */
internal fun Set<String>.joined() = joinToString(",")
internal fun String.toIdSet() = split(',').filter { it.isNotBlank() }.toSet()

/**
 * The card the task stack shows, from what it reads. First the purpose question reopened from the finished-trip card
 * ([reclassifyId], while that trip is still the last one finished and not recording; a newer trip retires it), then the
 * finished trip's own card unless something the car measured (an incident, a card question, a charge) is waiting, then the
 * first of [tasks]. Pure, so the home can tell whether the stack draws a card before it composes the stack.
 */
internal fun parkedStackKey(tasks: List<ParkedTask>, data: TripLogData, lastFinishedId: String?, now: Long, dismissed: Set<String>,
    reclassifyId: String?): StackKey {
    val reclassify = reclassifyId?.takeIf { it == lastFinishedId }?.let { id -> data.trips.firstOrNull { it.id == id && !it.open } }
    val finished = finishedTripToShow(data, lastFinishedId, now, dismissed)
    val first = tasks.firstOrNull()
    // Safety and money the car measured come before the good news; everything else waits behind it.
    val finishedFirst = finished != null && first?.kind !in setOf(TaskKind.INCIDENT, TaskKind.CARD_REVIEW, TaskKind.CHARGE)
    return when {
        reclassify != null -> StackKey(null, null, reclassify.id)
        finishedFirst -> StackKey(null, finished!!.id, null)
        else -> StackKey(first?.kind, null, null)
    }
}

/**
 * The stack's own answers: the finished-trip cards already acknowledged ([dismissed], ids joined by commas) and the trip
 * whose purpose is being changed from its card ([reclassifyId]). The home holds them ([rememberParkedStackAnswers]), so the
 * answers outlive the stack leaving the composition and the home can ask [key] whether the stack draws a card at all.
 */
@Stable
internal class ParkedStackAnswers(dismissed: String = "", reclassifyId: String? = null) {
    var dismissed by mutableStateOf(dismissed)
    var reclassifyId by mutableStateOf(reclassifyId)

    /** The card the stack shows for [tasks] with these answers ([parkedStackKey]). */
    fun key(tasks: List<ParkedTask>, data: TripLogData, lastFinishedId: String?, now: Long): StackKey =
        parkedStackKey(tasks, data, lastFinishedId, now, dismissed.toIdSet(), reclassifyId)

    /** Acknowledges the finished-trip card of [tripId]; with [reclassify] the purpose question for that trip opens in its place. */
    fun answer(tripId: String, reclassify: Boolean = false) {
        dismissed = (dismissed.toIdSet() + tripId).joined()
        if (reclassify) reclassifyId = tripId
    }

    companion object {
        val Saver: Saver<ParkedStackAnswers, Any> = listSaver<ParkedStackAnswers, String>(save = { listOf(it.dismissed, it.reclassifyId.orEmpty()) },
            restore = { ParkedStackAnswers(it[0], it[1].ifEmpty { null }) })
    }
}

/** The task stack's answers, kept across rotation and a return to the tab; hold them where the stack is decided on. */
@Composable internal fun rememberParkedStackAnswers(): ParkedStackAnswers = rememberSaveable(saver = ParkedStackAnswers.Saver) { ParkedStackAnswers() }

/**
 * The home task stack: [TaskStepper] over an `AnimatedContent` that shows the card [parkedStackKey] picks. [other] renders
 * the kinds whose cards live elsewhere (MONTH_CLOSE, CARE); [chargePrompt] and [onWaitForCard] carry the driver's answer to
 * the card-feed wait between this stack and the [AutopilotStrip]. While [blocked] the swap snaps. With [showAllDone] false
 * an empty stack draws nothing, so compose it only while [ParkedStackAnswers.key] shows a card, handing it the same [answers].
 */
@Composable internal fun ParkedTaskStack(state: TripLogState, now: Long, wide: Boolean, blocked: Boolean, act: Act, repository: TripLogRepository,
    openDriver: (String?) -> Unit, goSettings: () -> Unit, modifier: Modifier = Modifier, chargePrompt: ChargePrompt = ChargePrompt.AUTO,
    onWaitForCard: (() -> Unit)? = null, snoozedCare: Set<String> = emptySet(), showAllDone: Boolean = true,
    answers: ParkedStackAnswers = rememberParkedStackAnswers(), other: @Composable (ParkedTask) -> Unit = {}) {
    val data = state.data
    val live = state.live?.takeIf { it.demo == data.profile.demoMode }
    val tasks = parkedTasks(data, state.sync, now, zone, snoozedCare, chargePrompt, live?.odometerKm)
    val key = answers.key(tasks, data, state.lastFinishedId, now)
    val baseClassify = rememberClassifier(data, act, repository)
    val classify: (Trip, TripPurpose, EditSource) -> Unit = { trip, purpose, source -> answers.reclassifyId = null; baseClassify(trip, purpose, source) }
    val swap = cardSwap(snap = blocked)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (key.kind != null) TaskStepper(tasks)
        AnimatedContent(targetState = key, transitionSpec = { swap }, label = "parked-task") { shown ->
            // The data may have moved on by the time the exit frame renders; a card whose subject is gone draws nothing.
            when {
                shown.reclassifyId != null -> data.trips.firstOrNull { it.id == shown.reclassifyId }?.let { trip ->
                    PendingPurposeCard(trip, 1, data, act, repository, openDriver, classify, reclassifying = true) { answers.reclassifyId = null }
                }
                shown.finishedId != null -> data.trips.firstOrNull { it.id == shown.finishedId }?.let { trip ->
                    FinishedTripCard(trip, matchedChargeFor(trip, data), onConfirm = { answers.answer(trip.id) },
                        onChangePurpose = { answers.answer(trip.id, reclassify = true) }, note = autoFillMessage(trip, data.trips))
                }
                shown.kind == TaskKind.INCIDENT -> openIncidents(data).firstOrNull()?.let { IncidentCard(it, data, act, repository) }
                shown.kind == TaskKind.CARD_REVIEW -> {
                    val reviews = data.charges.filter { it.state == ChargeState.REVIEW && it.demo == data.profile.demoMode }.sortedBy { it.at }
                    reviews.firstOrNull()?.let { ChargeReviewCard(it, reviews.size, data, act, repository) }
                }
                shown.kind == TaskKind.CHARGE -> pendingChargeSession(data)?.let { ChargeCard(it, act, repository, data, onWaitForCard) }
                shown.kind == TaskKind.PURPOSE -> {
                    val pending = data.trips.filter { it.demo == data.profile.demoMode && !it.open && it.purpose == TripPurpose.UNCLASSIFIED }.sortedByDescending { it.startAt }
                    pending.firstOrNull()?.let { PendingPurposeCard(it, pending.size, data, act, repository, openDriver, classify) }
                }
                shown.kind == TaskKind.SETUP -> SetupCard(data, goSettings) { openDriver(null) }
                shown.kind == TaskKind.PAIRING -> PairingCard(state.sync, now, compact = !wide)
                shown.kind != null -> tasks.firstOrNull { it.kind == shown.kind }?.let { other(it) }
                showAllDone -> AllDoneCard(data, now, state.signal)
                else -> Spacer(Modifier.size(0.dp))
            }
        }
    }
}

/** Remembers how many steps this parking session started with, so "2/3" stays "2/3" as the list shrinks; empties with the list. */
@Composable internal fun rememberStepTotal(tasks: List<ParkedTask>): Int {
    var saved by rememberSaveable { mutableIntStateOf(0) }
    val total = if (tasks.isEmpty()) 0 else maxOf(saved, tasks.size)
    LaunchedEffect(total) { saved = total }
    return total
}

/**
 * A 36 dp line over the task card: "정차 후 정리 1/3 · 충전 비용 → 목적 확인 → 카드 확인" with one dot per step. The current
 * step is the scene's text, the rest its secondary text; a narrow column keeps only "1/3 · 충전 비용". Hidden when there
 * was never more than one thing to do, so a single question is just a question.
 */
@Composable internal fun TaskStepper(tasks: List<ParkedTask>, modifier: Modifier = Modifier) {
    val total = rememberStepTotal(tasks)
    if (tasks.isEmpty() || total <= 1) return
    val step = (total - tasks.size + 1).coerceIn(1, total)
    val text = Scene.text
    val secondary = Scene.textSecondary
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val narrow = maxWidth < 520.dp
        val description = stepperLabel(tasks, step, total, narrow = false) + " · " + tasks.joinToString(", ") { "${it.kind.label} ${it.count}건" }
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 6.dp).semantics(mergeDescendants = true) {
            contentDescription = description; liveRegion = LiveRegionMode.Polite
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.clearAndSetSemantics {}) {
                repeat(total) { index ->
                    val dot = Modifier.size(10.dp).clip(CircleShape)
                    Box(when {
                        index == step - 1 -> dot.background(text)
                        index < step - 1 -> dot.background(secondary)
                        else -> dot.border(1.5.dp, secondary, CircleShape)
                    })
                }
            }
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = secondary)) { append(if (narrow) "$step/$total · " else "정차 후 정리 $step/$total · ") }
                withStyle(SpanStyle(color = text)) { append(tasks.first().kind.label) }
                if (!narrow) withStyle(SpanStyle(color = secondary)) { tasks.drop(1).forEach { append(" → ${it.kind.label}") } }
            }, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clearAndSetSemantics {})
        }
    }
}

/** The quiet end of the stack: today's driver (the avatar used everywhere) and numbers, and a promise about the next trip only the signal can keep. */
@Composable internal fun AllDoneCard(data: TripLogData, now: Long, signal: SignalStatus, modifier: Modifier = Modifier) {
    val driver = data.drivers.firstOrNull { it.id == data.currentDriverId }
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DriverAvatar(driver, 60.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // A field label inside the card, not a section label.
                Eyebrow("지금 운전하는 직원", Scene.textSecondary)
                Text(driver?.label ?: "직원을 선택해 주세요", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = Scene.divider)
        val mine = data.trips.filter { it.driverId == data.currentDriverId && !it.open && localDate(it.startAt) == localDate(now) }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Stat("오늘 직원 운행", "${mine.size}건", Modifier.weight(1f))
            Stat("오늘 등록 경비", formatWon(mine.sumOf { it.expenseWon }), Modifier.weight(1f))
        }
        Text(allDoneText(signal), style = MaterialTheme.typography.bodySmall, color = Scene.textSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

// ───────────────────────── 방금 운행 ─────────────────────────

/**
 * The peak-end moment: the trip is already in the logbook with the purpose the driver gave this place before. One tap
 * acknowledges it; "다른 목적" reopens the question. [charge] is the card payment already attached; [note] says how many
 * earlier answers the label rests on. Nothing here carries a "확인 필요" badge, because nothing needs confirming.
 */
@Composable internal fun FinishedTripCard(trip: Trip, charge: CardCharge?, onConfirm: () -> Unit, onChangePurpose: () -> Unit, note: String? = null) {
    Panel {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow("방금 운행 · ${hm(trip.startAt)} – ${hm(trip.endAt ?: trip.updatedAt)}", Scene.positive)
            Text("${withRo(trip.purpose.label)} 자동 분류했어요", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(kmText(trip.distanceKm), style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"))
            Muted("${durationText(trip.durationMillis)} · ${trip.destination ?: trip.endAddress ?: "도착지 미기록"}", Modifier.padding(bottom = 6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PurposeChip(trip.purpose)
            note?.let { Muted(it, Modifier.weight(1f)) }
        }
        charge?.let { Muted("${it.category?.label ?: "카드 결제"} ${formatWon(it.amountWon)} 반영 · ${it.merchant}") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryAction("확인", Modifier.weight(1f), onClick = onConfirm)
            QuietAction("다른 목적", Modifier.weight(1f), onClick = onChangePurpose)
        }
    }
}

/**
 * The purpose question with the driver's own earlier answer as the one-tap default: "일반 업무로 기록 · 지난 3회와 같아요",
 * the real count behind it, and the other purposes one tap away (none hidden once expanded). A truthful line says when
 * this destination will start labelling itself. [reclassifying] adapts the copy for a trip that already has a purpose.
 */
@Composable internal fun PendingPurposeCard(trip: Trip, count: Int, data: TripLogData, act: Act, repository: TripLogRepository, openDriver: (String?) -> Unit,
    classify: (Trip, TripPurpose, EditSource) -> Unit = rememberClassifier(data, act, repository), reclassifying: Boolean = false, onClose: (() -> Unit)? = null) {
    val suggestion = remember(trip.id, data.trips.size) { suggestPurpose(trip, data.trips)?.takeIf { !reclassifying || it.purpose != trip.purpose } }
    val sameCount = suggestion?.let { sameDestinationCount(trip, data.trips, it.purpose) } ?: 0
    val driver = data.drivers.firstOrNull { it.id == trip.driverId }
    val place = trip.destination ?: trip.endAddress
    var more by rememberSaveable(trip.id) { mutableStateOf(suggestion == null) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow(listOfNotNull(when {
                reclassifying -> "목적 바꾸기 · ${trip.purpose.label}에서"
                count > 1 -> "분류가 필요한 운행 ${count}건 · 가장 최근"
                else -> "방금 끝난 운행"
            }, place).joinToString(" · "), if (reclassifying) Scene.textSecondary else Scene.negative)
            if (onClose != null) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) { Text("그대로 두기") }
            }
        }
        Text(if (reclassifying) "어떤 목적이었나요?" else "운행 목적만 확인해 주세요", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(kmText(trip.distanceKm), style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"))
            Muted("${hm(trip.startAt)} – ${hm(trip.endAt ?: trip.updatedAt)}", Modifier.padding(bottom = 6.dp))
        }
        Muted(routeText(trip))
        if (suggestion != null) {
            PrimaryAction(suggestedPurposeLabel(suggestion.purpose, sameCount), Modifier.fillMaxWidth()) { classify(trip, suggestion.purpose, EditSource.SUGGESTED) }
            if (!more) QuietAction("다른 목적", Modifier.fillMaxWidth(), description = "다른 목적 선택지 펼치기") { more = true }
        }
        if (more) PurposeOptions(if (reclassifying) trip.purpose else null) { purpose -> if (!reclassifying || purpose != trip.purpose) classify(trip, purpose, EditSource.DRIVER) }
        autoPurposeProgress(trip, data.trips, data.profile.autopilot)?.let { Muted(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Muted("사용자  ${driver?.label ?: "미지정"}", Modifier.weight(1f))
            TextButton(onClick = { openDriver(trip.id) }, modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = "이 운행의 사용자 변경" }) {
                Text("변경", modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

// ───────────────────────── 카드 자동 반영 묶음 ─────────────────────────

/**
 * Several card payments landed on trips at once (a statement import, a phone catching up): one banner with the count and
 * the sum, a way to the list where each line can be reversed, and a close that is not remembered, so the next batch is
 * announced again. Shown under the status hero; [charges] come from [newlyMatched].
 */
@Composable internal fun MatchedBanner(charges: List<CardCharge>, onShow: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (charges.isEmpty()) return
    val message = "카드 결제 ${charges.size}건 ${formatWon(charges.sumOf { it.amountWon })}을 운행에 자동 반영했어요"
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Scene.glass, contentColor = Scene.text,
        border = BorderStroke(1.dp, Scene.positive.copy(alpha = .45f))) {
        BoxWithConstraints(Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            val text: @Composable (Modifier) -> Unit = { place ->
                Text(message, style = MaterialTheme.typography.bodyLarge, modifier = place.semantics { liveRegion = LiveRegionMode.Polite })
            }
            val close: @Composable () -> Unit = {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = "자동 반영 알림 닫기" }) {
                    Text("×", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clearAndSetSemantics {})
                }
            }
            if (maxWidth < 560.dp) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { text(Modifier.weight(1f).padding(top = 8.dp)); close() }
                Row { QuietAction("내역 보기", Modifier.fillMaxWidth(), onClick = onShow) }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                text(Modifier.weight(1f))
                QuietAction("내역 보기", onClick = onShow)
                close()
            }
        }
    }
}

/**
 * The "자동 반영" filter of the log: this month's trips that carry a card-matched expense, each line with its own
 * "회사 비용 아님" and an undo that attaches the payment again. Nothing was hidden in a basket, so nothing sneaks in.
 */
@Composable internal fun MatchedTripList(data: TripLogData, now: Long, act: Act, repository: TripLogRepository, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    val rows = matchedTripsThisMonth(data, zone, localDate(now))
    if (rows.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Muted("이달에 카드 결제가 자동으로 들어간 운행이 없어요.") }
        return
    }
    val followUp = LocalFollowUp.current
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(rows, key = { it.first.id }) { (trip, expenses) ->
            Column(Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface)) {
                TripRow(trip, data, flagged = false, selected = false) { onOpen(trip.id) }
                expenses.forEach { expense ->
                    val chargeId = expense.cardChargeId ?: return@forEach
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text("${expense.type.label} ${formatWon(expense.amountWon)}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 1)
                        }
                        Muted(expense.merchant ?: EditSource.CARD.label, Modifier.weight(1f))
                        QuietAction("회사 비용 아님", description = "${expense.merchant ?: expense.type.label} ${formatWon(expense.amountWon)} 회사 비용 아님") {
                            act {
                                repository.excludeCardCharge(chargeId)
                                followUp(FollowUp("${expense.merchant ?: expense.type.label} ${formatWon(expense.amountWon)}을 회사 비용에서 뺐어요", "실행 취소") {
                                    repository.confirmCardCharge(chargeId, trip.id)
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 오늘의 운전자 ─────────────────────────

/** The driver row folds away in 200 ms once it is answered (spec C); nothing is composed once it has gone. */
private const val MOTION_FOLD_MS = 200

/**
 * One question a day under the header, in a window without the 지금 column (where [DriverNowCard] asks it): the avatar and
 * "오늘도 김서연 님이 운전하나요?" ([driverQuestion]: the person, not the department) with the current driver as the default.
 * "네" writes nothing and remembers today; "다른 직원" opens the picker. Both count as an answer, so the row does not
 * come back for the newly chosen driver. See [driverConfirmDue] for when the question is worth asking at all.
 * [confirmed] holds the day keys already answered; the shell owns it so the home hero and the other tabs ask once a day between them.
 * The row folds itself in and out; [modifier] applies inside the fold, so a gap the caller pads under the row folds with it.
 */
@Composable internal fun DriverConfirmRow(state: TripLogState, now: Long, openDriver: (String?) -> Unit, confirmed: String, onConfirmed: (String) -> Unit,
    modifier: Modifier = Modifier) {
    val dates = confirmed.toIdSet()
    val due = driverConfirmDue(state.data, now, zone, dates)
    // The answered row shrinks and fades showing the question it just asked, then leaves the composition altogether.
    var shown by remember { mutableStateOf(due) }
    if (due != null) shown = due
    val visible = remember { MutableTransitionState(due != null) }
    visible.targetState = due != null
    val driver = shown
    if (driver == null || (!visible.currentState && !visible.targetState)) return
    val reduce = LocalReduceMotion.current
    val ease = FastOutSlowInEasing
    AnimatedVisibility(visibleState = visible,
        enter = if (reduce) fadeIn(snap()) else fadeIn(tween(MOTION_FOLD_MS, easing = ease)) + expandVertically(tween(MOTION_FOLD_MS, easing = ease)),
        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(MOTION_FOLD_MS, easing = ease)) + shrinkVertically(tween(MOTION_FOLD_MS, easing = ease)),
        label = "driver-confirm") {
        val answer = { onConfirmed((dates + dateKey(now, zone)).joined()) }
        Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Scene.glass, contentColor = Scene.text, border = Scene.edge) {
            BoxWithConstraints(Modifier.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)) {
                val question: @Composable (Modifier) -> Unit = { place ->
                    Row(place, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        DriverAvatar(driver, 40.dp)
                        Text(driverQuestion(driver), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).semantics { heading() })
                    }
                }
                val buttons: @Composable () -> Unit = {
                    PrimaryAction("네", Modifier.heightIn(min = 56.dp), onClick = answer)
                    QuietAction("다른 직원", Modifier.heightIn(min = 56.dp)) { answer(); openDriver(null) }
                }
                if (maxWidth < 560.dp) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    question(Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { buttons() }
                } else Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    question(Modifier.weight(1f))
                    buttons()
                }
            }
        }
    }
}
