package ai.sharemate.app

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/*
 * What the parked screen asks a person to do, in the order the moment demands: safety, then money the car already
 * measured, then paperwork. One list feeds 홈's attention queue ([attentionQueue]) and its status line, the home badge and
 * the last line of the driving screen, so every surface counts the same things. Everything here is pure.
 */

enum class TaskKind(val label: String) {
    INCIDENT("특이사항 대응"), CARD_REVIEW("카드 확인"), CHARGE("충전 비용"), PURPOSE("목적 확인"),
    SETUP("시작 설정"), PAIRING("휴대폰 연결"), MONTH_CLOSE("월 마감"), CARE("차량 관리"),
}

data class ParkedTask(val kind: TaskKind, val count: Int)

/** How the driver wants the charge-amount question handled while a card approval may still be on its way. */
enum class ChargePrompt { AUTO, NOW, WAIT }

/** Task kinds the car may bring up once it is parked; the driving screen mentions only these. */
internal val DRIVING_TASK_KINDS = setOf(TaskKind.INCIDENT, TaskKind.CARD_REVIEW, TaskKind.CHARGE, TaskKind.PURPOSE)

/** With a card feed the amount is on its way by itself, so the car waits this long before asking a person to type it. */
internal const val CHARGE_FEED_WAIT_MS = 20 * 60_000L
/** The finished-trip card is a moment, not a chore: it is offered only this long after the car parked. */
internal const val FINISHED_CARD_MS = 2 * 60_000L
/** A driver who has not driven for this long is asked once whether it is still them. */
internal const val DRIVER_CONFIRM_GAP_MS = 8 * 3_600_000L

private fun today(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
private fun Trip.placeKey() = destination?.takeIf { it.isNotBlank() } ?: endAddress?.takeIf { it.isNotBlank() }

/** True while a card approval could still settle the stop by itself. */
private fun cardFeedActive(data: TripLogData) = data.charges.isNotEmpty() || data.profile.cards.isNotEmpty()

/** The detected stop that still needs an amount, if any, for the current data set. */
internal fun pendingChargeSession(data: TripLogData): ChargeSession? = data.pendingCharge?.takeIf { it.demo == data.profile.demoMode }

/** Whether the charge card is on screen now: a feed-less car asks at once; with a feed it waits 20 minutes unless the driver chose otherwise. */
internal fun pendingChargeVisible(data: TripLogData, now: Long, prompt: ChargePrompt = ChargePrompt.AUTO): Boolean {
    val session = pendingChargeSession(data) ?: return false
    return when (prompt) {
        ChargePrompt.NOW -> true
        ChargePrompt.WAIT -> !cardFeedActive(data)
        ChargePrompt.AUTO -> !cardFeedActive(data) || now - session.endAt > CHARGE_FEED_WAIT_MS
    }
}

/** The stop whose question is being held back for the card feed; 홈's waiting card tells the driver so instead of a silent gap. */
internal fun hiddenPendingCharge(data: TripLogData, now: Long, prompt: ChargePrompt = ChargePrompt.AUTO): ChargeSession? =
    pendingChargeSession(data)?.takeUnless { pendingChargeVisible(data, now, prompt) }

/** Care alerts that deserve a home card: near or past a deadline and not snoozed for this threshold (keys from [careSnoozeKey] in FleetCareUi). */
internal fun dueCareAlerts(data: TripLogData, odometerKm: Double?, today: LocalDate, snoozedCare: Set<String>): List<CareAlert> =
    careDueAlerts(data.care, odometerKm ?: data.vehicle.odometerKm, today, snoozedCare)

/**
 * Everything the parked screen will ask for, most urgent first. Demo and real records never mix: only the current
 * data set counts. [odometerKm] is the live reading when the car has one; the saved card value is the fallback.
 */
internal fun parkedTasks(data: TripLogData, sync: SyncInfo, now: Long, zone: ZoneId, snoozedCare: Set<String> = emptySet(),
    chargePrompt: ChargePrompt = ChargePrompt.AUTO, odometerKm: Double? = null): List<ParkedTask> {
    val demo = data.profile.demoMode
    val day = today(now, zone)
    val out = mutableListOf<ParkedTask>()
    fun add(kind: TaskKind, count: Int) { if (count > 0) out += ParkedTask(kind, count) }
    add(TaskKind.INCIDENT, data.incidents.count { it.demo == demo && it.status == IncidentStatus.OPEN })
    add(TaskKind.CARD_REVIEW, data.charges.count { it.demo == demo && it.state == ChargeState.REVIEW })
    add(TaskKind.CHARGE, if (pendingChargeVisible(data, now, chargePrompt)) 1 else 0)
    add(TaskKind.PURPOSE, data.trips.count { it.demo == demo && !it.open && it.purpose == TripPurpose.UNCLASSIFIED })
    val setupNeeded = data.drivers.isEmpty() || data.profile.plateNumber.isBlank() || data.profile.companyName.isBlank()
    add(TaskKind.SETUP, if (setupNeeded) 1 else 0)
    add(TaskKind.PAIRING, if (data.profile.phoneSync && sync.running && sync.pairedPhones == 0) 1 else 0)
    val previous = monthStatus(data, YearMonth.from(day).minusMonths(1), zone)
    add(TaskKind.MONTH_CLOSE, if (previous.trips > 0 && previous.closed == null) 1 else 0)
    add(TaskKind.CARE, dueCareAlerts(data, odometerKm, day, snoozedCare).size)
    return out
}

/** The subset the car may raise on its own once parked; what the driving screen and the home badge count. */
internal fun drivingTasks(tasks: List<ParkedTask>): List<ParkedTask> = tasks.filter { it.kind in DRIVING_TASK_KINDS }

/** Home badge: how many things the car itself detected and is waiting on. */
internal fun parkedTaskBadge(tasks: List<ParkedTask>): Int = drivingTasks(tasks).sumOf { it.count }

/** The driving screen's last line: plain text, the same names and numbers as 홈's queue the driver will see after parking. */
internal fun drivingTaskLine(tasks: List<ParkedTask>): String {
    val later = drivingTasks(tasks)
    return if (later.isEmpty()) "정차 후 할 일 없음" else "정차 후 · " + later.joinToString(" · ") { "${it.kind.label} ${it.count}건" }
}

/** All-done text that only promises automatic recording when the vehicle signal really is there. */
internal fun allDoneText(signal: SignalStatus): String =
    if (signal in setOf(SignalStatus.READY, SignalStatus.RECORDING, SignalStatus.DEMO)) "오늘 정리 끝 · 다음 운행도 자동 기록"
    else "오늘 정리 끝 · 차량 신호를 받으면 자동 기록해요"

// ───────────────────────── 방금 운행 ─────────────────────────

/** Prior trips to the same place that a person labelled; automatic guesses are never evidence for the next guess. */
private fun humanAnswers(trip: Trip, trips: List<Trip>): List<Trip> {
    val key = trip.placeKey() ?: return emptyList()
    return trips.filter { it.id != trip.id && !it.open && it.demo == trip.demo && it.placeKey() == key &&
        it.purpose != TripPurpose.UNCLASSIFIED && it.purposeSource != EditSource.AUTO }.sortedByDescending { it.startAt }
}

/** How many times a person labelled this destination ([purpose] limits it to one answer, for "지난 3회와 같아요"). */
internal fun sameDestinationCount(trip: Trip, trips: List<Trip>, purpose: TripPurpose? = null): Int =
    humanAnswers(trip, trips).count { purpose == null || it.purpose == purpose }

/** Label for the one-tap default: the suggested purpose and how often it was the answer before. */
internal fun suggestedPurposeLabel(purpose: TripPurpose, count: Int): String =
    "${withRo(purpose.label)} 기록 · " + if (count <= 1) "지난번과 같아요" else "지난 ${count}회와 같아요"

/**
 * One truthful line about when this destination will label itself, or null when the answers disagree, nobody has
 * answered yet, or automatic completion is off. Mirrors [autoPurpose]: the five most recent human answers must agree.
 */
internal fun autoPurposeProgress(trip: Trip, trips: List<Trip>, autopilot: Boolean = true): String? {
    if (!autopilot) return null
    val recent = humanAnswers(trip, trips).take(5)
    if (recent.isEmpty()) return null
    val purpose = recent.map { it.purpose }.distinct().singleOrNull() ?: return null
    val n = recent.size
    return if (n >= AUTO_PURPOSE_MIN) "같은 도착지 ${n}회 ${purpose.label} 기록 · 다음 운행부터 자동으로 채워요"
    else "같은 도착지 ${n}회 기록 · ${AUTO_PURPOSE_MIN - n}번 더 같으면 다음부터 자동으로 채워요"
}

/** Follow-up text for an automatic purpose: the place and the real count behind it. */
internal fun autoFillMessage(trip: Trip, trips: List<Trip>): String {
    val count = sameDestinationCount(trip, trips, trip.purpose)
    val place = trip.placeKey() ?: "같은 도착지"
    return "$place · ${count}회 같았던 목적으로 자동 분류"
}

/**
 * The trip whose finish the car should mention: labelled by itself, parked less than two minutes ago, automatic
 * completion on, and not yet acknowledged. Null means the stack starts with real work instead.
 */
internal fun finishedTripToShow(data: TripLogData, lastFinishedId: String?, now: Long, dismissed: Set<String>): Trip? {
    if (!data.profile.autopilot || lastFinishedId == null || lastFinishedId in dismissed) return null
    val trip = data.trips.firstOrNull { it.id == lastFinishedId } ?: return null
    val end = trip.endAt ?: return null
    if (trip.demo != data.profile.demoMode || trip.purposeSource != EditSource.AUTO || trip.purpose == TripPurpose.UNCLASSIFIED) return null
    return trip.takeIf { now - end in 0 until FINISHED_CARD_MS }
}

/** The card payment the car already attached to this trip, newest first. */
internal fun matchedChargeFor(trip: Trip, data: TripLogData): CardCharge? =
    data.charges.filter { it.state == ChargeState.MATCHED && it.tripId == trip.id }.maxByOrNull { it.at }

// ───────────────────────── 충전 금액 앵커 ─────────────────────────

data class ChargeAnchor(val label: String, val amountWon: Long)

private fun monthDay(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).let { "${it.monthValue}/${it.dayOfMonth}" }

/** The trip that arrived at a stop: the last one that started before the stop ended. */
private fun tripForStop(stop: ChargeSession, trips: List<Trip>): Trip? = trips.filter { it.demo == stop.demo && it.startAt <= stop.endAt }.maxByOrNull { it.startAt }

/**
 * Up to two amounts this car paid before, as chips above the round numbers: the same place with a card-matched
 * payment, then a stop with a similar level change whose fuel line a person entered. Nothing is estimated.
 */
internal fun recentChargeAmounts(session: ChargeSession, data: TripLogData, zone: ZoneId = ZoneId.systemDefault()): List<ChargeAnchor> {
    val stops = data.stops.filter { it.demo == session.demo && it.id != session.id && it.fuel == session.fuel && it.endAt < session.startAt }.sortedByDescending { it.endAt }
    val out = mutableListOf<ChargeAnchor>()
    fun add(anchor: ChargeAnchor) { if (out.size < 2 && out.none { it.amountWon == anchor.amountWon }) out += anchor }
    val place = session.place?.takeIf { it.isNotBlank() }
    if (place != null) stops.filter { it.place == place }.forEach { stop ->
        val charge = data.charges.firstOrNull { (it.id == stop.chargeId || it.stopId == stop.id) && it.state == ChargeState.MATCHED } ?: return@forEach
        add(ChargeAnchor("${monthDay(stop.endAt, zone)} ${formatWon(charge.amountWon)} · 이곳", charge.amountWon))
    }
    val delta = if (session.socFrom != null && session.socTo != null) session.socTo - session.socFrom else null
    if (delta != null) stops.filter { it.chargeId == null && it.socFrom != null && it.socTo != null && kotlin.math.abs((it.socTo - it.socFrom) - delta) <= 10 }.forEach { stop ->
        val trip = tripForStop(stop, data.trips) ?: return@forEach
        val typed = trip.expenses.filter { it.type == ExpenseType.FUEL && it.cardChargeId == null && it.at >= stop.startAt }.maxByOrNull { it.at } ?: return@forEach
        add(ChargeAnchor("${monthDay(stop.endAt, zone)} ${formatWon(typed.amountWon)} · 비슷한 ${if (session.fuel) "주유" else "충전"}", typed.amountWon))
    }
    return out
}

// ───────────────────────── 카드 자동 반영 ─────────────────────────

/** Trips of the current month that carry a card-matched expense, newest first, with just those expenses. */
internal fun matchedTripsThisMonth(data: TripLogData, zone: ZoneId, today: LocalDate = LocalDate.now(zone)): List<Pair<Trip, List<TripExpense>>> {
    val demo = data.profile.demoMode
    val matched = data.charges.filter { it.demo == demo && it.state == ChargeState.MATCHED }.map { it.id }.toSet()
    if (matched.isEmpty()) return emptyList()
    return tripsIn(data.trips.filter { it.demo == demo }, today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()), zone)
        .map { trip -> trip to trip.expenses.filter { it.cardChargeId in matched } }
        .filter { it.second.isNotEmpty() }
        .sortedByDescending { it.first.startAt }
}

/** Charges that became MATCHED since [previousIds] was taken, oldest first; several at once get a banner instead of one bar. */
internal fun newlyMatched(previousIds: Set<String>, data: TripLogData): List<CardCharge> =
    data.charges.filter { it.demo == data.profile.demoMode && it.state == ChargeState.MATCHED && it.id !in previousIds }.sortedBy { it.at }

internal fun matchedIds(data: TripLogData): Set<String> = data.charges.filter { it.state == ChargeState.MATCHED }.map { it.id }.toSet()

// ───────────────────────── 오늘의 운전자 ─────────────────────────

/**
 * The driver to confirm today, or null when the question is pointless: a single driver, nobody selected, the switch
 * off, the car moving, that driver drove within eight hours, or today already answered.
 */
internal fun driverConfirmDue(data: TripLogData, now: Long, zone: ZoneId, confirmedDates: Set<String>): Driver? {
    if (!data.profile.confirmDriverDaily || data.drivers.size < 2 || data.open != null) return null
    val driver = data.drivers.firstOrNull { it.id == data.currentDriverId } ?: return null
    if (today(now, zone).toString() in confirmedDates) return null
    val lastDrive = data.trips.filter { it.driverId == driver.id && it.demo == data.profile.demoMode && !it.open }.maxOfOrNull { it.endAt ?: it.updatedAt }
    return driver.takeIf { lastDrive == null || now - lastDrive >= DRIVER_CONFIRM_GAP_MS }
}

internal fun dateKey(now: Long, zone: ZoneId): String = today(now, zone).toString()

// ───────────────────────── 카드 승인 대기 ─────────────────────────

/**
 * The 홈 card's line while a charge waits for its card approval (2.13, pure, unit-tested): when the stop ended and what
 * happens next, "충전 종료 14:35 · 20분 안에 안 오면 금액을 물어볼게요"; the headline already says the car is waiting, and
 * TalkBack hears the whole of [chargeWaitingLine]. No countdown.
 */
internal fun chargeWaitingShort(session: ChargeSession, prompt: ChargePrompt = ChargePrompt.AUTO, zone: ZoneId = ZoneId.systemDefault()): String {
    val end = Instant.ofEpochMilli(session.endAt).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
    val word = if (session.fuel) "주유" else "충전"
    val tail = if (prompt == ChargePrompt.WAIT) "승인이 오면 자동으로 들어가요" else "20분 안에 안 오면 금액을 물어볼게요"
    return "$word 종료 $end · $tail"
}

/** The dimmed waiting row: when the stop ended, that the car is waiting for the card, and what happens next. No countdown. */
internal fun chargeWaitingLine(session: ChargeSession, prompt: ChargePrompt = ChargePrompt.AUTO, zone: ZoneId = ZoneId.systemDefault()): String {
    val end = Instant.ofEpochMilli(session.endAt).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
    val word = if (session.fuel) "주유" else "충전"
    val tail = if (prompt == ChargePrompt.WAIT) "승인이 오면 자동으로 들어가요" else "20분 안에 안 오면 금액을 물어볼게요"
    return "$word 종료 $end · 카드 승인 기다리는 중 · $tail"
}
