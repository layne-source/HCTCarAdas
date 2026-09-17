package com.hct.adas;

/** Converts a confirmed target's bottom edge into a filtered distance and closing speed. */
public final class LeadVehicleMotionEstimator {
    public record Measurement(long trackId, double distanceMeters, double closingSpeedMps,
                              double targetAreaPixels, boolean visible) { }

    private static final double DISTANCE_ALPHA = 0.35;
    private static final double SPEED_ALPHA = 0.35;
    private static final double MAX_RELATIVE_ACCEL_MPS2 = 12.0; // ~1.2g max automotive relative acceleration
    private long previousId;
    private long previousTimestamp;
    private double previousRawDistance;
    private double filteredDistance;
    private double filteredSpeed;
    private boolean initialized;
    private CameraCalibration previousCalibration;
    public Measurement update(LeadVehicleTracker.Snapshot snapshot,
                              CameraCalibration calibration, int width, int height) {
        if (snapshot == null || snapshot.detection() == null
                || snapshot.state() != LeadVehicleTracker.State.TRACKING
                || calibration == null || !calibration.isUsableFor(width, height)) {
            if (snapshot != null && snapshot.state() != LeadVehicleTracker.State.LOST) {
                reset();
            }
            return new Measurement(snapshot == null ? 0L : snapshot.trackId(),
                    Double.NaN, 0.0, 0.0, false);
        }
        VehicleDetector.Detection box = snapshot.detection();
        if (box.bottom() >= 0.995f || box.left() <= 0.005f || box.right() >= 0.995f) {
            reset();
            return new Measurement(snapshot.trackId(), Double.NaN, 0.0, area(box, width, height), false);
        }
        double distance = calibration.estimateDistanceMeters(box.bottom());
        if (!Double.isFinite(distance)) {
            reset();
            return new Measurement(snapshot.trackId(), Double.NaN, 0.0, area(box, width, height), false);
        }
        double closingSpeed = 0.0;
        if (!initialized || !calibration.equals(previousCalibration)
                || previousId != snapshot.trackId()
                || snapshot.timestampNanos() <= previousTimestamp
                || snapshot.timestampNanos() - previousTimestamp > 2_000_000_000L) {
            filteredDistance = distance;
            filteredSpeed = 0.0;
            previousRawDistance = distance;
            initialized = true;
            previousCalibration = calibration;
        } else {
            double dt = (snapshot.timestampNanos() - previousTimestamp) / 1_000_000_000.0;
            double rawSpeed = (previousRawDistance - distance) / dt;
            previousRawDistance = distance;
            if (!Double.isFinite(rawSpeed)) {
                rawSpeed = 0.0;
            } else {
                // Reject single-frame box jitter: clamp to realistic vehicle acceleration limit
                double maxSpeedChange = MAX_RELATIVE_ACCEL_MPS2 * dt;
                rawSpeed = Math.max(filteredSpeed - maxSpeedChange, Math.min(filteredSpeed + maxSpeedChange, rawSpeed));
            }
            filteredSpeed += SPEED_ALPHA * (rawSpeed - filteredSpeed);
            filteredDistance += DISTANCE_ALPHA * (distance - filteredDistance);
            closingSpeed = Math.max(-50.0, Math.min(50.0, filteredSpeed));
        }
        previousId = snapshot.trackId();
        previousTimestamp = snapshot.timestampNanos();
        return new Measurement(snapshot.trackId(), filteredDistance, closingSpeed,
                area(box, width, height), true);
    }

    public void reset() {
        initialized = false;
        previousId = 0L;
        previousTimestamp = 0L;
        previousRawDistance = Double.NaN;
        filteredDistance = Double.NaN;
        filteredSpeed = 0.0;
        previousCalibration = null;
    }

    private static double area(VehicleDetector.Detection box, int width, int height) {
        return (box.right() - box.left()) * width * (box.bottom() - box.top()) * height;
    }
}
