package ai.sharemate.app

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/*
 * Fleet care: the dates and distances a company must not miss for a vehicle, the maintenance it paid for, and the
 * month-end close that turns a month of trips into a finished logbook. All pure; the repository wraps the mutations.
 */

enum class MaintenanceKind(val label: String) {
    OIL("엔진오일·소모품"), TIRE("타이어"), BRAKE("브레이크"), WASH("세차"), REPAIR("수리·정비"), INSPECTION("정기검사"), OTHER("기타")
}

/** Work done on the car. Card-matched records carry the charge id so a cancelled approval removes them again. */
data class MaintenanceRecord(
    val id: String,
    val at: Long,
    val kind: MaintenanceKind,
    val odometerKm: Double?,
    val amountWon: Long,
    val merchant: String? = null,
    val note: String = "",
    val source: EditSource = EditSource.DRIVER,
    val cardChargeId: String? = null,
    val demo: Boolean = false,
)

/** Dates are ISO yyyy-MM-dd so the file stays readable and free of time zones. */
data class VehicleCare(
    val inspectionDue: String? = null,
    val insuranceDue: String? = null,
    val leaseEnd: String? = null,
    val serviceIntervalKm: Int? = null,
    val lastServiceKm: Double? = null,
    val lastServiceAt: Long? = null,
)

enum class CareKind(val label: String) { INSPECTION("정기검사"), INSURANCE("보험 만기"), LEASE("리스·렌트 만기"), SERVICE("정비 주기") }
enum class CareLevel { INFO, SOON, OVERDUE }

data class CareAlert(val kind: CareKind, val level: CareLevel, val title: String, val detail: String, val daysLeft: Long? = null, val kmLeft: Double? = null)

internal const val CARE_SOON_DAYS = 30L
internal const val CARE_SOON_KM = 1_000.0
internal const val MAX_MAINTENANCE = 1_000

internal fun parseIsoDate(value: String?): LocalDate? = value?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
internal fun validIsoDate(value: String?) = value == null || value.isEmpty() || runCatching { LocalDate.parse(value); true }.getOrDefault(false)

/** Every configured deadline with how far away it is. Nothing here is estimated: a date the manager typed, or kilometres the odometer showed. */
internal fun careAlerts(care: VehicleCare, odometerKm: Double?, today: LocalDate): List<CareAlert> {
    val out = mutableListOf<CareAlert>()
    fun dated(kind: CareKind, raw: String?, verb: String) {
        val due = parseIsoDate(raw) ?: return
        val days = ChronoUnit.DAYS.between(today, due)
        val level = when { days < 0 -> CareLevel.OVERDUE; days <= CARE_SOON_DAYS -> CareLevel.SOON; else -> CareLevel.INFO }
        val title = when { days < 0 -> "${kind.label} ${-days}일 지남"; days == 0L -> "${kind.label} 오늘"; else -> "${kind.label} D-$days" }
        out += CareAlert(kind, level, title, "${due.monthValue}월 ${due.dayOfMonth}일 $verb", daysLeft = days)
    }
    dated(CareKind.INSPECTION, care.inspectionDue, "까지 검사를 받아야 해요")
    dated(CareKind.INSURANCE, care.insuranceDue, "만기 · 갱신 확인")
    dated(CareKind.LEASE, care.leaseEnd, "계약 종료 · 반납·연장 결정")
    val interval = care.serviceIntervalKm
    val last = care.lastServiceKm
    if (interval != null && interval > 0 && last != null && odometerKm != null) {
        val left = last + interval - odometerKm
        val level = when { left < 0 -> CareLevel.OVERDUE; left <= CARE_SOON_KM -> CareLevel.SOON; else -> CareLevel.INFO }
        val title = if (left < 0) "정비 주기 ${"%,.0f".format(-left)} km 지남" else "다음 정비까지 ${"%,.0f".format(left)} km"
        out += CareAlert(CareKind.SERVICE, level, title, "마지막 정비 ${"%,.0f".format(last)} km · ${"%,d".format(interval)} km마다", kmLeft = left)
    }
    return out.sortedWith(compareBy({ -it.level.ordinal }, { it.daysLeft ?: Long.MAX_VALUE }))
}

internal fun validateCare(care: VehicleCare) {
    require(validIsoDate(care.inspectionDue) && validIsoDate(care.insuranceDue) && validIsoDate(care.leaseEnd)) { "날짜는 2026-03-15처럼 입력해 주세요." }
    require(care.serviceIntervalKm == null || care.serviceIntervalKm in 500..100_000) { "정비 주기는 500~100,000 km로 입력해 주세요." }
    require(care.lastServiceKm == null || (care.lastServiceKm.isFinite() && care.lastServiceKm in 0.0..10_000_000.0)) { "마지막 정비 거리를 확인해 주세요." }
}

/** Guesses the kind of work from a merchant name; a card charge at a tyre shop becomes a tyre record without anyone typing. */
internal fun maintenanceKindFor(merchant: String): MaintenanceKind {
    val name = merchant.uppercase().replace(Regex("\\s+"), "")
    return when {
        listOf("타이어", "TIRE", "휠").any(name::contains) -> MaintenanceKind.TIRE
        listOf("세차", "워시", "WASH", "디테일링").any(name::contains) -> MaintenanceKind.WASH
        listOf("오일", "OIL", "필터", "와이퍼").any(name::contains) -> MaintenanceKind.OIL
        listOf("검사", "교통안전공단", "TS한국").any(name::contains) -> MaintenanceKind.INSPECTION
        listOf("브레이크", "패드").any(name::contains) -> MaintenanceKind.BRAKE
        else -> MaintenanceKind.REPAIR
    }
}

/** A month-end close: the snapshot the company signed off on. Trips can still be corrected, but the correction is marked. */
data class MonthClose(val month: String, val closedAt: Long, val trips: Int, val totalKm: Double, val businessKm: Double, val expenseWon: Long, val demo: Boolean = false)

data class MonthStatus(val month: YearMonth, val trips: Int, val unfinished: Int, val reviews: Int, val closed: MonthClose?) {
    val issues get() = unfinished + reviews
    val ready get() = trips > 0 && issues == 0 && closed == null
    val percent get() = if (trips == 0) null else ((trips - unfinished) * 100 / trips).coerceIn(0, 100)
}

internal fun monthKey(month: YearMonth) = month.toString()

internal fun monthStatus(data: TripLogData, month: YearMonth, zone: ZoneId): MonthStatus {
    val demo = data.profile.demoMode
    val trips = tripsIn(data.trips.filter { it.demo == demo }, month.atDay(1), month.atEndOfMonth(), zone)
    val unfinished = trips.count { it.purpose == TripPurpose.UNCLASSIFIED || it.driverId == null }
    val reviews = data.charges.count { it.demo == demo && it.state == ChargeState.REVIEW && Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate().let { d -> YearMonth.from(d) == month } }
    return MonthStatus(month, trips.size, unfinished, reviews, data.closedMonths.firstOrNull { it.month == monthKey(month) && it.demo == demo })
}

/** Closing needs every trip finished and every card question answered: the close is a statement that the month is complete. */
internal fun closeMonth(data: TripLogData, month: YearMonth, now: Long, zone: ZoneId): TripLogData {
    val status = monthStatus(data, month, zone)
    require(status.closed == null) { "이미 마감한 달이에요." }
    require(status.trips > 0) { "마감할 운행이 없어요." }
    require(status.issues == 0) { "목적 미분류·사용자 미지정 ${status.unfinished}건, 카드 확인 ${status.reviews}건을 먼저 끝내 주세요." }
    val trips = tripsIn(data.trips.filter { it.demo == data.profile.demoMode }, month.atDay(1), month.atEndOfMonth(), zone)
    val totals = logbookTotals(trips)
    val close = MonthClose(monthKey(month), now, totals.trips, totals.totalKm, totals.businessKm, totals.expenseWon, data.profile.demoMode)
    return data.copy(closedMonths = (data.closedMonths + close).takeLast(240))
}

internal fun reopenMonth(data: TripLogData, month: YearMonth): TripLogData =
    data.copy(closedMonths = data.closedMonths.filterNot { it.month == monthKey(month) && it.demo == data.profile.demoMode })

/** The closed month a trip belongs to, if any; edits to such a trip are allowed but marked in its history. */
internal fun closedMonthOf(data: TripLogData, trip: Trip, zone: ZoneId): MonthClose? {
    val key = monthKey(YearMonth.from(Instant.ofEpochMilli(trip.startAt).atZone(zone).toLocalDate()))
    return data.closedMonths.firstOrNull { it.month == key && it.demo == trip.demo }
}

internal fun parseMonth(value: String): YearMonth = try { YearMonth.parse(value) } catch (e: DateTimeParseException) { throw IllegalArgumentException("월 형식이 올바르지 않아요.") }
