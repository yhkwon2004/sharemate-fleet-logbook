package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 설정's words on the car (2.12): the card registry line, and every line of [SettingsCopy] and of the helpers beside its cards
 * held to the one-line rule (CarCopyRules): one fact, no final period, inside the budget of the place it is drawn. Titles stay
 * at most 8 syllables; the long forms TalkBack reads and the ⓘ rows' unfolded lines may run long, but end without a period.
 */
class SettingsCopyTest {
    /**
     * Two lines beside a switch and the assistant's ⓘ line run over their unit budgets: 21.5 and 20.6 of 20, 27.35 of 26. The
     * spec measured them in Asta Sans (DESIGN_2_12_SPEC §9) at 292 and 282 of the 306 dp a switch leaves on the narrowest
     * two-up page, and at 373 of the 466 dp after an ⓘ mark: the estimate counts a space and a middle dot wider than the font
     * draws them. These lines are held to that measured fit, capped at their present width so that they cannot grow.
     */
    private val measuredFits = mapOf(SettingsCopy.AUTOPILOT_LINE to 21.5, SettingsCopy.DEMO_LINE to 20.6, ASSISTANT_NOTE to 27.35)

    private fun assertFits(line: String, budget: Double) = assertOneLine(line, measuredFits[line] ?: budget)

    private fun syllables(text: String) = text.count { it in '가'..'힣' }

    @Test fun theCardLineSaysWhichPaymentsTheCarTakes() {
        assertEquals("카드 미등록 · 주유·충전·통행료·주차 결제만 받아요", cardRegistryLine(emptyList()))
        assertEquals("등록한 카드 1장의 결제만 받아요", cardRegistryLine(listOf("1234")))
        assertEquals("등록한 카드 2장의 결제만 받아요", cardRegistryLine(listOf("1234", "5678")))
    }

    @Test fun linesBesideASwitchFitBesideIt() {
        listOf(SettingsCopy.ADDRESS_LINE, SettingsCopy.AUTOPILOT_LINE, SettingsCopy.PHONE_SYNC_LINE, SettingsCopy.DEMO_LINE, SettingsCopy.CONFIRM_LINE)
            .forEach { assertFits(it, SWITCH_LINE_UNITS) }
    }

    @Test fun linesAcrossACardFitTheHalfCard() {
        // The longest the helpers write: a seven-digit odometer, the most cards the field takes (10). A person's line in the 직원
        // list is data, which the spec lets ellipsise (TalkBack reads it whole), so it has no budget here.
        val odometer = listOf(careOdometerLine(null, false), careOdometerLine(1_234_567.0, true), careOdometerLine(1_234_567.0, false))
        val cards = listOf(cardRegistryLine(emptyList()), cardRegistryLine(List(10) { "1234" }))
        (listOf(SettingsCopy.PROFILE_LINE, SettingsCopy.CARE_LINE, SettingsCopy.DRIVERS_EMPTY, SettingsCopy.DRIVERS_FULL, SettingsCopy.EDIT_NOTE,
            SettingsCopy.KEEP_DRIVER, SettingsCopy.COST_HINT, SettingsCopy.CARDS_ERROR, SettingsCopy.ASSISTANT_LINE) + odometer + cards)
            .forEach { assertFits(it, CARD_LINE_UNITS) }
    }

    @Test fun infoLinesFitAfterTheirMark() {
        listOf(SettingsCopy.STORAGE_INFO, SettingsCopy.SIGNALS_INFO, ASSISTANT_NOTE).forEach { assertFits(it, INFO_LINE_UNITS) }
    }

    @Test fun titlesAndStatesStayShort() {
        val titles = SettingsCopy.SECTIONS + listOf(SettingsCopy.PROFILE_TITLE, SettingsCopy.CARE_TITLE, SettingsCopy.driversTitle(50),
            SettingsCopy.CONFIRM_TITLE, SettingsCopy.COST_TITLE, SettingsCopy.SCENE_TITLE, SettingsCopy.PRIVACY_TITLE, SettingsCopy.ADDRESS_TITLE,
            SettingsCopy.AUTOPILOT_TITLE, SettingsCopy.PHONE_SYNC_TITLE, SettingsCopy.DEMO_TITLE, SettingsCopy.AUTOFILL_TITLE, ASSISTANT_TITLE)
        titles.forEach { assertTrue("$it: 8음절 이하", syllables(it) <= 8) }
        // Tags, the link and button labels share a row with a title or another control.
        listOf(SettingsCopy.DRIVERS_CAP, SettingsCopy.ADD_DRIVER, SettingsCopy.NOW_DRIVING, SettingsCopy.TUTORIAL_LINK, SettingsCopy.MAKE_DRIVER, SettingsCopy.DELETE_DRIVER,
            SettingsCopy.PROFILE_SAVE, SettingsCopy.PROFILE_SAVED, SettingsCopy.COST_SAVE, SettingsCopy.COST_SAVED)
            .forEach { assertTrue("$it: 8음절 이하", syllables(it) <= 8) }
        // The 추가 pill in the 직원 title row says what it adds for TalkBack, and says the label too.
        assertTrue(SettingsCopy.SPOKEN_ADD_DRIVER.endsWith(SettingsCopy.ADD_DRIVER))
    }

    @Test fun nothingEndsInAPeriodAndTheLongFormsSayMore() {
        val spoken = listOf(SettingsCopy.SPOKEN_CARE, SettingsCopy.SPOKEN_CARE_SERVICE, SettingsCopy.SPOKEN_CONFIRM, SettingsCopy.SPOKEN_KEEP_DRIVER,
            SettingsCopy.SPOKEN_ADDRESS, SettingsCopy.SPOKEN_AUTOPILOT, SettingsCopy.SPOKEN_PHONE_SYNC, SettingsCopy.SPOKEN_DEMO, SettingsCopy.SPOKEN_CARDS,
            SettingsCopy.makeDriverSpoken("Kim Jiwoo"))
        val unfolded = SettingsCopy.STORAGE_MORE + SettingsCopy.SIGNALS_MORE + SettingsCopy.ASSISTANT_MORE
        val messages = listOf(SettingsCopy.PROFILE_DONE, SettingsCopy.COST_DONE)
        (spoken + unfolded + messages).forEach { line ->
            assertFalse("한 줄: $line", line.contains('\n'))
            assertFalse("마침표 없음: $line", line.trimEnd().endsWith('.'))
        }
        // Each long form says at least what its line says, and more.
        listOf(SettingsCopy.ADDRESS_LINE to SettingsCopy.SPOKEN_ADDRESS, SettingsCopy.AUTOPILOT_LINE to SettingsCopy.SPOKEN_AUTOPILOT,
            SettingsCopy.PHONE_SYNC_LINE to SettingsCopy.SPOKEN_PHONE_SYNC, SettingsCopy.DEMO_LINE to SettingsCopy.SPOKEN_DEMO,
            SettingsCopy.CONFIRM_LINE to SettingsCopy.SPOKEN_CONFIRM, SettingsCopy.CARE_LINE to SettingsCopy.SPOKEN_CARE,
            SettingsCopy.KEEP_DRIVER to SettingsCopy.SPOKEN_KEEP_DRIVER).forEach { (line, long) ->
            assertTrue("$line → $long", long.length > line.length)
        }
        assertTrue("시연 모드는 보이는 줄에 덧붙임", SettingsCopy.SPOKEN_DEMO.startsWith(SettingsCopy.DEMO_LINE))
        assertTrue("삭제할 수 없는 이유도 그대로", SettingsCopy.SPOKEN_KEEP_DRIVER.startsWith(SettingsCopy.KEEP_DRIVER))
        assertEquals("Kim Jiwoo 님을 지금 운전하는 직원으로 선택", SettingsCopy.makeDriverSpoken("Kim Jiwoo"))
        assertEquals("0명도 그대로", "직원 0명", SettingsCopy.driversTitle(0))
    }

    /**
     * 2.15 §7: 직원별 운행 under the manager writes its rows as 월간's card does and gives each one stop; 업무사용비율 in 정산·세무
     * carries its year as a tag.
     */
    @Test fun theNewCardsOfTwoFifteenKeepTheRules() {
        assertEquals("9건 · 업무 43.0 km", SettingsCopy.usageLine(9, 43.0))
        // The longest a row's line gets: a full month of short trips over a long distance, across the card beside the avatar.
        assertFits(SettingsCopy.usageLine(999, 99_999.9), CARD_LINE_UNITS)
        assertEquals("2026년", SettingsCopy.ratioYearTag(2026))
        assertTrue(syllables(SettingsCopy.ratioYearTag(2026)) <= 8)
        val line = SettingsCopy.usageLine(9, 43.0)
        val spoken = SettingsCopy.usageShareSpoken("Sales · Kim Jiwoo", line, "54.0 km", "10월", 100)
        assertEquals("Sales · Kim Jiwoo, 9건 · 업무 43.0 km, 총 54.0 km, 10월 거리의 100퍼센트", spoken)
        assertEquals("10월 운행 기준", SettingsCopy.usageMonthSpoken("10월"))
        listOf(spoken, SettingsCopy.usageMonthSpoken("2025년 12월")).forEach { long ->
            assertFalse("한 줄: $long", long.contains('\n'))
            assertFalse("마침표 없음: $long", long.trimEnd().endsWith('.'))
        }
        assertTrue("한 번에 듣는 행은 보이는 줄을 다 말함", spoken.contains(line))
    }

    /**
     * 2.15 §9.5: the tiles before the first employee (a title over one line, three across a 1112-dp page, so a line has a
     * destination card's room), the rows without trips, the card's tag before the first trip, and 비용 참고's limit bar, whose
     * label shares a half card with its amount.
     */
    @Test fun theFillsOfNineFiveKeepTheRules() {
        assertTrue(syllables(SettingsCopy.DRIVERS_INTRO) <= 8)
        assertEquals(3, SettingsCopy.DRIVERS_INTRO_TILES.size)
        assertEquals(SettingsCopy.DRIVERS_INTRO_TILES.size, SettingsCopy.SPOKEN_DRIVERS_INTRO_TILES.size)
        SettingsCopy.DRIVERS_INTRO_TILES.zip(SettingsCopy.SPOKEN_DRIVERS_INTRO_TILES).forEach { (tile, long) ->
            val (title, line) = tile
            assertTrue("$title: 8음절 이하", syllables(title) <= 8)
            assertOneLine(line, HOME_TILE_LINE_UNITS)
            assertOneLine(long, Double.MAX_VALUE)
            assertTrue("$line → $long", long.length > line.length)
            // The spoken form names the tile as its title does, without the circled number.
            assertTrue("$title → $long", long.contains(title.removePrefix("④ ")))
            assertFalse(long.contains('④'))
        }
        assertEquals("④ 사용자 칸", SettingsCopy.DRIVERS_INTRO_TILES.first().first)
        listOf(SettingsCopy.USAGE_NONE, SettingsCopy.USAGE_NO_TRIP_YET).forEach { assertTrue("$it: 8음절 이하", syllables(it) <= 8) }
        assertEquals("Finance · Lee Minho, 10월 운행 없음", SettingsCopy.usageNoneSpoken("Finance · Lee Minho", "10월"))
        assertEquals("Finance · Lee Minho, 운행 없음", SettingsCopy.usageNoneSpoken("Finance · Lee Minho", null))
        // The bar's label beside its amount (about 150 dp) in a 500-dp half card, bodyMedium: about 21 units.
        listOf(SettingsCopy.TAX_LIMIT, ReportCopy.TAX_WITHOUT_LOG, ReportCopy.TAX_WITH_LOG).forEach { assertOneLine(it, 20.0) }
        assertEquals("기록부 없을 때 인정 한도 15,000,000원", SettingsCopy.taxBarSpoken(SettingsCopy.TAX_LIMIT, NO_LOGBOOK_CAP_WON))
        assertFalse(SettingsCopy.SPOKEN_FACT_NONE.endsWith("."))
    }
}
