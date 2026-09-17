package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AutoCalibrationLearnerTest {
    @Test
    public void rejectsNullAndUnconfiguredCalibration() {
        AutoCalibrationLearner learner = new AutoCalibrationLearner();
        assertEquals(CalibrationStore.Status.UNCONFIGURED, learner.status());

        AutoCalibrationLearner.StepResult result = learner.update(
                new LaneDepartureDetector.Observation(0.0, 0.8, true, 0.35, 0.65, 0.20, 0.80),
                60.0, null);
        assertEquals(CalibrationStore.Status.UNCONFIGURED, result.status());
        assertFalse(result.calibrationUpdated());
    }

    @Test
    public void solvesVanishingPointFromPerspectiveGeometry() {
        // Left line: (0.20, 0.78) to (0.38, 0.60)
        // Right line: (0.80, 0.78) to (0.62, 0.60)
        // W_bottom = 0.60, W_top = 0.24, Delta_W = 0.36
        // y_vp = 0.78 - 0.18 * (0.60 / 0.36) = 0.78 - 0.30 = 0.48
        LaneDepartureDetector.Observation lane = new LaneDepartureDetector.Observation(
                0.0, 0.85, true, 0.38, 0.62, 0.20, 0.80);

        AutoCalibrationLearner.VanishingPoint vp = AutoCalibrationLearner.solveVanishingPoint(lane);
        assertTrue(vp.valid());
        assertEquals(0.48, vp.y(), 0.01);
        assertEquals(0.50, vp.x(), 0.01);
    }

    @Test
    public void rejectsNonConvergingPerspectiveLanes() {
        // Parallel in 2D image (W_bottom == W_top)
        LaneDepartureDetector.Observation parallel = new LaneDepartureDetector.Observation(
                0.0, 0.85, true, 0.20, 0.80, 0.20, 0.80);
        assertFalse(AutoCalibrationLearner.solveVanishingPoint(parallel).valid());

        // Diverging towards top (physically inverted)
        LaneDepartureDetector.Observation diverging = new LaneDepartureDetector.Observation(
                0.0, 0.85, true, 0.15, 0.85, 0.25, 0.75);
        assertFalse(AutoCalibrationLearner.solveVanishingPoint(diverging).valid());
    }

    @Test
    public void gatesOnCruisingSpeedAndLaneQuality() {
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);
        CameraCalibration calib = CameraCalibration.fromWizard(1280, 720, 1.25, 90.0, 4.0);
        LaneDepartureDetector.Observation goodLane = new LaneDepartureDetector.Observation(
                0.0, 0.85, true, 0.38, 0.62, 0.20, 0.80);

        // Low speed (< 35 km/h) ignored
        AutoCalibrationLearner.StepResult lowSpeed = learner.update(goodLane, 20.0, calib);
        assertEquals(CalibrationStore.Status.WIZARD_COMPLETED, lowSpeed.status());
        assertEquals(0, lowSpeed.progressPercent());

        // High speed (> 120 km/h) ignored
        AutoCalibrationLearner.StepResult highSpeed = learner.update(goodLane, 130.0, calib);
        assertEquals(CalibrationStore.Status.WIZARD_COMPLETED, highSpeed.status());

        // Low confidence (< 0.25) ignored
        LaneDepartureDetector.Observation lowConf = new LaneDepartureDetector.Observation(
                0.0, 0.15, true, 0.38, 0.62, 0.20, 0.80);
        AutoCalibrationLearner.StepResult confResult = learner.update(lowConf, 60.0, calib);
        assertEquals(CalibrationStore.Status.WIZARD_COMPLETED, confResult.status());
        // Turning / large lane offset (> 0.15) ignored
        LaneDepartureDetector.Observation turningLane = new LaneDepartureDetector.Observation(
                0.25, 0.85, true, 0.38, 0.62, 0.20, 0.80);
        AutoCalibrationLearner.StepResult turnResult = learner.update(turningLane, 60.0, calib);
        assertEquals(CalibrationStore.Status.WIZARD_COMPLETED, turnResult.status());
    }


    @Test
    public void convergesFromWizardCompletedToCalibrated() {
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);
        CameraCalibration calib = CameraCalibration.fromWizard(1280, 720, 1.25, 90.0, 4.0);
        LaneDepartureDetector.Observation steadyLane = new LaneDepartureDetector.Observation(
                0.0, 0.85, true, 0.38, 0.62, 0.20, 0.80);

        CameraCalibration active = calib;
        AutoCalibrationLearner.StepResult step = null;

        // Feed samples sequentially
        for (int i = 0; i < AutoCalibrationLearner.REQUIRED_CONVERGENCE_SAMPLES; i++) {
            step = learner.update(steadyLane, 60.0, active);
            assertNotNull(step);
            if (i < AutoCalibrationLearner.REQUIRED_CONVERGENCE_SAMPLES - 1) {
                assertEquals(CalibrationStore.Status.CALIBRATING, step.status());
                assertTrue(step.progressPercent() >= 0 && step.progressPercent() < 100);
                assertFalse(step.calibrationUpdated());
            }
        }

        assertNotNull(step);
        assertEquals(CalibrationStore.Status.CALIBRATED, step.status());
        assertEquals(100, step.progressPercent());
        assertTrue(step.calibrationUpdated());

        // Pitch should correspond to y_vp ≈ 0.48:
        // tan(pitch) = (cy - y_vp) / fy = (0.50 - 0.48) / 0.8889 = 0.02 / 0.8889 ≈ 0.0225
        // pitch ≈ +1.29 degrees (downward angle)
        assertEquals(1.29, step.calibration().pitchDegrees(), 0.1);
        assertEquals(1.25, step.calibration().cameraHeightMeters(), 0.001);
    }

    @Test
    public void rejectedObservationDiscardsPreviouslyCollectedSamples() {
        CameraCalibration calibration = CameraCalibration.fromWizard(1280, 720, 1.25, 90.0, 4.0);
        LaneDepartureDetector.Observation good = new LaneDepartureDetector.Observation(
                0.0, 0.85, true, 0.38, 0.62, 0.20, 0.80);
        LaneDepartureDetector.Observation[] invalid = {
                null,
                new LaneDepartureDetector.Observation(0.0, 0.85, false, 0.38, 0.62, 0.20, 0.80),
                new LaneDepartureDetector.Observation(0.0, Double.NaN, true, 0.38, 0.62, 0.20, 0.80),
                new LaneDepartureDetector.Observation(Double.NaN, 0.85, true, 0.38, 0.62, 0.20, 0.80),
                new LaneDepartureDetector.Observation(0.25, 0.85, true, 0.38, 0.62, 0.20, 0.80),
                new LaneDepartureDetector.Observation(0.0, 0.1, true, 0.38, 0.62, 0.20, 0.80),
                new LaneDepartureDetector.Observation(0.0, 0.85, true, 0.20, 0.80, 0.20, 0.80)
        };
        for (LaneDepartureDetector.Observation rejected : invalid) {
            AutoCalibrationLearner learner = new AutoCalibrationLearner(CalibrationStore.Status.WIZARD_COMPLETED, 0);
            for (int i = 0; i < 59; i++) {
                learner.update(good, 60.0, calibration);
            }
            assertEquals(0, learner.update(rejected, 60.0, calibration).progressPercent());
            AutoCalibrationLearner.StepResult resumed = learner.update(good, 60.0, calibration);
            assertEquals(CalibrationStore.Status.CALIBRATING, resumed.status());
            assertFalse(resumed.calibrationUpdated());
            assertEquals(1, resumed.progressPercent());
        }
    }

    @Test
    public void speedRejectionAndCaptureGapRestartPendingCalibration() {
        CameraCalibration calibration = CameraCalibration.fromWizard(1280, 720, 1.25, 90.0, 4.0);
        LaneDepartureDetector.Observation good = new LaneDepartureDetector.Observation(
                0.0, 0.85, true, 0.38, 0.62, 0.20, 0.80);
        for (double speed : new double[] {Double.NaN, 20.0, 130.0}) {
            AutoCalibrationLearner learner = new AutoCalibrationLearner(CalibrationStore.Status.WIZARD_COMPLETED, 0);
            for (int i = 0; i < 59; i++) {
                learner.update(good, 60.0, calibration);
            }
            assertEquals(0, learner.update(good, speed, calibration).progressPercent());
            assertFalse(learner.update(good, 60.0, calibration).calibrationUpdated());
            learner.resetSamples();
            assertEquals(1, learner.update(good, 60.0, calibration).progressPercent());
        }
    }

    @Test
    public void captureGapDiscardsPendingPitchDriftButKeepsCompletedCalibration() {
        AutoCalibrationLearner learner = new AutoCalibrationLearner(CalibrationStore.Status.CALIBRATED, 100);
        CameraCalibration calibration = CameraCalibration.fromWizard(1280, 720, 1.25, 90.0, 2.0);
        LaneDepartureDetector.Observation lane = new LaneDepartureDetector.Observation(
                0.0, 0.85, true, 0.35, 0.65, 0.20, 0.80);
        for (int i = 0; i < 8; i++) {
            assertFalse(learner.update(lane, 60.0, calibration).calibrationUpdated());
        }
        learner.resetSamples();
        AutoCalibrationLearner.StepResult resumed = learner.update(lane, 60.0, calibration);
        assertEquals(CalibrationStore.Status.CALIBRATED, resumed.status());
        assertEquals(100, resumed.progressPercent());
        assertFalse(resumed.calibrationUpdated());
        assertEquals(calibration, resumed.calibration());
    }

    @Test
    public void tracksSlowlyWhenInCalibratedState() {
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.CALIBRATED, 100);
        CameraCalibration calib = CameraCalibration.fromWizard(1280, 720, 1.25, 90.0, 2.0);

        // Feed observation where y_vp corresponds to pitch ≈ 5.0 deg
        // y_vp = cy - fy * tan(5 deg) ≈ 0.50 - 0.8889 * 0.08749 ≈ 0.422
        // We can create a lane with y_vp ≈ 0.45
        // W_bottom = 0.60, W_top = 0.29 => Delta_W = 0.31
        // y_vp = 0.86 - 0.21 * (0.60 / 0.31) = 0.86 - 0.406 = 0.454
        LaneDepartureDetector.Observation lane = new LaneDepartureDetector.Observation(
                0.0, 0.85, true, 0.35, 0.65, 0.20, 0.80);
        CameraCalibration current = calib;
        // Run 50 frames of tracking
        for (int i = 0; i < 50; i++) {
            AutoCalibrationLearner.StepResult res = learner.update(lane, 60.0, current);
            if (res.calibrationUpdated()) {
                current = res.calibration();
            }
        }

        // The pitch should have adjusted slightly towards observed pitch
        assertNotEquals(2.0, current.pitchDegrees(), 0.001);
        assertTrue(current.pitchDegrees() > 2.0);
    }

    @Test
    public void laneRoiGuardRejectsSteepPitchWhereTheFixedRoiLooksAtTheHood() {
        // At 1.25 m the ROI keeps at least 4 m of road in view up to ~14 deg of downward pitch.
        assertTrue(AutoCalibrationLearner.isLaneRoiVisible(wizardAt(4.0)));
        assertTrue(AutoCalibrationLearner.isLaneRoiVisible(wizardAt(9.6)));
        assertTrue(AutoCalibrationLearner.isLaneRoiVisible(wizardAt(14.0)));
        assertFalse(AutoCalibrationLearner.isLaneRoiVisible(wizardAt(16.0)));
        assertFalse(AutoCalibrationLearner.isLaneRoiVisible(wizardAt(20.0)));
        // A taller mounting keeps the same ROI rows on usable road, so the guard is not a flat
        // pitch limit: it rejects the angle only where the ROI actually collapses.
        assertTrue(AutoCalibrationLearner.isLaneRoiVisible(
                CameraCalibration.fromWizard(1280, 720, 1.75, 90.0, 16.0)));
        assertTrue(AutoCalibrationLearner.isLaneRoiVisible(
                CameraCalibration.fromWizard(1280, 720, 2.0, 90.0, 20.0)));
        assertFalse(AutoCalibrationLearner.isLaneRoiVisible(null));
    }

    @Test
    public void vanishingWindowStaysInsideTheLaneRoiGuard() {
        // The observable window must not admit samples the physical guard already rejects,
        // otherwise the two limits disagree and samples are silently dropped.
        CameraCalibration atWindowLimit = wizardAt(
                AutoCalibrationLearner.vanishingPitchLimitDegrees(wizardAt(4.0)));
        assertEquals(12.68, atWindowLimit.pitchDegrees(), 0.05);
        assertTrue(AutoCalibrationLearner.isLaneRoiVisible(atWindowLimit));
    }

    @Test
    public void rejectsSamplesWhenTheConfiguredPitchPutsTheRoiOnTheHood() {
        CameraCalibration steep = wizardAt(20.0);
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);
        LaneDepartureDetector.Observation lane = new LaneDepartureDetector.Observation(
                0.0, 0.85, true, 0.38, 0.62, 0.20, 0.80);

        for (int i = 0; i < 80; i++) {
            AutoCalibrationLearner.StepResult step = learner.update(lane, 60.0, steep);
            assertFalse("A collapsed ROI must never produce a calibration update",
                    step.calibrationUpdated());
        }
        // Samples are discarded rather than accumulated, so the learner must stay pending.
        assertEquals(CalibrationStore.Status.WIZARD_COMPLETED, learner.status());
        assertEquals(0, learner.progress());
    }

    @Test
    public void reachesCalibratedForATallVehicleWithinTheGuard() {
        CameraCalibration tall = CameraCalibration.fromWizard(1280, 720, 1.75, 90.0, 4.0);
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);
        // A constant vanishing point at the far end of the observable window (y_vp = 0.30,
        // ~12.7 deg of pitch). Tall mounting keeps the fixed ROI on usable road at that angle,
        // so this is the steepest calibration the learner is allowed to persist.
        LaneDepartureDetector.Observation lane = new LaneDepartureDetector.Observation(
                0.0, 0.90, true, 0.38, 0.62, 0.20, 0.80);
        assertEquals(0.30, AutoCalibrationLearner.solveVanishingPoint(lane).y(), 0.005);

        CameraCalibration current = tall;
        AutoCalibrationLearner.StepResult step = null;
        for (int i = 0; i < AutoCalibrationLearner.REQUIRED_CONVERGENCE_SAMPLES; i++) {
            step = learner.update(lane, 60.0, current);
            if (step.calibrationUpdated()) {
                current = step.calibration();
            }
        }

        assertNotNull(step);
        assertEquals(CalibrationStore.Status.CALIBRATED, step.status());
        assertTrue(step.calibrationUpdated());
        assertEquals(12.68, current.pitchDegrees(), 0.05);
        assertTrue("Learned pitch must keep the ROI on the road",
                AutoCalibrationLearner.isLaneRoiVisible(current));
    }

    private static CameraCalibration wizardAt(double pitchDegrees) {
        return CameraCalibration.fromWizard(1280, 720, 1.25, 90.0, pitchDegrees);
    }
}
