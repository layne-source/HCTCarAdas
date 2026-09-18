package com.hct.adas;

/**
 * Flat-road lane geometry shared by the calibration learner, the UI mapper and the overlay.
 *
 * <p>All model functions work on normalized image coordinates: {@code x}, {@code y} are both in
 * [0, 1] and {@code y} grows downwards, matching the detector. Canonical ground coordinates are
 * {@code Z} forwards and {@code X} to the right of the vehicle, in metres, so a positive
 * {@code X} means the point is to the right of the camera optical axis. Lateral distance uses the
 * pinhole relation {@code X = (x - 0.5) * Z_axis / f}, assuming a centered principal point and a
 * camera aligned with the vehicle axis in yaw. For a row ray, the independent geometry contract is
 * {@code Z_ground = H/tan(alpha + beta)} and {@code Z_axis = H*cos(beta)/sin(alpha + beta)}.
 * Ground distance remains available through {@link CameraCalibration#estimateDistanceMeters(double)}
 * for target range calculations.
 *
 * <p>The vertical focal length {@code f_y} is height-normalized (image height = 1) while the
 * horizontal one is width-normalized (image width = 1), so {@code f_x = f_y * height / width}.
 * Every relation here assumes a matched frame size; callers must gate on
 * {@link CameraCalibration#isUsableFor(int, int)} first.
 */
public final class LaneGeometry {
    /** Nominal lane width used only for the metric readout; the calibration gates use ratios. */
    public static final double DEFAULT_LANE_WIDTH_METERS = 3.5;
    /** Distance at which the reported curvature radius is evaluated. */
    public static final double CURVATURE_EVALUATION_METERS = 15.0;
    /**
     * A fitted quadratic term below this magnitude is treated as a straight lane: at 1e-4 the
     * radius at 15 m exceeds 1 km, well past the LKAS "straight" threshold.
     */
    public static final double MIN_ABS_CURVATURE = 1.0e-4;

    private static final double DEGREES_PER_RADIAN = 180.0 / Math.PI;

    private LaneGeometry() {
    }

    /** A lane boundary sample: where the line was seen and how strong that sighting was. */
    public record BoundarySample(double x, double confidence) {
        public static final BoundarySample NONE = new BoundarySample(Double.NaN, 0.0);
    }

    /**
     * Lane width measured at one image row. This is the raw observation the pitch solver consumes,
     * so it carries both edges and the depth they were measured at.
     */
    public record WidthSample(double rowY, double leftX, double rightX, double confidence) {
        /** Pixel width between the two boundaries; NaN when an edge is missing. */
        public double widthPixels() {
            return Double.isFinite(leftX) && Double.isFinite(rightX) ? rightX - leftX : Double.NaN;
        }
    }

    /**
     * Lane geometry published to the UI. {@code centerOffsetNormalized} and
     * {@code centerOffsetMeters} are positive when the vehicle sits right of the lane centre, matching
     * the "keep left" instruction of travel: the lane centre images to the left of the vehicle axis.
     */
    public record LaneSnapshot(long timestampNanos,
                               double centerOffsetNormalized,
                               double centerOffsetMeters,
                               double laneWidthMeters,
                               double curvatureRadiusMeters,
                               int sampleCount) {
        public static final LaneSnapshot INVALID =
                new LaneSnapshot(0L, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);

        public boolean valid() {
            return sampleCount > 0 && Double.isFinite(centerOffsetNormalized);
        }

        public boolean curvatureValid() {
            return Double.isFinite(curvatureRadiusMeters);
        }

        /** True when the fit classified the lane as curved or confirmed it is straight. */
        public boolean curvatureKnown() {
            return !Double.isNaN(curvatureRadiusMeters);
        }

        /** Offset as a fraction of the measured lane width, or NaN without metric geometry. */
        public double centerOffsetLaneFraction() {
            return Double.isFinite(centerOffsetMeters) && Double.isFinite(laneWidthMeters)
                    && laneWidthMeters > 0.0 ? centerOffsetMeters / laneWidthMeters : Double.NaN;
        }

        /** Image-space lane centre; vehicle offset has the opposite sign by definition. */
        public double laneCenterImageX() {
            return valid() ? 0.5 - centerOffsetNormalized : Double.NaN;
        }

        /** True when the vehicle sits left of the lane centre, so the driver should move right. */
        public boolean vehicleLeftOfCenter() {
            return valid() && centerOffsetNormalized < 0.0;
        }

        /** True when the vehicle sits right of the lane centre, so the driver should move left. */
        public boolean vehicleRightOfCenter() {
            return valid() && centerOffsetNormalized > 0.0;
        }
    }

    public static boolean isUsable(CameraCalibration calibration, int frameWidth, int frameHeight) {
        return calibration != null && frameWidth > 0 && frameHeight > 0
                && calibration.isUsableFor(frameWidth, frameHeight);
    }

    /** Ground distance of an image row under the flat-road model, or NaN when the ray misses. */
    public static double distanceMeters(CameraCalibration calibration, double rowY) {
        return calibration == null ? Double.NaN : calibration.estimateDistanceMeters(rowY);
    }

    /** Lateral offset of a normalized image column at the given camera-axis depth. */
    public static double lateralMeters(CameraCalibration calibration, double x, double zMeters,
                                       int frameWidth, int frameHeight) {
        if (calibration == null || !Double.isFinite(x) || !Double.isFinite(zMeters)
                || zMeters <= 0.0) {
            return Double.NaN;
        }
        double fx = focalLengthXNormalized(calibration, frameWidth, frameHeight);
        return fx > 0.0 ? (x - 0.5) * zMeters / fx : Double.NaN;
    }

    /** Horizontal focal length normalized by image width, so x in [0, 1] maps linearly. */
    public static double focalLengthXNormalized(CameraCalibration calibration,
                                                int frameWidth, int frameHeight) {
        if (calibration == null || frameWidth <= 0 || frameHeight <= 0) {
            return Double.NaN;
        }
        return calibration.focalLengthYNormalized() * frameHeight / frameWidth;
    }

    /** Image row that looks at the given ground distance, assuming the calibration is correct. */
    public static double rowForDistance(CameraCalibration calibration, double distanceMeters) {
        if (calibration == null || !Double.isFinite(distanceMeters) || distanceMeters <= 0.0) {
            return Double.NaN;
        }
        double theta = Math.atan(calibration.cameraHeightMeters() / distanceMeters);
        return calibration.principalPointYNormalized()
                + calibration.focalLengthYNormalized()
                * Math.tan(theta - Math.toRadians(calibration.pitchDegrees()));
    }

    /**
     * Normalized image width of a lane of the given physical width, as seen at one image row.
     *
     * <p>For pitch alpha and row-ray angle beta, the calibrated width contract is
     * {@code W * f_x * sin(alpha + beta) / (H*cos(beta))}. The inverse below uses the same
     * contract.
     *
     * <p>Height and horizontal focal length are therefore the two error sources of any absolute lane
     * width measurement, which is why the calibrated mounting angle is not replaced by this model.
     */
    public static double laneWidthModelMeters(CameraCalibration calibration, double rowY,
                                              double pitchDegrees, double laneWidthMeters,
                                              int frameWidth, int frameHeight) {
        if (calibration == null || !Double.isFinite(rowY) || !Double.isFinite(pitchDegrees)
                || !Double.isFinite(laneWidthMeters) || laneWidthMeters <= 0.0) {
            return Double.NaN;
        }
        double fy = calibration.focalLengthYNormalized();
        double height = calibration.cameraHeightMeters();
        if (fy <= 0.0 || height <= 0.0) {
            return Double.NaN;
        }
        double beta = Math.atan((rowY - calibration.principalPointYNormalized()) / fy);
        double theta = Math.toRadians(pitchDegrees) + beta;
        double sin = Math.sin(theta);
        if (sin <= 1.0e-4 || Math.cos(theta) <= 1.0e-4) {
            return Double.NaN;
        }
        double fx = focalLengthXNormalized(calibration, frameWidth, frameHeight);
        return laneWidthMeters * fx * sin / (height * Math.cos(beta));
    }

    /** Model ratio between two rows; physical lane width and camera height cancel. */
    public static double widthRatioModel(CameraCalibration calibration, double nearRow,
                                         double farRow, double pitchDegrees, double laneWidthMeters,
                                         int frameWidth, int frameHeight) {
        // On a road descending away from the camera a larger row value is closer, so the near sample
        // sits at the larger row. Taking these the wrong way round makes the model ratio the inverse
        // of the measurement and the solve silently fails.
        double near = laneWidthModelMeters(calibration, nearRow, pitchDegrees, laneWidthMeters,
                frameWidth, frameHeight);
        double far = laneWidthModelMeters(calibration, farRow, pitchDegrees, laneWidthMeters,
                frameWidth, frameHeight);
        if (!Double.isFinite(near) || !Double.isFinite(far) || far <= 0.0) {
            return Double.NaN;
        }
        return near / far;
    }

    /**
     * Ratio of the two measured widths.
     *
     * <p>Under the flat-road model the ratio depends on pitch, image rows and vertical focal length.
     * It is meaningful only when both tracked boundaries belong to the same constant-width lane.
     */
    public static double widthRatioMeasured(WidthSample near, WidthSample far) {
        if (near == null || far == null) {
            return Double.NaN;
        }
        double nearWidth = near.widthPixels();
        double farWidth = far.widthPixels();
        if (!Double.isFinite(nearWidth) || !Double.isFinite(farWidth)
                || nearWidth <= 0.0 || farWidth <= 0.0) {
            return Double.NaN;
        }
        return nearWidth / farWidth;
    }

    /**
     * Inverts the lane width model to obtain the pitch that would make the two lane edges sit
     * {@code laneWidthMeters} apart at {@code rowY}. The model increases monotonically with pitch
     * over the supported mounting range, so a plain bisection is enough and cannot run away.
     *
     * <p>Accuracy is bounded by the uncertainty of the lane width prior (about 10%) and of the focal
     * length (up to 15% for an unpublished lens), which together put roughly 2-3 degrees on a single
     * measurement. Time averaging is what makes this usable, and it is the reason the configured
     * mounting angle - not this solve - remains the value that gets persisted.
     *
     * @return the implied pitch in degrees, or NaN when {@code laneWidthMeters} cannot be produced by
     *         any pitch inside the bracket
     */
    public static double solvePitchFromLaneWidth(CameraCalibration calibration, double laneWidthMeters,
                                                 double rowY, double measuredWidthNormalized,
                                                 int frameWidth, int frameHeight,
                                                 double lowerPitchDegrees,
                                                 double upperPitchDegrees) {
        if (calibration == null || !Double.isFinite(measuredWidthNormalized)
                || measuredWidthNormalized <= 0.0 || !(lowerPitchDegrees < upperPitchDegrees)) {
            return Double.NaN;
        }
        double lowWidth = laneWidthModelMeters(calibration, rowY, lowerPitchDegrees,
                laneWidthMeters, frameWidth, frameHeight);
        double highWidth = laneWidthModelMeters(calibration, rowY, upperPitchDegrees,
                laneWidthMeters, frameWidth, frameHeight);
        if (!Double.isFinite(lowWidth) || !Double.isFinite(highWidth)
                || (lowWidth - measuredWidthNormalized) * (highWidth - measuredWidthNormalized) > 0.0) {
            return Double.NaN;
        }
        double low = lowerPitchDegrees;
        double high = upperPitchDegrees;
        for (int i = 0; i < 40 && high - low > 1.0e-4; i++) {
            double mid = 0.5 * (low + high);
            double midWidth = laneWidthModelMeters(calibration, rowY, mid, laneWidthMeters,
                    frameWidth, frameHeight);
            if (!Double.isFinite(midWidth)) {
                return Double.NaN;
            }
            // Width grows with pitch over the supported mounting range (it peaks around 45 degrees of
            // depression, far outside the bracket).
            if (midWidth < measuredWidthNormalized) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return 0.5 * (low + high);
    }

    /**
     * Recovers the pitch from the measured near/far lane width ratio.
     *
     * <p>The ratio is {@code sin(theta_near) * cos(beta_far)
     * / (sin(theta_far) * cos(beta_near))}. Physical lane width, camera height and horizontal focal
     * length cancel; vertical focal length still determines beta. The ratio decreases monotonically
     * with pitch over the supported bracket, so bisection is sufficient.
     *
     * @return the solved pitch in degrees, or NaN when no pitch in the bracket reproduces the ratio
     */
    public static double solvePitchFromRatio(CameraCalibration calibration, double measuredRatio,
                                             double nearRow, double farRow,
                                             int frameWidth, int frameHeight,
                                             double lowerPitchDegrees,
                                             double upperPitchDegrees) {
        if (calibration == null || !Double.isFinite(measuredRatio) || measuredRatio <= 0.0
                || !(lowerPitchDegrees < upperPitchDegrees)) {
            return Double.NaN;
        }
        double lowerRatio = widthRatioModel(calibration, nearRow, farRow, lowerPitchDegrees,
                DEFAULT_LANE_WIDTH_METERS, frameWidth, frameHeight);
        double upperRatio = widthRatioModel(calibration, nearRow, farRow, upperPitchDegrees,
                DEFAULT_LANE_WIDTH_METERS, frameWidth, frameHeight);
        if (!Double.isFinite(lowerRatio) || !Double.isFinite(upperRatio)
                || (lowerRatio - measuredRatio) * (upperRatio - measuredRatio) > 0.0) {
            return Double.NaN;
        }
        double low = lowerPitchDegrees;
        double high = upperPitchDegrees;
        for (int i = 0; i < 40 && high - low > 1.0e-4; i++) {
            double mid = 0.5 * (low + high);
            double midRatio = widthRatioModel(calibration, nearRow, farRow, mid,
                    DEFAULT_LANE_WIDTH_METERS, frameWidth, frameHeight);
            if (!Double.isFinite(midRatio)) {
                return Double.NaN;
            }
            if (midRatio > measuredRatio) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return 0.5 * (low + high);
    }

    /**
     * Physical lane width implied by a measurement at the configured pitch, used to report the metric
     * offset and to detect a boundary that is not the ego lane edge. This is the exact inverse of
     * {@link #laneWidthModelMeters}: {@code W = w * H * cos(beta) / (f_x * sin(theta))}.
     */
    public static double laneWidthFromSample(CameraCalibration calibration, WidthSample sample,
                                             double pitchDegrees, int frameWidth, int frameHeight) {
        if (calibration == null || sample == null || !Double.isFinite(pitchDegrees)) {
            return Double.NaN;
        }
        double width = sample.widthPixels();
        if (!Double.isFinite(width) || width <= 0.0) {
            return Double.NaN;
        }
        double fy = calibration.focalLengthYNormalized();
        double height = calibration.cameraHeightMeters();
        if (fy <= 0.0 || height <= 0.0) {
            return Double.NaN;
        }
        double beta = Math.atan((sample.rowY() - calibration.principalPointYNormalized()) / fy);
        double theta = Math.toRadians(pitchDegrees) + beta;
        double sin = Math.sin(theta);
        if (sin <= 1.0e-4) {
            return Double.NaN;
        }
        double fx = focalLengthXNormalized(calibration, frameWidth, frameHeight);
        if (fx <= 0.0) {
            return Double.NaN;
        }
        return width * height * Math.cos(beta) / (fx * sin);
    }

    /**
     * Assembles the published lane geometry from the near-row width sample. The metric offset and
     * the curvature both need the calibration to be bound to this frame size; when it is not, the
     * normalized offset is still reported so the UI can show something useful.
     */
    public static LaneSnapshot snapshot(long timestampNanos, CameraCalibration calibration,
                                        double pitchDegrees, WidthSample nearSample,
                                        java.util.List<WidthSample> samples,
                                        int frameWidth, int frameHeight) {
        if (nearSample == null || !Double.isFinite(nearSample.widthPixels())
                || nearSample.widthPixels() <= 0.0) {
            return LaneSnapshot.INVALID;
        }
        double centerOffset = 0.5 * (nearSample.leftX() + nearSample.rightX()) - 0.5;
        // Image space has the lane centre to the left of the vehicle axis when the vehicle sits right
        // of the lane, so the published offset is the negation of the image-space one.
        double vehicleOffsetNormalized = -centerOffset;
        double laneWidth = laneWidthFromSample(calibration, nearSample, pitchDegrees,
                frameWidth, frameHeight);
        double radius = curvatureRadiusMeters(calibration, pitchDegrees, samples,
                frameWidth, frameHeight);
        return new LaneSnapshot(timestampNanos, vehicleOffsetNormalized,
                Double.isFinite(laneWidth)
                        ? vehicleOffsetNormalized / nearSample.widthPixels() * laneWidth : Double.NaN,
                laneWidth, radius, samples == null ? 0 : samples.size());
    }

    /**
     * Fits {@code X = a·Z² + b·Z + c} to the lane centre line and returns the radius at
     * {@link #CURVATURE_EVALUATION_METERS}.
     *
     * <p>Returns positive infinity for a confirmed straight lane (|a| below
     * {@link #MIN_ABS_CURVATURE}); NaN means the fit was unavailable or failed.
     */
    public static double curvatureRadiusMeters(CameraCalibration calibration, double pitchDegrees,
                                               java.util.List<WidthSample> samples,
                                               int frameWidth, int frameHeight) {
        if (calibration == null || !Double.isFinite(pitchDegrees)
                || samples == null || samples.size() < 4) {
            return Double.NaN;
        }
        double fx = focalLengthXNormalized(calibration, frameWidth, frameHeight);
        if (!(fx > 0.0)) {
            return Double.NaN;
        }
        int count = 0;
        double sumZ = 0.0;
        double sumZ2 = 0.0;
        double sumZ3 = 0.0;
        double sumZ4 = 0.0;
        double sumX = 0.0;
        double sumZX = 0.0;
        double sumZ2X = 0.0;
        for (WidthSample sample : samples) {
            if (sample == null) {
                continue;
            }
            double width = sample.widthPixels();
            if (!Double.isFinite(width) || width <= 0.0) {
                continue;
            }
            double groundZ = distanceMeters(calibration, sample.rowY());
            if (!Double.isFinite(groundZ) || groundZ < 4.0 || groundZ > 60.0) {
                continue;
            }
            double zAxis = cameraAxisDepthMeters(calibration, sample.rowY(), pitchDegrees);
            if (!Double.isFinite(zAxis) || zAxis <= 0.0) {
                continue;
            }
            double x = lateralMeters(calibration, 0.5 * (sample.leftX() + sample.rightX()), zAxis,
                    frameWidth, frameHeight);
            if (!Double.isFinite(x)) {
                continue;
            }
            count++;
            sumZ += groundZ;
            sumZ2 += groundZ * groundZ;
            sumZ3 += groundZ * groundZ * groundZ;
            sumZ4 += groundZ * groundZ * groundZ * groundZ;
            sumX += x;
            sumZX += groundZ * x;
            sumZ2X += groundZ * groundZ * x;
        }
        if (count < 4) {
            return Double.NaN;
        }
        double[] fit = solveNormalEquations(count, sumZ, sumZ2, sumZ3, sumZ4,
                sumX, sumZX, sumZ2X);
        if (fit == null) {
            return Double.NaN;
        }
        double a = fit[0];
        double b = fit[1];
        if (!Double.isFinite(a) || !Double.isFinite(b)) {
            return Double.NaN;
        }
        if (Math.abs(a) < MIN_ABS_CURVATURE) {
            return Double.POSITIVE_INFINITY;
        }
        double z = CURVATURE_EVALUATION_METERS;
        double slope = 2.0 * a * z + b;
        // The published ISO 8855 sign is negative for a left curve. With X growing to the right,
        // a negative fitted quadratic coefficient therefore maps through 2a.
        double radius = Math.pow(1.0 + slope * slope, 1.5) / (2.0 * a);
        return Double.isFinite(radius) && radius != 0.0 ? radius : Double.NaN;
    }

    /** Camera-axis depth for a row ray: H*cos(beta)/sin(alpha + beta). */
    private static double cameraAxisDepthMeters(CameraCalibration calibration, double rowY,
                                                double pitchDegrees) {
        if (calibration == null || !Double.isFinite(rowY) || !Double.isFinite(pitchDegrees)) {
            return Double.NaN;
        }
        double beta = Math.atan((rowY - calibration.principalPointYNormalized())
                / calibration.focalLengthYNormalized());
        double theta = Math.toRadians(pitchDegrees) + beta;
        double sin = Math.sin(theta);
        return sin > 1.0e-4
                ? calibration.cameraHeightMeters() * Math.cos(beta) / sin : Double.NaN;
    }

    /**
     * Least-squares solution of the 3x3 normal equations for a quadratic fit. Returns
     * {@code [a, b, c]} for {@code X = a·Z² + b·Z + c}, or null when the system is singular.
     */
    private static double[] solveNormalEquations(int n, double sz, double sz2, double sz3,
                                                 double sz4, double sx, double szx, double sz2x) {
        double[][] m = {
                {sz4, sz3, sz2, sz2x},
                {sz3, sz2, sz, szx},
                {sz2, sz, n, sx}
        };
        for (int col = 0; col < 3; col++) {
            int pivot = col;
            for (int row = col + 1; row < 3; row++) {
                if (Math.abs(m[row][col]) > Math.abs(m[pivot][col])) {
                    pivot = row;
                }
            }
            if (Math.abs(m[pivot][col]) < 1.0e-9) {
                return null;
            }
            double[] swap = m[col];
            m[col] = m[pivot];
            m[pivot] = swap;
            double diagonal = m[col][col];
            for (int k = col; k < 4; k++) {
                m[col][k] /= diagonal;
            }
            for (int row = 0; row < 3; row++) {
                if (row == col) {
                    continue;
                }
                double factor = m[row][col];
                if (factor == 0.0) {
                    continue;
                }
                for (int k = col; k < 4; k++) {
                    m[row][k] -= factor * m[col][k];
                }
            }
        }
        return new double[] {m[0][3], m[1][3], m[2][3]};
    }

    /** Convenience for direction text: negative curvature means the road turns left. */
    public static String curveDirection(double curvatureRadiusMeters) {
        if (!Double.isFinite(curvatureRadiusMeters)) {
            return "";
        }
        return curvatureRadiusMeters < 0.0 ? "Left" : "Right";
    }

    static double degreesPerRadian() {
        return DEGREES_PER_RADIAN;
    }
}
