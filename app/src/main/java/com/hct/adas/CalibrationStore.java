package com.hct.adas;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists validated calibration values without coupling them to the detection pipeline. */
public final class CalibrationStore {
    public enum Status {
        UNCONFIGURED,
        WIZARD_COMPLETED,
        CALIBRATING,
        CALIBRATED
    }

    /**
     * Records written before camera binding existed carry no {@link #KEY_CAMERA_ID}, so
     * {@link #load(String)} cannot attribute them to a device and they are treated as unbound
     * rather than migrated. That is a deliberate choice for the current test phase: the project has
     * no deployed users to keep compatible, and silently adopting an unattributable pitch is
     * exactly the failure this binding exists to prevent. Revisit if the app ever ships to users
     * who would lose a valid calibration on upgrade.
     */
    private static final String PREFS = "camera_calibration";
    private static final int VERSION = 1;
    private static final String KEY_VERSION = "version";
    private static final String KEY_WIDTH = "image_width";
    private static final String KEY_HEIGHT = "image_height";
    private static final String KEY_CAMERA_HEIGHT = "camera_height_m";
    private static final String KEY_FOCAL_Y = "focal_y_normalized";
    private static final String KEY_PRINCIPAL_Y = "principal_y_normalized";
    private static final String KEY_PITCH = "pitch_degrees";
    private static final String KEY_STATUS = "calibration_status";
    private static final String KEY_PROGRESS = "learning_progress";
    private static final String KEY_CAMERA_ID = "camera_hardware_id";
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
    public CameraCalibration load(String expectedCameraId) {
        if (expectedCameraId != null && !expectedCameraId.isEmpty()) {
            String storedId = preferences.getString(KEY_CAMERA_ID, null);
            if (!expectedCameraId.equals(storedId)) {
                return null;
            }
        }
        return load();
    }

    /**
     * Camera id recorded together with the stored calibration, or an empty string when the
     * calibration predates camera binding or no calibration is stored at all.
     */
    public String storedCameraId() {
        String storedId = preferences.getString(KEY_CAMERA_ID, null);
        return storedId == null ? "" : storedId;
    }

    public Status loadStatus(String expectedCameraId) {
        CameraCalibration calibration = load(expectedCameraId);
        if (calibration == null) {
            return Status.UNCONFIGURED;
        }
        return loadStatus();
    }

    public Status loadStatus() {
        CameraCalibration calibration = load();
        if (calibration == null) {
            return Status.UNCONFIGURED;
        }
        String name = preferences.getString(KEY_STATUS, null);
        if (name == null) {
            return Status.CALIBRATED;
        }
        try {
            return Status.valueOf(name);
        } catch (IllegalArgumentException invalid) {
            // Unknown persisted state must fail safe and keep distance warnings disabled.
            return Status.UNCONFIGURED;
        }
    }

    public int loadProgress() {
        return Math.max(0, Math.min(100, preferences.getInt(KEY_PROGRESS, 0)));
    }

    public void save(CameraCalibration calibration) {
        save(calibration, Status.CALIBRATED, 100, null);
    }

    public void save(CameraCalibration calibration, Status status, int progress) {
        save(calibration, status, progress, null);
    }

    public void save(CameraCalibration calibration, Status status, int progress, String cameraId) {
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
                .putString(KEY_STATUS, targetStatus.name())
                .putInt(KEY_PROGRESS, clampedProgress);
        if (cameraId != null && !cameraId.isEmpty()) {
            editor.putString(KEY_CAMERA_ID, cameraId);
        } else {
            editor.remove(KEY_CAMERA_ID);
        }
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
