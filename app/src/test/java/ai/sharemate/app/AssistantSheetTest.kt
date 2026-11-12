package ai.sharemate.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSheetTest {
    @Test fun theAssistantSizesItsTargetsByTheDeviceNotTheWindow() {
        assertEquals("휴대폰", 48.dp, assistantTapTarget(car = false, compact = true))
        assertEquals("태블릿 폭이어도 휴대폰은 48", 48.dp, assistantTapTarget(car = false, compact = false))
        assertEquals("1100 dp보다 좁게 나뉜 차량 창도 차량의 56", 56.dp, assistantTapTarget(car = true, compact = true))
        assertEquals("넓은 차량 창의 버튼은 60", 60.dp, assistantTapTarget(car = true, compact = false))
    }

    @Test fun theBarSaysWhatTheAssistantChangedInOneLine() {
        // 2.13 (docs/design/DESIGN_2_13_SPEC.md §8.4): the car drops the final period and falls back to a plain line past the bar's budget.
        assertEquals("운행 비서: 방금 운행을 업무로 기록했어요", assistantFollowUp("방금 운행을 업무로 기록했어요. 되돌릴 수 있어요.", car = true))
        val long = "11월 3일부터 11월 9일까지 미분류로 남아 있던 운행 다섯 건을 모두 일반 업무로 기록하고 통행료를 함께 넣었어요."
        assertTrue(long.length >= 60)
        assertEquals("운행 비서가 기록을 바꿨어요", assistantFollowUp(long, car = true))
        assertOneLine(assistantFollowUp("방금 운행을 업무로 기록했어요. 되돌릴 수 있어요.", car = true), FOLLOW_UP_UNITS)
        // The phone keeps 2.12's words.
        assertEquals("운행 비서: 방금 운행을 업무로 기록했어요.", assistantFollowUp("방금 운행을 업무로 기록했어요. 되돌릴 수 있어요.", car = false))
        assertEquals("운행 비서: ${long.take(60)}", assistantFollowUp(long, car = false))
    }

    @Test fun everyCarTargetClearsTheCarMinimumAndEveryPhoneTargetThePhoneMinimum() {
        for (compact in listOf(true, false)) {
            assertTrue(assistantTapTarget(car = true, compact = compact) >= 56.dp)
            // Chips and bubbles stop at 56 on the car and still clear it.
            assertEquals(56.dp, assistantTapTarget(car = true, compact = compact).coerceAtMost(56.dp))
            assertTrue(assistantTapTarget(car = false, compact = compact) >= 48.dp)
        }
    }
}
