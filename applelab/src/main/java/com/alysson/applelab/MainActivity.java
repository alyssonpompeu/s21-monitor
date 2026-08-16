package com.alysson.applelab;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final String RAWCLOCK_SHA = "8d033c9079d648372c8e509cc40ebeacb7a9e0d45554edadc19be56bf027e3e8";
    private static final String STOCK_MALI_SHA = "20059aebd341856d99555931a09c64fa46bd9cc2e07242abf0e7a58d6f80ac02";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status, output;
    private Button scan, gpuPulse, mifPulse, save;
    private volatile String lastReport = "";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        runFullScan();
    }

    private void buildUi() {
        int p = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p,p,p,p);

        TextView title = new TextView(this);
        title.setText("Apple Lab");
        title.setTextSize(30);
        title.setTypeface(null, 1);
        title.setTextColor(Color.rgb(28,72,160));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("G991B • Exynos 2100 • OPP / CAL / GPU / MIF diagnostic\nNão assume caminhos fixos. Só chama OC de real quando há readback físico.");
        sub.setTextSize(15);
        sub.setPadding(0,dp(4),0,dp(12));
        root.addView(sub);

        status = new TextView(this);
        status.setText("Preparando scanner root...");
        status.setTextSize(15);
        status.setPadding(0,0,0,dp(8));
        root.addView(status);

        scan = button("SCAN COMPLETO + READBACK");
        gpuPulse = button("PULSO GPU 900 MHz • 3 s");
        mifPulse = button("PULSO MIF 3264 MHz • 3 s");
        save = button("SALVAR DIAGNÓSTICO TXT");
        save.setEnabled(false);
        root.addView(scan); root.addView(gpuPulse); root.addView(mifPulse); root.addView(save);

        TextView warn = new TextView(this);
        warn.setText("Segurança: GPU 900 só é tentado se o módulo RAWCLOCK exato for detectado. MIF 3264 só é tentado se 3264000 aparecer na tabela live. Os pulsos restauram DVFS/margens automaticamente.");
        warn.setTextSize(13);
        warn.setPadding(0,dp(8),0,dp(8));
        root.addView(warn);

        output = new TextView(this);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        output.setText("Aguardando scan...");
        root.addView(output);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        scan.setOnClickListener(v -> runFullScan());
        gpuPulse.setOnClickListener(v -> runGpuPulse());
        mifPulse.setOnClickListener(v -> runMifPulse());
        save.setOnClickListener(v -> saveTxt());
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(3),0,dp(3)); b.setLayoutParams(lp);
        return b;
    }
    private int dp(int v) { return (int)(v*getResources().getDisplayMetrics().density+0.5f); }

    private void busy(boolean b) {
        scan.setEnabled(!b); gpuPulse.setEnabled(!b); mifPulse.setEnabled(!b); save.setEnabled(!b && !lastReport.isEmpty());
    }

    private void runFullScan() {
        busy(true); status.setText("Escaneando sysfs, tabelas OPP e módulos...");
        worker.submit(() -> {
            RootResult rr = root(SCAN_SCRIPT, 20);
            String report = "APPLE LAB REPORT\nversion=1.0\ntimestamp=" + now() + "\n" +
                    "model=" + Build.MODEL + "\nbuild=" + Build.DISPLAY + "\nandroid=" + Build.VERSION.RELEASE + "\n\n" + rr.output;
            lastReport = report;
            runOnUiThread(() -> {
                output.setText(report);
                status.setText(rr.exitCode == 0 ? verdict(report) : "SCAN falhou: exit="+rr.exitCode);
                busy(false); save.setEnabled(true);
            });
        });
    }

    private String verdict(String r) {
        boolean raw = r.contains("rawclock_backend=ACTIVE");
        boolean g900 = r.contains("gpu_opp_900=EXPOSED");
        boolean m3264 = r.contains("mif_opp_3264=EXPOSED");
        return "OK • RAWCLOCK="+(raw?"ATIVO":"não")+" • GPU900="+(g900?"OPP exposto":raw?"pulso raw possível":"não exposto")+" • MIF3264="+(m3264?"OPP exposto":"não exposto");
    }

    private void runGpuPulse() {
        busy(true); status.setText("Pulso GPU 900: validando hash, temperatura e readback...");
        worker.submit(() -> {
            RootResult rr = root(GPU_PULSE_SCRIPT, 15);
            String block = "\n\n=== GPU 900 PULSE ===\n"+rr.output+"\nexit="+rr.exitCode+"\n";
            lastReport += block;
            runOnUiThread(() -> {
                output.setText(lastReport);
                status.setText(rr.output.contains("GPU900_RESULT=PASS") ? "GPU 900: READBACK CONFIRMADO" : "GPU 900: não confirmado / não tentado");
                busy(false);
            });
        });
    }

    private void runMifPulse() {
        busy(true); status.setText("Pulso MIF 3264: validando tabela live e readback...");
        worker.submit(() -> {
            RootResult rr = root(MIF_PULSE_SCRIPT, 15);
            String block = "\n\n=== MIF 3264 PULSE ===\n"+rr.output+"\nexit="+rr.exitCode+"\n";
            lastReport += block;
            runOnUiThread(() -> {
                output.setText(lastReport);
                status.setText(rr.output.contains("MIF3264_RESULT=PASS") ? "MIF 3264: READBACK CONFIRMADO" : "MIF 3264: não confirmado / não tentado");
                busy(false);
            });
        });
    }

    private RootResult root(String cmd, int timeoutSec) {
        try {
            java.lang.Process p = new java.lang.ProcessBuilder("su","-c",cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Thread t = new Thread(() -> {
                try (InputStream in = p.getInputStream()) { byte[] b=new byte[8192]; int n; while((n=in.read(b))>=0) bos.write(b,0,n); }
                catch(IOException ignored) {}
            });
            t.start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); t.join(500); return new RootResult(124,"TIMEOUT\n"+bos.toString(StandardCharsets.UTF_8)); }
            t.join(1000);
            return new RootResult(p.exitValue(), bos.toString(StandardCharsets.UTF_8).trim());
        } catch (Exception e) { return new RootResult(127,"ROOT_ERR="+e); }
    }

    private void saveTxt() {
        if (lastReport.isEmpty()) return;
        String name = "AppleLab_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".txt";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME,name); cv.put(MediaStore.Downloads.MIME_TYPE,"text/plain");
        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        if (Build.VERSION.SDK_INT>=29) cv.put(MediaStore.Downloads.IS_PENDING,1);
        Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);
        if(u==null){Toast.makeText(this,"Falha ao criar TXT",Toast.LENGTH_LONG).show();return;}
        try(OutputStream os=getContentResolver().openOutputStream(u,"w")){ os.write(lastReport.getBytes(StandardCharsets.UTF_8)); }
        catch(Exception e){getContentResolver().delete(u,null,null);Toast.makeText(this,"Erro: "+e,Toast.LENGTH_LONG).show();return;}
        if(Build.VERSION.SDK_INT>=29){ContentValues done=new ContentValues();done.put(MediaStore.Downloads.IS_PENDING,0);getContentResolver().update(u,done,null,null);}
        Toast.makeText(this,"Salvo em Downloads/"+name,Toast.LENGTH_LONG).show();
    }

    private String now(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date());}
    private static class RootResult { final int exitCode; final String output; RootResult(int c,String o){exitCode=c;output=o==null?"":o;} }

    private static final String SCAN_SCRIPT = String.join("\n",
        "echo root_id=$(id)",
        "echo kernel=$(uname -r)",
        "echo verifiedbootstate=$(getprop ro.boot.verifiedbootstate 2>/dev/null)",
        "echo vbmeta_state=$(getprop ro.boot.vbmeta.device_state 2>/dev/null)",
        "echo '=== GPU PATH SCAN ==='",
        "GPU=''",
        "for p in /sys/devices/platform/18500000.mali /sys/kernel/gpu /sys/class/misc/mali0/device; do [ -e \"$p/dvfs_table\" ] && { GPU=\"$p\"; break; }; done",
        "if [ -z \"$GPU\" ]; then GPU=$(find /sys/devices /sys/kernel /sys/class -maxdepth 6 -type f -name dvfs_table 2>/dev/null | head -1 | sed 's#/dvfs_table$##'); fi",
        "echo gpu_path=$GPU",
        "for n in clock dvfs_table asv_table dvfs_governor highspeed_clock highspeed_load highspeed_delay polling_speed dvfs_min_lock dvfs_max_lock dvfs_min_lock_status dvfs_max_lock_status utilization time_in_state; do echo ---gpu_$n---; [ -n \"$GPU\" ] && cat \"$GPU/$n\" 2>/dev/null || echo NA; done",
        "if [ -n \"$GPU\" ] && tr ' ,\\n' '\\n' < \"$GPU/dvfs_table\" 2>/dev/null | grep -qx 900000; then echo gpu_opp_900=EXPOSED; else echo gpu_opp_900=NOT_EXPOSED; fi",
        "echo '=== MALI MODULE ==='",
        "MALI=''; for b in /vendor/lib/modules /vendor_dlkm/lib/modules /odm/lib/modules /lib/modules; do [ -d \"$b\" ] || continue; MALI=$(find \"$b\" -type f -name mali_kbase.ko 2>/dev/null | head -1); [ -n \"$MALI\" ] && break; done",
        "echo mali_path=$MALI",
        "MSHA=$([ -n \"$MALI\" ] && sha256sum \"$MALI\" 2>/dev/null | awk '{print $1}')",
        "echo mali_sha256=$MSHA",
        "if [ \"$MSHA\" = '"+RAWCLOCK_SHA+"' ]; then echo rawclock_backend=ACTIVE; elif [ \"$MSHA\" = '"+STOCK_MALI_SHA+"' ]; then echo rawclock_backend=STOCK_TABLE_GUARD; else echo rawclock_backend=UNKNOWN; fi",
        "echo '=== MIF PATH SCAN ==='",
        "MIF=''; for d in /sys/class/devfreq/*; do [ -e \"$d\" ] || continue; b=$(basename \"$d\"); case \"$b\" in *mif*|*MIF*) MIF=\"$d\"; break;; esac; done",
        "if [ -z \"$MIF\" ]; then for d in /sys/class/devfreq/*; do [ -r \"$d/max_freq\" ] || continue; x=$(cat \"$d/max_freq\" 2>/dev/null); [ \"$x\" = 3172000 ] && { MIF=\"$d\"; break; }; done; fi",
        "echo mif_path=$MIF",
        "for n in name governor cur_freq min_freq max_freq available_frequencies target_freq; do echo ---mif_$n---; [ -n \"$MIF\" ] && cat \"$MIF/$n\" 2>/dev/null || echo NA; done",
        "if [ -n \"$MIF\" ] && tr ' ,\\n' '\\n' < \"$MIF/available_frequencies\" 2>/dev/null | grep -qx 3264000; then echo mif_opp_3264=EXPOSED; else echo mif_opp_3264=NOT_EXPOSED; fi",
        "echo '=== UV ==='",
        "for n in cpucl0 cpucl1 cpucl2 g3d mif dsu int; do f=/sys/kernel/percent_margin/${n}_margin_percent; printf '%s=' \"$n\"; cat \"$f\" 2>/dev/null || echo NA; done",
        "echo '=== THERMAL ==='",
        "for z in /sys/class/thermal/thermal_zone*; do [ -r \"$z/type\" ] || continue; t=$(cat \"$z/type\" 2>/dev/null); case \"$t\" in LITTLE|MID|BIG|G3D|*MIF*|*mif*|*DDR*|*ddr*|*DRAM*|*dram*) echo $t=$(cat \"$z/temp\" 2>/dev/null);; esac; done",
        "echo scan_state=FINAL_CLOSED"
    );

    private static final String GPU_PULSE_SCRIPT = String.join("\n",
        "GPU=/sys/devices/platform/18500000.mali",
        "[ -r \"$GPU/clock\" ] || { echo GPU900_RESULT=NO_GPU_NODE; exit 10; }",
        "MALI=''; for b in /vendor/lib/modules /vendor_dlkm/lib/modules /odm/lib/modules /lib/modules; do [ -d \"$b\" ] || continue; MALI=$(find \"$b\" -type f -name mali_kbase.ko 2>/dev/null | head -1); [ -n \"$MALI\" ] && break; done",
        "MSHA=$([ -n \"$MALI\" ] && sha256sum \"$MALI\" 2>/dev/null | awk '{print $1}')",
        "echo mali_sha256=$MSHA",
        "[ \"$MSHA\" = '"+RAWCLOCK_SHA+"' ] || { echo GPU900_RESULT=RAWCLOCK_NOT_ACTIVE; exit 20; }",
        "TEMP=999999; for z in /sys/class/thermal/thermal_zone*; do [ \"$(cat \"$z/type\" 2>/dev/null)\" = G3D ] && { TEMP=$(cat \"$z/temp\" 2>/dev/null); break; }; done",
        "echo g3d_temp_before=$TEMP",
        "[ \"$TEMP\" -lt 70000 ] 2>/dev/null || { echo GPU900_RESULT=TOO_HOT; exit 21; }",
        "UV=/sys/kernel/percent_margin/g3d_margin_percent; OLD=$(cat \"$UV\" 2>/dev/null); echo gpu_margin_before=$OLD",
        "cleanup(){ echo 0 > \"$GPU/clock\" 2>/dev/null; [ -n \"$OLD\" ] && echo \"$OLD\" > \"$UV\" 2>/dev/null; }",
        "trap cleanup EXIT INT TERM",
        "echo 0 > \"$UV\" 2>/dev/null || true",
        "echo 900000 > \"$GPU/clock\" 2>/dev/null || { echo GPU900_RESULT=WRITE_FAILED; exit 22; }",
        "PASS=0; for i in 1 2 3; do sleep 1; C=$(cat \"$GPU/clock\" 2>/dev/null); echo gpu_clock_sample_$i=$C; [ \"$C\" = 900000 ] && PASS=1; done",
        "if [ \"$PASS\" = 1 ]; then echo GPU900_RESULT=PASS; else echo GPU900_RESULT=NO_900_READBACK; fi"
    );

    private static final String MIF_PULSE_SCRIPT = String.join("\n",
        "MIF=''; for d in /sys/class/devfreq/*; do case \"$(basename \"$d\")\" in *mif*|*MIF*) MIF=\"$d\"; break;; esac; done",
        "[ -n \"$MIF\" ] || { echo MIF3264_RESULT=NO_MIF_PATH; exit 30; }",
        "tr ' ,\\n' '\\n' < \"$MIF/available_frequencies\" 2>/dev/null | grep -qx 3264000 || { echo MIF3264_RESULT=OPP_NOT_EXPOSED; exit 31; }",
        "UV=/sys/kernel/percent_margin/mif_margin_percent; OLD_UV=$(cat \"$UV\" 2>/dev/null); OLD_MIN=$(cat \"$MIF/min_freq\" 2>/dev/null); OLD_MAX=$(cat \"$MIF/max_freq\" 2>/dev/null)",
        "echo mif_margin_before=$OLD_UV; echo mif_min_before=$OLD_MIN; echo mif_max_before=$OLD_MAX",
        "cleanup(){ [ -n \"$OLD_MIN\" ] && echo \"$OLD_MIN\" > \"$MIF/min_freq\" 2>/dev/null; [ -n \"$OLD_MAX\" ] && echo \"$OLD_MAX\" > \"$MIF/max_freq\" 2>/dev/null; [ -n \"$OLD_UV\" ] && echo \"$OLD_UV\" > \"$UV\" 2>/dev/null; }",
        "trap cleanup EXIT INT TERM",
        "echo 0 > \"$UV\" 2>/dev/null || true",
        "echo 3264000 > \"$MIF/max_freq\" 2>/dev/null || { echo MIF3264_RESULT=MAX_WRITE_FAILED; exit 32; }",
        "echo 3264000 > \"$MIF/min_freq\" 2>/dev/null || { echo MIF3264_RESULT=MIN_WRITE_FAILED; exit 33; }",
        "PASS=0; for i in 1 2 3; do sleep 1; C=$(cat \"$MIF/cur_freq\" 2>/dev/null); echo mif_cur_sample_$i=$C; [ \"$C\" = 3264000 ] && PASS=1; done",
        "if [ \"$PASS\" = 1 ]; then echo MIF3264_RESULT=PASS; else echo MIF3264_RESULT=NO_3264_READBACK; fi"
    );

    @Override protected void onDestroy(){ worker.shutdownNow(); super.onDestroy(); }
}
