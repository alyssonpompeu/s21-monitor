package com.alysson.kernelbench;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final long SINGLE_MS = 12_000L;
    private static final long MULTI_MS = 20_000L;
    private static final long GPU_MS = 18_000L;
    private static final long MEM_MS = 12_000L;
    private static final int STORAGE_MB = 256;
    private static final long SOAK_MS = 180_000L;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private BenchmarkSurface scene;
    private TextView status;
    private TextView current;
    private TextView analysis;
    private TextView historyText;
    private ProgressBar progress;
    private ChartView chart;
    private Button fullBtn, cpuBtn, gpuBtn, ramBtn, saveBtn, clearBtn;
    private Button chartGpu, chartCpu, chartTotal, chartRam;

    private volatile boolean busy;
    private volatile boolean sceneBenchPaused;
    private volatile String lastReport = "";
    private volatile RunRecord lastFull;
    private String pendingLabel = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(6,9,16));
        getWindow().setNavigationBarColor(Color.rgb(6,9,16));
        buildUi();
        refreshAll();
    }

    @Override protected void onResume() {
        super.onResume();
        if (scene != null && !sceneBenchPaused) scene.onResume();
    }

    @Override protected void onPause() {
        if (scene != null && !sceneBenchPaused) scene.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);

        scene = new BenchmarkSurface(this);
        scene.setAlpha(0.48f);
        root.addView(scene, new FrameLayout.LayoutParams(-1,-1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16),dp(24),dp(16),dp(40));
        scroll.addView(box, new ScrollView.LayoutParams(-1,-2));

        TextView title = text("S21 LAB HISTORY", 29, Color.WHITE, true);
        box.addView(title);

        TextView sub = text(
                "SM-G991B • Exynos 2100\n" +
                "Mesma régua S21Lab v1.2 + histórico + gráficos + cruzamento",
                13, Color.rgb(190,202,224), false);
        sub.setPadding(0,dp(2),0,dp(12));
        box.addView(sub);

        LinearLayout info = card();
        TextView infoTitle = text("RÉGUA DE MEDIÇÃO", 12, Color.rgb(139,203,255), true);
        info.addView(infoTitle);
        TextView infoBody = text(
                "Sem normalização 3DMark. O score vem das fórmulas do S21Lab existente.\n" +
                "A cena 3D fica ~60 fps quando o app está ocioso e é pausada durante a medição para não alterar os resultados.",
                13, Color.rgb(220,228,240), false);
        infoBody.setPadding(0,dp(6),0,0);
        info.addView(infoBody);
        box.addView(info);

        fullBtn = primaryButton("▶  FULL S21LAB + SOAK");
        LinearLayout.LayoutParams fullLp = new LinearLayout.LayoutParams(-1,dp(58));
        fullLp.topMargin=dp(14);
        box.addView(fullBtn,fullLp);
        fullBtn.setOnClickListener(v -> askFullLabel());

        TextView sep = text("TESTES SEPARADOS — MESMO WORKLOAD DO S21LAB", 11,
                Color.rgb(180,193,217), true);
        sep.setPadding(0,dp(16),0,dp(7));
        box.addView(sep);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        cpuBtn = secondaryButton("CPU");
        gpuBtn = secondaryButton("GPU");
        ramBtn = secondaryButton("RAM");
        addWeighted(row,cpuBtn); addWeighted(row,gpuBtn); addWeighted(row,ramBtn);
        box.addView(row);
        cpuBtn.setOnClickListener(v -> runCpuOnly());
        gpuBtn.setOnClickListener(v -> runGpuOnly());
        ramBtn.setOnClickListener(v -> runRamOnly());

        LinearLayout live = card();
        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(-1,-2);
        liveLp.topMargin=dp(14);

        status = text("Pronto.",16,Color.WHITE,true);
        live.addView(status);

        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1,dp(10));
        pLp.topMargin=dp(8);
        live.addView(progress,pLp);

        current = text("Nenhum teste novo nesta instalação.",13,Color.rgb(219,226,238),false);
        current.setTypeface(Typeface.MONOSPACE);
        current.setPadding(0,dp(10),0,0);
        current.setTextIsSelectable(true);
        live.addView(current);

        saveBtn = tinyButton("SALVAR TXT");
        saveBtn.setEnabled(false);
        saveBtn.setOnClickListener(v -> saveReport());
        live.addView(saveBtn);
        box.addView(live,liveLp);

        TextView graphTitle = text("GRÁFICOS HISTÓRICOS",16,Color.WHITE,true);
        graphTitle.setPadding(0,dp(20),0,dp(8));
        box.addView(graphTitle);

        LinearLayout graphBtns1 = new LinearLayout(this);
        graphBtns1.setOrientation(LinearLayout.HORIZONTAL);
        chartGpu=tinyButton("GPU");
        chartCpu=tinyButton("CPU");
        chartTotal=tinyButton("TOTAL");
        chartRam=tinyButton("RAM");
        addWeighted(graphBtns1,chartGpu); addWeighted(graphBtns1,chartCpu);
        addWeighted(graphBtns1,chartTotal); addWeighted(graphBtns1,chartRam);
        box.addView(graphBtns1);

        chart = new ChartView(this);
        box.addView(chart,new LinearLayout.LayoutParams(-1,dp(300)));
        chartGpu.setOnClickListener(v -> chart.setMode(ChartView.MODE_GPU));
        chartCpu.setOnClickListener(v -> chart.setMode(ChartView.MODE_CPU));
        chartTotal.setOnClickListener(v -> chart.setMode(ChartView.MODE_TOTAL));
        chartRam.setOnClickListener(v -> chart.setMode(ChartView.MODE_RAM));

        LinearLayout analysisCard = card();
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(-1,-2);
        aLp.topMargin=dp(14);
        TextView aTitle = text("CRUZAMENTO DE DADOS",12,Color.rgb(130,236,180),true);
        analysisCard.addView(aTitle);
        analysis = text("",12,Color.rgb(218,226,239),false);
        analysis.setTypeface(Typeface.MONOSPACE);
        analysis.setPadding(0,dp(8),0,0);
        analysis.setTextIsSelectable(true);
        analysisCard.addView(analysis);
        box.addView(analysisCard,aLp);

        LinearLayout histCard=card();
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(-1,-2);
        hLp.topMargin=dp(14);
        TextView hTitle=text("HISTÓRICO INCORPORADO",12,Color.rgb(183,159,255),true);
        histCard.addView(hTitle);
        historyText=text("",11,Color.rgb(214,222,235),false);
        historyText.setTypeface(Typeface.MONOSPACE);
        historyText.setTextIsSelectable(true);
        historyText.setPadding(0,dp(8),0,0);
        histCard.addView(historyText);
        clearBtn=tinyButton("LIMPAR APENAS RUNS NOVOS");
        clearBtn.setOnClickListener(v -> confirmClear());
        histCard.addView(clearBtn);
        box.addView(histCard,hLp);

        TextView note = text(
                "Os dados históricos embutidos não são apagados. Runs sem identificação comprovada de kernel ficam com o nome do arquivo/horário; o app não inventa versão.",
                11, Color.rgb(153,166,188), false);
        note.setPadding(0,dp(14),0,0);
        box.addView(note);

        root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
    }

    private void askFullLabel() {
        if (busy) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("ex.: Apple v1.3 teste 1");
        input.setText("Run " + new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date()));

        new AlertDialog.Builder(this)
                .setTitle("Nome deste kernel/run")
                .setMessage("Use um nome que permita comparar depois. A medição não depende deste texto.")
                .setView(input)
                .setPositiveButton("INICIAR", (d,w) -> {
                    pendingLabel=input.getText().toString().trim();
                    if(pendingLabel.isEmpty()) pendingLabel="Run atual";
                    runFull();
                })
                .setNegativeButton("Cancelar",null)
                .show();
    }

    private void runFull() {
        if (busy) return;
        setBusy(true);
        pauseSceneForBench();
        progress.setProgress(0);
        status.setText("FULL • preparando");
        current.setText("Medição em andamento…");

        exec.submit(() -> {
            try {
                boolean root=RootTelemetry.hasRoot();
                String startTel=root?RootTelemetry.snapshot():"root=NA";
                int threads=Math.max(1,Runtime.getRuntime().availableProcessors());

                ui("FULL 1/6 • CPU single 12 s",5);
                double single=Benchmarks.cpuSingleMops(SINGLE_MS);

                ui("FULL 2/6 • CPU multi 20 s",18);
                double multi=Benchmarks.cpuMultiMops(MULTI_MS,threads);

                ui("FULL 3/6 • GPU 18 s",35);
                GpuBench.Result gpu=GpuBench.run(GPU_MS);

                ui("FULL 4/6 • RAM",52);
                double ramCopy=Benchmarks.memoryBandwidthMBs(MEM_MS);
                double ramLat=Benchmarks.memoryLatencyMops(Math.max(3000,MEM_MS/2));

                ui("FULL 5/6 • storage 256 MB",66);
                Benchmarks.StorageResult st=Benchmarks.storage(getCacheDir(),STORAGE_MB);

                ui("FULL 6/6 • CPU + GPU soak 180 s",78);
                final double[] soakCpu=new double[1];
                Thread cpuSoak=new Thread(() -> {
                    try { soakCpu[0]=Benchmarks.cpuMultiMops(SOAK_MS,threads); }
                    catch(InterruptedException e){ Thread.currentThread().interrupt(); }
                },"S21LabSoakCPU");
                cpuSoak.start();
                GpuBench.Result soakGpu=GpuBench.run(SOAK_MS);
                cpuSoak.join();

                String endTel=root?RootTelemetry.snapshot():"root=NA";
                RunRecord r=makeFullRecord(pendingLabel,threads,single,multi,gpu,ramCopy,ramLat,st,soakCpu[0],soakGpu);
                r.startTelemetry=startTel;
                r.endTelemetry=endTel;
                r.kernel=System.getProperty("os.version","");
                r.note=root?RootTelemetry.moduleInfo():"root não concedido";
                HistoryStore.add(this,r);
                lastFull=r;
                lastReport=buildReport(r,threads,gpu.status,soakGpu.status,root);

                runOnUiThread(() -> {
                    current.setText(formatCurrent(r));
                    analysis.setText(Analytics.compare(r,HistoryStore.full(this)));
                    status.setText("FULL concluído.");
                    progress.setProgress(100);
                    saveBtn.setEnabled(true);
                    refreshAll();
                    resumeSceneAfterBench();
                    setBusy(false);
                });
            } catch(Throwable e) {
                fail(e);
            }
        });
    }

    private void runCpuOnly() {
        if(busy)return;
        setBusy(true); pauseSceneForBench();
        progress.setProgress(0); status.setText("CPU • mesma rotina S21Lab");
        exec.submit(() -> {
            try {
                int threads=Math.max(1,Runtime.getRuntime().availableProcessors());
                ui("CPU single 12 s",25);
                double single=Benchmarks.cpuSingleMops(SINGLE_MS);
                ui("CPU multi 20 s",65);
                double multi=Benchmarks.cpuMultiMops(MULTI_MS,threads);
                double score=single*90.0+multi*28.0;

                RunRecord r=partial("CPU");
                r.single=single; r.multi=multi; r.cpu=score;
                HistoryStore.add(this,r);
                RunRecord v11=HistoricalData.findById("hist-h11");
                String text=String.format(Locale.US,
                        "CPU score = %.0f\nsingle = %.3f Mops\nmulti = %.3f Mops\nvs Hybrid v1.1 multi = %+.2f%%",
                        score,single,multi,delta(multi,v11==null?0:v11.multi));
                runOnUiThread(() -> finishPartial(text));
            } catch(Throwable e){ fail(e); }
        });
    }

    private void runGpuOnly() {
        if(busy)return;
        setBusy(true); pauseSceneForBench();
        progress.setProgress(0); status.setText("GPU • mesmo PBuffer/shader S21Lab");
        exec.submit(() -> {
            try {
                ui("GPU 18 s",35);
                GpuBench.Result g=GpuBench.run(GPU_MS);
                double score=g.drawsPerSecond*140.0;

                RunRecord r=partial("GPU");
                r.gpuBurst=g.drawsPerSecond; r.gpu=score;
                HistoryStore.add(this,r);
                RunRecord v11=HistoricalData.findById("hist-h11");
                String text=String.format(Locale.US,
                        "GPU score = %.0f\ngpu_draws_per_sec = %.3f\nstatus = %s\nvs Hybrid v1.1 burst = %+.2f%%",
                        score,g.drawsPerSecond,g.status,delta(g.drawsPerSecond,v11==null?0:v11.gpuBurst));
                runOnUiThread(() -> finishPartial(text));
            } catch(Throwable e){ fail(e); }
        });
    }

    private void runRamOnly() {
        if(busy)return;
        setBusy(true); pauseSceneForBench();
        progress.setProgress(0); status.setText("RAM • mesma rotina S21Lab");
        exec.submit(() -> {
            try {
                ui("RAM copy 12 s",30);
                double copy=Benchmarks.memoryBandwidthMBs(MEM_MS);
                ui("RAM latency 6 s",70);
                double lat=Benchmarks.memoryLatencyMops(Math.max(3000,MEM_MS/2));
                double score=copy*7.0+lat*900.0;

                RunRecord r=partial("RAM");
                r.ramCopy=copy; r.ramLatency=lat; r.mem=score;
                HistoryStore.add(this,r);
                RunRecord v11=HistoricalData.findById("hist-h11");
                String text=String.format(Locale.US,
                        "MEM score = %.0f\nram_copy = %.2f MB/s\nram_latency = %.3f Mops\nvs Hybrid v1.1 RAM = %+.2f%%",
                        score,copy,lat,delta(copy,v11==null?0:v11.ramCopy));
                runOnUiThread(() -> finishPartial(text));
            } catch(Throwable e){ fail(e); }
        });
    }

    private RunRecord makeFullRecord(String label,int threads,double single,double multi,
                                     GpuBench.Result gpu,double ramCopy,double ramLat,
                                     Benchmarks.StorageResult st,double soakCpu,GpuBench.Result soakGpu) {
        RunRecord r=new RunRecord();
        r.id="local-"+System.currentTimeMillis();
        r.label=label;
        r.timestamp=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date());
        r.source="S21 Lab History v2";
        r.kind="FULL";
        r.historical=false;
        r.single=single; r.multi=multi; r.gpuBurst=gpu.drawsPerSecond;
        r.ramCopy=ramCopy; r.ramLatency=ramLat;
        r.storageWrite=st.writeMBs; r.storageRead=st.readMBs;
        r.cpuSoak=soakCpu; r.gpuSoak=soakGpu.drawsPerSecond;
        r.cpu=single*90.0+multi*28.0;
        r.gpu=gpu.drawsPerSecond*140.0;
        r.mem=ramCopy*7.0+ramLat*900.0;
        r.storage=(st.writeMBs+st.readMBs)*4.0;
        r.total=r.cpu*0.36+r.gpu*0.34+r.mem*0.20+r.storage*0.10;
        return r;
    }

    private RunRecord partial(String kind) {
        RunRecord r=new RunRecord();
        r.id="local-"+System.currentTimeMillis();
        r.label=kind+" "+new SimpleDateFormat("MM-dd HH:mm:ss",Locale.US).format(new Date());
        r.timestamp=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date());
        r.source="S21 Lab History v2";
        r.kind=kind;
        r.historical=false;
        r.kernel=System.getProperty("os.version","");
        return r;
    }

    private String buildReport(RunRecord r,int threads,String gpuStatus,String soakGpuStatus,boolean root) {
        StringBuilder sb=new StringBuilder(8192);
        sb.append("S21 LAB BENCHMARK REPORT\n");
        sb.append("S21Lab Score v1.2 compatible\n");
        sb.append("app=S21 Lab History v2\n");
        sb.append("mode=FULL\n");
        sb.append("run_label=").append(r.label).append('\n');
        sb.append("timestamp=").append(r.timestamp).append('\n');
        sb.append("model=").append(Build.MODEL).append('\n');
        sb.append("device=").append(Build.DEVICE).append('\n');
        sb.append("hardware=").append(Build.HARDWARE).append('\n');
        sb.append("build=").append(Build.DISPLAY).append('\n');
        sb.append("android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("kernel=").append(r.kernel).append('\n');
        sb.append("cpu_threads=").append(threads).append('\n');
        sb.append("refresh_current_hz=").append(getWindowManager().getDefaultDisplay().getRefreshRate()).append("\n\n");

        sb.append("=== SCORE ===\n");
        sb.append(String.format(Locale.US,"TOTAL=%.0f\nCPU=%.0f\nGPU=%.0f\nMEM=%.0f\nSTORAGE=%.0f\n\n",
                r.total,r.cpu,r.gpu,r.mem,r.storage));

        sb.append("=== RAW BENCHMARK ===\n");
        sb.append(String.format(Locale.US,"cpu_single_mops=%.3f\ncpu_multi_mops=%.3f\n",r.single,r.multi));
        sb.append(String.format(Locale.US,"gpu_draws_per_sec=%.3f\ngpu_status=%s\n",r.gpuBurst,gpuStatus));
        sb.append(String.format(Locale.US,"ram_copy_MBps=%.2f\nram_latency_Mops=%.3f\n",r.ramCopy,r.ramLatency));
        sb.append(String.format(Locale.US,"storage_write_MBps=%.2f\nstorage_read_MBps=%.2f\n",r.storageWrite,r.storageRead));
        sb.append(String.format(Locale.US,"soak_cpu_multi_mops=%.3f\nsoak_gpu_draws_per_sec=%.3f\nsoak_gpu_status=%s\n\n",
                r.cpuSoak,r.gpuSoak,soakGpuStatus));

        sb.append("=== COMPARISON ===\n");
        sb.append(Analytics.compare(r,HistoryStore.full(this))).append("\n\n");

        sb.append("=== ROOT SNAPSHOT ===\n");
        sb.append("root=").append(root).append('\n');
        sb.append("start=").append(r.startTelemetry).append('\n');
        sb.append("end=").append(r.endTelemetry).append('\n');
        sb.append("modules=").append(r.note).append("\n\n");

        sb.append("txt_state=FINAL_CLOSED\nEND_REPORT\n");
        return sb.toString();
    }

    private String formatCurrent(RunRecord r) {
        return String.format(Locale.US,
                "%s\nTOTAL %.0f | CPU %.0f | GPU %.0f | MEM %.0f | STORAGE %.0f\n\n" +
                "single %.3f Mops\nmulti %.3f Mops\nGPU burst %.3f draws/s\n" +
                "RAM %.2f MB/s | lat %.3f Mops\nCPU soak %.3f Mops\nGPU soak %.3f draws/s",
                r.label,r.total,r.cpu,r.gpu,r.mem,r.storage,
                r.single,r.multi,r.gpuBurst,r.ramCopy,r.ramLatency,r.cpuSoak,r.gpuSoak);
    }

    private void finishPartial(String text) {
        current.setText(text);
        status.setText("Teste separado concluído.");
        progress.setProgress(100);
        refreshHistoryOnly();
        resumeSceneAfterBench();
        setBusy(false);
    }

    private void refreshAll() {
        List<RunRecord> all=HistoryStore.all(this);
        chart.setData(HistoryStore.full(this));
        if(lastFull==null) {
            analysis.setText(Analytics.historicalSummary(all) +
                    "\nExecute um FULL novo para gerar deltas contra v2.3.3 e Hybrid v1.1.");
        } else {
            analysis.setText(Analytics.compare(lastFull,HistoryStore.full(this)));
        }
        refreshHistoryOnly();
    }

    private void refreshHistoryOnly() {
        List<RunRecord> all=HistoryStore.all(this);
        StringBuilder sb=new StringBuilder();
        for(int i=all.size()-1;i>=0;i--) {
            RunRecord r=all.get(i);
            sb.append(r.historical?"[H] ":"[N] ");
            sb.append(r.timestamp).append(" • ").append(r.label).append(" • ").append(r.kind).append('\n');
            if(r.isFull()) {
                sb.append(String.format(Locale.US,
                        "  TOTAL %.0f | multi %.1f | GPU %.1f | RAM %.0f | CPU-soak %.1f | GPU-soak %.1f\n",
                        r.total,r.multi,r.gpuBurst,r.ramCopy,r.cpuSoak,r.gpuSoak));
            } else if("CPU".equals(r.kind)) {
                sb.append(String.format(Locale.US,"  CPU %.0f | single %.1f | multi %.1f\n",r.cpu,r.single,r.multi));
            } else if("GPU".equals(r.kind)) {
                sb.append(String.format(Locale.US,"  GPU %.0f | %.1f draws/s\n",r.gpu,r.gpuBurst));
            } else if("RAM".equals(r.kind)) {
                sb.append(String.format(Locale.US,"  MEM %.0f | %.0f MB/s | %.2f Mops\n",r.mem,r.ramCopy,r.ramLatency));
            }
            if(!r.source.isEmpty()) sb.append("  ").append(r.source).append('\n');
            if(!r.note.isEmpty() && r.historical) sb.append("  ").append(r.note).append('\n');
            sb.append('\n');
        }
        historyText.setText(sb.toString());
        chart.setData(HistoryStore.full(this));
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Limpar runs novos?")
                .setMessage("Os dados históricos incorporados permanecem. Só os testes executados por este APK serão apagados.")
                .setPositiveButton("Limpar",(d,w)->{
                    HistoryStore.clearLocal(this);
                    lastFull=null; lastReport="";
                    saveBtn.setEnabled(false);
                    current.setText("Runs locais apagados.");
                    refreshAll();
                })
                .setNegativeButton("Cancelar",null).show();
    }

    private void saveReport() {
        if(lastReport.isEmpty()) return;
        String name="S21Lab_History_FULL_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".txt";
        ContentValues cv=new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME,name);
        cv.put(MediaStore.Downloads.MIME_TYPE,"text/plain");
        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        if(Build.VERSION.SDK_INT>=29) cv.put(MediaStore.Downloads.IS_PENDING,1);
        Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);
        if(uri==null){Toast.makeText(this,"Falha ao criar arquivo.",Toast.LENGTH_LONG).show();return;}
        try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){
            if(out==null) throw new IOException("OutputStream nulo");
            out.write(lastReport.getBytes(StandardCharsets.UTF_8)); out.flush();
        } catch(IOException e){
            getContentResolver().delete(uri,null,null);
            Toast.makeText(this,"Erro ao salvar: "+e.getMessage(),Toast.LENGTH_LONG).show();return;
        }
        if(Build.VERSION.SDK_INT>=29){
            ContentValues done=new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING,0);
            getContentResolver().update(uri,done,null,null);
        }
        Toast.makeText(this,"Salvo em Downloads/"+name,Toast.LENGTH_LONG).show();
    }

    private void ui(String s,int p) {
        runOnUiThread(() -> {status.setText(s);progress.setProgress(p);});
    }

    private void fail(Throwable e) {
        runOnUiThread(() -> {
            status.setText("Erro: "+e.getClass().getSimpleName()+": "+e.getMessage());
            current.setText("Teste interrompido; nenhum FULL foi promovido ao histórico.");
            resumeSceneAfterBench();
            setBusy(false);
        });
    }

    private void pauseSceneForBench() {
        sceneBenchPaused=true;
        try { scene.onPause(); } catch(Exception ignored){}
    }

    private void resumeSceneAfterBench() {
        try { scene.onResume(); } catch(Exception ignored){}
        sceneBenchPaused=false;
    }

    private void setBusy(boolean b) {
        busy=b;
        fullBtn.setEnabled(!b); cpuBtn.setEnabled(!b); gpuBtn.setEnabled(!b); ramBtn.setEnabled(!b);
        clearBtn.setEnabled(!b);
    }

    private static double delta(double v,double base) {
        if(v<=0||base<=0)return 0;
        return (v/base-1.0)*100.0;
    }

    private LinearLayout card() {
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14),dp(14),dp(14),dp(14));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.argb(224,13,19,31));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1),Color.rgb(49,61,82));
        l.setBackground(bg);
        return l;
    }

    private TextView text(String s,int sp,int color,boolean bold) {
        TextView t=new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setLineSpacing(0,1.08f);
        return t;
    }

    private Button primaryButton(String s) {
        Button b=new Button(this); b.setText(s); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setTextSize(16); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.rgb(30,113,219)); bg.setCornerRadius(dp(15));
        b.setBackground(bg); return b;
    }

    private Button secondaryButton(String s) {
        Button b=new Button(this); b.setText(s); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setTextSize(15); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.argb(235,27,37,55)); bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1),Color.rgb(69,86,114));
        b.setBackground(bg); return b;
    }

    private Button tinyButton(String s) {
        Button b=secondaryButton(s); b.setTextSize(11);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(46));
        lp.topMargin=dp(9); b.setLayoutParams(lp);
        return b;
    }

    private void addWeighted(LinearLayout row,View v) {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(50),1f);
        lp.setMargins(dp(2),0,dp(2),0);
        row.addView(v,lp);
    }

    private int dp(int v) {
        return (int)(v*getResources().getDisplayMetrics().density+0.5f);
    }
}
