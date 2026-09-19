package com.hct.adas;

/** Fixed visual template in source-image coordinates; it is not a measured road lane. */
public record FixedGuideGeometry(double centerX, double horizonY, double nearY,
                                 double farY, double nearHalfWidth) {
    private static final double NEAR_Y = 0.90;
    private static final double FAR_HORIZON_GAP = 0.06;
    private static final double NEAR_HALF_WIDTH = 0.22;
    // Match the double-line calibration's tolerance for saved boundary-pitch round trips.
    private static final double PITCH_ROUNDING_TOLERANCE = 1e-9;

    /** The caller also gates the saved calibration status through distanceReady(). */
    public static FixedGuideGeometry fromCalibration(CameraCalibration calibration,
                                                     int frameWidth, int frameHeight) {
        if (calibration == null || !calibration.isUsableFor(frameWidth, frameHeight)) {
            return null;
        }
        double pitch = calibration.pitchDegrees();
        // horizonYNormalized() clamps to the image; that must not legitimize an invalid pitch.
        if (!Double.isFinite(pitch)
                || pitch < CalibrationAlignment.MIN_PITCH_DEGREES - PITCH_ROUNDING_TOLERANCE
                || pitch > CalibrationAlignment.MAX_PITCH_DEGREES + PITCH_ROUNDING_TOLERANCE) {
            return null;
        }
        double horizon = calibration.horizonYNormalized();
        double center = calibration.guideCenterXNormalized();
        if (!CalibrationAlignment.evaluate(horizon, center,
                calibration.focalLengthYNormalized(),
                calibration.principalPointYNormalized()).valid()) {
            return null;
        }
        double far = horizon + FAR_HORIZON_GAP;
        if (!Double.isFinite(far) || far < 0.0 || far >= NEAR_Y) {
            return null;
        }
        double farHalfWidth = NEAR_HALF_WIDTH * (far - horizon) / (NEAR_Y - horizon);
        if (!Double.isFinite(farHalfWidth) || farHalfWidth <= 0.0
                || center - NEAR_HALF_WIDTH < 0.0 || center + NEAR_HALF_WIDTH > 1.0
                || center - farHalfWidth < 0.0 || center + farHalfWidth > 1.0) {
            return null;
        }
        return new FixedGuideGeometry(center, horizon, NEAR_Y, far, NEAR_HALF_WIDTH);
    }

    /** Linear perspective converges at the saved horizon, independently of the visual center. */
    public double halfWidthAt(double y) {
        return nearHalfWidth * (y - horizonY) / (nearY - horizonY);
    }
}
