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

public class MainActivity extends Activity {
    private TextView status, resultView;
    private ProgressBar progress;
    private Button full, save;
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
        title.setText("S21 Lab Benchmark");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(25, 90, 180));
        title.setTypeface(null, 1);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("SM-G991B / Exynos 2100\nFULL único • TXT compacto e fechado");
        sub.setTextSize(15);
        sub.setPadding(0, dp(4), 0, dp(12));
        box.addView(sub);

        full = button("Executar FULL");
        save = button("Salvar log TXT");
        save.setEnabled(false);
        box.addView(full);
        box.addView(save);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
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
        resultView.setText("Execute o FULL. Depois salve o TXT final.");
        box.addView(resultView);

        full.setOnClickListener(v -> runBench());
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

    private void runBench() {
        full.setEnabled(false);
        save.setEnabled(false);
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
                    status.setText("Concluído. Toque em Salvar log TXT.");
                    full.setEnabled(true);
                    save.setEnabled(true);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    status.setText("Erro: " + e);
                    full.setEnabled(true);
                });
            }
        });
    }

    private void ui(String s, int p) {
        runOnUiThread(() -> { status.setText(s); progress.setProgress(p); });
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
