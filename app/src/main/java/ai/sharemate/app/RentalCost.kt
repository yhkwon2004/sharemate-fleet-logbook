package ai.sharemate.app

import java.math.BigDecimal
import java.math.RoundingMode

data class RentalRates(val baseWon: Long, val perMinuteWon: Long, val perKmWon: Long)
data class RentalCost(val elapsedMinutes: Long, val distanceKm: Double?, val timeWon: Long, val distanceWon: Long?, val totalWon: Long?)

internal fun validateRates(rates: RentalRates) {
    require(listOf(rates.baseWon, rates.perMinuteWon, rates.perKmWon).all { it in 0..1_000_000 }) { "요금은 0~1,000,000원으로 입력해 주세요." }
}

fun rentalDistance(session: RentalSession, snapshot: VehicleSnapshot, now: Long): Double? {
    val start = session.startingOdometerKm ?: return null
    val current = snapshot.odometerKm.takeIf { it.isFresh(now) }?.value?.toDouble() ?: return null
    return (current - start).takeIf { start.isFinite() && current.isFinite() && it >= 0.0 && it <= 100_000.0 }
}

fun rentalCost(session: RentalSession, snapshot: VehicleSnapshot, now: Long): RentalCost? {
    val rates = session.rates ?: return null
    validateRates(rates)
    if (session.startedAt <= 0 || now < session.startedAt) return null
    val elapsed = Math.subtractExact(now, session.startedAt)
    val minutes = elapsed / 60_000 + if (elapsed % 60_000 > 0) 1 else 0
    val timeWon = Math.addExact(rates.baseWon, Math.multiplyExact(minutes, rates.perMinuteWon))
    val km = rentalDistance(session, snapshot, now)
    val distanceWon = if (rates.perKmWon == 0L) 0L else km?.let {
        BigDecimal.valueOf(it).multiply(BigDecimal.valueOf(rates.perKmWon)).setScale(0, RoundingMode.CEILING).longValueExact()
    }
    return RentalCost(minutes, km, timeWon, distanceWon, distanceWon?.let { Math.addExact(timeWon, it) })
}
