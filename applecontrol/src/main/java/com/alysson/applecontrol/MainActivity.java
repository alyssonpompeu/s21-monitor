package com.alysson.applecontrol;

import android.app.*;
import android.os.*;
import android.provider.MediaStore;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final String[] DOMAIN_NAMES={"CPU A55 / cpucl0","CPU A78 / cpucl1","CPU X1 / cpucl2","GPU G3D","MIF","DSU","INT"};
    private static final String[] NODES={
            "/sys/kernel/percent_margin/cpucl0_margin_percent","/sys/kernel/percent_margin/cpucl1_margin_percent","/sys/kernel/percent_margin/cpucl2_margin_percent",
            "/sys/kernel/percent_margin/g3d_margin_percent","/sys/kernel/percent_margin/mif_margin_percent","/sys/kernel/percent_margin/dsu_margin_percent","/sys/kernel/percent_margin/int_margin_percent"};
    private static final int[] APPLE_DEFAULT={-9,-10,-8,-7,-6,-6,-6};
    private static final long TELEMETRY_INTERVAL_MS=2000;

    private final SeekBar[] marginBars=new SeekBar[7];
    private final TextView[] marginLabels=new TextView[7];
    private TextView controlStatus,readbackView,temperatureView,labStatus,highspeedLabel;
    private Button presetButton,applyButton,fullButton,saveButton,labReadButton,labApplyButton,labReleaseButton,labOcPresetButton;
    private EditText gpuTargetEdit,mifTargetEdit;
    private SeekBar highspeedLoadBar;
    private TextView benchStatus,resultView;
    private ProgressBar progress;
    private volatile String lastReport="";

    private final ExecutorService benchExec=Executors.newSingleThreadExecutor();
    private final ExecutorService rootExec=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Object rootLock=new Object();
    private KernelTelemetry.RootShell rootShell;
    private KernelTelemetry.Discovery discovery;
    private volatile String lastRootError="";
    private volatile boolean activityVisible=false,benchmarkRunning=false;

    private final Runnable liveTick=new Runnable(){@Override public void run(){
        if(!activityVisible||benchmarkRunning)return;
        rootExec.submit(() -> {
            KernelTelemetry.Snapshot s=readSnapshot(2500);
            runOnUiThread(() -> {
                if(s!=null) temperatureView.setText(formatLiveTelemetry(s));
                else temperatureView.setText("Temperaturas: N/D — "+safeError(lastRootError));
                if(activityVisible&&!benchmarkRunning)handler.postDelayed(liveTick,1000);
            });
        });
    }};

    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);buildUi();}
    @Override protected void onResume(){super.onResume();activityVisible=true;handler.removeCallbacks(liveTick);if(!benchmarkRunning)autoReadKernel();}
    @Override protected void onPause(){activityVisible=false;handler.removeCallbacks(liveTick);super.onPause();}

    private void buildUi(){
        int pad=dp(16);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(this);title.setText("G991B Apple Control");title.setTextSize(27);title.setTextColor(Color.rgb(30,75,165));title.setTypeface(null,1);box.addView(title);
        TextView sub=new TextView(this);sub.setText("Apple 1.4 UV • Exynos 2100\nReadback real + temperaturas + GPU/MIF Lab + S21 Lab");sub.setTextSize(15);sub.setPadding(0,dp(4),0,dp(14));box.addView(sub);

        addSectionTitle(box,"Controle de tensão");
        TextView info=new TextView(this);info.setText("Perfil Apple 1.4: A55 -9%, A78 -10%, X1 -8%, GPU -7%, MIF/DSU/INT -6%. Ao abrir, os sliders recebem o readback REAL do kernel; se root/leitura falhar, aparece N/D.");info.setTextSize(14);info.setPadding(0,0,0,dp(8));box.addView(info);
        for(int i=0;i<7;i++)addMarginControl(box,i);
        presetButton=button("Padrão Apple 1.4 UV");applyButton=button("Aplicar + verificar");box.addView(presetButton);box.addView(applyButton);
        controlStatus=text(14);controlStatus.setText("Lendo kernel automaticamente...");box.addView(controlStatus);
        readbackView=textMono(12);readbackView.setText("Aguardando kernel.");box.addView(readbackView);
        presetButton.setOnClickListener(v->{setPreset(APPLE_DEFAULT);controlStatus.setText("Preset Apple 1.4 carregado nos sliders; ainda NÃO escrito.");});
        applyButton.setOnClickListener(v->applyMargins());

        addDivider(box);addSectionTitle(box,"Temperaturas live");
        temperatureView=textMono(14);temperatureView.setText("Lendo...");box.addView(temperatureView);

        addDivider(box);addSectionTitle(box,"GPU / MIF Lab");
        TextView li=text(13);li.setText("Controles separados da CPU. O app só aplica frequência se o OPP existir na tabela real do kernel. Digitar 900/3264 não cria OC: se o OPP não existir, o app recusa e mostra UNSUPPORTED.");box.addView(li);
        LinearLayout row1=new LinearLayout(this);row1.setOrientation(LinearLayout.HORIZONTAL);
        gpuTargetEdit=numberField("GPU MHz",858);mifTargetEdit=numberField("MIF MHz",3172);row1.addView(gpuTargetEdit,new LinearLayout.LayoutParams(0,-2,1));row1.addView(mifTargetEdit,new LinearLayout.LayoutParams(0,-2,1));box.addView(row1);
        highspeedLabel=text(14);box.addView(highspeedLabel);
        highspeedLoadBar=new SeekBar(this);highspeedLoadBar.setMax(50);highspeedLoadBar.setProgress(20);highspeedLoadBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean f){highspeedLabel.setText("GPU highspeed_load: "+(40+p)+"%");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});box.addView(highspeedLoadBar);highspeedLabel.setText("GPU highspeed_load: 60%");
        labReadButton=button("Ler GPU/MIF");labApplyButton=button("Aplicar lock + validar OPP");labReleaseButton=button("Liberar locks GPU/MIF");labOcPresetButton=button("Preset OC teste 900 / 3264");
        box.addView(labReadButton);box.addView(labApplyButton);box.addView(labReleaseButton);box.addView(labOcPresetButton);
        labStatus=textMono(12);labStatus.setText("Aguardando leitura das tabelas OPP.");box.addView(labStatus);
        labReadButton.setOnClickListener(v->refreshGpuMif());
        labOcPresetButton.setOnClickListener(v->{gpuTargetEdit.setText("900");mifTargetEdit.setText("3264");labStatus.setText("Preset 900/3264 carregado. Toque em Aplicar: só será escrito se os OPPs existirem de verdade.");});
        labApplyButton.setOnClickListener(v->applyGpuMif());labReleaseButton.setOnClickListener(v->releaseGpuMif());

        addDivider(box);addSectionTitle(box,"S21 Lab Benchmark");
        TextView bi=text(14);bi.setText("Workload e fórmulas S21Lab preservados. A telemetria agora descobre os paths uma vez e depois faz leituras diretas a cada 2 s, reduzindo os TIMEOUTs. O TXT inclui UV, temperaturas, clocks, tabela GPU e frequências MIF.");box.addView(bi);
        fullButton=button("Iniciar FULL");saveButton=button("Salvar log TXT");saveButton.setEnabled(false);box.addView(fullButton);box.addView(saveButton);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);box.addView(progress,new LinearLayout.LayoutParams(-1,dp(18)));
        benchStatus=text(16);benchStatus.setText("Pronto.");box.addView(benchStatus);
        resultView=textMono(12);resultView.setText("Execute o FULL.");resultView.setTextIsSelectable(true);box.addView(resultView);
        fullButton.setOnClickListener(v->runBench());saveButton.setOnClickListener(v->saveReport());

        ScrollView scroll=new ScrollView(this);scroll.addView(box);setContentView(scroll);setMarginsUnknown("lendo kernel");
    }

    private TextView text(int size){TextView t=new TextView(this);t.setTextSize(size);t.setPadding(0,dp(5),0,dp(5));return t;}
    private TextView textMono(int size){TextView t=text(size);t.setTypeface(android.graphics.Typeface.MONOSPACE);return t;}
    private void addSectionTitle(LinearLayout b,String s){TextView t=text(22);t.setTypeface(null,1);t.setText(s);b.addView(t);}
    private void addDivider(LinearLayout b){View v=new View(this);v.setBackgroundColor(Color.rgb(205,205,205));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.setMargins(0,dp(18),0,dp(12));b.addView(v,lp);}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(16);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(4),0,dp(4));b.setLayoutParams(lp);return b;}
    private EditText numberField(String hint,int value){EditText e=new EditText(this);e.setHint(hint);e.setInputType(InputType.TYPE_CLASS_NUMBER);e.setText(Integer.toString(value));return e;}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}

    private void addMarginControl(LinearLayout box,int i){TextView l=text(16);l.setTypeface(null,1);l.setText(DOMAIN_NAMES[i]+": N/D");box.addView(l);marginLabels[i]=l;SeekBar b=new SeekBar(this);b.setMax(30);b.setProgress(APPLE_DEFAULT[i]+15);b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar x,int p,boolean f){if(f)updateMarginLabel(i);}public void onStartTrackingTouch(SeekBar x){}public void onStopTrackingTouch(SeekBar x){}});marginBars[i]=b;box.addView(b);}
    private void updateMarginLabel(int i){int v=marginBars[i].getProgress()-15;marginLabels[i].setText(DOMAIN_NAMES[i]+": "+(v>0?"+":"")+v+"%");}
    private void setMarginsUnknown(String why){for(int i=0;i<7;i++){marginBars[i].setProgress(APPLE_DEFAULT[i]+15);marginLabels[i].setText(DOMAIN_NAMES[i]+": N/D ("+why+")");}}
    private void setPreset(int[] v){for(int i=0;i<7;i++){marginBars[i].setProgress(v[i]+15);updateMarginLabel(i);}}
    private int[] currentMargins(){int[] v=new int[7];for(int i=0;i<7;i++)v[i]=marginBars[i].getProgress()-15;return v;}

    private KernelTelemetry.RootShell ensureRootShell() throws IOException {synchronized(rootLock){if(rootShell==null||!rootShell.isAlive()){if(rootShell!=null)try{rootShell.close();}catch(Exception ignored){}rootShell=KernelTelemetry.RootShell.open();discovery=null;}return rootShell;}}
    private KernelTelemetry.Discovery ensureDiscovery() throws IOException {synchronized(rootLock){if(discovery!=null)return discovery;KernelTelemetry.ExecResult r=ensureRootShell().exec(KernelTelemetry.discoveryCommand(),7000);if(r.exitCode!=0)throw new IOException("discovery exit="+r.exitCode+" "+r.output);discovery=KernelTelemetry.parseDiscovery(r.output);return discovery;}}
    private KernelTelemetry.Snapshot readSnapshot(long timeout){synchronized(rootLock){try{KernelTelemetry.Discovery d=ensureDiscovery();KernelTelemetry.ExecResult r=ensureRootShell().exec(KernelTelemetry.fastSnapshotCommand(d),timeout);if(r.exitCode!=0){lastRootError="exit="+r.exitCode+" "+r.output;return null;}lastRootError="";return KernelTelemetry.Snapshot.parse(r.output);}catch(Exception e){lastRootError=e.getMessage();return null;}}}
    private KernelTelemetry.ExecResult rootCommand(String cmd,long timeout){synchronized(rootLock){try{return ensureRootShell().exec(cmd,timeout);}catch(Exception e){lastRootError=e.getMessage();return new KernelTelemetry.ExecResult(127,lastRootError);}}}

    private void autoReadKernel(){controlStatus.setText("Lendo kernel automaticamente...");rootExec.submit(() -> {KernelTelemetry.Snapshot s=readSnapshot(4000);KernelTelemetry.Discovery d=null;try{d=ensureDiscovery();}catch(Exception ignored){}KernelTelemetry.Discovery fd=d;runOnUiThread(() -> {if(s!=null&&s.margins()!=null){setPreset(s.margins());controlStatus.setText("OK — sliders sincronizados com o kernel REAL.");readbackView.setText(formatKernelReadback(s));temperatureView.setText(formatLiveTelemetry(s));if(fd!=null)labStatus.setText(fd.summary()+"\nGPU max="+(fd.gpuMax()/1000)+" MHz | MIF max="+(fd.mifMax()/1000)+" MHz");handler.postDelayed(liveTick,1000);}else{setMarginsUnknown("readback indisponível");controlStatus.setText("ATENÇÃO — root/readback não confirmado.");readbackView.setText("N/D — "+safeError(lastRootError));}});});}

    private String formatKernelReadback(KernelTelemetry.Snapshot s){return "=== KERNEL READBACK REAL ===\n"+s.marginsText()+"\nA55 cur/max="+s.get("p0_cur")+"/"+s.get("p0_max")+" kHz\nA78 cur/max="+s.get("p4_cur")+"/"+s.get("p4_max")+" kHz\nX1 cur/max="+s.get("p7_cur")+"/"+s.get("p7_max")+" kHz\nGPU clock="+s.get("gpu_clock")+" kHz util="+s.get("gpu_util")+"%\nMIF cur/min/max="+s.get("mif_cur")+"/"+s.get("mif_min")+"/"+s.get("mif_max")+" kHz\nGPU locks raw="+s.get("gpu_min_lock")+" | "+s.get("gpu_max_lock");}
    private String formatLiveTelemetry(KernelTelemetry.Snapshot s){return "A55/LITTLE  "+tempText(s,"little_temp")+" | "+mhzText(s,"p0_cur")+"\nA78/MID     "+tempText(s,"mid_temp")+" | "+mhzText(s,"p4_cur")+"\nX1/BIG      "+tempText(s,"big_temp")+" | "+mhzText(s,"p7_cur")+"\nGPU/G3D     "+tempText(s,"g3d_temp")+" | "+mhzText(s,"gpu_clock")+"\nMIF/DDR5    "+tempText(s,"mif_temp")+" | "+mhzText(s,"mif_cur");}
    private String tempText(KernelTelemetry.Snapshot s,String k){Double v=s.tempC(k);return v==null?"N/D":String.format(Locale.US,"%5.1f °C",v);}private String mhzText(KernelTelemetry.Snapshot s,String k){Long v=s.longValue(k);return v==null?"N/D":String.format(Locale.US,"%4.0f MHz",v/1000.0);}
    private static String safeError(String s){if(s==null||s.trim().isEmpty())return "indisponível";s=s.replace('\n',' ');return s.length()>180?s.substring(0,180)+"…":s;}

    private void applyMargins(){final int[] req=currentMargins();setControllerEnabled(false);controlStatus.setText("Aplicando...");rootExec.submit(() -> {KernelTelemetry.ExecResult w=rootCommand(KernelTelemetry.applyMarginsCommand(NODES,req),7000);KernelTelemetry.Snapshot s=readSnapshot(4000);boolean ok=w.exitCode==0&&s!=null&&Arrays.equals(req,s.margins());runOnUiThread(() -> {if(s!=null&&s.margins()!=null){setPreset(s.margins());readbackView.setText("Solicitado: "+formatValues(req)+"\nReadback: "+s.marginsText()+"\n\n"+formatKernelReadback(s));}controlStatus.setText(ok?"OK — escrita/readback confirmados.":"ATENÇÃO — kernel não confirmou exatamente o solicitado.");setControllerEnabled(true);});});}
    private String formatValues(int[] v){return String.format(Locale.US,"A55=%d%% A78=%d%% X1=%d%% GPU=%d%% MIF=%d%% DSU=%d%% INT=%d%%",v[0],v[1],v[2],v[3],v[4],v[5],v[6]);}
    private void setControllerEnabled(boolean e){presetButton.setEnabled(e);applyButton.setEnabled(e);for(SeekBar b:marginBars)b.setEnabled(e);}

    private int editInt(EditText e,int def){try{return Integer.parseInt(e.getText().toString().trim());}catch(Exception x){return def;}}
    private void refreshGpuMif(){labStatus.setText("Lendo tabelas...");rootExec.submit(() -> {try{discovery=null;KernelTelemetry.Discovery d=ensureDiscovery();KernelTelemetry.Snapshot s=readSnapshot(3000);runOnUiThread(() -> labStatus.setText(d.summary()+"\nGPU max="+(d.gpuMax()/1000)+" MHz | MIF max="+(d.mifMax()/1000)+" MHz\nAtual: GPU="+(s==null?"N/D":s.get("gpu_clock"))+" kHz | MIF="+(s==null?"N/D":s.get("mif_cur"))+" kHz"));}catch(Exception e){runOnUiThread(() -> labStatus.setText("Erro: "+e.getMessage()));}});}
    private void applyGpuMif(){int gpuMHz=editInt(gpuTargetEdit,858),mifMHz=editInt(mifTargetEdit,3172),hsl=40+highspeedLoadBar.getProgress();long g=gpuMHz*1000L,m=mifMHz*1000L;labStatus.setText("Validando OPPs...");rootExec.submit(() -> {try{KernelTelemetry.Discovery d=ensureDiscovery();if(!d.gpuHas(g)||!d.mifHas(m)){String msg="NÃO APLICADO. "+(!d.gpuHas(g)?"GPU "+gpuMHz+" MHz não existe na dvfs_table. ":"")+(!d.mifHas(m)?"MIF "+mifMHz+" MHz não existe em available_frequencies. ":"")+"\nMáximos reais expostos: GPU="+(d.gpuMax()/1000)+" MHz, MIF="+(d.mifMax()/1000)+" MHz.";runOnUiThread(() -> labStatus.setText(msg));return;}KernelTelemetry.ExecResult r=rootCommand(KernelTelemetry.applyGpuMifCommand(d,g,m,hsl),5000);KernelTelemetry.Snapshot s=readSnapshot(3000);runOnUiThread(() -> labStatus.setText("exit="+r.exitCode+"\nOPPs válidos. Lock solicitado: GPU="+gpuMHz+" MHz, MIF="+mifMHz+" MHz, highspeed_load="+hsl+"%\nReadback: GPU min="+(s==null?"N/D":s.get("gpu_min_lock"))+" | MIF min="+(s==null?"N/D":s.get("mif_min"))));}catch(Exception e){runOnUiThread(() -> labStatus.setText("Erro: "+e.getMessage()));}});}
    private void releaseGpuMif(){labStatus.setText("Liberando locks...");rootExec.submit(() -> {try{KernelTelemetry.Discovery d=ensureDiscovery();KernelTelemetry.ExecResult r=rootCommand(KernelTelemetry.releaseGpuMifCommand(d),4000);KernelTelemetry.Snapshot s=readSnapshot(3000);runOnUiThread(() -> labStatus.setText("Liberado. exit="+r.exitCode+"\nGPU min="+(s==null?"N/D":s.get("gpu_min_lock"))+" | MIF min="+(s==null?"N/D":s.get("mif_min"))));}catch(Exception e){runOnUiThread(() -> labStatus.setText("Erro: "+e.getMessage()));}});}

    private final class BenchmarkTelemetryRunner{
        final KernelTelemetry.Accumulator acc=new KernelTelemetry.Accumulator(TELEMETRY_INTERVAL_MS);final AtomicBoolean running=new AtomicBoolean();Thread thread;KernelTelemetry.Discovery d;
        void start(){try{d=ensureDiscovery();}catch(Exception e){acc.fail(e.getMessage());return;}KernelTelemetry.Snapshot first=readSnapshot(3000);if(first==null){acc.fail(lastRootError);return;}acc.add(first);running.set(true);thread=new Thread(() -> {while(running.get()){try{Thread.sleep(TELEMETRY_INTERVAL_MS);}catch(InterruptedException e){if(!running.get())break;}if(!running.get())break;KernelTelemetry.Snapshot s=readSnapshot(1800);if(s!=null)acc.add(s);else acc.fail(lastRootError);}},"AppleTelemetrySampler");thread.setPriority(Thread.MIN_PRIORITY);thread.start();}
        void stop(){running.set(false);if(thread!=null){thread.interrupt();try{thread.join(2200);}catch(InterruptedException e){Thread.currentThread().interrupt();}}KernelTelemetry.Snapshot end=readSnapshot(2500);if(end!=null)acc.add(end);else acc.fail(lastRootError);}
        String report(){if(d==null)d=KernelTelemetry.parseDiscovery("");return acc.reportBlock(d);}
    }

    private void runBench(){benchmarkRunning=true;handler.removeCallbacks(liveTick);fullButton.setEnabled(false);saveButton.setEnabled(false);setControllerEnabled(false);labApplyButton.setEnabled(false);labReleaseButton.setEnabled(false);lastReport="";resultView.setText("");progress.setProgress(0);temperatureView.setText("FULL em execução — telemetria direta a cada 2 s.");
        benchExec.submit(() -> {BenchmarkTelemetryRunner tel=new BenchmarkTelemetryRunner();try{final long singleMs=12000,multiMs=20000,gpuMs=18000,memMs=12000,soakMs=180000;final int storageMB=256;ui("Preparando telemetria...",2);tel.start();ui("CPU single-core...",5);double single=Benchmarks.cpuSingleMops(singleMs);ui("CPU multi-core...",18);int threads=Math.max(1,Runtime.getRuntime().availableProcessors());double multi=Benchmarks.cpuMultiMops(multiMs,threads);ui("GPU OpenGL ES...",35);GpuBench.Result gpu=GpuBench.run(gpuMs);ui("Memória RAM...",52);double memBw=Benchmarks.memoryBandwidthMBs(memMs);double memLat=Benchmarks.memoryLatencyMops(Math.max(3000,memMs/2));ui("Armazenamento...",66);Benchmarks.StorageResult storage=Benchmarks.storage(getCacheDir(),storageMB);ui("CPU + GPU / thermal soak...",78);final double[] soakCpu=new double[1];Thread cpuSoak=new Thread(() -> {try{soakCpu[0]=Benchmarks.cpuMultiMops(soakMs,threads);}catch(InterruptedException ignored){}},"S21LabSoakCPU");cpuSoak.start();GpuBench.Result soakGpu=GpuBench.run(soakMs);cpuSoak.join();ui("Fechando telemetria...",94);tel.stop();lastReport=buildReport(threads,single,multi,gpu,memBw,memLat,storage,soakCpu[0],soakGpu,tel.report());runOnUiThread(() -> finishBench(true,null));}catch(Throwable e){try{tel.stop();}catch(Throwable ignored){}runOnUiThread(() -> finishBench(false,e));}});
    }
    private void finishBench(boolean ok,Throwable e){benchmarkRunning=false;fullButton.setEnabled(true);setControllerEnabled(true);labApplyButton.setEnabled(true);labReleaseButton.setEnabled(true);if(ok){saveButton.setEnabled(true);progress.setProgress(100);benchStatus.setText("Concluído. Salve o TXT.");resultView.setText(lastReport);}else benchStatus.setText("Erro: "+e);autoReadKernel();}
    private void ui(String s,int p){runOnUiThread(() -> {benchStatus.setText(s);progress.setProgress(p);});}

    private String buildReport(int threads,double single,double multi,GpuBench.Result gpu,double memBw,double memLat,Benchmarks.StorageResult storage,double soakCpu,GpuBench.Result soakGpu,String telemetry){double cpuScore=single*90.0+multi*28.0,gpuScore=gpu.drawsPerSecond*140.0,memScore=memBw*7.0+memLat*900.0,storageScore=(storage.writeMBs+storage.readMBs)*4.0,total=cpuScore*.36+gpuScore*.34+memScore*.20+storageScore*.10;StringBuilder sb=new StringBuilder(10000);sb.append("S21 LAB BENCHMARK REPORT\nS21Lab Score v1.2\napple_control=1.3-gpumif\nmode=FULL\n");sb.append("timestamp=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date())).append('\n');sb.append("model=").append(Build.MODEL).append("\ndevice=").append(Build.DEVICE).append("\nhardware=").append(Build.HARDWARE).append("\nbuild=").append(Build.DISPLAY).append('\n');sb.append("android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT).append("\nkernel=").append(System.getProperty("os.version")).append("\ncpu_threads=").append(threads).append("\nrefresh_current_hz=").append(getDisplay().getRefreshRate()).append("\n\n");sb.append("=== SCORE ===\n").append(String.format(Locale.US,"TOTAL=%.0f\nCPU=%.0f\nGPU=%.0f\nMEM=%.0f\nSTORAGE=%.0f\n\n",total,cpuScore,gpuScore,memScore,storageScore));sb.append("=== RAW BENCHMARK ===\n").append(String.format(Locale.US,"cpu_single_mops=%.3f\ncpu_multi_mops=%.3f\ngpu_draws_per_sec=%.3f\ngpu_status=%s\nram_copy_MBps=%.2f\nram_latency_Mops=%.3f\nstorage_write_MBps=%.2f\nstorage_read_MBps=%.2f\nsoak_cpu_multi_mops=%.3f\nsoak_gpu_draws_per_sec=%.3f\nsoak_gpu_status=%s\n\n",single,multi,gpu.drawsPerSecond,gpu.status,memBw,memLat,storage.writeMBs,storage.readMBs,soakCpu,soakGpu.drawsPerSecond,soakGpu.status));sb.append(telemetry).append("\ntxt_state=FINAL_CLOSED\nEND_REPORT\n");return sb.toString();}

    private void saveReport(){if(lastReport.isEmpty())return;String name="S21Lab_FULL_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".txt";ContentValues cv=new ContentValues();cv.put(MediaStore.Downloads.DISPLAY_NAME,name);cv.put(MediaStore.Downloads.MIME_TYPE,"text/plain");cv.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);if(Build.VERSION.SDK_INT>=29)cv.put(MediaStore.Downloads.IS_PENDING,1);Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);if(uri==null){Toast.makeText(this,"Falha ao criar arquivo.",Toast.LENGTH_LONG).show();return;}try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new IOException("OutputStream nulo");out.write(lastReport.getBytes(StandardCharsets.UTF_8));}catch(IOException e){getContentResolver().delete(uri,null,null);Toast.makeText(this,"Erro: "+e.getMessage(),Toast.LENGTH_LONG).show();return;}if(Build.VERSION.SDK_INT>=29){ContentValues done=new ContentValues();done.put(MediaStore.Downloads.IS_PENDING,0);getContentResolver().update(uri,done,null,null);}Toast.makeText(this,"Salvo em Downloads/"+name,Toast.LENGTH_LONG).show();}

    @Override protected void onDestroy(){activityVisible=false;benchmarkRunning=false;handler.removeCallbacksAndMessages(null);synchronized(rootLock){if(rootShell!=null)try{rootShell.close();}catch(Exception ignored){}}rootExec.shutdownNow();benchExec.shutdownNow();super.onDestroy();}
}