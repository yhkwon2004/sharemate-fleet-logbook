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

    @Test fun theTileLineReadsEveryUpcomingBookingAndNamesOnlySomeoneElse() {
        val data = TripLogData(drivers = listOf(kim), reservations = listOf(booking("a", at(4, 14), at(4, 17))))
        val matched = reservationTileLine(data.copy(currentDriverId = kim.id), at(4, 15), zone)
        assertEquals("운전자가 이미 예약한 직원이면 이름 없이", "사용 중 · 17:00까지", matched.text)
        assertEquals("예약, 사용 중, 17:00까지, 영업 · 김, 운전자를 예약한 직원으로 맞췄어요", matched.spoken)
        assertTrue(matched.spoken.endsWith("운전자를 예약한 직원으로 맞췄어요"))
        val inUse = reservationTileLine(data, at(4, 15), zone)
        assertEquals("사용 중 · 17:00까지 · 김 님", inUse.text)
        assertEquals("예약, 사용 중, 17:00까지, 영업 · 김", inUse.spoken)
        val soon = reservationTileLine(data, at(4, 13, 50), zone)
        assertEquals("15분 전부터 곧 시작", "곧 시작 · 14:00부터 · 김 님", soon.text)
        assertEquals("예약, 곧 시작, 14:00부터 17:00까지, 영업 · 김", soon.spoken)
        val today = reservationTileLine(data, at(4, 9), zone)
        assertEquals("오늘 14:00 · 김 님", today.text)
        assertEquals("예약, 다음 예약 오늘 14:00, 영업 · 김 · 목적 미정", today.spoken)
        assertTrue(today.spoken.startsWith("예약, 다음 예약 오늘 14:00, 영업 · 김"))
        assertNull("홈의 줄은 24시간 안만", nextReservationLine(data, at(3, 13), zone))
        assertEquals("타일은 24시간 너머도 읽음", "내일 14:00 · 김 님", reservationTileLine(data, at(3, 13), zone).text)
        assertEquals("11월 4일 (수) 14:00 · 김 님", reservationTileLine(data, at(1, 9), zone).text)
        val none = ReservationTileLine("현재 잡힌 예약이 없어요", "예약, 현재 잡힌 예약이 없어요")
        assertEquals(none, reservationTileLine(TripLogData(drivers = listOf(kim)), at(4, 9), zone))
        assertEquals("취소된 예약", none, reservationTileLine(data.copy(reservations = data.reservations.map { it.copy(status = ReservationStatus.CANCELLED) }), at(4, 9), zone))
        assertEquals("시연 예약은 실제 화면에 없음", none, reservationTileLine(data.copy(reservations = data.reservations.map { it.copy(demo = true) }), at(4, 9), zone))
        assertEquals("목록에 없는 직원의 예약은 이름 없이", "오늘 14:00", reservationTileLine(data.copy(drivers = emptyList()), at(4, 9), zone).text)
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
