package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 검토's 증빙 준비 상태 as a list of what is left (2.15, docs/design/DESIGN_2_15.md §5.2): the kinds and their counts in
 * 점검 필요's order, the issues the card counts, and the card's words.
 */
class AuditListTest {
    private val day = 1_790_000_000_000L
    private val hour = 3_600_000L

    /** A 10-km, 30-minute trip [days] after the base day, on the odometer from [from] (a plausible speed, so no speed issue). */
    private fun trip(seed: String, days: Int, from: Double, purpose: TripPurpose = TripPurpose.BUSINESS, driver: String? = "kim") =
        Trip(seed, day + days * 24 * hour, day + days * 24 * hour + hour / 2, from, from + 10.0, driverId = driver, purpose = purpose)

    @Test fun theKindsComeInTheOrderOfTheListTheyOpen() {
        val (d1, d2, d3) = listOf(trip("1", 1, 0.0), trip("2", 2, 10.0), trip("3", 3, 20.0))
        val byId = listOf(d1, d2, d3).associateBy { it.id }
        val issues = listOf(
            TripIssue(IssueType.ZERO_DISTANCE, d1.id), TripIssue(IssueType.UNCLASSIFIED, d1.id), TripIssue(IssueType.ODOMETER_GAP, d3.id, 3.0, d2.id),
            TripIssue(IssueType.NO_DRIVER, d2.id), TripIssue(IssueType.UNCLASSIFIED, d2.id), TripIssue(IssueType.RECOVERED, d2.id),
        )
        // 점검 필요: 사용자 미지정 (d2), 목적 미분류 (d2), 목적 미분류 (d1), then 기록되지 않은 주행 (d3), 종료 시각 추정 (d2), 0 km (d1).
        assertEquals("필수가 먼저, 각 부분은 가장 최근 항목의 종류부터",
            listOf(AuditKind(IssueType.NO_DRIVER, 1), AuditKind(IssueType.UNCLASSIFIED, 2), AuditKind(IssueType.ODOMETER_GAP, 1),
                AuditKind(IssueType.RECOVERED, 1), AuditKind(IssueType.ZERO_DISTANCE, 1)),
            auditBreakdown(issues, byId))
        assertEquals("issueOrder의 첫 종류가 첫 행", issueOrder(issues, byId).first().type, auditBreakdown(issues, byId).first().type)
        assertEquals("건수의 합은 항목 수", issues.size, auditBreakdown(issues, byId).sumOf { it.count })
    }

    @Test fun withoutTheTripsTheRequiredKindsStillLead() {
        val issues = listOf(TripIssue(IssueType.ODOMETER_GAP, "a", 1.0, "z"), TripIssue(IssueType.UNCLASSIFIED, "a"), TripIssue(IssueType.ODOMETER_GAP, "b", 2.0, "a"),
            TripIssue(IssueType.CARD_ON_PERSONAL, "b"), TripIssue(IssueType.NO_DRIVER, "b"))
        assertEquals(listOf(AuditKind(IssueType.UNCLASSIFIED, 1), AuditKind(IssueType.NO_DRIVER, 1), AuditKind(IssueType.ODOMETER_GAP, 2),
            AuditKind(IssueType.CARD_ON_PERSONAL, 1)), auditBreakdown(issues))
        assertEquals(listOf(true, true, false, false), auditBreakdown(issues).map { it.required })
    }

    @Test fun nothingLeftIsNoKind() {
        assertTrue(auditBreakdown(emptyList()).isEmpty())
        assertTrue(auditBreakdown(emptyList(), mapOf("a" to trip("a", 1, 0.0))).isEmpty())
    }

    @Test fun theCardCountsWhatTheReviewListShows() {
        // October: a trip on the 30th. November: a gap before its first trip, then an unclassified one without a driver.
        val october = trip("oct", 0, 0.0)
        val first = trip("nov1", 2, 25.0)
        val second = trip("nov2", 3, 35.0, purpose = TripPurpose.UNCLASSIFIED, driver = null)
        val all = listOf(october, first, second)
        val november = listOf(first, second)
        val issues = periodAudit(all, november)
        assertEquals("앞 달과 이어지지 않은 구간도 셈", setOf(IssueType.ODOMETER_GAP, IssueType.UNCLASSIFIED, IssueType.NO_DRIVER), issues.map { it.type }.toSet())
        assertTrue("기간의 운행만", issues.all { it.tripId in setOf(first.id, second.id) })
        assertEquals("운행기록이 세는 방식과 같음", auditTrips(all).filter { it.tripId in november.map(Trip::id) }, issues)
        assertTrue("기간만 감사하면 놓치는 구간", auditTrips(november).none { it.type == IssueType.ODOMETER_GAP })
        assertTrue("운행 없는 기간", periodAudit(all, emptyList()).isEmpty())
        assertTrue("빈 기록", periodAudit(emptyList(), emptyList()).isEmpty())
    }

    @Test fun aRowSaysItsKindItsCountAndWhetherACloseNeedsIt() {
        assertEquals("3건", auditCountText(3))
        assertEquals("목적 미분류 3건, 마감 전 필수", auditKindSpoken(AuditKind(IssueType.UNCLASSIFIED, 3)))
        assertEquals("기록되지 않은 주행 1건, 참고", auditKindSpoken(AuditKind(IssueType.ODOMETER_GAP, 1)))
    }

    @Test fun theCardsWordsFitTheirPlaces() {
        val titleSmallLineUnits = CARD_LINE_UNITS * 16 / 19
        // A row's title is the kind's label (titleSmall) beside its count and chevron; its line is the issue tag's word.
        IssueType.entries.forEach { assertOneLine(it.label, titleSmallLineUnits) }
        listOf(LogCopy.REQUIRED, LogCopy.REFERENCE, auditCountText(20_000)).forEach { assertOneLine(it, CARD_LINE_UNITS) }
        with(ReportCopy) {
            assertOneLine(AUDIT_ALL_CLEAR, titleSmallLineUnits)
            assertOneLine(AUDIT_ROW_ACTION, CARD_LINE_UNITS)
            // The sentence about the Excel sheet moved behind the ⓘ row (spec §5.2).
            assertTrue(AUDIT_NOTE_MORE.contains(AUDIT_LINE))
            AUDIT_NOTE_MORE.forEach { assertTrue(it, !it.trimEnd().endsWith(".")) }
        }
        // The spoken form says at least what the row shows.
        IssueType.entries.forEach { type ->
            val kind = AuditKind(type, 7)
            val spoken = auditKindSpoken(kind)
            assertTrue(spoken, spoken.startsWith(type.label) && spoken.contains(auditCountText(7)) && !spoken.endsWith("."))
        }
    }
}
