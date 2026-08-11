package com.alysson.exynostuner;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler monitorHandler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private Hardware.Snapshot snapshot;
    private boolean busy;
    private long prevCpuTotal;
    private long prevCpuIdle;

    private TextView rootInfo;
    private TextView monitorInfo;
    private TextView thermalInfo;
    private TextView actionInfo;
    private TextView devfreqInfo;
    private LinearLayout cpuContainer;
    private LinearLayout gpuContainer;
    private LinearLayout mifContainer;
    private Switch liveSwitch;
    private Button applyCpuButton;
    private Button applyGpuButton;
    private Button applyMifButton;
    private Button restoreButton;

    private final List<CpuUi> cpuUis = new ArrayList<>();
    private DevUi gpuUi;
    private DevUi mifUi;

    private final Runnable monitorTick = new Runnable() {
        @Override public void run() {
            if (liveSwitch != null && liveSwitch.isChecked() && snapshot != null && snapshot.rooted && !busy) {
                updateLive();
            }
            monitorHandler.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("exynos_tuner", MODE_PRIVATE);
        setContentView(buildUi());
        detectHardware();
    }

    @Override protected void onStart() {
        super.onStart();
        monitorHandler.post(monitorTick);
    }

    @Override protected void onStop() {
        monitorHandler.removeCallbacks(monitorTick);
        super.onStop();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(7, 10, 13));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(root);

        TextView title = text("Exynos 2100 Tuner", 28, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, match());
        TextView sub = text("ROOT • CPU clusters • Mali GPU • MIF / memória • monitor ao vivo", 12, 0xFF80CBC4);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(3), 0, dp(14));
        root.addView(sub, match());

        section(root, "ROOT / ESTADO");
        rootInfo = card("Solicitando root…");
        root.addView(rootInfo, matchMargin(0, 0, 0, 6));

        monitorInfo = card("Monitor: aguardando detecção…");
        root.addView(monitorInfo, matchMargin(0, 0, 0, 6));
        thermalInfo = card("Temperaturas: —");
        root.addView(thermalInfo, matchMargin(0, 0, 0, 6));

        liveSwitch = new Switch(this);
        liveSwitch.setText("Monitoramento ao vivo ~800 ms");
        liveSwitch.setTextColor(Color.WHITE);
        liveSwitch.setChecked(true);
        root.addView(liveSwitch, matchMargin(0, 4, 0, 4));

        Button detect = button("REDETECTAR HARDWARE / SYSFS");
        detect.setOnClickListener(v -> detectHardware());
        root.addView(detect, matchMargin(0, 4, 0, 4));

        section(root, "CPU — POLICIES / CLUSTERS");
        cpuContainer = new LinearLayout(this);
        cpuContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(cpuContainer, match());
        applyCpuButton = button("APLICAR CLOCKS DA CPU");
        applyCpuButton.setEnabled(false);
        applyCpuButton.setOnClickListener(v -> applyCpu());
        root.addView(applyCpuButton, matchMargin(0, 6, 0, 4));

        section(root, "GPU — MALI / DEVFREQ");
        gpuContainer = new LinearLayout(this);
        gpuContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(gpuContainer, match());
        applyGpuButton = button("APLICAR CLOCK DA GPU");
        applyGpuButton.setEnabled(false);
        applyGpuButton.setOnClickListener(v -> applyDevice(true));
        root.addView(applyGpuButton, matchMargin(0, 6, 0, 4));

        section(root, "MEMÓRIA — MIF / CONTROLADORA");
        TextView mifNote = text("No Exynos, este bloco controla o domínio MIF/devfreq quando o kernel o expõe. Isso não é uma leitura direta de MHz do chip LPDDR como em um PC.", 12, 0xFFFFD180);
        mifNote.setPadding(dp(8), dp(8), dp(8), dp(8));
        mifNote.setBackgroundColor(0xFF332A16);
        root.addView(mifNote, matchMargin(0, 0, 0, 6));
        mifContainer = new LinearLayout(this);
        mifContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(mifContainer, match());
        applyMifButton = button("APLICAR CLOCK DO MIF / MEMÓRIA");
        applyMifButton.setEnabled(false);
        applyMifButton.setOnClickListener(v -> applyDevice(false));
        root.addView(applyMifButton, matchMargin(0, 6, 0, 4));

        section(root, "RESTAURAÇÃO");
        restoreButton = button("RESTAURAR TODOS OS LIMITES SALVOS");
        restoreButton.setEnabled(false);
        restoreButton.setOnClickListener(v -> restoreAll());
        root.addView(restoreButton, matchMargin(0, 0, 0, 6));

        actionInfo = card("Nenhuma alteração aplicada.");
        root.addView(actionInfo, matchMargin(0, 0, 0, 8));

        section(root, "DIAGNÓSTICO DEVFREQ");
        devfreqInfo = text("—", 11, 0xFFCFD8DC);
        devfreqInfo.setPadding(dp(8), dp(8), dp(8), dp(8));
        devfreqInfo.setBackgroundColor(0xFF172027);
        root.addView(devfreqInfo, match());

        TextView warning = text(
                "SEGURANÇA: o app só oferece frequências que o kernel expõe, não altera voltagem, não desliga thermal throttling e não aplica nada automaticamente no boot. Forçar MIN=MAX em clocks altos aumenta consumo e temperatura; o kernel ainda pode reduzir clocks por proteção térmica.",
                12, 0xFFFFCC80);
        warning.setPadding(dp(10), dp(12), dp(10), dp(12));
        warning.setBackgroundColor(0xFF3A241B);
        root.addView(warning, matchMargin(0, 12, 0, 0));
        return scroll;
    }

    private void detectHardware() {
        if (busy) return;
        setBusy(true, "Detectando policies CPU, GPU, MIF e sensores…");
        executor.execute(() -> {
            Hardware.Snapshot s = Hardware.detect();
            main.post(() -> {
                snapshot = s;
                renderHardware(s);
                setBusy(false, s.rooted ? "Detecção concluída." : "Root não concedido.");
            });
        });
    }

    private void renderHardware(Hardware.Snapshot s) {
        cpuContainer.removeAllViews();
        gpuContainer.removeAllViews();
        mifContainer.removeAllViews();
        cpuUis.clear();
        gpuUi = null;
        mifUi = null;

        if (!s.rooted) {
            rootInfo.setText("ROOT: NÃO DISPONÍVEL / NEGADO\n" + s.rootManager + "\nConceda acesso su ao Exynos 2100 Tuner e toque em REDETECTAR.");
            rootInfo.setTextColor(0xFFEF9A9A);
            monitorInfo.setText("Monitor: controles de sysfs aguardando root.");
            thermalInfo.setText("Temperaturas: —");
            devfreqInfo.setText("Sem diagnóstico root.");
            setControlsEnabled(false);
            return;
        }

        rootInfo.setText("ROOT: OK • uid=0\nGerenciador: " + s.rootManager);
        rootInfo.setTextColor(0xFF81C784);

        if (s.cpuPolicies.isEmpty()) {
            cpuContainer.addView(card("Nenhuma policy cpufreq encontrada."), match());
        } else {
            for (Hardware.CpuPolicy c : s.cpuPolicies) {
                CpuUi ui = new CpuUi(c);
                cpuUis.add(ui);
                cpuContainer.addView(ui.root, matchMargin(0, 0, 0, 8));
                saveOriginalCpu(c);
            }
        }

        if (s.gpu == null) {
            gpuContainer.addView(card("GPU devfreq não identificada. Veja DIAGNÓSTICO DEVFREQ."), match());
        } else {
            gpuUi = new DevUi(s.gpu, "GPU");
            gpuContainer.addView(gpuUi.root, match());
            saveOriginalDev(s.gpu, "gpu");
        }

        if (s.mif == null) {
            mifContainer.addView(card("MIF/DMC/DRAM devfreq não identificado neste kernel. O monitor ainda lista todos os devfreq abaixo."), match());
        } else {
            mifUi = new DevUi(s.mif, "MIF");
            mifContainer.addView(mifUi.root, match());
            saveOriginalDev(s.mif, "mif");
        }

        thermalInfo.setText("Temperaturas: " + (s.thermalSummary.isEmpty() ? "—" : s.thermalSummary));
        devfreqInfo.setText(s.devfreqSummary);
        setControlsEnabled(true);
        updateLive();
    }

    private void updateLive() {
        final Hardware.Snapshot s = snapshot;
        if (s == null || !s.rooted || busy) return;
        executor.execute(() -> {
            Hardware.LiveSnapshot live = Hardware.readLive(s);
            float cpuLoad = readCpuLoad();
            main.post(() -> renderLive(live, cpuLoad));
        });
    }

    private void renderLive(Hardware.LiveSnapshot live, float cpuLoad) {
        if (snapshot == null) return;
        StringBuilder m = new StringBuilder();
        m.append(String.format(Locale.US, "CPU carga: %.0f%%", cpuLoad));
        for (CpuUi ui : cpuUis) {
            Long v = live.currentByPath.get(ui.policy.path);
            if (v != null) {
                ui.current.setText("Atual: " + Hardware.formatFreq(v) + " • governor " + empty(ui.policy.governor));
                m.append("\n").append(ui.policy.title()).append(": ").append(Hardware.formatFreq(v));
            }
        }
        if (gpuUi != null) {
            Long v = live.currentByPath.get(gpuUi.device.path);
            if (v != null) {
                gpuUi.current.setText("Atual: " + Hardware.formatFreq(v) + " • governor " + empty(gpuUi.device.governor));
                m.append("\nGPU: ").append(Hardware.formatFreq(v));
            }
        }
        if (mifUi != null) {
            Long v = live.currentByPath.get(mifUi.device.path);
            if (v != null) {
                mifUi.current.setText("Atual: " + Hardware.formatFreq(v) + " • governor " + empty(mifUi.device.governor));
                m.append("\nMIF: ").append(Hardware.formatFreq(v));
            }
        }
        monitorInfo.setText(m.toString());
        thermalInfo.setText("Temperaturas: " + live.thermalSummary +
                (live.hottestMilliC > 0 ? "\nMais quente: " + live.hottestType + " " + Hardware.formatTemp(live.hottestMilliC) : ""));
        snapshot.hottestMilliC = live.hottestMilliC;
    }

    private void applyCpu() {
        if (!readyToWrite()) return;
        if (cpuUis.isEmpty()) return;
        List<Selection> selections = new ArrayList<>();
        for (CpuUi ui : cpuUis) {
            Selection x = ui.selection();
            if (x == null) continue;
            selections.add(x);
        }
        if (selections.isEmpty()) return;
        setBusy(true, "Aplicando limites da CPU…");
        executor.execute(() -> {
            StringBuilder report = new StringBuilder();
            boolean ok = true;
            for (int i = 0; i < selections.size(); i++) {
                CpuUi ui = cpuUis.get(i);
                Selection x = selections.get(i);
                RootShell.Result r = Hardware.applyCpu(ui.policy, x.min, x.max);
                if (report.length() > 0) report.append('\n');
                report.append(ui.policy.title()).append(": ").append(r.code == 0 ? "OK" : "FALHA " + compact(r.output));
                if (r.code != 0) ok = false;
            }
            final boolean success = ok;
            final String txt = report.toString();
            main.post(() -> {
                actionInfo.setText("CPU:\n" + txt);
                toast(success ? "Limites da CPU aplicados." : "Algum cluster recusou a alteração.");
                setBusy(false, null);
                detectHardware();
            });
        });
    }

    private void applyDevice(boolean gpu) {
        if (!readyToWrite()) return;
        DevUi ui = gpu ? gpuUi : mifUi;
        if (ui == null) return;
        Selection x = ui.selection();
        if (x == null) return;
        setBusy(true, "Aplicando " + (gpu ? "GPU" : "MIF") + "…");
        executor.execute(() -> {
            RootShell.Result r = Hardware.applyDev(ui.device, x.min, x.max);
            main.post(() -> {
                if (r.code == 0) {
                    actionInfo.setText((gpu ? "GPU" : "MIF") + " aplicado: MIN " + Hardware.formatFreq(x.min) + " • MAX " + Hardware.formatFreq(x.max));
                    toast("Alteração aplicada.");
                } else {
                    actionInfo.setText("Kernel recusou: " + compact(r.output));
                    toast("Kernel recusou a alteração.");
                }
                setBusy(false, null);
                detectHardware();
            });
        });
    }

    private void restoreAll() {
        if (snapshot == null || !snapshot.rooted || busy) return;
        setBusy(true, "Restaurando valores salvos…");
        executor.execute(() -> {
            StringBuilder r = new StringBuilder();
            for (Hardware.CpuPolicy c : snapshot.cpuPolicies) {
                long min = prefs.getLong(key("cpu_min", c.path), 0);
                long max = prefs.getLong(key("cpu_max", c.path), 0);
                if (min > 0 && max > 0) {
                    RootShell.Result x = Hardware.restoreCpu(c, min, max);
                    r.append(c.title()).append(' ').append(x.code == 0 ? "OK" : "FALHA").append('\n');
                }
            }
            if (snapshot.gpu != null) restoreOneDev(snapshot.gpu, "gpu", r);
            if (snapshot.mif != null) restoreOneDev(snapshot.mif, "mif", r);
            String report = r.toString();
            main.post(() -> {
                actionInfo.setText("Restauração:\n" + report);
                setBusy(false, null);
                detectHardware();
            });
        });
    }

    private void restoreOneDev(Hardware.DevDevice d, String prefix, StringBuilder r) {
        long min = prefs.getLong(key(prefix + "_min", d.path), 0);
        long max = prefs.getLong(key(prefix + "_max", d.path), 0);
        if (min > 0 && max > 0) {
            RootShell.Result x = Hardware.restoreDev(d, min, max);
            r.append(prefix.toUpperCase(Locale.US)).append(' ').append(x.code == 0 ? "OK" : "FALHA").append('\n');
        }
    }

    private boolean readyToWrite() {
        if (snapshot == null || !snapshot.rooted) {
            toast("Root não disponível.");
            return false;
        }
        if (busy) return false;
        if (snapshot.hottestMilliC >= Hardware.WRITE_TEMP_LIMIT_MC) {
            toast("Temperatura acima de 85 °C. Alteração bloqueada até esfriar.");
            return false;
        }
        return true;
    }

    private void saveOriginalCpu(Hardware.CpuPolicy c) {
        String km = key("cpu_min", c.path);
        if (!prefs.contains(km) && c.min > 0 && c.max > 0) {
            prefs.edit().putLong(km, c.min).putLong(key("cpu_max", c.path), c.max).apply();
        }
    }

    private void saveOriginalDev(Hardware.DevDevice d, String prefix) {
        String km = key(prefix + "_min", d.path);
        if (!prefs.contains(km) && d.min > 0 && d.max > 0) {
            prefs.edit().putLong(km, d.min).putLong(key(prefix + "_max", d.path), d.max).apply();
        }
    }

    private float readCpuLoad() {
        RootShell.Result r = RootShell.direct("head -n 1 /proc/stat");
        String[] p = r.output.trim().split("\\s+");
        if (p.length < 5) return 0f;
        try {
            long user = Long.parseLong(p[1]);
            long nice = Long.parseLong(p[2]);
            long system = Long.parseLong(p[3]);
            long idle = Long.parseLong(p[4]);
            long iowait = p.length > 5 ? Long.parseLong(p[5]) : 0;
            long irq = p.length > 6 ? Long.parseLong(p[6]) : 0;
            long soft = p.length > 7 ? Long.parseLong(p[7]) : 0;
            long steal = p.length > 8 ? Long.parseLong(p[8]) : 0;
            long total = user + nice + system + idle + iowait + irq + soft + steal;
            long idleAll = idle + iowait;
            long dt = total - prevCpuTotal;
            long di = idleAll - prevCpuIdle;
            prevCpuTotal = total;
            prevCpuIdle = idleAll;
            if (dt <= 0) return 0f;
            return Math.max(0f, Math.min(100f, 100f * (dt - di) / dt));
        } catch (Exception e) {
            return 0f;
        }
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        if (message != null) actionInfo.setText(message);
        setControlsEnabled(!value && snapshot != null && snapshot.rooted);
    }

    private void setControlsEnabled(boolean enabled) {
        applyCpuButton.setEnabled(enabled && !cpuUis.isEmpty());
        applyGpuButton.setEnabled(enabled && gpuUi != null && gpuUi.canWrite());
        applyMifButton.setEnabled(enabled && mifUi != null && mifUi.canWrite());
        restoreButton.setEnabled(enabled);
    }

    private final class CpuUi {
        final Hardware.CpuPolicy policy;
        final LinearLayout root;
        final TextView current;
        final Spinner min;
        final Spinner max;
        final Switch lock;

        CpuUi(Hardware.CpuPolicy c) {
            policy = c;
            root = panel();
            TextView t = text(c.title(), 15, 0xFF90CAF9);
            root.addView(t, match());
            current = text("Atual: " + Hardware.formatFreq(c.current) + " • governor " + empty(c.governor), 13, Color.WHITE);
            root.addView(current, matchMargin(0, 3, 0, 3));
            TextView hw = text("HW: " + Hardware.formatFreq(c.hwMin) + " – " + Hardware.formatFreq(c.hwMax) +
                    " • escrita min/max: " + yesNo(c.minWritable && c.maxWritable), 11, 0xFFB0BEC5);
            root.addView(hw, matchMargin(0, 0, 0, 4));
            min = spinner(c.frequencies, c.min);
            max = spinner(c.frequencies, c.max);
            addLabeled(root, "MIN", min);
            addLabeled(root, "MAX", max);
            lock = new Switch(MainActivity.this);
            lock.setText("Travar este cluster em MIN = MAX");
            lock.setTextColor(Color.WHITE);
            root.addView(lock, match());
        }

        Selection selection() {
            if (policy.frequencies.isEmpty()) return null;
            int a = min.getSelectedItemPosition();
            int b = max.getSelectedItemPosition();
            if (a < 0 || b < 0) return null;
            long lo = policy.frequencies.get(a);
            long hi = policy.frequencies.get(b);
            if (lock.isChecked()) lo = hi;
            if (lo > hi) { toast("CPU: MIN não pode ser maior que MAX."); return null; }
            return new Selection(lo, hi);
        }
    }

    private final class DevUi {
        final Hardware.DevDevice device;
        final LinearLayout root;
        final TextView current;
        final Spinner min;
        final Spinner max;
        final Switch lock;

        DevUi(Hardware.DevDevice d, String kind) {
            device = d;
            root = panel();
            root.addView(text(kind + ": " + d.label(), 15, 0xFF90CAF9), match());
            root.addView(text(d.path, 10, 0xFF90A4AE), matchMargin(0, 2, 0, 3));
            current = text("Atual: " + Hardware.formatFreq(d.current) + " • governor " + empty(d.governor), 13, Color.WHITE);
            root.addView(current, matchMargin(0, 2, 0, 3));
            root.addView(text("Faixa atual: " + Hardware.formatFreq(d.min) + " – " + Hardware.formatFreq(d.max) +
                    " • escrita min/max: " + yesNo(canWrite()), 11, 0xFFB0BEC5), matchMargin(0, 0, 0, 4));
            min = spinner(d.frequencies, d.min);
            max = spinner(d.frequencies, d.max);
            addLabeled(root, "MIN", min);
            addLabeled(root, "MAX", max);
            lock = new Switch(MainActivity.this);
            lock.setText("Travar em MIN = MAX");
            lock.setTextColor(Color.WHITE);
            root.addView(lock, match());
        }

        boolean canWrite() { return device.minWritable && device.maxWritable && !device.frequencies.isEmpty(); }

        Selection selection() {
            if (device.frequencies.isEmpty()) return null;
            int a = min.getSelectedItemPosition();
            int b = max.getSelectedItemPosition();
            if (a < 0 || b < 0) return null;
            long lo = device.frequencies.get(a);
            long hi = device.frequencies.get(b);
            if (lock.isChecked()) lo = hi;
            if (lo > hi) { toast("MIN não pode ser maior que MAX."); return null; }
            return new Selection(lo, hi);
        }
    }

    private static final class Selection {
        final long min;
        final long max;
        Selection(long min, long max) { this.min = min; this.max = max; }
    }

    private Spinner spinner(List<Long> values, long selected) {
        Spinner s = new Spinner(this);
        List<String> labels = new ArrayList<>();
        for (Long v : values) labels.add(Hardware.formatFreq(v));
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        s.setAdapter(a);
        if (!values.isEmpty()) s.setSelection(Hardware.nearestIndex(values, selected));
        s.setEnabled(!values.isEmpty());
        return s;
    }

    private void addLabeled(LinearLayout root, String label, Spinner spinner) {
        TextView l = text(label, 11, 0xFF80CBC4);
        l.setPadding(0, dp(3), 0, 0);
        root.addView(l, match());
        root.addView(spinner, match());
    }

    private LinearLayout panel() {
        LinearLayout x = new LinearLayout(this);
        x.setOrientation(LinearLayout.VERTICAL);
        x.setPadding(dp(10), dp(10), dp(10), dp(10));
        x.setBackgroundColor(0xFF1B252B);
        return x;
    }

    private TextView card(String value) {
        TextView v = text(value, 13, 0xFFECEFF1);
        v.setPadding(dp(10), dp(10), dp(10), dp(10));
        v.setBackgroundColor(0xFF202A30);
        return v;
    }

    private void section(LinearLayout root, String value) {
        TextView v = text(value, 12, 0xFF80CBC4);
        v.setPadding(0, dp(16), 0, dp(6));
        root.addView(v, match());
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        return b;
    }

    private TextView text(String value, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchMargin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = match();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String v) { Toast.makeText(this, v, Toast.LENGTH_LONG).show(); }
    private static String yesNo(boolean v) { return v ? "SIM" : "NÃO"; }
    private static String empty(String v) { return v == null || v.trim().isEmpty() ? "—" : v.trim(); }
    private static String compact(String v) {
        if (v == null) return "";
        String x = v.replace('\n', ' ').trim();
        return x.length() > 180 ? x.substring(0, 180) + "…" : x;
    }
    private static String key(String prefix, String path) { return prefix + "_" + Integer.toHexString(path.hashCode()); }
}
