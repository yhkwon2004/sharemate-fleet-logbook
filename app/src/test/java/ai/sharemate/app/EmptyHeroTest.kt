package ai.sharemate.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * 운행기록's empty hero (2.15, docs/design/DESIGN_2_15.md §2.2): every row of the table ([logEmptyHero]) with its title, line,
 * tiles, actions and the spoken title with the month and the employee, demo mode, the nearest months with trips, and where
 * the tiles stand across. The page's landing month is MonthCloseUiTest's; the words' budgets are LogCopyTest's.
 */
class EmptyHeroTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 13)
    private val november = YearMonth.of(2026, 11)
    private val october = YearMonth.of(2026, 10)
    private fun at(year: Int, month: Int, day: Int, hour: Int) = LocalDateTime.of(year, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private val kim = Driver(id("kim"), "Sales", "Kim Jiwoo")
    private val kwon = Driver(id("kwon"), "영업", "권")

    /** A 10-km trip on [day] of [month], one odometer chain by [start]. */
    private fun trip(seed: String, month: Int, day: Int, driver: Driver = kim, purpose: TripPurpose = TripPurpose.BUSINESS, start: Double = 100.0,
        year: Int = 2026, demo: Boolean = false) =
        Trip(id(seed), at(year, month, day, 9), at(year, month, day, 10), start, start + 10.0, driverId = driver.id, purpose = purpose,
            updatedAt = at(year, month, day, 10), demo = demo)

    /** October: five trips of Kim's, 50.0 km, four of them business (80 %); September: two, closed; August: one. */
    private fun log(): TripLogData {
        val octoberTrips = (1..5).map { day -> trip("o$day", 10, day * 3, purpose = if (day == 5) TripPurpose.PERSONAL else TripPurpose.BUSINESS, start = 200.0 + day * 10) }
        val open = TripLogData(drivers = listOf(kim, kwon), trips = listOf(trip("a1", 8, 20, start = 100.0), trip("s1", 9, 3, start = 110.0),
            trip("s2", 9, 10, start = 120.0)) + octoberTrips)
        return closeMonth(open, YearMonth.of(2026, 9), at(2026, 10, 1, 9), zone)
    }

    @Test fun aLogWithoutAnyTripShowsTheThreeStepsOfAFirstTripAndTheWalkthrough() {
        val hero = logEmptyHero(0, november, TripLogData(), null, today, zone)
        assertEquals(EmptyHeroCase.FIRST, hero.case)
        assertEquals("아직 운행이 없어요", hero.title); assertEquals(hero.title, hero.spoken)
        assertEquals("출발하면 자동으로 기록해요", hero.line)
        assertEquals(listOf("D로 출발", "P로 주차", "목적 확인"), hero.tiles.map { it.title })
        assertEquals(listOf("기록이 시작돼요", "거리와 시간이 저장돼요", "홈에서 한 번 눌러요"), hero.tiles.map { it.line })
        assertEquals(listOf(1, 2, 3), hero.tiles.map { it.step })
        assertEquals(listOf(HeroGlyph.DRIVE, HeroGlyph.PARK, HeroGlyph.PURPOSE), hero.tiles.map { it.glyph })
        assertTrue("단계는 버튼이 아님", hero.tiles.all { it.action == null && it.clickLabel == null })
        // The same steps as 홈's idle card without a trip, in its words and heard as it is heard (2.15 §4).
        assertEquals("1단계, D로 출발, 기어를 P에서 D로 바꾸면 운행 기록이 자동으로 시작돼요", hero.tiles.first().spoken)
        assertEquals(HomeCopy.IDLE_STEPS.mapIndexed { index, step -> HomeCopy.spokenIdleStep(index + 1, step) }, hero.tiles.map { it.spoken })
        assertEquals(HomeCopy.IDLE_EMPTY, hero.title)
        assertEquals(HomeCopy.IDLE_DEMO, hero.actions.first().label)
        assertEquals(listOf(HeroButton("시연 체험 보기", HeroAction.TUTORIAL, primary = true), HeroButton("시연 모드 켜러 가기", HeroAction.DEMO_SETTING)), hero.actions)
        // A recording first trip is not a trip of the list yet.
        val recording = TripLogData(open = Trip(id("open"), at(2026, 11, 13, 9), null, 100.0, 103.0))
        assertEquals(EmptyHeroCase.FIRST, logEmptyHero(0, november, recording, null, today, zone).case)
    }

    @Test fun inDemoModeTheFirstHeroLeadsToTheDemoDriveOnHomeAndNeverToTheSwitch() {
        val demo = TripLogData(profile = FleetProfile(demoMode = true))
        val hero = logEmptyHero(0, november, demo, null, today, zone)
        assertEquals(EmptyHeroCase.FIRST, hero.case)
        assertEquals(listOf(HeroButton("홈에서 시연 출발", HeroAction.DEMO_DRIVE, primary = true)), hero.actions)
        assertFalse("시연 모드 스위치로 가는 길은 없음", hero.actions.any { it.action == HeroAction.DEMO_SETTING || it.action == HeroAction.TUTORIAL })
        // Real trips are not the demo data set's: in demo mode without demo trips the log is still empty.
        val real = log().copy(profile = FleetProfile(demoMode = true))
        assertEquals(EmptyHeroCase.FIRST, logEmptyHero(0, november, real, null, today, zone).case)
        val demoTrips = TripLogData(profile = FleetProfile(demoMode = true), trips = listOf(trip("d", 10, 2, demo = true)))
        assertEquals(EmptyHeroCase.MONTH, logEmptyHero(0, november, demoTrips, null, today, zone).case)
    }

    @Test fun anEmptyLogUnderAFilterSaysTheSameWithFilterClearingAdded() {
        val issues = logEmptyHero(1, november, TripLogData(drivers = listOf(kim)), null, today, zone)
        assertEquals(EmptyHeroCase.FIRST, issues.case)
        assertEquals(HeroAction.CLEAR_FILTER, issues.actions.last().action)
        assertEquals(EmptyHeroCase.FIRST, logEmptyHero(0, november, TripLogData(drivers = listOf(kim)), kim.id, today, zone).case)
    }

    @Test fun thisMonthWithoutTripsOffersTheNearestMonthsThatHaveSome() {
        val hero = logEmptyHero(0, november, log(), null, today, zone)
        assertEquals(EmptyHeroCase.MONTH, hero.case)
        assertEquals("11월 운행이 아직 없어요", hero.title); assertEquals(hero.title, hero.spoken)
        assertEquals("출발하면 자동으로 기록해요", hero.line)
        assertEquals(listOf("10월", "9월", "8월"), hero.tiles.map { it.title })
        assertEquals(listOf(october, YearMonth.of(2026, 9), YearMonth.of(2026, 8)), hero.tiles.map { it.month })
        assertTrue(hero.tiles.all { it.action == HeroAction.OPEN_MONTH && it.glyph == HeroGlyph.MONTH && it.clickLabel == "이 달 운행 보기" && it.step == null })
        val (oct, sep, aug) = hero.tiles
        assertEquals("5건 · 50.0 km · 업무 80%", oct.line)
        assertEquals("10월, 5건, 50.0 km, 업무 80%", oct.spoken)
        assertNull(oct.tag)
        assertEquals("마감된 달은 태그", "마감됨", sep.tag)
        assertEquals("9월, 2건, 20.0 km, 업무 100%, 마감됨", sep.spoken)
        assertEquals("1건 · 10.0 km · 업무 100%", aug.line)
        assertEquals(listOf(HeroButton("이전 달 보기", HeroAction.PREVIOUS_MONTH)), hero.actions)
    }

    @Test fun aPastMonthWithoutTripsSaysSoWithoutALine() {
        val july = logEmptyHero(0, YearMonth.of(2026, 7), log(), null, today, zone)
        assertEquals(EmptyHeroCase.MONTH, july.case)
        assertEquals("7월 운행 기록이 없어요", july.title)
        assertNull("지난 달에는 줄이 없음", july.line)
        assertEquals("가까운 달부터", listOf("8월", "9월", "10월"), july.tiles.map { it.title })
        val lastYear = TripLogData(drivers = listOf(kim), trips = listOf(trip("y", 11, 20, year = 2025)))
        val december = logEmptyHero(0, YearMonth.of(2025, 12), lastYear, null, today, zone)
        assertEquals("2025년 12월 운행 기록이 없어요", december.title)
        assertEquals("다른 해의 달은 연도를 붙임", listOf("2025년 11월"), december.tiles.map { it.title })
    }

    @Test fun aNarrowedMonthWithoutTripsOffersClearingAndTheEmployeesOwnMonths() {
        val kwons = logEmptyHero(0, november, log(), kwon.id, today, zone)
        assertEquals(EmptyHeroCase.MONTH, kwons.case)
        assertEquals("11월 운행이 아직 없어요", kwons.title)
        assertEquals("11월 영업 · 권 님의 운행이 아직 없어요", kwons.spoken)
        assertTrue("권의 운행이 없으면 타일도 없음", kwons.tiles.isEmpty())
        assertEquals(listOf(HeroAction.PREVIOUS_MONTH, HeroAction.CLEAR_FILTER), kwons.actions.map { it.action })
        assertFalse(kwons.actions.any { it.primary })
        val kims = logEmptyHero(0, november, log(), kim.id, today, zone)
        assertEquals("목록에 나올 운행의 달", listOf("10월", "9월", "8월"), kims.tiles.map { it.title })
        assertEquals("11월 Sales · Kim Jiwoo 님의 운행이 아직 없어요", kims.spoken)
        val issues = logEmptyHero(1, november, log(), null, today, zone)
        assertEquals("운행 없는 달의 점검 필요", EmptyHeroCase.MONTH, issues.case)
        assertEquals(HeroAction.CLEAR_FILTER, issues.actions.last().action)
    }

    @Test fun aMonthWithTripsButNoneOfTheEmployeesNamesThemAndClearsFirst() {
        val data = log().let { log -> log.copy(trips = log.trips.map { if (YearMonth.from(Instant.ofEpochMilli(it.startAt).atZone(zone)) == october) it.copy(driverId = kwon.id) else it }) }
        val hero = logEmptyHero(0, october, data, kim.id, today, zone)
        assertEquals(EmptyHeroCase.EMPLOYEE, hero.case)
        assertEquals("Kim Jiwoo 님의 운행이 없어요", hero.title)
        assertEquals("10월 Sales · Kim Jiwoo 님의 운행이 없어요", hero.spoken)
        assertNull(hero.line)
        assertTrue(hero.tiles.isEmpty())
        assertEquals(listOf(HeroButton("필터 해제", HeroAction.CLEAR_FILTER, primary = true), HeroButton("이전 달 보기", HeroAction.PREVIOUS_MONTH)), hero.actions)
        assertEquals("점검 필요에서도 같음", EmptyHeroCase.EMPLOYEE, logEmptyHero(1, october, data, kim.id, today, zone).case)
        val gone = logEmptyHero(0, october, data, id("gone"), today, zone)
        assertEquals("지워진 직원의 필터", "선택한 직원의 운행이 없어요", gone.title)
        assertEquals("10월 선택한 직원의 운행이 없어요", gone.spoken)
    }

    @Test fun nothingToCheckShowsTheMonthsThreeFactsAndLeadsToItsClose() {
        val hero = logEmptyHero(1, october, log(), null, today, zone)
        assertEquals(EmptyHeroCase.ISSUES, hero.case)
        assertEquals("점검할 운행이 없어요", hero.title)
        assertEquals("10월 점검할 운행이 없어요", hero.spoken)
        assertEquals("계기판 누적거리가 끊김 없이 이어져요", hero.line)
        assertEquals(listOf("5건", "50.0 km", "80%"), hero.tiles.map { it.title })
        assertEquals(listOf("운행", "총주행", "업무사용비율"), hero.tiles.map { it.line })
        assertEquals(listOf("운행 5건", "총주행 50.0 km", "업무사용비율 80%"), hero.tiles.map { it.spoken })
        assertEquals(listOf(HeroGlyph.COUNT, HeroGlyph.DISTANCE, HeroGlyph.RATIO), hero.tiles.map { it.glyph })
        assertTrue("사실은 버튼이 아님", hero.tiles.all { it.action == null && it.step == null })
        assertEquals(listOf(HeroButton("10월 마감하러 가기", HeroAction.CLOSE_MONTH, primary = true), HeroButton("전체 보기", HeroAction.SHOW_ALL)), hero.actions)
        // A closed month has no close to lead to; the facts are the employee's under the employee chip.
        val september = logEmptyHero(1, YearMonth.of(2026, 9), log(), null, today, zone)
        assertEquals(listOf(HeroButton("전체 보기", HeroAction.SHOW_ALL)), september.actions)
        assertEquals("2건", september.tiles.first().title)
        val kims = logEmptyHero(1, october, log(), kim.id, today, zone)
        assertEquals("10월 Sales · Kim Jiwoo 님의 점검할 운행이 없어요", kims.spoken)
        // A month with an unclassified trip is not ready to close (its 점검 필요 would not be empty, but the rule is the close's own).
        val unready = log().let { data -> data.copy(trips = data.trips.map { if (it.id == id("o1")) it.copy(purpose = TripPurpose.UNCLASSIFIED) else it }) }
        assertFalse(logEmptyHero(1, october, unready, null, today, zone).actions.any { it.action == HeroAction.CLOSE_MONTH })
    }

    @Test fun noMatchedPaymentExplainsTheCardsWayInThreeStepsTheFirstOfThemAButton() {
        val hero = logEmptyHero(2, november, log(), null, today, zone)
        assertEquals(EmptyHeroCase.MATCHED, hero.case)
        assertEquals("자동으로 들어간 카드 결제가 없어요", hero.title)
        assertEquals("11월 자동으로 들어간 카드 결제가 없어요", hero.spoken)
        assertNull(hero.line)
        assertEquals(listOf("카드 등록", "결제 알림", "자동 반영"), hero.tiles.map { it.title })
        assertEquals(listOf("끝 4자리만 적어요", "휴대폰 앱이 읽어요", "운행 비용에 들어가요"), hero.tiles.map { it.line })
        assertEquals(listOf(1, 2, 3), hero.tiles.map { it.step })
        assertEquals(listOf(HeroAction.CARD_SETTING, null, null), hero.tiles.map { it.action })
        assertEquals("카드 등록하러 가기", hero.tiles.first().clickLabel)
        assertEquals("2단계, 결제 알림, 휴대폰 앱이 읽어요", hero.tiles[1].spoken)
        assertEquals(listOf(HeroButton("카드 등록하러 가기", HeroAction.CARD_SETTING, primary = true), HeroButton("전체 보기", HeroAction.SHOW_ALL)), hero.actions)
        assertEquals("운행이 없어도 자동 반영의 빈 목록", EmptyHeroCase.MATCHED, logEmptyHero(2, november, TripLogData(), null, today, zone).case)
    }

    @Test fun everyHeroHasOnePrimaryAtMostAndItComesFirst() {
        val data = log()
        listOf(0, 1, 2).forEach { filter ->
            listOf(null, kim.id, kwon.id).forEach { employee ->
                listOf(YearMonth.of(2026, 7), october, november).forEach { month ->
                    val hero = logEmptyHero(filter, month, data, employee, today, zone)
                    assertTrue("$filter $employee $month", hero.actions.count { it.primary } <= 1)
                    assertTrue(hero.actions.drop(1).none { it.primary })
                    assertTrue("타일은 세 개까지", hero.tiles.size <= 3)
                    assertTrue("TalkBack은 제목보다 짧게 듣지 않음", hero.spoken.length >= hero.title.length)
                }
            }
        }
    }

    @Test fun theNearestMonthsWithTripsComeNearestFirstAndNeverFromTheFuture() {
        val trips = listOf(trip("a", 8, 1), trip("b", 8, 2), trip("c", 10, 1), trip("d", 6, 1))
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 8)), nearestTripMonths(trips, YearMonth.of(2026, 9), today, zone, limit = 2))
        assertEquals("같은 거리면 나중 달 먼저", listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 8), YearMonth.of(2026, 6)),
            nearestTripMonths(trips, YearMonth.of(2026, 9), today, zone))
        assertEquals("화면의 달은 빠짐", listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 6)), nearestTripMonths(trips, YearMonth.of(2026, 8), today, zone))
        val future = trip("f", 12, 1)
        assertEquals("시계를 되돌려도 오지 않은 달은 없음", listOf(YearMonth.of(2026, 10)), nearestTripMonths(trips + future, november, today, zone, limit = 1))
        val recording = Trip(id("open"), at(2026, 11, 2, 9), null, 100.0, 101.0)
        assertTrue("기록 중인 운행은 셈하지 않음", nearestTripMonths(listOf(recording), YearMonth.of(2026, 10), today, zone).isEmpty())
        assertTrue(nearestTripMonths(emptyList(), november, today, zone).isEmpty())
        val acrossTheYear = listOf(trip("y", 12, 5, year = 2025))
        assertEquals(listOf(YearMonth.of(2025, 12)), nearestTripMonths(acrossTheYear, YearMonth.of(2026, 2), today, zone))
    }

    @Test fun threeTilesStandAcrossFromNineHundredDpOfEffectivePage() {
        assertTrue("FULL 1112 dp", heroTilesAcross(1112.dp, 1f))
        assertTrue(heroTilesAcross(900.dp, 1f))
        assertFalse(heroTilesAcross(899.dp, 1f))
        assertFalse("1400 dp 창의 832 페이지", heroTilesAcross(832.dp, 1f))
        assertFalse("1.3배 글꼴의 1046 페이지(805 상당)", heroTilesAcross(1046.dp, 1.3f))
        assertTrue("작은 글꼴은 1배로 셈", heroTilesAcross(900.dp, .85f))
        assertEquals(208f, HERO_TILE_TALL.value, .01f)
        assertEquals(104f, HERO_TILE_COMPACT.value, .01f)
    }

    @Test fun aFollowingPageOnAnEarlierMonthSaysThisMonthHasNoTripYet() {
        assertEquals("11월은 아직 운행이 없어요", LogCopy.landingTag(november))
        assertEquals("1월은 아직 운행이 없어요", LogCopy.landingTag(YearMonth.of(2027, 1)))
    }
}
