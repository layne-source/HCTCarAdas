package com.hct.adas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Y-plane lane edge extractor. Two lane boundaries are tracked across a band of image rows using a
 * bright-ridge (second derivative) response with per-row local contrast normalisation, which keeps
 * shaded, tunnel and over-exposed asphalt usable where a fixed absolute threshold is not.
 *
 * <p>Each boundary keeps its identity across rows and frames by tracking the ridge nearest to the
 * previous sighting, starting from the edge closest to the image centre. That ordering is what stops
 * a neighbouring lane line, a guard rail or a road seam from being adopted as the ego lane edge: the
 * ego lane edges are always the innermost bright ridges on each side of the vanishing point.
 *
 * <p>The detection reports two things: the legacy four-point trapezoid (kept so the existing overlay,
 * simulator and learner contracts stay valid) and the per-row width samples that the calibration
 * pitch solver consumes.
 */
public final class LaneDepartureDetector {
    /** Row the top measurement band is reported at. */
    public static final double Y_TOP = 0.60;
    /** Row the bottom measurement band is reported at. */
    public static final double Y_BOTTOM = 0.78;
    /** Far edge of the scanned region; used to check the ROI still looks at road, not at the hood. */
    public static final double ROI_TOP_ROW = 0.54;
    /**
     * Near edge of the scanned region. Kept well above the image bottom: the near sample carries the
     * lane width, and on a road receding away from the camera the depression angle of a row close to
     * the bottom can exceed the mounting angle by so much that the near/far width ratio stops being a
     * monotonic function of pitch and the calibration solve loses its root.
     */
    public static final double ROI_BOTTOM_ROW = 0.82;
    /** Rows actually sampled. More than two points is what makes the slope estimate usable. */
    private static final int SAMPLE_ROWS = 9;
    /** Half-width of the per-row boundary search window around the previous sighting. */
    private static final double SEARCH_HALF_WIDTH = 0.07;
    /** Widest a single search step may look when no previous sighting exists yet. */
    private static final double INITIAL_SEARCH_HALF_WIDTH = 0.34;
    /** Neighbouring bright pixels counted into one ridge before the second derivative is taken. */
    private static final double RIDGE_HALF_SPAN = 0.005;
    /** Ridge response above the local row contrast that counts as a lane marking. */
    private static final double MIN_RIDGE_RESPONSE = 30.0;
    /** Response that maps to a per-sighting confidence of 1.0. */
    private static final double FULL_RIDGE_RESPONSE = 100.0;
    /** Rows a width sample needs on both boundaries before it is reported. */
    private static final double MIN_SAMPLE_SPAN = 0.26;
    /** Both boundaries must clear this confidence before a width sample is published. */
    private static final double MIN_SAMPLE_CONFIDENCE = 0.30;
    /** Temporal blend applied to the published trapezoid when consecutive frames both see lanes. */
    private static final double TEMPORAL_BLEND = 0.35;

    public record Observation(double centerOffset, double confidence, boolean available,
                              double leftTopX, double rightTopX,
                              double leftBottomX, double rightBottomX,
                              List<LaneGeometry.WidthSample> widthSamples) {
        public static final Observation UNAVAILABLE = new Observation(
                0.0, 0.0, false, Double.NaN, Double.NaN, Double.NaN, Double.NaN, List.of());

        /** Backwards-compatible construction without width samples (legacy vanishing-point path). */
        public Observation(double centerOffset, double confidence, boolean available,
                           double leftTopX, double rightTopX,
                           double leftBottomX, double rightBottomX) {
            this(centerOffset, confidence, available, leftTopX, rightTopX, leftBottomX, rightBottomX,
                    List.of());
        }

        public Observation {
            widthSamples = widthSamples == null ? List.of() : List.copyOf(widthSamples);
        }

        public boolean hasWidthSamples() {
            return !widthSamples.isEmpty();
        }

        /**
         * Replaces the width samples while keeping the legacy fields. Used by the indoor simulator,
         * which synthesises boundary geometry instead of reading pixels.
         */
        public Observation withWidthSamples(List<LaneGeometry.WidthSample> samples) {
            return new Observation(centerOffset, confidence, available, leftTopX, rightTopX,
                    leftBottomX, rightBottomX, samples);
        }
    }

    /** One frame of boundary sightings: a straight fit plus the rows it was actually seen on. */
    private static final class BoundaryTrack {
        double sumW;
        double sumWx;
        double sumWy;
        double sumWxy;
        double sumWy2;
        double minY = Double.NaN;
        double maxY = Double.NaN;
        double meanX = Double.NaN;
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double lastConfidence;
        double lastRowY = Double.NaN;
        double lastX = Double.NaN;

        void add(double x, double y, double ridge) {
            double w = Math.max(1.0, ridge);
            sumW += w;
            sumWx += w * x;
            sumWy += w * y;
            sumWxy += w * x * y;
            sumWy2 += w * y * y;
            meanX = sumWx / sumW;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            if (Double.isNaN(minY)) {
                minY = y;
            }
            maxY = y;
            lastConfidence = confidenceOf(ridge);
            lastRowY = y;
            lastX = x;
        }

        boolean empty() {
            return sumW <= 0.0;
        }

        double rowSpan() {
            return empty() || Double.isNaN(minY) ? 0.0 : maxY - minY;
        }

        double coverage() {
            return rowSpan() / Math.max(0.01, ROI_BOTTOM_ROW - ROI_TOP_ROW);
        }

        /** Interpolated boundary position at a row; NaN when the fit is not usable. */
        double xAt(double rowY) {
            if (empty() || maxY - minY < 0.01) {
                return Double.NaN;
            }
            double denominator = sumW * sumWy2 - sumWy * sumWy;
            if (Math.abs(denominator) < 1.0e-12) {
                return meanX;
            }
            double slope = (sumW * sumWxy - sumWy * sumWx) / denominator;
            double intercept = (sumWx - slope * sumWy) / sumW;
            if (!Double.isFinite(slope) || !Double.isFinite(intercept)) {
                return Double.NaN;
            }
            return slope * rowY + intercept;
        }

        LaneGeometry.BoundarySample sampleAt(double rowY) {
            double x = xAt(rowY);
            return Double.isFinite(x)
                    ? new LaneGeometry.BoundarySample(x, lastConfidence)
                    : LaneGeometry.BoundarySample.NONE;
        }
    }

    private record RidgeCandidate(int x, double ridge) {
        public static final RidgeCandidate NONE = new RidgeCandidate(-1, 0.0);

        public boolean found() {
            return x >= 0;
        }
    }

    private static final class FrameContext {
        final byte[] nv21;
        final int width;
        final int height;
        final int delta;
        final int step;
        final double[] rowBrightness;
        final double[] rows;

        FrameContext(byte[] nv21, int width, int height) {
            this.nv21 = nv21;
            this.width = width;
            this.height = height;
            this.delta = Math.max(3, (int) (width * RIDGE_HALF_SPAN));
            this.step = Math.max(2, width / 320);
            this.rows = new double[SAMPLE_ROWS];
            for (int i = 0; i < SAMPLE_ROWS; i++) {
                double rowY = ROI_TOP_ROW
                        + (ROI_BOTTOM_ROW - ROI_TOP_ROW) * i / (SAMPLE_ROWS - 1.0);
                rows[i] = Math.round(rowY * height) / (double) height;
            }
            this.rowBrightness = new double[SAMPLE_ROWS];
            for (int i = 0; i < SAMPLE_ROWS; i++) {
                rowBrightness[i] = medianLuma(nv21, width, height, rows[i]);
            }
        }

        int pixelRow(double rowY) {
            return Math.min(height - 1, Math.max(0, (int) Math.round(rowY * height)));
        }
    }

    private Observation last;
    private int lastWidth;
    private int lastHeight;

    public void reset() {
        last = null;
        lastWidth = 0;
        lastHeight = 0;
    }

    public Observation detect(byte[] nv21, int width, int height) {
        if (nv21 == null || width <= 0 || height <= 0
                || nv21.length != (long) width * height * 3 / 2) {
            return unavailable();
        }
        if (width != lastWidth || height != lastHeight) {
            last = null;
            lastWidth = width;
            lastHeight = height;
        }
        FrameContext context = new FrameContext(nv21, width, height);
        BoundaryTrack left = trackBoundary(context, true, last);
        BoundaryTrack right = trackBoundary(context, false, last);
        if (left.empty() || right.empty()) {
            return unavailable();
        }
        double leftTop = left.xAt(Y_TOP);
        double leftBottom = left.xAt(Y_BOTTOM);
        double rightTop = right.xAt(Y_TOP);
        double rightBottom = right.xAt(Y_BOTTOM);
        if (!finite(leftTop) || !finite(leftBottom) || !finite(rightTop) || !finite(rightBottom)) {
            return unavailable();
        }
        double widthTop = rightTop - leftTop;
        double widthBottom = rightBottom - leftBottom;
        // Perspective sanity check: the lane must be wider near the camera than far away.
        if (widthBottom < 0.18 || widthTop < 0.08 || widthBottom <= widthTop + 0.02) {
            return unavailable();
        }
        List<LaneGeometry.WidthSample> samples = widthSamples(context, left, right);
        double confidence = 0.5 * (left.lastConfidence + right.lastConfidence);
        boolean available = confidence >= 0.35;
        double centerOffset = (leftBottom + rightBottom) * 0.5 - 0.5;
        Observation current = new Observation(centerOffset, confidence, available,
                leftTop, rightTop, leftBottom, rightBottom, samples);
        if (last != null && last.available() && current.available()) {
            current = blend(last, current, TEMPORAL_BLEND);
        }
        last = current;
        return current;
    }

    /**
     * Width samples on the rows where both boundaries were actually seen well enough. Publishing
     * only corroborated rows keeps the calibration ratio from mixing a measured edge with an
     * extrapolated one.
     */
    private static List<LaneGeometry.WidthSample> widthSamples(FrameContext context,
                                                               BoundaryTrack left,
                                                               BoundaryTrack right) {
        List<LaneGeometry.WidthSample> samples = new ArrayList<>(SAMPLE_ROWS);
        if (!hasSpan(left) || !hasSpan(right)) {
            return samples;
        }
        for (double rowY : context.rows) {
            LaneGeometry.BoundarySample leftSample = left.sampleAt(rowY);
            LaneGeometry.BoundarySample rightSample = right.sampleAt(rowY);
            if (!finite(leftSample.x()) || !finite(rightSample.x())) {
                continue;
            }
            if (leftSample.confidence() < MIN_SAMPLE_CONFIDENCE
                    || rightSample.confidence() < MIN_SAMPLE_CONFIDENCE) {
                continue;
            }
            double width = rightSample.x() - leftSample.x();
            if (width <= 0.02) {
                continue;
            }
            samples.add(new LaneGeometry.WidthSample(rowY, leftSample.x(), rightSample.x(),
                    Math.min(leftSample.confidence(), rightSample.confidence())));
        }
        return samples;
    }

    private static boolean hasSpan(BoundaryTrack track) {
        return !track.empty() && track.rowSpan() >= MIN_SAMPLE_SPAN;
    }

    /**
     * Tracks one lane boundary across the sampled rows, starting from the bright ridge closest to
     * the image centre and then following the ridge nearest to the previous row's sighting.
     */
    private BoundaryTrack trackBoundary(FrameContext context, boolean leftSide, Observation previous) {
        BoundaryTrack track = new BoundaryTrack();
        double reference = 0.5;
        Double previousBoundaryAtStart = boundaryAt(previous, leftSide, context.rows[0]);
        if (previousBoundaryAtStart != null) {
            reference = previousBoundaryAtStart;
        }
        boolean first = true;
        for (int i = 0; i < context.rows.length; i++) {
            double rowY = context.rows[i];
            int y = context.pixelRow(rowY);
            double halfWidth = first ? INITIAL_SEARCH_HALF_WIDTH : SEARCH_HALF_WIDTH;
            double minX = Math.max(0.02, leftSide ? 0.02 : reference - halfWidth);
            double maxX = Math.min(0.98, leftSide ? reference + halfWidth : 0.98);
            RidgeCandidate candidate = findRidge(context, y, context.rowBrightness[i],
                    minX, maxX);
            if (!candidate.found()) {
                continue;
            }
            double x = candidate.x() / (double) context.width;
            if (leftSide) {
                x = Math.min(x, 0.5);
            } else {
                x = Math.max(x, 0.5);
            }
            track.add(x, rowY, candidate.ridge());
            reference = x;
            first = false;
        }
        return track;
    }

    private static Double boundaryAt(Observation observation, boolean leftSide, double rowY) {
        if (observation == null || !observation.available()) {
            return null;
        }
        for (LaneGeometry.WidthSample sample : observation.widthSamples()) {
            if (Math.abs(sample.rowY() - rowY) < 0.02) {
                double x = leftSide ? sample.leftX() : sample.rightX();
                return Double.isFinite(x) ? x : null;
            }
        }
        double x = leftSide ? observation.leftTopX() : observation.rightTopX();
        return Double.isFinite(x) ? x : null;
    }

    /**
     * Strongest bright-ridge response inside [minX, maxX] on one row. The response is measured
     * against the row's own median luma, so a dark tunnel and a sunlit road use the same threshold.
     */
    private static RidgeCandidate findRidge(FrameContext context, int y, double rowBrightness,
                                           double minX, double maxX) {
        double threshold = rowBrightness + MIN_RIDGE_RESPONSE;
        int start = Math.max(context.delta + 1, (int) (context.width * minX));
        int end = Math.min(context.width - context.delta - 2, (int) (context.width * maxX));
        int bestX = -1;
        double bestRidge = 0.0;
        for (int x = start; x <= end; x += context.step) {
            double center = luma(context.nv21, context.width, x, y);
            if (center < threshold) {
                continue;
            }
            double left = luma(context.nv21, context.width, x - context.delta, y);
            double right = luma(context.nv21, context.width, x + context.delta, y);
            double ridge = center * 2.0 - left - right;
            if (ridge > bestRidge) {
                bestRidge = ridge;
                bestX = x;
            }
        }
        return bestX >= 0 ? new RidgeCandidate(bestX, bestRidge) : RidgeCandidate.NONE;
    }

    /** Median luma of one image row, used as the local illumination reference. */
    private static double medianLuma(byte[] nv21, int width, int height, double rowY) {
        int y = Math.min(height - 1, Math.max(0, (int) Math.round(rowY * height)));
        int[] row = new int[width];
        int offset = y * width;
        for (int x = 0; x < width; x++) {
            row[x] = nv21[offset + x] & 0xff;
        }
        Arrays.sort(row);
        return row[width / 2];
    }

    private static double confidenceOf(double ridge) {
        return Math.min(1.0, Math.max(0.0,
                (ridge - MIN_RIDGE_RESPONSE) / FULL_RIDGE_RESPONSE));
    }

    private static Observation blend(Observation previous, Observation current, double alpha) {
        return new Observation(
                lerp(previous.centerOffset(), current.centerOffset(), alpha),
                lerp(previous.confidence(), current.confidence(), alpha),
                current.available(),
                lerp(previous.leftTopX(), current.leftTopX(), alpha),
                lerp(previous.rightTopX(), current.rightTopX(), alpha),
                lerp(previous.leftBottomX(), current.leftBottomX(), alpha),
                lerp(previous.rightBottomX(), current.rightBottomX(), alpha),
                current.widthSamples());
    }

    private static double lerp(double a, double b, double alpha) {
        return a + (b - a) * alpha;
    }

    private Observation unavailable() {
        last = null;
        return Observation.UNAVAILABLE;
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    private static int luma(byte[] nv21, int width, int x, int y) {
        return nv21[y * width + x] & 0xff;
    }
}
