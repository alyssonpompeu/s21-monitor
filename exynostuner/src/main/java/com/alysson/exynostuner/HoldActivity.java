package com.alysson.exynostuner;

import android.app.Activity;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HoldActivity extends Activity {
    private static final int STOP_TEMP_MC = 80_000;
    private static final int RESUME_TEMP_MC = 74_000;
    private static final long INTERVAL_MS = 750;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler timer = new Handler(Looper.getMainLooper());

    private Hardware.Snapshot snapshot;
    private final List<TargetUi> targetUis = new ArrayList<>();
    private final List<HoldTarget> activeTargets = new ArrayList<>();
    private final Map<String, Original> originals = new LinkedHashMap<>();

    private LinearLayout targetsContainer;
    private TextView status;
    private TextView thermal;
    private TextView cooling;
    private Switch holdSwitch;
    private boolean holdActive;
    private boolean working;
    private boolean thermalPaused;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (holdActive && !working) maintainHold();
            timer.postDelayed(this, INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        detectHardware();
    }

    @Override protected void onStart() {
        super.onStart();
        timer.post(tick);
    }

    @Override protected void onStop() {
        timer.removeCallbacks(tick);
        if (holdActive) disableHold(true);
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

        TextView title = text("Exynos Performance Hold", 27, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, match());

        TextView sub = text("CPU • GPU • MIF | governor + MIN=MAX com proteção térmica ativa", 12, 0xFF80CBC4);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub, match());

        status = card("Detectando root e hardware…");
        root.addView(status, margin(0,0,0,6));
        thermal = card("Temperatura: —");
        root.addView(thermal, margin(0,0,0,6));
        cooling = card("Cooling devices: —");
        root.addView(cooling, margin(0,0,0,10));

        Button redetect = button("REDETECTAR HARDWARE");
        redetect.setOnClickListener(v -> detectHardware());
        root.addView(redetect, margin(0,0,0,8));

        TextView help = text("Em cada domínio, escolha a frequência e marque INCLUIR NO HOLD. O modo tenta usar governor performance/userspace e reaplica MIN=MAX se algum ajuste automático normal desfizer o limite.", 12, 0xFFCFD8DC);
        help.setPadding(dp(10),dp(10),dp(10),dp(10));
        help.setBackgroundColor(0xFF172027);
        root.addView(help, margin(0,0,0,8));

        targetsContainer = new LinearLayout(this);
        targetsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(targetsContainer, match());

        holdSwitch = new Switch(this);
        holdSwitch.setText("PERFORMANCE HOLD SEGURO");
        holdSwitch.setTextColor(Color.WHITE);
        holdSwitch.setTextSize(16);
        holdSwitch.setEnabled(false);
        holdSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (checked) enableHold();
            else if (holdActive) disableHold(false);
        });
        root.addView(holdSwitch, margin(0,10,0,8));

        TextView warning = text("O app NÃO desativa thermal throttling, thermal HAL, cooling devices, shutdown térmico ou limites de bateria. O Hold pausa em 80 °C e restaura os valores anteriores quando você sai desta tela.", 12, 0xFFFFCC80);
        warning.setPadding(dp(10),dp(12),dp(10),dp(12));
        warning.setBackgroundColor(0xFF3A241B);
        root.addView(warning, match());
        return scroll;
    }

    private void detectHardware() {
        if (working) return;
        working = true;
        status.setText("Detectando root, CPU, GPU e MIF…");
        executor.execute(() -> {
            Hardware.Snapshot s = Hardware.detect();
            main.post(() -> {
                snapshot = s;
                renderTargets();
                working = false;
            });
        });
    }

    private void renderTargets() {
        targetsContainer.removeAllViews();
        targetUis.clear();
        if (snapshot == null || !snapshot.rooted) {
            status.setText("ROOT indisponível ou negado. Conceda su no gerenciador root.");
            holdSwitch.setEnabled(false);
            return;
        }
        status.setText("ROOT OK • " + snapshot.rootManager + "\nEscolha as frequências que quer manter.");
        holdSwitch.setEnabled(true);

        for (Hardware.CpuPolicy c : snapshot.cpuPolicies) addCpuTarget(c);
        if (snapshot.gpu != null) addDevTarget(snapshot.gpu, "GPU");
        if (snapshot.mif != null && (snapshot.gpu == null || !snapshot.mif.path.equals(snapshot.gpu.path))) {
            addDevTarget(snapshot.mif, "MIF / MEMÓRIA");
        }
        if (targetUis.isEmpty()) targetsContainer.addView(card("Nenhum domínio ajustável encontrado."), match());
        refreshThermalInfo();
    }

    private void addCpuTarget(Hardware.CpuPolicy c) {
        TargetUi ui = new TargetUi(true, c, null, c.title() + " • governor " + c.governor, c.frequencies, c.current);
        targetUis.add(ui);
        targetsContainer.addView(ui.root, margin(0,0,0,8));
    }

    private void addDevTarget(Hardware.DevDevice d, String kind) {
        TargetUi ui = new TargetUi(false, null, d, kind + ": " + d.label() + " • governor " + d.governor, d.frequencies, d.current);
        targetUis.add(ui);
        targetsContainer.addView(ui.root, margin(0,0,0,8));
    }

    private void enableHold() {
        if (snapshot == null || !snapshot.rooted || working) {
            holdSwitch.setChecked(false);
            return;
        }
        activeTargets.clear();
        originals.clear();
        for (TargetUi ui : targetUis) {
            if (!ui.include.isChecked()) continue;
            long freq = ui.selectedFreq();
            if (freq <= 0) continue;
            HoldTarget h = new HoldTarget(ui.cpu, ui.cpuPolicy, ui.dev, freq);
            activeTargets.add(h);
            originals.put(h.path(), ui.cpu ? Original.cpu(ui.cpuPolicy) : Original.dev(ui.dev));
        }
        if (activeTargets.isEmpty()) {
            toast("Marque pelo menos um domínio em INCLUIR NO HOLD.");
            holdSwitch.setChecked(false);
            return;
        }

        working = true;
        status.setText("Ativando Hold…");
        executor.execute(() -> {
            StringBuilder report = new StringBuilder();
            for (HoldTarget h : activeTargets) {
                RootShell.Result gov = h.cpu ? setCpuGovernor(h.cpuPolicy, h.freq) : setDevGovernor(h.dev, h.freq);
                RootShell.Result range = h.cpu ? Hardware.applyCpu(h.cpuPolicy, h.freq, h.freq) : Hardware.applyDev(h.dev, h.freq, h.freq);
                if (report.length() > 0) report.append('\n');
                report.append(h.label()).append(" • governor ").append(gov.code == 0 ? "OK" : "inalterado")
                        .append(" • MIN=MAX ").append(range.code == 0 ? "OK" : "FALHA");
            }
            String text = report.toString();
            main.post(() -> {
                holdActive = true;
                thermalPaused = false;
                working = false;
                status.setText("HOLD ATIVO\n" + text);
            });
        });
    }

    private void disableHold(boolean leaving) {
        holdActive = false;
        thermalPaused = false;
        working = true;
        executor.execute(() -> {
            restoreOriginals();
            main.post(() -> {
                activeTargets.clear();
                originals.clear();
                working = false;
                if (!leaving) {
                    status.setText("Hold desligado. Limites e governors anteriores restaurados.");
                    if (holdSwitch.isChecked()) holdSwitch.setChecked(false);
                }
            });
        });
    }

    private void maintainHold() {
        if (snapshot == null || activeTargets.isEmpty()) return;
        working = true;
        executor.execute(() -> {
            Hardware.LiveSnapshot live = Hardware.readLive(snapshot);
            String coolingNow = activeCoolingDevices();
            boolean coolingActive = !coolingNow.isEmpty();
            boolean blocked = live.hottestMilliC >= STOP_TEMP_MC || (coolingActive && live.hottestMilliC >= 70_000);
            if (thermalPaused && live.hottestMilliC > RESUME_TEMP_MC) blocked = true;

            int repaired = 0;
            if (!blocked) {
                for (HoldTarget h : activeTargets) {
                    if (!rangeMatches(h)) {
                        RootShell.Result r = h.cpu ? Hardware.applyCpu(h.cpuPolicy, h.freq, h.freq) : Hardware.applyDev(h.dev, h.freq, h.freq);
                        if (r.code == 0) repaired++;
                    }
                }
            }

            int changed = repaired;
            boolean finalBlocked = blocked;
            main.post(() -> {
                thermalPaused = finalBlocked;
                thermal.setText("Mais quente: " + live.hottestType + " " + Hardware.formatTemp(live.hottestMilliC) +
                        "\nHold: " + (finalBlocked ? "PAUSADO POR SEGURANÇA" : "ATIVO"));
                cooling.setText("Cooling devices: " + (coolingNow.isEmpty() ? "nenhum relevante ativo" : coolingNow));
                status.setText(finalBlocked
                        ? "HOLD PAUSADO — o sistema térmico/potência está limitando. O app não vai sobrescrever essa proteção."
                        : "HOLD ATIVO • " + activeTargets.size() + " domínio(s) • limites recuperados nesta rodada: " + changed);
                working = false;
            });
        });
    }

    private void refreshThermalInfo() {
        if (snapshot == null || !snapshot.rooted) return;
        executor.execute(() -> {
            Hardware.LiveSnapshot live = Hardware.readLive(snapshot);
            String c = activeCoolingDevices();
            main.post(() -> {
                thermal.setText("Mais quente: " + live.hottestType + " " + Hardware.formatTemp(live.hottestMilliC));
                cooling.setText("Cooling devices: " + (c.isEmpty() ? "nenhum relevante ativo" : c));
            });
        });
    }

    private RootShell.Result setCpuGovernor(Hardware.CpuPolicy c, long freq) {
        String gov = RootShell.q(c.path + "/scaling_governor");
        String avail = RootShell.q(c.path + "/scaling_available_governors");
        String setspeed = RootShell.q(c.path + "/scaling_setspeed");
        String cmd = "a=$(cat " + avail + " 2>/dev/null); " +
                "if echo \"$a\" | grep -qw performance; then echo performance > " + gov + "; " +
                "elif echo \"$a\" | grep -qw userspace; then echo userspace > " + gov + "; [ -w " + setspeed + " ] && echo " + freq + " > " + setspeed + " || true; fi";
        return RootShell.run(cmd);
    }

    private RootShell.Result setDevGovernor(Hardware.DevDevice d, long freq) {
        String gov = RootShell.q(d.path + "/governor");
        String avail = RootShell.q(d.path + "/available_governors");
        String setspeed = RootShell.q(d.path + "/userspace/set_freq");
        String cmd = "a=$(cat " + avail + " 2>/dev/null); " +
                "if echo \"$a\" | grep -qw performance; then echo performance > " + gov + "; " +
                "elif echo \"$a\" | grep -qw userspace; then echo userspace > " + gov + "; [ -w " + setspeed + " ] && echo " + freq + " > " + setspeed + " || true; fi";
        return RootShell.run(cmd);
    }

    private boolean rangeMatches(HoldTarget h) {
        String cmd;
        if (h.cpu) {
            cmd = "printf '%s %s' \"$(cat " + RootShell.q(h.cpuPolicy.path + "/scaling_min_freq") + " 2>/dev/null)\" \"$(cat " + RootShell.q(h.cpuPolicy.path + "/scaling_max_freq") + " 2>/dev/null)\"";
        } else {
            String base = RootShell.q(h.dev.path);
            cmd = "a=$(cat " + base + "/min_freq 2>/dev/null || cat " + base + "/scaling_min_freq 2>/dev/null); " +
                    "b=$(cat " + base + "/max_freq 2>/dev/null || cat " + base + "/scaling_max_freq 2>/dev/null); printf '%s %s' \"$a\" \"$b\"";
        }
        String[] p = RootShell.run(cmd).output.trim().split("\\s+");
        if (p.length < 2) return false;
        try { return Long.parseLong(p[0]) == h.freq && Long.parseLong(p[1]) == h.freq; }
        catch (Exception e) { return false; }
    }

    private String activeCoolingDevices() {
        String cmd = "for c in /sys/class/thermal/cooling_device*; do [ -d \"$c\" ] || continue; " +
                "ty=$(cat \"$c/type\" 2>/dev/null); st=$(cat \"$c/cur_state\" 2>/dev/null); " +
                "l=$(printf '%s' \"$ty\" | tr '[:upper:]' '[:lower:]'); " +
                "case \"$l\" in *cpu*|*cpufreq*|*gpu*|*g3d*|*mali*|*devfreq*|*thermal*) [ \"${st:-0}\" -gt 0 ] 2>/dev/null && printf '%s=%s ' \"$ty\" \"$st\";; esac; done";
        return RootShell.run(cmd).output.trim();
    }

    private void restoreOriginals() {
        for (HoldTarget h : activeTargets) {
            Original o = originals.get(h.path());
            if (o == null) continue;
            if (h.cpu) {
                Hardware.applyCpu(h.cpuPolicy, o.min, o.max);
                if (!o.governor.isEmpty()) RootShell.run("echo " + RootShell.q(o.governor) + " > " + RootShell.q(h.cpuPolicy.path + "/scaling_governor"));
            } else {
                Hardware.applyDev(h.dev, o.min, o.max);
                if (!o.governor.isEmpty()) RootShell.run("echo " + RootShell.q(o.governor) + " > " + RootShell.q(h.dev.path + "/governor"));
            }
        }
    }

    private final class TargetUi {
        final LinearLayout root;
        final boolean cpu;
        final Hardware.CpuPolicy cpuPolicy;
        final Hardware.DevDevice dev;
        final List<Long> frequencies;
        final Spinner spinner;
        final Switch include;

        TargetUi(boolean cpu, Hardware.CpuPolicy c, Hardware.DevDevice d, String title, List<Long> values, long current) {
            this.cpu = cpu;
            this.cpuPolicy = c;
            this.dev = d;
            this.frequencies = values;
            root = panel();
            root.addView(text(title, 15, 0xFF90CAF9), match());
            if (!cpu && d != null) root.addView(text(d.path, 10, 0xFF90A4AE), match());
            root.addView(text("Atual: " + Hardware.formatFreq(current), 13, Color.WHITE), match());
            root.addView(text("FREQUÊNCIA ALVO", 11, 0xFF80CBC4), match());
            spinner = new Spinner(HoldActivity.this);
            List<String> labels = new ArrayList<>();
            for (Long f : values) labels.add(Hardware.formatFreq(f));
            spinner.setAdapter(new ArrayAdapter<>(HoldActivity.this, android.R.layout.simple_spinner_dropdown_item, labels));
            if (!values.isEmpty()) spinner.setSelection(Hardware.nearestIndex(values, current));
            spinner.setEnabled(!values.isEmpty());
            root.addView(spinner, match());
            include = new Switch(HoldActivity.this);
            include.setText("INCLUIR NO HOLD");
            include.setTextColor(Color.WHITE);
            root.addView(include, match());
        }

        long selectedFreq() {
            int pos = spinner.getSelectedItemPosition();
            return pos >= 0 && pos < frequencies.size() ? frequencies.get(pos) : 0;
        }
    }

    private static final class HoldTarget {
        final boolean cpu;
        final Hardware.CpuPolicy cpuPolicy;
        final Hardware.DevDevice dev;
        final long freq;
        HoldTarget(boolean cpu, Hardware.CpuPolicy c, Hardware.DevDevice d, long freq) {
            this.cpu = cpu; this.cpuPolicy = c; this.dev = d; this.freq = freq;
        }
        String path() { return cpu ? cpuPolicy.path : dev.path; }
        String label() { return cpu ? cpuPolicy.title() : dev.label(); }
    }

    private static final class Original {
        final long min, max;
        final String governor;
        Original(long min, long max, String governor) {
            this.min = min; this.max = max; this.governor = governor == null ? "" : governor;
        }
        static Original cpu(Hardware.CpuPolicy c) { return new Original(c.min, c.max, c.governor); }
        static Original dev(Hardware.DevDevice d) { return new Original(d.min, d.max, d.governor); }
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(10),dp(10),dp(10),dp(10));
        p.setBackgroundColor(0xFF1B252B);
        return p;
    }
    private TextView card(String s) { TextView v = text(s,13,0xFFECEFF1); v.setPadding(dp(10),dp(10),dp(10),dp(10)); v.setBackgroundColor(0xFF202A30); return v; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private TextView text(String s, int sp, int color) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); return v; }
    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams margin(int l,int t,int r,int b) { LinearLayout.LayoutParams p = match(); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
}
