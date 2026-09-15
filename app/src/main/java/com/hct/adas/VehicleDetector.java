package com.hct.adas;

import java.util.List;

/** Only the frame consumer thread may call detect or close. Coordinates are normalized. */
public interface VehicleDetector extends AutoCloseable {
    record Detection(String label, float confidence, float left, float top,
                     float right, float bottom) {
    }

    record Result(long timestampNanos, int frameWidth, int frameHeight,
                  long inferenceNanos, List<Detection> vehicles) {
        public Result {
            vehicles = List.copyOf(vehicles);
        }
    }

    Result detect(FrameDispatcher.Frame frame);

    @Override
    void close();
}
