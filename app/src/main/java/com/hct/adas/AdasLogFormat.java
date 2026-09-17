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
     * @param captureToHandoffMs time from capture to the producer's hand-off (sampling throttle plus
     *                           the NV21 copy)
     * @param queueWaitMs        time the frame waited to be picked up
     * @param processingMs       time the worker spent handling the frame
     */
    static String heartbeat(double captureFps, long ageMs, long captureToHandoffMs,
                            long queueWaitMs, long processingMs, String speed, String calibration,
                            String target, String lane, String alert) {
        return String.format(Locale.ROOT,
                "[HEARTBEAT] FPS=%.1f | Age=%dms | Split=%d+%d+%dms"
                        + " | Speed=%s | Calib=%s | Target=%s | Lane=%s | Alert=%s",
                captureFps, ageMs, captureToHandoffMs, queueWaitMs, processingMs,
                speed, calibration, target, lane, alert);
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
}
