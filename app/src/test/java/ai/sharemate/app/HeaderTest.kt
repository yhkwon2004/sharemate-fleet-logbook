package ai.sharemate.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2.12 header's pure rules: how it lays itself out in each window (one row or two, the clock with the date beside the
 * time or over it, the driver chip and the recording state in the header or in the 지금 column's 사용자 card), when the
 * inline clock fits, and what TalkBack says for the driver chip.
 */
class HeaderTest {
    /** The shell's own question for a window [width] dp wide: does the 지금 column stand? Its rail runs between the gutters. */
    private fun nowColumn(layout: LayoutTokens, width: Dp, fontScale: Float = 1f) = carColumns(layout, width - layout.gutter * 2, fontScale).split

    @Test fun theHeaderPlanFollowsTheWindowAndTheNowColumn() {
        val full = carLayout(1680.dp)
        assertTrue(nowColumn(full, 1680.dp))
        assertEquals("FULL: 지금 열 옆에서 한 줄, 시계는 날짜와 나란히, 상태는 사용자 카드로",
            HeaderPlan(oneRow = true, clockInline = true, statusInHeader = false), headerPlan(full, nowColumn = true))
        val short = carLayout(1680.dp, 500.dp)
        assertFalse("낮은 창에는 지금 열이 없음", nowColumn(short, 1680.dp))
        assertEquals("낮은 FULL: 열 없이도 한 줄, 사용자와 상태는 헤더에", HeaderPlan(oneRow = true, clockInline = true, statusInHeader = true),
            headerPlan(short, nowColumn = false))
        val expanded = carLayout(1200.dp)
        assertFalse(nowColumn(expanded, 1200.dp))
        assertEquals("열 없는 EXPANDED: 두 줄, 시계는 날짜와 나란히", HeaderPlan(oneRow = false, clockInline = true, statusInHeader = true),
            headerPlan(expanded, nowColumn = false))
        assertEquals("MEDIUM: 두 줄, 시계는 날짜 아래 시각", HeaderPlan(oneRow = false, clockInline = false, statusInHeader = true),
            headerPlan(carLayout(900.dp), nowColumn = false))
        // The full window at a 1.3× font is EXPANDED by width ÷ scale and still keeps the column (its page is 805 dp in effect).
        val large = carLayout(1680.dp / 1.3f)
        assertTrue(nowColumn(large, 1680.dp, 1.3f))
        assertEquals("1.3배 글꼴의 전체 창도 지금 열 옆에서 한 줄", HeaderPlan(oneRow = true, clockInline = true, statusInHeader = false),
            headerPlan(large, nowColumn = true))
    }

    @Test fun theInlineClockNeedsItsRoomAtTheFontInEffect() {
        assertTrue("여백 52 + 글자 180 = 232 dp", clockFitsInline(232.dp, 1f))
        assertFalse(clockFitsInline(231.dp, 1f))
        assertTrue("1.3배 글꼴은 글자만 커져 52 + 234 = 286 dp", clockFitsInline(286.dp, 1.3f))
        assertFalse(clockFitsInline(285.dp, 1.3f))
        assertTrue("작은 글꼴은 1배로 셈", clockFitsInline(232.dp, .85f))
    }

    @Test fun theDriverChipSaysItsCaptionThePersonAndWhatATapDoes() {
        assertEquals("사용자 영업 · 김, 바꾸기", driverChipLabel(Driver("d", "영업", "김")))
        assertEquals("아무도 고르지 않았으면 운전자 선택", "사용자 미선택, 운전자 선택", driverChipLabel(null))
    }
}
