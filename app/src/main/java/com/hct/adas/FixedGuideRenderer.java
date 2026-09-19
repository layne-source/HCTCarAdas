package com.hct.adas;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/** Cached fixed-guide drawing only. The overlay owns visibility, color and animation timing. */
public final class FixedGuideRenderer {
    private static final int FILL_NEAR_ALPHA = 64;
    private static final int EDGE_NEAR_ALPHA = 114;
    private static final int MONITOR_NEAR_ALPHA = 30;
    private static final int ARROW_NEAR_ALPHA = 140;
    private static final int ARROW_COUNT = 3;
    private static final float ARROW_FADE_IN_SPAN = 0.08f;
    private static final float WARNING_ARROW_PHASE = 0.18f;
    private static final float WARNING_ARROW_OPACITY = 0.35f;

    private final float density;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint monitorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bandPath = new Path();
    private final Path edgePath = new Path();
    private final Path arrowPath = new Path();
    private final RectF imageBounds = new RectF();
    private CameraCalibration preparedCalibration;
    private int preparedFrameWidth;
    private int preparedFrameHeight;
    private int preparedViewWidth;
    private int preparedViewHeight;
    private boolean inputsPrepared;
    private FixedGuideGeometry geometry;
    private CalibrationAlignment.Viewport viewport;
    private float centerX;
    private float nearY;
    private float farY;
    private int shaderRgb;
    private boolean shadersPrepared;

    public FixedGuideRenderer(float density) {
        this.density = Float.isFinite(density) && density > 0.0f ? density : 1.0f;
        fillPaint.setStyle(Paint.Style.FILL);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(1.5f * this.density);
        monitorPaint.setStyle(Paint.Style.STROKE);
        monitorPaint.setStrokeWidth(this.density);
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Rebuild only when saved calibration, source size or the safe content size changes. */
    public boolean prepare(CameraCalibration calibration, int frameWidth, int frameHeight,
                           int viewWidth, int viewHeight) {
        boolean sameCalibration = preparedCalibration == calibration
                || (preparedCalibration != null && preparedCalibration.equals(calibration));
        if (inputsPrepared && sameCalibration
                && preparedFrameWidth == frameWidth && preparedFrameHeight == frameHeight
                && preparedViewWidth == viewWidth && preparedViewHeight == viewHeight) {
            return geometry != null;
        }
        inputsPrepared = true;
        preparedCalibration = calibration;
        preparedFrameWidth = frameWidth;
        preparedFrameHeight = frameHeight;
        preparedViewWidth = viewWidth;
        preparedViewHeight = viewHeight;
        clearGeometry();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return false;
        }
        geometry = FixedGuideGeometry.fromCalibration(calibration, frameWidth, frameHeight);
        if (geometry == null) {
            return false;
        }
        viewport = CalibrationAlignment.fitCenter(viewWidth, viewHeight, frameWidth, frameHeight);
        imageBounds.set((float) viewport.left(), (float) viewport.top(),
                (float) (viewport.left() + viewport.width()),
                (float) (viewport.top() + viewport.height()));
        centerX = (float) viewport.viewX(geometry.centerX());
        nearY = (float) viewport.viewY(geometry.nearY());
        farY = (float) viewport.viewY(geometry.farY());
        float nearHalfWidth = (float) (geometry.nearHalfWidth() * viewport.width());
        float farHalfWidth = (float) (geometry.halfWidthAt(geometry.farY()) * viewport.width());
        bandPath.moveTo(centerX - farHalfWidth, farY);
        bandPath.lineTo(centerX + farHalfWidth, farY);
        bandPath.lineTo(centerX + nearHalfWidth, nearY);
        bandPath.lineTo(centerX - nearHalfWidth, nearY);
        bandPath.close();
        edgePath.moveTo(centerX - nearHalfWidth, nearY);
        edgePath.lineTo(centerX - farHalfWidth, farY);
        edgePath.moveTo(centerX + nearHalfWidth, nearY);
        edgePath.lineTo(centerX + farHalfWidth, farY);
        return true;
    }

    /** Bounds are view coordinates. Null or empty bounds mean there is nothing to avoid. */
    public void draw(Canvas canvas, FixedGuideController.Mode mode, int color, float phase,
                     RectF targetBounds, RectF labelBounds) {
        if (geometry == null || mode == null || mode == FixedGuideController.Mode.HIDDEN) {
            return;
        }
        prepareShaders(color);
        int saveCount = canvas.save();
        try {
            canvas.clipRect(imageBounds);
            clipOut(canvas, targetBounds);
            clipOut(canvas, labelBounds);
            if (mode == FixedGuideController.Mode.MONITORING) {
                canvas.drawPath(edgePath, monitorPaint);
                return;
            }
            canvas.drawPath(bandPath, fillPaint);
            canvas.drawPath(edgePath, edgePaint);
            if (mode == FixedGuideController.Mode.NORMAL
                    || mode == FixedGuideController.Mode.WARNING) {
                // Clip the strokes too, so no part of an arrow can leave the template.
                canvas.clipPath(bandPath);
                boolean warning = mode == FixedGuideController.Mode.WARNING;
                drawArrows(canvas, color, warning ? WARNING_ARROW_PHASE : phase,
                        warning ? WARNING_ARROW_OPACITY : 1.0f);
            }
        } finally {
            canvas.restoreToCount(saveCount);
        }
    }

    public void reset() {
        inputsPrepared = false;
        preparedCalibration = null;
        clearGeometry();
    }

    private void clearGeometry() {
        geometry = null;
        viewport = null;
        bandPath.rewind();
        edgePath.rewind();
        arrowPath.rewind();
        imageBounds.setEmpty();
        fillPaint.setShader(null);
        edgePaint.setShader(null);
        monitorPaint.setShader(null);
        shadersPrepared = false;
    }

    private void prepareShaders(int color) {
        int rgb = color & 0x00FFFFFF;
        if (shadersPrepared && shaderRgb == rgb) {
            return;
        }
        // A linear fade gives about 12% fill in the middle and zero at the far end.
        fillPaint.setShader(gradient(rgb, FILL_NEAR_ALPHA));
        edgePaint.setShader(gradient(rgb, EDGE_NEAR_ALPHA));
        monitorPaint.setShader(gradient(rgb, MONITOR_NEAR_ALPHA));
        shaderRgb = rgb;
        shadersPrepared = true;
    }

    private LinearGradient gradient(int rgb, int nearAlpha) {
        return new LinearGradient(0.0f, farY, 0.0f, nearY, rgb,
                rgb | (nearAlpha << 24), Shader.TileMode.CLAMP);
    }

    private void drawArrows(Canvas canvas, int color, float phase, float opacity) {
        float boundedPhase = Float.isFinite(phase) ? Math.max(0.0f, Math.min(1.0f, phase)) : 0.0f;
        for (int index = 0; index < ARROW_COUNT; index++) {
            float progress = boundedPhase + (float) index / ARROW_COUNT;
            if (progress >= 1.0f) {
                progress -= 1.0f;
            }
            float fadeIn = Math.min(1.0f, progress / ARROW_FADE_IN_SPAN);
            int alpha = Math.round(ARROW_NEAR_ALPHA * opacity * fadeIn * (1.0f - progress));
            if (alpha <= 0) {
                continue;
            }
            double row = geometry.nearY() - (geometry.nearY() - geometry.farY()) * progress;
            double bandHalfWidth = geometry.halfWidthAt(row);
            float perspectiveScale = (float) (bandHalfWidth / geometry.nearHalfWidth());
            float halfWidth = (float) (bandHalfWidth * viewport.width() * 0.24);
            float tailY = (float) viewport.viewY(row);
            float height = Math.min(halfWidth * 0.55f, tailY - farY);
            if (height <= 0.0f) {
                continue;
            }
            arrowPath.rewind();
            arrowPath.moveTo(centerX - halfWidth, tailY);
            arrowPath.lineTo(centerX, tailY - height);
            arrowPath.lineTo(centerX + halfWidth, tailY);
            arrowPaint.setColor((color & 0x00FFFFFF) | (alpha << 24));
            arrowPaint.setStrokeWidth(density * (0.75f + perspectiveScale));
            canvas.drawPath(arrowPath, arrowPaint);
        }
    }

    private static void clipOut(Canvas canvas, RectF bounds) {
        if (bounds != null && !bounds.isEmpty()
                && Float.isFinite(bounds.left) && Float.isFinite(bounds.top)
                && Float.isFinite(bounds.right) && Float.isFinite(bounds.bottom)) {
            canvas.clipOutRect(bounds);
        }
    }
}
