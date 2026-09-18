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
    /**
     * Fallback installation pitch when the bracket angle is unknown. A windshield-inside mount just
     * below the factory forward camera typically looks 6-10 degrees down at the road ahead; starting at
     * 4 degrees would leave every drive drifting in from too shallow an angle. The learner confirms the
     * value once lane markings are seen and reports the deviation the installer has to correct.
     */
    private static final double INITIAL_PITCH_DEGREES = 8.0;
    private static final long LANE_SNAPSHOT_HOLD_MILLIS = 1_200L;
    /**
     * Camera height above ground for the presets the wizard offers. These describe where the lens sits
     * for a windshield-inside mount just below the factory forward camera, which is the installation
     * this product targets; the truck value follows the same position on a much taller windshield.
     */
    private static final double HEIGHT_SEDAN_METERS = 1.30;
    private static final double HEIGHT_SUV_METERS = 1.55;
    private static final double HEIGHT_TRUCK_METERS = 2.00;
    /**
     * Bounds accepted by the wizard. The upper bound mirrors the lane ROI guard at the target mounting
     * height (~14.4 deg): accepting a steeper angle would store a calibration whose every frame is then
     * rejected for looking at the hood instead of the road.
     */
    private static final double MIN_INITIAL_PITCH_DEGREES = -5.0;
    private static final double MAX_INITIAL_PITCH_DEGREES = 14.0;
    /**
     * Pitch at which the wizard warns that the mounting angle is near the limit the lane sampling band
     * can still see: at 1.3 m of mounting height the ROI's far row drops under its minimum ground
     * distance at about 14.4 degrees. Deliberately derived from the ROI guard rather than from a
     * hand-picked number, which is what produced the earlier false "angle out of range" reports.
     */
    private static final double STEEP_PITCH_WARNING_DEGREES = 14.0;
    private FrameDispatcher frameDispatcher;
    private FrameConsumer frameConsumer;
    private volatile RuntimeException initializationFailure;
    private volatile RuntimeException detectorFailure;
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
    /** Last lane measurement, kept so the overlay can fade a dropped lane instead of blinking. */
    private LaneGeometry.LaneSnapshot heldLaneGeometry = LaneGeometry.LaneSnapshot.INVALID;
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
                            LaneGeometry.LaneSnapshot laneGeometry,
                            AdasDecisionEngine.Decision decision, double speedKmh) { }

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
                                      CalibrationStore.Status status,
                                      String cameraId) { }
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
                    LaneDepartureDetector.Observation lane = laneDetector.detect(
                            frame.nv21(), frame.width(), frame.height());
                    logLaneSampling(lane);
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
            if (hasFineLocationPermission()) {
                startLocationUpdates();
            } else {
                egoSpeedKmh = Double.NaN;
                speedTimestampNanos = 0L;
                statusView.setText("需要精确定位权限，车速相关功能已暂停");
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

    /** One calibration line per second while the pitch is being learned, independent of the sample count. */
    private void logCalibrationHeartbeat() {
        if (calibrationStatus == CalibrationStore.Status.CALIBRATED
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
        double fps = (captured - previousCaptured) * 1_000_000_000.0
                / Math.max(1L, now - previousMetricsTime);
        previousCaptured = captured;
        previousMetricsTime = now;
        FrameDispatcher.Metrics queue = frameDispatcher.metrics();
        FrameConsumer.Metrics worker = workerSnapshot;
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
        RuntimeException modelFailure = initializationFailure != null
                ? initializationFailure : detectorFailure;
        String workerError = modelFailure == null ? worker.lastError() : modelFailure.getMessage();
        if (!simulator.isRunning() && !workerError.isEmpty()) {
            metricsView.setText(stream + "\n" + workerError);
            overlayView.setResult(null, null);
        } else if (fresh) {
            fitPreview(result);
            AdasDecisionEngine.Decision shown = displayDecision(analysis, now);
            overlayView.setResult(result, analysis.tracking(), analysis.lane(),
                    analysis.laneGeometry(), shown, analysis.motion().visible(),
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
        return getString(R.string.measurement_status, distance, ttcText,
                speedText + " · " + warning + laneWarning + " · " + lane)
                + " · " + composeReadouts(analysis, shown) + " · " + getString(R.string.estimated_note);
    }

    private double displaySpeedKmh(Analysis analysis) {
        return simulator.isRunning() ? analysis.speedKmh()
                : validSpeedKmh() && Double.isFinite(analysis.speedKmh()) ? egoSpeedKmh : Double.NaN;
    }
    /**
     * The three driver-facing readouts (FCWS, LDWS, LKAS) as fixed-width lines so the values line up
     * while the text changes length. Wording and colours come from {@link AdasUiStatusMapper}; no
     * decision threshold is evaluated here.
     */
    private String composeReadouts(Analysis analysis, AdasDecisionEngine.Decision shown) {
        double ttc = Double.NaN;
        double distance = Double.NaN;
        boolean targetVisible = false;
        if (analysis != null && analysis.motion() != null && analysis.motion().visible()) {
            targetVisible = true;
            distance = analysis.motion().distanceMeters();
            if (analysis.motion().closingSpeedMps() > 0.0) {
                ttc = distance / analysis.motion().closingSpeedMps();
            }
        }
        double speed = analysis == null ? Double.NaN : displaySpeedKmh(analysis);
        AdasUiStatusMapper.Status status = AdasUiStatusMapper.forward(shown, targetVisible,
                ttc, distance, speed, shown.headwayWarning());
        LaneGeometry.LaneSnapshot lane = analysis == null ? null : analysis.laneGeometry();
        if (lane != null && (lane.timestampNanos() <= 0L
                || System.currentTimeMillis() - lane.timestampNanos() > LANE_SNAPSHOT_HOLD_MILLIS)) {
            lane = null;
        }
        boolean laneSupported = calibrationStatus == CalibrationStore.Status.CALIBRATED
                || simulator.isRunning();
        AdasUiStatusMapper.LaneStatus laneStatus = AdasUiStatusMapper.lane(lane, laneSupported,
                shown.laneWarning());
        return "\nFCWS " + status.riskText() + " (" + status.detail() + ")"
                + "\nLDWS " + laneStatus.departure() + AdasUiStatusMapper.laneDetail(lane)
                + "\nLKAS " + laneStatus.keeping();
    }

    /** Risk band of the current decision, used for the colour of the overlay text. */
    private int riskColor(AdasDecisionEngine.Decision decision, Analysis analysis) {
        double ttc = Double.NaN;
        double distance = Double.NaN;
        boolean targetVisible = false;
        if (analysis != null && analysis.motion() != null && analysis.motion().visible()) {
            targetVisible = true;
            distance = analysis.motion().distanceMeters();
            if (analysis.motion().closingSpeedMps() > 0.0) {
                ttc = distance / analysis.motion().closingSpeedMps();
            }
        }
        return AdasUiStatusMapper.forward(decision, targetVisible, ttc, distance,
                analysis == null ? Double.NaN : displaySpeedKmh(analysis),
                decision.headwayWarning()).riskColor();
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

    private void updateCalibrationStatus(VehicleDetector.Result result) {
        if (calibration == null || calibrationStatus == CalibrationStore.Status.UNCONFIGURED) {
            calibrationView.setText(R.string.calibration_unset);
            calibrationView.setTextColor(0xFFFFD180);
        } else if (result != null && !calibration.isUsableFor(result.frameWidth(), result.frameHeight())) {
            calibrationView.setText(getString(R.string.calibration_size_mismatch,
                    calibration.imageWidth(), calibration.imageHeight()));
            calibrationView.setTextColor(0xFFFF8A80);
        } else if (calibrationStatus == CalibrationStore.Status.WIZARD_COMPLETED) {
            if (showAngleOutOfRangeHint() || showLaneObservationHint()) {
                return;
            }
            calibrationView.setText(getString(R.string.calibration_wizard_done,
                    calibration.cameraHeightMeters()) + calibrationProgressDetail());
            calibrationView.setTextColor(0xFF80DEEA);
        } else if (calibrationStatus == CalibrationStore.Status.CALIBRATING) {
            if (showAngleOutOfRangeHint() || showLaneObservationHint()) {
                return;
            }
            calibrationView.setText(getString(R.string.calibration_in_progress,
                    calibrationProgress) + calibrationProgressDetail());
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
     *
     * <p>Only a genuinely collapsed ROI may raise this hint. A lane observation that merely cannot be
     * solved is a different failure and is reported separately, because telling the user to re-aim a
     * correctly mounted camera is worse than saying nothing.
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

    /**
     * Explains why an observation was dropped without asking the user to touch the mounting angle,
     * except when the solved pitch disagrees with the configured one by more than the tolerance.
     */
    private boolean showLaneObservationHint() {
        AutoCalibrationLearner.Rejection rejection = autoCalibrationLearner.lastRejection();
        if (rejection == AutoCalibrationLearner.Rejection.OBSERVATION_INCOHERENT) {
            double solved = autoCalibrationLearner.lastSolvedPitchDegrees();
            CameraCalibration active = calibration;
            if (Double.isFinite(solved) && active != null
                    && Math.abs(solved - active.pitchDegrees())
                    > AutoCalibrationLearner.MAX_PITCH_DEVIATION_DEGREES) {
                calibrationView.setText(getString(R.string.calibration_reaim,
                        solved - active.pitchDegrees(), solved));
                calibrationView.setTextColor(0xFFFF8A80);
                return true;
            }
            calibrationView.setText(R.string.calibration_observation_inconsistent);
            calibrationView.setTextColor(0xFFFFD180);
            return true;
        }
        if (rejection == AutoCalibrationLearner.Rejection.DRIVING_CONDITION) {
            calibrationView.setText(R.string.calibration_waiting_conditions);
            calibrationView.setTextColor(0xFF80DEEA);
            return true;
        }
        return false;
    }

    /** Compact progress line: window fill plus the measured/model lane width ratio. */
    private String calibrationProgressDetail() {
        StringBuilder builder = new StringBuilder();
        double measured = autoCalibrationLearner.lastMeasuredRatio();
        double model = autoCalibrationLearner.lastModelRatio();
        if (Double.isFinite(measured)) {
            builder.append(String.format(Locale.ROOT, " · 车道宽比 %.2f", measured));
            if (Double.isFinite(model)) {
                builder.append(String.format(Locale.ROOT, " (模型 %.2f)", model));
            }
        }
        double solved = autoCalibrationLearner.lastSolvedPitchDegrees();
        if (Double.isFinite(solved)) {
            builder.append(String.format(Locale.ROOT, " · 解算俯仰 %.2f°", solved));
        }
        return builder.toString();
    }

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
        LeadVehicleTracker.Snapshot tracking = tracker.update(result, lane);
        CameraCalibration activeCalib = calibrationStatus == CalibrationStore.Status.CALIBRATED
                ? calibration : null;
        boolean persistCalibration = !simulationFrame;
        CameraCalibration learningCalibration = calibration;
        if (learningCalibration != null
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
                    String currentId = cameraSource != null ? cameraSource.currentCameraId() : "";
                    calibrationStore.save(step.calibration(), step.status(), step.progressPercent(), currentId);
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
        double laneOffsetFraction = laneGeometry.centerOffsetLaneFraction();
        boolean currentMetricLane = lane != null && lane.available() && lane.hasWidthSamples();
        AdasDecisionEngine.LaneObservation laneObservation =
                (!simulationFrame && calibrationStatus != CalibrationStore.Status.CALIBRATED)
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
        if (!decision.events().isEmpty()) {
            heldAlerts = decision.events();
            heldAlertTargetId = decisionTargetId;
            alertsHoldUntilNanos = nowNanos + 1_500_000_000L;
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
        latestAnalysis = new Analysis(result, tracking, motion, lane, laneGeometry, decision, speed);
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
            simulationSnapshot = new SimulationSnapshot(calibration, calibrationStatus,
                    cameraSource == null ? "" : cameraSource.currentCameraId());
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
        simulationButton.setBackgroundColor(0xFFC62828);

        simulator.start(scenario, width, height, calibration, new AdasSimulator.Listener() {
            @Override
            public void onFrame(AdasSimulator.SimFrame simFrame) {
                statusView.setText("【室内模拟】" + simFrame.description());
                processAdasFrame(simFrame.detections(), simFrame.lane(), simFrame.speedKmh(),
                        learningPitchDegrees(simFrame.detections().frameWidth(),
                                simFrame.detections().frameHeight()),
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
        simulationSnapshot = null;
        String currentCameraId = cameraSource == null ? "" : cameraSource.currentCameraId();
        boolean sameCamera = !snapshot.cameraId().isEmpty()
                && snapshot.cameraId().equals(currentCameraId);
        if (sameCamera) {
            calibration = snapshot.calibration();
            calibrationStatus = snapshot.status();
            autoCalibrationLearner.reset(calibrationStatus);
            calibrationProgress = autoCalibrationLearner.progress();
            calibrationStore.saveStatus(calibrationStatus, calibrationProgress);
        } else {
            // Never restore a simulation's pre-existing calibration to another or unidentified
            // camera. A stable camera may load only its own persisted record; an unidentified camera
            // stays uncalibrated and drops stale storage.
            calibration = null;
            calibrationStatus = CalibrationStore.Status.UNCONFIGURED;
            calibrationProgress = 0;
            autoCalibrationLearner.reset(calibrationStatus);
            if (currentCameraId.isEmpty()) {
                calibrationStore.clear();
            } else {
                CameraCalibration stored = calibrationStore.load(currentCameraId);
                if (stored != null) {
                    calibration = stored;
                    calibrationStatus = calibrationStore.loadStatus();
                    calibrationProgress = calibrationStore.loadProgress();
                    autoCalibrationLearner.reset(calibrationStatus);
                }
            }
        }
        clearResults();
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
        vehicleLabel.setText("1. 安装位置（镜头镜片离地高度）:");
        vehicleLabel.setTextSize(14f);
        vehicleLabel.setTextColor(0xFFFFFFFF);
        form.addView(vehicleLabel);

        RadioGroup vehicleGroup = new RadioGroup(this);
        vehicleGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbSedan = new RadioButton(this);
        rbSedan.setText("轿车 1.30m");
        RadioButton rbSuv = new RadioButton(this);
        rbSuv.setText("SUV 1.55m");
        RadioButton rbTruck = new RadioButton(this);
        rbTruck.setText("货车 2.00m");
        RadioButton rbCustom = new RadioButton(this);
        rbCustom.setText("自定义");
        vehicleGroup.addView(rbSedan);
        vehicleGroup.addView(rbSuv);
        vehicleGroup.addView(rbTruck);
        vehicleGroup.addView(rbCustom);
        form.addView(vehicleGroup);

        EditText customHeightField = numberField("镜头镜片到地面的垂直距离(米，如 1.35)");
        customHeightField.setVisibility(View.GONE);
        form.addView(customHeightField);

        rbSedan.setChecked(true);
        if (calibration != null) {
            double h = calibration.cameraHeightMeters();
            if (Math.abs(h - HEIGHT_SEDAN_METERS) < 0.05) {
                rbSedan.setChecked(true);
            } else if (Math.abs(h - HEIGHT_SUV_METERS) < 0.05) {
                rbSuv.setChecked(true);
            } else if (Math.abs(h - HEIGHT_TRUCK_METERS) < 0.05) {
                rbTruck.setChecked(true);
            } else {
                rbCustom.setChecked(true);
                customHeightField.setVisibility(View.VISIBLE);
                customHeightField.setText(Double.toString(h));
            }
        }
        TextView heightNote = new TextView(this);
        heightNote.setText("• 挡风玻璃内侧、原车前视摄像头下方：轿车约 1.30m，SUV 约 1.55m，货车约 2.00m\n"
                + "• 高度只影响距离尺度，粗选即可：偏 0.2m 约带来 16% 的距离偏差");
        heightNote.setTextSize(12f);
        heightNote.setTextColor(0xFFB0BEC5);
        form.addView(heightNote);
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

        TextView fovNote = new TextView(this);
        fovNote.setText("• 查镜头规格书；查不到先用 90°\n"
                + "• 填错的后果：车道宽读数会长期偏大（HFOV 填小了）或偏小（填大了），据此回改");
        fovNote.setTextSize(12f);
        fovNote.setTextColor(0xFFB0BEC5);
        form.addView(fovNote);

        TextView pitchLabel = new TextView(this);
        pitchLabel.setText("\n3. 初始俯仰角 (度，向下为正):");
        pitchLabel.setTextSize(14f);
        pitchLabel.setTextColor(0xFFFFFFFF);
        form.addView(pitchLabel);

        EditText pitchField = numberField("默认 8.0（挡风玻璃内侧安装的典型值）");
        double pitchToShow = calibration != null && calibration.isUsableFor(width, height)
                ? calibration.pitchDegrees() : INITIAL_PITCH_DEGREES;
        pitchField.setText(Double.toString(pitchToShow));
        form.addView(pitchField);

        TextView pitchNote = new TextView(this);
        pitchNote.setText("• 知道支架角度就直接填；不知道保持 8.0 即可\n"
                + "• 上路后本机会用车道线测出实际角度，若偏差超过 ±4° 会提示你调多少度");
        pitchNote.setTextSize(12f);
        pitchNote.setTextColor(0xFFB0BEC5);
        form.addView(pitchNote);

        TextView guideNote = new TextView(this);
        guideNote.setText("\n4. 物理对准提示:\n• 调整镜头使车头机盖露出在屏幕下方参考线处\n"
                + "• 确保道路远方处于中间黄色地平线附近\n"
                + "• 拧紧支架螺丝保存后，上路行驶几分钟即可完成验证");
        guideNote.setTextSize(12f);
        guideNote.setTextColor(0xFFB0BEC5);
        form.addView(guideNote);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(form);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("ADAS 摄像头安装向导（" + width + "×" + height + "）")
                .setView(scrollView)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存并开始验证", (dialog, which) -> {
                    try {
                        double heightMeters;
                        if (rbCustom.isChecked()) {
                            heightMeters = parse(customHeightField);
                        } else if (rbSuv.isChecked()) {
                            heightMeters = HEIGHT_SUV_METERS;
                        } else if (rbTruck.isChecked()) {
                            heightMeters = HEIGHT_TRUCK_METERS;
                        } else {
                            heightMeters = HEIGHT_SEDAN_METERS;
                        }

                        double hfov = rb120.isChecked() ? 120.0 : (rb100.isChecked() ? 100.0 : 90.0);
                        double initialPitch = parsePitchDegrees(pitchField);

                        CameraCalibration next = CameraCalibration.fromWizard(
                                width, height, heightMeters, hfov, initialPitch);

                        applyCameraCalibration(next);
                        Toast.makeText(this, initialPitch >= STEEP_PITCH_WARNING_DEGREES
                                ? "向导配置已保存！俯仰角偏大，若标定栏提示需调整角度请按提示微调支架"
                                : "向导配置已保存！请上路以 >35km/h 在本车道居中行驶几分钟完成验证",
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
        overlayView.setCalibration(calibration, calibrationStatus);
        updateCalibrationStatus(null);
    }

    /**
     * Rebinds calibration state once a camera has actually opened. A stored calibration without a
     * recorded camera id cannot be attributed to the device on the other end of the cable, so it is
     * treated as unbound and dropped rather than adopted: keeping it would silently apply one
     * camera's pitch to another. This project is still in its test phase, so the resulting one-off
     * re-calibration on upgrade is accepted instead of migrating the old record; see
     * {@code CalibrationStore} for that note. A connected camera without a stable serial is also
     * fail-closed: its metric calibration is cleared because the next attachment could be a
     * different identical UVC device.
     */
    private synchronized void reloadCalibrationForCamera() {
        String currentId = cameraSource == null ? "" : cameraSource.currentCameraId();
        if (currentId.isEmpty()) {
            if (calibration != null || calibrationStatus != CalibrationStore.Status.UNCONFIGURED) {
                Log.w(TAG, "[CALIB] Camera has no stable serial; invalidating metric calibration");
                applyCameraCalibration(null);
            }
            return;
        }
        CameraCalibration stored = calibrationStore.load(currentId);
        if (stored != null) {
            calibration = stored;
            calibrationStatus = calibrationStore.loadStatus();
            calibrationProgress = calibrationStore.loadProgress();
            autoCalibrationLearner.reset(calibrationStatus);
            overlayView.setCalibration(calibration, calibrationStatus);
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
        heldAlerts = Set.of();
        alertsHoldUntilNanos = 0L;
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
