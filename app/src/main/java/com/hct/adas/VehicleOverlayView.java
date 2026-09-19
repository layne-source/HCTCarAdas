package com.hct.adas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import java.util.Locale;

/** Displays source-normalized detections in the same letterboxed viewport as the preview. */
public final class VehicleOverlayView extends View {
    private static final String TAG = "HctAdasCore";
    private int bottomInsetPx = 0;
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint regionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laneFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laneLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetLabelBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
    /** Wall-clock time of the newest frame that actually carried a lane, or 0 when none did. */
    private long lastLaneSeenMillis;
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
        regionPaint.setColor(0x9980DEEA);
        regionPaint.setStyle(Paint.Style.STROKE);
        regionPaint.setStrokeWidth(density);
        regionPaint.setPathEffect(new DashPathEffect(new float[] {8f * density, 6f * density}, 0));
        laneFillPaint.setStyle(Paint.Style.FILL);
        laneLinePaint.setStyle(Paint.Style.STROKE);
        laneLinePaint.setStrokeWidth(3f * density);
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
            lastLaneSeenMillis = System.currentTimeMillis();
        }
        this.lane = nextLane;
        this.laneSnapshot = laneSnapshot;
        this.decision = decision;
        this.measurementAvailable = measurementAvailable;
        this.speedAvailable = speedAvailable;
        this.motion = motion;
        invalidate();
    }

    /**
     * True while the newest lane sighting is recent enough to be drawn in the normal solid style. A few
     * dropped frames are invisible; only a sustained loss degrades to the dashed memory style.
     */
    private boolean freshLane() {
        return lastLaneSeenMillis > 0L
                && System.currentTimeMillis() - lastLaneSeenMillis <= SOLID_GRACE_MILLIS;
    }

    /** Style and geometry of the newest lane drawing, for the 1 Hz diagnostic log. */
    String laneDrawStyle() {
        return laneDrawStyle;
    }

    float laneDrawHoodY() {
        return laneDrawHoodY;
    }

    double laneDrawHalfWidth() {
        return laneDrawHalfWidth;
    }

    double laneDrawNearRow() {
        return laneDrawNearRow;
    }

    double laneDrawFarRow() {
        return laneDrawFarRow;
    }

    public void setCalibration(CameraCalibration calibration) {
        setCalibration(calibration, CalibrationStore.Status.CALIBRATED);
    }

    public void setCalibration(CameraCalibration calibration, CalibrationStore.Status status) {
        this.calibration = calibration;
        this.calibrationStatus = status == null ? CalibrationStore.Status.UNCONFIGURED : status;
        invalidate();
    }

    public void setBottomInset(int bottomInsetPx) {
        if (this.bottomInsetPx != bottomInsetPx) {
            this.bottomInsetPx = bottomInsetPx;
            invalidate();
        }
    }

    public float getHoodY(float top, float height) {
        float density = getResources().getDisplayMetrics().density;
        float margin = 4f * density;
        // Lower the hood reference baseline to 92% of the video frame (closer to the car's hood)
        float baselineY = top + height * 0.92f;
        if (bottomInsetPx > 0) {
            float availableBottom = getHeight() - bottomInsetPx - margin;
            return Math.min(baselineY, availableBottom);
        }
        return baselineY;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int frameWidth = result != null ? result.frameWidth()
                : (calibration != null ? calibration.imageWidth() : 1280);
        int frameHeight = result != null ? result.frameHeight()
                : (calibration != null ? calibration.imageHeight() : 720);

        float scale = Math.min((float) getWidth() / frameWidth,
                (float) getHeight() / frameHeight);
        float width = frameWidth * scale;
        float height = frameHeight * scale;
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;

        // The lightweight release has no lane guidance UI. Keep this branch behind the product
        // switch so an old calibration state can never expose the previous engineering guides.
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

        // Lane geometry is extrapolated from a fixed normalized ROI, so it is only meaningful while
        // the active calibration is bound to this exact frame size.
        if (AdasCalibrationMode.LDW_ENABLED && !sizeMismatch) {
            drawLane(canvas, left, top, width, height);
        }
        drawTargetReadout(canvas, left, top, width, height);
        // Keep unselected boxes for debug verification; production shows only the tracked target
        // together with its distance label so the overlay follows the real vehicle position.
        if (!BuildConfig.DEBUG) {
            drawTargetBox(canvas, left, top, width, height);
            logLaneDrawing(false);
            return;
        }
        for (VehicleDetector.Detection detection : result.vehicles()) {
            boolean selected = tracking != null && detection.equals(tracking.detection());
            int color = selected ? selectedColor() : 0xFFB0BEC5;
            boxPaint.setColor(color);
            textPaint.setColor(color);
            float x = left + detection.left() * width;
            float y = top + detection.top() * height;
            canvas.drawRect(x, y, left + detection.right() * width,
                    top + detection.bottom() * height, boxPaint);
            String label = switch (detection.label()) {
                case "car" -> "轿车";
                case "bus" -> "客车";
                default -> "货车";
            };
            if (selected) {
                label = (tracking.state() == LeadVehicleTracker.State.TRACKING
                        ? getResources().getString(R.string.tracking_box_label, tracking.trackId())
                        : getResources().getString(R.string.tracking_candidate)) + " · " + label;
            }
            canvas.drawText(String.format(Locale.ROOT, "%s %.0f%%", label,
                    detection.confidence() * 100f), x,
                    Math.max(textPaint.getTextSize(), y - 4f), textPaint);
        }
        logLaneDrawing(hoodLineVisible());
    }

    /** Draws the distance next to the currently tracked vehicle instead of in a fixed HUD card. */
    private void drawTargetReadout(Canvas canvas, float left, float top, float width, float height) {
        if (tracking == null || tracking.detection() == null || motion == null || !motion.visible()
                || motion.trackId() != tracking.trackId() || !Double.isFinite(motion.distanceMeters())) {
            return;
        }
        VehicleDetector.Detection target = tracking.detection();
        float x = left + target.left() * width;
        float y = top + target.top() * height;
        int color = selectedColor();
        String label = String.format(Locale.ROOT, "%.1f m", motion.distanceMeters());
        if (decision != null && decision.events().contains(AdasDecisionEngine.Alert.FCW)) {
            label += " · FCW";
        } else if (decision != null
                && decision.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL)) {
            label += " · HMW";
        } else if (decision != null
                && decision.events().contains(AdasDecisionEngine.Alert.LVSA)) {
            label += " · LVSA";
        }
        float density = getResources().getDisplayMetrics().density;
        float textSize = 14f * getResources().getDisplayMetrics().scaledDensity;
        textPaint.setTextSize(textSize);
        textPaint.setColor(Color.WHITE);
        float paddingX = 8f * density;
        float paddingY = 5f * density;
        float labelWidth = textPaint.measureText(label) + paddingX * 2f;
        float labelLeft = Math.max(left, Math.min(x, left + width - labelWidth));
        float baseline = Math.max(top + textSize + paddingY, y - 7f * density);
        targetLabelBackgroundPaint.setColor((color & 0x00FFFFFF) | 0xD9000000);
        canvas.drawRoundRect(labelLeft, baseline - textSize - paddingY,
                labelLeft + labelWidth, baseline + paddingY * 0.5f,
                7f * density, 7f * density, targetLabelBackgroundPaint);
        canvas.drawText(label, labelLeft + paddingX, baseline, textPaint);
    }

    private void drawTargetBox(Canvas canvas, float left, float top, float width, float height) {
        if (tracking == null || tracking.detection() == null || motion == null || !motion.visible()
                || motion.trackId() != tracking.trackId()) {
            return;
        }
        VehicleDetector.Detection target = tracking.detection();
        boxPaint.setColor(selectedColor());
        boxPaint.setStrokeWidth(2.5f * getResources().getDisplayMetrics().density);
        canvas.drawRect(left + target.left() * width, top + target.top() * height,
                left + target.right() * width, top + target.bottom() * height, boxPaint);
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
                laneDrawStyle, lastLaneSeenMillis == 0L ? -1L
                        : System.currentTimeMillis() - lastLaneSeenMillis,
                number(laneDrawHoodY), number(laneDrawNearRow), number(laneDrawFarRow),
                number(laneDrawHalfWidth), referenceLines));
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "--";
    }

    private int selectedColor() {
        if (decision != null && (decision.collisionDanger() || decision.headwayCritical()
                || decision.events().contains(AdasDecisionEngine.Alert.FCW)
                || decision.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL))) {
            return Color.RED;
        }
        if (decision != null && (decision.headwayWarning() || decision.laneWarning())) {
            return Color.YELLOW;
        }
        if (tracking != null && tracking.state() == LeadVehicleTracker.State.CANDIDATE) {
            return Color.CYAN;
        }
        if (!measurementAvailable || !speedAvailable) {
            return 0xFFB0BEC5;
        }
        return Color.GREEN;
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
        if (live && lastLaneSeenMillis == 0L) {
            // First frame that carries a lane in this session.
            lastLaneSeenMillis = System.currentTimeMillis();
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
        if (laneSnapshot == null || !laneSnapshot.valid() || laneSnapshot.timestampNanos() <= 0L) {
            return -1L;
        }
        return Math.max(0L, System.currentTimeMillis() - laneSnapshot.timestampNanos()) * 1_000_000L;
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
