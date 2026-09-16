package com.hct.adas;

import static org.junit.Assert.assertEquals;
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
        assertTrue(moving.closingSpeedMps() > 0.0);
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

    private static LeadVehicleTracker.Snapshot snapshot(long id, float bottom, long millis) {
        VehicleDetector.Detection box = new VehicleDetector.Detection(
                "car", 0.9f, 0.40f, bottom - 0.2f, 0.60f, bottom);
        return new LeadVehicleTracker.Snapshot(millis * 1_000_000L,
                LeadVehicleTracker.State.TRACKING, id, box);
    }
}
