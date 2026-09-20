package com.hct.adas;

import android.util.Log;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Online camera pitch calibration.
 *
 * <p>The primary judgement is the <em>lane width ratio</em>: the width between the two lane edges
 * seen at a near row divided by the width seen at a far row. Because both widths are measured at
 * fixed image rows, the ratio is independent of the camera height and, to first order, of the focal
 * length, while remaining strongly dependent on pitch (about 50% ratio change per degree at the
 * sample rows used here). That makes it a usable replacement for the vanishing-point window, whose
 * tolerance on real frames is only a few pixels and therefore never converges.
 *
 * <p>The ratio is inverted with a bisection on the same model, which yields the pitch correction
 * directly. A learned pitch that still disagrees with the configured mounting angle is reported so
 * the user can be told to re-aim the camera instead of being told to keep driving.
 *
 * <p>Observations that carry width samples keep their accumulated samples when the driver merely
 * fails a gate (too slow, off-centre, lane lost); only a camera-geometry failure discards them. A
 * consumer-grade installer expects the progress bar to hold, not to restart, while driving.
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

    /**
     * Why the latest observation did not enter the sample window. Only the geometric reasons can be
     * fixed by re-aiming the camera; the rest are normal driving conditions that simply have to be
     * waited out, so the UI must not confuse the two.
     */
    public enum Rejection {
        /** The observation was accepted into the sample window. */
        NONE,
        /** Ego speed, lane quality or lane-centering gate. */
        DRIVING_CONDITION,
        /** Vanishing point fell outside the observable window (legacy path only). */
        VANISHING_OUT_OF_RANGE,
        /** The fixed lane ROI no longer covers usable road at the configured pitch. */
        ROI_NOT_VISIBLE,
        /** Lane edges were seen, but their width ratio contradicts the model. */
        OBSERVATION_INCOHERENT
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
    /**
     * Median absolute deviation of the solved pitch inside the window, in degrees. A robust spread
     * estimate is used instead of the standard deviation of the vanishing point: one bad frame must
     * not be able to restart the whole window.
     */
    public static final double MAX_CONVERGENCE_MAD_DEGREES = 0.40;
    public static final double ONLINE_TRACKING_RATE = 0.002;
    /**
     * How far the window-average implied pitch may sit from the configured mounting angle before the
     * user is asked to re-aim. Sized above the single-frame accuracy of the lane-width prior (about
     * 2-3 degrees) so that normal prior error does not raise the hint.
     */
    public static final double MAX_PITCH_DEVIATION_DEGREES = 4.0;
    /**
     * Half-width of the bisection bracket around the configured pitch. Deliberately tighter than the
     * mounting tolerance in {@link CameraCalibration}: the far sample row approaches the horizon as
     * pitch falls, and past that horizon the width ratio is no longer monotonic in pitch, so a wider
     * bracket would contain a second root and could invert to a nonsense angle.
     */
    public static final double PITCH_SOLVE_RANGE_DEGREES = 6.0;
    /** Safety margin above the horizon for the lower end of the solve bracket, in degrees. */
    static final double HORIZON_MARGIN_DEGREES = 1.2;
    /**
     * Plausible physical lane widths. Outside this band the two tracked edges cannot be the ego lane
     * boundaries: a neighbouring lane line roughly doubles the estimate and a wheel track or seam
     * roughly halves it.
     */
    private static final double MIN_PLAUSIBLE_LANE_WIDTH_METERS = 2.6;
    private static final double MAX_PLAUSIBLE_LANE_WIDTH_METERS = 4.8;
    private static final double MIN_PITCH_DEGREES = -5.0;
    private static final double MAX_PITCH_DEGREES = 20.0;
    /**
     * A vanishing point is only trusted while the lane ROI's far edge still reaches this far ahead.
     * The ROI rows are fixed, so a steeply pitched camera compresses the whole ROI towards the
     * bumper and this bound is what rejects the resulting vanishing points.
     */
    static final double MIN_ROI_FAR_DISTANCE_METERS = 3.5;
    /** Rejects a theoretical pitch that would push the ROI's near edge past the usable range. */
    static final double MAX_ROI_NEAR_DISTANCE_METERS = 40.0;
    private static final String TAG = "HctAdasCore";
    private static final double PITCH_UPDATE_THRESHOLD_DEGREES = 0.05;
    /**
     * Consecutive geometric rejections after which the learner is treated as unable to converge at
     * the configured pitch. At the 5 Hz analysis rate this is about one second of steady driving.
     */
    public static final int GEOMETRIC_REJECTION_HINT_THRESHOLD = 5;
    public static final double LANE_Y_TOP = LaneDepartureDetector.Y_TOP;
    public static final double LANE_Y_BOTTOM = LaneDepartureDetector.Y_BOTTOM;

    private final ArrayDeque<Double> samples = new ArrayDeque<>(REQUIRED_CONVERGENCE_SAMPLES);
    private CalibrationStore.Status status;
    private int progress;
    private double lastVanishingY = Double.NaN;
    private double lastVanishingX = Double.NaN;
    private double trackedPitch = Double.NaN;
    private double lastImpliedPitch = Double.NaN;
    private double lastMeasuredRatio = Double.NaN;
    private double lastModelRatio = Double.NaN;
    private double lastLaneWidthMeters = Double.NaN;
    private Rejection lastRejection = Rejection.NONE;
    private int consecutiveGeometricRejections;

    public AutoCalibrationLearner() {
        this(CalibrationStore.Status.UNCONFIGURED, 0);
    }

    public AutoCalibrationLearner(CalibrationStore.Status initialStatus, int initialProgress) {
        this.status = initialStatus == null ? CalibrationStore.Status.UNCONFIGURED : initialStatus;
        this.progress = Math.max(0, Math.min(100, initialProgress));
    }

    /** Legacy entry point: no pitch solve, so the vanishing-point window remains the judgement. */
    public synchronized StepResult update(LaneDepartureDetector.Observation lane,
                                          double egoSpeedKmh,
                                          CameraCalibration currentCalibration) {
        return update(lane, egoSpeedKmh, currentCalibration, Double.NaN, 0, 0);
    }

    public synchronized StepResult update(LaneDepartureDetector.Observation lane,
                                          double egoSpeedKmh,
                                          CameraCalibration currentCalibration,
                                          double pitchDegrees,
                                          int frameWidth,
                                          int frameHeight) {
        if (currentCalibration == null) {
            reset(CalibrationStore.Status.UNCONFIGURED);
            return new StepResult(null, status, 0, Double.NaN, false);
        }
        if (status == CalibrationStore.Status.UNCONFIGURED) {
            return new StepResult(currentCalibration, status, 0, Double.NaN, false);
        }

        // Gating 1: ego speed must be in steady cruising range.
        if (!Double.isFinite(egoSpeedKmh) || egoSpeedKmh < MIN_SPEED_KMH
                || egoSpeedKmh > MAX_SPEED_KMH) {
            return reject(currentCalibration, Rejection.DRIVING_CONDITION, softRejection(lane));
        }

        // Gating 2: lane observation quality. A lane that is not seen at all, or is seen too weakly,
        // carries no geometry and discards the window; a sample that merely broke a channel is a
        // transient that must not throw away a nearly complete window.
        if (lane == null || !lane.available()) {
            return reject(currentCalibration, Rejection.DRIVING_CONDITION, false);
        }
        if (!Double.isFinite(lane.confidence()) || lane.confidence() < MIN_LANE_CONFIDENCE) {
            return reject(currentCalibration, Rejection.DRIVING_CONDITION, softRejection(lane));
        }

        // Gating 3: vehicle centered in lane to reject turning/lane changing. An unusable offset is a
        // broken observation rather than a reason to start the window over.
        double centerOffset = lane.centerOffset();
        if (!Double.isFinite(centerOffset)) {
            return reject(currentCalibration, Rejection.DRIVING_CONDITION, true);
        }
        if (Math.abs(centerOffset) > 0.15) {
            return reject(currentCalibration, Rejection.DRIVING_CONDITION, true);
        }

        // Gating 3b: the observation must look like one lane. These checks only apply to observations
        // that carry sampled geometry, which is the shape every real frame has.
        if (lane.hasWidthSamples()
                && (!Double.isFinite(lane.leftTopX()) || !Double.isFinite(lane.rightTopX())
                || !Double.isFinite(lane.leftBottomX()) || !Double.isFinite(lane.rightBottomX()))) {
            return reject(currentCalibration, Rejection.DRIVING_CONDITION, true);
        }

        // Gating 4: the fixed lane ROI must still look at road, not at the hood. A steeply pitched
        // camera can produce a plausible looking perspective fit while the ROI covers only 1-2 m,
        // so the learned pitch is never accepted without this physical check.
        if (!isLaneRoiVisible(currentCalibration)) {
            Log.w(TAG, String.format(Locale.ROOT,
                    "[CALIB] Rejected sample: lane ROI collapsed to the hood at pitch=%.1f deg",
                    currentCalibration.pitchDegrees()));
            return reject(currentCalibration, Rejection.ROI_NOT_VISIBLE, false);
        }

        // A temporarily missing width sample must not mix legacy vanishing rows into a pitch window.
        if (Double.isFinite(pitchDegrees) && !lane.hasWidthSamples()) {
            return reject(currentCalibration, Rejection.DRIVING_CONDITION, true);
        }
        boolean pitchPath = Double.isFinite(pitchDegrees) && frameWidth > 0 && frameHeight > 0
                && lane.hasWidthSamples();
        double observation;
        if (pitchPath) {
            double implied = impliedPitchDegrees(lane, currentCalibration, frameWidth, frameHeight);
            if (!Double.isFinite(implied)) {
                return reject(currentCalibration, Rejection.OBSERVATION_INCOHERENT, true);
            }
            lastImpliedPitch = implied;
            // A single implied pitch carries the lane-width prior uncertainty, so it is the window
            // average that may ask the user to re-aim, never one frame.
            if (Math.abs(implied - currentCalibration.pitchDegrees())
                    > MAX_PITCH_DEVIATION_DEGREES * 2.0) {
                Log.w(TAG, String.format(Locale.ROOT,
                        "[CALIB] Implied pitch %.2f deg is far from configured %.2f deg",
                        implied, currentCalibration.pitchDegrees()));
                return reject(currentCalibration, Rejection.OBSERVATION_INCOHERENT, true);
            }
            observation = implied;
        } else {
            VanishingPoint vp = solveVanishingPoint(lane);
            if (!vp.valid()) {
                // Kept out of the geometric-rejection counter: this path is no longer used with real
                // frames, and blaming the mounting angle for a numeric failure is what made the UI
                // ask users to re-aim a correctly mounted camera.
                return reject(currentCalibration, Rejection.VANISHING_OUT_OF_RANGE, true);
            }
            lastVanishingX = vp.x();
            lastVanishingY = vp.y();
            observation = Double.NaN;
            return accept(currentCalibration, vp.y(), Double.NaN);
        }

        lastVanishingX = Double.NaN;
        lastVanishingY = Double.NaN;
        return accept(currentCalibration, observation, observation);
    }

    /**
     * Feeds one accepted observation into the window and reports whether it converged. Pitch-path
     * and legacy-path samples share the window; only the aggregation differs.
     */
    private StepResult accept(CameraCalibration currentCalibration, double key, double solvedPitch) {
        lastRejection = Rejection.NONE;
        consecutiveGeometricRejections = 0;

        if (status == CalibrationStore.Status.WIZARD_COMPLETED) {
            status = CalibrationStore.Status.CALIBRATING;
            // The window is already empty on this transition; clearing again would discard the very
            // sample that triggered it and cost one count.
            Log.i(TAG, "[CALIB] Starting online calibration from lane width consistency");
        }

        if (status == CalibrationStore.Status.CALIBRATING) {
            boolean impliedPath = Double.isFinite(solvedPitch);
            // The window centre is the implied pitch on the new path and the mean vanishing row on the
            // legacy one; the spread criterion follows the same split.
            double windowCentre = impliedPath ? solvedPitch : key;
            samples.addLast(windowCentre);
            while (samples.size() > REQUIRED_CONVERGENCE_SAMPLES) {
                samples.pollFirst();
            }
            progress = Math.min(99, (int) (samples.size() * 100.0 / REQUIRED_CONVERGENCE_SAMPLES));
            if (samples.size() % 15 == 0) {
                Log.d(TAG, String.format(Locale.ROOT, "[CALIB] Progress %d%% (%d/%d), implied=%.2f deg",
                        progress, samples.size(), REQUIRED_CONVERGENCE_SAMPLES, windowCentre));
            }
            if (samples.size() >= REQUIRED_CONVERGENCE_SAMPLES) {
                // Recomputed once the window is full so the aggregation sees every sample.
                windowCentre = impliedPath ? median(samples) : mean(samples);
                if (impliedPath && Math.abs(windowCentre - currentCalibration.pitchDegrees())
                        > MAX_PITCH_DEVIATION_DEGREES) {
                    lastImpliedPitch = windowCentre;
                    lastRejection = Rejection.OBSERVATION_INCOHERENT;
                    return new StepResult(currentCalibration, status, progress, windowCentre, false);
                }
                double spread = impliedPath ? medianAbsoluteDeviation(samples)
                        : standardDeviation(samples, windowCentre);
                boolean converged = impliedPath
                        ? spread <= MAX_CONVERGENCE_MAD_DEGREES
                        : spread <= MAX_CONVERGENCE_STD_DEV;
                if (converged) {
                    // The configured angle is what gets persisted: it comes from the installer rather
                    // than from the lane-width prior, and the implied pitch has already been shown to
                    // agree with it. What the solve buys is the right to declare convergence.
                    double learnedPitch = impliedPath ? currentCalibration.pitchDegrees()
                            : computePitchFromVanishingY(windowCentre, currentCalibration);
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
                                "[CALIB] CONVERGED: pitch=%.2f deg confirmed by implied %.2f deg "
                                        + "(spread=%.3f, laneWidth=%.2f m)",
                                learnedPitch, windowCentre, spread, lastLaneWidthMeters));
                        return new StepResult(candidate, status, 100, windowCentre, true);
                    }
                    Log.w(TAG, String.format(Locale.ROOT,
                            "[CALIB] Convergence rejected: pitch=%.2f deg would push the lane ROI "
                                    + "off the road", learnedPitch));
                }
            }
            return new StepResult(currentCalibration, status, progress, windowCentre, false);
        }

        if (status == CalibrationStore.Status.CALIBRATED) {
            // Online tracking only absorbs a slow drift of the mounting angle, so it uses the implied
            // pitch as a nudge and refuses readings that disagree with the locked value by more than
            // the drift budget. A single implied pitch must never be able to pull a good calibration
            // away on the strength of the lane width prior alone.
            double observedPitch = Double.isFinite(solvedPitch) ? solvedPitch
                    : computePitchFromVanishingY(key, currentCalibration);
            boolean driftBudgetRespected = Double.isFinite(solvedPitch)
                    ? Math.abs(solvedPitch - currentCalibration.pitchDegrees())
                    <= MAX_PITCH_DEVIATION_DEGREES
                    : true;
            if (Double.isFinite(observedPitch) && driftBudgetRespected
                    && observedPitch >= MIN_PITCH_DEGREES
                    && observedPitch <= MAX_PITCH_DEGREES
                    && isLaneRoiVisible(currentCalibration.withPitchDegrees(observedPitch))) {
                if (!Double.isFinite(trackedPitch)) {
                    trackedPitch = currentCalibration.pitchDegrees();
                }
                trackedPitch = trackedPitch * (1.0 - ONLINE_TRACKING_RATE)
                        + observedPitch * ONLINE_TRACKING_RATE;
                if (Math.abs(trackedPitch - currentCalibration.pitchDegrees())
                        >= PITCH_UPDATE_THRESHOLD_DEGREES
                        && isLaneRoiVisible(currentCalibration.withPitchDegrees(trackedPitch))) {
                    Log.i(TAG, String.format(Locale.ROOT,
                            "[CALIB] Online tracking adjusted pitch: %.2f -> %.2f deg",
                            currentCalibration.pitchDegrees(), trackedPitch));
                    CameraCalibration updated = currentCalibration.withPitchDegrees(trackedPitch);
                    return new StepResult(updated, status, 100, key, true);
                }
            }
            return new StepResult(currentCalibration, status, 100, key, false);
        }

        return new StepResult(currentCalibration, status, progress, key, false);
    }

    /**
     * Pitch implied by the lane width measured on the newest frame, or NaN when the observation is
     * not usable.
     *
     * <p>This is a weak absolute sensor: the lane width prior and the focal length enter the result
     * directly. Its value here is that it turns an otherwise invisible mounting error into a number
     * the installer can act on ("the lens looks about 3 degrees too steep"), and that averaging it
     * over a minute of driving tightens it enough to judge the configured angle. It deliberately does
     * not replace the configured pitch, because the configure-then-verify chain keeps the persisted
     * value free of the prior's bias.
     *
     * <p>The near/far width ratio supplies the pitch measurement after the absolute width has
     * passed the ego-lane plausibility check.
     */
    private double impliedPitchDegrees(LaneDepartureDetector.Observation lane,
                                       CameraCalibration calibration,
                                       int frameWidth, int frameHeight) {
        double nearRow = LaneDepartureDetector.ROI_BOTTOM_ROW;
        double farRow = LaneDepartureDetector.ROI_TOP_ROW;
        LaneGeometry.WidthSample near = sampleAtRow(lane, nearRow);
        LaneGeometry.WidthSample far = sampleAtRow(lane, farRow);
        if (near == null || far == null || near.rowY() <= far.rowY()) {
            lastMeasuredRatio = Double.NaN;
            lastModelRatio = Double.NaN;
            return Double.NaN;
        }
        nearRow = near.rowY();
        farRow = far.rowY();
        double measuredRatio = LaneGeometry.widthRatioMeasured(near, far);
        double modelRatio = LaneGeometry.widthRatioModel(calibration, nearRow, farRow,
                calibration.pitchDegrees(), LaneGeometry.DEFAULT_LANE_WIDTH_METERS,
                frameWidth, frameHeight);
        lastMeasuredRatio = measuredRatio;
        lastModelRatio = modelRatio;
        if (!Double.isFinite(measuredRatio) || !Double.isFinite(modelRatio) || modelRatio <= 0.0
                || near == null) {
            return Double.NaN;
        }
        // Absolute width of the tracked pair at the configured pitch: a pair that cannot be one ego
        // lane (a neighbouring lane line, a wheel track, a seam) is rejected outright. This guard is
        // what makes the ratio below trustworthy, since the ratio itself would still look plausible.
        double laneWidthMeters = LaneGeometry.laneWidthFromSample(calibration, near,
                calibration.pitchDegrees(), frameWidth, frameHeight);
        if (!Double.isFinite(laneWidthMeters)
                || laneWidthMeters < MIN_PLAUSIBLE_LANE_WIDTH_METERS
                || laneWidthMeters > MAX_PLAUSIBLE_LANE_WIDTH_METERS) {
            return Double.NaN;
        }
        lastLaneWidthMeters = laneWidthMeters;
        // The ratio cancels the lane width prior, the camera height and the focal length, so it is
        // the pitch observable; the width above only had to be plausible for this to be meaningful.
        //
        // The bracket is clamped just above the pitch at which the far sample row reaches the horizon.
        // Past that horizon the depression angle changes sign, the ratio stops being monotonic in
        // pitch, and the solve would either fail or invert to a nonsense angle.
        double horizonPitch = horizonPitchDegrees(calibration, farRow);
        double lowerBound = Math.max(calibration.pitchDegrees() - PITCH_SOLVE_RANGE_DEGREES,
                horizonPitch);
        double upperBound = Math.max(calibration.pitchDegrees() + PITCH_SOLVE_RANGE_DEGREES,
                lowerBound + 0.5);
        return LaneGeometry.solvePitchFromRatio(calibration, measuredRatio, nearRow, farRow,
                frameWidth, frameHeight, lowerBound, upperBound);
    }

    /**
     * Pitch at which the given row looks exactly at the horizon. The lane width model is only defined
     * for rows below the horizon, so any bracket bound must stay above this angle.
     */
    static double horizonPitchDegrees(CameraCalibration calibration, double rowY) {
        if (calibration == null || !Double.isFinite(rowY)) {
            return Double.NaN;
        }
        double fy = calibration.focalLengthYNormalized();
        if (fy <= 0.0) {
            return Double.NaN;
        }
        double beta = Math.atan((rowY - calibration.principalPointYNormalized()) / fy);
        // theta = pitch + beta > 0 for a row below the horizon, so the bracket must keep
        // pitch > -beta. A small margin keeps the far width away from zero.
        return Math.toDegrees(-beta) + HORIZON_MARGIN_DEGREES;
    }

    private static LaneGeometry.WidthSample sampleAtRow(LaneDepartureDetector.Observation lane,
                                                        double rowY) {
        LaneGeometry.WidthSample closest = null;
        double bestDistance = Double.MAX_VALUE;
        for (LaneGeometry.WidthSample sample : lane.widthSamples()) {
            double distance = Math.abs(sample.rowY() - rowY);
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = sample;
            }
        }
        return bestDistance <= 0.045 ? closest : null;
    }

    /**
     * True when the observation carries the width samples the pitch solve needs. Those samples are
     * real sightings of both lane edges, so a gate failure is a driving condition to wait out rather
     * than a reason to throw the accumulated window away.
     */
    private static boolean softRejection(LaneDepartureDetector.Observation lane) {
        return lane != null && lane.hasWidthSamples();
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
     * Records why an observation was dropped and returns the pending result.
     *
     * @param soft true to keep the accumulated window (a condition the driver can wait out), false
     *             to discard it (the observation says nothing usable about the geometry)
     */
    private StepResult reject(CameraCalibration calibration, Rejection reason, boolean soft) {
        if (soft) {
            pauseSamples();
        } else {
            resetSamples();
        }
        lastRejection = reason;
        if (reason == Rejection.ROI_NOT_VISIBLE) {
            consecutiveGeometricRejections++;
        } else if (!soft) {
            consecutiveGeometricRejections = 0;
        }
        return new StepResult(calibration, status, progress, Double.NaN, false);
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
     * this check is what stops the learner from trusting lane widths measured there.
     */
    static boolean isLaneRoiVisible(CameraCalibration calibration) {
        double nearDistance = groundDistanceMeters(calibration, LaneDepartureDetector.ROI_BOTTOM_ROW);
        double farDistance = groundDistanceMeters(calibration, LaneDepartureDetector.ROI_TOP_ROW);
        return Double.isFinite(nearDistance) && Double.isFinite(farDistance)
                && farDistance >= MIN_ROI_FAR_DISTANCE_METERS
                && nearDistance <= MAX_ROI_NEAR_DISTANCE_METERS;
    }

    private static double mean(ArrayDeque<Double> data) {
        double sum = 0.0;
        for (double value : data) {
            sum += value;
        }
        return sum / data.size();
    }

    private static double standardDeviation(ArrayDeque<Double> data, double mean) {
        double sumSq = 0.0;
        for (double value : data) {
            double diff = value - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / data.size());
    }

    private static double median(ArrayDeque<Double> data) {
        List<Double> sorted = new ArrayList<>(data);
        sorted.sort(Double::compare);
        return percentile(sorted, 0.5);
    }

    /** Median absolute deviation: one outlier frame cannot dominate the spread estimate. */
    private static double medianAbsoluteDeviation(ArrayDeque<Double> data) {
        double center = median(data);
        List<Double> deviations = new ArrayList<>(data.size());
        for (double value : data) {
            deviations.add(Math.abs(value - center));
        }
        deviations.sort(Double::compare);
        return percentile(deviations, 0.5);
    }

    private static double percentile(List<Double> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        int index = (int) Math.round(fraction * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    public synchronized void reset(CalibrationStore.Status newStatus) {
        this.status = newStatus == null ? CalibrationStore.Status.UNCONFIGURED : newStatus;
        this.progress = this.status == CalibrationStore.Status.CALIBRATED ? 100 : 0;
        this.samples.clear();
        this.lastVanishingY = Double.NaN;
        this.lastVanishingX = Double.NaN;
        this.trackedPitch = Double.NaN;
        this.lastImpliedPitch = Double.NaN;
        this.lastMeasuredRatio = Double.NaN;
        this.lastModelRatio = Double.NaN;
        this.lastLaneWidthMeters = Double.NaN;
        this.lastRejection = Rejection.NONE;
        this.consecutiveGeometricRejections = 0;
    }

    /** Invalidates in-flight samples after a capture gap without changing the configured status. */
    public synchronized void resetSamples() {
        samples.clear();
        lastVanishingY = Double.NaN;
        lastVanishingX = Double.NaN;
        trackedPitch = Double.NaN;
        // The window is the progress: letting the two disagree makes the status screen show a partly
        // filled bar next to an empty window, which is exactly the kind of contradiction that made the
        // original "stuck at 0%" report hard to interpret.
        if (status == CalibrationStore.Status.CALIBRATING) {
            progress = 0;
        }
    }

    /** Drops in-flight samples but keeps the reported progress, for conditions that pass. */
    private void pauseSamples() {
        // Deliberately keeps the window: the driver is expected to satisfy the gate again shortly.
    }

    /** Reason the most recent observation was dropped, or {@link Rejection#NONE} if it was accepted. */
    public synchronized Rejection lastRejection() {
        return lastRejection;
    }

    /**
     * Number of back-to-back rejections caused by camera geometry. A sustained count means the
     * configured pitch puts the lane ROI off the road, which driving cannot fix.
     */
    public synchronized int consecutiveGeometricRejections() {
        return consecutiveGeometricRejections;
    }

    public synchronized CalibrationStore.Status status() {
        return status;
    }

    public synchronized int progress() {
        return progress;
    }

    /** Samples currently held in the convergence window. */
    public synchronized int windowSize() {
        return samples.size();
    }

    public synchronized double lastVanishingY() {
        return lastVanishingY;
    }

    public synchronized double lastVanishingX() {
        return lastVanishingX;
    }

    /** Pitch implied by the newest lane width measurement, or NaN when unavailable. */
    public synchronized double lastSolvedPitchDegrees() {
        return lastImpliedPitch;
    }

    /** Lane width implied by the newest measurement at the configured pitch, in metres. */
    public synchronized double lastLaneWidthMeters() {
        return lastLaneWidthMeters;
    }

    /** Measured near/far lane width ratio of the newest coherent observation. */
    public synchronized double lastMeasuredRatio() {
        return lastMeasuredRatio;
    }

    /** Model ratio at the configured pitch for the newest coherent observation. */
    public synchronized double lastModelRatio() {
        return lastModelRatio;
    }
}
