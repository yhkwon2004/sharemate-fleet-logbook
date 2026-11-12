package ai.sharemate.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header's pure rules (2.12, 2.13): how it lays itself out in each window (one row or two, the driver chip and the
 * recording state in the header or in the 지금 column's 사용자 card), which form the search bar takes in the room it is left,
 * how much the driver chip may take before the bar would drop to its label, and what TalkBack says for the driver chip.
 * There is no clock: the car's own status bar shows the time.
 */
class HeaderTest {
    /** The shell's own question for a window [width] dp wide: does the 지금 column stand? Its rail runs between the gutters. */
    private fun nowColumn(layout: LayoutTokens, width: Dp, fontScale: Float = 1f) = carColumns(layout, width - layout.gutter * 2, fontScale).split

    @Test fun theHeaderPlanFollowsTheWindowAndTheNowColumn() {
        val full = carLayout(1680.dp)
        assertTrue(nowColumn(full, 1680.dp))
        assertEquals("FULL: 지금 열 옆에서 한 줄, 상태는 사용자 카드로", HeaderPlan(oneRow = true, statusInHeader = false), headerPlan(full, nowColumn = true))
        val short = carLayout(1680.dp, 500.dp)
        assertFalse("낮은 창에는 지금 열이 없음", nowColumn(short, 1680.dp))
        assertEquals("낮은 FULL: 열 없이도 한 줄, 사용자와 상태는 헤더에", HeaderPlan(oneRow = true, statusInHeader = true), headerPlan(short, nowColumn = false))
        val expanded = carLayout(1200.dp)
        assertFalse(nowColumn(expanded, 1200.dp))
        assertEquals("열 없는 EXPANDED: 두 줄", HeaderPlan(oneRow = false, statusInHeader = true), headerPlan(expanded, nowColumn = false))
        assertEquals("MEDIUM: 두 줄", HeaderPlan(oneRow = false, statusInHeader = true), headerPlan(carLayout(900.dp), nowColumn = false))
        // The full window at a 1.3× font is EXPANDED by width ÷ scale and still keeps the column (its page is 805 dp in effect).
        val large = carLayout(1680.dp / 1.3f)
        assertTrue(nowColumn(large, 1680.dp, 1.3f))
        assertEquals("1.3배 글꼴의 전체 창도 지금 열 옆에서 한 줄", HeaderPlan(oneRow = true, statusInHeader = false), headerPlan(large, nowColumn = true))
    }

    @Test fun theSearchBarTakesTheFormItsRoomAllows() {
        assertEquals("FULL, 지금 열 옆: 824 dp", SearchBarMode.EXAMPLE, searchBarMode(824.dp, 1f))
        assertEquals(SearchBarMode.EXAMPLE, searchBarMode(496.dp, 1f))
        assertEquals(SearchBarMode.PHRASE, searchBarMode(495.dp, 1f))
        assertEquals("막대의 최소 폭", SearchBarMode.PHRASE, searchBarMode(280.dp, 1f))
        assertEquals(SearchBarMode.LABEL, searchBarMode(279.dp, 1f))
        assertEquals(SearchBarMode.LABEL, searchBarMode(160.dp, 1f))
        assertEquals("아이콘만", SearchBarMode.ICON, searchBarMode(159.dp, 1f))
        assertEquals("1.3배 글꼴: 280 × 1.3 = 364", SearchBarMode.PHRASE, searchBarMode(364.dp, 1.3f))
        assertEquals(SearchBarMode.LABEL, searchBarMode(363.dp, 1.3f))
        assertEquals("작은 글꼴은 1배로 셈", SearchBarMode.PHRASE, searchBarMode(495.dp, .85f))
        assertEquals(SearchBarMode.LABEL, searchBarMode(279.dp, .85f))
    }

    @Test fun theChipGivesWayBeforeTheBarDropsToItsLabel() {
        assertEquals("1024 − 176 − 16 − 280", 552.dp, headerChipMax(1024.dp, 176.dp, 1f))
        assertEquals("600 dp 창의 둘째 줄", 88.dp, headerChipMax(560.dp, 176.dp, 1f))
        assertEquals("아바타 알약 밑으로는 줄지 않음", 56.dp, headerChipMax(560.dp, 295.dp, 1f))
        assertEquals("1.3배 글꼴은 막대가 364 dp를 지킴", 468f, headerChipMax(1024.dp, 176.dp, 1.3f).value, .01f)
        // What the chip may take keeps the bar a bar: the room left for it is 280 dp while the chip can still shrink.
        val left = 1024.dp - 176.dp - 16.dp - headerChipMax(1024.dp, 176.dp, 1f)
        assertEquals(SearchBarMode.PHRASE, searchBarMode(left, 1f))
    }

    @Test fun theBarSpeaksTheWordsItShows() {
        assertTrue(HeaderCopy.SPOKEN_BAR.startsWith(HeaderCopy.BAR_PHRASE))
        assertTrue(HeaderCopy.SPOKEN_BAR.endsWith(HeaderCopy.BAR_EXAMPLE))
        assertEquals("운행 비서에게 물어보세요, 예: 주차비 4,500원 추가해줘", HeaderCopy.SPOKEN_BAR)
        assertOneLine(HeaderCopy.BAR_PHRASE, BAR_PHRASE_UNITS)
    }

    @Test fun theDriverChipSaysItsCaptionThePersonAndWhatATapDoes() {
        assertEquals("사용자 영업 · 김, 바꾸기", driverChipLabel(Driver("d", "영업", "김")))
        assertEquals("아무도 고르지 않았으면 운전자 선택", "사용자 미선택, 운전자 선택", driverChipLabel(null))
    }
}
