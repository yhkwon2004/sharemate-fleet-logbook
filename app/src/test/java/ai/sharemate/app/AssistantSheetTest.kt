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

    @Test fun everyCarTargetClearsTheCarMinimumAndEveryPhoneTargetThePhoneMinimum() {
        for (compact in listOf(true, false)) {
            assertTrue(assistantTapTarget(car = true, compact = compact) >= 56.dp)
            // Chips and bubbles stop at 56 on the car and still clear it.
            assertEquals(56.dp, assistantTapTarget(car = true, compact = compact).coerceAtMost(56.dp))
            assertTrue(assistantTapTarget(car = false, compact = compact) >= 48.dp)
        }
    }
}
