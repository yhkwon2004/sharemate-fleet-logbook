package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class PhoneFleetUiTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val now = at(10, 9)
    private val fp = "a".repeat(64)
    private val link = "https://10.0.0.5:9247|${"1".repeat(64)}|$fp"
    private val paired = FleetVehicle(fp, "12가 3456", "아이오닉 5", "쉐어메이트", link, at(1, 9), now - 5 * 60_000)
    private val typed = FleetVehicle("manual", "34나 5678", "", "", null, at(2, 9), 0)
    private val kim = Driver(UUID.nameUUIDFromBytes("kim".toByteArray()).toString(), "영업", "김")
    private val empty = TripLogData(drivers = listOf(kim))

    @Test fun lastSeenReadsAsARelativeAge() {
        assertNull(fleetLastSeenText(0, now, zone))
        assertEquals("방금", fleetLastSeenText(now - 30_000, now, zone))
        assertEquals("시계가 앞서도 방금", "방금", fleetLastSeenText(now + 120_000, now, zone))
        assertEquals("1분 전", fleetLastSeenText(now - 60_000, now, zone))
        assertEquals("59분 전", fleetLastSeenText(now - 59 * 60_000, now, zone))
        assertEquals("1시간 전", fleetLastSeenText(now - 60 * 60_000, now, zone))
        assertEquals("23시간 전", fleetLastSeenText(now - (24 * 60 - 1) * 60_000L, now, zone))
        assertEquals("11월 8일", fleetLastSeenText(at(8, 10), now, zone))
    }

    @Test fun statusLineForATypedVehicleSaysItIsNotConnected() {
        assertEquals("아직 연결하지 않은 차량", fleetStatusLine(typed, selected = true, connected = false, selectedData = empty, now = now, zone = zone))
        assertEquals("아직 연결하지 않은 차량", fleetStatusLine(typed, selected = false, connected = false, selectedData = null, now = now, zone = zone))
    }

    @Test fun statusLineForTheSelectedConnectedCarIsItsAvailability() {
        assertEquals("사용 가능 · 예정된 예약 없음", fleetStatusLine(paired, true, true, empty, now, zone))
        val booked = empty.copy(reservations = listOf(Reservation("r1", kim.id, at(10, 14), at(10, 17))))
        assertEquals("사용 가능 · 다음 예약 오늘 14:00", fleetStatusLine(paired, true, true, booked, now, zone))
        val inUse = empty.copy(reservations = listOf(Reservation("r2", kim.id, at(10, 8), at(10, 12))))
        assertEquals("예약 사용 중 · 12:00까지", fleetStatusLine(paired, true, true, inUse, now, zone))
    }

    @Test fun statusLineWhileTheSelectedCarIsOutOfReachSaysSo() {
        assertEquals("연결 대기 · 사용 가능 · 예정된 예약 없음", fleetStatusLine(paired, true, false, empty, now, zone))
        assertEquals("연결 대기 · 아직 받은 기록 없음", fleetStatusLine(paired.copy(lastSeenAt = 0), true, false, empty, now, zone))
    }

    @Test fun statusLineForAnotherPairedCarShowsWhenItLastAnswered() {
        assertEquals("연결됨 · 마지막 확인 5분 전", fleetStatusLine(paired, false, false, null, now, zone))
        assertEquals("연결됨 · 아직 받은 기록 없음", fleetStatusLine(paired.copy(lastSeenAt = 0), false, false, null, now, zone))
        assertEquals("선택됐지만 사본이 없으면 연결 대기", "연결 대기 · 아직 받은 기록 없음", fleetStatusLine(paired, true, true, null, now, zone))
    }

    @Test fun linkToneFollowsHowTheVehicleIsRegistered() {
        assertEquals(FleetLinkTone.NONE, fleetLinkTone(null, true))
        assertEquals(FleetLinkTone.MANUAL, fleetLinkTone(typed, true))
        assertEquals(FleetLinkTone.LIVE, fleetLinkTone(paired, true))
        assertEquals(FleetLinkTone.WAITING, fleetLinkTone(paired, false))
    }

    @Test fun switcherDescriptionNamesTheVehicleAndItsState() {
        val fleet = FleetState(listOf(paired, typed), fp)
        assertEquals("차량 선택, 현재 12가 3456, 상태 실시간 연결 · 사용 가능 · 예정된 예약 없음", fleetSwitcherDescription(fleet, empty, true, now, zone))
        assertEquals("차량 선택, 현재 12가 3456, 상태 연결 대기", fleetSwitcherDescription(fleet, empty, false, now, zone))
        assertEquals("차량 선택, 현재 34나 5678, 상태 직접 등록 · 연결 전", fleetSwitcherDescription(fleet.copy(selectedId = "manual"), empty, true, now, zone))
        assertEquals("차량 선택, 현재 선택한 차량 없음, 상태 등록된 차량 없음", fleetSwitcherDescription(FleetState(), null, false, now, zone))
    }

    @Test fun subtitleJoinsModelAndCompany() {
        assertEquals("아이오닉 5 · 쉐어메이트", fleetVehicleSubtitle(paired))
        assertEquals("쉐어메이트", fleetVehicleSubtitle(paired.copy(model = " ")))
        assertEquals("차종·회사 미입력", fleetVehicleSubtitle(typed))
    }

    @Test fun removeTextWarnsAboutTheLinkOnlyForAPairedCar() {
        assertTrue(fleetRemoveText(paired).startsWith("이 차량과의 연결도 끊어요"))
        assertFalse(fleetRemoveText(typed).contains("연결"))
    }

    @Test fun manualFormChecksThePlateAndPlacesTheMessage() {
        assertNull(manualVehicleError("12가 3456", "아이오닉 5", "쉐어메이트"))
        assertNull(manualVehicleError(" 12가3456 ", "", ""))
        val blank = manualVehicleError("", "", "")!!
        assertTrue(fleetErrorIsPlate(blank))
        assertTrue(fleetErrorIsPlate(manualVehicleError("12가-3456", "", "")!!))
        val long = manualVehicleError("12가 3456", "가".repeat(FLEET_MODEL_MAX + 1), "")!!
        assertFalse(fleetErrorIsPlate(long))
        assertNull("입력 한도는 검증 한도와 같음", manualVehicleError("12가 3456", "가".repeat(FLEET_MODEL_MAX), "가".repeat(FLEET_COMPANY_MAX)))
        assertNull(manualVehicleError("가".repeat(FLEET_PLATE_MAX), "", ""))
        assertTrue(fleetErrorIsPlate("이미 등록된 차량번호예요."))
        assertFalse(fleetErrorIsPlate("차량은 30대까지 등록할 수 있어요."))
    }
}
