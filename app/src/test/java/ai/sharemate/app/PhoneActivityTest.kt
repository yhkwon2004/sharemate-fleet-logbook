package ai.sharemate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneActivityTest {
    @Test fun theCurrentInspectionStepIsAnnouncedNotOnlyColoured() {
        assertEquals("현재 단계", inspectionStepState(current = true, done = false))
        assertEquals("현재 단계 · 완료", inspectionStepState(current = true, done = true))
        assertEquals("완료", inspectionStepState(current = false, done = true))
        assertNull("남은 단계는 이름만", inspectionStepState(current = false, done = false))
    }
}
