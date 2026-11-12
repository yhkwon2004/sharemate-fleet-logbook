package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * The words of 운행기록 on the car (2.13, docs/design/DESIGN_2_13_SPEC.md §7 and §10): a trip row's title, line and spoken
 * form, the trip the detail shows when nothing is tapped, the issue lines, tags and order, the toll chip, and the one-line
 * rule for every static line of [LogCopy].
 */
class LogCopyTest {
    // hm() reads the device's zone, so the trips are built in it: 02:26 is 02:26 wherever the test runs.
    private val zone = ZoneId.systemDefault()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private fun at(day: Int, hour: Int, minute: Int) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val kim = Driver(id("d"), "Sales", "Kim Jiwoo")

    /** An 8.0-km, one-minute trip on 10월 [day] at 02:26 unless told otherwise. */
    private fun trip(seed: String, day: Int = 29, start: String? = null, end: String? = null, destination: String? = null, km: Double = 8.0,
        expenses: List<TripExpense> = emptyList(), purpose: TripPurpose = TripPurpose.BUSINESS, demo: Boolean = false, fare: Long? = null,
        gate: Boolean = false) = Trip(id(seed), at(day, 2, 26), at(day, 2, 27), 12_345.0, 12_345.0 + km, startAddress = start, endAddress = end,
        destination = destination, driverId = kim.id, purpose = purpose, expenses = expenses, demo = demo, tollFareWon = fare,
        tollPasses = if (gate) listOf(TollPass(at(day, 2, 26) + 20_000, null)) else emptyList())

    private fun cost(type: ExpenseType, won: Long) = TripExpense(type, won, at(29, 3, 0), EditSource.DRIVER)

    @Test fun aRowIsTitledByItsRouteOrByItsDistanceAndTime() {
        assertEquals("수정구 시흥동 → 판교", tripRowTitle(trip("a", start = "수정구 시흥동", end = "판교")))
        assertEquals("목적지가 도착 주소보다 먼저", "수정구 시흥동 → 판교역", tripRowTitle(trip("b", start = "수정구 시흥동", end = "판교", destination = "판교역")))
        assertEquals("주소가 하나여도 그 주소", "판교", tripRowTitle(trip("c", end = "판교")))
        assertEquals("주소가 없으면 거리와 시간으로 구분", "8.0 km · 1분", tripRowTitle(trip("d")))
        assertEquals("빈 주소는 없는 것", "8.0 km · 1분", tripRowTitle(trip("e", start = " ", destination = "")))
    }

    @Test fun aRowLineCarriesThePersonAndTheCostButTheDistanceOnlyUnderARoute() {
        val routed = trip("a", start = "수정구 시흥동", destination = "판교")
        assertEquals("8.0 km · 1분 · 김", tripRowLine(routed, "김", edited = false))
        assertEquals("거리 제목 아래에는 거리를 되풀이하지 않음", "김", tripRowLine(trip("b"), "김", edited = false))
        assertEquals("8.0 km · 1분 · 사용자 미지정", tripRowLine(routed, null, edited = false))
        assertEquals("사용자 미지정", tripRowLine(trip("c"), " ", edited = false))
        val costly = routed.copy(expenses = listOf(cost(ExpenseType.PARKING, 4_500)))
        assertEquals("마감 후 수정 · 8.0 km · 1분 · 김 · 4,500원", tripRowLine(costly, "김", edited = true))
        val demo = costly.copy(demo = true)
        assertFalse("시연은 페이지 태그가 한 번 말함", tripRowLine(demo, kim.name, edited = false).contains("시연"))
        assertFalse("부서는 상세에서", tripRowLine(demo, kim.name, edited = false).contains("Sales"))
    }

    @Test fun aRowSpeaksAsOneStopWithTheDepartmentAndTheDemoFlag() {
        val costly = trip("a", start = "수정구 시흥동", destination = "판교", expenses = listOf(cost(ExpenseType.PARKING, 4_500)))
        assertEquals("02:26 출발, 수정구 시흥동, 판교, 8.0 km, 1분, 일반 업무, Sales · Kim Jiwoo, 경비 4,500원, 마감 후 수정",
            tripRowSpoken(costly, kim, edited = true))
        val demo = tripRowSpoken(trip("b", purpose = TripPurpose.UNCLASSIFIED, demo = true), null, edited = false)
        assertEquals("02:26 출발, 8.0 km, 1분, 목적 미분류, 사용자 미지정, 시연 데이터", demo)
        assertFalse("화살표는 읽지 않음", tripRowSpoken(costly, kim, edited = false).contains("→"))
    }

    @Test fun theDetailTitleNamesTheDayAndBothEnds() {
        assertEquals("10월 29일 (목) 02:26 – 02:27", tripDetailTitle(trip("a")))
        assertEquals("10월 29일 (목) 02:26 – 기록 중", tripDetailTitle(trip("b").copy(endAt = null)))
    }

    @Test fun theDetailFollowsTheListsFirstRowUntilARowIsTapped() {
        val (old, mid, new) = listOf(trip("o", day = 27), trip("m", day = 28), trip("n", day = 29))
        val trips = listOf(old, mid, new)
        val issues = listOf(TripIssue(IssueType.UNCLASSIFIED, mid.id), TripIssue(IssueType.ZERO_DISTANCE, old.id))
        val matched = listOf(new to emptyList<TripExpense>(), old to emptyList())
        assertEquals("전체: 가장 최근 운행", new.id, logTopId(0, trips, issues, matched))
        assertEquals("점검 필요: 첫 항목의 운행", mid.id, logTopId(1, trips, issues, matched))
        assertEquals("자동 반영: 첫 운행", new.id, logTopId(2, trips, issues, matched))
        assertNull(logTopId(0, emptyList(), issues, matched))
        assertNull(logTopId(1, trips, emptyList(), matched))
        assertNull(logTopId(2, trips, issues, emptyList()))

        val known = setOf(old.id, mid.id, new.id)
        assertEquals("누른 행이 먼저", old.id, logSelection(old.id, known, new.id, twoPane = true))
        assertEquals(old.id, logSelection(old.id, known, new.id, twoPane = false))
        assertEquals("두 칸이면 목록의 첫 행", new.id, logSelection(null, known, new.id, twoPane = true))
        assertNull("옆 시트는 스스로 열리지 않음", logSelection(null, known, new.id, twoPane = false))
        assertEquals("지운 운행이면 첫 행", new.id, logSelection(id("gone"), known, new.id, twoPane = true))
        assertNull(logSelection(id("gone"), known, new.id, twoPane = false))
        assertNull("빈 목록", logSelection(null, known, null, twoPane = true))
    }

    @Test fun theTotalsAreOneTag() {
        assertEquals("총 54.0 km · 업무 80% · 9건", logTotalsLine(LogbookTotals(54.0, 10.0, 33.2, 10.8, 0.0, 9, 0)))
        assertEquals("총 0.0 km · 업무 – · 0건", logTotalsLine(LogbookTotals(0.0, 0.0, 0.0, 0.0, 0.0, 0, 0)))
    }

    @Test fun everyIssueSaysWhatItFoundInOneLine() {
        val routed = trip("a", start = "수정구 시흥동", destination = "판교")
        IssueType.entries.forEach { type ->
            assertOneLine(issueLine(type, routed), CARD_LINE_UNITS)
            assertOneLine(issueLine(type, trip("b")), CARD_LINE_UNITS)
            assertTrue("긴 설명은 TalkBack에: ${type.label}", type.detail.length > issueLine(type, routed).length || issueRequired(type))
        }
        assertEquals("8.0 km · 수정구 시흥동 → 판교", issueLine(IssueType.UNCLASSIFIED, routed))
        assertEquals("8.0 km · 수정구 시흥동 → 판교", issueLine(IssueType.NO_DRIVER, routed))
        assertEquals("주소가 없으면 거리와 시간", "8.0 km · 1분", issueLine(IssueType.UNCLASSIFIED, trip("b")))
        assertEquals("시동만 켠 기록이면 삭제해도 돼요", issueLine(IssueType.ZERO_DISTANCE, routed))
        assertEquals("앞 운행과 계기판 거리가 이어지지 않아요", issueLine(IssueType.ODOMETER_GAP, routed))
        assertEquals("출발 계기판이 앞 운행 도착보다 작아요", issueLine(IssueType.ODOMETER_OVERLAP, routed))
        assertEquals("평균 시속 200 km를 넘어요", issueLine(IssueType.IMPLAUSIBLE_SPEED, routed))
        assertEquals("앱이 멈춰 마지막 수신 시각으로 끝냈어요", issueLine(IssueType.RECOVERED, routed))
        assertEquals("회사 규정에 따라 처리해 주세요", issueLine(IssueType.CARD_ON_PERSONAL, routed))
        assertEquals("기록되지 않은 주행 · 12.0 km", issueTitle(TripIssue(IssueType.ODOMETER_GAP, routed.id, 12.0, id("p"))))
        assertEquals("목적 미분류", issueTitle(TripIssue(IssueType.UNCLASSIFIED, routed.id)))
    }

    @Test fun onlyAPurposeAndAPersonKeepAMonthFromClosing() {
        assertEquals(setOf(IssueType.UNCLASSIFIED, IssueType.NO_DRIVER), IssueType.entries.filter(::issueRequired).toSet())
        assertEquals("필수 2건 · 참고 5건", issueCountLine(2, 5))
        assertEquals("참고 5건", issueCountLine(0, 5))
        assertEquals("필수 2건", issueCountLine(2, 0))
        assertEquals("", issueCountLine(0, 0))
    }

    @Test fun requiredIssuesComeFirstThenNewestFirst() {
        val (d27, d28, d29) = listOf(trip("27", day = 27), trip("28", day = 28), trip("29", day = 29))
        val byId = listOf(d27, d28, d29).associateBy { it.id }
        val gap29 = TripIssue(IssueType.ODOMETER_GAP, d29.id, 3.0, d28.id)
        val purpose27 = TripIssue(IssueType.UNCLASSIFIED, d27.id)
        val driver28 = TripIssue(IssueType.NO_DRIVER, d28.id)
        val zero27 = TripIssue(IssueType.ZERO_DISTANCE, d27.id)
        val purpose29 = TripIssue(IssueType.UNCLASSIFIED, d29.id)
        val driver29 = TripIssue(IssueType.NO_DRIVER, d29.id)
        assertEquals(listOf(purpose29, driver29, driver28, purpose27, gap29, zero27),
            issueOrder(listOf(gap29, purpose27, driver28, zero27, purpose29, driver29), byId))
        assertEquals(emptyList<TripIssue>(), issueOrder(emptyList(), byId))
    }

    @Test fun theTollChipOffersTheNavigationsFareOnlyForAGatedTripWithoutAToll() {
        assertEquals(3_600L, tollSuggestion(trip("a", fare = 3_600, gate = true)))
        assertNull("게이트를 지나지 않음", tollSuggestion(trip("b", fare = 3_600)))
        assertNull("요금 정보 없음", tollSuggestion(trip("c", gate = true)))
        assertNull("통행료가 이미 있음", tollSuggestion(trip("d", fare = 3_600, gate = true, expenses = listOf(cost(ExpenseType.TOLL, 3_600)))))
        assertEquals("다른 비용은 상관없음", 3_600L, tollSuggestion(trip("e", fare = 3_600, gate = true, expenses = listOf(cost(ExpenseType.PARKING, 4_500)))))
        assertEquals("통행료 3,600원 · 내비 예상", tollChipText(3_600))
    }

    @Test fun theDetailsSmallLinesAreBuiltFromTheTrip() {
        assertEquals(LogCopy.ODOMETER_READ, odometerNote(TripOrigin.AUTO))
        assertEquals(LogCopy.ODOMETER_FILLED, odometerNote(TripOrigin.GAP_FILL))
        assertEquals("GS칼텍스 · 카드 자동 매칭", expenseLine(TripExpense(ExpenseType.FUEL, 45_000, 0, EditSource.CARD, "c", "GS칼텍스")))
        assertEquals("직접 선택", expenseLine(TripExpense(ExpenseType.PARKING, 4_500, 0, EditSource.DRIVER)))
        assertEquals("수정 이력 3건", historyLine(3))
        assertEquals("주차 4,500원을 추가했어요", expenseAddedLine(ExpenseType.PARKING, 4_500))
        assertEquals("누락 구간 12.0 km를 추가했어요", gapAddedLine(12.0))
    }

    @Test fun everyLineOfTheLogFitsItsPlace() {
        // A row's line beside the time and the purpose tag; its title gets 16 units.
        val routed = trip("a", start = "수정구 시흥동", destination = "판교", expenses = listOf(cost(ExpenseType.PARKING, 4_500)))
        listOf(tripRowLine(routed, kim.name, edited = false), tripRowLine(trip("b"), kim.name, edited = true),
            tripRowLine(routed.copy(expenses = emptyList()), kim.name, edited = true)).forEach { assertOneLine(it, LOG_ROW_LINE_UNITS) }
        listOf(tripRowTitle(routed), tripRowTitle(trip("c"))).forEach { assertOneLine(it, 16.0) }
        with(LogCopy) {
            // Card lines, ⓘ lines and tags.
            listOf(EMPTY_ISSUES_LINE, ODOMETER_READ, ODOMETER_FILLED, COSTS_EMPTY, DRIVER_EMPTY, REQUIRED, REFERENCE, DEMO, NO_DRIVER, EDITED,
                FILL_GAP, AMOUNT_HINT, historyLine(120), tollChipText(10_000_000)).forEach { assertOneLine(it, CARD_LINE_UNITS) }
            // Titles: the empty card's (titleSmall) and the detail's sections.
            listOf(EMPTY_TRIPS, EMPTY_ISSUES, EMPTY_MATCHED).forEach { assertOneLine(it, CARD_LINE_UNITS * 16 / 19) }
            listOf(PURPOSE, COSTS, NOTE, DETAIL).forEach { title ->
                assertOneLine(title, CARD_LINE_UNITS)
                assertTrue("$title: 8음절 이하", title.count { it in '가'..'힣' } <= 8)
            }
            DELETE_LINES.forEach { assertOneLine(it, DIALOG_LINE_UNITS) }
            listOf(NOTE_SAVED, expenseAddedLine(ExpenseType.FUEL, 10_000_000), gapAddedLine(1_234.5)).forEach { assertOneLine(it, FOLLOW_UP_UNITS) }
            // Every spoken form says at least what its line shows.
            assertTrue(SPOKEN_BEFORE.startsWith(BEFORE)); assertTrue(SPOKEN_AFTER.startsWith(AFTER))
            listOf(SPOKEN_CHANGE to CHANGE, SPOKEN_ASSIGN to ASSIGN, SPOKEN_DELETE to DELETE).forEach { (spoken, line) ->
                assertTrue(spoken, spoken.endsWith(line) && spoken.length > line.length)
            }
        }
    }
}
