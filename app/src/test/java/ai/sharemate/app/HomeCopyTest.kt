package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * line it stands for. The safety steps of an incident are shared copy and keep their periods; each still fits its row. 2.14
 * adds the tall forms' lines, the setup tiles and the idle card.
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
        // The tall dock's header (2.12's words): the title, the caption and 휴대폰에도 공유 on one line; TalkBack hears the first two.
        assertOneLine("${HomeCopy.DOCK_TITLE} ${HomeCopy.DOCK_CAPTION} ${HomeCopy.DOCK_SHARED}", DOCK_CAPTION_UNITS)
        // Under 560 dp of dock ([dockHeaderOneRow]) the title row holds 휴대폰에도 공유 and the caption is a line of its own.
        assertOneLine("${HomeCopy.DOCK_TITLE} ${HomeCopy.DOCK_SHARED}", DOCK_NARROW_UNITS)
        assertOneLine(HomeCopy.DOCK_CAPTION, DOCK_NARROW_UNITS)
        assertEquals("사고·특이사항, 한 번 누르면 차량 정보가 바로 기록돼요", HomeCopy.SPOKEN_DOCK)
        val today = LocalDate.of(2026, 11, 20)
        val driver = Driver(id("d"), "영업", "권")
        val trips = (1..12).map { day -> Trip(id("t$day"), at(11, day, 9), at(11, day, 10), 100.0 + day * 10, 110.0 + day * 10, driverId = driver.id,
            purpose = if (day % 3 == 0) TripPurpose.UNCLASSIFIED else TripPurpose.BUSINESS, updatedAt = at(11, day, 10)) }
        val october = Trip(id("o"), at(10, 30, 9), at(10, 30, 10), 90.0, 100.0, driverId = driver.id, purpose = TripPurpose.BUSINESS, updatedAt = at(10, 30, 10))
        val data = TripLogData(drivers = listOf(driver), trips = trips + october, care = VehicleCare(inspectionDue = "2026-11-28"))
        val lines = homeCardLines(data, 100, 51_300.0, true, today, zone)
        val quiet = homeCardLines(TripLogData(), null, 51_300.0, false, today, zone)
        val closed = homeCardLines(data, 100, 51_300.0, false, LocalDate.of(2026, 11, 3), zone)
        // Each fits the compact card (bodySmall) and, at bodyMedium, the tall one.
        listOf(lines, quiet, closed).flatMap { listOf(it.vehicle, it.log, it.export) }.forEach {
            assertOneLine(it, HOME_TILE_LINE_UNITS)
            assertOneLine(it, HOME_TALL_LINE_UNITS)
        }
    }

    @Test fun theSetupTilesAndTheIdleCardKeepToOneLine() {
        HomeCopy.SETUP_LINES.forEach { (line, spoken) ->
            assertOneLine(line, SETUP_TILE_LINE_UNITS)
            assertTrue("$spoken ⊄ $line", spoken.length >= line.length)
        }
        listOf(HomeCopy.IDLE_EMPTY, HomeCopy.IDLE_EMPTY_LINE).forEach { assertOneLine(it, HOME_CARD_LINE_UNITS) }
        assertTrue(HomeCopy.SPOKEN_IDLE_EMPTY_LINE.length > HomeCopy.IDLE_EMPTY_LINE.length)
        assertTrue(HomeCopy.SPOKEN_IDLE_TAG.length > HomeCopy.IDLE_TAG.length)
        listOf(HomeCopy.SETUP_STATE_DONE, HomeCopy.SETUP_STATE_NEXT, HomeCopy.SETUP_STATE_WAITING, HomeCopy.IDLE_TAG, HomeCopy.IDLE_ACTION).forEach {
            assertOneLine(it, 8.0)
        }
        assertEquals("2.12의 브랜드 줄", "SHAREMATE  ·  FLEET JOURNAL", HomeCopy.BRAND)
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
        val driver = Driver(id("d"), "영업", "권")
        // A batch opens on the month of its first payment.
        val october = charge.copy(id = id("o"), at = at(10, 31, 20), state = ChargeState.MATCHED)
        assertEquals(YearMonth.of(2026, 10), matchedBatchMonth(listOf(charge.copy(state = ChargeState.MATCHED), october), zone))
        assertNull(matchedBatchMonth(emptyList(), zone))
        // The incident's facts, and their fallbacks.
        val incident = Incident(id("i"), now, IncidentKind.ACCIDENT, 51_300.0, "성남시 분당구", null, driver.id)
        assertEquals("계기판 51,300.0 km · 운전자 권 · 성남시 분당구", incidentFacts(incident, driver))
        assertEquals("계기판 미수신 · 운전자 미지정 · 내비 주소 미수신", incidentFacts(incident.copy(odometerKm = null, address = null), null))
    }

    /**
     * 시작 설정's three tiles (2.14): each says what its step is for, then what it holds; the first open one is the current
     * step; choosing a driver waits for an employee, and says so; TalkBack hears each tile's state.
     */
    @Test fun theSetupTilesSayWhatEachStepIsForAndThenWhatItHolds() {
        val empty = setupSteps(TripLogData())
        assertEquals(listOf("법인·차량 정보", "직원 등록", "운전자 선택"), empty.map { it.label })
        assertEquals(listOf("운행기록부 머리말에 들어가요", "④ 사용자 칸에 들어가요", "직원을 등록하면 고를 수 있어요"), empty.map { it.line })
        assertEquals(listOf(SetupTarget.COMPANY, SetupTarget.DRIVERS, SetupTarget.PICKER), empty.map { it.target })
        assertTrue(empty.none { it.done })
        assertEquals("직원이 없으면 운전자 선택은 못 누름", listOf(true, true, false), empty.map { it.enabled })
        assertEquals("첫 단계가 지금 할 단계", listOf(true, false, false), empty.map { it.current })
        assertEquals(listOf("다음 단계", "다음 단계", "직원 등록 뒤"), empty.map { it.state })
        assertEquals("읽는 말은 ④ 칸에 무엇이 들어가는지까지", HomeCopy.SPOKEN_SETUP_DRIVERS_LINE, empty[1].spokenLine)
        val kim = Driver(id("k"), "Sales", "Kim Jiwoo")
        val lee = Driver(id("l"), "영업", "이서연")
        val staffed = setupSteps(TripLogData(drivers = listOf(kim, lee)))
        assertEquals(listOf("운행기록부 머리말에 들어가요", "직원 2명", "오늘 운전하는 직원을 골라요"), staffed.map { it.line })
        assertEquals(listOf(true, true, true), staffed.map { it.enabled })
        assertEquals("법인 정보가 아직이면 그것이 먼저", listOf(true, false, false), staffed.map { it.current })
        assertEquals(listOf("다음 단계", "완료", "다음 단계"), staffed.map { it.state })
        val company = FleetProfile(companyName = "쉐어메이트", plateNumber = "123가4567")
        val almost = setupSteps(TripLogData(profile = company, drivers = listOf(kim)))
        assertEquals("설정처럼 법인과 차량번호", "쉐어메이트 · 123가4567", almost[0].line)
        assertEquals("남은 운전자 선택이 지금 할 단계", listOf(false, false, true), almost.map { it.current })
        val complete = setupSteps(TripLogData(profile = company, drivers = listOf(kim), currentDriverId = kim.id))
        assertTrue(complete.all { it.done && it.enabled && !it.current })
        assertEquals("운전자는 이름으로", "Kim Jiwoo", complete[2].line)
        assertEquals(listOf("완료", "완료", "완료"), complete.map { it.state })
        assertFalse("차량번호가 없으면 법인·차량은 미완", setupSteps(TripLogData(profile = company.copy(plateNumber = " ")))[0].done)
        assertFalse("없는 직원을 고른 기록은 미완", setupSteps(TripLogData(drivers = listOf(kim), currentDriverId = "gone"))[2].done)
        listOf(empty, staffed, almost, complete).flatten().forEach { assertOneLine(it.line, SETUP_TILE_LINE_UNITS) }
    }

    /**
     * 최근 운행 (2.14, the idle card): the newest finished trip of the data set on screen, titled as 운행기록 titles its row,
     * its distance, and one line with the day, the start, the purpose and the driver; or nothing yet and how a trip starts.
     */
    @Test fun theIdleCardShowsTheNewestTripOfTheDataSetOnScreen() {
        // hm() and dayLabel read the device's zone, so these trips are built in it: 02:26 is 02:26 wherever the test runs.
        val local = ZoneId.systemDefault()
        fun on(day: Int, hour: Int, minute: Int) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(local).toInstant().toEpochMilli()
        val kim = Driver(id("k"), "Sales", "Kim Jiwoo")
        val none = homeIdle(TripLogData())
        assertEquals(HomeIdle(HomeLine("아직 운행이 없어요"), null, HomeLine("P에서 D로 바꾸면 기록이 시작돼요", HomeCopy.SPOKEN_IDLE_EMPTY_LINE), null), none)
        val older = Trip(id("a"), on(28, 9, 0), on(28, 9, 30), 12_000.0, 12_012.0, startAddress = "본사", destination = "강남 오피스", driverId = kim.id,
            purpose = TripPurpose.BUSINESS, updatedAt = on(28, 9, 30))
        val newest = Trip(id("b"), on(29, 2, 26), on(29, 2, 27), 12_337.0, 12_345.0, startAddress = "수정구 시흥동", destination = "판교", driverId = kim.id,
            purpose = TripPurpose.BUSINESS, updatedAt = on(29, 2, 27))
        val recording = Trip(id("r"), on(30, 8, 0), null, 12_345.0, 12_350.0, driverId = kim.id)
        val classified = homeIdle(TripLogData(drivers = listOf(kim), trips = listOf(older, newest, recording)))
        assertEquals("운행기록 행의 제목 그대로", tripRowTitle(newest), classified.headline.text)
        assertEquals("수정구 시흥동 → 판교", classified.headline.text)
        assertEquals("장소는 하나씩 읽음", "수정구 시흥동, 판교", classified.headline.spoken)
        assertFalse("화살표는 읽지 않음", classified.headline.spoken.contains('→'))
        assertEquals(8.0, classified.km!!, 1e-9)
        assertEquals("10월 29일 (목) 02:26 · 일반 업무 · Kim Jiwoo", classified.line.text)
        assertEquals("10월 29일 (목) 02:26 출발, 일반 업무, Kim Jiwoo", classified.line.spoken)
        assertEquals("그 운행의 달로 열림", YearMonth.of(2026, 10), classified.month)
        // An unclassified trip without a driver or an address: the row title is its distance and time, so no figure repeats it.
        val bare = newest.copy(id = id("c"), startAddress = null, destination = null, driverId = null, purpose = TripPurpose.UNCLASSIFIED)
        val unclassified = homeIdle(TripLogData(drivers = listOf(kim), trips = listOf(older, bare)))
        assertEquals("8.0 km · 1분", unclassified.headline.text)
        assertEquals("8.0 km, 1분", unclassified.headline.spoken)
        assertNull("주소 없는 운행은 제목이 이미 거리", unclassified.km)
        assertEquals("10월 29일 (목) 02:26 · 미분류 · 사용자 미지정", unclassified.line.text)
        assertEquals("10월 29일 (목) 02:26 출발, 목적 미분류, 사용자 미지정", unclassified.line.spoken)
        // Demo trips only in demo mode, real ones only outside it.
        val demoTrip = older.copy(id = id("d"), startAt = on(30, 10, 0), endAt = on(30, 10, 20), updatedAt = on(30, 10, 20), demo = true)
        val mixed = TripLogData(drivers = listOf(kim), trips = listOf(newest, demoTrip))
        assertEquals("실제 모드는 시연 운행을 건너뜀", tripRowTitle(newest), homeIdle(mixed).headline.text)
        val demo = homeIdle(mixed.copy(profile = FleetProfile(demoMode = true)))
        assertEquals("시연 모드는 시연 운행만", tripRowTitle(demoTrip), demo.headline.text)
        assertEquals("10월 30일 (금) 10:00 · 일반 업무 · Kim Jiwoo", demo.line.text)
        assertEquals("시연 모드에 시연 운행이 없으면 아직 없음", none, homeIdle(TripLogData(trips = listOf(newest), profile = FleetProfile(demoMode = true))))
        listOf(classified, unclassified, demo).forEach { assertOneLine(it.line.text, HOME_CARD_LINE_UNITS) }
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
