package ai.sharemate.app

import androidx.compose.ui.unit.dp
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

    @Test fun theReportOpensWhereTheHomeCardBannerAndBadgePoint() {
        val data = TripLogData(drivers = listOf(driver), trips = listOf(trip("a", 10, 3), trip("b", 10, 5, TripPurpose.UNCLASSIFIED), trip("c", 11, 2)))
        val closed = closeMonth(data.copy(trips = data.trips.map { it.copy(purpose = TripPurpose.BUSINESS) }), YearMonth.of(2026, 10), at(11, 1, 9), zone)
        val december = LocalDateTime.of(2025, 12, 10, 9, 0).atZone(zone).toInstant().toEpochMilli()
        val lastYear = TripLogData(drivers = listOf(driver), trips = listOf(Trip(id("dec"), december, december + 3_600_000, 100.0, 110.0, driverId = driver.id, purpose = TripPurpose.BUSINESS)))
        val cases = listOf(
            Triple(data, LocalDate.of(2026, 11, 3), YearMonth.of(2026, 10)),
            Triple(closed, LocalDate.of(2026, 11, 3), YearMonth.of(2026, 11)),
            Triple(closed, LocalDate.of(2026, 11, 25), YearMonth.of(2026, 11)),
            Triple(lastYear, LocalDate.of(2026, 1, 2), YearMonth.of(2025, 12)),
            Triple(TripLogData(), LocalDate.of(2026, 11, 9), YearMonth.of(2026, 11)),
        )
        cases.forEach { (case, today, expected) ->
            assertEquals("$today", expected, reportLandingMonth(case, today, zone))
            // The home's and the phone's 정보 내보내기 open the same month their pane lands on: the car's (2.15) and the phone's.
            assertEquals("홈 $today", carReportLandingMonth(case, today, zone), homeCardLines(case, null, null, false, today, zone).exportMonth)
            assertEquals("휴대폰 $today", expected, phoneCardLines(case, today, zone).exportMonth)
        }
        // Where the two differ: last month closed, this month without a trip yet. The phone keeps this month, the car shows October.
        val quiet = closed.copy(trips = closed.trips.filter { it.id != id("c") })
        val today = LocalDate.of(2026, 11, 13)
        assertEquals(YearMonth.of(2026, 11), phoneCardLines(quiet, today, zone).exportMonth)
        assertEquals(YearMonth.of(2026, 10), homeCardLines(quiet, null, null, false, today, zone).exportMonth)
        assertEquals(YearMonth.of(2026, 10), carReportLandingMonth(quiet, today, zone))
    }

    @Test fun aCarPageLandsOnTheLatestMonthThatHasTrips() {
        val october = listOf(trip("a", 10, 3), trip("b", 10, 28))
        // This month while it has a trip, and while the log is empty.
        assertEquals(YearMonth.of(2026, 11), landingMonth(october + trip("c", 11, 2), LocalDate.of(2026, 11, 13), zone))
        assertEquals(YearMonth.of(2026, 11), landingMonth(emptyList(), LocalDate.of(2026, 11, 13), zone))
        // Otherwise the latest earlier month with a trip, however far back, across a year's end too.
        assertEquals(YearMonth.of(2026, 10), landingMonth(october, LocalDate.of(2026, 11, 13), zone))
        assertEquals(YearMonth.of(2026, 10), landingMonth(october + trip("old", 8, 1), LocalDate.of(2026, 12, 1), zone))
        val december = LocalDateTime.of(2025, 12, 10, 9, 0).atZone(zone).toInstant().toEpochMilli()
        val lastYear = Trip(id("dec"), december, december + 3_600_000, 100.0, 110.0, driverId = driver.id, purpose = TripPurpose.BUSINESS)
        assertEquals(YearMonth.of(2025, 12), landingMonth(listOf(lastYear), LocalDate.of(2026, 2, 1), zone))
        // A trip still recording is not a trip of the list, and one dated after today never pulls the page forward.
        val recording = Trip(id("open"), at(11, 13, 9), null, 110.0, 110.0, driverId = driver.id)
        assertEquals(YearMonth.of(2026, 10), landingMonth(october + recording, LocalDate.of(2026, 11, 13), zone))
        assertEquals(YearMonth.of(2026, 10), landingMonth(october + trip("future", 12, 1), LocalDate.of(2026, 11, 13), zone))
    }

    @Test fun theCarsReportLandsOnAMonthItCanShow() {
        val data = TripLogData(drivers = listOf(driver), trips = listOf(trip("a", 10, 3), trip("b", 10, 5, TripPurpose.UNCLASSIFIED)))
        val closed = closeMonth(data.copy(trips = data.trips.map { it.copy(purpose = TripPurpose.BUSINESS) }), YearMonth.of(2026, 10), at(11, 1, 9), zone)
        // A month still to close keeps the close target; a closed last month is shown while this month has no trip yet.
        assertEquals(YearMonth.of(2026, 10), carReportLandingMonth(data, LocalDate.of(2026, 11, 3), zone))
        assertEquals(YearMonth.of(2026, 11), reportLandingMonth(closed, LocalDate.of(2026, 11, 13), zone))
        assertEquals(YearMonth.of(2026, 10), carReportLandingMonth(closed, LocalDate.of(2026, 11, 13), zone))
        // This month as soon as it has a trip, and for an empty log.
        assertEquals(YearMonth.of(2026, 11), carReportLandingMonth(closed.copy(trips = closed.trips + trip("c", 11, 2)), LocalDate.of(2026, 11, 13), zone))
        assertEquals(YearMonth.of(2026, 11), carReportLandingMonth(TripLogData(), LocalDate.of(2026, 11, 13), zone))
    }

    @Test fun theHeadlineNeverPromisesTripsToAPastMonth() {
        val today = LocalDate.of(2026, 11, 9)
        assertEquals("11월 운행이 아직 없어요", monthCloseHeadline(MonthStatus(YearMonth.of(2026, 11), 0, 0, 0, null), today))
        assertEquals("8월 운행 기록이 없어요", monthCloseHeadline(MonthStatus(YearMonth.of(2026, 8), 0, 0, 0, null), today))
        assertEquals("2025년 12월 운행 기록이 없어요", monthCloseHeadline(MonthStatus(YearMonth.of(2025, 12), 0, 0, 0, null), today))
        assertEquals("10월 운행일지 마감됨", monthCloseHeadline(MonthStatus(YearMonth.of(2026, 10), 10, 0, 0, MonthClose("2026-10", at(11, 1, 9), 10, 300.0, 280.0, 45_000)), today))
        assertEquals("10월 운행일지 50% 완성", monthCloseHeadline(MonthStatus(YearMonth.of(2026, 10), 10, 5, 0, null), today))
        // A closed month whose trips were deleted keeps the closed branch (§4's "past month, closed" row), not the no-trips line.
        assertEquals("8월 운행일지 마감됨", monthCloseHeadline(MonthStatus(YearMonth.of(2026, 8), 0, 0, 0, MonthClose("2026-08", at(9, 1, 9), 2, 20.0, 20.0, 0)), today))
    }

    @Test fun theFootnoteSaysWhatTheCloseButtonWaitsFor() {
        val today = LocalDate.of(2026, 11, 9)
        assertEquals("마감은 이 달이 완성됐다는 표시예요. 마감 후에도 운행은 고칠 수 있고, 고친 기록엔 ‘마감 후 수정’이 붙어요.",
            monthCloseFootnote(MonthStatus(YearMonth.of(2026, 10), 10, 0, 0, null), today))
        assertEquals("운행이 기록되면 여기서 마감할 수 있어요.", monthCloseFootnote(MonthStatus(YearMonth.of(2026, 11), 0, 0, 0, null), today))
        assertEquals("운행이 없는 달은 마감하지 않아도 돼요.", monthCloseFootnote(MonthStatus(YearMonth.of(2026, 8), 0, 0, 0, null), today))
        assertEquals("남은 확인 3건을 끝내면 마감 버튼이 열려요.", monthCloseFootnote(MonthStatus(YearMonth.of(2026, 10), 10, 2, 1, null), today))
    }

    @Test fun theReportCardKeepsOneNoteAndFoldsTheRestBehindInfo() {
        val today = LocalDate.of(2026, 11, 9)
        val closed = MonthStatus(YearMonth.of(2026, 10), 10, 0, 0, MonthClose("2026-10", at(11, 1, 9), 10, 300.0, 280.0, 45_000))
        assertEquals("마감 후 수정 1건 · 운행기록에 표시돼요" to emptyList<String>(), monthCloseNote(closed, today, edited = 1))
        val folded = "마감 후에도 운행은 고칠 수 있어요" to listOf("고친 운행엔 ‘마감 후 수정’이 붙어요")
        assertEquals("마감 후 수정이 없는 달", folded, monthCloseNote(closed, today, edited = 0))
        assertEquals("마감할 수 있는 달도 같은 말", folded, monthCloseNote(MonthStatus(YearMonth.of(2026, 10), 10, 0, 0, null), today, edited = 0))
        assertEquals("운행이 없는 지난달", "운행이 없는 달은 마감하지 않아도 돼요" to emptyList<String>(),
            monthCloseNote(MonthStatus(YearMonth.of(2026, 8), 0, 0, 0, null), today, edited = 0))
        // 2.13: a month without trips shows no steps and no close button, so its note says what the close waits for.
        assertEquals("운행이 없는 이번 달", "운행이 기록되면 여기서 마감해요" to emptyList<String>(),
            monthCloseNote(MonthStatus(YearMonth.of(2026, 11), 0, 0, 0, null), today, edited = 0))
        // ④ already says what the close waits for ("①·②를 끝내면 열려요"), and TalkBack hears it on the button.
        assertNull("남은 확인이 있는 달", monthCloseNote(MonthStatus(YearMonth.of(2026, 10), 10, 2, 1, null), today, edited = 0))
        // Every note is one card line; its folded form may wrap.
        listOf(monthCloseNote(closed, today, 12)!!, folded, monthCloseNote(MonthStatus(YearMonth.of(2026, 8), 0, 0, 0, null), today, 0)!!,
            monthCloseNote(MonthStatus(YearMonth.of(2026, 11), 0, 0, 0, null), today, 0)!!).forEach { (line, more) ->
            assertOneLine(line, CARD_LINE_UNITS)
            more.forEach { assertFalse(it, it.endsWith(".")) }
        }
    }

    @Test fun theStepsGoAcrossOnlyWhereTheCardKeeps980Dp() {
        assertTrue("FULL의 1064 dp 카드", closeStepsAcross(1064.dp, 1f))
        assertTrue("열 없는 EXPANDED의 1056 dp", closeStepsAcross(1056.dp, 1f))
        assertFalse("896 dp는 행으로", closeStepsAcross(896.dp, 1f))
        assertFalse("1.3배 글꼴의 998 dp는 768 상당", closeStepsAcross(998.dp, 1.3f))
        assertTrue("작은 글꼴은 1배로 셈", closeStepsAcross(980.dp, .85f))
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
        assertTrue("운행이 있는 달의 단계는 대기가 아님", (open + ready + closed).none { it.idle })
        assertEquals("Excel 저장", ready[2].action)
    }

    @Test fun aMonthWithoutTripsShowsNothingDoneAndOffersNoExcelSave() {
        val empty = monthCloseSteps(MonthStatus(YearMonth.of(2026, 11), 0, 0, 0, null))
        assertEquals("운행이 없는 달은 완료 표시가 없음", listOf(false, false, false, false), empty.map { it.done })
        assertEquals(listOf(true, true, true, true), empty.map { it.idle })
        assertEquals(listOf("운행이 없어요", "확인할 결제 없음", "저장할 운행이 없어요", "운행이 없어요"), empty.map { it.detail })
        assertTrue("저장할 운행이 없으면 ③에 저장 버튼이 없음", empty.all { it.action == null })
        // The column's Excel 저장 reads the same rule, so the two buttons can never disagree about an empty range.
        assertFalse(excelSaveAvailable(0)); assertTrue(excelSaveAvailable(1))
        // A card question that is really pending still shows, with its action, in a month without trips.
        val pending = monthCloseSteps(MonthStatus(YearMonth.of(2026, 11), 0, 0, 2, null))
        assertFalse(pending[1].done); assertFalse(pending[1].idle)
        assertEquals("2건 남음", pending[1].detail); assertEquals("확인하기", pending[1].action)
        assertTrue(pending[0].idle && pending[2].idle && pending[3].idle)
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
