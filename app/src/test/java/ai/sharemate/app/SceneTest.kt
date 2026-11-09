package ai.sharemate.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun duskAndNightChangeOnlyTheSky() {
        // The palette changes between day and the other two, so only those changes fade the content through the base colour.
        listOf(ScenePhase.DUSK, ScenePhase.NIGHT).forEach { dark ->
            assertTrue("낮 → $dark 은 페이드스루", sceneChangeNeedsScrim(ScenePhase.DAY, dark))
            assertTrue("$dark → 낮 은 페이드스루", sceneChangeNeedsScrim(dark, ScenePhase.DAY))
        }
        // Dusk and night share the Midnight palette: no scrim, the shown phase moves at once and only the sky crossfades.
        assertTrue("해 질 무렵 → 밤은 하늘만", !sceneChangeNeedsScrim(ScenePhase.DUSK, ScenePhase.NIGHT))
        assertTrue("밤 → 해 뜰 무렵도 하늘만", !sceneChangeNeedsScrim(ScenePhase.NIGHT, ScenePhase.DUSK))
        ScenePhase.entries.forEach { assertTrue("같은 장면은 바꿀 것이 없음", !sceneChangeNeedsScrim(it, it)) }
        // The sky-only clock: the crossfade keeps its length (600 ms, 360 on a tap), nothing is hidden, and no scrim is ever drawn.
        listOf(false, true).forEach { tap ->
            val full = sceneChangeTiming(tap)
            val sky = skyOnlyTiming(full)
            assertEquals("하늘은 같은 길이로 겹침", full.crossfadeMs, sky.crossfadeMs)
            assertEquals("가려지는 구간 없음", 0, sky.swapAtMs)
            assertEquals(sky.crossfadeMs, sky.totalMs)
            listOf(0f, 1f, sky.totalMs / 2f, sky.totalMs.toFloat(), 2_000f).forEach { ms ->
                assertEquals("스크림 없음 ($ms ms)", 0f, sceneScrimAlpha(ms, sky), 0f)
            }
            // With nothing hidden, the orb and the stars move over the whole crossfade.
            listOf(0f, .25f, .5f, 1f).forEach { t -> assertEquals(t, sceneRevealProgress(t, sky), 1e-4f) }
        }
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

    @Test fun theCarSunRestsInTheHeroBandOnly() {
        // The full Pleos window (1680 × 1210 dp at 160 dpi, px = dp): the hero leaves (824, 92)–(1152, 478) free, the day disc is
        // .72 × 1210 × .045 = 39.2, and the hero haze ends 300 past the hero column's edge (72 + 720 + 300 = 1092).
        val full = Rect(824f, 92f, 1152f, 478f)
        val disc = 39.2f
        val margin = 24f
        val sun = heroOrbAt(full, disc, margin, preferredY = 1210f * FREE_SKY_ORB_Y, hazeEnd = 1092f)!!
        assertEquals("헤이즈가 끝난 곳, 띠 안", 1052.8f, sun.x, .01f)
        assertEquals("탑보다 높이", 181.5f, sun.y, .01f)
        // A 1.3× font: the 1046-dp page ends the band at 1062, which holds the sun back from the haze's end (48 + 720 + 300).
        assertEquals("띠 끝에서 멈춤", 998.8f, heroOrbAt(Rect(800f, 92f, 1062f, 508f), disc, margin, 181.5f, 1068f)!!.x, .01f)
        assertNull("1400 dp 창의 띠는 48 dp뿐이라 해와 달이 없음", heroOrbAt(Rect(752f, 92f, 800f, 478f), disc, margin, 181.5f, 1092f))
        assertNull("카드 위 하늘이 낮으면 해와 달이 없음", heroOrbAt(full.copy(bottom = 230f), disc, margin, 181.5f, 1092f))
        assertEquals("띠의 위쪽 여백 아래로", 155.2f, heroOrbAt(full, disc, margin, preferredY = 120f, hazeEnd = 1092f)!!.y, .01f)
        // Wherever it rests, the disc and its margin stay inside the band.
        val bands = listOf(full, Rect(800f, 92f, 1062f, 508f), Rect(600f, 40f, 760f, 300f), Rect(824f, 92f, 1400f, 900f), Rect(900f, 200f, 1030f, 330f))
        bands.forEach { band ->
            listOf(0f, 900f, 1092f, 2000f).forEach { hazeEnd ->
                listOf(0f, 120f, 181.5f, 400f).forEach { preferred ->
                    heroOrbAt(band, disc, margin, preferred, hazeEnd)?.let { at ->
                        val room = disc + margin
                        assertTrue("$band $hazeEnd $preferred → $at", at.x - room >= band.left - 1e-3f && at.x + room <= band.right + 1e-3f &&
                            at.y - room >= band.top - 1e-3f && at.y + room <= band.bottom + 1e-3f)
                    }
                }
            }
        }
    }

    @Test fun theSunFadesBeforeACardReachesIt() {
        assertEquals("멈춘 홈에서는 다 보임", 1f, heroSkyAlpha(0f, 200f), 1e-4f)
        assertEquals(.5f, heroSkyAlpha(100f, 200f), 1e-4f)
        assertEquals("카드가 닿을 때 사라짐", 0f, heroSkyAlpha(200f, 200f), 1e-4f)
        assertEquals(0f, heroSkyAlpha(300f, 200f), 1e-4f)
        var last = 1f
        for (scroll in 0..400 step 8) {
            val alpha = heroSkyAlpha(scroll.toFloat(), 200f)
            assertTrue("스크롤할수록 옅어지기만 함 ($scroll)", alpha <= last)
            last = alpha
        }
        assertEquals("여유가 없으면 그리지 않음", 0f, heroSkyAlpha(0f, 0f), 1e-4f)
        assertEquals(0f, heroSkyAlpha(0f, -12f), 1e-4f)
        // The full window: the cards start 24 under the band (478 + 24), the disc and the cloud margin end at 181.5 + 39.2 + 12,
        // so the sun is gone after 269.3 px of scroll, when the cards' top edge reaches it.
        val clearance = 478f + 24f - (181.5f + 39.2f + 12f)
        assertEquals(269.3f, clearance, .01f)
        assertEquals(0f, heroSkyAlpha(clearance, clearance), 1e-4f)
    }

    @Test fun aScrolledPageFadesUnderTheHeaderAndAPageAtRestDoesNot() {
        assertEquals("멈춘 페이지는 첫 줄을 다 보여 줌", 0f, scrollEdgeStrength(0f, 24f), 1e-4f)
        assertEquals(.5f, scrollEdgeStrength(12f, 24f), 1e-4f)
        assertEquals("띠 하나만큼 내려가면 다 흐려짐", 1f, scrollEdgeStrength(400f, 24f), 1e-4f)
        assertEquals(0f, scrollEdgeStrength(10f, 0f), 1e-4f)
        assertEquals("다 흐려진 띠의 맨 위는 비어 있음", 0f, scrollEdgeAlpha(0f, 1f), 1e-4f)
        assertEquals("헤더 바로 밑의 선은 거의 사라짐", .0625f, scrollEdgeAlpha(.25f, 1f), 1e-4f)
        assertEquals(1f, scrollEdgeAlpha(1f, 1f), 1e-4f)
        assertEquals("멈춘 페이지에는 띠가 없음", 1f, scrollEdgeAlpha(0f, 0f), 1e-4f)
        assertEquals(.5f, scrollEdgeAlpha(0f, .5f), 1e-4f)
    }

    @Test fun theDayHazeIsWhiteAndLighterThanTheNightScrim() {
        val day = DaySceneColors
        val night = NightSceneColors
        val carDay = skyScrims(day = true, leftScrim = true, horizonAt = .52f, haze = day.haze, bg = day.bg, heroEdge = .47f, heroFade = .18f)
        val carNight = skyScrims(day = false, leftScrim = true, horizonAt = .52f, haze = night.haze, bg = night.bg)
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
        assertEquals("밤은 바탕색", night.bg, left.stops[0].second.copy(alpha = 1f))
        assertEquals(0f, left.stops[0].first, 1e-3f)
        assertEquals(.92f, left.stops[0].second.alpha, .005f)
        assertEquals("히어로 칼럼의 오른쪽 끝", .47f, left.stops[1].first, 1e-3f)
        assertEquals(.62f, left.stops[1].second.alpha, .005f)
        assertEquals("낮 안개와 같은 곳에서 사라짐", .65f, left.stops[2].first, 1e-3f)
        assertEquals(0f, left.stops[2].second.alpha, .005f)
        // Behind the greeting the night scrim only grows stronger than 2.10's (.92 at 0, .62 at .38, 0 at .74), so moving the moon
        // out of the free sky never costs the greeting any contrast; past the hero edge it thins faster, over the moon's band.
        val before = listOf(0f to .92f, .38f to .62f, .74f to 0f)
        val now = left.stops.map { it.first to it.second.alpha }
        for (i in 0..47) {
            val x = i / 100f
            assertTrue("x = $x", stopAlpha(now, x) >= stopAlpha(before, x) - 1e-4f)
        }
        assertTrue("달이 있는 곳(1053 / 1680)은 덜 어두움", stopAlpha(now, 1053f / 1680f) < stopAlpha(before, 1053f / 1680f))
        assertEquals("낮의 아래 스크림은 더 옅음", .25f, carDay[1].stops[0].second.alpha, .005f)
        assertEquals(.85f, carDay[1].stops[3].second.alpha, .005f)
        assertEquals(.35f, carNight[1].stops[0].second.alpha, .005f)
        assertEquals(.90f, carNight[1].stops[3].second.alpha, .005f)
        assertEquals("아래쪽은 바탕색으로 끝남", day.bg, carDay[1].stops.last().second)
        assertEquals(night.bg, carNight[1].stops.last().second)
        // The phone home (horizon .40) and the sign-in (.46) pass no left scrim and lay their own day haze, so the backdrop adds
        // only its bottom scrim: one white layer over the sky, not two. By night they keep the bottom scrim alone, as built.
        listOf(.40f, .46f).forEach { horizonAt ->
            val phoneDay = skyScrims(day = true, leftScrim = false, horizonAt = horizonAt, haze = day.haze, bg = day.bg)
            assertEquals("휴대폰 홈·로그인 낮: 아래 스크림 하나뿐", 1, phoneDay.size)
            val base = phoneDay[0]
            assertTrue("아래 스크림은 세로", base.vertical)
            assertEquals("위쪽은 옅게만", .25f, base.stops[0].second.alpha, .005f)
            assertEquals("아래쪽은 바탕색으로 끝남", day.bg, base.stops.last().second)
            val sameBase = skyScrims(day = true, leftScrim = true, horizonAt = horizonAt, haze = day.haze, bg = day.bg)[1]
            assertEquals("아래 스크림은 차량과 같음", sameBase.stops, base.stops)
            val phoneNight = skyScrims(day = false, leftScrim = false, horizonAt = horizonAt, haze = night.haze, bg = night.bg)
            assertEquals("휴대폰 홈·로그인 밤: 위 스크림 없음", 1, phoneNight.size)
            assertEquals(.35f, phoneNight[0].stops[0].second.alpha, .005f)
            assertEquals(night.bg, phoneNight[0].stops.last().second)
        }
    }

    @Test fun theDayCloudsKeepToTheHeroBandAndOffTheSun() {
        // The full Pleos window: 1680 × 1210 dp at 160 dpi (px = dp), towers scaled .72, the hero band and the day sun as placed
        // in theCarSunRestsInTheHeroBandOnly.
        val h = 1210f; val ht = h * .72f
        val band = Rect(824f, 92f, 1152f, 478f)
        val hazeEnd = 1092f
        val disc = ht * .045f
        val sun = heroOrbAt(band, disc, HERO_SKY_MARGIN.value, h * FREE_SKY_ORB_Y, hazeEnd)!!
        val clear = disc + SUN_CLOUD_MARGIN.value
        val reach = CLOUD_HAZE_REACH.value
        fun assertDrawable(kept: List<SkyCloud>, inBand: Rect) {
            val boxes = kept.map { heroCloudBox(it, inBand, h, ht) }
            boxes.forEach { b ->
                assertTrue("띠 안: $b", b.left >= inBand.left && b.top >= inBand.top && b.right <= inBand.right && b.bottom <= inBand.bottom)
                assertTrue("헤이즈가 옅어진 뒤: $b", b.left >= hazeEnd - 120f)
                assertTrue("해에서 떨어짐: $b", distanceToBox(sun, b) > disc + 12f)
            }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) assertTrue("구름 $i, $j 는 겹치지 않음", !boxes[i].overlaps(boxes[j]))
        }
        val kept = visibleDayClouds(band, h, ht, hazeEnd, sun, clear, reach)
        assertTrue("구름 둘 이상: ${kept.size}", kept.size >= 2)
        assertDrawable(kept, band)
        // A band that grows downward keeps every cloud where it was and may reveal the next one; it never moves one.
        val taller = band.copy(bottom = band.bottom + 200f)
        val more = visibleDayClouds(taller, h, ht, hazeEnd, sun, clear, reach)
        assertTrue("같은 구름이 남음", more.containsAll(kept))
        kept.forEach { assertEquals("같은 자리", heroCloudBox(it, band, h, ht), heroCloudBox(it, taller, h, ht)) }
        assertDrawable(more, taller)
        // Without the sun the filter keeps at least as many: it only ever removes.
        assertTrue(visibleDayClouds(band, h, ht, hazeEnd, null, clear, reach).size >= kept.size)
        assertTrue("띠가 없으면 구름도 없음", visibleDayClouds(Rect(824f, 92f, 824f, 92f), h, ht, hazeEnd, null, clear, reach).isEmpty())
        assertEquals("상자 안의 점은 거리 0", 0f, distanceToBox(Offset(5f, 5f), Rect(0f, 0f, 10f, 10f)), 1e-4f)
    }

    /** The alpha of a horizontal scrim at [x], read linearly between its (fraction, alpha) stops. */
    private fun stopAlpha(stops: List<Pair<Float, Float>>, x: Float): Float {
        for (i in 0 until stops.lastIndex) {
            val (x0, a0) = stops[i]
            val (x1, a1) = stops[i + 1]
            if (x <= x1) return if (x <= x0) a0 else a0 + (a1 - a0) * (x - x0) / (x1 - x0)
        }
        return stops.last().second
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
        // The selection: an opaque fill (the glow at .12 over white), so a selected chip, segment or row reads the same on glass,
        // on a rail sheet or straight over the sky; the ring and the glow label are measured on the fill itself (spec: 5.15).
        assertEquals("낮 선택 채움은 불투명", 1f, day.selectionFill.alpha, 0f)
        assertEquals(Color(0xFFE2EDF0), day.selectionFill)
        listOf(day.glass, day.rail, day.bg, DaySkyTop).forEach { under ->
            val selected = day.selectionFill.compositeOver(under)
            assertTrue("selection ring on the selection fill over $under", contrast(day.selectionRing, selected) >= 3.0)
            assertTrue("glow label on the selection fill over $under", contrast(day.glow, selected) >= 4.5)
        }
        assertEquals("spec: glow on fill 5.15", 5.15, contrast(day.glow, day.selectionFill), .01)
        assertTrue("textMuted on the rail", contrast(day.textMuted, day.rail) >= 4.5)
        // Only text, textSecondary and brand sit on the day haze. Its weakest point under text: the car hero haze at .76 (the
        // stop at the hero column's edge) and the sign-in haze at .84, each over the top of the day sky.
        val heroHaze = skyScrims(day = true, leftScrim = true, horizonAt = .52f, haze = day.haze, bg = day.bg)[0].stops[1].second
        assertEquals(.76f, heroHaze.alpha, .005f)
        val hazes = listOf(heroHaze.compositeOver(DaySkyTop), day.haze.copy(alpha = .84f).compositeOver(DaySkyTop))
        assertNear("히어로 안개 ≈ #D5E7F9", Color(0xFFD5E7F9), hazes[0])
        assertNear("로그인 안개 ≈ #E3EFFB", Color(0xFFE3EFFB), hazes[1])
        (hazes + listOf(Color(0xFFD5E7F9), Color(0xFFE3EFFB))).forEach { haze ->
            listOf(day.text, day.textSecondary, day.brand).forEach { color -> assertTrue("$color on the haze $haze", contrast(color, haze) >= 4.5) }
        }
        val night = NightSceneColors
        listOf(night.text, night.textSecondary, night.textMuted, night.glow, night.warn, night.brand).forEach { color ->
            assertTrue("$color on night glass", contrast(color, night.glass) >= 4.5)
        }
        // The night outline is the only edge of an empty text field: 3:1 on night glass, and on the night background and rail.
        assertEquals(Color(0xFF6E7A8F), night.outline)
        listOf(night.glass, night.bg, night.rail).forEach { under -> assertTrue("night outline on $under", contrast(night.outline, under) >= 3.0) }
    }

    /** Two opaque colours within one step of 255 per channel (a composite rounds either way). */
    private fun assertNear(message: String, expected: Color, actual: Color) {
        listOf(expected.red to actual.red, expected.green to actual.green, expected.blue to actual.blue).forEach { (e, a) ->
            assertEquals(message, e * 255f, a * 255f, 1.01f)
        }
    }

    @Test fun koreanBreaksBetweenPhrasesNeverInsideAWord() {
        listOf(false, true).forEach { compact ->
            val t = typographyFor(compact)
            val headings = listOf(t.displayLarge, t.displayMedium, t.displaySmall, t.headlineLarge, t.headlineMedium, t.headlineSmall,
                t.titleLarge, t.titleMedium, t.titleSmall)
            val text = listOf(t.bodyLarge, t.bodyMedium, t.bodySmall, t.labelLarge, t.labelMedium, t.labelSmall)
            (headings + text).forEach { style ->
                assertEquals("어절 단위로 줄바꿈 ('맞춰'/'요.' 없음)", LineBreak.WordBreak.Phrase, style.lineBreak.wordBreak)
                assertEquals(LineBreak.Strictness.Strict, style.lineBreak.strictness)
                assertEquals("한국어 규칙", LocaleList("ko-KR"), style.localeList)
            }
            headings.forEach { assertEquals("제목은 줄 길이를 고르게", LineBreak.Strategy.Balanced, it.lineBreak.strategy) }
            text.forEach { assertEquals("본문은 단순 줄바꿈", LineBreak.Strategy.Simple, it.lineBreak.strategy) }
        }
    }

    @Test fun layoutTokensGrowWithTheWindow() {
        assertEquals(WidthClass.COMPACT, carLayout(700.dp).widthClass)
        assertEquals(WidthClass.MEDIUM, carLayout(1000.dp).widthClass)
        assertEquals(WidthClass.EXPANDED, carLayout(1200.dp).widthClass)
        assertEquals(WidthClass.FULL, carLayout(1680.dp).widthClass)
        assertTrue(carLayout(1680.dp).gutter > carLayout(1100.dp).gutter)
        assertEquals("가장 넓은 창에서도 레일은 거터 사이를 채움", Dp.Infinity, carLayout(1680.dp).contentMax)
        // A 1.3x font on the full window: 1680 / 1.3 = 1292 dp is EXPANDED, and its rail fills the window too (1584 dp between
        // the gutters); carColumns splits it into the page and the 지금 column.
        val largeFont = carLayout(1680.dp / 1.3f)
        assertEquals("1.3배 글꼴은 EXPANDED", WidthClass.EXPANDED, largeFont.widthClass)
        assertEquals("큰 글꼴에서도 레일은 창을 채움", Dp.Infinity, largeFont.contentMax)
        assertTrue(largeFont.startAnchored)
        assertEquals("좁은 창은 꽉 채움", Dp.Infinity, carLayout(1000.dp).contentMax)
        assertEquals(Dp.Infinity, carLayout(700.dp).contentMax)
        assertEquals("낮은 창", HeightClass.SHORT, carLayout(1680.dp, 500.dp).heightClass)
        // startAnchored is the car's flag (no ripple, 56-dp dialog buttons, the .98 press) in every class, and never the phone's.
        listOf(700.dp, 1000.dp, 1200.dp, 1680.dp).forEach { assertTrue("차량 $it", carLayout(it).startAnchored) }
        listOf(411.dp, 800.dp, 1024.dp).forEach { assertTrue("휴대폰 $it", !phoneLayout(it).startAnchored) }
        assertEquals(WidthClass.COMPACT, phoneLayout(411.dp).widthClass)
        assertEquals(WidthClass.MEDIUM, phoneLayout(800.dp).widthClass)
        assertEquals(WidthClass.EXPANDED, phoneLayout(1024.dp).widthClass)
    }

    @Test fun carColumnsSplitTheRailIntoPageAndNow() {
        // The full window: 1680 less two 72-dp gutters is a 1536-dp rail, split 1112 · 32 · 392.
        val full = carColumns(carLayout(1680.dp), 1536.dp, 1f)
        assertEquals(CarColumns(page = 1112.dp, side = 392.dp, gap = 32.dp), full)
        assertTrue(full.split)
        assertEquals("1400 dp 창: 페이지 832", 832f, carColumns(carLayout(1400.dp), 1256.dp, 1f).page.value, .01f)
        // A 1.3x font: EXPANDED (gutter 48, gap 28) on the 1584-dp rail; the column grows with the font.
        val large = carColumns(carLayout(1680.dp / 1.3f), 1584.dp, 1.3f)
        assertEquals("지금 열 392 × 1.3", 509.6f, large.side.value, .01f)
        assertEquals(28f, large.gap.value, .01f)
        assertEquals("페이지 1046 (805 dp 상당)", 1046.4f, large.page.value, .01f)
        val edge = carColumns(carLayout(1296.dp), 1200.dp, 1f)
        assertTrue("페이지가 780이면 나뉨", edge.split)
        assertEquals(780f, edge.page.value, .01f)
        assertTrue("779면 하나", !carColumns(carLayout(1295.dp), 1199.dp, 1f).split)
        // No column on a MEDIUM or SHORT window, on the phone's tokens, or where the font leaves the page too little (1.5x: 697):
        // the page takes the whole rail.
        listOf(Triple(carLayout(1000.dp), 936.dp, 1f), Triple(carLayout(1680.dp, 500.dp), 1536.dp, 1f), Triple(phoneLayout(800.dp), 736.dp, 1f),
            Triple(carLayout(1680.dp / 1.5f), 1584.dp, 1.5f)).forEach { (layout, rail, font) ->
            val columns = carColumns(layout, rail, font)
            assertTrue("${layout.widthClass} ${layout.heightClass} ×$font: 하나", !columns.split)
            assertEquals(rail, columns.page)
        }
        // Whenever it splits, page + gap + 지금 is the rail.
        listOf(Triple(1680.dp, 1536.dp, 1f), Triple(1400.dp, 1256.dp, 1f), Triple(1680.dp / 1.3f, 1584.dp, 1.3f), Triple(1296.dp, 1200.dp, 1f),
            Triple(1500.dp / 1.15f, 1404.dp, 1.15f)).forEach { (window, rail, font) ->
            val columns = carColumns(carLayout(window), rail, font)
            assertTrue("$window ×$font", columns.split)
            assertEquals(rail.value, (columns.page + columns.gap + columns.side).value, .01f)
        }
    }

    @Test fun theSettingsLineMatchesTheChoice() {
        assertTrue(sceneModeHint(SceneMode.AUTO, ScenePhase.DAY).startsWith("지금은 낮"))
        assertTrue(sceneModeHint(SceneMode.AUTO, ScenePhase.NIGHT).contains("해가 뜨면"))
        assertEquals("항상 밝은 낮 화면을 써요", sceneModeHint(SceneMode.DAY, ScenePhase.NIGHT))
        // Dusk covers the half hour around sunrise too. Ten minutes before sunrise and before sunset are both dusk, but only the
        // evening one turns to night; SceneModeCard passes morning = hour < 12. November: 07:10 · 17:25; June: 05:15 · 19:55.
        listOf(at(11, 7, 0), at(6, 5, 5)).forEach { dawn ->
            assertEquals("$dawn", ScenePhase.DUSK, scenePhaseAt(dawn))
            assertEquals("지금은 해 뜰 무렵이에요 · 곧 낮 화면으로 바뀌어요", sceneModeHint(SceneMode.AUTO, scenePhaseAt(dawn), morning = dawn.hour < 12))
        }
        listOf(at(11, 17, 15), at(6, 19, 45)).forEach { evening ->
            assertEquals("$evening", ScenePhase.DUSK, scenePhaseAt(evening))
            assertEquals("지금은 해 질 무렵이에요 · 곧 밤 화면으로 바뀌어요", sceneModeHint(SceneMode.AUTO, scenePhaseAt(evening), morning = evening.hour < 12))
        }
        assertEquals("고정하면 아침이어도 그대로", "항상 어두운 밤 화면을 써요", sceneModeHint(SceneMode.NIGHT, ScenePhase.DUSK, morning = true))
        // While the car's light sensor holds the night scene, the line says why (in place of the clock's line, which would say
        // '지금은 낮 화면이에요' over a night screen). Only under 자동: a 낮 or 밤 choice ignores the sensor.
        assertEquals("지금은 터널·주차장이라 밤 화면이에요 · 밝아지면 낮 화면으로 돌아가요", sceneModeHint(SceneMode.AUTO, ScenePhase.NIGHT, vetoed = true))
        assertEquals(SCENE_VETO_HINT, sceneModeHint(SceneMode.AUTO, ScenePhase.NIGHT, morning = true, vetoed = true))
        assertEquals("항상 밝은 낮 화면을 써요", sceneModeHint(SceneMode.DAY, ScenePhase.DAY, vetoed = true))
        assertEquals("지금은 밤 화면이에요 · 해가 뜨면 낮 화면으로 바뀌어요", sceneModeHint(SceneMode.AUTO, ScenePhase.NIGHT, vetoed = false))
        // Short and polite: every line ends in -요.
        listOf(SCENE_VETO_HINT, sceneModeHint(SceneMode.AUTO, ScenePhase.DAY), sceneModeHint(SceneMode.NIGHT, ScenePhase.NIGHT)).forEach {
            assertTrue(it, it.endsWith("요"))
        }
    }
}
