package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class CalibrationAlignmentTest {
    private static final CameraCalibration CALIBRATION = CameraCalibration.fromWizard(
            1280, 720, 1.55, 90.0, 8.0);

    @Test
    public void horizonLineRoundTripsToConfiguredPitch() {
        double horizon = CALIBRATION.horizonYNormalized();

        CalibrationAlignment.Result result = CalibrationAlignment.evaluate(
                horizon, 0.5, CALIBRATION.focalLengthYNormalized(),
                CALIBRATION.principalPointYNormalized());

        assertTrue(result.valid());
        assertEquals(8.0, result.pitchDegrees(), 0.05);
    }

    @Test
    public void largeVerticalAlignmentIsRejected() {
        CalibrationAlignment.Result result = CalibrationAlignment.evaluate(
                CALIBRATION.horizonYNormalized(), 0.64,
                CALIBRATION.focalLengthYNormalized(),
                CALIBRATION.principalPointYNormalized());

        assertFalse(result.valid());
        assertTrue(result.centerOutOfRange());
    }

    @Test
    public void horizonOutsideSupportedPitchRangeIsRejected() {
        CalibrationAlignment.Result result = CalibrationAlignment.evaluate(
                0.02, 0.5, CALIBRATION.focalLengthYNormalized(),
                CALIBRATION.principalPointYNormalized());

        assertFalse(result.valid());
        assertTrue(result.pitchOutOfRange());
    }

    @Test
    public void letterboxedPreviewUsesImageCoordinatesForBothLines() {
        // 16:9 camera in a 1000x800 view: image is 1000x562.5, starting at y=118.75.
        CalibrationAlignment.Viewport viewport = CalibrationAlignment.fitCenter(
                1000, 800, 1280, 720);
        assertEquals(118.75, viewport.top(), 0.0001);
        assertEquals(287.5, viewport.viewY(0.30), 0.0001);
        assertEquals(0.30, viewport.imageY(287.5), 0.0001);
        assertEquals(0.56, viewport.imageX(560), 0.0001);
        assertFalse(viewport.contains(500, 100));
        assertTrue(viewport.contains(500, 300));
    }

    @Test
    public void pillarboxedPreviewExcludesSideBarsAndSupports1080p() {
        // Camera occupies x=200..1000, y=0..450 in an extra-wide view.
        CalibrationAlignment.Viewport viewport = CalibrationAlignment.fitCenter(
                1200, 450, 1920, 1080);
        assertEquals(200, viewport.left(), 0.0001);
        assertEquals(640, viewport.viewX(0.55), 0.0001);
        assertEquals(0.55, viewport.imageX(640), 0.0001);
        assertFalse(viewport.contains(100, 225));
    }

    @Test
    public void confirmationUsesSelectedFovAndPersistsFixedGuideCenter() {
        CameraCalibration selectedLens = CameraCalibration.fromWizard(
                1280, 720, 1.55, 120.0, 8.0);
        CameraCalibration saved = CalibrationAlignment.confirm(selectedLens, 0.40, 0.56);
        // fy=640/tan(60deg)/720=0.5132; atan(0.1/fy)=11.0262deg.
        assertEquals(11.0262, saved.pitchDegrees(), 0.001);
        assertEquals(0.40, saved.horizonYNormalized(), 0.000001);
        assertEquals(0.56, saved.guideCenterXNormalized(), 0.000001);
        assertEquals(selectedLens.focalLengthYNormalized(), saved.focalLengthYNormalized(), 0.000001);
    }

    @Test
    public void invalidAlignmentCannotCreateSavedCalibration() {
        assertThrows(IllegalArgumentException.class,
                () -> CalibrationAlignment.confirm(CALIBRATION, 0.40, 0.8));
        assertThrows(IllegalArgumentException.class,
                () -> CalibrationAlignment.confirm(CALIBRATION, Double.NaN, 0.5));
    }

    @Test
    public void savedProfileRejectsOffImageHorizonEvenWithSupportedPitch() {
        CameraCalibration invalid = new CameraCalibration(1280, 720, 1.55, 4.0, 0.5, 14.0);
        assertFalse(CalibrationAlignment.isValid(invalid));
    }

    @Test
    public void confirmedImageEdgeHorizonSurvivesSavedProfileValidation() {
        CameraCalibration lens = new CameraCalibration(1280, 720, 1.55, 4.0, 0.5, 8.0);
        assertTrue(CalibrationAlignment.isValid(CalibrationAlignment.confirm(lens, 0.0, 0.5)));
    }

    @Test
    public void boundaryPitchSurvivesFloatingPointRoundTrip() {
        for (double pitch : new double[] {2.0, 14.0}) {
            double horizon = 0.5 - (640.0 / 720.0) * Math.tan(Math.toRadians(pitch));
            assertTrue(CalibrationAlignment.evaluate(horizon, 0.62, 640.0 / 720.0, 0.5).valid());
        }
    }
}
