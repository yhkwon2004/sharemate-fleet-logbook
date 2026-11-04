package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

class HomeDashboardTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(month: Int, day: Int, hour: Int) = LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val driver = Driver(id("d"), "영업", "권")
    private fun trip(seed: String, month: Int, day: Int, purpose: TripPurpose = TripPurpose.BUSINESS, driverId: String? = driver.id, km: Double = 10.0,
        start: Double = 100.0) =
        Trip(id(seed), at(month, day, 9), at(month, day, 10), start, start + km, driverId = driverId, purpose = purpose, updatedAt = at(month, day, 10))

    @Test fun greetingFollowsTheHourAndNamesTheEmployeeOnlyWhenKnown() {
        assertEquals("좋은 아침이에요, 권 님", homeGreeting(8, "권", false))
        assertEquals("오늘도 안전한 운행 되세요, 권 님", homeGreeting(14, "권", false))
        assertEquals("오늘도 수고 많으셨어요", homeGreeting(19, null, false))
        assertEquals("오늘도 안전한 운행 되세요", homeGreeting(12, " ", false))
        assertEquals("늦은 시간이에요 · 천천히 안전하게", homeGreeting(3, "권", false))
        assertEquals("기록 중이면 시간대와 무관", "운행을 기록하고 있어요", homeGreeting(8, "권", true))
    }

    @Test fun subtitleSaysWhatTheCarIsDoingInItsRealState() {
        assertEquals("출발하면 자동으로 기록하고, 주차하면 목적과 비용을 정리해 드려요.", homeSubtitle(SignalStatus.READY, false, null))
        assertEquals("계기판 누적거리를 읽으려면 차량 정보 권한이 필요해요.", homeSubtitle(SignalStatus.DENIED, false, null))
        assertTrue(homeSubtitle(SignalStatus.DEMO, true, null).startsWith("시연 주행은 15배속"))
        val open = Trip(id("o"), at(11, 4, 9), null, 100.0, 103.2)
        assertTrue(homeSubtitle(SignalStatus.RECORDING, false, open).contains("3.2 km"))
        assertEquals("차량 데이터를 받을 수 없어요. 연결 상태를 확인해 주세요.", homeSubtitle(SignalStatus.ERROR, false, null))
    }

    @Test fun cardLinesQuoteRealVehicleNumbersAndPutADueDeadlineFirst() {
        val today = LocalDate.of(2026, 11, 1)
        val plain = homeCardLines(TripLogData(drivers = listOf(driver)), 100, 12_394.0, true, today, zone)
        assertEquals("배터리 100% · 12,394.0 km", plain.vehicle)
        assertEquals("실시간이 아닌 값은 마지막으로 표시", "배터리 100% · 마지막 12,394.0 km",
            homeCardLines(TripLogData(), 100, 12_394.0, false, today, zone).vehicle)
        assertEquals("0 km 같은 읽기 값은 빼기", "배터리 100%", homeCardLines(TripLogData(), 100, 0.0, true, today, zone).vehicle)
        assertEquals("차량 상태 · 관리 기한", homeCardLines(TripLogData(), null, null, false, today, zone).vehicle)
        val care = VehicleCare(inspectionDue = "2026-11-20")
        assertEquals("정기검사 D-19 · 배터리 100%", homeCardLines(TripLogData(care = care), 100, 12_394.0, true, today, zone).vehicle)
        val key = careSnoozeKey(careDueAlerts(care, 12_394.0, today, emptySet()).single(), care)
        assertEquals("미뤄 둔 기한은 카드에서도 빠짐", "배터리 100% · 12,394.0 km",
            homeCardLines(TripLogData(care = care), 100, 12_394.0, true, today, zone, setOf(key)).vehicle)
    }

    @Test fun logCardCountsExactlyWhatTheReviewListShows() {
        val today = LocalDate.of(2026, 11, 20)
        assertEquals("11월 운행이 아직 없어요", homeCardLines(TripLogData(), null, null, false, today, zone).log)
        // One continuous odometer chain, so the only issues are the two the trips themselves carry.
        val data = TripLogData(drivers = listOf(driver), trips = listOf(trip("x", 10, 30, start = 90.0), trip("a", 11, 3), trip("b", 11, 5, TripPurpose.UNCLASSIFIED, start = 110.0),
            trip("c", 11, 6, driverId = null, start = 120.0)))
        val lines = homeCardLines(data, null, null, false, today, zone)
        assertEquals("11월 3건 · 확인 2건 남음", lines.log); assertEquals(2, lines.logPending)
        assertEquals(LogReview(3, 2), logReview(data, YearMonth.of(2026, 11), zone))
        val gap = data.copy(trips = data.trips.map { if (it.id == id("c")) it.copy(startOdometerKm = 150.0, endOdometerKm = 160.0, driverId = driver.id) else it })
        assertEquals("계기판 공백도 점검 필요 목록과 같이 셈", 2, homeCardLines(gap, null, null, false, today, zone).logPending)
        val done = homeCardLines(data.copy(trips = listOf(trip("a", 11, 3))), null, null, false, today, zone)
        assertEquals("11월 1건 · 모두 확인됨", done.log); assertEquals(0, done.logPending)
        val recording = TripLogData(open = Trip(id("o"), at(11, 20, 8), null, 100.0, 101.0))
        assertEquals("11월 첫 운행 기록 중", homeCardLines(recording, null, null, false, today, zone).log)
    }

    @Test fun exportCardNamesLastMonthWhileItIsStillOpen() {
        val data = TripLogData(drivers = listOf(driver), trips = listOf(trip("a", 10, 3), trip("b", 11, 2, start = 110.0)))
        assertEquals("10월 운행기록부 · 마감 전 저장", homeCardLines(data, null, null, false, LocalDate.of(2026, 11, 3), zone).export)
        val closed = closeMonth(data, YearMonth.of(2026, 10), at(11, 1, 9), zone)
        assertEquals("11월 운행기록부 · Excel 5개 시트", homeCardLines(closed, null, null, false, LocalDate.of(2026, 11, 3), zone).export)
    }

    @Test fun monthWidgetUsesOneTruthfulWord() {
        val today = LocalDate.of(2026, 11, 20)
        val empty = monthWidgetInfo(TripLogData(), today, zone)
        assertEquals("대기", empty.headline); assertEquals(0, empty.tone); assertNull(empty.auto)
        val open = TripLogData(drivers = listOf(driver), trips = listOf(trip("a", 11, 3, km = 30.0), trip("b", 11, 5, TripPurpose.PERSONAL, km = 10.0), trip("c", 11, 6, TripPurpose.UNCLASSIFIED, km = 10.0)))
        val info = monthWidgetInfo(open, today, zone)
        assertEquals("확인 1건", info.headline); assertEquals(2, info.tone)
        assertEquals("업무 60% · 50.0 km · 3건", info.detail)
        assertEquals("이달 운행일지 · 11월", info.title)
        assertTrue(info.auto!!.startsWith("자동 분류 0건 · 카드 반영 0건"))
        val complete = open.copy(trips = open.trips.dropLast(1))
        assertEquals("완성", monthWidgetInfo(complete, today, zone).headline)
        val closed = closeMonth(complete, YearMonth.of(2026, 11), at(11, 20, 9), zone)
        assertEquals("마감됨", monthWidgetInfo(closed, today, zone).headline)
        val reopened = closed.copy(trips = closed.trips + trip("d", 11, 25, TripPurpose.UNCLASSIFIED, start = 140.0))
        val after = monthWidgetInfo(reopened, LocalDate.of(2026, 11, 26), zone)
        assertEquals("마감 뒤 들어온 미확인 운행은 완료로 보이지 않음", "마감 후 확인 1건", after.headline); assertEquals(2, after.tone)
        assertNull("자동 완성을 끄면 자동 줄도 없음", monthWidgetInfo(complete.copy(profile = complete.profile.copy(autopilot = false)), today, zone).auto)
        val first = monthWidgetInfo(TripLogData(open = Trip(id("o"), at(11, 20, 8), null, 100.0, 101.0)), today, zone)
        assertEquals("첫 운행 기록 중이면 대기가 아님", "기록 중", first.headline)
    }
}
