package com.alysson.bcm4375lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RemoteLab3Activity extends Activity {
    private static final String BASE = "https://bcm4375-remote-lab.vercel.app";
    private static final String FWCLASS = "/sys/module/firmware_class/parameters/path";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, details, log;
    private Button sync, run;
    private volatile JSONObject config;
    private volatile boolean busy;
    private String labId;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        labId = getPreferences(MODE_PRIVATE).getString("lab_id", null);
        if (labId == null) {
            labId = UUID.randomUUID().toString();
            getPreferences(MODE_PRIVATE).edit().putString("lab_id", labId).apply();
        }
        setContentView(buildUi());
    }

    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    private ScrollView buildUi() {
        ScrollView s = new ScrollView(this);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(18), dp(16), dp(30));
        r.setBackgroundColor(Color.rgb(7,10,13));
        s.addView(r);
        r.addView(text("BCM4375 Remote Lab 3", 27, Color.WHITE, true));
        r.addView(text("Nexmon monitor RX • sem reload de firmware", 12, 0xFF80CBC4, false));
        r.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE, 12, 0xFFCFD8DC, false));
        status = text("Carregue o teste de monitor RX.", 14, 0xFFFFD180, true); status.setPadding(0,dp(14),0,dp(10)); r.addView(status);
        details = text("lab_id=" + labId, 11, 0xFFB0BEC5, false); details.setTypeface(Typeface.MONOSPACE); r.addView(details);
        sync = button("1. CARREGAR TESTE MONITOR RX"); sync.setOnClickListener(v -> loadConfig()); r.addView(sync);
        run = button("2. EXECUTAR TESTE ATUAL"); run.setEnabled(false); run.setOnClickListener(v -> confirmRun()); r.addView(run);
        log = text("Nenhum teste executado.", 11, 0xFFE0E0E0, false); log.setTypeface(Typeface.MONOSPACE); log.setTextIsSelectable(true); log.setPadding(0,dp(12),0,0); r.addView(log);
        return s;
    }

    private void loadConfig() {
        if (busy) return;
        try {
            JSONObject c = localMonitorConfig();
            validateConfig(c);
            config = c;
            status.setTextColor(0xFF81C784); status.setText("TESTE MONITOR RX PRONTO");
            details.setText("lab_id="+labId+"\ntest_id="+c.optString("test_id")+" rev="+c.optInt("revision")+"\n"+c.optString("title"));
            log.setText(c.toString()); run.setEnabled(true);
        } catch(Exception e) { fail("FALHA AO CARREGAR", e); }
    }

    private JSONObject localMonitorConfig() throws Exception {
        JSONObject c = new JSONObject();
        c.put("schema",1); c.put("test_id","v44_nexmon_monitor_rx"); c.put("revision",1);
        c.put("title","Nexmon 0x600 confirmado → monitor_on → sniff radiotap → monitor_off");
        JSONArray a = new JSONArray();
        a.put("preflight_nexmon"); a.put("monitor_on"); a.put("probe_monitor"); a.put("sniff_radiotap"); a.put("monitor_off"); a.put("ensure_nexmon_final");
        c.put("operations",a); return c;
    }

    private void validateConfig(JSONObject c) throws Exception {
        if (c.optInt("schema") != 1) throw new Exception("schema não suportado");
        JSONArray ops = c.getJSONArray("operations");
        for (int i=0;i<ops.length();i++) if (!isAllowed(ops.getString(i))) throw new Exception("operação não permitida: "+ops.getString(i));
    }

    private boolean isAllowed(String op) {
        switch(op) {
            case "preflight_nexmon": case "monitor_on": case "probe_monitor": case "sniff_radiotap": case "monitor_off": case "ensure_nexmon_final": return true;
            default: return false;
        }
    }

    private void confirmRun() {
        JSONObject c=config; if(c==null || busy) return;
        new AlertDialog.Builder(this).setTitle("Executar "+c.optString("test_id"))
                .setMessage("Teste passivo: ativa monitor mode do Nexmon por poucos segundos, observa frames e desativa monitor. Não transmite e não recarrega firmware.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar", (d,w)->executeConfig(c)).show();
    }

    private void executeConfig(JSONObject c) {
        if(busy) return;
        setBusy("Executando teste de monitor RX…"); run.setEnabled(false);
        worker.execute(() -> {
            JSONObject report = new JSONObject();
            StringBuilder trace = new StringBuilder();
            boolean success = false;
            try {
                report.put("lab_id",labId); report.put("test_id",c.optString("test_id")); report.put("revision",c.optInt("revision"));
                report.put("model",Build.MODEL); report.put("hardware",Build.HARDWARE); report.put("android",Build.VERSION.RELEASE); report.put("started_ms",System.currentTimeMillis());
                JSONArray ops=c.getJSONArray("operations");
                for(int i=0;i<ops.length();i++) {
                    String op=ops.getString(i); postStatus((i+1)+"/"+ops.length()+" • "+op);
                    String out=executeOp(op);
                    trace.append("\n=== ").append(op).append(" ===\n").append(out).append('\n');
                    if("sniff_radiotap".equals(op) && out.contains("SNIFF_RESULT=RADIOTAP_RX_PRESENT")) success=true;
                }
                report.put("success",success);
            } catch(Exception e) {
                try { report.put("exception",e.getClass().getSimpleName()+": "+e.getMessage()); } catch(Exception ignored) {}
                trace.append("\nEXCEPTION=").append(e).append('\n');
            } finally {
                RootReader.run(nativeProbe()+" wlan0 monitor_off",5);
                RootReader.run("setenforce 1 2>/dev/null || true",4);
                try {
                    String tri=triage().output;
                    report.put("final_nexmon",tri.contains("TRIAGE_RESULT=NEXMON_PRESENT"));
                    report.put("final_selinux",RootReader.run("getenforce 2>&1",3).output.trim());
                    report.put("final_fwclass",RootReader.run("cat "+FWCLASS+" 2>&1",3).output.trim());
                    report.put("triage",tri); report.put("trace",trace.toString()); report.put("finished_ms",System.currentTimeMillis());
                } catch(Exception ignored) {}
            }
            try { Thread.sleep(1200); report.put("upload_response",httpPost(BASE+"/api/report",report.toString())); }
            catch(Exception e) { try { report.put("upload_error",e.toString()); } catch(Exception ignored) {} }
            final boolean ok=report.optBoolean("success",false);
            ui.post(() -> {
                busy=false; sync.setEnabled(true); run.setEnabled(false);
                status.setTextColor(ok?0xFF81C784:0xFFFFD180);
                status.setText(ok?"RADIOTAP RX CONFIRMADO • relatório enviado":"TESTE TERMINOU • relatório enviado");
                log.setText(report.toString());
            });
        });
    }

    private String executeOp(String op) throws Exception {
        switch(op) {
            case "preflight_nexmon": {
                RootReader.Result id=RootReader.run("id",3); RootReader.Result t=triage();
                String se=RootReader.run("getenforce 2>&1",3).output.trim();
                if(!id.output.contains("uid=0") || !t.output.contains("TRIAGE_RESULT=NEXMON_PRESENT") || !"Enforcing".equalsIgnoreCase(se))
                    throw new Exception("preflight bloqueado: root + Nexmon + Enforcing esperados");
                return "ready=true\n"+t.output+"\nSELinux="+se;
            }
            case "monitor_on": {
                RootReader.Result r=RootReader.run(nativeProbe()+" wlan0 monitor_on",6);
                if(!r.output.contains("CONTROL_RESULT=MONITOR_SET_OK")) throw new Exception("monitor_on falhou: "+r.output);
                Thread.sleep(800); return r.output;
            }
            case "probe_monitor": return triage().output;
            case "sniff_radiotap": return RootReader.run(nativeSniff()+" wlan0 6",10).output;
            case "monitor_off": { RootReader.Result r=RootReader.run(nativeProbe()+" wlan0 monitor_off",6); Thread.sleep(800); return r.output; }
            case "ensure_nexmon_final": return triage().output+"\nSELinux="+RootReader.run("getenforce 2>&1",3).output.trim();
            default: throw new Exception("operação não permitida");
        }
    }

    private RootReader.Result triage(){ return RootReader.run(nativeProbe()+" wlan0",6); }
    private String nativeProbe(){ return q(getApplicationInfo().nativeLibraryDir+"/libnexprobe.so"); }
    private String nativeSniff(){ return q(getApplicationInfo().nativeLibraryDir+"/libmonrx.so"); }

    private static String httpPost(String u,String body) throws Exception { HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection(); c.setConnectTimeout(10000); c.setReadTimeout(15000); c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json; charset=utf-8"); try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));} return read(c); }
    private static String read(HttpURLConnection c) throws Exception { int code=c.getResponseCode(); InputStream in=(code>=200&&code<300)?c.getInputStream():c.getErrorStream(); StringBuilder s=new StringBuilder(); try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String l;while((l=r.readLine())!=null)s.append(l);} if(code<200||code>=300) throw new Exception("HTTP "+code+" "+s); return s.toString(); }

    private void setBusy(String s){ busy=true; ui.post(()->{sync.setEnabled(false);run.setEnabled(false);status.setTextColor(0xFFFFD180);status.setText(s);}); }
    private void postStatus(String s){ ui.post(()->status.setText(s)); }
    private void fail(String h,Exception e){ ui.post(()->{busy=false;sync.setEnabled(true);run.setEnabled(false);status.setTextColor(0xFFEF9A9A);status.setText(h);log.setText(e.toString());}); }
    private TextView text(String s,float sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(9);b.setLayoutParams(p);return b;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static String q(String s){return "'"+s.replace("'","'\\''")+"'";}
}
