package com.hct.adas;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.widget.TextView;

import java.io.IOException;

/** Foreground USB capture and real vehicle detection; calibrated warnings are a later stage. */
public final class MainActivity extends Activity {
    private static final String TAG = "HctAdasDetector";
    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private FrameDispatcher frameDispatcher;
    private FrameConsumer frameConsumer;
    private UsbCameraSource cameraSource;
    private TextureView previewView;
    private VehicleOverlayView overlayView;
    private TextView statusView;
    private TextView metricsView;
    private boolean permissionAsked;
    private volatile boolean started;
    private volatile long acceptFramesAfterNanos;
    private volatile VehicleDetector.Result latestResult;
    private long previousCaptured;
    private long previousMetricsTime;
    private final Handler metricsHandler = new Handler(Looper.getMainLooper());
    private final Runnable metricsUpdater = new Runnable() {
        @Override
        public void run() {
            if (started) {
                renderMetrics();
                metricsHandler.postDelayed(this, 250L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusView = findViewById(R.id.status);
        metricsView = findViewById(R.id.metrics);
        previewView = findViewById(R.id.usb_preview);
        overlayView = findViewById(R.id.vehicle_overlay);
        statusView.setOnClickListener(view -> {
            if (started && checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            }
        });
        frameDispatcher = new FrameDispatcher(2);
        frameConsumer = new FrameConsumer(frameDispatcher, new FrameConsumer.Handler() {
            private VehicleDetector detector;
            private RuntimeException initializationFailure;

            @Override
            public void onFrame(FrameDispatcher.Frame frame) {
                if (!started || frame.timestampNanos() < acceptFramesAfterNanos) {
                    return;
                }
                if (initializationFailure != null) {
                    throw initializationFailure;
                }
                if (detector == null) {
                    try {
                        detector = new LiteRtVehicleDetector(getAssets());
                    } catch (IOException | RuntimeException | LinkageError failure) {
                        Log.e(TAG, "Vehicle model initialization failed", failure);
                        initializationFailure = new IllegalStateException(
                                "模型加载失败: " + failure.getMessage(), failure);
                        throw initializationFailure;
                    }
                }
                VehicleDetector.Result result = detector.detect(frame);
                if (started && result.timestampNanos() >= acceptFramesAfterNanos) {
                    latestResult = result;
                }
            }

            @Override
            public void onStopped() {
                if (detector != null) {
                    detector.close();
                    detector = null;
                }
                initializationFailure = null;
            }
        });
        cameraSource = new UsbCameraSource(this, previewView, frameDispatcher,
                new UsbCameraSource.Listener() {
                    public void onDeviceAttached(UsbDevice device) {
                        statusView.setText(R.string.camera_attached);
                    }

                    public void onDeviceConnectionChanged(UsbDevice device, boolean connected) {
                        statusView.setText(connected ? R.string.camera_opened
                                : R.string.camera_disconnected);
                    }

                    public void onDeviceDetached(UsbDevice device) {
                        clearResults();
                        statusView.setText(R.string.camera_disconnected);
                    }

                    public void onError(String message) {
                        clearResults();
                        statusView.setText(message);
                    }
                });
        previewView.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (latestResult != null) {
                fitPreview(latestResult);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        clearResults();
        statusView.setText(R.string.app_bootstrap_status);
        previousMetricsTime = System.nanoTime();
        previousCaptured = cameraSource.capturedFrames();
        frameConsumer.start();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraSource.start();
        } else {
            statusView.setText(R.string.camera_permission_required);
            if (!permissionAsked) {
                permissionAsked = true;
                requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            }
        }
        metricsHandler.post(metricsUpdater);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST && started) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                statusView.setText(R.string.app_bootstrap_status);
                cameraSource.start();
            } else {
                statusView.setText(R.string.camera_permission_required);
            }
        }
    }

    private void renderMetrics() {
        long now = System.nanoTime();
        long captured = cameraSource.capturedFrames();
        double fps = (captured - previousCaptured) * 1_000_000_000.0
                / Math.max(1L, now - previousMetricsTime);
        previousCaptured = captured;
        previousMetricsTime = now;
        FrameDispatcher.Metrics queue = frameDispatcher.metrics();
        FrameConsumer.Metrics worker = frameConsumer.metrics();
        String stream = getString(R.string.stream_metrics, fps, queue.offeredFrames(),
                worker.processedFrames(), queue.droppedFrames(),
                worker.failedFrames() + cameraSource.invalidFrames());
        VehicleDetector.Result result = latestResult;
        boolean fresh = result != null && result.timestampNanos() >= acceptFramesAfterNanos
                && now - result.timestampNanos() <= 1_500_000_000L;
        if (!worker.lastError().isEmpty()) {
            metricsView.setText(stream + "\n" + worker.lastError());
            overlayView.setResult(null);
        } else if (fresh) {
            fitPreview(result);
            overlayView.setResult(result);
            metricsView.setText(stream + "\n" + getString(R.string.detection_metrics,
                    result.vehicles().size(), result.inferenceNanos() / 1_000_000.0));
        } else {
            overlayView.setResult(null);
            if (result != null && result.timestampNanos() >= acceptFramesAfterNanos) {
                metricsView.setText(stream + "\n" + getString(R.string.detection_metrics,
                        result.vehicles().size(), result.inferenceNanos() / 1_000_000.0)
                        + "\n" + getString(R.string.stale_detection_result));
            } else {
                metricsView.setText(stream + "\n" + getString(R.string.waiting_for_frames));
            }
        }
    }

    private void fitPreview(VehicleDetector.Result result) {
        if (previewView.getWidth() == 0 || previewView.getHeight() == 0) {
            return;
        }
        float scale = Math.min((float) previewView.getWidth() / result.frameWidth(),
                (float) previewView.getHeight() / result.frameHeight());
        Matrix matrix = new Matrix();
        matrix.setScale(result.frameWidth() * scale / previewView.getWidth(),
                result.frameHeight() * scale / previewView.getHeight(),
                previewView.getWidth() / 2f, previewView.getHeight() / 2f);
        previewView.setTransform(matrix);
    }

    private void clearResults() {
        acceptFramesAfterNanos = System.nanoTime();
        latestResult = null;
        overlayView.setResult(null);
    }

    @Override
    protected void onStop() {
        started = false;
        metricsHandler.removeCallbacks(metricsUpdater);
        cameraSource.stop();
        frameConsumer.close();
        clearResults();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        metricsHandler.removeCallbacks(metricsUpdater);
        cameraSource.close();
        frameConsumer.close();
        frameDispatcher.close();
        super.onDestroy();
    }
}
