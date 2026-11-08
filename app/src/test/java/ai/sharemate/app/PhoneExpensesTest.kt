package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** Pure helpers behind the phone's 경비 tab and its one-tap incident undo; the composables themselves are not under test here. */
class PhoneExpensesTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 10) = LocalDateTime.of(2026, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private fun trip(seed: String, day: Int, open: Boolean = false, demo: Boolean = false) =
        Trip(id(seed), at(day, 9), if (open) null else at(day, 10), 100.0 + day, 110.0 + day, destination = "거래처 $seed", demo = demo, updatedAt = at(day, 10))
    private fun line(seed: String, day: Int, won: Long, merchant: String) = CardApproval(id(seed), at(day, 12), true, won, merchant, null, CardSource.STATEMENT)
    private fun charge(line: CardApproval, state: ChargeState, category: CostCategory = CostCategory.PARKING, statementId: String? = null, demo: Boolean = false) =
        CardCharge(line.id, line.at, line.timeKnown, line.amountWon, line.merchant, null, line.source, category, state, statementId = statementId, demo = demo)

    @Test fun statementOutcomeReadsWhatTheCarDidOffTheMirror() {
        val parking = line("p", 20, 4_500, "아이파킹 강남")
        val toll = line("t", 21, 3_600, "한국도로공사")
        val knownToll = line("k", 22, 1_800, "한국도로공사 서울")
        val lost = line("l", 23, 9_000, "하이패스")
        val cafe = line("c", 24, 5_000, "스타벅스")
        // The notification already delivered the toll; the statement line only confirms it.
        val before = listOf(charge(line("n", 21, 3_600, "한국도로공사"), ChargeState.MATCHED, CostCategory.TOLL), charge(knownToll, ChargeState.MATCHED, CostCategory.TOLL))
        val after = before.map { if (it.merchant == "한국도로공사") it.copy(statementId = toll.id) else it } + charge(parking, ChargeState.MATCHED) + charge(lost, ChargeState.REVIEW, CostCategory.TOLL)
        val outcome = statementOutcome(listOf(parking, toll, knownToll, lost, cafe), listOf(parking, toll, knownToll, lost), before, after, paired = true)
        assertEquals(5, outcome.total); assertEquals(4, outcome.relevant)
        assertEquals(mapOf(("주차" to "새로 반영") to 1, ("통행료" to "확인 필요") to 1), outcome.arrived)
        assertEquals("알림과 같은 건 + 이미 있던 id", 2, outcome.duplicates)
        assertEquals(0, outcome.waiting)
        assertEquals("명세서 5건 중 차량 관련 4건을 보냈어요 · 주차 1건 새로 반영, 통행료 1건 확인 필요, 2건은 이미 있는 건", statementOutcomeText(outcome))
    }

    @Test fun statementOutcomeTextCoversEmptyUnpairedAndWaitingCases() {
        val parking = line("p", 20, 4_500, "아이파킹 강남")
        assertEquals("명세서 3건 중 차량 관련 결제가 없어요 · 보낸 것 없음", statementOutcomeText(statementOutcome(listOf(parking, parking, parking), emptyList(), emptyList(), emptyList(), true)))
        val unpaired = statementOutcome(listOf(parking), listOf(parking), emptyList(), emptyList(), paired = false)
        assertEquals("명세서 1건 중 차량 관련 1건을 보냈어요 · 차량과 연결되면 자동 전송돼요", statementOutcomeText(unpaired))
        val waiting = statementOutcome(listOf(parking), listOf(parking), emptyList(), emptyList(), paired = true)
        assertEquals(1, waiting.waiting)
        assertEquals("명세서 1건 중 차량 관련 1건을 보냈어요 · 1건은 차량 반영 대기", statementOutcomeText(waiting))
    }

    @Test fun monthChargesFollowTheDemoSwitchAndTheCalendar() {
        val october = charge(line("a", 20, 4_500, "아이파킹"), ChargeState.MATCHED)
        val november = charge(line("b", 2, 4_500, "아이파킹").copy(at = at(2, 12, month = 11)), ChargeState.MATCHED)
        val demo = charge(line("c", 21, 4_500, "아이파킹"), ChargeState.MATCHED, demo = true)
        val data = TripLogData(charges = listOf(october, november, demo))
        assertEquals(listOf(october), chargesIn(data, YearMonth.of(2026, 10), zone))
        assertEquals(listOf(november), chargesIn(data, YearMonth.of(2026, 11), zone))
        assertEquals(listOf(demo), chargesIn(data.copy(profile = data.profile.copy(demoMode = true)), YearMonth.of(2026, 10), zone))
    }

    @Test fun defaultExpenseTripIsTheLatestFinishedTripOfTheVisibleSet() {
        val older = trip("a", 10); val latest = trip("b", 20); val recording = trip("c", 25, open = true); val demo = trip("d", 28, demo = true)
        val data = TripLogData(trips = listOf(older, latest, recording, demo))
        assertEquals(latest, defaultExpenseTrip(data))
        assertEquals(demo, defaultExpenseTrip(data.copy(profile = data.profile.copy(demoMode = true))))
        assertNull(defaultExpenseTrip(TripLogData(trips = listOf(recording))))
        assertEquals("09:00 출발 · 거래처 b", expenseTripLabel(latest).replace(Regex("^\\d\\d:\\d\\d"), "09:00"))
    }

    @Test fun matcherReasonsSplitIntoNumberedSteps() {
        assertEquals(listOf("운행 중 통행", "톨게이트 통과", "경로 예상요금 3,600원 일치"), reasonSteps("운행 중 통행 · 톨게이트 통과 · 경로 예상요금 3,600원 일치"))
        assertEquals(listOf("가맹점 분류 주차"), reasonSteps("가맹점 분류 주차"))
        assertTrue(reasonSteps("").isEmpty())
        assertEquals("빈 조각은 버림", 2, reasonSteps("a ·  · b").size)
    }

    @Test fun laterOnTheAlertPromptHoldsForThirtyDaysOnly() {
        val now = at(1, 9)
        assertEquals(30L * 24 * 60 * 60 * 1000, ALERT_PROMPT_LATER_MILLIS)
        assertTrue(alertPromptDeferred(now + ALERT_PROMPT_LATER_MILLIS, now))
        assertTrue(alertPromptDeferred(now + ALERT_PROMPT_LATER_MILLIS, now + ALERT_PROMPT_LATER_MILLIS - 1))
        assertFalse("기한이 지나면 카드가 다시 펼쳐짐", alertPromptDeferred(now + ALERT_PROMPT_LATER_MILLIS, now + ALERT_PROMPT_LATER_MILLIS))
        assertFalse("저장된 값이 없으면 바로 물음", alertPromptDeferred(0L, now))
    }

    @Test fun theUndoTargetIsTheNewestOpenReportOfThatKindThatWasNotThereBefore() {
        val before = Incident(id("old"), at(1, 9), IncidentKind.DAMAGE, null, null, null, null)
        val closed = Incident(id("closed"), at(1, 11), IncidentKind.DAMAGE, null, null, null, null, status = IncidentStatus.CLOSED)
        val other = Incident(id("other"), at(1, 12), IncidentKind.BREAKDOWN, null, null, null, null)
        val demo = Incident(id("demo"), at(1, 13), IncidentKind.DAMAGE, null, null, null, null, demo = true)
        val created = Incident(id("new"), at(1, 10), IncidentKind.DAMAGE, 12_406.0, "시흥동", null, null)
        val known = setOf(before.id)
        assertNull("거울이 아직 안 왔으면 없음", newIncidentFor(IncidentKind.DAMAGE, known, listOf(before), demo = false))
        assertEquals(created, newIncidentFor(IncidentKind.DAMAGE, known, listOf(before, closed, other, demo, created), demo = false))
        assertNull("다른 종류·완료·시연 기록은 대상이 아님", newIncidentFor(IncidentKind.ACCIDENT, known, listOf(before, closed, other, demo, created), demo = false))
        val newer = created.copy(id = id("newer"), at = at(1, 10, 30))
        assertEquals("둘 이상이면 가장 최근 것", newer, newIncidentFor(IncidentKind.DAMAGE, known, listOf(created, newer), demo = false))
        assertEquals(60_000L, INCIDENT_UNDO_WINDOW_MILLIS)
    }

    @Test fun pairingFailuresLandUnderTheRightField() {
        assertEquals("연결 번호가 달라요. 차량 화면의 번호를 다시 확인해 주세요.", pairingErrorMessage(IllegalArgumentException("연결 번호가 달라요. 차량 화면의 번호를 다시 확인해 주세요.")))
        assertFalse(pairingErrorIsAddress("연결 번호가 달라요. 차량 화면의 번호를 다시 확인해 주세요."))
        assertTrue(pairingErrorIsAddress("차량에 닿지 않아요. 주소와 포트, 같은 Wi-Fi·핫스팟인지 확인해 주세요. (ConnectException)"))
        assertTrue(pairingErrorIsAddress("차량 주소를 확인해 주세요."))
        assertEquals("빈 메시지나 다른 예외는 일반 문구", "차량과 연결하지 못했어요. 주소와 번호를 확인하고 다시 시도해 주세요.", pairingErrorMessage(IllegalStateException("x")))
        assertEquals("차량과 연결하지 못했어요. 주소와 번호를 확인하고 다시 시도해 주세요.", pairingErrorMessage(IllegalArgumentException("")))
    }

    @Test fun checkedResponseStepsRoundTripThroughPreferences() {
        assertEquals(setOf(0, 2), parseStepMarks("0,2"))
        assertEquals(setOf(1), parseStepMarks(" 1 , x , -3 "))
        assertTrue(parseStepMarks(null).isEmpty()); assertTrue(parseStepMarks("").isEmpty())
        assertEquals("0,1,4", encodeStepMarks(setOf(4, 0, 1)))
        assertEquals(setOf(0, 1, 4), parseStepMarks(encodeStepMarks(setOf(4, 0, 1))))
        assertEquals("incident-steps-abc", incidentStepsKey("abc"))
        assertEquals("phone-ui", PHONE_UI_PREFS)
    }

    @Test fun typeTotalsCoverEveryKindInOrderWithZeroForNothingSpent() {
        val a = trip("a", 10).copy(expenses = listOf(TripExpense(ExpenseType.PARKING, 4_500, at(10, 12), EditSource.PHONE), TripExpense(ExpenseType.FUEL, 60_000, at(10, 13), EditSource.DRIVER)))
        val b = trip("b", 11).copy(expenses = listOf(TripExpense(ExpenseType.PARKING, 2_000, at(11, 12), EditSource.CARD, cardChargeId = "c1")))
        val totals = expenseTypeTotals(listOf(a, b))
        assertEquals("종류 순서대로, 빠진 종류 없이", ExpenseType.entries, totals.keys.toList())
        assertEquals(6_500L, totals[ExpenseType.PARKING]); assertEquals(60_000L, totals[ExpenseType.FUEL])
        assertEquals(0L, totals[ExpenseType.TOLL]); assertEquals(0L, totals[ExpenseType.MAINTENANCE]); assertEquals(0L, totals[ExpenseType.OTHER])
        assertEquals("합계는 운행 경비 합과 같음", listOf(a, b).sumOf { it.expenseWon }, totals.values.sum())
        assertTrue(expenseTypeTotals(emptyList()).values.all { it == 0L })
    }
}
