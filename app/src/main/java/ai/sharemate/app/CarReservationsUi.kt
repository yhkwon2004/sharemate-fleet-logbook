package ai.sharemate.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

/*
 * Bookings on the car (2.9). The car owns the calendar: phones ask, the car confirms, and the car can cancel any booking
 * (with undo). The 지금 column's 예약 tile names the booking in use or the next one, and 홈 without the column shows the same
 * tile while one is due within a day; the 차량 tab lists them all.
 */

// ───────────────────────── pure copy (unit-tested) ─────────────────────────

internal const val CAR_RESERVATION_ROWS = 6
private const val LINE_HORIZON_MILLIS = 24 * 60 * 60_000L

/** "17:00" today, "내일 09:00", or "11월 21일 (토) 18:00". */
internal fun carClockText(at: Long, now: Long, zone: ZoneId): String {
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = "%02d:%02d".format(time.hour, time.minute)
    return when (time.toLocalDate()) { today -> clock; today.plusDays(1) -> "내일 $clock"; else -> "${dayLabel(time.toLocalDate())} $clock" }
}

/** The booker as the car greets them: the name ("김"), or the label when no name was given. */
internal fun bookerName(data: TripLogData, driverId: String): String =
    data.drivers.firstOrNull { it.id == driverId }?.let { it.name.ifBlank { it.label } }?.ifBlank { null } ?: "예약한 직원"

/** The active booking's headline: "예약 사용 중 · 17:00까지", or during the lead before it starts "곧 예약 시작 · 14:00부터 17:00까지". */
internal fun carActiveTitle(reservation: Reservation, now: Long, zone: ZoneId): String =
    if (now < reservation.startAt) "곧 예약 시작 · ${carClockText(reservation.startAt, now, zone)}부터 ${carClockText(reservation.endAt, now, zone)}까지"
    else "예약 사용 중 · ${carClockText(reservation.endAt, now, zone)}까지"

/** The 지금 column's 예약 tile: its one [text] line, and what TalkBack hears for the whole tile ([spoken]). */
@Immutable
internal data class ReservationTileLine(val text: String, val spoken: String)

/**
 * The 예약 tile's line (2.12, pure, unit-tested): the booking in use ("사용 중 · 17:00까지"), or starting within the lead
 * ("곧 시작 · 14:00부터"), else the next confirmed booking however far ahead ("오늘 14:00", "내일 09:30", "11월 21일 (토)
 * 18:00"), else "현재 잡힌 예약이 없어요". Unlike 홈's rule for the tile ([reservationDueWithinDay], 24 hours) it reads every upcoming
 * booking, so "none" is only said when it is true. The booker's name ends the line (" · 김 님") unless they are the 사용자
 * already or not on the roster. [ReservationTileLine.spoken] is the long form: "예약, 사용 중, 17:00까지, 영업 · 김", with
 * "운전자를 예약한 직원으로 맞췄어요" once the car has made the booker the driver (the reason their name left the line).
 */
internal fun reservationTileLine(data: TripLogData, now: Long, zone: ZoneId): ReservationTileLine {
    val none = "현재 잡힌 예약이 없어요"
    fun name(r: Reservation) = if (r.driverId == data.currentDriverId || data.drivers.none { it.id == r.driverId }) "" else " · ${bookerName(data, r.driverId)} 님"
    activeReservation(data, now)?.let { r ->
        val matched = data.currentDriverId == r.driverId && data.drivers.any { it.id == r.driverId }
        val note = if (matched) ", 운전자를 예약한 직원으로 맞췄어요" else ""
        val until = "${carClockText(r.endAt, now, zone)}까지"
        if (now < r.startAt) {
            val from = "${carClockText(r.startAt, now, zone)}부터"
            return ReservationTileLine("곧 시작 · $from${name(r)}", "예약, 곧 시작, $from $until, ${bookerLabel(data, r.driverId)}$note")
        }
        return ReservationTileLine("사용 중 · $until${name(r)}", "예약, 사용 중, $until, ${bookerLabel(data, r.driverId)}$note")
    }
    val next = upcomingReservations(data, now).firstOrNull { it.startAt > now } ?: return ReservationTileLine(none, "예약, $none")
    val start = reservationStartText(next, now, zone)
    return ReservationTileLine("$start${name(next)}", "예약, 다음 예약 $start, ${reservationDetailText(data, next)}")
}

/**
 * Whether 홈 without the 지금 column shows the 예약 tile (2.13, pure, unit-tested): while a booking is in use or in its lead
 * before it starts, or the next one starts within 24 hours. The tile itself ([ReservationTile]) says which, in the column's
 * words; further ahead a booking waits on 차량, so the narrow 홈 keeps its room for what is due.
 */
internal fun reservationDueWithinDay(data: TripLogData, now: Long): Boolean =
    activeReservation(data, now) != null || upcomingReservations(data, now).any { it.startAt > now && it.startAt - now <= LINE_HORIZON_MILLIS }

/**
 * What the empty card says (2.12, pure, unit-tested), as the ⓘ row's line and what it folds: where bookings come from, then what
 * the car does when one starts; the purpose clause only when the car really applies booked purposes (automatic
 * classification on).
 */
internal fun carReservationsEmptyText(autopilot: Boolean): Pair<String, List<String>> =
    "휴대폰 앱 예약 탭에서 잡으면 여기 보여요" to listOfNotNull("예약 시간이 되면 예약한 직원으로 바꿔요", "그 시간 운행은 예약 목적으로 기록돼요".takeIf { autopilot })

/** The line under the card's rows when more bookings wait than it lists (pure, unit-tested): how many, and where to see them. */
internal fun carReservationsOverflow(more: Int): String = "외 ${more}건은 휴대폰 앱 예약 탭에서 봐요"

// ───────────────────────── 차량 tab card ─────────────────────────

/**
 * The car's bookings (2.12): the title with the upcoming count as a tag, the one in use first (in glow), then the upcoming ones,
 * each a row cancellable with undo. Empty, the same words as the 지금 column's 예약 tile, with where bookings come from behind
 * ⓘ. Rows arrive staggered the first time only.
 */
@Composable internal fun CarReservationsCard(state: TripLogState, now: Long, act: Act, repository: TripLogRepository, modifier: Modifier = Modifier) {
    val data = state.data
    val tz = remember { ZoneId.systemDefault() }
    val followUp = LocalFollowUp.current
    val active = remember(data, now) { activeReservation(data, now) }
    val upcoming = remember(data, now, active) { upcomingReservations(data, now).filter { it.id != active?.id } }
    val cancel: (Reservation) -> Unit = { reservation ->
        act {
            repository.cancelReservation(reservation.id)
            followUp(FollowUp("예약을 취소했어요", "실행 취소") { repository.restoreReservation(reservation.id) })
        }
    }
    // First composition only (spec C): a booking a phone adds later, and the list on a return to this tab, are simply there.
    var introduced by rememberSaveable { mutableStateOf(false) }
    val stagger = remember { !introduced }
    val initial = remember { (listOfNotNull(active) + upcoming).map { it.id }.toSet() }
    LaunchedEffect(Unit) { introduced = true }
    val count = upcoming.size
    CarCard(modifier, title = {
        CarSectionTitle(VehicleCopy.RESERVATIONS_TITLE, icon = rememberVectorPainter(Icons.Outlined.Notifications),
            trailing = if (count > 0) { { CarTag("다가오는 ${count}건") } } else null)
    }) {
        if (active == null && upcoming.isEmpty()) {
            Text(VehicleCopy.RESERVATIONS_EMPTY, style = MaterialTheme.typography.titleSmall, color = Scene.text)
            val (line, more) = carReservationsEmptyText(data.profile.autopilot)
            CarInfoRow(line, more = more)
        } else Column {
            active?.let { r ->
                Appear(index = 0, animate = stagger && r.id in initial) {
                    CarReservationRow(carActiveTitle(r, now, tz), reservationDetailText(data, r), highlighted = true,
                        cancelDescription = "사용 중인 ${bookerLabel(data, r.driverId)} 예약 취소") { cancel(r) }
                }
            }
            upcoming.take(CAR_RESERVATION_ROWS).forEachIndexed { index, r ->
                val window = reservationWindowText(r, tz)
                val order = index + if (active != null) 1 else 0
                // The rule above a row arrives with it.
                Appear(index = order.coerceAtMost(CAR_APPEAR_CAP - 1), animate = stagger && r.id in initial) {
                    Column {
                        if (order > 0) CarDivider()
                        CarReservationRow(window, reservationDetailText(data, r), highlighted = false,
                            cancelDescription = "$window ${bookerLabel(data, r.driverId)} 예약 취소") { cancel(r) }
                    }
                }
            }
        }
        if (upcoming.size > CAR_RESERVATION_ROWS) CarLine(carReservationsOverflow(upcoming.size - CAR_RESERVATION_ROWS))
    }
}

/**
 * One booking as a [CarRow]: a 12-dp dot (glow for the one in use, the brand at 70 % for the others), the window or the active
 * headline as the title (two lines: a booking window is data, never cut), the booker and purpose under it, and 취소 in the
 * scene's negative, which names the booking for TalkBack ([cancelDescription]).
 */
@Composable private fun CarReservationRow(title: String, detail: String, highlighted: Boolean, cancelDescription: String, onCancel: () -> Unit) {
    val dot = if (highlighted) Scene.glow else Scene.brand.copy(alpha = .7f)
    CarRow(title, line = detail, lead = { Box(Modifier.size(12.dp).clip(CircleShape).background(dot)) },
        titleColor = if (highlighted) Scene.glow else Scene.text, titleMaxLines = 2,
        trailing = { CarPillButton("취소", onCancel, color = Scene.negative, description = cancelDescription) })
}
