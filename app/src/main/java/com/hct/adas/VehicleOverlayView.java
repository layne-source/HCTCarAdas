package com.hct.adas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/** Displays source-normalized detections in the same letterboxed viewport as the preview. */
public final class VehicleOverlayView extends View {
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private VehicleDetector.Result result;

    public VehicleOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        boxPaint.setColor(Color.GREEN);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        textPaint.setColor(Color.GREEN);
        textPaint.setTextSize(16f * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setShadowLayer(2f, 1f, 1f, Color.BLACK);
    }

    public void setResult(VehicleDetector.Result result) {
        this.result = result;
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
        for (VehicleDetector.Detection detection : result.vehicles()) {
            float x = left + detection.left() * width;
            float y = top + detection.top() * height;
            canvas.drawRect(x, y, left + detection.right() * width,
                    top + detection.bottom() * height, boxPaint);
            String label = switch (detection.label()) {
                case "car" -> "轿车";
                case "bus" -> "客车";
                default -> "货车";
            };
            canvas.drawText(String.format(Locale.ROOT, "%s %.0f%%", label,
                    detection.confidence() * 100f), x,
                    Math.max(textPaint.getTextSize(), y - 4f), textPaint);
        }
    }
}
