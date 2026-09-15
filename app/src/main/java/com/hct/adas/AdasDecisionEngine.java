package com.hct.adas;

import java.util.EnumSet;
import java.util.Set;

/** Deterministic FCW, HMW and LVSA decision state machine. */
public final class AdasDecisionEngine {
    public enum Alert {
        FCW,
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

    public record Decision(Set<Alert> events, boolean headwayWarning) {
        public Decision {
            events = Set.copyOf(events);
        }
    }

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

    private int dangerousFrames;
    private long fcwCooldownUntil;
    private long lvsaCooldownUntil;
    private Long stationarySince;
    private double stationaryDistance;
    private double stationaryArea;

    public Decision update(Observation observation) {
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
        return new Decision(events, headwayWarning);
    }

    private boolean isCollisionDanger(Observation observation) {
        if (!observation.targetVisible()
                || observation.egoSpeedKmh() < FCW_MIN_SPEED_KMH
                || observation.distanceMeters() <= 0.0
                || observation.closingSpeedMps() <= 0.0) {
            return false;
        }
        double ttcSeconds = observation.distanceMeters() / observation.closingSpeedMps();
        return ttcSeconds <= FCW_TTC_SECONDS;
    }

    private boolean isHeadwayWarning(Observation observation) {
        return observation.targetVisible()
                && observation.distanceMeters() > 0.0
                && observation.distanceMeters() <= HMW_DISTANCE_METERS;
    }

    private void updateStationaryState(Observation observation, EnumSet<Alert> events) {
        boolean stationaryTarget = observation.targetVisible()
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
}
