package ai.sharemate.app

import ai.pleos.playground.navi.data.DestinationInfo
import org.junit.Assert.*
import org.junit.Test

class VehicleSourcesTest {
    @Test fun continuousChargeTimeRejectsOldFutureAndMissingVhalTimestamps() {
        val now = 100_000_000_000L
        assertTrue(freshChargeTimestamp(now, now))
        assertTrue(freshChargeTimestamp(now - 30_000_000_000, now))
        assertFalse(freshChargeTimestamp(now - 30_000_000_001, now))
        assertFalse(freshChargeTimestamp(0, now))
        assertFalse(freshChargeTimestamp(now + 1, now))
    }

    @Test fun closedPositionIsZeroIncludingVentWindows() {
        assertEquals("닫힘", positionLabel(0, -5, 100))
        assertEquals("열림", positionLabel(-5, -5, 100))
        assertEquals("열림", positionLabel(100, 0, 100))
        assertTrue(runCatching { positionLabel(101, 0, 100) }.isFailure)
        assertTrue(runCatching { positionLabel(5, 5, 100) }.isFailure)
    }

    @Test fun missingAndInvalidSocNeverBecomeZeroOrAvailable() {
        assertEquals(DataStatus.ERROR, socReading(null).status)
        assertEquals(DataStatus.ERROR, socReading(-1).status)
        assertEquals(DataStatus.ERROR, socReading(101).status)
        assertEquals(0, socReading(0).value)
        assertEquals(DataStatus.AVAILABLE, socReading(100).status)
        assertEquals(DataStatus.DENIED, failureStatus(SecurityException()))
        assertEquals(DataStatus.UNSUPPORTED, failureStatus(UnsupportedOperationException()))
        assertEquals(DataStatus.ERROR, failureStatus(IllegalStateException()))
    }

    @Test fun invalidOrChangedRoutesCannotReuseTheBoundIdentity() {
        val destination = DestinationInfo(37.5, 127.0, name = "반납 장소", address = "", remainDistance = 10_000,
            remainTime = 1_200, tollFare = 0, poiId = "return-hub", poiSubId = null, poiProvider = "test")
        val first = destinationEstimate(destination, 0, 1_000)!!
        assertEquals(1_200L, first.remainingSeconds)
        assertNotEquals(first.destinationId, destinationEstimate(destination, 1, 1_000)!!.destinationId)
        assertNotEquals(first.destinationId, destinationEstimate(destination.copy(latitude = 37.6), 0, 1_000)!!.destinationId)
        assertNull(destinationEstimate(destination.copy(remainTime = -1), 0, 1_000))
        assertNull(destinationEstimate(destination.copy(latitude = Double.NaN), 0, 1_000))
        assertNull(destinationEstimate(destination.copy(name = " "), 0, 1_000))
    }
}
