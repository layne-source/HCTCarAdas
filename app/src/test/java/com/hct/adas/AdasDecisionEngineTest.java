package com.hct.adas;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdasDecisionEngineTest {
    @Test
    public void emitsForwardCollisionWarningAfterThreeDangerFrames() {
        AdasDecisionEngine engine = new AdasDecisionEngine();

        assertFalse(engine.update(observation(0L, 50.0, 20.0, 10.0, 1000.0, true))
                .events().contains(AdasDecisionEngine.Alert.FCW));
        assertFalse(engine.update(observation(100L, 50.0, 20.0, 10.0, 1000.0, true))
                .events().contains(AdasDecisionEngine.Alert.FCW));
        assertTrue(engine.update(observation(200L, 50.0, 20.0, 10.0, 1000.0, true))
                .events().contains(AdasDecisionEngine.Alert.FCW));
    }

    @Test
    public void exposesHeadwayWarningWhileTargetStaysTooClose() {
        AdasDecisionEngine engine = new AdasDecisionEngine();

        assertTrue(engine.update(observation(0L, 40.0, 7.0, 0.0, 1000.0, true)).headwayWarning());
        assertFalse(engine.update(observation(100L, 40.0, 9.0, 0.0, 1000.0, true)).headwayWarning());
    }

    @Test
    public void emitsLeadVehicleStartAfterStationaryWaitAndMovement() {
        AdasDecisionEngine engine = new AdasDecisionEngine();

        engine.update(observation(0L, 0.0, 6.0, 0.0, 1000.0, true));
        engine.update(observation(2_999L, 0.0, 6.0, 0.0, 1000.0, true));
        assertFalse(engine.update(observation(3_000L, 0.0, 6.0, 0.0, 1000.0, true))
                .events().contains(AdasDecisionEngine.Alert.LVSA));
        assertTrue(engine.update(observation(3_100L, 0.0, 8.6, 0.0, 800.0, true))
                .events().contains(AdasDecisionEngine.Alert.LVSA));
    }

    @Test
    public void emitsLaneDepartureOnlyAfterValidSpeedAndContinuousOffset() {
        AdasDecisionEngine engine = new AdasDecisionEngine();
        AdasDecisionEngine.Observation observation = observation(0L, 60.0,
                20.0, 0.0, 1000.0, false);
        AdasDecisionEngine.LaneObservation lane = new AdasDecisionEngine.LaneObservation(
                0.16, 0.8, true);

        assertFalse(engine.update(observation, lane).events().contains(AdasDecisionEngine.Alert.LDW));
        assertFalse(engine.update(observation(500L, 60.0, 20.0, 0.0, 1000.0, false), lane)
                .events().contains(AdasDecisionEngine.Alert.LDW));
        assertTrue(engine.update(observation(1_000L, 60.0, 20.0, 0.0, 1000.0, false), lane)
                .events().contains(AdasDecisionEngine.Alert.LDW));
    }

    private static AdasDecisionEngine.Observation observation(
            long timestamp,
            double egoSpeedKmh,
            double distanceMeters,
            double closingSpeedMps,
            double targetArea,
            boolean visible) {
        return new AdasDecisionEngine.Observation(
                timestamp, egoSpeedKmh, distanceMeters, closingSpeedMps, targetArea, visible);
    }
}
