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

    @Test
    public void missingRowsAreNotReplacedByFittedSamples() {
        byte[] road = createSyntheticRoad(WIDTH, HEIGHT, 0.0);
        int row = (int) Math.round(0.68 * HEIGHT);
        Arrays.fill(road, row * WIDTH, (row + 1) * WIDTH, (byte) 70);
        LaneDepartureDetector.Observation observation = new LaneDepartureDetector().detect(
                road, WIDTH, HEIGHT);
        assertTrue(observation.available());
        assertEquals(8, observation.widthSamples().size());
        for (LaneGeometry.WidthSample sample : observation.widthSamples()) {
            assertTrue(Math.abs(sample.rowY() - row / (double) HEIGHT) > 1.0e-9);
        }
    }

    @Test
    public void brighterRightBoundaryDoesNotReplaceLeftBoundary() {
        byte[] road = createSyntheticRoad(WIDTH, HEIGHT, 0.0);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH / 2; x++) {
                if ((road[y * WIDTH + x] & 0xff) == 220) {
                    road[y * WIDTH + x] = (byte) 160;
                }
            }
        }
        LaneDepartureDetector.Observation observation = new LaneDepartureDetector().detect(
                road, WIDTH, HEIGHT);
        assertTrue(observation.available());
        assertTrue(observation.leftBottomX() < 0.3);
        assertTrue(observation.rightBottomX() > 0.7);
        assertEquals(9, observation.widthSamples().size());
    }

    @Test
    public void brighterSameSideLineDoesNotReplaceTrackedBoundary() {
        LaneDepartureDetector detector = new LaneDepartureDetector();
        LaneDepartureDetector.Observation first = detector.detect(
                createSyntheticRoad(WIDTH, HEIGHT, 0.0), WIDTH, HEIGHT);
        assertTrue(first.available());

        byte[] next = createSyntheticRoad(WIDTH, HEIGHT, 0.0);
        dimLeftLane(next);
        for (int y = (int) (HEIGHT * 0.50); y <= (int) (HEIGHT * 0.85); y++) {
            drawStripe(next, WIDTH, HEIGHT, (int) (WIDTH * 0.10), y, 6, 240);
        }

        LaneDepartureDetector.Observation tracked = detector.detect(next, WIDTH, HEIGHT);
        assertTrue(tracked.available());
        assertTrue("same-side distractor must not replace the tracked left boundary",
                tracked.leftTopX() > 0.30);
    }

    @Test
    public void oneMissingEndpointStillPublishesActualWidthSamples() {
        LaneDepartureDetector detector = new LaneDepartureDetector();
        assertTrue(detector.detect(createSyntheticRoad(WIDTH, HEIGHT, 0.0), WIDTH, HEIGHT)
                .available());

        byte[] next = createSyntheticRoad(WIDTH, HEIGHT, 0.0);
        int firstSampleRow = (int) Math.round(LaneDepartureDetector.ROI_TOP_ROW * HEIGHT);
        Arrays.fill(next, firstSampleRow * WIDTH, (firstSampleRow + 1) * WIDTH, (byte) 70);
        LaneDepartureDetector.Observation observation = detector.detect(next, WIDTH, HEIGHT);

        assertTrue(observation.available());
        assertEquals(8, observation.widthSamples().size());
        assertTrue(observation.widthSamples().get(0).rowY()
                > LaneDepartureDetector.ROI_TOP_ROW);
    }

    @Test
    public void coldStartMissingFirstRowKeepsSearchingUntilBoundaryIsFound() {
        byte[] road = createSyntheticRoad(WIDTH, HEIGHT, 0.0);
        int firstSampleRow = (int) Math.round(LaneDepartureDetector.ROI_TOP_ROW * HEIGHT);
        Arrays.fill(road, firstSampleRow * WIDTH, (firstSampleRow + 1) * WIDTH, (byte) 70);

        LaneDepartureDetector.Observation observation = new LaneDepartureDetector().detect(
                road, WIDTH, HEIGHT);

        assertTrue("A missing first row must not prevent cold-start tracking", observation.available());
        assertTrue(observation.widthSamples().size() >= 7);
    }

    @Test
    public void threeDistantSightingsDoNotPublishSparseGeometry() {
        LaneDepartureDetector detector = new LaneDepartureDetector();
        byte[] road = createSyntheticRoad(WIDTH, HEIGHT, 0.0);
        for (int i = 2; i < 8; i++) {
            double rowY = LaneDepartureDetector.ROI_TOP_ROW
                    + (LaneDepartureDetector.ROI_BOTTOM_ROW - LaneDepartureDetector.ROI_TOP_ROW)
                    * i / 8.0;
            int row = (int) Math.round(rowY * HEIGHT);
            Arrays.fill(road, row * WIDTH, (row + 1) * WIDTH, (byte) 70);
        }
        LaneDepartureDetector.Observation observation = detector.detect(road, WIDTH, HEIGHT);
        assertTrue(observation.available());
        assertTrue(observation.widthSamples().isEmpty());
    }

    @Test
    public void widthSamplesRetainCurvedBoundarySightings() {
        byte[] road = createSyntheticRoad(WIDTH, HEIGHT, 0.0);
        for (int y = (int) (0.5 * HEIGHT); y <= (int) (0.85 * HEIGHT); y++) {
            double t = (y / (double) HEIGHT - 0.54) / 0.28;
            int shift = (int) Math.round(0.04 * t * t * WIDTH);
            byte[] row = Arrays.copyOfRange(road, y * WIDTH, (y + 1) * WIDTH);
            Arrays.fill(road, y * WIDTH, (y + 1) * WIDTH, (byte) 70);
            for (int x = 0; x + shift < WIDTH; x++) {
                road[y * WIDTH + x + shift] = row[x];
            }
        }
        LaneDepartureDetector.Observation observation = new LaneDepartureDetector().detect(
                road, WIDTH, HEIGHT);
        assertEquals(9, observation.widthSamples().size());
        var first = observation.widthSamples().get(0);
        var middle = observation.widthSamples().get(4);
        var last = observation.widthSamples().get(8);
        double firstCenter = (first.leftX() + first.rightX()) / 2.0;
        double middleCenter = (middle.leftX() + middle.rightX()) / 2.0;
        double lastCenter = (last.leftX() + last.rightX()) / 2.0;
        double fraction = (middle.rowY() - first.rowY()) / (last.rowY() - first.rowY());
        assertTrue(Math.abs(middleCenter - (firstCenter + fraction * (lastCenter - firstCenter)))
                > 0.004);
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
        drawStripe(nv21, width, height, centerX, y, stripeWidth, 220);
    }

    private static void drawStripe(byte[] nv21, int width, int height, int centerX, int y,
                                   int stripeWidth, int luma) {
        int half = stripeWidth / 2;
        for (int x = centerX - half; x <= centerX + half; x++) {
            if (x >= 0 && x < width && y >= 0 && y < height) {
                nv21[y * width + x] = (byte) luma;
            }
        }
    }

    private static void dimLeftLane(byte[] nv21) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH / 2; x++) {
                if ((nv21[y * WIDTH + x] & 0xff) == 220) {
                    nv21[y * WIDTH + x] = (byte) 160;
                }
            }
        }
    }
}
