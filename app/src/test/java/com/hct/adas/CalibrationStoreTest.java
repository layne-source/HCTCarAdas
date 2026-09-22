package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public final class CalibrationStoreTest {
    @Test
    public void completeConfirmedProfileRemainsUsable() {
        CalibrationStore store = store(validValues());
        assertNotNull(store.load());
        assertEquals(CalibrationStore.Status.DISTANCE_READY, store.loadStatus());
        assertEquals(100, store.loadProgress());
    }

    @Test
    public void distanceReadyProgressIsCanonicalEvenWhenCallerSuppliesZero() {
        assertEquals(100, CalibrationStore.canonicalProgress(
                CalibrationStore.Status.DISTANCE_READY, 0));
        assertEquals(100, CalibrationStore.canonicalProgress(
                CalibrationStore.Status.CALIBRATED, 0));
        assertEquals(0, CalibrationStore.canonicalProgress(
                CalibrationStore.Status.WIZARD_COMPLETED, 100));
        assertEquals(99, CalibrationStore.canonicalProgress(
                CalibrationStore.Status.CALIBRATING, 100));
    }

    @Test
    public void completeProfileLoadsFullProgressFromLegacyZeroValue() {
        Map<String, Object> values = validValues();
        values.put("learning_progress", 0);
        assertEquals(100, store(values).loadProgress());
    }

    @Test
    public void missingGeometryCannotFallBackToZero() {
        for (String key : new String[] {"image_width", "image_height", "camera_height_m",
                "focal_y_normalized", "principal_y_normalized", "pitch_degrees",
                "guide_center_x_normalized"}) {
            Map<String, Object> values = validValues();
            values.remove(key);
            assertNull(key, store(values).load());
            assertEquals(CalibrationStore.Status.UNCONFIGURED, store(values).loadStatus());
            assertEquals(0, store(values).loadProgress());
        }
    }

    @Test
    public void malformedStoredTypesFailClosed() {
        for (String key : validValues().keySet()) {
            Map<String, Object> values = validValues();
            values.put(key, new Object());
            CalibrationStore store = store(values);
            if (key.equals("learning_progress")) {
                assertEquals(0, store.loadProgress());
            } else {
                assertEquals(key, CalibrationStore.Status.UNCONFIGURED, store.loadStatus());
            }
        }
    }

    @Test
    public void missingOrUnknownStatusDoesNotEnableWarnings() {
        Map<String, Object> values = validValues();
        values.remove("calibration_status");
        assertEquals(CalibrationStore.Status.UNCONFIGURED, store(values).loadStatus());
        values.put("calibration_status", "FUTURE_STATUS");
        assertEquals(CalibrationStore.Status.UNCONFIGURED, store(values).loadStatus());
    }

    @Test
    public void savedStatusCannotLegitimizeUnconfirmedGeometry() {
        for (double pitch : new double[] {-5.01, -20.0, 14.01}) {
            Map<String, Object> values = validValues();
            values.put("pitch_degrees", Double.doubleToRawLongBits(pitch));
            assertNull(store(values).load());
            assertEquals(CalibrationStore.Status.UNCONFIGURED, store(values).loadStatus());
        }
    }

    @Test
    public void levelAndUpwardProfilesRetainDistanceAfterReload() {
        for (double pitch : new double[] {0.0, -2.0, -5.0}) {
            Map<String, Object> values = validValues();
            values.put("pitch_degrees", Double.doubleToRawLongBits(pitch));
            CalibrationStore store = store(values);
            CameraCalibration loaded = store.load();
            assertNotNull(loaded);
            assertEquals(CalibrationStore.Status.DISTANCE_READY, store.loadStatus());
            assertEquals(pitch, loaded.pitchDegrees(), 0.0);
            double sin = Math.sin(Math.toRadians(pitch));
            double cos = Math.cos(Math.toRadians(pitch));
            double bottom = 0.5 + 0.9 * (1.55 * cos - 30.0 * sin)
                    / (30.0 * cos + 1.55 * sin);
            assertEquals(30.0, loaded.estimateDistanceMeters(bottom), 1e-6);
        }
    }

    private static Map<String, Object> validValues() {
        Map<String, Object> values = new HashMap<>();
        values.put("version", 2);
        values.put("image_width", 1280);
        values.put("image_height", 720);
        values.put("camera_height_m", Double.doubleToRawLongBits(1.55));
        values.put("focal_y_normalized", Double.doubleToRawLongBits(0.90));
        values.put("principal_y_normalized", Double.doubleToRawLongBits(0.50));
        values.put("pitch_degrees", Double.doubleToRawLongBits(8.0));
        values.put("guide_center_x_normalized", Double.doubleToRawLongBits(0.50));
        values.put("calibration_status", "DISTANCE_READY");
        values.put("learning_progress", 100);
        return values;
    }

    /** Emulates typed preference reads; malformed types must throw as Android does. */
    private static CalibrationStore store(Map<String, Object> values) {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("contains")) return values.containsKey(args[0]);
                    Object value = values.getOrDefault(args[0], args[1]);
                    return switch (method.getName()) {
                        case "getInt" -> (Integer) value;
                        case "getLong" -> (Long) value;
                        case "getString" -> (String) value;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return preferences;
            }
        };
        return new CalibrationStore(context);
    }
}
