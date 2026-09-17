package com.hct.adas;

import android.util.Log;
import java.util.ArrayDeque;
import java.util.Locale;
/**
 * Online camera calibration learner using lane perspective vanishing points.
 * Automatically converges pitch angle during driving and adapts to vehicle load changes.
 */
public final class AutoCalibrationLearner {
    public record StepResult(
            CameraCalibration calibration,
            CalibrationStore.Status status,
            int progressPercent,
            double vanishingPointY,
            boolean calibrationUpdated) { }

    public record VanishingPoint(double x, double y, boolean valid) {
        public static final VanishingPoint INVALID = new VanishingPoint(Double.NaN, Double.NaN, false);
    }

    public static final double MIN_SPEED_KMH = 35.0;
    public static final double MAX_SPEED_KMH = 120.0;
    public static final double MIN_LANE_CONFIDENCE = 0.35;
    public static final double MIN_LANE_WIDTH_DELTA = 0.04;
    /**
     * Upper end of the observable vanishing point window, ~12.9 deg of downward pitch at the
     * default wizard intrinsics. Kept inside the ROI visibility guard below so the geometric guard
     * never rejects a sample this window still admits.
     */
    public static final double MIN_VANISHING_Y = 0.30;
    public static final double MAX_VANISHING_Y = 0.60;
    public static final double MIN_VANISHING_X = 0.36;
    public static final double MAX_VANISHING_X = 0.64;
    public static final int REQUIRED_CONVERGENCE_SAMPLES = 60;
    public static final double MAX_CONVERGENCE_STD_DEV = 0.015;
    public static final double ONLINE_TRACKING_RATE = 0.002;
    private static final double MIN_PITCH_DEGREES = -5.0;
    private static final double MAX_PITCH_DEGREES = 20.0;
    /**
     * A vanishing point is only trusted while the lane ROI's far edge still reaches this far ahead.
     * The ROI rows are fixed, so a steeply pitched camera compresses the whole ROI towards the
     * bumper and this bound is what rejects the resulting vanishing points. At the default wizard
     * intrinsics it starts biting around 13.3 deg, just outside MIN_VANISHING_Y.
     */
    static final double MIN_ROI_FAR_DISTANCE_METERS = 4.0;
    /** Rejects a theoretical pitch that would push the ROI's near edge past the usable range. */
    static final double MAX_ROI_NEAR_DISTANCE_METERS = 40.0;
    private static final String TAG = "HctAdasCore";
    private static final double PITCH_UPDATE_THRESHOLD_DEGREES = 0.05;
    public static final double LANE_Y_TOP = LaneDepartureDetector.Y_TOP;
    public static final double LANE_Y_BOTTOM = LaneDepartureDetector.Y_BOTTOM;

    private final ArrayDeque<Double> samples = new ArrayDeque<>(REQUIRED_CONVERGENCE_SAMPLES);
    private CalibrationStore.Status status;
    private int progress;
    private double lastVanishingY = Double.NaN;
    private double lastVanishingX = Double.NaN;
    private double trackedPitch = Double.NaN;
    public AutoCalibrationLearner() {
        this(CalibrationStore.Status.UNCONFIGURED, 0);
    }

    public AutoCalibrationLearner(CalibrationStore.Status initialStatus, int initialProgress) {
        this.status = initialStatus == null ? CalibrationStore.Status.UNCONFIGURED : initialStatus;
        this.progress = Math.max(0, Math.min(100, initialProgress));
    }

    public synchronized StepResult update(LaneDepartureDetector.Observation lane,
                                          double egoSpeedKmh,
                                          CameraCalibration currentCalibration) {
        if (currentCalibration == null) {
            reset(CalibrationStore.Status.UNCONFIGURED);
            return new StepResult(null, status, 0, Double.NaN, false);
        }
        if (status == CalibrationStore.Status.UNCONFIGURED) {
            return new StepResult(currentCalibration, status, 0, Double.NaN, false);
        }

        // Gating 1: ego speed must be in steady cruising range.
        if (!Double.isFinite(egoSpeedKmh) || egoSpeedKmh < MIN_SPEED_KMH || egoSpeedKmh > MAX_SPEED_KMH) {
            resetSamples();
            return new StepResult(currentCalibration, status, progress, Double.NaN, false);
        }

        // Gating 2: lane observation quality.
        if (lane == null || !lane.available() || !Double.isFinite(lane.confidence())
                || lane.confidence() < MIN_LANE_CONFIDENCE || lane.confidence() > 1.0) {
            resetSamples();
            return new StepResult(currentCalibration, status, progress, Double.NaN, false);
        }

        // Gating 3: vehicle centered in lane to reject turning/lane changing.
        double centerOffset = lane.centerOffset();
        if (!Double.isFinite(centerOffset) || Math.abs(centerOffset) > 0.15) {
            resetSamples();
            return new StepResult(currentCalibration, status, progress, Double.NaN, false);
        }

        // Gating 4: the fixed lane ROI must still look at road, not at the hood. A steeply pitched
        // camera can produce a plausible looking perspective fit while the ROI covers only 1-2 m,
        // so the learned pitch is never accepted without this physical check.
        if (!isLaneRoiVisible(currentCalibration)) {
            resetSamples();
            Log.w(TAG, String.format(Locale.ROOT,
                    "[CALIB] Rejected sample: lane ROI collapsed to the hood at pitch=%.1f deg",
                    currentCalibration.pitchDegrees()));
            return new StepResult(currentCalibration, status, progress, Double.NaN, false);
        }

        VanishingPoint vp = solveVanishingPoint(lane);
        if (!vp.valid()) {
            resetSamples();
            return new StepResult(currentCalibration, status, progress, Double.NaN, false);
        }

        lastVanishingX = vp.x();
        lastVanishingY = vp.y();

        if (status == CalibrationStore.Status.WIZARD_COMPLETED) {
            status = CalibrationStore.Status.CALIBRATING;
            samples.clear();
            Log.i(TAG, String.format("[CALIB] Starting online vanishing point learning: speed=%.1f km/h, vpY=%.3f", egoSpeedKmh, vp.y()));
        }

        if (status == CalibrationStore.Status.CALIBRATING) {
            samples.addLast(vp.y());
            while (samples.size() > REQUIRED_CONVERGENCE_SAMPLES) {
                samples.pollFirst();
            }
            progress = Math.min(99, (int) (samples.size() * 100.0 / REQUIRED_CONVERGENCE_SAMPLES));
            if (samples.size() % 15 == 0) {
                Log.d(TAG, String.format("[CALIB] Learning progress: %d%% (%d/%d samples)", progress, samples.size(), REQUIRED_CONVERGENCE_SAMPLES));
            }
            if (samples.size() >= REQUIRED_CONVERGENCE_SAMPLES) {
                double meanY = computeMean(samples);
                double stdDev = computeStdDev(samples, meanY);

                if (stdDev <= MAX_CONVERGENCE_STD_DEV) {
                    double learnedPitch = computePitchFromVanishingY(meanY, currentCalibration);
                    // The candidate pitch itself must keep the ROI on the road, otherwise a steeply
                    // pitched camera would persist a pitch whose own measurements are unusable.
                    CameraCalibration candidate = Double.isFinite(learnedPitch)
                            ? currentCalibration.withPitchDegrees(learnedPitch) : null;
                    if (candidate != null
                            && learnedPitch >= MIN_PITCH_DEGREES
                            && learnedPitch <= MAX_PITCH_DEGREES
                            && isLaneRoiVisible(candidate)) {
                        status = CalibrationStore.Status.CALIBRATED;
                        progress = 100;
                        trackedPitch = learnedPitch;
                        Log.i(TAG, String.format(Locale.ROOT,
                                "[CALIB] Auto-calibration CONVERGED! Learned pitch=%.2f deg (stdDev=%.4f)",
                                learnedPitch, stdDev));
                        return new StepResult(candidate, status, 100, vp.y(), true);
                    }
                    Log.w(TAG, String.format(Locale.ROOT,
                            "[CALIB] Convergence rejected: pitch=%.2f deg would push the lane ROI off the road",
                            learnedPitch));
                }
            }
            return new StepResult(currentCalibration, status, progress, vp.y(), false);
        }

        if (status == CalibrationStore.Status.CALIBRATED) {
            double observedPitch = computePitchFromVanishingY(vp.y(), currentCalibration);
            if (Double.isFinite(observedPitch)
                    && observedPitch >= MIN_PITCH_DEGREES
                    && observedPitch <= MAX_PITCH_DEGREES
                    && isLaneRoiVisible(currentCalibration.withPitchDegrees(observedPitch))) {
                if (!Double.isFinite(trackedPitch)) {
                    trackedPitch = currentCalibration.pitchDegrees();
                }
                trackedPitch = trackedPitch * (1.0 - ONLINE_TRACKING_RATE)
                        + observedPitch * ONLINE_TRACKING_RATE;
                if (Math.abs(trackedPitch - currentCalibration.pitchDegrees()) >= PITCH_UPDATE_THRESHOLD_DEGREES
                        && isLaneRoiVisible(currentCalibration.withPitchDegrees(trackedPitch))) {
                    Log.i(TAG, String.format(Locale.ROOT,
                            "[CALIB] Online tracking adjusted pitch: %.2f -> %.2f deg",
                            currentCalibration.pitchDegrees(), trackedPitch));
                    CameraCalibration updated = currentCalibration.withPitchDegrees(trackedPitch);
                    return new StepResult(updated, status, 100, vp.y(), true);
                }
            }
            return new StepResult(currentCalibration, status, 100, vp.y(), false);
        }

        return new StepResult(currentCalibration, status, progress, vp.y(), false);
    }

    public static VanishingPoint solveVanishingPoint(LaneDepartureDetector.Observation lane) {
        if (lane == null || !lane.available()) {
            return VanishingPoint.INVALID;
        }
        double leftTop = lane.leftTopX();
        double rightTop = lane.rightTopX();
        double leftBottom = lane.leftBottomX();
        double rightBottom = lane.rightBottomX();

        if (!Double.isFinite(leftTop) || !Double.isFinite(rightTop)
                || !Double.isFinite(leftBottom) || !Double.isFinite(rightBottom)) {
            return VanishingPoint.INVALID;
        }

        double widthTop = rightTop - leftTop;
        double widthBottom = rightBottom - leftBottom;

        if (widthBottom <= widthTop + MIN_LANE_WIDTH_DELTA) {
            return VanishingPoint.INVALID;
        }

        // Perspective intersection:
        // y_vp = y_bottom - (y_bottom - y_top) * (widthBottom / (widthBottom - widthTop))
        double deltaY = LANE_Y_BOTTOM - LANE_Y_TOP;
        double vpY = LANE_Y_BOTTOM - deltaY * (widthBottom / (widthBottom - widthTop));

        if (vpY < MIN_VANISHING_Y || vpY > MAX_VANISHING_Y) {
            return VanishingPoint.INVALID;
        }

        // x_vp = leftBottom + ((leftTop - leftBottom) / (y_top - y_bottom)) * (vpY - y_bottom)
        double slopeLeft = (leftTop - leftBottom) / (LANE_Y_TOP - LANE_Y_BOTTOM);
        double vpX = leftBottom + slopeLeft * (vpY - LANE_Y_BOTTOM);

        if (vpX < MIN_VANISHING_X || vpX > MAX_VANISHING_X) {
            return VanishingPoint.INVALID;
        }

        return new VanishingPoint(vpX, vpY, true);
    }

    public static double computePitchFromVanishingY(double vanishingY, CameraCalibration calibration) {
        if (!Double.isFinite(vanishingY) || calibration == null) {
            return Double.NaN;
        }
        double cy = calibration.principalPointYNormalized();
        double fy = calibration.focalLengthYNormalized();
        if (fy <= 0.0) {
            return Double.NaN;
        }
        // rayAngle = -pitch => (y_vp - cy) / fy = tan(-pitch) = -tan(pitch)
        // tan(pitch) = (cy - y_vp) / fy => pitch = atan((cy - y_vp) / fy)
        return Math.toDegrees(Math.atan((cy - vanishingY) / fy));
    }

    /**
     * Largest downward pitch the vanishing point window can represent. Keeping the window and the
     * pitch acceptance bounds derived from the same expression prevents one of them from silently
     * rejecting samples the other still admits.
     */
    public static double vanishingPitchLimitDegrees(CameraCalibration calibration) {
        return calibration == null
                ? Double.NaN : computePitchFromVanishingY(MIN_VANISHING_Y, calibration);
    }

    /**
     * Ground distance of a normalized image row under the flat-road model, or NaN when the ray
     * leaves the valid ground region.
     */
    static double groundDistanceMeters(CameraCalibration calibration, double rowYNormalized) {
        if (calibration == null || !Double.isFinite(rowYNormalized)) {
            return Double.NaN;
        }
        return calibration.estimateDistanceMeters(rowYNormalized);
    }

    /**
     * True while the lane detector's fixed ROI still falls inside a usable distance band. The ROI
     * rows are constants, so a steeply pitched camera compresses the whole ROI towards the bumper;
     * this check is what stops the learner from trusting vanishing points measured there.
     */
    static boolean isLaneRoiVisible(CameraCalibration calibration) {
        double nearDistance = groundDistanceMeters(calibration, LaneDepartureDetector.ROI_BOTTOM_ROW);
        double farDistance = groundDistanceMeters(calibration, LaneDepartureDetector.ROI_TOP_ROW);
        return Double.isFinite(nearDistance) && Double.isFinite(farDistance)
                && farDistance >= MIN_ROI_FAR_DISTANCE_METERS
                && nearDistance <= MAX_ROI_NEAR_DISTANCE_METERS;
    }

    private static double computeMean(ArrayDeque<Double> data) {
        double sum = 0.0;
        for (double val : data) {
            sum += val;
        }
        return sum / data.size();
    }

    private static double computeStdDev(ArrayDeque<Double> data, double mean) {
        double sumSq = 0.0;
        for (double val : data) {
            double diff = val - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / data.size());
    }

    public synchronized void reset(CalibrationStore.Status newStatus) {
        this.status = newStatus == null ? CalibrationStore.Status.UNCONFIGURED : newStatus;
        this.progress = this.status == CalibrationStore.Status.CALIBRATED ? 100 : 0;
        this.samples.clear();
        this.lastVanishingY = Double.NaN;
        this.lastVanishingX = Double.NaN;
        this.trackedPitch = Double.NaN;
    }

    /** Invalidates in-flight samples after a capture gap without changing the configured status. */
    public synchronized void resetSamples() {
        samples.clear();
        lastVanishingY = Double.NaN;
        lastVanishingX = Double.NaN;
        trackedPitch = Double.NaN;
        if (status == CalibrationStore.Status.CALIBRATING) {
            progress = 0;
        }
    }

    public synchronized CalibrationStore.Status status() {
        return status;
    }

    public synchronized int progress() {
        return progress;
    }

    public synchronized double lastVanishingY() {
        return lastVanishingY;
    }

    public synchronized double lastVanishingX() {
        return lastVanishingX;
    }
}
