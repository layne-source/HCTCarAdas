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
        if (!Double.isFinite(denominator) || denominator <= 0.0) {
            return Double.NaN;
        }
        double distance = cameraHeightMeters / denominator;
        return Double.isFinite(distance) && distance > 0.0 ? distance : Double.NaN;
    }
}
