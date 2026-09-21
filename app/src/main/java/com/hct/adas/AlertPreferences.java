package com.hct.adas;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists the user-facing alert sound switches independently from camera calibration. */
public final class AlertPreferences {
    private static final String PREFS = "adas_alert_preferences";
    private static final String KEY_FCW_ENABLED = "fcw_sound_enabled";
    private static final String KEY_HMW_ENABLED = "hmw_sound_enabled";
    private static final String KEY_LVSA_ENABLED = "lvsa_sound_enabled";
    private final SharedPreferences preferences;

    public AlertPreferences(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled(AdasDecisionEngine.Alert alert) {
        switch (alert) {
            case FCW:
                return preferences.getBoolean(KEY_FCW_ENABLED, true);
            case HMW_CRITICAL:
                return preferences.getBoolean(KEY_HMW_ENABLED, true);
            case LVSA:
                return preferences.getBoolean(KEY_LVSA_ENABLED, true);
            case LDW:
            default:
                // LDW is not part of the active product profile yet.
                return true;
        }
    }

    public void setEnabled(AdasDecisionEngine.Alert alert, boolean enabled) {
        String key;
        switch (alert) {
            case FCW:
                key = KEY_FCW_ENABLED;
                break;
            case HMW_CRITICAL:
                key = KEY_HMW_ENABLED;
                break;
            case LVSA:
                key = KEY_LVSA_ENABLED;
                break;
            case LDW:
            default:
                return;
        }
        preferences.edit().putBoolean(key, enabled).apply();
    }
}
