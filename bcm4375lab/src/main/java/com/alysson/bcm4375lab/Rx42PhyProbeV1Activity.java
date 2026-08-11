package com.alysson.bcm4375lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v3 app probe: loads a BCM4375B1 Nexmon firmware containing 0x630 + 0x631.
 * 0x631 performs only a bounded Template RAM write/read/restore while sample playback is OFF.
 * It never invokes sample playback and never intentionally transmits RF.
 */
public class Rx42PhyProbeV1Activity extends Activity {
    private static final String BASE = "https://bcm4375-remote-lab.vercel.app";
    private static final String FWCLASS = "/sys/module/firmware_class/parameters/path";
    private static final String STAGE = "/data/vendor/wifi/rx42_tplramprobe_v2";
    private static final String ASSET = "nexmon/bcmdhd_sta_rx42_tplram_probe_v2.bin";
    private static final String EXPECTED_SHA = "TPLRAM_PROBE_V2_SHA";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, log;
    private Button run;
    private volatile boolean busy;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView s = new ScrollView(this);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(22), dp(24), dp(22), dp(28));
        r.setBackgroundColor(0xFF071014);
        s.addView(r);

        r.addView(text("RX42 Template RAM Probe v2", 28, Color.WHITE, true));
        r.addView(text("BCM4375B1 • write/read/restore • SAMPLE PLAYBACK OFF", 13, 0xFF80CBC4, false));
        r.addView(text("Este teste escreve só 4 palavras numa área de scratch da Template RAM, lê de volta e restaura o conteúdo original. O firmware aborta se sample playback já estiver ativo. Não chama rotina de TX.", 13, 0xFFCFD8DC, false));
        status = text("Pronto para round-trip controlado sem TX.", 15, 0xFFFFD180, true);
        status.setPadding(0, dp(18), 0, dp(12));
        r.addView(status);

        run = new Button(this);
        run.setText("CARREGAR FIRMWARE + TESTAR 0x630/0x631");
        run.setAllCaps(false);
        run.setOnClickListener(v -> confirmRun());
        r.addView(run);

        log = text("Nenhum teste executado.", 11, 0xFFE0E0E0, false);
        log.setPadding(0, dp(16), 0, 0);
        log.setTextIsSelectable(true);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        r.addView(log);
        return s;
    }

    private void confirmRun() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Testar Template RAM sem TX?")
                .setMessage("O Wi-Fi será reiniciado. SELinux ficará Permissive apenas durante o carregamento do firmware e voltará a Enforcing. O 0x631 exige SAMPLE_PLAY_CTRL=0, salva 4 palavras, escreve um padrão, lê, restaura e verifica. Nenhuma rotina de sample playback/TX é chamada.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar", (d,w) -> execute()).show();
    }

    private void execute() {
        busy = true; run.setEnabled(false); status.setText("Executando…");
        worker.execute(() -> {
            StringBuilder tr = new StringBuilder();
            JSONObject report = new JSONObject();
            boolean success = false;
            String originalPath = "/vendor/firmware";
            try {
                report.put("test_id", "rx42_tplram_v2_roundtrip_0631_no_tx");
                report.put("model", android.os.Build.MODEL);
                report.put("hardware", android.os.Build.HARDWARE);
                report.put("android", android.os.Build.VERSION.RELEASE);
                report.put("started_ms", System.currentTimeMillis());

                RootReader.Result id = RootReader.run("id", 4);
                String se = rr("getenforce 2>&1", 4).trim();
                originalPath = rr("cat " + FWCLASS + " 2>/dev/null", 4).trim();
                if (originalPath.isEmpty()) originalPath = "/vendor/firmware";
                tr.append("=== PREFLIGHT ===\nroot=").append(id.output.contains("uid=0"))
                  .append("\nSELinux=").append(se)
                  .append("\nfwclass=").append(originalPath)
                  .append("\nwifiver=\n").append(rr("cat /sys/wifi/wifiver 2>/dev/null", 4)).append('\n');
                if (!id.output.contains("uid=0") || !"Enforcing".equalsIgnoreCase(se))
                    throw new Exception("preflight: root + SELinux Enforcing necessários");

                post("Extraindo firmware Template RAM Probe…");
                File src = new File(getFilesDir(), "bcmdhd_sta_rx42_tplram_probe_v2.bin");
                copyAsset(ASSET, src);
                String qsrc = q(src.getAbsolutePath());
                String stageCmd = "rm -rf " + q(STAGE) + "; mkdir -p " + q(STAGE) +
                        "; cp " + qsrc + " " + q(STAGE + "/bcmdhd_sta.bin_b1") +
                        "; cp /vendor/firmware/bcmdhd_clm.blob " + q(STAGE + "/bcmdhd_clm.blob") +
                        "; chown -R wifi:wifi " + q(STAGE) +
                        "; chmod 0755 " + q(STAGE) +
                        "; chmod 0644 " + q(STAGE + "/bcmdhd_sta.bin_b1") + " " + q(STAGE + "/bcmdhd_clm.blob") +
                        "; restorecon -RF " + q(STAGE) + " 2>&1 || true";
                tr.append("=== STAGE ===\n").append(rr(stageCmd, 8));
                String stagedSha = rr("sha256sum " + q(STAGE + "/bcmdhd_sta.bin_b1") + " | awk '{print $1}'", 4).trim();
                tr.append("staged_sha=").append(stagedSha).append('\n');
                if (!EXPECTED_SHA.equalsIgnoreCase(stagedSha)) throw new Exception("SHA do firmware experimental não confere");

                post("Entrando em Samsung B1 Monitor…");
                rr("svc wifi disable; sleep 2; setprop vendor.wlandriver.mode monitor; setprop ctl.start mfgloader; sleep 3", 9);
                String mon = rr("cat /sys/wifi/wifiver 2>/dev/null", 4);
                boolean monitorLoaded = mon.contains("B1 Monitor");
                tr.append("=== MONITOR ===\n").append(mon).append("MONITOR_CONFIRMED=").append(monitorLoaded).append('\n');
                if (!monitorLoaded) throw new Exception("B1 Monitor não confirmado");

                post("Carregando Nexmon Template RAM Probe…");
                rr("printf '%s' " + q(STAGE) + " > " + FWCLASS, 4);
                String setPath = rr("cat " + FWCLASS + " 2>/dev/null", 3).trim();
                tr.append("FWCLASS_STAGE=").append(setPath).append('\n');
                if (!STAGE.equals(setPath)) throw new Exception("firmware_class.path não aceitou staging");

                rr("setenforce 0", 3);
                String permissive = rr("getenforce 2>&1", 3).trim();
                tr.append("SELINUX_LOAD=").append(permissive).append('\n');
                if (!"Permissive".equalsIgnoreCase(permissive)) throw new Exception("não entrou em Permissive");

                rr("setprop vendor.wlandriver.mode normal; setprop ctl.start mfgloader; sleep 3; svc wifi enable; sleep 4", 12);
                String net = rr("cat /sys/wifi/wifiver 2>/dev/null", 4);
                tr.append("=== EXPERIMENTAL NETWORK ===\n").append(net).append('\n');

                post("Executando 0x630 + 0x631 sem TX…");
                String probe = rr(nativeProbe() + " wlan0", 9);
                tr.append("=== IOCTL 0x630 + 0x631 ===\n").append(probe).append('\n');
                success = probe.contains("RX42_TPLRAM_USER_PROBE=PASS_NO_TX") &&
                          probe.contains("TPLRAM_RESULT=PASS") &&
                          probe.contains("WRITE_READBACK_OK=1") &&
                          probe.contains("RESTORE_OK=1") &&
                          probe.contains("PLAYBACK_STAYED_OFF=1") &&
                          probe.contains("TX_TRIGGERED=0");
                report.put("probe_output", probe);
                report.put("success", success);
                report.put("experimental_sha", EXPECTED_SHA);
            } catch (Exception e) {
                try { report.put("exception", e.getClass().getSimpleName() + ": " + e.getMessage()); } catch(Exception ignored) {}
                tr.append("EXCEPTION=").append(e).append('\n');
            } finally {
                try {
                    rr("printf '%s' " + q(originalPath) + " > " + FWCLASS + "; setenforce 1", 5);
                    if (!success) {
                        post("Falhou; restaurando firmware normal…");
                        rr("svc wifi disable; sleep 2; setprop vendor.wlandriver.mode normal; setprop ctl.start mfgloader; sleep 3; svc wifi enable; sleep 3", 11);
                    }
                    String finalSe = rr("getenforce 2>&1", 3).trim();
                    String finalPath = rr("cat " + FWCLASS + " 2>/dev/null", 3).trim();
                    String finalWifi = rr("cat /sys/wifi/wifiver 2>/dev/null", 4);
                    String finalProbe = rr(nativeProbe() + " wlan0", 9);
                    tr.append("=== FINAL ===\nSELinux=").append(finalSe)
                      .append("\nfwclass=").append(finalPath)
                      .append("\nwifiver=\n").append(finalWifi)
                      .append("\nprobe=\n").append(finalProbe).append('\n');
                    rr("rm -rf " + q(STAGE), 4);
                    report.put("final_selinux", finalSe);
                    report.put("final_fwclass", finalPath);
                    report.put("final_probe", finalProbe);
                    report.put("trace", tr.toString());
                    report.put("finished_ms", System.currentTimeMillis());
                } catch(Exception ignored) {}
            }

            try { postReport(report); } catch(Exception e) { tr.append("UPLOAD_ERROR=").append(e).append('\n'); }
            boolean ok = success;
            String shown = tr.toString();
            ui.post(() -> {
                busy = false; run.setEnabled(false);
                status.setTextColor(ok ? 0xFF81C784 : 0xFFEF9A9A);
                status.setText(ok ? "TEMPLATE RAM ROUND-TRIP OK • PLAYBACK OFF • SEM TX" : "PROVA NÃO CONFIRMADA • estado seguro restaurado");
                log.setText(shown);
            });
        });
    }

    private String nativeProbe() { return q(getApplicationInfo().nativeLibraryDir + "/libsdrrx42probe.so"); }
    private String rr(String cmd, long timeout) { return RootReader.run(cmd, timeout).output; }
    private void post(String s) { ui.post(() -> status.setText(s)); }

    private void copyAsset(String asset, File dst) throws Exception {
        try (InputStream in = getAssets().open(asset); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        dst.setReadable(true, false);
    }

    private static void postReport(JSONObject j) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/api/report").openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream o = c.getOutputStream()) { o.write(j.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8))) {
            while (br.readLine() != null) {}
        }
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); return t;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
