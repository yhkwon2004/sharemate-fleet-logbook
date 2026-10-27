package ai.sharemate.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
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

    @Test fun navigationAddressUsesReceptionAgeAndNeverRevivesMissingOrStaleData() {
        assertEquals("본사", freshNavigationAddress(" 본사 ", 0))
        assertEquals("거래처", freshNavigationAddress("거래처", 45_000))
        assertNull(freshNavigationAddress("이전 위치", 45_001))
        assertNull(freshNavigationAddress("미래 수신", -1))
        assertNull(freshNavigationAddress(null, 0))
        assertNull(freshNavigationAddress("  ", 0))
        assertNull(freshNavigationAddress("기어 전환으로 지운 위치", Long.MAX_VALUE))
        assertEquals(120, freshNavigationAddress("가".repeat(121), 0)!!.length)
    }
}
