package ai.sharemate.app

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test fun aPressDimsAtOnceUnderReducedMotion() {
        // Controls without a ripple (header tabs, segments, rows, steppers) still answer the finger when nothing may move.
        assertEquals(.82f, pressDimAlpha(pressed = true), 0f)
        assertEquals(1f, pressDimAlpha(pressed = false), 0f)
    }

    @Test fun aChangingNumberResizesWithItsText() {
        assertTrue("동작 줄이기: 크기도 즉시", digitSizeSpec(reduce = true) is SnapSpec<*>)
        val spec = digitSizeSpec(reduce = false)
        assertTrue(spec is TweenSpec<*>)
        assertEquals("글자와 같은 160 ms", 160, (spec as TweenSpec<*>).durationMillis)
    }
}
