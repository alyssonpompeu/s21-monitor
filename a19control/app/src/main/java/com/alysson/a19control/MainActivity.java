package com.alysson.a19control;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private LinearLayout root;
    private TextView status;
    private TextView output;
    private final Map<String, SeekBar> marginBars = new LinkedHashMap<>();

    private static final String[][] MARGINS = new String[][]{
            {"CPU A55 / cpucl0", "/sys/kernel/percent_margin/cpucl0_margin_percent"},
            {"CPU A78 / cpucl1", "/sys/kernel/percent_margin/cpucl1_margin_percent"},
            {"CPU X1 / cpucl2", "/sys/kernel/percent_margin/cpucl2_margin_percent"},
            {"GPU G3D", "/sys/kernel/percent_margin/g3d_margin_percent"},
            {"MIF", "/sys/kernel/percent_margin/mif_margin_percent"},
            {"DSU", "/sys/kernel/percent_margin/dsu_margin_percent"},
            {"INT", "/sys/kernel/percent_margin/int_margin_percent"}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        checkRoot();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        root.addView(text("G991B A19 Control", 24, true));
        root.addView(text("Hybrid v1.3 / Exynos 2100 • controle por readback", 14, false));
        root.addView(text("Não cria OPP, não força OC inexistente e não desliga TMU/HOT/CRITICAL. Escritas só são tratadas como válidas quando o sysfs devolve o valor.", 13, false));
        root.addView(spacer(8));

        status = text("Root: verificando...", 15, true);
        root.addView(status);
        root.addView(text("Dispositivo: " + Build.MODEL + " • Android " + Build.VERSION.RELEASE, 13, false));

        root.addView(section("Leitura live"));
        addButton("Ler kernel / margens / DeX", v -> readLive());
        addButton("Listar modos de display expostos", v -> listDisplayModes());

        root.addView(section("Preset elétrico comprovado"));
        root.addView(text("Baseline validado: CPU A55/A78/X1 -7%, GPU -7%, MIF -1%, DSU -2%, INT -1%.", 13, false));
        addButton("Aplicar baseline v1.3 seguro", v -> applyBaseline());
        addButton("Margens elétricas 0%", v -> applyZeroMargins());
        addButton("Liberar UFCC (-1 / -1)", v -> applyUfcc());

        root.addView(section("Margens manuais (-15% a +15%)"));
        root.addView(text("A faixa completa é experimental. O histórico validou -7% nos domínios CPU/GPU; valores mais agressivos exigem teste de estabilidade.", 12, false));
        int[] defaults = new int[]{-7,-7,-7,-7,-1,-2,-1};
        for (int i = 0; i < MARGINS.length; i++) addMarginControl(MARGINS[i][0], MARGINS[i][1], defaults[i]);
        addButton("Aplicar margens manuais + readback", v -> applyManualMargins());

        root.addView(section("Samsung DeX / display externo"));
        root.addView(text("QHD padrão = 2560×1440. O pedido 120/165 Hz só entra se o HWC/monitor expuser um modo compatível; o app não inventa timing físico.", 12, false));
        addButton("Solicitar DeX QHD 120 Hz", v -> setDexMode(120));
        addButton("Solicitar DeX QHD 165 Hz (EXPERIMENTAL)", v -> setDexMode(165));
        addButton("Limpar preferência de modo DeX", v -> clearDexMode());

        root.addView(section("Saída / readback"));
        output = text("Aguardando comando.", 12, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        root.addView(output);

        setContentView(scroll);
    }

    private void addMarginControl(String label, String path, int def) {
        TextView row = text(label + ": " + signed(def) + "%", 14, true);
        root.addView(row);
        SeekBar bar = new SeekBar(this);
        bar.setMax(30);
        bar.setProgress(def + 15);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = progress - 15;
                row.setText(label + ": " + signed(v) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        marginBars.put(path, bar);
        root.addView(bar);
    }

    private String signed(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

    private TextView section(String s) {
        TextView t = text(s, 18, true);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private void addButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        root.addView(b);
    }

    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density + 0.5f); }

    private void checkRoot() {
        runRootAsync("Teste root", "id; echo __ROOT_OK__", false, result -> {
            boolean ok = result.contains("uid=0") || result.contains("__ROOT_OK__");
            status.setText(ok ? "Root: OK (Magisk/su)" : "Root: indisponível ou negado");
        });
    }

    private void readLive() {
        String cmd =
                "echo '=== CPU ==='; " +
                "for p in 0 4 7; do d=/sys/devices/system/cpu/cpufreq/policy$p; echo policy$p; for f in scaling_cur_freq scaling_max_freq cpuinfo_max_freq scaling_governor; do [ -r $d/$f ] && echo \"  $f=$(cat $d/$f)\"; done; done; " +
                "echo '=== MARGINS ==='; for f in /sys/kernel/percent_margin/*_margin_percent; do [ -r \"$f\" ] && echo \"$(basename $f)=$(cat $f)\"; done; " +
                "echo '=== UFCC ==='; for f in /sys/devices/platform/exynos-ufcc/ufc/cpufreq_max_limit /sys/devices/platform/exynos-ufcc/ufc/cpufreq_max_limit_strict; do [ -r \"$f\" ] && echo \"$(basename $f)=$(cat $f)\"; done; " +
                "echo '=== GPU candidates ==='; for f in /sys/kernel/gpu/gpu_clock /sys/kernel/gpu/gpu_max_clock /sys/kernel/gpu/gpu_governor /sys/devices/platform/18500000.mali/governor; do [ -r \"$f\" ] && echo \"$f=$(cat $f 2>/dev/null)\"; done; " +
                "echo '=== MIF candidates ==='; for d in /sys/class/devfreq/*mif* /sys/devices/platform/*mif*/devfreq/*; do [ -d \"$d\" ] || continue; echo \"$d\"; for f in cur_freq max_freq governor; do [ -r \"$d/$f\" ] && echo \"  $f=$(cat $d/$f)\"; done; done; " +
                "echo '=== DeX preferred ==='; cmd display get-user-preferred-display-mode 2>&1;";
        runRootAsync("Leitura live", cmd, true, null);
    }

    private void listDisplayModes() {
        String cmd = "echo '=== cmd display get-displays ==='; cmd display get-displays 2>&1; " +
                "echo '=== dumpsys display (modes) ==='; dumpsys display 2>/dev/null | grep -E 'DisplayDeviceInfo\\{|supportedModes=|supportedColorModes=|modeId|refreshRate|fps' | head -n 140";
        runRootAsync("Modos de display", cmd, true, null);
    }

    private String writeFn() {
        return "write_node(){ f=\"$1\"; v=\"$2\"; if [ -e \"$f\" ]; then echo \"$v\" > \"$f\" 2>/dev/null; r=$(cat \"$f\" 2>/dev/null); echo \"$f request=$v readback=$r\"; else echo \"$f MISSING\"; fi; }; ";
    }

    private void applyBaseline() {
        String cmd = writeFn() +
                "write_node /sys/kernel/percent_margin/cpucl0_margin_percent -7;" +
                "write_node /sys/kernel/percent_margin/cpucl1_margin_percent -7;" +
                "write_node /sys/kernel/percent_margin/cpucl2_margin_percent -7;" +
                "write_node /sys/kernel/percent_margin/g3d_margin_percent -7;" +
                "write_node /sys/kernel/percent_margin/mif_margin_percent -1;" +
                "write_node /sys/kernel/percent_margin/dsu_margin_percent -2;" +
                "write_node /sys/kernel/percent_margin/int_margin_percent -1;" +
                "write_node /sys/devices/platform/exynos-ufcc/ufc/cpufreq_max_limit -1;" +
                "write_node /sys/devices/platform/exynos-ufcc/ufc/cpufreq_max_limit_strict -1;";
        runRootAsync("Baseline v1.3 seguro", cmd, true, null);
    }

    private void applyZeroMargins() {
        StringBuilder cmd = new StringBuilder(writeFn());
        for (String[] m : MARGINS) cmd.append("write_node ").append(m[1]).append(" 0;");
        runRootAsync("Margens 0%", cmd.toString(), true, null);
    }

    private void applyUfcc() {
        String cmd = writeFn() +
                "write_node /sys/devices/platform/exynos-ufcc/ufc/cpufreq_max_limit -1;" +
                "write_node /sys/devices/platform/exynos-ufcc/ufc/cpufreq_max_limit_strict -1;";
        runRootAsync("UFCC", cmd, true, null);
    }

    private void applyManualMargins() {
        StringBuilder cmd = new StringBuilder(writeFn());
        for (String[] m : MARGINS) {
            SeekBar b = marginBars.get(m[1]);
            int v = b == null ? 0 : b.getProgress() - 15;
            cmd.append("write_node ").append(m[1]).append(" ").append(v).append(";");
        }
        runRootAsync("Margens manuais", cmd.toString(), true, null);
    }

    private void setDexMode(int hz) {
        String cmd = "echo 'Request: 2560x1440@" + hz + "'; " +
                "cmd display set-user-preferred-display-mode 2560 1440 " + hz + " 2>&1; " +
                "cmd display get-user-preferred-display-mode 2>&1; " +
                "echo 'Nota: preferred mode != garantia de modo físico ativo. Confirme em Lista de modos/monitor.'";
        runRootAsync("DeX QHD " + hz, cmd, true, null);
    }

    private void clearDexMode() {
        String cmd = "cmd display clear-user-preferred-display-mode 2>&1; cmd display get-user-preferred-display-mode 2>&1";
        runRootAsync("Limpar DeX override", cmd, true, null);
    }

    private interface ResultCallback { void onResult(String result); }

    private void runRootAsync(String label, String command, boolean showOutput, ResultCallback cb) {
        status.setText("Executando: " + label + "...");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            int code = -1;
            try {
                Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                code = p.waitFor();
            } catch (Exception e) {
                sb.append("ERRO: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
            }
            final int exit = code;
            final String result = sb.toString();
            runOnUiThread(() -> {
                status.setText("Último comando: " + label + " • exit=" + exit);
                if (showOutput && output != null) output.setText(result.length() == 0 ? "(sem saída)" : result);
                if (cb != null) cb.onResult(result);
            });
        }).start();
    }
}
