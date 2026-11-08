package ai.sharemate.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

class PhoneMidnightTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(month: Int, day: Int, hour: Int) = LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val driver = Driver(id("d"), "영업", "권")
    private fun trip(seed: String, month: Int, day: Int, start: Double, purpose: TripPurpose = TripPurpose.BUSINESS, km: Double = 10.0, demo: Boolean = false) =
        Trip(id(seed), at(month, day, 9), at(month, day, 10), start, start + km, driverId = driver.id, purpose = purpose, demo = demo, updatedAt = at(month, day, 10))

    @Test fun cardLinesAreShortAndComeFromTheCopy() {
        val today = LocalDate.of(2026, 11, 20)
        val empty = phoneCardLines(TripLogData(), today, zone)
        assertEquals("차량 상태", empty.vehicle); assertEquals("11월 운행 없음", empty.log); assertEquals("11월 Excel", empty.export)
        val data = TripLogData(drivers = listOf(driver), vehicle = VehicleCard(socPercent = 82, odometerKm = 12_394.0),
            trips = listOf(trip("a", 11, 3, 100.0), trip("b", 11, 5, 110.0, TripPurpose.UNCLASSIFIED)))
        val lines = phoneCardLines(data, today, zone)
        assertEquals("마지막 저장 잔량 82%", lines.vehicle)
        assertEquals("배터리 81%", phoneCardLines(data, today, zone, socPercent = 81).vehicle)
        assertEquals("미분류 1건", lines.log); assertEquals(1, lines.logPending)
        assertEquals("기한이 다가오면 기한이 먼저", "정기검사 D-10",
            phoneCardLines(data.copy(care = VehicleCare(inspectionDue = "2026-11-30")), today, zone).vehicle)
        assertEquals("2건 · 모두 확인", phoneCardLines(data.copy(trips = data.trips.map { it.copy(purpose = TripPurpose.BUSINESS) }), today, zone).log)
        assertEquals("시연 모드가 아니면 시연 기록은 세지 않음", "11월 운행 없음",
            phoneCardLines(data.copy(trips = data.trips.map { it.copy(demo = true) }), today, zone).log)
    }

    @Test fun exportCardNamesLastMonthWhileItIsOpen() {
        val data = TripLogData(drivers = listOf(driver), trips = listOf(trip("a", 10, 3, 100.0), trip("b", 11, 2, 110.0)))
        assertEquals("10월 마감 전 Excel", phoneCardLines(data, LocalDate.of(2026, 11, 3), zone).export)
        assertEquals("11월 Excel", phoneCardLines(closeMonth(data, YearMonth.of(2026, 10), at(11, 1, 9), zone), LocalDate.of(2026, 11, 3), zone).export)
    }

    @Test fun bookingCardSaysWhetherTheCarIsFree() {
        val today = LocalDate.of(2026, 11, 20)
        val noon = at(11, 20, 12)
        val data = TripLogData(drivers = listOf(driver))
        assertEquals("지금 예약 가능", phoneCardLines(data, today, zone, noon).booking)
        val booked = data.copy(reservations = listOf(Reservation(id("r"), driver.id, at(11, 20, 15), at(11, 20, 17))))
        assertEquals("다음 오늘 15:00", phoneCardLines(booked, today, zone, noon).booking)
        assertEquals("예약 사용 중", phoneCardLines(booked, today, zone, at(11, 20, 16)).booking)
        assertEquals("지금 운행 중", phoneCardLines(data.copy(open = Trip(id("o"), at(11, 20, 11), null, 1.0, 2.0)), today, zone, noon).booking)
        assertEquals("휴대폰이 받은 실시간 배터리가 먼저", "배터리 64%", phoneCardLines(data, today, zone, noon, socPercent = 64).vehicle)
    }

    @Test fun subtitleTellsTheTruthAboutTheLink() {
        assertEquals("차량 기록을 실시간으로 받고 있어요", phoneSubtitle(paired = true, connected = true, open = null))
        assertEquals("연결 대기 · 마지막으로 받은 기록을 보여 줘요", phoneSubtitle(paired = true, connected = false, open = null))
        assertTrue(phoneSubtitle(paired = false, connected = false, open = null).startsWith("파일 사본"))
        assertTrue(phoneSubtitle(true, true, Trip(id("o"), at(11, 20, 8), null, 100.0, 103.5)).contains("3.5 km"))
    }

    @Test fun todayCountsFinishedTripsAndTheOpenOneStartedToday() {
        val today = LocalDate.of(2026, 11, 20)
        val data = TripLogData(trips = listOf(trip("a", 11, 20, 100.0, km = 12.0).copy(expenses = listOf(TripExpense(ExpenseType.PARKING, 3_000, at(11, 20, 10), EditSource.DRIVER))),
            trip("y", 11, 19, 80.0)), open = Trip(id("o"), at(11, 20, 15), null, 112.0, 115.5))
        val day = phoneToday(data, today, zone)
        assertEquals(15.5, day.km, 1e-9); assertEquals("열린 운행도 건수에 포함", 2, day.trips); assertEquals(3_000L, day.expenseWon)
        assertEquals("어제 시작한 열린 운행은 오늘 거리에 넣지 않음", 12.0,
            phoneToday(data.copy(open = data.open!!.copy(startAt = at(11, 19, 23))), today, zone).km, 1e-9)
    }

    @Test fun heroColumnsFollowTheWidthAndTheFontScale() {
        assertEquals("세로 휴대폰: 2 × 2", 2, phoneHeroColumns(371.dp, 1f))
        assertEquals("태블릿: 네 장 가로", 4, phoneHeroColumns(664.dp, 1f))
        assertEquals("경계값", 4, phoneHeroColumns(640.dp, 1f))
        assertEquals("1.3× 글꼴: 한 줄에 한 장", 1, phoneHeroColumns(664.dp, 1.3f))
        assertEquals("큰 글꼴은 너비도 배율로 나눠 봄", 2, phoneHeroColumns(700.dp, 1.15f))
    }

    @Test fun landscapePhoneGetsTheRailAndTheSplitHero() {
        assertTrue(phoneUsesRail(915.dp, 412.dp))
        assertFalse("세로 휴대폰은 아래 탭", phoneUsesRail(412.dp, 915.dp))
        assertFalse("가로 태블릿도 아래 탭", phoneUsesRail(1280.dp, 800.dp))
        assertTrue(phoneHomeSplit(600.dp, 300.dp))
        assertFalse("세로", phoneHomeSplit(371.dp, 800.dp))
        assertFalse("태블릿은 쌓은 히어로에 네 장 가로", phoneHomeSplit(760.dp, 700.dp))
        assertFalse("너무 좁으면 쌓기", phoneHomeSplit(500.dp, 300.dp))
        assertFalse("1.3× 글꼴은 한 열", phoneHomeSplit(600.dp, 300.dp, 1.3f))
    }

    @Test fun dayHazeEndsUnderTheGreetingOrAtAQuarterOfTheHeight() {
        assertEquals(420f, phoneHazeEnd(420f, 2000f), 0f)
        assertEquals("측정 전에는 높이의 26 %", 520f, phoneHazeEnd(0f, 2000f), 0f)
        assertEquals("화면 밖 값은 무시", 520f, phoneHazeEnd(2400f, 2000f), 0f)
    }
}
