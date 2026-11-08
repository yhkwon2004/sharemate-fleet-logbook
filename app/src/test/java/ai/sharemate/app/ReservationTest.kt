package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class ReservationTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val kim = Driver(id("kim"), "영업", "김")
    private val lee = Driver(id("lee"), "재무", "이")
    private val base = TripLogData(drivers = listOf(kim, lee))
    private val now = at(10, 9)
    private fun request(seed: String, driver: Driver, from: Long, to: Long, purpose: TripPurpose? = TripPurpose.BUSINESS) =
        Reservation(id(seed), driver.id, from, to, purpose, "본사 → 수원", "")

    @Test fun purposeUsesTheSameBookingAsAutomaticDriverSelectionRegardlessOfListOrder() {
        val current = request("current", kim, at(10, 10), at(10, 11), TripPurpose.BUSINESS)
        val upcoming = request("next", lee, at(10, 11), at(10, 12))
        val data = base.copy(reservations = listOf(upcoming, current))
        val start = at(10, 10, 50)
        val trip = Trip("trip", start, start + 60_000, 100.0, 101.0)
        assertEquals(current.id, activeReservation(data, start)?.id)
        assertEquals(current.id, reservationForTrip(data, trip)?.id)
        // A different manually selected driver must not inherit the next booking's purpose early.
        assertNull(reservationForTrip(data, trip.copy(driverId = lee.id)))
        assertEquals(upcoming.id, reservationForTrip(data, trip.copy(startAt = at(10, 11), driverId = lee.id))?.id)
    }

    @Test fun aFreeSlotIsConfirmedAndAnOverlapIsRefusedWithWhoHasIt() {
        val booked = reserveIn(base, request("a", kim, at(10, 14), at(10, 17)), now, zone)
        assertEquals(1, booked.reservations.size); assertEquals(ReservationStatus.CONFIRMED, booked.reservations[0].status)
        val clash = assertThrows(IllegalArgumentException::class.java) { reserveIn(booked, request("b", lee, at(10, 16), at(10, 18)), now, zone) }
        assertTrue(clash.message!!.contains("영업 · 김") || clash.message!!.contains("김"))
        assertTrue("끝과 시작이 맞닿는 것은 겹침이 아님", reserveIn(booked, request("c", lee, at(10, 17), at(10, 19)), now, zone).reservations.size == 2)
    }

    @Test fun requestsAreValidated() {
        fun fails(r: Reservation) = assertThrows(IllegalArgumentException::class.java) { reserveIn(base, r, now, zone) }
        fails(request("x", kim, at(10, 14), at(10, 14, 10)))                 // under 15 minutes
        fails(request("x", kim, at(10, 14), at(10, 13)))                     // ends before it starts
        fails(request("x", kim, at(9, 8), at(9, 9)))                         // already over
        fails(request("x", kim, at(10, 10), at(18, 10)))                     // longer than 7 days
        fails(request("x", Driver(id("ghost"), "", "유령"), at(10, 14), at(10, 15)))  // unknown employee
        fails(Reservation(id("x"), kim.id, at(10, 14), at(10, 15), destination = "가".repeat(61)))
    }

    @Test fun cancelAndRestoreKeepTheSlotHonest() {
        val booked = reserveIn(base, request("a", kim, at(10, 14), at(10, 17)), now, zone)
        val cancelled = cancelReservationIn(booked, id("a"), now)
        assertEquals(ReservationStatus.CANCELLED, cancelled.reservations[0].status)
        val other = reserveIn(cancelled, request("b", lee, at(10, 15), at(10, 16)), now, zone)
        assertThrows("되돌릴 시간에 다른 예약이 생기면 거절", IllegalArgumentException::class.java) { restoreReservationIn(other, id("a"), now) }
        assertEquals(ReservationStatus.CONFIRMED, restoreReservationIn(cancelled, id("a"), now).reservations[0].status)
        assertThrows("끝난 예약은 취소할 수 없음", IllegalArgumentException::class.java) { cancelReservationIn(booked, id("a"), at(10, 18)) }
    }

    @Test fun theActiveWindowStartsFifteenMinutesEarlyAndTripsInsideItAreMatched() {
        val booked = reserveIn(base, request("a", kim, at(10, 14), at(10, 17)), now, zone)
        assertNull(activeReservation(booked, at(10, 13, 40)))
        assertEquals(id("a"), activeReservation(booked, at(10, 13, 46))?.id)
        assertNull(activeReservation(booked, at(10, 17)))
        val trip = Trip(id("t"), at(10, 14, 5), at(10, 15), 100.0, 130.0, driverId = kim.id)
        assertEquals(id("a"), reservationForTrip(booked, trip)?.id)
        assertNull("다른 직원의 운행은 예약 목적을 받지 않음", reservationForTrip(booked, trip.copy(driverId = lee.id)))
        assertNull(reservationForTrip(booked, trip.copy(startAt = at(10, 17, 5))))
    }

    @Test fun availabilityAndCopy() {
        val booked = reserveIn(base, request("a", kim, at(10, 14), at(10, 17)), now, zone)
        val free = vehicleAvailability(booked, now)
        assertTrue(free is VehicleAvailability.Free)
        assertEquals("사용 가능 · 다음 예약 오늘 14:00", availabilityText(free, now, zone))
        val during = vehicleAvailability(booked, at(10, 15))
        assertTrue(during is VehicleAvailability.Reserved)
        assertEquals("예약 사용 중 · 17:00까지", availabilityText(during, at(10, 15), zone))
        val driving = vehicleAvailability(booked.copy(open = Trip(id("o"), at(10, 15), null, 1.0, 2.0, driverId = lee.id)), at(10, 15))
        assertEquals("운행 중 · 재무 · 이", availabilityText(driving, at(10, 15), zone))
        assertEquals("사용 가능 · 예정된 예약 없음", availabilityText(vehicleAvailability(base, now), now, zone))
        assertEquals("내일 09:30", reservationStartText(request("t", kim, at(11, 9, 30), at(11, 10)), now, zone))
        assertEquals(listOf(id("a")), reservationsOn(booked, LocalDate.of(2026, 11, 10), zone).map { it.id })
        assertTrue(reservationsOn(booked, LocalDate.of(2026, 11, 11), zone).isEmpty())
    }

    @Test fun oldBookingsArePrunedOnTheNextBooking() {
        val old = Reservation(id("old"), kim.id, at(1, 9) - 100L * 24 * 3_600_000, at(1, 10) - 100L * 24 * 3_600_000)
        val next = reserveIn(base.copy(reservations = listOf(old)), request("a", kim, at(10, 14), at(10, 15)), now, zone)
        assertEquals(listOf(id("a")), next.reservations.map { it.id })
    }

    @Test fun reservationsSurviveTheLogbookCodecAndTheSyncCommand() {
        val booked = reserveIn(base, request("a", kim, at(10, 14), at(10, 17)), now, zone)
        val round = TripLogCodec.decode(TripLogCodec.encode(booked))
        assertEquals(booked.reservations, round.reservations)
        val command = SyncCommand.Reserve(request("a", kim, at(10, 14), at(10, 17)))
        val parsed = parseSyncCommand(encodeSyncCommand(command)) as SyncCommand.Reserve
        assertEquals(command.reservation.copy(createdAt = parsed.reservation.createdAt), parsed.reservation)
        assertEquals(SyncCommand.CancelReservation(id("a")), parseSyncCommand(encodeSyncCommand(SyncCommand.CancelReservation(id("a")))))
        assertThrows("미분류 목적으로는 예약할 수 없음", Exception::class.java) {
            parseSyncCommand(encodeSyncCommand(SyncCommand.Reserve(request("u", kim, at(10, 14), at(10, 15), TripPurpose.UNCLASSIFIED))))
        }
        assertNotNull(parseSyncCommand(encodeSyncCommand(SyncCommand.Reserve(request("n", kim, at(10, 14), at(10, 15), null)))))
    }
}
