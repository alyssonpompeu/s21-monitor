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
import java.util.concurrent.atomic.AtomicBoolean;

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

    // Current Apple 1.3 UV profile. This is only a selectable preset; on every
    // Activity resume the sliders are replaced by the actual kernel readback.
    private static final int[] APPLE_DEFAULT = {-8, -9, -9, -8, -2, -2, -2};
    private static final long TELEMETRY_INTERVAL_MS = 2000L;

    private final SeekBar[] marginBars = new SeekBar[7];
    private final TextView[] marginLabels = new TextView[7];
    private TextView controlStatus, readbackView, temperatureView;
    private Button presetButton, applyButton;

    private TextView benchStatus, resultView;
    private ProgressBar progress;
    private Button fullButton, saveButton;
    private volatile String lastReport = "";

    private final ExecutorService benchExec = Executors.newSingleThreadExecutor();
    private final ExecutorService rootExec = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object rootLock = new Object();
    private KernelTelemetry.RootShell rootShell;
    private volatile String lastRootError = "";
    private volatile boolean activityVisible = false;
    private volatile boolean benchmarkRunning = false;
    private volatile KernelTelemetry.Snapshot latestSnapshot;

    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            if (!activityVisible || benchmarkRunning) return;
            rootExec.submit(() -> {
                KernelTelemetry.Snapshot s = readSnapshot(5000);
                if (s != null) {
                    latestSnapshot = s;
                    runOnUiThread(() -> temperatureView.setText(formatLiveTelemetry(s)));
                    if (activityVisible && !benchmarkRunning) handler.postDelayed(liveTick, 1000);
                } else {
                    runOnUiThread(() -> temperatureView.setText(
                            "Temperaturas: leitura indisponível.\nRoot/su: " + safeError(lastRootError)));
                }
            });
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        activityVisible = true;
        handler.removeCallbacks(liveTick);
        if (!benchmarkRunning) autoReadKernel();
    }

    @Override protected void onPause() {
        activityVisible = false;
        handler.removeCallbacks(liveTick);
        super.onPause();
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
        sub.setText("Apple 1.3 UV • Exynos 2100\nReadback automático + temperaturas + S21 Lab Benchmark");
        sub.setTextSize(15);
        sub.setPadding(0, dp(4), 0, dp(14));
        box.addView(sub);

        addSectionTitle(box, "Controle de tensão");

        TextView info = new TextView(this);
        info.setText("Perfil Apple 1.3 UV: A55 -8%, A78/X1 -9%, GPU -8%, MIF/DSU/INT -2%.\n" +
                "Ao abrir ou voltar ao app, os sliders são substituídos pelo readback REAL do kernel. " +
                "Se a leitura falhar, o app mostra N/D em vez de fingir que o preset está ativo.");
        info.setTextSize(14);
        info.setPadding(0, 0, 0, dp(8));
        box.addView(info);

        for (int i = 0; i < marginBars.length; i++) addMarginControl(box, i);

        presetButton = button("Padrão Apple 1.3 UV");
        applyButton = button("Aplicar + verificar");
        box.addView(presetButton);
        box.addView(applyButton);

        controlStatus = new TextView(this);
        controlStatus.setText("Lendo kernel automaticamente...");
        controlStatus.setTextSize(14);
        controlStatus.setPadding(0, dp(6), 0, dp(4));
        box.addView(controlStatus);

        readbackView = new TextView(this);
        readbackView.setTypeface(android.graphics.Typeface.MONOSPACE);
        readbackView.setTextSize(12);
        readbackView.setTextIsSelectable(true);
        readbackView.setText("Aguardando readback real do kernel.");
        box.addView(readbackView);

        presetButton.setOnClickListener(v -> {
            setPreset(APPLE_DEFAULT);
            controlStatus.setText("Perfil Apple 1.3 UV carregado nos sliders. Ainda NÃO foi escrito no kernel.");
        });
        applyButton.setOnClickListener(v -> applyMargins());

        addDivider(box);
        addSectionTitle(box, "Temperaturas live");

        TextView tempInfo = new TextView(this);
        tempInfo.setText("Leitura: A55/LITTLE, A78/MID, X1/BIG, GPU/G3D e MIF/DDR5 quando o firmware expõe um sensor correspondente. " +
                "MIF/DDR5 não é rotulado como temperatura física do die DRAM sem um sensor explícito.");
        tempInfo.setTextSize(13);
        tempInfo.setPadding(0, 0, 0, dp(6));
        box.addView(tempInfo);

        temperatureView = new TextView(this);
        temperatureView.setTypeface(android.graphics.Typeface.MONOSPACE);
        temperatureView.setTextSize(14);
        temperatureView.setText("Lendo temperaturas...");
        temperatureView.setPadding(0, 0, 0, dp(4));
        box.addView(temperatureView);

        addDivider(box);
        addSectionTitle(box, "S21 Lab Benchmark");

        TextView benchInfo = new TextView(this);
        benchInfo.setText("Mesmo workload e mesmas fórmulas do S21 Lab: CPU, GPU OpenGL ES, RAM, armazenamento e soak CPU+GPU. " +
                "Durante o FULL, a atualização visual para e uma telemetria root persistente coleta a cada 2 s, sem abrir um novo processo su por amostra. " +
                "O TXT inclui temperaturas, clocks e UV real do começo ao fim.");
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

        setMarginsUnknown("lendo kernel");
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
        label.setText(DOMAIN_NAMES[index] + ": N/D");
        box.addView(label);
        marginLabels[index] = label;

        SeekBar bar = new SeekBar(this);
        bar.setMax(30);
        bar.setProgress(APPLE_DEFAULT[index] + 15);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) updateMarginLabel(index);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        marginBars[index] = bar;
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }

    private void updateMarginLabel(int index) {
        if (marginLabels[index] == null || marginBars[index] == null) return;
        int value = marginBars[index].getProgress() - 15;
        marginLabels[index].setText(DOMAIN_NAMES[index] + ": " + (value > 0 ? "+" : "") + value + "%");
    }

    private void setMarginsUnknown(String why) {
        for (int i = 0; i < marginBars.length; i++) {
            marginBars[i].setProgress(APPLE_DEFAULT[i] + 15);
            marginLabels[i].setText(DOMAIN_NAMES[i] + ": N/D (" + why + ")");
        }
    }

    private void setPreset(int[] values) {
        for (int i = 0; i < marginBars.length; i++) {
            if (marginBars[i] != null) {
                marginBars[i].setProgress(values[i] + 15);
                updateMarginLabel(i);
            }
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

    private KernelTelemetry.RootShell ensureRootShell() throws IOException {
        synchronized (rootLock) {
            if (rootShell == null || !rootShell.isAlive()) {
                closeRootShellLocked();
                rootShell = KernelTelemetry.RootShell.open();
            }
            return rootShell;
        }
    }

    private void closeRootShellLocked() {
        if (rootShell != null) {
            try { rootShell.close(); } catch (Exception ignored) {}
            rootShell = null;
        }
    }

    private KernelTelemetry.Snapshot readSnapshot(long timeoutMs) {
        synchronized (rootLock) {
            try {
                KernelTelemetry.RootShell shell = ensureRootShell();
                KernelTelemetry.ExecResult rr = shell.exec(KernelTelemetry.snapshotCommand(), timeoutMs);
                if (rr.exitCode != 0) {
                    lastRootError = "exit=" + rr.exitCode + " " + rr.output;
                    if (rr.exitCode == 127 || rr.exitCode == 124) closeRootShellLocked();
                    return null;
                }
                KernelTelemetry.Snapshot s = KernelTelemetry.Snapshot.parse(rr.output);
                lastRootError = "";
                return s;
            } catch (Exception e) {
                lastRootError = e.getMessage() == null ? e.toString() : e.getMessage();
                closeRootShellLocked();
                return null;
            }
        }
    }

    private KernelTelemetry.ExecResult rootCommand(String command, long timeoutMs) {
        synchronized (rootLock) {
            try {
                KernelTelemetry.RootShell shell = ensureRootShell();
                KernelTelemetry.ExecResult rr = shell.exec(command, timeoutMs);
                if (rr.exitCode != 0) lastRootError = "exit=" + rr.exitCode + " " + rr.output;
                return rr;
            } catch (Exception e) {
                lastRootError = e.getMessage() == null ? e.toString() : e.getMessage();
                closeRootShellLocked();
                return new KernelTelemetry.ExecResult(127, lastRootError);
            }
        }
    }

    private void autoReadKernel() {
        controlStatus.setText("Lendo kernel automaticamente...");
        readbackView.setText("Aguardando readback real do kernel/root.");
        rootExec.submit(() -> {
            KernelTelemetry.Snapshot s = readSnapshot(7000);
            runOnUiThread(() -> {
                if (s != null && s.margins() != null) {
                    latestSnapshot = s;
                    setPreset(s.margins());
                    controlStatus.setText("OK — sliders sincronizados automaticamente com o kernel REAL.");
                    readbackView.setText(formatKernelReadback(s));
                    temperatureView.setText(formatLiveTelemetry(s));
                    if (activityVisible && !benchmarkRunning) handler.postDelayed(liveTick, 1000);
                } else {
                    setMarginsUnknown("readback indisponível");
                    controlStatus.setText("ATENÇÃO — não foi possível confirmar o UV do kernel. Conceda root no Magisk e reabra/volte ao app.");
                    readbackView.setText("Kernel readback: N/D\nRoot/su: " + safeError(lastRootError));
                    temperatureView.setText("Temperaturas: N/D enquanto root/readback estiver indisponível.");
                }
            });
        });
    }

    private String formatKernelReadback(KernelTelemetry.Snapshot s) {
        return "=== KERNEL READBACK REAL ===\n" +
                s.marginsText() + "\n" +
                "A55 cur/max=" + s.get("p0_cur") + "/" + s.get("p0_max") + " kHz\n" +
                "A78 cur/max=" + s.get("p4_cur") + "/" + s.get("p4_max") + " kHz\n" +
                "X1 cur/max=" + s.get("p7_cur") + "/" + s.get("p7_max") + " kHz\n" +
                "GPU clock=" + s.get("gpu_clock") + " kHz util=" + s.get("gpu_util") + "%\n" +
                "MIF cur/min/max=" + s.get("mif_cur") + "/" + s.get("mif_min") + "/" + s.get("mif_max") + " kHz";
    }

    private String formatLiveTelemetry(KernelTelemetry.Snapshot s) {
        String mifSource = s.get("mif_temp_source");
        String mifLabel = "NA".equals(mifSource) ? "sensor N/D" : mifSource;
        return "A55 / LITTLE  " + tempText(s, "little_temp") + "  | " + mhzText(s, "p0_cur") + "\n" +
                "A78 / MID     " + tempText(s, "mid_temp") + "  | " + mhzText(s, "p4_cur") + "\n" +
                "X1  / BIG     " + tempText(s, "big_temp") + "  | " + mhzText(s, "p7_cur") + "\n" +
                "GPU / G3D     " + tempText(s, "g3d_temp") + "  | " + mhzText(s, "gpu_clock") + "\n" +
                "MIF / DDR5    " + tempText(s, "mif_temp") + "  | " + mhzText(s, "mif_cur") + "  [" + mifLabel + "]";
    }

    private String tempText(KernelTelemetry.Snapshot s, String key) {
        Double v = s.tempC(key);
        return v == null ? "N/D" : String.format(Locale.US, "%5.1f °C", v);
    }

    private String mhzText(KernelTelemetry.Snapshot s, String key) {
        Long v = s.longValue(key);
        return v == null ? "N/D" : String.format(Locale.US, "%4.0f MHz", v / 1000.0);
    }

    private static String safeError(String s) {
        if (s == null || s.trim().isEmpty()) return "indisponível";
        String x = s.replace('\n', ' ').trim();
        return x.length() > 180 ? x.substring(0, 180) + "…" : x;
    }

    private void applyMargins() {
        final int[] requested = currentMargins();
        setControllerEnabled(false);
        fullButton.setEnabled(false);
        controlStatus.setText("Solicitando root e aplicando margens...");
        readbackView.setText("");

        rootExec.submit(() -> {
            KernelTelemetry.ExecResult write = rootCommand(KernelTelemetry.applyMarginsCommand(NODES, requested), 8000);
            KernelTelemetry.Snapshot s = readSnapshot(5000);
            int[] actual = s == null ? null : s.margins();
            boolean ok = write.exitCode == 0 && actual != null && Arrays.equals(actual, requested);
            runOnUiThread(() -> {
                if (s != null && actual != null) {
                    setPreset(actual);
                    latestSnapshot = s;
                    readbackView.setText("Solicitado: " + formatValues(requested) + "\nReadback:   " + s.marginsText() + "\n\n" + formatKernelReadback(s));
                    temperatureView.setText(formatLiveTelemetry(s));
                } else {
                    readbackView.setText("Solicitado: " + formatValues(requested) + "\nReadback: N/D\n" + safeError(lastRootError));
                }
                if (ok) {
                    controlStatus.setText("OK — escrita e readback real confirmados.");
                } else if (write.exitCode == 126 || write.exitCode == 127 || s == null) {
                    controlStatus.setText("Root/su indisponível ou negado. Nenhum valor foi tratado como confirmado.");
                } else {
                    controlStatus.setText("ATENÇÃO — kernel devolveu valores diferentes do solicitado. Sliders mostram o READBACK real.");
                }
                setControllerEnabled(true);
                fullButton.setEnabled(true);
                if (activityVisible && !benchmarkRunning && s != null) {
                    handler.removeCallbacks(liveTick);
                    handler.postDelayed(liveTick, 1000);
                }
            });
        });
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

    private final class BenchmarkTelemetryRunner {
        final KernelTelemetry.Accumulator accumulator = new KernelTelemetry.Accumulator(TELEMETRY_INTERVAL_MS);
        final AtomicBoolean running = new AtomicBoolean(false);
        Thread thread;

        void start() {
            KernelTelemetry.Snapshot first = readSnapshot(6000);
            if (first == null) {
                accumulator.setError("root/readback indisponível no início: " + safeError(lastRootError));
                return;
            }
            accumulator.add(first);
            running.set(true);
            thread = new Thread(() -> {
                while (running.get()) {
                    try { Thread.sleep(TELEMETRY_INTERVAL_MS); }
                    catch (InterruptedException e) { if (!running.get()) break; }
                    if (!running.get()) break;
                    KernelTelemetry.Snapshot s = readSnapshot(4000);
                    if (s != null) accumulator.add(s);
                    else accumulator.setError(safeError(lastRootError));
                }
            }, "AppleTelemetrySampler");
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        }

        void stop() {
            running.set(false);
            if (thread != null) {
                thread.interrupt();
                try { thread.join(2500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            KernelTelemetry.Snapshot end = readSnapshot(5000);
            if (end != null) accumulator.add(end);
            else accumulator.setError(safeError(lastRootError));
        }

        String report() { return accumulator.reportBlock(); }
    }

    // S21 Lab FULL: workload, durations, formulas and score fields remain
    // unchanged from the previous combined APK. Only low-frequency telemetry
    // was added around it.
    private void runBench() {
        benchmarkRunning = true;
        handler.removeCallbacks(liveTick);
        fullButton.setEnabled(false);
        saveButton.setEnabled(false);
        setControllerEnabled(false);
        lastReport = "";
        resultView.setText("");
        progress.setProgress(0);
        temperatureView.setText("FULL em execução — telemetria térmica/clocks sendo coletada para o TXT a cada 2 s.");

        benchExec.submit(() -> {
            BenchmarkTelemetryRunner telemetry = new BenchmarkTelemetryRunner();
            try {
                final long singleMs = 12000;
                final long multiMs = 20000;
                final long gpuMs = 18000;
                final long memMs = 12000;
                final int storageMB = 256;
                final long soakMs = 180000;

                ui("Preparando readback/telemetria...", 2);
                telemetry.start();

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

                ui("Fechando telemetria...", 94);
                telemetry.stop();

                ui("Gerando TXT final...", 96);
                lastReport = buildReport(threads, single, multi, gpu, memBw, memLat,
                        storage, soakCpu[0], soakGpu, telemetry.report());

                runOnUiThread(() -> {
                    resultView.setText(lastReport);
                    progress.setProgress(100);
                    benchStatus.setText("Concluído. Toque em Salvar log TXT.");
                    fullButton.setEnabled(true);
                    saveButton.setEnabled(true);
                    setControllerEnabled(true);
                    benchmarkRunning = false;
                    autoReadKernel();
                });
            } catch (Throwable e) {
                try { telemetry.stop(); } catch (Throwable ignored) {}
                runOnUiThread(() -> {
                    benchStatus.setText("Erro: " + e);
                    fullButton.setEnabled(true);
                    setControllerEnabled(true);
                    benchmarkRunning = false;
                    autoReadKernel();
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
                               double soakCpu, GpuBench.Result soakGpu, String telemetryBlock) {
        double cpuScore = single * 90.0 + multi * 28.0;
        double gpuScore = gpu.drawsPerSecond * 140.0;
        double memScore = memBw * 7.0 + memLat * 900.0;
        double storageScore = (storage.writeMBs + storage.readMBs) * 4.0;
        double total = cpuScore * 0.36 + gpuScore * 0.34 + memScore * 0.20 + storageScore * 0.10;

        StringBuilder sb = new StringBuilder(8192);
        sb.append("S21 LAB BENCHMARK REPORT\n");
        sb.append("S21Lab Score v1.2\n");
        sb.append("apple_control=1.2-telemetry\n");
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
        sb.append(String.format(Locale.US, "soak_cpu_multi_mops=%.3f\nsoak_gpu_draws_per_sec=%.3f\nsoak_gpu_status=%s\n\n",
                soakCpu, soakGpu.drawsPerSecond, soakGpu.status));

        if (telemetryBlock != null && !telemetryBlock.isEmpty()) sb.append(telemetryBlock).append('\n');
        sb.append("txt_state=FINAL_CLOSED\nEND_REPORT\n");
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
        activityVisible = false;
        benchmarkRunning = false;
        handler.removeCallbacksAndMessages(null);
        synchronized (rootLock) { closeRootShellLocked(); }
        rootExec.shutdownNow();
        benchExec.shutdownNow();
        super.onDestroy();
    }
}
