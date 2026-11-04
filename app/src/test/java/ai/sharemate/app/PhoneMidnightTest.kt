package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

class PhoneMidnightTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(month: Int, day: Int, hour: Int) = LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val driver = Driver(id("d"), "영업", "권")
    private fun trip(seed: String, month: Int, day: Int, start: Double, purpose: TripPurpose = TripPurpose.BUSINESS, km: Double = 10.0, demo: Boolean = false) =
        Trip(id(seed), at(month, day, 9), at(month, day, 10), start, start + km, driverId = driver.id, purpose = purpose, demo = demo, updatedAt = at(month, day, 10))

    @Test fun cardLinesAreShortAndComeFromTheCopy() {
        val today = LocalDate.of(2026, 11, 20)
        val empty = phoneCardLines(TripLogData(), today, zone)
        assertEquals("차량 상태", empty.vehicle); assertEquals("11월 운행 없음", empty.log); assertEquals("11월 Excel", empty.export)
        val data = TripLogData(drivers = listOf(driver), vehicle = VehicleCard(socPercent = 82, odometerKm = 12_394.0),
            trips = listOf(trip("a", 11, 3, 100.0), trip("b", 11, 5, 110.0, TripPurpose.UNCLASSIFIED)))
        val lines = phoneCardLines(data, today, zone)
        assertEquals("배터리 82%", lines.vehicle)
        assertEquals("확인 1건 남음", lines.log); assertEquals(1, lines.logPending)
        assertEquals("기한이 다가오면 기한이 먼저", "정기검사 D-10",
            phoneCardLines(data.copy(care = VehicleCare(inspectionDue = "2026-11-30")), today, zone).vehicle)
        assertEquals("2건 · 모두 확인", phoneCardLines(data.copy(trips = data.trips.map { it.copy(purpose = TripPurpose.BUSINESS) }), today, zone).log)
        assertEquals("시연 모드가 아니면 시연 기록은 세지 않음", "11월 운행 없음",
            phoneCardLines(data.copy(trips = data.trips.map { it.copy(demo = true) }), today, zone).log)
    }

    @Test fun exportCardNamesLastMonthWhileItIsOpen() {
        val data = TripLogData(drivers = listOf(driver), trips = listOf(trip("a", 10, 3, 100.0), trip("b", 11, 2, 110.0)))
        assertEquals("10월 마감 전 Excel", phoneCardLines(data, LocalDate.of(2026, 11, 3), zone).export)
        assertEquals("11월 Excel", phoneCardLines(closeMonth(data, YearMonth.of(2026, 10), at(11, 1, 9), zone), LocalDate.of(2026, 11, 3), zone).export)
    }

    @Test fun subtitleTellsTheTruthAboutTheLink() {
        assertEquals("차량 기록을 실시간으로 받고 있어요", phoneSubtitle(paired = true, connected = true, open = null))
        assertEquals("연결 대기 · 마지막으로 받은 기록을 보여 줘요", phoneSubtitle(paired = true, connected = false, open = null))
        assertTrue(phoneSubtitle(paired = false, connected = false, open = null).startsWith("파일 사본"))
        assertTrue(phoneSubtitle(true, true, Trip(id("o"), at(11, 20, 8), null, 100.0, 103.5)).contains("3.5 km"))
    }

    @Test fun todayCountsFinishedTripsAndTheOpenOneStartedToday() {
        val today = LocalDate.of(2026, 11, 20)
        val data = TripLogData(trips = listOf(trip("a", 11, 20, 100.0, km = 12.0).copy(expenses = listOf(TripExpense(ExpenseType.PARKING, 3_000, at(11, 20, 10), EditSource.DRIVER))),
            trip("y", 11, 19, 80.0)), open = Trip(id("o"), at(11, 20, 15), null, 112.0, 115.5))
        val day = phoneToday(data, today, zone)
        assertEquals(15.5, day.km, 1e-9); assertEquals(1, day.trips); assertEquals(3_000L, day.expenseWon)
        assertEquals("어제 시작한 열린 운행은 오늘 거리에 넣지 않음", 12.0,
            phoneToday(data.copy(open = data.open!!.copy(startAt = at(11, 19, 23))), today, zone).km, 1e-9)
    }
}
