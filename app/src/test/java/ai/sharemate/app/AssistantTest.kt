package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

class AssistantTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 10, 30)
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val now = at(30, 15)
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private fun trip(seed: String, day: Int, hour: Int, purpose: TripPurpose = TripPurpose.UNCLASSIFIED, destination: String? = "판교 연구소") =
        Trip(id(seed), at(day, hour), at(day, hour + 1), 100.0, 110.0, destination = destination, purpose = purpose, updatedAt = at(day, hour + 1))

    @Test fun understandsTheThreeGleoActionsInKorean() {
        assertEquals(AssistantIntent.SetPurpose(TripPurpose.BUSINESS, TripScope.Latest), parseIntent("방금 운행 업무로 기록해줘", today))
        assertEquals(AssistantIntent.SetPurpose(TripPurpose.COMMUTE, TripScope.Latest), parseIntent("출퇴근으로 분류해줘", today))
        assertEquals(AssistantIntent.SetPurpose(TripPurpose.PERSONAL, TripScope.Day(today.minusDays(1))), parseIntent("어제 운행은 개인으로 바꿔줘", today))
        assertEquals(AssistantIntent.AddExpense(ExpenseType.TOLL, 3_600), parseIntent("통행료 3,600원 추가해줘", today))
        assertEquals(AssistantIntent.AddExpense(ExpenseType.PARKING, 15_000), parseIntent("주차비 1.5만원 넣어줘", today))
        assertEquals(AssistantIntent.AddExpense(ExpenseType.FUEL, 63_250), parseIntent("주유 63250원 결제했어", today))
        assertEquals(AssistantIntent.AddExpense(ExpenseType.MAINTENANCE, 320_000), parseIntent("타이어 32만원 등록", today))
        assertEquals(AssistantIntent.Summary(today.withDayOfMonth(1), today, "이번 달"), parseIntent("이번 달 운행 요약해줘", today))
        assertEquals(AssistantIntent.Summary(today, today, "오늘"), parseIntent("오늘 얼마나 달렸어?", today))
        assertEquals(AssistantIntent.Summary(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "9월"), parseIntent("지난달 현황 알려줘", today))
        assertEquals(AssistantIntent.Care, parseIntent("차량 관리 알려줘", today))
        assertEquals(AssistantIntent.Care, parseIntent("보험 만기 언제야", today))
        assertEquals(AssistantIntent.MonthClose(YearMonth.of(2026, 10)), parseIntent("이번 달 마감 상태 알려줘", today))
        assertEquals(AssistantIntent.Unknown, parseIntent("안녕", today))
        assertEquals(AssistantIntent.Unknown, parseIntent("", today))
        assertNull("금액 없이 비용만 말하면 추가하지 않음", (parseIntent("통행료 추가해줘", today) as? AssistantIntent.AddExpense))
    }

    @Test fun amountsRejectFractionsAndAbsurdValues() {
        assertEquals(3_600L, parseAmount("3,600원"))
        assertEquals(20_000L, parseAmount("2만원"))
        assertEquals(15_000L, parseAmount("1.5만 원"))
        assertNull(parseAmount("0원"))
        assertNull(parseAmount("12,345,678원"))
        assertNull(parseAmount("3.3원"))
    }

    @Test fun readOnlyAnswersCiteTheTripsTheyRead() {
        val data = TripLogData(trips = listOf(trip("a", 30, 9, TripPurpose.BUSINESS), trip("b", 30, 11), trip("c", 2, 9, TripPurpose.COMMUTE)))
        val answer = answerReadOnly(data, parseIntent("이번 달 운행 요약해줘", today), now, zone)
        assertTrue(answer.text, answer.text.startsWith("이번 달 3건, 30.0 km"))
        assertTrue(answer.text, answer.text.contains("목적 확인이 남은 운행이 1건"))
        assertEquals(listOf(id("b"), id("a"), id("c")), answer.citations.map { it.tripId })
        assertFalse(answer.changed)
        val care = answerReadOnly(data.copy(care = VehicleCare(inspectionDue = "2026-11-10")), AssistantIntent.Care, now, zone)
        assertTrue(care.text, care.text.contains("정기검사 D-11"))
        val close = answerReadOnly(data, AssistantIntent.MonthClose(YearMonth.of(2026, 10)), now, zone)
        assertTrue(close.text, close.text.contains("목적·사용자 확인"))
    }

    @Test fun chipsFollowWhatTheLogNeedsRightNow() {
        val latest = trip("latest", 30, 14)
        val data = TripLogData(trips = listOf(latest, trip("y1", 29, 9), trip("y2", 29, 13)),
            pendingCharge = ChargeSession(at(30, 14), at(30, 15), 40, 80, 110.0), care = VehicleCare(insuranceDue = "2026-11-05"))
        val chips = suggestedPrompts(data, now, zone)
        assertEquals(listOf("방금 운행 업무로 기록해줘", "어제 운행 전부 업무로 기록해줘", "충전 비용 등록해줘", "차량 관리 알려줘"), chips)
        assertTrue(suggestedPrompts(TripLogData(), now, zone).contains("이번 달 운행 요약해줘"))
    }

    @Test fun latestTargetIsTheRecordingTripThenTheRecentOne() {
        val recent = trip("r", 30, 13)
        val old = trip("o", 29, 13)
        assertEquals(listOf(recent), purposeTargets(TripLogData(trips = listOf(old, recent)), TripScope.Latest, now, zone))
        val open = recent.copy(id = id("open"), endAt = null)
        assertEquals(listOf(open), purposeTargets(TripLogData(trips = listOf(recent), open = open), TripScope.Latest, now, zone))
        assertTrue("12시간이 지난 운행은 '방금'이 아님", purposeTargets(TripLogData(trips = listOf(old)), TripScope.Latest, now, zone).isEmpty())
        assertEquals(listOf(id("y2"), id("y1")), purposeTargets(TripLogData(trips = listOf(trip("y1", 29, 9), trip("y2", 29, 13), trip("done", 29, 15, TripPurpose.BUSINESS))),
            TripScope.Day(today.minusDays(1)), now, zone).map { it.id })
    }
}
