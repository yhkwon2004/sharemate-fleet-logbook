package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private val now = 1_800_000_000_000L
    private val session = RentalSession("00000000-0000-0000-0000-000000000001", now + 60 * 60 * 1_000, "반납 허브", Purpose.COMMUTE, boundDestinationId = "return-hub")
    private val route = RouteEstimate("return-hub", "반납 허브", 20 * 60, now)

    @Test fun returnTimeRequiresFreshConfirmedMatchingDestination() {
        assertEquals(25 * 60 * 1_000L, returnAvailableMillis(session, route, now))
        assertNull(returnAvailableMillis(session.copy(boundDestinationId = null), route, now))
        assertNull(returnAvailableMillis(session, route.copy(destinationId = "elsewhere"), now))
        assertNull(returnAvailableMillis(session, route.copy(receivedAt = now - 30_001), now))
        assertNotNull(returnAvailableMillis(session, route.copy(receivedAt = now - 30_000), now))
        assertNull(returnAvailableMillis(session, route.copy(receivedAt = now + 1), now))
        assertNull(returnAvailableMillis(session, null, now))
    }

    @Test fun overdueBufferRemainsNegativeAndInvalidDurationsAreRejected() {
        assertEquals(-5 * 60 * 1_000L, returnAvailableMillis(session.copy(returnAt = now + 30 * 60 * 1_000), route, now))
        assertNull(returnAvailableMillis(session, route.copy(remainingSeconds = -1), now))
        assertNull(returnAvailableMillis(session, route.copy(remainingSeconds = Long.MAX_VALUE), now))
    }

    @Test fun returnDepartureKeepsItsDateAcrossMidnightAndRequiresConfirmedFreshRoute() {
        val beforeMidnight = java.time.Instant.parse("2026-10-24T14:50:00Z").toEpochMilli()
        val afterMidnight = java.time.Instant.parse("2026-10-24T15:10:00Z").toEpochMilli()
        val nextDayReturn = session.copy(returnAt = java.time.Instant.parse("2026-10-24T15:50:00Z").toEpochMilli())
        val latest = route.copy(remainingSeconds = 25 * 60, receivedAt = beforeMidnight)
        assertEquals(afterMidnight, returnDepartureAt(nextDayReturn, latest, beforeMidnight))
        assertEquals(afterMidnight, returnDepartureAt(nextDayReturn, latest, beforeMidnight + 10_000))
        assertNull(returnDepartureAt(nextDayReturn.copy(boundDestinationId = null), latest, beforeMidnight))
        assertNull(returnDepartureAt(nextDayReturn, latest, beforeMidnight + 30_001))
        assertNull(returnDepartureAt(nextDayReturn, null, beforeMidnight))
    }

    @Test fun chargeEstimateRequiresFreshChargingAndNonnegativeVehicleDuration() {
        val charging = VehicleSnapshot(charging = Reading(true, DataStatus.AVAILABLE, now),
            chargeRemainingSeconds = Reading(2_100, DataStatus.AVAILABLE, now))
        assertEquals(now + 2_100_000, chargeCompletionAt(charging, now))
        assertEquals(now + 2_100_000, chargeCompletionAt(charging, now + 20_000))
        assertNull(chargeCompletionAt(charging, now + 30_001))
        assertNull(chargeCompletionAt(charging.copy(charging = Reading(false, DataStatus.AVAILABLE, now)), now))
        assertNull(chargeCompletionAt(charging.copy(charging = Reading(status = DataStatus.DENIED)), now))
        assertNull(chargeCompletionAt(charging.copy(chargeRemainingSeconds = Reading(status = DataStatus.UNSUPPORTED)), now))
        assertNull(chargeCompletionAt(charging.copy(chargeRemainingSeconds = Reading(-1, DataStatus.AVAILABLE, now)), now))
        assertNull(chargeCompletionAt(charging.copy(chargeRemainingSeconds = Reading(60, DataStatus.AVAILABLE, now - 30_001)), now))
        assertEquals(now, chargeCompletionAt(charging.copy(chargeRemainingSeconds = Reading(0, DataStatus.AVAILABLE, now)), now))
    }

    @Test fun countdownDoesNotGoNegativeOrOverflow() {
        assertEquals(0L, remainingMillis(now - 1, now))
        assertEquals(12_000L, remainingMillis(now + 12_000, now))
        assertEquals(Long.MAX_VALUE, remainingMillis(Long.MAX_VALUE, -1))
        assertEquals(0L, remainingMillis(Long.MIN_VALUE, 1))
    }

    @Test fun readingsNeverTreatMissingStaleOrFutureDataAsFresh() {
        val reading = Reading(80, DataStatus.AVAILABLE, now)
        assertTrue(reading.isFresh(now + 30_000))
        assertFalse(reading.isFresh(now + 30_001))
        assertFalse(reading.isFresh(now - 1))
        assertFalse(reading.copy(status = DataStatus.DENIED).isFresh(now))
        assertFalse(Reading<Int>(status = DataStatus.AVAILABLE, updatedAt = now).isFresh(now))
        assertFalse(Reading(80, DataStatus.AVAILABLE, 0).isFresh(now))
    }

    @Test fun storedAndEditedSessionsValidateAtTheBoundary() {
        validateSession(session)
        listOf(
            session.copy(id = "invalid"), session.copy(returnAt = -1), session.copy(returnPlace = " "),
            session.copy(targetSoc = 101), session.copy(targetSoc = -1), session.copy(timerEnd = 0),
            session.copy(destinationNote = "x".repeat(1_001)), session.copy(parkingNote = "x".repeat(1_001)),
            session.copy(checks = setOf("")), session.copy(boundDestinationId = ""),
        ).forEach { invalid -> assertTrue(runCatching { validateSession(invalid) }.isFailure) }
    }

    @Test fun changingReturnPlaceRequiresNewDestinationConfirmation() {
        val changed = applySessionEdit(session) { it.copy(returnPlace = "다른 반납 허브") }
        assertNull(changed.boundDestinationId)
        assertNull(returnAvailableMillis(changed, route, now))
        assertEquals(session.boundDestinationId, applySessionEdit(session) { it.copy(returnPlace = " 반납 허브 ") }.boundDestinationId)
    }

    @Test fun queuedEditsUseLatestSessionAndKeepIndependentFields() {
        val edits: List<(RentalSession) -> RentalSession> = listOf(
            { it.copy(destinationNote = "목적지 메모") },
            { it.copy(parkingNote = "B2 18번") },
            { it.copy(targetSoc = 90) },
        )
        val changed = edits.fold(session) { latest, edit -> applySessionEdit(latest, edit) }
        assertEquals("목적지 메모", changed.destinationNote)
        assertEquals("B2 18번", changed.parkingNote)
        assertEquals(90, changed.targetSoc)
    }

    @Test fun genericEditsCannotOverwriteWorkflowOrRouteConfirmation() {
        val checked = session.copy(checks = setOf("pickup_exterior"))
        val changed = applySessionEdit(checked) { it.copy(checks = emptySet(), boundDestinationId = null) }
        assertEquals(checked.checks, changed.checks)
        assertEquals(checked.boundDestinationId, changed.boundDestinationId)
        assertTrue(runCatching { applySessionEdit(session) { it.copy(phase = Phase.RETURN) } }.isFailure)
        assertTrue(runCatching { applySessionEdit(session) { it.copy(id = "00000000-0000-0000-0000-000000000002") } }.isFailure)
    }

    @Test fun destinationConfirmationCannotBindARouteChangedWhileWaitingToSave() {
        val unbound = session.copy(boundDestinationId = null)
        assertEquals(route.destinationId, bindReturnDestination(unbound, route, route.destinationId, now).boundDestinationId)
        assertTrue(runCatching { bindReturnDestination(unbound, route.copy(destinationId = "changed"), route.destinationId, now) }.isFailure)
        assertTrue(runCatching { bindReturnDestination(unbound, route.copy(receivedAt = now - 30_001), route.destinationId, now) }.isFailure)
        assertTrue(runCatching { bindReturnDestination(unbound, null, route.destinationId, now) }.isFailure)
        assertEquals(route.destinationId, bindReturnDestination(unbound, route.copy(remainingSeconds = 600), route.destinationId, now).boundDestinationId)
    }
}
