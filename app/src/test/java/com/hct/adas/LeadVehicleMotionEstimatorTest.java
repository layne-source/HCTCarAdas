package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class LeadVehicleMotionEstimatorTest {
    private static final CameraCalibration CALIBRATION = new CameraCalibration(
            1280, 720, 1.35, 0.92, 0.50, 0.0);

    @Test
    public void estimatesSmoothedClosingSpeedForSameTarget() {
        LeadVehicleMotionEstimator estimator = new LeadVehicleMotionEstimator();
        LeadVehicleTracker.Snapshot first = snapshot(1, 0.75f, 2L);
        LeadVehicleTracker.Snapshot second = snapshot(1, 0.80f, 202L);

        LeadVehicleMotionEstimator.Measurement initial = estimator.update(
                first, CALIBRATION, 1280, 720);
        LeadVehicleMotionEstimator.Measurement moving = estimator.update(
                second, CALIBRATION, 1280, 720);

        assertTrue(initial.visible());
        assertEquals(1L, moving.trackId());
        assertTrue(moving.distanceMeters() < initial.distanceMeters());
        assertTrue("The first valid delta must establish closing speed promptly",
                moving.closingSpeedMps() > 3.0);
    }

    @Test
    public void resetsVelocityWhenTargetIdChangesOrDistanceIsInvalid() {
        LeadVehicleMotionEstimator estimator = new LeadVehicleMotionEstimator();
        estimator.update(snapshot(1, 0.75f, 2L), CALIBRATION, 1280, 720);
        LeadVehicleMotionEstimator.Measurement changed = estimator.update(
                snapshot(2, 0.80f, 202L), CALIBRATION, 1280, 720);
        assertEquals(0.0, changed.closingSpeedMps(), 0.0001);

        LeadVehicleMotionEstimator.Measurement invalid = estimator.update(
                snapshot(2, 0.50f, 402L), CALIBRATION, 1280, 720);
        assertTrue(!invalid.visible());
    }

    @Test
    public void clearsVelocityHistoryAfterLostBeforeTargetReappears() {
        LeadVehicleMotionEstimator estimator = new LeadVehicleMotionEstimator();
        estimator.update(snapshot(1, 0.75f, 2L), CALIBRATION, 1280, 720);
        LeadVehicleMotionEstimator.Measurement moving = estimator.update(
                snapshot(1, 0.80f, 202L), CALIBRATION, 1280, 720);
        assertTrue(moving.closingSpeedMps() > 0.0);

        LeadVehicleTracker.Snapshot lost = new LeadVehicleTracker.Snapshot(
                302_000_000L, LeadVehicleTracker.State.LOST, 1L, null);
        assertTrue(!estimator.update(lost, CALIBRATION, 1280, 720).visible());

        LeadVehicleMotionEstimator.Measurement recovered = estimator.update(
                snapshot(1, 0.85f, 402L), CALIBRATION, 1280, 720);
        assertTrue(recovered.visible());
        assertEquals("a LOST gap must not create a stale closing-speed spike",
                0.0, recovered.closingSpeedMps(), 0.0001);
    }

    @Test
    public void rejectsBoundingBoxClippedByImageBorder() {
        LeadVehicleMotionEstimator estimator = new LeadVehicleMotionEstimator();
        VehicleDetector.Detection clipped = new VehicleDetector.Detection(
                "car", 0.9f, 0.40f, 0.70f, 0.60f, 1.0f);
        LeadVehicleTracker.Snapshot snapshot = new LeadVehicleTracker.Snapshot(
                1L, LeadVehicleTracker.State.TRACKING, 1L, clipped);

        assertTrue(!estimator.update(snapshot, CALIBRATION, 1280, 720).visible());
    }

    @Test
    public void startupBoxReboundDoesNotBecomeThreeFrameCollisionWarning() {
        assertNoStartupCollision(new double[] {15, 15, 15, 13, 15, 15, 15},
                new long[] {0, 200, 400, 600, 800, 1000, 1200});
        assertNoStartupCollision(new double[] {15, 15, 15, 13, 15, 15, 15},
                new long[] {0, 200, 400, 550, 850, 1050, 1250});
    }

    @Test
    public void startupSingleDistanceStepDoesNotLeaveAFalseClosingSpeed() {
        assertNoStartupCollision(new double[] {15, 15, 15, 13, 13, 13, 13},
                new long[] {0, 200, 400, 600, 800, 1000, 1200});
    }

    @Test
    public void alternatingStartupJitterNeverSeedsPersistentCollisionRisk() {
        assertNoStartupCollision(new double[] {15, 15, 15, 13, 15, 13, 15, 15, 15},
                new long[] {0, 200, 400, 600, 800, 1000, 1200, 1400, 1600});
        assertNoStartupCollision(new double[] {15, 15, 15, 13, 15, 13, 15, 15, 15},
                new long[] {0, 200, 400, 550, 850, 1000, 1300, 1500, 1700});
    }

    @Test
    public void sustainedApproachStillInitializesPromptlyAndTriggersFcw() {
        CameraCalibration calibration = CameraCalibration.fromWizard(1280, 720, 1.3, 90, 8);
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        LeadVehicleMotionEstimator estimator = new LeadVehicleMotionEstimator();
        AdasDecisionEngine engine = new AdasDecisionEngine();
        double[] distances = {22, 22, 22, 20, 18, 16};
        boolean warned = false;
        for (int i = 0; i < distances.length; i++) {
            LeadVehicleTracker.Snapshot track = tracker.update(projectedFrame(distances[i], i * 200L),
                    null, calibration);
            LeadVehicleMotionEstimator.Measurement motion = estimator.update(track, calibration, 1280, 720);
            if (i >= 3) {
                assertEquals("A real 10 m/s approach must not ramp slowly from zero",
                        10.0, motion.closingSpeedMps(), 0.001);
            }
            warned |= engine.update(observation(i * 200L, motion)).events().contains(AdasDecisionEngine.Alert.FCW);
        }
        assertTrue(warned);
    }

    private static void assertNoStartupCollision(double[] distances, long[] times) {
        CameraCalibration calibration = CameraCalibration.fromWizard(1280, 720, 1.3, 90, 8);
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        LeadVehicleMotionEstimator estimator = new LeadVehicleMotionEstimator();
        AdasDecisionEngine engine = new AdasDecisionEngine();
        long id = 0L;
        LeadVehicleMotionEstimator.Measurement motion = null;
        for (int i = 0; i < distances.length; i++) {
            LeadVehicleTracker.Snapshot track = tracker.update(projectedFrame(distances[i], times[i]),
                    null, calibration);
            if (i == 2) {
                id = track.trackId();
                assertTrue(id > 0L);
            }
            if (i >= 2) {
                assertEquals("The jitter must keep the same track to exercise speed initialization", id, track.trackId());
            }
            motion = estimator.update(track, calibration, 1280, 720);
            assertFalse(engine.update(observation(times[i], motion)).events().contains(AdasDecisionEngine.Alert.FCW));
        }
        assertEquals(0.0, motion.closingSpeedMps(), 0.001);
    }

    private static AdasDecisionEngine.Observation observation(long millis,
                                                              LeadVehicleMotionEstimator.Measurement motion) {
        return new AdasDecisionEngine.Observation(millis, 60.0, motion.distanceMeters(),
                motion.closingSpeedMps(), motion.targetAreaPixels(), motion.visible());
    }

    private static VehicleDetector.Result projectedFrame(double distance, long millis) {
        // Independent ground-plane projection for the fixture lens (HFOV 90 degrees, pitch 8).
        float bottom = (float) (0.5 + (1280.0 / (2.0 * 720.0))
                * Math.tan(Math.atan2(1.3, distance) - Math.toRadians(8.0)));
        VehicleDetector.Detection box = new VehicleDetector.Detection(
                "car", 0.9f, 0.40f, bottom - 0.2f, 0.60f, bottom);
        return new VehicleDetector.Result(millis * 1_000_000L, 1280, 720, 0, List.of(box));
    }

    private static LeadVehicleTracker.Snapshot snapshot(long id, float bottom, long millis) {
        VehicleDetector.Detection box = new VehicleDetector.Detection(
                "car", 0.9f, 0.40f, bottom - 0.2f, 0.60f, bottom);
        return new LeadVehicleTracker.Snapshot(millis * 1_000_000L,
                LeadVehicleTracker.State.TRACKING, id, box);
    }
}
