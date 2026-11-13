package ai.sharemate.app

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * 2.15 §7 and §9.5: what 설정 › 직원's 직원별 운행 lists (every employee with their share of the month's distance, then 사용자
 * 미지정) and the facts that fill its height, the tiles before the first employee, 비용 참고's bars in 정산·세무, and how much a
 * 설정 section's blocks fill before the page would scroll.
 */
class SettingsFillTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(month: Int, day: Int, hour: Int) = LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val kim = Driver(id("kim"), "Sales", "Kim Jiwoo")
    private val lee = Driver(id("lee"), "Finance", "Lee Minho")
    private val park = Driver(id("park"), "HR", "Park Sora")
    private fun trip(seed: String, day: Int, km: Double, driver: String?, purpose: TripPurpose = TripPurpose.BUSINESS, open: Boolean = false) =
        Trip(id(seed), at(10, day, 9), if (open) null else at(10, day, 10), 100.0, 100.0 + km, driverId = driver, purpose = purpose, updatedAt = at(10, day, 10))

    // ───────────────────────── 직원별 운행 ─────────────────────────

    @Test fun everyEmployeeHasARowLongestFirstThenTheUnassigned() {
        val trips = listOf(trip("a", 3, 20.0, kim.id), trip("b", 4, 10.0, kim.id, TripPurpose.PERSONAL), trip("c", 5, 10.0, lee.id), trip("d", 6, 10.0, null))
        val shares = employeeShares(trips, listOf(park, kim, lee))
        // The longest first, Park Sora (no trip) after the people who drove, 사용자 미지정 last.
        assertEquals(listOf(kim, lee, park, null), shares.map { it.usage.driver })
        assertEquals(listOf(.6, .2, 0.0, .2), shares.map { it.share })
        assertEquals(1.0, shares.sumOf { it.share }, 1e-9)
        assertEquals(listOf(60, 20, 0, 20), shares.map { it.percent })
        assertEquals(listOf(false, false, true, false), shares.map { it.none })
        // The row keeps 월간's figures: all of the person's trips, business distance apart.
        assertEquals(2, shares.first().usage.totals.trips)
        assertEquals(20.0, shares.first().usage.totals.businessKm, 0.0)
        assertEquals(30.0, shares.first().usage.totals.totalKm, 0.0)
        // An employee without trips reads zeros.
        val none = shares[2].usage.totals
        assertEquals(listOf(0, 0.0, 0.0), listOf(none.trips, none.totalKm, none.businessKm))
    }

    @Test fun unassignedTripsAreOneRowOfTheirOwnAtTheEnd() {
        // A trip without a person and one whose person was deleted are both 사용자 미지정, one row, after the employees even where
        // it is the longest (30 of 50 km).
        val trips = listOf(trip("a", 3, 20.0, kim.id), trip("b", 4, 5.0, null), trip("c", 5, 25.0, id("gone")))
        val shares = employeeShares(trips, listOf(kim))
        assertEquals(listOf(kim, null), shares.map { it.usage.driver })
        assertEquals(2, shares.last().usage.totals.trips)
        assertEquals(.6, shares.last().share, 1e-9)
        assertEquals(1.0, shares.sumOf { it.share }, 1e-9)
        // Without such a trip there is no 사용자 미지정 row.
        assertTrue(employeeShares(listOf(trip("a", 3, 10.0, kim.id)), listOf(kim, lee)).none { it.usage.driver == null })
    }

    @Test fun beforeTheFirstTripEveryEmployeeIsARowOfZeros() {
        val shares = employeeShares(emptyList(), listOf(kim, lee))
        assertEquals("등록 순서", listOf(kim, lee), shares.map { it.usage.driver })
        assertTrue(shares.all { it.none && it.share == 0.0 && it.percent == 0 && it.usage.totals.totalKm == 0.0 })
        // A trip still recording is not a finished trip: 월간's card leaves it out too.
        assertTrue(employeeShares(listOf(trip("o", 3, 12.0, kim.id, open = true)), listOf(kim)).single().none)
        assertTrue("직원이 없으면 행도 없음", employeeShares(emptyList(), emptyList()).isEmpty())
    }

    @Test fun tripsWithoutDistanceShareNothing() {
        val shares = employeeShares(listOf(trip("a", 3, 0.0, kim.id), trip("b", 4, 0.0, null)), listOf(kim))
        assertEquals(listOf(kim, null), shares.map { it.usage.driver })
        assertTrue(shares.all { it.share == 0.0 && it.percent == 0 })
        assertFalse("운행은 있음", shares.first().none)
    }

    @Test fun theFactsOfTheMonthAreItsCountDistanceAndRatio() {
        val facts = monthFacts(listOf(trip("a", 3, 20.0, kim.id), trip("b", 4, 10.0, lee.id, TripPurpose.PERSONAL), trip("o", 5, 5.0, kim.id, open = true)))
        assertEquals(listOf("2건", "30.0 km", "67%"), facts.map { it.figure })
        assertEquals(listOf("운행", "총주행", "업무사용비율"), facts.map { it.label })
        assertEquals(listOf(ShareFactKind.TRIPS, ShareFactKind.DISTANCE, ShareFactKind.RATIO), facts.map { it.kind })
        assertEquals(listOf("운행 2건", "총주행 30.0 km", "업무사용비율 67%"), facts.map { it.spoken })
        // Before the first trip: 0건, 0.0 km and –, the dash heard as 없음.
        val zero = monthFacts(emptyList())
        assertEquals(listOf("0건", "0.0 km", "–"), zero.map { it.figure })
        assertEquals("업무사용비율 없음", zero.last().spoken)
    }

    @Test fun theFactsTakeTheLookTheHeightGivesThem() {
        val looks = listOf(0.dp, 103.dp, 104.dp, 207.dp, 208.dp, 299.dp, 300.dp, 500.dp).map { shareFactsLook(it) }
        assertEquals(listOf(ShareFactsLook.NONE, ShareFactsLook.NONE, ShareFactsLook.SHORT, ShareFactsLook.SHORT, ShareFactsLook.TALL,
            ShareFactsLook.TALL, ShareFactsLook.BIG, ShareFactsLook.BIG), looks)
        assertNull(shareFactsHeight(103.dp))
        assertEquals("남은 만큼", 136.dp, shareFactsHeight(136.dp))
        assertEquals(304.dp, shareFactsHeight(304.dp))
        assertEquals("360에서 멈춤", 360.dp, shareFactsHeight(399.dp))
    }

    /**
     * The heights the lead checks, 설정 › 직원 in the FULL window at 1× (a 1007-dp section): the manager's card is 368 dp while
     * its editor is the taller half (none or one person), 371 with two (measured) and about 447 with three (a 76-dp row more).
     * 직원별 운행 at its own height is 116 + 92 per row (24 + 32 + 12 + the rows, 16, the 20-dp ⓘ row, 24); stretched, its facts
     * get the room less that and the 16 dp over them.
     */
    @Test fun theFullWindowFillsTheDriversPageInEachState() {
        val section = Density(1f).settingsFillPx(1097.dp)
        fun room(manager: Int) = (section - manager - 16).dp
        fun free(manager: Int, rows: Int) = room(manager) - (116 + 92 * rows).dp - 16.dp
        // No employee: the tiles stand on the scene, 360 dp tall, the larger look.
        assertEquals(623.dp, room(368))
        assertEquals(360.dp, heroTileHeight(room(368), 30.dp + 12.dp))
        // One employee: a 360-dp row of facts in the larger look; two: 304 dp, still the larger look; three: 136, the short look.
        assertEquals(399.dp, free(368, 1))
        assertEquals(ShareFactsLook.BIG, shareFactsLook(free(368, 1)))
        assertEquals(304.dp, free(371, 2))
        assertEquals(ShareFactsLook.BIG, shareFactsLook(free(371, 2)))
        assertEquals(136.dp, free(447, 3))
        assertEquals(ShareFactsLook.SHORT, shareFactsLook(free(447, 3)))
        // Four employees outgrow the section by 16 dp: no facts, and the section scrolls as before.
        assertTrue(free(523, 4) < 0.dp)
    }

    // ───────────────────────── tiles before the first employee ─────────────────────────

    @Test fun theIntroTilesGrowWithTheRoomAndStandAcrossAWidePage() {
        // The empty hero's own rule (§9.1), the heading's 30-dp line and its 12-dp gap as the chrome.
        assertEquals("최대 360", 360.dp, heroTileHeight(623.dp, 42.dp))
        assertTrue("큰 모양", heroTileLarge(heroTileHeight(623.dp, 42.dp)))
        assertEquals("방에서 머리와 도시를 뺀 만큼", 258.dp, heroTileHeight(500.dp, 42.dp))
        assertEquals("최소 208", 208.dp, heroTileHeight(300.dp, 42.dp))
        assertEquals("스크롤 안에서는 208", 208.dp, heroTileHeight(0.dp, 42.dp))
        assertTrue(heroTilesAcross(1112.dp, 1f))
        assertTrue("작은 글꼴은 1배로", heroTilesAcross(900.dp, .9f))
        assertFalse("한 단 설정은 720 dp", heroTilesAcross(720.dp, 1f))
        assertFalse("1.3배에서 1112 dp는 855 dp", heroTilesAcross(1112.dp, 1.3f))
    }

    @Test fun theIntroTilesAreHeroTilesThatLeadNowhere() {
        val tiles = driverIntroTiles()
        assertEquals(listOf("④ 사용자 칸", "출발 전 확인", "유류비 정산"), tiles.map { it.title })
        assertEquals(listOf("운행기록부에 이름이 들어가요", "하루 한 번 운전자를 물어요", "직원별로 계산해요"), tiles.map { it.line })
        assertEquals(listOf(HeroGlyph.PERSON, HeroGlyph.PURPOSE, HeroGlyph.CARD), tiles.map { it.glyph })
        assertEquals(SettingsCopy.SPOKEN_DRIVERS_INTRO_TILES, tiles.map { it.spoken })
        // Not buttons, no step disc, no tag, no picture: one stop each that explains.
        assertTrue(tiles.all { it.action == null && it.clickLabel == null && it.step == null && it.tag == null && it.ornament == null })
        assertTrue("④는 소리로 읽지 않음", tiles.none { '④' in it.spoken })
    }

    // ───────────────────────── 비용 참고 ─────────────────────────

    @Test fun theComparisonIsTwoBarsTheLargerAtFullWidth() {
        val bars = taxBars(32_000_000, .8)
        assertEquals(listOf(TaxBarKind.WITHOUT_LOG, TaxBarKind.WITH_LOG), bars.map { it.kind })
        assertEquals(listOf("기록부 없을 때", "업무 비율 적용액"), bars.map { it.label })
        assertEquals(listOf(15_000_000L, 25_600_000L), bars.map { it.won })
        assertEquals(15f / 25.6f, bars[0].fraction, 1e-6f)
        assertEquals(1f, bars[1].fraction, 0f)
        // A low ratio puts the no-logbook figure ahead.
        assertEquals(listOf(1f, .64f), taxBars(32_000_000, .3).map { it.fraction })
        assertEquals("같으면 둘 다 끝까지", listOf(1f, 1f), taxBars(10_000_000, 1.0).map { it.fraction })
        assertEquals("비용 0이면 길이 0", listOf(0f, 0f), taxBars(0, .8).map { it.fraction })
        assertEquals("기록부 없을 때 15,000,000원, 업무 비율 적용액 25,600,000원", taxBarsSpoken(bars))
    }

    @Test fun beforeTheComparisonOneBarStandsForTheLimit() {
        listOf(taxBars(null, .8), taxBars(null, null), taxBars(32_000_000, null)).forEach { bars ->
            assertEquals(listOf(TaxBar("기록부 없을 때 인정 한도", 15_000_000L, 1f, TaxBarKind.LIMIT)), bars)
        }
        assertEquals("기록부 없을 때 인정 한도 15,000,000원", taxBarsSpoken(taxBars(null, null)))
    }

    // ───────────────────────── the page's foot ─────────────────────────

    @Test fun theCardsShowTheMonthTheLogLandsOn() {
        // 11월 has no finished trip yet, so 직원별 운행 and 유류비 정산 show 10월, as 운행기록 opens there (landingMonth, §2.1).
        val trips = listOf(trip("a", 3, 20.0, kim.id), trip("b", 4, 10.0, lee.id))
        val today = LocalDate.of(2026, 11, 13)
        val month = landingMonth(trips, today, zone)
        assertEquals(YearMonth.of(2026, 10), month)
        assertEquals("10월", monthLabel(month, today))
        assertEquals(2, employeeShares(tripsIn(trips, month.atDay(1), month.atEndOfMonth(), zone), listOf(kim, lee)).count { !it.none })
        // Without a trip anywhere the month is this one: the rows are the employees' zeros under 아직 운행 없음.
        val empty = landingMonth(emptyList(), today, zone)
        assertEquals(YearMonth.of(2026, 11), empty)
        assertTrue(tripsIn(emptyList(), empty.atDay(1), empty.atEndOfMonth(), zone).isEmpty())
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
        // 비용 참고 with its bars stays under that room: 24 + 32 + 12 + the money rows (185) + 24 + two bars (2 × 59 + 16) + 24 + the
        // 56-dp ⓘ row + 24 with a cost, and 24 + 32 + 12 + the line (23) + 24 + the limit bar (59) + 16 + 56 + 24 + 56 + 24 without.
        assertTrue(24 + 32 + 12 + 185 + 24 + (2 * 59 + 16) + 24 + 56 + 24 <= pairRoom)
        assertTrue(24 + 32 + 12 + 23 + 24 + 59 + 16 + 56 + 24 + 56 + 24 <= pairRoom)
    }
}
