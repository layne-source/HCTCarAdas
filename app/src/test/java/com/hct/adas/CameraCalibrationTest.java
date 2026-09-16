package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CameraCalibrationTest {
    @Test
    public void acceptsAndAppliesCalibrationForItsBoundImageSize() {
        CameraCalibration calibration = new CameraCalibration(
                1280, 720, 1.35, 0.92, 0.50, 0.0);

        assertTrue(calibration.isUsableFor(1280, 720));
        assertFalse(calibration.isUsableFor(640, 480));
        assertEquals(4.96, calibration.estimateDistanceMeters(0.75), 0.01);
    }

    @Test
    public void returnsUnknownDistanceForInvalidGeometry() {
        CameraCalibration calibration = new CameraCalibration(
                1280, 720, 1.35, 0.92, 0.50, 0.0);

        assertTrue(Double.isNaN(calibration.estimateDistanceMeters(0.50)));
        assertTrue(Double.isNaN(calibration.estimateDistanceMeters(1.01)));
        assertTrue(Double.isNaN(calibration.estimateDistanceMeters(0.49)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPhysicalHeight() {
        new CameraCalibration(1280, 720, 0.0, 0.92, 0.50, 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsFocalLengthOutsideImageScale() {
        new CameraCalibration(1280, 720, 1.35, 20.0, 0.50, 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPrincipalPointOutsideImage() {
        new CameraCalibration(1280, 720, 1.35, 0.92, 1.01, 0.0);
    }
}
