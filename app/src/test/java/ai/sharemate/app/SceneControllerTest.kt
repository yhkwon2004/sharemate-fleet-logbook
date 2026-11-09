package ai.sharemate.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/** The scene's control, outside composition: the car's light-sensor veto, the process-level memory, and when a change may start. */
class SceneControllerTest {
    @Before fun forget() = SceneController.reset()
    @After fun tidy() = SceneController.reset()

    private val s = 1_000L
    private val min = 60_000L

    /** One car's light sensor stepped through time (ms) under a fixed clock band and mode. */
    private class Sensor(val clock: ScenePhase = ScenePhase.DAY, val mode: SceneMode = SceneMode.AUTO) {
        var veto = SensorVeto()
        fun at(t: Long, night: Boolean): SensorVetoStep = sensorVetoStep(veto, t, night, clock, mode).also { veto = it.veto }
    }

    @Test fun aTunnelTurnsTheDayToNightAfterNinetySecondsAndBackNinetySecondsAfterIt() {
        val sensor = Sensor()
        val first = sensor.at(0, night = true)
        assertFalse("바로 바뀌지 않음", first.vetoing)
        assertEquals("90초 뒤에 다시 봄", 90 * s, first.wakeAt)
        assertFalse(sensor.at(89 * s, night = true).vetoing)
        val held = sensor.at(90 * s, night = true)
        assertTrue("90초 동안 밤이면 밤 화면", held.vetoing)
        assertNull("붙잡는 동안 더 볼 것 없음", held.wakeAt)
        // Out of the tunnel: the night screen stays 90 s more, then lets go, even though it took hold only 110 s before.
        val clear = sensor.at(200 * s, night = false)
        assertTrue("밝아져도 90초는 그대로", clear.vetoing)
        assertEquals(290 * s, clear.wakeAt)
        assertTrue(sensor.at(289 * s, night = false).vetoing)
        val released = sensor.at(290 * s, night = false)
        assertFalse("밝아지고 90초 뒤 낮 화면", released.vetoing)
        assertNull(released.wakeAt)
        assertEquals("놓은 때가 마지막 전환", 290 * s, released.veto.lastFlipAt)
    }

    @Test fun aShortShadowNeverFlipsTheScene() {
        val sensor = Sensor()
        sensor.at(0, night = true)
        assertFalse(sensor.at(60 * s, night = false).vetoing)
        // A new run of night counts its 90 s from the start.
        val again = sensor.at(100 * s, night = true)
        assertEquals("다시 90초", 190 * s, again.wakeAt)
        assertFalse(sensor.at(189 * s, night = true).vetoing)
        assertTrue(sensor.at(190 * s, night = true).vetoing)
        // While it holds, a bright spell shorter than 90 s does not let go, and the release clock starts again after it.
        sensor.at(200 * s, night = false)
        assertTrue(sensor.at(250 * s, night = true).vetoing)
        assertTrue(sensor.at(401 * s, night = false).vetoing)
        assertTrue(sensor.at(490 * s, night = false).vetoing)
        assertFalse(sensor.at(491 * s, night = false).vetoing)
    }

    @Test fun theSensorTakesHoldAtMostOnceInTenMinutes() {
        val sensor = Sensor()
        sensor.at(0, night = true)
        assertTrue("1:30 밤 화면 (첫 전환)", sensor.at(90 * s, night = true).vetoing)
        sensor.at(120 * s, night = false)
        assertFalse("3:30 낮 화면 (놓는 것은 미루지 않음)", sensor.at(210 * s, night = false).vetoing)
        // Another tunnel at 4:00 is dark for 90 s by 5:30, but the last flip was at 3:30: the night screen waits until 13:30.
        val next = sensor.at(240 * s, night = true)
        assertEquals("마지막 전환 10분 뒤", 210 * s + 10 * min, next.wakeAt)
        assertFalse(sensor.at(330 * s, night = true).vetoing)
        assertFalse(sensor.at(210 * s + 10 * min - 1, night = true).vetoing)
        assertTrue("10분이 지나면 다시 밤 화면", sensor.at(210 * s + 10 * min, night = true).vetoing)
        // A tunnel left before the wait ran out never flips the screen.
        val quick = Sensor()
        quick.at(0, true); quick.at(90 * s, true); quick.at(100 * s, false); quick.at(190 * s, false)
        quick.at(200 * s, true)
        assertFalse(quick.at(500 * s, true).vetoing)
        assertFalse(quick.at(600 * s, false).vetoing)
        assertNull("낮이면 기다릴 것 없음", quick.at(601 * s, false).wakeAt)
    }

    @Test fun onlyAutoInADayOrDuskBandShowsTheVeto() {
        val dusk = Sensor(clock = ScenePhase.DUSK)
        dusk.at(0, true)
        assertTrue("해 질 무렵도 밤 화면으로", dusk.at(90 * s, true).vetoing)
        // The night band is night already; the sensor is still followed, so a dark dawn stays night.
        val night = Sensor(clock = ScenePhase.NIGHT)
        night.at(0, true)
        val atNight = night.at(90 * s, true)
        assertTrue("밤 구간에서도 센서는 따라감", atNight.veto.forcing)
        assertFalse("이미 밤이라 바꿀 것 없음", atNight.vetoing)
        assertTrue("어두운 새벽은 밤 화면 유지", sensorVetoes(atNight.veto, ScenePhase.DUSK, SceneMode.AUTO))
        // 낮 and 밤 ignore it.
        val noon = LocalDateTime.of(2026, 11, 15, 12, 0)
        listOf(SceneMode.DAY, SceneMode.NIGHT).forEach { pinned ->
            val sensor = Sensor(clock = scenePhaseAt(noon, pinned), mode = pinned)
            sensor.at(0, true)
            val step = sensor.at(90 * s, true)
            assertTrue(step.veto.forcing)
            assertFalse("$pinned 고정은 센서를 따르지 않음", step.vetoing)
        }
        // Back to 자동 while the sensor holds: the night scene.
        assertTrue(sensorVetoes(SensorVeto(forcing = true), ScenePhase.DAY, SceneMode.AUTO))
        assertFalse(sensorVetoes(SensorVeto(forcing = false), ScenePhase.DAY, SceneMode.AUTO))
    }

    @Test fun theControllerKeepsTheVetoForTheProcess() {
        val first = SceneController.stepVeto(0, sensorNight = true, clockPhase = ScenePhase.DAY, mode = SceneMode.AUTO)
        assertEquals(90 * s, first.wakeAt)
        // A theme composed again (a recreated activity) carries on the same 90 s instead of starting over.
        assertTrue(SceneController.stepVeto(90 * s, sensorNight = true, clockPhase = ScenePhase.DAY, mode = SceneMode.AUTO).vetoing)
        assertTrue(SceneController.currentVeto().forcing)
        SceneController.reset()
        assertFalse(SceneController.currentVeto().forcing)
    }

    @Test fun aRecreatedScreenStartsOnWhatTheLastOneShowed() {
        // Nothing remembered yet: the phase asked for.
        assertEquals(ScenePhase.DAY, SceneController.seed(compact = false, requested = ScenePhase.DAY, hold = true))
        // Driving at noon, the car shows and holds day. The clock turns to dusk (the night palette) during the drive and a
        // day/night flip or a resize recreates the activity: the new theme starts on day, not on the clock.
        SceneController.record(compact = false, phase = ScenePhase.DAY, holding = ScenePhase.DAY)
        assertEquals(ScenePhase.DAY, SceneController.held(compact = false))
        assertEquals("운전 중 다시 만들어져도 낮 그대로", ScenePhase.DAY, SceneController.seed(compact = false, requested = ScenePhase.DUSK, hold = true))
        // Parked, a fresh screen starts on the phase asked for.
        assertEquals("주차 중에는 요청대로", ScenePhase.DUSK, SceneController.seed(compact = false, requested = ScenePhase.DUSK, hold = false))
        // Last seen parked (nothing held): a car screen that starts held (the restriction service has not answered yet) keeps
        // what was last on screen until it knows.
        SceneController.record(compact = false, phase = ScenePhase.NIGHT, holding = null)
        assertNull(SceneController.held(compact = false))
        assertEquals(ScenePhase.NIGHT, SceneController.lastShown(compact = false))
        assertEquals(ScenePhase.NIGHT, SceneController.seed(compact = false, requested = ScenePhase.DAY, hold = true))
        // The phone keeps its own memory and never holds.
        assertNull(SceneController.lastShown(compact = true))
        assertEquals(ScenePhase.DAY, SceneController.seed(compact = true, requested = ScenePhase.DAY, hold = false))
    }

    @Test fun theSeedPrefersTheHeldPhaseThenTheLastShown() {
        assertEquals(ScenePhase.DAY, sceneSeed(ScenePhase.NIGHT, hold = true, held = ScenePhase.DAY, lastShown = ScenePhase.DUSK))
        assertEquals(ScenePhase.DUSK, sceneSeed(ScenePhase.NIGHT, hold = true, held = null, lastShown = ScenePhase.DUSK))
        assertEquals(ScenePhase.NIGHT, sceneSeed(ScenePhase.NIGHT, hold = true, held = null, lastShown = null))
        assertEquals(ScenePhase.NIGHT, sceneSeed(ScenePhase.NIGHT, hold = false, held = ScenePhase.DAY, lastShown = ScenePhase.DAY))
    }

    @Test fun aClockChangeWaitsForAQuietMoment() {
        // The driver's own tap: at once, unless a dialog, a sheet or a menu holds the window.
        assertEquals(0L, sceneChangeWait(byTap = true, windowFocused = true, fieldFocused = true, touching = true, idleMs = 0))
        assertNull("창이 가려져 있으면 탭도 기다림", sceneChangeWait(byTap = true, windowFocused = false, fieldFocused = false, touching = false, idleMs = 60_000))
        // The clock and the sensor: the window in front, no field focused, no touch for 3 s.
        assertNull(sceneChangeWait(byTap = false, windowFocused = false, fieldFocused = false, touching = false, idleMs = 60_000))
        assertNull("입력 중에는 기다림", sceneChangeWait(byTap = false, windowFocused = true, fieldFocused = true, touching = false, idleMs = 60_000))
        assertEquals("누르는 동안은 짧게 다시 봄", SCENE_TOUCH_POLL_MS, sceneChangeWait(byTap = false, windowFocused = true, fieldFocused = false, touching = true, idleMs = 500))
        assertEquals("마지막 터치 1초 뒤면 2초 더", 2_000L, sceneChangeWait(byTap = false, windowFocused = true, fieldFocused = false, touching = false, idleMs = 1_000))
        assertEquals("3초 조용하면 바로", 0L, sceneChangeWait(byTap = false, windowFocused = true, fieldFocused = false, touching = false, idleMs = SCENE_QUIET_MS))
        assertEquals(0L, sceneChangeWait(byTap = false, windowFocused = true, fieldFocused = false, touching = false, idleMs = 90_000))
        // A press that never heard its lift stops holding the change back after 10 s.
        assertEquals(0L, sceneChangeWait(byTap = false, windowFocused = true, fieldFocused = false, touching = true, idleMs = SCENE_TOUCH_STALE_MS))
    }
}
