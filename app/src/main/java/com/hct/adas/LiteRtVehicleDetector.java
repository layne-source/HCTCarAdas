package com.hct.adas;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bundled EfficientDet-Lite0 UINT8 model; postprocessing/NMS is part of the model. */
public final class LiteRtVehicleDetector implements VehicleDetector {
    private static final int INPUT_SIZE = 320;
    private static final float MIN_CONFIDENCE = 0.5f;
    private final ByteBuffer model;
    private final Interpreter interpreter;
    private final ByteBuffer rgb = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3)
            .order(ByteOrder.nativeOrder());
    private final Object[] inputs = {rgb};
    private final List<String> labels = new ArrayList<>();
    private final Map<Integer, Object> outputs = new HashMap<>();
    private final float[][][] boxes;
    private final float[][] classes;
    private final float[][] scores;
    private final float[] count = new float[1];

    public LiteRtVehicleDetector(AssetManager assets) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open("models/labels.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                labels.add(line.trim());
            }
        }
        try (AssetFileDescriptor descriptor = assets.openFd("models/efficientdet_lite0_int8.tflite");
             FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor())) {
            model = stream.getChannel().map(FileChannel.MapMode.READ_ONLY,
                    descriptor.getStartOffset(), descriptor.getDeclaredLength());
        }
        interpreter = new Interpreter(model, new Interpreter.Options().setNumThreads(1));
        try {
            interpreter.allocateTensors();
            if (interpreter.getInputTensorCount() != 1
                    || interpreter.getInputTensor(0).dataType() != DataType.UINT8
                    || !Arrays.equals(interpreter.getInputTensor(0).shape(),
                    new int[] {1, INPUT_SIZE, INPUT_SIZE, 3})
                    || interpreter.getOutputTensorCount() != 4) {
                throw new IllegalStateException("Unexpected EfficientDet model input/output contract");
            }
            int[] shape = interpreter.getOutputTensor(0).shape();
            if (shape.length != 3 || shape[0] != 1 || shape[2] != 4
                    || shape[1] < 1 || shape[1] > 1000) {
                throw new IllegalStateException("Unexpected detection box shape");
            }
            int capacity = shape[1];
            for (int i = 0; i < 4; i++) {
                if (interpreter.getOutputTensor(i).dataType() != DataType.FLOAT32) {
                    throw new IllegalStateException("Unexpected output tensor type");
                }
            }
            if (!Arrays.equals(interpreter.getOutputTensor(1).shape(), new int[] {1, capacity})
                    || !Arrays.equals(interpreter.getOutputTensor(2).shape(), new int[] {1, capacity})
                    || !Arrays.equals(interpreter.getOutputTensor(3).shape(), new int[] {1})) {
                throw new IllegalStateException("Unexpected detection output shapes");
            }
            boxes = new float[1][capacity][4];
            classes = new float[1][capacity];
            scores = new float[1][capacity];
            // Verified against TFLite_Detection_PostProcess outputs in the bundled model.
            outputs.put(0, boxes);
            outputs.put(1, classes);
            outputs.put(2, scores);
            outputs.put(3, count);
        } catch (RuntimeException failure) {
            interpreter.close();
            throw failure;
        }
    }

    @Override
    public Result detect(FrameDispatcher.Frame frame) {
        long started = System.nanoTime();
        Nv21Preprocessor.Transform transform = Nv21Preprocessor.fill(
                frame.nv21(), frame.width(), frame.height(), INPUT_SIZE, rgb);
        interpreter.runForMultipleInputsOutputs(inputs, outputs);
        List<Detection> vehicles = new ArrayList<>();
        int resultCount = Float.isFinite(count[0])
                ? Math.max(0, Math.min(boxes[0].length, (int) count[0])) : 0;
        for (int i = 0; i < resultCount; i++) {
            float score = scores[0][i];
            float category = classes[0][i];
            if (!Float.isFinite(score) || score < MIN_CONFIDENCE || score > 1f
                    || !Float.isFinite(category) || category != (int) category) {
                continue;
            }
            int index = (int) category;
            if (index < 0 || index >= labels.size()) {
                continue;
            }
            String label = labels.get(index);
            if (!label.equals("car") && !label.equals("bus") && !label.equals("truck")) {
                continue;
            }
            float[] box = boxes[0][i]; // ymin, xmin, ymax, xmax
            if (!Float.isFinite(box[0]) || !Float.isFinite(box[1])
                    || !Float.isFinite(box[2]) || !Float.isFinite(box[3])) {
                continue;
            }
            float left = transform.sourceX(box[1]);
            float top = transform.sourceY(box[0]);
            float right = transform.sourceX(box[3]);
            float bottom = transform.sourceY(box[2]);
            if (right > left && bottom > top) {
                vehicles.add(new Detection(label, score, left, top, right, bottom));
            }
        }
        return new Result(frame.timestampNanos(), frame.width(), frame.height(),
                System.nanoTime() - started, vehicles);
    }

    @Override
    public void close() {
        interpreter.close();
    }
}
