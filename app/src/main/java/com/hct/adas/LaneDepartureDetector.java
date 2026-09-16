package com.hct.adas;

/** Small Y-plane lane cue extractor; absence of both reliable sides is an explicit no-result. */
public final class LaneDepartureDetector {
    public record Observation(double centerOffset, double confidence, boolean available) { }

    public Observation detect(byte[] nv21, int width, int height) {
        if (nv21 == null || width <= 0 || height <= 0
                || nv21.length != (long) width * height * 3 / 2) {
            return new Observation(0.0, 0.0, false);
        }
        double leftX = findBrightEdge(nv21, width, height, 0.08, 0.46);
        double rightX = findBrightEdge(nv21, width, height, 0.54, 0.92);
        if (Double.isNaN(leftX) || Double.isNaN(rightX) || rightX - leftX < 0.20) {
            return new Observation(0.0, 0.0, false);
        }
        double center = (leftX + rightX) * 0.5;
        double confidence = Math.min(edgeConfidence(nv21, width, height, leftX),
                edgeConfidence(nv21, width, height, rightX));
        return new Observation(center - 0.5, confidence, confidence >= 0.35);
    }

    private static double findBrightEdge(byte[] nv21, int width, int height,
                                         double minX, double maxX) {
        double weightedX = 0.0;
        double weight = 0.0;
        int yStart = (int) (height * 0.58);
        int yEnd = (int) (height * 0.94);
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

    private static double edgeConfidence(byte[] nv21, int width, int height, double x) {
        int y = (int) (height * 0.78);
        int px = Math.max(2, Math.min(width - 3, (int) (x * width)));
        return Math.min(1.0, Math.max(0.0,
                (luma(nv21, width, px + 1, y) - luma(nv21, width, px - 2, y)) / 80.0));
    }

    private static int luma(byte[] nv21, int width, int x, int y) {
        return nv21[y * width + x] & 0xff;
    }
}
