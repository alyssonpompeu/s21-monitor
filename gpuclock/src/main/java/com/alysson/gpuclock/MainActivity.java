package com.alysson.gpuclock;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int TEMP_LIMIT_MC = 85_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView rootStatus;
    private TextView deviceStatus;
    private TextView currentClock;
    private TextView governorView;
    private TextView temperatureView;
    private TextView actionStatus;
    private Spinner minSpinner;
    private Spinner maxSpinner;
    private Switch lockSwitch;
    private Button applyButton;
    private Button restoreButton;

    private GpuState state;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("gpuclock", MODE_PRIVATE);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        int pad = dp(18);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = text("GPU Clock Control", 26, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView subtitle = text("Controle de frequência da GPU • ROOT", 13, 0xFF90CAF9);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle, matchWrap());

        rootStatus = info(root, "Root", "Verificando…");
        deviceStatus = info(root, "GPU / devfreq", "Detectando…");
        currentClock = info(root, "Clock atual", "—");
        governorView = info(root, "Governor", "—");
        temperatureView = info(root, "Temperatura GPU", "—");

        section(root, "LIMITES DE CLOCK");
        label(root, "Clock mínimo");
        minSpinner = new Spinner(this);
        root.addView(minSpinner, matchWrap());

        label(root, "Clock máximo");
        maxSpinner = new Spinner(this);
        root.addView(maxSpinner, matchWrap());

        lockSwitch = new Switch(this);
        lockSwitch.setText("Travar no clock máximo selecionado");
        lockSwitch.setTextColor(Color.WHITE);
        lockSwitch.setPadding(0, dp(12), 0, dp(6));
        root.addView(lockSwitch, matchWrap());

        applyButton = button("APLICAR CLOCK");
        applyButton.setEnabled(false);
        applyButton.setOnClickListener(v -> applyClock());
        root.addView(applyButton, buttonParams());

        restoreButton = button("RESTAURAR LIMITES ORIGINAIS");
        restoreButton.setEnabled(false);
        restoreButton.setOnClickListener(v -> restoreOriginal());
        root.addView(restoreButton, buttonParams());

        Button refreshButton = button("ATUALIZAR LEITURA");
        refreshButton.setOnClickListener(v -> refresh());
        root.addView(refreshButton, buttonParams());

        actionStatus = text("", 13, 0xFFB0BEC5);
        actionStatus.setPadding(0, dp(12), 0, dp(10));
        root.addView(actionStatus, matchWrap());

        TextView warning = text(
                "Segurança: o app só usa frequências expostas pelo kernel. Não altera voltagem, não aplica nada no boot e não desativa throttling térmico. Clock inadequado pode causar travamentos, aquecimento e perda de dados. Use por sua conta e risco.",
                12, 0xFFFFCC80);
        warning.setPadding(dp(10), dp(12), dp(10), dp(12));
        warning.setBackgroundColor(0xFF3E2723);
        root.addView(warning, matchWrap());

        return scroll;
    }

    private void refresh() {
        setBusy(true, "Solicitando root e lendo GPU…");
        executor.execute(() -> {
            ShellResult rootCheck = Shell.run("id");
            if (rootCheck.code != 0 || !rootCheck.output.contains("uid=0")) {
                main.post(() -> showNoRoot(rootCheck.output));
                return;
            }

            GpuState loaded = detectGpu();
            main.post(() -> {
                rootStatus.setText("Root: OK (su concedido)");
                rootStatus.setTextColor(0xFF81C784);
                if (loaded == null) {
                    deviceStatus.setText("GPU / devfreq: não encontrado");
                    actionStatus.setText("O kernel não expôs uma interface GPU devfreq reconhecida.");
                    setBusy(false, null);
                    applyButton.setEnabled(false);
                    restoreButton.setEnabled(false);
                    return;
                }
                state = loaded;
                saveOriginalIfNeeded(loaded);
                renderState(loaded);
                setBusy(false, "Pronto.");
                applyButton.setEnabled(!loaded.frequencies.isEmpty());
                restoreButton.setEnabled(true);
            });
        });
    }

    private void showNoRoot(String detail) {
        rootStatus.setText("Root: indisponível ou negado");
        rootStatus.setTextColor(0xFFEF9A9A);
        deviceStatus.setText("GPU / devfreq: exige root");
        currentClock.setText("Clock atual: —");
        governorView.setText("Governor: —");
        temperatureView.setText("Temperatura GPU: —");
        actionStatus.setText("Conceda acesso root (Magisk/KernelSU/APatch) e toque em Atualizar. " + compact(detail));
        setBusy(false, null);
        applyButton.setEnabled(false);
        restoreButton.setEnabled(false);
    }

    private GpuState detectGpu() {
        String find = "for d in /sys/class/devfreq/* /sys/class/kgsl/kgsl-3d0/devfreq; do " +
                "[ -d \"$d\" ] || continue; n=$(cat \"$d/name\" 2>/dev/null); " +
                "l=$(printf '%s %s' \"$n\" \"$d\" | tr '[:upper:]' '[:lower:]'); " +
                "case \"$l\" in *gpu*|*mali*|*g3d*|*kgsl*|*adreno*) printf '%s|%s\\n' \"$d\" \"$n\"; exit 0;; esac; done; exit 1";
        ShellResult found = Shell.run(find);
        if (found.code != 0 || TextUtils.isEmpty(found.output)) return null;

        String first = found.output.split("\\n")[0].trim();
        String[] parts = first.split("\\|", 2);
        String path = parts[0].trim();
        String name = parts.length > 1 && !parts[1].trim().isEmpty() ? parts[1].trim() : path;

        GpuState s = new GpuState();
        s.path = path;
        s.name = name;
        s.governor = read(path + "/governor");
        s.current = readLong(path + "/cur_freq");
        if (s.current <= 0 && path.contains("kgsl")) s.current = readLong("/sys/class/kgsl/kgsl-3d0/gpuclk");
        s.min = readLong(path + "/min_freq");
        s.max = readLong(path + "/max_freq");
        s.temperatureMilliC = readGpuTemp();

        Set<Long> unique = new LinkedHashSet<>();
        addNumbers(unique, read(path + "/available_frequencies"));
        if (unique.isEmpty()) addTransitionFrequencies(unique, read(path + "/trans_stat"));
        if (unique.isEmpty()) {
            if (s.min > 0) unique.add(s.min);
            if (s.current > 0) unique.add(s.current);
            if (s.max > 0) unique.add(s.max);
        }
        s.frequencies.addAll(unique);
        Collections.sort(s.frequencies);
        return s;
    }

    private void renderState(GpuState s) {
        deviceStatus.setText("GPU / devfreq: " + s.name + "\n" + s.path);
        currentClock.setText("Clock atual: " + formatFreq(s.current));
        governorView.setText("Governor: " + emptyDash(s.governor));
        temperatureView.setText("Temperatura GPU: " + formatTemp(s.temperatureMilliC));

        List<String> labels = new ArrayList<>();
        for (long f : s.frequencies) labels.add(formatFreq(f));
        ArrayAdapter<String> adapterMin = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        ArrayAdapter<String> adapterMax = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        minSpinner.setAdapter(adapterMin);
        maxSpinner.setAdapter(adapterMax);
        minSpinner.setSelection(nearestIndex(s.frequencies, s.min > 0 ? s.min : s.frequencies.isEmpty() ? 0 : s.frequencies.get(0)));
        maxSpinner.setSelection(nearestIndex(s.frequencies, s.max > 0 ? s.max : s.frequencies.isEmpty() ? 0 : s.frequencies.get(s.frequencies.size() - 1)));
    }

    private void applyClock() {
        if (state == null || state.frequencies.isEmpty()) return;
        int minPos = minSpinner.getSelectedItemPosition();
        int maxPos = maxSpinner.getSelectedItemPosition();
        if (minPos < 0 || maxPos < 0) return;
        long min = state.frequencies.get(minPos);
        long max = state.frequencies.get(maxPos);
        if (lockSwitch.isChecked()) min = max;
        if (min > max) {
            Toast.makeText(this, "O clock mínimo não pode ser maior que o máximo.", Toast.LENGTH_LONG).show();
            return;
        }
        if (state.temperatureMilliC >= TEMP_LIMIT_MC) {
            Toast.makeText(this, "GPU acima de 85 °C. Aplicação bloqueada por segurança.", Toast.LENGTH_LONG).show();
            return;
        }

        final long targetMin = min;
        final long targetMax = max;
        setBusy(true, "Aplicando " + formatFreq(targetMin) + " – " + formatFreq(targetMax) + "…");
        executor.execute(() -> {
            String p = q(state.path);
            String cmd = "echo 0 > " + p + "/min_freq && echo 0 > " + p + "/max_freq && " +
                    "echo " + targetMax + " > " + p + "/max_freq && echo " + targetMin + " > " + p + "/min_freq";
            ShellResult r = Shell.run(cmd);
            GpuState after = r.code == 0 ? detectGpu() : null;
            main.post(() -> {
                if (r.code != 0 || after == null) {
                    actionStatus.setText("Falha ao aplicar: " + compact(r.output));
                    Toast.makeText(this, "Kernel recusou a alteração.", Toast.LENGTH_LONG).show();
                } else {
                    state = after;
                    renderState(after);
                    actionStatus.setText("Aplicado: MIN " + formatFreq(after.min) + " • MAX " + formatFreq(after.max));
                }
                setBusy(false, null);
                applyButton.setEnabled(true);
                restoreButton.setEnabled(true);
            });
        });
    }

    private void restoreOriginal() {
        if (state == null) return;
        String savedPath = prefs.getString("original_path", "");
        if (!state.path.equals(savedPath)) {
            Toast.makeText(this, "Não há limites originais salvos para esta GPU.", Toast.LENGTH_LONG).show();
            return;
        }
        long originalMin = prefs.getLong("original_min", 0);
        long originalMax = prefs.getLong("original_max", 0);
        setBusy(true, "Restaurando limites originais…");
        executor.execute(() -> {
            String p = q(state.path);
            StringBuilder cmd = new StringBuilder();
            cmd.append("echo 0 > ").append(p).append("/min_freq && echo 0 > ").append(p).append("/max_freq");
            if (originalMax > 0) cmd.append(" && echo ").append(originalMax).append(" > ").append(p).append("/max_freq");
            if (originalMin > 0) cmd.append(" && echo ").append(originalMin).append(" > ").append(p).append("/min_freq");
            ShellResult r = Shell.run(cmd.toString());
            GpuState after = r.code == 0 ? detectGpu() : null;
            main.post(() -> {
                if (r.code != 0 || after == null) {
                    actionStatus.setText("Falha ao restaurar: " + compact(r.output));
                } else {
                    state = after;
                    renderState(after);
                    lockSwitch.setChecked(false);
                    actionStatus.setText("Limites originais restaurados.");
                }
                setBusy(false, null);
                applyButton.setEnabled(state != null && !state.frequencies.isEmpty());
                restoreButton.setEnabled(state != null);
            });
        });
    }

    private void saveOriginalIfNeeded(GpuState s) {
        String savedPath = prefs.getString("original_path", "");
        if (!s.path.equals(savedPath)) {
            prefs.edit()
                    .putString("original_path", s.path)
                    .putLong("original_min", s.min)
                    .putLong("original_max", s.max)
                    .apply();
        }
    }

    private int readGpuTemp() {
        String cmd = "for z in /sys/class/thermal/thermal_zone*; do [ -d \"$z\" ] || continue; " +
                "t=$(cat \"$z/type\" 2>/dev/null | tr '[:upper:]' '[:lower:]'); " +
                "case \"$t\" in *gpu*|*g3d*|*mali*) cat \"$z/temp\" 2>/dev/null; exit 0;; esac; done; exit 1";
        long v = parseLong(Shell.run(cmd).output);
        if (v > 0 && v < 1000) v *= 1000;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, v));
    }

    private String read(String path) {
        ShellResult r = Shell.run("cat " + q(path) + " 2>/dev/null");
        return r.code == 0 ? r.output.trim() : "";
    }

    private long readLong(String path) {
        return parseLong(read(path));
    }

    private static void addNumbers(Set<Long> out, String text) {
        if (text == null) return;
        for (String token : text.trim().split("\\s+")) {
            long v = parseLong(token.replaceAll("[^0-9]", ""));
            if (v > 0) out.add(v);
        }
    }

    private static void addTransitionFrequencies(Set<Long> out, String text) {
        if (text == null) return;
        for (String line : text.split("\\n")) {
            String cleaned = line.trim().replace("*", "");
            if (cleaned.isEmpty()) continue;
            String first = cleaned.split("\\s+")[0].replaceAll("[^0-9]", "");
            long v = parseLong(first);
            if (v > 1000) out.add(v);
        }
    }

    private static long parseLong(String s) {
        try {
            if (s == null) return 0;
            String digits = s.trim().split("\\s+")[0].replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0 : Long.parseLong(digits);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int nearestIndex(List<Long> list, long target) {
        if (list.isEmpty()) return 0;
        int best = 0;
        long distance = Math.abs(list.get(0) - target);
        for (int i = 1; i < list.size(); i++) {
            long d = Math.abs(list.get(i) - target);
            if (d < distance) {
                distance = d;
                best = i;
            }
        }
        return best;
    }

    private static String formatFreq(long value) {
        if (value <= 0) return "—";
        double mhz;
        if (value >= 10_000_000L) mhz = value / 1_000_000.0;
        else if (value >= 10_000L) mhz = value / 1_000.0;
        else mhz = value;
        return String.format(Locale.US, mhz >= 100 ? "%.0f MHz" : "%.1f MHz", mhz);
    }

    private static String formatTemp(int mc) {
        if (mc <= 0) return "não exposta pelo kernel";
        return String.format(Locale.US, "%.1f °C", mc / 1000.0);
    }

    private static String emptyDash(String s) {
        return TextUtils.isEmpty(s) ? "—" : s;
    }

    private static String compact(String s) {
        if (s == null) return "";
        String c = s.replace('\n', ' ').trim();
        return c.length() > 180 ? c.substring(0, 180) + "…" : c;
    }

    private static String q(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    private void setBusy(boolean busy, String message) {
        if (message != null) actionStatus.setText(message);
        applyButton.setEnabled(!busy && state != null && !state.frequencies.isEmpty());
        restoreButton.setEnabled(!busy && state != null);
    }

    private TextView info(LinearLayout parent, String key, String value) {
        TextView v = text(key + ": " + value, 14, 0xFFECEFF1);
        v.setPadding(dp(10), dp(10), dp(10), dp(10));
        v.setBackgroundColor(0xFF263238);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, 0, 0, dp(6));
        parent.addView(v, lp);
        return v;
    }

    private void section(LinearLayout parent, String value) {
        TextView v = text(value, 12, 0xFF90CAF9);
        v.setPadding(0, dp(18), 0, dp(6));
        parent.addView(v, matchWrap());
    }

    private void label(LinearLayout parent, String value) {
        TextView v = text(value, 13, 0xFFCFD8DC);
        v.setPadding(0, dp(8), 0, dp(4));
        parent.addView(v, matchWrap());
    }

    private Button button(String title) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(8), 0, 0);
        return lp;
    }

    private TextView text(String value, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class GpuState {
        String path;
        String name;
        String governor;
        long current;
        long min;
        long max;
        int temperatureMilliC;
        final List<Long> frequencies = new ArrayList<>();
    }

    private static final class ShellResult {
        final int code;
        final String output;

        ShellResult(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }
    }

    private static final class Shell {
        static ShellResult run(String command) {
            Process process = null;
            try {
                process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                StringBuilder out = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (out.length() > 0) out.append('\n');
                        out.append(line);
                    }
                }
                int code = process.waitFor();
                return new ShellResult(code, out.toString());
            } catch (Exception e) {
                return new ShellResult(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (process != null) process.destroy();
            }
        }
    }
}
