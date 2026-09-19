package com.hct.adas;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
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
import android.view.View;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/** Foreground vehicle detection/tracking for the lightweight front-vehicle ADAS profile. */
public final class MainActivity extends Activity {
    private static final String TAG = "HctAdasCore";
    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final int LOCATION_PERMISSION_REQUEST = 11;
    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm:ss", Locale.ROOT);
    /** Fallback installation pitch for a typical windshield-inside camera mount. */
    private static final double INITIAL_PITCH_DEGREES = 8.0;
    /**
     * Camera height above ground for the presets the wizard offers. These describe where the lens sits
     * for a windshield-inside mount just below the factory forward camera, which is the installation
     * this product targets; the truck value follows the same position on a much taller windshield.
     */
    private static final double HEIGHT_SEDAN_METERS = 1.30;
    private static final double HEIGHT_SUV_METERS = 1.55;
    private static final double HEIGHT_TRUCK_METERS = 2.00;
    private FrameDispatcher frameDispatcher;
    private FrameConsumer frameConsumer;
    private volatile RuntimeException initializationFailure;
    private volatile RuntimeException detectorFailure;
    private UsbCameraSource cameraSource;
    private TextureView previewView;
    private VehicleOverlayView overlayView;
    private TextView statusView;
    private CharSequence cameraStatusText;
    private TextView clockView;
    private TextView speedView;
    private View speedPanel;
    private View settingsButton;
    private View calibrationOverlayContainer;
    private CalibrationOverlayView calibrationOverlayView;
    private TextView calibrationConfirmView;
    private CameraCalibration pendingCalibration;
    private long pendingCalibrationGeneration;
    private int pendingCalibrationSession;
    private AlertDialog settingsDialog;
    private final OnBackInvokedCallback cancelCalibrationOnBack = this::hideCalibrationOverlay;
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
    /** Last lane measurement, kept so the overlay can fade a dropped lane instead of blinking. */
    private LaneGeometry.LaneSnapshot heldLaneGeometry = LaneGeometry.LaneSnapshot.INVALID;
    private final AdasDecisionEngine decisionEngine = new AdasDecisionEngine();
    private AlertAudio alertAudio;
    private LocationManager locationManager;
    private volatile double egoSpeedKmh = Double.NaN;
    private volatile long speedTimestampNanos;
    private boolean permissionAsked;
    private boolean locationPermissionAsked;
    private boolean locationPermissionDenied;
    private volatile boolean started;
    private volatile long acceptFramesAfterNanos;
    private volatile long resultsGeneration;
    private record Analysis(VehicleDetector.Result detections, LeadVehicleTracker.Snapshot tracking,
                            LeadVehicleMotionEstimator.Measurement motion,
                            LaneDepartureDetector.Observation lane,
                            LaneGeometry.LaneSnapshot laneGeometry,
                            AdasDecisionEngine.Decision decision, double speedKmh,
                            long speedMeasuredAtNanos, long generation) { }
    private record OverlayPresentation(Analysis analysis, AdasDecisionEngine.Decision decision,
                                       FixedGuideController.Input guide, long continuity) { }

    private long lastHeartbeatLogNanos;
    private long lastLaneLogNanos;
    /**
     * Whether the per-second lane sampling dump is emitted. {@code Log.isLoggable} lets an installer
     * turn it on with {@code adb shell setprop log.tag.HctAdasCore DEBUG} without a rebuild; by default
     * it stays off so a normal drive is not flooded.
     */
    private final boolean laneSamplingLogging = Log.isLoggable(TAG, Log.DEBUG);
    private LeadVehicleTracker.State previousTrackingState = LeadVehicleTracker.State.NONE;
    private long previousTargetId = 0L;
    private long previousDecisionTargetId;
    private volatile Analysis latestAnalysis;
    // Owned under the Activity monitor alongside latestAnalysis; display history is not audio state.
    private final VisualAlertState visualAlerts = new VisualAlertState();
    private long previousVisualSpeedTimestampNanos;
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
    private final Handler hudHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideHudControls = () -> {
        if (calibrationOverlayContainer == null
                || calibrationOverlayContainer.getVisibility() != View.VISIBLE) {
            settingsButton.setVisibility(View.GONE);
        }
    };
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
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_main);
        hideSystemStatusBar();
        View content = findViewById(R.id.adas_content);
        statusView = findViewById(R.id.status);
        clockView = findViewById(R.id.clock);
        speedView = findViewById(R.id.speed_value);
        speedPanel = findViewById(R.id.speed_panel);
        settingsButton = findViewById(R.id.settings_button);
        calibrationOverlayContainer = findViewById(R.id.calibration_overlay);
        calibrationOverlayView = findViewById(R.id.calibration_canvas);
        calibrationConfirmView = findViewById(R.id.calibration_confirm);
        calibrationConfirmView.setEnabled(false);
        calibrationConfirmView.setAlpha(0.45f);
        simulationButton = findViewById(R.id.simulation_button);
        if (!BuildConfig.DEBUG) {
            simulationButton.setVisibility(View.GONE);
        }
        previewView = findViewById(R.id.usb_preview);
        overlayView = findViewById(R.id.vehicle_overlay);
        calibrationOverlayView.setListener(new CalibrationOverlayView.Listener() {
            @Override
            public void onConfirmed(CameraCalibration confirmed) {
                confirmInstallationCalibration(confirmed);
            }

            @Override
            public void onCancelled() {
                hideCalibrationOverlay();
            }

            @Override
            public void onValidityChanged(boolean valid) {
                calibrationConfirmView.setEnabled(valid);
                calibrationConfirmView.setAlpha(valid ? 1.0f : 0.45f);
            }
        });
        // One safe viewport for preview, boxes, calibration lines and controls. This also handles
        // landscape side navigation bars and cutouts without shifting overlays away from the image.
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
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
        simulationButton.setOnClickListener(view -> showSimulationDialog());
        settingsButton.setVisibility(View.GONE);
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
            private long nextDetectorRetryNanos;
            private int detectorFailureStreak;

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
                if (System.nanoTime() < nextDetectorRetryNanos) {
                    return;
                }
                if (detector == null) {
                    try {
                        detector = new LiteRtVehicleDetector(getAssets());
                        initializationFailure = null;
                    } catch (IOException | RuntimeException | LinkageError failure) {
                        Log.e(TAG, "Vehicle model initialization failed", failure);
                        initializationFailure = new IllegalStateException(
                                "模型加载失败: " + failure.getMessage(), failure);
                        nextDetectorRetryNanos = System.nanoTime() + 5_000_000_000L;
                        throw initializationFailure;
                    }
                }
                VehicleDetector.Result result;
                try {
                    result = detector.detect(frame);
                } catch (RuntimeException | LinkageError failure) {
                    // Runtime failures are different from a bad model asset: close this interpreter
                    // before retrying, and keep any worker restart behind an exponential backoff
                    // instead of recreating a broken interpreter in a tight loop.
                    if (detector != null) {
                        try {
                            detector.close();
                        } catch (RuntimeException | LinkageError closeFailure) {
                            Log.w(TAG, "Vehicle detector cleanup failed after inference error",
                                    closeFailure);
                        } finally {
                            detector = null;
                        }
                    }
                    detectorFailureStreak = Math.min(6, detectorFailureStreak + 1);
                    detectorFailure = new IllegalStateException("模型推理失败: "
                            + failure.getMessage(), failure);
                    long delayNanos = 5_000_000_000L
                            << Math.min(3, detectorFailureStreak - 1);
                    nextDetectorRetryNanos = System.nanoTime() + delayNanos;
                    throw new IllegalStateException("Vehicle inference failed", failure);
                }
                detectorFailureStreak = 0;
                detectorFailure = null;
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
                    LaneDepartureDetector.Observation lane = AdasCalibrationMode.LDW_ENABLED
                            ? laneDetector.detect(frame.nv21(), frame.width(), frame.height())
                            : LaneDepartureDetector.Observation.UNAVAILABLE;
                    if (AdasCalibrationMode.LDW_ENABLED) {
                        logLaneSampling(lane);
                    }
                    double speed = validSpeedKmh() ? egoSpeedKmh : Double.NaN;
                    processAdasFrame(result, lane, speed, learningPitchDegrees(result.frameWidth(),
                            result.frameHeight()), frameGeneration, sessionStart, false);
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
                        if (connected && simulationSnapshot == null) {
                            reloadCalibration();
                        }
                    }
                    public void onDeviceDetached(UsbDevice device) {
                        updateCameraStatus(getString(R.string.camera_disconnected), true);
                    }

                    public void onError(String message) {
                        updateCameraStatus(message + " · 点击重试", true);
                    }
                });
        previewView.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            // A resize changes the transform pivot even if the scale is unchanged.
            appliedScaleX = Float.NaN;
            appliedScaleY = Float.NaN;
            fitCurrentPreview();
        });
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (calibrationOverlayContainer == null
                || calibrationOverlayContainer.getVisibility() != View.VISIBLE) {
            settingsButton.setVisibility(View.VISIBLE);
            hudHandler.removeCallbacks(hideHudControls);
            hudHandler.postDelayed(hideHudControls, 2_500L);
        }
    }

    /** Camera callbacks run on the main thread; real capture cannot reset a simulated session. */
    private void updateCameraStatus(CharSequence text, boolean resetResults) {
        cameraStatusText = text;
        if (simulationSnapshot != null) {
            return;
        }
        if (resetResults) {
            hideCalibrationOverlay();
            clearResults();
        }
        setStatusText(text, true);
    }

    private void setStatusText(CharSequence text, boolean hideWhenCameraReady) {
        statusView.setText(text);
        boolean hide = hideWhenCameraReady
                && getString(R.string.camera_opened).contentEquals(text);
        statusView.setVisibility(hide ? View.GONE : View.VISIBLE);
    }

    private void hideSystemStatusBar() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemStatusBar();
        }
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
            if (hasFineLocationPermission()) {
                locationPermissionDenied = false;
                startLocationUpdates();
                setStatusText(cameraStatusText == null
                        ? getString(R.string.camera_opened) : cameraStatusText, true);
            } else {
                locationPermissionDenied = true;
                egoSpeedKmh = Double.NaN;
                speedTimestampNanos = 0L;
                setStatusText("需要精确定位权限，车速相关功能已暂停", false);
            }
        }
    }

    private void ensureLocationPermission() {
        if (hasFineLocationPermission()) {
            startLocationUpdates();
        } else if (!locationPermissionAsked) {
            locationPermissionAsked = true;
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
        }
    }

    /** Diagnostic calibration heartbeat; the lightweight profile has no online lane-learning phase. */
    private void logCalibrationHeartbeat() {
        if (!AdasCalibrationMode.LDW_ENABLED) {
            return;
        }
        if (calibrationStatus == CalibrationStore.Status.CALIBRATED
                || calibrationStatus == CalibrationStore.Status.DISTANCE_READY
                || calibrationStatus == CalibrationStore.Status.UNCONFIGURED) {
            return;
        }
        CameraCalibration active = calibration;
        double configuredPitch = active == null ? Double.NaN : active.pitchDegrees();
        Log.i(TAG, AdasLogFormat.calibration(calibrationStatus.name(), calibrationProgress,
                autoCalibrationLearner.windowSize(),
                AutoCalibrationLearner.REQUIRED_CONVERGENCE_SAMPLES,
                autoCalibrationLearner.lastMeasuredRatio(), autoCalibrationLearner.lastModelRatio(),
                autoCalibrationLearner.lastSolvedPitchDegrees(), configuredPitch,
                autoCalibrationLearner.lastLaneWidthMeters(),
                autoCalibrationLearner.lastRejection().name(),
                autoCalibrationLearner.consecutiveGeometricRejections()));
    }

    /** One lane sampling dump per second while the installer has enabled verbose logging. */
    private void logLaneSampling(LaneDepartureDetector.Observation lane) {
        if (!laneSamplingLogging) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastLaneLogNanos < 1_000_000_000L) {
            return;
        }
        lastLaneLogNanos = now;
        Log.d(TAG, AdasLogFormat.laneSampling(lane));
    }

    private void renderMetrics() {
        long now = System.nanoTime();
        long captured = cameraSource.capturedFrames();
        FrameConsumer.Metrics workerSnapshot = frameConsumer.metrics();
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
            String eventDesc = AdasLogFormat.alerts(
                    heartbeat == null ? Set.of() : heartbeat.decision().events());
            // Age is mixed with the 250 ms metrics phase, so report it alongside the split at the
            // hand-off and the dequeue: the parts are phase-free and attribute latency to the
            // throttle, the queue or the pipeline.
            double currentFps = (captured - previousCaptured) * 1_000_000_000.0
                    / Math.max(1L, now - previousMetricsTime);

            Log.i(TAG, AdasLogFormat.heartbeat(currentFps, measurementAgeMs(workerSnapshot, now),
                    measurementCaptureToHandoffMs(workerSnapshot),
                    measurementQueueWaitMs(workerSnapshot),
                    measurementProcessingMs(workerSnapshot),
                    speedDesc, calibDesc, targetDesc, laneDesc, eventDesc));
            logCalibrationHeartbeat();
        }
        previousCaptured = captured;
        previousMetricsTime = now;
        AlertAudio.Status audioStatus = alertAudio == null
                ? AlertAudio.Status.UNAVAILABLE : alertAudio.status();
        Analysis analysis = latestAnalysis;
        updateClockAndSpeed(analysis);
        VehicleDetector.Result result = analysis == null ? null : analysis.detections();
        fitCurrentPreview();
        if (pendingCalibration != null) {
            calibrationOverlayView.setPreviewAvailable(calibrationFrameMatches(currentCalibrationFrame()));
        }
        RuntimeException modelFailure = initializationFailure != null
                ? initializationFailure : detectorFailure;
        boolean workerFailed = modelFailure != null || !workerSnapshot.lastError().isEmpty();
        boolean previewReady = simulator.isRunning() || currentCalibrationFrame() != null;
        OverlayPresentation presentation = snapshotPresentation(workerFailed, previewReady,
                validSpeedKmh());
        if (!simulator.isRunning() && workerFailed) {
            updateRuntimeStatus("前车检测暂不可用");
            overlayView.setResult(null, null);
        } else if (presentation != null) {
            analysis = presentation.analysis();
            result = analysis.detections();
            overlayView.setVisualContinuity(presentation.continuity());
            overlayView.setResult(result, analysis.tracking(), analysis.lane(),
                    analysis.laneGeometry(), presentation.decision(), analysis.motion().visible(),
                    Double.isFinite(presentation.guide().speedKmh()), analysis.motion());
            overlayView.setGuideInput(presentation.guide());
            updateRuntimeStatus(audioStatusText(audioStatus));
        } else {
            overlayView.setResult(null, null);
            if (result != null && result.timestampNanos() >= acceptFramesAfterNanos) {
                updateRuntimeStatus(getString(R.string.stale_detection_result));
            } else {
                updateRuntimeStatus(getString(R.string.waiting_for_frames));
            }
        }
    }

    private void updateRuntimeStatus(String runtimeStatus) {
        if (simulator.isRunning()) {
            return;
        }
        CharSequence cameraStatus = cameraStatusText == null
                ? getString(R.string.app_bootstrap_status) : cameraStatusText;
        if (!getString(R.string.camera_opened).contentEquals(cameraStatus)) {
            setStatusText(cameraStatus, false);
        } else if (runtimeStatus == null || runtimeStatus.isEmpty()) {
            if (locationPermissionDenied) {
                setStatusText("需要精确定位权限，车速相关功能已暂停", false);
            } else {
                setStatusText(cameraStatus, true);
            }
        } else {
            setStatusText(runtimeStatus, false);
        }
    }

    private static String audioStatusText(AlertAudio.Status status) {
        return switch (status) {
            case READY, LOADING -> null;
            case UNAVAILABLE -> "报警音不可用，请检查车机音频通道";
            case MUTED -> "媒体音量已静音，声音预警不可用";
        };
    }

    private void updateClockAndSpeed(Analysis analysis) {
        clockView.setText(LocalDateTime.now().format(CLOCK_FORMAT));
        double speed = analysis == null
                ? (validSpeedKmh() ? egoSpeedKmh : Double.NaN)
                : displaySpeedKmh(analysis);
        speedView.setText(Double.isFinite(speed)
                ? String.format(Locale.ROOT, "%.0f km/h", speed) : "-- km/h");
    }


    /**
     * Millisecond parts of the end-to-end measurement leg for the newest processed frame: how long
     * the frame stayed between capture and hand-off, how long it waited in the queue, and how long
     * the worker spent handling it.
     *
     * <p>All are differences between timestamps taken on the same clock, so unlike the heartbeat's
     * {@code Age} they do not carry the 250 ms metrics-tick phase. They answer different questions:
     * capture-to-hand-off is the sampling throttle plus the NV21 copy, queue wait is backlog, and
     * processing is the per-frame pipeline cost that a cheaper detection stage would reduce.
     *
     * <p>Each is exposed as its own typed accessor rather than an array: a format-string mismatch
     * between {@code long} and {@code double} throws at runtime, and every one of these values goes
     * straight into a log statement.
     */
    private static long measurementCaptureToHandoffMs(FrameConsumer.Metrics worker) {
        return elapsedMs(worker.lastTimestampNanos(), worker.offeredNanos());
    }

    private static long measurementQueueWaitMs(FrameConsumer.Metrics worker) {
        return elapsedMs(worker.offeredNanos(), worker.pickedUpNanos());
    }

    private static long measurementProcessingMs(FrameConsumer.Metrics worker) {
        return elapsedMs(worker.pickedUpNanos(), worker.finishedNanos());
    }

    /** Age of the newest processed frame at this instant, in milliseconds. */
    private static long measurementAgeMs(FrameConsumer.Metrics worker, long nowNanos) {
        return elapsedMs(worker.lastTimestampNanos(), nowNanos);
    }

    /** Non-negative difference of two {@link System#nanoTime()} readings, in milliseconds. */
    private static long elapsedMs(long fromNanos, long toNanos) {
        if (fromNanos == 0L || toNanos == 0L || toNanos < fromNanos) {
            return 0L;
        }
        return (toNanos - fromNanos) / 1_000_000L;
    }

    private double displaySpeedKmh(Analysis analysis) {
        return simulator.isRunning() ? analysis.speedKmh()
                : validSpeedKmh() && Double.isFinite(analysis.speedKmh()) ? egoSpeedKmh : Double.NaN;
    }

    private AdasDecisionEngine.Decision displayDecision(Analysis analysis, long now) {
        AdasDecisionEngine.Decision current = analysis.decision();
        return new AdasDecisionEngine.Decision(
                visualAlerts.eventsFor(analysis.tracking().trackId(), now),
                current.headwayWarning(), current.headwayCritical(), current.collisionDanger(),
                current.laneWarning());
    }

    private static boolean validVisualTarget(LeadVehicleTracker.Snapshot tracking,
                                             LeadVehicleMotionEstimator.Measurement motion) {
        return tracking != null && tracking.state() == LeadVehicleTracker.State.TRACKING
                && tracking.detection() != null && motion != null && motion.visible()
                && motion.trackId() == tracking.trackId() && Double.isFinite(motion.distanceMeters())
                && motion.distanceMeters() > 0.0;
    }

    private boolean validSpeedKmh() {
        long age = SystemClock.elapsedRealtimeNanos() - speedTimestampNanos;
        return Double.isFinite(egoSpeedKmh) && speedTimestampNanos > 0L && age >= 0L
                && age <= 2_000_000_000L && hasFineLocationPermission();
    }

    private synchronized void startLocationUpdates() {
        egoSpeedKmh = Double.NaN;
        speedTimestampNanos = 0L;
        if (locationManager == null || !hasFineLocationPermission()) {
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

    private boolean hasFineLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
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

    /** Configured pitch is available to the learner before calibration has converged. */
    private double learningPitchDegrees(int frameWidth, int frameHeight) {
        CameraCalibration active = calibration;
        if (active == null || calibrationStatus == CalibrationStore.Status.UNCONFIGURED
                || !active.isUsableFor(frameWidth, frameHeight)) {
            return Double.NaN;
        }
        return active.pitchDegrees();
    }

    /** Metric output remains gated on a converged calibration. */
    private double lanePitchDegrees(int frameWidth, int frameHeight) {
        if (calibrationStatus != CalibrationStore.Status.CALIBRATED) {
            return Double.NaN;
        }
        return learningPitchDegrees(frameWidth, frameHeight);
    }

    private synchronized void processAdasFrame(VehicleDetector.Result result,
                                               LaneDepartureDetector.Observation lane,
                                               double speed,
                                               double framePitchDegrees,
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
        CameraCalibration activeCalib = AdasCalibrationMode.distanceReady(calibrationStatus,
                calibration, result.frameWidth(), result.frameHeight()) ? calibration : null;
        LeadVehicleTracker.Snapshot tracking = tracker.update(result, lane, activeCalib);
        boolean persistCalibration = !simulationFrame;
        CameraCalibration learningCalibration = calibration;
        if (AdasCalibrationMode.LDW_ENABLED && learningCalibration != null
                && (!simulationFrame || simulator.currentScenario() == AdasSimulator.Scenario.AUTO_CALIBRATION)
                && learningCalibration.isUsableFor(result.frameWidth(), result.frameHeight())) {
            AutoCalibrationLearner.StepResult step = autoCalibrationLearner.update(
                    lane, speed, learningCalibration, framePitchDegrees,
                    result.frameWidth(), result.frameHeight());
            if (step.calibrationUpdated()) {
                calibration = step.calibration();
                calibrationStatus = step.status();
                calibrationProgress = step.progressPercent();
                activeCalib = step.status() == CalibrationStore.Status.CALIBRATED
                        ? step.calibration() : null;
                if (persistCalibration) {
                    calibrationStore.save(step.calibration(), step.status(), step.progressPercent());
                }
                postCalibrationOverlay(frameGeneration);
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
        LaneGeometry.LaneSnapshot laneGeometry = laneSnapshot(lane, result.frameWidth(),
                result.frameHeight());

        boolean hasTarget = (tracking.state() == LeadVehicleTracker.State.TRACKING
                || tracking.state() == LeadVehicleTracker.State.LOST);
        long decisionTargetId = hasTarget ? tracking.trackId() : 0L;
        if (decisionTargetId != previousDecisionTargetId) {
            decisionEngine.resetTargetState();
            previousDecisionTargetId = decisionTargetId;
            visualAlerts.clear();
            // Danger edge belongs to the previous target; a new target must re-arm its own start.
            previousCollisionDanger = false;
            dangerStartedNanos = 0L;
        }
        double decisionDistance = motion.distanceMeters();
        AdasDecisionEngine.Observation observation = new AdasDecisionEngine.Observation(
                result.timestampNanos() / 1_000_000L, speed,
                decisionDistance, motion.closingSpeedMps(),
                motion.targetAreaPixels(), motion.visible());
        double laneOffsetFraction = laneGeometry.centerOffsetLaneFraction();
        boolean currentMetricLane = lane != null && lane.available() && lane.hasWidthSamples();
        AdasDecisionEngine.LaneObservation laneObservation =
                (!AdasCalibrationMode.LDW_ENABLED
                        || (!simulationFrame && calibrationStatus != CalibrationStore.Status.CALIBRATED))
                        ? new AdasDecisionEngine.LaneObservation(0.0, 0.0, false)
                        : new AdasDecisionEngine.LaneObservation(laneOffsetFraction,
                        lane.confidence(), currentMetricLane && Double.isFinite(laneOffsetFraction));
        AdasDecisionEngine.Decision decision = decisionEngine.update(observation, laneObservation);
        // Latency instrumentation only: age is measured against the capture timestamp, so it covers
        // sampling throttle plus preprocessing and inference for this decision.
        long nowNanos = System.nanoTime();
        long resultAgeMs = Math.max(0L, nowNanos - result.timestampNanos()) / 1_000_000L;
        if (decision.collisionDanger() && !previousCollisionDanger) {
            dangerStartedNanos = nowNanos;
            double ttcSeconds = motion.closingSpeedMps() > 0.0
                    ? motion.distanceMeters() / motion.closingSpeedMps() : Double.NaN;
            Log.w(TAG, AdasLogFormat.dangerStart(resultAgeMs, motion.distanceMeters(),
                    motion.closingSpeedMps(), speed, ttcSeconds));
        }
        previousCollisionDanger = decision.collisionDanger();
        long visualSpeedMeasuredAt = simulationFrame ? SystemClock.elapsedRealtimeNanos()
                : speedTimestampNanos;
        if (previousVisualSpeedTimestampNanos > 0L
                && visualSpeedMeasuredAt - previousVisualSpeedTimestampNanos > 2_000_000_000L) {
            visualAlerts.clear();
        }
        previousVisualSpeedTimestampNanos = Double.isFinite(speed) ? visualSpeedMeasuredAt : 0L;
        // Observe invalid speed on the producer too: the UI can skip this frame before GPS recovers.
        visualAlerts.observe(decisionTargetId, validVisualTarget(tracking, motion)
                        && Double.isFinite(speed),
                decision.events(), nowNanos);
        if (!decision.events().isEmpty()) {
            long confirmationMs = dangerStartedNanos > 0L
                    ? (nowNanos - dangerStartedNanos) / 1_000_000L : -1L;
            for (AdasDecisionEngine.Alert alert : decision.events()) {
                Log.w(TAG, AdasLogFormat.alertTrigger(alert.name(), resultAgeMs, confirmationMs,
                        motion.distanceMeters(), motion.closingSpeedMps(), speed,
                        tracking.trackId()));
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
        latestAnalysis = new Analysis(result, tracking, motion, lane, laneGeometry, decision, speed,
                visualSpeedMeasuredAt,
                frameGeneration);
    }

    /**
     * Lane geometry published to the UI. Curvature and the metric offset need a converged pitch, but
     * the normalized offset is still reported beforehand so the calibration progress screen can show
     * the lane centre marker.
     *
     * <p>When a frame carries no lane, the previous measurement is returned unchanged: the overlay
     * fades it out against its own timestamp, so one dropped frame no longer makes the corridor blink.
     */
    private LaneGeometry.LaneSnapshot laneSnapshot(LaneDepartureDetector.Observation lane,
                                                   int frameWidth, int frameHeight) {
        if (!AdasCalibrationMode.LDW_ENABLED) {
            return LaneGeometry.LaneSnapshot.INVALID;
        }
        if (lane == null || !lane.available() || lane.widthSamples().isEmpty()) {
            return heldLaneGeometry;
        }
        CameraCalibration active = calibration;
        double pitch = lanePitchDegrees(frameWidth, frameHeight);
        LaneGeometry.WidthSample near = null;
        double bestDistance = Double.MAX_VALUE;
        for (LaneGeometry.WidthSample sample : lane.widthSamples()) {
            double distance = Math.abs(sample.rowY() - LaneDepartureDetector.ROI_BOTTOM_ROW);
            if (distance < bestDistance) {
                bestDistance = distance;
                near = sample;
            }
        }
        if (near == null) {
            return heldLaneGeometry;
        }
        LaneGeometry.LaneSnapshot snapshot = LaneGeometry.snapshot(
                System.currentTimeMillis(), active, pitch, near, lane.widthSamples(),
                frameWidth, frameHeight);
        heldLaneGeometry = snapshot;
        return snapshot;
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
            simulationButton.setBackgroundResource(R.drawable.hud_debug_button);
            simulationButton.setTextColor(0xFF80D8FF);
            Toast.makeText(this, "已停止模拟测试，恢复正常监测", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] scenarioNames = AdasCalibrationMode.LDW_ENABLED
                ? new String[] {
                "🔊 扬声器发声测试 (测试车机喇叭通路)",
                "1. FCW 紧急碰撞测试 (60km/h 高速前车急刹/逼近)",
                "2. HMW 极近车距测试 (25km/h 跟车贴近至 3.5m 报警)",
                "3. LDW 车道偏离测试 (65km/h 车辆压线偏离报警)",
                "4. LVSA 前车起步测试 (红灯静止等候 / 前车起步驶离)",
                "5. 行车自标定收敛测试 (自动学习灭点 0% -> 100%)"
        }
                : new String[] {
                "🔊 扬声器发声测试 (测试车机喇叭通路)",
                "1. FCW 紧急碰撞测试 (60km/h 高速前车急刹/逼近)",
                "2. HMW 极近车距测试 (25km/h 跟车贴近至 3.5m 报警)",
                "3. LVSA 前车起步测试 (红灯静止等候 / 前车起步驶离)"
        };
        AdasSimulator.Scenario[] scenarios = AdasCalibrationMode.LDW_ENABLED
                ? new AdasSimulator.Scenario[] {
                AdasSimulator.Scenario.FCW_APPROACH,
                AdasSimulator.Scenario.HMW_PROXIMITY,
                AdasSimulator.Scenario.LDW_DEPARTURE,
                AdasSimulator.Scenario.LVSA_START,
                AdasSimulator.Scenario.AUTO_CALIBRATION
        }
                : new AdasSimulator.Scenario[] {
                AdasSimulator.Scenario.FCW_APPROACH,
                AdasSimulator.Scenario.HMW_PROXIMITY,
                AdasSimulator.Scenario.LVSA_START
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
        calibration = CameraCalibration.fromWizard(width, height, HEIGHT_SEDAN_METERS, 90.0,
                INITIAL_PITCH_DEGREES);
        calibrationStatus = scenario == AdasSimulator.Scenario.AUTO_CALIBRATION
                ? CalibrationStore.Status.WIZARD_COMPLETED : CalibrationStore.Status.CALIBRATED;
        calibrationProgress = calibrationStatus == CalibrationStore.Status.CALIBRATED ? 100 : 0;
        autoCalibrationLearner.reset(calibrationStatus);
        clearResults();
        overlayView.setCalibration(calibration, calibrationStatus);

        simulationButton.setText("模拟中 (点击停止)");
        simulationButton.setBackgroundResource(R.drawable.hud_debug_active);
        simulationButton.setTextColor(0xFFFFE6E8);

        simulator.start(scenario, width, height, calibration, new AdasSimulator.Listener() {
            @Override
            public void onFrame(AdasSimulator.SimFrame simFrame) {
                setStatusText("【室内模拟】" + simFrame.description(), false);
                processAdasFrame(simFrame.detections(), simFrame.lane(), simFrame.speedKmh(),
                        learningPitchDegrees(simFrame.detections().frameWidth(),
                                simFrame.detections().frameHeight()),
                        resultsGeneration, acceptFramesAfterNanos, true);
            }

            @Override
            public void onFinished(AdasSimulator.Scenario finished) {
                restoreSimulationState();
                simulationButton.setText("室内模拟测试");
                simulationButton.setBackgroundResource(R.drawable.hud_debug_button);
                simulationButton.setTextColor(0xFF80D8FF);
                Toast.makeText(MainActivity.this, "模拟完成: " + finished.displayName(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private synchronized void restoreSimulationState() {
        SimulationSnapshot snapshot = simulationSnapshot;
        if (snapshot == null) {
            return;
        }
        simulationSnapshot = null;
        calibration = snapshot.calibration();
        calibrationStatus = snapshot.status();
        autoCalibrationLearner.reset(calibrationStatus);
        calibrationProgress = autoCalibrationLearner.progress();
        calibrationStore.saveStatus(calibrationStatus, calibrationProgress);
        clearResults();
        overlayView.setCalibration(calibration, calibrationStatus);
        setStatusText(cameraStatusText == null
                ? getString(R.string.app_bootstrap_status) : cameraStatusText, true);
    }
    /** XML click handler for the compact settings entry in the driver HUD. */
    public void openCalibrationFromSettings(View view) {
        showCalibrationDialog();
    }

    public void confirmCalibrationOverlay(View view) {
        calibrationOverlayView.setPreviewAvailable(calibrationFrameMatches(currentCalibrationFrame()));
        calibrationOverlayView.confirm();
    }

    public void cancelCalibrationOverlay(View view) {
        calibrationOverlayView.cancel();
    }

    private void showCalibrationDialog() {
        if (settingsDialog != null || pendingCalibration != null) {
            return;
        }
        if (simulator.isRunning()) {
            Toast.makeText(this, "请先停止模拟", Toast.LENGTH_SHORT).show();
            return;
        }
        UsbCameraSource.PreviewSnapshot frame = currentCalibrationFrame();

        View content = getLayoutInflater().inflate(R.layout.dialog_adas_settings, null);
        RadioButton rbSedan = content.findViewById(R.id.rb_sedan);
        RadioButton rbSuv = content.findViewById(R.id.rb_suv);
        RadioButton rbTruck = content.findViewById(R.id.rb_truck);
        RadioButton rb90 = content.findViewById(R.id.rb_fov_90);
        RadioButton rb100 = content.findViewById(R.id.rb_fov_100);
        RadioButton rb120 = content.findViewById(R.id.rb_fov_120);
        TextView calibrationStatusView = content.findViewById(R.id.calibration_status);
        View calibrationAction = content.findViewById(R.id.calibration_action);

        rbSedan.setChecked(true);
        if (calibration != null) {
            double cameraHeight = calibration.cameraHeightMeters();
            if (Math.abs(cameraHeight - HEIGHT_SUV_METERS) < 0.05) {
                rbSuv.setChecked(true);
            } else if (Math.abs(cameraHeight - HEIGHT_TRUCK_METERS) < 0.05) {
                rbTruck.setChecked(true);
            }
        }

        rb90.setChecked(true);
        if (calibration != null) {
            double currentFov = Math.toDegrees(2.0 * Math.atan(
                    (calibration.imageWidth() / 2.0)
                            / (calibration.focalLengthYNormalized() * calibration.imageHeight())));
            if (Math.abs(currentFov - 120.0) < Math.abs(currentFov - 100.0)
                    && Math.abs(currentFov - 120.0) < Math.abs(currentFov - 90.0)) {
                rb120.setChecked(true);
            } else if (Math.abs(currentFov - 100.0) < Math.abs(currentFov - 90.0)) {
                rb100.setChecked(true);
            }
        }

        calibrationStatusView.setText(calibration == null ? "未校准"
                : frame == null ? "待连接"
                : AdasCalibrationMode.distanceReady(calibrationStatus, calibration,
                        frame.frameWidth(), frame.frameHeight()) ? "已校准" : "需校准");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .setNegativeButton("关闭", null)
                .create();
        settingsDialog = dialog;
        overlayView.setGuideSettingsOpen(true);
        dialog.setOnDismissListener(ignored -> {
            settingsDialog = null;
            overlayView.setGuideSettingsOpen(false);
        });
        calibrationAction.setOnClickListener(view -> {
            UsbCameraSource.PreviewSnapshot currentFrame = currentCalibrationFrame();
            if (currentFrame == null) {
                Toast.makeText(this, "请等待实时摄像头画面", Toast.LENGTH_SHORT).show();
                return;
            }
            double heightMeters = rbSuv.isChecked() ? HEIGHT_SUV_METERS
                    : rbTruck.isChecked() ? HEIGHT_TRUCK_METERS : HEIGHT_SEDAN_METERS;
            double hfov = rb120.isChecked() ? 120.0 : rb100.isChecked() ? 100.0 : 90.0;
            CameraCalibration previous = calibration;
            boolean sameSize = previous != null && previous.isUsableFor(
                    currentFrame.frameWidth(), currentFrame.frameHeight());
            // Always build with the newly selected lens. Preview and confirmation share this draft.
            CameraCalibration lens = CameraCalibration.fromWizard(currentFrame.frameWidth(),
                    currentFrame.frameHeight(), heightMeters, hfov,
                    sameSize ? previous.pitchDegrees() : INITIAL_PITCH_DEGREES);
            pendingCalibration = new CameraCalibration(lens.imageWidth(), lens.imageHeight(),
                    lens.cameraHeightMeters(), lens.focalLengthYNormalized(),
                    lens.principalPointYNormalized(), lens.pitchDegrees(),
                    sameSize ? previous.guideCenterXNormalized() : 0.5);
            pendingCalibrationGeneration = resultsGeneration;
            pendingCalibrationSession = currentFrame.sessionId();
            fitPreview(currentFrame.frameWidth(), currentFrame.frameHeight());
            dialog.dismiss();
            showCalibrationOverlay();
        });
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(0x00000000));
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                window.setGravity(Gravity.CENTER);
                window.setWindowAnimations(0);
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int maxWidth = (int) (380 * getResources().getDisplayMetrics().density);
                window.setLayout(Math.min(maxWidth, (int) (screenWidth * 0.88f)),
                        WindowManager.LayoutParams.WRAP_CONTENT);
            }
            Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            negative.setTextColor(0xFF9AA5B1);
        });
        dialog.show();
    }

    private void showCalibrationOverlay() {
        overlayView.setGuideInput(null);
        calibrationOverlayView.setCalibration(pendingCalibration);
        calibrationOverlayView.setPreviewAvailable(calibrationFrameMatches(currentCalibrationFrame()));
        calibrationOverlayContainer.setVisibility(View.VISIBLE);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, cancelCalibrationOnBack);
        hudHandler.removeCallbacks(hideHudControls);
        settingsButton.setVisibility(View.GONE);
        simulationButton.setVisibility(View.GONE);
        overlayView.setVisibility(View.INVISIBLE);
        findViewById(R.id.top_info).setVisibility(View.INVISIBLE);
        speedPanel.setVisibility(View.INVISIBLE);
    }

    private void hideCalibrationOverlay() {
        if (calibrationOverlayContainer.getVisibility() == View.VISIBLE) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(cancelCalibrationOnBack);
        }
        pendingCalibration = null;
        calibrationOverlayView.setPreviewAvailable(false);
        calibrationOverlayContainer.setVisibility(View.GONE);
        settingsButton.setVisibility(View.GONE);
        simulationButton.setVisibility(BuildConfig.DEBUG ? View.VISIBLE : View.GONE);
        overlayView.setVisibility(View.VISIBLE);
        findViewById(R.id.top_info).setVisibility(View.VISIBLE);
        speedPanel.setVisibility(View.VISIBLE);
    }

    private synchronized void confirmInstallationCalibration(CameraCalibration next) {
        if (!calibrationFrameMatches(currentCalibrationFrame())) {
            calibrationOverlayView.setPreviewAvailable(false);
            Toast.makeText(this, "画面已变化，请重新校准", Toast.LENGTH_SHORT).show();
            return;
        }
        applyCameraCalibration(next);
        hideCalibrationOverlay();
        Toast.makeText(this, "ADAS 校准已保存", Toast.LENGTH_SHORT).show();
    }

    /** Use only a real, fresh frame; never guess 720p when the stream is not available. */
    private UsbCameraSource.PreviewSnapshot currentCalibrationFrame() {
        if (!started || simulator.isRunning() || !previewView.isAvailable()) {
            return null;
        }
        UsbCameraSource.PreviewSnapshot frame = cameraSource.previewSnapshot();
        if (frame == null) {
            return null;
        }
        long age = System.nanoTime() - frame.timestampNanos();
        return frame.timestampNanos() >= acceptFramesAfterNanos && age >= 0L
                && age <= LeadVehicleTracker.MAX_OBSERVATION_GAP_NANOS ? frame : null;
    }

    private boolean calibrationFrameMatches(UsbCameraSource.PreviewSnapshot frame) {
        return pendingCalibration != null && frame != null
                && pendingCalibrationGeneration == resultsGeneration
                && pendingCalibrationSession == frame.sessionId()
                && pendingCalibration.isUsableFor(frame.frameWidth(), frame.frameHeight());
    }

    private synchronized void applyCameraCalibration(CameraCalibration next) {
        calibration = next;
        calibrationStatus = next == null ? CalibrationStore.Status.UNCONFIGURED
                : CalibrationStore.Status.DISTANCE_READY;
        calibrationProgress = next == null ? 0 : 100;
        autoCalibrationLearner.reset(calibrationStatus);
        clearResults();
        if (next == null) {
            calibrationStore.clear();
        } else {
            calibrationStore.save(next, calibrationStatus, calibrationProgress);
        }
        overlayView.setCalibration(calibration, calibrationStatus);
    }

    /** Reloads the resolution-bound installation profile after the camera stream opens. */
    private synchronized void reloadCalibration() {
        calibration = calibrationStore.load();
        calibrationStatus = calibrationStore.loadStatus();
        calibrationProgress = calibrationStore.loadProgress();
        autoCalibrationLearner.reset(calibrationStatus);
        overlayView.setCalibration(calibration, calibrationStatus);
    }

    /**
     * Letterboxes the preview to the frame aspect ratio. The scale factors only change when the view
     * size or the frame size changes, so identical inputs are skipped: this runs from the 250 ms
     * metrics tick and a redundant setTransform would invalidate the view four times a second.
     */
    private void fitCurrentPreview() {
        UsbCameraSource.PreviewSnapshot frame = cameraSource.previewSnapshot();
        if (frame != null) {
            fitPreview(frame.frameWidth(), frame.frameHeight());
        }
    }

    /** Copy one coherent observation. No UI, logging or audio-service calls while holding this lock. */
    private synchronized OverlayPresentation snapshotPresentation(boolean workerFailed,
                                                                  boolean previewReady, boolean gpsReady) {
        long now = System.nanoTime();
        Analysis analysis = latestAnalysis;
        VehicleDetector.Result result = analysis == null ? null : analysis.detections();
        boolean simulation = simulator.isRunning();
        boolean fresh = result != null && analysis.generation() == resultsGeneration
                && result.timestampNanos() >= acceptFramesAfterNanos
                && now - result.timestampNanos() >= 0L
                && now - result.timestampNanos() <= LeadVehicleTracker.MAX_OBSERVATION_GAP_NANOS;
        if (!started || !fresh || !previewReady || (!simulation && workerFailed)) {
            visualAlerts.clear();
            return null;
        }
        // GPS may recover before the producer publishes another analysis. A new fix must not
        // revive the old observation's risk/hold across a gap the UI never sampled.
        boolean continuousSpeed = simulation
                || speedTimestampNanos - analysis.speedMeasuredAtNanos() <= 2_000_000_000L;
        double speed = simulation ? analysis.speedKmh()
                : gpsReady && continuousSpeed && Double.isFinite(analysis.speedKmh())
                        ? egoSpeedKmh : Double.NaN;
        if (!Double.isFinite(speed)) {
            visualAlerts.clear();
        }
        AdasDecisionEngine.Decision shown = continuousSpeed ? displayDecision(analysis, now)
                : new AdasDecisionEngine.Decision(Set.of(), false, false, false, false);
        boolean distanceReady = AdasCalibrationMode.distanceReady(calibrationStatus, calibration,
                result.frameWidth(), result.frameHeight());
        FixedGuideController.Input input = new FixedGuideController.Input(analysis.generation(),
                result.timestampNanos(), true, distanceReady, started, pendingCalibration != null,
                settingsDialog != null, speed,
                simulation ? analysis.speedMeasuredAtNanos() : speedTimestampNanos,
                analysis.tracking().state(), validVisualTarget(analysis.tracking(), analysis.motion()),
                shown.headwayWarning(), shown.collisionDanger() || shown.headwayCritical()
                || shown.events().contains(AdasDecisionEngine.Alert.FCW)
                || shown.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL));
        return new OverlayPresentation(analysis, shown, input, visualAlerts.continuity());
    }

    private void fitPreview(int frameWidth, int frameHeight) {
        if (previewView.getWidth() == 0 || previewView.getHeight() == 0) {
            return;
        }
        CalibrationAlignment.Viewport viewport = CalibrationAlignment.fitCenter(
                previewView.getWidth(), previewView.getHeight(), frameWidth, frameHeight);
        float scaleX = (float) (viewport.width() / previewView.getWidth());
        float scaleY = (float) (viewport.height() / previewView.getHeight());
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
        heldLaneGeometry = LaneGeometry.LaneSnapshot.INVALID;
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
        visualAlerts.clear();
        previousVisualSpeedTimestampNanos = 0L;
    }

    /**
     * Analysis only runs while this activity is started: the camera, the worker and the speed feed
     * are all released here and rebuilt in {@link #onStart()}.
     *
     * <p>This prototype therefore depends on the head unit keeping it in the foreground. No wake
     * lock and no foreground service are held, so a device that lets the display sleep would throttle
     * the CPU while frames still trickle in - the pipeline would keep reporting "fresh" results at a
     * collapsing rate instead of raising any alarm. The target head unit never sleeps, which makes
     * that path unreachable here; re-check the assumption before running on any other host.
     */
    @Override
    protected void onStop() {
        if (settingsDialog != null) {
            settingsDialog.dismiss();
        }
        hideCalibrationOverlay();
        hudHandler.removeCallbacks(hideHudControls);
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
        hudHandler.removeCallbacks(hideHudControls);
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
