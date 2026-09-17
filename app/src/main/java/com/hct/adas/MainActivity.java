package com.hct.adas;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.hardware.usb.UsbDevice;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/** Foreground vehicle detection/tracking; calibrated warnings are a later stage. */
public final class MainActivity extends Activity {
    private static final String TAG = "HctAdasCore";
    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final int LOCATION_PERMISSION_REQUEST = 11;
    /** Fallback installation pitch when the bracket angle is unknown; self-learning refines it. */
    private static final double INITIAL_PITCH_DEGREES = 4.0;
    /**
     * Bounds accepted by the wizard. They mirror the learner's pitch acceptance range: a value
     * outside it could never be reached by self-learning either, so accepting it would only store
     * a calibration the pipeline refuses to use.
     */
    private static final double MIN_INITIAL_PITCH_DEGREES = -5.0;
    private static final double MAX_INITIAL_PITCH_DEGREES = 20.0;
    /**
     * Pitch at which the default-intrinsics vanishing point window (12.7 deg) is approached, so the
     * wizard warns that the mounting angle may be too steep for self-learning to converge.
     */
    private static final double STEEP_PITCH_WARNING_DEGREES = 12.0;
    private FrameDispatcher frameDispatcher;
    private FrameConsumer frameConsumer;
    private UsbCameraSource cameraSource;
    private TextureView previewView;
    private VehicleOverlayView overlayView;
    private TextView statusView;
    private CharSequence cameraStatusText;
    private TextView metricsView;
    private TextView calibrationView;
    private CalibrationStore calibrationStore;
    private volatile CameraCalibration calibration;
    private final AutoCalibrationLearner autoCalibrationLearner = new AutoCalibrationLearner();
    private volatile CalibrationStore.Status calibrationStatus = CalibrationStore.Status.UNCONFIGURED;
    private volatile int calibrationProgress = 0;
    private TextView simulationButton;
    private final AdasSimulator simulator = new AdasSimulator();
    private SimulationSnapshot simulationSnapshot;
    private final LeadVehicleTracker tracker = new LeadVehicleTracker();
    private final LeadVehicleMotionEstimator motionEstimator = new LeadVehicleMotionEstimator();
    private final LaneDepartureDetector laneDetector = new LaneDepartureDetector();
    private final AdasDecisionEngine decisionEngine = new AdasDecisionEngine();
    private AlertAudio alertAudio;
    private LocationManager locationManager;
    private volatile double egoSpeedKmh = Double.NaN;
    private volatile long speedTimestampNanos;
    private boolean permissionAsked;
    private boolean locationPermissionAsked;
    private volatile boolean started;
    private volatile long acceptFramesAfterNanos;
    private volatile long resultsGeneration;
    private record Analysis(VehicleDetector.Result detections, LeadVehicleTracker.Snapshot tracking,
                            LeadVehicleMotionEstimator.Measurement motion,
                            LaneDepartureDetector.Observation lane,
                            AdasDecisionEngine.Decision decision, double speedKmh) { }

    private long lastHeartbeatLogNanos;
    private LeadVehicleTracker.State previousTrackingState = LeadVehicleTracker.State.NONE;
    private long previousTargetId = 0L;
    private long previousDecisionTargetId;
    private volatile Analysis latestAnalysis;
    private volatile Set<AdasDecisionEngine.Alert> heldAlerts = Set.of();
    private volatile long heldAlertTargetId;
    private volatile long alertsHoldUntilNanos;
    // Latency instrumentation: when the collision risk first became measurable, so the log can show
    // the real confirmation delay instead of an estimate.
    private boolean previousCollisionDanger;
    private long dangerStartedNanos;
    private long previousCaptured;
    private long previousMetricsTime;
    /** Last preview letterbox scale actually applied, so an unchanged fit is not re-applied. */
    private float appliedScaleX = Float.NaN;
    private float appliedScaleY = Float.NaN;
    private long previousProcessedTimestampNanos;
    private final Handler metricsHandler = new Handler(Looper.getMainLooper());
    private record SimulationSnapshot(CameraCalibration calibration,
                                      CalibrationStore.Status status) { }
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
        View content = findViewById(R.id.adas_content);
        statusView = findViewById(R.id.status);
        metricsView = findViewById(R.id.metrics);
        calibrationView = findViewById(R.id.calibration);
        simulationButton = findViewById(R.id.simulation_button);
        if (!BuildConfig.DEBUG) {
            simulationButton.setVisibility(View.GONE);
        }
        previewView = findViewById(R.id.usb_preview);
        overlayView = findViewById(R.id.vehicle_overlay);
        // Registered only after overlayView exists: the listener touches it directly, so a
        // synchronous insets dispatch during onCreate would otherwise dereference null.
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets navBars = insets.getInsets(WindowInsets.Type.navigationBars());
            Insets systemBars = insets.getInsets(WindowInsets.Type.systemBars());
            int bottom = Math.max(navBars.bottom, systemBars.bottom);
            overlayView.setBottomInset(bottom);
            return insets;
        });
        content.requestApplyInsets();
        calibrationStore = new CalibrationStore(this);
        calibration = calibrationStore.load();
        calibrationStatus = calibrationStore.loadStatus();
        calibrationProgress = calibrationStore.loadProgress();
        autoCalibrationLearner.reset(calibrationStatus);
        // The learner keeps an in-memory sample window. A process restart cannot
        // resume that window, so never present persisted CALIBRATING progress as
        // if those samples were still available.
        if (calibrationStatus == CalibrationStore.Status.CALIBRATING) {
            calibrationProgress = 0;
            calibrationStore.saveStatus(calibrationStatus, calibrationProgress);
        }
        overlayView.setCalibration(calibration, calibrationStatus);
        calibrationView.setOnClickListener(view -> showCalibrationDialog());
        simulationButton.setOnClickListener(view -> showSimulationDialog());
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
            } else if (started) {
                cameraSource.retryOpen();
            }
        });
        frameDispatcher = new FrameDispatcher(2);
        frameConsumer = new FrameConsumer(frameDispatcher, new FrameConsumer.Handler() {
            private VehicleDetector detector;
            private RuntimeException initializationFailure;
            private long nextDetectorRetryNanos;

            @Override
            public void onFrame(FrameDispatcher.Frame frame) {
                long sessionStart;
                long frameGeneration;
                synchronized (MainActivity.this) {
                    sessionStart = acceptFramesAfterNanos;
                    frameGeneration = resultsGeneration;
                    if (!started || frame.timestampNanos() < sessionStart || simulationSnapshot != null) {
                        return;
                    }
                }
                if (initializationFailure != null) {
                    if (System.nanoTime() < nextDetectorRetryNanos) {
                        throw initializationFailure;
                    }
                    initializationFailure = null;
                }
                if (detector == null) {
                    try {
                        detector = new LiteRtVehicleDetector(getAssets());
                    } catch (IOException | RuntimeException | LinkageError failure) {
                        Log.e(TAG, "Vehicle model initialization failed", failure);
                        initializationFailure = new IllegalStateException(
                                "模型加载失败: " + failure.getMessage(), failure);
                        nextDetectorRetryNanos = System.nanoTime() + 5_000_000_000L;
                        throw initializationFailure;
                    }
                }
                VehicleDetector.Result result = detector.detect(frame);
                nextDetectorRetryNanos = 0L;
                // Keep inference outside the lock; transitions and the short analysis stage
                synchronized (MainActivity.this) {
                    if (!started || sessionStart != acceptFramesAfterNanos
                            || frameGeneration != resultsGeneration || simulationSnapshot != null) {
                        return;
                    }
                    if (System.nanoTime() - result.timestampNanos()
                            > LeadVehicleTracker.MAX_OBSERVATION_GAP_NANOS) {
                        resetAnalysisState();
                        return;
                    }
                    if (result.timestampNanos() <= previousProcessedTimestampNanos) {
                        return;
                    }
                    if (previousProcessedTimestampNanos > 0L
                            && result.timestampNanos() - previousProcessedTimestampNanos
                            > LeadVehicleTracker.MAX_OBSERVATION_GAP_NANOS) {
                        resetAnalysisState();
                    }
                    LaneDepartureDetector.Observation lane = laneDetector.detect(
                            frame.nv21(), frame.width(), frame.height());
                    double speed = validSpeedKmh() ? egoSpeedKmh : Double.NaN;
                    processAdasFrame(result, lane, speed, frameGeneration, sessionStart, false);
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
                    // Retain nextDetectorRetryNanos to prevent rapid spin-loop worker restarts
                }
            }
        });
        cameraSource = new UsbCameraSource(this, previewView, frameDispatcher,
                new UsbCameraSource.Listener() {
                    public void onDeviceAttached(UsbDevice device) {
                        updateCameraStatus(getString(R.string.camera_attached), false);
                    }

                    public void onDeviceConnectionChanged(UsbDevice device, boolean connected) {
                        updateCameraStatus(getString(connected ? R.string.camera_opened
                                : R.string.camera_disconnected), !connected);
                        if (connected) {
                            // Rebind the calibration to the camera that actually opened: the same
                            // 1280x720 geometry from a different sensor would otherwise be inherited.
                            reloadCalibrationForCamera();
                        }
                    }
                    public void onDeviceDetached(UsbDevice device) {
                        updateCameraStatus(getString(R.string.camera_disconnected), true);
                    }

                    public void onError(String message) {
                        updateCameraStatus(message + "\n点击此处重试", true);
                    }
                });
        previewView.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            Analysis analysis = latestAnalysis;
            if (analysis != null) {
                fitPreview(analysis.detections());
            }
        });
    }

    /** Camera callbacks run on the main thread; real capture cannot reset a simulated session. */
    private void updateCameraStatus(CharSequence text, boolean resetResults) {
        cameraStatusText = text;
        if (simulationSnapshot != null) {
            return;
        }
        if (resetResults) {
            clearResults();
        }
        statusView.setText(text);
    }

    @Override
    protected void onStart() {
        super.onStart();
        synchronized (this) {
            started = true;
            clearResults();
        }
        updateCameraStatus(getString(R.string.app_bootstrap_status), false);
        previousMetricsTime = System.nanoTime();
        previousCaptured = cameraSource.capturedFrames();
        frameConsumer.start();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraSource.start();
            ensureLocationPermission();
        } else {
            updateCameraStatus(getString(R.string.camera_permission_required), false);
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
                updateCameraStatus(getString(R.string.app_bootstrap_status), false);
                cameraSource.start();
                ensureLocationPermission();
            } else {
                updateCameraStatus(getString(R.string.camera_permission_required), false);
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
        if (now - lastHeartbeatLogNanos >= 1_000_000_000L) {
            lastHeartbeatLogNanos = now;
            Analysis heartbeat = latestAnalysis;
            CameraCalibration heartbeatCalibration = calibration;
            String calibDesc = (heartbeatCalibration == null) ? "UNSET"
                    : String.format(Locale.ROOT, "%s(H=%.2fm, pitch=%.1f°)",
                            calibrationStatus.name(), heartbeatCalibration.cameraHeightMeters(), heartbeatCalibration.pitchDegrees());
            String targetDesc = (heartbeat != null && heartbeat.tracking() != null && heartbeat.motion() != null && heartbeat.motion().visible())
                    ? String.format(Locale.ROOT, "#%d(D=%.1fm, vc=%.1fm/s, TTC=%s)",
                            heartbeat.tracking().trackId(), heartbeat.motion().distanceMeters(),
                            heartbeat.motion().closingSpeedMps(),
                            heartbeat.motion().closingSpeedMps() > 0 ? String.format(Locale.ROOT, "%.1fs", heartbeat.motion().distanceMeters() / heartbeat.motion().closingSpeedMps()) : "--")
                    : "NONE";
            String laneDesc = (heartbeat != null && heartbeat.lane() != null && heartbeat.lane().available())
                    ? String.format(Locale.ROOT, "offset=%.2f, conf=%.2f", heartbeat.lane().centerOffset(), heartbeat.lane().confidence())
                    : "UNAVAILABLE";
            String speedDesc = validSpeedKmh() ? String.format(Locale.ROOT, "%.1f km/h", egoSpeedKmh) : "NO_GPS";
            String eventDesc = (heartbeat != null && !heartbeat.decision().events().isEmpty())
                    ? heartbeat.decision().events().toString() : "NONE";
            double resultAgeMs = (heartbeat != null && heartbeat.detections() != null)
                    ? Math.max(0.0, (now - heartbeat.detections().timestampNanos()) / 1_000_000.0) : 0.0;
            double currentFps = (captured - previousCaptured) * 1_000_000_000.0
                    / Math.max(1L, now - previousMetricsTime);

            Log.i(TAG, String.format(Locale.ROOT,
                    "[HEARTBEAT] FPS=%.1f | Age=%.0fms | Speed=%s | Calib=%s | Target=%s | Lane=%s | Alert=%s",
                    currentFps, resultAgeMs, speedDesc, calibDesc, targetDesc, laneDesc, eventDesc));
        }
        double fps = (captured - previousCaptured) * 1_000_000_000.0
                / Math.max(1L, now - previousMetricsTime);
        previousCaptured = captured;
        previousMetricsTime = now;
        FrameDispatcher.Metrics queue = frameDispatcher.metrics();
        FrameConsumer.Metrics worker = frameConsumer.metrics();
        String stream = getString(R.string.stream_metrics, fps, queue.offeredFrames(),
                worker.processedFrames(), queue.droppedFrames(), queue.discardedFrames(),
                worker.failedFrames() + cameraSource.invalidFrames());
        AlertAudio.Status audioStatus = alertAudio == null ? AlertAudio.Status.UNAVAILABLE : alertAudio.status();
        stream += switch (audioStatus) {
            case READY -> "";
            case LOADING -> "\n报警音正在加载";
            case UNAVAILABLE -> "\n报警音不可用，请检查车机音频通道";
            case MUTED -> "\n媒体音量已静音，声音预警不可用";
        };
        Analysis analysis = latestAnalysis;
        VehicleDetector.Result result = analysis == null ? null : analysis.detections();
        updateCalibrationStatus(result);
        boolean fresh = result != null && (simulator.isRunning()
                || (result.timestampNanos() >= acceptFramesAfterNanos
                && now - result.timestampNanos() <= LeadVehicleTracker.MAX_OBSERVATION_GAP_NANOS));
        if (!simulator.isRunning() && !worker.lastError().isEmpty()) {
            metricsView.setText(stream + "\n" + worker.lastError());
            overlayView.setResult(null, null);
        } else if (fresh) {
            fitPreview(result);
            overlayView.setResult(result, analysis.tracking(), analysis.lane(),
                    displayDecision(analysis, now), analysis.motion().visible(),
                    Double.isFinite(displaySpeedKmh(analysis)));
            metricsView.setText(stream + "\n" + getString(R.string.detection_metrics,
                    result.vehicles().size(), result.inferenceNanos() / 1_000_000.0)
                    + "\n" + trackingStatus(analysis.tracking())
                    + "\n" + measurementStatus(analysis, now));
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

    private String measurementStatus(Analysis analysis, long now) {
        LeadVehicleMotionEstimator.Measurement motion = analysis.motion();
        double speed = displaySpeedKmh(analysis);
        String lane = analysis.lane().available()
                ? (!Double.isFinite(speed) ? "车道已识别，LDW等待有效车速"
                : displayDecision(analysis, now).laneWarning() ? "车道注意" : "车道已识别") : "车道不可用";
        String speedText = Double.isFinite(speed)
                ? String.format(Locale.ROOT, "车速 %.0f km/h", speed)
                : "车速 -- (GPS无效或过期)";
        if (!motion.visible()) {
            String availability = calibrationStatus == CalibrationStore.Status.CALIBRATED
                    ? getString(R.string.measurement_unavailable)
                    : getString(R.string.measurement_calibration_pending);
            return availability + " · " + speedText + " · " + lane;
        }
        double ttc = motion.closingSpeedMps() > 0.0
                ? motion.distanceMeters() / motion.closingSpeedMps() : Double.NaN;
        String distance = String.format(Locale.ROOT, "%.1f m", motion.distanceMeters());
        String ttcText = Double.isFinite(ttc)
                ? String.format(Locale.ROOT, "%.1f s", ttc) : "--";
        AdasDecisionEngine.Decision shown = displayDecision(analysis, now);
        String warning = shown.events().isEmpty()
                ? (shown.headwayWarning() ? "HMW 条件" : "无预警")
                : "事件: " + shown.events();
        String laneWarning = shown.laneWarning() ? " · LDW 条件" : "";
        String risk = !Double.isFinite(speed) && !shown.headwayWarning()
                ? "预警受限" : riskStatus(shown);
        return getString(R.string.measurement_status, distance, ttcText,
                risk + " · " + speedText + " · " + warning + laneWarning + " · " + lane);
    }

    private double displaySpeedKmh(Analysis analysis) {
        return simulator.isRunning() ? analysis.speedKmh()
                : validSpeedKmh() && Double.isFinite(analysis.speedKmh()) ? egoSpeedKmh : Double.NaN;
    }
    private String riskStatus(AdasDecisionEngine.Decision decision) {
        if (decision.collisionDanger() || decision.headwayCritical()
                || decision.events().contains(AdasDecisionEngine.Alert.FCW)
                || decision.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL)) {
            return "危险";
        }
        if (decision.headwayWarning() || decision.laneWarning()) {
            return "注意";
        }
        return "正常";
    }

    private AdasDecisionEngine.Decision displayDecision(Analysis analysis, long now) {
        long currentTargetId = (analysis != null && analysis.tracking() != null)
                ? analysis.tracking().trackId() : 0L;
        if (now < alertsHoldUntilNanos && !heldAlerts.isEmpty() && heldAlertTargetId == currentTargetId) {
            return new AdasDecisionEngine.Decision(heldAlerts,
                    analysis.decision().headwayWarning(),
                    analysis.decision().headwayCritical(),
                    analysis.decision().collisionDanger(),
                    analysis.decision().laneWarning());
        }
        return analysis.decision();
    }

    private boolean validSpeedKmh() {
        return Double.isFinite(egoSpeedKmh) && speedTimestampNanos > 0L
                && SystemClock.elapsedRealtimeNanos() - speedTimestampNanos <= 2_000_000_000L;
    }

    private synchronized void startLocationUpdates() {
        egoSpeedKmh = Double.NaN;
        speedTimestampNanos = 0L;
        if (locationManager == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            boolean requested = false;
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f,
                        locationListener, Looper.getMainLooper());
                requested = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 500L, 0f,
                        locationListener, Looper.getMainLooper());
                requested = true;
            }
            if (!requested) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f,
                        locationListener, Looper.getMainLooper());
            }

            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) {
                last = locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER);
            }
            // Stale check: strictly reject cached location older than 2.0 seconds
            if (last != null && (SystemClock.elapsedRealtimeNanos() - last.getElapsedRealtimeNanos())
                    <= 2_000_000_000L) {
                locationListener.onLocationChanged(last);
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "GPS speed unavailable", failure);
            egoSpeedKmh = Double.NaN;
        }
    }

    private synchronized void stopLocationUpdates() {
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
            synchronized (MainActivity.this) {
                if (!started || location == null || !location.hasSpeed()
                        || !Float.isFinite(location.getSpeed()) || location.getSpeed() < 0f) {
                    return;
                }
                if (location.hasAccuracy() && location.getAccuracy() > 50f) {
                    return; // Reject poor GPS fix accuracy
                }
                double rawKmh = location.getSpeed() * 3.6;
                if (rawKmh > 180.0) {
                    return; // Reject unrealistic automotive speed
                }
                long measuredAt = location.getElapsedRealtimeNanos();
                long now = SystemClock.elapsedRealtimeNanos();
                if (measuredAt <= speedTimestampNanos || measuredAt > now
                        || now - measuredAt > 2_000_000_000L) {
                    return;
                }
                // Reject impossible acceleration spikes (> 15 m/s^2 ≈ 1.5g)
                if (Double.isFinite(egoSpeedKmh) && speedTimestampNanos > 0L) {
                    double dtSec = (measuredAt - speedTimestampNanos) / 1_000_000_000.0;
                    if (dtSec > 0.05 && dtSec < 1.0) {
                        double accelMps2 = Math.abs(rawKmh - egoSpeedKmh) / (dtSec * 3.6);
                        if (accelMps2 > 15.0) {
                            return; // Spike/glitch rejected
                        }
                    }
                }
                double prevSpeed = egoSpeedKmh;
                egoSpeedKmh = rawKmh;
                speedTimestampNanos = measuredAt;
                if (!Double.isFinite(prevSpeed)) {
                    Log.i(TAG, String.format(Locale.ROOT, "[GPS] First speed fix received: %.1f km/h (provider=%s)",
                            egoSpeedKmh, location.getProvider()));
                }
            }
        }
    };

    private void updateCalibrationStatus(VehicleDetector.Result result) {
        if (calibration == null || calibrationStatus == CalibrationStore.Status.UNCONFIGURED) {
            calibrationView.setText(R.string.calibration_unset);
            calibrationView.setTextColor(0xFFFFD180);
        } else if (result != null && !calibration.isUsableFor(result.frameWidth(), result.frameHeight())) {
            calibrationView.setText(getString(R.string.calibration_size_mismatch,
                    calibration.imageWidth(), calibration.imageHeight()));
            calibrationView.setTextColor(0xFFFF8A80);
        } else if (calibrationStatus == CalibrationStore.Status.WIZARD_COMPLETED) {
            if (showAngleOutOfRangeHint()) {
                return;
            }
            calibrationView.setText(getString(R.string.calibration_wizard_done,
                    calibration.cameraHeightMeters()));
            calibrationView.setTextColor(0xFF80DEEA);
        } else if (calibrationStatus == CalibrationStore.Status.CALIBRATING) {
            if (showAngleOutOfRangeHint()) {
                return;
            }
            calibrationView.setText(getString(R.string.calibration_in_progress,
                    calibrationProgress));
            calibrationView.setTextColor(0xFF80DEEA);
        } else if (calibrationStatus == CalibrationStore.Status.CALIBRATED) {
            calibrationView.setText(getString(R.string.calibration_loaded,
                    calibration.imageWidth(), calibration.imageHeight(),
                    calibration.cameraHeightMeters(), calibration.pitchDegrees()));
            calibrationView.setTextColor(0xFFA5D6A7);
        }
    }

    /**
     * A sustained run of geometric rejections cannot be waited out: the configured pitch keeps the
     * lane ROI off the road, so the learner never even leaves the wizard state. Both pre-learning
     * states must surface that, otherwise the user is told to keep driving forever.
     */
    private boolean showAngleOutOfRangeHint() {
        if (autoCalibrationLearner.consecutiveGeometricRejections()
                < AutoCalibrationLearner.GEOMETRIC_REJECTION_HINT_THRESHOLD) {
            return false;
        }
        calibrationView.setText(R.string.calibration_angle_out_of_range);
        calibrationView.setTextColor(0xFFFF8A80);
        return true;
    }

    private synchronized void processAdasFrame(VehicleDetector.Result result,
                                               LaneDepartureDetector.Observation lane,
                                               double speed,
                                               long frameGeneration,
                                               long frameSessionStart,
                                               boolean simulationFrame) {
        if (!started || simulationFrame != simulator.isRunning()
                || simulationFrame != (simulationSnapshot != null)
                || frameGeneration != resultsGeneration
                || frameSessionStart != acceptFramesAfterNanos) {
            return;
        }
        if (!simulationFrame && System.nanoTime() - result.timestampNanos()
                > LeadVehicleTracker.MAX_OBSERVATION_GAP_NANOS) {
            resetAnalysisState();
            return;
        }
        if (result.timestampNanos() <= previousProcessedTimestampNanos) {
            return;
        }
        previousProcessedTimestampNanos = result.timestampNanos();
        LeadVehicleTracker.Snapshot tracking = tracker.update(result);
        CameraCalibration activeCalib = calibrationStatus == CalibrationStore.Status.CALIBRATED
                ? calibration : null;
        boolean persistCalibration = !simulationFrame;
        CameraCalibration learningCalibration = calibration;
        if (learningCalibration != null
                && (!simulationFrame || simulator.currentScenario() == AdasSimulator.Scenario.AUTO_CALIBRATION)
                && learningCalibration.isUsableFor(result.frameWidth(), result.frameHeight())) {
            AutoCalibrationLearner.StepResult step = autoCalibrationLearner.update(
                    lane, speed, learningCalibration);
            if (step.calibrationUpdated()) {
                calibration = step.calibration();
                calibrationStatus = step.status();
                calibrationProgress = step.progressPercent();
                activeCalib = step.status() == CalibrationStore.Status.CALIBRATED
                        ? step.calibration() : null;
                if (persistCalibration) {
                    String currentId = cameraSource != null ? cameraSource.currentCameraId() : "";
                    calibrationStore.save(step.calibration(), step.status(), step.progressPercent(), currentId);
                }
            } else if (step.status() != calibrationStatus || step.progressPercent() != calibrationProgress) {
                calibrationStatus = step.status();
                calibrationProgress = step.progressPercent();
                if (persistCalibration) {
                    calibrationStore.saveStatus(step.status(), step.progressPercent());
                }
                postCalibrationOverlay(frameGeneration);
            }
        }
        LeadVehicleMotionEstimator.Measurement motion = motionEstimator.update(
                tracking, activeCalib, result.frameWidth(), result.frameHeight());

        boolean hasTarget = (tracking.state() == LeadVehicleTracker.State.TRACKING
                || tracking.state() == LeadVehicleTracker.State.LOST);
        long decisionTargetId = hasTarget ? tracking.trackId() : 0L;
        if (decisionTargetId != previousDecisionTargetId) {
            decisionEngine.resetTargetState();
            previousDecisionTargetId = decisionTargetId;
            heldAlerts = Set.of();
            alertsHoldUntilNanos = 0L;
            heldAlertTargetId = decisionTargetId;
            // Danger edge belongs to the previous target; a new target must re-arm its own start.
            previousCollisionDanger = false;
            dangerStartedNanos = 0L;
        }
        double decisionDistance = motion.distanceMeters();
        AdasDecisionEngine.Observation observation = new AdasDecisionEngine.Observation(
                result.timestampNanos() / 1_000_000L, speed,
                decisionDistance, motion.closingSpeedMps(),
                motion.targetAreaPixels(), motion.visible());
        AdasDecisionEngine.LaneObservation laneObservation =
                (!simulationFrame && calibrationStatus != CalibrationStore.Status.CALIBRATED)
                        ? new AdasDecisionEngine.LaneObservation(0.0, 0.0, false)
                        : new AdasDecisionEngine.LaneObservation(lane.centerOffset(), lane.confidence(), lane.available());
        AdasDecisionEngine.Decision decision = decisionEngine.update(observation, laneObservation);
        // Latency instrumentation only: age is measured against the capture timestamp, so it covers
        // sampling throttle plus preprocessing and inference for this decision.
        long nowNanos = System.nanoTime();
        long resultAgeMs = Math.max(0L, nowNanos - result.timestampNanos()) / 1_000_000L;
        if (decision.collisionDanger() && !previousCollisionDanger) {
            dangerStartedNanos = nowNanos;
            Log.w(TAG, String.format(Locale.ROOT,
                    "[DANGER-START] Age=%dms | Dist=%.1fm | ClosingSpeed=%.1fm/s | EgoSpeed=%.1f km/h | TTC=%s",
                    resultAgeMs, motion.distanceMeters(), motion.closingSpeedMps(), speed,
                    motion.closingSpeedMps() > 0.0
                            ? String.format(Locale.ROOT, "%.2fs",
                            motion.distanceMeters() / motion.closingSpeedMps()) : "--"));
        }
        previousCollisionDanger = decision.collisionDanger();
        if (!decision.events().isEmpty()) {
            heldAlerts = decision.events();
            heldAlertTargetId = decisionTargetId;
            alertsHoldUntilNanos = nowNanos + 1_500_000_000L;
            long confirmationMs = dangerStartedNanos > 0L
                    ? (nowNanos - dangerStartedNanos) / 1_000_000L : -1L;
            for (AdasDecisionEngine.Alert alert : decision.events()) {
                Log.w(TAG, String.format(Locale.ROOT,
                        "[ALERT-TRIGGER] >>> %s <<< | Age=%dms | Confirm=%dms | Dist=%.1fm | ClosingSpeed=%.1fm/s | EgoSpeed=%.1f km/h | Target=#%d",
                        alert.name(), resultAgeMs, confirmationMs, motion.distanceMeters(),
                        motion.closingSpeedMps(), speed, tracking.trackId()));
            }
        }
        if (tracking.state() != previousTrackingState || tracking.trackId() != previousTargetId) {
            Log.i(TAG, String.format(Locale.ROOT,
                    "[TRACKER] Target transition: %s(#%d) -> %s(#%d)",
                    previousTrackingState, previousTargetId, tracking.state(), tracking.trackId()));
            previousTrackingState = tracking.state();
            previousTargetId = tracking.trackId();
        }
        if (alertAudio != null) {
            alertAudio.play(decision.events());
        }
        latestAnalysis = new Analysis(result, tracking, motion, lane, decision, speed);
    }

    private void postCalibrationOverlay(long frameGeneration) {
        runOnUiThread(() -> {
            synchronized (MainActivity.this) {
                if (started && frameGeneration == resultsGeneration) {
                    overlayView.setCalibration(calibration, calibrationStatus);
                }
            }
        });
    }

    private synchronized void showSimulationDialog() {
        if (!BuildConfig.DEBUG) {
            return;
        }
        if (simulator.isRunning()) {
            simulator.stop();
            restoreSimulationState();
            simulationButton.setText("室内模拟测试");
            simulationButton.setBackgroundColor(0xB30D47A1);
            Toast.makeText(this, "已停止模拟测试，恢复正常监测", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] scenarioNames = {
                "🔊 扬声器发声测试 (测试车机喇叭通路)",
                "1. FCW 紧急碰撞测试 (60km/h 高速前车急刹/逼近)",
                "2. HMW 极近车距测试 (25km/h 跟车贴近至 3.5m 报警)",
                "3. LDW 车道偏离测试 (65km/h 车辆压线偏离报警)",
                "4. LVSA 前车起步测试 (红灯静止等候 / 前车起步驶离)",
                "5. 行车自标定收敛测试 (自动学习灭点 0% -> 100%)"
        };
        AdasSimulator.Scenario[] scenarios = {
                AdasSimulator.Scenario.FCW_APPROACH,
                AdasSimulator.Scenario.HMW_PROXIMITY,
                AdasSimulator.Scenario.LDW_DEPARTURE,
                AdasSimulator.Scenario.LVSA_START,
                AdasSimulator.Scenario.AUTO_CALIBRATION
        };

        new AlertDialog.Builder(this)
                .setTitle("室内 ADAS 场景模拟测试")
                .setItems(scenarioNames, (dialog, which) -> {
                    if (which == 0) {
                        if (alertAudio != null) {
                            boolean played = alertAudio.testSound();
                            Toast.makeText(this, played
                                    ? "已请求播放测试音，请确认扬声器实际出声"
                                    : "测试音播放失败，请检查媒体音量和音频通道", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "报警音不可用，请检查音频通道", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        startSimulation(scenarios[which - 1]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    private synchronized void startSimulation(AdasSimulator.Scenario scenario) {
        if (!BuildConfig.DEBUG || !started) {
            return;
        }
        Analysis analysis = latestAnalysis;
        int width = analysis == null ? 1280 : analysis.detections().frameWidth();
        int height = analysis == null ? 720 : analysis.detections().frameHeight();

        if (simulationSnapshot == null) {
            simulationSnapshot = new SimulationSnapshot(calibration, calibrationStatus);
        }
        // Synthetic boxes use this fixture, independent of the real camera's saved calibration.
        calibration = CameraCalibration.fromWizard(width, height, 1.25, 90.0, 4.0);
        calibrationStatus = scenario == AdasSimulator.Scenario.AUTO_CALIBRATION
                ? CalibrationStore.Status.WIZARD_COMPLETED : CalibrationStore.Status.CALIBRATED;
        calibrationProgress = calibrationStatus == CalibrationStore.Status.CALIBRATED ? 100 : 0;
        autoCalibrationLearner.reset(calibrationStatus);
        clearResults();
        overlayView.setCalibration(calibration, calibrationStatus);

        simulationButton.setText("模拟中 (点击停止)");
        simulationButton.setBackgroundColor(0xFFC62828);

        simulator.start(scenario, width, height, calibration, new AdasSimulator.Listener() {
            @Override
            public void onFrame(AdasSimulator.SimFrame simFrame) {
                statusView.setText("【室内模拟】" + simFrame.description());
                processAdasFrame(simFrame.detections(), simFrame.lane(), simFrame.speedKmh(),
                        resultsGeneration, acceptFramesAfterNanos, true);
            }

            @Override
            public void onFinished(AdasSimulator.Scenario finished) {
                restoreSimulationState();
                simulationButton.setText("室内模拟测试");
                simulationButton.setBackgroundColor(0xB30D47A1);
                Toast.makeText(MainActivity.this, "模拟完成: " + finished.displayName(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private synchronized void restoreSimulationState() {
        SimulationSnapshot snapshot = simulationSnapshot;
        if (snapshot == null) {
            return;
        }
        calibration = snapshot.calibration();
        calibrationStatus = snapshot.status();
        autoCalibrationLearner.reset(calibrationStatus);
        calibrationProgress = autoCalibrationLearner.progress();
        simulationSnapshot = null;
        clearResults();
        calibrationStore.saveStatus(calibrationStatus, calibrationProgress);
        overlayView.setCalibration(calibration, calibrationStatus);
        statusView.setText(cameraStatusText == null
                ? getString(R.string.app_bootstrap_status) : cameraStatusText);
    }
    private void showCalibrationDialog() {
        if (simulator.isRunning()) {
            Toast.makeText(this, "请先停止模拟再修改标定", Toast.LENGTH_SHORT).show();
            return;
        }
        Analysis analysis = latestAnalysis;
        int width = analysis == null ? 1280 : analysis.detections().frameWidth();
        int height = analysis == null ? 720 : analysis.detections().frameHeight();

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding / 2, padding, padding / 2);

        TextView vehicleLabel = new TextView(this);
        vehicleLabel.setText("1. 车辆类型与安装高度 (米):");
        vehicleLabel.setTextSize(14f);
        vehicleLabel.setTextColor(0xFFFFFFFF);
        form.addView(vehicleLabel);

        RadioGroup vehicleGroup = new RadioGroup(this);
        vehicleGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbSedan = new RadioButton(this);
        rbSedan.setText("轿车 (1.25m)");
        RadioButton rbSuv = new RadioButton(this);
        rbSuv.setText("SUV (1.45m)");
        RadioButton rbTruck = new RadioButton(this);
        rbTruck.setText("货车 (1.75m)");
        RadioButton rbCustom = new RadioButton(this);
        rbCustom.setText("自定义");
        vehicleGroup.addView(rbSedan);
        vehicleGroup.addView(rbSuv);
        vehicleGroup.addView(rbTruck);
        vehicleGroup.addView(rbCustom);
        form.addView(vehicleGroup);

        EditText customHeightField = numberField("输入自定义离地高度(米，如 1.30)");
        customHeightField.setVisibility(View.GONE);
        form.addView(customHeightField);

        rbSedan.setChecked(true);
        if (calibration != null) {
            double h = calibration.cameraHeightMeters();
            if (Math.abs(h - 1.25) < 0.05) {
                rbSedan.setChecked(true);
            } else if (Math.abs(h - 1.45) < 0.05) {
                rbSuv.setChecked(true);
            } else if (Math.abs(h - 1.75) < 0.05) {
                rbTruck.setChecked(true);
            } else {
                rbCustom.setChecked(true);
                customHeightField.setVisibility(View.VISIBLE);
                customHeightField.setText(Double.toString(h));
            }
        }
        vehicleGroup.setOnCheckedChangeListener((group, checkedId) -> {
            customHeightField.setVisibility(checkedId == rbCustom.getId() ? View.VISIBLE : View.GONE);
        });

        TextView lensLabel = new TextView(this);
        lensLabel.setText("\n2. 摄像头水平视场角 (HFOV):");
        lensLabel.setTextSize(14f);
        lensLabel.setTextColor(0xFFFFFFFF);
        form.addView(lensLabel);

        RadioGroup fovGroup = new RadioGroup(this);
        fovGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rb90 = new RadioButton(this);
        rb90.setText("标准 90° (推荐)");
        RadioButton rb100 = new RadioButton(this);
        rb100.setText("广角 100°");
        RadioButton rb120 = new RadioButton(this);
        rb120.setText("超广角 120°");
        fovGroup.addView(rb90);
        fovGroup.addView(rb100);
        fovGroup.addView(rb120);
        rb90.setChecked(true);
        if (calibration != null && calibration.isUsableFor(width, height)) {
            double currentFov = Math.toDegrees(2.0 * Math.atan(
                    (width / 2.0) / (calibration.focalLengthYNormalized() * height)));
            if (Math.abs(currentFov - 120.0) < Math.abs(currentFov - 100.0)
                    && Math.abs(currentFov - 120.0) < Math.abs(currentFov - 90.0)) {
                rb120.setChecked(true);
            } else if (Math.abs(currentFov - 100.0) < Math.abs(currentFov - 90.0)) {
                rb100.setChecked(true);
            }
        }
        form.addView(fovGroup);

        TextView pitchLabel = new TextView(this);
        pitchLabel.setText("\n3. 初始俯仰角 (度，向下为正):");
        pitchLabel.setTextSize(14f);
        pitchLabel.setTextColor(0xFFFFFFFF);
        form.addView(pitchLabel);

        EditText pitchField = numberField("默认 4.0（支架未定角度时先用默认值）");
        double pitchToShow = calibration != null && calibration.isUsableFor(width, height)
                ? calibration.pitchDegrees() : INITIAL_PITCH_DEGREES;
        pitchField.setText(Double.toString(pitchToShow));
        form.addView(pitchField);

        TextView pitchNote = new TextView(this);
        pitchNote.setText("• 已知安装角度时直接填，可省去自学习等待\n"
                + "• 未知则保持默认：上路正常行驶后自动收敛，无需精确测量");
        pitchNote.setTextSize(12f);
        pitchNote.setTextColor(0xFFB0BEC5);
        form.addView(pitchNote);

        TextView guideNote = new TextView(this);
        guideNote.setText("\n4. 物理对准提示:\n• 调整镜头使车头机盖露出在屏幕下方参考线处\n• 确保道路远方处于中间黄色地平线附近\n• 拧紧支架螺丝保存后，上路正常行驶自动收敛俯仰角");
        guideNote.setTextSize(12f);
        guideNote.setTextColor(0xFFB0BEC5);
        form.addView(guideNote);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(form);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("ADAS 摄像头安装向导（" + width + "×" + height + "）")
                .setView(scrollView)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存并开启自学习", (dialog, which) -> {
                    try {
                        double heightMeters;
                        if (rbCustom.isChecked()) {
                            heightMeters = parse(customHeightField);
                        } else if (rbSuv.isChecked()) {
                            heightMeters = 1.45;
                        } else if (rbTruck.isChecked()) {
                            heightMeters = 1.75;
                        } else {
                            heightMeters = 1.25;
                        }

                        double hfov = rb120.isChecked() ? 120.0 : (rb100.isChecked() ? 100.0 : 90.0);
                        double initialPitch = parsePitchDegrees(pitchField);

                        CameraCalibration next = CameraCalibration.fromWizard(
                                width, height, heightMeters, hfov, initialPitch);

                        applyCameraCalibration(next);
                        Toast.makeText(this, initialPitch >= STEEP_PITCH_WARNING_DEGREES
                                ? "向导配置已保存！当前俯仰角较大，若标定栏未开始收敛请按提示调整支架角度"
                                : "向导配置已保存！请上路以 >35km/h 正常行驶以完成自标定",
                                Toast.LENGTH_LONG).show();
                    } catch (RuntimeException failure) {
                        Toast.makeText(this, "参数错误: " + failure.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });

        if (calibrationStatus != CalibrationStore.Status.UNCONFIGURED) {
            builder.setNeutralButton("重置标定", (dialog, which) -> {
                applyCameraCalibration(null);
                Toast.makeText(this, "已重置标定参数", Toast.LENGTH_SHORT).show();
            });
        }
        builder.show();
    }

    private synchronized void applyCameraCalibration(CameraCalibration next) {
        calibration = next;
        calibrationStatus = next == null ? CalibrationStore.Status.UNCONFIGURED
                : CalibrationStore.Status.WIZARD_COMPLETED;
        calibrationProgress = 0;
        autoCalibrationLearner.reset(calibrationStatus);
        clearResults();
        String currentId = cameraSource != null ? cameraSource.currentCameraId() : "";
        if (next == null) {
            calibrationStore.clear();
        } else {
            calibrationStore.save(next, calibrationStatus, 0, currentId);
        }
        updateCalibrationStatus(null);
    }

    /**
     * Rebinds calibration state once a camera has actually opened. A stored calibration without a
     * recorded camera id cannot be attributed to the device on the other end of the cable, so it is
     * treated as unbound and dropped rather than adopted: keeping it would silently apply one
     * camera's pitch to another. This project is still in its test phase, so the resulting one-off
     * re-calibration on upgrade is accepted instead of migrating the old record; see
     * {@code CalibrationStore} for that note. When no camera id is known yet the current state is
     * left untouched, because that says nothing about whether the calibration belongs to this camera.
     */
    private synchronized void reloadCalibrationForCamera() {
        String currentId = cameraSource == null ? "" : cameraSource.currentCameraId();
        if (currentId.isEmpty()) {
            return;
        }
        CameraCalibration stored = calibrationStore.load(currentId);
        if (stored != null) {
            calibration = stored;
            calibrationStatus = calibrationStore.loadStatus();
            calibrationProgress = calibrationStore.loadProgress();
            autoCalibrationLearner.reset(calibrationStatus);
            updateCalibrationStatus(null);
            return;
        }
        String storedId = calibrationStore.storedCameraId();
        if (storedId.isEmpty() && calibration != null) {
            Log.w(TAG, "[CALIB] Calibration has no camera id; invalidating it for " + currentId);
        } else if (calibration != null) {
            Log.w(TAG, "[CALIB] Camera changed to " + currentId + " (stored " + storedId
                    + "), invalidating old calibration");
        }
        if (calibration != null) {
            applyCameraCalibration(null);
        }
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

    /**
     * Validates the wizard's pitch input. The accepted range mirrors the learner's pitch bounds;
     * a value outside them could never be reached by self-learning either, so storing it would only
     * produce a calibration the pipeline refuses to use. Whether the resulting pitch actually keeps
     * the lane ROI on the road is judged afterwards by the learner's ROI guard, which surfaces the
     * "安装角度超出可学习范围" hint once it rejects repeatedly.
     */
    private double parsePitchDegrees(EditText field) {
        double value = parse(field);
        if (!Double.isFinite(value) || value < MIN_INITIAL_PITCH_DEGREES
                || value > MAX_INITIAL_PITCH_DEGREES) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "初始俯仰角须在 %.0f ~ %.0f 度之间", MIN_INITIAL_PITCH_DEGREES,
                    MAX_INITIAL_PITCH_DEGREES));
        }
        return value;
    }

    /**
     * Letterboxes the preview to the frame aspect ratio. The scale factors only change when the view
     * size or the frame size changes, so identical inputs are skipped: this runs from the 250 ms
     * metrics tick and a redundant setTransform would invalidate the view four times a second.
     */
    private void fitPreview(VehicleDetector.Result result) {
        if (result == null || previewView.getWidth() == 0 || previewView.getHeight() == 0) {
            return;
        }
        float scale = Math.min((float) previewView.getWidth() / result.frameWidth(),
                (float) previewView.getHeight() / result.frameHeight());
        float scaleX = result.frameWidth() * scale / previewView.getWidth();
        float scaleY = result.frameHeight() * scale / previewView.getHeight();
        if (appliedScaleX == scaleX && appliedScaleY == scaleY) {
            return;
        }
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, previewView.getWidth() / 2f, previewView.getHeight() / 2f);
        previewView.setTransform(matrix);
        appliedScaleX = scaleX;
        appliedScaleY = scaleY;
    }

    private synchronized void clearResults() {
        acceptFramesAfterNanos = System.nanoTime();
        resultsGeneration++;
        resetAnalysisState();
        overlayView.setResult(null, null);
    }

    private synchronized void resetAnalysisState() {
        if (alertAudio != null) {
            alertAudio.stop();
        }
        tracker.reset();
        motionEstimator.reset();
        laneDetector.reset();
        autoCalibrationLearner.resetSamples();
        int progress = autoCalibrationLearner.progress();
        if (calibrationProgress != progress) {
            calibrationProgress = progress;
            if (simulationSnapshot == null) {
                calibrationStore.saveStatus(calibrationStatus, progress);
            }
        }
        decisionEngine.reset();
        previousDecisionTargetId = 0L;
        previousTrackingState = LeadVehicleTracker.State.NONE;
        previousTargetId = 0L;
        previousProcessedTimestampNanos = 0L;
        previousCollisionDanger = false;
        dangerStartedNanos = 0L;
        latestAnalysis = null;
        heldAlerts = Set.of();
        alertsHoldUntilNanos = 0L;
    }

    @Override
    protected void onStop() {
        synchronized (this) {
            started = false;
            simulator.stop();
            restoreSimulationState();
            clearResults();
        }
        metricsHandler.removeCallbacks(metricsUpdater);
        stopLocationUpdates();
        cameraSource.stop();
        frameConsumer.close();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        simulator.stop();
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
