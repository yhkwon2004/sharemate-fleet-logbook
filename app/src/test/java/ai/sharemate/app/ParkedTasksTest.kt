package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class ParkedTasksTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val now = at(2, 14, 30)
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val kim = Driver(id("kim"), "영업1팀", "김서연")
    private val park = Driver(id("park"), "영업2팀", "박지훈")
    private fun trip(seed: String, day: Int, hour: Int, purpose: TripPurpose = TripPurpose.BUSINESS, source: EditSource? = EditSource.DRIVER,
        destination: String? = "강남 오피스", driverId: String? = kim.id, demo: Boolean = false, expenses: List<TripExpense> = emptyList()) =
        Trip(id(seed), at(day, hour), at(day, hour) + 24 * 60_000, 1_000.0 + day * 10, 1_012.4 + day * 10, destination = destination,
            driverId = driverId, purpose = purpose, purposeSource = source, demo = demo, expenses = expenses, updatedAt = at(day, hour) + 24 * 60_000)
    private val ready = FleetProfile(companyName = "쉐어메이트", plateNumber = "123가4567", cards = emptyList())
    private fun base(profile: FleetProfile = ready) = TripLogData(profile = profile, drivers = listOf(kim, park), currentDriverId = kim.id)

    // ───── 우선순위와 주행 화면 부분집합

    @Test fun tasksFollowTheHomePriorityAndCountOnlyTheCurrentDataSet() {
        val incident = Incident(id("i"), now - 60_000, IncidentKind.DAMAGE, 1_020.0, null, null, kim.id)
        val review = CardCharge(id("c1"), now - 3_600_000, true, 4_500, "아이파킹 강남", null, CardSource.NOTIFICATION, CostCategory.PARKING, ChargeState.REVIEW)
        val demoReview = review.copy(id = id("c2"), demo = true)
        val stop = ChargeSession(now - 40 * 60_000, now - 30 * 60_000, 40, 80, 1_030.0, id = id("s"))
        val data = base(FleetProfile(phoneSync = true)).copy(
            incidents = listOf(incident, incident.copy(id = id("closed"), status = IncidentStatus.CLOSED)),
            charges = listOf(review, demoReview),
            pendingCharge = stop,
            trips = listOf(trip("a", 2, 9, TripPurpose.UNCLASSIFIED, null), trip("b", 2, 11, TripPurpose.UNCLASSIFIED, null), trip("d", 2, 12, TripPurpose.UNCLASSIFIED, null, demo = true)),
            care = VehicleCare(inspectionDue = "2026-11-20"))
        val tasks = parkedTasks(data, SyncInfo(running = true, pairedPhones = 0), now, zone)
        assertEquals(listOf(TaskKind.INCIDENT, TaskKind.CARD_REVIEW, TaskKind.CHARGE, TaskKind.PURPOSE, TaskKind.SETUP, TaskKind.PAIRING, TaskKind.CARE), tasks.map { it.kind })
        assertEquals("열린 기록만", 1, tasks[0].count)
        assertEquals("같은 데이터 세트의 카드만", 1, tasks[1].count)
        assertEquals("시연 운행은 세지 않음", 2, tasks[3].count)
        assertEquals("D-18 정기검사", 1, tasks.last().count)
        // The driving screen and the home badge mention only what the car itself detected.
        assertEquals(listOf(TaskKind.INCIDENT, TaskKind.CARD_REVIEW, TaskKind.CHARGE, TaskKind.PURPOSE), drivingTasks(tasks).map { it.kind })
        assertEquals(5, parkedTaskBadge(tasks))
        assertEquals("정차 후 · 특이사항 대응 1건 · 카드 확인 1건 · 충전 비용 1건 · 목적 확인 2건", drivingTaskLine(tasks))
        assertEquals("정차 후 할 일 없음", drivingTaskLine(emptyList()))
        assertEquals("정차 후 정리 2/4 · 충전 비용 → 목적 확인", stepperLabel(tasks.drop(2).take(2), 2, 4))
        assertEquals("2/4 · 충전 비용", stepperLabel(tasks.drop(2).take(2), 2, 4, narrow = true))
    }

    @Test fun chargeQuestionWaitsForTheCardFeedUnlessTheDriverSaysOtherwise() {
        val stop = ChargeSession(now - 15 * 60_000, now - 5 * 60_000, 40, 80, 1_030.0, id = id("s"))
        val noFeed = base().copy(pendingCharge = stop)
        val feed = base(ready.copy(cards = listOf("1234"))).copy(pendingCharge = stop)
        assertTrue("카드가 없으면 바로 물어봄", pendingChargeVisible(noFeed, now))
        assertFalse("카드 피드가 있으면 20분 기다림", pendingChargeVisible(feed, now))
        assertTrue(pendingChargeVisible(feed, now + 16 * 60_000))
        assertTrue("지금 입력", pendingChargeVisible(feed, now, ChargePrompt.NOW))
        assertFalse("승인 기다리기", pendingChargeVisible(feed, now + 60 * 60_000, ChargePrompt.WAIT))
        assertTrue("카드 없이 기다리기는 의미가 없음", pendingChargeVisible(noFeed, now, ChargePrompt.WAIT))
        assertEquals(stop, hiddenPendingCharge(feed, now))
        assertNull(hiddenPendingCharge(noFeed, now))
        assertEquals(listOf(TaskKind.CHARGE), parkedTasks(noFeed, SyncInfo(), now, zone).map { it.kind })
        assertTrue(parkedTasks(feed, SyncInfo(), now, zone).none { it.kind == TaskKind.CHARGE })
        assertEquals("충전 종료 14:25 · 카드 승인 기다리는 중 · 20분 안에 안 오면 금액을 물어볼게요", chargeWaitingLine(stop, ChargePrompt.AUTO, zone))
        assertEquals("주유 종료 14:25 · 카드 승인 기다리는 중 · 승인이 오면 자동으로 들어가요", chargeWaitingLine(stop.copy(fuel = true), ChargePrompt.WAIT, zone))
    }

    @Test fun monthCloseAndCareSnoozeAreTheQuietTail() {
        val october = base().copy(trips = listOf(trip("o", 2, 9)).map { it.copy(startAt = at(1, 9) - 2 * 24 * 3_600_000, endAt = at(1, 10) - 2 * 24 * 3_600_000) },
            care = VehicleCare(insuranceDue = "2026-11-05", leaseEnd = "2028-01-01", serviceIntervalKm = 10_000, lastServiceKm = 0.0))
        val tasks = parkedTasks(october, SyncInfo(), now, zone, odometerKm = 9_500.0)
        assertEquals(listOf(TaskKind.MONTH_CLOSE, TaskKind.CARE), tasks.map { it.kind })
        assertEquals("보험 만기와 정비 주기 둘 다, 리스는 아직 멀어서 제외", 2, tasks.last().count)
        val alerts = careAlerts(october.care, 9_500.0, LocalDate.of(2026, 11, 2))
        val snoozed = alerts.filter { it.level != CareLevel.INFO }.map { careSnoozeKey(october.care, it) }.toSet()
        assertTrue(parkedTasks(october, SyncInfo(), now, zone, snoozedCare = snoozed, odometerKm = 9_500.0).none { it.kind == TaskKind.CARE })
        assertNotEquals("기한이 바뀌면 다른 키", careSnoozeKey(october.care, alerts[0]), careSnoozeKey(october.care.copy(insuranceDue = "2026-11-06"), alerts[0]))
        val closed = october.copy(closedMonths = listOf(MonthClose("2026-10", now, 1, 12.4, 12.4, 0)))
        assertTrue(parkedTasks(closed, SyncInfo(), now, zone, odometerKm = 9_500.0).none { it.kind == TaskKind.MONTH_CLOSE })
        assertEquals("오늘 정리 끝 · 다음 운행도 자동 기록", allDoneText(SignalStatus.READY))
        assertEquals("오늘 정리 끝 · 차량 신호를 받으면 자동 기록해요", allDoneText(SignalStatus.DENIED))
    }

    // ───── 방금 운행

    @Test fun suggestedDefaultCountsOnlyHumanAnswersForTheSamePlace() {
        val history = listOf(trip("1", 1, 9), trip("2", 1, 13), trip("3", 1, 17, source = EditSource.AUTO), trip("4", 1, 18, TripPurpose.PERSONAL, destination = "집"),
            trip("5", 1, 19, TripPurpose.UNCLASSIFIED, null))
        val current = trip("now", 2, 13, TripPurpose.UNCLASSIFIED, null)
        assertEquals("자동 분류와 미분류는 근거가 아님", 2, sameDestinationCount(current, history + current))
        assertEquals(2, sameDestinationCount(current, history + current, TripPurpose.BUSINESS))
        assertEquals(0, sameDestinationCount(current, history + current, TripPurpose.PERSONAL))
        assertEquals("일반 업무로 기록 · 지난 2회와 같아요", suggestedPurposeLabel(TripPurpose.BUSINESS, 2))
        assertEquals("출·퇴근으로 기록 · 지난번과 같아요", suggestedPurposeLabel(TripPurpose.COMMUTE, 1))
        assertEquals("같은 도착지 2회 기록 · 1번 더 같으면 다음부터 자동으로 채워요", autoPurposeProgress(current, history + current))
        assertEquals("같은 도착지 3회 일반 업무 기록 · 다음 운행부터 자동으로 채워요", autoPurposeProgress(current, history + current + trip("6", 1, 20)))
        assertNull("답이 갈리면 약속하지 않음", autoPurposeProgress(current, history + current + trip("7", 1, 21, TripPurpose.PERSONAL)))
        assertNull("자동 완성이 꺼져 있으면 말하지 않음", autoPurposeProgress(current, history + current, autopilot = false))
        assertNull(autoPurposeProgress(current.copy(destination = "처음 가는 곳"), history))
        assertEquals("강남 오피스 · 2회 같았던 목적으로 자동 분류", autoFillMessage(current.copy(purpose = TripPurpose.BUSINESS), history + current))
    }

    @Test fun finishedCardIsAMomentNotAChore() {
        val done = trip("f", 2, 14, source = EditSource.AUTO).copy(endAt = now - 30_000)
        val data = base().copy(trips = listOf(done))
        assertEquals(done, finishedTripToShow(data, done.id, now, emptySet()))
        assertNull("확인했으면 다시 안 나옴", finishedTripToShow(data, done.id, now, setOf(done.id)))
        assertNull("2분이 지나면 조용히 사라짐", finishedTripToShow(data, done.id, now + FINISHED_CARD_MS, emptySet()))
        assertNull("직접 고른 목적은 알릴 일이 아님", finishedTripToShow(data.copy(trips = listOf(done.copy(purposeSource = EditSource.DRIVER))), done.id, now, emptySet()))
        assertNull(finishedTripToShow(data.copy(profile = ready.copy(autopilot = false)), done.id, now, emptySet()))
        assertNull(finishedTripToShow(data, null, now, emptySet()))
        val toll = CardCharge(id("t"), now - 60_000, true, 3_600, "한국도로공사", null, CardSource.NOTIFICATION, CostCategory.TOLL, ChargeState.MATCHED, tripId = done.id)
        assertEquals(toll, matchedChargeFor(done, data.copy(charges = listOf(toll, toll.copy(id = id("x"), state = ChargeState.EXCLUDED)))))
        assertNull(matchedChargeFor(done, data))
    }

    // ───── 충전 금액 앵커

    @Test fun chargeAnchorsAreThisCarsOwnPastPaymentsAndNothingElse() {
        val oldStop = ChargeSession(at(1, 18), at(1, 18, 40), 35, 80, 900.0, id = id("s1"), place = "차지비 판교")
        val matched = CardCharge(id("m"), at(1, 18, 45), true, 18_400, "차지비 판교", null, CardSource.NOTIFICATION, CostCategory.CHARGE, ChargeState.MATCHED, stopId = oldStop.id)
        val linkedStop = oldStop.copy(chargeId = matched.id)
        val typedStop = ChargeSession(at(1, 8), at(1, 8, 30), 30, 78, 850.0, id = id("s2"), place = "집 앞 충전소")
        val typedTrip = trip("typed", 1, 7, expenses = listOf(TripExpense(ExpenseType.FUEL, 22_000, at(1, 8, 31), EditSource.DRIVER)))
        val farStop = ChargeSession(at(1, 12), at(1, 12, 20), 70, 80, 870.0, id = id("s3"), place = "다른 곳")
        val farTrip = trip("far", 1, 11, expenses = listOf(TripExpense(ExpenseType.FUEL, 9_000, at(1, 12, 21), EditSource.DRIVER)))
        val session = ChargeSession(now - 30 * 60_000, now - 60_000, 40, 83, 1_020.0, id = id("now"), place = "차지비 판교")
        val data = base().copy(stops = listOf(linkedStop, typedStop, farStop, session), charges = listOf(matched), trips = listOf(typedTrip, farTrip), pendingCharge = session)
        val anchors = recentChargeAmounts(session, data, zone)
        assertEquals(listOf("11/1 18,400원 · 이곳", "11/1 22,000원 · 비슷한 충전"), anchors.map { it.label })
        assertEquals(listOf(18_400L, 22_000L), anchors.map { it.amountWon })
        assertTrue("연료 세션은 충전 이력을 쓰지 않음", recentChargeAmounts(session.copy(fuel = true), data, zone).isEmpty())
        assertTrue("처음 보는 차에는 앵커가 없음", recentChargeAmounts(session, base().copy(pendingCharge = session), zone).isEmpty())
    }

    // ───── 카드 자동 반영 묶음

    @Test fun newlyMatchedChargesAndTheirTripsAreListedForReversal() {
        val a = CardCharge(id("a"), at(1, 9), true, 18_400, "차지비", null, CardSource.STATEMENT, CostCategory.CHARGE, ChargeState.MATCHED, tripId = id("ta"))
        val b = CardCharge(id("b"), at(2, 9), true, 3_600, "한국도로공사", null, CardSource.STATEMENT, CostCategory.TOLL, ChargeState.MATCHED, tripId = id("tb"))
        val waiting = CardCharge(id("w"), at(2, 10), true, 5_000, "주차", null, CardSource.NOTIFICATION, CostCategory.PARKING)
        val before = base().copy(charges = listOf(a, waiting))
        val after = before.copy(charges = listOf(b, a, waiting), trips = listOf(
            trip("ta", 1, 8, expenses = listOf(TripExpense(ExpenseType.FUEL, 18_400, at(1, 9), EditSource.CARD, a.id, "차지비"))),
            trip("tb", 2, 8, expenses = listOf(TripExpense(ExpenseType.TOLL, 3_600, at(2, 9), EditSource.CARD, b.id, "한국도로공사"), TripExpense(ExpenseType.PARKING, 2_000, at(2, 9), EditSource.DRIVER))),
            trip("old", 2, 7).copy(startAt = at(1, 7) - 40L * 24 * 3_600_000, endAt = at(1, 8) - 40L * 24 * 3_600_000,
                expenses = listOf(TripExpense(ExpenseType.FUEL, 1_000, at(1, 7), EditSource.CARD, a.id)))))
        assertEquals(listOf(b), newlyMatched(matchedIds(before), after))
        assertEquals(listOf(a, b), newlyMatched(emptySet(), after))
        val rows = matchedTripsThisMonth(after, zone, LocalDate.of(2026, 11, 2))
        assertEquals("이달 운행만, 최신 먼저", listOf(id("tb"), id("ta")), rows.map { it.first.id })
        assertEquals("카드로 들어온 비용만", listOf(3_600L), rows[0].second.map { it.amountWon })
    }

    // ───── 오늘의 운전자

    @Test fun driverQuestionIsAskedOnceADayAndOnlyWhenItCouldBeWrong() {
        val yesterday = trip("y", 1, 9).copy(endAt = now - 20 * 3_600_000)
        val data = base().copy(trips = listOf(yesterday))
        assertEquals(kim, driverConfirmDue(data, now, zone, emptySet()))
        assertNull("오늘 이미 답함", driverConfirmDue(data, now, zone, setOf(dateKey(now, zone))))
        assertNull("8시간 안에 운전했으면 묻지 않음", driverConfirmDue(data.copy(trips = listOf(yesterday.copy(endAt = now - 3 * 3_600_000))), now, zone, emptySet()))
        assertNull("직원이 한 명이면 묻지 않음", driverConfirmDue(data.copy(drivers = listOf(kim)), now, zone, emptySet()))
        assertNull("설정에서 끄면 묻지 않음", driverConfirmDue(data.copy(profile = ready.copy(confirmDriverDaily = false)), now, zone, emptySet()))
        assertNull("운행 중에는 묻지 않음", driverConfirmDue(data.copy(open = yesterday.copy(id = id("open"), endAt = null)), now, zone, emptySet()))
        assertNull("선택된 직원이 없으면 물을 대상이 없음", driverConfirmDue(data.copy(currentDriverId = null), now, zone, emptySet()))
        assertEquals("처음 운전하는 직원도 한 번은 확인", kim, driverConfirmDue(data.copy(trips = emptyList()), now, zone, emptySet()))
        assertEquals("2026-11-02", dateKey(now, zone))
    }

    @Test fun autopilotSublineNeverSaysNothingWhileAPaymentWaits() {
        assertEquals("사람이 확인할 일 없음", autopilotSubline(AutopilotSummary(3, 3, 1, 1, 18_400, 0, 0, 0)))
        assertEquals("카드 결제 2건 대기 중", autopilotSubline(AutopilotSummary(3, 3, 1, 1, 18_400, 0, 2, 0)))
        assertEquals("남은 확인 1건 · 카드 결제 2건 대기 중", autopilotSubline(AutopilotSummary(3, 2, 1, 1, 18_400, 0, 2, 1)))
    }
}
