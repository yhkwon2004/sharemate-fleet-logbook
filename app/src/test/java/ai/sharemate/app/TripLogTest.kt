package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class TripLogTest {
    private val zone = ZoneOffset.UTC
    private var ids = 0
    private val newId = { "t${++ids}" }
    private fun sample(at: Long, gear: GearState, km: Double?, address: String? = null) = DriveSample(at, gear, km, address)
    private fun trip(id: String, start: Long, startKm: Double, endKm: Double, purpose: TripPurpose = TripPurpose.BUSINESS, driver: String? = "d1", minutes: Long = 30) =
        Trip(id, start, start + minutes * 60_000, startKm, endKm, driverId = driver, purpose = purpose)

    @Test fun leavingParkStartsAndReturningToParkFinishesWithOdometerValues() {
        val started = advanceTrip(null, sample(1_000, GearState.MOVING, 100.0, "강남구"), "d1", newId).single() as TripChange.Started
        assertEquals(100.0, started.trip.startOdometerKm, 0.0)
        assertEquals("d1", started.trip.driverId)
        val moving = advanceTrip(started.trip, sample(60_000, GearState.MOVING, 104.2, "서초구"), "d1", newId).single() as TripChange.Progress
        val finished = advanceTrip(moving.trip, sample(540_000, GearState.PARK, 112.5, "판교"), "d1", newId).single() as TripChange.Finished
        assertEquals(12.5, finished.trip.distanceKm, 1e-9)
        assertEquals(540_000L, finished.trip.endAt); assertFalse(finished.trip.recovered)
        assertEquals("강남구", finished.trip.startAddress)
        assertEquals("판교", finished.trip.endAddress)
    }

    @Test fun noTripWithoutOdometerOrWhileParked() {
        assertTrue(advanceTrip(null, sample(1, GearState.MOVING, null), null, newId).isEmpty())
        assertTrue(advanceTrip(null, sample(1, GearState.PARK, 10.0), null, newId).isEmpty())
        assertTrue(advanceTrip(null, sample(1, GearState.UNKNOWN, 10.0), null, newId).isEmpty())
        assertTrue(advanceTrip(null, sample(1, GearState.MOVING, Double.NaN), null, newId).isEmpty())
    }

    @Test fun lowerOdometerReadingIsIgnoredAndShortManoeuvreIsDiscarded() {
        val open = Trip("a", 0, startOdometerKm = 50.0, endOdometerKm = 50.4)
        val progress = advanceTrip(open, sample(1_000, GearState.MOVING, 49.0), null, newId).single() as TripChange.Progress
        assertEquals(50.4, progress.trip.endOdometerKm, 0.0)
        val reversing = Trip("b", 0, startOdometerKm = 50.0, endOdometerKm = 50.0)
        assertTrue(advanceTrip(reversing, sample(60_000, GearState.PARK, 50.05), null, newId).single() is TripChange.Discarded)
        // A long stop in D without distance is kept so it can be reviewed as a 0 km trip.
        assertTrue(advanceTrip(reversing, sample(MIN_TRIP_MILLIS + 1, GearState.PARK, 50.0), null, newId).single() is TripChange.Finished)
    }

    @Test fun staleRecordingIsClosedAtLastCheckpointBeforeNewTrip() {
        val open = Trip("a", 0, startOdometerKm = 10.0, endOdometerKm = 18.0, updatedAt = 1_000)
        val changes = advanceTrip(open, sample(1_000 + STALE_TRIP_MILLIS + 1, GearState.MOVING, 25.0), "d1", newId)
        val closed = changes[0] as TripChange.Finished
        assertTrue(closed.trip.recovered)
        assertEquals(1_000L, closed.trip.endAt)
        assertEquals(18.0, closed.trip.endOdometerKm, 0.0)
        assertEquals(25.0, (changes[1] as TripChange.Started).trip.startOdometerKm, 0.0)
    }

    @Test fun modeSwitchClosesOldOdometerChainBeforeStartingAnother() {
        val real = Trip("real", 1_000, startOdometerKm = 100.0, endOdometerKm = 112.3, updatedAt = 60_000)
        val toDemo = advanceTrip(real, sample(61_000, GearState.MOVING, 12_345.6).copy(demo = true), "d1", newId)
        val closedReal = (toDemo[0] as TripChange.Finished).trip
        assertEquals(60_000L, closedReal.endAt); assertEquals(112.3, closedReal.endOdometerKm, 0.0)
        assertTrue(closedReal.recovered); assertFalse(closedReal.demo)
        val demo = (toDemo[1] as TripChange.Started).trip
        assertTrue(demo.demo); assertEquals(12_345.6, demo.startOdometerKm, 0.0)
        val toReal = advanceTrip(demo, sample(62_000, GearState.MOVING, 113.0), "d1", newId)
        assertEquals(12_345.6, (toReal[0] as TripChange.Finished).trip.endOdometerKm, 0.0)
        val nextReal = (toReal[1] as TripChange.Started).trip
        assertFalse(nextReal.demo); assertEquals(113.0, nextReal.startOdometerKm, 0.0)
        val parked = advanceTrip(real, sample(61_000, GearState.PARK, 12_345.6).copy(demo = true), "d1", newId)
        assertEquals(1, parked.size); assertTrue(parked.single() is TripChange.Finished)
    }

    @Test fun olderOrUnknownGearSamplesCannotFinishOrRefreshTrip() {
        val open = Trip("a", 1_000, startOdometerKm = 100.0, endOdometerKm = 112.3, updatedAt = 60_000)
        assertTrue(advanceTrip(open, sample(59_000, GearState.PARK, 113.0), "d1", newId).isEmpty())
        assertTrue(advanceTrip(open, sample(59_000, GearState.MOVING, 114.0), "d1", newId).isEmpty())
        assertTrue(advanceTrip(open, sample(60_000 + STALE_TRIP_MILLIS + 1, GearState.UNKNOWN, 115.0), "d1", newId).isEmpty())
        val finished = (advanceTrip(open, sample(61_000, GearState.PARK, 113.0), "d1", newId).single() as TripChange.Finished).trip
        assertEquals(61_000L, finished.endAt); assertEquals(13.0, finished.distanceKm, 0.0)
    }

    @Test fun parkWaitsForValidOdometerBeforeClosingAndMissingValuesDoNotRefreshCheckpoint() {
        val open = Trip("a", 1_000, startOdometerKm = 100.0, endOdometerKm = 112.3, updatedAt = 60_000)
        assertTrue(advanceTrip(open, sample(61_000, GearState.PARK, null), "d1", newId).isEmpty())
        assertTrue(advanceTrip(open, sample(61_000, GearState.MOVING, Double.NaN), "d1", newId).isEmpty())
        assertTrue(advanceTrip(open, sample(60_000 + STALE_TRIP_MILLIS + 1, GearState.PARK, null), "d1", newId).isEmpty())
        val finished = (advanceTrip(open, sample(62_000, GearState.PARK, 113.4), "d1", newId).single() as TripChange.Finished).trip
        assertEquals(62_000L, finished.endAt); assertEquals(13.4, finished.distanceKm, 1e-9); assertFalse(finished.recovered)
    }

    @Test fun auditFindsGapsOverlapsAndIncompleteTrips() {
        val trips = listOf(
            trip("a", 0, 100.0, 110.0),
            trip("b", 3_600_000, 125.0, 130.0, TripPurpose.UNCLASSIFIED, driver = null),
            trip("c", 7_200_000, 129.0, 129.0),
            trip("d", 10_800_000, 129.0, 300.0, minutes = 30),
        )
        val issues = auditTrips(trips)
        val gap = issues.single { it.type == IssueType.ODOMETER_GAP }
        assertEquals("b", gap.tripId); assertEquals("a", gap.previousTripId); assertEquals(15.0, gap.gapKm!!, 1e-9)
        assertEquals("c", issues.single { it.type == IssueType.ODOMETER_OVERLAP }.tripId)
        assertEquals(setOf("b"), issues.filter { it.type == IssueType.UNCLASSIFIED || it.type == IssueType.NO_DRIVER }.map { it.tripId }.toSet())
        assertEquals("c", issues.single { it.type == IssueType.ZERO_DISTANCE }.tripId)
        assertEquals("d", issues.single { it.type == IssueType.IMPLAUSIBLE_SPEED }.tripId)
    }

    @Test fun demoTripsDoNotBreakTheVehicleOdometerChain() {
        val trips = listOf(trip("a", 0, 100.0, 110.0), trip("demo", 1_000_000, 12_345.0, 12_350.0).copy(demo = true), trip("b", 2_000_000, 110.2, 120.0))
        assertTrue(auditTrips(trips).none { it.type == IssueType.ODOMETER_GAP || it.type == IssueType.ODOMETER_OVERLAP })
    }

    @Test fun gapTripUsesNeighbouringOdometerValues() {
        val previous = trip("a", 0, 100.0, 110.0)
        val next = trip("b", 7_200_000, 125.0, 130.0)
        val gap = gapTrip(previous, next, "g", 9_000_000)
        assertEquals(15.0, gap.distanceKm, 1e-9)
        assertEquals(previous.endAt, gap.startAt); assertEquals(next.startAt, gap.endAt)
        assertEquals(TripOrigin.GAP_FILL, gap.origin); assertEquals(TripPurpose.UNCLASSIFIED, gap.purpose)
        assertTrue(auditTrips(listOf(previous, gap, next)).none { it.type == IssueType.ODOMETER_GAP })
    }

    @Test fun logbookFollowsNtsColumnsAndRatio() {
        val day = LocalDate.of(2026, 10, 26).atStartOfDay(zone).toInstant().toEpochMilli()
        val trips = listOf(
            trip("a", day + 3_600_000, 1000.0, 1012.34, TripPurpose.COMMUTE),
            trip("b", day + 7_200_000, 1012.34, 1030.0, TripPurpose.BUSINESS).copy(destination = "판교 거래처",
                expenses = listOf(TripExpense(ExpenseType.TOLL, 3_600, 0, EditSource.VOICE))),
            trip("c", day + 10_800_000, 1030.0, 1040.0, TripPurpose.PERSONAL),
        )
        val drivers = listOf(Driver("d1", "영업팀", "김카택"))
        val rows = logbookRows(trips, drivers, zone)
        assertEquals("영업팀", rows[0].department); assertEquals("김카택", rows[0].name)
        assertEquals(12.3, rows[0].distanceKm, 0.0); assertEquals(12.3, rows[0].commuteKm, 0.0); assertEquals(0.0, rows[0].businessKm, 0.0)
        assertEquals(17.7, rows[1].businessKm, 0.0)
        assertTrue(rows[1].note.contains("판교 거래처") && rows[1].note.contains("통행료 3,600원"))
        assertTrue(rows[2].note.startsWith("개인 사용"))
        val totals = logbookTotals(trips)
        assertEquals(40.0, totals.totalKm, 1e-9); assertEquals(30.0, totals.businessKm, 1e-9); assertEquals(.75, totals.ratio!!, 1e-9)
        val csv = logbookCsv(FleetProfile("(주)셰어메이트", "123-45-67890", "IONIQ 5", "12가3456"), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), rows, totals)
        assertEquals(0xFEFF, csv[0].code)
        assertTrue(csv.substring(1).startsWith("업무용승용차 운행기록부"))
        assertTrue(csv.contains("2026-10-26(월),영업팀,김카택,1000.0,1012.3,12.3,12.3,0.0,"))
        assertTrue(csv.contains("⑬업무사용비율(⑫/⑪),75.0%"))
        assertTrue(csv.contains("\"(주)셰어메이트\"") || csv.contains("(주)셰어메이트"))
    }

    @Test fun csvQuotesCommasAndQuotes() {
        val row = LogbookRow(LocalDate.of(2026, 1, 2), "A,B", "\"홍\"", 0.0, 1.0, 1.0, 0.0, 1.0, "메모")
        val csv = logbookCsv(FleetProfile(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), listOf(row), logbookTotals(emptyList()))
        assertTrue(csv.contains("\"A,B\",\"\"\"홍\"\"\""))
        assertTrue(csv.contains("⑬업무사용비율(⑫/⑪),-"))
    }

    @Test fun csvTreatsUserEnteredSpreadsheetFormulasAsText() {
        val row = LogbookRow(LocalDate.of(2026, 1, 2), "+SUM(1:1)", "@name", 100.0, 101.0, 1.0, 0.0, 1.0, "  =1+1")
        val result = logbookCsv(FleetProfile(companyName = "=1+1", carModel = "-1+1", plateNumber = "\tplate"),
            row.date, row.date, listOf(row), logbookTotals(emptyList()))
        assertTrue(result.contains("법인명,'=1+1"))
        assertTrue(result.contains("①차종,'-1+1")); assertTrue(result.contains("②자동차등록번호,'\tplate"))
        assertTrue(result.contains(",'+SUM(1:1),'@name,100.0,101.0,1.0,0.0,1.0,'  =1+1"))
    }

    @Test fun taxEffectComparesLogbookWithNoLogbookCap() {
        val high = taxEffect(32_000_000, .9)
        assertEquals(28_800_000L, high.withLogWon); assertEquals(15_000_000L, high.withoutLogWon); assertEquals(13_800_000L, high.differenceWon)
        val low = taxEffect(12_000_000, .5)
        assertEquals(6_000_000L, low.withLogWon); assertEquals(12_000_000L, low.withoutLogWon); assertTrue(low.differenceWon < 0)
        assertThrows(IllegalArgumentException::class.java) { taxEffect(1_000, 1.5) }
    }

    @Test fun reimbursementCountsOnlyBusinessKilometresPerDriver() {
        val trips = listOf(trip("a", 0, 0.0, 10.0, TripPurpose.BUSINESS, "d1"), trip("b", 1, 10.0, 15.0, TripPurpose.COMMUTE, "d1"),
            trip("c", 2, 15.0, 40.0, TripPurpose.PERSONAL, "d1"), trip("d", 3, 40.0, 60.0, TripPurpose.BUSINESS, "d2"))
        val result = reimbursements(trips, 150)
        assertEquals(listOf("d2", "d1"), result.map { it.driverId })
        assertEquals(15.0, result[1].businessKm, 1e-9); assertEquals(2_250L, result[1].amountWon)
    }

    @Test fun suggestionReusesPurposeOfSameDestinationButNotEarlierSuggestions() {
        val previous = trip("a", 0, 0.0, 10.0, TripPurpose.BUSINESS).copy(destination = "판교 거래처", purposeSource = EditSource.DRIVER)
        val suggested = trip("b", 1_000, 10.0, 20.0, TripPurpose.PERSONAL).copy(destination = "판교 거래처", purposeSource = EditSource.SUGGESTED)
        val current = trip("c", 2_000, 20.0, 30.0, TripPurpose.UNCLASSIFIED).copy(destination = "판교 거래처")
        assertEquals("a", suggestPurpose(current, listOf(previous, suggested, current))?.id)
        assertNull(suggestPurpose(current.copy(destination = "다른 곳"), listOf(previous)))
    }

    @Test fun profileValidationRejectsMalformedBusinessNumber() {
        validateProfile(FleetProfile(businessNumber = "123-45-67890"))
        assertThrows(IllegalArgumentException::class.java) { validateProfile(FleetProfile(businessNumber = "1234567890")) }
        assertThrows(IllegalArgumentException::class.java) { validateProfile(FleetProfile(reimbursePerKmWon = -1)) }
    }
}

class GleoActionTest {
    @Test fun actionParamAcceptsPlainQuotedOrSeparateExtras() {
        assertEquals("business", parseActionParam("{\"purpose\":\"business\"}", emptyMap()).getString("purpose"))
        assertEquals("toll", parseActionParam("\"{\\\"expenseType\\\":\\\"toll\\\", \\\"amount\\\":3600}\"", emptyMap()).getString("expenseType"))
        val extras = parseActionParam(null, mapOf("purpose" to "commute", "ACTION_PARAM" to null))
        assertEquals("commute", extras.getString("purpose"))
        assertEquals(0, parseActionParam("not json", emptyMap()).length())
    }

    @Test fun enumsMapOnlyKnownValues() {
        assertEquals(TripPurpose.PERSONAL, gleoPurpose(" Personal "))
        assertNull(gleoPurpose("vacation"))
        assertEquals(ExpenseType.PARKING, gleoExpense("parking"))
        val today = LocalDate.of(2026, 10, 27)
        assertEquals(LocalDate.of(2026, 10, 1) to today, gleoPeriod("this_month", today))
        assertEquals(LocalDate.of(2026, 1, 1) to today, gleoPeriod("this_year", today))
        assertNull(gleoPeriod("last_decade", today))
    }

    @Test fun summaryReplyCarriesTotalsWithoutPersonalData() {
        val totals = LogbookTotals(100.0, 20.0, 60.0, 15.0, 5.0, 7, 0)
        val reply = gleoSummaryReply("this month", totals, 2, null)
        assertEquals("success", reply.status)
        assertTrue(reply.message.contains("100.0 km") && reply.message.contains("80%") && reply.message.contains("2 trips"))
        val json = replyJson(reply)
        assertEquals(GLEO_SUMMARY, json.getString("functionName"))
        assertTrue(json.getString("data").contains("\"businessKm\":80"))
    }
}
