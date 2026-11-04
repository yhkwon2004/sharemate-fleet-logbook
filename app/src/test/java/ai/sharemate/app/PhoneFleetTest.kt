package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFleetTest {
    private val fp1 = "a".repeat(64)
    private val fp2 = "b".repeat(64)
    private val link1 = "https://10.0.0.5:9247|${"1".repeat(64)}|$fp1"
    private val link2 = "https://10.0.0.6:9247|${"2".repeat(64)}|$fp2"

    @Test fun pairingAddsTheCarSelectsItAndKeepsItsRegistrationDate() {
        val first = upsertPaired(FleetState(), fp1, link1, TripLogData(profile = FleetProfile(companyName = "쉐어메이트", plateNumber = "12가 3456")), 100)
        assertEquals(fp1, first.selectedId); assertEquals("12가 3456", first.selected?.plate); assertTrue(first.selected!!.paired)
        val second = upsertPaired(first, fp2, link2, null, 200)
        assertEquals(2, second.vehicles.size); assertEquals(fp2, second.selectedId)
        val again = upsertPaired(second, fp1, link1, null, 300)
        assertEquals("다시 연결해도 등록일 유지", 100, again.vehicles.first { it.id == fp1 }.addedAt)
        assertEquals("차량이 보낸 이름이 없으면 등록된 이름 유지", "12가 3456", again.vehicles.first { it.id == fp1 }.plate)
    }

    @Test fun theListRoundTripsAndDropsBrokenLinks() {
        val state = FleetState(listOf(FleetVehicle(fp1, "12가 3456", "아이오닉", "쉐어메이트", link1, 1, 2), FleetVehicle("manual", "34나 5678", "", "", null, 3, 0)), "manual")
        assertEquals(state, decodeFleet(encodeFleet(state)))
        val broken = decodeFleet(encodeFleet(state.copy(vehicles = state.vehicles.map { it.copy(link = if (it.id == fp1) "nonsense" else it.link) })))
        assertNull(broken.vehicles.first { it.id == fp1 }.link)
        assertEquals("선택된 차량이 없어지면 첫 차량", fp1, decodeFleet(encodeFleet(state.copy(selectedId = "gone"))).selectedId)
    }

    @Test fun namesFollowWhatTheCarReports() {
        val state = upsertPaired(FleetState(), fp1, link1, null, 1)
        val refreshed = refreshFromCopy(state, fp1, TripLogData(profile = FleetProfile(companyName = "쉐어메이트", plateNumber = "56다 7890", carModel = "아이오닉 5")), 50)
        assertEquals("56다 7890", refreshed.selected?.plate); assertEquals("아이오닉 5", refreshed.selected?.model); assertEquals(50L, refreshed.selected?.lastSeenAt)
    }

    @Test fun typedVehiclesAreValidated() {
        validateFleetVehicle("12가 3456", "아이오닉 5", "쉐어메이트")
        assertThrows(IllegalArgumentException::class.java) { validateFleetVehicle("", "", "") }
        assertThrows(IllegalArgumentException::class.java) { validateFleetVehicle("12가3456!!", "", "") }
        assertThrows(IllegalArgumentException::class.java) { validateFleetVehicle("12가 3456", "가".repeat(41), "") }
    }

    @Test fun theAccountRoundTrips() {
        val account = PhoneAccount("kim@company.com", "kim@company.com", "김서연", "https://example.com/p.png", AccountProvider.GOOGLE, 42, true, "d1", "영업1팀")
        assertEquals(account, decodeAccount(encodeAccount(account)))
        assertEquals(AccountProvider.DEMO, demoAccount(1).provider)
        val bare = decodeAccount(encodeAccount(account.copy(photoUrl = null, driverId = null)))
        assertNull(bare.photoUrl); assertNull(bare.driverId)
    }
}
