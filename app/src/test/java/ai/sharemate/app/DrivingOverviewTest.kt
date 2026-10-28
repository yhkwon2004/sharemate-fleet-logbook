package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class DrivingOverviewTest {
    private val now = Instant.parse("2026-10-28T08:00:00Z").toEpochMilli()
    private val zone = ZoneOffset.UTC
    private val open = Trip("open", now - 600_000, startOdometerKm = 120.0, endOdometerKm = 125.0)
    private fun state() = TripLogState(loaded = true, signal = SignalStatus.READY,
        live = DriveSample(now, GearState.MOVING, 125.0),
        data = TripLogData(trips = listOf(open.copy(id = "closed", startAt = now - 3_600_000, endAt = now - 1_800_000, endOdometerKm = 123.0),
            open.copy(id = "demo", endAt = now - 1, endOdometerKm = 999.0, demo = true)), open = open))

    @Test fun todayAddsCurrentTripAndExcludesTheOtherSourceMode() {
        val info = drivingOverviewInfo(state(), now, true, zone)
        assertEquals(8.0, info.todayKm!!, 0.0)
        assertEquals(5.0, info.currentKm!!, 0.0)
        assertEquals("운행 기록 중", info.status)
        assertTrue(info.fresh)
    }

    @Test fun unsupportedOrUnloadedDataDoesNotTurnIntoZeroOrSuccessfulRecording() {
        val info = drivingOverviewInfo(TripLogState(loaded = true, signal = SignalStatus.UNSUPPORTED), now, true, zone)
        assertNull(info.todayKm); assertNull(info.currentKm)
        assertEquals("차량 데이터 미지원", info.status)
        val unloaded = drivingOverviewInfo(state().copy(loaded = false), now, true, zone)
        assertNull(unloaded.todayKm); assertNull(unloaded.currentKm)
        assertFalse(unloaded.fresh)
    }

    @Test fun staleLiveValuesShowLastKnownDistanceAndRequireRefresh() {
        val info = drivingOverviewInfo(state(), now + 45_001, true, zone)
        assertEquals(5.0, info.currentKm!!, 0.0)
        assertFalse(info.fresh)
        assertEquals("거리 갱신 확인 필요", info.status)
        assertFalse(drivingOverviewInfo(state().copy(signal = SignalStatus.DENIED), now, true, zone).fresh)
    }

    @Test fun unavailableRestrictionsAndSaveFailureCannotClaimNormalRecording() {
        assertEquals("주행 상태 확인 중", drivingOverviewInfo(state(), now, false, zone).status)
        val failed = drivingOverviewInfo(state().copy(error = "저장 오류"), now, true, zone)
        assertEquals("기록 저장 확인 필요", failed.status)
        assertFalse(failed.fresh)
        assertNull(drivingOverviewInfo(state().copy(loadFailed = true), now, true, zone).todayKm)
    }

    @Test fun tripCrossingMidnightIsNotInventedAsTodaysDistance() {
        val midnight = Instant.parse("2026-10-29T00:01:00Z").toEpochMilli()
        val nextDay = state().copy(live = DriveSample(midnight, GearState.MOVING, 125.0))
        val info = drivingOverviewInfo(nextDay, midnight, true, zone)
        assertEquals(0.0, info.todayKm!!, 0.0)
        assertEquals(5.0, info.currentKm!!, 0.0)
    }
}
