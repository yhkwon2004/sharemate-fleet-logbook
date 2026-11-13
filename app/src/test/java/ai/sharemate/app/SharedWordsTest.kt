package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * 2.15 fix round, integration: the same thing has the same word wherever the three packages name it (FA's empty hero, FB's
 * 지금 column and 설정 pages, FC's pairing), so a later change to one of them changes all of them. Each case pins the spec's
 * words and that they come from one constant.
 */
class SharedWordsTest {
    @Test fun aMonthsStepsAreNamedAsHomeAndMonthCloseNameThem() {
        // §9.3's three steps: ① 목적 확인 (홈's no-trip step), ② 월 마감 (홈's card title), ③ Excel 저장 (월 마감's step).
        assertEquals(listOf(HomeCopy.IDLE_STEP_PURPOSE, HomeCopy.CLOSE_TITLE, ReportCopy.EXCEL_SAVE), ReportCopy.HERO_STEPS.map { it.first })
        assertEquals(listOf("목적 확인", "월 마감", "Excel 저장"), ReportCopy.HERO_STEPS.map { it.first })
        // §9.7: 홈's line for a closed month of another year ends in the same save.
        assertEquals("2026년 12월 운행기록부 · Excel 저장", HomeCopy.exportLine("2026년 12월", toClose = false, otherYear = true))
        assertTrue(HomeCopy.exportLine("2026년 12월", toClose = false, otherYear = true).endsWith(ReportCopy.EXCEL_SAVE))
    }

    @Test fun settingsNamesTheSettlementAndTheLimitAsTheirCardsDo() {
        // §9.5: the third tile before the first employee is the card it stands for, seen and heard.
        assertEquals("유류비 정산", SettingsCopy.DRIVERS_INTRO_TILES[2].first)
        assertEquals(ReportCopy.SETTLEMENT_TITLE, driverIntroTiles()[2].title)
        assertTrue(SettingsCopy.SPOKEN_DRIVERS_INTRO_TILES[2].startsWith("${ReportCopy.SETTLEMENT_TITLE}, "))
        // The limit bar keeps the comparison's own label for a car without a logbook.
        assertEquals("기록부 없을 때 인정 한도", SettingsCopy.TAX_LIMIT)
        assertTrue(SettingsCopy.TAX_LIMIT.startsWith(ReportCopy.TAX_WITHOUT_LOG))
    }

    @Test fun theIssuesHeroSaysTheMonthsFactsAsSettingsDoes() {
        val zone = ZoneId.of("Asia/Seoul")
        val at = { day: Int, hour: Int -> LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli() }
        val trips = listOf(Trip("a", at(5, 9), at(5, 10), 100.0, 120.0, purpose = TripPurpose.BUSINESS),
            Trip("b", at(6, 9), at(6, 10), 120.0, 125.0, purpose = TripPurpose.PERSONAL))
        val hero = logEmptyHero(1, YearMonth.of(2026, 10), TripLogData(trips = trips), null, LocalDate.of(2026, 11, 13), zone)
        assertEquals(EmptyHeroCase.ISSUES, hero.case)
        val facts = monthFacts(trips)
        assertEquals(facts.map { it.figure }, hero.tiles.map { it.title })
        assertEquals(facts.map { it.label }, hero.tiles.map { it.line })
        assertEquals(facts.map { it.spoken }, hero.tiles.map { it.spoken })
        assertEquals(facts.map { it.kind.glyph }, hero.tiles.map { it.glyph })
    }

    @Test fun thePhoneLinkIsOffOrFailedInOneWordOnTheCarsTwoPlaces() {
        // The 차량 page's 휴대폰 연결 tag and the 지금 column's 휴대폰 figure (§9.4).
        assertEquals(VehicleCopy.NOW_PHONE_OFF, phoneLinkTag(false, false, null, false, 0).first)
        assertEquals(VehicleCopy.NOW_PHONE_OFF, nowVehiclePhone(false, false, null, 0).value)
        assertEquals(VehicleCopy.NOW_PHONE_ERROR, phoneLinkTag(true, false, "bind", false, 0).first)
        assertEquals(VehicleCopy.NOW_PHONE_ERROR, nowVehiclePhone(true, false, "bind", 0).value)
        assertEquals(listOf("꺼짐", "서버 오류"), listOf(VehicleCopy.NOW_PHONE_OFF, VehicleCopy.NOW_PHONE_ERROR))
    }

    @Test fun thePhonesWaitingLineNamesItsButtonInTheButtonsWords() {
        // §9.6: the line asks for the button under the fields, whose label is the same constant.
        assertEquals("차량과 연결", PhoneQrCopy.CONNECT)
        assertEquals("주소를 확인하고 ${PhoneQrCopy.CONNECT}을 눌러 주세요 · 10.0.2.15:9247", PhoneQrCopy.confirm("10.0.2.15:9247"))
    }
}
