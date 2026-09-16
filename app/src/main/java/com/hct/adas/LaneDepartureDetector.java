package com.hct.adas;

/** Small Y-plane lane cue extractor; absence of both reliable sides is an explicit no-result. */
public final class LaneDepartureDetector {
    public record Observation(double centerOffset, double confidence, boolean available,
                              double leftTopX, double rightTopX,
                              double leftBottomX, double rightBottomX) { }

    private Observation last;

    public void reset() {
        last = null;
    }

    public Observation detect(byte[] nv21, int width, int height) {
        if (nv21 == null || width <= 0 || height <= 0
                || nv21.length != (long) width * height * 3 / 2) {
            return unavailable();
        }
        double leftTop = findBrightEdge(nv21, width, height, 0.08, 0.46, 0.58, 0.72);
        double rightTop = findBrightEdge(nv21, width, height, 0.54, 0.92, 0.58, 0.72);
        double leftBottom = findBrightEdge(nv21, width, height, 0.08, 0.46, 0.78, 0.94);
        double rightBottom = findBrightEdge(nv21, width, height, 0.54, 0.92, 0.78, 0.94);
        if (!finite(leftTop) || !finite(rightTop) || !finite(leftBottom)
                || !finite(rightBottom) || rightBottom - leftBottom < 0.20
                || rightTop - leftTop < 0.12) {
            return unavailable();
        }
        double confidence = Math.min(Math.min(edgeConfidence(nv21, width, height, leftTop, 0.65),
                edgeConfidence(nv21, width, height, rightTop, 0.65)),
                Math.min(edgeConfidence(nv21, width, height, leftBottom, 0.86),
                        edgeConfidence(nv21, width, height, rightBottom, 0.86)));
        Observation current = new Observation((leftBottom + rightBottom) * 0.5 - 0.5,
                confidence, confidence >= 0.35, leftTop, rightTop, leftBottom, rightBottom);
        if (last != null && last.available() && current.available()) {
            current = blend(last, current, 0.35);
        }
        last = current;
        return current;
    }

    private static double findBrightEdge(byte[] nv21, int width, int height,
                                         double minX, double maxX,
                                         double minY, double maxY) {
        double weightedX = 0.0;
        double weight = 0.0;
        int yStart = (int) (height * minY);
        int yEnd = (int) (height * maxY);
        for (int y = yStart; y <= yEnd; y += Math.max(1, height / 90)) {
            int start = Math.max(2, (int) (width * minX));
            int end = Math.min(width - 3, (int) (width * maxX));
            double bestGradient = 18.0;
            int bestX = -1;
            for (int x = start; x <= end; x += 2) {
                int gradient = luma(nv21, width, x + 1, y) - luma(nv21, width, x - 2, y);
                if (gradient > bestGradient) {
                    bestGradient = gradient;
                    bestX = x;
                }
            }
            if (bestX >= 0) {
                weightedX += (double) bestX / width * bestGradient;
                weight += bestGradient;
            }
        }
        return weight > 0.0 ? weightedX / weight : Double.NaN;
    }

    private static double edgeConfidence(byte[] nv21, int width, int height, double x, double yRatio) {
        int y = (int) (height * yRatio);
        int px = Math.max(2, Math.min(width - 3, (int) (x * width)));
        return Math.min(1.0, Math.max(0.0,
                (luma(nv21, width, px + 1, y) - luma(nv21, width, px - 2, y)) / 80.0));
    }

    private static Observation blend(Observation previous, Observation current, double alpha) {
        return new Observation(
                lerp(previous.centerOffset(), current.centerOffset(), alpha),
                lerp(previous.confidence(), current.confidence(), alpha), true,
                lerp(previous.leftTopX(), current.leftTopX(), alpha),
                lerp(previous.rightTopX(), current.rightTopX(), alpha),
                lerp(previous.leftBottomX(), current.leftBottomX(), alpha),
                lerp(previous.rightBottomX(), current.rightBottomX(), alpha));
    }

    private static double lerp(double a, double b, double alpha) {
        return a + (b - a) * alpha;
    }

    private Observation unavailable() {
        last = null;
        return new Observation(0.0, 0.0, false, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    private static int luma(byte[] nv21, int width, int x, int y) {
        return nv21[y * width + x] & 0xff;
    }
}
