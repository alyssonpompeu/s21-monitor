package com.alysson.kernelbench;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity {
    private static final int GPU_REFERENCE_SCORE = 1940;
    private static final int CPU_REFERENCE_SCORE = 1000;
    private static final int RAM_REFERENCE_SCORE = 1000;

    private static final long GPU_MS = 25_000L;
    private static final long CPU_MS = 15_000L;
    private static final long RAM_MS = 10_000L;

    private static volatile long cpuSink;

    private BenchmarkSurface glView;
    private TextView stageText;
    private TextView resultText;
    private TextView referenceText;
    private LinearLayout historyContainer;
    private ProgressBar progress;
    private Button fullButton;
    private Button gpuButton;
    private Button cpuButton;
    private Button ramButton;
    private Button recalibrateButton;
    private Button clearButton;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private boolean busy;

    private android.content.SharedPreferences scorePrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setStatusBarColor(Color.rgb(7, 10, 18));
        w.setNavigationBarColor(Color.rgb(7, 10, 18));

        scorePrefs = getSharedPreferences("kernelbench_scores", MODE_PRIVATE);
        buildUi();
        refreshReferenceCard();
        refreshHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glView != null) glView.onResume();
    }

    @Override
    protected void onPause() {
        if (glView != null) glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        glView = new BenchmarkSurface(this);
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(28), dp(18), dp(36));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = text("KERNELBENCH X1", 30, Color.WHITE, true);
        content.addView(title);

        TextView subtitle = text("SM-G991B • Exynos 2100 • Kernel comparison lab", 13,
                Color.rgb(172, 187, 214), false);
        subtitle.setPadding(0, dp(2), 0, dp(16));
        content.addView(subtitle);

        LinearLayout refCard = card();
        TextView refLabel = text("GPU REFERENCE", 12, Color.rgb(151, 166, 196), true);
        refCard.addView(refLabel);
        TextView refValue = text("1940", 54, Color.WHITE, true);
        refCard.addView(refValue);
        TextView refDesc = text(
                "Âncora de comparação: Wild Life Extreme = 1940 no seu S21.\n" +
                "O primeiro teste GPU calibra este workload interno para 1940; depois mostramos a variação do kernel.",
                13, Color.rgb(205, 214, 232), false);
        refDesc.setLineSpacing(0, 1.08f);
        refCard.addView(refDesc);
        referenceText = text("", 12, Color.rgb(125, 229, 181), false);
        referenceText.setPadding(0, dp(10), 0, 0);
        refCard.addView(referenceText);
        content.addView(refCard);

        fullButton = primaryButton("▶  INICIAR TESTE COMPLETO");
        fullButton.setOnClickListener(v -> startFull());
        LinearLayout.LayoutParams bigLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        bigLp.topMargin = dp(16);
        content.addView(fullButton, bigLp);

        TextView separateLabel = text("TESTES SEPARADOS", 12, Color.rgb(169, 182, 208), true);
        separateLabel.setPadding(0, dp(18), 0, dp(8));
        content.addView(separateLabel);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(3f);

        gpuButton = secondaryButton("GPU");
        cpuButton = secondaryButton("CPU");
        ramButton = secondaryButton("RAM");
        gpuButton.setOnClickListener(v -> startGpu(false, null));
        cpuButton.setOnClickListener(v -> startCpu(false, null));
        ramButton.setOnClickListener(v -> startRam(false, null));

        addWeighted(row, gpuButton, 1f);
        addWeighted(row, cpuButton, 1f);
        addWeighted(row, ramButton, 1f);
        content.addView(row);

        LinearLayout liveCard = card();
        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        liveLp.topMargin = dp(16);

        stageText = text("Pronto para medir", 17, Color.WHITE, true);
        liveCard.addView(stageText);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(0);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        pLp.topMargin = dp(10);
        liveCard.addView(progress, pLp);

        resultText = text(
                "GPU index: —\nCPU index: —\nRAM index: —\nÍndice geral: —\n\n" +
                "A cena 3D de fundo fica limitada a ~60 fps e usa um workload leve constante.",
                14, Color.rgb(214, 223, 239), false);
        resultText.setTypeface(Typeface.MONOSPACE);
        resultText.setLineSpacing(0, 1.10f);
        resultText.setPadding(0, dp(12), 0, 0);
        liveCard.addView(resultText);
        content.addView(liveCard, liveLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(0, dp(14), 0, 0);

        recalibrateButton = tinyButton("RECALIBRAR 1940");
        clearButton = tinyButton("LIMPAR HISTÓRICO");
        recalibrateButton.setOnClickListener(v -> confirmRecalibrate());
        clearButton.setOnClickListener(v -> confirmClearHistory());

        addWeighted(controls, recalibrateButton, 1f);
        addWeighted(controls, clearButton, 1f);
        content.addView(controls);

        TextView histTitle = text("HISTÓRICO", 16, Color.WHITE, true);
        histTitle.setPadding(0, dp(22), 0, dp(8));
        content.addView(histTitle);

        historyContainer = new LinearLayout(this);
        historyContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(historyContainer);

        TextView note = text(
                "Nota técnica: o índice GPU 1940 é uma normalização deste benchmark para a sua referência externa; " +
                "não é uma pontuação oficial nem diretamente comparável ao 3DMark em outros aparelhos.",
                11, Color.rgb(142, 157, 184), false);
        note.setPadding(0, dp(18), 0, 0);
        content.addView(note);

        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void startFull() {
        if (busy) return;
        setBusy(true);
        FullState state = new FullState();
        stageText.setText("Teste completo • etapa 1/3 • GPU");
        startGpu(true, state);
    }

    private void startGpu(boolean full, FullState state) {
        if (!full && busy) return;
        if (!full) setBusy(true);

        stageText.setText(full
                ? "Teste completo • etapa 1/3 • GPU"
                : "GPU • workload offscreen fixo");
        runProgress(GPU_MS);

        glView.startGpuBenchmark(GPU_MS, result -> {
            double raw = result.megapixelsPerSecond;
            int score = normalizeGpu(raw);
            double delta = ratio(raw, getGpuBase()) * 100.0 - 100.0;

            if (full) {
                state.gpuRaw = raw;
                state.gpuScore = score;
                state.gpuDelta = delta;
                state.sceneFps = result.averageSceneFps;
                stageText.setText("Teste completo • etapa 2/3 • CPU");
                startCpu(true, state);
            } else {
                showGpuResult(score, delta, raw, result.averageSceneFps);
                saveGpuRecord(score, delta, raw, result.averageSceneFps);
                setBusy(false);
            }
            refreshReferenceCard();
        });
    }

    private void startCpu(boolean full, FullState state) {
        if (!full && busy) return;
        if (!full) setBusy(true);

        stageText.setText(full
                ? "Teste completo • etapa 2/3 • CPU"
                : "CPU • multicore determinístico");
        runProgress(CPU_MS);

        background.execute(() -> {
            double raw = runCpuWorkload(CPU_MS);
            int score = normalizeCpu(raw);
            double delta = ratio(raw, getCpuBase()) * 100.0 - 100.0;

            runOnUiThread(() -> {
                if (full) {
                    state.cpuRaw = raw;
                    state.cpuScore = score;
                    state.cpuDelta = delta;
                    stageText.setText("Teste completo • etapa 3/3 • RAM");
                    startRam(true, state);
                } else {
                    showCpuResult(score, delta, raw);
                    saveCpuRecord(score, delta, raw);
                    setBusy(false);
                }
                refreshReferenceCard();
            });
        });
    }

    private void startRam(boolean full, FullState state) {
        if (!full && busy) return;
        if (!full) setBusy(true);

        stageText.setText(full
                ? "Teste completo • etapa 3/3 • RAM"
                : "RAM • cópia sequencial");
        runProgress(RAM_MS);

        background.execute(() -> {
            double raw = runRamWorkload(RAM_MS);
            int score = normalizeRam(raw);
            double delta = ratio(raw, getRamBase()) * 100.0 - 100.0;

            runOnUiThread(() -> {
                if (full) {
                    state.ramRaw = raw;
                    state.ramScore = score;
                    state.ramDelta = delta;
                    finishFull(state);
                } else {
                    showRamResult(score, delta, raw);
                    saveRamRecord(score, delta, raw);
                    setBusy(false);
                }
                refreshReferenceCard();
            });
        });
    }

    private void finishFull(FullState s) {
        double gpuRatio = ratio(s.gpuRaw, getGpuBase());
        double cpuRatio = ratio(s.cpuRaw, getCpuBase());
        double ramRatio = ratio(s.ramRaw, getRamBase());
        int overall = (int) Math.round(1000.0 *
                (0.50 * gpuRatio + 0.30 * cpuRatio + 0.20 * ramRatio));

        stageText.setText("Teste completo finalizado");
        progress.setProgress(1000);
        resultText.setText(String.format(Locale.US,
                "GPU index: %d  (%+.2f%%)\n" +
                "CPU index: %d  (%+.2f%%)\n" +
                "RAM index: %d  (%+.2f%%)\n" +
                "Índice geral: %d\n\n" +
                "GPU raw: %.2f MPix/s\n" +
                "CPU raw: %.2f Miter/s\n" +
                "RAM raw: %.2f GB/s\n" +
                "Cena: %.1f fps",
                s.gpuScore, s.gpuDelta,
                s.cpuScore, s.cpuDelta,
                s.ramScore, s.ramDelta,
                overall,
                s.gpuRaw, s.cpuRaw, s.ramRaw, s.sceneFps));

        HistoryStore.Record r = baseRecord("ALL");
        r.gpuScore = s.gpuScore;
        r.gpuRaw = s.gpuRaw;
        r.cpuScore = s.cpuScore;
        r.cpuRaw = s.cpuRaw;
        r.ramScore = s.ramScore;
        r.ramRaw = s.ramRaw;
        r.overall = overall;
        r.gpuDelta = s.gpuDelta;
        r.cpuDelta = s.cpuDelta;
        r.ramDelta = s.ramDelta;
        r.sceneFps = s.sceneFps;
        HistoryStore.add(this, r);
        refreshHistory();
        setBusy(false);
    }

    private double runCpuWorkload(long durationMs) {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicLong total = new AtomicLong();
        long end = System.nanoTime() + durationMs * 1_000_000L;
        long start = System.nanoTime();

        for (int t = 0; t < threads; t++) {
            final long seed = 0x9E3779B97F4A7C15L ^ (t * 0xD1B54A32D192ED03L);
            pool.execute(() -> {
                long x = seed;
                long local = 0;
                while (System.nanoTime() < end) {
                    for (int i = 0; i < 8192; i++) {
                        x ^= x << 13;
                        x ^= x >>> 7;
                        x ^= x << 17;
                        x = x * 2862933555777941757L + 3037000493L;
                        local++;
                    }
                }
                cpuSink ^= x;
                total.addAndGet(local);
                latch.countDown();
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();

        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        return seconds > 0 ? total.get() / seconds / 1_000_000.0 : 0.0;
    }

    private double runRamWorkload(long durationMs) {
        final int size = 32 * 1024 * 1024;
        byte[] a = new byte[size];
        byte[] b = new byte[size];
        for (int i = 0; i < a.length; i += 4096) a[i] = (byte) (i * 31);

        long bytes = 0;
        long start = System.nanoTime();
        long end = start + durationMs * 1_000_000L;
        boolean flip = false;

        while (System.nanoTime() < end) {
            if (flip) {
                System.arraycopy(b, 0, a, 0, size);
            } else {
                System.arraycopy(a, 0, b, 0, size);
            }
            flip = !flip;
            bytes += size;
        }

        long checksum = 0;
        for (int i = 0; i < size; i += 4096) checksum += a[i] + b[i];
        cpuSink ^= checksum;

        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        return seconds > 0 ? bytes / seconds / 1_000_000_000.0 : 0.0;
    }

    private int normalizeGpu(double raw) {
        double base = getGpuBase();
        if (base <= 0.0 && raw > 0.0) {
            scorePrefs.edit().putFloat("gpu_base", (float) raw).apply();
            base = raw;
        }
        return (int) Math.round(GPU_REFERENCE_SCORE * ratio(raw, base));
    }

    private int normalizeCpu(double raw) {
        double base = getCpuBase();
        if (base <= 0.0 && raw > 0.0) {
            scorePrefs.edit().putFloat("cpu_base", (float) raw).apply();
            base = raw;
        }
        return (int) Math.round(CPU_REFERENCE_SCORE * ratio(raw, base));
    }

    private int normalizeRam(double raw) {
        double base = getRamBase();
        if (base <= 0.0 && raw > 0.0) {
            scorePrefs.edit().putFloat("ram_base", (float) raw).apply();
            base = raw;
        }
        return (int) Math.round(RAM_REFERENCE_SCORE * ratio(raw, base));
    }

    private double getGpuBase() {
        return scorePrefs.getFloat("gpu_base", 0f);
    }

    private double getCpuBase() {
        return scorePrefs.getFloat("cpu_base", 0f);
    }

    private double getRamBase() {
        return scorePrefs.getFloat("ram_base", 0f);
    }

    private static double ratio(double value, double base) {
        if (base <= 0.0 || value <= 0.0) return 1.0;
        return value / base;
    }

    private void showGpuResult(int score, double delta, double raw, double fps) {
        stageText.setText("GPU finalizada");
        progress.setProgress(1000);
        resultText.setText(String.format(Locale.US,
                "GPU index: %d  (%+.2f%%)\nCPU index: —\nRAM index: —\nÍndice geral: —\n\n" +
                "GPU raw: %.2f MPix/s\nCena: %.1f fps",
                score, delta, raw, fps));
    }

    private void showCpuResult(int score, double delta, double raw) {
        stageText.setText("CPU finalizada");
        progress.setProgress(1000);
        resultText.setText(String.format(Locale.US,
                "GPU index: —\nCPU index: %d  (%+.2f%%)\nRAM index: —\nÍndice geral: —\n\n" +
                "CPU raw: %.2f Miter/s",
                score, delta, raw));
    }

    private void showRamResult(int score, double delta, double raw) {
        stageText.setText("RAM finalizada");
        progress.setProgress(1000);
        resultText.setText(String.format(Locale.US,
                "GPU index: —\nCPU index: —\nRAM index: %d  (%+.2f%%)\nÍndice geral: —\n\n" +
                "RAM raw: %.2f GB/s",
                score, delta, raw));
    }

    private void saveGpuRecord(int score, double delta, double raw, double fps) {
        HistoryStore.Record r = baseRecord("GPU");
        r.gpuScore = score;
        r.gpuDelta = delta;
        r.gpuRaw = raw;
        r.sceneFps = fps;
        HistoryStore.add(this, r);
        refreshHistory();
    }

    private void saveCpuRecord(int score, double delta, double raw) {
        HistoryStore.Record r = baseRecord("CPU");
        r.cpuScore = score;
        r.cpuDelta = delta;
        r.cpuRaw = raw;
        HistoryStore.add(this, r);
        refreshHistory();
    }

    private void saveRamRecord(int score, double delta, double raw) {
        HistoryStore.Record r = baseRecord("RAM");
        r.ramScore = score;
        r.ramDelta = delta;
        r.ramRaw = raw;
        HistoryStore.add(this, r);
        refreshHistory();
    }

    private HistoryStore.Record baseRecord(String type) {
        HistoryStore.Record r = new HistoryStore.Record();
        r.timestamp = System.currentTimeMillis();
        r.type = type;
        r.kernel = System.getProperty("os.version", "unknown");
        r.build = Build.DISPLAY;
        return r;
    }

    private void refreshReferenceCard() {
        double g = getGpuBase();
        double c = getCpuBase();
        double r = getRamBase();
        referenceText.setText(String.format(Locale.US,
                "Calibração interna • GPU %s • CPU %s • RAM %s",
                g > 0 ? "OK" : "pendente",
                c > 0 ? "OK" : "pendente",
                r > 0 ? "OK" : "pendente"));
    }

    private void refreshHistory() {
        historyContainer.removeAllViews();
        List<HistoryStore.Record> records = HistoryStore.load(this);
        if (records.isEmpty()) {
            TextView empty = text("Nenhum teste salvo ainda.", 13,
                    Color.rgb(153, 168, 194), false);
            historyContainer.addView(empty);
            return;
        }

        SimpleDateFormat df = new SimpleDateFormat("dd/MM • HH:mm:ss", Locale.getDefault());
        int shown = Math.min(records.size(), 30);
        for (int i = 0; i < shown; i++) {
            HistoryStore.Record r = records.get(i);
            LinearLayout c = card();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);

            TextView head = text(df.format(new Date(r.timestamp)) + "   " + r.type,
                    13, Color.WHITE, true);
            c.addView(head);

            StringBuilder b = new StringBuilder();
            if (r.gpuScore > 0) {
                b.append(String.format(Locale.US, "GPU %d  %+.2f%%", r.gpuScore, r.gpuDelta));
            }
            if (r.cpuScore > 0) {
                if (b.length() > 0) b.append("   •   ");
                b.append(String.format(Locale.US, "CPU %d  %+.2f%%", r.cpuScore, r.cpuDelta));
            }
            if (r.ramScore > 0) {
                if (b.length() > 0) b.append("   •   ");
                b.append(String.format(Locale.US, "RAM %d  %+.2f%%", r.ramScore, r.ramDelta));
            }
            if (r.overall > 0) {
                b.append("\nÍndice geral ").append(r.overall);
            }
            if (r.gpuRaw > 0) b.append(String.format(Locale.US, "\nGPU %.1f MPix/s", r.gpuRaw));
            if (r.cpuRaw > 0) b.append(String.format(Locale.US, "   CPU %.1f Miter/s", r.cpuRaw));
            if (r.ramRaw > 0) b.append(String.format(Locale.US, "   RAM %.2f GB/s", r.ramRaw));
            b.append("\nKernel ").append(shorten(r.kernel, 44));

            TextView body = text(b.toString(), 12, Color.rgb(199, 210, 230), false);
            body.setTypeface(Typeface.MONOSPACE);
            body.setPadding(0, dp(7), 0, 0);
            c.addView(body);
            historyContainer.addView(c, lp);
        }
    }

    private void confirmRecalibrate() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Recalibrar referência")
                .setMessage("O próximo teste GPU passará a valer 1940. CPU e RAM também serão recalibrados no próximo teste de cada componente. Use isso somente quando estiver no kernel que deseja adotar como baseline.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Recalibrar", (d, w) -> {
                    scorePrefs.edit()
                            .remove("gpu_base")
                            .remove("cpu_base")
                            .remove("ram_base")
                            .apply();
                    refreshReferenceCard();
                    stageText.setText("Baseline apagado • próximo teste calibra");
                })
                .show();
    }

    private void confirmClearHistory() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Limpar histórico")
                .setMessage("Isso apaga apenas os resultados salvos. A calibração 1940 é mantida.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Apagar", (d, w) -> {
                    HistoryStore.clear(this);
                    refreshHistory();
                })
                .show();
    }

    private void runProgress(long durationMs) {
        final long start = System.currentTimeMillis();
        progress.setProgress(0);
        Runnable ticker = new Runnable() {
            @Override public void run() {
                long elapsed = System.currentTimeMillis() - start;
                int p = (int) Math.min(1000, elapsed * 1000 / Math.max(1, durationMs));
                progress.setProgress(p);
                if (busy && p < 1000) mainHandler.postDelayed(this, 100);
            }
        };
        mainHandler.post(ticker);
    }

    private void setBusy(boolean value) {
        busy = value;
        fullButton.setEnabled(!value);
        gpuButton.setEnabled(!value);
        cpuButton.setEnabled(!value);
        ramButton.setEnabled(!value);
        recalibrateButton.setEnabled(!value);
        clearButton.setEnabled(!value);
        if (!value && progress.getProgress() < 1000) progress.setProgress(1000);
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(205, 15, 22, 38));
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), Color.argb(100, 101, 135, 196));
        l.setBackground(g);
        return l;
    }

    private Button primaryButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(58, 94, 216), Color.rgb(91, 58, 210)});
        g.setCornerRadius(dp(18));
        b.setBackground(g);
        return b;
    }

    private Button secondaryButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(215, 24, 34, 55));
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), Color.argb(125, 111, 142, 209));
        b.setBackground(g);
        return b;
    }

    private Button tinyButton(String s) {
        Button b = secondaryButton(s);
        b.setTextSize(11);
        return b;
    }

    private void addWeighted(LinearLayout row, View v, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), weight);
        lp.setMarginEnd(dp(6));
        row.addView(v, lp);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static final class FullState {
        double gpuRaw;
        int gpuScore;
        double gpuDelta;
        double cpuRaw;
        int cpuScore;
        double cpuDelta;
        double ramRaw;
        int ramScore;
        double ramDelta;
        double sceneFps;
    }
}
