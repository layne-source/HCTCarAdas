package com.hct.adas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import java.util.Locale;
import java.util.List;

/** Displays source-normalized detections in the same cropped viewport as the preview. */
public final class VehicleOverlayView extends View {
    private static final String TAG = "HctAdasCore";
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint regionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laneFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laneLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetLabelBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warningMarkerFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warningMarkerStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warningMarkerIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path warningMarkerPath = new Path();
    private VehicleDetector.Result result;
    private LeadVehicleTracker.Snapshot tracking;
    private CameraCalibration calibration;
    private CalibrationStore.Status calibrationStatus = CalibrationStore.Status.UNCONFIGURED;
    private LaneDepartureDetector.Observation lane;
    private LaneGeometry.LaneSnapshot laneSnapshot;
    private AdasDecisionEngine.Decision decision;
    private LeadVehicleMotionEstimator.Measurement motion;
    private boolean measurementAvailable;
    private boolean speedAvailable;
    private static final int GUIDE_GREEN = 0xFF36D99C;
    private static final int GUIDE_YELLOW = 0xFFF2BA49;
    private static final int GUIDE_RED = 0xFFFF525E;
    private static final int SECONDARY_VEHICLE_COLOR = 0xFFB0BEC5;
    private static final float TARGET_BOX_STROKE_DP = 4f;
    private static final float SECONDARY_BOX_STROKE_DP = 2.5f;
    private static final float CORNER_LENGTH_RATIO = 0.24f;
    private static final float CORNER_LENGTH_MAX_DP = 32f;
    private static final long COLOR_FADE_NANOS = 250_000_000L;
    private static final long ARROW_CYCLE_NANOS = 1_800_000_000L;
    private final FixedGuideController guideController = new FixedGuideController();
    private final FixedGuideRenderer guideRenderer;
    private FixedGuideController.Input guideInput;
    private final RectF targetBounds = new RectF();
    private final RectF targetLabelBounds = new RectF();
    private CalibrationAlignment.Viewport imageViewport;
    private int viewportFrameWidth;
    private int viewportFrameHeight;
    private int viewportViewWidth;
    private int viewportViewHeight;
    private String targetLabel = "";
    private float targetLabelBaseline;
    private boolean guideSettingsOpen;
    private boolean animationScheduled;
    private boolean arrowsMoving;
    private long arrowStartNanos;
    private long visualContinuity;
    private long colorTargetId;
    private int displayColor = GUIDE_GREEN;
    private int colorFrom = GUIDE_GREEN;
    private int colorTo = GUIDE_GREEN;
    private long colorStartNanos;
    private boolean colorFading;
    private final Runnable animationTick = () -> {
        animationScheduled = false;
        if (isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE) {
            invalidate();
        }
    };
    /**
     * How long a dropped lane keeps being drawn as a faded corridor, in milliseconds. Long enough that
     * a few dropped frames are invisible, short enough that a stale corridor never outlives the
     * situation it describes.
     */
    private static final long HOLD_MILLIS = 1_200L;
    /**
     * How long a dropped lane keeps being drawn as a <em>solid</em> corridor before it degrades to the
     * dashed memory style. Without this the overlay flickers between solid and dashed whenever the
     * detector drops a single frame, which reads as three different lane lines on the same screen.
     */
    private static final long SOLID_GRACE_MILLIS = 250L;
    /** How much narrower the held corridor is drawn at its far end, for perspective. */
    private static final float FAR_SCALE = 0.45f;
    /** Monotonic capture time of the newest frame that carried a lane, or 0 when none did. */
    private long lastLaneSeenNanos;
    /** Style of the newest lane drawing, for the diagnostic log. */
    private String laneDrawStyle = "none";
    private long lastDrawLogNanos;
    /** Geometry of the newest lane drawing, for the diagnostic log. */
    private float laneDrawHoodY = Float.NaN;
    private double laneDrawHalfWidth;
    /** Longitudinal span the corridor was last drawn over, in normalized image rows. */
    private double laneDrawNearRow;
    private double laneDrawFarRow;
    public VehicleOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        boxPaint.setColor(Color.GREEN);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        textPaint.setColor(Color.GREEN);
        textPaint.setTextSize(16f * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        float density = getResources().getDisplayMetrics().density;
        guideRenderer = new FixedGuideRenderer(density);
        regionPaint.setColor(0x9980DEEA);
        regionPaint.setStyle(Paint.Style.STROKE);
        regionPaint.setStrokeWidth(density);
        regionPaint.setPathEffect(new DashPathEffect(new float[] {8f * density, 6f * density}, 0));
        laneFillPaint.setStyle(Paint.Style.FILL);
        laneLinePaint.setStyle(Paint.Style.STROKE);
        laneLinePaint.setStrokeWidth(3f * density);
        warningMarkerFillPaint.setStyle(Paint.Style.FILL);
        warningMarkerStrokePaint.setStyle(Paint.Style.STROKE);
        warningMarkerStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        warningMarkerStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        warningMarkerIconPaint.setStyle(Paint.Style.STROKE);
        warningMarkerIconPaint.setStrokeCap(Paint.Cap.ROUND);
        warningMarkerIconPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setResult(VehicleDetector.Result result, LeadVehicleTracker.Snapshot tracking) {
        setResult(result, tracking, null, null);
    }

    public void setResult(VehicleDetector.Result result, LeadVehicleTracker.Snapshot tracking,
                          LaneDepartureDetector.Observation lane,
                          AdasDecisionEngine.Decision decision) {
        setResult(result, tracking, lane, decision, false, false);
    }

    public void setResult(VehicleDetector.Result result, LeadVehicleTracker.Snapshot tracking,
                          LaneDepartureDetector.Observation lane,
                          AdasDecisionEngine.Decision decision,
                          boolean measurementAvailable, boolean speedAvailable) {
        setResult(result, tracking, lane, null, decision, measurementAvailable, speedAvailable, null);
    }

    public void setResult(VehicleDetector.Result result, LeadVehicleTracker.Snapshot tracking,
                          LaneDepartureDetector.Observation lane,
                          LaneGeometry.LaneSnapshot laneSnapshot,
                          AdasDecisionEngine.Decision decision,
                          boolean measurementAvailable, boolean speedAvailable) {
        setResult(result, tracking, lane, laneSnapshot, decision, measurementAvailable,
                speedAvailable, null);
    }

    public void setResult(VehicleDetector.Result result, LeadVehicleTracker.Snapshot tracking,
                          LaneDepartureDetector.Observation lane,
                          LaneGeometry.LaneSnapshot laneSnapshot,
                          AdasDecisionEngine.Decision decision,
                          boolean measurementAvailable, boolean speedAvailable,
                          LeadVehicleMotionEstimator.Measurement motion) {
        this.result = result;
        this.tracking = result != null && tracking != null
                && tracking.timestampNanos() == result.timestampNanos() ? tracking : null;
        LaneDepartureDetector.Observation nextLane = result == null ? null : lane;
        if (nextLane != null && nextLane.available()) {
            // Only a frame that really carried a lane refreshes the "seen" clock: a dropped lane has to
            // age, otherwise the corridor would be held forever.
            lastLaneSeenNanos = result.timestampNanos();
        }
        this.lane = nextLane;
        this.laneSnapshot = laneSnapshot;
        this.decision = decision;
        this.measurementAvailable = measurementAvailable;
        this.speedAvailable = speedAvailable;
        this.motion = motion;
        targetLabel = makeTargetLabel();
        if (result == null) {
            lastLaneSeenNanos = 0L;
            setGuideInput(null);
            resetVisualAnimation();
        }
        invalidate();
    }

    /** The Activity publishes original input times; animation never refreshes them. */
    public void setGuideInput(FixedGuideController.Input input) {
        guideInput = input;
        if (input == null) {
            guideController.reset();
            stopAnimation();
        } else {
            guideSettingsOpen = input.settingsOpen();
        }
        invalidate();
    }

    public void setGuideSettingsOpen(boolean open) {
        guideSettingsOpen = open;
        arrowsMoving = false;
        invalidate();
    }

    public void setVisualContinuity(long continuity) {
        if (visualContinuity != continuity) {
            visualContinuity = continuity;
            resetVisualAnimation();
        }
    }

    private void stopAnimation() {
        removeCallbacks(animationTick);
        animationScheduled = false;
        arrowsMoving = false;
    }

    private void resetVisualAnimation() {
        stopAnimation();
        colorFading = false;
        colorTargetId = 0L;
    }

    @Override
    protected void onDetachedFromWindow() {
        setGuideInput(null);
        resetVisualAnimation();
        guideRenderer.reset();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE && guideController != null) {
            setGuideInput(null);
            resetVisualAnimation();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE && guideController != null) {
            setGuideInput(null);
            resetVisualAnimation();
        }
    }

    /**
     * True while the newest lane sighting is recent enough to be drawn in the normal solid style. A few
     * dropped frames are invisible; only a sustained loss degrades to the dashed memory style.
     */
    private boolean freshLane() {
        long age = System.nanoTime() - lastLaneSeenNanos;
        return lastLaneSeenNanos > 0L && age >= 0L
                && age <= SOLID_GRACE_MILLIS * 1_000_000L;
    }

    public void setCalibration(CameraCalibration calibration) {
        setCalibration(calibration, CalibrationStore.Status.CALIBRATED);
    }

    public void setCalibration(CameraCalibration calibration, CalibrationStore.Status status) {
        if (!java.util.Objects.equals(this.calibration, calibration) || calibrationStatus != status) {
            setGuideInput(null);
            resetVisualAnimation();
            guideRenderer.reset();
        }
        this.calibration = calibration;
        this.calibrationStatus = status == null ? CalibrationStore.Status.UNCONFIGURED : status;
        invalidate();
    }

    public float getHoodY(float top, float height) {
        // Lower the hood reference baseline to 92% of the video frame (closer to the car's hood)
        return top + height * 0.92f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        long now = System.nanoTime();
        if (result != null && (now - result.timestampNanos() < 0L
                || now - result.timestampNanos() > LeadVehicleTracker.MAX_OBSERVATION_GAP_NANOS)) {
            // Check on every draw, including animation ticks between the Activity's 250 ms updates.
            guideController.reset();
            resetVisualAnimation();
            return;
        }

        int frameWidth = result != null ? result.frameWidth()
                : (calibration != null ? calibration.imageWidth() : 1280);
        int frameHeight = result != null ? result.frameHeight()
                : (calibration != null ? calibration.imageHeight() : 720);

        if (getWidth() <= 0 || getHeight() <= 0 || frameWidth <= 0 || frameHeight <= 0) {
            stopAnimation();
            return;
        }
        if (imageViewport == null || viewportFrameWidth != frameWidth || viewportFrameHeight != frameHeight
                || viewportViewWidth != getWidth() || viewportViewHeight != getHeight()) {
            imageViewport = CalibrationAlignment.fitCover(getWidth(), getHeight(), frameWidth, frameHeight);
            viewportFrameWidth = frameWidth;
            viewportFrameHeight = frameHeight;
            viewportViewWidth = getWidth();
            viewportViewHeight = getHeight();
        }
        float width = (float) imageViewport.width();
        float height = (float) imageViewport.height();
        float left = (float) imageViewport.left();
        float top = (float) imageViewport.top();

        // Real lane detection remains disabled. The fixed guide below has its own product gate.
        boolean sizeMismatch = result != null && calibration != null
                && !calibration.isUsableFor(result.frameWidth(), result.frameHeight());
        if (AdasCalibrationMode.LDW_ENABLED
                && (calibrationStatus != CalibrationStore.Status.CALIBRATED
                || calibration == null || sizeMismatch)) {
            Paint calibrationPaint = regionPaint;
            calibrationPaint.setColor(0xFFFFB74D);
            calibrationPaint.setPathEffect(null);
            double normHorizon = (calibration != null)
                    ? calibration.horizonYNormalized() : 0.438;
            float horizonY = top + (float) normHorizon * height;
            canvas.drawLine(left, horizonY, left + width, horizonY, calibrationPaint);
            canvas.drawText("地平线", left + 8f * getResources().getDisplayMetrics().density,
                    Math.max(textPaint.getTextSize(), horizonY - 4f), textPaint);

            calibrationPaint.setColor(0xCCFFCC80);
            float hoodY = getHoodY(top, height);
            canvas.drawLine(left + width * 0.15f, hoodY, left + width * 0.85f, hoodY, calibrationPaint);
            canvas.drawText("机盖对齐参考线", left + width * 0.16f, hoodY - 4f, textPaint);
        }

        if (result == null) {
            return; // Only skip dynamic vehicle boxes and lanes when no detection result is available
        }

        prepareTargetReadout(left, top, width, height);
        updateDisplayColor(now);
        FixedGuideController.Mode guideMode = guideController.update(guideInput, now,
                SystemClock.elapsedRealtimeNanos());
        boolean guideGeometryReady = guideRenderer.prepare(calibration, frameWidth, frameHeight,
                getWidth(), getHeight());
        if (!guideGeometryReady) {
            guideMode = FixedGuideController.Mode.HIDDEN;
        }
        boolean moving = guideMode == FixedGuideController.Mode.NORMAL && !guideSettingsOpen;
        if (moving && !arrowsMoving) {
            arrowStartNanos = now;
        }
        arrowsMoving = moving;
        float phase = moving ? (float) ((now - arrowStartNanos) % ARROW_CYCLE_NANOS)
                / ARROW_CYCLE_NANOS : 0f;
        guideRenderer.draw(canvas, guideMode,
                guideMode == FixedGuideController.Mode.MONITORING ? GUIDE_GREEN : displayColor,
                phase, targetBounds, targetLabelBounds);
        if ((moving || colorFading) && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE && !animationScheduled) {
            animationScheduled = true;
            postDelayed(animationTick, 34L);
        } else if (!moving && !colorFading) {
            stopAnimation();
        }

        // Lane geometry is extrapolated from a fixed normalized ROI, so it is only meaningful while
        // the active calibration is bound to this exact frame size.
        if (AdasCalibrationMode.LDW_ENABLED && !sizeMismatch) {
            drawLane(canvas, left, top, width, height);
        }
        // Production must show every detector result, not only the tracked lead vehicle. The
        // tracker still owns distance/alert semantics; secondary boxes are visual-only.
        drawVehicleBoxes(canvas, left, top, width, height);
        drawWarningMarker(canvas);
        drawTargetReadout(canvas, left, top, width, height);
        logLaneDrawing(BuildConfig.DEBUG && hoodLineVisible());
    }

    private boolean currentTargetValid() {
        return tracking != null && tracking.state() == LeadVehicleTracker.State.TRACKING
                && tracking.detection() != null && motion != null && motion.visible()
                && motion.trackId() == tracking.trackId() && Double.isFinite(motion.distanceMeters())
                && motion.distanceMeters() > 0.0;
    }

    /** Label formatting follows new measurements, never the 30 FPS arrow clock. */
    private String makeTargetLabel() {
        if (!currentTargetValid()) {
            return "";
        }
        return String.format(Locale.ROOT, "前车 %.1f m", motion.distanceMeters());
    }

    private void prepareTargetReadout(float left, float top, float width, float height) {
        targetBounds.setEmpty();
        targetLabelBounds.setEmpty();
        if (!currentTargetValid() || targetLabel.isEmpty()) {
            return;
        }
        VehicleDetector.Detection target = tracking.detection();
        float x = left + target.left() * width;
        float y = top + target.top() * height;
        targetBounds.set(x, y, left + target.right() * width, top + target.bottom() * height);
        float density = getResources().getDisplayMetrics().density;
        float textSize = 14f * getResources().getDisplayMetrics().scaledDensity;
        textPaint.setTextSize(textSize);
        float paddingX = 8f * density;
        float paddingY = 5f * density;
        float labelWidth = textPaint.measureText(targetLabel) + paddingX * 2f;
        float labelLeft = Math.max(4f * density,
                Math.min(18f * density, getWidth() - labelWidth - 4f * density));
        float bottomSafeGap = Math.max(48f * density, 26f * getResources().getDisplayMetrics().scaledDensity);
        targetLabelBaseline = Math.max(top + textSize + paddingY,
                getHeight() - bottomSafeGap);
        targetLabelBounds.set(labelLeft, targetLabelBaseline - textSize - paddingY,
                labelLeft + labelWidth, targetLabelBaseline + paddingY * 0.5f);
    }

    /** Draws the cached label using exactly the same bounds excluded from the reference band. */
    private void drawTargetReadout(Canvas canvas, float left, float top, float width, float height) {
        if (targetLabelBounds.isEmpty()) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        textPaint.setColor(Color.WHITE);
        targetLabelBackgroundPaint.setColor((selectedColor() & 0x00FFFFFF) | 0xD9000000);
        canvas.drawRoundRect(targetLabelBounds, 7f * density, 7f * density,
                targetLabelBackgroundPaint);
        canvas.drawText(targetLabel, targetLabelBounds.left + 8f * density,
                targetLabelBaseline, textPaint);
    }

    private void drawWarningMarker(Canvas canvas) {
        if (targetBounds.isEmpty()) {
            return;
        }
        int warningColor = warningColorForDecision(decision, true);
        if (warningColor == 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float markerWidth = Math.min(72f * density, targetBounds.width() * 0.42f);
        if (markerWidth < 22f * density) {
            return;
        }
        float markerHeight = markerWidth * 0.78f;
        float centerX = targetBounds.centerX();
        float baseY = targetBounds.top + targetBounds.height() * 0.80f;
        float topY = baseY - markerHeight;
        if (baseY > getHeight() - 8f * density) {
            baseY = getHeight() - 8f * density;
            topY = baseY - markerHeight;
        }
        warningMarkerPath.reset();
        warningMarkerPath.moveTo(centerX, topY);
        warningMarkerPath.lineTo(centerX - markerWidth * 0.5f, baseY);
        warningMarkerPath.lineTo(centerX + markerWidth * 0.5f, baseY);
        warningMarkerPath.close();

        warningMarkerFillPaint.setColor(warningColor);
        canvas.drawPath(warningMarkerPath, warningMarkerFillPaint);
        warningMarkerStrokePaint.setColor(0xDD101820);
        warningMarkerStrokePaint.setStrokeWidth(2.5f * density);
        canvas.drawPath(warningMarkerPath, warningMarkerStrokePaint);

        float iconWidth = markerWidth * 0.42f;
        float iconBodyHeight = markerHeight * 0.22f;
        float iconLeft = centerX - iconWidth * 0.5f;
        float iconRight = centerX + iconWidth * 0.5f;
        float iconBottom = baseY - markerHeight * 0.25f;
        float iconTop = iconBottom - iconBodyHeight;
        warningMarkerIconPaint.setColor(Color.WHITE);
        warningMarkerIconPaint.setStrokeWidth(Math.max(1.5f * density, markerWidth * 0.035f));
        canvas.drawRoundRect(new RectF(iconLeft, iconTop, iconRight, iconBottom),
                iconBodyHeight * 0.25f, iconBodyHeight * 0.25f, warningMarkerIconPaint);
        warningMarkerPath.reset();
        warningMarkerPath.moveTo(iconLeft + iconWidth * 0.16f, iconTop);
        warningMarkerPath.lineTo(centerX - iconWidth * 0.20f, iconTop - iconBodyHeight * 0.55f);
        warningMarkerPath.lineTo(centerX + iconWidth * 0.20f, iconTop - iconBodyHeight * 0.55f);
        warningMarkerPath.lineTo(iconRight - iconWidth * 0.16f, iconTop);
        canvas.drawPath(warningMarkerPath, warningMarkerIconPaint);
    }

    static int warningColorForDecision(AdasDecisionEngine.Decision decision,
                                       boolean targetValid) {
        if (!targetValid || decision == null) {
            return 0;
        }
        if (decision.collisionDanger() || decision.headwayCritical()
                || decision.events().contains(AdasDecisionEngine.Alert.FCW)
                || decision.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL)) {
            return GUIDE_RED;
        }
        if (decision.headwayWarning()) {
            return GUIDE_YELLOW;
        }
        return 0;
    }

    private void drawVehicleBoxes(Canvas canvas, float left, float top, float width, float height) {
        if (result == null || result.vehicles().isEmpty()) {
            return;
        }
        // Draw secondary detections first so the selected lead vehicle remains visually dominant
        // when boxes touch or overlap.
        for (int index = 0; index < result.vehicles().size(); index++) {
            VehicleDetector.Detection detection = result.vehicles().get(index);
            if (!isTrackedDetection(detection)) {
                drawVehicleBox(canvas, left, top, width, height, detection, false);
            }
        }
        for (int index = 0; index < result.vehicles().size(); index++) {
            VehicleDetector.Detection detection = result.vehicles().get(index);
            if (isTrackedDetection(detection)) {
                drawVehicleBox(canvas, left, top, width, height, detection, true);
            }
        }
    }

    private boolean isTrackedDetection(VehicleDetector.Detection detection) {
        return tracking != null && detection != null && detection.equals(tracking.detection());
    }

    private void drawVehicleBox(Canvas canvas, float left, float top, float width, float height,
                                VehicleDetector.Detection detection, boolean tracked) {
        RectF bounds = new RectF(left + detection.left() * width,
                top + detection.top() * height,
                left + detection.right() * width,
                top + detection.bottom() * height);
        int color = tracked ? selectedColor() : SECONDARY_VEHICLE_COLOR;
        drawCornerBox(canvas, bounds, color, tracked);
    }

    private void drawCornerBox(Canvas canvas, RectF bounds, int color, boolean tracked) {
        float density = getResources().getDisplayMetrics().density;
        float corner = cornerLengthForBounds(bounds.width(), bounds.height(), density);
        if (!(corner > 0f)) {
            return;
        }
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setColor(color);
        boxPaint.setStrokeWidth(boxStrokeWidth(tracked, density));
        boxPaint.setStrokeCap(Paint.Cap.SQUARE);
        boxPaint.setStrokeJoin(Paint.Join.MITER);

        float left = bounds.left;
        float top = bounds.top;
        float right = bounds.right;
        float bottom = bounds.bottom;
        canvas.drawLine(left, top, left + corner, top, boxPaint);
        canvas.drawLine(left, top, left, top + corner, boxPaint);
        canvas.drawLine(right - corner, top, right, top, boxPaint);
        canvas.drawLine(right, top, right, top + corner, boxPaint);
        canvas.drawLine(left, bottom - corner, left, bottom, boxPaint);
        canvas.drawLine(left, bottom, left + corner, bottom, boxPaint);
        canvas.drawLine(right - corner, bottom, right, bottom, boxPaint);
        canvas.drawLine(right, bottom - corner, right, bottom, boxPaint);
    }

    static float cornerLengthForBounds(float boxWidth, float boxHeight, float density) {
        if (!(boxWidth > 0f) || !(boxHeight > 0f)) {
            return 0f;
        }
        float safeDensity = Float.isFinite(density) && density > 0f ? density : 1f;
        return Math.min(Math.min(boxWidth, boxHeight) * CORNER_LENGTH_RATIO,
                CORNER_LENGTH_MAX_DP * safeDensity);
    }

    static float boxStrokeWidth(boolean tracked, float density) {
        float safeDensity = Float.isFinite(density) && density > 0f ? density : 1f;
        return (tracked ? TARGET_BOX_STROKE_DP : SECONDARY_BOX_STROKE_DP) * safeDensity;
    }

    /** True when the calibration reference lines are being drawn this frame. */
    private boolean hoodLineVisible() {
        return AdasCalibrationMode.LDW_ENABLED
                && (calibrationStatus != CalibrationStore.Status.CALIBRATED || calibration == null);
    }

    /**
     * One line per second naming exactly which lane drawing was produced. Without it the only way to
     * tell a solid measurement from a dashed memory (or from the calibration reference lines) is to
     * read the code.
     */
    private void logLaneDrawing(boolean referenceLines) {
        long now = System.nanoTime();
        if (now - lastDrawLogNanos < 1_000_000_000L) {
            return;
        }
        lastDrawLogNanos = now;
        Log.i(TAG, String.format(Locale.ROOT,
                "[LANE-DRAW] style=%s age=%dms hoodY=%s span=[%s..%s] halfWidth=%s refLines=%s",
                laneDrawStyle, lastLaneSeenNanos == 0L ? -1L
                        : (now - lastLaneSeenNanos) / 1_000_000L,
                number(laneDrawHoodY), number(laneDrawNearRow), number(laneDrawFarRow),
                number(laneDrawHalfWidth), referenceLines));
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "--";
    }

    private int selectedColor() {
        return displayColor;
    }

    private int desiredTargetColor() {
        if (decision != null && (decision.collisionDanger() || decision.headwayCritical()
                || decision.events().contains(AdasDecisionEngine.Alert.FCW)
                || decision.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL))) {
            return GUIDE_RED;
        }
        if (decision != null && (decision.headwayWarning() || decision.laneWarning())) {
            return GUIDE_YELLOW;
        }
        if (tracking != null && tracking.state() == LeadVehicleTracker.State.CANDIDATE) {
            return Color.CYAN;
        }
        if (!measurementAvailable || !speedAvailable) {
            return 0xFFB0BEC5;
        }
        return GUIDE_GREEN;
    }

    /** Both the box and band consume one color; only risk downgrades blend for 250 ms. */
    private void updateDisplayColor(long now) {
        int desired = desiredTargetColor();
        long targetId = currentTargetValid() ? tracking.trackId() : 0L;
        if (targetId == 0L || targetId != colorTargetId || riskLevel(desired) < 0
                || riskLevel(colorTo) < 0) {
            arrowsMoving = false;
            colorTargetId = targetId;
            displayColor = colorFrom = colorTo = desired;
            colorFading = false;
            return;
        }
        if (desired != colorTo) {
            if (riskLevel(desired) >= riskLevel(colorTo)) {
                displayColor = colorFrom = colorTo = desired;
                colorFading = false;
            } else {
                colorFrom = displayColor;
                colorTo = desired;
                colorStartNanos = now;
                colorFading = true;
            }
        }
        if (colorFading) {
            float fraction = Math.max(0f, Math.min(1f, (float) (now - colorStartNanos)
                    / COLOR_FADE_NANOS));
            displayColor = Color.rgb(
                    Math.round(Color.red(colorFrom) + (Color.red(colorTo) - Color.red(colorFrom)) * fraction),
                    Math.round(Color.green(colorFrom) + (Color.green(colorTo) - Color.green(colorFrom)) * fraction),
                    Math.round(Color.blue(colorFrom) + (Color.blue(colorTo) - Color.blue(colorFrom)) * fraction));
            colorFading = fraction < 1f;
        }
    }

    private static int riskLevel(int color) {
        return color == GUIDE_RED ? 2 : color == GUIDE_YELLOW ? 1 : color == GUIDE_GREEN ? 0 : -1;
    }

    private void drawLane(Canvas canvas, float left, float top, float width, float height) {
        // The detector only measures between ROI_TOP_ROW and ROI_BOTTOM_ROW, but the corridor has to be
        // drawn down to the hood reference line: a line that stops above the road surface reads as a
        // rendering fault even when the measurement behind it is correct.
        float hoodY = getHoodY(top, height);
        float normNearY = (hoodY - top) / height;
        boolean live = lane != null && lane.available();
        if (!live && !heldLaneUsable()) {
            // No measurement and no recent memory: draw nothing rather than inventing a corridor.
            laneDrawStyle = "none";
            return;
        }
        if (!live) {
            drawHeldLane(canvas, left, top, width, height, hoodY);
            return;
        }
        double dt = LaneDepartureDetector.Y_BOTTOM - LaneDepartureDetector.Y_TOP;
        double slopeL = (lane.leftBottomX() - lane.leftTopX()) / dt;
        double slopeR = (lane.rightBottomX() - lane.rightTopX()) / dt;
        if (!Double.isFinite(slopeL) || !Double.isFinite(slopeR)) {
            drawHeldLane(canvas, left, top, width, height, hoodY);
            return;
        }
        int lineColor = laneColor();
        laneDrawStyle = "solid";
        laneDrawHoodY = hoodY;
        laneDrawHalfWidth = 0.5 * Math.abs(lane.rightBottomX() - lane.leftBottomX());
        laneDrawNearRow = normNearY;
        laneDrawFarRow = LaneDepartureDetector.ROI_TOP_ROW;
        float lineFarY = top + height * (float) LaneDepartureDetector.ROI_TOP_ROW;

        float nearLeftX = left + (float) (lane.leftBottomX()
                + slopeL * (normNearY - LaneDepartureDetector.Y_BOTTOM)) * width;
        float nearRightX = left + (float) (lane.rightBottomX()
                + slopeR * (normNearY - LaneDepartureDetector.Y_BOTTOM)) * width;
        float lineFarLeftX = left + (float) (lane.leftTopX()
                - slopeL * (LaneDepartureDetector.Y_TOP - LaneDepartureDetector.ROI_TOP_ROW)) * width;
        float lineFarRightX = left + (float) (lane.rightTopX()
                - slopeR * (LaneDepartureDetector.Y_TOP - LaneDepartureDetector.ROI_TOP_ROW)) * width;

        // 1. Soft ground carpet between the two boundaries, fading out towards the horizon.
        float carpetFarY = top + height * (float) (LaneDepartureDetector.ROI_TOP_ROW - 0.02);
        float carpetFarLeftX = left + (float) (lane.leftTopX()
                - slopeL * (LaneDepartureDetector.Y_TOP - (LaneDepartureDetector.ROI_TOP_ROW - 0.02)))
                * width;
        float carpetFarRightX = left + (float) (lane.rightTopX()
                - slopeR * (LaneDepartureDetector.Y_TOP - (LaneDepartureDetector.ROI_TOP_ROW - 0.02)))
                * width;
        int bottomColor = (lineColor & 0x00FFFFFF) | 0x40000000;
        int topColor = (lineColor & 0x00FFFFFF);
        laneFillPaint.setShader(new LinearGradient(0, carpetFarY, 0, hoodY, topColor, bottomColor,
                Shader.TileMode.CLAMP));
        Path carpet = new Path();
        carpet.moveTo(carpetFarLeftX, carpetFarY);
        carpet.lineTo(carpetFarRightX, carpetFarY);
        carpet.lineTo(nearRightX, hoodY);
        carpet.lineTo(nearLeftX, hoodY);
        carpet.close();
        canvas.drawPath(carpet, laneFillPaint);

        // 2. Crisp boundary lines, with a lighter halo so they stay visible on bright asphalt.
        laneLinePaint.setShader(null);
        laneLinePaint.setPathEffect(null);
        laneLinePaint.setColor((lineColor & 0x00FFFFFF) | 0xDD000000);
        laneLinePaint.setStrokeWidth(3.5f * getResources().getDisplayMetrics().density);
        canvas.drawLine(nearLeftX, hoodY, lineFarLeftX, lineFarY, laneLinePaint);
        canvas.drawLine(nearRightX, hoodY, lineFarRightX, lineFarY, laneLinePaint);

        drawLaneReadout(canvas, left, top, width, height, lineColor);
    }

    /**
     * Redraws the last measured geometry so a dropped lane fades instead of vanishing. Dashed and
     * greyed on purpose: it is a memory of a measurement, not a measurement, and the driver must not
     * read it as the current lane position. Within {@link #SOLID_GRACE_MILLIS} this is still drawn as
     * a solid line, so a single dropped frame does not flicker the corridor.
     */
    private void drawHeldLane(Canvas canvas, float left, float top, float width, float height,
                              float hoodY) {
        if (!heldLaneUsable()) {
            laneDrawStyle = "none";
            return;
        }
        boolean faded = !freshLane();
        laneDrawStyle = faded ? "held-dashed" : "held-solid";
        laneDrawHoodY = hoodY;
        float nearHalfWidth = (float) (laneWidthNormalized() * 0.5) * width;
        laneDrawHalfWidth = laneWidthNormalized() * 0.5;
        laneDrawNearRow = (hoodY - top) / height;
        laneDrawFarRow = LaneDepartureDetector.ROI_TOP_ROW;
        float nearCenterX = left + (float) laneSnapshot.laneCenterImageX() * width;
        float farCenterX = left + width * 0.5f + (nearCenterX - (left + width * 0.5f)) * FAR_SCALE;
        float farHalfWidth = nearHalfWidth * FAR_SCALE;
        float lineFarY = top + height * (float) LaneDepartureDetector.ROI_TOP_ROW;

        float density = getResources().getDisplayMetrics().density;
        laneLinePaint.setShader(null);
        if (faded) {
            laneLinePaint.setColor(0x99B0BEC5);
            laneLinePaint.setStrokeWidth(2.5f * density);
            laneLinePaint.setPathEffect(new DashPathEffect(new float[] {10f * density, 8f * density}, 0));
        } else {
            laneLinePaint.setColor((laneColor() & 0x00FFFFFF) | 0xDD000000);
            laneLinePaint.setStrokeWidth(3.5f * density);
            laneLinePaint.setPathEffect(null);
        }
        canvas.drawLine(nearCenterX - nearHalfWidth, hoodY, farCenterX - farHalfWidth, lineFarY,
                laneLinePaint);
        canvas.drawLine(nearCenterX + nearHalfWidth, hoodY, farCenterX + farHalfWidth, lineFarY,
                laneLinePaint);
        laneLinePaint.setPathEffect(null);
        drawLaneReadout(canvas, left, top, width, height,
                faded ? 0xFFB0BEC5 : laneColor());
    }

    /** True while a held measurement exists and has not outlived the hold window. */
    private boolean heldLaneUsable() {
        return laneSnapshot != null && laneSnapshot.valid() && heldAgeNanos() >= 0
                && heldAgeNanos() <= HOLD_MILLIS * 1_000_000L;
    }

    /** Lane width in normalized image units, falling back to a nominal lane when unmeasured. */
    private double laneWidthNormalized() {
        double meters = laneSnapshot == null ? Double.NaN : laneSnapshot.laneWidthMeters();
        return Double.isFinite(meters) && meters > 0.5 ? Math.min(0.6, meters / 8.0) : 0.30;
    }

    /** Age of the held measurement, or -1 when there is nothing held. */
    private long heldAgeNanos() {
        return laneSnapshot == null ? -1L : laneSnapshot.ageNanos(System.nanoTime());
    }

    /** Boundary colour: grey for diagnostic/un-calibrated geometry, then hazard/caution colours. */
    private int laneColor() {
        if (calibrationStatus != CalibrationStore.Status.CALIBRATED || calibration == null) {
            return 0xFFB0BEC5;
        }
        if (decision != null && (decision.collisionDanger() || decision.headwayCritical()
                || decision.events().contains(AdasDecisionEngine.Alert.FCW)
                || decision.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL))) {
            return 0xFFFF5252;
        }
        if (decision != null && (decision.headwayWarning() || decision.laneWarning())) {
            return 0xFFFFB300;
        }
        if (!speedAvailable) {
            return 0xFFB0BEC5;
        }
        return 0xFF65D6C5;
    }

    /**
     * Offset and curvature readout drawn next to the hood line, plus a marker for the lane centre
     * against the vehicle centre. Kept above the bottom edge so it never collides with the metrics
     * panel underneath the preview.
     */
    private void drawLaneReadout(Canvas canvas, float left, float top, float width, float height,
                                 int lineColor) {
        if (laneSnapshot == null || !laneSnapshot.valid()
                || !Double.isFinite(laneSnapshot.centerOffsetMeters())) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float rowY = top + height * (float) LaneDepartureDetector.ROI_TOP_ROW;
        float textX = left + 8f * density;
        textPaint.setColor(lineColor);
        canvas.drawText(String.format(Locale.ROOT, "Offset %.2f m", laneSnapshot.centerOffsetMeters()),
                textX, Math.max(textPaint.getTextSize(), rowY - 4f), textPaint);
        String radius = laneSnapshot.curvatureValid()
                ? String.format(Locale.ROOT, "R %.0f m", Math.abs(laneSnapshot.curvatureRadiusMeters()))
                : laneSnapshot.curvatureKnown() ? "R straight" : "R unknown";
        canvas.drawText("LDWS / " + radius, textX,
                Math.max(textPaint.getTextSize() * 2f, rowY - 4f + textPaint.getTextSize()),
                textPaint);

        // Lane centre marker against the image centre: the gap is the offset the text reports.
        float centerRowY = top + height * (float) LaneDepartureDetector.Y_BOTTOM;
        float laneCenterX = left + (float) laneSnapshot.laneCenterImageX() * width;
        float vehicleCenterX = left + width * 0.5f;
        boxPaint.setColor(lineColor);
        canvas.drawLine(vehicleCenterX, centerRowY - 10f * density, vehicleCenterX,
                centerRowY + 10f * density, boxPaint);
        canvas.drawLine(laneCenterX, centerRowY - 6f * density, laneCenterX,
                centerRowY + 6f * density, boxPaint);
    }
}
