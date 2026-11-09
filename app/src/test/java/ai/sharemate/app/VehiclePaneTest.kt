package ai.sharemate.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehiclePaneTest {
    @Test fun onlyFreshAvailableSignalsInTheCurrentDataModeAppearAsLive() {
        val now = 100_000L
        val sample = DriveSample(now - 45_000, GearState.PARK, 12345.6)
        val state = TripLogState(live = sample, signal = SignalStatus.READY)
        assertTrue(vehicleSampleFresh(state, now))
        assertFalse(vehicleSampleFresh(state, now + 1))
        assertFalse(vehicleSampleFresh(state.copy(live = sample.copy(at = now + 1)), now))
        assertFalse(vehicleSampleFresh(state.copy(live = sample.copy(demo = true)), now))
        assertFalse(vehicleSampleFresh(state.copy(live = sample.copy(gear = GearState.UNKNOWN)), now))
        assertFalse(vehicleSampleFresh(state.copy(live = sample.copy(odometerKm = Double.NaN)), now))
        assertFalse(vehicleSampleFresh(state.copy(signal = SignalStatus.DENIED), now))
        assertFalse(vehicleSampleFresh(state.copy(live = null), now))
    }

    @Test fun theVehiclePaneFollowsTheWindowClassAndItsOwnWidth() {
        val twoUp = VehiclePaneLayout(twoColumn = true, drawingHeight = 420.dp, twoUp = true)
        val stacked = VehiclePaneLayout(twoColumn = false, drawingHeight = 260.dp, twoUp = false)
        // FULL and EXPANDED: the car card beside the info column with the 420 drawing, the four cards two up.
        assertEquals(twoUp, vehiclePaneLayout(carLayout(1680.dp, 1200.dp), 1200.dp))
        assertEquals(twoUp, vehiclePaneLayout(carLayout(1200.dp, 800.dp), 1104.dp))
        // Beside the 지금 column the pane is the 1112-dp page; a 1.3x font's 1046-dp page is 805 dp of effective width, so it stacks.
        assertEquals("FULL의 1112 페이지", twoUp, vehiclePaneLayout(carLayout(1680.dp, 1200.dp), 1112.dp, 1f))
        assertEquals("1.3배 글꼴은 한 열, 그림 260", stacked, vehiclePaneLayout(carLayout(1680.dp / 1.3f, 1200.dp), 1046.dp, 1.3f))
        assertEquals("1400 dp 창의 832 페이지", stacked, vehiclePaneLayout(carLayout(1400.dp, 1200.dp), 832.dp))
        // A split window can carry an EXPANDED class while the pane itself is too narrow for two columns.
        assertEquals(stacked, vehiclePaneLayout(carLayout(1200.dp, 800.dp), 900.dp))
        // MEDIUM and COMPACT: one column with the 260 drawing, the cards stacked.
        assertEquals(stacked, vehiclePaneLayout(carLayout(900.dp, 800.dp), 836.dp))
        assertEquals(stacked, vehiclePaneLayout(carLayout(700.dp, 800.dp), 660.dp))
        // A SHORT window drops the drawing and keeps the plate and the odometer, in either arrangement.
        val shortWide = vehiclePaneLayout(carLayout(1680.dp, 500.dp), 1200.dp)
        assertTrue(shortWide.twoColumn)
        assertEquals(0.dp, shortWide.drawingHeight)
        val shortNarrow = vehiclePaneLayout(carLayout(900.dp, 500.dp), 836.dp)
        assertFalse(shortNarrow.twoColumn)
        assertEquals(0.dp, shortNarrow.drawingHeight)
    }
}
