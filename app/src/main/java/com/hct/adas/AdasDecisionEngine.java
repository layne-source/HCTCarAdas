package com.hct.adas;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Deterministic FCW, HMW, LDW and LVSA decision state machine. */
public final class AdasDecisionEngine {
    public enum Alert {
        FCW,
        HMW_CRITICAL,
        LDW,
        LVSA
    }

    public record Observation(
            long timestampMillis,
            double egoSpeedKmh,
            double distanceMeters,
            double closingSpeedMps,
            double targetAreaPixels,
            boolean targetVisible) {
        public Observation {
            if (timestampMillis < 0) {
                throw new IllegalArgumentException("timestampMillis must not be negative");
            }
        }
    }

    public record Decision(
            Set<Alert> events,
            boolean headwayWarning,
            boolean headwayCritical,
            boolean collisionDanger,
            boolean laneWarning) {
        public Decision {
            events = Set.copyOf(events);
        }

        public Decision(Set<Alert> events, boolean headwayWarning, boolean laneWarning) {
            this(events, headwayWarning, false, false, laneWarning);
        }
    }

    /** centerOffset is the signed vehicle offset as a fraction of the measured lane width. */
    public record LaneObservation(double centerOffset, double confidence, boolean available) { }

    private static final double FCW_MIN_SPEED_KMH = 20.0;
    private static final double FCW_TTC_SECONDS = 2.4;
    private static final int FCW_REQUIRED_FRAMES = 3;
    private static final long FCW_CONFIRM_MILLIS = 200L;
    private static final long FCW_COOLDOWN_MILLIS = 3_000L;
    private static final double HMW_DISTANCE_METERS = 8.0;
    private static final double HMW_LOW_SPEED_THRESHOLD_KMH = 15.0;
    private static final double HMW_THW_CAUTION_SECONDS = 1.2;
    private static final double HMW_THW_CRITICAL_SECONDS = 0.6;
    private static final double HMW_CRITICAL_DISTANCE_METERS = 4.0;
    private static final long HMW_CRITICAL_COOLDOWN_MILLIS = 4_000L;
    private static final double LVSA_MAX_STATIONARY_SPEED_KMH = 1.0;
    private static final double LVSA_MIN_DISTANCE_METERS = 3.0;
    private static final double LVSA_MAX_DISTANCE_METERS = 9.0;
    private static final long LVSA_WAIT_MILLIS = 3_000L;
    private static final double LVSA_DISTANCE_DELTA_METERS = 2.5;
    private static final double LVSA_AREA_DROP_RATIO = 0.15;
    private static final int LVSA_REQUIRED_MOVEMENT_FRAMES = 2;
    private static final long LVSA_COOLDOWN_MILLIS = 10_000L;
    // Engineering noise tolerances for establishing a stationary lead; validate on the fitted camera.
    private static final long LVSA_MAX_LOST_MILLIS = 600L;
    private static final double LVSA_MAX_STATIONARY_CLOSING_SPEED_MPS = 0.75;
    private static final double LVSA_DISTANCE_STABILITY_TOLERANCE_METERS = 0.35;
    private static final double LVSA_AREA_STABILITY_TOLERANCE_RATIO = 0.08;
    private static final long LVSA_DIAGNOSTIC_INTERVAL_MILLIS = 1_000L;
    private static final double LDW_MIN_SPEED_KMH = 50.0;
    private static final double LDW_OFFSET = 0.12;
    private static final double LDW_CONFIDENCE = 0.35;
    private static final long LDW_REQUIRED_MILLIS = 1_000L;
    private static final long LDW_COOLDOWN_MILLIS = 6_000L;

    private int dangerousFrames;
    private Long dangerSinceMillis;
    private long fcwCooldownUntil;
    private long hmwCriticalCooldownUntil;
    private long lvsaCooldownUntil;
    private Long stationarySince;
    private double stationaryDistance;
    private double stationaryArea;
    private int stationaryMovementFrames;
    private boolean stationaryArmed;
    private Long targetLostSinceMillis;
    private Long laneDepartureSince;
    private long ldwCooldownUntil;

    // Diagnostics are optional and never participate in alert decisions or cooldowns.
    private final Consumer<String> lvsaDiagnosticLogger;
    private Observation lastLvsaObservation;
    private String lastLvsaDiagnosticReason;
    private long lastLvsaDiagnosticMillis;

    public AdasDecisionEngine() {
        this(null);
    }

    public AdasDecisionEngine(Consumer<String> lvsaDiagnosticLogger) {
        this.lvsaDiagnosticLogger = lvsaDiagnosticLogger;
    }

    public Decision update(Observation observation) {
        return update(observation, null);
    }

    public Decision update(Observation observation, LaneObservation lane) {
        EnumSet<Alert> events = EnumSet.noneOf(Alert.class);
        boolean collisionDanger = isCollisionDanger(observation);
        boolean headwayCritical = isHeadwayCritical(observation);
        boolean headwayWarning = isHeadwayWarning(observation);

        if (collisionDanger) {
            dangerousFrames++;
            if (dangerSinceMillis == null) {
                dangerSinceMillis = observation.timestampMillis();
            }
        } else {
            dangerousFrames = 0;
            dangerSinceMillis = null;
        }
        boolean fcwConfirmed = collisionDanger && dangerousFrames >= FCW_REQUIRED_FRAMES
                && dangerSinceMillis != null
                && (observation.timestampMillis() - dangerSinceMillis >= FCW_CONFIRM_MILLIS);
        if (fcwConfirmed && observation.timestampMillis() >= fcwCooldownUntil) {
            events.add(Alert.FCW);
            fcwCooldownUntil = observation.timestampMillis() + FCW_COOLDOWN_MILLIS;
            dangerousFrames = 0;
            dangerSinceMillis = null;
        }
        // Level 2 Proximity Alert: HMW_CRITICAL when <= 4.0m (or the stricter THW rule).
        if (headwayCritical
                && observation.timestampMillis() >= hmwCriticalCooldownUntil) {
            events.add(Alert.HMW_CRITICAL);
            hmwCriticalCooldownUntil = observation.timestampMillis() + HMW_CRITICAL_COOLDOWN_MILLIS;
        }
        updateStationaryState(observation, events);
        boolean laneWarning = updateLaneState(observation, lane, events);
        return new Decision(events, headwayWarning, headwayCritical, collisionDanger, laneWarning);
    }

    private boolean isCollisionDanger(Observation observation) {
        if (!observation.targetVisible() || !Double.isFinite(observation.egoSpeedKmh())
                || observation.egoSpeedKmh() < FCW_MIN_SPEED_KMH
                || !Double.isFinite(observation.distanceMeters())
                || !Double.isFinite(observation.closingSpeedMps())
                || observation.distanceMeters() <= 0.0
                || observation.closingSpeedMps() <= 0.0) {
            return false;
        }
        double ttcSeconds = observation.distanceMeters() / observation.closingSpeedMps();
        return ttcSeconds <= FCW_TTC_SECONDS;
    }

    /** Display fallback when speed expires between analyses; never mutates event/cooldown state. */
    public static Decision distanceOnlyDecision(double distanceMeters, double closingSpeedMps,
                                                boolean targetVisible) {
        Observation observation = new Observation(0L, Double.NaN, distanceMeters,
                closingSpeedMps, Double.NaN, targetVisible);
        return new Decision(Set.of(), isHeadwayWarning(observation), isHeadwayCritical(observation),
                false, false);
    }

    private static boolean isHeadwayWarning(Observation observation) {
        if (!observation.targetVisible() || !Double.isFinite(observation.distanceMeters())
                || observation.distanceMeters() <= 0.0) {
            return false;
        }
        double speedKmh = observation.egoSpeedKmh();
        double distance = observation.distanceMeters();
        if (Double.isFinite(speedKmh)) {
            if (speedKmh <= LVSA_MAX_STATIONARY_SPEED_KMH) {
                // Stationary in traffic: only warn if right on the bumper (<= 4.0m)
                return distance <= HMW_CRITICAL_DISTANCE_METERS;
            }
            if (speedKmh >= HMW_LOW_SPEED_THRESHOLD_KMH) {
                double speedMps = speedKmh / 3.6;
                double thwSeconds = distance / speedMps;
                return thwSeconds <= HMW_THW_CAUTION_SECONDS || distance <= HMW_DISTANCE_METERS;
            }
        }
        return distance <= HMW_DISTANCE_METERS;
    }
    private static boolean isHeadwayCritical(Observation observation) {
        if (!observation.targetVisible() || !Double.isFinite(observation.distanceMeters())
                || observation.distanceMeters() <= 0.0) {
            return false;
        }
        if (Double.isFinite(observation.closingSpeedMps()) && observation.closingSpeedMps() < -0.5) {
            return false; // Target pulling away, suppress proximity chime
        }
        double speedKmh = observation.egoSpeedKmh();
        double distance = observation.distanceMeters();
        if (Double.isFinite(speedKmh)) {
            if (speedKmh <= LVSA_MAX_STATIONARY_SPEED_KMH) {
                return false; // Stopped in traffic, suppress proximity chime
            }
            if (speedKmh >= HMW_LOW_SPEED_THRESHOLD_KMH) {
                double speedMps = speedKmh / 3.6;
                double thwSeconds = distance / speedMps;
                return thwSeconds <= HMW_THW_CRITICAL_SECONDS || distance <= HMW_CRITICAL_DISTANCE_METERS;
            }
        }
        return distance <= HMW_CRITICAL_DISTANCE_METERS;
    }

    private void updateStationaryState(Observation observation, EnumSet<Alert> events) {
        if (lvsaDiagnosticLogger != null) {
            lastLvsaObservation = observation;
        }
        // 1. Ego vehicle motion check: immediate reset if ego moves or speed invalid
        if (!Double.isFinite(observation.egoSpeedKmh())
                || observation.egoSpeedKmh() < 0.0
                || observation.egoSpeedKmh() > LVSA_MAX_STATIONARY_SPEED_KMH) {
            traceLvsa(Double.isFinite(observation.egoSpeedKmh()) && observation.egoSpeedKmh() >= 0.0
                    ? "EGO_MOVING" : "SPEED_INVALID", observation, stationarySince != null);
            clearStationaryState();
            return;
        }

        // 2. Target visibility tolerance: allow up to 600ms transient occlusion before disarming
        if (!observation.targetVisible()) {
            if (targetLostSinceMillis == null) {
                targetLostSinceMillis = observation.timestampMillis();
            }
            if (observation.timestampMillis() - targetLostSinceMillis > LVSA_MAX_LOST_MILLIS) {
                traceLvsa("TARGET_LOST_TIMEOUT", observation, true);
                clearStationaryState();
            } else {
                traceLvsa("TARGET_UNAVAILABLE", observation, false);
            }
            return;
        }
        // The first recovered sample may cross the tolerance between observations. Check it
        // before clearing the loss timestamp, even when the tracker kept the same target ID.
        if (targetLostSinceMillis != null
                && observation.timestampMillis() - targetLostSinceMillis > LVSA_MAX_LOST_MILLIS) {
            traceLvsa("RECOVERY_TOO_LATE", observation, true);
            clearStationaryState();
        }
        if (targetLostSinceMillis != null) {
            traceLvsa("TARGET_RECOVERED", observation, true);
        }
        targetLostSinceMillis = null;

        boolean validData = Double.isFinite(observation.distanceMeters())
                && Double.isFinite(observation.targetAreaPixels())
                && Double.isFinite(observation.closingSpeedMps())
                && observation.distanceMeters() > 0.0 && observation.targetAreaPixels() > 0.0;
        if (!validData) {
            traceLvsa("INVALID_MEASUREMENT", observation, stationarySince != null);
            clearStationaryState();
            return;
        }
        if (stationaryArmed) {
            if (stationaryDistance - observation.distanceMeters() > LVSA_DISTANCE_STABILITY_TOLERANCE_METERS
                    || (observation.targetAreaPixels() - stationaryArea) / stationaryArea
                    > LVSA_AREA_STABILITY_TOLERANCE_RATIO) {
                traceLvsa(stationaryDistance - observation.distanceMeters()
                        > LVSA_DISTANCE_STABILITY_TOLERANCE_METERS
                        ? "ARMED_CANCEL_DISTANCE" : "ARMED_CANCEL_AREA", observation, true);
                clearStationaryState();
                return;
            }
            boolean targetMoved = observation.distanceMeters() - stationaryDistance
                    >= LVSA_DISTANCE_DELTA_METERS;
            boolean targetShrank = stationaryArea > 0.0
                    && (stationaryArea - observation.targetAreaPixels()) / stationaryArea
                    >= LVSA_AREA_DROP_RATIO;
            if (targetMoved || targetShrank) {
                stationaryMovementFrames++;
                traceLvsa(targetMoved && targetShrank ? "MOVEMENT_DISTANCE_AND_AREA"
                        : targetMoved ? "MOVEMENT_DISTANCE" : "MOVEMENT_AREA", observation, true);
            } else {
                traceLvsa(stationaryMovementFrames > 0 ? "MOVEMENT_BROKEN" : "ARMED_MONITOR",
                        observation, stationaryMovementFrames > 0);
                stationaryMovementFrames = 0;
            }
            if (stationaryMovementFrames >= LVSA_REQUIRED_MOVEMENT_FRAMES) {
                if (observation.timestampMillis() >= lvsaCooldownUntil) {
                    events.add(Alert.LVSA);
                    traceLvsa("TRIGGER", observation, true);
                    lvsaCooldownUntil = observation.timestampMillis() + LVSA_COOLDOWN_MILLIS;
                } else {
                    traceLvsa("COOLDOWN_SUPPRESSED", observation, true);
                }
                // Consume departures suppressed by cooldown too; never replay them later.
                clearStationaryState();
            }
            return;
        }

        boolean stationaryTarget = Math.abs(observation.closingSpeedMps())
                <= LVSA_MAX_STATIONARY_CLOSING_SPEED_MPS
                && observation.egoSpeedKmh() <= LVSA_MAX_STATIONARY_SPEED_KMH
                && observation.distanceMeters() >= LVSA_MIN_DISTANCE_METERS
                && observation.distanceMeters() <= LVSA_MAX_DISTANCE_METERS;
        if (!stationaryTarget) {
            traceLvsa(Math.abs(observation.closingSpeedMps()) > LVSA_MAX_STATIONARY_CLOSING_SPEED_MPS
                    ? "RELATIVE_MOTION" : "DISTANCE_OUT_OF_RANGE", observation, stationarySince != null);
            clearStationaryState();
            return;
        }

        if (stationarySince == null) {
            stationarySince = observation.timestampMillis();
            stationaryDistance = observation.distanceMeters();
            stationaryArea = observation.targetAreaPixels();
            stationaryMovementFrames = 0;
            traceLvsa("WAIT_START", observation, true);
            return;
        }

        boolean distanceStable = Math.abs(observation.distanceMeters() - stationaryDistance)
                <= LVSA_DISTANCE_STABILITY_TOLERANCE_METERS;
        boolean areaStable = stationaryArea > 0.0
                && Math.abs(observation.targetAreaPixels() - stationaryArea) / stationaryArea
                <= LVSA_AREA_STABILITY_TOLERANCE_RATIO;
        if (!distanceStable || !areaStable) {
            // Log the previous baseline before replacing it, so a restart is explainable.
            traceLvsa(!distanceStable ? "WAIT_RESTART_DISTANCE" : "WAIT_RESTART_AREA", observation, true);
            stationarySince = observation.timestampMillis();
            stationaryDistance = observation.distanceMeters();
            stationaryArea = observation.targetAreaPixels();
            stationaryMovementFrames = 0;
            return;
        }
        if (observation.timestampMillis() - stationarySince >= LVSA_WAIT_MILLIS) {
            stationaryArmed = true;
            stationaryMovementFrames = 0;
            traceLvsa("WAIT_READY", observation, true);
        } else {
            traceLvsa("WAITING", observation, false);
        }
    }

    /** Resets are logged before mutation; wait/armed progress is logged after mutation. */
    private void traceLvsa(String reason, Observation observation, boolean event) {
        if (lvsaDiagnosticLogger == null || observation == null) {
            return;
        }
        long now = observation.timestampMillis();
        if (!event && reason.equals(lastLvsaDiagnosticReason)
                && ("EGO_MOVING".equals(reason)
                || (now >= lastLvsaDiagnosticMillis
                && now - lastLvsaDiagnosticMillis < LVSA_DIAGNOSTIC_INTERVAL_MILLIS))) {
            return;
        }
        lastLvsaDiagnosticReason = reason;
        lastLvsaDiagnosticMillis = now;
        boolean waiting = stationarySince != null;
        double baseDistance = waiting ? stationaryDistance : Double.NaN;
        double baseArea = waiting ? stationaryArea : Double.NaN;
        double areaDropPercent = baseArea > 0.0
                ? (baseArea - observation.targetAreaPixels()) / baseArea * 100.0 : Double.NaN;
        try {
            lvsaDiagnosticLogger.accept(String.format(Locale.ROOT,
                    "reason=%s state=%s sampleMs=%d waitMs=%d speed=%.2f visible=%s"
                            + " D=%.3f baseD=%.3f deltaD=%+.3f A=%.1f baseA=%.1f areaDrop=%+.2f%%"
                            + " vc=%+.3f move=%d/%d lostMs=%d cooldownMs=%d",
                    reason, stationaryArmed ? "ARMED" : waiting ? "WAITING" : "IDLE",
                    now, waiting ? now - stationarySince : 0L, observation.egoSpeedKmh(),
                    observation.targetVisible(), observation.distanceMeters(), baseDistance,
                    observation.distanceMeters() - baseDistance, observation.targetAreaPixels(), baseArea,
                    areaDropPercent, observation.closingSpeedMps(), stationaryMovementFrames,
                    LVSA_REQUIRED_MOVEMENT_FRAMES,
                    targetLostSinceMillis == null ? 0L : now - targetLostSinceMillis,
                    Math.max(0L, lvsaCooldownUntil - now)));
        } catch (RuntimeException ignored) {
            // A diagnostic sink failure must not change or interrupt safety decisions.
        }
    }

    private void clearStationaryState() {
        stationarySince = null;
        stationaryDistance = Double.NaN;
        stationaryArea = Double.NaN;
        stationaryMovementFrames = 0;
        stationaryArmed = false;
        targetLostSinceMillis = null;
    }

    private boolean updateLaneState(Observation observation, LaneObservation lane,
                                    EnumSet<Alert> events) {
        boolean departure = lane != null && lane.available()
                && Double.isFinite(lane.centerOffset()) && Double.isFinite(lane.confidence())
                && Math.abs(lane.centerOffset()) >= LDW_OFFSET
                && lane.confidence() >= LDW_CONFIDENCE
                && Double.isFinite(observation.egoSpeedKmh())
                && observation.egoSpeedKmh() >= LDW_MIN_SPEED_KMH;
        if (!departure) {
            laneDepartureSince = null;
            return false;
        }
        if (laneDepartureSince == null) {
            laneDepartureSince = observation.timestampMillis();
            return false;
        }
        boolean warning = observation.timestampMillis() - laneDepartureSince >= LDW_REQUIRED_MILLIS;
        if (warning && observation.timestampMillis() >= ldwCooldownUntil) {
            events.add(Alert.LDW);
            ldwCooldownUntil = observation.timestampMillis() + LDW_COOLDOWN_MILLIS;
        }
        return warning;
    }
    public void resetTargetState() {
        if (stationarySince != null || targetLostSinceMillis != null) {
            traceLvsa("RESET_TARGET", lastLvsaObservation, true);
        }
        lastLvsaObservation = null;
        lastLvsaDiagnosticReason = null;
        dangerousFrames = 0;
        dangerSinceMillis = null;
        clearStationaryState();
    }

    public void reset() {
        resetTargetState();
        fcwCooldownUntil = 0L;
        hmwCriticalCooldownUntil = 0L;
        lvsaCooldownUntil = 0L;
        laneDepartureSince = null;
        ldwCooldownUntil = 0L;
    }
}
