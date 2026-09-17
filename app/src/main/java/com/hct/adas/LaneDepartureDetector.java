package com.hct.adas;

/**
 * Y-plane lane marking and departure extractor using bright ridge (2nd derivative) filtering.
 * ROIs are elevated above the vehicle hood line to ensure clear road pavement visibility.
 */
public final class LaneDepartureDetector {
    public static final double Y_TOP = 0.60;
    public static final double Y_BOTTOM = 0.78;

    public record Observation(double centerOffset, double confidence, boolean available,
                              double leftTopX, double rightTopX,
                              double leftBottomX, double rightBottomX) { }

    private record LineResult(double x, double confidence) {
        public static final LineResult NONE = new LineResult(Double.NaN, 0.0);
    }

    private Observation last;

    public void reset() {
        last = null;
    }

    public Observation detect(byte[] nv21, int width, int height) {
        if (nv21 == null || width <= 0 || height <= 0
                || nv21.length != (long) width * height * 3 / 2) {
            return unavailable();
        }

        // Elevated bands: Top band [0.54, 0.66], Bottom band [0.72, 0.84] - strictly above hood.
        LineResult lt = findBrightLine(nv21, width, height, 0.04, 0.50, 0.54, 0.66);
        LineResult rt = findBrightLine(nv21, width, height, 0.50, 0.96, 0.54, 0.66);
        LineResult lb = findBrightLine(nv21, width, height, 0.04, 0.48, 0.72, 0.84);
        LineResult rb = findBrightLine(nv21, width, height, 0.52, 0.98, 0.72, 0.84);

        if (!finite(lt.x()) || !finite(rt.x()) || !finite(lb.x()) || !finite(rb.x())) {
            return unavailable();
        }

        double widthTop = rt.x() - lt.x();
        double widthBottom = rb.x() - lb.x();

        // Perspective sanity check: bottom lane must be wider than top lane.
        if (widthBottom < 0.18 || widthTop < 0.08 || widthBottom <= widthTop + 0.02) {
            return unavailable();
        }

        double confLeft = (lt.confidence() + lb.confidence()) * 0.5;
        double confRight = (rt.confidence() + rb.confidence()) * 0.5;
        double confidence = (confLeft + confRight) * 0.5;

        boolean available = lt.confidence() >= 0.25 && lb.confidence() >= 0.25
                && rt.confidence() >= 0.25 && rb.confidence() >= 0.25
                && confidence >= 0.35;
        double centerOffset = (lb.x() + rb.x()) * 0.5 - 0.5;

        Observation current = new Observation(centerOffset, confidence, available,
                lt.x(), rt.x(), lb.x(), rb.x());

        if (last != null && last.available() && current.available()) {
            current = blend(last, current, 0.35);
        }
        last = current;
        return current;
    }

    private static LineResult findBrightLine(byte[] nv21, int width, int height,
                                            double minX, double maxX,
                                            double minY, double maxY) {
        double weightedX = 0.0;
        double totalWeight = 0.0;
        int yStart = (int) (height * minY);
        int yEnd = (int) (height * maxY);
        int delta = Math.max(3, (int) (width * 0.006)); // ~4-8 pixels span
        int stepY = Math.max(1, (yEnd - yStart) / 10);
        int validRows = 0;
        int totalRows = 0;

        for (int y = yStart; y <= yEnd; y += stepY) {
            totalRows++;
            int startX = Math.max(delta + 1, (int) (width * minX));
            int endX = Math.min(width - delta - 2, (int) (width * maxX));
            int bestX = -1;
            int bestRidge = 14; // Minimum ridge intensity threshold

            for (int x = startX; x <= endX; x += 2) {
                int center = luma(nv21, width, x, y);
                int left = luma(nv21, width, x - delta, y);
                int right = luma(nv21, width, x + delta, y);
                // Second derivative ridge response: positive on bright stripe between darker asphalt
                int ridge = (center * 2) - left - right;
                if (ridge > bestRidge) {
                    bestRidge = ridge;
                    bestX = x;
                }
            }

            if (bestX >= 0) {
                weightedX += (double) bestX / width * bestRidge;
                totalWeight += bestRidge;
                validRows++;
            }
        }

        if (totalWeight <= 0.0 || validRows < totalRows / 2) {
            return LineResult.NONE;
        }

        double avgRidge = totalWeight / validRows;
        double conf = Math.min(1.0, Math.max(0.0, (avgRidge - 10.0) / 60.0));
        return new LineResult(weightedX / totalWeight, conf);
    }

    private static Observation blend(Observation previous, Observation current, double alpha) {
        return new Observation(
                lerp(previous.centerOffset(), current.centerOffset(), alpha),
                lerp(previous.confidence(), current.confidence(), alpha),
                current.available(),
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
