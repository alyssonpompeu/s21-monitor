package com.alysson.s21lab;

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
    private TextView status, resultView, rootView;
    private ProgressBar progress;
    private Button quick, full, copy, share, save;
    private volatile String lastReport = "";
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        checkRoot();
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("S21 Lab Benchmark");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(25, 90, 180));
        title.setTypeface(null, 1);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("SM-G991B / Exynos 2100 • S21Lab Score v1\nBenchmark próprio para comparar seus módulos Magisk.");
        sub.setTextSize(15);
        sub.setPadding(0, dp(4), 0, dp(12));
        box.addView(sub);

        rootView = new TextView(this);
        rootView.setText("Root: verificando...");
        box.addView(rootView);

        quick = button("Executar QUICK (~1 min)");
        full = button("Executar FULL (~4–5 min)");
        box.addView(quick); box.addView(full);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); progress.setProgress(0);
        box.addView(progress, new LinearLayout.LayoutParams(-1, dp(18)));

        status = new TextView(this);
        status.setText("Pronto.");
        status.setPadding(0, dp(8), 0, dp(8));
        status.setTextSize(16);
        box.addView(status);

        resultView = new TextView(this);
        resultView.setTextIsSelectable(true);
        resultView.setTypeface(android.graphics.Typeface.MONOSPACE);
        resultView.setTextSize(12);
        resultView.setText("O relatório aparecerá aqui.");
        box.addView(resultView);

        copy = button("Copiar relatório");
        share = button("Compartilhar relatório");
        save = button("Salvar TXT em Downloads");
        copy.setEnabled(false); share.setEnabled(false); save.setEnabled(false);
        box.addView(copy); box.addView(share); box.addView(save);

        quick.setOnClickListener(v -> runBench(false));
        full.setOnClickListener(v -> runBench(true));
        copy.setOnClickListener(v -> copyReport());
        share.setOnClickListener(v -> shareReport());
        save.setOnClickListener(v -> saveReport());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);
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

    private void checkRoot() {
        exec.submit(() -> {
            boolean root = RootTelemetry.hasRoot();
            String mod = root ? RootTelemetry.moduleInfo() : "sem root";
            runOnUiThread(() -> rootView.setText("Root: " + (root ? "OK (Magisk/su)" : "não disponível") +
                    "\nMódulo detectado:\n" + mod));
        });
    }

    private void runBench(boolean fullMode) {
        quick.setEnabled(false); full.setEnabled(false);
        copy.setEnabled(false); share.setEnabled(false); save.setEnabled(false);
        resultView.setText("");
        progress.setProgress(0);

        exec.submit(() -> {
            try {
                final boolean root = RootTelemetry.hasRoot();
                final String mode = fullMode ? "FULL" : "QUICK";
                final long singleMs = fullMode ? 12000 : 6000;
                final long multiMs = fullMode ? 20000 : 8000;
                final long gpuMs = fullMode ? 18000 : 10000;
                final long memMs = fullMode ? 12000 : 6000;
                final int storageMB = fullMode ? 256 : 128;
                final long soakMs = fullMode ? 180000 : 20000;

                List<String> telemetry = Collections.synchronizedList(new ArrayList<>());
                AtomicBoolean sampling = new AtomicBoolean(true);
                Thread sampler = new Thread(() -> {
                    long t0 = System.nanoTime();
                    while (sampling.get()) {
                        long ms = (System.nanoTime() - t0) / 1_000_000L;
                        String s = root ? RootTelemetry.snapshot() : "root=NA";
                        telemetry.add(ms + "ms|" + s);
                        try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                    }
                }, "S21LabTelemetry");
                sampler.start();

                ui("CPU single-core...", 5);
                double single = Benchmarks.cpuSingleMops(singleMs);

                ui("CPU multi-core...", 18);
                int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
                double multi = Benchmarks.cpuMultiMops(multiMs, threads);

                ui("GPU OpenGL ES offscreen...", 35);
                GpuBench.Result gpu = GpuBench.run(gpuMs);

                ui("Memória RAM...", 52);
                double memBw = Benchmarks.memoryBandwidthMBs(memMs);
                double memLat = Benchmarks.memoryLatencyMops(Math.max(3000, memMs / 2));

                ui("Armazenamento...", 66);
                Benchmarks.StorageResult storage = Benchmarks.storage(getCacheDir(), storageMB);

                ui("Carga combinada CPU + GPU / thermal soak...", 78);
                final double[] soakCpu = new double[1];
                Thread cpuSoak = new Thread(() -> {
                    try { soakCpu[0] = Benchmarks.cpuMultiMops(soakMs, threads); }
                    catch (InterruptedException ignored) {}
                }, "S21LabSoakCPU");
                cpuSoak.start();
                GpuBench.Result soakGpu = GpuBench.run(soakMs);
                cpuSoak.join();

                sampling.set(false);
                sampler.interrupt();
                sampler.join(2000);

                ui("Gerando relatório...", 96);
                String report = buildReport(mode, root, threads, single, multi, gpu, memBw, memLat,
                        storage, soakCpu[0], soakGpu, telemetry);
                lastReport = report;

                runOnUiThread(() -> {
                    resultView.setText(report);
                    progress.setProgress(100);
                    status.setText("Concluído. Copie o relatório e cole no ChatGPT.");
                    quick.setEnabled(true); full.setEnabled(true);
                    copy.setEnabled(true); share.setEnabled(true); save.setEnabled(true);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    status.setText("Erro: " + e);
                    quick.setEnabled(true); full.setEnabled(true);
                });
            }
        });
    }

    private void ui(String s, int p) {
        runOnUiThread(() -> { status.setText(s); progress.setProgress(p); });
    }

    private String buildReport(String mode, boolean root, int threads,
                               double single, double multi, GpuBench.Result gpu,
                               double memBw, double memLat, Benchmarks.StorageResult storage,
                               double soakCpu, GpuBench.Result soakGpu, List<String> telemetry) {
        double cpuScore = single * 90.0 + multi * 28.0;
        double gpuScore = gpu.drawsPerSecond * 140.0;
        double memScore = memBw * 7.0 + memLat * 900.0;
        double storageScore = (storage.writeMBs + storage.readMBs) * 4.0;
        double total = cpuScore * 0.36 + gpuScore * 0.34 + memScore * 0.20 + storageScore * 0.10;

        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("S21 LAB BENCHMARK REPORT\n");
        sb.append("S21Lab Score v1\n");
        sb.append("mode=").append(mode).append('\n');
        sb.append("timestamp=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        sb.append("model=").append(Build.MODEL).append('\n');
        sb.append("device=").append(Build.DEVICE).append('\n');
        sb.append("hardware=").append(Build.HARDWARE).append('\n');
        sb.append("build=").append(Build.DISPLAY).append('\n');
        sb.append("android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("kernel=").append(System.getProperty("os.version")).append('\n');
        sb.append("root=").append(root).append('\n');
        sb.append("cpu_threads=").append(threads).append('\n');
        sb.append("refresh_current_hz=").append(getWindowManager().getDefaultDisplay().getRefreshRate()).append('\n');
        sb.append('\n');

        sb.append("=== SCORE ===\n");
        sb.append(String.format(Locale.US, "TOTAL=%.0f\nCPU=%.0f\nGPU=%.0f\nMEM=%.0f\nSTORAGE=%.0f\n",
                total, cpuScore, gpuScore, memScore, storageScore));
        sb.append('\n');

        sb.append("=== RAW BENCHMARK ===\n");
        sb.append(String.format(Locale.US, "cpu_single_mops=%.3f\ncpu_multi_mops=%.3f\n", single, multi));
        sb.append(String.format(Locale.US, "gpu_draws_per_sec=%.3f\ngpu_status=%s\n", gpu.drawsPerSecond, gpu.status));
        sb.append(String.format(Locale.US, "ram_copy_MBps=%.2f\nram_latency_Mops=%.3f\n", memBw, memLat));
        sb.append(String.format(Locale.US, "storage_write_MBps=%.2f\nstorage_read_MBps=%.2f\n", storage.writeMBs, storage.readMBs));
        sb.append(String.format(Locale.US, "soak_cpu_multi_mops=%.3f\nsoak_gpu_draws_per_sec=%.3f\nsoak_gpu_status=%s\n",
                soakCpu, soakGpu.drawsPerSecond, soakGpu.status));
        sb.append('\n');

        if (root) {
            sb.append("=== MAGISK MODULE ===\n").append(RootTelemetry.moduleInfo()).append("\n\n");
            sb.append("=== STATIC HARDWARE / DVFS ===\n").append(RootTelemetry.staticHardware()).append("\n\n");
            String npu = RootTelemetry.npuInfo();
            sb.append("=== NPU / NNAPI CAPABILITY ===\n");
            sb.append(npu.isBlank() ? "NA - backend NNAPI não expôs dumpsys\n" : npu).append("\n\n");
        } else {
            sb.append("=== ROOT TELEMETRY ===\nNA - root não concedido\n\n");
        }

        sb.append("=== TELEMETRY 1s ===\n");
        synchronized (telemetry) {
            for (String line : telemetry) sb.append(line).append('\n');
        }
        sb.append("\nEND_REPORT\n");
        return sb.toString();
    }

    private void copyReport() {
        ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("S21Lab report", lastReport));
        Toast.makeText(this, "Relatório copiado.", Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "S21 Lab Benchmark");
        i.putExtra(Intent.EXTRA_TEXT, lastReport);
        startActivity(Intent.createChooser(i, "Compartilhar relatório"));
    }

    private void saveReport() {
        if (lastReport.isEmpty()) return;
        String name = "S21Lab_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
        cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) {
            Toast.makeText(this, "Falha ao criar arquivo.", Toast.LENGTH_LONG).show();
            return;
        }
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out != null) out.write(lastReport.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Salvo em Downloads/" + name, Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Erro ao salvar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }
}
