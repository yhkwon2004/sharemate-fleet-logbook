package ai.sharemate.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/*
 * The phone's 예약 tab (2.9). The phone only asks: a booking is a request sent over the paired link, and the car decides,
 * so two phones can never both win the same hour. The phone checks locally first with the same rules the car uses, so
 * an obvious clash is caught under the form instead of after a round trip. 2.10: the tab reads the layout tokens, puts
 * the day beside the form on a wide window, and takes the scene's selection and press feedback.
 */

// ───────────────────────── pure helpers (unit-tested) ─────────────────────────

internal const val SLOT_STEP_MINUTES = 30
internal const val DAY_MINUTES = 24 * 60
internal const val BOOKING_STRIP_DAYS = 14
internal const val TIMELINE_FIRST_HOUR = 6
/** The last hour row (23:00–24:00). */
internal const val TIMELINE_LAST_HOUR = 23
internal const val DEFAULT_SLOT_MINUTES = 120
internal const val DEFAULT_START_MINUTES = 9 * 60
internal const val BOOKING_DESTINATION_MAX = 60
internal const val BOOKING_NOTE_MAX = 200
private const val STEP_MILLIS = SLOT_STEP_MINUTES * 60_000L

/** [now] rounded up to the next half hour in [zone]; a time already on the half hour stays (09:00:00 → 09:00, 09:00:01 → 09:30). */
internal fun nextHalfHourMillis(now: Long, zone: ZoneId): Long {
    val start = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    val elapsed = now - start
    return start + (elapsed + STEP_MILLIS - 1) / STEP_MILLIS * STEP_MILLIS
}

/** The epoch millis of [minutes] after midnight on [date] in [zone]; 1440 is the next midnight. */
internal fun epochMillisAt(date: LocalDate, minutes: Int, zone: ZoneId): Long =
    date.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()

/** Minutes after midnight of [date] at [at], clamped to the day (0..1440). */
internal fun minutesOfDay(at: Long, date: LocalDate, zone: ZoneId): Int {
    val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
    return ((at - from) / 60_000L).coerceIn(0L, DAY_MINUTES.toLong()).toInt()
}

/** "09:00"; 1440 reads "24:00". */
internal fun minutesText(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

/** The dates the strip offers: today and the next [count] − 1 days. */
internal fun stripDates(today: LocalDate, count: Int = BOOKING_STRIP_DAYS): List<LocalDate> = (0 until count).map { today.plusDays(it.toLong()) }

/** "오늘", "내일", then "11/20 (금)". */
internal fun stripLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "오늘"
    today.plusDays(1) -> "내일"
    else -> "${date.monthValue}/${date.dayOfMonth} (${weekday(date)})"
}

/** What a screen reader hears for a strip chip: "오늘, 11월 4일 (수), 예약 2건". */
internal fun stripDescription(date: LocalDate, today: LocalDate, bookings: Int): String =
    listOfNotNull(stripLabel(date, today).takeIf { date == today || date == today.plusDays(1) }, dayLabel(date),
        if (bookings > 0) "예약 ${bookings}건" else null).joinToString(", ")

/** The form's start and end (minutes after midnight) for [date]: the next half hour today, 09:00 on later days, two hours long. */
internal fun defaultSlot(date: LocalDate, now: Long, zone: ZoneId): Pair<Int, Int> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val start = when {
        date.isAfter(today) -> DEFAULT_START_MINUTES
        date == today -> {
            val next = nextHalfHourMillis(now, zone)
            if (Instant.ofEpochMilli(next).atZone(zone).toLocalDate() == today) minutesOfDay(next, today, zone) else DAY_MINUTES - SLOT_STEP_MINUTES
        }
        else -> DEFAULT_START_MINUTES
    }.coerceIn(0, DAY_MINUTES - SLOT_STEP_MINUTES)
    return start to (start + DEFAULT_SLOT_MINUTES).coerceAtMost(DAY_MINUTES)
}

/** Moves the start by [delta] minutes and keeps the length, as long as the day allows; the end never comes before start + 30. */
internal fun shiftStart(start: Int, end: Int, delta: Int): Pair<Int, Int> {
    val next = (start + delta).coerceIn(0, DAY_MINUTES - SLOT_STEP_MINUTES)
    val length = (end - start).coerceAtLeast(SLOT_STEP_MINUTES)
    return next to (next + length).coerceIn(next + SLOT_STEP_MINUTES, DAY_MINUTES)
}

/** Moves the end by [delta] minutes, between start + 30 and 24:00. */
internal fun shiftEnd(start: Int, end: Int, delta: Int): Pair<Int, Int> = start to (end + delta).coerceIn(start + SLOT_STEP_MINUTES, DAY_MINUTES)

/**
 * The slot a tap on the free [hour] row fills in: from the hour (or the next half hour when it is under way), two hours
 * long, stopped at the next booking that day so the pre-filled slot is free.
 */
internal fun slotFromHour(hour: Int, date: LocalDate, dayBookings: List<Reservation>, now: Long, zone: ZoneId): Pair<Int, Int> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    var start = hour * 60
    if (date == today) {
        val next = nextHalfHourMillis(now, zone)
        if (Instant.ofEpochMilli(next).atZone(zone).toLocalDate() == today) start = maxOf(start, minutesOfDay(next, today, zone))
    }
    start = start.coerceIn(0, DAY_MINUTES - SLOT_STEP_MINUTES)
    val nextBooking = dayBookings.map { minutesOfDay(it.startAt, date, zone) }.filter { it > start }.minOrNull() ?: DAY_MINUTES
    val end = minOf(start + DEFAULT_SLOT_MINUTES, nextBooking, DAY_MINUTES).coerceAtLeast(start + SLOT_STEP_MINUTES)
    return start to end
}

/** After a request ending at [sentEnd] (minutes of day): the default-length slot right after it, or null when the day is used up. */
internal fun slotAfterSent(sentEnd: Int): Pair<Int, Int>? =
    if (sentEnd > DAY_MINUTES - SLOT_STEP_MINUTES) null else sentEnd to minOf(sentEnd + DEFAULT_SLOT_MINUTES, DAY_MINUTES)

/** The first booking touching [hour]:00–[hour+1]:00 on [date]. */
internal fun hourOccupant(dayBookings: List<Reservation>, date: LocalDate, hour: Int, zone: ZoneId): Reservation? {
    val from = epochMillisAt(date, hour * 60, zone)
    val to = epochMillisAt(date, (hour + 1) * 60, zone)
    return dayBookings.firstOrNull { it.startAt < to && from < it.endAt }
}

/** True once the whole hour row is over. */
internal fun hourIsPast(date: LocalDate, hour: Int, now: Long, zone: ZoneId): Boolean = epochMillisAt(date, (hour + 1) * 60, zone) <= now

/** Where the 'now' line sits inside the [hour] row (0..1), or null when now is not in that hour. */
internal fun nowFraction(date: LocalDate, hour: Int, now: Long, zone: ZoneId): Float? {
    val from = epochMillisAt(date, hour * 60, zone)
    val to = epochMillisAt(date, (hour + 1) * 60, zone)
    return if (now in from until to) (now - from).toFloat() / (to - from) else null
}

/** The booker's label on the car's driver list. */
internal fun bookerLabel(data: TripLogData, driverId: String): String = data.drivers.firstOrNull { it.id == driverId }?.label ?: "목록에 없는 직원"

/** "영업 · 김 · 일반 업무 · 본사 → 수원"; a booking without a purpose says so. */
internal fun reservationDetailText(data: TripLogData, reservation: Reservation): String =
    listOfNotNull(bookerLabel(data, reservation.driverId), reservation.purpose?.label ?: "목적 미정", reservation.destination.ifBlank { null }).joinToString(" · ")

/** The booked part of [date]: "09:00–12:00", clamped to the day. */
internal fun dayWindowText(reservation: Reservation, date: LocalDate, zone: ZoneId): String =
    "${minutesText(minutesOfDay(reservation.startAt, date, zone))}–${minutesText(minutesOfDay(reservation.endAt, date, zone))}"

/** The follow-up after a request goes out: "11월 20일 (금) 09:00–12:00 예약을 차량에 보냈어요". */
internal fun reservationSentText(date: LocalDate, start: Int, end: Int): String = "${dayLabel(date)} ${minutesText(start)}–${minutesText(end)} 예약을 차량에 보냈어요"

/** "2시간 30분", "30분". */
internal fun slotLengthText(start: Int, end: Int): String {
    val minutes = (end - start).coerceAtLeast(0)
    return when { minutes < 60 -> "${minutes}분"; minutes % 60 == 0 -> "${minutes / 60}시간"; else -> "${minutes / 60}시간 ${minutes % 60}분" }
}

/** The account's employee id when it is on this car's driver list; null means the phone must ask who the user is first. */
internal fun bookingDriverId(account: PhoneAccount?, data: TripLogData): String? = account?.driverId?.takeIf { id -> data.drivers.any { it.id == id } }

/** Why the form cannot send right now, or null when it can. */
internal fun bookingBlockedReason(paired: Boolean, connected: Boolean): String? = when {
    !paired -> "예약은 연결된 차량이 확정해요. 설정에서 차량과 연결하면 여기서 신청할 수 있어요."
    !connected -> "지금은 차량과 연결되지 않아 신청할 수 없어요. 겹치는 예약은 차량이 판단하니 연결된 뒤 다시 신청해 주세요."
    else -> null
}

/** Upcoming bookings split into the user's own and everyone else's. */
internal fun splitReservations(upcoming: List<Reservation>, driverId: String?): Pair<List<Reservation>, List<Reservation>> =
    upcoming.partition { driverId != null && it.driverId == driverId }

/** The sentence shown under the form when the slot is taken, or null when it is free. */
internal fun slotConflictText(data: TripLogData, startAt: Long, endAt: Long, zone: ZoneId): String? =
    reservationConflict(data.reservations, startAt, endAt, data.profile.demoMode)?.let { other ->
        "${reservationWindowText(other, zone)}에 ${bookerLabel(data, other.driverId)} 님 예약이 있어요."
    }

/** The same checks the car runs, done on the phone first; returns the sentence to show, or null when the request may go out. */
internal fun precheckReservation(data: TripLogData, request: Reservation, now: Long, zone: ZoneId): String? =
    try { validateReservationRequest(data, request, now, zone); null } catch (e: IllegalArgumentException) { e.message ?: "예약 내용을 확인해 주세요." }

/** Spec B: on a landscape phone or a tablet (MEDIUM and up) the day's timeline sits beside the request form instead of above it. */
internal fun bookingTwoPane(tokens: LayoutTokens): Boolean = !tokens.compact

/** The 'now' line's resting place inside its hour row: the fraction, or the top edge while the line is elsewhere (nothing is drawn then). */
internal fun nowLineTarget(marker: Float?): Float = marker?.coerceIn(0f, 1f) ?: 0f

// ───────────────────────── tab ─────────────────────────

/**
 * The 예약 tab: the car's state at a glance, a 14-day strip, the chosen day from 06:00 to 24:00, the request form and the
 * upcoming bookings. [send] dispatches to the car (the shell reports a failed send); [onPickMe] opens the employee list
 * so the account can say which employee it is.
 */
@Composable internal fun PhoneReservationsTab(data: TripLogData, account: PhoneAccount?, paired: Boolean, connected: Boolean, busy: Boolean, now: Long,
    send: (SyncCommand, () -> Unit) -> Unit, onPickMe: () -> Unit, modifier: Modifier = Modifier) {
    val tz = remember { ZoneId.systemDefault() }
    val followUp = LocalFollowUp.current
    val tokens = phoneTokens()
    val twoPane = bookingTwoPane(tokens)
    // The now line moves on the minute even while nothing else on the phone changes.
    val clock by produceState(now) { while (true) { delay(60_000L - System.currentTimeMillis() % 60_000L); value = System.currentTimeMillis() } }
    val liveNow = maxOf(now, clock)
    val today = Instant.ofEpochMilli(liveNow).atZone(tz).toLocalDate()
    val dates = remember(today) { stripDates(today) }
    var selectedDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedDay).takeIf { it in dates } ?: today
    val dayBookings = remember(data, selected) { reservationsOn(data, selected, tz) }
    val driverId = bookingDriverId(account, data)
    val blocked = bookingBlockedReason(paired, connected)

    var start by rememberSaveable(selected.toEpochDay()) { mutableIntStateOf(defaultSlot(selected, now, tz).first) }
    var end by rememberSaveable(selected.toEpochDay()) { mutableIntStateOf(defaultSlot(selected, now, tz).second) }
    var error by remember { mutableStateOf<String?>(null) }
    val setSlot: (Pair<Int, Int>) -> Unit = { (s, e) -> start = s; end = e; error = null }
    // Picking an hour fills the form; the form then scrolls into view so the next step is on screen.
    val formView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    val day: @Composable ColumnScope.() -> Unit = {
        AvailabilityCard(data, liveNow, tz, tokens.panelPadding)
        DateStrip(dates, selected, today, data, tz) { selectedDay = it.toEpochDay(); error = null }
        DayTimeline(selected, today, dayBookings, data, driverId, liveNow, tz, tokens.panelPadding, enabled = blocked == null) { hour ->
            setSlot(slotFromHour(hour, selected, dayBookings, liveNow, tz)); scope.launch { formView.bringIntoView() }
        }
    }
    val request: @Composable ColumnScope.() -> Unit = {
        Box(Modifier.bringIntoViewRequester(formView)) {
            ReservationForm(data, selected, start, end, driverId, blocked, busy, error, tz, tokens.panelPadding, onSlot = setSlot, onPickMe = onPickMe, onError = { error = it }) { request, confirmed ->
                send(SyncCommand.Reserve(request)) {
                    confirmed()
                    followUp(FollowUp(reservationSentText(selected, start, end)))
                    val next = slotAfterSent(end)
                    if (next != null) setSlot(next) else { selectedDay = selected.plusDays(1).toEpochDay(); error = null }
                }
            }
        }
        ReservationLists(data, driverId, liveNow, tz, enabled = blocked == null && !busy) { reservation ->
            send(SyncCommand.CancelReservation(reservation.id)) {
                followUp(FollowUp("예약을 취소했어요", "실행 취소") { send(SyncCommand.RestoreReservation(reservation.id)) {} })
            }
        }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.contentWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = tokens.gutter).padding(top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(tokens.cardGap)) {
            if (twoPane) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokens.cardGap)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(tokens.cardGap), content = day)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(tokens.cardGap), content = request)
            } else { day(); request() }
        }
    }
}

@Composable private fun AvailabilityCard(data: TripLogData, now: Long, tz: ZoneId, padding: androidx.compose.ui.unit.Dp) {
    val availability = remember(data, now) { vehicleAvailability(data, now) }
    val text = availabilityText(availability, now, tz)
    val dot = when (availability) {
        is VehicleAvailability.InUse -> Scene.warn
        is VehicleAvailability.Reserved -> Scene.glow
        is VehicleAvailability.Free -> Scene.positive
    }
    val plate = data.profile.plateNumber.ifBlank { "차량번호 미등록" }
    val model = (data.vehicle.model ?: data.profile.carModel).ifBlank { null }
    Surface(shape = RoundedCornerShape(26.dp), color = Scene.glass, border = Scene.edge,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("차량 예약", style = MaterialTheme.typography.labelMedium, color = Scene.brand, letterSpacing = 1.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                Text(text, style = MaterialTheme.typography.titleLarge, color = Scene.text, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() })
            }
            Text(listOfNotNull(plate, model).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Fourteen 48-dp date chips that scroll; the chosen one carries the selection fill, ring and glow label. */
@Composable private fun DateStrip(dates: List<LocalDate>, selected: LocalDate, today: LocalDate, data: TripLogData, tz: ZoneId, onSelect: (LocalDate) -> Unit) {
    val counts = remember(data, dates) { dates.associateWith { reservationsOn(data, it, tz).size } }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        dates.forEach { date ->
            val active = date == selected
            val count = counts[date] ?: 0
            val press = remember { MutableInteractionSource() }
            Surface(selected = active, onClick = { onSelect(date) }, shape = RoundedCornerShape(18.dp),
                color = if (active) Scene.selectionFill else Scene.glass, border = selectionBorder(active), interactionSource = press,
                modifier = Modifier.pressable(press).heightIn(min = 48.dp).widthIn(min = 64.dp).semantics { contentDescription = stripDescription(date, today, count); role = Role.Tab }) {
                Column(Modifier.clearAndSetSemantics {}.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                    Text(stripLabel(date, today), style = MaterialTheme.typography.labelLarge, color = if (active) Scene.glow else Scene.text, maxLines = 1)
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (count > 0) Scene.brand else Color.Transparent))
                }
            }
        }
    }
}

@Composable private fun DayTimeline(date: LocalDate, today: LocalDate, dayBookings: List<Reservation>, data: TripLogData, driverId: String?, now: Long, tz: ZoneId,
    padding: androidx.compose.ui.unit.Dp, enabled: Boolean, onPickHour: (Int) -> Unit) {
    val early = dayBookings.any { it.startAt < epochMillisAt(date, TIMELINE_FIRST_HOUR * 60, tz) }
    Panel(color = Scene.glass, padding = padding) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${stripLabel(date, today)} 예약 현황", style = MaterialTheme.typography.titleSmall, color = Scene.text,
                modifier = Modifier.weight(1f).semantics { heading() })
            DigitChange("${dayBookings.size}건", style = MaterialTheme.typography.labelMedium, color = Scene.textMuted)
        }
        // Hours already over with nothing booked fold into one line, so today opens at the hour that can still be booked.
        val skipped = (TIMELINE_FIRST_HOUR..TIMELINE_LAST_HOUR).takeWhile { hourIsPast(date, it, now, tz) && hourOccupant(dayBookings, date, it, tz) == null }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (skipped.size > 1) Text("%02d:00–%02d:00 · 지난 시간".format(skipped.first(), skipped.last() + 1), style = MaterialTheme.typography.labelMedium,
                color = Scene.textMuted, modifier = Modifier.padding(vertical = 6.dp))
            for (hour in (if (skipped.size > 1) skipped.last() + 1 else TIMELINE_FIRST_HOUR)..TIMELINE_LAST_HOUR) {
                val occupant = hourOccupant(dayBookings, date, hour, tz)
                val first = occupant != null && (hour == TIMELINE_FIRST_HOUR || hourOccupant(dayBookings, date, hour - 1, tz)?.id != occupant.id)
                HourRow(hour, occupant, first, data, mine = occupant != null && occupant.driverId == driverId, past = hourIsPast(date, hour, now, tz),
                    marker = nowFraction(date, hour, now, tz), date = date, tz = tz, onPick = if (enabled) ({ onPickHour(hour) }) else null)
            }
        }
        if (early) Muted("06:00 전에 시작하는 예약은 아래 목록에서 볼 수 있어요.")
        if (enabled) Muted("빈 시간을 누르면 신청서의 시작 시각이 채워져요.")
    }
}

@Composable private fun HourRow(hour: Int, occupant: Reservation?, first: Boolean, data: TripLogData, mine: Boolean, past: Boolean, marker: Float?,
    date: LocalDate, tz: ZoneId, onPick: (() -> Unit)?) {
    val shape = RoundedCornerShape(14.dp)
    val fill = when {
        occupant == null -> Scene.fill
        mine -> Scene.selectionFill
        else -> Scene.brand.copy(alpha = .16f)
    }
    val edge: BorderStroke? = when {
        occupant == null || !first -> null
        mine -> BorderStroke(1.5.dp, Scene.selectionRing)
        else -> BorderStroke(1.dp, Scene.brand.copy(alpha = .5f))
    }
    // Hoisted for the draw lambda; the line itself slides to the new minute (enterSpec: 220 ms, a snap under reduced motion).
    val nowLine = Scene.glow.copy(alpha = .55f)
    val nowDot = Scene.glow
    val lineAt by animateFloatAsState(nowLineTarget(marker), enterSpec(), label = "now-line")
    val tappable = occupant == null && !past && onPick != null
    val press = remember { MutableInteractionSource() }
    val label = when {
        occupant != null && first -> "${bookerLabel(data, occupant.driverId)} · ${dayWindowText(occupant, date, tz)}"
        occupant != null -> "예약 계속"
        past -> "지난 시간"
        else -> "비어 있어요"
    }
    val description = "${minutesText(hour * 60)}, " + (if (occupant != null) "${bookerLabel(data, occupant.driverId)} 님 예약 ${dayWindowText(occupant, date, tz)}" else label) +
        if (marker != null) ", 지금" else ""
    Row(Modifier.fillMaxWidth().alpha(if (past) .45f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(minutesText(hour * 60), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = Scene.textMuted,
            modifier = Modifier.widthIn(min = 44.dp).clearAndSetSemantics {})
        Box(Modifier.weight(1f).then(if (tappable) Modifier.pressable(press) else Modifier).heightIn(min = 48.dp).clip(shape).background(fill)
            .then(if (edge != null) Modifier.border(edge, shape) else Modifier)
            .then(if (tappable) Modifier.clickable(interactionSource = press, indication = LocalIndication.current, onClickLabel = "이 시간으로 신청서 채우기", role = Role.Button) { onPick?.invoke() } else Modifier)
            .drawWithContent {
                // The now line runs under the row's text so "비어 있어요" never reads as struck through.
                if (marker != null) {
                    val y = size.height * lineAt
                    drawLine(nowLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                    drawCircle(nowDot, radius = 4.dp.toPx(), center = Offset(4.dp.toPx(), y))
                }
                drawContent()
            }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
            Text(label, Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = when { occupant != null && first -> Scene.text; occupant != null -> Scene.textMuted; else -> Scene.textSecondary })
        }
    }
}

@Composable private fun ReservationForm(data: TripLogData, date: LocalDate, start: Int, end: Int, driverId: String?, blocked: String?, busy: Boolean, error: String?, tz: ZoneId,
    padding: androidx.compose.ui.unit.Dp, onSlot: (Pair<Int, Int>) -> Unit, onPickMe: () -> Unit, onError: (String?) -> Unit, onSubmit: (Reservation, () -> Unit) -> Unit) {
    var purposeName by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    // True from the tap until the car answers (or the send fails and the shell is idle again): the button shows the work.
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { if (!busy) sending = false }
    val purpose = purposeChoices.firstOrNull { it.name == purposeName }
    val editable = blocked == null && !busy
    val startAt = epochMillisAt(date, start, tz)
    val endAt = epochMillisAt(date, end, tz)
    val conflict = remember(data, startAt, endAt) { slotConflictText(data, startAt, endAt, tz) }
    Panel(color = Scene.glass, padding = padding) {
        Text("새 예약", style = MaterialTheme.typography.labelMedium, color = Scene.brand, letterSpacing = 1.sp)
        Text(dayLabel(date), style = MaterialTheme.typography.titleMedium, color = Scene.text, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        if (blocked != null) Text(blocked, style = MaterialTheme.typography.bodyMedium, color = Scene.warn, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (driverId == null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("먼저 나를 직원 목록에서 선택해 주세요", style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary)
                OutlinedButton(onClick = onPickMe, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("직원 목록 열기") }
            }
        } else Text("예약자 · ${bookerLabel(data, driverId)}", style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary)

        TimeStepper("시작", start, editable, canMinus = start > 0, canPlus = start < DAY_MINUTES - SLOT_STEP_MINUTES,
            onMinus = { onSlot(shiftStart(start, end, -SLOT_STEP_MINUTES)) }, onPlus = { onSlot(shiftStart(start, end, SLOT_STEP_MINUTES)) })
        TimeStepper("끝", end, editable, canMinus = end > start + SLOT_STEP_MINUTES, canPlus = end < DAY_MINUTES,
            onMinus = { onSlot(shiftEnd(start, end, -SLOT_STEP_MINUTES)) }, onPlus = { onSlot(shiftEnd(start, end, SLOT_STEP_MINUTES)) })
        DigitChange("${slotLengthText(start, end)} 동안 · 30분 단위", style = MaterialTheme.typography.labelMedium, color = Scene.textMuted)

        Text("목적", style = MaterialTheme.typography.titleSmall, color = Scene.text)
        val options: List<Pair<String, String>> = purposeChoices.map { it.name to it.label } + ("" to "정하지 않음")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (name, label) ->
                        PurposeRadio(label, name == purposeName, editable, Modifier.weight(1f)) { purposeName = name; onError(null) }
                    }
                }
            }
        }
        if (purpose != null && data.profile.autopilot) Text("예약한 시간의 운행은 ${withRo(purpose.label)} 기록돼요.", style = MaterialTheme.typography.labelMedium, color = Scene.textMuted)

        BookingField("목적지", destination, BOOKING_DESTINATION_MAX, editable, done = false) { destination = it; onError(null) }
        BookingField("메모", note, BOOKING_NOTE_MAX, editable, done = true) { note = it; onError(null) }

        InlineError(error ?: conflict)
        Button(onClick = {
            val id = driverId ?: return@Button
            val request = Reservation(UUID.randomUUID().toString(), id, startAt, endAt, purpose, destination.trim(), note.trim())
            val problem = precheckReservation(data, request, System.currentTimeMillis(), tz)
            if (problem != null) onError(problem)
            else { onError(null); sending = true; onSubmit(request) { destination = ""; note = ""; sending = false } }
        }, enabled = editable && driverId != null && !sending, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
            LoadingLabel("예약 신청", sending, "보내는 중…")
        }
        Text("겹치는 예약이 있는지는 차량이 마지막으로 확인해요. 확정되면 아래 목록에 나타나요.", style = MaterialTheme.typography.labelMedium, color = Scene.textMuted)
    }
}

/** A 56-dp stepper row with 48-dp ± buttons (spec B); the time cross-fades to its new value. */
@Composable private fun TimeStepper(label: String, minutes: Int, enabled: Boolean, canMinus: Boolean, canPlus: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = Scene.fill, border = Scene.edge, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = Scene.textSecondary, modifier = Modifier.weight(1f))
            StepButton("−", "$label 30분 앞당기기", enabled && canMinus, onMinus)
            // The box keeps the digits centred between the buttons (the cross-fade wraps its text) and reads as one value to a screen reader.
            Box(Modifier.widthIn(min = 76.dp).semantics { contentDescription = "$label ${minutesText(minutes)}"; liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.Center) {
                DigitChange(minutesText(minutes), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = Scene.text,
                    modifier = Modifier.clearAndSetSemantics {})
            }
            StepButton("+", "$label 30분 늦추기", enabled && canPlus, onPlus)
        }
    }
}

@Composable private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = if (enabled) Scene.selectionFill else Color.Transparent, interactionSource = press,
        modifier = Modifier.pressable(press).size(48.dp).semantics { contentDescription = description }) {
        Box(Modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Text(symbol, style = MaterialTheme.typography.titleLarge, color = if (enabled) Scene.glow else Scene.textMuted)
        }
    }
}

@Composable private fun PurposeRadio(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Surface(selected = selected, onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp),
        color = if (selected) Scene.selectionFill else Scene.fill, border = selectionBorder(selected), interactionSource = press,
        modifier = modifier.pressable(press).heightIn(min = 52.dp).semantics { role = Role.RadioButton }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(16.dp).clip(CircleShape).border(2.dp, if (selected) Scene.glow else Scene.textMuted, CircleShape), contentAlignment = Alignment.Center) {
                if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Scene.glow))
            }
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = if (selected) Scene.glow else Scene.text)
        }
    }
}

@Composable private fun BookingField(label: String, value: String, max: Int, enabled: Boolean, done: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.take(max)) }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled, singleLine = max <= BOOKING_DESTINATION_MAX,
        label = { Text(label) }, shape = RoundedCornerShape(16.dp), textStyle = MaterialTheme.typography.bodyLarge,
        supportingText = { Text("${value.length}/$max", style = MaterialTheme.typography.labelSmall) },
        keyboardOptions = KeyboardOptions(imeAction = if (done) ImeAction.Done else ImeAction.Next), colors = sceneTextFieldColors())
}

private val seenRowsSaver = listSaver<MutableState<Set<String>>, String>(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) })

@Composable private fun ReservationLists(data: TripLogData, driverId: String?, now: Long, tz: ZoneId, enabled: Boolean, onCancel: (Reservation) -> Unit) {
    val upcoming = remember(data, now) { upcomingReservations(data, now) }
    val (mine, others) = remember(upcoming, driverId) { splitReservations(upcoming, driverId) }
    // Rows arrive once: when the list is first seen and when a booking the car just confirmed joins it; not on a return to the tab.
    val seen = rememberSaveable(saver = seenRowsSaver) { mutableStateOf(emptySet()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PhoneSectionTitle("내 예약", trailing = "${mine.size}건")
        if (mine.isEmpty()) Muted(if (driverId == null) "직원 목록에서 나를 선택하면 내 예약을 따로 모아 보여 줘요." else "다가오는 내 예약이 없어요.", Modifier.padding(horizontal = 4.dp))
        mine.forEachIndexed { index, reservation ->
            key(reservation.id) { AppearingRow(reservation.id, index, seen) { ReservationRow(reservation, data, now, tz, enabled, mine = true) { onCancel(reservation) } } }
        }
        Spacer(Modifier.height(4.dp))
        PhoneSectionTitle("다른 예약", trailing = "${others.size}건")
        if (others.isEmpty()) Muted("다른 직원의 다가오는 예약이 없어요.", Modifier.padding(horizontal = 4.dp))
        others.forEachIndexed { index, reservation ->
            key(reservation.id) { AppearingRow(reservation.id, mine.size + index, seen) { ReservationRow(reservation, data, now, tz, enabled, mine = false) { onCancel(reservation) } } }
        }
    }
}

/** Fades and lifts a row in on its first appearance only (stagger capped at the phone's eight); rows already seen draw in place. */
@Composable private fun AppearingRow(id: String, position: Int, seen: MutableState<Set<String>>, content: @Composable () -> Unit) {
    val first = remember(id) { id !in seen.value }
    LaunchedEffect(id) { if (first) seen.value = seen.value + id }
    Appear(index = appearIndex(position), animate = first) { content() }
}

@Composable private fun ReservationRow(reservation: Reservation, data: TripLogData, now: Long, tz: ZoneId, enabled: Boolean, mine: Boolean, onCancel: () -> Unit) {
    val window = reservationWindowText(reservation, tz)
    val ongoing = reservation.startAt <= now
    Surface(shape = RoundedCornerShape(20.dp), color = Scene.glass, border = if (mine) BorderStroke(1.dp, Scene.selectionRing) else Scene.edge,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (ongoing) Text("사용 중", style = MaterialTheme.typography.labelMedium, color = Scene.glow)
                Text(window, style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(reservationDetailText(data, reservation), style = MaterialTheme.typography.bodySmall, color = Scene.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (reservation.note.isNotBlank()) Text(reservation.note, style = MaterialTheme.typography.bodySmall, color = Scene.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onCancel, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp)
                .semantics { contentDescription = "$window ${bookerLabel(data, reservation.driverId)} 예약 취소" }) {
                Text("취소", color = if (enabled) Scene.negative else Scene.textMuted)
            }
        }
    }
}
