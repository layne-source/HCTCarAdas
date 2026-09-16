package com.hct.adas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/** Displays source-normalized detections in the same letterboxed viewport as the preview. */
public final class VehicleOverlayView extends View {
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint regionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laneFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laneLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path regionPath = new Path();
    private VehicleDetector.Result result;
    private LeadVehicleTracker.Snapshot tracking;
    private CameraCalibration calibration;
    private LaneDepartureDetector.Observation lane;
    private AdasDecisionEngine.Decision decision;

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
        this.result = result;
        this.tracking = result != null && tracking != null
                && tracking.timestampNanos() == result.timestampNanos() ? tracking : null;
        this.lane = lane;
        this.decision = decision;
        invalidate();
    }

    public void setCalibration(CameraCalibration calibration) {
        this.calibration = calibration;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (result == null) {
            return;
        }
        float scale = Math.min((float) getWidth() / result.frameWidth(),
                (float) getHeight() / result.frameHeight());
        float width = result.frameWidth() * scale;
        float height = result.frameHeight() * scale;
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        drawSearchRegion(canvas, left, top, width, height);
        drawLane(canvas, left, top, width, height);
        if (calibration != null && calibration.isUsableFor(result.frameWidth(), result.frameHeight())) {
            Paint calibrationPaint = regionPaint;
            calibrationPaint.setColor(0xFFFFB74D);
            calibrationPaint.setPathEffect(null);
            float horizonY = top + (float) calibration.principalPointYNormalized() * height;
            canvas.drawLine(left, horizonY, left + width, horizonY, calibrationPaint);
            calibrationPaint.setColor(0x9980DEEA);
            calibrationPaint.setPathEffect(new DashPathEffect(new float[] {
                    8f * getResources().getDisplayMetrics().density,
                    6f * getResources().getDisplayMetrics().density}, 0));
        }
        for (VehicleDetector.Detection detection : result.vehicles()) {
            boolean selected = tracking != null && detection.equals(tracking.detection());
            int color = selected ? selectedColor() : Color.GREEN;
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
        if (decision != null && decision.events().contains(AdasDecisionEngine.Alert.FCW)) {
            return Color.RED;
        }
        if (decision != null && (decision.headwayWarning() || decision.laneWarning())) {
            return Color.YELLOW;
        }
        return tracking != null && tracking.state() == LeadVehicleTracker.State.CANDIDATE
                ? Color.CYAN : Color.YELLOW;
    }

    private void drawLane(Canvas canvas, float left, float top, float width, float height) {
        if (lane == null || !lane.available()) {
            return;
        }
        float leftTop = left + (float) lane.leftTopX() * width;
        float rightTop = left + (float) lane.rightTopX() * width;
        float leftBottom = left + (float) lane.leftBottomX() * width;
        float rightBottom = left + (float) lane.rightBottomX() * width;
        int color = decision != null && decision.laneWarning() ? Color.YELLOW : Color.GREEN;
        laneFillPaint.setColor((color & 0x00ffffff) | 0x33000000);
        Path fill = new Path();
        fill.moveTo(leftTop, top + height * 0.58f);
        fill.lineTo(rightTop, top + height * 0.58f);
        fill.lineTo(rightBottom, top + height * 0.94f);
        fill.lineTo(leftBottom, top + height * 0.94f);
        fill.close();
        canvas.drawPath(fill, laneFillPaint);
        laneLinePaint.setColor((color & 0x00ffffff) | 0xcc000000);
        Path lines = new Path();
        lines.moveTo(leftTop, top + height * 0.58f);
        lines.lineTo(leftBottom, top + height * 0.94f);
        canvas.drawPath(lines, laneLinePaint);
        lines.reset();
        lines.moveTo(rightTop, top + height * 0.58f);
        lines.lineTo(rightBottom, top + height * 0.94f);
        canvas.drawPath(lines, laneLinePaint);
    }

    private void drawSearchRegion(Canvas canvas, float left, float top, float width, float height) {
        float upperY = top + LeadVehicleTracker.REGION_TOP * height;
        regionPath.reset();
        regionPath.moveTo(left + (0.5f - LeadVehicleTracker.REGION_TOP_HALF_WIDTH) * width, upperY);
        regionPath.lineTo(left + (0.5f + LeadVehicleTracker.REGION_TOP_HALF_WIDTH) * width, upperY);
        regionPath.lineTo(left + (0.5f + LeadVehicleTracker.REGION_BOTTOM_HALF_WIDTH) * width, top + height);
        regionPath.lineTo(left + (0.5f - LeadVehicleTracker.REGION_BOTTOM_HALF_WIDTH) * width, top + height);
        regionPath.close();
        canvas.drawPath(regionPath, regionPaint);
    }
}
