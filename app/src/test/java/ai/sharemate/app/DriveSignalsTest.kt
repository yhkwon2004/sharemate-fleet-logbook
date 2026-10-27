package ai.sharemate.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSignalsTest {
    @Test fun knownGearAndOdometerStayFreshThroughInclusiveAgeBoundary() {
        assertTrue(driveSignalsFresh(GearState.PARK, 0.0, 0, 0))
        assertTrue(driveSignalsFresh(GearState.MOVING, 10_000_000.0, 45_000, 45_000))
        assertFalse(driveSignalsFresh(GearState.MOVING, 100.0, 45_001, 0))
        assertFalse(driveSignalsFresh(GearState.MOVING, 100.0, 0, 45_001))
        assertFalse(driveSignalsFresh(GearState.MOVING, 100.0, -1, 0))
        assertFalse(driveSignalsFresh(GearState.MOVING, 100.0, 0, -1))
    }

    @Test fun missingOrInvalidVehicleSignalsCannotBeTreatedAsFresh() {
        assertFalse(driveSignalsFresh(GearState.UNKNOWN, 100.0, 0, 0))
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 10_000_000.1).forEach { km ->
            assertFalse("Invalid odometer: $km", driveSignalsFresh(GearState.PARK, km, 0, 0))
        }
    }
}
