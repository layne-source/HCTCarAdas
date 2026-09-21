package com.hct.adas;

import static com.hct.adas.FixedGuideController.Mode.DANGER;
import static com.hct.adas.FixedGuideController.Mode.HIDDEN;
import static com.hct.adas.FixedGuideController.Mode.MONITORING;
import static com.hct.adas.FixedGuideController.Mode.NORMAL;
import static com.hct.adas.FixedGuideController.Mode.WARNING;
import static org.junit.Assert.assertEquals;

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
}
