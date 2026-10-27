package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test

class TelemetryFreshnessTest {
    @Test fun bothVehicleAndServerReceiptsMustBeCurrent() {
        assertTrue(currentTelemetry(70_000, 80_000, 100_000, 100_000, false))
        assertFalse(currentTelemetry(69_999, 100_000, 100_000, 100_000, false))
        assertFalse(currentTelemetry(100_000, 69_999, 100_000, 100_000, false))
        assertFalse(currentTelemetry(100_000, 100_000, 100_000, 100_000, true))
    }

    @Test fun missingFutureAndClockRollbackAreNotCurrent() {
        assertFalse(currentTelemetry(0, 1, 1, 1, false))
        assertFalse(currentTelemetry(1, 0, 1, 1, false))
        assertFalse(currentTelemetry(101, 100, 100, 100, false))
        assertFalse(currentTelemetry(100, 101, 100, 100, false))
        assertFalse(currentTelemetry(Long.MAX_VALUE, 100, 100, 100, false))
        assertFalse(currentTelemetry(100, 100, 100, Long.MIN_VALUE, false))
    }
}
