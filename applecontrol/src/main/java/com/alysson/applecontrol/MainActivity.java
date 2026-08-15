package com.alysson.applecontrol;

import android.app.*;
import android.os.*;
import android.provider.MediaStore;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final String[] DOMAIN_NAMES = {
            "CPU A55 / cpucl0", "CPU A78 / cpucl1", "CPU X1 / cpucl2",
            "GPU G3D", "MIF", "DSU", "INT"
    };
    private static final String[] NODE_NAMES = {
            "cpucl0", "cpucl1", "cpucl2", "g3d", "mif", "dsu", "int"
    };
    private static final String[] NODES = {
            "/sys/kernel/percent_margin/cpucl0_margin_percent",
            "/sys/kernel/percent_margin/cpucl1_margin_percent",
            "/sys/kernel/percent_margin/cpucl2_margin_percent",
            "/sys/kernel/percent_margin/g3d_margin_percent",
            "/sys/kernel/percent_margin/mif_margin_percent",
            "/sys/kernel/percent_margin/dsu_margin_percent",
            "/sys/kernel/percent_margin/int_margin_percent"
    };
    private static final int[] APPLE_DEFAULT = {-8, -8, -8, -7, -2, -2, -2};

    private final SeekBar[] marginBars = new SeekBar[7];
    private final TextView[] marginLabels = new TextView[7];
    private TextView controlStatus, readbackView;
    private Button presetButton, applyButton;

    private TextView benchStatus, resultView;
    private ProgressBar progress;
    private Button fullButton, saveButton;
    private volatile String lastReport = "";
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("G991B Apple Control");
        title.setTextSize(27);
        title.setTextColor(Color.rgb(30, 75, 165));
        title.setTypeface(null, 1);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Apple v1.1 • Exynos 2100\nUndervolt manual + S21 Lab Benchmark");
        sub.setTextSize(15);
        sub.setPadding(0, dp(4), 0, dp(14));
        box.addView(sub);

        addSectionTitle(box, "Controle de tensão");

        TextView info = new TextView(this);
        info.setText("Padrão estável: A55/A78/X1 -8%, GPU -7%, MIF/DSU/INT -2%.\nFaixa manual: -15% a +15%. O valor só é aceito como aplicado após readback do sysfs.");
        info.setTextSize(14);
        info.setPadding(0, 0, 0, dp(8));
        box.addView(info);

        for (int i = 0; i < marginBars.length; i++) addMarginControl(box, i);

        presetButton = button("Padrão Apple v1.1");
        applyButton = button("Aplicar + verificar");
        box.addView(presetButton);
        box.addView(applyButton);

        controlStatus = new TextView(this);
        controlStatus.setText("Preset Apple v1.1 carregado. Nenhuma escrita feita nesta abertura.");
        controlStatus.setTextSize(14);
        controlStatus.setPadding(0, dp(6), 0, dp(4));
        box.addView(controlStatus);

        readbackView = new TextView(this);
        readbackView.setTypeface(android.graphics.Typeface.MONOSPACE);
        readbackView.setTextSize(12);
        readbackView.setTextIsSelectable(true);
        readbackView.setText("Readback aparecerá aqui após Aplicar + verificar.");
        box.addView(readbackView);

        presetButton.setOnClickListener(v -> {
            setPreset(APPLE_DEFAULT);
            controlStatus.setText("Padrão Apple v1.1 carregado nos sliders. Toque em Aplicar + verificar para escrever.");
        });
        applyButton.setOnClickListener(v -> applyMargins());

        addDivider(box);
        addSectionTitle(box, "S21 Lab Benchmark");

        TextView benchInfo = new TextView(this);
        benchInfo.setText("Mesmo workload FULL do S21 Lab: CPU, GPU OpenGL ES, RAM, armazenamento e soak CPU+GPU. Nenhum polling de tensão roda durante o teste.");
        benchInfo.setTextSize(14);
        benchInfo.setPadding(0, 0, 0, dp(8));
        box.addView(benchInfo);

        fullButton = button("Iniciar FULL");
        saveButton = button("Salvar log TXT");
        saveButton.setEnabled(false);
        box.addView(fullButton);
        box.addView(saveButton);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        box.addView(progress, new LinearLayout.LayoutParams(-1, dp(18)));

        benchStatus = new TextView(this);
        benchStatus.setText("Pronto.");
        benchStatus.setPadding(0, dp(8), 0, dp(8));
        benchStatus.setTextSize(16);
        box.addView(benchStatus);

        resultView = new TextView(this);
        resultView.setTextIsSelectable(true);
        resultView.setTypeface(android.graphics.Typeface.MONOSPACE);
        resultView.setTextSize(12);
        resultView.setText("Execute o FULL. Depois salve o TXT final.");
        box.addView(resultView);

        fullButton.setOnClickListener(v -> runBench());
        saveButton.setOnClickListener(v -> saveReport());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);

        setPreset(APPLE_DEFAULT);
    }

    private void addSectionTitle(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(22);
        t.setTypeface(null, 1);
        t.setPadding(0, dp(6), 0, dp(8));
        box.addView(t);
    }

    private void addDivider(LinearLayout box) {
        View v = new View(this);
        v.setBackgroundColor(Color.rgb(205, 205, 205));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(0, dp(18), 0, dp(12));
        box.addView(v, lp);
    }

    private void addMarginControl(LinearLayout box, int index) {
        TextView label = new TextView(this);
        label.setTextSize(16);
        label.setTypeface(null, 1);
        box.addView(label);
        marginLabels[index] = label;

        SeekBar bar = new SeekBar(this);
        bar.setMax(30);
        bar.setProgress(APPLE_DEFAULT[index] + 15);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateMarginLabel(index);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        marginBars[index] = bar;
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        updateMarginLabel(index);
    }

    private void updateMarginLabel(int index) {
        if (marginLabels[index] == null || marginBars[index] == null) return;
        int value = marginBars[index].getProgress() - 15;
        marginLabels[index].setText(DOMAIN_NAMES[index] + ": " + (value > 0 ? "+" : "") + value + "%");
    }

    private void setPreset(int[] values) {
        for (int i = 0; i < marginBars.length; i++) {
            if (marginBars[i] != null) marginBars[i].setProgress(values[i] + 15);
        }
    }

    private int[] currentMargins() {
        int[] out = new int[marginBars.length];
        for (int i = 0; i < marginBars.length; i++) out[i] = marginBars[i].getProgress() - 15;
        return out;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private void applyMargins() {
        final int[] requested = currentMargins();
        setControllerEnabled(false);
        fullButton.setEnabled(false);
        controlStatus.setText("Solicitando root e aplicando margens...");
        readbackView.setText("");

        exec.submit(() -> {
            RootResult rr = runRoot(buildMarginCommand(requested));
            boolean ok = rr.exitCode == 0 && readbackMatches(rr.output, requested);
            runOnUiThread(() -> {
                readbackView.setText("Solicitado: " + formatValues(requested) + "\n\n" + rr.output);
                if (ok) {
                    controlStatus.setText("OK — escrita e readback confirmados.");
                } else if (rr.exitCode == 126 || rr.exitCode == 127) {
                    controlStatus.setText("Root/su indisponível. Nenhum valor foi tratado como confirmado.");
                } else {
                    controlStatus.setText("ATENÇÃO — readback não confirmou todos os valores. exit=" + rr.exitCode);
                }
                setControllerEnabled(true);
                fullButton.setEnabled(true);
            });
        });
    }

    private String buildMarginCommand(int[] v) {
        StringBuilder s = new StringBuilder();
        s.append("write_node(){ [ -e \"$1\" ] && [ -w \"$1\" ] && printf '%s\\n' \"$2\" > \"$1\" 2>/dev/null; }; ");
        for (int i = 0; i < NODES.length; i++) {
            s.append("write_node ").append(NODES[i]).append(' ').append(v[i]).append("; ");
        }
        s.append("echo '=== READBACK ==='; ");
        for (int i = 0; i < NODES.length; i++) {
            s.append("printf '").append(NODE_NAMES[i]).append("='; ")
                    .append("if [ -r ").append(NODES[i]).append(" ]; then cat ").append(NODES[i])
                    .append("; else echo NA; fi; ");
        }
        return s.toString();
    }

    private RootResult runRoot(String command) {
        try {
            Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            boolean done = p.waitFor(15, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new RootResult(124, "TIMEOUT aguardando su/root");
            }
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append('\n');
            }
            return new RootResult(p.exitValue(), out.toString().trim());
        } catch (IOException e) {
            return new RootResult(127, "Falha ao executar su: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RootResult(130, "Operação interrompida");
        }
    }

    private boolean readbackMatches(String output, int[] requested) {
        for (int i = 0; i < NODE_NAMES.length; i++) {
            String wanted = NODE_NAMES[i] + "=" + requested[i];
            boolean found = false;
            for (String line : output.split("\\n")) {
                if (line.trim().equals(wanted)) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    private String formatValues(int[] values) {
        return String.format(Locale.US, "A55=%d%% A78=%d%% X1=%d%% GPU=%d%% MIF=%d%% DSU=%d%% INT=%d%%",
                values[0], values[1], values[2], values[3], values[4], values[5], values[6]);
    }

    private void setControllerEnabled(boolean enabled) {
        presetButton.setEnabled(enabled);
        applyButton.setEnabled(enabled);
        for (SeekBar b : marginBars) if (b != null) b.setEnabled(enabled);
    }

    private static final class RootResult {
        final int exitCode;
        final String output;
        RootResult(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
    }

    // S21 Lab FULL: workload, durations, formulas and TXT fields intentionally
    // preserved from the standalone S21 Lab Benchmark supplied by the user.
    private void runBench() {
        fullButton.setEnabled(false);
        saveButton.setEnabled(false);
        setControllerEnabled(false);
        lastReport = "";
        resultView.setText("");
        progress.setProgress(0);

        exec.submit(() -> {
            try {
                final long singleMs = 12000;
                final long multiMs = 20000;
                final long gpuMs = 18000;
                final long memMs = 12000;
                final int storageMB = 256;
                final long soakMs = 180000;

                ui("CPU single-core...", 5);
                double single = Benchmarks.cpuSingleMops(singleMs);

                ui("CPU multi-core...", 18);
                int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
                double multi = Benchmarks.cpuMultiMops(multiMs, threads);

                ui("GPU OpenGL ES...", 35);
                GpuBench.Result gpu = GpuBench.run(gpuMs);

                ui("Memória RAM...", 52);
                double memBw = Benchmarks.memoryBandwidthMBs(memMs);
                double memLat = Benchmarks.memoryLatencyMops(Math.max(3000, memMs / 2));

                ui("Armazenamento...", 66);
                Benchmarks.StorageResult storage = Benchmarks.storage(getCacheDir(), storageMB);

                ui("CPU + GPU / thermal soak...", 78);
                final double[] soakCpu = new double[1];
                Thread cpuSoak = new Thread(() -> {
                    try { soakCpu[0] = Benchmarks.cpuMultiMops(soakMs, threads); }
                    catch (InterruptedException ignored) {}
                }, "S21LabSoakCPU");
                cpuSoak.start();
                GpuBench.Result soakGpu = GpuBench.run(soakMs);
                cpuSoak.join();

                ui("Gerando TXT final...", 96);
                lastReport = buildReport(threads, single, multi, gpu, memBw, memLat,
                        storage, soakCpu[0], soakGpu);

                runOnUiThread(() -> {
                    resultView.setText(lastReport);
                    progress.setProgress(100);
                    benchStatus.setText("Concluído. Toque em Salvar log TXT.");
                    fullButton.setEnabled(true);
                    saveButton.setEnabled(true);
                    setControllerEnabled(true);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    benchStatus.setText("Erro: " + e);
                    fullButton.setEnabled(true);
                    setControllerEnabled(true);
                });
            }
        });
    }

    private void ui(String s, int p) {
        runOnUiThread(() -> { benchStatus.setText(s); progress.setProgress(p); });
    }

    private String buildReport(int threads,
                               double single, double multi, GpuBench.Result gpu,
                               double memBw, double memLat, Benchmarks.StorageResult storage,
                               double soakCpu, GpuBench.Result soakGpu) {
        double cpuScore = single * 90.0 + multi * 28.0;
        double gpuScore = gpu.drawsPerSecond * 140.0;
        double memScore = memBw * 7.0 + memLat * 900.0;
        double storageScore = (storage.writeMBs + storage.readMBs) * 4.0;
        double total = cpuScore * 0.36 + gpuScore * 0.34 + memScore * 0.20 + storageScore * 0.10;

        StringBuilder sb = new StringBuilder(4096);
        sb.append("S21 LAB BENCHMARK REPORT\n");
        sb.append("S21Lab Score v1.2\n");
        sb.append("mode=FULL\n");
        sb.append("timestamp=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        sb.append("model=").append(Build.MODEL).append('\n');
        sb.append("device=").append(Build.DEVICE).append('\n');
        sb.append("hardware=").append(Build.HARDWARE).append('\n');
        sb.append("build=").append(Build.DISPLAY).append('\n');
        sb.append("android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("kernel=").append(System.getProperty("os.version")).append('\n');
        sb.append("cpu_threads=").append(threads).append('\n');
        sb.append("refresh_current_hz=").append(getDisplay().getRefreshRate()).append("\n\n");

        sb.append("=== SCORE ===\n");
        sb.append(String.format(Locale.US, "TOTAL=%.0f\nCPU=%.0f\nGPU=%.0f\nMEM=%.0f\nSTORAGE=%.0f\n\n",
                total, cpuScore, gpuScore, memScore, storageScore));

        sb.append("=== RAW BENCHMARK ===\n");
        sb.append(String.format(Locale.US, "cpu_single_mops=%.3f\ncpu_multi_mops=%.3f\n", single, multi));
        sb.append(String.format(Locale.US, "gpu_draws_per_sec=%.3f\ngpu_status=%s\n", gpu.drawsPerSecond, gpu.status));
        sb.append(String.format(Locale.US, "ram_copy_MBps=%.2f\nram_latency_Mops=%.3f\n", memBw, memLat));
        sb.append(String.format(Locale.US, "storage_write_MBps=%.2f\nstorage_read_MBps=%.2f\n", storage.writeMBs, storage.readMBs));
        sb.append(String.format(Locale.US, "soak_cpu_multi_mops=%.3f\nsoak_gpu_draws_per_sec=%.3f\nsoak_gpu_status=%s\n",
                soakCpu, soakGpu.drawsPerSecond, soakGpu.status));
        sb.append("\ntxt_state=FINAL_CLOSED\nEND_REPORT\n");
        return sb.toString();
    }

    private void saveReport() {
        if (lastReport.isEmpty()) return;
        String name = "S21Lab_FULL_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
        cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        if (Build.VERSION.SDK_INT >= 29) cv.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) {
            Toast.makeText(this, "Falha ao criar arquivo.", Toast.LENGTH_LONG).show();
            return;
        }

        try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("OutputStream nulo");
            out.write(lastReport.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            getContentResolver().delete(uri, null, null);
            Toast.makeText(this, "Erro ao salvar: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
        }
        Toast.makeText(this, "Log final salvo em Downloads/" + name, Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }
}
