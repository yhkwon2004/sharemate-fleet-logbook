package ai.sharemate.app

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * 2.15 §7: what 설정 › 직원's 직원별 운행 lists (the rows of 월간's card, each with its share of the month's distance) and how
 * much a 설정 section's blocks fill before the page would scroll.
 */
class SettingsFillTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(month: Int, day: Int, hour: Int) = LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val kim = Driver(id("kim"), "Sales", "Kim Jiwoo")
    private val lee = Driver(id("lee"), "Finance", "Lee Minho")
    private fun trip(seed: String, day: Int, km: Double, driver: String?, purpose: TripPurpose = TripPurpose.BUSINESS, open: Boolean = false) =
        Trip(id(seed), at(10, day, 9), if (open) null else at(10, day, 10), 100.0, 100.0 + km, driverId = driver, purpose = purpose, updatedAt = at(10, day, 10))

    @Test fun theSharesOfTheMonthAddUpLongestFirst() {
        val trips = listOf(trip("a", 3, 20.0, kim.id), trip("b", 4, 10.0, kim.id, TripPurpose.PERSONAL), trip("c", 5, 10.0, lee.id), trip("d", 6, 10.0, null))
        val shares = usageShares(trips, listOf(kim, lee))
        // Longest first; a tie keeps the order the trips came in, as 월간's card lists them.
        assertEquals(listOf(kim, lee, null), shares.map { it.usage.driver })
        assertEquals(.6, shares.first().share, 1e-9)
        assertEquals(1.0, shares.sumOf { it.share }, 1e-9)
        assertEquals(listOf(60, 20, 20), shares.map { it.percent })
        // The row keeps 월간's figures: all of the person's trips, business distance apart.
        assertEquals(2, shares.first().usage.totals.trips)
        assertEquals(20.0, shares.first().usage.totals.businessKm, 0.0)
        assertEquals(30.0, shares.first().usage.totals.totalKm, 0.0)
    }

    @Test fun unassignedTripsAreOneRowOfTheirOwn() {
        // A trip without a person and one whose person was deleted are both 사용자 미지정, one row.
        val trips = listOf(trip("a", 3, 30.0, kim.id), trip("b", 4, 5.0, null), trip("c", 5, 15.0, id("gone")))
        val shares = usageShares(trips, listOf(kim))
        assertEquals(2, shares.size)
        val unassigned = shares.single { it.usage.driver == null }
        assertEquals(2, unassigned.usage.totals.trips)
        assertEquals(.4, unassigned.share, 1e-9)
        assertEquals(1.0, shares.sumOf { it.share }, 1e-9)
    }

    @Test fun aMonthWithoutTripsHasNoRowAndTheCardIsLeftOut() {
        assertTrue(usageShares(emptyList(), listOf(kim, lee)).isEmpty())
        // A trip still recording is not a finished trip: 월간's card leaves it out too.
        assertTrue(usageShares(listOf(trip("o", 3, 12.0, kim.id, open = true)), listOf(kim)).isEmpty())
    }

    @Test fun tripsWithoutDistanceShareNothing() {
        val shares = usageShares(listOf(trip("a", 3, 0.0, kim.id), trip("b", 4, 0.0, null)), listOf(kim))
        assertEquals(2, shares.size)
        assertTrue(shares.all { it.share == 0.0 && it.percent == 0 })
    }

    @Test fun theCardShowsTheMonthTheLogLandsOn() {
        // 11월 has no finished trip yet, so the card shows 10월, as 운행기록 opens there (landingMonth, DESIGN_2_15 §2.1).
        val trips = listOf(trip("a", 3, 20.0, kim.id), trip("b", 4, 10.0, lee.id))
        val today = LocalDate.of(2026, 11, 13)
        val month = landingMonth(trips, today, zone)
        assertEquals(YearMonth.of(2026, 10), month)
        assertEquals("10월", monthLabel(month, today))
        assertEquals(2, usageShares(tripsIn(trips, month.atDay(1), month.atEndOfMonth(), zone), listOf(kim, lee)).size)
        // Without a trip anywhere the month is this one and there is nothing to list.
        val empty = landingMonth(emptyList(), today, zone)
        assertTrue(usageShares(tripsIn(emptyList(), empty.atDay(1), empty.atEndOfMonth(), zone), listOf(kim)).isEmpty())
    }

    @Test fun theSectionFillsThePaneLessTheTabsTheGapAndTheFoot() {
        // FULL at 1×: the 1097-dp page less the 66-dp tabs, the 16-dp gap and the 8-dp foot.
        assertEquals(1007, Density(1f).settingsFillPx(1097.dp))
        assertEquals(2014, Density(2f).settingsFillPx(1097.dp))
        // At 1.5 the parts round on their own, as the layout rounds them: 1646 − (8 + 8 + 84) − 24 − 12.
        val density = Density(1.5f)
        assertEquals(1510, density.settingsFillPx(with(density) { 1646.toDp() }))
        assertEquals("짧은 창은 0", 0, Density(1f).settingsFillPx(60.dp))
        assertEquals("한없는 창은 늘이지 않음", 0, Density(1f).settingsFillPx(Dp.Infinity))
    }

    @Test fun theFullWindowLeavesTheLowerBlocksTheirRoom() {
        // The measured 2.14.1 blocks at FULL 1×: 정산·세무's first pair 364 dp, 직원's manager with two people 371 dp, 16 apart.
        val section = Density(1f).settingsFillPx(1097.dp)
        val pairRoom = section - 364 - 16
        val usageRoom = section - 371 - 16
        // 업무사용비율 at the 220-dp ring is about 596 dp: it fits under the first pair, so the page reaches its foot unscrolled.
        assertTrue("$pairRoom", pairRoom in 596..640)
        assertTrue("$usageRoom", usageRoom in 560..640)
    }
}
