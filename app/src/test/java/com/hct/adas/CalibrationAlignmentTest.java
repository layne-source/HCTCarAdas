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
    public void coverPreviewFillsWideSafeAreaByCroppingVerticalEdges() {
        CalibrationAlignment.Viewport viewport = CalibrationAlignment.fitCover(
                1280, 640, 1280, 720);

        assertEquals(0.0, viewport.left(), 0.0001);
        assertEquals(-40.0, viewport.top(), 0.0001);
        assertEquals(1280.0, viewport.width(), 0.0001);
        assertEquals(720.0, viewport.height(), 0.0001);
        assertEquals(640.0, viewport.viewY(680.0 / 720.0), 0.0001);
        assertEquals(680.0 / 720.0, viewport.imageY(640.0), 0.0001);
    }

    @Test
    public void navigationBarAndCameraResolutionDoNotChangeAlignedDistance() {
        // 1280x720 screen; each fixture is {source horizon, display row without insets,
        // source contact row of a target 30 m away}. HFOV=90 degrees, camera height=1.3 m.
        double[][] fixtures = {{0.5, 360.0, 0.5385185185185185},
                {0.55, 396.0, 0.5887348096169599}};
        for (int[] size : new int[][] {{1280, 720}, {1920, 1080}}) {
            CameraCalibration draft = CameraCalibration.fromWizard(
                    size[0], size[1], 1.30, 90.0, 0.0);
            for (int navigationBarHeight : new int[] {0, 48, 80, 120}) {
                CalibrationAlignment.Viewport viewport = CalibrationAlignment.fitCover(
                        1280, 720 - navigationBarHeight, size[0], size[1]);
                assertEquals(720.0, viewport.height(), 1e-9);
                assertEquals(-navigationBarHeight / 2.0, viewport.top(), 1e-9);
                assertEquals((720 - navigationBarHeight) / 2.0,
                        viewport.viewY(draft.horizonYNormalized()), 1e-9);
                for (double[] fixture : fixtures) {
                    double touchY = fixture[1] - navigationBarHeight / 2.0;
                    assertEquals(touchY, viewport.viewY(fixture[0]), 1e-9);
                    double imageHorizon = viewport.imageY(touchY);
                    assertEquals(fixture[0], imageHorizon, 1e-9);
                    CameraCalibration saved = CalibrationAlignment.confirm(draft, imageHorizon, 0.5);
                    assertEquals(fixture[0], saved.horizonYNormalized(), 1e-9);
                    // Detection coordinates are from the full source frame, not the cropped view.
                    assertEquals(30.0, saved.estimateDistanceMeters(fixture[2]), 1e-6);
                }
            }
        }
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
        for (double pitch : new double[] {-5.0, 0.0, 2.0, 14.0}) {
            double horizon = 0.5 - (640.0 / 720.0) * Math.tan(Math.toRadians(pitch));
            assertTrue(CalibrationAlignment.evaluate(horizon, 0.62, 640.0 / 720.0, 0.5).valid());
        }
    }

    @Test
    public void alignedGroundDistanceIsIndependentOfMountingPitch() {
        for (int[] size : new int[][] {{1280, 720}, {1920, 1080}, {640, 480}}) {
            for (double hfov : new double[] {90.0, 100.0, 120.0}) {
                double focal = (size[0] / 2.0) / Math.tan(Math.toRadians(hfov / 2.0));
                for (double height : new double[] {1.30, 1.55, 2.00}) {
                    CameraCalibration draft = CameraCalibration.fromWizard(
                            size[0], size[1], height, hfov, 8.0);
                    for (double pitch : new double[] {-5.0, -2.0, 0.0, 1.0, 2.0, 8.0, 14.0}) {
                        double sin = Math.sin(Math.toRadians(pitch));
                        double cos = Math.cos(Math.toRadians(pitch));
                        double horizon = 0.5 - focal / size[1] * sin / cos;
                        CameraCalibration saved = CalibrationAlignment.confirm(draft, horizon, 0.5);
                        assertTrue(CalibrationAlignment.isValid(saved));
                        assertEquals(horizon, saved.horizonYNormalized(), 1e-9);
                        for (double distance : new double[] {20.0, 40.0, 80.0}) {
                            // Rotate the known ground point into camera coordinates, then project.
                            // The expected distance is the original world distance, not a value
                            // obtained from the calibration's inverse projection.
                            double cameraY = height * cos - distance * sin;
                            double cameraZ = distance * cos + height * sin;
                            double bottom = 0.5 + focal * cameraY / cameraZ / size[1];
                            assertTrue(cameraZ > 0.0 && bottom > horizon && bottom < 0.995);
                            assertEquals("pitch=" + pitch + ", HFOV=" + hfov,
                                    distance, saved.estimateDistanceMeters(bottom), 1e-6);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void smallUpwardPitchBoundaryIsAcceptedButSteepMountIsRejected() {
        CameraCalibration draft = CameraCalibration.fromWizard(1280, 720, 1.55, 120.0, 8.0);
        // At 120-degree HFOV, -5 degrees places the horizon at about 54.49% of the frame.
        for (double horizon : new double[] {0.5448992030557626, 0.5448992030567626}) {
            CameraCalibration saved = CalibrationAlignment.confirm(draft, horizon, 0.5);
            assertTrue(CalibrationAlignment.isValid(saved));
            assertEquals(-5.0, saved.pitchDegrees(), 1e-9);
            assertEquals(horizon, saved.horizonYNormalized(), 1e-9);
        }
        assertThrows(IllegalArgumentException.class,
                () -> CalibrationAlignment.confirm(draft, 0.55, 0.5));
    }

    @Test
    public void upwardAlignmentStillRejectsTargetsAtOrAboveHorizon() {
        CameraCalibration saved = CalibrationAlignment.confirm(CALIBRATION, 0.55, 0.5);
        for (double bottom : new double[] {0.50, 0.55, 0.551, 1.0, Double.NaN}) {
            assertTrue(Double.isNaN(saved.estimateDistanceMeters(bottom)));
        }
    }
}
