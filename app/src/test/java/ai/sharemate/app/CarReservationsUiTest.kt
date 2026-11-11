package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class CarReservationsUiTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val kim = Driver(id("kim"), "영업", "김")
    private fun booking(seed: String, from: Long, to: Long, purpose: TripPurpose? = null, destination: String = "") =
        Reservation(id(seed), kim.id, from, to, purpose, destination)

    @Test fun clockTextNamesTheDayOnlyWhenItIsNotToday() {
        val now = at(4, 9)
        assertEquals("17:00", carClockText(at(4, 17), now, zone))
        assertEquals("내일 09:30", carClockText(at(5, 9, 30), now, zone))
        assertEquals("11월 21일 (토) 18:00", carClockText(at(21, 18), now, zone))
    }

    @Test fun activeBookingLineSaysWhetherTheDriverWasSet() {
        val data = TripLogData(drivers = listOf(kim), reservations = listOf(booking("a", at(4, 14), at(4, 17))))
        val matched = nextReservationLine(data.copy(currentDriverId = kim.id), at(4, 15), zone)!!
        assertTrue(matched.active)
        assertEquals("예약 사용 중 · 김 님 · 17:00까지 · 운전자를 예약한 직원으로 맞췄어요", matched.text)
        assertEquals("예약 사용 중 · 김 님 · 17:00까지", nextReservationLine(data, at(4, 15), zone)!!.text)
        assertEquals("곧 예약 시작 · 김 님 · 14:00부터", nextReservationLine(data, at(4, 13, 50), zone)!!.text)
        assertEquals("예약 사용 중 · 17:00까지", carActiveTitle(data.reservations[0], at(4, 15), zone))
        assertEquals("곧 예약 시작 · 14:00부터 17:00까지", carActiveTitle(data.reservations[0], at(4, 13, 50), zone))
    }

    @Test fun upcomingLineOnlyWithinADay() {
        val data = TripLogData(drivers = listOf(kim), reservations = listOf(booking("a", at(4, 14), at(4, 17), TripPurpose.BUSINESS, "본사 → 수원")))
        val line = nextReservationLine(data, at(4, 9), zone)!!
        assertFalse(line.active)
        assertEquals("다음 예약 · 오늘 14:00 · 영업 · 김 · 일반 업무 · 본사 → 수원", line.text)
        assertNull(nextReservationLine(data, at(3, 13), zone))   // more than 24 h ahead
        assertNull(nextReservationLine(TripLogData(drivers = listOf(kim)), at(4, 9), zone))
        val cancelled = data.copy(reservations = data.reservations.map { it.copy(status = ReservationStatus.CANCELLED) })
        assertNull(nextReservationLine(cancelled, at(4, 9), zone))
    }

    @Test fun bookerNameFallsBack() {
        val data = TripLogData(drivers = listOf(kim, Driver(id("x"), "총무", "")))
        assertEquals("김", bookerName(data, kim.id))
        assertEquals("총무", bookerName(data, id("x")))
        assertEquals("예약한 직원", bookerName(data, id("ghost")))
    }

    @Test fun emptyTextPromisesThePurposeOnlyWhenTheCarAppliesIt() {
        // 2.12: one ⓘ line, with what the car does when a booking starts folded under it.
        val where = "휴대폰 앱 예약 탭에서 잡으면 여기 보여요"
        assertEquals(where to listOf("예약 시간이 되면 예약한 직원으로 바꿔요", "그 시간 운행은 예약 목적으로 기록돼요"), carReservationsEmptyText(true))
        assertEquals("자동 완성이 꺼져 있으면 목적 문장은 빠짐", where to listOf("예약 시간이 되면 예약한 직원으로 바꿔요"), carReservationsEmptyText(false))
        assertOneLine(where, INFO_LINE_UNITS)
    }

    @Test fun theOverflowLineSaysHowManyAndWhere() {
        assertEquals("외 3건은 휴대폰 앱 예약 탭에서 봐요", carReservationsOverflow(3))
        assertOneLine(carReservationsOverflow(12), CARD_LINE_UNITS)
    }
}
