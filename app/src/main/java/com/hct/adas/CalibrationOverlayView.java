package com.hct.adas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/** Full-screen two-line installation alignment surface. */
public final class CalibrationOverlayView extends View {
    public interface Listener {
        void onConfirmed(CameraCalibration calibration);

        void onCancelled();

        default void onValidityChanged(boolean valid) {
        }
    }

    private enum DragTarget { NONE, HORIZON, CENTER }

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private DragTarget dragTarget = DragTarget.NONE;
    private Listener listener;
    private double horizonYNormalized = 0.35;
    private double centerXNormalized = 0.5;
    private CameraCalibration draft;
    private CalibrationAlignment.Viewport viewport;
    private boolean previewAvailable;
    private double dragOffset;
    private CalibrationAlignment.Result result = new CalibrationAlignment.Result(
            false, true, false, Double.NaN);

    public CalibrationOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setFocusable(true);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(20f * density);
        textPaint.setShadowLayer(4f * density, 0f, 2f * density, Color.BLACK);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setCalibration(CameraCalibration draft) {
        this.draft = draft;
        horizonYNormalized = draft.horizonYNormalized();
        centerXNormalized = draft.guideCenterXNormalized();
        dragTarget = DragTarget.NONE;
        updateViewport();
        updateResult();
        notifyValidity();
        invalidate();
    }

    public boolean isValid() {
        return draft != null && viewport != null && previewAvailable && result.valid();
    }

    public void setPreviewAvailable(boolean available) {
        if (previewAvailable != available) {
            previewAvailable = available;
            dragTarget = DragTarget.NONE;
            notifyValidity();
            invalidate();
        }
    }

    public void confirm() {
        if (isValid() && listener != null) {
            listener.onConfirmed(CalibrationAlignment.confirm(draft, horizonYNormalized, centerXNormalized));
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateViewport();
        notifyValidity();
    }

    private void updateViewport() {
        viewport = draft == null || getWidth() <= 0 || getHeight() <= 0 ? null
                : CalibrationAlignment.fitCenter(getWidth(), getHeight(),
                        draft.imageWidth(), draft.imageHeight());
    }

    private void notifyValidity() {
        if (listener != null) {
            listener.onValidityChanged(isValid());
        }
    }

    public void cancel() {
        if (listener != null) {
            listener.onCancelled();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (viewport == null) {
            return;
        }

        boolean valid = isValid();
        int color = valid ? 0xFF36E36F : 0xFFFF4D5A;
        linePaint.setColor(color);
        linePaint.setStrokeWidth(2.5f * density);
        float left = (float) viewport.left();
        float top = (float) viewport.top();
        float right = (float) (viewport.left() + viewport.width());
        float bottom = (float) (viewport.top() + viewport.height());
        float horizonY = (float) viewport.viewY(horizonYNormalized);
        float centerX = (float) viewport.viewX(centerXNormalized);
        canvas.drawLine(left, horizonY, right, horizonY, linePaint);
        canvas.drawLine(centerX, top, centerX, bottom, linePaint);

        textPaint.setColor(color);
        canvas.drawText("地平线", left + 24f * density,
                Math.max(top + 56f * density, horizonY - 12f * density), textPaint);
        String centerLabel = "请对齐车头中心";
        float centerLabelWidth = textPaint.measureText(centerLabel);
        canvas.drawText(centerLabel,
                Math.max(left + 12f * density, Math.min(right - centerLabelWidth - 12f * density,
                        centerX - centerLabelWidth * 0.5f)),
                Math.min(bottom - 20f * density, height - 88f * density), textPaint);

        textPaint.setTextSize(13f * density);
        String state = !previewAvailable ? "等待实时画面"
                : valid ? String.format(Locale.ROOT, "停车对齐两线 · 俯仰 %.1f°", result.pitchDegrees())
                : invalidReason();
        float stateWidth = textPaint.measureText(state);
        textPaint.setColor(Color.WHITE);
        canvas.drawText(state, Math.max(12f * density, (width - stateWidth) * 0.5f),
                28f * density, textPaint);
        textPaint.setTextSize(20f * density);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (viewport == null || !previewAvailable) {
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                float x = event.getX();
                float y = event.getY();
                if (!viewport.contains(x, y)) {
                    dragTarget = DragTarget.NONE;
                    return true;
                }
                float horizonY = (float) viewport.viewY(horizonYNormalized);
                float centerX = (float) viewport.viewX(centerXNormalized);
                float radius = 32f * density;
                if (Math.abs(y - horizonY) <= radius
                        && Math.abs(y - horizonY) <= Math.abs(x - centerX)) {
                    dragTarget = DragTarget.HORIZON;
                    dragOffset = viewport.imageY(y) - horizonYNormalized;
                } else if (Math.abs(x - centerX) <= radius) {
                    dragTarget = DragTarget.CENTER;
                    dragOffset = viewport.imageX(x) - centerXNormalized;
                } else {
                    dragTarget = DragTarget.NONE;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (dragTarget == DragTarget.HORIZON) {
                    horizonYNormalized = clamp(viewport.imageY(event.getY()) - dragOffset, 0.0, 1.0);
                } else if (dragTarget == DragTarget.CENTER) {
                    centerXNormalized = clamp(viewport.imageX(event.getX()) - dragOffset, 0.0, 1.0);
                }
                updateResult();
                notifyValidity();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragTarget = DragTarget.NONE;
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    private void updateResult() {
        result = CalibrationAlignment.evaluate(horizonYNormalized, centerXNormalized,
                draft.focalLengthYNormalized(), draft.principalPointYNormalized());
    }

    private String invalidReason() {
        if (result.pitchOutOfRange() && result.centerOutOfRange()) {
            return "水平线和中心线超出允许范围";
        }
        if (result.pitchOutOfRange()) {
            return "水平线位置无效，请小幅调整";
        }
        return "中心线偏移过大，请小幅调整";
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
