package com.hct.adas;

/**
 * Camera geometry captured for one exact image size. Focal length and principal point are
 * normalized by image height so the values remain readable while the size binding is explicit.
 * Downward pitch is positive. A calibration is never a substitute for runtime validity checks.
 */
public record CameraCalibration(int imageWidth, int imageHeight,
                                double cameraHeightMeters,
                                double focalLengthYNormalized,
                                double principalPointYNormalized,
                                double pitchDegrees) {
    private static final double MIN_GROUND_RAY_TANGENT = 0.01;
    private static final double MAX_VALID_DISTANCE_METERS = 200.0;
    public CameraCalibration {
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }
        if (!Double.isFinite(cameraHeightMeters) || cameraHeightMeters < 0.2
                || cameraHeightMeters > 4.0) {
            throw new IllegalArgumentException("camera height must be between 0.2 and 4 meters");
        }
        if (!Double.isFinite(focalLengthYNormalized) || focalLengthYNormalized <= 0.05
                || focalLengthYNormalized > 4.0) {
            throw new IllegalArgumentException("focal length is outside the supported image scale");
        }
        if (!Double.isFinite(principalPointYNormalized)
                || principalPointYNormalized < 0.0 || principalPointYNormalized > 1.0) {
            throw new IllegalArgumentException("principal point must be inside the image");
        }
        if (!Double.isFinite(pitchDegrees) || pitchDegrees < -30.0 || pitchDegrees > 45.0) {
            throw new IllegalArgumentException("pitch must be between -30 and 45 degrees");
        }
    }

    public boolean isUsableFor(int width, int height) {
        return width == imageWidth && height == imageHeight;
    }

    /** Returns NaN when the target is not geometrically valid for the planar model. */
    public double estimateDistanceMeters(double bottomYNormalized) {
        if (!Double.isFinite(bottomYNormalized) || bottomYNormalized <= 0.0
                || bottomYNormalized > 1.0) {
            return Double.NaN;
        }
        double rayAngle = Math.atan(
                (bottomYNormalized - principalPointYNormalized) / focalLengthYNormalized);
        double denominator = Math.tan(Math.toRadians(pitchDegrees) + rayAngle);
        if (!Double.isFinite(denominator) || denominator < MIN_GROUND_RAY_TANGENT) {
            return Double.NaN;
        }
        double distance = cameraHeightMeters / denominator;
        return Double.isFinite(distance) && distance > 0.0
                && distance <= MAX_VALID_DISTANCE_METERS ? distance : Double.NaN;
    }

    public CameraCalibration withPitchDegrees(double newPitch) {
        return new CameraCalibration(imageWidth, imageHeight, cameraHeightMeters,
                focalLengthYNormalized, principalPointYNormalized, newPitch);
    }

    /**
     * Returns the normalized Y position of the horizon (vanishing line on flat ground).
     * Satisfies rayAngle = -pitchDegrees => (y_horizon - cy) / fy = tan(-pitch).
     */
    public double horizonYNormalized() {
        double horizon = principalPointYNormalized
                - focalLengthYNormalized * Math.tan(Math.toRadians(pitchDegrees));
        return Math.max(0.0, Math.min(1.0, horizon));
    }

    /**
     * Creates an initial calibration from installation wizard parameters.
     * Assumes square pixels and symmetric principal point.
     */
    public static CameraCalibration fromWizard(int imageWidth, int imageHeight,
                                               double cameraHeightMeters,
                                               double hfovDegrees,
                                               double initialPitchDegrees) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        if (!Double.isFinite(hfovDegrees) || hfovDegrees < 30.0 || hfovDegrees > 160.0) {
            throw new IllegalArgumentException("HFOV must be between 30 and 160 degrees");
        }
        double focalLengthPixels = (imageWidth / 2.0) / Math.tan(Math.toRadians(hfovDegrees / 2.0));
        double focalLengthYNorm = focalLengthPixels / imageHeight;
        double principalPointYNorm = 0.5;
        return new CameraCalibration(imageWidth, imageHeight, cameraHeightMeters,
                focalLengthYNorm, principalPointYNorm, initialPitchDegrees);
    }
}
