package ai.sharemate.app

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// Purposes follow the NTS logbook columns: ⑧ commute and ⑨ general business are business use; personal is not.
enum class TripPurpose(val label: String) {
    UNCLASSIFIED("미분류"), COMMUTE("출·퇴근"), BUSINESS("일반 업무"), PERSONAL("개인");
    val business get() = this == COMMUTE || this == BUSINESS
}
enum class EditSource(val label: String) { DRIVER("직접 선택"), VOICE("Gleo 음성"), SUGGESTED("이전 기록 제안"), PHONE("휴대폰") }

enum class IncidentKind(val label: String) { ACCIDENT("사고"), DAMAGE("파손·긁힘"), BREAKDOWN("고장·경고"), OTHER("특이사항") }
enum class IncidentStatus(val label: String) { OPEN("대응 중"), CLOSED("처리 완료") }

/** A field report captured from the vehicle at the moment it happened: time, odometer, place, trip and driver come from the car, not memory. */
data class Incident(
    val id: String,
    val at: Long,
    val kind: IncidentKind,
    val odometerKm: Double?,
    val address: String?,
    val tripId: String?,
    val driverId: String?,
    val note: String = "",
    val status: IncidentStatus = IncidentStatus.OPEN,
    val updatedAt: Long = at,
    val demo: Boolean = false,
)

/** A usable VIN: the emulator answers a quoted "invalid", and a real one is 17 characters without quotes. */
internal fun validVin(value: String?): String? = value?.trim()?.trim('"')?.take(40)?.takeIf { it.length >= 11 && !it.equals("invalid", ignoreCase = true) }

/** Identity read from the vehicle itself so the logbook header and incident reports carry the real car, not typed text. */
data class VehicleCard(
    val model: String? = null,
    val vin: String? = null,
    val odometerKm: Double? = null,
    val socPercent: Int? = null,
    val capturedAt: Long = 0,
)

/** A charging stop detected from the vehicle; the amount is the one thing the driver still has to enter. */
data class ChargeSession(val startAt: Long, val endAt: Long, val socFrom: Int?, val socTo: Int?, val odometerKm: Double?, val demo: Boolean = false)
enum class ExpenseType(val label: String) { FUEL("주유·충전"), TOLL("통행료"), PARKING("주차"), OTHER("기타") }
enum class GearState { PARK, MOVING, UNKNOWN }
enum class TripOrigin { AUTO, GAP_FILL }

data class Driver(val id: String, val department: String, val name: String) {
    val label get() = listOf(department, name).filter { it.isNotBlank() }.joinToString(" · ")
}
data class TripExpense(val type: ExpenseType, val amountWon: Long, val at: Long, val source: EditSource)

data class Trip(
    val id: String,
    val startAt: Long,
    val endAt: Long? = null,
    val startOdometerKm: Double,
    // While recording this holds the latest odometer reading.
    val endOdometerKm: Double,
    val startAddress: String? = null,
    val endAddress: String? = null,
    val destination: String? = null,
    val driverId: String? = null,
    val purpose: TripPurpose = TripPurpose.UNCLASSIFIED,
    val purposeSource: EditSource? = null,
    val note: String = "",
    val expenses: List<TripExpense> = emptyList(),
    val origin: TripOrigin = TripOrigin.AUTO,
    val recovered: Boolean = false,
    val demo: Boolean = false,
    val history: List<String> = emptyList(),
    val updatedAt: Long = startAt,
) {
    val open get() = endAt == null
    val distanceKm get() = (endOdometerKm - startOdometerKm).coerceAtLeast(0.0)
    val durationMillis get() = ((endAt ?: updatedAt) - startAt).coerceAtLeast(0)
    val expenseWon get() = expenses.sumOf { it.amountWon }
}

data class DriveSample(
    val at: Long,
    val gear: GearState,
    val odometerKm: Double?,
    val address: String? = null,
    val destination: String? = null,
    val demo: Boolean = false,
    val charging: Boolean? = null,
    val socPercent: Int? = null,
    val vin: String? = null,
)

data class FleetProfile(
    val companyName: String = "",
    val businessNumber: String = "",
    val carModel: String = "",
    val plateNumber: String = "",
    val annualCostWon: Long? = null,
    val reimbursePerKmWon: Long = 0,
    val storeAddresses: Boolean = true,
    val demoMode: Boolean = false,
    // Car-hosted live sync for the phone app; off until the fleet manager turns it on in the vehicle.
    val phoneSync: Boolean = false,
)

internal const val MIN_CHARGE_MILLIS = 60_000L

/** Closes a charging stop when the plug signal drops after at least a minute; shorter blips are ignored. */
internal fun chargeSessionEnded(startAt: Long?, socFrom: Int?, sample: DriveSample, wasCharging: Boolean): ChargeSession? {
    if (!wasCharging || sample.charging != false || startAt == null) return null
    if (sample.at - startAt < MIN_CHARGE_MILLIS) return null
    return ChargeSession(startAt, sample.at, socFrom, sample.socPercent, sample.odometerKm, sample.demo)
}

/** Incident steps shown the moment a report is opened; vehicle data is already captured so the driver only follows these. */
internal fun incidentSteps(kind: IncidentKind): List<String> = when (kind) {
    IncidentKind.ACCIDENT -> listOf("비상등을 켜고 안전한 곳으로 이동한 뒤 차 밖의 안전한 장소로 피하세요.", "부상자가 있으면 119, 사고 신고는 112.",
        "휴대폰 SHAREMATE에서 현장·차량·상대 차량 번호판을 촬영하세요.", "회사 차량 담당자와 보험사에 알리세요. 시각·계기판·위치는 이미 기록됐어요.")
    IncidentKind.DAMAGE -> listOf("손상 부위를 휴대폰으로 가까이·멀리 각각 촬영하세요.", "주차 중 발견이면 주변 CCTV·블랙박스 보관을 요청하세요.", "회사 차량 담당자에게 공유하세요.")
    IncidentKind.BREAKDOWN -> listOf("안전한 곳에 정차하고 계기판 경고를 확인하세요.", "주행이 어려우면 보험사 긴급출동을 부르세요.", "경고 표시를 휴대폰으로 촬영해 두세요.")
    IncidentKind.OTHER -> listOf("상황을 메모로 남기세요.", "필요하면 휴대폰으로 사진을 추가하세요.", "회사 차량 담당자에게 공유하세요.")
}

sealed interface TripChange {
    data class Started(val trip: Trip) : TripChange
    data class Progress(val trip: Trip) : TripChange
    data class Finished(val trip: Trip) : TripChange
    data class Discarded(val trip: Trip) : TripChange
}

internal const val MIN_TRIP_KM = 0.1
internal const val MIN_TRIP_MILLIS = 180_000L
// A recording trip without any sample for this long was interrupted (app stopped or vehicle power cut).
internal const val STALE_TRIP_MILLIS = 600_000L

private fun validOdometer(value: Double?) = value?.takeIf { it.isFinite() && it in 0.0..10_000_000.0 }

/**
 * Advances the gear-and-odometer state machine by one vehicle sample.
 * Leaving P with a known odometer starts a trip; returning to P finishes it.
 */
internal fun advanceTrip(open: Trip?, sample: DriveSample, driverId: String?, newId: () -> String): List<TripChange> {
    if (sample.gear == GearState.UNKNOWN || (open != null && sample.at < open.updatedAt)) return emptyList()
    val odometer = validOdometer(sample.odometerKm) ?: return emptyList()
    if (open != null && (open.demo != sample.demo || sample.at - open.updatedAt > STALE_TRIP_MILLIS)) {
        val closed = TripChange.Finished(open.copy(endAt = open.updatedAt, recovered = true))
        return listOf(closed) + advanceTrip(null, sample, driverId, newId)
    }
    if (open == null) {
        if (sample.gear != GearState.MOVING) return emptyList()
        return listOf(TripChange.Started(Trip(newId(), sample.at, startOdometerKm = odometer, endOdometerKm = odometer,
            startAddress = sample.address, destination = sample.destination, driverId = driverId, demo = sample.demo, updatedAt = sample.at)))
    }
    // The odometer only grows; a lower reading is a stale or faulty value and is ignored.
    val latest = if (odometer >= open.endOdometerKm) odometer else open.endOdometerKm
    val updated = open.copy(endOdometerKm = latest, endAddress = if (sample.gear == GearState.PARK) sample.address else sample.address ?: open.endAddress,
        destination = sample.destination ?: open.destination, updatedAt = maxOf(open.updatedAt, sample.at))
    if (sample.gear != GearState.PARK) return listOf(TripChange.Progress(updated))
    val finished = updated.copy(endAt = maxOf(open.startAt, sample.at))
    return listOf(if (finished.distanceKm < MIN_TRIP_KM && finished.durationMillis < MIN_TRIP_MILLIS) TripChange.Discarded(finished)
        else TripChange.Finished(finished))
}

enum class IssueType(val label: String, val detail: String) {
    UNCLASSIFIED("목적 미분류", "출·퇴근, 일반 업무, 개인 중 하나를 선택해 주세요."),
    NO_DRIVER("사용자 미지정", "운행기록부 ④사용자 칸에 들어갈 부서·성명을 지정해 주세요."),
    ZERO_DISTANCE("0 km 운행", "주행거리가 없는 기록입니다. 시동만 켠 기록이면 삭제할 수 있어요."),
    ODOMETER_GAP("기록되지 않은 주행", "앞 운행의 도착 거리와 다음 운행의 출발 거리가 이어지지 않아요."),
    ODOMETER_OVERLAP("누적거리 겹침", "다음 운행의 출발 거리가 앞 운행의 도착 거리보다 작아요."),
    IMPLAUSIBLE_SPEED("비정상 평균 속도", "평균 속도가 시속 200 km를 넘어요. 시각이나 거리를 확인해 주세요."),
    RECOVERED("종료 시각 추정", "운행 중 앱이 중단되어 마지막 수신 시각으로 종료했어요."),
}
data class TripIssue(val type: IssueType, val tripId: String, val gapKm: Double? = null, val previousTripId: String? = null)

internal const val ODOMETER_TOLERANCE_KM = 0.5

/** Integrity checks over the odometer chain; the vehicle odometer makes unrecorded driving visible. */
internal fun auditTrips(trips: List<Trip>): List<TripIssue> {
    val issues = mutableListOf<TripIssue>()
    val closed = trips.filter { !it.open }.sortedWith(compareBy({ it.startAt }, { it.startOdometerKm }))
    closed.forEach { trip ->
        if (trip.purpose == TripPurpose.UNCLASSIFIED) issues += TripIssue(IssueType.UNCLASSIFIED, trip.id)
        if (trip.driverId == null) issues += TripIssue(IssueType.NO_DRIVER, trip.id)
        if (trip.distanceKm < MIN_TRIP_KM) issues += TripIssue(IssueType.ZERO_DISTANCE, trip.id)
        if (trip.recovered) issues += TripIssue(IssueType.RECOVERED, trip.id)
        val hours = trip.durationMillis / 3_600_000.0
        if (hours > 0 && trip.distanceKm / hours > 200) issues += TripIssue(IssueType.IMPLAUSIBLE_SPEED, trip.id)
    }
    // Demo trips run on their own odometer and are checked separately from vehicle data.
    closed.groupBy { it.demo }.values.forEach { chain ->
        chain.zipWithNext { previous, next ->
            val gap = next.startOdometerKm - previous.endOdometerKm
            if (gap > ODOMETER_TOLERANCE_KM) issues += TripIssue(IssueType.ODOMETER_GAP, next.id, gap, previous.id)
            else if (gap < -ODOMETER_TOLERANCE_KM) issues += TripIssue(IssueType.ODOMETER_OVERLAP, next.id, -gap, previous.id)
        }
    }
    return issues
}

/** Builds the missing trip between two recorded trips from the odometer chain; times are the known bounds. */
internal fun gapTrip(previous: Trip, next: Trip, id: String, now: Long): Trip = Trip(
    id = id, startAt = previous.endAt ?: previous.updatedAt, endAt = next.startAt,
    startOdometerKm = previous.endOdometerKm, endOdometerKm = next.startOdometerKm,
    startAddress = previous.endAddress, endAddress = next.startAddress, driverId = next.driverId ?: previous.driverId,
    origin = TripOrigin.GAP_FILL, demo = previous.demo, history = listOf("${historyStamp(now)} 누적거리 연속성으로 누락 구간 추가"), updatedAt = now,
)

/** Other closed, unclassified trips on the same day and data set as [classified], oldest first; offered for bulk classification. */
internal fun bulkClassifyCandidates(trips: List<Trip>, classified: Trip, zone: ZoneId): List<Trip> {
    val day = Instant.ofEpochMilli(classified.startAt).atZone(zone).toLocalDate()
    return trips.filter {
        it.id != classified.id && !it.open && it.demo == classified.demo && it.purpose == TripPurpose.UNCLASSIFIED &&
            Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() == day
    }.sortedBy { it.startAt }
}

internal fun suggestPurpose(trip: Trip, history: List<Trip>): Trip? {
    val key = trip.destination?.takeIf { it.isNotBlank() } ?: trip.endAddress?.takeIf { it.isNotBlank() } ?: return null
    return history.filter { it.id != trip.id && !it.open && it.purpose != TripPurpose.UNCLASSIFIED && it.purposeSource != EditSource.SUGGESTED &&
        ((it.destination ?: it.endAddress) == key) }.maxByOrNull { it.startAt }
}

data class LogbookRow(
    val date: LocalDate, val department: String, val name: String,
    val beforeKm: Double, val afterKm: Double, val distanceKm: Double,
    val commuteKm: Double, val businessKm: Double, val note: String,
)
data class LogbookTotals(
    val totalKm: Double, val commuteKm: Double, val generalKm: Double, val personalKm: Double, val unclassifiedKm: Double,
    val trips: Int, val expenseWon: Long,
) {
    val businessKm get() = commuteKm + generalKm
    val ratio: Double? get() = if (totalKm > 0) (businessKm / totalKm).coerceIn(0.0, 1.0) else null
}

private fun round1(value: Double) = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toDouble()

internal fun tripsIn(trips: List<Trip>, from: LocalDate, to: LocalDate, zone: ZoneId): List<Trip> = trips.filter { trip ->
    !trip.open && Instant.ofEpochMilli(trip.startAt).atZone(zone).toLocalDate().let { it >= from && it <= to }
}.sortedWith(compareBy({ it.startAt }, { it.startOdometerKm }))

internal fun logbookTotals(trips: List<Trip>): LogbookTotals {
    fun km(purpose: TripPurpose) = round1(trips.filter { it.purpose == purpose }.sumOf { round1(it.distanceKm) })
    return LogbookTotals(round1(trips.sumOf { round1(it.distanceKm) }), km(TripPurpose.COMMUTE), km(TripPurpose.BUSINESS),
        km(TripPurpose.PERSONAL), km(TripPurpose.UNCLASSIFIED), trips.size, trips.sumOf { it.expenseWon })
}

internal fun logbookRows(trips: List<Trip>, drivers: List<Driver>, zone: ZoneId): List<LogbookRow> = trips.map { trip ->
    val driver = drivers.firstOrNull { it.id == trip.driverId }
    val distance = round1(trip.distanceKm)
    val note = buildList {
        when (trip.purpose) {
            TripPurpose.PERSONAL -> add("개인 사용")
            TripPurpose.UNCLASSIFIED -> add("목적 미분류")
            else -> {}
        }
        (trip.destination ?: trip.endAddress)?.takeIf { it.isNotBlank() }?.let { add(it) }
        trip.expenses.groupBy { it.type }.forEach { (type, items) -> add("${type.label} ${formatWon(items.sumOf { it.amountWon })}") }
        if (trip.note.isNotBlank()) add(trip.note)
        if (trip.origin == TripOrigin.GAP_FILL) add("누락 구간 보완")
        if (trip.recovered) add("종료 시각 추정")
        if (trip.demo) add("시연 데이터")
    }.joinToString(" · ")
    LogbookRow(Instant.ofEpochMilli(trip.startAt).atZone(zone).toLocalDate(), driver?.department.orEmpty(), driver?.name.orEmpty(),
        round1(trip.startOdometerKm), round1(trip.endOdometerKm), distance,
        if (trip.purpose == TripPurpose.COMMUTE) distance else 0.0, if (trip.purpose == TripPurpose.BUSINESS) distance else 0.0, note)
}

internal val BOM = Char(0xFEFF).toString()
internal fun formatWon(amount: Long) = "%,d원".format(Locale.KOREA, amount)
private fun km1(value: Double) = String.format(Locale.US, "%.1f", value)
private fun csv(value: String): String {
    // Quoting alone does not stop spreadsheet programs from evaluating user-entered formulas.
    val text = if (value != "-" && (value.trimStart().firstOrNull() in setOf('=', '+', '-', '@') || value.firstOrNull() in setOf('\t', '\r', '\n'))) "'" + value else value
    return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
}
internal fun weekday(date: LocalDate) = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)

/** CSV laid out like the NTS 업무용승용차 운행기록부 form (국세청 고시 제2025-8호); opens directly in Excel. */
internal fun logbookCsv(profile: FleetProfile, from: LocalDate, to: LocalDate, rows: List<LogbookRow>, totals: LogbookTotals): String {
    val lines = mutableListOf<List<String>>()
    lines += listOf("업무용승용차 운행기록부")
    lines += listOf("사업연도", "$from ~ $to", "법인명", profile.companyName, "사업자등록번호", profile.businessNumber)
    lines += listOf("①차종", profile.carModel, "②자동차등록번호", profile.plateNumber)
    lines += listOf("③사용일자(요일)", "④사용자 부서", "④사용자 성명", "⑤주행 전 계기판의 거리(km)", "⑥주행 후 계기판의 거리(km)",
        "⑦주행거리(km)", "⑧출·퇴근용(km)", "⑨일반 업무용(km)", "⑩비고")
    rows.forEach { row ->
        lines += listOf("${row.date}(${weekday(row.date)})", row.department, row.name, km1(row.beforeKm), km1(row.afterKm),
            km1(row.distanceKm), km1(row.commuteKm), km1(row.businessKm), row.note)
    }
    lines += listOf("⑪사업연도 총주행 거리(km)", km1(totals.totalKm), "⑫사업연도 업무용 사용거리(km)", km1(totals.businessKm),
        "⑬업무사용비율(⑫/⑪)", totals.ratio?.let { String.format(Locale.US, "%.1f%%", it * 100) } ?: "-")
    // UTF-8 byte-order mark so Excel opens the Korean text correctly.
    return BOM + lines.joinToString("\r\n") { line -> line.joinToString(",") { csv(it) } } + "\r\n"
}

// Without a logbook the business-use ratio is deemed 100% only up to 15,000,000 won per vehicle per year.
internal const val NO_LOGBOOK_CAP_WON = 15_000_000L

data class TaxEffect(val annualCostWon: Long, val ratio: Double, val withLogWon: Long, val withoutLogWon: Long) {
    val differenceWon get() = withLogWon - withoutLogWon
}

internal fun taxEffect(annualCostWon: Long, ratio: Double): TaxEffect {
    require(annualCostWon in 0..10_000_000_000L && ratio.isFinite() && ratio in 0.0..1.0)
    val withLog = BigDecimal.valueOf(annualCostWon).multiply(BigDecimal.valueOf(ratio)).setScale(0, RoundingMode.FLOOR).longValueExact()
    return TaxEffect(annualCostWon, ratio, withLog, minOf(annualCostWon, NO_LOGBOOK_CAP_WON))
}

data class Reimbursement(val driverId: String?, val businessKm: Double, val amountWon: Long)

/** Fuel reimbursement from business kilometres only; personal and unclassified trips are excluded. */
internal fun reimbursements(trips: List<Trip>, perKmWon: Long): List<Reimbursement> {
    require(perKmWon in 0..100_000)
    return trips.filter { it.purpose.business }.groupBy { it.driverId }.map { (driverId, list) ->
        val km = round1(list.sumOf { round1(it.distanceKm) })
        Reimbursement(driverId, km, BigDecimal.valueOf(km).multiply(BigDecimal.valueOf(perKmWon)).setScale(0, RoundingMode.HALF_UP).longValueExact())
    }.sortedByDescending { it.businessKm }
}

data class EmployeeUsage(val driver: Driver?, val totals: LogbookTotals, val fuelWon: Long, val reimbursementWon: Long)

/** Registered expenses and company-rate estimates stay separate to avoid paying fuel twice. */
internal fun employeeUsage(trips: List<Trip>, drivers: List<Driver>, perKmWon: Long): List<EmployeeUsage> {
    require(perKmWon in 0..100_000)
    val knownDrivers = drivers.associateBy { it.id }
    return trips.filterNot { it.open }.groupBy { it.driverId?.takeIf(knownDrivers::containsKey) }.map { (id, records) ->
        EmployeeUsage(knownDrivers[id], logbookTotals(records),
            records.sumOf { trip -> trip.expenses.filter { it.type == ExpenseType.FUEL }.sumOf { it.amountWon } },
            if (id == null) 0 else reimbursements(records, perKmWon).sumOf { it.amountWon })
    }.sortedByDescending { it.totals.totalKm }
}

internal fun historyStamp(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(at).atZone(zone).let { "%d/%d %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }

internal fun withHistory(trip: Trip, entry: String, now: Long): Trip =
    trip.copy(history = (trip.history + "${historyStamp(now)} $entry").takeLast(30), updatedAt = maxOf(trip.updatedAt, now))
