package ai.sharemate.app

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parts of the 2.12 car components: how many lines a supporting line may take, when a segment's or a tab's label turns
 * white under the gliding accent, and the one-line copy rule's units. (2.13 took the header clock out with its test.)
 */
class CarUiTest {
    @Test fun aCarLineIsOneLineAtTheNormalFontAndTwoAboveIt() {
        assertEquals("작은 글꼴도 한 줄", 1, carLineMaxLines(.85f))
        assertEquals(1, carLineMaxLines(1f))
        assertEquals("1.05×까지 한 줄", 1, carLineMaxLines(1.05f))
        assertEquals("큰 글꼴은 두 줄로 단어를 잃지 않음", 2, carLineMaxLines(1.15f))
        assertEquals(2, carLineMaxLines(1.3f))
    }

    @Test fun aLabelTurnsWhiteOnceTheGlideCoversHalfOfIt() {
        val item = Rect(100f, 0f, 200f, 56f)
        assertTrue("제자리의 선택", glideCovers(item, item))
        assertTrue("정확히 절반", glideCovers(Rect(150f, 0f, 250f, 56f), item))
        assertTrue("왼쪽에서 정확히 절반", glideCovers(Rect(0f, 0f, 150f, 56f), item))
        assertFalse("49 %는 아직", glideCovers(Rect(151f, 0f, 251f, 56f), item))
        assertFalse("왼쪽에서 49 %", glideCovers(Rect(0f, 0f, 149f, 56f), item))
        assertFalse("넓은 표시가 지나가도 겹친 폭으로", glideCovers(Rect(160f, 0f, 400f, 56f), item))
        assertFalse("표시가 아직 없으면", glideCovers(null, item))
        assertFalse("항목을 아직 재지 않았으면", glideCovers(item, null))
    }

    @Test fun oneLineUnitsWeighHangulSpacesAndEverythingElse() {
        assertEquals(1.8, oneLineUnits("가 a"), 1e-9)
        assertEquals("숫자와 가운뎃점은 .55", 2.75, oneLineUnits("10·28"), 1e-9)
        // The 예약 tile's empty line keeps inside the 지금 column.
        assertEquals(10.75, oneLineUnits("현재 잡힌 예약이 없어요"), 1e-9)
        assertOneLine("현재 잡힌 예약이 없어요", NOW_LINE_UNITS)
    }
}
