package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AutoCalibrationLearnerTest {
    @Test
    public void distanceReadyStartsCompleteEvenWithLegacyZeroProgress() {
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.DISTANCE_READY, 0);
        assertEquals(100, learner.progress());
    }

    @Test
    public void distanceReadyRemainsCompleteAcrossSessionAndCaptureResets() {
        AutoCalibrationLearner learner = new AutoCalibrationLearner();
        learner.reset(CalibrationStore.Status.DISTANCE_READY);
        assertEquals(100, learner.progress());
        learner.resetSamples();
        assertEquals(CalibrationStore.Status.DISTANCE_READY, learner.status());
        assertEquals(100, learner.progress());
    }

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
        // Observations that carry no lane geometry at all - the lane is not seen, or is seen too
        // weakly to be worth a sample - discard the window rather than pausing it.
        LaneDepartureDetector.Observation[] invalid = {
                null,
                new LaneDepartureDetector.Observation(0.0, 0.85, false, 0.38, 0.62, 0.20, 0.80),
                new LaneDepartureDetector.Observation(0.0, 0.1, true, 0.38, 0.62, 0.20, 0.80)
        };
        for (LaneDepartureDetector.Observation rejected : invalid) {
            AutoCalibrationLearner learner = new AutoCalibrationLearner(CalibrationStore.Status.WIZARD_COMPLETED, 0);
            for (int i = 0; i < 59; i++) {
                learner.update(good, 60.0, calibration);
            }
            assertTrue("the window must be nearly full before the rejection",
                    learner.progress() >= 90);
            AutoCalibrationLearner.StepResult rejectedStep =
                    learner.update(rejected, 60.0, calibration);
            // The returned step still carries the pre-update progress value.
            assertEquals(0, learner.progress());
            assertFalse(rejectedStep.calibrationUpdated());
            AutoCalibrationLearner.StepResult resumed = learner.update(good, 60.0, calibration);
            assertEquals(CalibrationStore.Status.CALIBRATING, resumed.status());
            assertFalse(resumed.calibrationUpdated());
            assertEquals(1, resumed.progressPercent());
        }
    }

    @Test
    public void brokenChannelsPauseTheWindowInsteadOfRestartingIt() {
        CameraCalibration calibration = CameraCalibration.fromWizard(1280, 720, 1.25, 90.0, 4.0);
        LaneDepartureDetector.Observation good = new LaneDepartureDetector.Observation(
                0.0, 0.85, true, 0.38, 0.62, 0.20, 0.80);
        // Each of these has one unusable channel on an otherwise real observation: a transient
        // measurement failure, not an absent lane.
        LaneDepartureDetector.Observation[] broken = {
                new LaneDepartureDetector.Observation(0.0, Double.NaN, true,
                        0.38, 0.62, 0.20, 0.80, WIDTH_SAMPLES),
                new LaneDepartureDetector.Observation(Double.NaN, 0.85, true,
                        0.38, 0.62, 0.20, 0.80, WIDTH_SAMPLES),
                new LaneDepartureDetector.Observation(0.0, 0.85, true,
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN, WIDTH_SAMPLES)
        };
        for (LaneDepartureDetector.Observation observation : broken) {
            AutoCalibrationLearner learner = new AutoCalibrationLearner(CalibrationStore.Status.WIZARD_COMPLETED, 0);
            for (int i = 0; i < 30; i++) {
                learner.update(good, 60.0, calibration);
            }
            int before = learner.progress();
            assertTrue(before > 0);
            learner.update(observation, 60.0, calibration);
            assertEquals(AutoCalibrationLearner.Rejection.DRIVING_CONDITION,
                    learner.lastRejection());
            assertEquals("a transient must hold the window", before, learner.progress());
        }

        // A lane the vehicle is leaving is a driving condition too, and it also just pauses.
        AutoCalibrationLearner turning = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);
        for (int i = 0; i < 30; i++) {
            turning.update(good, 60.0, calibration);
        }
        int beforeTurn = turning.progress();
        turning.update(new LaneDepartureDetector.Observation(0.25, 0.85, true,
                0.38, 0.62, 0.20, 0.80, WIDTH_SAMPLES), 60.0, calibration);
        assertEquals(AutoCalibrationLearner.Rejection.DRIVING_CONDITION, turning.lastRejection());
        assertEquals(beforeTurn, turning.progress());
    }

    /** Width samples over the ROI rows: the shape every real frame carries. */
    private static final java.util.List<LaneGeometry.WidthSample> WIDTH_SAMPLES = buildWidthSamples();

    private static java.util.List<LaneGeometry.WidthSample> buildWidthSamples() {
        java.util.List<LaneGeometry.WidthSample> samples = new java.util.ArrayList<>();
        for (int i = 0; i <= 8; i++) {
            double rowY = LaneDepartureDetector.ROI_TOP_ROW
                    + (LaneDepartureDetector.ROI_BOTTOM_ROW - LaneDepartureDetector.ROI_TOP_ROW)
                    * i / 8.0;
            double halfWidth = 0.05 + 0.02 * i;
            samples.add(new LaneGeometry.WidthSample(rowY, 0.5 - halfWidth, 0.5 + halfWidth, 0.9));
        }
        return java.util.List.copyOf(samples);
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
        // The far edge of the sampling band is at row 0.54, so the guard starts rejecting once that
        // row falls closer than 3.5 m of road: between 17 and 18 degrees at 1.25 m of mounting height.
        assertTrue(AutoCalibrationLearner.isLaneRoiVisible(wizardAt(4.0)));
        assertTrue(AutoCalibrationLearner.isLaneRoiVisible(wizardAt(9.6)));
        assertTrue(AutoCalibrationLearner.isLaneRoiVisible(wizardAt(17.0)));
        assertFalse(AutoCalibrationLearner.isLaneRoiVisible(wizardAt(18.0)));
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
        // A steady vanishing point that is still inside the observable window. The reported row is
        // derived from the same perspective relation the learner uses, so this stays valid if the
        // reporting rows move.
        LaneDepartureDetector.Observation lane = new LaneDepartureDetector.Observation(
                0.0, 0.90, true, 0.38, 0.62, 0.20, 0.80);
        double expectedVpY = expectedVanishingY(0.24, 0.60);
        assertEquals(expectedVpY, AutoCalibrationLearner.solveVanishingPoint(lane).y(), 0.005);

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
        assertEquals("the learned pitch must match the fixture vanishing point",
                1.29, current.pitchDegrees(), 0.1);
        assertTrue("Learned pitch must keep the ROI on the road",
                AutoCalibrationLearner.isLaneRoiVisible(current));
    }

    @Test
    public void reportsGeometricRejectionsSoTheUiCanAskForReaiming() {
        // A 20 deg pitch collapses the ROI at 1.25 m, and no amount of driving fixes that.
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);
        CameraCalibration steep = wizardAt(20.0);

        for (int i = 0; i < AutoCalibrationLearner.GEOMETRIC_REJECTION_HINT_THRESHOLD; i++) {
            learner.update(straightLane(), 60.0, steep);
        }
        assertEquals(AutoCalibrationLearner.Rejection.ROI_NOT_VISIBLE, learner.lastRejection());
        assertEquals(AutoCalibrationLearner.GEOMETRIC_REJECTION_HINT_THRESHOLD,
                learner.consecutiveGeometricRejections());

        // A usable observation clears both the reason and the counter.
        CameraCalibration normal = wizardAt(4.0);
        learner.update(straightLane(), 60.0, normal);
        assertEquals(AutoCalibrationLearner.Rejection.NONE, learner.lastRejection());
        assertEquals(0, learner.consecutiveGeometricRejections());
    }

    @Test
    public void vanishingPointOutOfRangeNoLongerBlamesTheMountingAngle() {
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);
        // Parallel lane edges: the legacy width-delta sanity check fails. That is a numeric failure
        // of the vanishing-point extrapolation, not evidence that the camera is mounted wrong, so it
        // must not raise the re-aiming hint.
        LaneDepartureDetector.Observation parallel = new LaneDepartureDetector.Observation(
                0.0, 0.85, true, 0.20, 0.80, 0.20, 0.80);

        learner.update(parallel, 60.0, wizardAt(4.0));
        assertEquals(AutoCalibrationLearner.Rejection.VANISHING_OUT_OF_RANGE,
                learner.lastRejection());
        assertEquals(0, learner.consecutiveGeometricRejections());
    }

    @Test
    public void convergesAndKeepsTheConfiguredPitchWhenLaneWidthIsConsistent() {
        // The lane width matches the geometry the configured pitch predicts, so the window confirms
        // the mounting angle and the persisted value is the configured one.
        CameraCalibration wizard = wizardAt(8.0);
        LaneDepartureDetector.Observation lane = laneAt(8.0, wizard);
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);

        AutoCalibrationLearner.StepResult first = learner.update(lane, 60.0, wizard, 8.0, 1280, 720);
        assertEquals(CalibrationStore.Status.CALIBRATING, first.status());
        assertEquals("implied pitch must reproduce the mounted angle",
                8.0, learner.lastSolvedPitchDegrees(), 0.2);
        assertEquals("implied lane width must match the prior",
                LaneGeometry.DEFAULT_LANE_WIDTH_METERS, learner.lastLaneWidthMeters(), 0.05);

        CameraCalibration current = wizard;
        AutoCalibrationLearner.StepResult step = null;
        for (int i = 0; i < AutoCalibrationLearner.REQUIRED_CONVERGENCE_SAMPLES; i++) {
            step = learner.update(lane, 60.0, current, current.pitchDegrees(), 1280, 720);
            if (step.calibrationUpdated()) {
                current = step.calibration();
            }
        }

        assertNotNull(step);
        assertEquals(CalibrationStore.Status.CALIBRATED, step.status());
        assertEquals(100, step.progressPercent());
        assertEquals("the confirmed value is the configured angle", 8.0, current.pitchDegrees(), 0.01);
    }

    @Test
    public void drivingGatePausesInsteadOfRestartingWhenLaneSamplesExist() {
        CameraCalibration wizard = wizardAt(8.0);
        LaneDepartureDetector.Observation lane = laneAt(8.0, wizard);
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);

        for (int i = 0; i < 20; i++) {
            learner.update(lane, 60.0, wizard, 8.0, 1280, 720);
        }
        int progressBefore = learner.progress();
        assertTrue(progressBefore > 0);

        // Dropping below the speed gate must hold the window, not discard it: the consumer-grade
        // installer shows a progress bar that survives normal traffic.
        learner.update(lane, 10.0, wizard, 8.0, 1280, 720);
        assertEquals(AutoCalibrationLearner.Rejection.DRIVING_CONDITION, learner.lastRejection());
        assertEquals("the gate failure must hold the window", progressBefore, learner.progress());
        int resumed = learner.update(lane, 60.0, wizard, 8.0, 1280, 720).progressPercent();
        assertTrue("the window must resume where it stopped, not restart or double-count",
                resumed > progressBefore && resumed <= progressBefore + 2);
    }

    @Test
    public void rejectsBoundaryPairThatIsNotOneEgoLane() {
        CameraCalibration wizard = wizardAt(8.0);
        // Both tracked edges are twice as far apart as a lane: the pair is a neighbouring lane line,
        // so the observation says nothing about this vehicle's mounting angle.
        LaneDepartureDetector.Observation neighbour = laneWithWidthFactor(8.0, wizard, 2.0);
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);

        AutoCalibrationLearner.StepResult step = learner.update(neighbour, 60.0, wizard, 8.0,
                1280, 720);
        assertEquals(AutoCalibrationLearner.Rejection.OBSERVATION_INCOHERENT,
                learner.lastRejection());
        assertEquals(CalibrationStore.Status.WIZARD_COMPLETED, step.status());
        assertEquals(0, learner.progress());
    }

    @Test
    public void rejectsObservationWhoseWidthRatioContradictsTheModel() {
        CameraCalibration wizard = wizardAt(8.0);
        // A width ratio of 3 cannot be produced by a flat road at this mounting angle: the ratio is
        // fixed by the two rows and the pitch together.
        LaneDepartureDetector.Observation lane = flatObservation(3.0);
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);

        AutoCalibrationLearner.StepResult step = learner.update(lane, 60.0, wizard, 8.0, 1280, 720);
        // The ratios cannot be reconciled by any pitch inside the bracket, so the frame is dropped.
        assertEquals(AutoCalibrationLearner.Rejection.OBSERVATION_INCOHERENT,
                learner.lastRejection());
        assertEquals(CalibrationStore.Status.WIZARD_COMPLETED, step.status());
        assertEquals(0, learner.progress());
    }

    @Test
    public void reportsImpliedPitchDeviationForReaiming() {
        CameraCalibration wizard = wizardAt(8.0);

        // A lane whose ratio no pitch inside the bracket can explain: the frame is dropped and the
        // implied angle is deliberately not published.
        AutoCalibrationLearner incoherent = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);
        incoherent.update(laneAt(20.0, wizard), 60.0, wizard, 8.0, 1280, 720);
        assertEquals(AutoCalibrationLearner.Rejection.OBSERVATION_INCOHERENT,
                incoherent.lastRejection());
        assertEquals("a mounting error is not a camera-geometry failure",
                0, incoherent.consecutiveGeometricRejections());

        // Inside the bracket the deviation is reported instead, so the UI can advise re-aiming: the
        // camera looks 4 degrees steeper than the wizard was told.
        AutoCalibrationLearner insideBracket = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);
        AutoCalibrationLearner.StepResult step = insideBracket.update(laneAt(12.0, wizard), 60.0,
                wizard, 8.0, 1280, 720);
        assertEquals(AutoCalibrationLearner.Rejection.NONE, insideBracket.lastRejection());
        assertEquals(CalibrationStore.Status.CALIBRATING, step.status());
        assertEquals(12.0, insideBracket.lastSolvedPitchDegrees(), 0.3);
        assertTrue("the deviation must be large enough to explain to the installer",
                Math.abs(insideBracket.lastSolvedPitchDegrees() - wizard.pitchDegrees()) > 3.5);
    }

    @Test
    public void stablePitchFiveDegreesAwayDoesNotConverge() {
        CameraCalibration wizard = wizardAt(8.0);
        LaneDepartureDetector.Observation lane = laneAt(13.0, wizard);
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);
        AutoCalibrationLearner.StepResult step = null;
        for (int i = 0; i < AutoCalibrationLearner.REQUIRED_CONVERGENCE_SAMPLES + 10; i++) {
            step = learner.update(lane, 60.0, wizard, 8.0, 1280, 720);
            assertFalse(step.calibrationUpdated());
        }
        assertNotNull(step);
        assertEquals(CalibrationStore.Status.CALIBRATING, step.status());
        assertEquals(AutoCalibrationLearner.Rejection.OBSERVATION_INCOHERENT,
                learner.lastRejection());
        assertEquals(99, step.progressPercent());
    }

    @Test
    public void missingWidthsPausePitchWindowWithoutUsingLegacyFit() {
        CameraCalibration wizard = wizardAt(8.0);
        LaneDepartureDetector.Observation lane = laneAt(8.0, wizard);
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);
        for (int i = 0; i < 20; i++) {
            learner.update(lane, 60.0, wizard, 8.0, 1280, 720);
        }
        int progress = learner.progress();
        LaneDepartureDetector.Observation missing = new LaneDepartureDetector.Observation(
                lane.centerOffset(), lane.confidence(), true, lane.leftTopX(), lane.rightTopX(),
                lane.leftBottomX(), lane.rightBottomX());
        learner.update(missing, 60.0, wizard, 8.0, 1280, 720);
        assertEquals(progress, learner.progress());
        assertEquals(AutoCalibrationLearner.Rejection.DRIVING_CONDITION, learner.lastRejection());
    }

    @Test
    public void pitchSolveUsesActualRowsWhenFarEndpointIsMissing() {
        CameraCalibration wizard = wizardAt(8.0);
        LaneDepartureDetector.Observation lane = laneAt(8.0, wizard);
        lane = lane.withWidthSamples(lane.widthSamples().subList(1, lane.widthSamples().size()));
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);
        learner.update(lane, 60.0, wizard, 8.0, 1280, 720);
        assertEquals(AutoCalibrationLearner.Rejection.NONE, learner.lastRejection());
        assertEquals(8.0, learner.lastSolvedPitchDegrees(), 0.05);
    }

    /** Synthetic lane whose edges imply the given true pitch, on the real ROI rows. */
    private static LaneDepartureDetector.Observation laneAt(double truePitchDegrees,
                                                            CameraCalibration wizard) {
        return laneWithWidthFactor(truePitchDegrees, wizard, 1.0);
    }

    /**
     * As {@link #laneAt}, with both edges scaled away from the lane width prior and the sample
     * geometry kept inside the image so the detector's own plausibility rules still apply.
     */
    private static LaneDepartureDetector.Observation laneWithWidthFactor(double truePitchDegrees,
                                                                         CameraCalibration wizard,
                                                                         double widthFactor) {
        int frameWidth = 1280;
        int frameHeight = 720;
        java.util.List<LaneGeometry.WidthSample> samples = new java.util.ArrayList<>();
        // On a road descending away from the camera the larger row is the nearer one.
        double nearRow = LaneDepartureDetector.ROI_BOTTOM_ROW;
        double farRow = LaneDepartureDetector.ROI_TOP_ROW;
        double nearWidth = 0.0;
        for (int i = 0; i <= 8; i++) {
            double rowY = farRow + (nearRow - farRow) * i / 8.0;
            double width = widthFactor * LaneGeometry.laneWidthNormalized(wizard, rowY,
                    truePitchDegrees, LaneGeometry.DEFAULT_LANE_WIDTH_METERS, frameWidth,
                    frameHeight);
            if (!Double.isFinite(width)) {
                continue;
            }
            width = Math.min(width, 0.8);
            if (i == 8) {
                nearWidth = width;
            }
            samples.add(new LaneGeometry.WidthSample(rowY, 0.5 - width / 2.0,
                    0.5 + width / 2.0, 0.9));
        }
        return new LaneDepartureDetector.Observation(0.0, 0.9, true,
                0.5 - nearWidth / 2.0, 0.5 + nearWidth / 2.0,
                0.5 - nearWidth / 2.0, 0.5 + nearWidth / 2.0, samples);
    }

    /** Observation with a fixed near/far width ratio and no physical model behind it. */
    private static LaneDepartureDetector.Observation flatObservation(double ratio) {
        double farWidth = 0.10;
        double nearWidth = farWidth * ratio;
        java.util.List<LaneGeometry.WidthSample> samples = java.util.List.of(
                new LaneGeometry.WidthSample(LaneDepartureDetector.ROI_TOP_ROW,
                        0.5 - farWidth / 2.0, 0.5 + farWidth / 2.0, 0.9),
                new LaneGeometry.WidthSample(LaneDepartureDetector.ROI_BOTTOM_ROW,
                        0.5 - nearWidth / 2.0, 0.5 + nearWidth / 2.0, 0.9));
        return new LaneDepartureDetector.Observation(0.0, 0.9, true,
                0.5 - farWidth / 2.0, 0.5 + farWidth / 2.0,
                0.5 - nearWidth / 2.0, 0.5 + nearWidth / 2.0, samples);
    }

    @Test
    public void drivingConditionRejectionsDoNotCountAsGeometric() {
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);
        CameraCalibration normal = wizardAt(4.0);

        // Too slow: the user just has to drive faster, so the hint must stay suppressed.
        for (int i = 0; i < AutoCalibrationLearner.GEOMETRIC_REJECTION_HINT_THRESHOLD * 2; i++) {
            learner.update(straightLane(), 20.0, normal);
        }
        assertEquals(AutoCalibrationLearner.Rejection.DRIVING_CONDITION, learner.lastRejection());
        assertEquals(0, learner.consecutiveGeometricRejections());

        // A geometric rejection after that still starts counting from one.
        learner.update(straightLane(), 60.0, wizardAt(20.0));
        assertEquals(1, learner.consecutiveGeometricRejections());

        // Lane quality and lane centering are driving conditions as well.
        learner.update(new LaneDepartureDetector.Observation(0.0, 0.1, true,
                0.38, 0.62, 0.20, 0.80), 60.0, normal);
        assertEquals(AutoCalibrationLearner.Rejection.DRIVING_CONDITION, learner.lastRejection());
        assertEquals(0, learner.consecutiveGeometricRejections());

        learner.update(new LaneDepartureDetector.Observation(0.25, 0.85, true,
                0.38, 0.62, 0.20, 0.80), 60.0, normal);
        assertEquals(AutoCalibrationLearner.Rejection.DRIVING_CONDITION, learner.lastRejection());
        assertEquals(0, learner.consecutiveGeometricRejections());
    }

    @Test
    public void resetClearsRejectionTracking() {
        AutoCalibrationLearner learner = new AutoCalibrationLearner(
                CalibrationStore.Status.WIZARD_COMPLETED, 0);
        learner.update(straightLane(), 60.0, wizardAt(20.0));
        assertEquals(1, learner.consecutiveGeometricRejections());

        learner.reset(CalibrationStore.Status.WIZARD_COMPLETED);
        assertEquals(AutoCalibrationLearner.Rejection.NONE, learner.lastRejection());
        assertEquals(0, learner.consecutiveGeometricRejections());
    }

    /** Straight-road observation whose vanishing point lands at y_vp = 0.48 (~1.3 deg), well inside the window. */
    private static LaneDepartureDetector.Observation straightLane() {
        return new LaneDepartureDetector.Observation(0.0, 0.85, true, 0.38, 0.62, 0.20, 0.80);
    }

    /**
     * Vanishing row the legacy two-row extrapolation reports for a lane whose reported top and bottom
     * widths differ by the given amounts. Mirrors the perspective relation rather than hard-coding a
     * number, so the assertion cannot drift away from the implementation silently.
     */
    private static double expectedVanishingY(double widthTop, double widthBottom) {
        double deltaY = LaneDepartureDetector.Y_BOTTOM - LaneDepartureDetector.Y_TOP;
        return LaneDepartureDetector.Y_BOTTOM
                - deltaY * (widthBottom / (widthBottom - widthTop));
    }

    private static CameraCalibration wizardAt(double pitchDegrees) {
        return CameraCalibration.fromWizard(1280, 720, 1.25, 90.0, pitchDegrees);
    }
}
