package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class LaneDepartureDetectorTest {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;

    @Test
    public void unavailableForNullOrMalformedNv21() {
        LaneDepartureDetector detector = new LaneDepartureDetector();
        assertFalse(detector.detect(null, WIDTH, HEIGHT).available());
        assertFalse(detector.detect(new byte[10], WIDTH, HEIGHT).available());
        assertFalse(detector.detect(new byte[WIDTH * HEIGHT * 3 / 2], 0, HEIGHT).available());
    }

    @Test
    public void detectsStraightLanesOnSynthesizedNv21() {
        LaneDepartureDetector detector = new LaneDepartureDetector();
        byte[] nv21 = createSyntheticRoad(WIDTH, HEIGHT, 0.0);

        LaneDepartureDetector.Observation observation = detector.detect(nv21, WIDTH, HEIGHT);
        assertTrue("Straight lane lines must be detected and available", observation.available());
        assertTrue("Confidence must exceed detection threshold", observation.confidence() >= 0.25);
        assertEquals("Vehicle should be roughly centered in straight lane",
                0.0, observation.centerOffset(), 0.06);
        assertTrue("Bottom lane must be wider than top lane",
                observation.rightBottomX() - observation.leftBottomX()
                        > observation.rightTopX() - observation.leftTopX());
    }

    @Test
    public void detectsLeftDriftingDeparture() {
        LaneDepartureDetector detector = new LaneDepartureDetector();
        // Offset +0.11 simulates vehicle drifting left by -0.11
        byte[] nv21 = createSyntheticRoad(WIDTH, HEIGHT, 0.11);
        LaneDepartureDetector.Observation observation = detector.detect(nv21, WIDTH, HEIGHT);
        assertTrue("Drifting lane lines must be detected and available", observation.available());
        assertTrue("Center offset must reflect departure (|offset| > 0.10)",
                Math.abs(observation.centerOffset()) > 0.10);
    }

    private static byte[] createSyntheticRoad(int width, int height, double lateralShift) {
        int ySize = width * height;
        byte[] nv21 = new byte[ySize * 3 / 2];
        // Fill asphalt pavement with dark gray (luma = 70)
        Arrays.fill(nv21, 0, ySize, (byte) 70);
        // Fill UV chroma with neutral 128
        Arrays.fill(nv21, ySize, nv21.length, (byte) 128);

        // Draw left and right lane stripes with bright white luma (220)
        int yStart = (int) (height * 0.50);
        int yEnd = (int) (height * 0.85);

        for (int y = yStart; y <= yEnd; y++) {
            double normY = (double) y / height;
            double shift = lateralShift * Math.max(0.0, (normY - 0.48) / (0.78 - 0.48));
            double depth = (normY - 0.60) / (0.78 - 0.60);
            double leftNormX = 0.38 - depth * 0.18 + shift;
            double rightNormX = 0.62 + depth * 0.18 + shift;
            int leftPx = (int) (leftNormX * width);
            int rightPx = (int) (rightNormX * width);

            drawStripe(nv21, width, height, leftPx, y, 6);
            drawStripe(nv21, width, height, rightPx, y, 6);
        }
        return nv21;
    }

    private static void drawStripe(byte[] nv21, int width, int height, int centerX, int y, int stripeWidth) {
        int half = stripeWidth / 2;
        for (int x = centerX - half; x <= centerX + half; x++) {
            if (x >= 0 && x < width && y >= 0 && y < height) {
                nv21[y * width + x] = (byte) 220; // Bright white stripe
            }
        }
    }
}
