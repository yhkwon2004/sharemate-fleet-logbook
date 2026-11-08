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

    @Test fun theSceneChangeFadesThroughTheBaseColourInsteadOfLerping() {
        val auto = sceneChangeTiming(override = false)
        assertEquals("시계가 바꾸면 800 ms", MOTION_SCENE_MS, auto.totalMs)
        assertEquals("200 ms에 한 번만 바뀜", 200, auto.swapAtMs)
        assertEquals("하늘은 600 ms 동안 겹침", 600, auto.crossfadeMs)
        val tap = sceneChangeTiming(override = true)
        assertEquals("직접 고르면 480 ms", 480, tap.totalMs)
        assertEquals(120, tap.swapAtMs)
        assertEquals(360, tap.crossfadeMs)
        // The scrim: nothing before, solid while the tokens swap, gone at the end; the content is never drawn half-way.
        assertEquals(0f, sceneScrimAlpha(0f, auto), 1e-4f)
        assertEquals(1f, sceneScrimAlpha(200f, auto), 1e-4f)
        assertEquals(1f, sceneScrimAlpha(400f, auto), 1e-4f)
        assertEquals(1f, sceneScrimAlpha(560f, auto), 1e-4f)
        assertEquals(0f, sceneScrimAlpha(800f, auto), 1e-4f)
        assertEquals("끝난 뒤에는 스크림 없음", 0f, sceneScrimAlpha(1200f, auto), 1e-4f)
        assertTrue("나갈 때 짙어짐", sceneScrimAlpha(150f, auto) > sceneScrimAlpha(50f, auto))
        assertTrue("돌아올 때 옅어짐", sceneScrimAlpha(760f, auto) < sceneScrimAlpha(600f, auto))
        assertTrue(sceneScrimAlpha(100f, tap) > 0f && sceneScrimAlpha(120f, tap) == 1f && sceneScrimAlpha(480f, tap) == 0f)
        // The base colour travels only while the content is hidden.
        assertEquals(0f, sceneBaseFraction(100f, auto), 1e-4f)
        assertEquals(0f, sceneBaseFraction(200f, auto), 1e-4f)
        assertTrue(sceneBaseFraction(380f, auto) in 0.05f..0.95f)
        assertEquals(1f, sceneBaseFraction(560f, auto), 1e-4f)
        assertEquals(1f, sceneBaseFraction(800f, auto), 1e-4f)
        // The sky's orb and stars move on the part of the crossfade the driver sees: the content is hidden for its first 60 %.
        assertEquals(0f, sceneRevealProgress(0f, auto), 1e-4f)
        assertEquals(0f, sceneRevealProgress(.6f, auto), 1e-4f)
        assertEquals(.5f, sceneRevealProgress(.8f, auto), 1e-4f)
        assertEquals(1f, sceneRevealProgress(1f, auto), 1e-4f)
        assertEquals(.5f, sceneRevealProgress(.8f, tap), 1e-4f)
        // Palettes are picked, never mixed: dusk is the night palette under a different sky.
        assertEquals(DaySceneColors, sceneColorsFor(ScenePhase.DAY))
        assertEquals(NightSceneColors, sceneColorsFor(ScenePhase.DUSK))
        assertEquals(NightSceneColors, sceneColorsFor(ScenePhase.NIGHT))
    }

    @Test fun aSecondChangeCarriesOnFromTheScrimAsItStands() {
        val auto = sceneChangeTiming(override = false)
        val tap = sceneChangeTiming(override = true)
        // The inverse of the scrim curve, on the way out and on the way back, for both clocks.
        listOf(auto, tap).forEach { timing ->
            listOf(.1f, .5f, .9f).forEach { alpha ->
                assertEquals("나가는 중 $alpha", alpha, sceneScrimAlpha(sceneClockAt(alpha, timing, leaving = true), timing), 1e-3f)
                assertEquals("돌아오는 중 $alpha", alpha, sceneScrimAlpha(sceneClockAt(alpha, timing, leaving = false), timing), 1e-3f)
            }
        }
        assertEquals("아직 안 나갔으면 처음부터", 0f, sceneClockAt(0f, auto, leaving = true), .5f)
        assertEquals("다 나갔으면 바로 바꿀 때", 200f, sceneClockAt(1f, auto, leaving = true), .5f)
        assertEquals("다 가려져 있으면 돌아오기 시작", 560f, sceneClockAt(1f, auto, leaving = false), .5f)
        assertEquals("다 돌아왔으면 끝", 800f, sceneClockAt(0f, auto, leaving = false), .5f)
        assertTrue("나가는 중 시계는 나가는 구간 안", sceneClockAt(.5f, tap, leaving = true) in 0f..120f)
        assertTrue("돌아오는 중 시계는 돌아오는 구간 안", sceneClockAt(.5f, tap, leaving = false) in 336f..480f)
        // A tap is a change that arrives with a new 자동 · 낮 · 밤 choice; the clock keeps the choice.
        assertTrue(sceneChangeIsTap(modeNow = SceneMode.DAY, modeBefore = SceneMode.AUTO))
        assertTrue(sceneChangeIsTap(modeNow = SceneMode.AUTO, modeBefore = SceneMode.NIGHT))
        assertTrue(!sceneChangeIsTap(modeNow = SceneMode.AUTO, modeBefore = SceneMode.AUTO))
    }

    @Test fun theOrbRisesIntoPlaceAndTheStarsGoFirst() {
        assertEquals("들어오는 해는 8 % 아래에서 시작", .08f, orbLift(entering = true, leaving = false, t = 0f), 1e-4f)
        assertEquals("제자리에 도착", 0f, orbLift(entering = true, leaving = false, t = 1f), 1e-4f)
        assertTrue(orbLift(true, false, .5f) < orbLift(true, false, .2f))
        assertEquals("나가는 달은 가라앉음", 0f, orbLift(entering = false, leaving = true, t = 0f), 1e-4f)
        assertEquals(.08f, orbLift(entering = false, leaving = true, t = 1f), 1e-4f)
        assertEquals("가만히 있는 하늘은 움직이지 않음", 0f, orbLift(false, false, .5f), 1e-4f)
        assertEquals("범위 밖 t는 잘라냄", .08f, orbLift(true, false, -1f), 1e-4f)
        assertEquals(0f, starFade(entering = true, leaving = false, t = 0f), 1e-4f)
        assertEquals(.5f, starFade(entering = true, leaving = false, t = .2f), 1e-4f)
        assertEquals("별은 처음 40 % 안에 다 들어옴", 1f, starFade(entering = true, leaving = false, t = .4f), 1e-4f)
        assertEquals("나갈 때는 처음 40 % 안에 사라짐", 0f, starFade(entering = false, leaving = true, t = .4f), 1e-4f)
        assertEquals(1f, starFade(false, false, .9f), 1e-4f)
    }

    @Test fun theDayHazeIsWhiteAndLighterThanTheNightScrim() {
        val day = DaySceneColors
        val night = NightSceneColors
        val carDay = skyScrims(day = true, compact = false, leftScrim = true, horizonAt = .52f, haze = day.haze, bg = day.bg, heroEdge = .47f, heroFade = .18f)
        val carNight = skyScrims(day = false, compact = false, leftScrim = true, horizonAt = .52f, haze = night.haze, bg = night.bg)
        assertEquals(2, carDay.size)
        assertEquals(2, carNight.size)
        val hero = carDay[0]
        assertTrue("차량 낮: 왼쪽에서 오는 흰 안개", !hero.vertical)
        assertEquals(Color.White, hero.stops[0].second.copy(alpha = 1f))
        assertEquals(.90f, hero.stops[0].second.alpha, .005f)
        assertEquals(.76f, hero.stops[1].second.alpha, .005f)
        assertEquals(0f, hero.stops[2].second.alpha, .005f)
        assertEquals("히어로 칼럼의 오른쪽 끝", .47f, hero.stops[1].first, 1e-3f)
        assertEquals("그 뒤 300 dp에서 사라짐", .65f, hero.stops[2].first, 1e-3f)
        val left = carNight[0]
        assertTrue(!left.vertical)
        assertEquals("밤은 그대로", night.bg, left.stops[0].second.copy(alpha = 1f))
        assertEquals(.92f, left.stops[0].second.alpha, .005f)
        assertEquals(.62f, left.stops[1].second.alpha, .005f)
        assertEquals(.74f, left.stops[2].first, 1e-3f)
        assertEquals("낮의 아래 스크림은 더 옅음", .25f, carDay[1].stops[0].second.alpha, .005f)
        assertEquals(.85f, carDay[1].stops[3].second.alpha, .005f)
        assertEquals(.35f, carNight[1].stops[0].second.alpha, .005f)
        assertEquals(.90f, carNight[1].stops[3].second.alpha, .005f)
        assertEquals("아래쪽은 바탕색으로 끝남", day.bg, carDay[1].stops.last().second)
        assertEquals(night.bg, carNight[1].stops.last().second)
        val phoneDay = skyScrims(day = true, compact = true, leftScrim = true, horizonAt = .40f, haze = day.haze, bg = day.bg, greetingBottom = .20f, greetingFade = .14f)
        assertTrue("휴대폰 낮: 위에서 내려오는 안개", phoneDay[0].vertical)
        assertEquals(.88f, phoneDay[0].stops[0].second.alpha, .005f)
        assertEquals(.76f, phoneDay[0].stops[1].second.alpha, .005f)
        assertEquals(.34f, phoneDay[0].stops[2].first, 1e-3f)
        val signInDay = skyScrims(day = true, compact = true, leftScrim = false, horizonAt = .46f, haze = day.haze, bg = day.bg)
        assertTrue(signInDay[0].vertical)
        assertEquals("로그인 낮: 위 55 %에 흰 스크림", .84f, signInDay[0].stops[0].second.alpha, .005f)
        assertEquals(.55f, signInDay[0].stops[1].first, 1e-3f)
        assertEquals("로그인 밤: 위 스크림 없음", 1, skyScrims(day = false, compact = true, leftScrim = false, horizonAt = .46f, haze = night.haze, bg = night.bg).size)
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
