package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class LaneGeometryTest {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final double LANE_WIDTH = LaneGeometry.DEFAULT_LANE_WIDTH_METERS;
    private static final CameraCalibration CALIBRATION =
            CameraCalibration.fromWizard(WIDTH, HEIGHT, 1.25, 90.0, 8.0);

    @Test
    public void widthRatioIsIndependentOfCameraHeight() {
        CameraCalibration low = CameraCalibration.fromWizard(WIDTH, HEIGHT, 1.25, 90.0, 8.0);
        CameraCalibration high = CameraCalibration.fromWizard(WIDTH, HEIGHT, 2.00, 90.0, 8.0);

        double lowRatio = ratio(low, 8.0);
        double highRatio = ratio(high, 8.0);
        assertTrue("ratio must be finite", Double.isFinite(lowRatio));
        assertEquals(lowRatio, highRatio, 1.0e-6);
    }

    @Test
    public void widthRatioMovesWithPitch() {
        // With the corrected width model the near/far ratio is sin(theta_near)/sin(theta_far), so the
        // physical lane width, the camera height and the focal length all cancel out of the pitch
        // observable. This test pins that sensitivity.
        CameraCalibration calibration = CameraCalibration.fromWizard(WIDTH, HEIGHT, 1.25, 90.0, 8.0);
        double atFour = ratio(calibration, 4.0);
        double atTwelve = ratio(calibration, 12.0);
        assertTrue("a mounting error must move the ratio well beyond measurement noise",
                atFour > atTwelve * 1.4);
    }

    @Test
    public void ratioSolveRecoversTheMountedPitch() {
        CameraCalibration calibration = CameraCalibration.fromWizard(WIDTH, HEIGHT, 1.25, 90.0, 8.0);
        for (double truePitch : new double[] {2.0, 5.0, 8.0, 11.0, 13.0}) {
            double measured = ratio(calibration, truePitch);
            // The bracket has to stay above the horizon pitch of the far row; below it the ratio is no
            // longer monotonic and the solve legitimately fails. See the learner's clamp.
            double lower = Math.max(truePitch - 6.0,
                    AutoCalibrationLearner.horizonPitchDegrees(calibration,
                            LaneDepartureDetector.ROI_TOP_ROW, HEIGHT));
            double solved = LaneGeometry.solvePitchFromRatio(calibration, measured,
                    LaneDepartureDetector.ROI_BOTTOM_ROW, LaneDepartureDetector.ROI_TOP_ROW,
                    WIDTH, HEIGHT, lower, truePitch + 6.0);
            assertEquals("ratio must invert to the mounted pitch", truePitch, solved, 0.05);
        }
    }

    @Test
    public void ratioSolveFailsInsteadOfInvertingPastTheHorizon() {
        CameraCalibration calibration = CameraCalibration.fromWizard(WIDTH, HEIGHT, 1.25, 90.0, 8.0);
        double measured = ratio(calibration, 2.0);
        // A bracket that reaches past the horizon of the far row has no single root, so the solver must
        // refuse rather than return whichever root it happened to bracket.
        assertTrue(Double.isNaN(LaneGeometry.solvePitchFromRatio(calibration, measured,
                LaneDepartureDetector.ROI_BOTTOM_ROW, LaneDepartureDetector.ROI_TOP_ROW,
                WIDTH, HEIGHT, -6.0, 10.0)));
    }

    @Test
    public void impliedPitchFollowsTheMeasuredLaneWidth() {
        CameraCalibration calibration = CameraCalibration.fromWizard(WIDTH, HEIGHT, 1.25, 90.0, 8.0);
        for (double truePitch : new double[] {4.0, 6.0, 8.0, 10.0, 12.0}) {
            double measuredWidth = LaneGeometry.laneWidthModelMeters(calibration,
                    LaneDepartureDetector.ROI_BOTTOM_ROW, truePitch, LANE_WIDTH, WIDTH, HEIGHT);
            double solved = LaneGeometry.solvePitchFromLaneWidth(calibration, LANE_WIDTH,
                    LaneDepartureDetector.ROI_BOTTOM_ROW, measuredWidth, WIDTH, HEIGHT,
                    truePitch - 8.0, truePitch + 8.0);
            assertEquals("pitch must be recovered from the measured lane width",
                    truePitch, solved, 0.05);
        }
    }

    @Test
    public void impliedPitchRejectsWidthsOutsideTheBracket() {
        CameraCalibration calibration = CameraCalibration.fromWizard(WIDTH, HEIGHT, 1.25, 90.0, 8.0);
        // A lane twice as wide as any plausible pitch can explain must fail rather than invert.
        assertTrue(Double.isNaN(LaneGeometry.solvePitchFromLaneWidth(calibration, LANE_WIDTH,
                LaneDepartureDetector.ROI_BOTTOM_ROW, 9.0, WIDTH, HEIGHT, 0.0, 16.0)));
    }

    @Test
    public void recoversThePhysicalLaneWidthFromASample() {
        CameraCalibration calibration = CameraCalibration.fromWizard(WIDTH, HEIGHT, 1.25, 90.0, 8.0);
        double rowY = LaneDepartureDetector.ROI_BOTTOM_ROW;
        double width = LaneGeometry.laneWidthModelMeters(calibration, rowY, 8.0, LANE_WIDTH,
                WIDTH, HEIGHT);
        LaneGeometry.WidthSample sample = new LaneGeometry.WidthSample(rowY,
                0.5 - width / 2.0, 0.5 + width / 2.0, 0.9);

        LaneGeometry.LaneSnapshot snapshot = LaneGeometry.snapshot(1L, calibration, 8.0, sample,
                List.of(sample), WIDTH, HEIGHT);
        assertTrue(snapshot.valid());
        assertEquals(LANE_WIDTH, snapshot.laneWidthMeters(), 0.05);
        assertEquals(0.0, snapshot.centerOffsetNormalized(), 0.01);
        assertEquals(0.0, snapshot.centerOffsetMeters(), 0.05);
    }

    @Test
    public void offsetsAreSignedRightPositiveLeftNegative() {
        double rowY = LaneDepartureDetector.ROI_BOTTOM_ROW;
        double width = LaneGeometry.laneWidthModelMeters(CALIBRATION, rowY, 8.0, LANE_WIDTH,
                WIDTH, HEIGHT);

        // Lane centre shifted left of the vehicle axis: the vehicle sits to the right of the lane, so
        // the offset is positive and the driver has to move left.
        LaneGeometry.WidthSample shiftedLeft = new LaneGeometry.WidthSample(rowY,
                0.5 - width / 2.0 - 0.05, 0.5 + width / 2.0 - 0.05, 0.9);
        LaneGeometry.LaneSnapshot left = LaneGeometry.snapshot(1L, CALIBRATION, 8.0, shiftedLeft,
                List.of(shiftedLeft), WIDTH, HEIGHT);
        assertTrue("vehicle right of the lane centre reads as a positive offset",
                left.centerOffsetNormalized() > 0.0);
        assertTrue(left.centerOffsetMeters() > 0.0);
        assertTrue(left.vehicleRightOfCenter());
        assertFalse(left.vehicleLeftOfCenter());

        // Lane centre shifted right: the vehicle sits to the left of the lane.
        LaneGeometry.WidthSample shiftedRight = new LaneGeometry.WidthSample(rowY,
                0.5 - width / 2.0 + 0.05, 0.5 + width / 2.0 + 0.05, 0.9);
        LaneGeometry.LaneSnapshot right = LaneGeometry.snapshot(1L, CALIBRATION, 8.0, shiftedRight,
                List.of(shiftedRight), WIDTH, HEIGHT);
        assertTrue(right.centerOffsetNormalized() < 0.0);
        assertTrue(right.centerOffsetMeters() < 0.0);
        assertTrue(right.vehicleLeftOfCenter());
        assertFalse(right.vehicleRightOfCenter());
    }

    @Test
    public void curvatureRadiusMatchesASyntheticArc() {
        // A left curve swings the lane centre to the right of the vehicle axis as depth grows, which is
        // a negative radius in the published world frame (ISO 8855), so the direction label reads Left.
        List<LaneGeometry.WidthSample> leftCurve = arcSamples(CALIBRATION, 250.0, 8.0, 1.0);
        double radius = LaneGeometry.curvatureRadiusMeters(CALIBRATION, 8.0, leftCurve,
                WIDTH, HEIGHT);
        assertEquals(-250.0, radius, 25.0);
        assertEquals("Left", LaneGeometry.curveDirection(radius));

        List<LaneGeometry.WidthSample> rightCurve = arcSamples(CALIBRATION, 250.0, 8.0, -1.0);
        double rightRadius = LaneGeometry.curvatureRadiusMeters(CALIBRATION, 8.0, rightCurve,
                WIDTH, HEIGHT);
        assertEquals(250.0, rightRadius, 25.0);
        assertEquals("Right", LaneGeometry.curveDirection(rightRadius));
    }

    @Test
    public void straightLaneHasNoCurvatureInsteadOfAnInfiniteRadius() {
        CameraCalibration calibration = CameraCalibration.fromWizard(WIDTH, HEIGHT, 1.25, 90.0, 8.0);
        List<LaneGeometry.WidthSample> straight = new ArrayList<>();
        for (double z : new double[] {6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0}) {
            straight.add(sampleAtDepth(calibration, z, 0.0, 8.0));
        }
        double radius = LaneGeometry.curvatureRadiusMeters(calibration, 8.0, straight, WIDTH, HEIGHT);
        assertTrue("A straight lane must report no curvature, not a 10 km radius",
                Double.isNaN(radius));
        assertEquals("", LaneGeometry.curveDirection(radius));
    }

    @Test
    public void rowForDistanceInvertsTheGroundModel() {
        CameraCalibration calibration = CameraCalibration.fromWizard(WIDTH, HEIGHT, 1.25, 90.0, 8.0);
        for (double z : new double[] {4.0, 6.0, 10.0, 20.0}) {
            double row = LaneGeometry.rowForDistance(calibration, z);
            assertEquals(z, LaneGeometry.distanceMeters(calibration, row), 0.02);
        }
    }

    private static double ratio(CameraCalibration calibration, double pitchDegrees) {
        return LaneGeometry.widthRatioModel(calibration, LaneDepartureDetector.ROI_BOTTOM_ROW,
                LaneDepartureDetector.ROI_TOP_ROW, pitchDegrees, LANE_WIDTH, WIDTH, HEIGHT);
    }
    /** Width samples of a lane-following vehicle on a circular arc of the given signed radius. */
    private static List<LaneGeometry.WidthSample> arcSamples(CameraCalibration calibration,
                                                             double radius, double pitchDegrees,
                                                             double direction) {
        List<LaneGeometry.WidthSample> samples = new ArrayList<>();
        for (double z : new double[] {6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0}) {
            double lateral = direction * (radius - Math.sqrt(radius * radius - z * z));
            samples.add(sampleAtDepth(calibration, z, lateral, pitchDegrees));
        }
        return samples;
    }

    /**
     * One ideal width sample measured at the row that looks {@code z} metres ahead, with the lane
     * centre placed {@code lateral} metres to the right of the optical axis.
     */
    private static LaneGeometry.WidthSample sampleAtDepth(CameraCalibration calibration, double z,
                                                         double lateral, double pitchDegrees) {
        double rowY = LaneGeometry.rowForDistance(calibration, z);
        double width = LaneGeometry.laneWidthModelMeters(calibration, rowY, pitchDegrees,
                LANE_WIDTH, WIDTH, HEIGHT);
        double fx = LaneGeometry.focalLengthXNormalized(calibration, WIDTH, HEIGHT);
        double centerX = 0.5 + lateral * fx / z;
        return new LaneGeometry.WidthSample(rowY, centerX - width / 2.0, centerX + width / 2.0, 0.9);
    }
}
