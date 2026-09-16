package com.hct.adas;

import java.util.EnumSet;
import java.util.Set;

/** Deterministic FCW, HMW, LDW and LVSA decision state machine. */
public final class AdasDecisionEngine {
    public enum Alert {
        FCW,
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

    public record Decision(Set<Alert> events, boolean headwayWarning, boolean laneWarning) {
        public Decision {
            events = Set.copyOf(events);
        }
    }

    public record LaneObservation(double centerOffset, double confidence, boolean available) { }

    private static final double FCW_MIN_SPEED_KMH = 30.0;
    private static final double FCW_TTC_SECONDS = 2.5;
    private static final int FCW_REQUIRED_FRAMES = 3;
    private static final long FCW_COOLDOWN_MILLIS = 3_000L;
    private static final double HMW_DISTANCE_METERS = 8.0;
    private static final double LVSA_MAX_STATIONARY_SPEED_KMH = 1.0;
    private static final double LVSA_MIN_DISTANCE_METERS = 3.0;
    private static final double LVSA_MAX_DISTANCE_METERS = 9.0;
    private static final long LVSA_WAIT_MILLIS = 3_000L;
    private static final double LVSA_DISTANCE_DELTA_METERS = 2.5;
    private static final double LVSA_AREA_DROP_RATIO = 0.15;
    private static final long LVSA_COOLDOWN_MILLIS = 10_000L;
    private static final double LDW_MIN_SPEED_KMH = 50.0;
    private static final double LDW_OFFSET = 0.12;
    private static final double LDW_CONFIDENCE = 0.35;
    private static final long LDW_REQUIRED_MILLIS = 1_000L;
    private static final long LDW_COOLDOWN_MILLIS = 6_000L;

    private int dangerousFrames;
    private long fcwCooldownUntil;
    private long lvsaCooldownUntil;
    private Long stationarySince;
    private double stationaryDistance;
    private double stationaryArea;
    private Long laneDepartureSince;
    private long ldwCooldownUntil;

    public Decision update(Observation observation) {
        return update(observation, null);
    }

    public Decision update(Observation observation, LaneObservation lane) {
        EnumSet<Alert> events = EnumSet.noneOf(Alert.class);
        boolean headwayWarning = isHeadwayWarning(observation);

        if (isCollisionDanger(observation)) {
            dangerousFrames++;
        } else {
            dangerousFrames = 0;
        }
        if (dangerousFrames >= FCW_REQUIRED_FRAMES
                && observation.timestampMillis() >= fcwCooldownUntil) {
            events.add(Alert.FCW);
            fcwCooldownUntil = observation.timestampMillis() + FCW_COOLDOWN_MILLIS;
            dangerousFrames = 0;
        }

        updateStationaryState(observation, events);
        boolean laneWarning = updateLaneState(observation, lane, events);
        return new Decision(events, headwayWarning, laneWarning);
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
        return observation.targetVisible() && Double.isFinite(observation.distanceMeters())
                && observation.distanceMeters() > 0.0
                && observation.distanceMeters() <= HMW_DISTANCE_METERS;
    }

    private void updateStationaryState(Observation observation, EnumSet<Alert> events) {
        boolean stationaryTarget = observation.targetVisible() && Double.isFinite(observation.egoSpeedKmh())
                && Double.isFinite(observation.distanceMeters()) && Double.isFinite(observation.targetAreaPixels())
                && observation.egoSpeedKmh() <= LVSA_MAX_STATIONARY_SPEED_KMH
                && observation.distanceMeters() >= LVSA_MIN_DISTANCE_METERS
                && observation.distanceMeters() <= LVSA_MAX_DISTANCE_METERS;
        if (!stationaryTarget) {
            stationarySince = null;
            return;
        }

        if (stationarySince == null) {
            stationarySince = observation.timestampMillis();
            stationaryDistance = observation.distanceMeters();
            stationaryArea = observation.targetAreaPixels();
            return;
        }

        long stationaryMillis = observation.timestampMillis() - stationarySince;
        boolean targetMoved = observation.distanceMeters() - stationaryDistance
                >= LVSA_DISTANCE_DELTA_METERS;
        boolean targetShrank = stationaryArea > 0.0
                && (stationaryArea - observation.targetAreaPixels()) / stationaryArea
                >= LVSA_AREA_DROP_RATIO;
        if (stationaryMillis >= LVSA_WAIT_MILLIS
                && observation.timestampMillis() >= lvsaCooldownUntil
                && (targetMoved || targetShrank)) {
            events.add(Alert.LVSA);
            lvsaCooldownUntil = observation.timestampMillis() + LVSA_COOLDOWN_MILLIS;
            stationaryDistance = observation.distanceMeters();
            stationaryArea = observation.targetAreaPixels();
        }
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

    public void reset() {
        dangerousFrames = 0;
        fcwCooldownUntil = 0L;
        lvsaCooldownUntil = 0L;
        stationarySince = null;
        laneDepartureSince = null;
        ldwCooldownUntil = 0L;
    }
}
