package com.hct.adas;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Indoor simulation controller for testing all ADAS scenarios without a physical vehicle.
 * Generates synthetic frame sequences and sensor feeds for SITL (Software-in-the-Loop) verification.
 */
public final class AdasSimulator {
    public enum Scenario {
        NONE("停止模拟"),
        FCW_APPROACH("FCW 前向碰撞测试 (前车急刹/高速逼近)"),
        HMW_PROXIMITY("HMW 极近车距测试 (近距跟车/防碰提醒)"),
        LDW_DEPARTURE("LDW 车道偏离测试 (车辆压线偏离)"),
        LVSA_START("LVSA 前车起步测试 (红灯等车/前车驶离)"),
        AUTO_CALIBRATION("行车自标定收敛测试 (自动学习灭点)");

        private final String displayName;

        Scenario(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public record SimFrame(
            VehicleDetector.Result detections,
            LaneDepartureDetector.Observation lane,
            double speedKmh,
            String description,
            int stepIndex,
            int totalSteps) { }

    public interface Listener {
        void onFrame(SimFrame frame);
        void onFinished(Scenario scenario);
    }

    private static final long FRAME_INTERVAL_MS = 200L; // 5 FPS
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Scenario currentScenario = Scenario.NONE;
    private List<SimFrame> currentFrames = List.of();
    private int currentIndex = 0;
    private Listener currentListener;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentScenario == Scenario.NONE || currentListener == null) {
                return;
            }
            if (currentIndex < currentFrames.size()) {
                SimFrame frame = currentFrames.get(currentIndex);
                currentIndex++;
                long nowNanos = System.nanoTime();
                VehicleDetector.Result freshDetections = new VehicleDetector.Result(
                        nowNanos,
                        frame.detections().frameWidth(),
                        frame.detections().frameHeight(),
                        frame.detections().inferenceNanos(),
                        frame.detections().vehicles());
                SimFrame freshFrame = new SimFrame(freshDetections, frame.lane(), frame.speedKmh(),
                        frame.description(), frame.stepIndex(), frame.totalSteps());
                currentListener.onFrame(freshFrame);
                handler.postDelayed(this, FRAME_INTERVAL_MS);
            } else {
                Scenario finished = currentScenario;
                stop();
                if (currentListener != null) {
                    currentListener.onFinished(finished);
                }
            }
        }
    };

    public synchronized void start(Scenario scenario, int width, int height,
                                   CameraCalibration calibration, Listener listener) {
        stop();
        if (scenario == null || scenario == Scenario.NONE) {
            return;
        }
        currentScenario = scenario;
        currentListener = listener;
        currentIndex = 0;
        currentFrames = generateScenario(scenario, width, height, calibration);
        handler.post(tickRunnable);
    }

    public synchronized void stop() {
        handler.removeCallbacks(tickRunnable);
        currentScenario = Scenario.NONE;
        currentFrames = List.of();
        currentIndex = 0;
    }

    public synchronized boolean isRunning() {
        return currentScenario != Scenario.NONE;
    }

    public synchronized Scenario currentScenario() {
        return currentScenario;
    }

    public static List<SimFrame> generateScenario(Scenario scenario, int width, int height,
                                                  CameraCalibration calibration) {
        if (calibration == null || !calibration.isUsableFor(width, height)) {
            throw new IllegalArgumentException("Simulation calibration must match the frame size");
        }
        List<SimFrame> frames = new ArrayList<>();
        switch (scenario) {
            case FCW_APPROACH -> generateFcwApproach(frames, width, height, calibration);
            case HMW_PROXIMITY -> generateHmwProximity(frames, width, height, calibration);
            case LDW_DEPARTURE -> generateLdwDeparture(frames, width, height, calibration);
            case LVSA_START -> generateLvsaStart(frames, width, height, calibration);
            case AUTO_CALIBRATION -> generateAutoCalibration(frames, width, height, calibration);
            default -> { }
        }
        return frames;
    }

    private static float groundContactY(double distanceMeters, CameraCalibration calibration) {
        double rayAngle = Math.atan(calibration.cameraHeightMeters() / distanceMeters)
                - Math.toRadians(calibration.pitchDegrees());
        return (float) (calibration.principalPointYNormalized()
                + calibration.focalLengthYNormalized() * Math.tan(rayAngle));
    }

    /**
     * Lane observation with the width samples the calibration solver consumes, synthesised from the
     * same projection model so the simulation exercises the real pitch-solve path instead of the
     * legacy vanishing-point fallback.
     *
     * @param lateralOffset normalized lane-centre offset; positive means the vehicle sits left
     */
    private static LaneDepartureDetector.Observation syntheticLane(CameraCalibration calibration,
                                                                   int width, int height,
                                                                   double lateralOffset,
                                                                   double confidence) {
        return syntheticLane(calibration, width, height, lateralOffset, confidence,
                calibration.pitchDegrees());
    }

    private static LaneDepartureDetector.Observation syntheticLane(CameraCalibration calibration,
                                                                   int width, int height,
                                                                   double lateralOffset,
                                                                   double confidence,
                                                                   double truePitchDegrees) {
        double farRow = LaneDepartureDetector.ROI_TOP_ROW;
        double nearRow = LaneDepartureDetector.ROI_BOTTOM_ROW;
        List<LaneGeometry.WidthSample> samples = new ArrayList<>();
        double nearWidth = Double.NaN;
        for (int i = 0; i <= 8; i++) {
            double rowY = farRow + (nearRow - farRow) * i / 8.0;
            double laneWidth = LaneGeometry.laneWidthNormalized(calibration, rowY, truePitchDegrees,
                    LaneGeometry.DEFAULT_LANE_WIDTH_METERS, width, height);
            if (!Double.isFinite(laneWidth)) {
                continue;
            }
            // Keep the synthetic pair inside the frame at steep pitches.
            laneWidth = Math.min(laneWidth, 0.8);
            if (i == 8) {
                nearWidth = laneWidth;
            }
            double centerX = 0.5 + lateralOffset;
            samples.add(new LaneGeometry.WidthSample(rowY, centerX - laneWidth / 2.0,
                    centerX + laneWidth / 2.0, confidence));
        }
        if (!Double.isFinite(nearWidth)) {
            nearWidth = 0.40;
        }
        double center = 0.5 + lateralOffset;
        return new LaneDepartureDetector.Observation(lateralOffset, confidence, true,
                center - nearWidth * 0.45, center + nearWidth * 0.45,
                center - nearWidth / 2.0, center + nearWidth / 2.0, samples);
    }

    private static void generateFcwApproach(List<SimFrame> frames, int width, int height,
                                             CameraCalibration calibration) {
        int total = 25; // 5 seconds
        double speedKmh = 60.0;
        LaneDepartureDetector.Observation lane = syntheticLane(calibration, width, height,
                0.0, 0.85);

        for (int i = 0; i < total; i++) {
            long timeNanos = i * 200_000_000L;
            float bottomY;
            float boxWidth;
            String desc;
            if (i < 6) {
                bottomY = groundContactY(22.0, calibration);
                boxWidth = 0.08f;
                desc = "60 km/h 巡航中 · 前车距离 22 米";
            } else {
                float approachProgress = Math.min(1.0f, (float) (i - 5) / 15f);
                bottomY = groundContactY(22.0 - approachProgress * 18.0, calibration);
                boxWidth = 0.08f + approachProgress * 0.22f;
                desc = String.format("前车急刹/快速逼近! TTC 持续下降 (第 %d 帧)", i - 5);
            }

            float boxHeight = boxWidth * 0.85f;
            float topY = Math.max(0.38f, bottomY - boxHeight);
            float leftX = 0.50f - boxWidth * 0.5f;
            float rightX = 0.50f + boxWidth * 0.5f;

            VehicleDetector.Detection vehicle = new VehicleDetector.Detection(
                    "car", 0.92f, leftX, topY, rightX, bottomY);
            VehicleDetector.Result detections = new VehicleDetector.Result(
                    timeNanos, width, height, 45_000_000L, List.of(vehicle));

            frames.add(new SimFrame(detections, lane, speedKmh, desc, i + 1, total));
        }
    }

    private static void generateHmwProximity(List<SimFrame> frames, int width, int height,
                                              CameraCalibration calibration) {
        int total = 25; // 5 seconds
        double speedKmh = 25.0; // Low city speed
        LaneDepartureDetector.Observation lane = syntheticLane(calibration, width, height,
                0.0, 0.85);

        for (int i = 0; i < total; i++) {
            long timeNanos = i * 200_000_000L;
            float bottomY;
            float boxWidth;
            String desc;
            if (i < 8) {
                bottomY = groundContactY(7.0, calibration);
                boxWidth = 0.26f;
                desc = "25 km/h 低速跟车 · 距离 7.0 米 (HMW 黄色常态注意)";
            } else {
                float closeProgress = (float) (i - 8) / (total - 9);
                bottomY = groundContactY(7.0 - closeProgress * 3.5, calibration);
                boxWidth = 0.26f + closeProgress * 0.12f;
                desc = String.format("距离持续贴近至 3.5 米! 触发 HMW 二级极近提醒 (第 %d 帧)", i - 7);
            }

            float boxHeight = boxWidth * 0.85f;
            float topY = Math.max(0.42f, bottomY - boxHeight);
            float leftX = 0.50f - boxWidth * 0.5f;
            float rightX = 0.50f + boxWidth * 0.5f;

            VehicleDetector.Detection vehicle = new VehicleDetector.Detection(
                    "car", 0.94f, leftX, topY, rightX, bottomY);
            VehicleDetector.Result detections = new VehicleDetector.Result(
                    timeNanos, width, height, 40_000_000L, List.of(vehicle));

            frames.add(new SimFrame(detections, lane, speedKmh, desc, i + 1, total));
        }
    }

    private static void generateLdwDeparture(List<SimFrame> frames, int width, int height,
                                             CameraCalibration calibration) {
        int total = 25; // 5 seconds
        double speedKmh = 65.0;

        for (int i = 0; i < total; i++) {
            long timeNanos = i * 200_000_000L;
            double offset;
            String desc;
            if (i < 6) {
                offset = 0.0;
                desc = "65 km/h 车道中央巡航 (正常行驶)";
            } else {
                float drift = Math.min(1.0f, (float) (i - 5) / 10f);
                offset = -0.05 - drift * 0.14; // drifts to -0.19 (well over 0.12 threshold)
                desc = String.format("自车向左偏离压线! 偏离量 %.2f (LDW 偏离报警中)", offset);
            }

            LaneDepartureDetector.Observation lane = syntheticLane(calibration, width, height,
                    offset, 0.88);
            VehicleDetector.Result detections = new VehicleDetector.Result(
                    timeNanos, width, height, 38_000_000L, List.of());

            frames.add(new SimFrame(detections, lane, speedKmh, desc, i + 1, total));
        }
    }

    private static void generateLvsaStart(List<SimFrame> frames, int width, int height,
                                            CameraCalibration calibration) {
        int total = 35; // 7 seconds, including tracker confirmation before the stationary wait.
        int waitFrames = 20;
        double speedKmh = 0.0; // Stationary at traffic light
        LaneDepartureDetector.Observation lane = syntheticLane(calibration, width, height,
                0.0, 0.85);

        for (int i = 0; i < total; i++) {
            long timeNanos = i * 200_000_000L;
            float bottomY;
            float boxWidth;
            String desc;
            if (i < waitFrames) {
                // Allow the tracker to confirm, then observe at least 3 seconds of stable waiting.
                bottomY = groundContactY(5.5, calibration);
                boxWidth = 0.28f;
                desc = String.format("红绿灯自车静止 · 前车在 5.5 米处静止等待中 (%d/%d)", i + 1, waitFrames);
            } else {
                // Lead vehicle starts moving away!
                float moveProgress = (float) (i - waitFrames + 1) / (total - waitFrames);
                bottomY = groundContactY(5.5 + moveProgress * 5.5, calibration);
                boxWidth = 0.28f - moveProgress * 0.12f;
                desc = String.format("前车起步驶离! 距离拉开至 %.1f 米 (触发 LVSA 起步提醒)",
                        5.5 + moveProgress * 5.5);
            }

            float boxHeight = boxWidth * 0.85f;
            float topY = Math.max(0.40f, bottomY - boxHeight);
            float leftX = 0.50f - boxWidth * 0.5f;
            float rightX = 0.50f + boxWidth * 0.5f;

            VehicleDetector.Detection vehicle = new VehicleDetector.Detection(
                    "car", 0.95f, leftX, topY, rightX, bottomY);
            VehicleDetector.Result detections = new VehicleDetector.Result(
                    timeNanos, width, height, 42_000_000L, List.of(vehicle));

            frames.add(new SimFrame(detections, lane, speedKmh, desc, i + 1, total));
        }
    }

    private static void generateAutoCalibration(List<SimFrame> frames, int width, int height,
                                                CameraCalibration calibration) {
        int total = 65; // 13 seconds, enough for 60-sample convergence
        double speedKmh = 60.0;
        // The fixture pitch is deliberately not the mounted one: the samples describe a camera
        // looking 1.5 degrees steeper, so the simulation proves the pitch solve actually corrects
        // the configured value instead of echoing it back.
        double mountedPitch = calibration.pitchDegrees() + 1.5;
        LaneDepartureDetector.Observation lane = syntheticLane(calibration, width, height,
                0.0, 0.85, mountedPitch);

        for (int i = 0; i < total; i++) {
            long timeNanos = i * 200_000_000L;
            String desc = String.format("60 km/h 直行平稳行驶 · 车道宽度比值验证中 (%d/%d)",
                    Math.min(60, i + 1), 60);

            VehicleDetector.Result detections = new VehicleDetector.Result(
                    timeNanos, width, height, 35_000_000L, List.of());

            frames.add(new SimFrame(detections, lane, speedKmh, desc, i + 1, total));
        }
    }
}
