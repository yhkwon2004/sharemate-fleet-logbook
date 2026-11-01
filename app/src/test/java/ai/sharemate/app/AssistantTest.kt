package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking

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

    // ───────── writer contract: the sheet only sees AssistantWriter ─────────

    private class FakeSync(var paired: Boolean = true, val fail: Boolean = false) {
        val sent = mutableListOf<SyncCommand>()
        suspend fun send(command: SyncCommand) { if (fail) throw IllegalArgumentException("차량이 변경을 받지 않았어요."); sent += command }
    }

    private fun phoneWriter(data: TripLogData, sync: FakeSync) = PhoneAssistantWriter({ data }, { sync.paired }, sync::send)

    @Test fun writeIntentsAreTheOnlyOnesTheSheetConfirmsFirst() {
        assertTrue(writesLog(parseIntent("방금 운행 업무로 기록해줘", today)))
        assertTrue(writesLog(parseIntent("통행료 3,600원 추가해줘", today)))
        assertFalse(writesLog(parseIntent("이번 달 운행 요약해줘", today)))
        assertFalse(writesLog(parseIntent("차량 관리 알려줘", today)))
        assertFalse(writesLog(parseIntent("이번 달 마감 상태 알려줘", today)))
        assertFalse(writesLog(AssistantIntent.Unknown))
    }

    @Test fun phoneWriterSendsPurposeAsOneClassifyOrAClassifyMany() = runBlocking {
        val latest = trip("latest", 30, 14)
        val data = TripLogData(trips = listOf(latest, trip("y1", 29, 9), trip("y2", 29, 13)))
        val sync = FakeSync()
        val writer = phoneWriter(data, sync)
        val one = writer.run("방금 운행 업무로 기록해줘", now, zone)
        assertTrue(one.changed)
        assertEquals(listOf<SyncCommand>(SyncCommand.Classify(id("latest"), TripPurpose.BUSINESS)), sync.sent)
        assertEquals(mapOf(id("latest") to (TripPurpose.UNCLASSIFIED to null)), one.undoPurposes)
        assertEquals(listOf(id("latest")), one.citations.map { it.tripId })
        assertEquals(3, one.steps.size)
        sync.sent.clear()
        val many = writer.run("어제 운행 전부 개인으로 기록해줘", now, zone)
        assertEquals(listOf<SyncCommand>(SyncCommand.ClassifyMany(listOf(id("y2"), id("y1")), TripPurpose.PERSONAL)), sync.sent)
        assertTrue(many.text, many.text.startsWith("2건을 개인으로"))
        sync.sent.clear()
        writer.undo(many)
        assertEquals(setOf<SyncCommand>(SyncCommand.Classify(id("y1"), TripPurpose.UNCLASSIFIED), SyncCommand.Classify(id("y2"), TripPurpose.UNCLASSIFIED)), sync.sent.toSet())
    }

    @Test fun phoneWriterAttachesExpenseToTheLatestTripAndUndoRemovesThatIndex() = runBlocking {
        val latest = trip("latest", 30, 14).copy(expenses = listOf(TripExpense(ExpenseType.PARKING, 2_000, now, EditSource.DRIVER)))
        val sync = FakeSync()
        val writer = phoneWriter(TripLogData(trips = listOf(latest)), sync)
        val answer = writer.run("통행료 3,600원 추가해줘", now, zone)
        assertTrue(answer.changed)
        assertEquals(listOf<SyncCommand>(SyncCommand.AddExpense(id("latest"), ExpenseType.TOLL, 3_600)), sync.sent)
        assertEquals(id("latest") to 1, answer.undoExpense)
        sync.sent.clear()
        writer.undo(answer)
        assertEquals(listOf<SyncCommand>(SyncCommand.RemoveExpense(id("latest"), 1)), sync.sent)
    }

    @Test fun phoneWriterNeverWritesWhenUnpairedOrForQuestions() = runBlocking {
        val data = TripLogData(trips = listOf(trip("latest", 30, 14), trip("done", 30, 9, TripPurpose.BUSINESS)))
        val sync = FakeSync(paired = false)
        val writer = phoneWriter(data, sync)
        assertFalse(writer.canWrite)
        val refused = writer.run("방금 운행 업무로 기록해줘", now, zone)
        assertFalse(refused.changed)
        assertEquals(PhoneAssistantWriter.UNPAIRED_NOTE, refused.text)
        assertEquals("아직 연결 전에도 어떤 운행인지 보여 줌", listOf(id("latest")), refused.citations.map { it.tripId })
        val expense = writer.run("주차비 4,500원 추가해줘", now, zone)
        assertFalse(expense.changed)
        assertTrue(sync.sent.isEmpty())
        sync.paired = true
        val summary = writer.run("이번 달 운행 요약해줘", now, zone)
        assertFalse(summary.changed)
        assertTrue(summary.text, summary.text.startsWith("이번 달 2건"))
        assertEquals("휴대폰 사본 기준", writer.sourceNote)
        assertTrue("요약은 차량에 아무것도 보내지 않음", sync.sent.isEmpty())
        val unknown = writer.run("안녕", now, zone)
        assertEquals(AssistantIntent.Unknown, unknown.intent)
        assertTrue(sync.sent.isEmpty())
    }

    @Test fun phoneWriterSurfacesTheCarsRefusalInsteadOfPretending() = runBlocking {
        val sync = FakeSync(fail = true)
        val writer = phoneWriter(TripLogData(trips = listOf(trip("latest", 30, 14))), sync)
        val error = runCatching { writer.run("방금 운행 업무로 기록해줘", now, zone) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("차량이 변경을 받지 않았어요.", error?.message)
    }

    @Test fun phoneWriterWithNoRecentTripAnswersWithoutSending() = runBlocking {
        val sync = FakeSync()
        val writer = phoneWriter(TripLogData(trips = listOf(trip("old", 2, 9))), sync)
        val answer = writer.run("방금 운행 업무로 기록해줘", now, zone)
        assertEquals("바꿀 운행을 찾지 못했어요.", answer.text)
        assertFalse(answer.changed)
        assertTrue(sync.sent.isEmpty())
    }

    @Test fun demoAndRealTripsStayApartForTheAssistant() = runBlocking {
        val real = trip("real", 30, 14)
        val demo = trip("demo", 30, 14).copy(demo = true)
        val sync = FakeSync()
        val writer = phoneWriter(TripLogData(trips = listOf(real, demo), profile = FleetProfile(demoMode = true)), sync)
        writer.run("방금 운행 업무로 기록해줘", now, zone)
        assertEquals(listOf<SyncCommand>(SyncCommand.Classify(id("demo"), TripPurpose.BUSINESS)), sync.sent)
    }

    @Test fun sheetChipsAreSixDistinctAndEveryOneParses() {
        val data = TripLogData(trips = listOf(trip("latest", 30, 14), trip("y1", 29, 9), trip("y2", 29, 13)),
            pendingCharge = ChargeSession(at(30, 14), at(30, 15), 40, 80, 110.0), care = VehicleCare(insuranceDue = "2026-11-05"))
        val chips = assistantChips(data, now, zone)
        assertEquals(6, chips.size)
        assertEquals(chips.distinct(), chips)
        assertEquals("방금 운행 업무로 기록해줘", chips.first())
        chips.forEach { assertNotEquals(it, AssistantIntent.Unknown, parseIntent(it, today)) }
        val empty = assistantChips(TripLogData(), now, zone)
        assertEquals(6, empty.size)
        assertTrue("빈 기록에서는 대상이 없는 쓰기 칩을 보여 주지 않음", empty.none { writesLog(parseIntent(it, today)) })
        assertTrue("최근 운행이 있으면 비용 칩이 살아 있음", chips.any { parseIntent(it, today) is AssistantIntent.AddExpense || parseIntent(it, today) is AssistantIntent.AskAmount })
        ASSISTANT_EXAMPLES.forEach { assertNotEquals(it, AssistantIntent.Unknown, parseIntent(it, today)) }
    }

    @Test fun pendingListCitesUnclassifiedTripsNewestFirstUpToFive() {
        assertEquals(AssistantIntent.ListPending, parseIntent("미분류 운행 뭐가 남았어?", today))
        assertEquals(AssistantIntent.ListPending, parseIntent("목적 확인 안 된 운행 보여줘", today))
        assertEquals("마감이 먼저", AssistantIntent.MonthClose(YearMonth.of(2026, 10)), parseIntent("이번 달 마감까지 뭐가 남았어", today))
        val trips = (1..7).map { trip("p$it", 20 + it, 9) } + trip("done", 28, 15, TripPurpose.BUSINESS) + trip("demo", 29, 9).copy(demo = true)
        val answer = answerReadOnly(TripLogData(trips = trips), AssistantIntent.ListPending, now, zone)
        assertTrue(answer.text, answer.text.startsWith("목적 확인이 남은 운행이 7건"))
        assertEquals(listOf(id("p7"), id("p6"), id("p5"), id("p4"), id("p3")), answer.citations.map { it.tripId })
        assertFalse(answer.changed)
        val none = answerReadOnly(TripLogData(trips = listOf(trip("done", 28, 15, TripPurpose.BUSINESS))), AssistantIntent.ListPending, now, zone)
        assertTrue(none.text, none.text.startsWith("목적 확인이 남은 운행이 없어요"))
        assertTrue(none.citations.isEmpty())
    }

    @Test fun quickAmountPhrasesParseBackAsTheSameExpense() {
        ExpenseType.entries.forEach { type ->
            quickAmounts(type).forEach { amount ->
                assertEquals(expensePhrase(type, amount), AssistantIntent.AddExpense(type, amount), parseIntent(expensePhrase(type, amount), today))
            }
        }
        assertEquals(AssistantIntent.AskAmount(ExpenseType.TOLL), parseIntent("통행료 추가해줘", today))
        assertEquals(AssistantIntent.AskAmount(ExpenseType.FUEL), parseIntent("충전 비용 등록해줘", today))
        val unknown = answerReadOnly(TripLogData(), AssistantIntent.Unknown, now, zone)
        assertTrue(unknown.text, unknown.text.contains("목적 기록, 비용 추가"))
        assertFalse(unknown.changed)
    }
}
