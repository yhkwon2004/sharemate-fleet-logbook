package ai.sharemate.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class PhoneReservationsUiTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 4) // a Wednesday
    private fun at(day: Int, hour: Int, minute: Int = 0, second: Int = 0) =
        LocalDateTime.of(2026, 11, day, hour, minute, second).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val kim = Driver(id("kim"), "영업", "김")
    private val lee = Driver(id("lee"), "재무", "이")
    private fun booking(seed: String, driver: Driver, from: Long, to: Long, purpose: TripPurpose? = TripPurpose.BUSINESS, destination: String = "") =
        Reservation(id(seed), driver.id, from, to, purpose, destination)

    @Test fun roundsUpToTheNextHalfHour() {
        assertEquals(at(4, 9, 0), nextHalfHourMillis(at(4, 9, 0), zone))
        assertEquals(at(4, 9, 30), nextHalfHourMillis(at(4, 9, 0, 1), zone))
        assertEquals(at(4, 9, 30), nextHalfHourMillis(at(4, 9, 10), zone))
        assertEquals(at(4, 10, 0), nextHalfHourMillis(at(4, 9, 31), zone))
        assertEquals(at(5, 0, 0), nextHalfHourMillis(at(4, 23, 45), zone))
    }

    @Test fun convertsDateAndMinutesToEpochMillisInTheZone() {
        assertEquals(at(4, 9, 30), epochMillisAt(today, 9 * 60 + 30, zone))
        assertEquals(at(5, 0), epochMillisAt(today, DAY_MINUTES, zone))
        assertEquals(570, minutesOfDay(at(4, 9, 30), today, zone))
        assertEquals(0, minutesOfDay(at(3, 22), today, zone))
        assertEquals(DAY_MINUTES, minutesOfDay(at(5, 3), today, zone))
        assertEquals("09:05", minutesText(545)); assertEquals("24:00", minutesText(DAY_MINUTES))
    }

    @Test fun stripOffersFourteenDaysWithKoreanLabels() {
        val dates = stripDates(today)
        assertEquals(14, dates.size); assertEquals(today, dates.first()); assertEquals(today.plusDays(13), dates.last())
        assertEquals("오늘", stripLabel(today, today))
        assertEquals("내일", stripLabel(today.plusDays(1), today))
        assertEquals("11/20 (금)", stripLabel(LocalDate.of(2026, 11, 20), today))
        assertEquals("오늘, 11월 4일 (수), 예약 2건", stripDescription(today, today, 2))
        assertEquals("11월 6일 (금)", stripDescription(today.plusDays(2), today, 0))
    }

    @Test fun defaultSlotIsTheNextHalfHourTodayAndNineOnLaterDays() {
        assertEquals(570 to 690, defaultSlot(today, at(4, 9, 10), zone))
        assertEquals(DEFAULT_START_MINUTES to DEFAULT_START_MINUTES + 120, defaultSlot(today.plusDays(3), at(4, 9, 10), zone))
        assertEquals(1380 to DAY_MINUTES, defaultSlot(today, at(4, 22, 40), zone)) // 23:00, clipped at midnight
        assertEquals(1410 to DAY_MINUTES, defaultSlot(today, at(4, 23, 50), zone)) // the day is over: last half hour
    }

    @Test fun steppersKeepAValidSlot() {
        assertEquals(600 to 720, shiftStart(570, 690, 30))           // moves and keeps two hours
        assertEquals(1410 to DAY_MINUTES, shiftStart(1380, DAY_MINUTES, 60)) // never past 23:30, end follows
        assertEquals(0 to 120, shiftStart(0, 120, -30))
        assertEquals(570 to 600, shiftEnd(570, 630, -60))            // never before start + 30
        assertEquals(570 to DAY_MINUTES, shiftEnd(570, 1410, 60))
    }

    @Test fun tappingAFreeHourFillsAFreeSlot() {
        val day = today.plusDays(1)
        val later = listOf(booking("a", kim, at(5, 11), at(5, 13)))
        assertEquals(600 to 660, slotFromHour(10, day, later, at(4, 9), zone))  // stops at the 11:00 booking
        assertEquals(840 to 960, slotFromHour(14, day, later, at(4, 9), zone))
        assertEquals(570 to 690, slotFromHour(9, today, emptyList(), at(4, 9, 10), zone)) // hour under way: next half hour
        assertEquals(1380 to DAY_MINUTES, slotFromHour(23, day, emptyList(), at(4, 9), zone))
    }

    @Test fun hourRowsKnowTheirBookingPastAndNow() {
        val list = listOf(booking("a", kim, at(4, 9, 30), at(4, 11)))
        assertNull(hourOccupant(list, today, 8, zone))
        assertEquals(id("a"), hourOccupant(list, today, 9, zone)?.id)
        assertEquals(id("a"), hourOccupant(list, today, 10, zone)?.id)
        assertNull(hourOccupant(list, today, 11, zone))
        assertTrue(hourIsPast(today, 8, at(4, 9, 10), zone)); assertFalse(hourIsPast(today, 9, at(4, 9, 10), zone))
        assertEquals(0.5f, nowFraction(today, 9, at(4, 9, 30), zone)!!, 0.001f)
        assertNull(nowFraction(today, 10, at(4, 9, 30), zone))
        assertEquals("09:30–11:00", dayWindowText(list[0], today, zone))
        assertEquals("22:00–24:00", dayWindowText(booking("n", kim, at(4, 22), at(5, 2)), today, zone))
    }

    @Test fun summaryAndDetailText() {
        assertEquals("11월 20일 (금) 09:00–12:00 예약을 차량에 보냈어요", reservationSentText(LocalDate.of(2026, 11, 20), 540, 720))
        assertEquals("2시간", slotLengthText(540, 660)); assertEquals("30분", slotLengthText(540, 570)); assertEquals("1시간 30분", slotLengthText(540, 630))
        val data = TripLogData(drivers = listOf(kim))
        assertEquals("영업 · 김 · 일반 업무 · 본사 → 수원", reservationDetailText(data, booking("a", kim, at(4, 14), at(4, 16), destination = "본사 → 수원")))
        assertEquals("영업 · 김 · 목적 미정", reservationDetailText(data, booking("b", kim, at(4, 14), at(4, 16), purpose = null)))
        assertEquals("목록에 없는 직원", bookerLabel(data, id("ghost")))
    }

    @Test fun whoBooksAndWhetherTheFormCanSend() {
        val data = TripLogData(drivers = listOf(kim, lee))
        val account = PhoneAccount("g1", "kim@example.com", "김")
        assertNull(bookingDriverId(null, data))
        assertNull(bookingDriverId(account, data))
        assertEquals(kim.id, bookingDriverId(account.copy(driverId = kim.id), data))
        assertNull("a driver id from another car does not count", bookingDriverId(account.copy(driverId = id("other")), data))
        assertNotNull(bookingBlockedReason(paired = false, connected = false))
        assertNotNull(bookingBlockedReason(paired = true, connected = false))
        assertNull(bookingBlockedReason(paired = true, connected = true))
        val (mine, others) = splitReservations(listOf(booking("a", kim, 1, 2), booking("b", lee, 3, 4)), kim.id)
        assertEquals(listOf(id("a")), mine.map { it.id }); assertEquals(listOf(id("b")), others.map { it.id })
        assertTrue(splitReservations(listOf(booking("a", kim, 1, 2)), null).first.isEmpty())
    }

    @Test fun precheckCatchesClashesAndBadRequestsBeforeSending() {
        val data = TripLogData(drivers = listOf(kim, lee), reservations = listOf(booking("a", kim, at(5, 9), at(5, 12))))
        val now = at(4, 9)
        assertNull(precheckReservation(data, booking("b", lee, at(5, 12), at(5, 14)), now, zone))
        val clash = precheckReservation(data, booking("c", lee, at(5, 11), at(5, 13)), now, zone)
        assertNotNull(clash); assertTrue(clash!!.contains("김"))
        assertNotNull(slotConflictText(data, at(5, 11), at(5, 13), zone))
        assertNull(slotConflictText(data, at(5, 12), at(5, 13), zone))
        assertNotNull(precheckReservation(data, booking("d", lee, at(3, 9), at(3, 10)), now, zone))   // already over
        assertNotNull(precheckReservation(data, booking("e", Driver(id("x"), "", "유령"), at(5, 14), at(5, 15)), now, zone))
    }

    @Test fun theFormMovesPastWhatWasJustSent() {
        assertEquals(17 * 60 to 19 * 60, slotAfterSent(17 * 60))
        assertEquals("자정까지 남은 만큼만", 23 * 60 to 24 * 60, slotAfterSent(23 * 60))
        assertNull("하루가 끝나면 다음 날로", slotAfterSent(24 * 60))
    }

    @Test fun theDaySitsBesideTheFormFromALandscapePhoneUp() {
        assertFalse("세로 휴대폰은 한 줄로", bookingTwoPane(phoneLayout(411.dp, 900.dp)))
        assertTrue("가로 휴대폰", bookingTwoPane(phoneLayout(800.dp, 411.dp)))
        assertTrue("태블릿", bookingTwoPane(phoneLayout(1000.dp, 800.dp)))
        assertFalse("1.3배 글자에서는 가로 휴대폰도 한 줄로", bookingTwoPane(fallbackPhoneTokens(760.dp, 411.dp, 1.3f)))
    }

    @Test fun theNowLineRestsInsideItsHourRow() {
        assertEquals(0f, nowLineTarget(null), 0f)
        assertEquals(0.5f, nowLineTarget(0.5f), 0f)
        assertEquals("never below the row", 1f, nowLineTarget(1.4f), 0f)
        assertEquals("never above it", 0f, nowLineTarget(-0.2f), 0f)
    }
}
