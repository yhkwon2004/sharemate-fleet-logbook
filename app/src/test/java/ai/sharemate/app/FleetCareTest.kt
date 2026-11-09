package ai.sharemate.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

class FleetCareTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 1)
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val driver = Driver(id("d"), "영업", "권")
    private fun trip(seed: String, day: Int, purpose: TripPurpose = TripPurpose.BUSINESS, driverId: String? = driver.id, destination: String? = null) =
        Trip(id(seed), at(day, 9), at(day, 10), 100.0 + day, 110.0 + day, destination = destination, driverId = driverId, purpose = purpose, updatedAt = at(day, 10))
    private fun approval(merchant: String, when_: Long, won: Long) = CardApproval(cardApprovalId(when_, true, won, merchant, null, false), when_, true, won, merchant, null, CardSource.NOTIFICATION)

    @Test fun deadlinesAreRankedByUrgencyAndSayExactlyHowFarAwayTheyAre() {
        val care = VehicleCare(inspectionDue = "2026-11-20", insuranceDue = "2026-10-25", leaseEnd = "2027-06-30", serviceIntervalKm = 10_000, lastServiceKm = 42_000.0)
        val alerts = careAlerts(care, 51_300.0, today)
        assertEquals(listOf(CareKind.INSURANCE, CareKind.INSPECTION, CareKind.SERVICE, CareKind.LEASE), alerts.map { it.kind })
        assertEquals(CareLevel.OVERDUE, alerts[0].level); assertEquals("보험 만기 7일 지남", alerts[0].title)
        assertEquals(CareLevel.SOON, alerts[1].level); assertEquals("정기검사 D-19", alerts[1].title)
        assertEquals(CareLevel.SOON, alerts[2].level); assertEquals(700.0, alerts[2].kmLeft!!, 0.01)
        assertEquals(CareLevel.INFO, alerts[3].level)
        // The particle 까지 attaches to the date; the other lines keep a space before their first word.
        assertEquals("11월 20일까지 검사를 받아야 해요", alerts[1].detail)
        assertEquals("10월 25일 만기 · 갱신 확인", alerts[0].detail)
        assertEquals("6월 30일 계약 종료 · 반납·연장 결정", alerts[3].detail)
        assertTrue("미설정 항목은 조용함", careAlerts(VehicleCare(), 1.0, today).isEmpty())
        assertTrue("정비 주기는 계기판 없이는 계산하지 않음", careAlerts(care.copy(inspectionDue = null, insuranceDue = null, leaseEnd = null), null, today).isEmpty())
    }

    @Test fun careInputIsValidated() {
        assertThrows(IllegalArgumentException::class.java) { validateCare(VehicleCare(inspectionDue = "11/20")) }
        assertThrows(IllegalArgumentException::class.java) { validateCare(VehicleCare(serviceIntervalKm = 100)) }
        validateCare(VehicleCare(inspectionDue = "2026-11-20", serviceIntervalKm = 10_000, lastServiceKm = 0.0))
    }

    @Test fun workshopChargeBecomesAMaintenanceRecordAndMovesTheServiceClock() {
        val t = trip("a", 20, destination = "블루핸즈 판교점")
        val base = TripLogData(drivers = listOf(driver), trips = listOf(t), care = VehicleCare(serviceIntervalKm = 10_000, lastServiceKm = 100.0))
        val data = settleCharges(mergeCardEvents(base, listOf(approval("블루핸즈 판교점 오일교환", at(20, 10, 30), 89_000)), at(20, 11), zone), at(20, 11), zone)
        assertEquals(ChargeState.MATCHED, data.charges.single().state)
        val record = data.maintenance.single()
        assertEquals(MaintenanceKind.OIL, record.kind); assertEquals(89_000L, record.amountWon); assertEquals(t.endOdometerKm, record.odometerKm!!, 0.0)
        assertEquals(EditSource.CARD, record.source); assertEquals(data.charges.single().id, record.cardChargeId)
        assertEquals("오일 교환이 마지막 정비가 됨", t.endOdometerKm, data.care.lastServiceKm!!, 0.0)
        assertEquals(ExpenseType.MAINTENANCE, data.trips.single().expenses.single().type)
        val excluded = excludeCharge(data, data.charges.single().id, at(21, 9))
        assertTrue("제외하면 정비 기록도 사라짐", excluded.maintenance.isEmpty())
    }

    @Test fun merchantNamesMapToKindsOfWork() {
        assertEquals(MaintenanceKind.TIRE, maintenanceKindFor("타이어뱅크 수원"))
        assertEquals(MaintenanceKind.WASH, maintenanceKindFor("워시존 셀프세차"))
        assertEquals(MaintenanceKind.INSPECTION, maintenanceKindFor("한국교통안전공단 성남검사소"))
        assertEquals(MaintenanceKind.REPAIR, maintenanceKindFor("현대 블루핸즈"))
        assertEquals(CostCategory.MAINTENANCE, categorize("스피드메이트 분당점"))
        assertEquals("오일뱅크는 주유", CostCategory.FUEL, categorize("현대오일뱅크 판교"))
    }

    @Test fun aMonthClosesOnlyWhenNothingIsLeftToAnswer() {
        val month = YearMonth.of(2026, 10)
        val open = TripLogData(drivers = listOf(driver), trips = listOf(trip("a", 3), trip("b", 5, TripPurpose.UNCLASSIFIED)))
        val status = monthStatus(open, month, zone)
        assertEquals(2, status.trips); assertEquals(1, status.unfinished); assertEquals(50, status.percent); assertFalse(status.ready)
        val error = assertThrows(IllegalArgumentException::class.java) { closeMonth(open, month, at(31, 18), zone) }
        assertTrue(error.message, error.message!!.contains("1건"))
        val done = open.copy(trips = open.trips.map { it.copy(purpose = TripPurpose.BUSINESS) })
        assertTrue(monthStatus(done, month, zone).ready)
        val closed = closeMonth(done, month, at(31, 18), zone)
        val close = closed.closedMonths.single()
        assertEquals("2026-10", close.month); assertEquals(2, close.trips); assertEquals(20.0, close.totalKm, 0.01)
        assertThrows(IllegalArgumentException::class.java) { closeMonth(closed, month, at(31, 19), zone) }
        assertNotNull(closedMonthOf(closed, done.trips[0], zone))
        assertTrue(reopenMonth(closed, month).closedMonths.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { closeMonth(TripLogData(), month, at(31, 18), zone) }
    }

    @Test fun demoAndRealMonthsCloseSeparately() {
        val month = YearMonth.of(2026, 10)
        val demoTrip = trip("d", 3).copy(demo = true)
        val real = TripLogData(drivers = listOf(driver), trips = listOf(trip("a", 3), demoTrip))
        val closedReal = closeMonth(real, month, at(31, 18), zone)
        assertNull("시연 운행은 실제 마감에 들어가지 않음", monthStatus(closedReal.copy(profile = FleetProfile(demoMode = true)), month, zone).closed)
        assertEquals(1, closedReal.closedMonths.single().trips)
    }

    @Test fun careCommandsSurviveTheWire() {
        val care = VehicleCare("2026-11-20", null, "2027-06-30", 10_000, 42_000.0, at(1, 9))
        val commands = listOf(SyncCommand.UpdateCare(care), SyncCommand.AddMaintenance(MaintenanceKind.TIRE, 320_000, "타이어뱅크", "앞 2본", 51_300.0),
            SyncCommand.AddMaintenance(MaintenanceKind.WASH, 0, null, "", null), SyncCommand.RemoveMaintenance(id("m")), SyncCommand.CloseMonth("2026-10"), SyncCommand.ReopenMonth("2026-10"))
        commands.forEach { assertEquals(it, parseSyncCommand(JSONObject(encodeSyncCommand(it).toString()))) }
        assertThrows(Exception::class.java) { parseSyncCommand(JSONObject().put("type", "closeMonth").put("month", "2026/10")) }
        assertThrows(Exception::class.java) { parseSyncCommand(JSONObject().put("type", "updateCare").put("care", JSONObject().put("inspectionDue", "next week"))) }
    }

    @Test fun everythingRoundTripsThroughTheFileAndOldFilesStillLoad() {
        val record = MaintenanceRecord(id("m"), at(20, 10), MaintenanceKind.TIRE, 51_300.0, 320_000, "타이어뱅크", "앞 2본", EditSource.PHONE)
        val data = TripLogData(care = VehicleCare("2026-11-20", "2027-01-01", null, 10_000, 42_000.0, at(1, 9)), maintenance = listOf(record),
            closedMonths = listOf(MonthClose("2026-09", at(30, 18), 12, 310.5, 280.0, 45_000)))
        val copy = TripLogCodec.decode(TripLogCodec.encode(data))
        assertEquals(data.care, copy.care); assertEquals(data.maintenance, copy.maintenance); assertEquals(data.closedMonths, copy.closedMonths)
        val old = JSONObject(TripLogCodec.encode(TripLogData()).toString(Charsets.UTF_8)).apply { remove("care"); remove("maintenance"); remove("closedMonths") }
        val loaded = TripLogCodec.decode(old.toString().toByteArray())
        assertEquals(VehicleCare(), loaded.care); assertTrue(loaded.maintenance.isEmpty() && loaded.closedMonths.isEmpty())
        assertThrows(Exception::class.java) { TripLogCodec.decode(TripLogCodec.encode(data.copy(closedMonths = data.closedMonths + data.closedMonths))) }
    }
}
