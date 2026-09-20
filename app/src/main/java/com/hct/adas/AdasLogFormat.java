package com.hct.adas;

import java.util.Locale;
import java.util.Set;

/**
 * Builds the diagnostic log lines for the frame pipeline.
 *
 * <p>Formatting is deliberately separated from {@code MainActivity}: a mismatch between a format
 * specifier and its argument throws {@link java.util.IllegalFormatConversionException} at runtime,
 * and every one of these calls would do so on the main thread from the metrics tick, taking the
 * whole process down. Living outside the Activity keeps them reachable from JVM unit tests, which
 * exercise the real {@link String#format} call rather than a copy of it.
 *
 * <p>Every method returns a complete line without a tag or trailing newline; callers pass the result
 * straight to {@code Log}.
 */
final class AdasLogFormat {

    private AdasLogFormat() {
    }

    /**
     * @param captureToHandoffMs time from the sampled callback to hand-off (NV21 validation/copy;
     *                           excludes waiting for the next sampling interval)
     * @param queueWaitMs        time the frame waited to be picked up
     * @param processingMs       time the worker spent handling the frame
     */
    static String heartbeat(double captureFps, long ageMs, long captureToHandoffMs,
                            long queueWaitMs, long processingMs, String speed, String calibration,
                            String target, String lane, String alert) {
        return String.format(Locale.ROOT,
                "[HEARTBEAT] FPS=%.1f | HandledAge=%dms | Split=%d+%d+%dms"
                        + " | Speed=%s | Calib=%s | Target=%s | Lane=%s | Alert=%s",
                captureFps, ageMs, captureToHandoffMs, queueWaitMs, processingMs,
                speed, calibration, target, lane, alert);
    }

    /** Unlike handler timings, analysis age cannot appear fresh while inference is backing off. */
    static String pipelineHealth(long analyzedAtNanos, long nowNanos, FrameDispatcher.Metrics queue,
                                 long invalidFrames, long failures) {
        long ageMs = analyzedAtNanos > 0L && nowNanos >= analyzedAtNanos
                ? (nowNanos - analyzedAtNanos) / 1_000_000L : -1L;
        return String.format(Locale.ROOT,
                "[PIPELINE] AnalysisAge=%dms | Offered=%d Dropped=%d Discarded=%d Invalid=%d Failures=%d",
                ageMs, queue.offeredFrames(), queue.droppedFrames(), queue.discardedFrames(),
                invalidFrames, failures);
    }

    /** Logged once when the collision-risk condition first becomes measurable. */
    static String dangerStart(long ageMs, double distanceMeters, double closingSpeedMps,
                              double egoSpeedKmh, double ttcSeconds) {
        String ttc = Double.isFinite(ttcSeconds)
                ? String.format(Locale.ROOT, "%.2fs", ttcSeconds) : "--";
        return String.format(Locale.ROOT,
                "[DANGER-START] Age=%dms | Dist=%.1fm | ClosingSpeed=%.1fm/s"
                        + " | EgoSpeed=%.1f km/h | TTC=%s",
                ageMs, distanceMeters, closingSpeedMps, egoSpeedKmh, ttc);
    }

    /**
     * @param confirmationMs time from the first measurable danger frame to this event, or -1 when
     *                       no danger edge is recorded (events that do not depend on one, such as
     *                       LDW or LVSA, legitimately report -1)
     */
    static String alertTrigger(String alertName, long ageMs, long confirmationMs,
                               double distanceMeters, double closingSpeedMps, double egoSpeedKmh,
                               long trackId) {
        return String.format(Locale.ROOT,
                "[ALERT-TRIGGER] >>> %s <<< | Age=%dms | Confirm=%dms | Dist=%.1fm"
                        + " | ClosingSpeed=%.1fm/s | EgoSpeed=%.1f km/h | Target=#%d",
                alertName, ageMs, confirmationMs, distanceMeters, closingSpeedMps, egoSpeedKmh,
                trackId);
    }

    /** Renders an alert set as a stable, log-friendly string. */
    static String alerts(Set<AdasDecisionEngine.Alert> events) {
        return events == null || events.isEmpty() ? "NONE" : events.toString();
    }

    /**
     * One calibration line per second while the pitch is being learned, independent of the sample
     * count. Without it the learner is silent until the window has 15 samples, which is exactly the
     * window that matters when it never starts accumulating at all.
     *
     * @param samples       samples currently held in the window
     * @param required      samples the window needs before it may converge
     * @param measuredRatio near/far lane width ratio actually measured this frame
     * @param modelRatio    ratio the model predicts at the configured pitch
     * @param impliedPitch  pitch implied by the measured ratio, NaN when unavailable
     * @param runPitch      pitch the current frame is being interpreted at
     * @param laneWidth    physical lane width implied by the measurement, in metres
     * @param rejection     why the newest observation was dropped
     * @param geometryRejects consecutive camera-geometry rejections
     */
    static String calibration(String status, int progressPercent, int samples, int required,
                              double measuredRatio, double modelRatio, double impliedPitch,
                              double runPitch, double laneWidth, String rejection,
                              int geometryRejects) {
        return String.format(Locale.ROOT,
                "[CALIB] status=%s progress=%d%% samples=%d/%d"
                        + " | measuredRatio=%s modelRatio=%s implied=%s° run=%s°"
                        + " | laneWidth=%s | reject=%s geomRejects=%d",
                status, progressPercent, samples, required,
                ratio(measuredRatio), ratio(modelRatio), degrees(impliedPitch), degrees(runPitch),
                metres(laneWidth), rejection, geometryRejects);
    }

    /**
     * One line per second describing what the lane detector actually found: which rows were sampled
     * and how far apart the two boundaries were on each of them. This is the evidence needed to tell a
     * lock onto a neighbouring lane line from a mis-placed sampling band.
     */
    static String laneSampling(LaneDepartureDetector.Observation lane) {
        if (lane == null) {
            return "[LANE] observation=null";
        }
        StringBuilder builder = new StringBuilder(String.format(Locale.ROOT,
                "[LANE] available=%s confidence=%.2f offset=%+.3f span=[%.2f..%.2f]",
                lane.available(), lane.confidence(), lane.centerOffset(),
                LaneDepartureDetector.ROI_TOP_ROW, LaneDepartureDetector.ROI_BOTTOM_ROW));
        int index = 0;
        for (LaneGeometry.WidthSample sample : lane.widthSamples()) {
            builder.append(String.format(Locale.ROOT, " | r%d y=%.2f L=%s R=%s w=%s c=%.2f",
                    index++, sample.rowY(), coordinate(sample.leftX()), coordinate(sample.rightX()),
                    coordinate(sample.widthNormalized()), sample.confidence()));
        }
        if (index == 0) {
            builder.append(" | noRowsWithBothEdges");
        }
        return builder.toString();
    }

    private static String ratio(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "--";
    }

    private static String degrees(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f", value) : "--";
    }

    private static String metres(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2fm", value) : "--";
    }

    private static String coordinate(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "--";
    }
}
