package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class AdasSimulationTest {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final CameraCalibration CALIBRATION =
            CameraCalibration.fromWizard(WIDTH, HEIGHT, 1.25, 90.0, 4.0);

    @Test
    public void scenarioDistancesUseTheSuppliedCameraGeometry() {
        CameraCalibration calibration = CameraCalibration.fromWizard(WIDTH, HEIGHT, 1.45, 100.0, 3.7);
        AdasSimulator.Scenario[] scenarios = {AdasSimulator.Scenario.FCW_APPROACH,
                AdasSimulator.Scenario.HMW_PROXIMITY, AdasSimulator.Scenario.LVSA_START};
        double[] distances = {22.0, 7.0, 5.5};
        for (int i = 0; i < scenarios.length; i++) {
            AdasSimulator.SimFrame frame = AdasSimulator.generateScenario(
                    scenarios[i], WIDTH, HEIGHT, calibration).get(0);
            assertEquals(distances[i], calibration.estimateDistanceMeters(
                    frame.detections().vehicles().get(0).bottom()), 0.001);
        }
    }

    @Test
    public void fcwApproachScenarioTriggersFcwAlarm() {
        List<AdasSimulator.SimFrame> frames = AdasSimulator.generateScenario(
                AdasSimulator.Scenario.FCW_APPROACH, WIDTH, HEIGHT, CALIBRATION);
        assertFalse(frames.isEmpty());

        LeadVehicleTracker tracker = new LeadVehicleTracker();
        LeadVehicleMotionEstimator motionEstimator = new LeadVehicleMotionEstimator();
        AdasDecisionEngine decisionEngine = new AdasDecisionEngine();

        boolean fcwTriggered = false;

        for (AdasSimulator.SimFrame simFrame : frames) {
            LeadVehicleTracker.Snapshot tracking = tracker.update(simFrame.detections());
            LeadVehicleMotionEstimator.Measurement motion = motionEstimator.update(
                    tracking, CALIBRATION, WIDTH, HEIGHT);

            AdasDecisionEngine.Observation observation = new AdasDecisionEngine.Observation(
                    simFrame.detections().timestampNanos() / 1_000_000L,
                    simFrame.speedKmh(),
                    motion.distanceMeters(),
                    motion.closingSpeedMps(),
                    motion.targetAreaPixels(),
                    motion.visible());

            AdasDecisionEngine.Decision decision = decisionEngine.update(
                    observation,
                    new AdasDecisionEngine.LaneObservation(
                            simFrame.lane().centerOffset(),
                            simFrame.lane().confidence(),
                            simFrame.lane().available()));

            if (decision.events().contains(AdasDecisionEngine.Alert.FCW)) {
                fcwTriggered = true;
            }
        }

        assertTrue("FCW alert must be triggered during rapid approach scenario", fcwTriggered);
    }

    @Test
    public void hmwProximityScenarioTriggersCriticalHmwChime() {
        List<AdasSimulator.SimFrame> frames = AdasSimulator.generateScenario(
                AdasSimulator.Scenario.HMW_PROXIMITY, WIDTH, HEIGHT, CALIBRATION);
        assertFalse(frames.isEmpty());

        LeadVehicleTracker tracker = new LeadVehicleTracker();
        LeadVehicleMotionEstimator motionEstimator = new LeadVehicleMotionEstimator();
        AdasDecisionEngine decisionEngine = new AdasDecisionEngine();

        boolean hmwVisualObserved = false;
        boolean hmwCriticalTriggered = false;

        for (AdasSimulator.SimFrame simFrame : frames) {
            LeadVehicleTracker.Snapshot tracking = tracker.update(simFrame.detections());
            LeadVehicleMotionEstimator.Measurement motion = motionEstimator.update(
                    tracking, CALIBRATION, WIDTH, HEIGHT);

            AdasDecisionEngine.Observation observation = new AdasDecisionEngine.Observation(
                    simFrame.detections().timestampNanos() / 1_000_000L,
                    simFrame.speedKmh(),
                    motion.distanceMeters(),
                    motion.closingSpeedMps(),
                    motion.targetAreaPixels(),
                    motion.visible());

            AdasDecisionEngine.Decision decision = decisionEngine.update(
                    observation,
                    new AdasDecisionEngine.LaneObservation(
                            simFrame.lane().centerOffset(),
                            simFrame.lane().confidence(),
                            simFrame.lane().available()));

            if (decision.headwayWarning()) {
                hmwVisualObserved = true;
            }
            if (decision.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL)) {
                hmwCriticalTriggered = true;
            }
        }

        assertTrue("HMW visual caution must be observed", hmwVisualObserved);
        assertTrue("HMW critical chime event must be triggered when <= 4.0m", hmwCriticalTriggered);
    }

    @Test
    public void ldwDepartureScenarioTriggersLdwAlarm() {
        List<AdasSimulator.SimFrame> frames = AdasSimulator.generateScenario(
                AdasSimulator.Scenario.LDW_DEPARTURE, WIDTH, HEIGHT, CALIBRATION);
        assertFalse(frames.isEmpty());

        AdasDecisionEngine decisionEngine = new AdasDecisionEngine();
        boolean ldwTriggered = false;

        for (AdasSimulator.SimFrame simFrame : frames) {
            AdasDecisionEngine.Observation observation = new AdasDecisionEngine.Observation(
                    simFrame.detections().timestampNanos() / 1_000_000L,
                    simFrame.speedKmh(),
                    Double.NaN, 0.0, 0.0, false);

            AdasDecisionEngine.Decision decision = decisionEngine.update(
                    observation,
                    new AdasDecisionEngine.LaneObservation(
                            simFrame.lane().centerOffset(),
                            simFrame.lane().confidence(),
                            simFrame.lane().available()));

            if (decision.events().contains(AdasDecisionEngine.Alert.LDW)) {
                ldwTriggered = true;
            }
        }

        assertTrue("LDW alert must be triggered after drifting across lane for >1.0s", ldwTriggered);
    }

    @Test
    public void lvsaStartScenarioTriggersLvsaAlarm() {
        List<AdasSimulator.SimFrame> frames = AdasSimulator.generateScenario(
                AdasSimulator.Scenario.LVSA_START, WIDTH, HEIGHT, CALIBRATION);
        assertFalse(frames.isEmpty());

        LeadVehicleTracker tracker = new LeadVehicleTracker();
        LeadVehicleMotionEstimator motionEstimator = new LeadVehicleMotionEstimator();
        AdasDecisionEngine decisionEngine = new AdasDecisionEngine();

        boolean lvsaTriggered = false;

        for (AdasSimulator.SimFrame simFrame : frames) {
            LeadVehicleTracker.Snapshot tracking = tracker.update(simFrame.detections());
            LeadVehicleMotionEstimator.Measurement motion = motionEstimator.update(
                    tracking, CALIBRATION, WIDTH, HEIGHT);

            AdasDecisionEngine.Observation observation = new AdasDecisionEngine.Observation(
                    simFrame.detections().timestampNanos() / 1_000_000L,
                    simFrame.speedKmh(),
                    motion.distanceMeters(),
                    motion.closingSpeedMps(),
                    motion.targetAreaPixels(),
                    motion.visible());

            AdasDecisionEngine.Decision decision = decisionEngine.update(
                    observation,
                    new AdasDecisionEngine.LaneObservation(
                            simFrame.lane().centerOffset(),
                            simFrame.lane().confidence(),
                            simFrame.lane().available()));

            if (decision.events().contains(AdasDecisionEngine.Alert.LVSA)) {
                lvsaTriggered = true;
            }
        }

        assertTrue("LVSA alert must be triggered when lead vehicle pulls away after red light wait", lvsaTriggered);
    }

    @Test
    public void autoCalibrationScenarioConvergesToCalibrated() {
        List<AdasSimulator.SimFrame> frames = AdasSimulator.generateScenario(
                AdasSimulator.Scenario.AUTO_CALIBRATION, WIDTH, HEIGHT, CALIBRATION);
        assertFalse(frames.isEmpty());

        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);

        CameraCalibration current = CALIBRATION;
        AutoCalibrationLearner.StepResult finalStep = null;

        for (AdasSimulator.SimFrame simFrame : frames) {
            AutoCalibrationLearner.StepResult step = learner.update(
                    simFrame.lane(), simFrame.speedKmh(), current, current.pitchDegrees(),
                    WIDTH, HEIGHT);
            if (step.calibrationUpdated()) {
                current = step.calibration();
            }
            finalStep = step;
        }

        assertNotNull(finalStep);
        assertEquals("Auto calibration must converge to CALIBRATED",
                CalibrationStore.Status.CALIBRATED, finalStep.status());
        assertEquals("Progress must reach 100%", 100, finalStep.progressPercent());
        // The simulated camera looks 1.5 degrees steeper than the fixture calibration, and the pitch
        // solve must see that: the confirmed value stays the configured one, but the implied angle has
        // to land on the simulated mounting angle.
        assertEquals("The solve must recover the simulated mounting angle",
                CALIBRATION.pitchDegrees() + 1.5, learner.lastSolvedPitchDegrees(), 0.5);
        assertTrue("The confirmed value must keep the configured angle",
                Math.abs(current.pitchDegrees() - CALIBRATION.pitchDegrees()) < 0.01);
    }
}
