package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test

class RentalCostTest {
    private val session = RentalSession("00000000-0000-0000-0000-000000000001", 100_000_000, "반납", Purpose.TRAVEL,
        startedAt = 1_000, startingOdometerKm = 100.0, rates = RentalRates(5_000, 100, 200))
    private fun snapshot(km: Float = 101.25f, at: Long = 61_000) = VehicleSnapshot(odometerKm = Reading(km, DataStatus.AVAILABLE, at))
    @Test fun exactMinuteAndFractionalDistance() {
        val cost = rentalCost(session, snapshot(), 61_000)!!
        assertEquals(1L, cost.elapsedMinutes); assertEquals(5_350L, cost.totalWon)
        assertEquals(5_450L, rentalCost(session, snapshot(), 61_001)!!.totalWon)
    }
    @Test fun staleMissingOrRollbackNeverBecomesZeroDistanceCharge() {
        assertNull(rentalCost(session, snapshot(at = 1), 61_000)!!.totalWon)
        assertNull(rentalCost(session, snapshot(km = 99f), 61_000)!!.totalWon)
        assertNull(rentalCost(session.copy(startingOdometerKm = null), snapshot(), 61_000)!!.totalWon)
        assertNull(rentalCost(session.copy(startedAt = 0), snapshot(), 61_000))
        assertNull(rentalCost(session, snapshot(), 999))
    }
    @Test fun midnightAndZeroDistanceTariff() {
        val s = session.copy(startedAt = 86_340_000, rates = RentalRates(0, 100, 0))
        assertEquals(200L, rentalCost(s, VehicleSnapshot(), 86_460_000)!!.totalWon)
    }
    @Test fun pinnedHttpsOnly() {
        val token = "a".repeat(64)
        assertEquals("https://localhost:8765", parseLink("https://localhost:8765|$token|$token").endpoint)
        listOf("http://localhost", "https://user@localhost", "https://localhost/path", "https://localhost/?key=x").forEach {
            assertThrows(IllegalArgumentException::class.java) { parseLink("$it|$token|$token") }
        }
    }
}
