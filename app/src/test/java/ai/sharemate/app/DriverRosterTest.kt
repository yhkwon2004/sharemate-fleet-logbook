package ai.sharemate.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Driver management's pure rules (2.11): the avatar, the copy, the stable list with what is next, deletes and undo, and the booking switch. */
class DriverRosterTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 11, year: Int = 2026) =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val now = at(9, 19, 6)
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val kimJiwoo = Driver(id("kj"), "Sales", "Kim Jiwoo")
    private val leeMinho = Driver(id("lm"), "Finance", "Lee Minho")
    private val kim = Driver(id("kim"), "영업1팀", "김서연")
    private val park = Driver(id("park"), "운영팀", "박지훈")
    private val lee = Driver(id("lee"), "재무팀", "이준")
    private val choi = Driver(id("choi"), "총무팀", "최유진")
    private fun trip(seed: String, driver: Driver?, start: Long, end: Long?, demo: Boolean = false) =
        Trip(id(seed), start, end, 100.0, 112.4, driverId = driver?.id, demo = demo, updatedAt = end ?: start)
    private fun booking(seed: String, driver: Driver, from: Long, to: Long, demo: Boolean = false) = Reservation(id(seed), driver.id, from, to, demo = demo)

    @Test fun monogramsUseTheGivenNameOrInitials() {
        assertEquals("서연", driverMonogram("김서연"))
        assertEquals("이준", driverMonogram("이준"))
        assertEquals("두 글자 성도 이름만", "민수", driverMonogram("남궁민수"))
        assertEquals("띄어 쓴 한글 이름", "서연", driverMonogram("김 서연"))
        assertEquals("KJ", driverMonogram("Kim Jiwoo"))
        assertEquals("J", driverMonogram("jiwoo"))
        assertEquals("–", driverMonogram(""))
        assertEquals("–", driverMonogram("   "))
    }

    @Test fun theQuestionNamesThePersonNotTheDepartment() {
        assertEquals("오늘도 Kim Jiwoo 님이 운전하나요?", driverQuestion(kimJiwoo))
        assertEquals("오늘도 김서연 님이 운전하나요?", driverQuestion(kim))
        assertEquals("이름이 비면 부서·성명 표시", "오늘도 Sales 님이 운전하나요?", driverQuestion(Driver(id("x"), "Sales", " ")))
    }

    @Test fun lastDriveReadsTodayYesterdayOrTheDate() {
        assertEquals("운행 기록 없음", lastDriveLabel(null, now, zone))
        assertEquals("마지막 운행 오늘 08:47", lastDriveLabel(at(9, 8, 47), now, zone))
        assertEquals("마지막 운행 어제 18:47", lastDriveLabel(at(8, 18, 47), now, zone))
        assertEquals("마지막 운행 11월 3일", lastDriveLabel(at(3, 9), now, zone))
        assertEquals("마지막 운행 2025년 12월 30일", lastDriveLabel(at(30, 9, month = 12, year = 2025), now, zone))
        val closed = trip("closed", kimJiwoo, at(8, 18), at(8, 18, 47))
        val demo = trip("demo", kimJiwoo, at(9, 9), at(9, 10), demo = true)
        val recording = trip("open", kimJiwoo, at(9, 18), null)
        val data = TripLogData(drivers = listOf(kimJiwoo, leeMinho), trips = listOf(closed, demo, recording), open = recording)
        assertEquals("기록 중인 운행과 시연 운행은 빼고", at(8, 18, 47), lastDriveAt(data, kimJiwoo.id))
        assertEquals("시연 모드에서는 시연 운행만", at(9, 10), lastDriveAt(data.copy(profile = FleetProfile(demoMode = true)), kimJiwoo.id))
        assertNull(lastDriveAt(data, leeMinho.id))
    }

    @Test fun theStatusLineAddsTodaysAnswer() {
        assertEquals("오늘 확인함 · 마지막 운행 어제 18:47", driverStatusLine(at(8, 18, 47), now, zone, answeredToday = true))
        assertEquals("마지막 운행 어제 18:47", driverStatusLine(at(8, 18, 47), now, zone, answeredToday = false))
        assertEquals("오늘 확인함 · 운행 기록 없음", driverStatusLine(null, now, zone, answeredToday = true))
        assertEquals("TalkBack은 카드를 한 번에", "지금 운전, Kim Jiwoo, Sales, 오늘 확인함, 마지막 운행 어제 18:47",
            driverNowDescription(kimJiwoo, "오늘 확인함 · 마지막 운행 어제 18:47"))
        assertEquals("부서가 없으면 건너뜀", "지금 운전, 이준, 운행 기록 없음", driverNowDescription(lee.copy(department = ""), "운행 기록 없음"))
    }

    @Test fun rowsKeepAStableOrderAndSayWhatIsNext() {
        val data = TripLogData(drivers = listOf(lee, choi, kim, park), currentDriverId = lee.id,
            trips = listOf(trip("lee", lee, at(8, 18), at(8, 18, 47)), trip("lee-demo", lee, at(9, 9), at(9, 10), demo = true)),
            reservations = listOf(booking("kim", kim, at(9, 14), at(9, 17)), booking("park", park, at(10, 9), at(10, 10)),
                booking("lee", lee, at(10, 14, 50), at(10, 16)), booking("choi-demo", choi, at(9, 14), at(9, 15), demo = true)))
        val early = driverRows(data, lee.id, at(9, 13, 50), zone)
        assertEquals("가나다순, 선택한 사람도 제자리", listOf(kim, park, lee, choi), early.map { it.driver })
        assertEquals(listOf(false, false, true, false), early.map { it.selected })
        assertEquals("곧 예약 · 14:00부터", early[0].status)
        assertEquals("다음 예약 · 내일 09:00", early[1].status)
        assertEquals("25시간 뒤 예약은 아직 말하지 않음", "마지막 운행 어제 18:47", early[2].status)
        assertEquals("시연 예약은 실제 목록에 없음", "운행 기록 없음", early[3].status)
        assertEquals("완료 운행만 셈", 1, early[2].trips)
        assertEquals(at(8, 18, 47), early[2].lastAt)
        val later = driverRows(data, kim.id, at(9, 14, 30), zone)
        assertEquals("선택이 바뀌어도 순서는 그대로", listOf(kim, park, lee, choi), later.map { it.driver })
        assertTrue(later[0].selected)
        assertEquals("예약 사용 중 · 17:00까지", later[0].status)
        assertEquals("다음 예약 · 내일 09:00", later[1].status)
        val named = data.copy(drivers = data.drivers + kimJiwoo)
        assertEquals(listOf(kimJiwoo), driverRows(named, null, now, zone, "sales").map { it.driver })
        assertEquals(listOf(kim), driverRows(named, null, now, zone, "서연").map { it.driver })
        assertEquals("공백·대소문자 무시", listOf(kimJiwoo), driverRows(named, null, now, zone, " KIM jiwoo").map { it.driver })
        assertTrue(driverRows(named, null, now, zone, "홍길동").isEmpty())
        assertEquals("같은 이름은 부서순", listOf("영업1팀", "영업2팀"),
            driverRows(TripLogData(drivers = listOf(kim.copy(id = id("kim2"), department = "영업2팀"), kim)), null, now, zone).map { it.driver.department })
    }

    @Test fun theManagerListsDatesAndThePickerNamesItsTrip() {
        assertEquals("운행 12건 · 마지막 운행 11월 8일", driverHistoryLine(12, at(8, 18, 47), now, zone))
        assertEquals("운행 3건 · 마지막 운행 2025년 12월 30일", driverHistoryLine(3, at(30, 9, month = 12, year = 2025), now, zone))
        assertEquals("운행 기록 없음", driverHistoryLine(0, null, now, zone))
        val closed = trip("t", kimJiwoo, at(9, 8, 12), at(9, 8, 47))
        assertEquals("11월 9일 (월) 08:12–08:47 · 12.4 km", pickerTripLine(closed, zone))
        assertEquals("08:12 출발 · 기록 중", pickerTripLine(closed.copy(endAt = null), zone))
        assertEquals("Kim Jiwoo, Sales, 마지막 운행 어제 18:47", driverRowDescription(DriverRowInfo(kimJiwoo, true, 1, at(8, 18, 47), "마지막 운행 어제 18:47")))
    }

    @Test fun pickerColumnsFollowTheDialogWidthAndFont() {
        assertEquals(2, pickerColumns(664.dp, 1f))
        assertEquals(1, pickerColumns(664.dp, 1.3f))
        assertEquals(1, pickerColumns(560.dp, 1f))
        assertEquals("작은 글꼴은 1배로 셈", 1, pickerColumns(560.dp, .85f))
    }

    @Test fun onlyDriversWithoutAnyTripCanBeRemoved() {
        val recording = trip("open", lee, at(9, 18), null)
        val all = TripLogData(drivers = listOf(kim, park, lee, choi), open = recording,
            trips = listOf(trip("real", kim, at(8, 9), at(8, 10)), trip("demo", park, at(8, 11), at(8, 12), demo = true)))
        assertFalse("실제 운행", driverRemovable(all, kim.id))
        assertFalse("시연 모드가 꺼져 있어도 시연 운행", driverRemovable(all, park.id))
        assertFalse("기록 중인 운행", driverRemovable(all, lee.id))
        assertTrue(driverRemovable(all, choi.id))
    }

    @Test fun duplicatesAreFlaggedButNotAgainstThemselves() {
        val drivers = listOf(kimJiwoo, leeMinho)
        assertTrue(driverDuplicate(drivers, " sales ", "kim  jiwoo", null))
        assertFalse("고치는 사람 자신", driverDuplicate(drivers, "Sales", "Kim Jiwoo", kimJiwoo.id))
        assertFalse("다른 부서", driverDuplicate(drivers, "Finance", "Kim Jiwoo", null))
        assertFalse("이름이 비면 묻지 않음", driverDuplicate(drivers, "Sales", " ", null))
    }

    @Test fun pickMessagesSayWhatChanged() {
        assertEquals("운전자를 Lee Minho 님으로 바꿨어요", driverPickMessage(leeMinho, kimJiwoo, forTrip = false))
        assertEquals("이 운행의 운전자를 Lee Minho 님으로 바꿨어요", driverPickMessage(leeMinho, kimJiwoo, forTrip = true))
        assertEquals("이 운행에 Lee Minho 님을 지정했어요", driverPickMessage(leeMinho, null, forTrip = true))
        assertEquals("지금 운전하는 직원을 Lee Minho 님으로 정했어요", driverPickMessage(leeMinho, null, forTrip = false))
        assertNull("같은 사람은 그냥 닫힘", driverPickMessage(kimJiwoo, kimJiwoo, forTrip = false))
        assertNull(driverPickMessage(kimJiwoo, kimJiwoo, forTrip = true))
    }

    @Test fun undoPutsTheDriverBackWhereItWas() {
        assertEquals(listOf(kim, park, lee), restoredDrivers(listOf(kim, lee), park, 1))
        assertEquals("목록이 줄었으면 끝에", listOf(kim, lee, park), restoredDrivers(listOf(kim, lee), park, 9))
        assertEquals(listOf(kim, lee, park), restoredDrivers(listOf(kim, lee), park, Int.MAX_VALUE))
        assertEquals("이미 있으면 그대로", listOf(kim, park, lee), restoredDrivers(listOf(kim, park, lee), park, 0))
    }

    @Test fun aBookingSwitchesTheDriverOnceAndAManualPickSticks() {
        val first = booking("first", leeMinho, at(9, 14), at(9, 17))
        val data = TripLogData(drivers = listOf(kimJiwoo, leeMinho), currentDriverId = kimJiwoo.id, reservations = listOf(first))
        val parked = at(9, 14, 5)
        assertEquals(first, bookingDriverSwitch(data, parked, null))
        assertEquals("15분 전부터", first, bookingDriverSwitch(data, at(9, 13, 50), null))
        assertNull("한 번 바꾼 예약은 다시 바꾸지 않음", bookingDriverSwitch(data, parked, first.id))
        assertNull("운행 중에는 바꾸지 않음", bookingDriverSwitch(data.copy(open = trip("open", kimJiwoo, at(9, 13), null)), parked, null))
        assertNull("등록되지 않은 직원의 예약", bookingDriverSwitch(data.copy(drivers = listOf(kimJiwoo)), parked, null))
        assertNull("예약한 직원이 이미 운전 중", bookingDriverSwitch(data.copy(currentDriverId = leeMinho.id), parked, null))
        assertNull("예약 시간 밖", bookingDriverSwitch(data, at(9, 12), null))
        val second = booking("second", leeMinho, at(9, 18), at(9, 19))
        assertEquals("다음 예약은 다시 한 번", second, bookingDriverSwitch(data.copy(reservations = listOf(first, second)), at(9, 18, 10), first.id))
    }
}
