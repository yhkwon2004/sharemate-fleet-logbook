package ai.sharemate.app

import java.math.BigDecimal
import java.math.RoundingMode

// Car-share tariff: prepaid rental and coverage, metered distance, and a late fee per started 10 minutes.
data class RentalRates(val rentalWon: Long, val insuranceWon: Long, val perKmWon: Long, val latePer10MinWon: Long)
data class RentalCost(val distanceKm: Double?, val fixedWon: Long, val distanceWon: Long?, val lateWon: Long, val totalWon: Long?)
data class CostForecast(val distanceKm: Double?, val remainingKm: Double?, val distanceWon: Long?, val lateWon: Long, val totalWon: Long?, val routeIncluded: Boolean, val arrivalAt: Long?)

internal fun validateRates(rates: RentalRates) {
    require(rates.rentalWon in 0..10_000_000) { "대여요금은 0~10,000,000원으로 입력해 주세요." }
    require(listOf(rates.insuranceWon, rates.perKmWon, rates.latePer10MinWon).all { it in 0..1_000_000 }) { "보험·주행·지연 요금은 0~1,000,000원으로 입력해 주세요." }
}

fun rentalDistance(session: RentalSession, snapshot: VehicleSnapshot, now: Long): Double? {
    val start = session.startingOdometerKm ?: return null
    val current = snapshot.odometerKm.takeIf { it.isFresh(now) }?.value?.toDouble() ?: return null
    return (current - start).takeIf { start.isFinite() && current.isFinite() && it >= 0.0 && it <= 100_000.0 }
}

internal fun lateFee(rates: RentalRates, returnAt: Long, at: Long): Long {
    if (at <= returnAt || rates.latePer10MinWon == 0L) return 0
    val over = Math.subtractExact(at, returnAt)
    return Math.multiplyExact(over / 600_000 + if (over % 600_000 > 0) 1 else 0, rates.latePer10MinWon)
}

private fun distanceFee(rates: RentalRates, km: Double?): Long? =
    if (rates.perKmWon == 0L) 0L else km?.let {
        BigDecimal.valueOf(it).multiply(BigDecimal.valueOf(rates.perKmWon)).setScale(0, RoundingMode.CEILING).longValueExact()
    }

fun rentalCost(session: RentalSession, snapshot: VehicleSnapshot, now: Long): RentalCost? {
    val rates = session.rates ?: return null
    validateRates(rates)
    val fixed = Math.addExact(rates.rentalWon, rates.insuranceWon)
    val km = rentalDistance(session, snapshot, now)
    val distanceWon = distanceFee(rates, km)
    val late = lateFee(rates, session.returnAt, now)
    return RentalCost(km, fixed, distanceWon, late, distanceWon?.let { Math.addExact(Math.addExact(fixed, it), late) })
}

// Expected total on arrival at the confirmed return destination. Without that route only what is known now counts.
fun costForecast(session: RentalSession, snapshot: VehicleSnapshot, route: RouteEstimate?, now: Long): CostForecast? {
    val actual = rentalCost(session, snapshot, now) ?: return null
    val rates = session.rates!!
    val meters = route?.remainingMeters
    if (route == null || returnAvailableMillis(session, route, now) == null || meters == null || meters !in 0..10_000_000)
        return CostForecast(actual.distanceKm, null, actual.distanceWon, actual.lateWon, actual.totalWon, false, null)
    val arrival = Math.addExact(now, route.remainingSeconds * 1_000)
    val remainingKm = meters / 1_000.0
    val km = actual.distanceKm?.plus(remainingKm)
    val distanceWon = distanceFee(rates, km)
    val late = lateFee(rates, session.returnAt, arrival)
    return CostForecast(km, remainingKm, distanceWon, late, distanceWon?.let { Math.addExact(Math.addExact(actual.fixedWon, it), late) }, true, arrival)
}
