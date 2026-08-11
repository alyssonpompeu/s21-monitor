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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Permanent client. Remote config only selects from allow-listed operations. */
public class RemoteLabActivity extends Activity {
    private static final String BASE = "https://bcm4375-remote-lab.vercel.app";
    private static final String FWCLASS = "/sys/module/firmware_class/parameters/path";
    private static final String STAGE = "/data/vendor/wifi/bcm4375_remote_lab";
    private static final String NEXMON_SHA = "ec77f799a989e8104322d3c51901685426389c435a968e30d89f134f47c03d0c";

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
        r.addView(text("BCM4375 Remote Lab", 28, Color.WHITE, true));
        r.addView(text("cliente permanente • teste sincronizado online", 12, 0xFF80CBC4, false));
        r.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE, 12, 0xFFCFD8DC, false));
        status = text("Sincronize o teste atual.", 14, 0xFFFFD180, true); status.setPadding(0,dp(14),0,dp(10)); r.addView(status);
        details = text("lab_id=" + labId, 11, 0xFFB0BEC5, false); details.setTypeface(Typeface.MONOSPACE); r.addView(details);
        sync = button("1. SINCRONIZAR TESTE ONLINE"); sync.setOnClickListener(v -> syncConfig()); r.addView(sync);
        run = button("2. EXECUTAR TESTE ATUAL"); run.setEnabled(false); run.setOnClickListener(v -> confirmRun()); r.addView(run);
        log = text("Nenhum teste executado.", 11, 0xFFE0E0E0, false); log.setTypeface(Typeface.MONOSPACE); log.setTextIsSelectable(true); log.setPadding(0,dp(12),0,0); r.addView(log);
        return s;
    }

    private void syncConfig() {
        if (busy) return; setBusy("Consultando backend…");
        worker.execute(() -> {
            try {
                String body = httpGet(BASE + "/api/config");
                JSONObject c = new JSONObject(body);
                validateConfig(c);
                config = c;
                final String shown = c.toString();
                ui.post(() -> {
                    busy=false; sync.setEnabled(true); run.setEnabled(true);
                    status.setTextColor(0xFF81C784); status.setText("TESTE ONLINE PRONTO");
                    details.setText("lab_id=" + labId + "\n" + "test_id=" + c.optString("test_id") + " rev=" + c.optInt("revision") + "\n" + c.optString("title"));
                    log.setText(shown);
                });
            } catch (Exception e) { fail("FALHA AO SINCRONIZAR", e); }
        });
    }

    private void validateConfig(JSONObject c) throws Exception {
        if (c.optInt("schema") != 1) throw new Exception("schema remoto não suportado");
        JSONArray ops = c.getJSONArray("operations");
        for (int i=0;i<ops.length();i++) {
            String op=ops.getString(i);
            if (!isAllowed(op)) throw new Exception("operação remota não permitida: " + op);
        }
        if (!NEXMON_SHA.equalsIgnoreCase(c.optString("expected_nexmon_sha", NEXMON_SHA))) throw new Exception("SHA Nexmon remoto inesperado");
    }

    private boolean isAllowed(String op) {
        switch (op) {
            case "preflight_stock": case "stage_nexmon_vendor_wifi": case "wifi_off": case "load_monitor":
            case "selinux_permissive": case "set_fwclass_stage": case "load_normal": case "wifi_on":
            case "probe_413": case "restore_fwclass": case "selinux_enforcing": case "ensure_safe_final": return true;
            default: return false;
        }
    }

    private void confirmRun() {
        JSONObject c=config; if (c==null || busy) return;
        new AlertDialog.Builder(this).setTitle("Executar " + c.optString("test_id"))
                .setMessage("O teste pode reinicializar o firmware Wi‑Fi e alternar SELinux temporariamente. As ações vêm de uma lista fixa dentro do APK. Execute uma única vez.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar", (d,w) -> executeConfig(c))
                .show();
    }

    private void executeConfig(JSONObject c) {
        if (busy) return; setBusy("Executando teste remoto…"); run.setEnabled(false);
        worker.execute(() -> {
            JSONObject report = new JSONObject();
            StringBuilder tr = new StringBuilder();
            String originalFw = readFwClass();
            String originalSe = RootReader.run("getenforce 2>&1",3).output.trim();
            boolean nexmon=false;
            try {
                report.put("lab_id", labId); report.put("test_id", c.optString("test_id")); report.put("revision", c.optInt("revision"));
                report.put("model", Build.MODEL); report.put("hardware", Build.HARDWARE); report.put("android", Build.VERSION.RELEASE);
                report.put("started_ms", System.currentTimeMillis());
                JSONArray ops=c.getJSONArray("operations");
                for (int i=0;i<ops.length();i++) {
                    String op=ops.getString(i); postStatus((i+1)+"/"+ops.length()+" • "+op);
                    String out=executeOp(op, tr, originalFw);
                    tr.append("\n=== ").append(op).append(" ===\n").append(out).append('\n');
                    if ("probe_413".equals(op)) nexmon = out.contains("TRIAGE_RESULT=NEXMON_PRESENT");
                }
                report.put("success", nexmon);
            } catch (Exception e) {
                try { report.put("exception", e.getClass().getSimpleName()+": "+e.getMessage()); } catch(Exception ignored) {}
                tr.append("\nEXCEPTION=").append(e).append('\n');
            } finally {
                RootReader.run("printf %s " + q(originalFw) + " > " + FWCLASS,4);
                RootReader.run("setenforce 1 2>/dev/null || true",4);
                MonitorController.wifi(true);
                try {
                    String tri=triage().output;
                    boolean finalNex=tri.contains("TRIAGE_RESULT=NEXMON_PRESENT");
                    if (!finalNex && !isStockRuntime()) {
                        MonitorController.setMode("normal"); MonitorController.startSamsungLoader(); MonitorController.wifi(true);
                        MonitorController.waitForFirmware("B1 Network/rsdb",15);
                    }
                    report.put("final_nexmon_413", finalNex);
                    report.put("final_stock_runtime", isStockRuntime());
                    report.put("final_selinux", RootReader.run("getenforce 2>&1",3).output.trim());
                    report.put("final_fwclass", readFwClass());
                    report.put("initial_selinux", originalSe);
                    report.put("wifiver", NexmonOneShotController.wifiver());
                    report.put("triage", tri);
                    report.put("trace", tr.toString());
                    report.put("finished_ms", System.currentTimeMillis());
                } catch(Exception ignored) {}
                RootReader.run("rm -rf " + q(STAGE),5);
            }
            String send="";
            try { send=httpPost(BASE + "/api/report", report.toString()); report.put("upload_response", send); }
            catch(Exception e) { try{report.put("upload_error",e.toString());}catch(Exception ignored){} }
            final boolean ok = report.optBoolean("final_nexmon_413", false);
            ui.post(() -> {
                busy=false; sync.setEnabled(true); run.setEnabled(false);
                status.setTextColor(ok?0xFF81C784:0xFFFFD180);
                status.setText(ok?"NEXMON 413 CONFIRMADO • relatório enviado":"TESTE TERMINOU • relatório enviado; não repita");
                log.setText(report.toString());
            });
        });
    }

    private String executeOp(String op, StringBuilder tr, String originalFw) throws Exception {
        switch (op) {
            case "preflight_stock": {
                RootReader.Result id=RootReader.run("id",4); RootReader.Result tri=triage();
                String se=RootReader.run("getenforce 2>&1",3).output.trim();
                boolean ready=id.output.contains("uid=0") && "SM-G991B".equalsIgnoreCase(Build.MODEL) && "exynos2100".equalsIgnoreCase(Build.HARDWARE)
                        && isStockRuntime() && tri.output.contains("TRIAGE_BASE_IOCTL=SUPPORTED") && !tri.output.contains("TRIAGE_RESULT=NEXMON_PRESENT")
                        && "Enforcing".equalsIgnoreCase(se);
                if(!ready) throw new Exception("preflight bloqueado: stock/base-ioctl/Enforcing esperado");
                return "ready=true\n"+tri.output+"\nSELinux="+se+"\nFWCLASS="+originalFw;
            }
            case "stage_nexmon_vendor_wifi": return stageNexmon();
            case "wifi_off": return MonitorController.wifi(false).output;
            case "load_monitor": {
                Thread.sleep(1200); String a=MonitorController.setMode("monitor").output; String b=MonitorController.startSamsungLoader().output;
                boolean ok=MonitorController.waitForFirmware("B1 Monitor",12); if(!ok) throw new Exception("B1 Monitor não confirmado");
                return a+b+"\nMONITOR_CONFIRMED=true\n"+MonitorController.snapshot("REMOTE MONITOR");
            }
            case "selinux_permissive": {
                RootReader.Result r=RootReader.run("setenforce 0; getenforce",4); if(!r.output.contains("Permissive")) throw new Exception("não entrou em Permissive"); return r.output;
            }
            case "set_fwclass_stage": {
                RootReader.Result r=RootReader.run("printf %s "+q(STAGE)+" > "+FWCLASS+"; cat "+FWCLASS,4); if(!STAGE.equals(r.output.trim())) throw new Exception("fwclass staging falhou"); return r.output;
            }
            case "load_normal": return MonitorController.setMode("normal").output + MonitorController.startSamsungLoader().output;
            case "wifi_on": { Thread.sleep(800); return MonitorController.wifi(true).output; }
            case "probe_413": {
                String last=""; for(int i=0;i<25;i++){Thread.sleep(1000); last=triage().output; if(last.contains("TRIAGE_RESULT=NEXMON_PRESENT")) break;}
                return last + "\n" + NexmonOneShotController.wifiver() + "\n" + RootReader.run("dmesg | grep -iE 'bcmdhd_sta.bin_b1|Request Firmware API|Falling back|error -13|firmware load|nexmon' | tail -250",8).output;
            }
            case "restore_fwclass": return RootReader.run("printf %s "+q(originalFw)+" > "+FWCLASS+"; cat "+FWCLASS,4).output;
            case "selinux_enforcing": return RootReader.run("setenforce 1; getenforce",4).output;
            case "ensure_safe_final": return "selinux="+RootReader.run("getenforce 2>&1",3).output.trim()+"\nfwclass="+readFwClass()+"\n"+triage().output;
            default: throw new Exception("operação não permitida");
        }
    }

    private String stageNexmon() throws Exception {
        File src=new File(getFilesDir(),"bcmdhd_sta_nexmon_18_41_117.bin");
        try(InputStream in=getAssets().open("nexmon/bcmdhd_sta_nexmon_18_41_117.bin"); OutputStream out=new FileOutputStream(src)){
            byte[] b=new byte[65536]; int n; while((n=in.read(b))!=-1) out.write(b,0,n);
        }
        String localSha=sha256(src); if(!NEXMON_SHA.equalsIgnoreCase(localSha)) throw new Exception("asset Nexmon SHA inválido");
        String cmd="rm -rf "+q(STAGE)+"; mkdir -p "+q(STAGE)+"; cp "+q(src.getAbsolutePath())+" "+q(STAGE+"/bcmdhd_sta.bin_b1")+"; "
                +"cp /vendor/firmware/bcmdhd_clm.blob "+q(STAGE+"/bcmdhd_clm.blob")+"; chown -R wifi:wifi "+q(STAGE)+"; chmod 0755 "+q(STAGE)+"; chmod 0644 "+q(STAGE)+"/*; restorecon -RF "+q(STAGE)+" 2>&1; sha256sum "+q(STAGE+"/bcmdhd_sta.bin_b1")+"; ls -ldZ "+q(STAGE)+"; ls -lZ "+q(STAGE);
        RootReader.Result r=RootReader.run(cmd,12); if(r.code!=0 || !r.output.toLowerCase(Locale.ROOT).contains(NEXMON_SHA)) throw new Exception("staging Nexmon falhou"); return r.output;
    }

    private RootReader.Result triage(){ return RootReader.run(q(getApplicationInfo().nativeLibraryDir+"/libnexprobe.so")+" wlan0",6); }
    private boolean isStockRuntime(){ return NexmonOneShotController.STOCK_SHA.equalsIgnoreCase(NexmonOneShotController.currentFirmwareSha()) && NexmonOneShotController.wifiver().contains("B1 Network/rsdb"); }
    private String readFwClass(){ return RootReader.run("cat "+FWCLASS+" 2>&1",4).output.trim(); }

    private static String httpGet(String u) throws Exception { HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection(); c.setConnectTimeout(10000); c.setReadTimeout(10000); c.setRequestMethod("GET"); return read(c); }
    private static String httpPost(String u,String body) throws Exception { HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection(); c.setConnectTimeout(10000); c.setReadTimeout(15000); c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json; charset=utf-8"); try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));} return read(c); }
    private static String read(HttpURLConnection c) throws Exception { int code=c.getResponseCode(); InputStream in=(code>=200&&code<300)?c.getInputStream():c.getErrorStream(); StringBuilder s=new StringBuilder(); try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String l;while((l=r.readLine())!=null)s.append(l);} if(code<200||code>=300)throw new Exception("HTTP "+code+" "+s); return s.toString(); }
    private static String sha256(File f) throws Exception { MessageDigest md=MessageDigest.getInstance("SHA-256"); try(InputStream in=new java.io.FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);} StringBuilder s=new StringBuilder();for(byte x:md.digest())s.append(String.format(Locale.US,"%02x",x));return s.toString(); }
    private void setBusy(String s){busy=true;sync.setEnabled(false);run.setEnabled(false);status.setTextColor(0xFFFFD180);status.setText(s);} private void postStatus(String s){ui.post(()->status.setText(s));}
    private void fail(String t,Exception e){ui.post(()->{busy=false;sync.setEnabled(true);run.setEnabled(false);status.setTextColor(0xFFEF9A9A);status.setText(t);log.setText(e.toString());});}
    private Button button(String s){Button b=new Button(this);b.setText(s);return b;} private TextView text(String s,int sp,int c,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(c);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;} private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+0.5f);} private static String q(String s){return "'"+s.replace("'","'\\''")+"'";}
}
