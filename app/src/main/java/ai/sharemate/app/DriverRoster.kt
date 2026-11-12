package ai.sharemate.app

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/*
 * Driver management (2.11), the pure part. The people are 직원 and the role is 운전 (운전자); ‘④ 사용자’ stays the name of
 * the logbook column. One avatar stands for a person everywhere. The picker and 설정 › 직원 list everyone in one stable order
 * (Korean collation by name, then department), so the person driving never jumps to the top, and each row says what is next
 * for that person: a booking in use or due, else the last drive. A booking makes its booker the driver once; a pick made
 * after that sticks.
 */

/** The most 직원 a car keeps; the repository refuses the next one and 설정 › 직원 says why before it gets there. */
internal const val MAX_DRIVERS = 50

/** The picker offers a search field from this many 직원 up; below it every card is in reach without typing. */
internal const val PICKER_SEARCH_FROM = 9

/** How far ahead a row names a booking ("다음 예약 · 내일 09:00"); one further out leaves the line to the last drive. */
private const val NEXT_BOOKING_HORIZON_MS = 24 * 60 * 60_000L

/**
 * The avatar's letters. An all-Hangul name (spaces removed) gives its last two syllables, the given name in practice: 김서연 →
 * 서연, 남궁민수 → 민수, 이준 → 이준. Any other name gives the initials of its first two words in capitals: Kim Jiwoo → KJ. A
 * blank name gives a dash.
 */
internal fun driverMonogram(name: String): String {
    val compact = name.filterNot { it.isWhitespace() }
    if (compact.isEmpty()) return "–"
    if (compact.all { it in '가'..'힣' }) return compact.takeLast(2)
    return name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
}

/** How the car addresses a person: by name, or by the label (부서 · 성명) when no name was given. */
internal fun driverName(driver: Driver): String = driver.name.trim().ifBlank { driver.label }

/** The once-a-day question names the person, not the department: "오늘도 Kim Jiwoo 님이 운전하나요?". */
internal fun driverQuestion(driver: Driver): String = "오늘도 ${driverName(driver)} 님이 운전하나요?"

/**
 * When [driverId] last finished a drive in the data set on screen: the end of their latest closed trip, or null. A trip
 * still recording does not count, nor does the other data set (demo or real).
 */
internal fun lastDriveAt(data: TripLogData, driverId: String): Long? =
    data.trips.filter { it.driverId == driverId && it.demo == data.profile.demoMode && !it.open }.maxOfOrNull { it.endAt ?: it.updatedAt }

/** "마지막 운행 오늘 08:47", "마지막 운행 어제 18:47", "마지막 운행 11월 3일", "마지막 운행 2025년 12월 30일", or "운행 기록 없음". */
internal fun lastDriveLabel(lastAt: Long?, now: Long, zone: ZoneId): String {
    if (lastAt == null) return "운행 기록 없음"
    val time = Instant.ofEpochMilli(lastAt).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = "%02d:%02d".format(time.hour, time.minute)
    return "마지막 운행 " + when (time.toLocalDate()) {
        today -> "오늘 $clock"
        today.minusDays(1) -> "어제 $clock"
        else -> driveDate(time.toLocalDate(), today)
    }
}

/** The 지금 운전 card's status line: "오늘 확인함 · 마지막 운행 어제 18:47" once today's question is answered, else the last drive alone. */
internal fun driverStatusLine(lastAt: Long?, now: Long, zone: ZoneId, answeredToday: Boolean): String =
    (if (answeredToday) "오늘 확인함 · " else "") + lastDriveLabel(lastAt, now, zone)

/** What TalkBack reads for the 지금 운전 card, in one stop: "지금 운전, Kim Jiwoo, Sales, 오늘 확인함, 마지막 운행 어제 18:47". */
internal fun driverNowDescription(driver: Driver, status: String): String =
    listOf("지금 운전", driverName(driver), driver.department.trim(), status.replace(" · ", ", ")).filter { it.isNotBlank() }.joinToString(", ")

/** A person's record in 설정 › 직원, by date rather than time (a list read at a desk): "운행 12건 · 마지막 운행 11월 8일", or "운행 기록 없음". */
internal fun driverHistoryLine(trips: Int, lastAt: Long?, now: Long, zone: ZoneId): String {
    if (trips == 0 || lastAt == null) return "운행 기록 없음"
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return "운행 ${trips}건 · 마지막 운행 ${driveDate(Instant.ofEpochMilli(lastAt).atZone(zone).toLocalDate(), today)}"
}

/**
 * A person's one line in 설정 › 직원 (2.12), in the list and under the editor's name: the department, then the record
 * ([driverHistoryLine]), "Sales · 운행 12건 · 마지막 운행 11월 8일" or "영업1팀 · 운행 기록 없음"; a blank department is left
 * out. Data, so a long one may ellipsise on the row; TalkBack reads it whole.
 */
internal fun driverListLine(driver: Driver, trips: Int, lastAt: Long?, now: Long, zone: ZoneId): String =
    listOf(driver.department.trim(), driverHistoryLine(trips, lastAt, now, zone)).filter { it.isNotBlank() }.joinToString(" · ")

private fun driveDate(date: LocalDate, today: LocalDate): String =
    if (date.year == today.year) "${date.monthValue}월 ${date.dayOfMonth}일" else "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일"

/**
 * One person as the picker and 설정 › 직원 list them: whether they are the one chosen (driving now, or the trip's driver),
 * their closed trips in the data set on screen, when they last drove, and the status line ([driverRows]).
 */
@Immutable
internal data class DriverRowInfo(val driver: Driver, val selected: Boolean, val trips: Int, val lastAt: Long?, val status: String)

/**
 * Everyone, in Korean collation by name and then department, whoever is [selectedId], so a pick never reorders the list.
 * The status says what is next for the person, first match wins: "예약 사용 중 · 17:00까지" inside their booking, "곧 예약 ·
 * 14:00부터" in the 15 minutes before it (when the car already switches to them), "다음 예약 · 내일 09:00" for one starting
 * within 24 hours, else the last drive ([lastDriveLabel]). Bookings and trips come from the data set on screen. A [query]
 * keeps the people whose name or department contains it, ignoring case and spaces.
 */
internal fun driverRows(data: TripLogData, selectedId: String?, now: Long, zone: ZoneId, query: String = ""): List<DriverRowInfo> {
    val collator = Collator.getInstance(Locale.KOREAN)
    val needle = looseKey(query)
    val closed = data.trips.filter { it.demo == data.profile.demoMode && !it.open && it.driverId != null }
    val trips = closed.groupingBy { it.driverId }.eachCount()
    val last = closed.groupBy { it.driverId }.mapValues { (_, list) -> list.maxOf { it.endAt ?: it.updatedAt } }
    val bookings = data.reservations.filter { it.status == ReservationStatus.CONFIRMED && it.demo == data.profile.demoMode && it.endAt > now }
    return data.drivers
        .filter { needle.isEmpty() || needle in looseKey(it.name) || needle in looseKey(it.department) }
        .sortedWith(compareBy<Driver, String>(collator) { it.name.trim() }.thenBy(collator) { it.department.trim() })
        .map { driver ->
            val lastAt = last[driver.id]
            val status = driverBookingStatus(bookings.filter { it.driverId == driver.id }, now, zone) ?: lastDriveLabel(lastAt, now, zone)
            DriverRowInfo(driver, driver.id == selectedId, trips[driver.id] ?: 0, lastAt, status)
        }
}

/** The booking part of a row's status ([driverRows]); null when none of [bookings] (one person's, not over) is in use or near. */
private fun driverBookingStatus(bookings: List<Reservation>, now: Long, zone: ZoneId): String? {
    bookings.filter { it.startAt <= now }.minByOrNull { it.startAt }?.let { return "예약 사용 중 · ${carClockText(it.endAt, now, zone)}까지" }
    val next = bookings.filter { it.startAt > now }.minByOrNull { it.startAt } ?: return null
    return when {
        next.startAt - now <= RESERVATION_LEAD_MILLIS -> "곧 예약 · ${carClockText(next.startAt, now, zone)}부터"
        next.startAt - now <= NEXT_BOOKING_HORIZON_MS -> "다음 예약 · ${reservationStartText(next, now, zone)}"
        else -> null
    }
}

/** Text compared loosely: spaces dropped, case folded, so "kim  jiwoo" finds Kim Jiwoo and "김 서연" finds 김서연. */
private fun looseKey(text: String): String = text.filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)

/** What TalkBack reads for a picker card: "Kim Jiwoo, Sales, 마지막 운행 어제 18:47". */
internal fun driverRowDescription(row: DriverRowInfo): String =
    listOf(driverName(row.driver), row.driver.department.trim(), row.status).filter { it.isNotBlank() }.joinToString(", ")

/**
 * The picker's card columns: two when its content (664 dp inside the 720-dp dialog) is at least 600 dp wide at the font in
 * effect, so a 1.3× font gets one card per row and its longer lines; a font below 1× counts as 1×, as for the window classes.
 */
internal fun pickerColumns(contentWidth: Dp, fontScale: Float): Int = if (contentWidth / fontScale.coerceAtLeast(1f) >= 600.dp) 2 else 1

/**
 * The width of the 사용자 card's 바꾸기 (2.12, pure, unit-tested). [space] is what the name and the button share beside the
 * avatar (the card's content less the 56-dp avatar, its 12-dp gap and the button's 16; 268 dp at 1×), [nameWidth] the name in
 * titleLarge Bold and [labelWidth] the label in labelLarge. The button keeps the mock's 151 dp while the name leaves it room
 * ("Kim Jiwoo" measures 111), then gives way down to 112 dp before the name ellipsises; it never drops under its label plus
 * its 24-dp padding a side, which a larger font can make the width on its own.
 */
internal fun changeButtonWidth(space: Dp, nameWidth: Dp, labelWidth: Dp): Dp =
    (space - nameWidth).coerceIn(max(112.dp, labelWidth + 48.dp), max(151.dp, labelWidth + 48.dp))

/**
 * The picker's one line under its title (2.13, pure, unit-tested), or null: nothing with nobody registered ([noDrivers], the
 * empty state says it all); for a trip, the trip itself ([tripLine], from [pickerTripLine]), or nothing when the trip is not
 * found ([forTrip] without a line); for the person driving now, where the pick lands, the recording trip too while one
 * [recording]. One line each without a final period, the car's one-line rule (2.12 wrote them as sentences).
 */
internal fun pickerLead(noDrivers: Boolean, tripLine: String?, forTrip: Boolean, recording: Boolean): String? = when {
    noDrivers -> null
    tripLine != null -> tripLine
    forTrip -> null
    recording -> OverlayCopy.PICKER_RECORDING
    else -> OverlayCopy.PICKER_NEXT
}

/** The trip the picker sets the driver of: "11월 9일 (월) 08:12–08:47 · 12.4 km", or "08:12 출발 · 기록 중" while it records. */
internal fun pickerTripLine(trip: Trip, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(trip.startAt).atZone(zone)
    val from = "%02d:%02d".format(start.hour, start.minute)
    val endAt = trip.endAt ?: return "$from 출발 · 기록 중"
    val end = Instant.ofEpochMilli(endAt).atZone(zone)
    return "${dayLabel(start.toLocalDate())} $from–${"%02d:%02d".format(end.hour, end.minute)} · ${kmText(trip.distanceKm)}"
}

/**
 * True when [id] can be deleted: no trip of either data set carries them, recorded or recording, because the logbook's ④
 * column must stay filled. Pass the unfiltered data (the repository's), the same the repository checks before it removes.
 */
internal fun driverRemovable(all: TripLogData, id: String): Boolean = all.trips.none { it.driverId == id } && all.open?.driverId != id

/** True when someone other than [editingId] has the same department and name, ignoring case and spaces. A warning, never a block. */
internal fun driverDuplicate(drivers: List<Driver>, department: String, name: String, editingId: String?): Boolean {
    val who = looseKey(name)
    if (who.isEmpty()) return false
    val team = looseKey(department)
    return drivers.any { it.id != editingId && looseKey(it.name) == who && looseKey(it.department) == team }
}

/**
 * The follow-up after a pick: a change of driver now or of a trip's driver (both undoable), a trip that had no driver, or
 * the first person chosen to drive. Null when [picked] is [previous]: the picker just closes.
 */
internal fun driverPickMessage(picked: Driver, previous: Driver?, forTrip: Boolean): String? {
    if (picked.id == previous?.id) return null
    val name = driverName(picked)
    return when {
        forTrip && previous != null -> "이 운행의 운전자를 $name 님으로 바꿨어요"
        forTrip -> "이 운행에 $name 님을 지정했어요"
        previous != null -> "운전자를 $name 님으로 바꿨어요"
        else -> "지금 운전하는 직원을 $name 님으로 정했어요"
    }
}

/** The list after undoing a delete: [driver] back at [index] (clamped, so a list that shrank meanwhile takes them at its end); unchanged when they are already in it. */
internal fun restoredDrivers(drivers: List<Driver>, driver: Driver, index: Int): List<Driver> =
    if (drivers.any { it.id == driver.id }) drivers else drivers.toMutableList().apply { add(index.coerceIn(0, size), driver) }

/**
 * The booking that makes its booker the driver now, or null. Only while parked, only a registered booker who is not driving
 * already, and only once per booking: [appliedBookingId] is the booking the car last saw while parked, so a pick made during
 * that booking sticks, and the next booking switches again.
 */
internal fun bookingDriverSwitch(data: TripLogData, now: Long, appliedBookingId: String?): Reservation? {
    if (data.open != null) return null
    val booking = activeReservation(data, now) ?: return null
    return booking.takeIf { it.id != appliedBookingId && it.driverId != data.currentDriverId && data.drivers.any { d -> d.id == it.driverId } }
}
