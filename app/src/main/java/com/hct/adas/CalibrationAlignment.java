package com.hct.adas;

/** Pure geometry rules for the two-line installation calibration screen. */
public final class CalibrationAlignment {
    // Allow level mounting and a small upward adjustment, not the camera model's full range.
    public static final double MIN_PITCH_DEGREES = -5.0;
    public static final double MAX_PITCH_DEGREES = 14.0;
    public static final double MAX_CENTER_OFFSET = 0.12;
    private static final double ROUNDING_TOLERANCE = 1e-9;

    private CalibrationAlignment() {
    }

    /** Validates saved profiles using the same installation limits as line confirmation. */
    public static boolean isValid(CameraCalibration calibration) {
        if (calibration == null) return false;
        double pitch = calibration.pitchDegrees();
        if (pitch < MIN_PITCH_DEGREES - ROUNDING_TOLERANCE
                || pitch > MAX_PITCH_DEGREES + ROUNDING_TOLERANCE) return false;
        // Use the raw horizon: clamping an off-image horizon must not validate a profile.
        double horizon = calibration.principalPointYNormalized()
                - calibration.focalLengthYNormalized() * Math.tan(Math.toRadians(pitch));
        if (horizon < -ROUNDING_TOLERANCE || horizon > 1.0 + ROUNDING_TOLERANCE) return false;
        return evaluate(Math.max(0.0, Math.min(1.0, horizon)), calibration.guideCenterXNormalized(),
                calibration.focalLengthYNormalized(),
                calibration.principalPointYNormalized()).valid();
    }

    public record Result(boolean valid, boolean pitchOutOfRange, boolean centerOutOfRange,
                         double pitchDegrees) {
    }

    /** Source-image viewport mapping between normalized frame coordinates and a view. */
    public record Viewport(double left, double top, double width, double height) {
        public double viewX(double imageX) { return left + imageX * width; }
        public double viewY(double imageY) { return top + imageY * height; }
        public double imageX(double viewX) { return (viewX - left) / width; }
        public double imageY(double viewY) { return (viewY - top) / height; }
        public boolean contains(double x, double y) {
            return x >= left && x <= left + width && y >= top && y <= top + height;
        }
    }

    public static Viewport fitCenter(int viewWidth, int viewHeight, int imageWidth, int imageHeight) {
        return fit(viewWidth, viewHeight, imageWidth, imageHeight, false);
    }

    /** Source-image viewport that fills the view and crops only outside the visible image area. */
    public static Viewport fitCover(int viewWidth, int viewHeight, int imageWidth, int imageHeight) {
        return fit(viewWidth, viewHeight, imageWidth, imageHeight, true);
    }

    private static Viewport fit(int viewWidth, int viewHeight, int imageWidth, int imageHeight,
                                boolean cover) {
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("viewport dimensions must be positive");
        }
        double scale = cover
                ? Math.max((double) viewWidth / imageWidth, (double) viewHeight / imageHeight)
                : Math.min((double) viewWidth / imageWidth, (double) viewHeight / imageHeight);
        double width = imageWidth * scale;
        double height = imageHeight * scale;
        return new Viewport((viewWidth - width) / 2.0, (viewHeight - height) / 2.0, width, height);
    }

    /** Only this validated result is committed by the installation screen. */
    public static CameraCalibration confirm(CameraCalibration draft, double horizonY, double centerX) {
        Result result = evaluate(horizonY, centerX,
                draft.focalLengthYNormalized(), draft.principalPointYNormalized());
        if (!result.valid()) {
            throw new IllegalArgumentException("alignment is outside the supported range");
        }
        return new CameraCalibration(draft.imageWidth(), draft.imageHeight(),
                draft.cameraHeightMeters(), draft.focalLengthYNormalized(),
                draft.principalPointYNormalized(), result.pitchDegrees(), centerX);
    }

    public static Result evaluate(double horizonYNormalized, double centerXNormalized,
                                  double focalLengthYNormalized,
                                  double principalPointYNormalized) {
        double pitch = Double.NaN;
        if (Double.isFinite(horizonYNormalized) && horizonYNormalized >= 0.0
                && horizonYNormalized <= 1.0 && Double.isFinite(focalLengthYNormalized)
                && focalLengthYNormalized > 0.0
                && Double.isFinite(principalPointYNormalized)) {
            pitch = Math.toDegrees(Math.atan(
                    (principalPointYNormalized - horizonYNormalized) / focalLengthYNormalized));
        }
        boolean pitchOutOfRange = !Double.isFinite(pitch)
                || pitch < MIN_PITCH_DEGREES - ROUNDING_TOLERANCE
                || pitch > MAX_PITCH_DEGREES + ROUNDING_TOLERANCE;
        boolean centerOutOfRange = !Double.isFinite(centerXNormalized)
                || Math.abs(centerXNormalized - 0.5) > MAX_CENTER_OFFSET + ROUNDING_TOLERANCE;
        return new Result(!pitchOutOfRange && !centerOutOfRange,
                pitchOutOfRange, centerOutOfRange, pitch);
    }
}
