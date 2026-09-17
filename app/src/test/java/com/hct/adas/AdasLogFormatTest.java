package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

/**
 * Guards the diagnostic log lines. A specifier/argument type mismatch throws
 * {@link java.util.IllegalFormatConversionException}, and every one of these lines is emitted from
 * the main thread, so the failure mode is a process-wide crash rather than a missing log entry.
 * A regression of that kind is exactly what happened once, so the real {@link String#format} call
 * is exercised here rather than a copy of the format strings.
 */
public final class AdasLogFormatTest {

    @Test
    public void heartbeatRendersEveryFieldWithoutConversionErrors() {
        // Mixes double, long and String arguments: the original defect was a %d given to a double.
        String line = AdasLogFormat.heartbeat(15.6, 219L, 100L, 0L, 119L,
                "60.0 km/h", "CALIBRATED(H=1.25m, pitch=4.0°)", "#1(D=11.0m, vc=5.7m/s, TTC=1.9s)",
                "offset=-0.07, conf=0.68", "[FCW]");

        assertTrue(line.startsWith("[HEARTBEAT] FPS=15.6"));
        assertTrue(line.contains("Age=219ms"));
        assertTrue(line.contains("Split=100+0+119ms"));
        assertTrue(line.contains("Speed=60.0 km/h"));
        assertTrue(line.contains("Alert=[FCW]"));
    }

    @Test
    public void heartbeatRendersZeroTimingsBeforeTheFirstFrame() {
        // The metrics record reports zeros until a frame has been handled, so this is the very first
        // heartbeat a fresh process emits.
        String line = AdasLogFormat.heartbeat(0.0, 0L, 0L, 0L, 0L,
                "NO_GPS", "UNSET", "NONE", "UNAVAILABLE", "NONE");

        assertTrue(line.contains("FPS=0.0"));
        assertTrue(line.contains("Age=0ms"));
        assertTrue(line.contains("Split=0+0+0ms"));
    }

    @Test
    public void dangerStartFallsBackWhenTtcIsNotUsable() {
        String finite = AdasLogFormat.dangerStart(175L, 13.4, 5.6, 60.0, 2.39);
        assertTrue(finite.contains("TTC=2.39s"));
        assertTrue(finite.contains("Age=175ms"));

        String notAvailable = AdasLogFormat.dangerStart(175L, 13.4, 5.6, 60.0, Double.NaN);
        assertTrue(notAvailable.contains("TTC=--"));
    }

    @Test
    public void alertTriggerRendersNegativeConfirmationForEventsWithoutADangerEdge() {
        String fcw = AdasLogFormat.alertTrigger("FCW", 0L, 413L, 11.0, 5.7, 60.0, 1L);
        assertTrue(fcw.contains(">>> FCW <<<"));
        assertTrue(fcw.contains("Confirm=413ms"));
        assertTrue(fcw.contains("Target=#1"));

        // LDW and LVSA legitimately fire without a recorded danger edge.
        String ldw = AdasLogFormat.alertTrigger("LDW", 12L, -1L, Double.NaN, 0.0, 65.0, 0L);
        assertTrue(ldw.contains("Confirm=-1ms"));
        assertTrue(ldw.contains("Target=#0"));
    }

    @Test
    public void alertsRendersNoneInsteadOfAnEmptySet() {
        assertEquals("NONE", AdasLogFormat.alerts(null));
        assertEquals("NONE", AdasLogFormat.alerts(Set.of()));
        String rendered = AdasLogFormat.alerts(Set.of(AdasDecisionEngine.Alert.FCW));
        assertTrue(rendered.contains("FCW"));
        assertFalse(rendered.contains("NONE"));
    }

    @Test
    public void calibrationRendersEveryFieldAndFallsBackOnMissingValues() {
        String line = AdasLogFormat.calibration("CALIBRATING", 35, 21,
                AutoCalibrationLearner.REQUIRED_CONVERGENCE_SAMPLES,
                2.441, 2.441, 8.02, 8.0, 3.48, "NONE", 0);
        assertTrue(line.startsWith("[CALIB] status=CALIBRATING progress=35% samples=21/60"));
        assertTrue(line.contains("measuredRatio=2.441"));
        assertTrue(line.contains("implied=8.02°"));
        assertTrue(line.contains("laneWidth=3.48m"));
        assertTrue(line.contains("reject=NONE geomRejects=0"));

        // Before the first usable frame every derived value is absent.
        String empty = AdasLogFormat.calibration("WIZARD_COMPLETED", 0, 0,
                AutoCalibrationLearner.REQUIRED_CONVERGENCE_SAMPLES,
                Double.NaN, Double.NaN, Double.NaN, 4.0, Double.NaN,
                "DRIVING_CONDITION", 0);
        assertTrue(empty.contains("measuredRatio=--"));
        assertTrue(empty.contains("implied=--°"));
        assertTrue(empty.contains("laneWidth=--"));
        assertTrue(empty.contains("run=4.00°"));
    }

    @Test
    public void laneSamplingRendersEverySightedRow() {
        java.util.List<LaneGeometry.WidthSample> samples = java.util.List.of(
                new LaneGeometry.WidthSample(0.54, 0.41, 0.62, 0.80),
                new LaneGeometry.WidthSample(0.82, 0.20, 0.80, 0.90));
        LaneDepartureDetector.Observation lane = new LaneDepartureDetector.Observation(
                0.005, 0.85, true, 0.41, 0.62, 0.20, 0.80, samples);

        String line = AdasLogFormat.laneSampling(lane);
        assertTrue(line.startsWith("[LANE] available=true"));
        assertTrue(line.contains("r0 y=0.54"));
        assertTrue(line.contains("r1 y=0.82"));
        assertTrue(line.contains("w=0.210"));
        assertFalse(line.contains("noRowsWithBothEdges"));

        // A lane that is not tracked says so instead of rendering a row list.
        assertTrue(AdasLogFormat.laneSampling(LaneDepartureDetector.Observation.UNAVAILABLE)
                .contains("noRowsWithBothEdges"));
        assertTrue(AdasLogFormat.laneSampling(null).contains("observation=null"));
    }
}
