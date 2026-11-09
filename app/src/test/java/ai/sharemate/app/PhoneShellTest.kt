package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.async
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** Pure parts of the phone shell, home, trip list and detail: routing, badges, in-flight edits, month paging, swipe targets, source lines. */
class PhoneShellTest {
    @Test fun failedOrPendingBookingKeepsTheDraftUntilTheCarAcceptsIt() = kotlinx.coroutines.runBlocking {
        val command = SyncCommand.CancelReservation("booking")
        var draft = "거래처 방문"
        var rejected = false
        try {
            sendAndConfirm(command, { throw IllegalArgumentException("예약 충돌") }) { draft = "" }
        } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals("거래처 방문", draft)
        val ack = kotlinx.coroutines.CompletableDeferred<Unit>()
        val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            sendAndConfirm(command, { ack.await() }) { draft = "" }
        }
        assertEquals("거래처 방문", draft)
        ack.complete(Unit)
        pending.await()
        assertEquals("", draft)
    }

    @Test fun firstBookingDoesNotRequireAPreviousTrip() {
        val empty = TripLogData()
        assertFalse(phoneHasWorkspace(empty, false))
        assertTrue(phoneHasWorkspace(empty, true))
        assertTrue(phoneHasWorkspace(empty.copy(profile = FleetProfile(plateNumber = "12가3456")), false))
    }

    @Test fun onlyTabPagesRunUnderTheFloatingBar() {
        assertTrue("탭 화면은 막대 아래까지 스크롤", phoneFrameUnderBar(PhoneRoute.Tab, hasLog = true))
        assertFalse("연결 화면은 막대가 없어 아래 여백을 지킴", phoneFrameUnderBar(PhoneRoute.Tab, hasLog = false))
        assertFalse("설정 같은 전체 화면은 아래 여백을 지킴", phoneFrameUnderBar(PhoneRoute.Settings, hasLog = true))
        assertFalse(phoneFrameUnderBar(PhoneRoute.TripDetail("t"), hasLog = true))
        assertFalse(phoneFrameUnderBar(PhoneRoute.Vehicle, hasLog = true))
    }

    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-23T05:02:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun trip(id: String, startAt: Long, purpose: TripPurpose = TripPurpose.UNCLASSIFIED, source: EditSource? = null, destination: String? = "강남 오피스",
        driverId: String? = "d1", demo: Boolean = false, open: Boolean = false, history: List<String> = emptyList(), odometer: Double = 100.0) =
        Trip(id, startAt, endAt = if (open) null else startAt + 40 * 60_000, startOdometerKm = odometer, endOdometerKm = odometer + 12.4, startAddress = "본사", destination = destination,
            driverId = driverId, purpose = purpose, purposeSource = source, demo = demo, history = history, updatedAt = startAt + 40 * 60_000)

    private val data = TripLogData(
        drivers = listOf(Driver("d1", "영업1팀", "김서연")),
        trips = listOf(
            trip("a", now - hour, odometer = 137.2), // unclassified, this month
            trip("b", now - 2 * day, TripPurpose.BUSINESS, EditSource.DRIVER, odometer = 124.8),
            trip("c", now - 40 * day, TripPurpose.BUSINESS, EditSource.DRIVER, odometer = 112.4), // September
            trip("d", now - 200 * day, TripPurpose.COMMUTE, EditSource.DRIVER, driverId = null), // April, no driver; the odometer chain is contiguous
            trip("demo", now - hour, demo = true),
        ),
        charges = listOf(CardCharge("ch1", now - hour, true, 4_500, "아이파킹 강남", null, CardSource.NOTIFICATION, null, ChargeState.REVIEW),
            CardCharge("ch2", now - day, true, 18_400, "차지비", null, CardSource.NOTIFICATION, null, ChargeState.MATCHED, tripId = "b")),
    )

    @Test fun routesRoundTripThroughTheSaver() {
        listOf(PhoneRoute.Tab, PhoneRoute.Settings, PhoneRoute.TripDetail("t-1"), PhoneRoute.Employee("d1")).forEach { assertEquals(it, decodeRoute(encodeRoute(it))) }
        assertNull(decodeRoute("elsewhere"))
        val ui = PhoneUiState(tab = 2).push(PhoneRoute.Settings).push(PhoneRoute.TripDetail("x"))
        assertEquals(PhoneRoute.TripDetail("x"), ui.route)
        assertEquals(PhoneRoute.Settings, ui.pop().route)
        assertEquals(PhoneRoute.Tab, ui.pop().pop().route)
        assertEquals(1, PhoneUiState().push(PhoneRoute.Settings).push(PhoneRoute.Settings).routes.size)
        val saved = PhoneUiStateSaver.run { object : androidx.compose.runtime.saveable.SaverScope { override fun canBeSaved(value: Any) = true }.save(ui.copy(employeeFilter = "", month = "2026-09",
            pendingEdits = mapOf("x" to PendingEdit(TripPurpose.BUSINESS, 5L, 9L)), dismissedBanners = setOf("offline"))) }!!
        val restored = PhoneUiStateSaver.restore(saved)!!
        assertEquals(2, restored.tab); assertEquals("", restored.employeeFilter); assertEquals("2026-09", restored.month)
        assertEquals(listOf(PhoneRoute.Settings, PhoneRoute.TripDetail("x")), restored.routes)
        assertEquals(PendingEdit(TripPurpose.BUSINESS, 5L, 9L), restored.pendingEdits["x"]); assertEquals(setOf("offline"), restored.dismissedBanners)
        assertNull(PhoneUiStateSaver.restore(PhoneUiStateSaver.run { object : androidx.compose.runtime.saveable.SaverScope { override fun canBeSaved(value: Any) = true }.save(PhoneUiState()) }!!)!!.employeeFilter)
    }

    @Test fun badgesCountOnlyRealItemsInTheCarsMode() {
        // home = 1 unclassified + 1 review; trips = 1 unclassified; expenses = 1 review; report = issues in October (a: unclassified) only.
        assertEquals(listOf(2, 0, 1, 1, 1), phoneBadges(data, YearMonth.of(2026, 10), zone))
        assertEquals(2, phoneHomeTasks(data))
        // April carries the trip without a driver; the demo trip never counts while the car is in real mode.
        assertEquals(1, phoneBadges(data, YearMonth.of(2026, 4), zone)[4])
        val demoMode = data.copy(profile = FleetProfile(demoMode = true))
        assertEquals(1, phoneBadges(demoMode, YearMonth.of(2026, 10), zone)[2])
        assertEquals(0, phoneBadges(demoMode, YearMonth.of(2026, 10), zone)[3])
        assertTrue(matchesEmployee(data.trips[3], "")); assertFalse(matchesEmployee(data.trips[0], "")); assertTrue(matchesEmployee(data.trips[0], null))
    }

    @Test fun pendingEditsSettleAgainstTheMirror() {
        val sent = now - 2_000
        val pending = mapOf("a" to PendingEdit(TripPurpose.BUSINESS, sent, 1), "b" to PendingEdit(TripPurpose.PERSONAL, sent, 1), "gone" to PendingEdit(TripPurpose.BUSINESS, sent, 1),
            "old" to PendingEdit(TripPurpose.BUSINESS, now - 60_000, 1))
        val mirror = data.copy(trips = data.trips.map { if (it.id == "b") it.copy(purpose = TripPurpose.COMMUTE, updatedAt = now - 1_000) else it } + trip("old", now - 3 * hour))
        val (kept, conflicts) = reconcilePendingEdits(pending, mirror, now)
        assertEquals(setOf("a"), kept.keys) // still unclassified in the mirror, recent: keep showing the sent value
        assertEquals(listOf("b"), conflicts) // the car wrote something else after we sent
        // A settled edit disappears without a conflict.
        val settled = data.copy(trips = data.trips.map { if (it.id == "a") it.copy(purpose = TripPurpose.BUSINESS) else it })
        assertTrue(reconcilePendingEdits(mapOf("a" to PendingEdit(TripPurpose.BUSINESS, sent, 1)), settled, now).let { it.first.isEmpty() && it.second.isEmpty() })
    }

    @Test fun lastSeenReadsAsAnHonestAge() {
        assertNull(lastSeenText(0, now, zone))
        assertEquals("방금", lastSeenText(now - 20_000, now, zone))
        assertEquals("3분 전", lastSeenText(now - 3 * 60_000, now, zone))
        assertEquals("02:02", lastSeenText(now - 3 * hour, now, zone))
    }

    @Test fun monthsPageFromTheCurrentMonthBackToTheFirstTripOnly() {
        val groups = tripMonthGroups(phoneTrips(data), YearMonth.of(2026, 10), zone)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9), YearMonth.of(2026, 4)), groups.map { it.month })
        assertEquals(listOf("a", "b"), groups[0].trips.map { it.id }) // newest first, demo excluded
        assertTrue(monthHeaderText(groups[0]).startsWith("2026년 10월 · 2건 · "))
        assertTrue(monthHeaderText(groups[0]).endsWith("업무 50%"))
        // An empty current month still heads the list so the sticky header has something to say.
        val empty = tripMonthGroups(emptyList(), YearMonth.of(2026, 11), zone)
        assertEquals(1, empty.size); assertEquals("2026년 11월 · 운행 없음", monthHeaderText(empty[0]))
        assertTrue(phoneEditedAfterClose(trip("x", now, history = listOf("11/1 09:00 마감된 달의 기록 수정 · 목적 변경"))))
        assertFalse(phoneEditedAfterClose(trip("x", now)))
    }

    @Test fun swipeTargetsShowTheValueTheyWillApply() {
        val (right, left) = swipeTargets(data.trips[0], phoneTrips(data))
        assertEquals(TripPurpose.BUSINESS, right!!.first); assertEquals("이전 기록과 같게", right.second) // b went to the same place as 일반 업무
        assertEquals(TripPurpose.PERSONAL, left)
        // A trip already personal cannot swipe to personal; right offers 일반 업무.
        val personal = swipeTargets(trip("p", now, TripPurpose.PERSONAL, destination = "집"), emptyList())
        assertEquals(TripPurpose.BUSINESS, personal.first!!.first); assertEquals("", personal.first!!.second); assertNull(personal.second)
        // A business trip with no suggestion flips to 출·퇴근 on the right.
        assertEquals(TripPurpose.COMMUTE, swipeTargets(trip("q", now, TripPurpose.BUSINESS, destination = "아무데"), emptyList()).first!!.first)
    }

    @Test fun homeStackSkipsTripsAlreadyAnsweredInFlight() {
        assertEquals(listOf("a"), homeUnclassified(data, emptyMap()).map { it.id })
        assertTrue(homeUnclassified(data, mapOf("a" to PendingEdit(TripPurpose.BUSINESS, now, 1))).isEmpty())
        assertEquals(listOf("a"), homeUnclassified(data, mapOf("a" to PendingEdit(TripPurpose.UNCLASSIFIED, now, 1))).map { it.id })
    }

    @Test fun settingsPromiseAutomaticRecordingOnlyWhileTheCarListens() {
        assertEquals("다음 운행도 자동 기록해요", autoRecordText(SignalStatus.READY))
        assertEquals("다음 운행도 자동 기록해요", autoRecordText(SignalStatus.RECORDING))
        assertEquals("차량 신호를 받으면 자동 기록해요", autoRecordText(SignalStatus.DENIED))
        assertEquals("차량 신호를 받으면 자동 기록해요", autoRecordText(null))
    }

    @Test fun purposeSourceLineNeverHidesAnAutomaticValue() {
        val history = listOf(trip("h1", now - 5 * day, TripPurpose.BUSINESS, EditSource.DRIVER), trip("h2", now - 6 * day, TripPurpose.BUSINESS, EditSource.PHONE),
            trip("h3", now - 7 * day, TripPurpose.BUSINESS, EditSource.AUTO), trip("h4", now - 8 * day, TripPurpose.BUSINESS, EditSource.DRIVER, destination = "다른 곳"),
            trip("later", now + day, TripPurpose.BUSINESS, EditSource.DRIVER))
        val auto = trip("t", now, TripPurpose.BUSINESS, EditSource.AUTO)
        assertEquals(2, phoneSameDestinationCount(auto, history + auto, TripPurpose.BUSINESS)) // h3 (automatic) and the later trip do not count
        assertEquals("목적: 일반 업무 · 자동 분류 — 같은 도착지 2회 기록을 근거로 차량이 채웠어요", purposeSourceLine(auto, history + auto))
        assertEquals("목적: 미분류 · 아직 아무도 정하지 않았어요", purposeSourceLine(trip("u", now), history))
        assertEquals("목적: 개인 · 휴대폰에서 직접 선택", purposeSourceLine(trip("p", now, TripPurpose.PERSONAL, EditSource.PHONE), history))
        assertEquals("목적: 출·퇴근 · 카드 자동 매칭 — 결제 기록을 근거로 채웠어요", purposeSourceLine(trip("c", now, TripPurpose.COMMUTE, EditSource.CARD), history))
        assertEquals("목적: 출·퇴근 · 출처 기록 없음", purposeSourceLine(trip("n", now, TripPurpose.COMMUTE, null), history))
    }

    @Test fun expenseDeleteOnlyFiresWhenTheRowStillMatchesTheMirror() {
        val toll = TripExpense(ExpenseType.TOLL, 3_600, now, EditSource.PHONE)
        val parking = TripExpense(ExpenseType.PARKING, 4_500, now + 1, EditSource.CARD, cardChargeId = "ch2")
        val withBoth = trip("e", now).copy(expenses = listOf(toll, parking))
        assertTrue(expenseStillAt(withBoth, 1, parking))
        assertFalse(expenseStillAt(withBoth.copy(expenses = listOf(parking)), 1, parking)) // the list shrank under the finger
        assertFalse(expenseStillAt(withBoth.copy(expenses = listOf(parking, toll)), 1, parking)) // reordered
        assertFalse(expenseStillAt(null, 0, toll))
    }
}
