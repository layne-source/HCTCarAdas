package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

public final class AdasUiStatusMapperTest {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final CameraCalibration CALIBRATION =
            CameraCalibration.fromWizard(WIDTH, HEIGHT, 1.25, 90.0, 8.0);

    @Test
    public void forwardRiskBandsFollowTimeToCollision() {
        AdasDecisionEngine.Decision clear = decision(false, false, false, false);
        assertEquals(AdasUiStatusMapper.RiskLevel.NORMAL,
                AdasUiStatusMapper.forward(clear, true, 3.5, 30.0, 60.0, false).risk());
        assertEquals("Normal Risk",
                AdasUiStatusMapper.forward(clear, true, 3.5, 30.0, 60.0, false).riskText());
        assertEquals(AdasUiStatusMapper.COLOR_NORMAL,
                AdasUiStatusMapper.forward(clear, true, 3.5, 30.0, 60.0, false).riskColor());

        assertEquals(AdasUiStatusMapper.RiskLevel.PROMPT,
                AdasUiStatusMapper.forward(clear, true, 2.0, 25.0, 60.0, false).risk());
        assertEquals("Prompt Risk",
                AdasUiStatusMapper.forward(clear, true, 2.0, 25.0, 60.0, false).riskText());

        assertEquals(AdasUiStatusMapper.RiskLevel.WARNING,
                AdasUiStatusMapper.forward(clear, true, 0.9, 12.0, 60.0, false).risk());
        assertEquals("Warning Risk",
                AdasUiStatusMapper.forward(clear, true, 0.9, 12.0, 60.0, false).riskText());
        assertEquals(AdasUiStatusMapper.COLOR_WARNING,
                AdasUiStatusMapper.forward(clear, true, 0.9, 12.0, 60.0, false).riskColor());
    }

    @Test
    public void headwayCautionPromotesToPromptWithoutAClosingSpeed() {
        AdasDecisionEngine.Decision headway = decision(false, true, false, false);
        AdasUiStatusMapper.Status status =
                AdasUiStatusMapper.forward(headway, true, Double.NaN, 9.0, 60.0, true);
        assertEquals(AdasUiStatusMapper.RiskLevel.PROMPT, status.risk());
    }

    @Test
    public void undecidedRiskIsNotReportedAsNormal() {
        // A lead vehicle is measured but no closing speed exists yet, and the headway is comfortable:
        // the band is unknown rather than normal.
        AdasUiStatusMapper.Status status = AdasUiStatusMapper.forward(
                decision(false, false, false, false), true, Double.NaN, 30.0, 60.0, false);
        assertEquals(AdasUiStatusMapper.RiskLevel.INACTIVE, status.risk());
        assertEquals(AdasUiStatusMapper.INACTIVE_TEXT, status.riskText());
        assertEquals(AdasUiStatusMapper.COLOR_INACTIVE, status.riskColor());
    }

    @Test
    public void closeHeadwayPromotesToPromptEvenWithoutAClosingSpeed() {
        // 25 m at 60 km/h is a 1.5 s headway, at the prompt threshold.
        AdasUiStatusMapper.Status status = AdasUiStatusMapper.forward(
                decision(false, false, false, false), true, Double.NaN, 25.0, 60.0, false);
        assertEquals(AdasUiStatusMapper.RiskLevel.PROMPT, status.risk());
    }

    @Test
    public void missingLeadVehicleOrSpeedReportsDetermination() {
        assertEquals("Determined …", AdasUiStatusMapper.forward(
                decision(false, false, false, false), false, Double.NaN, Double.NaN, 60.0, false)
                .riskText());
        assertEquals("Determined …", AdasUiStatusMapper.forward(
                decision(false, false, false, false), true, 3.0, 30.0, Double.NaN, false)
                .riskText());
    }

    @Test
    public void collisionDangerAlwaysWarns() {
        AdasUiStatusMapper.Status status = AdasUiStatusMapper.forward(
                decision(true, false, false, false), true, 5.0, 40.0, 60.0, false);
        assertEquals(AdasUiStatusMapper.RiskLevel.WARNING, status.risk());
    }

    @Test
    public void departureTextNamesTheDirectionTheVehicleMustMove() {
        // The vehicle sits right of the lane centre, so it has to move left.
        AdasUiStatusMapper.LaneStatus rightOfCentre = AdasUiStatusMapper.lane(
                lane(0.18, 900.0), true, false);
        assertEquals("Please Keep Left", rightOfCentre.departure());
        assertEquals(AdasUiStatusMapper.COLOR_PROMPT, rightOfCentre.departureColor());

        // The vehicle sits left of the lane centre, so it has to move right.
        AdasUiStatusMapper.LaneStatus leftOfCentre = AdasUiStatusMapper.lane(
                lane(-0.20, 900.0), true, false);
        assertEquals("Please Keep Right", leftOfCentre.departure());

        // Inside the threshold the lane keeping is good.
        AdasUiStatusMapper.LaneStatus centered = AdasUiStatusMapper.lane(
                lane(0.05, 900.0), true, false);
        assertEquals("Good Lane Keeping", centered.departure());
        assertEquals(AdasUiStatusMapper.COLOR_NORMAL, centered.departureColor());
    }

    @Test
    public void laneSteeringWarningEscalatesTheDepartureColour() {
        AdasUiStatusMapper.LaneStatus withoutEngineFlag = AdasUiStatusMapper.lane(
                lane(0.20, 900.0), true, false);
        AdasUiStatusMapper.LaneStatus withEngineFlag = AdasUiStatusMapper.lane(
                lane(0.20, 900.0), true, true);
        assertEquals(AdasUiStatusMapper.COLOR_PROMPT, withoutEngineFlag.departureColor());
        assertEquals(AdasUiStatusMapper.COLOR_WARNING, withEngineFlag.departureColor());
    }

    @Test
    public void keepingAssistCoversAllSixStates() {
        assertEquals("To Be Determined …", AdasUiStatusMapper.keepingText(Double.NaN));
        assertEquals("Keep Straight Ahead", AdasUiStatusMapper.keepingText(900.0));
        assertEquals("Keep Straight Ahead", AdasUiStatusMapper.keepingText(-900.0));
        assertEquals("Gentle Left Curve Ahead", AdasUiStatusMapper.keepingText(-300.0));
        assertEquals("Gentle Right Curve Ahead", AdasUiStatusMapper.keepingText(300.0));
        assertEquals("Hard Left Curve Ahead", AdasUiStatusMapper.keepingText(-120.0));
        assertEquals("Hard Right Curve Ahead", AdasUiStatusMapper.keepingText(120.0));

        assertEquals(AdasUiStatusMapper.COLOR_NORMAL, AdasUiStatusMapper.keepingColor(900.0));
        assertEquals(AdasUiStatusMapper.COLOR_PROMPT, AdasUiStatusMapper.keepingColor(-300.0));
        assertEquals(AdasUiStatusMapper.COLOR_WARNING, AdasUiStatusMapper.keepingColor(120.0));
        assertEquals(AdasUiStatusMapper.COLOR_INACTIVE, AdasUiStatusMapper.keepingColor(Double.NaN));
    }

    @Test
    public void unsupportedCalibrationReportsLaneGeometryAsUnavailable() {
        AdasUiStatusMapper.LaneStatus status = AdasUiStatusMapper.lane(null, false, false);
        assertEquals("Not Calibrated", status.departure());
        assertEquals("To Be Determined …", status.keeping());
        assertFalse(status.departure().contains("Keep"));
        AdasUiStatusMapper.LaneStatus measured = AdasUiStatusMapper.lane(lane(0.2, 120.0),
                false, true);
        assertEquals("Not Calibrated", measured.departure());
        assertEquals("To Be Determined …", measured.keeping());
    }

    @Test
    public void metricOffsetUsesTheMeasuredLaneWidth() {
        // The vehicle sits right of the lane centre, so the metre readout is positive and agrees with
        // the "keep left" instruction.
        assertEquals(0.35, AdasUiStatusMapper.offsetMeters(lane(0.10, 900.0)), 1.0e-6);
        assertEquals(-0.35, AdasUiStatusMapper.offsetMeters(lane(-0.10, 900.0)), 1.0e-6);
        assertEquals(0.7, AdasUiStatusMapper.offsetMeters(
                new LaneGeometry.LaneSnapshot(1L, 0.1, 0.7, 3.5, 900.0, 9)), 1.0e-6);
    }

    private static LaneGeometry.LaneSnapshot lane(double centerOffset, double radius) {
        return new LaneGeometry.LaneSnapshot(1L, centerOffset, centerOffset * 3.5, 3.5, radius, 9);
    }

    private static AdasDecisionEngine.Decision decision(boolean collisionDanger, boolean headway,
                                                        boolean headwayCritical, boolean laneWarning) {
        return new AdasDecisionEngine.Decision(Set.of(), headway, headwayCritical,
                collisionDanger, laneWarning);
    }

    @Test
    public void straightLaneKeepsTheVehicleReportedAsCentered() {
        double rowY = LaneDepartureDetector.ROI_BOTTOM_ROW;
        double width = LaneGeometry.laneWidthModelMeters(CALIBRATION, rowY, 8.0,
                LaneGeometry.DEFAULT_LANE_WIDTH_METERS, WIDTH, HEIGHT);
        LaneGeometry.WidthSample sample = new LaneGeometry.WidthSample(rowY, 0.5 - width / 2.0,
                0.5 + width / 2.0, 0.9);
        LaneGeometry.LaneSnapshot snapshot = LaneGeometry.snapshot(1L, CALIBRATION, 8.0, sample,
                java.util.List.of(sample), WIDTH, HEIGHT);
        assertTrue(snapshot.valid());
        assertEquals("Good Lane Keeping", AdasUiStatusMapper.lane(snapshot, true, false).departure());
    }
}
