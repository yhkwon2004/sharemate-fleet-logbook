package ai.sharemate.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Vehicle reservations (2.9). Each car keeps its own booking calendar; phones ask for a slot over the paired link and the car
 * decides, so two phones can never both win the same hour. A confirmed slot also drives automation on the car: when the
 * window opens the car makes the booker the current employee, and a trip made inside the window takes the booked purpose.
 */

enum class ReservationStatus(val label: String) { CONFIRMED("예약 확정"), CANCELLED("취소됨") }

data class Reservation(
    val id: String,
    val driverId: String,
    val startAt: Long,
    val endAt: Long,
    val purpose: TripPurpose? = null,
    val destination: String = "",
    val note: String = "",
    val status: ReservationStatus = ReservationStatus.CONFIRMED,
    val createdAt: Long = startAt,
    val source: EditSource = EditSource.PHONE,
    val cancelledAt: Long? = null,
    val demo: Boolean = false,
)

internal const val MAX_RESERVATIONS = 500
internal const val MIN_RESERVATION_MINUTES = 15L
internal const val MAX_RESERVATION_HOURS = 7 * 24L
/** How far ahead a booking may be made. */
internal const val MAX_RESERVATION_AHEAD_DAYS = 90L
/** The car switches to the booker this long before the slot starts, so the driver who walks up early finds their name. */
internal const val RESERVATION_LEAD_MILLIS = 15 * 60_000L
/** Bookings that ended or were cancelled this long ago are dropped from the car's calendar. */
internal const val RESERVATION_KEEP_DAYS = 90L
private const val MINUTE = 60_000L
private const val DAY = 24 * 60 * MINUTE

/** The first confirmed booking (other than [exceptId]) that overlaps [startAt, endAt), in the same data set. */
internal fun reservationConflict(existing: List<Reservation>, startAt: Long, endAt: Long, demo: Boolean, exceptId: String? = null): Reservation? =
    existing.firstOrNull { it.status == ReservationStatus.CONFIRMED && it.demo == demo && it.id != exceptId && it.startAt < endAt && startAt < it.endAt }

/** Throws with the sentence the phone shows when a request cannot be booked; returns normally when it can. */
internal fun validateReservationRequest(data: TripLogData, request: Reservation, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
    require(data.drivers.any { it.id == request.driverId }) { "예약할 직원을 다시 선택해 주세요." }
    require(request.endAt > request.startAt) { "끝나는 시각이 시작 시각보다 늦어야 해요." }
    require(request.endAt - request.startAt >= MIN_RESERVATION_MINUTES * MINUTE) { "예약은 ${MIN_RESERVATION_MINUTES}분 이상이어야 해요." }
    require(request.endAt - request.startAt <= MAX_RESERVATION_HOURS * 60 * MINUTE) { "한 번에 최대 7일까지 예약할 수 있어요." }
    require(request.endAt > now) { "이미 지난 시간은 예약할 수 없어요." }
    require(request.startAt <= now + MAX_RESERVATION_AHEAD_DAYS * DAY) { "${MAX_RESERVATION_AHEAD_DAYS}일 안의 날짜만 예약할 수 있어요." }
    require(request.destination.length <= 60 && request.note.length <= 200) { "목적지는 60자, 메모는 200자까지예요." }
    reservationConflict(data.reservations, request.startAt, request.endAt, data.profile.demoMode, request.id)?.let { other ->
        val who = data.drivers.firstOrNull { it.id == other.driverId }?.label ?: "다른 직원"
        throw IllegalArgumentException("${reservationWindowText(other, zone)}에 $who 님 예약이 있어요. 다른 시간을 골라 주세요.")
    }
    // A trip already being driven blocks the slot only while it is still on the road.
    data.open?.takeIf { it.demo == data.profile.demoMode && request.startAt <= now }?.let {
        require(it.driverId == request.driverId) { "지금은 다른 직원이 운행 중이에요. 운행이 끝난 뒤로 예약해 주세요." }
    }
}

/** Adds a confirmed booking after validation, and drops bookings that ended or were cancelled long ago. */
internal fun reserveIn(data: TripLogData, request: Reservation, now: Long, zone: ZoneId = ZoneId.systemDefault()): TripLogData {
    validateReservationRequest(data, request, now, zone)
    require(data.reservations.none { it.id == request.id }) { "이미 접수된 예약이에요." }
    val kept = pruneReservations(data.reservations, now)
    require(kept.size < MAX_RESERVATIONS) { "예약이 ${MAX_RESERVATIONS}건에 도달했어요. 지난 예약을 정리해 주세요." }
    val booking = request.copy(status = ReservationStatus.CONFIRMED, createdAt = now, demo = data.profile.demoMode, cancelledAt = null)
    return data.copy(reservations = (kept + booking).sortedBy { it.startAt })
}

/** Cancels a booking; one that already ended cannot be cancelled, it is history. */
internal fun cancelReservationIn(data: TripLogData, id: String, now: Long): TripLogData {
    val booking = data.reservations.firstOrNull { it.id == id } ?: throw IllegalArgumentException("예약을 찾지 못했어요.")
    if (booking.status == ReservationStatus.CANCELLED) return data
    require(booking.endAt > now) { "이미 끝난 예약은 취소할 수 없어요." }
    return data.copy(reservations = data.reservations.map { if (it.id == id) it.copy(status = ReservationStatus.CANCELLED, cancelledAt = now) else it })
}

/** Restores a cancelled booking (undo) if its slot is still free and not over. */
internal fun restoreReservationIn(data: TripLogData, id: String, now: Long): TripLogData {
    val booking = data.reservations.firstOrNull { it.id == id } ?: throw IllegalArgumentException("예약을 찾지 못했어요.")
    if (booking.status == ReservationStatus.CONFIRMED) return data
    require(booking.endAt > now) { "이미 지난 예약은 되돌릴 수 없어요." }
    require(reservationConflict(data.reservations, booking.startAt, booking.endAt, booking.demo, booking.id) == null) { "그 사이 다른 예약이 들어왔어요." }
    return data.copy(reservations = data.reservations.map { if (it.id == id) it.copy(status = ReservationStatus.CONFIRMED, cancelledAt = null) else it })
}

internal fun pruneReservations(list: List<Reservation>, now: Long): List<Reservation> =
    list.filter { (it.cancelledAt ?: it.endAt) > now - RESERVATION_KEEP_DAYS * DAY }

/** The confirmed booking whose window (with the lead) covers [now] in the current data set. */
internal fun activeReservation(data: TripLogData, now: Long): Reservation? =
    data.reservations.filter { it.status == ReservationStatus.CONFIRMED && it.demo == data.profile.demoMode && it.startAt - RESERVATION_LEAD_MILLIS <= now && now < it.endAt }
        .minByOrNull { it.startAt }

/** Confirmed bookings not yet over, soonest first. */
internal fun upcomingReservations(data: TripLogData, now: Long): List<Reservation> =
    data.reservations.filter { it.status == ReservationStatus.CONFIRMED && it.demo == data.profile.demoMode && it.endAt > now }.sortedBy { it.startAt }

/** Confirmed bookings touching [date] in [zone], in order. */
internal fun reservationsOn(data: TripLogData, date: LocalDate, zone: ZoneId): List<Reservation> {
    val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return data.reservations.filter { it.status == ReservationStatus.CONFIRMED && it.demo == data.profile.demoMode && it.startAt < to && from < it.endAt }.sortedBy { it.startAt }
}

/** The booking a closed trip was driven under: same driver (or none recorded), and the trip started inside the window. */
internal fun reservationForTrip(data: TripLogData, trip: Trip): Reservation? =
    data.reservations.firstOrNull { it.status == ReservationStatus.CONFIRMED && it.demo == trip.demo &&
        it.startAt - RESERVATION_LEAD_MILLIS <= trip.startAt && trip.startAt < it.endAt && (trip.driverId == null || trip.driverId == it.driverId) }

/** What a phone shows for a vehicle at a glance. */
internal sealed interface VehicleAvailability {
    data class InUse(val driverLabel: String?, val since: Long) : VehicleAvailability
    data class Reserved(val reservation: Reservation, val driverLabel: String?) : VehicleAvailability
    data class Free(val next: Reservation?, val nextDriverLabel: String?) : VehicleAvailability
}

internal fun vehicleAvailability(data: TripLogData, now: Long): VehicleAvailability {
    fun label(id: String?) = data.drivers.firstOrNull { it.id == id }?.label
    data.open?.takeIf { it.demo == data.profile.demoMode }?.let { return VehicleAvailability.InUse(label(it.driverId), it.startAt) }
    data.reservations.firstOrNull { it.status == ReservationStatus.CONFIRMED && it.demo == data.profile.demoMode && it.startAt <= now && now < it.endAt }
        ?.let { return VehicleAvailability.Reserved(it, label(it.driverId)) }
    val next = upcomingReservations(data, now).firstOrNull { it.startAt > now }
    return VehicleAvailability.Free(next, label(next?.driverId))
}

internal fun availabilityText(availability: VehicleAvailability, now: Long, zone: ZoneId = ZoneId.systemDefault()): String = when (availability) {
    is VehicleAvailability.InUse -> "운행 중" + (availability.driverLabel?.let { " · $it" } ?: "")
    is VehicleAvailability.Reserved -> "예약 사용 중 · ${hmIn(availability.reservation.endAt, zone)}까지"
    is VehicleAvailability.Free -> availability.next?.let { "사용 가능 · 다음 예약 ${reservationStartText(it, now, zone)}" } ?: "사용 가능 · 예정된 예약 없음"
}

private fun hmIn(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }

/** "오늘 14:00", "내일 09:30", or "11월 20일 (금) 09:30". */
internal fun reservationStartText(reservation: Reservation, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val start = Instant.ofEpochMilli(reservation.startAt).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = when (start.toLocalDate()) { today -> "오늘"; today.plusDays(1) -> "내일"; else -> dayLabel(start.toLocalDate()) }
    return "$day ${hmIn(reservation.startAt, zone)}"
}

/** "11월 20일 (금) 09:00–12:00", or across days "11월 20일 (금) 09:00 – 11월 21일 (토) 18:00". */
internal fun reservationWindowText(reservation: Reservation, zone: ZoneId = ZoneId.systemDefault()): String {
    val start = Instant.ofEpochMilli(reservation.startAt).atZone(zone)
    val end = Instant.ofEpochMilli(reservation.endAt).atZone(zone)
    return if (start.toLocalDate() == end.toLocalDate() || (end.hour == 0 && end.minute == 0 && end.toLocalDate() == start.toLocalDate().plusDays(1)))
        "${dayLabel(start.toLocalDate())} ${hmIn(reservation.startAt, zone)}–${if (end.toLocalDate() == start.toLocalDate()) hmIn(reservation.endAt, zone) else "24:00"}"
    else "${dayLabel(start.toLocalDate())} ${hmIn(reservation.startAt, zone)} – ${dayLabel(end.toLocalDate())} ${hmIn(reservation.endAt, zone)}"
}
