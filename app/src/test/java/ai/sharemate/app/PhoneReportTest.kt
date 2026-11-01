package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** Pure helpers behind the phone's 현황 tab: month paging, close steps, issue grouping and the employee drill-down. */
class PhoneReportTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val kim = Driver(id("kim"), "영업", "김서연")
    private val park = Driver(id("park"), "총무", "박지훈")
    private fun trip(seed: String, day: Int, driverId: String?, history: List<String> = emptyList()) =
        Trip(id(seed), at(day, 9), at(day, 10), 100.0 + day, 110.0 + day, driverId = driverId, purpose = TripPurpose.BUSINESS, history = history, updatedAt = at(day, 10))

    @Test fun nextMonthStopsAtTheCurrentMonth() {
        val today = LocalDate.of(2026, 11, 2)
        assertTrue(canGoForward(YearMonth.of(2026, 10), today))
        assertFalse("이번 달에서는 더 못 감", canGoForward(YearMonth.of(2026, 11), today))
        assertFalse(canGoForward(YearMonth.of(2026, 12), today))
    }

    @Test fun closeStepsCompleteInOrderAndTheLastTwoTogether() {
        val open = monthCloseSteps(MonthStatus(YearMonth.of(2026, 10), trips = 12, unfinished = 3, reviews = 1, closed = null))
        assertEquals(listOf("목적·사용자 확인 3건", "카드 확인 1건", "Excel 저장", "마감"), open.map { it.label })
        assertEquals(listOf(false, false, false, false), open.map { it.done })
        val ready = monthCloseSteps(MonthStatus(YearMonth.of(2026, 10), 12, 0, 0, null))
        assertEquals(listOf(true, true, false, false), ready.map { it.done })
        val closed = monthCloseSteps(MonthStatus(YearMonth.of(2026, 10), 12, 0, 0, MonthClose("2026-10", at(31, 18), 12, 300.0, 200.0, 0)))
        assertEquals(listOf(true, true, true, true), closed.map { it.done })
        assertTrue(closedLabel(MonthClose("2026-10", at(31, 18), 12, 300.0, 200.0, 0)).startsWith("마감됨 · "))
    }

    @Test fun issuesGroupByKindWithThePhoneActionableOnesFirst() {
        val issues = listOf(
            TripIssue(IssueType.ODOMETER_GAP, id("a"), gapKm = 3.2), TripIssue(IssueType.NO_DRIVER, id("b")), TripIssue(IssueType.UNCLASSIFIED, id("c")),
            TripIssue(IssueType.NO_DRIVER, id("d")), TripIssue(IssueType.CARD_ON_PERSONAL, id("e")), TripIssue(IssueType.RECOVERED, id("f")),
        )
        val groups = groupIssues(issues)
        assertEquals(listOf(IssueType.UNCLASSIFIED, IssueType.NO_DRIVER, IssueType.CARD_ON_PERSONAL, IssueType.ODOMETER_GAP, IssueType.RECOVERED), groups.map { it.first })
        assertEquals(2, groups[1].second.size)
        assertEquals("목적 정하기", issueActionLabel(IssueType.UNCLASSIFIED))
        assertEquals("직원 지정", issueActionLabel(IssueType.NO_DRIVER))
        assertEquals("운행 보기", issueActionLabel(IssueType.CARD_ON_PERSONAL))
        listOf(IssueType.ODOMETER_GAP, IssueType.ODOMETER_OVERLAP, IssueType.ZERO_DISTANCE, IssueType.IMPLAUSIBLE_SPEED, IssueType.RECOVERED).forEach {
            assertNull("차량에서만 처리: $it", issueActionLabel(it))
        }
    }

    @Test fun employeeDrillDownSeparatesUnassignedAndUnknownDrivers() {
        val trips = listOf(trip("a", 1, kim.id), trip("b", 2, park.id), trip("c", 3, null), trip("d", 4, id("left-company")))
        assertEquals(listOf(trips[0]), tripsOfDriver(trips, listOf(kim, park), kim.id))
        assertEquals("미지정 = 직원 없음 + 등록에서 지워진 직원", listOf(trips[2], trips[3]), tripsOfDriver(trips, listOf(kim, park), null))
        assertTrue(tripsOfDriver(trips, listOf(kim, park), id("nobody")).isEmpty())
    }

    @Test fun editsAfterCloseAreReadFromTheHistoryLine() {
        assertFalse(phoneEditedAfterClose(trip("a", 1, kim.id)))
        assertTrue(phoneEditedAfterClose(trip("b", 2, kim.id, history = listOf("휴대폰에서 목적 변경", "마감된 달의 기록 수정 · 11/2 09:10"))))
        assertEquals(CLOSED_MONTH_EDIT_HISTORY, "마감된 달의 기록 수정")
    }
}
