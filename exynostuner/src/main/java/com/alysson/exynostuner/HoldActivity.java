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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HoldActivity extends Activity {
    private static final int HOLD_STOP_TEMP_MC = 80_000;
    private static final int HOLD_RESUME_TEMP_MC = 74_000;
    private static final long HOLD_INTERVAL_MS = 750;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler timer = new Handler(Looper.getMainLooper());

    private Hardware.Snapshot snapshot;
    private boolean working;
    private boolean thermalPaused;
    private boolean holdActive;

    private TextView status;
    private TextView thermal;
    private TextView cooling;
    private LinearLayout targets;
    private Switch holdSwitch;
    private final List<TargetUi> targetUis = new ArrayList<>();
    private final List<HoldTarget> holdTargets = new ArrayList<>();
    private final Map<String, Original> originals = new LinkedHashMap<>();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (holdActive && !working) maintainHold();
            timer.postDelayed(this, HOLD_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        detect();
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
        TextView sub = text("CPU • GPU • MIF | trava frequências sem desativar proteção térmica", 12, 0xFF80CBC4);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub, match());

        status = card("Detectando root e hardware…");
        root.addView(status, margin(0,0,0,6));
        thermal = card("Temperatura: —");
        root.addView(thermal, margin(0,0,0,6));
        cooling = card("Thermal cooling: —");
        root.addView(cooling, margin(0,0,0,10));

        Button detect = button("REDETECTAR HARDWARE");
        detect.setOnClickListener(v -> detect());
        root.addView(detect, margin(0,0,0,8));

        TextView info = text("Escolha uma frequência e marque INCLUIR NO HOLD em cada domínio desejado. Ao ativar, o app tenta governor performance/userspace e mantém MIN=MAX somente enquanto não houver pressão térmica.", 12, 0xFFCFD8DC);
        info.setPadding(dp(10),dp(10),dp(10),dp(10));
        info.setBackgroundColor(0xFF172027);
        root.addView(info, margin(0,0,0,8));

        targets = new LinearLayout(this);
        targets.setOrientation(LinearLayout.VERTICAL);
        root.addView(targets, match());

        holdSwitch = new Switch(this);
        holdSwitch.setText("PERFORMANCE HOLD SEGURO");
        holdSwitch.setTextColor(Color.WHITE);
        holdSwitch.setTextSize(16);
        holdSwitch.setEnabled(false);
        holdSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (checked) enableHold(); else if (holdActive) disableHold(false);
        });
        root.addView(holdSwitch, margin(0,10,0,8));

        TextView safety = text("Proteção térmica permanece ATIVA. O Hold pausa a partir de 80 °C; também pausa quando um cooling device relevante está ativo com temperatura elevada. Ao sair desta tela, o Hold é desligado e os limites/governors anteriores são restaurados.", 12, 0xFFFFCC80);
        safety.setPadding(dp(10),dp(12),dp(10),dp(12));
        safety.setBackgroundColor(0xFF3A241B);
        root.addView(safety, match());
        return scroll;
    }

    private void detect() {
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
        targets.removeAllViews();
        targetUis.clear();
        holdSwitch.setEnabled(snapshot != null && snapshot.rooted);
        if (snapshot == null || !snapshot.rooted) {
            status.setText("ROOT não disponível. Conceda su no Magisk/KernelSU/APatch.");
            return;
        }
        status.setText("ROOT OK • " + snapshot.rootManager + "\nSelecione os domínios que quer manter travados.");
        for (Hardware.CpuPolicy c : snapshot.cpuPolicies) {
            TargetUi ui = TargetUi.cpu(c);
            targetUis.add(ui);
            targets.addView(ui.root, margin(0,0,0,8));
        }
        if (snapshot.gpu != null) {
            TargetUi ui = TargetUi.dev(snapshot.gpu, "GPU");
            targetUis.add(ui);
            targets.addView(ui.root, margin(0,0,0,8));
        }
        if (snapshot.mif != null && (snapshot.gpu == null || !snapshot.mif.path.equals(snapshot.gpu.path))) {
            TargetUi ui = TargetUi.dev(snapshot.mif, "MIF / MEMÓRIA");
            targetUis.add(ui);
            targets.addView(ui.root, margin(0,0,0,8));
        }
        if (targetUis.isEmpty()) targets.addView(card("Nenhum domínio ajustável foi encontrado."), match());
        updateThermalOnly();
    }

    private void enableHold() {
        if (snapshot == null || !snapshot.rooted || working) {
            holdSwitch.setChecked(false);
            return;
        }
        holdTargets.clear();
        originals.clear();
        for (TargetUi ui : targetUis) {
            if (!ui.include.isChecked()) continue;
            long freq = ui.selectedFreq();
            if (freq <= 0) continue;
            HoldTarget h = new HoldTarget(ui.cpu, ui.cpuPolicy, ui.dev, freq);
            holdTargets.add(h);
            if (ui.cpu) originals.put(ui.cpuPolicy.path, Original.cpu(ui.cpuPolicy));
            else originals.put(ui.dev.path, Original.dev(ui.dev));
        }
        if (holdTargets.isEmpty()) {
            toast("Marque pelo menos um domínio em INCLUIR NO HOLD.");
            holdSwitch.setChecked(false);
            return;
        }
        working = true;
        status.setText("Ativando Performance Hold…");
        executor.execute(() -> {
            StringBuilder report = new StringBuilder();
            for (HoldTarget h : holdTargets) {
                RootShell.Result g = h.cpu ? setCpuPerformanceGovernor(h.cpuPolicy, h.freq) : setDevPerformanceGovernor(h.dev, h.freq);
                RootShell.Result w = h.cpu ? Hardware.applyCpu(h.cpuPolicy, h.freq, h.freq) : Hardware.applyDev(h.dev, h.freq, h.freq);
                if (report.length() > 0) report.append('\n');
                report.append(h.label()).append(": governor ").append(g.code == 0 ? "OK" : "não alterado")
                        .append(" • clock ").append(w.code == 0 ? "OK" : "FALHA");
            }
            String result = report.toString();
            main.post(() -> {
                holdActive = true;
                thermalPaused = false;
                working = false;
                status.setText("HOLD ATIVO\n" + result);
            });
        });
    }

    private void disableHold(boolean leavingScreen) {
        if (!holdActive && originals.isEmpty()) return;
        holdActive = false;
        thermalPaused = false;
        working = true;
        executor.execute(() -> {
            restoreOriginals();
            main.post(() -> {
                originals.clear();
                holdTargets.clear();
                working = false;
                if (!leavingScreen) status.setText("Performance Hold desligado. Limites e governors anteriores restaurados.");
                if (!leavingScreen && holdSwitch.isChecked()) holdSwitch.setChecked(false);
            });
        });
    }

    private void maintainHold() {
        if (snapshot == null || holdTargets.isEmpty()) return;
        working = true;
        executor.execute(() -> {
            Hardware.LiveSnapshot live = Hardware.readLive(snapshot);
            String activeCooling = activeCoolingDevices();
            boolean coolingActive = !activeCooling.isEmpty();
            boolean block = live.hottestMilliC >= HOLD_STOP_TEMP_MC || (coolingActive && live.hottestMilliC >= 70_000);
            if (thermalPaused && live.hottestMilliC > HOLD_RESUME_TEMP_MC) block = true;

            int repaired = 0;
            if (!block) {
                for (HoldTarget h : holdTargets) {
                    if (!rangeMatches(h)) {
                        RootShell.Result r = h.cpu ? Hardware.applyCpu(h.cpuPolicy, h.freq, h.freq) : Hardware.applyDev(h.dev, h.freq, h.freq);
                        if (r.code == 0) repaired++;
                    }
                }
            }
            final int changed = repaired;
            final boolean blocked = block;
            main.post(() -> {
                thermalPaused = blocked;
                thermal.setText("Mais quente: " + live.hottestType + " " + Hardware.formatTemp(live.hottestMilliC) +
                        "\nHold: " + (blocked ? "PAUSADO POR SEGURANÇA" : "ATIVO"));
                cooling.setText("Thermal cooling: " + (activeCooling.isEmpty() ? "sem cooling relevante ativo" : activeCooling));
                if (blocked) status.setText("HOLD PAUSADO — proteção térmica/limite de potência está atuando. Não vou sobrescrever esse limite.");
                else status.setText("HOLD ATIVO • " + holdTargets.size() + " domínio(s) • correções nesta rodada: " + changed);
                working = false;
            });
        });
    }

    private void updateThermalOnly() {
        if (snapshot == null || !snapshot.rooted) return;
        executor.execute(() -> {
            Hardware.LiveSnapshot live = Hardware.readLive(snapshot);
            String c = activeCoolingDevices();
            main.post(() -> {
                thermal.setText("Mais quente: " + live.hottestType + " " + Hardware.formatTemp(live.hottestMilliC));
                cooling.setText("Thermal cooling: " + (c.isEmpty() ? "sem cooling relevante ativo" : c));
            });
        });
    }

    private boolean rangeMatches(HoldTarget h) {
        String cmd;
        if (h.cpu) {
            cmd = "a=$(cat " + RootShell.q(h.cpuPolicy.path + "/scaling_min_freq") + " 2>/dev/null); b=$(cat " + RootShell.q(h.cpuPolicy.path + "/scaling_max_freq") + " 2>/dev/null); echo ${a:-0}' '${b:-0}";
        } else {
            String p = RootShell.q(h.dev.path);
            cmd = "a=$(cat " + p + "/min_freq 2>/dev/null || cat " + p + "/scaling_min_freq 2>/dev/null); b=$(cat " + p + "/max_freq 2>/dev/null || cat " + p + "/scaling_max_freq 2>/dev/null); echo ${a:-0}' '${b:-0}";
        }
        RootShell.Result r = RootShell.run(cmd);
        String[] x = r.output.trim().split("\\s+");
        if (x.length < 2) return false;
        try { return Long.parseLong(x[0]) == h.freq && Long.parseLong(x[1]) == h.freq; }
        catch (Exception e) { return false; }
    }

    private RootShell.Result setCpuPerformanceGovernor(Hardware.CpuPolicy c, long freq) {
        String gov = RootShell.q(c.path + "/scaling_governor");
        String avail = RootShell.q(c.path + "/scaling_available_governors");
        String set = RootShell.q(c.path + "/scaling_setspeed");
        String cmd = "a=$(cat " + avail + " 2>/dev/null); " +
                "if echo \"$a\" | grep -qw performance; then echo performance > " + gov + "; " +
                "elif echo \"$a\" | grep -qw userspace; then echo userspace > " + gov + "; [ -w " + set + " ] && echo " + freq + " > " + set + " || true; fi";
        return RootShell.run(cmd);
    }

    private RootShell.Result setDevPerformanceGovernor(Hardware.DevDevice d, long freq) {
        String gov = RootShell.q(d.path + "/governor");
        String avail = RootShell.q(d.path + "/available_governors");
        String set = RootShell.q(d.path + "/userspace/set_freq");
        String cmd = "a=$(cat " + avail + " 2>/dev/null); " +
                "if echo \"$a\" | grep -qw performance; then echo performance > " + gov + "; " +
                "elif echo \"$a\" | grep -qw userspace; then echo userspace > " + gov + "; [ -w " + set + " ] && echo " + freq + " > " + set + " || true; fi";
        return RootShell.run(cmd);
    }

    private String activeCoolingDevices() {
        String cmd = "for c in /sys/class/thermal/cooling_device*; do [ -d \"$c\" ] || continue; ty=$(cat \"$c/type\" 2>/dev/null); st=$(cat \"$c/cur_state\" 2>/dev/null); " +
                "l=$(printf '%s' \"$ty\" | tr '[:upper:]' '[:lower:]'); case \"$l\" in *cpu*|*cpufreq*|*gpu*|*g3d*|*mali*|*devfreq*|*thermal*) [ \"${st:-0}\" -gt 0 ] 2>/dev/null && printf '%s=%s ' \"$ty\" \"$st\";; esac; done";
        RootShell.Result r = RootShell.run(cmd);
        return r.output.trim();
    }

    private void restoreOriginals() {
        for (HoldTarget h : holdTargets) {
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

        private TargetUi(boolean cpu, Hardware.CpuPolicy c, Hardware.DevDevice d, String title, List<Long> f, long current) {
            this.cpu = cpu; this.cpuPolicy = c; this.dev = d; this.frequencies = f;
            root = panel();
            root.addView(text(title, 15, 0xFF90CAF9), match());
            if (!cpu && d != null) root.addView(text(d.path, 10, 0xFF90A4AE), match());
            root.addView(text("Atual: " + Hardware.formatFreq(current), 13, Color.WHITE), match());
            spinner = new Spinner(HoldActivity.this);
            List<String> labels = new ArrayList<>();
            for (Long x : f) labels.add(Hardware.formatFreq(x));
            spinner.setAdapter(new ArrayAdapter<>(HoldActivity.this, android.R.layout.simple_spinner_dropdown_item, labels));
            if (!f.isEmpty()) spinner.setSelection(Hardware.nearestIndex(f, current));
            spinner.setEnabled(!f.isEmpty());
            root.addView(text("FREQUÊNCIA ALVO", 11, 0xFF80CBC4), match());
            root.addView(spinner, match());
            include = new Switch(HoldActivity.this);
            include.setText("INCLUIR NO HOLD");
            include.setTextColor(Color.WHITE);
            include.setChecked(false);
            root.addView(include, match());
        }

        static TargetUi cpu(Hardware.CpuPolicy c) {
            return new TargetUi(true, c, null, c.title() + " • governor " + c.governor, c.frequencies, c.current);
        }
        static TargetUi dev(Hardware.DevDevice d, String kind) {
            return new TargetUi(false, null, d, kind + ": " + d.label() + " • governor " + d.governor, d.frequencies, d.current);
        }
        long selectedFreq() {
            int p = spinner.getSelectedItemPosition();
            return p >= 0 && p < frequencies.size() ? frequencies.get(p) : 0;
        }
    }

    private static final class HoldTarget {
        final boolean cpu;
        final Hardware.CpuPolicy cpuPolicy;
        final Hardware.DevDevice dev;
        final long freq;
        HoldTarget(boolean cpu, Hardware.CpuPolicy c, Hardware.DevDevice d, long freq) { this.cpu=cpu; this.cpuPolicy=c; this.dev=d; this.freq=freq; }
        String path() { return cpu ? cpuPolicy.path : dev.path; }
        String label() { return cpu ? cpuPolicy.title() : dev.label(); }
    }

    private static final class Original {
        final long min, max; final String governor;
        Original(long min, long max, String gov) { this.min=min; this.max=max; this.governor=gov == null ? "" : gov; }
        static Original cpu(Hardware.CpuPolicy c) { return new Original(c.min, c.max, c.governor); }
        static Original dev(Hardware.DevDevice d) { return new Original(d.min, d.max, d.governor); }
    }

    private LinearLayout panel() {
        LinearLayout x = new LinearLayout(this); x.setOrientation(LinearLayout.VERTICAL); x.setPadding(dp(10),dp(10),dp(10),dp(10)); x.setBackgroundColor(0xFF1B252B); return x;
    }
    private TextView card(String s) { TextView v=text(s,13,0xFFECEFF1); v.setPadding(dp(10),dp(10),dp(10),dp(10)); v.setBackgroundColor(0xFF202A30); return v; }
    private Button button(String s) { Button b=new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private TextView text(String s,int sp,int color) { TextView v=new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); return v; }
    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams margin(int l,int t,int r,int b) { LinearLayout.LayoutParams p=match(); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }
    private int dp(int v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
}
