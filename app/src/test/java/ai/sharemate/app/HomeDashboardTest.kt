package ai.sharemate.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
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

class HomeDashboardTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(month: Int, day: Int, hour: Int) = LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val driver = Driver(id("d"), "영업", "권")
    private fun trip(seed: String, month: Int, day: Int, purpose: TripPurpose = TripPurpose.BUSINESS, driverId: String? = driver.id, km: Double = 10.0,
        start: Double = 100.0) =
        Trip(id(seed), at(month, day, 9), at(month, day, 10), start, start + km, driverId = driverId, purpose = purpose, updatedAt = at(month, day, 10))

    @Test fun greetingFollowsTheHourAndNamesTheEmployeeOnlyWhenKnown() {
        assertEquals("좋은 아침이에요, 권 님", homeGreeting(8, "권", false))
        assertEquals("오늘도 안전한 운행 되세요, 권 님", homeGreeting(14, "권", false))
        assertEquals("오늘도 수고 많으셨어요", homeGreeting(19, null, false))
        assertEquals("오늘도 안전한 운행 되세요", homeGreeting(12, " ", false))
        assertEquals("늦은 시간이에요 · 천천히 안전하게", homeGreeting(3, "권", false))
        assertEquals("기록 중이면 시간대와 무관", "운행을 기록하고 있어요", homeGreeting(8, "권", true))
    }

    @Test fun subtitleSaysWhatTheCarIsDoingInItsRealState() {
        assertEquals("출발하면 자동으로 기록하고, 주차하면 목적과 비용을 정리해 드려요.", homeSubtitle(SignalStatus.READY, false, null))
        assertEquals("계기판 누적거리를 읽으려면 차량 정보 권한이 필요해요.", homeSubtitle(SignalStatus.DENIED, false, null))
        assertTrue(homeSubtitle(SignalStatus.DEMO, true, null).startsWith("시연 주행은 15배속"))
        val open = Trip(id("o"), at(11, 4, 9), null, 100.0, 103.2)
        assertTrue(homeSubtitle(SignalStatus.RECORDING, false, open).contains("3.2 km"))
        assertEquals("차량 데이터를 받을 수 없어요. 연결 상태를 확인해 주세요.", homeSubtitle(SignalStatus.ERROR, false, null))
    }

    @Test fun aRecordingTripMakesTheHomeDistancesLiveTelemetry() {
        val open = Trip(id("o"), at(11, 4, 9), null, 100.0, 103.2)
        assertTrue("기록 중이면 거리는 그 자리에서 갱신", homeShowsLiveTrip(TripLogData(open = open)))
        assertFalse("기록 중인 운행이 없으면 숫자는 교차 페이드", homeShowsLiveTrip(TripLogData()))
        assertFalse("시연 모드에서는 실제 운행이 화면 숫자에 들어가지 않음", homeShowsLiveTrip(TripLogData(open = open, profile = FleetProfile(demoMode = true))))
        assertTrue(homeShowsLiveTrip(TripLogData(open = open.copy(demo = true), profile = FleetProfile(demoMode = true))))
    }

    @Test fun theSheetTabUnderlineSitsOnTheTabsBottomEdgeInsetAndNeverShrinksToADot() {
        val tab = Rect(100f, 0f, 200f, 56f)
        assertEquals("16 dp씩 안쪽, 바닥에 3 dp", Rect(116f, 53f, 184f, 56f), underlineBar(tab, thickness = 3f, inset = 16f))
        assertEquals("좁은 탭도 두께의 4배 폭은 유지", Rect(144f, 53f, 156f, 56f), underlineBar(Rect(130f, 0f, 170f, 56f), thickness = 3f, inset = 16f))
    }

    @Test fun cardLinesQuoteRealVehicleNumbersAndPutADueDeadlineFirst() {
        val today = LocalDate.of(2026, 11, 1)
        val plain = homeCardLines(TripLogData(drivers = listOf(driver)), 100, 12_394.0, true, today, zone)
        assertEquals("배터리 100% · 12,394.0 km", plain.vehicle)
        assertEquals("실시간이 아닌 값은 마지막으로 표시", "배터리 100% · 마지막 12,394.0 km",
            homeCardLines(TripLogData(), 100, 12_394.0, false, today, zone).vehicle)
        assertEquals("0 km 같은 읽기 값은 빼기", "배터리 100%", homeCardLines(TripLogData(), 100, 0.0, true, today, zone).vehicle)
        assertEquals("차량 상태 · 관리 기한", homeCardLines(TripLogData(), null, null, false, today, zone).vehicle)
        val care = VehicleCare(inspectionDue = "2026-11-20")
        assertEquals("정기검사 D-19 · 배터리 100%", homeCardLines(TripLogData(care = care), 100, 12_394.0, true, today, zone).vehicle)
        val key = careSnoozeKey(careDueAlerts(care, 12_394.0, today, emptySet()).single(), care)
        assertEquals("미뤄 둔 기한은 카드에서도 빠짐", "배터리 100% · 12,394.0 km",
            homeCardLines(TripLogData(care = care), 100, 12_394.0, true, today, zone, setOf(key)).vehicle)
    }

    @Test fun logCardCountsExactlyWhatTheReviewListShows() {
        val today = LocalDate.of(2026, 11, 20)
        assertEquals("11월 운행이 아직 없어요", homeCardLines(TripLogData(), null, null, false, today, zone).log)
        // One continuous odometer chain, so the only issues are the two the trips themselves carry.
        val data = TripLogData(drivers = listOf(driver), trips = listOf(trip("x", 10, 30, start = 90.0), trip("a", 11, 3), trip("b", 11, 5, TripPurpose.UNCLASSIFIED, start = 110.0),
            trip("c", 11, 6, driverId = null, start = 120.0)))
        val lines = homeCardLines(data, null, null, false, today, zone)
        assertEquals("11월 3건 · 확인 2건 남음", lines.log); assertEquals(2, lines.logPending)
        assertEquals(LogReview(3, 2), logReview(data, YearMonth.of(2026, 11), zone))
        val gap = data.copy(trips = data.trips.map { if (it.id == id("c")) it.copy(startOdometerKm = 150.0, endOdometerKm = 160.0, driverId = driver.id) else it })
        assertEquals("계기판 공백도 점검 필요 목록과 같이 셈", 2, homeCardLines(gap, null, null, false, today, zone).logPending)
        val done = homeCardLines(data.copy(trips = listOf(trip("a", 11, 3))), null, null, false, today, zone)
        assertEquals("11월 1건 · 모두 확인됨", done.log); assertEquals(0, done.logPending)
        val recording = TripLogData(open = Trip(id("o"), at(11, 20, 8), null, 100.0, 101.0))
        assertEquals("11월 첫 운행 기록 중", homeCardLines(recording, null, null, false, today, zone).log)
    }

    @Test fun exportCardNamesLastMonthWhileItIsStillOpen() {
        val data = TripLogData(drivers = listOf(driver), trips = listOf(trip("a", 10, 3), trip("b", 11, 2, start = 110.0)))
        assertEquals("10월 운행기록부 · 마감 전 저장", homeCardLines(data, null, null, false, LocalDate.of(2026, 11, 3), zone).export)
        val closed = closeMonth(data, YearMonth.of(2026, 10), at(11, 1, 9), zone)
        assertEquals("11월 운행기록부 · Excel 5개 시트", homeCardLines(closed, null, null, false, LocalDate.of(2026, 11, 3), zone).export)
    }

    @Test fun monthWidgetUsesOneTruthfulWord() {
        val today = LocalDate.of(2026, 11, 20)
        val empty = monthWidgetInfo(TripLogData(), today, zone)
        assertEquals("대기", empty.headline); assertEquals(0, empty.tone); assertNull(empty.auto)
        val open = TripLogData(drivers = listOf(driver), trips = listOf(trip("a", 11, 3, km = 30.0), trip("b", 11, 5, TripPurpose.PERSONAL, km = 10.0), trip("c", 11, 6, TripPurpose.UNCLASSIFIED, km = 10.0)))
        val info = monthWidgetInfo(open, today, zone)
        assertEquals("확인 1건", info.headline); assertEquals(2, info.tone)
        assertEquals("업무 60% · 50.0 km · 3건", info.detail)
        assertEquals("이달 운행일지 · 11월", info.title)
        assertTrue(info.auto!!.startsWith("자동 분류 0건 · 카드 반영 0건"))
        val complete = open.copy(trips = open.trips.dropLast(1))
        assertEquals("완성", monthWidgetInfo(complete, today, zone).headline)
        val closed = closeMonth(complete, YearMonth.of(2026, 11), at(11, 20, 9), zone)
        assertEquals("마감됨", monthWidgetInfo(closed, today, zone).headline)
        val reopened = closed.copy(trips = closed.trips + trip("d", 11, 25, TripPurpose.UNCLASSIFIED, start = 140.0))
        val after = monthWidgetInfo(reopened, LocalDate.of(2026, 11, 26), zone)
        assertEquals("마감 뒤 들어온 미확인 운행은 완료로 보이지 않음", "마감 후 확인 1건", after.headline); assertEquals(2, after.tone)
        assertNull("자동 완성을 끄면 자동 줄도 없음", monthWidgetInfo(complete.copy(profile = complete.profile.copy(autopilot = false)), today, zone).auto)
        val first = monthWidgetInfo(TripLogData(open = Trip(id("o"), at(11, 20, 8), null, 100.0, 101.0)), today, zone)
        assertEquals("첫 운행 기록 중이면 대기가 아님", "기록 중", first.headline)
    }

    @Test fun theMonthTilesLineIsTheDetailOrItsShortForm() {
        val today = LocalDate.of(2026, 11, 20)
        val first = monthWidgetInfo(TripLogData(open = Trip(id("o"), at(11, 20, 8), null, 100.0, 101.0)), today, zone)
        assertEquals("첫 운행 기록 중: 지금 열의 한 줄은 짧게", "이번 달 첫 운행", first.line)
        assertEquals("휴대폰과 홈 위젯은 그대로 문장", "이번 달 첫 운행을 기록하고 있어요", first.detail)
        val empty = monthWidgetInfo(TripLogData(), today, zone)
        assertEquals("이번 달 운행이 아직 없어요", empty.line)
        val open = TripLogData(drivers = listOf(driver), trips = listOf(trip("a", 11, 3, km = 30.0), trip("b", 11, 5, TripPurpose.PERSONAL, km = 10.0)))
        val info = monthWidgetInfo(open, today, zone)
        assertEquals("그 밖에는 detail 그대로", "업무 75% · 40.0 km · 2건", info.line)
        assertEquals(info.detail, info.line)
        val closed = monthWidgetInfo(closeMonth(open, YearMonth.of(2026, 11), at(11, 20, 9), zone), today, zone)
        assertEquals("마감된 달도 detail 그대로", closed.detail, closed.line)
        listOf(first, empty).forEach { assertOneLine(it.line, NOW_LINE_UNITS) }
    }

    @Test fun theTodayTileCountsFinishedTripsWhileItsDistanceKeepsTheOneRecording() {
        val now = at(11, 20, 12)
        assertEquals("아무것도 모를 때", TodayTileInfo("—", "0건", "0원", "확인 필요", "오늘 운행 정보 없음, 완료 0건, 등록 경비 0원, 계기판 확인 필요"),
            todayTileInfo(TripLogState(), now, zone))
        val done = trip("a", 11, 20, km = 12.4).copy(expenses = listOf(TripExpense(ExpenseType.PARKING, 4_500, at(11, 20, 10), EditSource.DRIVER)))
        val recording = Trip(id("o"), at(11, 20, 11), null, 200.0, 203.2)
        val yesterday = trip("y", 11, 19, km = 50.0, start = 50.0)
        val state = TripLogState(loaded = true, signal = SignalStatus.RECORDING,
            data = TripLogData(drivers = listOf(driver), trips = listOf(yesterday, done, recording), open = recording))
        val info = todayTileInfo(state, now, zone)
        assertEquals("기록 중인 운행도 거리에는 들어감", "15.6", info.km)
        assertEquals("완료 건수에는 기록 중인 운행이 빠짐", "1건", info.trips)
        assertEquals("4,500원", info.expenses)
        assertEquals("타일의 금액은 숫자로 (2.13)", 4_500L, info.expenseWon)
        assertEquals("차량 신호가 없으면 계기판은 확인 필요", "확인 필요", info.odometer)
        assertEquals("오늘 운행 15.6 km, 완료 1건, 등록 경비 4,500원, 계기판 확인 필요", info.spoken)
        val live = state.copy(live = DriveSample(now - 5_000, GearState.MOVING, 51_300.0))
        assertEquals("신호가 새로우면 계기판 값", "51,300.0 km", todayTileInfo(live, now, zone).odometer)
        assertEquals("45초가 지난 신호는 확인 필요", "확인 필요", todayTileInfo(live, now + 60_000, zone).odometer)
    }

    @Test fun homePlanFollowsTheWindowClassTheHeightAndTheFontScale() {
        val full = homePlan(carLayout(1680.dp, 1200.dp), 1f, 1200.dp)
        assertEquals(3, full.cardColumns)
        assertTrue(full.widgetsSideBySide && full.showEyebrow && !full.compactWidgets)
        assertEquals("히어로는 창의 70%, 420~600dp 사이", 600.dp, full.heroMin)
        // The class comes from width ÷ font scale, so the full window at 1.3× lands in EXPANDED: cards go 2 + 1.
        assertEquals(2, homePlan(carLayout(1292.dp, 1200.dp), 1.3f, 1200.dp).cardColumns)
        assertEquals("FULL은 큰 글꼴에서도 3열", 3, homePlan(carLayout(1680.dp, 1200.dp), 1.3f, 1200.dp).cardColumns)
        assertEquals("EXPANDED의 기본 글꼴은 3열", 3, homePlan(carLayout(1200.dp, 1200.dp), 1f, 1200.dp).cardColumns)
        val medium = homePlan(carLayout(900.dp, 1000.dp), 1f, 1000.dp)
        assertEquals("MEDIUM은 카드 한 줄씩", 1, medium.cardColumns)
        assertFalse("위젯은 세로로", medium.widgetsSideBySide)
        val short = homePlan(carLayout(1680.dp, 500.dp), 1f, 500.dp)
        assertTrue("낮은 창: 위젯 한 줄, 브랜드 줄 숨김", short.compactWidgets && short.widgetsSideBySide && !short.showEyebrow)
        assertEquals(300.dp, short.heroMin)
        // Today, this month and the next booking leave the page only for the 지금 column beside it.
        listOf(full, medium, short, homePlan(carLayout(1292.dp, 1200.dp), 1.3f, 1200.dp)).forEach { assertFalse("지금 열이 없으면 페이지에", it.widgetsInColumn) }
        val beside = homePlan(carLayout(1680.dp, 1200.dp), 1f, 1200.dp, nowColumn = true)
        assertTrue("지금 열이 있으면 그 열로", beside.widgetsInColumn)
        assertEquals("카드와 히어로는 그대로", full.copy(widgetsInColumn = true), beside)
    }

    @Test fun heroBandLeavesTheSkyRightOfTheGreetingAndAboveTheCards() {
        // FULL: a 1112-dp page, the hero 600 tall, the greeting's 720 measure inside 8 dp, the 184-dp card row inside 6, 24 clear.
        assertEquals(Rect(752f, 0f, 1080f, 386f), heroBand(1112f, 600f, 720f, 184f, 8f, 6f, 24f))
        assertEquals("히어로가 커지면 아래로만 자람", Rect(752f, 0f, 1080f, 486f), heroBand(1112f, 700f, 720f, 184f, 8f, 6f, 24f))
        assertNull("읽기 폭 옆에 남는 하늘이 없으면 없음", heroBand(760f, 600f, 720f, 184f, 8f, 6f, 24f))
        assertNull("카드 위에 남는 하늘이 없으면 없음", heroBand(1112f, 200f, 720f, 184f, 8f, 6f, 24f))
        assertNull("재기 전에는 없음", heroBand(0f, 0f, 720f, 0f, 8f, 6f, 24f))
    }

    @Test fun heroMinimumIsAFloorNotACeiling() {
        assertEquals(600.dp, heroMinHeight(1200.dp, short = false))
        assertEquals(420.dp, heroMinHeight(400.dp, short = false))
        assertEquals(490f, heroMinHeight(700.dp, short = false).value, .01f)
        assertEquals(300.dp, heroMinHeight(500.dp, short = true))
        assertEquals(420.dp, heroMinHeight(900.dp, short = true))
    }

    @Test fun theThirdCardKeepsOneColumnsWidthAtTwoPlusOne() {
        assertEquals("3열은 한 줄", listOf(listOf<Int?>(0, 1, 2)), homeCardRows(3, 3))
        // 2 + 1: the lone third card sits under the first with an empty cell beside it, never stretched across the row.
        assertEquals(listOf(listOf<Int?>(0, 1), listOf(2, null)), homeCardRows(3, 2))
        assertEquals("한 열은 카드마다 한 줄", listOf(listOf<Int?>(0), listOf(1), listOf(2)), homeCardRows(3, 1))
        assertEquals(listOf(listOf<Int?>(0, 1), listOf(2, 3)), homeCardRows(4, 2))
        assertTrue(homeCardRows(0, 3).isEmpty())
        assertEquals("열 수가 0이면 한 열로", listOf(listOf<Int?>(0), listOf(1)), homeCardRows(2, 0))
        // The plan the 1.3× full window gets (EXPANDED by width ÷ font scale) is the one that pads the last row.
        val large = homePlan(carLayout(1292.dp, 1200.dp), 1.3f, 1200.dp)
        assertEquals(listOf(2, 2), homeCardRows(3, large.cardColumns).map { it.size })
    }
}
