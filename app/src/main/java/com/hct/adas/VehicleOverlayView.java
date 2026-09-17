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
import android.view.View;

import java.util.Locale;

/** Displays source-normalized detections in the same letterboxed viewport as the preview. */
public final class VehicleOverlayView extends View {
    private int bottomInsetPx = 0;
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint regionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laneFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laneLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private VehicleDetector.Result result;
    private LeadVehicleTracker.Snapshot tracking;
    private CameraCalibration calibration;
    private CalibrationStore.Status calibrationStatus = CalibrationStore.Status.UNCONFIGURED;
    private LaneDepartureDetector.Observation lane;
    private AdasDecisionEngine.Decision decision;
    private boolean measurementAvailable;
    private boolean speedAvailable;

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
        this.result = result;
        this.tracking = result != null && tracking != null
                && tracking.timestampNanos() == result.timestampNanos() ? tracking : null;
        this.lane = lane;
        this.decision = decision;
        this.measurementAvailable = measurementAvailable;
        this.speedAvailable = speedAvailable;
        invalidate();
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

        // Calibration baseline guidelines are always drawn during uncalibrated, wizard, learning, or size mismatch states
        boolean sizeMismatch = result != null && calibration != null
                && !calibration.isUsableFor(result.frameWidth(), result.frameHeight());
        if (calibrationStatus != CalibrationStore.Status.CALIBRATED || calibration == null || sizeMismatch) {
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
        if (!sizeMismatch) {
            drawLane(canvas, left, top, width, height);
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
        if (lane == null || !lane.available()) {
            return; // Clean preview: never draw fake dashed lines or virtual corridors when lanes are absent
        }
        float hoodY = getHoodY(top, height);
        float normNearY = Math.max(0.70f, Math.min(0.98f, (hoodY - top) / height));

        int lineColor;
        if (decision != null && (decision.collisionDanger() || decision.headwayCritical()
                || decision.events().contains(AdasDecisionEngine.Alert.FCW)
                || decision.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL))) {
            lineColor = 0xFFFF5252; // Red for hazard/collision
        } else if (decision != null && (decision.headwayWarning() || decision.laneWarning())) {
            lineColor = 0xFFFFB300; // Amber/Yellow for caution
        } else if (!speedAvailable) {
            lineColor = 0xFFB0BEC5;
        } else {
            lineColor = 0xFF00E676; // Tech Green for normal
        }

        double dt = LaneDepartureDetector.Y_BOTTOM - LaneDepartureDetector.Y_TOP;
        double slopeL = (lane.leftBottomX() - lane.leftTopX()) / dt;
        double slopeR = (lane.rightBottomX() - lane.rightTopX()) / dt;

        float normCarpetFarY = 0.68f; // Near-field ground carpet stops at 0.68 (wide and flat, never converges to a triangle!)
        float normLineFarY = 0.58f;   // Boundary lines extend further forward

        float carpetFarY = top + height * normCarpetFarY;
        float lineFarY = top + height * normLineFarY;

        float nearLeftX = left + (float) (lane.leftBottomX() + slopeL * (normNearY - LaneDepartureDetector.Y_BOTTOM)) * width;
        float nearRightX = left + (float) (lane.rightBottomX() + slopeR * (normNearY - LaneDepartureDetector.Y_BOTTOM)) * width;
        float carpetFarLeftX = left + (float) (lane.leftTopX() - slopeL * (LaneDepartureDetector.Y_TOP - normCarpetFarY)) * width;
        float carpetFarRightX = left + (float) (lane.rightTopX() - slopeR * (LaneDepartureDetector.Y_TOP - normCarpetFarY)) * width;
        float lineFarLeftX = left + (float) (lane.leftTopX() - slopeL * (LaneDepartureDetector.Y_TOP - normLineFarY)) * width;
        float lineFarRightX = left + (float) (lane.rightTopX() - slopeR * (LaneDepartureDetector.Y_TOP - normLineFarY)) * width;

        // 1. Render refined near-field AR Ground Carpet (soft gradient fading forward into the road)
        int bottomColor = (lineColor & 0x00FFFFFF) | 0x40000000; // ~25% alpha near hood
        int topColor = (lineColor & 0x00FFFFFF) | 0x00000000;    // 0% alpha (softly melts into asphalt, no harsh cut edge)
        laneFillPaint.setShader(new LinearGradient(0, carpetFarY, 0, hoodY, topColor, bottomColor, Shader.TileMode.CLAMP));

        Path carpet = new Path();
        carpet.moveTo(carpetFarLeftX, carpetFarY);
        carpet.lineTo(carpetFarRightX, carpetFarY);
        carpet.lineTo(nearRightX, hoodY);
        carpet.lineTo(nearLeftX, hoodY);
        carpet.close();
        canvas.drawPath(carpet, laneFillPaint);

        // 2. Render crisp, solid boundary guidance lines
        laneLinePaint.setShader(null);
        laneLinePaint.setPathEffect(null);
        laneLinePaint.setColor((lineColor & 0x00FFFFFF) | 0xDD000000);
        laneLinePaint.setStrokeWidth(3.5f * getResources().getDisplayMetrics().density);

        canvas.drawLine(nearLeftX, hoodY, lineFarLeftX, lineFarY, laneLinePaint);
        canvas.drawLine(nearRightX, hoodY, lineFarRightX, lineFarY, laneLinePaint);
    }
}
