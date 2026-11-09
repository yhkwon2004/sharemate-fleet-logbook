package ai.sharemate.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * Pure helpers behind the phone's 운행 list (2.10): the day panels, the month header and the empty current month, the
 * layout fallback, sheet widths, the bottom padding under the tab bar and the entrance stagger.
 */
class PhoneTripListTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private fun trip(seed: String, day: Int, hour: Int) = Trip(id(seed), at(day, hour), at(day, hour) + 30 * 60_000, 100.0, 110.0, updatedAt = at(day, hour) + 30 * 60_000)

    @Test fun theLastTripOfEachDayClosesItsPanel() {
        // Newest first, the way the list shows them: two on the 28th, one on the 27th, two on the 26th.
        val trips = listOf(trip("a", 28, 17), trip("b", 28, 9), trip("c", 27, 18), trip("d", 26, 8), trip("e", 26, 7))
        assertEquals(listOf(false, true, true, false, true), dayPanelEnds(trips, zone))
        assertEquals(listOf(true), dayPanelEnds(listOf(trip("x", 1, 9)), zone))
        assertTrue(dayPanelEnds(emptyList(), zone).isEmpty())
    }

    @Test fun aTripJustAfterMidnightOpensANewDayPanel() {
        val trips = listOf(trip("next", 29, 0), trip("late", 28, 23))
        assertEquals("서울 기준 다른 날", listOf(true, true), dayPanelEnds(trips, zone))
    }

    @Test fun sheetsNeverGrowPastMaterialsWidestSheet() {
        assertEquals(640.dp, PHONE_SHEET_MAX)
        assertEquals("세로 휴대폰: 폭 제한 없음 → Material 최대", PHONE_SHEET_MAX, sheetMaxWidthFor(Dp.Infinity))
        assertEquals("가로 휴대폰: 내용 폭 600", 600.dp, sheetMaxWidthFor(600.dp))
        assertEquals("태블릿: 내용 폭 760이어도 시트는 640", PHONE_SHEET_MAX, sheetMaxWidthFor(760.dp))
    }

    @Test fun theFallbackTokensDivideTheWidthByTheFontScaleLikeTheShell() {
        assertEquals(WidthClass.COMPACT, fallbackPhoneTokens(411.dp, 900.dp, 1f).widthClass)
        assertEquals(WidthClass.MEDIUM, fallbackPhoneTokens(800.dp, 411.dp, 1f).widthClass)
        assertEquals(WidthClass.EXPANDED, fallbackPhoneTokens(1000.dp, 800.dp, 1f).widthClass)
        assertEquals("1.3배 글자에서는 가로 휴대폰이 COMPACT", WidthClass.COMPACT, fallbackPhoneTokens(760.dp, 411.dp, 1.3f).widthClass)
        assertEquals("글자를 줄여도 더 넓은 등급은 아님", WidthClass.MEDIUM, fallbackPhoneTokens(800.dp, 411.dp, 0.85f).widthClass)
        assertEquals(HeightClass.SHORT, fallbackPhoneTokens(800.dp, 411.dp, 1f).heightClass)
        val portrait = fallbackPhoneTokens(411.dp, 900.dp, 1f)
        assertFalse("휴대폰은 가운데 정렬", portrait.startAnchored)
        assertEquals(20.dp, portrait.gutter); assertEquals(12.dp, portrait.cardGap); assertEquals(Dp.Infinity, portrait.contentMax)
    }

    @Test fun theEntranceStaggerIsCappedAtEightRows() {
        assertEquals(8, PHONE_APPEAR_CAP)
        assertEquals(0, appearIndex(0)); assertEquals(3, appearIndex(3)); assertEquals(7, appearIndex(7))
        assertEquals("아홉 번째부터는 마지막 자리를 같이 씀", 7, appearIndex(8)); assertEquals(7, appearIndex(40))
        assertEquals(0, appearIndex(-1))
    }

    @Test fun theMonthHeaderKeepsItsNumbersOnASecondLine() {
        val october = TripMonthGroup(YearMonth.of(2026, 10), listOf(trip("a", 29, 9).copy(purpose = TripPurpose.BUSINESS), trip("b", 28, 9)))
        assertEquals("2026년 10월", monthTitleText(october.month))
        assertEquals("2건 · 20.0 km · 업무 50%", monthStatsText(october))
        assertEquals("화면 낭독은 두 줄을 한 문장으로", "2026년 10월 · 2건 · 20.0 km · 업무 50%", monthHeaderText(october))
        val november = TripMonthGroup(YearMonth.of(2026, 11), emptyList())
        assertEquals("운행 없음", monthStatsText(november))
        assertEquals("2026년 11월 · 운행 없음", monthHeaderText(november))
        assertEquals("11월 운행은 아직 없어요", emptyMonthNote(november.month))
    }

    @Test fun anEmptyCurrentMonthIsOneLineAboveASingleStickyHeader() {
        val november = YearMonth.of(2026, 11)
        val october = YearMonth.of(2026, 10)
        val groups = tripMonthGroups(listOf(trip("a", 29, 9), trip("b", 29, 8), trip("c", 28, 9)), november, zone)
        assertEquals(listOf(november, october), groups.map { it.month })
        // Only the current month is in so far: its line, and no header (or month picker) of its own.
        assertEquals(listOf<TripListEntry>(TripListEntry.EmptyMonth(november)), tripListEntries(groups, 1, october, zone))
        val all = tripListEntries(groups, 2, october, zone)
        assertEquals(TripListEntry.EmptyMonth(november), all.first())
        val headers = all.filterIsInstance<TripListEntry.Month>()
        assertEquals("고정 머리글은 10월 하나", listOf(october), headers.map { it.group.month })
        assertTrue("빈 달 다음의 머리글이 맨 위 머리글", headers.single().first)
        assertEquals(2, all.count { it is TripListEntry.Day }); assertEquals(3, all.count { it is TripListEntry.Row })
        assertEquals(TripListEntry.End(october), all.last())
        // The month picker finds November at its line and October at its header.
        assertEquals(0, all.indexOfFirst { it.opens(november) })
        assertEquals(1, all.indexOfFirst { it.opens(october) })
        assertEquals(-1, all.indexOfFirst { it.opens(YearMonth.of(2026, 9)) })
    }

    @Test fun aCurrentMonthWithTripsKeepsItsHeaderAndALoneEmptyMonthLeavesTheEndLine() {
        val october = YearMonth.of(2026, 10)
        val entries = tripListEntries(tripMonthGroups(listOf(trip("a", 29, 9)), october, zone), 1, october, zone)
        assertEquals(october, (entries.first() as TripListEntry.Month).group.month)
        assertTrue((entries.first() as TripListEntry.Month).first)
        // Nothing closed at all (a trip still being recorded): the end line speaks, without a second line saying the same.
        val alone = tripMonthGroups(emptyList(), YearMonth.of(2026, 11), zone)
        assertEquals(listOf<TripListEntry>(TripListEntry.End(null)), tripListEntries(alone, 1, null, zone))
    }

    @Test fun tabsScrollOnUnderTheTabBarAndComeToRestSixteenDpAboveIt() {
        assertEquals(16.dp, TAB_SCROLL_END_SPACE)
        assertEquals("72 막대 + 여백 10·10이면 108", 108.dp, tabScrollBottom(92.dp))
        assertEquals("탭 막대가 없으면 16만", 16.dp, tabScrollBottom(0.dp))
        assertEquals("떠 있는 버튼만큼 더", 196.dp, tabScrollBottom(92.dp, extra = 88.dp))
    }
}
