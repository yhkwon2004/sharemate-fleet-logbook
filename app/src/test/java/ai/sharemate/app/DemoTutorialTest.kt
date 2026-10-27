package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test

class DemoTutorialTest {
    @Test fun `park without current address does not report earlier moving location as endpoint`() {
        val trip = Trip("trip", 1000, startOdometerKm = 100.0, endOdometerKm = 100.4, endAddress = "이전 위치", updatedAt = 2000)
        val result = advanceTrip(trip, DriveSample(3000, GearState.PARK, 101.0), null) { error("No new trip") }.single() as TripChange.Finished
        assertNull(result.trip.endAddress)
        assertEquals(1.0, result.trip.distanceKm, 0.0)
    }
    @Test fun `walkthrough uses real recording logic and never mixes actual records`() {
        val at = 1_792_800_000_000L
        for (employee in 0..1) for (step in 0..5) {
            val data = tutorialData(step, employee, TripPurpose.BUSINESS, at)
            assertTrue(data.profile.demoMode)
            assertEquals(data.drivers[employee].id, data.currentDriverId)
            assertEquals(step == 1, data.open != null)
            if (step >= 2) {
                val trip = data.trips.single()
                assertTrue(trip.demo)
                assertFalse(trip.open)
                assertEquals(12.4, trip.distanceKm, .0001)
                assertEquals(if (step >= 4) 55_000L else 0L, trip.expenseWon)
                assertEquals(if (step >= 3) TripPurpose.BUSINESS else TripPurpose.UNCLASSIFIED, trip.purpose)
            }
            assertEquals(data, TripLogCodec.decode(TripLogCodec.encode(data)))
        }
        val personal = tutorialData(5, 1, TripPurpose.PERSONAL, at)
        assertEquals(0.0, logbookTotals(personal.trips).businessKm, 0.0)
        assertTrue(reimbursements(personal.trips, 200).isEmpty())
    }
}
