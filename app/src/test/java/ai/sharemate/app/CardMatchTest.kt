package ai.sharemate.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CardMatchTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(hour: Int, minute: Int = 0, day: Int = 29) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private fun trip(seed: String, start: Long, end: Long?, destination: String? = null, fare: Long? = null, passes: List<TollPass> = emptyList(),
        purpose: TripPurpose = TripPurpose.BUSINESS) = Trip(id(seed), start, end, 100.0, 120.0, destination = destination, purpose = purpose, driverId = null,
        tollFareWon = fare, tollPasses = passes, updatedAt = end ?: start)
    private fun stop(seed: String, start: Long, end: Long, fuel: Boolean = false, place: String? = null) =
        ChargeSession(start, end, 40, 80, 120.0, id = id(seed), fuel = fuel, place = place)
    private fun approval(merchant: String, at: Long, won: Long, last4: String? = null, source: CardSource = CardSource.NOTIFICATION, timeKnown: Boolean = true, cancel: Boolean = false, occurrence: Int = 0) =
        CardApproval(cardApprovalId(at, timeKnown, won, merchant, last4, cancel, occurrence), at, timeKnown, won, merchant, last4, source, cancel)
    private fun run(data: TripLogData, vararg events: CardApproval, now: Long = at(23)) = settleCharges(mergeCardEvents(data, events.toList(), now, zone), now, zone)

    // ─── 알림 파싱 ───

    @Test fun readsCommonIssuerAlertLayouts() {
        val posted = at(14, 33)
        val shinhan = parseCardNotification("신한카드", "[Web발신]\n신한카드(1234)승인 권*호 63,250원(일시불)10/29 14:32 GS칼텍스 판교주유소 누적1,204,500원", posted, zone)!!
        assertEquals(63_250L, shinhan.amountWon); assertEquals("1234", shinhan.last4); assertEquals(at(14, 32), shinhan.at); assertFalse(shinhan.cancel)
        assertEquals(CostCategory.FUEL, categorize(shinhan.merchant)); assertTrue(shinhan.merchant, shinhan.merchant.contains("판교주유소"))
        assertFalse(shinhan.merchant, shinhan.merchant.contains("누적"))

        val kb = parseCardNotification(null, "KB국민카드 승인\n권*호님 18,400원 일시불\n10/29 14:30\n차지비 판교역점\n누적 2,300,000원", posted, zone)!!
        assertEquals(18_400L, kb.amountWon); assertEquals(CostCategory.CHARGE, categorize(kb.merchant))

        val before = parseCardNotification("삼성카드", "삼성카드 승인 5678 아이파킹 판교 4,500원 일시불 10/29 14:31", posted, zone)!!
        assertEquals(4_500L, before.amountWon); assertEquals("5678", before.last4); assertEquals(CostCategory.PARKING, categorize(before.merchant))
        assertEquals("카드번호는 가맹점명에 남지 않음", "아이파킹 판교", before.merchant)
        assertEquals("차지비 판교역점", kb.merchant)
    }

    @Test fun placeComparisonWorksAcrossSpacingAndBrandNames() {
        assertTrue(sharesPlace("아이파킹 강남", "강남 거래처"))
        assertTrue(sharesPlace("GS칼텍스판교주유소", "경기 성남시 분당구 판교역로"))
        assertTrue(sharesPlace("코엑스 주차장", "코엑스"))
        assertFalse("브랜드만 같고 지역이 다르면 위치 근거가 아님", sharesPlace("아이파킹 강남", "아이파킹 수원"))
        assertFalse(sharesPlace("GS칼텍스", "판교"))
        assertFalse(sharesPlace("아이파킹 강남", null))
    }

    @Test fun parkingNearTheArrivalPlaceBeatsATripThatOnlyOverlapsInTime() {
        val first = trip("a", at(9), at(10), "강남 거래처")
        val second = trip("b", at(10, 1), at(10, 5), "판교 연구소")
        val data = run(TripLogData(trips = listOf(first, second)), approval("아이파킹 강남", at(10, 6), 4_500))
        assertEquals(ChargeState.MATCHED, data.charges.single().state)
        assertEquals(4_500L, data.trips.first { it.id == first.id }.expenseWon)
    }

    @Test fun excludingAnAlreadyCancelledChargeChangesNothing() {
        val t = trip("a", at(13), at(15), "GS칼텍스 판교")
        val data = run(TripLogData(trips = listOf(t)), approval("GS칼텍스 판교", at(14), 50_000), approval("GS칼텍스 판교", at(14, 5), 50_000, cancel = true))
        val after = excludeCharge(data, data.charges.single().id, at(16))
        assertEquals(ChargeState.CANCELED, after.charges.single().state)
    }

    @Test fun readsACancellationAsACancellation() {
        val cancel = parseCardNotification("현대카드", "현대카드 승인취소 권*호 100,000원 10/29 14:35 SK에너지 판교", at(14, 36), zone)!!
        assertTrue(cancel.cancel); assertEquals(100_000L, cancel.amountWon)
    }

    @Test fun ignoresEverythingThatIsNotAPayment() {
        val posted = at(12)
        listOf(
            "신한카드" to "이번달 결제예정금액은 1,204,500원입니다",
            "KB국민카드" to "[광고] 주유 할인 이벤트 최대 10,000원 캐시백",
            "삼성카드" to "승인거절 한도초과 63,250원",
            "우리은행" to "인증번호 482910",
            "친구" to "오늘 점심 12,000원 내가 결제했어",
            "현대카드" to "10월 명세서가 도착했습니다 청구금액 1,000,000원",
        ).forEach { (title, text) -> assertNull(text, parseCardNotification(title, text, posted, zone)) }
    }

    @Test fun anAlertFromJanuaryAboutDecemberBelongsToLastYear() {
        val newYear = LocalDateTime.of(2027, 1, 1, 0, 2).atZone(zone).toInstant().toEpochMilli()
        val late = parseCardNotification("신한카드", "신한카드 승인 권*호 9,000원 일시불 12/31 23:59 GS칼텍스 양재", newYear, zone)!!
        assertEquals(LocalDateTime.of(2026, 12, 31, 23, 59).atZone(zone).toInstant().toEpochMilli(), late.at)
    }

    // ─── 가맹점 분류 ───

    @Test fun categorizesVehicleMerchantsAndNothingElse() {
        assertEquals(CostCategory.FUEL, categorize("SK에너지 판교셀프주유소"))
        assertEquals(CostCategory.FUEL, categorize("S-OIL 양재"))
        assertEquals(CostCategory.CHARGE, categorize("에버온 판교"))
        assertEquals(CostCategory.TOLL, categorize("한국도로공사"))
        assertEquals(CostCategory.TOLL, categorize("인천공항 신공항하이웨이"))
        assertEquals(CostCategory.PARKING, categorize("모두의주차장"))
        assertNull(categorize("스타벅스 판교점"))
        assertNull("교통카드 충전은 차량 비용이 아님", categorize("티머니 교통카드충전"))
        assertNull("휴게소 식당은 통행료가 아님", categorize("경부고속도로 안성휴게소"))
        assertEquals("휴게소 주유소는 주유", CostCategory.FUEL, categorize("안성휴게소 주유소"))
    }

    // ─── 매칭 ───

    @Test fun aCardChargeThatLinesUpWithTheCarIsWrittenWithNoTouch() {
        val t = trip("a", at(13), at(14, 10), "GS칼텍스 판교주유소")
        val s = stop("s", at(14, 12), at(14, 40), fuel = true, place = "GS칼텍스 판교주유소")
        val data = run(TripLogData(trips = listOf(t), stops = listOf(s), pendingCharge = s), approval("GS칼텍스 판교주유소", at(14, 41), 63_250))
        val expense = data.trips.single().expenses.single()
        assertEquals(63_250L, expense.amountWon); assertEquals(ExpenseType.FUEL, expense.type); assertEquals(EditSource.CARD, expense.source)
        assertEquals("GS칼텍스 판교주유소", expense.merchant)
        val charge = data.charges.single()
        assertEquals(ChargeState.MATCHED, charge.state); assertEquals(expense.cardChargeId, charge.id); assertTrue(charge.reason, charge.reason.contains("위치 일치"))
        assertEquals("비용을 묻던 카드는 사라짐", null, data.pendingCharge)
        assertEquals("같은 정차는 다시 쓰이지 않음", charge.id, data.stops.single().chargeId)
    }

    @Test fun pumpPreauthorisationCancelsOutAndOnlyTheRealAmountRemains() {
        val t = trip("a", at(13), at(14, 10), "GS칼텍스 판교주유소")
        val s = stop("s", at(14, 12), at(14, 40), fuel = true, place = "판교")
        var data = TripLogData(trips = listOf(t), stops = listOf(s))
        data = run(data, approval("GS칼텍스 판교주유소", at(14, 15), 100_000))
        assertEquals(100_000L, data.trips.single().expenseWon)
        data = run(data, approval("GS칼텍스 판교주유소", at(14, 38), 100_000, cancel = true))
        assertEquals(0L, data.trips.single().expenseWon)
        assertEquals(ChargeState.CANCELED, data.charges.single().state)
        assertNull("취소되면 정차가 비어 실제 금액을 받을 수 있음", data.stops.single().chargeId)
        data = run(data, approval("GS칼텍스 판교주유소", at(14, 39), 63_250))
        assertEquals(63_250L, data.trips.single().expenseWon)
    }

    @Test fun twoPossibleStopsTheSameDayAskInsteadOfGuessing() {
        val first = trip("a", at(9), at(10)); val second = trip("b", at(15), at(16))
        val stops = listOf(stop("s1", at(10, 5), at(10, 30), fuel = true), stop("s2", at(16, 5), at(16, 30), fuel = true))
        val data = run(TripLogData(trips = listOf(first, second), stops = stops),
            approval("SK에너지", at(12), 50_000, source = CardSource.STATEMENT, timeKnown = false))
        assertEquals(ChargeState.REVIEW, data.charges.single().state)
        assertTrue(data.trips.all { it.expenses.isEmpty() })
    }

    @Test fun tollFindsTheTripThroughGateAndRouteFare() {
        val plain = trip("a", at(9), at(10))
        val highway = trip("b", at(13), at(15), "부산", fare = 22_700, passes = listOf(TollPass(at(13, 40), "서울TG")))
        val data = run(TripLogData(trips = listOf(plain, highway)), approval("한국도로공사", at(12), 22_700, source = CardSource.STATEMENT, timeKnown = false))
        assertEquals(ChargeState.MATCHED, data.charges.single().state)
        assertEquals(ExpenseType.TOLL, data.trips.first { it.id == highway.id }.expenses.single().type)
        assertTrue(data.charges.single().reason, data.charges.single().reason.contains("예상요금"))
    }

    @Test fun parkingLandsOnTheTripThatArrivedThereEvenWhenPaidOnTheWayOut() {
        val arrive = trip("a", at(9), at(10), "코엑스")
        val leave = trip("b", at(15), at(16))
        val data = run(TripLogData(trips = listOf(arrive, leave)), approval("코엑스 주차장", at(15, 5), 12_000))
        assertEquals(12_000L, data.trips.first { it.id == arrive.id }.expenseWon)
        assertEquals(0L, data.trips.first { it.id == leave.id }.expenseWon)
    }

    @Test fun aCostOnAnOpenTripIsAttachedWhileDriving() {
        val driving = trip("a", at(13), null, "부산", fare = 3_600)
        val data = run(TripLogData(open = driving), approval("한국도로공사", at(13, 20), 3_600))
        assertEquals(3_600L, data.open!!.expenseWon)
    }

    @Test fun aTypedAmountIsCorrectedRatherThanDoubled() {
        val typed = TripExpense(ExpenseType.FUEL, 60_000, at(14, 45), EditSource.DRIVER)
        val t = trip("a", at(13), at(14, 10), "GS칼텍스 판교").copy(expenses = listOf(typed))
        val data = run(TripLogData(trips = listOf(t), stops = listOf(stop("s", at(14, 12), at(14, 40), fuel = true))), approval("GS칼텍스 판교", at(14, 41), 63_250))
        val expense = data.trips.single().expenses.single()
        assertEquals(63_250L, expense.amountWon); assertEquals("입력 출처는 유지", EditSource.DRIVER, expense.source); assertNotNull(expense.cardChargeId)
    }

    // ─── 유입 필터 ───

    @Test fun otherCardsAndUnrelatedShoppingNeverEnterTheLog() {
        val t = trip("a", at(13), at(15))
        val base = TripLogData(profile = FleetProfile(cards = listOf("1234")), trips = listOf(t))
        assertTrue("등록되지 않은 카드", run(base, approval("GS칼텍스", at(14), 50_000, last4 = "9999")).charges.isEmpty())
        val unregistered = TripLogData(trips = listOf(t))
        assertTrue("차량과 무관한 가맹점", run(unregistered, approval("스타벅스 판교", at(14), 5_000)).charges.isEmpty())
    }

    @Test fun aRegisteredCardWithAnUnknownMerchantStillMatchesARefuelTheCarSaw() {
        val t = trip("a", at(13), at(14, 10), "대성상사 판교")
        val s = stop("s", at(14, 12), at(14, 40), fuel = true, place = "대성상사 판교")
        val data = run(TripLogData(profile = FleetProfile(cards = listOf("1234")), trips = listOf(t), stops = listOf(s)), approval("대성상사 판교점", at(14, 41), 70_000, last4 = "1234"))
        assertEquals(ChargeState.MATCHED, data.charges.single().state)
    }

    @Test fun anUnknownMerchantWithNoCarEvidenceIsForgottenAfterTheWait() {
        val base = TripLogData(profile = FleetProfile(cards = listOf("1234")))
        val waiting = run(base, approval("대성상사", at(9), 70_000, last4 = "1234"), now = at(10))
        assertEquals(ChargeState.WAITING, waiting.charges.single().state)
        assertTrue(settleCharges(waiting, at(20), zone).charges.isEmpty())
    }

    @Test fun aVehicleMerchantWithNoTripEndsUpAsAQuestion() {
        val waiting = run(TripLogData(), approval("GS칼텍스", at(9), 50_000), now = at(10))
        assertEquals(ChargeState.WAITING, waiting.charges.single().state)
        val late = settleCharges(waiting, at(20), zone).charges.single()
        assertEquals(ChargeState.REVIEW, late.state); assertNull(late.tripId)
    }

    @Test fun aRepeatedAlertAndARepeatedImportCountOnce() {
        val t = trip("a", at(13), at(15), "한국도로공사")
        val sms = approval("한국도로공사", at(14), 3_600)
        val app = approval("한국도로공사(판교)", at(14, 1), 3_600)
        var data = run(TripLogData(trips = listOf(t)), sms, app, sms)
        assertEquals(1, data.charges.size)
        val line = approval("한국도로공사", at(12), 3_600, source = CardSource.STATEMENT, timeKnown = false)
        data = run(data, line); data = run(data, line)
        assertEquals("명세서는 알림을 확인할 뿐 새 건이 아님", 1, data.charges.size)
        assertEquals(line.id, data.charges.single().statementId)
        assertEquals(3_600L, data.trips.single().expenseWon)
    }

    // ─── 사람의 결정 ───

    @Test fun excludingTakesTheCostBackOffAndItStaysOff() {
        val t = trip("a", at(13), at(15), "GS칼텍스 판교")
        var data = run(TripLogData(trips = listOf(t)), approval("GS칼텍스 판교", at(14), 50_000))
        assertEquals(50_000L, data.trips.single().expenseWon)
        data = excludeCharge(data, data.charges.single().id, at(16))
        assertEquals(0L, data.trips.single().expenseWon)
        data = settleCharges(data, at(17), zone)
        assertEquals(ChargeState.EXCLUDED, data.charges.single().state); assertEquals(0L, data.trips.single().expenseWon)
    }

    @Test fun confirmingAQuestionWritesItToTheChosenTrip() {
        val first = trip("a", at(9), at(10)); val second = trip("b", at(15), at(16))
        val stops = listOf(stop("s1", at(10, 5), at(10, 30), fuel = true), stop("s2", at(16, 5), at(16, 30), fuel = true))
        val review = run(TripLogData(trips = listOf(first, second), stops = stops), approval("SK에너지", at(12), 50_000, source = CardSource.STATEMENT, timeKnown = false))
        val data = confirmCharge(review, review.charges.single().id, second.id, at(17))
        assertEquals(50_000L, data.trips.first { it.id == second.id }.expenseWon)
        assertEquals(ChargeState.MATCHED, data.charges.single().state)
    }

    @Test fun switchingAutopilotOffLeavesCardRecordsUntouched() {
        val t = trip("a", at(13), at(15), "GS칼텍스 판교")
        val data = run(TripLogData(profile = FleetProfile(autopilot = false), trips = listOf(t)), approval("GS칼텍스 판교", at(14), 50_000))
        assertEquals(ChargeState.WAITING, data.charges.single().state); assertTrue(data.trips.single().expenses.isEmpty())
    }

    @Test fun aCardCostOnAPersonalTripRaisesAnAuditFlag() {
        val t = trip("a", at(13), at(15), "GS칼텍스 판교", purpose = TripPurpose.PERSONAL)
        val data = run(TripLogData(trips = listOf(t)), approval("GS칼텍스 판교", at(14), 50_000))
        assertTrue(auditTrips(data.trips).any { it.type == IssueType.CARD_ON_PERSONAL })
    }

    // ─── 차량 증거 ───

    @Test fun aRefillWhileTheHeadUnitWasOffShowsUpAsAStop() {
        val card = VehicleCard(socPercent = 40, fuelPercent = null, capturedAt = at(10))
        val after = DriveSample(at(12), GearState.PARK, 100.0, socPercent = 80)
        val stop = refillAcrossGap(card, after)!!
        assertEquals(40, stop.socFrom); assertEquals(80, stop.socTo); assertFalse(stop.fuel); assertEquals(at(10), stop.startAt)
        val fuel = refillAcrossGap(VehicleCard(fuelPercent = 20, capturedAt = at(10)), DriveSample(at(12), GearState.PARK, 100.0, fuelPercent = 70))!!
        assertTrue(fuel.fuel)
        assertNull("주행 중 회생제동은 충전이 아님", refillAcrossGap(card, after.copy(gear = GearState.MOVING)))
        assertNull("잔량 오차", refillAcrossGap(card, after.copy(socPercent = 41)))
        assertNull("충전 신호가 보이는 동안은 세션이 맡음", refillAcrossGap(card, after.copy(charging = true)))
        assertNull("첫 기록에는 기준값이 없음", refillAcrossGap(VehicleCard(socPercent = 40), after))
    }

    @Test fun tollGateCountsWhenGuidanceMovesPastIt() {
        assertTrue(tollGatePassed(300.0, null))
        assertTrue("다음 톨게이트가 멀리 나타남", tollGatePassed(300.0, 25_000.0))
        assertFalse("계속 접근 중", tollGatePassed(900.0, 300.0))
        assertFalse("아직 멀리서 경로가 바뀐 경우", tollGatePassed(9_000.0, null))
        assertFalse(tollGatePassed(null, 300.0))
    }

    @Test fun recordingCollectsGatesAndFareOntoTheTrip() {
        val base = Trip(id("t"), at(9, 26), startOdometerKm = 100.0, endOdometerKm = 100.0, updatedAt = at(9, 26))
        val first = advanceTrip(base, DriveSample(at(9, 30), GearState.MOVING, 130.0, "서울TG", tollFareWon = 22_700, tollPassAt = at(9, 29)), null) { "x" }.single() as TripChange.Progress
        assertEquals(22_700L, first.trip.tollFareWon); assertEquals(1, first.trip.tollPasses.size); assertEquals("서울TG", first.trip.tollPasses[0].place)
        val again = advanceTrip(first.trip, DriveSample(at(9, 31), GearState.MOVING, 131.0, tollPassAt = at(9, 29)), null) { "x" }.single() as TripChange.Progress
        assertEquals("같은 통과를 두 번 세지 않음", 1, again.trip.tollPasses.size)
    }

    // ─── 자동 목적 ───

    @Test fun aPlaceLabelledTheSameWayThreeTimesLabelsItself() {
        fun past(seed: String, purpose: TripPurpose, source: EditSource = EditSource.DRIVER) = trip(seed, at(9, day = 20), at(10, day = 20), "판교 연구소", purpose = purpose).copy(purposeSource = source)
        val next = trip("new", at(9), at(10), "판교 연구소", purpose = TripPurpose.UNCLASSIFIED)
        assertNull("두 번은 부족", autoPurpose(next, listOf(past("1", TripPurpose.BUSINESS), past("2", TripPurpose.BUSINESS))))
        assertEquals(TripPurpose.BUSINESS, autoPurpose(next, listOf(past("1", TripPurpose.BUSINESS), past("2", TripPurpose.BUSINESS), past("3", TripPurpose.BUSINESS))))
        assertNull("기록이 갈리면 묻는다", autoPurpose(next, listOf(past("1", TripPurpose.BUSINESS), past("2", TripPurpose.PERSONAL), past("3", TripPurpose.BUSINESS))))
        assertNull("자동 분류끼리는 증거가 안 됨", autoPurpose(next, listOf(past("1", TripPurpose.BUSINESS, EditSource.AUTO), past("2", TripPurpose.BUSINESS, EditSource.AUTO), past("3", TripPurpose.BUSINESS, EditSource.AUTO))))
    }

    @Test fun summaryCountsWhatFinishedItselfAndWhatIsLeft() {
        val auto = trip("a", at(9), at(10), "판교").copy(purposeSource = EditSource.AUTO, driverId = null)
        val done = TripLogData(profile = FleetProfile(), drivers = listOf(Driver(id("d"), "영업", "권")), trips = listOf(auto.copy(driverId = id("d")), trip("b", at(11), at(12), purpose = TripPurpose.UNCLASSIFIED)))
        val data = run(done, approval("GS칼텍스 판교", at(9, 30), 50_000))
        val summary = autopilotSummary(data, java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 31), zone)
        assertEquals(2, summary.trips); assertEquals(1, summary.complete); assertEquals(50, summary.percent)
        assertEquals(1, summary.autoPurpose); assertEquals(1, summary.cardMatched); assertEquals(50_000L, summary.cardWon)
        assertEquals("미분류·미지정 운행도 사람이 볼 일로 센다", 1, summary.left)
    }

    // ─── 명세서 ───

    @Test fun readsCsvWithQuotedThousandsInMs949() {
        val csv = """
            이용카드 조회 결과,,,
            이용일시,이용카드,가맹점명,이용금액,승인구분
            2026.10.29 14:32,본인 ****-1234,GS칼텍스 판교주유소,"63,250",승인
            2026.10.29,본인 ****-1234,한국도로공사,"3,600원",승인
            2026.10.28 09:10,본인 ****-1234,GS칼텍스 양재,"100,000",취소
            합계,,,"166,850",
        """.trimIndent().toByteArray(java.nio.charset.Charset.forName("MS949"))
        val rows = parseStatementFile(csv, zone)
        assertEquals(3, rows.size)
        assertEquals(at(14, 32), rows[0].at); assertTrue(rows[0].timeKnown); assertEquals("1234", rows[0].last4); assertEquals(63_250L, rows[0].amountWon)
        assertFalse("시각 없는 줄", rows[1].timeKnown); assertEquals(3_600L, rows[1].amountWon)
        assertTrue(rows[2].cancel)
    }

    @Test fun readsDateAndTimeInSeparateColumnsAndNegativeAmounts() {
        val rows = statementApprovals(listOf(listOf("승인일자", "승인시간", "이용가맹점", "승인금액"),
            listOf("20261029", "14:32:10", "SK에너지", "-50000"), listOf("26-10-29", "", "에버온", "20000")), zone)
        assertEquals(at(14, 32), rows[0].at); assertTrue(rows[0].cancel); assertEquals(50_000L, rows[0].amountWon)
        assertFalse(rows[1].timeKnown)
    }

    @Test fun identicalLinesStayDistinctButReimportingTheFileChangesNothing() {
        val rows = listOf(listOf("이용일", "가맹점", "금액"), listOf("2026-10-29", "한국도로공사", "3600"), listOf("2026-10-29", "한국도로공사", "3600"))
        val first = statementApprovals(rows, zone); val second = statementApprovals(rows, zone)
        assertEquals(2, first.map { it.id }.distinct().size); assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test fun readsTheHtmlThatManyXlsDownloadsReallyAre() {
        val html = "<html><table><tr><th>이용일</th><th>가맹점</th><th>금액</th></tr><tr><td>2026.10.29</td><td>아이파킹&nbsp;판교</td><td>4,500</td></tr></table></html>"
        val rows = parseStatementFile(html.toByteArray(), zone)
        assertEquals(1, rows.size); assertEquals(4_500L, rows[0].amountWon); assertTrue(rows[0].merchant, rows[0].merchant.contains("아이파킹"))
    }

    @Test fun readsAnXlsxWorkbookWithSharedStringsAndExcelDates() {
        val shared = "<sst><si><t>이용일</t></si><si><t>가맹점</t></si><si><t>금액</t></si><si><t>GS칼텍스 판교</t></si></sst>"
        val sheet = """<worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c></row>
            <row r="2"><c r="A2"><v>46324.60555</v></c><c r="B2" t="s"><v>3</v></c><c r="C2"><v>63250</v></c></row>
        </sheetData></worksheet>"""
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                listOf("xl/sharedStrings.xml" to shared, "xl/worksheets/sheet1.xml" to sheet).forEach { (name, body) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
                }
            }
        }.toByteArray()
        val row = parseStatementFile(bytes, zone).single()
        assertEquals(63_250L, row.amountWon); assertEquals("GS칼텍스 판교", row.merchant); assertTrue(row.timeKnown)
        assertEquals(java.time.LocalDate.of(2026, 10, 29), java.time.Instant.ofEpochMilli(row.at).atZone(zone).toLocalDate())
    }

    @Test fun refusesTheOldBinaryFormatWithAUsefulMessage() {
        val error = assertThrows(IllegalArgumentException::class.java) { parseStatementFile(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0, 0), zone) }
        assertTrue(error.message, error.message!!.contains(".xlsx"))
    }

    @Test fun refusesAFileWithoutTheColumnsItNeeds() {
        assertThrows(IllegalArgumentException::class.java) { statementApprovals(listOf(listOf("이름", "전화번호"), listOf("권", "010")), zone) }
    }

    @Test fun onlyLinesThatCouldBeTheCarLeaveThePhone() {
        val stops = listOf(stop("s", at(14), at(14, 30)))
        val rows = listOf(approval("GS칼텍스", at(9, day = 2), 1), approval("스타벅스", at(9, day = 2), 2),
            approval("대성상사", at(15), 3, last4 = "1234"), approval("스타벅스", at(15), 4))
        assertEquals("등록 카드가 없으면 이름으로 확인되는 비용만", listOf(1L), vehicleRelevant(rows, TripLogData(stops = stops), zone).map { it.amountWon })
        val registered = TripLogData(profile = FleetProfile(cards = listOf("1234")), stops = stops)
        assertEquals("등록 카드는 충전·주유한 날에 한해 함께", listOf(1L, 3L), vehicleRelevant(rows, registered, zone).map { it.amountWon })
    }

    // ─── 프로토콜·저장 ───

    @Test fun cardCommandsSurviveTheWireAndBadOnesAreRefused() {
        val tripId = id("t")
        val commands = listOf(SyncCommand.CardEvents(listOf(approval("GS칼텍스", at(14), 63_250, last4 = "1234"), approval("한국도로공사", at(12), 3_600, source = CardSource.STATEMENT, timeKnown = false, cancel = true))),
            SyncCommand.ConfirmCharge(id("c"), tripId), SyncCommand.ExcludeCharge(id("c")))
        commands.forEach { assertEquals(it, parseSyncCommand(JSONObject(encodeSyncCommand(it).toString()))) }
        val good = encodeSyncCommand(commands[0]).toString()
        assertThrows(Exception::class.java) { parseSyncCommand(JSONObject(good.replace("\"1234\"", "\"12\""))) }
        assertThrows(Exception::class.java) { parseSyncCommand(JSONObject(good.replace("63250", "0"))) }
        assertThrows(Exception::class.java) { parseSyncCommand(JSONObject().put("type", "cardEvents").put("events", org.json.JSONArray())) }
    }

    @Test fun everythingAddedHereSurvivesSaveAndLoad() {
        val t = trip("a", at(13), at(15), "GS칼텍스 판교", fare = 3_600, passes = listOf(TollPass(at(13, 30), "서울TG")))
        val s = stop("s", at(14), at(14, 30), fuel = true, place = "판교")
        val source = run(TripLogData(profile = FleetProfile(autopilot = false, cards = listOf("1234", "5678")), trips = listOf(t), stops = listOf(s), vehicle = VehicleCard(fuelPercent = 55, capturedAt = 1)), approval("GS칼텍스 판교", at(14, 20), 50_000))
        val withExpense = settleCharges(source.copy(profile = source.profile.copy(autopilot = true)), at(23), zone)
        val copy = TripLogCodec.decode(TripLogCodec.encode(withExpense))
        assertEquals(withExpense.trips, copy.trips); assertEquals(withExpense.stops, copy.stops); assertEquals(withExpense.charges, copy.charges)
        assertEquals(withExpense.profile, copy.profile); assertEquals(55, copy.vehicle.fuelPercent)
        assertEquals("카드 비용이 저장됨", "GS칼텍스 판교", copy.trips.single().expenses.single().merchant)
    }

    @Test fun filesFromBeforeThisVersionStillLoad() {
        val old = JSONObject(TripLogCodec.encode(TripLogData()).toString(Charsets.UTF_8))
        old.remove("stops"); old.remove("charges"); old.getJSONObject("profile").apply { remove("autopilot"); remove("cards") }
        val data = TripLogCodec.decode(old.toString().toByteArray())
        assertTrue(data.profile.autopilot); assertTrue(data.charges.isEmpty()); assertTrue(data.stops.isEmpty())
    }

    @Test fun demoCardFollowsWhatTheDemoCarJustDid() {
        val s = ChargeSession(at(14), at(14, 30), 40, 80, 120.0, demo = true, id = id("s"))
        val t = trip("a", at(13), at(14)).copy(demo = true)
        assertTrue(demoCardApproval(TripLogData(trips = listOf(t), stops = listOf(s)), at(15)).merchant.startsWith("차지비"))
        val toll = t.copy(tollFareWon = 3_600, tollPasses = listOf(TollPass(at(13, 30), null)))
        assertEquals("한국도로공사", demoCardApproval(TripLogData(trips = listOf(toll)), at(15)).merchant)
        assertTrue(demoCardApproval(TripLogData(trips = listOf(t)), at(15)).merchant.startsWith("아이파킹"))
    }
}
