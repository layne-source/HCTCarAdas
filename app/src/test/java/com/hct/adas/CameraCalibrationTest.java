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
        assertTrue(Double.isNaN(calibration.estimateDistanceMeters(0.501)));
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

    @Test
    public void withPitchDegreesReturnsNewInstanceWithUpdatedPitch() {
        CameraCalibration base = new CameraCalibration(1280, 720, 1.35, 0.92, 0.50, 0.0);
        CameraCalibration updated = base.withPitchDegrees(3.5);
        assertEquals(3.5, updated.pitchDegrees(), 0.001);
        assertEquals(1.35, updated.cameraHeightMeters(), 0.001);
        assertEquals(base.focalLengthYNormalized(), updated.focalLengthYNormalized(), 0.001);
    }

    @Test
    public void computesHorizonNormalizedAccurately() {
        CameraCalibration zeroPitch = new CameraCalibration(1280, 720, 1.35, 0.92, 0.50, 0.0);
        assertEquals(0.50, zeroPitch.horizonYNormalized(), 0.001);

        CameraCalibration downPitch = new CameraCalibration(1280, 720, 1.35, 0.92, 0.50, 5.0);
        // horizon = 0.50 - 0.92 * tan(5 deg) ≈ 0.50 - 0.92 * 0.08749 = 0.4195
        assertTrue(downPitch.horizonYNormalized() < 0.50);
        assertEquals(0.4195, downPitch.horizonYNormalized(), 0.001);
    }

    @Test
    public void createsValidCalibrationFromWizard() {
        CameraCalibration calib = CameraCalibration.fromWizard(1280, 720, 1.25, 90.0, 4.0);
        assertEquals(1280, calib.imageWidth());
        assertEquals(720, calib.imageHeight());
        assertEquals(1.25, calib.cameraHeightMeters(), 0.001);
        assertEquals(4.0, calib.pitchDegrees(), 0.001);
        // fx = (1280 / 2) / tan(45 deg) = 640 => fy_norm = 640 / 720 ≈ 0.8889
        assertEquals(640.0 / 720.0, calib.focalLengthYNormalized(), 0.001);
        assertEquals(0.50, calib.principalPointYNormalized(), 0.001);
    }

    @Test
    public void fixedGuideCenterSurvivesPitchUpdateWithoutChangingDistanceScale() {
        CameraCalibration centered = new CameraCalibration(1280, 720, 1.35, 0.92, 0.50, 8.0);
        CameraCalibration shiftedGuide = new CameraCalibration(
                1280, 720, 1.35, 0.92, 0.50, 8.0, 0.56);
        assertEquals(centered.estimateDistanceMeters(0.75),
                shiftedGuide.estimateDistanceMeters(0.75), 0.000001);
        assertEquals(0.56, shiftedGuide.withPitchDegrees(9.0).guideCenterXNormalized(), 0.000001);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonFiniteGuideCenter() {
        new CameraCalibration(1280, 720, 1.35, 0.92, 0.50, 8.0, Double.NaN);
    }
}
