package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FixedGuideGeometryTest {
    private static final double EPSILON = 0.000001;
    // atan(0.1) degrees: fy=1 and cy=0.5 place the horizon at exactly 0.4.
    private static final double FIXTURE_PITCH = 5.710593137499643;

    @Test
    public void templateConvergesOnSavedHorizonAndKeepsSavedCenter() {
        FixedGuideGeometry geometry = FixedGuideGeometry.fromCalibration(
                calibration(1280, 720, FIXTURE_PITCH, 0.56), 1280, 720);

        assertNotNull(geometry);
        assertEquals(0.56, geometry.centerX(), EPSILON);
        assertEquals(0.4, geometry.horizonY(), EPSILON);
        assertEquals(0.46, geometry.farY(), EPSILON);
        assertEquals(0.9, geometry.nearY(), EPSILON);
        assertEquals(0.0264, geometry.halfWidthAt(0.46), EPSILON);
        assertEquals(0.11, geometry.halfWidthAt(0.65), EPSILON);
        assertEquals(0.22, geometry.halfWidthAt(0.9), EPSILON);
        assertEquals(0.0, geometry.halfWidthAt(0.4), EPSILON);
    }

    @Test
    public void templateAcceptsDisplayAnchoredNearRow() {
        FixedGuideGeometry geometry = FixedGuideGeometry.fromCalibration(
                calibration(1280, 720, FIXTURE_PITCH, 0.56), 1280, 720, 0.94);

        assertNotNull(geometry);
        assertEquals(0.94, geometry.nearY(), EPSILON);
        assertEquals(0.46, geometry.farY(), EPSILON);
        assertEquals(0.22, geometry.halfWidthAt(0.94), EPSILON);
    }

    @Test
    public void letterboxed720pGuideUsesImageCoordinates() {
        FixedGuideGeometry geometry = FixedGuideGeometry.fromCalibration(
                calibration(1280, 720, FIXTURE_PITCH, 0.56), 1280, 720);
        CalibrationAlignment.Viewport viewport = CalibrationAlignment.fitCenter(
                1000, 800, 1280, 720);

        assertNotNull(geometry);
        assertEquals(118.75, viewport.top(), EPSILON);
        assertEquals(377.5, viewport.viewY(geometry.farY()), EPSILON);
        assertEquals(625.0, viewport.viewY(geometry.nearY()), EPSILON);
        assertEquals(340.0, viewport.viewX(
                geometry.centerX() - geometry.nearHalfWidth()), EPSILON);
        assertEquals(780.0, viewport.viewX(
                geometry.centerX() + geometry.nearHalfWidth()), EPSILON);
    }

    @Test
    public void pillarboxed1080pRetainsSameNormalizedTemplate() {
        FixedGuideGeometry geometry = FixedGuideGeometry.fromCalibration(
                calibration(1920, 1080, FIXTURE_PITCH, 0.56), 1920, 1080);
        CalibrationAlignment.Viewport viewport = CalibrationAlignment.fitCenter(
                1200, 450, 1920, 1080);

        assertNotNull(geometry);
        assertEquals(200.0, viewport.left(), EPSILON);
        assertEquals(207.0, viewport.viewY(geometry.farY()), EPSILON);
        assertEquals(405.0, viewport.viewY(geometry.nearY()), EPSILON);
        assertEquals(472.0, viewport.viewX(
                geometry.centerX() - geometry.nearHalfWidth()), EPSILON);
        assertEquals(824.0, viewport.viewX(
                geometry.centerX() + geometry.nearHalfWidth()), EPSILON);
    }

    @Test
    public void fourByThreeFallbackFitsWithoutStretchingTheTemplate() {
        FixedGuideGeometry geometry = FixedGuideGeometry.fromCalibration(
                calibration(640, 480, FIXTURE_PITCH, 0.56), 640, 480);
        CalibrationAlignment.Viewport viewport = CalibrationAlignment.fitCenter(
                1280, 720, 640, 480);

        assertNotNull(geometry);
        assertEquals(160.0, viewport.left(), EPSILON);
        assertEquals(331.2, viewport.viewY(geometry.farY()), EPSILON);
        assertEquals(648.0, viewport.viewY(geometry.nearY()), EPSILON);
        assertEquals(486.4, viewport.viewX(
                geometry.centerX() - geometry.nearHalfWidth()), EPSILON);
        assertEquals(908.8, viewport.viewX(
                geometry.centerX() + geometry.nearHalfWidth()), EPSILON);
    }

    @Test
    public void pitchAndCenterBoundariesStayInsideImageForSupportedLenses() {
        for (double hfov : new double[] {60.0, 90.0, 120.0}) {
            for (double pitch : new double[] {-5.0, -2.0, 0.0, 2.0, 14.0}) {
                for (double center : new double[] {0.38, 0.62}) {
                    CameraCalibration lens = CameraCalibration.fromWizard(
                            1280, 720, 1.55, hfov, pitch);
                    CameraCalibration saved = new CameraCalibration(1280, 720,
                            lens.cameraHeightMeters(), lens.focalLengthYNormalized(),
                            lens.principalPointYNormalized(), pitch, center);
                    FixedGuideGeometry geometry = FixedGuideGeometry.fromCalibration(
                            saved, 1280, 720);

                    assertNotNull(geometry);
                    assertEquals(center, geometry.centerX(), EPSILON);
                    assertTrue(geometry.farY() > geometry.horizonY());
                    assertTrue(geometry.farY() < geometry.nearY());
                    assertTrue(geometry.halfWidthAt(geometry.farY()) > 0.0);
                    assertTrue(geometry.halfWidthAt(geometry.farY()) < 0.22);
                    assertTrue(geometry.centerX() - geometry.nearHalfWidth() >= 0.0);
                    assertTrue(geometry.centerX() + geometry.nearHalfWidth() <= 1.0);
                }
            }
        }
    }

    @Test
    public void confirmedBoundaryCalibrationSurvivesPitchRoundTrip() {
        for (double pitch : new double[] {-5.0, 0.0, 2.0, 14.0}) {
            CameraCalibration lens = CameraCalibration.fromWizard(
                    1280, 720, 1.55, 120.0, pitch);
            CameraCalibration saved = CalibrationAlignment.confirm(
                    lens, lens.horizonYNormalized(), 0.62);

            assertNotNull(FixedGuideGeometry.fromCalibration(saved, 1280, 720));
        }
    }

    @Test
    public void rejectsMissingCalibrationAndAnyImageSizeMismatch() {
        CameraCalibration saved = calibration(1280, 720, FIXTURE_PITCH, 0.5);

        assertNull(FixedGuideGeometry.fromCalibration(null, 1280, 720));
        assertNull(FixedGuideGeometry.fromCalibration(saved, 1920, 1080));
        assertNull(FixedGuideGeometry.fromCalibration(saved, 720, 1280));
        assertNull(FixedGuideGeometry.fromCalibration(saved, 0, 720));
        assertNull(FixedGuideGeometry.fromCalibration(saved, 1280, -1));
    }

    @Test
    public void rejectsPitchAndCenterOutsideInstallationLimits() {
        assertNull(FixedGuideGeometry.fromCalibration(
                calibration(1280, 720, -5.01, 0.5), 1280, 720));
        assertNull(FixedGuideGeometry.fromCalibration(
                calibration(1280, 720, 14.1, 0.5), 1280, 720));
        assertNull(FixedGuideGeometry.fromCalibration(
                calibration(1280, 720, FIXTURE_PITCH, 0.379), 1280, 720));
        assertNull(FixedGuideGeometry.fromCalibration(
                calibration(1280, 720, FIXTURE_PITCH, 0.621), 1280, 720));
    }

    @Test
    public void clampedHorizonCannotHideAnOutOfRangeOriginalPitch() {
        // This projects above the image, then horizonYNormalized() clamps to zero. The
        // clamped horizon alone implies 7.125 degrees and would pass the alignment gate.
        CameraCalibration saved = new CameraCalibration(
                1280, 720, 1.55, 4.0, 0.5, 45.0, 0.5);

        assertEquals(0.0, saved.horizonYNormalized(), EPSILON);
        assertNull(FixedGuideGeometry.fromCalibration(saved, 1280, 720));
    }

    @Test
    public void rejectsTemplateWhoseFarEndReachesOrPassesNearEnd() {
        CameraCalibration saved = new CameraCalibration(
                1280, 720, 1.55, 0.1, 1.0, 2.0, 0.5);

        assertNull(FixedGuideGeometry.fromCalibration(saved, 1280, 720));
    }

    @Test
    public void croppedOutGuideDoesNotDisableDistanceCalibration() {
        CameraCalibration saved = CalibrationAlignment.confirm(
                CameraCalibration.fromWizard(1280, 720, 1.55, 90.0, 8.0), 0.55, 0.5);
        assertNull(FixedGuideGeometry.fromCalibration(saved, 1280, 720, 0.60));
        assertTrue(AdasCalibrationMode.distanceReady(
                CalibrationStore.Status.DISTANCE_READY, saved, 1280, 720));
        assertTrue(Double.isFinite(saved.estimateDistanceMeters(0.65)));
    }

    @Test
    public void changingVisualCenterDoesNotChangeHorizonWidthOrDistanceProjection() {
        CameraCalibration left = calibration(1280, 720, FIXTURE_PITCH, 0.38);
        CameraCalibration right = calibration(1280, 720, FIXTURE_PITCH, 0.62);
        FixedGuideGeometry leftGuide = FixedGuideGeometry.fromCalibration(left, 1280, 720);
        FixedGuideGeometry rightGuide = FixedGuideGeometry.fromCalibration(right, 1280, 720);

        assertNotNull(leftGuide);
        assertNotNull(rightGuide);
        assertEquals(0.16, leftGuide.centerX() - leftGuide.nearHalfWidth(), EPSILON);
        assertEquals(0.84, rightGuide.centerX() + rightGuide.nearHalfWidth(), EPSILON);
        assertEquals(0.4, leftGuide.horizonY(), EPSILON);
        assertEquals(0.4, rightGuide.horizonY(), EPSILON);
        assertEquals(0.11, leftGuide.halfWidthAt(0.65), EPSILON);
        assertEquals(0.11, rightGuide.halfWidthAt(0.65), EPSILON);
        // tan(atan(0.1) + atan(0.25)) = 0.35 / 0.975; height is 1.55 m.
        assertEquals(4.317857142857143, left.estimateDistanceMeters(0.75), EPSILON);
        assertEquals(4.317857142857143, right.estimateDistanceMeters(0.75), EPSILON);
        assertEquals(0.5, left.principalPointYNormalized(), EPSILON);
        assertEquals(0.5, right.principalPointYNormalized(), EPSILON);
    }

    private static CameraCalibration calibration(int width, int height,
                                                 double pitch, double center) {
        return new CameraCalibration(width, height, 1.55, 1.0, 0.5, pitch, center);
    }
}
