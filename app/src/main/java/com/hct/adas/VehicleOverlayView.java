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
    private final Path regionPath = new Path();
    private VehicleDetector.Result result;
    private LeadVehicleTracker.Snapshot tracking;
    private CameraCalibration calibration;

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
    }

    public void setResult(VehicleDetector.Result result, LeadVehicleTracker.Snapshot tracking) {
        this.result = result;
        this.tracking = result != null && tracking != null
                && tracking.timestampNanos() == result.timestampNanos() ? tracking : null;
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
            int color = selected ? (tracking.state() == LeadVehicleTracker.State.TRACKING
                    ? Color.YELLOW : Color.CYAN) : Color.GREEN;
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
