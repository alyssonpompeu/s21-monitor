package com.alysson.hdrboost;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class HdrController {
    private static final String PREFS = "hdr_boost_state";
    private static final String ENABLED = "enabled";
    private static final String ROOT_MODE = "root_mode";
    private static final String SYSFS_RECORDS = "root_sysfs_records";

    private static final String[] SYSTEM_KEYS = {
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS,
            "screen_brightness_float",
            "screen_mode_automatic_setting",
            "screen_mode_setting",
            "hdr_effect",
            "blue_light_filter",
            "blue_light_filter_adaptive_mode"
    };

    private static final String[] HBM_NODES = {
            "/sys/class/lcd/panel/auto_brightness",
            "/sys/class/backlight/panel0-backlight/auto_brightness",
            "/sys/class/backlight/panel/auto_brightness",
            "/sys/devices/platform/panel/auto_brightness",
            "/sys/class/lcd/panel/hbm_mode",
            "/sys/class/backlight/panel0-backlight/hbm_mode",
            "/sys/class/backlight/panel/hbm_mode"
    };

    private HdrController() {}

    static final class ToggleResult {
        final boolean enabled;
        final boolean rootUsed;
        final int panelNodesChanged;
        final String message;

        ToggleResult(boolean enabled, boolean rootUsed, int panelNodesChanged, String message) {
            this.enabled = enabled;
            this.rootUsed = rootUsed;
            this.panelNodesChanged = panelNodesChanged;
            this.message = message;
        }
    }

    private static final class ShellResult {
        final int exitCode;
        final String output;

        ShellResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output.trim();
        }

        boolean ok() {
            return exitCode == 0;
        }
    }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
    }

    static boolean hasRoot() {
        ShellResult r = root("id");
        return r.ok() && r.output.contains("uid=0");
    }

    static ToggleResult toggle(Context context) {
        if (isEnabled(context)) {
            return disable(context);
        }
        return enable(context);
    }

    private static ToggleResult enable(Context context) {
        if (hasRoot()) {
            return enableRoot(context);
        }

        if (!Settings.System.canWrite(context)) {
            return new ToggleResult(false, false, 0,
                    "Root não concedido. Autorize o Magisk/KernelSU/APatch ou permita Modificar configurações do sistema.");
        }

        enableLegacy(context);
        return new ToggleResult(true, false, 0,
                "Ativado sem root: brilho máximo + Samsung Vivid (melhor esforço). Para HBM do painel, conceda root.");
    }

    private static ToggleResult enableRoot(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();

        saveRootSettings(e);
        List<String> sysfsRecords = new ArrayList<>();
        int panelNodesChanged = 0;

        putRootSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, "0");
        putRootSystem(Settings.System.SCREEN_BRIGHTNESS, "255");
        putRootSystem("screen_brightness_float", "1.0");

        if ("samsung".equalsIgnoreCase(Build.MANUFACTURER)) {
            putRootSystem("screen_mode_automatic_setting", "0");
            putRootSystem("screen_mode_setting", "4");
            putRootSystem("hdr_effect", "1");
            putRootSystem("blue_light_filter", "0");
            putRootSystem("blue_light_filter_adaptive_mode", "0");
        }

        root("cmd display set-brightness 1.0 >/dev/null 2>&1 || true");

        for (String path : HBM_NODES) {
            String old = rootRead(path);
            if (old == null) continue;

            String target = path.endsWith("auto_brightness") ? "6" : "1";
            if (rootWrite(path, target)) {
                sysfsRecords.add(path + "|" + old);
                panelNodesChanged++;
            }
        }

        ShellResult backlights = root(
                "for d in /sys/class/backlight/*; do " +
                "if [ -r \"$d/brightness\" ] && [ -r \"$d/max_brightness\" ]; then " +
                "b=$(cat \"$d/brightness\" 2>/dev/null); " +
                "m=$(cat \"$d/max_brightness\" 2>/dev/null); " +
                "printf '%s|%s|%s\\n' \"$d/brightness\" \"$b\" \"$m\"; " +
                "fi; done");

        if (backlights.ok() && !backlights.output.isEmpty()) {
            for (String line : backlights.output.split("\\n")) {
                String[] parts = line.split("\\|", 3);
                if (parts.length != 3) continue;
                String path = parts[0].trim();
                String old = parts[1].trim();
                String max = parts[2].trim();
                if (path.isEmpty() || old.isEmpty() || max.isEmpty()) continue;
                if (rootWrite(path, max)) {
                    sysfsRecords.add(path + "|" + old);
                    panelNodesChanged++;
                }
            }
        }

        e.putString(SYSFS_RECORDS, joinLines(sysfsRecords));
        e.putBoolean(ROOT_MODE, true);
        e.putBoolean(ENABLED, true);
        e.apply();

        String detail = panelNodesChanged > 0
                ? "Root aplicado: Vivid + brilho máximo + " + panelNodesChanged + " controle(s) direto(s) do painel/HBM."
                : "Root aplicado: Vivid + brilho máximo. O kernel não expôs um nó HBM gravável; o limite térmico do painel continua valendo.";

        return new ToggleResult(true, true, panelNodesChanged,
                detail + " HDR real ainda depende de conteúdo HDR e do compositor do sistema.");
    }

    private static ToggleResult disable(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean wasRoot = p.getBoolean(ROOT_MODE, false);
        int restored = 0;

        if (wasRoot) {
            if (!hasRoot()) {
                return new ToggleResult(true, true, 0,
                        "Root é necessário para restaurar os controles do painel. Conceda acesso root e toque novamente para desativar com segurança.");
            }

            restoreRootSettings(p);
            String records = p.getString(SYSFS_RECORDS, "");
            if (records != null && !records.isEmpty()) {
                for (String line : records.split("\\n")) {
                    String[] parts = line.split("\\|", 2);
                    if (parts.length != 2) continue;
                    if (rootWrite(parts[0], parts[1])) restored++;
                }
            }
        } else {
            restoreLegacy(context);
        }

        p.edit()
                .putBoolean(ENABLED, false)
                .putBoolean(ROOT_MODE, false)
                .remove(SYSFS_RECORDS)
                .apply();

        return new ToggleResult(false, wasRoot, restored,
                wasRoot ? "HDR Boost ROOT desativado — configurações e controles do painel restaurados."
                        : "HDR Boost desativado — ajustes restaurados.");
    }

    private static void saveRootSettings(SharedPreferences.Editor e) {
        for (String key : SYSTEM_KEYS) {
            ShellResult r = root("settings get system " + key);
            String value = r.ok() ? r.output : "null";
            boolean present = !value.isEmpty() && !"null".equalsIgnoreCase(value);
            e.putBoolean("root_has_" + key, present);
            if (present) e.putString("root_old_" + key, value);
            else e.remove("root_old_" + key);
        }
    }

    private static void restoreRootSettings(SharedPreferences p) {
        for (String key : SYSTEM_KEYS) {
            boolean had = p.getBoolean("root_has_" + key, false);
            if (had) {
                String old = p.getString("root_old_" + key, null);
                if (old != null) putRootSystem(key, old);
            } else {
                root("settings delete system " + key + " >/dev/null 2>&1 || true");
            }
        }
    }

    private static void enableLegacy(Context context) {
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
        Settings.System.putString(cr, "screen_brightness_float", "1.0");

        if ("samsung".equalsIgnoreCase(Build.MANUFACTURER)) {
            Settings.System.putInt(cr, "screen_mode_automatic_setting", 0);
            Settings.System.putInt(cr, "screen_mode_setting", 4);
            Settings.System.putInt(cr, "hdr_effect", 1);
            Settings.System.putInt(cr, "blue_light_filter", 0);
            Settings.System.putInt(cr, "blue_light_filter_adaptive_mode", 0);
        }

        p.edit().putBoolean(ROOT_MODE, false).putBoolean(ENABLED, true).apply();
    }

    private static void restoreLegacy(Context context) {
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
    }

    private static void putRootSystem(String key, String value) {
        root("settings put system " + key + " " + shellQuote(value) + " >/dev/null 2>&1 || true");
    }

    private static String rootRead(String path) {
        ShellResult r = root("if [ -r " + shellQuote(path) + " ]; then cat " + shellQuote(path) + "; else exit 44; fi");
        if (!r.ok() || r.output.isEmpty()) return null;
        return r.output.split("\\n", 2)[0].trim();
    }

    private static boolean rootWrite(String path, String value) {
        ShellResult r = root("printf %s " + shellQuote(value) + " > " + shellQuote(path));
        return r.ok();
    }

    private static ShellResult root(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (out.length() > 0) out.append('\n');
                    out.append(line);
                }
            }
            int code = process.waitFor();
            return new ShellResult(code, out.toString());
        } catch (Exception ex) {
            return new ShellResult(-1, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }
}
