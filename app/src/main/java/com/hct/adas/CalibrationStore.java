package com.hct.adas;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists validated calibration values without coupling them to the detection pipeline. */
public final class CalibrationStore {
    private static final String PREFS = "camera_calibration";
    private static final int VERSION = 1;
    private static final String KEY_VERSION = "version";
    private static final String KEY_WIDTH = "image_width";
    private static final String KEY_HEIGHT = "image_height";
    private static final String KEY_CAMERA_HEIGHT = "camera_height_m";
    private static final String KEY_FOCAL_Y = "focal_y_normalized";
    private static final String KEY_PRINCIPAL_Y = "principal_y_normalized";
    private static final String KEY_PITCH = "pitch_degrees";

    private final SharedPreferences preferences;

    public CalibrationStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public CameraCalibration load() {
        if (preferences.getInt(KEY_VERSION, 0) != VERSION
                || !preferences.contains(KEY_WIDTH) || !preferences.contains(KEY_HEIGHT)) {
            return null;
        }
        try {
            return new CameraCalibration(
                    preferences.getInt(KEY_WIDTH, 0),
                    preferences.getInt(KEY_HEIGHT, 0),
                    Double.longBitsToDouble(preferences.getLong(KEY_CAMERA_HEIGHT, 0L)),
                    Double.longBitsToDouble(preferences.getLong(KEY_FOCAL_Y, 0L)),
                    Double.longBitsToDouble(preferences.getLong(KEY_PRINCIPAL_Y, 0L)),
                    Double.longBitsToDouble(preferences.getLong(KEY_PITCH, 0L)));
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public void save(CameraCalibration calibration) {
        if (calibration == null) {
            throw new IllegalArgumentException("calibration must not be null");
        }
        preferences.edit()
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
                .apply();
    }

    public void clear() {
        preferences.edit().clear().apply();
    }
}
