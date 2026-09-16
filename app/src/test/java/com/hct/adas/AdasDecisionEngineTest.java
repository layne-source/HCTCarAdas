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
    public void emitsCriticalHeadwayWarningWhenVeryClose() {
        AdasDecisionEngine engine = new AdasDecisionEngine();

        // 4.0m <= 4.5m critical threshold, not pulling away
        AdasDecisionEngine.Decision decision = engine.update(
                observation(0L, 25.0, 4.0, 0.0, 1000.0, true));
        assertTrue(decision.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL));
        assertTrue(decision.headwayWarning());

        // Cooldown active, next frame in cooldown does not emit audio event again
        AdasDecisionEngine.Decision cooldown = engine.update(
                observation(1_000L, 25.0, 4.0, 0.0, 1000.0, true));
        assertFalse(cooldown.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL));
        assertTrue(cooldown.headwayWarning());
    }

    @Test
    public void doesNotChimeCriticalHeadwayWhileEgoVehicleIsStationary() {
        AdasDecisionEngine engine = new AdasDecisionEngine();
        AdasDecisionEngine.Decision decision = engine.update(
                observation(0L, 0.0, 4.0, 0.0, 1000.0, true));
        assertFalse(decision.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL));
        assertTrue(decision.headwayWarning());
    }

    @Test
    public void emitsLeadVehicleStartAfterStationaryWaitAndMovement() {
        AdasDecisionEngine engine = new AdasDecisionEngine();

        engine.update(observation(0L, 0.0, 6.0, 0.0, 1000.0, true));
        engine.update(observation(2_999L, 0.0, 6.0, 0.0, 1000.0, true));
        assertFalse(engine.update(observation(3_000L, 0.0, 6.0, 0.0, 1000.0, true))
                .events().contains(AdasDecisionEngine.Alert.LVSA));
        assertFalse(engine.update(observation(3_100L, 0.0, 8.6, 0.0, 800.0, true))
                .events().contains(AdasDecisionEngine.Alert.LVSA));
        assertTrue(engine.update(observation(3_200L, 0.0, 9.0, 0.0, 760.0, true))
                .events().contains(AdasDecisionEngine.Alert.LVSA));
    }

    @Test
    public void resetClearsPreviousStationaryVehicleState() {
        AdasDecisionEngine engine = new AdasDecisionEngine();
        engine.update(observation(0L, 0.0, 6.0, 0.0, 1000.0, true));
        engine.reset();

        engine.update(observation(3_000L, 0.0, 6.0, 0.0, 1000.0, true));
        assertFalse(engine.update(observation(3_100L, 0.0, 8.6, 0.0, 800.0, true))
                .events().contains(AdasDecisionEngine.Alert.LVSA));
    }

    @Test
    public void resetClearsLaneDepartureTimerAndCooldown() {
        AdasDecisionEngine engine = new AdasDecisionEngine();
        AdasDecisionEngine.LaneObservation lane = new AdasDecisionEngine.LaneObservation(
                0.16, 0.8, true);
        AdasDecisionEngine.Observation moving = observation(
                0L, 60.0, Double.NaN, 0.0, 0.0, false);

        engine.update(moving, lane);
        engine.update(observation(900L, 60.0, Double.NaN, 0.0, 0.0, false), lane);
        engine.reset();

        assertFalse(engine.update(observation(1_000L, 60.0, Double.NaN,
                0.0, 0.0, false), lane).events()
                .contains(AdasDecisionEngine.Alert.LDW));
        assertTrue(engine.update(observation(2_000L, 60.0, Double.NaN,
                0.0, 0.0, false), lane).events()
                .contains(AdasDecisionEngine.Alert.LDW));
    }

    @Test
    public void targetResetPreservesLaneTimerAndAlertCooldown() {
        AdasDecisionEngine engine = new AdasDecisionEngine();
        AdasDecisionEngine.LaneObservation lane = new AdasDecisionEngine.LaneObservation(
                0.16, 0.8, true);

        engine.update(observation(0L, 60.0, 20.0, 10.0, 1000.0, true), lane);
        engine.update(observation(900L, 60.0, 20.0, 10.0, 1000.0, true), lane);
        assertTrue(engine.update(observation(1_000L, 60.0, 20.0, 10.0, 1000.0, true), lane)
                .events().contains(AdasDecisionEngine.Alert.FCW));

        engine.resetTargetState();

        // Target reassociation must not replay FCW during its cooldown or lose LDW continuity.
        assertFalse(engine.update(observation(1_100L, 60.0, 20.0, 10.0, 1000.0, true), lane)
                .events().contains(AdasDecisionEngine.Alert.FCW));
        assertFalse(engine.update(observation(1_200L, 60.0, 20.0, 10.0, 1000.0, true), lane)
                .events().contains(AdasDecisionEngine.Alert.FCW));
        assertFalse(engine.update(observation(1_300L, 60.0, 20.0, 10.0, 1000.0, true), lane)
                .events().contains(AdasDecisionEngine.Alert.FCW));
        // LDW fired at 1,000ms and its 6s cooldown survives target reassociation.
        assertFalse(engine.update(observation(2_000L, 60.0, 20.0, 10.0, 1000.0, true), lane)
                .events().contains(AdasDecisionEngine.Alert.LDW));
        assertTrue(engine.update(observation(7_001L, 60.0, 20.0, 10.0, 1000.0, true), lane)
                .events().contains(AdasDecisionEngine.Alert.LDW));
    }

    @Test
    public void targetResetPreservesLaneDepartureTimerWhenNoAlertHasFired() {
        AdasDecisionEngine engine = new AdasDecisionEngine();
        AdasDecisionEngine.LaneObservation lane = new AdasDecisionEngine.LaneObservation(
                0.16, 0.8, true);

        engine.update(observation(0L, 60.0, 20.0, 0.0, 1000.0, false), lane);
        engine.update(observation(900L, 60.0, 20.0, 0.0, 1000.0, false), lane);
        engine.resetTargetState();

        assertTrue(engine.update(observation(1_000L, 60.0, 20.0, 0.0, 1000.0, false), lane)
                .events().contains(AdasDecisionEngine.Alert.LDW));
    }

    @Test
    public void fullResetClearsAlertCooldownsAndLaneTimer() {
        AdasDecisionEngine engine = new AdasDecisionEngine();
        AdasDecisionEngine.LaneObservation lane = new AdasDecisionEngine.LaneObservation(
                0.16, 0.8, true);

        engine.update(observation(0L, 60.0, 4.0, 10.0, 1000.0, true), lane);
        engine.update(observation(900L, 60.0, 4.0, 10.0, 1000.0, true), lane);
        AdasDecisionEngine.Decision fired = engine.update(
                observation(1_000L, 60.0, 4.0, 10.0, 1000.0, true), lane);
        assertTrue(fired.events().contains(AdasDecisionEngine.Alert.FCW));
        assertTrue(fired.events().contains(AdasDecisionEngine.Alert.LDW));

        engine.reset();
        AdasDecisionEngine.Decision afterReset = engine.update(
                observation(1_000L, 60.0, 4.0, 10.0, 1000.0, true), lane);
        assertFalse(afterReset.events().contains(AdasDecisionEngine.Alert.FCW));
        assertFalse(afterReset.events().contains(AdasDecisionEngine.Alert.LDW));
        assertTrue(afterReset.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL));
        engine.update(observation(1_100L, 60.0, 4.0, 10.0, 1000.0, true), lane);
        assertTrue(engine.update(observation(1_200L, 60.0, 4.0, 10.0, 1000.0, true), lane)
                .events().contains(AdasDecisionEngine.Alert.FCW));
    }

    @Test
    public void targetResetPreservesCriticalHeadwayCooldown() {
        AdasDecisionEngine engine = new AdasDecisionEngine();
        assertTrue(engine.update(observation(0L, 25.0, 4.0, 0.0, 1000.0, true))
                .events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL));
        engine.resetTargetState();
        assertFalse(engine.update(observation(200L, 25.0, 4.0, 0.0, 1000.0, true))
                .events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL));
        assertTrue(engine.update(observation(4_000L, 25.0, 4.0, 0.0, 1000.0, true))
                .events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL));
    }

    @Test
    public void lvsaRequiresStableWaitingAndDoesNotRepeatWhileTargetKeepsLeaving() {
        AdasDecisionEngine engine = new AdasDecisionEngine();

        engine.update(observation(0L, 0.0, 6.0, 0.0, 1000.0, true));
        // A distance jump before the wait expires restarts the stability window.
        engine.update(observation(2_000L, 0.0, 6.4, 0.0, 1000.0, true));
        assertFalse(engine.update(observation(4_900L, 0.0, 6.4, 0.0, 1000.0, true))
                .events().contains(AdasDecisionEngine.Alert.LVSA));
        engine.update(observation(5_000L, 0.0, 6.4, 0.0, 1000.0, true));
        assertFalse(engine.update(observation(5_100L, 0.0, 9.2, -1.0, 800.0, true))
                .events().contains(AdasDecisionEngine.Alert.LVSA));
        assertTrue(engine.update(observation(5_200L, 0.0, 9.4, -1.0, 760.0, true))
                .events().contains(AdasDecisionEngine.Alert.LVSA));
        assertFalse(engine.update(observation(15_500L, 0.0, 12.0, -1.0, 500.0, true))
                .events().contains(AdasDecisionEngine.Alert.LVSA));
        assertFalse(engine.update(observation(15_700L, 0.0, 12.5, -1.0, 450.0, true))
                .events().contains(AdasDecisionEngine.Alert.LVSA));
    }

    @Test
    public void continuouslyMovingLeadVehicleDoesNotArmStartAlert() {
        AdasDecisionEngine engine = new AdasDecisionEngine();
        for (int i = 0; i < 26; i++) {
            assertFalse(engine.update(observation(i * 200L, 0.0, 3.0 + i * 0.2,
                    -1.0, 2000.0 - i * 40.0, true)).events().contains(AdasDecisionEngine.Alert.LVSA));
        }
    }

    @Test
    public void suppressedDepartureIsNotReplayedWhenCooldownExpires() {
        AdasDecisionEngine engine = new AdasDecisionEngine();
        engine.update(observation(0L, 0.0, 6.0, 0.0, 1000.0, true));
        engine.update(observation(3_000L, 0.0, 6.0, 0.0, 1000.0, true));
        engine.update(observation(3_200L, 0.0, 8.6, -1.0, 800.0, true));
        assertTrue(engine.update(observation(3_400L, 0.0, 9.2, -1.0, 760.0, true))
                .events().contains(AdasDecisionEngine.Alert.LVSA));
        engine.update(observation(4_000L, 0.0, 6.0, 0.0, 1000.0, true));
        engine.update(observation(7_000L, 0.0, 6.0, 0.0, 1000.0, true));
        engine.update(observation(7_200L, 0.0, 8.6, -1.0, 800.0, true));
        assertFalse(engine.update(observation(7_400L, 0.0, 9.2, -1.0, 760.0, true))
                .events().contains(AdasDecisionEngine.Alert.LVSA));
        assertFalse(engine.update(observation(14_000L, 0.0, 12.0, -1.0, 500.0, true))
                .events().contains(AdasDecisionEngine.Alert.LVSA));
        assertFalse(engine.update(observation(14_200L, 0.0, 12.5, -1.0, 450.0, true))
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
