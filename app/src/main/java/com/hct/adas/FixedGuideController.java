package com.hct.adas;

/** Pure display state, owned by the UI thread; does not modify tracking or alert decisions. */
public final class FixedGuideController {
    public enum Mode { HIDDEN, MONITORING, NORMAL, WARNING, DANGER }

    /**
     * Frame time is in the System.nanoTime domain; speed time is elapsed realtime.
     * The caller supplies current target validity and already target-bound visual risk flags.
     * settingsOpen is consumed by the renderer to pause arrows without closing the speed gate.
     */
    public record Input(long sessionId, long frameTimestampNanos, boolean runtimeReady,
                        boolean calibrationReady, boolean foreground, boolean calibrating,
                        boolean settingsOpen, double speedKmh, long speedTimestampNanos,
                        LeadVehicleTracker.State trackingState, boolean targetValid,
                        boolean warning, boolean danger) { }

    private static final double SHOW_SPEED_KMH = 20.0;
    private static final double HIDE_SPEED_KMH = 15.0;
    private static final long SPEED_HOLD_NANOS = 1_000_000_000L;
    private static final long MAX_SPEED_AGE_NANOS = 2_000_000_000L;

    private boolean hasInput;
    private long sessionId;
    private long lastUpdateNanos;
    private long lastFrameTimestampNanos;
    private long lastSpeedTimestampNanos;
    private boolean speedGateOpen;
    private boolean speedTransitionPending;
    private long speedTransitionSinceNanos;

    /**
     * nowNanos uses System.nanoTime for frame freshness and speed hysteresis. The independent
     * elapsedRealtimeNanos is used only for GPS freshness; UI ticks never renew either input.
     */
    public Mode update(Input input, long nowNanos, long elapsedRealtimeNanos) {
        if (!isUsable(input, nowNanos, elapsedRealtimeNanos)) {
            reset();
            return Mode.HIDDEN;
        }
        if (hasInput && input.sessionId() != sessionId) {
            reset();
        }
        if (hasInput && (nowNanos < lastUpdateNanos
                || input.frameTimestampNanos() < lastFrameTimestampNanos
                || input.speedTimestampNanos() < lastSpeedTimestampNanos)) {
            reset();
            return Mode.HIDDEN;
        }
        if (hasInput && (input.frameTimestampNanos() - lastFrameTimestampNanos
                > LeadVehicleTracker.MAX_OBSERVATION_GAP_NANOS
                || input.speedTimestampNanos() - lastSpeedTimestampNanos > MAX_SPEED_AGE_NANOS)) {
            // Fresh input can arrive after its predecessor expired between two UI ticks.
            reset();
        }
        hasInput = true;
        sessionId = input.sessionId();
        lastUpdateNanos = nowNanos;
        lastFrameTimestampNanos = input.frameTimestampNanos();
        lastSpeedTimestampNanos = input.speedTimestampNanos();
        updateSpeedGate(input.speedKmh(), nowNanos);

        if (!speedGateOpen) {
            return Mode.HIDDEN;
        }
        return switch (input.trackingState()) {
            case NONE, CANDIDATE -> Mode.MONITORING;
            case LOST -> Mode.HIDDEN;
            case TRACKING -> !input.targetValid() ? Mode.HIDDEN
                    : input.danger() ? Mode.DANGER
                    : input.warning() ? Mode.WARNING : Mode.NORMAL;
        };
    }

    public void reset() {
        hasInput = false;
        sessionId = 0L;
        lastUpdateNanos = 0L;
        lastFrameTimestampNanos = 0L;
        lastSpeedTimestampNanos = 0L;
        speedGateOpen = false;
        speedTransitionPending = false;
        speedTransitionSinceNanos = 0L;
    }

    private void updateSpeedGate(double speedKmh, long nowNanos) {
        boolean crossingThreshold = speedGateOpen
                ? speedKmh <= HIDE_SPEED_KMH : speedKmh >= SHOW_SPEED_KMH;
        if (!crossingThreshold) {
            speedTransitionPending = false;
            return;
        }
        if (!speedTransitionPending) {
            speedTransitionPending = true;
            speedTransitionSinceNanos = nowNanos;
        } else if (nowNanos - speedTransitionSinceNanos >= SPEED_HOLD_NANOS) {
            speedGateOpen = !speedGateOpen;
            speedTransitionPending = false;
        }
    }

    private static boolean isUsable(Input input, long nowNanos, long elapsedRealtimeNanos) {
        return input != null && input.runtimeReady() && input.calibrationReady()
                && input.foreground() && !input.calibrating() && input.trackingState() != null
                && Double.isFinite(input.speedKmh()) && input.speedKmh() >= 0.0
                && input.speedTimestampNanos() > 0L
                && isFresh(input.frameTimestampNanos(), nowNanos,
                        LeadVehicleTracker.MAX_OBSERVATION_GAP_NANOS)
                && isFresh(input.speedTimestampNanos(), elapsedRealtimeNanos, MAX_SPEED_AGE_NANOS);
    }

    private static boolean isFresh(long timestampNanos, long nowNanos, long maximumAgeNanos) {
        // System.nanoTime has no positive-origin guarantee; null Input represents a missing frame.
        long ageNanos = nowNanos - timestampNanos;
        return timestampNanos <= nowNanos && ageNanos >= 0L && ageNanos <= maximumAgeNanos;
    }
}
