package com.hct.adas;

/** Converts a confirmed target's bottom edge into a filtered distance and closing speed. */
public final class LeadVehicleMotionEstimator {
    /** Diagnostic outcome only; never used to accept measurements or change filtering. */
    public enum Rejection { NONE, NO_TARGET, NOT_TRACKING, CALIBRATION_UNAVAILABLE,
        RESOLUTION_MISMATCH, BOX_CLIPPED, DISTANCE_INVALID }

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
    private boolean velocityInitialized;
    private boolean hasInitialSpeed;
    private double initialRawSpeed;
    private double initialSpeedInterval;
    private CameraCalibration previousCalibration;
    private Rejection lastRejection = Rejection.NONE;

    public Rejection lastRejection() {
        return lastRejection;
    }

    public Measurement update(LeadVehicleTracker.Snapshot snapshot,
                              CameraCalibration calibration, int width, int height) {
        if (snapshot == null || snapshot.detection() == null
                || snapshot.state() != LeadVehicleTracker.State.TRACKING
                || calibration == null || !calibration.isUsableFor(width, height)) {
            // LOST does not carry a current bounding box. Keep no distance or timestamp across
            // that gap: when a target reappears, the first valid frame must establish a fresh
            // baseline instead of turning the stale-to-new box delta into a closing-speed spike.
            reset();
            lastRejection = snapshot == null || snapshot.detection() == null ? Rejection.NO_TARGET
                    : snapshot.state() != LeadVehicleTracker.State.TRACKING ? Rejection.NOT_TRACKING
                    : calibration == null ? Rejection.CALIBRATION_UNAVAILABLE
                    : Rejection.RESOLUTION_MISMATCH;
            return new Measurement(snapshot == null ? 0L : snapshot.trackId(),
                    Double.NaN, 0.0, 0.0, false);
        }
        VehicleDetector.Detection box = snapshot.detection();
        if (box.bottom() >= 0.995f || box.left() <= 0.005f || box.right() >= 0.995f) {
            reset();
            lastRejection = Rejection.BOX_CLIPPED;
            return new Measurement(snapshot.trackId(), Double.NaN, 0.0, area(box, width, height), false);
        }
        double distance = calibration.estimateDistanceMeters(box.bottom());
        if (!Double.isFinite(distance)) {
            reset();
            lastRejection = Rejection.DISTANCE_INVALID;
            return new Measurement(snapshot.trackId(), Double.NaN, 0.0, area(box, width, height), false);
        }
        double closingSpeed = 0.0;
        if (!initialized || materiallyChanged(previousCalibration, calibration)
                || previousId != snapshot.trackId()
                || snapshot.timestampNanos() <= previousTimestamp
                || snapshot.timestampNanos() - previousTimestamp > 2_000_000_000L) {
            filteredDistance = distance;
            filteredSpeed = 0.0;
            velocityInitialized = false;
            hasInitialSpeed = false;
            previousRawDistance = distance;
            initialized = true;
        } else {
            double dt = (snapshot.timestampNanos() - previousTimestamp) / 1_000_000_000.0;
            double rawSpeed = (previousRawDistance - distance) / dt;
            previousRawDistance = distance;
            if (!Double.isFinite(rawSpeed)) {
                rawSpeed = 0.0;
            }
            if (!velocityInitialized) {
                // The first delta is provisional so a real fast approach is visible promptly.
                // Only consistent consecutive raw deltas may seed the acceleration limiter;
                // otherwise a box jump/rebound would be held as motion for multiple alert frames.
                if (!hasInitialSpeed) {
                    filteredSpeed = rawSpeed;
                    hasInitialSpeed = true;
                } else {
                    double midpointInterval = (initialSpeedInterval + dt) * 0.5;
                    boolean consistent = Math.signum(initialRawSpeed) == Math.signum(rawSpeed)
                            && Math.abs(rawSpeed - initialRawSpeed)
                            <= MAX_RELATIVE_ACCEL_MPS2 * midpointInterval;
                    filteredSpeed = consistent
                            ? (initialRawSpeed * initialSpeedInterval + rawSpeed * dt)
                                    / (initialSpeedInterval + dt) : 0.0;
                    velocityInitialized = consistent;
                }
                initialRawSpeed = rawSpeed;
                initialSpeedInterval = dt;
                filteredSpeed = Math.max(-50.0, Math.min(50.0, filteredSpeed));
            } else {
                // Reject single-frame box jitter: clamp to realistic vehicle acceleration limit
                double maxSpeedChange = MAX_RELATIVE_ACCEL_MPS2 * dt;
                rawSpeed = Math.max(filteredSpeed - maxSpeedChange, Math.min(filteredSpeed + maxSpeedChange, rawSpeed));
                filteredSpeed += SPEED_ALPHA * (rawSpeed - filteredSpeed);
            }
            filteredDistance += DISTANCE_ALPHA * (distance - filteredDistance);
            closingSpeed = Math.max(-50.0, Math.min(50.0, filteredSpeed));
        }
        previousId = snapshot.trackId();
        previousTimestamp = snapshot.timestampNanos();
        previousCalibration = calibration;
        lastRejection = Rejection.NONE;
        return new Measurement(snapshot.trackId(), filteredDistance, closingSpeed,
                area(box, width, height), true);
    }

    public void reset() {
        lastRejection = Rejection.NONE;
        initialized = false;
        previousId = 0L;
        previousTimestamp = 0L;
        previousRawDistance = Double.NaN;
        filteredDistance = Double.NaN;
        filteredSpeed = 0.0;
        velocityInitialized = false;
        hasInitialSpeed = false;
        previousCalibration = null;
    }

    private static boolean materiallyChanged(CameraCalibration previous, CameraCalibration current) {
        if (previous == null) {
            return false;
        }
        return previous.imageWidth() != current.imageWidth()
                || previous.imageHeight() != current.imageHeight()
                || Math.abs(previous.cameraHeightMeters() - current.cameraHeightMeters()) > 1.0e-3
                || Math.abs(previous.focalLengthYNormalized() - current.focalLengthYNormalized()) > 1.0e-3
                || Math.abs(previous.principalPointYNormalized()
                - current.principalPointYNormalized()) > 1.0e-3
                || Math.abs(previous.pitchDegrees() - current.pitchDegrees()) > 0.5;
    }

    private static double area(VehicleDetector.Detection box, int width, int height) {
        return (box.right() - box.left()) * width * (box.bottom() - box.top()) * height;
    }
}
