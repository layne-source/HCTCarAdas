package com.hct.adas;

/** Fixed visual template in source-image coordinates; it is not a measured road lane. */
public record FixedGuideGeometry(double centerX, double horizonY, double nearY,
                                 double farY, double nearHalfWidth) {
    private static final double DEFAULT_NEAR_Y = 0.90;
    private static final double FAR_HORIZON_GAP = 0.06;
    private static final double NEAR_HALF_WIDTH = 0.22;

    /** The caller also gates the saved calibration status through distanceReady(). */
    public static FixedGuideGeometry fromCalibration(CameraCalibration calibration,
                                                     int frameWidth, int frameHeight) {
        return fromCalibration(calibration, frameWidth, frameHeight, DEFAULT_NEAR_Y);
    }

    /** Builds the template with a near row chosen by the visible safe viewport. */
    public static FixedGuideGeometry fromCalibration(CameraCalibration calibration,
                                                     int frameWidth, int frameHeight,
                                                     double nearY) {
        if (!CalibrationAlignment.isValid(calibration)
                || !calibration.isUsableFor(frameWidth, frameHeight)
                || !Double.isFinite(nearY) || nearY <= 0.0 || nearY > 1.0) {
            return null;
        }
        double horizon = calibration.horizonYNormalized();
        double center = calibration.guideCenterXNormalized();
        double far = horizon + FAR_HORIZON_GAP;
        if (!Double.isFinite(far) || far < 0.0 || far >= nearY) {
            return null;
        }
        double farHalfWidth = NEAR_HALF_WIDTH * (far - horizon) / (nearY - horizon);
        if (!Double.isFinite(farHalfWidth) || farHalfWidth <= 0.0
                || center - NEAR_HALF_WIDTH < 0.0 || center + NEAR_HALF_WIDTH > 1.0
                || center - farHalfWidth < 0.0 || center + farHalfWidth > 1.0) {
            return null;
        }
        return new FixedGuideGeometry(center, horizon, nearY, far, NEAR_HALF_WIDTH);
    }

    /** Linear perspective converges at the saved horizon, independently of the visual center. */
    public double halfWidthAt(double y) {
        return nearHalfWidth * (y - horizonY) / (nearY - horizonY);
    }
}
