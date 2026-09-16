package com.hct.adas;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.hardware.usb.UsbDevice;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.text.InputType;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

/** Foreground vehicle detection/tracking; calibrated warnings are a later stage. */
public final class MainActivity extends Activity {
    private static final String TAG = "HctAdasDetector";
    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final int LOCATION_PERMISSION_REQUEST = 11;
    private FrameDispatcher frameDispatcher;
    private FrameConsumer frameConsumer;
    private UsbCameraSource cameraSource;
    private TextureView previewView;
    private VehicleOverlayView overlayView;
    private TextView statusView;
    private TextView metricsView;
    private TextView calibrationView;
    private CalibrationStore calibrationStore;
    private volatile CameraCalibration calibration;
    private AlertAudio alertAudio;
    private LocationManager locationManager;
    private volatile double egoSpeedKmh = Double.NaN;
    private volatile long speedTimestampNanos;
    private boolean permissionAsked;
    private boolean locationPermissionAsked;
    private volatile boolean started;
    private volatile long acceptFramesAfterNanos;
    private record Analysis(VehicleDetector.Result detections, LeadVehicleTracker.Snapshot tracking,
                            LeadVehicleMotionEstimator.Measurement motion,
                            LaneDepartureDetector.Observation lane,
                            AdasDecisionEngine.Decision decision) { }

    private volatile Analysis latestAnalysis;
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
        calibrationView = findViewById(R.id.calibration);
        previewView = findViewById(R.id.usb_preview);
        overlayView = findViewById(R.id.vehicle_overlay);
        calibrationStore = new CalibrationStore(this);
        calibration = calibrationStore.load();
        overlayView.setCalibration(calibration);
        calibrationView.setOnClickListener(view -> showCalibrationDialog());
        locationManager = getSystemService(LocationManager.class);
        try {
            alertAudio = new AlertAudio(this);
        } catch (IOException | RuntimeException failure) {
            Log.w(TAG, "Alert audio unavailable", failure);
        }
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
            private final LeadVehicleTracker tracker = new LeadVehicleTracker();
            private final LeadVehicleMotionEstimator motionEstimator = new LeadVehicleMotionEstimator();
            private final LaneDepartureDetector laneDetector = new LaneDepartureDetector();
            private final AdasDecisionEngine decisionEngine = new AdasDecisionEngine();
            private long trackingSessionStart;

            @Override
            public void onFrame(FrameDispatcher.Frame frame) {
                long sessionStart = acceptFramesAfterNanos;
                if (!started || frame.timestampNanos() < sessionStart) {
                    return;
                }
                if (trackingSessionStart != sessionStart) {
                    tracker.reset();
                    motionEstimator.reset();
                    decisionEngine.reset();
                    trackingSessionStart = sessionStart;
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
                if (started && sessionStart == acceptFramesAfterNanos) {
                    if (System.nanoTime() - result.timestampNanos()
                            > LeadVehicleTracker.MAX_OBSERVATION_GAP_NANOS) {
                        tracker.reset();
                        latestAnalysis = null;
                        return;
                    }
                    LeadVehicleTracker.Snapshot tracking = tracker.update(result);
                    LeadVehicleMotionEstimator.Measurement motion = motionEstimator.update(
                            tracking, calibration, result.frameWidth(), result.frameHeight());
                    LaneDepartureDetector.Observation lane = laneDetector.detect(
                            frame.nv21(), frame.width(), frame.height());
                    double speed = validSpeedKmh() ? egoSpeedKmh : Double.NaN;
                    AdasDecisionEngine.Observation observation = new AdasDecisionEngine.Observation(
                            result.timestampNanos() / 1_000_000L, speed,
                            motion.distanceMeters(), motion.closingSpeedMps(),
                            motion.targetAreaPixels(), motion.visible());
                    AdasDecisionEngine.Decision decision = decisionEngine.update(observation,
                            new AdasDecisionEngine.LaneObservation(lane.centerOffset(),
                                    lane.confidence(), lane.available()));
                    if (started && sessionStart == acceptFramesAfterNanos && alertAudio != null) {
                        alertAudio.play(decision.events());
                    }
                    if (started && sessionStart == acceptFramesAfterNanos) {
                        // One publication keeps target ID and boxes from the same frame together.
                        latestAnalysis = new Analysis(result, tracking, motion, lane, decision);
                    }
                }
            }

            @Override
            public void onStopped() {
                try {
                    if (detector != null) {
                        detector.close();
                    }
                } finally {
                    detector = null;
                    initializationFailure = null;
                    tracker.reset();
                }
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
            Analysis analysis = latestAnalysis;
            if (analysis != null) {
                fitPreview(analysis.detections());
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
            ensureLocationPermission();
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
                ensureLocationPermission();
            } else {
                statusView.setText(R.string.camera_permission_required);
            }
        } else if (requestCode == LOCATION_PERMISSION_REQUEST && started) {
            startLocationUpdates();
        }
    }

    private void ensureLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        } else if (!locationPermissionAsked) {
            locationPermissionAsked = true;
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
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
        Analysis analysis = latestAnalysis;
        VehicleDetector.Result result = analysis == null ? null : analysis.detections();
        updateCalibrationStatus(result);
        boolean fresh = result != null && result.timestampNanos() >= acceptFramesAfterNanos
                && now - result.timestampNanos() <= LeadVehicleTracker.MAX_OBSERVATION_GAP_NANOS;
        if (!worker.lastError().isEmpty()) {
            metricsView.setText(stream + "\n" + worker.lastError());
            overlayView.setResult(null, null);
        } else if (fresh) {
            fitPreview(result);
            overlayView.setResult(result, analysis.tracking());
            metricsView.setText(stream + "\n" + getString(R.string.detection_metrics,
                    result.vehicles().size(), result.inferenceNanos() / 1_000_000.0)
                    + "\n" + trackingStatus(analysis.tracking())
                    + "\n" + measurementStatus(analysis));
        } else {
            overlayView.setResult(null, null);
            if (result != null && result.timestampNanos() >= acceptFramesAfterNanos) {
                metricsView.setText(stream + "\n" + getString(R.string.detection_metrics,
                        result.vehicles().size(), result.inferenceNanos() / 1_000_000.0)
                        + "\n" + getString(R.string.stale_detection_result));
            } else {
                metricsView.setText(stream + "\n" + getString(R.string.waiting_for_frames));
            }
        }
    }

    private String trackingStatus(LeadVehicleTracker.Snapshot tracking) {
        return switch (tracking.state()) {
            case NONE -> getString(R.string.tracking_none);
            case CANDIDATE -> getString(R.string.tracking_candidate);
            case TRACKING -> getString(R.string.tracking_active, tracking.trackId());
            case LOST -> getString(R.string.tracking_lost, tracking.trackId());
        };
    }

    private String measurementStatus(Analysis analysis) {
        LeadVehicleMotionEstimator.Measurement motion = analysis.motion();
        if (!motion.visible()) {
            return getString(R.string.measurement_unavailable);
        }
        double ttc = motion.closingSpeedMps() > 0.0
                ? motion.distanceMeters() / motion.closingSpeedMps() : Double.NaN;
        String distance = String.format(Locale.ROOT, "%.1f m", motion.distanceMeters());
        String ttcText = Double.isFinite(ttc)
                ? String.format(Locale.ROOT, "%.1f s", ttc) : "--";
        String warning = analysis.decision().events().isEmpty()
                ? (analysis.decision().headwayWarning() ? "HMW 条件" : "无预警")
                : "事件: " + analysis.decision().events();
        String lane = analysis.decision().laneWarning() ? " · LDW 条件" : "";
        return getString(R.string.measurement_status, distance, ttcText, warning + lane);
    }

    private boolean validSpeedKmh() {
        return Double.isFinite(egoSpeedKmh) && speedTimestampNanos > 0L
                && System.nanoTime() - speedTimestampNanos <= 2_000_000_000L;
    }

    private void startLocationUpdates() {
        egoSpeedKmh = Double.NaN;
        speedTimestampNanos = 0L;
        if (locationManager == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f,
                    locationListener, Looper.getMainLooper());
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) {
                locationListener.onLocationChanged(last);
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "GPS speed unavailable", failure);
            egoSpeedKmh = Double.NaN;
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
                // Permission can be revoked while the activity is stopping.
            }
        }
        egoSpeedKmh = Double.NaN;
        speedTimestampNanos = 0L;
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location != null && location.hasSpeed() && Float.isFinite(location.getSpeed())) {
                egoSpeedKmh = Math.max(0.0, location.getSpeed() * 3.6);
                speedTimestampNanos = System.nanoTime();
            }
        }
    };

    private void updateCalibrationStatus(VehicleDetector.Result result) {
        if (calibration == null) {
            calibrationView.setText(R.string.calibration_unset);
        } else if (result != null && !calibration.isUsableFor(result.frameWidth(), result.frameHeight())) {
            calibrationView.setText(getString(R.string.calibration_size_mismatch,
                    calibration.imageWidth(), calibration.imageHeight()));
        } else {
            calibrationView.setText(getString(R.string.calibration_loaded,
                    calibration.imageWidth(), calibration.imageHeight()));
        }
    }

    private void showCalibrationDialog() {
        int width = latestAnalysis == null ? 1280 : latestAnalysis.detections().frameWidth();
        int height = latestAnalysis == null ? 720 : latestAnalysis.detections().frameHeight();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, 0, padding, 0);
        EditText cameraHeight = numberField("相机离地高度（米）");
        EditText focalY = numberField("竖直焦距 fy（像素）");
        EditText principalY = numberField("主点 cy（像素）");
        EditText pitch = numberField("向下俯仰角（度）");
        form.addView(cameraHeight);
        form.addView(focalY);
        form.addView(principalY);
        form.addView(pitch);
        if (calibration != null && calibration.isUsableFor(width, height)) {
            cameraHeight.setText(Double.toString(calibration.cameraHeightMeters()));
            focalY.setText(Double.toString(calibration.focalLengthYNormalized() * height));
            principalY.setText(Double.toString(calibration.principalPointYNormalized() * height));
            pitch.setText(Double.toString(calibration.pitchDegrees()));
        }
        new AlertDialog.Builder(this)
                .setTitle("摄像头标定（" + width + "×" + height + "）")
                .setMessage("请使用实测参数；保存后仅显示标定辅助线，尚未启用测距报警。")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        CameraCalibration next = new CameraCalibration(width, height,
                                parse(cameraHeight), parse(focalY) / height,
                                parse(principalY) / height, parse(pitch));
                        calibrationStore.save(next);
                        calibration = next;
                        overlayView.setCalibration(next);
                        updateCalibrationStatus(latestAnalysis == null
                                ? null : latestAnalysis.detections());
                    } catch (RuntimeException failure) {
                        Toast.makeText(this, "标定参数无效: " + failure.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private EditText numberField(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return field;
    }

    private static double parse(EditText field) {
        String value = field.getText().toString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("参数不能为空");
        }
        return Double.parseDouble(value);
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
        latestAnalysis = null;
        overlayView.setResult(null, null);
    }

    @Override
    protected void onStop() {
        started = false;
        metricsHandler.removeCallbacks(metricsUpdater);
        stopLocationUpdates();
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
        if (alertAudio != null) {
            alertAudio.close();
            alertAudio = null;
        }
        super.onDestroy();
    }
}
