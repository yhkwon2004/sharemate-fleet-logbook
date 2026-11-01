package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

class MonthCloseUiTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val driver = Driver(id("d"), "영업", "권")
    private fun trip(seed: String, month: Int, day: Int, purpose: TripPurpose = TripPurpose.BUSINESS) =
        Trip(id(seed), at(month, day, 9), at(month, day, 10), 100.0, 110.0, driverId = driver.id, purpose = purpose, updatedAt = at(month, day, 10))

    @Test fun theTargetIsLastMonthWhileItIsOpenThenThisMonthFromTheTwentyFifth() {
        val data = TripLogData(drivers = listOf(driver), trips = listOf(trip("a", 10, 3), trip("b", 10, 5, TripPurpose.UNCLASSIFIED), trip("c", 11, 2)))
        val early = monthCloseTarget(data, LocalDate.of(2026, 11, 3), zone)
        assertEquals(YearMonth.of(2026, 10), early!!.month); assertEquals(1, early.issues)
        val closed = closeMonth(data.copy(trips = data.trips.map { it.copy(purpose = TripPurpose.BUSINESS) }), YearMonth.of(2026, 10), at(11, 1, 9), zone)
        assertNull("지난달이 마감되면 25일 전에는 조용함", monthCloseTarget(closed, LocalDate.of(2026, 11, 3), zone))
        assertEquals(YearMonth.of(2026, 11), monthCloseTarget(closed, LocalDate.of(2026, 11, 25), zone)!!.month)
        assertNull("운행 없는 달은 대상이 아님", monthCloseTarget(closed.copy(trips = closed.trips.filter { it.id != id("c") }), LocalDate.of(2026, 11, 25), zone))
        assertNull(monthCloseTarget(TripLogData(), LocalDate.of(2026, 11, 28), zone))
    }

    @Test fun theBannerQuotesTheRealDeadlineAndTheRealCount() {
        val open = MonthStatus(YearMonth.of(2026, 11), 10, 2, 1, null)
        assertEquals("11월 마감까지 5일 · 남은 확인 3건", monthCloseBannerText(open, LocalDate.of(2026, 11, 25)))
        assertEquals("11월 마감일 오늘 · 남은 확인 3건", monthCloseBannerText(open, LocalDate.of(2026, 11, 30)))
        assertEquals("10월 운행일지 마감 전 · 남은 확인 3건", monthCloseBannerText(open.copy(month = YearMonth.of(2026, 10)), LocalDate.of(2026, 11, 2)))
        assertEquals("10월 정리 끝 · Excel 저장과 마감만 남았어요", monthCloseBannerText(MonthStatus(YearMonth.of(2026, 10), 10, 0, 0, null), LocalDate.of(2026, 11, 2)))
        assertEquals("지난해 달은 연도를 붙임", "2025년 12월 운행일지 마감 전 · 남은 확인 1건", monthCloseBannerText(MonthStatus(YearMonth.of(2025, 12), 4, 1, 0, null), LocalDate.of(2026, 1, 2)))
        assertEquals("12월 마감까지 3일 · 남은 확인 1건", monthCloseBannerText(MonthStatus(YearMonth.of(2025, 12), 4, 1, 0, null), LocalDate.of(2025, 12, 28)))
        assertEquals("12월", monthLabel(YearMonth.of(2025, 12), LocalDate.of(2025, 12, 28)))
    }

    @Test fun stepsFollowTheAuditNumbersAndNeverClaimAnExcelFileWasSaved() {
        val open = monthCloseSteps(MonthStatus(YearMonth.of(2026, 10), 10, 2, 1, null))
        assertEquals(listOf("목적·사용자 확인", "카드 확인", "Excel 저장", "마감"), open.map { it.label })
        assertEquals(listOf(false, false, false, false), open.map { it.done })
        assertEquals("2건 남음", open[0].detail); assertEquals("정리하기", open[0].action)
        assertEquals("1건 남음", open[1].detail); assertEquals("확인하기", open[1].action)
        assertTrue(open[2].optional); assertEquals("①·②를 끝내면 열려요", open[3].detail)
        val ready = monthCloseSteps(MonthStatus(YearMonth.of(2026, 10), 10, 0, 0, null))
        assertTrue(ready[0].done && ready[1].done); assertNull(ready[0].action); assertEquals("지금 마감할 수 있어요", ready[3].detail)
        val closed = monthCloseSteps(MonthStatus(YearMonth.of(2026, 10), 10, 0, 0, MonthClose("2026-10", at(11, 1, 18, 2), 10, 300.0, 280.0, 45_000)))
        assertTrue(closed[3].done); assertEquals("마감됨 · " + closedStamp(at(11, 1, 18, 2)), closed[3].detail)
        assertFalse("Excel 저장은 앱이 확인할 수 없어 완료 표시를 하지 않음", closed[2].done)
        assertEquals("운행이 없어요", monthCloseSteps(MonthStatus(YearMonth.of(2026, 10), 0, 0, 0, null))[3].detail)
    }

    @Test fun editsAfterCloseAreRecognisedFromTheHistoryTheRepositoryWrites() {
        val month = YearMonth.of(2026, 10)
        val base = TripLogData(drivers = listOf(driver), trips = listOf(trip("a", 10, 3), trip("b", 10, 5)))
        val closed = closeMonth(base, month, at(11, 1, 9), zone)
        assertEquals(0, editedAfterCloseCount(closed, month, zone))
        val touched = closed.copy(trips = closed.trips.map { if (it.id == id("a")) withHistory(it, SEALED_EDIT_MARK, at(11, 2, 9)) else it })
        assertTrue(editedAfterClose(touched.trips.first { it.id == id("a") }))
        assertFalse(editedAfterClose(touched.trips.first { it.id == id("b") }))
        assertEquals(1, editedAfterCloseCount(touched, month, zone))
        assertEquals("시연 운행은 실제 집계에서 제외", 0, editedAfterCloseCount(touched.copy(trips = touched.trips.map { it.copy(demo = true) }), month, zone))
    }

    @Test fun previewHeaderFindsTheCloseOnlyForASingleClosedMonth() {
        val month = YearMonth.of(2026, 10)
        val closed = closeMonth(TripLogData(drivers = listOf(driver), trips = listOf(trip("a", 10, 3))), month, at(11, 1, 9), zone)
        assertNotNull(closedMonthFor(closed, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
        assertNotNull(closedMonthFor(closed, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20)))
        assertNull("연간 범위에는 표시하지 않음", closedMonthFor(closed, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 31)))
        assertNull("시연 모드에서는 실제 마감이 보이지 않음", closedMonthFor(closed.copy(profile = FleetProfile(demoMode = true)), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
        assertEquals("11/1", closedDay(at(11, 1, 9), zone))
        assertEquals("11/1 09:00", closedStamp(at(11, 1, 9), zone))
    }
}
