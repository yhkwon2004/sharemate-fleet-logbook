package ai.sharemate.app

import androidx.compose.ui.unit.Dp
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
 * the tiles stand across. The corrections (§9.1–§9.3): the tiles' height from the room ([heroTileHeight], [heroChrome]) and
 * the FULL window's budget, the pictures of the first-trip steps, months as rows, people tiles, the split page's layout and the
 * saved month of a tap ([logHeroAcross], [logKeptSelection]), and 월간·Excel's hero ([reportEmptyHero]) with its words. The
 * page's landing month is MonthCloseUiTest's; the other words' budgets are LogCopyTest's.
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
        // 2.15 §9.1: each step carries its picture (D lit, P lit, the purposes); the steps stand across, never as rows.
        assertEquals(listOf(HeroOrnament.GEAR_DRIVE, HeroOrnament.GEAR_PARK, HeroOrnament.PURPOSES), hero.tiles.map { it.ornament })
        assertFalse(hero.rows)
        assertEquals(listOf("P", "R", "N", "D"), LogCopy.HERO_GEARS)
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
        // 2.15 §9.1: months never stretch across: they stand as the stacked rows, with no picture.
        assertTrue("달은 줄로", hero.rows)
        assertTrue(hero.tiles.all { it.ornament == null && it.driverId == null })
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
        // 2.15 §9.1: no hero without tiles. 권 has no trip anywhere, so the tiles are who drove in the nearest month with trips;
        // a tap filters by that person and opens that month.
        val kimTile = kwons.tiles.single()
        assertEquals("Kim Jiwoo", kimTile.title)
        assertEquals("10월 · 5건 · 50.0 km", kimTile.line)
        assertEquals("Sales · Kim Jiwoo, 10월, 5건, 50.0 km", kimTile.spoken)
        assertEquals(HeroGlyph.PERSON, kimTile.glyph)
        assertEquals(HeroAction.PICK_EMPLOYEE, kimTile.action)
        assertEquals(kim.id, kimTile.driverId); assertEquals(october, kimTile.month)
        assertEquals("이 직원 운행 보기", kimTile.clickLabel)
        assertFalse("사람은 타일로", kwons.rows)
        assertEquals(listOf(HeroAction.PREVIOUS_MONTH, HeroAction.CLEAR_FILTER), kwons.actions.map { it.action })
        assertFalse(kwons.actions.any { it.primary })
        val kims = logEmptyHero(0, november, log(), kim.id, today, zone)
        assertEquals("목록에 나올 운행의 달", listOf("10월", "9월", "8월"), kims.tiles.map { it.title })
        assertTrue(kims.rows)
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
        // 2.15 §9.1: no hero without tiles: Kim's nearest months with trips as rows (a tap opens the month, the filter kept).
        assertEquals(listOf("9월", "8월"), hero.tiles.map { it.title })
        assertTrue(hero.rows)
        assertTrue(hero.tiles.all { it.action == HeroAction.OPEN_MONTH && it.driverId == null })
        assertEquals("마감됨", hero.tiles.first().tag)
        assertEquals(listOf(HeroButton("필터 해제", HeroAction.CLEAR_FILTER, primary = true), HeroButton("이전 달 보기", HeroAction.PREVIOUS_MONTH)), hero.actions)
        assertEquals("점검 필요에서도 같음", EmptyHeroCase.EMPLOYEE, logEmptyHero(1, october, data, kim.id, today, zone).case)
        val gone = logEmptyHero(0, october, data, id("gone"), today, zone)
        assertEquals("지워진 직원의 필터", "선택한 직원의 운행이 없어요", gone.title)
        assertEquals("10월 선택한 직원의 운행이 없어요", gone.spoken)
        // An employee without any trip: who drove in the month on screen, a tap filtering by them (the month stays).
        val lee = Driver(id("lee"), "총무", "이")
        val newcomer = logEmptyHero(0, october, data.copy(drivers = data.drivers + lee), lee.id, today, zone)
        assertEquals(EmptyHeroCase.EMPLOYEE, newcomer.case)
        assertEquals("이 님의 운행이 없어요", newcomer.title)
        val person = newcomer.tiles.single()
        assertEquals("권", person.title); assertEquals("5건 · 50.0 km", person.line); assertEquals("영업 · 권, 5건, 50.0 km", person.spoken)
        assertEquals(HeroAction.PICK_EMPLOYEE, person.action); assertEquals(kwon.id, person.driverId); assertNull("화면의 달 그대로", person.month)
        assertFalse(newcomer.rows)
        assertEquals("지워진 직원도 같음", listOf(kwon.id), gone.tiles.map { it.driverId })
    }

    @Test fun peopleTilesNameWhoDroveMostFirstAndLeaveOutTripsWithoutAPerson() {
        val lee = Driver(id("lee"), "총무", "이")
        val october = (1..2).map { day -> trip("k$day", 10, day, start = 100.0 + day * 10) } +
            (3..5).map { day -> trip("w$day", 10, day, driver = kwon, start = 100.0 + day * 10) } + trip("n", 10, 9, start = 200.0).copy(driverId = null)
        val data = TripLogData(drivers = listOf(kim, kwon, lee), trips = october)
        val hero = logEmptyHero(0, YearMonth.of(2026, 10), data, lee.id, today, zone)
        assertEquals("운행이 많은 사람 먼저, 미지정은 빠짐", listOf(kwon.id, kim.id), hero.tiles.map { it.driverId })
        assertEquals(listOf("3건 · 30.0 km", "2건 · 20.0 km"), hero.tiles.map { it.line })
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
                    assertTrue("타일 없는 히어로는 없음 (§9.1): $filter $employee $month", hero.tiles.isNotEmpty())
                    assertTrue("그림은 첫 운행 단계에만", hero.case == EmptyHeroCase.FIRST || hero.tiles.all { it.ornament == null })
                    assertTrue("TalkBack은 제목보다 짧게 듣지 않음", hero.spoken.orEmpty().length >= hero.title.orEmpty().length)
                    assertTrue("운행기록의 히어로는 제목이 있음", hero.title != null)
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

    /**
     * 2.15 §9.1: across, the tiles take the hero's bounded height less its own title, line, gaps and actions and less a 200-dp
     * band for the city, held between 208 and 360 dp; unbounded (월간·Excel's scroll) they keep 208.
     */
    @Test fun theTilesAcrossGrowWithTheRoomBetween208And360AndLeaveTheCityItsBand() {
        val first = heroChrome(40.dp, 30.dp, tiles = true, actions = true)
        assertEquals("headlineMedium 40 + 8 + bodyLarge 30 + 24 + 24 + 56", 182f, first.value, .01f)
        assertEquals("줄이 없으면(MATCHED) 40 + 24 + 24 + 56", 144f, heroChrome(40.dp, null, tiles = true, actions = true).value, .01f)
        assertEquals("제목 없는 월간 히어로: 24 + 56", 80f, heroChrome(null, null, tiles = true, actions = true).value, .01f)
        assertEquals("버튼이 없으면 그 줄과 간격도 없음", 102f, heroChrome(40.dp, 30.dp, tiles = true, actions = false).value, .01f)
        assertEquals(0f, heroChrome(null, null, tiles = true, actions = false).value, .01f)
        // The band: what is left under the chrome and 200 dp of city.
        assertEquals("700 − 182 − 200", 318f, heroTileHeight(700.dp, first).value, .01f)
        assertEquals("가장 낮아도 208", 208f, heroTileHeight(500.dp, first).value, .01f)
        assertEquals("가장 높아도 360", 360f, heroTileHeight(2_000.dp, first).value, .01f)
        assertEquals(HERO_TILE_TALL, heroTileHeight(first + HERO_CITY_BAND + HERO_TILE_TALL, first))
        assertEquals("높이가 정해지지 않으면 208", HERO_TILE_TALL, heroTileHeight(Dp.Infinity, first))
        assertEquals(HERO_TILE_TALL, heroTileHeight(Dp.Unspecified, first))
        // The larger square and title from 300 dp up.
        assertFalse(heroTileLarge(299.dp)); assertTrue(heroTileLarge(300.dp)); assertTrue(heroTileLarge(HERO_TILE_MAX))
        assertFalse(heroTileLarge(HERO_TILE_TALL))
    }

    /**
     * The FULL window at 1×: the 1097-dp page less 운행기록's 66-dp header row (the stepper: two 56-dp arrows in 5 dp of padding)
     * and the 16-dp gap gives the hero 1015 dp, 971 under the tag row (32 dp and its 12-dp gap). The tallest hero, FIRST, takes
     * 182 + 360 = 542 dp there, so it never scrolls and leaves the city more than its band.
     */
    @Test fun theHeroNeverScrollsInTheFullWindow() {
        val header = 66.dp
        val region = HOME_PAGE_FULL - header - 16.dp
        assertEquals(1015f, region.value, .01f)
        val tagged = region - 32.dp - 12.dp
        assertEquals(971f, tagged.value, .01f)
        listOf(heroChrome(40.dp, 30.dp, true, true), heroChrome(40.dp, null, true, true)).forEach { chrome ->
            listOf(region, tagged).forEach { room ->
                val tiles = heroTileHeight(room, chrome)
                assertEquals("FULL에서는 가장 높은 타일", HERO_TILE_MAX, tiles)
                assertTrue("스크롤 없음: $chrome + $tiles ≤ $room", chrome + tiles <= room)
                assertTrue("도시 띠가 남음", room - chrome - tiles >= HERO_CITY_BAND)
                assertTrue(heroTileLarge(tiles))
            }
        }
        // Three month rows (never stretched) and their chrome fit as well: 182 + 3 × 104 + 2 × 16 = 526.
        assertTrue(heroChrome(40.dp, 30.dp, true, true) + HERO_TILE_COMPACT * 3 + 32.dp <= tagged)
    }

    /** 2.15 §9.2 (C3): the hero takes both panes only while no trip is shown; under a pinned trip it keeps the list column. */
    @Test fun anEmptyListTakesBothPanesOnlyWhileNoTripIsShown() {
        assertTrue("빈 목록, 상세 없음: 두 칸 모두", logHeroAcross(twoPane = true, empty = true, showing = false))
        assertFalse("고치던 운행이 상세에 있으면 목록 칸만", logHeroAcross(twoPane = true, empty = true, showing = true))
        assertFalse("목록이 있으면 히어로 없음", logHeroAcross(twoPane = true, empty = false, showing = true))
        assertFalse("좁은 화면은 목록 자리", logHeroAcross(twoPane = false, empty = true, showing = false))
        // With an empty list nothing follows the list: only a pinned trip (an edit in the pane) is shown.
        val pinned = trip("pinned", 10, 9)
        val top = logTopId(1, emptyList(), emptyList(), emptyList())
        assertNull(top)
        assertNull(logSelection(null, setOf(pinned.id), top, twoPane = true))
        assertEquals(pinned.id, logSelection(pinned.id, setOf(pinned.id), top, twoPane = true))
        assertFalse(logHeroAcross(true, true, showing = logSelection(pinned.id, setOf(pinned.id), top, true) != null))
        assertTrue(logHeroAcross(true, true, showing = logSelection(null, setOf(pinned.id), top, true) != null))
    }

    /** 2.15 §9.2 (C4): the month a tap belongs to is saved with it, so a following page whose month moved drops it. */
    @Test fun aFollowingPageDropsATapFromTheMonthItLeft() {
        val tapped = id("x")
        assertEquals("같은 달이면 그대로", tapped, logKeptSelection(tapped, "2026-10", october, following = true))
        assertNull("따라가던 달이 11월로 옮겨 가면 10월의 운행은 내려놓음", logKeptSelection(tapped, "2026-10", november, following = true))
        assertEquals("운전자가 고른 달이면 그대로", tapped, logKeptSelection(tapped, "2026-10", november, following = false))
        assertNull(logKeptSelection(null, "2026-11", november, following = true))
    }

    /** 2.15 §9.3: 월간·Excel without a trip in the period shows a hero without a title in place of its two cards. */
    @Test fun aReportPeriodWithoutTripsShowsAHeroWithoutATitle() {
        val month = { m: YearMonth -> ReportPeriod(m, yearly = false) }
        val empty = reportEmptyHero(TripLogData(), month(november), today, zone)!!
        assertEquals(EmptyHeroCase.FIRST, empty.case)
        assertNull("제목 없음: 월 마감 카드가 이미 말함", empty.title); assertNull(empty.spoken); assertNull(empty.line)
        assertEquals(listOf("목적 확인", "월 마감", "Excel 저장"), empty.tiles.map { it.title })
        assertEquals(listOf("운행마다 한 번 눌러요", "확인이 끝나면 잠가요", "운행기록부 양식으로 받아요"), empty.tiles.map { it.line })
        assertEquals(listOf(1, 2, 3), empty.tiles.map { it.step })
        assertEquals(listOf(HeroGlyph.PURPOSE, HeroGlyph.CLOSE, HeroGlyph.EXCEL), empty.tiles.map { it.glyph })
        assertEquals("2단계, 월 마감, 확인이 끝나면 잠가요", empty.tiles[1].spoken)
        assertTrue("단계는 버튼도 그림도 아님", empty.tiles.all { it.action == null && it.ornament == null })
        assertFalse(empty.rows)
        assertEquals(listOf(HeroButton("시연 체험 보기", HeroAction.TUTORIAL, primary = true)), empty.actions)
        val demo = reportEmptyHero(TripLogData(profile = FleetProfile(demoMode = true)), month(november), today, zone)!!
        assertEquals("시연 모드는 홈의 시연 출발", listOf(HeroButton("홈에서 시연 출발", HeroAction.DEMO_DRIVE, primary = true)), demo.actions)
        assertEquals("연간도 같음", EmptyHeroCase.FIRST, reportEmptyHero(TripLogData(), ReportPeriod(november, yearly = true), today, zone)?.case)
    }

    @Test fun aReportPeriodBesideMonthsWithTripsOffersThemAsRows() {
        val hero = reportEmptyHero(log(), ReportPeriod(november, yearly = false), today, zone)!!
        assertEquals(EmptyHeroCase.MONTH, hero.case)
        assertNull(hero.title)
        assertTrue("달은 줄로", hero.rows)
        assertEquals(listOf("10월", "9월", "8월"), hero.tiles.map { it.title })
        assertEquals(listOf(october, YearMonth.of(2026, 9), YearMonth.of(2026, 8)), hero.tiles.map { it.month })
        assertEquals("5건 · 50.0 km · 업무 80%", hero.tiles.first().line)
        assertEquals(listOf(null, "마감됨", null), hero.tiles.map { it.tag })
        assertTrue(hero.tiles.all { it.action == HeroAction.OPEN_MONTH && it.clickLabel == "이 달 보기" })
        assertEquals(listOf(HeroButton("이전 달 보기", HeroAction.PREVIOUS_MONTH)), hero.actions)
        assertNull("운행이 있는 기간은 두 카드 그대로", reportEmptyHero(log(), ReportPeriod(october, yearly = false), today, zone))
        assertNull(reportEmptyHero(log(), ReportPeriod(november, yearly = true), today, zone))
        // 연간 without a trip in the year: the nearest months of other years, and the step back by a year.
        val lastYear = reportEmptyHero(log(), ReportPeriod(YearMonth.of(2025, 11), yearly = true), today, zone)!!
        assertEquals(listOf("8월", "9월", "10월"), lastYear.tiles.map { it.title })
        assertEquals(listOf(HeroButton("이전 해 보기", HeroAction.PREVIOUS_MONTH)), lastYear.actions)
        // Real trips are not the demo data set's.
        assertEquals(EmptyHeroCase.FIRST, reportEmptyHero(log().copy(profile = FleetProfile(demoMode = true)), ReportPeriod(october, false), today, zone)?.case)
    }

    /** The words the corrections add, each in its place at the normal font (CarCopyRules). */
    @Test fun theCorrectionsWordsKeepToOneLine() {
        ReportCopy.HERO_STEPS.forEach { (title, line) ->
            assertOneLine(title, 10.0)
            assertOneLine(line, SETUP_TILE_LINE_UNITS)
        }
        listOf(ReportCopy.HERO_OPEN_MONTH, ReportCopy.HERO_PREVIOUS_YEAR, LogCopy.HERO_PICK_EMPLOYEE).forEach { assertOneLine(it, 14.0) }
        val most = LogbookTotals(1_234.5, 0.0, 1_234.5, 0.0, 0.0, 120, 0)
        listOf(LogCopy.personTileLine(most), LogCopy.personTileLine(most, monthLabel(YearMonth.of(2025, 12), today))).forEach { assertOneLine(it, SETUP_TILE_LINE_UNITS) }
        assertEquals("2025년 12월 · 120건 · 1,234.5 km", LogCopy.personTileLine(most, "2025년 12월"))
        LogCopy.HERO_GEARS.forEach { assertOneLine(it, 1.0) }
    }

    @Test fun aFollowingPageOnAnEarlierMonthSaysThisMonthHasNoTripYet() {
        assertEquals("11월은 아직 운행이 없어요", LogCopy.landingTag(november))
        assertEquals("1월은 아직 운행이 없어요", LogCopy.landingTag(YearMonth.of(2027, 1)))
    }
}
