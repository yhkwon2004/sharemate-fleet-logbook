package ai.sharemate.app

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
}
