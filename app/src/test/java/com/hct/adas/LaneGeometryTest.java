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
        // Physical width and height cancel, while the row angles retain pitch sensitivity.
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
            double measured = projectedWidth(calibration, LaneDepartureDetector.ROI_BOTTOM_ROW, truePitch)
                    / projectedWidth(calibration, LaneDepartureDetector.ROI_TOP_ROW, truePitch);
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
            double measuredWidth = projectedWidth(calibration,
                    LaneDepartureDetector.ROI_BOTTOM_ROW, truePitch);
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
        double width = projectedWidth(calibration, rowY, 8.0);
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
        assertEquals(0.05 / width * LANE_WIDTH, left.centerOffsetMeters(), 1.0e-6);
        assertEquals("lane centre must remain left of the image centre",
                0.45, left.laneCenterImageX(), 1.0e-9);
        assertTrue(left.vehicleRightOfCenter());
        assertFalse(left.vehicleLeftOfCenter());

        // Lane centre shifted right: the vehicle sits to the left of the lane.
        LaneGeometry.WidthSample shiftedRight = new LaneGeometry.WidthSample(rowY,
                0.5 - width / 2.0 + 0.05, 0.5 + width / 2.0 + 0.05, 0.9);
        LaneGeometry.LaneSnapshot right = LaneGeometry.snapshot(1L, CALIBRATION, 8.0, shiftedRight,
                List.of(shiftedRight), WIDTH, HEIGHT);
        assertTrue(right.centerOffsetNormalized() < 0.0);
        assertTrue(right.centerOffsetMeters() < 0.0);
        assertEquals(-0.05 / width * LANE_WIDTH, right.centerOffsetMeters(), 1.0e-6);
        assertEquals("lane centre must remain right of the image centre",
                0.55, right.laneCenterImageX(), 1.0e-9);
        assertTrue(right.vehicleLeftOfCenter());
        assertFalse(right.vehicleRightOfCenter());
    }

    @Test
    public void curvatureRadiusMatchesASyntheticArc() {
        // X grows to the right. A negative lateral displacement with increasing ground depth has
        // a negative fitted quadratic coefficient and therefore maps to the published left radius.
        List<LaneGeometry.WidthSample> leftCurve = arcSamples(CALIBRATION, 250.0, 8.0, -1.0);
        double radius = LaneGeometry.curvatureRadiusMeters(CALIBRATION, 8.0, leftCurve,
                WIDTH, HEIGHT);
        assertEquals(-250.0, radius, 25.0);
        assertEquals("Left", LaneGeometry.curveDirection(radius));

        List<LaneGeometry.WidthSample> rightCurve = arcSamples(CALIBRATION, 250.0, 8.0, 1.0);
        double rightRadius = LaneGeometry.curvatureRadiusMeters(CALIBRATION, 8.0, rightCurve,
                WIDTH, HEIGHT);
        assertEquals(250.0, rightRadius, 25.0);
        assertEquals("Right", LaneGeometry.curveDirection(rightRadius));
    }

    @Test
    public void straightLaneIsExplicitlyKnownInsteadOfUnknown() {
        CameraCalibration calibration = CameraCalibration.fromWizard(WIDTH, HEIGHT, 1.25, 90.0, 8.0);
        List<LaneGeometry.WidthSample> straight = new ArrayList<>();
        for (double z : new double[] {6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0}) {
            straight.add(sampleAtDepth(calibration, z, 0.0, 8.0));
        }
        double radius = LaneGeometry.curvatureRadiusMeters(calibration, 8.0, straight, WIDTH, HEIGHT);
        assertTrue("A straight lane must be represented as a known straight result",
                Double.isInfinite(radius));
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
        for (double groundZ : new double[] {6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0}) {
            // Build the world lane centre directly in ground coordinates. The production fit then
            // has to recover this curve using its independently computed camera projection.
            double lateral = direction * (radius
                    - Math.sqrt(radius * radius - groundZ * groundZ));
            samples.add(sampleAtDepth(calibration, groundZ, lateral, pitchDegrees));
        }
        return samples;
    }

    /**
     * One ideal width sample measured at the row that looks {@code z} metres ahead, with the lane
     * centre placed {@code lateral} metres to the right of the optical axis.
     */
    private static LaneGeometry.WidthSample sampleAtDepth(CameraCalibration calibration, double z,
                                                         double lateral, double pitchDegrees) {
        double rowY = projectGroundPoint(calibration, lateral, z, pitchDegrees)[1];
        double leftX = projectGroundPoint(calibration, lateral - LANE_WIDTH / 2.0, z,
                pitchDegrees)[0];
        double rightX = projectGroundPoint(calibration, lateral + LANE_WIDTH / 2.0, z,
                pitchDegrees)[0];
        return new LaneGeometry.WidthSample(rowY, leftX, rightX, 0.9);
    }

    /** Independent ground-world projection, without using the production width model. */
    private static double projectedWidth(CameraCalibration calibration, double row, double pitchDegrees) {
        double beta = Math.atan((row - calibration.principalPointYNormalized())
                / calibration.focalLengthYNormalized());
        double theta = Math.toRadians(pitchDegrees) + beta;
        double groundZ = calibration.cameraHeightMeters() / Math.tan(theta);
        double leftX = projectGroundPoint(calibration, -LANE_WIDTH / 2.0, groundZ,
                pitchDegrees)[0];
        double rightX = projectGroundPoint(calibration, LANE_WIDTH / 2.0, groundZ,
                pitchDegrees)[0];
        return rightX - leftX;
    }

    /** Independent world-ground to image projection using the camera pitch rotation. */
    private static double[] projectGroundPoint(CameraCalibration calibration, double groundX,
                                               double groundZ, double pitchDegrees) {
        double alpha = Math.toRadians(pitchDegrees);
        double cameraZ = groundZ * Math.cos(alpha)
                + calibration.cameraHeightMeters() * Math.sin(alpha);
        double cameraY = calibration.cameraHeightMeters() * Math.cos(alpha)
                - groundZ * Math.sin(alpha);
        double fx = calibration.focalLengthYNormalized() * HEIGHT / WIDTH;
        double imageX = 0.5 + fx * groundX / cameraZ;
        double imageY = calibration.principalPointYNormalized()
                + calibration.focalLengthYNormalized() * cameraY / cameraZ;
        return new double[] {imageX, imageY, cameraZ};
    }

    @Test
    public void lateralProjectionUsesIndependentOpticalDepthRelation() {
        double groundDepth = 10.0;
        double lateral = 1.25;
        double[] projected = projectGroundPoint(CALIBRATION, lateral, groundDepth,
                CALIBRATION.pitchDegrees());
        // Independent pinhole relation after the pitch rotation: x = 0.5 + fx*X/Z_axis.
        double imageX = projected[0];
        double opticalDepth = projected[2];
        assertEquals(lateral, LaneGeometry.lateralMeters(CALIBRATION, imageX, opticalDepth,
                WIDTH, HEIGHT), 1.0e-9);
    }

    @Test
    public void widthMatchesIndependentCameraProjectionAcrossRows() {
        for (double row : new double[] {0.54, 0.68, 0.82}) {
            assertEquals(projectedWidth(CALIBRATION, row, 8.0),
                    LaneGeometry.laneWidthModelMeters(CALIBRATION, row, 8.0,
                            LANE_WIDTH, WIDTH, HEIGHT), 1.0e-9);
        }
    }

    @Test
    public void uncalibratedPitchDoesNotPublishMetricGeometry() {
        List<LaneGeometry.WidthSample> samples = arcSamples(CALIBRATION, 250.0, 8.0, -1.0);
        LaneGeometry.LaneSnapshot snapshot = LaneGeometry.snapshot(1L, CALIBRATION, Double.NaN,
                samples.get(0), samples, WIDTH, HEIGHT);
        assertTrue(snapshot.valid());
        assertTrue(Double.isNaN(snapshot.centerOffsetMeters()));
        assertTrue(Double.isNaN(snapshot.laneWidthMeters()));
        assertFalse(snapshot.curvatureValid());
    }
}
