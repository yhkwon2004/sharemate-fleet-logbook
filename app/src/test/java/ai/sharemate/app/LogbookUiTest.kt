package ai.sharemate.app

import androidx.compose.ui.unit.Dp
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

/**
 * Pure parts of the car shell: the header pill's line and tone and the driver chip's avatar, the log's filter labels and empty
 * state, the settings form's columns, and the one period 월간·Excel shows (its range, stepper, close card, file name and
 * tidy-up month).
 */
class LogbookUiTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(month: Int, day: Int, hour: Int) = LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    @Test fun statusPillPutsFailuresFirstThenWhatTheCarIsDoing() {
        val ready = TripLogState(loaded = true, signal = SignalStatus.READY)
        assertEquals("자동 기록 대기" to PillTone.READY, statusPillInfo(ready, fresh = true, status = "수신 중"))
        assertEquals("저장 실패가 가장 먼저", "기록 저장 확인 필요" to PillTone.NEGATIVE, statusPillInfo(ready.copy(error = "disk"), fresh = true, status = "수신 중"))
        assertEquals("기록 저장 확인 필요" to PillTone.NEGATIVE, statusPillInfo(ready.copy(loadFailed = true), fresh = true, status = "수신 중"))
        assertEquals(SignalStatus.DENIED.label to PillTone.NEGATIVE, statusPillInfo(ready.copy(signal = SignalStatus.DENIED), fresh = true, status = "수신 중"))
        assertEquals("연결 중은 조용한 톤", SignalStatus.STARTING.label to PillTone.QUIET, statusPillInfo(ready.copy(signal = SignalStatus.STARTING), fresh = false, status = "확인 필요"))
        assertEquals("오래된 신호는 대기로 읽히지 않음", "마지막 수신 5분 전" to PillTone.NEGATIVE, statusPillInfo(ready, fresh = false, status = "마지막 수신 5분 전"))
        val recording = ready.copy(signal = SignalStatus.RECORDING, data = TripLogData(open = Trip(id("o"), at(11, 4, 9), null, 100.0, 103.2)))
        assertEquals("기록 중 · 3.2 km" to PillTone.POSITIVE, statusPillInfo(recording, fresh = true, status = "수신 중"))
        assertEquals("기록 중이면 신호가 오래돼도 기록 중", "기록 중 · 3.2 km" to PillTone.POSITIVE, statusPillInfo(recording, fresh = false, status = "확인 필요"))
        assertEquals("시연 데이터" to PillTone.DEMO, statusPillInfo(ready.copy(signal = SignalStatus.DEMO), fresh = true, status = "수신 중"))
    }

    @Test fun theExcelPreviewFillsThePaneUnderTheViewTabsAndNeverDropsUnder480() {
        // FULL car pane, about 950 dp: less the pinned save bar (72 + 16) and the view tabs with the range line (66 + 16 + 16 + 16).
        assertEquals(748f, excelPreviewHeight(950.dp).value, .01f)
        assertEquals("큰 글꼴은 범위 줄이 커진 만큼만 줄어듦", 743.2f, excelPreviewHeight(950.dp, 1.3f).value, .01f)
        assertEquals("짧은 창은 480에서 멈추고 창이 더 스크롤", EXCEL_PREVIEW_MIN_HEIGHT, excelPreviewHeight(400.dp))
        assertEquals(480f, EXCEL_PREVIEW_MIN_HEIGHT.value, .01f)
        assertEquals("큰 글꼴의 최소 높이는 글꼴만큼 커짐", 624f, excelPreviewHeight(400.dp, 1.3f).value, .01f)
        assertEquals("높이가 정해지지 않으면 최소값", EXCEL_PREVIEW_MIN_HEIGHT, excelPreviewHeight(Dp.Infinity))
        assertEquals("저장 바 72 + 위 간격 16", 88f, SAVE_BAR_RESERVE.value, .01f)
    }

    @Test fun logFilterLabelsCarryTheCountsAndShortenWhereTheRowIsNarrow() {
        assertEquals(listOf("전체 9", "점검 필요 7", "자동 반영 0"), logFilterLabels(9, 7, 0, wide = true))
        assertEquals(listOf("전체 9", "점검 필요 7", "자동 0"), logFilterLabels(9, 7, 0, wide = false))
    }

    @Test fun anEmptyLogSaysWhatTheMonthIsMissingAndOffersClearingOnlyWhenSomethingNarrowsIt() {
        val today = LocalDate.of(2026, 11, 9)
        val november = YearMonth.of(2026, 11)
        val plain = logEmptyCopy(0, november, 0, null, today)
        assertEquals("11월 운행 기록이 없어요", plain.title); assertNull(plain.detail)
        assertFalse("전체·전체 직원이면 필터 해제가 없음", plain.clearable)
        val employee = logEmptyCopy(0, november, 0, "영업 · 권", today)
        assertEquals("11월 영업 · 권 님의 운행 기록이 없어요", employee.title); assertTrue(employee.clearable)
        val checked = logEmptyCopy(1, november, 4, null, today)
        assertEquals("11월 점검할 운행이 없어요", checked.title); assertEquals("계기판 누적거리가 끊김 없이 이어져요", checked.detail)
        assertTrue(checked.clearable)
        assertEquals("운행이 없으면 점검 필요도 운행이 없다고 말함", "11월 운행 기록이 없어요", logEmptyCopy(1, november, 0, null, today).title)
        assertEquals("11월 카드 결제가 자동으로 들어간 운행이 없어요", logEmptyCopy(2, november, 3, null, today).title)
        assertEquals("지난해 달은 연도를 붙임", "2025년 12월 운행 기록이 없어요", logEmptyCopy(0, YearMonth.of(2025, 12), 0, null, today).title)
    }

    private val reportToday = LocalDate.of(2026, 11, 9)
    private fun month(year: Int, month: Int, yearly: Boolean = false) = ReportPeriod(YearMonth.of(year, month), yearly)

    @Test fun aPastPeriodRunsToItsLastDayAndTheRunningOneStopsToday() {
        val today = reportToday
        val october = reportRange(month(2026, 10), today)
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 31), october)
        val november = reportRange(month(2026, 11), today)
        assertEquals("진행 중인 달은 오늘까지", LocalDate.of(2026, 11, 1) to today, november)
        assertEquals("윤년 2월은 29일까지", LocalDate.of(2024, 2, 1) to LocalDate.of(2024, 2, 29), reportRange(month(2024, 2), today))
        assertEquals(LocalDate.of(2025, 1, 1) to LocalDate.of(2025, 12, 31), reportRange(month(2025, 11, yearly = true), today))
        val year = reportRange(month(2026, 11, yearly = true), today)
        assertEquals("진행 중인 해는 오늘까지", LocalDate.of(2026, 1, 1) to today, year)
        // The same ranges as Gleo's this_month · this_year and the assistant's 지난달.
        assertEquals(gleoPeriod("this_month", today), november)
        assertEquals(gleoPeriod("this_year", today), year)
        val lastMonth = parseIntent("지난달 요약해줘", today) as AssistantIntent.Summary
        assertEquals(lastMonth.from to lastMonth.to, october)
    }

    @Test fun aSavedMonthIsReadBackNeverPastThisMonth() {
        val today = reportToday
        assertEquals("시계를 되돌려도 아직 오지 않은 달은 보이지 않음", YearMonth.of(2026, 11), reportPeriod("2026-12", false, today).month)
        assertEquals("읽을 수 없는 값은 이번 달", YearMonth.of(2026, 11), reportPeriod("x", false, today).month)
        assertEquals(month(2026, 10, yearly = true), reportPeriod("2026-10", true, today))
        assertEquals("범위도 이번 달로 읽음", LocalDate.of(2026, 11, 1) to today, reportRange(reportPeriod("2026-12", false, today), today))
    }

    @Test fun theStepperStopsAtThisMonthAndThisYear() {
        val today = reportToday
        assertTrue(reportCanGoForward(month(2026, 10), today))
        assertTrue(reportCanGoForward(month(2025, 11, yearly = true), today))
        assertFalse("이번 달에서는 더 못 감", reportCanGoForward(month(2026, 11), today))
        assertFalse("올해의 어느 달에서든 연간은 더 못 감", reportCanGoForward(month(2026, 3, yearly = true), today))
        assertEquals(month(2025, 12), reportStep(month(2026, 1), forward = false, today))
        assertEquals(month(2025, 11, yearly = true), reportStep(month(2026, 11, yearly = true), forward = false, today))
        assertEquals("한 해 앞은 이번 달에서 멈춤", month(2026, 11, yearly = true), reportStep(month(2025, 12, yearly = true), forward = true, today))
        assertEquals(month(2026, 11), reportStep(month(2026, 11), forward = true, today))
    }

    @Test fun theArrowsSayWhichWayAndWhyNextIsOff() {
        val today = reportToday
        assertEquals(StepperCopy("2026년 10월", "이전 달", "다음 달"), reportStepperCopy(month(2026, 10), today))
        assertEquals("다음 달 · 아직 오지 않은 달", reportStepperCopy(month(2026, 11), today).forward)
        assertEquals(StepperCopy("2025년", "이전 해", "다음 해"), reportStepperCopy(month(2025, 11, yearly = true), today))
        assertEquals("다음 해 · 아직 오지 않은 해", reportStepperCopy(month(2026, 11, yearly = true), today).forward)
    }

    /** September has one unclassified trip, October is closed, August and November have no trips. */
    private fun reportData(): TripLogData {
        val driver = Driver(id("d"), "영업", "권")
        fun trip(seed: String, month: Int, day: Int, start: Double, purpose: TripPurpose = TripPurpose.BUSINESS) =
            Trip(id(seed), at(month, day, 9), at(month, day, 10), start, start + 10.0, driverId = driver.id, purpose = purpose, updatedAt = at(month, day, 10))
        val open = TripLogData(drivers = listOf(driver), trips = listOf(trip("s", 9, 10, 100.0, TripPurpose.UNCLASSIFIED), trip("o1", 10, 3, 110.0), trip("o2", 10, 20, 120.0)))
        return closeMonth(open, YearMonth.of(2026, 10), at(11, 1, 9), zone)
    }

    @Test fun theCloseCardFollowsTheMonthOnScreen() {
        val today = reportToday
        val data = reportData()
        assertNull("연간에는 마감 카드가 없음", reportCloseStatus(data, month(2026, 10, yearly = true), today, zone))
        assertNotNull(reportCloseStatus(data, month(2026, 10), today, zone)?.closed)
        assertEquals(1, reportCloseStatus(data, month(2026, 9), today, zone)?.issues)
        assertEquals("이번 달은 운행이 없어도 카드가 있음", 0, reportCloseStatus(data, month(2026, 11), today, zone)?.trips)
        assertEquals("25일 뒤에도 같음", 0, reportCloseStatus(data, month(2026, 11), LocalDate.of(2026, 11, 26), zone)?.trips)
        assertNull("보여 줄 것이 없는 지난 달은 카드가 없음", reportCloseStatus(data, month(2026, 8), today, zone))
        val question = CardCharge(id("c"), at(8, 20, 12), true, 4_500, "아이파킹 강남", null, CardSource.NOTIFICATION, CostCategory.PARKING, ChargeState.REVIEW)
        assertEquals("남은 카드 확인이 있으면 지난 달도 카드가 있음", 1, reportCloseStatus(data.copy(charges = listOf(question)), month(2026, 8), today, zone)?.reviews)
        // October's trips deleted after the close (deleteTrip has no closed-month guard): the card stays, so 마감 해제 is still reachable.
        val emptied = data.copy(trips = data.trips.filter { it.id == id("s") })
        val october = reportCloseStatus(emptied, month(2026, 10), today, zone)
        assertEquals("운행을 지운 마감 달도 카드가 남음", 0, october?.trips)
        assertNotNull(october?.closed)
    }

    @Test fun theRowAndStepThreeAgreeOnEveryMonth() {
        val data = reportData()
        for (m in 8..11) {
            val (from, to) = reportRange(month(2026, m), reportToday)
            assertEquals("${m}월", excelSaveAvailable(monthStatus(data, YearMonth.of(2026, m), zone).trips), excelSaveAvailable(tripsIn(data.trips, from, to, zone).size))
        }
    }

    @Test fun tidyingUpOpensTheEarliestMonthThatNeedsAPurposeOrDriver() {
        val fallback = YearMonth.of(2026, 11)
        val done = Trip(id("a"), at(1, 5, 9), at(1, 5, 10), 80.0, 90.0, driverId = id("d"), purpose = TripPurpose.BUSINESS)
        val trips = listOf(done,
            Trip(id("m"), at(3, 4, 9), at(3, 4, 10), 90.0, 100.0, driverId = id("d"), purpose = TripPurpose.UNCLASSIFIED),
            Trip(id("j"), at(7, 2, 9), at(7, 2, 10), 100.0, 110.0, driverId = null, purpose = TripPurpose.BUSINESS))
        assertEquals("미분류 3월이 직원 미지정 7월보다 먼저", YearMonth.of(2026, 3), tidyMonth(trips, fallback, zone))
        assertEquals("정리할 운행이 없으면 화면의 달", fallback, tidyMonth(listOf(done), fallback, zone))
        // March is both the earliest trip and the only unclassified one, so the driverless half needs a case of its own.
        assertEquals("직원 미지정만 남아도 그 달", YearMonth.of(2026, 7), tidyMonth(listOf(done, trips.last()), fallback, zone))
        assertEquals("목록 순서와 상관없이 가장 이른 달", YearMonth.of(2026, 3), tidyMonth(trips.reversed(), fallback, zone))
    }

    @Test fun theExcelFileNameCarriesTheExactRange() {
        val profile = FleetProfile(plateNumber = "12가 3456")
        assertEquals("운행기록부_12가_3456_2026-10-01_2026-10-31.xlsx", logbookFileName(profile, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
        assertEquals("시연_운행기록부_12가_3456_2026-11-01_2026-11-09.xlsx", logbookFileName(profile.copy(demoMode = true), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 9)))
        assertEquals("운행기록부_차량_2025-01-01_2025-12-31.xlsx", logbookFileName(FleetProfile(), LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)))
    }

    @Test fun theTaxCardNamesTheYearItReads() {
        assertEquals("비용 참고 · 올해 업무사용비율 기준", taxEyebrow(2026, reportToday))
        assertEquals("비용 참고 · 2025년 업무사용비율 기준", taxEyebrow(2025, reportToday))
    }

    @Test fun theTaxCardAsksForTheCostOnlyWhenItIsMissing() {
        val ask = "연간 차량 비용을 입력하면 운행기록부의 효과를 계산해요"
        assertEquals(ask, taxCardBlocker(null, .8, 2026, reportToday))
        assertEquals("비용이 없으면 운행이 없어도 비용부터", ask, taxCardBlocker(null, null, 2025, reportToday))
        // A year without trips has no ratio, and no setting fixes that: the card names the year instead of asking for the cost again.
        assertEquals("비용이 있으면 운행 없는 해라고 말함", "2025년 운행 기록이 없어 업무사용비율을 계산할 수 없어요", taxCardBlocker(15_000_000, null, 2025, reportToday))
        assertEquals("올해 운행 기록이 없어 업무사용비율을 계산할 수 없어요", taxCardBlocker(15_000_000, null, 2026, reportToday))
        assertNull("비용과 비율이 있으면 계산함", taxCardBlocker(15_000_000, .8, 2025, reportToday))
    }

    @Test fun anEmptyPeriodHasNothingToCheckRatherThanZeroItems() {
        assertEquals("운행 없는 기간", "점검할 운행이 없어요", auditHeadline(trips = 0, issues = 0))
        assertEquals("기록 기본 점검 완료", auditHeadline(trips = 9, issues = 0))
        assertEquals("확인이 필요한 항목 2개", auditHeadline(trips = 9, issues = 2))
    }

    @Test fun settingsFormsPairFieldsOnlyOnWideWindowsAtTheNormalFont() {
        assertEquals(2, settingsFieldColumns(carLayout(1680.dp), 1f))
        assertEquals("EXPANDED도 두 열", 2, settingsFieldColumns(carLayout(1200.dp), 1.15f))
        assertEquals("1.3배 글꼴은 항상 한 열", 1, settingsFieldColumns(carLayout(1680.dp), 1.3f))
        assertEquals("MEDIUM 이하는 한 열", 1, settingsFieldColumns(carLayout(1000.dp), 1f))
    }

    @Test fun theLogSplitsOnlyWhenThePageHasRoom() {
        // FULL beside the 지금 column: the 1112-dp page, less the 24-dp gap, gives the list 48 % (the detail the other 566).
        assertEquals(522.24f, logListWidth(carLayout(1680.dp), 1112.dp, 24.dp, 1f)!!.value, .01f)
        assertNull("1400 dp 창의 832 페이지는 옆 시트", logListWidth(carLayout(1400.dp), 832.dp, 24.dp, 1f))
        assertNull("1.3배 글꼴의 1046 페이지(805 상당)는 옆 시트", logListWidth(carLayout(1680.dp / 1.3f), 1046.dp, 20.dp, 1.3f))
        assertEquals("열이 없는 EXPANDED", 520.32f, logListWidth(carLayout(1200.dp), 1104.dp, 20.dp, 1f)!!.value, .01f)
        assertNull("MEDIUM은 옆 시트", logListWidth(carLayout(1000.dp), 936.dp, 16.dp, 1f))
    }

    @Test fun settingsCardsGoTwoUpWhenThePageHasRoom() {
        assertEquals("FULL: 2 × 544", 2, settingsCardColumns(carLayout(1680.dp), 1112.dp, 1f))
        assertEquals("1.3배 글꼴의 1046 페이지도 두 장", 2, settingsCardColumns(carLayout(1680.dp / 1.3f), 1046.dp, 1.3f))
        assertEquals("900 ÷ 1.3 = 692는 한 장", 1, settingsCardColumns(carLayout(1680.dp / 1.3f), 900.dp, 1.3f))
        assertEquals("MEDIUM은 한 장", 1, settingsCardColumns(carLayout(1000.dp), 936.dp, 1f))
    }

    @Test fun theHeaderChipDropsItsAvatarBeforeItsCaptionWouldWrap() {
        assertTrue("넓은 헤더", driverChipAvatar(230.dp, 1f))
        assertTrue("140 dp: 아바타와 여백 74 + 글자 66", driverChipAvatar(140.dp, 1f))
        assertFalse("139 dp는 글자만", driverChipAvatar(139.dp, 1f))
        assertFalse("1.3배 글꼴은 160 dp부터", driverChipAvatar(150.dp, 1.3f))
        assertTrue(driverChipAvatar(160.dp, 1.3f))
        assertTrue("작은 글꼴은 1배로 셈", driverChipAvatar(140.dp, .85f))
        assertTrue("너비 제한 없음", driverChipAvatar(Dp.Infinity, 1f))
    }

    @Test fun theTightestHeaderKeepsTheDriversInitialsInsteadOfEllipses() {
        assertEquals(DriverChipMode.AVATAR_AND_NAME, driverChipMode(140.dp, 1f))
        assertEquals("글자 66 + 여백 40", DriverChipMode.NAME, driverChipMode(106.dp, 1f))
        assertEquals(DriverChipMode.NAME, driverChipMode(139.dp, 1f))
        assertEquals("말줄임표만 남을 폭에서는 이니셜", DriverChipMode.AVATAR, driverChipMode(105.dp, 1f))
        assertEquals("좁은 운행기록 헤더(약 58 dp)", DriverChipMode.AVATAR, driverChipMode(58.dp, 1f))
        assertEquals("1.3배 글꼴은 글자에 더 필요", DriverChipMode.AVATAR, driverChipMode(120.dp, 1.3f))
        assertEquals(DriverChipMode.NAME, driverChipMode(126.dp, 1.3f))
    }
}
