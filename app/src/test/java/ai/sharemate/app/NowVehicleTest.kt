package ai.sharemate.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The 지금 column's 차량 tile (2.15, docs/design/DESIGN_2_15.md §3): its height is what the page leaves, between its least
 * and its most, or null where even the least does not fit (the column then scrolls); its three figures follow the live
 * signal, and a figure the car does not report is "—".
 */
class NowVehicleTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = 1_790_000_000_000L

    /** A mini stat's value: titleSmall (19 sp, a syllable about 17.5 dp) in a third of the 352-dp tile less two 12-dp gaps, 109 dp. */
    private val statUnits = 6.0

    /** A mini stat's label: labelMedium (16 sp) after the 18-dp icon and its 6-dp gap, 85 dp. */
    private val labelUnits = 5.0

    @Test fun theTileTakesWhatThePageLeavesBetweenItsLeastAndItsMost() {
        val page = HOME_PAGE_FULL
        // The least that fits: 1,097 − 16 − 210 − 8 = 863 dp of tiles above.
        assertEquals("최소에서 딱 맞음", NOW_VEHICLE_MIN, nowVehicleHeight(page, 863.dp))
        assertNull("최소도 안 맞으면 null, 열은 스크롤", nowVehicleHeight(page, 864.dp))
        assertEquals("남은 만큼", 247.dp, nowVehicleHeight(page, 826.dp))
        assertEquals("최대까지", NOW_VEHICLE_MAX, nowVehicleHeight(page, 793.dp))
        assertEquals("남아도 최대에서 멈춤", NOW_VEHICLE_MAX, nowVehicleHeight(page, 600.dp))
        assertNull("페이지 높이를 모르면 최소로 스크롤", nowVehicleHeight(0.dp, 826.dp))
        assertNull("1,040 dp 페이지(낮은 창)의 가장 긴 상태", nowVehicleHeight(1040.dp, 826.dp))
    }

    @Test fun theLeastFollowsTheFontInEffectButNeverDropsUnder210() {
        val page = HOME_PAGE_FULL
        assertEquals("큰 글꼴의 최소 230도 247에 들어감", 247.dp, nowVehicleHeight(page, 826.dp, min = 230.dp))
        assertNull("최소 250은 247에 안 들어감", nowVehicleHeight(page, 826.dp, min = 250.dp))
        assertEquals("최소가 최대보다 크면 최소가 상한", 300.dp, nowVehicleHeight(page, 500.dp, min = 300.dp))
        assertNull("작은 글꼴의 최소도 210으로 셈", nowVehicleHeight(page, 864.dp, min = 180.dp))
        assertEquals(NOW_VEHICLE_MIN, nowVehicleHeight(page, 863.dp, min = 180.dp))
    }

    @Test fun theFullWindowHoldsTheColumnInItsTallestState() {
        // 사용자 with a two-line question (323), 예약 84, 오늘 운행 199, 이달 운행일지 172 and three 16-dp gaps.
        val tallest = 323.dp + 16.dp + 84.dp + 16.dp + 199.dp + 16.dp + 172.dp
        assertEquals(826.dp, tallest)
        val tile = nowVehicleHeight(HOME_PAGE_FULL, tallest)!!
        assertEquals("826 + 16 + 차량 + 8 = 1,097", HOME_PAGE_FULL, tallest + NOW_TILE_GAP + tile + NOW_COLUMN_FOOT)
        assertEquals("가장 긴 상태에도 최소보다 37 dp 큼", 37.dp, tile - NOW_VEHICLE_MIN)
    }

    @Test fun theBoundsAreTheTilesAnatomy() {
        // 20 + the title row 32 + 12 + the drawing + 16 + the mini stats (19 + 4 + 23) + 20.
        assertEquals(20.dp + 32.dp + 12.dp + NOW_VEHICLE_PICTURE_MIN + 16.dp + (19.dp + 4.dp + 23.dp) + 20.dp, NOW_VEHICLE_MIN)
        assertEquals("그림은 최소 64 dp", 64.dp, NOW_VEHICLE_PICTURE_MIN)
        // At its most the drawing is 134 dp tall, 322 wide at the bitmap's 1942 : 809: about the tile's 352-dp width.
        val picture = NOW_VEHICLE_MAX - (NOW_VEHICLE_MIN - NOW_VEHICLE_PICTURE_MIN)
        assertEquals(134.dp, picture)
        assertTrue(picture.value * 1942f / 809f <= 392f - 40f)
    }

    @Test fun theSignalSaysThePillsStateInOneShortWord() {
        assertEquals("수신 중", nowVehicleSignal(PillTone.READY))
        assertEquals("기록 중도 수신 중", "수신 중", nowVehicleSignal(PillTone.POSITIVE))
        assertEquals("시연 신호도 수신 중", "수신 중", nowVehicleSignal(PillTone.DEMO))
        assertEquals("연결 중", nowVehicleSignal(PillTone.QUIET))
        assertEquals("확인 필요", nowVehicleSignal(PillTone.NEGATIVE))
    }

    @Test fun theFiguresFollowTheLiveSignal() {
        val profile = FleetProfile(plateNumber = " 12가 3456 ")
        val ready = TripLogState(loaded = true, signal = SignalStatus.READY, data = TripLogData(profile = profile),
            live = DriveSample(now - 5_000, GearState.PARK, 51_300.0, socPercent = 100))
        val info = nowVehicleInfo(ready, now, zone)
        assertEquals(NowVehicleInfo("12가 3456", "100%", "P", "수신 중", "차량 12가 3456, 배터리 100%, 기어 P, 신호 수신 중"), info)

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

    @Test fun theSignalFollowsTheHeaderPill() {
        val sample = DriveSample(now - 5_000, GearState.PARK, 51_300.0, socPercent = 80)
        assertEquals("연결 중", nowVehicleInfo(TripLogState(loaded = true), now, zone).signal)
        assertEquals("확인 필요", nowVehicleInfo(TripLogState(loaded = true, signal = SignalStatus.DENIED, live = sample), now, zone).signal)
        assertEquals("저장 실패가 먼저", "확인 필요", nowVehicleInfo(TripLogState(loaded = true, signal = SignalStatus.READY, live = sample, error = "disk"), now, zone).signal)
        val open = Trip("o", now - 600_000, null, 100.0, 103.2)
        val recording = TripLogState(loaded = true, signal = SignalStatus.RECORDING, live = sample.copy(gear = GearState.MOVING), data = TripLogData(open = open))
        assertEquals("기록 중", "수신 중", nowVehicleInfo(recording, now, zone).signal)
        val demo = TripLogState(loaded = true, signal = SignalStatus.DEMO, live = sample.copy(demo = true), data = TripLogData(profile = FleetProfile(demoMode = true)))
        assertEquals("시연 신호", "수신 중", nowVehicleInfo(demo, now, zone).signal)
        assertEquals("80%", nowVehicleInfo(demo, now, zone).battery)
    }

    @Test fun withoutAPlateTheTileHasNoTagAndSaysNoNumber() {
        val info = nowVehicleInfo(TripLogState(), now, zone)
        assertNull(info.plate)
        assertNull("공백뿐인 번호도 없음", nowVehicleInfo(TripLogState(data = TripLogData(profile = FleetProfile(plateNumber = "  "))), now, zone).plate)
        assertEquals(NowVehicleInfo(null, "—", "—", "연결 중", "차량, 배터리 정보 없음, 기어 정보 없음, 신호 연결 중"), info)
    }

    @Test fun theTilesWordsAreOneShortLineEach() {
        with(VehicleCopy) {
            listOf(NOW_RECEIVING, NOW_CONNECTING, CHECK, NOW_NONE, "100%", "D · R · N").forEach { assertOneLine(it, statUnits) }
            listOf(NOW_BATTERY, GEAR, NOW_SIGNAL).forEach { assertOneLine(it, labelUnits) }
            listOf(NOW_TITLE, NOW_OPEN).forEach { assertOneLine(it, CARD_LINE_UNITS) }
            assertTrue(NOW_TITLE.count { it in '가'..'힣' } <= 8)
            listOf(DRAWING, SPOKEN_NOW_NONE).forEach { assertFalse(it, it.trimEnd().endsWith(".")) }
            // The drawing is described as staged, never as the car's model (the 차량 page's rule).
            assertTrue(DRAWING.startsWith("연출용"))
        }
        // TalkBack hears what the tile shows (WCAG 2.5.3): the title and every figure with its label.
        val info = nowVehicleInfo(TripLogState(loaded = true, signal = SignalStatus.READY, data = TripLogData(profile = FleetProfile(plateNumber = "12가 3456")),
            live = DriveSample(now - 5_000, GearState.PARK, 51_300.0, socPercent = 100)), now, zone)
        listOf("차량 12가 3456", "배터리 ${info.battery}", "기어 ${info.gear}", "신호 ${info.signal}").forEach { assertTrue(it, info.spoken.contains(it)) }
    }
}
