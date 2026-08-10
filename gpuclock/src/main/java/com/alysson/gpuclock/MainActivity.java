package com.alysson.gpuclock;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
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
    private static final int TEST_TEMP_LIMIT_MC = 82_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView rootStatus;
    private TextView bootStatus;
    private TextView rootManagerStatus;
    private TextView deviceStatus;
    private TextView currentClock;
    private TextView governorView;
    private TextView temperatureView;
    private TextView actionStatus;
    private TextView testReport;
    private Spinner minSpinner;
    private Spinner maxSpinner;
    private Switch lockSwitch;
    private Button applyButton;
    private Button restoreButton;
    private Button testButton;

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

        TextView title = text("GPU Root & Clock Test", 26, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView subtitle = text("Exynos / Mali • Adreno • devfreq • ROOT", 13, 0xFF90CAF9);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle, matchWrap());

        section(root, "DIAGNÓSTICO DE ROOT");
        rootStatus = info(root, "Root", "Verificando…");
        bootStatus = info(root, "Bootloader / Verified Boot", "Lendo…");
        rootManagerStatus = info(root, "Gerenciador root", "—");

        Button rootDiagButton = button("VERIFICAR ROOT / BOOTLOADER");
        rootDiagButton.setOnClickListener(v -> refresh());
        root.addView(rootDiagButton, buttonParams());

        Button rootGuideButton = button("ABRIR GUIA OFICIAL DO MAGISK");
        rootGuideButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/topjohnwu/Magisk/blob/master/docs/install.md")));
            } catch (Exception e) {
                Toast.makeText(this, "Não foi possível abrir o navegador.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(rootGuideButton, buttonParams());

        section(root, "GPU");
        deviceStatus = info(root, "GPU / devfreq", "Detectando…");
        currentClock = info(root, "Clock atual", "—");
        governorView = info(root, "Governor", "—");
        temperatureView = info(root, "Temperatura GPU", "—");

        section(root, "TESTE DE FREQUÊNCIAS ACEITAS");
        testButton = button("TESTAR TODAS AS FREQUÊNCIAS EXPOSTAS");
        testButton.setEnabled(false);
        testButton.setOnClickListener(v -> testFrequencies());
        root.addView(testButton, buttonParams());

        testReport = text("Nenhum teste executado.", 12, 0xFFCFD8DC);
        testReport.setPadding(dp(10), dp(10), dp(10), dp(10));
        testReport.setBackgroundColor(0xFF1E2A30);
        root.addView(testReport, matchWrap());

        section(root, "CONTROLE MANUAL");
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
                "O APK não explora vulnerabilidades e não consegue desbloquear o bootloader sozinho. Ele solicita su quando Magisk/KernelSU/APatch já fornecem root. O teste usa somente frequências expostas pelo kernel, mantém a proteção térmica e restaura os limites após cada tentativa. Não altera voltagem e não aplica nada no boot.",
                12, 0xFFFFCC80);
        warning.setPadding(dp(10), dp(12), dp(10), dp(12));
        warning.setBackgroundColor(0xFF3E2723);
        root.addView(warning, matchWrap());

        return scroll;
    }

    private void refresh() {
        setBusy(true, "Verificando bootloader, root e GPU…");
        executor.execute(() -> {
            String boot = buildBootDiagnostic();
            ShellResult rootCheck = Shell.run("id");
            boolean rooted = rootCheck.code == 0 && rootCheck.output.contains("uid=0");
            String manager = rooted ? detectRootManager() : detectSuBinary();
            GpuState loaded = rooted ? detectGpu() : null;

            main.post(() -> {
                bootStatus.setText("Bootloader / Verified Boot: " + boot);
                rootManagerStatus.setText("Gerenciador root: " + manager);
                if (!rooted) {
                    showNoRoot(rootCheck.output);
                    return;
                }

                rootStatus.setText("Root: OK (uid=0 / su concedido)");
                rootStatus.setTextColor(0xFF81C784);
                if (loaded == null) {
                    deviceStatus.setText("GPU / devfreq: não encontrado");
                    actionStatus.setText("Root funciona, mas o kernel não expôs uma interface GPU devfreq reconhecida.");
                    state = null;
                    setBusy(false, null);
                    return;
                }
                state = loaded;
                saveOriginalIfNeeded(loaded);
                renderState(loaded);
                setBusy(false, "Pronto para testar.");
            });
        });
    }

    private String buildBootDiagnostic() {
        String flashLocked = prop("ro.boot.flash.locked");
        String vbmeta = prop("ro.boot.vbmeta.device_state");
        String verified = prop("ro.boot.verifiedbootstate");
        String warranty = prop("ro.boot.warranty_bit");
        StringBuilder s = new StringBuilder();
        if (!flashLocked.isEmpty()) {
            s.append("flash.locked=").append(flashLocked);
            if ("0".equals(flashLocked)) s.append(" (desbloqueado)");
            else if ("1".equals(flashLocked)) s.append(" (bloqueado)");
        }
        if (!vbmeta.isEmpty()) appendPart(s, "vbmeta=" + vbmeta);
        if (!verified.isEmpty()) appendPart(s, "AVB=" + verified);
        if (!warranty.isEmpty()) appendPart(s, "warranty_bit=" + warranty);
        if (s.length() == 0) return "propriedades não expostas";
        return s.toString();
    }

    private static void appendPart(StringBuilder s, String value) {
        if (s.length() > 0) s.append(" • ");
        s.append(value);
    }

    private String prop(String name) {
        ShellResult r = Shell.runDirect("getprop " + name);
        return r.code == 0 ? r.output.trim() : "";
    }

    private String detectSuBinary() {
        ShellResult r = Shell.runDirect("command -v su 2>/dev/null || which su 2>/dev/null");
        return r.code == 0 && !r.output.trim().isEmpty()
                ? "su encontrado em " + r.output.trim() + ", mas acesso não foi concedido"
                : "su não encontrado";
    }

    private String detectRootManager() {
        ShellResult r = Shell.run(
                "if command -v magisk >/dev/null 2>&1; then printf 'Magisk '; magisk -V 2>/dev/null || true; " +
                "elif [ -d /data/adb/ksu ] || command -v ksud >/dev/null 2>&1; then echo KernelSU; " +
                "elif [ -d /data/adb/ap ] || command -v apd >/dev/null 2>&1; then echo APatch; " +
                "else echo 'su/root disponível'; fi");
        return r.code == 0 && !r.output.trim().isEmpty() ? r.output.trim() : "su/root disponível";
    }

    private void showNoRoot(String detail) {
        rootStatus.setText("Root: indisponível ou negado");
        rootStatus.setTextColor(0xFFEF9A9A);
        deviceStatus.setText("GPU / devfreq: teste de escrita exige root");
        currentClock.setText("Clock atual: —");
        governorView.setText("Governor: —");
        temperatureView.setText("Temperatura GPU: —");
        state = null;
        actionStatus.setText("O APK não consegue criar root por exploit. Desbloqueie o bootloader quando o aparelho permitir, instale Magisk/KernelSU/APatch e depois conceda su. " + compact(detail));
        setBusy(false, null);
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
        s.availableGovernors = read(path + "/available_governors");
        s.current = readLong(path + "/cur_freq");
        if (s.current <= 0 && path.contains("kgsl")) s.current = readLong("/sys/class/kgsl/kgsl-3d0/gpuclk");
        s.min = readLong(path + "/min_freq");
        s.max = readLong(path + "/max_freq");
        s.temperatureMilliC = readGpuTemp();
        s.minWritable = rootWritable(path + "/min_freq");
        s.maxWritable = rootWritable(path + "/max_freq");

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

    private boolean rootWritable(String path) {
        ShellResult r = Shell.run("[ -w " + q(path) + " ] && echo yes || echo no");
        return r.output.trim().equals("yes");
    }

    private void renderState(GpuState s) {
        deviceStatus.setText("GPU / devfreq: " + s.name + "\n" + s.path +
                "\nEscrita: min=" + yesNo(s.minWritable) + " • max=" + yesNo(s.maxWritable));
        currentClock.setText("Clock atual: " + formatFreq(s.current));
        governorView.setText("Governor: " + emptyDash(s.governor) +
                (TextUtils.isEmpty(s.availableGovernors) ? "" : "\nDisponíveis: " + s.availableGovernors));
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

    private static String yesNo(boolean value) {
        return value ? "SIM" : "NÃO";
    }

    private void testFrequencies() {
        if (state == null || state.frequencies.isEmpty()) return;
        if (!state.minWritable || !state.maxWritable) {
            Toast.makeText(this, "O kernel não permite escrita em min_freq/max_freq mesmo com root.", Toast.LENGTH_LONG).show();
            return;
        }
        if (state.temperatureMilliC >= TEST_TEMP_LIMIT_MC) {
            Toast.makeText(this, "GPU acima de 82 °C. Teste bloqueado por segurança.", Toast.LENGTH_LONG).show();
            return;
        }

        final GpuState snapshot = state;
        final List<Long> frequencies = new ArrayList<>(snapshot.frequencies);
        final long originalMin = snapshot.min;
        final long originalMax = snapshot.max;
        setBusy(true, "Testando " + frequencies.size() + " frequências e restaurando após cada uma…");
        testReport.setText("Teste em andamento…");

        executor.execute(() -> {
            StringBuilder report = new StringBuilder();
            report.append("GPU: ").append(snapshot.name).append('\n');
            report.append("Original: MIN ").append(formatFreq(originalMin)).append(" • MAX ").append(formatFreq(originalMax)).append("\n\n");
            int limitsAccepted = 0;
            int observed = 0;
            int attempted = 0;
            boolean thermalAbort = false;

            try {
                for (long f : frequencies) {
                    if (Thread.currentThread().isInterrupted()) break;
                    int temp = readGpuTemp();
                    if (temp >= TEST_TEMP_LIMIT_MC) {
                        report.append("ABORTADO: temperatura chegou a ").append(formatTemp(temp)).append(".\n");
                        thermalAbort = true;
                        break;
                    }
                    attempted++;
                    long liveMin = readLong(snapshot.path + "/min_freq");
                    long liveMax = readLong(snapshot.path + "/max_freq");
                    ShellResult write = setExactFrequency(snapshot.path, f, liveMin, liveMax);
                    if (write.code != 0) {
                        report.append("❌ ").append(formatFreq(f)).append(" — escrita recusada: ").append(compact(write.output)).append('\n');
                        restoreLimits(snapshot.path, originalMin, originalMax);
                        sleepQuiet(160);
                        continue;
                    }

                    sleepQuiet(380);
                    long gotMin = readLong(snapshot.path + "/min_freq");
                    long gotMax = readLong(snapshot.path + "/max_freq");
                    boolean accepted = gotMin == f && gotMax == f;
                    boolean seen = false;
                    long lastCurrent = 0;
                    for (int sample = 0; sample < 3; sample++) {
                        lastCurrent = readCurrent(snapshot.path);
                        if (lastCurrent == f) seen = true;
                        sleepQuiet(180);
                    }

                    if (accepted) limitsAccepted++;
                    if (seen) observed++;
                    if (accepted && seen) {
                        report.append("✅ ").append(formatFreq(f)).append(" — ACEITA e observada em cur_freq\n");
                    } else if (accepted) {
                        report.append("⚠ ").append(formatFreq(f)).append(" — limites aceitos; cur_freq observado: ").append(formatFreq(lastCurrent)).append('\n');
                    } else {
                        report.append("❌ ").append(formatFreq(f)).append(" — kernel alterou/recusou (MIN ")
                                .append(formatFreq(gotMin)).append(" • MAX ").append(formatFreq(gotMax)).append(")\n");
                    }

                    restoreLimits(snapshot.path, originalMin, originalMax);
                    sleepQuiet(180);
                }
            } finally {
                restoreLimits(snapshot.path, originalMin, originalMax);
            }

            report.append("\nResumo: ").append(limitsAccepted).append('/').append(attempted)
                    .append(" aceitaram os limites; ").append(observed).append('/').append(attempted)
                    .append(" foram observadas em cur_freq.");
            if (thermalAbort) report.append(" Teste interrompido por temperatura.");

            GpuState after = detectGpu();
            String finalReport = report.toString();
            main.post(() -> {
                testReport.setText(finalReport);
                if (after != null) {
                    state = after;
                    renderState(after);
                }
                actionStatus.setText("Teste concluído. Limites originais restaurados.");
                setBusy(false, null);
            });
        });
    }

    private ShellResult setExactFrequency(String path, long target, long currentMin, long currentMax) {
        String minFile = q(path + "/min_freq");
        String maxFile = q(path + "/max_freq");
        String cmd;
        if (target < currentMin) {
            cmd = "echo " + target + " > " + minFile + " && echo " + target + " > " + maxFile;
        } else if (target > currentMax) {
            cmd = "echo " + target + " > " + maxFile + " && echo " + target + " > " + minFile;
        } else {
            cmd = "echo " + target + " > " + maxFile + " && echo " + target + " > " + minFile;
        }
        return Shell.run(cmd);
    }

    private void restoreLimits(String path, long originalMin, long originalMax) {
        if (originalMin <= 0 || originalMax <= 0) return;
        long currentMax = readLong(path + "/max_freq");
        String minFile = q(path + "/min_freq");
        String maxFile = q(path + "/max_freq");
        String cmd;
        if (originalMin > currentMax) {
            cmd = "echo " + originalMax + " > " + maxFile + " && echo " + originalMin + " > " + minFile;
        } else {
            cmd = "echo " + originalMin + " > " + minFile + " && echo " + originalMax + " > " + maxFile;
        }
        Shell.run(cmd);
    }

    private long readCurrent(String path) {
        long value = readLong(path + "/cur_freq");
        if (value <= 0 && path.contains("kgsl")) value = readLong("/sys/class/kgsl/kgsl-3d0/gpuclk");
        return value;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        if (!state.minWritable || !state.maxWritable) {
            Toast.makeText(this, "O kernel não permite escrita nos limites de GPU.", Toast.LENGTH_LONG).show();
            return;
        }

        final long targetMin = min;
        final long targetMax = max;
        setBusy(true, "Aplicando " + formatFreq(targetMin) + " – " + formatFreq(targetMax) + "…");
        executor.execute(() -> {
            long liveMin = readLong(state.path + "/min_freq");
            long liveMax = readLong(state.path + "/max_freq");
            ShellResult r;
            if (targetMin == targetMax) {
                r = setExactFrequency(state.path, targetMin, liveMin, liveMax);
            } else {
                String minFile = q(state.path + "/min_freq");
                String maxFile = q(state.path + "/max_freq");
                String cmd;
                if (targetMin > liveMax) {
                    cmd = "echo " + targetMax + " > " + maxFile + " && echo " + targetMin + " > " + minFile;
                } else {
                    cmd = "echo " + targetMin + " > " + minFile + " && echo " + targetMax + " > " + maxFile;
                }
                r = Shell.run(cmd);
            }
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
            restoreLimits(state.path, originalMin, originalMax);
            GpuState after = detectGpu();
            main.post(() -> {
                if (after == null) {
                    actionStatus.setText("Não foi possível confirmar a restauração.");
                } else {
                    state = after;
                    renderState(after);
                    lockSwitch.setChecked(false);
                    actionStatus.setText("Limites originais restaurados.");
                }
                setBusy(false, null);
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
        boolean ready = !busy && state != null && !state.frequencies.isEmpty();
        applyButton.setEnabled(ready);
        testButton.setEnabled(ready);
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
        String availableGovernors;
        long current;
        long min;
        long max;
        int temperatureMilliC;
        boolean minWritable;
        boolean maxWritable;
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
            return execute(new String[]{"su", "-c", command});
        }

        static ShellResult runDirect(String command) {
            return execute(new String[]{"/system/bin/sh", "-c", command});
        }

        private static ShellResult execute(String[] argv) {
            Process process = null;
            try {
                process = new ProcessBuilder(argv).redirectErrorStream(true).start();
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
