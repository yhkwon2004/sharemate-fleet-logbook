package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test

class RentalRemainingFractionTest {
    @Test fun remainingTimeTracksStartMidpointAndOverdue() {
        assertEquals(1f, rentalRemainingFraction(100, 200, 100)!!, 0f)
        assertEquals(.5f, rentalRemainingFraction(100, 200, 150)!!, 0f)
        assertEquals(0f, rentalRemainingFraction(100, 200, 200)!!, 0f)
        assertEquals(0f, rentalRemainingFraction(100, 200, Long.MAX_VALUE)!!, 0f)
    }

    @Test fun missingOrInvalidClockNeverLooksLikeValidProgress() {
        assertNull(rentalRemainingFraction(0, 200, 150))
        assertNull(rentalRemainingFraction(200, 100, 250))
        assertNull(rentalRemainingFraction(100, 100, 100))
        assertNull(rentalRemainingFraction(100, 200, 99))
        assertEquals(1f, rentalRemainingFraction(1, Long.MAX_VALUE, 1)!!, 0f)
    }
}
