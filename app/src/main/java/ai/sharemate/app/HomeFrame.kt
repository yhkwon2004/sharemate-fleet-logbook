package ai.sharemate.app

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/*
 * 홈 without scrolling (2.13, docs/design/DESIGN_2_13_SPEC.md §3), the pure half: what the attention slot holds and in which
 * order ([attentionQueue]), which item the driver is looking at ([attentionIndex]), how tall each card may get
 * ([attentionCardMax]) and how the frame adds up in the full window ([homeFrame]). The composables in HomeAttention.kt and
 * LogbookUi.kt's DrivePane lay themselves out with the constants here, and HomeFrameTest checks every card kind, with and
 * without the pager and the demo strip, against the 1209-dp window: that is why 홈 never needs to scroll there.
 *
 * Heights are one text line as Asta Sans draws it (round(.952 s) + round(.241 s) dp: bodySmall 19, titleSmall and labelLarge
 * 23, bodyLarge 24, headlineSmall 31, displaySmall 53), which reproduces the shots to the pixel.
 */

// ───────────────────────── the frame (FULL, 1×, with the 지금 column) ─────────────────────────

/** The full Pleos window's height, the header's one row and the page slot's padding (8 above, 20 below). */
internal val HOME_WINDOW_FULL = 1209.dp
internal val HOME_HEADER_ROW = 84.dp
internal val HOME_PAGE_PADDING = 28.dp

/** The page the frame is budgeted for: the full window less the header row and the page slot's padding (1,097 dp). */
internal val HOME_PAGE_FULL = HOME_WINDOW_FULL - HOME_HEADER_ROW - HOME_PAGE_PADDING

/** The greeting block's minimum beside the 지금 column: the sun rests right of it and needs the band down to 152.7 dp. */
internal val HOME_GREETING_MIN = 160.dp

/** The space between the greeting block and the attention card; it goes with the card. */
internal val HOME_CARD_GAP = 24.dp

/** The sky's fixed part; the rest of the page's height is the sky's too (a weighted spacer). */
internal val HOME_SKY_MIN = 24.dp

/** The demo strip (12 + 56 + 12) and the space under it, while demo mode is on. */
internal val HOME_DEMO_STRIP = 80.dp

/** The gap inside the bottom band: under the demo strip and between the destination row and the incident dock. */
internal val HOME_BAND_GAP = 16.dp

/** The destination row: 20 + a 32-dp title + 12 + one 19-dp line + 20 is 103, held at 104 so the budget is exact. */
internal val HOME_DESTINATION_ROW = 104.dp

/** The incident dock: 12 + the 56-dp buttons + 12. */
internal val HOME_DOCK = 80.dp

/** The tallest an attention card is designed to get; every kind stays under it (HomeFrameTest). */
internal val ATTENTION_CARD_CAP = 480.dp

// ───────────────────────── the attention card's anatomy ─────────────────────────

/** The card's padding on every side, as `CarCard(padding = 24.dp)`. */
internal val ATTENTION_PADDING = 24.dp

/** The title row: 32 dp, or 56 with the pager's 56-dp arrows. */
internal val ATTENTION_TITLE = 32.dp
internal val ATTENTION_TITLE_PAGER = 56.dp

/** Between the title row and the headline. */
internal val ATTENTION_TITLE_GAP = 12.dp

/** The headline (headlineSmall, one line), or 53 with a figure at its end (displaySmall). */
internal val ATTENTION_HEADLINE = 31.dp
internal val ATTENTION_FIGURE = 53.dp

/** A supporting line: 4 dp above a one-line [CarLine] (bodySmall, 19 dp). */
internal val ATTENTION_LINE_GAP = 4.dp
internal val CAR_LINE_HEIGHT = 19.dp
internal val ATTENTION_LINE = ATTENTION_LINE_GAP + CAR_LINE_HEIGHT

/** Before the body block and before the action row. */
internal val ATTENTION_BLOCK_GAP = 16.dp

/** The action row: 56 dp, 68 when its start holds the money field, plus 24 while that field shows its error. */
internal val ATTENTION_ROW = 56.dp
internal val ATTENTION_FIELD_ROW = 68.dp
internal val ATTENTION_FIELD_ERROR = 24.dp

/** The incident card's steps: 28-dp discs in 32-dp rows, so four stacked discs never touch; the photo strip at 56. */
internal val INCIDENT_STEP_ROW = 32.dp
internal val INCIDENT_STEP_DISC = 28.dp
internal const val INCIDENT_STEPS_MAX = 4
internal val INCIDENT_PHOTO = 56.dp
internal val INCIDENT_GAP = 8.dp

/** The charge card's amount chips: two rows of 56-dp targets at most, 8 dp apart. */
internal const val CHARGE_CHIP_ROWS = 2
internal val CHARGE_CHIP_ROW = 56.dp
internal val CHARGE_CHIP_GAP = 8.dp

/** The purpose tiles ([PurposeOptions]), the setup steps across, the pairing card's two rows, the mini stats. */
internal val PURPOSE_TILES = 72.dp
internal val SETUP_STEPS = 32.dp
internal val PAIRING_ROWS = 66.dp
internal val MINI_STATS = 46.dp

/** The month-close card's completion bar and the gap over it. */
internal val CLOSE_BAR = 10.dp
internal val CLOSE_BAR_GAP = 12.dp

// ───────────────────────── the queue ─────────────────────────

/**
 * What the attention slot can hold, most urgent first, with the word the status line and the pager use. The eight kinds the
 * parked task list shares ([TaskKind]) keep its words, so the driving screen's last line ([drivingTaskLine]) still names
 * what the parked 홈 shows.
 */
internal enum class AttentionKind(val label: String) {
    INCIDENT(TaskKind.INCIDENT.label), PERMISSION("차량 권한"), RECLASSIFY("목적 바꾸기"), CARD_REVIEW(TaskKind.CARD_REVIEW.label),
    CHARGE(TaskKind.CHARGE.label), FINISHED("방금 운행"), MATCHED("카드 반영"), PURPOSE(TaskKind.PURPOSE.label), SETUP(TaskKind.SETUP.label),
    PAIRING(TaskKind.PAIRING.label), MONTH_CLOSE(TaskKind.MONTH_CLOSE.label), CARE(TaskKind.CARE.label), CLOSE_REMINDER("월 마감"),
    CHARGE_WAITING("충전 비용"),
}

/** One thing in the attention slot: its [kind], how many of it wait ([count]) and, for a trip's card, the trip ([id]). */
@Immutable internal data class AttentionItem(val kind: AttentionKind, val count: Int = 1, val id: String? = null)

/** The item the driver paged to ([item]) and the queue's head when they did ([head]); see [attentionIndex]. */
@Immutable internal data class AttentionPin(val item: AttentionItem, val head: AttentionItem)

/**
 * Everything 홈's attention slot will show, most urgent first (pure, unit-tested): the parked task list's order (incident,
 * card question, charge, purpose, setup, pairing, month close, care) with what the home adds folded in. The permission prompt
 * while the vehicle signal is [SignalStatus.DENIED]; the purpose question reopened from the 방금 운행 card ([reclassifyId]),
 * behind an open incident; the 방금 운행 card ([finishedId]), behind card and charge questions instead of dropped; a batch of
 * card payments the car settled at once ([matched]); this month's close reminder ([closeReminder]) only when no month-close
 * task stands; and a charge whose card approval is awaited ([chargeWaiting]) last. [finishedId] and [reclassifyId] come from
 * the home's answers ([ParkedStackAnswers]), already checked against the data.
 */
internal fun attentionQueue(tasks: List<ParkedTask>, signal: SignalStatus, finishedId: String?, reclassifyId: String?, matched: Int,
    closeReminder: Boolean, chargeWaiting: Boolean): List<AttentionItem> {
    fun task(kind: TaskKind, target: AttentionKind) = tasks.firstOrNull { it.kind == kind && it.count > 0 }?.let { AttentionItem(target, it.count) }
    return listOfNotNull(
        task(TaskKind.INCIDENT, AttentionKind.INCIDENT),
        AttentionItem(AttentionKind.PERMISSION).takeIf { signal == SignalStatus.DENIED },
        reclassifyId?.let { AttentionItem(AttentionKind.RECLASSIFY, id = it) },
        task(TaskKind.CARD_REVIEW, AttentionKind.CARD_REVIEW),
        task(TaskKind.CHARGE, AttentionKind.CHARGE),
        finishedId?.let { AttentionItem(AttentionKind.FINISHED, id = it) },
        AttentionItem(AttentionKind.MATCHED, matched).takeIf { matched > 0 },
        task(TaskKind.PURPOSE, AttentionKind.PURPOSE),
        task(TaskKind.SETUP, AttentionKind.SETUP),
        task(TaskKind.PAIRING, AttentionKind.PAIRING),
        task(TaskKind.MONTH_CLOSE, AttentionKind.MONTH_CLOSE),
        task(TaskKind.CARE, AttentionKind.CARE),
        AttentionItem(AttentionKind.CLOSE_REMINDER).takeIf { closeReminder && tasks.none { it.kind == TaskKind.MONTH_CLOSE && it.count > 0 } },
        AttentionItem(AttentionKind.CHARGE_WAITING).takeIf { chargeWaiting },
    )
}

/**
 * Which item of [queue] the slot shows (pure, unit-tested). The queue's head unless the driver paged ([pin]) while the same
 * head stood: then the item of the pinned kind and trip, wherever it moved (a count that changed keeps it). A 방금 운행 pin
 * follows the 목적 바꾸기 question of the same trip that its 다른 목적 opens, so the driver lands on the question just asked
 * for; a 충전 비용 card that was waiting for the card approval follows the amount question that 지금 입력 (or the end of the
 * wait) puts in its place, even when something more urgent stands at the head. A new head (something more urgent arrived,
 * or the head was answered) or a pinned item that has gone shows the head.
 */
internal fun attentionIndex(queue: List<AttentionItem>, pin: AttentionPin?): Int {
    if (pin == null || queue.isEmpty() || queue.first() != pin.head) return 0
    val at = queue.indexOfFirst { it.kind == pin.item.kind && it.id == pin.item.id }
    if (at >= 0) return at
    if (pin.item.kind == AttentionKind.FINISHED) {
        val next = queue.indexOfFirst { it.kind == AttentionKind.RECLASSIFY && it.id == pin.item.id }
        if (next >= 0) return next
    }
    if (pin.item.kind == AttentionKind.CHARGE_WAITING) {
        val next = queue.indexOfFirst { it.kind == AttentionKind.CHARGE }
        if (next >= 0) return next
    }
    return 0
}

/**
 * How a card kind is built, for its height: a figure at the headline's end, the supporting lines, a block under the headline
 * ([under], the month-close bar), the body block, and the action row.
 */
@Immutable internal data class AttentionAnatomy(val figure: Boolean, val lines: Int, val under: Dp = 0.dp, val body: Dp? = null, val row: Dp = ATTENTION_ROW)

/** Each kind's anatomy at its tallest (§3.2's table); the composables draw the same blocks with the same constants. */
internal fun attentionAnatomy(kind: AttentionKind): AttentionAnatomy = when (kind) {
    // 지금 할 일 / the photo hint; four steps, the note and the photo strip, 8 dp apart; the facts line beside two buttons.
    AttentionKind.INCIDENT -> AttentionAnatomy(figure = false, lines = 1,
        body = INCIDENT_STEP_ROW * INCIDENT_STEPS_MAX + INCIDENT_GAP + CAR_LINE_HEIGHT + INCIDENT_GAP + INCIDENT_PHOTO)
    AttentionKind.PERMISSION, AttentionKind.CARE -> AttentionAnatomy(figure = false, lines = 1)
    AttentionKind.RECLASSIFY, AttentionKind.PURPOSE -> AttentionAnatomy(figure = true, lines = 2, body = PURPOSE_TILES)
    AttentionKind.CARD_REVIEW -> AttentionAnatomy(figure = true, lines = 2)
    AttentionKind.CHARGE -> AttentionAnatomy(figure = false, lines = 1, body = CHARGE_CHIP_ROW * CHARGE_CHIP_ROWS + CHARGE_CHIP_GAP * (CHARGE_CHIP_ROWS - 1),
        row = ATTENTION_FIELD_ROW + ATTENTION_FIELD_ERROR)
    AttentionKind.FINISHED, AttentionKind.MATCHED -> AttentionAnatomy(figure = true, lines = 1)
    AttentionKind.SETUP -> AttentionAnatomy(figure = false, lines = 0, body = SETUP_STEPS)
    AttentionKind.PAIRING -> AttentionAnatomy(figure = true, lines = 1, body = PAIRING_ROWS)
    AttentionKind.MONTH_CLOSE -> AttentionAnatomy(figure = false, lines = 1, under = CLOSE_BAR_GAP + CLOSE_BAR)
    AttentionKind.CLOSE_REMINDER -> AttentionAnatomy(figure = false, lines = 0)
    AttentionKind.CHARGE_WAITING -> AttentionAnatomy(figure = false, lines = 1, body = MINI_STATS)
}

/**
 * The tallest a card of [kind] gets at FULL 1× (pure, unit-tested): padding, the title row (taller with the [pager]), the
 * headline or its figure, the lines, the body and the action row. Every kind stays under [ATTENTION_CARD_CAP]: the incident
 * card is the tallest at 477 with the pager.
 */
internal fun attentionCardMax(kind: AttentionKind, pager: Boolean): Dp {
    val a = attentionAnatomy(kind)
    return ATTENTION_PADDING * 2 + (if (pager) ATTENTION_TITLE_PAGER else ATTENTION_TITLE) + ATTENTION_TITLE_GAP +
        (if (a.figure) ATTENTION_FIGURE else ATTENTION_HEADLINE) + a.under + ATTENTION_LINE * a.lines +
        (a.body?.let { ATTENTION_BLOCK_GAP + it } ?: 0.dp) + ATTENTION_BLOCK_GAP + a.row
}

/**
 * 홈's frame in the full window, slot by slot (§3.1): the greeting block, the card's gap and the card (none without a card),
 * the sky's fixed part, the demo strip with its gap, the destination row, the band's gap and the dock. [total] is the page's
 * content at its tallest; the page slot is 1097 dp.
 */
@Immutable internal data class HomeFrame(val greeting: Dp, val cardGap: Dp, val card: Dp, val sky: Dp, val demo: Dp, val destinations: Dp,
    val bandGap: Dp, val dock: Dp) {
    val total: Dp get() = greeting + cardGap + card + sky + demo + destinations + bandGap + dock
}

/**
 * The frame for the card of [kind] (null: the slot is empty), with the [pager] (two items or more) and the [demo] strip (pure,
 * unit-tested). Idle it is 384 dp; the worst, an incident with the pager in demo mode, 981 of the 1097-dp page.
 */
internal fun homeFrame(kind: AttentionKind?, pager: Boolean, demo: Boolean): HomeFrame = HomeFrame(
    greeting = HOME_GREETING_MIN,
    cardGap = if (kind != null) HOME_CARD_GAP else 0.dp,
    card = kind?.let { attentionCardMax(it, pager) } ?: 0.dp,
    sky = HOME_SKY_MIN,
    demo = if (demo) HOME_DEMO_STRIP + HOME_BAND_GAP else 0.dp,
    destinations = HOME_DESTINATION_ROW,
    bandGap = HOME_BAND_GAP,
    dock = HOME_DOCK,
)

/** The height of 홈's bottom band (demo strip, destination row, dock), the follow-up bar rides above it. */
internal fun homeBandHeight(demo: Boolean): Dp = (if (demo) HOME_DEMO_STRIP + HOME_BAND_GAP else 0.dp) + HOME_DESTINATION_ROW + HOME_BAND_GAP + HOME_DOCK

/**
 * Whether an incident button's caption (119 · 112 안내) sits beside its label (pure, unit-tested): from 196 dp of [button]
 * width, more with a larger font (one below 1× counts as 1×); narrower, it sits under the label in a 72-dp button. Either
 * way it stays on screen: it is the safety hint.
 */
internal fun incidentCaptionBeside(button: Dp, fontScale: Float): Boolean = button >= 196.dp * fontScale.coerceAtLeast(1f)

// ───────────────────────── words of the cards (pure, unit-tested) ─────────────────────────

/** A line on 홈 and what TalkBack says for it ([spoken], the long form where the line is the short one). */
@Immutable internal data class HomeLine(val text: String, val spoken: String = text)

/**
 * The status line's queue form: "확인할 일 3건 · 카드 확인 → 목적 확인 → 차량 관리", three names, then "외 N건"; TalkBack hears
 * the names one by one.
 */
internal fun attentionQueueLine(queue: List<AttentionItem>): HomeLine {
    val names = queue.map { it.kind.label }
    val shown = names.take(3).joinToString(" → ") + if (names.size > 3) " 외 ${names.size - 3}건" else ""
    return HomeLine("${HomeCopy.QUEUE_HEAD} ${queue.size}건 · $shown", "${HomeCopy.QUEUE_HEAD} ${queue.size}건, ${names.joinToString(", ")}")
}

/**
 * The card question's headline (pure, unit-tested): the matcher put the payment on a trip ([CardCharge.tripId]); the car only
 * guessed the nearest trip ([suggestedTrip] without a match), and says so; or it found no trip at all.
 */
internal fun chargeReviewHeadline(charge: CardCharge, trip: Trip?): String = when {
    trip == null -> HomeCopy.REVIEW_NO_TRIP
    charge.tripId != null -> HomeCopy.REVIEW_MATCHED
    else -> HomeCopy.REVIEW_NEAREST
}

/** The incident card's line under 지금 할 일 (pure, unit-tested): the photos the phone sent, where they are added, or how to turn that on. */
internal fun incidentHint(photos: Int, phoneSync: Boolean): HomeLine = when {
    photos > 0 -> HomeLine("사진 ${photos}장 · ${HomeCopy.INCIDENT_PHOTOS_SAVED}")
    phoneSync -> HomeLine(HomeCopy.INCIDENT_PHONE, HomeCopy.SPOKEN_INCIDENT_PHONE)
    else -> HomeLine(HomeCopy.INCIDENT_SYNC_OFF, HomeCopy.SPOKEN_INCIDENT_SYNC_OFF)
}

/** What the car captured for an incident, as the action row's line (pure): "계기판 51,300.0 km · 운전자 Kim Jiwoo · {주소}". */
internal fun incidentFacts(incident: Incident, driver: Driver?): String = listOf(
    "계기판 ${incident.odometerKm?.takeIf { it.isFinite() }?.let(::kmText) ?: "미수신"}",
    "운전자 ${driver?.let { it.name.ifBlank { it.label } } ?: "미지정"}",
    incident.address?.takeIf { it.isNotBlank() } ?: "내비 주소 미수신",
).joinToString(" · ")

/** The month-close card's line (pure): the first step still open, or that the month can be closed now. */
internal fun monthCloseTaskLine(status: MonthStatus): HomeLine = when {
    status.unfinished > 0 -> HomeLine("목적·사용자 확인 ${status.unfinished}건 남음", "1단계 목적·사용자 확인, ${status.unfinished}건 남음")
    status.reviews > 0 -> HomeLine("카드 확인 ${status.reviews}건 남음", "2단계 카드 확인, ${status.reviews}건 남음")
    status.ready -> HomeLine("모두 확인했어요 · 지금 마감할 수 있어요")
    else -> HomeLine("남은 확인 ${status.issues}건")
}

/** The one way on of a month-close card ([monthCloseNext]). */
internal enum class MonthCloseNext { TIDY, REVIEW, CLOSE }

/**
 * The one way on that both month-close cards offer (pure, unit-tested), in [monthCloseTaskLine]'s order: 정리하기 while trips
 * still want a purpose or a driver, 확인하기 while only card questions are left, and the close once nothing is. Card questions
 * get their own step because 점검 필요 lists trips alone: sent there, a month whose last open items are card payments would
 * find an empty list and no way on.
 */
internal fun monthCloseNext(status: MonthStatus): MonthCloseNext = when {
    status.unfinished > 0 -> MonthCloseNext.TIDY
    status.reviews > 0 -> MonthCloseNext.REVIEW
    else -> MonthCloseNext.CLOSE
}

/** When a trip ran (pure, unit-tested): "09:30 – 10:00" today, "11월 8일 (일) 09:30 – 10:00" on another day. */
internal fun tripWhenLine(trip: Trip, now: Long, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(trip.startAt).atZone(zone)
    val end = Instant.ofEpochMilli(trip.endAt ?: trip.updatedAt).atZone(zone)
    val span = "%02d:%02d – %02d:%02d".format(start.hour, start.minute, end.hour, end.minute)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (start.toLocalDate() == today) span else "${dayLabel(start.toLocalDate())} $span"
}

/** The purpose card's first line (pure): when the trip ran and where, "09:30 – 10:00 · 본사 → 강남 오피스"; the time alone without an address. */
internal fun purposeCardLine(trip: Trip, now: Long, zone: ZoneId): String {
    val route = listOfNotNull(trip.startAddress, trip.destination ?: trip.endAddress).filter { it.isNotBlank() }.joinToString(" → ")
    return listOf(tripWhenLine(trip, now, zone), route).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * The purpose card's state tag (pure, unit-tested): while a purpose is changed, the one the trip has ("현재 일반 업무"); while
 * several trips wait for one, how many ("미분류 3건"); a single question needs none.
 */
internal fun purposeCardTag(count: Int, current: TripPurpose?): String? = when {
    current != null && current != TripPurpose.UNCLASSIFIED -> "현재 ${current.label}"
    count > 1 -> "미분류 ${count}건"
    else -> null
}

/** Where a setup step's button leads: 설정 › 법인·차량, 설정 › 직원, or the driver picker. */
internal enum class SetupTarget { COMPANY, DRIVERS, PICKER }

/** One step of 시작 설정: its [label], whether it is [done], and its button ([action], none while it cannot be done yet). */
@Immutable internal data class SetupStep(val label: String, val done: Boolean, val action: String?, val target: SetupTarget)

/**
 * The three setup steps (pure, unit-tested): 법인·차량 정보 (company and plate), 직원 등록 and 운전자 선택. Each open step names
 * its own button; choosing a driver needs a registered employee first, so with nobody registered it has none.
 */
internal fun setupSteps(data: TripLogData): List<SetupStep> = listOf(
    SetupStep(HomeCopy.SETUP_COMPANY, data.profile.companyName.isNotBlank() && data.profile.plateNumber.isNotBlank(), HomeCopy.SETUP_COMPANY_ACTION, SetupTarget.COMPANY),
    SetupStep(HomeCopy.SETUP_DRIVERS, data.drivers.isNotEmpty(), HomeCopy.SETUP_DRIVERS_ACTION, SetupTarget.DRIVERS),
    SetupStep(HomeCopy.SETUP_PICK, data.currentDriverId != null && data.drivers.any { it.id == data.currentDriverId },
        HomeCopy.SETUP_PICK_ACTION.takeIf { data.drivers.isNotEmpty() }, SetupTarget.PICKER),
).map { if (it.done) it.copy(action = null) else it }

/** The month a batch of settled card payments opens on (pure): the month of its first payment; null for no batch. */
internal fun matchedBatchMonth(charges: List<CardCharge>, zone: ZoneId): YearMonth? =
    charges.minByOrNull { it.at }?.let { YearMonth.from(Instant.ofEpochMilli(it.at).atZone(zone)) }

/** True when [today] already holds a finished trip of the data set on screen: one condition of the tidy status line. */
internal fun finishedTripToday(data: TripLogData, today: LocalDate, zone: ZoneId): Boolean =
    data.trips.any { it.demo == data.profile.demoMode && !it.open && it.endAt != null && Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() == today }
