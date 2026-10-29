package ai.sharemate.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncProtocolTest {
    private val tripId = "6f1c9c2e-0d8a-4b4e-9f1e-0a3c2b1d4e5f"
    private val otherId = "0b2d4f6a-8c1e-4a3b-9d7f-1e2c3b4a5d6e"

    @Test fun everyCommandSurvivesARoundTrip() {
        val commands = listOf(
            SyncCommand.Classify(tripId, TripPurpose.BUSINESS),
            SyncCommand.Note(tripId, "거래처 방문"),
            SyncCommand.AddExpense(tripId, ExpenseType.TOLL, 3_600),
            SyncCommand.RemoveExpense(tripId, 2),
            SyncCommand.SetTripDriver(tripId, otherId),
            SyncCommand.ResolveCharge(42_000),
            SyncCommand.ResolveCharge(null),
            SyncCommand.ReportIncident(IncidentKind.ACCIDENT, ""),
            SyncCommand.UpdateIncident(otherId, "상대 차량 12가 3456", IncidentStatus.CLOSED, null),
            SyncCommand.UpdateIncident(otherId, null, null, IncidentKind.DAMAGE),
        )
        commands.forEach { command -> assertEquals(command, parseSyncCommand(JSONObject(encodeSyncCommand(command).toString()))) }
    }

    @Test fun malformedCommandsAreRejectedBeforeTheyReachTheLog() {
        val bad = listOf(
            """{"type":"explode"}""",
            """{"type":"classify","tripId":"not-a-uuid","purpose":"BUSINESS"}""",
            """{"type":"classify","tripId":"$tripId","purpose":"LUNCH"}""",
            """{"type":"addExpense","tripId":"$tripId","expenseType":"TOLL","amountWon":0}""",
            """{"type":"addExpense","tripId":"$tripId","expenseType":"TOLL","amountWon":10000001}""",
            """{"type":"removeExpense","tripId":"$tripId","index":30}""",
            """{"type":"note","tripId":"$tripId","note":"${"가".repeat(301)}"}""",
            """{"type":"resolveCharge","amountWon":-5}""",
            """{"type":"reportIncident","kind":"FIRE"}""",
            """{"type":"updateIncident","id":"$otherId","status":"DONE"}""",
        )
        bad.forEach { json -> assertThrows(json, Exception::class.java) { parseSyncCommand(JSONObject(json)) } }
    }

    @Test fun pairingCodesAndTokensHaveTheExpectedShape() {
        repeat(50) {
            assertTrue(newPairingCode().matches(Regex("\\d{6}")))
            assertTrue(validToken(newToken()))
        }
        assertFalse(validToken(null)); assertFalse(validToken("abc")); assertFalse(validToken("G".repeat(64)))
        assertEquals("123 456", pairingDisplay("123456"))
    }

    @Test fun stateCarriesTheLogOnlyWhenTheSavedCopyMoved() {
        val sample = DriveSample(5_000, GearState.MOVING, 1234.5, charging = false, socPercent = 61, vin = "KMH0000000000001")
        val state = TripLogState(loaded = true, data = TripLogData(trips = listOf(Trip(tripId, 1_000, 2_000, 1.0, 2.0))), live = sample,
            signal = SignalStatus.READY, vehicleModel = "IONIQ 5", revision = 7, dataRevision = 3)
        val unchanged = encodeSyncState(state, dataSince = 3, now = 9_000)
        assertEquals(7L, unchanged.getLong("revision")); assertEquals(3L, unchanged.getLong("dataRevision"))
        assertFalse(unchanged.getBoolean("changed")); assertFalse(unchanged.has("data"))
        assertEquals("READY", unchanged.getString("signal")); assertEquals("IONIQ 5", unchanged.getString("vehicleModel"))
        val live = decodeLive(unchanged.getJSONObject("live"))!!
        assertEquals(GearState.MOVING, live.gear); assertEquals(1234.5, live.odometerKm!!, 0.0); assertEquals(false, live.charging); assertEquals(61, live.socPercent)

        val changed = encodeSyncState(state, dataSince = 2, now = 9_000)
        assertTrue(changed.getBoolean("changed"))
        val copy = TripLogCodec.decode(changed.getJSONObject("data").toString().toByteArray())
        assertEquals(tripId, copy.trips.single().id)
    }

    @Test fun chargeSessionsCloseOnlyAfterARealStop() {
        val ended = DriveSample(200_000, GearState.PARK, 500.0, charging = false, socPercent = 80)
        assertNull(chargeSessionEnded(startAt = null, socFrom = null, sample = ended, wasCharging = false))
        assertNull(chargeSessionEnded(startAt = 190_000, socFrom = 40, sample = ended, wasCharging = true))
        assertNull(chargeSessionEnded(startAt = 100_000, socFrom = 40, sample = ended.copy(charging = true), wasCharging = true))
        val session = chargeSessionEnded(startAt = 100_000, socFrom = 40, sample = ended, wasCharging = true)!!
        assertEquals(100_000L, session.startAt); assertEquals(200_000L, session.endAt); assertEquals(40, session.socFrom); assertEquals(80, session.socTo)
    }

    @Test fun codecKeepsVehicleCardIncidentsAndPendingCharge() {
        val incident = Incident("0b2d4f6a-8c1e-4a3b-9d7f-1e2c3b4a5d6e", 10_000, IncidentKind.DAMAGE, 321.0, "서울 중구", null, null, "주차 중 긁힘", IncidentStatus.CLOSED, 11_000)
        val data = TripLogData(profile = FleetProfile(phoneSync = true), vehicle = VehicleCard("IONIQ 5", "KMH0000000000001", 321.0, 77, 9_000),
            incidents = listOf(incident), pendingCharge = ChargeSession(1_000, 70_000, 30, 55, 320.0))
        val copy = TripLogCodec.decode(TripLogCodec.encode(data))
        assertEquals(data.vehicle, copy.vehicle); assertEquals(listOf(incident), copy.incidents); assertEquals(data.pendingCharge, copy.pendingCharge)
        assertTrue(copy.profile.phoneSync)
        assertEquals(listOf("사고"), IncidentKind.entries.filter { incidentSteps(it).any { step -> step.contains("112") } }.map { it.label })
    }

    @Test fun emulatorPlaceholderVinIsNotAVin() {
        assertNull(validVin("\"invalid\"")); assertNull(validVin("INVALID")); assertNull(validVin(" ")); assertNull(validVin(null)); assertNull(validVin("KMH123"))
        assertEquals("KMHDEMO0000000001", validVin(" \"KMHDEMO0000000001\" "))
    }
}
