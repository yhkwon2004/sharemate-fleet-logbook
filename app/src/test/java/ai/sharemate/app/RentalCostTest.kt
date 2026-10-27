package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test

class RentalCostTest {
    private val rates = RentalRates(rentalWon = 30_000, insuranceWon = 5_000, perKmWon = 200, latePer10MinWon = 1_000)
    private val session = RentalSession("00000000-0000-0000-0000-000000000001", 10_000_000, "반납", Purpose.TRAVEL,
        startedAt = 1_000, startingOdometerKm = 100.0, rates = rates, boundDestinationId = "dest")
    private fun snapshot(km: Float = 101.25f, at: Long = 61_000) = VehicleSnapshot(odometerKm = Reading(km, DataStatus.AVAILABLE, at))
    private fun route(meters: Long? = 12_300, receivedAt: Long = 61_000, id: String = "dest") = RouteEstimate(id, "반납", 1_800, receivedAt, meters)

    @Test fun prepaidFeesPlusMeteredDistanceRoundedUp() {
        val cost = rentalCost(session, snapshot(), 61_000)!!
        assertEquals(35_000L, cost.fixedWon); assertEquals(250L, cost.distanceWon); assertEquals(0L, cost.lateWon); assertEquals(35_250L, cost.totalWon)
        assertEquals(35_417L, rentalCost(session.copy(rates = rates.copy(perKmWon = 333)), snapshot(), 61_000)!!.totalWon)
    }

    @Test fun lateFeeChargesEachStartedTenMinutes() {
        assertEquals(0L, lateFee(rates, 1_000_000, 1_000_000))
        assertEquals(1_000L, lateFee(rates, 1_000_000, 1_000_001))
        assertEquals(1_000L, lateFee(rates, 1_000_000, 1_600_000))
        assertEquals(2_000L, lateFee(rates, 1_000_000, 1_600_001))
        assertEquals(0L, lateFee(rates.copy(latePer10MinWon = 0), 1_000_000, 9_000_000))
        val now = session.returnAt + 600_001
        assertEquals(37_250L, rentalCost(session, snapshot(at = now), now)!!.totalWon)
    }

    @Test fun staleMissingOrRollbackNeverBecomesZeroDistanceCharge() {
        assertNull(rentalCost(session, snapshot(at = 1), 61_000)!!.totalWon)
        assertNull(rentalCost(session, snapshot(km = 99f), 61_000)!!.totalWon)
        assertNull(rentalCost(session.copy(startingOdometerKm = null), snapshot(), 61_000)!!.totalWon)
        assertNull(rentalCost(session.copy(rates = null), snapshot(), 61_000))
        assertEquals(35_000L, rentalCost(session.copy(rates = rates.copy(perKmWon = 0), startingOdometerKm = null), VehicleSnapshot(), 61_000)!!.totalWon)
    }

    @Test fun forecastAddsConfirmedReturnRouteDistanceAndLateArrival() {
        val onTime = costForecast(session, snapshot(), route(), 61_000)!!
        assertTrue(onTime.routeIncluded); assertEquals(1_861_000L, onTime.arrivalAt)
        assertEquals(13.55, onTime.distanceKm!!, 1e-9); assertEquals(37_710L, onTime.totalWon)
        // Arrival 861 seconds after the scheduled return starts two late units.
        val late = costForecast(session.copy(returnAt = 1_000_000), snapshot(), route(), 61_000)!!
        assertEquals(2_000L, late.lateWon); assertEquals(39_710L, late.totalWon)
    }

    @Test fun forecastWithoutConfirmedRouteKeepsKnownCostOnly() {
        listOf(route(id = "other"), route(receivedAt = 1), route(meters = null), null).forEach {
            val forecast = costForecast(session, snapshot(), it, 61_000)!!
            assertFalse(forecast.routeIncluded); assertNull(forecast.arrivalAt); assertEquals(35_250L, forecast.totalWon)
        }
        assertNull(costForecast(session.copy(rates = null), snapshot(), route(), 61_000))
    }

    @Test fun pinnedHttpsOnly() {
        val token = "a".repeat(64)
        assertEquals("https://localhost:8765", parseLink("https://localhost:8765|$token|$token").endpoint)
        listOf("http://localhost", "https://user@localhost", "https://localhost/path", "https://localhost/?key=x").forEach {
            assertThrows(IllegalArgumentException::class.java) { parseLink("$it|$token|$token") }
        }
    }
}
