package com.alysson.hdrboost;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

final class HdrController {
    private static final String PREFS = "hdr_boost_state";
    private static final String ENABLED = "enabled";

    private static final String[] SYSTEM_KEYS = {
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS,
            "screen_mode_automatic_setting",
            "screen_mode_setting",
            "hdr_effect",
            "blue_light_filter",
            "blue_light_filter_adaptive_mode"
    };

    private HdrController() {}

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
    }

    static boolean toggle(Context context) {
        if (isEnabled(context)) {
            disable(context);
            return false;
        } else {
            enable(context);
            return true;
        }
    }

    private static void enable(Context context) {
        ContentResolver cr = context.getContentResolver();
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();

        for (String key : SYSTEM_KEYS) {
            String value = Settings.System.getString(cr, key);
            e.putBoolean("has_" + key, value != null);
            if (value != null) e.putString("old_" + key, value);
            else e.remove("old_" + key);
        }
        e.apply();

        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 255);

        if ("samsung".equalsIgnoreCase(Build.MANUFACTURER)) {
            Settings.System.putInt(cr, "screen_mode_automatic_setting", 0);
            Settings.System.putInt(cr, "screen_mode_setting", 4); // Samsung Vivid
            Settings.System.putInt(cr, "hdr_effect", 1);          // best effort Samsung flag
            Settings.System.putInt(cr, "blue_light_filter", 0);
            Settings.System.putInt(cr, "blue_light_filter_adaptive_mode", 0);
        }

        p.edit().putBoolean(ENABLED, true).apply();
    }

    private static void disable(Context context) {
        ContentResolver cr = context.getContentResolver();
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        for (String key : SYSTEM_KEYS) {
            boolean hadValue = p.getBoolean("has_" + key, false);
            if (hadValue) {
                String old = p.getString("old_" + key, null);
                Settings.System.putString(cr, key, old);
            } else {
                Settings.System.putString(cr, key, null);
            }
        }

        p.edit().putBoolean(ENABLED, false).apply();
    }
}
