package ai.sharemate.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The 지금 column's 차량 tile (2.15, docs/design/DESIGN_2_15.md §3 and §9.4): its height is what the page leaves, between its
 * least and its most, or null where even the least does not fit (the column then scrolls); in the FULL window it ends the column
 * on the page's foot in every state of the 사용자 card, and the room beyond the drawing holds a second row of figures. Its
 * figures follow the live signal, the deadlines and the phone link, and a figure the car does not report is "—".
 */
class NowVehicleTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = 1_790_000_000_000L
    private val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    /** A mini stat's value: titleSmall (19 sp, a syllable about 17.5 dp) in a third of the 352-dp tile less two 12-dp gaps, 109 dp. */
    private val statUnits = 6.0

    /** A mini stat's label: labelMedium (16 sp) after the 18-dp icon and its 6-dp gap, 85 dp. */
    private val labelUnits = 5.0

    /** The tile's parts other than the drawing: 20 + 32 + 12 + 16 + 46 + 20 at 1×. */
    private val chrome = NOW_VEHICLE_MIN - NOW_VEHICLE_PICTURE_MIN

    /** The drawing's own height in the 352-dp tile (the 392-dp column less 20 a side) at the bitmap's 1942 : 809. */
    private val drawing = nowVehicleTileWidth * NOW_VEHICLE_ASPECT

    private val tiles = 16.dp + 84.dp + 16.dp + 199.dp + 16.dp + 172.dp

    @Test fun theTileTakesWhatThePageLeavesBetweenItsLeastAndItsMost() {
        val page = HOME_PAGE_FULL
        // The least that fits: 1,097 − 16 − 210 − 8 = 863 dp of tiles above.
        assertEquals("최소에서 딱 맞음", NOW_VEHICLE_MIN, nowVehicleHeight(page, 863.dp))
        assertNull("최소도 안 맞으면 null, 열은 스크롤", nowVehicleHeight(page, 864.dp))
        assertEquals("남은 만큼", 247.dp, nowVehicleHeight(page, 826.dp))
        assertEquals("최대까지", NOW_VEHICLE_MAX, nowVehicleHeight(page, 673.dp))
        assertEquals("남아도 최대에서 멈춤", NOW_VEHICLE_MAX, nowVehicleHeight(page, 600.dp))
        assertNull("페이지 높이를 모르면 최소로 스크롤", nowVehicleHeight(0.dp, 826.dp))
        assertNull("1,040 dp 페이지(낮은 창)의 가장 긴 상태", nowVehicleHeight(1040.dp, 826.dp))
        assertEquals("§9.4: 최대 400 dp", 400.dp, NOW_VEHICLE_MAX)
    }

    @Test fun theLeastFollowsTheFontInEffectButNeverDropsUnder210() {
        val page = HOME_PAGE_FULL
        assertEquals("큰 글꼴의 최소 230도 247에 들어감", 247.dp, nowVehicleHeight(page, 826.dp, min = 230.dp))
        assertNull("최소 250은 247에 안 들어감", nowVehicleHeight(page, 826.dp, min = 250.dp))
        assertEquals("최소가 최대보다 크면 최소가 상한", 450.dp, nowVehicleHeight(page, 500.dp, min = 450.dp))
        assertNull("작은 글꼴의 최소도 210으로 셈", nowVehicleHeight(page, 864.dp, min = 180.dp))
        assertEquals(NOW_VEHICLE_MIN, nowVehicleHeight(page, 863.dp, min = 180.dp))
    }

    /**
     * §9.4: the column ends on the page's foot in each state of the 사용자 card (DriverUi.kt's DriverNowCard at 1×, single
     * lines at their trimmed heights): 171 with the person (20 + 32 + 12 + the 56-dp avatar row + 12 + the 19-dp last drive +
     * 20); 323 with today's question on two lines (16 + 1 + 16 + 23 + 28 + 12 + 56 more); 212 on a first run (the 56-dp prompt
     * row, 16, the 56-dp 직원 추가). The tile takes 247, 399 and 358 dp, all between its least and its most.
     */
    @Test fun theFullWindowEndsTheColumnOnThePagesFootInEveryDriverState() {
        val answered = 20.dp + 32.dp + 12.dp + 56.dp + 12.dp + 19.dp + 20.dp
        assertEquals(171.dp, answered)
        assertEquals(323.dp, answered + 16.dp + 1.dp + 16.dp + 23.dp + 28.dp + 12.dp + 56.dp)
        assertEquals(212.dp, 20.dp + 32.dp + 12.dp + 56.dp + 16.dp + 56.dp + 20.dp)
        val expected = mapOf(323.dp to 247.dp, 295.dp to 275.dp, 171.dp to 399.dp, 212.dp to 358.dp)
        expected.forEach { (driver, tile) ->
            val above = driver + tiles
            val height = nowVehicleHeight(HOME_PAGE_FULL, above)
            assertEquals("사용자 $driver", tile, height)
            assertEquals("사용자 $driver: 페이지 끝에서 끝남", HOME_PAGE_FULL, above + NOW_TILE_GAP + height!! + NOW_COLUMN_FOOT)
            assertTrue(height in NOW_VEHICLE_MIN..NOW_VEHICLE_MAX)
        }
        assertEquals("가장 긴 상태에도 최소보다 37 dp 큼", 37.dp, nowVehicleHeight(HOME_PAGE_FULL, 323.dp + tiles)!! - NOW_VEHICLE_MIN)
    }

    @Test fun theBoundsAreTheTilesAnatomy() {
        // 20 + the title row 32 + 12 + the drawing + 16 + the mini stats (19 + 4 + 23) + 20.
        assertEquals(20.dp + 32.dp + 12.dp + NOW_VEHICLE_PICTURE_MIN + 16.dp + (19.dp + 4.dp + 23.dp) + 20.dp, NOW_VEHICLE_MIN)
        assertEquals("그림은 최소 64 dp", 64.dp, NOW_VEHICLE_PICTURE_MIN)
        assertEquals("둘째 줄은 간격 16 + 미니 통계 46", 16.dp + 19.dp + 4.dp + 23.dp, NOW_VEHICLE_MORE)
        // The drawing fills the tile's width at 147 dp, the most it can use: what the slot holds beyond it is air or the second row.
        assertEquals(146.6f, drawing.value, .1f)
    }

    @Test fun theSecondRowStandsWhereTheSlotHasItsRoomBeyondTheDrawing() {
        // The slot is the tile less its other parts; the row needs 62 dp beyond the drawing's own height.
        assertTrue("딱 62 dp 남으면 둘째 줄", nowVehicleMoreRow(209.dp, 147.dp))
        assertFalse("하나 모자라면 없음", nowVehicleMoreRow(208.dp, 147.dp))
        assertFalse("큰 글꼴의 더 긴 줄은 더 필요함", nowVehicleMoreRow(209.dp, 147.dp, need = 70.dp))
        // The FULL window at 1×: asked (247), asked on one line (275), answered (399), first run (358).
        val shown = mapOf(247.dp to false, 275.dp to false, 399.dp to true, 358.dp to true).mapValues { (tile, _) -> nowVehicleMoreRow(tile - chrome, drawing) }
        assertEquals(mapOf(247.dp to false, 275.dp to false, 399.dp to true, 358.dp to true), shown)
        // With the row the drawing keeps its own height: 358 leaves it 150 dp, 399 leaves 191.
        listOf(358.dp, 399.dp, NOW_VEHICLE_MAX).forEach { tile -> assertTrue("$tile", tile - chrome - NOW_VEHICLE_MORE >= drawing) }
    }

    @Test fun theNextDeadlineIsADayCount() {
        fun care(inspection: Long? = null, insurance: Long? = null, lease: Long? = null) = nowVehicleCare(careAlerts(VehicleCare(
            inspectionDue = inspection?.let { today.plusDays(it).toString() }, insuranceDue = insurance?.let { today.plusDays(it).toString() },
            leaseEnd = lease?.let { today.plusDays(it).toString() }), null, today))
        assertEquals(NowFigure("D-7", "관리 정기검사까지 7일"), care(inspection = 7))
        assertEquals(NowFigure("오늘", "관리 보험 만기 오늘"), care(insurance = 0))
        assertEquals(NowFigure("3일 지남", "관리 리스·렌트 만기 3일 지남"), care(lease = -3))
        assertEquals("먼 기한도 다음 기한", "D-120", care(inspection = 120).value)
        assertEquals("지난 기한이 먼저", "2일 지남", care(inspection = 5, insurance = -2).value)
        assertEquals("가까운 기한이 먼저", "D-5", care(inspection = 40, insurance = 5).value)
        val none = care()
        assertEquals(NowFigure("—", "관리 등록된 기한 없음"), none)
        assertTrue(none.muted)
        // The service interval counts kilometres: 차량 관리 keeps it, the tile's D-day does not.
        val service = nowVehicleCare(careAlerts(VehicleCare(serviceIntervalKm = 10_000, lastServiceKm = 40_000.0), 50_500.0, today))
        assertEquals("—", service.value)
    }

    @Test fun theChargeAndThePhoneSayTheirState() {
        assertEquals(NowFigure("충전 중", "충전 중"), nowVehicleCharge(true))
        assertEquals(NowFigure("—", "충전 중 아님"), nowVehicleCharge(false))
        assertEquals(NowFigure("—", "충전 정보 없음"), nowVehicleCharge(null))
        assertFalse(nowVehicleCharge(true).muted)
        assertTrue(nowVehicleCharge(false).muted)

        assertEquals(NowFigure("꺼짐", "휴대폰 꺼짐", muted = true), nowVehiclePhone(enabled = false, running = false, error = null, paired = 2))
        assertEquals(NowFigure("연결 전", "휴대폰 연결 전"), nowVehiclePhone(enabled = true, running = true, error = null, paired = 0))
        assertEquals("서버가 뜨는 중에는 준비 중", "준비 중", nowVehiclePhone(enabled = true, running = false, error = null, paired = 1).value)
        // One state, one word on the car's two places: the 차량 page's 휴대폰 연결 tag says the same before a phone pairs and while starting.
        assertEquals(phoneLinkTag(true, true, null, false, 0).first, nowVehiclePhone(true, true, null, 0).value)
        assertEquals(phoneLinkTag(true, false, null, false, 0).first, nowVehiclePhone(true, false, null, 0).value)
        assertEquals(NowFigure("연결 2대", "휴대폰 연결 2대"), nowVehiclePhone(enabled = true, running = true, error = null, paired = 2))
        assertEquals("차량 페이지의 태그 그대로", phoneLinkTag(true, false, "port", false, 0).first, nowVehiclePhone(true, false, "port", 0).value)
        assertFalse(nowVehiclePhone(true, true, null, 1).muted)
    }

    @Test fun theSignalSaysThePillsStateInOneShortWord() {
        assertEquals("수신 중", nowVehicleSignal(PillTone.READY))
        assertEquals("기록 중도 수신 중", "수신 중", nowVehicleSignal(PillTone.POSITIVE))
        assertEquals("시연 모드의 신호는 차량 페이지의 말 그대로", "시연 신호", nowVehicleSignal(PillTone.DEMO))
        assertEquals("시연 주행을 기록하는 중에도", "시연 신호", nowVehicleSignal(PillTone.POSITIVE, demo = true))
        assertEquals("시연 신호", nowVehicleSignal(PillTone.READY, demo = true))
        assertEquals("저장 실패는 시연에서도 확인 필요", "확인 필요", nowVehicleSignal(PillTone.NEGATIVE, demo = true))
        assertEquals(vehicleHeroCopy(demo = true, fresh = true, signal = SignalStatus.READY, gear = null).tag, nowVehicleSignal(PillTone.DEMO))
        assertEquals("연결 중", nowVehicleSignal(PillTone.QUIET))
        assertEquals("확인 필요", nowVehicleSignal(PillTone.NEGATIVE))
    }

    @Test fun theFiguresFollowTheLiveSignal() {
        val profile = FleetProfile(plateNumber = " 12가 3456 ")
        val ready = TripLogState(loaded = true, signal = SignalStatus.READY, data = TripLogData(profile = profile),
            live = DriveSample(now - 5_000, GearState.PARK, 51_300.0, socPercent = 100))
        val info = nowVehicleInfo(ready, now, zone)
        assertEquals(NowVehicleInfo("12가 3456", "100%", "P", "수신 중", "차량 12가 3456, 배터리 100%, 기어 P, 신호 수신 중",
            NowFigure("—", "관리 등록된 기한 없음"), NowFigure("—", "충전 정보 없음"), NowFigure("꺼짐", "휴대폰 꺼짐", muted = true)), info)

        // 45 seconds on, the sample is stale: no charge, no gear, and the pill's 마지막 수신 reads as 확인 필요.
        val stale = nowVehicleInfo(ready, now + 60_000, zone)
        assertEquals("—", stale.battery)
        assertEquals("—", stale.gear)
        assertEquals("확인 필요", stale.signal)
        assertEquals("차량 12가 3456, 배터리 정보 없음, 기어 정보 없음, 신호 확인 필요", stale.spoken)

        val moving = nowVehicleInfo(ready.copy(live = ready.live!!.copy(gear = GearState.MOVING)), now, zone)
        assertEquals("차량 페이지의 기어 그대로", "D · R · N", moving.gear)
        assertEquals("알 수 없는 기어는 —", "—", nowVehicleInfo(ready.copy(live = ready.live!!.copy(gear = GearState.UNKNOWN)), now, zone).gear)
        assertEquals("잔량이 없으면 —", "—", nowVehicleInfo(ready.copy(live = ready.live!!.copy(socPercent = null)), now, zone).battery)
        assertEquals("말이 안 되는 잔량은 —", "—", nowVehicleInfo(ready.copy(live = ready.live!!.copy(socPercent = 140)), now, zone).battery)
        val demo = ready.copy(data = TripLogData(profile = profile.copy(demoMode = true)))
        assertEquals("시연 모드에서 실제 신호의 잔량은 보이지 않음", "—", nowVehicleInfo(demo, now, zone).battery)
    }

    @Test fun theSecondRowReadsTheDeadlinesThePlugAndThePhones() {
        val care = VehicleCare(inspectionDue = today.plusDays(7).toString())
        val charging = TripLogState(loaded = true, signal = SignalStatus.READY,
            data = TripLogData(profile = FleetProfile(phoneSync = true), care = care),
            live = DriveSample(now - 5_000, GearState.PARK, 51_300.0, charging = true, socPercent = 64),
            sync = SyncInfo(running = true, pairedPhones = 1))
        val info = nowVehicleInfo(charging, now, zone)
        assertEquals("D-7", info.care.value)
        assertEquals("충전 중", info.charge.value)
        assertEquals("연결 1대", info.phone.value)
        assertEquals("차량, 배터리 64%, 기어 P, 신호 수신 중, 관리 정기검사까지 7일, 충전 중, 휴대폰 연결 1대", info.spokenMore)
        // A stale sample says nothing about the plug; a demo sample counts only in demo mode, as the battery does.
        assertEquals("충전 정보 없음", nowVehicleInfo(charging, now + 60_000, zone).charge.spoken)
        val demoSample = charging.copy(live = charging.live!!.copy(demo = true))
        assertEquals("—", nowVehicleInfo(demoSample, now, zone).charge.value)
        assertEquals("연결 전", nowVehicleInfo(charging.copy(sync = SyncInfo(running = true)), now, zone).phone.value)
    }

    @Test fun theSignalFollowsTheHeaderPill() {
        val sample = DriveSample(now - 5_000, GearState.PARK, 51_300.0, socPercent = 80)
        assertEquals("연결 중", nowVehicleInfo(TripLogState(loaded = true), now, zone).signal)
        assertEquals("확인 필요", nowVehicleInfo(TripLogState(loaded = true, signal = SignalStatus.DENIED, live = sample), now, zone).signal)
        assertEquals("저장 실패가 먼저", "확인 필요", nowVehicleInfo(TripLogState(loaded = true, signal = SignalStatus.READY, live = sample, error = "disk"), now, zone).signal)
        val open = Trip("o", now - 600_000, null, 100.0, 103.2)
        val recording = TripLogState(loaded = true, signal = SignalStatus.RECORDING, live = sample.copy(gear = GearState.MOVING), data = TripLogData(open = open))
        assertEquals("기록 중", "수신 중", nowVehicleInfo(recording, now, zone).signal)
        val demo = TripLogState(loaded = true, signal = SignalStatus.DEMO, live = sample.copy(demo = true), data = TripLogData(profile = FleetProfile(demoMode = true)))
        assertEquals("시연 모드는 차량 페이지의 말로", "시연 신호", nowVehicleInfo(demo, now, zone).signal)
        assertEquals("80%", nowVehicleInfo(demo, now, zone).battery)
    }

    @Test fun withoutAPlateTheTileHasNoTagAndSaysNoNumber() {
        val info = nowVehicleInfo(TripLogState(), now, zone)
        assertNull(info.plate)
        assertNull("공백뿐인 번호도 없음", nowVehicleInfo(TripLogState(data = TripLogData(profile = FleetProfile(plateNumber = "  "))), now, zone).plate)
        assertEquals("—", info.battery)
        assertEquals("—", info.gear)
        assertEquals("연결 중", info.signal)
        assertEquals("차량, 배터리 정보 없음, 기어 정보 없음, 신호 연결 중", info.spoken)
    }

    @Test fun theTilesWordsAreOneShortLineEach() {
        with(VehicleCopy) {
            val values = listOf(NOW_RECEIVING, NOW_CONNECTING, CHECK, NOW_NONE, "100%", "D · R · N", NOW_CHARGING, NOW_PHONE_OFF, NOW_PHONE_WAITING, NOW_PHONE_UNPAIRED, NOW_PHONE_STARTING, NOW_DEMO,
                NOW_PHONE_ERROR, nowPhoneLinked(9), nowCareDays(7), nowCareDays(365), nowCareDays(0), nowCareDays(-365))
            values.forEach { assertOneLine(it, statUnits) }
            listOf(NOW_BATTERY, GEAR, NOW_SIGNAL, NOW_CARE, NOW_CHARGE, NOW_PHONE).forEach { assertOneLine(it, labelUnits) }
            listOf(NOW_TITLE, NOW_OPEN).forEach { assertOneLine(it, CARD_LINE_UNITS) }
            assertTrue(NOW_TITLE.count { it in '가'..'힣' } <= 8)
            listOf(DRAWING, SPOKEN_NOW_NONE, SPOKEN_NOW_NO_CARE, SPOKEN_NOT_CHARGING, spokenNowCare("리스·렌트 만기", -3)).forEach {
                assertFalse(it, it.trimEnd().endsWith("."))
            }
            // The drawing is described as staged, never as the car's model (the 차량 page's rule).
            assertTrue(DRAWING.startsWith("연출용"))
        }
        // TalkBack hears what the tile shows (WCAG 2.5.3): the title and every figure with its label, both rows.
        val info = nowVehicleInfo(TripLogState(loaded = true, signal = SignalStatus.READY, data = TripLogData(profile = FleetProfile(plateNumber = "12가 3456",
            phoneSync = true)), live = DriveSample(now - 5_000, GearState.PARK, 51_300.0, charging = true, socPercent = 100), sync = SyncInfo(running = true,
            pairedPhones = 2)), now, zone)
        listOf("차량 12가 3456", "배터리 ${info.battery}", "기어 ${info.gear}", "신호 ${info.signal}", info.charge.value, "휴대폰 ${info.phone.value}")
            .forEach { assertTrue(it, info.spokenMore.contains(it)) }
        assertTrue("둘째 줄은 첫 줄 뒤에", info.spokenMore.startsWith(info.spoken))
    }

    private companion object {
        /** The 392-dp column's tile less its 20-dp padding a side. */
        val nowVehicleTileWidth = 392.dp - 40.dp
    }
}
