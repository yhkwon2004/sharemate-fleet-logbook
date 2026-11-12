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

/**
 * The words of the header bar and 홈 (2.13, HomeCopy.kt) held to the one-line rule (CarCopyRules): the status line within the
 * 720-dp reading measure, the dock's line in its lead, a card's line beside its buttons, a destination's line three across,
 * the bar's phrase at its least width; card titles short; no final period anywhere; and every spoken form longer than the
 * line it stands for. The safety steps of an incident are shared copy and keep their periods; each still fits its row.
 */
class HomeCopyTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private fun syllables(text: String) = text.count { it in '가'..'힣' }

    @Test fun statusLinesFitTheReadingMeasure() {
        HomeCopy.STATUS_LINES.forEach { (line, _) -> assertOneLine(line, HOME_STATUS_UNITS) }
        val longest = AttentionKind.entries.sortedByDescending { oneLineUnits(it.label) }.map { AttentionItem(it) }
        assertOneLine(attentionQueueLine(longest).text, HOME_STATUS_UNITS)
        assertEquals(HomeCopy.STATUS_TIDY, allDoneText(SignalStatus.READY))
    }

    @Test fun theDockAndTheDestinationsKeepToTheirPlaces() {
        assertOneLine(HomeCopy.DOCK_LINE, DOCK_LINE_UNITS)
        assertOneLine(HomeCopy.DOCK_LINE_OFF, DOCK_LINE_UNITS)
        val today = LocalDate.of(2026, 11, 20)
        val driver = Driver(id("d"), "영업", "권")
        val trips = (1..12).map { day -> Trip(id("t$day"), at(11, day, 9), at(11, day, 10), 100.0 + day * 10, 110.0 + day * 10, driverId = driver.id,
            purpose = if (day % 3 == 0) TripPurpose.UNCLASSIFIED else TripPurpose.BUSINESS, updatedAt = at(11, day, 10)) }
        val october = Trip(id("o"), at(10, 30, 9), at(10, 30, 10), 90.0, 100.0, driverId = driver.id, purpose = TripPurpose.BUSINESS, updatedAt = at(10, 30, 10))
        val data = TripLogData(drivers = listOf(driver), trips = trips + october, care = VehicleCare(inspectionDue = "2026-11-28"))
        val lines = homeCardLines(data, 100, 51_300.0, true, today, zone)
        listOf(lines.vehicle, lines.log, lines.export).forEach { assertOneLine(it, HOME_TILE_LINE_UNITS) }
        val quiet = homeCardLines(TripLogData(), null, 51_300.0, false, today, zone)
        listOf(quiet.vehicle, quiet.log, quiet.export).forEach { assertOneLine(it, HOME_TILE_LINE_UNITS) }
    }

    @Test fun cardLinesFitBesideTheirButtons() {
        HomeCopy.CARD_LINES.forEach { (line, _) -> assertOneLine(line, HOME_CARD_LINE_UNITS) }
        listOf(true, false).forEach { sync -> assertOneLine(incidentHint(0, sync).text, HOME_CARD_LINE_UNITS) }
        assertEquals("사진 3장 · 휴대폰에서 찍어 차량에 저장했어요", incidentHint(3, true).text)
        assertOneLine(incidentHint(12, false).text, HOME_CARD_LINE_UNITS)
        listOf(HeaderCopy.BAR_EXAMPLE, HomeCopy.MATCHED_LINE, HomeCopy.SETUP_HEADLINE).forEach { assertOneLine(it, HOME_CARD_LINE_UNITS) }
    }

    @Test fun theBarsPhraseShowsWholeAtItsLeastWidth() {
        assertOneLine(HeaderCopy.BAR_PHRASE, BAR_PHRASE_UNITS)
        assertTrue(HeaderCopy.SPOKEN_BAR.contains(HeaderCopy.BAR_EXAMPLE))
    }

    @Test fun titlesAreShort() {
        HomeCopy.TITLES.forEach { assertTrue("$it: ${syllables(it)}", syllables(it) <= 8) }
        AttentionKind.entries.forEach { assertTrue(it.label, syllables(it.label) <= 8) }
    }

    @Test fun spokenFormsSayMoreThanTheirLines() {
        val pairs = listOf(HomeCopy.STATUS_READY to HomeCopy.SPOKEN_STATUS_READY, HomeCopy.STATUS_DEMO to HomeCopy.SPOKEN_STATUS_DEMO,
            HomeCopy.STATUS_DENIED to HomeCopy.SPOKEN_STATUS_DENIED, HomeCopy.STATUS_NO_SIGNAL to HomeCopy.SPOKEN_STATUS_NO_SIGNAL,
            HomeCopy.STATUS_TIDY to HomeCopy.SPOKEN_STATUS_TIDY, HomeCopy.DOCK_LINE to HomeCopy.SPOKEN_DOCK, HomeCopy.DOCK_LINE_OFF to HomeCopy.SPOKEN_DOCK,
            HomeCopy.INCIDENT_PHONE to HomeCopy.SPOKEN_INCIDENT_PHONE, HomeCopy.INCIDENT_SYNC_OFF to HomeCopy.SPOKEN_INCIDENT_SYNC_OFF,
            HomeCopy.PURPOSE_OTHER to HomeCopy.SPOKEN_PURPOSE_OTHER, HomeCopy.CHARGE_REGISTER to HomeCopy.SPOKEN_CHARGE_REGISTER,
            HomeCopy.CLOSE to HomeCopy.SPOKEN_MATCHED_CLOSE, HomeCopy.CLOSE to HomeCopy.SPOKEN_REMINDER_CLOSE, HomeCopy.FINISHED_ADD_COST to HomeCopy.SPOKEN_FINISHED_ADD_COST,
            HeaderCopy.BAR_PHRASE to HeaderCopy.SPOKEN_BAR)
        pairs.forEach { (line, spoken) -> assertTrue("$spoken ⊄ $line", spoken.length > line.length) }
        assertTrue(HomeCopy.SPOKEN_DOCK.startsWith(HomeCopy.DOCK_TITLE))
        (HomeCopy.STATUS_LINES + HomeCopy.CARD_LINES).forEach { (line, spoken) ->
            assertTrue("한 줄에 마침표 없음: $line", !line.endsWith('.'))
            assertTrue("읽는 말도 마침표 없음: $spoken", !spoken.endsWith('.'))
        }
    }

    @Test fun theIncidentStepsFitTheirRows() {
        IncidentKind.entries.forEach { kind ->
            val steps = incidentSteps(kind)
            assertTrue("$kind: ${steps.size}", steps.size <= INCIDENT_STEPS_MAX)
            steps.forEach { assertTrue("$it: ${oneLineUnits(it)}", oneLineUnits(it) <= 56.0) }
        }
        assertEquals("사고는 네 단계", 4, incidentSteps(IncidentKind.ACCIDENT).size)
    }

    @Test fun theCardsHelpersSayWhatTheyShow() {
        val now = at(11, 9, 12)
        val trip = Trip(id("t"), at(11, 9, 9, 30), at(11, 9, 10), 100.0, 110.0, startAddress = "본사", destination = "강남 오피스", updatedAt = at(11, 9, 10))
        assertEquals("09:30 – 10:00", tripWhenLine(trip, now, zone))
        assertEquals("11월 8일 (일) 09:30 – 10:00", tripWhenLine(trip.copy(startAt = at(11, 8, 9, 30), endAt = at(11, 8, 10)), now, zone))
        assertEquals("09:30 – 10:00 · 본사 → 강남 오피스", purposeCardLine(trip, now, zone))
        assertEquals("주소가 없으면 시간만", "09:30 – 10:00", purposeCardLine(trip.copy(startAddress = null, destination = null), now, zone))
        assertEquals("미분류 3건", purposeCardTag(3, null))
        assertNull("하나뿐인 질문은 태그 없음", purposeCardTag(1, null))
        assertEquals("현재 일반 업무", purposeCardTag(1, TripPurpose.BUSINESS))
        // The card question's headline is as sure as the matcher was.
        val charge = CardCharge(id("c"), at(11, 9, 13, 30), true, 4_500, "아이파킹 강남", null, CardSource.NOTIFICATION, CostCategory.PARKING, ChargeState.REVIEW)
        assertEquals("이 운행의 비용이 맞나요?", chargeReviewHeadline(charge.copy(tripId = trip.id), trip))
        assertEquals("가장 가까운 운행의 비용이 맞나요?", chargeReviewHeadline(charge, trip))
        assertEquals("어느 운행의 비용인지 찾지 못했어요", chargeReviewHeadline(charge, null))
        // The setup steps, each with its own way on; choosing a driver waits for an employee.
        val empty = setupSteps(TripLogData())
        assertEquals(listOf("법인·차량 정보", "직원 등록", "운전자 선택"), empty.map { it.label })
        assertEquals(listOf("법인·차량 입력", "직원 추가", null), empty.map { it.action })
        assertTrue(empty.none { it.done })
        val driver = Driver(id("d"), "영업", "권")
        val complete = setupSteps(TripLogData(profile = FleetProfile(companyName = "쉐어메이트", plateNumber = "123가4567"), drivers = listOf(driver), currentDriverId = driver.id))
        assertTrue(complete.all { it.done && it.action == null })
        assertEquals("직원이 있으면 운전자 선택", "운전자 선택", setupSteps(TripLogData(drivers = listOf(driver)))[2].action)
        // A batch opens on the month of its first payment.
        val october = charge.copy(id = id("o"), at = at(10, 31, 20), state = ChargeState.MATCHED)
        assertEquals(YearMonth.of(2026, 10), matchedBatchMonth(listOf(charge.copy(state = ChargeState.MATCHED), october), zone))
        assertNull(matchedBatchMonth(emptyList(), zone))
        // The incident's facts, and their fallbacks.
        val incident = Incident(id("i"), now, IncidentKind.ACCIDENT, 51_300.0, "성남시 분당구", null, driver.id)
        assertEquals("계기판 51,300.0 km · 운전자 권 · 성남시 분당구", incidentFacts(incident, driver))
        assertEquals("계기판 미수신 · 운전자 미지정 · 내비 주소 미수신", incidentFacts(incident.copy(odometerKm = null, address = null), null))
    }

    @Test fun theMonthCloseLineNamesTheFirstOpenStep() {
        val oct = YearMonth.of(2026, 10)
        assertEquals(HomeLine("목적·사용자 확인 3건 남음", "1단계 목적·사용자 확인, 3건 남음"), monthCloseTaskLine(MonthStatus(oct, 10, 3, 2, null)))
        assertEquals(HomeLine("카드 확인 2건 남음", "2단계 카드 확인, 2건 남음"), monthCloseTaskLine(MonthStatus(oct, 10, 0, 2, null)))
        assertEquals("모두 확인했어요 · 지금 마감할 수 있어요", monthCloseTaskLine(MonthStatus(oct, 10, 0, 0, null)).text)
        listOf(MonthStatus(oct, 999, 999, 0, null), MonthStatus(oct, 999, 0, 999, null), MonthStatus(oct, 10, 0, 0, null)).map(::monthCloseTaskLine).forEach {
            assertOneLine(it.text, HOME_CARD_LINE_UNITS)
            assertTrue("${it.spoken} ⊄ ${it.text}", it.spoken.length >= it.text.length)
        }
    }

    /**
     * Both month-close cards (the card and the 25th's reminder) offer the step the line names: trips first, then card questions,
     * which 점검 필요 never lists, so a month left with only card payments is sent to 카드 확인, not to an empty list.
     */
    @Test fun theMonthCloseCardsOfferTheStepTheirLineNames() {
        val oct = YearMonth.of(2026, 10)
        assertEquals(MonthCloseNext.TIDY, monthCloseNext(MonthStatus(oct, 10, 3, 2, null)))
        assertEquals("카드 질문만 남으면 카드 확인으로", MonthCloseNext.REVIEW, monthCloseNext(MonthStatus(oct, 10, 0, 1, null)))
        assertEquals(MonthCloseNext.CLOSE, monthCloseNext(MonthStatus(oct, 10, 0, 0, null)))
        // The button and the line never disagree on which step is open.
        (0..2).flatMap { unfinished -> (0..2).map { reviews -> MonthStatus(oct, 10, unfinished, reviews, null) } }.forEach { status ->
            val step = when (monthCloseNext(status)) {
                MonthCloseNext.TIDY -> "목적·사용자 확인"
                MonthCloseNext.REVIEW -> "카드 확인"
                MonthCloseNext.CLOSE -> "지금 마감할 수 있어요"
            }
            assertTrue("$status: ${monthCloseTaskLine(status).text}", monthCloseTaskLine(status).text.contains(step))
        }
    }
}
