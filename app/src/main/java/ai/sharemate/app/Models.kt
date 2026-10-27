package ai.sharemate.app

enum class Purpose(val label: String) { COMMUTE("출퇴근"), TRAVEL("여행"), REST("휴식"), WORK("업무") }
enum class Phase { CHECK_IN, ACTIVE, RETURN }
enum class SourceMode { PLEOS, DEMO }
enum class DataStatus { AVAILABLE, UNSUPPORTED, DENIED, ERROR, LOADING }
enum class DemoScenario { FAMILY_CHARGING, PET_RETURN, COMMUTE, REST_WORK, UNAVAILABLE }

data class Reading<T>(
    val value: T? = null,
    val status: DataStatus = DataStatus.UNSUPPORTED,
    val updatedAt: Long = 0,
) {
    fun isFresh(now: Long): Boolean = status == DataStatus.AVAILABLE && value != null && fresh(updatedAt, now)
}

data class VehicleSnapshot(
    val soc: Reading<Int> = Reading(),
    val charging: Reading<Boolean> = Reading(),
    val chargeRemainingSeconds: Reading<Int> = Reading(),
    val doors: Reading<String> = Reading(),
    val windows: Reading<String> = Reading(),
    val tires: Reading<String> = Reading(),
    val warnings: Reading<String> = Reading(),
    val hvac: Reading<String> = Reading(),
    val belts: Reading<String> = Reading(),
    val carName: Reading<String> = Reading(),
    val doorLocks: Reading<String> = Reading(),
    val parked: Reading<Boolean> = Reading(),
    val rangeKm: Reading<Float> = Reading(),
    val odometerKm: Reading<Float> = Reading(),
)

data class RouteEstimate(
    val destinationId: String,
    val destinationName: String,
    val remainingSeconds: Long,
    val receivedAt: Long,
)

data class RentalSession(
    val id: String,
    val returnAt: Long,
    val returnPlace: String,
    val purpose: Purpose,
    val family: Boolean = false,
    val pet: Boolean = false,
    val targetSoc: Int = 80,
    val timerEnd: Long? = null,
    val destinationNote: String = "",
    val parkingNote: String = "",
    val checks: Set<String> = emptySet(),
    val boundDestinationId: String? = null,
    val phase: Phase = Phase.CHECK_IN,
    val startedAt: Long = 0,
    val startingOdometerKm: Double? = null,
    val odometerSource: SourceMode = SourceMode.PLEOS,
    val rates: RentalRates? = null,
)

data class AppState(
    val session: RentalSession? = null,
    val pendingSession: RentalSession? = null,
    val restoring: Boolean = true,
    val restoreError: Boolean = false,
    val sourceMode: SourceMode = SourceMode.PLEOS,
    val snapshot: VehicleSnapshot = VehicleSnapshot(),
    val route: RouteEstimate? = null,
    val error: String? = null,
    val now: Long = System.currentTimeMillis(),
)

val pickupChecks = setOf("pickup_exterior", "pickup_supplies", "pickup_guide")
val returnChecks = setOf("return_belongings", "return_clean", "return_exterior", "return_operator")

fun remainingMillis(end: Long, now: Long): Long = subtract(end, now).coerceAtLeast(0)

internal fun rentalRemainingFraction(start: Long, end: Long, now: Long): Float? {
    if (start <= 0 || end <= start || now < start) return null
    return (remainingMillis(end, now).toDouble() / (end - start).toDouble()).coerceIn(0.0, 1.0).toFloat()
}

fun returnAvailableMillis(session: RentalSession, route: RouteEstimate?, now: Long): Long? {
    if (route == null || route.destinationId.isBlank() || session.boundDestinationId != route.destinationId ||
        !fresh(route.receivedAt, now) || route.remainingSeconds < 0 || route.remainingSeconds > Long.MAX_VALUE / 1_000
    ) return null
    return subtract(subtract(subtract(session.returnAt, now), route.remainingSeconds * 1_000), 15 * 60 * 1_000L)
}

fun returnDepartureAt(session: RentalSession, route: RouteEstimate?, now: Long): Long? =
    returnAvailableMillis(session, route, now)?.let { remaining ->
        runCatching { Math.addExact(now, remaining) }.getOrNull()?.takeIf { it in 1..253402300799999L }
    }

fun chargeCompletionAt(snapshot: VehicleSnapshot, now: Long): Long? {
    val remaining = snapshot.chargeRemainingSeconds
    if (!snapshot.charging.isFresh(now) || snapshot.charging.value != true || !remaining.isFresh(now) || remaining.value!! < 0) return null
    return runCatching { Math.addExact(remaining.updatedAt, remaining.value.toLong() * 1_000) }
        .getOrNull()?.takeIf { it in 1..253402300799999L }
}

internal fun bindReturnDestination(session: RentalSession, route: RouteEstimate?, expectedDestinationId: String, now: Long): RentalSession {
    requireNotNull(route) { "현재 경로 정보가 없습니다." }
    require(route.destinationId == expectedDestinationId) { "내비 목적지가 바뀌었습니다. 다시 확인해 주세요." }
    val bound = session.copy(boundDestinationId = route.destinationId)
    require(returnAvailableMillis(bound, route, now) != null) { "최신 경로 정보가 없어 목적지를 연결할 수 없습니다." }
    return bound
}

internal fun applySessionEdit(current: RentalSession, edit: (RentalSession) -> RentalSession): RentalSession {
    val requested = edit(current)
    require(current.id == requested.id && current.phase == requested.phase) { "현재 이용 기록을 다시 확인해 주세요." }
    val returnPlace = requested.returnPlace.trim()
    return requested.copy(
        returnPlace = returnPlace,
        checks = current.checks,
        boundDestinationId = if (returnPlace == current.returnPlace) current.boundDestinationId else null,
    ).also(::validateSession)
}

internal fun validateSession(session: RentalSession) {
    require(session.startedAt in 0..253402300799999L)
    require(session.startingOdometerKm == null || (session.startingOdometerKm.isFinite() && session.startingOdometerKm in 0.0..100_000_000.0))
    session.rates?.let(::validateRates)
    require(runCatching { java.util.UUID.fromString(session.id).toString() == session.id }.getOrDefault(false)) { "이용 기록 식별자가 올바르지 않습니다." }
    require(session.returnAt in 1..253402300799999L) { "반납 시각을 확인해 주세요." }
    require(session.returnPlace.isNotBlank() && session.returnPlace.length <= 200) { "반납 장소는 1~200자로 입력해 주세요." }
    require(session.targetSoc in 0..100) { "목표 충전량은 0~100%로 설정해 주세요." }
    require(session.timerEnd == null || session.timerEnd in 1..253402300799999L) { "타이머 시각을 확인해 주세요." }
    require(session.destinationNote.length <= 1_000 && session.parkingNote.length <= 1_000) { "메모는 1,000자 이하로 입력해 주세요." }
    require(session.checks.size <= 100 && session.checks.all { it.isNotBlank() && it.length <= 80 }) { "확인 항목이 올바르지 않습니다." }
    require(session.boundDestinationId == null || (session.boundDestinationId.isNotBlank() && session.boundDestinationId.length <= 500)) { "목적지 식별자가 올바르지 않습니다." }
}

private fun fresh(at: Long, now: Long): Boolean = at > 0 && now >= at && now - at <= 30_000

internal fun currentTelemetry(sentAt: Long, receivedAt: Long, serverNow: Long, now: Long, closed: Boolean): Boolean =
    !closed && fresh(sentAt, now) && fresh(receivedAt, serverNow)

private fun subtract(left: Long, right: Long): Long = try {
    Math.subtractExact(left, right)
} catch (_: ArithmeticException) {
    if (left >= 0 && right < 0) Long.MAX_VALUE else Long.MIN_VALUE
}
