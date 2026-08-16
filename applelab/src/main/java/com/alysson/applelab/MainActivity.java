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
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status, output;
    private Button scan, save;
    private volatile String lastReport = "";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        runFullScan();
    }

    @Override protected void onResume() {
        super.onResume();
        if (output != null && !lastReport.isEmpty()) runFullScan();
    }

    private void buildUi() {
        int p=dp(16);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(p,p,p,p);
        TextView title=new TextView(this);
        title.setText("Apple Lab v1.1"); title.setTextSize(30); title.setTypeface(null,1); title.setTextColor(Color.rgb(28,72,160)); root.addView(title);
        TextView sub=new TextView(this);
        sub.setText("G991B • Exynos 2100 • scanner OPP GPU/MIF somente leitura\nNão escreve clock. Não chama lock de OC. 900/3264 só aparecem como REAL se estiverem nas tabelas do kernel.");
        sub.setTextSize(14); sub.setPadding(0,dp(4),0,dp(12)); root.addView(sub);
        status=new TextView(this); status.setText("Solicitando root e lendo kernel..."); status.setTextSize(15); status.setPadding(0,0,0,dp(8)); root.addView(status);
        scan=button("SCAN COMPLETO + READBACK"); save=button("SALVAR DIAGNÓSTICO TXT"); save.setEnabled(false); root.addView(scan); root.addView(save);
        output=new TextView(this); output.setTypeface(android.graphics.Typeface.MONOSPACE); output.setTextSize(11); output.setTextIsSelectable(true); output.setText("Aguardando scan..."); root.addView(output);
        ScrollView sv=new ScrollView(this); sv.addView(root); setContentView(sv);
        scan.setOnClickListener(v->runFullScan()); save.setOnClickListener(v->saveTxt());
    }

    private Button button(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(16); return b; }
    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
    private void busy(boolean b){ scan.setEnabled(!b); save.setEnabled(!b && !lastReport.isEmpty()); }

    private void runFullScan(){
        busy(true); status.setText("Escaneando GPU, devfreq/MIF, CPU, UV, thermal e módulos...");
        worker.submit(()->{
            RootResult rr=root(SCAN_SCRIPT,30);
            String report="APPLE LAB DIAGNOSTIC\nversion=1.1\ntimestamp="+now()+"\nmodel="+Build.MODEL+"\nbuild="+Build.DISPLAY+"\nandroid="+Build.VERSION.RELEASE+" sdk="+Build.VERSION.SDK_INT+"\n\n"+rr.output+"\nscan_exit="+rr.exitCode+"\nEND_REPORT\n";
            lastReport=report;
            runOnUiThread(()->{ output.setText(report); status.setText(verdict(report,rr.exitCode)); busy(false); save.setEnabled(true); });
        });
    }

    private String verdict(String r,int rc){
        if(rc!=0) return "SCAN falhou: exit="+rc;
        boolean g=r.contains("GPU900_OPP_REAL=YES"); boolean m=r.contains("MIF3264_OPP_REAL=YES");
        String gc=value(r,"GPU_TABLE_MAX_KHZ"); String mc=value(r,"MIF_TABLE_MAX_KHZ");
        return "OK • GPU max="+gc+" kHz • GPU900="+(g?"REAL":"não")+" • MIF max="+mc+" kHz • MIF3264="+(m?"REAL":"não");
    }
    private String value(String r,String k){ for(String s:r.split("\\n")) if(s.startsWith(k+"=")) return s.substring(k.length()+1).trim(); return "N/D"; }

    private RootResult root(String cmd,int timeoutSec){
        try{
            java.lang.Process p=new java.lang.ProcessBuilder("su","-c",cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream bos=new ByteArrayOutputStream();
            Thread t=new Thread(()->{try(InputStream in=p.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)bos.write(b,0,n);}catch(IOException ignored){}}); t.start();
            if(!p.waitFor(timeoutSec,TimeUnit.SECONDS)){p.destroyForcibly();t.join(500);return new RootResult(124,"TIMEOUT\n"+bos.toString(StandardCharsets.UTF_8));}
            t.join(1000); return new RootResult(p.exitValue(),bos.toString(StandardCharsets.UTF_8).trim());
        }catch(Exception e){return new RootResult(127,"ROOT_ERR="+e);}
    }

    private void saveTxt(){
        if(lastReport.isEmpty())return;
        String name="AppleLab_v1.1_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".txt";
        ContentValues cv=new ContentValues(); cv.put(MediaStore.Downloads.DISPLAY_NAME,name);cv.put(MediaStore.Downloads.MIME_TYPE,"text/plain");cv.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);if(Build.VERSION.SDK_INT>=29)cv.put(MediaStore.Downloads.IS_PENDING,1);
        Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv); if(u==null){Toast.makeText(this,"Falha ao criar TXT",Toast.LENGTH_LONG).show();return;}
        try(OutputStream os=getContentResolver().openOutputStream(u,"w")){if(os==null)throw new IOException("stream nulo");os.write(lastReport.getBytes(StandardCharsets.UTF_8));}
        catch(Exception e){getContentResolver().delete(u,null,null);Toast.makeText(this,"Erro: "+e,Toast.LENGTH_LONG).show();return;}
        if(Build.VERSION.SDK_INT>=29){ContentValues done=new ContentValues();done.put(MediaStore.Downloads.IS_PENDING,0);getContentResolver().update(u,done,null,null);} Toast.makeText(this,"Salvo: Downloads/"+name,Toast.LENGTH_LONG).show();
    }
    private String now(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date());}
    private static class RootResult{final int exitCode;final String output;RootResult(int c,String o){exitCode=c;output=o==null?"":o;}}

    private static final String SCAN_SCRIPT=String.join("\n",
        "echo root_id=$(id)",
        "echo kernel=$(uname -r)",
        "echo boot_completed=$(getprop sys.boot_completed 2>/dev/null)",
        "echo '=== APPLE STATUS ==='",
        "for f in /sdcard/Download/Apple_G991B_v1.4_Status.txt /sdcard/Download/Apple_G991B_v1.3_Status.txt; do if [ -r \"$f\" ]; then echo status_file=$f; sed -n '1,80p' \"$f\"; break; fi; done",
        "echo '=== CPU POLICIES ==='",
        "for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -d \"$p\" ] || continue; echo CPU_POLICY=$p; for n in scaling_cur_freq scaling_min_freq scaling_max_freq cpuinfo_min_freq cpuinfo_max_freq scaling_available_frequencies scaling_governor; do [ -r \"$p/$n\" ] && echo $n=$(cat \"$p/$n\" 2>/dev/null); done; done",
        "echo '=== GPU CANDIDATES ==='",
        "TMP=/data/local/tmp/applelab_gpu_$$; : > $TMP",
        "for p in /sys/devices/platform/18500000.mali /sys/kernel/gpu /sys/class/misc/mali0/device; do [ -e \"$p\" ] && echo \"$p\" >> $TMP; done",
        "for f in $(find /sys/devices /sys/kernel /sys/class -maxdepth 8 -type f \( -name dvfs_table -o -name asv_table -o -name time_in_state \) 2>/dev/null | head -80); do dirname \"$f\" >> $TMP; done",
        "sort -u $TMP > ${TMP}.u; GCOUNT=0; G900=NO; GMAX=0",
        "while IFS= read -r p; do [ -d \"$p\" ] || continue; HAS=0; for n in clock dvfs_table asv_table dvfs_governor highspeed_clock highspeed_load highspeed_delay polling_speed dvfs_min_lock dvfs_max_lock dvfs_min_lock_status dvfs_max_lock_status utilization time_in_state; do [ -e \"$p/$n\" ] && HAS=1; done; [ $HAS -eq 1 ] || continue; GCOUNT=$((GCOUNT+1)); echo GPU_CANDIDATE_$GCOUNT=$p; echo GPU_REALPATH_$GCOUNT=$(readlink -f \"$p\" 2>/dev/null); for n in clock dvfs_table asv_table dvfs_governor highspeed_clock highspeed_load highspeed_delay polling_speed dvfs_min_lock dvfs_max_lock dvfs_min_lock_status dvfs_max_lock_status utilization time_in_state; do if [ -r \"$p/$n\" ]; then echo ---GPU_${GCOUNT}_${n}---; cat \"$p/$n\" 2>/dev/null | head -80; fi; done; if [ -r \"$p/dvfs_table\" ]; then for x in $(cat \"$p/dvfs_table\" 2>/dev/null | tr ',:/' '   ' | tr -cs '0-9' ' '); do [ \"$x\" = 900000 ] && G900=YES; [ \"$x\" -gt $GMAX ] 2>/dev/null && GMAX=$x; done; fi; done < ${TMP}.u",
        "rm -f $TMP ${TMP}.u; echo GPU_CANDIDATE_COUNT=$GCOUNT; echo GPU_TABLE_MAX_KHZ=$GMAX; echo GPU900_OPP_REAL=$G900",
        "echo '=== DEVFREQ / MIF ==='",
        "DCOUNT=0; MIF=''; M3264=NO; MMAX=0",
        "for d in /sys/class/devfreq/*; do [ -e \"$d\" ] || continue; DCOUNT=$((DCOUNT+1)); rp=$(readlink -f \"$d\" 2>/dev/null); bn=$(basename \"$d\"); nm=$(cat \"$d/name\" 2>/dev/null); echo DEVFREQ_$DCOUNT=$d; echo DEVFREQ_${DCOUNT}_REALPATH=$rp; echo DEVFREQ_${DCOUNT}_NAME=$nm; for n in governor cur_freq min_freq max_freq available_frequencies target_freq; do [ -r \"$d/$n\" ] && echo DEVFREQ_${DCOUNT}_${n}=$(cat \"$d/$n\" 2>/dev/null); done; low=$(printf '%s %s' \"$bn\" \"$nm\" | tr 'A-Z' 'a-z'); mx=$(cat \"$d/max_freq\" 2>/dev/null); case \"$low\" in *mif*|*memory*|*dram*) [ -z \"$MIF\" ] && MIF=$d;; esac; [ -z \"$MIF\" ] && [ \"$mx\" = 3172000 ] && MIF=$d; done",
        "echo DEVFREQ_COUNT=$DCOUNT; echo MIF_CANDIDATE=$MIF",
        "if [ -n \"$MIF\" ]; then echo MIF_REALPATH=$(readlink -f \"$MIF\" 2>/dev/null); AV=$(cat \"$MIF/available_frequencies\" 2>/dev/null); echo MIF_AVAILABLE_FREQUENCIES=$AV; for x in $(printf '%s' \"$AV\" | tr -cs '0-9' ' '); do [ \"$x\" = 3264000 ] && M3264=YES; [ \"$x\" -gt $MMAX ] 2>/dev/null && MMAX=$x; done; [ $MMAX -eq 0 ] && MMAX=$(cat \"$MIF/max_freq\" 2>/dev/null); echo MIF_CUR_KHZ=$(cat \"$MIF/cur_freq\" 2>/dev/null); echo MIF_MIN_KHZ=$(cat \"$MIF/min_freq\" 2>/dev/null); echo MIF_MAX_KHZ=$(cat \"$MIF/max_freq\" 2>/dev/null); fi",
        "echo MIF_TABLE_MAX_KHZ=$MMAX; echo MIF3264_OPP_REAL=$M3264",
        "echo '=== UV READBACK ==='",
        "for n in cpucl0 cpucl1 cpucl2 g3d mif dsu int; do f=/sys/kernel/percent_margin/${n}_margin_percent; printf 'UV_%s=' \"$n\"; cat \"$f\" 2>/dev/null || echo NA; done",
        "echo '=== THERMAL ALL ==='",
        "for z in /sys/class/thermal/thermal_zone*; do [ -r \"$z/type\" ] || continue; echo THERMAL=$(basename \"$z\")'|'$(cat \"$z/type\" 2>/dev/null)'|'$(cat \"$z/temp\" 2>/dev/null); done",
        "echo '=== MALI MODULES ==='",
        "for b in /vendor/lib/modules /vendor_dlkm/lib/modules /odm/lib/modules /lib/modules; do [ -d \"$b\" ] || continue; find \"$b\" -type f \( -name '*mali*.ko' -o -name '*gpu*.ko' \) 2>/dev/null | head -20 | while read f; do echo MODULE=$f; sha256sum \"$f\" 2>/dev/null; done; done",
        "echo scan_state=FINAL_CLOSED"
    );
}
