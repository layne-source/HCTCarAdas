package com.hct.adas;

import java.util.EnumSet;
import java.util.Set;

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
        // Level 2 Proximity Alert: HMW_CRITICAL when <= 4.5m
        if (headwayCritical
                && observation.timestampMillis() >= hmwCriticalCooldownUntil
                && !events.contains(Alert.FCW)) {
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

    private boolean isHeadwayWarning(Observation observation) {
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
    private boolean isHeadwayCritical(Observation observation) {
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
        // 1. Ego vehicle motion check: immediate reset if ego moves or speed invalid
        if (!Double.isFinite(observation.egoSpeedKmh())
                || observation.egoSpeedKmh() < 0.0
                || observation.egoSpeedKmh() > LVSA_MAX_STATIONARY_SPEED_KMH) {
            clearStationaryState();
            return;
        }

        // 2. Target visibility tolerance: allow up to 600ms transient occlusion before disarming
        if (!observation.targetVisible()) {
            if (targetLostSinceMillis == null) {
                targetLostSinceMillis = observation.timestampMillis();
            }
            if (observation.timestampMillis() - targetLostSinceMillis > LVSA_MAX_LOST_MILLIS) {
                clearStationaryState();
            }
            return;
        }
        targetLostSinceMillis = null;

        boolean validData = Double.isFinite(observation.distanceMeters())
                && Double.isFinite(observation.targetAreaPixels())
                && Double.isFinite(observation.closingSpeedMps())
                && observation.distanceMeters() > 0.0 && observation.targetAreaPixels() > 0.0;
        if (!validData) {
            clearStationaryState();
            return;
        }
        if (stationaryArmed) {
            if (stationaryDistance - observation.distanceMeters() > LVSA_DISTANCE_STABILITY_TOLERANCE_METERS
                    || (observation.targetAreaPixels() - stationaryArea) / stationaryArea
                    > LVSA_AREA_STABILITY_TOLERANCE_RATIO) {
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
            } else {
                stationaryMovementFrames = 0;
            }
            if (stationaryMovementFrames >= LVSA_REQUIRED_MOVEMENT_FRAMES) {
                if (observation.timestampMillis() >= lvsaCooldownUntil) {
                    events.add(Alert.LVSA);
                    lvsaCooldownUntil = observation.timestampMillis() + LVSA_COOLDOWN_MILLIS;
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
            clearStationaryState();
            return;
        }

        if (stationarySince == null) {
            stationarySince = observation.timestampMillis();
            stationaryDistance = observation.distanceMeters();
            stationaryArea = observation.targetAreaPixels();
            stationaryMovementFrames = 0;
            return;
        }

        boolean distanceStable = Math.abs(observation.distanceMeters() - stationaryDistance)
                <= LVSA_DISTANCE_STABILITY_TOLERANCE_METERS;
        boolean areaStable = stationaryArea > 0.0
                && Math.abs(observation.targetAreaPixels() - stationaryArea) / stationaryArea
                <= LVSA_AREA_STABILITY_TOLERANCE_RATIO;
        if (!distanceStable || !areaStable) {
            stationarySince = observation.timestampMillis();
            stationaryDistance = observation.distanceMeters();
            stationaryArea = observation.targetAreaPixels();
            stationaryMovementFrames = 0;
            return;
        }
        if (observation.timestampMillis() - stationarySince >= LVSA_WAIT_MILLIS) {
            stationaryArmed = true;
            stationaryMovementFrames = 0;
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
