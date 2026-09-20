package com.hct.adas;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists validated calibration values without coupling them to the detection pipeline. */
public final class CalibrationStore {
    public enum Status {
        UNCONFIGURED,
        WIZARD_COMPLETED,
        DISTANCE_READY,
        CALIBRATING,
        CALIBRATED
    }

    private static final String PREFS = "camera_calibration";
    // Old profiles may have been enabled with the default pitch without line confirmation.
    // Require one manual alignment before re-enabling them under the new installation contract.
    private static final int VERSION = 2;
    private static final String KEY_VERSION = "version";
    private static final String KEY_WIDTH = "image_width";
    private static final String KEY_HEIGHT = "image_height";
    private static final String KEY_CAMERA_HEIGHT = "camera_height_m";
    private static final String KEY_FOCAL_Y = "focal_y_normalized";
    private static final String KEY_PRINCIPAL_Y = "principal_y_normalized";
    private static final String KEY_PITCH = "pitch_degrees";
    private static final String KEY_GUIDE_CENTER_X = "guide_center_x_normalized";
    private static final String KEY_STATUS = "calibration_status";
    private static final String KEY_PROGRESS = "learning_progress";
    private final SharedPreferences preferences;

    public CalibrationStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public CameraCalibration load() {
        try {
            if (preferences.getInt(KEY_VERSION, 0) != VERSION
                    || !preferences.contains(KEY_WIDTH) || !preferences.contains(KEY_HEIGHT)
                    || !preferences.contains(KEY_CAMERA_HEIGHT) || !preferences.contains(KEY_FOCAL_Y)
                    || !preferences.contains(KEY_PRINCIPAL_Y) || !preferences.contains(KEY_PITCH)
                    || !preferences.contains(KEY_GUIDE_CENTER_X)) {
                return null;
            }
            CameraCalibration calibration = new CameraCalibration(
                    preferences.getInt(KEY_WIDTH, 0),
                    preferences.getInt(KEY_HEIGHT, 0),
                    Double.longBitsToDouble(preferences.getLong(KEY_CAMERA_HEIGHT, 0L)),
                    Double.longBitsToDouble(preferences.getLong(KEY_FOCAL_Y, 0L)),
                    Double.longBitsToDouble(preferences.getLong(KEY_PRINCIPAL_Y, 0L)),
                    Double.longBitsToDouble(preferences.getLong(KEY_PITCH, 0L)),
                    Double.longBitsToDouble(preferences.getLong(KEY_GUIDE_CENTER_X, 0L)));
            return CalibrationAlignment.isValid(calibration) ? calibration : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public Status loadStatus() {
        CameraCalibration calibration = load();
        if (calibration == null) {
            return Status.UNCONFIGURED;
        }
        try {
            String name = preferences.getString(KEY_STATUS, null);
            return name == null ? Status.UNCONFIGURED : Status.valueOf(name);
        } catch (RuntimeException invalid) {
            // Unknown persisted state must fail safe and keep distance warnings disabled.
            return Status.UNCONFIGURED;
        }
    }

    public int loadProgress() {
        try {
            return Math.max(0, Math.min(100, preferences.getInt(KEY_PROGRESS, 0)));
        } catch (RuntimeException malformed) {
            return 0;
        }
    }

    public void save(CameraCalibration calibration) {
        save(calibration, Status.CALIBRATED, 100);
    }

    public void save(CameraCalibration calibration, Status status, int progress) {
        if (calibration == null) {
            throw new IllegalArgumentException("calibration must not be null");
        }
        Status targetStatus = status == null ? Status.CALIBRATED : status;
        int clampedProgress = Math.max(0, Math.min(100, progress));
        SharedPreferences.Editor editor = preferences.edit()
                .putInt(KEY_VERSION, VERSION)
                .putInt(KEY_WIDTH, calibration.imageWidth())
                .putInt(KEY_HEIGHT, calibration.imageHeight())
                .putLong(KEY_CAMERA_HEIGHT, Double.doubleToRawLongBits(
                        calibration.cameraHeightMeters()))
                .putLong(KEY_FOCAL_Y, Double.doubleToRawLongBits(
                        calibration.focalLengthYNormalized()))
                .putLong(KEY_PRINCIPAL_Y, Double.doubleToRawLongBits(
                        calibration.principalPointYNormalized()))
                .putLong(KEY_PITCH, Double.doubleToRawLongBits(calibration.pitchDegrees()))
                .putLong(KEY_GUIDE_CENTER_X, Double.doubleToRawLongBits(
                        calibration.guideCenterXNormalized()))
                .putString(KEY_STATUS, targetStatus.name())
                .putInt(KEY_PROGRESS, clampedProgress);
        editor.apply();
    }

    public void saveStatus(Status status, int progress) {
        Status targetStatus = status == null ? Status.UNCONFIGURED : status;
        int clampedProgress = Math.max(0, Math.min(100, progress));
        preferences.edit()
                .putString(KEY_STATUS, targetStatus.name())
                .putInt(KEY_PROGRESS, clampedProgress)
                .apply();
    }

    public void clear() {
        preferences.edit().clear().apply();
    }
}
