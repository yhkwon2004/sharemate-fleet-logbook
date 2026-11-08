package ai.sharemate.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SceneTest {
    private fun at(month: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, month, 15, hour, minute)

    @Test fun thePhaseFollowsSeoulDaylight() {
        assertEquals("11월 정오는 낮", ScenePhase.DAY, scenePhaseAt(at(11, 12)))
        assertEquals("11월 07:10 일출 · 07:40부터 낮", ScenePhase.DUSK, scenePhaseAt(at(11, 7, 20)))
        assertEquals(ScenePhase.DAY, scenePhaseAt(at(11, 7, 40)))
        assertEquals("11월 17:25 일몰 · 16:40부터 해 질 무렵", ScenePhase.DUSK, scenePhaseAt(at(11, 16, 45)))
        assertEquals(ScenePhase.NIGHT, scenePhaseAt(at(11, 18, 15)))
        assertEquals(ScenePhase.NIGHT, scenePhaseAt(at(11, 23)))
        assertEquals("6월 19:55 일몰 · 19:00은 아직 낮", ScenePhase.DAY, scenePhaseAt(at(6, 19)))
        assertEquals(ScenePhase.NIGHT, scenePhaseAt(at(6, 3)))
    }

    @Test fun theDriverCanPinTheScene() {
        assertEquals(ScenePhase.DAY, scenePhaseAt(at(11, 23), SceneMode.DAY))
        assertEquals(ScenePhase.NIGHT, scenePhaseAt(at(11, 12), SceneMode.NIGHT))
        assertEquals("고정하면 한 시간마다만 다시 봄", 60L * 60_000, nextSceneChangeIn(at(11, 12), SceneMode.NIGHT))
    }

    @Test fun theTickerWakesAtTheNextChange() {
        // 16:00 in November: dusk starts at 16:40, so the ticker wakes at 16:40, 40 minutes on.
        assertEquals(40L * 60_000, nextSceneChangeIn(at(11, 16)))
        assertTrue(nextSceneChangeIn(at(11, 12)) > 60_000)
    }

    @Test fun thePaletteCrossFadesAndSaysWhichSideItIsOn() {
        assertEquals(NightSceneColors, lerpScene(NightSceneColors, DaySceneColors, 0f))
        assertEquals(DaySceneColors, lerpScene(NightSceneColors, DaySceneColors, 1f))
        val mid = lerpScene(NightSceneColors, DaySceneColors, .5f)
        assertTrue(mid.isDay)
        assertTrue(mid.bg != NightSceneColors.bg && mid.bg != DaySceneColors.bg)
        assertEquals("배지 빨강은 두 장면이 같음", DaySceneColors.badge, mid.badge)
    }

    @Test fun dayTextClearsAaOnWhiteGlass() {
        fun luminance(c: Color): Double {
            fun lin(v: Float): Double = if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
        }
        fun contrast(a: Color, b: Color): Double { val l1 = luminance(a); val l2 = luminance(b); return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05) }
        val day = DaySceneColors
        listOf(day.text, day.textSecondary, day.textMuted, day.glow, day.warn, day.brand).forEach { color ->
            assertTrue("$color on white glass", contrast(color, day.glass) >= 4.5)
            assertTrue("$color on the day background", contrast(color, day.bg) >= 4.5)
        }
        assertTrue("badge digits", contrast(Color.White, day.badge) >= 4.5)
        listOf(day.positive, day.negative).forEach { color -> assertTrue("$color status text on white", contrast(color, day.glass) >= 4.5) }
        assertTrue("outline on white", contrast(day.outline, day.glass) >= 3.0)
        assertTrue("white icon on the tile", contrast(Color.White, day.tileEnd) >= 4.5 && contrast(Color.White, day.tileStart) >= 4.5)
        assertTrue("selection ring on the selection fill", contrast(day.selectionRing, day.glass) >= 3.0)
        val night = NightSceneColors
        listOf(night.text, night.textSecondary, night.textMuted, night.glow, night.warn, night.brand).forEach { color ->
            assertTrue("$color on night glass", contrast(color, night.glass) >= 4.5)
        }
    }

    @Test fun layoutTokensGrowWithTheWindow() {
        assertEquals(WidthClass.COMPACT, carLayout(700.dp).widthClass)
        assertEquals(WidthClass.MEDIUM, carLayout(1000.dp).widthClass)
        assertEquals(WidthClass.EXPANDED, carLayout(1200.dp).widthClass)
        assertEquals(WidthClass.FULL, carLayout(1680.dp).widthClass)
        assertTrue(carLayout(1680.dp).gutter > carLayout(1100.dp).gutter)
        assertTrue("가장 넓은 창에서도 내용은 1200dp까지", carLayout(1680.dp).contentMax == 1200.dp)
        assertEquals("낮은 창", HeightClass.SHORT, carLayout(1680.dp, 500.dp).heightClass)
        assertTrue(carLayout(1680.dp).startAnchored && !phoneLayout(411.dp).startAnchored)
        assertEquals(WidthClass.COMPACT, phoneLayout(411.dp).widthClass)
        assertEquals(WidthClass.MEDIUM, phoneLayout(800.dp).widthClass)
        assertEquals(WidthClass.EXPANDED, phoneLayout(1024.dp).widthClass)
    }

    @Test fun theSettingsLineMatchesTheChoice() {
        assertTrue(sceneModeHint(SceneMode.AUTO, ScenePhase.DAY).startsWith("지금은 낮"))
        assertTrue(sceneModeHint(SceneMode.AUTO, ScenePhase.NIGHT).contains("해가 뜨면"))
        assertEquals("항상 밝은 낮 화면을 써요", sceneModeHint(SceneMode.DAY, ScenePhase.NIGHT))
    }
}
