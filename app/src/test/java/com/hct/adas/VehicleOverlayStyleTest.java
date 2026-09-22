package com.hct.adas;

import static com.hct.adas.FixedGuideController.Mode.DANGER;
import static com.hct.adas.FixedGuideController.Mode.HIDDEN;
import static com.hct.adas.FixedGuideController.Mode.MONITORING;
import static com.hct.adas.FixedGuideController.Mode.NORMAL;
import static com.hct.adas.FixedGuideController.Mode.WARNING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

public final class VehicleOverlayStyleTest {
    @Test
    public void monitoringGuideUsesTheNormalFlowingRenderMode() {
        assertEquals(NORMAL, VehicleOverlayView.renderModeFor(MONITORING));
        assertEquals(HIDDEN, VehicleOverlayView.renderModeFor(HIDDEN));
        assertEquals(WARNING, VehicleOverlayView.renderModeFor(WARNING));
        assertEquals(DANGER, VehicleOverlayView.renderModeFor(DANGER));
    }

    @Test
    public void cornerLengthScalesWithSmallVehicleBox() {
        assertEquals(24f, VehicleOverlayView.cornerLengthForBounds(100f, 100f, 1f), 0.001f);
    }

    @Test
    public void cornerLengthIsCappedForLargeVehicleBox() {
        assertEquals(96f, VehicleOverlayView.cornerLengthForBounds(1000f, 1000f, 3f), 0.001f);
    }

    @Test
    public void strokeWidthsKeepTrackedVehicleEmphasis() {
        assertEquals(12f, VehicleOverlayView.boxStrokeWidth(true, 3f), 0.001f);
        assertEquals(7.5f, VehicleOverlayView.boxStrokeWidth(false, 3f), 0.001f);
    }

    @Test
    public void warningMarkerUsesCompactBounds() {
        assertEquals(30f, VehicleOverlayView.warningMarkerWidth(100f, 1f), 0.001f);
        assertEquals(96f, VehicleOverlayView.warningMarkerWidth(1000f, 3f), 0.001f);
    }

    @Test
    public void warningMarkerIconKeepsAnInsetInsideTriangle() {
        assertEquals(34f, VehicleOverlayView.warningMarkerIconWidth(100f), 0.001f);
    }

    @Test
    public void ongoingHeadwayCautionShowsYellowWithoutAnAudioEvent() {
        AdasDecisionEngine.Decision decision =
                new AdasDecisionEngine.Decision(Set.of(), true, false, false, false);
        assertEquals(0xFFF2BA49, VehicleOverlayView.warningColorForDecision(decision, true));
    }

    @Test
    public void ongoingDangerShowsRedDuringAudioCooldown() {
        assertEquals(0xFFFF525E, VehicleOverlayView.warningColorForDecision(
                new AdasDecisionEngine.Decision(Set.of(), true, true, false, false), true));
        assertEquals(0xFFFF525E, VehicleOverlayView.warningColorForDecision(
                new AdasDecisionEngine.Decision(Set.of(), false, false, true, false), true));
    }

    @Test
    public void heldCollisionEventsKeepTheRedIndicator() {
        for (AdasDecisionEngine.Alert event : new AdasDecisionEngine.Alert[] {
                AdasDecisionEngine.Alert.FCW, AdasDecisionEngine.Alert.HMW_CRITICAL}) {
            assertEquals(0xFFFF525E, VehicleOverlayView.warningColorForDecision(
                    new AdasDecisionEngine.Decision(Set.of(event), true, false), true));
        }
    }

    @Test
    public void invalidOrLostTargetCannotDisplayAHeldWarning() {
        AdasDecisionEngine.Decision decision = new AdasDecisionEngine.Decision(
                Set.of(AdasDecisionEngine.Alert.FCW), true, true, true, false);
        assertEquals(0, VehicleOverlayView.warningColorForDecision(decision, false));
    }

    @Test
    public void normalLaneAndStartEventsDoNotDisplayVehicleDanger() {
        assertEquals(0, VehicleOverlayView.warningColorForDecision(null, true));
        assertEquals(0, VehicleOverlayView.warningColorForDecision(
                new AdasDecisionEngine.Decision(Set.of(), false, false), true));
        assertEquals(0, VehicleOverlayView.warningColorForDecision(
                new AdasDecisionEngine.Decision(Set.of(AdasDecisionEngine.Alert.LDW), false, true),
                true));
        assertEquals(0, VehicleOverlayView.warningColorForDecision(
                new AdasDecisionEngine.Decision(Set.of(AdasDecisionEngine.Alert.LVSA), false, false),
                true));
    }

    @Test
    public void gpsUnavailableStillAllowsDistanceBasedWarning() {
        assertEquals(0xFFF2BA49, VehicleOverlayView.warningColorForDecision(
                AdasDecisionEngine.distanceOnlyDecision(7.0, 0.0, true), true));
        assertEquals(0xFFFF525E, VehicleOverlayView.warningColorForDecision(
                AdasDecisionEngine.distanceOnlyDecision(3.5, 0.0, true), true));
    }
    @Test
    public void targetLabelCardHeightProvidesComfortableBadgePadding() {
        assertEquals(28f, VehicleOverlayView.targetLabelCardHeight(14f, 1f), 0.001f);
        assertEquals(56f, VehicleOverlayView.targetLabelCardHeight(28f, 2f), 0.001f);
        assertEquals(36f, VehicleOverlayView.targetLabelCardHeight(24f, 1f), 0.001f);
    }

    @Test
    public void targetLabelBaselineCentersTextSymmetricallyWithinCard() {
        float cardTop = 100f;
        float cardBottom = 128f;
        float cardCenterY = (cardTop + cardBottom) / 2f;
        float fontAscent = -14f;
        float fontDescent = 4f;

        float baseline = VehicleOverlayView.targetLabelBaseline(cardCenterY, fontAscent, fontDescent);
        float glyphTop = baseline + fontAscent;
        float glyphBottom = baseline + fontDescent;

        float topPadding = glyphTop - cardTop;
        float bottomPadding = cardBottom - glyphBottom;

        assertEquals(topPadding, bottomPadding, 0.001f);
        assertEquals(5f, topPadding, 0.001f);
        assertEquals(5f, bottomPadding, 0.001f);
    }

    @Test
    public void targetLabelBottomGapIsPositionedCloserToSpeedReadout() {
        assertEquals(42f, VehicleOverlayView.TARGET_LABEL_BOTTOM_GAP_DP, 0.001f);
        assertEquals(28f, VehicleOverlayView.TARGET_LABEL_HEIGHT_DP, 0.001f);
        assertEquals(10f, VehicleOverlayView.TARGET_LABEL_PADDING_X_DP, 0.001f);
        assertEquals(7f, VehicleOverlayView.TARGET_LABEL_CORNER_RADIUS_DP, 0.001f);
    }

    @Test
    public void targetLabelLayoutAdaptsAcrossSupportedCarScreenResolutions() {
        int[][] screens = {
                {800, 480, 213},
                {1024, 600, 240},
                {1280, 720, 260},
                {1280, 800, 260},
                {1920, 1080, 280},
                {1920, 1200, 320},
                {2000, 1200, 320},
                {2560, 1440, 360},
                {2560, 1600, 400}
        };

        for (int[] screen : screens) {
            int width = screen[0];
            int height = screen[1];
            int dpi = screen[2];
            float density = dpi / 160f;

            float textSize = 14f * density;
            float cardHeight = VehicleOverlayView.targetLabelCardHeight(textSize, density);
            float bottomMargin = VehicleOverlayView.TARGET_LABEL_BOTTOM_GAP_DP * density;
            float cardBottom = height - bottomMargin;
            float cardTop = cardBottom - cardHeight;
            float cardLeft = 18f * density;

            float speedTop = height - (10f + 21f) * density;
            float gapToSpeed = speedTop - cardBottom;

            assertEquals(28f, cardHeight / density, 0.01f);
            assertEquals(42f, bottomMargin / density, 0.01f);
            assertEquals(11f, gapToSpeed / density, 0.01f);
            assertEquals(18f, cardLeft / density, 0.01f);
            assertTrue(cardTop / density >= 250f);
            float estimatedCardWidth = 100f * density;
            assertTrue(cardLeft + estimatedCardWidth < width - 100f * density);
        }
    }
}
